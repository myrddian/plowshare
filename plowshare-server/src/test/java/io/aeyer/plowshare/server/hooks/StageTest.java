package io.aeyer.plowshare.server.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class StageTest {

  /**
   * Spec 2026-09-28-hooks-reach-the-log §6: the refusal lists values(), so it cannot fall behind.
   */
  @Test
  void an_unknown_stage_is_refused_naming_every_stage_there_is() {
    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> Stage.of("log.opened"));
    for (Stage stage : Stage.values()) {
      assertTrue(refused.getMessage().contains(stage.wireName()), refused.getMessage());
    }
  }

  @Test
  void the_five_run_stages_are_not_on_a_log_and_the_nine_new_ones_are() {
    assertEquals(
        List.of(
            Stage.PROMPT_PRE, Stage.PROMPT_POST, Stage.TOOL_PRE, Stage.TOOL_POST, Stage.STEP_POST),
        Arrays.stream(Stage.values()).filter(stage -> !stage.onALog()).toList());
    assertEquals(9, Arrays.stream(Stage.values()).filter(Stage::onALog).count());
  }

  /**
   * Spec 2026-09-28-hooks-reach-the-log, amended 2026-09-29: fold.pre is gone, and a file that
   * still names it is told where its keep went rather than that it named nonsense.
   */
  @Test
  void fold_pre_is_refused_as_removed_and_points_at_fold_post() {
    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> Stage.of("fold.pre"));
    assertTrue(refused.getMessage().contains("'fold.pre' was removed"), refused.getMessage());
    assertTrue(
        refused.getMessage().contains("fold.post now sees the summary"), refused.getMessage());
    assertFalse(refused.getMessage().contains("is not a stage"), refused.getMessage());
  }
}
