package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.access.InferenceCalls;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.hooks.Deadline;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.CallAuthorization;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DispatchReviewInferencesTest {
  private final OperationAuthority.Admission operation = mock(OperationAuthority.Admission.class);
  private final InferenceCalls calls = mock(InferenceCalls.class);
  private final AgentDefinition definition =
      new AgentDefinition(
          "security_reviewer",
          "review intent",
          "fast",
          List.of(),
          List.of(),
          List.of(),
          1,
          1,
          "Review the operation");

  @BeforeEach
  void authorize() {
    when(calls.claimed(eq(operation), any())).thenReturn(CallAuthorization.DIRECT);
  }

  @Test
  void consultUsesExistingAllowanceAuthorityAndNoTools() {
    Budget budget = Budget.of(3);
    AtomicInteger validity = new AtomicInteger();
    ReviewModelTransport transport =
        (request, cancelled) -> {
          assertTrue(request.tools().isEmpty());
          assertEquals(null, request.toolChoice());
          assertNotNull(request.submitTimeout());
          assertFalse(request.submitTimeout().isNegative());
          assertTrue(request.submitTimeout().compareTo(Duration.ofSeconds(2)) <= 0);
          request.authorization().require();
          assertFalse(cancelled.getAsBoolean());
          return completion(List.of());
        };
    try (var dispatch = new DispatchReviewInferences(transport, calls, 1, Duration.ofSeconds(1))) {
      assertEquals(
          "decision",
          dispatch
              .within(operation, budget, UsageAttribution.LEGACY, validity::incrementAndGet)
              .consult(definition, "Review", "evidence", work(Duration.ofSeconds(2))));
    }
    assertEquals(1, budget.spent());
    assertTrue(validity.get() >= 2);
    verify(calls).claimed(eq(operation), any());
  }

  @Test
  void quotedWebReviewUsesItsOwnTransportAndTheExistingAllowance() {
    Budget budget = Budget.of(2);
    ReviewModelTransport ordinary = mock(ReviewModelTransport.class);
    ReviewModelTransport web =
        (request, cancelled) -> {
          assertTrue(request.tools().isEmpty());
          request.authorization().require();
          return completion(List.of());
        };
    try (var dispatch =
        new DispatchReviewInferences(ordinary, web, calls, 1, Duration.ofSeconds(1))) {
      assertEquals(
          "decision",
          dispatch
              .withinWeb(operation, budget, UsageAttribution.LEGACY, () -> {})
              .consult(definition, "Review", "quoted evidence", work(Duration.ofSeconds(2))));
    }
    verifyNoInteractions(ordinary);
    verify(calls).claimed(eq(operation), any());
    assertEquals(1, budget.spent());
  }

  @Test
  void timeoutDoesNotReleasePhysicalCapacityOrReplayTheAttempt() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger attempts = new AtomicInteger();
    Budget budget = Budget.of(3);
    ReviewModelTransport transport =
        (request, cancelled) -> {
          attempts.incrementAndGet();
          entered.countDown();
          try {
            assertTrue(release.await(3, TimeUnit.SECONDS));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
          }
          assertTrue(cancelled.getAsBoolean());
          return completion(List.of());
        };
    try (var dispatch = new DispatchReviewInferences(transport, calls, 1, Duration.ofSeconds(1));
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var bound = dispatch.within(operation, budget, UsageAttribution.LEGACY, () -> {});
      try {
        var waiting =
            callers.submit(
                () ->
                    assertThrows(
                        IllegalStateException.class,
                        () ->
                            bound.consult(
                                definition, "Review", "evidence", work(Duration.ofMillis(100)))));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        waiting.get(2, TimeUnit.SECONDS);
        assertThrows(
            IllegalStateException.class,
            () -> bound.consult(definition, "Review", "evidence", work(Duration.ofSeconds(1))));
        assertEquals(1, attempts.get());
        assertEquals(1, budget.spent());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void cancellationStopsASilentWaitWithoutWaitingOutTheModelBudget() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean cancelled = new AtomicBoolean();
    ReviewModelTransport transport =
        (request, stopped) -> {
          entered.countDown();
          try {
            assertTrue(release.await(3, TimeUnit.SECONDS));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
          }
          assertTrue(stopped.getAsBoolean());
          return completion(List.of());
        };
    try (var dispatch = new DispatchReviewInferences(transport, calls, 1, Duration.ofSeconds(1));
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        var waiting =
            callers.submit(
                () ->
                    assertThrows(
                        IllegalStateException.class,
                        () ->
                            dispatch
                                .within(operation, Budget.of(2), UsageAttribution.LEGACY, () -> {})
                                .consult(
                                    definition,
                                    "Review",
                                    "evidence",
                                    new ReviewWork(
                                        Deadline.after(Duration.ofSeconds(20)), cancelled::get))));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        cancelled.set(true);
        waiting.get(1, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void exhaustedAllowanceAndRevokedValidityNeverReachProvider() {
    ReviewModelTransport transport = mock(ReviewModelTransport.class);
    try (var dispatch = new DispatchReviewInferences(transport, calls, 1, Duration.ofSeconds(1))) {
      assertThrows(
          CallerFault.class,
          () ->
              dispatch
                  .within(operation, Budget.of(0), UsageAttribution.LEGACY, () -> {})
                  .consult(definition, "Review", "evidence", work(Duration.ofSeconds(1))));
      assertThrows(
          IllegalStateException.class,
          () ->
              dispatch
                  .within(
                      operation,
                      Budget.of(2),
                      UsageAttribution.LEGACY,
                      () -> {
                        throw new IllegalStateException("Revoked");
                      })
                  .consult(definition, "Review", "evidence", work(Duration.ofSeconds(1))));
    }
    verifyNoInteractions(transport, calls);
  }

  @Test
  void returnedToolCallsCannotTurnReviewIntoAnAgentRun() {
    try (var dispatch =
        new DispatchReviewInferences(
            (request, cancelled) -> completion(List.of(new ToolCall("call", "run", "{}"))),
            calls,
            1,
            Duration.ofSeconds(1))) {
      assertThrows(
          IllegalStateException.class,
          () ->
              dispatch
                  .within(operation, Budget.of(2), UsageAttribution.LEGACY, () -> {})
                  .consult(definition, "Review", "evidence", work(Duration.ofSeconds(1))));
    }
  }

  private static ReviewWork work(Duration limit) {
    return new ReviewWork(Deadline.after(limit), () -> false);
  }

  private static Completion completion(List<ToolCall> tools) {
    return new Completion("decision", "stop", TokenUsage.UNKNOWN, tools);
  }
}
