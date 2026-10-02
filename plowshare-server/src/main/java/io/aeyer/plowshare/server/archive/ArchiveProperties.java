package io.aeyer.plowshare.server.archive;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The three numbers {@link Archive}'s constructor takes and nothing else does
 * — where they come from, so an operator can change them without a rebuild.
 *
 * <p>Defaults copied from Excalibur's {@code excalibur.example.toml}, not
 * invented: {@code max_body_chars = 8000}, {@code toc_threshold = 200},
 * {@code half_life_days = 30.0}. Matching the reference implementation's
 * defaults means a corpus migrated from Excalibur behaves the same on day one
 * instead of being retuned by accident.
 */
@ConfigurationProperties(prefix = "plowshare.archive")
public class ArchiveProperties {

    /** Body length ceiling enforced by {@link Validation}. */
    private int maxBodyChars = 8000;

    /**
     * How many {@code active} memories one tier may hold before the
     * lowest-scoring unpinned ones are demoted. Applied per tier, per {@link
     * Archive}'s own javadoc — a single number here, but it means "per tier",
     * not "total".
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
