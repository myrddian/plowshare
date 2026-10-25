package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.JdbcInboxStore;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual PostgreSQL prepared plans, function boundaries and live revocation require a database. */
@Tag("full-db")
@Testcontainers
class InteractiveLibraryPlanDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource source;
  private JdbcTemplate jdbc;
  private UnitOfWork work;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(source);
    AuthorityFixtures.truncate(jdbc, "TRUNCATE admins,projects CASCADE");
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('reader','fixture')");
    jdbc.update("INSERT INTO projects(name) VALUES('selected')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','VIEWER' FROM projects WHERE name='selected'");
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) SELECT gen_random_uuid(),'project:selected','document-'||n,'reader',(SELECT id FROM projects WHERE name='selected'),'source' FROM generate_series(1,48) n");
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text) SELECT gen_random_uuid(),id,1,'Document','text/plain','fixture',1,'source' FROM information_resources");
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
  }

  private InformationSql.Filter selection() {
    return InformationSelectionSql.catalogue(
        new InformationContext("reader", InformationContext.Selection.project("selected")), null);
  }

  private record Query(String sql, List<Object> arguments) {}

  /** Capture the owning repository's executed SQL, rather than explaining a parallel test query. */
  private static final class RecordingJdbc extends JdbcTemplate {
    private Query last;

    RecordingJdbc(DataSource source) {
      super(source);
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> type, Object... args) {
      last = new Query(sql, Arrays.asList(args.clone()));
      return super.queryForObject(sql, type, args);
    }
  }

  private String prepare(Query query) {
    var sql = new StringBuilder();
    int parameter = 0;
    // These code-owned selection/inbox statements contain only JDBC parameter question marks.
    for (char character : query.sql().toCharArray()) {
      if (character == '?') sql.append('$').append(++parameter);
      else sql.append(character);
    }
    assertEquals(query.arguments().size(), parameter);
    jdbc.execute("PREPARE library_plan AS " + sql);
    return "EXECUTE library_plan("
        + query.arguments().stream()
            .map(
                value ->
                    value == null
                        ? "NULL"
                        : value instanceof Boolean || value instanceof Number
                            ? value.toString()
                            : "'" + value.toString().replace("'", "''") + "'")
            .collect(java.util.stream.Collectors.joining(","))
        + ")";
  }

  private JsonNode plan(String execute) {
    try {
      return new ObjectMapper()
          .readTree(jdbc.queryForObject("EXPLAIN (ANALYZE, FORMAT JSON) " + execute, String.class))
          .get(0);
    } catch (java.io.IOException invalid) {
      throw new AssertionError("Invalid PostgreSQL JSON plan", invalid);
    }
  }

  private boolean hasFunctionBoundary(JsonNode plan, String function) {
    var pending = new ArrayList<JsonNode>();
    pending.add(plan.path("Plan"));
    boolean boundary = false;
    for (int index = 0; index < pending.size(); index++) {
      JsonNode node = pending.get(index);
      boundary |=
          node.path("Node Type").asText().equals("Function Scan")
              && node.path("Function Name").asText().equals(function);
      node.path("Plans").forEach(pending::add);
    }
    return boundary;
  }

  private void assertBoundedPlan(JsonNode plan, String function) {
    assertTrue(
        hasFunctionBoundary(plan, function),
        "Authority must remain behind its statement-local function boundary");
    assertFalse(plan.has("JIT"), "A small interactive read must not compile the authority graph");
    assertTrue(plan.path("Plan").path("Total Cost").asDouble() < 100000);
    assertEquals("on", jdbc.queryForObject("SHOW jit", String.class));
  }

  private void settings(String mode) {
    jdbc.execute("SET LOCAL plan_cache_mode=" + mode);
    jdbc.execute("SET LOCAL jit=on");
    jdbc.execute("SET LOCAL jit_above_cost=100000");
    jdbc.execute("SET LOCAL jit_inline_above_cost=500000");
    jdbc.execute("SET LOCAL jit_optimize_above_cost=500000");
  }

  @ParameterizedTest
  @ValueSource(strings = {"force_custom_plan", "force_generic_plan"})
  void cataloguePlansKeepAuthorityBoundedAndRecheckRevocationWhenThePreparedPlanIsReused(
      String mode) {
    work.inTransaction(
        () -> {
          settings(mode);
          var filter = selection();
          String execute =
              prepare(
                  new Query(
                      "SELECT r.id FROM information_revisions r WHERE " + filter.sql(),
                      filter.arguments()));
          assertEquals(48, jdbc.queryForList(execute, UUID.class).size());
          assertBoundedPlan(plan(execute), "knowledge_readable_selection");
          jdbc.update(
              "DELETE FROM project_members WHERE handle='reader' AND project_id=(SELECT id FROM projects WHERE name='selected')");
          assertTrue(jdbc.queryForList(execute, UUID.class).isEmpty());
          assertBoundedPlan(plan(execute), "knowledge_readable_selection");
          jdbc.execute("DEALLOCATE library_plan");
          return null;
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"force_custom_plan", "force_generic_plan"})
  void theActualInboxCountDoesNotCompileTheLogGraphEvenWhenTheInboxIsEmpty(String mode) {
    work.inTransaction(
        () -> {
          settings(mode);
          var recorded = new RecordingJdbc(source);
          var inbox = new JdbcInboxStore(recorded);
          inbox.useInformationInputs(mock(InformationJobs.class));
          assertEquals(0, inbox.unread("reader"));
          assertNotNull(recorded.last);
          String execute = prepare(recorded.last);
          assertEquals(0, jdbc.queryForObject(execute, Integer.class));
          assertBoundedPlan(plan(execute), "information_unreadable_logs");
          jdbc.execute("DEALLOCATE library_plan");
          return null;
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"knowledge_readable_selection", "information_unreadable_logs"})
  void restoringThePre196ConfigurationReproducesTheCompilerHeavyPlan(String function) {
    work.inTransaction(
        () -> {
          settings("force_generic_plan");
          Query query;
          String signature;
          if (function.equals("knowledge_readable_selection")) {
            var filter = selection();
            query =
                new Query(
                    "SELECT r.id FROM information_revisions r WHERE " + filter.sql(),
                    filter.arguments());
            signature =
                "knowledge_readable_selection(text,text,text,boolean,text,boolean,text,text,boolean,uuid,text,text)";
          } else {
            var recorded = new RecordingJdbc(source);
            var inbox = new JdbcInboxStore(recorded);
            inbox.useInformationInputs(mock(InformationJobs.class));
            assertEquals(0, inbox.unread("reader"));
            query = recorded.last;
            signature = "information_unreadable_logs(text)";
          }
          String execute = prepare(query);
          assertBoundedPlan(plan(execute), function);
          // V196 changes only this local configuration. Restoring it reproduces the shipped
          // expansion, independently of wall-clock load. Prepare afresh: an already bounded plan
          // need not be expanded when a function setting is removed. Revocation reuse is tested
          // separately above without deallocating its prepared selection.
          try {
            jdbc.execute("DEALLOCATE library_plan");
            jdbc.execute("ALTER FUNCTION " + signature + " RESET ALL");
            assertNull(
                jdbc.queryForObject(
                    "SELECT proconfig::text FROM pg_proc WHERE oid=?::regprocedure",
                    String.class,
                    signature));
            execute = prepare(query);
            JsonNode before = plan(execute);
            // This small fixture need not cross production's compiler threshold. The expansion
            // itself is the work regression; representative production plans cross that threshold.
            assertFalse(hasFunctionBoundary(before, function));
            assertThrows(AssertionError.class, () -> assertBoundedPlan(before, function));
          } finally {
            jdbc.execute("ALTER FUNCTION " + signature + " SET jit='off'");
            jdbc.execute("DEALLOCATE library_plan");
          }
          execute = prepare(query);
          assertBoundedPlan(plan(execute), function);
          jdbc.execute("DEALLOCATE library_plan");
          return null;
        });
  }
}
