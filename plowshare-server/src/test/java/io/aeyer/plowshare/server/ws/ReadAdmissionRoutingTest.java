package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.reads.BoundedReadAdmission;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.reads.ReadCapacityProperties;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadSchedulingProperties;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import io.aeyer.plowshare.server.telemetry.FailureReporter;
import io.aeyer.plowshare.server.telemetry.WorkPerformanceReporter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        ReadDeadline.within(
            Duration.ofNanos(1),
            () ->
                router
                    .routeAsync(request, new Asking("session", "alice", "two"))
                    .toCompletableFuture()
                    .join());
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

  @Test
  void ordinaryBurstsWaitBeforeStartingTheNextHandler() throws Exception {
    var admission = new BoundedReadAdmission(new ReadCapacityProperties(2, 1, 2, 1, 8, 2));
    var source = new CompletableFuture<Outcome>();
    var calls = new AtomicInteger();
    FrameHandler handler =
        new FrameHandler() {
          public Outcome handle(Map<String, Object> payload, Asking asking) {
            throw new AssertionError();
          }

          public CompletionStage<Outcome> handleAsync(Map<String, Object> payload, Asking asking) {
            calls.incrementAndGet();
            return asking.handle().equals("alice")
                ? source
                : CompletableFuture.completedFuture(Outcome.ok(List.of()));
          }
        };
    var router = new FrameRouter(Map.of("information.facets", handler), null, admission);
    var request = new Envelope("read", "information.facets", Envelope.CURRENT_VERSION, Map.of());
    var first = router.routeAsync(request, new Asking("session", "alice", "one"));
    try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var next =
          callers.submit(
              () ->
                  router
                      .routeAsync(request, new Asking("session", "alice", "two"))
                      .toCompletableFuture()
                      .join());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (admission.waitingInteractive() != 1 && System.nanoTime() - deadline < 0)
        Thread.sleep(1);
      assertEquals(1, admission.waitingInteractive());
      assertEquals(1, calls.get());
      assertEquals(
          Code.OK,
          router
              .routeAsync(request, new Asking("session", "bob", "three"))
              .toCompletableFuture()
              .join()
              .code());
      source.complete(Outcome.ok(List.of()));
      assertEquals(Code.OK, first.toCompletableFuture().join().code());
      assertEquals(Code.OK, next.get(5, TimeUnit.SECONDS).code());
      assertEquals(3, calls.get());
    }
    assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
  }

  @Test
  void admittedHandlersHaveTheirOwnLifetimeAndCancelledRepliesRetainThePhysicalSeat()
      throws Exception {
    for (boolean cancel : List.of(false, true)) {
      var admission =
          new BoundedReadAdmission(
              new ReadCapacityProperties(1, 1, 2, 1, 8, 2),
              new ReadSchedulingProperties(64, 4, 2000, 2000, 8, 4, 20));
      var source = new CompletableFuture<Outcome>();
      FrameHandler handler =
          new FrameHandler() {
            public Outcome handle(Map<String, Object> payload, Asking asking) {
              throw new AssertionError();
            }

            public CompletionStage<Outcome> handleAsync(
                Map<String, Object> payload, Asking asking) {
              return source;
            }
          };
      var router = new FrameRouter(Map.of("information.facets", handler), null, admission);
      var reply =
          router
              .routeAsync(
                  new Envelope("read", "information.facets", Envelope.CURRENT_VERSION, Map.of()),
                  new Asking("session", "alice", "one"))
              .toCompletableFuture();
      if (cancel) assertTrue(reply.cancel(true));
      else {
        Thread.sleep(40);
        assertFalse(reply.isDone(), "Queue waiting must not impose a handler lifetime");
      }
      assertFalse(source.isDone());
      assertEquals(1, admission.active(ReadAdmission.Work.INTERACTIVE));
      source.complete(Outcome.ok(List.of()));
      assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
      if (!cancel) assertEquals(Code.OK, reply.get(5, TimeUnit.SECONDS).code());
    }
  }

  @Test
  void cancellingAnObserverReplyRetainsItsSeatUntilTheSourceSettles() {
    var admission = new BoundedReadAdmission(new ReadCapacityProperties(1, 1, 2, 1, 8, 2));
    var source = new CompletableFuture<Outcome>();
    FrameHandler handler =
        new FrameHandler() {
          public Outcome handle(Map<String, Object> payload, Asking asking) {
            throw new AssertionError();
          }

          public CompletionStage<Outcome> handleAsync(Map<String, Object> payload, Asking asking) {
            return source;
          }
        };
    var router = new FrameRouter(Map.of("information.await", handler), null, admission);
    var reply =
        router
            .routeAsync(
                new Envelope("read", "information.await", Envelope.CURRENT_VERSION, Map.of()),
                new Asking("session", "alice", "one"))
            .toCompletableFuture();
    assertTrue(reply.cancel(true));
    assertEquals(1, admission.active(ReadAdmission.Work.OBSERVER));
    source.complete(Outcome.ok(List.of()));
    assertEquals(0, admission.active(ReadAdmission.Work.OBSERVER));
  }

  @Test
  void onlyPreHandlerCapacityIsFlowControlAndItDoesNotReportAServerFailure() throws Exception {
    var admission =
        new BoundedReadAdmission(
            new ReadCapacityProperties(1, 1, 2, 1, 8, 2),
            new ReadSchedulingProperties(64, 4, 2000, 2000, 8, 4, 1));
    var calls = new AtomicInteger();
    var failures = mock(FailureReporter.class);
    var router =
        new FrameRouter(
            Map.of(
                "information.facets",
                (payload, asking) -> {
                  calls.incrementAndGet();
                  throw new ReadUnavailableException();
                }),
            null,
            admission,
            failures);
    String frame =
        FrameJson.answering()
            .writeValueAsString(
                new Envelope("read", "information.facets", Envelope.CURRENT_VERSION, Map.of()));
    var held = admission.admit("alice", ReadAdmission.Work.INTERACTIVE);
    try (held) {
      var refused =
          router
              .routeForTransport(frame, new Asking("session", "alice", "one"))
              .toCompletableFuture()
              .join();
      assertEquals(Code.CAPACITY_EXHAUSTED, refused.outcome().code());
      assertTrue(refused.refusedBeforeHandler());
      assertEquals(0, calls.get());
      verifyNoInteractions(failures);
    }
    var failed =
        router
            .routeForTransport(frame, new Asking("session", "alice", "one"))
            .toCompletableFuture()
            .join();
    assertFalse(failed.refusedBeforeHandler());
    assertEquals(Code.ARCHIVE_UNAVAILABLE, failed.outcome().code());
    assertEquals(1, calls.get());
  }

  @Test
  void aStartedHandlerCannotForgeAPreHandlerCapacityPromise() throws Exception {
    var router =
        new FrameRouter(
            Map.of(
                "information.facets",
                (payload, asking) ->
                    Outcome.failed(Code.CAPACITY_EXHAUSTED, "too late to promise no work")));
    String frame =
        FrameJson.answering()
            .writeValueAsString(
                new Envelope("read", "information.facets", Envelope.CURRENT_VERSION, Map.of()));
    var failed =
        router
            .routeForTransport(frame, new Asking("session", "alice", "one"))
            .toCompletableFuture()
            .join();
    assertFalse(failed.refusedBeforeHandler());
    assertEquals(Code.INTERNAL_ERROR, failed.outcome().code());
  }

  @Test
  void warningObservationStartsOnlyForAdmittedWorkAndRetainsItsPhysicalLifetime() {
    var admission =
        new BoundedReadAdmission(
            new ReadCapacityProperties(1, 1, 2, 1, 8, 2),
            new ReadSchedulingProperties(64, 4, 2000, 2000, 8, 4, 1));
    var source = new CompletableFuture<Outcome>();
    var opened = new AtomicInteger();
    var closed = new AtomicInteger();
    FrameHandler handler =
        new FrameHandler() {
          public Outcome handle(Map<String, Object> payload, Asking asking) {
            throw new AssertionError();
          }

          public CompletionStage<Outcome> handleAsync(Map<String, Object> payload, Asking asking) {
            return source;
          }
        };
    var router = new FrameRouter(Map.of("information.facets", handler), null, admission);
    router.useWorkPerformance(
        (kind, operation) -> {
          assertEquals(WorkPerformanceReporter.Kind.REQUEST, kind);
          assertEquals("information.facets", operation);
          opened.incrementAndGet();
          return () -> closed.incrementAndGet();
        });
    var request = new Envelope("read", "information.facets", Envelope.CURRENT_VERSION, Map.of());
    var held = admission.admit("alice", ReadAdmission.Work.INTERACTIVE);
    try (held) {
      assertEquals(
          Code.CAPACITY_EXHAUSTED,
          router
              .routeAsync(request, new Asking("session", "alice", "one"))
              .toCompletableFuture()
              .join()
              .code());
      assertEquals(0, opened.get());
    }
    var reply =
        router.routeAsync(request, new Asking("session", "alice", "one")).toCompletableFuture();
    assertEquals(1, opened.get());
    assertEquals(0, closed.get());
    reply.cancel(true);
    assertEquals(0, closed.get(), "Caller cancellation is not physical handler settlement");
    source.complete(Outcome.ok(List.of()));
    assertEquals(1, closed.get());
    assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
  }
}
