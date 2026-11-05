package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayBlob;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit deployment admission, independent of existing text limits and topic permissions. */
@ConfigurationProperties(prefix = "plowshare.relay.blobs", ignoreUnknownFields = false)
public class RelayBlobProperties {
  private boolean enabled;
  private int maxBytes = 5 * 1024 * 1024;
  private int maxConcurrent = 4;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean value) {
    enabled = value;
  }

  public int getMaxBytes() {
    return maxBytes;
  }

  public void setMaxBytes(int value) {
    if (value < 1 || value > RelayBlob.MAX_BYTES)
      throw new IllegalArgumentException("invalid Relay blob byte limit");
    maxBytes = value;
  }

  public int getMaxConcurrent() {
    return maxConcurrent;
  }

  public void setMaxConcurrent(int value) {
    if (value < 1 || value > 32)
      throw new IllegalArgumentException("invalid Relay blob concurrency limit");
    maxConcurrent = value;
  }
}
