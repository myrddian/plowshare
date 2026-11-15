package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AsyncAuthoritySnapshotRefreshTest {
  /** Deterministic queue: the test chooses exactly when the worker task starts and completes. */
  private static final class Worker extends ThreadPoolExecutor {
    final ArrayDeque<Runnable> pending = new ArrayDeque<>();

    Worker() {
      super(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
    }

    @Override
    public void execute(Runnable task) {
      pending.add(task);
    }

    void run() {
      pending.remove().run();
    }
  }

  @Test
  void burstsAreCoalescedFailuresCanRetryAndCompletedUnhelpfulLoadsAreRateBounded() {
    var snapshots = mock(AuthoritySnapshots.class);
    doThrow(new IllegalStateException("test load failure")).doNothing().when(snapshots).refresh();
    var clock = new AtomicLong();
    var worker = new Worker();
    try (var refresh =
        new AsyncAuthoritySnapshotRefresh(snapshots, Duration.ofSeconds(1), clock::get, worker)) {
      for (int i = 0; i < 100; i++) refresh.request();
      assertEquals(1, worker.pending.size());
      worker.run();
      verify(snapshots).refresh();
      // A failed/oversized/historical reload cannot cause one full hydration per item.
      for (int i = 0; i < 100; i++) refresh.request();
      assertTrue(worker.pending.isEmpty());
      clock.addAndGet(Duration.ofSeconds(1).toNanos());
      refresh.request();
      assertEquals(1, worker.pending.size());
      worker.run();
      verify(snapshots, times(2)).refresh();
    }
    assertTrue(worker.isShutdown());
  }

  @Test
  void refreshHasNoRequestingTransactionAndShutdownRejectsNewRequests() throws Exception {
    var started = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    var done = new CountDownLatch(1);
    var inheritedTransaction = new AtomicBoolean(true);
    AuthoritySnapshots snapshots =
        new AuthoritySnapshots() {
          public Optional<AuthoritySnapshot> current() {
            return Optional.empty();
          }

          public void refresh() {
            inheritedTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            started.countDown();
            try {
              assertTrue(finish.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException stopped) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          }
        };
    var refresh = new AsyncAuthoritySnapshotRefresh(snapshots);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      refresh.request();
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertFalse(inheritedTransaction.get());
      for (int i = 0; i < 100; i++) refresh.request();
      refresh.close();
      assertTrue(done.await(5, TimeUnit.SECONDS));
      refresh.request();
    } finally {
      TransactionSynchronizationManager.clear();
      finish.countDown();
      refresh.close();
    }
  }
}
