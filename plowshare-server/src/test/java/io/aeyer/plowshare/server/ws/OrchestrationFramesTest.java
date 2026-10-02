package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationRegistry;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.orchestrations.OrchestrationCancel;
import io.aeyer.plowshare.server.orchestrations.OrchestrationMessage;
import io.aeyer.plowshare.server.orchestrations.OrchestrationRecord;
import io.aeyer.plowshare.server.orchestrations.OrchestrationState;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore;
import io.aeyer.plowshare.server.orchestrations.Orchestrations;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoStatus;
import io.aeyer.plowshare.server.ws.OrchestrationFrames.Answered;
import io.aeyer.plowshare.server.ws.OrchestrationFrames.Cancelled;
import io.aeyer.plowshare.server.ws.OrchestrationFrames.DefinitionView;
import io.aeyer.plowshare.server.ws.OrchestrationFrames.Definitions;
import io.aeyer.plowshare.server.ws.OrchestrationFrames.Listed;
import io.aeyer.plowshare.server.ws.OrchestrationFrames.Status;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class OrchestrationFramesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OrchestrationStore store;
    private Orchestrations orchestrations;
    private OrchestrationCancel cancel;
    private TodoLists todos;
    private OrchestrationResolver resolver;
    private ProjectStore projects;
    private FrameRouter router;

    @BeforeEach
    void setUp() {
        store = mock(OrchestrationStore.class);
        orchestrations = mock(Orchestrations.class);
        cancel = mock(OrchestrationCancel.class);
        todos = mock(TodoLists.class);
        resolver = mock(OrchestrationResolver.class);
        projects = mock(ProjectStore.class);
        router = routerOver(providerOf(resolver));
    }

    private FrameRouter routerOver(ObjectProvider<OrchestrationResolver> provider) {
        return new FrameRoutingConfig().frameRouter(
                List.of(new OrchestrationFrames(store, orchestrations, cancel, todos, provider, projects)));
    }

    @Test
    void direct_start_checks_caller_grants_and_uses_socket_authority_without_a_bot_turn() {
        var callers = mock(io.aeyer.plowshare.server.agents.Callers.class);
        var grants = mock(io.aeyer.plowshare.server.orchestrations.CallerOrchestrations.class);
        var frame = new OrchestrationFrames(store, orchestrations, cancel, todos, providerOf(resolver), projects);
        frame.useStartAuthority(callers, providerForGrants(grants));
        var agent = mock(io.aeyer.plowshare.server.agents.AgentDefinition.class);
        when(agent.name()).thenReturn("authorised_caller");
        var caller = new io.aeyer.plowshare.server.agents.DefinitionResolver.Caller(null, "tab-1");
        when(callers.callerFor(null, "tab-1")).thenReturn(caller);
        when(callers.requireAgent("authorised_caller", caller)).thenReturn(agent);
        var definition = definition("custom_script");
        when(grants.granted(eq(agent), any(), eq(null), eq("tab-1"))).thenReturn(Map.of("custom_script", definition));
        var key = java.util.UUID.randomUUID();
        when(orchestrations.start(any(), eq(key), any())).thenReturn(run("orc_started", "enzo", OrchestrationState.RUNNING));
        var payload = Map.<String,Object>of("agent", "authorised_caller", "definition", "custom_script", "request", "small task", "requestId", key.toString());
        var outcome = frame.start(payload, new Asking("tab-1", "enzo"));
        assertEquals(Code.ACCEPTED, outcome.code());
        assertEquals("orc_started", ((OrchestrationFrames.Started) outcome.payload()).id());
        var captured = org.mockito.ArgumentCaptor.forClass(Orchestrations.Start.class);
        verify(orchestrations).start(captured.capture(), eq(key), any());
        assertEquals("enzo", captured.getValue().callerHandle());
        assertEquals("tab-1", captured.getValue().callerSession());
        org.junit.jupiter.api.Assertions.assertNull(captured.getValue().callerConversation());
        assertEquals(definition.hash(), captured.getValue().definition().hash());
        verify(callers).requireProject(null, "enzo");
        verify(callers).requireSession("tab-1", "enzo");
        when(grants.granted(eq(agent), any(), eq(null), eq("tab-1"))).thenReturn(Map.of());
        org.junit.jupiter.api.Assertions.assertThrows(io.aeyer.plowshare.server.faults.CallerFault.class,
                () -> frame.start(payload, new Asking("tab-1", "enzo")));
        org.mockito.Mockito.verifyNoMoreInteractions(orchestrations);
        org.junit.jupiter.api.Assertions.assertThrows(io.aeyer.plowshare.server.faults.CallerFault.class,
                () -> frame.start(payload, new Asking("tab-1")));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<io.aeyer.plowshare.server.orchestrations.CallerOrchestrations> providerForGrants(
            io.aeyer.plowshare.server.orchestrations.CallerOrchestrations available) {
        ObjectProvider<io.aeyer.plowshare.server.orchestrations.CallerOrchestrations> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(available);
        return provider;
    }

    @Test
    void receipt_lookup_is_account_scoped_and_applies_existing_run_visibility() {
        var key = java.util.UUID.randomUUID();
        when(store.startReceipt("enzo", key)).thenReturn(Optional.of(new OrchestrationStore.StartReceipt(key, "orc_original", false)));
        when(store.find("orc_original")).thenReturn(Optional.of(run("orc_original", "enzo", OrchestrationState.ASKING)));
        var result = route("orchestration.receipt", "{\"requestId\":\"" + key + "\"}");
        assertEquals(Code.OK, result.code());
        assertEquals("orc_original", ((OrchestrationFrames.Started) result.payload()).id());
        assertEquals("asking", ((OrchestrationFrames.Started) result.payload()).state());
        assertEquals(Code.BAD_REQUEST, routeAs("other", "orchestration.receipt", "{\"requestId\":\"" + key + "\"}").code());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OrchestrationResolver> providerOf(OrchestrationResolver available) {
        ObjectProvider<OrchestrationResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(available);
        return provider;
    }

    private Outcome route(String type, String json) {
        return router.route(FrameParity.frame(type, json), new Asking("tab-1", "enzo"));
    }

    private Outcome routeAs(String handle, String type, String json) {
        return router.route(FrameParity.frame(type, json), new Asking("tab-1", handle));
    }

    private static String source(String name) {
        return "---\nname: " + name + "\ndescription: Conducts " + name + ".\nmodel: reasoning\n"
                + "max-turns: 10\nmax-model-calls: 40\nstages:\n  - {id: goal}\n  - {id: code}\n"
                + "triggers: [\"" + name + "\", \"/" + name + "\"]\n---\nConduct " + name + ".\n";
    }

    private static OrchestrationDefinition definition(String name) {
        return OrchestrationRegistry.parsePinned(
                name, "test", source(name), Set.of(), OrchestrationDefinition.Tier.PROJECT);
    }

    private static OrchestrationRecord run(String id, String handle, OrchestrationState state) {
        return run(id, handle, state, null, 0, null);
    }

    private static OrchestrationRecord run(String id, String handle, OrchestrationState state,
            String parent, int depth, String waitingFor) {
        return new OrchestrationRecord(id, "code_implementation",
                OrchestrationDefinition.Tier.PROJECT, "sha256:x", source("code_implementation"),
                "test", List.of(), 3, 1, "story", "cnv_conductor", "cnv_caller", "interlocutor",
                handle, "ses_1", parent, depth, waitingFor, state, null, null, null, 0, 0, false, null,
                Instant.parse("2026-09-15T09:00:00Z"), null);
    }

    // -- definitions -----------------------------------------------------------------------

    @Test
    void definitions_merge_enabled_and_refused_rows_sorted_by_name() {
        OrchestrationDefinition bravo = definition("bravo");
        OrchestrationDefinition alpha = definition("alpha");
        when(resolver.forCaller(any())).thenReturn(Map.of("bravo", bravo, "alpha", alpha));
        when(resolver.refusalsFor(any())).thenReturn(Map.of("charlie", "charlie is disabled"));

        Outcome outcome = route(FrameTypes.ORCHESTRATION_DEFINITIONS, "{}");

        assertEquals(Code.OK, outcome.code());
        Definitions definitions = (Definitions) outcome.payload();
        assertEquals(List.of("alpha", "bravo", "charlie"),
                definitions.definitions().stream().map(DefinitionView::name).toList());

        DefinitionView served = definitions.definitions().get(0);
        assertEquals("alpha", served.name());
        assertEquals("Conducts alpha.", served.description());
        assertEquals("project", served.tier());
        assertEquals(List.of("goal", "code"),
                served.stages().stream().map(OrchestrationFrames.StageView::id).toList());
        assertEquals(List.of("alpha", "/alpha"), served.triggers());
        assertTrue(served.served());
        assertEquals(null, served.withheld());

        DefinitionView refused = definitions.definitions().get(2);
        assertEquals("charlie", refused.name());
        assertEquals(null, refused.description());
        assertEquals(null, refused.tier());
        assertEquals(List.of(), refused.stages());
        assertEquals(List.of(), refused.triggers());
        assertFalse(refused.served());
        assertEquals("charlie is disabled", refused.withheld());
    }

    @Test
    void a_refused_name_that_is_also_enabled_is_shown_once_as_enabled() {
        OrchestrationDefinition alpha = definition("alpha");
        when(resolver.forCaller(any())).thenReturn(Map.of("alpha", alpha));
        when(resolver.refusalsFor(any())).thenReturn(Map.of("alpha", "disabled elsewhere"));

        Outcome outcome = route(FrameTypes.ORCHESTRATION_DEFINITIONS, "{}");

        Definitions definitions = (Definitions) outcome.payload();
        assertEquals(1, definitions.definitions().size());
        DefinitionView only = definitions.definitions().get(0);
        assertEquals("alpha", only.name());
        assertTrue(only.served());
        assertEquals(null, only.withheld());
    }

    @Test
    void definitions_are_empty_when_no_resolver_is_available() {
        FrameRouter withoutResolver = routerOver(providerOf(null));

        Outcome outcome = withoutResolver.route(
                FrameParity.frame(FrameTypes.ORCHESTRATION_DEFINITIONS, "{}"), new Asking("tab-1"));

        assertEquals(Code.OK, outcome.code());
        assertEquals(new Definitions(List.of()), outcome.payload());
    }

    // -- list --------------------------------------------------------------------------------

    @Test
    void list_reads_only_the_asking_accounts_runs_with_its_filters() {
        when(store.byCaller("enzo", "story", OrchestrationState.ASKING, 50))
                .thenReturn(List.of(run("orc_1", "enzo", OrchestrationState.ASKING)));

        Outcome outcome = route(FrameTypes.ORCHESTRATION_LIST,
                "{\"project\":\"story\",\"state\":\"asking\"}");

        assertEquals(Code.OK, outcome.code());
        verify(store).byCaller("enzo", "story", OrchestrationState.ASKING, 50);
        assertEquals(List.of("orc_1"),
                ((Listed) outcome.payload()).orchestrations().stream()
                        .map(OrchestrationFrames.RunView::id).toList());
    }

    @Test
    void the_unknown_state_refusal_lists_waiting() {
        Outcome outcome = route(FrameTypes.ORCHESTRATION_LIST, "{\"state\":\"pondering\"}");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        assertEquals("orchestration.list has an unknown state 'pondering'; it is one of running,"
                + " asking, waiting, finished, failed, capped, cancelled.", outcome.said());
        verify(store, never()).byCaller(any(), any(), any(), anyInt());
    }

    @Test
    void a_listed_run_carries_stalledsince_when_the_store_has_marked_it() {
        Instant since = Instant.parse("2026-09-27T09:00:00Z");
        when(store.byCaller("enzo", null, null, 50))
                .thenReturn(List.of(run("orc_1", "enzo", OrchestrationState.RUNNING)));
        when(store.stalledSince("orc_1")).thenReturn(Optional.of(since));

        Outcome outcome = route(FrameTypes.ORCHESTRATION_LIST, "{}");

        assertEquals(since, ((Listed) outcome.payload()).orchestrations().get(0).stalledSince());
    }

    @Test
    void a_listed_run_carries_no_stalledsince_when_the_store_has_none() {
        when(store.byCaller("enzo", null, null, 50))
                .thenReturn(List.of(run("orc_1", "enzo", OrchestrationState.RUNNING)));
        when(store.stalledSince("orc_1")).thenReturn(Optional.empty());

        Outcome outcome = route(FrameTypes.ORCHESTRATION_LIST, "{}");

        assertEquals(null, ((Listed) outcome.payload()).orchestrations().get(0).stalledSince());
    }

    @Test
    void list_refuses_a_limit_out_of_range() {
        Outcome tooFew = route(FrameTypes.ORCHESTRATION_LIST, "{\"limit\":0}");
        Outcome tooMany = route(FrameTypes.ORCHESTRATION_LIST, "{\"limit\":201}");

        assertEquals(Code.BAD_REQUEST, tooFew.code());
        assertTrue(tooFew.said().contains("1 to 200"), tooFew.said());
        assertEquals(Code.BAD_REQUEST, tooMany.code());
        verify(store, never()).byCaller(any(), any(), any(), anyInt());
    }

    @Test
    void list_needs_a_signed_in_socket() {
        Outcome outcome = routeAs(null, FrameTypes.ORCHESTRATION_LIST, "{}");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        assertEquals("orchestration.list needs a socket signed in as an account, and this one is"
                + " not: sign in, or set plowshare.auth.admin-handle so the operator token is the"
                + " admin.", outcome.said());
        verify(store, never()).byCaller(any(), any(), any(), anyInt());
    }

    // -- status ------------------------------------------------------------------------------

    @Test
    void status_returns_the_run_its_todos_and_its_messages() {
        when(store.find("orc_1")).thenReturn(Optional.of(run("orc_1", "enzo", OrchestrationState.ASKING)));
        when(todos.list("cnv_conductor")).thenReturn(List.of(new TodoItem("td_1", "cnv_conductor",
                null, 0, "Implement", TodoStatus.IN_PROGRESS, null, true, "code",
                Instant.parse("2026-09-15T09:05:00Z"))));
        when(store.messages("orc_1")).thenReturn(List.of(new OrchestrationMessage("orm_1", "orc_1",
                OrchestrationMessage.Kind.QUESTION, "Which database?", "conductor",
                Instant.parse("2026-09-15T09:06:00Z"), null, null)));

        Outcome outcome = route(FrameTypes.ORCHESTRATION_STATUS, "{\"id\":\"orc_1\"}");

        assertEquals(Code.OK, outcome.code());
        Status status = (Status) outcome.payload();
        assertEquals("orc_1", status.orchestration().id());
        assertEquals("asking", status.orchestration().state());
        assertEquals(1, status.todos().size());
        assertEquals("code", status.todos().get(0).stage());
        assertEquals(1, status.messages().size());
        assertEquals("Which database?", status.messages().get(0).text());
    }

    @Test
    void a_status_names_the_parent_the_depth_and_the_children() {
        OrchestrationRecord own = run("orc_1", "enzo", OrchestrationState.WAITING, "orc_root", 1,
                "orc_child_1");
        when(store.find("orc_1")).thenReturn(Optional.of(own));
        when(todos.list("cnv_conductor")).thenReturn(List.of());
        when(store.messages("orc_1")).thenReturn(List.of());
        when(store.children("orc_1")).thenReturn(List.of(
                run("orc_child_2", "enzo", OrchestrationState.RUNNING),
                run("orc_child_1", "enzo", OrchestrationState.FINISHED)));

        Outcome outcome = route(FrameTypes.ORCHESTRATION_STATUS, "{\"id\":\"orc_1\"}");

        assertEquals(Code.OK, outcome.code());
        Status status = (Status) outcome.payload();
        assertEquals("orc_root", status.orchestration().parent());
        assertEquals(1, status.orchestration().depth());
        assertEquals("orc_child_1", status.orchestration().waitingFor());
        assertEquals(List.of("orc_child_2", "orc_child_1"),
                status.children().stream().map(OrchestrationFrames.ChildView::id).toList());
        assertEquals(List.of("running", "finished"),
                status.children().stream().map(OrchestrationFrames.ChildView::state).toList());
    }

    @Test
    void a_root_with_no_children_answers_an_empty_children_list() {
        when(store.find("orc_1")).thenReturn(Optional.of(run("orc_1", "enzo", OrchestrationState.RUNNING)));
        when(todos.list("cnv_conductor")).thenReturn(List.of());
        when(store.messages("orc_1")).thenReturn(List.of());
        when(store.children("orc_1")).thenReturn(List.of());

        Outcome outcome = route(FrameTypes.ORCHESTRATION_STATUS, "{\"id\":\"orc_1\"}");

        assertEquals(Code.OK, outcome.code());
        Status status = (Status) outcome.payload();
        assertEquals(null, status.orchestration().parent());
        assertEquals(0, status.orchestration().depth());
        assertEquals(null, status.orchestration().waitingFor());
        assertTrue(status.children().isEmpty());
    }

    @Test
    void status_of_another_accounts_run_reads_as_not_owned() {
        when(store.find("orc_missing")).thenReturn(Optional.empty());
        when(store.find("orc_theirs"))
                .thenReturn(Optional.of(run("orc_theirs", "mallory", OrchestrationState.RUNNING)));

        Outcome missing = route(FrameTypes.ORCHESTRATION_STATUS, "{\"id\":\"orc_missing\"}");
        Outcome theirs = route(FrameTypes.ORCHESTRATION_STATUS, "{\"id\":\"orc_theirs\"}");

        assertEquals(Code.BAD_REQUEST, missing.code());
        assertEquals(Code.BAD_REQUEST, theirs.code());
        assertEquals(missing.said(), theirs.said());
        assertEquals("No orchestration with that id is owned by this account.", missing.said());
    }

    @Test
    void a_missing_id_is_refused() {
        Outcome outcome = route(FrameTypes.ORCHESTRATION_STATUS, "{}");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        assertEquals("orchestration.status needs the orchestration id; nothing was done.",
                outcome.said());
    }

    // -- answer ------------------------------------------------------------------------------

    @Test
    void answer_routes_through_the_engine_with_the_handle_as_author() {
        when(store.find("orc_1"))
                .thenReturn(Optional.of(run("orc_1", "enzo", OrchestrationState.ASKING)))
                .thenReturn(Optional.of(run("orc_1", "enzo", OrchestrationState.RUNNING)));
        when(orchestrations.answer("orc_1", "Use PostgreSQL", "enzo")).thenReturn(true);

        Outcome outcome = route(FrameTypes.ORCHESTRATION_ANSWER,
                "{\"id\":\"orc_1\",\"answer\":\"Use PostgreSQL\"}");

        assertEquals(Code.OK, outcome.code());
        verify(orchestrations).answer("orc_1", "Use PostgreSQL", "enzo");
        assertEquals(new Answered("orc_1", "running"), outcome.payload());
    }

    @Test
    void answer_to_a_run_not_waiting_is_refused() {
        when(store.find("orc_1")).thenReturn(Optional.of(run("orc_1", "enzo", OrchestrationState.RUNNING)));
        when(orchestrations.answer(eq("orc_1"), any(), eq("enzo"))).thenReturn(false);

        Outcome outcome = route(FrameTypes.ORCHESTRATION_ANSWER,
                "{\"id\":\"orc_1\",\"answer\":\"too late\"}");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        assertEquals("Orchestration orc_1 is not waiting for an answer; nothing changed.",
                outcome.said());
    }

    @Test
    void a_persons_answer_that_came_second_says_who_answered_first_and_what() {
        // Spec 2026-09-29 §2: a cap question goes to the person and the parent model at once,
        // and the model's answer settled it first.
        when(store.find("orc_2")).thenReturn(Optional.of(run("orc_2", "enzo", OrchestrationState.RUNNING)));
        when(orchestrations.answer(eq("orc_2"), any(), eq("enzo"))).thenReturn(false);
        when(orchestrations.answeredAlready("orc_2")).thenReturn(Optional.of(
                "Orchestration orc_2's question was already answered by implement_specification"
                        + " (`yes`); nothing changed."));

        Outcome outcome = route(FrameTypes.ORCHESTRATION_ANSWER, "{\"id\":\"orc_2\",\"answer\":\"no\"}");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        assertEquals("Orchestration orc_2's question was already answered by implement_specification"
                + " (`yes`); nothing changed.", outcome.said());
    }

    @Test
    void a_blank_answer_is_refused_before_the_engine() {
        when(store.find("orc_1")).thenReturn(Optional.of(run("orc_1", "enzo", OrchestrationState.ASKING)));

        Outcome outcome = route(FrameTypes.ORCHESTRATION_ANSWER, "{\"id\":\"orc_1\",\"answer\":\"   \"}");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        assertEquals("orchestration.answer needs the answer text; nothing was answered.",
                outcome.said());
        verify(orchestrations, never()).answer(any(), any(), any());
    }

    @Test
    void another_account_cannot_answer() {
        when(store.find("orc_1"))
                .thenReturn(Optional.of(run("orc_1", "mallory", OrchestrationState.ASKING)));

        Outcome outcome = route(FrameTypes.ORCHESTRATION_ANSWER, "{\"id\":\"orc_1\",\"answer\":\"yes\"}");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        verify(orchestrations, never()).answer(any(), any(), any());
    }

    @Test
    void choices_route_through_the_engine_as_the_person() throws Exception {
        when(store.find("orc_1"))
                .thenReturn(Optional.of(run("orc_1", "enzo", OrchestrationState.ASKING)))
                .thenReturn(Optional.of(run("orc_1", "enzo", OrchestrationState.RUNNING)));
        when(orchestrations.answerChosen(eq("orc_1"), any(), eq("thanks"), eq("enzo"), eq(true)))
                .thenReturn(new Orchestrations.Chosen.Answered("1. [Store] chose \"SQLite\""));

        Outcome outcome = route(FrameTypes.ORCHESTRATION_ANSWER, """
                {"id":"orc_1","answer":"thanks","choices":[{"header":"Store","chosen":["SQLite"]}]}""");

        assertEquals(Code.OK, outcome.code());
        verify(orchestrations).answerChosen(eq("orc_1"),
                eq(JSON.readTree("[{\"header\":\"Store\",\"chosen\":[\"SQLite\"]}]")), eq("thanks"),
                eq("enzo"), eq(true));
        verify(orchestrations, never()).answer(any(), any(), any());
        assertEquals(new Answered("orc_1", "running"), outcome.payload());
    }

    @Test
    void choices_that_do_not_fit_are_refused_in_the_engine_s_sentence() {
        when(store.find("orc_1")).thenReturn(Optional.of(run("orc_1", "enzo", OrchestrationState.ASKING)));
        when(orchestrations.answerChosen(eq("orc_1"), any(), any(), eq("enzo"), eq(true)))
                .thenReturn(new Orchestrations.Chosen.Refused("'Store' is not answered; choose an"
                        + " option or give 'other'."));

        Outcome outcome = route(FrameTypes.ORCHESTRATION_ANSWER,
                "{\"id\":\"orc_1\",\"choices\":[{\"header\":\"Store\"}]}");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        assertEquals("'Store' is not answered; choose an option or give 'other'. Nothing was"
                + " answered.", outcome.said());
    }

    @Test
    void choices_that_came_second_say_who_answered_first() {
        when(store.find("orc_2")).thenReturn(Optional.of(run("orc_2", "enzo", OrchestrationState.RUNNING)));
        when(orchestrations.answerChosen(eq("orc_2"), any(), any(), eq("enzo"), eq(true)))
                .thenReturn(new Orchestrations.Chosen.Lost());
        when(orchestrations.answeredAlready("orc_2")).thenReturn(Optional.of(
                "Orchestration orc_2's question was already answered by interlocutor; nothing"
                        + " changed."));

        Outcome outcome = route(FrameTypes.ORCHESTRATION_ANSWER,
                "{\"id\":\"orc_2\",\"choices\":[{\"header\":\"Store\",\"chosen\":[\"SQLite\"]}]}");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        assertEquals("Orchestration orc_2's question was already answered by interlocutor; nothing"
                + " changed.", outcome.said());
    }

    @Test
    void a_status_carries_each_message_s_structure_as_an_object() throws Exception {
        when(store.find("orc_1")).thenReturn(Optional.of(run("orc_1", "enzo", OrchestrationState.ASKING)));
        when(todos.list(any())).thenReturn(List.of());
        when(store.children("orc_1")).thenReturn(List.of());
        when(store.messages("orc_1")).thenReturn(List.of(new OrchestrationMessage("msg_1", "orc_1",
                OrchestrationMessage.Kind.QUESTION, "First: …", "code_implementation",
                Instant.parse("2026-09-15T09:00:00Z"), null, null,
                "{\"lead\":\"First:\",\"questions\":[]}")));

        Outcome outcome = route(FrameTypes.ORCHESTRATION_STATUS, "{\"id\":\"orc_1\"}");

        OrchestrationFrames.Status status = (OrchestrationFrames.Status) outcome.payload();
        assertEquals(JSON.readTree("{\"lead\":\"First:\",\"questions\":[]}"),
                status.messages().get(0).structure());
    }

    // -- cancel ------------------------------------------------------------------------------

    @Test
    void cancel_routes_through_orchestration_cancel_with_the_handle() {
        when(store.find("orc_1")).thenReturn(Optional.of(run("orc_1", "enzo", OrchestrationState.RUNNING)));
        when(cancel.cancel("orc_1", "enzo")).thenReturn(true);

        Outcome outcome = route(FrameTypes.ORCHESTRATION_CANCEL, "{\"id\":\"orc_1\"}");

        assertEquals(Code.OK, outcome.code());
        assertEquals(new Cancelled("orc_1", "cancelled"), outcome.payload());
        verify(cancel).cancel("orc_1", "enzo");
    }

    @Test
    void cancel_of_an_ended_run_is_refused() {
        when(store.find("orc_1")).thenReturn(Optional.of(run("orc_1", "enzo", OrchestrationState.FINISHED)));
        when(cancel.cancel("orc_1", "enzo")).thenReturn(false);

        Outcome outcome = route(FrameTypes.ORCHESTRATION_CANCEL, "{\"id\":\"orc_1\"}");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        assertEquals("Orchestration orc_1 has already ended; nothing changed.", outcome.said());
    }

    @Test
    void another_account_cannot_cancel() {
        when(store.find("orc_1"))
                .thenReturn(Optional.of(run("orc_1", "mallory", OrchestrationState.RUNNING)));

        Outcome outcome = route(FrameTypes.ORCHESTRATION_CANCEL, "{\"id\":\"orc_1\"}");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        assertEquals("No orchestration with that id is owned by this account.", outcome.said());
        verify(cancel, never()).cancel(any(), any());
    }

    // -- wire shape --------------------------------------------------------------------------

    @Test
    void the_wire_form_of_status_is_stable() throws Exception {
        when(store.find("orc_1")).thenReturn(Optional.of(run("orc_1", "enzo", OrchestrationState.ASKING)));
        when(todos.list("cnv_conductor")).thenReturn(List.of());
        when(store.messages("orc_1")).thenReturn(List.of(new OrchestrationMessage("orm_1", "orc_1",
                OrchestrationMessage.Kind.QUESTION, "Which database?", "conductor",
                Instant.parse("2026-09-15T09:06:00Z"), null, null)));
        when(store.children("orc_1")).thenReturn(List.of());

        Outcome outcome = route(FrameTypes.ORCHESTRATION_STATUS, "{\"id\":\"orc_1\"}");
        String json = FrameJson.answering().writeValueAsString(outcome.payload());
        JsonNode root = FrameJson.answering().readTree(json);

        JsonNode orchestration = root.path("orchestration");
        for (String field : List.of("id", "definition", "tier", "project", "state", "pendingCap",
                "result", "failure", "returnsUsed", "maxReturns", "nudges", "restarts",
                "callerAgent", "callerConversation", "conductorConversation", "parent", "depth",
                "waitingFor", "createdAt", "endedAt", "stalledSince")) {
            assertTrue(orchestration.has(field), field + " missing from " + orchestration);
        }
        JsonNode message = root.path("messages").get(0);
        for (String field : List.of("kind", "text", "author", "deliveredAt", "capKind")) {
            assertTrue(message.has(field), field + " missing from " + message);
        }
        assertTrue(root.has("children"), "children missing from " + root);
    }
}
