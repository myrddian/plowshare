package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/** Ports {@code tests/archive/test_ids.py}: the id format is pinned, not arbitrary. */
class MemoryIdsTest {

    @Test
    void an_id_has_the_expected_shape() {
        String id = MemoryIds.newId(Instant.parse("2026-08-16T00:00:00Z"));

        assertTrue(id.startsWith("mem_"), id);
        assertEquals("mem_".length() + 16, id.length(), id);
        String suffix = id.substring(4);
        assertTrue(suffix.chars().allMatch(Character::isLetterOrDigit), id);
        assertEquals(suffix.toUpperCase(java.util.Locale.ROOT), suffix, id);
    }

    /**
     * The property the whole format exists for: string order is creation
     * order, which is what lets the store sort an index and a supersession
     * chain with {@code ORDER BY id} and no timestamp column at all.
     */
    @Test
    void ids_sort_by_creation_time() {
        String earlier = MemoryIds.newId(Instant.parse("2026-08-16T10:00:00Z"));
        String later = MemoryIds.newId(Instant.parse("2026-08-16T11:00:00Z"));

        assertTrue(earlier.compareTo(later) < 0, earlier + " should sort before " + later);
    }

    /**
     * Two memories formed in the same millisecond still get different ids —
     * the whole job of the six random hex chars. Without them this returns one
     * id 500 times, and the archive's primary key would turn a burst of writes
     * into a single overwritten row.
     *
     * <p><b>Seeded, and deliberately not faithful to Excalibur here.</b> The
     * Python draws 500 real random ids and asserts all 500 differ. Three bytes
     * is 16,777,216 values, so by the birthday bound 500 draws collide with
     * probability 500 x 499 / 2 / 16,777,216 — about 0.7%, or one run in 135.
     * Excalibur can carry that; a suite four commits old cannot, because a test
     * that fails occasionally for no reason teaches people to re-run rather
     * than read. Seeding decides the claim once and for all runs, and it still
     * fails loudly against an implementation with no random suffix at all: that
     * one returns a set of size 1.
     *
     * <p>The id <em>format</em> is untouched — lexicographic order matching
     * creation order is a contract the store's {@code ORDER BY id} depends on.
     * Only the test's source of randomness moved.
     */
    @Test
    void ids_are_unique_within_the_same_millisecond() {
        Instant fixed = Instant.parse("2026-08-16T00:00:00Z");
        RandomGenerator seeded = new Random(20260816L);

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            ids.add(MemoryIds.newId(fixed, seeded));
        }

        assertEquals(500, ids.size());
    }

    /**
     * And the production entry point really does reach for randomness, rather
     * than being unique only when a test hands it a generator.
     *
     * <p>Two draws, not five hundred: at n=2 a false failure is one run in
     * 16,777,216, which is negligible in a way n=500 is not. Everything a
     * larger sample would add is already pinned by the shape test above, which
     * fixes the suffix at six hex characters.
     */
    @Test
    void the_production_entry_point_draws_from_a_random_source() {
        Instant fixed = Instant.parse("2026-08-16T00:00:00Z");

        assertNotEquals(MemoryIds.newId(fixed), MemoryIds.newId(fixed));
    }
}
