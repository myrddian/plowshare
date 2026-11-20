package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.access.InferenceCalls;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded, non-replaying review dispatch. A timeout stops the caller's wait and requests provider
 * cancellation; the physical slot remains occupied until the provider actually returns. There is no
 * second queue in front of the model pool: saturated review admission asks the human.
 */
public final class DispatchReviewInferences implements ReviewInferenceFactory, AutoCloseable {
  private final ReviewModelTransport transport;
  private final ReviewModelTransport webTransport;
  private final InferenceCalls calls;
  private final Semaphore physical;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final Duration cleanupBudget;

  DispatchReviewInferences(
      ReviewModelTransport transport, InferenceCalls calls, int maximum, Duration cleanupBudget) {
    this(transport, transport, calls, maximum, cleanupBudget);
  }

  public DispatchReviewInferences(
      ReviewModelTransport transport,
      ReviewModelTransport webTransport,
      InferenceCalls calls,
      int maximum,
      Duration cleanupBudget) {
    this.transport = Objects.requireNonNull(transport);
    this.webTransport = Objects.requireNonNull(webTransport);
    this.calls = Objects.requireNonNull(calls);
    if (maximum < 1 || maximum > 1024)
      throw new IllegalArgumentException("Review capacity must be 1–1024");
    this.physical = new Semaphore(maximum);
    this.cleanupBudget = Objects.requireNonNull(cleanupBudget);
    if (cleanupBudget.isNegative()
        || cleanupBudget.isZero()
        || cleanupBudget.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException(
          "Review cleanup budget must be positive and at most 30 seconds");
  }

  @Override
  public ReviewInference within(
      OperationAuthority.Admission operation,
      Budget budget,
      UsageAttribution usage,
      Runnable validity) {
    return bind(transport, operation, budget, usage, validity);
  }

  @Override
  public ReviewInference withinWeb(
      OperationAuthority.Admission operation,
      Budget budget,
      UsageAttribution usage,
      Runnable validity) {
    return bind(webTransport, operation, budget, usage, validity);
  }

  private ReviewInference bind(
      ReviewModelTransport transport,
      OperationAuthority.Admission operation,
      Budget budget,
      UsageAttribution usage,
      Runnable validity) {
    Objects.requireNonNull(operation, "Review execution authority");
    Objects.requireNonNull(budget);
    Objects.requireNonNull(usage);
    Objects.requireNonNull(validity);
    return (definition, system, evidence, work) ->
        consult(transport, operation, budget, usage, validity, definition, system, evidence, work);
  }

  private String consult(
      ReviewModelTransport transport,
      OperationAuthority.Admission operation,
      Budget budget,
      UsageAttribution usage,
      Runnable validity,
      AgentDefinition definition,
      String system,
      String evidence,
      ReviewWork work) {
    if (work.stopped()) throw new IllegalStateException("Review work expired or was cancelled");
    validity.run();
    if (!physical.tryAcquire())
      throw new IllegalStateException("Review capacity is occupied; ask the human");
    CompletableFuture<Completion> response = new CompletableFuture<>();
    AtomicBoolean abandoned = new AtomicBoolean();
    try {
      var claim =
          budget
              .tryClaim()
              .orElseThrow(() -> new IllegalStateException("Review allowance is exhausted"));
      var authorization =
          calls
              .claimed(operation, claim)
              .and(
                  () -> {
                    validity.run();
                    if (work.stopped() || abandoned.get())
                      throw new IllegalStateException("Review is no longer active");
                  });
      // Empty tools and no tool choice are structural. The bot's ordinary tools/calls/scopes are
      // never offered, and a returned tool call is not executed by this one-shot protocol.
      ChatRequest request =
          new ChatRequest(
              definition.model(),
              List.of(ChatMessage.system(system), ChatMessage.user(evidence)),
              definition.sampling(),
              work.remaining(),
              List.of(),
              null,
              usage.forOperation(UsageAttribution.Operation.REVIEW, definition.name()),
              authorization);
      workers.execute(
          () -> {
            try {
              if (work.stopped() || abandoned.get())
                throw new IllegalStateException("Review expired before provider dispatch");
              response.complete(transport.stream(request, () -> work.stopped() || abandoned.get()));
            } catch (Throwable failure) {
              response.completeExceptionally(failure);
            } finally {
              physical.release();
            }
          });
    } catch (RuntimeException beforeDispatch) {
      physical.release();
      throw beforeDispatch;
    }
    try {
      Completion result = await(response, work);
      if (work.stopped()) throw new IllegalStateException("Late review result was discarded");
      if (!result.toolCalls().isEmpty())
        throw new IllegalStateException("A reviewer cannot request tools");
      return result.content();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Review wait was interrupted", interrupted);
    } catch (ExecutionException failed) {
      throw new IllegalStateException(
          "Review provider did not return a decision", failed.getCause());
    } finally {
      abandoned.set(true);
    }
  }

  private static Completion await(CompletableFuture<Completion> response, ReviewWork work)
      throws InterruptedException, ExecutionException {
    while (!work.stopped()) {
      try {
        return response.get(
            Math.min(Duration.ofMillis(50).toNanos(), Math.max(1, work.remaining().toNanos())),
            TimeUnit.NANOSECONDS);
      } catch (TimeoutException waiting) {
        // This is another bounded wait on the same attempt, never a provider retry or a fresh
        // deadline. Poll cancellation even if a provider is silent and cannot return promptly.
      }
    }
    throw new IllegalStateException("Review deadline expired or work was cancelled; ask the human");
  }

  @Override
  public void close() {
    workers.shutdown();
    try {
      if (!workers.awaitTermination(cleanupBudget.toNanos(), TimeUnit.NANOSECONDS))
        workers.shutdownNow();
    } catch (InterruptedException interrupted) {
      workers.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
