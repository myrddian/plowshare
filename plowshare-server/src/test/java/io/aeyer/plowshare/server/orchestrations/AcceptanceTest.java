package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.CommandRunner;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The acceptance section's grammar (spec 2026-09-29 §1b). */
class AcceptanceTest {

  static final String SPEC =
      """
            # The text RPG

            Runnable with `python -m rpg.main`.

            ## Acceptance

            ```
            run: python -m rpg.main | stdin: 3 | exit: 0 | expect: Welcome to the RPG
            run: python -m pytest -q | exit: 0
            run: python -c "import rpg.save" | exit: 0 | expect: ok
            ```

            ## Out of scope

            Multiplayer.
            """;

  @Test
  void each_line_is_a_command_with_its_expectation() {
    List<Acceptance.Command> commands = Acceptance.parse(SPEC);

    assertEquals(3, commands.size());
    assertEquals(List.of("python", "-m", "rpg.main"), commands.get(0).argv());
    assertEquals("3\n", commands.get(0).stdin());
    assertEquals(0, commands.get(0).exit());
    assertEquals("Welcome to the RPG", commands.get(0).expect());
    assertNull(commands.get(1).stdin());
    assertNull(commands.get(1).expect());
    assertEquals(List.of("python", "-c", "import rpg.save"), commands.get(2).argv());
  }

  @Test
  void stdin_takes_line_breaks_and_always_ends_with_one() {
    assertEquals(
        "1\n2\n", Acceptance.parse(SPEC.replace("stdin: 3", "stdin: 1\\n2")).get(0).stdin());
  }

  @Test
  void no_section_is_refused_with_the_format() {
    Acceptance.Unwritten refused =
        assertThrows(Acceptance.Unwritten.class, () -> Acceptance.parse("# spec\n\nRunnable.\n"));
    assertTrue(refused.getMessage().contains("spec.md has no `## Acceptance` section"));
    assertTrue(refused.getMessage().contains(Acceptance.FORMAT));
  }

  @Test
  void a_pipe_into_the_command_is_refused_and_pointed_at_stdin() {
    Acceptance.Unwritten refused =
        assertThrows(
            Acceptance.Unwritten.class,
            () ->
                Acceptance.parse(
                    SPEC.replace(
                        "run: python -m rpg.main | stdin: 3",
                        "run: printf 3 | python -m rpg.main")));
    assertTrue(
        refused.getMessage().contains("'python -m rpg.main' is not `key: value`"),
        refused.getMessage());
    assertTrue(refused.getMessage().contains("line 8"), refused.getMessage());
  }

  @Test
  void a_shell_operator_or_a_missing_exit_is_refused() {
    assertThrows(
        Acceptance.Unwritten.class,
        () ->
            Acceptance.parse(
                SPEC.replace(
                    "run: python -m pytest -q | exit: 0",
                    "run: python -m pytest -q > out | exit: 0")));
    assertThrows(
        Acceptance.Unwritten.class,
        () ->
            Acceptance.parse(
                SPEC.replace("run: python -m pytest -q | exit: 0", "run: python -m pytest -q")));
  }

  /**
   * Measured 2026-09-29: {@code python -c "import x; assert ..."} was refused for its {@code ;},
   * which is Python's inside a quoted argument and never a shell's. Outside the quotes it still is
   * one.
   */
  @Test
  void an_operator_inside_a_quoted_argument_is_the_programs_and_is_allowed() {
    List<Acceptance.Command> commands =
        Acceptance.parse(
            SPEC.replace(
                "run: python -c \"import rpg.save\"",
                "run: python -c \"import rpg.save; assert rpg.save.SLOTS > 0 and 'x' < 'y'\""));

    assertEquals(
        List.of("python", "-c", "import rpg.save; assert rpg.save.SLOTS > 0 and 'x' < 'y'"),
        commands.get(2).argv());
    assertThrows(
        Acceptance.Unwritten.class,
        () ->
            Acceptance.parse(
                SPEC.replace(
                    "run: python -c \"import rpg.save\"",
                    "run: python -c \"import rpg.save\"; rm -rf build")));
  }

