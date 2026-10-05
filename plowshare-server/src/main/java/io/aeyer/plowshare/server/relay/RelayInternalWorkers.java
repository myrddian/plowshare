package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.events.Dispatcher;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supervises publishers per source topic and native consumers per destination group. Discovery only
 * manages lifetimes; it never dispatches effects. Bounded passes and keyset rotation prevent a busy
 * partition from starving others. Virtual threads park without reserving a core.
 */
public final class RelayInternalWorkers implements AutoCloseable, Dispatcher.NativeQueue {
  private static final Logger LOG = LoggerFactory.getLogger(RelayInternalWorkers.class);
  private final RelaySourceRepository sources;
  private final RelayNativeRepository inboxes;
  private final RelayConsumerRepository consumers;
  private final Relay relay;
  private final RelayPublicationSignals signals;
  private final Dispatcher dispatcher;
  private final RelayWorkerProperties properties;
  private final boolean enabled;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ConcurrentHashMap<String, FutureTask<Void>> workers = new ConcurrentHashMap<>();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Semaphore capacity;

  public RelayInternalWorkers(
      RelaySourceRepository sources,
      RelayNativeRepository inboxes,
      RelayConsumerRepository consumers,
      Relay relay,
      RelayPublicationSignals signals,
      Dispatcher dispatcher,
      RelayWorkerProperties properties,
      boolean enabled) {
    this.sources = Objects.requireNonNull(sources);
    this.inboxes = Objects.requireNonNull(inboxes);
    this.consumers = Objects.requireNonNull(consumers);
    this.relay = Objects.requireNonNull(relay);
    this.signals = Objects.requireNonNull(signals);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.properties = Objects.requireNonNull(properties);
    this.enabled = enabled;
    capacity = new Semaphore(properties.getConcurrency(), true);
    dispatcher.useNativeQueue(this);
  }

  /** Called after owning startup recovery, so abandoned native starts settle before new claims. */
  public void start() {
    if (!enabled || closed.get() || !started.compareAndSet(false, true)) return;
    executor.submit(this::watch);
  }

  @Override
  public boolean routes(String target) {
    return enabled && inboxes.routes(target);
  }

  @Override
  public void signal(String target) {
    // Topic signals coalesce. Missing local subscribers are harmless: persistent discovery and
    // inbox polling recover notifications, including free-turn and pot-settlement callbacks.
    if (enabled && !closed.get()) {
      signals.published(new Relay.TopicKey(Relay.SystemScope.SERVER, "message.wake.requested"));
      signals.published(new Relay.TopicKey(Relay.SystemScope.SERVER, "board.wake.requested"));
    }
  }

  private void watch() {
    String sourceCursor = null, nativeCursor = null;
    boolean failed = false;
    boolean nativeFirst = false;
    try {
      while (!closed.get() && !Thread.currentThread().isInterrupted()) {
        try {
          workers.entrySet().removeIf(entry -> entry.getValue().isDone());
          int pageSize = Math.min(properties.getMaxWorkers(), 256);
          var topics = sources.pendingTopics(sourceCursor, pageSize);

          sourceCursor =
              topics.size() < pageSize ? null : RelaySourceRepository.cursor(topics.getLast());
          var bindings = inboxes.ready(nativeCursor, pageSize);
          if (nativeFirst)
            for (var binding : bindings)
              launch("native/" + binding.cursor(), () -> consume(binding));
          for (var topic : topics)
            launch("source/" + RelaySourceRepository.cursor(topic), () -> publish(topic));
          if (!nativeFirst)
            for (var binding : bindings)
              launch("native/" + binding.cursor(), () -> consume(binding));
          nativeFirst = !nativeFirst;
          nativeCursor = bindings.size() < pageSize ? null : bindings.getLast().cursor();
          failed = false;
        } catch (RuntimeException failure) {
          if (!failed)
            LOG.warn(
                "Relay internal discovery paused; durable source notices and inboxes retained");
          failed = true;
        }
        TimeUnit.NANOSECONDS.sleep(
            (failed ? properties.getFailureBackoff() : properties.getIdleInterval()).toNanos());
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void launch(String key, Runnable task) {
    if (closed.get() || workers.size() >= properties.getMaxWorkers()) return;
    String role = key.substring(0, key.indexOf('/') + 1);
    int roleLimit = Math.max(1, properties.getMaxWorkers() / 2);
    if (workers.keySet().stream().filter(value -> value.startsWith(role)).count() >= roleLimit)
      return;
    var future =
        new FutureTask<Void>(
            () -> {
              try {
                if (!closed.get() && !Thread.currentThread().isInterrupted()) task.run();
              } catch (RuntimeException failure) {
                // Owning repositories retain work; never log source payload, instruction or
                // exception text.
                LOG.warn("Relay internal partition paused; persisted work retained");
                try {
                  TimeUnit.NANOSECONDS.sleep(properties.getFailureBackoff().toNanos());
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              }
              return null;
            });
    if (workers.putIfAbsent(key, future) == null) {
      if (closed.get()) future.cancel(true);
      else executor.execute(future);
    }
  }

  private void publish(Relay.TopicKey topic) {
    try {
      capacity.acquire();
      try {
        for (int i = 0;
            i < properties.getBatchSize()
                && !closed.get()
                && !Thread.currentThread().isInterrupted();
            i++) if (!sources.publishNext(topic)) return;
      } finally {
        capacity.release();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void consume(RelayNativeRepository.Binding binding) {
    String worker = "native:" + UUID.randomUUID();
    // Each group owns its waits and offset progression. A bounded lifetime rotates scarce local
    // worker slots across large installations; re-created workers resume the same durable offset.
    try (var waiting = signals.listen(binding.subscription())) {
      for (int pass = 0;
          pass < 8 && !closed.get() && !Thread.currentThread().isInterrupted();
          pass++) {
        boolean progressed = false;
        capacity.acquire();
        try {
          if (closed.get() || Thread.currentThread().isInterrupted()) return;
          var acquired =
              consumers.acquire(
                  binding.subscription(), worker, binding.account(), Duration.ofMinutes(2));
          if (acquired.isEmpty()) return;
          var lease = acquired.get();
          try {
            var read = relay.read(binding.subscription(), properties.getBatchSize());
            if (read.gap().isPresent()) {
              inboxes.recoverGap(lease, read.gap().get().throughInclusive());
              read = relay.read(binding.subscription(), properties.getBatchSize());
            }
            for (var publication : read.publications()) {
              if (closed.get() || Thread.currentThread().isInterrupted()) return;
              if (!(publication.event().payload() instanceof RelayPayload.WakeRequested))
                throw new IllegalStateException("Native wake topic contains a different payload");
              inboxes.advance(lease, publication.position());
              progressed = true;
            }
            if (!closed.get() && !Thread.currentThread().isInterrupted())
              dispatcher.consume(
                  binding.target(),
                  (firing, at) -> {
                    if (closed.get() || Thread.currentThread().isInterrupted()) return false;
                    return inboxes.claim(binding, lease, firing, at);
                  });
          } finally {
            consumers.release(lease);
          }
        } finally {
          capacity.release();
        }
        if (!progressed) waiting.await(properties.getIdleInterval());
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    workers.values().forEach(future -> future.cancel(true));
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS))
        LOG.warn(
            "Relay internal workers still stopping; outstanding claims retain native recovery semantics");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
