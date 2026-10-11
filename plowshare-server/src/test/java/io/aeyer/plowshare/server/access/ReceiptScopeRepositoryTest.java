package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Actual UUID parameter binding, account isolation and null/global project mapping. */
@Tag("full-db")
@Testcontainers
class ReceiptScopeRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork work;
  private ResourceScopeRepository scopes;
  private OrchestrationStore runs;
  private ConversationStore conversations;

  @BeforeAll
  static void migrate() {
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(data).load().migrate();
    jdbc = new JdbcTemplate(data);
    var transactions = new TransactionTemplate(new DataSourceTransactionManager(data));
    work =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> action) {
            return transactions.execute(status -> action.get());
          }
        };
  }

  @BeforeEach
  void fresh() {
    jdbc.execute("TRUNCATE projects,admins CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES ('owner','fixture'),('other','fixture')");
    scopes = new JdbcResourceScopeRepository(jdbc);
    runs = new OrchestrationStore(jdbc, Instant::now, work);
    conversations = new ConversationStore(jdbc, Instant::now, null);
  }

  private String receipt(String account, UUID key, String project) {
    String conductor =
        conversations
            .log(
                Origin.ORCHESTRATION,
                project == null ? Home.global() : Home.of(project),
                "fixture",
                null,
                Budget.of(1))
            .id();
    return work.inTransaction(
        () ->
            runs.receiveStart(
                    account,
                    key,
                    "{}",
                    () ->
                        runs.insert(
                            new OrchestrationStore.NewOrchestration(
                                "fixture",
                                OrchestrationDefinition.Tier.PROJECT,
                                "sha256:fixture",
                                "fixture",
                                "project",
                                List.of(),
                                0,
                                project,
                                conductor,
                                null,
                                "fixture",
                                account,
                                "fixture-session",
                                null,
                                0)))
                .id());
  }

  @Test
  void sameRequestUuidResolvesOnlyTheAuthenticatedAccountsRetainedRun() {
    var key = UUID.randomUUID();
    var run = receipt("owner", key, "application");
    receipt("other", key, "other-application");
    assertEquals(List.of("application"), scopes.orchestrationReceiptProjects("owner", key));
    assertEquals(List.of("other-application"), scopes.orchestrationReceiptProjects("other", key));
    assertTrue(scopes.orchestrationReceiptProjects("missing", key).isEmpty());
    assertTrue(scopes.orchestrationReceiptProjects("owner", UUID.randomUUID()).isEmpty());
    assertTrue(scopes.orchestrationReceiptProjects("owner' OR true --", key).isEmpty());
    jdbc.update("UPDATE orchestrations SET project='moved-application' WHERE id=?", run);
    assertEquals(List.of("moved-application"), scopes.orchestrationReceiptProjects("owner", key));
  }

  @Test
  void globalWorkRemainsDistinctFromAnAbsentReceiptAndAccountlessReadsAreRefused() {
    var key = UUID.randomUUID();
    receipt("owner", key, null);
    assertEquals(
        Collections.singletonList(null), scopes.orchestrationReceiptProjects("owner", key));
    assertTrue(scopes.orchestrationReceiptProjects("other", key).isEmpty());
    assertThrows(RuntimeException.class, () -> scopes.orchestrationReceiptProjects(null, key));
    assertThrows(RuntimeException.class, () -> scopes.orchestrationReceiptProjects("owner", null));
    assertThrows(
        RuntimeException.class,
        () ->
            scopes.projects(
                new AccessRequest.Resource(
                    ResourceScopeRepository.Kind.ORCHESTRATION_RECEIPT, key.toString())));
  }
}
