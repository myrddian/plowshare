package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;

/** Read authority for stored context, separate from human-only usage reports. */
public interface ConversationContextAccess {
  /**
   * Rechecks exact conversation ownership and current project read permission, and returns the
   * durable attribution for counting. The supplied identity must be an authenticated principal;
   * service token principals are not interchangeable with their owning account or sibling tokens.
   */
  UsageAttribution owner(String principal, String conversation);
}
