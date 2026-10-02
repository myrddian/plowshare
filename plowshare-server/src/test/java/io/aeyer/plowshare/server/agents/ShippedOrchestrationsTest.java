package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Trigger;
import io.aeyer.plowshare.server.agents.OrchestrationRegistry.Layer;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.todos.LockedMoves;
import io.aeyer.plowshare.server.todos.StageMoves;
import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The shipped orchestrations, read the way a boot reads them: the classpath's {@code
 * orchestrations} location through the real parser and registry, against the tools this server
 * binds and the shipped agents they call. {@code DefinitionChecks.NONE} stands in for the boot's
 * model and sampling checks, which need a dispatcher; {@code OrchestrationRegistryTest} does the
 * same.
 */
class ShippedOrchestrationsTest {

    /** By path and not through the classpath: the test source set publishes an {@code agents}
     *  directory of fixtures too. {@code ShippedExposureTest} owns the note. */
    private static final Path SHIPPED_AGENTS = Path.of("src/main/resources/agents");

    static OrchestrationRegistry.Loaded shipped() {
        return OrchestrationRegistry.read(
                List.of(new Layer(OrchestrationDefinition.Tier.SHIPPED,
                        new ClasspathDefinitions(ClasspathDefinitions.SHIPPED_ORCHESTRATIONS))),
                BoundTools.boundByThisServer(),
                new AgentRegistry(AgentRegistry.load(SHIPPED_AGENTS, BoundTools.boundByThisServer())),
                DefinitionChecks.NONE);
    }

    static OrchestrationDefinition definition(String name) {
        OrchestrationRegistry.Loaded loaded = shipped();
        OrchestrationDefinition definition = loaded.enabled().get(name);
        assertNotNull(definition, () -> name + " did not load: " + loaded.disabled());
        return definition;
    }

    /** The conductor's prompt with its line breaks folded, so a phrase can be found across a wrap. */
    static String flowed(String name) {
        return definition(name).conductor().prompt().replaceAll("\\s+", " ");
    }

    @Test
    void every_shipped_orchestration_loads_with_no_refusal() {
        assertEquals(Map.of(), shipped().disabled());
    }

    /** The shipped conductors, against the tools this server really binds: each that names
     *  callees can reach them. See {@code OrchestrationParserTest} for the measured failure. */
    @Test
    void every_shipped_conductor_that_names_calls_can_reach_them() {
        for (OrchestrationDefinition definition : shipped().enabled().values()) {
            if (!definition.conductor().calls().isEmpty()) {
                assertTrue(definition.conductor().canDelegate(), () -> definition.name()
                        + " names calls " + definition.conductor().calls()
                        + " but is not offered agent_run: " + definition.conductor().tools());
            }
        }
    }

    /** Measured 2026-09-25: a phase with no coder to reach asked its caller to run the tests,
     *  and its caller invented the output. Running is the coder's; asking is for what to build. */
    @Test
    void code_implementation_never_asks_its_caller_to_run_anything() {
        String prompt = flowed("code_implementation");
        assertTrue(prompt.contains("Never ask your caller to run, test or check anything"), prompt);
    }

    /** The other half of the same measurement: the parent answered "1 passed" for a run it never
     *  saw, and marked the phase done on that answer while the phase was still going. */
    @Test
    void implement_specification_never_answers_with_a_result_it_did_not_see() {
        String prompt = flowed("implement_specification");
        assertTrue(prompt.contains("You run nothing"), prompt);
        assertTrue(prompt.contains("never write output you did not see"), prompt);
        assertTrue(prompt.contains("done when its child has finished"), prompt);
    }

    /** A waiting root polled its phase until the stuck advisor called it a loop and the root
     *  cancelled a healthy child. The prompt says how a wait ends and that slow is not stuck. */
    @Test
    void implement_specification_says_how_to_stop_while_a_phase_runs() {
        String prompt = flowed("implement_specification");
        assertTrue(prompt.contains("`orchestration_status` on a child you started that is still"
                + " running or waiting ends your turn"), prompt);
        assertTrue(prompt.contains("Never cancel a phase for taking its time"), prompt);
    }

