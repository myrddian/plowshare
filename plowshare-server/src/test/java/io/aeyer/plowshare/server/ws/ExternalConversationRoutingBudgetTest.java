package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.access.ProjectAuthorization;
import io.aeyer.plowshare.server.api.ConversationView;
import io.aeyer.plowshare.server.archive.ConversationAliasProperties;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.telemetry.FailureReporter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ExternalConversationRoutingBudgetTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private static ConversationView view() {
    return new ConversationView("cnv_target", "house", 20, 0, null, false, false, null);
  }

  private static final class SqlScope implements SqlWorkBudget {
    private int depth;
    private final List<Long> deadlines = new ArrayList<>();

    @Override
    public <T> T within(Duration budget, Supplier<T> action) {
      return ReadDeadline.within(
          budget,
          () -> {
            depth++;
            deadlines.add(ReadDeadline.deadlineNanos(budget));
            try {
              return action.get();
            } finally {
              depth--;
            }
          });
    }
  }

  private static FrameRouter router(Map<String, FrameHandler> handlers, SqlScope sql) {
    return new FrameRouter(
        handlers,
        null,
        ReadAdmission.direct(),
        FailureReporter.NONE,
        new ScopedExternalConversationRequestWork(
            sql,
            new ConversationAliasProperties(TIMEOUT, Duration.ofSeconds(1)),
            mock(ExternalConversationCompletions.class)));
  }

  @Test
  void scopeBeginsBeforeCommonAuthorizationAndNestsWithoutResettingTheDeadline() {
    var sql = new SqlScope();
    Thread original = Thread.currentThread();
    var phases = new ArrayList<String>();
    var handler =
        new FrameHandler() {
          @Override
          public Outcome handle(Map<String, Object> payload, Asking asking) {
            assertEquals(original, Thread.currentThread());
            assertEquals(1, sql.depth);
            phases.add("handler");
            return sql.within(
                TIMEOUT,
                () -> {
                  assertEquals(2, sql.depth);
                  return Outcome.ok(view());
                });
          }

          @Override
          public CompletionStage<Outcome> handleAsync(Map<String, Object> payload, Asking asking) {
            throw new AssertionError("Ex scopes must not escape through an asynchronous handler");
          }
        };
    var router = router(Map.of(FrameTypes.CONVERSATION_OPEN_EX, handler), sql);
    var authority = mock(ProjectAuthorization.class);
    doCallRealMethod()
        .when(authority)
        .requireExternalConversation(anyString(), any(), anyString(), anyString());
    doAnswer(
            call -> {
              assertEquals(1, sql.depth);
              phases.add("authority");
              return null;
            })
        .when(authority)
        .require(anyString(), any(), anyString());
    doAnswer(
            call -> {
              assertEquals(1, sql.depth);
              phases.add("authority");
              return Home.of("house");
            })
        .when(authority)
        .requireExternalConversationTarget(anyString(), anyString(), anyString());
    router.useAuthorization(authority);
    var asking = new Asking("session", "alice", "socket");
    var result =
        router
            .routeAsync(
                new Envelope(
                    "ex",
                    FrameTypes.CONVERSATION_OPEN_EX,
                    Envelope.CURRENT_VERSION,
                    Map.of("project", "house")),
                asking)
            .toCompletableFuture()
            .join();
    assertEquals(Code.OK, result.code());
    assertEquals(List.of("authority", "handler", "authority"), phases);
    assertEquals(2, sql.deadlines.size());
    assertEquals(sql.deadlines.getFirst(), sql.deadlines.getLast());
    assertEquals(0, sql.depth);
  }

  @Test
  void queuedDisclosureHasAnExplicitBoundedPhaseAndOrdinaryRoutesDoNotOptIn() throws Exception {
    var sql = new SqlScope();
    var router =
        router(
            Map.of(
                "example.run",
                (payload, asking) -> {
                  assertEquals(0, sql.depth);
                  return Outcome.ok();
                }),
            sql);
    router.route(
        new Envelope("ordinary", "example.run", Envelope.CURRENT_VERSION, Map.of()),
        new Asking("session", "alice"));
    assertTrue(sql.deadlines.isEmpty());
    var authority = mock(ProjectAuthorization.class);
    doAnswer(
            call -> {
              assertEquals(1, sql.depth);
              return Home.of("house");
            })
        .when(authority)
        .requireExternalConversationTarget(anyString(), anyString(), anyString());
    router.useAuthorization(authority);
    // Serialize through the actual envelope mapper so the fixture follows the real wire key.
    String frame =
        FrameJson.answering()
            .writeValueAsString(
                new Envelope(
                    "ex", FrameTypes.CONVERSATION_OPEN_EX, Envelope.CURRENT_VERSION, Map.of()));
    router.requireExternalDisclosure(
        frame, new Asking("session", "alice", "socket"), Outcome.ok(view()));
    assertEquals(1, sql.deadlines.size());
    assertFalse(sql.depth > 0);
  }
}
