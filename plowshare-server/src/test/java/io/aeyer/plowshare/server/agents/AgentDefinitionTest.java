package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The record's own guards, which {@link AgentRegistryTest} cannot reach.
 *
 * <p>Everything the registry loads has already been checked by the time it is
 * constructed, so these tests exist for the other way in: a caller building a
 * definition directly, which {@code AgentRegistry}'s map constructor allows and
 * which the runtime's own fixtures will use. The commit that added this record
 * claimed every guard had been broken and its test confirmed to fail; none of
 * the four below had a test at all.
 */
class AgentDefinitionTest {

    private static final Grant READ = new Grant(Scope.WORKSPACE, Mode.READ);

    private static AgentDefinition definition(List<String> tools, List<String> calls) {
        return definition(tools, calls, List.of());
    }

    private static AgentDefinition definition(
            List<String> tools, List<String> calls, List<Grant> scopes) {
        return new AgentDefinition(
                "judge", "a leaf", "fast", tools, calls, scopes, 4, 8, "You judge things.");
    }

    private static AgentDefinition definition(List<String> tools, List<String> calls,
            List<Grant> scopes, List<String> orchestrations) {
        return new AgentDefinition("judge", "a leaf", "fast", AgentDefinition.DEFAULT_INTENT,
                Sampling.NONE, tools, calls, scopes, 4, 8, "You judge things.", false, true,
                false, false, false, AgentDefinition.Fallback.NONE, orchestrations);
    }

    /**
     * Every component, and each refusal names the one that was null.
     *
     * <p>The message is asserted and not just the exception type, because
     * {@code List.copyOf} would throw a {@code NullPointerException} for the two
     * lists whatever this constructor does — one raised from inside the JDK,
     * naming neither the field nor this record. A test that accepted any NPE
     * could not tell the named check from the unnamed one, which is the whole
     * difference the two {@code requireNonNull}s buy.
     */
    @Test
    void a_null_component_is_refused_by_name() {
        assertEquals("name", assertThrows(NullPointerException.class, () -> new AgentDefinition(
                null, "d", "fast", List.of(), List.of(), List.of(), 4, 8, "body")).getMessage());
        assertEquals("description", assertThrows(NullPointerException.class,
                () -> new AgentDefinition(
                        "judge", null, "fast", List.of(), List.of(), List.of(), 4, 8, "body"))
                .getMessage());
        assertEquals("model", assertThrows(NullPointerException.class, () -> new AgentDefinition(
                "judge", "d", null, List.of(), List.of(), List.of(), 4, 8, "body")).getMessage());
        assertEquals("prompt", assertThrows(NullPointerException.class, () -> new AgentDefinition(
                "judge", "d", "fast", List.of(), List.of(), List.of(), 4, 8, null)).getMessage());
        assertEquals("tools", assertThrows(NullPointerException.class,
                () -> definition(null, List.of())).getMessage());
        assertEquals("calls", assertThrows(NullPointerException.class,
                () -> definition(List.of(), null)).getMessage());
        assertEquals("scopes", assertThrows(NullPointerException.class,
                () -> definition(List.of(), List.of(), null)).getMessage());
        assertEquals("orchestrations", assertThrows(NullPointerException.class,
                () -> definition(List.of(), List.of(), List.of(), null)).getMessage());
    }

    /**
     * The copy that stops a definition changing after the graph it belongs to
     * was validated.
     *
     * <p>Without it, {@code AgentRegistry}'s cycle check and callee check would
     * be statements about the lists as they were at load, and a caller holding
     * the list it passed in could add an edge afterwards that nothing rechecks.
     */
    @Test
    void the_lists_are_copied_rather_than_captured() {
        List<String> tools = new ArrayList<>(List.of("memory_read"));
        List<String> calls = new ArrayList<>(List.of("bottom"));
        List<Grant> scopes = new ArrayList<>(List.of(READ));
        List<String> orchestrations = new ArrayList<>(List.of("code_implementation"));
        AgentDefinition d = definition(tools, calls, scopes, orchestrations);

        tools.add("agent_run");
        calls.add("alice");
        scopes.add(new Grant(Scope.WORKSPACE, Mode.WRITE));
        orchestrations.add("deep_research");

        assertEquals(List.of("memory_read"), d.tools());
        assertEquals(List.of("bottom"), d.calls());
        // The one whose escape would widen access rather than the graph: the
        // non-escalation check runs once, at load, over the grants as they were
        // then, and a caller still holding the list it passed in could add a
        // write grant afterwards that nothing rechecks.
        assertEquals(List.of(READ), d.scopes());
        assertEquals(List.of("code_implementation"), d.orchestrations());
    }

