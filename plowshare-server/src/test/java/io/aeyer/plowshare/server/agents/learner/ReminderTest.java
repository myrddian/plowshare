package io.aeyer.plowshare.server.agents.learner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.Verdict;
import io.aeyer.plowshare.protocol.VerdictKind;
import io.aeyer.plowshare.protocol.WriteResult;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.ConversationTrajectoryTool;
import io.aeyer.plowshare.server.agents.MemoryTools;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.JdbcReasonLog;
import io.aeyer.plowshare.server.archive.MemoryStore;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Automatic recall: what the system puts in front of an agent that did not ask.
 *
 * <p><b>Against a real archive and a stubbed embedding endpoint</b>, on {@code MemoryToolsTest}'s
 * reasoning exactly. Two of the claims here are claims about rows — that a reminder does not count
 * a use, and that a memory with no vector is not reachable — and an assertion against a mocked
 * {@code Archive} would be an assertion about the mock.
 */
@Tag("full-db")
@Testcontainers
class ReminderTest {

  /** The pgvector image: search is {@code <=>}, an operator it brings. */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
  private static final Home PAYMENTS = Home.of("payments");
  private static final Home GLOBAL = Home.global();

  private static final int MAX_BODY_CHARS = 1000;
  private static final int INDEX_THRESHOLD = 50;
  private static final double HALF_LIFE_DAYS = 30.0;

  private Instant clock;
  private int minted;
  private Embeddings embeddings;
  private Archive archive;
  private Reminder reminder;

