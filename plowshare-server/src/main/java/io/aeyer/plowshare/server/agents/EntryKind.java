package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import java.util.Optional;

/**
 * What a logged entry is, and — as a consequence and never as a separate decision — whether a model
 * ever sees it.
 *
 * <h2>The projection rule lives on this enum</h2>
 *
 * <p>An entry is <b>recorded</b>. Whether it reaches a model is a property of its kind, not
 * something decided when a request is built. That is the whole design: {@link Projection} asks each
 * entry's kind for a role and builds a message when there is one, so there is no place in the
 * codebase where somebody can decide, for one entry, to include it anyway.
 *
 * <p>Three kinds are the three OpenAI-shaped roles a conversation can carry — {@link #UTTERANCE},
 * {@link #ANSWER}, {@link #TOOL_RESULT}. {@link #SUMMARY} is a fourth and it is the harness
 * speaking: a fold's seam is neither the person nor the model, and {@code Compaction} has
 * introduced one as a system message since before this log existed. {@link #NOTICE} and {@link
 * #TURN_SUMMARY} are the harness speaking in the person's role, each argued where it is declared.
 * The rest are recorded and invisible.
 *
 * <p><b>The agent's own system prompt is not a kind and is not in the log.</b> It is assembled at
 * request time from the agent definition — {@code JobRuntime.oneSystemMessageFirst} — because it
 * belongs to the agent and not to the conversation. A log that carried it would let a definition
 * change silently disagree with what a past request contained, and would make replaying an old
 * conversation under a new prompt impossible. A {@link #SUMMARY} is not a counter-example: it is a
 * fact about <em>this conversation's</em> history, produced by folding it, and it happens to be
 * delivered in the system slot.
 *
 * <h2>An unclassified kind does not project, and that is the guarantee</h2>
 *
 * <p>{@link #role()} answers empty for anything that is not one of those, so a constant added here
 * without a decision about what a model should see is <em>invisible</em> rather than accidentally
 * visible. The failure mode of forgetting is a missing message, which is loud, rather than a leaked
 * one, which is not.
 *
 * <p>The same rule is written into the schema. {@code entries_role_matches_kind} derives the role
 * column from the kind with a {@code CASE} that has no {@code ELSE}, so a kind it does not name can
 * only be stored with a NULL role — and a NULL role is exactly what the projection skips. The net
 * is the constraint and the constraint is the net.
 *
 * <p><b>Nothing holds this enum and {@code entries_kind_is_known} together at compile time</b>,
 * which is the situation {@code Outcome.Ending} and {@code turns_ending_is_known} are already in
 * and is worth restating rather than discovering. A constant added here is not a compile error
 * against the schema; it is a row Postgres refuses at the moment a real conversation reaches it.
 * {@code EntryStoreTest.every_kind_this_server_can_write_is_an_entry_this_table_holds} enumerates
 * {@link #values()} and writes one of each, so the build that adds a constant without a migration
 * is what fails.
 */
public enum EntryKind {

  /** What a person said. Becomes {@code ChatMessage.user(content)}. */
  UTTERANCE("utterance", ChatMessage.Role.USER),

  /**
   * What the model said and what it asked to call. Becomes {@code ChatMessage.assistant(content,
   * toolCalls)}.
   *
   * <p>A turn writes several: one per assistant message the loop appends to its history, and one
   * more for what the turn came to. Either half may be empty and both may be — an answer that is
   * entirely tool calls carries no content, and a model that stopped on its first token carries
   * neither, which {@code Outcome} permits and {@link Projection} renders rather than drops.
   */
  ANSWER("answer", ChatMessage.Role.ASSISTANT),

  /**
   * What one tool returned, against the id of the call it answers. Becomes {@code
   * ChatMessage.tool(toolCallId, content)}.
   */
  TOOL_RESULT("tool_result", ChatMessage.Role.TOOL),

  /**
   * What a fold put in place of the turns it covers. Becomes {@code ChatMessage.system(SEAM)} — the
   * seam sentence, not the bare summary.
   *
   * <p>The seam is said out loud because a model shown a summary as if it were the conversation
   * cannot tell it is reading a compression of one, and will answer about the gaps with the same
   * confidence as about the rest. {@code Compaction.SEAM} is the wording and {@link Projection}
   * formats it; what is stored is the summary itself, so the entry holds the fact and the sentence
   * that introduces it stays in one place.
   */
  SUMMARY("summary", ChatMessage.Role.SYSTEM),

