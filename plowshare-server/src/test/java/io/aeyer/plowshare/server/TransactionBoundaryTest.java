package io.aeyer.plowshare.server;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.MemoryState;
import io.aeyer.plowshare.protocol.Verdict;
import io.aeyer.plowshare.protocol.VerdictKind;
import io.aeyer.plowshare.protocol.WriteResult;
import io.aeyer.plowshare.server.api.MemoryController;
import io.aeyer.plowshare.server.api.WriteMemoryRequest;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.ArchiveProperties;
import io.aeyer.plowshare.server.archive.MemoryStore;
import io.aeyer.plowshare.server.archive.ReasonLog;
import io.aeyer.plowshare.server.archive.TocEntry;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.DispatchingEmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.openai.OpenAiTransport;
import io.aeyer.plowshare.server.llm.tokens.RatioTokenizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The archive's central promise, against the real transaction manager: a write that has been
 * accepted is never lost, and a supersession never half-applies.
 *
 * <p><b>Why this class exists at all.</b> Both properties were, until this file, asserted by
 * nothing. {@code MemoryControllerTest} builds its MockMvc with {@code standaloneSetup}, which
 * creates no Spring context and therefore applies no transaction proxy; {@code EndToEndTest} runs a
 * real context but only ever asks the archive for happy paths. Deleting all five
 * {@code @Transactional} annotations from {@code MemoryController} left the suite 170/170 green.
 * That is the gap: everything here needs a real context, a real pool and a real Postgres, and none
 * of it can be demonstrated with a mock.
 *
 * <p>Three of the four tests are three faces of one root cause. The embedding model was being
 * called from inside the write transaction — see {@code UnitOfWork} for the account — so an
 * unexpected failure from it rolled the write back, and a slow response from it held a pooled
 * connection hostage. The fourth, {@code
 * a_base_url_with_no_scheme_keeps_the_memory_and_answers_the_caller_normally}, is the incident
 * itself end to end, over the real dispatcher chain.
 */
@Testcontainers
@SpringBootTest
class TransactionBoundaryTest {

