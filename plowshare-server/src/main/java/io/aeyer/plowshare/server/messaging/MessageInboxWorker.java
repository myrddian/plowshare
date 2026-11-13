package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.relay.RelayWorkerProperties;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded, coalesced admission after commit; periodic discovery remains the recovery authority. */
public final class MessageInboxWorker implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(MessageInboxWorker.class);
  private final MessageInboxes inboxes;
  private final RelayWorkerProperties properties;
  private final MessageDefinitions definitions;
  private final AtomicBoolean started = new AtomicBoolean();
  private boolean closed;
  private final AtomicBoolean queued = new AtomicBoolean();
  private final ConcurrentMap<MessageInboxRepository.Inbox, Boolean> pending =
      new ConcurrentHashMap<>();
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            var thread = new Thread(task, "message-inbox-admission");
            thread.setDaemon(true);
            return thread;
          });

  public MessageInboxWorker(
      MessageInboxes inboxes, RelayWorkerProperties properties, MessageDefinitions definitions) {
    this.inboxes = Objects.requireNonNull(inboxes);
    this.properties = Objects.requireNonNull(properties);
    this.definitions = Objects.requireNonNull(definitions);
  }

  /**
   * Only after transaction commit. Duplicate hints coalesce; overflow is recovered by discovery.
   */
  public synchronized void signal(MessageInboxRepository.Inbox inbox) {
    Objects.requireNonNull(inbox);
    if (closed) return;
    // The reservation guard bounds the compound size/add invariant; the map remains concurrent
    // because the executor removes hints while publishing threads add them.
    synchronized (pending) {
      if (pending.containsKey(inbox) || pending.size() < properties.getMaxSignalWaiters())
        pending.put(inbox, true);
    }
    schedule();
  }

  /** Started after interrupted paid work recovery; queued pre-start hints are retained. */
  public synchronized void start() {
    if (closed) return;
    if (!started.compareAndSet(false, true)) return;
    executor.scheduleWithFixedDelay(
        this::discover, 0, properties.getIdleInterval().toMillis(), TimeUnit.MILLISECONDS);
    schedule();
  }

  private synchronized void schedule() {
    if (!closed && started.get() && !pending.isEmpty() && queued.compareAndSet(false, true))
      executor.execute(this::admitSignals);
  }

  private void admitSignals() {
    try {
      int count = 0;
      for (var inbox : pending.keySet()) {
        if (count++ == 32) break;
        if (!pending.remove(inbox, true)) continue;
        try {
          inboxes.poll(inbox);
        } catch (RuntimeException failure) {
          LOG.warn(
              "Messaging admission blocked for project={} topic={} agent={}; inspect retained position and native receipt before recovery",
              inbox.project(),
              inbox.subscription().topic().name(),
              inbox.agent());
        }
      }
    } finally {
      queued.set(false);
      schedule();
    }
  }

  private void discover() {
    try {
      inboxes.poll();
      inboxes.retireMissing(definitions);
      inboxes.cleanupRetired();
    } catch (RuntimeException failure) {
      LOG.warn(
          "Messaging failure category=DISCOVERY_OR_MAINTENANCE_FAILED; inspect retained evidence; scoped inbox failures include coordinates");
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    started.set(false);
    executor.shutdownNow();
    pending.clear();
  }
}
