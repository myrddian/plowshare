package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Calls native messaging once under its owning locks; broker uncertainty only reads its receipt.
 */
public final class RelayMessageIngress implements MessageRelayIngress {
  private final MessageIngress messages;
  private final MessageRelayRepository repository;
  private final MessageRelayPages pages;
  private final MessageRelayDefinitions definitions;
  private final UnitOfWork work;
  private final MessageRelayProperties properties;
  private final Runnable signal;

  public RelayMessageIngress(
      MessageIngress messages,
      MessageRelayRepository repository,
      MessageRelayPages pages,
      MessageRelayDefinitions definitions,
      UnitOfWork work,
      MessageRelayProperties properties,
      Runnable signal) {
    this.signal = Objects.requireNonNull(signal);
    this.messages = Objects.requireNonNull(messages);
    this.repository = Objects.requireNonNull(repository);
    this.properties = Objects.requireNonNull(properties);
    this.pages = Objects.requireNonNull(pages);
    this.definitions = Objects.requireNonNull(definitions);
    this.work = Objects.requireNonNull(work);
  }

  @Override
  public MessageRelayPayload.Snapshot accept(Request request) {
    try {
      return work.inTransaction(() -> apply(request));
    } catch (MessageRelayRepository.Conflict conflict) {
      return refused(request, "REQUEST_CONFLICT");
    } catch (MessageRefused denied) {
      return refused(request, "NOT_ACCESSIBLE");
    }
  }

  private MessageRelayPayload.Snapshot apply(Request request) {
    var intent = request.intent();
    var family = intent.family();
    var command = request.command();
    repository.retainFamily(family);
    var prior = repository.prior(intent);
    if (prior.isPresent()) return prior.orElseThrow();
    if (properties.permitsMigration(family, command.client()))
      repository.migrate(family, command.client());
    MessageRelayPayload.Snapshot snapshot;
    if (command instanceof MessageRelayPayload.Receive receive) {
      try {
        definitions.require(family, receive.command());
      } catch (CallerFault denied) {
        return retainRefusal(request, "COMMAND_REFUSED");
      }
      var nativeRequest =
          new MessageIngress.Request(
              family.project(),
              family.namespace(),
              receive.client(),
              family.agent(),
              receive.requestId(),
              receive.context(),
              receive.body(),
              receive.command(),
              new MessageIngress.Source("relay", intent.sourceEvent(), intent.fingerprint()),
              intent.causation());
      var admitted = messages.receive(family.account(), nativeRequest);
      snapshot =
          pages.admitted(admitted, command.client(), command.requestId(), intent.sourceEvent());
      repository.bind(intent, admitted.id(), progress(snapshot), admitted.revision());
    } else {
      UUID task;
      String cursor = null;
      if (command instanceof MessageRelayPayload.Cancel cancel) {
        task =
            repository
                .task(family, command.client(), cancel.task(), null)
                .orElseThrow(() -> new MessageRefused("No accessible native task"));
        messages.cancel(
            family.account(), family.project(), family.namespace(), command.client(), task);
      } else if (command instanceof MessageRelayPayload.Inspect inspect) {
        task =
            repository
                .task(family, command.client(), inspect.task(), inspect.originalRequest())
                .orElseThrow(() -> new MessageRefused("No accessible native task"));
        cursor = inspect.cursor();
      } else throw new IllegalArgumentException("Unsupported native control");
      snapshot =
          pages.snapshot(
              family, command.client(), task, command.requestId(), intent.sourceEvent(), cursor);
      // A configured legacy adoption has no historical Relay receive event. Its first authorized
      // inspection becomes the retained notification binding; the native task/context IDs stay.
      if (properties.permitsMigration(family, command.client()))
        repository.bind(intent, task, progress(snapshot), Long.parseLong(snapshot.revision()));
    }
    repository.retain(intent, snapshot);
    work.afterCommit(signal);
    return snapshot;
  }

  private MessageRelayPayload.Snapshot refused(Request request, String code) {
    // A failed participating native transaction is rollback-only. Negative evidence belongs in a
    // fresh transaction after that rollback; it never hides a committed positive receipt.
    return work.inTransaction(
        () -> {
          var committed = repository.receipt(request.intent().delivery());
          if (committed.isPresent()) return committed.orElseThrow();
          repository.retainFamily(request.intent().family());
          try {
            var prior = repository.prior(request.intent());
            if (prior.isPresent()) return prior.orElseThrow();
          } catch (MessageRelayRepository.Conflict ignored) {
            if (!code.equals("REQUEST_CONFLICT")) throw ignored;
          }
          return retainRefusal(request, code);
        });
  }

  private MessageRelayPayload.Snapshot retainRefusal(Request request, String code) {
    var intent = request.intent();
    var snapshot =
        new MessageRelayPayload.Snapshot(
            "plowshare-message-task/1",
            intent.requestId(),
            intent.sourceEvent(),
            intent.client(),
            null,
            null,
            code,
            null,
            null);
    repository.retain(intent, snapshot);
    work.afterCommit(signal);
    return snapshot;
  }

  @Override
  public Optional<MessageRelayPayload.Snapshot> inspect(
      UUID delivery, long project, String publisher, String topic, String event) {
    return repository.receipt(delivery, project, publisher, topic, event);
  }

  private static String progress(MessageRelayPayload.Snapshot snapshot) {
    return snapshot.next() == null ? snapshot.resume() : snapshot.next();
  }
}
