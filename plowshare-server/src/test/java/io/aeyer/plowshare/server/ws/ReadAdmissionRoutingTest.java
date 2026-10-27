package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.frames.*;
import io.aeyer.plowshare.server.reads.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReadAdmissionRoutingTest {
  @Test
  void asynchronousReadsKeepCapacityUntilCompletionAcrossSocketsAndMutationsRetainTheirRoute() {
    var admission = new BoundedReadAdmission(new ReadCapacityProperties(2, 1, 2, 1, 8, 2));
    var running = new ConcurrentHashMap<String, CompletableFuture<Outcome>>();
    var mutations = new AtomicInteger();
    FrameHandler read =
        new FrameHandler() {
          public Outcome handle(Map<String, Object> payload, Asking asking) {
            throw new AssertionError("Async path expected");
          }

          public CompletionStage<Outcome> handleAsync(Map<String, Object> payload, Asking asking) {
            return running.computeIfAbsent(asking.handle(), ignored -> new CompletableFuture<>());
          }
        };
    var router =
        new FrameRouter(
            Map.of(
                "information.facets",
                read,
                "conversation.reset",
                (payload, asking) -> {
                  mutations.incrementAndGet();
                  return Outcome.ok(List.of());
                }),
            null,
            admission);
    var request = new Envelope("read", "information.facets", Envelope.CURRENT_VERSION, Map.of());
    var alice = router.routeAsync(request, new Asking("session", "alice", "one"));
    var refused =
        router
            .routeAsync(request, new Asking("session", "alice", "two"))
            .toCompletableFuture()
            .join();
    assertEquals(
        Code.ARCHIVE_UNAVAILABLE,
        refused.code(),
        "Busy reads must not invite automatic mutation replay");
    var bob = router.routeAsync(request, new Asking("other", "bob", "three"));
    router.disconnected("one");
    assertEquals(
        2,
        admission.active(ReadAdmission.Work.INTERACTIVE),
        "Disconnect is not physical completion");
    assertEquals(
        Code.OK,
        router
            .routeAsync(
                new Envelope("write", "conversation.reset", Envelope.CURRENT_VERSION, Map.of()),
                new Asking("session", "alice", "two"))
            .toCompletableFuture()
            .join()
            .code());
    assertEquals(1, mutations.get());
    running.get("alice").complete(Outcome.ok(List.of()));
    running.get("bob").complete(Outcome.ok(List.of()));
    assertEquals(Code.OK, alice.toCompletableFuture().join().code());
    assertEquals(Code.OK, bob.toCompletableFuture().join().code());
    assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
  }
}
