package io.aeyer.plowshare.server.agents.scribe;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;

/** Optional shape judgement over the exact inputs and claims supplied by an admitted write. */
public interface MemoryJudgements {
  /** Optional model failures file flat; invalid live authority must propagate to the writer. */
  Scribe.Judgement judge(
      MemoryProposal proposal, Home home, UsageAttribution attribution, Scribe.Consult consult);
}
