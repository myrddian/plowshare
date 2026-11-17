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
import io.aeyer.plowshare.server.agents.ExternalConversationUnavailableException;
import io.aeyer.plowshare.server.archive.ConversationAliasProperties;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.telemetry.FailureReporter;
import io.aeyer.plowshare.server.testing.LogicalSqlWorkBudget;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
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
    // This test exercises disposal before alarm delivery; expiry ownership has separate cases.
    try (var completions = new BoundedExternalConversationCompletions(Duration.ofSeconds(10), 1)) {
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
              new ScopedExternalConversationRequestWork(
                  new LogicalSqlWorkBudget(),
                  new ConversationAliasProperties(Duration.ofSeconds(2), Duration.ofSeconds(1)),
                  completions));
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
        assertThrows(
            ExternalConversationUnavailableException.class, () -> completions.begin(ignored -> {}));
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
  void postAssemblyEntrySessionCheckRunsInsideTheRemainingExSqlScope(
      boolean negotiated, boolean segmented) throws Exception {
    var scoped = new ThreadLocal<Boolean>();
    var expected = new AtomicBoolean();
    var checked = new AtomicInteger();
    var sql =
        new SqlWorkBudget() {
          @Override
          public <T> T within(Duration budget, Supplier<T> action) {
            return ReadDeadline.within(
                budget,
                () -> {
                  Boolean previous = scoped.get();
                  scoped.set(true);
                  try {
                    return action.get();
                  } finally {
                    if (previous == null) scoped.remove();
                    else scoped.set(previous);
                  }
                });
          }
        };
    var authorization = mock(SocketAuthorization.class);
    when(authorization.attach(any())).thenReturn(true);
    when(authorization.current(any()))
        .thenAnswer(
            ignored -> {
              String thread = Thread.currentThread().getName();
              if (expected.get()
                  && (thread.equals("request-operation") || thread.startsWith("event-requests-"))) {
                assertEquals(Boolean.TRUE, scoped.get());
                checked.incrementAndGet();
              }
              return true;
            });
    try (var completions = new BoundedExternalConversationCompletions(Duration.ofSeconds(2), 1)) {
      var router =
          new FrameRouter(
              Map.of(
                  FrameTypes.CONVERSATION_OPEN_EX,
                  (payload, asking) -> {
                    assertTrue(
                        checked.get() > 0, "Entry session SQL must precede the facade in scope");
                    return Outcome.ok();
                  }),
              null,
              ReadAdmission.direct(),
              FailureReporter.NONE,
              new ScopedExternalConversationRequestWork(
                  sql,
                  new ConversationAliasProperties(Duration.ofSeconds(2), Duration.ofSeconds(1)),
                  completions));
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
  void queueingConsumesTheOriginalDeadlineAndExpiredWorkNeverReachesItsFacade(
      boolean negotiated, boolean segmented) throws Exception {
    var ordinaryEntered = new CompletableFuture<Void>();
    var releaseOrdinary = new CompletableFuture<Void>();
    var facadeCalls = new AtomicInteger();
    try (var completions = new BoundedExternalConversationCompletions(Duration.ofMillis(100), 1)) {
      var router =
          new FrameRouter(
              Map.of(
                  "example.run",
                  (payload, asking) -> {
                    if (Boolean.TRUE.equals(payload.get("block"))) {
                      ordinaryEntered.complete(null);
                      releaseOrdinary.join();
                    }
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
              new ScopedExternalConversationRequestWork(
                  new LogicalSqlWorkBudget(),
                  new ConversationAliasProperties(Duration.ofSeconds(2), Duration.ofSeconds(1)),
                  completions));
      try (var fixture = new ConcurrentEventChannelTest.Fixture(segmented, router)) {
        if (negotiated) {
          fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
          fixture.reply();
        }
        fixture.send("ordinary", "example.run", Map.of("block", true));
        ordinaryEntered.get(2, TimeUnit.SECONDS);
        fixture.send("expired", FrameTypes.CONVERSATION_OPEN_EX, Map.of());
        var timeout = FrameJson.answering().readTree(fixture.reply());
        assertEquals("expired", timeout.path("id").asText());
        assertEquals("ARCHIVE_UNAVAILABLE", timeout.path("payload").path("code").asText());
        assertEquals(0, facadeCalls.get());
        assertThrows(
            ExternalConversationUnavailableException.class, () -> completions.begin(ignored -> {}));
        if (negotiated) {
          assertEquals(1, fixture.handler.dispatchMeasurements().active());
          assertEquals(1, fixture.handler.dispatchMeasurements().queued());
        }
        releaseOrdinary.complete(null);
        assertEquals(
            "ordinary", FrameJson.answering().readTree(fixture.reply()).path("id").asText());
        fixture.send("marker", "example.run", Map.of());
        assertEquals("marker", FrameJson.answering().readTree(fixture.reply()).path("id").asText());
        assertEquals(0, facadeCalls.get());
        assertNull(fixture.received.poll(100, TimeUnit.MILLISECONDS));
        try (var released = completions.begin(ignored -> {})) {
          released.answer(Outcome.ok());
        }
      } finally {
        releaseOrdinary.complete(null);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void earlyTimeoutKeepsTheOriginalPhysicalOperationAndSuppressesItsLateReply(
      boolean negotiated, boolean segmented) throws Exception {
    var entered = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    var later = new CompletableFuture<Void>();
    var firstReturned = new CompletableFuture<Void>();
    var secondReturned = new CompletableFuture<Void>();
    try (var completions = new BoundedExternalConversationCompletions(Duration.ofMillis(100), 1)) {
      var router =
          new FrameRouter(
              Map.of(
                  FrameTypes.CONVERSATION_OPEN_EX,
                  (payload, asking) -> {
                    entered.complete(null);
                    release.join();
                    return Outcome.ok(Map.of("late", "result"));
                  },
                  "example.run",
                  (payload, asking) -> {
                    later.complete(null);
                    return Outcome.ok();
                  }),
              null,
              ReadAdmission.direct(),
              FailureReporter.NONE,
              new ScopedExternalConversationRequestWork(
                  new LogicalSqlWorkBudget(),
                  new ConversationAliasProperties(Duration.ofSeconds(2), Duration.ofSeconds(1)),
                  completions));
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
                        fixture.send("first", FrameTypes.CONVERSATION_OPEN_EX, Map.of());
                        firstReturned.complete(null);
                      } catch (Exception failed) {
                        firstReturned.completeExceptionally(failed);
                      }
                    });
        entered.get(2, TimeUnit.SECONDS);
        var timeout = FrameJson.answering().readTree(fixture.reply());
        assertEquals("first", timeout.path("id").asText());
        assertEquals("ARCHIVE_UNAVAILABLE", timeout.path("payload").path("code").asText());
        String detail = timeout.path("payload").path("said").asText();
        assertTrue(detail.contains("do not replay"));
        assertFalse(detail.contains("nothing was written"));
        assertFalse(release.isDone());
        assertThrows(
            ExternalConversationUnavailableException.class, () -> completions.begin(ignored -> {}));
        if (negotiated) assertEquals(1, fixture.handler.dispatchMeasurements().active());
        if (!negotiated && !segmented) assertFalse(firstReturned.isDone());
        Thread following =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        // Unsegmented unnegotiated input runs on its original socket caller. The
                        // next input belongs after that callback; never invent concurrent callers.
                        if (!negotiated && !segmented) firstReturned.join();
                        fixture.send("second", "example.run", Map.of());
                        secondReturned.complete(null);
                      } catch (Exception failed) {
                        secondReturned.completeExceptionally(failed);
                      }
                    });
        try {
          assertThrows(TimeoutException.class, () -> later.get(50, TimeUnit.MILLISECONDS));
        } finally {
          release.complete(null);
        }
        later.get(2, TimeUnit.SECONDS);
        assertEquals("second", FrameJson.answering().readTree(fixture.reply()).path("id").asText());
        firstReturned.get(2, TimeUnit.SECONDS);
        secondReturned.get(2, TimeUnit.SECONDS);
        sender.join(2000);
        following.join(2000);
        assertFalse(sender.isAlive());
        assertFalse(following.isAlive());
        assertNull(fixture.received.poll(100, TimeUnit.MILLISECONDS));
      } finally {
        release.complete(null);
      }
    }
  }
}
