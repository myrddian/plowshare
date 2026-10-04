package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.hooks.HookRecord;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The per-turn allowance and the words of spec 2026-09-28-call-failures §3-§5. */
class CallFailuresTest {

  @Test
  void three_warnings_go_out_and_the_fourth_failure_in_a_row_finds_none() {
    CallFailures failures = new CallFailures();

    assertEquals(1, failures.failed());
    assertFalse(failures.exhausted());
    assertEquals(2, failures.warned());
    assertEquals(2, failures.failed());
    assertEquals(1, failures.warned());
    assertEquals(3, failures.failed());
    assertEquals(0, failures.warned());
    assertEquals(4, failures.failed());
    assertTrue(failures.exhausted());
  }

  @Test
  void a_real_call_restores_the_allowance_and_breaks_the_row() {
    CallFailures failures = new CallFailures();
    failures.failed();
    failures.warned();
    failures.failed();
    failures.warned();

    failures.called();

    assertEquals(1, failures.failed(), "the row starts again");
    assertEquals(2, failures.warned(), "the allowance is whole again");
  }

  @Test
  void a_warning_with_nothing_left_is_a_bug_and_not_a_negative_count() {
    CallFailures failures = new CallFailures();
    failures.warned();
    failures.warned();
    failures.warned();

    assertThrows(IllegalStateException.class, failures::warned);
  }

  @Test
  void the_warning_and_the_ending_are_the_spec_s_sentences() {
    assertEquals(
        "[harness] Warning: your reply wrote a call to `todo_write` as text, so"
            + " nothing ran. Writing a tool's name or its arguments in a reply does not call"
            + " it. Make the call now as a tool call. (Warnings left before this turn ends:"
            + " `2`.)",
        CallFailures.warning("todo_write", 2));
    assertEquals(
        "This run kept writing tool calls as text instead of making them, so it was" + " stopped.",
        CallFailures.ENDED);
  }

  @Test
  void the_safe_list_is_exactly_the_spec_s() {
    assertEquals(
        Set.of(
            "todo_read",
            "todo_write",
            "file_read",
            "file_stat",
            "file_glob",
            "file_grep",
            "file_roots",
            "orchestration_status",
            "memory_read",
            "memory_recall",
            "memory_navigate",
            "result_read",
            "result_list"),
        CallFailures.SAFE);
    for (String harmful :
        List.of(
            "orchestrate_code_implementation",
            "agent_run",
            "run",
            "file_edit",
            "file_delete",
            "file_move",
            "orchestration_cancel",
            "orchestration_finish",
            "orchestration_answer",
            "orchestration_ask",
            "orchestration_check",
            "memory_write",
            "fetch",
            "search")) {
      assertFalse(CallFailures.SAFE.contains(harmful), harmful);
    }
  }

  @Test
  void the_records_name_their_hook_their_tool_and_what_was_decided() {
    HookRecord failure = CallFailures.failure("todo_write", "the warning");
    assertEquals("harness:call-failure", failure.hook());
    assertEquals("todo_write", failure.tool());
    assertEquals(HookRecord.ADD, failure.decision());
    assertEquals("the warning", failure.added());

    HookRecord made =
        CallFailures.validation(
            "todo_write", HookRecord.ADD, "bare arguments", "{\"ops\":[]}", "{\"ops\": []}", 12);
    assertEquals("harness:call-validator", made.hook());
    assertEquals("todo_write", made.tool());
    assertEquals("bare arguments", made.reason());
    assertEquals("{\"ops\":[]}", made.added());
    assertEquals("{\"ops\": []}", made.original());
    assertEquals(12, made.tookMs());
  }
}
