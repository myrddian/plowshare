package io.aeyer.plowshare.server.todos;

import java.util.List;
import java.util.Objects;

/**
 * What the harness holds one orchestration's stages to (spec §4.3), looked up per conversation.
 *
 * @param stages in the definition's order, which is the order that counts, not {@code position}
 * @param returnsUsed returns already counted for this orchestration
 * @param countReturn records one more return; run inside the todo write's transaction
 */
public record StageRules(List<Stage> stages, int maxReturns, int returnsUsed, Runnable countReturn) {

    public StageRules {
        stages = List.copyOf(stages);
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("a stage policy needs at least one stage");
        }
        if (maxReturns < 0) {
            throw new IllegalArgumentException("maxReturns must not be negative");
        }
        if (returnsUsed < 0) {
            throw new IllegalArgumentException("returnsUsed must not be negative");
        }
        Objects.requireNonNull(countReturn, "countReturn");
    }

    /** @param checked the harness runs the run's check whenever this stage is marked done —
     *      spec 2026-09-26
     *  @param acceptance {@code "written"} on the stage whose done move reads spec.md's {@code
     *      ## Acceptance} section, {@code "required"} on the stage whose done move runs it, or
     *      {@code null} for a stage that is neither — spec 2026-09-29 §1b
     *  @param holdsPhases the definition's {@code children: phases} stage, whose child todos are
     *      the phases; a refusal over children left under another stage names it */
    public record Stage(String id, List<String> mayReturnTo, boolean checked, String acceptance,
            boolean holdsPhases) {
        public Stage {
            mayReturnTo = List.copyOf(mayReturnTo);
        }

        public Stage(String id, List<String> mayReturnTo, boolean checked, String acceptance) {
            this(id, mayReturnTo, checked, acceptance, false);
        }

        public Stage(String id, List<String> mayReturnTo, boolean checked) {
            this(id, mayReturnTo, checked, null);
        }

        public Stage(String id, List<String> mayReturnTo) {
            this(id, mayReturnTo, false);
        }
    }
}
