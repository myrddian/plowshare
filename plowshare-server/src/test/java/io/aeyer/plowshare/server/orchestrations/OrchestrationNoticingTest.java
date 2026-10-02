package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationRegistry;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.todos.TodoLists;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrchestrationNoticingTest {

    private static final String CODE_IMPLEMENTATION_SOURCE = """
            ---
            name: code_implementation
            description: Takes a change to reviewed code.
            model: reasoning
            max-turns: 10
            max-model-calls: 40
            triggers:
              - implement
              - /implement
            stages:
              - {id: goal}
              - {id: spec}
            ---
            Conduct the implementation.
            """;

    private static final String NO_TRIGGERS_SOURCE = """
            ---
            name: docs_pass
            description: Cleans up documentation.
            model: reasoning
            max-turns: 10
            max-model-calls: 40
            stages:
              - {id: sweep}
            ---
            Conduct the docs pass.
            """;

    private static final String SHIP_IT_SOURCE = """
            ---
            name: ship_it
            description: Ships a reviewed change.
            model: reasoning
            max-turns: 10
            max-model-calls: 40
            triggers:
              - ship
            stages:
              - {id: release}
            ---
            Conduct the release.
            """;

    private CallerOrchestrations callers;
    private OrchestrationStore store;
    private OrchestrationNoticing noticing;
    private AgentDefinition agent;
    private OrchestrationDefinition codeImplementation;

    @BeforeEach
    void setUp() {
        callers = mock(CallerOrchestrations.class);
        store = mock(OrchestrationStore.class);
        noticing = new OrchestrationNoticing(callers, store);
        codeImplementation = OrchestrationRegistry.parsePinned("code_implementation", "test",
                CODE_IMPLEMENTATION_SOURCE, Set.of(), OrchestrationDefinition.Tier.PROJECT);
        agent = new AgentDefinition("interlocutor", "d", "m", AgentDefinition.DEFAULT_INTENT,
                Sampling.NONE, List.of(), List.of(), List.of(), 4, 8, "Talk.", true, false, false,
                true, false, AgentDefinition.Fallback.NONE, List.of("code_implementation"));
        when(store.liveDefinitionsFrom(any())).thenReturn(Set.of());
    }

    @Test
    void a_matching_granted_orchestration_is_named_with_its_trigger_and_stages() {
        when(callers.granted(agent, Home.of("story"), "cnv_1", "ses_1"))
                .thenReturn(Map.of("code_implementation", codeImplementation));
        when(store.liveDefinitionsFrom("cnv_1")).thenReturn(Set.of());

        Optional<String> notice = noticing.noticeFor(agent, "implement the parser",
                Home.of("story"), "ses_1", "cnv_1");

        assertEquals(Optional.of(
                "This request may suit an orchestration. You may start it, or carry on without it:\n"
                        + "- orchestrate_code_implementation (matched \"implement\"; stages: goal, spec)"),
                notice);
    }

    @Test
    void nothing_matches_and_nothing_is_said() {
        when(callers.granted(agent, Home.of("story"), "cnv_1", "ses_1"))
                .thenReturn(Map.of("code_implementation", codeImplementation));

        Optional<String> notice = noticing.noticeFor(agent, "please clean the kitchen",
                Home.of("story"), "ses_1", "cnv_1");

        assertEquals(Optional.empty(), notice);
    }

    @Test
    void an_orchestration_already_live_from_this_conversation_is_not_named_again() {
        when(callers.granted(agent, Home.of("story"), "cnv_1", "ses_1"))
                .thenReturn(Map.of("code_implementation", codeImplementation));
        when(store.liveDefinitionsFrom("cnv_1")).thenReturn(Set.of("code_implementation"));

        Optional<String> notice = noticing.noticeFor(agent, "implement the parser",
                Home.of("story"), "ses_1", "cnv_1");

        assertEquals(Optional.empty(), notice);
    }

    @Test
    void two_matches_are_named_in_grant_order() {
        OrchestrationDefinition shipIt = OrchestrationRegistry.parsePinned("ship_it", "test",
                SHIP_IT_SOURCE, Set.of(), OrchestrationDefinition.Tier.PROJECT);
        Map<String, OrchestrationDefinition> granted = new LinkedHashMap<>();
        granted.put("code_implementation", codeImplementation);
        granted.put("ship_it", shipIt);
        when(callers.granted(agent, Home.of("story"), "cnv_1", "ses_1")).thenReturn(granted);
        when(store.liveDefinitionsFrom("cnv_1")).thenReturn(Set.of());

        Optional<String> notice = noticing.noticeFor(agent, "implement and ship it",
                Home.of("story"), "ses_1", "cnv_1");

        assertEquals(Optional.of(
                "This request may suit an orchestration. You may start it, or carry on without it:\n"
                        + "- orchestrate_code_implementation (matched \"implement\"; stages: goal, spec)\n"
                        + "- orchestrate_ship_it (matched \"ship\"; stages: release)"),
                notice);
    }

    @Test
    void a_run_with_no_conversation_skips_the_liveness_check() {
        when(callers.granted(agent, Home.of("story"), null, "ses_1"))
                .thenReturn(Map.of("code_implementation", codeImplementation));

        Optional<String> notice = noticing.noticeFor(agent, "implement the parser",
                Home.of("story"), "ses_1", null);

        assertEquals(Optional.of(
                "This request may suit an orchestration. You may start it, or carry on without it:\n"
                        + "- orchestrate_code_implementation (matched \"implement\"; stages: goal, spec)"),
                notice);
        verify(store, never()).liveDefinitionsFrom(any());
    }

    @Test
    void a_granted_orchestration_with_no_triggers_is_never_named() {
        OrchestrationDefinition noTriggers = OrchestrationRegistry.parsePinned("docs_pass", "test",
                NO_TRIGGERS_SOURCE, Set.of(), OrchestrationDefinition.Tier.PROJECT);
        when(callers.granted(agent, Home.of("story"), "cnv_1", "ses_1"))
                .thenReturn(Map.of("docs_pass", noTriggers));

        Optional<String> notice = noticing.noticeFor(agent, "implement and clean up the docs",
                Home.of("story"), "ses_1", "cnv_1");

        assertEquals(Optional.empty(), notice);
    }

    /**
     * An orchestration the project can reach is still not this agent's to start. {@code
     * CallerOrchestrations.granted} is where that intersection is decided — its own grant list
     * against what {@link OrchestrationResolver#forCaller} resolves — so this test builds a real
     * {@link CallerOrchestrations} over a mocked {@link OrchestrationResolver} and {@link
     * Callers}, on {@link CallerOrchestrationsTest}'s own pattern, rather than mocking {@code
     * granted} directly: a mock of the seam under test would prove nothing about the seam itself.
     */
    @Test
    void an_orchestration_the_project_can_reach_but_the_agent_is_not_granted_stays_silent() {
        OrchestrationDefinition shipIt = OrchestrationRegistry.parsePinned("ship_it", "test",
                SHIP_IT_SOURCE, Set.of(), OrchestrationDefinition.Tier.PROJECT);
        OrchestrationResolver resolver = mock(OrchestrationResolver.class);
        Callers callerResolver = mock(Callers.class);
        DefinitionResolver.Caller resolvedCaller = new DefinitionResolver.Caller(7L, "ses_1");
        when(callerResolver.callerForConversation("cnv_1", "ses_1")).thenReturn(resolvedCaller);
        // The project's tier reaches ship_it, but `agent` (built in setUp) grants only
        // code_implementation -- so CallerOrchestrations.granted must drop it before it ever
        // reaches this seam's matching.
        when(resolver.forCaller(resolvedCaller)).thenReturn(Map.of("ship_it", shipIt));
        CallerOrchestrations realCallers = new CallerOrchestrations(resolver, callerResolver,
                mock(Orchestrations.class), mock(OrchestrationCancel.class), store,
                mock(TodoLists.class));
        OrchestrationNoticing realNoticing = new OrchestrationNoticing(realCallers, store);

        Optional<String> notice = realNoticing.noticeFor(agent, "let's ship it",
                Home.of("story"), "ses_1", "cnv_1");

        assertEquals(Optional.empty(), notice);
    }
}
