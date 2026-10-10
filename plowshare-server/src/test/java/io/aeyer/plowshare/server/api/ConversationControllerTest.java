package io.aeyer.plowshare.server.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.ConversationDefinitions;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.agents.RecordingLogStages;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.ArchiveRefusedException;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.CompactionRecord;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.archive.EntryPage;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.LogSearch;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.llm.tokens.RatioTokenizer;
import io.aeyer.plowshare.server.requests.RequestedAgent;
import io.aeyer.plowshare.server.requests.RequestedHome;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The one door a conversation can be opened through.
 *
 * <p>Pure MVC against a mocked {@link ConversationStore}, matching {@code ProjectControllerTest}:
 * what the store does with the row is {@code ConversationStoreTest}'s subject against a real
 * database, and this class's job is narrower — does the endpoint call {@code open} with the home
 * and the allowance the body asked for, and does each refusal become the right status with nothing
 * written.
 *
 * <p><b>The reads are driven here too, and each one's own section says what it is for.</b> This
 * file used to say there were none and that their absence was the test of them; the console's
 * screens are what changed that, and {@code ConversationController} carries the argument where each
 * endpoint landed.
 */
class ConversationControllerTest {

  private static final Instant OPENED_AT = Instant.parse("2026-08-31T09:00:00Z");
  private static final Home PAYMENTS_HOME = Home.of("payments");

  private ConversationStore conversations;
  private CompactionStore compactions;
  private TurnStore turns;
  private EntryStore entries;
  private JobRuntime runtime;
  private Turn speaking;
  private MockMvc mvc;
  private final RecordingLogStages told = new RecordingLogStages();

  /**
   * The {@link Compaction} this class's controller is wired over, kept so it can be closed.
   *
   * <p>It was built per test and dropped on the floor, sixty-four times a run. {@code AgentsConfig}
   * declares this bean {@code destroyMethod = "close"} — the object owns an executor and says so —
   * and a test fixture that builds the real thing owes it the same lifecycle the container gives
   * it. See {@link #closeTheCompaction}.
   */
  private Compaction folding;