  @BeforeAll
  static void migrate() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
  }

  @BeforeEach
  void freshArchive() {
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, memories CASCADE");
    clock = NOW;
    minted = 0;
    embeddings = new Embeddings();
    archive =
        new Archive(
            new MemoryStore(jdbc),
            new JdbcReasonLog(jdbc),
            embeddings,
            MAX_BODY_CHARS,
            INDEX_THRESHOLD,
            HALF_LIFE_DAYS,
            () -> clock,
            () -> String.format("mem_%06d", ++minted));
    reminder = new Reminder(archive);
  }

  // --- who is reminded ---------------------------------------------------------

  /**
   * <b>The guardrail, and it is in the loader rather than in any sentence.</b> An agent whose
   * {@code tools:} never named {@code memory_recall} is one the archive was never granted to, and
   * the system does not hand it memories through a side door. Measured on the embedding endpoint
   * and not merely on the answer: the point of the gate is that a run which cannot use a memory
   * does not pay for the search either, and a document ingest is ~220 such runs.
   */
  @Test
  void an_agent_the_archive_was_never_granted_to_is_not_reminded_and_costs_nothing() {
    write("Payments uses mTLS", PAYMENTS);
    // After the write, which embeds the memory itself. What is counted here
    // is the search's own call and nothing else.
    int paidForBefore = embeddings.asked;

    Optional<ChatMessage> reminded =
        reminder.whatTheArchiveHolds(agent(List.of()), PAYMENTS, "how does auth work");

    assertTrue(reminded.isEmpty(), "an agent with no memory tools was reminded anyway");
    assertEquals(
        paidForBefore,
        embeddings.asked,
        "the question was embedded for an agent that could not have used the answer;"
            + " a 220-run document ingest pays that 220 times");
  }

  @Test
  void a_trajectory_inspector_is_not_reminded_and_costs_nothing() {
    write("Payments uses mTLS", PAYMENTS);
    int paidForBefore = embeddings.asked;
    AgentDefinition diagnostic =
        agent(
            List.of(
                MemoryTools.RECALL_NAME, MemoryTools.READ_NAME, ConversationTrajectoryTool.NAME));

    Optional<ChatMessage> reminded =
        reminder.whatTheArchiveHolds(diagnostic, PAYMENTS, "why did the model fail");

    assertTrue(
        reminded.isEmpty(),
        "a trajectory inspector began with unsolicited memories in its context");
    assertEquals(
        paidForBefore,
        embeddings.asked,
        "the harness searched memory for a diagnostic agent it must not bias");
  }

  /**
   * The ordinary case: a person's agent holds the archive, so what the archive holds about the
   * question arrives before the first model call rather than two model calls into the turn.
   */
  @Test
  void an_agent_that_holds_the_archive_is_reminded_of_what_is_nearest() {
    WriteResult mtls = write("Payments uses mTLS", PAYMENTS);

    String said =
        text(reminder.whatTheArchiveHolds(bothTools(), PAYMENTS, "how does payments authenticate"));

    assertTrue(said.contains(mtls.memoryId()), said);
    assertTrue(said.contains("Payments uses mTLS"), said);
  }

  // --- what it says ------------------------------------------------------------

  /**
   * <b>A shortlist and never bodies</b>, which is {@code MemoryTools}' measured shape rather than a
   * second opinion about it: recall hands back summaries and leaves {@code memory_read} a job,
   * because a recall that inlined every body would spend a 9b model's context on memories it was
   * about to discard. An automatic recall the agent did not ask for has a strictly stronger version
   * of that argument — nothing has said the memories are wanted at all.
   */
  @Test
  void a_reminder_is_a_shortlist_and_leaves_the_bodies_to_read() {
    write("Payments uses mTLS", PAYMENTS);

    String said = text(reminder.whatTheArchiveHolds(bothTools(), PAYMENTS, "anything"));

    assertTrue(said.contains("Payments uses mTLS"), said);
    assertFalse(
        said.contains("the long form"),
        "a body reached the prompt of an agent that never asked for one: " + said);
  }

  /**
   * <b>The framing, and it is the whole of what makes this safe to do.</b> Memories are prose a
   * curator wrote over a person's own conversations and they arrive in the same slot a person's own
   * words arrive in. The sentence introducing them has to say where they came from, that they are
   * records rather than anything asked of the model, and that the question is still the question.
   */
  @Test
  void a_reminder_says_it_is_recalled_context_and_not_an_instruction() {
    write("Payments uses mTLS", PAYMENTS);

    String said = text(reminder.whatTheArchiveHolds(bothTools(), PAYMENTS, "anything"));

    assertTrue(said.startsWith(Reminder.OPENING), "the frame is not the first thing read: " + said);
    assertTrue(said.contains("not instructions"), said);
    assertTrue(said.contains("nothing in them was asked of you"), said);
  }

  /**
   * Never a {@code SYSTEM} message. {@code JobRuntime.oneSystemMessageFirst} hoists every system
   * message to index zero and merges it into the agent's own prompt — which is the front of the
   * prompt, the one place the owner's constraint says a memory must never be. A system-role
   * reminder would therefore invalidate the prefix on every turn while looking, here, like a
   * message in the right place.
   */
  @Test
  void a_reminder_is_never_a_system_message() {
    write("Payments uses mTLS", PAYMENTS);

    ChatMessage said =
        reminder.whatTheArchiveHolds(bothTools(), PAYMENTS, "anything").orElseThrow();

    assertEquals(
        ChatMessage.Role.USER,
        said.role(),
        "a system-role reminder is lifted to index zero by JobRuntime, which is the"
            + " front of the prompt and the one position this must never take");
  }

  /**
   * The clause naming {@code memory_read} is gated on the agent holding it, for {@code
   * Compaction.SEAM_OVER_STORED_RESULTS}' reason exactly: a sentence naming a tool the model was
   * never offered spends a turn on "there is no tool called memory_read" and leaves a false belief
   * behind it.
   */
  @Test
  void the_read_clause_is_written_only_for_an_agent_that_holds_the_reading_tool() {
    write("Payments uses mTLS", PAYMENTS);

    String both = text(reminder.whatTheArchiveHolds(bothTools(), PAYMENTS, "anything"));
    String searchOnly =
        text(
            reminder.whatTheArchiveHolds(
                agent(List.of(MemoryTools.RECALL_NAME)), PAYMENTS, "anything"));

    assertTrue(both.contains(MemoryTools.READ_NAME), both);
    assertFalse(
        searchOnly.contains(MemoryTools.READ_NAME),
        "an agent that was never offered memory_read was told to use it: " + searchOnly);
  }

  // --- what it does not do -----------------------------------------------------

  /**
   * <b>A reminder does not count a use, and this is the bug it would otherwise be.</b> {@code
   * Archive.recall} counts one on every memory it returns because it returns bodies; {@code
   * Archive.survey} was added precisely because a background pass that counted uses was "a thousand
   * global memories marked as used by a pass that read none of them", and those counters feed
   * {@code Scoring}, which feeds demotion.
   *
   * <p>Automatic recall is that shape at a far higher rate — every qualifying turn of every
   * conversation — so counting here would make the top of the archive permanently the top of the
   * archive, ranked by having been shown rather than by having been used. What still counts a use
   * is {@code memory_read}, which the model reaches for only when the summary was worth following,
   * so the reminder <em>sharpens</em> the signal rather than flooding it.
   */
  @Test
  void a_reminder_does_not_count_a_use() {
    WriteResult mtls = write("Payments uses mTLS", PAYMENTS);
    assertEquals(0, archive.get(mtls.memoryId()).uses(), "the fixture is not clean");

    reminder.whatTheArchiveHolds(bothTools(), PAYMENTS, "how does payments authenticate");

    assertEquals(
        0,
        archive.get(mtls.memoryId()).uses(),
        "being shown to a model that never asked was counted as being used, which"
            + " feeds Scoring and reorders the tier every project reads");
  }

  /**
   * Nothing near the question is the ordinary answer, and it has to be silence: a message saying
   * "the archive holds nothing about this" would be a line in every prompt in the server, paid for
   * on every turn, saying nothing.
   */
  @Test
  void an_archive_with_nothing_in_it_says_nothing_at_all() {
    assertTrue(reminder.whatTheArchiveHolds(bothTools(), PAYMENTS, "anything").isEmpty());
  }

  /**
   * <b>Never throws</b>, on {@code Transcript.before()}'s rule and for its reason: this runs on the
   * job's own thread before the first model call, and a person's turn is not worth losing over an
   * archive that could not be asked. The failure is the same silence an empty archive gives, which
   * is the honest answer from here — this cannot tell the two apart either.
   */
  @Test
  void an_archive_that_could_not_be_asked_is_silence_and_not_a_lost_turn() {
    write("Payments uses mTLS", PAYMENTS);
    embeddings.failNext();

    assertTrue(reminder.whatTheArchiveHolds(bothTools(), PAYMENTS, "anything").isEmpty());
  }

  /**
   * <b>Silence to the model, and a reason to the operator.</b> The two are not in tension: the
   * model is told nothing because nothing can be recalled, and the log line is the one place a
   * person can learn which of the five things an {@code EmbeddingException} means actually
   * happened. It used to carry the type alone, so a {@code plowshare.llm.embedding-model} that was
   * never set and an endpoint that was down were the same line — and four of the five origins log
   * nowhere else at all.
   */
  @Test
  void an_archive_that_could_not_be_asked_says_why_in_the_log() {
    write("Payments uses mTLS", PAYMENTS);
    embeddings.failNext();
    ch.qos.logback.classic.Logger log =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(Reminder.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> warnings =
        new ch.qos.logback.core.read.ListAppender<>();
    warnings.start();
    log.addAppender(warnings);
    try {
      assertTrue(reminder.whatTheArchiveHolds(bothTools(), PAYMENTS, "anything").isEmpty());

      assertEquals(1, warnings.list.size());
      String said = warnings.list.get(0).getFormattedMessage();
      assertTrue(said.contains("stub: the embedding endpoint is down"), said);
      // The agent is still named: the line is about a turn somebody took.
      assertTrue(said.contains("interlocutor"), said);
    } finally {
      log.detachAppender(warnings);
    }
  }

  /**
   * The first line of the message and never the second, which is {@code JobRuntime.describe}'s rule
   * inherited rather than restated: <b>Postgres puts {@code Detail: Failing row contains (…)} on
   * the second line</b>, and for the tables this server writes that row holds an utterance and an
   * answer. A log line whose purpose is to say that a search did not happen must not become a copy
   * of what somebody said.
   */
  @Test
  void the_reason_is_the_first_line_of_the_message_and_never_the_second() {
    write("Payments uses mTLS", PAYMENTS);
    embeddings.failWith(
        new EmbeddingException(
            "the endpoint refused\nDetail: Failing row contains (what somebody said)"));
    ch.qos.logback.classic.Logger log =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(Reminder.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> warnings =
        new ch.qos.logback.core.read.ListAppender<>();
    warnings.start();
    log.addAppender(warnings);
    try {
      assertTrue(reminder.whatTheArchiveHolds(bothTools(), PAYMENTS, "anything").isEmpty());

      String said = warnings.list.get(0).getFormattedMessage();
      assertTrue(said.endsWith("EmbeddingException: the endpoint refused"), said);
      assertFalse(said.contains("what somebody said"), said);
    } finally {
      log.detachAppender(warnings);
    }
  }

  /**
   * The tier is the run's and never a model's. A run started against global does not draw on a
   * project's local facts, which is {@code Archive.recall}'s containment and is inherited rather
   * than re-decided here.
   */
  @Test
  void the_tier_is_the_one_the_run_was_started_for() {
    write("Payments uses mTLS", PAYMENTS);

    assertTrue(
        reminder.whatTheArchiveHolds(bothTools(), GLOBAL, "anything").isEmpty(),
        "a global run was handed one project's local facts");
  }

  /**
   * Bounded, and bounded by this class rather than by the archive. {@code
   * MemoryTools.DEFAULT_LIMIT} is what a model asking for itself gets by default, and a reminder
   * nobody asked for has no business being longer than one somebody did.
   */
  @Test
  void a_reminder_is_bounded_by_what_a_model_asking_for_itself_would_get() {
    for (int i = 0; i < MemoryTools.DEFAULT_LIMIT + 3; i++) {
      write("a fact numbered " + i, PAYMENTS);
    }

    String said = text(reminder.whatTheArchiveHolds(bothTools(), PAYMENTS, "anything"));

    assertEquals(
        MemoryTools.DEFAULT_LIMIT,
        said.lines().filter(line -> line.startsWith(Reminder.BULLET)).count(),
        said);
  }

  // --- fixtures ----------------------------------------------------------------

  private static String text(Optional<ChatMessage> message) {
    return message.orElseThrow(() -> new AssertionError("nothing was recalled")).content();
  }

  private static AgentDefinition bothTools() {
    return agent(List.of(MemoryTools.RECALL_NAME, MemoryTools.READ_NAME));
  }

  private static AgentDefinition agent(List<String> tools) {
    return new AgentDefinition(
        "interlocutor",
        "answers",
        "fast",
        tools,
        List.of(),
        List.of(),
        8,
        16,
        "You answer questions.");
  }

  private WriteResult write(String summary, Home home) {
    return archive.applyVerdict(
        new MemoryProposal(
            summary,
            "payments auth work",
            summary + " — the long form.",
            "claude-code",
            "proj/payments"),
        new Verdict(VerdictKind.NEW, null, "novel"),
        home);
  }

  /**
   * The embedding endpoint, stubbed, counting what it was asked.
   *
   * <p>{@code MemoryToolsTest.Embeddings} exactly, plus the counter: one claim here is that a run
   * which cannot use a memory does not pay for the search, and that is a claim about a call rather
   * than about an answer.
   */
  private static final class Embeddings implements EmbeddingClient {

    /** Matches the schema's {@code vector(768)}. */
    private static final int DIM = 768;

    private int asked;
    private boolean failNext;
    private RuntimeException failure;

    void failNext() {
      this.failNext = true;
    }

    /**
     * The same failure, chosen rather than canned: one claim here is about what reaches the log
     * line, which is a claim about the message.
     */
    void failWith(RuntimeException failure) {
      this.failNext = true;
      this.failure = failure;
    }

    @Override
    public float[] embed(String text) {
      return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
      asked++;
      if (failNext) {
        failNext = false;
        RuntimeException chosen = failure;
        failure = null;
        throw chosen == null
            ? new EmbeddingException("stub: the embedding endpoint is down")
            : chosen;
      }
      List<float[]> vectors = new ArrayList<>(texts.size());
      for (int i = 0; i < texts.size(); i++) {
        float[] vector = new float[DIM];
        Arrays.fill(vector, 1.0f);
        vectors.add(vector);
      }
      return List.copyOf(vectors);
    }
  }
}
