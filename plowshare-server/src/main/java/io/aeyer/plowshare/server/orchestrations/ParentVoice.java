package io.aeyer.plowshare.server.orchestrations;

/** Speaking a child's question or ending into its parent conductor's own conversation. */
public interface ParentVoice {

  /** Whether this run is a live parent that can be spoken to as a conductor. */
  boolean isLiveParent(String orchestration);

  /**
   * Wake it if it was waiting, then speak. Refuses by throwing Turn.Refused, as CallerVoice does.
   *
   * @throws io.aeyer.plowshare.server.agents.Turn.Refused as {@code Orchestrations.speakToParent}
   *     refuses: the row moved on, its pinned definition no longer builds a conductor, or the turn
   *     itself was refused
   */
  void speak(String parentOrchestration, String utterance);

  /**
   * Whether the parent's own CONDUCTOR conversation has a turn in flight — the conversation {@link
   * #speak} speaks into, never the conversation that started the parent. A run with no row is not
   * speaking.
   */
  boolean isSpeaking(String parentOrchestration);
}
