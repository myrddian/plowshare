package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import java.util.List;

/**
 * The message list a conversation's next prompt would carry, or the one a turn it has already had
 * was shown — {@code Compaction.projectionFor} and {@code Compaction.projectionAsOf}, told apart on
 * the answer by {@link #turn}.
 *
 * <h2>What "next" means here, and what it does not promise</h2>
 *
 * <p><b>With no {@link #turn}, this is not a record of what an earlier turn was sent.</b> It is
 * recomputed on every read, from the agent definition as it stands at the moment of the read —
 * {@link ConversationController#projection}'s own javadoc carries the argument in full, and {@link
 * #agent} is what says, on the wire, which definition this particular answer was computed against.
 * An agent's prompt file can change between turns, so two reads of the same conversation a minute
 * apart can honestly disagree if somebody edited the file in between; that is not a bug in this
 * view, it is what "computed now" means.
 *
 * <h2>Three fields of provenance, and each answers a different question</h2>
 *
 * <p>{@link #agent} says whose prompt this is; {@link #turn} says which moment it was computed as
 * of, or that it is the next prompt; {@link #systemBlockAsSent} says whether the block at the front
 * is a recording or today's file. <b>A response missing any one of them is one a reader has to
 * guess about</b>, and two responses held side by side are indistinguishable — which is the state
 * this view was in before V32, and the reason the last two fields exist.
 *
 * <h2>Role and content, and nothing else off {@code ChatMessage}</h2>
 *
 * <p>A {@link ChatMessage} also carries {@code toolCalls} and {@code toolCallId} — the correlation
 * that keeps a request well formed — and neither crosses onto this view. What a person opening a
 * turn wants to see is what the model was shown, in the order it was shown it; the wiring that
 * makes a well-formed request out of that is this server's problem and not a fact this screen
 * exists to display twice, alongside the tool detail {@code EntryView} already carries for the same
 * rows on the trajectory's own terms.
 *
 * @param agent which agent's definition this projection was computed against — the caller's own
 *     choice, or the one {@link ConversationController} resolved when the caller named none.
 *     Carried on every answer because the system message in {@link #messages} is that agent's
 *     prompt, and a reader comparing two projections of the same conversation needs to know whether
 *     they were computed against the same agent before comparing anything else
 * @param turn the turn this was computed as of, or {@code null} for the conversation's next prompt
 *     — the two questions {@code GET /v1/conversations/&#123;id&#125;/projection} answers, told
 *     apart on the answer rather than only on the request.
 *     <p><b>Null and never zero.</b> There is no turn zero — {@code turns_are_numbered_from_one} —
 *     so a zero would be a turn number that cannot exist standing in for the absence of one, which
 *     is the collapse {@code TurnRecord.promptTokens} refuses one layer down for the same reason.
 *     The absence is the meaningful state: it is the next prompt, and no turn has been sent it.
 *     <p>It exists because a response did not say it. {@link #agent} carried half of a projection's
 *     provenance and nothing carried the other, so two responses saved from the same conversation —
 *     one of turn 3, one of the next prompt — were the same shape with no field distinguishing them
 * @param systemBlockAsSent whether the system message at the front of {@link #messages} is built
 *     from the block the turn was <em>actually</em> sent.
 *     <p><b>True only when this is a turn's projection and that turn recorded its block</b> —
 *     {@code turns.system_block}, which {@code V32__turn_system_prompt.sql} added and which no turn
 *     written before it has. <b>False means the block is the agent's prompt as the file stands
 *     now</b>, which is the honest answer for a turn that recorded none and the only available one
 *     for the whole of the archive that predates the column. It is always false with no {@link
 *     #turn}: the next prompt has not been sent to anybody, so there is nothing for it to be
 *     as-sent as.
 *     <p><b>It is a statement about the system block alone, and it is not the only thing on this
 *     response that is not a recording.</b> The <em>rows</em> under the block are the ones that
 *     turn could see either way — {@code EntryStore.thatProjectedAt} reconstructs precisely those —
 *     so a false here narrows what is uncertain about the block to the block. What no field here
 *     covers is the agent's {@code tools:}, which nothing records per turn: a fold's seam is worded
 *     from today's {@code result_list} and an older tool result is shown in full or as a reference
 *     from today's {@code result_read}, so an agent whose tool list changed since renders two
 *     things differently from the way that turn was sent them. {@code
 *     ConversationController.projection} carries that gap in full. A true here means the block was
 *     recorded; it does not mean the whole message list is byte-for-byte the request that went out
 * @param messages the assembled list, system message first, in the order a request would carry
 *     them. Never empty for a conversation that has had a turn: the agent's own prompt is folded
 *     into the system slot even when the conversation's own history is
 */
public record ProjectionView(
    String agent, Integer turn, boolean systemBlockAsSent, List<Message> messages) {

  /**
   * The conversation's next prompt: no turn, and nothing was sent this.
   *
   * <p>{@code systemBlockAsSent} is false here as a statement and not as a default. A projection of
   * what a turn <em>would</em> carry cannot be a recording of what one was sent, so the honest
   * value is the one that says the block came from the file as it stands now.
   */
  public static ProjectionView of(String agent, List<ChatMessage> messages) {
    return new ProjectionView(agent, null, false, said(messages));
  }

  /**
   * One turn's projection, carrying the turn it is of and whether its block is a recording.
   *
   * <p>Both facts come from {@code Compaction.projectionAsOf}, which is the one place that knows
   * whether the turn had a block, so this view never infers either of them from the messages it was
   * handed.
   */
  public static ProjectionView asOf(String agent, int turn, Compaction.Shown shown) {
    return new ProjectionView(agent, turn, shown.systemBlockAsSent(), said(shown.messages()));
  }

  private static List<Message> said(List<ChatMessage> messages) {
    return messages.stream().map(Message::of).toList();
  }

  /**
   * One message, reduced to what a reader of a turn wants from it: who it is from and what it says.
   *
   * @param role the wire spelling — {@code system}, {@code user}, {@code assistant} or {@code tool}
   *     — from {@link ChatMessage.Role#wireName()}, so this reads the same vocabulary a request on
   *     the wire does
   * @param content what the message says. Blank for an assistant message that is entirely tool
   *     calls, which is a real shape and not an omission — see {@link ChatMessage}
   */
  public record Message(String role, String content) {

    static Message of(ChatMessage said) {
      return new Message(said.role().wireName(), said.content());
    }
  }
}
