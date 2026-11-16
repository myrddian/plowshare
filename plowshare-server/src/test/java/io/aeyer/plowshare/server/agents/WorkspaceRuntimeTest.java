package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.RunProviders;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real runtime and accounting, with deterministic transport loss mid-batch. */
@Timeout(10)
@SuppressWarnings("try")
class WorkspaceRuntimeTest {
  @Test
  void mid_batch_disconnect_retains_completed_tools_and_resumes_without_an_extra_model_call()
      throws Exception {
    var fixture = new RetainedExecutionTest();
    var location = new Presence("files", "machine", "/fixture/work", "project");
    var serving = new AtomicReference<>(location);
    var pauses = new WorkspaceRuns(id -> Optional.ofNullable(serving.get()), Duration.ofMillis(5));
    var sessions = new SessionRegistry();
    sessions.attach("transport", Role.LISTENER, new Object(), "alice");
    sessions.recover("transport", "files", "alice");
    pauses.useSessions(sessions);
    var cancelled = new AtomicBoolean();
    var paused = new CountDownLatch(1);
    var completed = new CompletableFuture<Outcome>();
    var first = fixture.tool("get_date");
    var second = fixture.tool("search");
    when(first.run(anyString(), eq(fixture.home)))
        .thenAnswer(
            call -> {
              // Reusing the viewer for another folder cannot retarget this run's captured session.
              sessions.recover("transport", "another-workspace", "alice");
              serving.set(null);
              return "completed effect, retained once";
            });
    when(fixture.dispatcher.stream(any(), any(), any()))
        .thenReturn(
            fixture.calls(
                new ToolCall("one", "get_date", "{}"), new ToolCall("two", "search", "{}")),
            fixture.answer());
    RunProviders providers =
        (home, grants, session, owner) -> {
          assertEquals("files", session);
          pauses.requires("files");
          return List.of();
        };
    var definition =
        fixture
            .definition("chat", List.of("get_date", "search"), List.of(), true)
            .withScopes(List.of(Grant.parse("workspace:read")));
    var runtime =
        new JobRuntime(
            fixture.dispatcher,
            List.of(first, second),
            () -> new AgentRegistry(Map.of(definition.name(), definition)),
            providers,
            Instant::now);
    runtime.useWorkspaceRuns(pauses);
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var scope =
                      pauses.enter(
                          "job_fixture",
                          cancelled::get,
                          (state, reason) -> {
                            if (state == Job.State.PAUSED) paused.countDown();
                          })) {
                    completed.complete(
                        runtime.run(
                            definition,
                            "Complete the task",
                            fixture.home,
                            fixture.budget,
                            cancelled::get,
                            "transport",
                            JobWatch.UNWATCHED,
                            fixture.transcript,
                            TurnCap.of(8),
                            List.of(),
                            "alice",
                            false));
                  } catch (Throwable failed) {
                    completed.completeExceptionally(failed);
                  }
                });
    try {
      assertTrue(
          paused.await(2, TimeUnit.SECONDS),
          () ->
              completed.isDone()
                  ? completed.join().toString()
                  : "Worker did not reach the pause boundary");
      assertFalse(completed.isDone());
      verify(first, times(1)).run("{}", fixture.home);
      verify(second, never()).run(anyString(), any());
      assertEquals(1, fixture.budget.spent());
      serving.set(new Presence("files", "machine", "/fixture/work", "project"));
      Outcome outcome = completed.get(3, TimeUnit.SECONDS);
      assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
      assertEquals(2, outcome.modelCalls());
      assertEquals(2, fixture.budget.spent());
      verify(first, times(1)).run("{}", fixture.home);
      verify(second, times(1)).run("{}", fixture.home);
    } finally {
      cancelled.set(true);
      worker.interrupt();
      worker.join(1000);
    }
  }
}
