package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.hooks.Approving;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.StageDone;
import io.aeyer.plowshare.server.hooks.StageShown;
import io.aeyer.plowshare.server.hooks.StageStart;
import io.aeyer.plowshare.server.hooks.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Spec 2026-09-28-hooks-reach-the-log §2.4: the in-turn gates fail closed, on the run's chain. */
class InTurnHooksTest {

    private static final HookContext RUN = new HookContext("conductor", true,
            Set.of("todo_write"), "story", "cnv_c", HookContext.SERVER).inLog("orchestration");
    private static final HookContext.Orchestration ORC =
            new HookContext.Orchestration("orc_1", "code_implementation", "code");
    private static final StageShown CODE = new StageShown("code", "write the code", 1, 3);

    private static HookRecord said(Stage stage, String decision) {
        return new HookRecord("fixture", "f.js", Tier.PROJECT, stage, null, decision, null, null,
                null, 0);
    }

    @Test
    void each_gate_is_asked_with_the_run_s_context_its_orchestration_and_its_environment() {
        List<HookContext> seen = new ArrayList<>();
        Hooks chain = new Hooks() {
            @Override
            public Gate stagePre(HookContext context, StageStart start) {
                seen.add(context);
                return Gate.NOTHING;
            }

            @Override
            public Gate approvalPre(HookContext context, Approving approving) {
                seen.add(context);
                return new Gate(null, List.of("assessed"), List.of(said(Stage.APPROVAL_PRE,
                        HookRecord.NOTE)));
            }
        };
        InTurnHooks hooks = new InTurnHooks(RUN, () -> chain);
        HookContext.RunEnvironment local = new HookContext.RunEnvironment("local", "ask", false,
                "none");

        hooks.stagePre(ORC, new StageStart(CODE, false, 3));
        Gate asked = hooks.approvalPre(local, null, new Approving(List.of("make"), "/repo", null,
                true, List.of("once")));

        assertEquals("orchestration", seen.get(0).origin());
        assertEquals(ORC, seen.get(0).orchestration());
        assertEquals("conductor", seen.get(0).agent());
        assertEquals(local, seen.get(1).environment());
        assertEquals(null, seen.get(1).orchestration());
        assertEquals(List.of("assessed"), asked.notes());
        assertEquals(List.of(), hooks.drain(), "a gate hands its records back; it parks nothing");
    }

    @Test
    void a_chain_that_throws_denies_and_is_recorded_as_the_harness() {
        InTurnHooks hooks = new InTurnHooks(RUN, () -> new Hooks() {
            @Override
            public Gate stagePost(HookContext context, StageDone done) {
                throw new IllegalStateException("boom");
            }
        });

        Gate gate = hooks.stagePost(ORC, new StageDone(CODE, "built it", null));

        assertTrue(gate.isDenied());
        assertEquals(InTurnHooks.UNJUDGED, gate.denied());
        assertEquals(HookRecord.FAILED, gate.records().get(0).decision());
        assertEquals(Tier.HARNESS, gate.records().get(0).tier());
        assertEquals(Stage.STAGE_POST, gate.records().get(0).stage());
    }

    /** A null answer is outside {@code Hooks}' contract, and it must deny rather than pass. */
    @Test
    void a_chain_that_answers_nothing_denies_and_is_recorded_as_the_harness() {
        InTurnHooks hooks = new InTurnHooks(RUN, () -> new Hooks() {
            @Override
            public Gate approvalPre(HookContext context, Approving approving) {
                return null;
            }
        });

        Gate gate = hooks.approvalPre(null, null, new Approving(List.of("make"), "/repo", null,
                true, List.of("once")));

        assertEquals(InTurnHooks.UNJUDGED, gate.denied());
        assertEquals(HookRecord.FAILED, gate.records().get(0).decision());
        assertEquals(Tier.HARNESS, gate.records().get(0).tier());
        assertEquals(Stage.APPROVAL_PRE, gate.records().get(0).stage());
    }

    @Test
    void a_chain_that_cannot_be_built_denies_too() {
        InTurnHooks hooks = new InTurnHooks(RUN, () -> {
            throw new IllegalStateException("no profile");
        });

        assertTrue(hooks.stagePre(ORC, new StageStart(CODE, false, 3)).isDenied());
    }

    @Test
    void parked_records_are_drained_once_in_the_order_they_were_parked() {
        InTurnHooks hooks = new InTurnHooks(RUN, () -> Hooks.NONE);
        HookRecord first = said(Stage.TOOL_PRE, HookRecord.ALLOW);
        HookRecord second = said(Stage.STAGE_POST, HookRecord.NOTE);

        hooks.record(List.of(first));
        Gate kept = hooks.recorded(new Gate(null, List.of(), List.of(second)));

        assertFalse(kept.isDenied());
        assertEquals(List.of(first, second), hooks.drain());
        assertEquals(List.of(), hooks.drain());
    }

    @Test
    void none_passes_every_gate_and_parks_nothing() {
        assertEquals(Gate.NOTHING, RunHooks.NONE.stagePre(ORC, new StageStart(CODE, false, 3)));
        assertEquals(Gate.NOTHING, RunHooks.NONE.stagePost(ORC, new StageDone(CODE, "s", null)));
        assertEquals(Gate.NOTHING, RunHooks.NONE.approvalPre(null, null,
                new Approving(List.of("make"), "/repo", null, true, List.of("once"))));
    }
}
