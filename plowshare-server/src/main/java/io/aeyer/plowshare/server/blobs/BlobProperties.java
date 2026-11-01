package io.aeyer.plowshare.server.blobs;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cleanup is explicit deployment opt-in; no independent storage root overrides DataLayout. */
@ConfigurationProperties(prefix = "plowshare.blobs")
public class BlobProperties {
  private int maxBytes = 50 * 1024 * 1024;

  /** Generic storage ceiling; operation-specific limits may be stricter. */
  public int getMaxBytes() {
    return maxBytes;
  }

  public void setMaxBytes(int value) {
    if (value < 1) throw new IllegalArgumentException("invalid blob byte limit");
    maxBytes = value;
  }

  private boolean cleanupEnabled;
  private Duration cleanupInterval = Duration.ofMinutes(1);
  private Duration recoveryGrace = Duration.ofMinutes(10);
  private int cleanupBatchSize = 32;

  public boolean isCleanupEnabled() {
    return cleanupEnabled;
  }

  public void setCleanupEnabled(boolean value) {
    cleanupEnabled = value;
  }

  public Duration getCleanupInterval() {
    return cleanupInterval;
  }

  public void setCleanupInterval(Duration value) {
    if (value == null
        || value.compareTo(Duration.ofSeconds(1)) < 0
        || value.compareTo(Duration.ofDays(1)) > 0)
      throw new IllegalArgumentException("invalid blob cleanup interval");
    cleanupInterval = value;
  }

  public Duration getRecoveryGrace() {
    return recoveryGrace;
  }

  public void setRecoveryGrace(Duration value) {
    if (value == null || value.isNegative() || value.compareTo(Duration.ofDays(30)) > 0) {
      throw new IllegalArgumentException("invalid blob recovery grace");
    }
    recoveryGrace = value;
  }

  public int getCleanupBatchSize() {
    return cleanupBatchSize;
  }

  public void setCleanupBatchSize(int value) {
    if (value < 1 || value > 100) throw new IllegalArgumentException("invalid blob cleanup batch");
    cleanupBatchSize = value;
  }
}
