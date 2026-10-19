package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RelayPortNotificationTest {
  @Test
  void hintsAfterTheFirstReadWakeLongPollWithoutRepeatingReadsWhileItWaits() throws Exception {
    try (var fixture = new Fixture();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var parked = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var signals = mock(RelayPublicationSignals.class);
      var waiting = mock(RelayPublicationSignals.Waiting.class);
      when(signals.listen(any())).thenReturn(waiting);
      when(signals.reconciliationWait(any())).thenReturn(Duration.ofSeconds(30));
      doAnswer(
              call -> {
                parked.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
                return null;
              })
          .when(waiting)
          .await(any());
      var ports = fixture.ports(signals);
      var result = executor.submit(() -> ports.consume("reader", fixture.request(30000)));
      try {
        assertTrue(parked.await(1, TimeUnit.SECONDS));
        assertEquals(1, fixture.reads.get());
        verify(fixture.members, times(1)).mayWork("project", "reader");
        when(fixture.repository.consume(any(), anyString(), any(), any()))
            .thenReturn(fixture.batch(RelayPort.Status.BUSY));
      } finally {
        release.countDown();
      }
      assertEquals(RelayPort.Status.BUSY, result.get(1, TimeUnit.SECONDS).status());
      verify(waiting).close();
      verify(fixture.members, times(2)).mayWork("project", "reader");
    }
  }

  @Test
  void revocationDuringAWaitFailsBeforeTheNextConsumeAndReleasesRegistration() throws Exception {
    try (var fixture = new Fixture();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var parked = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var signals = mock(RelayPublicationSignals.class);
      var waiting = mock(RelayPublicationSignals.Waiting.class);
      when(signals.listen(any())).thenReturn(waiting);
      when(signals.reconciliationWait(any())).thenReturn(Duration.ofSeconds(30));
      doAnswer(
              call -> {
                parked.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
                return null;
              })
          .when(waiting)
          .await(any());
      var result =
          executor.submit(
              () ->
                  assertThrows(
                      CallerFault.class,
                      () -> fixture.ports(signals).consume("reader", fixture.request(30000))));
      try {
        assertTrue(parked.await(1, TimeUnit.SECONDS));
        when(fixture.members.mayWork("project", "reader")).thenReturn(false);
      } finally {
        release.countDown();
      }
      result.get(1, TimeUnit.SECONDS);
      assertEquals(1, fixture.reads.get());
      verify(waiting).close();
    }
  }

  @Test
  void aMissedHintStillHonorsTheCallersDeadline() throws Exception {
    try (var fixture = new Fixture();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      fixture.source.state(2, true);
      var ports = fixture.ports(fixture.signals);
      var result = executor.submit(() -> ports.consume("reader", fixture.request(50)));
      assertEquals(RelayPort.Status.EMPTY, result.get(2, TimeUnit.SECONDS).status());
      assertTrue(
          fixture.reads.get() <= 2,
          "No 100-ms polling loop or replay is needed before the deadline");
    }
  }

  private static final class Fixture implements AutoCloseable {
    final String id = UUID.randomUUID().toString();
    final RelayPortRepository repository = mock(RelayPortRepository.class);
    final ProjectMembers members = mock(ProjectMembers.class);
    final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
    final RelayPortProperties properties = new RelayPortProperties();
    final NotificationRelayPublicationSignalsTest.Source source =
        new NotificationRelayPublicationSignalsTest.Source();
    final NotificationRelayPublicationSignals signals =
        new NotificationRelayPublicationSignals(source, new RelayWorkerProperties());
    final AtomicInteger reads = new AtomicInteger();

    Fixture() {
      when(members.mayWork("project", "reader")).thenReturn(true);
      when(projects.id("project")).thenReturn(7L);
      properties.setBindings(
          List.of(
              new RelayPortProperties.Binding(
                  "project",
                  "source.topic",
                  "reader",
                  RelayPortProperties.Direction.EGRESS,
                  List.of("group"))));
      when(repository.consume(any(), anyString(), any(), any()))
          .thenAnswer(
              call -> {
                reads.incrementAndGet();
                return batch(RelayPort.Status.EMPTY);
              });
    }

    ProjectRelayPorts ports(RelayPublicationSignals signals) {
      return new ProjectRelayPorts(
          mock(Relay.class),
          repository,
          properties,
          members,
          projects,
          8,
          new io.aeyer.plowshare.server.relay.tools.RelayToolProperties(),
          signals);
    }

    RelayPort.Consume request(int wait) {
      return new RelayPort.Consume(
          "project", "source.topic", "group", id, RelayPort.Start.OLDEST_RETAINED, 1, wait);
    }

    RelayPort.Batch batch(RelayPort.Status status) {
      return new RelayPort.Batch(
          "project", "source.topic", "group", id, status, null, null, "0", null, null, List.of());
    }

    public void close() {
      signals.close();
    }
  }
}
