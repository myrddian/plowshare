package io.aeyer.plowshare.server.agents;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Finite budgets for workspace controls, source snapshots and paused-owner renewal. */
@ConfigurationProperties(prefix = "plowshare.jobs.workspace")
public class WorkspaceRunProperties {
  private Duration sqlTimeout = Duration.ofSeconds(2);
  private Duration snapshotTimeout = Duration.ofSeconds(30);
  private Duration cancellationCheck = Duration.ofMillis(100);
  private long recoveryDelayMs = 1000;
  private Duration ownerLease = Duration.ofSeconds(30);

  private static Duration positive(Duration value) {
    if (value == null || value.toMillis() < 1 || value.compareTo(Duration.ofMinutes(10)) > 0)
      throw new IllegalArgumentException(
          "Workspace budgets must be positive and at most ten minutes");
    return value;
  }

  public Duration getSqlTimeout() {
    return sqlTimeout;
  }

  public void setSqlTimeout(Duration value) {
    sqlTimeout = positive(value);
  }

  public Duration getSnapshotTimeout() {
    return snapshotTimeout;
  }

  public void setSnapshotTimeout(Duration value) {
    snapshotTimeout = positive(value);
  }

  public Duration getCancellationCheck() {
    return cancellationCheck;
  }

  public void setCancellationCheck(Duration value) {
    if (positive(value).compareTo(Duration.ofSeconds(2)) > 0)
      throw new IllegalArgumentException(
          "Workspace cancellation checks must be within two seconds");
    cancellationCheck = value;
  }

  public long getRecoveryDelayMs() {
    return recoveryDelayMs;
  }

  public void setRecoveryDelayMs(long value) {
    if (value < 1 || value > 600_000)
      throw new IllegalArgumentException("Workspace recovery delay must be within ten minutes");
    recoveryDelayMs = value;
  }

  public Duration getOwnerLease() {
    return ownerLease;
  }

  public void setOwnerLease(Duration value) {
    if (positive(value).compareTo(Duration.ofSeconds(3)) < 0)
      throw new IllegalArgumentException("A workspace owner lease must be at least three seconds");
    ownerLease = value;
  }
}
