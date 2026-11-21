package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;

/** Binds a tool-free reviewer to the existing execution's authority, allowance and accounting. */
public interface ReviewInferenceFactory {
  ReviewInference within(
      OperationAuthority.Admission operation,
      Budget budget,
      UsageAttribution usage,
      Runnable validity);

  /** Reviews quoted web evidence under the same limits as an ordinary SECURITY consult. */
  ReviewInference withinWeb(
      OperationAuthority.Admission operation,
      Budget budget,
      UsageAttribution usage,
      Runnable validity);
}
