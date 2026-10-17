package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real admission/support rollback, immutability, exact-grant revocation and job CAS semantics. */
@Tag("full-db")
@Testcontainers
class OperationRecordsPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  JdbcTemplate jdbc;
  JdbcOperationRecords records;
  PinnedOperationAuthority policy;

  @BeforeAll
  static void migrate() {
    source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(source);
    AuthorityFixtures.truncate(jdbc, "TRUNCATE projects,admins CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin,must_change_password) VALUES('admin','fixture',true,false),('alice','fixture',false,false)");
    jdbc.update("INSERT INTO projects(name) VALUES('one')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'alice','CONTRIBUTOR' FROM projects WHERE name='one'");
    var identities = new JdbcIdentityRepository(jdbc);
    identities.verifyBuiltIns();
    var grants = new JdbcScopedGrantRepository(jdbc);
    ApplicationPolicy applications =
        project -> new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of());
    var authority =
        new LiveScopedAuthority(
            grants, applications, new ApplicationGrantLineage(grants, applications), identities);
    records = new JdbcOperationRecords(jdbc);
    policy =
        new PinnedOperationAuthority(
            identities,
            grants,
            authority,
            new JdbcProjectMembers(jdbc),
            records,
            new JdbcResourceScopeRepository(jdbc));
  }

  OperationAuthority.Admission admit() {
    return policy.admit(
        "alice",
        Home.of("one"),
        Set.of(AuthorityAction.READ, AuthorityAction.WRITE, AuthorityAction.RUN_MODEL));
  }

  @Test
  void captured_grant_and_scope_survive_restart_without_replacement_authority() {
    var admitted = admit();
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT count(*) FROM operation_admission_support WHERE operation=?",
            Integer.class,
            admitted.id()));
    policy.started(admitted, "job-original");
    assertEquals(
        "job-original",
        jdbc.queryForObject(
            "SELECT job_id FROM operation_admissions WHERE id=?", String.class, admitted.id()));
    var members = new JdbcProjectMembers(jdbc);
    members.remove("one", "alice");
    members.assign("one", "alice", ProjectRole.CONTRIBUTOR, "admin", true);
    assertThrows(CallerFault.class, () -> policy.require(admitted));
    assertEquals(
        admitted.scope().value(),
        jdbc.queryForObject(
            "SELECT scope FROM operation_admissions WHERE id=?", UUID.class, admitted.id()));
    assertDoesNotThrow(() -> admit());
  }

  @Test
  void support_failure_rolls_back_and_history_cannot_be_widened() {
    var good = admit();
    var bad =
        new OperationAuthority.Admission(
            UUID.randomUUID(),
            good.executor(),
            good.account(),
            good.scope(),
            good.home(),
            good.source(),
            List.of(new OperationAuthority.Support(AuthorityAction.RUN_MODEL, UUID.randomUUID())));
    assertThrows(DataAccessException.class, () -> records.retain(bad));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM operation_admissions", Integer.class));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE operation_admissions SET project_name='two' WHERE id=?", good.id()));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM operation_admission_support WHERE operation=?", good.id()));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO operation_admission_support(operation,action,grant_id) VALUES(?,'CONVERSATION_CREATE',?)",
                good.id(),
                good.support().getFirst().grant()));
  }

  @Test
  void correlation_is_idempotent_for_the_same_job_and_concurrent_rebinding_has_one_winner()
      throws Exception {
    var admission = admit();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (String job : List.of("job-one", "job-two"))
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    records.started(admission.id(), job);
                    return true;
                  } catch (IllegalStateException denied) {
                    return false;
                  }
                }));
      start.countDown();
      int accepted = 0;
      for (var result : futures) if (result.get(10, TimeUnit.SECONDS)) accepted++;
      assertEquals(1, accepted);
    }
    String job =
        jdbc.queryForObject(
            "SELECT job_id FROM operation_admissions WHERE id=?", String.class, admission.id());
    assertDoesNotThrow(() -> new JdbcOperationRecords(jdbc).started(admission.id(), job));
    assertThrows(IllegalStateException.class, () -> records.started(admission.id(), "replacement"));
  }
}
