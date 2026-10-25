package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.JdbcInboxStore;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real SQL verdict equivalence and function counts pin both safety and the repeated-work defect.
 */
@Tag("full-db")
@Testcontainers
class LogAndWorkerAuthorityDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource source;
  private JdbcTemplate jdbc;
  private UnitOfWork work;
  private JdbcInformationProcessingRepository processing;

  private record Retained(UUID resource, UUID revision) {}

  @BeforeAll
  static void migrate() throws IOException {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    // Freeze the shipped V186 implementation as an independent oracle. It still calls the
    // unchanged scalar information_readable, not any of the new batched machinery.
    try (var input =
        LogAndWorkerAuthorityDatabaseTest.class.getResourceAsStream(
            "/db/migration/V186__log_readability_walks_the_ancestry.sql")) {
      assertNotNull(input);
      new JdbcTemplate(source)
          .execute(
              new String(input.readAllBytes(), StandardCharsets.UTF_8)
                  .replace(
                      "FUNCTION information_log_readable(", "FUNCTION log_readable_unbatched("));
    }
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(source);
    AuthorityFixtures.truncate(
        jdbc,
        "TRUNCATE admins,projects,conversations,information_quarantined_payloads,information_job_inputs,documents CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES('reader','fixture'),('other','fixture')");
    AuthorityFixtures.personalIdentity(jdbc, "reader");
    AuthorityFixtures.personalIdentity(jdbc, "other");
    jdbc.update("INSERT INTO projects(name) VALUES('one'),('two')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','MANAGER' FROM projects WHERE name='one'");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'other','MANAGER' FROM projects WHERE name='two'");
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    processing =
        new JdbcInformationProcessingRepository(
            jdbc, Clock.systemUTC(), mock(ProjectMembers.class));
  }

  private Retained retained(String owner, String project) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) VALUES(?,?,?,?,(SELECT id FROM projects WHERE name=?),'source')",
        resource,
        project == null ? "account:" + owner : "project:" + project,
        resource + ".txt",
        owner,
        project);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text,allowance_total) VALUES(?,?,1,'Retained','text/plain','fixture',1,'body',10)",
        revision,
        resource);
    return new Retained(resource, revision);
  }

  private void log(String id, String parent) {
    if (parent == null)
      jdbc.update(
          "INSERT INTO conversations(id,created_at,origin,budget_total,budget_spent,owner_handle) VALUES(?,now(),'turn',100,0,'reader')",
          id);
    else
      jdbc.update(
          "INSERT INTO conversations(id,created_at,origin,agent,parent_id,lifecycle,owner_handle) VALUES(?,now(),'delegation','agent',?,NULL,'reader')",
          id,
          parent);
  }

  private void input(
      String job, String owner, Retained value, String scope, String project, boolean global) {
    jdbc.update(
        "INSERT INTO information_job_inputs(job_id,owner_handle,revision_id,scope,project_name,include_shared) VALUES(?,?,?,?,?,?)",
        job,
        owner,
        value.revision(),
        scope,
        project,
        global);
  }

  private void input(String job, Retained value) {
    input(job, "reader", value, "project", "one", false);
  }

  private void audience(Retained value, String audience, boolean privateOnly) {
    jdbc.update(
        "UPDATE information_resource_policies SET version=version+1,audience=?,private_only=?,reason='AUDIENCE',actor=(SELECT id FROM authority_accounts WHERE legacy_handle='reader') WHERE resource_id=?",
        audience,
        privateOnly,
        value.resource());
  }

  private boolean readable(String job, String actor) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT information_log_readable(?,?)", Boolean.class, job, actor));
  }

  private void parity() {
    for (String actor : Arrays.asList("reader", "other", "missing", null)) {
      var expected =
          Set.copyOf(
              jdbc.queryForList(
                  "SELECT id FROM conversations WHERE log_readable_unbatched(id,?) UNION SELECT id FROM orchestrations WHERE log_readable_unbatched(id,?)",
                  String.class,
                  actor,
                  actor));
      var targeted =
          Set.copyOf(
              jdbc.queryForList(
                  "SELECT id FROM conversations WHERE information_log_readable(id,?) UNION SELECT id FROM orchestrations WHERE information_log_readable(id,?)",
                  String.class,
                  actor,
                  actor));
      var batched =
          Set.copyOf(
              jdbc.queryForList(
                  "WITH blocked AS MATERIALIZED (SELECT log FROM information_unreadable_logs(?)) SELECT id FROM conversations WHERE id NOT IN (SELECT log FROM blocked) UNION SELECT id FROM orchestrations WHERE id NOT IN (SELECT log FROM blocked)",
                  String.class,
                  actor));
      assertEquals(expected, targeted, "targeted verdicts for " + actor);
      assertEquals(expected, batched, "collection verdicts for " + actor);
    }
  }

  @Test
  void retainedSelectionsPrivacyOwnershipAndRevocationMatchTheScalarOracle() {
    log("empty", null);
    log("project", null);
    log("child", "project");
    log("foreign-owner", null);
    log("personal", null);
    log("general", null);
    log("account", null);
    log("invalid-selection", null);
    var project = retained("reader", "one");
    var personal = retained("reader", null);
    var publicValue = retained("other", "two");
    audience(publicValue, "GLOBAL_READ", false);
    input("project", project);
    input("foreign-owner", "other", publicValue, "project", "two", false);
    input("personal", "reader", personal, "personal", null, false);
    input("general", "reader", publicValue, "global_read", null, false);
    input("account", "reader", personal, "account", null, false);
    input("invalid-selection", "reader", project, "project", "two", false);
    parity();
    assertTrue(readable("project", "reader"));
    assertTrue(readable("personal", "reader"));
    assertTrue(readable("general", "reader"));
    assertFalse(readable("foreign-owner", "reader"));
    assertFalse(readable("invalid-selection", "reader"));
    assertTrue(readable("nonexistent", "reader"));
    assertTrue(readable(null, "reader"));
    audience(publicValue, "HOME", false);
    parity();
    assertFalse(readable("general", "reader"));
    audience(project, "HOME", true);
    parity();
    jdbc.update(
        "DELETE FROM project_members WHERE handle='reader' AND project_id=(SELECT id FROM projects WHERE name='one')");
    parity();
    assertFalse(readable("child", "reader"));
    jdbc.update("UPDATE admins SET enabled=false WHERE handle='reader'");
    parity();
    assertFalse(readable("personal", "reader"));
  }

  @Test
  void overlappingDependenciesCyclesAndEveryAncestryEdgeKeepTheirFences() {
    var a = retained("reader", "one");
    var b = retained("reader", "one");
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) VALUES(?,?),(?,?)",
        a.revision(),
        b.revision(),
        b.revision(),
        a.revision());
    for (String id : List.of("root", "conductor", "nested")) log(id, null);
    log("child", "nested");
    jdbc.update(
        "INSERT INTO orchestrations(id,definition_name,tier,definition_hash,definition_source,definition_origin,stages,max_returns,conductor_conversation,caller_agent,state,depth,created_at,caller_conversation) VALUES('outer','fixture','SHIPPED','hash','source','origin','[]',1,'conductor','agent','running',0,now(),'root')");
    jdbc.update(
        "INSERT INTO orchestrations(id,definition_name,tier,definition_hash,definition_source,definition_origin,stages,max_returns,conductor_conversation,caller_agent,state,depth,created_at,parent) VALUES('inner','fixture','SHIPPED','hash','source','origin','[]',1,'nested','agent','running',1,now(),'outer')");
    input("root", a);
    input("conductor", b);
    parity();
    assertTrue(readable("child", "reader"));
    audience(b, "QUARANTINED", false);
    parity();
    for (String id : List.of("root", "conductor", "nested", "child", "outer", "inner"))
      assertFalse(readable(id, "reader"), id);
    audience(b, "HOME", false);
    jdbc.update(
        "INSERT INTO information_quarantined_payloads(payload_id,reason) VALUES('root','fixture')");
    parity();
    assertFalse(readable("child", "reader"));
  }

  @Test
  void retainedDependencyClosureLimitStillFailsClosed() {
    log("bounded", null);
    var root = retained("reader", "one");
    input("bounded", root);
    // Each leaf is independently readable; only the per-root closure limit can refuse this log.
    for (int index = 0; index < 1024; index++) {
      var leaf = retained("reader", "one");
      jdbc.update(
          "INSERT INTO information_inputs(derived_revision,input_revision) VALUES(?,?)",
          root.revision(),
          leaf.revision());
    }
    assertFalse(readable("bounded", "reader"));
    assertFalse(
        jdbc.queryForList("SELECT log FROM information_unreadable_logs('reader')", String.class)
            .isEmpty());
  }

  private long calls(String function) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(calls),0)::bigint FROM pg_stat_xact_user_functions WHERE funcname=?",
        Long.class,
        function);
  }

  @Test
  void delegatedGrantExpiryAndIssuerRevocationAreObservedOnTheNextStatement()
      throws InterruptedException {
    log("delegated", null);
    input("delegated", "other", retained("reader", "one"), "project", "one", false);
    String issue =
        "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,issuing_grant,provenance,expires_at) SELECT recipient.id,parent.scope_id,role.id,'READER',parent.subject_account,parent.id,'ROLE',? FROM authority_grants parent JOIN authority_accounts owner ON owner.id=parent.subject_account AND owner.legacy_handle='reader' JOIN authority_scopes scope ON scope.id=parent.scope_id JOIN projects p ON p.id=scope.project_id CROSS JOIN authority_accounts recipient CROSS JOIN authority_roles role WHERE parent.provenance='MEMBERSHIP' AND parent.revoked_at IS NULL AND p.name='one' AND recipient.legacy_handle='other' AND role.name='READER'";
    jdbc.update(issue, java.sql.Timestamp.from(Instant.now().plusMillis(500)));
    assertTrue(readable("delegated", "other"));
    Instant expires =
        jdbc.queryForObject(
                "SELECT max(expires_at) FROM authority_grants WHERE provenance='ROLE'",
                java.sql.Timestamp.class)
            .toInstant();
    while (Instant.now().isBefore(expires.plusMillis(10))) Thread.sleep(10);
    parity();
    assertFalse(readable("delegated", "other"));
    jdbc.update(issue, (Object) null);
    assertTrue(readable("delegated", "other"));
    jdbc.update(
        "DELETE FROM project_members WHERE handle='reader' AND project_id=(SELECT id FROM projects WHERE name='one')");
    parity();
    assertFalse(readable("delegated", "other"));
  }

  @Test
  void personalAliasLinksGlobalInclusionAndReferenceHeadsPreserveTheirDistinctMeaning() {
    var personal = retained("reader", null);
    var target = retained("reader", "one");
    var live = retained("reader", "one");
    var pinned = retained("reader", "one");
    for (String id : List.of("alias", "linked", "live", "pinned", "included")) log(id, null);
    input(
        "alias",
        "reader",
        personal,
        "project",
        io.aeyer.plowshare.server.personal.PersonalSpaces.name("reader"),
        false);
    jdbc.update(
        "INSERT INTO information_links(revision_id,project_id,actor_handle) SELECT ?,id,'reader' FROM projects WHERE name='one'",
        personal.revision());
    input("linked", personal);
    input("live", live);
    input("pinned", pinned);
    var shared = retained("other", "two");
    audience(shared, "GLOBAL_READ", false);
    input("included", "reader", shared, "project", "one", true);
    jdbc.update(
        "INSERT INTO information_relationships(id,source,target,relation,actor) VALUES(?,?,?,'cites','reader')",
        UUID.randomUUID(),
        live.resource(),
        target.resource());
    jdbc.update(
        "INSERT INTO information_relationships(id,source,target,pinned_revision,relation,actor) VALUES(?,?,?,?,'cites','reader')",
        UUID.randomUUID(),
        pinned.resource(),
        target.resource(),
        target.revision());
    parity();
    for (String id : List.of("alias", "linked", "live", "pinned", "included"))
      assertTrue(readable(id, "reader"), id);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text,availability) VALUES(?,?,2,'New head','text/plain','new',1,'source','withdrawn')",
        UUID.randomUUID(),
        target.resource());
    parity();
    assertFalse(readable("live", "reader"));
    assertTrue(readable("pinned", "reader"));
    audience(shared, "GLOBAL_READ", true);
    parity();
    assertFalse(readable("included", "reader"));
  }

  private long activeCalls(Runnable read) {
    long before = calls("account_active");
    read.run();
    return calls("account_active") - before;
  }

  @Test
  void logAuthorityWorkIsConstantAcrossInputsAndCollectionRows() {
    log("root", null);
    input("root", retained("reader", "one"));
    work.inTransaction(
        () -> {
          jdbc.execute("SET LOCAL track_functions='all'");
          long small = activeCalls(() -> assertTrue(readable("root", "reader")));
          for (int index = 0; index < 48; index++) {
            input("root", retained("reader", "one"));
            log("child-" + index, "root");
          }
          long large = activeCalls(() -> assertTrue(readable("root", "reader")));
          long old =
              activeCalls(
                  () ->
                      assertTrue(
                          jdbc.queryForObject(
                              "SELECT log_readable_unbatched('root','reader')", Boolean.class)));
          long listing =
              activeCalls(
                  () ->
                      assertEquals(
                          49,
                          jdbc.queryForList(
                                  "SELECT id FROM conversations WHERE id NOT IN (SELECT log FROM information_unreadable_logs('reader'))",
                                  String.class)
                              .size()));
          assertTrue(small > 0, "the profiler must observe actual checks");
          assertEquals(small, large, "grant work must not scale with retained inputs");
          assertEquals(large, listing, "grant work must not scale with listed logs");
          assertTrue(large < old / 4, "unbatched=" + old + ", batched=" + large);
          return null;
        });
  }

  @Test
  void inboxUsesOneAccountSnapshotBeforeCountsPagingAndMarkRead() {
    log("good", null);
    log("bad", null);
    input("good", retained("reader", "one"));
    input("bad", "other", retained("other", "two"), "project", "two", false);
    var inbox = new JdbcInboxStore(jdbc);
    inbox.useInformationInputs(mock(InformationJobs.class));
    var now = Instant.now().plusSeconds(1);
    var good = inbox.deliver("reader", null, "good", "done", "answer", now);
    var bad = inbox.deliver("reader", null, "bad", "done", "hidden", now.plusSeconds(1));
    var plain = inbox.notice("reader", "sync.conflict", "notice", now.plusSeconds(2));
    assertEquals(2, inbox.unread("reader"));
    assertEquals(
        List.of(plain.id(), good.id()),
        inbox.list("reader", false, 0, 2).stream().map(row -> row.id()).toList());
    assertEquals(2, inbox.unreadSinceLastTurn("reader", "good"));
    assertEquals(
        Optional.of(now.plusSeconds(2)), inbox.newestUnreadSinceLastTurn("reader", "good"));
    assertEquals(
        2, inbox.markRead("reader", List.of(good.id(), bad.id(), plain.id()), now.plusSeconds(3)));
    assertEquals(0, inbox.unread("reader"));
    assertNull(
        jdbc.queryForObject(
            "SELECT read_at FROM user_inbox WHERE id=?", java.sql.Timestamp.class, bad.id()));
    assertEquals(0, inbox.unread("other"));
  }

  private void workerParity() {
    assertEquals(
        Set.copyOf(
            jdbc.queryForList(
                "SELECT id FROM information_revisions WHERE information_system_processing_valid(id)",
                UUID.class)),
        Set.copyOf(
            jdbc.queryForList(
                "SELECT revision FROM information_system_processing_revisions()", UUID.class)));
  }

  @Test
  void protectedCitationAndOrchestrationReadsFilterBeforeTheirLimit() {
    log("good", null);
    log("bad", null);
    var value = retained("reader", "one");
    input("good", value);
    input("bad", "other", retained("other", "two"), "project", "two", false);
    jdbc.update(
        "INSERT INTO documents(id,source_name,title,content_hash,text_hash,byte_size,ingested_at,ingested_by,information_namespace) SELECT r.id,q.source_name,r.title,r.content_hash,'fixture',r.byte_size,now(),'reader',q.namespace FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE r.id=?",
        value.revision());
    var paragraph = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO paragraphs(id,document_id,text,content_hash,occurrence,ordinal) VALUES(?,?,'body','fixture',1,1)",
        paragraph,
        value.revision());
    var citations = new io.aeyer.plowshare.server.documents.CitationStore(jdbc);
    var now = Instant.now();
    assertTrue(citations.record(paragraph, "good", 1, "agent", now));
    assertTrue(citations.record(paragraph, "bad", 1, "agent", now.plusSeconds(1)));
    var access =
        new InformationAccess(
            new io.aeyer.plowshare.server.access.EffectiveProjectPermissions(
                new io.aeyer.plowshare.server.archive.JdbcProjectMembers(jdbc)));
    var scoped =
        citations.scoped(
            access,
            new InformationContext(
                "reader",
                new InformationContext.Selection(InformationContext.Scope.PROJECT, "one", false)));
    assertEquals(1, scoped.recent(1).size());
    assertEquals(0, scoped.madeIn("bad", 10).size());
    for (String id : List.of("good", "bad"))
      jdbc.update(
          "INSERT INTO orchestrations(id,definition_name,tier,definition_hash,definition_source,definition_origin,stages,max_returns,conductor_conversation,caller_agent,caller_handle,state,depth,created_at) VALUES(?,'fixture','SHIPPED','hash','source','origin','[]',1,?,'agent','reader','running',0,?)",
          "run-" + id,
          id,
          java.sql.Timestamp.from(id.equals("bad") ? now.plusSeconds(1) : now));
    var runs = new io.aeyer.plowshare.server.orchestrations.OrchestrationStore(jdbc, Instant::now);
    runs.protectInformation();
    assertEquals(
        List.of("run-good"),
        runs.byCaller("reader", null, null, 1).stream().map(row -> row.id()).toList());
  }

  @Test
  void actualInboxQueriesDoNotRepeatAuthorityForEachNotice() {
    log("root", null);
    input("root", retained("reader", "one"));
    var inbox = new JdbcInboxStore(jdbc);
    inbox.useInformationInputs(mock(InformationJobs.class));
    var now = Instant.now();
    inbox.deliver("reader", null, "root", "done", "answer", now);
    work.inTransaction(
        () -> {
          jdbc.execute("SET LOCAL track_functions='all'");
          long small = activeCalls(() -> assertEquals(1, inbox.unread("reader")));
          for (int index = 0; index < 48; index++)
            inbox.deliver("reader", null, "root", "done", "answer", now.plusMillis(index + 1));
          long count = activeCalls(() -> assertEquals(49, inbox.unread("reader")));
          long list =
              activeCalls(() -> assertEquals(49, inbox.list("reader", true, 0, 100).size()));
          assertTrue(small > 0);
          assertEquals(small, count, "count must resolve authority once for the account");
          assertEquals(small, list, "paging must resolve authority before filtering notices");
          return null;
        });
  }

  @Test
  void serviceTokenSelectionsFollowCredentialOwnerAndScopeRevocation() {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,account_kind,must_change_password) VALUES('owner','fixture','SERVICE',false),('@service/probe','fixture','SERVICE_TOKEN',false)");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'owner','MANAGER' FROM projects WHERE name='one'");
    var token = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at) VALUES(?,'owner','fixture','@service/probe',?,now()+interval '1 hour')",
        token,
        token.toString());
    jdbc.update(
        "INSERT INTO service_token_scopes(token_id,project_id,role) SELECT ?,id,'VIEWER' FROM projects WHERE name='one'",
        token);
    log("token-log", null);
    input("token-log", "@service/probe", retained("reader", "one"), "project", "one", false);
    assertTrue(readable("token-log", "@service/probe"));
    for (String mutation :
        List.of(
            "UPDATE service_tokens SET credential_issued=false",
            "UPDATE service_tokens SET credential_issued=true,revoked_at=now()",
            "UPDATE service_tokens SET revoked_at=NULL,expires_at=now()-interval '1 second'",
            "UPDATE service_tokens SET expires_at=now()+interval '1 hour'")) {
      jdbc.update(mutation + " WHERE id=?", token);
      assertEquals(
          jdbc.queryForObject(
              "SELECT log_readable_unbatched('token-log','@service/probe')", Boolean.class),
          readable("token-log", "@service/probe"));
      assertEquals(
          !readable("token-log", "@service/probe"),
          jdbc.queryForList(
                  "SELECT log FROM information_unreadable_logs('@service/probe')", String.class)
              .contains("token-log"));
    }
    jdbc.update("DELETE FROM project_members WHERE handle='owner'");
    assertFalse(readable("token-log", "@service/probe"));
  }

  @Test
  void bulkSystemEligibilityRequiresLiveIntrinsicAuthorityAndEveryRequiredAction() {
    retained("reader", "one");
    for (String action : List.of("READ", "WRITE", "CONVERSATION_CREATE", "RUN_MODEL")) {
      new org.springframework.transaction.support.TransactionTemplate(
              new DataSourceTransactionManager(source))
          .executeWithoutResult(
              status -> {
                // Only the disposable test schema permits simulating a damaged builtin action
                // registry.
                // The real immutable-table trigger remains part of the migrated schema and is
                // restored
                // by transaction rollback. This pin catches a rewrite that treats SYSTEM identity
                // as a grant.
                status.setRollbackOnly();
                jdbc.execute(
                    "ALTER TABLE authority_role_actions DISABLE TRIGGER authority_role_actions_locked");
                jdbc.update(
                    "DELETE FROM authority_role_actions WHERE role_id=(SELECT role_id FROM authority_grants WHERE provenance='ROOT_POLICY') AND action=?",
                    action);
                workerParity();
                assertTrue(
                    jdbc.queryForList(
                            "SELECT revision FROM information_system_processing_revisions()",
                            UUID.class)
                        .isEmpty(),
                    action);
              });
    }
    new org.springframework.transaction.support.TransactionTemplate(
            new DataSourceTransactionManager(source))
        .executeWithoutResult(
            status -> {
              status.setRollbackOnly();
              jdbc.execute("ALTER TABLE authority_grants DISABLE TRIGGER authority_grant_mutation");
              jdbc.update(
                  "UPDATE authority_grants SET revoked_at=now(),version=version+1 WHERE provenance='ROOT_POLICY'");
              workerParity();
              assertTrue(
                  jdbc.queryForList(
                          "SELECT revision FROM information_system_processing_revisions()",
                          UUID.class)
                      .isEmpty());
            });
  }

  private void stages(Retained value) {
    for (String stage : InformationCatalogue.STAGES)
      jdbc.update(
          "INSERT INTO information_steps(revision_id,generation,stage,state) VALUES(?,1,?,'pending')",
          value.revision(),
          stage);
  }

  private void metadata(Retained value, String stage) {
    stages(value);
    jdbc.update("UPDATE information_steps SET state='ready' WHERE revision_id=?", value.revision());
    jdbc.update(
        "UPDATE information_steps SET state='skipped' WHERE revision_id=? AND stage=?",
        value.revision(),
        stage);
    if (stage.equals("tagGroups"))
      jdbc.update(
          "UPDATE information_resources SET tags='[\"evidence\"]'::jsonb WHERE id=?",
          value.resource());
  }

  @Test
  void metadataSweepsShareAuthorityAcrossEligibleRows() {
    for (String stage : List.of("autoTag", "tagGroups")) {
      var first = retained("reader", "one");
      metadata(first, stage);
      work.inTransaction(
          () -> {
            jdbc.execute("SET LOCAL track_functions='all'");
            Runnable one =
                () ->
                    assertEquals(
                        1,
                        (stage.equals("autoTag")
                                ? processing.sweepUntagged()
                                : processing.sweepTagGroups())
                            .size());
            long small = activeCalls(one);
            jdbc.update(
                "UPDATE information_steps SET state='skipped' WHERE revision_id=? AND stage=?",
                first.revision(),
                stage);
            for (int index = 0; index < 48; index++) metadata(retained("reader", "one"), stage);
            long large =
                activeCalls(
                    () ->
                        assertEquals(
                            49,
                            (stage.equals("autoTag")
                                    ? processing.sweepUntagged()
                                    : processing.sweepTagGroups())
                                .size()));
            assertTrue(small > 0);
            assertEquals(small, large, stage + " must share authority across the selected rows");
            return null;
          });
    }
  }

  @Test
  void workerValidityPreservesCyclesWithdrawalExclusionAndCreatorIndependence() {
    var a = retained("reader", "one");
    var b = retained("other", "two");
    retained("reader", null);
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) VALUES(?,?),(?,?)",
        a.revision(),
        b.revision(),
        b.revision(),
        a.revision());
    workerParity();
    jdbc.update("UPDATE admins SET enabled=false WHERE handle='other'");
    workerParity();
    assertTrue(
        jdbc.queryForList(
                "SELECT revision FROM information_system_processing_revisions()", UUID.class)
            .contains(a.revision()));
    jdbc.update("UPDATE information_revisions SET excluded=true WHERE id=?", b.revision());
    workerParity();
    assertFalse(
        jdbc.queryForList(
                "SELECT revision FROM information_system_processing_revisions()", UUID.class)
            .contains(a.revision()));
    jdbc.update(
        "UPDATE information_revisions SET excluded=false,availability='withdrawn' WHERE id=?",
        b.revision());
    workerParity();
    assertFalse(
        jdbc.queryForList(
                "SELECT revision FROM information_system_processing_revisions()", UUID.class)
            .contains(a.revision()));
  }

  @Test
  void workerAuthorityWorkIsConstantAcrossCandidatesAndBothSweeps() {
    var first = retained("reader", "one");
    stages(first);
    work.inTransaction(
        () -> {
          jdbc.execute("SET LOCAL track_functions='all'");
          long small = activeCalls(() -> assertTrue(processing.candidate(null, false).isPresent()));
          for (int index = 0; index < 48; index++) stages(retained("reader", "one"));
          long large = activeCalls(() -> assertTrue(processing.candidate(null, false).isPresent()));
          assertTrue(small > 0, "the profiler must observe actual checks");
          assertEquals(small, large, "claim grant work must not scale with candidates");
          long before = calls("account_active");
          jdbc.queryForList(
              "SELECT id FROM information_revisions WHERE information_system_processing_valid(id)",
              UUID.class);
          long old = calls("account_active") - before;
          long batched =
              activeCalls(
                  () ->
                      jdbc.queryForList(
                          "SELECT revision FROM information_system_processing_revisions()",
                          UUID.class));
          assertTrue(batched < old / 4, "unbatched=" + old + ", batched=" + batched);
          // All metadata stages are already pending, so neither sweep has reset writes to obscure
          // its permission cost. Even an empty result must not traverse grants once per revision.
          long tagging = activeCalls(() -> assertTrue(processing.sweepUntagged().isEmpty()));
          long grouping = activeCalls(() -> assertTrue(processing.sweepTagGroups().isEmpty()));
          assertTrue(tagging <= batched, "tag sweep=" + tagging + ", authority=" + batched);
          assertTrue(grouping <= batched, "group sweep=" + grouping + ", authority=" + batched);
          return null;
        });
  }
}
