package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.a2a.Ingress;
import io.aeyer.plowshare.protocol.RelayPort;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Independent wire DTOs for general messaging receivers on existing Relay TEXT ports. */
public final class IngressWire {
  private IngressWire() {}

  public enum Action {
    RECEIVE,
    INSPECT,
    CANCEL
  }

  public record Receive(
      String schema, String requestId, String client, String context, String body, String command) {
    public Receive {
      checkSchema(schema, "plowshare-message-request/1");
      uuid(requestId);
      checkClient(client);
      if (context != null) uuid(context);
      new Ingress.Receive(
          "validation",
          client,
          "validation",
          UUID.fromString(requestId),
          context == null ? null : UUID.fromString(context),
          body,
          command,
          null);
    }
  }

  public record Inspect(
      String schema,
      String requestId,
      String client,
      String task,
      String originalRequest,
      String cursor) {
    public Inspect {
      checkSchema(schema, "plowshare-message-inspect/1");
      uuid(requestId);
      checkClient(client);
      if ((task == null) == (originalRequest == null))
        throw new IllegalArgumentException("Inspect needs exactly one selector");
      if (task != null) uuid(task);
      if (originalRequest != null) uuid(originalRequest);
      if (cursor != null) RelayWork.identity(cursor);
    }
  }

  public record Cancel(String schema, String requestId, String client, String task) {
    public Cancel {
      checkSchema(schema, "plowshare-message-cancel/1");
      uuid(requestId);
      checkClient(client);
      uuid(task);
    }
  }

  public record Task(
      String id,
      String context,
      String requestId,
      String agent,
      String message,
      String state,
      String ending,
      List<Chunk> replies,
      Instant createdAt) {
    public Task {
      uuid(id);
      uuid(context);
      uuid(requestId);
      RelayWork.identity(agent);
      RelayWork.identity(message);
      new Ingress.Task(
          UUID.fromString(id),
          UUID.fromString(context),
          agent,
          message,
          state,
          ending,
          null,
          List.of(),
          createdAt);
      if (replies.size() > 2) throw new IllegalArgumentException("Task page has too many chunks");
      replies = List.copyOf(replies);
    }

    public Ingress.Task projection(Ingress.Source source, List<Ingress.Reply> replies) {
      return new Ingress.Task(
          UUID.fromString(id),
          UUID.fromString(context),
          agent,
          message,
          state,
          ending,
          source,
          replies,
          createdAt);
    }
  }

  public record Chunk(
      String id,
      String body,
      String offset,
      boolean complete,
      boolean finalReply,
      boolean generated,
      String ending,
      Instant postedAt) {
    public Chunk {
      RelayWork.identity(id);
      RelayWork.text(body, 32768);
      if (body.codePointCount(0, body.length()) > 16384)
        throw new IllegalArgumentException("Reply chunk exceeds16384codepoints");
      if (offset == null || !offset.matches("0|[1-9][0-9]{0,18}"))
        throw new IllegalArgumentException("Invalid reply offset");
      Long.parseLong(offset);
      new Ingress.ReplyMetadata(id, ending, postedAt);
    }
  }

  public record Snapshot(
      String schema,
      String requestId,
      String sourceEvent,
      String client,
      String revision,
      Task task,
      String refusal,
      String next,
      String resume) {
    public Snapshot {
      checkSchema(schema, "plowshare-message-task/1");
      uuid(requestId);
      uuid(sourceEvent);
      checkClient(client);
      if (next != null) RelayWork.identity(next);
      if (resume != null) RelayWork.identity(resume);
      if (refusal == null) {
        Objects.requireNonNull(task);
        if ((next == null) == (resume == null))
          throw new IllegalArgumentException("Positive page needs exactly one next/resume cursor");
        if (revision == null || !revision.matches("[1-9][0-9]{0,18}"))
          throw new IllegalArgumentException("Positive task revision required");
        Long.parseLong(revision);
      } else if (task != null
          || revision != null
          || next != null
          || resume != null
          || !Set.of("NOT_ACCESSIBLE", "REQUEST_CONFLICT", "COMMAND_REFUSED", "INVALID_REQUEST")
              .contains(refusal)) throw new IllegalArgumentException("Invalid refusal evidence");
    }
  }

  public record Return(
      String schema, String requestId, String sourceEvent, String body, boolean finalReply) {
    public Return {
      checkSchema(schema, "plowshare-message-return/1");
      uuid(requestId);
      uuid(sourceEvent);
      RelayPort.text(body, 1024 * 1024);
      if (body.isBlank()) throw new IllegalArgumentException("Return body required");
    }
  }

  static String uuid(String value) {
    if (!UUID.fromString(value).toString().equals(value))
      throw new IllegalArgumentException("Canonical UUID required");
    return value;
  }

  static String checkClient(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_.-]{1,64}"))
      throw new IllegalArgumentException("Invalid authenticated client alias");
    return value;
  }

  private static void checkSchema(String value, String expected) {
    if (!expected.equals(value))
      throw new IllegalArgumentException("Unsupported messaging wire schema");
  }
}
