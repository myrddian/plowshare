package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * An assembled Ex frame owns a bounded completion slot before queueing. The queue retains this
 * exact token until physical execution and reply settlement; it never adds a workflow deadline.
 * Reply acknowledgement is bridged to the physical dispatcher when execution starts.
 */
final class QueuedExternalConversationRequest implements AutoCloseable {
  private final ExternalConversationCompletions.Request completion;
  private final DeliveryState delivery;
  private final Asking asking;
  private final RequestSessionValidity session;

  static QueuedExternalConversationRequest begin(
      ExternalConversationRequestWork work,
      long admittedAtNanos,
      Asking asking,
      RequestSessionValidity session,
      BiConsumer<Outcome, Runnable> notify) {
    var state = new DeliveryState();
    var completion =
        work.begin(admittedAtNanos, outcome -> notify.accept(outcome, state::delivered));
    return new QueuedExternalConversationRequest(completion, state, asking, session);
  }

  private QueuedExternalConversationRequest(
      ExternalConversationCompletions.Request completion,
      DeliveryState delivery,
      Asking asking,
      RequestSessionValidity session) {
    this.completion = completion;
    this.delivery = delivery;
    this.asking = asking;
    this.session = session;
  }

  Asking asking() {
    return asking;
  }

  boolean verify() {
    return session == null || session.verify();
  }

  void bind(ConcurrentRequests.Delivery physical) {
    delivery.bind(physical);
  }

  <T> T within(Supplier<T> action) {
    return completion.within(action);
  }

  void answer(Outcome outcome) {
    completion.answer(outcome);
  }

  @Override
  public void close() {
    completion.close();
  }

  private static final class DeliveryState {
    private final AtomicReference<ConcurrentRequests.Delivery> physical = new AtomicReference<>();
    private final AtomicBoolean delivered = new AtomicBoolean();
    private final AtomicBoolean acknowledged = new AtomicBoolean();

    void bind(ConcurrentRequests.Delivery value) {
      if (!physical.compareAndSet(null, value))
        throw new IllegalStateException("External request already has a physical dispatcher");
      acknowledge();
    }

    void delivered() {
      delivered.set(true);
      acknowledge();
    }

    private void acknowledge() {
      var value = physical.get();
      if (value != null && delivered.get() && acknowledged.compareAndSet(false, true)) value.run();
    }
  }
}
