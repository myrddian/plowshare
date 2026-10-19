package io.aeyer.plowshare.server.agents.digests;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.llm.accounting.*;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.List;
import java.util.function.Supplier;

/** Each operation owns its budget and trace. No navigator conversation is a delegation. */
public final class DigestModel implements UsageAware {
  private UsageOwners usageOwners = UsageOwners.NONE;

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = java.util.Objects.requireNonNull(source);
  }

  private io.aeyer.plowshare.server.embedding.OperationEmbeddings embeddingOperations;

  public void useEmbeddingOperations(
      io.aeyer.plowshare.server.embedding.OperationEmbeddings operations) {
    embeddingOperations = java.util.Objects.requireNonNull(operations);
  }

  private final LlmDispatcher dispatcher;
  private final Supplier<Compaction> logs;
  private final MemoryProperties properties;
  private final Supplier<String> specifier;

  /**
   * {@code specifier} is a {@link Supplier}, not a {@code String}, so that a specifier is read at
   * the call rather than frozen at construction — {@code LlmProperties} is a bean and the binding
   * is configuration, and a constructor-time copy would make a rebind silently ineffective.
   */
  private io.aeyer.plowshare.server.access.SystemOperations system;

  public DigestModel(
      LlmDispatcher dispatcher,
      Supplier<Compaction> logs,
      MemoryProperties properties,
      Supplier<String> specifier,
      io.aeyer.plowshare.server.access.SystemOperations system) {
    this(dispatcher, logs, properties, specifier);
    this.system = java.util.Objects.requireNonNull(system);
  }

  public DigestModel(
      LlmDispatcher dispatcher,
      Supplier<Compaction> logs,
      MemoryProperties properties,
      Supplier<String> specifier) {
    this.dispatcher = dispatcher;
    this.logs = logs;
    this.properties = properties;
    this.specifier = specifier;
  }

  public final class Operation implements AutoCloseable {
    private Runnable validity = () -> {};
    private Runnable inputs = () -> {};
    private boolean closed;
    private Home home;

    public Operation withValidity(Runnable validity) {
      this.validity = java.util.Objects.requireNonNull(validity);
      return this;
    }

    public void inputs(Runnable inputs) {
      this.inputs = java.util.Objects.requireNonNull(inputs);
      require();
    }

    public void require() {
      if (closed || Thread.currentThread().isInterrupted())
        throw new IllegalStateException("Memory operation ended");
      validity.run();
      inputs.run();
    }

    public io.aeyer.plowshare.server.archive.PassageIndex.Query query(
        io.aeyer.plowshare.server.archive.PassageIndex index, String text) {
      require();
      if (embeddingOperations == null) return index.capture(text, usage());
      try (var permit = embeddingOperations.query(home, text, budget, this::require)) {
        return index.capture(text, usage(), permit);
      }
    }

    private final Transcript trace;
    private final Budget budget;
    private final UsageAttribution owner;
    private io.aeyer.plowshare.server.access.SystemOperations.Work systemWork;
    private String result = "System memory operation did not complete";
    private Outcome.Ending ending = Outcome.Ending.STUCK;

    private Operation(Transcript trace, Budget budget, UsageAttribution owner) {
      this.trace = trace;
      this.budget = budget;
      this.owner = owner;
    }

    public UsageAttribution usage() {
      return owner;
    }

    public String call(
        String role, String instruction, String evidence, Home home, Budget allowance) {
      require();
      if (allowance != budget) throw new IllegalArgumentException("Operation owns its budget");
      return DigestModel.this.call(this, role, instruction, evidence, home, allowance);
    }

    public void result(String text, boolean complete) {
      result = text;
      if (ending != Outcome.Ending.UNAVAILABLE || complete)
        ending =
            complete
                ? Outcome.Ending.ANSWERED
                : budget.exhausted() ? Outcome.Ending.CALL_BUDGET : Outcome.Ending.STUCK;
    }

    public void cancelled() {
      ending = Outcome.Ending.CANCELLED;
    }

    @Override
    public void close() {
      closed = true;
      trace.closed(
          "System memory operation",
          new Outcome(ending, result, budget.spent(), budget.spent(), ""));
    }
  }

  public Operation operation(String role, Home home, Budget budget) {
    return operation(
        role,
        home,
        budget,
        usageOwners.in(
            home,
            null,
            role.equals("memory_navigator")
                ? UsageAttribution.Operation.NAVIGATION
                : UsageAttribution.Operation.DIGEST));
  }

  public Operation operation(String role, Home home, Budget budget, UsageAttribution owner) {
    AgentDefinition definition =
        new AgentDefinition(
            role,
            "System memory operation",
            specifier.get(),
            List.of(),
            List.of(),
            List.of(),
            1,
            budget.capped() ? Math.max(1, budget.limit()) : 1,
            "System memory operation",
            false,
            false);
    Compaction log = logs.get();
    Transcript trace = log.logFor(Origin.MEMORY, home, definition, null, budget);
    if (owner.conversations().id() == null && log.accountingEnabled()) {
      trace = log.own(home, trace, definition, owner.accountHandle());
      owner = trace.usage();
    }
    var owned =
        owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED
            ? owner
            : owner.forOperation(
                role.equals("memory_navigator")
                    ? UsageAttribution.Operation.NAVIGATION
                    : UsageAttribution.Operation.DIGEST,
                role);
    // System operations have their own trace; user-triggered navigation retains its originating
    // run.
    if (owned.conversations().id() == null && trace.spokenIn() != null) {
      owned =
          usageOwners.conversation(
              trace.conversationId(), trace.spokenIn().turnOrdinal(), owned.operation());
    }
    if (owned.status() != UsageAttribution.Status.LEGACY_UNATTRIBUTED
        && trace.usage().status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED) {
      trace.accounted(owned);
    }
    var operation = new Operation(trace, budget, owned);
    operation.home = home;
    if (system != null)
      operation.systemWork =
          system.admit(
              io.aeyer.plowshare.server.access.SystemOperations.Purpose.MEMORY_DIGEST,
              home,
              definition,
              String.valueOf(trace.conversationId()),
              operation::require);
    return operation;
  }

  private ChatRequest authorized(Operation operation, ChatRequest request, Budget.Claim claim) {
    return operation.systemWork == null
        ? request
        : request.withAuthorization(operation.systemWork.claimed(request, claim));
  }

  private String call(
      Operation operation,
      String role,
      String instruction,
      String evidence,
      Home home,
      Budget budget) {
    var claim =
        budget
            .tryClaim()
            .orElseThrow(() -> new IllegalStateException("System memory allowance exhausted"));
    AgentDefinition definition =
        new AgentDefinition(
            role,
            "System memory operation",
            specifier.get(),
            List.of(),
            List.of(),
            List.of(),
            1,
            budget.capped() ? Math.max(1, budget.limit()) : 1,
            instruction,
            false,
            false);
    if (operation == null)
      throw new IllegalStateException("A memory call needs its own operation trace");
    Transcript trace = operation.trace;
    try {
      trace.record(LoggedEntry.utterance(evidence, Speaker.harness()));
      Completion result =
          dispatcher.complete(
              authorized(
                  operation,
                  JobRuntime.requestFor(
                          definition,
                          List.of(ChatMessage.system(instruction), ChatMessage.user(evidence)))
                      .withAttribution(
                          operation.owner.forOperation(operation.owner.operation(), role)),
                  claim));
      operation.require();
      if ("length".equals(result.finishReason()))
        throw new IllegalStateException("Memory model output was truncated");
      String answer = result.content();
      if (answer == null || answer.isBlank())
        throw new IllegalStateException("Memory model answered with no text");
      trace.record(LoggedEntry.answer(answer, List.of()));
      operation.result = answer;
      return answer.strip();
    } catch (RuntimeException failed) {
      operation.ending = Outcome.Ending.UNAVAILABLE;
      operation.result = "Memory operation unavailable (" + failed.getClass().getSimpleName() + ")";
      throw failed;
    }
  }
}
