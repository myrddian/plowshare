package io.aeyer.plowshare.server.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Where a conversation folds, as a pure function of the model's measured context and what an
 * operator configured — spec 2026-09-30-fold-at-60-and-80 §1 and §1a (revised).
 *
 * <p>Every number here is worked by hand from §1a's table: due is none at or below 64K, 60% just
 * above it, rising linearly to 75% at 200K (204 800) and 75% beyond; now is 90% at or below 64K,
 * falling linearly to 80% at 128K (131 072) and 80% beyond. Rounded down, in tokens.
 */
class FoldThresholdsTest {

    private static final int K64 = 65_536;
    private static final int K128 = 131_072;
    private static final int K200 = 204_800;

    @Test
    void a_32k_window_never_folds_between_turns_and_folds_inside_one_at_90_percent() {
        FoldThresholds at = FoldThresholds.byWindow(32_768);

        assertEquals(OptionalInt.empty(), at.due());
        assertEquals(29_491, at.now(), "90% of 32 768 is 29 491.2");
        assertEquals(32_768, at.ceiling());
    }

    @Test
    void a_64k_window_is_the_small_end_of_the_table() {
        FoldThresholds at = FoldThresholds.byWindow(K64);

        assertEquals(OptionalInt.empty(), at.due(),
                "64K is the least an agent can work in: no room to spare to an early fold");
        assertEquals(58_982, at.now(), "90% of 65 536 is 58 982.4");
    }

    @Test
    void one_token_above_64k_a_fold_is_due_at_60_percent_and_runs_inside_the_turn_at_90() {
        FoldThresholds at = FoldThresholds.byWindow(K64 + 1);

        // 65 537 * 60.0001...% and 65 537 * 89.9998...%, rounded down.
        assertEquals(OptionalInt.of(39_322), at.due());
        assertEquals(58_983, at.now());
    }

    @Test
    void a_96k_window_is_partway_along_both_lines() {
        FoldThresholds at = FoldThresholds.byWindow(98_304);

        // due: 60 + 15 * 32 768/139 264 = 63.53%; now: 90 - 10 * 32 768/65 536 = 85%.
        assertEquals(OptionalInt.of(62_451), at.due());
        assertEquals(83_558, at.now(), "85% of 98 304 is 83 558.4");
    }

    @Test
    void one_token_below_128k_the_in_turn_fold_is_still_falling() {
        FoldThresholds at = FoldThresholds.byWindow(K128 - 1);

        assertEquals(OptionalInt.of(87_894), at.due());
        assertEquals(104_856, at.now());
    }

    @Test
    void a_128k_window_folds_inside_the_turn_at_80_percent_and_is_due_at_about_67() {
        // The spark model's window. 60 + 15 * 65 536/139 264 = 67.06%.
        FoldThresholds at = FoldThresholds.byWindow(K128);

        assertEquals(OptionalInt.of(87_895), at.due());
        assertEquals(104_857, at.now(), "80% of 131 072 is 104 857.6");
    }

    @Test
    void a_200k_window_by_the_decimal_count_is_just_short_of_the_top() {
        FoldThresholds at = FoldThresholds.byWindow(200_000);

        assertEquals(OptionalInt.of(148_965), at.due(), "74.48%");
        assertEquals(160_000, at.now());
    }

    @Test
    void a_200k_window_is_due_at_75_percent_the_most() {
        FoldThresholds at = FoldThresholds.byWindow(K200);

        assertEquals(OptionalInt.of(153_600), at.due());
        assertEquals(163_840, at.now());
    }

    @Test
    void one_token_below_200k_the_due_fold_is_still_rising() {
        FoldThresholds at = FoldThresholds.byWindow(K200 - 1);

        assertEquals(OptionalInt.of(153_599), at.due());
        assertEquals(163_839, at.now());
    }

    @Test
    void a_300k_window_stays_at_75_and_80_percent() {
        FoldThresholds at = FoldThresholds.byWindow(300_000);

        assertEquals(OptionalInt.of(225_000), at.due());
        assertEquals(240_000, at.now());
    }

    @Test
    void now_is_above_due_everywhere_in_the_table() {
        for (int window = K64 + 1; window <= 400_000; window += 997) {
            FoldThresholds at = FoldThresholds.byWindow(window);
            assertTrue(at.due().getAsInt() < at.now(), "at " + window + ": " + at);
        }
    }

    @Test
    void the_coder_turn_that_could_not_fold_is_measured_against_the_spark_window() {
        // Measured 2026-09-30: one coder turn on openai/gpt-oss-120b reached 80 345 of 131 072 —
        // under both thresholds of the revised table, which is said rather than hidden.
        FoldThresholds at = FoldThresholds.byWindow(K128);

        assertTrue(80_345 < at.due().getAsInt());
        assertTrue(80_345 < at.now());
    }

    @Test
    void a_configured_due_wins_over_the_derived_one_even_where_there_was_none() {
        FoldThresholds small = FoldThresholds.of(32_768, OptionalInt.of(20_000), OptionalInt.empty());
        FoldThresholds large = FoldThresholds.of(K128, OptionalInt.of(40_000), OptionalInt.empty());

        assertEquals(OptionalInt.of(20_000), small.due());
        assertEquals(29_491, small.now());
        assertEquals(OptionalInt.of(40_000), large.due());
        assertEquals(104_857, large.now());
    }

    @Test
    void a_configured_now_wins_over_the_derived_one() {
        FoldThresholds at = FoldThresholds.of(K128, OptionalInt.empty(), OptionalInt.of(100_000));

        assertEquals(OptionalInt.of(87_895), at.due());
        assertEquals(100_000, at.now());
    }

    @Test
    void neither_threshold_may_pass_the_ceiling() {
        FoldThresholds at = FoldThresholds.of(1_000, OptionalInt.of(6_000), OptionalInt.of(7_000));

        assertEquals(OptionalInt.of(1_000), at.due());
        assertEquals(1_000, at.now());
    }

    @Test
    void where_a_configured_due_is_not_below_now_now_is_raised_to_it_and_never_the_other_way() {
        // Boot refuses this where it can see both numbers (LlmConfig); a discovered window is
        // the case it cannot, and the operator's due is not overruled by a derived now.
        FoldThresholds at = FoldThresholds.of(K128, OptionalInt.of(110_000), OptionalInt.empty());

        assertEquals(OptionalInt.of(110_000), at.due());
        assertEquals(110_000, at.now());
    }

    @Test
    void a_window_that_is_not_a_window_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> FoldThresholds.byWindow(0));
    }
}
