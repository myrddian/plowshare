package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.agents.Outcome;
import java.util.Objects;

/**
 * One row of {@code turns}: what was said, what came back, and what it cost.
 *
 * <h2>{@link Outcome.Ending} and not a string</h2>
 *
 * <p>The column holds the constant's own name, so a record carrying a {@code String} would be the
 * more literal translation of it — and it would move the failure {@code turns_ending_is_known}
 * exists to prevent out of this class and into every caller. An ending this server cannot read back
 * is a row written successfully and unreadable for ever; making the field the enum means the one
 * place that can produce that fault is the one place that parses it.
 *
 * <p><b>It is the second import of {@code server.agents} by {@code server.archive}, and it is the
 * same edge rather than a new one.</b> {@link ConversationRecord}'s javadoc records the first —
 * {@code Budget} — and names the tidy repair: neither type belongs to either package, since jobs,
 * delegation trees, conversations and now turns all share them. Moving them is a rename across
 * every signature that carries one and is still not this task's.
 *
 * <h2>{@code promptTokens} is a boxed {@code Integer} on purpose</h2>
 *
 * <p>Absent is a state this record has to be able to hold: an endpoint may omit {@code usage}
 * entirely, and {@code TokenUsage.UNKNOWN} is the type that already says so. A turn that never got
 * a completion back — one that ended {@code UNAVAILABLE} at its first call — is the other way to
 * arrive here with nothing. An {@code int} would make either absence a zero, which is the one value
 * {@code turns_prompt_tokens_are_a_measurement} refuses, because {@code agents.Compaction} is
 * decided from this number and a zero read as a measurement says the history is empty.
 *
 * <p>{@code V7__turns.sql} argued the nullability from a second fact as well — that nothing in this
 * server could supply the number at all — and that half is spent: {@code agents.Transcript} carries
 * it out of a run now. The half above is the whole of the reason today, and it was always the
 * load-bearing one.
 *
 * @param conversationId the conversation this was said in
 * @param ordinal where in that conversation it came; 1 is the first thing said. Assigned by {@link
 *     TurnStore#record} and never by its caller
 * @param utterance what the person said, in prose. Never blank
 * @param answer what the turn came to — {@code Outcome.text}, whatever the ending. Never null;
 *     blank only for an {@code ANSWERED} turn, which is a model that answered with nothing
 * @param ending which of the seven this turn reached. A turn that ended other than {@code ANSWERED}
 *     does not end the conversation; the transcript records which one it was and a person decides
 *     whether to go on
 * @param promptTokens what this turn's prompt cost by the model's own tokenizer, or {@code null}
 *     for a turn nobody measured. Never zero
 * @param agent which agent answered this turn, or {@code null} for a turn written before {@code
 *     V17__conversation_origin.sql}, when nothing recorded it.
 *     <p><b>A {@link String} and not an {@code AgentDefinition}</b>, which is the opposite choice
 *     from {@code ending} one line up and rests on the same reasoning read the other way. An ending
 *     is a closed set this server owns, so parsing it once here is what stops an unreadable row
 *     reaching every caller; an agent name is a file in a directory an operator controls, and a row
 *     naming an agent this boot no longer serves is an ordinary fact about history rather than a
 *     fault. Resolving it to a definition at read time would make reading a year-old transcript
 *     depend on the agent still existing.
 *     <p><b>NULL is exactly "written before V17"</b> and is not an unknown to be filled in: nothing
 *     anywhere records which agent answered those turns, so there is nothing to recover it from.
 *     {@code ConversationController.resume} reads this column and falls back to asking its caller
 *     for precisely those rows
 * @param systemBlock the name of the system block this turn actually went out with — the SHA-256
 *     {@link TurnStore#remember} gave it — or {@code null} for a turn that recorded none.
 *     <p><b>The digest and not the block.</b> This record is what {@code TurnStore.forConversation}
 *     answers a whole conversation with, and a shipped prompt is ~5 kB; carrying the text here
 *     would put it on every turn of every listing, which is the cost {@code
 *     V32__turn_system_prompt.sql} spends its header refusing to pay in the table. {@code
 *     TurnStore.blockSentAt} fetches the text for one turn, which is the only granularity anything
 *     reads it at.
 *     <p><b>NULL is "this turn did not record its block", and it is never filled in.</b> Every row
 *     written before {@code V32__turn_system_prompt.sql} is in that state permanently — the prompt
 *     text of a past turn is nowhere, and the agent's file today is a file today — and so is a turn
 *     whose block could not be stored, which {@code Compaction} logs and steps over. A backfill
 *     from today's definitions would assert that those turns were sent today's prompt, which is the
 *     one claim this column exists to stop making; V17's {@code agent} refused the same move for
 *     the same reason one field up
 */
public record TurnRecord(
    String conversationId,
    int ordinal,
    String utterance,
    String answer,
    Outcome.Ending ending,
    Integer promptTokens,
    String agent,
    String systemBlock) {

  /**
   * Rejects the nulls that have no meaning, and only those.
   *
   * <p>{@code promptTokens}, {@code agent} and {@code systemBlock} are legitimately absent and are
   * the only three that are. A null {@code answer} in particular would not be "it said nothing" —
   * the empty string is that, and {@code Outcome} is explicit that the two are different — and
   * letting one through would reach the row as a NOT NULL violation several layers from whoever
   * dropped it.
   */
  public TurnRecord {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(utterance, "utterance");
    Objects.requireNonNull(answer, "answer");
    Objects.requireNonNull(ending, "ending");
  }
}
