package io.aeyer.plowshare.server.ws;

import static io.aeyer.plowshare.server.ws.EventReadPolicyTest.frame;
import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.transport.PacketBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EventReadDispatchTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource(
      "io.aeyer.plowshare.server.ws.DispatcherReadAuditTest#reads")
  void audited_app_reads_also_overlap_without_dispatch_negotiation(String type) throws Exception {
    var fixture = new Fixture(new EventReadCapacity(2, 2));
    var slow = fixture.offer("slow", type, true);
    fixture.requests.start("audit");
    try {
      slow.entered.get(5, TimeUnit.SECONDS);
      var fast = fixture.offer("fast", "information.read", false);
      fast.finished.get(5, TimeUnit.SECONDS);
      assertFalse(slow.finished.isDone());
    } finally {
      slow.release.complete(null);
      fixture.closeAndAwait();
    }
  }

  @Test
  void fast_relay_read_finishes_while_a_prior_facet_read_is_still_running() throws Exception {
    var fixture = new Fixture(new EventReadCapacity(2, 2));
    var facet = fixture.offer("facet", "information.facets", true);
    fixture.requests.start("parallel");
    facet.entered.get(5, TimeUnit.SECONDS);
    var relay = fixture.offer("relay", "relay.topics", false);
    relay.finished.get(5, TimeUnit.SECONDS);
    assertFalse(facet.finished.isDone());
    assertEquals(2, fixture.maximum.get());
    facet.release.complete(null);
    fixture.closeAndAwait();
  }

  @Test
  void mutation_unknown_and_malformed_barriers_wait_reads_and_block_subsequent_reads()
      throws Exception {
    for (String barrierType :
        List.of(
            "information.tags",
            "project.member.remove",
            "application.file.save",
            "future.read",
            "malformed")) {
      var fixture = new Fixture(new EventReadCapacity(2, 2));
      var before = fixture.offer("before", "proc.calls", true);
      fixture.requests.start("barrier");
      before.entered.get(5, TimeUnit.SECONDS);
      var barrier = fixture.offer("barrier", barrierType, true);
      var after = fixture.offer("after", "usage.calls", false);
      assertFalse(barrier.entered.isDone());
      before.release.complete(null);
      barrier.entered.get(5, TimeUnit.SECONDS);
      assertTrue(before.finished.isDone());
      assertFalse(after.entered.isDone());
      barrier.release.complete(null);
      after.finished.get(5, TimeUnit.SECONDS);
      assertEquals(List.of("before", "barrier", "after"), fixture.order);
      fixture.closeAndAwait();
    }
  }

  @Test
  void global_and_connection_limits_apply_before_creating_workers_and_close_releases_waiting_bytes()
      throws Exception {
    var capacity = new EventReadCapacity(2, 2);
    var first = new Fixture(capacity);
    var one = first.offer("one", "information.facets", true);
    var two = first.offer("two", "relay.log", true);
    first.requests.start("global-first");
    one.entered.get(5, TimeUnit.SECONDS);
    two.entered.get(5, TimeUnit.SECONDS);
    var queued = first.offer("third", "relay.log", false);
    var other = new Fixture(capacity);
    var waiting = other.offer("waiting", "relay.topics", false);
    other.requests.start("global-second");
    assertThrows(TimeoutException.class, () -> waiting.entered.get(100, TimeUnit.MILLISECONDS));
    // The full global capacity is held by the two blocked callbacks. Whether its coordinator has
    // selected the frame yet or is waiting for a permit, closing must discard and release it.
    other.requests.close();
    assertEquals(0, other.budget.heldBytes());
    first.requests.close();
    assertEquals(
        2L * (frame("one", "information.facets").length() + frame("two", "relay.log").length()),
        first.budget.heldBytes());
    assertFalse(waiting.entered.isDone());
    assertFalse(queued.entered.isDone());
    assertEquals(2, first.maximum.get());
    one.release.complete(null);
    two.release.complete(null);
    first.closeAndAwait();
    // A fresh connection can use permits returned by callbacks that completed after close.
    var recovered = new Fixture(capacity);
    var ok = recovered.offer("ok", "relay.log", false);
    recovered.requests.start("recovered");
    ok.finished.get(5, TimeUnit.SECONDS);
    recovered.closeAndAwait();
  }

  @Test
  void per_connection_limit_does_not_consume_all_server_capacity() throws Exception {
    var capacity = new EventReadCapacity(1, 2);
    var first = new Fixture(capacity);
    var slow = first.offer("slow", "information.facets", true);
    first.requests.start("limited");
    slow.entered.get(5, TimeUnit.SECONDS);
    var queued = first.offer("queued", "relay.log", false);
    var other = new Fixture(capacity);
    var fast = other.offer("fast", "relay.log", false);
    other.requests.start("other");
    fast.finished.get(5, TimeUnit.SECONDS);
    assertFalse(queued.entered.isDone());
    slow.release.complete(null);
    queued.finished.get(5, TimeUnit.SECONDS);
    assertEquals(1, first.maximum.get());
    first.closeAndAwait();
    other.closeAndAwait();
  }

  @Test
  void running_reads_count_toward_the_request_limit_and_release_reservations_after_failure()
      throws Exception {
    var capacity = new EventReadCapacity(1, 1);
    var fixture = new Fixture(capacity);
    var slow = fixture.offer("slow", "relay.log", true);
    fixture.requests.start("count");
    slow.entered.get(5, TimeUnit.SECONDS);
    for (int i = 1; i < EventChannelHandler.PENDING; i++) {
      fixture.offer("queued" + i, "relay.log", false);
    }
    assertThrows(
        IllegalArgumentException.class, () -> fixture.offer("overflow", "relay.log", false));
    fixture.requests.close();
    slow.release.complete(null);
    fixture.closeAndAwait();

    var budget = new PacketBudget(1024 * 1024);
    var failed = new CountDownLatch(1);
    var requests =
        new EventRequests(
            budget,
            ignored -> {
              failed.countDown();
              throw new IllegalStateException("fixture failure");
            },
            capacity);
    requests.offer(frame("failure", "relay.log"));
    requests.start("failure");
    assertTrue(failed.await(5, TimeUnit.SECONDS));
    awaitReleased(requests, budget);
    var next = new Fixture(capacity);
    var success = next.offer("success", "relay.log", false);
    next.requests.start("after-failure");
    success.finished.get(5, TimeUnit.SECONDS);
    next.closeAndAwait();
  }

  @Test
  void invalid_concurrency_configuration_fails_before_dispatch() {
    assertThrows(IllegalArgumentException.class, () -> new EventReadCapacity(0, 2));
    assertThrows(IllegalArgumentException.class, () -> new EventReadCapacity(3, 2));
  }

  private static void awaitReleased(EventRequests requests, PacketBudget budget) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    synchronized (requests) {
      while (budget.heldBytes() != 0) {
        long remaining = deadline - System.nanoTime();
        assertTrue(remaining > 0, "Dispatcher did not release its packet reservation");
        TimeUnit.NANOSECONDS.timedWait(requests, remaining);
      }
    }
  }

  private static final class Gate {
    final CompletableFuture<Void> entered = new CompletableFuture<>();
    final CompletableFuture<Void> release = new CompletableFuture<>();
    final CompletableFuture<Void> finished = new CompletableFuture<>();
  }

  private static final class Fixture {
    final PacketBudget budget = new PacketBudget(1024 * 1024);
    final Map<String, Gate> gates = new ConcurrentHashMap<>();
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger maximum = new AtomicInteger();
    final List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
    final EventRequests requests;

    Fixture(EventReadCapacity capacity) {
      requests =
          new EventRequests(
              budget,
              raw -> {
                String id;
                try {
                  id =
                      raw.equals("malformed")
                          ? "barrier"
                          : FrameJson.reading().readValue(raw, Envelope.class).id();
                } catch (java.io.IOException invalid) {
                  throw new AssertionError(invalid);
                }
                var gate = gates.get(id);
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                order.add(id);
                gate.entered.complete(null);
                try {
                  gate.release.get(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                  throw new AssertionError(failure);
                } finally {
                  active.decrementAndGet();
                  gate.finished.complete(null);
                }
              },
              capacity);
    }

    Gate offer(String id, String type, boolean block) {
      var gate = new Gate();
      if (!block) gate.release.complete(null);
      gates.put(id, gate);
      requests.offer(type.equals("malformed") ? "malformed" : frame(id, type));
      return gate;
    }

    void closeAndAwait() throws Exception {
      requests.close();
      awaitReleased(requests, budget);
    }
  }
}
