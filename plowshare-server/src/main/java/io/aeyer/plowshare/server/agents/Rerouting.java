package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.agents.Invocation.Dispatch;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which model one run's next call goes to, and what came of a refusal.
 *
 * <h2>Refusal is an outcome and fallback is a dispatch decision</h2>
 *
 * <p>{@link JobRuntime}'s loop asks this two things and nothing else: <em>what
 * request does this history become</em>, and <em>this answer was judged a
 * refusal — what now</em>. Everything else about the loop is unchanged, which is
 * the point: a fallback is the same run continuing with a different specifier,
 * over the same history, under the same cap, budget, cancellation and tools. It
 * is not a second run, not a delegated agent and not a tool call, so nothing is
 * fabricated in the conversation to explain it.
 *
 * <h2>The fallback is shown exactly what the refused call was shown</h2>
 *
 * <p>The refusal is never appended to the loop's history — an answer with no
 * tool calls ends the loop and is never sent back — so the history a fallback
 * is dispatched with is, byte for byte, the one the refused call went out with:
 * the agent's prompt, the conversation's projection, the utterance, and whatever
 * this run's own tool calls have already brought back. No sentence says a model
 * refused, and none asks for what another would not give. The fallback sees an
 * ordinary task. It is also why the prefix up to the utterance does not move.
 *
 * <h2>What the log comes to</h2>
 *
 * <ul>
 *   <li><b>No refusal</b>: nothing here does anything, and the log is what it
 *       was, plus provenance.
 *   <li><b>A refusal the agent may not reroute</b> — no {@code fallback:}, or
 *       the attempt already spent — is the answer. It is recorded as an {@code
 *       answer} whose provenance says {@code refused}.
 *   <li><b>A refusal that is rerouted</b> is recorded as a {@link
 *       EntryKind#REFUSAL}, which never projects, and the fallback's answer is
 *       recorded as the ordinary {@code answer} it is, attributed to the
 *       fallback's own pool and model.
 *   <li><b>A fallback that refuses in turn</b> is not rerouted again. Its
 *       refusal is the output: recorded as the turn's {@code answer}, attributed
 *       to the fallback, with {@code refused} on it, exactly as an agent with no
 *       fallback has its refusal recorded.
 *   <li><b>A fallback that does not answer at all</b> — cancelled, out of steps
 *       or budget, or unreachable — leaves the run behaving as it would with no
 *       fallback: the refusal the agent's own model gave is recorded as the
 *       turn's {@code answer}, carrying the refused call's own provenance, and
 *       the run ends {@link Ending#ANSWERED} with it. A diagnostic says why.
 *       A low-refusal model nobody can reach does not turn an answer into a
 *       stopped run.
 * </ul>
 *
 * <h2>Telemetry</h2>
 *
 * <p>One structured line per model call and per decision, on the {@code
 * plowshare.fallback} logger, as {@code key=value} pairs a log pipeline can
 * count without parsing prose: {@code event=model_call} for every call (so the
 * count of primary calls, their latency and tokens are a filter), {@code
 * event=refusal} for every probable refusal with whether it was eligible and why
 * not, {@code event=fallback_dispatched}, and {@code event=fallback_settled}
 * with whether it succeeded. Every line carries {@code after_fallback}, which
 * says whether the answer this turn follows was a fallback's — the measurement
 * for "does the primary refuse again once it has read what the fallback wrote".
 * The refusal's own text goes on the {@code refusal} line, for debugging; it is
 * also in the log, which is where an audit reads it.
 *
 * <h2>Affinity is not built, and this is where it would go</h2>
 *
 * <p>A continuation of a fallback-handled task — "find three more" — is likely
 * to be refused again. Routing it straight to the fallback would be a decision
 * about the <em>opening</em> dispatch of a run, and {@link #opensOn} is that
 * decision, made in one place from {@link Transcript#followsAFallback}. It
 * answers {@link Dispatch#PRIMARY} unconditionally today: sending a question to
 * a model other than the agent's own, before that model has declined anything,
 * is a policy nobody has written, and the signal it would need is recorded
 * already.
 */
final class Rerouting {

    private static final Logger telemetry = LoggerFactory.getLogger("plowshare.fallback");

    /** What the loop does with an answer that was judged a refusal. */
    enum Decision {
        /** The refusal is the answer: record it as one, as the loop always has. */
        STANDS_AS_THE_ANSWER,
        /** Recorded as a refusal; call again, on the fallback. */
        REROUTED
    }

    private final AgentDefinition definition;
    private final Transcript transcript;
    private final boolean afterFallback;

    private Dispatch dispatch;
    private int taken;
    private String reason;
    private Invocation refusedCall;
    private String refusedContent;
    private Duration refusedTook;
    private Instant reroutedAt;
    private boolean fallbackRefused;
    private boolean settled;

    Rerouting(AgentDefinition definition, Transcript transcript) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.afterFallback = transcript.followsAFallback();
        this.dispatch = opensOn(definition, afterFallback);
    }

    /**
     * Where a run's first call goes. See the class javadoc: the agent's own
     * model, always, until somebody writes an affinity rule.
     */
    static Dispatch opensOn(AgentDefinition definition, boolean followsAFallback) {
        return Dispatch.PRIMARY;
    }

    Dispatch dispatch() {
        return dispatch;
    }

    /** The specifier this run's calls currently go to. */
    String specifier() {
        return dispatch == Dispatch.PRIMARY ? definition.model() : definition.fallback().model();
    }

    /**
     * The request {@code history} becomes on the current target.
     *
     * <p>The primary is {@link JobRuntime#requestFor}, unchanged. The fallback is
     * the same messages under the fallback's specifier and the sampling {@code
     * AgentsConfig} resolved for the fallback's own model — never the agent's,
     * which was resolved for a different one.
     */
    ChatRequest requestFor(List<ChatMessage> history) {
        if (dispatch == Dispatch.PRIMARY) {
            return JobRuntime.requestFor(definition, history);
        }
        AgentDefinition.Fallback fallback = definition.fallback();
        return ChatRequest.of(fallback.model(), history).withSampling(fallback.sampling());
    }

    /** The provenance of a call this run just made, on the current target. */
    Invocation invocation(Completion completion, CompletionOutcome outcome, Instant sentAt,
            Long firstTokenMillis) {
        return Invocation.of(definition.name(), dispatch, specifier(), completion, outcome,
                sentAt, dispatch == Dispatch.FALLBACK ? reason : null, firstTokenMillis);
    }

    /** Counted for every call, whatever came of it. */
    void called(Invocation call, Duration took) {
        telemetry.info("event=model_call agent={} dispatch={} specifier={} pool={} wire_model={}"
                        + " completion={} finish_reason={} prompt_tokens={} completion_tokens={}"
                        + " took_ms={} invocation={} after_fallback={}",
                call.agent(), call.dispatch().wireName(), call.specifier(), call.pool(),
                call.wireModel(), call.outcome().wireName(), call.finishReason(),
                call.usage().promptTokens(), call.usage().completionTokens(), took.toMillis(),
                call.id(), afterFallback);
    }

    /**
     * An answer was judged a refusal. Decide, and record what the decision
     * requires recording.
     *
     * <p><b>Detection has already happened and authorisation happens here</b>,
     * from the definition alone. The detector cannot reach this decision and
     * the definition cannot reach the detector's.
     */
    Decision refused(Invocation call, String why, String content, Duration took) {
        if (dispatch == Dispatch.FALLBACK) {
            // The fallback's refusal is the output. It is what the model this
            // run was rerouted to said, so it is recorded as the answer -- by the
            // loop, attributed to the fallback, with `refused` on it -- and it is
            // not rerouted again: nothing here is recursive.
            fallbackRefused = true;
            telemetry.info("event=refusal agent={} dispatch=fallback specifier={} pool={}"
                            + " wire_model={} eligible=false why_not=\"the fallback is not"
                            + " rerouted again\" reason=\"{}\" invocation={} after_fallback={}"
                            + " content=\"{}\"",
                    call.agent(), call.specifier(), call.pool(), call.wireModel(), why,
                    call.id(), afterFallback, oneLine(content));
            return Decision.STANDS_AS_THE_ANSWER;
        }
        AgentDefinition.Fallback fallback = definition.fallback();
        if (!fallback.permits(AgentDefinition.Fallback.Trigger.REFUSAL, taken)) {
            telemetry.info("event=refusal agent={} dispatch=primary specifier={} pool={}"
                            + " wire_model={} eligible=false why_not=\"{}\" reason=\"{}\""
                            + " invocation={} after_fallback={} content=\"{}\"",
                    call.agent(), call.specifier(), call.pool(), call.wireModel(),
                    fallback.on().isEmpty()
                            ? "the agent declares no fallback"
                            : "its fallback attempts are spent",
                    why, call.id(), afterFallback, oneLine(content));
            return Decision.STANDS_AS_THE_ANSWER;
        }
        // Recorded BEFORE the fallback is dispatched, as it happened: a server
        // killed during the fallback's call still leaves the refusal in the log.
        transcript.record(LoggedEntry.refusal(content, withReason(call, why)).took(took));
        taken++;
        reason = why;
        refusedCall = withReason(call, why);
        refusedContent = content;
        refusedTook = took;
        reroutedAt = call.sentAt();
        dispatch = Dispatch.FALLBACK;
        telemetry.info("event=refusal agent={} dispatch=primary specifier={} pool={} wire_model={}"
                        + " eligible=true reason=\"{}\" invocation={} after_fallback={}"
                        + " content=\"{}\"",
                call.agent(), call.specifier(), call.pool(), call.wireModel(), why, call.id(),
                afterFallback, oneLine(content));
        telemetry.info("event=fallback_dispatched agent={} from_specifier={} from_wire_model={}"
                        + " to_specifier={} refused_invocation={} after_fallback={}",
                call.agent(), call.specifier(), call.wireModel(), fallback.model(), call.id(),
                afterFallback);
        return Decision.REROUTED;
    }

    /**
     * What a run that is about to return comes to.
     *
     * <p>Every way out of the loop passes through here, which is what lets the
     * loop keep its own returns: a stopping ending reached while the fallback was
     * running is the fallback failing, and nothing inside the loop has to know.
     * Idempotent, so a caller that settles an outcome this already produced gets
     * it back unchanged.
     */
    Outcome settle(Outcome outcome, Instant now) {
        if (dispatch == Dispatch.PRIMARY || settled) {
            return outcome;
        }
        settled = true;
        long spentMs = Duration.between(reroutedAt, now).toMillis();
        if (outcome.answered()) {
            // Answered covers the fallback refusing in turn: its refusal is the
            // output and the run answered with it. What telemetry counts as a
            // success is an answer that was not a refusal.
            telemetry.info("event=fallback_settled agent={} succeeded={} ending={}"
                            + " fallback_completion={} to_specifier={} refused_invocation={}"
                            + " steps={} model_calls={} since_refused_ms={} after_fallback={}",
                    definition.name(), !fallbackRefused, outcome.ending(),
                    fallbackRefused ? "refused" : "answered", definition.fallback().model(),
                    refusedCall.id(), outcome.steps(), outcome.modelCalls(), spentMs,
                    afterFallback);
            return outcome;
        }
        return stands(outcome.ending().name(), outcome.steps(), outcome.modelCalls(), spentMs,
                outcome.detail());
    }

    private Outcome stands(String how, int steps, int modelCalls, long spentMs, String detail) {
        String said = "the agent's fallback '" + definition.fallback().model()
                + "' did not answer (" + how + ")" + (detail.isBlank() ? "" : ": " + detail)
                + "; the agent's own answer stands";
        // The harness saying what it did, where a person reading the
        // conversation back will find it: between the fallback's working and
        // the answer that stands. A diagnostic never projects.
        transcript.record(LoggedEntry.diagnostic("Refusal fallback: " + said + "."));
        // The refused call's own provenance: this answer is the words the
        // agent's model said, and saying so is the whole of not misattributing.
        transcript.record(LoggedEntry.answer(refusedContent, List.of())
                .took(refusedTook)
                .by(refusedCall));
        telemetry.info("event=fallback_settled agent={} succeeded=false ending={}"
                        + " fallback_completion=none to_specifier={} refused_invocation={}"
                        + " steps={} model_calls={} since_refused_ms={} after_fallback={}",
                definition.name(), how, definition.fallback().model(), refusedCall.id(), steps,
                modelCalls, spentMs, afterFallback);
        return new Outcome(Ending.ANSWERED, refusedContent, steps, modelCalls, said);
    }

    private static Invocation withReason(Invocation call, String why) {
        return new Invocation(call.id(), call.agent(), call.dispatch(), call.specifier(),
                call.pool(), call.wireModel(), call.outcome(), call.finishReason(),
                call.usage(), call.sentAt(), why, call.firstTokenMillis());
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace("\"", "\\\"");
    }
}