    /**
     * The guard whose javadoc used to overclaim.
     *
     * <p>On the load path a null tool name never reaches here — {@code
     * AgentRegistry}'s {@code requireStringList} refuses {@code tools:
     * [memory_read, ~]} first, naming the file and the entry, and {@code
     * a_null_entry_in_a_tool_list_is_refused} pins that. This is the case that
     * is genuinely left for {@code List.copyOf}: direct construction, where a
     * null tool name would otherwise survive as far as the turn loop.
     */
    @Test
    void a_null_entry_in_a_list_is_refused() {
        assertThrows(NullPointerException.class,
                () -> definition(Arrays.asList("memory_read", null), List.of()));
        assertThrows(NullPointerException.class,
                () -> definition(List.of(), Arrays.asList("bottom", null)));
        assertThrows(NullPointerException.class,
                () -> definition(List.of(), List.of(), Arrays.asList(READ, null)));
    }

    /** Delegation is what {@code tools} grants, not what {@code calls} names —
     *  the registry enforces that the two agree, and this is the question the
     *  runtime asks. */
    @Test
    void canDelegate_is_exactly_the_agent_run_tool() {
        assertTrue(definition(List.of("memory_read", "agent_run"), List.of("bottom")).canDelegate());
        assertFalse(definition(List.of("memory_read"), List.of()).canDelegate());
        assertFalse(definition(List.of(), List.of()).canDelegate());
    }

    /** Redemption is what {@code tools} grants, exactly as delegation is, and
     *  {@code Compaction} asks this before it substitutes a reference for a
     *  result. */
    @Test
    void canRedeem_is_exactly_the_result_read_tool() {
        assertTrue(definition(List.of("memory_read", ResultTools.READ_NAME), List.of())
                .canRedeem());
        assertFalse(definition(List.of("memory_read"), List.of()).canRedeem());
        assertFalse(definition(List.of(), List.of()).canRedeem());
    }

    /**
     * The name a definition must declare to be given references is the name the
     * tool registers itself under, and the name this boot serves.
     *
     * <h2>What drifts, and how silently</h2>
     *
     * <p>Two sites spell one tool. {@link AgentDefinition#canRedeem()} decides
     * whether {@code Compaction} substitutes a reference for an earlier turn's
     * result; {@code ResultTools.Read}'s schema is the name a model calls and
     * the name {@code JobRuntime.offeredTo} builds the tool under. A literal in
     * the first that differed from the second would hand references to an agent
     * that is offered no tool — which is exactly the cost-for-nothing this gate
     * exists to prevent — or withhold them from an agent that holds it.
     * <b>Neither reports anything.</b>
     *
     * <p>So this asserts across the two rather than about either: the tool is
     * asked for its own name, and a definition declaring exactly that name is
     * asked whether it can redeem. Neither side is the constant, so the test is
     * not a field compared with itself. {@code FileWiringTest.the_file_tools_are_
     * known_exactly_when_a_run_can_reach_a_filesystem} ties the same name to the
     * set a boot serves, which is the third site.
     */
    @Test
    void the_name_that_gates_references_is_the_name_the_tool_registers() {
        String registered = new ResultTools.Read(Transcript.NONE).schema().name();

        assertTrue(definition(List.of(registered), List.of()).canRedeem(),
                "an agent declaring the tool by the name the tool registers itself under was"
                        + " told it cannot redeem, so it would be handed references with nothing"
                        + " able to come back — and nothing would fail anywhere");
    }

    /** Listing is what {@code tools} grants, exactly as redemption is, and
     *  {@code Projection} asks this before it writes a seam that names {@code
     *  result_list}. Separate from {@code canRedeem} because each gates the text
     *  that names its own tool, and a definition may hold either alone. */
    @Test
    void canList_is_exactly_the_result_list_tool() {
        assertTrue(definition(List.of(ResultTools.READ_NAME, ResultTools.LIST_NAME), List.of())
                .canList());
        assertFalse(definition(List.of(ResultTools.READ_NAME), List.of()).canList(),
                "an agent holding only the redeeming half was told it can be sent to the"
                        + " listing one");
        assertFalse(definition(List.of(), List.of()).canList());
    }

    /**
     * The name that gates the seam's sentence is the name that tool registers
     * itself under.
     *
     * <p>{@link #the_name_that_gates_references_is_the_name_the_tool_registers}'
     * argument, asked of the other half and with a louder failure: a drift here
     * puts a sentence in front of a model naming a tool it was never offered, so
     * it spends a turn on "there is no tool called result_list" and keeps a false
     * belief about what it can reach.
     */
    @Test
    void the_name_that_gates_the_seams_sentence_is_the_name_the_tool_registers() {
        String registered = new ResultTools.Listing(Transcript.NONE).schema().name();

        assertTrue(definition(List.of(registered), List.of()).canList(),
                "an agent declaring the listing tool by the name it registers itself under was"
                        + " told it cannot list, so its seams would never say what is behind"
                        + " them — and nothing would fail anywhere");
    }

