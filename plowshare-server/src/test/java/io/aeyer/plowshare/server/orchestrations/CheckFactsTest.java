package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.CommandRunner;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The block a reviewing delegate is handed above its task: the run's check as the harness last
 * ran it. Measured 2026-09-30, orc_3190C667F18B8E57: a code_reviewer that runs nothing reported,
 * with "High" confidence, a test failing that the harness's check had just passed with, and the
 * conductor believed it over the check.
 */
class CheckFactsTest {

    private static final Instant AT = Instant.parse("2026-09-30T14:05:12.345Z");

    private static final List<String> CHECK = List.of("make", "test");

    @Test
    void a_passed_check_is_told_with_its_time_its_exit_and_the_end_of_its_output() {
        String block = CheckFacts.block(CHECK, Optional.of(new CheckFacts.Ran(AT,
                CheckFacts.Result.PASSED, 0, "--- stdout ---\n26 passed\n--- stderr ---\n(nothing)")));

        assertEquals("[harness] The run's check `make test` last ran at 2026-09-30T14:05:12Z:"
                + " passed (exit 0). The harness ran it itself; this is its result, not anyone's"
                + " account of it. Its output ended:\n--- stdout ---\n26 passed\n--- stderr ---\n"
                + "(nothing)", block);
    }

    @Test
    void a_failed_check_is_told_with_its_exit_code_and_its_output() {
        String block = CheckFacts.block(CHECK, Optional.of(new CheckFacts.Ran(AT,
                CheckFacts.Result.FAILED, 2, "--- stdout ---\n1 failed, 25 passed")));

        assertTrue(block.startsWith("[harness] The run's check `make test` last ran at"
                + " 2026-09-30T14:05:12Z: failed (exit 2)."), block);
        assertTrue(block.endsWith("Its output ended:\n--- stdout ---\n1 failed, 25 passed"), block);
    }

    @Test
    void a_timed_out_check_and_one_with_no_exit_code_say_so() {
        assertTrue(CheckFacts.block(CHECK, Optional.of(new CheckFacts.Ran(AT,
                CheckFacts.Result.TIMED_OUT, null, null))).contains(": timed out, with no exit"
                        + " code."));
        assertTrue(CheckFacts.block(CHECK, Optional.of(new CheckFacts.Ran(AT,
                CheckFacts.Result.FAILED, null, null))).contains(": failed, with no exit code."));
    }

    @Test
    void a_run_the_record_holds_no_output_for_says_that_rather_than_inventing_one() {
        String block = CheckFacts.block(CHECK, Optional.of(new CheckFacts.Ran(AT,
                CheckFacts.Result.PASSED, 0, null)));

        assertTrue(block.endsWith("passed (exit 0). The harness ran it itself; this is its"
                + " result, not anyone's account of it. The record holds none of its output."),
                block);
    }

    @Test
    void a_result_the_record_cannot_be_read_for_is_not_called_a_pass_or_a_failure() {
        String block = CheckFacts.block(CHECK, Optional.of(new CheckFacts.Ran(AT,
                CheckFacts.Result.UNKNOWN, null, null)));

        assertTrue(block.contains("last ran at 2026-09-30T14:05:12Z, and the record does not say"
                + " how it ended."), block);
        assertFalse(block.contains("passed"), block);
        assertFalse(block.contains("failed"), block);
    }

    @Test
    void a_check_that_has_not_run_yet_says_there_is_no_result() {
        assertEquals("[harness] The run's check `make test` has not run yet: the harness runs it"
                + " each time a checked stage is marked done, and none has been, so there is no"
                + " result to go on.", CheckFacts.block(CHECK, Optional.empty()));
    }

    @Test
    void a_run_with_no_check_says_it_has_none() {
        assertEquals("[harness] This run has no check yet: no command has been set to show the"
                + " work is done, so the harness has run nothing and has no result to go on.",
                CheckFacts.block(null, Optional.empty()));
    }

    /** Where test runners print their counts is the end, whatever the language: each stream is
     *  cut to its last lines, and each line run to a bounded length, keeping the end. */
    @Test
    void the_output_kept_is_each_stream_s_last_lines_bounded_in_characters() {
        String stdout = IntStream.rangeClosed(1, 100).mapToObj(n -> "line " + n)
                .collect(Collectors.joining("\n")) + "\n";
        String stderr = "x".repeat(10_000) + "\nthe end";
        String kept = CheckFacts.output(new CommandRunner.Outcome(1, false, false, stdout, 0,
                stderr, 0, 10));

        String out = kept.substring(0, kept.indexOf("--- stderr ---"));
        assertTrue(out.startsWith("--- stdout ---\n"), kept);
        assertFalse(out.contains("line 70\n"), kept);
        assertTrue(out.contains("line 71\n"), kept);
        assertTrue(out.contains("line 100"), kept);
        assertEquals(CheckFacts.TAIL_LINES, out.strip().lines().count() - 1, kept);
        String err = kept.substring(kept.indexOf("--- stderr ---"));
        assertTrue(err.endsWith("the end"), kept);
        assertTrue(err.length() <= "--- stderr ---\n".length() + CheckFacts.TAIL_CHARACTERS, kept);
        assertTrue(err.startsWith("--- stderr ---\n…"), kept);
    }

    @Test
    void an_empty_stream_is_nothing_rather_than_a_blank() {
        assertEquals("--- stdout ---\n3 passed\n--- stderr ---\n(nothing)",
                CheckFacts.output(new CommandRunner.Outcome(0, false, false, "3 passed\n", 0, "",
                        0, 10)));
    }

    /** A block is bounded whatever the record hands it — an older row's body, or a long command. */
    @Test
    void the_block_is_bounded_whatever_it_is_handed() {
        String huge = "y".repeat(50_000) + "\nlast";
        List<String> longCommand = List.of("z".repeat(2_000));
        String block = CheckFacts.block(longCommand, Optional.of(new CheckFacts.Ran(AT,
                CheckFacts.Result.PASSED, 0, huge)));

        assertTrue(block.length() <= CheckFacts.MOST_SHOWN + 600, "length " + block.length());
        assertTrue(block.endsWith("last"), "the end of the output is what is kept");
        assertTrue(block.contains("passed (exit 0)"), "the result survives a long command");
    }
}
