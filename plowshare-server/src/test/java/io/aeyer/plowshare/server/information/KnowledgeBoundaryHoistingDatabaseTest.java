package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pins the read boundary's <b>decision</b>, before its evaluation is hoisted out of the per-row
 * position it occupies today.
 *
 * <p>Real PostgreSQL rather than a mock: every assertion here is about what a SQL function decides
 * and at what granularity it decides it. A mocked repository cannot answer either question, which
 * is the exception the database test policy allows.
 *
 * <h2>What is being changed, and why these pins come first</h2>
 *
 * <p>{@code knowledge_document_in_boundary} is applied as a row filter, so a listing evaluates it
 * once per revision. Measured against a live installation: a sequential scan over 2,782 revisions
 * calling it on each, keeping 66 — six seconds to discard 97.6% of the rows. Each call nests four
 * more function calls ({@code information_global_read_member}, {@code knowledge_scope_readable},
 * and inside that {@code authority_project_role} and {@code service_project_role}).
 *
 * <p>The decision depends on far less than the row. A revision's permission is inherited from its
 * resource, and a resource's from its scope — and an installation has a handful of scopes. On that
 * same installation, 2,808 revisions across 2,796 resources resolved to <b>7</b> distinct scopes,
 * and <b>no</b> resource had two revisions that disagreed. So the per-row evaluation answers one
 * question thousands of times.
 *
 * <p>Hoisting it is only sound if that inheritance actually holds, so {@link
 * #revisions_of_one_resource_never_disagree()} pins it rather than trusting it.
 *
 * <h2>The pin that matters most</h2>
 *
 * <p>{@link #the_boundary_admits_exactly_these_revisions()} records the decision as a <b>set</b>,
 * compared in both directions. A first attempt at this change passed every timing comparison while
 * admitting 2,698 revisions it should have refused, because it dropped the conjunct binding a
 * document's scope to the read boundary's own home. {@code EXPLAIN} cannot catch that and neither
 * can a faster-is-better test; only comparing the admitted sets can.
 */
@Tag("full-db")
@Testcontainers
class KnowledgeBoundaryHoistingDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final String READER = "reader";
  private static final String PERSONAL =
      io.aeyer.plowshare.server.personal.PersonalSpaces.name(READER);
  private static DriverManagerDataSource source;
  private JdbcTemplate jdbc;

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
        "INSERT INTO admins(handle,password_hash) VALUES(?,'fixture'),('stranger','fixture')",
        READER);
    jdbc.update("INSERT INTO projects(name) VALUES('shared'),('foreign')");
    jdbc.update("INSERT INTO projects(name,personal_owner) VALUES(?,?)", PERSONAL, READER);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,?,'VIEWER' FROM projects WHERE name='shared'",
        READER);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,?,'MANAGER' FROM projects WHERE name=?",
        READER,
        PERSONAL);
  }

  /**
   * One resource with {@code revisions} revisions, in the named project or an account namespace.
   */
  private List<UUID> retained(String owner, String project, int revisions) {
    UUID resource = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind,tags)"
            + " VALUES(?,?,?,?,(SELECT id FROM projects WHERE name=?),'source','[]')",
        resource,
        project == null ? "account:" + owner : "project:" + project,
        resource + ".txt",
        owner,
        project);
    List<UUID> ids = new ArrayList<>();
    for (int ordinal = 1; ordinal <= revisions; ordinal++) {
      UUID revision = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text)"
              + " VALUES(?,?,?,'Retained','text/plain','fixture',1,'source')",
          revision,
          resource,
          ordinal);
      ids.add(revision);
    }
    return ids;
  }

  private boolean admitted(UUID revision, String home, boolean globalRead) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT knowledge_document_in_boundary(?,?,?,?)",
            Boolean.class,
            revision,
            READER,
            home,
            globalRead));
  }

  private Set<UUID> admittedSet(String home, boolean globalRead) {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT r.id FROM information_revisions r"
                + " WHERE knowledge_document_in_boundary(r.id,?,?,?)",
            UUID.class,
            READER,
            home,
            globalRead));
  }

  // ------------------------------------------------------ the inheritance the hoist relies on

  /**
   * <b>The load-bearing pin.</b> Hoisting the decision above the revision is sound only because a
   * revision inherits its resource's permission. Verified on a live installation across 2,808
   * revisions with zero disagreement; asserted here so a change that makes a revision's permission
   * independent of its resource fails this test rather than silently invalidating the hoist.
   */
  @Test
  void revisions_of_one_resource_never_disagree() {
    for (String project : new String[] {PERSONAL, "shared", "foreign"}) {
      List<UUID> revisions = retained(READER, project, 4);
      Set<Boolean> decisions = new HashSet<>();
      for (UUID revision : revisions) decisions.add(admitted(revision, PERSONAL, true));
      assertEquals(
          1,
          decisions.size(),
          "revisions of one resource in " + project + " disagreed: " + decisions);
    }
  }

  // --------------------------------------------------------------- the decision, as a set

  /**
   * The admitted set, which is what any rewrite must reproduce exactly. Compared in both
   * directions, because a change can be wrong by admitting too much as easily as too little, and
   * over-admission is the dangerous direction.
   */
  @Test
  void the_boundary_admits_exactly_these_revisions() {
    UUID own = retained(READER, PERSONAL, 1).getFirst();
    UUID shared = retained(READER, "shared", 1).getFirst();
    UUID foreign = retained("stranger", "foreign", 1).getFirst();
    UUID accountHome = retained(READER, null, 1).getFirst();

    Set<UUID> admitted = admittedSet(PERSONAL, true);
    assertTrue(admitted.contains(own), "a document in the boundary's own home is admitted");
    assertTrue(
        admitted.contains(accountHome),
        "the reader's own account-knowledge home is admitted when it is their personal home");
    assertFalse(
        admitted.contains(shared),
        "a project the reader can read is NOT in this boundary: the home must match");
    assertFalse(admitted.contains(foreign), "another account's document is refused");
  }

  /**
   * The conjunct a first attempt at this change dropped. A document in a project the reader can
   * genuinely read is still outside a boundary whose home is elsewhere — readability and
   * containment are different questions, and conflating them admitted thousands of rows.
   */
  @Test
  void readable_elsewhere_is_not_the_same_as_inside_this_boundary() {
    UUID shared = retained(READER, "shared", 1).getFirst();
    assertTrue(admitted(shared, "shared", true), "inside its own home it is admitted");
    assertFalse(
        admitted(shared, PERSONAL, true),
        "the same document, same reader, different boundary home: refused");
  }

  /**
   * The equivalence that licenses the rewrite: for every revision and every boundary, the hoisted
   * predicate admits exactly what the original did. Compared in both directions over a fixture
   * holding each shape the boundary distinguishes, because over-admission is the dangerous
   * direction and a first attempt at this change over-admitted by 2,698 rows.
   */
  @Test
  void the_hoisted_predicate_admits_the_same_set_as_the_original() {
    retained(READER, PERSONAL, 3);
    retained(READER, "shared", 2);
    retained("stranger", "foreign", 2);
    retained(READER, null, 2);

    for (String home : new String[] {PERSONAL, "shared", "foreign"})
      for (boolean globalRead : new boolean[] {false, true}) {
        List<UUID> original =
            jdbc.queryForList(
                "SELECT r.id FROM information_revisions r"
                    + " WHERE knowledge_document_in_boundary(r.id,?,?,?) ORDER BY r.id",
                UUID.class,
                READER,
                home,
                globalRead);
        List<UUID> hoisted =
            jdbc.queryForList(
                "SELECT r.id FROM information_revisions r"
                    + " WHERE knowledge_document_admitted(r.id,"
                    + "(SELECT ?::boolean AND information_global_read_member(?::text)),"
                    + "ARRAY(SELECT knowledge_readable_scopes(?::text,?::text))) ORDER BY r.id",
                UUID.class,
                globalRead,
                READER,
                READER,
                home);
        assertEquals(
            original,
            hoisted,
            "home=" + home + " globalRead=" + globalRead + " admitted a different set");
      }
  }

  /**
   * The scope set is the hoisted half, and it must name scopes rather than readability in general:
   * a project the reader can read is absent unless it is this boundary's home.
   */
  @Test
  void the_readable_scope_set_is_bound_to_the_boundarys_home() {
    retained(READER, PERSONAL, 1);
    retained(READER, "shared", 1);
    List<UUID> own =
        jdbc.queryForList("SELECT knowledge_readable_scopes(?,?)", UUID.class, READER, PERSONAL);
    List<UUID> shared =
        jdbc.queryForList("SELECT knowledge_readable_scopes(?,?)", UUID.class, READER, "shared");
    assertFalse(own.isEmpty(), "the reader's own personal home resolves to a scope");
    assertFalse(shared.isEmpty(), "a project the reader reads resolves to a scope");
    assertNotEquals(own, shared, "each home resolves to its own scopes, not to all readable ones");
    assertTrue(
        jdbc.queryForList("SELECT knowledge_readable_scopes(?,?)", UUID.class, READER, "foreign")
            .isEmpty(),
        "a home the reader holds no role in resolves to no scopes");
  }

  /** A home the reader holds no role in refuses, whatever else is true. */
  @Test
  void a_home_the_reader_cannot_read_admits_nothing() {
    retained("stranger", "foreign", 1);
    assertTrue(admittedSet("foreign", true).isEmpty());
  }

  /**
   * Global read is a separate route and must stay separate: turning it off must not change what the
   * home already admitted, and turning it on must not widen the home.
   */
  @Test
  void global_read_is_an_independent_route_into_the_boundary() {
    UUID own = retained(READER, PERSONAL, 1).getFirst();
    UUID foreign = retained("stranger", "foreign", 1).getFirst();
    assertTrue(admitted(own, PERSONAL, false), "the home admits without global read");
    assertFalse(admitted(foreign, PERSONAL, false), "and global read off admits nothing further");
    assertFalse(
        admitted(foreign, PERSONAL, true),
        "global read alone does not admit a document with no public policy");
  }
}
