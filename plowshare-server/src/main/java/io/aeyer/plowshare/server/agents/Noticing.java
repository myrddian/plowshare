package io.aeyer.plowshare.server.agents;

import java.util.Optional;

/**
 * The harness telling a bot something happened since the conversation last spoke, today only that
 * its speaker's user-inbox has new items. NOT Reminding: that seam is archive recall and is never
 * logged. A notice is logged, after the utterance, and never hidden, which costs no re-prefill
 * because it only extends the prefix.
 */
public interface Noticing {

  Noticing NONE = (definition, sessionId, conversation) -> Optional.empty();

  /** The notice for this turn, or empty. Must not throw. */
  Optional<String> noticeFor(AgentDefinition definition, String sessionId, String conversation);

  /** The per-speaker inbox tool, or empty when this turn has no speaker. */
  default Optional<AgentTool> inboxToolFor(AgentDefinition definition, String sessionId) {
    return Optional.empty();
  }
}
