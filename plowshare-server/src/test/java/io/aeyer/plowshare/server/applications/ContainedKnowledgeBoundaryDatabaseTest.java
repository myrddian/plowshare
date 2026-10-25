package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
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
   * <p><strong>The array is supplied by hand, and for a contained scope that is
   * counterfactual.</strong> {@link #aContainedHomeResolvesAnEmptyReadableSet()} shows production
   * cannot build an array holding a contained scope at all, because {@code
   * knowledge_scope_readable} admits no {@code CONTAINED} kind. So every assertion below that
   * passes {@code childScope} asks what the closure predicate <em>would</em> decide if that scope
   * were ever readable — which is the right question for pinning closure semantics, and is not
   * evidence that any caller can obtain that answer today. The widening that would make it
   * producible is ADR 0042's recorded gap and is not this slice's. Reality is therefore stricter
   * than these tests, never looser, which is why the fence conclusions hold regardless.
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
   * Where the array the predicate takes comes from, and what a contained home resolves to:
   * <b>nothing at all</b>, which is the opposite of what a reader might expect and is why this test
   * is named for the empty set rather than for the child's own scope.
   *
   * <p>{@code knowledge_scope_readable} admits {@code ACCOUNT_KNOWLEDGE}, the {@code
   * GLOBAL_KNOWLEDGE} builtin, {@code PROJECT} and {@code PERSONAL}, and a contained scope is none
   * of those — the SQL-side closure this slice already shipped. So a reader under a contained home
   * resolves an empty readable set: neither its container's scope nor the Global tier, which is the
   * fence, and <em>not its own scope either</em>, which is ADR 0042's recorded gap and is what
   * makes every hand-supplied {@code childScope} array in this class counterfactual.
   */
  @Test
  void aContainedHomeResolvesAnEmptyReadableSet() {
    assertEquals(List.of(), readableScopes(childProject));
    assertFalse(readableScopes(childProject).contains(parentScope));
    assertFalse(readableScopes(childProject).contains(globalScope));
    // The container's own home still resolves to the container's own scope and to nothing of the
    // child's, so the fence is a fence and not a general refusal of every home.
    assertEquals(List.of(parentScope), readableScopes("parent"));
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
    // The container, reading under its own ceiling, sees its own home and not the child's. A
    // parent reaches a child through application.child.* and never by widening its read home.
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
        "a closure wholly inside the subtree would be admitted, were the subtree readable");
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
   * so the row is the child's and is in no other home. It deliberately stops there. The child
   * <em>cannot</em> read that row back — {@link #aContainedHomeResolvesAnEmptyReadableSet()} is why
   * — and the trailing assertion says only what the closure predicate would decide if the subtree
   * were readable, which no caller can ask today.
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
    // Counterfactual, as the helper's name says: production cannot build this array.
    assertTrue(admittedUnderHandSuppliedCeiling(written, false, childScope));
  }

  private void input(UUID derived, UUID from) {
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) VALUES(?,?)",
        derived,
        from);
  }
}
