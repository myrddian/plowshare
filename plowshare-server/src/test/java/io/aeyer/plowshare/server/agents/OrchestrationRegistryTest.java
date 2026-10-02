package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class OrchestrationRegistryTest {

    private static final Set<String> TOOLS = Set.of("file_read", "agent_run");

    private static void agent(Path dir, String name, String extra) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".md"), "---\nname: " + name + "\ndescription: d\n"
                + "model: m\nmax-turns: 2\nmax-model-calls: 4\n" + extra + "---\nYou " + name + ".\n");
    }

    private static void orchestration(Path dir, String name, String calls) throws Exception {
        orchestration(dir, name, calls, "");
    }

    private static void orchestration(Path dir, String name, String calls, String extra)
            throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".md"), "---\nname: " + name + "\ndescription: d\n"
                + "model: m\nmax-turns: 2\nmax-model-calls: 4\ncalls: " + calls + "\n"
                + extra + "stages:\n  - {id: goal}\n---\nYou conduct.\n");
    }

    private static AgentRegistry agents(Path dir) {
        // Not AgentRegistry.read(Path, Set, Set): that overload's requireDirectory refuses a
        // missing directory outright, which several fixtures below rely on reading as "no
        // agents", exactly as FilesystemDefinitions.list() already treats it. The
        // DefinitionSource overload carries the same disable-rule semantics without that guard.
        return new AgentRegistry(
                AgentRegistry.read(new FilesystemDefinitions(dir), TOOLS, Set.of()));
    }

    @Test
    void required_system_definition_must_exist_and_load(@TempDir Path root) throws Exception {
        Path dir = root.resolve("orchestrations");
        var layers = List.of(new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.SHIPPED,
                new FilesystemDefinitions(dir)));
        AgentRegistry agents = agents(root.resolve("agents"));
        var missing = assertThrows(IllegalStateException.class, () ->
                OrchestrationRegistry.readRequired(layers, TOOLS, agents, DefinitionChecks.NONE));
        assertTrue(missing.getMessage().contains("required system orchestration 'design_orchestration'"));
        assertTrue(missing.getMessage().contains("no global or shipped definition"));

        Files.createDirectories(dir);
        Files.writeString(dir.resolve("design_orchestration.md"), "no fence\n");
        var broken = assertThrows(IllegalStateException.class, () ->
                OrchestrationRegistry.readRequired(layers, TOOLS, agents, DefinitionChecks.NONE));
        assertTrue(broken.getMessage().contains("design_orchestration.md"), broken.getMessage());
    }

    @Test
    void valid_global_replacement_is_allowed_but_bad_required_shadow_aborts_boot(
            @TempDir Path root) throws Exception {
        Path global = root.resolve("global");
        Path shipped = root.resolve("shipped");
        orchestration(shipped, "design_orchestration", "[]");
        orchestration(global, "design_orchestration", "[]");
        Files.writeString(global.resolve("ordinary.md"), "broken\n");
        var layers = List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.GLOBAL,
                        new FilesystemDefinitions(global)),
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.SHIPPED,
                        new FilesystemDefinitions(shipped)));
        AgentRegistry agents = agents(root.resolve("agents"));
        var loaded = OrchestrationRegistry.readRequired(layers, TOOLS, agents, DefinitionChecks.NONE);
        assertEquals(OrchestrationDefinition.Tier.GLOBAL,
                loaded.enabled().get("design_orchestration").tier());
        assertTrue(loaded.disabled().containsKey("ordinary"));

        Files.writeString(global.resolve("design_orchestration.md"), "broken override\n");
        assertThrows(IllegalStateException.class, () ->
                OrchestrationRegistry.readRequired(layers, TOOLS, agents, DefinitionChecks.NONE));
    }

    @Test
    void every_good_file_is_enabled_by_name(@TempDir Path root) throws Exception {
        agent(root.resolve("agents"), "planner", "");
        orchestration(root.resolve("orchestrations"), "code_implementation", "[planner]");
        orchestration(root.resolve("orchestrations"), "deep_research", "[]");

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,
                        new FilesystemDefinitions(root.resolve("orchestrations")))),
                TOOLS, agents(root.resolve("agents")), DefinitionChecks.NONE);

        assertEquals(Set.of("code_implementation", "deep_research"), loaded.enabled().keySet());
        assertEquals(List.of(), List.copyOf(loaded.disabled().keySet()));
    }

    @Test
    void script_and_markdown_formats_cannot_silently_shadow_within_one_layer(@TempDir Path root) throws Exception {
        Path directory=root.resolve("orchestrations");
        orchestration(directory,"research","[]");
        Files.writeString(directory.resolve("research.js"),"""
                // plowshare-script v1
                export const manifest={name:'research',description:'A script',model:'m',tools:[],calls:[],scopes:[],
                  'max-turns':2,'max-model-calls':4,stages:[{id:'work'}]};
                export function step(input){return {state:{},command:{tool:'orchestration_finish',arguments:{result:'done'}}};}
                """);
        var source=new FilesystemDefinitions(directory,true);
        var layer=new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,source);
        var loaded=OrchestrationRegistry.read(List.of(layer),TOOLS,agents(root.resolve("agents")),DefinitionChecks.NONE);
        assertTrue(loaded.enabled().isEmpty());
        assertTrue(loaded.disabled().get("research").contains("defined more than once"));
        Files.delete(directory.resolve("research.md"));
        loaded=OrchestrationRegistry.read(List.of(layer),TOOLS,agents(root.resolve("agents")),DefinitionChecks.NONE);
        assertEquals(Set.of("research"),loaded.enabled().keySet());
        assertTrue(loaded.enabled().get("research").source().contains("export function step"));
        assertTrue(new FilesystemDefinitions(directory).list().isEmpty(),"Agent directories do not evaluate scripts");
    }

    @Test
    void a_bad_file_is_disabled_and_its_neighbours_still_load(@TempDir Path root) throws Exception {
        orchestration(root.resolve("orchestrations"), "good", "[]");
        Files.writeString(root.resolve("orchestrations").resolve("broken.md"), "no fence at all\n");

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,
                        new FilesystemDefinitions(root.resolve("orchestrations")))),
                TOOLS, agents(root.resolve("agents")), DefinitionChecks.NONE);

        assertEquals(Set.of("good"), loaded.enabled().keySet());
        assertTrue(loaded.disabled().get("broken").startsWith("the orchestration definition 'broken'"));
    }

    @Test
    void a_conductor_may_not_grant_itself_an_orchestration(@TempDir Path root) throws Exception {
        Path dir = root.resolve("orchestrations");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("nested.md"), """
                ---
                name: nested
                description: d
                model: m
                max-turns: 2
                max-model-calls: 4
                orchestrations: [nested]
                stages:
                  - {id: goal}
                ---
                You conduct.
                """);

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,
                        new FilesystemDefinitions(dir))), TOOLS,
                agents(root.resolve("agents")), DefinitionChecks.NONE);

        assertEquals(Set.of(), loaded.enabled().keySet());
        String reason = loaded.disabled().get("nested");
        assertTrue(reason.contains("the orchestrations nested -> nested form a cycle in the"
                + " orchestration grants"), reason);
        assertTrue(reason.contains("a run tree that never drains"), reason);
    }

    @Test
    void a_conductor_may_be_granted_orchestrations_and_hold_their_tools(@TempDir Path root)
            throws Exception {
        agent(root.resolve("agents"), "planner", "");
        orchestration(root.resolve("orchestrations"), "code_implementation", "[planner]",
                "orchestrations: [implement_task]\n"
                        + "tools: [orchestrate_implement_task, orchestration_answer,"
                        + " orchestration_status, orchestration_cancel]\n");
        orchestration(root.resolve("orchestrations"), "implement_task", "[]");

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,
                        new FilesystemDefinitions(root.resolve("orchestrations")))),
                Set.of("file_read", "agent_run", "orchestrate_implement_task",
                        "orchestration_answer", "orchestration_status", "orchestration_cancel"),
                agents(root.resolve("agents")), DefinitionChecks.NONE);

        assertEquals(Set.of("code_implementation", "implement_task"), loaded.enabled().keySet());
        assertEquals(List.of("implement_task"),
                loaded.enabled().get("code_implementation").conductor().orchestrations());
        assertTrue(loaded.enabled().get("code_implementation").conductor().tools()
                .contains("orchestrate_implement_task"));
    }

    @Test
    void a_conductor_may_not_grant_an_orchestration_whose_conductor_holds_a_wider_scope(
            @TempDir Path root) throws Exception {
        Path dir = root.resolve("orchestrations");
        Files.createDirectories(dir);
        // Two independent pairs, sorting in opposite directions, so the test tells "enforced"
        // apart from "enforced only when the grantee happens to sort first": the escalation check
        // runs over the finished `enabled` map, so file order must not matter to it either way.
        // "assistant" sorts before "narrow" (grantee first); "aardvark" sorts before "zebra"
        // (granter first) — it is "aardvark" that grants the wider "zebra" here.
        Files.writeString(dir.resolve("assistant.md"), "---\nname: assistant\ndescription: d\n"
                + "model: m\nmax-turns: 2\nmax-model-calls: 4\nscopes: [workspace:write]\n"
                + "stages:\n  - {id: goal}\n---\nYou conduct.\n");
        Files.writeString(dir.resolve("narrow.md"), "---\nname: narrow\ndescription: d\n"
                + "model: m\nmax-turns: 2\nmax-model-calls: 4\norchestrations: [assistant]\n"
                + "stages:\n  - {id: goal}\n---\nYou conduct.\n");
        Files.writeString(dir.resolve("aardvark.md"), "---\nname: aardvark\ndescription: d\n"
                + "model: m\nmax-turns: 2\nmax-model-calls: 4\norchestrations: [zebra]\n"
                + "stages:\n  - {id: goal}\n---\nYou conduct.\n");
        Files.writeString(dir.resolve("zebra.md"), "---\nname: zebra\ndescription: d\n"
                + "model: m\nmax-turns: 2\nmax-model-calls: 4\nscopes: [workspace:write]\n"
                + "stages:\n  - {id: goal}\n---\nYou conduct.\n");

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,
                        new FilesystemDefinitions(dir))),
                TOOLS, agents(root.resolve("agents")), DefinitionChecks.NONE);

        assertEquals(Set.of("assistant", "zebra"), loaded.enabled().keySet());
        String reason = loaded.disabled().get("narrow");
        assertTrue(reason.startsWith("the orchestration definition 'narrow' ("
                + dir.resolve("narrow.md") + ") grants 'assistant', which is granted"
                + " workspace:write — a grant the conductor 'narrow' does not hold: it holds none"
                + " at all"), reason);
        assertTrue(reason.contains("A grantee may hold fewer grants than its granter and never"
                + " more, or nesting is how a run widens its own reach"), reason);

        String reversed = loaded.disabled().get("aardvark");
        assertTrue(reversed.startsWith("the orchestration definition 'aardvark' ("
                + dir.resolve("aardvark.md") + ") grants 'zebra', which is granted"
                + " workspace:write — a grant the conductor 'aardvark' does not hold: it holds"
                + " none at all"), reversed);
        assertTrue(reversed.contains("nesting is how a run widens its own reach"), reversed);
    }

    @Test
    void two_definitions_that_grant_each_other_are_refused_naming_the_cycle(@TempDir Path root)
            throws Exception {
        Path dir = root.resolve("orchestrations");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("alpha.md"), "---\nname: alpha\ndescription: d\n"
                + "model: m\nmax-turns: 2\nmax-model-calls: 4\norchestrations: [beta]\n"
                + "stages:\n  - {id: goal}\n---\nYou conduct.\n");
        Files.writeString(dir.resolve("beta.md"), "---\nname: beta\ndescription: d\n"
                + "model: m\nmax-turns: 2\nmax-model-calls: 4\norchestrations: [alpha]\n"
                + "stages:\n  - {id: goal}\n---\nYou conduct.\n");

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,
                        new FilesystemDefinitions(dir))),
                TOOLS, agents(root.resolve("agents")), DefinitionChecks.NONE);

        assertEquals(Set.of(), loaded.enabled().keySet());
        String alphaReason = loaded.disabled().get("alpha");
        String betaReason = loaded.disabled().get("beta");
        assertTrue(alphaReason.contains("the orchestrations alpha -> beta -> alpha form a cycle"
                + " in the orchestration grants"), alphaReason);
        assertTrue(alphaReason.contains("a run tree that never drains"), alphaReason);
        assertEquals(alphaReason, betaReason);
    }

    @Test
    void a_grant_of_an_orchestration_no_tier_serves_is_not_a_cycle_and_not_a_refusal(
            @TempDir Path root) throws Exception {
        orchestration(root.resolve("orchestrations"), "solo", "[]",
                "orchestrations: [missing]\n");

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,
                        new FilesystemDefinitions(root.resolve("orchestrations")))),
                TOOLS, agents(root.resolve("agents")), DefinitionChecks.NONE);

        assertEquals(Set.of("solo"), loaded.enabled().keySet());
        assertEquals(List.of(), List.copyOf(loaded.disabled().keySet()));
    }

    @Test
    void a_trigger_with_no_loaded_agent_grant_is_warned_about(@TempDir Path root) throws Exception {
        agent(root.resolve("agents"), "caller", "orchestrations: [granted]\n");
        orchestration(root.resolve("orchestrations"), "granted", "[]", "triggers: [/granted]\n");
        orchestration(root.resolve("orchestrations"), "orphan", "[]", "triggers: [/orphan]\n");
        Logger logger = (Logger) LoggerFactory.getLogger(OrchestrationRegistry.class);
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.start();
        logger.addAppender(warnings);

        try {
            OrchestrationRegistry.read(List.of(new OrchestrationRegistry.Layer(
                            OrchestrationDefinition.Tier.PROJECT,
                            new FilesystemDefinitions(root.resolve("orchestrations")))),
                    TOOLS, agents(root.resolve("agents")), DefinitionChecks.NONE);
        } finally {
            logger.detachAppender(warnings);
            warnings.stop();
        }

        List<String> messages = warnings.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(messages.stream().anyMatch(message -> message.contains("'orphan'")
                && message.contains("no loaded agent is granted")), messages.toString());
        assertFalse(messages.stream().anyMatch(message -> message.contains("'granted'")
                && message.contains("no loaded agent is granted")), messages.toString());
    }

    @Test
    void a_trigger_granted_only_by_another_orchestrations_conductor_is_not_warned_about(
            @TempDir Path root) throws Exception {
        // Nothing in agents/ grants 'implement_task' at all — only code_implementation's own
        // conductor does, through 'orchestrations:'. The trigger is reachable through that grant,
        // so it must not be reported as orphaned just because the reachability is a conductor's
        // and not a plain agent's.
        orchestration(root.resolve("orchestrations"), "code_implementation", "[]",
                "orchestrations: [implement_task]\n");
        orchestration(root.resolve("orchestrations"), "implement_task", "[]",
                "triggers: [/implement]\n");
        Logger logger = (Logger) LoggerFactory.getLogger(OrchestrationRegistry.class);
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.start();
        logger.addAppender(warnings);

        try {
            OrchestrationRegistry.read(List.of(new OrchestrationRegistry.Layer(
                            OrchestrationDefinition.Tier.PROJECT,
                            new FilesystemDefinitions(root.resolve("orchestrations")))),
                    TOOLS, agents(root.resolve("agents")), DefinitionChecks.NONE);
        } finally {
            logger.detachAppender(warnings);
            warnings.stop();
        }

        List<String> messages = warnings.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertFalse(messages.stream().anyMatch(message -> message.contains("'implement_task'")
                && message.contains("no loaded agent is granted")), messages.toString());
    }

    @Test
    void a_callee_the_tier_does_not_serve_disables_the_orchestration(@TempDir Path root)
            throws Exception {
        orchestration(root.resolve("orchestrations"), "code_implementation", "[planner]");

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,
                        new FilesystemDefinitions(root.resolve("orchestrations")))),
                TOOLS, agents(root.resolve("agents")), DefinitionChecks.NONE);

        assertEquals("the orchestration definition 'code_implementation' ("
                + root.resolve("orchestrations").resolve("code_implementation.md")
                + ") calls 'planner', which is not an agent this tier serves",
                loaded.disabled().get("code_implementation"));
    }

    @Test
    void a_callee_that_is_not_delegable_disables_the_orchestration(@TempDir Path root)
            throws Exception {
        agent(root.resolve("agents"), "concierge", "delegable: false\nexported: true\n");
        orchestration(root.resolve("orchestrations"), "code_implementation", "[concierge]");

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,
                        new FilesystemDefinitions(root.resolve("orchestrations")))),
                TOOLS, agents(root.resolve("agents")), DefinitionChecks.NONE);

        assertTrue(loaded.disabled().get("code_implementation")
                .endsWith("calls 'concierge', which is not delegable: an agent can never run it"));
    }

    @Test
    void the_servers_definition_checks_run_over_every_conductor(@TempDir Path root)
            throws Exception {
        orchestration(root.resolve("orchestrations"), "served", "[]");
        orchestration(root.resolve("orchestrations"), "unserved", "[]");
        DefinitionChecks noModelForUnserved = (loaded, source) ->
                loaded.enabled().containsKey("unserved")
                        ? loaded.without("unserved", "no pool serves the model 'm'")
                        : loaded;

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,
                        new FilesystemDefinitions(root.resolve("orchestrations")))),
                TOOLS, agents(root.resolve("agents")), noModelForUnserved);

        assertEquals(Set.of("served"), loaded.enabled().keySet());
        assertEquals("no pool serves the model 'm'", loaded.disabled().get("unserved"));
    }

    @Test
    void a_file_the_parser_throws_on_unexpectedly_is_disabled_not_a_failed_boot(@TempDir Path root)
            throws Exception {
        orchestration(root.resolve("orchestrations"), "good", "[]");
        DefinitionSource files = new FilesystemDefinitions(root.resolve("orchestrations"));
        // A null text is no shape a real file reaches: it stands in for whatever unforeseen shape
        // makes the parser throw something other than its own refusal.
        DefinitionSource source = new DefinitionSource() {
            @Override
            public String describe() {
                return files.describe();
            }

            @Override
            public List<DefinitionSource.Definition> list() {
                List<DefinitionSource.Definition> all = new java.util.ArrayList<>(files.list());
                all.add(new DefinitionSource.Definition("strange", "test", null));
                return all;
            }
        };

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT, source)),
                TOOLS, agents(root.resolve("agents")), DefinitionChecks.NONE);

        assertEquals(Set.of("good"), loaded.enabled().keySet());
        assertTrue(loaded.disabled().get("strange").startsWith(
                "the orchestration definition 'strange' (test) could not be read: "
                        + "java.lang.NullPointerException"), loaded.disabled().get("strange"));
    }

    @Test
    void the_checked_conductor_is_the_one_kept(@TempDir Path root) throws Exception {
        orchestration(root.resolve("orchestrations"), "triage", "[]");
        DefinitionChecks redescribe = (loaded, source) -> {
            java.util.Map<String, AgentDefinition> replaced = new java.util.LinkedHashMap<>();
            loaded.enabled().forEach((name, d) -> replaced.put(name, new AgentDefinition(d.name(),
                    "checked", d.model(), d.intent(), d.sampling(), d.tools(), d.calls(),
                    d.scopes(), d.maxTurns(), d.maxModelCalls(), d.prompt(), d.exported(),
                    d.delegable(), d.vision(), d.bot(), d.announcesInbox(), d.fallback())));
            return new AgentRegistry.Loaded(replaced, loaded.disabled(), loaded.withheldEdges(),
                    loaded.withheldTools());
        };

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,
                        new FilesystemDefinitions(root.resolve("orchestrations")))),
                TOOLS, agents(root.resolve("agents")), redescribe);

        assertEquals("checked", loaded.enabled().get("triage").conductor().description());
        assertEquals("checked", loaded.enabled().get("triage").description());
    }

    @Test
    void a_conductor_may_not_call_an_agent_holding_a_grant_it_does_not(@TempDir Path root)
            throws Exception {
        agent(root.resolve("agents"), "writer", "scopes: [workspace:write]\n");
        Path dir = root.resolve("orchestrations");
        Files.createDirectories(dir);
        for (String[] conductor : new String[][] {{"narrow", "[]"}, {"wide", "[workspace:write]"}}) {
            Files.writeString(dir.resolve(conductor[0] + ".md"), "---\nname: " + conductor[0]
                    + "\ndescription: d\nmodel: m\nmax-turns: 2\nmax-model-calls: 4\n"
                    + "calls: [writer]\nscopes: " + conductor[1] + "\n"
                    + "stages:\n  - {id: goal}\n---\nYou conduct.\n");
        }

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,
                        new FilesystemDefinitions(dir))),
                TOOLS, agents(root.resolve("agents")), DefinitionChecks.NONE);

        assertEquals(Set.of("wide"), loaded.enabled().keySet());
        String reason = loaded.disabled().get("narrow");
        assertTrue(reason.startsWith("the orchestration definition 'narrow' ("
                + dir.resolve("narrow.md") + ") calls 'writer', which is granted workspace:write"),
                reason);
    }

    @Test
    void an_empty_source_is_an_empty_tier(@TempDir Path root) {
        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,
                        new FilesystemDefinitions(root.resolve("absent")))),
                TOOLS, agents(root.resolve("agents")), DefinitionChecks.NONE);
        assertEquals(OrchestrationRegistry.Loaded.EMPTY, loaded);
    }

    @Test
    void each_definition_carries_the_tier_of_the_layer_it_was_read_from(@TempDir Path root)
            throws Exception {
        orchestration(root.resolve("global"), "deep_research", "[]");
        orchestration(root.resolve("shipped"), "triage", "[]");

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.GLOBAL,
                        new FilesystemDefinitions(root.resolve("global"))),
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.SHIPPED,
                        new FilesystemDefinitions(root.resolve("shipped")))),
                TOOLS, agents(root.resolve("agents")), DefinitionChecks.NONE);

        assertEquals(OrchestrationDefinition.Tier.GLOBAL, loaded.enabled().get("deep_research").tier());
        assertEquals(OrchestrationDefinition.Tier.SHIPPED, loaded.enabled().get("triage").tier());
    }

    @Test
    void a_name_in_a_more_specific_layer_hides_it_below_even_when_disabled(@TempDir Path root)
            throws Exception {
        Files.createDirectories(root.resolve("global"));
        Files.writeString(root.resolve("global").resolve("deep_research.md"), "no fence\n");
        orchestration(root.resolve("shipped"), "deep_research", "[]");

        OrchestrationRegistry.Loaded loaded = OrchestrationRegistry.read(List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.GLOBAL,
                        new FilesystemDefinitions(root.resolve("global"))),
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.SHIPPED,
                        new FilesystemDefinitions(root.resolve("shipped")))),
                TOOLS, agents(root.resolve("agents")), DefinitionChecks.NONE);

        assertFalse(loaded.enabled().containsKey("deep_research"));
        assertTrue(loaded.disabled().get("deep_research").startsWith(
                "the orchestration definition 'deep_research'"));
    }

    // --- checker: (spec 2026-10-01, the acceptance checker §2) ---------------------------------

    private static OrchestrationRegistry.Loaded withChecker(Path root, String checkerExtra)
            throws Exception {
        if (checkerExtra != null) {
            agent(root.resolve("agents"), "acceptance_checker", checkerExtra);
        }
        Path dir = root.resolve("orchestrations");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("implement_specification.md"), "---\nname:"
                + " implement_specification\ndescription: d\nmodel: m\nmax-turns: 2\n"
                + "max-model-calls: 4\ncalls: []\nchecker: acceptance_checker\n"
                + "artifacts: docs/orchestrations/{id}/\nstages:\n  - {id: spec, acceptance:"
                + " written}\n  - {id: acceptance, acceptance: required}\n---\nYou conduct.\n");
        return OrchestrationRegistry.read(List.of(new OrchestrationRegistry.Layer(
                OrchestrationDefinition.Tier.PROJECT, new FilesystemDefinitions(dir))), TOOLS,
                agents(root.resolve("agents")), DefinitionChecks.NONE);
    }

    @Test
    void a_read_only_undelegable_checker_is_served(@TempDir Path root) throws Exception {
        OrchestrationRegistry.Loaded loaded = withChecker(root,
                "tools: [file_read]\ncalls: []\ndelegable: false\n");

        assertEquals(Set.of("implement_specification"), loaded.enabled().keySet(),
                loaded.disabled().toString());
    }

    @Test
    void a_checker_that_is_missing_delegable_or_more_than_read_only_disables_it(
            @TempDir Path root) throws Exception {
        assertTrue(withChecker(root.resolve("a"), null).disabled().get("implement_specification")
                .contains("names the checker 'acceptance_checker', which is not an agent this tier"
                        + " serves"));
        assertTrue(withChecker(root.resolve("b"), "tools: [file_read]\ncalls: []\n").disabled()
                .get("implement_specification").contains("which is delegable"));
        agent(root.resolve("c").resolve("agents"), "helper", "");
        assertTrue(withChecker(root.resolve("c"), "tools: [file_read, agent_run]\n"
                + "calls: [helper]\ndelegable: false\n").disabled()
                .get("implement_specification").contains("which holds the tools [agent_run]"));
    }
}
