package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.todos.StageRules;
import java.time.Instant;
import java.util.List;

/**
 * One run of an orchestration, as {@code V48__orchestrations.sql} holds it.
 *
 * @param id {@link OrchestrationStore#PREFIX}-prefixed
 * @param definitionName the definition's own name, not this run's id
 * @param tier where the definition was read from, pinned at start
 * @param definitionHash {@code sha256:<hex>} of the definition's text at the moment this run began
 * @param definitionSource the definition file's text, pinned at start so the engine can re-parse
 *     it after a restart without re-resolving a tier that may have changed or, for a laptop
 *     session, gone — V49
 * @param definitionOrigin where the definition file was read from, pinned alongside its text —
 *     V49
 * @param stages the definition's stages, pinned at start so an edited definition cannot strand a
 *     run already under way
 * @param maxReturns how many times a later stage may send the conductor back
 * @param returnsUsed returns counted so far, never above {@code maxReturns}
 * @param project the home this run answers from, or {@code null} for the global tier
 * @param conductorConversation the conductor's own conversation, one per run
 * @param callerConversation the conversation that started this run, or {@code null}
 * @param callerAgent the agent that started this run
 * @param callerHandle the admin that started this run, or {@code null}
 * @param callerSession the session the caller was attached to when this run started, or {@code
 *     null} — V49
 * @param parent the orchestration that started this run, or {@code null} for a root run — V52
 * @param depth how many parents this run has; a root run is 0 — V52
 * @param waitingFor the child this run is waiting on, set only while {@code state} is {@code
 *     WAITING} — V52
 * @param state where this run has got to
 * @param pendingCap which cap a conductor is asking its caller to raise — {@code turn_cap} or
 *     {@code call_budget} — or {@code stuck} for a run asking the person whether it goes on after
 *     three turns without progress (V58), or {@code null}; set only while {@code state} is {@code
 *     ASKING} — Decision 7, V49
 * @param result the outcome, set only once {@code state} is {@code FINISHED}
 * @param failure why this run stopped, set only once {@code state} is a terminal state other than
 *     {@code FINISHED}
 * @param restarts how many times this run has been restarted
 * @param nudges how many prose endings in a row this run has been nudged for since it last made
 *     progress — the count the {@code stuck} limit reads, reset by progress
 * @param endedInProse whether this run's conductor has <em>ever</em> ended a turn in prose — set
 *     with the first nudge and never cleared, V58. What {@code
 *     OrchestrationsConfig.hasEndedATurnInProse} reads, so progress forgiving the count does not
 *     also lift the forced tool call
 * @param resultDeliveredAt when the ending was delivered to whoever is waiting on it, or {@code
 *     null} while still undelivered
 * @param createdAt when this run started
 * @param endedAt when this run reached a terminal state, or {@code null} while still live
 */
public record OrchestrationRecord(
        String id,
        String definitionName,
        OrchestrationDefinition.Tier tier,
        String definitionHash,
        String definitionSource,
        String definitionOrigin,
        List<StageRules.Stage> stages,
        int maxReturns,
        int returnsUsed,
        String project,
        String conductorConversation,
        String callerConversation,
        String callerAgent,
        String callerHandle,
        String callerSession,
        String parent,
        int depth,
        String waitingFor,
        OrchestrationState state,
        String pendingCap,
        String result,
        String failure,
        int restarts,
        int nudges,
        boolean endedInProse,
        Instant resultDeliveredAt,
        Instant createdAt,
        Instant endedAt) {

    public OrchestrationRecord {
        stages = List.copyOf(stages);
    }
}
