package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.Provenance;
import io.aeyer.plowshare.protocol.VerdictKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A database that cannot be reached says so, and a database that answered "no" does not.
 *
 * <p>This is the gap {@code JobRuntime.dependencyFailure} carried a paragraph about from Task 6 to
 * Task 10. A dead Postgres surfaced as an ordinary {@code DataAccessException}, which lands on that
 * method's recoverable side, so a tool reaching a dead database was rendered to the model as "the
 * tool failed; you may try something else" — and an agent told that about {@code memory_recall}
 * reasonably rephrases the question, forever, against an archive that was never asked. That is the
 * confident-empty-answer failure one layer down from where this project usually meets it.
 *
 * <p><b>Why a wider catch is not the fix, measured rather than assumed.</b> Probed against
 * spring-jdbc/spring-tx 6.1.14 on 2026-08-29, printing each class's superclass chain:
 *
 * <pre>
 * CannotGetJdbcConnectionException -&gt; DataAccessResourceFailureException
 *     -&gt; NonTransientDataAccessResourceException
 *     -&gt; NonTransientDataAccessException -&gt; DataAccessException
 * DuplicateKeyException -&gt; DataIntegrityViolationException
 *     -&gt; NonTransientDataAccessException -&gt; DataAccessException
 * BadSqlGrammarException -&gt; InvalidDataAccessResourceUsageException
 *     -&gt; NonTransientDataAccessException -&gt; DataAccessException
 * </pre>
 *
 * <p>So the unreachable case and the refused case are <em>siblings</em> and the split has to happen
 * one level lower. These tests pin both sides of it.
 *
 * <p><b>The worked example that used to sit here was wrong, and correcting it is worth more than
 * the example was.</b> It said a clause wide enough to catch {@code DataAccessException} would turn
 * {@code ProposalStore}'s "a proposal is already waiting" into an {@code UNAVAILABLE} run. It would
 * not: {@code propose} inserts with {@code ON CONFLICT ... DO NOTHING}, so a duplicate pending
 * proposal raises nothing at all — it is a returned count of zero.
 *
 * <p>The rule is unchanged and the real example is one line away in the same method: the <b>foreign
 * key</b> on {@code memory_id}. {@code translating} sits inside {@code propose}'s {@code try}, so a
 * wider rule would fire before that method's own {@code catch} and turn "no memory with id X" — a
 * caller's mistake, correctable on its next turn — into a run that ended {@code UNAVAILABLE}.
 * {@code propose}'s inline comment had it right all along.
 */
class ArchiveUnavailableTest {

  private static final Provenance FORMED = new Provenance(Instant.EPOCH, "probe", "test");

  private static final String PASSWORD = "hunter2-not-in-any-message";

  /** Who the fixture's proposals are filed by; nothing here reads it back. */
  private static final String ASKED_BY = "curator";

  private static JdbcTemplate deadJdbc;

  /**
   * A datasource pointed at a port nothing listens on.
   *
   * <p>Loopback and a closed port, so nothing is reached and nothing has to be started. This is the
   * instrument that measures the <em>real</em> translation rather than the classification rule: the
   * tests further down hand the rule exceptions built by hand, which would pass just as well if the
   * driver had stopped raising the class they name.
   */
  @BeforeAll
  static void unreachableDatabase() {
    int closed;
    try (ServerSocket socket = new ServerSocket(0)) {
      closed = socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException("could not find a port to leave closed", e);
    }
    deadJdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:postgresql://localhost:" + closed + "/plowshare", "plowshare", PASSWORD));
  }

  // --- the real thing, against a database that is not there --------------------

