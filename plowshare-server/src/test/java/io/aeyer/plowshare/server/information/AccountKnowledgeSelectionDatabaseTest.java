package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.personal.PersonalSpaces;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pins that an account's knowledge scope and its personal project select the same resources.
 *
 * <p>Real PostgreSQL rather than a mock: every assertion is about which rows a SQL selection
 * function returns, which is the exception the database test policy allows.
 *
 * <h2>What this is about</h2>
 *
 * <p>An account is an identity; what gives it information to hold is that it resolves to a personal
 * space, and a personal space is a project. So {@code {kind:"personal"}} is an alias for {@code
 * {kind:"project", project:"personal:<hex>"}} and resolves to it once, in {@link
 * InformationAccess}. After that the server only ever sees a project selection.
 *
 * <p>The read selection did not agree. It admitted a resource homed in an account's
 * ACCOUNT_KNOWLEDGE scope under the {@code personal} spelling, and under {@code project} only with
 * an explicit {@code information_links} row — so once the alias resolved, an account's own
 * knowledge was unreachable through the project that is supposed to be the same place. Resources
 * written after the alias are homed in the personal project's own scope and were never affected;
 * this is about everything written under the earlier spelling.
 *
 * <h2>The pin that matters most</h2>
 *
 * <p>{@link #both_spellings_select_exactly_the_same_set()} compares the two selections as sets, in
 * both directions. "The alias works" is cheap to satisfy by admitting too much — an earlier rewrite
 * of a neighbouring read predicate passed every timing and spot check while admitting 2,698
 * revisions it should have refused, because it dropped a single conjunct. Only comparing the
 * admitted sets catches that, so the negative cases here are as load-bearing as the positive one.
 */
@Tag("full-db")
@Testcontainers
class AccountKnowledgeSelectionDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final String ALICE = "alice";
  private static final String BOB = "bob";
  private static final String ALICE_PERSONAL = PersonalSpaces.name(ALICE);
  private static final String BOB_PERSONAL = PersonalSpaces.name(BOB);

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
    AuthorityFixtures.truncate(jdbc, "TRUNCATE admins,projects CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES(?,'fixture'),(?,'fixture')", ALICE, BOB);
    for (String handle : List.of(ALICE, BOB)) AuthorityFixtures.personalIdentity(jdbc, handle);
    jdbc.update("INSERT INTO projects(name) VALUES('shared')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,?,'MANAGER' FROM projects WHERE name='shared'",
        ALICE);
  }

  /**
   * One revision of one resource. A null project homes the resource in the owner's
   * ACCOUNT_KNOWLEDGE scope, which is the pre-alias spelling; a named project homes it there.
   * {@code information_resource_identity} derives the home scope from the namespace and {@code
   * information_initial_root_policy} supplies the root policy, so neither is written here.
   */
  private UUID resource(String owner, String project) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind,tags)"
            + " VALUES(?,?,?,?,(SELECT id FROM projects WHERE name=?),'source','[]'::jsonb)",
        resource,
        project == null ? "account:" + owner : "project:" + project,
        resource + ".txt",
        owner,
        project);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,text_hash,byte_size,extracted_text)"
            + " VALUES(?,?,1,'Pinned','text/plain','fixture','fixture',10,'body')",
        revision,
        resource);
    return revision;
  }

  /** The selection as a set. A null boundary ceiling keeps these pins about selection alone. */
  private Set<UUID> selected(String actor, String scope, String project) {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT revision FROM knowledge_readable_selection(?,?,?,false,NULL,NULL,'document',NULL,false,NULL,NULL,NULL)",
            UUID.class,
            actor,
            scope,
            project));
  }

  @Test
  void an_account_s_knowledge_is_selected_through_its_own_personal_project() {
    UUID mine = resource(ALICE, null);
    assertTrue(
        selected(ALICE, "project", ALICE_PERSONAL).contains(mine),
        "alice's account knowledge was not selected through alice's personal project");
  }

  /**
   * The alias, as set equality in both directions. Whatever the {@code personal} spelling selected
   * for an account, selecting that account's personal project selects exactly that and no more.
   */
  @Test
  void both_spellings_select_exactly_the_same_set() {
    resource(ALICE, null);
    resource(ALICE, null);
    resource(ALICE, "shared");
    resource(BOB, null);

    Set<UUID> byAlias = selected(ALICE, "personal", null);
    Set<UUID> byProject = selected(ALICE, "project", ALICE_PERSONAL);
    assertEquals(byAlias, byProject, "the two spellings of one place disagreed");
    assertFalse(
        byAlias.isEmpty(), "both spellings selected nothing, so the comparison proves none");
  }

  /** The alias is to the actor's own space, never to an account that merely owns a resource. */
  @Test
  void another_account_s_knowledge_is_never_selected() {
    UUID hers = resource(ALICE, null);
    assertFalse(
        selected(BOB, "project", BOB_PERSONAL).contains(hers),
        "alice's account knowledge leaked into bob's personal project");
    assertFalse(
        selected(BOB, "project", ALICE_PERSONAL).contains(hers),
        "bob selected alice's personal project, which he holds no role in");
  }

  /** The home gate's verdict, which is what decides writes and audience changes. */
  private boolean allows(UUID revision, String actor, String project, String action) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT information_home_allows((SELECT resource_id FROM information_revisions WHERE id=?),?,'project',?,?)",
            Boolean.class,
            revision,
            actor,
            project,
            action));
  }

  /**
   * The same alias on the write side. An account held READ, WRITE and SET_AUDIENCE over its own
   * knowledge under the {@code personal} spelling; it keeps exactly those under the project
   * spelling of the same place, which is what a navigation relationship on it needs.
   */
  @Test
  void an_account_keeps_its_own_knowledge_writable_through_its_personal_project() {
    UUID mine = resource(ALICE, null);
    for (String action : List.of("READ", "WRITE", "SET_AUDIENCE"))
      assertTrue(
          allows(mine, ALICE, ALICE_PERSONAL, action),
          action + " was refused on alice's own account knowledge");
  }

  /** And gains nothing over anyone else's: the gate is not a general account-knowledge grant. */
  @Test
  void no_account_may_write_another_s_knowledge_through_a_personal_project() {
    UUID hers = resource(ALICE, null);
    for (String action : List.of("READ", "WRITE", "SET_AUDIENCE")) {
      assertFalse(
          allows(hers, BOB, BOB_PERSONAL, action),
          action + " on alice's knowledge was allowed from bob's personal project");
      assertFalse(
          allows(hers, BOB, ALICE_PERSONAL, action),
          action + " on alice's knowledge was allowed to bob naming alice's personal project");
    }
  }

  /** A project resource stays in its project; the alias must not pull it into a personal space. */
  @Test
  void a_project_resource_is_not_drawn_into_a_personal_space() {
    UUID shared = resource(ALICE, "shared");
    assertTrue(selected(ALICE, "project", "shared").contains(shared), "the project lost its own");
    assertFalse(
        selected(ALICE, "project", ALICE_PERSONAL).contains(shared),
        "a project resource appeared in a personal space");
  }
}
