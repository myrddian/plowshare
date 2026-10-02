package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Pictures;
import io.aeyer.plowshare.server.agents.Runs;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.api.AgentController;
import io.aeyer.plowshare.server.api.ApiExceptionHandler;
import io.aeyer.plowshare.server.archive.ProjectStore;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The trap neither pilot can spring: an endpoint whose <em>answer</em> depends
 * on which caller asked.
 *
 * <h2>Why this file exists beside its area's own parity suite</h2>
 *
 * <p>{@code project.define} and {@code conversation.turns} both ignore the
 * caller entirely, so the two pilots proved the dispatcher's shape against the
 * easy half of the surface. Most of what the breadth plan moves is the other
 * half: {@code Runs.start} resolves a {@code DefinitionResolver.Caller} out of
 * the request's own {@code project} and {@code session} and looks the agent up
 * in <b>that caller's</b> registry, so a frame that resolved a different caller
 * than the endpoint answers the same status with a different sentence, from a
 * different set of agents — and a parity test written to match whatever the
 * handler did would have agreed with it.
 *
 * <p>This file was written before {@code agent.run} existed, with the handler
 * sketched inline in the shape a breadth task would write it — {@code POST
 * /v1/agents/&#123;name&#125;/runs} belonged to the breadth plan's Task 3, and
 * giving it a frame here would have been this task doing that one's work.
 * <b>Task 3 has landed, so the sketch is gone and {@link AgentRunHandler} is
 * what is driven below.</b> The file is kept rather than folded into {@code
 * AgentFramesTest} because what it measures is different: that suite compares
 * the two surfaces against each other, and this one compares the real handler
 * against the handler somebody could have written instead — see {@link
 * #a_handler_that_took_the_project_from_the_caller_would_answer_differently},
 * which is the mutation the parity assertion above it needs in order to mean
 * anything, and which no two-surface comparison can contain.
 *
 * <h2>The refusal, not the happy path, for the usual reason</h2>
 *
 * <p>{@code Callers.requireAgent}'s refusal enumerates the agents the caller's
 * <em>project</em> can run. That sentence is therefore a direct readout of
 * which caller was resolved — which is exactly what {@link
 * #a_handler_that_took_the_project_from_the_caller_would_answer_differently}
 * measures, and what a status-only comparison cannot see.
 */
class CallerParityTest {

    private static final String SESSION = "session-9";

    /** The real type, from the one file that names them — not a literal of its
     *  own any more, which is what keeps this test and the registration from
     *  drifting into two spellings of one discriminator. */
    private static final String AGENT_RUN = FrameTypes.AGENT_RUN;

    private DefinitionResolver resolver;
    private ProjectStore projects;
    private Callers callers;
    private Runs runs;
    private Pictures pictures;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        resolver = mock(DefinitionResolver.class);
        projects = mock(ProjectStore.class);
        pictures = mock(Pictures.class);
        // The real Callers and the real Runs over mocked stores: what is under
        // test is which caller each surface resolves, and a mocked Callers
        // would have answered that question for both of them.
        callers = new Callers(resolver, projects, mock(Turn.class), org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class));
        runs = new Runs(callers, mock(JobStore.class), mock(Turn.class));
        mvc = MockMvcBuilders
                .standaloneSetup(new AgentController(mock(JobStore.class), resolver, projects,
                        callers, runs, pictures, null, null, null))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        when(projects.id("payments")).thenReturn(7L);
        // Four registries, because Callers asks two questions of two different
        // callers: the lookup runs against the caller's own set (session
        // included) and the enumeration a refusal offers is always the same
        // caller without its session.
        resolves(7L, SESSION, "scribe");
        resolves(7L, null, "scribe");
        resolves(null, SESSION, "echo");
        resolves(null, null, "echo");
    }

    // -- the parity itself ----------------------------------------------------

    /**
     * Both surfaces refuse an unknown agent in the same words, and those words
     * are the project's own list.
     *
     * <p>The sentence names {@code scribe} — what the project registry serves —
     * and not {@code echo}, which is what a caller with no project would have
     * been offered. That is the whole assertion: the frame resolved the project
     * out of its payload, exactly as the endpoint resolved it out of its body.
     */
    @Test
    void both_surfaces_refuse_an_unknown_agent_from_the_projects_own_registry() throws Exception {
        MockHttpServletResponse http = over("ghost", "payments");
        Outcome outcome = run("ghost", "payments");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
        assertTrue(outcome.said().contains("scribe"),
                "the refusal enumerates what the payload's own project runs: " + outcome.said());
    }

    /**
     * A handler that took its project from the caller rather than the payload
     * answers a different sentence — which is why the surface hands a handler
     * no caller at all.
     *
     * <p><b>This is the mutation that proves the test above discriminates</b>,
     * and it is also the failure {@link Asking} exists to make impossible: the
     * frame surface can only ever supply {@code Caller(null, session)}, so a
     * handler that reached for it would enumerate the boot set — a well-formed
     * 400, in the right shape, listing the wrong agents. Under the shape as
     * built, the handler below cannot be written by accident: there is no
     * project on {@link Asking} to take, and taking it from the payload is the
     * only thing that compiles.
     */
    @Test
    void a_handler_that_took_the_project_from_the_caller_would_answer_differently()
            throws Exception {
        MockHttpServletResponse http = over("ghost", "payments");

        // What the old shape made available: the channel's own caller, whose
        // project is null on every frame ever received.
        Outcome asIfFromTheCaller = ignoringThePayloadsProject("ghost");

        assertEquals(Code.BAD_REQUEST, asIfFromTheCaller.code(),
                "the same status, which is why a status-only parity test would have passed");
        assertTrue(asIfFromTheCaller.said().contains("echo"),
                "and the boot set rather than the project's: " + asIfFromTheCaller.said());
        assertNotEquals(http.getContentAsString(), asIfFromTheCaller.said(),
                "so the two surfaces really would have disagreed, in words a client reads");
    }

    // -- the shape a breadth task would write ---------------------------------

    /**
     * The real handler, over the same two services the endpoint above is built
     * on — no longer a sketch written in this file.
     *
     * <p>It is what the breadth plan's own shape says a handler is: the
     * controller's body minus its {@code ResponseEntity}, reading the payload
     * through {@link Payloads}, taking the agent name — a path variable on HTTP
     * — under its noun, and handing every decision to {@link Runs#start}.
     * Constructing it here rather than reaching for {@link FrameAreas#router()}
     * is deliberate: this test needs the handler over <em>these</em> mocks, and
     * {@code AgentFramesTest} is what proves the production table claims the
     * type at all.
     */
    private FrameHandler handler() {
        return new AgentRunHandler(runs, pictures);
    }

    /** The run as a frame, through a router claiming only this type. */
    private Outcome run(String agent, String project) {
        FrameRouter router = new FrameRouter(Map.of(AGENT_RUN, handler()));
        return router.route(FrameParity.frame(AGENT_RUN, payload(agent, project)),
                FrameParity.ASKING);
    }

    /**
     * The same run through a handler that read its project off the caller the
     * channel supplies instead of off the payload — the divergence this task
     * made impossible, reconstructed by hand so that it can be measured.
     */
    private Outcome ignoringThePayloadsProject(String agent) {
        // The session still comes from the payload, exactly as the endpoint
        // takes it from its body -- only the project is taken from the caller,
        // which is the single field this shape makes impossible to get wrong.
        FrameHandler fromTheCaller = (payload, asking) -> Outcome.ok(runs.start(
                new Runs.Ask(agent, "say hello", null, SESSION, null, null, null, null),
                pictures));
        FrameRouter router = new FrameRouter(Map.of(AGENT_RUN, fromTheCaller));
        return router.route(FrameParity.frame(AGENT_RUN, payload(agent, "payments")),
                FrameParity.ASKING);
    }

    /** The run over HTTP — the same fields, in the body the endpoint takes. */
    private MockHttpServletResponse over(String agent, String project) throws Exception {
        return mvc.perform(post("/v1/agents/" + agent + "/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"say hello\",\"project\":\"" + project
                                + "\",\"session\":\"" + SESSION + "\"}"))
                .andReturn().getResponse();
    }

    /** The frame's payload: the endpoint's body, plus the agent under its noun
     *  because a frame has no path to carry it in. */
    private static String payload(String agent, String project) {
        return "{\"agent\":\"" + agent + "\",\"task\":\"say hello\",\"project\":\"" + project
                + "\",\"session\":\"" + SESSION + "\"}";
    }

    /** A registry for one caller, serving exactly one agent. */
    private void resolves(Long project, String session, String serving) {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.names()).thenReturn(Set.of(serving));
        when(registry.exportedNames()).thenReturn(Set.of(serving));
        when(resolver.forCaller(new DefinitionResolver.Caller(project, session)))
                .thenReturn(registry);
    }
}
