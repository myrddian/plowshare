package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable bounded pages and transactional outbox acceptance stay independent of native execution.
 */
public final class RelayMessageRelayOutbox implements MessageRelayOutbox {
  private static final Logger LOG = LoggerFactory.getLogger(RelayMessageRelayOutbox.class);
  private final MessageRelayRepository repository;
  private final MessageRelayPages pages;
  private final MessageRelayAccess access;
  private final Relay relay;
  private final UnitOfWork work;

  public RelayMessageRelayOutbox(
      MessageRelayRepository repository,
      MessageRelayPages pages,
      MessageRelayAccess access,
      Relay relay,
      UnitOfWork work) {
    this.repository = Objects.requireNonNull(repository);
    this.pages = Objects.requireNonNull(pages);
    this.access = Objects.requireNonNull(access);
    this.relay = Objects.requireNonNull(relay);
    this.work = Objects.requireNonNull(work);
  }

  @Override
  public boolean captureNext() {
    try {
      return work.inTransaction(
          () -> {
            var next = repository.nextTask();
            if (next.isEmpty()) return false;
            var binding = next.orElseThrow();
            try {
              access.require(binding.family());
              var snapshot =
                  pages.snapshot(
                      binding.family(),
                      binding.client(),
                      binding.task(),
                      binding.requestId(),
                      binding.sourceEvent(),
                      binding.cursor());
              repository.update(binding, snapshot);
              return true;
            } catch (RuntimeException failure) {
              throw new CaptureFailed(binding, failure);
            }
          });
    } catch (CaptureFailed failure) {
      var binding = failure.binding;
      LOG.warn(
          "Native snapshot category=CAPTURE_FAILED task={} project={} topic={}; inspect retained evidence",
          binding.task(),
          binding.family().projectId(),
          binding.family().replyTopic());
      work.inTransaction(
          () -> {
            repository.deferTask(binding);
            return null;
          });
      return true;
    }
  }

  @Override
  public boolean publishNext() {
    try {
      return work.inTransaction(
          () -> {
            var next = repository.pending();
            if (next.isEmpty()) return false;
            var operation = next.orElseThrow();
            try {
              // The immutable operation is locked first, then its task before any broker topic.
              // Capture cannot select a binding with an unpublished predecessor, and only inserts
              // a distinct update identity; it never locks this existing operation while holding t.
              var fence = Objects.requireNonNull(repository.lockTask(operation));
              if (fence == MessageRelayRepository.TaskFence.UNAVAILABLE) {
                repository.unavailable(operation, "TASK_UNAVAILABLE");
                return true;
              }
              access.require(operation.family());
              var result =
                  relay.publish(
                      operation.family().replies(),
                      operation.family().replyGeneration(),
                      new Relay.Draft(
                          "message.task:" + RelayPort.hash(operation.identity()),
                          MessageRelayPayload.PUBLISHER,
                          operation.occurredAt(),
                          operation.snapshot().requestId().toString(),
                          null,
                          new RelayPayload.Text(MessageRelayCodec.write(operation.snapshot())),
                          operation.causation()));
              if (result instanceof Relay.Appended) repository.published(operation);
              else if (result instanceof Relay.DestinationUnavailable unavailable)
                repository.unavailable(operation, unavailable.reason().name());
              else throw new IllegalStateException("Native snapshot append returned no outcome");
              return true;
            } catch (RuntimeException failure) {
              throw new PublishFailed(operation, failure);
            }
          });
    } catch (PublishFailed failure) {
      var operation = failure.operation;
      LOG.warn(
          "Native snapshot category=PUBLISH_FAILED operation={} project={} topic={}; inspect retained evidence",
          operation.identity(),
          operation.family().projectId(),
          operation.family().replyTopic());
      work.inTransaction(
          () -> {
            repository.defer(operation);
            return null;
          });
      return true;
    }
  }

  private static final class CaptureFailed extends RuntimeException {
    final MessageRelayRepository.Binding binding;

    CaptureFailed(MessageRelayRepository.Binding binding, RuntimeException cause) {
      super(cause);
      this.binding = binding;
    }
  }

  private static final class PublishFailed extends RuntimeException {
    final MessageRelayRepository.Operation operation;

    PublishFailed(MessageRelayRepository.Operation operation, RuntimeException cause) {
      super(cause);
      this.operation = operation;
    }
  }
}
