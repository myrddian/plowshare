package io.aeyer.plowshare.server.harness;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.hooks.HarnessHook;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Builds {@code harness:stuck} for a run, asking the dispatcher as the advisor. */
public final class StuckTrapFactory implements HarnessHookFactory, AutoCloseable {

    public static final String ADVISOR = "stuck_advisor";

    /**
     * The four signals' thresholds (0 turns one off; see {@code StuckSignals}), then how the
     * consult runs. No step count: {@code after-steps} and {@code every-steps} were removed
     * with the step-count trigger, and a profile still naming one is refused at boot rather
     * than quietly ignored. {@code advice-most} is the longest note sent; a longer one is
     * swallowed, not cut.
     */
    public static final List<Parameter> PARAMETERS = List.of(
            Parameter.integer(StuckSignals.REPEAT_FAILURES, 3),
            Parameter.integer(StuckSignals.FAILURE_STREAK, 4),
            Parameter.integer(StuckSignals.REPEAT_CALLS, 3),
            Parameter.integer(StuckSignals.READ_ONLY_STEPS, 12),
            Parameter.integer("most-per-turn", 1),
            Parameter.text("advisor", "system.advisor"),
            Parameter.duration("timeout", Duration.ofSeconds(60)),
            Parameter.integer("arguments-excerpt", 300),
            Parameter.integer("result-excerpt", 300),
            Parameter.integer("thinking-excerpt", 800),
            Parameter.integer("advice-most", 600));

    private final LlmDispatcher dispatcher;
    private final Supplier<AgentDefinition> definition;
    private final ExecutorService consults = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param definition the {@code stuck_advisor} agent, resolved per consult — on
     *     {@code Compaction}'s reason for its folder: an operator editing the file
     *     should get the edit, not the boot's copy
     */
    public StuckTrapFactory(LlmDispatcher dispatcher, Supplier<AgentDefinition> definition) {
        this.dispatcher = dispatcher;
        this.definition = definition;
    }

    @Override
    public String name() {
        return StuckTrap.NAME;
    }

    @Override
    public List<Parameter> parameters() {
        return PARAMETERS;
    }

    @Override
    public HarnessHook create(Parameters parameters) {
        return new StuckTrap(parameters, new StuckTrap.Advisor() {
            @Override public StuckTrap.Advice advise(String specifier, String brief, BooleanSupplier abandoned) {
                return StuckTrapFactory.this.advise(specifier, brief, abandoned,
                        UsageAttribution.LEGACY);
            }
            @Override public StuckTrap.Advice advise(String specifier, String brief, BooleanSupplier abandoned,
                    UsageAttribution owner) {
                return StuckTrapFactory.this.advise(specifier, brief, abandoned, owner);
            }
        }, consults, ZonedDateTime::now,
                System::currentTimeMillis);
    }

    /**
     * One advisor call: the agent's prompt, the brief, no tools, no allowance spent.
     *
     * <p><b>Streamed, though nothing reads the deltas.</b> A non-streaming call cannot
     * be stopped, so a consult the run abandoned would go on holding an inference slot
     * — and on a pool with one chat slot, the run's own next call would queue behind
     * advice nobody will read. A stream is asked {@code abandoned} at every chunk and
     * gives the slot back; the {@code CallerAbandonedException} it throws lands on a
     * future that is already done, so the consult keeps the outcome that abandoned it.
     */
    private StuckTrap.Advice advise(String specifier, String brief, BooleanSupplier abandoned,
            UsageAttribution owner) {
        AgentDefinition advisor = definition.get();
        Completion said = dispatcher.stream(ChatRequest.of(specifier,
                List.of(ChatMessage.system(advisor.prompt()), ChatMessage.user(brief)))
                .withSampling(advisor.sampling()).withAttribution(owner.forOperation(
                        UsageAttribution.Operation.HOOK_MODEL,
                        advisor.name())), Deltas.DISCARDING, abandoned);
        return advice(said);
    }

    /** What a completion says as advice; a completion may carry no usage or no stamp. */
    static StuckTrap.Advice advice(Completion said) {
        return new StuckTrap.Advice(said.content() == null ? "" : said.content(),
                said.servedBy() == null ? null : said.servedBy().wireModel(),
                said.usage() == null ? null : said.usage().completionTokens());
    }

    @Override
    public void close() {
        consults.shutdownNow();
    }
}
