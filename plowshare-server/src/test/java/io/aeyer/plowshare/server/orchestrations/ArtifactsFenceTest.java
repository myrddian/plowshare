package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.RunExtras;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rule 4 (spec 2026-09-29 §3): a conductor's file tools reach its own artifacts and no further. */
class ArtifactsFenceTest {

  private static final String ROOT_DIR =
      "docs/orchestrations/2026-09-28-implement_specification-orc_31893856D8F462A1/";
  private static final String PHASE_DIR =
      ROOT_DIR + "phases/03-character/2026-09-28-code_implementation-orc_2/";

  /** Measured: the conductor wrote rpg/*.py itself, eleven edits. */
  @Test
  void the_measured_edit_of_the_product_is_refused_with_the_way_to_do_it() {
    RunExtras.Fence fence = ArtifactsFence.of(PHASE_DIR);

    assertEquals(
        "file_edit refused: /Users/e/rpg/rpg/main.py is outside this run's"
            + " directory, "
            + PHASE_DIR.substring(0, PHASE_DIR.length() - 1)
            + ", so nothing"
            + " was written. You write only your own documents there. Code and tests are"
            + " written by the coder, called with agent_run at the `tests` and `code` stages;"
            + " until then they are only named, in spec.md and plan.md, and a test found wrong"
            + " is the coder's to correct: give it the reason, returning to `tests` first from"
            + " `code` or `review`.",
        fence.refusal(
            "file_edit", "{\"path\":\"/Users/e/rpg/rpg/main.py\",\"content\":\"print()\"}"));
  }

  /**
   * Measured 2026-09-29 (orc_318D8534E8B9D04B): answered only "That is a phase's work: start it
   * with orchestrate_code_implementation.", a conductor at the spec stage counted its refused test
   * files as written and was told to have them written at once. The refusal says it refused, and
   * orders nothing.
   */
  @Test
  void the_refusal_says_it_refused_and_orders_nothing() {
    String said =
        ArtifactsFence.of(ROOT_DIR, ArtifactsFence.PHASE_REFUSAL)
            .refusal(
                "file_edit", "{\"path\":\"/Users/e/rpg/tests/test_player.py\",\"content\":\"x\"}");

    assertTrue(said.startsWith("file_edit refused: /Users/e/rpg/tests/test_player.py"), said);
    assertTrue(said.contains("nothing was written"), said);
    assertTrue(said.contains("until then they are only named"), said);
    assertFalse(said.contains("orchestrate_code_implementation"), said);
  }

  /** A refusal: says so, and ends with who writes code and tests. */
  private static void assertRefused(String who, String said) {
    assertTrue(
        said != null
            && said.contains(" refused: ")
            && said.contains("so nothing was written")
            && said.endsWith(who),
        said);
  }

  @Test
  void its_own_directory_and_its_phase_directory_are_its_to_write() {
    RunExtras.Fence fence = ArtifactsFence.of(PHASE_DIR);

    assertNull(
        fence.refusal(
            "file_edit", "{\"path\":\"/Users/e/rpg/" + PHASE_DIR + "spec.md\",\"content\":\"x\"}"));
    assertNull(
        fence.refusal(
            "file_edit",
            "{\"path\":\"/Users/e/rpg/"
                + ROOT_DIR
                + "phases/03-character/notes.md\",\"content\":\"x\"}"));
    for (String artifact : List.of("goal.md", "spec.md", "plan.md", "test-design.md")) {
      assertNull(
          fence.refusal(
              "file_edit",
              "{\"path\":\"/Users/e/rpg/"
                  + PHASE_DIR
                  + artifact
                  + "\",\"old\":\"a\",\"new\":\"b\"}"),
          artifact);
    }
  }

  /** A phase's fence is its phase and its own directory, not the whole of the root's. */
  @Test
  void a_phase_may_not_write_its_root_s_own_artifacts_or_a_sibling_phase_s() {
    RunExtras.Fence fence = ArtifactsFence.of(PHASE_DIR);

    assertRefused(
        ArtifactsFence.REFUSAL,
        fence.refusal(
            "file_edit", "{\"path\":\"/Users/e/rpg/" + ROOT_DIR + "spec.md\",\"content\":\"x\"}"));
    assertRefused(
        ArtifactsFence.REFUSAL,
        fence.refusal(
            "file_edit",
            "{\"path\":\"/Users/e/rpg/"
                + ROOT_DIR
                + "phases/04-combat/plan.md\",\"content\":\"x\"}"));
  }

