package io.aeyer.plowshare.server.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounds for the new inspection capability; legacy image upload behavior is unchanged. */
@ConfigurationProperties(prefix = "plowshare.media.inspection")
public class MediaInspectionProperties {
  private int maxPixels = 16_000_000;
  private int maxDimension = 16_384;
  private int maxConcurrent = 2;

  public int getMaxPixels() {
    return maxPixels;
  }

  public void setMaxPixels(int maxPixels) {
    this.maxPixels = maxPixels;
  }

  public int getMaxDimension() {
    return maxDimension;
  }

  public void setMaxDimension(int maxDimension) {
    this.maxDimension = maxDimension;
  }

  public int getMaxConcurrent() {
    return maxConcurrent;
  }

  public void setMaxConcurrent(int maxConcurrent) {
    this.maxConcurrent = maxConcurrent;
  }
}
