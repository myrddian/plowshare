package io.aeyer.plowshare.server.ws;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.agents.RecordingLogStages;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.api.ContextView;
import io.aeyer.plowshare.server.api.ConversationController;
import io.aeyer.plowshare.server.api.ConversationView;
import io.aeyer.plowshare.server.api.ConversationsProperties;
import io.aeyer.plowshare.server.archive.ArchiveRefusedException;
import io.aeyer.plowshare.server.archive.CompactionRecord;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.archive.EntryPage;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.LogSearch;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.llm.tokens.RatioTokenizer;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import io.aeyer.plowshare.server.requests.RequestedAgent;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Every endpoint {@code ConversationController} answers, driven twice — once over HTTP and once as
 * a frame — off one request, asserting that the two surfaces said the same thing.
 *
 * <h2>One file for the area, and not one file per handler</h2>
 *
 * <p>The two pilots have a file each ({@code ProjectDefineHandlerTest}, {@code
 * ConversationTurnsHandlerTest}), which was right for one endpoint apiece and does not scale to
 * eleven: the fixture below is an eleven-argument controller constructor and a routing area over
 * the same mocks, and restating it ten times would be ten chances for one copy to hand the two
 * surfaces different stores — the exact failure a parity test exists to catch, reintroduced by the
 * shape of the test file. <b>This is the shape the later breadth tasks should copy</b>: one {@code
 * <Controller>FramesTest}, one fixture, a section per endpoint. The pilots keep their own files;
 * nothing is moved out of them.
 *
 * <h2>What every section asserts</h2>
 *
 * <p>The happy path and <b>at least one refusal</b>. Refusals are where two surfaces drift — a
 * status is easy to agree on and a sentence is not, and the client design records that a client's
 * own hand-written sentence is correct only when the server sent none. {@link
 * FrameParity#assertSameRefusal} compares the words, which is the assertion that matters.
 *
 * <p><b>One section has no second surface, and it says so where it is.</b> {@code
 * conversation.latest} mirrors no endpoint — {@link FrameTypes#CONVERSATION_LATEST} argues why — so
 * there is nothing there for a parity assertion to compare, and its cases assert what the frame
 * answers and which method it asks instead. Each of them was measured by mutation, since a case
 * with no second surface behind it has no comparison keeping it honest: the handler was made to
 * read {@code Home.global()} regardless of its payload, and the two cases that must notice, did.
 *
 * <p><b>Mocked stores, shared by both surfaces, with the real {@link Conversations} over them.</b>
 * What a store does with a row is its own Testcontainers test's subject; what is under test here is
 * which method each surface calls, with what, and what each does with what comes back — including
 * with what is thrown.
 */
class ConversationFramesTest {

  private static final Instant OPENED_AT = Instant.parse("2026-09-11T00:00:00Z");
  private static final Home PAYMENTS = Home.of("payments");

  private ConversationStore conversations;
  private CompactionStore compactions;
  private TurnStore turns;
  private EntryStore entries;
  private JobRuntime runtime;
  private Turn speaking;
  private Compaction compaction;
  private Callers callers;
  private MockMvc mvc;
  private FrameRouter router;
  private final RecordingLogStages told = new RecordingLogStages();

  @BeforeEach
  void setUp() {
    conversations = mock(ConversationStore.class);
    compactions = mock(CompactionStore.class);
    turns = mock(TurnStore.class);
    entries = mock(EntryStore.class);
    runtime = mock(JobRuntime.class);
    when(runtime.withAgentRules(any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(runtime.schemasOfferedTo(any()))
        .thenReturn(
            List.of(
                new ToolSchema(
                    "memory_recall",
                    "The memories nearest a question.",
                    Map.of("type", "object"))));
    speaking = mock(Turn.class);
    compaction = mock(Compaction.class);
    Conversations rules = new Conversations(conversations, turns);
    AgentRegistry booted = new AgentRegistry(Map.of("talker", agent()));
    ObjectProvider<AgentRegistry> agents = providerOf(booted);
    // The boot set for every caller unless a case says otherwise, which is
    // what a conversation in no project with no client tier resolves to.
    callers = mock(Callers.class);
    when(callers.callerForConversation(any(), any()))
        .thenAnswer(asked -> new DefinitionResolver.Caller(null, asked.getArgument(1)));
    when(callers.readAgent(any(), any()))
        .thenAnswer(
            asked -> RequestedAgent.toRead(booted, booted::exportedNames, asked.getArgument(0)));
    Tokenizer tokenizer = new RatioTokenizer(RatioTokenizer.DEFAULT_CHARACTERS_PER_TOKEN);
    ConversationsProperties properties = configured();

    // FrameParity.endpointsOf and not a bare standaloneSetup: three of these
    // eleven endpoints answer with a timestamp in the body, and the bare
    // harness renders one as a number where a deployed server sends an ISO
    // string. That class's javadoc has the argument.
    mvc =
        FrameParity.endpointsOf(
            new ConversationController(
                conversations,
                compactions,
                turns,
                entries,
                runtime,
                speaking,
                agents,
                properties,
                compaction,
                tokenizer,
                rules,
                told,
                org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class)));
    // Through the area rather than through a literal map: this is the
    // registration the handlers are reached by in production, and a test
    // that built its own map would pass for a type nobody wired.
    router =
        new FrameRoutingConfig()
            .frameRouter(
                List.of(
                    new ConversationFrames(
                        rules,
                        conversations,
                        compactions,
                        turns,
                        entries,
                        runtime,
                        speaking,
                        agents,
                        properties,
                        compaction,
                        tokenizer,
                        callers,
                        told,
                        org.mockito.Mockito.mock(
                            io.aeyer.plowshare.server.agents.CallerAccess.class))));
  }

  // --- conversation.open ---------------------------------------------------

  /** Both surfaces open one conversation, with one home and one allowance. */
  @Test
  void both_surfaces_open_the_same_conversation() throws Exception {
    when(conversations.open(any(), any(), any(), any())).thenReturn(row("cnv_1", PAYMENTS, 12));
    String asked =
        """
                {"project": "payments", "maxModelCalls": 12}""";

    MockHttpServletResponse http = posted("/v1/conversations", asked);
    Outcome outcome = route(FrameTypes.CONVERSATION_OPEN, asked);

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(conversations, times(2)).open(eq(PAYMENTS), any(), isNull(), isNull());
  }

  /**
   * A body that says nothing about the allowance takes the operator's, on both surfaces.
   *
   * <p><b>A default is a place two surfaces drift silently</b>: it is not in the request, so
   * nothing in a payload comparison would show a handler that reached for a number of its own. The
   * fixture's 77 is deliberately not what {@code application.yml} ships, so a hard-coded default
   * would fail.
   */
  @Test
  void a_conversation_that_names_no_allowance_takes_the_operators_on_both_surfaces()
      throws Exception {
    when(conversations.open(any(), any(), any(), any()))
        .thenReturn(row("cnv_1", Home.global(), 77));

    MockHttpServletResponse http = posted("/v1/conversations", "{}");
    Outcome outcome = route(FrameTypes.CONVERSATION_OPEN, "{}");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    // Captured rather than matched: Budget is a class with no equals, so
    // eq(Budget.of(77)) would compare identities and fail against two
    // perfectly correct allowances.
    ArgumentCaptor<Budget> allowance = ArgumentCaptor.captor();
    verify(conversations, times(2))
        .open(eq(Home.global()), allowance.capture(), isNull(), isNull());
    for (Budget given : allowance.getAllValues()) {
      assertEquals(
          configured().getDefaultBudget(),
          given.limit(),
          "both surfaces opened with the operator's own default");
    }
  }

  /**
   * A cap named and lifted at once is refused in the same words, and nothing is opened by either
   * surface.
   */
  @Test
  void a_cap_named_and_lifted_at_once_is_the_same_refusal_on_both_surfaces() throws Exception {
    String asked =
        """
                {"maxModelCalls": 40, "maxTurns": 30, "noTurnCap": true}""";

    MockHttpServletResponse http = posted("/v1/conversations", asked);
    Outcome outcome = route(FrameTypes.CONVERSATION_OPEN, asked);

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(conversations, never()).open(any(), any(), any(), any());
  }

  /**
   * A conversation that has just been opened has no name on either surface, and both say so as a
   * null rather than as a word.
   *
   * <p><b>{@code title} is on this answer even though it can only ever be null here</b>, which is
   * the opposite call from the one {@code modelCallsSpent} got and is made for the same reason.
   * That field was withheld from {@code POST} because a number a client could poll would invite
   * polling; a null is not a number and invites nothing. What it does do is tell a client that the
   * field exists and is empty — so a console that renders a listing and an opening through one type
   * reads one shape from both, instead of learning on the open answer that a key it knows is
   * missing and having to decide whether that means "no name" or "this server is too old to have
   * names".
   */
  @Test
  void both_surfaces_open_a_conversation_that_has_no_name_yet() throws Exception {
    when(conversations.open(any(), any(), any(), any())).thenReturn(row("cnv_1", PAYMENTS, 12));
    String asked =
        """
                {"project": "payments", "maxModelCalls": 12}""";

    MockHttpServletResponse http =
        mvc.perform(
                post("/v1/conversations").contentType(MediaType.APPLICATION_JSON).content(asked))
            .andExpect(jsonPath("$.title").value(nullValue()))
            .andReturn()
            .getResponse();
    Outcome outcome = route(FrameTypes.CONVERSATION_OPEN, asked);

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
  }

  /** Both surfaces open a person's conversation to log.open, and tell log stages of a move. */
  @Test
  void both_surfaces_tell_the_log_stages_what_they_did() throws Exception {
    when(conversations.open(any(), any(), any(), any())).thenReturn(row("cnv_1", PAYMENTS, 12));
    when(conversations.moveTo("cnv_1", ConversationLifecycle.ARCHIVED))
        .thenReturn(archived("cnv_1"));

    posted(
        "/v1/conversations",
        """
                {"project": "payments"}""");
    route(
        FrameTypes.CONVERSATION_OPEN,
        """
                {"project": "payments"}""");
    mvc.perform(
        put("/v1/conversations/cnv_1/lifecycle")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                        {"lifecycle": "archived"}"""));
    route(
        FrameTypes.CONVERSATION_LIFECYCLE,
        """
                {"conversation": "cnv_1", "lifecycle": "archived"}""");

    assertEquals(
        List.of(
            "opened turn cnv_1",
            "opened turn cnv_1",
            "moved cnv_1 archived",
            "moved cnv_1 archived"),
        told.lines);
  }

  /**
   * Spec 2026-09-30-local-hooks-are-served decision 4: the socket's session, and none over REST.
   */
  @Test
  void a_conversation_opened_over_the_socket_names_its_session_and_one_over_rest_none()
      throws Exception {
    when(conversations.open(any(), any(), any(), any())).thenReturn(row("cnv_1", PAYMENTS, 12));

    posted(
        "/v1/conversations",
        """
                {"project": "payments"}""");
    route(
        FrameTypes.CONVERSATION_OPEN,
        """
                {"project": "payments"}""");

    assertNull(told.opened.get(0).session());
    assertEquals("session-1", told.opened.get(1).session());
  }

  // --- conversation.list ---------------------------------------------------

  /** One tier's conversations, read the same way by both surfaces. */
  @Test
  void both_surfaces_list_the_same_tier() throws Exception {
    when(conversations.inHome(PAYMENTS, ConversationLifecycle.ARCHIVED))
        .thenReturn(List.of(row("cnv_1", PAYMENTS, 12)));

    MockHttpServletResponse http =
        mvc.perform(
                get("/v1/conversations")
                    .param("project", "payments")
                    .param("lifecycle", "archived"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_LIST,
            """
                {"project": "payments", "lifecycle": "archived"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(conversations, times(2)).inHome(PAYMENTS, ConversationLifecycle.ARCHIVED);
  }

  /** A lifecycle nothing spells is refused in the same words, and nothing is listed. */
  @Test
  void a_lifecycle_nothing_spells_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http =
        mvc.perform(get("/v1/conversations").param("lifecycle", "asleep"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_LIST,
            """
                {"lifecycle": "asleep"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(conversations, never()).inHome(any(), any());
  }

  /**
   * The name the first turn gave a conversation reaches both surfaces, and it is the same name.
   *
   * <p><b>The {@code jsonPath} beside the parity assertion is not redundant.</b> {@link
   * FrameParity#assertSameAnswer} compares two surfaces against each other and says nothing about
   * what either of them carries, so it passes for two surfaces that both dropped the field — which
   * is exactly the state this task started in. One assertion says the title is really on the wire;
   * the other says the frame carries the identical answer.
   */
  @Test
  void both_surfaces_carry_the_name_a_conversation_was_given() throws Exception {
    when(conversations.inHome(PAYMENTS, ConversationLifecycle.ACTIVE))
        .thenReturn(List.of(named("cnv_1", "why did the deploy roll back?")));

    MockHttpServletResponse http =
        mvc.perform(get("/v1/conversations").param("project", "payments"))
            .andExpect(jsonPath("$[0].title").value("why did the deploy roll back?"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_LIST,
            """
                {"project": "payments"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
  }

  /**
   * A conversation nothing has named is listed with no name on either surface, and neither invents
   * one.
   *
   * <p><b>This is the common row and not the edge.</b> There was no backfill, so every conversation
   * opened before the title column has a null one permanently, and so does one opened and never
   * spoken into — the listing a person opens on today is mostly these. A surface that answered
   * {@code "Untitled"} here would be a third name for a row beside the id and the real title, which
   * is the invention this whole change exists to stop.
   */
  @Test
  void a_conversation_nothing_has_named_is_listed_with_no_name_on_both_surfaces() throws Exception {
    when(conversations.inHome(PAYMENTS, ConversationLifecycle.ACTIVE))
        .thenReturn(List.of(named("cnv_1", null)));

    MockHttpServletResponse http =
        mvc.perform(get("/v1/conversations").param("project", "payments"))
            .andExpect(jsonPath("$[0].title").value(nullValue()))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_LIST,
            """
                {"project": "payments"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
  }

  // --- conversation.latest --------------------------------------------------

  //
  // THE ONE SECTION IN THIS FILE WITH NO SECOND SURFACE IN IT, AND THAT IS
  // THE THING TO CHECK FIRST. Every other section drives one request twice
  // and compares; `conversation.latest` mirrors no endpoint — see
  // FrameTypes.CONVERSATION_LATEST, which argues that a tier's listing is a
  // different question from "which one am I carrying on with", and
  // Capabilities, which records the absence in the register rather than
  // leaving it to be found. So what these cases assert is what the frame
  // answers and which method it asks: there is nothing to compare it against,
  // and a parity assertion here would be comparing a surface with itself.
  //

  /** The conversation the agent is still having, read through the service the rule lives in. */
  @Test
  void the_frame_answers_the_conversation_this_agent_is_still_having() {
    when(conversations.latestFor(PAYMENTS, "aristoxenus"))
        .thenReturn(Optional.of(named("cnv_1", "how many modules are there")));

    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_LATEST,
            """
                {"project": "payments", "agent": "aristoxenus"}""");

    assertEquals(Code.OK, outcome.code());
    assertEquals(
        new ConversationView(
            "cnv_1", "payments", 12, 0, null, false, false, "how many modules are there"),
        outcome.payload(),
        "and it is the view both other conversation frames answer with, not a shape"
            + " this handler invented for one client");
  }

  /**
   * The tier is the payload's and not the global one.
   *
   * <p>The mutation this catches is the one a sweep of nine handlers found across this surface: a
   * handler that ignored its payload's {@code project} reads another tier, and a case driven with
   * no project at all cannot tell {@code RequestedHome.in(null)} from a hard-coded {@link
   * Home#global()}. Both are driven here for that reason.
   */
  @Test
  void the_tier_it_looks_in_is_the_one_the_payload_named() {
    when(conversations.latestFor(any(), any())).thenReturn(Optional.empty());

    route(
        FrameTypes.CONVERSATION_LATEST,
        """
                {"project": "payments", "agent": "aristoxenus"}""");
    route(
        FrameTypes.CONVERSATION_LATEST,
        """
                {"agent": "aristoxenus"}""");

    verify(conversations).latestFor(PAYMENTS, "aristoxenus");
    verify(conversations).latestFor(Home.global(), "aristoxenus");
  }

  /**
   * An agent with no conversation here gets an {@code OK} with nothing in it, and nothing is opened
   * on its behalf.
   *
   * <p><b>An ordinary state and not a 404.</b> It is every first run, and the client's answer to it
   * is to open a conversation when somebody speaks — so a refusal here would turn the ordinary case
   * into an error, and a handler that opened one to have something to answer with would put a row
   * behind every start of a terminal.
   */
  @Test
  void an_agent_with_no_conversation_here_is_answered_with_nothing_rather_than_refused() {
    when(conversations.latestFor(PAYMENTS, "aristoxenus")).thenReturn(Optional.empty());

    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_LATEST,
            """
                {"project": "payments", "agent": "aristoxenus"}""");

    assertEquals(Code.OK, outcome.code());
    assertNull(
        outcome.payload(),
        "which travels as {\"code\":\"OK\"} with no payload key at all, since Outcome is"
            + " NON_NULL — an absence a client can tell from a refusal by the code");
    verify(conversations, never()).open(any(), any());
  }

  /**
   * A payload naming no agent is refused before anything is read.
   *
   * <p><b>The refusal has to come before the query</b>, which is why the agent is not a component
   * of the handler's record: a blank name matches no turn, so the store would answer "no
   * conversation yet" to a caller that named nobody — and a client opens a second conversation on
   * the strength of that answer, every time it starts.
   */
  @Test
  void a_payload_naming_no_agent_is_a_caller_fault_rather_than_an_empty_answer() {
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_LATEST,
            """
                {"project": "payments"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    assertNotNull(outcome.said(), "and says which field was missing");
    verify(conversations, never()).latestFor(any(), any());
  }

  // --- conversation.lifecycle ----------------------------------------------

  /** Both surfaces move the same row to the same state. */
  @Test
  void both_surfaces_move_the_same_conversation_to_the_same_state() throws Exception {
    when(conversations.moveTo("cnv_1", ConversationLifecycle.ARCHIVED))
        .thenReturn(archived("cnv_1"));

    MockHttpServletResponse http =
        mvc.perform(
                put("/v1/conversations/cnv_1/lifecycle")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                                {"lifecycle": "archived"}"""))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_LIFECYCLE,
            """
                {"conversation": "cnv_1", "lifecycle": "archived"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(conversations, times(2)).moveTo("cnv_1", ConversationLifecycle.ARCHIVED);
  }

  /**
   * A move the transition table does not allow is a 409 in the same words on both surfaces — the
   * store's refusal, reaching two surfaces through one {@code Faults} row.
   */
  @Test
  void a_move_the_table_refuses_is_the_same_conflict_on_both_surfaces() throws Exception {
    when(conversations.moveTo("cnv_1", ConversationLifecycle.ARCHIVED))
        .thenThrow(
            new ArchiveRefusedException(
                "conversation cnv_1 is already archived, and only an active conversation"
                    + " may be archived"));

    MockHttpServletResponse http =
        mvc.perform(
                put("/v1/conversations/cnv_1/lifecycle")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                                {"lifecycle": "archived"}"""))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_LIFECYCLE,
            """
                {"conversation": "cnv_1", "lifecycle": "archived"}""");

    assertEquals(Code.CONFLICT, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  /**
   * A payload naming no conversation is the caller's mistake and says so.
   *
   * <p>Only the frame is driven, and that is a fact about the two surfaces rather than a gap: the
   * endpoint takes its id from the path, so there is no such URL to call. {@code Faults} is still
   * the one table deciding this is a 400.
   */
  @Test
  void a_lifecycle_payload_naming_no_conversation_is_a_caller_fault() {
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_LIFECYCLE,
            """
                {"lifecycle": "archived"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    assertNotNull(outcome.said(), "and says what was missing");
    verify(conversations, never()).moveTo(any(), any());
  }

  // --- conversation.compactions --------------------------------------------

  /** Both surfaces read the same seams back. */
  @Test
  void both_surfaces_read_the_same_seams() throws Exception {
    exists("cnv_1");
    when(compactions.forConversation("cnv_1"))
        .thenReturn(List.of(new CompactionRecord("cnv_1", 4, "what was said before")));

    MockHttpServletResponse http = read("/v1/conversations/cnv_1/compactions");
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_COMPACTIONS,
            """
                {"conversation": "cnv_1"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(compactions, times(2)).forConversation("cnv_1");
  }

  /**
   * An id nothing opened is a 404 in the same words, and neither surface reads the seams.
   *
   * <p><b>The sentence is the assertion.</b> "There is no transcript to read the seams of" is not a
   * phrase any {@code requireExists(id, noun)} could build, which is why this reading and {@code
   * turns} went through the wider door — and why a handler that spelled its own would have been a
   * 404 in different words.
   */
  @Test
  void a_conversation_nothing_opened_has_no_seams_in_the_same_words() throws Exception {
    when(conversations.find("cnv_nope")).thenReturn(Optional.empty());

    MockHttpServletResponse http = read("/v1/conversations/cnv_nope/compactions");
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_COMPACTIONS,
            """
                {"conversation": "cnv_nope"}""");

    assertEquals(Code.NOT_FOUND, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertEquals(
        "no conversation has the id cnv_nope, so there is no transcript to read" + " the seams of",
        outcome.said());
    verify(compactions, never()).forConversation(any());
  }

  // --- conversation.chat ---------------------------------------------------

  /** Both surfaces page the projection the same way, and the page says which window it used. */
  @Test
  void both_surfaces_page_the_same_chat() throws Exception {
    exists("cnv_1");
    when(entries.pageOfProjection("cnv_1", 5, 2)).thenReturn(aPage());

    MockHttpServletResponse http =
        mvc.perform(get("/v1/conversations/cnv_1/chat").param("offset", "5").param("limit", "2"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_CHAT,
            """
                {"conversation": "cnv_1", "offset": 5, "limit": 2}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(entries, times(2)).pageOfProjection("cnv_1", 5, 2);
  }

  /**
   * A page that could hold nothing is refused in the same words, before either surface asks whether
   * the conversation exists.
   *
   * <p><b>The order is part of the answer.</b> Both surfaces read the window first, so a bad limit
   * on an id nobody opened is a 400 about the limit and not a 404 about the id — and a handler that
   * checked existence first would answer a different status for the same request while agreeing on
   * every other test in this section.
   */
  @Test
  void a_page_that_could_hold_nothing_is_the_same_refusal_on_both_surfaces() throws Exception {
    when(conversations.find(any())).thenReturn(Optional.empty());

    MockHttpServletResponse http =
        mvc.perform(get("/v1/conversations/cnv_nope/chat").param("limit", "0"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_CHAT,
            """
                {"conversation": "cnv_nope", "limit": 0}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(entries, never()).pageOfProjection(any(), anyInt(), anyInt());
  }

  /** A limit past the cap is narrowed, not refused, on both surfaces. */
  @Test
  void a_limit_past_the_cap_is_narrowed_the_same_way_on_both_surfaces() throws Exception {
    exists("cnv_1");
    when(entries.pageOfProjection("cnv_1", 0, ConversationController.MOST_ENTRIES_A_PAGE))
        .thenReturn(aPage());

    MockHttpServletResponse http =
        mvc.perform(get("/v1/conversations/cnv_1/chat").param("limit", "100000"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_CHAT,
            """
                {"conversation": "cnv_1", "limit": 100000}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(entries, times(2))
        .pageOfProjection("cnv_1", 0, ConversationController.MOST_ENTRIES_A_PAGE);
  }

  // --- conversation.trajectory ---------------------------------------------

  /** Both surfaces page the log the same way. */
  @Test
  void both_surfaces_page_the_same_trajectory() throws Exception {
    exists("cnv_1");
    when(entries.pageOfLog("cnv_1", 0, ConversationController.MOST_ENTRIES_A_PAGE))
        .thenReturn(aPage());

    MockHttpServletResponse http = read("/v1/conversations/cnv_1/trajectory");
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_TRAJECTORY,
            """
                {"conversation": "cnv_1"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(entries, times(2)).pageOfLog("cnv_1", 0, ConversationController.MOST_ENTRIES_A_PAGE);
  }

  /** An offset before the beginning is refused in the same words. */
  @Test
  void an_offset_before_the_beginning_is_the_same_refusal_on_both_surfaces() throws Exception {
    exists("cnv_1");

    MockHttpServletResponse http =
        mvc.perform(get("/v1/conversations/cnv_1/trajectory").param("offset", "-1"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_TRAJECTORY,
            """
                {"conversation": "cnv_1", "offset": -1}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(entries, never()).pageOfLog(any(), anyInt(), anyInt());
  }

  /** An id nothing opened is a 404 in the same words, naming this reading. */
  @Test
  void a_trajectory_of_a_conversation_nothing_opened_is_the_same_404() throws Exception {
    when(conversations.find("cnv_nope")).thenReturn(Optional.empty());

    MockHttpServletResponse http = read("/v1/conversations/cnv_nope/trajectory");
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_TRAJECTORY,
            """
                {"conversation": "cnv_nope"}""");

    assertEquals(Code.NOT_FOUND, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertEquals(
        "no conversation has the id cnv_nope, so there is no trajectory to read", outcome.said());
  }

  /** Both surfaces read what came after an ordinal the same way, through the same read. */
  @Test
  void both_surfaces_read_the_log_after_an_ordinal() throws Exception {
    exists("cnv_1");
    when(entries.pageOfLogAfter(
            "cnv_1",
            40,
            EntryStore.EVERY_KIND,
            false,
            0,
            ConversationController.MOST_ENTRIES_A_PAGE))
        .thenReturn(aPage());

    MockHttpServletResponse http =
        mvc.perform(get("/v1/conversations/cnv_1/trajectory").param("after", "40"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_TRAJECTORY,
            """
                {"conversation": "cnv_1", "after": 40}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(entries, times(2))
        .pageOfLogAfter(
            "cnv_1",
            40,
            EntryStore.EVERY_KIND,
            false,
            0,
            ConversationController.MOST_ENTRIES_A_PAGE);
    verify(entries, never()).pageOfLog(any(), anyInt(), anyInt());
  }

  /** An ordinal before the first is refused in the same words, and nothing is read. */
  @Test
  void an_ordinal_before_the_first_is_the_same_refusal_on_both_surfaces() throws Exception {
    exists("cnv_1");

    MockHttpServletResponse http =
        mvc.perform(get("/v1/conversations/cnv_1/trajectory").param("after", "-1"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_TRAJECTORY,
            """
                {"conversation": "cnv_1", "after": -1}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(entries, never())
        .pageOfLogAfter(any(), anyInt(), any(), anyBoolean(), anyInt(), anyInt());
  }

  /**
   * The tail, read backwards and narrowed to what a chat draws, is one read on both surfaces, and
   * both say where to go back from and whether there is anywhere to go.
   */
  @Test
  void both_surfaces_read_the_tail_backwards_narrowed_to_some_kinds() throws Exception {
    exists("cnv_1");
    when(entries.pageOfLogBefore("cnv_1", EntryStore.FROM_THE_END, DRAWN, false, 0, 40))
        .thenReturn(aBackwardsPage());

    MockHttpServletResponse http =
        mvc.perform(
                get("/v1/conversations/cnv_1/trajectory")
                    .param("tail", "true")
                    .param("limit", "40")
                    .param("kinds", "utterance", "answer", "summary"))
            .andExpect(jsonPath("$.oldest").value(7))
            .andExpect(jsonPath("$.more").value(true))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_TRAJECTORY,
            """
                {"conversation": "cnv_1", "tail": true, "limit": 40,
                 "kinds": ["utterance", "answer", "summary"]}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(entries, times(2))
        .pageOfLogBefore("cnv_1", EntryStore.FROM_THE_END, DRAWN, false, 0, 40);
  }

  /** Leaving out the answers that asked for tools is one read on both surfaces. */
  @Test
  void both_surfaces_leave_out_the_answers_no_chat_draws() throws Exception {
    exists("cnv_1");
    when(entries.pageOfLogBefore("cnv_1", EntryStore.FROM_THE_END, DRAWN, true, 0, 40))
        .thenReturn(aBackwardsPage());
    when(entries.pageOfLogAfter(
            "cnv_1", 0, EntryStore.EVERY_KIND, true, 0, ConversationController.MOST_ENTRIES_A_PAGE))
        .thenReturn(aPage());

    MockHttpServletResponse http =
        mvc.perform(
                get("/v1/conversations/cnv_1/trajectory")
                    .param("tail", "true")
                    .param("limit", "40")
                    .param("kinds", "utterance", "answer", "summary")
                    .param("drawn", "true"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_TRAJECTORY,
            """
                {"conversation": "cnv_1", "tail": true, "limit": 40,
                 "kinds": ["utterance", "answer", "summary"], "drawn": true}""");
    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);

    // Alone, on a reading that is otherwise the plain log, it is still the narrower read.
    MockHttpServletResponse plain =
        mvc.perform(get("/v1/conversations/cnv_1/trajectory").param("drawn", "true"))
            .andReturn()
            .getResponse();
    Outcome narrowed =
        route(
            FrameTypes.CONVERSATION_TRAJECTORY,
            """
                {"conversation": "cnv_1", "drawn": true}""");
    assertEquals(Code.OK, narrowed.code());
    FrameParity.assertSameAnswer(plain, narrowed);

    verify(entries, times(2)).pageOfLogBefore("cnv_1", EntryStore.FROM_THE_END, DRAWN, true, 0, 40);
    verify(entries, times(2))
        .pageOfLogAfter(
            "cnv_1", 0, EntryStore.EVERY_KIND, true, 0, ConversationController.MOST_ENTRIES_A_PAGE);
    verify(entries, never()).pageOfLog(any(), anyInt(), anyInt());
  }

  /** A drawn that is not a yes or a no is refused on both surfaces, and nothing is read. */
  @Test
  void a_drawn_that_is_not_a_boolean_is_refused_on_both_surfaces() throws Exception {
    exists("cnv_1");

    MockHttpServletResponse http =
        mvc.perform(get("/v1/conversations/cnv_1/trajectory").param("drawn", "maybe"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_TRAJECTORY,
            """
                {"conversation": "cnv_1", "drawn": "maybe"}""");

    assertEquals(400, http.getStatus());
    assertEquals(Code.BAD_REQUEST, outcome.code());
    verify(entries, never())
        .pageOfLogAfter(any(), anyInt(), any(), anyBoolean(), anyInt(), anyInt());
    verify(entries, never()).pageOfLog(any(), anyInt(), anyInt());
  }

  /** Going further back names the ordinal the last page ended at. */
  @Test
  void both_surfaces_read_back_from_an_ordinal() throws Exception {
    exists("cnv_1");
    when(entries.pageOfLogBefore("cnv_1", 7, DRAWN, false, 0, 40)).thenReturn(aBackwardsPage());

    MockHttpServletResponse http =
        mvc.perform(
                get("/v1/conversations/cnv_1/trajectory")
                    .param("before", "7")
                    .param("limit", "40")
                    .param("kinds", "utterance", "answer", "summary"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_TRAJECTORY,
            """
                {"conversation": "cnv_1", "before": 7, "limit": 40,
                 "kinds": ["utterance", "answer", "summary"]}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(entries, times(2)).pageOfLogBefore("cnv_1", 7, DRAWN, false, 0, 40);
  }

  /** A catch-up narrowed to what a chat draws: the rows are narrowed, the reading is not. */
  @Test
  void both_surfaces_narrow_a_reading_after_an_ordinal_by_kind() throws Exception {
    exists("cnv_1");
    when(entries.pageOfLogAfter(
            "cnv_1", 40, DRAWN, false, 0, ConversationController.MOST_ENTRIES_A_PAGE))
        .thenReturn(aPage());

    MockHttpServletResponse http =
        mvc.perform(
                get("/v1/conversations/cnv_1/trajectory")
                    .param("after", "40")
                    .param("kinds", "utterance", "answer", "summary"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_TRAJECTORY,
            """
                {"conversation": "cnv_1", "after": 40,
                 "kinds": ["utterance", "answer", "summary"]}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(entries, times(2))
        .pageOfLogAfter("cnv_1", 40, DRAWN, false, 0, ConversationController.MOST_ENTRIES_A_PAGE);
  }

  /**
   * Each request that names two readings, or a reading nothing can hold, is the same refusal on
   * both surfaces, and nothing is read.
   */
  @Test
  void a_reading_that_cannot_be_one_is_the_same_refusal_on_both_surfaces() throws Exception {
    exists("cnv_1");
    refusedAlike(
        get("/v1/conversations/cnv_1/trajectory").param("before", "7").param("after", "3"),
        """
                {"conversation": "cnv_1", "before": 7, "after": 3}""",
        "'after'");
    refusedAlike(
        get("/v1/conversations/cnv_1/trajectory").param("tail", "true").param("after", "3"),
        """
                {"conversation": "cnv_1", "tail": true, "after": 3}""",
        "'tail'");
    refusedAlike(
        get("/v1/conversations/cnv_1/trajectory").param("before", "0"),
        """
                {"conversation": "cnv_1", "before": 0}""",
        "1 or later");
    refusedAlike(
        get("/v1/conversations/cnv_1/trajectory").param("tail", "true").param("before", "7"),
        """
                {"conversation": "cnv_1", "tail": true, "before": 7}""",
        "Name one");
    refusedAlike(
        get("/v1/conversations/cnv_1/trajectory").param("kinds", "daydream"),
        """
                {"conversation": "cnv_1", "kinds": ["daydream"]}""",
        "'daydream'");

    verify(entries, never())
        .pageOfLogBefore(any(), anyInt(), any(), anyBoolean(), anyInt(), anyInt());
    verify(entries, never())
        .pageOfLogAfter(any(), anyInt(), any(), anyBoolean(), anyInt(), anyInt());
    verify(entries, never()).pageOfLog(any(), anyInt(), anyInt());
  }

  private void refusedAlike(MockHttpServletRequestBuilder http, String frame, String saying)
      throws Exception {
    MockHttpServletResponse refused = mvc.perform(http).andReturn().getResponse();
    Outcome outcome = route(FrameTypes.CONVERSATION_TRAJECTORY, frame);

    assertEquals(Code.BAD_REQUEST, outcome.code(), frame);
    FrameParity.assertSameRefusal(refused, outcome);
    assertTrue(outcome.said().contains(saying), outcome.said());
  }

  // --- conversation.context ------------------------------------------------

  /** Both surfaces price the same conversation against the same agent. */
  @Test
  void both_surfaces_price_the_same_prompt() throws Exception {
    exists("cnv_1");
    when(turns.forConversation("cnv_1")).thenReturn(List.of(spoken(1, "talker", 900)));

    MockHttpServletResponse http = read("/v1/conversations/cnv_1/context");
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_CONTEXT,
            """
                {"conversation": "cnv_1"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
  }

  /**
   * A conversation nobody has spoken into and that names no agent is priced without a prefix on
   * both surfaces — the ordinary answer, and the one {@code projection}'s refusal below must stay
   * distinct from.
   */
  @Test
  void a_conversation_with_no_agent_is_priced_without_a_prefix_on_both_surfaces() throws Exception {
    exists("cnv_1");
    when(turns.forConversation("cnv_1")).thenReturn(List.of());

    MockHttpServletResponse http = read("/v1/conversations/cnv_1/context");
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_CONTEXT,
            """
                {"conversation": "cnv_1"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
  }

  /**
   * A bot the asking session's own machine defines is priced on the socket, against the
   * conversation's project and that session.
   *
   * <p><b>Measured before it was written:</b> a laptop's {@code .plowshare/bots/cathy.md} answered
   * every turn of {@code cnv_313C33636A926A69} and this frame refused her by name, because it
   * looked her up in the boot set — which no client tier is ever in. A run and {@code agent.list}
   * both resolve for the caller; this now does too. The REST route keeps the boot set, for {@code
   * AgentController.agents}' reason: it names no session and must not reach one.
   */
  @Test
  void a_bot_the_asking_session_s_machine_defines_is_priced_on_the_socket() {
    exists("cnv_1");
    when(turns.forConversation("cnv_1")).thenReturn(List.of(spoken(1, "cathy", 9_602)));
    DefinitionResolver.Caller hers = new DefinitionResolver.Caller(10L, "session-1");
    when(callers.callerForConversation("cnv_1", "session-1")).thenReturn(hers);
    // doReturn, because when(...) would call the default answer, which refuses
    // her — the boot set is exactly what does not hold her.
    org.mockito.Mockito.doReturn(
            new AgentDefinition(
                "cathy",
                "a local bot",
                "fast",
                List.of(),
                List.of(),
                List.of(),
                2,
                4,
                "Hi.",
                true,
                true))
        .when(callers)
        .readAgent("cathy", hers);

    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_CONTEXT,
            """
                {"conversation": "cnv_1"}""");

    assertEquals(Code.OK, outcome.code());
    ContextView view = (ContextView) outcome.payload();
    assertEquals(9_602, view.sent());
    assertEquals("cathy", view.prefix().agent());
  }

  /** An agent nothing is called is refused in the same words, naming the agents there are. */
  @Test
  void an_agent_nothing_is_called_is_the_same_refusal_on_both_surfaces() throws Exception {
    exists("cnv_1");
    when(turns.forConversation("cnv_1")).thenReturn(List.of());

    MockHttpServletResponse http =
        mvc.perform(get("/v1/conversations/cnv_1/context").param("agent", "nobody"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_CONTEXT,
            """
                {"conversation": "cnv_1", "agent": "nobody"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  // --- conversation.projection ---------------------------------------------

  /** Both surfaces assemble the same next prompt. */
  @Test
  void both_surfaces_assemble_the_same_next_prompt() throws Exception {
    exists("cnv_1");
    when(turns.forConversation("cnv_1")).thenReturn(List.of(spoken(1, "talker", 900)));
    when(compaction.projectionFor(eq("cnv_1"), any()))
        .thenReturn(
            List.of(
                ChatMessage.system("You do one thing."), ChatMessage.user("what is the balance")));

    MockHttpServletResponse http = read("/v1/conversations/cnv_1/projection");
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_PROJECTION,
            """
                {"conversation": "cnv_1"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(compaction, times(2)).projectionFor(eq("cnv_1"), any());
  }

  /**
   * A conversation with no turn and no agent named is refused in the same words.
   *
   * <p><b>This is the refusal Task 1 retired out of the controller.</b> It was an inline {@code
   * BadRequestException} arguing from what the conversation records — so a handler calling the
   * service would have gone without it, and answered an agent-less projection with a 500 out of
   * {@code RequestedAgent}. {@code Conversations.whoToProjectAs} is where it lives now, and this is
   * what holds the two readings of it together.
   */
  @Test
  void a_projection_with_no_agent_to_name_is_the_same_refusal_on_both_surfaces() throws Exception {
    exists("cnv_1");
    when(turns.forConversation("cnv_1")).thenReturn(List.of());

    MockHttpServletResponse http = read("/v1/conversations/cnv_1/projection");
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_PROJECTION,
            """
                {"conversation": "cnv_1"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertEquals(
        "conversation cnv_1 has had no turn and names no agent, so there is no"
            + " agent whose prompt this next turn's projection would open with."
            + " Name one with 'agent' to ask what it would send.",
        outcome.said());
    verify(compaction, never()).projectionFor(any(), any());
  }

  /**
   * A turn the conversation never reached is refused in the same words, naming the turns there are.
   */
  @Test
  void a_turn_that_never_happened_is_the_same_refusal_on_both_surfaces() throws Exception {
    exists("cnv_1");
    when(turns.forConversation("cnv_1")).thenReturn(List.of(spoken(1, "talker", 900)));

    MockHttpServletResponse http =
        mvc.perform(get("/v1/conversations/cnv_1/projection").param("turn", "40"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_PROJECTION,
            """
                {"conversation": "cnv_1", "turn": 40}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(compaction, never()).projectionAsOf(any(), any(), anyInt());
  }

  // --- conversation.search -------------------------------------------------

  /** Both surfaces search one tier's log the same way. */
  @Test
  void both_surfaces_search_the_same_tier() throws Exception {
    when(entries.search(PAYMENTS, "budget", 0, ConversationController.MOST_ENTRIES_A_PAGE))
        .thenReturn(aHit());

    MockHttpServletResponse http =
        mvc.perform(get("/v1/entries/search").param("q", "budget").param("project", "payments"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_SEARCH,
            """
                {"q": "budget", "project": "payments"}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(entries, times(2))
        .search(PAYMENTS, "budget", 0, ConversationController.MOST_ENTRIES_A_PAGE);
  }

  /**
   * A search naming a window is searched through that window on both surfaces, and not through the
   * default one.
   *
   * <p><b>The tier test above names no window</b>, so the skip and the page it verifies are the
   * ones {@code RequestedWindow.in(null, null)} answers — the same two numbers a handler that never
   * read {@code offset} or {@code limit} would pass. The refusals in this section do not close it
   * either: each is refused on the question, before the window is built. A frame that paged from
   * the beginning whatever its payload asked for would have looked correct from every angle this
   * file had.
   */
  @Test
  void both_surfaces_search_through_the_same_window() throws Exception {
    when(entries.search(PAYMENTS, "budget", 4, 2)).thenReturn(aHit());

    MockHttpServletResponse http =
        mvc.perform(
                get("/v1/entries/search")
                    .param("q", "budget")
                    .param("project", "payments")
                    .param("offset", "4")
                    .param("limit", "2"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_SEARCH,
            """
                {"q": "budget", "project": "payments", "offset": 4, "limit": 2}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(entries, times(2)).search(PAYMENTS, "budget", 4, 2);
  }

  /**
   * A search with no question is refused in the same words, and nothing is searched.
   *
   * <p><b>The other refusal Task 1 retired</b>, into {@code requests.RequestedLogQuestion}: the
   * empty string is what an unset field arrives as, and a search that read it as "no filter" would
   * answer with a page of the whole tier under a question nobody asked.
   */
  @Test
  void a_search_for_nothing_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http =
        mvc.perform(get("/v1/entries/search").param("q", "   ")).andReturn().getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_SEARCH,
            """
                {"q": "   "}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertTrue(outcome.said().contains("'q'"), "and names the field: " + outcome.said());
    verify(entries, never()).search(any(), any(), anyInt(), anyInt());
  }

  /**
   * The question is read before the window is, so a search that asked nothing and asked for no page
   * is told about the question.
   */
  @Test
  void a_search_reads_the_question_before_the_window_on_both_surfaces() throws Exception {
    MockHttpServletResponse http =
        mvc.perform(get("/v1/entries/search").param("q", "").param("limit", "0"))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_SEARCH,
            """
                {"q": "", "limit": 0}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertTrue(
        outcome.said().contains("'q'"),
        "the question is what it was told about: " + outcome.said());
  }

  // --- conversation.resume -------------------------------------------------

  /**
   * Both surfaces continue the same stopped run, and both answer 202.
   *
   * <p><b>202 and not 200, which is the whole reason {@code Code} grew {@link Code#ACCEPTED}.</b>
   * What comes back is a handle to poll, and an {@code OK} here would tell a client its work had
   * finished while it holds a job id that has not started.
   */
  @Test
  void both_surfaces_continue_the_same_run_and_both_say_accepted() throws Exception {
    when(turns.forConversation("cnv_1")).thenReturn(List.of(spoken(1, "talker", 900)));
    when(speaking.homeOf("cnv_1")).thenReturn(PAYMENTS);
    when(speaking.resume(eq("cnv_1"), any(), any(), any(), any())).thenReturn("job_7");
    String asked =
        """
                {"session": "session-1", "maxModelCalls": 20}""";

    MockHttpServletResponse http = posted("/v1/conversations/cnv_1/resume", asked);
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_RESUME,
            """
                {"conversation": "cnv_1", "session": "session-1", "maxModelCalls": 20}""");

    assertEquals(
        Code.ACCEPTED,
        outcome.code(),
        "a run that has started is 202 on both surfaces: the answer is a handle");
    assertEquals(202, http.getStatus());
    FrameParity.assertSameAnswer(http, outcome);
    // The session is named rather than left absent, and matched rather than
    // any(): it decides which machine the continued run reaches, and a body
    // that named none would make RequestedSession.in(asked.session())
    // indistinguishable from a handler that never read the field.
    verify(speaking, times(2)).resume(eq("cnv_1"), any(), eq("session-1"), any(), eq(20));
  }

  /**
   * A body naming an agent the last turn did not is refused in the same words, and nothing is
   * started.
   *
   * <p>{@code Conversations.whoToContinueAs}' refusal, which is a fact about the conversation's own
   * rows rather than about the request — so both surfaces get it by calling the same service,
   * without either restating it.
   */
  @Test
  void continuing_as_the_wrong_agent_is_the_same_refusal_on_both_surfaces() throws Exception {
    when(turns.forConversation("cnv_1")).thenReturn(List.of(spoken(1, "talker", 900)));
    String asked =
        """
                {"agent": "shadow"}""";

    MockHttpServletResponse http = posted("/v1/conversations/cnv_1/resume", asked);
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_RESUME,
            """
                {"conversation": "cnv_1", "agent": "shadow"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(speaking, never()).resume(any(), any(), any(), any(), any());
  }

  /**
   * A conversation that will not take a turn is a 409 in the same words on both surfaces — {@code
   * Turn.Refused}, through one {@code Faults} row.
   */
  @Test
  void a_conversation_that_will_not_take_a_turn_is_the_same_conflict_on_both_surfaces()
      throws Exception {
    when(turns.forConversation("cnv_1")).thenReturn(List.of(spoken(1, "talker", 900)));
    when(speaking.homeOf("cnv_1")).thenReturn(PAYMENTS);
    when(speaking.resume(eq("cnv_1"), any(), any(), any(), any()))
        .thenThrow(
            new Turn.Refused(
                "conversation cnv_1 ended by answering, and an answered run has nothing"
                    + " left to continue"));

    MockHttpServletResponse http = posted("/v1/conversations/cnv_1/resume", "{}");
    Outcome outcome =
        route(
            FrameTypes.CONVERSATION_RESUME,
            """
                {"conversation": "cnv_1"}""");

    assertEquals(Code.CONFLICT, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  // --- the surface's own two rules, for all ten types -----------------------

  /**
   * Every type this area claims ignores a field this build has never heard of — spec §3.2's "the
   * payload is tolerant", asserted once per type.
   *
   * <p><b>One test and not ten, because the assertion is the surface's rather than the
   * endpoint's.</b> {@code FrameParity.assertUnknownFieldsAreIgnored} compares two routes of the
   * same frame against each other, so it needs no stubbing: a type that refuses the payload for
   * some other reason still has to refuse both spellings identically, which is what is being
   * measured.
   */
  @Test
  void every_type_in_this_area_ignores_a_field_this_build_has_never_heard_of() throws Exception {
    for (Map.Entry<String, String> each : representativePayloads().entrySet()) {
      FrameParity.assertUnknownFieldsAreIgnored(router, each.getKey(), each.getValue());
    }
  }

  /**
   * The production routing table really does claim all eleven of this controller's types.
   *
   * <p>Built from every {@link FrameArea} Spring would collect: an unregistered type is answered
   * with a perfectly well-formed {@code NOT_FOUND}, so a handler nobody wired looks from the
   * outside exactly like a handler nobody wrote.
   */
  @Test
  void the_production_routing_table_claims_every_conversation_type() {
    FrameRouter wired = FrameAreas.router();

    for (String type : representativePayloads().keySet()) {
      assertTrue(
          wired.types().contains(type), type + " is not in the production table: " + wired.types());
    }
    assertTrue(
        wired.types().contains(FrameTypes.CONVERSATION_TURNS),
        "and the pilot's, which this task leaves alone: " + wired.types());
  }

  /** One payload per type this area adds, good enough to reach the handler. */
  private static Map<String, String> representativePayloads() {
    return Map.ofEntries(
        Map.entry(FrameTypes.CONVERSATION_OPEN, "{\"project\":\"payments\"}"),
        Map.entry(FrameTypes.CONVERSATION_LIST, "{\"project\":\"payments\"}"),
        Map.entry(
            FrameTypes.CONVERSATION_LIFECYCLE,
            "{\"conversation\":\"cnv_1\",\"lifecycle\":\"archived\"}"),
        Map.entry(FrameTypes.CONVERSATION_COMPACTIONS, "{\"conversation\":\"cnv_1\"}"),
        Map.entry(FrameTypes.CONVERSATION_CHAT, "{\"conversation\":\"cnv_1\"}"),
        Map.entry(FrameTypes.CONVERSATION_TRAJECTORY, "{\"conversation\":\"cnv_1\"}"),
        Map.entry(FrameTypes.CONVERSATION_CONTEXT, "{\"conversation\":\"cnv_1\"}"),
        Map.entry(FrameTypes.CONVERSATION_PROJECTION, "{\"conversation\":\"cnv_1\"}"),
        Map.entry(FrameTypes.CONVERSATION_SEARCH, "{\"q\":\"budget\"}"),
        Map.entry(FrameTypes.CONVERSATION_RESUME, "{\"conversation\":\"cnv_1\"}"));
  }

  // --- driving the two surfaces off one request ----------------------------

  private Outcome route(String type, String payload) {
    return router.route(FrameParity.frame(type, payload), FrameParity.ASKING);
  }

  private MockHttpServletResponse read(String path) throws Exception {
    return mvc.perform(get(path)).andReturn().getResponse();
  }

  private MockHttpServletResponse posted(String path, String body) throws Exception {
    return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn()
        .getResponse();
  }

  /** The store answers with a row for {@code id}, so the existence rule passes. */
  private void exists(String id) {
    when(conversations.find(id)).thenReturn(Optional.of(row(id, PAYMENTS, 12)));
  }

  private static ConversationRecord row(String id, Home home, int allowance) {
    return new ConversationRecord(
        id,
        home,
        Origin.TURN,
        ConversationLifecycle.ACTIVE,
        null,
        null,
        OPENED_AT,
        null,
        Budget.of(allowance),
        null,
        null);
  }

  /**
   * The same row, carrying the name its first turn gave it — or carrying null, which is what every
   * row written before there was a title column carries and is not a different kind of row.
   */
  private static ConversationRecord named(String id, String title) {
    return new ConversationRecord(
        id,
        PAYMENTS,
        Origin.TURN,
        ConversationLifecycle.ACTIVE,
        null,
        null,
        OPENED_AT,
        null,
        Budget.of(12),
        null,
        title);
  }

  /**
   * A row in the state a move answers with, which is the only field {@code LifecycleView} reads.
   */
  private static ConversationRecord archived(String id) {
    return new ConversationRecord(
        id,
        PAYMENTS,
        Origin.TURN,
        ConversationLifecycle.ARCHIVED,
        null,
        null,
        OPENED_AT,
        null,
        Budget.of(12),
        null,
        null);
  }

  private static TurnRecord spoken(int ordinal, String agent, Integer promptTokens) {
    return new TurnRecord(
        "cnv_1", ordinal, "asked", "answered", Ending.ANSWERED, promptTokens, agent, null);
  }

  /**
   * One page with one row on it, which is enough for two surfaces to render the same JSON out of.
   */
  private static final Set<EntryKind> DRAWN =
      EnumSet.of(EntryKind.UTTERANCE, EntryKind.ANSWER, EntryKind.SUMMARY);

  /** A page read backwards: newest first, with more of the log before it. */
  private static EntryPage aBackwardsPage() {
    return new EntryPage(
        List.of(
            new EntryPage.Row(
                9,
                3,
                EntryKind.ANSWER,
                "said back",
                9,
                null,
                null,
                null,
                List.of(),
                null,
                OPENED_AT,
                null),
            new EntryPage.Row(
                7,
                3,
                EntryKind.UTTERANCE,
                "what was said",
                13,
                null,
                null,
                null,
                List.of(),
                null,
                OPENED_AT,
                null)),
        12,
        9,
        true);
  }

  private static EntryPage aPage() {
    return new EntryPage(
        List.of(
            new EntryPage.Row(
                1,
                1,
                EntryKind.UTTERANCE,
                "what was said",
                13,
                null,
                null,
                null,
                List.of(),
                null,
                OPENED_AT,
                null)),
        1);
  }

  /**
   * One hit, carrying the {@code recordedAt} that makes a log search's answer a payload with a
   * timestamp in it.
   */
  private static LogSearch aHit() {
    return new LogSearch(
        List.of(
            new LogSearch.Hit(
                "cnv_1",
                1,
                1,
                EntryKind.UTTERANCE,
                0.5,
                "what was said about the budget",
                30,
                null,
                null,
                OPENED_AT)),
        1,
        new LogSearch.Reach(1, 0, 0));
  }

  /**
   * The allowance an operator configured, deliberately not the number {@code application.yml}
   * ships: a hard-coded default would pass against that one.
   */
  private static ConversationsProperties configured() {
    ConversationsProperties properties = new ConversationsProperties();
    properties.setDefaultBudget(77);
    return properties;
  }

  private static AgentDefinition agent() {
    return new AgentDefinition(
        "talker",
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

  private static ObjectProvider<AgentRegistry> providerOf(AgentRegistry available) {
    @SuppressWarnings("unchecked")
    ObjectProvider<AgentRegistry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(available);
    return provider;
  }
}
