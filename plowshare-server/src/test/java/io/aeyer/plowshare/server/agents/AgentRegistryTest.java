package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.llm.dispatch.JsonSchema;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The registry is the boot check for agents, and it exists for the same reason
 * LlmConfig's is: a misconfiguration that would fail silently at run time
 * becomes a startup failure that names the file.
 */
class AgentRegistryTest {

    private static final Set<String> TOOLS =
            Set.of("memory_recall", "memory_read", "agent_run", "memory_write");

    private static void write(Path dir, String name, String frontmatter, String body)
            throws Exception {
        Files.writeString(dir.resolve(name + ".md"), "---\n" + frontmatter + "\n---\n" + body);
    }

    private static String leaf(String name) {
        return """
               name: %s
               description: a leaf
               model: fast
               tools: [memory_read]
               max-turns: 4
               max-model-calls: 8
               """.formatted(name);
    }

    @Test
    void a_definition_binds_from_its_frontmatter(@TempDir Path dir) throws Exception {
        write(dir, "judge", leaf("judge"), "You judge things.");
        AgentDefinition d = AgentRegistry.load(dir, TOOLS).get("judge");

        assertEquals("judge", d.name());
        assertEquals("fast", d.model());
        assertEquals(List.of("memory_read"), d.tools());
        assertEquals(List.of(), d.calls());
        assertEquals(List.of(), d.scopes());
        assertEquals(4, d.maxTurns());
        assertEquals(8, d.maxModelCalls());
        assertEquals("You judge things.", d.prompt().strip());
    }

    @Test
    void orchestration_grants_bind_by_name_and_default_to_none(@TempDir Path dir) throws Exception {
        write(dir, "caller", leaf("caller")
                + "orchestrations: [code_implementation, deep_research]\n", "Call them.");
        write(dir, "plain", leaf("plain"), "Do not call them.");

        Map<String, AgentDefinition> registry = AgentRegistry.load(dir, TOOLS);

        assertEquals(List.of("code_implementation", "deep_research"),
                registry.get("caller").orchestrations());
        assertEquals(List.of(), registry.get("plain").orchestrations());
    }