    /**
     * <b>The archive grant is a declared tool name and not a sentence
     * anywhere.</b> Automatic recall starts from this grant, then applies the
     * separate diagnostic exclusion asserted below.
     *
     * <p>Asserted across the two sides rather than about either, on {@code
     * the_name_that_gates_references_is_the_name_the_tool_registers}' argument:
     * the tool is asked for its own registered name, and a definition declaring
     * exactly that name is asked whether it may recall deliberately.
     */
    @Test
    void the_name_that_gates_deliberate_recall_is_the_name_the_tool_registers() {
        String registered = new MemoryTools.Recall(untouched()).schema().name();

        assertTrue(definition(List.of(registered), List.of()).canRecall(),
                "an agent declaring the recall tool by the name it registers itself under was"
                        + " told the archive is not in its scope");
        assertFalse(definition(List.of(), List.of()).canRecall(),
                "an agent the loader never granted the archive could recall from it");
    }

    @Test
    void a_trajectory_reader_may_recall_deliberately_but_is_never_reminded() {
        AgentDefinition diagnostic = definition(
                List.of(MemoryTools.RECALL_NAME, ConversationTrajectoryTool.NAME), List.of());

        assertTrue(diagnostic.canRecall(),
                "the diagnostic agent lost the memory tool it may deliberately use");
        assertFalse(diagnostic.canBeReminded(),
                "the harness injected memory into an agent inspecting what another model saw");
        assertTrue(definition(List.of(MemoryTools.RECALL_NAME), List.of()).canBeReminded(),
                "ordinary agents granted memory recall stopped receiving automatic recall");
    }

    /**
     * Reading a memory in full is granted separately from searching for one, and
     * the reminder's sentence about {@code memory_read} is gated on it for
     * {@link AgentDefinition#canList()}'s reason exactly: a sentence naming a
     * tool the model was never offered spends a turn on "there is no tool called
     * memory_read" and leaves a false belief behind it.
     */
    @Test
    void the_name_that_gates_the_reminders_read_clause_is_the_name_the_tool_registers() {
        String registered = new MemoryTools.Read(untouched()).schema().name();

        assertTrue(definition(List.of(registered), List.of()).canReadMemory());
        assertFalse(definition(List.of(MemoryTools.RECALL_NAME), List.of()).canReadMemory(),
                "an agent holding only the searching half was told it can be sent to the"
                        + " reading one");
    }

    /**
     * A person's caps replace the two numbers they name and nothing else — spec 2026-09-29 §2. A
     * component dropped here would be a conductor that lost its tools or its prompt because its
     * project set a budget.
     */
    @Test
    void caps_replace_max_turns_and_max_model_calls_and_keep_every_other_component() {
        AgentDefinition own = new AgentDefinition("judge", "a leaf", "fast",
                AgentDefinition.DEFAULT_INTENT, Sampling.NONE, List.of("file_read"), List.of(),
                List.of(READ), 60, 400, "You judge things.", true, false, true, false, true,
                AgentDefinition.Fallback.NONE, List.of("code_implementation"), "reviewer", true);

        AgentDefinition capped = own.withCaps(40, 600);

        assertEquals(40, capped.maxTurns());
        assertEquals(600, capped.maxModelCalls());
        assertEquals(new AgentDefinition(own.name(), own.description(), own.model(), own.intent(),
                own.sampling(), own.tools(), own.calls(), own.scopes(), 40, 600, own.prompt(),
                own.exported(), own.delegable(), own.vision(), own.bot(), own.announcesInbox(),
                own.fallback(), own.orchestrations(), own.reviewWith(), own.board()), capped);
        assertEquals(400, own.withCaps(40, null).maxModelCalls());
        assertEquals(60, own.withCaps(null, 600).maxTurns());
        assertSame(own, own.withCaps(null, null), "no caps is the definition as it was");
    }

    /**
     * An archive nothing touches, for {@code ModelSurfaceTest.untouched}'s
     * reason: both memory tools build their schema in their constructor and hold
     * the archive for {@code run}, which neither test above reaches. Wiring a
     * live one would mean a Postgres container for a constant.
     */
    private static Archive untouched() {
        return new Archive(null, null, null, 0, 0, 0.0, Instant::now, null);
    }
}
