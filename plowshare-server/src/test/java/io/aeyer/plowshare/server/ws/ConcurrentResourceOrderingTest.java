package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.transport.PacketBudget;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConcurrentResourceOrderingTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "project.member.remove",
        "provider.connection.change",
        "application.file.save",
        "approval.answer"
      })
  void queued_mutations_drain_prior_reads_and_prevent_later_reads_from_overtaking(String mutation)
      throws Exception {
    var budget = new PacketBudget(1024 * 1024);
    var firstEntered = new CompletableFuture<Void>();
    var mutationEntered = new CompletableFuture<Void>();
    var secondMutationEntered = new CompletableFuture<Void>();
    var afterEntered = new CompletableFuture<Void>();
    var firstRelease = new CompletableFuture<Void>();
    var mutationRelease = new CompletableFuture<Void>();
    var secondMutationRelease = new CompletableFuture<Void>();
    var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
    try (var dispatcher = new ConcurrentRequests(budget, 2, 4, new DispatchTelemetry())) {
      var connection =
          dispatcher.connect(
              "mutation-order",
              (frame, delivered) -> {
                order.add(frame);
                return switch (frame) {
                  case "before" -> {
                    firstEntered.complete(null);
                    yield firstRelease.thenRun(delivered);
                  }
                  case "mutation" -> {
                    mutationEntered.complete(null);
                    yield mutationRelease.thenRun(delivered);
                  }
                  case "second-mutation" -> {
                    secondMutationEntered.complete(null);
                    yield secondMutationRelease.thenRun(delivered);
                  }
                  default -> {
                    afterEntered.complete(null);
                    delivered.run();
                    yield CompletableFuture.completedFuture(null);
                  }
                };
              },
              () -> fail("Unexpected failure"),
              8);
      try {
        connection.offer("before", typed("before", "proc.calls"), "alice");
        firstEntered.get(5, TimeUnit.SECONDS);
        connection.offer("mutation", typed("mutation", mutation), "alice");
        connection.offer("second-mutation", typed("second-mutation", mutation), "alice");
        connection.offer("after", typed("after", "usage.calls"), "alice");
        // Capacity is free. The queued barrier must prevent the later finite read overtaking it.
        assertThrows(
            java.util.concurrent.TimeoutException.class,
            () -> afterEntered.get(100, TimeUnit.MILLISECONDS));
        assertFalse(mutationEntered.isDone());
        firstRelease.complete(null);
        mutationEntered.get(5, TimeUnit.SECONDS);
        assertFalse(secondMutationEntered.isDone());
        assertFalse(afterEntered.isDone());
        mutationRelease.complete(null);
        secondMutationEntered.get(5, TimeUnit.SECONDS);
        assertFalse(afterEntered.isDone());
        secondMutationRelease.complete(null);
        afterEntered.get(5, TimeUnit.SECONDS);
        assertEquals(java.util.List.of("before", "mutation", "second-mutation", "after"), order);
      } finally {
        firstRelease.complete(null);
        mutationRelease.complete(null);
        secondMutationRelease.complete(null);
      }
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"conversation.context.count", "conversation.context.snapshot", "todos.read"})
  void conversation_inspection_waits_for_its_mutation_while_another_conversation_progresses(
      String type) throws Exception {
    var budget = new PacketBudget(1024 * 1024);
    var entered = new CompletableFuture<Void>();
    var same = new CompletableFuture<Void>();
    var other = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    try (var dispatcher = new ConcurrentRequests(budget, 2, 4, new DispatchTelemetry())) {
      var connection =
          dispatcher.connect(
              "contexts",
              (frame, delivered) -> {
                if (frame.equals("mutation")) {
                  entered.complete(null);
                  return release.thenRun(delivered);
                }
                (frame.equals("same") ? same : other).complete(null);
                delivered.run();
                return CompletableFuture.completedFuture(null);
              },
              () -> fail("Unexpected failure"),
              8);
      try {
        connection.offer("mutation", request("mutation", "one"), "alice");
        entered.get(5, TimeUnit.SECONDS);
        connection.offer(
            "same",
            new Envelope("same", type, Envelope.CURRENT_VERSION, Map.of("conversation", "one")),
            "alice");
        connection.offer(
            "other",
            new Envelope("other", type, Envelope.CURRENT_VERSION, Map.of("conversation", "two")),
            "alice");
        other.get(5, TimeUnit.SECONDS);
        assertFalse(same.isDone());
        release.complete(null);
        same.get(5, TimeUnit.SECONDS);
      } finally {
        release.complete(null);
      }
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
    }
  }

  private Envelope request(String id, String conversation) {
    return new Envelope(
        id, "conversation.resume", Envelope.CURRENT_VERSION, Map.of("conversation", conversation));
  }

  @Test
  void conflict_waits_across_connections_while_unrelated_equal_hash_key_overtakes()
      throws Exception {
    assertEquals("Aa".hashCode(), "BB".hashCode());
    var budget = new PacketBudget(1024 * 1024);
    var telemetry = new DispatchTelemetry();
    try (var dispatcher = new ConcurrentRequests(budget, 1, 2, telemetry)) {
      var release = new CompletableFuture<Void>();
      var firstEntered = new CompletableFuture<Void>();
      var secondEntered = new CompletableFuture<Void>();
      var unrelatedEntered = new CompletableFuture<Void>();
      var first =
          dispatcher.connect(
              "one",
              (frame, delivered) -> {
                firstEntered.complete(null);
                return release.thenRun(delivered);
              },
              () -> fail("Unexpected failure"),
              1);
      var second =
          dispatcher.connect(
              "two",
              (frame, delivered) -> {
                secondEntered.complete(null);
                delivered.run();
                return CompletableFuture.completedFuture(null);
              },
              () -> fail("Unexpected failure"),
              1);
      var unrelated =
          dispatcher.connect(
              "three",
              (frame, delivered) -> {
                unrelatedEntered.complete(null);
                delivered.run();
                return CompletableFuture.completedFuture(null);
              },
              () -> fail("Unexpected failure"),
              1);
      first.offer("first", request("first", "Aa"), "alice");
      firstEntered.get(5, TimeUnit.SECONDS);
      second.offer("second", request("second", "Aa"), "bob");
      unrelated.offer("unrelated", request("unrelated", "BB"), "alice");
      unrelatedEntered.get(5, TimeUnit.SECONDS);
      assertFalse(secondEntered.isDone());
      release.complete(null);
      secondEntered.get(5, TimeUnit.SECONDS);
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, telemetry.snapshot().resourceKeys());
      assertTrue(telemetry.snapshot().contentions() >= 1);
      assertEquals(0, budget.heldBytes());
    }
  }

  @Test
  void running_and_queued_requests_share_the_advertised_pending_bound() throws Exception {
    var budget = new PacketBudget(1024 * 1024);
    var telemetry = new DispatchTelemetry();
    try (var dispatcher = new ConcurrentRequests(budget, 1, 2, telemetry)) {
      var entered = new CompletableFuture<Void>();
      var release = new CompletableFuture<Void>();
      var workers = new AtomicInteger();
      var connection =
          dispatcher.connect(
              "bounded",
              (frame, delivered) -> {
                workers.incrementAndGet();
                entered.complete(null);
                return release;
              },
              () -> fail("Unexpected failure"),
              64);
      connection.offer("x", request("first", "same"), "alice");
      entered.get(5, TimeUnit.SECONDS);
      for (int n = 1; n < 64; n++) connection.offer("x", request("id-" + n, "same"), "alice");
      assertThrows(
          IllegalArgumentException.class,
          () -> connection.offer("x", request("overflow", "same"), "alice"));
      assertEquals(1, workers.get());
      assertEquals(128, budget.heldBytes());
      connection.close();
      assertEquals(2, budget.heldBytes());
      release.complete(null);
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, telemetry.snapshot().queued());
      assertEquals(0, telemetry.snapshot().resourceKeys());
      assertEquals(1, telemetry.snapshot().rejected());
      assertEquals(0, budget.heldBytes());
    }
  }

  private Envelope typed(String id, String type) {
    return new Envelope(id, type, Envelope.CURRENT_VERSION, Map.of());
  }

  @Test
  void the_desktop_read_hot_path_is_finite() {
    for (String type :
        new String[] {
          "board.topics",
          "swarm.status",
          "admin.status",
          "document.list",
          "document.detail",
          "inbox.list",
          "orchestration.list"
        }) {
      var policy = DispatchPolicy.decode(typed("id", type), "alice", "connection");
      assertEquals(DispatchPolicy.Kind.FINITE, policy.kind(), type);
      assertTrue(policy.resources().isEmpty(), type);
    }
    // The conservative default is deliberately unchanged for unaudited operations.
    assertEquals(
        DispatchPolicy.Kind.BARRIER,
        DispatchPolicy.decode(typed("id", "unaudited.operation"), "alice", "connection").kind());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "orchestration.list,document.detail",
    "orchestration.list,document.list",
    "orchestration.list,information.read",
    "inbox.list,document.detail",
    "inbox.list,document.list",
    "inbox.list,information.read"
  })
  void a_library_read_uses_the_free_permit_while_an_activity_read_is_still_running(
      String background, String foreground) throws Exception {
    var budget = new PacketBudget(1024 * 1024);
    var telemetry = new DispatchTelemetry();
    var release = new CompletableFuture<Void>();
    try (var dispatcher = new ConcurrentRequests(budget, 2, 4, telemetry)) {
      var activityEntered = new CompletableFuture<Void>();
      var libraryEntered = new CompletableFuture<Void>();
      var connection =
          dispatcher.connect(
              "library",
              (frame, delivered) -> {
                if (frame.equals("activity")) {
                  activityEntered.complete(null);
                  return release.thenRun(delivered);
                }
                libraryEntered.complete(null);
                delivered.run();
                return CompletableFuture.completedFuture(null);
              },
              () -> fail("Unexpected transport failure"),
              8);
      try {
        connection.offer("activity", typed("poll", background), "alice");
        activityEntered.get(5, TimeUnit.SECONDS);
        connection.offer("library", typed("read", foreground), "alice");
        // A connection-wide barrier would wait for release, despite a free execution permit.
        libraryEntered.get(5, TimeUnit.SECONDS);
        assertFalse(release.isDone());
      } finally {
        release.complete(null);
      }
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
      assertEquals(0, telemetry.snapshot().resourceKeys());
    }
  }

  @Test
  void two_finite_reads_overlap_on_one_connection() throws Exception {
    var budget = new PacketBudget(1024 * 1024);
    try (var dispatcher = new ConcurrentRequests(budget, 2, 4, new DispatchTelemetry())) {
      var entered = new AtomicInteger();
      var bothEntered = new CompletableFuture<Void>();
      var release = new CompletableFuture<Void>();
      var connection =
          dispatcher.connect(
              "reads",
              (frame, delivered) -> {
                if (entered.incrementAndGet() == 2) bothEntered.complete(null);
                return release.thenRun(delivered);
              },
              () -> fail("Unexpected failure"),
              4);
      connection.offer("a", typed("a", "board.topics"), "alice");
      connection.offer("b", typed("b", "swarm.status"), "alice");
      // Both are inside the handler at once; a barrier would have held the second back.
      bothEntered.get(5, TimeUnit.SECONDS);
      release.complete(null);
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
    }
  }

  @Test
  void a_finite_read_still_waits_behind_a_barrier_on_its_connection() throws Exception {
    var budget = new PacketBudget(1024 * 1024);
    try (var dispatcher = new ConcurrentRequests(budget, 2, 4, new DispatchTelemetry())) {
      var barrierEntered = new CompletableFuture<Void>();
      var readEntered = new CompletableFuture<Void>();
      var releaseBarrier = new CompletableFuture<Void>();
      var connection =
          dispatcher.connect(
              "mixed",
              (frame, delivered) -> {
                if (frame.equals("barrier")) {
                  barrierEntered.complete(null);
                  return releaseBarrier.thenRun(delivered);
                }
                readEntered.complete(null);
                delivered.run();
                return CompletableFuture.completedFuture(null);
              },
              () -> fail("Unexpected failure"),
              4);
      connection.offer("barrier", typed("barrier", "unaudited.operation"), "alice");
      barrierEntered.get(5, TimeUnit.SECONDS);
      connection.offer("read", typed("read", "admin.status"), "alice");
      // Per-connection and global capacity are both free, so only the barrier can be holding the
      // read back. A bounded wait is the only way to observe that something did not happen.
      assertThrows(
          java.util.concurrent.TimeoutException.class,
          () -> readEntered.get(300, TimeUnit.MILLISECONDS));
      releaseBarrier.complete(null);
      readEntered.get(5, TimeUnit.SECONDS);
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
    }
  }
}