    /** The file name is not the identity; the frontmatter is. A mismatch means
     *  one of the two is a typo and the graph would be built on the wrong
     *  node. */
    @Test
    void a_name_that_disagrees_with_its_file_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "judge", leaf("something_else"), "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("judge"), e.getMessage());
        assertTrue(e.getMessage().contains("something_else"), e.getMessage());
    }

    @Test
    void an_unknown_tool_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "judge", """
                name: judge
                description: d
                model: fast
                tools: [memory_read, file_read]
                max-turns: 4
                max-model-calls: 8
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("file_read"), e.getMessage());
    }

    /**
     * The three agents that ARE the memory pipeline may not author into it.
     *
     * <p>Not a tidiness rule. {@code scribe} judges the shape of a write and
     * {@code promotion_judge} rules on a memory's generality; either holding an
     * authoring verb would be ruling on its own claim, which is the objection that
     * keeps a caller from naming its own supersession target, one rung down.
     * {@code learner} already reaches the archive through the judged path and
     * gets no second one.
     *
     * <p><b>The boot failure is not new machinery.</b> A dropped item costs the
     * item and the agent is served -- except on an agent the code depends on,
     * where {@code read} already throws. All three are in
     * {@code AgentsConfig.REQUIRED}, so the existing rung turns this rule into a
     * refusal without a new failure mode being invented for it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"scribe", "promotion_judge", "learner"})
    void an_agent_that_is_the_memory_pipeline_cannot_author_into_it(String agent,
            @TempDir Path dir) throws Exception {
        write(dir, agent, """
                name: %s
                description: d
                model: fast
                tools: [memory_write]
                max-turns: 1
                max-model-calls: 1
                """.formatted(agent), "body");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.read(dir, TOOLS, Set.of(agent)));

        assertTrue(e.getMessage().contains("memory_write"), e.getMessage());
        assertTrue(e.getMessage().contains(agent + ".md"), e.getMessage());
    }

    /** And the rule is about those three and not about authoring in general: any
     *  other agent may hold the verb, which is the whole point of binding it. */
    @Test
    void an_agent_that_is_not_the_pipeline_may_hold_memory_write(@TempDir Path dir)
            throws Exception {
        write(dir, "helper", """
                name: helper
                description: d
                model: fast
                tools: [memory_write]
                max-turns: 1
                max-model-calls: 1
                """, "body");

        AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, Set.of());

        assertEquals(List.of("memory_write"), loaded.enabled().get("helper").tools());
        assertTrue(loaded.withheldTools().isEmpty(), loaded.withheldTools().toString());
    }

    /**
     * The rule is per-tool and not per-agent: {@code promotion_judge} rules on
     * a memory's generality, which needs {@code memory_read}, and the ban on
     * authoring must not take that away with it. Read as non-required so the
     * dropped {@code memory_write} costs only the item and not the boot, which
     * is what lets both halves of this be asserted in one definition.
     */
    @Test
    void promotion_judge_keeps_memory_read_while_memory_write_is_dropped(@TempDir Path dir)
            throws Exception {
        write(dir, "promotion_judge", """
                name: promotion_judge
                description: d
                model: fast
                tools: [memory_read, memory_write]
                max-turns: 1
                max-model-calls: 1
                """, "body");

        AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, Set.of());

        assertEquals(List.of("memory_read"), loaded.enabled().get("promotion_judge").tools());
        String why = loaded.withheldTools().get("promotion_judge: memory_write");
        assertTrue(why != null && why.contains("memory_write"), loaded.withheldTools().toString());
    }

    /** file_write was renamed file_edit with no alias; a definition still naming it is told so. */
    @Test
    void a_definition_naming_file_write_is_told_it_was_renamed_file_edit(@TempDir Path dir)
            throws Exception {
        write(dir, "old", """
                name: old
                description: d
                model: fast
                tools: [memory_read, file_write]
                max-turns: 1
                max-model-calls: 1
                """, "body");

        AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, Set.of());

        assertEquals(List.of("memory_read"), loaded.enabled().get("old").tools());
        String why = loaded.withheldTools().get("old: file_write");
        assertTrue(why != null && why.contains("renamed 'file_edit'"), loaded.withheldTools().toString());
    }

    @Test
    void an_unknown_callee_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "boss", """
                name: boss
                description: d
                model: fast
                tools: [agent_run]
                calls: [ghost]
                max-turns: 4
                max-model-calls: 8
                """, "body");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS)).getMessage().contains("ghost"));
    }

    /** The two halves of delegation must agree. Either alone is a definition
     *  that says one thing and does another. */
    @Test
    void agent_run_without_calls_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "boss", """
                name: boss
                description: d
                model: fast
                tools: [agent_run]
                max-turns: 4
                max-model-calls: 8
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        // The branch, not merely the throw. disagreeingHalves has two
        // symmetric arms with two distinct messages, and each fixture reaches
        // exactly one — so asserting only that something was thrown lets either
        // test pass when the wrong arm fires.
        assertTrue(e.getMessage().contains("boss"), e.getMessage());
        assertTrue(e.getMessage().contains("names no 'calls'"), e.getMessage());
    }

    @Test
    void calls_without_agent_run_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "boss", leaf("boss") + "calls: [judge]\n", "body");
        write(dir, "judge", leaf("judge"), "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("boss"), e.getMessage());
        assertTrue(e.getMessage().contains("does not have"), e.getMessage());
    }

    @Test
    void review_with_names_a_validated_callee(@TempDir Path dir) throws Exception {
        write(dir, "diagnoser", """
                name: diagnoser
                description: d
                model: fast
                tools: [agent_run]
                calls: [verifier]
                review-with: verifier
                max-turns: 4
                max-model-calls: 8
                """, "body");
        write(dir, "verifier", leaf("verifier"), "body");

        assertEquals("verifier", AgentRegistry.load(dir, TOOLS).get("diagnoser").reviewWith());
    }

    @Test
    void review_with_cannot_bypass_the_declared_call_graph(@TempDir Path dir) throws Exception {
        write(dir, "diagnoser", """
                name: diagnoser
                description: d
                model: fast
                tools: [agent_run]
                calls: [helper]
                review-with: verifier
                max-turns: 4
                max-model-calls: 8
                """, "body");
        write(dir, "helper", leaf("helper"), "body");
        write(dir, "verifier", leaf("verifier"), "body");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("review-with"), e.getMessage());
        assertTrue(e.getMessage().contains("calls"), e.getMessage());
    }

    /**
     * The guard this registry mainly exists for.
     *
     * <p>With turns ending at the tool call there is no stack, so a cycle is
     * not a stack overflow anything catches at run time — it is a job tree that
     * never drains. The load-time check is the guard; nothing downstream is.
     */
    @Test
    void a_cycle_is_refused_naming_both_agents(@TempDir Path dir) throws Exception {
        write(dir, "alice", """
                name: alice
                description: d
                model: fast
                tools: [agent_run]
                calls: [bob]
                max-turns: 4
                max-model-calls: 8
                """, "body");
        write(dir, "bob", """
                name: bob
                description: d
                model: fast
                tools: [agent_run]
                calls: [alice]
                max-turns: 4
                max-model-calls: 8
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("alice"), e.getMessage());
        assertTrue(e.getMessage().contains("bob"), e.getMessage());
    }

    /** A self-call is the shortest cycle and the easiest to miss in a
     *  depth-first check that only looks at children. */
    @Test
    void an_agent_that_calls_itself_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "ouroboros", """
                name: ouroboros
                description: d
                model: fast
                tools: [agent_run]
                calls: [ouroboros]
                max-turns: 4
                max-model-calls: 8
                """, "body");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS)).getMessage().contains("ouroboros"));
    }

    /** A diamond is not a cycle. A checker using a single "visited" set rather
     *  than a recursion stack rejects this, and would forbid the most ordinary
     *  useful shape there is. */
    @Test
    void a_diamond_is_not_a_cycle(@TempDir Path dir) throws Exception {
        write(dir, "top", """
                name: top
                description: d
                model: fast
                tools: [agent_run]
                calls: [left, right]
                max-turns: 4
                max-model-calls: 8
                """, "body");
        for (String mid : List.of("left", "right")) {
            write(dir, mid, """
                    name: %s
                    description: d
                    model: fast
                    tools: [agent_run]
                    calls: [bottom]
                    max-turns: 4
                    max-model-calls: 8
                    """.formatted(mid), "body");
        }
        write(dir, "bottom", leaf("bottom"), "body");
        assertEquals(4, AgentRegistry.load(dir, TOOLS).size());
    }

    /** An agent with no instructions is a model call with no instructions. It
     *  will do something, which is worse than failing. */
    @Test
    void an_empty_body_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "hollow", leaf("hollow"), "   \n  ");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS)).getMessage().contains("hollow"));
    }

    /** An empty tools list is legal and must stay legal: the scribe has none
     *  by design, and refusing it would forbid the one agent whose
     *  toollessness is the point. */
    @Test
    void an_agent_with_no_tools_is_legal(@TempDir Path dir) throws Exception {
        write(dir, "scribe", """
                name: scribe
                description: judges the shape of a write
                model: fast
                tools: []
                max-turns: 1
                max-model-calls: 1
                """, "You decide where a proposal is filed.");
        assertEquals(List.of(), AgentRegistry.load(dir, TOOLS).get("scribe").tools());
    }

    @Test
    void a_non_positive_budget_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "greedy", """
                name: greedy
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 0
                """, "body");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS)).getMessage().contains("max-model-calls"));
    }

    @Test
    void a_directory_with_no_agents_loads_empty(@TempDir Path dir) {
        assertEquals(Map.of(), AgentRegistry.load(dir, TOOLS));
    }

    // ---------------------------------------------------------------------
    // Beyond the plan's thirteen. Each of the tests from here to the end of
    // the file pins a way a bad definition would otherwise load
    // *successfully* and misbehave later, or a refusal that had no test at
    // all — which is the one failure mode this class exists to make
    // impossible.
    // ---------------------------------------------------------------------

    /**
     * The key set is closed. An unrecognised key silently dropped turns {@code
     * tool: [memory_read]} into a toolless agent that starts, runs, and can
     * never do its job.
     *
     * <p>The fixture is {@code max_turns} because that is the mistake the
     * documents themselves once invited: the spec wrote the budget keys
     * snake_case until {@code 4cc482a} corrected it. The two agree now, and
     * this test is why the disagreement would have been a boot failure naming
     * the key rather than an agent running on a default nobody wrote.
     */
    @Test
    void an_unrecognised_frontmatter_key_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "typo", """
                name: typo
                description: d
                model: fast
                tools: []
                max_turns: 4
                max-model-calls: 8
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("max_turns"), e.getMessage());
    }

    /** {@code vision:} binds as a requirement the definition carries; whether
     *  the fleet can meet it is {@code AgentsConfig}'s question, because the
     *  registry reads files and knows nothing about pools. */
    @Test
    void a_declared_vision_requirement_is_what_the_definition_carries(@TempDir Path dir)
            throws Exception {
        write(dir, "figure_reader", """
                name: figure_reader
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                vision: true
                """, "You read figures.");

        assertTrue(AgentRegistry.load(dir, TOOLS).get("figure_reader").vision());
    }

    /** Absent means no, which is {@code exported}'s rule and not {@code
     *  delegable}'s: a requirement nobody stated has not been stated. */
    @Test
    void an_agent_that_says_nothing_about_vision_requires_none(@TempDir Path dir)
            throws Exception {
        write(dir, "judge", leaf("judge"), "You judge things.");

        assertFalse(AgentRegistry.load(dir, TOOLS).get("judge").vision());
    }

    // ---------------------------------------------------------------------
    // schema: the thirteenth key, and the one that turns "this agent is a
    // microservice" from a description into a contract. Everything below is
    // about the file, not the wire — OpenAiTransportTest owns the request body
    // and the carries() half.
    // ---------------------------------------------------------------------

    /**
     * A block in the file becomes the schema the request carries, named after
     * the agent.
     *
     * <p>One agent is one microservice with one contract, so it is one name. A
     * {@code name:} inside the block would give an agent two, with nothing
     * keeping them related and a refusal naming whichever one the operator did
     * not search for.
     */
    @Test
    void a_schema_block_becomes_the_response_format_named_after_the_agent(@TempDir Path dir)
            throws Exception {
        write(dir, "figure_reader", """
                name: figure_reader
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                schema:
                  type: object
                  properties:
                    shape:
                      type: string
                  required: [shape]
                """, "You read figures.");

        JsonSchema schema = AgentRegistry.load(dir, TOOLS).get("figure_reader")
                .sampling().responseFormat().orElseThrow();

        assertEquals("figure_reader", schema.name());
        assertEquals("object", schema.schema().get("type"));
        assertEquals(List.of("shape"), schema.schema().get("required"));
    }

    /** An agent with no block asks for no shape, and answers in prose exactly
     *  as every agent in this tree did before the key existed. */
    @Test
    void an_agent_with_no_schema_block_carries_no_response_format(@TempDir Path dir)
            throws Exception {
        write(dir, "judge", leaf("judge"), "You judge things.");

        assertTrue(AgentRegistry.load(dir, TOOLS).get("judge")
                .sampling().responseFormat().isEmpty());
    }

    /** A temperature and a schema are two independent overrides, and stating
     *  one must not erase the other -- Sampling.overriddenBy's rule, reached
     *  from the file that writes both. */
    @Test
    void a_temperature_and_a_schema_in_one_file_both_survive(@TempDir Path dir)
            throws Exception {
        write(dir, "figure_reader", """
                name: figure_reader
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                temperature: 0.3
                schema:
                  type: object
                  properties:
                    shape:
                      type: string
                """, "You read figures.");

        AgentDefinition read = AgentRegistry.load(dir, TOOLS).get("figure_reader");

        assertEquals(0.3d, read.sampling().temperature().getAsDouble());
        assertEquals("figure_reader", read.sampling().responseFormat().orElseThrow().name());
    }

    /**
     * A root that is not an object is refused where the file is read.
     *
     * <p>Structured output constrains the sampler to one JSON document, and the
     * endpoints this project targets refuse a root that is not an object. A
     * file shipping one would be an agent that loads, runs, and fails on its
     * first call with a message about a document nobody here wrote.
     */
    @Test
    void a_schema_whose_root_is_not_an_object_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "lister", """
                name: lister
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                schema:
                  type: array
                """, "You list.");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(refused.getMessage().contains("object"), refused.getMessage());
        assertTrue(refused.getMessage().contains("lister"), refused.getMessage());
    }

    /** A written key with no value is a half-finished edit; reading it as
     *  absent would make the one thing the operator can see mean nothing. */
    @Test
    void a_schema_key_with_nothing_after_it_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "lister", """
                name: lister
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                schema:
                """, "You list.");

        assertThrows(IllegalStateException.class, () -> AgentRegistry.load(dir, TOOLS));
    }

    /** A schema is not item-shaped: there is no remainder to serve without, so
     *  a bad one disables the agent rather than costing it the key. Serving an
     *  agent with its contract quietly removed would be an agent whose callers
     *  parse JSON out of prose. */
    @Test
    void a_schema_that_is_not_a_block_is_refused_rather_than_dropped(@TempDir Path dir)
            throws Exception {
        write(dir, "lister", """
                name: lister
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                schema: "type: object"
                """, "You list.");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(refused.getMessage().contains("schema"), refused.getMessage());
    }

    // ---------------------------------------------------------------------
    // temperature: the eleventh key, and the second per-agent inference
    // setting after `model`. It changes how the model samples rather than what
    // it may reach, so none of the escalation machinery above applies to it —
    // what it needs is that a number an operator wrote is the number the
    // request carries, and that a number they nearly wrote is never silently
    // read as zero.
    // ---------------------------------------------------------------------

    /** The declared value reaches the definition unrounded. */
    @Test
    void a_declared_temperature_is_what_the_definition_carries(@TempDir Path dir)
            throws Exception {
        write(dir, "warm", """
                name: warm
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                temperature: 0.3
                """, "body");
        assertEquals(0.3d, AgentRegistry.load(dir, TOOLS).get("warm")
                .sampling().temperature().getAsDouble());
    }

    /**
     * An absent key means <b>send nothing</b>, which is a different statement
     * from the zero it used to mean.
     *
     * <p>Silence in an agent file is not a request for greedy decoding — it is
     * the absence of a request, and the honest thing to do with it is to write
     * no sampling key onto the wire at all and let the model's own defaults
     * apply. That was measured as the better answer: the endpoint resolves model
     * defaults → {@code model.yaml} → load-time → inference-time, and a model's
     * own packaging carries its vendor's numbers, so a request that sends
     * nothing runs it correctly.
     *
     * <p>Asserted as {@code isEmpty()} and not as a value, because there is no
     * value: that is the whole point of the field being able to be absent.
     */
    @Test
    void an_agent_that_declares_no_sampling_sends_nothing(@TempDir Path dir)
            throws Exception {
        write(dir, "judge", leaf("judge"), "body");
        AgentDefinition judge = AgentRegistry.load(dir, TOOLS).get("judge");
        assertEquals(Sampling.NONE, judge.sampling());
        assertTrue(judge.sampling().isEmpty());
        assertEquals(Sampling.Intent.BALANCED, judge.intent());
    }

    /**
     * The intent vocabulary, and a name outside it is a disablement.
     *
     * <p>Same rung as an unreadable {@code temperature:} and for the {@code
     * scopes:} reason: an intent is not item-shaped, so there is no remainder to
     * serve without. Serving an agent at {@code balanced} while its own file
     * says {@code presice} is the silent narrowing a closed key set exists to
     * prevent, arriving through the value instead of through the key.
     */
    @Test
    void a_declared_intent_is_what_the_definition_carries(@TempDir Path dir) throws Exception {
        for (Sampling.Intent intent : Sampling.Intent.values()) {
            Path each = dir.resolve(intent.declared());
            java.nio.file.Files.createDirectories(each);
            write(each, "agent", """
                    name: agent
                    description: d
                    model: fast
                    tools: []
                    max-turns: 4
                    max-model-calls: 8
                    sampling: %s
                    """.formatted(intent.declared()), "body");
            assertEquals(intent, AgentRegistry.load(each, TOOLS).get("agent").intent());
        }
    }

    @Test
    void a_sampling_intent_nobody_can_resolve_is_a_refusal(@TempDir Path dir) throws Exception {
        write(dir, "agent", """
                name: agent
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                sampling: presice
                """, "body");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(refused.getMessage().contains("presice"), refused.getMessage());
        assertTrue(refused.getMessage().contains("precise"), refused.getMessage());
    }

    /**
     * {@code temperature: 1} is one, and not a refusal.
     *
     * <p>The opposite of {@code requirePositiveInt}'s rule and deliberately so.
     * A budget written {@code 4.0} is not a whole number and {@code intValue()}
     * would invent one, so that key takes {@link Integer} and nothing else. A
     * temperature written {@code 1} is an exact temperature: snakeyaml resolves
     * it to {@link Integer}, {@code doubleValue()} is lossless, and refusing it
     * would be the parser demanding a decimal point for no reason a reader of
     * the refusal could act on.
     */
    @Test
    void a_temperature_written_as_a_whole_number_is_that_number(@TempDir Path dir)
            throws Exception {
        write(dir, "warm", """
                name: warm
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                temperature: 1
                """, "body");
        assertEquals(1.0d, AgentRegistry.load(dir, TOOLS).get("warm")
                .sampling().temperature().getAsDouble());
    }

    /**
     * A negative temperature loads, because {@code ChatRequest} takes one.
     *
     * <p><b>The loader is deliberately not stricter than the chokepoint.</b>
     * {@code ChatRequest}'s own javadoc argues the range: finite, and otherwise
     * unrestricted, "because backends disagree about the ceiling and llama.cpp
     * reads a temperature at or below zero as greedy sampling rather than as an
     * error. Refusing a value the local server would have honoured is the worse
     * of the two mistakes." A second, narrower range here would be a second
     * answer to a question this repository has already answered once, and the
     * operator would meet whichever layer they happened to hit first.
     */
    @Test
    void a_temperature_the_request_would_accept_is_not_refused_by_the_loader(@TempDir Path dir)
            throws Exception {
        write(dir, "greedy", """
                name: greedy
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                temperature: -1.0
                """, "body");
        assertEquals(-1.0d, AgentRegistry.load(dir, TOOLS).get("greedy")
                .sampling().temperature().getAsDouble());
    }

    /**
     * A value that is not a number is a disablement, not a drop.
     *
     * <p><b>Third rung, and the {@code scopes:} precedent is the one it
     * follows.</b> An invalid item <em>within</em> a grant costs the item
     * because it was never issuable; an unreadable {@code scopes:} entry stays a
     * disablement "because a grant that failed to parse is not a grant that was
     * never issuable". A temperature is not item-shaped at all — there is no
     * remainder to carry on with — and serving the agent at zero would run it at
     * a temperature its own file does not describe, which is exactly the silent
     * narrowing that argument forbids.
     *
     * <p>Quoted or misspelt reads as text, which is {@code requireBoolean}'s
     * lesson: {@code temperature: "0.3"} resolves to {@link String} and a parser
     * that shrugged at it would run an agent at zero over a pair of quotes.
     */
    @Test
    void a_temperature_that_is_not_a_number_disables_the_agent(@TempDir Path dir)
            throws Exception {
        write(dir, "warm", """
                name: warm
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                temperature: "0.3"
                """, "body");
        AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, Set.of());
        assertFalse(loaded.enabled().containsKey("warm"), loaded.enabled().toString());
        assertTrue(loaded.disabled().get("warm").contains("temperature"),
                loaded.disabled().toString());
    }

    /**
     * And a required agent's is the boot, which is the rung above and needs no
     * new rule: any fault on an agent the code looks up by name stops the
     * server rather than leaving Java to meet a name nothing serves.
     */
    @Test
    void a_temperature_that_is_not_a_number_on_a_required_agent_stops_the_boot(@TempDir Path dir)
            throws Exception {
        write(dir, "warm", """
                name: warm
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                temperature: warm
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.read(dir, TOOLS, Set.of("warm")));
        assertTrue(e.getMessage().contains("temperature"), e.getMessage());
        assertTrue(e.getMessage().contains("warm.md"), e.getMessage());
    }

    /**
     * {@code .inf} and {@code .nan} are numbers to YAML and not to JSON.
     *
     * <p>{@code ChatRequest} refuses a non-finite temperature and says why —
     * Jackson writes one as a quoted string, so it fails at the endpoint as a
     * type error about a field the caller never sees. Meeting that at boot,
     * naming the file, is the whole point of a loader.
     */
    @Test
    void a_temperature_that_is_not_finite_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "wild", """
                name: wild
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                temperature: .inf
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.read(dir, TOOLS, Set.of("wild")));
        assertTrue(e.getMessage().contains("temperature"), e.getMessage());
    }

    /**
     * {@code temperature:} with nothing after it is the {@code max-turns: ~}
     * shape, and gets the same answer rather than the default.
     *
     * <p>A written key with no value is a half-finished edit. Reading it as
     * "absent" would make the one thing an operator can see — that they typed
     * the key — mean nothing.
     */
    @Test
    void a_temperature_key_written_with_no_value_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "blank", """
                name: blank
                description: d
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                temperature:
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.read(dir, TOOLS, Set.of("blank")));
        assertTrue(e.getMessage().contains("temperature"), e.getMessage());
    }

    /**
     * Measured against snakeyaml 2.2, not assumed: with default {@code
     * LoaderOptions} a repeated key is accepted and the <em>last</em> one wins
     * silently, so a file listing {@code tools:} twice loads as whichever half
     * happens to be lower in the file.
     */
    @Test
    void a_duplicate_key_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "twofaced", """
                name: twofaced
                description: d
                model: fast
                tools: [memory_read]
                tools: []
                max-turns: 4
                max-model-calls: 8
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("twofaced"), e.getMessage());
    }

    /**
     * Every {@code .md} in the directory is an agent. A file with no
     * frontmatter fence is a note somebody left in the wrong folder, and
     * skipping it silently would mean an agent whose fence was mistyped simply
     * ceases to exist — a callee vanishing without a word.
     *
     * <p>This and {@link #frontmatter_that_is_never_closed_is_refused} assert
     * on which refusal fired, not merely that one did, because the two guards
     * mask each other: measured, deleting the opening-fence check leaves this
     * file refused anyway by the closing-fence check, and a test asserting only
     * the file name cannot tell that the first guard has gone.
     */
    @Test
    void a_file_with_no_frontmatter_is_refused(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("notes.md"), "just some prose about agents\n");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("notes.md"), e.getMessage());
        assertTrue(e.getMessage().contains("does not open"), e.getMessage());
    }

    /** An opened fence and no closing one means the whole prompt would be read
     *  as YAML. */
    @Test
    void frontmatter_that_is_never_closed_is_refused(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("halfopen.md"), "---\n" + leaf("halfopen") + "You judge.\n");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("halfopen.md"), e.getMessage());
        assertTrue(e.getMessage().contains("never closes"), e.getMessage());
    }

    /** Blank is not the same fault as missing, and it is the one a template
     *  produces: a key left for somebody to fill in and never filled. */
    @Test
    void a_blank_value_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "nameless", """
                name: nameless
                description: ''
                model: fast
                tools: []
                max-turns: 4
                max-model-calls: 8
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("description"), e.getMessage());
    }

    /** A budget is a number an operator has to be able to reason about, so it
     *  is the number they wrote or nothing — never a coercion of whatever YAML
     *  resolved the value to. */
    @Test
    void a_budget_that_is_not_a_whole_number_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "vague", """
                name: vague
                description: d
                model: fast
                tools: []
                max-turns: many
                max-model-calls: 8
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("max-turns"), e.getMessage());
    }

    /**
     * Measured: snakeyaml resolves an unquoted {@code 3.5} to a {@code Double}
     * and an unquoted {@code yes} to a {@code Boolean}. Calling {@code
     * toString} on either would hand the dispatcher a specifier nobody wrote —
     * {@code "true"} for an agent named {@code yes} — so a non-string scalar is
     * refused where it is written rather than coerced.
     */
    @Test
    void a_non_string_scalar_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "numeric", """
                name: numeric
                description: d
                model: 3.5
                tools: []
                max-turns: 4
                max-model-calls: 8
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("model"), e.getMessage());
    }

    // ---------------------------------------------------------------------
    // The grants a definition declares. `scopes:` is the third list key, and
    // the only one whose entries are not bare names: each is parsed into a
    // Grant here, at boot, naming the file — so nothing downstream ever holds
    // a scope as a string, and a misspelling cannot survive as far as a job
    // that silently reaches no file.
    // ---------------------------------------------------------------------

    @Test
    void a_definition_declares_the_grants_it_holds(@TempDir Path dir) throws Exception {
        write(dir, "reader", leaf("reader") + "scopes: [workspace:read]\n", "body");
        // Also the one test that fails if 'scopes' is left out of KNOWN_KEYS:
        // the closed key set would refuse this file as unrecognised.
        assertEquals(List.of(new Grant(Scope.WORKSPACE, Mode.READ)),
                AgentRegistry.load(dir, TOOLS).get("reader").scopes());
    }

    /**
     * Two spellings of no grant, and they take different paths through the
     * loader. Measured against snakeyaml 2.2: an absent key is not in the map
     * at all, while {@code scopes: []} binds an empty {@code ArrayList} — and
     * {@code scopes:} with nothing after it binds {@code null}, which is the
     * third path and is refused by the same guard that refuses {@code model:}
     * with no value.
     */
    @Test
    void an_absent_scopes_key_and_an_empty_list_both_mean_no_grant(@TempDir Path dir)
            throws Exception {
        write(dir, "silent", leaf("silent"), "body");
        write(dir, "explicit", leaf("explicit") + "scopes: []\n", "body");
        Map<String, AgentDefinition> loaded = AgentRegistry.load(dir, TOOLS);

        assertEquals(List.of(), loaded.get("silent").scopes());
        assertEquals(List.of(), loaded.get("explicit").scopes());
    }

    /**
     * {@code scopes:} with nothing after it is not {@code scopes: []}.
     *
     * <p>Measured against snakeyaml 2.2: the first binds {@code null} and the
     * second an empty list, and only the second means "no grant". A key written
     * and left unfilled is a template nobody finished, and it gets the refusal
     * that already owns that shape.
     *
     * <p>It needs a test of its own although the guard is shared, because a
     * list key reaches that guard down a path a scalar key does not:
     * {@code requireStringList} asks {@code containsKey} first and answers
     * "absent means empty" without consulting the value, so
     * {@code a_key_written_with_no_value_is_refused} — which writes a bare
     * {@code model:} — says nothing about what a bare {@code scopes:} does.
     */
    @Test
    void a_scopes_key_written_with_no_value_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "unfinished", leaf("unfinished") + "scopes:\n", "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("unfinished.md"), e.getMessage());
        assertTrue(e.getMessage().contains("no value"), e.getMessage());
    }

    /** Excalibur's {@code archive} scope is the port somebody will try, and
     *  Plowshare's archive is Postgres: there is no directory to grant. */
    @Test
    void a_scope_that_does_not_exist_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "greedy", leaf("greedy") + "scopes: [archive:read]\n", "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("greedy.md"), e.getMessage());
        assertTrue(e.getMessage().contains("names the scope 'archive'"), e.getMessage());
    }

    /**
     * {@code scopes: [workspace]} means read in Excalibur and nothing here.
     *
     * <p>The file name is asserted as well as the complaint, because {@code
     * Grant.parse}'s own message knows nothing about files: what this test
     * pins is that the loader wraps it in the sentence naming the file an
     * operator has to open, which is the whole of what this class adds over
     * the parser.
     */
    @Test
    void a_grant_written_without_a_mode_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "vague", leaf("vague") + "scopes: [workspace]\n", "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("vague.md"), e.getMessage());
        assertTrue(e.getMessage().contains("not a scope and a mode"), e.getMessage());
    }

    /**
     * Two grants over one scope resolve to the wider of the two — {@code
     * LocalProvider} asks whether <em>any</em> grant allows a write — so a file
     * reading as mostly-read would grant write. Excalibur refuses the same
     * shape for the same reason.
     */
    @Test
    void a_scope_granted_twice_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "twice", leaf("twice") + "scopes: [workspace:read, workspace:write]\n", "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("twice.md"), e.getMessage());
        assertTrue(e.getMessage().contains("two grants"), e.getMessage());
        // Both spellings, so the operator can see which pair collided rather
        // than being told only that something did.
        assertTrue(e.getMessage().contains("workspace:read"), e.getMessage());
        assertTrue(e.getMessage().contains("workspace:write"), e.getMessage());
    }

    /**
     * One space, and YAML reads a different document.
     *
     * <p>Measured against snakeyaml 2.2: {@code [workspace:read]} is a list of
     * one string, and {@code [workspace: read]} is a list of one <em>map</em>,
     * because a colon followed by a space opens a mapping in flow context. The
     * generic "is not a name" refusal is true of that map and tells an operator
     * nothing they can act on, since what they wrote looks exactly like a name.
     * This is the one key where that mistake is natural — no tool or agent name
     * contains a colon — so the entry reader says which way YAML read it.
     */
    @Test
    void a_grant_written_with_a_space_after_the_colon_is_refused_as_a_mapping(@TempDir Path dir)
            throws Exception {
        write(dir, "spaced", leaf("spaced") + "scopes: [workspace: read]\n", "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("spaced.md"), e.getMessage());
        assertTrue(e.getMessage().contains("mapping"), e.getMessage());
    }

    // ---------------------------------------------------------------------
    // Non-escalation: for every edge A -> B, B's grants are a subset of A's.
    //
    // The rule is checked here, once, over the graph `calls:` already
    // declares, and not at run time. There is nothing to check at run time:
    // by the time a child job exists its provider has already been built from
    // its own definition, and a guard on the delegation call would be a second
    // reading of the same file, reachable only by a path somebody has to test.
    // ---------------------------------------------------------------------

    /** A caller who writes a callee that reads is fine. The other way round is
     *  delegation used as a way to acquire a right the caller never had. */
    private static String delegating(String name, String callee, String scopes) {
        return """
               name: %s
               description: d
               model: fast
               tools: [agent_run]
               calls: [%s]
               scopes: [%s]
               max-turns: 4
               max-model-calls: 8
               """.formatted(name, callee, scopes);
    }

    @Test
    void a_sub_agent_may_not_hold_a_grant_its_caller_lacks(@TempDir Path dir) throws Exception {
        write(dir, "boss", delegating("boss", "helper", "workspace:read"), "body");
        write(dir, "helper", leaf("helper") + "scopes: [workspace:write]\n", "body");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("boss"), e.getMessage());
        assertTrue(e.getMessage().contains("helper"), e.getMessage());
        // The grant that escalated, in the spelling the file uses. Without it
        // an operator holding two grants has to work out which one was refused.
        assertTrue(e.getMessage().contains("workspace:write"), e.getMessage());
        // And what the caller does hold, which is the other half of the fix:
        // the choice is between narrowing the callee and widening the caller,
        // and neither can be made without seeing both sides. Measured — the
        // mutant that reports the caller's grants as "some grants" survived
        // every other assertion in this file.
        assertTrue(e.getMessage().contains("holds [workspace:read]"), e.getMessage());
    }

    /**
     * Narrowing is the ordinary case and must stay silent.
     *
     * <p>It is also the fixture that sees {@code Grant.allows} inverted: the
     * caller writes and the callee reads, so a check that made write <em>not</em>
     * imply read would refuse this perfectly legal pair at boot and take the
     * server with it.
     */
    @Test
    void a_sub_agent_holding_fewer_grants_than_its_caller_loads(@TempDir Path dir)
            throws Exception {
        write(dir, "boss", delegating("boss", "helper", "workspace:write"), "body");
        write(dir, "helper", leaf("helper") + "scopes: [workspace:read]\n", "body");

        assertEquals(List.of(new Grant(Scope.WORKSPACE, Mode.READ)),
                AgentRegistry.load(dir, TOOLS).get("helper").scopes());
    }

    /**
     * The test the plan says is the one that matters, and it is.
     *
     * <p>The cycle walk beside this check visits each node once — that is what
     * makes a diamond legal — so a subset check written onto that same visit
     * would see {@code builder} from whichever caller reached it first and skip
     * the other edge entirely. Here {@code builder} is within {@code
     * architect}'s grants and outside {@code clerk}'s, and {@code architect}
     * sorts first, so a node-once check passes a file that lets a read-only
     * agent delegate a write.
     *
     * <p>The failing edge is asserted whole rather than by its two names apart:
     * a message naming {@code clerk} and {@code builder} is what a check firing
     * on the wrong edge would also produce.
     */
    @Test
    void a_diamond_checks_both_edges_and_not_just_the_first(@TempDir Path dir) throws Exception {
        write(dir, "architect", delegating("architect", "builder", "workspace:write"), "body");
        write(dir, "clerk", delegating("clerk", "builder", "workspace:read"), "body");
        write(dir, "builder", leaf("builder") + "scopes: [workspace:write]\n", "body");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("'clerk' calls 'builder'"), e.getMessage());
        assertTrue(e.getMessage().contains("workspace:write"), e.getMessage());
    }

    /**
     * The commonest violation there will ever be: every agent shipped before
     * {@code scopes:} existed declares none, so the first file-reading agent
     * somebody delegates to will be reached from one of them.
     *
     * <p>It has its own sentence because {@code holds []} reads as an omission
     * rather than as the statement it is.
     */
    @Test
    void an_agent_that_declares_no_grants_may_delegate_to_no_agent_that_has_one(@TempDir Path dir)
            throws Exception {
        write(dir, "boss", """
                name: boss
                description: d
                model: fast
                tools: [agent_run]
                calls: [helper]
                max-turns: 4
                max-model-calls: 8
                """, "body");
        write(dir, "helper", leaf("helper") + "scopes: [workspace:read]\n", "body");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("holds none at all"), e.getMessage());
        assertTrue(e.getMessage().contains("workspace:read"), e.getMessage());
    }

    /**
     * A cycle is reported first, and the order is not arbitrary.
     *
     * <p>Grants in a cycle must be equal — each is a subset of the other — so a
     * cyclic pair that differs is both faults at once. The cycle is the one
     * with no downstream guard at all, and fixing the grants would leave a
     * directory that still cannot boot; reporting them the other way round
     * costs an operator a second boot to find that out.
     */
    @Test
    void a_cycle_is_reported_before_an_escalation_inside_it(@TempDir Path dir) throws Exception {
        write(dir, "alice", delegating("alice", "bob", "workspace:read"), "body");
        write(dir, "bob", delegating("bob", "alice", "workspace:write"), "body");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("form a cycle"), e.getMessage());
    }

    // ---------------------------------------------------------------------
    // The instance side: what the runtime holds after a successful load.
    // ---------------------------------------------------------------------

    @Test
    void the_registry_serves_the_definitions_it_loaded(@TempDir Path dir) throws Exception {
        write(dir, "judge", leaf("judge"), "You judge things.");
        AgentRegistry registry = AgentRegistry.of(dir, TOOLS);

        assertEquals(Set.of("judge"), registry.names());
        assertEquals("fast", registry.get("judge").model());
    }

    /** Every declared callee is checked at load, so an unknown name here came
     *  from a caller outside the graph — an MCP request naming an agent that
     *  does not exist. It gets the list of ones that do, not a null. */
    @Test
    void asking_for_an_agent_that_does_not_exist_names_the_ones_that_do(@TempDir Path dir)
            throws Exception {
        write(dir, "judge", leaf("judge"), "You judge things.");
        AgentRegistry registry = AgentRegistry.of(dir, TOOLS);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry.get("ghost"));
        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
        assertTrue(e.getMessage().contains("judge"), e.getMessage());
    }

    // ---------------------------------------------------------------------
    // Refusals that had no test at all until the spec review found them. The
    // commit that added this class claimed every guard had been broken and
    // its test confirmed to fail; for the seven below that was not true.
    // ---------------------------------------------------------------------

    /** Fences with nothing between them. YAML resolves that to null rather
     *  than to an empty map, so it is its own path. */
    @Test
    void empty_frontmatter_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "blank", "", "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("blank.md"), e.getMessage());
        assertTrue(e.getMessage().contains("no frontmatter keys"), e.getMessage());
    }

    /** YAML keys are not necessarily strings: {@code 42:} binds an Integer
     *  key, which is why the frontmatter is copied entry by entry rather than
     *  cast to a {@code Map<String, Object>}. */
    @Test
    void a_non_text_frontmatter_key_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "odd", leaf("odd") + "42: x\n", "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("non-text"), e.getMessage());
        assertTrue(e.getMessage().contains("42"), e.getMessage());
    }

    /** The shape {@code LlmConfig} has to name for {@code fast: ~} in a pool's
     *  classes: a key written, and nothing written after it. Present-but-null
     *  is not the same fault as absent, and gets its own message. */
    @Test
    void a_key_written_with_no_value_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "empty", """
                name: empty
                description: d
                model:
                tools: []
                max-turns: 4
                max-model-calls: 8
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("model"), e.getMessage());
        assertTrue(e.getMessage().contains("no value"), e.getMessage());
    }

    /** {@code tools: memory_read} without the brackets is the natural typo,
     *  and it binds a String where the runtime expects a list. */
    @Test
    void a_tool_list_that_is_not_a_list_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "flat", """
                name: flat
                description: d
                model: fast
                tools: memory_read
                max-turns: 4
                max-model-calls: 8
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("tools"), e.getMessage());
        assertTrue(e.getMessage().contains("not a list"), e.getMessage());
    }

    /**
     * The guard that actually catches {@code tools: [memory_read, ~]}.
     *
     * <p>{@code AgentDefinition}'s {@code List.copyOf} would also reject a null
     * element, and this class's javadoc used to credit it with doing so. It
     * never sees one on the load path: this refusal fires first, and unlike a
     * bare {@code NullPointerException} it names the file and the entry.
     */
    @Test
    void a_null_entry_in_a_tool_list_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "gappy", """
                name: gappy
                description: d
                model: fast
                tools: [memory_read, ~]
                max-turns: 4
                max-model-calls: 8
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("gappy.md"), e.getMessage());
        // "is not a name" and not merely "tools": coercing the null to the
        // string "null" would be caught by the unknown-tool guard instead, whose
        // message also contains the word tools. Measured — that mutant survived
        // the weaker assertion.
        assertTrue(e.getMessage().contains("is not a name"), e.getMessage());
    }

    /** Measured: {@code Files.list} throws {@code NoSuchFileException} for an
     *  absent path. Both this and the next test assert the wording that
     *  distinguishes this refusal from the generic I/O one, because the two
     *  otherwise differ only in which exception the multi-catch names. */
    @Test
    void a_missing_agent_directory_is_refused(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir.resolve("nowhere"), TOOLS));
        assertTrue(e.getMessage().contains("does not exist, or is not a directory"), e.getMessage());
    }

    /** Measured: {@code Files.list} throws {@code NotDirectoryException}, not
     *  {@code NoSuchFileException}, when the path exists but is a file. */
    @Test
    void an_agent_directory_that_is_a_file_is_refused(@TempDir Path dir) throws Exception {
        Path notADirectory = Files.writeString(dir.resolve("agents.md"), "x");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(notADirectory, TOOLS));
        assertTrue(e.getMessage().contains("does not exist, or is not a directory"), e.getMessage());
    }

    /** The first closing fence wins, so a markdown horizontal rule in the body
     *  stays in the prompt instead of truncating it. A prompt silently cut at
     *  its first rule is an agent given half its instructions. */
    @Test
    void a_horizontal_rule_in_the_body_stays_in_the_prompt(@TempDir Path dir) throws Exception {
        write(dir, "essay", leaf("essay"), "First part.\n\n---\n\nSecond part.");
        assertEquals("First part.\n\n---\n\nSecond part.",
                AgentRegistry.load(dir, TOOLS).get("essay").prompt());
    }

    /** The spec requires refusing a missing budget, and nothing asserted it:
     *  every other fixture writes both keys. */
    @Test
    void a_missing_required_key_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "incomplete", """
                name: incomplete
                description: d
                model: fast
                tools: []
                max-turns: 4
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("missing"), e.getMessage());
        assertTrue(e.getMessage().contains("max-model-calls"), e.getMessage());
    }

    /**
     * A budget must be the whole number an operator wrote, not a coercion of
     * whatever YAML resolved the value to.
     *
     * <p>{@code a_budget_that_is_not_a_whole_number_is_refused} writes {@code
     * many}, a String, which any non-{@code Number} check refuses too — so it
     * cannot tell {@code instanceof Integer} from {@code instanceof Number},
     * and widening the check survived it. Measured against snakeyaml 2.2:
     * {@code 4.0} resolves to a Double and {@code 99999999999999999999} to a
     * BigInteger, so {@code intValue()} on a Number would silently turn a
     * fractional or overflowing budget into some other number entirely. This
     * fixture is the one that can see the difference.
     */
    @Test
    void a_fractional_budget_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "fractional", """
                name: fractional
                description: d
                model: fast
                tools: []
                max-turns: 4.0
                max-model-calls: 8
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("max-turns"), e.getMessage());
        assertTrue(e.getMessage().contains("not a whole number"), e.getMessage());
    }

    // ---------------------------------------------------------------------
    // The other way in. The map constructor took its argument on trust, which
    // meant every guarantee in this class's javadoc held only for callers who
    // came through load() — a bad set could still exist, one door over.
    // ---------------------------------------------------------------------

    private static AgentDefinition definition(String name, List<String> tools, List<String> calls) {
        return definition(name, tools, calls, List.of());
    }

    private static AgentDefinition definition(
            String name, List<String> tools, List<String> calls, List<Grant> scopes) {
        return new AgentDefinition(name, "d", "fast", tools, calls, scopes, 4, 8, "body");
    }

    @Test
    void a_registry_built_directly_from_a_cycle_is_refused() {
        Map<String, AgentDefinition> cyclic = Map.of(
                "alice", definition("alice", List.of("agent_run"), List.of("bob")),
                "bob", definition("bob", List.of("agent_run"), List.of("alice")));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AgentRegistry(cyclic));
        assertTrue(e.getMessage().contains("alice"), e.getMessage());
        assertTrue(e.getMessage().contains("bob"), e.getMessage());
    }

    @Test
    void a_registry_built_directly_with_an_unknown_callee_is_refused() {
        Map<String, AgentDefinition> dangling = Map.of(
                "boss", definition("boss", List.of("agent_run"), List.of("ghost")));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new AgentRegistry(dangling)).getMessage().contains("ghost"));
    }

    @Test
    void a_registry_built_directly_with_mismatched_delegation_is_refused() {
        Map<String, AgentDefinition> lopsided = Map.of(
                "boss", definition("boss", List.of("agent_run"), List.of()));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new AgentRegistry(lopsided)).getMessage().contains("names no 'calls'"));
    }

    /** Non-escalation is a property of the set, so it is checked on both ways
     *  in — a fixture or a wiring that assembles definitions in Java gets the
     *  same refusal as a directory, which is the whole reason the set-level
     *  checks live in one place. */
    @Test
    void a_registry_built_directly_with_an_escalating_edge_is_refused() {
        Map<String, AgentDefinition> escalating = Map.of(
                "boss", definition("boss", List.of("agent_run"), List.of("helper"),
                        List.of(new Grant(Scope.WORKSPACE, Mode.READ))),
                "helper", definition("helper", List.of(), List.of(),
                        List.of(new Grant(Scope.WORKSPACE, Mode.WRITE))));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AgentRegistry(escalating));
        assertTrue(e.getMessage().contains("'boss' calls 'helper'"), e.getMessage());
    }

    /** The one fault load() cannot produce and a direct caller can: the walk
     *  looks agents up by key and the graph is declared by name, so a map where
     *  the two disagree has edges pointing at different nodes than its
     *  definitions name. */
    @Test
    void a_registry_whose_key_disagrees_with_its_definition_is_refused() {
        Map<String, AgentDefinition> misfiled =
                Map.of("alias", definition("real", List.of(), List.of()));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AgentRegistry(misfiled));
        assertTrue(e.getMessage().contains("alias"), e.getMessage());
        assertTrue(e.getMessage().contains("real"), e.getMessage());
    }

    // ---------------------------------------------------------------------
    // Files as other tools actually write them.
    // ---------------------------------------------------------------------

    /**
     * A file that visually opens with {@code ---} loads, even when an editor
     * put a byte-order mark in front of it.
     *
     * <p>Measured: {@code Character.isWhitespace('\uFEFF')} is false, so {@code
     * strip()} does not remove it and {@code readString} returns it as the first
     * character. Without the drop, this file is refused for "not opening with a
     * '---' fence" — a message pointing at something the operator cannot see.
     * Windows tools emit a BOM by default; PowerShell's {@code Out-File} does.
     */
    @Test
    void a_byte_order_mark_does_not_hide_the_fence(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("bommed.md"),
                "\uFEFF---\n" + leaf("bommed") + "---\nYou judge things.");
        assertEquals("You judge things.",
                AgentRegistry.load(dir, TOOLS).get("bommed").prompt());
    }

    /** {@code .md} matched case-insensitively. On a case-sensitive filesystem
     *  AGENT.MD would otherwise be skipped in silence — an agent that ceases to
     *  exist, which is what the fence check refuses to allow. */
    @Test
    void an_uppercase_extension_is_still_an_agent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("SHOUTY.MD"),
                "---\n" + leaf("SHOUTY") + "---\nYou judge things.");
        assertEquals(Set.of("SHOUTY"), AgentRegistry.of(dir, TOOLS).names());
    }

    /** A coverage gap rather than a known defect: the frontmatter name is bytes
     *  in a file and the stem is whatever readdir returns, and the two are
     *  compared in NFC because a filesystem that normalises differently would
     *  otherwise refuse a file over a difference invisible in the message. */
    @Test
    void a_non_ascii_name_binds(@TempDir Path dir) throws Exception {
        write(dir, "caf\u00e9", leaf("caf\u00e9"), "You judge things.");
        assertEquals("fast", AgentRegistry.of(dir, TOOLS).get("caf\u00e9").model());
    }

    /**
     * A file whose name is NFD and whose frontmatter is NFC still binds.
     *
     * <p>The first version of this comparison used raw {@code equals} and the
     * NFC mutant survived, because the test above writes both sides in NFC and
     * cannot see the difference. Measured on APFS: {@code readdir} returns a
     * name in exactly the form it was created with — a file saved NFD comes back
     * NFD — so the two sides genuinely can disagree while looking identical.
     * Refusing that pair would print two names an operator cannot tell apart,
     * which is the same dead end as the byte-order mark.
     */
    @Test
    void a_name_differing_only_by_unicode_normalisation_still_binds(@TempDir Path dir)
            throws Exception {
        String nfc = "caf\u00e9";        // e-acute as a single code point
        String nfd = "cafe\u0301";       // e followed by a combining acute
        Files.writeString(dir.resolve(nfd + ".md"),
                "---\n" + leaf(nfc) + "---\nYou judge things.");
        assertEquals("fast", AgentRegistry.of(dir, TOOLS).get(nfc).model());
    }

    // ---------------------------------------------------------------------
    // Exposure. Two axes, both declared and neither implied by the other:
    // `exported` is whether anything outside may name this agent, `delegable`
    // is whether anything inside may. A bot is exported and not delegable-to.
    // ---------------------------------------------------------------------

    /** The default, and it matches every other grant in this file: tools
     *  declared, scopes declared, delegation's two halves agreeing. Nothing is
     *  implicit, so a file that says nothing about export is not exported. */
    @Test
    void an_agent_that_says_nothing_about_export_is_not_exported(@TempDir Path dir)
            throws Exception {
        write(dir, "quiet", leaf("quiet"), "body");
        assertFalse(AgentRegistry.load(dir, TOOLS).get("quiet").exported());
    }

    @Test
    void an_agent_may_declare_itself_exported(@TempDir Path dir) throws Exception {
        write(dir, "front", leaf("front") + "exported: true\n", "body");
        assertTrue(AgentRegistry.load(dir, TOOLS).get("front").exported());
    }

    /** Lowercase, as `max-turns` and `tools` are, and the key set is closed —
     *  so the snake_case spelling is a refusal naming the keys that exist
     *  rather than an agent quietly loading unexported. */
    @Test
    void the_snake_case_spelling_of_exported_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "misspelt", leaf("misspelt") + "is_exported: true\n", "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("is_exported"), e.getMessage());
        assertTrue(e.getMessage().contains("exported"), e.getMessage());
    }

    /** Measured against snakeyaml 2.2: an unquoted `yes` resolves to
     *  Boolean.TRUE but a quoted "true" is a String, and Boolean.parseBoolean
     *  answers false for every string that is not "true" — so a typo would
     *  silently unexport an agent an operator believes is reachable. */
    @Test
    void an_exported_that_is_not_a_boolean_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "vague", leaf("vague") + "exported: maybe\n", "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("exported"), e.getMessage());
        assertTrue(e.getMessage().contains("true or false"), e.getMessage());
    }

    /** The other default, and it is `true` rather than `false` because this key
     *  is a refusal and not a grant: every file written before it existed is
     *  delegable-to, and an absent key must not silently sever an edge that
     *  `calls:` already declares and the loader already validated. */
    @Test
    void an_agent_that_says_nothing_about_delegation_may_be_called(@TempDir Path dir)
            throws Exception {
        write(dir, "quiet", leaf("quiet"), "body");
        assertTrue(AgentRegistry.load(dir, TOOLS).get("quiet").delegable());
    }

    /** "Nobody may call this one" is disagreeingHalves read the
     *  other way: the caller's file names an agent that has refused to be
     *  named, so the caller is the file that is wrong. */
    @Test
    void naming_an_agent_that_refuses_to_be_called_is_refused(@TempDir Path dir)
            throws Exception {
        write(dir, "bot", leaf("bot") + "delegable: false\n", "body");
        write(dir, "boss", """
                name: boss
                description: d
                model: fast
                tools: [agent_run]
                calls: [bot]
                max-turns: 4
                max-model-calls: 8
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("'boss' calls 'bot'"), e.getMessage());
        assertTrue(e.getMessage().contains("delegable: false"), e.getMessage());
    }

    /** The bot shape: exported, calls others, and called by none. It is what
     *  `interlocutor` already is, which is the argument that this declares
     *  something that exists rather than adding a kind of thing. */
    @Test
    void an_agent_nobody_may_call_may_still_call_others(@TempDir Path dir) throws Exception {
        write(dir, "helper", leaf("helper"), "body");
        write(dir, "bot", """
                name: bot
                description: d
                model: fast
                tools: [agent_run]
                calls: [helper]
                exported: true
                delegable: false
                max-turns: 4
                max-model-calls: 8
                """, "body");
        AgentDefinition bot = AgentRegistry.load(dir, TOOLS).get("bot");
        assertTrue(bot.exported());
        assertFalse(bot.delegable());
        assertEquals(List.of("helper"), bot.calls());
    }

    /** The third key of the same kind, and the one that says what a definition
     *  IS rather than who may reach it. Absent means agent, on `exported`'s
     *  rule and not `delegable`'s: every file written before the key existed
     *  is an agent, and a directory of seventeen of them keeps its meaning
     *  without being edited. */
    @Test
    void a_definition_that_says_nothing_about_being_a_bot_is_not_one(@TempDir Path dir)
            throws Exception {
        write(dir, "quiet", leaf("quiet"), "body");
        assertFalse(AgentRegistry.load(dir, TOOLS).get("quiet").bot());
    }

    @Test
    void a_definition_may_declare_itself_a_bot(@TempDir Path dir) throws Exception {
        write(dir, "somebody", leaf("somebody") + "bot: true\n", "body");
        assertTrue(AgentRegistry.load(dir, TOOLS).get("somebody").bot());
    }

    /** A definition that says nothing about the inbox announces nothing, on
     *  {@code bot}'s rule: every file written before the key existed keeps its
     *  meaning without being edited. */
    @Test
    void a_definition_that_says_nothing_about_the_inbox_does_not_announce_it(@TempDir Path dir)
            throws Exception {
        write(dir, "quiet", leaf("quiet"), "body");
        assertFalse(AgentRegistry.load(dir, TOOLS).get("quiet").announcesInbox());
    }

    @Test
    void a_definition_may_declare_that_it_announces_the_inbox(@TempDir Path dir) throws Exception {
        write(dir, "somebody", leaf("somebody") + "announces-inbox: true\n", "body");
        assertTrue(AgentRegistry.load(dir, TOOLS).get("somebody").announcesInbox());
    }

    /**
     * {@code withoutCallees} rebuilds the caller once its last route is
     * withheld, and it used to do that by writing out every field by hand —
     * exactly the copy that drops one silently when a new field joins the
     * record. {@code vision} and {@code bot} already survive this path; this
     * pins that {@code announces-inbox} does too, alongside them, so a bot
     * that also delegates and loses its last callee does not quietly stop
     * being told about its speaker's mail.
     *
     * <p>{@code read(dir, TOOLS, Set.of())} rather than {@code load}, because
     * an unreachable callee is only <em>withheld</em> — the item-shaped fault
     * that costs the route and keeps the caller serving — when the caller is
     * not in the required set; {@code load} treats every name as required and
     * would throw instead of exercising this path at all.
     */
    @Test
    void a_withheld_route_does_not_cost_the_caller_its_vision_bot_or_inbox_flags(
            @TempDir Path dir) throws Exception {
        write(dir, "bot", """
                name: bot
                description: d
                model: fast
                tools: [agent_run]
                calls: [ghost]
                vision: true
                bot: true
                announces-inbox: true
                board: true
                max-turns: 4
                max-model-calls: 8
                """, "body");
        AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, Set.of());
        assertTrue(loaded.enabled().containsKey("bot"), loaded.toString());
        AgentDefinition survivor = loaded.enabled().get("bot");
        assertTrue(survivor.vision(), "the withheld route cost the caller its vision requirement");
        assertTrue(survivor.bot(), "the withheld route demoted the caller from a bot to an agent");
        assertTrue(survivor.announcesInbox(),
                "the withheld route cost the caller its announces-inbox declaration");
        assertTrue(survivor.board(), "the withheld route cost the caller its board grant");
        assertTrue(survivor.calls().isEmpty(), survivor.calls().toString());
        assertFalse(survivor.tools().contains(AgentRegistry.AGENT_RUN), survivor.tools().toString());
    }

    /** The decision spec §2.1 records and `DataLayout.botsFor` states: the two
     *  directories are a filing convenience an operator arranges and the loader
     *  reads frontmatter. A definition in a directory called `bots` that says
     *  nothing is an agent, and nothing about the path is consulted. */
    @Test
    void the_directory_a_definition_sits_in_does_not_make_it_a_bot(@TempDir Path dir)
            throws Exception {
        Path bots = Files.createDirectories(dir.resolve("bots"));
        write(bots, "filed_here", leaf("filed_here"), "body");
        assertFalse(AgentRegistry.load(bots, TOOLS).get("filed_here").bot());
    }

    /** `requireBoolean`'s lesson a third time, and it is the direction that
     *  matters here too: `bot: yes` quoted, or misspelt, would read as text and
     *  text that is not the word true means false — a character quietly loading
     *  as an agent, which nothing downstream reports. */
    @Test
    void a_bot_that_is_not_a_boolean_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "vague", leaf("vague") + "bot: \"true\"\n", "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("bot"), e.getMessage());
        assertTrue(e.getMessage().contains("true or false"), e.getMessage());
    }

    @Test
    void a_delegable_that_is_not_a_boolean_is_refused(@TempDir Path dir) throws Exception {
        write(dir, "vague", leaf("vague") + "delegable: sometimes\n", "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("delegable"), e.getMessage());
        assertTrue(e.getMessage().contains("true or false"), e.getMessage());
    }

    /** The set-level half, on the door that assembles definitions in Java —
     *  the same reason non-escalation is checked on both ways in. */
    @Test
    void a_registry_built_directly_that_calls_an_undelegable_agent_is_refused() {
        Map<String, AgentDefinition> refusing = Map.of(
                "boss", definition("boss", List.of("agent_run"), List.of("bot")),
                "bot", new AgentDefinition(
                        "bot", "d", "fast", List.of(), List.of(), List.of(), 4, 8, "body",
                        true, false));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AgentRegistry(refusing));
        assertTrue(e.getMessage().contains("'boss' calls 'bot'"), e.getMessage());
    }

    @Test
    void the_conversation_folder_is_harness_only_and_holds_no_tools() {
        var registry = new AgentRegistry(AgentRegistry.load(Path.of("src/main/resources/agents"),
                BoundTools.boundByThisServer()));
        // assertDoesNotThrow and not assertNotNull, which is what this line was
        // and which could never have fired: AgentRegistry.get throws on a miss
        // and has no null to return, so the message under it described a failure
        // mode that does not exist and the real one -- the folder not shipping
        // at all -- would have surfaced as an IllegalArgumentException nobody
        // had written a sentence for.
        var definition = assertDoesNotThrow(() -> registry.get("conversation_folder"),
                "conversation_folder must ship as an agent definition");
        assertFalse(definition.exported(), "a person must not be able to run the folder");
        assertFalse(definition.delegable(), "an agent must not be able to delegate to the folder");
        assertTrue(definition.tools().isEmpty(), "the folder reads only what it is handed");
        assertTrue(definition.calls().isEmpty());
        assertFalse(definition.prompt().isBlank());
    }

    // --- bot: ---------------------------------------------------------------------------

    /**
     * The directory is a filing convenience, and this is what keeps that true:
     * the same bytes filed under {@code agents/} or {@code bots/} are the same
     * definition, and the loader reads frontmatter. A fixture rather than a
     * shipped bot, so a bot's owner can rewrite it without this test noticing.
     */
    @Test
    void the_flag_and_not_the_directory_is_what_makes_a_definition_a_bot(@TempDir Path dir)
            throws Exception {
        String character = leaf("character") + "bot: true\nexported: true\ndelegable: false\n";
        Path agents = Files.createDirectories(dir.resolve("agents"));
        Path bots = Files.createDirectories(dir.resolve("bots"));
        write(agents, "character", character, "You are somebody.");
        write(bots, "plain", leaf("plain"), "You judge things.");

        assertTrue(AgentRegistry.load(agents, TOOLS).get("character").bot(),
                "a bot filed under agents/ stopped being one, so the loader is reading the"
                        + " directory after all");
        assertFalse(AgentRegistry.load(bots, TOOLS).get("plain").bot(),
                "and a definition filed under bots/ that does not declare it is not one");
    }

    // --- fallback: ---------------------------------------------------------------------

    @Test
    void an_agent_that_says_nothing_about_a_fallback_has_none(@TempDir Path dir)
            throws Exception {
        write(dir, "judge", leaf("judge"), "You judge things.");
        AgentDefinition d = AgentRegistry.load(dir, TOOLS).get("judge");

        assertEquals(AgentDefinition.Fallback.NONE, d.fallback());
        assertFalse(d.fallback().permits(AgentDefinition.Fallback.Trigger.REFUSAL, 0),
                "an agent nobody said may be rerouted is not");
    }

    @Test
    void a_fallback_binds_from_its_block(@TempDir Path dir) throws Exception {
        write(dir, "osint", leaf("osint") + """
                fallback:
                  when: [refusal]
                  model: low_refusal_osint
                  max-attempts: 1
                """, "You research public sources.");
        AgentDefinition.Fallback fallback = AgentRegistry.load(dir, TOOLS).get("osint").fallback();

        assertEquals(Set.of(AgentDefinition.Fallback.Trigger.REFUSAL), fallback.on());
        assertEquals("low_refusal_osint", fallback.model());
        assertTrue(fallback.permits(AgentDefinition.Fallback.Trigger.REFUSAL, 0));
        assertFalse(fallback.permits(AgentDefinition.Fallback.Trigger.REFUSAL, 1),
                "one attempt, and a run that has taken it is not rerouted again");
    }

    @Test
    void a_fallback_written_with_on_is_refused_by_naming_what_yaml_did(@TempDir Path dir)
            throws Exception {
        write(dir, "osint", leaf("osint") + """
                fallback:
                  on: [refusal]
                  model: low_refusal_osint
                  max-attempts: 1
                """, "body");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(e.getMessage().contains("boolean") && e.getMessage().contains("when:"),
                e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "fallback:\n  when: [refusal]\n  model: low_refusal\n  max-attempts: 2\n",
            "fallback:\n  when: [timeout]\n  model: low_refusal\n  max-attempts: 1\n",
            "fallback:\n  when: []\n  model: low_refusal\n  max-attempts: 1\n",
            "fallback:\n  when: [refusal]\n  max-attempts: 1\n",
            "fallback:\n  when: [refusal]\n  modle: low_refusal\n  max-attempts: 1\n",
            "fallback: low_refusal\n"})
    void a_fallback_the_runtime_would_not_honour_as_written_is_refused(
            String block, @TempDir Path dir) throws Exception {
        write(dir, "osint", leaf("osint") + block.replace("\\n", "\n"), "body");
        assertThrows(IllegalStateException.class, () -> AgentRegistry.load(dir, TOOLS));
    }

    @Test
    void a_copy_that_withholds_a_route_keeps_the_fallback() {
        AgentDefinition caller = new AgentDefinition("caller", "d", "fast", List.of("agent_run"),
                List.of("callee"), List.of(), 4, 8, "You delegate.")
                .fallback(new AgentDefinition.Fallback(
                        Set.of(AgentDefinition.Fallback.Trigger.REFUSAL), "low_refusal", 1,
                        Sampling.NONE));

        AgentDefinition resampled = caller.sampling(Sampling.NONE);

        assertEquals(caller.fallback(), resampled.fallback(),
                "a copy that dropped the fallback would quietly make an agent's refusals final");
    }

    @Test
    void board_is_an_explicit_grant_and_defaults_to_false(@TempDir Path dir) throws Exception {
        write(dir, "caller", leaf("caller") + "bot: true\nboard: true\n", "body");
        write(dir, "plain", leaf("plain"), "body");
        var registry = AgentRegistry.load(dir, TOOLS);
        assertTrue(registry.get("caller").board());
        assertFalse(registry.get("plain").board());
        var own = registry.get("caller");
        assertTrue(own.withTools(List.of()).board());
        assertTrue(own.withScopes(List.of()).board());
        assertTrue(own.withCaps(2, 3).board());
        assertTrue(own.sampling(Sampling.NONE).board());
        assertTrue(own.fallback(AgentDefinition.Fallback.NONE).board());
    }

    @Test
    void an_ordinary_agent_can_be_granted_board_open(@TempDir Path dir) throws Exception {
        write(dir, "caller", leaf("caller") + "board: true\n", "body");
        var caller = AgentRegistry.load(dir, TOOLS).get("caller");
        assertTrue(caller.board());
        assertFalse(caller.bot());
    }

    @Test
    void board_requires_a_boolean(@TempDir Path dir) throws Exception {
        write(dir, "caller", leaf("caller") + "bot: true\nboard: \"yes\"\n", "body");
        var refused = assertThrows(IllegalStateException.class, () -> AgentRegistry.load(dir, TOOLS));
        assertTrue(refused.getMessage().contains("board"), refused.getMessage());
    }


    @Test
    void board_tools_are_granted_by_the_harness_and_never_declared(@TempDir Path dir) throws Exception {
        for (String tool : BoardTools.NAMES) {
            write(dir, "caller", leaf("caller").replace("tools: [memory_read]", "tools: [" + tool + "]"), "body");
            var refused = assertThrows(IllegalStateException.class, () -> AgentRegistry.load(dir, BoardTools.NAMES));
            assertTrue(refused.getMessage().contains("harness tool"), refused.getMessage());
        }
    }
}