  @Test
  void a_read_against_an_unreachable_database_says_so_rather_than_answering_nothing() {
    MemoryStore store = new MemoryStore(deadJdbc);

    assertThrows(ArchiveUnavailableException.class, () -> store.index(Home.of("payments")));
    assertThrows(ArchiveUnavailableException.class, () -> store.loadAll(Home.of("payments")));
    assertThrows(ArchiveUnavailableException.class, () -> store.load("mem_000001"));
    assertThrows(ArchiveUnavailableException.class, () -> store.loadRetired(Home.global()));
    assertThrows(ArchiveUnavailableException.class, () -> store.countUnsearchable(Home.global()));
    assertThrows(ArchiveUnavailableException.class, () -> store.unsearchable(Home.global()));
    assertThrows(
        ArchiveUnavailableException.class,
        () -> store.searchByVector(new float[] {1.0f}, Home.global(), 5));
  }

  @Test
  void a_write_against_an_unreachable_database_says_so() {
    MemoryStore store = new MemoryStore(deadJdbc);
    Memory memory = Memory.formed("mem_000001", "s", "sc", "b", FORMED, Home.global());

    assertThrows(ArchiveUnavailableException.class, () -> store.save(memory));
    assertThrows(
        ArchiveUnavailableException.class,
        () -> store.saveEmbedding("mem_000001", new float[] {1.0f}));
  }

  @Test
  void the_proposal_queue_against_an_unreachable_database_says_so() {
    ProposalStore store = new ProposalStore(deadJdbc);

    assertThrows(ArchiveUnavailableException.class, () -> store.get("prp_000001"));
    assertThrows(ArchiveUnavailableException.class, () -> store.pending(Home.of("payments")));
    assertThrows(ArchiveUnavailableException.class, () -> store.ruledOn(Home.of("payments")));
    assertThrows(
        ArchiveUnavailableException.class,
        () -> store.propose("mem_000001", ProposalStore.PROMOTE, "because", ASKED_BY));
    assertThrows(
        ArchiveUnavailableException.class,
        () -> store.resolve("prp_000001", true, null, "somebody"));
  }

  /**
   * Every {@code ProjectStore} call site, for the reason the two above exist.
   *
   * <p>A workspace lookup that came back empty because Postgres was gone is the same
   * confident-empty-answer failure one layer over: a job would be told its project has no workspace
   * and would go on with no file access, rather than stopping. {@code define} is given a directory
   * that certainly exists — the process's own — because its validation runs <em>before</em> the
   * write and a refused path would never reach the database at all.
   */
  @Test
  void project_workspaces_against_an_unreachable_database_say_so() {
    ProjectStore store =
        new ProjectStore(
            deadJdbc,
            Path.of("plowshare.yml"),
            Path.of("sampling"),
            Path.of("console-token"),
            Path.of("exports"),
            Path.of("data"));

    assertThrows(ArchiveUnavailableException.class, () -> store.find("payments"));
    assertThrows(ArchiveUnavailableException.class, () -> store.all());
    assertThrows(ArchiveUnavailableException.class, () -> store.forget("payments"));
    assertThrows(
        ArchiveUnavailableException.class, () -> store.define("payments", Path.of("."), List.of()));
    assertThrows(ArchiveUnavailableException.class, () -> store.effectiveExclusions("payments"));
  }

  /**
   * Both {@code ReasonLog} call sites, for the reason every other enumeration here exists.
   *
   * <p>The read is the sharper of the two: {@code forMemory} answers with an empty list for a
   * memory nothing was recorded about, deliberately, so a database that could not be reached would
   * otherwise come back as "nothing was ever recorded about this write" — a confident empty answer
   * to the one question this table exists to be able to answer a year later. The write matters less
   * on its own, because it runs inside the archive's unit of work and the memory would fail with
   * it; naming it here is what stops a later edit moving it out and losing the translation with it.
   */
  @Test
  void the_reason_log_against_an_unreachable_database_says_so() {
    ReasonLog reasons = new JdbcReasonLog(deadJdbc);

    assertThrows(ArchiveUnavailableException.class, () -> reasons.forMemory("mem_000001"));
    assertThrows(
        ArchiveUnavailableException.class,
        () ->
            reasons.record(
                new ReasonLog.Entry(
                    "mem_000001", Instant.EPOCH, VerdictKind.NEW, null, "because")));
  }

