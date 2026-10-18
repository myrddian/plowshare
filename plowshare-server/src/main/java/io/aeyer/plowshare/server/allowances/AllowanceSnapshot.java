package io.aeyer.plowshare.server.allowances;

import java.util.Objects;

/**
 * Unlimited is an absent ceiling; zero disables calls. Historical overspending stays observable.
 */
public record AllowanceSnapshot(AllowanceId id, Integer ceiling, int spent) {
  public AllowanceSnapshot {
    Objects.requireNonNull(id);
    if (spent < 0 || ceiling != null && ceiling < 0)
      throw new IllegalArgumentException("Invalid allowance snapshot");
  }
}
