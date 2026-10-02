package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.Provenance;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * Ported from Excalibur's {@code tests/archive/test_scoring.py} (6 tests).
 * All six port: decay is a pure function of a record's fields, with nothing
 * here touching the Markdown file layout Postgres replaces.
 *
 * <p>Assertions use {@code assertEquals} with a delta rather than the plan's
 * looser {@code > 0.0}: the formula has an exact closed form for every case
 * below, and a tolerance loose enough to accept "positive" would also accept
 * a wrong exponent or a swapped numerator — the failure this suite exists to
 * catch.
 */
class ScoringTest {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final double DELTA = 0.0001;

    private static Memory memory(int uses, Instant lastUsed, Instant formedAt) {
        Memory m = Memory.formed("mem_01", "s", "sc", "b",
                        new Provenance(formedAt, "test", "tests"), Home.global())
                .withUses(uses);
        return lastUsed == null ? m : m.withLastUsed(lastUsed);
    }

    /** `max(uses, 1)` and not `uses`: a never-used memory would otherwise score
     *  zero regardless of age, sorting below everything and being demoted first
     *  the moment the archive crosses its threshold — exactly backwards for
     *  something written yesterday. Formation counts as one implicit use, so a
     *  memory scored the instant it is formed starts at exactly 1.0. */
    @Test
    void never_used_memory_formed_now_scores_one() {
        Memory m = memory(0, null, NOW);
        assertEquals(1.0, Scoring.score(m, NOW, 30.0), DELTA);
    }

    /** With no {@code lastUsed}, the reference point falls back to
     *  {@code formed.at} — a never-used memory still decays with age. */
    @Test
    void never_used_memory_formed_one_half_life_ago_scores_half() {
        Memory m = memory(0, null, NOW.minus(30, ChronoUnit.DAYS));
        assertEquals(0.5, Scoring.score(m, NOW, 30.0), DELTA);
    }

    @Test
    void score_equals_uses_when_just_used() {
        Memory m = memory(8, NOW, Instant.EPOCH);
        assertEquals(8.0, Scoring.score(m, NOW, 30.0), DELTA);
    }

    @Test
    void score_halves_after_one_half_life() {
        Memory m = memory(8, NOW.minus(30, ChronoUnit.DAYS), Instant.EPOCH);
        assertEquals(4.0, Scoring.score(m, NOW, 30.0), DELTA);
    }

    @Test
    void score_quarters_after_two_half_lives() {
        Memory m = memory(8, NOW.minus(60, ChronoUnit.DAYS), Instant.EPOCH);
        assertEquals(2.0, Scoring.score(m, NOW, 30.0), DELTA);
    }

    /** {@code lastUsed} wins over {@code formed.at} when present, and outweighs
     *  raw use count: a record used twice this morning outranks one used
     *  twenty times but stale for half a year — recall should surface what is
     *  current, not what was once popular. */
    @Test
    void recently_used_beats_older_heavier_use() {
        Memory fresh = memory(2, NOW, Instant.EPOCH);
        Memory stale = memory(20, NOW.minus(180, ChronoUnit.DAYS), Instant.EPOCH);

        assertTrue(Scoring.score(fresh, NOW, 30.0) > Scoring.score(stale, NOW, 30.0));
    }
}
