package io.aeyer.plowshare.server.union;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnionGateTest {

  @TempDir Path data;

  private Hub hub;
  private Hubs hubs;
  private UnionGate gate;
  private final List<UnionAdvanced> advanced = new ArrayList<>();
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-09-14T10:00:00Z"));

  @BeforeEach
  void setUp() throws Exception {
    hubs = new Hubs(new DataLayout(data).initialise(), name -> 7L);
    hub = hubs.of("ledger").orElseThrow();
    hub.create();
    Files.writeString(hub.tree().resolve("a.txt"), "one\n");
    hub.commitTree("seed", "seed");
    gate =
        new UnionGate(hubs, advanced::add, now::get, Duration.ofMillis(300), Duration.ofMinutes(2));
  }

  @Test
  void a_project_nobody_has_claimed_is_offline() {
    assertEquals(UnionGate.State.OFFLINE, gate.state("ledger"));
  }

  @Test
  void offline_writes_go_through() throws Exception {
    gate.write("ledger", () -> write("a.txt", "two\n"));
    assertEquals("two\n", Files.readString(hub.tree().resolve("a.txt")));
  }

  @Test
  void begin_commits_writes_in_flight() throws Exception {
    write("a.txt", "dirty\n");
    gate.begin("ledger", "s1");
    assertEquals(UnionGate.State.SYNCING, gate.state("ledger"));
    assertEquals(1, advanced.size(), "the partial commit advanced main");
  }

  @Test
  void a_push_is_admitted_only_while_syncing_or_live() {
    assertFalse(gate.admitPush("ledger"), "nobody has claimed the project yet");

    gate.begin("ledger", "s1");
    assertTrue(gate.admitPush("ledger"), "syncing admits a push");
    gate.pushEnded("ledger");

    gate.ready("ledger", "s1", hub.main().orElseThrow());
    assertTrue(gate.admitPush("ledger"), "live admits a push");
    gate.pushEnded("ledger");
  }

  @Test
  void a_mirror_write_waits_for_an_admitted_push_even_if_the_claim_is_gone() throws Exception {
    gate.begin("ledger", "s1");
    assertTrue(gate.admitPush("ledger"));
    gate.sessionClosed("s1");
    assertEquals(
        UnionGate.State.OFFLINE,
        gate.state("ledger"),
        "the claim is gone, but the receive in flight must still hold writes off");

    CompletableFuture<Void> writing =
        CompletableFuture.runAsync(
            () -> gate.write("ledger", () -> write("a.txt", "pushed-over\n")));

    for (int i = 0; i < 20; i++) {
      assertFalse(writing.isDone(), "the write must wait for the admitted push to end");
      Thread.sleep(10);
    }

    gate.pushEnded("ledger");
    writing.get(2, TimeUnit.SECONDS);
    assertEquals("pushed-over\n", Files.readString(hub.tree().resolve("a.txt")));

    gate.begin("ledger", "s2");
    assertTrue(gate.admitPush("ledger"));
    gate.sessionClosed("s2");
    assertEquals(UnionGate.State.OFFLINE, gate.state("ledger"));

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> gate.write("ledger", () -> write("a.txt", "late\n")));
    assertTrue(refused.getMessage().contains("push"), refused.getMessage());
    gate.pushEnded("ledger");
  }

  @Test
  void begin_waits_for_an_admitted_push_to_end() throws Exception {
    gate.begin("ledger", "s1");
    assertTrue(gate.admitPush("ledger"));
    gate.sessionClosed("s1");

    CompletableFuture<Void> beginning =
        CompletableFuture.runAsync(() -> gate.begin("ledger", "s2"));
    for (int i = 0; i < 20; i++) {
      assertFalse(beginning.isDone(), "begin must wait for the receive in flight to end");
      Thread.sleep(10);
    }
    gate.pushEnded("ledger");
    beginning.get(2, TimeUnit.SECONDS);
    assertEquals(UnionGate.State.SYNCING, gate.state("ledger"));
  }

  @Test
  void begin_while_a_push_never_ends_is_refused_as_receiving_a_push() {
    gate.begin("ledger", "s1");
    assertTrue(gate.admitPush("ledger"));
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> gate.begin("ledger", "s2"));
    assertTrue(refused.getMessage().contains("receiving a push"), refused.getMessage());
    gate.pushEnded("ledger");
  }

  @Test
  void an_advanced_consumer_that_throws_does_not_stop_begin() {
    UnionGate throwingGate =
        new UnionGate(
            hubs,
            event -> {
              throw new RuntimeException("boom");
            },
            now::get,
            Duration.ofMillis(300),
            Duration.ofMinutes(2));
    write("a.txt", "dirty\n");

    throwingGate.begin("ledger", "s1");
    assertEquals(
        UnionGate.State.SYNCING,
        throwingGate.state("ledger"),
        "the state change must survive a throwing consumer, and begin must return normally");
  }

  @Test
  void advanced_events_are_delivered_in_the_order_they_happened() {
    String seedCommit = hub.main().orElseThrow();
    List<UnionAdvanced> ordered = new ArrayList<>();
    AtomicBoolean chained = new AtomicBoolean();
    UnionGate[] chaining = new UnionGate[1];
    chaining[0] =
        new UnionGate(
            hubs,
            event -> {
              ordered.add(event);
              if (chained.compareAndSet(false, true)) {
                // Synchronously produced while the first event is still being delivered:
                // a consumer chaining a second commit must see it land after the first.
                chaining[0].pushed("ledger", event.newCommit(), seedCommit);
              }
            },
            now::get,
            Duration.ofMillis(300),
            Duration.ofMinutes(2));

    write("a.txt", "dirty\n");
    chaining[0].begin("ledger", "s1");

    assertEquals(
        2, ordered.size(), "the partial commit and the chained push must both be delivered");
    assertEquals(
        ordered.get(0).newCommit(),
        ordered.get(1).oldCommit(),
        "the second event must be delivered after the first event that caused it");
    assertEquals(seedCommit, ordered.get(1).newCommit());
  }

  @Test
  void a_write_while_syncing_waits_and_then_times_out() {
    gate.begin("ledger", "s1");
    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> gate.write("ledger", () -> write("a.txt", "late\n")));
    assertTrue(refused.getMessage().contains("syncing"));
  }

  @Test
  void a_write_waiting_on_sync_is_refused_once_the_machine_is_live() throws Exception {
    gate.begin("ledger", "s1");
    CompletableFuture<Throwable> waiting =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                gate.write("ledger", () -> write("a.txt", "late\n"));
                return null;
              } catch (Throwable t) {
                return t;
              }
            });
    Thread.sleep(50);
    gate.ready("ledger", "s1", hub.main().orElseThrow());
    Throwable outcome = waiting.get(2, TimeUnit.SECONDS);
    assertTrue(outcome instanceof WorkspaceRefusedException);
    assertTrue(outcome.getMessage().contains("live"));
  }

  @Test
  void ready_from_another_session_or_a_stale_commit_is_refused() {
    gate.begin("ledger", "s1");
    assertThrows(
        IllegalStateException.class, () -> gate.ready("ledger", "s2", hub.main().orElseThrow()));
    assertThrows(
        IllegalStateException.class,
        () -> gate.ready("ledger", "s1", "0000000000000000000000000000000000000000"));
    assertEquals(UnionGate.State.SYNCING, gate.state("ledger"));
  }

  @Test
  void ready_makes_the_session_live_and_closing_it_goes_offline() {
    gate.begin("ledger", "s1");
    gate.ready("ledger", "s1", hub.main().orElseThrow());
    assertTrue(gate.live("ledger", "s1"));
    assertFalse(gate.live("ledger", "s2"));
    gate.sessionClosed("s1");
    assertEquals(UnionGate.State.OFFLINE, gate.state("ledger"));
  }

  @Test
  void a_sync_that_never_becomes_ready_expires() {
    gate.begin("ledger", "s1");
    now.set(now.get().plus(Duration.ofMinutes(3)));
    assertEquals(UnionGate.State.OFFLINE, gate.state("ledger"));
  }

  @Test
  void a_repeated_begin_by_the_same_session_does_not_extend_the_sync() {
    gate.begin("ledger", "s1");
    now.set(now.get().plus(Duration.ofSeconds(90)));
    gate.begin("ledger", "s1");
    now.set(now.get().plus(Duration.ofSeconds(40)));
    assertEquals(
        UnionGate.State.OFFLINE,
        gate.state("ledger"),
        "the retry's begin must keep the original start, so the sync still expires");
  }

  @Test
  void abort_releases_a_write_waiting_on_the_sync() throws Exception {
    gate.begin("ledger", "s1");
    UnionGate patient = gate;
    CompletableFuture<Void> writing =
        CompletableFuture.runAsync(
            () -> patient.write("ledger", () -> write("a.txt", "after-abort\n")));
    Thread.sleep(50);
    assertFalse(writing.isDone());
    gate.abort("ledger", "s1");
    writing.get(2, TimeUnit.SECONDS);
    assertEquals(UnionGate.State.OFFLINE, gate.state("ledger"));
    assertEquals("after-abort\n", Files.readString(hub.tree().resolve("a.txt")));
  }

  @Test
  void abort_by_another_session_or_of_a_live_project_is_a_no_op() {
    gate.begin("ledger", "s1");
    gate.abort("ledger", "s2");
    assertEquals(UnionGate.State.SYNCING, gate.state("ledger"));
    gate.ready("ledger", "s1", hub.main().orElseThrow());
    gate.abort("ledger", "s1");
    assertTrue(gate.live("ledger", "s1"));
  }

  @Test
  void touch_restarts_the_sync_patience() {
    gate.begin("ledger", "s1");
    now.set(now.get().plus(Duration.ofSeconds(90)));
    gate.touch("ledger");
    now.set(now.get().plus(Duration.ofSeconds(90)));
    assertEquals(
        UnionGate.State.SYNCING,
        gate.state("ledger"),
        "a push that started 90 s in keeps the sync alive past the original 2 minutes");
    gate.touch("other");
    assertEquals(UnionGate.State.OFFLINE, gate.state("other"), "touch never claims a project");
  }

  @Test
  void run_end_commits_only_while_offline() throws Exception {
    write("a.txt", "bot\n");
    gate.runEnded("ledger", "run_9", "nightly-bot");
    assertEquals(1, advanced.size());
    gate.begin("ledger", "s1");
    gate.runEnded("ledger", "run_10", "nightly-bot");
    assertEquals(
        1, advanced.size(), "begin found nothing dirty; run end while syncing commits nothing");
  }

  private void write(String path, String content) {
    try {
      Files.writeString(hub.tree().resolve(path), content);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
