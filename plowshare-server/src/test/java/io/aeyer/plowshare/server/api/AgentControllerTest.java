package io.aeyer.plowshare.server.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.AgentsProperties;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionChecks;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.DefinitionWriter;
import io.aeyer.plowshare.server.agents.Definitions;
import io.aeyer.plowshare.server.agents.FakeFiles;
import io.aeyer.plowshare.server.agents.Job;
import io.aeyer.plowshare.server.agents.JobAccess;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Limits;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.agents.Pictures;
import io.aeyer.plowshare.server.agents.RunLimits;
import io.aeyer.plowshare.server.agents.Runs;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.agents.curator.Curator;
import io.aeyer.plowshare.server.agents.curator.Passes;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The job surface, endpoint by endpoint, against a mocked {@link JobStore}.
 *
 * <p>A pure MVC test in {@code MemoryControllerTest}'s shape: no Spring context, no Postgres, no
 * model. What a run <em>does</em> is {@code JobRuntimeTest}'s and {@code CuratorTest}'s subject and
 * is measured there against real fixtures; this class's job is narrower and is the half a caller
 * sees — does each endpoint call the right thing with the right arguments, and does each refusal
 * become the right status with a message somebody can act on.
 *
 * <p>The registry is the real {@link AgentRegistry} and not a mock, on every test that has one. It
 * validates the set it is given exactly as the loader does, so a fixture graph that could not exist
 * at boot cannot be built here either — which is what keeps the agents this file lists to a console
 * the same shape as the agents a deployment has.
 *
 * <p><b>{@link DefinitionResolver} is real too, built over that registry as its boot set.</b>
 * {@code projects} is a mock whose {@code id(String)} answers {@code null} for every project name a
 * request names unless a test stubs it — Mockito's own default for an unstubbed method returning an
 * object — and {@link DefinitionResolver#forCaller} reads a {@code null} project id as "no project
 * tier to read" and falls straight back to the boot set. Most of these tests are not about a
 * project's own {@code agents/} or {@code bots/} shadowing the boot set, so most requests here
 * resolve to exactly {@code registry}, the same registry a test built with {@link
 * #wire(AgentRegistry)}. The ones that <em>are</em> about it — "per-caller scoping" below — stub
 * {@code projects.id} and build their own {@link DefinitionResolver} over a real {@link
 * DataLayout}, through {@link #wire(DefinitionResolver)}.
 */
class AgentControllerTest {

  private JobStore jobs;
  private Curator curator;
  private ProjectStore projects;
  private AgentRegistry registry;
  private ImageStore images;
  private Turn turns;
  private SessionChannel channel;
  private MockMvc mvc;

  @BeforeEach
  void setUp(@TempDir Path tmp) {
    jobs = mock(JobStore.class);
    // The adapter now calls the account-aware store doors. Policy behaviour is tested
    // with a real store in the authenticated information integration tests.
    when(jobs.jobsFor(org.mockito.ArgumentMatchers.isNull())).thenAnswer(invocation -> jobs.jobs());
    when(jobs.getFor(anyString(), org.mockito.ArgumentMatchers.isNull()))
        .thenAnswer(invocation -> jobs.get(invocation.getArgument(0)));
    curator = mock(Curator.class);
    projects = mock(ProjectStore.class);
    turns = mock(Turn.class);
    // Every conversation-carrying request in this file speaks into the
    // global tier unless a test says otherwise -- a stub and not
    // Mockito's own unstubbed default, since AgentController.run calls
    // Turn.homeOf before it calls anything a null Home would NPE inside.
    when(turns.homeOf(anyString())).thenReturn(Home.global());
    // A real store over a TempDir and not a mock: what the image half of
    // this endpoint does is turn a UID into bytes, and a mock would assert
    // that a method was called rather than that a picture reached the run.
    // The tier decides the directory the way ImageDirectories.under does,
    // with the name standing in for the id -- the translation is the
    // archive's and is tested against a database there.
    images =
        new ImageStore(
            home -> home.isGlobal() ? tmp.resolve("global") : tmp.resolve(home.project()), 4096);
    registry =
        new AgentRegistry(
            Map.of(
                "promotion_judge", agent("promotion_judge"),
                "scribe", agent("scribe")));
    wire(registry);
  }

  /**
   * Rebuilt per test so that the empty-registry case is the same controller with one thing
   * different, rather than a second harness.
   */
  private void wire(AgentRegistry bootSet) {
    channel = mock(SessionChannel.class);
    // DataLayout.NONE, an always-false projectExists and a mocked channel:
    // none of the three is ever consulted by most tests in this file,
    // because every Caller they build resolves with a null project id
    // (this class's own javadoc says why) and DefinitionResolver.forCaller
    // returns the boot set on that alone, before any of the three would be
    // asked anything. Kept as a field regardless, so
    // a_session_query_parameter_on_the_listing_is_not_read_at_all can
    // prove GET /v1/agents never touches it.
    wire(
        new DefinitionResolver(
            bootSet,
            DataLayout.NONE,
            id -> false,
            Set.of(),
            Set.of(),
            channel,
            session -> true,
            DefinitionChecks.NONE));
  }

  /**
   * {@link #wire(AgentRegistry)}'s one line further in, for the tests that need a resolver of their
   * own -- a real project tier over a real {@code DataLayout}, or a {@code projectExists} predicate
   * that actually says yes. Every other test in this file goes through the other overload.
   */
  private void wire(DefinitionResolver resolver) {
    ObjectMapper json =
        new ObjectMapper()
            .findAndRegisterModules()
            // Matching Spring Boot's auto-configured mapper rather than a
            // plain one, which has this ON. MemoryControllerTest was
            // silently wrong about it until Task 10; the correction belongs
            // in every standalone harness or the next one repeats it.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    // The six services wired exactly as the container wires them -- one
    // Callers, shared by Runs and Definitions, which is what the scanned
    // singletons are. The controller no longer builds any of them.
    Callers callers =
        new Callers(
            resolver,
            projects,
            turns,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AgentController(
                    jobs,
                    resolver,
                    projects,
                    callers,
                    new Runs(callers, jobs, turns),
                    new Pictures(images),
                    new Passes(jobs, curator, new AgentsProperties()),
                    new Limits(jobs),
                    new Definitions(mock(DefinitionWriter.class), resolver, projects, callers)))
            .setControllerAdvice(new ApiExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
            .build();
  }

  // --- POST /v1/agents/{name}/runs -------------------------------------------

  @Test
  void running_an_agent_answers_with_the_job_id_at_once() throws Exception {
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_000001");

    mvc.perform(
            post("/v1/agents/promotion_judge/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "rule on mem_000001", "project": "payments"}"""))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value("job_000001"))
        .andExpect(jsonPath("$.agent").value("promotion_judge"));

    // The trailing null is the run-level turn cap: this body names none,
    // so what bounds the run is the level above -- the agent's own.
    verify(jobs)
        .submit(
            registry.get("promotion_judge"),
            "rule on mem_000001",
            Home.of("payments"),
            null,
            null,
            List.of(),
            true,
            null);
  }

  /** No project means global, matching {@code Home}'s own rule and every other endpoint's. */
  @Test
  void a_run_with_no_project_answers_from_the_global_tier() throws Exception {
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_000001");

    mvc.perform(
            post("/v1/agents/scribe/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "judge this"}"""))
        .andExpect(status().isAccepted());

    verify(jobs)
        .submit(
            any(AgentDefinition.class),
            eq("judge this"),
            eq(Home.global()),
            isNull(),
            isNull(),
            eq(List.of()),
            eq(true),
            isNull());
  }

  // --- images: an agent names one, the server attaches it ---------------------

  private static final byte[] PNG = {
    (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4
  };

  /**
   * The whole feature, end to end at this layer: a UID in the body becomes bytes on the run's
   * opening message.
   *
   * <p><b>Resolved before a job id is handed back</b>, which is the reason this happens here at all
   * — {@code DocumentController}'s split, drawn in the same place. What the captured argument
   * proves is that the run was handed a {@code data:} URI and never a UID: nothing inside the turn
   * loop can name an image, so nothing inside it can reach one.
   */
  @Test
  void a_run_naming_an_image_is_handed_its_bytes_and_never_its_uid() throws Exception {
    wire(new AgentRegistry(Map.of("figure_reader", seeing("figure_reader"))));
    String uid = images.store(Home.of("payments"), "red.png", PNG).id();
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_000001");

    mvc.perform(
            post("/v1/agents/figure_reader/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "what shape is this", "project": "payments",
                                 "images": ["%s"]}"""
                        .formatted(uid)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value("job_000001"));

    ArgumentCaptor<List<Content.Image>> shown = ArgumentCaptor.captor();
    verify(jobs)
        .submit(
            any(AgentDefinition.class),
            eq("what shape is this"),
            eq(Home.of("payments")),
            isNull(),
            isNull(),
            shown.capture(),
            eq(true),
            isNull());
    assertEquals(1, shown.getValue().size());
    assertEquals(uid, shown.getValue().get(0).uid());
    assertEquals(
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(PNG),
        shown.getValue().get(0).dataUri());
  }

  /**
   * An agent that has not declared vision is not shown a picture, and this is the second half of
   * the capability check.
   *
   * <p>{@code AgentsConfig} refuses at boot an agent that declares vision and names a model no pool
   * declares as seeing. Without this, that boot check is a check on a claim nobody has to make: any
   * agent could be handed an image, the model would answer that it saw nothing, and the
   * configuration would look correct from every side.
   */
  @Test
  void an_agent_that_never_declared_vision_is_not_shown_a_picture() throws Exception {
    String uid = images.store(Home.global(), "red.png", PNG).id();

    mvc.perform(
            post("/v1/agents/scribe/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "what is this", "images": ["%s"]}"""
                        .formatted(uid)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("vision")));

    verify(jobs, never())
        .submit(any(AgentDefinition.class), any(), any(), any(), any(), any(), anyBoolean(), any());
  }

  /**
   * A UID this tier does not hold is a 404 the submitter reads now, rather than a job they poll to
   * discover.
   */
  @Test
  void a_uid_this_tier_does_not_hold_is_refused_before_a_job_exists() throws Exception {
    wire(new AgentRegistry(Map.of("figure_reader", seeing("figure_reader"))));

    mvc.perform(
            post("/v1/agents/figure_reader/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "what is this", "images": ["img_%s"]}"""
                        .formatted("0".repeat(32))))
        .andExpect(status().isNotFound());

    verify(jobs, never())
        .submit(any(AgentDefinition.class), any(), any(), any(), any(), any(), anyBoolean(), any());
  }

  /**
   * An image belongs to the tier it was uploaded to, so a run in one project cannot be shown
   * another's -- which is the only thing keeping the store from being one shared bucket with a
   * naming convention over it.
   */
  @Test
  void a_run_cannot_be_shown_another_projects_image() throws Exception {
    wire(new AgentRegistry(Map.of("figure_reader", seeing("figure_reader"))));
    String uid = images.store(Home.of("payments"), "red.png", PNG).id();

    mvc.perform(
            post("/v1/agents/figure_reader/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "what is this", "project": "ledger",
                                 "images": ["%s"]}"""
                        .formatted(uid)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value(containsString("ledger")));
  }

  /**
   * A string that is not an id at all is the caller's mistake and not a miss, so it is 400 and not
   * 404 -- and it never reaches the filesystem.
   */
  @Test
  void a_string_that_is_not_an_image_id_is_a_bad_request() throws Exception {
    wire(new AgentRegistry(Map.of("figure_reader", seeing("figure_reader"))));

    mvc.perform(
            post("/v1/agents/figure_reader/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "what is this", "images": ["../../etc/passwd"]}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("img_")));
  }

  /**
   * An utterance in a conversation cannot carry images, and is refused rather than having them
   * dropped.
   *
   * <p>A picture reaches one turn and is not recorded — an entry carries what was said, and a
   * base64 payload is not something anybody said — so a conversation given one would have a history
   * that cannot be replayed. That is a real limitation with a design behind fixing it, and until
   * then the honest thing is to refuse rather than to accept and quietly do less.
   */
  @Test
  void an_utterance_in_a_conversation_cannot_carry_images() throws Exception {
    wire(new AgentRegistry(Map.of("figure_reader", seeing("figure_reader"))));
    String uid = images.store(Home.global(), "red.png", PNG).id();

    mvc.perform(
            post("/v1/agents/figure_reader/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "what is this", "conversation": "cnv_1",
                                 "images": ["%s"]}"""
                        .formatted(uid)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("replayed")));
  }

  /**
   * A body naming both a conversation and a project is refused rather than having one of them
   * silently preferred.
   *
   * <p>Two answers to one question — which home this run answers from — and taking either quietly
   * is how somebody comes to believe a run reached a project it never touched. A conversation is
   * opened in a home and its turns run in that home, so {@code project} has nothing left to decide.
   *
   * <p><b>This refusal had no test.</b> Every other body in this file that names a conversation
   * leaves {@code project} out entirely, so the branch was live and unpinned. Written against the
   * handler as it stands, so that the message and the status are held by something before the
   * decision moves anywhere.
   */
  @Test
  void a_run_naming_both_a_conversation_and_a_project_is_refused() throws Exception {
    mvc.perform(
            post("/v1/agents/scribe/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "go", "conversation": "cnv_1",
                                 "project": "payments"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail").value(containsString("names both a conversation and a project")))
        .andExpect(
            jsonPath("$.detail").value(containsString("leave 'project' out of an utterance")));

    verify(turns, never())
        .speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any());
    verify(jobs, never())
        .submit(any(AgentDefinition.class), any(), any(), any(), any(), any(), anyBoolean(), any());
  }

  /** And an ordinary run names none, which is every run this server made before images existed. */
  @Test
  void a_run_that_names_no_images_is_shown_nothing() throws Exception {
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_000001");

    mvc.perform(
            post("/v1/agents/scribe/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "judge this"}"""))
        .andExpect(status().isAccepted());

    verify(jobs)
        .submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            isNull(),
            isNull(),
            eq(List.of()),
            eq(true),
            isNull());
  }

  /**
   * An unknown agent is 400 with the list, not 404.
   *
   * <p>The caller is a model choosing a name, so the list <em>is</em> the correction and it can act
   * on it on its next turn. A 404 would be true and useless.
   */
  @Test
  void an_unknown_agent_is_refused_with_the_agents_that_exist() throws Exception {
    mvc.perform(
            post("/v1/agents/librarian/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "go"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("librarian")))
        .andExpect(jsonPath("$.detail").value(containsString("promotion_judge")))
        .andExpect(jsonPath("$.detail").value(containsString("scribe")));

    verify(jobs, never())
        .submit(any(AgentDefinition.class), any(), any(), any(), any(), any(), anyBoolean(), any());
  }

  /**
   * A server that serves nothing at all answers the same 400 an unknown name gets on an ordinary
   * server — naming the agents that exist, which is the empty set here — and not a distinct
   * refusal.
   *
   * <p><b>Was a distinguishable state until task 10, and is not one any more.</b> {@link
   * DefinitionResolver#forCaller} never answers {@code null} — the boot set, seeded from the
   * classpath at minimum, is always there in production — so {@code AgentController} has no "no
   * registry at all" branch left to ask {@code RequestedAgent} for. A deployment that serves
   * nothing is now indistinguishable, at this door, from one whose resolved registry happens to
   * hold nothing; both answer with an empty list in the message.
   */
  @Test
  void a_server_with_no_agents_names_the_empty_set_like_any_unknown_name() throws Exception {
    wire(new AgentRegistry(Map.of()));

    mvc.perform(
            post("/v1/agents/scribe/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "go"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("no agent called 'scribe'")))
        .andExpect(jsonPath("$.detail").value(containsString("[]")));
  }

  /**
   * Refused here rather than several seconds later on a virtual thread: by then a job id has been
   * handed back and a caller is polling something that was never going to run.
   */
  @Test
  void a_run_with_no_task_is_refused_before_a_job_id_is_minted() throws Exception {
    mvc.perform(
            post("/v1/agents/scribe/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "   ", "project": "payments"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("no task to do")));

    verify(jobs, never())
        .submit(any(AgentDefinition.class), any(), any(), any(), any(), any(), anyBoolean(), any());
  }

  /**
   * A blank project is refused rather than folded into global — {@code Home.of}'s rule, because an
   * empty string is what an unset field sends and reading it as "global" would widen a run's reach
   * by accident.
   */
  @Test
  void a_run_naming_a_blank_project_is_refused_rather_than_read_as_global() throws Exception {
    mvc.perform(
            post("/v1/agents/scribe/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "go", "project": "  "}"""))
        .andExpect(status().isBadRequest());

    verify(jobs, never())
        .submit(any(AgentDefinition.class), any(), any(), any(), any(), any(), anyBoolean(), any());
  }

  // --- GET /v1/agents ----------------------------------------------------------

  /**
   * The picker's list: what can be run, what each may do, and nothing it was told to be.
   *
   * <p><b>The prompt assertion is the reason this test reads the body rather than only its
   * fields.</b> A system prompt is an agent's instructions and not its interface: a console has no
   * use for it, and a prompt shipped to a browser is one XSS away from being read by whatever
   * influenced the text on the page. Asserting that the body lacks the <em>word</em> "prompt" would
   * be the weak version of this — it passes on a body that carries the prompt text under any other
   * field name — so what is asserted is a distinctive fragment of each fixture's prompt. The two
   * fixtures carry different ones, so a view that leaked either is caught.
   *
   * <p>The two agents also differ in every field that is disclosed — different tools, one
   * delegating and one not, and different grants — so a view that built one row and repeated it, or
   * that read the caller's grants for the callee, cannot pass. {@code code_reviewer} sorts first,
   * which is what makes the assertion on {@code $[0]} a statement about the order and not about the
   * order the fixtures happened to be written in.
   */
  @Test
  void listing_agents_answers_what_each_may_do_and_never_its_prompt() throws Exception {
    wire(
        new AgentRegistry(
            Map.of(
                "interlocutor",
                    agent(
                        "interlocutor",
                        List.of("file_read", AgentRegistry.AGENT_RUN),
                        List.of("code_reviewer"),
                        List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)),
                        "Speak plainly, and never invent a citation you have not read."),
                "code_reviewer",
                    agent(
                        "code_reviewer",
                        List.of("file_read"),
                        List.of(),
                        List.of(new Grant(Scope.WORKSPACE, Mode.READ)),
                        "Name the defect and the line, and refuse to guess at intent."))));

    String body =
        mvc.perform(get("/v1/agents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].name").value("code_reviewer"))
            .andExpect(jsonPath("$[0].tools").value(hasItem("file_read")))
            .andExpect(jsonPath("$[0].calls.length()").value(0))
            .andExpect(jsonPath("$[0].scopes").value(hasItem("workspace:read")))
            .andExpect(jsonPath("$[1].name").value("interlocutor"))
            .andExpect(jsonPath("$[1].tools").value(hasItem("agent_run")))
            .andExpect(jsonPath("$[1].calls").value(hasItem("code_reviewer")))
            // The caller's own grant, and not the callee's: a view that read
            // the wrong definition would put workspace:read here and satisfy
            // every other assertion in this test.
            .andExpect(jsonPath("$[1].scopes").value(hasItem("workspace:write")))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertFalse(
        body.contains("never invent a citation"),
        "the agent list must not carry a system prompt; it carried the interlocutor's");
    assertFalse(
        body.contains("refuse to guess at intent"),
        "the agent list must not carry a system prompt; it carried the reviewer's");
  }

  /**
   * A server that serves nothing lists none, and does not refuse to answer.
   *
   * <p>The class javadoc's own promise, on the endpoint where it is easiest to get wrong: {@link
   * DefinitionResolver#forCaller} never answers {@code null} — an empty boot set is a legal,
   * running server whose resolved registry simply has nothing in it. <b>A 400 here would be the
   * {@code POST} arm's answer given to a question nobody asked wrongly</b> — naming an agent that
   * does not exist is the caller's mistake and is told so, but asking what a server runs is not,
   * and a console that met a refusal on its first screen would report the deployment broken.
   */
  @Test
  void a_server_with_no_agents_lists_none_rather_than_refusing_to_answer() throws Exception {
    wire(new AgentRegistry(Map.of()));

    mvc.perform(get("/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /**
   * A private agent is refused by name, and not by pretending it is absent.
   *
   * <p><b>The decision, made on purpose rather than fallen into.</b> Identical refusals for "not
   * exported" and "no such agent" would give a caller no enumeration oracle — it could not learn
   * which private agents exist by probing names. That is worth something on a server whose agents
   * belong to strangers, and it is worth nothing here: <b>this is a single-user system and the
   * operator owns every definition in the directory</b>, so the only person who can probe it is the
   * person who wrote the files. What the vague answer costs is the case that actually happens — an
   * operator who has just written an agent, run it, and is told it does not exist while looking
   * straight at the file. So the message names the key and the value that would make it runnable.
   *
   * <p>It also says what is still true of the agent, because "not exported" read as "broken" is the
   * next wrong conclusion: a private agent is fully callable through another definition's {@code
   * calls:}, which is the whole reason the axis exists.
   */
  @Test
  void a_private_agent_is_refused_by_name_and_not_as_a_missing_one() throws Exception {
    wire(
        new AgentRegistry(
            Map.of(
                "interlocutor", agent("interlocutor"),
                "scribe", privately("scribe"))));

    mvc.perform(
            post("/v1/agents/scribe/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "go"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("scribe")))
        .andExpect(jsonPath("$.detail").value(containsString("exported: true")))
        .andExpect(jsonPath("$.detail").value(containsString("calls")));

    // The other refusal, from the same request shape, so the two are
    // measured to differ rather than asserted to.
    mvc.perform(
            post("/v1/agents/librarian/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "go"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("no agent called")));

    verify(jobs, never())
        .submit(any(AgentDefinition.class), any(), any(), any(), any(), any(), anyBoolean(), any());
  }

  /**
   * The list a caller may choose from is the list of what it may run. A private agent in the picker
   * is an entry that answers 400 when clicked.
   */
  @Test
  void listing_agents_offers_only_the_exported_ones() throws Exception {
    wire(
        new AgentRegistry(
            Map.of(
                "interlocutor", agent("interlocutor"),
                "scribe", privately("scribe"))));

    mvc.perform(get("/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("interlocutor"));
  }

  /**
   * A disabled agent is on the list, marked, with the reason — never merely absent.
   *
   * <p><b>This is the price of the disable rule and the only thing that makes it a trade rather
   * than a loss.</b> Abort-on-fail's value was that it could not be missed; a server that starts
   * without an agent has spent that, and what it buys back is a row on the one screen a person
   * looks at, saying what is not there and why. A silently missing agent is worse than a failed
   * boot, because it is met at first use with no explanation at all.
   *
   * <p><b>{@code exported} does not filter these out</b>, and that is deliberate: an agent whose
   * file could not be parsed has no {@code exported} to read, so filtering would mean the least
   * readable definitions were the ones that vanished. The operator owns every file in the
   * directory, so there is nothing here they are not entitled to see.
   */
  @Test
  void a_disabled_agent_is_listed_with_its_reason_and_not_merely_absent() throws Exception {
    wire(
        new AgentRegistry(
            new AgentRegistry.Loaded(
                Map.of("interlocutor", agent("interlocutor")),
                Map.of(
                    "bot",
                    "the agent file bot.md lists the tool 'memory_grep', which this"
                        + " runtime does not bind"),
                Map.of())));

    mvc.perform(get("/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].name").value("bot"))
        .andExpect(jsonPath("$[0].served").value(false))
        .andExpect(jsonPath("$[0].withheld[0]").value(containsString("memory_grep")))
        .andExpect(jsonPath("$[1].name").value("interlocutor"))
        .andExpect(jsonPath("$[1].served").value(true))
        .andExpect(jsonPath("$[1].withheld.length()").value(0))
        // The specifier as the file writes it for the served row, and
        // none for the row whose file this server could not read.
        .andExpect(jsonPath("$[0].model").value(nullValue()))
        .andExpect(jsonPath("$[1].model").value("fast"));
  }

  /**
   * The listing says which rows are bots, and a row nobody could parse says no rather than saying
   * nothing.
   *
   * <p><b>Both values are exercised on purpose.</b> A test whose only row is {@code bot: true}
   * cannot tell a view that reads the definition from one that writes {@code true} into every row,
   * so the agent is asserted as explicitly false — the flag is a fact about each definition and the
   * listing has to have read it per row to answer this.
   *
   * <p><b>The disabled row is the case with a decision in it.</b> A file that could not be parsed
   * has no {@code bot:} to read, exactly as it has no {@code exported}, and {@link
   * AgentView#disabled} answers false for the same reason its three declaration lists are empty:
   * what this server may honestly say about that definition is its name and why it is not there.
   * False here is "this server is not offering you a bot", which is true, and not a claim about
   * what the file says.
   */
  @Test
  void the_listing_says_which_rows_are_bots_and_a_row_nobody_parsed_says_no() throws Exception {
    wire(
        new AgentRegistry(
            new AgentRegistry.Loaded(
                Map.of(
                    "aristoxenus",
                    character("aristoxenus"),
                    "code_reviewer",
                    agent("code_reviewer")),
                Map.of("zz_unreadable", "the file zz_unreadable.md has no closing frontmatter"),
                Map.of())));

    mvc.perform(get("/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].name").value("aristoxenus"))
        .andExpect(jsonPath("$[0].bot").value(true))
        .andExpect(jsonPath("$[1].name").value("code_reviewer"))
        .andExpect(jsonPath("$[1].bot").value(false))
        .andExpect(jsonPath("$[2].name").value("zz_unreadable"))
        .andExpect(jsonPath("$[2].served").value(false))
        .andExpect(jsonPath("$[2].bot").value(false));
  }

  /**
   * The listing carries each row's own description, and a row nobody could parse has none to carry.
   *
   * <p><b>Two distinct sentences, not one repeated.</b> Every other fixture in this file shares the
   * literal {@code "a fixture"}, which a view that hardcoded that string into every row would also
   * satisfy — the same shape of bug {@code
   * listing_agents_answers_what_each_may_do_and_never_its_prompt} guards against for the prompt. So
   * each row here is given its own sentence, and the listing has to have read it off the right
   * definition to answer either one correctly.
   *
   * <p><b>The disabled row's description is empty, not omitted.</b> {@link AgentView#disabled}'s
   * own javadoc gives the reason: a file that could not be parsed has no {@code description:} to
   * read, and empty is the honestly sayable value.
   */
  @Test
  void the_listing_carries_each_rows_own_description() throws Exception {
    wire(
        new AgentRegistry(
            new AgentRegistry.Loaded(
                Map.of(
                    "aristoxenus",
                    described(
                        "aristoxenus",
                        "who broke with the Pythagoreans over the ear and the ratio"),
                    "code_reviewer",
                    described("code_reviewer", "reads a diff and says what is wrong with it")),
                Map.of("zz_unreadable", "the file zz_unreadable.md has no closing frontmatter"),
                Map.of())));

    mvc.perform(get("/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].name").value("aristoxenus"))
        .andExpect(
            jsonPath("$[0].description").value(containsString("broke with the Pythagoreans")))
        .andExpect(jsonPath("$[1].name").value("code_reviewer"))
        .andExpect(jsonPath("$[1].description").value(containsString("reads a diff")))
        .andExpect(jsonPath("$[2].name").value("zz_unreadable"))
        .andExpect(jsonPath("$[2].served").value(false))
        .andExpect(jsonPath("$[2].description").value(""));
  }

  /**
   * An agent whose delegation was withheld is served, and says which route went. The two states
   * have to be distinguishable on one screen, or "one route removed" reads as "this agent is
   * broken".
   */
  @Test
  void an_agent_with_a_withheld_edge_is_served_and_says_which_edge() throws Exception {
    wire(
        new AgentRegistry(
            new AgentRegistry.Loaded(
                Map.of("interlocutor", agent("interlocutor")),
                Map.of(),
                Map.of(
                    "interlocutor -> code_reviewer",
                    "the agent 'interlocutor' calls 'code_reviewer', which is granted"
                        + " workspace:write"))));

    mvc.perform(get("/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("interlocutor"))
        .andExpect(jsonPath("$[0].served").value(true))
        .andExpect(
            jsonPath("$[0].withheld[0]").value(containsString("interlocutor -> code_reviewer")));
  }

  /**
   * An agent whose grant held an item it could never have used is served, and says which item went.
   *
   * <p><b>The same field as a withheld route, and deliberately.</b> Both are "this server took
   * something away from an agent that is running", which is one subject on a screen; what must stay
   * distinguishable is that from a <em>disablement</em>, and {@code served} is what does that. A
   * third list would make the console choose between three renderings of one sentence.
   *
   * <p>The row is selectable and can be the default — which is the point of the rung. A bot with
   * one bad line in its {@code tools:} is still a bot.
   */
  @Test
  void an_agent_with_a_withheld_tool_is_served_and_says_which_tool() throws Exception {
    wire(
        new AgentRegistry(
            new AgentRegistry.Loaded(
                Map.of("interlocutor", agent("interlocutor")),
                Map.of(),
                Map.of(),
                Map.of(
                    "interlocutor: project_move",
                    "the agent file interlocutor.md lists the tool 'project_move', which is"
                        + " a real tool on the MCP surface a person's own harness"
                        + " offers"))));

    mvc.perform(get("/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("interlocutor"))
        .andExpect(jsonPath("$[0].served").value(true))
        .andExpect(jsonPath("$[0].withheld.length()").value(1))
        .andExpect(jsonPath("$[0].withheld[0]").value(containsString("interlocutor: project_move")))
        .andExpect(jsonPath("$[0].withheld[0]").value(containsString("project_move")));
  }

  /**
   * The two are one list and still one entry each: a dropped tool does not swallow a dropped route,
   * and neither is reported as the other's kind.
   */
  @Test
  void a_dropped_route_and_a_dropped_tool_are_both_carried() throws Exception {
    wire(
        new AgentRegistry(
            new AgentRegistry.Loaded(
                Map.of("interlocutor", agent("interlocutor")),
                Map.of(),
                Map.of("interlocutor -> code_reviewer", "an escalating edge"),
                Map.of("interlocutor: project_move", "a withheld tool"))));

    mvc.perform(get("/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].served").value(true))
        .andExpect(jsonPath("$[0].withheld.length()").value(2));
  }

  // --- per-caller scoping ------------------------------------------------------

  /**
   * The point of task 10's whole surface change, measured rather than assumed: a project's own
   * {@code bots/} genuinely changes what {@code GET /v1/agents} answers, and an unscoped request
   * never sees it.
   *
   * <p>A real {@link DefinitionResolver} over a real {@link DataLayout} under a fresh
   * {@code @TempDir} — {@code DefinitionResolverTest}'s own shape, reused here because {@link
   * #wire(AgentRegistry)}'s {@code DataLayout.NONE} cannot answer this question by construction:
   * {@code keepsAnything()} is false, so {@code readProject} returns the boot set on every call
   * before {@code projectExists} or the tree is ever asked. {@code projects.id("payments")} is
   * stubbed to the id the fixture file is written under, which is the one piece {@link
   * AgentController} itself has to translate a name into.
   */
  @Test
  void listing_agents_for_a_project_includes_that_projects_own_tier(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(9L), "librarian", "exported: true\n", "the project's own librarian");
    wire(
        new DefinitionResolver(
            registry,
            layout,
            id -> true,
            Set.of(),
            Set.of(),
            mock(SessionChannel.class),
            session -> true,
            DefinitionChecks.NONE));
    when(projects.id("payments")).thenReturn(9L);

    mvc.perform(get("/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].name").value("promotion_judge"))
        .andExpect(jsonPath("$[1].name").value("scribe"));

    mvc.perform(get("/v1/agents?project=payments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].name").value("librarian"))
        .andExpect(jsonPath("$[1].name").value("promotion_judge"))
        .andExpect(jsonPath("$[2].name").value("scribe"));
  }

  /**
   * The same fact from {@code POST}'s side: an agent defined only in a project's own tier can be
   * named once the request scopes to that project, and is an ordinary unknown name otherwise.
   *
   * <p>This is what a mutation reverting {@code AgentController.run} to resolve against the
   * injected boot set directly — the shape it had before task 10's Step 6 — would break: both
   * assertions below would then answer the unscoped 400, and the first {@code isAccepted()} would
   * fail.
   */
  @Test
  void running_an_agent_defined_only_in_the_requesting_projects_own_tier(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(9L), "librarian", "exported: true\n", "the project's own librarian");
    wire(
        new DefinitionResolver(
            registry,
            layout,
            id -> true,
            Set.of(),
            Set.of(),
            mock(SessionChannel.class),
            session -> true,
            DefinitionChecks.NONE));
    when(projects.id("payments")).thenReturn(9L);
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_000001");

    mvc.perform(
            post("/v1/agents/librarian/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "go", "project": "payments"}"""))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.agent").value("librarian"));

    mvc.perform(
            post("/v1/agents/librarian/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "go"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("no agent called 'librarian'")));
  }

  /**
   * A turn spoken into a conversation may name an agent that conversation's own project defines,
   * because the caller {@code requireAgent} judges the name against comes from {@code
   * Turn.homeOf(conversation)} — the same home {@link Turn#speak} would actually run the turn in —
   * and never from the request body's {@code project} field, which {@code run} refuses outright
   * whenever a conversation is also named.
   *
   * <p>This is Finding 2's own regression: before {@code callerForConversation} existed, this
   * request would answer the unscoped 400 below despite the turn itself, had one gone through,
   * running in {@code payments} all along — the disagreement between what a caller was told it
   * could run and what a run actually reached.
   */
  @Test
  void a_turn_may_name_an_agent_the_conversations_own_project_defines(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(9L), "librarian", "exported: true\n", "the project's own librarian");
    wire(
        new DefinitionResolver(
            registry,
            layout,
            id -> true,
            Set.of(),
            Set.of(),
            mock(SessionChannel.class),
            session -> true,
            DefinitionChecks.NONE));
    when(projects.id("payments")).thenReturn(9L);
    when(turns.homeOf("cnv_1")).thenReturn(Home.of("payments"));
    when(turns.speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any()))
        .thenReturn("job_000009");

    mvc.perform(
            post("/v1/agents/librarian/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "go", "conversation": "cnv_1"}"""))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value("job_000009"));

    // No handle: a MockMvc request carries no attribute AuthFilter would have set.
    verify(turns)
        .speak(
            eq("cnv_1"),
            any(AgentDefinition.class),
            eq("go"),
            isNull(),
            isNull(),
            eq(Speaker.person(null)),
            any());
  }

  /**
   * The contrast that proves the test above measures something: the same agent, in the same
   * project's own tier, is unreachable from a conversation whose home {@code turns.homeOf} answers
   * as global — {@link #setUp}'s own default stub, deliberately left unstubbed here.
   */
  @Test
  void a_turn_into_a_conversation_with_no_project_cannot_reach_a_project_only_agent(
      @TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(9L), "librarian", "exported: true\n", "the project's own librarian");
    wire(
        new DefinitionResolver(
            registry,
            layout,
            id -> true,
            Set.of(),
            Set.of(),
            mock(SessionChannel.class),
            session -> true,
            DefinitionChecks.NONE));

    mvc.perform(
            post("/v1/agents/librarian/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "go", "conversation": "cnv_1"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("no agent called 'librarian'")));

    verify(turns, never())
        .speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any());
  }

  /**
   * The security fix: {@code GET /v1/agents} takes no {@code session} at all, so a caller cannot
   * name another live session's own machine and have this listing read its {@code .plowshare/}
   * back. An unbound query parameter is ignored by Spring's {@code @RequestParam} binding rather
   * than refused, so the proof has to be behavioural: the channel this resolver would ask a named
   * session through is never touched, whatever the query string says.
   */
  @Test
  void a_session_query_parameter_on_the_listing_is_not_read_at_all() throws Exception {
    mvc.perform(get("/v1/agents?session=someone-elses-live-session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));

    verifyNoInteractions(channel);
  }

  /**
   * The same capability, arriving by the other door. {@code POST /v1/agents/&#123;name&#125;/runs}
   * <em>does</em> take a {@code session}, and it must: that is how a client-local bot is reachable
   * at all. But its refusal used to print {@code exportedNames()} of the caller-resolved registry,
   * so naming any live session id and any name that does not exist answered with that session's own
   * {@code .plowshare/} contents in the 400 — precisely what {@code GET /v1/agents} had its {@code
   * session} parameter removed to prevent.
   *
   * <p>Both halves are asserted, because either alone would pass for a wrong fix: the client-local
   * name must be <b>absent</b> from the refusal, and the project tier's own {@code librarian} must
   * be <b>present</b> in it. Dropping the enumeration entirely would satisfy the first and take the
   * correction away from every operator who mistyped a project bot's name; listing the boot set
   * alone would satisfy the first and answer a narrower question than the caller asked.
   */
  @Test
  void a_refusal_on_the_run_path_never_enumerates_a_clients_own_definitions(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(9L), "librarian", "exported: true\n", "the project's own librarian");
    FakeFiles laptop =
        new FakeFiles()
            .withListing(".plowshare/bots", List.of("laptop_only.md"))
            .withFile(
                ".plowshare/bots/laptop_only.md",
                """
                        ---
                        name: laptop_only
                        description: d
                        model: m
                        max-turns: 2
                        max-model-calls: 4
                        exported: true
                        ---
                        only on somebody's own machine
                        """);
    wire(
        new DefinitionResolver(
            registry,
            layout,
            id -> true,
            Set.of(),
            Set.of(),
            laptop,
            session -> true,
            DefinitionChecks.NONE));
    when(projects.id("payments")).thenReturn(9L);
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_000001");

    // The fixture has to be a client tier that genuinely resolves, or the
    // absence asserted below would prove only that the fake said nothing.
    mvc.perform(
            post("/v1/agents/laptop_only/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "go", "project": "payments", "session": "session-A"}"""))
        .andExpect(status().isAccepted());

    mvc.perform(
            post("/v1/agents/nope/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "go", "project": "payments", "session": "session-A"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("no agent called 'nope'")))
        .andExpect(jsonPath("$.detail").value(not(containsString("laptop_only"))))
        .andExpect(jsonPath("$.detail").value(containsString("librarian")));
  }

  // --- GET /v1/jobs ------------------------------------------------------------

  /**
   * The listing carries running and finished runs together, and the outcome is what tells them
   * apart.
   *
   * <p>This is the endpoint the console's live job view reconciles against, so the two rows are the
   * two states that view has to render. <b>The absent outcome is the contract</b>, exactly as it is
   * on {@code GET /v1/jobs/&#123;id&#125;}: a row with one has finished whatever its ending, and a
   * row without one has not. Asserting only that two rows came back would pass for a listing that
   * reported every run as still going.
   */
  @Test
  void listing_jobs_answers_running_and_finished_and_distinguishes_them_by_outcome()
      throws Exception {
    when(jobs.jobs())
        .thenReturn(
            List.of(
                running("job_000001", "scribe"),
                finished(
                    "job_000002",
                    "promotion_judge",
                    new Outcome(Ending.ANSWERED, "{\"decision\": \"keep\"}", 2, 2, ""))));

    mvc.perform(get("/v1/jobs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value("job_000001"))
        .andExpect(jsonPath("$[0].agent").value("scribe"))
        .andExpect(jsonPath("$[0].state").value("RUNNING"))
        // A running job's outcome is absent, and the absence is the
        // contract: a caller reads it to know the run has not finished.
        .andExpect(jsonPath("$[0].outcome").doesNotExist())
        .andExpect(jsonPath("$[1].id").value("job_000002"))
        .andExpect(jsonPath("$[1].state").value("DONE"))
        .andExpect(jsonPath("$[1].outcome.ending").value("ANSWERED"))
        .andExpect(jsonPath("$[1].outcome.answered").value(true));
  }

  /**
   * A store that could not answer is a 500, and never an empty listing.
   *
   * <p>Every other endpoint here has an arm for the store refusing — the two 404s below, the 400s
   * above — and without this one the listing was the only path to {@code ApiExceptionHandler} that
   * nothing drove. <b>The status is what the whole endpoint is for.</b> An empty 200 would read to
   * the console as "this server is running nothing", which is the exact false reading the event
   * stream's droppability already makes possible and which this listing exists to be the correction
   * for; a failure has to look like a failure.
   *
   * <p>500 and not 404: the id-shaped refusals below are a {@code faults.NotFoundFault} because a
   * caller named a job this process does not have, and a listing names no job. Nothing here is the
   * caller's to correct, which is what the catch-all means.
   */
  @Test
  void a_listing_the_store_could_not_answer_is_a_500_and_not_an_empty_list() throws Exception {
    when(jobs.jobs()).thenThrow(new IllegalStateException("the job store is shut down"));

    mvc.perform(get("/v1/jobs"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("internal_error"))
        // Names the type, so an operator has something to grep for
        // rather than a bare number.
        .andExpect(jsonPath("$.detail").value(containsString("IllegalStateException")))
        .andExpect(jsonPath("$.detail").value(containsString("shut down")));
  }

  // --- GET /v1/jobs/{id} -------------------------------------------------------

  /**
   * {@code JobView.conversation} is the only thing that makes a stopped run continuable from
   * anywhere but the conversation it belongs to -- see {@code Job#conversation}. A caller that
   * cannot read it off {@code GET /v1/jobs/&#123;id&#125;} has no way to reach {@code POST
   * /v1/conversations/&#123;id&#125;/resume} for a run it did not submit itself.
   */
  @Test
  void a_job_names_the_conversation_it_speaks_into() throws Exception {
    String job = submitARun();

    mvc.perform(get("/v1/jobs/" + job))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conversation").isNotEmpty());
  }

  /**
   * A running job has no outcome, and the null is the whole of what a poller needs: a job with one
   * has finished, whatever its ending.
   */
  @Test
  void polling_a_running_job_reports_no_outcome() throws Exception {
    when(jobs.get("job_000001")).thenReturn(running("job_000001", "scribe"));

    mvc.perform(get("/v1/jobs/job_000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("RUNNING"))
        .andExpect(jsonPath("$.cancelRequested").value(false))
        .andExpect(jsonPath("$.outcome").doesNotExist());
  }

  /**
   * A truncated run is never dressed as an answer.
   *
   * <p>{@code ending} carries which constant it was and {@code answered} is the one bit a caller
   * reads before believing {@code text}. Excalibur paid for this: a run that exhausted its turns
   * returned the model's own deliberation as the answer.
   */
  @Test
  void a_run_that_stopped_is_distinguishable_from_one_that_decided() throws Exception {
    when(jobs.get("job_000001"))
        .thenReturn(
            finished(
                "job_000001",
                "promotion_judge",
                new Outcome(
                    Ending.TURN_CAP, "This run stopped at its turn cap of 4 turns.", 4, 4, "")));

    mvc.perform(get("/v1/jobs/job_000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("DONE"))
        .andExpect(jsonPath("$.outcome.ending").value("TURN_CAP"))
        .andExpect(jsonPath("$.outcome.answered").value(false))
        .andExpect(jsonPath("$.outcome.steps").value(4))
        .andExpect(jsonPath("$.outcome.modelCalls").value(4));
  }

  @Test
  void a_run_that_answered_says_so_and_carries_its_text() throws Exception {
    when(jobs.get("job_000001"))
        .thenReturn(
            finished(
                "job_000001",
                "promotion_judge",
                new Outcome(Ending.ANSWERED, "{\"decision\": \"keep\"}", 2, 2, "")));

    mvc.perform(get("/v1/jobs/job_000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome.ending").value("ANSWERED"))
        .andExpect(jsonPath("$.outcome.answered").value(true))
        .andExpect(jsonPath("$.outcome.text").value("{\"decision\": \"keep\"}"));
  }

  /**
   * A finished run says how it went at the model, and a run that measured nothing says nothing
   * rather than a row of absent numbers.
   */
  @Test
  void a_finished_run_carries_its_pace_and_an_unmeasured_one_carries_none() throws Exception {
    when(jobs.get("job_000001"))
        .thenReturn(
            finished(
                "job_000001",
                "promotion_judge",
                new Outcome(Ending.ANSWERED, "keep", 2, 2, "")
                    .paced(
                        new io.aeyer.plowshare.server.agents.Pace(
                            3, 1_210, null, 1_830L, 38.4, false))));
    when(jobs.get("job_000002"))
        .thenReturn(
            finished(
                "job_000002", "promotion_judge", new Outcome(Ending.ANSWERED, "keep", 2, 2, "")));

    mvc.perform(get("/v1/jobs/job_000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome.pace.toolCalls").value(3))
        .andExpect(jsonPath("$.outcome.pace.completionTokens").value(1_210))
        .andExpect(jsonPath("$.outcome.pace.reasoningTokens").value(nullValue()))
        .andExpect(jsonPath("$.outcome.pace.firstTokenMillis").value(1_830))
        .andExpect(jsonPath("$.outcome.pace.tokensPerSecond").value(38.4))
        .andExpect(jsonPath("$.outcome.pace.reasoningEstimated").value(false));
    mvc.perform(get("/v1/jobs/job_000002"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome.pace").value(nullValue()));
  }

  /**
   * A run that stopped at its cap says that continuing it is on offer, and the client does not
   * decide which endings those are.
   *
   * <p><b>The list belongs to this server and to nothing that renders it.</b> {@code
   * Turn.CONTINUABLE} is what a grant is actually checked against, one layer down and against the
   * row rather than against a job in memory, and a console that carried its own copy would offer a
   * grant this server refuses the day the set changes — the exact drift {@code TurnView} sends an
   * ending's name rather than an enum to avoid.
   */
  @Test
  void a_run_stopped_at_its_cap_says_a_grant_would_continue_it() throws Exception {
    when(jobs.get("job_000001"))
        .thenReturn(
            stoppedUnder(
                "job_000001",
                new Outcome(Ending.TURN_CAP, "This run stopped at its turn cap.", 4, 4, ""),
                new RunLimits(Budget.of(40), TurnCap.of(4))));

    mvc.perform(get("/v1/jobs/job_000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome.ending").value("TURN_CAP"))
        .andExpect(jsonPath("$.outcome.resumable").value(true));
  }

  /**
   * A submission owns its allowance and can stop at a continuable ending same as a turn — the
   * arithmetic above says yes on both counts, {@code limits} and the ending together — and {@code
   * resumable} still has to say no.
   *
   * <p>{@code Turn.requireResumable} accepts exactly {@code Origin.TURN} and refuses a submission
   * by name: it is a run nobody was going to speak to again, not a person's turn, however much of
   * its allowance is left. Before {@code Job#conversationOrigin} existed this field had no way to
   * ask, and answered {@code true} here — a jobs screen would offer a continue button that {@code
   * POST /v1/conversations/&#123;id&#125;/resume} then refused with 409.
   */
  @Test
  void a_submission_stopped_at_a_continuable_ending_is_not_resumable() throws Exception {
    Job job =
        JobAccess.newJob(
            "job_000001",
            "scribe",
            new RunLimits(Budget.of(40), TurnCap.of(4)),
            "cnv_000001",
            Origin.SUBMISSION);
    JobAccess.finish(
        job, new Outcome(Ending.TURN_CAP, "This run stopped at its turn cap.", 4, 4, ""));
    when(jobs.get("job_000001")).thenReturn(job);

    mvc.perform(get("/v1/jobs/job_000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome.ending").value("TURN_CAP"))
        .andExpect(jsonPath("$.conversation").value("cnv_000001"))
        .andExpect(jsonPath("$.outcome.resumable").value(false));
  }

  /**
   * A cancelled run is continuable and is never offered, and those are two different questions this
   * field answers only one of.
   *
   * <p>{@code POST /v1/conversations/&#123;id&#125;/resume} accepts {@code CANCELLED} —
   * mechanically it stops at the same loop boundary as the other two, so refusing it would be an
   * invented restriction — but the person asked it to stop, and putting "continue?" in front of
   * them answers a question they did not ask. This field is the offer and not the rule, so it is
   * false here while the endpoint goes on taking it.
   */
  @Test
  void a_cancelled_run_is_not_offered_though_a_grant_would_still_be_taken() throws Exception {
    when(jobs.get("job_000001"))
        .thenReturn(
            stoppedUnder(
                "job_000001",
                new Outcome(Ending.CANCELLED, "This run was cancelled.", 2, 2, ""),
                new RunLimits(Budget.of(40), TurnCap.of(16))));

    mvc.perform(get("/v1/jobs/job_000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome.resumable").value(false));
  }

  /**
   * A run that spent the conversation's whole budget <em>is</em> offered, because the grant for
   * that ending raises the thing that stopped it.
   *
   * <p><b>This test asserted the opposite and the opposite was a defect.</b> The reasoning it
   * carried was sound about an older offer: the only grant on offer was turns, turns will not
   * continue a run that ran out of model calls, so the offer was withheld rather than made and
   * refused — and, pleasingly, it fell out of the arithmetic rather than out of a second list of
   * endings. Then the offer learned to grant model calls for exactly this ending, and the
   * arithmetic went on excluding it: a {@code CALL_BUDGET} stop has {@code remaining() == 0} by
   * construction, so {@code resumable} was false, so the console returned before its new branch was
   * ever reached and the feature was silently absent in a green suite.
   *
   * <p>{@code Turn.grantRaisesTheBudget} is what the arithmetic was missing. The conjunct is not
   * gone — see the test below, which is the half of the old rule that is still true.
   */
  @Test
  void a_run_that_stopped_at_its_budget_is_offered_the_grant_that_raises_it() throws Exception {
    Budget spent = Budget.resumed(40, 40);
    when(jobs.get("job_000001"))
        .thenReturn(
            stoppedUnder(
                "job_000001",
                new Outcome(
                    Ending.CALL_BUDGET, "This conversation has spent its budget.", 9, 40, ""),
                new RunLimits(spent, TurnCap.of(16))));

    mvc.perform(get("/v1/jobs/job_000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome.ending").value("CALL_BUDGET"))
        .andExpect(jsonPath("$.outcome.resumable").value(true));
  }

  /**
   * A run that stopped at its <em>turn cap</em> with nothing left to spend is still not offered,
   * which is the half of the old rule that survives.
   *
   * <p>The grant for a {@code TURN_CAP} stop raises turns, and turns will not continue a run whose
   * conversation has no model calls left: {@code Turn.resume} would refuse it with "there is
   * nothing left for the run this would continue", and offering something certain to be refused is
   * worse than not offering it. Without this test, deleting the budget conjunct outright would pass
   * the suite.
   */
  @Test
  void a_run_stopped_at_its_cap_with_nothing_left_to_spend_is_not_offered() throws Exception {
    when(jobs.get("job_000001"))
        .thenReturn(
            stoppedUnder(
                "job_000001",
                new Outcome(Ending.TURN_CAP, "This run stopped at its turn cap.", 16, 40, ""),
                new RunLimits(Budget.resumed(40, 40), TurnCap.of(16))));

    mvc.perform(get("/v1/jobs/job_000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome.ending").value("TURN_CAP"))
        .andExpect(jsonPath("$.outcome.resumable").value(false));
  }

  /**
   * Every job in a lifted delegation tree can be polled, and the limits it reports say "no ceiling"
   * rather than a number.
   *
   * <p><b>This is the poll the console runs on a timer, and it answered 500.</b> {@code JobStore}
   * puts the conversation's one shared {@code Budget} into every {@code RunLimits} in the tree —
   * the parent's and each delegated child's, by reference — so a conversation opened with {@code
   * noBudget} made {@code Budget.limit()} throw for every job in it, at both the limits and the
   * {@code resumable} arithmetic. The turn cap one line above had had its
   * nullable-number-beside-a-boolean since the beginning and the budget had not.
   *
   * <p>The spend is asserted with it, because a lifted budget still measures what it spent and that
   * is the only number left for a person watching one.
   */
  @Test
  void polling_a_run_in_a_lifted_tree_reports_no_ceiling_and_what_it_spent() throws Exception {
    when(jobs.get("job_000001"))
        .thenReturn(
            stoppedUnder(
                "job_000001",
                new Outcome(Ending.TURN_CAP, "This run stopped at its turn cap.", 16, 90, ""),
                new RunLimits(Budget.lifted(90), TurnCap.of(16))));

    mvc.perform(get("/v1/jobs/job_000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limits.noBudget").value(true))
        .andExpect(jsonPath("$.limits.maxModelCalls").value(nullValue()))
        .andExpect(jsonPath("$.limits.modelCallsSpent").value(90))
        // And it is offered: a budget with no ceiling has not run out,
        // so the conjunct that withholds the offer cannot fire for one.
        .andExpect(jsonPath("$.outcome.resumable").value(true));
  }

  /**
   * A run that reached an answer has nothing to continue, which is the ordinary case and the one a
   * console must not put a control beside.
   */
  @Test
  void a_run_that_answered_is_not_offered_a_continuation() throws Exception {
    when(jobs.get("job_000001"))
        .thenReturn(
            stoppedUnder(
                "job_000001",
                new Outcome(Ending.ANSWERED, "here is the answer", 2, 2, ""),
                new RunLimits(Budget.of(40), TurnCap.of(16))));

    mvc.perform(get("/v1/jobs/job_000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome.resumable").value(false));
  }

  /**
   * A job this server holds no bounds for is not offered either, and the absence is read as "no"
   * rather than skipped.
   *
   * <p>A curator pass is a job and is not one agent's run — {@code JobView.limits} is null for it —
   * so nothing here can say what it has left to spend. It is also not a conversation anybody speaks
   * into, so there is nothing for a grant to continue.
   */
  @Test
  void a_job_with_no_bounds_on_its_handle_is_not_offered_a_continuation() throws Exception {
    when(jobs.get("job_000001"))
        .thenReturn(
            finished(
                "job_000001",
                "promotion_judge",
                new Outcome(Ending.TURN_CAP, "This run stopped at its turn cap.", 4, 4, "")));

    mvc.perform(get("/v1/jobs/job_000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome.resumable").value(false));
  }

  /**
   * An unknown job is 404, and the message is the honest one: jobs live in memory and a restart
   * really does lose them, by design. Nothing durable is left holding a lie about a run that is
   * gone.
   */
  @Test
  void polling_a_job_this_process_does_not_know_is_404() throws Exception {
    when(jobs.get("job_999999"))
        .thenThrow(
            new NotFoundFault("no job called 'job_999999'; this process knows [job_000001]"));

    mvc.perform(get("/v1/jobs/job_999999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value(containsString("job_999999")))
        .andExpect(jsonPath("$.detail").value(containsString("job_000001")));
  }

  // --- POST /v1/jobs/{id}/cancel -----------------------------------------------

  /**
   * A cancel is a request the loop honours between turns, not a kill.
   *
   * <p>So the job still reads {@code RUNNING} and {@code cancelRequested} is what says the request
   * landed. Reporting {@code DONE} here would report a state that is not yet true, and a caller
   * acting on it would read the outcome of a run still going.
   */
  @Test
  void cancelling_reports_the_request_and_not_a_state_that_is_not_true_yet() throws Exception {
    Job job = running("job_000001", "promotion_judge");
    when(jobs.get("job_000001")).thenReturn(job);
    when(jobs.cancel("job_000001"))
        .thenAnswer(
            call -> {
              cancelRequest(job);
              return true;
            });

    mvc.perform(post("/v1/jobs/job_000001/cancel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("RUNNING"))
        .andExpect(jsonPath("$.cancelRequested").value(true));

    verify(jobs).cancel("job_000001");
  }

  // --- POST /v1/agents/{name}/runs, under a ceiling this run was given ---------

  /** The narrowest of the three levels, reaching the store as the object the run will read. */
  @Test
  void a_run_can_be_submitted_under_a_cap_of_its_own() throws Exception {
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_000001");

    mvc.perform(
            post("/v1/agents/promotion_judge/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "rule on mem_000001", "maxTurns": 40}"""))
        .andExpect(status().isAccepted());

    ArgumentCaptor<TurnCap> cap = ArgumentCaptor.captor();
    verify(jobs)
        .submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            isNull(),
            cap.capture(),
            any(),
            anyBoolean(),
            any());
    assertEquals(40, cap.getValue().turns());
  }

  /**
   * Disabled is expressible on one run, and it is expressed as a decision rather than as a number
   * nobody chose.
   */
  @Test
  void a_run_can_be_submitted_with_no_turn_cap_at_all() throws Exception {
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_000001");

    mvc.perform(
            post("/v1/agents/promotion_judge/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "read the whole tree", "noTurnCap": true}"""))
        .andExpect(status().isAccepted());

    ArgumentCaptor<TurnCap> cap = ArgumentCaptor.captor();
    verify(jobs)
        .submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            isNull(),
            cap.capture(),
            any(),
            anyBoolean(),
            any());
    assertFalse(cap.getValue().capped(), "and no ceiling was invented for it");
  }

  @Test
  void a_run_naming_a_cap_and_lifting_it_is_refused_before_anything_is_started() throws Exception {
    mvc.perform(
            post("/v1/agents/promotion_judge/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "go", "maxTurns": 40, "noTurnCap": true}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("noTurnCap")));

    verify(jobs, never())
        .submit(any(AgentDefinition.class), any(), any(), any(), any(), any(), anyBoolean(), any());
  }

  // --- POST /v1/jobs/{id}/limits ----------------------------------------------

  /**
   * <b>A ceiling raised on a run that is already going</b>, and the proof it landed is that the
   * object the run reads has moved — not that a 200 came back.
   */
  @Test
  void raising_a_running_jobs_turn_cap_moves_the_ceiling_the_run_reads() throws Exception {
    RunLimits limits = new RunLimits(Budget.of(40), TurnCap.of(16));
    when(jobs.get("job_000001")).thenReturn(runningUnder("job_000001", limits));

    mvc.perform(
            post("/v1/jobs/job_000001/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"maxTurns": 36}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("RUNNING"))
        .andExpect(jsonPath("$.limits.maxTurns").value(36));

    assertEquals(36, limits.cap().turns());
    assertFalse(limits.cap().stops(20), "the run is no longer past its ceiling");
  }

  @Test
  void a_running_jobs_cap_can_be_taken_off_altogether() throws Exception {
    RunLimits limits = new RunLimits(Budget.of(40), TurnCap.of(16));
    when(jobs.get("job_000001")).thenReturn(runningUnder("job_000001", limits));

    mvc.perform(
            post("/v1/jobs/job_000001/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"noTurnCap": true}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limits.noTurnCap").value(true))
        .andExpect(jsonPath("$.limits.maxTurns").doesNotExist());

    assertFalse(limits.cap().capped());
  }

  /**
   * The budget is the bound that matters, because it is the one that bounds cost — and it is shared
   * by a whole delegation tree, so this moves it for every job in one.
   */
  @Test
  void raising_a_running_jobs_budget_moves_what_its_whole_tree_may_spend() throws Exception {
    Budget budget = Budget.of(16);
    budget.trySpend();
    RunLimits limits = new RunLimits(budget, TurnCap.of(100));
    when(jobs.get("job_000001")).thenReturn(runningUnder("job_000001", limits));

    mvc.perform(
            post("/v1/jobs/job_000001/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"maxModelCalls": 60}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limits.maxModelCalls").value(60))
        .andExpect(jsonPath("$.limits.modelCallsSpent").value(1));

    assertEquals(60, budget.limit());
    assertEquals(1, budget.spent(), "and raising a ceiling un-spends nothing");
  }

  /**
   * Raising a ceiling on a run whose budget was never one is asking for something that cannot
   * happen, which is exactly {@code moving_the_limits_of_a_finished_job_is_refused}'s reasoning one
   * field over — and {@code Budget.changeTo} says so with an {@code IllegalStateException}, not the
   * {@code IllegalArgumentException} {@code moveBudget} used to be the only one caught. Before this
   * door caught both, a lifted conversation reaching it would have surfaced as an uncaught 500
   * rather than the clean 400 every other refusal here answers with.
   */
  @Test
  void raising_the_ceiling_on_a_run_that_never_had_one_is_refused_cleanly() throws Exception {
    RunLimits limits = new RunLimits(Budget.none(), TurnCap.of(100));
    when(jobs.get("job_000001")).thenReturn(runningUnder("job_000001", limits));

    mvc.perform(
            post("/v1/jobs/job_000001/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"maxModelCalls": 60}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("no ceiling")));

    assertFalse(limits.budget().capped(), "and nothing was changed");
  }

  /**
   * The refusal that keeps a conversation's accounting true.
   *
   * <p>{@code Budget.changeTo} permits this and defines what it means; this door refuses it because
   * a conversation's row cannot record a total below its own spending, and names the verb that does
   * stop a run.
   */
  @Test
  void a_budget_cannot_be_lowered_below_what_a_run_has_already_spent() throws Exception {
    Budget budget = Budget.of(16);
    budget.trySpend();
    budget.trySpend();
    budget.trySpend();
    RunLimits limits = new RunLimits(budget, TurnCap.of(100));
    when(jobs.get("job_000001")).thenReturn(runningUnder("job_000001", limits));

    mvc.perform(
            post("/v1/jobs/job_000001/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"maxModelCalls": 1}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("already spent 3")))
        .andExpect(jsonPath("$.detail").value(containsString("cancel")));

    assertEquals(16, budget.limit(), "and nothing was changed");
  }

  /**
   * A cap may be lowered under the turns a run has taken, which stops it at its next boundary. The
   * budget's refusal above is about the archive and not about the run, so it does not apply here:
   * nothing durable records a run's ceiling.
   */
  @Test
  void a_turn_cap_may_be_lowered_under_what_a_run_has_taken() throws Exception {
    RunLimits limits = new RunLimits(Budget.of(40), TurnCap.of(100));
    when(jobs.get("job_000001")).thenReturn(runningUnder("job_000001", limits));

    mvc.perform(
            post("/v1/jobs/job_000001/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"maxTurns": 2}"""))
        .andExpect(status().isOk());

    assertTrue(limits.cap().stops(11), "a run eleven turns in is now past its ceiling");
  }

  /**
   * A body that names nothing would answer 200 having done nothing, which reads to whoever sent it
   * as a limit that moved.
   */
  @Test
  void a_body_naming_no_limit_is_refused_rather_than_answered() throws Exception {
    RunLimits limits = new RunLimits(Budget.of(40), TurnCap.of(16));
    when(jobs.get("job_000001")).thenReturn(runningUnder("job_000001", limits));

    mvc.perform(
            post("/v1/jobs/job_000001/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("names no limit")));

    assertEquals(16, limits.cap().turns());
    assertEquals(40, limits.budget().limit());
  }

  /**
   * A finished run is refused where a finished run is cancelled without complaint, and the
   * difference is what the caller is asking for.
   *
   * <p>Cancelling one asks for a state it is already in; raising its ceiling asks for something
   * that cannot happen, and a 200 would say it had.
   */
  @Test
  void moving_the_limits_of_a_finished_job_is_refused() throws Exception {
    RunLimits limits = new RunLimits(Budget.of(40), TurnCap.of(16));
    Job job = JobAccess.newJob("job_000001", "promotion_judge", limits);
    JobAccess.finish(job, new Outcome(Ending.ANSWERED, "done", 1, 1, ""));
    when(jobs.get("job_000001")).thenReturn(job);

    mvc.perform(
            post("/v1/jobs/job_000001/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"maxTurns": 36}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("already finished")));

    assertEquals(16, limits.cap().turns(), "and nothing was changed");
  }

  /**
   * A curator pass builds its own budget and shares it across every ruling in it; {@code JobStore}
   * never sees it, so there is nothing on the handle to move and the refusal says which kind of job
   * it is.
   */
  @Test
  void a_job_that_is_not_an_agent_run_has_no_limits_to_move() throws Exception {
    when(jobs.get("job_000001")).thenReturn(running("job_000001", "curator"));

    mvc.perform(
            post("/v1/jobs/job_000001/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"maxModelCalls": 60}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("not an agent run")));
  }

  @Test
  void moving_the_limits_of_a_job_this_process_does_not_know_is_404() throws Exception {
    when(jobs.get("job_000001")).thenThrow(new NotFoundFault("no job has the id job_000001"));

    mvc.perform(
            post("/v1/jobs/job_000001/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"maxTurns": 36}"""))
        .andExpect(status().isNotFound());
  }

  /**
   * A job whose bounds this server holds reports them while it runs, so a console can show a run
   * approaching one before it gets there.
   */
  @Test
  void polling_a_running_job_reports_what_it_is_bounded_by() throws Exception {
    Budget budget = Budget.of(40);
    budget.trySpend();
    when(jobs.get("job_000001"))
        .thenReturn(runningUnder("job_000001", new RunLimits(budget, TurnCap.of(16))));

    mvc.perform(get("/v1/jobs/job_000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limits.maxTurns").value(16))
        .andExpect(jsonPath("$.limits.noTurnCap").value(false))
        .andExpect(jsonPath("$.limits.maxModelCalls").value(40))
        .andExpect(jsonPath("$.limits.modelCallsSpent").value(1));
  }

  /**
   * <b>A run that ran uncapped says so after it has finished</b>, which is where the decision is
   * visible.
   *
   * <p>An ending cannot carry it: a run that answered on its ninetieth turn and one that answered
   * on its ninetieth turn with nothing over it produce the same {@code ANSWERED}, and only one of
   * them was a decision somebody took.
   */
  @Test
  void a_finished_run_still_says_whether_anything_was_capping_it() throws Exception {
    Job job =
        JobAccess.newJob(
            "job_000001", "promotion_judge", new RunLimits(Budget.of(40), TurnCap.none()));
    JobAccess.finish(job, new Outcome(Ending.ANSWERED, "the codename is Gnomon", 90, 90, ""));
    when(jobs.get("job_000001")).thenReturn(job);

    mvc.perform(get("/v1/jobs/job_000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome.ending").value("ANSWERED"))
        .andExpect(jsonPath("$.limits.noTurnCap").value(true))
        .andExpect(jsonPath("$.limits.maxTurns").doesNotExist());
  }

  /**
   * Cancelling a finished job changes nothing and is not an error. Refusing it would make a caller
   * race the run it is trying to stop.
   */
  @Test
  void cancelling_a_finished_job_is_not_an_error() throws Exception {
    when(jobs.get("job_000001"))
        .thenReturn(
            finished("job_000001", "scribe", new Outcome(Ending.ANSWERED, "done", 1, 1, "")));
    when(jobs.cancel("job_000001")).thenReturn(false);

    mvc.perform(post("/v1/jobs/job_000001/cancel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("DONE"));
  }

  @Test
  void cancelling_a_job_this_process_does_not_know_is_404() throws Exception {
    when(jobs.get("job_999999")).thenThrow(new NotFoundFault("no job called 'job_999999'"));

    mvc.perform(post("/v1/jobs/job_999999/cancel")).andExpect(status().isNotFound());

    verify(jobs, never()).cancel(anyString());
  }

  // --- POST /v1/curate ---------------------------------------------------------

  /**
   * A pass is a job, submitted through the same store and polled through the same endpoints.
   *
   * <p>It is not an agent — there is deliberately no {@code curator.md}, because with triage in
   * Java there is nothing for a model in one to do — so it goes through {@code JobStore}'s other
   * door. Everything else about it is a job: a virtual thread, an id, a cancel honoured at a
   * boundary.
   */
  @Test
  void curating_starts_a_job_and_answers_with_its_id() throws Exception {
    when(jobs.submit(anyString(), any(), any())).thenReturn("job_000007");

    mvc.perform(
            post("/v1/curate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"project": "payments"}"""))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value("job_000007"))
        .andExpect(jsonPath("$.agent").value(Curator.BY));
  }

  /**
   * The pass really is the work the job runs, and it is put to the project that was asked for.
   *
   * <p>Captured and invoked rather than asserted about, because "submit was called" is satisfied by
   * a controller that submitted the wrong pass, or an empty one. This is the only test that can see
   * which project reaches {@code Curator.pass}.
   */
  @Test
  void the_job_a_curate_starts_is_a_pass_over_the_project_that_was_asked_for() throws Exception {
    when(jobs.submit(anyString(), any(), any())).thenReturn("job_000007");
    when(curator.pass(anyString(), any(), any()))
        .thenReturn(new Outcome(Ending.ANSWERED, "This pass finished.", 0, 0, ""));

    mvc.perform(
            post("/v1/curate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"project": "payments", "maxModelCalls": 12}"""))
        .andExpect(status().isAccepted());

    ArgumentCaptor<Function<BooleanSupplier, Outcome>> work = workCaptor();
    // The project is on the submission as well as inside the pass now: a
    // curator pass has no conversation of its own, so the job row is the
    // only durable thing that can say which project it went over.
    verify(jobs).submit(eq(Curator.BY), eq(Home.of("payments")), work.capture());
    work.getValue().apply(() -> false);

    ArgumentCaptor<Budget> budget = ArgumentCaptor.forClass(Budget.class);
    verify(curator).pass(eq("payments"), budget.capture(), any());
    assertEquals(12, budget.getValue().limit());
  }

  /** With no number from the caller, the configured default. */
  @Test
  void a_curate_with_no_budget_uses_the_configured_one() throws Exception {
    when(jobs.submit(anyString(), any(), any())).thenReturn("job_000007");
    when(curator.pass(anyString(), any(), any()))
        .thenReturn(new Outcome(Ending.ANSWERED, "This pass finished.", 0, 0, ""));

    mvc.perform(
            post("/v1/curate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"project": "payments"}"""))
        .andExpect(status().isAccepted());

    ArgumentCaptor<Function<BooleanSupplier, Outcome>> work = workCaptor();
    verify(jobs).submit(eq(Curator.BY), eq(Home.of("payments")), work.capture());
    work.getValue().apply(() -> false);

    ArgumentCaptor<Budget> budget = ArgumentCaptor.forClass(Budget.class);
    verify(curator).pass(eq("payments"), budget.capture(), any());
    assertEquals(
        new AgentsProperties().getCuratorBudget(),
        budget.getValue().limit(),
        "the default is the property's, read from it rather than repeated here");
  }

  /**
   * There is no global pass, and the refusal says why rather than saying "project is required".
   *
   * <p>Promotion is the operation that puts a project's memory into global, so a pass over global
   * would have nowhere to promote to. A caller told only that a field was missing would reasonably
   * try to guess a value for it.
   */
  @Test
  void curating_with_no_project_is_refused_and_says_why_there_is_no_global_pass() throws Exception {
    mvc.perform(post("/v1/curate").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("nowhere to promote to")));

    verify(jobs, never()).submit(anyString(), any(), any());
  }

  /**
   * A budget of zero is a pass that can do nothing, and {@code Budget.of} already refuses it. The
   * refusal reaches the caller as a 400 rather than as a 500 from a constructor.
   */
  @Test
  void curating_with_a_budget_of_nothing_is_refused_as_a_bad_request() throws Exception {
    mvc.perform(
            post("/v1/curate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"project": "payments", "maxModelCalls": 0}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("at least one model call")));

    verify(jobs, never()).submit(anyString(), any(), any());
  }

  // --- helpers -----------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<Function<BooleanSupplier, Outcome>> workCaptor() {
    return ArgumentCaptor.forClass(Function.class);
  }

  /**
   * A minimal, valid definition file, {@code DefinitionResolverTest}'s own shape: {@code
   * max-turns}/{@code max-model-calls} are required frontmatter with no default, so a fixture that
   * omitted them would parse as disabled rather than as the project-tier agent a scoping test needs
   * to actually take effect. {@code extraFrontmatter} is where a test adds {@code exported: true}
   * -- absent means no, on both doors this file's scoping tests reach.
   */
  private static void write(Path dir, String name, String extraFrontmatter, String body)
      throws Exception {
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(name + ".md"),
        "---\nname: "
            + name
            + "\ndescription: d\nmodel: fast\nmax-turns: 2\n"
            + "max-model-calls: 4\n"
            + extraFrontmatter
            + "---\n"
            + body
            + "\n");
  }

  private static AgentDefinition agent(String name) {
    return agent(name, List.of(), List.of(), List.of(), "You do one thing.");
  }

  /**
   * The same fixture with the one field {@code the_listing_carries_each_rows_own_description}
   * needs: a description that is not the {@code "a fixture"} literal every other definition in this
   * file shares, so that row can be told apart from a hardcoded string.
   */
  private static AgentDefinition described(String name, String description) {
    return new AgentDefinition(
        name,
        description,
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
   * An agent that declares something, for the tests that read a declaration back.
   *
   * <p>The prompt is a parameter and not a constant for the reason {@code
   * listing_agents_answers_what_each_may_do_and_never_its_prompt} needs: two agents whose prompts
   * differ in a distinctive phrase each, so that a leak of either one is a named failure rather
   * than a body somebody has to read.
   */
  private static AgentDefinition agent(
      String name, List<String> tools, List<String> calls, List<Grant> scopes, String prompt) {
    return new AgentDefinition(
        name, "a fixture", "fast", tools, calls, scopes, 2, 4, prompt, true, true);
  }

  /**
   * The same fixture, declaring that it needs a model that sees. Whether the fleet has one is
   * {@code AgentsConfig}'s boot question and not this endpoint's; what this endpoint asks is
   * whether the agent said it can.
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
   * The same fixture declaring that it is somebody a person talks to rather than a role something
   * invokes.
   *
   * <p>{@code delegable} is false, which is the shipped bot's own pair: a person's bot becoming
   * somebody else's sub-agent is the category error {@code interlocutor} already refuses.
   */
  private static AgentDefinition character(String name) {
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
        "You are somebody.",
        true,
        false,
        false,
        true);
  }

  /**
   * The same fixture with the one difference this surface is about: an agent nothing outside may
   * name.
   */
  private static AgentDefinition privately(String name) {
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
        false,
        true);
  }

  /**
   * A real {@link Job}, not a mock.
   *
   * <p>Its state transitions are package-private on purpose, and building the real thing through a
   * package-private helper keeps this test honest about them: a stubbed {@code state()} could
   * report {@code DONE} with no outcome, which is a pair {@code Job} cannot produce.
   */
  private static Job running(String id, String agent) {
    return JobAccess.newJob(id, agent);
  }

  /**
   * A running job whose bounds this server holds — the shape every agent run has, and the one a
   * curator pass does not.
   *
   * <p>Origin.TURN and a conversation, on the same terms: every agent run has a conversation now,
   * and the tests built over this helper are about the turn-cap and budget arithmetic rather than
   * about origin, so this fixture gives them the ordinary shape that arithmetic is actually read
   * against — a person's turn — rather than the shape of a run nothing was going to speak to again.
   */
  private static Job runningUnder(String id, RunLimits limits) {
    return JobAccess.newJob(id, "promotion_judge", limits, "cnv_000001", Origin.TURN);
  }

  /**
   * A finished run under the bounds it went under, which is what every agent run has and what an
   * outcome's offer is read against. {@link #runningUnder}'s conversation, for the same reason.
   */
  private static Job stoppedUnder(String id, Outcome outcome, RunLimits limits) {
    Job job = JobAccess.newJob(id, "promotion_judge", limits, "cnv_000001", Origin.TURN);
    JobAccess.finish(job, outcome);
    return job;
  }

  private static Job finished(String id, String agent, Outcome outcome) {
    Job job = JobAccess.newJob(id, agent);
    JobAccess.finish(job, outcome);
    return job;
  }

  private static void cancelRequest(Job job) {
    JobAccess.requestCancel(job);
  }

  /**
   * Submits a run through the real endpoint and stubs its poll to answer as the
   * conversation-carrying job a real {@code JobStore} would register, so a test that only cares
   * about the wire shape does not have to hand-build the request and response pieces itself.
   *
   * @return the job id, minted the way every submission answers one
   */
  private String submitARun() throws Exception {
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_000001");
    // Origin.SUBMISSION: POST /v1/agents/{name}/runs is exactly that door,
    // and matching it here is what makes
    // a_job_names_the_conversation_it_speaks_into an honest fixture for the
    // scenario it is named for.
    when(jobs.get("job_000001"))
        .thenReturn(JobAccess.newJob("job_000001", "scribe", "cnv_000001", Origin.SUBMISSION));

    mvc.perform(
            post("/v1/agents/scribe/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "go"}"""))
        .andExpect(status().isAccepted());

    return "job_000001";
  }
}
