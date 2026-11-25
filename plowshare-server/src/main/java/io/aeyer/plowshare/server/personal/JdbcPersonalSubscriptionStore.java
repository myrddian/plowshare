package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayCausationCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcPersonalSubscriptionStore implements PersonalSubscriptionStore {
  private static final String BINDINGS =
      """
      SELECT s.project_id,s.name,s.consumer,s.topic_generation,s.source_project,s.schedule,s.ending,
        s.relay_project_id,s.relay_topic,s.relay_seen,s.source_type,s.attention_project,
        s.agent,s.skill,s.mode,s.skill_hash,s.message_route,s.enabled_at,s.paused,
        s.state,s.source_event,s.source_position,s.causation,s.attempt,s.claim,s.job,s.detail,
        s.updated_at,p.personal_owner,j.id IS NOT NULL AS job_recorded,j.ending AS job_ending
      FROM personal_mailbox_subscriptions s JOIN projects p ON p.id=s.project_id
      LEFT JOIN jobs j ON j.id=s.job
      """;
  private final JdbcTemplate jdbc;

  JdbcPersonalSubscriptionStore(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public void lock(long project) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended('personal-mailbox:' || ?::text,0))",
        row -> {},
        project);
  }

  @Override
  public void lockApplicationInboxes(List<Long> projects) {
    // Every activation locks Applications in the same order, before its Personal owner lock.
    for (long project : projects.stream().distinct().sorted().toList())
      jdbc.query(
          "SELECT pg_advisory_xact_lock(hashtextextended('personal-application-inbox:' || ?::text,0))",
          row -> {},
          project);
  }

  @Override
  public String revision(long project) {
    return jdbc
        .query(
            "SELECT revision FROM personal_mailbox_configs WHERE project_id=?",
            (row, index) -> row.getString(1),
            project)
        .stream()
        .findFirst()
        .orElse("");
  }

  @Override
  public Instant now() {
    return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
        .toInstant();
  }

  @Override
  public List<Binding> bindings(long project) {
    return jdbc.query(BINDINGS + " WHERE s.project_id=? ORDER BY s.name", this::row, project);
  }

  @Override
  public Optional<Binding> find(Key key) {
    return jdbc
        .query(
            BINDINGS + " WHERE s.project_id=? AND s.name=?", this::row, key.project(), key.name())
        .stream()
        .findFirst();
  }

  @Override
  public List<Binding> workAfter(Key after, int limit) {
    return jdbc.query(
        BINDINGS
            + """
         WHERE (s.project_id,s.name)>(?,?) AND NOT s.paused
           AND s.state NOT IN ('blocked','uncertain')
         ORDER BY s.project_id,s.name LIMIT ?
        """,
        this::row,
        after.project(),
        after.name(),
        limit);
  }

  @Override
  public void activate(long project, String revision) {
    jdbc.update(
        """
        INSERT INTO personal_mailbox_configs(project_id,revision) VALUES (?,?)
        ON CONFLICT(project_id) DO UPDATE SET revision=EXCLUDED.revision
        """,
        project,
        revision);
  }

  @Override
  public void save(
      long project,
      UUID consumer,
      UUID topicGeneration,
      Spec spec,
      Instant enabledAt,
      long relaySeen) {
    jdbc.update(
        """
        INSERT INTO personal_mailbox_subscriptions(project_id,name,consumer,topic_generation,
            source_project,schedule,ending,relay_project_id,relay_topic,agent,skill,mode,
            skill_hash,enabled_at,paused,relay_seen,source_type,attention_project,message_route)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(project_id,name) DO UPDATE SET agent=EXCLUDED.agent, skill=EXCLUDED.skill,
            ending=EXCLUDED.ending, mode=EXCLUDED.mode, skill_hash=EXCLUDED.skill_hash,
            paused=EXCLUDED.paused, message_route=EXCLUDED.message_route,
            state=CASE WHEN personal_mailbox_subscriptions.state='starting'
                AND (personal_mailbox_subscriptions.agent, personal_mailbox_subscriptions.skill,
                     personal_mailbox_subscriptions.mode, personal_mailbox_subscriptions.skill_hash,
                     personal_mailbox_subscriptions.ending, personal_mailbox_subscriptions.message_route)
                IS DISTINCT FROM (EXCLUDED.agent, EXCLUDED.skill, EXCLUDED.mode, EXCLUDED.skill_hash, EXCLUDED.ending, EXCLUDED.message_route)
              THEN 'source-unavailable'
              WHEN personal_mailbox_subscriptions.state='blocked' THEN 'idle'
              ELSE personal_mailbox_subscriptions.state END,
            detail=CASE WHEN personal_mailbox_subscriptions.state='starting'
                AND (personal_mailbox_subscriptions.agent, personal_mailbox_subscriptions.skill,
                     personal_mailbox_subscriptions.mode, personal_mailbox_subscriptions.skill_hash,
                     personal_mailbox_subscriptions.ending, personal_mailbox_subscriptions.message_route)
                IS DISTINCT FROM (EXCLUDED.agent, EXCLUDED.skill, EXCLUDED.mode, EXCLUDED.skill_hash, EXCLUDED.ending, EXCLUDED.message_route)
              THEN 'The subscription changed before dispatch.'
              WHEN personal_mailbox_subscriptions.state='blocked' THEN NULL
              ELSE personal_mailbox_subscriptions.detail END,
            relay_input=CASE WHEN personal_mailbox_subscriptions.state='starting'
                AND (personal_mailbox_subscriptions.agent, personal_mailbox_subscriptions.skill,
                     personal_mailbox_subscriptions.mode, personal_mailbox_subscriptions.skill_hash,
                     personal_mailbox_subscriptions.message_route)
                IS DISTINCT FROM (EXCLUDED.agent, EXCLUDED.skill, EXCLUDED.mode, EXCLUDED.skill_hash, EXCLUDED.message_route)
              THEN NULL ELSE personal_mailbox_subscriptions.relay_input END
        """,
        project,
        spec.name(),
        consumer,
        topicGeneration,
        spec.sourceProject(),
        spec.schedule(),
        spec.ending(),
        spec.relayTopic() == null ? null : spec.relayTopic().projectId(),
        spec.relayTopic() == null ? null : spec.relayTopic().name(),
        spec.agent(),
        spec.skill(),
        spec.mode(),
        spec.skillHash(),
        Timestamp.from(enabledAt),
        spec.paused(),
        relaySeen,
        spec.sourceType().name(),
        spec.attentionProject(),
        spec.messageRoute());
  }

  @Override
  public void remove(long project, List<String> keep) {
    jdbc.update(
        "DELETE FROM personal_mailbox_subscriptions WHERE project_id=? AND NOT (name=ANY(?))",
        statement -> {
          statement.setLong(1, project);
          statement.setArray(2, statement.getConnection().createArrayOf("text", keep.toArray()));
        });
  }

  @Override
  public void prepare(
      Binding binding,
      String event,
      String position,
      String relayInput,
      RelayCausation cause,
      UUID attempt,
      UUID claim) {
    if (jdbc.update(
            """
        UPDATE personal_mailbox_subscriptions SET state='starting',source_event=?,source_position=?,
            relay_input=?,causation=?::jsonb,attempt=?,claim=?,job=NULL,
            detail=NULL,updated_at=clock_timestamp()
        WHERE project_id=? AND name=? AND consumer=? AND NOT paused
          AND state IN ('idle','completed','source-unavailable')
        """,
            event,
            position,
            relayInput,
            RelayCausationCodec.write(cause),
            attempt,
            claim,
            binding.key().project(),
            binding.key().name(),
            binding.consumer())
        != 1) {
      throw new IllegalStateException("The Personal subscription is already handling an event");
    }
  }

  @Override
  public Optional<String> relayInput(Binding binding) {
    return jdbc
        .query(
            """
        SELECT relay_input FROM personal_mailbox_subscriptions
        WHERE project_id=? AND name=? AND consumer=? AND claim=? AND NOT paused
          AND state='starting' AND relay_input IS NOT NULL
        """,
            (row, index) -> row.getString(1),
            binding.key().project(),
            binding.key().name(),
            binding.consumer(),
            binding.claim())
        .stream()
        .findFirst();
  }

  @Override
  public void advanceRelay(Binding binding, long position, String unavailableEvent, String detail) {
    if (binding.spec().relayTopic() == null || position <= binding.relaySeen())
      throw new IllegalArgumentException("Relay cursor must advance on its pinned source");
    int changed;
    if (unavailableEvent == null) {
      changed =
          jdbc.update(
              "UPDATE personal_mailbox_subscriptions SET relay_seen=?"
                  + " WHERE project_id=? AND name=? AND consumer=? AND relay_seen=?",
              position,
              binding.key().project(),
              binding.key().name(),
              binding.consumer(),
              binding.relaySeen());
    } else {
      changed =
          jdbc.update(
              """
          UPDATE personal_mailbox_subscriptions SET relay_seen=?,state='source-unavailable',
              source_event=?,source_position=?,detail=?,job=NULL,updated_at=clock_timestamp()
          WHERE project_id=? AND name=? AND consumer=? AND relay_seen=?
          """,
              position,
              unavailableEvent,
              Long.toString(position),
              Objects.requireNonNull(detail, "Unavailable event disposition"),
              binding.key().project(),
              binding.key().name(),
              binding.consumer(),
              binding.relaySeen());
    }
    if (changed != 1) throw new IllegalStateException("Relay cursor changed during admission");
  }

  @Override
  public boolean reclaim(Binding binding, UUID claim) {
    return jdbc.update(
            """
        UPDATE personal_mailbox_subscriptions SET claim=?,updated_at=clock_timestamp()
        WHERE project_id=? AND name=? AND claim=? AND state='starting' AND NOT paused AND job IS NULL
        """,
            claim,
            binding.key().project(),
            binding.key().name(),
            binding.claim())
        == 1;
  }

  @Override
  public boolean started(Binding binding, String job) {
    return jdbc.update(
            """
        UPDATE personal_mailbox_subscriptions SET state='running',job=?,relay_input=NULL,
            updated_at=clock_timestamp()
        WHERE project_id=? AND name=? AND consumer=? AND claim=? AND state='starting'
          AND NOT paused AND agent=? AND skill=? AND mode=? AND skill_hash=?
        """,
            job,
            binding.key().project(),
            binding.key().name(),
            binding.consumer(),
            binding.claim(),
            binding.spec().agent(),
            binding.spec().skill(),
            binding.spec().mode(),
            binding.spec().skillHash())
        == 1;
  }

  @Override
  public void settle(Binding binding, String state, String detail) {
    jdbc.update(
        """
        UPDATE personal_mailbox_subscriptions SET state=?,detail=?,relay_input=NULL,
            updated_at=clock_timestamp()
        WHERE project_id=? AND name=? AND consumer=? AND claim IS NOT DISTINCT FROM ? AND state=?
        """,
        state,
        detail,
        binding.key().project(),
        binding.key().name(),
        binding.consumer(),
        binding.claim(),
        binding.state());
  }

  private Binding row(ResultSet row, int index) throws SQLException {
    String name = row.getString("name");
    Long source = row.getObject("relay_project_id", Long.class);
    return new Binding(
        new Key(row.getLong("project_id"), name),
        row.getString("personal_owner"),
        row.getObject("consumer", UUID.class),
        row.getObject("topic_generation", UUID.class),
        new Spec(
            name,
            row.getString("source_project"),
            row.getString("schedule"),
            row.getString("ending"),
            source == null ? null : new Relay.TopicKey(source, row.getString("relay_topic")),
            SourceType.valueOf(row.getString("source_type")),
            row.getObject("attention_project", Long.class),
            row.getString("agent"),
            row.getString("skill"),
            row.getString("mode"),
            row.getString("skill_hash"),
            row.getString("message_route"),
            row.getBoolean("paused")),
        row.getTimestamp("enabled_at").toInstant(),
        row.getLong("relay_seen"),
        row.getString("state"),
        row.getString("source_event"),
        row.getString("source_position"),
        RelayCausationCodec.read(row.getString("causation")),
        row.getObject("attempt", UUID.class),
        row.getObject("claim", UUID.class),
        row.getString("job"),
        row.getString("detail"),
        row.getTimestamp("updated_at").toInstant(),
        row.getBoolean("job_recorded"),
        row.getString("job_ending"));
  }
}
