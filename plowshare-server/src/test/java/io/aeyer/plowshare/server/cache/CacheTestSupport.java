package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Controlled execution and immutable values; no wall-clock timer or database is involved. */
final class CacheTestSupport {
  private CacheTestSupport() {}

  record Value(String text) {}

  static SnapshotCache.Namespace namespace() {
    return new SnapshotCache.Namespace("database-fixture", "immutable-values-v1");
  }

  static SnapshotCache.Limits limits() {
    return new SnapshotCache.Limits(4, 100_000, 4, 100_000, 8, 4, 4, 32, Duration.ofSeconds(30));
  }

  static SnapshotCache.Request request(String identity) {
    return new SnapshotCache.Request(
        new SnapshotCache.Key(identity, "account-fixture"),
        new SnapshotCache.SourceVersion("v1"),
        Set.of(new SnapshotCache.Dependency("collection-fixture")),
        1_000);
  }

  static SnapshotCache.Loaded<Value> loaded(SnapshotCache.Request request, String text) {
    return new SnapshotCache.Loaded<>(request.version(), new Value(text), 100);
  }

  static Throwable failure(SnapshotCache.Read<?> read) {
    return assertThrows(CompletionException.class, () -> read.result().toCompletableFuture().join())
        .getCause();
  }

  /** Completion visibility can precede the delivery task's finally/accounting cleanup. */
  static SnapshotCache.Statistics awaitStatistics(
      SnapshotCache<?> cache, Predicate<SnapshotCache.Statistics> expected) {
    return assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          while (!Thread.currentThread().isInterrupted()) {
            var statistics = cache.statistics();
            if (expected.test(statistics)) return statistics;
            Thread.yield();
          }
          throw new AssertionError("Interrupted before cache accounting became quiescent");
        });
  }

  static final class HeldExecutor implements Executor {
    private final ConcurrentLinkedDeque<Runnable> tasks = new ConcurrentLinkedDeque<>();
    volatile boolean reject;

    @Override
    public void execute(Runnable task) {
      if (reject) throw new RejectedExecutionException("Executor fixture rejected load");
      tasks.addLast(task);
    }

    int size() {
      return tasks.size();
    }

    Runnable takeNext() {
      return tasks.removeFirst();
    }

    void runNext() {
      takeNext().run();
    }

    void runLast() {
      tasks.removeLast().run();
    }
  }

  /**
   * No tasks reach the superclass scheduler. A fired task runs even after cancel reports success,
   * reproducing a timer that had already started when cancellation raced with it.
   */
  static final class ManualTimer extends ScheduledThreadPoolExecutor {
    private final CopyOnWriteArrayList<Task> tasks = new CopyOnWriteArrayList<>();
    volatile boolean reject;
    volatile Runnable scheduling = () -> {};
    volatile Runnable cancelling = () -> {};

    ManualTimer() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      if (reject) throw new RejectedExecutionException("Timer fixture rejected deadline");
      scheduling.run();
      var task = new Task(command, unit.toNanos(delay));
      tasks.add(task);
      return task;
    }

    int size() {
      return tasks.size();
    }

    void fire(int index) {
      tasks.get(index).fire();
    }

    boolean cancelled(int index) {
      return tasks.get(index).cancelled;
    }

    private final class Task implements ScheduledFuture<Void> {
      final Runnable command;
      final long delay;
      volatile boolean cancelled;
      volatile boolean fired;

      Task(Runnable command, long delay) {
        this.command = command;
        this.delay = delay;
      }

      void fire() {
        fired = true;
        command.run();
      }

      @Override
      public long getDelay(TimeUnit unit) {
        return unit.convert(delay, TimeUnit.NANOSECONDS);
      }

      @Override
      public int compareTo(Delayed other) {
        return Long.compare(delay, other.getDelay(TimeUnit.NANOSECONDS));
      }

      @Override
      public boolean cancel(boolean interrupt) {
        cancelling.run();
        cancelled = true;
        return true;
      }

      @Override
      public boolean isCancelled() {
        return cancelled;
      }

      @Override
      public boolean isDone() {
        return cancelled || fired;
      }

      @Override
      public Void get() {
        throw new UnsupportedOperationException("Fire the timer explicitly");
      }

      @Override
      public Void get(long timeout, TimeUnit unit) {
        return get();
      }
    }
  }
}
