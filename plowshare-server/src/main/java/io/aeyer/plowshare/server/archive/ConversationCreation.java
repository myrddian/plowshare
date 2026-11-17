package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.TurnCap;
import java.util.Optional;

/**
 * Ordinary conversation persistence used by alias transactions. Implementations join the calling
 * Spring transaction; creation includes its canonical allowance and never invokes lifecycle hooks.
 * Reading a snapshot grants no access and does not reserve its allowance or execution slot.
 */
public interface ConversationCreation {
  ConversationRecord open(Home home, Budget budget, TurnCap turnCap, String owner);

  Optional<ConversationRecord> find(String conversation);
}
