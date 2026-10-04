package io.aeyer.plowshare.server.hooks;

import java.util.List;
import java.util.Objects;

/**
 * {@code stage.post}: a locked stage item about to move from in progress to done, after every
 * system gate in front of the board let it (spec 2026-09-28-hooks-reach-the-log §3).
 *
 * @param summary the summary the board will store, the system gates' own sentences included
 * @param check the run's check command, on a checked stage, which has just passed; otherwise null
 */
public record StageDone(StageShown stage, String summary, List<String> check) {

  public StageDone {
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(summary, "summary");
    check = check == null ? null : List.copyOf(check);
  }
}
