package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.AgentsProperties;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionChecks;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.DefinitionWriter;
import io.aeyer.plowshare.server.agents.Definitions;
import io.aeyer.plowshare.server.agents.Job;
import io.aeyer.plowshare.server.agents.JobAccess;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Limits;
import io.aeyer.plowshare.server.agents.Pictures;
import io.aeyer.plowshare.server.agents.RunLimits;
import io.aeyer.plowshare.server.agents.Runs;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.agents.curator.Curator;
import io.aeyer.plowshare.server.agents.curator.Passes;
import io.aeyer.plowshare.server.api.AgentController;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every endpoint {@code AgentController} answers, driven twice — once over HTTP and once as a frame
 * — off one request, asserting that the two surfaces said the same thing.
 *
 * <h2>Eight of eight, and no ruling to record</h2>
 *
 * <p>Unlike the corpus, this controller has no binary door: every one of its endpoints is JSON in
 * and JSON out, so this area claims all eight and there is no declared gap for a reader counting
 * sections to wonder about.
 *
 * <h2>What this file is really measuring</h2>
 *
 * <p>The rules plan took this controller's fourteen inline throws down into {@code Runs}, {@code
 * Passes}, {@code Definitions}, {@code Limits} and {@code Pictures} before this task ran, and with
 * them <b>four orderings and a silent default</b> — the half of a decision no status and no payload
 * comparison can see. A frame handler gets every one of them by calling the same service method,
 * and the five tests below are what prove it rather than assume it:
 *
 * <ul>
 *   <li>{@link #the_caller_and_the_definition_are_resolved_before_every_other_
 *       check_on_both_surfaces} — {@code Runs.start} names the agent first.
 *   <li>{@link #an_image_this_tier_does_not_hold_is_a_404_on_both_surfaces} — {@code Pictures} asks
 *       {@code find} before {@code dataUri}.
 *   <li>{@link #a_budget_below_what_was_spent_leaves_the_turn_cap_alone_on_both_ surfaces} — {@code
 *       Limits.move} applies the budget first.
 *   <li>{@link #a_global_write_is_resolved_without_restart_on_both_surfaces} — {@code
 *       Definitions.define} builds a view instead of asking.
 *   <li>{@link #a_pass_that_names_no_allowance_takes_the_operators_on_both_ surfaces} — {@code
 *       Passes.start} defaults from configuration.
 * </ul>
 *
 * <p><b>Mocked stores and shared services.</b> What a store does with a row is its own test's
 * subject; what is under test here is which method each surface calls, with what, and what each
 * does with what comes back — including with what is thrown. The services are built once and handed
 * to both the controller and the area, which is the whole claim: one decision, one instance, two
 * readings.
 */
class AgentFramesTest {

  private static final String SESSION = "session-1";

  /**
   * A well-formed image id nothing has ever stored — {@code img_} and thirty-two hexadecimal
   * characters, which is what makes it a miss rather than a malformed name.
   */
  private static final String ABSENT_UID = "img_" + "0".repeat(32);

  private JobStore jobs;
  private ProjectStore projects;
  private Turn turns;
  private Curator curator;
  private DefinitionWriter writer;
  private SessionChannel channel;
  private ImageStore images;
  private AgentsProperties props;
  private AgentRegistry registry;
  private DefinitionResolver resolver;
  private Callers callers;
  private Runs runs;
  private Pictures pictures;
  private Passes passes;
  private Limits limits;
  private Definitions definitions;
  // Held rather than rebuilt per resolver: it is the map a session's follow
  // lives in, and a test asserting on a follow needs the same instance the
  // router below was wired over.
  private final Watchers watchers = new Watchers();
  // What a follow asks whether the conversation exists of: every id here does, unless a test
  // says it does not.
  private ConversationStore conversations;
  private MockMvc mvc;
  private FrameRouter router;

  @BeforeEach
  void setUp(@TempDir Path tmp) {
    jobs = mock(JobStore.class);
    when(jobs.getFor(anyString(), org.mockito.ArgumentMatchers.nullable(String.class)))
        .thenCallRealMethod();
    when(jobs.jobsFor(org.mockito.ArgumentMatchers.nullable(String.class))).thenCallRealMethod();
    projects = mock(ProjectStore.class);
    conversations = mock(ConversationStore.class);
    when(conversations.find(anyString()))
        .thenAnswer(asked -> Optional.of(mock(ConversationRecord.class)));
    turns = mock(Turn.class);
    curator = mock(Curator.class);
    writer = mock(DefinitionWriter.class);
    channel = mock(SessionChannel.class);
    // Every conversation-carrying request here speaks into a runnable project
    // unless a test says otherwise: Callers.callerForConversation asks
    // Turn.homeOf before anything a null Home would NPE inside.
    when(turns.homeOf(anyString())).thenReturn(Home.of("payments"));
    // A real store over a TempDir and not a mock, for the ordering test:
    // what Pictures decides is which of two refusals a caller is told, and
    // only a store that really answers "I do not hold that" can show it.
    images =
        new ImageStore(
            home -> home.isGlobal() ? tmp.resolve("global") : tmp.resolve(home.project()), 4096);
    props = configured();
    registry =
        new AgentRegistry(
            Map.of(
                "scribe", agent("scribe"),
                "figure_reader", seeing("figure_reader"),
                "aristoxenus", bot("aristoxenus")));
    pictures = new Pictures(images);
    passes = new Passes(jobs, curator, props);
    limits = new Limits(jobs);
    // AgentControllerTest's own wiring: a real resolver whose projectExists
    // always says no, so every caller resolves to the boot set. What varies
    // between the two surfaces in this file is never the registry.
    useResolver(
        new DefinitionResolver(
            registry,
            DataLayout.NONE,
            id -> false,
            Set.of(),
            Set.of(),
            channel,
            session -> true,
            DefinitionChecks.NONE));
  }

  /**
   * Rebuilds every service that is built over {@link #resolver} — {@link #callers}, {@link #runs},
   * {@link #definitions}, {@link #mvc} and {@link #router} — exactly as {@link #setUp} does, so a
   * test that swaps in a different resolver (a spy, or one that actually reads a {@link
   * DataLayout}) drives both surfaces off it rather than off the fixture built in {@code setUp}.
   */
  private void useResolver(DefinitionResolver given) {
    resolver = given;
    // The nine services built once and shared, exactly as the container
    // shares its singletons -- one Callers behind both Runs and Definitions.
    callers =
        new Callers(
            resolver,
            projects,
            turns,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class));
    runs = new Runs(callers, jobs, turns);
    definitions = new Definitions(writer, resolver, projects, callers);

    // FrameParity.endpointsOf and not a bare standaloneSetup: this file
    // compares rendered bodies, and the bare harness's converters are not
    // the ones a deployed server has. That class's javadoc has the case.
    mvc =
        FrameParity.endpointsOf(
            new AgentController(
                jobs, resolver, projects, callers, runs, pictures, passes, limits, definitions));
    // Through the area rather than through a literal map: this is the
    // registration the handlers are reached by in production, and a test
    // that built its own map would pass for a type nobody wired.
    router =
        new FrameRoutingConfig()
            .frameRouter(
                List.of(
                    new AgentFrames(
                        jobs,
                        resolver,
                        projects,
                        callers,
                        runs,
                        pictures,
                        passes,
                        limits,
                        definitions,
                        watchers,
                        new Conversations(conversations, mock(TurnStore.class)))));
  }

  // --- agent.run -----------------------------------------------------------

  /**
   * Both surfaces submit one run, with the same task in the same home, and both answer 202 with the
   * same handle.
   */
  @Test
  void both_surfaces_start_the_same_run() throws Exception {
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_1");

    MockHttpServletResponse http =
        posted(
            "/v1/agents/scribe/runs",
            """
                {"task": "say hello", "session": "session-1"}""");
    Outcome outcome =
        route(
            FrameTypes.AGENT_RUN,
            """
                {"agent": "scribe", "task": "say hello", "session": "session-1"}""");

    // ACCEPTED and not OK: what comes back is a handle to poll, and an OK
    // would tell a client a run of minutes had already finished.
    assertEquals(Code.ACCEPTED, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(jobs, times(2))
        .submit(
            registry.get("scribe"),
            "say hello",
            Home.global(),
            SESSION,
            null,
            List.of(),
            true,
            null);
  }

  /** A turn spoken over either surface is the signed-in account's own words. */
  @Test
  void both_surfaces_speak_into_a_conversation_as_the_account_that_asked() throws Exception {
    when(turns.speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any()))
        .thenReturn("job_3");

    MockHttpServletResponse http =
        mvc.perform(
                post("/v1/agents/scribe/runs")
                    .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "enzo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"task\": \"go\", \"conversation\": \"cnv_1\"}"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        router.route(
            FrameParity.frame(
                FrameTypes.AGENT_RUN,
                "{\"agent\": \"scribe\", \"task\": \"go\", \"conversation\": \"cnv_1\"}"),
            new Asking("session-1", "enzo"));

    assertEquals(Code.ACCEPTED, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(turns, times(2))
        .speak(
            eq("cnv_1"),
            eq(registry.get("scribe")),
            eq("go"),
            isNull(),
            isNull(),
            eq(Speaker.person("enzo")),
            any());
  }

  /**
   * A run naming a tier and a cap is submitted into that tier under that cap, on both surfaces.
   *
   * <p><b>The run above names neither</b>, so the {@code Home.global()} and the null cap it
   * verifies are exactly what a handler that never read {@code project} or {@code maxTurns} would
   * submit. The two refusals below that do name a project never reach {@code submit} — they are
   * answered about the agent — so nothing in this file could tell the two handlers apart, and a
   * frame that dropped its payload's project would run an agent against another tier's lending and
   * another tier's memories.
   *
   * <p>The cap is captured rather than matched: {@link TurnCap} has no {@code equals}, so {@code
   * eq(TurnCap.of(3))} would compare identities and fail against two perfectly correct caps.
   */
  @Test
  void both_surfaces_submit_a_run_into_the_tier_and_cap_it_names() throws Exception {
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_2");

    MockHttpServletResponse http =
        posted(
            "/v1/agents/scribe/runs",
            """
                {"task": "say hello", "session": "session-1", "project": "payments",
                 "maxTurns": 3}""");
    Outcome outcome =
        route(
            FrameTypes.AGENT_RUN,
            """
                {"agent": "scribe", "task": "say hello", "session": "session-1",
                 "project": "payments", "maxTurns": 3}""");

    assertEquals(Code.ACCEPTED, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    ArgumentCaptor<TurnCap> cap = ArgumentCaptor.captor();
    verify(jobs, times(2))
        .submit(
            eq(registry.get("scribe")),
            eq("say hello"),
            eq(Home.of("payments")),
            eq(SESSION),
            cap.capture(),
            eq(List.of()),
            eq(true),
            isNull());
    for (TurnCap given : cap.getAllValues()) {
      assertEquals(3, given.turns(), "both surfaces capped the run at what it asked for");
    }
  }

  /**
   * An agent the caller's own resolved set does not hold is the same refusal in the same words, and
   * those words enumerate what this server runs.
   */
  @Test
  void a_run_naming_an_agent_that_does_not_exist_is_the_same_refusal_on_both_surfaces()
      throws Exception {
    MockHttpServletResponse http =
        posted(
            "/v1/agents/ghost/runs",
            """
                {"task": "say hello"}""");
    Outcome outcome =
        route(
            FrameTypes.AGENT_RUN,
            """
                {"agent": "ghost", "task": "say hello"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertTrue(
        outcome.said().contains("scribe"),
        "the refusal enumerates the agents that exist: " + outcome.said());
    verify(jobs, never())
        .submit(any(AgentDefinition.class), any(), any(), any(), any(), any(), anyBoolean(), any());
  }

  /**
   * <b>The first of the four moved orderings.</b> A body that is wrong in two ways at once — an
   * agent nothing serves, and a conversation beside a project, which {@code Runs.start} refuses
   * outright — is answered about the agent, because the caller and the definition are resolved
   * before any other field is read.
   *
   * <p>Both refusals are 400, so a status-only comparison passes either way; the sentence is the
   * readout. A handler that restated the conversation-and-project check itself, ahead of the
   * lookup, would answer the other one and look perfectly correct from the outside.
   */
  @Test
  void the_caller_and_the_definition_are_resolved_before_every_other_check_on_both_surfaces()
      throws Exception {
    MockHttpServletResponse http =
        posted(
            "/v1/agents/ghost/runs",
            """
                {"task": "say hello", "conversation": "cnv_1", "project": "payments"}""");
    Outcome outcome =
        route(
            FrameTypes.AGENT_RUN,
            """
                {"agent": "ghost", "task": "say hello", "conversation": "cnv_1",
                 "project": "payments"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertTrue(
        outcome.said().contains("ghost"),
        "the unknown agent is what the caller is told about, not the two fields it also"
            + " got wrong: "
            + outcome.said());
  }

  /**
   * <b>The second moved ordering.</b> A UID that is well formed and names no image this tier holds
   * is a {@code NOT_FOUND} naming it, and not the {@code BAD_REQUEST} that asking for absent bytes
   * raises — {@code Pictures} asks {@code ImageStore.find} before it calls {@code dataUri}, and
   * only the order decides which of two true things the caller is told.
   */
  @Test
  void an_image_this_tier_does_not_hold_is_a_404_on_both_surfaces() throws Exception {
    MockHttpServletResponse http =
        posted(
            "/v1/agents/figure_reader/runs",
            """
                {"task": "what is this", "images": ["%s"]}"""
                .formatted(ABSENT_UID));
    Outcome outcome =
        route(
            FrameTypes.AGENT_RUN,
            """
                {"agent": "figure_reader", "task": "what is this",
                 "images": ["%s"]}"""
                .formatted(ABSENT_UID));

    assertEquals(Code.NOT_FOUND, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(jobs, never())
        .submit(any(AgentDefinition.class), any(), any(), any(), any(), any(), anyBoolean(), any());
  }

  /**
   * An agent that never declared it can see is refused a picture in the same words on both
   * surfaces.
   */
  @Test
  void an_agent_that_never_declared_vision_is_refused_on_both_surfaces() throws Exception {
    String uid = images.store(Home.global(), "red.png", PNG).id();

    MockHttpServletResponse http =
        posted(
            "/v1/agents/scribe/runs",
            """
                {"task": "what is this", "images": ["%s"]}"""
                .formatted(uid));
    Outcome outcome =
        route(
            FrameTypes.AGENT_RUN,
            """
                {"agent": "scribe", "task": "what is this",
                 "images": ["%s"]}"""
                .formatted(uid));

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertTrue(outcome.said().contains("vision"), outcome.said());
  }

  /**
   * A frame naming no agent at all is refused — a request only this surface can receive, since a
   * URL naming none is a different URL.
   */
  @Test
  void a_run_naming_no_agent_is_the_frame_surfaces_own_refusal() {
    Outcome outcome =
        route(
            FrameTypes.AGENT_RUN,
            """
                {"task": "say hello"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    assertTrue(outcome.said().contains("agent.run needs its payload to say which"), outcome.said());
  }

  // --- agent.curate --------------------------------------------------------

  /**
   * Both surfaces start one curator pass over one project, and both answer 202 with the same
   * handle.
   */
  @Test
  void both_surfaces_start_the_same_curator_pass() throws Exception {
    when(jobs.submit(eq(Curator.BY), any(Home.class), any())).thenReturn("job_2");

    MockHttpServletResponse http =
        posted(
            "/v1/curate",
            """
                {"project": "payments", "maxModelCalls": 12}""");
    Outcome outcome =
        route(
            FrameTypes.AGENT_CURATE,
            """
                {"project": "payments", "maxModelCalls": 12}""");

    assertEquals(Code.ACCEPTED, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(jobs, times(2)).submit(eq(Curator.BY), eq(Home.of("payments")), any());
  }

  /**
   * <b>The moved default.</b> A body that names no allowance takes the operator's configured one,
   * on both surfaces.
   *
   * <p>A default is the place two surfaces drift in silence: it is not in the request, so nothing
   * in a payload comparison would show a handler that reached for a number of its own. The
   * fixture's {@code 91} is deliberately not what {@code application.yml} ships — a hard-coded
   * default would pass against that one.
   *
   * <p>The submitted closure is run here rather than on a thread, because {@code JobStore.submit}
   * is mocked and the {@code Budget} each surface built is reachable no other way.
   */
  @Test
  void a_pass_that_names_no_allowance_takes_the_operators_on_both_surfaces() throws Exception {
    when(jobs.submit(eq(Curator.BY), any(Home.class), any())).thenReturn("job_2");

    posted(
        "/v1/curate",
        """
                {"project": "payments"}""");
    route(
        FrameTypes.AGENT_CURATE,
        """
                {"project": "payments"}""");

    runSubmittedPasses();
    // Captured rather than matched: Budget has no equals, so eq(Budget.of(91))
    // would compare identities and fail against two perfectly correct
    // allowances.
    ArgumentCaptor<Budget> allowance = ArgumentCaptor.captor();
    verify(curator, times(2)).pass(eq("payments"), allowance.capture(), any());
    for (Budget given : allowance.getAllValues()) {
      assertEquals(
          props.getCuratorBudget(),
          given.limit(),
          "both surfaces asked with the operator's own default");
    }
  }

  /**
   * A pass naming no project is the same refusal on both surfaces, and there is no global pass for
   * either to fall back to.
   */
  @Test
  void a_pass_with_no_project_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http = posted("/v1/curate", "{}");
    Outcome outcome = route(FrameTypes.AGENT_CURATE, "{}");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(jobs, never()).submit(anyString(), any(Home.class), any());
  }

  // --- agent.define --------------------------------------------------------

  /**
   * A name that did not exist is {@code CREATED} on both surfaces — 201, and the resolved view
   * beside it.
   */
  @Test
  void both_surfaces_answer_created_for_a_name_that_did_not_exist() throws Exception {
    when(projects.id("payments")).thenReturn(7L);
    wrote("scribe", DefinitionWriter.Disposition.CREATED);

    String asked =
        """
                {"project": "payments", "name": "scribe", "text": "---\\nname: scribe\\n---"}""";
    MockHttpServletResponse http = posted("/v1/agents", asked);
    Outcome outcome = route(FrameTypes.AGENT_DEFINE, asked);

    // CREATED and not OK, which is the one dynamic success code on this
    // surface: the endpoint answers 201 here and 200 for a replacement.
    assertEquals(Code.CREATED, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
  }

  /**
   * A name that was there and was asked to be replaced is {@code OK} on both surfaces — the same
   * branch, read off the same {@code Disposition}.
   */
  @Test
  void both_surfaces_answer_ok_for_a_name_that_was_replaced() throws Exception {
    when(projects.id("payments")).thenReturn(7L);
    wrote("scribe", DefinitionWriter.Disposition.REPLACED);

    String asked =
        """
                {"project": "payments", "name": "scribe", "text": "---\\nname: scribe\\n---",
                 "overwrite": true}""";
    MockHttpServletResponse http = posted("/v1/agents", asked);
    Outcome outcome = route(FrameTypes.AGENT_DEFINE, asked);

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    // The flag is verified and not merely sent: the disposition this test
    // reads the 200 off is the mock's, so a handler that passed false
    // would answer REPLACED all the same -- and would be refusing, in
    // production, the replacement the caller explicitly asked for.
    verify(writer, times(2)).write(7L, "scribe", "---\nname: scribe\n---", true);
  }

  /** Global authoring returns the current read-back without a restart on both surfaces. */
  @Test
  void a_global_write_is_resolved_without_restart_on_both_surfaces() throws Exception {
    wrote("scribe", DefinitionWriter.Disposition.CREATED);

    String asked =
        """
                {"name": "scribe", "text": "---\\nname: scribe\\n---"}""";
    MockHttpServletResponse http = posted("/v1/agents", asked);
    Outcome outcome = route(FrameTypes.AGENT_DEFINE, asked);

    assertEquals(Code.CREATED, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    assertTrue(
        http.getContentAsString().contains("\"restartRequired\":false"),
        "a global write answers without a restart: " + http.getContentAsString());
    assertTrue(
        http.getContentAsString().contains("\"served\":true"),
        "and the view says so machine-readably: " + http.getContentAsString());
  }

  /**
   * A project this server holds no row for is refused before a byte is written, in the same words
   * on both surfaces.
   */
  @Test
  void a_definition_naming_an_unknown_project_is_the_same_refusal_on_both_surfaces()
      throws Exception {
    when(projects.id("ghost-project")).thenReturn(null);

    String asked =
        """
                {"project": "ghost-project", "name": "scribe", "text": "x"}""";
    MockHttpServletResponse http = posted("/v1/agents", asked);
    Outcome outcome = route(FrameTypes.AGENT_DEFINE, asked);

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(writer, never()).write(any(), anyString(), anyString(), anyBoolean());
  }

  /**
   * A body naming no {@code name} is a 400 before the writer is called, on both surfaces — the
   * refusal that used to be an opaque 500.
   */
  @Test
  void a_definition_naming_no_name_is_the_same_refusal_on_both_surfaces() throws Exception {
    String asked =
        """
                {"project": "payments", "text": "x"}""";
    when(projects.id("payments")).thenReturn(7L);

    MockHttpServletResponse http = posted("/v1/agents", asked);
    Outcome outcome = route(FrameTypes.AGENT_DEFINE, asked);

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(writer, never()).write(any(), anyString(), anyString(), anyBoolean());
  }

  // --- agent.list ----------------------------------------------------------

  /** Both surfaces list the same agents, in the same order, with the same declarations on each. */
  @Test
  void both_surfaces_list_the_same_agents() throws Exception {
    MockHttpServletResponse http = read("/v1/agents");
    Outcome outcome = route(FrameTypes.AGENT_LIST, "{}");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    assertTrue(http.getContentAsString().contains("scribe"), http.getContentAsString());
  }

  @Test
  void command_catalog_is_grant_filtered_on_both_surfaces_and_refreshes_source(@TempDir Path tmp)
      throws Exception {
    var data = new DataLayout(tmp);
    Path source =
        Files.createDirectories(data.skillsFor(null).resolve("review")).resolve("SKILL.md");
    Files.writeString(
        source,
        "---\nname: review\ndescription: Review work\nmode: DIRECT\n---\nPrivate skill instructions.");
    Path broken =
        Files.createDirectories(data.skillsFor(null).resolve("broken")).resolve("SKILL.md");
    Files.writeString(broken, "invalid package");
    registry =
        new AgentRegistry(
            Map.of(
                "scribe",
                agent("scribe").withSkills(List.of("review", "broken")),
                "figure_reader",
                seeing("figure_reader"),
                "aristoxenus",
                bot("aristoxenus").withSkills(List.of("*"))));
    useResolver(
        new DefinitionResolver(
            registry,
            DataLayout.NONE,
            id -> false,
            Set.of(),
            Set.of(),
            channel,
            session -> true,
            DefinitionChecks.NONE));
    var packages =
        new io.aeyer.plowshare.server.agents.SkillResolver(
            data, channel, id -> false, session -> false, (project, session) -> false);
    var procedures =
        new org.springframework.beans.factory.support.StaticListableBeanFactory()
            .getBeanProvider(io.aeyer.plowshare.server.agents.OrchestrationResolver.class);
    var controller =
        new AgentController(
            jobs, resolver, projects, callers, runs, pictures, passes, limits, definitions);
    controller.useCommandCatalog(packages, procedures);
    mvc = FrameParity.endpointsOf(controller);
    var area =
        new AgentFrames(
            jobs,
            resolver,
            projects,
            callers,
            runs,
            pictures,
            passes,
            limits,
            definitions,
            watchers,
            new Conversations(conversations, mock(TurnStore.class)));
    area.useCommandCatalog(packages, procedures);
    router = new FrameRoutingConfig().frameRouter(List.of(area));

    MockHttpServletResponse http = read("/v1/agents");
    Outcome outcome = route(FrameTypes.AGENT_LIST, "{}");
    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    JsonNode rows = FrameJson.answering().readTree(http.getContentAsString());
    for (JsonNode row : rows) {
      String name = row.path("name").asText();
      assertEquals(name.equals("figure_reader") ? 0 : 1, row.path("commands").size());
      if (!name.equals("figure_reader")) {
        assertEquals("/skill:review", row.path("commands").get(0).path("command").asText());
        assertEquals(name, row.path("commands").get(0).path("executor").asText());
        assertTrue(row.path("withheld").toString().contains("broken"));
      }
    }
    assertFalse(http.getContentAsString().contains("Private skill instructions"));

    Files.writeString(source, "invalid replacement");
    http = read("/v1/agents");
    outcome = route(FrameTypes.AGENT_LIST, "{}");
    FrameParity.assertSameAnswer(http, outcome);
    for (JsonNode row : FrameJson.answering().readTree(http.getContentAsString())) {
      assertEquals(0, row.path("commands").size());
    }
  }

  /**
   * Both surfaces say which of the rows is a bot, and <b>both are read for the field itself</b>
   * rather than only against each other.
   *
   * <h2>Why the two map assertions are not one</h2>
   *
   * <p>{@link FrameParity#assertSameAnswer} compares two rendered bodies, and it passes when they
   * are <em>equally</em> wrong: a build where {@code AgentView} never gained {@code bot} satisfies
   * it perfectly, because neither surface carries the field and the two strings still match. That
   * failure was met on this branch, so the flag is read out of each surface's own bytes and
   * compared to a literal — the endpoint's body first, then the frame's payload serialised with the
   * mapper the channel really writes with.
   *
   * <h2>Why three rows and not one</h2>
   *
   * <p>A test whose only bot is {@code true} cannot tell a view that reads the definition from one
   * that writes {@code true} into every row, which is the same shape as the bugs this repository
   * has already found twice — a field whose only test sends its default. {@code aristoxenus}
   * declares the flag and the two agents do not, so the listing has to have read something per row
   * to answer this.
   */
  @Test
  void both_surfaces_say_which_of_the_listed_definitions_is_a_bot() throws Exception {
    MockHttpServletResponse http = read("/v1/agents");
    Outcome outcome = route(FrameTypes.AGENT_LIST, "{}");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    assertEquals(
        Map.of("aristoxenus", true, "figure_reader", false, "scribe", false),
        botFlagsIn(http.getContentAsString(StandardCharsets.UTF_8)),
        "GET /v1/agents really carries bot, per row, read off the definition");
    assertEquals(
        Map.of("aristoxenus", true, "figure_reader", false, "scribe", false),
        botFlagsIn(FrameJson.answering().writeValueAsString(outcome.payload())),
        "and so does agent.list, asserted on its own bytes: two surfaces agreeing"
            + " that neither carries the field is what a comparison alone passes");
  }

  /**
   * Every row's name against the {@code bot} it declared, read out of rendered JSON.
   *
   * <p><b>Out of the bytes and not out of an {@code AgentView}</b>, which is the point of the test
   * above it: a Java caller can see a record component that Jackson never wrote, so reading the
   * record back would pass on a field no client is ever sent. The field is required to be present
   * and to be a JSON boolean — a build that sent the string {@code "true"} would otherwise read
   * here as {@code false} and look like a different bug.
   */
  private static Map<String, Boolean> botFlagsIn(String json) throws Exception {
    Map<String, Boolean> flags = new LinkedHashMap<>();
    for (JsonNode row : FrameJson.answering().readTree(json)) {
      assertTrue(row.hasNonNull("bot"), "no bot on this row: " + row);
      assertTrue(row.get("bot").isBoolean(), "bot is not a boolean on this row: " + row);
      flags.put(row.get("name").asText(), row.get("bot").booleanValue());
    }
    return flags;
  }

  /**
   * Both surfaces carry each row's own description, read off the wire and not off an {@code
   * AgentView}, for {@link #botFlagsIn}'s exact reason: a Java caller can see a record component
   * Jackson never wrote.
   *
   * <h2>Measured while red</h2>
   *
   * <p>On a build without the field {@link FrameParity#assertSameAnswer} passed here too, the same
   * way it passed for {@code bot} — both surfaces omitted the field equally and the two bodies
   * still matched. So this reads {@code description} out of each surface's own rendered JSON and
   * requires it present and non-empty, rather than trusting the comparison alone.
   *
   * <h2>Why {@code aristoxenus} is not {@code "a fixture"}</h2>
   *
   * <p>{@code scribe} and {@code figure_reader} share that literal, which a view that hardcoded a
   * string into every row would also send back. {@code bot}'s fixture carries the shipped bot's own
   * opening sentence instead, so a view that is not reading the definition per row fails on that
   * one entry even though it might coincidentally match the other two.
   */
  @Test
  void both_surfaces_carry_each_rows_own_description() throws Exception {
    MockHttpServletResponse http = read("/v1/agents");
    Outcome outcome = route(FrameTypes.AGENT_LIST, "{}");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    Map<String, String> fromEndpoint =
        descriptionsIn(http.getContentAsString(StandardCharsets.UTF_8));
    Map<String, String> fromFrame =
        descriptionsIn(FrameJson.answering().writeValueAsString(outcome.payload()));

    assertEquals(
        fromEndpoint, fromFrame, "the two surfaces read the same descriptions off their own bytes");
    assertTrue(
        fromEndpoint.get("aristoxenus").contains("broke with the Pythagoreans"),
        "GET /v1/agents carries aristoxenus's own description: " + fromEndpoint);
    assertTrue(
        fromFrame.get("aristoxenus").contains("broke with the Pythagoreans"),
        "agent.list carries aristoxenus's own description: " + fromFrame);
  }

  /**
   * Every row's name against the {@code description} it declared, present and non-empty, read out
   * of rendered JSON.
   */
  private static Map<String, String> descriptionsIn(String json) throws Exception {
    Map<String, String> descriptions = new LinkedHashMap<>();
    for (JsonNode row : FrameJson.answering().readTree(json)) {
      assertTrue(row.hasNonNull("description"), "no description on this row: " + row);
      String said = row.get("description").asText();
      assertTrue(!said.isEmpty(), "description is empty on this row: " + row);
      descriptions.put(row.get("name").asText(), said);
    }
    return descriptions;
  }

  /**
   * The frame lists with the session that asked; the endpoint has none to give.
   *
   * <p><b>This reversed a deliberate rule, and the reversal is the point.</b> The test it replaces
   * asserted neither surface read a session, because passing one down "would enumerate a live
   * client's own {@code .plowshare/} — a new capability". It is the capability the terminal client
   * needs: who answers in a project a person has rooted includes the bots that person keeps beside
   * their files, and the socket asking is that very session. No other session's directory is
   * reachable, because the id is the asker's own.
   */
  @Test
  void the_frame_lists_with_its_own_session_and_the_endpoint_with_none() throws Exception {
    when(projects.id("payments")).thenReturn(7L);
    DefinitionResolver watched = spy(resolver);
    FrameRouter listing =
        new FrameRoutingConfig()
            .frameRouter(
                List.of(
                    new AgentFrames(
                        jobs,
                        watched,
                        projects,
                        callers,
                        runs,
                        pictures,
                        passes,
                        limits,
                        definitions,
                        new Watchers(),
                        new Conversations(conversations, mock(TurnStore.class)))));

    Outcome outcome =
        listing.route(
            FrameParity.frame(
                FrameTypes.AGENT_LIST,
                """
                {"project": "payments"}"""),
            FrameParity.ASKING);

    assertEquals(Code.OK, outcome.code());
    verify(watched)
        .refreshForCaller(new DefinitionResolver.Caller(7L, FrameParity.ASKING.sessionId()));
    verify(watched).defaultBot(new DefinitionResolver.Caller(7L, FrameParity.ASKING.sessionId()));
  }

  /**
   * The row a tier's {@code bots/default} names is the one row marked, on both surfaces — and a
   * default naming nothing is a row of its own that says so, rather than a flag on nobody.
   */
  @Test
  void both_surfaces_mark_the_default_bot_and_say_so_when_it_names_nothing(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    Files.createDirectories(layout.botsFor(null));
    Files.writeString(
        layout.botsFor(null).resolve(DefinitionResolver.DEFAULT_FILE), "aristoxenus\n");
    useResolver(
        new DefinitionResolver(
            registry,
            layout,
            id -> false,
            Set.of(),
            Set.of(),
            channel,
            session -> true,
            DefinitionChecks.NONE));

    MockHttpServletResponse http = read("/v1/agents");
    Outcome outcome = route(FrameTypes.AGENT_LIST, "{}");

    FrameParity.assertSameAnswer(http, outcome);
    JsonNode rows = FrameJson.answering().readTree(http.getContentAsString());
    List<String> marked = new ArrayList<>();
    for (JsonNode row : rows) {
      if (row.get("preferred").asBoolean()) {
        marked.add(row.get("name").asText());
      }
    }
    assertEquals(List.of("aristoxenus"), marked);

    Files.writeString(layout.botsFor(null).resolve(DefinitionResolver.DEFAULT_FILE), "sophron\n");
    JsonNode missing = null;
    for (JsonNode row : FrameJson.answering().readTree(read("/v1/agents").getContentAsString())) {
      if (row.get("name").asText().equals("sophron")) {
        missing = row;
      }
    }
    assertNotNull(missing, "a default naming nothing left no row saying so");
    assertTrue(missing.get("preferred").asBoolean());
    assertFalse(missing.get("served").asBoolean());
    assertTrue(missing.get("withheld").get(0).asText().contains("default"));
  }

  // --- job.list ------------------------------------------------------------

  /** Both surfaces list every job this process is holding, in one order. */
  @Test
  void both_surfaces_list_the_same_jobs() throws Exception {
    when(jobs.jobs()).thenReturn(List.of(JobAccess.newJob("job_1", "scribe")));

    MockHttpServletResponse http = read("/v1/jobs");
    Outcome outcome = route(FrameTypes.JOB_LIST, "{}");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(jobs, times(2)).jobs();
  }

  // --- job.status ----------------------------------------------------------

  /** Both surfaces report the same job in the same shape. */
  @Test
  void both_surfaces_report_the_same_job() throws Exception {
    when(jobs.get("job_1")).thenReturn(JobAccess.newJob("job_1", "scribe"));

    MockHttpServletResponse http = read("/v1/jobs/job_1");
    Outcome outcome =
        route(
            FrameTypes.JOB_STATUS,
            """
                {"job": "job_1"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
  }

  /**
   * A job this process never held is the same 404 in the same words — {@code JobStore.get}'s own
   * refusal, reached by both.
   */
  @Test
  void a_job_nobody_holds_is_the_same_404_on_both_surfaces() throws Exception {
    misses("job_ghost");

    MockHttpServletResponse http = read("/v1/jobs/job_ghost");
    Outcome outcome =
        route(
            FrameTypes.JOB_STATUS,
            """
                {"job": "job_ghost"}""");

    assertEquals(Code.NOT_FOUND, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  // --- job.cancel ----------------------------------------------------------

  /**
   * Both surfaces ask the same run to stop, and both report it still {@code RUNNING} with the
   * request recorded — a cancel is honoured at the next turn boundary and is not a kill.
   */
  @Test
  void both_surfaces_ask_the_same_run_to_stop() throws Exception {
    Job job = JobAccess.newJob("job_1", "scribe");
    when(jobs.get("job_1")).thenReturn(job);
    when(jobs.cancel("job_1")).thenAnswer(call -> JobAccess.requestCancel(job));

    MockHttpServletResponse http = posted("/v1/jobs/job_1/cancel", null);
    Outcome outcome =
        route(
            FrameTypes.JOB_CANCEL,
            """
                {"job": "job_1"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(jobs, times(2)).cancel("job_1");
  }

  /** Cancelling a job nobody holds is the same 404 on both surfaces, and nothing is cancelled. */
  @Test
  void cancelling_a_job_nobody_holds_is_the_same_404_on_both_surfaces() throws Exception {
    misses("job_ghost");

    MockHttpServletResponse http = posted("/v1/jobs/job_ghost/cancel", null);
    Outcome outcome =
        route(
            FrameTypes.JOB_CANCEL,
            """
                {"job": "job_ghost"}""");

    assertEquals(Code.NOT_FOUND, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(jobs, never()).cancel(anyString());
  }

  // --- job.limits ----------------------------------------------------------

  /**
   * Both surfaces move the same ceilings on the same live run, and both read the changed job back.
   */
  @Test
  void both_surfaces_move_the_same_ceilings() throws Exception {
    RunLimits held = new RunLimits(Budget.of(10), TurnCap.of(3));
    when(jobs.get("job_1")).thenReturn(JobAccess.newJob("job_1", "scribe", held));

    String asked =
        """
                {"maxTurns": 40, "maxModelCalls": 20}""";
    MockHttpServletResponse http = posted("/v1/jobs/job_1/limits", asked);
    Outcome outcome =
        route(
            FrameTypes.JOB_LIMITS,
            """
                {"job": "job_1", "maxTurns": 40, "maxModelCalls": 20}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    assertEquals(40, held.cap().turns(), "the cap really moved");
    assertEquals(20, held.budget().limit(), "and so did the budget");
  }

  /**
   * <b>The third moved ordering.</b> A body naming both a budget the run has already overspent and
   * a turn cap is refused about the budget, and the cap <em>does not move</em> — {@code
   * Limits.move} applies the budget first precisely so a refused half cannot leave the other half
   * quietly applied.
   *
   * <p>The refusal is identical whichever order the two are tried in, so the status and the
   * sentence cannot show this. The cap's own value afterwards is the only readout, and it is
   * asserted after <b>both</b> surfaces have run: a handler that moved the cap first would have
   * left it at 40.
   */
  @Test
  void a_budget_below_what_was_spent_leaves_the_turn_cap_alone_on_both_surfaces() throws Exception {
    Budget budget = Budget.of(10);
    for (int spent = 0; spent < 5; spent++) {
      budget.trySpend();
    }
    RunLimits held = new RunLimits(budget, TurnCap.of(3));
    when(jobs.get("job_1")).thenReturn(JobAccess.newJob("job_1", "scribe", held));

    MockHttpServletResponse http =
        posted(
            "/v1/jobs/job_1/limits",
            """
                {"maxTurns": 40, "maxModelCalls": 2}""");
    Outcome outcome =
        route(
            FrameTypes.JOB_LIMITS,
            """
                {"job": "job_1", "maxTurns": 40, "maxModelCalls": 2}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertEquals(
        3,
        held.cap().turns(),
        "the budget is applied first, so a refused budget leaves the cap where it was --"
            + " on both surfaces");
    assertEquals(10, held.budget().limit(), "and the budget itself is unchanged");
  }

  /**
   * A body naming no limit at all is the same refusal on both surfaces: a 200 having changed
   * nothing reads as a limit that moved.
   */
  @Test
  void a_limits_body_naming_nothing_is_the_same_refusal_on_both_surfaces() throws Exception {
    when(jobs.get("job_1"))
        .thenReturn(
            JobAccess.newJob("job_1", "scribe", new RunLimits(Budget.of(10), TurnCap.of(3))));

    MockHttpServletResponse http = posted("/v1/jobs/job_1/limits", "{}");
    Outcome outcome =
        route(
            FrameTypes.JOB_LIMITS,
            """
                {"job": "job_1"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  /**
   * A job that is not one agent's run has no limits here to move, and both surfaces say which kind
   * of job it is rather than pretending.
   */
  @Test
  void moving_the_limits_of_a_pass_is_the_same_refusal_on_both_surfaces() throws Exception {
    when(jobs.get("job_2")).thenReturn(JobAccess.newJob("job_2", Curator.BY));

    MockHttpServletResponse http =
        posted(
            "/v1/jobs/job_2/limits",
            """
                {"maxTurns": 40}""");
    Outcome outcome =
        route(
            FrameTypes.JOB_LIMITS,
            """
                {"job": "job_2", "maxTurns": 40}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  // --- conversation.follow -------------------------------------------------------------

  @Test
  void following_a_conversation_is_recorded_for_the_asking_session_and_replaces_the_last() {
    Outcome first = route(FrameTypes.CONVERSATION_FOLLOW, "{\"conversation\":\"cnv_1\"}");
    Outcome second = route(FrameTypes.CONVERSATION_FOLLOW, "{\"conversation\":\"cnv_2\"}");

    assertEquals(Code.OK, first.code());
    assertEquals(Code.OK, second.code());
    assertEquals(
        Set.of(),
        watchers.followersOf("cnv_1"),
        "the legacy single-log payload replaces every earlier follow");
    assertEquals(Set.of(FrameParity.ASKING.sessionId()), watchers.followersOf("cnv_2"));
  }

  @Test
  void a_multi_view_client_replaces_its_complete_set_without_changing_other_sessions() {
    String own = FrameParity.ASKING.sessionId();
    watchers.follows("another-session", "cnv_1");
    Outcome first =
        route(
            FrameTypes.CONVERSATION_FOLLOW,
            "{\"conversations\":[\"cnv_1\",\"cnv_2\",\"cnv_1\"],\"futureField\":true}");
    assertEquals(Code.OK, first.code());
    assertEquals(Set.of(own, "another-session"), watchers.followersOf("cnv_1"));
    assertEquals(Set.of(own), watchers.followersOf("cnv_2"));

    Outcome second =
        route(FrameTypes.CONVERSATION_FOLLOW, "{\"conversations\":[\"cnv_2\",\"cnv_3\"]}");
    assertEquals(Code.OK, second.code());
    assertEquals(Set.of("another-session"), watchers.followersOf("cnv_1"));
    assertEquals(Set.of(own), watchers.followersOf("cnv_2"));
    assertEquals(Set.of(own), watchers.followersOf("cnv_3"));

    assertEquals(Code.OK, route(FrameTypes.CONVERSATION_FOLLOW, "{\"conversations\":[]}").code());
    assertEquals(Set.of("another-session"), watchers.followersOf("cnv_1"));
    assertEquals(Set.of(), watchers.followersOf("cnv_2"));
    assertEquals(Set.of(), watchers.followersOf("cnv_3"));
  }

  @Test
  void a_legacy_follow_replaces_a_multi_view_set_too() {
    route(FrameTypes.CONVERSATION_FOLLOW, "{\"conversations\":[\"cnv_1\",\"cnv_2\"]}");
    assertEquals(
        Code.OK, route(FrameTypes.CONVERSATION_FOLLOW, "{\"conversation\":\"cnv_3\"}").code());
    assertEquals(Set.of(), watchers.followersOf("cnv_1"));
    assertEquals(Set.of(), watchers.followersOf("cnv_2"));
    assertEquals(Set.of(FrameParity.ASKING.sessionId()), watchers.followersOf("cnv_3"));
  }

  @Test
  void a_missing_log_refuses_the_whole_set_and_preserves_every_existing_follow() {
    route(FrameTypes.CONVERSATION_FOLLOW, "{\"conversations\":[\"cnv_1\",\"cnv_2\"]}");
    when(conversations.find("cnv_nope")).thenReturn(Optional.empty());

    Outcome refused =
        route(FrameTypes.CONVERSATION_FOLLOW, "{\"conversations\":[\"cnv_3\",\"cnv_nope\"]}");

    assertEquals(Code.NOT_FOUND, refused.code());
    assertEquals(
        "no conversation has the id cnv_nope, so there is no log to follow", refused.said());
    assertEquals(Set.of(FrameParity.ASKING.sessionId()), watchers.followersOf("cnv_1"));
    assertEquals(Set.of(FrameParity.ASKING.sessionId()), watchers.followersOf("cnv_2"));
    assertEquals(Set.of(), watchers.followersOf("cnv_3"));
    assertEquals(Set.of(), watchers.followersOf("cnv_nope"));
  }

  @Test
  void malformed_or_ambiguous_follow_sets_preserve_the_existing_subscription() {
    watchers.follows(FrameParity.ASKING.sessionId(), "cnv_1");
    for (String payload :
        List.of(
            "{\"conversations\":null}",
            "{\"conversations\":\"cnv_2\"}",
            "{\"conversations\":[\"cnv_2\",null]}",
            "{\"conversations\":[\"cnv_2\",12]}",
            "{\"conversations\":[true]}",
            "{\"conversations\":[{}]}",
            "{\"conversations\":[\"cnv_2\",\" \"]}",
            "{\"conversation\":\"cnv_2\",\"conversations\":[]}")) {
      assertEquals(
          Code.BAD_REQUEST, route(FrameTypes.CONVERSATION_FOLLOW, payload).code(), payload);
      assertEquals(Set.of(FrameParity.ASKING.sessionId()), watchers.followersOf("cnv_1"));
      assertEquals(Set.of(), watchers.followersOf("cnv_2"));
    }
  }

  @Test
  void a_multi_log_follow_still_requires_a_listener_session() {
    Outcome refused =
        router.route(
            FrameParity.frame(FrameTypes.CONVERSATION_FOLLOW, "{\"conversations\":[\"cnv_1\"]}"),
            new Asking(null, "enzo"));
    assertEquals(Code.BAD_REQUEST, refused.code());
    assertEquals(Set.of(), watchers.followersOf("cnv_1"));
  }

  /**
   * A conversation nothing opened is refused as its siblings refuse it — the trajectory a follower
   * reads, and the chat — and not followed: a client restoring a stale id would otherwise wait on
   * pushes for a log that will never grow, told nothing was wrong.
   */
  @Test
  void a_follow_of_a_conversation_nothing_opened_is_refused_in_its_siblings_words() {
    when(conversations.find("cnv_nope")).thenReturn(Optional.empty());

    Outcome refused = route(FrameTypes.CONVERSATION_FOLLOW, "{\"conversation\":\"cnv_nope\"}");

    assertEquals(Code.NOT_FOUND, refused.code());
    assertEquals(
        "no conversation has the id cnv_nope, so there is no log to follow", refused.said());
    assertEquals(Set.of(), watchers.followersOf("cnv_nope"));
  }

  @Test
  void a_follow_that_names_no_conversation_is_refused_and_follows_nothing() {
    Outcome refused = route(FrameTypes.CONVERSATION_FOLLOW, "{}");

    assertEquals(Code.BAD_REQUEST, refused.code());
    assertEquals(Set.of(), watchers.followersOf("cnv_1"));
  }

  // --- the surface's own two rules, for all eight types ---------------------

  /**
   * Every type this area claims ignores a field this build has never heard of — spec §3.2's "the
   * payload is tolerant", asserted once per type.
   */
  @Test
  void every_type_in_this_area_ignores_a_field_this_build_has_never_heard_of() throws Exception {
    for (Map.Entry<String, String> each : representativePayloads().entrySet()) {
      FrameParity.assertUnknownFieldsAreIgnored(router, each.getKey(), each.getValue());
    }
  }

  /**
   * The production routing table really does claim all eight of this controller's types.
   *
   * <p>Built from every {@link FrameArea} Spring would collect: an unregistered type is answered
   * with a perfectly well-formed {@code NOT_FOUND}, so a handler nobody wired looks from the
   * outside exactly like a handler nobody wrote.
   */
  @Test
  void the_production_routing_table_claims_every_agent_and_job_type() {
    FrameRouter wired = FrameAreas.router();

    for (String type : representativePayloads().keySet()) {
      assertTrue(
          wired.types().contains(type), type + " is not in the production table: " + wired.types());
    }
  }

  /** One payload per type this area adds, good enough to reach the handler. */
  private static Map<String, String> representativePayloads() {
    return Map.ofEntries(
        Map.entry(FrameTypes.AGENT_RUN, "{\"agent\":\"scribe\",\"task\":\"say hello\"}"),
        Map.entry(FrameTypes.AGENT_CURATE, "{\"project\":\"payments\"}"),
        Map.entry(FrameTypes.AGENT_DEFINE, "{\"name\":\"scribe\",\"text\":\"x\"}"),
        Map.entry(FrameTypes.AGENT_LIST, "{}"),
        Map.entry(FrameTypes.JOB_LIST, "{}"),
        Map.entry(FrameTypes.JOB_STATUS, "{\"job\":\"job_1\"}"),
        Map.entry(FrameTypes.JOB_CANCEL, "{\"job\":\"job_1\"}"),
        Map.entry(FrameTypes.JOB_LIMITS, "{\"job\":\"job_1\",\"maxTurns\":40}"),
        Map.entry(FrameTypes.CONVERSATION_FOLLOW, "{\"conversation\":\"cnv_1\"}"));
  }

  // --- driving the two surfaces off one request ----------------------------

  private Outcome route(String type, String payload) {
    return router.route(FrameParity.frame(type, payload), FrameParity.ASKING);
  }

  private MockHttpServletResponse read(String path) throws Exception {
    return mvc.perform(get(path)).andReturn().getResponse();
  }

  private MockHttpServletResponse posted(String path, String body) throws Exception {
    if (body == null) {
      return mvc.perform(post(path)).andReturn().getResponse();
    }
    return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn()
        .getResponse();
  }

  /** The store holds no job under {@code id}, and says so in its own words. */
  private void misses(String id) {
    when(jobs.get(id))
        .thenThrow(new NotFoundFault("no job called '" + id + "'; this process knows []"));
  }

  /**
   * The writer lands {@code name} on disk with the disposition a caller's status is read off.
   * Mocked rather than real, because two surfaces writing the same name to one directory would make
   * the second one a replacement and the parity comparison a comparison of two branches.
   */
  private void wrote(String name, DefinitionWriter.Disposition disposition) {
    when(writer.write(any(), eq(name), anyString(), anyBoolean()))
        .thenReturn(
            new DefinitionWriter.Written(
                Path.of("bots", name + ".md"), "global/bots", disposition));
  }

  /**
   * Both closures the job store was handed, run here, so an assertion about the allowance each
   * surface closed over can be made without a thread.
   */
  private void runSubmittedPasses() {
    ArgumentCaptor<Function<BooleanSupplier, io.aeyer.plowshare.server.agents.Outcome>> work =
        ArgumentCaptor.captor();
    verify(jobs, times(2)).submit(eq(Curator.BY), any(Home.class), work.capture());
    for (Function<BooleanSupplier, io.aeyer.plowshare.server.agents.Outcome> each :
        work.getAllValues()) {
      each.apply(() -> false);
    }
  }

  private static final byte[] PNG = {
    (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4
  };

  private static AgentDefinition agent(String name) {
    return new AgentDefinition(
        name,
        "a fixture",
        "fast",
        List.of(),
        List.of(),
        List.of(),
        2,
        4,
        "You do one thing.",
        true,
        true);
  }

  /**
   * The same fixture, declaring that it is somebody a person talks to rather than a role something
   * invokes.
   *
   * <p>{@code delegable} is false, which is the shipped bot's own pair and not decoration: a
   * person's bot becoming somebody else's sub-agent is the category error {@code interlocutor}
   * already refuses. It also keeps this fixture out of every other test in the file, since nothing
   * can name it as a callee.
   *
   * <p><b>The description is the shipped bot's own opening sentence, and not {@code "a
   * fixture"}</b>, so that {@code both_surfaces_carry_each_rows_own_description} has something
   * distinctive to find — the same reason {@code agent} and {@code seeing} keep the shared literal
   * rather than each getting their own.
   */
  private static AgentDefinition bot(String name) {
    return new AgentDefinition(
        name,
        "Aristoxenus of Tarentum, who broke with the"
            + " Pythagoreans over whether a harmony is judged by the ear that hears it or"
            + " by the elegance of the ratio behind it, and took the ear's side.",
        "fast",
        Sampling.Intent.DEFAULT,
        Sampling.NONE,
        List.of(),
        List.of(),
        List.of(),
        2,
        4,
        "You are somebody.",
        true,
        false,
        false,
        true);
  }

  /**
   * The same fixture, declaring that it needs a model that sees — which is the half of the picture
   * rule this endpoint actually checks.
   */
  private static AgentDefinition seeing(String name) {
    return new AgentDefinition(
        name,
        "a fixture",
        "fast",
        Sampling.Intent.DEFAULT,
        Sampling.NONE,
        List.of(),
        List.of(),
        List.of(),
        2,
        4,
        "You look at things.",
        true,
        true,
        true);
  }

  /**
   * The allowance an operator configured, deliberately not the number {@code application.yml}
   * ships: a hard-coded default would pass against that one.
   */
  private static AgentsProperties configured() {
    AgentsProperties properties = new AgentsProperties();
    properties.setCuratorBudget(91);
    return properties;
  }
}
