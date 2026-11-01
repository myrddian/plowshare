package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What a contained Application's read ceiling actually admits.
 *
 * <p><strong>Real PostgreSQL, and the reason is the assertion itself.</strong> {@code
 * ContainedVisibilityTest} proves the value half with mocks: a contained admission's {@link
 * io.aeyer.plowshare.server.access.KnowledgeReadScope} is its own home with {@code globalRead}
 * false, the intersection can only narrow, and {@code InformationSql.boundary} binds exactly that
 * home and that flag into the predicate. The remaining question is what {@code
 * knowledge_readable_scopes} and {@code knowledge_document_admitted} then <em>decide</em> — and in
 * particular that {@code knowledge_document_admitted}'s {@code bool_and} over {@code
 * knowledge_dependency_revisions} refuses a child revision whose closure leaves the subtree. That
 * is a SQL function's behaviour over a recursive closure; no mocked repository can answer it, which
 * is the exception the database test policy allows. The predicate is called here exactly as the
 * repository calls it, with the ceiling hoisted into its two non-revision arguments.
 */
@Tag("full-db")
@Testcontainers
class ContainedKnowledgeBoundaryDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final String READER = "operator";

  private static DriverManagerDataSource source;
  private JdbcTemplate jdbc;

  private String childProject;
  private UUID parentScope;
  private UUID childScope;
  private UUID globalScope;

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
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES(?,'fixture')", READER);
    jdbc.update("INSERT INTO projects(name) VALUES('parent')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,?,'MANAGER' FROM projects"
            + " WHERE name='parent'",
        READER);
    parentScope =
        jdbc.queryForObject(
            "SELECT s.id FROM authority_scopes s JOIN projects p ON p.id=s.project_id"
                + " WHERE p.name='parent' AND s.kind='PROJECT'",
            UUID.class);
    globalScope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE builtin='GLOBAL_KNOWLEDGE'", UUID.class);

    // The containment insert JdbcContainedApplicationRepository.create makes, in SQL, because the
    // reserved prefix has exactly one legitimate home and this is a fixture for its result.
    childProject = "contained:" + UUID.randomUUID();
    Long project =
        jdbc.queryForObject(
            "INSERT INTO projects(name,project_type,application_boundary)"
                + " VALUES(?,'MANAGED',TRUE) RETURNING id",
            Long.class,
            childProject);
    childScope = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES(?,'CONTAINED',?,?)",
        childScope,
        project,
        parentScope);
  }

  /** A resource in one home with one revision; {@code home_scope_id} is set by V157's trigger. */
  private UUID retained(String project) {
    UUID resource = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind,tags)"
            + " VALUES(?,?,?,?,(SELECT id FROM projects WHERE name=?),'source','[]')",
        resource,
        project == null ? "account:" + READER : "project:" + project,
        resource + ".txt",
        READER,
        project);
    UUID revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,"
            + "byte_size,extracted_text) VALUES(?,?,1,'Retained','text/plain','fixture',1,'text')",
        revision,
        resource);
    return revision;
  }

  /**
   * Exactly {@code InformationSql.boundary}'s call, with the ceiling already hoisted into the two
   * non-revision arguments. The scope array is built in SQL for the same reason the repository
   * builds it in SQL: the predicate takes {@code uuid[]} and the driver has no binding for one.
   *
   * <p><strong>The array is supplied by hand so that one ceiling can be posed at a time.</strong>
   * It is no longer counterfactual: V223 admits a contained scope to its own principal and to its
   * ancestors, and {@link #aContainedHomeResolvesTheSubtreeAndNothingAbove()} shows {@code
   * knowledge_readable_scopes} really does build an array holding {@code childScope}. What stays
   * hand-supplied is the <em>pairing</em> of a ceiling with a revision, because these tests are
   * about the closure predicate's {@code bool_and} and want each ceiling named explicitly rather
   * than derived from an actor.
   */
  private boolean admittedUnderHandSuppliedCeiling(
      UUID revision, boolean globalRead, UUID... readable) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT knowledge_document_admitted(?,?,"
                + "ARRAY(SELECT unnest(string_to_array(?,','))::uuid))",
            Boolean.class,
            revision,
            globalRead,
            java.util.Arrays.stream(readable)
                .map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","))));
  }

  private List<UUID> readableScopes(String home) {
    return jdbc.queryForList("SELECT knowledge_readable_scopes(?,?)", UUID.class, READER, home);
  }

  /**
   * Where the array the predicate takes comes from, restated for what V223 now admits. This test
   * asserted an empty set while slice one refused a {@code CONTAINED} scope by kind: a reader under
   * a contained home resolved nothing, not even the child's own scope. That limitation is the one
   * this slice lifts, so the empty-set claim is obsolete by design and the new contract is stated
   * here in its place.
   *
   * <p>The actor is {@code READER}, a human account holding MANAGER on {@code parent} — an
   * <em>ancestor</em> of the child's scope, which is the whole reason anything resolves. Under the
   * child's own home it resolves exactly the child's scope; under the container's home it resolves
   * the container's scope and the child's, because the ceiling now reaches down the containment
   * chain. Neither home resolves the Global tier, and the child's home never resolves its
   * container's scope: the direction is one-way and the refusal is what pins it.
   *
   * <p>Two gates sit outside this set and are deliberately untouched. An actor with no role on any
   * ancestor resolves nothing, however live its account — that is {@code authority_project_role}'s,
   * asserted below. And the child's <em>own</em> principal cannot read its documents back even
   * though this set would hold its scope, because {@code knowledge_revision_readable} opens with
   * {@code information_global_read_member} and a bare-UUID contained principal has no {@code
   * admins} row to be a member through; that refusal is pinned in {@code
   * ContainedKnowledgeReadSchemaTest} and is out of this slice by ruling.
   */
  @Test
  void aContainedHomeResolvesTheSubtreeAndNothingAbove() {
    assertEquals(
        List.of(childScope),
        readableScopes(childProject),
        "an ancestor's role resolves the child's own scope under the child's home");
    assertFalse(readableScopes(childProject).contains(parentScope), "and never its container's");
    assertFalse(readableScopes(childProject).contains(globalScope));
    // The container's own home reaches down the chain, which is what a retained ceiling widened
    // downward means on the listing path.
    assertEquals(Set.of(parentScope, childScope), new HashSet<>(readableScopes("parent")));
    // A set comparison would tolerate the same scope arriving twice from the hoisted array, which
    // the predicate's ANY() would hide; the size keeps the old exact-list strength.
    assertEquals(2, readableScopes("parent").size(), "each scope exactly once");
    assertFalse(readableScopes("parent").contains(globalScope));
    // A live account with no role on any ancestor resolves nothing at either home. Containment
    // carries a role down; it does not manufacture one.
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('outsider','fixture')");
    assertTrue(jdbc.queryForObject("SELECT account_active('outsider')", Boolean.class));
    assertEquals(
        List.of(),
        jdbc.queryForList(
            "SELECT knowledge_readable_scopes('outsider',?)", UUID.class, childProject));
    assertEquals(
        List.of(),
        jdbc.queryForList("SELECT knowledge_readable_scopes('outsider','parent')", UUID.class));
  }

  @Test
  void aChildKnowledgeReadReturnsNothingFromOutsideItsSubtree() {
    UUID own = retained(childProject);
    UUID above = retained("parent");

    assertTrue(
        admittedUnderHandSuppliedCeiling(own, false, childScope),
        "its own home is what the ceiling names");
    assertFalse(
        admittedUnderHandSuppliedCeiling(above, false, childScope), "its container's home is not");
    assertFalse(
        admittedUnderHandSuppliedCeiling(own, false, globalScope),
        "and the Global tier is not its home either");
    // The predicate is a membership test over the array it is handed and nothing more, which the
    // pair below is here to keep explicit: handed only the container's scope it admits only the
    // container's row, even though the container's real ceiling now resolves the child's scope too
    // -- aContainedHomeResolvesTheSubtreeAndNothingAbove is where that array is asserted.
    assertTrue(admittedUnderHandSuppliedCeiling(above, false, parentScope));
    assertFalse(admittedUnderHandSuppliedCeiling(own, false, parentScope));
  }

  /**
   * The closure is {@code bool_and}, so one input outside the subtree refuses the whole derived
   * revision. This is the row a child never named: it reaches outward only through a dependency.
   */
  @Test
  void aChildKnowledgeReadCannotReachOutwardThroughDependencyClosure() {
    UUID inside = retained(childProject);
    UUID outside = retained("parent");
    UUID derivedFromInside = retained(childProject);
    UUID derivedFromOutside = retained(childProject);
    input(derivedFromInside, inside);
    input(derivedFromOutside, outside);

    assertTrue(
        admittedUnderHandSuppliedCeiling(derivedFromInside, false, childScope),
        "a closure wholly inside the subtree is admitted");
    assertFalse(
        admittedUnderHandSuppliedCeiling(derivedFromOutside, false, childScope),
        "a closure that leaves the subtree refuses the derived revision entirely");
    // Global read is the only other route into the predicate, and a contained ceiling never
    // carries it: with it on, the same closure is still refused unless every input is generally
    // readable, and with it off there is no route at all.
    assertFalse(admittedUnderHandSuppliedCeiling(derivedFromOutside, true, childScope));
  }

  /**
   * A child's write lands in its own home, under its own contained scope, and is not refused.
   *
   * <p>This is the whole of the decision that a contained Application has its own writable
   * knowledge home: V157's identity trigger resolves {@code home_scope_id} to the contained scope,
   * so the row is the child's and is in no other home. It deliberately stops there. The child still
   * <em>cannot</em> read that row back, but the reason is no longer scope admission: {@link
   * #aContainedHomeResolvesTheSubtreeAndNothingAbove()} shows the scope resolves. It is {@code
   * knowledge_revision_readable}'s {@code information_global_read_member} gate, which a bare-UUID
   * principal cannot satisfy, and which this slice deliberately does not weaken.
   */
  @Test
  void aChildWritesKnowledgeInsideItsOwnHome() {
    UUID written = retained(childProject);
    assertEquals(
        childScope,
        jdbc.queryForObject(
            "SELECT q.home_scope_id FROM information_resources q"
                + " JOIN information_revisions r ON r.resource_id=q.id WHERE r.id=?",
            UUID.class,
            written),
        "the write is in the child's own home and in no other");
    // Production builds exactly this array for the child's own home; see the helper's note.
    assertTrue(admittedUnderHandSuppliedCeiling(written, false, childScope));
  }

  private void input(UUID derived, UUID from) {
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) VALUES(?,?)",
        derived,
        from);
  }
}