  /**
   * What a fold <b>inside a turn</b> put in place of the older steps of that turn — and of any
   * earlier turns no fold stood for yet (spec 2026-09-30-fold-at-60-and-80 §1–§2). Becomes {@code
   * ChatMessage.user(TURN_SEAM)}: the summary, introduced as the harness's summary of earlier work
   * in this turn.
   *
   * <p><b>{@code USER} and not {@code SYSTEM}, and where it stands is the reason.</b> A
   * between-turn {@link #SUMMARY} stands for whole turns before everything still shown, so it
   * belongs at the front and is merged into the one system message there. This one stands for steps
   * <em>inside</em> a turn, between the turn's opening request — which is kept word for word — and
   * the most recent steps, which are kept whole; it has to be read in that place, and a system
   * message anywhere but first is a request the reference model refuses ({@code
   * JobRuntime.oneSystemMessageFirst}). A {@link #NOTICE} is the harness speaking in the person's
   * role for the same reason, and this is the same move.
   *
   * <p><b>It is appended, so it sorts after the steps it stands in front of</b>, and {@link
   * Projection} moves it: in-turn summaries are rendered, in the order they were written,
   * immediately before the first step of their turn still standing. The rows it covers point at it
   * through {@code superseded_by} exactly as a between-turn fold's do.
   */
  TURN_SUMMARY("turn_summary", ChatMessage.Role.USER),

  /**
   * The harness telling the model something happened, e.g. inbox arrivals. Logged, never hidden.
   * Becomes {@code ChatMessage.user(content)}.
   *
   * <p><b>{@code USER} and not {@code SYSTEM}, though a notice is the harness speaking exactly as a
   * {@link #SUMMARY} seam is.</b> Measured against this server's own {@code ChatRequest}: every
   * shipped agent carries a non-blank prompt (a blank one is refused at load), so {@code opening}
   * always seats that prompt as message zero, {@code SYSTEM}. A notice that also projected {@code
   * SYSTEM} would be a second system message on every turn it fires — which {@code
   * ChatRequest.requireAtMostOneSystemMessageAndItFirst} refuses outright — and folding it into
   * message zero to dodge that refusal rewrites the very first byte of the prompt on the turn it
   * arrives, which is exactly the non-extension prefix cost {@link Reminding}'s own placement rule
   * exists to avoid. {@code USER}, appended after the utterance, is what {@code Reminding} already
   * does for the identical reason — it is a message this run adds and never rewrites — and a logged
   * one behaves the same way on every later turn: {@code before()} carries it in place, so a turn
   * N+1 that also gets no fresh notice sends exactly turn N's messages plus its own new ones, a
   * strict extension of the cached prefix.
   */
  NOTICE("notice", ChatMessage.Role.USER),

  /**
   * A model call that was refused or abandoned, and the ending the turn reached because of it.
   *
   * <p><b>Recorded because it is a fact about the run</b> — the fourth problem this log was built
   * for is that failed attempts are excluded by accident rather than by construction, and a turn
   * that stopped inside a tool call is the case that matters most: it is why the calls above it
   * have no results.
   *
   * <p><b>It must not project, because it never reached a model.</b> Replaying it would invent
   * history — a conversation in which the harness told the model that a call had been abandoned,
   * which is a thing that never happened. What the model reads about an abandoned call is {@link
   * Projection#NEVER_COMPLETED}, in the tool slot where it looks for that call's answer, and that
   * is a different sentence in a different place for a different reader.
   */
  ATTEMPT_FAILED("attempt_failed"),

  /**
   * The nudge {@code JobRuntime.Repeats} writes when a run has called the same tool with the same
   * arguments several times over.
   *
   * <p><b>Recorded because it is a real event</b>: it went into that run's history as a user
   * message and the model read it.
   *
   * <p><b>It must not project</b> because it was already delivered in-run. Projecting it would put
   * it in front of the model a second time, in a later turn, about a repetition that stopped
   * several turns ago.
   */
  RUNTIME_NOTE("runtime_note"),

  /**
   * An externalised plan.
   *
   * <p><b>Recorded, and the shape is reserved rather than built.</b> A plan needs somewhere to live
   * that is not the transcript, because the transcript is what compaction folds away — so the kind
   * exists and is classified, and what a plan <em>is</em> is a separate design. Nothing in this
   * server writes one yet.
   *
   * <p><b>It must not project</b>: a plan is read deliberately, by whatever asks for it, and not
   * carried in every request.
   */
  PLAN("plan"),

  /**
   * Something the harness noticed about the conversation itself — a compaction being triggered, and
   * whatever else is later worth writing down about the machinery rather than about what was said.
   *
   * <p><b>Recorded because the log is the only place these events are a fact about a particular
   * conversation.</b> They go to the server's own log today, where they are a line in a stream
   * shared with every other run and are gone when the file rolls. Here they sit between the entries
   * they happened between, so a person reading a conversation back can see the machinery act on it
   * at the moment it acted.
   *
   * <p><b>It must not project, and this kind is the one where that is the whole point rather than a
   * consequence.</b> The others are invisible because replaying them would be dishonest — an
   * attempt that never reached a model, a nudge already delivered. This one is invisible because it
   * is not part of the conversation at all: it is the harness talking about the conversation, and a
   * model shown it would be reading its own plumbing as though somebody had said it. A {@link
   * #SUMMARY} is not a counter-example; it stands in for turns that really were said, which is why
   * it is the one harness voice a model hears.
   *
   * <p><b>{@code Compaction} is the first writer</b>, and it became one for a reason worth
   * recording: a fold now happens after the turn that triggered it has ended, on a thread of its
   * own, so the server's own log is both the only place it was written down and the one place its
   * position in the conversation is lost. {@code LoggedEntry.diagnostic} exists now that there is a
   * caller for it — a factory with no caller is a shape invented for its own test, which is the
   * situation {@link #PLAN} is still in.
   */
  DIAGNOSTIC("diagnostic"),