  /**
   * The one thing a reader of this exception has to be able to do is tell an operator which box to
   * look at. Asserted positively rather than as "the message is not empty": a message that says
   * only "unavailable" would pass that and be useless.
   */
  @Test
  void the_message_names_the_operation_and_the_reason_the_connection_failed() {
    ArchiveUnavailableException down =
        assertThrows(
            ArchiveUnavailableException.class,
            () -> new MemoryStore(deadJdbc).index(Home.of("payments")));

    String said = down.getMessage().toLowerCase(Locale.ROOT);
    assertTrue(said.contains("index"), down.getMessage());
    assertTrue(said.contains("could not be reached"), down.getMessage());
    assertTrue(said.contains("connection"), down.getMessage());
  }

  /**
   * The message travels into {@code Outcome.detail}, an HTTP body and a log line, so it must not
   * carry the credential the datasource was built with. The slice's one rule with no exceptions is
   * about the model's API key; a database password is the same category and gets the same
   * instrument rather than an argument that it happens not to leak.
   */
  @Test
  void no_database_password_reaches_the_message_or_any_cause_in_the_chain() {
    ArchiveUnavailableException down =
        assertThrows(
            ArchiveUnavailableException.class,
            () -> new MemoryStore(deadJdbc).index(Home.of("payments")));

    for (Throwable link = down; link != null; link = link.getCause()) {
      assertFalse(
          String.valueOf(link.getMessage()).contains(PASSWORD),
          link.getClass().getName() + " carried the password: " + link.getMessage());
    }
  }

  @Test
  void the_driver_failure_is_kept_as_the_cause_so_an_operator_can_see_it() {
    ArchiveUnavailableException down =
        assertThrows(
            ArchiveUnavailableException.class,
            () -> new MemoryStore(deadJdbc).index(Home.of("payments")));

    assertNotNull(down.getCause(), "the translated failure is the only record of what broke");
    assertInstanceOf(
        CannotGetJdbcConnectionException.class,
        down.getCause(),
        "the measurement this class is built on says an unreachable Postgres arrives"
            + " as CannotGetJdbcConnectionException");
  }

  /**
   * It is not an {@link ArchiveException}, and that is load-bearing rather than tidy: {@code
   * ApiExceptionHandler} answers {@code ArchiveException} with 404, whose whole meaning is "the
   * archive does not hold that". A database nobody could reach holds nothing and denies nothing,
   * and 404 is the answer a caller would cache.
   */
  @Test
  void an_unavailability_is_not_a_missing_record() {
    ArchiveUnavailableException down =
        assertThrows(
            ArchiveUnavailableException.class, () -> new MemoryStore(deadJdbc).load("mem_000001"));

    // Reflectively, not `instanceof`: with the types unrelated as they are
    // now, `down instanceof ArchiveException` does not compile at all, and a
    // check that cannot be written is a check nobody can run after somebody
    // changes the hierarchy. isInstance compiles either way and fails at
    // run time, which is the assertion this test is for.
    assertFalse(
        ArchiveException.class.isInstance(down),
        "an unreachable database must not answer as a 404");
    assertFalse(
        ValidationException.class.isInstance(down),
        "an unreachable database is not a malformed proposal either");
  }

  // --- the transaction manager, which fails first ------------------------------

