package io.aeyer.plowshare.server.archive;

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
 * Pins {@code information_log_readable}'s verdict across V186, which changed how it reaches an
 * answer and must not change the answer.
 *
 * <p>Real PostgreSQL rather than a mock: every assertion is about what a recursive SQL predicate
 * decides, which is the exception the database test policy allows.
 *
 * <h2>What changed</h2>
 *
 * <p>The predicate used to build the parent graph of the whole installation on every call -- all of
 * {@code conversations} plus {@code orchestrations}, four ways -- and only then walk it from one
 * id. It is applied as a row filter in thirteen places, so a listing paid that fixed cost per
 * candidate row: 4.6 ms each on the live installation, 59 s to filter 12,845 conversations, and 2.7
 * s for a single page of 50. V186 joins the edge set against the recursive term instead, so the
 * walk uses the primary keys. Same branches, same conditions, same verdict -- 0.095 ms per call.
 *
 * <h2>The pin that matters most</h2>
 *
 * <p>{@link #the_two_predicates_admit_exactly_the_same_logs()} keeps the pre-V186 body in this test
 * database under another name and compares the admitted sets <b>in both directions</b>, for an
 * authenticated account and for the unauthenticated case. A rewrite of a neighbouring read
 * predicate once passed every timing and spot check while admitting 2,698 rows it should have
 * refused, because it quietly dropped one conjunct; only set comparison catches that.
 *
 * <p>{@link #every_edge_kind_is_part_of_the_ancestry()} exists for the same reason at the level of
 * the rewrite: four edge branches were moved, and a fixture built only from conversation parents
 * would pass even if the three orchestration branches had been dropped.
 */
@Tag("full-db")
@Testcontainers
class LogReadabilityAncestryDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  /** The pre-V186 body, kept here rather than in the schema: it is a test reference, not an API. */
  private static final String REFERENCE =
      """
      CREATE OR REPLACE FUNCTION log_readable_reference(target TEXT, account TEXT)
      RETURNS BOOLEAN LANGUAGE SQL STABLE AS $ref$
          WITH RECURSIVE edges(child,parent) AS (
              SELECT id,parent_id FROM conversations WHERE parent_id IS NOT NULL
              UNION SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL
              UNION SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent
              UNION SELECT id,conductor_conversation FROM orchestrations
          ), ancestors(id) AS (
              SELECT target UNION SELECT e.parent FROM edges e JOIN ancestors a ON e.child=a.id
          )
          SELECT NOT EXISTS(SELECT 1 FROM ancestors a JOIN information_quarantined_payloads q ON q.payload_id=a.id)
          AND NOT EXISTS(SELECT 1 FROM ancestors a JOIN information_job_inputs i ON i.job_id=a.id WHERE
              account IS NULL OR account<>i.owner_handle
              OR NOT information_readable(i.revision_id,account,i.scope,i.project_name,i.include_shared));
      $ref$;
      """;

  private static final String ALICE = "alice";
  private static final String BOB = "bob";
  private static DriverManagerDataSource source;
  private JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    new JdbcTemplate(source).execute(REFERENCE);
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(source);
    AuthorityFixtures.truncate(
        jdbc,
        "TRUNCATE admins,projects,conversations,information_quarantined_payloads,information_job_inputs CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES(?,'fixture'),(?,'fixture')", ALICE, BOB);
    for (String handle : List.of(ALICE, BOB)) AuthorityFixtures.personalIdentity(jdbc, handle);

    // A parent chain, so a verdict has to travel more than one edge.
    log("root", null);
    log("child", "root");
    log("grandchild", "child");
    // Three separate roots, one per thing that can block a descendant.
    log("quarantined", null);
    log("quarantined-child", "quarantined");
    log("owned-input", null);
    log("owned-input-child", "owned-input");
    log("foreign-input", null);
    log("foreign-input-child", "foreign-input");
    // The orchestration edges: a conductor reached from a caller, and a nested orchestration.
    log("caller", null);
    log("conductor", null);
    log("nested-conductor", null);
    orchestration("outer", "conductor", "caller", null);
    orchestration("inner", "nested-conductor", null, "outer");

    jdbc.update(
        "INSERT INTO information_quarantined_payloads(payload_id,reason) VALUES('quarantined','fixture')");
    // A second, independent chain whose only route to a blocked ancestor is through the three
    // orchestration edges. Without it the set comparison traverses those edges but every verdict
    // along them is "readable", so neutralising one of them would not move the admitted set and
    // the comparison would pass a rewrite that had dropped it.
    log("edge-root", null);
    log("edge-conductor", null);
    log("edge-nested-conductor", null);
    orchestration("edge-outer", "edge-conductor", "edge-root", null);
    orchestration("edge-inner", "edge-nested-conductor", null, "edge-outer");
    jdbc.update(
        "INSERT INTO information_quarantined_payloads(payload_id,reason) VALUES('edge-root','fixture')");
    // alice owns this one and can read it, so it blocks nobody for alice.
    input("owned-input", ALICE, revision(ALICE), PersonalSpaces.name(ALICE));
    // bob owns this one, so the owner mismatch alone blocks it for alice.
    input("foreign-input", BOB, revision(BOB), PersonalSpaces.name(BOB));
  }

  /**
   * One log. A root is a person's turn and carries a budget; anything with a parent is a
   * delegation, which names an agent and owns no budget. Both shapes are what the conversation
   * check constraints allow, and the predicate under test cares only about the parent edge.
   */
  private void log(String id, String parent) {
    if (parent == null)
      jdbc.update(
          "INSERT INTO conversations(id,created_at,origin,budget_total,budget_spent)"
              + " VALUES(?,now(),'turn',100,0)",
          id);
    else
      jdbc.update(
          "INSERT INTO conversations(id,created_at,origin,agent,parent_id,lifecycle)"
              + " VALUES(?,now(),'delegation','coder',?,NULL)",
          id,
          parent);
  }

  /** One orchestration, optionally called from a log and optionally nested under another. */
  private void orchestration(String id, String conductor, String caller, String parent) {
    jdbc.update(
        "INSERT INTO orchestrations(id,definition_name,tier,definition_hash,definition_source,"
            + "definition_origin,stages,max_returns,conductor_conversation,caller_agent,state,"
            + "depth,created_at,caller_conversation,parent)"
            + " VALUES(?,'fixture','SHIPPED','hash','source','origin','[]'::jsonb,1,?,'agent','running',?,now(),?,?)",
        id,
        conductor,
        parent == null ? 0 : 1,
        caller,
        parent);
  }

  /** A revision homed in that account's personal project, which only its owner may read. */
  private UUID revision(String owner) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind,tags)"
            + " VALUES(?,?,?,?,(SELECT id FROM projects WHERE name=?),'source','[]'::jsonb)",
        resource,
        "project:" + PersonalSpaces.name(owner),
        resource + ".txt",
        owner,
        PersonalSpaces.name(owner));
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,"
            + "text_hash,byte_size,extracted_text)"
            + " VALUES(?,?,1,'Pinned','text/plain','fixture','fixture',10,'body')",
        revision,
        resource);
    return revision;
  }

  private void input(String job, String owner, UUID revision, String project) {
    jdbc.update(
        "INSERT INTO information_job_inputs(job_id,owner_handle,revision_id,scope,project_name,include_shared)"
            + " VALUES(?,?,?,'project',?,false)",
        job,
        owner,
        revision,
        project);
  }

  private boolean readable(String log, String account) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT information_log_readable(?,?)", Boolean.class, log, account));
  }

  /**
   * The admitted set over <b>every</b> id the predicate is asked about in production: conversation
   * ids and orchestration ids both. Comparing conversations alone is not enough — dropping the
   * nested-orchestration edge leaves conversation verdicts untouched, and {@code
   * OrchestrationStore} filters orchestration ids with this predicate.
   */
  private Set<String> admitted(String function, String account) {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT t.id FROM (SELECT id FROM conversations UNION SELECT id FROM orchestrations) t"
                + " WHERE "
                + function
                + "(t.id,?)",
            String.class,
            account));
  }

  // ------------------------------------------------------------------ the load-bearing pin

  /**
   * The admitted set, compared both ways, authenticated and not. This is what the rewrite must
   * reproduce exactly; the timing it was made for is worth nothing if the verdict moved.
   */
  @Test
  void the_two_predicates_admit_exactly_the_same_logs() {
    for (String account : Arrays.asList(null, ALICE, BOB)) {
      Set<String> rewritten = admitted("information_log_readable", account);
      Set<String> reference = admitted("log_readable_reference", account);
      assertEquals(reference, rewritten, "the two predicates disagreed for account " + account);
      assertFalse(
          rewritten.isEmpty() && reference.isEmpty(),
          "both admitted nothing for account " + account + ", so the comparison proves nothing");
    }
  }

  /**
   * Four edge branches moved in the rewrite. A fixture built only from conversation parents would
   * pass even if the three orchestration branches had been dropped, so each is reached here.
   */
  @Test
  void every_edge_kind_is_part_of_the_ancestry() {
    // conversations.parent_id: the chain carries the root's verdict down two edges.
    jdbc.update(
        "INSERT INTO information_quarantined_payloads(payload_id,reason) VALUES('root','fixture')");
    assertFalse(readable("grandchild", ALICE), "the conversation parent chain was not followed");

    // orchestrations.caller_conversation -> conductor_conversation.
    jdbc.update(
        "INSERT INTO information_quarantined_payloads(payload_id,reason) VALUES('caller','fixture')");
    assertFalse(readable("conductor", ALICE), "the caller edge was not followed");

    // orchestrations.id -> conductor_conversation, and the parent-orchestration edge above it.
    assertFalse(readable("inner", ALICE), "the nested orchestration edges were not followed");
  }

  // -------------------------------------------------------------- the semantics being preserved

  @Test
  void a_quarantined_ancestor_blocks_every_descendant() {
    assertFalse(readable("quarantined", ALICE));
    assertFalse(readable("quarantined-child", ALICE), "quarantine did not reach the descendant");
    assertTrue(readable("root", ALICE), "an unrelated log was blocked");
  }

  @Test
  void an_input_blocks_descendants_only_when_its_owner_cannot_read_it() {
    // alice owns and can read this input, so it blocks neither it nor its descendant for her.
    assertTrue(readable("owned-input", ALICE));
    assertTrue(readable("owned-input-child", ALICE));
    // bob owns the other one, so the owner mismatch blocks it for alice, descendant included.
    assertFalse(readable("foreign-input", ALICE));
    assertFalse(readable("foreign-input-child", ALICE), "the input did not reach the descendant");
  }

  /** Unauthenticated listings hide any log whose ancestry consumed information at all. */
  @Test
  void an_absent_account_is_blocked_by_any_input_ancestor() {
    assertFalse(readable("owned-input", null), "a null account saw a log with an input ancestor");
    assertFalse(readable("owned-input-child", null));
    assertTrue(readable("root", null), "a null account was refused a log with no input ancestry");
  }
}
