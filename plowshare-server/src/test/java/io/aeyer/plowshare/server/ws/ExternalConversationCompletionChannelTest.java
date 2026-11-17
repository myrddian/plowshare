package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.protocol.transport.ConcurrentDispatch;
import io.aeyer.plowshare.server.api.ConversationView;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import io.aeyer.plowshare.server.telemetry.FailureReporter;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExternalConversationCompletionChannelTest {
  @ParameterizedTest
  @CsvSource({"true,false", "true,true", "false,true"})
  void closingAConnectionDisposesItsUnstartedExTokenWithoutReleasingActiveWorkEarly(
      boolean negotiated, boolean segmented) throws Exception {
    var ordinaryEntered = new CompletableFuture<Void>();
    var releaseOrdinary = new CompletableFuture<Void>();
    var facadeCalls = new AtomicInteger();
    // Closing a queued request releases only its own unstarted completion slot.
    try (var completions = new BoundedExternalConversationCompletions(1)) {
      var router =
          new FrameRouter(
              Map.of(
                  "example.run",
                  (payload, asking) -> {
                    ordinaryEntered.complete(null);
                    releaseOrdinary.join();
                    return Outcome.ok();
                  },
                  FrameTypes.CONVERSATION_OPEN_EX,
                  (payload, asking) -> {
                    facadeCalls.incrementAndGet();
                    return Outcome.ok();
                  }),
              null,
              ReadAdmission.direct(),
              FailureReporter.NONE,
              new ScopedExternalConversationRequestWork(completions));
      var fixture =
          new ConcurrentEventChannelTest.Fixture(
              segmented, router, handler -> handler.useRequestDrain(Duration.ofMillis(20)));
      try {
        if (negotiated) {
          fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
          fixture.reply();
        }
        fixture.send("ordinary", "example.run", Map.of());
        ordinaryEntered.get(2, TimeUnit.SECONDS);
        fixture.send("never-started", FrameTypes.CONVERSATION_OPEN_EX, Map.of());
        assertThrows(ReadUnavailableException.class, () -> completions.begin(ignored -> {}));
        fixture.close();
        assertFalse(releaseOrdinary.isDone());
        assertEquals(0, facadeCalls.get());
        try (var freed = completions.begin(ignored -> {})) {
          freed.answer(Outcome.ok());
        }
      } finally {
        releaseOrdinary.complete(null);
        fixture.close();
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"true,false", "true,true", "false,true"})
  void postAssemblyEntrySessionCheckDoesNotCreateAWorkflowSqlScope(
      boolean negotiated, boolean segmented) throws Exception {
    var expected = new AtomicBoolean();
    var checked = new AtomicInteger();
    var authorization = mock(SocketAuthorization.class);
    when(authorization.attach(any())).thenReturn(true);
    when(authorization.current(any()))
        .thenAnswer(
            ignored -> {
              String thread = Thread.currentThread().getName();
              if (expected.get()
                  && (thread.equals("request-operation") || thread.startsWith("event-requests-"))) {
                assertTrue(ReadDeadline.currentDeadlineNanos().isEmpty());
                checked.incrementAndGet();
              }
              return true;
            });
    try (var completions = new BoundedExternalConversationCompletions(1)) {
      var router =
          new FrameRouter(
              Map.of(
                  FrameTypes.CONVERSATION_OPEN_EX,
                  (payload, asking) -> {
                    assertTrue(
                        checked.get() > 0, "Entry session authorization must precede the facade");
                    return Outcome.ok();
                  }),
              null,
              ReadAdmission.direct(),
              FailureReporter.NONE,
              new ScopedExternalConversationRequestWork(completions));
      try (var fixture =
          new ConcurrentEventChannelTest.Fixture(
              segmented, router, handler -> handler.useSocketAuthorization(authorization))) {
        if (negotiated) {
          fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
          fixture.reply();
        }
        expected.set(true);
        fixture.send("ex", FrameTypes.CONVERSATION_OPEN_EX, Map.of());
        var reply = FrameJson.answering().readTree(fixture.reply());
        assertEquals("OK", reply.path("payload").path("code").asText());
        assertTrue(checked.get() > 0);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"true,false", "true,true", "false,true"})
  void queuedAcceptedWorkStartsOnceAfterItsPredecessorSettles(boolean negotiated, boolean segmented)
      throws Exception {
    var entered = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    var calls = new AtomicInteger();
    try (var completions = new BoundedExternalConversationCompletions(1)) {
      var router =
          new FrameRouter(
              Map.of(
                  "example.run",
                  (payload, asking) -> {
                    entered.complete(null);
                    release.join();
                    return Outcome.ok();
                  },
                  FrameTypes.CONVERSATION_OPEN_EX,
                  (payload, asking) -> {
                    calls.incrementAndGet();
                    return Outcome.ok();
                  }),
              null,
              ReadAdmission.direct(),
              FailureReporter.NONE,
              new ScopedExternalConversationRequestWork(completions));
      try (var fixture = new ConcurrentEventChannelTest.Fixture(segmented, router)) {
        if (negotiated) {
          fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
          fixture.reply();
        }
        fixture.send("first", "example.run", Map.of());
        entered.get(2, TimeUnit.SECONDS);
        fixture.send("queued", FrameTypes.CONVERSATION_OPEN_EX, Map.of());
        assertNull(fixture.received.poll(150, TimeUnit.MILLISECONDS));
        assertEquals(0, calls.get());
        assertThrows(ReadUnavailableException.class, () -> completions.begin(ignored -> {}));
        release.complete(null);
        assertEquals("first", FrameJson.answering().readTree(fixture.reply()).path("id").asText());
        var response = FrameJson.answering().readTree(fixture.reply());
        assertEquals("queued", response.path("id").asText());
        assertEquals("OK", response.path("payload").path("code").asText());
        assertEquals(1, calls.get());
      } finally {
        release.complete(null);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void slowAcceptedWorkKeepsItsPhysicalOperationAndReturnsItsCompletedResult(
      boolean negotiated, boolean segmented) throws Exception {
    var entered = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    var returned = new CompletableFuture<Void>();
    var calls = new AtomicInteger();
    try (var completions = new BoundedExternalConversationCompletions(1)) {
      var router =
          new FrameRouter(
              Map.of(
                  FrameTypes.CONVERSATION_OPEN_EX,
                  (payload, asking) -> {
                    calls.incrementAndGet();
                    entered.complete(null);
                    release.join();
                    return Outcome.ok(
                        new ConversationView(
                            "cnv_retained", "house", 20, 0, null, false, false, null));
                  }),
              null,
              ReadAdmission.direct(),
              FailureReporter.NONE,
              new ScopedExternalConversationRequestWork(completions));
      try (var fixture = new ConcurrentEventChannelTest.Fixture(segmented, router)) {
        if (negotiated) {
          fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
          fixture.reply();
        }
        Thread sender =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        fixture.send("slow", FrameTypes.CONVERSATION_OPEN_EX, Map.of());
                        returned.complete(null);
                      } catch (Exception failure) {
                        returned.completeExceptionally(failure);
                      }
                    });
        try {
          entered.get(2, TimeUnit.SECONDS);
          assertNull(fixture.received.poll(150, TimeUnit.MILLISECONDS));
          assertThrows(ReadUnavailableException.class, () -> completions.begin(ignored -> {}));
          assertFalse(release.isDone());
          release.complete(null);
          var response = FrameJson.answering().readTree(fixture.reply());
          assertEquals("slow", response.path("id").asText());
          assertEquals("OK", response.path("payload").path("code").asText());
          assertEquals(
              "cnv_retained", response.path("payload").path("payload").path("id").asText());
          returned.get(2, TimeUnit.SECONDS);
          sender.join(2000);
          assertFalse(sender.isAlive());
          assertEquals(1, calls.get());
        } finally {
          release.complete(null);
        }
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"true,false", "true,true", "false,true"})
  void occupiedCompletionSlotsRefuseOnlyTheUnstartedIncomingRequest(
      boolean negotiated, boolean segmented) throws Exception {
    var entered = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    var calls = new AtomicInteger();
    try (var completions = new BoundedExternalConversationCompletions(1)) {
      var router =
          new FrameRouter(
              Map.of(
                  FrameTypes.CONVERSATION_OPEN_EX,
                  (payload, asking) -> {
                    if (calls.incrementAndGet() == 1) {
                      entered.complete(null);
                      release.join();
                    }
                    return Outcome.ok();
                  }),
              null,
              ReadAdmission.direct(),
              FailureReporter.NONE,
              new ScopedExternalConversationRequestWork(completions));
      try (var fixture = new ConcurrentEventChannelTest.Fixture(segmented, router)) {
        if (negotiated) {
          fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
          fixture.reply();
        }
        fixture.send("accepted", FrameTypes.CONVERSATION_OPEN_EX, Map.of());
        entered.get(2, TimeUnit.SECONDS);
        fixture.send("incoming", FrameTypes.CONVERSATION_OPEN_EX, Map.of());
        var refusal = FrameJson.answering().readTree(fixture.reply());
        assertEquals("incoming", refusal.path("id").asText());
        assertEquals("CAPACITY_EXHAUSTED", refusal.path("payload").path("code").asText());
        assertEquals(1, calls.get());
        assertFalse(release.isDone());
        release.complete(null);
        var accepted = FrameJson.answering().readTree(fixture.reply());
        assertEquals("accepted", accepted.path("id").asText());
        assertEquals("OK", accepted.path("payload").path("code").asText());
        fixture.send("incoming", FrameTypes.CONVERSATION_OPEN_EX, Map.of());
        var retried = FrameJson.answering().readTree(fixture.reply());
        assertEquals("incoming", retried.path("id").asText());
        assertEquals("OK", retried.path("payload").path("code").asText());
        assertEquals(2, calls.get());
      } finally {
        release.complete(null);
      }
    }
  }
}
