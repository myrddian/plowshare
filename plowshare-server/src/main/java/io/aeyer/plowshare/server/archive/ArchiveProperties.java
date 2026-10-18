package io.aeyer.plowshare.server.archive;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Archive sizing/decay and the explicit filing-operation spending policy. Unlimited write spending
 * is the default; a configured zero disables optional inference.
 *
 * <p>Defaults copied from Excalibur's {@code excalibur.example.toml}, not invented: {@code
 * max_body_chars = 8000}, {@code toc_threshold = 200}, {@code half_life_days = 30.0}. Matching the
 * reference implementation's defaults means a corpus migrated from Excalibur behaves the same on
 * day one instead of being retuned by accident.
 */
@ConfigurationProperties(prefix = "plowshare.archive")
public class ArchiveProperties {

  private String writeBudget = "unlimited";

  public String getWriteBudget() {
    return writeBudget;
  }

  public void setWriteBudget(String value) {
    writeBudget = value == null || value.isBlank() ? "unlimited" : value.strip();
    writeLimit();
  }

  /** Null deliberately means unlimited, not absence of operation authority. */
  public Integer writeLimit() {
    return io.aeyer.plowshare.server.embedding.SpendingPolicy.limit(
        writeBudget, "plowshare.archive.write-budget");
  }

  /** Body length ceiling enforced by {@link Validation}. */
  private int maxBodyChars = 8000;

  /**
   * How many {@code active} memories one tier may hold before the lowest-scoring unpinned ones are
   * demoted. Applied per tier, per {@link Archive}'s own javadoc — a single number here, but it
   * means "per tier", not "total".
   */
  private int indexThreshold = 200;

  /** Decay half-life in days, handed to {@link Scoring}. */
  private double halfLifeDays = 30.0;

  public int getMaxBodyChars() {
    return maxBodyChars;
  }

  public void setMaxBodyChars(int maxBodyChars) {
    this.maxBodyChars = maxBodyChars;
  }

  public int getIndexThreshold() {
    return indexThreshold;
  }

  public void setIndexThreshold(int indexThreshold) {
    this.indexThreshold = indexThreshold;
  }

  public double getHalfLifeDays() {
    return halfLifeDays;
  }

  public void setHalfLifeDays(double halfLifeDays) {
    this.halfLifeDays = halfLifeDays;
  }
}
