package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.agents.Speaker;

/**
 * How a question or an ending is spoken into the caller's own conversation: {@code Turn.speak} and
 * {@code Turn.isSpeaking}, behind a seam. Used by {@code Delivery}.
 */
public interface CallerVoice {

  boolean isSpeaking(String conversation);

  /**
   * Starts a turn in the caller's conversation, spoken by {@code callerAgent}.
   *
   * @param speaker who the utterance is from — {@code orchestration <id>}
   * @throws io.aeyer.plowshare.server.agents.Turn.Refused as {@code Turn.speak} does, and for an
   *     agent that no longer resolves
   */
  void speak(String conversation, String callerAgent, String utterance, Speaker speaker);
}
