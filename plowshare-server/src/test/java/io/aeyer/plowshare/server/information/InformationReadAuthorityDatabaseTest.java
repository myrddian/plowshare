package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.sql.Timestamp;
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

/** PostgreSQL equivalence, bounded closure semantics and actual function-call work require a DB. */
@Tag("full-db")
@Testcontainers
class InformationReadAuthorityDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource source;
  private static final String PERSONAL =
      io.aeyer.plowshare.server.personal.PersonalSpaces.name("reader");
  private JdbcTemplate jdbc;
  private UnitOfWork work;

  private record Retained(UUID resource, UUID revision) {}

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
    io.aeyer.plowshare.server.testing.AuthorityFixtures.truncate(
        jdbc, "TRUNCATE admins,projects CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES('reader','fixture'),('other','fixture'),('disabled','fixture')");
    jdbc.update("UPDATE admins SET enabled=false WHERE handle='disabled'");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,account_kind,must_change_password) VALUES('owner','fixture','SERVICE',false),('@service/probe','fixture','SERVICE_TOKEN',false)");
    jdbc.update("INSERT INTO projects(name) VALUES('one'),('two')");
    jdbc.update("INSERT INTO projects(name,personal_owner) VALUES(?,'reader')", PERSONAL);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','VIEWER' FROM projects WHERE name='one'");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','MANAGER' FROM projects WHERE name=?",
        PERSONAL);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'owner','MANAGER' FROM projects WHERE name='one'");
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
  }

  private Retained retained(String owner, String project) {
    return retained(owner, project, project == null ? "account:" + owner : "project:" + project);
  }

  private Retained retained(String owner, String project, String namespace) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind,tags) VALUES(?,?,?,?,(SELECT id FROM projects WHERE name=?),'source','[]')",
        resource,
        namespace,
        resource + ".txt",
        owner,
        project);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text) VALUES(?,?,1,'Retained','text/plain','fixture',1,'source')",
        revision,
        resource);
    return new Retained(resource, revision);
  }

  private void audience(Retained value, String audience, boolean privateOnly) {
    jdbc.update(
        "UPDATE information_resource_policies SET version=version+1,audience=?,private_only=?,reason='AUDIENCE',actor=(SELECT id FROM authority_accounts WHERE legacy_handle='reader') WHERE resource_id=?",
        audience,
        privateOnly,
        value.resource());
  }

  private InformationContext context(
      String actor, InformationContext.Scope scope, String project, boolean global) {
    return new InformationContext(actor, new InformationContext.Selection(scope, project, global))
        .withView(true);
  }

  private Set<UUID> selected(
      InformationContext context, boolean heads, UUID related, String relation, String direction) {
    var filter =
        heads
            ? InformationSelectionSql.navigation(context, related, relation, direction)
            : InformationSelectionSql.catalogue(context, null);
    return Set.copyOf(
        jdbc.queryForList(
            "SELECT r.id FROM information_revisions r WHERE " + filter.sql(),
            UUID.class,
            filter.arguments().toArray()));
  }

  private Set<UUID> legacy(
      InformationContext context, boolean heads, UUID related, String relation, String direction) {
    var arguments = new ArrayList<Object>();
    String predicate;
    if (related == null) {
      var read = InformationSql.read(context, "r");
      predicate = read.sql();
      arguments.addAll(read.arguments());
    } else {
      var boundary = InformationSql.boundary(context, "r.id");
      predicate = "knowledge_dependencies_readable(r.id,?) AND " + boundary.sql();
      arguments.add(context.account());
      arguments.addAll(boundary.arguments());
      predicate += " AND information_readable(information_navigation_head(?),?,?,?,?)";
      arguments.addAll(
          Arrays.asList(
              related,
              context.account(),
              context.selection().scope().name().toLowerCase(Locale.ROOT),
              context.selection().project(),
              context.selection().includeGlobal()));
      var parent = InformationSql.boundary(context, "information_navigation_head(?)");
      predicate += " AND " + parent.sql();
      if (context.readBoundary() != null) arguments.add(related);
      arguments.addAll(parent.arguments());
      boolean outgoing = !"backlinks".equals(direction);
      predicate +=
          " AND EXISTS(SELECT 1 FROM information_relationships edge WHERE NOT edge.removed AND edge."
              + (outgoing ? "source" : "target")
              + "=? AND edge."
              + (outgoing ? "target" : "source")
              + "=q.id"
              + (relation == null ? "" : " AND edge.relation=?")
              + ")";
      arguments.add(related);
      if (relation != null) arguments.add(relation);
    }
    predicate += " AND r.availability='active' AND NOT r.excluded AND r.document_type=?";
    arguments.add(context.corpus().documentType());
    if (heads) predicate += " AND r.id=information_navigation_head(q.id)";
    return Set.copyOf(
        jdbc.queryForList(
            "SELECT r.id FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE "
                + predicate,
            UUID.class,
            arguments.toArray()));
  }

  private void parity(InformationContext context) {
    assertEquals(
        legacy(context, false, null, null, null),
        selected(context, false, null, null, null),
        context.toString());
    assertEquals(
        legacy(context, true, null, null, null),
        selected(context, true, null, null, null),
        context.toString());
    var oldScopes =
        Set.copyOf(
            jdbc.queryForList(
                "SELECT id FROM authority_scopes WHERE knowledge_scope_readable(?,id)",
                UUID.class,
                context.account()));
    var scopes =
        Set.copyOf(
            jdbc.queryForList(
                "SELECT scope_id FROM knowledge_read_authority(?) WHERE scope_id IS NOT NULL",
                UUID.class,
                context.account()));
    assertEquals(oldScopes, scopes, "scope relation for " + context.account());
  }

  @Test
  void allSelectionsPreservePrivateGeneralAccountLinkedAndInactiveRules() {
    retained("reader", "one");
    retained("reader", PERSONAL);
    retained("other", "two");
    retained("reader", null);
    var linked = retained("reader", null);
    jdbc.update(
        "INSERT INTO information_links(revision_id,project_id,actor_handle) SELECT ?,id,'reader' FROM projects WHERE name='one'",
        linked.revision());
    var privateValue = retained("other", "one");
    audience(privateValue, "HOME", true);
    var published = retained("other", "two");
    audience(published, "GLOBAL_READ", false);
    var quarantined = retained("reader", "one");
    audience(quarantined, "QUARANTINED", false);
    String global =
        jdbc.queryForObject(
            "SELECT legacy_handle FROM authority_accounts WHERE builtin='GLOBAL'", String.class);
    // The migration's immutable Global account namespace is a destination, not general READ.
    retained(global, null, "account:00000000-0000-0000-0000-000000000004");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES('operator','fixture',true)");
    var actors =
        new ArrayList<>(
            List.of(
                "reader", "other", "disabled", "missing", "operator", "owner", "@service/probe"));
    actors.addAll(
        jdbc.queryForList(
            "SELECT legacy_handle FROM authority_accounts WHERE builtin IS NOT NULL",
            String.class));
    for (String actor : actors) {
      parity(context(actor, InformationContext.Scope.PERSONAL, null, false));
      parity(context(actor, InformationContext.Scope.PERSONAL, null, true));
      parity(context(actor, InformationContext.Scope.PROJECT, "one", false));
      parity(context(actor, InformationContext.Scope.PROJECT, PERSONAL, false));
      parity(context(actor, InformationContext.Scope.PROJECT, "two", true));
      parity(context(actor, InformationContext.Scope.GLOBAL_READ, null, false));
      parity(
          new InformationContext(
                  actor,
                  InformationContext.Selection.account(
                      UUID.fromString("00000000-0000-0000-0000-000000000004")))
              .withView(true));
    }
    assertTrue(
        selected(
                context("reader", InformationContext.Scope.PROJECT, "one", false),
                false,
                null,
                null,
                null)
            .contains(linked.revision()));
    assertFalse(
        selected(
                context("reader", InformationContext.Scope.PROJECT, "one", false),
                false,
                null,
                null,
                null)
            .contains(privateValue.revision()));
  }

  private UUID token(Instant expires) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at) VALUES(?,'owner','probe','@service/probe',?,?)",
        id,
        id.toString(),
        Timestamp.from(expires));
    jdbc.update(
        "INSERT INTO service_token_scopes(token_id,project_id,role) SELECT ?,id,'VIEWER' FROM projects WHERE name='one'",
        id);
    return id;
  }

  @Test
  void tokenReadRequiresIssuedLiveCredentialEnabledOwnerAndCurrentOwnerGrant() {
    var value = retained("reader", "one");
    UUID token = token(Instant.now().plusSeconds(60));
    var context = context("@service/probe", InformationContext.Scope.PROJECT, "one", false);
    parity(context);
    assertTrue(selected(context, false, null, null, null).contains(value.revision()));
    for (String change :
        List.of(
            "UPDATE service_tokens SET credential_issued=false",
            "UPDATE service_tokens SET credential_issued=true,revoked_at=now()",
            "UPDATE service_tokens SET revoked_at=NULL,expires_at=now()-interval '1 second'")) {
      jdbc.update(change + " WHERE id=?", token);
      parity(context);
      assertTrue(selected(context, false, null, null, null).isEmpty());
    }
    jdbc.update("UPDATE service_tokens SET expires_at=now()+interval '1 hour' WHERE id=?", token);
    jdbc.update("UPDATE admins SET enabled=false WHERE handle='owner'");
    parity(context);
    assertTrue(selected(context, false, null, null, null).isEmpty());
    jdbc.update("UPDATE admins SET enabled=true WHERE handle='owner'");
    jdbc.update("DELETE FROM project_members WHERE handle='owner'");
    parity(context);
    assertTrue(selected(context, false, null, null, null).isEmpty());
  }

  @Test
  void grantAncestorsAndExpiryRemainLiveAndNeverBecomeAnOperationLease()
      throws InterruptedException {
    retained("reader", "one");
    UUID child =
        jdbc.queryForObject(
            "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,issuing_grant,provenance,expires_at) SELECT recipient.id,parent.scope_id,role.id,'READER',parent.subject_account,parent.id,'ROLE',now()+interval '3 seconds' FROM authority_grants parent JOIN authority_accounts owner ON owner.id=parent.subject_account AND owner.legacy_handle='owner' CROSS JOIN authority_accounts recipient CROSS JOIN authority_roles role WHERE parent.provenance='MEMBERSHIP' AND parent.revoked_at IS NULL AND recipient.legacy_handle='other' AND role.name='READER' RETURNING id",
            UUID.class);
    var context = context("other", InformationContext.Scope.PROJECT, "one", false);
    parity(context);
    assertFalse(selected(context, false, null, null, null).isEmpty());
    Instant expires =
        jdbc.queryForObject(
                "SELECT expires_at FROM authority_grants WHERE id=?", Timestamp.class, child)
            .toInstant();
    while (Instant.now().isBefore(expires.plusMillis(20))) Thread.sleep(10);
    parity(context);
    assertTrue(selected(context, false, null, null, null).isEmpty());
    // An independent nonexpiring descendant must also disappear when its issuing membership does.
    jdbc.update(
        "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,issuing_grant,provenance) SELECT recipient.id,parent.scope_id,role.id,'READER',parent.subject_account,parent.id,'ROLE' FROM authority_grants parent JOIN authority_accounts owner ON owner.id=parent.subject_account AND owner.legacy_handle='owner' CROSS JOIN authority_accounts recipient CROSS JOIN authority_roles role WHERE parent.provenance='MEMBERSHIP' AND parent.revoked_at IS NULL AND recipient.legacy_handle='other' AND role.name='READER'");
    assertFalse(selected(context, false, null, null, null).isEmpty());
    jdbc.update("DELETE FROM project_members WHERE handle='owner'");
    parity(context);
    assertTrue(selected(context, false, null, null, null).isEmpty());
  }

  @Test
  void dependencyCyclesMissingAudienceAndRetainedCeilingsMatchExistingChecks() {
    var a = retained("reader", "one");
    var b = retained("reader", "one");
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) VALUES(?,?),(?,?)",
        a.revision(),
        b.revision(),
        b.revision(),
        a.revision());
    var context = context("reader", InformationContext.Scope.PROJECT, "one", true);
    parity(context);
    assertEquals(2, selected(context, false, null, null, null).size());
    parity(context.within(KnowledgeReadScope.homeOnly(Home.of("one"))));
    assertTrue(
        selected(
                context.within(KnowledgeReadScope.homeOnly(Home.of("two"))),
                false,
                null,
                null,
                null)
            .isEmpty());
    parity(context.within(KnowledgeReadScope.homeOnly(Home.global())));
    audience(b, "QUARANTINED", false);
    parity(context);
    assertTrue(selected(context, false, null, null, null).isEmpty());
  }

  @Test
  void relatedParentsAndNeighboursRetainTheirDifferentSelectionAndBoundaryRules() {
    var parent = retained("reader", "one");
    var neighbour = retained("reader", null);
    UUID edge = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_relationships(id,source,target,relation,actor) VALUES(?,?,?,'cites','reader')",
        edge,
        parent.resource(),
        neighbour.resource());
    var context = context("reader", InformationContext.Scope.PROJECT, "one", false);
    assertEquals(
        legacy(context, true, parent.resource(), "cites", "outgoing"),
        selected(context, true, parent.resource(), "cites", "outgoing"));
    assertTrue(
        selected(context, true, parent.resource(), "cites", "outgoing")
            .contains(neighbour.revision()));
    var bounded = context.within(KnowledgeReadScope.homeOnly(Home.of("one")));
    assertEquals(
        legacy(bounded, true, parent.resource(), "cites", "outgoing"),
        selected(bounded, true, parent.resource(), "cites", "outgoing"));
    assertTrue(selected(bounded, true, parent.resource(), "cites", "outgoing").isEmpty());
    assertEquals(
        legacy(context, true, neighbour.resource(), "cites", "backlinks"),
        selected(context, true, neighbour.resource(), "cites", "backlinks"));
    assertTrue(selected(context, true, neighbour.resource(), "cites", "backlinks").isEmpty());
    audience(parent, "QUARANTINED", false);
    assertTrue(selected(context, true, parent.resource(), "cites", "outgoing").isEmpty());
  }

  @Test
  void liveReferenceHeadsAndPinnedRevisionsKeepTheirDifferentDependencyMeaning() {
    var parent = retained("reader", "one");
    var target = retained("reader", "one");
    UUID edge = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_relationships(id,source,target,relation,actor) VALUES(?,?,?,'cites','reader')",
        edge,
        parent.resource(),
        target.resource());
    var context = context("reader", InformationContext.Scope.PROJECT, "one", false);
    parity(context);
    assertTrue(selected(context, false, null, null, null).contains(parent.revision()));
    UUID newHead = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text,availability) VALUES(?,?,2,'New head','text/plain','new',1,'source','withdrawn')",
        newHead,
        target.resource());
    parity(context);
    assertFalse(selected(context, false, null, null, null).contains(parent.revision()));
    // Obligation history is append-only: a separately pinned parent continues consuming old text.
    var pinned = retained("reader", "one");
    UUID pinnedEdge = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_relationships(id,source,target,pinned_revision,relation,actor) VALUES(?,?,?,?,'cites','reader')",
        pinnedEdge,
        pinned.resource(),
        target.resource(),
        target.revision());
    parity(context);
    assertTrue(selected(context, false, null, null, null).contains(pinned.revision()));
  }

  @Test
  void oneRootOverTheClosureLimitFailsClosedWithoutRejectingOtherRoots() {
    var root = retained("reader", "one");
    // One owning INSERT per relation creates a star with 1025 nodes, avoiding fixture N+1 writes.
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) SELECT gen_random_uuid(),'project:one','leaf-'||n,'reader',(SELECT id FROM projects WHERE name='one'),'source' FROM generate_series(1,1024) n");
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text) SELECT gen_random_uuid(),id,1,'Leaf','text/plain','fixture',1,'source' FROM information_resources WHERE source_name LIKE 'leaf-%'");
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) SELECT ?,id FROM information_revisions WHERE id<>?",
        root.revision(),
        root.revision());
    var context = context("reader", InformationContext.Scope.PROJECT, "one", false);
    parity(context);
    var result = selected(context, false, null, null, null);
    assertEquals(1024, result.size());
    assertFalse(result.contains(root.revision()));
  }

  private long activeCalls() {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(calls),0)::bigint FROM pg_stat_xact_user_functions WHERE funcname='account_active'",
        Long.class);
  }

  private long navigationCalls() {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(calls),0)::bigint FROM pg_stat_xact_user_functions WHERE funcname='information_navigation_policy'",
        Long.class);
  }

  @Test
  void unrelatedPersonalCorporaDoNotAddDependencyPolicyWorkToASmallSelection() {
    var own = retained("reader", null);
    var context = context("reader", InformationContext.Scope.PERSONAL, null, false);
    work.inTransaction(
        () -> {
          jdbc.execute("SET LOCAL track_functions='all'");
          long before = navigationCalls();
          assertEquals(Set.of(own.revision()), selected(context, false, null, null, null));
          long small = navigationCalls() - before;
          for (int index = 0; index < 32; index++) retained("other", null);
          before = navigationCalls();
          assertEquals(Set.of(own.revision()), selected(context, false, null, null, null));
          long skewed = navigationCalls() - before;
          assertTrue(small > 0, "fixture must observe actual PostgreSQL function calls");
          assertEquals(
              small, skewed, "unrelated homes must be pruned before dependency policy facts");
          parity(context);
          return null;
        });
  }

  @Test
  void coldAuthorityWorkDoesNotScaleWithTheNumberOfSelectedRevisions() {
    for (int index = 0; index < 48; index++) retained("reader", "one");
    var context = context("reader", InformationContext.Scope.PROJECT, "one", false);
    work.inTransaction(
        () -> {
          jdbc.execute("SET LOCAL track_functions='all'");
          long start = activeCalls();
          assertEquals(48, legacy(context, false, null, null, null).size());
          long oldCalls = activeCalls() - start;
          long before = activeCalls();
          assertEquals(48, selected(context, false, null, null, null).size());
          long newCalls = activeCalls() - before;
          assertTrue(oldCalls > 0, "fixture must observe actual PostgreSQL function calls");
          assertTrue(
              newCalls < oldCalls / 4, "old account_active=" + oldCalls + ", batched=" + newCalls);
          return null;
        });
  }
}
