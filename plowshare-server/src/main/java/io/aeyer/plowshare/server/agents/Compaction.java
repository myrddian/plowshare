package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.archive.CompactionRecord;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.Redemption;
import io.aeyer.plowshare.server.archive.StoredResults;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.hooks.Summarised;
import io.aeyer.plowshare.server.llm.FoldThresholds;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.tokens.TokenCount;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a conversation puts in front of its next turn, and the measurement that
 * decides whether it still fits.
 *
 * <h2>Measure, do not predict, and do not wait for a failure</h2>
 *
 * <p>Every chat completion reports {@code usage.prompt_tokens}, counted by the
 * model's own tokenizer, so the cost of a conversation's history is known
 * exactly as a side effect of using it. There is no tokenizer to call ahead of
 * time — {@code LmStudio} records that {@code /api/v0/tokenize} and {@code
 * /v1/tokenize} both answer <em>"Unexpected endpoint or method"</em>, and that
 * one of them answers it with an HTTP 200 — and none is needed between turns,
 * because there the previous turn's measurement is already in hand.
 *
 * <p>So: at the end of each turn, this class folds if the context that turn
 * <em>sent</em> — its first prompt, measured — plus what that turn added would
 * exceed the size at which a fold is <b>due</b>. And inside a turn, at each
 * tool-call boundary, it folds <b>now</b> if the turn's own last prompt, what the
 * model generated for it and the results just appended pass a second, higher
 * threshold — see "Inside a turn" below and {@link FoldThresholds}.
 *
 * <h2>The fold happens at the end of a turn, on another thread, and nobody
 * waits for it</h2>
 *
 * <p><b>It used to happen inside {@link TurnTranscript#before()}, which is the
 * job's own thread at the start of a run, and that made one person's question
 * cost two model calls end to end.</b> A summarising call is the most expensive
 * thing this server does — tens of seconds on the reference node — and putting
 * it in front of the first real call meant a turn that tripped the threshold
 * answered at roughly twice the latency of one that did not, for a reason the
 * person cannot see and did nothing to cause.
 *
 * <p>So {@link TurnTranscript#before()} is a pure read now: it settles the turn
 * ordinal and projects the log, and makes no model call on any path. The
 * decision is taken at {@link TurnTranscript#foldWhenTheTurnIsOver()}, which
 * {@code Turn} calls from the ending callback after the turn's outcome has been
 * written, and which dispatches the fold onto a thread of its own and returns
 * at once. <b>The next turn picks the summary up</b>, out of the log, exactly as
 * it always did.
 *
 * <p><b>A fold may therefore land after a later turn's entries, and the log is
 * built for that.</b> The log's reads order by {@code turn_ordinal, ordinal}
 * and a summary carries the last turn it stands for as its own turn ordinal, so
 * it sorts among the turns it covers whichever moment
 * it was physically written; and {@code EntryStore.supersede} bounds its update
 * at {@code turn_ordinal <= through}, so a fold that lands late cannot swallow
 * the entries of a turn it does not stand for.
 *
 * <p><b>One fold per conversation at a time.</b> {@code Turn} already refuses a
 * second concurrent turn, and that guard does not reach this one: a fold
 * deliberately outlives the turn that started it, so a slow fold and a fast next
 * turn is the ordinary case rather than the exceptional one. {@link #folding} is
 * the separate guard, and a fold that finds one in flight is dropped rather than
 * queued — the reach only moves forward, so the next fold's span is simply
 * wider.
 *
 * <p><b>A fold spends no budget</b>, which is the other half of the same
 * argument. A budget is what a person's turns spend from; a fold is the
 * machinery keeping their conversation sendable, and infrastructure that draws
 * on an agent's allowance is infrastructure a person can be billed for and can
 * run out of. It is bounded instead by the two facts above — at most one per
 * turn end, and at most one in flight — and on a conversation whose turns are
 * quicker than a summary is, the second of those is the binding one.
 *
 * <h2>Inside a turn, when the turn itself is what outgrew the window</h2>
 *
 * <p><b>Measured 2026-09-30, and it is why a second fold exists.</b> Over two
 * implement_specification runs on {@code openai/gpt-oss-120b} (131 072 tokens) no
 * conversation folded in two days, because the long conversations are single
 * turns: a coder delegation is one turn of up to a hundred model calls, and a
 * fold that only runs between turns cannot run until it ends. One such turn
 * reached <b>80 345</b> prompt tokens — 61% of the window, past the old third —
 * with no fold possible. {@code
 * implementation rationale} is the decision.
 *
 * <p>So there are two thresholds ({@link FoldThresholds}, which follows the
 * window: at 64K or less no due fold and an in-turn fold at 90%; above it a fold
 * is due at 60%, rising with the window to 75% at 200K, and runs inside the turn
 * at a point falling from 90% to 80% at 128K — 67% and 80% on a 131 072 window).
 * At <b>due</b> the log notes that a fold became due and nothing else
 * happens until the turn ends, where the decision above is taken as it always
 * was — a turn that returned small folds nothing. At <b>now</b> the fold runs
 * {@link TurnTranscript#stepEnded inside the turn}, on the job's own thread,
 * after the step's results are appended and before the next model call: the
 * older steps of the turn, and any earlier turns no fold stands for yet, become
 * one summary placed where the steps were, and the turn's opening request and its
 * most recent whole steps — within about 30% of the window, and at least three —
 * stay word for word. It is the one fold a turn waits for, because the turn
 * cannot go on without it; it is still on nobody's allowance and against neither
 * the turn's step cap nor the run's budget, and a failed one leaves the turn
 * running on its whole history, is written down, and is tried at most once per
 * crossing.
 *
 * <p><b>The one place this class estimates, and why it may.</b> Between turns the
 * decision is built only from measurements. Inside a turn the step's results were
 * appended after the last measurement, so they are counted through the server's
 * {@link Tokenizer} — never a character ratio of this class's own — with a third
 * again added where the count is an estimate. Everything else in the inside-a-turn
 * number is the model's own count, and the estimate is only ever the smaller
 * part: the results of one step.
 *
 * <h2>It folds at a threshold, and the context length is the ceiling</h2>
 *
 * <p>That size used to be {@link LlmDispatcher#contextLength(String, int)} itself —
 * the conversation coasted to the wall and folded there. <b>Measured, that is
 * the most expensive place to fold and every turn on the way is slow.</b> A turn
 * costs about 0.845 s more for every further thousand tokens of standing
 * history, so the mean cost of a turn rises monotonically with where the fold
 * happens; see {@link #SPAN_FRACTION} for the measurements and
 * {@code implementation rationale} §5 for the
 * arithmetic.
 *
 * <p>So the bound is a threshold: what an operator configured for this model, or
 * a fraction of the context length where nobody did — {@link FoldThresholds} —
 * and <b>never more than the context length whichever it is</b>. The threshold only ever folds a
 * conversation sooner; the ceiling is what stops one exceeding what the model
 * can accept, and it is still the reason a model with no known context length is
 * not compacted at all.
 *
 * <h2>Two prompt measurements, and each answers a different question</h2>
 *
 * <p>A turn makes several model calls and each prompt is longer than the last,
 * because the turn's own tool results are appended as it goes. Both ends of that
 * sequence are kept, and neither stands in for the other.
 *
 * <p><b>The longest is what has to fit.</b> The context bound applies to every
 * one of a turn's calls, so a turn fits only if its largest prompt does. That is
 * the number {@code turns.prompt_tokens} records, and <b>no part of the fold
 * decision is built from it any more</b> — see the section below.
 *
 * <p><b>The first is what was sent.</b> A turn sends the whole context — the
 * system prompt, whatever the harness adds to it, and the history — plus the
 * person's new question, and that <em>is</em> its first prompt. Everything after
 * it in the sequence is the turn's own working.
 *
 * <p><b>The fold trigger reads the first, and reading the longest was a bug
 * worth naming.</b> {@link #whatWasSaidAndWhatCameBack} carries a tool result
 * into a later turn as a bounded reference, or — for an agent that could not
 * redeem one — not at all; never at full size either way, so most of what a
 * turn's working added to its own prompts is never in a later turn's. A
 * trigger comparing the longest prompt against the threshold was therefore
 * comparing a number <b>far larger than any fold can bring it down to</b>: a
 * conversation
 * whose every turn reads a large file tripped it, folded, recovered none of what
 * it had been measured on, and tripped it again on the next turn, and the next,
 * for as long as the person kept asking. {@code
 * CompactionTest.a_conversation_whose_turns_each_read_a_file_does_not_fold_on_
 * every_turn} is what fails if it comes back.
 *
 * <h2>Headroom, and what it turned out to be</h2>
 *
 * <p><b>Headroom is the room the next turn needs, and the honest measure of it
 * is what the turn that just ended added</b> — {@link TurnTranscript#added()},
 * the assistant messages it generated that a later turn will carry, counted by
 * the model's own tokenizer. Nothing else in this class is headroom now; there
 * is no separate quantity and no separate method.
 *
 * <p>The decomposition is what forces it. What the trigger is a bound on is what
 * the next turn will <em>send</em>, and the next turn's first prompt is:
 *
 * <pre>
 * first(N+1) = first(N)             // this turn sent it, and it is `sent`
 *            + generations(N)       // the answers a later turn carries: `added()`, measured
 *            + references(N)        // one bounded line per call: unmeasured
 *            + utterance(N+1)       // unmeasured: not said yet
 * </pre>
 *
 * <p><b>The last two lines are the agent's, and the decomposition has two
 * readings because the projection does.</b> {@link #whatWasSaidAndWhatCameBack}
 * used to drop every tool result <em>and</em> every answer of a turn but the
 * last, together — the dropped answers being exactly the ones carrying tool
 * calls, so dropping both kept the message sequence balanced. It still does that
 * for an agent that cannot redeem a handle, and for one of those {@code
 * generations(N)} is the last generation alone and {@code references(N)} is
 * nothing.
 *
 * <p>For an agent that declares {@code result_read} it carries a result forward
 * as a reference, and a reference is a {@code tool} message that is only well
 * formed with its {@code tool_calls} above it. So the answers come back, and with
 * them the tokens the model was charged for generating them and their arguments:
 * {@code generations(N)} is every one of them. {@link
 * AgentDefinition#canRedeem()} is the one condition, asked by the projection and
 * again by {@link TurnTranscript#added()} so that the two cannot disagree.
 *
 * <p><b>The third line is the one nothing here can measure.</b> A reference is
 * counted in characters and there is no tokenizer on this box; it is bounded by
 * construction — the same short line whatever a tool was called with — and it is
 * left out rather than estimated, for the reason the alternatives section below
 * gives about constants. So {@code sent + added} is the next turn's prompt less
 * one utterance and one line per earlier call, and that is what {@code
 * foldIfItWouldNotFit} compares against the threshold.
 *
 * <p><b>It used to be the largest growth in {@code turns.prompt_tokens} from one
 * measured turn to the next, and that was the same bug twice.</b> That column is
 * the turn's <em>longest</em> prompt, so a growth in it counts the turn's own
 * tool traffic — traffic no fold can shorten, which is the failure the section
 * above names. And it was a running maximum with no decay, seeded by the first
 * measured turn's whole cost: <b>one turn whose working alone exceeded the fold
 * threshold seeded a headroom the conversation could never get back under</b>,
 * so {@code sent + headroom > foldAt} held on every later turn however small,
 * and each of them bought a summary and folded one turn further. On a 128 000
 * window with no configured threshold that is a single turn reading about 42 700
 * tokens of file. {@code CompactionTest.a_conversation_whose_first_turn_read_a_
 * huge_file_does_not_fold_for_ever_after} is what fails if it comes back.
 *
 * <p><b>Measured fresh every turn, so it decays.</b> That is the property the
 * maximum could not have. A conversation that read something enormous and then
 * calmed down is now described by the turns it is having rather than by the
 * worst turn it ever had, and the number a fold is decided from goes down when
 * the conversation does.
 *
 * <p><b>What it gives up is one utterance and one line per earlier call, and
 * that is a real trade.</b> The old quantity over-stated, which is the safe
 * direction; this one under-states by {@code utterance(N+1)} — a person's next
 * question, which has not been said and which no tokenizer here could measure if
 * it had been — and by the references, which are bounded per call and are
 * counted in characters rather than tokens. Two things pay
 * for it. The due threshold leaves room above it before the fold inside a turn —
 * at least 5% of the window, 13% on a 131 072 one — and a further fifth or more
 * before the wall, so a turn that crosses it by one question is still nowhere near
 * what the model refuses; and the
 * over-statement it replaces was not conservatism but a term that never came
 * down, which cost a summary on every turn for ever rather than one turn's
 * lateness once.
 *
 * <p><b>The alternative was costed and rejected, and saying why is the point.</b>
 * The obvious constant is the largest tool result this server will emit —
 * {@code FileTools.MAX_DISPLAY_CHARS}, 100 000 characters, which is a real
 * enforced bound and is measured in <em>characters</em>. Turning it into tokens
 * needs a characters-per-token ratio, and there is no tokenizer on this box to
 * measure one with; a worst-case bound is no use either, since a token can be a
 * single UTF-8 byte and 100 000 characters is then more than the whole 128 000
 * context. So any constant here would be a number arrived at by feel, wearing
 * the authority of a limit. This project deletes those.
 *
 * <p><b>What this does not claim.</b> It is a bound taken from experience and
 * not a proof: a conversation of small turns followed by one that reads a large
 * file can still overflow. Nothing short of a tokenizer could prevent that, and
 * the failure is loud — the endpoint refuses the request, the turn ends {@code
 * UNAVAILABLE} naming the model, and the conversation survives to be spoken into
 * again. A silently wrong constant would have been the quiet version of the same
 * outcome.
 *
 * <h2>The history is projected out of the log, and the decision is not</h2>
 *
 * <p><b>What the next turn reads comes from {@code entries} and nothing else.</b>
 * {@link Projection} turns one conversation's log into messages, and {@link
 * #whatWasSaidAndWhatCameBack} is the one thing this class contributes to it:
 * an earlier turn's result is carried as a <em>reference</em> rather than as
 * itself, <b>for an agent that declares {@code result_read}</b>. That is a
 * change of policy and not a refinement of one. The rule used to be that the
 * working stayed out, which was true from before the log existed; what replaces
 * it is that the working stays <em>reachable</em>, at a bounded cost, through a
 * handle the model can redeem.
 *
 * <p><b>And it is a change only where the handle is worth something.</b> An
 * agent that cannot redeem gets the old rule unchanged, because a reference it
 * could never turn back into anything is the bounded cost with none of the
 * reach — strictly worse than the drop, and silent. See that method, and {@link
 * AgentDefinition#canRedeem()}.
 *
 * <p><b>The fold decision is still taken from {@code turns} and must be.</b>
 * {@code prompt_tokens} is a fact about a request and its outcome; it is not a
 * message and it is nowhere in {@code entries}. Counting entries instead would
 * be the estimate the section above rejects, and {@code
 * turns_prompt_tokens_are_a_measurement} refuses a zero one layer down precisely
 * so that the absence of a measurement cannot be read as a small history.
 *
 * <h2>A compaction is an event, and the seam is readable from both sides</h2>
 *
 * <p>Nothing is deleted. A compaction writes one {@code compactions} row saying
 * which turns it stands for, and those turns stay in {@code turns} at their own
 * ordinals with their own text — so a person reads behind the seam with {@code
 * TurnStore.forConversation}, and {@code CompactionStore.forConversation} shows
 * every seam this conversation has had rather than only the last.
 *
 * <p>The <em>model</em> is told too, by {@link #SEAM}: the summary arrives
 * introduced as a summary, naming the span of turns it stands for. A summary
 * spliced in as though it were what was said is the failure the design names —
 * "a bad summary is information loss wearing a receipt, which is worse than an
 * obvious truncation because it looks like continuity" — and a reader who cannot
 * see the join has no way to ask for what is behind it.
 *
 * <h2>The most recent turn is never summarised</h2>
 *
 * <p>A compaction reaches through the second-to-last turn, so the exchange a
 * person is still in the middle of stays verbatim. One is not an arbitrary
 * number here: it is the smallest that keeps that property, and any larger one
 * would be a guess about how far back "still in the middle of" reaches. A
 * conversation with one turn therefore cannot be compacted at all — there is
 * nothing older — and that is said rather than worked around.
 */
public final class Compaction {

    private static final Logger log = LoggerFactory.getLogger(Compaction.class);

    /**
     * The agent a fold runs as, and it is never the agent being folded.
     *
     * <p><b>The name is here and the lookup is not</b>, which is the whole of
     * what this constant is for: {@code AgentsConfig} resolves it against the
     * registry and this class is handed the answer — see {@link #folder} for why
     * a supplier of one definition rather than a registry — so the spelling is
     * written once and a test can name the same agent the boot does.
     *
     * <p>See {@code agents/conversation_folder.md} for what that definition
     * declares and why, and {@code
     * implementation rationale} §6.1 for the
     * measurement that made a dedicated agent necessary rather than tidy.
     */
    public static final String FOLDER = "conversation_folder";

    /**
     * How much of the fold threshold one turn has to add before it is worth
     * folding on its own account: one part in three. What is left of the
     * one-third rule.
     *
     * <h2>The rule it was, and why the fold threshold stopped being it</h2>
     *
     * <p>This constant used to be where a conversation folded: a third of the
     * window where nobody configured a threshold. <b>That rule is replaced</b>
     * (spec 2026-09-30-fold-at-60-and-80, §1a as revised): at 64K or less there is
     * no due fold and the in-turn fold is at 90%; above it a fold is due at 60%,
     * rising linearly with the window to 75% at 200K, and runs inside the turn at a
     * point falling linearly from 90% to 80% at 128K — {@link FoldThresholds}.
     * The measurement that moved it: on {@code openai/gpt-oss-120b} (131 072
     * tokens) one coder turn reached <b>80 345</b> prompt tokens and could not
     * fold, and no conversation folded in two days, because the long
     * conversations are single turns and a third of the window only ever applied
     * between them. A threshold nothing can reach while it matters is not a
     * cheaper fold; it is no fold.
     *
     * <h2>What the cost curve still says, and it came from one model on one box</h2>
     *
     * <p><b>{@code qwen3.5-9b} on the reference node, 2026-09-02 —
     * {@code implementation rationale} §4 and
     * §5.</b> That document measured what an ordinary turn costs at a given
     * depth, in two parts. Prefill of one more turn rose from 4.8 s at 9 000
     * tokens to 13.2 s at 69 000. Generation, which the roadmap had recorded as
     * a flat rate, decayed from 31.1 tokens a second on an empty context to 21.3
     * at 18 500, 14.8 at 39 000 and 8.1 at 72 000 — the larger of the two
     * effects. Together they fit a cost per turn rising at about 0.845 s per
     * thousand tokens of standing history.
     *
     * <p>A fold amortises to one to three seconds a turn across a cycle, and the
     * depth penalty does not stop growing, so the mean cost of a turn rises
     * monotonically with the threshold: about 28.8 s folding at 20 000, 31.8 at
     * 30 000, 35.6 at 40 000 and 69.6 at 120 000. <b>Folding later is never
     * cheaper</b> — and that is still true, and still the reason a due threshold
     * sits well short of the wall. What the 2026-09-30 measurement adds is
     * that the curve prices turns, and a single turn of a hundred calls is not
     * priced by it at all until a fold can reach inside it. The per-pool,
     * per-model overrides ({@code compaction-thresholds} for due, {@code
     * compaction-now-thresholds} for now) are still how an operator whose model
     * and box have different curves says so.
     *
     * <p><b>Independent of anything the endpoint does with a cache.</b> The
     * curve above is quadratic attention, which holds on llama.cpp, vLLM and a
     * hosted API alike, so folding earlier is neutral-or-better everywhere and
     * not a bet on this node.
     */
    static final int SPAN_FRACTION = 3;

    /**
     * The line every summarising instruction opens with, whatever span it goes
     * on to ask for.
     *
     * <p>Held apart from the rest of {@link #ASK_FOR_A_SUMMARY} because the rest
     * of it varies now — the instruction names the turns it wants, so no two
     * folds of a conversation send the same text — and this sentence does not.
     * It is the fixed thing about a summarising call: the one message in this
     * server that says it is the harness rather than the person, which is both
     * what the model needs to be told first and what tells this call apart from
     * a turn's.
     */
    static final String FROM_THE_HARNESS = """
            This message is not from the person you have been talking to. It is \
            the harness that owns this conversation, and it is asking for one \
            thing that is not a reply to anybody.""";

    /**
     * What the caller tells the summarising model, and it is bookkeeping about
     * this call now rather than an instruction on how to summarise.
     *
     * <p><b>It used to be both, and that was the bug.</b> {@code
     * conversation_folder}'s own prompt already carries the job, a fidelity
     * rule, the style aimed at it, and containment — see that file's prompt and
     * the comment above it. Until now this message repeated all of it a second
     * time, in the same words a system prompt this call also sends was already
     * using: "keep decisions taken, facts established, names and paths", the
     * same disclaimer of participation, the same containment paragraph. Two
     * slots carrying one instruction is not redundancy that helps; it is one
     * instruction that cost twice and one place fewer for a reader to notice if
     * the two ever said something different. {@code
     * implementation rationale} §6.5 names
     * where each of those duties now belongs: "a compaction prompt in the
     * system slot, which is where containment belongs and where §6.1 stops
     * being possible" — and a system slot is also where a job description and a
     * style belong, for the same reason.
     *
     * <p><b>What is left is what only this message can say.</b> A system
     * prompt is fixed at boot; it cannot name which turns a particular fold
     * covers or whether an earlier summary is standing beside this one. That is
     * the whole of what {@link #askForASummary} still does: open with {@link
     * #FROM_THE_HARNESS} so the model does not read one more user turn as the
     * person asking, then name the span. Nothing about how to write the summary
     * is said again here.
     *
     * <h2>A template, because the span is the whole of the change</h2>
     *
     * <p><b>This is a format string and not the message: {@link
     * #askForASummary} is what goes out.</b> A fold covers the turns since the
     * last one and leaves that one standing — {@code foldTheLog} — so telling a
     * summariser the wrong thing about what came before would either repeat a
     * span already covered or leave one it was never shown named nowhere.
     * {@link #THE_WHOLE_CONVERSATION_SO_FAR} and {@link
     * #THE_SPAN_SINCE_THE_LAST_SUMMARY} are the two shapes that span-naming
     * takes, and this field is only ever seen formatted with one of them.
     *
     * <h2>Where this arrives, and why it says "below"</h2>
     *
     * <p>A fold runs as {@code conversation_folder} over the span as data —
     * {@link TurnTranscript#summarise}, and {@code
     * implementation rationale} §6.5 for why
     * that shape and not another — so this message is the <em>one</em> user
     * message of a call that carries the folder's prompt in the system slot,
     * and the record it names follows it in that same message: below it, and
     * the wording says so.
     *
     * <p><b>{@link #FROM_THE_HARNESS} is what the scripted transports in
     * {@code CompactionTest} and {@code ProjectionTest} match a fold on</b> —
     * by {@code startsWith}, on this message's fixed opening sentence and
     * nothing after it. Everything past that opening is the part that varies
     * fold to fold and carries no instruction any more, so shortening it here
     * changed no prefix either transport reads.
     */
    static final String ASK_FOR_A_SUMMARY = FROM_THE_HARNESS + "\n\n%s";

    /**
     * What a first fold names: everything it reaches.
     *
     * <p>There is no standing summary above it, so there is nothing to say
     * about one. It still names the reach rather than saying "of it", because
     * the list this sentence is formatted into runs one turn further than the
     * fold does — {@link TurnTranscript#summarise} says why the most recent
     * turn is sent to a call that does not fold it — and "the conversation
     * below" alone would quietly include the exchange the person is still in
     * the middle of.
     */
    static final String THE_WHOLE_CONVERSATION_SO_FAR =
            "Write a summary of turns 1 to %d of the conversation below.";

    /**
     * What a later fold names: the turns since the summary that is staying.
     *
     * <p>Two things it has to say, and each clause is one of them. <b>That the
     * earlier summary stands</b>, because a model shown turns it is not asked
     * to cover, with no reason given, has been handed an instruction to forget
     * them rather than an account of who is already covering them. <b>The
     * span</b>, which is the whole of the fidelity this exists for: those turns
     * are summarised once here and never again, instead of being carried
     * through a further lossy pass on every fold.
     *
     * <p><b>It no longer also tells the model to read the rest for context.</b>
     * That sentence was doing work the data already does on its own: {@link
     * TurnTranscript#theSpan} puts the standing summary in the block regardless
     * of what this sentence says, so a reader who wants the earlier decisions a
     * later turn refers to has always had them in front of it. Saying so again
     * was instruction, not bookkeeping, and it is exactly what moved out.
     */
    static final String THE_SPAN_SINCE_THE_LAST_SUMMARY =
            "Turns 1 to %d are already summarised, and that summary stands unchanged in the"
                    + " conversation below, beside the one you are about to write: write a"
                    + " summary of turns %d to %d only.";

    /**
     * How the span a fold reads is written down, and why it is written down at
     * all.
     *
     * <h2>The record is text now, and no longer a conversation</h2>
     *
     * <p><b>A fold is handed the turns it covers as data in one user message,
     * rather than being handed the conversation to stand in.</b> {@code
     * implementation rationale} §6.5 is the
     * argument: of the five things the old shape needed a model to get right,
     * scope — "turns 9 to 56 only, and leave out what the earlier summary
     * covers" — is the one that cannot be fixed by better wording, because it is
     * a request to read part of an input and ignore the rest. Put the span in
     * the message and scope stops being a request at all.
     *
     * <p><b>Turn numbers, said and answered, and nothing cleverer.</b> The
     * numbers are what {@link #askForASummary} names — an instruction asking for
     * "turns 2 to 3" over a record whose turns are unnumbered is asking about
     * something the reader cannot find — and the two labels are what a
     * conversation is: one thing said and one thing that came back. A fold that
     * loses which of the two said something has lost the only distinction a
     * summary of a conversation rests on.
     *
     * <p><b>The working is rendered as it stands in the projection and is not
     * re-read.</b> {@link #whatWasSaidAndWhatCameBack} has already decided how
     * much of a turn's own working this conversation's agent is shown — a
     * bounded reference for an agent that can redeem one, nothing at all for an
     * agent that cannot — and this renderer shows whatever that left. So a fold
     * reads what the conversation itself would have been shown, flattened;
     * there is no second reading of the log in which a result that a turn could
     * not see comes back.
     */
    private static final String A_TURN = "Turn %d";

    /** What the person said, which opens a turn. See {@link #A_TURN}. */
    private static final String SAID = "Said: ";

    /** What came back, which is a model's prose or the sentence {@code
     *  JobRuntime} wrote about an ending. See {@link #A_TURN}. */
    private static final String ANSWERED = "Answered: ";

    /** What an answer asked to call, for the answers that are entirely tool
     *  calls and carry no prose. The arguments are included: a path or a name
     *  passed to a tool is exactly the kind of thing {@code
     *  conversation_folder} is told to keep. */
    private static final String ASKED_FOR = "Asked for: ";

    /** What a tool returned, as the projection left it — the result itself, or
     *  the bounded reference that stands in for one. See {@link #A_TURN}. */
    private static final String CAME_BACK = "Came back: ";

    /** What {@link Noticing} told the person's own turn, folded into that
     *  turn's block rather than opening one of its own. See {@link #A_TURN}
     *  and {@code userMessagesAreUtterances} for why a {@code USER}-role
     *  message needs asking at all before it is assumed to be one. */
    private static final String NOTICED = "Noticed: ";

    /**
     * The user message one fold sends: {@link #ASK_FOR_A_SUMMARY} with the span
     * this fold is being taken over written into it.
     *
     * @param since how far the log is already folded — the reach of the summary
     *     that is standing, and zero for a conversation that has never folded.
     *     <b>The bound is exclusive</b>, so the span asked for starts at the
     *     turn after it, and a first fold asks from turn one rather than from
     *     turn zero
     * @param through the last turn this fold stands for
     */
    static String askForASummary(int since, int through) {
        return ASK_FOR_A_SUMMARY.formatted(since <= 0
                ? THE_WHOLE_CONVERSATION_SO_FAR.formatted(through)
                : THE_SPAN_SINCE_THE_LAST_SUMMARY.formatted(since, since + 1, through));
    }

    /** The part of a seam before anything is said about what is behind it. Not a
     *  message on its own; see {@link #SEAM}. */
    private static final String SEAM_OPENING =
            "Turns %d to %d of this conversation were summarised to make room, and are not"
                    + " repeated below. The originals are unchanged in the transcript.";

    /** How a seam ends, whichever seam it is: the summary, introduced as one. */
    private static final String SEAM_SUMMARY = " The summary:%n%n%s";

    /** The one sentence that makes what is behind a seam addressable again. Its
     *  length does not depend on how much was folded, which is the whole reason
     *  it is a sentence and not a list of handles; see {@link
     *  #SEAM_OVER_STORED_RESULTS}. */
    private static final String STORED_RESULTS_SURVIVE_IT =
            "What the tools returned in those turns was not summarised and is stored whole: "
                    + ResultTools.LIST_NAME + " names each stored result with its handle, and "
                    + ResultTools.READ_NAME + " reads one back in full.";

    /**
     * How the summary is introduced to the model on the next turn.
     *
     * <p>The seam, said out loud. A model shown a summary as if it were the
     * conversation cannot tell that it is reading a compression of one, and will
     * answer about the gaps with the same confidence as about the rest.
     *
     * <p><b>It names both ends of its span and not only the reach.</b> A fold
     * used to replace the fold before it, so every seam a model ever read began
     * at turn one and saying so cost nothing. Folds are incremental now — {@code
     * foldTheLog} — and a conversation folded twice shows two seams at once, so
     * two sentences both opening "Turns 1 to" would describe overlapping spans
     * and the second of them would be false: that summary covers the turns since
     * the first and no more.
     *
     * <p>The lower bound is the turn after the previous standing seam's reach,
     * which {@link Projection} carries down the log as it renders. A first fold
     * has no previous seam and a reach of zero, so it reads "Turns 1 to 4" as it
     * always did rather than "Turns 0 to 4".
     *
     * <p><b>In two halves so that a sentence can be put between them</b>, and
     * the halves are not separately meaningful: {@link #SEAM_OVER_STORED_RESULTS}
     * is the same message with {@link #STORED_RESULTS_SURVIVE_IT} in the middle,
     * spliced here rather than written out twice so that the two seams cannot
     * drift apart in the parts they share.
     */
    static final String SEAM = SEAM_OPENING + SEAM_SUMMARY;

    /**
     * The seam an agent that can list its stored results reads: {@link #SEAM}
     * with one sentence more.
     *
     * <h2>The sentence is why this exists, and its length is the whole design</h2>
     *
     * <p>A fold supersedes the reference lines in the span it covers along with
     * the turns that carried them. The rows stay redeemable — {@code
     * EntryStore.redeem} does not filter {@code superseded_by} — and stop being
     * <b>addressable</b>, because nothing in the prompt tells the model a handle
     * any more. Before this sentence existed, a model past a seam was exactly
     * where it had been before references were built.
     *
     * <p><b>The obvious repair is to list the handles here, and it is wrong.</b>
     * A second fold would have to carry the first fold's handles or lose them, so
     * the list would accumulate across every fold, grow without bound, and partly
     * undo the shrinking a fold exists to do. <b>This sentence is the same length
     * whether the span held two results or two hundred</b>, and the addresses are
     * fetched by a model that has decided it wants them, on a turn it chose to
     * spend. That is the {@code file_stat} trade rather than a new one: a cheap
     * fact that turns a decision into an informed one, and no cost at all to a
     * turn that does not need it.
     *
     * <p><b>It says the results were not summarised</b>, which is the half a
     * model would otherwise get wrong. Everything else in this message is about a
     * summary standing in for what was said; without this clause the natural
     * reading of "and are not repeated below" is that the working went the way of
     * the words, and a model that believes that runs the tool again.
     *
     * <p><b>It is conditional, and {@link AgentDefinition#canList()} is the
     * condition.</b> A seam naming a tool the agent does not hold buys a turn
     * spent on "there is no tool called result_list" and a false belief after it —
     * which is the failure {@code canRedeem} already exists to prevent one layer
     * down, and it arrives here through a sentence rather than through a
     * substitution.
     */
    static final String SEAM_OVER_STORED_RESULTS =
            SEAM_OPENING + " " + STORED_RESULTS_SURVIVE_IT + SEAM_SUMMARY;

    /**
     * Which of the two seams a history is rendered with.
     *
     * <p>Here rather than in {@link Projection} so that the wording and the
     * choice between the wordings sit together: the sentence that names {@code
     * result_list} and the question "does this agent hold it" are one decision,
     * and a reader who finds one has found the other.
     *
     * @param canList {@link AgentDefinition#canList()} for the agent this history
     *     is being read for
     */
    static String seam(boolean canList) {
        return canList ? SEAM_OVER_STORED_RESULTS : SEAM;
    }

    /**
     * What stands in for a turn whose answer was the empty string.
     *
     * <p>{@code Outcome} permits it — "a model that stops on its first token has
     * answered with nothing, which is a decision and not a failure" — but {@code
     * ChatMessage} refuses an assistant message with neither content nor tool
     * calls, on the grounds that it is a turn that did not happen. So the
     * absence is rendered rather than dropped, exactly as {@code
     * JobRuntime.usable} renders a tool that returned nothing: dropping it would
     * leave an utterance with no reply after it, and a model reading that has
     * been shown a conversation that never took place.
     */
    static final String SAID_NOTHING = "(answered with nothing)";

    /**
     * What a later turn is shown in place of an earlier turn's tool result.
     *
     * <h2>It is the tool message, not a note beside it</h2>
     *
     * <p>Same {@code tool} role, same {@code tool_call_id}, content substituted.
     * That is what keeps the call/result pairing valid <em>by construction</em>:
     * there is still exactly one {@code tool} message per {@code tool_call}, so
     * nothing dangles, {@link Projection#pairTheUnanswered} never fires for this
     * case, and the model reads an ordinary well-formed turn in which the result
     * happens to be a pointer.
     *
     * <h2>This is model-visible text and every clause is a decision</h2>
     *
     * <p>The whole point is that the model can decide <b>without redeeming</b>,
     * so the line has to carry enough to decide on and nothing it does not:
     *
     * <ul>
     *   <li><b>which tool ran.</b> The largest single fact about a result the
     *       model cannot see. It is what separates "the file I already read" from
     *       "the search I already ran";
     *   <li><b>how big it is.</b> The other half of the decision, and the half
     *       nothing else in the prompt can supply: a model weighing whether to
     *       spend a turn is weighing that turn against a quantity;
     *   <li><b>where the arguments are, rather than the arguments.</b> The spec
     *       asks for "the path, the pattern" and this deliberately points at them
     *       instead of copying them, because the assistant message carrying the
     *       call is <em>immediately above</em> — the pairing above guarantees it —
     *       and copying is both redundant and UNBOUNDED. {@code file_edit} sends
     *       a whole file as an argument, so a reference that inlined arguments
     *       could be larger than the result it stands for. Named as a departure
     *       from the spec rather than left to be noticed;
     *   <li><b>that nothing was lost.</b> A model reading "not shown" and
     *       inferring "gone" would re-run the tool, which is the outcome this
     *       change exists to stop and is exactly today's behaviour with an extra
     *       line paid for;
     *   <li><b>how to get it, by name.</b> {@link ResultTools#READ_NAME} and the
     *       handle, so the next action is in the line rather than inferable from
     *       it. {@code the_reference_names_the_tool_that_redeems_it} is what fails
     *       if the two ever spell different names;
     *   <li><b>who is speaking.</b> Everything else in the {@code tool} slot is a
     *       tool's own output, so a harness sentence sitting there unattributed
     *       reads as one. {@link Projection#NEVER_COMPLETED} and {@link
     *       #ASK_FOR_A_SUMMARY} both open with the same move for the same reason.
     * </ul>
     *
     * <p><b>{@code %d} and not {@code %,d}.</b> {@code String.formatted} uses the
     * default locale, so a grouped number is a model-visible string that changes
     * with the server's locale — "4,812" here and "4 812" on a box configured in
     * French, with a non-breaking space in it. A raw integer is the same
     * everywhere and is what the threshold below is computed against.
     *
     * <p><b>Its length is the threshold</b>, and that is the one number here
     * nobody had to pick: a result shorter than this line costs more as a
     * reference than as itself, so {@link #referenced} builds the line and keeps
     * whichever of the two is smaller. See {@code
     * a_result_smaller_than_its_own_reference_is_kept_as_itself}.
     */
    static final String REFERENCE = "[Stored result, not shown here. %s ran and returned %d"
            + " characters; its arguments are in the call this answers, immediately above."
            + " Nothing was trimmed and nothing was summarised: read the whole of it back with"
            + " %s, handle %s. This line is the harness that owns this conversation speaking,"
            + " and is not the tool's answer.]";

    /**
     * How much one turn has to add to the conversation before it is worth
     * folding on its own account: a third of the size this conversation folds
     * at.
     *
     * <p><b>{@link #SPAN_FRACTION}, which used to be the fraction the fold
     * threshold itself was taken at, applied one level down.</b> It said a
     * conversation folds at a third of the window, and this that a single turn is
     * enormous when it alone adds a third of everything the conversation is
     * allowed to weigh. The fold threshold has moved ({@link FoldThresholds}); this
     * one is left where it was, as a third of whatever the due threshold is — the
     * spec changes the thresholds and not how a between-turn fold chooses its span.
     *
     * <h2>The number is unchanged and what it measures is not</h2>
     *
     * <p>This threshold used to be compared against a quantity that counted a
     * turn's tool traffic — {@code longestPrompt - firstPrompt}, everything the
     * turn appended to its own in-run history. That is the turn's <em>cost</em>,
     * and against it this fraction was easy to reach and reaching it meant
     * nothing: none of that traffic survives into a later prompt, so the fold it
     * bought recovered none of what triggered it.
     *
     * <p>What it is compared against now is {@link TurnTranscript#added()} — the
     * answer the turn came to, which is exactly what the next turn's context
     * carries that this one's did not. <b>So the sentence above is true of this
     * constant for the first time</b>: a third of the fold threshold is a third
     * of the conversation's allowance, genuinely added, by one turn.
     *
     * <p><b>What that costs is reachability, and it is stated rather than tuned
     * away.</b> On a 131 072-token model with no configured threshold, a fold is
     * due at 87 895 and this fires on a single answer of about 29 300 tokens.
     * That is rare and it is not unreachable: nothing in this server sets
     * {@code max_tokens} — {@code OpenAiTransport.chatBody}
     * says so in as many words — so a generation is bounded only by the window
     * and by {@code OpenAiTransport.MAX_STREAM_CHARS}, and {@code
     * completion_tokens} folds reasoning tokens into the count. A reasoning model
     * thinking at length, or one in a repetition loop, reaches it. Every other
     * turn is caught by the threshold trigger one turn later, which is the lag
     * this exists to close and not a case it exists to catch.
     *
     * <p><b>It is not derived from the read cap, and that is the interesting
     * refusal.</b> The obvious anchor used to look like {@code
     * FileTools.Window.MAX_WINDOW_BYTES} — 96 KiB, a real enforced bound, and the
     * exact shape of the turn this trigger was written for. It is measured in
     * <em>bytes</em>, and this class's javadoc has already costed turning a
     * character bound into a token bound and refused it. The corrected quantity
     * removes the temptation as well as the argument: a read cap bounds a turn's
     * working, and a turn's working is precisely what this no longer weighs.
     */
    private static int foldASpanAt(int foldAt) {
        return foldAt / SPAN_FRACTION;
    }

    /**
     * What a fold that happened says about itself, in the conversation's own
     * log.
     *
     * <p>The span, and then the numbers the decision was taken from, and what
     * the turn spent on its own working beside them. It is
     * prose and nothing parses it — see {@code LoggedEntry.diagnostic} — so the
     * counts are spelled out rather than encoded.
     */
    static final String COMPACTED = "A compaction was triggered and turns %d to %d were folded"
            + " into a summary. %s";

    /**
     * What a fold that did not happen says, when it was triggered and the log
     * came out of it unchanged.
     *
     * <p><b>The lower bound is deliberately absent.</b> A fold reaches this
     * sentence because its summarising call failed or because the log refused
     * the fold, and the read that would have given the lower bound is one of the
     * things that can have failed — a sentence that guessed at it would be a
     * reader told which turns were folded by a method that did not find out.
     */
    static final String NOT_COMPACTED = "A compaction was triggered through turn %d and the"
            + " conversation was not folded. Nothing in the log changed. %s";

    /**
     * The numbers, in the unit the constraint is in.
     *
     * <h2>Two of them are the decision and the third is not</h2>
     *
     * <p><b>What the turn sent</b> is the context that went in front of the
     * model — the history plus the person's question, which is the turn's first
     * prompt — and it is asked whether the conversation still fits. <b>What the
     * turn added</b> is the assistant messages it generated that a later turn
     * will carry — which of them depends on the agent — and that is what the
     * next turn's context carries that this one's did not; it is asked whether
     * the turn that just ended is by itself big enough to be worth summarising.
     * See {@link TurnTranscript#added()}.
     *
     * <p><b>What the turn's working cost is reported and feeds nothing</b>, and
     * the separation is the point. {@code longestPrompt - firstPrompt} is
     * everything the turn appended to its own in-run history — tool calls, tool
     * results, intermediate generations — and it is real money: it is what the
     * turn actually spent, and an operator asking why a turn was slow or
     * expensive has nowhere else to read it. It is <em>not</em> context growth.
     * It counts every tool result at <b>full size</b>, and a later turn carries
     * at most a bounded reference to each — {@link #whatWasSaidAndWhatCameBack} — so
     * a trigger that read it would fold a conversation on every turn that reads a
     * file, each time for a quantity the fold cannot recover. It is named as cost
     * in this sentence so that nobody reads it back as weight.
     *
     * <p>Recorded here and nowhere else, because there is nowhere else to put
     * them: {@code turns.prompt_tokens} holds one number per turn and widening
     * it is a migration. A diagnostic entry's body is free-form and never
     * reaches a model, so it carries all three without a schema change.
     */
    static final String THE_NUMBERS = "The context this turn sent measured %d tokens and the"
            + " turn added %d to it, against a fold threshold of %d and a span threshold of %d."
            + " Its own working cost a further %d tokens, which a later turn carries only as a"
            + " reference to each result.";

    // --- a fold inside a turn (spec 2026-09-30-fold-at-60-and-80) ------------------------------

    /**
     * How much of the window the steps a fold inside a turn keeps whole may take: about 30%,
     * the spec's number. Most recent first, whole steps only, and never fewer than {@link
     * #KEEP_AT_LEAST} however large they are — a step split from its results is a request the
     * endpoint refuses, and a turn left with fewer than three steps of its own working has
     * lost the thread it was following.
     */
    static final int KEEP_PERCENT = 30;

    /** The fewest steps a fold inside a turn keeps whole: the last three, whatever they weigh. */
    static final int KEEP_AT_LEAST = 3;

    /**
     * What an estimated count is multiplied by before it is weighed against a threshold: four
     * thirds, a third again. Only the part of an in-turn measurement the model has not counted
     * yet — one step's results — is ever an estimate, and an estimate that ran low is the
     * direction that ends in a refused request, so it is inflated rather than trusted; a count
     * the {@link Tokenizer} says it <em>measured</em> is taken as it is. The same room the
     * embedding ceiling leaves under its 2 048 tokens (1 536), for the same reason.
     */
    private static final int HEADROOM_NUMERATOR = 4;
    private static final int HEADROOM_DENOMINATOR = 3;

    /** How an in-turn summary opens when it took only the turn's own older steps. Not a message
     *  on its own; see {@link #turnSeam}. */
    private static final String TURN_SEAM_OPENING = "This message is from the harness that owns"
            + " this conversation, not from the person: earlier work in this turn was summarised"
            + " to make room and is not repeated here. The originals are unchanged in the"
            + " transcript.";

    /** The same, for a fold that also took earlier turns nothing stood for yet. */
    private static final String TURN_SEAM_OPENING_WITH_TURNS = "This message is from the harness"
            + " that owns this conversation, not from the person: turns %d to %d of this"
            + " conversation, and earlier work in this turn, were summarised to make room and are"
            + " not repeated here. The originals are unchanged in the transcript.";

    /** {@link #STORED_RESULTS_SURVIVE_IT}, for the work of a turn rather than for turns. */
    private static final String STORED_RESULTS_OF_THE_WORK_SURVIVE_IT =
            "What the tools returned in that work was not summarised and is stored whole: "
                    + ResultTools.LIST_NAME + " names each stored result with its handle, and "
                    + ResultTools.READ_NAME + " reads one back in full.";

    /**
     * What a fold inside a turn puts where the older steps were: the summary, marked as the
     * harness's summary of earlier work in this turn (spec 2026-09-30-fold-at-60-and-80 §1).
     *
     * <p><b>It is {@link #SEAM}'s move, one message down.</b> A summary read as if it were the
     * work it stands for is information loss wearing a receipt; so it says who is speaking,
     * what it stands for and that the originals are still there, and nothing else about the
     * fold — no threshold, no warning, no number. Where the fold took earlier turns too it
     * names them in the same words a between-turn seam does, because the model is reading
     * both.
     *
     * <p>The same function renders it on the run's own list and in {@link Projection} from the
     * log, so the two cannot drift apart — which is what "a projection rebuilt from the log
     * reproduces it" rests on.
     *
     * @param canList {@link AgentDefinition#canList()}, which decides whether the sentence
     *     naming {@code result_list} is there, on {@link #seam}'s terms
     * @param fromTurn the first earlier turn the fold took
     * @param toTurn the last earlier turn it took; below {@code fromTurn} when it took none
     * @param summary what the folder wrote
     */
    static String turnSeam(boolean canList, int fromTurn, int toTurn, String summary) {
        String opening = fromTurn <= toTurn
                ? TURN_SEAM_OPENING_WITH_TURNS.formatted(fromTurn, toTurn)
                : TURN_SEAM_OPENING;
        return opening + (canList ? " " + STORED_RESULTS_OF_THE_WORK_SURVIVE_IT : "")
                + SEAM_SUMMARY.formatted(summary);
    }

    /** What the instruction to a fold inside a turn asks for when it took earlier turns. */
    static final String THE_TURNS_AND_THE_WORK_SO_FAR = "Write a summary of turns %d to %d of the"
            + " conversation below, and of the work so far in turn %d, which is still in"
            + " progress.";

    /** And when it took only the turn's own older steps. */
    static final String THE_WORK_SO_FAR = "Write a summary of the work so far in turn %d of the"
            + " conversation below, which is still in progress.";

    /** Said when turns before the span are already summarised. */
    static final String TURNS_ALREADY_SUMMARISED = "Turns 1 to %d are already summarised, and that"
            + " summary stands unchanged in the conversation below, beside the one you are about"
            + " to write.";

    /** Said when earlier work of the same turn is already summarised. */
    static final String WORK_ALREADY_SUMMARISED = "Earlier work in turn %d is already summarised"
            + " below, and that summary stands unchanged beside the one you are about to write:"
            + " summarise only the steps after it.";

    /** Said always: the part the fold keeps is not in the record, and the reader is told why. */
    static final String THE_LATEST_STEPS_STAY = "The turn's most recent steps stay in front of"
            + " the model word for word, so they are not in the record below.";

    /**
     * The user message a fold inside a turn sends: {@link #ASK_FOR_A_SUMMARY}'s opening, then the
     * span in words. {@link #FROM_THE_HARNESS} first, for {@link #askForASummary}'s reason.
     *
     * @param since how far the log is already folded, exclusive
     * @param turn the turn in progress
     * @param workSummarised whether an earlier fold inside this turn left a summary standing
     */
    static String askForATurnSummary(int since, int turn, boolean workSummarised) {
        StringBuilder asked = new StringBuilder();
        if (since > 0) {
            asked.append(TURNS_ALREADY_SUMMARISED.formatted(since)).append(' ');
        }
        asked.append(since < turn - 1
                ? THE_TURNS_AND_THE_WORK_SO_FAR.formatted(since + 1, turn - 1, turn)
                : THE_WORK_SO_FAR.formatted(turn));
        if (workSummarised) {
            asked.append(' ').append(WORK_ALREADY_SUMMARISED.formatted(turn));
        }
        asked.append(' ').append(THE_LATEST_STEPS_STAY);
        return ASK_FOR_A_SUMMARY.formatted(asked);
    }

    /** How the turn in progress is headed in the record a fold inside it reads. */
    private static final String A_TURN_IN_PROGRESS = "Turn %d, in progress";

    /** An earlier in-turn summary, in that record. */
    private static final String SUMMARISED = "Summarised: ";

    /**
     * Where the context of a turn stood at a tool-call boundary, in the unit the constraint is
     * in and split the way it was counted: the model's own count of the last prompt and of
     * what it generated, and the estimate for what was appended since. Prose, like {@link
     * #THE_NUMBERS}; nothing parses it.
     */
    static final String STOOD_AT = "The context stood at about %d tokens: the model counted %d"
            + " for the last prompt and %d for what it generated, and %d were estimated for what"
            + " has been appended since, counted through the tokenizer with room for estimate"
            + " error. The model's context is %d tokens.";

    /** What the log says when a fold becomes due inside a turn: nothing runs yet. */
    static final String FOLD_DUE = "A fold became due in turn %d, against a threshold of %d. %s It"
            + " runs when the turn ends, if the conversation is still over the threshold then.";

    /** What the log says about a fold that ran inside a turn. */
    static final String FOLDED_IN_THE_TURN = "A fold ran inside turn %d, before its next model"
            + " call, against a threshold of %d: %s folded into a summary placed where the steps"
            + " were, and the turn's request and its last %d steps were kept whole. %s It cost"
            + " one summariser call, which counts against neither the turn's step cap nor the"
            + " run's model-call budget.";

    /** What the log says about a fold inside a turn that could not be made. */
    static final String NOT_FOLDED_IN_THE_TURN = "A fold was triggered inside turn %d against a"
            + " threshold of %d, and the turn goes on with its whole history: %s. Nothing in the"
            + " log changed. %s The fold is not tried again until the context has fallen below"
            + " the threshold and crossed it again.";

    private final LlmDispatcher models;

    /**
     * Where a conversation is opened for a run that is nobody's turn, or null
     * for a wiring that opens none.
     *
     * <p><b>Nullable, and the null is a fixture rather than a deployment.</b>
     * Every {@code @Bean} in {@code AgentsConfig} supplies it, so no boot of
     * this server is without it; what the null is for is the tests that build a
     * {@code Compaction} over an in-memory dispatcher to assert on folding and
     * have no {@code conversations} table to open anything in. {@link
     * #logFor} answers {@code Transcript.NONE} when it is absent, which is the
     * only remaining production-shaped meaning of that constant — see its
     * javadoc.
     */
    private final ConversationStore conversations;

    /**
     * What is told that a fold has just made material invisible, or {@link
     * Learning#NONE}.
     *
     * <p><b>An interface and not the learner, which is the whole of what keeps a
     * fold safe.</b> This class knows that something wants to hear about folded
     * material; it does not know what that something does, cannot be made to
     * wait for a model call it can see, and gains no dependency on the archive.
     * {@link Learning} carries the ordering rule — the fold commits first, this
     * runs after (and after {@code fold.post}'s notices), on the fold's own thread — and {@link
     * TurnTranscript#foldIfItWouldNotFit} keeps its own {@code catch} around the
     * call anyway.
     *
     * <p>Never null. Absent wiring is {@link Learning#NONE} rather than a null
     * check at the one call site, because a no-op is what "this server does not
     * learn" actually is, and it is a legal server.
     */
    private final Learning learning;

    /**
     * What is told that an answer cited something, or {@link Citing#NONE}.
     *
     * <p>{@link #learning}'s shape and its wiring: never null, defaulted in the
     * constructor, so {@link TurnTranscript#closed} calls it unconditionally and
     * a server with no corpus is a server whose turns close exactly as they did.
     */
    private final Citing citing;

    /**
     * The agent a fold runs as, asked for once per fold.
     *
     * <h2>Why this class holds one at all</h2>
     *
     * <p><b>A fold used to run as the agent being folded — its model, its
     * temperature and its system prompt — and that is a defect and not a
     * saving.</b> A system-slot instruction outranks a user-slot one, so a bot
     * with a strong voice folded in that voice: measured, three runs of three,
     * a "CAVEMAN" bot folded a conversation carrying four decisions and a file
     * path down to "CAVEMAN grunt. CAVEMAN no understand this big word. Too much
     * think." — {@code
     * implementation rationale} §6.1. The
     * same persona ten words shorter passes three runs of three, so the trigger
     * is a sentence rather than a kind of bot, it cannot be found by reviewing
     * the bots that exist, and better wording in the instruction is not a fix.
     *
     * <h2>A supplier of one definition, and not the registry</h2>
     *
     * <p><b>The narrowest dependency that answers the question.</b> This class
     * needs one definition and never a graph; taking an {@code AgentRegistry}
     * would let it reach every other agent, and would make a fixture that only
     * wants to assert what a fold sent stand up a registry to get one. It is a
     * supplier rather than the definition itself for {@code JobRuntime}'s reason
     * exactly — {@code AgentsConfig} backs it with an {@code ObjectProvider}
     * resolved on the first fold and not at bean-creation time, so a registry
     * built after this bean is still the one a fold reads, and an operator
     * editing {@code conversation_folder.md} between folds is read rather than
     * cached.
     *
     * <p>Never null, and no default. A {@code Compaction} that cannot name its
     * folder is a {@code Compaction} that cannot fold, and every call site is
     * made to say what it folds as — a compile error at a fixture is the cheap
     * version of that, and a silently unfoldable conversation is the expensive
     * one. What the supplier does on a miss is the registry's business: {@code
     * AgentRegistry.get} throws, {@link TurnTranscript#summarise} catches, and
     * the turn runs with its whole history and says why in the log.
     */
    private final Supplier<AgentDefinition> folder;

    private final TurnStore turns;
    private final CompactionStore compactions;
    private final EntryStore entries;

    /**
     * Where a fold runs, which is never the thread that asked for one.
     *
     * <p>One virtual thread per fold, for {@code JobStore}'s reason exactly: a
     * fold blocks on a model call for tens of seconds, and blocking on the
     * dispatcher is what virtual threads are for. The scarce resource is the
     * lane {@code LlmPool} bounds, not the thread, so a fold waiting for one
     * holds nothing that a turn needs.
     */
    private final Executor folds;

    /**
     * The same executor when this instance made it, and null when it was given
     * one.
     *
     * <p>Only what {@link #close()} is allowed to shut down. An executor handed
     * in belongs to whoever handed it in — a test that wants a fold to run on
     * the calling thread, or on demand — and shutting down somebody else's
     * executor from a lifecycle method is how a fixture ends up debugging this
     * class.
     */
    private final ExecutorService owned;

    /**
     * The conversations with a fold in flight.
     *
     * <p>A set of ids and not a map to anything, for the reason {@code
     * Turn.speaking} is one: there is nothing about the fold a second caller
     * could do with a handle on it. The entry is added on the thread that asks
     * for the fold and removed on the thread that runs it, in a {@code finally},
     * so a fold that threw anywhere at all still frees its conversation.
     *
     * <p><b>Not the same guard as {@code Turn.speaking} and it cannot be.</b>
     * That one is released when a turn ends, and a fold starts there and outlives
     * it on purpose.
     */
    private final Set<String> folding = ConcurrentHashMap.newKeySet();

    private volatile RunUsage runUsage = RunUsage.NONE;

    /** Production ownership resolver; disabled accounting and fixtures keep the original behavior. */
    public void useRunUsage(RunUsage source) { runUsage = java.util.Objects.requireNonNull(source); }

    public boolean accountingEnabled() { return runUsage != RunUsage.NONE; }

    /** Freeze a service root before it creates children, even if it never performs inference itself. */
    public Transcript own(Home home, Transcript transcript, AgentDefinition definition, String account) {
        return runUsage == RunUsage.NONE ? transcript : runUsage.start(home,transcript,definition.name(),account);
    }

    /**
     * The context length to fold against when nobody can say what the model is
     * really loaded at: {@code plowshare.llm.default-context-length}.
     *
     * <p><b>The policy this class holds, which is why it is here and not on the
     * dispatcher.</b> {@link LlmDispatcher#contextLength(String, int)} ranks
     * three tiers — a configured entry, then the node's own report, then this —
     * but the first two are facts about the fleet and this one is a decision
     * about what to do without them. Compaction is the only thing that has to
     * take that decision, so it is the thing that carries the number.
     *
     * <p>Before it existed, a model nobody could size was a conversation that
     * never folded: no bound, no decision, and a server that kept working right
     * up until an endpoint refused a prompt. One warning at boot was the whole of
     * the report. See {@code application.yml} for why the number is 64 000 and
     * why being wrong downwards is the cheap direction.
     */
    private final int defaultContextLength;

    /**
     * Told when a conversation's log grew. See {@link LogGrowth}.
     *
     * <p>Set after construction and not passed in, because what it tells —
     * the socket's sessions — is wired long after this bean and must not be
     * needed to build it. Until then it tells nobody, which is also what every
     * fixture that never asks gets.
     */
    private volatile LogGrowth growth = LogGrowth.NONE;

    /**
     * The log stages (spec 2026-09-28-hooks-reach-the-log §3): {@code log.open} for every log
     * {@link #logFor} opens, {@code log.close} when a machine log's run ends, and {@code
     * fold.post} once the folder has written a fold's summary, on the fold's own thread. {@link
     * LogStages#NONE} until wired — a setter, on {@link #growth}'s pattern, because the stages
     * are built after this bean, over the turn registry that is built over it.
     */
    private volatile LogStages logStages = LogStages.NONE;

    /**
     * What counts what a model has not counted yet: a step's results, appended after the last
     * prompt was measured, when a fold inside a turn is being decided. The server's one {@link
     * Tokenizer} — never a character ratio of this class's own, which is what the argument
     * about constants above refuses — and the count it gives is inflated by a third where it
     * is an estimate ({@link #HEADROOM_NUMERATOR}).
     *
     * <p>Set after construction, on {@link #growth}'s pattern. <b>Null is a fixture and not a
     * deployment</b>: {@code AgentsConfig} hands every boot one. A compaction without one
     * decides inside a turn from what the model counted alone — which under-states by one
     * step's results — and keeps exactly {@link #KEEP_AT_LEAST} steps, since it cannot weigh
     * them.
     */
    private volatile Tokenizer tokenizer;

    public Compaction(
            LlmDispatcher models, Supplier<AgentDefinition> folder, TurnStore turns,
            CompactionStore compactions, EntryStore entries, int defaultContextLength) {
        this(models, folder, turns, compactions, entries, defaultContextLength, (Executor) null,
                null, null);
    }

    /**
     * The same, able to open the conversation a run that is nobody's turn logs
     * into.
     *
     * <p>The production wiring. The constructor above states <b>this compaction
     * opens no conversations</b>, which is true of the fixtures that build one
     * to assert on folding and false of every boot.
     *
     * @param conversations where a delegated child's, a curator's ruling's or a
     *     submission's row is written
     */
    public Compaction(
            LlmDispatcher models, Supplier<AgentDefinition> folder, TurnStore turns,
            CompactionStore compactions, EntryStore entries, int defaultContextLength,
            ConversationStore conversations) {
        this(models, folder, turns, compactions, entries, defaultContextLength, null,
                conversations, null);
    }

    /**
     * The same, telling something that a fold has made material invisible.
     *
     * <p>The production wiring. The constructor above states <b>nothing is told
     * about this server's folds</b>, which is true of every fixture that builds
     * a compaction to assert on folding and false of every boot.
     *
     * @param learning what is told, or null for a server that does not learn —
     *     see {@link #learning}
     */
    public Compaction(
            LlmDispatcher models, Supplier<AgentDefinition> folder, TurnStore turns,
            CompactionStore compactions, EntryStore entries, int defaultContextLength,
            ConversationStore conversations, Learning learning) {
        this(models, folder, turns, compactions, entries, defaultContextLength, null,
                conversations, learning);
    }

    /**
     * The same, telling something what a finished answer cited.
     *
     * <p>The production wiring, and the constructor {@code AgentsConfig} calls.
     *
     * @param citing what is told, or null for a server with no corpus — see
     *     {@link #citing}
     */
    public Compaction(
            LlmDispatcher models, Supplier<AgentDefinition> folder, TurnStore turns,
            CompactionStore compactions, EntryStore entries, int defaultContextLength,
            ConversationStore conversations, Learning learning, Citing citing) {
        this(models, folder, turns, compactions, entries, defaultContextLength, null,
                conversations, learning, citing);
    }

    /**
     * The same, with the threads a fold runs on supplied.
     *
     * <p>Package-private and for tests, which is said rather than disguised. A
     * fold is asynchronous by design and an assertion about what one produced
     * has to wait for it somehow; an executor that runs the fold on the calling
     * thread, or holds it until the test says so, is how that wait becomes a
     * fact about the test rather than a duration it hopes is long enough. The
     * production wiring uses the constructor above and gets virtual threads.
     *
     * @param given where folds run, or null to make and own one
     */
    Compaction(
            LlmDispatcher models, Supplier<AgentDefinition> folder, TurnStore turns,
            CompactionStore compactions, EntryStore entries, int defaultContextLength,
            Executor given) {
        this(models, folder, turns, compactions, entries, defaultContextLength, given, null,
                null);
    }

    Compaction(
            LlmDispatcher models, Supplier<AgentDefinition> folder, TurnStore turns,
            CompactionStore compactions, EntryStore entries, int defaultContextLength,
            Executor given, ConversationStore conversations) {
        this(models, folder, turns, compactions, entries, defaultContextLength, given,
                conversations, null);
    }

    Compaction(
            LlmDispatcher models, Supplier<AgentDefinition> folder, TurnStore turns,
            CompactionStore compactions, EntryStore entries, int defaultContextLength,
            Executor given, ConversationStore conversations, Learning learning) {
        this(models, folder, turns, compactions, entries, defaultContextLength, given,
                conversations, learning, null);
    }

    /**
     * The same, able to write down what an answer cited.
     *
     * <p>The production wiring. Every constructor above states <b>nothing
     * records a citation</b>, which is true of every fixture that builds a
     * compaction to assert on folding and false of a boot with a corpus.
     *
     * @param citing what is told, or null for a server that records none — see
     *     {@link #citing}
     */
    Compaction(
            LlmDispatcher models, Supplier<AgentDefinition> folder, TurnStore turns,
            CompactionStore compactions, EntryStore entries, int defaultContextLength,
            Executor given, ConversationStore conversations, Learning learning,
            Citing citing) {
        this.conversations = conversations;
        this.learning = learning == null ? Learning.NONE : learning;
        this.citing = citing == null ? Citing.NONE : citing;
        this.models = Objects.requireNonNull(models, "models");
        this.folder = Objects.requireNonNull(folder, "folder");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.compactions = Objects.requireNonNull(compactions, "compactions");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.defaultContextLength = defaultContextLength;
        this.owned = given == null ? Executors.newVirtualThreadPerTaskExecutor() : null;
        this.folds = given == null ? this.owned : given;
    }

    /**
     * Stop taking folds, and leave the one in flight alone.
     *
     * <p><b>{@code shutdown()} and not {@code shutdownNow()} or {@code
     * close()}</b>, which is {@code JobStore.close}'s decision for the same
     * reasons and one more. {@code close()} would make shutdown wait on a
     * summarising call, whose only deadline is the transport's read timeout;
     * {@code shutdownNow()} would interrupt one.
     *
     * <p>The one more is that <b>interrupting a fold costs nothing worth
     * saving</b>. A fold writes nothing until its summary is in hand, and a fold
     * that dies before that leaves the log exactly as it found it — which is the
     * same state a failed summarising call leaves, and the state the next fold
     * repairs by covering the wider span. So there is nothing to drain and
     * nothing to wait for, and letting an in-flight fold finish if it can is
     * strictly better than stopping it. The threads are daemon by construction,
     * every virtual thread is, so one still summarising does not hold the JVM
     * open.
     */
    public void close() {
        if (owned != null) {
            owned.shutdown();
        }
    }

    /**
     * The conversation's side of one turn: what goes in front of it, and where
     * its measurement is kept.
     *
     * <p>Built by {@code Turn} at submission and used on the job's own thread.
     * Nothing happens when this is called — {@link TurnTranscript#before()}
     * reads the log when the run starts, and {@link
     * TurnTranscript#foldWhenTheTurnIsOver()} takes the fold decision when it
     * ends.
     *
     * <p><b>No budget, and its absence is the rule.</b> A fold used to spend one
     * call from the conversation's allowance and to decline when the allowance
     * was nearly out. Both were wrong in the same direction: an allowance is
     * what a person's turns spend from, and a fold is the machinery that keeps
     * their conversation sendable. Infrastructure that draws on an agent's
     * counter is infrastructure a person can be billed for, and — worse —
     * infrastructure that stops working precisely when a long conversation needs
     * it most.
     *
     * @param conversationId the conversation this turn is being spoken into
     * @param definition the agent answering. Its {@code model} is both the
     *     specifier whose context length bounds the history and the model that
     *     writes the summary, so that the summary is counted by the same
     *     tokenizer that will read it
     */
    /**
     * Open the conversation a run that is nobody's turn keeps its log in, and
     * hand back its transcript.
     *
     * <h2>What this is for</h2>
     *
     * <p>{@link #transcriptFor} is a turn's: the conversation already exists,
     * somebody opened it, and this is one more turn in it. This is every other
     * run's — a delegated child, a curator's ruling, a submission — and the
     * conversation does not exist until it is called. That is the whole of
     * "every run gets a conversation": one row, opened here, and everything
     * downstream is the machinery a turn already had.
     *
     * <p><b>The allowance is not this method's to hold.</b> A {@link
     * Origin#DELEGATION} spends its parent's {@code Budget} by reference and a
     * {@link Origin#CURATOR} ruling spends the pass's, so those two are written
     * with no budget at all; a {@link Origin#SUBMISSION} owns the one {@code
     * JobStore} built from the definition's {@code max-model-calls}. {@code
     * ConversationStore.log} refuses the wrong combination and {@code
     * conversations_an_allowance_is_owned_or_shared} refuses it again.
     *
     * <p><b>It never throws</b>, which is not this class's usual position and is
     * the right one here. Every caller is on a path where the alternative is
     * losing a run: {@code AgentRunTool} calls it from inside the parent's turn
     * loop, {@code Curator} from inside a pass, {@code JobStore} from a
     * submission that has already been accepted. A conversation that could not
     * be opened is a run with no log — which is exactly what every one of these
     * runs had before this change — so it is logged at {@code warn} and the run
     * goes on, degraded rather than lost.
     *
     * @param origin which door. Never {@link Origin#TURN}: a person's
     *     conversation is opened by {@code POST /v1/conversations} and reached
     *     through {@link #transcriptFor}
     * @param home the tier the run answers from
     * @param definition the agent whose run this is. Its name goes on the row
     *     and its model is what bounds the history
     * @param parent the conversation that delegated to this one, or {@code null}
     *     for a root
     * @param owned the allowance this conversation owns, or {@code null} when it
     *     spends one it does not
     * @return a transcript over the new conversation, or {@link
     *     Transcript#NONE} if one could not be opened
     */
    public Transcript logFor(
            Origin origin, Home home, AgentDefinition definition, String parent, Budget owned) {
        return logFor(origin, home, definition, parent, owned, Speaker.harness());
    }

    /**
     * {@link #logFor(Origin, Home, AgentDefinition, String, Budget)}, naming who speaks the
     * utterance the run opens with: a plain submission is a person's, every other run the
     * harness opens a log for is the harness's.
     */
    public Transcript logFor(Origin origin, Home home, AgentDefinition definition, String parent,
            Budget owned, Speaker speaker) {
        return logFor(origin, home, definition, parent, owned, speaker, null);
    }

    /**
     * {@link #logFor(Origin, Home, AgentDefinition, String, Budget, Speaker)}, naming the account
     * that owns the log (spec 2026-09-28-hooks-reach-the-log decision 8). A delegated child named
     * none inherits its parent's.
     *
     * <p><b>{@code log.open} fires here, once, before the transcript is handed back</b> (spec §3):
     * the row is committed and no turn has started, so an opening a hook fixes is on the row
     * before the transcript's first {@link Transcript#opening()} reads it and keeps it.
     */
    public Transcript logFor(Origin origin, Home home, AgentDefinition definition, String parent,
            Budget owned, Speaker speaker, String owner) {
        return logFor(origin, home, definition, parent, owned, speaker, owner, null);
    }

    /**
     * {@link #logFor(Origin, Home, AgentDefinition, String, Budget, Speaker, String)}, naming the
     * tool call that opened this child, so its row carries the one link back to it (spec
     * 2026-09-29 §2.3, §6).
     */
    public Transcript logFor(Origin origin, Home home, AgentDefinition definition, String parent,
            Budget owned, Speaker speaker, String owner, String openedBy) {
        return logFor(origin, home, definition, parent, owned, speaker, owner, openedBy, null);
    }

    /**
     * {@link #logFor(Origin, Home, AgentDefinition, String, Budget, Speaker, String, String)},
     * naming the session that asked for the run: the log is snapshotted with that session's
     * {@code .plowshare/hooks/} (spec 2026-09-30-local-hooks-are-served decision 4). A delegated
     * child names none; it inherits its parent's snapshot.
     */
    public Transcript logFor(Origin origin, Home home, AgentDefinition definition, String parent,
            Budget owned, Speaker speaker, String owner, String openedBy, String session) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(definition, "definition");
        if (conversations == null) {
            // A fixture, not a deployment. See the field.
            return Transcript.NONE;
        }
        String id;
        try {
            id = conversations.log(origin, home, definition.name(), parent, owned, owner,
                    openedBy).id();
        } catch (RuntimeException notOpened) {
            // First line only; see JobRuntime.describe. A constraint violation's
            // second line quotes the failing row.
            log.warn("a {} run of '{}' could not be given a conversation to log in, so it runs"
                            + " without one and nothing about it will be readable afterwards."
                            + " Reason: {}",
                    origin.wireName(), definition.name(), JobRuntime.describe(notOpened));
            return Transcript.NONE;
        }
        // Never throws: LogStages' contract. Guarded all the same, and apart from the row's
        // write: a stage that breaks the contract must not turn a committed row into NONE.
        try {
            logStages.opened(new LogStages.LogOpened(id, origin, home, definition.name(),
                    definition.bot(), parent, session,
                    // Decision 4: a delegated child carries its parent's snapshot, never re-reads.
                    origin == Origin.DELEGATION ? parent : null));
        } catch (RuntimeException contractBroken) {
            log.warn("log {}: log.open threw, so it opens with no additions. Reason: {}", id,
                    JobRuntime.describe(contractBroken));
        }
        // The budget it was written with, carried so that what the run
        // spends is written back onto the row that granted it. See
        // TurnTranscript.owned and TODO §11.
        return new TurnTranscript(id, definition, owned, speaker, origin);
    }

    /**
     * A person's conversation's side of one turn, naming who speaks it. <b>No overload leaves
     * the speaker out</b>: a transcript that named nobody records its utterance as a person's,
     * and a caller that must say who spoke cannot pick that up by omission.
     *
     * @param speaker who speaks this turn, or null for nobody named — which reads as a person
     */
    public TurnTranscript transcriptFor(
            String conversationId, AgentDefinition definition, Speaker speaker) {
        return new TurnTranscript(
                Objects.requireNonNull(conversationId, "conversationId"),
                Objects.requireNonNull(definition, "definition"),
                // No allowance held here, and that is not an omission: a
                // person's conversation is written back by Turn, which is the
                // only caller that can see the run's, the conversation's and the
                // definition's ceilings at once. A copy written from here would
                // be a second write of one turn's spending.
                null,
                speaker,
                // Read from the row: a machine log continued after AWAITING -- an approved
                // submission, a delegated child spoken to again -- is reached here and not
                // through logFor, and still closes when its run ends (spec §3).
                originOf(conversationId));
    }

    /**
     * The origin a log was opened with, or {@code null} when it cannot be read — which closes
     * nothing: a missed {@code log.close} is a smaller loss than a turn that fails to start.
     */
    private Origin originOf(String conversationId) {
        if (conversations == null) {
            return null;
        }
        try {
            return conversations.find(conversationId).map(ConversationRecord::origin).orElse(null);
        } catch (RuntimeException unread) {
            log.warn("conversation {}: its origin could not be read, so log.close will not fire"
                    + " for this run. Reason: {}", conversationId, JobRuntime.describe(unread));
            return null;
        }
    }

    /**
     * The conversation's side of a turn that is continuing a run which stopped:
     * the same turn in every respect but what it opens with.
     *
     * <h2>A wrapper, so that the ordinary path is not touched at all</h2>
     *
     * <p>{@link TurnTranscript} is what every normal turn runs on and its {@link
     * TurnTranscript#before()} was deliberately reduced to a pure read — two
     * reads and a projection, no model call on any path. A branch inside it
     * would have been the smaller diff and the worse shape: the ordinary path is
     * the one that must stay obvious, and a reader of {@code before()} would
     * have had to hold a case that fires for one caller in a thousand.
     *
     * <p>So the resumed turn <b>is</b> an ordinary turn, with one method
     * replaced. {@link ResumedTranscript} delegates every measurement, the
     * ordinal, the log write and the fold to a real {@link TurnTranscript}, and
     * answers {@code before()} out of {@link #whatWasSaidAndWhatTheRunLearned}
     * instead of {@link #whatWasSaidAndWhatCameBack}. Nothing about folding,
     * measuring or recording is duplicated, and nothing about them is special
     * for a resumed run — which is what makes "a resumed run is a new turn" true
     * in the code and not only in the design.
     *
     * @param conversationId the conversation whose run is being continued
     * @param definition the agent answering, on {@link #transcriptFor}'s terms
     * @param continuing the ordinal of the turn this run continues, which is the
     *     conversation's last. <b>Not the ordinal this turn takes</b>: it takes
     *     the next one, like any turn, and this is the one it reads whole
     */
    public ResumedTranscript resumedTranscriptFor(
            String conversationId, AgentDefinition definition, int continuing) {
        return new ResumedTranscript(
                new TurnTranscript(
                        Objects.requireNonNull(conversationId, "conversationId"),
                        Objects.requireNonNull(definition, "definition"),
                        // transcriptFor's reason exactly: a resumed run is a
                        // person's turn, and Turn writes its spending back.
                        null,
                        null,
                        // transcriptFor's, and for its reason.
                        originOf(conversationId)),
                continuing);
    }

    /**
     * A turn that continues a run which stopped: an ordinary turn, opening with
     * what that run learned.
     *
     * <p>Two methods differ from {@link TurnTranscript} and everything else is
     * delegation. {@link #before()} reads the log in the resumed shape; {@link
     * #record} files the run's opening message as the harness speaking, because
     * on this path it is. Both are argued where they are.
     */
    public final class ResumedTranscript implements Transcript {

        @Override public Integer continuingTurn() { return continuing; }

        @Override public Spoken spokenIn() { return turn.spokenIn(); }
        @Override public UsageAttribution usage() { return turn.usage(); }
        @Override public void accounted(UsageAttribution owner) {
            turn.accounted(owner);
        }


        private final TurnTranscript turn;
        private final int continuing;

        private ResumedTranscript(TurnTranscript turn, int continuing) {
            this.turn = turn;
            this.continuing = continuing;
        }

        /** {@inheritDoc} — the continued turn's: a resumed run writes to the same log. */
        @Override
        public Origin origin() {
            return turn.origin();
        }

        /**
         * {@inheritDoc}
         *
         * <p>Everything older than the turn being continued as any turn reads
         * it, and the turn being continued whole. {@link
         * #whatWasSaidAndWhatTheRunLearned} is the reading and carries the
         * argument for it.
         *
         * <p><b>The turn ordinal is settled here too</b>, from the turns already
         * recorded plus one, exactly as an ordinary turn settles it — so the
         * entries this turn writes are filed against the <em>new</em> turn and
         * never against the one it is continuing. The stopped turn's row is
         * already written, which is what makes that number the next one.
         *
         * <p><b>It makes no model call and never throws</b>, on {@link
         * TurnTranscript#before()}'s terms exactly. A resumed run whose history
         * could not be assembled is worse than an ordinary turn that loses its
         * history — it would re-do work somebody has already paid for — so this
         * is logged at {@code warn} and the run goes on with the note alone
         * rather than the grant being lost to an exception.
         */
        @Override
        public List<ChatMessage> before() {
            try {
                turn.turnOrdinal();
                return Projection.of(whatWasSaidAndWhatTheRunLearned(
                        entries.thatProjectFor(turn.conversationId), continuing,
                        turn.definition.canRedeem()), turn.definition.canList());
            } catch (RuntimeException failed) {
                // First line only; see JobRuntime.describe. A constraint
                // violation's second line quotes the failing row, and an
                // `entries` row holds whatever a tool read off a disk.
                log.warn("conversation {}: the run it is continuing could not be read back, so"
                                + " this run starts without what that one learned. Reason: {}",
                        turn.conversationId, JobRuntime.describe(failed));
                return List.of();
            }
        }

        /**
         * {@inheritDoc}
         *
         * <p><b>The message this run opens with is recorded as a {@code
         * runtime_note} and not as an utterance</b>, and that is the whole of
         * what this override does.
         *
         * <p>{@link JobRuntime} records its opening message as an {@link
         * EntryKind#UTTERANCE} because on every other path it is one — a person
         * said it. On this path nobody said anything: the utterance is already
         * in the log, on the turn that carried it, and what opens a resumed run
         * is the harness saying when the previous run stopped. Recording it as
         * an utterance would put the same question into the log twice and would
         * show a person's conversation as having two questions where they asked
         * one.
         *
         * <p>{@link EntryKind#RUNTIME_NOTE} is exactly the kind for it, and it
         * is the kind {@code JobRuntime.Repeats}' nudge already uses for the
         * same two reasons: it was <em>delivered</em> in the run and the model
         * read it, so it is recorded; and it must not project, because a later
         * turn shown it would be told about a resumption that is over.
         */
        @Override
        public void record(LoggedEntry entry) {
            turn.record(entry.kind() == EntryKind.UTTERANCE
                    ? LoggedEntry.runtimeNote(entry.content())
                    : entry);
        }

        @Override
        public void promptMeasured(int promptTokens) {
            turn.promptMeasured(promptTokens);
        }

        @Override
        public void answerMeasured(int completionTokens) {
            turn.answerMeasured(completionTokens);
        }

        /** {@inheritDoc}
         *
         * <p>{@link TurnTranscript#stepEnded} exactly: a resumed run is a turn, and a fold
         * inside it projects the same way on any later reading of the log (spec
         * 2026-09-30-fold-at-60-and-80 §2). The turn it continues sits in front of this run's
         * opening message, among the earlier turns such a fold may take. */
        @Override
        public int stepEnded(List<ChatMessage> history, int openedAt) {
            return turn.stepEnded(history, openedAt);
        }

        /**
         * {@inheritDoc}
         *
         * <p>{@link TurnTranscript#redeem} exactly, and the delegation is the
         * whole answer: a resumed run is in the same conversation, so it is
         * entitled to the same stored results and to no others.
         *
         * <p>A resumed run is also the one reading that already carries its
         * own turn's working in full — {@link #whatWasSaidAndWhatTheRunLearned}
         * declines the narrowing for the turn being continued — so what it has
         * left to redeem is what the turns <em>before</em> that one stored. Which
         * is the ordinary case, read from an unusual angle.
         */
        @Override
        public Optional<Redemption> redeem(UUID handle) {
            return turn.redeem(handle);
        }

        /**
         * {@inheritDoc}
         *
         * <p>{@link TurnTranscript#stored} exactly, and the delegation is the
         * whole answer for {@link #redeem}'s reason: a resumed run is in the same
         * conversation, so it is entitled to the same stored results and to no
         * others. What a fold covered is behind a seam for it as for anybody
         * else — the turn this run is continuing is the conversation's last, and
         * a fold reaches no further than the turn before it.
         */
        @Override
        public StoredResults stored(int skip, int most) {
            return turn.stored(skip, most);
        }

        /** What this turn's longest prompt cost, or null for a turn nothing
         *  measured. {@link TurnTranscript#promptTokens()} exactly. */
        public Integer promptTokens() {
            return turn.promptTokens();
        }

        /** The turn is over: take the fold decision, somewhere else. {@link
         *  TurnTranscript#foldWhenTheTurnIsOver()} exactly — a resumed turn is a
         *  turn, and the conversation it lengthened folds on the same rule as
         *  any other. */
        public void foldWhenTheTurnIsOver() {
            turn.foldWhenTheTurnIsOver();
        }

        /** {@inheritDoc}
         *
         * <p>{@link TurnTranscript#delegate} exactly, and the delegation is the
         * point: a child of a resumed run is a child of the same conversation,
         * because a resumed run is a new turn in it and not a new conversation.
         * A separate child tree per resumption would make the same delegated
         * work look like two unrelated runs. */
        @Override
        public Transcript delegate(AgentDefinition callee, Home home, String openedBy) {
            return turn.delegate(callee, home, openedBy);
        }

        /** {@inheritDoc}
         *
         * <p>{@link TurnTranscript#closed} exactly. The utterance written is the
         * stopped turn's own question, which {@code Turn.resume} passes and
         * argues for: {@code turns} is the human-readable transcript and the
         * question this turn answers <em>is</em> that one. */
        @Override
        public void closed(String utterance, Outcome outcome) {
            turn.closed(utterance, outcome);
        }

        @Override
        public boolean followsAFallback() {
            return turn.followsAFallback();
        }

        /** {@inheritDoc}
         *
         * <p>{@link TurnTranscript#conversationId()} exactly, and the delegation
         * is the point: a resumed run is a new turn in the same conversation, not
         * a new one, so the job it starts under names the same id every other
         * turn in it would. */
        @Override
        public String conversationId() {
            return turn.conversationId();
        }

        @Override
        public String opening() {
            return turn.opening();
        }
    }

    /** Tells {@code growth} whenever this class commits entries: a turn's close, a fold. */
    public void useGrowth(LogGrowth growth) {
        this.growth = Objects.requireNonNull(growth, "growth");
    }

    /** The log stages a log opens and closes through. See {@link #logStages}. */
    public void useLogStages(LogStages logStages) {
        this.logStages = Objects.requireNonNull(logStages, "logStages");
    }

    /** What counts a step's results inside a turn. See {@link #tokenizer}. */
    public void useTokenizer(Tokenizer tokenizer) {
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    }

    /**
     * Where a conversation on {@code definition}'s model folds: its context length, and what an
     * operator configured for it, laid over the defaults {@link FoldThresholds} derives from
     * that length — or empty for a model nothing serves, which has no bound worth inventing.
     *
     * <p>May open a connection, as {@link #contextLengthOf} may.
     */
    Optional<FoldThresholds> thresholdsFor(AgentDefinition definition) {
        OptionalInt bound = contextLengthOf(definition);
        if (bound.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(FoldThresholds.of(bound.getAsInt(),
                models.compactionThreshold(definition.model()),
                models.compactionNowThreshold(definition.model())));
    }

    /** A count, with a third again where it is an estimate. See {@link #HEADROOM_NUMERATOR}. */
    static long withHeadroom(TokenCount count) {
        if (count.isMeasured()) {
            return count.tokens();
        }
        return ((long) count.tokens() * HEADROOM_NUMERATOR + HEADROOM_DENOMINATOR - 1)
                / HEADROOM_DENOMINATOR;
    }

    /** One turn's transcript, and the one number the conversation takes back
     *  from it. */
    public final class TurnTranscript implements Transcript {

        private final String conversationId;
        private final AgentDefinition definition;

        /**
         * The largest prompt this turn has put in front of the model, or zero
         * for a turn nothing measured.
         *
         * <p>Volatile because it is written on the job's thread and read by
         * whatever files the turn afterwards — which is the same thread today,
         * inside {@code JobStore.finish}, and is not a thing this class should
         * have to depend on.
         */
        private volatile int longestPrompt;

        /**
         * The <b>first</b> prompt this turn put in front of the model, or zero
         * for a turn nothing measured.
         *
         * <p>Kept alongside {@link #longestPrompt} and never instead of it, and
         * the two answer different questions — see this class's javadoc. The
         * largest is what a turn had to fit inside, and it is what {@link
         * #promptTokens()} records. <b>The first is what the turn sent</b>: the
         * whole context plus the person's new question, before the turn put
         * anything of its own in front of the model, and therefore the number
         * the fold threshold is compared against.
         *
         * <p>The two together also bracket what the turn spent on its own
         * working — {@link #spentOnItsWorking()} is the subtraction — which is
         * reported and decides nothing.
         *
         * <p>Volatile for {@link #longestPrompt}'s reason exactly.
         */
        private volatile int firstPrompt;

        /**
         * What the model was charged for every generation of this turn, summed,
         * or zero when the endpoint said nothing.
         *
         * <p><b>The sum, which is what an agent that can redeem a reference
         * adds.</b> This used to be a single number because every generation but
         * the last was appended to the history the turn went on sending — so it
         * was already inside {@link #longestPrompt} — <em>and</em> because none
         * of those middle ones survived into a later turn, since {@link
         * #whatWasSaidAndWhatCameBack} dropped the answers carrying tool calls
         * along with the results they asked for.
         *
         * <p>That second half no longer holds for an agent that declares {@code
         * result_read}. A result is referenced for it, and a reference is a
         * {@code tool} message that is only well formed with its {@code
         * tool_calls} above it, so <b>every assistant message the turn produced
         * is in the next turn's prompt</b>. Each one was measured by the model's
         * own tokenizer, and their sum is exactly what those messages cost —
         * their text and the arguments of their calls together, since a generated
         * tool call is generated tokens like any other.
         *
         * <p><b>It still holds for an agent that cannot</b>, which is what {@link
         * #lastGeneration} is kept for. Both numbers are recorded on every turn
         * and {@link #added()} picks.
         *
         * <p>"Summing them would count the middle ones twice" was the old
         * argument and it was about {@link #longestPrompt}, which this is not
         * added to and never was. {@link #spentOnItsWorking()} is the quantity
         * that overlaps, and it reports rather than decides.
         *
         * <p><b>One writer.</b> It accumulates on the job's own thread, one call
         * at a time, and is read by whatever files the turn afterwards — {@link
         * #longestPrompt}'s arrangement exactly, and volatile for its reason.
         */
        private volatile int generated;

        /**
         * What the model was charged for this turn's <b>last</b> generation, or
         * zero when the endpoint said nothing.
         *
         * <p>Kept alongside {@link #generated} and never instead of it, because
         * which of the two a turn adds to its conversation is a property of the
         * <em>agent</em> and not of the turn. An agent that declares {@code
         * result_read} carries every assistant message of an earlier turn into
         * the next prompt, so the sum is what it added; an agent that does not
         * gets the old drop — every result and every answer but the last — so
         * the last generation alone is what survives, which is what this was
         * before references existed and is still exactly right there. {@link
         * #added()} is where the two are told apart.
         *
         * <p><b>Both are recorded on every turn and neither is conditional</b>,
         * so nothing about a measurement depends on the definition: the
         * arithmetic chooses, the instrument does not.
         *
         * <p>Volatile for {@link #generated}'s reason exactly.
         */
        private volatile int lastGeneration;

        /**
         * The <b>last</b> prompt this turn put in front of the model, or zero for a turn nothing
         * measured: what a fold inside the turn is decided from, together with {@link
         * #lastGeneration} and the results appended since. Neither the first nor the longest
         * answers that — the first is before the turn's working and the longest is before any
         * fold inside the turn shortened it. Volatile for {@link #longestPrompt}'s reason.
         */
        private volatile int lastPrompt;

        /**
         * Whether the next time the turn is found at or above the in-turn threshold a fold may
         * be tried. Cleared by the try, whatever it came to, and set again only once the turn is
         * found below the threshold — so a fold is tried at most once per crossing, and a
         * summariser that failed is not asked again at every step of a turn that stays over
         * (spec 2026-09-30-fold-at-60-and-80 §4). The job's own thread is the only one that
         * reads or writes it.
         */
        private boolean armed = true;

        /** Whether this turn has written down that a fold became due: once a turn is enough. */
        private boolean dueNoted;

        /** Whether {@link #within} has been asked for, and what it answered: once a turn, at
         *  its first step boundary, because the context length may open a connection. */
        private boolean withinAsked;

        private FoldThresholds within;

        /**
         * Which turn of the conversation this one is, or zero before anything
         * has asked.
         *
         * <p>Computed once, from the turns already recorded plus one, and kept —
         * because it must not move under a run. It is what every entry this turn
         * writes is filed against, and the turn's own row does not exist while
         * the turn is running, so re-deriving it after that row lands would file
         * the closing entries against the turn after this one.
         *
         * <p>Volatile for {@code longestPrompt}'s reason exactly: written on the
         * job's thread and read by whatever files the turn afterwards.
         */
        private volatile int turnOrdinal;

        private volatile UsageAttribution accountingOwner =
                UsageAttribution.LEGACY;

        @Override public UsageAttribution usage() {
            return accountingOwner;
        }

        @Override public synchronized void accounted(
                UsageAttribution owner) {
            java.util.Objects.requireNonNull(owner);
            if (accountingOwner.status() != UsageAttribution.Status.LEGACY_UNATTRIBUTED
                    && !accountingOwner.equals(owner)) {
                throw new IllegalStateException("a turn cannot change its accounting ownership");
            }
            accountingOwner = owner;
        }

        /** Resolve once for a standalone log, or reuse the runtime's already admitted snapshot. */
        private UsageAttribution foldUsage(AgentDefinition folder) {
            var owner = accountingOwner;
            if (owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED
                    && runUsage != RunUsage.NONE) {
                Home home = conversations.find(conversationId).orElseThrow().home();
                owner = runUsage.start(home, this, definition.name(), null).usage();
            }
            return owner.forOperation(UsageAttribution.Operation.FOLD,
                    folder.name());
        }

        /**
         * The allowance this conversation's own row holds, or {@code null} when
         * it spends one it does not own.
         *
         * <p><b>The discriminator is the schema's.</b> {@code
         * conversations_an_allowance_is_owned_or_shared} lets exactly the rows
         * that own a budget hold one, and this field is non-null for exactly
         * those: {@link #logFor} passes what it wrote, {@link #transcriptFor}
         * passes null because a person's conversation is written back by {@code
         * Turn} where the three ceilings are resolved. So a delegated child and
         * a curator's ruling carry null here <em>structurally</em> rather than
         * by a check in {@link #closed} — they were opened with no budget, so
         * there is nothing to pass.
         *
         * <p>Non-null implies {@link #conversations} is non-null, because {@link
         * #logFor} answers {@link Transcript#NONE} for a fixture with no store
         * and never reaches this constructor.
         */
        private final Budget owned;

        private final Speaker speaker;

        private final Origin origin;

        /** Read once per run and kept: a log's opening never changes once written. */
        private String opening;

        /** {@inheritDoc} */
        @Override
        public String opening() {
            String known = opening;
            if (known == null) {
                known = openingOf(conversationId);
                opening = known;
            }
            return known;
        }

        /**
         * @param origin the log's origin, which decides whether {@link #closed} fires {@code
         *     log.close}: for a machine log, never for a {@link Origin#TURN} or {@link
         *     Origin#ORCHESTRATION} log, which close on their own terms. {@code null} when it
         *     could not be read, which closes nothing
         */
        private TurnTranscript(String conversationId, AgentDefinition definition, Budget owned,
                Speaker speaker, Origin origin) {
            this.conversationId = conversationId;
            this.definition = definition;
            this.owned = owned;
            this.speaker = speaker;
            this.origin = origin;
        }

        /** {@inheritDoc} — whoever the door that built this transcript said is speaking. */
        @Override
        public Speaker speaker() {
            return speaker;
        }

        /** {@inheritDoc} — the origin this transcript was built with, which may be null. */
        @Override
        public Origin origin() {
            return origin;
        }

        /**
         * {@inheritDoc}
         *
         * <p>The field set in the constructor above, and nothing more — no
         * {@code turns} read, no ordinal, no possibility of throwing. This is
         * what makes it safe for {@code JobStore} to call the instant a job is
         * registered, before the run it is handing back a transcript for has
         * made a single call.
         */
        @Override
        public String conversationId() {
            return conversationId;
        }

        /**
         * {@inheritDoc}
         *
         * <p>Projects the log, and nothing else.
         *
         * <p><b>It makes no model call on any path, and that is the property to
         * keep.</b> This method used to fold first, on the job's own thread,
         * before the run could make its first real call — so a turn that tripped
         * the threshold cost a person two model calls end to end for one
         * question. The fold moved to {@link #foldWhenTheTurnIsOver()}; what is
         * left here is two reads and a projection. {@code
         * CompactionTest.a_turn_that_trips_the_threshold_makes_one_model_call_
         * and_does_not_wait_for_the_fold} is what fails if a call comes back.
         *
         * <p><b>The turn ordinal is settled here</b>, from the turns already
         * recorded plus one, because this is the one place that has already paid
         * for that read and because every entry the turn writes is filed against
         * it.
         *
         * <p><b>The decision and the history come from two different records,
         * and that is deliberate.</b> {@code turns} is asked whether to fold —
         * {@code prompt_tokens} is a fact about a request and its outcome and is
         * nowhere in {@code entries} — and {@code entries} is asked what the next
         * turn reads. Reading the log for the measurement would mean counting
         * rows, which is the estimate this class exists to refuse.
         *
         * <p><b>Where the two records can disagree, this now follows the log, and
         * that is a change worth naming.</b> A turn writes entries as it runs and
         * its {@code turns} row when it ends, so a run killed outright leaves
         * entries and no row — the case {@code V11__entries.sql} says the table
         * exists for. Reading {@code turns} meant such a turn vanished from the
         * conversation entirely, utterance included; reading the log means a
         * later turn sees it, ending inside whatever call it died in, with {@link
         * Projection#NEVER_COMPLETED} standing where the result would be. The
         * more honest of the two, and it is instrumented rather than assumed:
         * {@code CompactionTest.a_turn_that_never_wrote_its_row_is_still_in_the_
         * conversation_the_log_kept}.
         *
         * <p><b>The one place that still answers from {@code turns} alone is a
         * conversation with no turns at all</b>, which returns before reading the
         * log. A first turn opens empty as it always did, and the alternative —
         * a conversation whose <em>only</em> turn was killed, opening with that
         * turn's half-finished working and nothing else — is resumption. That is
         * built now and it does not reach here: it is {@link ResumedTranscript},
         * a different reading through a different door, and it refuses a
         * conversation with no {@code turns} row to continue. See {@code
         * implementation rationale} §7.
         *
         * <p><b>A seam comes back where the log put it and not at the front.</b>
         * The log is append-only, so a fold's summary sits after the entries it
         * covers; {@code JobRuntime.oneSystemMessageFirst} lifts it to index zero
         * and merges it with the agent's own prompt, which it already had to do
         * for this class's seam and which {@link Transcript#before()} names as
         * the shape a caller should expect.
         *
         * <p><b>Never throws</b>, per the contract. A conversation whose history
         * cannot be read is a turn that runs without it — worse than the
         * alternative in every way except the one that matters, which is that
         * the person still gets an answer and the conversation's budget is still
         * written back. It is logged at {@code warn}, because a turn that
         * silently forgot everything said before it is not an ordinary state.
         */
        @Override
        public List<ChatMessage> before() {
            try {
                List<TurnRecord> spoken = turns.forConversation(conversationId);
                // Settled here because this is the one place that has already
                // paid for the read.
                turnOrdinal = spoken.size() + 1;
                if (spoken.isEmpty()) {
                    return List.of();
                }
                return messages(conversationId, definition);
            } catch (RuntimeException failed) {
                // First line only; see JobRuntime.describe. A constraint
                // violation's second line quotes the failing row, and an
                // `entries` row holds whatever a tool read off a disk.
                log.warn("conversation {}: its history could not be assembled, so this turn runs"
                                + " without it. The turns themselves are untouched. Reason: {}",
                        conversationId, JobRuntime.describe(failed));
                return List.of();
            }
        }

        /**
         * {@inheritDoc}
         *
         * <p>The largest wins; see this class's javadoc for why it is the
         * largest and not the first.
         */
        @Override
        public void promptMeasured(int promptTokens) {
            if (promptTokens > longestPrompt) {
                longestPrompt = promptTokens;
            }
            if (firstPrompt == 0) {
                firstPrompt = promptTokens;
            }
            // The last, too: what a fold inside the turn is decided from, and the one that is
            // not the longest once such a fold has shortened the history.
            lastPrompt = promptTokens;
        }

        /**
         * {@inheritDoc}
         *
         * <p>They accumulate into {@link #generated} and the last is kept in
         * {@link #lastGeneration}. Both, on every turn: which of the two a turn
         * added depends on what the agent is shown of an earlier turn, and that
         * is {@link #added()}'s question rather than a measurement's.
         */
        @Override
        public void answerMeasured(int completionTokens) {
            generated += completionTokens;
            lastGeneration = completionTokens;
        }

        /**
         * {@inheritDoc}
         *
         * <p><b>Scoped by construction and again by the query.</b> This
         * transcript is one conversation's, and {@link EntryStore#redeem} is
         * handed that conversation's id — so a handle belonging to another one
         * answers empty here even though a UUID means it could not have been
         * constructed in the first place. Neither copy is redundant: the UUID is
         * what makes the request inexpressible, and this is what makes it refused
         * if it is ever expressed anyway.
         *
         * <p><b>A folded result still answers</b>, because {@code redeem} does
         * not filter {@code superseded_by} the way {@code thatProjectFor} does.
         * That single missing clause is the whole of the claim compaction now
         * makes: the summary is a view, the log is complete, and what is behind
         * a seam is still addressable.
         *
         * <p><b>Nothing is remembered.</b> A redeemed result is referenced again
         * on the next turn, and the reason is purity rather than thrash: {@link
         * Projection} is a function of the log, and a redemption that pinned a
         * result into later prompts would make it a function of what appeared in
         * an <em>earlier prompt</em> — which is nowhere in the log and could not
         * be recovered from one. So this method holds no state, and the fold
         * arithmetic keeps describing something a reader of the conversation can
         * reconstruct.
         *
         * <p><b>Never throws</b>, per the contract. A database that cannot be
         * reached becomes "that result is not here", which is the same answer a
         * handle the model invented gets — the honest one from this side, since
         * this cannot tell those apart either. It is logged at {@code warn}
         * because a conversation that cannot read its own log is not an ordinary
         * state.
         */
        @Override
        public Optional<Redemption> redeem(UUID handle) {
            try {
                // Redemption.of is where the two shapes of a row are told apart,
                // once: a payload still here, and one that has been ejected with
                // the account of when and where. Mapping to `content` alone --
                // which this line used to do -- turned the second into the
                // third answer, "nothing is stored at that address", which is
                // the sentence the retention design refuses.
                return entries.redeem(conversationId, handle).map(Redemption::of);
            } catch (RuntimeException failed) {
                // First line only; see JobRuntime.describe. A constraint
                // violation's second line quotes the failing row, and an
                // `entries` row holds whatever a tool read off a disk.
                log.warn("conversation {}: a stored result could not be read back, so the model"
                                + " is told there is none at that address. Reason: {}",
                        conversationId, JobRuntime.describe(failed));
                return Optional.empty();
            }
        }

        /**
         * {@inheritDoc}
         *
         * <p><b>The other half of {@link #redeem}, and it exists because a
         * handle outlives the line that carried it.</b> A fold supersedes the
         * reference along with the turn it belonged to, so the row stays
         * redeemable and stops being addressable. {@code
         * EntryStore.storedResultsBehindASeam} is the read that gives the
         * address back, and the scope is exactly the rows {@code thatProjectFor}
         * declines to fetch: what is <em>not</em> behind a seam is in the prompt
         * already, as its own reference line.
         *
         * <p><b>Scoped by construction and again by the query</b>, as {@link
         * #redeem} is, and the doubling matters more here: this is the read that
         * hands out addresses rather than the one that spends them.
         *
         * <p><b>Nothing is remembered</b>, for {@link #redeem}'s reason exactly.
         * A listing changes nothing about what the next projection shows.
         *
         * <p><b>Never throws</b>, per the contract. A database that cannot be
         * reached becomes "there is nothing behind a seam here", which is the
         * same answer a conversation nothing has folded gets — the honest one
         * from this side, since a model cannot act on the difference. Logged at
         * {@code warn} because a conversation that cannot read its own log is
         * not an ordinary state.
         */
        @Override
        public StoredResults stored(int skip, int most) {
            try {
                return entries.storedResultsBehindASeam(conversationId, skip, most);
            } catch (RuntimeException failed) {
                // First line only; see JobRuntime.describe.
                log.warn("conversation {}: its stored results could not be listed, so the model"
                                + " is told there are none behind its seams. Reason: {}",
                        conversationId, JobRuntime.describe(failed));
                return StoredResults.NONE;
            }
        }

        /**
         * What this turn added to the conversation, in tokens: the assistant
         * messages it produced that a later turn will carry.
         *
         * <h2>The decomposition, and what moved it</h2>
         *
         * <p>A turn sends the whole context — system prompt, whatever the
         * harness adds, and the history — plus the person's new question. That is
         * what was sent, and it is {@link #firstPrompt}. What the <em>next</em>
         * turn's prompt carries that this one's did not is what this measures.
         *
         * <h2>Which assistant messages those are is the agent's question, so
         * this asks it</h2>
         *
         * <p>It was the last generation alone, and the justification was exact:
         * the turn's other generations were the assistant messages carrying tool
         * calls, and {@link #whatWasSaidAndWhatCameBack} dropped every one of
         * them along with the results they asked for. That is still true <b>for
         * an agent that cannot redeem a reference</b>, which gets that drop
         * unchanged — so for one of those this is {@link #lastGeneration}, and
         * the old justification holds word for word.
         *
         * <p>For an agent that declares {@code result_read} it does not. A result
         * is carried as a reference, a reference is a {@code tool} message, and a
         * {@code tool} message with no {@code tool_calls} above it is a request
         * endpoints reject — so those assistant messages are reinstated and they
         * are in every later prompt. For one of those this is the sum.
         *
         * <p><b>So the condition here is the same condition the transcript
         * asked</b>, {@link AgentDefinition#canRedeem()}, and it has to be: this
         * number exists to say what a fold could later remove, and what a fold
         * could later remove is whatever the projection put there. Two sites
         * asking different questions would over-count for one kind of agent —
         * exactly the shape of the loops below — or under-count for the other,
         * which is the direction that ends in a refused request.
         *
         * <p>The sum is honest rather than a correction factor: <b>every addend
         * was counted by the model's own tokenizer</b>, and a generated tool call
         * is generated tokens, so the arguments a turn's calls carried are inside
         * these numbers already. That matters, because those arguments are the
         * part a naive estimate misses — {@code file_edit} sends a whole file as
         * one.
         *
         * <h2>What it still under-states, and why that is not repaired with a
         * constant</h2>
         *
         * <p>Two things are outside it. <b>The next utterance</b>, which has not
         * been said — the trade this class already took and argues for. <b>And
         * the reference lines themselves</b>, one per tool call an earlier turn
         * made, which no tokenizer here can measure: {@link #REFERENCE} is
         * counted in characters and there is no tokenizer on this box to turn
         * characters into tokens with. A constant would be a number arrived at by
         * feel wearing the authority of a limit, and this project deletes those.
         * <b>Only the redeeming agent under-states by that second term</b>; the
         * other carries no reference lines at all, so for it the term does not
         * exist.
         *
         * <p>What bounds the damage is that a reference is <em>bounded by
         * construction</em> — it names where the arguments are rather than
         * copying them, so it is the same short line whatever the tool was called
         * with — where the term this change actually repaired was unbounded and
         * grew with everything a turn asked for.
         *
         * <h2>What it must not become, which is the loop this class closed</h2>
         *
         * <p>It must count only what a fold can later remove. {@code
         * (longestPrompt - firstPrompt) + lastGeneration} is the shape that
         * failed, and it still would: that leading term counts a turn's tool
         * results at <b>full size</b>, and only a bounded reference to each one
         * survives — so it over-states by however large the results were, trips
         * the threshold on every turn that reads a file, and folds each time for
         * a quantity the fold cannot recover. It is still reported, as {@link
         * #spentOnItsWorking()}, and it still decides nothing. {@code
         * a_conversation_whose_turns_each_read_a_file_does_not_fold_on_every_turn}
         * and {@code a_conversation_whose_first_turn_read_a_huge_file_does_not_
         * fold_for_ever_after} are the two loops and are what fails if either
         * comes back. <b>Both run over both kinds of agent</b>, because both
         * readings have to satisfy this and a quantity that is right for one is
         * not automatically right for the other.
         *
         * <p>Every addend here passes that test on either path. An assistant
         * message from turn N is an entry filed against turn N, so a fold
         * covering N supersedes it and the next prompt is genuinely shorter —
         * and on the drop path the addend is one of those messages rather than
         * all of them, which is smaller still and therefore safe in the same
         * direction.
         *
         * <p><b>Measured fresh every turn, so it decays.</b> A conversation that
         * read something enormous and then calmed down is described by the turns
         * it is having rather than by the worst turn it ever had, and the number a
         * fold is decided from goes down when the conversation does.
         *
         * <p><b>It is zero for a turn nothing measured</b>, an endpoint that omits
         * {@code usage} being the ordinary way to arrive there, and zero is the
         * honest answer: a turn that added nothing measurable triggers nothing.
         *
         * <p><b>It is also the headroom</b>, which is not a second job so much as
         * the same one read forwards: what the next turn's prompt carries that
         * this one's did not <em>is</em> what this turn generated.
         *
         * <p><b>It is a fact about a run and lives only in memory.</b> This
         * transcript is the one place it exists, so the fold that reads it is the
         * fold at the end of the turn that produced it. What turns further back
         * added cannot be recovered at all — see {@link #THE_NUMBERS}. That is the
         * reason headroom could not have stayed a property of the conversation
         * even had the column been the right one: there is no series of past
         * answers to take a maximum over.
         */
        int added() {
            return definition.canRedeem() ? generated : lastGeneration;
        }

        /**
         * What this turn spent on its own working, in tokens: tool calls, tool
         * results and intermediate generations, all of it.
         *
         * <p>The two prompt measurements bracket it exactly — the first is what
         * the turn sent and the longest is what it had grown to by its last call
         * — so the difference is everything the turn put in front of the model
         * that nobody outside the turn ever asked for.
         *
         * <p><b>Reported and never triggered on.</b> It is genuinely useful: it
         * says what a turn actually cost, which is the question an operator asks
         * about a slow or expensive turn, and no column holds it. It is not
         * context growth and must never be read as any — {@link #added()} says
         * why at length, and {@link #THE_NUMBERS} names it as cost in the one
         * place it is written down.
         *
         * <p><b>{@link #longestPrompt} is the largest and not the last, and here
         * that is the safe reading rather than the exact one.</b> A turn's
         * prompts grow as its own tool results are appended, so the largest is
         * the last in every shape this runtime produces; if some future one ever
         * shortens a history mid-turn, the largest still bounds what was appended
         * from above and this over-states rather than under-states. Clamped at
         * zero all the same, because a reported cost below nothing is a number an
         * operator would have to decode.
         */
        int spentOnItsWorking() {
            int grew = longestPrompt - firstPrompt;
            return grew < 0 ? 0 : grew;
        }

        /**
         * What this turn's longest prompt cost, or {@code null} for a turn
         * nothing measured.
         *
         * <p>Null and never zero, which is the whole reason this method exists
         * rather than the field being read directly. {@code
         * turns_prompt_tokens_are_a_measurement} refuses a zero, because a
         * history measured at nought is one that never needs compacting: a
         * confidently wrong answer where the absence would have been a loud one.
         * An endpoint that omits {@code usage} entirely is the ordinary way to
         * arrive here with nothing.
         */
        public Integer promptTokens() {
            int longest = longestPrompt;
            return longest == 0 ? null : longest;
        }

        /**
         * {@inheritDoc}
         *
         * <p>One row per message, at the next place in this conversation's log.
         *
         * <p><b>Never throws</b>, per the contract, and this is the
         * implementation that makes the contract matter: it is called from
         * inside the turn loop and a database that blinked would otherwise end a
         * run at {@code UNAVAILABLE} for a reason nothing about the run
         * explains. What is kept when it fails is the answer, the turn, and the
         * spending.
         *
         * <p><b>What is lost is a message the next turn will not read</b>, which
         * is a heavier loss than it was: this log is now what {@link #before()}
         * projects, so a dropped {@code utterance} row is a question missing from
         * every later prompt rather than a gap in a record nothing consulted. It
         * is still not worth a run. The turn's own row still carries the
         * utterance and the answer — {@code turns} keeps both, and §4 of the
         * design says why it keeps them — so the fault is recoverable by hand and
         * is not silent.
         *
         * <p><b>It logs at {@code debug} and not at {@code warn}</b>, which is
         * the one place this class treats a failure more quietly than {@code
         * before()} does. A failing write here fails once per message rather
         * than once per turn, so a warning would be several lines per turn for
         * every run in the server for as long as the fault lasted;
         * {@code Turn.writeDown} still warns once, naming the conversation, when
         * the same database refuses the turn row.
         */
        @Override
        public boolean followsAFallback() {
            try {
                return entries.lastAnswerWasAFallback(conversationId, turnOrdinal());
            } catch (RuntimeException unread) {
                log.debug("conversation {}: who answered its last turn could not be read."
                        + " Reason: {}", conversationId, JobRuntime.describe(unread));
                return false;
            }
        }

        @Override
        public void record(LoggedEntry entry) {
            try {
                entries.append(conversationId, turnOrdinal(), entry);
            } catch (RuntimeException notRecorded) {
                // First line only; see JobRuntime.describe. A constraint
                // violation's second line quotes the failing row, and an
                // `entries` row holds whatever a tool read off a disk.
                log.debug("conversation {}: a {} entry could not be recorded. Reason: {}",
                        conversationId, entry.kind().wireName(),
                        JobRuntime.describe(notRecorded));
            }
        }

        /**
         * {@inheritDoc}
         *
         * <p>The ordinal is settled by {@link #before()} at the start of the
         * run, so asking here after the run is over reads the number every entry
         * of the turn was filed against.
         *
         * <p><b>Null rather than an exception for the run whose {@code before()}
         * could not read its own turns.</b> {@link #turnOrdinal()} falls back to
         * the database, and on a conversation store that is still unreachable
         * that read throws again — so an unguarded call here would take down the
         * caller. Every other method on {@link Transcript} promises not to
         * throw, and the one caller is {@code documents.Deliberation}, writing
         * citations for an answer three model calls have already been paid for.
         * A citation filed with no conversation is a citation that is harder to
         * find; a citation that costs the answer is a different order of loss.
         */
        @Override
        public Spoken spokenIn() {
            try {
                return new Spoken(conversationId, turnOrdinal());
            } catch (RuntimeException notKnown) {
                log.debug("conversation {}: which turn this is could not be read, so what it"
                                + " cited is filed without one. Reason: {}",
                        conversationId, JobRuntime.describe(notKnown));
                return null;
            }
        }

        /**
         * Which turn of the conversation this is.
         *
         * <p>{@link #before()} settles it, which is every run that starts. The
         * fallback is for the one that did not get that far — a read that threw
         * before the assignment — and it takes the same answer the same way. If
         * that read fails too the exception reaches {@link #record}, which
         * swallows it, and the entry is lost rather than the run.
         *
         * <p><b>{@link ResumedTranscript#before()} calls it deliberately rather
         * than falling back into it.</b> That method builds a different history
         * and so does not run this one's {@code before()} at all, and it still
         * needs the ordinal settled before the run starts writing — so it asks
         * for the number the same way an ordinary turn does. The answer is the
         * same because the stopped turn's row is already written: a resumed run
         * takes the <em>next</em> ordinal, which is the whole of what makes it a
         * new turn.
         */
        private int turnOrdinal() {
            int known = turnOrdinal;
            if (known != 0) {
                return known;
            }
            int next = turns.forConversation(conversationId).size() + 1;
            turnOrdinal = next;
            return next;
        }

        /**
         * {@inheritDoc}
         *
         * <h2>Where the turn stands, in tokens</h2>
         *
         * <p>What the next model call would send is what the last one sent — {@link
         * #lastPrompt}, the model's own count — plus what that call generated, which is now in
         * the history as the step's assistant message ({@link #lastGeneration}, the model's own
         * count, and an over-count by whatever it reasoned, which is the safe direction), plus
         * the step's results and any notes appended after it, which nothing has measured: those
         * are counted through {@link #tokenizer} with a third again for estimate error.
         *
         * <h2>Due, and now</h2>
         *
         * <p>At or above the due threshold the log says once that a fold became due, and
         * nothing else happens here: it is the end of the turn that decides whether it is still
         * needed. At or above the in-turn threshold the fold runs now — {@link
         * #foldWithinTheTurn} — once per crossing ({@link #armed}). Nothing about either is said
         * to the model; what it reads afterwards is the summary where the steps were.
         *
         * <p><b>An unmeasured turn decides nothing</b>, for {@link #foldIfItWouldNotFit}'s
         * reason: compacting on no evidence is the guessing this class exists to avoid.
         */
        @Override
        public int stepEnded(List<ChatMessage> history, int openedAt) {
            try {
                int measured = lastPrompt;
                if (measured == 0) {
                    return openedAt;
                }
                FoldThresholds at = withinTheTurn();
                if (at == null) {
                    return openedAt;
                }
                int generated = lastGeneration;
                int appended = counted(sinceTheLastAnswer(history));
                int standing = measured + generated + appended;
                String stood = STOOD_AT.formatted(standing, measured, generated, appended,
                        at.ceiling());
                if (at.due().isPresent() && standing >= at.due().getAsInt() && !dueNoted) {
                    dueNoted = true;
                    record(LoggedEntry.diagnostic(FOLD_DUE.formatted(
                            turnOrdinal(), at.due().getAsInt(), stood)));
                }
                if (standing < at.now()) {
                    armed = true;
                    return openedAt;
                }
                if (!armed) {
                    return openedAt;
                }
                armed = false;
                return foldWithinTheTurn(history, openedAt, at, standing, stood);
            } catch (RuntimeException failed) {
                // First line only; see JobRuntime.describe.
                log.warn("conversation {}: a fold inside the turn could not be decided, so the"
                                + " turn goes on with its whole history. Reason: {}",
                        conversationId, JobRuntime.describe(failed));
                return openedAt;
            }
        }

        /** The thresholds this turn folds by inside itself, or null for a model nothing serves;
         *  asked once. A lookup that throws is a turn that does not fold inside itself, said
         *  once rather than at every step. */
        private FoldThresholds withinTheTurn() {
            if (!withinAsked) {
                withinAsked = true;
                try {
                    within = thresholdsFor(definition).orElse(null);
                } catch (RuntimeException unknown) {
                    log.warn("conversation {}: where its model folds could not be asked, so no"
                                    + " fold runs inside this turn. Reason: {}",
                            conversationId, JobRuntime.describe(unknown));
                    within = null;
                }
            }
            return within;
        }

        /**
         * The fold inside the turn: the older steps of this turn, and any earlier turns no fold
         * stands for yet, into one summary where the steps were, keeping the turn's opening
         * request and its most recent whole steps (spec 2026-09-30-fold-at-60-and-80 §1).
         *
         * <h2>In this order, and the order is the design</h2>
         *
         * <ol>
         *   <li><b>Which steps are kept</b>, from the run's own list: the most recent whole
         *       steps — an assistant message with every result it asked for, never split — that
         *       fit in {@link #KEEP_PERCENT}% of the window, and at least {@link
         *       #KEEP_AT_LEAST}.
         *   <li><b>Which rows of the log those are.</b> The steps still standing in the log and
         *       in the list are the same steps in the same order — every fold removes the same
         *       prefix from both — so the k-th of one is the k-th of the other, and their calls
         *       are checked against each other. A log that disagrees refuses the fold: a summary
         *       pointed at rows it was not written from is the data loss {@link
         *       #theNumbersDisagree} refuses between turns.
         *   <li><b>One summarising call</b>, as the folder, with the span as data: the earlier
         *       turns as a between-turn fold renders them and the folded steps whole, with their
         *       results as the model read them — those are what the turn learned.
         *   <li><b>The log</b>, in one write ({@code EntryStore.foldWithinATurn}): the summary
         *       appended at this turn and everything it stands for pointed at it.
         *   <li><b>Then, and only then, the run's own list</b>, rewritten to match: the steps
         *       replaced by the summary where they were, and the earlier turns taken out from in
         *       front of the request. The log first, so the list never shows a fold the log
         *       does not hold.
         * </ol>
         *
         * <p><b>Every way this can fail leaves the turn running on its whole history</b> and a
         * diagnostic saying why; nothing is retried until the next crossing.
         *
         * @return where the opening request is now
         */
        private int foldWithinTheTurn(List<ChatMessage> history, int openedAt, FoldThresholds at,
                int standing, String stood) {
            int turn = turnOrdinal();
            List<Integer> steps = stepsIn(history, openedAt);
            int keptFrom = keptFrom(history, steps, at.ceiling());
            int since = entries.foldedThrough(conversationId);
            boolean earlier = since < turn - 1;
            if (keptFrom == 0 && !earlier) {
                record(LoggedEntry.diagnostic(NOT_FOLDED_IN_THE_TURN.formatted(turn, at.now(),
                        "nothing is older than the " + steps.size() + " steps it keeps whole, and"
                                + " no earlier turn is left to fold", stood)));
                return openedAt;
            }
            try {
                List<EntryRecord> shown = entries.thatProjectFor(conversationId);
                List<EntryRecord> asked = shown.stream()
                        .filter(entry -> entry.turnOrdinal() == turn
                                && entry.kind() == EntryKind.ANSWER
                                && !entry.toolCalls().isEmpty())
                        .toList();
                requireTheSameSteps(history, steps, asked);
                int from = keptFrom == 0 ? 1 : asked.get(0).ordinal();
                int through = keptFrom == 0 ? 0 : lastRowBefore(shown, turn, from,
                        asked.get(keptFrom).ordinal());
                boolean workSummarised = shown.stream().anyMatch(entry ->
                        entry.turnOrdinal() == turn && entry.kind() == EntryKind.TURN_SUMMARY);
                String written = summariseTheWork(since, turn, from, through, shown,
                        workSummarised);
                int spanned = (int) shown.stream().filter(entry ->
                        (entry.turnOrdinal() > since && entry.turnOrdinal() < turn)
                                || (entry.turnOrdinal() == turn && entry.ordinal() >= from
                                        && entry.ordinal() <= through
                                        && entry.kind() != EntryKind.TURN_SUMMARY))
                        .count();
                // FOLD.POST, as a between-turn fold asks it: once the folder has written and
                // before the fold is saved, bounded by one hook time limit and never stopping it.
                LogStages.Held held = foldPost(new Summarised(turn, spanned, standing, written));
                String summary = held.kept().isEmpty() ? written : written + "\n\n" + held.kept();
                entries.foldWithinATurn(conversationId, since, turn, from, through,
                        LoggedEntry.turnSummary(summary));
                // The log has it: now the run's own list, to match what the log projects.
                int first = steps.get(0);
                if (keptFrom > 0) {
                    history.subList(first, steps.get(keptFrom)).clear();
                }
                history.add(first, ChatMessage.user(
                        turnSeam(definition.canList(), since + 1, turn - 1, summary)));
                int opened = openedAt;
                if (earlier) {
                    int after = !history.isEmpty()
                            && history.get(0).role() == ChatMessage.Role.SYSTEM ? 1 : 0;
                    history.subList(after, openedAt).clear();
                    opened = after;
                }
                String took = earlier && keptFrom > 0
                        ? "turns " + (since + 1) + " to " + (turn - 1) + " and " + keptFrom
                                + " earlier steps of this turn were"
                        : earlier ? "turns " + (since + 1) + " to " + (turn - 1) + " were"
                                : keptFrom + " earlier steps of this turn were";
                record(LoggedEntry.diagnostic(FOLDED_IN_THE_TURN.formatted(turn, at.now(), took,
                        steps.size() - keptFrom, stood)));
                log.info("conversation {}: folded inside turn {} at about {} tokens against an"
                                + " in-turn threshold of {} and a context of {}: {} folded, the"
                                + " last {} steps kept.",
                        conversationId, turn, standing, at.now(), at.ceiling(), took,
                        steps.size() - keptFrom);
                toldTheFold(held);
                // Not on this thread: a learning pass is model calls of its own, and this turn is
                // waiting. On the folds' executor, as a between-turn fold's learner runs.
                try {
                    folds.execute(this::thereIsNewFoldedMaterial);
                } catch (RuntimeException notDispatched) {
                    log.debug("conversation {}: what its fold made invisible could not be"
                            + " offered to the learner. Reason: {}", conversationId,
                            JobRuntime.describe(notDispatched));
                }
                return opened;
            } catch (RuntimeException notFolded) {
                log.warn("conversation {}: a fold inside turn {} could not be made, so the turn"
                                + " goes on with its whole history. Reason: {}",
                        conversationId, turn, JobRuntime.describe(notFolded));
                record(LoggedEntry.diagnostic(NOT_FOLDED_IN_THE_TURN.formatted(turn, at.now(),
                        JobRuntime.describe(notFolded), stood)));
                return openedAt;
            }
        }

        /** Where each tool-call step of this turn starts in the run's own list: every assistant
         *  message that asked for a tool, after the opening request. A step runs to the next
         *  one's start, so a note or a held draft between two steps belongs to the earlier. */
        private static List<Integer> stepsIn(List<ChatMessage> history, int openedAt) {
            List<Integer> starts = new ArrayList<>();
            for (int at = openedAt + 1; at < history.size(); at++) {
                ChatMessage message = history.get(at);
                if (message.role() == ChatMessage.Role.ASSISTANT
                        && !message.toolCalls().isEmpty()) {
                    starts.add(at);
                }
            }
            return starts;
        }

        /**
         * How many of the steps, oldest first, are folded: everything before the most recent
         * whole steps that fit in {@link #KEEP_PERCENT}% of the window, never fewer than {@link
         * #KEEP_AT_LEAST} kept. Weighed through {@link #tokenizer}, with room for estimate
         * error; without one the weights are unknown and exactly the minimum is kept.
         */
        private int keptFrom(List<ChatMessage> history, List<Integer> steps, int ceiling) {
            int count = steps.size();
            if (count <= KEEP_AT_LEAST) {
                return 0;
            }
            if (tokenizer == null) {
                return count - KEEP_AT_LEAST;
            }
            long room = (long) ceiling * KEEP_PERCENT / 100;
            long spent = 0;
            int kept = 0;
            for (int step = count - 1; step >= 0; step--) {
                int end = step + 1 < count ? steps.get(step + 1) : history.size();
                int weight = counted(history.subList(steps.get(step), end));
                if (kept >= KEEP_AT_LEAST && spent + weight > room) {
                    return step + 1;
                }
                kept++;
                spent += weight;
            }
            return 0;
        }

        /** What was appended after the last assistant message: the step's results and notes,
         *  which is what no model has counted yet. */
        private static List<ChatMessage> sinceTheLastAnswer(List<ChatMessage> history) {
            for (int at = history.size() - 1; at >= 0; at--) {
                if (history.get(at).role() == ChatMessage.Role.ASSISTANT) {
                    return history.subList(at + 1, history.size());
                }
            }
            return List.of();
        }

        /** Messages, in tokens, through the tokenizer with room for estimate error; zero when
         *  there is no tokenizer to ask. The arguments of a call are counted with it, since a
         *  generated argument is sent back like any other text. */
        private int counted(List<ChatMessage> messages) {
            Tokenizer counting = tokenizer;
            if (counting == null) {
                return 0;
            }
            long total = 0;
            for (ChatMessage message : messages) {
                String content = message.content();
                if (content != null && !content.isEmpty()) {
                    total += withHeadroom(counting.count(content));
                }
                for (ToolCall call : message.toolCalls()) {
                    total += withHeadroom(counting.count(call.name() + " " + call.arguments()));
                }
            }
            return (int) Math.min(Integer.MAX_VALUE, total);
        }

        /** The steps the log holds for this turn are the steps the list holds, call for call. */
        private void requireTheSameSteps(List<ChatMessage> history, List<Integer> steps,
                List<EntryRecord> asked) {
            boolean same = asked.size() == steps.size();
            for (int step = 0; same && step < steps.size(); step++) {
                same = idsOf(history.get(steps.get(step)).toolCalls())
                        .equals(idsOf(asked.get(step).toolCalls()));
            }
            if (!same) {
                throw new IllegalStateException("the log holds " + asked.size() + " steps of this"
                        + " turn and the run holds " + steps.size() + ", or their calls differ, so"
                        + " a summary could not be pointed at the steps it would be written from");
            }
        }

        private static List<String> idsOf(List<ToolCall> calls) {
            return calls.stream().map(ToolCall::id).toList();
        }

        /** The last row of this turn's folded steps: the latest answer or result before the
         *  first kept step's answer. Anything unprojected after it — a hook's record of the last
         *  folded call, the kept step's thinking — is left as it is. */
        private static int lastRowBefore(List<EntryRecord> shown, int turn, int from, int kept) {
            int last = from;
            for (EntryRecord entry : shown) {
                if (entry.turnOrdinal() == turn && entry.ordinal() >= from
                        && entry.ordinal() < kept
                        && (entry.kind() == EntryKind.ANSWER
                                || entry.kind() == EntryKind.TOOL_RESULT)) {
                    last = Math.max(last, entry.ordinal());
                }
            }
            return last;
        }

        /**
         * One model call, as the folder, over the work so far: {@link #summarise}'s shape — the
         * folder's prompt in the system slot, the span as data in one user message — and on
         * nobody's allowance. Throws where {@link #summarise} answers null, so the reason
         * reaches the diagnostic.
         */
        private String summariseTheWork(int since, int turn, int from, int through,
                List<EntryRecord> shown, boolean workSummarised) {
            AgentDefinition folding = folder.get();
            List<ChatMessage> asked = List.of(
                    ChatMessage.system(folding.prompt()),
                    ChatMessage.user(askForATurnSummary(since, turn, workSummarised) + "\n\n"
                            + theWorkSoFar(since, turn, from, through, shown)));
            Completion written = models.complete(JobRuntime.requestFor(folding, asked).withAttribution(foldUsage(folding)));
            String summary = written.content() == null ? "" : written.content().strip();
            if (summary.isEmpty()) {
                throw new IllegalStateException("the summary came back empty");
            }
            return summary;
        }

        /**
         * The record a fold inside a turn reads: the earlier turns it takes, as a between-turn
         * fold renders them — or, where it takes none, the seams standing in front of them, for
         * context — and then this turn so far, headed as in progress: what it opened with, any
         * earlier summary of its own work, and the folded steps whole, results as the model
         * read them.
         */
        private String theWorkSoFar(int since, int turn, int from, int through,
                List<EntryRecord> shown) {
            StringBuilder span = new StringBuilder();
            if (since < turn - 1) {
                span.append(theSpan(since, turn - 1));
            } else {
                for (ChatMessage message : messages(conversationId, definition)) {
                    if (message.role() == ChatMessage.Role.SYSTEM) {
                        startABlock(span);
                        span.append(message.content()).append('\n');
                    }
                }
            }
            startABlock(span);
            span.append(A_TURN_IN_PROGRESS.formatted(turn)).append('\n');
            for (EntryRecord entry : shown) {
                if (entry.turnOrdinal() != turn) {
                    continue;
                }
                switch (entry.kind()) {
                    case UTTERANCE -> span.append(SAID).append(entry.content()).append('\n');
                    case NOTICE -> span.append(NOTICED).append(entry.content()).append('\n');
                    case TURN_SUMMARY ->
                            span.append(SUMMARISED).append(entry.content()).append('\n');
                    default -> { }
                }
            }
            for (EntryRecord entry : shown) {
                if (entry.turnOrdinal() != turn || entry.ordinal() < from
                        || entry.ordinal() > through) {
                    continue;
                }
                if (entry.kind() == EntryKind.ANSWER) {
                    span.append(answered(ChatMessage.assistant(
                            entry.content().isBlank() && entry.toolCalls().isEmpty()
                                    ? SAID_NOTHING : entry.content(),
                            entry.toolCalls()))).append('\n');
                } else if (entry.kind() == EntryKind.TOOL_RESULT) {
                    span.append(CAME_BACK)
                            .append(entry.content() == null ? "" : entry.content())
                            .append('\n');
                }
            }
            return span.toString().strip();
        }

        /**
         * The turn is over: take the fold decision, somewhere else.
         *
         * <h2>Here rather than at the start of the next turn</h2>
         *
         * <p>Called by {@code Turn} from the ending callback, <b>after</b> the
         * conversation's spending and the turn's row have been written. It has to
         * be after: the decision is taken from {@code turns}, and a turn that has
         * not been written into that table yet is a turn the decision cannot see
         * — the reach would stop one short and the measurement would be the
         * turn before this one's.
         *
         * <p><b>It must not throw and must not block, and the callback it is
         * called from is why.</b> That callback runs on the job's own thread
         * inside {@code JobStore.finish}, before the job reports DONE at all, so
         * neither the ENDED event nor a poll can carry a person to their next
         * utterance before it returns. Anything slow here is latency a person
         * pays between turns, and anything thrown here is a database's message in
         * a log line that reads as if the turn failed. So the whole of this
         * method is a claim on the conversation and a submission, and the {@code
         * catch} is wide because the alternative is a fold's plumbing surfacing
         * as a broken turn.
         *
         * <h2>One at a time, and a fold that finds one in flight is dropped</h2>
         *
         * <p>Not queued. A dropped fold costs nothing: the reach only moves
         * forward, so the next turn's fold covers everything this one would have
         * and one turn more. Queueing would buy a second summarising call for a
         * span the first one is already covering.
         *
         * <p>The claim is taken <b>before</b> the submission and released by the
         * fold itself, in a {@code finally}, so the window in which a
         * conversation is marked as folding and nothing is running is the width
         * of {@code Executor.execute} — and the {@code catch} below releases it
         * if even that failed, which is what a rejected submission after {@link
         * Compaction#close} looks like.
         */
        public void foldWhenTheTurnIsOver() {
            if (!folding.add(conversationId)) {
                log.debug("conversation {}: a fold is already in flight, so the one this turn"
                        + " would have started was dropped. The next turn's fold covers the"
                        + " same span and one turn more.", conversationId);
                return;
            }
            try {
                folds.execute(() -> {
                    try {
                        // A fold appends outside any turn: a summary, or a diagnostic about why
                        // there is none. Most folds decide there is nothing to fold, and a push
                        // for those would send every follower to read a log that did not grow.
                        if (foldIfItWouldNotFit()) {
                            told();
                        }
                    } finally {
                        folding.remove(conversationId);
                    }
                });
            } catch (RuntimeException notDispatched) {
                folding.remove(conversationId);
                // Debug and not warn. The ordinary way to arrive here is a
                // server shutting down, which rejects every submission it is
                // given, and one line per ending turn about a process that is
                // going away is noise in the last thing an operator reads.
                log.debug("conversation {}: its fold could not be started, so its history stands"
                                + " until the next turn ends. Reason: {}",
                        conversationId, JobRuntime.describe(notDispatched));
            }
        }

        /**
         * {@inheritDoc}
         *
         * <p>A child conversation, parented to this one, named for the agent
         * being called and holding no allowance of its own. {@link #logFor} is
         * the whole of it, and it never throws — a child that could not be given
         * a log runs without one, which is what every delegated run did before
         * this existed.
         *
         * <p><b>This is where the delegation guarantee becomes structural.</b>
         * The child writes into its own conversation, so {@link #before()} on
         * the parent projects the parent's log and could not reach the child's
         * if it tried. Nothing filters and nothing has to.
         */
        @Override
        public Transcript delegate(AgentDefinition callee, Home home, String openedBy) {
            return logFor(Origin.DELEGATION, home, callee, conversationId, null,
                    Speaker.harness(), null, openedBy);
        }

        /** A person's conversation closes on its lifecycle, an orchestration's on its finish, a
         *  seat's with its topic — never with one wake. */
        private static boolean closesWithItsRun(Origin origin) {
            return origin != null && origin != Origin.TURN && origin != Origin.ORCHESTRATION
                    && origin != Origin.BOARD;
        }

        /**
         * {@inheritDoc}
         *
         * <p>The three writes that close any run's stretch of a conversation,
         * in the order the reasons below require.
         *
         * <p><b>The two closing entries come first, and only for a run that did
         * not answer.</b> An {@code ANSWERED} run's answer was recorded by the
         * loop itself, where the {@code Completion} that produced it was still
         * in hand and could be timed, so recording it again here would put the
         * same sentence into the conversation twice. Every other ending has no
         * site inside the loop at all — the loop returns with what the run came
         * to rather than appending it — so this is the only place the last
         * message of a run can be written down. The {@code attempt_failed} row
         * beside it is what makes a stopped run legible when the {@code turns}
         * write below fails and its entries are the whole record of it.
         *
         * <p><b>What is written is {@code Outcome.text} and not the model's last
         * content</b>, which matters for every ending but {@code ANSWERED}: a
         * run that stopped carries the sentence the runtime wrote, naming the
         * ending and listing the tools it called, and that is exactly what
         * {@link #messages} puts in front of the next turn as this turn's
         * answer. Recording anything else would put two different answers to one
         * turn into two records of one conversation.
         *
         * <p><b>Then the row, carrying the agent.</b> {@code turns.agent} is
         * this transcript's own definition and never a parameter, which is the
         * same scoping {@link #redeem} rests on: a transcript is one agent's
         * stretch of one conversation, so there is no argument here for a caller
         * to get wrong. {@code POST /v1/conversations/&#123;id&#125;/resume}
         * reads that column back.
         *
         * <p><b>The measurement may be absent and is never a zero.</b> {@link
         * #promptTokens()} answers null for a run nothing measured — an endpoint
         * that omitted {@code usage}, or a run that never got a completion back
         * — and {@code turns_prompt_tokens_are_a_measurement} refuses the zero
         * that absence could be confused with.
         *
         * <p><b>The fold is last</b>, and after the row, because the fold
         * decision is taken from {@code turns} and the row it needs is the one
         * just written. It neither blocks nor throws.
         *
         * <p><b>A failed row write is logged and swallowed.</b> What is lost is
         * one row of a history; what is kept is the answer, the entries and —
         * for the one caller that has one — the spending. The fold still runs,
         * because a conversation whose history outgrew its window is not made
         * smaller by a row that failed to land.
         */
        @Override
        public void closed(String utterance, Outcome outcome) {
            writeBackWhatWasSpent();
            if (outcome.ending() == Outcome.Ending.ANSWERED) {
                // ANSWERED only, and the other branch is why: every other ending
                // puts the runtime's own sentence into `outcome.text()`, and a
                // citation read out of a harness sentence would be one the agent
                // never made. What is read here is the model's last content,
                // which `JobRuntime` already recorded as this turn's answer, so
                // the citation and the log are over the same bytes.
                //
                // The catch is for `turnOrdinal()` and not for the seam --
                // Citing's implementations must not throw and say so -- but that
                // read can go to the database on a turn whose `before()` failed,
                // and a conversation must not lose its turn row over a citation.
                // foldWhenTheTurnIsOver's wide catch, for its reason.
                try {
                    citing.whatTheAnswerCited(
                            definition, conversationId, turnOrdinal(), outcome.text());
                } catch (RuntimeException notRecorded) {
                    log.warn("conversation {}: what its answer cited could not be written"
                                    + " down. Reason: {}",
                            conversationId, JobRuntime.describe(notRecorded));
                }
            } else if (outcome.ending() == Outcome.Ending.AWAITING) {
                // A question put to a person, and not a failed attempt: the turn ended where it
                // meant to, so no attempt_failed goes with it. The question is recorded as the
                // turn's answer, untimed for the reason the branch below gives -- the loop records
                // no answer for this ending, since the text is a tool's and not a model's reply.
                record(LoggedEntry.answer(outcome.text(), List.of()));
            } else {
                record(LoggedEntry.attemptFailed(outcome.ending(), outcome.text()));
                // The runtime's own sentence, and it is deliberately untimed: it
                // names an ending and lists the tools the run called, and no
                // single model call produced it.
                // entries_only_a_completed_operation_is_timed would accept a
                // number here and there is none to give.
                record(LoggedEntry.answer(outcome.text(), List.of()));
            }
            try {
                turns.record(conversationId, utterance, outcome.text(), outcome.ending(),
                        promptTokens(), definition.name(), theBlockThisTurnWentOutWith());
            } catch (RuntimeException notWritten) {
                // JobRuntime.describe and not getMessage(): the first line only.
                // Postgres puts "Detail: Failing row contains (...)" on the
                // second, and for `turns` that row holds the utterance and the
                // answer.
                log.warn("conversation {}: its run ended {}, and could not be written into the"
                                + " transcript. Reason: {}",
                        conversationId, outcome.ending(), JobRuntime.describe(notWritten));
            }
            // AFTER THE TURN'S ENTRIES AND ITS ROW, AND BEFORE THE FOLD: every entry this turn
            // wrote is committed, and this runs on the job's own thread before JobStore.finish
            // publishes `ended` — so a client streaming this turn is told before it hears it end.
            told();
            foldWhenTheTurnIsOver();
            // log.close, last: a machine log takes no more turns once its run ends on anything
            // but AWAITING, which a person's answer continues (spec 2026-09-28-hooks-reach-the-log
            // §3). At most once per log, and never throws: LogStages' contract. Lower case, as every
            // ending on that wire is; and at this turn's ordinal, which the log cannot be asked for
            // — this turn's row is written and the conversation still reads as speaking (decision 7).
            if (closesWithItsRun(origin) && outcome.ending() != Outcome.Ending.AWAITING) {
                String ending = outcome.ending().name().toLowerCase(Locale.ROOT);
                int atTurn;
                try {
                    atTurn = turnOrdinal();
                } catch (RuntimeException unknown) {
                    // before() never settled it and the store still cannot say: the log's own read.
                    logStages.closed(conversationId, ending);
                    return;
                }
                logStages.closed(conversationId, ending, atTurn);
            }
        }

        /** The log's followers, told; a failure is never the turn's. */
        private void told() {
            try {
                growth.appended(conversationId);
            } catch (RuntimeException notTold) {
                log.debug("conversation {}: its followers could not be told the log grew."
                        + " Reason: {}", conversationId, JobRuntime.describe(notTold));
            }
        }

        /**
         * Store the system block this turn was sent, and answer with the name
         * the {@code turns} row will point at it by.
         *
         * <h2>Why the block is recorded at all</h2>
         *
         * <p>Everything else a projection carries is derived from the log and can
         * be reconstructed exactly. The block cannot: it is {@code
         * AgentDefinition.prompt}, which comes out of a file an operator edits
         * between turns, and until V32 nothing kept a copy. {@code
         * ConversationController.projection} therefore answered every past turn
         * with today's text and had no way to say it was doing so — a confident
         * wrong answer on the one screen whose purpose is auditing what happened.
         *
         * <h2>The definition's prompt and not the assembled system message</h2>
         *
         * <p>{@code JobRuntime.oneSystemMessageFirst} merges the agent's prompt
         * with any standing seam into one message, and it is the <em>merged</em>
         * text that goes on the wire. What is stored here is the prompt half
         * alone, for two reasons and neither of them is that a seam comes back
         * out of the archive unchanged. <b>The summary is already stored, and
         * once</b>: it is an entry, the log holds it authoritatively, and the
         * merged text would put a second copy of it in the same archive for the
         * reading to disagree with. <b>And the merged text is derived</b> —
         * freezing it stores the output of {@code oneSystemMessageFirst} beside
         * the inputs, where a change to that rule would leave old rows built by
         * the old one; re-merging on the read keeps one implementation of "the
         * system message is first and there is one of it".
         *
         * <p><b>What that leaves floating is the seam's wording, and it is not
         * the summary's.</b> {@code Projection.messageFor} builds a seam at read
         * time out of the summary entry, the span it covers and the definition's
         * {@code canList()} as it stands then — so an agent whose {@code tools:}
         * changed since renders a differently worded seam over the same stored
         * summary. That is named in {@code Compaction.projectionAsOf}, which is
         * where a reader meets it, and it is a gap this write does not close and
         * was never able to: what is version-controlled here is the prompt.
         *
         * <h2>It must never fail the turn, and that is why it is its own call</h2>
         *
         * <p><b>A conversation must not lose the record of what it said over the
         * record of what it was shown.</b> The turn is the work; this is the
         * audit trail beside it. So a refused block insert is logged and stepped
         * over exactly as a refused {@code turns.record} already is one method
         * up, and the turn is written with a null reference — which reads back as
         * "not recorded", which is true of it. Doing the hashing and the insert
         * inside {@code TurnStore.record} would have made a failure here take the
         * {@code turns} row with it.
         *
         * <p><b>A blank prompt is no block and not an empty one.</b> {@code
         * JobRuntime.oneSystemMessageFirst} sends no system message at all when
         * there is no system text, so a row holding the empty string would be the
         * record of a message that was never sent; {@code AgentRegistry} refuses
         * an agent with an empty body, so this is reachable only from a
         * hand-built definition, and the null is what is true of it either way.
         *
         * @return the block's SHA-256, or {@code null} when there was no block to
         *     store or it could not be stored
         */
        private String theBlockThisTurnWentOutWith() {
            // What was sent, which is the prompt and the log's opening (spec
            // 2026-09-28-hooks-reach-the-log decision 9, amendment 3), not the prompt alone.
            String sent = JobRuntime.systemText(definition.prompt(), opening());
            if (sent.isBlank()) {
                return null;
            }
            try {
                return turns.remember(sent);
            } catch (RuntimeException notStored) {
                // JobRuntime.describe and not getMessage(), for the reason the
                // catch around turns.record gives: Postgres puts the failing row
                // on the second line, and for `system_blocks` that row is the
                // whole prompt.
                log.warn("conversation {}: the system block it went out with could not be"
                                + " written down, so this turn will read back as not recorded."
                                + " Reason: {}",
                        conversationId, JobRuntime.describe(notStored));
                return null;
            }
        }

        /**
         * What the run spent, onto the row that granted it — for the
         * conversations whose row is the grant.
         *
         * <h2>The hole this closes</h2>
         *
         * <p>{@code implementation rationale} §11. A {@link Origin#SUBMISSION} owns its
         * allowance, and until this method existed nothing ever wrote the
         * spending back: {@code JobStore}'s submission closed its log and
         * stopped, and so did {@code documents.Summariser}'s cascade root. Every
         * such row said <em>nought of its total</em> however many calls the run
         * made. That was a defect nobody paid for while a submission was one run
         * of one agent; a document ingest is a submission with ~220 delegated
         * children under it, and its row is the only durable record that half an
         * hour of model calls happened.
         *
         * <h2>Why here and not at either call site</h2>
         *
         * <p>Because there are two of them and there will be more. {@code
         * JobStore.submit} and {@code Summariser.summarise} both open a
         * submission through {@link #logFor} and both closed it without this;
         * fixing the ingest alone would have left the plain submission wrong and
         * put the same three lines in a second place to be corrected once. What
         * they have in common is precisely the thing that decides it — <b>the
         * row owns the allowance</b> — so the write belongs to whatever holds
         * that fact, which is this transcript.
         *
         * <p><b>It is not a relaxation of the rule {@code Transcript.closed}
         * states.</b> That rule is that a run must not write a budget it does
         * not own, and it is kept structurally rather than by a test here:
         * {@link #owned} is null for a delegated child and for a curator's
         * ruling because those conversations were opened with no budget at all,
         * and null for a person's turn because {@code Turn} does that write
         * where the three ceilings are resolved. There is no path on which this
         * writes a second copy of a shared allowance.
         *
         * <h2>First, and it does not throw</h2>
         *
         * <p><b>Before the entries and before the {@code turns} row</b>, which
         * is {@code Turn}'s own ordering and its argument: both are the record
         * of one run, and if only one of them can land it must be this one. A
         * row whose spending was not written hands a resumption calls that have
         * already been made; a row missing a transcript line has a gap in a
         * history. One overspends and the other under-remembers.
         *
         * <p><b>A failed write is logged and swallowed</b>, on {@link
         * #closed}'s terms: this runs inside {@code JobStore.finish}, where a
         * throw would cost the ENDED event and the rest of the closing, and the
         * run really did finish.
         */
        private void writeBackWhatWasSpent() {
            if (owned == null) {
                return;
            }
            try {
                conversations.turnEnded(conversationId, owned);
            } catch (RuntimeException notWritten) {
                // JobRuntime.describe and not getMessage(), for the reason the
                // `turns` write below gives: the first line only.
                //
                // The allowance is rendered by Budget.toString and not by
                // spent() and limit(), because this line is inside the catch
                // that exists to swallow a failed write. limit() throws for a
                // budget with no ceiling, and a throw from in here would leave
                // the handler by the one exit this whole method is arranged to
                // keep closed, costing the ENDED event that the paragraph above
                // is protecting — a diagnostic that takes down the thing it was
                // diagnosing. toString is the accessor that reports both states
                // and is documented never to throw, for exactly this.
                log.warn("conversation {}: its run's spending could not be written onto its row,"
                                + " which therefore still understates what it cost. The"
                                + " allowance as it stands: {}. Reason: {}",
                        conversationId, owned, JobRuntime.describe(notWritten));
            }
        }

        /**
         * Fold the older turns away, if the context the last turn sent says the
         * next turn would not fit — or if the turn that just ended added enough
         * to be worth folding on its own.
         *
         * <p><b>"Would not fit" is the due threshold</b> ({@link FoldThresholds}):
         * a fold that became due while the turn worked runs here only if the
         * conversation is still over when the turn has returned, which is what
         * {@code sent + added} measures — a turn whose own working took it past
         * due and that returned small leaves nothing to fold (spec
         * 2026-09-30-fold-at-60-and-80 §1).
         *
         * <h2>The two triggers, and what each is for</h2>
         *
         * <p><b>What the turn sent plus what it added, against the fold
         * threshold</b>, is the safety trigger: {@code sent + added > foldAt}
         * asks whether the <em>next</em> turn would still fit, and it is the
         * condition that stops a conversation walking into a refusal. The
         * second addend is the headroom this class used to keep a separate
         * quantity for; the class javadoc says why the two collapsed into one.
         *
         * <p><b>It reads the turn's first prompt and not the turn's row, and
         * that is the correction this method turns on.</b> It used to read {@code
         * lastMeasured(spoken)} — the persisted {@code turns.prompt_tokens},
         * which is the turn's <em>longest</em> prompt. Within a turn, tool
         * results are in the prompt at full size; across turns at most a bounded
         * reference to each one is, because {@link #whatWasSaidAndWhatCameBack}
         * substitutes or drops before anything is projected. So the trigger was comparing
         * a number against {@code foldAt} far larger than any fold could bring it
         * down to, and a conversation reading a large file every turn folded
         * every turn, for ever, recovering almost nothing.
         * The first prompt is the history plus the question — what was actually
         * sent — and a fold shortens it.
         *
         * <p><b>The same number is in both conditions, and it is asked two
         * different questions.</b> Against {@code foldAt} it is added to what
         * was sent and asks whether the conversation still fits; against {@code
         * spanAt} it stands alone and asks whether this one turn is worth
         * folding for. One quantity can answer both because it is what a turn
         * produces that a later prompt carries in full — see {@link #added()} —
         * and neither reading has anything to do with the other's threshold.
         *
         * <p><b>What the turn added, against a span threshold</b>, is the
         * efficiency trigger, and it fires on a case the first cannot see. The
         * safety trigger is evaluated on the context this turn <em>sent</em>,
         * which does not include the answer this turn has just produced; that
         * answer only appears in the sum one turn later. So a single very large
         * answer would otherwise be answered against once before anything
         * noticed, and the measured cost of a turn rises with the standing
         * history it answers against — {@link #SPAN_FRACTION}. This
         * closes that one-turn lag, and the fold is free of the turn now that it
         * neither blocks one nor is bought from anybody's allowance.
         *
         * <p><b>It is why no minimum span is needed, and the reasoning survives
         * the correction intact.</b> The worry a threshold-only trigger raises is
         * a fold that covers one turn, on every turn, each adding a seam. What a
         * turn added is a property of that one turn rather than of the
         * conversation, so a small turn after a fold adds little and does not
         * fire this trigger however recently the last fold ran — which is the
         * guard a minimum number of turns would have been a proxy for, expressed
         * in the unit the constraint is actually in.
         *
         * <h2>Every early return is a reason not to fold</h2>
         *
         * <p>Each is a state this conversation is legitimately in rather than a
         * failure:
         *
         * <ul>
         *   <li><b>this turn was not measured.</b> An endpoint that omits {@code
         *       usage} says nothing about what the turn sent, and compacting on
         *       no evidence is the guessing this class exists to avoid. <b>It is
         *       this turn and no longer this conversation</b>, which is narrower
         *       than the read it replaces: the decision is about the context the
         *       turn that just ended put in front of the model, and an older
         *       turn's measurement is not that. A conversation whose endpoint
         *       stops reporting {@code usage} therefore stops folding, which is
         *       the same answer this branch has always given for one that never
         *       reported any;
         *   <li><b>no context length</b>, which now means one thing only:
         *       <em>nothing serves this model</em>. It used to mean "nobody
         *       configured a length and no provider could discover one", and
         *       that was a silent off-switch on this whole mechanism — a
         *       deployment whose node would not answer never folded, kept
         *       working, and said so once at boot. {@link
         *       LlmDispatcher#contextLength(String, int)} now falls back to
         *       {@link #defaultContextLength} for that case, and answers empty
         *       only for a specifier no pool serves. There is still no bound
         *       worth inventing there: the conversation's next call raises
         *       {@code UnknownSpecifierException} naming every pool, which is a
         *       better report than a fold could give. The guard stays for it,
         *       and stays true even when a threshold <em>is</em> configured: the
         *       ceiling is what makes a threshold safe, and a threshold with
         *       nothing above it is a number nobody can check;
         *   <li><b>it still fits and this turn was ordinary</b>, which is the
         *       common case;
         *   <li><b>one turn only</b>, so there is nothing older than the
         *       exchange the person is still in;
         *   <li><b>already folded this far</b>, which is what a conversation
         *       past its bound with no new turns to add would otherwise do on
         *       every utterance — pay for the same summary again. Asked of {@code
         *       compactions} and of the log: a fold inside a turn moves the log's
         *       reach without a {@code compactions} row, and one that took every
         *       earlier turn leaves the end of its turn nothing to take;
         *   <li><b>no due threshold</b>: a window of 64K or less that nobody
         *       configured one for, which folds only inside a turn — see {@link
         *       FoldThresholds}.
         * </ul>
         *
         * <p><b>Nothing left to spend is no longer one of them.</b> A fold does
         * not draw on the conversation's allowance, so a conversation with one
         * call left folds exactly like one with a hundred; see {@link
         * #transcriptFor}.
         *
         * <p><b>It runs on a fold's own thread and never throws into a
         * caller</b>, because there is no caller left to throw into — the turn
         * that started it has ended. What a failure costs is one over-long prompt
         * on the next turn, which the fold after it repairs by covering the wider
         * span.
         *
         * @return whether it wrote to the log, or began to — what decides whether
         *     the log's followers are told it grew
         */
        private boolean foldIfItWouldNotFit() {
            // Set before the first write to the log and not after it: a failure part-way still
            // tells the followers, since a push for nothing new costs one read and a push
            // missed costs an entry nobody shows.
            boolean wrote = false;
            try {
                // What this turn sent, which is the context and the question and
                // nothing the turn then did. Zero is a turn no endpoint measured.
                int sent = firstPrompt;
                if (sent == 0) {
                    return false;
                }
                // Kept, and no longer reachable by a model that is merely
                // unmeasurable: the dispatcher's third tier answers for those
                // now. What still reaches this line is a specifier NOTHING
                // SERVES, which the dispatcher answers empty for on purpose --
                // a conversation whose next call is going to raise
                // UnknownSpecifierException has no bound worth inventing, and
                // the run itself is what reports the name.
                Optional<FoldThresholds> known = thresholdsFor(definition);
                if (known.isEmpty()) {
                    return false;
                }
                FoldThresholds at = known.get();
                // No due threshold: a window of 64K or less that nobody configured
                // one for, which keeps what little room it has rather than spend it
                // on an early fold. The fold inside a turn is the only one it gets.
                if (at.due().isEmpty()) {
                    return false;
                }
                OptionalInt bound = OptionalInt.of(at.ceiling());
                // One number in both conditions, asked two questions. See this
                // method's javadoc, and `added()` for what it measures -- every
                // assistant message the turn generated, which is what a later
                // turn carries of it now that its results are referenced rather
                // than dropped.
                int added = added();
                int foldAt = at.due().getAsInt();
                int spanAt = foldASpanAt(foldAt);
                if (sent + added <= foldAt && added <= spanAt) {
                    return false;
                }
                // Read after the decision and no longer before it. It used to
                // feed headroom as well as the reach, so every ending turn in
                // the server paid for it; the reach is the only thing left that
                // needs it, and a turn that is not folding does not need one.
                List<TurnRecord> spoken = turns.forConversation(conversationId);
                // A conversation with no turns at all cannot happen here -- the
                // turn that started this fold wrote its own row before the
                // ending callback ran -- but a `turns` read that came back empty
                // would index out of bounds rather than decline, and this method
                // is the one place a fold can still decide nothing safely.
                int through = spoken.isEmpty() ? 0 : spoken.get(spoken.size() - 1).ordinal() - 1;
                if (through < 1) {
                    log.warn("conversation {}: its one turn already sent {} tokens against a"
                                    + " fold threshold of {} and a context of {}, and a compaction"
                                    + " can only fold turns older than the current exchange."
                                    + " Nothing was compacted.",
                            conversationId, sent, foldAt, bound.getAsInt());
                    return false;
                }
                Optional<CompactionRecord> already = compactions.latest(conversationId);
                if (already.isPresent() && already.get().throughOrdinal() >= through) {
                    return false;
                }
                // And the log's own reach, which a fold inside a turn moves without a
                // compactions row: one that took every earlier turn has already folded
                // this far, and a second summary of nothing would be refused one layer
                // down anyway.
                if (entries.foldedThrough(conversationId) >= through) {
                    return false;
                }
                String numbers = THE_NUMBERS.formatted(
                        sent, added, foldAt, spanAt, spentOnItsWorking());
                String written = summarise(through);
                if (written == null) {
                    wrote = true;
                    record(LoggedEntry.diagnostic(NOT_COMPACTED.formatted(through, numbers)));
                    return true;
                }
                // FOLD.POST (spec 2026-09-28-hooks-reach-the-log §3, amended 2026-09-29), once the
                // folder has written and before the fold is saved: a hook reads the summary, so it
                // keeps a marker only when the folder lost it, and what it keeps follows the
                // folder's own words verbatim. On this thread and bounded by one hook time limit;
                // it never stops the fold. Its notices wait until the log has the fold.
                LogStages.Held held = heldByHooks(through, sent + added, written);
                // Both records of the fold carry the same text: the compactions row and the
                // summary entry never disagree about what the fold said.
                String summary = held.kept().isEmpty() ? written : written + "\n\n" + held.kept();
                compactions.record(conversationId, through, summary);
                wrote = true;
                int since = foldTheLog(through, summary);
                record(LoggedEntry.diagnostic(since < 0
                        ? NOT_COMPACTED.formatted(through, numbers)
                        : COMPACTED.formatted(since + 1, through, numbers)));
                // Through, and no longer "turns 1 to". A fold covers the span
                // since the last one, and this line does not have the lower bound:
                // the two places that need it read it for themselves, each inside
                // the guard belonging to the step it is for. The reach is what both
                // records of the fold hold anyway -- compactions.through_ordinal and
                // the summary entry's turn ordinal -- so it is the number an
                // operator can join this line to.
                log.info("conversation {}: its history is summarised through turn {}. Its last"
                                + " turn sent {} tokens and added {}, spending a further {} on"
                                + " working that a later turn carries only as references; it"
                                + " folds at {}, a span folds at {}, and the model is loaded at"
                                + " {}.",
                        conversationId, through, sent, added, spentOnItsWorking(),
                        foldAt, spanAt, bound.getAsInt());
                // AFTER the fold has committed, and only for a fold the log
                // actually got -- `since < 0` is a summary the entries could not
                // be pointed at, so nothing became invisible and there is
                // nothing new to learn from. See `thereIsNewFoldedMaterial`,
                // which owns the whole of why this line is here and not one
                // statement earlier.
                if (since >= 0) {
                    // fold.post's held notices, after the fold committed and its diagnostic is
                    // written, and before the learner: nobody hears of a fold the log never got.
                    toldTheFold(held);
                    thereIsNewFoldedMaterial();
                }
                return true;
            } catch (RuntimeException notFolded) {
                // First line only; see JobRuntime.describe. A constraint
                // violation's second line quotes the failing row, and an
                // `entries` row holds whatever a tool read off a disk.
                log.warn("conversation {}: its history could not be folded, so the next turn runs"
                                + " with all of it and the fold after that covers the wider span."
                                + " Reason: {}",
                        conversationId, JobRuntime.describe(notFolded));
                return wrote;
            }
        }

        /**
         * Tell whatever is listening that a fold has just taken a span out of
         * this conversation's view.
         *
         * <h2>It must not endanger the fold, and the ordering is the whole of
         * how</h2>
         *
         * <p>Compaction is dial-tone — the owner has been explicit about it —
         * and learning is another model call with its own failure surface. So
         * <b>the fold commits first</b>: the summary is written, the entries are
         * pointed at it, the {@code compactions} row is in, the diagnostic is
         * recorded and the line is logged, and {@code fold.post}'s notices sent, all before
         * this is called. A learner that fails, hangs to its own budget, or
         * throws a database exception
         * cannot cost this conversation the fold it has already had, because
         * there is nothing left of the fold to lose.
         *
         * <p><b>In the same async task and not a new one.</b> This runs on the
         * fold's own thread, which is a virtual thread of {@link #folds} with
         * nothing waiting on it — the turn that started the fold ended before it
         * began. A second executor would buy nothing and would need its own
         * shutdown story; a fold that now takes longer is a fold nobody is
         * timing.
         *
         * <p><b>It says only that there is material, and deliberately not which.</b>
         * The window is computed by the system from the whole queue, so a tree
         * marked for ejection can overtake this conversation; a call that named
         * this conversation would be a fold quietly choosing what gets mined. See
         * {@link Learning} and {@code learner.LearningWindow}.
         *
         * <p><b>The {@code catch} is wide and the log line is {@code debug}.</b>
         * {@link Learning} says an implementation must not throw, and this is
         * what happens when one does anyway: the fold that has already succeeded
         * is not turned into a warning about somebody else's plumbing. A learner
         * that could not run costs a wider window next time and nothing else.
         */
        private void thereIsNewFoldedMaterial() {
            try {
                learning.thereIsMaterial();
            } catch (RuntimeException notLearned) {
                log.debug("conversation {}: its fold stands, and what was told about it could"
                                + " not act. Reason: {}",
                        conversationId, JobRuntime.describe(notLearned));
            }
        }

        /**
         * {@code fold.post}'s kept text and held notices, or {@link LogStages.Held#NOTHING} (spec
         * 2026-09-28-hooks-reach-the-log §3, amended 2026-09-29). The event names the span's
         * projecting entries, so this reads them once, before the fold points them at its
         * summary; a read that fails skips the stage — it fails open, and a fold is dial-tone.
         *
         * @param estimatedTokens what the ending turn sent and added, as measured
         * @param written the folder's own summary, which the hooks see
         */
        private LogStages.Held heldByHooks(int through, int estimatedTokens, String written) {
            try {
                int since = entries.foldedThrough(conversationId);
                int spanned = (int) entries.thatProjectFor(conversationId).stream()
                        .filter(entry -> entry.turnOrdinal() > since
                                && entry.turnOrdinal() <= through)
                        .count();
                return foldPost(new Summarised(through, spanned, estimatedTokens, written));
            } catch (RuntimeException notAsked) {
                log.warn("conversation {}: fold.post could not be asked, so the fold keeps"
                        + " nothing of the hooks' and tells nobody. Reason: {}", conversationId,
                        JobRuntime.describe(notAsked));
                return LogStages.Held.NOTHING;
            }
        }

        /** {@code fold.post}, asked about one fold whichever kind it is; fails open. A fold inside
         *  a turn names the turn it reached into as its reach. */
        private LogStages.Held foldPost(Summarised summarised) {
            try {
                LogStages.Held held = logStages.foldPost(conversationId, turnOrdinal(), summarised);
                // Null is outside LogStages' contract, and it must not become a fold that failed.
                return held == null ? LogStages.Held.NOTHING : held;
            } catch (RuntimeException notAsked) {
                log.warn("conversation {}: fold.post could not be asked, so the fold keeps"
                        + " nothing of the hooks' and tells nobody. Reason: {}", conversationId,
                        JobRuntime.describe(notAsked));
                return LogStages.Held.NOTHING;
            }
        }

        /** {@code fold.post}'s held notices, guarded: the fold has committed; a lost one says so. */
        private void toldTheFold(LogStages.Held held) {
            try {
                held.tell().run();
            } catch (RuntimeException notTold) {
                log.warn("conversation {}: its fold stands, and fold.post's notices could not be"
                        + " sent. Reason: {}", conversationId, JobRuntime.describe(notTold));
            }
        }

        /**
         * The same fold, said in the log: a summary appended, and the entries it
         * stands for marked as covered by it.
         *
         * <h2>A fold is an append and the log never loses anything</h2>
         *
         * <p>The turns behind a seam are still in {@code turns} at their own
         * ordinals — that is what {@code compactions} has always promised — and
         * the entries behind it are still in {@code entries} at theirs, with
         * their text unchanged. What changes is one column on each of them,
         * written once, saying which summary covers it; {@code Projection} skips
         * a covered entry and renders the summary in its place. Nothing is
         * deleted and no entry is edited.
         *
         * <p><b>The summary entry carries the reach as its own turn ordinal</b>,
         * which is the same number {@code compactions.through_ordinal} holds for
         * the same fold. The projection needs it there because the seam sentence
         * names it, and {@code EntryStore.supersede} needs the summary's own
         * ordinal so that the row does not fold itself away.
         *
         * <h2>A fold covers the span since the last fold, and the last fold
         * stays live</h2>
         *
         * <p>It used to cover everything from turn one, the previous summary
         * entry included, so each fold replaced the one before it. Now it covers
         * {@code (since, through]} — where {@code since} is how far the log is
         * already folded — and the previous summary, whose turn ordinal is
         * exactly {@code since}, falls outside it and goes on projecting.
         *
         * <p><b>Two things that buys, and the second is measured.</b> Each span
         * of raw turns is summarised once instead of being carried through
         * successive summaries-of-summaries. And the <em>conversation's</em>
         * prompt prefix {@code system + summary₁} is byte-identical either side
         * of the fold, which is the only shape an endpoint that reuses work
         * credits — it credits a prompt that <em>extends</em> a sequence it holds
         * and gives no partial credit for a shared prefix otherwise. {@code
         * implementation rationale} §3 is where
         * that was measured: 2.50 s against 57.97 s on one prompt, a factor of
         * 23, between extending a held sequence and presenting a new one that
         * merely opens the same way.
         *
         * <p><b>That is a saving for the turns after this fold, and it is no
         * longer one the fold itself takes.</b> {@link #summarise} used to be the
         * conversation's own prompt with an instruction on the end and cited this
         * same fact for itself; it sends the folder's prompt and the span as data
         * now, and records what giving that up was priced at. The prefix
         * argument survives here because it was always about what the
         * conversation sends next, which an incremental fold protects and a
         * summary-of-summaries did not.
         *
         * <p><b>{@code since} is read from the log and not from {@code
         * compactions}.</b> The two can disagree — this method logs and swallows,
         * so a refused summary entry leaves a {@code compactions} row claiming a
         * reach the log never got — and the log is the one the projection
         * answers from. {@code EntryStore.foldedThrough} argues it at length.
         *
         * <p><b>The append and the supersede are one write, and they have to
         * be.</b> They were two statements here, ordered summary-first on the
         * argument that a failure between them left "a summary nothing points at
         * — redundant and readable", repaired on the next turn. <b>Both halves
         * of that were false.</b> {@code EntryStore.foldedThrough} reads the
         * newest standing summary, and an orphan is one: the log then reports
         * itself folded through {@code through} while every raw turn still
         * projects, so the next fold's lower bound is {@code through}, {@code
         * theSpan} finds the projection opening at turn one instead of at {@code
         * since + 1}, and it refuses — correctly, and for ever, because nothing
         * moves the two numbers back into agreement. The guard below reads
         * {@code compactions} rather than the log, so it does not rescue it
         * either. That is a conversation that never folds again, one WARN a turn,
         * until the endpoint refuses the prompt. {@link EntryStore#fold} is where
         * the boundary lives and carries the rejected alternative; the
         * summary-first ordering is kept inside it, where it still matters.
         *
         * <p><b>Failure here does not fail the fold, and it is the log that
         * answers the next turn.</b> A fold that could not be written down is a
         * turn that runs with the whole pre-compaction history — the state the
         * conversation was in a moment ago, and the state it would have been left
         * in by any other failure on this path. It costs a prompt that is longer
         * than it should be; it does not cost the conversation anything it said.
         *
         * <p><b>It repairs itself on the next turn rather than standing, and now
         * that is true of every way this can fail.</b> Nothing is written at all,
         * so {@code EntryStore.foldedThrough} still answers with the previous
         * reach; the {@code compactions} row this method is called after is the
         * only trace left, and the next fold's lower bound is read from the log
         * and not from that row. The reach is the turn before the last, so the
         * next utterance moves it on by one, {@code already.throughOrdinal() >=
         * through} no longer holds, and the fold is taken again over a range that
         * includes everything the failed one covered. The cost of the failure is
         * one over-long prompt and one extra summary. Run rather than reasoned,
         * and it takes two tests because there are two failures: {@code
         * CompactionTest.a_fold_the_log_could_not_be_given_is_taken_again_when_
         * the_reach_moves} sets up a {@code compactions} row the log never got,
         * and {@code CompactionTest.a_fold_whose_supersede_fails_leaves_no_
         * summary_behind_and_the_next_fold_takes_the_span} fails the second
         * statement and asserts that the first one did not survive it.
         *
         * <p>It is logged at {@code warn} because, unlike a single lost message,
         * this one leaves two records of the same conversation disagreeing about
         * a fold.
         *
         * @return the reach this fold started above — the lower bound it read,
         *     exclusive, so the span it covered is {@code (since, through]} — or
         *     {@code -1} for a fold the log did not get. The caller wants it for
         *     one thing only: the diagnostic entry names both ends of the span,
         *     and this method is the only place that has read the near end
         */
        private int foldTheLog(int through, String summary) {
            try {
                // BEFORE the append, and this is the whole of the ordering that
                // matters here. EntryStore.foldedThrough answers with the reach
                // of the newest STANDING summary, and the entry about to be
                // written is a standing summary reaching `through`. Read it
                // afterwards and the fold's lower bound would be its own upper
                // bound, `(through, through]` would be empty, and the fold would
                // supersede nothing at all while looking exactly like a fold
                // that worked. CompactionTest.a_fold_reads_how_far_the_log_is_
                // already_folded_before_it_appends_its_own_summary is the seam
                // that fails if these two lines are swapped.
                int since = entries.foldedThrough(conversationId);
                entries.fold(conversationId, since, through, LoggedEntry.summary(summary));
                return since;
            } catch (RuntimeException notFolded) {
                // The reach and not "turns 1 to", which stopped being true when
                // folds became incremental. The lower bound is deliberately not
                // named: the read that would have given it is one of the things
                // that can have failed here, and a line that guessed at it would
                // be an operator told which turns were folded by a method that
                // did not find out.
                log.warn("conversation {}: the summary through turn {} was written, and the"
                                + " entry log could not be folded to match. The compaction"
                                + " stands. Reason: {}",
                        conversationId, through, JobRuntime.describe(notFolded));
                return -1;
            }
        }

            /**
         * One model call, as the folder, and on nobody's allowance.
         *
         * <h2>It is the folder's prompt and the span as data</h2>
         *
         * <p><b>Two messages: {@code conversation_folder}'s own prompt in the
         * system slot, and the turns this fold covers in one user message.</b>
         * Not the conversing agent's prompt, not the conversing agent's model,
         * and not the conversing agent's temperature — all three are the
         * folder's, and {@link #folder} carries why they have to be.
         *
         * <p><b>It used to be the conversation itself.</b> {@code
         * oneSystemMessageFirst(definition, messages(…))} with the instruction
         * appended as one user message, sent on the agent's own model: a prompt
         * byte-identical to the one that conversation had just sent, plus a
         * paragraph. That was worth 2.50 s against 57.97 s on the reference node
         * — {@code implementation rationale} §3,
         * which measured the shape before it, a summariser prompt of its own
         * over the whole history flattened — because an endpoint that reuses
         * work credits a prompt that <em>extends</em> a sequence it holds.
         *
         * <p><b>That saving is given up here deliberately, and it is priced.</b>
         * {@code implementation rationale}
         * §6.5, folding a conversation at this server's real threshold: the
         * extension shape folds in 0.63 s <em>while that conversation is
         * resident</em> and pays a full cold prefill — 74.90 s — whenever
         * anything has evicted it, which §5's digest pass, another agent or a
         * reload all do at a moment nobody chose. The span as data on the second
         * node is 14.70 s, every time, which over a cycle of about 72 turns is
         * about 0.20 s a turn. Bimodal 0.63-or-75 s against reliably 14.7 s, and
         * for something rare and expensive that is the right way round.
         *
         * <p><b>And the fold is no longer the cheapest thing that can go
         * wrong.</b> §6.1 measured what the extension shape cost when the agent
         * had a voice: a "CAVEMAN" bot folded a conversation carrying four
         * decisions and a file path down to "CAVEMAN grunt. CAVEMAN no
         * understand this big word. Too much think.", three runs of three, while
         * the call succeeded, a row was written and nothing reported that the
         * summary carried no content. Nothing in the old arrangement could
         * report it, either — the archive stays whole and only the model's
         * forward view is destroyed, which is the one thing a fold exists to
         * produce.
         *
         * <h2>The narrowing is in the input now, and not only in the ask</h2>
         *
         * <p><b>{@link #spanAsData} sends the turns this fold covers and stops
         * there.</b> §6.5 names scope as the one of the old shape's five duties
         * that better wording cannot fix, because "summarise turns 2 to 3 of
         * what follows and leave the rest" is a request to read part of an input
         * and ignore the rest. Included in the message, the span is decided in
         * code.
         *
         * <p><b>The standing summary still goes in, and the newest turn no
         * longer does.</b> Those two are not a pair: the earlier summary is
         * context the span cannot be read without — notes about turns 4 to 7
         * written by a reader who cannot see turns 1 to 3 refer to decisions
         * they never name, which is what {@link
         * #THE_SPAN_SINCE_THE_LAST_SUMMARY} exists to pay for — while the turn
         * the fold does not reach was in the list for one reason only, that
         * dropping it turned an extension of the conversation's prompt into a
         * shorter prefix of it and cost the whole saving. There is no prefix to
         * protect now, so the exchange the person is still in the middle of is
         * simply not sent to be summarised.
         *
         * <p><b>{@code since} is read here and read again in {@link
         * #foldTheLog}, and the second read is not an oversight.</b> It is the
         * same number — nothing between the two appends a summary — and it is
         * one indexed row count in front of a model call that takes seconds. Two
         * reads is what keeps each of them inside the guard belonging to the
         * step that uses it: a bound this method cannot read is a summary that
         * is not bought, and a bound {@code foldTheLog} cannot read is a fold the
         * log did not get, which the next turn takes again. Read once above both
         * and a database that blinked would leave {@link #before()} returning a
         * turn with no history at all, which is the one failure this class never
         * takes.
         *
         * <p><b>It reads the log and not the {@code compactions} row it was
         * handed.</b> A standing seam is a {@code summary} entry, so the
         * projection carries it without this method being told about one — which
         * is what keeps what a fold reads and what the conversation itself is
         * shown one reading of one log rather than two constructions that have
         * to be kept in step.
         *
         * <h2>What this call is bounded by, which is no longer the same
         * ceiling</h2>
         *
         * <p><b>The old claim was that this call is the history unchanged and
         * therefore cannot be the prompt that overflows. It is neither claim any
         * more and both halves have to be said.</b> What goes out is the span,
         * with a turn number and two labels per turn, and without the agent's
         * prompt or the newest turn — so it is bounded by the same fold
         * threshold that triggered it, plus scaffolding that grows with the
         * number of turns folded, minus two things the conversation's own prompt
         * carries.
         *
         * <p><b>And it is measured against a different model's window.</b>
         * {@link Compaction#thresholdsFor} reads {@code definition.model()}'s context length,
         * because that is the prompt the fold exists to keep inside it; this
         * call goes out on the folder's model, whose window this class never
         * asks for. A folder bound to a <em>smaller</em> window than the
         * conversation's would be a fold refused by its own endpoint —
         * answering {@code null} below, taking the turn's history with it and
         * saying so in the log rather than failing the turn. That is a
         * configuration to detect at boot, beside the binding, and not a number
         * for this method to guess at.
         *
         * <p>A failure answers {@code null} rather than ending the turn — a
         * folder that cannot be resolved at all included, which {@link #folder}
         * leaves to the registry to report. The turn then runs with its whole
         * history and meets the endpoint's own refusal, which names the model
         * and the length; failing the turn here would turn a prompt that might
         * have fitted into one that certainly did not run.
         */
        private String summarise(int through) {
            try {
                int since = entries.foldedThrough(conversationId);
                // Asked for per fold and not held: see Compaction.folder. A miss
                // throws from here, which is one of the failures the catch below
                // turns into a turn that runs with its whole history.
                AgentDefinition folding = folder.get();
                List<ChatMessage> asked = List.of(
                        ChatMessage.system(folding.prompt()),
                        ChatMessage.user(spanAsData(since, through)));
                // requestFor and not ChatRequest.of, so the fold runs on the
                // folder's declared temperature as well as its model.
                // JobRuntime.requestFor's javadoc argues that this call should
                // not be carved out of the "a definition's sampling is what its
                // requests carry" rule because it "already borrows the agent's
                // model and the agent's prompt, so a temperature carve-out would
                // make one of the three fields behave unlike the other two".
                // The three fields are consistent again here and it is the other
                // way round: model, prompt and temperature are all the folder's.
                Completion written =
                        models.complete(JobRuntime.requestFor(folding, asked).withAttribution(foldUsage(folding)));
                String summary = written.content() == null ? "" : written.content().strip();
                if (summary.isEmpty()) {
                    // Refused rather than written. compactions_summary_says_something
                    // would refuse it one layer down anyway, and the row it
                    // would have made is the worst of both: the turns vanish
                    // from the prompt and the seam sentence goes on saying they
                    // were summarised.
                    log.warn("conversation {}: the summary came back empty, so nothing was"
                            + " compacted and the whole history stands.", conversationId);
                    return null;
                }
                return summary;
            } catch (RuntimeException failed) {
                log.warn("conversation {}: its history could not be summarised, so this turn runs"
                                + " with all of it. Reason: {}",
                        conversationId, JobRuntime.describe(failed));
                return null;
            }
        }

        /**
         * The one user message a fold carries: what is being asked for, and then
         * the span it is being asked about.
         *
         * <p><b>The instruction first, and that is not a free choice.</b> {@link
         * #ASK_FOR_A_SUMMARY} opens with {@link #FROM_THE_HARNESS}, which is how
         * the scripted transports in {@code CompactionTest} and {@code
         * ProjectionTest} tell a fold from a turn — {@code
         * messages.get(last).content().startsWith(FROM_THE_HARNESS)} — and a
         * fold they cannot recognise is one they answer down the ordinary
         * branch, which passes rather than fails.
         *
         * <p><b>That instruction's direction words now point where this
         * actually puts things, and fixing them left that recognition
         * untouched.</b> It used to say "the conversation above" and
         * "everything above this message", carried over unchanged from when it
         * arrived at the end of the conversation's own message list. Nothing
         * here reorders the two halves — {@code
         * implementation rationale} §6.5
         * keeps the instruction first and this method's own span as data below
         * it — so the words only needed to say "below" instead. {@link
         * #FROM_THE_HARNESS} is the entire prefix the two transports check, by
         * {@code startsWith}; it opens {@link #ASK_FOR_A_SUMMARY} unchanged, so
         * rewording what follows it was never a risk to what they recognise.
         */
        private String spanAsData(int since, int through) {
            return askForASummary(since, through) + "\n\n" + theSpan(since, through);
        }

        /**
         * The span, flattened: the summaries that already stand, and then every
         * turn this fold reaches, numbered.
         *
         * <h2>Written here rather than reused from somewhere</h2>
         *
         * <p>There is nothing to reuse. The renderer that produced this shape
         * before — {@code record(…)}, the one {@code
         * implementation rationale} §3 measured at
         * 57.97 s — went when the extension shape landed, and what is left in
         * this class under that name writes entries to a log. {@link #A_TURN}
         * carries what the shape has to do.
         *
         * <h2>It reads the projection, and numbers turns from {@code since}</h2>
         *
         * <p><b>{@link #messages} and not the log</b>, so that a fold is shown
         * exactly what this conversation's own next prompt would carry: the same
         * seams, the same reference-or-result substitution from the same
         * agent's declared capabilities, the same pairing of a call that never
         * came back. A second reading of the log here would be a second place
         * for an agent's view of its own past to be assembled differently, which
         * is the argument {@code Projection.of} makes for itself.
         *
         * <p><b>The numbers come from {@code since} and not from counting.</b> A
         * fold covers {@code (since, through]} and everything below {@code
         * since} is behind a summary, so the first turn the projection still
         * carries is {@code since + 1} and they run on from there — which is
         * what makes the numbers in this record the numbers {@link
         * #askForASummary} asks about.
         *
         * <p><b>That is checked and not assumed, and it stayed checked when the
         * window this class used to open itself was closed.</b> {@code
         * foldTheLog} appended a summary and then superseded what it covered as
         * two statements, and between them {@code EntryStore.foldedThrough}
         * answered with the new reach while every raw turn still projected;
         * {@link EntryStore#fold} makes them one write, so this server no longer
         * produces that state. What can still produce it is a log this process
         * did not write alone — another server folding the same conversation,
         * or a row put there by hand — and the cost of being wrong is the same
         * either way: a span summarised under turn numbers it does not have. The
         * rows are asked where they open and a disagreement refuses the fold —
         * {@link #theNumbersDisagree} carries the whole argument, and {@link
         * #withTurnsIn} the other half of it.
         *
         * <p><b>It stops at {@code through} and the stop is structural.</b> The
         * turn after it is the exchange the person is in the middle of; it is in
         * the projection because the next real turn needs it, and it is not in
         * this record because this fold does not cover it. A model asked to
         * leave it out would be being asked to ignore part of its input, which is
         * the request {@code
         * implementation rationale} §6.5 says
         * to stop making.
         */
        private String theSpan(int since, int through) {
            // ONE READ, and the rows and the messages come out of it together.
            // `messages(conversationId, definition)` is this same composition
            // over its own read; asking the store twice -- once to check where
            // the projection starts and once to render it -- would leave the
            // check and the render looking at two different answers, which is the
            // race this check exists to catch rather than to join.
            List<EntryRecord> shown = entries.thatProjectFor(conversationId);
            int opens = firstTurnIn(shown);
            if (opens != since + 1) {
                throw new IllegalStateException(theNumbersDisagree(since, through, opens));
            }
            StringBuilder span = new StringBuilder();
            int turn = since;
            int rendered = 0;
            // Which USER-role message is a turn and which is a notice -- see the
            // method for why a role alone cannot say, now that EntryKind.NOTICE
            // shares UTTERANCE's role.
            Iterator<EntryKind> userKinds = userMessagesAreUtterances(
                    shown, Projection.Superseded.HIDES_A_ROW).iterator();
            for (ChatMessage message
                    : projected(shown, definition, Projection.Superseded.HIDES_A_ROW)) {
                switch (message.role()) {
                    // A standing summary, worded as the seam the conversation
                    // itself is shown. It is above the turns it covers here for
                    // the same reason it is there: it is what they became.
                    case SYSTEM -> {
                        startABlock(span);
                        span.append(message.content()).append('\n');
                    }
                    case USER -> {
                        EntryKind kind = userKinds.next();
                        if (kind == EntryKind.TURN_SUMMARY) {
                            // A fold inside that turn: what its older steps became, read in
                            // the turn's own block, as the turn itself read it.
                            span.append(SUMMARISED).append(message.content()).append('\n');
                        } else if (kind == EntryKind.UTTERANCE) {
                            if (++turn > through) {
                                return withTurnsIn(span, rendered, since, through);
                            }
                            rendered++;
                            startABlock(span);
                            span.append(A_TURN.formatted(turn)).append('\n')
                                    .append(SAID).append(message.content()).append('\n');
                        } else {
                            // A NOTICE, not a turn: it carries the same turn
                            // ordinal as the utterance beside it and joins that
                            // turn's own block rather than opening one of its
                            // own. Counting it here would misnumber every turn
                            // after it and could cut the span short on a
                            // boundary `through` was never asked to stop at.
                            span.append(NOTICED).append(message.content()).append('\n');
                        }
                    }
                    case ASSISTANT -> span.append(answered(message)).append('\n');
                    case TOOL -> span.append(CAME_BACK).append(message.content()).append('\n');
                    default -> throw new IllegalStateException(
                            "a projected message has no place in a span: " + message.role());
                }
            }
            return withTurnsIn(span, rendered, since, through);
        }

        /**
         * Which kind each projected {@code USER}-role message is — an utterance,
         * which opens a turn, a notice, or an in-turn summary — in the order {@link
         * #theSpan} meets them.
         *
         * <h2>Why {@code theSpan} cannot ask a {@code ChatMessage} this itself</h2>
         *
         * <p>{@link EntryKind#UTTERANCE} and {@link EntryKind#NOTICE} both
         * project to {@code ChatMessage.Role.USER} — see {@code
         * EntryKind.NOTICE} for why a notice is harness speech that still takes
         * the person's role — so once an entry becomes a {@link ChatMessage} the
         * two are indistinguishable. {@code theSpan}'s turn counter used to treat
         * every {@code USER} message as a turn, which was safe while only
         * {@code UTTERANCE} produced one; a notice mistaken for a turn inflates
         * every turn number after it and can trip the {@code through} boundary on
         * a turn that was never actually reached, cutting the fold short for a
         * reason nothing in the record explains.
         *
         * <p><b>Built from the raw rows and not from the projection, and that is
         * sound rather than a shortcut.</b> {@link
         * Compaction#whatWasSaidAndWhatCameBack} — the one transform between
         * {@code shown} and what {@link #projected} renders — only ever rewrites
         * or drops {@code ANSWER} and {@code TOOL_RESULT} rows; it never touches
         * an {@code UTTERANCE} or a {@code NOTICE}. So the order and the count of
         * the two kinds that can produce a {@code USER} message survive that
         * transform unchanged, and the Nth flag this returns answers for the Nth
         * {@code USER} message {@code theSpan}'s loop reaches.
         *
         * <p>Filtered exactly as {@link Projection#of} filters: a row a fold
         * already covers, under the same {@link Projection.Superseded} reading
         * the caller asked for, contributes nothing, and neither does a kind that
         * does not project at all.
         */
        private static List<EntryKind> userMessagesAreUtterances(
                List<EntryRecord> shown, Projection.Superseded superseded) {
            List<EntryKind> flags = new ArrayList<>();
            for (EntryRecord entry : shown) {
                boolean covered = superseded == Projection.Superseded.HIDES_A_ROW
                        && entry.supersededBy() != null;
                if (covered || !entry.kind().projects()) {
                    continue;
                }
                if (entry.kind().role().orElse(null) == ChatMessage.Role.USER) {
                    // Projection moves an in-turn summary back past its own turn's answers
                    // and results only, never past another USER-role row, so the order of
                    // these is the order the loop meets them in.
                    flags.add(entry.kind());
                }
            }
            return flags;
        }

        /**
         * The turn the projection opens at, or {@code -1} for a projection with
         * no turn in it at all.
         *
         * <p>The first row that <em>is</em> a turn: a seam is a {@code SUMMARY}
         * entry and sorts in front of the turns still standing, and it carries
         * the last turn it stands for as its own ordinal, so reading the first
         * row's ordinal would answer with the end of the span before this one.
         */
        private static int firstTurnIn(List<EntryRecord> shown) {
            for (EntryRecord entry : shown) {
                if (entry.kind() == EntryKind.UTTERANCE) {
                    return entry.turnOrdinal();
                }
            }
            return -1;
        }

        /**
         * The record, once it is known to have turns in it.
         *
         * <p><b>A span with no turns is not a cheap fold, it is a wrong one.</b>
         * What would go out is {@link #askForASummary}'s instruction asking for
         * turns 3 to 5 over a body holding a seam sentence and nothing else — a
         * model asked to summarise turns it cannot see, whose answer is then
         * written down as their summary and the turns themselves superseded by
         * it. Declining costs one fold; the turn runs with its whole history and
         * the next fold takes the wider span, which is the path {@code
         * a_summary_that_could_not_be_written_leaves_the_log_alone_and_the_next_
         * fold_covers_more} already pins.
         */
        private String withTurnsIn(StringBuilder span, int rendered, int since, int through) {
            if (rendered == 0) {
                throw new IllegalStateException(theNumbersDisagree(since, through, -1));
            }
            return span.toString().strip();
        }

        /**
         * Why a fold declined to render a span, in a sentence an operator can
         * act on.
         *
         * <h2>The state this exists for, and who can still make it</h2>
         *
         * <p><b>{@code foldTheLog} used to make it, and no longer does.</b> It
         * appended the summary and then superseded what it covers as two
         * statements, arguing that the gap between them was benign — "a summary
         * nothing points at is redundant and readable". That was true of what a
         * <em>turn</em> reads and false of what this method infers: inside that
         * window {@code EntryStore.foldedThrough} already answers with the new
         * summary's reach while every raw turn it covers still projects, so
         * {@code since} and the rows disagree. {@link EntryStore#fold} makes the
         * two one write, so the gap is gone and, with it, the version of this
         * state that never healed.
         *
         * <p><b>The check stays, because this process is not the only writer.</b>
         * Two servers against one database fold on their own {@code folding}
         * sets, and a log can be edited; what this method refuses is a
         * disagreement whoever caused it. The numbering here would label the
         * conversation's first turn {@code since + 1}, the record would be cut a
         * turn or so in, and {@code supersede} would then hide turns whose summary
         * is of some other turns entirely.
         *
         * <p><b>Which is data loss and not a redundant prompt</b>, because the
         * summary is what every later turn reads in place of what it replaced.
         * So the disagreement is refused rather than rendered: this message
         * reaches {@link #summarise}'s {@code catch}, the fold declines, the turn
         * runs with its whole history and the next fold takes the span again —
         * which now succeeds if the two reads have come back into agreement and
         * declines again, loudly, if they have not.
         *
         * <p>It names both numbers and the row the projection actually opens at,
         * because the three together are what says which of the two is stale.
         */
        private String theNumbersDisagree(int since, int through, int opens) {
            return "conversation " + conversationId + ": a fold of turns " + (since + 1)
                    + " to " + through + " was asked for, and the history it projects "
                    + (opens < 0
                            ? "holds no turn at all"
                            : "opens at turn " + opens + " rather than at turn " + (since + 1))
                    + ". The log says it is folded through turn " + since + ". Nothing was"
                    + " summarised: a span rendered against a bound it disagrees with is"
                    + " summarised under the wrong turn numbers, and the turns it then"
                    + " supersedes are not the turns the summary is of.";
        }

        /**
         * A blank line before a block, where there is anything to separate it
         * from.
         *
         * <p>Which the labels do not do on their own: an answer running to
         * several paragraphs of its own is indistinguishable from the start of
         * the next turn in a record with no gaps in it, and a fold that misreads
         * where a turn ends attributes what one person said to another.
         */
        private static void startABlock(StringBuilder span) {
            if (!span.isEmpty()) {
                span.append('\n');
            }
        }

        /**
         * One assistant message, as one or two lines of the record.
         *
         * <h2>Prose and calls are independent, and an earlier version of this
         * method treated them as alternatives</h2>
         *
         * <p><b>An assistant message can carry both, and that is the ordinary
         * shape rather than a corner.</b> {@code JobRuntime} records {@code
         * LoggedEntry.answer(completion.content(), asked)} and {@code
         * Projection.answerOf} keeps the content whenever there is any, so a
         * model that says "let me check the config" and asks for {@code
         * file_read {"path": "/etc/app.yml"}} arrives here as one message holding
         * the sentence and the call. This method used to emit the calls only when
         * there was no prose, which rendered that message as its sentence alone:
         * a {@link #CAME_BACK} line with nothing above it saying what was asked
         * for, and the path gone.
         *
         * <p><b>Gone for good, which is what makes it worth a branch.</b> Every
         * later turn reads the summary instead of the turns it replaced, so
         * whatever this record drops is not recoverable by the model from
         * anywhere — and a path passed to a tool is precisely what {@code
         * conversation_folder} is told to keep ("names and paths that were
         * used"). {@link #ASKED_FOR} carries that argument.
         *
         * <p>An answer that is entirely tool calls still renders as the calls
         * alone: a line reading {@code "Answered: "} with nothing after it tells
         * a reader the model said nothing when what it did was act.
         */
        private String answered(ChatMessage message) {
            StringBuilder said = new StringBuilder();
            if (!message.content().isBlank()) {
                said.append(ANSWERED).append(message.content());
            }
            if (!message.toolCalls().isEmpty()) {
                if (!said.isEmpty()) {
                    said.append('\n');
                }
                said.append(ASKED_FOR);
                for (ToolCall call : message.toolCalls()) {
                    said.append(call.name()).append(' ').append(call.arguments()).append(' ');
                }
            }
            // Neither, which Outcome permits and Projection already substitutes
            // for: a model that stopped on its first token said nothing and asked
            // for nothing, and the substitution is the content this reads.
            return said.isEmpty() ? ANSWERED + message.content() : said.toString().strip();
        }
    }

    // --- what the model is shown ----------------------------------------------------

    /**
     * How long a prompt {@code definition}'s model accepts — the ceiling a fold
     * keeps a conversation under.
     *
     * <p><b>Public so a client showing a conversation's load divides by the same
     * number compaction does.</b> A second call to {@link
     * LlmDispatcher#contextLength(String, int)} elsewhere would have to carry
     * {@code plowshare.llm.default-context-length} along with it, and a surface
     * that forgot the fallback would show no ceiling for exactly the model this
     * class folds against a guessed one.
     *
     * <p>May open a connection, as the dispatcher's own method may; not for the
     * boot path.
     *
     * @return the smallest length any pool serving the model knows, the default
     *     where none could say, or empty when nothing serves the model at all
     */
    public OptionalInt contextLengthOf(AgentDefinition definition) {
        return models.contextLength(definition.model(), defaultContextLength);
    }

    /**
     * What this conversation's next turn would open with: the agent's prompt
     * hoisted to the front and merged with any standing seam, followed by the
     * projected history — the same arrangement {@code JobRuntime.opening} builds
     * for a real turn, minus the utterance nobody has typed yet.
     *
     * <h2>The public door onto {@link #messages}, and why one was needed</h2>
     *
     * <p>{@link #messages} already answers the question; it is private because
     * its only caller used to be {@code TurnTranscript.summarise} — private
     * itself, which is why this is {@code @code} and not {@code @link} — when a
     * fold was the conversation's own prompt with an instruction on the end. It
     * is still a caller, through {@code TurnTranscript.theSpan}, and it reads the
     * same projection for a different medium: the span a fold is sent is that
     * list flattened into text. A console asking "what would this conversation's
     * prompt be" is a third caller with the identical question, from outside
     * this package, and it needs the identical answer —
     * duplicating the projection would be a second place for {@code
     * JobRuntime.oneSystemMessageFirst}'s ordering rule to be gotten wrong, which
     * is exactly the outage its own comment records. So this method is the
     * composition {@code TurnTranscript.summarise} already performs, made public
     * rather than copied.
     *
     * <h2>It computes and it never sends</h2>
     *
     * <p><b>No model call is made on any path through this method.</b> {@link
     * #messages} is a read of {@code entries} and {@code
     * JobRuntime.oneSystemMessageFirst} is list arrangement; neither touches
     * {@link #models}. That is the property a console route built on this method
     * has to keep — serving the projection by actually sending it would evict
     * the prompt prefix the next real turn is reusing and buy a generation of
     * unbounded length for what is supposed to be a read, which is the same
     * argument {@code ContextView} already makes against measuring a prompt by
     * subtraction.
     *
     * <h2>What this does not promise</h2>
     *
     * <p>It is computed from {@code definition} <em>as it stands now</em>, and a
     * definition is a file an operator can edit between turns. So this is an
     * honest answer to "what would this conversation's next prompt be", and not
     * a promise that it is byte-identical to what an earlier turn actually sent
     * — the turns table and the log are the record of that, and this method
     * reads neither of them for the agent's prompt text.
     *
     * @param conversationId the conversation whose next prompt is being asked
     *     about
     * @param definition the agent that would answer it — the caller's choice,
     *     exactly as it is the caller's choice for {@code ContextView.Prefix},
     *     since a conversation names no agent of its own
     * @return the messages a turn would open with, system message first, in the
     *     order a request would carry them
     */
    public List<ChatMessage> projectionFor(String conversationId, AgentDefinition definition) {
        return JobRuntime.oneSystemMessageFirst(
                JobRuntime.systemText(definition.prompt(), openingOf(conversationId)),
                messages(conversationId, definition));
    }

    /**
     * A log's fixed opening, or the empty string for none, for a fixture with no store, or for a
     * read that failed — logged, because that request then goes without it. Never throws.
     */
    String openingOf(String conversationId) {
        if (conversations == null) {
            return "";
        }
        try {
            return conversations.opening(conversationId).orElse("");
        } catch (RuntimeException unread) {
            log.warn("conversation {}: its fixed opening could not be read, so this request goes"
                    + " without it. Reason: {}", conversationId, JobRuntime.describe(unread));
            return "";
        }
    }

    /**
     * What this conversation's turn {@code turn} opened with: the same
     * arrangement {@link #projectionFor} builds, over the history as that turn
     * could see it rather than as the log stands now.
     *
     * <p><b>Opened with, and not came to.</b> The list ends at that turn's own
     * utterance — the answer it produced and the results it collected are not in
     * it, because they were not in front of the model when it started. {@code
     * EntryStore.thatProjectedAt} is what draws that line, at the ordinal of the
     * turn's first entry, and says why it cannot be drawn at a turn number.
     *
     * <h2>One composition and not two</h2>
     *
     * <p><b>Everything above the store is identical, and that is the point.</b>
     * The projection, the reference substitution and {@code
     * JobRuntime.oneSystemMessageFirst}'s ordering rule are the same code on
     * both paths; the only thing that differs is which rows the store answers
     * with — {@code EntryStore.thatProjectedAt} instead of {@code
     * thatProjectFor}. A separate assembly here would be a second place for the
     * ordering rule to be gotten wrong, which is the outage {@link
     * #projectionFor}'s own javadoc records, and it would be a second answer to
     * a question that has one.
     *
     * <h2>It computes and it never sends</h2>
     *
     * <p><b>No model call is made on any path through this method</b>, for
     * {@link #projectionFor}'s reason in full: a read that dispatched to measure
     * itself would evict the prompt prefix the next real turn is reusing and buy
     * a generation of unbounded length. Nothing on this path touches {@link
     * #models} — it is one store read and list arrangement — and the route above
     * is tested against a dispatcher whose transport fails the moment it is
     * asked for anything.
     *
     * <h2>Which system block goes at the front, and the answer says which</h2>
     *
     * <p><b>The turn's own, when the turn recorded one.</b> {@code
     * turns.system_block} is the block a turn was actually sent — V32 — and a
     * turn that has one is answered with it, so a projection of a turn from last
     * week opens with last week's prompt however many times the file has been
     * edited since.
     *
     * <p><b>Today's definition, when it did not, and never a guess dressed as a
     * recording.</b> Every turn written before V32 has no block and nothing
     * anywhere can recover one — the migration argues at length why a backfill
     * from the current files was refused — so the honest answer for those is the
     * definition as it stands now, <em>labelled</em>: {@link Shown#systemBlockAsSent}
     * is false and {@code ProjectionView} carries it onto the wire so the console
     * can say it on the page. The alternative, refusing to answer at all until
     * every turn has a block, would withhold the half of the record that <em>is</em>
     * exact for the whole of the existing archive.
     *
     * <p><b>The prompt alone is stored, and the seam is rebuilt here.</b> What
     * {@code JobRuntime.oneSystemMessageFirst} put on the wire was the prompt
     * merged with any standing seam, and what V32 records is the prompt half.
     * Two reasons, neither of them "the seam comes back unchanged": the
     * <em>summary</em> is an entry and the archive already holds it
     * authoritatively, so storing the merged text would put a second copy of it
     * in the same archive for this reading to disagree with; and re-merging here
     * keeps one implementation of the "system message must be first" rule
     * instead of freezing a derived string beside it.
     *
     * <p><b>The seam itself is not a recording, and that is the residual gap.</b>
     * A seam is generated at read time — {@code Projection.messageFor}'s {@code
     * SUMMARY} arm, over {@link #seam(boolean)} — from the summary's text, the
     * span it covers and <em>today's</em> {@code definition.canList()}. So an
     * agent that has since lost {@code result_list} renders {@link #SEAM} where
     * the turn was sent {@link #SEAM_OVER_STORED_RESULTS}, and the same is true
     * one field over of {@code canRedeem}: gaining {@code result_read} since
     * replaces results the turn read in full with {@link #REFERENCE} lines.
     * <b>Versioning the tool block is out of scope for this route</b>, so what is
     * pinned is the prompt block and the rows, and what still floats is the
     * agent's tools and therefore the wording of a seam and the shape of an old
     * result. {@link Shown#systemBlockAsSent} says nothing about that half; it is
     * about the block.
     *
     * <h2>Two tables, one turn number, and nothing enforcing that</h2>
     *
     * <p><b>This method is the join, so the coupling is named here.</b> {@code
     * turns.blockSentAt} keys on {@code turns.ordinal}, which {@code
     * TurnStore.INSERT} computes as {@code MAX(ordinal) + 1} inside the insert;
     * {@code entries.thatProjectedAt} keys on {@code entries.turn_ordinal},
     * which {@code TurnTranscript.turnOrdinal} computes as {@code
     * turns.forConversation().size() + 1} before the turn runs. The two agree
     * only while every turn wrote its row — and {@code TurnTranscript.closed}
     * logs and steps over a refused {@code turns.record}, deliberately, so that a
     * failed audit write cannot take a turn's work with it. One such failure
     * leaves the counts one apart for the rest of the conversation.
     *
     * <p>Pre-existing, and this read gives it a new consequence: before {@code
     * ?turn=} nothing asked the two tables about the same turn number, and now a
     * skewed conversation can be served one turn's block over another turn's
     * history with {@code systemBlockAsSent} true. Not repaired here — the fix is
     * for the two writers to agree on one counter, which is a change to the
     * write path and to what a swallowed failure may swallow — and recorded here
     * because this is where the two numbers are first assumed to be one.
     *
     * @param conversationId the conversation being read
     * @param definition the agent whose prompt the answer opens with <b>when the
     *     turn recorded no block of its own</b> — the caller's choice, exactly as
     *     it is on {@link #projectionFor}. A turn that recorded one is answered
     *     with that, and this definition then decides only the reading of the
     *     rows: {@code canRedeem} and {@code canList}, as it does on both paths
     * @param turn the turn to read as of, from 1. A turn the conversation never
     *     reached is refused by the caller, which is the layer that knows how
     *     far this conversation got
     * @return the messages that turn opened with, system message first, and
     *     whether the block at the front is the one it was sent
     */
    public Shown projectionAsOf(
            String conversationId, AgentDefinition definition, int turn) {
        String recorded = turns.blockSentAt(conversationId, turn).orElse(null);
        // WAS_WEIGHED_BY_THE_READ, and it is the whole of what makes this answer
        // a history at all: `thatProjectedAt` returns the rows a later fold
        // covered on purpose, because at this turn that fold had not happened.
        // Handing them to the reading that re-checks `superseded_by` drops every
        // one of them and answers with a turn that saw nothing.
        List<ChatMessage> history = projected(
                entries.thatProjectedAt(conversationId, turn), definition,
                Projection.Superseded.WAS_WEIGHED_BY_THE_READ);
        return new Shown(
                JobRuntime.oneSystemMessageFirst(recorded == null
                        ? JobRuntime.systemText(definition.prompt(), openingOf(conversationId))
                        : recorded, history),
                recorded != null);
    }

    /**
     * What one turn opened with, and whether its system block is the one that
     * turn was actually sent.
     *
     * <h2>Why the flag travels with the list rather than being asked for
     * separately</h2>
     *
     * <p><b>Because they are one answer and a caller must not be able to hold
     * half of it.</b> A route that took the messages here and then asked the
     * store a second question about the block could get a different answer to the
     * second — a turn recorded between the two reads — and would then label a
     * list with a fact about a different one. More plainly: a reader who has the
     * messages and not the label has exactly the thing this whole feature exists
     * to remove, a projection that looks like a recording.
     *
     * <p>{@link #projectionFor} returns a bare list and not one of these, and
     * that is deliberate rather than an omission: it answers what the
     * conversation's <em>next</em> prompt would carry, and there is no turn for a
     * block to have been sent to. "As sent" is a question only a past turn has.
     *
     * @param messages the assembled list, system message first, in the order a
     *     request would carry them
     * @param systemBlockAsSent whether {@code messages}'s system block is the one
     *     this turn recorded going out with. <b>False means the block is the
     *     agent's prompt as it stands now</b>, with the log's fixed opening after
     *     it as {@link #projectionFor} arranges them — because the turn recorded none,
     *     which is every turn written before {@code V32__turn_system_prompt.sql}
     *     — and not that the block is missing or wrong.
     *
     *     <p><b>It is a statement about the block and about nothing else.</b>
     *     The <em>rows</em> under it are the ones that turn could see either way
     *     — {@code EntryStore.thatProjectedAt} settles that from the log — but
     *     how two of them read still follows the agent's {@code tools:} as it
     *     stands now, which nothing records per turn: a seam is worded from
     *     today's {@code canList} and an older tool result is shown in full or as
     *     a reference from today's {@code canRedeem}. {@link #projectionAsOf}
     *     carries that gap in full
     */
    public record Shown(List<ChatMessage> messages, boolean systemBlockAsSent) {
    }

    /**
     * The history, projected out of the conversation's own log.
     *
     * <p>Every turn becomes two messages — what was said and what came back —
     * and a turn that did not answer becomes those two as well. <b>Its answer is
     * the sentence {@code JobRuntime} wrote about the ending, not a model's
     * prose</b>, which is the point: a run that stopped at its turn cap is a
     * thing the next turn should know happened, and omitting it would leave an
     * utterance with no reply and a model reading a conversation that never took
     * place. {@code Outcome} guarantees that sentence names the ending and lists
     * the tools the run called, so it reads as what it is; {@code
     * Turn.closeTheLog} records exactly that sentence as the turn's last {@code
     * answer} entry, which is why the two records say the same thing.
     *
     * <p><b>A fold needs nothing said about it here.</b> A summary is an entry
     * and the entries it covers are marked superseded, so they are not read at
     * all and the seam is rendered in their place. This method knew about {@code
     * compactions} until it read the log; the fold is now a property of what it
     * is projecting rather than an instruction it has to carry out.
     *
     * <p><b>{@code thatProjectFor} and not {@code forConversation}</b>, which is
     * the same distinction one layer down: the store is asked for the entries a
     * model can be shown rather than for the log, so the rows a fold covered are
     * never fetched. {@link Projection} skips them either way — that filter
     * stays — and the store's own javadoc carries the proof that the SQL
     * predicate and {@code EntryKind.projects()} are one sentence. What changes
     * is that the projected history is bounded by folding and the log is not, so
     * the read no longer grows with everything ever said.
     */
    private List<ChatMessage> messages(String conversationId, AgentDefinition definition) {
        return projected(
                entries.thatProjectFor(conversationId), definition,
                Projection.Superseded.HIDES_A_ROW);
    }

    /**
     * The same reading, over rows somebody else chose.
     *
     * <p><b>Split out when {@link #projectionAsOf} arrived, and split rather
     * than copied.</b> Two readings of the log now exist — what a model can be
     * shown and what one turn was shown — and they differ in the store call and
     * in one parameter. Everything after the rows arrive is the substitution
     * {@link #whatWasSaidAndWhatCameBack} chooses from {@code canRedeem} and the
     * seam {@code Projection.of} words from {@code canList}, and a second copy
     * of that pair would be a second place for an agent's declared capabilities
     * to be read wrongly on one path only — which is the failure least likely to
     * be noticed, since the two paths are rarely compared.
     *
     * <p><b>The one parameter is the fold, and it has to be passed rather than
     * inferred.</b> {@code entries.superseded_by} says a row is covered today
     * and never says since when, so {@code thatProjectFor} filters on it in SQL
     * and {@code thatProjectedAt} deliberately answers <em>with</em> covered
     * rows — the ones a later fold hid and an older turn could plainly see. Only
     * the caller knows which question was asked, so only the caller can say
     * whether the column still means anything by the time the rows get here.
     * {@link Projection.Superseded} carries the whole argument, and the defect
     * that argument was written from is this method having handed both readings
     * to a renderer that re-decided the fold itself and dropped the whole of an
     * old turn's history.
     *
     * @param shown the rows to read, from whichever question the caller asked
     * @param definition the agent whose declared capabilities decide the reading
     * @param superseded whether a covered row is still hidden here, which is the
     *     question the caller's store call already answered
     */
    private List<ChatMessage> projected(
            List<EntryRecord> shown, AgentDefinition definition,
            Projection.Superseded superseded) {
        return Projection.of(
                whatWasSaidAndWhatCameBack(shown, definition.canRedeem()), definition.canList(),
                superseded);
    }

    /**
     * The transcript this system keeps: what was said, and what came back as a
     * reference to itself — <b>for an agent that can redeem one</b>.
     *
     * <h2>Two readings, and which one an agent gets is not a preference</h2>
     *
     * <p>This used to <b>drop</b> two things together: every {@code TOOL_RESULT},
     * and every {@code ANSWER} of a turn but the last. The information stayed in
     * the log with every byte — nothing was ever deleted — and a model had no way
     * to reach it, so on the next turn it read the same file again and paid for
     * it again.
     *
     * <p>What replaces the drop is a <b>reference</b>: the same {@code tool}
     * message, in the same slot, against the same {@code tool_call_id}, with its
     * content replaced by {@link #REFERENCE} — the tool that ran, the size of
     * what it returned, and a handle {@code ResultTools.Read} redeems. The log
     * still holds every byte and the prompt now holds a pointer at it, so
     * <b>compaction stops being lossy</b>: the summary is a view, the log is
     * complete, and the row behind a seam is still redeemable.
     *
     * <p><b>That is only true where something can redeem, and {@code result_read}
     * is declared per agent.</b> An agent that uses tools and does not declare it
     * would be handed a reference it can never turn back into anything: the line
     * costs context, the answer that asked for the result has to be carried with
     * it, and nothing can come back. <b>Strictly worse than the drop</b>, which
     * cost nothing at all — and silent, because no shipped agent is in that state
     * and an operator writing their own definition would meet no boot error, no
     * failing test and no log line.
     *
     * <p>So the reading is chosen by {@link AgentDefinition#canRedeem()}:
     * {@link #withResultsReferenced} where a handle is worth something, and
     * {@link #withTheWorkingDropped} — the previous behaviour, unchanged — where
     * it is not. The floor is the old floor either way, which is what the spec
     * promised and what this restores.
     *
     * <h2>The second clause was not incidental, and this is what it cost</h2>
     *
     * <p>The dropped answers were <b>exactly the ones carrying tool calls</b>.
     * {@code JobRuntime} returns before recording anything when a completion has
     * no calls, so every answer it records has them and only {@code
     * Turn.closeTheLog}'s closing answer does not. Dropping both together is what
     * kept the message sequence balanced.
     *
     * <p><b>So keeping the results means keeping those answers.</b> A {@code
     * tool} message with no preceding {@code tool_calls} is a request endpoints
     * reject outright, and {@code ChatRequest} deliberately does not refuse it on
     * this side — see {@link Projection}, which was written for that failure. The
     * consequence is that on the referencing path <b>nothing is dropped at
     * all</b>: it is a substitution and no longer a narrowing.
     *
     * <p><b>That costs more than a reference line each</b>, and saying so is the
     * point. An assistant message reinstated there carries whatever the model
     * said alongside its calls <em>and the arguments of those calls</em> — a
     * whole file for a {@code file_edit}. The arithmetic that used to be able to
     * ignore all of it cannot: see {@link TurnTranscript#added()}, which asks
     * this same question so that what it counts is what the projection put there.
     *
     * <h2>What this does to {@link Projection#pairTheUnanswered}</h2>
     *
     * <p>Less to do on the referencing path, and exactly as much as before on the
     * other. A dying turn's last answer survives with every call it declared while
     * every result is dropped, so under {@link #withTheWorkingDropped} those calls
     * dangle and are paired with {@link Projection#NEVER_COMPLETED} — which is
     * what that sentence was written for and why the backstop is not dead code.
     * Under {@link #withResultsReferenced} the answered ones come back with their
     * references and only a genuinely unanswered call is paired. <b>The pairing
     * invariant holds on both, and neither relies on the backstop to hold it.</b>
     *
     * <h2>What has not changed</h2>
     *
     * <ul>
     *   <li><b>The projection rule still bounds from above.</b> An entry whose
     *       kind carries no role reaches nothing, and there is no site that can
     *       raise it. What this method decides is not <em>whether an entry may be
     *       shown</em> but <em>how much of its own history this consumer reads</em>.
     *   <li><b>A turn's working is still not replayed in full</b>, which is the
     *       load-bearing half of the old rule. {@link #SPAN_FRACTION}
     *       prices a turn at about 0.845 s more per further thousand tokens of
     *       standing history, and a reference is bounded where a result is not.
     *   <li><b>Nothing is written back.</b> The substitution happens on the way
     *       out; the row keeps its text, its order and its ordinal, and {@code
     *       EntryStore.forConversation} answers with what it always did. The row
     *       stays redeemable for an agent that could reach it, whichever reading
     *       this conversation's agent gets.
     * </ul>
     *
     * @param log one conversation's entries in conversation order, as {@code
     *     EntryStore.thatProjectFor} answers. Superseded entries are tolerated
     *     and not required: nothing here reads {@code supersededBy}, and a
     *     reference is built per result from the call that asked for it, which is
     *     in the same turn and therefore present or folded away with it
     * @param canRedeem whether the agent this history is being read for declares
     *     {@code result_read}, which is {@link AgentDefinition#canRedeem()} and
     *     is passed rather than re-derived so that no reader has to hold a
     *     definition it has no other use for
     * @return with {@code canRedeem}, the same records in the same order and the
     *     same number of them, with each result that is worth referencing
     *     replaced by a reference to itself; without it, the same records less
     *     the working
     */
    private static List<EntryRecord> whatWasSaidAndWhatCameBack(
            List<EntryRecord> log, boolean canRedeem) {
        return canRedeem ? withResultsReferenced(log) : withTheWorkingDropped(log);
    }

    /**
     * The substitution: every result that is worth referencing, replaced by a
     * reference to itself.
     *
     * <p>What {@link #whatWasSaidAndWhatCameBack} does for an agent that
     * declares {@code result_read}. Nothing is dropped — see that method's
     * javadoc for what keeping the results costs, and why the answers that asked
     * for them have to be kept too.
     */
    private static List<EntryRecord> withResultsReferenced(List<EntryRecord> log) {
        // Every call any answer declared, by id. One pass, because a result is
        // described by the call it answers and the log is one flat list: the
        // call is always earlier than its result, but nothing here depends on
        // that and a map costs the same either way.
        Map<String, ToolCall> asked = new HashMap<>();
        for (EntryRecord entry : log) {
            if (entry.kind() == EntryKind.ANSWER) {
                for (ToolCall call : entry.toolCalls()) {
                    asked.put(call.id(), call);
                }
            }
        }
        List<EntryRecord> transcript = new ArrayList<>(log.size());
        for (EntryRecord entry : log) {
            transcript.add(entry.kind() == EntryKind.TOOL_RESULT
                    ? referenced(entry, asked)
                    : entry);
        }
        return transcript;
    }

    /**
     * The drop: every {@code tool_result}, and every {@code answer} of a turn but
     * the last.
     *
     * <p>What {@link #whatWasSaidAndWhatCameBack} does for an agent that does not
     * declare {@code result_read}, and it is the behaviour this class had before
     * references existed — restored unchanged rather than approximated, because
     * the balance it keeps is exact.
     *
     * <h2>The two clauses are one decision and neither works alone</h2>
     *
     * <p>The dropped answers are <b>exactly the ones carrying tool calls</b>.
     * {@code JobRuntime} returns before recording anything when a completion has
     * no calls, so every answer it records has them and only {@code
     * Turn.closeTheLog}'s closing answer does not. Dropping the results without
     * the answers would leave a declared call with nothing answering it — a
     * request an endpoint refuses for a reason nothing in the message explains —
     * and dropping the answers without the results would leave a {@code tool}
     * message with no {@code tool_calls} above it, which is refused just as
     * hard. Together they leave a sequence that is balanced by construction.
     *
     * <p>That is not a guess about content. The last answer filed against a turn
     * is the turn's answer and every earlier one is a step on the way to it. A
     * turn that never reached {@code closeTheLog} — a run killed outright — has
     * no closing entry, so its last answer is a step, it keeps the calls it
     * declared, and {@link Projection#pairTheUnanswered} pairs them with {@link
     * Projection#NEVER_COMPLETED} rather than leaving a request no endpoint
     * accepts. <b>That is the one case on this path where the backstop fires,
     * and it is why it is still a backstop and not dead code.</b>
     *
     * @param log one conversation's entries in conversation order. Superseded
     *     entries are tolerated and not required: nothing here reads {@code
     *     supersededBy}, and the working this drops is decided per turn, so a
     *     turn is either wholly present or wholly folded away and the last answer
     *     of a turn is the same row either way
     * @return the same records in the same order, less the working
     */
    private static List<EntryRecord> withTheWorkingDropped(List<EntryRecord> log) {
        // The ordinal of the last answer filed against each turn. Built in one
        // pass because the answer that closes a turn is only knowable once the
        // turn's later entries have been seen, and the log is one flat list
        // rather than a list per turn.
        Map<Integer, Integer> cameTo = new HashMap<>();
        for (EntryRecord entry : log) {
            if (entry.kind() == EntryKind.ANSWER) {
                cameTo.put(entry.turnOrdinal(), entry.ordinal());
            }
        }
        List<EntryRecord> transcript = new ArrayList<>();
        for (EntryRecord entry : log) {
            boolean working = entry.kind() == EntryKind.TOOL_RESULT
                    || (entry.kind() == EntryKind.ANSWER
                            && cameTo.get(entry.turnOrdinal()) != entry.ordinal());
            if (!working) {
                transcript.add(entry);
            }
        }
        return transcript;
    }

    /**
     * One result, as a reference to itself — or unchanged, when a reference
     * would be the worse of the two.
     *
     * <h2>Three ways a result stays as it is, and each is the safe direction</h2>
     *
     * <ul>
     *   <li><b>Nothing declared it.</b> A reference names the tool that ran, and
     *       a result whose call is not in this list is one nothing here can
     *       describe. It cannot arise through {@code EntryStore.thatProjectFor} —
     *       a fold covers a whole turn, so a result and the answer that asked for
     *       it are present or absent together — but this method is a function of
     *       whatever list it is handed, and hiding content behind a line that
     *       cannot say what it is hiding is the one outcome worth refusing;
     *   <li><b>It has no handle.</b> Rows written before {@code
     *       V13__entry_handles.sql} have none, and a reference with nothing to
     *       redeem is strictly worse than the result: the context is spent and
     *       nothing can come back. Old conversations therefore keep behaving
     *       exactly as they did;
     *   <li><b>The reference is not smaller.</b> The threshold, and the reason
     *       there is no constant to argue about. See {@link #REFERENCE}.
     * </ul>
     *
     * <p><b>A new record and never an edit.</b> The row in {@code entries} keeps
     * every byte — that is the whole claim — so the substitution happens on the
     * way out, in the projection, and touches nothing anybody can read back.
     */
    private static EntryRecord referenced(EntryRecord result, Map<String, ToolCall> asked) {
        ToolCall call = asked.get(result.toolCallId());
        // An ejected payload has no content to measure or to promise, and
        // REFERENCE promises exactly the thing it no longer has -- "read the
        // whole of it back with result_read". Substituting that line for a
        // payload that has gone would be the harness telling a model something
        // untrue at the one moment the model could check it.
        //
        // NO PROJECTION CAN REACH ONE. Ejection happens to a tree whose root is
        // `ejected`, and Turn.requireActive refuses both an utterance and a
        // resumption in a conversation that is not active -- so a run whose log
        // holds an ejected row cannot be started. This clause is a guard against
        // dereferencing a null, not a decision about what to substitute; the
        // decision lives in ResultTools.Read, which is where a model actually
        // meets an ejected payload.
        if (call == null || result.handle() == null || result.content() == null) {
            return result;
        }
        String reference = REFERENCE.formatted(
                call.name(), result.content().length(), ResultTools.READ_NAME, result.handle());
        if (reference.length() >= result.content().length()) {
            return result;
        }
        // The timing travels with everything else, unchanged. What this method
        // substitutes is the CONTENT; when the result arrived and how long the
        // tool took are facts about the row, and a copy that dropped them would
        // make the projection a place a measurement can be lost.
        return new EntryRecord(result.conversationId(), result.ordinal(), result.kind(),
                reference, result.toolCallId(), result.toolCalls(), result.supersededBy(),
                result.handle(), result.turnOrdinal(), result.recordedAt(), result.tookMillis(),
                result.ejectedAt(), result.export());
    }

    /**
     * The transcript a run that stopped comes back to: what was said, and what
     * <em>this</em> run learned.
     *
     * <h2>Two readings of one log, and why the second one had to exist</h2>
     *
     * <p>{@link #whatWasSaidAndWhatCameBack} is right about a <em>later</em>
     * turn and wrong about a run continuing itself, and the difference is the
     * whole of this method. A later turn does not want a previous turn's file
     * reads in full: carrying them forward is paid on every turn after the one
     * that produced them, and a reference is what it gets instead. <b>A resumed
     * run is not a later turn.</b> It is the same run going on, and those tool
     * results are precisely what it learned — so a resumed run opened on the
     * ordinary reading would come back holding a handle for every file it read,
     * and would spend the new grant redeeming them one turn at a time, which is
     * a slower version of the failure resumption exists to prevent.
     *
     * <p><b>The turns before the one being continued are referenced rather than
     * dropped where the agent can redeem</b>, which is a real gain rather than a
     * side effect: such a resumed run can reach what an <em>earlier</em> turn
     * read as well, where before that was gone from its view entirely. For an
     * agent that cannot, that half is dropped as it always was — the earlier
     * half is read exactly as an ordinary turn reads it, and that is the whole
     * of what {@code canRedeem} decides here.
     *
     * <p><b>The ordinary reading is untouched and is still what everything else
     * gets.</b> This is a second function over the same log, not a relaxation of
     * the first: what keeps a conversation's context flat is load-bearing for
     * compaction, and the one caller that wants this run's own working back in
     * full asks for it by name.
     *
     * <h2>Where the line falls</h2>
     *
     * <ul>
     *   <li><b>Before {@code continuing}</b>: the ordinary transcript, results
     *       referenced or dropped on the agent's terms. Those turns are history
     *       to a resumed run exactly as they are to any other,
     *       a seam an earlier fold left is read out of this half like anything
     *       else in it, and nothing here changes what a fold did.
     *   <li><b>{@code continuing} itself</b>: every entry, in order — the
     *       utterance, every assistant turn, every call it declared and every
     *       result that came back. The utterance appears once, on the turn that
     *       carried it, and the resumed turn contributes none of its own.
     *   <li><b>After {@code continuing}</b>: nothing. Not a shape resumption
     *       produces — a grant continues a conversation's last turn — and
     *       dropped rather than trusted, because the one thing this must never
     *       do is hand a run a history in which the answer it is about to write
     *       has already been given.
     * </ul>
     *
     * <p><b>A dangling call is not this method's to repair.</b> {@link
     * Projection} pairs every unanswered call with {@link
     * Projection#NEVER_COMPLETED} whatever it is handed, so a history read here
     * is well formed by the time it reaches a request. The endings resumption
     * accepts stop at the top of {@code JobRuntime}'s loop, after the previous
     * iteration appended its results, so there is nothing for that repair to do
     * — which is the reason for the scope rather than a happy accident.
     *
     * @param log one conversation's entries in conversation order, as {@code
     *     EntryStore.thatProjectFor} answers. <b>That read is the right one and
     *     a wider one is not needed</b>: its predicate is {@code superseded_by IS
     *     NULL AND role IS NOT NULL}, and a {@code tool_result} carries the
     *     {@code tool} role, so every result the stopped turn got is already in
     *     it. What turns an earlier turn's results into references is {@link
     *     #whatWasSaidAndWhatCameBack}, one layer up, and this method is what
     *     declines to apply it to the turn being continued
     * @param continuing the ordinal of the turn being continued, which is the
     *     conversation's last
     * @param canRedeem whether the agent this history is being read for declares
     *     {@code result_read}, which decides the earlier half and nothing else:
     *     the turn being continued is read whole either way, because that is
     *     what resumption is
     * @return the same records in the same order, less the working of everything
     *     older than the turn being continued
     */
    static List<EntryRecord> whatWasSaidAndWhatTheRunLearned(
            List<EntryRecord> log, int continuing, boolean canRedeem) {
        List<EntryRecord> earlier = new ArrayList<>();
        List<EntryRecord> inTheRun = new ArrayList<>();
        for (EntryRecord entry : log) {
            if (entry.turnOrdinal() < continuing) {
                earlier.add(entry);
            } else if (entry.turnOrdinal() == continuing) {
                inTheRun.add(entry);
            }
        }
        List<EntryRecord> history =
                new ArrayList<>(whatWasSaidAndWhatCameBack(earlier, canRedeem));
        history.addAll(inTheRun);
        return history;
    }

    // --- what the decision no longer reads out of the conversation ----------------------
    //
    // There used to be two methods here and now there are none.
    //
    // `lastMeasured(spoken)` answered with the most recent turn's
    // `turns.prompt_tokens` -- the longest prompt of that turn -- and the fold
    // trigger compared it against the threshold. `headroom(spoken)` answered
    // with the largest increase in that same column from one measured turn to
    // the next, seeded by the first measured turn's whole cost, and the trigger
    // added it to what the turn sent.
    //
    // Both read `longestPrompt`, which is the turn's own tool traffic at FULL
    // SIZE -- and a later turn carries a bounded reference to each result rather
    // than the result, so `longestPrompt` over-states what survives by however
    // large the results were. Each caused the same unterminating loop from its
    // own side of the addition. Headroom's was the worse of the two, because it was a
    // running maximum over the whole conversation: one turn's working could seed
    // a number larger than the threshold itself, and nothing later could bring
    // it down. `a_conversation_whose_turns_each_read_a_file_does_not_fold_on_
    // every_turn` and `a_conversation_whose_first_turn_read_a_huge_file_does_
    // not_fold_for_ever_after` are the two loops, and they are what fails if
    // either quantity comes back.
    //
    // What the trigger reads now is the ended turn's own transcript -- its first
    // prompt and its last generation -- so nothing consults `turns` for a
    // measurement at all. The only thing that column is still read for is the
    // turn ordinals a fold reaches, which is not a measurement.
}
