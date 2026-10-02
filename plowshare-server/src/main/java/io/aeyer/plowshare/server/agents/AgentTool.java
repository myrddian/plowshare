package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;

/**
 * What a tool is to the runtime: a schema the model is shown, and a call that
 * returns the text handed back as a tool result.
 *
 * <h2>A tool's errors are the model's to read, not the runtime's to throw</h2>
 *
 * <p>{@link #run} <b>never throws for a caller's mistake.</b> A tool that threw
 * on a malformed argument would end the job over something the model could have
 * corrected on its next turn — and the turn that produced the bad argument was
 * already paid for. Excalibur's loop learned the same thing from the other
 * direction: an unknown tool name came back as a result naming the tools that
 * exist, not as a crash, and the model picked the right one immediately.
 *
 * <p>The rule is about <em>a caller's mistakes</em> and no further. An
 * unreachable database or a dead embedding endpoint is not a mistake the model
 * made and not one it can correct, and rendering it as a tool result would hand
 * back "nothing was found" for a question that was never asked — which is the
 * confident-empty-answer failure this project exists to avoid. Those propagate,
 * and the job runtime ends the run as unavailable. See {@code MemoryTools} for
 * both halves in one class.
 *
 * <h2>{@code home} is passed by the runtime, never chosen by the model</h2>
 *
 * <p>It is a parameter of {@link #run} rather than an argument in {@link
 * #schema}, and that is the whole of the enforcement: <b>a tool must not let an
 * agent name its own tier.</b> An agent runs against the home its job was
 * started for. The MCP surface's equivalent tools do take a {@code project}
 * argument, because their caller is a person's session saying what it is working
 * on; an agent has no such standing, and copying the argument across would let
 * one widen its own reach to global by typing a word.
 *
 * <h2>The names here are the registry's {@code knownTools}</h2>
 *
 * <p>{@link ToolSchema#name()} is what the model calls, what the job log
 * records, and what {@code AgentRegistry.load} is told about. That set must be
 * <em>exactly</em> the names the tool layer registers at this boot, derived from
 * the registered tools rather than written out as a constant beside them: too
 * broad is the silent direction, since every name in the set is one the
 * unknown-tool refusal waves through, and an agent then boots, is never offered
 * the tool, and simply cannot do what its prompt describes. See that method's
 * javadoc, which says it at length.
 *
 * <p>Implementations must be safe to call from several threads at once: one
 * instance is shared by every job, and jobs run concurrently on virtual threads.
 * {@link AgentRunTool} is the one implementation built per run instead, because
 * it carries a run's budget and cancellation flag — neither of which is a
 * parameter here — and it is immutable, so the rule holds for it either way.
 */
public interface AgentTool {

    /** The tool as the model is told about it. Constant for the life of the
     *  instance — it is serialised into every request that offers the tool. */
    ToolSchema schema();

    /**
     * Do the work and return what the model should read.
     *
     * @param argumentsJson the raw JSON the model emitted. Never null — {@code
     *     ToolCall.arguments} is non-null by construction, and an empty string
     *     is how a call with no arguments arrives — but arbitrary otherwise:
     *     unparseable, the wrong shape, or missing what the schema required are
     *     all things a model does, and all of them are results rather than
     *     exceptions.
     * @param home the tier this job runs against, chosen by whoever started the
     *     job. Not an argument the model can set; see above.
     * @return prose for the model. Never null and never blank — a tool result
     *     with nothing in it reads to a model as a tool that does not work.
     * @throws NullPointerException if either argument is null, which is the
     *     runtime's bug and not the model's
     * @see io.aeyer.plowshare.server.agents.AgentRunTool.SubAgentFailed the one
     *     implementation that propagates a second exception on purpose. A child
     *     agent that could not reach what it depends on is the same category as
     *     the {@code EmbeddingException} above — infrastructure, not a mistake
     *     the model can correct — so it ends the parent's run rather than
     *     becoming a result the parent answers around. Named here because this
     *     interface is the seam a reader opens to learn what {@code run} can do,
     *     and that exception lives in a class they may never open.
     */
    String run(String argumentsJson, Home home);

    /** The runtime's immutable owner, supplied independently of model arguments. */
    default String run(String argumentsJson, Home home,
            io.aeyer.plowshare.server.llm.accounting.UsageAttribution owner) {
        return run(argumentsJson, home);
    }
}