  /**
   * What a model streamed as reasoning during one call, in order and whole.
   *
   * <p><b>A fact about the call, never part of the conversation.</b> No role, so it never projects:
   * a later call is sent what was said and what the tools returned, and not what a model thought on
   * the way. It sits in the log directly before the answer or refusal of the call that thought it.
   *
   * <p>Recorded because an endpoint may stream reasoning and count it as none — gpt-oss-120b
   * reported {@code reasoning_tokens: 0} beside 3 199 completion tokens for an answer of about 2
   * 400 — and the thinking itself is then the only record of what the call spent.
   */
  THINKING("thinking"),

  /**
   * A model's probable refusal that the agent's fallback was dispatched to answer in its place.
   *
   * <p><b>Recorded because it happened</b>: a model call was made, took time and tokens, and came
   * back declining the task. {@code RefusalDetector} judged it and the agent's own {@code
   * fallback:} allowed a reroute, and this row is what an operator reads to find both — with the
   * model that refused attributed on it, V39's columns.
   *
   * <p><b>It must not project, and that is what makes a fallback's answer the conversation rather
   * than a second opinion on one.</b> The person asked, and the assistant answered: the next
   * request carries the utterance and the fallback's answer, one ordinary assistant message, with
   * the prefix up to the utterance exactly what the refused call was sent. A model shown the
   * refusal as well would be reading two assistant turns for one question, one of them declining
   * what the other did.
   *
   * <p><b>A refusal that is not rerouted is not this kind.</b> An agent with no {@code fallback:}
   * leaves its refusal as what the assistant said, and a fallback that refuses in turn leaves its
   * own; either is an {@link #ANSWER} whose provenance says {@code refused}. This kind is reserved
   * for the refusal something else was asked to answer. {@code Rerouting} owns which is which.
   */
  REFUSAL("refusal"),

  /**
   * A hook's decision: which hook, which stage, what it added, denied, rewrote or redacted, and how
   * long it took. Written for every hook call and every harness step that transforms a request,
   * including the ones whose effect the model never sees again — see {@code HookRecord}.
   *
   * <p>No role, so it never projects: recording a volatile addition must not bring it back into a
   * later turn, which is the whole difference between volatile and durable.
   */
  HOOK("hook");

  private final String wireName;
  private final ChatMessage.Role role;

  EntryKind(String wireName) {
    this(wireName, null);
  }

  EntryKind(String wireName, ChatMessage.Role role) {
    this.wireName = wireName;
    this.role = role;
  }

  /**
   * The spelling the {@code kind} column holds.
   *
   * <p>Its own string and not {@code name().toLowerCase()}, for the reason {@code ChatMessage.Role}
   * and {@code Lane} each have one: the stored spelling is a schema fact and must not follow a
   * rename of the constant. {@code TOOL_RESULT} would silently become {@code tool_result} either
   * way today, and that agreement is exactly what makes the derived version look safe until
   * somebody renames the constant.
   */
  public String wireName() {
    return wireName;
  }

  /**
   * Which chat role this kind becomes when projected, or empty for a kind that does not reach a
   * model.
   *
   * <p>An {@link Optional} and not a nullable field, because every caller has to answer the
   * question and a null here is the one that would be answered by accident. There is exactly one
   * caller — {@link Projection} — and the whole of the projection rule is that it builds a message
   * when this is present and records nothing when it is not.
   */
  public Optional<ChatMessage.Role> role() {
    return Optional.ofNullable(role);
  }

  /**
   * Whether an entry of this kind reaches a model at all. The same question {@link #role()}
   * answers, asked where the answer is the only thing wanted.
   */
  public boolean projects() {
    return role != null;
  }

  /**
   * The kind a stored spelling names.
   *
   * @throws IllegalArgumentException if nothing has that spelling — a row this server cannot read
   *     back, which {@code entries_kind_is_known} exists to make unwritable. It is thrown rather
   *     than answered as an empty optional for the reason {@code TurnRecord} gives about {@code
   *     Ending.valueOf}: the one place that parses it is the one place that can produce the fault,
   *     and a caller handed an "unknown" constant would have to invent a policy for it
   */
  public static EntryKind of(String wireName) {
    for (EntryKind kind : values()) {
      if (kind.wireName.equals(wireName)) {
        return kind;
      }
    }
    throw new IllegalArgumentException(
        "no entry kind is spelled '"
            + wireName
            + "'; this row was written by something"
            + " that knows a kind this build does not");
  }
}