  /**
   * The measurement the whole two-place translation rests on.
   *
   * <p>Asserted on the raw {@code TransactionTemplate} rather than through {@code UnitOfWork},
   * because it is the <em>premise</em> and not the behaviour: if this ever stopped being true, the
   * wrapper in {@code ArchiveConfig} would be dead code and the test below would be passing on some
   * other exception's back.
   */
  @Test
  void an_unreachable_database_fails_at_the_transaction_and_not_in_a_store() {
    TransactionTemplate template =
        new TransactionTemplate(new DataSourceTransactionManager(deadJdbc.getDataSource()));

    CannotCreateTransactionException raised =
        assertThrows(
            CannotCreateTransactionException.class,
            () -> template.execute(status -> "never reached"));

    assertFalse(
        DataAccessException.class.isInstance(raised),
        "the whole point of translating in ArchiveConfig as well is that this is a"
            + " different tree from DataAccessException");
  }

  /**
   * And so the unit of work says so, which is what every {@code Archive} read actually goes
   * through.
   */
  @Test
  void a_transaction_that_cannot_be_opened_is_an_unavailability() {
    UnitOfWork unitOfWork =
        new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(deadJdbc.getDataSource()));

    ArchiveUnavailableException down =
        assertThrows(
            ArchiveUnavailableException.class,
            () -> unitOfWork.inTransaction(() -> "never reached"));