  /**
   * Measured 2026-09-29, orc_318D26A46144920B: a conductor put {@code # Requirement: …} lines
   * inside the block to say which requirement each command observes, and the line was refused as
   * "not run, stdin, exit or expect". A comment line or a blank one is skipped; a refusal of a
   * later line still names that line's own number in the file.
   */
  @Test
  void comment_and_blank_lines_in_the_block_are_skipped_and_later_lines_keep_their_numbers() {
    String commented =
        SPEC.replace(
            "run: python -m pytest -q | exit: 0",
            "# Requirement: the tests pass\n\n   # indented, still a comment\n"
                + "run: python -m pytest -q | exit: 0");

    List<Acceptance.Command> commands = Acceptance.parse(commented);

    assertEquals(3, commands.size());
    assertEquals(List.of("python", "-m", "pytest", "-q"), commands.get(1).argv());
    Acceptance.Unwritten refused =
        assertThrows(
            Acceptance.Unwritten.class,
            () ->
                Acceptance.parse(
                    commented.replace(
                        "run: python -c \"import rpg.save\"",
                        "rn: python -c \"import rpg.save\"")));
    assertTrue(refused.getMessage().contains("spec.md line 13 "), refused.getMessage());
  }

  /**
   * A comment reads like a heading; inside the block it ends neither the section nor the block, so
   * the requirements the verifier is shown are the same with it or without it.
   */
  @Test
  void a_comment_that_reads_like_a_heading_ends_neither_the_block_nor_the_section() {
    String commented =
        SPEC.replace(
            "run: python -m pytest -q | exit: 0",
            "# the tests\n## still the block\nrun: python -m pytest -q | exit: 0");

    assertEquals(3, Acceptance.parse(commented).size());
    assertEquals(Acceptance.requirements(SPEC), Acceptance.requirements(commented));
    assertTrue(Acceptance.requirements(commented).contains("## Out of scope"));
  }

  @Test
  void a_block_of_only_comments_has_no_command_in_it() {
    String onlyComments = SPEC.replaceAll("(?m)^run: .*$", "# nothing yet");

    Acceptance.Unwritten refused =
        assertThrows(Acceptance.Unwritten.class, () -> Acceptance.parse(onlyComments));
    assertTrue(refused.getMessage().contains("block has no line in it"), refused.getMessage());
  }

  /**
   * Task 2's review: input is bounded as output is. A {@code stdin:} past what a command may be
   * given is refused here, where the conductor can still change it, and not at the done move where
   * the provider would refuse it after the person had been asked to allow it.
   */
  @Test
  void a_stdin_past_the_bound_is_refused_with_the_bound() {
    String huge = "x".repeat(CommandRunner.MAX_STDIN_BYTES);

    Acceptance.Unwritten refused =
        assertThrows(
            Acceptance.Unwritten.class,
            () -> Acceptance.parse(SPEC.replace("stdin: 3", "stdin: " + huge)));

    assertTrue(refused.getMessage().contains("line 8"), refused.getMessage().substring(0, 80));
    assertTrue(
        refused
            .getMessage()
            .contains(
                "`stdin:` is "
                    + (CommandRunner.MAX_STDIN_BYTES + 1)
                    + " bytes, more than the "
                    + CommandRunner.MAX_STDIN_BYTES
                    + " a command may be given"),
        "the bound is named");
    assertEquals(
        CommandRunner.MAX_STDIN_BYTES,
        Acceptance.parse(
                SPEC.replace("stdin: 3", "stdin: " + "x".repeat(CommandRunner.MAX_STDIN_BYTES - 1)))
            .get(0)
            .stdin()
            .length(),
        "exactly the bound, its line break included, is allowed");
  }

  // --- runs-for and check: lines (spec 2026-10-01, the acceptance checker §1) ---------------