  @Test
  void a_path_that_climbs_out_is_judged_where_it_lands() {
    RunExtras.Fence fence = ArtifactsFence.of(ROOT_DIR);

    assertRefused(
        ArtifactsFence.REFUSAL,
        fence.refusal(
            "file_edit",
            "{\"path\":\"/Users/e/rpg/" + ROOT_DIR + "../../../rpg/main.py\",\"content\":\"x\"}"));
    assertRefused(
        ArtifactsFence.REFUSAL,
        fence.refusal(
            "file_move",
            "{\"path\":\"/Users/e/rpg/" + ROOT_DIR + "plan.md\",\"to\":\"/Users/e/rpg/plan.md\"}"));
    // Backslashes are separators to a Windows client, so they climb too.
    assertRefused(
        ArtifactsFence.REFUSAL,
        fence.refusal(
            "file_edit",
            "{\"path\":\"/Users/e/rpg/"
                + ROOT_DIR
                + "..\\\\..\\\\..\\\\rpg\\\\main.py\",\"content\":\"x\"}"));
    // A directory that merely begins with the run's is not the run's.
    assertRefused(
        ArtifactsFence.REFUSAL,
        fence.refusal(
            "file_delete",
            "{\"path\":\"/Users/e/rpg/"
                + ROOT_DIR.substring(0, ROOT_DIR.length() - 1)
                + "-other/x.md\"}"));
  }

  @Test
  void reading_is_never_fenced_and_no_directory_fences_nothing() {
    assertNull(ArtifactsFence.of(ROOT_DIR).refusal("file_read", "{\"path\":\"/etc/hosts\"}"));
    assertSame(RunExtras.Fence.NONE, ArtifactsFence.of(null));
    assertNull(ArtifactsFence.of(null).refusal("file_edit", "{\"path\":\"/x\"}"));
  }

  /** Unreadable arguments are the tool's to refuse, in its own words; the fence adds nothing. */
  @Test
  void arguments_the_tool_itself_would_refuse_are_left_to_it() {
    RunExtras.Fence fence = ArtifactsFence.of(ROOT_DIR);

    assertNull(fence.refusal("file_edit", "not json"));
    assertNull(fence.refusal("file_edit", "{\"content\":\"x\"}"));
  }

  /**
   * The refusal names the way that conductor has: code_implementation has a coder,
   * implement_specification has none and hands code to a phase run.
   */
  @Test
  void the_refusal_names_the_conductor_s_own_way_to_have_it_done() {
    assertEquals(
        ArtifactsFence.REFUSAL, ArtifactsFence.refusalFor("code_implementation", List.of()));
    assertEquals(
        ArtifactsFence.PHASE_REFUSAL,
        ArtifactsFence.refusalFor("implement_specification", List.of("code_reviewer")));
    // Any other definition: by whether it can call a coder at all; it names no stage, since its
    // stages are its own.
    assertEquals(
        ArtifactsFence.CODER_ELSEWHERE,
        ArtifactsFence.refusalFor("my_flow", List.of("coder", "code_reviewer")));
    assertEquals(
        ArtifactsFence.PHASE_ELSEWHERE,
        ArtifactsFence.refusalFor("my_flow", List.of("research_critic")));

    assertRefused(
        ArtifactsFence.PHASE_REFUSAL,
        ArtifactsFence.of(ROOT_DIR, ArtifactsFence.PHASE_REFUSAL)
            .refusal("file_edit", "{\"path\":\"/Users/e/rpg/rpg/main.py\",\"content\":\"x\"}"));
  }

  /**
   * Task 6's review: the stored directory is normalised as the judged paths are, so one given as
   * {@code ./docs/…} or {@code docs//…} still fences to the directory it names — before, it never
   * matched a normalised path and every write, its own spec.md's included, was refused.
   */
  @Test
  void a_stored_directory_is_normalised_as_the_paths_it_judges_are() {
    for (String written :
        List.of(
            "./" + ROOT_DIR,
            ROOT_DIR.replace("orchestrations/", "orchestrations//"),
            "docs/x/../" + ROOT_DIR.substring("docs/".length()))) {
      RunExtras.Fence fence = ArtifactsFence.of(written);

      assertNull(
          fence.refusal(
              "file_edit", "{\"path\":\"/Users/e/rpg/" + ROOT_DIR + "spec.md\",\"content\":\"x\"}"),
          written);
      assertRefused(
          ArtifactsFence.REFUSAL,
          fence.refusal("file_edit", "{\"path\":\"/Users/e/rpg/rpg/main.py\",\"content\":\"x\"}"));
    }
    assertSame(
        RunExtras.Fence.NONE,
        ArtifactsFence.of("./"),
        "a directory that is the"
            + " project itself holds every path, so it is no fence, and is said to be none");
  }
}
