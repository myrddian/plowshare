package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import java.util.List;
import java.util.Objects;

/**
 * Harness tools a run is handed that its definition did not declare, chosen by what the run is
 * rather than by what the agent is.
 *
 * <h2>A provider keyed on the run, not a parameter threaded to it</h2>
 *
 * <p>A conductor — the bot running an orchestration — is an ordinary definition in an ordinary
 * conversation, and what makes it a conductor is a row the orchestration engine owns. The tools
 * that come with that ({@code orchestration_ask} and its siblings, and the todo tools a conductor
 * keeps its stages on) could have reached a run as a new argument on {@code submit} and {@code
 * speak}, and from there through every door into {@link JobRuntime#run}. That would teach four
 * signatures, and every caller of them, about a concept only one component has, and teach {@code
 * JobRuntime} which conversations are conductors.
 *
 * <p>Instead the runtime asks, once at the top of each run, and the engine answers from what it
 * already knows: this {@link Context} is a conversation it conducts, so here are its tools. This is
 * {@link Noticing}'s and {@link Whereabouts}' shape, set late by {@link JobRuntime#useRunExtras}
 * for {@code useNoticing}'s reason.
 *
 * <h2>What extras are not</h2>
 *
 * <ul>
 *   <li><b>Never a grant.</b> They do not enter {@link JobRuntime#knownTools}, so no definition can
 *       declare one and the registry validates nothing against them; a run holds them only for as
 *       long as the provider keeps answering for it.
 *   <li><b>Never a displacement.</b> A declared tool of the same name keeps its place.
 *   <li><b>Never a failure.</b> A provider that throws is logged and the run goes on with {@link
 *       Extras#NONE}: a conversation that cannot be told it is a conductor still gets its answer.
 * </ul>
 *
 * <h2>Ending a turn</h2>
 *
 * <p>{@link Extras#end} is the {@link TurnEnd} this run's tools share, and the runtime reads it
 * after each whole tool batch. {@code TurnEnd} says why a tool trips it rather than throwing.
 */
@FunctionalInterface
public interface RunExtras {

    /** A runtime that hands nobody anything. */
    RunExtras NONE = context -> Extras.NONE;

    /**
     * What a provider is told about the run it is answering for.
     *
     * @param definition the agent being run
     * @param conversationId the run's conversation, or null for a run in none
     * @param sessionId the client session, or null for a run with no client
     * @param transcript the run's transcript, which a per-run tool may need as the todo tools do
     * @param home the tier the run resolves definitions and starts work in
     * @param callerHandle the account that owns this run, or null when no account can be resolved
     * @param commands this run's own way to place and run a command, or null for a runtime that
     *     reaches no filesystem; the harness's check (spec 2026-09-26) is the first caller
     * @param end the run's own TurnEnd, created by the runtime before any provider is asked, so a
     *     caller-side tool can end the caller's turn (spec 2026-09-27 §2)
     * @param hooks the run's in-turn log stages — {@code stage.*} and {@code approval.pre} (spec
     *     2026-09-28-hooks-reach-the-log, slices 2 and 3); null reads as {@link RunHooks#NONE}
     */
    record Context(AgentDefinition definition, String conversationId, String sessionId,
            Transcript transcript, Home home, String callerHandle, Commands.Port commands,
            TurnEnd end, RunHooks hooks) {

        public Context {
            hooks = hooks == null ? RunHooks.NONE : hooks;
        }

        /** The pre-hooks shape, kept for every caller built before a run's gates reached it. */
        public Context(AgentDefinition definition, String conversationId, String sessionId,
                Transcript transcript, Home home, String callerHandle, Commands.Port commands,
                TurnEnd end) {
            this(definition, conversationId, sessionId, transcript, home, callerHandle, commands,
                    end, RunHooks.NONE);
        }

        /** The pre-TurnEnd shape, kept for every caller built before a run's own end existed. */
        public Context(AgentDefinition definition, String conversationId, String sessionId,
                Transcript transcript, Home home, String callerHandle, Commands.Port commands) {
            this(definition, conversationId, sessionId, transcript, home, callerHandle, commands,
                    null);
        }

        /** The pre-commands-port shape, kept for every caller built before a run had one. */
        public Context(AgentDefinition definition, String conversationId, String sessionId,
                Transcript transcript, Home home, String callerHandle) {
            this(definition, conversationId, sessionId, transcript, home, callerHandle, null);
        }

        /** The pre-caller-tools shape, kept for fixtures and conductor-only providers. */
        public Context(AgentDefinition definition, String conversationId, String sessionId,
                Transcript transcript) {
            this(definition, conversationId, sessionId, transcript, null, null);
        }
    }

