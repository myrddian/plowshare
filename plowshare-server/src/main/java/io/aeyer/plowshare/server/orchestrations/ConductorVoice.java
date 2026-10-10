package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Speaker;
import java.util.function.Consumer;

/**
 * How the engine speaks to a conductor: {@code Turn.speakToConductor}'s 6-argument door, behind a
 * seam a test can replace without a model.
 */
public interface ConductorVoice {

  /**
   * Whether the conductor's conversation has a turn in flight right now — {@code Turn.isSpeaking}.
   * A turn already driving the run is why an ending that would otherwise nudge or restart it does
   * neither.
   */
  boolean isSpeaking(String conversation);

  /**
   * Starts a turn in the conductor's own conversation.
   *
   * @param sessionId the caller's session while it is live, or {@code null} — Decision 2
   * @param maxModelCalls the conversation's new model-call budget total, or {@code null} to leave
   *     it alone; a number only when a caller raised a call-budget cap — Decision 7
   * @param ended told the turn's outcome, once
   * @return the job id
   * @throws io.aeyer.plowshare.server.agents.Turn.Refused as {@code speakToConductor} does
   */
  String speak(
      String conversation,
      AgentDefinition conductor,
      String utterance,
      String sessionId,
      Integer maxModelCalls,
      Consumer<Outcome> ended);

  /** The first turn may name its immediate external source instead of a generic harness. */
  default String speakFrom(
      String conversation,
      AgentDefinition conductor,
      String utterance,
      String sessionId,
      Integer maxModelCalls,
      Consumer<Outcome> ended,
      Speaker source) {
    return speak(conversation, conductor, utterance, sessionId, maxModelCalls, ended);
  }

  /**
   * Carries a conductor's sub-agent on in its own conversation after the person answered the
   * approval it stopped on — {@code Turn.speakToDelegate}, spec 2026-09-26 §4. On the conductor's
   * budget, with the conductor's conversation held while it runs.
   *
   * @param child the sub-agent's delegation conversation
   * @param agent the sub-agent's name, resolved as {@code agent_run} resolved it
   * @param callerHandle the account the resumed run acts for, so a second question it raises has
   *     somebody to be asked
   * @param ended told the sub-agent's outcome, once, after both conversations are free
   * @return the job id
   * @throws io.aeyer.plowshare.server.agents.Turn.Refused if the agent no longer resolves, or as
   *     {@code speakToDelegate} does
   */
  String resumeDelegate(
      String child,
      String agent,
      String conductorConversation,
      String utterance,
      String callerHandle,
      Consumer<Outcome> ended);

  /** The retained orchestration session is revalidated and inherited by the resumed delegate. */
  default String resumeDelegate(
      String child,
      String agent,
      String conductorConversation,
      String utterance,
      String callerHandle,
      String session,
      Consumer<Outcome> ended) {
    return resumeDelegate(child, agent, conductorConversation, utterance, callerHandle, ended);
  }
}
