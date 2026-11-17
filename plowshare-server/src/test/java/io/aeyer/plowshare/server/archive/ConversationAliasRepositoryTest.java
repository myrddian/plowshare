package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.api.ConversationsProperties;
import io.aeyer.plowshare.server.archive.ConversationAliasRepository.Key;
import io.aeyer.plowshare.server.archive.ConversationAliasRepository.Opening;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.requests.RequestedBudget;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** Rules use mocked JDBC/creation boundaries; PostgreSQL assertions live in the tagged test. */
class ConversationAliasRepositoryTest {
  private final Key key =
      new Key(7, new AccountId(UUID.randomUUID()), "ha.assist", "Caller/Session");
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ConversationCreation conversations = mock(ConversationCreation.class);
  private final Transactions transactions = new Transactions();
  private JdbcConversationAliasRepository repository;
  private final CountDownLatch aborted = new CountDownLatch(1);

  @BeforeEach
  void setup() throws Exception {
    repository =
        new JdbcConversationAliasRepository(
            jdbc,
            conversations,
            transactions,
            new ConversationAliasProperties(Duration.ofSeconds(5), Duration.ofSeconds(1)),
            new SimpleMeterRegistry());
    Connection connection = mock(Connection.class);
    doAnswer(
            call -> {
              aborted.countDown();
              return null;
            })
        .when(connection)
        .abort(any());
    doAnswer(
            call -> {
              ConnectionCallback<?> callback = call.getArgument(0);
              return callback.doInConnection(connection);
            })
        .when(jdbc)
        .execute(ArgumentMatchers.<ConnectionCallback<Void>>any());
    ResultSet row = mock(ResultSet.class);
    when(row.getString(1)).thenReturn("house");
    when(row.getString(2)).thenReturn("alice");
    doAnswer(
            call -> {
              RowMapper<?> mapper = call.getArgument(1);
              return List.of(mapper.mapRow(row, 0));
            })
        .when(jdbc)
        .query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), any(UUID.class));
    when(jdbc.queryForObject(anyString(), eq(String.class), anyString(), anyString()))
        .thenReturn("1s1s");
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyLong())).thenReturn(false);
    when(jdbc.update(
            anyString(), anyLong(), any(UUID.class), anyString(), anyString(), anyString()))
        .thenReturn(1);
  }

  @AfterEach
  void close() {
    repository.close();
  }

  @Test
  void existingRootReusesSnapshotAndNeverResetsLimitsOrCreatesAgain() {
    var existing = record(Home.of("house"), ConversationLifecycle.ACTIVE);
    mapped(existing);
    var result = repository.open(key, new Opening(Budget.of(999), TurnCap.none()));
    assertFalse(result.created());
    assertSame(existing, result.conversation());
    assertEquals(7, result.conversation().budget().spent());
    verify(conversations, never()).open(any(), any(), any(), any());
    verify(jdbc, never())
        .update(anyString(), anyLong(), any(UUID.class), anyString(), anyString(), anyString());
    assertEquals(1, transactions.commits);
  }

  @Test
  void firstOpenUsesResolvedProjectAndOwnerAndReturnsOnlyAfterCommit() {
    absent();
    var opening = new Opening(Budget.of(20), TurnCap.of(4));
    var created = record(Home.of("house"), ConversationLifecycle.ACTIVE);
    when(conversations.open(
            eq(Home.of("house")), any(Budget.class), any(TurnCap.class), eq("alice")))
        .thenReturn(created);
    var result = repository.open(key, opening);
    assertTrue(result.created());
    assertSame(created, result.conversation());
    assertEquals(1, transactions.commits);
    var budget = ArgumentCaptor.forClass(Budget.class);
    var cap = ArgumentCaptor.forClass(TurnCap.class);
    verify(conversations).open(eq(Home.of("house")), budget.capture(), cap.capture(), eq("alice"));
    assertEquals(20, budget.getValue().limit());
    assertEquals(4, cap.getValue().turns());
  }

  @Test
  void resolveMissDoesNotOpenOrInsertAnything() {
    absent();
    assertTrue(repository.find(key).isEmpty());
    verifyNoInteractions(conversations);
    verify(jdbc, never())
        .update(anyString(), anyLong(), any(UUID.class), anyString(), anyString(), anyString());
  }

  @ParameterizedTest
  @EnumSource(ConversationLifecycle.class)
  void openNeverRevivesOrReplacesAnExistingLifecycle(ConversationLifecycle lifecycle) {
    var retained = record(Home.of("house"), lifecycle);
    mapped(retained);
    assertSame(retained, repository.open(key, new Opening(Budget.of(20), null)).conversation());
    verify(conversations, never()).open(any(), any(), any(), any());
  }

  @Test
  void aMappedTargetInAnotherProjectRollsBackInsteadOfOpeningAReplacement() {
    mapped(record(Home.of("elsewhere"), ConversationLifecycle.ACTIVE));
    assertThrows(
        IllegalStateException.class, () -> repository.open(key, new Opening(Budget.of(20), null)));
    assertEquals(1, transactions.rollbacks);
    verify(conversations, never()).open(any(), any(), any(), any());
  }

  @Test
  void aDeletedMappedTargetIsNotTreatedAsAnUnmappedExternalId() {
    lookup("cnv_missing");
    when(conversations.find("cnv_missing")).thenReturn(Optional.empty());
    assertThrows(
        IllegalStateException.class, () -> repository.open(key, new Opening(Budget.of(20), null)));
    verify(conversations, never()).open(any(), any(), any(), any());
    assertEquals(1, transactions.rollbacks);
  }

  @Test
  void unavailableOrRestrictedNamespaceRefusesBeforeAliasLookup() {
    when(jdbc.query(
            anyString(), ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), any(UUID.class)))
        .thenReturn(List.of());
    assertThrows(CallerFault.class, () -> repository.find(key));
    verifyNoInteractions(conversations);
    verify(jdbc, never())
        .query(
            anyString(),
            ArgumentMatchers.<RowMapper<Object>>any(),
            anyLong(),
            any(UUID.class),
            anyString(),
            anyString());
  }

  @Test
  void theEarlierEnclosingDeadlineCannotBeResetByTheRepository() {
    assertThrows(
        ReadFailedException.class,
        () -> ReadDeadline.within(Duration.ofNanos(1), () -> repository.find(key)));
    assertEquals(0, transactions.commits);
    verifyNoInteractions(conversations);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ordinaryCreationAndHydrationShareTheBoundedMaterializationGroup(boolean hydrate)
      throws Exception {
    var retained = record(Home.of("house"), ConversationLifecycle.ACTIVE);
    if (hydrate) {
      lookup(retained.id());
      when(conversations.find(retained.id()))
          .thenAnswer(
              call -> {
                blockOwner();
                return Optional.of(retained);
              });
    } else {
      absent();
      when(conversations.open(any(), any(), any(), any()))
          .thenAnswer(
              call -> {
                blockOwner();
                return retained;
              });
    }
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      var pending = executor.submit(() -> repository.open(key, new Opening(Budget.of(20), null)));
      var failed = assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
      assertInstanceOf(ReadFailedException.class, failed.getCause());
      assertEquals(0, transactions.commits);
      assertEquals(1, transactions.rollbacks);
      verify(jdbc, never())
          .update(anyString(), anyLong(), any(UUID.class), anyString(), anyString(), anyString());
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  /** Simulates driver work released by cancellation of its own attached JDBC connection. */
  private void blockOwner() {
    try {
      assertTrue(aborted.await(2, TimeUnit.SECONDS), "Owned JDBC abort must release the read");
      assertFalse(
          Thread.currentThread().isInterrupted(), "SQL timeout must not interrupt its caller");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw ReadFailedException.interrupted(interrupted);
    }
  }

  @Test
  void aCancelledSqlUnitLeavesTheSameCallerAbleToFinishTheNextRead() throws Exception {
    var retained = record(Home.of("house"), ConversationLifecycle.ACTIVE);
    lookup(retained.id());
    when(conversations.find(retained.id()))
        .thenAnswer(
            call -> {
              blockOwner();
              return Optional.of(retained);
            });
    try (var caller = Executors.newSingleThreadExecutor()) {
      var result =
          caller.submit(
              () -> {
                var failure = assertThrows(ReadFailedException.class, () -> repository.find(key));
                assertEquals(ReadFailedException.Reason.DEADLINE_EXCEEDED, failure.reason());
                assertFalse(Thread.currentThread().isInterrupted());
                when(conversations.find(retained.id())).thenReturn(Optional.of(retained));
                var next = repository.find(key);
                assertFalse(Thread.currentThread().isInterrupted());
                return next;
              });
      assertEquals(Optional.of(retained), result.get(3, TimeUnit.SECONDS));
      assertEquals(1, transactions.rollbacks);
      assertEquals(1, transactions.commits);
    }
  }

  @Test
  void shutdownCancelsRegisteredWorkBeforeRemovingAlarmsAndRefusesNewWork() throws Exception {
    var retained = record(Home.of("house"), ConversationLifecycle.ACTIVE);
    lookup(retained.id());
    var entered = new CountDownLatch(1);
    when(conversations.find(retained.id()))
        .thenAnswer(
            call -> {
              entered.countDown();
              blockOwner();
              return Optional.of(retained);
            });
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      var pending = executor.submit(() -> repository.find(key));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      repository.close();
      var failed = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
      assertInstanceOf(ReadFailedException.class, failed.getCause());
      assertEquals(0, transactions.commits);
      assertEquals(1, transactions.rollbacks);
      assertThrows(ReadFailedException.class, () -> repository.find(key));
      verify(conversations).find(retained.id());
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void anExpiredOwnerKeepsTypedUncertaintyWhenItsRollbackAlsoFails() throws Exception {
    var retained = record(Home.of("house"), ConversationLifecycle.ACTIVE);
    lookup(retained.id());
    transactions.rollbackFailure = rollbackFailure();
    when(conversations.find(retained.id()))
        .thenAnswer(
            call -> {
              blockOwner();
              return Optional.of(retained);
            });
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      var pending = executor.submit(() -> repository.find(key));
      var failed = assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
      assertInstanceOf(ReadFailedException.class, failed.getCause());
      assertSame(transactions.rollbackFailure, failed.getCause().getCause());
      assertEquals(0, transactions.commits);
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"57014", "55P03"})
  void serverStatementOrLockTimeoutKeepsItsTypeWhenRollbackWinsTheWatchdogRace(String state) {
    var retained = record(Home.of("house"), ConversationLifecycle.ACTIVE);
    lookup(retained.id());
    transactions.rollbackFailure = rollbackFailure();
    when(conversations.find(retained.id()))
        .thenThrow(
            new DataAccessResourceFailureException(
                "fixture cancellation", new SQLException("fixture", state)));
    var failed = assertThrows(ReadFailedException.class, () -> repository.find(key));
    assertSame(transactions.rollbackFailure, failed.getCause());
    assertEquals(0, transactions.commits);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void anOwnedDriverDeadlineKeepsItsReadFailureWhenRollbackRacesTheWatchdog(boolean rollback) {
    var retained = record(Home.of("house"), ConversationLifecycle.ACTIVE);
    lookup(retained.id());
    var original =
        new DataAccessResourceFailureException(
            "fixture timed out",
            new SQLException(
                "fixture socket timeout", "08006", new SocketTimeoutException("fixture")));
    if (rollback) transactions.rollbackFailure = rollbackFailure();
    when(conversations.find(retained.id())).thenThrow(original);
    var observed = assertThrows(ReadFailedException.class, () -> repository.find(key));
    if (rollback) {
      assertSame(transactions.rollbackFailure, observed.getCause());
      assertSame(
          original,
          assertInstanceOf(TransactionSystemException.class, observed.getCause())
              .getApplicationException());
    } else {
      Throwable retainedCause = observed;
      for (int depth = 0; retainedCause != null && retainedCause != original && depth < 8; depth++)
        retainedCause = retainedCause.getCause();
      assertSame(original, retainedCause);
    }
    assertTrue(observed.getMessage().contains("commit may be uncertain"));
    assertFalse(Thread.currentThread().isInterrupted());
    assertEquals(0, transactions.commits);
  }

  @Test
  void aConnectionOutageWithoutOwnedTimeoutEvidenceKeepsItsArchiveFailure() {
    var retained = record(Home.of("house"), ConversationLifecycle.ACTIVE);
    lookup(retained.id());
    var original =
        new DataAccessResourceFailureException(
            "fixture disconnected", new SQLException("fixture lost connection", "08006"));
    when(conversations.find(retained.id())).thenThrow(original);
    var observed = assertThrows(ArchiveUnavailableException.class, () -> repository.find(key));
    assertSame(original, observed.getCause());
    assertEquals(0, transactions.commits);
  }

  @ParameterizedTest
  @CsvSource({"42601,false", "42601,true", "23505,false", "23505,true"})
  void aDefiniteSqlDefectIsPreservedWhenItsResponseRacesTheDeadline(
      String state, boolean rollback) {
    var retained = record(Home.of("house"), ConversationLifecycle.ACTIVE);
    lookup(retained.id());
    SQLException sql = new SQLException("fixture SQL defect", state);
    DataAccessException original =
        state.equals("42601")
            ? new BadSqlGrammarException("fixture", "fixture query", sql)
            : new DataIntegrityViolationException("fixture constraint", sql);
    if (rollback) transactions.rollbackFailure = rollbackFailure();
    when(conversations.find(retained.id()))
        .thenAnswer(
            call -> {
              blockOwner();
              throw original;
            });
    var observed = assertThrows(RuntimeException.class, () -> repository.find(key));
    if (rollback) {
      assertSame(transactions.rollbackFailure, observed);
      assertSame(
          original,
          assertInstanceOf(TransactionSystemException.class, observed).getApplicationException());
    } else assertSame(original, observed);
    assertFalse(Thread.currentThread().isInterrupted());
    assertEquals(0, transactions.commits);
  }

  @Test
  void anUnrelatedBusinessAndRollbackFailureIsNotReclassifiedAsDeadlineExpiry() {
    var retained = record(Home.of("house"), ConversationLifecycle.ACTIVE);
    lookup(retained.id());
    transactions.rollbackFailure = rollbackFailure();
    when(conversations.find(retained.id()))
        .thenThrow(new IllegalStateException("fixture invariant"));
    var failed = assertThrows(TransactionSystemException.class, () -> repository.find(key));
    assertSame(transactions.rollbackFailure, failed);
  }

  private static TransactionSystemException rollbackFailure() {
    return new TransactionSystemException(
        "fixture rollback failed", new SQLException("fixture closed connection", "08003"));
  }

  @Test
  void identifiersAreOpaqueAndStrictAndBudgetsRemainFinite() {
    assertEquals("Caller/Session", key.externalConversation());
    for (String value : List.of("", "space id", "\u00e9", "a\n", "x".repeat(257)))
      assertThrows(IllegalArgumentException.class, () -> new Key(7, key.account(), "ha", value));
    for (String value : List.of("", "HA", "ha..assist", "x".repeat(129)))
      assertThrows(IllegalArgumentException.class, () -> new Key(7, key.account(), value, "id"));
    assertThrows(IllegalArgumentException.class, () -> new Key(0, key.account(), "ha", "id"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConversationAliasProperties(Duration.ZERO, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConversationAliasProperties(Duration.ofSeconds(2), Duration.ofMillis(2001)));
  }

  @Test
  void configuredZeroCreatesFreshDisabledAllowancesWhileExplicitWireZeroStillRefuses() {
    var policy = new ConversationsProperties();
    policy.setDefaultBudget("0");
    var opening = new Opening(RequestedBudget.in(null, null, policy.defaultBudgetLimit()), null);
    Budget first = opening.budget();
    Budget second = opening.budget();
    assertFalse(opening.noBudget());
    assertTrue(first.capped());
    assertEquals(0, first.limit());
    assertTrue(first.exhausted());
    assertFalse(first.trySpend());
    assertEquals(0, first.spent());
    assertEquals(0, second.limit());
    assertNotEquals(first.identity(), second.identity());
    assertThrows(CallerFault.class, () -> RequestedBudget.in(0, null, policy.defaultBudgetLimit()));
    assertThrows(IllegalArgumentException.class, () -> new Opening(-1, null, false, false));
  }

  private void absent() {
    when(jdbc.query(
            anyString(),
            ArgumentMatchers.<RowMapper<Object>>any(),
            anyLong(),
            any(UUID.class),
            anyString(),
            anyString()))
        .thenReturn(List.of());
  }

  private void lookup(String id) {
    when(jdbc.query(
            anyString(),
            ArgumentMatchers.<RowMapper<String>>any(),
            anyLong(),
            any(UUID.class),
            anyString(),
            anyString()))
        .thenReturn(List.of(id));
  }

  private void mapped(ConversationRecord record) {
    lookup(record.id());
    when(conversations.find(record.id())).thenReturn(Optional.of(record));
  }

  private static ConversationRecord record(Home home, ConversationLifecycle lifecycle) {
    return new ConversationRecord(
        "cnv_fixture",
        home,
        Origin.TURN,
        lifecycle,
        null,
        null,
        Instant.EPOCH,
        null,
        Budget.resumed(10, 7),
        TurnCap.of(3),
        null);
  }

  private static final class Transactions extends AbstractPlatformTransactionManager {
    int commits;
    int rollbacks;
    RuntimeException rollbackFailure;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
      if (rollbackFailure != null) throw rollbackFailure;
    }
  }
}
