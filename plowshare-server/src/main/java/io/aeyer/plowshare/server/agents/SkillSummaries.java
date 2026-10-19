package io.aeyer.plowshare.server.agents;

import java.util.function.BooleanSupplier;

/** Model-only SYSTEM preparation of an already materialized skill context. */
public interface SkillSummaries {
  /**
   * Summarizes historical data without folding or mutating the source log. The service selects its
   * own spending policy; the parent supplies source identity and usage provenance, never a task
   * allowance. Returns nonblank summary text, or throws on cancellation, invalid source, disabled
   * spending or unavailable inference. No automatic retry or context-mode fallback is performed.
   */
  String summaryForSkill(String source, Transcript parent, BooleanSupplier cancelled);
}