  static final String GAME =
      """
            # Space Invaders

            A window opens and the player's ship moves with the arrow keys.

            ## Acceptance

            ```
            run: python -m pytest -q | exit: 0
            # the game starts and stays up
            run: python game/main.py | runs-for: 5s
            check: run `python game/main.py` and press the arrow keys | expect: a window with the ship, which moves left and right
            ```
            """;

  @Test
  void a_runs_for_line_needs_no_exit_and_says_how_long_it_must_keep_running() {
    Acceptance.Section section = Acceptance.section(GAME);

    assertEquals(2, section.commands().size());
    Acceptance.Command game = section.commands().get(1);
    assertEquals(List.of("python", "game/main.py"), game.argv());
    assertEquals(Integer.valueOf(5), game.runsFor());
    assertNull(section.commands().get(0).runsFor());
    assertEquals(List.of(section.commands().get(0), game), Acceptance.parse(GAME));
  }

  @Test
  void runs_for_takes_whole_seconds_with_or_without_the_s_and_no_exit_beside_it() {
    assertEquals(
        Integer.valueOf(7),
        Acceptance.section(GAME.replace("runs-for: 5s", "runs-for: 7"))
            .commands()
            .get(1)
            .runsFor());
    Acceptance.Unwritten both =
        assertThrows(
            Acceptance.Unwritten.class,
            () -> Acceptance.section(GAME.replace("runs-for: 5s", "exit: 0 | runs-for: 5s")));
    assertTrue(
        both.getMessage().contains("`exit:` and `runs-for:` cannot both be given"),
        both.getMessage());
    for (String bad : List.of("0s", "five", "-3s", "601s", "1.5s")) {
      Acceptance.Unwritten refused =
          assertThrows(
              Acceptance.Unwritten.class,
              () -> Acceptance.section(GAME.replace("runs-for: 5s", "runs-for: " + bad)),
              bad);
      assertTrue(
          refused.getMessage().contains("`runs-for:` is whole seconds"), refused.getMessage());
    }
  }

  @Test
  void a_check_line_is_the_person_s_with_what_to_do_and_what_they_should_see() {
    Acceptance.Section section = Acceptance.section(GAME);

    assertEquals(1, section.checks().size());
    Acceptance.Check check = section.checks().get(0);
    assertEquals("run `python game/main.py` and press the arrow keys", check.what());
    assertEquals("a window with the ship, which moves left and right", check.expect());
    assertTrue(check.line().startsWith("check: run `python game/main.py`"));
  }

  @Test
  void a_check_line_needs_what_the_person_should_see_and_takes_no_command_keys() {
    Acceptance.Unwritten noExpect =
        assertThrows(
            Acceptance.Unwritten.class,
            () ->
                Acceptance.section(
                    GAME.replace(
                        " | expect: a window with the ship, which moves left and right", "")));
    assertTrue(noExpect.getMessage().contains(Acceptance.CHECK_FORMAT), noExpect.getMessage());
    assertTrue(noExpect.getMessage().contains("`expect:`"), noExpect.getMessage());
    assertThrows(
        Acceptance.Unwritten.class,
        () ->
            Acceptance.section(GAME.replace("| expect: a window", "| exit: 0 | expect: a window")));
  }

  @Test
  void a_section_of_only_check_lines_has_no_command_and_is_written() {
    String onlyChecks = GAME.replaceAll("(?m)^run: .*$", "");

    Acceptance.Section section = Acceptance.section(onlyChecks);

    assertEquals(List.of(), section.commands());
    assertEquals(1, section.checks().size());
  }

  @Test
  void a_block_with_neither_kind_of_line_says_both_formats() {
    String empty = GAME.replaceAll("(?m)^(run|check): .*$", "");

    Acceptance.Unwritten refused =
        assertThrows(Acceptance.Unwritten.class, () -> Acceptance.section(empty));
    assertTrue(refused.getMessage().contains(Acceptance.FORMAT), refused.getMessage());
    assertTrue(refused.getMessage().contains(Acceptance.CHECK_FORMAT), refused.getMessage());
  }
}
