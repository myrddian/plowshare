package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import java.util.Optional;

/**
 * What the archive already holds about the question a run is about to be asked, put in front of an
 * agent that did not ask for it.
 *
 * <h2>This is the reverse direction of {@link Learning}, and it is not the tool</h2>
 *
 * <p>{@code memory_recall} exists and an agent that wants memories can call it. <b>This is the
 * other half:</b> the system asking the archive on the agent's behalf, before the first model call,
 * so that a turn whose answer is already written down does not have to spend two model calls
 * discovering it. The measurement is {@code MemoryTools}' own — against qwen3.5-9b on 2026-08-29 a
 * run terminated as <em>recall, then read, then answer</em>, three turns — and what this removes is
 * the first of the three.
 *
 * <p><b>It does not replace the tool and must not be read as doing so.</b> The question this asks
 * is the person's own words and nothing else; an agent that has worked out a better question
 * halfway through a turn still has to ask it itself, and nothing here can. The relationship is a
 * floor, not a ceiling.
 *
 * <h2>Where the answer goes, which is the whole of the constraint</h2>
 *
 * <p>The owner's rule, measured rather than asserted: <em>"nah the memory would not be a prefix for
 * that reason - keep in the volatile space"</em>. Prefix caching here is extension-only with no
 * partial credit, so anything inserted ahead of the conversation changes the prefix on every turn
 * and buys a full cold prefill each time.
 *
 * <p>So an implementation's message is appended <b>last</b>, after the utterance, by {@code
 * JobRuntime.run} — and last is not merely late, it is the one position that costs nothing at all.
 * A turn's opening is {@code [system] [history] [utterance]}; the next turn's opening is {@code
 * [system] [history] [utterance] [what the turn added] [next utterance]}. Put this <em>before</em>
 * the utterance and the longest shared prefix between the two ends at the history, so the utterance
 * stops being cached. Put it after, and the shared prefix is {@code [system] [history] [utterance]}
 * exactly as it would have been with no reminder at all. {@code
 * JobRuntimeTest.a_reminder_is_the_last_message_and_leaves_every_message_before_it_untouched} is
 * the instrument.
 *
 * <p><b>Nothing here is written to the log</b>, for the reason {@link Projection} gives about
 * {@code NEVER_COMPLETED} and {@code JobRuntime} gives about the agent's own prompt: the log
 * records what happened in the conversation, and being reminded of an archive is not something the
 * conversation did. It is assembled at request time, from the archive as it stands at that moment,
 * and a later reader of the log sees the turn and not the reminder. That is a real cost — a memory
 * reached a model and no row says so — and it is paid at {@code INFO}, by id, in {@code
 * learner.Reminder}.
 *
 * <h2>An implementation must not throw</h2>
 *
 * <p>{@link Transcript#before()}'s rule and its reason. This is called on the job's own thread
 * before the first model call, and a run is not worth losing over an archive that could not be
 * asked. An implementation that cannot reach the archive says so in the log and answers empty,
 * which is the same answer an archive holding nothing gives.
 */
@FunctionalInterface
public interface Reminding {

  /**
   * A server that reminds nobody of anything.
   *
   * <p>{@link Learning#NONE}'s shape and its justification: a server with no archive wired, or one
   * that has switched automatic recall off, is a legal running server whose turns simply open the
   * way they always did. It is also what every fixture gets, which is what keeps this feature out
   * of the hundreds of tests that assert on a request's messages.
   */
  Reminding NONE = (definition, home, utterance) -> Optional.empty();

  /**
   * What this archive holds about what is about to be asked, as the one message that carries it, or
   * empty for nothing to say.
   *
   * <p><b>Empty is the ordinary answer and the important one.</b> An archive with nothing near the
   * question, an agent this does not apply to, and an archive that could not be reached all answer
   * the same way — and the request that follows is then byte-for-byte the request this server sent
   * before automatic recall existed. That is what makes the feature additive rather than a change
   * to every prompt in the system.
   *
   * @param definition the agent about to run. <b>The guardrail is here and not in any prose</b>:
   *     whether this agent has the archive in scope at all is a fact the loader validated, and an
   *     implementation reads it off the definition rather than deciding it
   * @param home the tier the run was started for, never chosen by a model
   * @param utterance what the run is about to be asked, verbatim — the query, because the person's
   *     own words are a fact the system holds and not a judgement it has to make
   * @return one message to append after the utterance, never a {@code SYSTEM} one — {@code
   *     JobRuntime.oneSystemMessageFirst} would hoist that to index zero, which is precisely the
   *     front of the prompt this must not reach
   */
  default Optional<ChatMessage> whatTheArchiveHolds(
      AgentDefinition definition,
      Home home,
      String utterance,
      io.aeyer.plowshare.server.llm.accounting.UsageAttribution owner) {
    return whatTheArchiveHolds(definition, home, utterance);
  }

  Optional<ChatMessage> whatTheArchiveHolds(
      AgentDefinition definition, Home home, String utterance);
}