  @BeforeEach
  void setUp() {
    conversations = mock(ConversationStore.class);
    compactions = mock(CompactionStore.class);
    turns = mock(TurnStore.class);
    entries = mock(EntryStore.class);
    runtime = mock(JobRuntime.class);
    when(runtime.withAgentRules(any(), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(runtime.schemasOfferedTo(any(), any(), any(), any()))
        .thenReturn(
            List.of(
                ToolSchema.from(
                    "memory_recall",
                    "The memories nearest a question.",
                    Map.of("type", "object"))));
    speaking = mock(Turn.class);
    when(speaking.homeOf(anyString())).thenReturn(Home.of("ledger"));
    // A real Compaction over a transport that fails the suite the moment
    // anything dispatches a model call, rather than a mock of Compaction --
    // a mock would prove nothing about the rule every projection test
    // exists to hold: the projection route must compute and never send.
    // See explodingCompaction.
    folding = explodingCompaction();
    // The real registry and not a mock, on AgentControllerTest's reasoning:
    // it validates the set it is given exactly as the loader does, so a
    // fixture graph that could not exist at boot cannot be built here.
    var booted = new AgentRegistry(Map.of("talker", agent(), "shadow", privately("shadow")));
    var definitions = mock(ConversationDefinitions.class);
    when(definitions.callerForConversation(any(), any()))
        .thenAnswer(asked -> new DefinitionResolver.Caller(null, asked.getArgument(1)));
    when(definitions.readAgent(any(), any()))
        .thenAnswer(
            asked -> RequestedAgent.toRead(booted, booted::exportedNames, asked.getArgument(0)));
    when(definitions.requireAgent(any(), any()))
        .thenAnswer(
            asked -> RequestedAgent.toRun(booted, booted::exportedNames, asked.getArgument(0)));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ConversationController(
                    conversations,
                    compactions,
                    turns,
                    entries,
                    runtime,
                    speaking,
                    definitions,
                    configured(),
                    folding,
                    // The fallback, which is what a deployment with no real
                    // tokenizer gets: every count it produces says it was
                    // estimated, and these tests assert exactly that.
                    new RatioTokenizer(RatioTokenizer.DEFAULT_CHARACTERS_PER_TOKEN),
                    // The real thing over the same two mocks above, rather
                    // than a mock of it: this class's assertions are about
                    // what the controller does with what those mocks answer,
                    // and Conversations is the layer that now reads them.
                    new Conversations(conversations, turns),
                    told,
                    org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class)))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  /**
   * Give the fixture the shutdown the container gives the bean.
   *
   * <p>{@link #explodingCompaction} builds a real {@link Compaction}, which owns an executor — that
   * is why {@code AgentsConfig} declares the bean with {@code destroyMethod = "close"}. Sixty-four
   * tests in this class each built one and left it, so a run of this file leaked sixty-four of
   * them. Nothing here ever submits a fold, so nothing is being drained; what this does is stop a
   * fixture from quietly disagreeing with the lifecycle the thing it fixtures declares.
   */
  @AfterEach
  void closeTheCompaction() {
    folding.close();
  }

  @Test
  void opening_a_conversation_names_the_home_and_the_allowance_it_got() throws Exception {
    when(conversations.open(any(), any(), any(), any()))
        .thenReturn(row("cnv_1", PAYMENTS_HOME, 12));

    mvc.perform(
            post("/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"project": "payments", "maxModelCalls": 12}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("cnv_1"))
        .andExpect(jsonPath("$.project").value("payments"))
        .andExpect(jsonPath("$.maxModelCalls").value(12));

    ArgumentCaptor<Budget> allowance = ArgumentCaptor.captor();
    verify(conversations).open(eq(PAYMENTS_HOME), allowance.capture(), isNull(), isNull());
    assertEquals(12, allowance.getValue().limit());
    assertEquals(
        0,
        allowance.getValue().spent(),
        "a conversation is opened with its whole allowance unspent");
  }

  /**
   * A body naming no project opens in the global tier, which is the ordinary shape rather than a
   * degenerate one.
   *
   * <p>{@link RequestedHome#in} does exactly this for every run that names no project, and V6
   * declines a NOT NULL on the column for the same reason. A conversation that could not be opened
   * without a project would be the one thing in this server that cannot be global.
   */
  @Test
  void a_conversation_that_names_no_project_is_opened_in_the_global_tier() throws Exception {
    when(conversations.open(any(), any(), any(), any())).thenReturn(row("cnv_2", Home.global(), 5));

    mvc.perform(
            post("/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"maxModelCalls": 5}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("cnv_2"))
        .andExpect(jsonPath("$.project").doesNotExist())
        .andExpect(jsonPath("$.maxModelCalls").value(5));

    verify(conversations).open(eq(Home.global()), any(), isNull(), isNull());
  }

  // --- the turn cap a conversation may decide ----------------------------------

  /**
   * The middle of the three levels.
   *
   * <p>A conversation that names a cap is deciding it for every turn in it, over whatever each
   * answering agent's own file says — and it reads back, because a caller that could not see what
   * it got would have to remember.
   */
  @Test
  void a_conversation_can_cap_the_turns_of_every_turn_in_it() throws Exception {
    when(conversations.open(any(), any(), any(), any()))
        .thenReturn(row("cnv_3", PAYMENTS_HOME, 40, TurnCap.of(30)));

    mvc.perform(
            post("/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"project": "payments", "maxModelCalls": 40, "maxTurns": 30}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxTurns").value(30))
        .andExpect(jsonPath("$.noTurnCap").value(false));

    ArgumentCaptor<TurnCap> cap = ArgumentCaptor.captor();
    verify(conversations).open(eq(PAYMENTS_HOME), any(), cap.capture(), isNull());
    assertEquals(30, cap.getValue().turns());
  }

  /**
   * Disabled is expressible, and it is expressed as a decision.
   *
   * <p>Not as a large number: the body says {@code noTurnCap} and the answer says {@code
   * noTurnCap}, with no ceiling anywhere in either. A conversation opened this way is bounded by
   * what it costs rather than by how many turns it takes, which is the whole distinction between
   * the two limits.
   */
  @Test
  void a_conversation_can_say_its_turns_run_with_no_cap_at_all() throws Exception {
    when(conversations.open(any(), any(), any(), any()))
        .thenReturn(row("cnv_4", PAYMENTS_HOME, 40, TurnCap.none()));

    mvc.perform(
            post("/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"project": "payments", "maxModelCalls": 40, "noTurnCap": true}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.noTurnCap").value(true))
        .andExpect(jsonPath("$.maxTurns").doesNotExist());

    ArgumentCaptor<TurnCap> cap = ArgumentCaptor.captor();
    verify(conversations).open(eq(PAYMENTS_HOME), any(), cap.capture(), isNull());
    assertFalse(cap.getValue().capped());
  }

  /**
   * A conversation that says nothing about the cap is the ordinary one, and it is not the same as
   * one that said there is to be none.
   */
  @Test
  void a_conversation_that_says_nothing_about_turns_decides_nothing_about_them() throws Exception {
    when(conversations.open(any(), any(), any(), any()))
        .thenReturn(row("cnv_5", PAYMENTS_HOME, 40));

    mvc.perform(
            post("/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"project": "payments", "maxModelCalls": 40}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxTurns").doesNotExist())
        .andExpect(jsonPath("$.noTurnCap").value(false));

    verify(conversations).open(eq(PAYMENTS_HOME), any(), isNull(), isNull());
  }

  /**
   * Two answers to one question, refused rather than resolved — the same rule a body naming both a
   * conversation and a project meets one resource over.
   */
  @Test
  void a_conversation_naming_a_cap_and_lifting_it_is_refused_before_anything_is_opened()
      throws Exception {
    mvc.perform(
            post("/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"maxModelCalls": 40, "maxTurns": 30, "noTurnCap": true}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("maxTurns")))
        .andExpect(jsonPath("$.detail").value(containsString("noTurnCap")));

    verify(conversations, never()).open(any(), any(), any(), any());
  }

  @Test
  void a_turn_cap_of_no_turns_is_refused_before_anything_is_opened() throws Exception {
    mvc.perform(
            post("/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"maxModelCalls": 40, "maxTurns": 0}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("0")));

    verify(conversations, never()).open(any(), any(), any(), any());
  }

  /**
   * A body that names no allowance is opened at the operator's number rather than refused.
   *
   * <p><b>This endpoint used to answer 400 here, and the argument behind that refusal is kept
   * rather than deleted.</b> It was that "how much a person is going to say has no arithmetic
   * behind it", so a number the <em>server</em> invented would be folklore — and nothing here
   * invents one: {@link ConversationsProperties} holds an operator's, set once in the file where
   * every other cost bound on this box is set. What the refusal actually reached was a person,
   * through a console that had to put a model-call field in front of them before they could say
   * anything at all, and a person opening a conversation is not choosing a cost policy.
   */
  @Test
  void a_conversation_that_names_no_allowance_is_opened_at_the_configured_one() throws Exception {
    when(conversations.open(any(), any(), any(), any()))
        .thenReturn(row("cnv_1", PAYMENTS_HOME, 77));

    mvc.perform(
            post("/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"project": "payments"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxModelCalls").value(77));

    ArgumentCaptor<Budget> opened = ArgumentCaptor.captor();
    verify(conversations).open(eq(PAYMENTS_HOME), opened.capture(), isNull(), isNull());
    assertEquals(77, opened.getValue().limit());
  }

  /**
   * A body that does name one is honoured, which is the half of the old behaviour that has not
   * changed.
   *
   * <p>The number is still the caller's to state — the CLI's {@code conversation_open} states it,
   * and a harness that knows what a run is worth states it — and the configured one answers only
   * the body that says nothing.
   */
  @Test
  void an_allowance_the_body_names_is_taken_over_the_configured_one() throws Exception {
    when(conversations.open(any(), any(), any(), any())).thenReturn(row("cnv_1", PAYMENTS_HOME, 5));

    mvc.perform(
            post("/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"project": "payments", "maxModelCalls": 5}"""))
        .andExpect(status().isOk());

    ArgumentCaptor<Budget> opened = ArgumentCaptor.captor();
    verify(conversations).open(eq(PAYMENTS_HOME), opened.capture(), isNull(), isNull());
    assertEquals(5, opened.getValue().limit());
  }

  // --- opting out of a ceiling altogether ---------------------------------

  /**
   * V31's third state, opened through the same door: a person who wants a conversation's turns
   * bounded by cost and not by a number at all.
   */
  @Test
  void a_conversation_may_be_opened_with_no_ceiling() throws Exception {
    when(conversations.open(any(), any(), any(), any()))
        .thenReturn(liftedRow("cnv_lifted", Home.global(), 0));

    mvc.perform(
            post("/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"project": null, "noBudget": true}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.noBudget").value(true));

    ArgumentCaptor<Budget> opened = ArgumentCaptor.captor();
    verify(conversations).open(eq(Home.global()), opened.capture(), isNull(), isNull());
    assertFalse(opened.getValue().capped(), "no ceiling was invented for it");
  }

  /**
   * A row whose allowance is not its own reports neither half of it, and the spend in particular is
   * absent rather than zero.
   *
   * <p><b>Unreachable through either door and asserted anyway, because the guard that handles it is
   * not.</b> {@code ConversationView.of} is only ever handed a turn-origin row and such a row
   * always owns an allowance, so this fixture is built by hand; what the guard used to do with it
   * was report {@code modelCallsSpent: 0}, which on the wire is indistinguishable from a
   * conversation that has genuinely spent nothing. This is the one field on this record that was
   * still substituting a number for an absence.
   */
  @Test
  void a_conversation_whose_allowance_is_not_its_own_reports_no_spend_rather_than_zero()
      throws Exception {
    when(conversations.inHome(Home.of("alpha"), ConversationLifecycle.ACTIVE))
        .thenReturn(
            List.of(
                new ConversationRecord(
                    "cnv_child",
                    Home.of("alpha"),
                    Origin.TURN,
                    ConversationLifecycle.ACTIVE,
                    null,
                    null,
                    OPENED_AT,
                    null,
                    null,
                    null,
                    null)));

    mvc.perform(get("/v1/conversations").param("project", "alpha"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].maxModelCalls").value(nullValue()))
        .andExpect(jsonPath("$[0].modelCallsSpent").value(nullValue()))
        .andExpect(jsonPath("$[0].noBudget").value(false));
  }

  /**
   * The same shape {@code RequestedTurnCap} already refuses for turns: a body that says two
   * contradictory things is a caller who does not know which it wants, and guessing is how a limit
   * nobody chose gets applied.
   */
  @Test
  void asking_for_both_a_ceiling_and_no_ceiling_is_refused() throws Exception {
    mvc.perform(
            post("/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"project": null, "noBudget": true, "maxModelCalls": 40}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("maxModelCalls")))
        .andExpect(jsonPath("$.detail").value(containsString("noBudget")));

    verify(conversations, never()).open(any(), any(), any(), any());
  }

  /**
   * {@code Budget.of}'s own refusal, reported as the caller's mistake rather than as a 500: a
   * conversation that may make no model calls is one whose every turn ends at its budget before it
   * says anything.
   */
  @Test
  void an_allowance_nothing_can_be_spent_from_is_refused_and_says_what_it_got() throws Exception {
    mvc.perform(
            post("/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"project": "payments", "maxModelCalls": 0}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("0")));

    verify(conversations, never()).open(any(), any(), any(), any());
  }

  /**
   * A project that is present and blank is not the global tier.
   *
   * <p>Omitting the key says "global"; sending {@code ""} says "here is my project" and names
   * nothing, and folding one into the other would put a conversation into the tier every agent
   * everywhere reads — {@code Home.of}'s own argument, surfaced as a 400 rather than as a 500.
   */
  @Test
  void a_project_that_cannot_be_named_is_refused_rather_than_read_as_global() throws Exception {
    mvc.perform(
            post("/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"project": "   ", "maxModelCalls": 4}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("project")));

    verify(conversations, never()).open(any(), any(), any(), any());
  }

  // --- reading the seams -------------------------------------------------------

  /**
   * Every fold this conversation has had, oldest first, with the summary each one stands for.
   *
   * <p><b>Two of them and not one</b>, because "in the order they fell" is not a claim a single row
   * can fail. A conversation past its bound compacts again as it grows, and a REPL that showed only
   * the newest would tell a person the history was folded once.
   */
  @Test
  void the_folds_of_a_conversation_come_back_oldest_first_with_what_each_stands_for()
      throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(compactions.forConversation("cnv_1"))
        .thenReturn(
            List.of(
                new CompactionRecord("cnv_1", 2, "they agreed on the retry budget"),
                new CompactionRecord("cnv_1", 5, "they moved on to the deploy")));

    mvc.perform(get("/v1/conversations/cnv_1/compactions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].throughOrdinal").value(2))
        .andExpect(jsonPath("$[0].summary").value("they agreed on the retry budget"))
        .andExpect(jsonPath("$[1].throughOrdinal").value(5))
        .andExpect(jsonPath("$[1].summary").value("they moved on to the deploy"));

    verify(compactions).forConversation("cnv_1");
  }

  /**
   * Nothing folded is not the same answer as no conversation, and the status is what says which.
   *
   * <p>The ordinary case, and the one a REPL asks after every turn: most turns fold nothing. An
   * empty list means "this conversation has had no seam", and a person is told nothing because
   * nothing happened.
   */
  @Test
  void a_conversation_nothing_has_folded_answers_an_empty_list_and_not_a_404() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(compactions.forConversation("cnv_1")).thenReturn(List.of());

    mvc.perform(get("/v1/conversations/cnv_1/compactions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /**
   * An id nothing opened is a 404, not an empty list.
   *
   * <p>{@code CompactionStore.forConversation} answers an empty list for a conversation that does
   * not exist and for one that has never been folded, and those are opposite facts. Answering
   * {@code []} to the first would tell a caller that a conversation it invented has a clean
   * history.
   */
  @Test
  void a_transcript_of_a_conversation_nothing_opened_is_a_404_and_not_an_empty_history()
      throws Exception {
    when(conversations.find("cnv_nope")).thenReturn(Optional.empty());

    mvc.perform(get("/v1/conversations/cnv_nope/compactions"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value(containsString("cnv_nope")));

    verify(compactions, never()).forConversation(any());
    // Without this the test passes against a server that maps no compactions
    // endpoint at all: ApiExceptionHandler extends ResponseEntityExceptionHandler,
    // which renders NoHandlerFoundException as a ProblemDetail whose detail
    // reads "No endpoint GET /v1/conversations/cnv_nope/compactions" -- so
    // both the 404 and the containsString pass on Spring's routing rather
    // than on this controller's answer. The sibling turns test was found
    // green during its own red step for exactly this reason.
    verify(conversations).find("cnv_nope");
  }

  // --- listing what is open ----------------------------------------------------

  /**
   * A listing answers one tier, and the other tier's conversation is what says so.
   *
   * <p><b>The global row is the thing the filter has to exclude.</b> Stubbing only {@code alpha}
   * would leave this assertion passing on a listing that ignored its argument entirely — {@code
   * inHome} would be called with whatever, Mockito would answer the one stubbed list, and one row
   * would come back looking correct. Two homes with different contents is what makes the argument
   * load-bearing.
   */
  @Test
  void listing_conversations_answers_one_tier_and_not_another() throws Exception {
    when(conversations.inHome(Home.of("alpha"), ConversationLifecycle.ACTIVE))
        .thenReturn(List.of(row("cnv_a", Home.of("alpha"), 40)));
    when(conversations.inHome(Home.global(), ConversationLifecycle.ACTIVE))
        .thenReturn(List.of(row("cnv_g", Home.global(), 25)));

    mvc.perform(get("/v1/conversations").param("project", "alpha"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value("cnv_a"))
        .andExpect(jsonPath("$[0].project").value("alpha"));

    verify(conversations).inHome(Home.of("alpha"), ConversationLifecycle.ACTIVE);
    verify(conversations, never()).inHome(eq(Home.global()), any());
  }

  /**
   * An omitted project is the global tier, and not every tier.
   *
   * <p>The same reading {@code POST /v1/conversations} gives the same absent field, and the reason
   * it cannot mean "all of them": {@code Home} refuses to let global be a name a project could
   * take, so a listing that spanned tiers would have no home to put on each row and no way to say
   * which of two conversations called the same thing a person was looking at.
   */
  @Test
  void a_listing_that_names_no_project_answers_the_global_tier_and_not_every_tier()
      throws Exception {
    when(conversations.inHome(Home.global(), ConversationLifecycle.ACTIVE))
        .thenReturn(List.of(row("cnv_g", Home.global(), 25)));

    mvc.perform(get("/v1/conversations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value("cnv_g"))
        .andExpect(jsonPath("$[0].project").doesNotExist());

    verify(conversations).inHome(Home.global(), ConversationLifecycle.ACTIVE);
  }

  /**
   * A listing carries the name the first turn gave each conversation.
   *
   * <p>The whole reason the column exists: a person choosing which conversation to go back to is
   * reading this row, and {@code cnv_3134E666E2D847AD} is not a choice between two things. The
   * derivation is {@code ConversationStore}'s and is not repeated here — what is under test is that
   * the view does not drop what the row holds.
   */
  @Test
  void a_listing_carries_the_name_the_first_turn_gave_each_conversation() throws Exception {
    when(conversations.inHome(PAYMENTS_HOME, ConversationLifecycle.ACTIVE))
        .thenReturn(List.of(named("cnv_1", "why did the deploy roll back?")));

    mvc.perform(get("/v1/conversations").param("project", "payments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").value("why did the deploy roll back?"));
  }

  /**
   * A conversation nothing has named carries no name, and the field is on the wire saying so.
   *
   * <p><b>Null and not {@code "Untitled"}.</b> Every conversation opened before there was a column
   * has a null title permanently — there was no backfill — and so does one opened and never spoken
   * into, so this is the ordinary row in a listing today rather than an edge of it. A server that
   * substituted a word here would be inventing the one thing this change exists to stop being
   * invented: three clients would show three different placeholders, which is where the raw id came
   * from in the first place. A client that would rather show the id can, because the id is on the
   * same row.
   *
   * <p><b>{@code "title":null} and not an absent key</b>, asserted against the raw body because
   * {@code jsonPath} cannot tell the two apart — {@code doesNotExist()} is satisfied by a JSON
   * null, which is the trap {@code TurnView} already documents for {@code promptTokens}. The
   * present-and-empty form is what lets a client distinguish "this conversation has no name" from
   * "this server does not know about names".
   */
  @Test
  void a_conversation_nothing_has_named_carries_no_name_rather_than_an_invented_one()
      throws Exception {
    when(conversations.inHome(PAYMENTS_HOME, ConversationLifecycle.ACTIVE))
        .thenReturn(List.of(named("cnv_1", null)));

    mvc.perform(get("/v1/conversations").param("project", "payments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").value(nullValue()))
        .andExpect(content().string(containsString("\"title\":null")));
  }

  /**
   * What each conversation has already spent, which is the half a listing cannot leave out.
   *
   * <p>{@code ConversationView} deferred {@code modelCallsSpent} until a read endpoint existed, on
   * the grounds that a freshly opened conversation's spending could only ever be zero. This is that
   * endpoint, and the reason comes with it: a listing of long-running conversations that reported
   * every one of them at its opening allowance would tell a person the one with a single call left
   * is as good as new.
   */
  // --- the lifecycle ------------------------------------------------------------

  /**
   * The default listing is what a person can speak into.
   *
   * <p><b>Archiving has to bite, and the listing is the half a person sees.</b> The other half is
   * {@code Turn.speak} refusing an utterance, and neither is a substitute for the other: a state
   * that only filtered a listing would be the label the retention design warns about, and one that
   * only refused turns would leave somebody looking at conversations they had put away and could
   * not use.
   */
  @Test
  void a_listing_that_names_no_state_answers_what_is_active() throws Exception {
    when(conversations.inHome(PAYMENTS_HOME, ConversationLifecycle.ACTIVE))
        .thenReturn(List.of(row("cnv_1", PAYMENTS_HOME, 12)));

    mvc.perform(get("/v1/conversations").param("project", "payments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("cnv_1"));

    verify(conversations).inHome(PAYMENTS_HOME, ConversationLifecycle.ACTIVE);
  }

  /**
   * And what was archived is still findable, or the state is one a person can enter and not leave.
   */
  @Test
  void a_listing_can_be_asked_for_what_was_archived() throws Exception {
    when(conversations.inHome(PAYMENTS_HOME, ConversationLifecycle.ARCHIVED))
        .thenReturn(List.of(row("cnv_put_away", PAYMENTS_HOME, 12)));

    mvc.perform(
            get("/v1/conversations").param("project", "payments").param("lifecycle", "archived"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("cnv_put_away"));
  }

  /**
   * A state nothing spells is a 400 with the value quoted, and not an empty listing that would read
   * as "you have none of those".
   */
  @Test
  void a_state_nothing_spells_is_refused_rather_than_answered_with_nothing() throws Exception {
    mvc.perform(get("/v1/conversations").param("project", "payments").param("lifecycle", "archive"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("archive")));

    verify(conversations, never()).inHome(any(), any());
  }

  /**
   * One verb takes a destination, and the transition table decides whether it is reachable.
   *
   * <p>Four endpoints over one column would each have to restate that table, and a fifth would be
   * needed the day a state is added. {@code ConversationLifecycle.reachedFrom} has one home and
   * {@code ConversationStore.moveTo} splices it into its own WHERE.
   */
  @Test
  void a_conversation_is_moved_by_naming_where_it_is_to_go() throws Exception {
    when(conversations.moveTo("cnv_1", ConversationLifecycle.ARCHIVED))
        .thenReturn(archived("cnv_1"));

    mvc.perform(
            put("/v1/conversations/cnv_1/lifecycle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"lifecycle": "archived"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("cnv_1"))
        .andExpect(jsonPath("$.lifecycle").value("archived"));

    verify(conversations).moveTo("cnv_1", ConversationLifecycle.ARCHIVED);
  }

  /**
   * A move the table does not allow is a 409 and not a 404.
   *
   * <p>The row is there and the caller is wrong about it, which is what {@code
   * ArchiveRefusedException} means everywhere else in this server. A 404 would tell an operator
   * their conversation had gone.
   */
  @Test
  void a_move_the_table_does_not_allow_is_a_conflict_about_a_row_that_is_there() throws Exception {
    when(conversations.moveTo("cnv_1", ConversationLifecycle.ACTIVE))
        .thenThrow(
            new ArchiveRefusedException(
                "conversation cnv_1 is ejected and cannot become active from there"));

    mvc.perform(
            put("/v1/conversations/cnv_1/lifecycle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"lifecycle": "active"}"""))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value(containsString("ejected")));
  }

  @Test
  void moving_a_conversation_nothing_opened_is_a_404() throws Exception {
    when(conversations.moveTo("cnv_9999", ConversationLifecycle.ARCHIVED))
        .thenThrow(new ArchiveException("no conversation has the id cnv_9999"));

    mvc.perform(
            put("/v1/conversations/cnv_9999/lifecycle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"lifecycle": "archived"}"""))
        .andExpect(status().isNotFound());
  }

  @Test
  void a_listing_says_how_much_of_each_conversation_has_been_spent() throws Exception {
    when(conversations.inHome(Home.of("alpha"), ConversationLifecycle.ACTIVE))
        .thenReturn(
            List.of(
                new ConversationRecord(
                    "cnv_fresh",
                    Home.of("alpha"),
                    Origin.TURN,
                    ConversationLifecycle.ACTIVE,
                    null,
                    null,
                    OPENED_AT,
                    null,
                    Budget.of(40),
                    null,
                    null),
                new ConversationRecord(
                    "cnv_nearly_done",
                    Home.of("alpha"),
                    Origin.TURN,
                    ConversationLifecycle.ACTIVE,
                    null,
                    null,
                    OPENED_AT,
                    OPENED_AT,
                    Budget.resumed(40, 39),
                    null,
                    null)));

    mvc.perform(get("/v1/conversations").param("project", "alpha"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].maxModelCalls").value(40))
        .andExpect(jsonPath("$[0].modelCallsSpent").value(0))
        .andExpect(jsonPath("$[1].maxModelCalls").value(40))
        .andExpect(jsonPath("$[1].modelCallsSpent").value(39));
  }

  /**
   * A project that is present and blank is refused here as it is on the way in.
   *
   * <p>{@code ?project=} is exactly what a console sends when the field it reads is empty, and
   * reading it as global would show a person every global conversation under the heading of the
   * project they had selected.
   */
  @Test
  void a_listing_naming_a_blank_project_is_refused_rather_than_read_as_global() throws Exception {
    mvc.perform(get("/v1/conversations").param("project", "   "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("project")));

    verify(conversations, never()).inHome(any(), any());
  }

  /**
   * A store that could not be reached is 503, and never an empty listing.
   *
   * <p>The arm {@code ProjectControllerTest} added for its own listing, for the same reason: an
   * empty 200 says "this tier holds no conversations", which is an answer, and a database nobody
   * could reach gave none. A console that offered a person the button to open their first
   * conversation because Postgres was down would be reporting an answer nobody made.
   */
  @Test
  void a_listing_the_archive_could_not_be_reached_for_is_503_and_not_an_empty_tier()
      throws Exception {
    when(conversations.inHome(any(), any()))
        .thenThrow(
            new ArchiveUnavailableException(
                "the archive could not be reached to list a home's conversations",
                new IllegalStateException("Connection to localhost:5432 refused")));

    mvc.perform(get("/v1/conversations").param("project", "payments"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("archive_unavailable"))
        .andExpect(jsonPath("$.detail").value(containsString("list a home's conversations")));
  }

  // --- reading the history -----------------------------------------------------

  /**
   * The turns come back in the order they were said, each carrying what its prompt cost.
   *
   * <p><b>The second turn's missing measurement is the point of the pair.</b> A turn that ended at
   * its allowance never reached a model call, so nobody counted its prompt — and {@code TurnRecord}
   * argues at length that this is an absence rather than a zero, because zero is a measurement and
   * a console rendering one for the other would state the opposite of what happened. {@code
   * doesNotExist} rather than {@code value(0)} is what holds it: a view that unboxed the null into
   * an {@code int} would send {@code 0} and pass every other assertion here.
   */
  @Test
  void a_conversations_turns_come_back_in_order_with_what_each_one_cost() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 2)));
    when(turns.forConversation("cnv_1"))
        .thenReturn(
            List.of(
                new TurnRecord(
                    "cnv_1",
                    1,
                    "why did the deploy stall?",
                    "it waited on the migration lock",
                    Ending.ANSWERED,
                    1200,
                    null,
                    null),
                new TurnRecord(
                    "cnv_1",
                    2,
                    "and the one before it?",
                    "this conversation has spent its whole allowance",
                    Ending.CALL_BUDGET,
                    null,
                    null,
                    null)));

    mvc.perform(get("/v1/conversations/cnv_1/turns"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].ordinal").value(1))
        .andExpect(jsonPath("$[0].utterance").value("why did the deploy stall?"))
        .andExpect(jsonPath("$[0].answer").value("it waited on the migration lock"))
        .andExpect(jsonPath("$[0].ending").value("ANSWERED"))
        .andExpect(jsonPath("$[0].promptTokens").value(1200))
        .andExpect(jsonPath("$[1].ordinal").value(2))
        .andExpect(jsonPath("$[1].ending").value("CALL_BUDGET"))
        // nullValue() and not doesNotExist(): the latter is satisfied by a
        // JSON null AND by a missing key, so it would pass whichever of
        // those the server actually sends -- an assertion that cannot
        // tell apart the two things this field's whole javadoc is about.
        .andExpect(jsonPath("$[1].promptTokens").value(nullValue()));
  }

  /**
   * A conversation nobody has spoken into has an empty history, and that is an answer rather than a
   * refusal.
   *
   * <p>Every conversation is in this state for the moment between {@code open} and its first
   * utterance, so a console that had to special-case it would be special-casing the first screen a
   * person ever sees.
   */
  @Test
  void a_conversation_nobody_has_spoken_into_answers_an_empty_history_and_not_a_404()
      throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of());

    mvc.perform(get("/v1/conversations/cnv_1/turns"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /**
   * An id nothing opened is a 404, exactly as it is for the seams.
   *
   * <p>{@code TurnStore.forConversation} cannot tell "nobody has spoken into this" from "no such
   * conversation" — both are the empty list — and the two are opposite facts. <b>The reload this
   * endpoint exists for is what makes the distinction matter.</b> A browser holding a conversation
   * id from before the database was rebuilt would be shown an empty conversation it could type
   * into, and the refusal would arrive from {@code Turn.speak} one utterance later; the person
   * would have written it by then.
   *
   * <p><b>The {@code find} is verified and that is not ceremony.</b> A request to a path no handler
   * is mapped to is <em>also</em> a 404, so this test passed against a controller that had no turns
   * endpoint at all — measured, on the red run. Asserting that the row was looked up is what makes
   * the status this controller's answer rather than Spring's.
   */
  @Test
  void a_history_of_a_conversation_nothing_opened_is_a_404_and_not_an_empty_history()
      throws Exception {
    when(conversations.find("cnv_nope")).thenReturn(Optional.empty());

    mvc.perform(get("/v1/conversations/cnv_nope/turns"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value(containsString("cnv_nope")));

    verify(conversations).find("cnv_nope");
    verify(turns, never()).forConversation(any());
  }

  /**
   * A history the archive could not be reached for is 503 and not an empty one.
   *
   * <p>The arm the listing above has, on the endpoint where the false reading is worst: an empty
   * 200 here is precisely the amnesia this endpoint was added to prevent, and a reload that met a
   * dead database would show the person the blank screen rather than the reason for it.
   */
  @Test
  void a_history_the_archive_could_not_be_reached_for_is_503_and_not_an_empty_history()
      throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1"))
        .thenThrow(
            new ArchiveUnavailableException(
                "the archive could not be reached to read a conversation's turns",
                new IllegalStateException("Connection to localhost:5432 refused")));

    mvc.perform(get("/v1/conversations/cnv_1/turns"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("archive_unavailable"))
        .andExpect(jsonPath("$.detail").value(containsString("read a conversation's turns")));
  }

  // --- continuing a run that stopped ------------------------------------------------

  /**
   * A grant starts a new run continuing the stopped one, and answers with it.
   *
   * <p>202 and the job, like every other endpoint that starts a run: what a caller wants next is
   * the handle to poll and to cancel.
   */
  @Test
  void global_conversation_history_cannot_be_resumed() throws Exception {
    when(speaking.homeOf("cnv_1")).thenReturn(Home.global());
    mvc.perform(
            post("/v1/conversations/cnv_1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agent\":\"talker\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("Global")));
    verify(speaking, never()).resume(any(), any(), any(), any(), any());
  }

  @Test
  void continuing_a_conversation_starts_a_run_and_answers_with_it() throws Exception {
    when(speaking.resume(eq("cnv_1"), any(), any(), any(), any())).thenReturn("job_000007");

    mvc.perform(
            post("/v1/conversations/cnv_1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"agent": "talker", "maxTurns": 20, "maxModelCalls": 40}"""))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value("job_000007"))
        .andExpect(jsonPath("$.agent").value("talker"));

    ArgumentCaptor<TurnCap> cap = ArgumentCaptor.captor();
    verify(speaking)
        .resume(eq("cnv_1"), any(AgentDefinition.class), isNull(), cap.capture(), eq(40));
    assertEquals(20, cap.getValue().turns());
  }

  /**
   * A body that decides no cap takes the levels above it.
   *
   * <p>Turns are the grant this endpoint is for, and they are still optional: a new run counts its
   * turns from zero, so the conversation's own ceiling — or the agent's — is a full grant again
   * rather than an empty one. {@code null} is how "not deciding" reaches {@code TurnCap.chosen}.
   */
  @Test
  void a_grant_that_decides_no_cap_leaves_the_levels_above_it_to_answer() throws Exception {
    when(speaking.resume(any(), any(), any(), any(), any())).thenReturn("job_000008");

    mvc.perform(
            post("/v1/conversations/cnv_1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"agent": "talker"}"""))
        .andExpect(status().isAccepted());

    verify(speaking).resume("cnv_1", agent(), null, null, null);
  }

  /**
   * A grant continues as the agent that answered the stopped run, and the caller does not have to
   * say.
   *
   * <p><b>This is the read that removes a way to be wrong.</b> The body carried the agent because
   * nothing recorded it; {@code turns.agent} does now, and a resumption opens with the stopped
   * run's whole history — every tool result it collected, in the shape that agent's own {@code
   * tools:} line produced. Continuing it as a different agent put one agent's working in front of
   * another under a different system prompt, and nothing refused it, because the request named an
   * agent, the agent existed, and the run started.
   */
  @Test
  void a_grant_continues_as_the_agent_that_answered_the_stopped_run() throws Exception {
    when(turns.forConversation("cnv_1")).thenReturn(List.of(answeredBy("talker")));
    when(speaking.resume(any(), any(), any(), any(), any())).thenReturn("job_000009");

    mvc.perform(
            post("/v1/conversations/cnv_1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"maxTurns": 20}"""))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.agent").value("talker"));

    verify(speaking).resume(eq("cnv_1"), eq(agent()), isNull(), any(), isNull());
  }

  /**
   * A body naming a different agent from the one that answered is refused rather than quietly
   * corrected.
   *
   * <p>A caller sending a name is asserting something about the run it is continuing, and an
   * assertion this server knows to be false is worth a message — the position {@code
   * AgentController.run} already takes on a body carrying both a conversation and a project.
   */
  @Test
  void a_grant_that_names_an_agent_other_than_the_one_that_answered_is_refused() throws Exception {
    when(turns.forConversation("cnv_1")).thenReturn(List.of(answeredBy("talker")));

    mvc.perform(
            post("/v1/conversations/cnv_1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"agent": "someone_else"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("talker")));

    verify(speaking, never()).resume(any(), any(), any(), any(), any());
  }

  /**
   * The half that must not move: {@code exported} is exactly the rule this endpoint needs, because
   * resuming a stopped run starts a new one. {@code projection} and {@code context} stopped asking
   * this question of an agent they were only ever reading; this endpoint still runs one.
   */
  @Test
  void resuming_as_an_unexported_agent_is_still_refused() throws Exception {
    mvc.perform(
            post("/v1/conversations/cnv_1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"agent": "shadow"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("not exported")));

    verify(speaking, never()).resume(any(), any(), any(), any(), any());
  }

  /**
   * A turn written before the column existed still takes an agent from its caller, and a grant that
   * supplies none is refused.
   *
   * <p>The pre-V17 arrangement, surviving for exactly the conversations that predate the column:
   * nothing anywhere records which agent answered those turns, so there is nothing for the server
   * to read and the body is the only source there is.
   */
  @Test
  void a_grant_on_a_turn_that_records_no_agent_still_has_to_be_told_which_one() throws Exception {
    when(turns.forConversation("cnv_1")).thenReturn(List.of(turn(1, 900)));

    mvc.perform(
            post("/v1/conversations/cnv_1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"maxTurns": 20}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("does not record")));

    verify(speaking, never()).resume(any(), any(), any(), any(), any());
  }

  /**
   * Two answers to one question, refused rather than resolved — {@code RequestedTurnCap}'s rule,
   * reaching a third body.
   */
  @Test
  void a_grant_that_says_both_a_number_and_no_cap_is_refused() throws Exception {
    mvc.perform(
            post("/v1/conversations/cnv_1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"agent": "talker", "maxTurns": 20, "noTurnCap": true}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("this run")));

    verify(speaking, never()).resume(any(), any(), any(), any(), any());
  }

  /**
   * A conversation whose last run a grant does not continue is a 409.
   *
   * <p>The row is there and the server will not do this to it, which is what {@code Turn.Refused}
   * means everywhere else it is thrown.
   */
  @Test
  void a_run_a_grant_does_not_continue_is_a_conflict() throws Exception {
    when(speaking.resume(any(), any(), any(), any(), any()))
        .thenThrow(
            new Turn.Refused(
                "conversation cnv_1's last turn ended ANSWERED, so"
                    + " there is nothing to continue."));

    mvc.perform(
            post("/v1/conversations/cnv_1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"agent": "talker"}"""))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value(containsString("ANSWERED")));
  }

  /**
   * A grant that would put a budget below what has been spent is a 400.
   *
   * <p>The identical refusal {@code POST /v1/jobs/&#123;id&#125;/limits} applies, at the identical
   * status: a number the caller can correct, where a conversation that cannot take this turn is a
   * 409 about the row.
   */
  @Test
  void a_grant_below_what_has_been_spent_is_refused_at_the_door() throws Exception {
    when(speaking.resume(any(), any(), any(), any(), any()))
        .thenThrow(
            new CallerFault(
                "conversation cnv_1 has already spent 9"
                    + " model calls, and a budget of 4 cannot record that."));

    mvc.perform(
            post("/v1/conversations/cnv_1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"agent": "talker", "maxModelCalls": 4}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("already spent 9")));
  }

  /**
   * And a grant naming a number for a conversation whose allowance has no ceiling is a 400 too,
   * carrying the sentence written for that operator.
   *
   * <p><b>The sibling of the refusal above, and it was a 500.</b> {@code Budget.changeTo} throws
   * {@code IllegalStateException} for a lifted budget — there is nothing for a number to raise —
   * and this door caught only {@code IllegalArgumentException}, so the one message actually
   * addressed to the person sending the request never reached them. {@code AgentController.limits}
   * answers the identical refusal at the other door that moves a budget, through {@code
   * Budget.changeToOrRefuse}; {@code Turn.grant} is where the conversion happens for this one, and
   * {@code TurnTest} drives the real path.
   */
  @Test
  void a_grant_of_model_calls_to_a_conversation_with_no_ceiling_is_refused_at_the_door()
      throws Exception {
    when(speaking.resume(any(), any(), any(), any(), any()))
        .thenThrow(
            new CallerFault(
                "this budget has no ceiling, so there"
                    + " is nothing for changeTo() to raise; it already permits every call a"
                    + " run could make"));

    mvc.perform(
            post("/v1/conversations/cnv_1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"agent": "talker", "maxModelCalls": 60}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("no ceiling")));
  }

  /**
   * A session that is present and empty is a 400, on {@code AgentController.run}'s terms: it says
   * "here is my session" and names nothing, and a run started anyway silently reaches no client
   * machine.
   */
  @Test
  void a_grant_carrying_a_session_that_names_nothing_is_refused() throws Exception {
    mvc.perform(
            post("/v1/conversations/cnv_1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"agent": "talker", "session": "  "}"""))
        .andExpect(status().isBadRequest());

    verify(speaking, never()).resume(any(), any(), any(), any(), any());
  }

  /**
   * The one property this controller reads, at a number no other fixture uses.
   *
   * <p>Deliberately not 240, which is what {@code application.yml} ships: a test asserting the
   * shipped number would pass if the controller ignored the properties object and hard-coded it,
   * which is the one mistake these tests are here to catch.
   */
  private static ConversationsProperties configured() {
    ConversationsProperties properties = new ConversationsProperties();
    properties.setDefaultBudget(77);
    return properties;
  }

  private static AgentDefinition agent() {
    // Exported: every test here names this agent for a turn or a resume,
    // and those are two of the four doors `exported` gates.
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

  /**
   * The other shape a definition takes: never exported, which is the ordinary state of a delegated
   * sub-agent. {@code AgentControllerTest} has a fixture by the same name for the same reason.
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
        "You do one thing, quietly.",
        false,
        true);
  }

  private static ObjectProvider<AgentRegistry> providerOf(AgentRegistry available) {
    @SuppressWarnings("unchecked")
    ObjectProvider<AgentRegistry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(available);
    return provider;
  }

  private static ConversationRecord row(String id, Home home, int allowance) {
    return row(id, home, allowance, null);
  }

  /**
   * A row in the state a move answers with, which is the only field {@code LifecycleView} reads.
   */
  private static ConversationRecord archived(String id) {
    return new ConversationRecord(
        id,
        PAYMENTS_HOME,
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

  private static ConversationRecord row(String id, Home home, int allowance, TurnCap cap) {
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
        cap,
        null);
  }

  /**
   * The same row, carrying the name its first turn gave it — or carrying null, which is what every
   * row written before there was a title column carries and is not a different kind of row.
   */
  private static ConversationRecord named(String id, String title) {
    return new ConversationRecord(
        id,
        PAYMENTS_HOME,
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

  /** V31's third state: owns its allowance, and there is no ceiling on it. */
  private static ConversationRecord liftedRow(String id, Home home, int spent) {
    return new ConversationRecord(
        id,
        home,
        Origin.TURN,
        ConversationLifecycle.ACTIVE,
        null,
        null,
        OPENED_AT,
        null,
        Budget.lifted(spent),
        null,
        null);
  }

  // --- the two readings of one log ------------------------------------------------

  /**
   * The trajectory is the log: what a fold covered, what carries no role, and the timings, all of
   * it.
   *
   * <p>The half of the pair that has never had a reader. {@code EntryStore.forConversation}'s own
   * javadoc says so — "nothing in production reads the whole log today" — and every fact this
   * endpoint carries is one the system already wrote down and could not be asked for.
   */
  @Test
  void a_trajectory_is_the_whole_log_and_says_how_much_of_it_this_page_is() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(entries.pageOfLog("cnv_1", 0, ConversationController.MOST_ENTRIES_A_PAGE))
        .thenReturn(
            new EntryPage(
                List.of(
                    aRow(1, EntryKind.UTTERANCE, "what broke it?", null),
                    aRow(2, EntryKind.DIAGNOSTIC, "compaction triggered", 4)),
                57));

    mvc.perform(get("/v1/conversations/cnv_1/trajectory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(57))
        .andExpect(jsonPath("$.offset").value(0))
        .andExpect(jsonPath("$.limit").value(ConversationController.MOST_ENTRIES_A_PAGE))
        .andExpect(jsonPath("$.entries.length()").value(2))
        .andExpect(jsonPath("$.entries[0].kind").value("utterance"))
        .andExpect(jsonPath("$.entries[0].excerpt").value("what broke it?"))
        .andExpect(jsonPath("$.entries[0].cut").value(false))
        .andExpect(jsonPath("$.entries[0].supersededBy").value(nullValue()))
        .andExpect(jsonPath("$.entries[1].kind").value("diagnostic"))
        .andExpect(jsonPath("$.entries[1].supersededBy").value(4))
        .andExpect(jsonPath("$.entries[1].recordedAt").exists())
        .andExpect(jsonPath("$.entries[1].tookMillis").value(120));
  }

  @Test
  void a_trajectory_says_who_spoke_each_utterance_and_how_far_the_log_reaches() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(entries.pageOfLog("cnv_1", 0, ConversationController.MOST_ENTRIES_A_PAGE))
        .thenReturn(
            new EntryPage(
                List.of(
                    new EntryPage.Row(
                        1,
                        1,
                        EntryKind.UTTERANCE,
                        "The orchestration finished.",
                        27,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Speaker.orchestration("orc_1"))),
                1,
                9));

    mvc.perform(get("/v1/conversations/cnv_1/trajectory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.through").value(9))
        .andExpect(jsonPath("$.entries[0].speaker").value("harness"))
        .andExpect(jsonPath("$.entries[0].speakerName").value("orchestration orc_1"));
  }

  @Test
  void a_trajectory_carries_the_outcome_the_salient_argument_and_the_child_a_call_opened()
      throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    EntryPage.Asked asked =
        new EntryPage.Asked(
            "c1",
            "agent_run",
            "{\"agent\":\"helper\"}",
            19,
            "helper",
            new EntryPage.Opened("cnv_2", "helper"));
    // A call from before V62, or one nothing delegated to: the 4-arg
    // constructor, which is what says salient and opened are null rather
    // than absent -- doesNotExist() cannot tell the two apart, on the same
    // terms as TurnView.promptTokens above.
    EntryPage.Asked plain = new EntryPage.Asked("c2", "file_stat", "{}", 2);
    when(entries.pageOfLog("cnv_1", 0, ConversationController.MOST_ENTRIES_A_PAGE))
        .thenReturn(
            new EntryPage(
                List.of(
                    new EntryPage.Row(
                        1,
                        1,
                        EntryKind.ANSWER,
                        "",
                        0,
                        null,
                        null,
                        null,
                        List.of(asked, plain),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                    new EntryPage.Row(
                        2,
                        1,
                        EntryKind.TOOL_RESULT,
                        "fine",
                        4,
                        null,
                        null,
                        "c1",
                        List.of(),
                        null,
                        null,
                        48_000L,
                        null,
                        null,
                        null,
                        null,
                        "answered")),
                2,
                2));
    mvc.perform(get("/v1/conversations/cnv_1/trajectory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].toolCalls[0].salient").value("helper"))
        .andExpect(jsonPath("$.entries[0].toolCalls[0].opened.conversation").value("cnv_2"))
        .andExpect(jsonPath("$.entries[0].toolCalls[0].opened.agent").value("helper"))
        .andExpect(jsonPath("$.entries[0].toolCalls[1].salient").value(nullValue()))
        .andExpect(jsonPath("$.entries[0].toolCalls[1].opened").value(nullValue()))
        .andExpect(jsonPath("$.entries[1].outcome").value("answered"))
        // nullValue() and not doesNotExist(): EntryView sets no
        // @JsonInclude, so a null outcome serialises as an explicit
        // "outcome":null and doesNotExist() would pass either way.
        .andExpect(jsonPath("$.entries[0].outcome").value(nullValue()));
  }

  /**
   * The tail read backwards is newest first, and says where to go back from and whether there is
   * anywhere to go; a forward page says nothing of what lies before it.
   */
  @Test
  void a_tail_read_backwards_is_newest_first_and_says_what_lies_before_it() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    Set<EntryKind> drawn = EnumSet.of(EntryKind.UTTERANCE, EntryKind.ANSWER, EntryKind.SUMMARY);
    when(entries.pageOfLogBefore("cnv_1", EntryStore.FROM_THE_END, drawn, false, 0, 40))
        .thenReturn(
            new EntryPage(
                List.of(
                    aRow(9, EntryKind.ANSWER, "fixed", null),
                    aRow(7, EntryKind.UTTERANCE, "what broke it?", null)),
                30,
                11,
                true));
    when(entries.pageOfLog("cnv_1", 0, ConversationController.MOST_ENTRIES_A_PAGE))
        .thenReturn(new EntryPage(List.of(aRow(1, EntryKind.UTTERANCE, "hello", null)), 1));

    mvc.perform(
            get("/v1/conversations/cnv_1/trajectory")
                .param("tail", "true")
                .param("limit", "40")
                .param("kinds", "utterance,answer,summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].ordinal").value(9))
        .andExpect(jsonPath("$.entries[1].ordinal").value(7))
        .andExpect(jsonPath("$.total").value(30))
        .andExpect(jsonPath("$.through").value(11))
        .andExpect(jsonPath("$.oldest").value(7))
        .andExpect(jsonPath("$.more").value(true));
    mvc.perform(get("/v1/conversations/cnv_1/trajectory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.oldest").value(1))
        .andExpect(jsonPath("$.more").value(nullValue()));
  }

  /**
   * {@code drawn=true} reaches the store as the narrower read, and the page it answers with still
   * says the whole log's reach.
   */
  @Test
  void a_drawn_tail_asks_the_store_to_leave_out_the_answers_no_chat_draws() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    Set<EntryKind> drawn = EnumSet.of(EntryKind.UTTERANCE, EntryKind.ANSWER, EntryKind.SUMMARY);
    when(entries.pageOfLogBefore("cnv_1", EntryStore.FROM_THE_END, drawn, true, 0, 40))
        .thenReturn(new EntryPage(List.of(aRow(9, EntryKind.ANSWER, "fixed", null)), 6, 12, true));

    mvc.perform(
            get("/v1/conversations/cnv_1/trajectory")
                .param("tail", "true")
                .param("limit", "40")
                .param("kinds", "utterance,answer,summary")
                .param("drawn", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(6))
        .andExpect(jsonPath("$.through").value(12))
        .andExpect(jsonPath("$.more").value(true));

    verify(entries).pageOfLogBefore("cnv_1", EntryStore.FROM_THE_END, drawn, true, 0, 40);
    verify(entries, never()).pageOfLogBefore(any(), anyInt(), any(), eq(false), anyInt(), anyInt());
  }

  /**
   * A kind this build cannot name is the caller's mistake, said as one, and nothing is read on the
   * strength of it.
   */
  @Test
  void a_kind_nobody_can_name_is_refused_at_the_door() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));

    mvc.perform(
            get("/v1/conversations/cnv_1/trajectory")
                .param("tail", "true")
                .param("kinds", "utterance", "daydream"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("'daydream'")));
    mvc.perform(get("/v1/conversations/cnv_1/trajectory").param("before", "4").param("after", "2"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("Name one")));

    verify(entries, never())
        .pageOfLogBefore(any(), anyInt(), any(), anyBoolean(), anyInt(), anyInt());
  }

  /**
   * The chat is the projection, and it is a different question of the same rows: {@code
   * thatProjectFor}'s reading rather than {@code forConversation}'s, asked one page at a time.
   */
  @Test
  void a_chat_is_what_the_model_is_shown_and_asks_the_narrower_read() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(entries.pageOfProjection("cnv_1", 10, 5))
        .thenReturn(new EntryPage(List.of(aRow(11, EntryKind.ANSWER, "", null)), 40));

    mvc.perform(get("/v1/conversations/cnv_1/chat").param("offset", "10").param("limit", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(40))
        .andExpect(jsonPath("$.offset").value(10))
        .andExpect(jsonPath("$.limit").value(5))
        .andExpect(jsonPath("$.entries[0].toolCalls[0].name").value("file_read"))
        .andExpect(jsonPath("$.entries[0].toolCalls[0].arguments").value("{\"p\":1}"))
        .andExpect(jsonPath("$.entries[0].toolCalls[0].cut").value(true));

    verify(entries, never()).pageOfLog(any(), anyInt(), anyInt());
  }

  /**
   * A page bigger than this endpoint's cap comes back at the cap, and the answer says which number
   * it used.
   *
   * <p><b>The bound is the server's and not the caller's</b>, which is the whole point of a cap: a
   * caller asking for a hundred thousand entries is asking for an answer nothing can hold, and
   * refusing it would make a client guess the number instead. The echoed {@code limit} is what
   * tells the caller its request was narrowed, and it is the number to page by.
   */
  @Test
  void a_page_larger_than_the_cap_is_answered_at_the_cap_and_says_so() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(entries.pageOfLog("cnv_1", 0, ConversationController.MOST_ENTRIES_A_PAGE))
        .thenReturn(EntryPage.NONE);

    mvc.perform(get("/v1/conversations/cnv_1/trajectory").param("limit", "100000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(ConversationController.MOST_ENTRIES_A_PAGE));

    verify(entries).pageOfLog("cnv_1", 0, ConversationController.MOST_ENTRIES_A_PAGE);
  }

  /**
   * A bound that is not one is the caller's mistake and is corrected from the message, which is
   * what makes it a 400 rather than the store's own IllegalArgumentException arriving as a 500.
   */
  @Test
  void a_page_that_could_not_hold_anything_is_refused_at_the_door() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));

    mvc.perform(get("/v1/conversations/cnv_1/trajectory").param("limit", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("1 or more")));
    mvc.perform(get("/v1/conversations/cnv_1/chat").param("offset", "-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("0 or later")));

    verify(entries, never()).pageOfLog(any(), anyInt(), anyInt());
    verify(entries, never()).pageOfProjection(any(), anyInt(), anyInt());
  }

  /**
   * An id nothing opened is a 404 on both readings, and not an empty page.
   *
   * <p>{@code turns} and {@code compactions} look their row up for this reason and say so: a store
   * answers the empty page both for a conversation nobody has spoken into and for one that was
   * never opened, and those are opposite facts. A trajectory is where it matters most — a client
   * restoring a saved id would render an empty run that looks resumable.
   */
  @Test
  void a_conversation_nothing_opened_has_neither_a_chat_nor_a_trajectory() throws Exception {
    when(conversations.find("nope")).thenReturn(Optional.empty());

    mvc.perform(get("/v1/conversations/nope/trajectory"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail", containsString("nope")));
    mvc.perform(get("/v1/conversations/nope/chat"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail", containsString("nope")));

    verify(entries, never()).pageOfLog(any(), anyInt(), anyInt());
    verify(entries, never()).pageOfProjection(any(), anyInt(), anyInt());
  }

  // --- what a conversation's context is made of, and what cannot be said -----------

  /**
   * The whole prompt is measured and its parts are not, and the surface says which is which.
   *
   * <p><b>The gap is the deliverable here.</b> {@code usage.prompt_tokens} is the model's own
   * tokenizer counting the entire request, so {@code sent} is exact; there is no tokenizer on this
   * box to count a part of it, which {@code Compaction} records in four places and {@code LmStudio}
   * measured. A number invented for the system prompt or the tool block would be a plausible one,
   * and the whole point of this endpoint is that a plausible number is worse than an honest
   * absence.
   */
  @Test
  void a_context_reports_the_whole_prompt_and_refuses_to_split_it() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1"))
        .thenReturn(List.of(turn(1, 900), turn(2, 18543), turn(3, null)));

    mvc.perform(get("/v1/conversations/cnv_1/context"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sent").value(18543))
        .andExpect(jsonPath("$.sentAtTurn").value(2))
        .andExpect(jsonPath("$.turns").value(3))
        .andExpect(jsonPath("$.turnsMeasured").value(2))
        .andExpect(jsonPath("$.systemPromptTokens").value(nullValue()))
        .andExpect(jsonPath("$.toolTokens").value(nullValue()))
        .andExpect(jsonPath("$.messageTokens").value(nullValue()))
        .andExpect(jsonPath("$.cacheHitRate").value(nullValue()))
        .andExpect(jsonPath("$.prefix").value(nullValue()));
  }

  /**
   * Every number this surface cannot give is named, with the reason it cannot.
   *
   * <p>Asserted on the components and on a distinctive phrase of each reason rather than on the
   * sentences whole, so that rewording one is not a test change and dropping one is. <b>The cache
   * hit rate is the load-bearing one</b>: the ~30x prefix reuse the entire compaction design rests
   * on is unobservable in production, because this endpoint's {@code usage} carries no {@code
   * cached_tokens}, and a client that estimated it from timing would be reporting the thing the
   * design was validated against as though it had been measured.
   */
  @Test
  void every_number_this_surface_cannot_measure_says_so_and_says_why() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of(turn(1, 900)));

    // ONE, WHERE THERE WERE FOUR. Three of these said a share of a prompt
    // was unobtainable, and they are now obtained -- on whatever basis the
    // deployment's tokenizer can manage, each saying which. One of the
    // three said so for a reason that was not true, that measuring the
    // system block "costs a generation of unbounded length", when
    // OpenAiTransport.chatBody has always threaded max_tokens through.
    //
    // The cache rate is different in kind and stays: no tokenizer and no
    // arithmetic recovers it, because the endpoint sends no observation of
    // it at any fidelity.
    mvc.perform(get("/v1/conversations/cnv_1/context"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unavailable.length()").value(1))
        .andExpect(jsonPath("$.unavailable[0].component").value("cacheHitRate"))
        .andExpect(
            jsonPath(
                "$.unavailable[?(@.component == 'cacheHitRate')].reason",
                hasItem(containsString("cached_tokens"))));
  }

  /**
   * A count on the wire carries its basis, and a bare number never appears.
   *
   * <p>This is the contract that let three refusals become three answers. An estimate rendered as a
   * plain figure cannot be told from a measurement, and refusing to answer was the only other way
   * to keep that promise.
   */
  @Test
  void every_count_says_what_it_is_worth() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of(turn(1, 900)));

    mvc.perform(get("/v1/conversations/cnv_1/context").param("agent", "talker"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.systemPromptTokens.basis").value("ESTIMATED"))
        .andExpect(jsonPath("$.toolTokens.basis").value("ESTIMATED"))
        // The whole was measured and the parts were not, so the
        // remainder is only as good as the parts -- estimated, not
        // measured, however exact the number it was subtracted from.
        .andExpect(jsonPath("$.messageTokens.basis").value("ESTIMATED"))
        .andExpect(
            jsonPath("$.messageTokens.how")
                .value(containsString("counted by the model at 900 tokens")));
  }

  /**
   * Growth between measured turns, which needs no tokenizer at all.
   *
   * <p>Both terms of every difference are {@code usage.prompt_tokens} from the model's own
   * tokenizer, so the difference is the model's count too.
   */
  @Test
  void the_growth_between_measured_turns_is_exact_and_names_what_it_spans() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1"))
        .thenReturn(List.of(turn(1, 900), turn(2, 1500), turn(4, 1250)));

    mvc.perform(get("/v1/conversations/cnv_1/context"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.measuredTurns.length()").value(3))
        // Nothing to differ from on the first, and null rather than a
        // zero: a zero would say a growth was computed and found none.
        .andExpect(jsonPath("$.measuredTurns[0].grewBy").value(nullValue()))
        .andExpect(jsonPath("$.measuredTurns[1].grewBy").value(600))
        .andExpect(jsonPath("$.measuredTurns[1].since").value(1))
        // Negative, and that is a fold rather than a fault: compaction
        // replaced history with a summary and the prompt got smaller.
        .andExpect(jsonPath("$.measuredTurns[2].grewBy").value(-250))
        // Turn 4 follows turn 2, so the span it covers is carried and
        // not left to be assumed to be one turn.
        .andExpect(jsonPath("$.measuredTurns[2].since").value(2));
  }

  /**
   * Naming an agent prices its fixed block in the one unit this box can count.
   *
   * <p><b>Characters of the JSON the transport sends, and never tokens.</b> The array is measured
   * through {@code ToolSchema.asDeclared}, which is what {@code OpenAiTransport.chatBody} builds a
   * request out of, so the number is of the bytes really sent rather than of a shape assembled
   * twice.
   *
   * <p><b>The agent is a parameter and not read off the conversation, because a conversation does
   * not have one.</b> {@code ConversationController}'s own javadoc has said so since conversations
   * existed — the agent is named per turn — and neither {@code conversations} nor {@code turns} has
   * a column for it. So no measurement of an agent's block can be attached to this conversation's
   * {@code sent} by the server, and the caller saying which agent it means is what keeps that from
   * being pretended.
   */
  @Test
  void naming_an_agent_prices_its_prefix_in_characters_exactly_and_in_tokens_on_a_stated_basis()
      throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of(turn(1, 900)));

    mvc.perform(get("/v1/conversations/cnv_1/context").param("agent", "talker"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.prefix.agent").value("talker"))
        .andExpect(jsonPath("$.prefix.model").value("fast"))
        // What compaction folds under: no pool here can size "fast",
        // so the configured default, exactly as a fold would use.
        .andExpect(jsonPath("$.prefix.contextLength").value(64_000))
        .andExpect(jsonPath("$.prefix.systemPromptCharacters").value(agent().prompt().length()))
        .andExpect(jsonPath("$.prefix.tools.length()").value(1))
        .andExpect(jsonPath("$.prefix.tools[0].name").value("memory_recall"))
        .andExpect(jsonPath("$.prefix.toolCharacters").isNumber())
        // The characters stay exact and the tokens arrive beside them
        // rather than instead of them. This assertion used to be that
        // the token count was null; what it was protecting was that a
        // character count must never be read as a token count, and the
        // basis is what protects it now -- visibly, on the wire, where
        // the old null could only protect it by saying nothing.
        .andExpect(jsonPath("$.toolTokens.tokens").isNumber())
        .andExpect(jsonPath("$.toolTokens.basis").value("ESTIMATED"))
        .andExpect(jsonPath("$.toolTokens.how").value(containsString("characters per token")));
  }

  /**
   * The estimate is a ratio over the same bytes the character count reports.
   *
   * <p>The two numbers on this record are of one string, and nothing downstream could tell if they
   * drifted apart — which is why {@code json} was split out of {@code characters} rather than each
   * building its own.
   */
  @Test
  void the_estimate_counts_the_same_bytes_the_character_count_reports() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of(turn(1, 900)));

    String body =
        mvc.perform(get("/v1/conversations/cnv_1/context").param("agent", "talker"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    int characters = com.jayway.jsonpath.JsonPath.read(body, "$.prefix.toolCharacters");
    int tokens = com.jayway.jsonpath.JsonPath.read(body, "$.toolTokens.tokens");
    org.junit.jupiter.api.Assertions.assertEquals(
        (int) Math.ceil(characters / RatioTokenizer.DEFAULT_CHARACTERS_PER_TOKEN), tokens);
  }

  /**
   * A context read with no agent named prices the one that last answered.
   *
   * <p><b>What changed at V17 is the default and not the parameter.</b> "What would this cost if I
   * asked X next" is a question about an agent the caller is choosing, and a conversation holding
   * two agents' turns has no opinion about which comes next — so the parameter stays an override.
   * What the server can now answer on its own is "what has this conversation been costing", and
   * omitting the parameter used to give no prefix at all, which was an absence standing in for a
   * fact nobody had recorded.
   */
  @Test
  void a_context_with_no_agent_named_prices_the_one_that_last_answered() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of(answeredBy("talker")));

    mvc.perform(get("/v1/conversations/cnv_1/context"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.prefix.agent").value("talker"));
  }

  /**
   * The other read {@link RequestedAgent}'s {@code exported} check has no business refusing:
   * pricing a delegated sub-agent's prefix is a look, never a run of it.
   */
  @Test
  void the_context_of_a_conversation_answered_by_an_unexported_agent_is_readable()
      throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of(turn(1, 900)));

    mvc.perform(get("/v1/conversations/cnv_1/context").param("agent", "shadow"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.prefix.agent").value("shadow"));
  }

  /**
   * An agent nothing is called is the caller's mistake and is corrected from the message, exactly
   * as it is when starting a run: a 400 naming what exists rather than a 404 that is true and
   * useless.
   */
  @Test
  void a_context_naming_an_agent_that_does_not_exist_is_refused() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));

    mvc.perform(get("/v1/conversations/cnv_1/context").param("agent", "nobody"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("talker")));
  }

  /**
   * A conversation nothing opened has no context either, for the reason every other read here looks
   * its row up: an empty answer and a missing conversation are opposite facts.
   */
  @Test
  void a_context_of_a_conversation_nothing_opened_is_a_404() throws Exception {
    when(conversations.find("nope")).thenReturn(Optional.empty());

    mvc.perform(get("/v1/conversations/nope/context"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail", containsString("nope")));
  }

  /**
   * One turn row recording which agent answered it, which is what a resumption and a context both
   * read.
   */
  private static TurnRecord answeredBy(String agent) {
    return new TurnRecord("cnv_1", 1, "said", "answered", Ending.ANSWERED, 900, agent, null);
  }

  /** One turn row, carrying only what a context reads off it. */
  private static TurnRecord turn(int ordinal, Integer promptTokens) {
    return new TurnRecord(
        "cnv_1", ordinal, "said", "answered", Ending.ANSWERED, promptTokens, null, null);
  }

  // --- the next prompt's projection --------------------------------------------

  /**
   * The plainest case: an agent's own prompt out front, then the projected history, exactly as
   * {@code JobRuntime.opening} would build it minus the utterance nobody has typed yet.
   */
  @Test
  void a_projection_opens_with_one_system_message_and_then_the_history() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(entries.thatProjectFor("cnv_1"))
        .thenReturn(
            List.of(utterance(1, 1, "what broke it?"), answer(2, 1, "a retry loop, fixed now")));

    mvc.perform(get("/v1/conversations/cnv_1/projection").param("agent", "talker"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agent").value("talker"))
        .andExpect(jsonPath("$.messages.length()").value(3))
        .andExpect(jsonPath("$.messages[0].role").value("system"))
        .andExpect(jsonPath("$.messages[0].content").value(agent().prompt()))
        .andExpect(jsonPath("$.messages[1].role").value("user"))
        .andExpect(jsonPath("$.messages[1].content").value("what broke it?"))
        .andExpect(jsonPath("$.messages[2].role").value("assistant"))
        .andExpect(jsonPath("$.messages[2].content").value("a retry loop, fixed now"));
    // Three messages total and index 0 is the only one asserted to be
    // "system" above; index 1 and index 2 are pinned to "user" and
    // "assistant" by name, which is what makes "exactly one system message,
    // and it is first" a property of this assertion set rather than
    // something a fourth, unchecked message could still violate.
  }

  /**
   * A folded conversation's projection carries the seam merged into the one system message, and not
   * the turns the seam stands for.
   *
   * <p><b>This is {@code thatProjectFor}'s question and not {@code forConversation}'s</b> — the
   * store already answers with the summary in place of what it covers and with the superseded rows
   * left out entirely, which is why the fixture below never mentions them: a mock standing in for
   * {@code EntryStore} is told to answer exactly what the real store would, and what {@link
   * Compaction#projectionFor} does with that answer is the property this test actually holds.
   */
  @Test
  void a_folded_conversation_projects_the_seam_and_not_the_superseded_rows() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(entries.thatProjectFor("cnv_1"))
        .thenReturn(
            List.of(
                summary(9, 3, "turns 1 to 3 diagnosed and fixed the retry budget"),
                utterance(10, 4, "and now?"),
                answer(11, 4, "now it holds")));

    mvc.perform(get("/v1/conversations/cnv_1/projection").param("agent", "talker"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages.length()").value(3))
        .andExpect(jsonPath("$.messages[0].role").value("system"))
        .andExpect(jsonPath("$.messages[0].content", containsString(agent().prompt())))
        .andExpect(jsonPath("$.messages[0].content", containsString("Turns 1 to 3")))
        .andExpect(
            jsonPath(
                "$.messages[0].content",
                containsString("turns 1 to 3 diagnosed and fixed the retry budget")))
        .andExpect(jsonPath("$.messages[1].role").value("user"))
        .andExpect(jsonPath("$.messages[1].content").value("and now?"))
        .andExpect(jsonPath("$.messages[2].content").value("now it holds"));

    verify(entries).thatProjectFor("cnv_1");
    verify(entries, never()).forConversation(any());
  }

  /**
   * The property that matters most: computing a projection makes no request to a model.
   *
   * <h2>Why this is proved against a real {@code Compaction} and not a mock</h2>
   *
   * <p>A mocked {@code Compaction} would make this assertion vacuous — nothing mocked out ever
   * dispatches anything, whatever the route did. Every test in this class is wired against {@link
   * #explodingCompaction}, a real {@code Compaction} over a real {@code LlmDispatcher} whose one
   * transport fails loudly the moment {@code complete} or {@code stream} is called, so a route that
   * quietly sent the assembled prompt to "measure" it would fail every test here with a 500 and not
   * only this one. This test names the property so a reader does not have to infer it from an
   * absence.
   */
  @Test
  void computing_a_projection_issues_no_request_to_a_model() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(entries.thatProjectFor("cnv_1"))
        .thenReturn(
            List.of(
                utterance(1, 1, "how much does this cost?"),
                answer(2, 1, "nothing to compute this")));

    mvc.perform(get("/v1/conversations/cnv_1/projection").param("agent", "talker"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages.length()").value(3));
  }

  /**
   * {@code exported} says who may RUN an agent. Reading what it was shown is not running it, and a
   * delegated sub-agent -- which is never exported -- is the ordinary case for a conversation
   * somebody wants to audit.
   */
  @Test
  void a_projection_of_a_conversation_answered_by_an_unexported_agent_is_readable()
      throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(entries.thatProjectFor("cnv_1"))
        .thenReturn(List.of(utterance(1, 1, "what was this shown?")));

    mvc.perform(get("/v1/conversations/cnv_1/projection").param("agent", "shadow"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agent").value("shadow"));
  }

  /**
   * An id nothing opened has no projection either, on {@link #found}'s reasoning: an empty answer
   * and a missing conversation are opposite facts.
   */
  @Test
  void a_projection_of_a_conversation_nothing_opened_is_a_404() throws Exception {
    when(conversations.find("nope")).thenReturn(Optional.empty());

    mvc.perform(get("/v1/conversations/nope/projection"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail", containsString("nope")));

    verify(entries, never()).thatProjectFor(any());
  }

  /**
   * An agent nothing is called is the caller's mistake, corrected from the message exactly as
   * {@link #context} refuses one: a 400 naming what exists rather than a 404 that is true and
   * useless.
   */
  @Test
  void a_projection_naming_an_agent_that_does_not_exist_is_refused() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of());

    mvc.perform(get("/v1/conversations/cnv_1/projection").param("agent", "nobody"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("talker")));
  }

  /**
   * No agent named resolves the same way {@link #context} resolves one: the conversation's own
   * agent for a machine's log, or the last turn's for a person's.
   */
  @Test
  void a_projection_with_no_agent_named_uses_the_one_that_last_answered() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of(answeredBy("talker")));
    when(entries.thatProjectFor("cnv_1")).thenReturn(List.of(utterance(1, 1, "hello")));

    mvc.perform(get("/v1/conversations/cnv_1/projection"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agent").value("talker"));
  }

  /**
   * A person's conversation with no turn yet and no agent named has nothing to build a system
   * message out of, so it is refused rather than answered with an empty projection.
   */
  @Test
  void a_projection_with_no_turn_and_no_agent_named_is_refused() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of());

    mvc.perform(get("/v1/conversations/cnv_1/projection"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("cnv_1")));

    verify(entries, never()).thatProjectFor(any());
  }

  // --- the projection as of a turn that has already happened --------------------------

  /**
   * A {@code turn} asks what that turn was shown, and it is a different read of the log rather than
   * the same one truncated.
   *
   * <p>The store is where the two come apart, and {@code EntryStore.thatProjectedAt} is where that
   * is argued and asserted against a real table. What this pins is the route's half: that {@code
   * turn} reaches that read and not the other one, and that the answer is assembled by the
   * identical arrangement — the agent's prompt out front, then the history.
   */
  @Test
  void a_projection_as_of_a_turn_reads_the_history_that_turn_could_see() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1"))
        .thenReturn(List.of(turn(1, 900), turn(12, 1400), turn(20, 2200)));
    when(entries.thatProjectedAt("cnv_1", 12))
        .thenReturn(
            List.of(
                utterance(1, 1, "what broke it?"),
                answer(2, 1, "a retry loop, fixed now"),
                utterance(3, 12, "and now?")));

    mvc.perform(
            get("/v1/conversations/cnv_1/projection").param("agent", "talker").param("turn", "12"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agent").value("talker"))
        .andExpect(jsonPath("$.messages.length()").value(4))
        .andExpect(jsonPath("$.messages[0].role").value("system"))
        .andExpect(jsonPath("$.messages[1].content").value("what broke it?"))
        .andExpect(jsonPath("$.messages[2].content").value("a retry loop, fixed now"))
        .andExpect(jsonPath("$.messages[3].content").value("and now?"));

    verify(entries).thatProjectedAt("cnv_1", 12);
    verify(entries, never()).thatProjectFor(any());
  }

  /**
   * No {@code turn} is the question this route has always answered, and the store call is how you
   * can tell: the next prompt's read, not a turn's.
   */
  @Test
  void a_projection_with_no_turn_named_is_still_the_next_prompt() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(entries.thatProjectFor("cnv_1")).thenReturn(List.of(utterance(1, 1, "hello")));

    mvc.perform(get("/v1/conversations/cnv_1/projection").param("agent", "talker"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages.length()").value(2));

    verify(entries).thatProjectFor("cnv_1");
    verify(entries, never()).thatProjectedAt(any(), anyInt());
  }

  /**
   * A turn the conversation never reached is refused, and the refusal names the turns there are.
   *
   * <p><b>Not clamped to the last one.</b> A projection assembled correctly under a turn number
   * that never existed is a record of a moment that did not happen, handed to the one reader who
   * came specifically to find out what did — and nothing in the response would say so. So the
   * answer is the range, which is the correction.
   */
  @Test
  void a_projection_as_of_a_turn_the_conversation_never_reached_is_refused() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of(turn(1, 900), turn(12, 1400)));

    mvc.perform(
            get("/v1/conversations/cnv_1/projection").param("agent", "talker").param("turn", "40"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("12")))
        .andExpect(jsonPath("$.detail", containsString("40")));

    verify(entries, never()).thatProjectedAt(any(), anyInt());
    verify(entries, never()).thatProjectFor(any());
  }

  /**
   * Below the first turn is the same non-answer as above the last, and is refused the same way
   * rather than clamped up to it.
   */
  @Test
  void a_projection_as_of_a_turn_before_the_first_is_refused() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of(turn(1, 900), turn(12, 1400)));

    mvc.perform(
            get("/v1/conversations/cnv_1/projection").param("agent", "talker").param("turn", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("cnv_1")));

    verify(entries, never()).thatProjectedAt(any(), anyInt());
  }

  /**
   * A conversation with no turn at all has no turn to be read as of, and says that rather than
   * answering with an empty history.
   */
  @Test
  void a_projection_as_of_a_turn_of_a_conversation_that_has_had_none_is_refused() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of());

    mvc.perform(
            get("/v1/conversations/cnv_1/projection").param("agent", "talker").param("turn", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("cnv_1")));

    verify(entries, never()).thatProjectedAt(any(), anyInt());
  }

  /**
   * The as-of-turn path makes no request to a model either, and that is asserted rather than
   * assumed.
   *
   * <p>{@link #computing_a_projection_issues_no_request_to_a_model} carries the argument and the
   * fixture: this runs against the same {@link #explodingCompaction}, so a route that quietly sent
   * the turn's assembled prompt anywhere fails here with a 500. A new path onto a rule is a new
   * place for the rule to be broken, which is why the coverage was extended rather than left to the
   * older test's name.
   */
  @Test
  void computing_a_projection_as_of_a_turn_issues_no_request_to_a_model() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of(turn(1, 900), turn(12, 1400)));
    when(entries.thatProjectedAt("cnv_1", 12))
        .thenReturn(
            List.of(
                utterance(1, 1, "how much did this cost?"),
                answer(2, 1, "nothing to compute this")));

    mvc.perform(
            get("/v1/conversations/cnv_1/projection").param("agent", "talker").param("turn", "12"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages.length()").value(3));
  }

  /**
   * A turn that recorded the block it went out with is answered with that block, and the answer
   * says so.
   *
   * <p><b>The case the whole change exists for.</b> The definition named on the request says {@code
   * "You do one thing."}; the turn was sent something else, because the file has been edited since.
   * Before V32 the response carried the definition's text with nothing to say it was not what the
   * turn saw, which is a confident wrong answer on an audit screen.
   */
  @Test
  void a_turn_that_recorded_its_block_is_answered_with_the_block_it_was_sent() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of(turn(1, 900), turn(12, 1400)));
    when(turns.blockSentAt("cnv_1", 12)).thenReturn(Optional.of(AS_SENT));
    when(entries.thatProjectedAt("cnv_1", 12)).thenReturn(List.of(utterance(3, 12, "and now?")));

    mvc.perform(
            get("/v1/conversations/cnv_1/projection").param("agent", "talker").param("turn", "12"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.turn").value(12))
        .andExpect(jsonPath("$.systemBlockAsSent").value(true))
        .andExpect(jsonPath("$.messages[0].role").value("system"))
        .andExpect(jsonPath("$.messages[0].content").value(AS_SENT))
        .andExpect(jsonPath("$.messages[1].content").value("and now?"));
  }

  /**
   * A turn that recorded no block is answered with today's definition, and the answer says
   * <em>that</em>.
   *
   * <p><b>Every turn written before {@code V32__turn_system_prompt.sql} is in this state,
   * permanently.</b> The honest answer is the current file, because there is nothing else — the
   * migration argues at length why a backfill was refused — and {@code systemBlockAsSent} is what
   * stops it being read as a recording. A response that carried the same messages and dropped the
   * flag would be indistinguishable from the test above, which is the whole defect.
   */
  @Test
  void a_turn_that_recorded_no_block_is_answered_with_todays_and_says_so() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of(turn(1, 900), turn(12, 1400)));
    when(turns.blockSentAt("cnv_1", 12)).thenReturn(Optional.empty());
    when(entries.thatProjectedAt("cnv_1", 12)).thenReturn(List.of(utterance(3, 12, "and now?")));

    mvc.perform(
            get("/v1/conversations/cnv_1/projection").param("agent", "talker").param("turn", "12"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.turn").value(12))
        .andExpect(jsonPath("$.systemBlockAsSent").value(false))
        .andExpect(jsonPath("$.messages[0].content").value(agent().prompt()));
  }

  /**
   * A recorded block still gets the seam merged into it, from the rows the turn saw.
   *
   * <p><b>What is stored is the agent's prompt alone and not the assembled system message</b>,
   * because the summary behind a seam is an entry the archive already holds once and the merged
   * text is derived from it — see {@code Compaction.projectionAsOf}. This is the instrument for
   * that: a stored block that was somehow the whole merged text would double the seam here, and a
   * reading that ignored the recorded block would show the definition's prompt instead.
   *
   * <p><b>The seam this asserts on is built now, not read back.</b> {@code Projection} renders it
   * from the summary row, the span, and the agent's {@code result_list} as it stands at the read —
   * so this test pins that the two halves are merged into one system message, and not that the
   * sentence is the one the turn was sent. Nothing records that, which is the gap {@code
   * ConversationController.projection} states in the open.
   */
  @Test
  void a_recorded_block_is_merged_with_the_seam_the_turn_was_shown() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of(turn(1, 900), turn(12, 1400)));
    when(turns.blockSentAt("cnv_1", 12)).thenReturn(Optional.of(AS_SENT));
    when(entries.thatProjectedAt("cnv_1", 12))
        .thenReturn(
            List.of(
                summary(9, 3, "turns 1 to 3 diagnosed and fixed the retry budget"),
                utterance(10, 12, "and now?")));

    mvc.perform(
            get("/v1/conversations/cnv_1/projection").param("agent", "talker").param("turn", "12"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.systemBlockAsSent").value(true))
        .andExpect(jsonPath("$.messages.length()").value(2))
        .andExpect(jsonPath("$.messages[0].role").value("system"))
        .andExpect(jsonPath("$.messages[0].content", containsString(AS_SENT)))
        .andExpect(jsonPath("$.messages[0].content", containsString("Turns 1 to 3")))
        .andExpect(jsonPath("$.messages[0].content", not(containsString(agent().prompt()))));
  }

  /**
   * With no {@code turn}, the answer says it is of no turn and that its block is not a recording.
   *
   * <p>Both fields are statements and neither is a default. A projection of what the <em>next</em>
   * prompt would carry cannot be a record of what a turn was sent, because no turn has been sent
   * it; and {@code turn} being absent is what tells a saved next-prompt answer from a saved turn
   * answer, which nothing on the response did before.
   *
   * <p><b>Null and not zero</b>, on {@code turns_are_numbered_from_one}'s terms: a zero would be a
   * turn number that cannot exist standing in for the absence of one.
   */
  @Test
  void a_projection_of_the_next_prompt_names_no_turn_and_claims_no_recording() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(row("cnv_1", PAYMENTS_HOME, 12)));
    when(entries.thatProjectFor("cnv_1")).thenReturn(List.of(utterance(1, 1, "hello")));

    mvc.perform(get("/v1/conversations/cnv_1/projection").param("agent", "talker"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.turn").value(nullValue()))
        .andExpect(jsonPath("$.systemBlockAsSent").value(false))
        .andExpect(jsonPath("$.messages[0].content").value(agent().prompt()));

    verify(turns, never()).blockSentAt(any(), anyInt());
  }

  /**
   * The block a turn went out with, which is not what the agent's file says now -- the situation
   * the recording exists for.
   */
  private static final String AS_SENT =
      "You did one thing, in the version of this file that was current in June.";

  /** One entry a conversation's log holds, in whichever kind and turn a test needs. */
  private static EntryRecord utterance(int ordinal, int turnOrdinal, String content) {
    return new EntryRecord(
        "cnv_1",
        ordinal,
        EntryKind.UTTERANCE,
        content,
        null,
        List.of(),
        null,
        null,
        turnOrdinal,
        OPENED_AT,
        null,
        null,
        null);
  }

  /** The same, for what a turn came to. */
  private static EntryRecord answer(int ordinal, int turnOrdinal, String content) {
    return new EntryRecord(
        "cnv_1",
        ordinal,
        EntryKind.ANSWER,
        content,
        null,
        List.of(),
        null,
        null,
        turnOrdinal,
        OPENED_AT,
        null,
        null,
        null);
  }

  /** A standing seam: what a fold left in place of the turns through {@code throughTurn}. */
  private static EntryRecord summary(int ordinal, int throughTurn, String content) {
    return new EntryRecord(
        "cnv_1",
        ordinal,
        EntryKind.SUMMARY,
        content,
        null,
        List.of(),
        null,
        null,
        throughTurn,
        OPENED_AT,
        null,
        null,
        null);
  }

  /**
   * A real {@link Compaction}, wired over a real {@link LlmDispatcher} whose one transport fails
   * the test the moment anything asks it to make a call — and over this test's own {@link #turns},
   * {@link #compactions} and {@link #entries} mocks, and not fresh ones of its own.
   *
   * <p><b>It has to be the same three mocks.</b> {@code Compaction} holds its own reference to an
   * {@code EntryStore} and reads {@code entries.thatProjectFor} through it; a {@code Compaction}
   * built over a second, unrelated mock would never see what a test stubbed onto {@link #entries},
   * and every projection test below would be asserting against a store that always answers empty.
   *
   * <p>This is also the fixture that makes {@code
   * computing_a_projection_issues_no_request_to_a_model} a real assertion rather than a name on a
   * test that could not fail: every test in this class runs the projection route against this, so a
   * route that quietly sent the assembled prompt anywhere would turn every projection test red with
   * a 500, not only the one written to say so.
   */
  private Compaction explodingCompaction() {
    LlmDispatcher models =
        new LlmDispatcher(
            List.of(
                new LlmPool(
                    "scripted",
                    List.of("model-fast"),
                    Map.of("fast", "model-fast"),
                    4,
                    1,
                    Duration.ofSeconds(5),
                    new ExplodingTransport())),
            new NoOpTokenLedger());
    return new Compaction(
        models, ConversationControllerTest::folder, turns, compactions, entries, 64_000);
  }

  /**
   * HTTP to nowhere: every call this interface declares is one the projection route must never
   * make, so each one fails the test that reaches it rather than answering with a fixture
   * completion.
   */
  private static final class ExplodingTransport implements LlmTransport {

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      throw new AssertionError(
          "computing a projection must never dispatch a model call, and this one did");
    }

    @Override
    public Completion stream(
        String wireModel,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      throw new AssertionError(
          "computing a projection must never dispatch a model call, and this one did");
    }

    @Override
    public Embeddings embed(String wireModel, List<String> input) {
      throw new AssertionError(
          "computing a projection must never dispatch a model call, and this one did");
    }

    @Override
    public void close() {}
  }

  // --- searching the log ------------------------------------------------------------

  /**
   * A search answers with the hits, how many matched, and what it could not look at.
   *
   * <p><b>The reach is the half a page has no equivalent of.</b> A trajectory shows an ejected
   * result <em>as ejected</em>, in its place; a search cannot, because there is nothing left to
   * match on and a hit with an empty snippet would be the harness telling a reader that a
   * hundred-thousand-character result said nothing. So the count travels instead, in the same
   * envelope as the hits, and a client that renders one renders the other.
   */
  @Test
  void a_search_answers_with_hits_and_with_what_it_could_not_look_at() throws Exception {
    when(entries.search(
            Home.global(), "retry budget", 0, ConversationController.MOST_ENTRIES_A_PAGE))
        .thenReturn(
            new LogSearch(
                List.of(
                    aHit(
                        "cnv_1",
                        7,
                        EntryKind.TOOL_RESULT,
                        0.25,
                        "... the [retry] [budget] refilled ...",
                        96_000,
                        4)),
                3,
                new LogSearch.Reach(120, 4, 31)));

    mvc.perform(get("/v1/entries/search").param("q", "retry budget"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.offset").value(0))
        .andExpect(jsonPath("$.limit").value(ConversationController.MOST_ENTRIES_A_PAGE))
        .andExpect(jsonPath("$.hits.length()").value(1))
        .andExpect(jsonPath("$.hits[0].conversationId").value("cnv_1"))
        .andExpect(jsonPath("$.hits[0].ordinal").value(7))
        .andExpect(jsonPath("$.hits[0].kind").value("tool_result"))
        .andExpect(jsonPath("$.hits[0].snippet").value(containsString("[retry] [budget]")))
        .andExpect(jsonPath("$.hits[0].length").value(96_000))
        .andExpect(jsonPath("$.hits[0].supersededBy").value(4))
        .andExpect(jsonPath("$.reach.searched").value(120))
        .andExpect(jsonPath("$.reach.ejected").value(4))
        .andExpect(jsonPath("$.reach.recordedOnly").value(31));
  }

  /**
   * A search is scoped to a tier, the same way {@code GET /v1/conversations} is, and naming no
   * project is the global tier rather than everything.
   */
  @Test
  void a_search_names_the_tier_it_was_asked_of() throws Exception {
    when(entries.search(any(), any(), anyInt(), anyInt())).thenReturn(LogSearch.NONE);

    mvc.perform(get("/v1/entries/search").param("q", "budget").param("project", "payments"))
        .andExpect(status().isOk());

    verify(entries).search(PAYMENTS_HOME, "budget", 0, ConversationController.MOST_ENTRIES_A_PAGE);
  }

  /**
   * A question with nothing in it is refused rather than answered with everything or with nothing.
   *
   * <p>The empty string is what an unset field arrives as, and a search that read it as "no filter"
   * would answer with a page of the whole tier under a question nobody asked. It is the caller's
   * mistake and is corrected from the message.
   */
  @Test
  void a_search_for_nothing_is_refused_at_the_door() throws Exception {
    mvc.perform(get("/v1/entries/search").param("q", "   "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("'q'")));

    verify(entries, never()).search(any(), any(), anyInt(), anyInt());
  }

  /**
   * The same window this controller applies to a page, applied to a search: a limit past the cap is
   * narrowed and the number used is what comes back, and a limit of none is refused.
   */
  @Test
  void a_search_is_bounded_by_the_same_window_a_page_is() throws Exception {
    when(entries.search(any(), any(), anyInt(), anyInt())).thenReturn(LogSearch.NONE);

    mvc.perform(get("/v1/entries/search").param("q", "budget").param("limit", "100000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(ConversationController.MOST_ENTRIES_A_PAGE));
    verify(entries).search(Home.global(), "budget", 0, ConversationController.MOST_ENTRIES_A_PAGE);

    mvc.perform(get("/v1/entries/search").param("q", "budget").param("limit", "0"))
        .andExpect(status().isBadRequest());
  }

  /** Amendment 2: the signed-in account owns the conversation; log.open follows the row. */
  @Test
  void opening_a_conversation_names_its_owner_and_opens_it_to_the_log_stages() throws Exception {
    when(conversations.open(any(), any(), any(), any()))
        .thenReturn(row("cnv_1", PAYMENTS_HOME, 12));

    mvc.perform(
            post("/v1/conversations")
                .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "enzo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"project": "payments"}"""))
        .andExpect(status().isOk());

    verify(conversations).open(eq(PAYMENTS_HOME), any(), isNull(), eq("enzo"));
    assertEquals(List.of("opened turn cnv_1"), told.lines);
  }

  @Test
  void a_move_is_told_to_the_log_stages() throws Exception {
    when(conversations.moveTo("cnv_1", ConversationLifecycle.ARCHIVED))
        .thenReturn(archived("cnv_1"));

    mvc.perform(
            put("/v1/conversations/cnv_1/lifecycle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"lifecycle": "archived"}"""))
        .andExpect(status().isOk());

    assertEquals(List.of("moved cnv_1 archived"), told.lines);
  }

  private static LogSearch.Hit aHit(
      String conversation,
      int ordinal,
      EntryKind kind,
      double rank,
      String snippet,
      int length,
      Integer folded) {
    return new LogSearch.Hit(
        conversation, ordinal, 3, kind, rank, snippet, length, folded, null, OPENED_AT);
  }

  private static EntryPage.Row aRow(int ordinal, EntryKind kind, String text, Integer folded) {
    return new EntryPage.Row(
        ordinal,
        1,
        kind,
        text,
        text.length(),
        null,
        folded,
        null,
        kind == EntryKind.ANSWER
            ? List.of(new EntryPage.Asked("c1", "file_read", "{\"p\":1}", 900))
            : List.of(),
        null,
        OPENED_AT,
        kind == EntryKind.DIAGNOSTIC ? 120L : null);
  }

  /**
   * The agent a fold runs as, which nothing in this class asks for.
   *
   * <p>Named rather than left out all the same. A fold that could not say what it runs as would run
   * as the agent being folded, which is the defect {@code Compaction.FOLDER} exists to end --
   * {@code implementation rationale} §6.1 -- and a fixture that quietly had no folder would be the
   * one place that could not tell the two apart.
   */
  private static AgentDefinition folder() {
    return new AgentDefinition(
        Compaction.FOLDER,
        "a fixture folder",
        "fast",
        List.of(),
        List.of(),
        List.of(),
        1,
        1,
        "You summarise a span of a recorded conversation.");
  }
}
