package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.relay.Relay;
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
 * {@code V190__information_topic_derived_name.sql} renames an installation's live information topic
 * without losing anything keyed on its name.
 *
 * <p><b>Why this test exists and why it is the one that matters.</b> The topic name is part of the
 * key of nine tables, and three of them hold state that cannot be reconstructed: a subscriber's
 * committed cursor ({@code relay_subscriptions.seen_through}), the read authority established for
 * an account together with the topic incarnation it was established against ({@code
 * relay_subscription_authority}), and a consumer's fencing epoch ({@code relay_consumer_leases}). A
 * rename that recreated rows instead of updating them would reset a cursor to zero or to the
 * retention floor and revoke every established authority — and nothing would report it. Silent
 * redelivery or silent delivery loss is strictly worse than leaving the literal in place, so the
 * rename is pinned against a real installation's rows rather than against the migration's text.
 *
 * <p>Real PostgreSQL by necessity: the subject is Flyway applying the migration, the composite
 * foreign keys it drops and restores, and the agreement between its SQL derivation and the Java
 * one. A fixture built at V189 is what an existing installation actually looks like.
 */
@Tag("full-db")
@Testcontainers
class InformationTopicRenameDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  /** The literal V189 left behind, spelled so this file is not its own counterexample. */
  private static final String WITHDRAWN = String.join(".", "information", "changed");

  private static final String GROUP = "reader:viewer:information.events";
  private static final long COMMITTED = 2;
  private static final long EPOCH = 5;

  private JdbcTemplate jdbc;
  private long projectId;
  private String scope;

  /**
   * The installation as V189 leaves it: one information topic with live state keyed on its name.
   */
  private void installation(DriverManagerDataSource source) {
    // Both pins need an installation at V189, and the container is shared, so each starts from an
    // empty schema rather than from whatever the previous one migrated.
    Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
    Flyway.configure().dataSource(source).target("189").load().migrate();
    jdbc = new JdbcTemplate(source);
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES('viewer','fixture',false)");
    projectId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('atlas','fixture') RETURNING id",
                Long.class));
    scope = "project:" + projectId;

    jdbc.update(
        "INSERT INTO relay_topics(scope_key,name,payload_kind,retention_seconds,project_id,"
            + "last_position) VALUES(?,?,'TEXT',345600,?,3)",
        scope,
        WITHDRAWN,
        projectId);
    for (long position = 1; position <= 3; position++)
      jdbc.update(
          "INSERT INTO relay_publications(scope_key,topic,position,event_id,publisher,occurred_at,"
              + "published_at,schema_version,payload) VALUES(?,?,?,?,?,now(),now(),1,?::jsonb)",
          scope,
          WITHDRAWN,
          position,
          "information:" + position,
          "information:" + "a".repeat(64),
          "{\"text\": \"{}\"}");
    // The cursor. Committed partway through the log, which is the only state that can be lost
    // without any other row looking wrong.
    jdbc.update(
        "INSERT INTO relay_subscriptions(scope_key,topic,subscriber,seen_through,seen_at,"
            + "reader_account) VALUES(?,?,?,?,now(),'viewer')",
        scope,
        WITHDRAWN,
        GROUP,
        COMMITTED);
    // The authority, carrying the topic incarnation it was established against. V188's
    // relay_subscription_authority_validity refuses a record whose generation no longer matches.
    jdbc.update(
        "INSERT INTO relay_subscription_authority(scope_key,topic,subscriber,account,"
            + "topic_generation,established_at) SELECT ?,?,?,'viewer',t.generation,now()"
            + " FROM relay_topics t WHERE t.scope_key=? AND t.name=?",
        scope,
        WITHDRAWN,
        GROUP,
        scope,
        WITHDRAWN);
    jdbc.update(
        "INSERT INTO relay_consumer_leases(scope_key,topic,subscriber,worker,account,epoch,"
            + "lease_until) VALUES(?,?,?,'worker','viewer',?,now()+interval '1 minute')",
        scope,
        WITHDRAWN,
        GROUP,
        EPOCH);
    jdbc.update(
        "INSERT INTO relay_admissions(scope_key,topic,subscriber,publication_position,event_id,"
            + "publisher,occurred_at,published_at,payload_kind,schema_version,payload,routing_path,"
            + "routing_source,routing_hash,branch_count,admitted_at) VALUES(?,?,?,1,'information:1',"
            + "?,now(),now(),'TEXT',1,?::jsonb,'fixture','fixture',?,1,now())",
        scope,
        WITHDRAWN,
        GROUP,
        "information:" + "a".repeat(64),
        "{\"text\": \"{}\"}",
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
            + " VALUES(?,?,?,'viewer',?,?,1,?,now()+interval '1 minute')",
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
            + "action,reason) VALUES(?,?,'viewer',?,?,'RECONCILE','fixture')",
        UUID.randomUUID(),
        projectId,
        "c".repeat(64),
        WITHDRAWN);
    jdbc.update(
        "INSERT INTO relay_source_events(project_id,topic,family,subject,state,occurred_at)"
            + " VALUES(?,?,'LIFECYCLE','fixture','fixture',now())",
        projectId,
        WITHDRAWN);
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

    // Captured before the migration, so the assertions below compare against the installation's
    // own values rather than against whatever the migration left.
    UUID generation =
        one("SELECT generation FROM relay_topics WHERE name=?", UUID.class, WITHDRAWN);
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

    String derived = InformationTopics.of(new Relay.ProjectScope(projectId));

    // 1. The topic itself was renamed, not recreated. The incarnation is what every authority
    //    record is checked against, so a new generation here would revoke all of them.
    assertEquals(
        List.of(derived),
        jdbc.queryForList("SELECT name FROM relay_topics", String.class),
        "the topic is renamed to the name Java derives for its scope");
    assertEquals(
        generation,
        one("SELECT generation FROM relay_topics WHERE name=?", UUID.class, derived),
        "a recreated topic row would invalidate every established authority against it");
    assertEquals(
        3L,
        one("SELECT last_position FROM relay_topics WHERE name=?", Long.class, derived),
        "and its position space is untouched");

    // 2. The cursor. The one row that cannot be reconstructed from anything else.
    assertEquals(
        COMMITTED,
        one(
            "SELECT seen_through FROM relay_subscriptions WHERE scope_key=? AND topic=?"
                + " AND subscriber=?",
            Long.class,
            scope,
            derived,
            GROUP),
        "the subscriber's committed position must resolve to the same subscriber on the new name");
    assertEquals(
        1,
        rowsNamed("relay_subscriptions", "topic", derived),
        "and it must be the same row, not a second one beside a stranded original");

    // 3. The authority, asserted through the schema's own definition of validity rather than by
    //    the row merely existing: a surviving row whose generation or age no longer passes would
    //    refuse every consume and every wake hint just as completely as a deleted one.
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
                + " WHERE a.account='viewer' AND a.subscriber=?",
            String.class,
            GROUP),
        "the account's established read authority must still be valid on the renamed topic");

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
            "relay_publications", 3,
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

    // 6. And nothing anywhere still holds the literal. The migration raises if it does, so this
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
   * The foreign keys the migration drops are restored, with the cascade they had.
   *
   * <p>Restoring them by their own {@code pg_get_constraintdef} text is what should make this true,
   * and the consequence worth pinning is the behaviour rather than the catalog: if a key came back
   * without {@code ON DELETE CASCADE}, removing a topic would start failing or start orphaning
   * rows, and a catalog count would not notice.
   */
  @Test
  void the_dropped_foreign_keys_come_back_and_still_cascade() {
    var source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    installation(source);
    Flyway.configure().dataSource(source).load().migrate();
    String derived = InformationTopics.of(new Relay.ProjectScope(projectId));

    assertEquals(
        8,
        one(
            "SELECT count(*) FROM pg_constraint WHERE contype='f' AND conrelid IN"
                + " ('relay_publications'::regclass,'relay_subscriptions'::regclass,"
                + "'relay_admissions'::regclass,'relay_deliveries'::regclass,"
                + "'relay_consumer_leases'::regclass,'relay_sdk_batches'::regclass,"
                + "'relay_approval_gap_recoveries'::regclass,"
                + "'relay_subscription_authority'::regclass) AND confrelid IN"
                + " ('relay_topics'::regclass,'relay_subscriptions'::regclass,"
                + "'relay_admissions'::regclass)",
            Integer.class),
        "every topic-keyed Relay foreign key must be back");

    // Removing the topic must take its whole dependent tree with it, through three levels of
    // cascade: topic to subscription, subscription to admission and lease, admission to delivery.
    jdbc.update("DELETE FROM relay_topics WHERE name=?", derived);
    for (String table :
        List.of(
            "relay_publications",
            "relay_subscriptions",
            "relay_subscription_authority",
            "relay_consumer_leases",
            "relay_admissions",
            "relay_deliveries",
            "relay_sdk_batches",
            "relay_approval_gap_recoveries"))
      assertEquals(
          0,
          Objects.requireNonNull(one("SELECT count(*) FROM " + table, Integer.class)).intValue(),
          table + " must cascade with the topic it is keyed on");
  }
}
