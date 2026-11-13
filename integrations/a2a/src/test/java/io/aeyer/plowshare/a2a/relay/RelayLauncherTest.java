package io.aeyer.plowshare.a2a.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the same classification and retry policy called by the executable entry point. */
class RelayLauncherTest {
  @TempDir Path directory;

  @Test
  void invalid_invocation_and_missing_configuration_require_operator_action() throws Exception {
    assertEquals(78, RelayMain.execute(new String[0], key -> null));
    assertEquals(78, RelayMain.execute(new String[] {"unused", "unknown-command"}, key -> null));
    assertEquals(
        78,
        RelayMain.execute(
            new String[] {directory.resolve("missing.json").toString()}, key -> null));
  }

  @Test
  void positively_unsubmitted_replies_have_bounded_backoff_and_temporary_exit_without_peer_replay()
      throws Exception {
    var gateway = new RelayAdapterTest.FakeGateway();
    gateway.notSubmitted = true;
    var peer = new RelayAdapterTest.FakePeer();
    var request = RelayAdapterTest.send();
    gateway.sources.add(RelayAdapterTest.event(request));
    var delays = new ArrayList<Duration>();
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var adapter =
          new RelayAdapter(
              RelayAdapterTest.BINDING,
              gateway,
              journal,
              Map.of("peer", peer),
              () -> RelayAdapterTest.TIME);
      assertEquals(
          75, RelayMain.classify(() -> RelayMain.supervise(adapter, () -> true, delays::add)));
      assertEquals(
          List.of(
              Duration.ofMillis(500),
              Duration.ofSeconds(1),
              Duration.ofSeconds(2),
              Duration.ofSeconds(4)),
          delays);
      assertEquals(1, peer.sends);
      assertEquals(0, gateway.publications.size());
      var entry = journal.find(request.requestId()).orElseThrow();
      assertEquals(5, entry.replyAttempts());
      assertEquals("NOT_SUBMITTED", entry.resolution());
      // A supervisor restarting with the same journal retries only the retained publication.
      gateway.notSubmitted = false;
      assertTrue(adapter.tick());
      assertEquals(1, peer.sends);
    }
  }

  @Test
  void unknown_publication_stops_as_operator_action_without_another_attempt() throws Exception {
    var gateway = new RelayAdapterTest.FakeGateway();
    gateway.loseReply = true;
    var peer = new RelayAdapterTest.FakePeer();
    gateway.sources.add(RelayAdapterTest.event(RelayAdapterTest.send()));
    var delays = new ArrayList<Duration>();
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var adapter =
          new RelayAdapter(
              RelayAdapterTest.BINDING,
              gateway,
              journal,
              Map.of("peer", peer),
              () -> RelayAdapterTest.TIME);
      assertEquals(
          78, RelayMain.classify(() -> RelayMain.supervise(adapter, () -> true, delays::add)));
      assertEquals(
          78, RelayMain.classify(() -> RelayMain.supervise(adapter, () -> true, delays::add)));
      assertTrue(delays.isEmpty());
      assertEquals(1, peer.sends);
      assertEquals(1, gateway.publications.size());
    }
  }

  @Test
  void disconnected_transport_is_temporary_but_retention_gap_requires_operator_action()
      throws Exception {
    var peer = new RelayAdapterTest.FakePeer();
    RelayGateway gap =
        new RelayGateway() {
          public RelayPort.Batch consume(RelayPort.Consume request) {
            return new RelayPort.Batch(
                request.project(),
                request.topic(),
                request.group(),
                request.consumerId(),
                RelayPort.Status.GAP,
                RelayAdapterTest.GENERATION,
                "1",
                "1",
                RelayAdapterTest.TIME.plusSeconds(30),
                "1",
                List.of(),
                RelayAdapterTest.GENERATION);
          }

          public RelayPort.Published publish(RelayPort.Publish request) {
            throw new AssertionError("Gap cannot publish");
          }

          public RelayPort.Acknowledged acknowledge(RelayPort.Ack request) {
            throw new AssertionError("Gap cannot acknowledge");
          }

          public RelayLog.Page log(RelayLog.Query request) {
            throw new AssertionError("Gap cannot infer recovery");
          }
        };
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var adapter =
          new RelayAdapter(
              RelayAdapterTest.BINDING,
              gap,
              journal,
              Map.of("peer", peer),
              () -> RelayAdapterTest.TIME);
      assertEquals(
          75, RelayMain.classify(() -> RelayMain.supervise(adapter, () -> false, duration -> {})));
      assertEquals(
          78, RelayMain.classify(() -> RelayMain.supervise(adapter, () -> true, duration -> {})));
      assertEquals(0, peer.sends);
    }
  }
}