    /**
     * What a run is handed.
     *
     * @param tools offered after the declared tools, in this order, under their own schema names
     * @param end what those tools trip to end the turn, or null when none of them can
     * @param keepsTodos whether this run is offered the todo tools and shown its list, as a
     *     definition declaring a todo tool would be
     * @param requiresAToolCall whether this run's turns may not end without calling something.
     *
     *     <p><b>A per-run fact and not a per-agent one</b>, which is why it is here rather than on
     *     {@code AgentDefinition}: the run that needs it is a specific orchestration run that has
     *     already ended a turn in prose, not every run of that conductor. {@code
     *     OrchestrationsConfig.runExtras} reads {@code nudges} off the row to decide, so the
     *     constraint arrives on exactly the turns that follow a failure and on no others.
     *
     *     <p>{@code JobRuntime} turns it into {@code ChatRequest.withToolChoice(REQUIRED)}, and
     *     only while tools are actually offered; {@code ToolChoice} carries the measurement this
     *     exists for.
     * @param fence what this run may not do with the tools it holds, declared and extra alike;
     *     null is {@link Fence#NONE}. A per-run fact for {@code requiresAToolCall}'s reason: a
     *     conductor's {@code file_edit} is fenced to its own artifacts, the coder's is not, and
     *     both are the same tool.
     */
    record Extras(List<AgentTool> tools, TurnEnd end, boolean keepsTodos,
            boolean requiresAToolCall, Fence fence) {
        public static final Extras NONE = new Extras(List.of(), null, false, false);

        /**
         * The pre-fence shape, kept for the providers that fence nothing.
         *
         * @param tools as the canonical constructor's
         * @param end as the canonical constructor's
         * @param keepsTodos as the canonical constructor's
         * @param requiresAToolCall as the canonical constructor's
         */
        public Extras(List<AgentTool> tools, TurnEnd end, boolean keepsTodos,
                boolean requiresAToolCall) {
            this(tools, end, keepsTodos, requiresAToolCall, Fence.NONE);
        }

        /** The pre-tool-choice shape, kept for the providers that never require a call. */
        public Extras(List<AgentTool> tools, TurnEnd end, boolean keepsTodos) {
            this(tools, end, keepsTodos, false);
        }

        public Extras {
            tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
            fence = fence == null ? Fence.NONE : fence;
        }
    }

    /**
     * What a run may not do with a tool it holds, said in the words the model reads — spec
     * 2026-09-29 §3 rule 4. Asked before a call's hooks and before it runs, so a refused call
     * reaches no hook, runs nothing and is not on the run's trail.
     *
     * <p>Measured 2026-09-28: a conductor whose prose said {@code file_edit} was "for goal.md,
     * spec.md, plan.md and test-design.md only" wrote the product itself, eleven edits, with the
     * coder called once. Prose did not hold it, so the harness does; the tool's own fences (roots,
     * exclusions, links) still judge whatever a fence lets through.
     */
    @FunctionalInterface
    interface Fence {

        /** Nothing fenced. */
        Fence NONE = (tool, argumentsJson) -> null;

        /**
         * Whether a call may go ahead.
         *
         * @param tool the tool called, by the name the model sent
         * @param argumentsJson the call's arguments as the model sent them
         * @return the refusal the model is shown, or null when the call may go ahead
         */
        String refusal(String tool, String argumentsJson);
    }

    /** What this run is handed. May throw; the runtime treats a throw as {@link Extras#NONE}. */
    Extras forRun(Context context);
}
