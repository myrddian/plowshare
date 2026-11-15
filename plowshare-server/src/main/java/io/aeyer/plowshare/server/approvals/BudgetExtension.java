package io.aeyer.plowshare.server.approvals;

/** A one-time additive grant. Spending is retained; steps apply to the requesting agent only. */
public record BudgetExtension(int calls, int steps) {
  public BudgetExtension {
    if (calls < 0 || steps < 0 || calls == 0 && steps == 0) {
      throw new IllegalArgumentException("A budget extension must add calls or steps");
    }
  }
}
