package io.aeyer.plowshare.server.hooks;

import java.util.Objects;

/**
 * One orchestration stage as {@code stage.*} is shown it (spec 2026-09-28-hooks-reach-the-log §3).
 *
 * @param id the stage's id, as the definition names it
 * @param title the stage item's text on the conductor's list
 * @param index where it stands in the run's stage order, from zero
 * @param count how many stages the run has
 */
public record StageShown(String id, String title, int index, int count) {

    public StageShown {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
    }
}
