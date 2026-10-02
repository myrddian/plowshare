package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 1a (spec 2026-09-29): what a delegate did, from the record — no claim is parsed. */
class DelegationFactsTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant AT = Instant.parse("2026-09-29T05:21:07Z");

    private static RecordRow line(int ordinal, String text, String detail) {
        return line(ordinal, text, detail, null);
    }

    private static RecordRow line(int ordinal, String text, String detail, String body) {
        return new RecordRow(ordinal, AT, "orc_1", "coder", RecordKind.TOOL_CALL, text, detail,
                body);
    }

    @Test
    void edits_and_runs_are_stated_as_the_record_has_them() {
        String footer = DelegationFacts.footer("coder", List.of(
                line(10, "coder · file_read rpg/main.py", "ok"),
                line(11, "coder · file_edit rpg/main.py", "ok"),
                line(12, "coder · file_edit tests/test_main.py", "ok"),
                line(13, "coder · file_edit rpg/main.py", "ok"),
                line(14, "coder · file_edit rpg/missing.py", "not found"),
                line(15, "coder · run python -m pytest -q", "exit 1")), UTC);

        assertEquals("[harness] coder edited rpg/main.py, tests/test_main.py;"
                + " ran python -m pytest -q → exit 1 (05:21); read 1 file"
                + "\n[harness] python -m pytest -q → exit 1 (05:21); the record holds none of its"
                + " output", footer);
    }

    /**
     * Measured 2026-09-30: "ran python -m pytest -q → exit 2" and none of the output, and three
     * levels of conductor then asked each other for it. The end of what the command printed is
     * read back from the record's own line for the run — never from what the delegate said.
     */
    @Test
    void a_command_that_did_not_end_ok_is_followed_by_the_end_of_its_output_from_the_record() {
        String footer = DelegationFacts.footer("coder", List.of(
                line(1, "coder · run python -m pytest -q", "exit 2",
                        "--- stdout ---\nFAILED tests/test_rpg.py::test_move - KeyError: 'north'\n"
                                + "1 failed, 4 passed in 0.12s"),
                line(2, "coder · run npm test", "timed out", "--- stdout ---\nstill waiting"),
                line(3, "coder · run cargo test", "ok", null)), UTC);

        assertEquals("[harness] coder edited nothing; ran python -m pytest -q → exit 2 (05:21),"
                + " npm test → timed out (05:21), cargo test → ok (05:21)"
                + "\n[harness] python -m pytest -q → exit 2 (05:21): its output ended:"
                + "\n--- stdout ---\nFAILED tests/test_rpg.py::test_move -"
                + " KeyError: 'north'\n1 failed, 4 passed in 0.12s"
                + "\n[harness] npm test → timed out (05:21): its output ended:"
                + "\n--- stdout ---\nstill waiting", footer);
    }

    /** The state the delegate left: a command that failed and then passed has nothing to add. */
    @Test
    void a_command_that_failed_and_then_passed_is_not_reported_as_failing() {
        String footer = DelegationFacts.footer("coder", List.of(
                line(1, "coder · run make test", "exit 1", "boom"),
                line(2, "coder · run make test", "ok", null)), UTC);

        assertEquals("[harness] coder edited nothing; ran make test → exit 1 (05:21),"
                + " make test → ok (05:21)", footer);
    }

    /** Past the few shown, the rest are counted, and the latest failures are the ones shown. */
    @Test
    void past_three_failed_commands_the_rest_are_counted_and_the_latest_shown() {
        List<RecordRow> lines = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            lines.add(line(i, "coder · run check" + i, "exit 1", "out" + i));
        }

        String footer = DelegationFacts.footer("coder", lines, UTC);

        assertEquals(false, footer.contains("out1"), footer);
        assertEquals(false, footer.contains("out2"), footer);
        assertEquals(true, footer.contains("check3 → exit 1 (05:21): its output ended:\nout3"
                + "\n[harness] check4"), footer);
        assertEquals(true, footer.endsWith("out5\n[harness] and 2 more commands did not end ok;"
                + " the record has their output"), footer);
    }

    /**
     * Final review: a phase run's lines carry its label first, and a phase whose name holds the
     * actor's ({@code 03-coder}) put {@code coder · } inside the label. The marker is matched
     * after the known label, so the tool is the tool and not the actor's name.
     */
    @Test
    void a_phase_label_that_holds_the_actor_s_name_is_taken_off_before_the_actor_is_matched() {
        List<RecordRow> lines = List.of(
                line(1, "03-coder · coder · file_edit rpg/coder.py", "ok"),
                line(2, "03-coder · coder · run python -m pytest -q", "exit 0"));

        assertEquals("[harness] coder edited rpg/coder.py; ran python -m pytest -q → exit 0"
                + " (05:21)", DelegationFacts.footer("coder", lines, UTC, "03-coder"));
        assertEquals(new DelegationFacts.ToolLine("file_edit", "rpg/coder.py"),
                DelegationFacts.parse(lines.get(0), "03-coder"));
    }

    @Test
    void nothing_edited_and_nothing_run_is_stated_too() {
        assertEquals("[harness] coder edited nothing and ran nothing",
                DelegationFacts.footer("coder", List.of(), UTC));
        assertEquals("[harness] coder edited nothing and ran nothing; read 2 files",
                DelegationFacts.footer("coder", List.of(
                        line(1, "coder · file_read a.py", "ok"),
                        line(2, "coder · file_read b.py", "ok")), UTC));
    }

    /** More than 8 distinct edits are cut at 8, first encountered, with the rest counted. */
    @Test
    void more_than_eight_edits_are_cut_at_eight_with_the_rest_counted() {
        List<RecordRow> lines = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            lines.add(line(i, "coder · file_edit f" + i + ".py", "ok"));
        }

        String footer = DelegationFacts.footer("coder", lines, UTC);

        assertEquals("[harness] coder edited f1.py, f2.py, f3.py, f4.py, f5.py, f6.py, f7.py,"
                + " f8.py and 12 more; ran nothing", footer);
    }

    /** More than 8 commands are cut at 8, the most recent kept, with the rest counted. */
    @Test
    void more_than_eight_commands_are_cut_at_eight_keeping_the_most_recent_last() {
        List<RecordRow> lines = new java.util.ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            lines.add(line(i, "coder · run cmd" + i, "exit 0"));
        }

        String footer = DelegationFacts.footer("coder", lines, UTC);

        assertEquals("[harness] coder edited nothing; ran cmd2 → exit 0 (05:21),"
                + " cmd3 → exit 0 (05:21), cmd4 → exit 0 (05:21), cmd5 → exit 0 (05:21),"
                + " cmd6 → exit 0 (05:21), cmd7 → exit 0 (05:21), cmd8 → exit 0 (05:21),"
                + " cmd9 → exit 0 (05:21) and 1 more", footer);
    }

    /** An unsettled run — RecordStore.settle has not written its outcome yet — reads {@code
     *  → …} on purpose: the record genuinely has no outcome for it yet, and this says so rather
     *  than guessing "ok" or dropping the line. */
    @Test
    void an_unsettled_run_reads_with_an_ellipsis_rather_than_a_guessed_outcome() {
        String footer = DelegationFacts.footer("coder", List.of(
                line(1, "coder · run python -m pytest -q", null)), UTC);

        assertEquals("[harness] coder edited nothing; ran python -m pytest -q → … (05:21)",
                footer);
    }

    @Test
    void a_phase_label_before_the_actor_is_read_past() {
        assertEquals(new DelegationFacts.ToolLine("file_edit", "rpg/main.py"),
                DelegationFacts.parse(line(1, "03-character · coder · file_edit rpg/main.py", "ok")));
        assertEquals(new DelegationFacts.ToolLine("todo_read", ""),
                DelegationFacts.parse(line(2, "coder · todo_read", "ok")));
    }
}
