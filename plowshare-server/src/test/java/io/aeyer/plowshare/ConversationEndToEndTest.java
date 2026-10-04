package io.aeyer.plowshare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.client.HttpServerClient;
import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.SessionClient;
import io.aeyer.plowshare.client.files.Workspace;
import io.aeyer.plowshare.protocol.JobEvent;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.PlowshareServerApplication;
import io.aeyer.plowshare.server.agents.FileTools;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.auth.TokenStore;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The test this slice exists for: a conversation held over a session outgrows the model's context,
 * folds, and every party can still see what was folded.
 *
 * <h2>What is real here, which is nearly all of it</h2>
 *
 * <p>The whole application context, on a random port, with Flyway and Postgres behind it. A
 * conversation is opened by {@code POST /v1/conversations} over HTTP. Each utterance is an HTTP
 * POST carrying the conversation id and the session id, and it runs through {@code
 * AgentController}, {@code Turn}, {@code JobStore}, {@code JobRuntime}, {@code Compaction}, {@code
 * TurnStore} and {@code CompactionStore}. The session is a real {@link SessionClient} holding two
 * real WebSockets, and the first turn reads a file <b>this server's own filesystem never held</b>,
 * so the file channel is load-bearing rather than merely open. The agent is the shipped {@code
 * interlocutor.md} — the same definition the REPL names — read from {@code
 * src/main/resources/agents}.
 *
 * <p><b>The one thing that is not real is the model</b>, and it is the same exception {@code
 * EndToEndTest} makes for the same reason: a suite that reached a live endpoint would have red runs
 * meaning "the model had a bad day". What this project's own record says about that class of bug —
 * that it appears only against the live node and presents as an empty result rather than an error —
 * is why the slice also carries a manual live check, which is not a test and is not here.
 *
 * <h2>The threshold is crossed by arithmetic nobody wrote down</h2>
 *
 * <p><b>Standing check 2, and it is the reason {@link Scripted} counts characters instead of
 * returning numbers a fixture chose.</b> The limit is {@link Scripted#CONTEXT}, and the accepted
 * side of it — the turns that pass without folding — must be written independently of it or the
 * test is asserting that a constant equals itself. So the transport reports {@code prompt_tokens}
 * as a quarter of the characters actually in front of it, which is a conversation's history
 * genuinely growing: the system prompt is the real interlocutor's, the utterances are this test's,
 * and the answers are whatever length the script makes them. Nothing in that chain consults {@code
 * CONTEXT}.
 *
 * <p>So the test does not assert "the fourth turn folds". It speaks until a seam appears, and
 * asserts what is actually load-bearing: that turns went by without one first (the limit really had
 * an accepted side), that one then arrived, and that everything behind it survived.
 *
 * <h2>The seam is checked from all three sides it is visible from</h2>
 *
 * <ul>
 *   <li><b>the database</b>, which is where it durably is: a {@code compactions} row, and the
 *       folded {@code turns} rows still at their own ordinals with their own text. "Nothing is
 *       deleted" is the design's claim and this is the only place it can be false;
 *   <li><b>the model</b>, which is told: the next turn's prompt carries {@code Compaction.SEAM}'s
 *       sentence and the summary, and no longer carries the folded turns verbatim. A fold that
 *       changed the row and not the prompt would be a receipt for work not done;
 *   <li><b>the person</b>, over {@code GET /v1/conversations/&#123;id&#125;/compactions} — the
 *       endpoint task 6 added because a compaction is the one thing a conversation's own speaker
 *       cannot see.
 * </ul>
 */
@Tag("full-db")
@Testcontainers
// The application must release its pool and workers before its class-owned database stops.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    // Both named, for the reason EndToEndTest sets out at length: this class
    // sits above the application's package rather than below it, so the
    // application class has to be named — and naming it turns off
    // auto-detection of the nested @TestConfiguration, which must then be
    // named too or the context quietly talks to whatever is on :1234.
    classes = {PlowshareServerApplication.class, ConversationEndToEndTest.ScriptedModel.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationEndToEndTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final int CLOSED_PORT = closedPort();

  /**
   * Generous: everything waited for here happens in milliseconds, and the number only has to
   * outlast a loaded machine's hiccup.
   */
  private static final Duration PATIENCE = Duration.ofSeconds(30);

  /**
   * How many utterances this test is willing to make before calling a conversation that never
   * folded a failure. Not a prediction of when the fold lands — see the class javadoc — but a bound
   * on a test that would otherwise run for ever if compaction stopped working altogether.
   */
  private static final int AT_MOST = 12;

  /**
   * The whole conversation's allowance. Comfortably more than {@link #AT_MOST} turns plus the
   * summaries they buy, because running out is a different test's subject and would reach this one
   * as a mystery.
   */
  private static final int ALLOWANCE = 40;

  /**
   * Text on the operator's machine and nowhere else, so a turn that quotes it crossed the file
   * socket to get it.
   *
   * <p>Without its terminator: a read's lines are the reply's only carrier now, and what is not in
   * a line is not delivered. The write below adds the newline the file has.
   */
  private static final String ONLY_ON_THE_LAPTOP =
      "record Ledger(String id) { /* only on the operator's disk */ }";

  @TempDir static Path tmp;

  private static Path onTheLaptop;

  @DynamicPropertySource
  static void wiring(DynamicPropertyRegistry registry) {
    registry.add("plowshare.data.dir", () -> tmp.resolve("server-data").toString());
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    // The second lock on the same door as ScriptedModel, and EndToEndTest's
    // reasoning verbatim: LlmConfig still builds a real dispatcher in this
    // context, and @Primary is the only thing keeping it out of the way. A
    // port nothing is listening on turns a mistake there into "connection
    // refused" rather than into a quiet measurement of whichever model
    // happens to be loaded on the machine running the build.
    registry.add("LLM_BASE_URL", () -> "http://localhost:" + CLOSED_PORT + "/v1");
    // No directory property for the shipped definitions any more —
    // interlocutor.md among them: since task 6, AgentsConfig.agentRegistry
    // always reads them off the classpath through ClasspathDefinitions,
    // which this context gets for free.
  }

  /**
   * Replaces the model, and answers the one question about it that {@code Compaction} cannot work
   * without.
   *
   * <p>{@code @Primary} for the reason {@code EndToEndTest.StubbedEmbeddings} gives: {@code
   * LlmConfig} builds a real dispatcher in this context, so without this annotation the injection
   * point has two candidates and the context does not start.
   */
  @TestConfiguration
  static class ScriptedModel {
    @Bean
    @Primary
    LlmDispatcher scriptedDispatcher() {
      return new LlmDispatcher(
          List.of(
              new LlmPool(
                  "scripted",
                  // The embedding model is listed although nothing here embeds:
                  // LlmConfig's dispatchingEmbeddingClient calls requireServed
                  // on it at boot, and a pool that did not serve it would fail
                  // the context rather than this test's subject.
                  List.of("nomic-embed-text", Scripted.WIRE),
                  Map.of("fast", Scripted.WIRE, "reasoning", Scripted.WIRE),
                  2,
                  2,
                  Duration.ofSeconds(10),
                  MODEL)),
          new NoOpTokenLedger(),
          // A SYSTEM BINDING, because a fold is a system function now.
          // conversation_folder declares `model: system.compaction`, so
          // a dispatcher built with the two-argument constructor
          // answers every system specifier "nothing serves this" and
          // every fold in this context fails into the branch that lets
          // the turn run with its whole history -- silently, and this
          // test then speaks twelve turns and folds nothing. The
          // shipped application.yml binds `plowshare.llm.system`, and
          // this is that binding for a context whose dispatcher is
          // replaced wholesale.
          type -> "fast");
    }
  }

  private static final Scripted MODEL = new Scripted();

  /**
   * The agent every turn here is spoken to.
   *
   * <p>The same string {@code INTERLOCUTOR} holds, and <b>a second copy of it rather than a
   * reference</b>: that constant is package-private in {@code io.aeyer.plowshare.client.cli} and
   * this test is not in that package. So the two can drift, and what stops the drift mattering is
   * not this line — it is that {@code AgentRegistry} keys a definition by the {@code name:} in its
   * front matter and answers an unknown one with a 400 naming the agents that exist. A rename of
   * the shipped file fails here loudly, on the first turn.
   */
  private static final String INTERLOCUTOR = "interlocutor";

  @LocalServerPort private int port;

  @Autowired private JdbcTemplate jdbc;

  /**
   * The server's own store, so this suite's credential is one this server issued rather than one a
   * fixture invented.
   */
  @Autowired private TokenStore tokens;

  /**
   * A live access token for the test that is running.
   *
   * <p>This class is where the CLI's transport of it is measured end to end: {@link #attach()}
   * opens <b>two WebSocket upgrades</b> through {@code SessionClient}, both of which are now behind
   * the filter, and both of which carry this value as an {@code Authorization} header put on by
   * okhttp. The failure when it is not sent is not subtle — {@code attach} refuses with "the
   * file-provider role was refused at v1/files ... (401)".
   *
   * <p>Never an operand of anything that prints its operands; see {@code TokensTest}.
   */
  private String access;

  private final List<AutoCloseable> opened = new ArrayList<>();

  @BeforeEach
  void freshFixture() throws IOException {
    MODEL.reset();
    jdbc.update(
        "INSERT INTO admins (handle, password_hash, server_admin) VALUES ('test-user', 'fixture-hash', TRUE)"
            + " ON CONFLICT DO NOTHING");
    access = tokens.issuePair("test-user", false).access();
    if (onTheLaptop == null) {
      onTheLaptop = Files.createDirectory(tmp.resolve("laptop")).toRealPath();
      Files.writeString(onTheLaptop.resolve("Ledger.java"), ONLY_ON_THE_LAPTOP + "\n");
    }
    // The container is per-class and rows outlive a test. CASCADE because
    // compactions and turns both reference conversations.
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, conversations CASCADE");
  }

  @AfterEach
  void closeWhatThisTestOpened() {
    for (AutoCloseable open : opened) {
      try {
        open.close();
      } catch (Exception ignored) {
        // Closing a fixture is not what any test here is about.
      }
    }
    opened.clear();
  }

  // --- the slice's own proof ------------------------------------------------------

  @Test
  void a_conversation_over_a_session_outgrows_its_context_and_the_seam_survives() throws Exception {
    SessionClient session = attach();
    ServerClient server = new HttpServerClient("http://localhost:" + port, access);
    ServerClient.Conversation conversation = server.openConversation("ledger-test", ALLOWANCE);

    // The first turn reaches the operator's disk, so the session on this path
    // is doing something rather than merely being attached. Every later turn
    // is prose, because what is being measured after this is the history.
    MODEL.readsAFileOnce(onTheLaptop.resolve("Ledger.java"));

    List<String> said = new ArrayList<>();
    int seamAfter = 0;
    int turnsWithNoSeam = 0;
    for (int turn = 1; turn <= AT_MOST && seamAfter == 0; turn++) {
      String utterance = "utterance " + turn + ": say more about the ledger";
      said.add(utterance);
      String job = session.submit(INTERLOCUTOR, utterance, null, conversation.id());
      ServerClient.JobStatus status = awaitOutcome(session, job);
      assertEquals(
          Outcome.Ending.ANSWERED.name(),
          status.outcome().ending(),
          "turn " + turn + " answered — " + status.outcome().detail());
      if (server.compactions(conversation.id()).isEmpty()) {
        turnsWithNoSeam++;
      } else {
        seamAfter = turn;
      }
    }

    // The file really did cross the socket, on the turn that asked for it.
    assertTrue(
        MODEL.toolResults().stream().anyMatch(r -> r.contains(ONLY_ON_THE_LAPTOP)),
        "the first turn read a file only the operator's machine has, over the session's"
            + " own file channel — "
            + MODEL.toolResults());
    assertFalse(
        Files.exists(tmp.resolve("Ledger.java")), "and this server's own tree never held it");

    // The limit had an accepted side and it was exercised. Without this a
    // conversation that folded on its very first opportunity would pass, and
    // the test would be asserting that compaction is unconditional.
    assertTrue(
        turnsWithNoSeam >= 2,
        "turns went by without folding before one folded, so the threshold is a"
            + " threshold rather than a switch — "
            + turnsWithNoSeam
            + " of them");
    assertTrue(
        seamAfter > 0,
        "and the history did eventually outgrow a third of "
            + Scripted.CONTEXT
            + " tokens: "
            + AT_MOST
            + " turns measured "
            + MODEL.measured()
            + " and folded nothing");

    // The turn calls and the single successful fold both stream. Counting the fold
    // separately also catches a duplicate summarising call at this crossing.
    assertEquals(
        MODEL.turnCalls() + 1,
        MODEL.sinkCalls.get(),
        "every turn and the single compaction summariser are streamed");

    // --- side one: the database, where nothing was deleted ---------------------

    List<Map<String, Object>> seams =
        jdbc.queryForList(
            "SELECT through_ordinal, summary FROM compactions WHERE conversation_id = ?"
                + " ORDER BY through_ordinal",
            conversation.id());
    assertEquals(1, seams.size(), "one fold, from one crossing");
    int through = ((Number) seams.get(0).get("through_ordinal")).intValue();
    assertTrue(
        through >= 1 && through <= seamAfter - 1,
        "a fold reaches through the second-to-last turn at most, so the exchange the"
            + " person is still in stays verbatim — through "
            + through
            + " of "
            + seamAfter);
    assertEquals(
        Scripted.SUMMARY,
        seams.get(0).get("summary"),
        "and the summary is what the model wrote, stored as it wrote it");

    List<Map<String, Object>> kept =
        jdbc.queryForList(
            "SELECT ordinal, utterance, answer FROM turns WHERE conversation_id = ?"
                + " ORDER BY ordinal",
            conversation.id());
    assertEquals(
        seamAfter,
        kept.size(),
        "every turn spoken is still a row, the folded ones included — nothing a"
            + " compaction stands for is deleted");
    for (int i = 0; i < through; i++) {
      assertEquals(
          said.get(i),
          kept.get(i).get("utterance"),
          "turn " + (i + 1) + " is behind the seam and still says what was said");
      assertNotNull(
          kept.get(i).get("answer"),
          "and still holds what came back, which is what 'read behind the seam'" + " means");
    }

    // --- side two: the model, which is told it is reading a summary -------------

    String next = "utterance after the fold: what did I ask you first?";
    String job = session.submit(INTERLOCUTOR, next, null, conversation.id());
    assertEquals(Outcome.Ending.ANSWERED.name(), awaitOutcome(session, job).outcome().ending());

    // The shape a real backend would see, asserted here because this is the
    // only test that assembles a post-fold prompt end to end.
    //
    // NOT a tidiness rule. The live check against qwen3.5-9b found that a
    // second system message ends every turn after a fold UNAVAILABLE, the
    // endpoint saying "System message must be at the beginning" — and that
    // no scripted transport and no MockWebServer in this repository rejects
    // a message list, so the whole suite read it as green. JobRuntime.opening
    // is where the rule is enforced and JobRuntimeTest holds it directly;
    // this is the same claim about the message list a conversation actually
    // produces, which is the one that broke.
    List<ChatMessage> afterTheFold = MODEL.lastTurnMessages();
    assertEquals(
        1,
        afterTheFold.stream().filter(m -> m.role() == ChatMessage.Role.SYSTEM).count(),
        "one system message, because a model whose template allows one refuses the"
            + " request outright and the turn ends UNAVAILABLE — "
            + afterTheFold);
    assertEquals(
        ChatMessage.Role.SYSTEM,
        afterTheFold.get(0).role(),
        "and it is first, which is the other half of what that template asks");

    String prompt = MODEL.lastTurnPrompt();
    assertTrue(prompt.contains(Scripted.SUMMARY), "the turn after the fold is shown the summary");
    assertTrue(
        prompt.contains("were summarised to make room"),
        "introduced as a summary and not spliced in as though it were what was said —"
            + " a reader who cannot see the join has no way to ask for what is"
            + " behind it");
    assertFalse(
        prompt.contains(said.get(0)),
        "and the folded turn is no longer in front of the model verbatim, which is the"
            + " whole point of having paid for a summary");
    assertTrue(
        prompt.contains(said.get(seamAfter - 1)),
        "while the exchange the person is still in is there in their own words");

    // --- side three: the person, who could not otherwise see any of it ----------

    // The following turn can schedule another asynchronous fold before this read.
    // Every seam is retained oldest first, so the original fold must still be visible.
    List<ServerClient.Seam> visible = server.compactions(conversation.id());
    assertFalse(visible.isEmpty(), "the terminal is told about the original seam");
    assertEquals(through, visible.get(0).throughOrdinal(), "how far back it reaches");
    assertEquals(
        Scripted.SUMMARY,
        visible.get(0).summary(),
        "and what stands in its place, which is the only route a person has to it —"
            + " a fold publishes no JobEvent and changes nothing on the wire");
  }

  /**
   * The lifecycle a person watches while all that happens, and what it does not carry.
   *
   * <p>The events arrive on the second of the session's two real sockets. The assertion is the
   * containment one: a conversation puts a file's contents and a model's prose through this server,
   * and no event holds either. {@code JobEventTest} holds that by signature over a fake seam; this
   * holds it over a socket, on the path a conversation actually takes.
   */
  @Test
  void the_events_of_a_turn_reach_the_terminal_and_carry_nothing_that_was_said() throws Exception {
    SessionClient session = attach();
    ServerClient server = new HttpServerClient("http://localhost:" + port, access);
    ServerClient.Conversation conversation = server.openConversation("ledger-test", ALLOWANCE);
    MODEL.readsAFileOnce(onTheLaptop.resolve("Ledger.java"));

    String utterance = "read the ledger and tell me about it";
    String job = session.submit(INTERLOCUTOR, utterance, null, conversation.id());
    List<JobEvent> events = awaitEnded(session, job);

    assertEquals(
        List.of(
            JobEvent.STARTED,
            JobEvent.MODEL_CALL,
            JobEvent.TOOL_CALLED,
            JobEvent.MODEL_CALL,
            JobEvent.ENDED),
        events.stream().map(JobEvent::kind).toList(),
        "a turn's whole life, in order, on the person's own terminal");
    assertEquals(
        FileTools.READ_NAME,
        events.stream()
            .filter(e -> JobEvent.TOOL_CALLED.equals(e.kind()))
            .findFirst()
            .orElseThrow()
            .tool(),
        "the tool is named, because the name is this server's own");
    for (JobEvent event : events) {
      for (String field :
          new String[] {event.job(), event.agent(), event.tool(), event.ending(), event.kind()}) {
        if (field == null) {
          continue;
        }
        assertFalse(field.contains(ONLY_ON_THE_LAPTOP), "no event replays what was read");
        assertFalse(field.contains(Scripted.ANSWER), "and none replays what was said");
        assertFalse(field.contains("Ledger.java"), "nor which file it was");
      }
    }
  }

  /**
   * A conversation with nothing left refuses the next turn, and the refusal names the budget rather
   * than failing generically.
   *
   * <p>Nothing has raised this one, and there is nothing in flight to raise it on: an operator
   * moves a ceiling on a run that is going, and this conversation's last run has ended.
   *
   * <p><b>This measures the current behaviour and does not close the spec's open question.</b> What
   * *should* happen when a conversation's allowance is gone — a refill, a fresh conversation seeded
   * with a summary, an end — is still a person's decision, and what is asserted here is only that
   * the server says which of the two 409s it is instead of leaving a terminal to guess.
   */
  @Test
  void a_conversation_with_nothing_left_refuses_the_next_turn_by_name() throws Exception {
    SessionClient session = attach();
    ServerClient server = new HttpServerClient("http://localhost:" + port, access);
    // Two calls: one turn's worth on this script, and then nothing.
    ServerClient.Conversation conversation = server.openConversation(null, 1);

    String job =
        session.submit(INTERLOCUTOR, "one utterance is all this affords", null, conversation.id());
    assertEquals(
        Outcome.Ending.ANSWERED.name(),
        awaitOutcome(session, job).outcome().ending(),
        "the first turn is affordable and is spent");

    ServerClient.ServerError refused =
        assertThrows(
            ServerClient.ServerError.class,
            () -> session.submit(INTERLOCUTOR, "and a second", null, conversation.id()));

    assertEquals(409, refused.status(), "the row is there and the server will not do this to it");
    assertTrue(
        refused.getMessage().contains("model calls"),
        "and the refusal names the budget rather than being a generic failure — "
            + refused.getMessage());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM turns WHERE conversation_id = ?",
            Integer.class,
            conversation.id()),
        "the refused utterance is not a turn: nothing ran, so nothing is recorded");
  }

  /**
   * Both sockets are gated, in the context production builds.
   *
   * <p><b>The claim the whole auth design rests on, measured where it matters most.</b> The design
   * says a WebSocket upgrade is an ordinary HTTP request before it is a socket, so one servlet
   * filter covers {@code /v1/events}, {@code /v1/files} and the REST API. {@code AuthFilterTest}
   * proves it against a hand-built context; this proves it against {@code @SpringBootTest} on
   * {@code PlowshareServerApplication}, where a filter that was registered in a test's own wiring
   * and nowhere else would show up as an upgrade that opened.
   *
   * <p>It is also the containment test for the rest of this class. Every other test here now
   * attaches with a credential, so all of them would stay green if the filter were deleted — they
   * would be sending a header nothing reads. This is the one that fails for that.
   *
   * <p>{@code attach} refuses both roles rather than one, and both are asserted: a client left
   * holding the listener and not the provider would submit runs whose files nothing could answer,
   * which is the smaller-capability-by-accident {@code SessionClient} exists to refuse.
   */
  @Test
  void an_unauthenticated_session_attaches_neither_role() {
    Workspace workspace = new Workspace();
    workspace.set(List.of(onTheLaptop));
    SessionClient anonymous =
        new SessionClient(new HttpServerClient("http://localhost:" + port), workspace);
    opened.add(anonymous);

    IOException refused = assertThrows(IOException.class, () -> anonymous.attach(PATIENCE));

    assertTrue(
        refused.getMessage().contains("401"),
        "a session attached without a credential, or was refused for some other"
            + " reason than the filter — "
            + refused.getMessage());
    assertFalse(
        anonymous.providing(),
        "the file-provider socket opened, so /v1/files is not behind the filter and a"
            + " stranger can answer this operator's file requests");
    assertFalse(
        anonymous.listening(),
        "the listener socket opened, so /v1/events is not behind the filter and a"
            + " stranger can watch this operator's jobs go by");
  }

  // --- fixtures --------------------------------------------------------------------

  private SessionClient attach() throws Exception {
    Workspace workspace = new Workspace();
    workspace.set(List.of(onTheLaptop));
    SessionClient session =
        new SessionClient(
            new HttpServerClient("http://localhost:" + port, access),
            workspace,
            access,
            io.aeyer.plowshare.client.files.Rooting.of(onTheLaptop, "ledger-test"));
    opened.add(session);
    session.attach(PATIENCE);
    assertTrue(
        session.providing() && session.listening(),
        "the session attached in both roles, over two real sockets");
    return session;
  }

  private static ServerClient.JobStatus awaitOutcome(SessionClient session, String job)
      throws Exception {
    for (long waited = 0; waited < PATIENCE.toMillis(); waited += 10) {
      ServerClient.JobStatus status = session.job(job);
      if (status.outcome() != null) {
        return status;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("job " + job + " never finished");
  }

  private static List<JobEvent> awaitEnded(SessionClient session, String job) throws Exception {
    List<JobEvent> events = new ArrayList<>();
    for (long waited = 0; waited < PATIENCE.toMillis(); waited += 10) {
      JobEvent event = session.nextEvent(Duration.ofMillis(10));
      if (event == null) {
        continue;
      }
      if (job.equals(event.job())) {
        events.add(event);
        if (JobEvent.ENDED.equals(event.kind())) {
          return events;
        }
      }
    }
    throw new AssertionError("job " + job + " never said it ended; saw " + events);
  }

  /**
   * A port nothing is listening on, so a call that escapes the stub fails loudly instead of
   * reaching a model somebody happens to be running.
   */
  private static int closedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException("could not find a closed port", e);
    }
  }

  /**
   * The model, scripted — and the one place a number in this test comes from.
   *
   * <h2>{@code prompt_tokens} is counted, not chosen</h2>
   *
   * <p>A quarter of the characters actually in front of it. That is not a claim about any real
   * tokenizer, and it does not have to be: what the test needs is a measurement that <b>grows with
   * the history the way a real one does</b> and that no fixture author tuned against {@link
   * #CONTEXT}. A constant per turn would have made the crossing a thing this file decided rather
   * than a thing the conversation did.
   *
   * <h2>A summariser call is told apart by having no tools</h2>
   *
   * <p>The folder offers no tools, while turn calls carry the interlocutor's schemas. Both use
   * streaming. This fixture distinguishes their answers by tools, and counts streaming separately
   * so a blocking fold or a duplicate fold cannot pass unnoticed.
   */
  private static final class Scripted implements LlmTransport {

    /** The wire model both classes resolve to, as one node's would. */
    static final String WIRE = "scripted-model";

    /** How many model calls took the streaming path. See {@link #stream}. */
    final java.util.concurrent.atomic.AtomicInteger sinkCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    /**
     * What this node reports itself loaded to.
     *
     * <p>Small, and the size is the only thing about it that matters: the real box is loaded at
     * 128000 and a suite that had to speak a hundred thousand tokens' worth of conversation to
     * reach a fold would be measuring patience. Everything the decision is taken from — the
     * measurement, the headroom, the reach — is computed by the production code from the
     * conversation's own turns either way.
     *
     * <p><b>This number is the limit and is allowed to be chosen; what must not be chosen is the
     * accepted side of it.</b> Measured, by raising it out of reach and reading what twelve turns
     * actually cost: {@code [703, 721, 1012, 1322, 1631, 1941, 2250, 2560, ...]} — the first two
     * being turn one's two calls, the rest one call each, and the ~310 between them being an
     * utterance plus an answer. The real interlocutor's system prompt is the 703, which is within
     * six per cent of the 667 task 5 measured against the live node for the same file. None of
     * those numbers is written anywhere in this test, which is the point.
     *
     * <p><b>The window, and not the size a conversation folds at.</b> Those became different
     * numbers when {@code Compaction} started folding at a third of the window rather than at the
     * wall. This test used to leave the threshold to be derived; since spec
     * 2026-09-30-fold-at-60-and-80 a window of 64K or less derives <em>no</em> between-turn fold at
     * all, and a window this small is what lets twelve turns cross a threshold. So the transport
     * configures one — a third of this, the rule the arithmetic below was written against — through
     * {@code compaction-thresholds}, the override the spec keeps, and holds the fold inside a turn
     * at the wall; {@code FoldThresholdsTest} is where the derived defaults are pinned. The fold
     * lands where a third of this lands.
     *
     * <p>Headroom settles at turn one's own measured cost. The fixture window leaves room for the
     * shipped prompt, tool schemas and code-navigation hint before repeated replies cross its
     * explicit one-third threshold. That prompt grows as capabilities are added; the assertions
     * still require both multiple accepted turns and a fold within {@link #AT_MOST}, rather than
     * hard-coding the turn on which folding must happen.
     */
    static final int CONTEXT = 12000;

    /**
     * What the summariser answers, distinctive enough that finding it is finding the summary and
     * not something that resembles one.
     */
    static final String SUMMARY =
        "Notes: the person asked repeatedly about a ledger record and its identifier.";

    /**
     * What a turn answers. Long, because the history is what has to grow, and a conversation of
     * one-word replies would take a hundred turns to outgrow anything.
     */
    static final String ANSWER =
        ("The ledger is a record with one component. "
                + "It is declared in Ledger.java and carries an identifier. ")
            .repeat(12);

    private final List<List<ChatMessage>> turnPrompts = new CopyOnWriteArrayList<>();
    private final List<String> toolResults = new CopyOnWriteArrayList<>();
    private final List<Integer> measured = new CopyOnWriteArrayList<>();

    /** Set for the one call that should ask for a file, and cleared by it. */
    private volatile String readOnce;

    void reset() {
      turnPrompts.clear();
      sinkCalls.set(0);
      toolResults.clear();
      measured.clear();
      readOnce = null;
    }

    void readsAFileOnce(Path path) {
      readOnce = path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Every tool result this model was handed, which is where a file's own bytes arrive after
     * crossing the session.
     */
    List<String> toolResults() {
      return List.copyOf(toolResults);
    }

    /**
     * What each turn call was charged, for a failure message that says how far the history actually
     * got.
     */
    /**
     * How many model calls a turn made — the summariser's is not one, since it is the only chat
     * call in this server that carries no tools.
     */
    int turnCalls() {
      return turnPrompts.size();
    }

    List<Integer> measured() {
      return List.copyOf(measured);
    }

    /**
     * The last prompt a *turn* put in front of the model, flattened. Turn calls only: a
     * summariser's prompt holds the transcript too, so including it would let an assertion about
     * what the next turn is shown pass on the summary request instead.
     */
    /**
     * The last prompt a turn put in front of the model, as messages — so a test can ask about roles
     * and order and not only about text.
     */
    List<ChatMessage> lastTurnMessages() {
      return turnPrompts.get(turnPrompts.size() - 1);
    }

    String lastTurnPrompt() {
      List<ChatMessage> last = turnPrompts.get(turnPrompts.size() - 1);
      StringBuilder text = new StringBuilder();
      for (ChatMessage message : last) {
        text.append(message.content() == null ? "" : message.content()).append('\n');
      }
      return text.toString();
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public OptionalInt contextLength(String wireModel) {
      return WIRE.equals(wireModel) ? OptionalInt.of(CONTEXT) : OptionalInt.empty();
    }

    /** A third of the window, configured; see {@link #CONTEXT}. */
    @Override
    public OptionalInt compactionThreshold(String wireModel) {
      return WIRE.equals(wireModel) ? OptionalInt.of(CONTEXT / 3) : OptionalInt.empty();
    }

    /** The fold inside a turn held at the wall, so this test's folds are between turns. */
    @Override
    public OptionalInt compactionNowThreshold(String wireModel) {
      return WIRE.equals(wireModel) ? OptionalInt.of(CONTEXT) : OptionalInt.empty();
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      int chars = 0;
      for (ChatMessage message : messages) {
        chars += message.content() == null ? 0 : message.content().length();
        if (message.role() == ChatMessage.Role.TOOL && message.content() != null) {
          toolResults.add(message.content());
        }
      }
      TokenUsage usage = TokenUsage.of(Math.max(1, chars / 4), 100, null);
      if (tools.isEmpty()) {
        // No tools offered: this is Compaction.summarise and nothing else
        // in this server makes a chat call without them.
        return new Completion(SUMMARY, "stop", usage, List.of());
      }
      turnPrompts.add(List.copyOf(messages));
      measured.add(usage.promptTokens());
      String wanted = readOnce;
      if (wanted != null) {
        readOnce = null;
        return new Completion(
            "let me look",
            "tool_calls",
            usage,
            List.of(new ToolCall("c1", FileTools.READ_NAME, "{\"path\":\"" + wanted + "\"}")));
      }
      return new Completion(ANSWER, "stop", usage, List.of());
    }

    @Override
    public Completion stream(
        String wireModel,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      // Both the turn loop and the folder reach this path. complete below supplies
      // the scripted answer; sinkCalls records how production dispatched the call.
      sinkCalls.incrementAndGet();
      Completion streamed = complete(wireModel, messages, sampling, tools);
      if (abandoned.getAsBoolean()) {
        throw new CallerAbandonedException(poolName());
      }
      String content = streamed.content();
      if (content != null && !content.isEmpty()) {
        sink.answered(content);
      }
      return streamed;
    }

    @Override
    public Embeddings embed(String wireModel, List<String> input) {
      throw new UnsupportedOperationException("no conversation embeds");
    }

    @Override
    public void close() {}
  }
}
