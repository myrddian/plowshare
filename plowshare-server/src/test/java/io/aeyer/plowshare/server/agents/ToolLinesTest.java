package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ToolLinesTest {

  @Test
  void the_salient_argument_is_the_one_a_person_follows_a_run_by() {
    assertEquals(
        "./gradlew test --tests Foo",
        ToolLines.salient(
            "run", "{\"command\":[\"./gradlew\",\"test\",\"--tests\",\"Foo\"],\"cwd\":\"/r\"}"));
    assertEquals("3 ops", ToolLines.salient("todo_write", "{\"ops\":[{},{},{}]}"));
    assertEquals("1 op", ToolLines.salient("todo_write", "{\"ops\":[{}]}"));
    assertEquals("coder", ToolLines.salient("agent_run", "{\"agent\":\"coder\",\"task\":\"x\"}"));
    assertEquals("/repo/a.py", ToolLines.salient("file_read", "{\"path\":\"/repo/a.py\"}"));
    assertEquals(
        "/repo/a.py", ToolLines.salient("file_edit", "{\"path\":\"/repo/a.py\",\"old\":\"x\"}"));
    assertEquals("/a → /b", ToolLines.salient("file_move", "{\"path\":\"/a\",\"to\":\"/b\"}"));
    assertEquals("src/**/*.py", ToolLines.salient("file_glob", "{\"pattern\":\"src/**/*.py\"}"));
    assertEquals("", ToolLines.salient("memory_recall", "{\"query\":\"x\"}"));
    assertEquals("", ToolLines.salient("run", "not json"));
    assertEquals("", ToolLines.salient("run", ""));
  }

  @Test
  void the_salient_argument_is_one_short_line() {
    String said =
        ToolLines.salient("run", "{\"command\":[\"echo\",\"" + "a\\nb ".repeat(100) + "\"]}");
    assertEquals(120, said.length());
    assertEquals('…', said.charAt(119));
    assertEquals(-1, said.indexOf('\n'));
  }

  @Test
  void a_run_s_outcome_is_its_status_line_in_a_word() {
    assertEquals("ok", ToolLines.outcome("run", "exit 0 after 1.2s in /r on the server side\n..."));
    assertEquals("exit 1", ToolLines.outcome("run", "exit 1 after 3.0s in /r on the local side"));
    assertEquals(
        "timed out", ToolLines.outcome("run", "timed out after 120s and was killed in /r"));
    assertEquals(
        "cancelled", ToolLines.outcome("run", "cancelled after 2.0s and was killed in /r"));
    assertEquals("ran", ToolLines.outcome("run", "[Cut: the last 100000 of 180000 characters]"));
    assertEquals("refused", ToolLines.outcome("run", "run needs an absolute 'cwd'"));
  }

  @Test
  void any_other_tool_s_outcome_is_refused_or_ok() {
    assertEquals(
        "refused",
        ToolLines.outcome(
            "todo_write",
            "todo_write refused operation 1: code is not done. Nothing was changed."));
    assertEquals("ok", ToolLines.outcome("todo_write", "Done. The list is now:\n..."));
    assertEquals("ok", ToolLines.outcome("file_read", ""));
  }

  @Test
  void a_word_in_what_a_content_tool_found_is_not_its_outcome() {
    assertEquals(
        "ok",
        ToolLines.outcome(
            "file_grep", "/repo/a.log:3: the call was refused by a hook\n/repo/b.log:9: refused"));
    assertEquals("ok", ToolLines.outcome("file_read", "refused: the migration was refused"));
    assertEquals(
        "ok",
        ToolLines.outcome(
            "agent_run", "the agent 'helper' answered:\nthe request was refused upstream"));
    assertEquals("ok", ToolLines.outcome("memory_recall", "nothing was refused"));
  }

  /** Rule 7 (spec 2026-09-29 §3): a file that is not there was not refused. */
  @Test
  void a_file_tool_s_not_found_answer_reads_not_found() {
    assertEquals("not found", ToolLines.NOT_FOUND);
    for (String tool :
        List.of("file_read", "file_stat", "file_edit", "file_delete", "file_move", "file_grep")) {
      assertEquals(
          ToolLines.NOT_FOUND,
          ToolLines.outcome(tool, "there is no file at /repo/rpg/main.py"),
          tool);
    }
    assertEquals(
        ToolLines.REFUSED,
        ToolLines.outcome("file_read", "path /etc/hosts is outside every root this job reaches"));
  }

  @Test
  void a_content_tool_s_refusal_is_read_off_the_sentence_it_opens_with() {
    assertEquals("not found", ToolLines.outcome("file_read", "there is no file at /repo/a.py"));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "file_read",
            "path /etc/passwd is outside every"
                + " root this job can reach. What it can reach — …"));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "file_read", "path /repo/bin is not a regular" + " file, so there is nothing to read"));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "file_read",
            "path /repo/a.bin is not UTF-8 text;" + " these tools read text files only"));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "file_read",
            "path /repo/big.log is 9000000"
                + " bytes, and this server will not read more than 1048576"));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "file_read", "path /repo/a.py could not be read:" + " Permission denied"));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "file_read", "This job has no filesystem at all" + " — no provider is wired to it"));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "file_read", "no root is reachable on this run," + " so there is nothing at /a"));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "file_glob",
            "'/src/**' is an absolute pattern,"
                + " and a pattern is matched against paths relative to a root"));
    assertEquals(
        "refused",
        ToolLines.outcome("file_glob", "more than 5000 files match;" + " narrow the pattern"));
    assertEquals(
        "refused", ToolLines.outcome("file_grep", "file_grep was given a 'text' of" + " ''."));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "file_read",
            "file_read could not read its" + " arguments: they were not valid JSON (x)."));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "file_read",
            "file_read needs a 'path': the file." + " It was missing, empty, or not a string."));
  }

  /** What the record keeps of a failed command: its answer's end, without the status. */
  @Test
  void a_failed_run_s_tail_is_its_last_lines_without_the_status_or_an_empty_stream() {
    String result =
        "exit 2 after 1.2s in /repo on the server side\n--- stdout ---\n"
            + "collected 5 items\nFAILED t.py::test_a - KeyError\n1 failed\n--- stderr ---\n"
            + "(nothing)";

    assertEquals(
        "--- stdout ---\ncollected 5 items\nFAILED t.py::test_a - KeyError\n" + "1 failed",
        ToolLines.tail(result));
    assertEquals(
        "run refused: the approval was denied",
        ToolLines.tail("run refused: the approval was denied"));
    assertEquals(
        null,
        ToolLines.tail(
            "exit 1 after 0.1s in /r on the local side\n"
                + "--- stdout ---\n(nothing)\n--- stderr ---\n(nothing)"));
  }

  @Test
  void a_failed_run_s_tail_is_bounded_in_lines_and_in_characters() {
    StringBuilder many =
        new StringBuilder("exit 1 after 1.0s in /r on the local side\n" + "--- stdout ---");
    for (int i = 0; i < 100; i++) {
      many.append("\nline ").append(i);
    }
    String tail = ToolLines.tail(many.toString());
    String wide = ToolLines.tail("x".repeat(10_000) + "END");

    assertEquals(ToolLines.TAIL_LINES, tail.lines().count(), tail);
    assertTrue(tail.endsWith("line 99"), tail);
    assertEquals(ToolLines.TAIL_CHARS, wide.length());
    assertTrue(wide.startsWith("…") && wide.endsWith("END"), wide);
  }

  /**
   * A client's file is refused in FileWords' words too, and those were read as a successful read:
   * "path … is outside this session's workspace" matched nothing here, so a delegate's footer
   * counted a refused read as a read.
   */
  @Test
  void a_refusal_of_a_client_s_file_is_a_refusal_in_the_record_too() {
    for (String said :
        List.of(
            "path /x is outside this session's workspace, which is /repo; ask for the roots",
            "path /repo/.env is inside this session's workspace, which is /repo, but hidden:",
            "path /old/a was inside this session's workspace, but the workspace moved",
            "no workspace is set for this session, so nothing on this machine is reachable",
            "path /repo/src is a directory on this machine, so there is nothing to read",
            "path /repo/big.log is 9000000 bytes, and this client will not read more than 1",
            "path /repo/a.pdf is a PDF, which this client reads, and this one could not be"
                + " converted: it is encrypted",
            "path /repo/a.png is a png image, and it could not be uploaded because",
            "path /repo/a.pdf is a PDF, and this client reads text files only; the MCP",
            "line 3 of this file is 200000 bytes, and one read carries at most 98304 bytes",
            "'**/[x' is not a usable glob: it opens a [ it never closes",
            "'**/**/**/**/**/x' has 5 '**/' segments and at most 4 are expanded",
            "this request asks for a search this client cannot run: a search needs",
            "'a\u0000b' is not a path this machine can even name: Nul character",
            "this request named no path")) {
      assertEquals("refused", ToolLines.outcome("file_read", said), said);
    }
  }

  @Test
  void a_tool_that_succeeds_in_one_sentence_refuses_in_any_other() {
    assertEquals("ok", ToolLines.outcome("file_edit", "Wrote 12 lines to /repo/a.py."));
    assertEquals(
        "ok",
        ToolLines.outcome("file_edit", "Replaced the one occurrence of the text in /repo/a.py."));
    // The view an edit shows since 2026-09-30 follows the sentence, and the
    // refusal's view follows the refusal: the first line still decides.
    assertEquals(
        "ok",
        ToolLines.outcome(
            "file_edit",
            "Replaced the one occurrence of the text in /repo/a.py. The new text is on line 3"
                + " now, shown with the 3 lines either side:\n[Lines 0 to 6 of 9,"
                + " counting from 0 as offset does.]\nWrote 3 lines"));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "file_edit",
            "the text to replace is not in"
                + " /repo/a.py; it must match the file exactly, spaces and line breaks included —"
                + " read the file again and copy it\n\nThe closest lines in the file now are:\n"
                + "[Line 2 of 9, counting from 0 as offset does.]\nReplaced the one occurrence"));
    assertEquals(
        "not found",
        ToolLines.outcome(
            "file_edit",
            "there is no file at /repo/a.py;"
                + " to create it, send {\"path\", \"content\"} with the whole file"));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "file_edit",
            "the workspace is read-only to this"
                + " agent, so /repo/a.py cannot be written; its definition declares 'scopes:"));
    assertEquals("ok", ToolLines.outcome("file_delete", "Deleted /repo/a.py."));
    assertEquals("not found", ToolLines.outcome("file_delete", "there is no file at /repo/a.py"));
    assertEquals("ok", ToolLines.outcome("file_move", "Moved /a to /b."));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "file_move",
            "/a is on laptop and /b is on server," + " and a file can only be moved within one"));
    assertEquals("ok", ToolLines.outcome("file_stat", "The file /repo/a.py has 12 lines."));
    assertEquals("not found", ToolLines.outcome("file_stat", "there is no file at /repo/a.py"));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "todo_write", "todo_write needs at least one" + " operation. Nothing was changed."));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "todo_write",
            "todo_write refused: this" + " orchestration has used all of its budget"));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "todo_write",
            "There is no todo list: this run is"
                + " in no conversation, so there is nowhere to keep one."));
  }

  @Test
  void a_run_s_refusal_is_anything_but_a_status_line() {
    assertEquals(
        "refused",
        ToolLines.outcome(
            "run", "commands do not run in the global tier," + " which names no filesystem"));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "run", "run was given a 'timeout_seconds' of 0;" + " it must be at least 1."));
  }

  @Test
  void agent_run_is_refused_only_when_no_delegate_ran() {
    assertEquals(
        "refused",
        ToolLines.outcome(
            "agent_run",
            "the agent 'boss' may not call 'nobody'. The agents it may call are [helper]."));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "agent_run",
            "the agent 'helper' is one 'boss'"
                + " may call, but it is not in the set this run can delegate into"));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "agent_run",
            "the agent 'helper' has not declared"
                + " 'vision: true', so it cannot be shown a picture."));
    assertEquals(
        "refused",
        ToolLines.outcome(
            "agent_run", "'img_x' is not an image id, so" + " there is no picture to send."));
    assertEquals(
        "ok",
        ToolLines.outcome("agent_run", "the agent 'helper', shown img_1, img_2, answered:\nred"));
    assertEquals(
        "ok",
        ToolLines.outcome(
            "agent_run",
            "the agent 'helper' did not reach an answer. This run stopped at its cap"));
  }

  @Test
  void a_blank_result_is_the_tool_s_fault() {
    assertEquals(
        "error",
        ToolLines.outcome(
            "file_read",
            "the tool 'file_read' returned nothing"
                + " at all, which is a fault in the tool. Nothing can be concluded from it."));
    assertEquals(
        "error",
        ToolLines.outcome(
            "run",
            "the tool 'run' returned nothing at all,"
                + " which is a fault in the tool. Nothing can be concluded from it."));
  }

  @Test
  void a_delegate_s_ending_is_its_call_s_outcome() {
    assertEquals("ok", ToolLines.delegation(Outcome.Ending.ANSWERED));
    assertEquals("asked", ToolLines.delegation(Outcome.Ending.AWAITING));
    assertEquals("turn_cap", ToolLines.delegation(Outcome.Ending.TURN_CAP));
    assertEquals("stuck", ToolLines.delegation(Outcome.Ending.STUCK));
  }

  @Test
  void a_tool_with_no_salient_argument_is_never_parsed() {
    assertEquals("", ToolLines.salient("file_write_everything", "{\"path\":\"/a\"}"));
    assertEquals("", ToolLines.salient("memory_recall", "{ this is not even json"));
  }
}
