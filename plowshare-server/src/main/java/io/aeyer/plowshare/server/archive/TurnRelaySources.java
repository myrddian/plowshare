package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.util.Optional;

/** Retained, authenticated effect ancestry for an exact accounting run and reserved turn. */
public interface TurnRelaySources {
  /**
   * Returns ancestry only when project, account, definition, run and conversation/turn match
   * retained accounting ownership. Message turns never borrow another turn's conversation-wide
   * root. Absence is unavailable provenance, not permission to begin a fresh root.
   */
  Optional<RelayCausation> ancestry(long project, UsageAttribution owner);
}
