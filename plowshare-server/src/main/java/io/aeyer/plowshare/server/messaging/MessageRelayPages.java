package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Each bounded page is one current native snapshot; immutable bodies support durable continuation.
 */
public final class MessageRelayPages {
  private final MessageIngress messages;
  private final MessageRelayRepository repository;
  private final UnitOfWork work;

  public MessageRelayPages(
      MessageIngress messages, MessageRelayRepository repository, UnitOfWork work) {
    this.messages = Objects.requireNonNull(messages);
    this.repository = Objects.requireNonNull(repository);
    this.work = Objects.requireNonNull(work);
  }

  public MessageRelayPayload.Snapshot snapshot(
      MessageRelayRepository.Family family,
      String client,
      UUID task,
      UUID operation,
      String sourceEvent,
      String cursor) {
    return work.inTransaction(
        () -> {
          var view =
              messages.summary(
                  family.account(), family.project(), family.namespace(), client, task);
          return build(view, client, operation, sourceEvent, cursor);
        });
  }

  /** Reuses the authorized, revision-locked admission result in its same owning transaction. */
  public MessageRelayPayload.Snapshot admitted(
      MessageIngress.Task view, String client, UUID operation, String sourceEvent) {
    return build(view, client, operation, sourceEvent, null);
  }

  private MessageRelayPayload.Snapshot build(
      MessageIngress.Task view, String client, UUID operation, String sourceEvent, String cursor) {
    UUID task = view.id();
    var start =
        cursor == null
            ? new MessageRelayPayload.Cursor(task, 0, 0, 0, true)
            : MessageRelayPayload.Cursor.read(cursor);
    if (!start.task().equals(task))
      throw new MessageRefused("The native history cursor belongs to another task");
    long through = start.follow() ? repository.head(view.message()) : start.through();
    if (start.sequence() > through)
      throw new MessageRefused("The native history cursor exceeds retained history");
    List<MessageRelayPayload.Reply> replies = new ArrayList<>();
    long sequence = start.sequence();
    int offset = start.offset();
    String next = null;
    var rows = repository.chunks(view.message(), through, sequence, offset);
    if (rows.isEmpty() && sequence < through)
      throw new MessageRefused("The native history cursor has no retained page");
    for (var row : rows) {
      int begin = row.sequence() == start.sequence() ? start.offset() : 0;
      if (begin >= row.length())
        throw new MessageRefused("The native history cursor exceeds its reply body");
      int count = row.body().codePointCount(0, row.body().length());
      if (count < 1 || begin + count > row.length())
        throw new IllegalStateException("Invalid retained native reply slice");
      boolean complete = begin + count == row.length();
      replies.add(
          new MessageRelayPayload.Reply(
              row.message(),
              row.body(),
              Integer.toString(begin),
              complete,
              row.finalReply(),
              row.generated(),
              row.ending(),
              row.postedAt()));
      sequence = row.sequence();
      if (!complete) {
        next =
            new MessageRelayPayload.Cursor(task, through, sequence, begin + count, false).encode();
        break;
      }
      if (sequence < through)
        next = new MessageRelayPayload.Cursor(task, through, sequence, 0, false).encode();
      else next = null;
    }
    String resume =
        next == null ? new MessageRelayPayload.Cursor(task, 0, through, 0, true).encode() : null;
    var snapshotTask =
        new MessageRelayPayload.Task(
            view.id(),
            view.context(),
            view.requestId(),
            view.agent(),
            view.message(),
            view.state(),
            view.ending(),
            replies,
            view.createdAt());
    return new MessageRelayPayload.Snapshot(
        "plowshare-message-task/1",
        operation,
        sourceEvent,
        client,
        Long.toString(view.revision()),
        snapshotTask,
        null,
        next,
        resume);
  }
}
