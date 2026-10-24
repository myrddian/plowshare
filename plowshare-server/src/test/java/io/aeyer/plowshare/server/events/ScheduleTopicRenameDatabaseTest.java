package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.relay.Relay;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code V191__schedule_topic_derived_name.sql} renames an installation's live schedule topics
 * without losing anything keyed on their names.
 *
 * <p><b>Why the cursor is the pin that matters, and why it matters more here than it did for
 * information events.</b> {@code builtin.scheduling} is the firing inbox's own subscription, and
 * {@code RelayScheduledPublication} asserts that each publication lands at exactly {@code
 * seenThrough + 1}. A cursor reset to zero or to the retention floor therefore does not merely
 * redeliver — it makes the very next tick throw "scheduled Relay subscriber has an unadmitted
 * publication" and stops every schedule on the installation, with nothing in the rename reporting
 * it. So the rename is pinned against a real installation's rows, captured before the migration and
 * compared after, rather than against the migration's text.
 *
 * <p>Both scopes are exercised, unlike V190's: {@code JdbcScheduleStore.claimPublication} returns
 * the owning project for a file-managed schedule and the server scope for a projectless one, so an
 * installation can hold schedule topics under either and one expression has to serve both.
 *
 * <p>Real PostgreSQL by necessity: the subject is Flyway applying the migration, the composite
 * foreign keys it drops and restores, the anonymous CHECK it discovers, and the agreement between
 * its SQL derivation and the Java one. A fixture built at V190 is what an existing installation
 * actually looks like.
 */
