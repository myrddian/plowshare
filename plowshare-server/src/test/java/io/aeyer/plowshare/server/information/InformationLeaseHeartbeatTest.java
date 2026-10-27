package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class InformationLeaseHeartbeatTest {
  private final InformationProcessingRepository repository =
      mock(InformationProcessingRepository.class);
  private final ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
  private final ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
  private final AtomicReference<Runnable> tick = new AtomicReference<>();
  private final InformationLifecycle.Lease lease =
      new InformationLifecycle.Lease(
          UUID.randomUUID(), UUID.randomUUID(), 1, "extract", 1, UUID.randomUUID(), "owner", null);

  private InformationLifecycle lifecycle(InformationLifecycle.Processor processor)
      throws Exception {
    when(repository.candidate(null, false))
        .thenReturn(Optional.of(new InformationProcessingRepository.Candidate(lease, null)));
    when(repository.renew(lease)).thenReturn(true);
    doAnswer(
            invocation -> {
              tick.set(invocation.getArgument(0));
              return scheduled;
            })
        .when(timer)
        .scheduleWithFixedDelay(any(Runnable.class), eq(5L), eq(5L), eq(TimeUnit.SECONDS));
    when(timer.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
    return new InformationLifecycle(
        repository,
        UnitOfWork.NONE,
        mock(InformationCatalogue.class),
        processor,
        new InformationLifecycle.Gates() {},
        timer);
  }

  @Test
  void concurrentTokenPollsDoNoSqlAndSilentProcessingStillRenewsOnTheOwnedTimer() throws Exception {
    var retained = new AtomicReference<BooleanSupplier>();
    try (var lifecycle =
        lifecycle(
            (selected, cancelled, fence) -> {
              retained.set(cancelled);
              try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
                var checks =
                    java.util.stream.IntStream.range(0, 8)
                        .mapToObj(
                            ignored ->
                                readers.submit(
                                    () -> {
                                      for (int i = 0; i < 10_000; i++)
                                        assertFalse(cancelled.getAsBoolean());
                                    }))
                        .toList();
                for (var check : checks) check.get(2, TimeUnit.SECONDS);
              } catch (Exception failure) {
                throw new AssertionError(failure);
              }
              verify(repository, never()).renew(lease);
              // No cancellation callback is needed to keep a silent provider's lease alive.
              tick.get().run();
              verify(repository).renew(lease);
              for (int i = 0; i < 10_000; i++) assertFalse(cancelled.getAsBoolean());
              verify(repository).renew(lease);
              fence.run();
            })) {
      assertTrue(lifecycle.drainOne());
      verify(repository, times(2)).renew(lease); // Periodic heartbeat and fresh completion check.
      verify(repository).finish(lease, "ready", null);
      verify(scheduled).cancel(false);
      assertTrue(retained.get().getAsBoolean(), "A retained callback cannot outlive its stage");
      tick.get().run();
      verify(repository, times(2)).renew(lease);
    }
    verify(timer).shutdown();
  }

  @Test
  void lostLeaseCancelsLocallyAndCannotPublishCompletionOrCheckpoint() throws Exception {
    try (var lifecycle =
        lifecycle(
            (selected, cancelled, fence) -> {
              when(repository.renew(lease)).thenReturn(false);
              tick.get().run();
              assertTrue(cancelled.getAsBoolean());
              assertThrows(InformationLifecycle.StaleLease.class, fence::run);
              tick.get().run();
            })) {
      assertTrue(lifecycle.drainOne());
      verify(repository).renew(lease);
      verify(repository, never()).finish(any(), anyString(), any());
    }
  }

  @Test
  void liveFenceStillRejectsRevocationBetweenHeartbeatsBeforeAnyCheckpoint() throws Exception {
    try (var lifecycle =
        lifecycle(
            (selected, cancelled, fence) -> {
              assertFalse(cancelled.getAsBoolean());
              doThrow(new InformationLifecycle.StaleLease()).when(repository).requireLease(lease);
              // A local liveness hint is never permission to write, disclose or complete work.
              fence.run();
              fail("The revoked stage reached its checkpoint");
            })) {
      assertTrue(lifecycle.drainOne());
      verify(repository, never()).renew(lease);
      verify(repository, never()).finish(any(), anyString(), any());
      verify(scheduled).cancel(false);
    }
  }

  @Test
  void renewalFailureStopsCallbacksAndReportsTheOriginalFailureOnTheStageOwner() throws Exception {
    var unavailable = new IllegalStateException("lease database unavailable");
    try (var lifecycle =
        lifecycle(
            (selected, cancelled, fence) -> {
              when(repository.renew(lease)).thenThrow(unavailable);
              tick.get().run();
              assertTrue(cancelled.getAsBoolean());
              assertSame(unavailable, assertThrows(IllegalStateException.class, fence::run));
              tick.get().run();
            })) {
      assertTrue(lifecycle.drainOne());
      verify(repository).renew(lease);
      verify(repository).finish(lease, "failed", unavailable.getMessage());
      verify(repository, never()).finish(lease, "ready", null);
    }
  }

  @Test
  void shutdownDrainsAnInFlightRenewalWithoutInterruptingItsTransaction() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    var calls = new AtomicInteger();
    var executor =
        new ScheduledThreadPoolExecutor(1) {
          @Override
          public ScheduledFuture<?> scheduleWithFixedDelay(
              Runnable task, long initial, long delay, TimeUnit unit) {
            // Exercise the real timer/close race without waiting for a production heartbeat.
            return super.scheduleWithFixedDelay(task, 0, delay, unit);
          }
        };
    when(repository.candidate(null, false))
        .thenReturn(Optional.of(new InformationProcessingRepository.Candidate(lease, null)));
    when(repository.renew(lease))
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                  assertTrue(release.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                  interrupted.set(true);
                  throw failure;
                }
              }
              return true;
            });
    var lifecycle =
        new InformationLifecycle(
            repository,
            UnitOfWork.NONE,
            mock(InformationCatalogue.class),
            (selected, cancelled, fence) -> {
              try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
              } catch (InterruptedException failure) {
                throw new AssertionError(failure);
              }
            },
            new InformationLifecycle.Gates() {},
            executor);
    try (var owners = Executors.newVirtualThreadPerTaskExecutor()) {
      assertTrue(lifecycle.drainOne());
      var closing = owners.submit(lifecycle::close);
      assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
      assertFalse(interrupted.get());
      release.countDown();
      closing.get(2, TimeUnit.SECONDS);
      assertTrue(executor.isTerminated());
      assertEquals(2, calls.get(), "One background renewal plus the fresh completion check");
    } finally {
      release.countDown();
      lifecycle.close();
    }
  }
}
