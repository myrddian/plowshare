package io.aeyer.plowshare.server.relay;

import static io.aeyer.plowshare.server.relay.RelayDispatchFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RelayWorkersTest {
  private RelayWorkerProperties properties() {
    var properties = new RelayWorkerProperties();
    properties.setProjects(List.of(new RelayWorkerProperties.Project("project", "operator")));
    properties.setConfigurationInterval(Duration.ofSeconds(1));
    properties.setIdleInterval(Duration.ofSeconds(1));
    properties.setFailureBackoff(Duration.ofSeconds(1));
    properties.setConcurrency(2);
    return properties;
  }

  @Test
  void blocked_group_does_not_block_another_and_shutdown_interrupts_both() throws Exception {
    var projects = mock(ProjectWorkspaces.class);
    when(projects.id("project")).thenReturn(9L);
    var work = mock(RelaySubscriptionWork.class);
    var second = new Relay.SubscriptionKey(SUB.topic(), "relay.notices.other");
    when(work.subscriptions(ACCESS)).thenReturn(List.of(SUB, second));
    var blocked = new CountDownLatch(1);
    var progressed = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    when(work.process(eq(ACCESS), eq(SUB), anyString(), anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              assertTrue(Thread.currentThread().isVirtual());
              blocked.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException stop) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
              }
              return new RelaySubscriptionWork.Result(0, 0, null);
            });
    when(work.process(eq(ACCESS), eq(second), anyString(), anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              assertTrue(Thread.currentThread().isVirtual());
              progressed.countDown();
              return new RelaySubscriptionWork.Result(0, 0, null);
            });
    try (var workers =
        new RelayWorkers(projects, work, new LocalRelayPublicationSignals(), properties())) {
      workers.start();
      workers.start();
      assertTrue(blocked.await(3, TimeUnit.SECONDS));
      assertTrue(progressed.await(3, TimeUnit.SECONDS));
    }
    assertTrue(interrupted.await(1, TimeUnit.SECONDS));
  }

  @Test
  void configuration_changes_stop_removed_workers_and_restart_reloads_active_offsets()
      throws Exception {
    var projects = mock(ProjectWorkspaces.class);
    when(projects.id("project")).thenReturn(9L);
    var work = mock(RelaySubscriptionWork.class);
    var declared = new AtomicReference<>(List.of(SUB));
    when(work.subscriptions(ACCESS)).thenAnswer(call -> declared.get());
    var count = new AtomicInteger();
    var first = new CountDownLatch(1);
    var removed = new CountDownLatch(1);
    when(work.process(eq(ACCESS), eq(SUB), anyString(), anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              count.incrementAndGet();
              first.countDown();
              return new RelaySubscriptionWork.Result(0, 0, null);
            });
    var signals = mock(RelayPublicationSignals.class);
    when(signals.listen(SUB))
        .thenAnswer(
            call ->
                new RelayPublicationSignals.Waiting() {
                  public void await(Duration maximum) throws InterruptedException {
                    new CountDownLatch(1).await();
                  }

                  public void close() {
                    removed.countDown();
                  }
                });
    try (var workers = new RelayWorkers(projects, work, signals, properties())) {
      workers.start();
      assertTrue(first.await(3, TimeUnit.SECONDS));
      declared.set(List.of());
      assertTrue(removed.await(3, TimeUnit.SECONDS));
      assertEquals(1, count.get());
    }
    declared.set(List.of(SUB));
    var restored = new CountDownLatch(1);
    when(work.process(eq(ACCESS), eq(SUB), anyString(), anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              restored.countDown();
              return new RelaySubscriptionWork.Result(0, 0, null);
            });
    try (var workers =
        new RelayWorkers(projects, work, new LocalRelayPublicationSignals(), properties())) {
      workers.start();
      assertTrue(restored.await(3, TimeUnit.SECONDS));
    }
  }

  @Test
  void worker_configuration_requires_explicit_valid_principals_and_bounds() {
    assertThrows(
        IllegalArgumentException.class, () -> new RelayWorkerProperties.Project("project", ""));
    var properties = properties();
    assertThrows(IllegalArgumentException.class, () -> properties.setConcurrency(0));
    assertThrows(IllegalArgumentException.class, () -> properties.setIdleInterval(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            properties.setProjects(
                List.of(
                    new RelayWorkerProperties.Project("project", "operator"),
                    new RelayWorkerProperties.Project("project", "other"))));
  }

  @Test
  void live_enrollment_replaces_accounts_and_stops_on_removed_or_unavailable_authority()
      throws Exception {
    var projects = mock(ProjectWorkspaces.class);
    when(projects.id("project")).thenReturn(9L);
    var work = mock(RelaySubscriptionWork.class);
    when(work.subscriptions(any())).thenReturn(List.of(SUB));
    var processed = new java.util.concurrent.LinkedBlockingQueue<RelayProjectFiles.Access>();
    when(work.process(any(), eq(SUB), anyString(), anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              processed.add(call.getArgument(0));
              // Work owns offsets and admission; enrollment never creates or resets either.
              return new RelaySubscriptionWork.Result(0, 0, null);
            });
    var stopped = new java.util.concurrent.LinkedBlockingQueue<Boolean>();
    var signals = mock(RelayPublicationSignals.class);
    when(signals.listen(SUB))
        .thenAnswer(
            call ->
                new RelayPublicationSignals.Waiting() {
                  public void await(Duration maximum) throws InterruptedException {
                    new CountDownLatch(1).await();
                  }

                  public void close() {
                    stopped.add(true);
                  }
                });
    var declared = new AtomicReference<List<RelayWorkerProperties.Project>>(List.of());
    var unavailable = new java.util.concurrent.atomic.AtomicBoolean();
    var firstSnapshot = new CountDownLatch(1);
    RelayWorkerBindings bindings =
        () -> {
          firstSnapshot.countDown();
          if (unavailable.get()) throw new IllegalStateException("authority unavailable");
          return declared.get();
        };
    try (var workers = new RelayWorkers(projects, work, signals, properties(), bindings)) {
      workers.start();
      assertTrue(firstSnapshot.await(3, TimeUnit.SECONDS));
      verifyNoInteractions(work);
      declared.set(List.of(new RelayWorkerProperties.Project("project", "service")));
      assertEquals(
          "service",
          java.util.Objects.requireNonNull(processed.poll(3, TimeUnit.SECONDS)).account());
      declared.set(List.of(new RelayWorkerProperties.Project("project", "replacement")));
      assertNotNull(stopped.poll(3, TimeUnit.SECONDS));
      assertEquals(
          "replacement",
          java.util.Objects.requireNonNull(processed.poll(3, TimeUnit.SECONDS)).account());
      unavailable.set(true);
      assertNotNull(stopped.poll(3, TimeUnit.SECONDS));
      unavailable.set(false);
      assertEquals(
          "replacement",
          java.util.Objects.requireNonNull(processed.poll(3, TimeUnit.SECONDS)).account());
      declared.set(List.of());
      assertNotNull(stopped.poll(3, TimeUnit.SECONDS));
      assertTrue(processed.isEmpty());
    }
    verify(work, times(3)).process(any(), eq(SUB), anyString(), anyInt(), anyInt());
  }
}