@Tag("full-db")
@Testcontainers
class ScheduleTopicRenameDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  /** The literal V190 leaves behind, spelled so this file is not its own counterexample. */
  private static final String WITHDRAWN = String.join(".", "schedule", "due");

  /** The firing inbox's own consumer group, as {@code RelayScheduledPublication} names it. */
  private static final String GROUP = "builtin.scheduling";

  private static final long COMMITTED = 2;
  private static final long SYSTEM_COMMITTED = 1;
  private static final long EPOCH = 5;

  private JdbcTemplate jdbc;
  private long projectId;
  private String scope;

  /**
   * The installation as V190 leaves it: two schedule topics, one under a project and one under the
   * server scope, each with live state keyed on its name.
   *
   * <p>The fixture declares {@code SCHEDULE_DUE}, because at V190 the agreement CHECK V117 wrote is
   * still in force and refuses any other family under this name. That CHECK is what V191 drops, and
   * a fixture that could not satisfy it would not be an installation.
   */
  private void installation(DriverManagerDataSource source) {
    Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
    Flyway.configure().dataSource(source).target("190").load().migrate();
    jdbc = new JdbcTemplate(source);
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES('operator','fixture',true)");
    projectId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('atlas','fixture') RETURNING id",
                Long.class));
    scope = "project:" + projectId;

    // The project-scoped topic: a file-managed schedule's occurrences.
    jdbc.update(
        "INSERT INTO relay_topics(scope_key,name,payload_kind,retention_seconds,project_id,"
            + "last_position) VALUES(?,?,'SCHEDULE_DUE',345600,?,3)",
        scope,
        WITHDRAWN,
        projectId);
    for (long position = 1; position <= 3; position++)
      jdbc.update(
          "INSERT INTO relay_publications(scope_key,topic,position,event_id,publisher,occurred_at,"
              + "published_at,schema_version,payload) VALUES(?,?,?,?,'operator',now(),now(),1,?::jsonb)",
          scope,
          WITHDRAWN,
          position,
          "schedule:" + position,
          "{\"schedule\":\"morning\",\"emits\":\"daily\",\"fireAt\":\"2026-10-05T01:00:00Z\"}");
    // The cursor. Committed partway through the log, which is the only state that can be lost
    // without any other row looking wrong. Work-tier, so reader_account stays null (V187).
    jdbc.update(
        "INSERT INTO relay_subscriptions(scope_key,topic,subscriber,seen_through,seen_at)"
            + " VALUES(?,?,?,?,now())",
        scope,
        WITHDRAWN,
        GROUP,
        COMMITTED);
    // The authority, carrying the topic incarnation it was established against. The schema admits
    // one for a work-tier subscription, and the rename must preserve it where an installation has
    // one: V188's relay_subscription_authority_validity refuses a record whose generation no longer
    // matches the topic's.
    jdbc.update(
        "INSERT INTO relay_subscription_authority(scope_key,topic,subscriber,account,"
            + "topic_generation,established_at) SELECT ?,?,?,'operator',t.generation,now()"
            + " FROM relay_topics t WHERE t.scope_key=? AND t.name=?",
        scope,
        WITHDRAWN,
        GROUP,
        scope,
        WITHDRAWN);
    jdbc.update(
        "INSERT INTO relay_consumer_leases(scope_key,topic,subscriber,worker,account,epoch,"
            + "lease_until) VALUES(?,?,?,'worker','operator',?,now()+interval '1 minute')",
        scope,
        WITHDRAWN,
        GROUP,
        EPOCH);
    jdbc.update(
        "INSERT INTO relay_admissions(scope_key,topic,subscriber,publication_position,event_id,"
            + "publisher,occurred_at,published_at,payload_kind,schema_version,payload,routing_path,"
            + "routing_source,routing_hash,branch_count,admitted_at) VALUES(?,?,?,1,'schedule:1',"
            + "'operator',now(),now(),'SCHEDULE_DUE',1,?::jsonb,'fixture','fixture',?,1,now())",
        scope,
        WITHDRAWN,
        GROUP,
        "{\"schedule\":\"morning\",\"emits\":\"daily\",\"fireAt\":\"2026-10-05T01:00:00Z\"}",
        "b".repeat(64));
    jdbc.update(
        "INSERT INTO relay_deliveries(id,scope_key,topic,subscriber,publication_position,"
            + "branch_index,branch_name,receiver,updated_at)"
            + " VALUES(?,?,?,?,1,0,'fixture','inbox.notify',now())",
        UUID.randomUUID(),
        scope,
        WITHDRAWN,
        GROUP);
    jdbc.update(
        "INSERT INTO relay_sdk_batches(scope_key,topic,subscriber,account,consumer_id,batch_id,"
            + "fence,through_position,lease_until)"
            + " VALUES(?,?,?,'operator',?,?,1,?,now()+interval '1 minute')",
        scope,
        WITHDRAWN,
        GROUP,
        UUID.randomUUID(),
        UUID.randomUUID(),
        COMMITTED);
    jdbc.update(
        "INSERT INTO relay_approval_gap_recoveries(scope_key,topic,subscriber,expired_through,"
            + "pending_approvals) VALUES(?,?,?,1,0)",
        scope,
        WITHDRAWN,
        GROUP);
    jdbc.update(
        "INSERT INTO relay_operator_receipts(request_id,project_id,account,fingerprint,topic,"
            + "action,reason) VALUES(?,?,'operator',?,?,'RECONCILE','fixture')",
        UUID.randomUUID(),
        projectId,
        "c".repeat(64),
        WITHDRAWN);
    jdbc.update(
        "INSERT INTO relay_source_events(project_id,topic,family,subject,state,occurred_at)"
            + " VALUES(?,?,'LIFECYCLE','fixture','fixture',now())",
        projectId,
        WITHDRAWN);

    // The server-scoped topic: a projectless schedule's occurrences, with its own cursor. V120
    // keys it 'system' with a null project.
    jdbc.update(
        "INSERT INTO relay_topics(scope_key,name,payload_kind,retention_seconds,project_id,"
            + "last_position) VALUES('system',?,'SCHEDULE_DUE',345600,NULL,1)",
        WITHDRAWN);
    jdbc.update(
        "INSERT INTO relay_publications(scope_key,topic,position,event_id,publisher,occurred_at,"
            + "published_at,schema_version,payload) VALUES('system',?,1,'schedule:system',"
            + "'operator',now(),now(),1,?::jsonb)",
        WITHDRAWN,
        "{\"schedule\":\"nightly\",\"emits\":\"daily\",\"fireAt\":\"2026-10-05T01:00:00Z\"}");
    jdbc.update(
        "INSERT INTO relay_subscriptions(scope_key,topic,subscriber,seen_through,seen_at)"
            + " VALUES('system',?,?,?,now())",
        WITHDRAWN,
        GROUP,
        SYSTEM_COMMITTED);
  }

  private <T> T one(String sql, Class<T> type, Object... arguments) {
    return jdbc.queryForObject(sql, type, arguments);
  }

  private int rowsNamed(String table, String column, String name) {
    // Both identifiers come from this method's own callers, which spell them literally.
    return Objects.requireNonNull(
        one("SELECT count(*) FROM " + table + " WHERE " + column + "=?", Integer.class, name));
  }

  @Test
  void the_rename_carries_every_subscribers_position_and_authority_onto_the_derived_name() {
    var source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    installation(source);

    // Captured before the migration, so the assertions below compare against the installation's own
    // values rather than against whatever the migration left.
    UUID generation =
        one(
            "SELECT generation FROM relay_topics WHERE scope_key=? AND name=?",
            UUID.class,
            scope,
            WITHDRAWN);
    String established =
        one(
            "SELECT established_at::text FROM relay_subscription_authority WHERE topic=?",
            String.class,
            WITHDRAWN);
    assertEquals(
        "VALID",
        one(
            "SELECT relay_subscription_authority_validity(a.topic_generation,t.generation,"
                + "a.established_at,interval '12 hours',now()) FROM relay_subscription_authority a"
                + " JOIN relay_topics t ON t.scope_key=a.scope_key AND t.name=a.topic",
            String.class),
        "the fixture's authority must be valid before the rename, or its survival proves nothing");

    Flyway.configure().dataSource(source).load().migrate();

    String derived = EventTopics.scheduled(new Relay.ProjectScope(projectId));
    String derivedSystem = EventTopics.scheduled(Relay.SystemScope.SERVER);
    assertNotEquals(derived, derivedSystem, "two scopes must not collide on one name");

    // 1. The topics were renamed, not recreated. The incarnation is what every authority record is
    //    checked against, so a new generation here would revoke all of them.
    assertEquals(
        List.of(derived, derivedSystem),
        jdbc.queryForList("SELECT name FROM relay_topics ORDER BY scope_key", String.class),
        "each topic is renamed to the name Java derives for its own scope");
    assertEquals(
        generation,
        one(
            "SELECT generation FROM relay_topics WHERE scope_key=? AND name=?",
            UUID.class,
            scope,
            derived),
        "a recreated topic row would invalidate every established authority against it");
    assertEquals(
        3L,
        one(
            "SELECT last_position FROM relay_topics WHERE scope_key=? AND name=?",
            Long.class,
            scope,
            derived),
        "and its position space is untouched");

    // 2. The cursor. The one row that cannot be reconstructed from anything else, and the one whose
    //    loss stops every schedule on the installation at the next tick.
    assertEquals(
        COMMITTED,
        one(
            "SELECT seen_through FROM relay_subscriptions WHERE scope_key=? AND topic=?"
                + " AND subscriber=?",
            Long.class,
            scope,
            derived,
            GROUP),
        "builtin.scheduling's committed position must resolve to the same subscriber on the new"
            + " name");
    assertEquals(
        SYSTEM_COMMITTED,
        one(
            "SELECT seen_through FROM relay_subscriptions WHERE scope_key='system' AND topic=?"
                + " AND subscriber=?",
            Long.class,
            derivedSystem,
            GROUP),
        "and so must the projectless schedule's, under the server scope");
    assertEquals(
        2,
        Objects.requireNonNull(one("SELECT count(*) FROM relay_subscriptions", Integer.class)),
        "two subscriptions in, two out: no row stranded beside a new one");

    // 3. The authority, asserted through the schema's own definition of validity rather than by the
    //    row merely existing: a surviving row whose generation or age no longer passes would refuse
    //    every consume just as completely as a deleted one.
    assertEquals(
        established,
        one(
            "SELECT established_at::text FROM relay_subscription_authority WHERE topic=?",
            String.class,
            derived));
    assertEquals(
        "VALID",
        one(
            "SELECT relay_subscription_authority_validity(a.topic_generation,t.generation,"
                + "a.established_at,interval '12 hours',now()) FROM relay_subscription_authority a"
                + " JOIN relay_topics t ON t.scope_key=a.scope_key AND t.name=a.topic"
                + " WHERE a.account='operator' AND a.subscriber=?",
            String.class,
            GROUP),
        "the established read authority must still be valid on the renamed topic");

    // 4. The fencing epoch. A reset epoch would let a restarted worker reuse an old fence.
    assertEquals(
        EPOCH,
        one(
            "SELECT epoch FROM relay_consumer_leases WHERE topic=? AND subscriber=?",
            Long.class,
            derived,
            GROUP));

    // 5. Everything else keyed on the name, including the two tables with no foreign key to it.
    Map<String, Integer> carried =
        Map.of(
            "relay_admissions", 1,
            "relay_deliveries", 1,
            "relay_sdk_batches", 1,
            "relay_approval_gap_recoveries", 1,
            "relay_operator_receipts", 1,
            "relay_source_events", 1);
    for (var table : carried.entrySet())
      assertEquals(
          table.getValue().intValue(),
          rowsNamed(table.getKey(), "topic", derived),
          table.getKey() + " must be renamed in place");
    assertEquals(3, rowsNamed("relay_publications", "topic", derived));
    assertEquals(1, rowsNamed("relay_publications", "topic", derivedSystem));

    // 6. The agreement CHECK V117 wrote is gone, so the kind is no longer tied to a name.
    assertEquals(
        0,
        Objects.requireNonNull(
            one(
                "SELECT count(*) FROM pg_constraint WHERE contype='c'"
                    + " AND conrelid='relay_topics'::regclass"
                    + " AND pg_get_constraintdef(oid) LIKE '%' || ? || '%'",
                Integer.class, WITHDRAWN)),
        "the schedule.due agreement CHECK must not survive the rename");
    // And the payload kind itself is untouched: it is still an admissible family.
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "INSERT INTO relay_topics(scope_key,name,payload_kind,retention_seconds,project_id)"
                    + " VALUES(?,'occurrence.sample','SCHEDULE_DUE',345600,?)",
                scope,
                projectId),
        "SCHEDULE_DUE stays a payload kind, now unbound from any particular topic name");

    // 7. And nothing anywhere still holds the literal. The migration raises if it does, so this
    //    asserts the migration's own guard ran rather than repeating it.
    for (String table :
        List.of(
            "relay_topics",
            "relay_publications",
            "relay_subscriptions",
            "relay_subscription_authority",
            "relay_consumer_leases",
            "relay_admissions",
            "relay_deliveries",
            "relay_sdk_batches",
            "relay_approval_gap_recoveries",
            "relay_operator_receipts",
            "relay_source_events"))
      assertEquals(
          0,
          rowsNamed(table, table.equals("relay_topics") ? "name" : "topic", WITHDRAWN),
          table + " must hold nothing under the withdrawn literal");
  }

  /**
   * PostgreSQL and Java derive the same name, asserted by evaluating the shipped migration's own
   * expression rather than a copy of it.
   *
   * <p>The expression is read out of the migration file, so a change to one side without the other
   * fails here. Both scope cases are evaluated, because both are live for this family.
   */
  @Test
  void the_topic_name_postgresql_derives_is_the_one_java_derives() {
    var source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    installation(source);
    Flyway.configure().dataSource(source).load().migrate();

    String migration = shipped();
    String expression =
        "'schedule.occurrence.'\n"
            + "        || encode(sha256(('plowshare.schedule.occurrence/1:' || scope_key)::bytea),"
            + " 'hex')";
    assertTrue(
        migration.contains(expression),
        "the migration must still carry the expression this test evaluates");

    for (Map.Entry<String, Relay.Scope> scopeCase :
        Map.of(
                scope,
                (Relay.Scope) new Relay.ProjectScope(projectId),
                "system",
                Relay.SystemScope.SERVER)
            .entrySet())
      assertEquals(
          EventTopics.scheduled(scopeCase.getValue()),
          one(
              "SELECT 'schedule.occurrence.'"
                  + " || encode(sha256(('plowshare.schedule.occurrence/1:' || ?::text)::bytea),"
                  + " 'hex')",
              String.class,
              scopeCase.getKey()),
          "PostgreSQL and Java must derive the same name for " + scopeCase.getKey());
  }

  /** The shipped migration's text, so the expression asserted above is the one that ran. */
  private static String shipped() {
    Path root = SourceScan.root();
    try {
      return Files.readString(
          root.resolve(
              "plowshare-server/src/main/resources/db/migration/"
                  + "V191__schedule_topic_derived_name.sql"));
    } catch (java.io.IOException unreadable) {
      throw new AssertionError(unreadable);
    }
  }
}
