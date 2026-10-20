package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.support.GenericApplicationContext;

@Timeout(10)
class InformationQueueLifecycleTest {
  @Test
  void context_shutdown_drains_the_current_transaction_before_destroying_repository_dependencies()
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stopping = new CountDownLatch(1);
    var finished = new AtomicBoolean();
    var interrupted = new AtomicBoolean();
    var dependenciesClosed = new AtomicBoolean();
    var repository = mock(InformationProcessingRepository.class);
    when(repository.sweepUntagged())
        .thenAnswer(
            invocation -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException failure) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted transaction", failure);
              }
              assertFalse(dependenciesClosed.get());
              finished.set(true);
              return List.of();
            });
    when(repository.sweepTagGroups()).thenReturn(List.of());
    when(repository.candidate(null, false)).thenReturn(Optional.empty());
    var lifecycle =
        new InformationLifecycle(
            repository,
            UnitOfWork.NONE,
            mock(InformationCatalogue.class),
            (lease, cancelled, fence) -> {},
            new InformationLifecycle.Gates() {});
    var acquisitions = mock(InformationAcquisitions.class);
    when(acquisitions.shutdownCompletion()).thenReturn(CompletableFuture.completedFuture(null));
    var events = mock(InformationEventPublisher.class);
    when(events.shutdownCompletion()).thenReturn(CompletableFuture.completedFuture(null));
    var invalidations = mock(InformationInvalidationPublisher.class);
    doAnswer(
            invocation -> {
              stopping.countDown();
              return null;
            })
        .when(acquisitions)
        .close();
    var queue = new InformationQueueLifecycle(lifecycle, acquisitions, events, invalidations);
    var context = new GenericApplicationContext();
    context.registerBean(
        "repositoryDependencies",
        AutoCloseable.class,
        () ->
            () -> {
              assertTrue(finished.get(), "repository dependencies outlive the current transaction");
              dependenciesClosed.set(true);
            },
        definition -> definition.setDestroyMethodName("close"));
    context.registerBean("informationQueue", InformationQueueLifecycle.class, () -> queue);
    context.refresh();
    assertFalse(queue.isRunning(), "workers wait for application readiness");
    queue.start();
    queue.start();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    try (var closer = Executors.newVirtualThreadPerTaskExecutor()) {
      var draining =
          closer.submit(
              () -> {
                queue.stop();
              });
      assertTrue(stopping.await(2, TimeUnit.SECONDS));
      assertTrue(queue.isRunning(), "Spring must still observe a lifecycle whose drain is active");
      var callback = new CountDownLatch(1);
      queue.stop(callback::countDown);
      var closed = closer.submit(context::close);
      assertFalse(
          callback.await(100, TimeUnit.MILLISECONDS), "second stop cannot acknowledge early");
      assertFalse(dependenciesClosed.get());
      assertFalse(closed.isDone());
      release.countDown();
      draining.get(2, TimeUnit.SECONDS);
      closed.get(2, TimeUnit.SECONDS);
      assertTrue(callback.await(2, TimeUnit.SECONDS));
      assertFalse(interrupted.get());
      assertTrue(dependenciesClosed.get());
      assertFalse(queue.isRunning());
      queue.stop();
      lifecycle.close();
      verify(acquisitions).start();
      verify(acquisitions).close();
      verify(events).close();
      verify(invalidations).close();
    } finally {
      release.countDown();
      lifecycle.close();
      context.close();
    }
  }

  @Test
  void workers_drain_concurrently_without_holding_the_state_lock_and_reentrant_stop_is_terminal()
      throws Exception {
    var lifecycle = mock(InformationLifecycle.class);
    when(lifecycle.shutdownCompletion()).thenReturn(CompletableFuture.completedFuture(null));
    var acquisitions = mock(InformationAcquisitions.class);
    when(acquisitions.shutdownCompletion()).thenReturn(CompletableFuture.completedFuture(null));
    var events = mock(InformationEventPublisher.class);
    when(events.shutdownCompletion()).thenReturn(CompletableFuture.completedFuture(null));
    var invalidations = mock(InformationInvalidationPublisher.class);
    var queue = new InformationQueueLifecycle(lifecycle, acquisitions, events, invalidations);
    var entered = new CountDownLatch(3);
    org.mockito.stubbing.Answer<Void> drain =
        invocation -> {
          assertFalse(Thread.holdsLock(queue));
          entered.countDown();
          assertTrue(entered.await(2, TimeUnit.SECONDS), "all workers must drain concurrently");
          queue.stop();
          return null;
        };
    doAnswer(drain).when(lifecycle).close();
    doAnswer(drain).when(acquisitions).close();
    doAnswer(drain).when(events).close();
    queue.start();
    queue.stop();
    queue.onApplicationEvent(null);
    assertFalse(queue.isRunning());
    var callback = new CountDownLatch(1);
    queue.stop(callback::countDown);
    assertTrue(callback.await(2, TimeUnit.SECONDS));
    verify(lifecycle).start();
    verify(lifecycle).close();
    verify(acquisitions).start();
    verify(acquisitions).close();
    verify(events).start();
    verify(events).close();
    verify(invalidations).close();
  }

  @Test
  void shutdown_before_readiness_never_starts_workers_from_a_late_ready_event() {
    var lifecycle = mock(InformationLifecycle.class);
    when(lifecycle.shutdownCompletion()).thenReturn(CompletableFuture.completedFuture(null));
    var acquisitions = mock(InformationAcquisitions.class);
    when(acquisitions.shutdownCompletion()).thenReturn(CompletableFuture.completedFuture(null));
    var events = mock(InformationEventPublisher.class);
    when(events.shutdownCompletion()).thenReturn(CompletableFuture.completedFuture(null));
    var invalidations = mock(InformationInvalidationPublisher.class);
    var queue = new InformationQueueLifecycle(lifecycle, acquisitions, events, invalidations);
    queue.stop();
    queue.onApplicationEvent(null);
    assertFalse(queue.isRunning());
    verify(lifecycle, never()).start();
    verify(acquisitions, never()).start();
    verify(events, never()).start();
    verify(invalidations, never()).start();
  }
}
