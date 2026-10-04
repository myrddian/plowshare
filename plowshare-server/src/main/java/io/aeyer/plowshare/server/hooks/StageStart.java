package io.aeyer.plowshare.server.hooks;

import java.util.Objects;

/**
 * {@code stage.pre}: a locked stage item about to move to in progress, from pending (a start) or
 * from done (a return).
 *
 * @param returning whether it is a return to a stage already done
 * @param returnsLeft how many returns the run has left once this move stands
 */
public record StageStart(StageShown stage, Boolean returning, Integer returnsLeft) {

  public StageStart {
    Objects.requireNonNull(stage, "stage");
  }
}
