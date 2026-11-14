package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthoritySnapshot;
import io.aeyer.plowshare.server.access.JdbcAuthoritySnapshotRepository;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link KnowledgeReadability} must give exactly the verdict of the targeted SQL check: {@code
 * knowledge_readable_targets} joined to {@code information_revision_allowances} with the typed
 * content filter of {@link InformationSql#content}.
 *
 * <p>Fixtures are generated, not hand-picked: every actor kind (ordinary users with and without
 * roles, a server administrator, a service owner and its token principal, a contained principal,
 * its upper-case spelling, an unknown UUID and an unknown handle) is crossed with every selection
 * arm (personal with and without include-global, project for every project kind with and without
 * include-global, global read and Global account knowledge), every read ceiling (none, a project, a
 * contained home, a personal project, the Global home, and global-read ceilings), and the typed
 * view on and off, against every revision of the fixture, after every mutation of the fixture. The
 * revisions cover every home kind, public and private policies with and without a privacy
 * principal, owner-only navigation, quarantine, withdrawal, exclusion, explicit-only processing,
 * typed content, absent policies and homes, cross-tenant inputs, cycles, pinned and unpinned
 * references to a moving navigation head, links into a project and public dependency chains.
 * Mutations remove a membership, issue and revoke a delegated grant, publish and restrict policies,
 * move a navigation head, impose and lift a platform owner-only policy, revoke a contained
 * principal and a service token, and disable an account. A second test builds dependency closures
 * of exactly 1,024 and 1,025 nodes.
 *
 * <p>The SQL oracle evaluates up to 100 targets in one call; the first state also compares it with
 * the repository's own single-target statement, so the batching cannot hide a difference.
 */
@Tag("full-db")
@Testcontainers
class KnowledgeReadabilityDifferentialDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final String GLOBAL_ACCOUNT = "00000000-0000-0000-0000-000000000004";
  private static SingleConnectionDataSource source;
  private JdbcTemplate jdbc;
  private JdbcAuthoritySnapshotRepository authority;
  private JdbcKnowledgeFactRepository facts;
  private final List<String> actors = new ArrayList<>();
  private final List<String> projects = new ArrayList<>();
  private final Map<String, UUID> revisions = new LinkedHashMap<>();
  private final Map<String, Integer> admittedBySelection = new TreeMap<>();
  private long comparisons;
  private int states;
  private String personalProject;
  private String child;
  private String grandchild;
  private String principal;

  private record Doc(UUID resource, UUID revision) {}

  @BeforeAll
  static void migrate() {
    source =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    source.setAutoCommit(true);
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(source);
    AuthorityFixtures.truncate(jdbc, "TRUNCATE admins,projects CASCADE");
    authority = new JdbcAuthoritySnapshotRepository(jdbc, 10_000);
    facts = new JdbcKnowledgeFactRepository(jdbc);
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
    jdbc.update(
        "INSERT INTO service_token_scopes(token_id,project_id,role) SELECT ?,id,'VIEWER' FROM projects WHERE name='two'",
        token);
    child = contained(scopeOf("parent"));
    grandchild = contained(scopeOf(child));
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
    projects.clear();
    projects.addAll(List.of("one", "two", "parent", child, personalProject, "nowhere"));
    revisions.clear();
    admittedBySelection.clear();
    comparisons = 0;
    states = 0;
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

  /** A contained scope over a fresh contained project; returns the project's name. */
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
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind) VALUES(?,?,?,?,(SELECT id FROM projects WHERE name=?),'source')",
        resource,
        namespace,
        resource + ".txt",
        owner,
        project);
    return new Doc(resource, revision(resource, 1, "automatic"));
  }

  private UUID revision(UUID resource, int ordinal, String processing) {
    UUID revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text,allowance_total,processing_policy) VALUES(?,?,?,'Fixture','text/plain','fixture',1,'body',10,?)",
        revision,
        resource,
        ordinal,
        processing);
    return revision;
  }

  private Doc project(String label, String project, String owner) {
    var doc = resource("project:" + project, project, owner);
    revisions.put(label, doc.revision());
    return doc;
  }

  private Doc account(String label, String handle) {
    var doc = resource("account:" + handle, null, handle);
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

  private void input(UUID derived, UUID input) {
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) VALUES(?,?)",
        derived,
        input);
  }

  private void reference(Doc source, UUID target, UUID pinned) {
    jdbc.update(
        "INSERT INTO information_relationships(id,source,target,pinned_revision,relation,actor) VALUES(?,?,?,?,'cites','alice')",
        UUID.randomUUID(),
        source.resource(),
        target,
        pinned);
  }

  private static String hash(char digit) {
    return String.valueOf(digit).repeat(64);
  }

  /** Every revision kind the rule distinguishes; see the class comment. */
  private Doc fixture() {
    var plain = project("one/plain", "one", "alice");
    var published = project("one/published", "one", "bob");
    policy(published.resource(), "GLOBAL_READ", false, "bob");
    var foreign = project("two/plain", "two", "bob");
    var aliceKnowledge = account("account/alice", "alice");
    account("account/bob", "bob");
    project("personal/alice", personalProject, "alice");
    var global = resource("account:" + GLOBAL_ACCOUNT, null, "GLOBAL");
    revisions.put("global/initial", global.revision());
    project("parent/plain", "parent", "alice");
    project("child/plain", child, "alice");
    project("grandchild/plain", grandchild, "alice");
    var privateOwn = project("one/private-principal", "one", "alice");
    policy(privateOwn.resource(), "HOME", true, "alice");
    var privateNobody = project("one/private-nobody", "one", "alice");
    policy(privateNobody.resource(), "HOME", true, null);
    var ownerOnly = project("one/owner-only", "one", "alice");
    jdbc.update(
        "INSERT INTO information_navigation_policies(policy_key,version,settings,actor) VALUES(?,1,'{\"defaults\":{\"ownerOnly\":true}}'::jsonb,'alice')",
        "document:" + ownerOnly.resource());
    var quarantined = project("one/quarantined", "one", "alice");
    policy(quarantined.resource(), "QUARANTINED", false, "alice");
    var withdrawn = project("one/withdrawn", "one", "alice");
    jdbc.update(
        "UPDATE information_revisions SET availability='withdrawn' WHERE id=?",
        withdrawn.revision());
    var excluded = project("one/excluded", "one", "alice");
    jdbc.update("UPDATE information_revisions SET excluded=true WHERE id=?", excluded.revision());
    var explicit = resource("project:one", "one", "alice");
    jdbc.update("DELETE FROM information_revisions WHERE id=?", explicit.revision());
    revisions.put("one/explicit-only", revision(explicit.resource(), 1, "explicit_only"));
    var typed = project("one/typed", "one", "alice");
    typedContent(typed.revision());
    var tainted = project("one/foreign-input", "one", "alice");
    input(tainted.revision(), foreign.revision());
    var cycleA = project("one/cycle-a", "one", "alice");
    var cycleB = project("one/cycle-b", "one", "alice");
    input(cycleA.revision(), cycleB.revision());
    input(cycleB.revision(), cycleA.revision());
    var pinnedForeign = project("one/pins-foreign", "one", "alice");
    reference(pinnedForeign, foreign.resource(), foreign.revision());
    var target = project("one/head-target", "one", "alice");
    var followsHead = project("one/follows-head", "one", "alice");
    reference(followsHead, target.resource(), null);
    var pinsHead = project("one/pins-old-head", "one", "alice");
    reference(pinsHead, target.resource(), target.revision());
    jdbc.update(
        "INSERT INTO information_links(revision_id,project_id,actor_handle) SELECT ?,id,'alice' FROM projects WHERE name='one'",
        aliceKnowledge.revision());
    var publicChain = project("one/public-chain", "one", "bob");
    policy(publicChain.resource(), "GLOBAL_READ", false, "bob");
    input(publicChain.revision(), published.revision());
    var publicOnPrivate = project("one/public-on-private", "one", "bob");
    policy(publicOnPrivate.resource(), "GLOBAL_READ", false, "bob");
    input(publicOnPrivate.revision(), plain.revision());
    var publicReference = project("one/public-references-private", "one", "bob");
    policy(publicReference.resource(), "GLOBAL_READ", false, "bob");
    reference(publicReference, plain.resource(), null);
    var childPublic = project("child/published", child, "alice");
    policy(childPublic.resource(), "GLOBAL_READ", false, "alice");
    var homeless = resource("legacy", null, "alice");
    revisions.put("homeless", homeless.revision());
    jdbc.execute(
        "ALTER TABLE information_resources DISABLE TRIGGER information_initial_root_policy");
    try {
      revisions.put("one/no-policy", resource("project:one", "one", "alice").revision());
    } finally {
      jdbc.execute(
          "ALTER TABLE information_resources ENABLE TRIGGER information_initial_root_policy");
    }
    revisions.put("absent", UUID.randomUUID());
    return target;
  }

  private void typedContent(UUID revision) {
    UUID type = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_content_types(id,namespace,slug,label,description,created_by) VALUES(?,'project:one','memo','Memo','','alice')",
        type);
    jdbc.update(
        "INSERT INTO information_content_type_versions(id,type_id,ordinal,schema_definition,presentation,profile,definition_hash,state,created_by) VALUES(?,?,1,'{\"type\":\"object\"}'::jsonb,'{}'::jsonb,'plowshare-document-metadata/1',?,'active','alice')",
        version,
        type,
        hash('a'));
    jdbc.update(
        "INSERT INTO information_revision_content(revision_id,type_version,profile,definition_hash,metadata_hash,metadata) VALUES(?,?,'plowshare-document-metadata/1',?,?,'{}'::jsonb)",
        revision,
        version,
        hash('a'),
        hash('b'));
  }

  /** Every selection arm, including project selections of every project kind. */
  private List<InformationContext.Selection> selections() {
    var selections = new ArrayList<InformationContext.Selection>();
    selections.add(InformationContext.Selection.personal());
    selections.add(
        new InformationContext.Selection(InformationContext.Scope.PERSONAL, null, false));
    for (String project : projects) {
      selections.add(InformationContext.Selection.project(project));
      selections.add(
          new InformationContext.Selection(InformationContext.Scope.PROJECT, project, false));
    }
    selections.add(InformationContext.Selection.globalRead());
    selections.add(InformationContext.Selection.account(UUID.fromString(GLOBAL_ACCOUNT)));
    return selections;
  }

  /** No ceiling, home-only ceilings of each home kind, and ceilings that admit global reads. */
  private List<KnowledgeReadScope> ceilings() {
    var ceilings = new ArrayList<KnowledgeReadScope>();
    ceilings.add(null);
    ceilings.add(KnowledgeReadScope.homeOnly(Home.of("one")));
    ceilings.add(KnowledgeReadScope.homeOnly(Home.of(child)));
    ceilings.add(KnowledgeReadScope.homeOnly(Home.of(personalProject)));
    ceilings.add(KnowledgeReadScope.homeOnly(Home.global()));
    ceilings.add(new KnowledgeReadScope(Home.of("parent"), true));
    ceilings.add(new KnowledgeReadScope(Home.global(), true));
    return ceilings;
  }

  private List<InformationContext> contexts(
      List<String> actors,
      List<InformationContext.Selection> selections,
      List<KnowledgeReadScope> ceilings) {
    var contexts = new ArrayList<InformationContext>();
    for (String actor : actors)
      for (var selection : selections)
        for (var ceiling : ceilings) {
          var context = new InformationContext(actor, selection);
          contexts.add(ceiling == null ? context : context.within(ceiling));
        }
    return contexts;
  }

  /**
   * The repository's targeted statement over up to 100 targets in one call, for both values of the
   * typed view at once: each admitted revision maps to whether {@link InformationSql#content}
   * admits it to a legacy (untyped) reader. The typed view's filter is {@code true}.
   */
  private Map<UUID, Boolean> sql(InformationContext context, List<UUID> targets) {
    var admitted = new HashMap<UUID, Boolean>();
    var legacy = InformationSql.content(context.withView(false), "r");
    for (int from = 0; from < targets.size(); from += 100) {
      var chunk = targets.subList(from, Math.min(targets.size(), from + 100));
      var boundary = context.readBoundary();
      var args = new ArrayList<Object>();
      args.add(context.account());
      args.add(context.selection().scope().name().toLowerCase(Locale.ROOT));
      args.add(context.selection().project());
      args.add(context.selection().includeGlobal());
      args.add(boundary == null ? null : boundary.home().project());
      args.add(boundary == null ? null : boundary.globalRead());
      args.add("{" + String.join(",", chunk.stream().map(UUID::toString).toList()) + "}");
      args.addAll(legacy.arguments());
      jdbc.query(
          "WITH admitted AS MATERIALIZED (SELECT revision FROM knowledge_readable_targets(?,?,?,?,?,?,?::uuid[],NULL,false,NULL,NULL,NULL)) "
              + "SELECT r.id,"
              + legacy.sql()
              + " AS legacy FROM admitted JOIN information_revision_allowances r ON r.id=admitted.revision"
              + " WHERE r.availability='active'",
          (org.springframework.jdbc.core.RowCallbackHandler)
              row -> admitted.put(row.getObject("id", UUID.class), row.getBoolean("legacy")),
          args.toArray());
    }
    return admitted;
  }

  /** Whether the SQL answer above admits {@code revision} under {@code typed}. */
  private static boolean admits(Map<UUID, Boolean> admitted, UUID revision, boolean typed) {
    var legacy = admitted.get(revision);
    return legacy != null && (typed || legacy);
  }

  /** Facts for one fixture state, each loaded once from the repository under test. */
  private final class StateFacts implements KnowledgeReadability.Facts {
    final Map<UUID, KnowledgeFact> revisions = new HashMap<>();
    final Map<UUID, KnowledgeFact> resources = new HashMap<>();

    public Optional<KnowledgeFact> revision(UUID id) {
      return Optional.of(revisions.computeIfAbsent(id, facts::revision));
    }

    public Optional<KnowledgeFact> resource(UUID id) {
      return Optional.of(resources.computeIfAbsent(id, facts::resource));
    }
  }

  /** The authority half: knowledge_read_authority and contained_knowledge_actor per actor. */
  private void authorityParity(String state, AuthoritySnapshot snapshot) {
    for (String actor : actors) {
      var rows = jdbc.queryForList("SELECT * FROM knowledge_read_authority(?)", actor);
      var scopes = new HashSet<UUID>();
      for (var row : rows) if (row.get("scope_id") != null) scopes.add((UUID) row.get("scope_id"));
      var reader = snapshot.knowledgeReader(actor);
      String label = state + ": " + actor;
      assertEquals(
          Optional.ofNullable((UUID) rows.getFirst().get("actor_id")),
          reader.actorId(),
          label + " actor_id");
      assertEquals(
          rows.getFirst().get("general_member"), reader.generalMember(), label + " member");
      assertEquals(scopes, reader.readableScopes(), label + " readable scopes");
      assertEquals(
          jdbc.queryForObject("SELECT contained_knowledge_actor(?)", Boolean.class, actor),
          reader.contained(),
          label + " contained");
    }
  }

  /** Java against SQL for every context and revision of this state. */
  private void parity(String state, List<InformationContext> contexts) {
    states++;
    var snapshot = new AuthoritySnapshot(authority.load().orElseThrow());
    authorityParity(state, snapshot);
    var stateFacts = new StateFacts();
    var targets = List.copyOf(revisions.values());
    long started = System.nanoTime();
    for (var context : contexts) {
      var admitted = sql(context, targets);
      for (boolean typed : List.of(false, true)) {
        var viewed = context.withView(typed);
        for (var entry : revisions.entrySet()) {
          boolean expected = admits(admitted, entry.getValue(), typed);
          var actual =
              KnowledgeReadability.readable(
                  KnowledgeReadability.Request.of(viewed, entry.getValue()), snapshot, stateFacts);
          assertEquals(
              Optional.of(expected),
              actual,
              () ->
                  state
                      + ": "
                      + entry.getKey()
                      + " for "
                      + viewed.account()
                      + " "
                      + viewed.selection()
                      + " ceiling "
                      + viewed.readBoundary()
                      + " typed "
                      + typed);
          comparisons++;
          if (expected)
            admittedBySelection.merge(viewed.selection().scope().name(), 1, Integer::sum);
        }
      }
    }
    System.out.printf(
        "%s: %d contexts in %d ms%n",
        state, 2 * contexts.size(), (System.nanoTime() - started) / 1_000_000);
  }

  @Test
  void everyActorSelectionCeilingAndRevisionMatchesSqlThroughEveryFixtureState() {
    var target = fixture();
    var contexts = contexts(actors, selections(), ceilings());

    // The batched oracle is the repository's own statement, checked once target by target.
    var repository =
        new JdbcInformationCatalogueRepository(
            jdbc, Clock.systemUTC(), mock(InformationEvents.class));
    var targets = List.copyOf(revisions.values());
    var sampled =
        contexts(
            List.of("alice", principal),
            List.of(
                InformationContext.Selection.personal(),
                InformationContext.Selection.project("one"),
                InformationContext.Selection.project(child),
                InformationContext.Selection.globalRead()),
            Arrays.asList(
                null,
                KnowledgeReadScope.homeOnly(Home.of("one")),
                new KnowledgeReadScope(Home.global(), true)));
    for (var context : sampled) {
      var admitted = sql(context, targets);
      for (boolean typed : List.of(false, true))
        for (UUID revision : targets)
          assertEquals(
              admits(admitted, revision, typed),
              repository.readable(context.withView(typed), revision));
    }

    parity("initial", contexts);
    jdbc.update(
        "DELETE FROM project_members WHERE handle='bob' AND project_id=(SELECT id FROM projects WHERE name='one')");
    parity("bob's membership of one removed", contexts);
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
    parity("carol granted READER on two", contexts);
    jdbc.update(
        "UPDATE authority_grants SET revoked_at=now(),version=version+1 WHERE provenance='ROLE'");
    parity("carol's grant revoked", contexts);
    policy(resourceOf("one/plain"), "GLOBAL_READ", false, "alice");
    policy(resourceOf("one/private-principal"), "HOME", false, "alice");
    policy(resourceOf("one/private-nobody"), "HOME", true, "bob");
    policy(resourceOf("global/initial"), "HOME", false, null);
    parity("policies published and restricted", contexts);
    jdbc.update(
        "UPDATE information_revisions SET availability='withdrawn' WHERE id=?",
        revisions.get("one/published"));
    revisions.put("one/head-target@2", revision(target.resource(), 2, "automatic"));
    parity("published input withdrawn and a new head", contexts);
    jdbc.update(
        "UPDATE information_revisions SET availability='withdrawn' WHERE id=?",
        revisions.get("one/head-target@2"));
    parity("new head withdrawn", contexts);
    jdbc.update(
        "INSERT INTO information_navigation_policies(policy_key,version,settings,actor) VALUES('platform',1,'{\"defaults\":{\"ownerOnly\":false,\"referenceHidden\":true,\"propagate\":true},\"mandatory\":{\"ownerOnly\":true,\"referenceHidden\":false,\"propagate\":false},\"allowOverrides\":true}'::jsonb,'alice')");
    parity("platform owner-only", contexts);
    jdbc.update(
        "UPDATE information_navigation_policies SET version=2,settings='{\"defaults\":{\"ownerOnly\":false,\"referenceHidden\":true,\"propagate\":true},\"mandatory\":{\"ownerOnly\":false,\"referenceHidden\":false,\"propagate\":false},\"allowOverrides\":false}'::jsonb WHERE policy_key='platform'");
    parity("platform overrides closed", contexts);
    jdbc.update("DELETE FROM contained_principals");
    jdbc.update("UPDATE service_tokens SET revoked_at=now()");
    parity("principal and token revoked", contexts);
    jdbc.update("UPDATE admins SET enabled=false WHERE handle='alice'");
    parity("alice disabled", contexts);

    assertEquals(11, states);
    for (var scope : InformationContext.Scope.values())
      assertTrue(
          admittedBySelection.getOrDefault(scope.name(), 0) > 0,
          "Some revision is admitted under " + scope + ": " + admittedBySelection);
    System.out.printf(
        "Knowledge readability parity: %d states, %d contexts, %d revisions, %d comparisons,"
            + " admitted by selection %s%n",
        states, 2 * contexts.size(), revisions.size(), comparisons, admittedBySelection);
  }

  private UUID resourceOf(String label) {
    return jdbc.queryForObject(
        "SELECT resource_id FROM information_revisions WHERE id=?",
        UUID.class,
        revisions.get(label));
  }

  /**
   * Roots whose dependency closures have exactly 1,024 nodes (admitted when every node is readable)
   * and 1,025 nodes (never admitted), and a 41-revision chain alternating inputs and unpinned
   * references.
   */
  private void closures() {
    revisions.put("closure/1024", wideRoot(1023));
    revisions.put("closure/1025", wideRoot(1024));
    var chain = project("closure/chain-root", "one", "alice");
    var previous = chain;
    for (int depth = 0; depth < 40; depth++) {
      var next = resource("project:one", "one", "alice");
      if (depth % 2 == 0) input(previous.revision(), next.revision());
      else reference(previous, next.resource(), null);
      previous = next;
    }
  }

  private List<InformationContext> closureContexts(List<String> actors) {
    return contexts(
        actors,
        List.of(
            InformationContext.Selection.personal(),
            InformationContext.Selection.project("one"),
            new InformationContext.Selection(InformationContext.Scope.PROJECT, "one", false),
            InformationContext.Selection.project("two"),
            InformationContext.Selection.globalRead()),
        Arrays.asList(
            null,
            KnowledgeReadScope.homeOnly(Home.of("one")),
            new KnowledgeReadScope(Home.global(), true)));
  }

  private void withdrawDeepInput() {
    jdbc.update(
        "UPDATE information_revisions SET availability='withdrawn' WHERE id=(SELECT input_revision FROM information_inputs WHERE derived_revision=? ORDER BY input_revision DESC LIMIT 1)",
        revisions.get("closure/1024"));
  }

  @Test
  void closuresAtTheBoundAndOnePastItMatchSql() {
    closures();
    var contexts = closureContexts(List.of("alice", "bob", "carol", "@service/probe", principal));
    parity("wide closures", contexts);
    var closure = revisions.get("closure/1024");
    assertTrue(
        admits(
            sql(
                new InformationContext("alice", InformationContext.Selection.project("one")),
                List.of(closure)),
            closure,
            false),
        "A closure of exactly 1,024 nodes is admitted");
    var past = revisions.get("closure/1025");
    assertFalse(
        admits(
            sql(
                new InformationContext("alice", InformationContext.Selection.project("one")),
                List.of(past)),
            past,
            true),
        "A closure of 1,025 nodes is never admitted");
    withdrawDeepInput();
    parity("one deep input withdrawn", contexts);
    System.out.printf(
        "Closure parity: %d states, %d contexts, %d revisions, %d comparisons%n",
        states, 2 * contexts.size(), revisions.size(), comparisons);
  }

  /**
   * The same closures when every node is generally readable, so the general arm walks public
   * dependencies over the whole closure; SQL's walk is the slow side, so fewer actors are asked.
   */
  @Test
  void generallyReadableClosuresAtTheBoundMatchSql() {
    closures();
    jdbc.update(
        "UPDATE information_resource_policies SET version=version+1,audience='GLOBAL_READ',reason='AUDIENCE',actor=(SELECT id FROM authority_accounts WHERE legacy_handle='alice')");
    var contexts = closureContexts(List.of("alice", "carol", principal));
    parity("every closure node public", contexts);
    withdrawDeepInput();
    parity("one deep public input withdrawn", contexts);
    System.out.printf(
        "Public closure parity: %d states, %d contexts, %d revisions, %d comparisons%n",
        states, 2 * contexts.size(), revisions.size(), comparisons);
  }

  /** A root in project one with {@code inputs} direct inputs: a closure of inputs+1 nodes. */
  private UUID wideRoot(int inputs) {
    var root = resource("project:one", "one", "alice");
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind)"
            + " SELECT gen_random_uuid(),'project:one',?||'-'||n,'alice',(SELECT id FROM projects WHERE name='one'),'source'"
            + " FROM generate_series(1,?) n",
        root.revision().toString(),
        inputs);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text,allowance_total)"
            + " SELECT gen_random_uuid(),q.id,1,'Fixture','text/plain','fixture',1,'body',10 FROM information_resources q"
            + " WHERE q.source_name LIKE ?||'-%'",
        root.revision().toString());
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) SELECT ?,r.id FROM information_revisions r"
            + " JOIN information_resources q ON q.id=r.resource_id WHERE q.source_name LIKE ?||'-%'",
        root.revision(), root.revision().toString());
    return root.revision();
  }
}
