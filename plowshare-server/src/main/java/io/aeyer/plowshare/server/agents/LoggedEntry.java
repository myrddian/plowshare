package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.ToolCall;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One thing that happened in a run, on its way to being recorded.
 *
 * <h2>What this carries and what it deliberately does not</h2>
 *
 * <p>A kind, some text, and the two fields only one kind each needs. <b>No conversation and no
 * ordinal</b>: {@link JobRuntime} does not know which conversation it is running in — that link
 * lives in {@code Turn} — and an ordinal is {@code EntryStore}'s to assign from what the
 * conversation already holds, exactly as {@code TurnStore} assigns a turn's. So this is the half of
 * an entry a run can honestly produce, and the store completes it.
 *
 * <p>The factories are the whole of the type's surface. Each one builds the combination its kind
 * allows and no other, so a caller cannot file a tool result with no call id or hang tool calls off
 * a runtime note — the two shapes {@code entries_a_tool_result_is_exactly_what_answers_a_call} and
 * {@code entries_only_an_answer_asks_for_tools} refuse one layer down. A constructor taking four
 * loose fields would make the database the first thing to notice.
 *
 * @param kind what this is, and therefore whether it will ever reach a model
 * @param content the text. Never null; blank only for an {@link EntryKind#ANSWER}
 * @param toolCallId the call this result answers, or null for every other kind
 * @param toolCalls what the model asked to call. Never null; empty for anything that asked for
 *     nothing
 * @param tookMillis how long the operation that produced this took, in milliseconds, or null for an
 *     entry that records no operation and for one nobody measured. <b>Measured where the operation
 *     happened and never derived from when two entries were written</b>: an entry is written when
 *     something completes, so the gap between two of them holds everything that happened in
 *     between. {@code V16__entry_timing.sql} argues it, and {@code
 *     entries_only_a_completed_operation_is_timed} refuses one on a kind that performed nothing.
 *     Set with {@link #took}, never through a factory: the factories say what a thing IS, and how
 *     long it took is a fact the caller that bracketed it adds
 * @param invocation which model call produced this answer, or null for anything no model call
 *     produced and for an answer nobody attributed. Set with {@link #by}, for {@link #took}'s
 *     reason: what a thing is and where it came from are said at different sites. Never projected —
 *     see {@link Invocation}
 * @param speaker who spoke it — non-null only on an {@link EntryKind#UTTERANCE}, and null there
 *     when nobody said
 * @param outcome a tool result's outcome in a word, as the runtime told it; null for every other
 *     kind and for a tool result nobody told. Set with {@link #told}
 * @param salients an answer's calls' salient arguments by call id; never null, empty for anything
 *     but an answer that named one. Set with {@link #naming}
 */
public record LoggedEntry(
    EntryKind kind,
    String content,
    String toolCallId,
    List<ToolCall> toolCalls,
    Long tookMillis,
    Invocation invocation,
    Speaker speaker,
    String outcome,
    Map<String, String> salients) {

  public LoggedEntry {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(content, "content");
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    if (tookMillis != null && tookMillis < 0) {
      throw new IllegalArgumentException(
          "an operation cannot have taken " + tookMillis + " milliseconds");
    }
    if (invocation != null && kind != EntryKind.ANSWER && kind != EntryKind.REFUSAL) {
      // entries_only_a_model_says_something_by_a_call, one layer up.
      throw new IllegalArgumentException(
          "only an answer or a refusal is produced by a model call; this is a " + kind.wireName());
    }
    if (kind == EntryKind.REFUSAL && invocation == null) {
      // entries_a_refusal_names_its_call.
      throw new IllegalArgumentException(
          "a refusal is only ever written because a model call produced one, so it"
              + " has to say which");
    }
    if (speaker != null && kind != EntryKind.UTTERANCE) {
      throw new IllegalArgumentException(
          "only an utterance is spoken by someone; this is a " + kind.wireName());
    }
    if (outcome != null && kind != EntryKind.TOOL_RESULT) {
      throw new IllegalArgumentException(
          "only a tool result has an outcome; a "
              + kind.wireName()
              + " was told '"
              + outcome
              + "'");
    }
    if (outcome != null && outcome.isBlank()) {
      throw new IllegalArgumentException("an outcome is a word, never a blank one");
    }
    salients = salients == null ? Map.of() : Map.copyOf(salients);
    if (!salients.isEmpty() && kind != EntryKind.ANSWER) {
      throw new IllegalArgumentException(
          "only an answer names its calls' salient arguments; a "
              + kind.wireName()
              + " was given "
              + salients.size());
    }
  }

  /**
   * An entry with neither an outcome nor any salients — every caller that predates {@code
   * V62__trajectory_fields.sql}.
   */
  public LoggedEntry(
      EntryKind kind,
      String content,
      String toolCallId,
      List<ToolCall> toolCalls,
      Long tookMillis,
      Invocation invocation,
      Speaker speaker) {
    this(kind, content, toolCallId, toolCalls, tookMillis, invocation, speaker, null, null);
  }

  /**
   * An entry nobody said a speaker for — every kind but an utterance, and an utterance whose
   * speaker is unknown.
   */
  public LoggedEntry(
      EntryKind kind,
      String content,
      String toolCallId,
      List<ToolCall> toolCalls,
      Long tookMillis,
      Invocation invocation) {
    this(kind, content, toolCallId, toolCalls, tookMillis, invocation, null);
  }

  /** An entry that may have been timed and was not attributed to a model call. */
  public LoggedEntry(
      EntryKind kind,
      String content,
      String toolCallId,
      List<ToolCall> toolCalls,
      Long tookMillis) {
    this(kind, content, toolCallId, toolCalls, tookMillis, null);
  }

  /**
   * The four fields an entry has before anybody measured it.
   *
   * <p>Kept as a constructor of its own so that every caller that has nothing to time — which is
   * most of them, and every one that existed before {@code V16__entry_timing.sql} — says so by not
   * mentioning it. The alternative was a null argument at each of those sites, which is the shape
   * that makes an omission and a decision look the same.
   */
  public LoggedEntry(EntryKind kind, String content, String toolCallId, List<ToolCall> toolCalls) {
    this(kind, content, toolCallId, toolCalls, null, null);
  }

  /**
   * The same entry, carrying how long the operation that produced it took.
   *
   * <p><b>A wither and not a fifth parameter on every factory.</b> A factory says what a thing is
   * and is called from places that have nothing to measure — {@code Turn} builds an {@code answer}
   * out of an {@code Outcome} after the run is over — while the measurement belongs to whichever
   * caller bracketed the operation. Separating them is what keeps "nobody measured this" from being
   * spelled the same way as "this took no time".
   *
   * <p>Milliseconds, because that is the resolution of the things being measured and the resolution
   * the column keeps; a sub-millisecond operation is stored as zero, which {@code
   * entries_a_duration_is_not_negative} allows and {@code turns.prompt_tokens} pointedly does not —
   * see the migration for why the two columns say opposite things about that digit.
   *
   * @param took how long it ran. Never null and never negative
   * @throws IllegalArgumentException if the duration runs backwards, which is a caller that
   *     subtracted its two clock reads the wrong way round
   */
  public LoggedEntry took(Duration took) {
    Objects.requireNonNull(took, "took");
    return new LoggedEntry(
        kind,
        content,
        toolCallId,
        toolCalls,
        took.toMillis(),
        invocation,
        speaker,
        outcome,
        salients);
  }

  /**
   * The same answer, attributed to the model call that produced it.
   *
   * @throws IllegalArgumentException for any kind but an answer or a refusal
   */
  public LoggedEntry by(Invocation call) {
    Objects.requireNonNull(call, "call");
    return new LoggedEntry(
        kind, content, toolCallId, toolCalls, tookMillis, call, speaker, outcome, salients);
  }

  /**
   * This tool result, carrying the outcome the runtime told the record ({@code ToolLines}). Spec
   * 2026-09-29 §2.1: stored so a reader never has to guess it from a cut excerpt.
   */
  public LoggedEntry told(String outcome) {
    return new LoggedEntry(
        kind, content, toolCallId, toolCalls, tookMillis, invocation, speaker, outcome, salients);
  }

  /**
   * This answer, carrying each call's salient argument by call id. Spec 2026-09-29 §2.2: computed
   * here, from the whole arguments, because the page read cuts them.
   */
  public LoggedEntry naming(Map<String, String> salients) {
    return new LoggedEntry(
        kind, content, toolCallId, toolCalls, tookMillis, invocation, speaker, outcome, salients);
  }

  /**
   * What someone said, which opens a turn, and who said it. <b>The only door</b>: a speaker left
   * out would be a person's by default, and the log exists to say who spoke.
   *
   * @param speaker who said it, or null for nobody named — which reads as a person
   */
  public static LoggedEntry utterance(String content, Speaker speaker) {
    return new LoggedEntry(EntryKind.UTTERANCE, content, null, List.of(), null, null, speaker);
  }

  /**
   * What the model said and what it asked to call.
   *
   * <p>Both halves may be empty, and that is not the mistake {@code ChatMessage} refuses. An
   * assistant <em>message</em> with neither is a turn that did not happen; an assistant
   * <em>entry</em> with neither is a model that stopped on its first token, which {@code Outcome}
   * calls a decision and not a failure and {@code turns.answer} already stores. {@link Projection}
   * is what renders that absence rather than dropping it.
   */
  public static LoggedEntry answer(String content, List<ToolCall> toolCalls) {
    return new LoggedEntry(EntryKind.ANSWER, content == null ? "" : content, null, toolCalls);
  }

  /** What one tool returned, against the id of the call it answers. */
  public static LoggedEntry toolResult(String toolCallId, String content) {
    return new LoggedEntry(EntryKind.TOOL_RESULT, content, toolCallId, List.of());
  }

  /**
   * The nudge a run that has repeated itself is given. Recorded because it was delivered; never
   * projected, because projecting it would deliver it again.
   */
  public static LoggedEntry runtimeNote(String content) {
    return new LoggedEntry(EntryKind.RUNTIME_NOTE, content, null, List.of());
  }

  /**
   * A turn that was abandoned rather than answered, and why.
   *
   * <p>Written for the endings that mean <em>this turn's tool results were never appended</em>,
   * which is the case that puts a declared call with no result into the log. It is what an operator
   * reads to find out why; {@link Projection#NEVER_COMPLETED} is what a model reads, in the tool
   * slot, and the two are deliberately different sentences for different readers.
   *
   * @param ending which ending the turn reached, spelled as the constant is
   * @param sentence what the runtime said about it — {@code Outcome.text}
   */
  public static LoggedEntry attemptFailed(Outcome.Ending ending, String sentence) {
    return new LoggedEntry(
        EntryKind.ATTEMPT_FAILED, ending.name() + ": " + sentence, null, List.of());
  }

  /**
   * A probable refusal the agent's fallback was dispatched to answer.
   *
   * <p>Built with its call and never without one: {@link EntryKind#REFUSAL} exists only because a
   * model produced it, so the factory takes the {@link Invocation} rather than leaving {@link #by}
   * to be forgotten. Never projected; see the kind.
   *
   * @param content what the model said, verbatim
   * @param call the call that said it, judged {@link CompletionOutcome#REFUSED}
   */
  public static LoggedEntry refusal(String content, Invocation call) {
    Objects.requireNonNull(call, "call");
    return new LoggedEntry(EntryKind.REFUSAL, content, null, List.of(), null, call);
  }

  /**
   * What a fold put in place of the turns it covers. The summary itself and not the seam sentence:
   * {@link Projection} formats the seam, so the wording stays in one place.
   */
  public static LoggedEntry summary(String summary) {
    return new LoggedEntry(EntryKind.SUMMARY, summary, null, List.of());
  }

  /**
   * What a fold inside a turn put where the older steps were; see {@link EntryKind#TURN_SUMMARY}.
   * The summary itself, and not the sentence that introduces it.
   */
  public static LoggedEntry turnSummary(String summary) {
    return new LoggedEntry(EntryKind.TURN_SUMMARY, summary, null, List.of());
  }

  /**
   * The harness telling the model something happened -- an inbox arrival and the like. Unlike
   * {@link #diagnostic}, this is addressed to the model and projects as a user message, not a
   * system one; see {@link EntryKind#NOTICE} for why.
   */
  public static LoggedEntry notice(String content) {
    return new LoggedEntry(EntryKind.NOTICE, content, null, List.of());
  }

  /**
   * A hook's decision, as a non-projecting entry whose content is {@link
   * io.aeyer.plowshare.server.hooks.HookRecord#json()}.
   */
  public static LoggedEntry hook(io.aeyer.plowshare.server.hooks.HookRecord record) {
    return new LoggedEntry(EntryKind.HOOK, record.json(), null, List.of());
  }

  /**
   * Something the harness did to this conversation, written where the conversation can see it.
   *
   * <p><b>The first writer of {@link EntryKind#DIAGNOSTIC}</b>, whose javadoc said there was no
   * factory because "a factory with no caller is a shape invented for its own test". {@code
   * Compaction} is the caller: a fold now happens after the turn that triggered it has already
   * ended, on another thread, so the one place it was legible — a line in the server's own log,
   * beside every other run on the box — is now also the one place where its position in the
   * conversation is lost. Written here it sits between the entries it happened between.
   *
   * <p><b>It never reaches a model</b>, and that is enforced twice below this method rather than by
   * anything it does: {@link EntryKind#projects()} is false for a kind with no role, and {@code
   * entries_role_matches_kind} confines a roleless kind to a NULL role, which {@link Projection}
   * skips. This factory cannot ask for a role and no caller can give it one.
   *
   * <p>The content is free-form prose about the machinery, in the third person, addressed to
   * whoever reads the conversation back. It is not an event type with fields: nothing parses it,
   * and a shape nothing reads is a shape that goes stale.
   */
  public static LoggedEntry diagnostic(String note) {
    return new LoggedEntry(EntryKind.DIAGNOSTIC, note, null, List.of());
  }

  /**
   * What a model streamed as reasoning during one call. Never projected — see {@link
   * EntryKind#THINKING} — and, like {@link #diagnostic}, no caller can give it a role.
   */
  public static LoggedEntry thinking(String thought) {
    return new LoggedEntry(EntryKind.THINKING, thought, null, List.of());
  }
}
