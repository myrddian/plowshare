package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Memory;
import java.time.Duration;
import java.time.Instant;

/**
 * Decayed usefulness score (spec §7) — the input to cold demotion.
 *
 * <p>Raw least-frequently-used has a specific pathology: it is brutal to the
 * rare-but-critical memory, which looks statistically identical to dead
 * weight right up until the night it matters. Decay softens the
 * rich-get-richer loop; pinning is what actually protects those records (see
 * {@link Lifecycle}), not this formula.
 *
 * <p>A record that has never been fetched is not the same as a record proven
 * useless — it just hasn't been tested yet. Formation therefore counts as one
 * implicit use, decaying from {@code formed.at} instead of flooring at zero.
 * A fresh memory starts at a score of 1.0 and decays like any other record,
 * so it competes on age rather than being born beneath every record that has
 * ever been read even once.
 *
 * <p>Ported from Excalibur's {@code archive/scoring.py}.
 */
public final class Scoring {

    private static final double SECONDS_PER_DAY = 86_400.0;

    private Scoring() {}

    /**
     * The decayed score used to rank memories for cold demotion: the highest
     * scores stay in the active index, the lowest cross the threshold first.
     *
     * @param memory the memory to score
     * @param now the instant to score against — passed in rather than read
     *     from the clock so a demotion pass can score a whole batch against
     *     one consistent instant
     * @param halfLifeDays days for the score to fall by half; server
     *     configuration, not an archive rule, so it is passed in rather than
     *     owned here
     * @return the decayed score; higher means more likely to stay active
     */
    public static double score(Memory memory, Instant now, double halfLifeDays) {
        Instant reference = memory.lastUsed() != null ? memory.lastUsed() : memory.formed().at();

        // max(uses, 1), not uses: a never-used memory would otherwise score
        // exactly zero regardless of age, sorting beneath every record that
        // has ever been read even once and being demoted first the moment
        // the archive crosses its threshold — exactly backwards for
        // something formed yesterday.
        int count = Math.max(memory.uses(), 1);

        Duration elapsed = Duration.between(reference, now);
        double elapsedDays = (elapsed.getSeconds() + elapsed.getNano() / 1e9) / SECONDS_PER_DAY;

        return count * Math.pow(0.5, elapsedDays / halfLifeDays);
    }
}
