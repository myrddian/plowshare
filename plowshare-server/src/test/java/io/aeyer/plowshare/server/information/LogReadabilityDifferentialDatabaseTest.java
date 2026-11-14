package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.access.AuthoritySnapshot;
import io.aeyer.plowshare.server.access.JdbcAuthoritySnapshotRepository;
import io.aeyer.plowshare.server.cache.JdbcProjectionVersions;
import io.aeyer.plowshare.server.cache.ProjectionCacheProperties;
import io.aeyer.plowshare.server.cache.ProjectionCaches;
import io.aeyer.plowshare.server.cache.ProjectionChangeSignals;
import io.aeyer.plowshare.server.cache.ProjectionTelemetry;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link LogReadability} must give exactly the verdict of {@code information_log_readable(log,
 * account)} (V235), and the cached check ({@link CachedLogReadability}) must never serve any other
 * verdict.
 *
 * <p>The fixture holds log trees over all four ancestry edges: conversations three deep, an
 * orchestration whose conductor hangs under a caller conversation, a nested orchestration whose
 * conductor hangs under its parent run's conductor, orchestration ids under their conductors, a
 * caller chain through a nested conductor's child, a separate tree, a tree with no inputs and
 * phantom job logs with no rows of their own. Retained inputs cover every selection arm (personal,
 * project of every project kind with and without include_shared, the actor's own account knowledge
 * through its personal project and through a project link, Global account knowledge, global read, a
 * contained home's public document, an unknown scope spelling) and revisions whose dependency is
 * unreadable, private, owner-only or quarantined. Actors are users with and without roles, a server
 * administrator, a service owner and its token principal, a contained principal and its upper-case
 * spelling, an unknown UUID and handle, and the NULL account.
 *
 * <p>States: quarantine at a root, a middle conductor and a leaf; a membership removed; a grant
 * issued and revoked; a dependency made unreadable; a tree joined under another by giving its root
 * conductor's run a caller (an orchestration row update), then split again; a quarantine on the
 * split root; a root conversation joined by a new orchestration as its conductor; a new input and
 * child added; a contained principal revoked; an account disabled. In every state, every (account,
 * log) pair is compared: the Java rule over freshly loaded trees and knowledge facts against SQL,
 * and the cached check (persisting across states, with no notifications, so freshness rests on its
 * confirmation alone) whenever it serves.
 */
@Tag("full-db")
@Testcontainers
class LogReadabilityDifferentialDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final String GLOBAL_ACCOUNT = "00000000-0000-0000-0000-000000000004";
  private static SingleConnectionDataSource source;
  private JdbcTemplate jdbc;
  private final List<String> actors = new ArrayList<>();
  private final List<String> logs = new ArrayList<>();
  private final Map<String, UUID> revisions = new LinkedHashMap<>();
  private String personalProject;
  private String child;
  private String principal;
  private long comparisons;
  private long served;
  private int states;
  private int readableVerdicts;
  private int unreadableVerdicts;

  private record Doc(UUID resource, UUID revision) {}

  @BeforeAll
  static void migrate() {
    source =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    source.setAutoCommit(true);
    Flyway.configure().dataSource(source).load().migrate();
  }

  private static DriverManagerDataSource independent() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  // ---- Authority and knowledge fixture (as KnowledgeReadabilityDifferentialDatabaseTest). ----

  private void authority() {
    AuthorityFixtures.truncate(jdbc, "TRUNCATE admins,projects CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES('alice','fixture'),('bob','fixture'),('carol','fixture')");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES('admin','fixture',true)");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,account_kind,must_change_password) VALUES('owner','fixture','SERVICE',false),('@service/probe','fixture','SERVICE_TOKEN',false)");
    for (String handle : List.of("alice", "bob", "carol", "admin"))
      AuthorityFixtures.personalIdentity(jdbc, handle);
    personalProject = PersonalSpaces.name("alice");
    jdbc.update("INSERT INTO projects(name) VALUES('one'),('two'),('parent')");
    member("one", "alice", "MANAGER");
    member("one", "bob", "VIEWER");
    member("two", "bob", "CONTRIBUTOR");
    member("one", "owner", "MANAGER");
    member("parent", "alice", "CONTRIBUTOR");
    UUID token = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at) VALUES(?,'owner','fixture','@service/probe',?,now()+interval '1 hour')",
        token,
        token.toString());
    jdbc.update(
        "INSERT INTO service_token_scopes(token_id,project_id,role) SELECT ?,id,'CONTRIBUTOR' FROM projects WHERE name='one'",
        token);
    child = contained(scopeOf("parent"));
    jdbc.update("INSERT INTO contained_principals(scope_id) VALUES(?)", scopeOf(child));
    principal =
        jdbc.queryForObject(
                "SELECT id FROM contained_principals WHERE scope_id=?", UUID.class, scopeOf(child))
            .toString();
    actors.clear();
    actors.addAll(
        List.of(
            "alice",
            "bob",
            "carol",
            "admin",
            "owner",
            "@service/probe",
            principal,
            principal.toUpperCase(Locale.ROOT),
            UUID.randomUUID().toString(),
            "missing"));
    actors.add(null);
  }

  private void member(String project, String handle, String role) {
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,?,? FROM projects WHERE name=?",
        handle,
        role,
        project);
  }

  private UUID scopeOf(String project) {
    return jdbc.queryForObject(
        "SELECT s.id FROM authority_scopes s JOIN projects p ON p.id=s.project_id WHERE p.name=?",
        UUID.class,
        project);
  }

  private String contained(UUID container) {
    String name = "contained:" + UUID.randomUUID();
    jdbc.update("INSERT INTO projects(name) VALUES(?)", name);
    jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) SELECT ?,'CONTAINED',id,? FROM projects WHERE name=?",
        UUID.randomUUID(),
        container,
        name);
    return name;
  }

  private Doc resource(String namespace, String project, String owner) {
    UUID resource = UUID.randomUUID();
    UUID revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) VALUES(?,?,?,?,(SELECT id FROM projects WHERE name=?),'source')",
        resource,
        namespace,
        resource + ".txt",
        owner,
        project);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text,allowance_total) VALUES(?,?,1,'Fixture','text/plain','fixture',1,'body',10)",
        revision,
        resource);
    return new Doc(resource, revision);
  }

  private Doc project(String label, String project, String owner) {
    var doc = resource("project:" + project, project, owner);
    revisions.put(label, doc.revision());
    return doc;
  }

  private void policy(UUID resource, String audience, boolean privateOnly, String principal) {
    jdbc.update(
        "UPDATE information_resource_policies SET version=version+1,audience=?,private_only=?,privacy_principal=(SELECT id FROM authority_accounts WHERE legacy_handle=?),reason='AUDIENCE',actor=(SELECT id FROM authority_accounts WHERE legacy_handle='alice') WHERE resource_id=?",
        audience,
        privateOnly,
        principal,
        resource);
  }

  private void dependency(UUID derived, UUID input) {
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) VALUES(?,?)",
        derived,
        input);
  }

  private void knowledge() {
    var plain = project("one/plain", "one", "alice");
    var published = project("one/published", "one", "bob");
    policy(published.resource(), "GLOBAL_READ", false, "bob");
    project("two/plain", "two", "bob");
    var aliceKnowledge = resource("account:alice", null, "alice");
    revisions.put("account/alice", aliceKnowledge.revision());
    project("personal/alice", personalProject, "alice");
    revisions.put("global", resource("account:" + GLOBAL_ACCOUNT, null, "GLOBAL").revision());
    project("child/plain", child, "alice");
    var childPublic = project("child/published", child, "alice");
    policy(childPublic.resource(), "GLOBAL_READ", false, "alice");
    var privateOwn = project("one/private-principal", "one", "alice");
    policy(privateOwn.resource(), "HOME", true, "alice");
    var ownerOnly = project("one/owner-only", "one", "alice");
    jdbc.update(
        "INSERT INTO information_navigation_policies(policy_key,version,settings,actor) VALUES(?,1,'{\"defaults\":{\"ownerOnly\":true}}'::jsonb,'alice')",
        "document:" + ownerOnly.resource());
    var quarantined = project("one/quarantined", "one", "alice");
    policy(quarantined.resource(), "QUARANTINED", false, "alice");
    var tainted = project("one/foreign-input", "one", "alice");
    dependency(tainted.revision(), revisions.get("two/plain"));
    var publicOnPrivate = project("one/public-on-private", "one", "bob");
    policy(publicOnPrivate.resource(), "GLOBAL_READ", false, "bob");
    dependency(publicOnPrivate.revision(), plain.revision());
    var later = project("one/later-tainted", "one", "alice");
    revisions.put("one/later-tainted", later.revision());
    jdbc.update(
        "INSERT INTO information_links(revision_id,project_id,actor_handle) SELECT ?,id,'alice' FROM projects WHERE name='one'",
        aliceKnowledge.revision());
  }

  // ---- Log trees over the four edges. ----

  private void conversation(String id, String parent) {
    logs.add(id);
    if (parent == null)
      jdbc.update(
          "INSERT INTO conversations(id,created_at,origin,budget_total,budget_spent,owner_handle) VALUES(?,now(),'turn',100,0,'alice')",
          id);
    else
      jdbc.update(
          "INSERT INTO conversations(id,created_at,origin,agent,parent_id,lifecycle,owner_handle) VALUES(?,now(),'delegation','agent',?,NULL,'alice')",
          id,
          parent);
  }

  /** An orchestration run; its conductor conversation must exist. */
  private void orchestration(String id, String conductor, String caller, String parent) {
    logs.add(id);
    jdbc.update(
        "INSERT INTO orchestrations(id,definition_name,tier,definition_hash,definition_source,"
            + "definition_origin,stages,max_returns,conductor_conversation,caller_agent,state,depth,"
            + "created_at,caller_conversation,parent) VALUES(?,'fixture','SHIPPED','hash','source','origin',"
            + "'[]',1,?,'agent','running',?,now(),?,?)",
        id,
        conductor,
        parent == null ? 0 : 1,
        caller,
        parent);
  }

  private void input(String job, String revision, String scope, String project, boolean shared) {
    jdbc.update(
        "INSERT INTO information_job_inputs(job_id,owner_handle,revision_id,scope,project_name,include_shared) VALUES(?,'alice',?,?,?,?)",
        job,
        revisions.get(revision),
        scope,
        project,
        shared);
  }

  private void quarantine(String log, boolean on) {
    if (on)
      jdbc.update(
          "INSERT INTO information_quarantined_payloads(payload_id,reason) VALUES(?,'fixture')",
          log);
    else jdbc.update("DELETE FROM information_quarantined_payloads WHERE payload_id=?", log);
  }

  /** One selection per phantom job log, so every arm is visible on its own. */
  private void phantomInputs() {
    var selections = new ArrayList<Object[]>();
    selections.add(new Object[] {"account/alice", "personal", null, false});
    selections.add(new Object[] {"one/published", "personal", null, true});
    selections.add(new Object[] {"one/plain", "personal", null, false});
    selections.add(new Object[] {"one/plain", "project", "one", false});
    selections.add(new Object[] {"one/plain", "project", "one", true});
    selections.add(new Object[] {"two/plain", "project", "two", false});
    selections.add(new Object[] {"child/plain", "project", child, false});
    selections.add(new Object[] {"child/published", "global_read", null, false});
    selections.add(new Object[] {"child/published", "project", "one", true});
    selections.add(new Object[] {"personal/alice", "project", personalProject, false});
    selections.add(new Object[] {"account/alice", "project", personalProject, false});
    selections.add(new Object[] {"account/alice", "project", "one", false});
    selections.add(new Object[] {"global", "account", null, false});
    selections.add(new Object[] {"global", "account", null, true});
    selections.add(new Object[] {"one/published", "global_read", null, false});
    selections.add(new Object[] {"one/published", "global_read", "one", false});
    selections.add(new Object[] {"one/public-on-private", "global_read", null, false});
    selections.add(new Object[] {"one/foreign-input", "project", "one", false});
    selections.add(new Object[] {"one/private-principal", "project", "one", false});
    selections.add(new Object[] {"one/owner-only", "project", "one", false});
    selections.add(new Object[] {"one/quarantined", "project", "one", false});
    selections.add(new Object[] {"one/later-tainted", "project", "one", false});
    selections.add(new Object[] {"one/plain", "PROJECT", "one", false});
    for (int index = 0; index < selections.size(); index++) {
      var selection = selections.get(index);
      String job = "job-" + index;
      logs.add(job);
      input(
          job,
          (String) selection[0],
          (String) selection[1],
          (String) selection[2],
          (Boolean) selection[3]);
    }
  }

  /**
   * c1 → c2 → c3 (parent_id); c2 calls run o1 with conductor k1 (caller edge, and o1 under k1);
   * nested run o2 under o1 with conductor k2 (parent-conductor edge, and o2 under k2); c4 under k2;
   * c4 calls run o3 with conductor k3. Tree d1 → d2 with run o5 under conductor d1 (no caller yet),
   * and lone trees e1 and e2.
   */
  private void trees() {
    conversation("c1", null);
    conversation("c2", "c1");
    conversation("c3", "c2");
    conversation("k1", null);
    orchestration("o1", "k1", "c2", null);
    conversation("k2", null);
    orchestration("o2", "k2", null, "o1");
    conversation("c4", "k2");
    conversation("k3", null);
    orchestration("o3", "k3", "c4", null);
    conversation("d1", null);
    conversation("d2", "d1");
    orchestration("o5", "d1", null, null);
    conversation("e1", null);
    conversation("e2", null);
    input("c2", "one/plain", "project", "one", true);
    input("o1", "one/published", "global_read", null, false);
    input("k2", "account/alice", "personal", null, false);
    input("c4", "two/plain", "project", "two", false);
    input("d1", "one/plain", "project", "one", false);
    input("d2", "one/later-tainted", "project", "one", false);
    logs.add("unknown-log");
  }

  // ---- Comparison. ----

  private Boolean sql(String log, String account) {
    return jdbc.queryForObject(
        "SELECT information_log_readable(?,?::text)", Boolean.class, log, account);
  }

  /** The Java rule over trees and facts loaded fresh for this state. */
  private Optional<Boolean> java(
      String account,
      String log,
      JdbcLogTreeFactRepository trees,
      Map<String, LogTreeFact> loaded,
      AuthoritySnapshot snapshot,
      KnowledgeReadability.Facts facts) {
    var roots = trees.roots(log).orElseThrow(() -> new AssertionError("Unaddressable " + log));
    var held = new ArrayList<LogTreeFact.Tree>();
    for (String root : roots)
      held.add((LogTreeFact.Tree) loaded.computeIfAbsent(root, trees::tree));
    var retained =
        account == null ? null : KnowledgeReadability.retainedInputs(account, snapshot, facts);
    var outcome =
        LogReadability.evaluate(
            account,
            log,
            held,
            input -> {
              if (retained == null) throw new AssertionError("A NULL account consults no input");
              return retained.readable(input);
            });
    return switch (outcome) {
      case LogReadability.Outcome.Verdict verdict -> Optional.of(verdict.readable());
      default -> throw new AssertionError(log + " for " + account + ": " + outcome);
    };
  }

  /** Facts for one fixture state, each loaded once. */
  private static final class StateFacts implements KnowledgeReadability.Facts {
    final JdbcKnowledgeFactRepository repository;
    final Map<UUID, KnowledgeFact> revisions = new HashMap<>();
    final Map<UUID, KnowledgeFact> resources = new HashMap<>();

    StateFacts(JdbcKnowledgeFactRepository repository) {
      this.repository = repository;
    }

    public Optional<KnowledgeFact> revision(UUID id) {
      return Optional.of(revisions.computeIfAbsent(id, repository::revision));
    }

    public Optional<KnowledgeFact> resource(UUID id) {
      return Optional.of(resources.computeIfAbsent(id, repository::resource));
    }
  }

  private void parity(
      String state,
      JdbcAuthoritySnapshotRepository authority,
      CachedLogReadability cached,
      AtomicReference<AuthoritySnapshot> current) {
    states++;
    var snapshot = new AuthoritySnapshot(authority.load().orElseThrow());
    current.set(snapshot);
    var trees = new JdbcLogTreeFactRepository(jdbc);
    var loaded = new HashMap<String, LogTreeFact>();
    var facts = new StateFacts(new JdbcKnowledgeFactRepository(jdbc));
    for (String account : actors)
      for (String log : logs) {
        boolean expected = sql(log, account);
        String label = state + ": " + log + " for " + account;
        assertEquals(
            Optional.of(expected), java(account, log, trees, loaded, snapshot, facts), label);
        comparisons++;
        if (expected) readableVerdicts++;
        else unreadableVerdicts++;
        if (account == null) continue;
        // Twice: a first check after a write may find a stale tree and decline; the second
        // reloads it. A served answer must equal SQL either way.
        for (int attempt = 0; attempt < 2; attempt++) {
          var answer = cached.readable(account, log);
          if (answer.isPresent()) {
            assertEquals(expected, answer.get(), label + " (cached, attempt " + attempt + ")");
            served++;
          }
        }
      }
  }

  private static ProjectionCacheProperties properties() {
    return new ProjectionCacheProperties(
        256,
        33_554_432,
        8,
        67_108_864,
        256,
        64,
        8192,
        8_388_608,
        Duration.ofSeconds(2),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        128,
        24,
        4);
  }

  @Test
  void everyAccountAndLogMatchesSqlThroughEveryFixtureState() {
    jdbc = new JdbcTemplate(source);
    authority();
    knowledge();
    trees();
    phantomInputs();
    var authority = new JdbcAuthoritySnapshotRepository(jdbc, 10_000);
    var versions = new JdbcProjectionVersions(jdbc, independent(), 128);
    var current = new AtomicReference<AuthoritySnapshot>();
    try (var signals = new SilentSignals();
        var caches =
            new ProjectionCaches(
                versions, versions, signals, properties(), null, ProjectionTelemetry.NONE)) {
      var facts =
          new KnowledgeFactSource(
              caches.createItemScoped(
                  "knowledge-facts-v1",
                  Set.of(ProjectionVersions.Domain.READABILITY),
                  ProjectionCaches.Freshness.VERSIONED),
              new JdbcKnowledgeFactRepository(jdbc),
              ReadAdmission.direct(),
              256);
      var cached =
          new CachedLogReadability(
              caches.createItemScoped(
                  "log-tree-facts-v1",
                  Set.of(ProjectionVersions.Domain.READABILITY),
                  ProjectionCaches.Freshness.VERSIONED),
              new JdbcLogTreeFactRepository(jdbc),
              facts,
              versions,
              () -> Optional.ofNullable(current.get()),
              ReadAdmission.direct(),
              4096);

      parity("initial", authority, cached, current);
      quarantine("c1", true);
      parity("root quarantined", authority, cached, current);
      quarantine("c1", false);
      quarantine("k1", true);
      parity("middle conductor quarantined", authority, cached, current);
      quarantine("k1", false);
      quarantine("c4", true);
      parity("leaf quarantined", authority, cached, current);
      quarantine("c4", false);
      parity("quarantine lifted", authority, cached, current);
      jdbc.update(
          "DELETE FROM project_members WHERE handle='bob' AND project_id=(SELECT id FROM projects WHERE name='one')");
      parity("bob's membership of one removed", authority, cached, current);
      member("two", "alice", "MANAGER");
      jdbc.update(
          "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,issuing_grant,provenance,expires_at)"
              + " SELECT recipient.id,parent.scope_id,role.id,'READER',parent.subject_account,parent.id,'ROLE',?"
              + " FROM authority_grants parent JOIN authority_accounts issuer ON issuer.id=parent.subject_account AND issuer.legacy_handle='alice'"
              + " JOIN authority_scopes scope ON scope.id=parent.scope_id JOIN projects p ON p.id=scope.project_id"
              + " CROSS JOIN authority_accounts recipient CROSS JOIN authority_roles role"
              + " WHERE parent.provenance='MEMBERSHIP' AND parent.revoked_at IS NULL AND p.name='two'"
              + " AND recipient.legacy_handle='carol' AND role.name='READER'",
          Timestamp.from(Instant.now().plusSeconds(3600)));
      parity("alice joins two, carol granted READER on two", authority, cached, current);
      jdbc.update(
          "UPDATE authority_grants SET revoked_at=now(),version=version+1 WHERE provenance='ROLE'");
      parity("carol's grant revoked", authority, cached, current);
      jdbc.update(
          "DELETE FROM project_members WHERE handle='alice' AND project_id=(SELECT id FROM projects WHERE name='two')");
      dependency(revisions.get("one/later-tainted"), revisions.get("two/plain"));
      parity("an input's dependency made unreadable", authority, cached, current);
      jdbc.update("UPDATE orchestrations SET caller_conversation='c3' WHERE id='o5'");
      parity("tree d joined under c3 by giving its root a caller", authority, cached, current);
      jdbc.update("UPDATE orchestrations SET caller_conversation=NULL WHERE id='o5'");
      parity("tree d split off again", authority, cached, current);
      quarantine("d1", true);
      parity("split tree's root quarantined", authority, cached, current);
      quarantine("d1", false);
      input("e2", "two/plain", "project", "two", false);
      orchestration("o4", "e1", "e2", null);
      parity("root e1 joined under e2 by a new orchestration", authority, cached, current);
      conversation("e3", "e1");
      input("e3", "one/plain", "project", "one", true);
      parity("a new child and input below the joined root", authority, cached, current);
      jdbc.update("DELETE FROM contained_principals");
      jdbc.update("UPDATE service_tokens SET revoked_at=now()");
      parity("principal and token revoked", authority, cached, current);
      jdbc.update("UPDATE admins SET enabled=false WHERE handle='alice'");
      parity("alice disabled", authority, cached, current);
    }

    assertEquals(16, states);
    assertTrue(readableVerdicts > 0 && unreadableVerdicts > 0, "Both verdicts occur");
    assertTrue(served > comparisons / 2, "The cached check serves most pairs: " + served);
    System.out.printf(
        "Log readability parity: %d states, %d accounts, %d logs, %d Java/SQL comparisons"
            + " (%d readable, %d unreadable), %d cached answers equal to SQL%n",
        states,
        actors.size(),
        logs.size(),
        comparisons,
        readableVerdicts,
        unreadableVerdicts,
        served);
  }

  /** A ready listener that delivers no hints: freshness rests on the confirmation alone. */
  private static final class SilentSignals implements ProjectionChangeSignals {
    private final List<Observer> observers = new CopyOnWriteArrayList<>();

    public Subscription subscribe(Set<ProjectionVersions.Domain> domains, Observer observer) {
      observers.add(observer);
      observer.stateChanged(new State(1, true));
      return () -> observers.remove(observer);
    }

    public void start() {}

    public boolean healthy() {
      return true;
    }

    public OptionalLong deliveredThrough() {
      return OptionalLong.empty();
    }

    public void close() {}
  }
}