  /**
   * The pgvector image, not stock postgres:16: the migration's first line is CREATE EXTENSION
   * vector, and stock Postgres has no vector.so to load.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  /**
   * Two connections, not the default ten.
   *
   * <p>{@code an_embedding_that_stalls_holds_no_database_connection} needs to exhaust the pool, and
   * exhausting ten would mean ten threads and ten stalled model calls to prove a thing two
   * demonstrate exactly as well. The production arithmetic is the same shape: ten concurrent writes
   * against Hikari's default of ten, during an LM Studio stall, and every read on the server queues
   * behind them.
   */
  private static final int POOL_SIZE = 2;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> POOL_SIZE);
  }

  @Autowired private MemoryController controller;
  @Autowired private Archive archive;
  @Autowired private MemoryStore store;
  @Autowired private ReasonLog reasons;
  @Autowired private UnitOfWork unitOfWork;
  @Autowired private ArchiveProperties archiveProps;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StubEmbeddings embeddings;
  @Autowired private HalfFailingStore halfFailing;

  private static final Home PAYMENTS = Home.of("payments");

  @BeforeEach
  void freshArchive() {
    // CASCADE because V2's `proposals` references this table: a plain
    // TRUNCATE is refused outright, and this class holds no proposals of
    // its own to lose.
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, memories CASCADE");
    embeddings.reset();
    halfFailing.failOnRetiring(false);
  }

  // --- 1. the write survives any failure of the embedding call --------------

  /**
   * An embedding client that throws something the archive does not expect must not take the memory
   * with it — and must no longer take the request with it either.
   *
   * <p><b>This test's assertions changed, and that is a behaviour change worth saying out loud
   * rather than absorbing.</b> It used to assert {@code
   * assertThrows(IllegalArgumentException.class, …)}, pinning {@code Archive.embed}'s narrow catch:
   * an unexpected exception escaped, on the reasoning that it "should be loud" and the row was
   * committed anyway. The exception thrown here is still the real one, verbatim — OkHttp's answer
   * to an {@code LLM_BASE_URL} with no {@code http://}.
   *
   * <p>That reasoning held only while nothing acted on the exception. {@code
   * PromotionQueue.approve} settles a proposal, promotes, and <em>releases the claim</em> if the
   * promotion throws — so a throw raised after the commit tells it to undo something that already
   * happened, and it did: a global record on disk, the origin a tombstone, and the proposal back on
   * a human's screen where every future approval is refused forever. {@code PromotionQueueTest
   * .a_promotion_that_committed_is_not_given_back_when_the_embedding_write_fails} is that failure,
   * and it cannot be fixed in that catch — a caller cannot tell a pre-commit throw from a
   * post-commit one.
   *
   * <p>So the post-commit call swallows, and the loudness moves to the log, which is asserted below
   * rather than assumed: the expected failure is a warning, and this one is an error <em>carrying
   * the throwable</em>, so the stack is on disk. What is no longer done is failing an operation
   * that succeeded — after the commit there is no true failure left to report, and the missing
   * vector is surfaced by {@code Recall.unsearchable}, {@code TocEntry.unsearchable} and {@code
   * reembed}, which persist where an exception does not.
   */
  @Test
  void a_write_survives_an_embedding_failure_the_archive_does_not_expect() {
    embeddings.failWith(
        () ->
            new IllegalArgumentException(
                "Expected URL scheme 'http' or 'https' but was 'localhost'"));
    Logger archiveLog = (Logger) LoggerFactory.getLogger(Archive.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    archiveLog.addAppender(captured);

    WriteResult written;
    try {
      written = controller.write(newMemoryRequest("payments", "The retry budget is 4")).getBody();
    } finally {
      archiveLog.detachAppender(captured);
    }

    // The caller is answered normally. The write succeeded; nothing about a
    // side service that failed afterwards belongs in the answer to it.
    assertNotNull(written);
    assertNotNull(written.memoryId());
    assertEquals(1, rowCount(), "the write was accepted and must still be there");
    Memory kept = archive.get(written.memoryId());
    assertEquals(MemoryState.ACTIVE, kept.state());
    assertEquals("The retry budget is 4", kept.summary());
    // No vector, which is the documented cost of an endpoint that was not
    // working: still active, still readable by id, still in the index, and
    // reachable by everything except vector search.
    assertNull(embeddingOf(kept.id()));

    // Swallowed is not silent, and this is the assertion that makes that
    // sentence true rather than a hope. ERROR and not WARN, because an
    // endpoint being down is routine and this is not — and with the
    // throwable attached, or the stack of a bug in the archive would be
    // lost exactly where somebody needs it.
    ILoggingEvent event =
        captured.list.stream()
            .filter(e -> e.getMessage().contains("does not expect"))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "an unexpected embedding failure left no trace at all: " + captured.list));
    assertEquals(Level.ERROR, event.getLevel(), "an unexpected failure was logged as routine");
    assertNotNull(
        event.getThrowableProxy(),
        "logged without the throwable, so the stack of whatever went wrong is lost");
    assertTrue(event.getFormattedMessage().contains(kept.id()), event.getFormattedMessage());
  }

  /**
   * The counterpart, and the reason the two clauses in {@code Archive.embed} are two clauses.
   *
   * <p>An endpoint that is down is routine — it is what {@link
   * io.aeyer.plowshare.server.llm.EmbeddingException} is <em>for</em>, and what {@code reembed}
   * exists to repair afterwards. Since nothing escapes {@code embed} any more, the log is the only
   * place the routine failure and the unexpected one are still distinguishable, so folding the two
   * clauses into one would put a stack trace in the log every time LM Studio is restarted and leave
   * nothing to mark the case that deserves one. Measured: with the clauses folded together this is
   * the test that fails.
   */
  @Test
  void an_embedding_failure_the_archive_does_expect_is_logged_as_routine() {
    embeddings.failWith(() -> new EmbeddingException("the endpoint is down"));
    Logger archiveLog = (Logger) LoggerFactory.getLogger(Archive.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    archiveLog.addAppender(captured);

    WriteResult written;
    try {
      written = controller.write(newMemoryRequest("payments", "The retry budget is 4")).getBody();
    } finally {
      archiveLog.detachAppender(captured);
    }

    assertNotNull(written);
    assertEquals(1, rowCount());
    assertNull(embeddingOf(written.memoryId()));

    ILoggingEvent event =
        captured.list.stream()
            .filter(e -> e.getMessage().contains("without an embedding"))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "a routine embedding failure left no trace: " + captured.list));
    assertEquals(
        Level.WARN,
        event.getLevel(),
        "an endpoint being down is routine and must not read like a bug");
    assertNull(
        event.getThrowableProxy(),
        "a stack trace for the ordinary case buries the one that deserves it");
  }

  /**
   * A memory written while the endpoint is misconfigured is still a memory.
   *
   * <p>This is the assertion the original incident was about. A base URL missing its scheme made
   * OkHttp throw {@code IllegalArgumentException}, which is not the type {@code Archive.embed}
   * catches, so the write was lost on the way out — and the agent was told its proposal was
   * malformed. The exception's <em>currency</em> is checked one layer down in {@code
   * DispatchingEmbeddingClientTest}; what is checked here is the outcome that matters: the row is
   * on disk, and it is unembedded rather than absent.
   *
   * <p>Restored, having stood as a comment since Task 3: the version that ran built the deleted
   * {@code OpenAiEmbeddingClient} by hand, and {@code base-url} is a pool's property now. This is
   * the same claim over the chain that replaced it — a real {@link OpenAiTransport}, a real {@link
   * LlmPool}, a real {@link LlmDispatcher}, and {@link DispatchingEmbeddingClient} as the only
   * thing between them and {@link Archive}. No socket is opened: the URL never parses, so the
   * failure happens before any call leaves the process.
   *
   * <p>The whole chain rather than a stub, because the guard under test is a <em>translation</em>
   * and a stub would be free to raise the translated type with nothing having translated anything.
   * Delete {@code catch (LlmException)} from {@code DispatchingEmbeddingClient.embedAll} and this
   * fails — measured, not assumed.
   *
   * <p><b>But note where it fails, because it is not where the incident report would lead you to
   * expect.</b> It fails at {@code applyVerdict}, with the {@code LlmTransportException} sailing
   * past {@code Archive.embed}'s narrow catch. It does <em>not</em> fail on {@code rowCount()}: the
   * transaction boundary is correct now, so the row is committed before the model is called and
   * survives an escaping exception either way. Losing the write took both faults at once — a
   * boundary that wrapped the model call, and a failure in the wrong currency — and only the second
   * is in this class's gift. So what the assertions below pin is the other half of the original
   * report: the write path stays quiet, and the caller is answered normally rather than told its
   * proposal was the problem. Restoring the boundary bug would make {@code rowCount()} the line
   * that fails, which is why it is still asserted here.
   */
  @Test
  void a_base_url_with_no_scheme_keeps_the_memory_and_answers_the_caller_normally() {
    PoolProperties pool = new PoolProperties();
    pool.setName("studio");
    // The incident, verbatim: no http://.
    pool.setBaseUrl("localhost:1234/v1");
    pool.setModels(List.of("nomic-embed-text"));

    LlmProperties llm = new LlmProperties();
    llm.setEmbeddingModel("nomic-embed-text");
    // The shipped input ceiling. Not decoration: DispatchingEmbeddingClient
    // refuses a missing one on the first call, so without this line the
    // assertion below would pass on the wrong refusal — a misconfigured
    // ceiling rather than the unparseable base-url this test is about.
    llm.setEmbeddingMaxInputTokens(1536);

    try (LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                new LlmPool(
                    pool.getName(),
                    pool.getModels(),
                    pool.getClasses(),
                    pool.getChat(),
                    pool.getEmbedding(),
                    pool.getSubmitTimeout(),
                    new OpenAiTransport(pool, new ObjectMapper()))),
            new NoOpTokenLedger())) {

      DispatchingEmbeddingClient client =
          new DispatchingEmbeddingClient(
              dispatcher, llm, new RatioTokenizer(RatioTokenizer.DEFAULT_CHARACTERS_PER_TOKEN));

      // The fixture is still unparseable, and this is what says so —
      // otherwise every expectation below is satisfied by an embedding
      // that failed for any reason at all. It fails before anything
      // leaves the process, so no socket opens.
      EmbeddingException refused =
          assertThrows(EmbeddingException.class, () -> client.embed("anything"));
      assertTrue(refused.getMessage().contains("base-url"), refused.getMessage());

      // The real store and the real UnitOfWork: this class asserts against
      // the actual transaction manager, and a write that survived only
      // because nothing was transactional would prove nothing.
      Archive misconfigured =
          new Archive(
              store,
              reasons,
              client,
              unitOfWork,
              archiveProps.getMaxBodyChars(),
              archiveProps.getIndexThreshold(),
              archiveProps.getHalfLifeDays());

      WriteResult written =
          misconfigured.applyVerdict(proposal("The retry budget is 4"), isNew(), PAYMENTS);

      // The caller is answered normally. Nothing about a misconfigured
      // side service belongs in the answer to a write that succeeded.
      assertNotNull(written.memoryId());
      assertEquals(1, rowCount(), "the write was accepted and must still be there");

      Memory kept = misconfigured.get(written.memoryId());
      assertEquals(MemoryState.ACTIVE, kept.state());
      assertEquals("The retry budget is 4", kept.summary());
      assertEquals("Four attempts since the timeout change.", kept.body());
      // Unembedded rather than absent: the documented cost of an endpoint
      // that was never reachable, and the whole of what was lost.
      assertNull(embeddingOf(written.memoryId()));
    }
  }

  // --- 2. atomicity is real ------------------------------------------------

  /**
   * A supersession is two saves, and they must not half-apply.
   *
   * <p>{@link HalfFailingStore} fails the second one — the save that marks the target retired —
   * which is what a dropped connection between the two looks like from inside {@code
   * Archive.supersede}. Without a transaction the first save stands: a new record live, the old one
   * still {@code active}, and two memories claiming the same fact with nothing to choose between
   * them. That is the "supersession quietly filed as a new memory" failure {@code
   * ArchiveException}'s javadoc warns about, reached by a crash instead of by a bad verdict, and
   * the archive looks fine until the stale memory answers a question.
   *
   * <p>This is the test the five {@code @Transactional} annotations never had. Point {@code
   * ArchiveConfig.unitOfWork} at {@link UnitOfWork#NONE} — the exact equivalent of deleting them —
   * and it fails here.
   *
   * <p><b>Through {@code Archive} and not through the controller</b>, unlike the rest of this
   * class. The controller no longer takes a verdict: the scribe makes one, and this context has no
   * agent registry, so every write through it is {@code NEW} — which never reaches {@code
   * supersede} and so never reaches the pair of saves under test. The boundary is drawn inside
   * {@code Archive} in any case, so this is the layer the claim is about.
   */
  @Test
  void a_supersession_that_fails_halfway_leaves_no_new_record() {
    WriteResult target = write(newMemoryRequest("payments", "The retry budget is 3"));
    halfFailing.failOnRetiring(true);

    assertThrows(
        IllegalStateException.class,
        () ->
            archive.applyVerdict(
                proposal("The retry budget is 4"),
                new Verdict(VerdictKind.SUPERSEDES, target.memoryId(), "the timeout changed"),
                PAYMENTS));

    assertEquals(1, rowCount(), "the new record must not survive a supersession that failed");
    Memory untouched = archive.get(target.memoryId());
    assertEquals(MemoryState.ACTIVE, untouched.state());
    assertNull(untouched.supersededBy());
    // And the surviving row really is the old one, not the new one under a
    // rolled-back label.
    assertEquals("The retry budget is 3", untouched.summary());
  }

  /**
   * A write that rolled back leaves no account of itself either.
   *
   * <p>{@link ReasonLog} is written <em>inside</em> the archive's unit of work, so the row
   * explaining a write and the write itself commit together or not at all. The alternative —
   * writing it after the commit, the way the embedding is written — would leave the log describing
   * memories that do not exist, which is worse than not having the log: a reader would have no way
   * to tell a rolled-back write from one whose row was later retired.
   *
   * <p><b>Its own archive with a threshold of one</b>, because the failure has to land
   * <em>after</em> the account is recorded and the demotion pass is the only thing that runs there.
   * The context's own threshold is production's, so reaching a demotion through the wired bean
   * would mean writing that many memories. Everything else is real: the real store, the real {@link
   * UnitOfWork}, the real transaction manager.
   */
  @Test
  void a_write_that_rolled_back_leaves_no_account_of_itself() {
    Archive tiny =
        new Archive(
            halfFailing,
            reasons,
            embeddings,
            unitOfWork,
            archiveProps.getMaxBodyChars(),
            1,
            archiveProps.getHalfLifeDays());

    WriteResult kept =
        tiny.applyVerdict(
            proposal("The retry budget is 3"),
            new Verdict(VerdictKind.NEW, null, "the write that survives"),
            PAYMENTS);
    assertEquals(1, reasonRowCount(), "fixture: the first write left its account");

    halfFailing.failOnDemoting(true);
    assertThrows(
        IllegalStateException.class,
        () ->
            tiny.applyVerdict(
                proposal("The retry budget is 4"),
                new Verdict(VerdictKind.NEW, null, "the write that does not"),
                PAYMENTS));

    assertEquals(1, rowCount(), "the second write must not survive its own demotion pass");
    assertEquals(
        1, reasonRowCount(), "and it must not have left an account of a write that did not happen");
    assertEquals(
        List.of("the write that survives"),
        reasons.forMemory(kept.memoryId()).stream().map(ReasonLog.Entry::reason).toList());
  }

  /**
   * A vector computed from other text takes the whole write back.
   *
   * <p>{@code Archive.applyVerdict} checks an offered vector against the text the memory is
   * actually embedded for, and refuses rather than storing one that does not belong to it — a wrong
   * vector on a right memory is a row that looks perfect and that no search will ever return. The
   * check is inside the unit of work on purpose: {@code Archive.embed} may not throw, because
   * {@code PromotionQueue} compensates on anything escaping {@code promote} after its commit, so a
   * check that could only refuse after the row was on disk would refuse nothing. Here it rolls the
   * write back.
   *
   * <p>{@code ArchiveTest} asserts the refusal itself; this asserts what it costs, for the reason
   * {@link #a_supersession_that_fails_halfway_leaves_no_new_record} gives — that archive is wired
   * with {@code UnitOfWork.NONE}, and a rollback assertion there would measure a rollback nothing
   * performs.
   */
  @Test
  void a_vector_computed_from_other_text_takes_the_whole_write_back() {
    float[] vector = new float[768];
    vector[7] = 1.0f;

    assertThrows(
        IllegalArgumentException.class,
        () ->
            archive.applyVerdict(
                proposal("The retry budget is 4"),
                new Verdict(VerdictKind.NEW, null, "novel"),
                PAYMENTS,
                new Archive.Precomputed(vector, "text that is not the memory's")));

    assertEquals(0, rowCount(), "the memory must not survive a vector that is not its own");
    assertEquals(0, reasonRowCount(), "and neither must the account of it");
  }

  private int reasonRowCount() {
    Integer rows = jdbc.queryForObject("SELECT count(*) FROM memory_reasons", Integer.class);
    return rows == null ? 0 : rows;
  }

  // --- 3. no connection is pinned across the model call --------------------

  /**
   * A stalled embedding call must not hold a database connection.
   *
   * <p>The write path's model call costs connect 10s plus read 30s per attempt, twice, plus
   * backoff: some eighty seconds worst case. Held inside the transaction, that is eighty seconds of
   * a Hikari connection spent waiting on LM Studio, and enough concurrent writes stop the server
   * for everything — reads included, which is the part that makes it look like the archive is down
   * rather than slow.
   *
   * <p>Both writers are parked inside the model call for the whole of the assertion below, and the
   * reader asks for an index — one plain SELECT, nothing to do with embeddings. With the pool at
   * {@link #POOL_SIZE} and the connections still pinned it waits out Hikari's 30-second connection
   * timeout; with them returned at commit it answers at once.
   */
  @Test
  void an_embedding_that_stalls_holds_no_database_connection() throws Exception {
    CountDownLatch arrived = new CountDownLatch(POOL_SIZE);
    CountDownLatch release = new CountDownLatch(1);
    embeddings.stallOn(arrived, release);

    ExecutorService threads = Executors.newFixedThreadPool(POOL_SIZE + 1);
    try {
      List<Future<?>> writes = new ArrayList<>();
      for (int i = 0; i < POOL_SIZE; i++) {
        String summary = "concurrent write " + i;
        writes.add(threads.submit(() -> controller.write(newMemoryRequest("payments", summary))));
      }
      assertTrue(arrived.await(30, SECONDS), "both writes should have reached the embedding call");

      Future<List<TocEntry>> reader = threads.submit(() -> controller.index(null).getBody());
      List<TocEntry> globalIndex;
      try {
        globalIndex = reader.get(5, SECONDS);
      } catch (TimeoutException stillWaiting) {
        throw new AssertionError(
            "a read queued behind a stalled embedding call: the write path is still"
                + " holding a pooled connection across the model call",
            stillWaiting);
      }
      assertTrue(globalIndex.isEmpty(), "nothing was written to the global tier");

      release.countDown();
      for (Future<?> write : writes) {
        write.get(30, SECONDS);
      }
      // And the stall cost nothing: both memories are there, embedded.
      assertEquals(POOL_SIZE, rowCount());
      assertNotNull(embeddingOf(archive.index(PAYMENTS).get(0).id()));
    } finally {
      release.countDown();
      threads.shutdownNow();
    }
  }

  // --- fixtures -------------------------------------------------------------

  private WriteResult write(WriteMemoryRequest request) {
    return controller.write(request).getBody();
  }

  private WriteMemoryRequest newMemoryRequest(String project, String summary) {
    // Explicit null for the verdict tripwire: this server does not take
    // one, and WriteMemoryRequest carries the component only so that a
    // stale client sending it is refused rather than ignored.
    return new WriteMemoryRequest(project, proposal(summary), null);
  }

  /**
   * Used by the one test that calls {@code Archive.applyVerdict} with a NEW verdict; the
   * supersession test below builds its own, and the controller no longer takes one at all.
   */
  private static Verdict isNew() {
    return new Verdict(VerdictKind.NEW, null, "novel");
  }

  private static MemoryProposal proposal(String summary) {
    return new MemoryProposal(
        summary,
        "Calling the payments API, or tuning retries",
        "Four attempts since the timeout change.",
        "claude-code",
        "proj/payments");
  }

  private int rowCount() {
    Integer rows = jdbc.queryForObject("SELECT count(*) FROM memories", Integer.class);
    return rows == null ? 0 : rows;
  }

  private String onlyId() {
    return jdbc.queryForObject("SELECT id FROM memories", String.class);
  }

  /**
   * The stored vector as pgvector's text form, or {@code null} for a memory written while the
   * endpoint was not working.
   */
  private String embeddingOf(String id) {
    return jdbc.queryForObject("SELECT embedding FROM memories WHERE id = ?", String.class, id);
  }

  @TestConfiguration
  static class Stubs {

    /**
     * Kept, now that the dispatcher has taken the place the real bean used to be missing from, and
     * for reasons this class cannot do without: no real client can be asked to fail every call or
     * to park one on demand, which is what three of the four tests here need.
     *
     * <p>{@code @Primary} is also what resolves the archive's {@code EmbeddingClient} injection
     * point, which has two candidates now that {@code LlmConfig} contributes a {@code
     * DispatchingEmbeddingClient}. Measured: dropping it fails all four tests at context startup
     * with {@code NoUniqueBeanDefinitionException}. And unlike {@code EndToEndTest}, this context
     * does not point its pool at a closed port, so this stub is also the only thing standing
     * between the suite and whatever LM Studio happens to be up on the machine running the build.
     */
    @Bean
    @Primary
    StubEmbeddings stubEmbeddings() {
      return new StubEmbeddings();
    }

    @Bean
    @Primary
    HalfFailingStore halfFailingStore(JdbcTemplate jdbc) {
      return new HalfFailingStore(jdbc);
    }
  }

  /**
   * An embedding client with two knobs the real one cannot be asked for on demand: fail every call,
   * and park every call until released.
   *
   * <p>No test in this project may reach a live model — Excalibur's golden-set eval scored anywhere
   * from 2/4 to 4/4 on identical code, so a suite built on one cannot tell anyone whether a change
   * helped. Nothing here asserts on what a vector means; the vector only has to be the right width.
   */
  static class StubEmbeddings implements EmbeddingClient {

    /**
     * Matches the schema's {@code vector(768)}. Postgres rejects any other width, with an error
     * naming the column rather than the test.
     */
    private static final int DIM = 768;

    private volatile Supplier<RuntimeException> failure;
    private volatile CountDownLatch arrived;
    private volatile CountDownLatch release;

    void reset() {
      failure = null;
      arrived = null;
      release = null;
    }

    /** Every call throws this, freshly built so each carries its own stack. */
    void failWith(Supplier<RuntimeException> thrown) {
      this.failure = thrown;
    }

    /**
     * Every call counts down {@code arrived}, then parks until {@code release} — a model that has
     * accepted the request and gone quiet.
     */
    void stallOn(CountDownLatch arrived, CountDownLatch release) {
      this.arrived = arrived;
      this.release = release;
    }

    @Override
    public float[] embed(String text) {
      CountDownLatch waitingFor = release;
      if (waitingFor != null) {
        arrived.countDown();
        try {
          // Bounded, so a regression stalls one test rather than the
          // whole build.
          if (!waitingFor.await(60, SECONDS)) {
            throw new IllegalStateException("stub: never released");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("stub: interrupted while stalled", interrupted);
        }
      }
      Supplier<RuntimeException> thrown = failure;
      if (thrown != null) {
        throw thrown.get();
      }
      float[] vector = new float[DIM];
      vector[0] = 1.0f;
      return vector;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
      List<float[]> vectors = new ArrayList<>(texts.size());
      for (String text : texts) {
        vectors.add(embed(text));
      }
      return List.copyOf(vectors);
    }
  }

  /**
   * A store whose second save fails, on demand.
   *
   * <p>{@code Archive.supersede} saves the new record and then saves the target as retired.
   * Refusing the retiring save is what a connection dropped between the two looks like from inside
   * that method — the one failure the transaction exists for, and one that cannot be provoked by
   * asking the real store nicely.
   */
  static class HalfFailingStore extends MemoryStore {

    private volatile boolean failOnRetiring;
    private volatile boolean failOnDemoting;

    HalfFailingStore(JdbcTemplate jdbc) {
      super(jdbc);
    }

    void failOnRetiring(boolean fail) {
      this.failOnRetiring = fail;
    }

    /**
     * Refuse the save that files a record {@code cold}.
     *
     * <p>A second failure point, and it is where it is because of what runs between it and the
     * commit. {@code Archive.applyVerdict} records the write's account in {@code memory_reasons}
     * and <em>then</em> runs the demotion pass, both inside one unit of work; nothing else can fail
     * after the account is written, so this is the only way to reach a rolled-back write that had
     * already left one.
     */
    void failOnDemoting(boolean fail) {
      this.failOnDemoting = fail;
    }

    @Override
    public void save(Memory memory) {
      if (failOnRetiring && memory.state() == MemoryState.SUPERSEDED) {
        throw new IllegalStateException(
            "stub: the connection dropped before the target could be retired");
      }
      if (failOnDemoting && memory.state() == MemoryState.COLD) {
        throw new IllegalStateException(
            "stub: the connection dropped before the demotion could be filed");
      }
      super.save(memory);
    }
  }
}