    assertTrue(down.getMessage().contains("open a transaction"), down.getMessage());
    assertInstanceOf(CannotCreateTransactionException.class, down.getCause());
  }

  /**
   * Not every transaction failure is an outage, and this is the guard against widening the rule to
   * the whole tree. A rollback that was asked for is this process deciding something, not the
   * database failing.
   */
  @Test
  void a_transaction_failure_that_is_not_about_reaching_the_database_is_left_alone() {
    UnexpectedRollbackException rolledBack =
        new UnexpectedRollbackException("the work marked itself rollback-only");

    assertSame(
        rolledBack,
        assertThrows(
            UnexpectedRollbackException.class,
            () ->
                ArchiveUnavailableException.translating(
                    "save a memory",
                    () -> {
                      throw rolledBack;
                    })));
  }

  // --- the classification rule, both sides ------------------------------------

  @Test
  void a_connection_that_could_not_be_got_is_an_unavailability() {
    assertUnavailable(new CannotGetJdbcConnectionException("failed to obtain a connection"));
    assertUnavailable(new DataAccessResourceFailureException("the box is gone"));
  }

  /**
   * Transient and recoverable failures are on the unavailable side too, and the reason is what the
   * two sides <em>mean to a run</em> rather than what they mean to a DBA. A lock this transaction
   * could not take is not a mistake the model made and not one it can correct by rephrasing, so
   * handing it back as "the tool failed, try something else" invites exactly the retry loop the
   * wrong side produces.
   */
  @Test
  void a_timeout_a_lock_and_a_recoverable_failure_are_all_unavailabilities() {
    assertUnavailable(new QueryTimeoutException("the statement timed out"));
    assertUnavailable(new CannotAcquireLockException("somebody else holds the row"));
    assertUnavailable(new TransientDataAccessResourceException("try again"));
    assertUnavailable(new RecoverableDataAccessException("try again on a new connection"));
  }

  /**
   * The refusal this whole split exists to protect: an integrity violation is the database
   * <em>answering</em>, and it must reach the caller that knows what it means.
   *
   * <p><b>The worked example here was wrong for one commit and is corrected.</b> It said {@code
   * ProposalStore.propose} turns a duplicate key into "a proposal is already waiting". It does not:
   * {@code propose} inserts with {@code ON CONFLICT ... DO NOTHING}, so a duplicate is a returned
   * count of zero and no exception is raised at all.
   *
   * <p>The live case in that same {@code try} is the <b>foreign key</b> on {@code memory_id}.
   * {@code translating} sits inside it, so widening this rule to {@code
   * DataIntegrityViolationException} would fire the translation first, leave {@code propose}'s own
   * {@code catch} unreached, and turn "no memory with id X" — a caller's mistake — into a run that
   * ended {@code UNAVAILABLE}. Both are asserted, the foreign key because it is the one that fires
   * and the duplicate key because it is the subtype and a rule written against the parent must not
   * catch either.
   */
  @Test
  void an_integrity_violation_is_left_alone_because_the_database_answered() {
    assertPassesThrough(
        new DataIntegrityViolationException(
            "insert or update on table \"proposals\" violates foreign key constraint"));
    assertPassesThrough(new DuplicateKeyException("a minted proposal id already exists"));
  }

  /**
   * Our own bugs stay ours. A clause wide enough to swallow bad SQL would report a typo in a query
   * as an outage, and the operator would go and look at a database that is fine.
   */
  @Test
  void bad_sql_and_api_misuse_are_left_alone_because_they_are_this_code_being_wrong() {
    assertPassesThrough(
        new BadSqlGrammarException(
            "select", "SELECT nonesuch FROM memories", new java.sql.SQLException("42703")));
    assertPassesThrough(new InvalidDataAccessApiUsageException("no parameters supplied"));
  }

  /**
   * Anything that is not a {@code DataAccessException} at all is a bug in this process and must not
   * be dressed as the database's fault — the same argument {@code EmbeddingException} was created
   * on.
   */
  @Test
  void a_failure_that_is_not_the_database_at_all_is_not_translated() {
    IllegalStateException bug = new IllegalStateException("a bug in the store");
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                ArchiveUnavailableException.translating(
                    "read a memory",
                    () -> {
                      throw bug;
                    }));
    assertSame(bug, thrown);
  }

  @Test
  void a_call_that_works_is_returned_unchanged() {
    assertEquals(
        List.of("a"), ArchiveUnavailableException.translating("read a memory", () -> List.of("a")));
  }

  /**
   * The two halves of the message are joined only when there are two.
   *
   * <p>A hand-built Spring exception has no cause, and the first version of this renderer appended
   * an empty {@code " ()"} for it — the same dangling punctuation {@code Archive.promote} was
   * corrected for. Asserted rather than eyeballed, because nothing else in the suite reads this
   * string.
   */
  @Test
  void a_failure_with_no_driver_underneath_it_gets_no_empty_parenthesis() {
    ArchiveUnavailableException down =
        assertThrows(
            ArchiveUnavailableException.class,
            () ->
                ArchiveUnavailableException.translating(
                    "read a memory",
                    () -> {
                      throw new QueryTimeoutException("the statement timed out");
                    }));

    assertTrue(down.getMessage().endsWith("the statement timed out"), down.getMessage());
    assertFalse(down.getMessage().contains("()"), down.getMessage());
  }

  /**
   * Spring can raise one of these with a null message. Falling back to the class name keeps the
   * sentence from ending in a colon and nothing, which would read as a truncated log line rather
   * than as an outage.
   */
  @Test
  void a_failure_with_no_message_at_all_still_names_its_type() {
    ArchiveUnavailableException down =
        assertThrows(
            ArchiveUnavailableException.class,
            () ->
                ArchiveUnavailableException.translating(
                    "index a tier",
                    () -> {
                      throw new QueryTimeoutException(null);
                    }));

    assertTrue(down.getMessage().contains("QueryTimeoutException"), down.getMessage());
    assertTrue(down.getMessage().contains("index a tier"), down.getMessage());
  }

  private static void assertUnavailable(DataAccessException raised) {
    ArchiveUnavailableException down =
        assertThrows(
            ArchiveUnavailableException.class,
            () ->
                ArchiveUnavailableException.translating(
                    "read a memory",
                    () -> {
                      throw raised;
                    }));
    assertSame(raised, down.getCause());
    assertTrue(down.getMessage().contains("read a memory"), down.getMessage());
  }

  private static void assertPassesThrough(DataAccessException raised) {
    DataAccessException thrown =
        assertThrows(
            raised.getClass(),
            () ->
                ArchiveUnavailableException.translating(
                    "file a proposal",
                    () -> {
                      throw raised;
                    }));
    assertSame(raised, thrown, "an answered refusal must reach its own caller untouched");
  }
}