    @Test
    void code_implementation_keeps_the_write_grant_and_the_budget_of_the_whole_tree() {
        OrchestrationDefinition definition = definition("code_implementation");
        assertEquals(List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)), definition.conductor().scopes());
        assertEquals(3, definition.maxReturns());
        assertEquals("reasoning", definition.conductor().model());
        assertEquals(60, definition.conductor().maxTurns());
        assertEquals(400, definition.conductor().maxModelCalls());
    }

    @Test
    void code_implementation_checks_code_and_review_and_says_how_the_check_is_set() {
        OrchestrationDefinition definition = definition("code_implementation");
        assertEquals(List.of("code", "review"), definition.stages().stream()
                .filter(OrchestrationDefinition.Stage::checked)
                .map(OrchestrationDefinition.Stage::id).toList());
        String prompt = flowed("code_implementation");
        assertTrue(prompt.contains("orchestration_check"), prompt);
        assertTrue(prompt.contains("the harness runs it"), prompt);
        assertFalse(prompt.contains("the last time the coder ran it"),
                "the result should name the run's check, not the coder's own run: " + prompt);
    }

    @Test
    void code_implementation_designs_its_tests_before_it_writes_them() {
        OrchestrationDefinition definition = definition("code_implementation");
        assertEquals(List.of("goal", "spec", "plan", "test_design", "tests", "code", "review",
                "acceptance"),
                definition.stages().stream().map(OrchestrationDefinition.Stage::id).toList());
        assertEquals(List.of("tests", "code"), definition.stages().get(6).mayReturnTo());
        assertEquals(List.of("test_designer", "coder", "code_reviewer"), definition.conductor().calls());
    }

    /**
     * Measured 2026-09-29/30, orc_318DFD3782228160: a conductor that found the tests wrong during
     * {@code code} had no legal path — told to correct the file in place with {@code file_edit},
     * which its fence refuses for any test file, and refused eleven times "stage 'code' does not
     * list 'tests' in may-return-to". {@code code} may now return to {@code tests}; review and
     * acceptance return where they did.
     */
    @Test
    void code_implementation_code_may_return_to_tests_and_the_return_counts() {
        OrchestrationDefinition definition = definition("code_implementation");
        List<OrchestrationDefinition.Stage> stages = definition.stages();
        assertEquals("code", stages.get(5).id());
        assertEquals(List.of("tests"), stages.get(5).mayReturnTo());
        assertEquals(List.of("tests", "code"), stages.get(6).mayReturnTo());
        assertEquals(List.of("code"), stages.get(7).mayReturnTo());

        AtomicInteger counted = new AtomicInteger();
        StageRules rules = new StageRules(stages.stream()
                .map(stage -> new StageRules.Stage(stage.id(), stage.mayReturnTo(), stage.checked(),
                        stage.acceptance()))
                .toList(), definition.maxReturns(), 0, counted::incrementAndGet);
        Instant t0 = Instant.parse("2026-09-30T09:00:00Z");
        List<TodoItem> list = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            String id = stages.get(i).id();
            TodoStatus status = i < 5 ? TodoStatus.DONE
                    : i == 5 ? TodoStatus.IN_PROGRESS : TodoStatus.PENDING;
            list.add(new TodoItem("td_" + id, "cnv_1", null, i, id, status, i < 5 ? "s" : null,
                    true, id, t0));
        }

        LockedMoves.Decision decision = new StageMoves(conversation -> Optional.of(rules))
                .decide(list.get(4), TodoStatus.IN_PROGRESS, null, list);

        LockedMoves.Allowed allowed = assertInstanceOf(LockedMoves.Allowed.class, decision);
        allowed.effects().forEach(Runnable::run);
        assertEquals(1, counted.get(), "a return from code to tests is one of the three returns");
    }

    /**
     * The same run: the prompt told the conductor to "Correct the file that is wrong in place with
     * `file_edit`" — tests included — and its fence refuses every path outside its own documents,
     * 17 times on test files. The conductor corrects its own documents; a wrong test goes back to
     * the coder, through a return to {@code tests} when the conductor is past it.
     */
    @Test
    void code_implementation_sends_a_wrong_test_back_to_the_coder_and_edits_only_its_documents() {
        String prompt = flowed("code_implementation");
        assertFalse(prompt.contains("Correct the file that is wrong in place with `file_edit`"),
                prompt);
        assertFalse(prompt.contains("the design, the plan or the tests are wrong. Correct"), prompt);
        assertTrue(prompt.contains("A wrong test file is the coder's to change"), prompt);
        assertTrue(prompt.contains("return to `tests`"), prompt);
        assertTrue(prompt.contains("contradicts `test-design.md` or another test"), prompt);
        assertTrue(prompt.contains("never `file_edit` a test or a code file yourself"), prompt);
        assertTrue(prompt.contains("without weakening them"), prompt);
    }

    /**
     * Measured 2026-09-30, orc_3190C667F18B8E57: at {@code review} the conductor believed a
     * reviewer's "this test fails" over the harness's check, which had just passed with that test,
     * and returned to {@code code} on it twice. A claim the harness's result contradicts is not
     * grounds for a return; untested wrong behaviour is a test to add, in {@code tests}.
     */
    @Test
    void code_implementation_weighs_a_review_finding_against_the_harness_s_check_result() {
        String prompt = flowed("code_implementation");
        assertTrue(prompt.contains("the harness hands `code_reviewer` the run's check as it last"
                + " ran it"), prompt);
        assertTrue(prompt.contains("is not grounds to return to `code` on that claim alone"),
                prompt);
        assertTrue(prompt.contains("Weigh the finding on its own reasoning"), prompt);
        assertTrue(prompt.contains("that is a test to add: return to `tests`"), prompt);
        assertTrue(prompt.contains("Do not tell the reviewer what the check showed yourself"),
                prompt);
    }

    @Test
    void a_phase_narrows_a_given_spec_and_writes_where_its_context_says() {
        String prompt = flowed("code_implementation");
        assertTrue(prompt.contains("narrowing work, not authoring work"), prompt);
        assertTrue(prompt.contains("do not re-derive"), prompt);
        assertTrue(prompt.contains("fill in `<NN>` and `<slug>`"), prompt);
        assertFalse(prompt.contains("the directory your context names"),
                "the phrase that read two ways: " + prompt);
        assertTrue(prompt.contains("test-design.md"), prompt);
    }

    @Test
    void code_implementation_still_stands_alone() {
        // it holds no orchestrations grant, so a phase cannot start children
        assertEquals(List.of(), definition("code_implementation").conductor().orchestrations());
        // and it keeps its own triggers and artifacts template for a standalone run
        assertEquals("docs/orchestrations/{date}-{name}-{id}/", definition("code_implementation").artifacts());
        assertEquals(List.of(new Trigger("implement a feature", false),
                new Trigger("build a feature", false), new Trigger("/implement", true)),
                definition("code_implementation").triggers());
    }

    @Test
    void code_implementation_reads_and_writes_its_artifacts_and_leaves_running_to_the_coder() {
        List<String> tools = definition("code_implementation").conductor().tools();
        assertEquals(List.of(FileTools.ROOTS_NAME, FileTools.GLOB_NAME, FileTools.GREP_NAME,
                FileTools.READ_NAME, FileTools.STAT_NAME, FileTools.EDIT_NAME,
                MemoryTools.RECALL_NAME, MemoryTools.READ_NAME, AgentRegistry.AGENT_RUN), tools);
        assertFalse(tools.contains(RunTool.NAME), "the coder runs the tests; the conductor does not");
    }

    @Test
    void code_implementation_is_told_who_does_each_stage_and_when_to_ask() {
        String prompt = flowed("code_implementation");
        assertTrue(prompt.contains("orchestration_ask"), prompt);
        assertTrue(prompt.contains("You write the goal, the spec and the plan yourself"), prompt);
        assertTrue(prompt.contains("`coder` writes the tests first"), prompt);
        assertTrue(prompt.contains("`code_reviewer`"), prompt);
        assertTrue(prompt.contains("a summary"), prompt);
        assertTrue(prompt.contains("orchestration_finish"), prompt);
    }

    @Test
    void implement_specification_finds_the_spec_plans_phases_and_reviews_the_change() {
        OrchestrationDefinition definition = definition("implement_specification");
        assertEquals(List.of("goal", "spec", "plan", "phases", "review", "acceptance"),
                definition.stages().stream().map(OrchestrationDefinition.Stage::id).toList());
        assertEquals(List.of("phases"), definition.stages().get(4).mayReturnTo());
        assertEquals(List.of("code_reviewer"), definition.conductor().calls());
        assertEquals(List.of("code_implementation"), definition.conductor().orchestrations());
        assertEquals("docs/orchestrations/{date}-{name}-{id}/", definition.artifacts());
        assertEquals(2, definition.maxReturns());
        assertEquals(List.of(new Trigger("implement the specification", false),
                new Trigger("spec driven", false), new Trigger("/deliver", true)),
                definition.triggers());
    }

    /**
     * Measured 2026-09-29/30, orc_318DFD3782228160: the root added its phase items as children of
     * the {@code plan} stage item, ran two, and was refused eleven times "`plan` has N not done"
     * before it re-added them under {@code phases}. The definition says which stage holds the
     * phases, so the refusal can name it; the prompt says it where the children are added.
     */
    @Test
    void implement_specification_marks_phases_as_the_stage_that_holds_them() {
        List<OrchestrationDefinition.Stage> stages = definition("implement_specification").stages();
        assertEquals(List.of("phases"), stages.stream()
                .filter(OrchestrationDefinition.Stage::holdsPhases)
                .map(OrchestrationDefinition.Stage::id).toList());
        assertTrue(definition("code_implementation").stages().stream()
                .noneMatch(OrchestrationDefinition.Stage::holdsPhases));
        String prompt = flowed("implement_specification");
        assertTrue(prompt.contains("children of the `phases` stage item, never of `plan`"), prompt);
    }

    @Test
    void implement_specification_delegates_its_spending_and_says_so() {
        AgentDefinition conductor = definition("implement_specification").conductor();
        assertEquals(200, conductor.maxModelCalls());
        assertEquals(80, conductor.maxTurns());
        assertEquals(List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)), conductor.scopes());
    }

    @Test
    void implement_specification_writes_its_artifacts_and_leaves_running_to_the_phases() {
        AgentDefinition conductor = definition("implement_specification").conductor();
        assertEquals("reasoning", conductor.model());
        assertEquals(List.of(FileTools.ROOTS_NAME, FileTools.GLOB_NAME, FileTools.GREP_NAME,
                FileTools.READ_NAME, FileTools.STAT_NAME, FileTools.EDIT_NAME,
                MemoryTools.RECALL_NAME, MemoryTools.READ_NAME, AgentRegistry.AGENT_RUN),
                conductor.tools());
        assertFalse(conductor.tools().contains(RunTool.NAME),
                "each phase runs its own tests; the root does not");
    }

    /**
     * The two things only a conductor with children can get wrong: {@code orchestration_finish} is
     * refused while a child has not ended — and a child left asking, which is what passing its
     * question or its cap up leaves behind, has not ended — and the walk over the phases lives on
     * the todo list, the only state a compaction or a restart leaves standing.
     */
    @Test
    void implement_specification_ends_the_children_it_abandons_and_records_each_start_on_its_list() {
        String prompt = flowed("implement_specification");
        assertTrue(prompt.contains("children still running"), prompt);
        assertTrue(prompt.contains("orchestration_cancel"), prompt);
        assertTrue(prompt.contains("as soon as the start returns"), prompt);
    }

    /**
     * Final review: a phase's cap question reaches the person as well (spec 2026-09-29 §2), so the
     * conductor passing it up with orchestration_ask asked the person the same question twice. It
     * is told the person holds every cap question, and to answer {@code no} or leave it to them.
     */
    @Test
    void implement_specification_leaves_a_cap_to_the_person_and_never_passes_it_up() {
        String prompt = flowed("implement_specification");
        assertTrue(prompt.contains("who holds every cap question too"), prompt);
        assertTrue(prompt.contains("answer `no`, or leave it to the person"), prompt);
        assertTrue(prompt.contains("Never pass a cap question up with `orchestration_ask`"), prompt);
        assertFalse(prompt.contains("pass that question up with `orchestration_ask`"), prompt);
    }

    /**
     * Final review: when acceptance cannot pass — its returns used up, or the fix failed — marking
     * it done again only runs the same commands to the same failure; both conductors are told to
     * stop and ask their caller with the failing output.
     */
    @Test
    void both_conductors_stop_re_marking_an_acceptance_that_cannot_pass_and_ask_their_caller() {
        for (String name : List.of("implement_specification", "code_implementation")) {
            String prompt = flowed(name);
            assertTrue(prompt.contains("When acceptance cannot pass"), name);
            assertTrue(prompt.contains("stop marking `acceptance` done"), name);
            assertTrue(prompt.contains("Call `orchestration_ask` with the failing output"), name);
        }
    }

    /**
     * Measured 2026-09-30, orc_318DFD3782228160: a dependency that could not run here was worked
     * around with a stand-in, and a fix phase spent two hours patching it. Both conductors put a
     * dependency that cannot run to their caller instead of delegating a workaround.
     */
    @Test
    void both_conductors_put_a_dependency_that_cannot_run_here_to_their_caller() {
        for (String name : List.of("implement_specification", "code_implementation")) {
            String prompt = flowed(name);
            assertTrue(prompt.contains("cannot run here"), name);
            assertTrue(prompt.contains("`orchestration_ask` quoting what failed"), name);
            assertTrue(prompt.contains("do not delegate a workaround"), name);
        }
    }

    /**
     * Re-review: once spec is done its acceptance commands are held to it, and the harness
     * refuses acceptance for a spec whose requirements changed — so a spec found wrong later is
     * put to the caller, never edited in place; the plan still is.
     */
    @Test
    void both_conductors_put_a_spec_found_wrong_after_spec_to_the_caller_not_edit_it() {
        String code = flowed("code_implementation");
        assertTrue(code.contains("If it is the spec, do not edit `spec.md`"), code);
        assertTrue(code.contains("Put it to the caller with `orchestration_ask`"), code);
        assertTrue(code.contains("fix `plan.md` in place"), code);
        assertFalse(code.contains("fix `spec.md` or `plan.md` in place"), code);
        String spec = flowed("implement_specification");
        assertTrue(spec.contains("Once `spec` is done, `spec.md` is not edited in place"), spec);
        assertTrue(spec.contains("put it to them with `orchestration_ask`"), spec);
    }

    @Test
    void implement_specification_asks_rather_than_inventing_a_goal_and_stops_after_a_failed_phase() {
        String prompt = flowed("implement_specification");
        assertTrue(prompt.contains("orchestration_ask"), prompt);
        assertTrue(prompt.contains("Never invent what the change must make true"), prompt);
        assertTrue(prompt.contains("one child todo per phase"), prompt);
        assertTrue(prompt.contains("orchestrate_code_implementation"), prompt);
        assertTrue(prompt.contains("start no later phase"), prompt);
        assertTrue(prompt.contains("raise it once"), prompt);
        assertTrue(prompt.contains("orchestration_finish"), prompt);
    }

    @Test
    void deep_research_is_an_executable_script_with_bounded_semantic_delegation() {
        var definition=definition("deep_research");
        assertEquals(400, definition.conductor().maxModelCalls());
        assertTrue(io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram.isScript(definition.source()));
        assertEquals(definition.source(),definition.conductor().prompt());
        assertEquals(List.of("research_analyst"),definition.conductor().calls());
        assertEquals(List.of(SearchTool.NAME,InformationTool.READ,InformationTool.WRITE,AgentRegistry.AGENT_RUN),definition.conductor().tools());
        assertEquals(List.of(),definition.conductor().scopes());
        assertFalse(definition.stages().stream().anyMatch(stage->stage.id().equals("finding_expansion")));
        assertTrue(definition.stages().stream().anyMatch(stage->stage.id().equals("final_expansion")));
        assertEquals(List.of(new Trigger("deep research",false),new Trigger("research report",false),new Trigger("/research",true)),definition.triggers());
        assertNull(definition.artifacts());
    }

    /**
     * Every shipped trigger is reachable from some shipped front door, and this is what "reachable"
     * means: {@link OrchestrationRegistry#read} itself logs an orphan-trigger warning — never a
     * refusal — the moment an orchestration declares triggers that nothing loaded is granted, so a
     * missing grant is a silent dead trigger rather than a test failure anywhere else in this
     * suite.
     *
     * <p>Read over {@code agents/} and {@code bots/} both, the way a real boot's {@code
     * AgentRegistry} does ({@code ClasspathDefinitions}'s no-argument constructor scans both
     * locations as one set) — {@code interlocutor} and {@code aristoxenus} are two different
     * directories and the trigger a person's chat agent cannot notice might still be one a bot
     * notices, or the reverse.
     */
    @Test
    void both_building_orchestrations_end_with_acceptance_after_review() {
        for (String name : List.of("implement_specification", "code_implementation")) {
            List<OrchestrationDefinition.Stage> stages = definition(name).stages();
            OrchestrationDefinition.Stage last = stages.get(stages.size() - 1);
            assertEquals("acceptance", last.id(), name);
            assertEquals(OrchestrationDefinition.ACCEPTANCE_REQUIRED, last.acceptance(), name);
            assertEquals("review", stages.get(stages.size() - 2).id(), name);
            assertEquals(OrchestrationDefinition.ACCEPTANCE_WRITTEN, stages.stream()
                    .filter(stage -> stage.id().equals("spec")).findFirst().orElseThrow()
                    .acceptance(), name);
            assertTrue(flowed(name).contains(
                    "run: <command> | stdin: <text> | exit: 0 | expect: <text>"), name);
        }
    }

    @Test
    void design_orchestration_drafts_validates_reviews_and_installs_only_on_the_person_s_word() {
        OrchestrationDefinition definition = definition("design_orchestration");
        assertEquals(List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)),
                definition.conductor().scopes(), "file tools must reach the rooted project's draft artifacts");

        assertEquals(Set.of("design_orchestration"), OrchestrationRegistry.REQUIRED);
        assertEquals("system.authoring", definition.conductor().model());

        assertEquals(List.of("intent", "draft", "validate", "review", "install"),
                definition.stages().stream().map(OrchestrationDefinition.Stage::id).toList());
        assertTrue(definition.conductor().tools().containsAll(StudioTools.NAMES));
        assertTrue(definition.conductor().calls().isEmpty(), "it delegates nothing");
        assertTrue(definition.conductor().orchestrations().isEmpty(), "it starts nothing");
        String prompt = flowed("design_orchestration");
        assertTrue(prompt.contains("orchestration_catalog"), "grants come from the catalog");
        assertTrue(prompt.contains("never invent"), prompt);
        assertTrue(prompt.contains("A refusal is fixed, never argued"), prompt);
        assertTrue(prompt.contains("Nothing is installed until"), prompt);
    }

    @Test
    void the_interlocutor_may_start_design_orchestration() {
        AgentDefinition interlocutor = AgentRegistry.load(SHIPPED_AGENTS,
                BoundTools.boundByThisServer()).get("interlocutor");
        assertNotNull(interlocutor);
        assertTrue(interlocutor.orchestrations().contains("design_orchestration"), "granted");
    }

    @Test
    void authoring_prompt_example_passes_the_real_loader() {
        String prompt = definition("design_orchestration").conductor().prompt();
        String fence = "```markdown\n";
        int start = prompt.indexOf(fence) + fence.length();
        assertTrue(start >= fence.length());
        String example = prompt.substring(start, prompt.indexOf("```", start));
        var parsed = OrchestrationParser.parse(new DefinitionSource.Definition(
                "source_note", "authoring prompt example", example), BoundTools.boundByThisServer(),
                OrchestrationDefinition.Tier.PROJECT);
        assertEquals("source_note", parsed.name());
        assertEquals("read_brief", parsed.stages().getFirst().id());
        assertEquals(List.of(new Grant(Scope.WORKSPACE, Mode.READ)), parsed.conductor().scopes());
    }

    @Test
    void every_shipped_trigger_is_reachable_through_a_shipped_grant() {
        Map<String, AgentDefinition> agentsAndBots =
                AgentRegistry.of(ShippedDefinitions.asOneSet(), BoundTools.boundByThisServer())
                        .byName();
        Set<String> granted = agentsAndBots.values().stream()
                .flatMap(agent -> agent.orchestrations().stream())
                .collect(Collectors.toSet());

        for (OrchestrationDefinition definition : shipped().enabled().values()) {
            if (definition.triggers().isEmpty()) {
                continue;
            }
            assertTrue(granted.contains(definition.name()),
                    "'" + definition.name() + "' declares triggers, but no shipped agent or bot"
                            + " lists it in orchestrations(), so " + OrchestrationRegistry.class
                                    .getSimpleName()
                            + ".read would log its triggers as unreachable rather than fail the"
                            + " boot; granted: " + granted);
        }
    }
}
