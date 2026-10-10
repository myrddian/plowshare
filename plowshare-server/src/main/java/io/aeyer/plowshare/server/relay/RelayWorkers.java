package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Local lifecycle supervision for explicitly bound projects. Project watchers only manage worker
 * lifetimes; each subscription consumes independently. Database leases provide distributed
 * ownership.
 */
public final class RelayWorkers
    implements AutoCloseable, ApplicationListener<ApplicationReadyEvent> {
  private static final Logger LOG = LoggerFactory.getLogger(RelayWorkers.class);
  private final ProjectWorkspaces projects;
  private final RelaySubscriptionWork work;
  private final RelayPublicationSignals signals;
  private final RelayWorkerProperties properties;
  private final RelayWorkerBindings bindings;
  private final ConcurrentHashMap<RelayWorkerProperties.Project, FutureTask<?>> watchers =
      new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ConcurrentHashMap<WorkerKey, Worker> workers = new ConcurrentHashMap<>();
  private final AtomicInteger workerCount = new AtomicInteger();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Semaphore capacity;

  public RelayWorkers(
      ProjectWorkspaces projects,
      RelaySubscriptionWork work,
      RelayPublicationSignals signals,
      RelayWorkerProperties properties) {
    this(projects, work, signals, properties, properties::getProjects);
  }

  public RelayWorkers(
      ProjectWorkspaces projects,
      RelaySubscriptionWork work,
      RelayPublicationSignals signals,
      RelayWorkerProperties properties,
      RelayWorkerBindings bindings) {
    this.bindings = Objects.requireNonNull(bindings);
    this.projects = Objects.requireNonNull(projects);
    this.work = Objects.requireNonNull(work);
    this.signals = Objects.requireNonNull(signals);
    this.properties = Objects.requireNonNull(properties);
    capacity = new Semaphore(properties.getConcurrency(), true);
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    start();
  }

  /** Idempotent start; restored configuration reuses the persisted subscription offsets. */
  public void start() {
    if (closed.get() || !started.compareAndSet(false, true)) return;
    executor.submit(this::supervise);
  }

  private void supervise() {
    boolean failed = false;
    try {
      while (!closed.get() && !Thread.currentThread().isInterrupted()) {
        try {
          var desired = new HashSet<>(bindings.current());
          if (desired.size() > 32
              || desired.stream().map(RelayWorkerProperties.Project::project).distinct().count()
                  != desired.size())
            throw new IllegalStateException("Invalid Relay worker binding snapshot");
          for (var entry : Map.copyOf(watchers).entrySet()) {
            if (!desired.contains(entry.getKey()) || entry.getValue().isDone()) {
              entry.getValue().cancel(true);
              watchers.remove(entry.getKey(), entry.getValue());
            }
          }
          for (var binding : desired) {
            if (watchers.containsKey(binding)) continue;
            var task =
                new FutureTask<Void>(
                    () -> {
                      watch(binding);
                      return null;
                    });
            watchers.put(binding, task);
            if (closed.get()) task.cancel(true);
            else executor.execute(task);
          }
          failed = false;
        } catch (RuntimeException unavailable) {
          if (!failed) LOG.warn("Relay worker enrollment paused; binding authority unavailable");
          failed = true;
          stopWatchers();
        }
        TimeUnit.NANOSECONDS.sleep(properties.getConfigurationInterval().toNanos());
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      stopWatchers();
    }
  }

  private void stopWatchers() {
    watchers.values().forEach(task -> task.cancel(true));
    watchers.clear();
  }

  private void watch(RelayWorkerProperties.Project binding) {
    var owned = new HashMap<WorkerKey, Worker>();
    boolean failed = false;
    try {
      while (!closed.get() && !Thread.currentThread().isInterrupted()) {
        try {
          var id = projects.id(binding.project());
          if (id == null || id < 1)
            throw new IllegalStateException("Relay worker project is unavailable");
          var access = new RelayProjectFiles.Access(binding.account(), binding.project(), id);
          // Configuration parsing uses the same concurrency bound as consumers, without dispatch.
          var desired = new HashSet<WorkerKey>();
          capacity.acquire();
          try {
            for (var key : work.subscriptions(access)) desired.add(new WorkerKey(access, key));
          } finally {
            capacity.release();
          }
          for (var entry : Map.copyOf(owned).entrySet())
            if (!desired.contains(entry.getKey()) || entry.getValue().future.isDone()) {
              stop(entry.getKey(), entry.getValue());
              owned.remove(entry.getKey());
            }
          for (var key : desired)
            if (!owned.containsKey(key)) {
              if (workerCount.incrementAndGet() > properties.getMaxWorkers()) {
                workerCount.decrementAndGet();
                throw new IllegalStateException("Relay local worker capacity exceeded");
              }
              var worker = new Worker();
              worker.future =
                  new FutureTask<>(
                      () -> {
                        consume(key, worker);
                        return null;
                      });
              workers.put(key, worker);
              owned.put(key, worker);
              if (closed.get()) {
                stop(key, worker);
                break;
              }
              executor.execute(worker.future);
            }
          failed = false;
        } catch (RuntimeException failure) {
          // Never log exception text: source/provider errors can contain private paths or JS.
          if (!failed)
            LOG.warn(
                "Relay project worker supervision paused for binding {}; configuration or authority unavailable",
                binding.project());
          failed = true;
          owned.forEach(this::stop);
          owned.clear();
        }
        TimeUnit.NANOSECONDS.sleep(properties.getConfigurationInterval().toNanos());
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      owned.forEach(this::stop);
    }
  }

  private void consume(WorkerKey key, Worker worker) {
    String identity = "consumer:" + UUID.randomUUID();
    boolean failed = false;
    try (var waiting = signals.listen(key.subscription())) {
      while (!closed.get() && !worker.stopped.get() && !Thread.currentThread().isInterrupted()) {
        boolean progressed = false;
        try {
          capacity.acquire();
          try {
            if (worker.stopped.get() || closed.get()) break;
            var result =
                work.process(
                    key.access(),
                    key.subscription(),
                    identity,
                    properties.getBatchSize(),
                    properties.getBatchSize());
            progressed = result.progressed();
          } finally {
            capacity.release();
          }
          failed = false;
        } catch (RuntimeException failure) {
          if (!failed)
            LOG.warn(
                "Relay subscription worker {} paused for scope {} topic {} group {}; persisted input retained",
                identity,
                key.access().projectId(),
                key.subscription().topic().name(),
                key.subscription().subscriber());
          failed = true;
        }
        // Idle waits are independent. Failed passes back off even under continuous publication.
        if (failed) TimeUnit.NANOSECONDS.sleep(properties.getFailureBackoff().toNanos());
        else if (!progressed) waiting.await(properties.getIdleInterval());
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void stop(WorkerKey key, Worker worker) {
    if (worker.stopped.compareAndSet(false, true)) {
      worker.future.cancel(true);
      workers.remove(key, worker);
      workerCount.decrementAndGet();
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    workers.forEach(this::stop);
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS))
        LOG.warn("Relay workers still stopping; outstanding leases retain normal expiry fencing");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private record WorkerKey(RelayProjectFiles.Access access, Relay.SubscriptionKey subscription) {}

  private static final class Worker {
    private final AtomicBoolean stopped = new AtomicBoolean();
    private FutureTask<?> future;
  }
}
