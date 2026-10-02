package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What orchestration_validate answers about a trial — spec 2026-09-29-orchestration-studio §3.3. */
class DraftReportTest {

    private static final Set<String> TOOLS = Set.of("file_read", "file_edit", "agent_run");

    private static OrchestrationDefinition parsed(String frontmatter, String body) {
        return OrchestrationRegistry.parsePinned("triage", "draft", "---\nname: triage\n"
                + "description: Triages bugs.\nmodel: m\nmax-turns: 2\nmax-model-calls: 4\n"
                + frontmatter + "---\n" + body + "\n", TOOLS, OrchestrationDefinition.Tier.PROJECT);
    }

    private static OrchestrationResolver.Trial clean(OrchestrationDefinition draft) {
        return new OrchestrationResolver.Trial(draft, null, Map.of(), null, null, false,
                Map.of("triage", draft), false);
    }

    private static AgentDefinition caller(List<String> tools) {
        return parsed("tools: " + tools + "\nstages:\n  - {id: goal}\n", "c").conductor();
    }

    @Test
    void draws_the_stage_graph_and_the_returns() {
        OrchestrationDefinition draft = parsed("""
                tools: [file_read]
                stages:
                  - {id: goal, done-when: "goal.md exists"}
                  - {id: fix, check: required, may-return-to: [goal]}
                max-returns: 2
                """, "Write goal.md, then fix.");

        DraftReport report = DraftReport.of(clean(draft), caller(List.of("file_read")), true);

        assertTrue(report.summary().contains("""
                Stages:
                  1. goal
                  2. fix — check — may return to: goal
                  max-returns: 2"""), report.summary());
        assertTrue(report.installable());
    }

    @Test
    void marks_a_grant_beyond_the_caller_and_refuses_it_when_nobody_attends() {
        OrchestrationDefinition draft = parsed("tools: [file_read, file_edit]\nstages:\n"
                + "  - {id: goal}\n", "Edit files.");

        DraftReport attended = DraftReport.of(clean(draft), caller(List.of("file_read")), true);
        assertEquals(List.of("tool file_edit"), attended.beyondCaller());
        assertTrue(attended.summary().contains("  tool file_edit (beyond caller)"), attended.summary());
        assertTrue(attended.installable());

        assertTrue(attended.summary().startsWith("Grants beyond triage:\n  tool file_edit\n"
                + "Stages:"), attended.summary());

        DraftReport unattended = DraftReport.of(clean(draft), caller(List.of("file_read")), false);
        assertEquals(List.of("tool file_edit is beyond what triage holds, and this run was not"
                + " started by a person, so it cannot be granted."), unattended.refusals());
        assertFalse(unattended.installable());
    }

    @Test
    void lints_a_stage_nothing_checks_a_command_nothing_runs_and_an_unnamed_callee() {
        OrchestrationDefinition draft = parsed("""
                tools: [file_read]
                calls: [coder]
                stages:
                  - {id: goal}
                  - {id: fix, done-when: "`make test` passes"}
                """, "Write the goal.");

        DraftReport report = DraftReport.of(clean(draft), null, true);

        assertEquals(List.of(
                "stage 'goal' has no check and no acceptance: it is done when the conductor says so.",
                "stage 'fix' has no check and no acceptance: it is done when the conductor says so.",
                "stage 'fix' names a command in its done-when (make test) but has no check: nothing"
                        + " runs it.",
                "callee 'coder' is in calls: but the prompt never names it: the conductor will not"
                        + " know when to use it."), report.lints());
    }

    @Test
    void lints_an_acceptance_stage_when_the_definition_names_no_artifacts() {
        OrchestrationDefinition draft = parsed("""
                tools: [file_read]
                stages:
                  - {id: spec, acceptance: written}
                  - {id: accept, acceptance: required, may-return-to: [spec]}
                """, "Write spec.md's acceptance section, then run it.");

        DraftReport report = DraftReport.of(clean(draft), null, true);

        assertTrue(report.lints().contains("a stage has acceptance but the definition names no"
                + " artifacts: spec.md is read from the artifacts directory, so that stage can"
                + " never be marked done."), report.lints().toString());
    }

    /** Final review 4: a trial with no draft and no refusal is never installable. */
    @Test
    void a_trial_with_no_draft_and_no_refusal_is_refused_as_not_loaded() {
        OrchestrationResolver.Trial trial = new OrchestrationResolver.Trial(null, null, Map.of(),
                null, null, false, Map.of(), false);

        DraftReport report = DraftReport.of(trial, null, true);

        assertEquals(List.of("the draft did not load"), report.refusals());
        assertFalse(report.installable());
    }

    @Test
    void a_refused_draft_is_its_refusal_and_everything_it_would_disable() {
        OrchestrationResolver.Trial trial = new OrchestrationResolver.Trial(null,
                "the orchestration definition 'triage' (draft) has no stages", Map.of(
                        "outer", "outer is in a cycle"), null, null, false, Map.of(), false);

        DraftReport report = DraftReport.of(trial, null, true);

        assertEquals(List.of("the orchestration definition 'triage' (draft) has no stages",
                "outer would no longer load: outer is in a cycle"), report.refusals());
        assertTrue(report.render().startsWith("REFUSED — this draft would not install:"),
                report.render());
    }
}
