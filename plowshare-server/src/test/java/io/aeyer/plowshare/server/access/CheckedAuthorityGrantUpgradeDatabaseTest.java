package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.CheckedScopedGrants.Reason;
import io.aeyer.plowshare.server.access.ScopedGrantRepository.Binding;
import io.aeyer.plowshare.server.cache.JdbcProjectionVersions;
import io.aeyer.plowshare.server.cache.ProjectionVersions.Domain;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proves append-only upgrades from the pre-feature schema and canonical main. The
 * fixture keeps a person-issued membership and an agent descendant across the canonical reroot
 * migration, then exercises the new checked reads against their migrated live lineage.
 */
@Tag("full-db")
@Testcontainers
class CheckedAuthorityGrantUpgradeDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private record AppliedMigration(
      int rank,
      String version,
      String description,
      String type,
      String script,
      Integer checksum,
      String installedBy,
      LocalDateTime installedOn,
      int executionTime,
      boolean success) {}

  /** All persisted history fields must remain untouched; no repair or baseline rewrite is used. */
  private List<AppliedMigration> history(JdbcTemplate jdbc) {
    return jdbc.query(
        "SELECT * FROM flyway_schema_history ORDER BY installed_rank",
        (row, n) ->
            new AppliedMigration(
                row.getInt("installed_rank"),
                row.getString("version"),
                row.getString("description"),
                row.getString("type"),
                row.getString("script"),
                row.getObject("checksum", Integer.class),
                row.getString("installed_by"),
                row.getObject("installed_on", LocalDateTime.class),
                row.getInt("execution_time"),
                row.getBoolean("success")));
  }

  private GrantRecipient recipient(JdbcTemplate jdbc, String handle, String agent) {
    var account =
        new AccountId(
            jdbc.queryForObject(
                "SELECT id FROM authority_accounts WHERE legacy_handle=?", UUID.class, handle));
    return new GrantRecipient(account, Optional.ofNullable(agent));
  }

  /** The original JDBC read is an independent ordering and row-mapping oracle. */
  private List<Binding> live(JdbcTemplate jdbc, GrantRecipient recipient) {
    return jdbc.query(
        "SELECT g.* FROM authority_grant_records g JOIN authority_live_grants() l ON l.id=g.id "
            + "WHERE g.subject_account=? AND g.subject_agent IS NOT DISTINCT FROM ? ORDER BY g.created_at,g.id",
        (row, n) -> JdbcScopedGrantRepository.read(row),
        recipient.account().value(),
        recipient.agent().orElse(null));
  }

  private List<Binding> lineage(JdbcTemplate jdbc, UUID grant) {
    return jdbc.query(
        "WITH RECURSIVE chain(id,issuing_grant,depth) AS ("
            + "SELECT g.id,g.issuing_grant,0 FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id WHERE g.id=? "
            + "UNION ALL SELECT p.id,p.issuing_grant,c.depth+1 FROM authority_grants p JOIN chain c ON c.issuing_grant=p.id WHERE c.depth<32) "
            + "SELECT g.* FROM chain c JOIN authority_grant_records g ON g.id=c.id ORDER BY c.depth",
        (row, n) -> JdbcScopedGrantRepository.read(row),
        grant);
  }

  /** A canonical-schema upgrade only installs functions; all grant and registry rows stay put. */
  private String grantAndRegistryState(JdbcTemplate jdbc) {
    return jdbc.queryForObject(
        "SELECT md5(concat("
            + "(SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id) FROM authority_roles r),"
            + "(SELECT jsonb_agg(to_jsonb(a) ORDER BY a.role_id,a.role_version,a.profile,a.action) FROM authority_role_actions a),"
            + "(SELECT jsonb_agg(to_jsonb(s) ORDER BY s.id) FROM authority_scopes s),"
            + "(SELECT jsonb_agg(to_jsonb(g) ORDER BY g.id) FROM authority_grants g)))",
        String.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {260, 263})
  void appendsCheckedReadsWithoutRewritingHistoryOrLosingExistingAuthority(int startingVersion) {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "checked_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var bootstrap = new JdbcTemplate(source);
    bootstrap.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    bootstrap.execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    var original =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .target(Integer.toString(startingVersion))
            .load();
    original.migrate();
    assertEquals(
        Integer.toString(startingVersion), original.info().current().getVersion().toString());
    var jdbc = new JdbcTemplate(source);
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
                + "WHERE n.nspname=current_schema() AND p.proname IN "
                + "('authority_grants_check','authority_live_grants_checked','authority_lineage_checked')",
            Long.class));
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password) VALUES('manager','fixture',false),('member','fixture',false)");
    jdbc.update("INSERT INTO projects(name) VALUES('upgrade')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'manager','MANAGER' FROM projects WHERE name='upgrade'");
    UUID issued =
        jdbc.queryForObject(
            "SELECT authority_issue_membership('upgrade','member','manager','MANAGER')",
            UUID.class);
    UUID delegated = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_grants(id,subject_account,subject_agent,scope_id,role_id,requested_role,"
            + "issuer_account,issuing_grant,provenance) SELECT ?,parent.subject_account,'scout',parent.scope_id,role.id,"
            + "'READER',parent.subject_account,parent.id,'ROLE' FROM authority_grants parent CROSS JOIN authority_roles role "
            + "WHERE parent.id=? AND role.name='READER'",
        delegated,
        issued);
    var member = recipient(jdbc, "member", null);
    var agent = recipient(jdbc, "member", "scout");
    var memberBefore = live(jdbc, member);
    var agentBefore = live(jdbc, agent);
    assertEquals(1, memberBefore.size());
    assertEquals(1, agentBefore.size());
    assertTrue(lineage(jdbc, delegated).size() >= 3);
    var before = history(jdbc);
    String grantAndRegistryBefore = grantAndRegistryState(jdbc);

    var upgrade =
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load();
    assertEquals(264 - startingVersion, upgrade.migrate().migrationsExecuted);
    upgrade.validate();
    assertEquals("264", upgrade.info().current().getVersion().toString());
    var after = history(jdbc);
    assertEquals(
        before,
        after.subList(0, before.size()),
        "Every existing Flyway history field is preserved");
    assertEquals(264 - startingVersion, after.size() - before.size());
    assertEquals("264", after.getLast().version());
    assertTrue(after.stream().allMatch(AppliedMigration::success));
    assertEquals(0, upgrade.migrate().migrationsExecuted, "A repeat upgrade is a no-op");
    assertTrue(jdbc.queryForObject("SELECT authority_builtin_registry_valid()", Boolean.class));
    if (startingVersion == 263)
      assertEquals(
          grantAndRegistryBefore,
          grantAndRegistryState(jdbc),
          "Installing checked functions preserves every canonical grant and role/action/scope registry row");

    // Canonical V262 may replace grant identities while preserving access; V264 must preserve
    // canonical V263 identities as well. Test the current live chains, not obsolete revoked ids.
    var memberAfter = live(jdbc, member);
    var agentAfter = live(jdbc, agent);
    assertEquals(
        memberBefore.stream().map(Binding::role).toList(),
        memberAfter.stream().map(Binding::role).toList());
    assertEquals(
        agentBefore.stream().map(Binding::role).toList(),
        agentAfter.stream().map(Binding::role).toList());
    if (startingVersion == 263) {
      assertEquals(memberBefore, memberAfter);
      assertEquals(agentBefore, agentAfter);
    }
    UUID currentGrant = agentAfter.getFirst().id();
    var expectedLineage = lineage(jdbc, currentGrant);
    assertEquals(memberAfter.getFirst().id(), expectedLineage.get(1).id());
    assertTrue(
        jdbc.queryForObject(
            "SELECT exercised_grant IS NOT NULL FROM authority_grants WHERE id=?",
            Boolean.class,
            memberAfter.getFirst().id()));
    assertTrue(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM authority_roles WHERE name='PROJECT_CREATOR')",
            Boolean.class));

    var token =
        new JdbcProjectionVersions(jdbc, source, 128)
            .capture(Set.of(Domain.AUTHORITY))
            .orElseThrow();
    var snapshot =
        new AuthoritySnapshot(
            new JdbcAuthoritySnapshotRepository(jdbc, 10_000).load().orElseThrow());
    var stored = new JdbcScopedGrantRepository(jdbc);
    new TransactionTemplate(new DataSourceTransactionManager(source))
        .executeWithoutResult(
            status -> {
              assertTrue(stored.liveChecked(agent, Optional.of(token)).exact());
              assertTrue(stored.lineageChecked(currentGrant, Optional.of(token)).exact());
              assertEquals(agentAfter, snapshot.live(agent));
              assertEquals(expectedLineage, snapshot.lineage(currentGrant));
              var liveSql = stored.liveChecked(agent, Optional.empty());
              var lineageSql = stored.lineageChecked(currentGrant, Optional.empty());
              assertEquals(Reason.INELIGIBLE, liveSql.reason());
              assertEquals(Reason.INELIGIBLE, lineageSql.reason());
              assertEquals(agentAfter, liveSql.rows());
              assertEquals(expectedLineage, lineageSql.rows());
              jdbc.update("UPDATE admins SET enabled=false WHERE handle='member'");
              assertEquals(Reason.DIRTY, stored.liveChecked(agent, Optional.of(token)).reason());
              assertEquals(
                  Reason.DIRTY, stored.lineageChecked(currentGrant, Optional.of(token)).reason());
              assertFalse(stored.liveChecked(agent, Optional.of(token)).exact());
              assertTrue(stored.liveChecked(agent, Optional.of(token)).rows().isEmpty());
              assertTrue(stored.lineageChecked(currentGrant, Optional.of(token)).rows().isEmpty());
              status.setRollbackOnly();
            });
  }
}
