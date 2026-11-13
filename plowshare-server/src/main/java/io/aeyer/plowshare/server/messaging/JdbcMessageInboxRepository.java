package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.relay.Relay;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists identity under a unique agent binding; discovery uses a keyset to avoid starvation. */
public final class JdbcMessageInboxRepository implements MessageInboxRepository {
  private final JdbcTemplate jdbc;

  public JdbcMessageInboxRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public Inbox retain(String account, String project, String agent, String topic) {
    RelayPort.identity(account);
    if (Home.of(project).isGlobal()) throw new IllegalArgumentException("Inbox requires a project");
    RelayPort.identity(agent);
    RelayPort.topic(topic);
    var existing =
        jdbc
            .query(
                """
        SELECT account,project,project_id,agent,topic,retired_at IS NOT NULL AS retired
        FROM message_agent_inboxes WHERE account=? AND project=? AND agent=? FOR UPDATE
        """,
                (row, number) ->
                    new Retained(
                        new Inbox(
                            row.getString("account"),
                            row.getString("project"),
                            row.getString("agent"),
                            new Relay.SubscriptionKey(
                                new Relay.TopicKey(
                                    row.getLong("project_id"), row.getString("topic")),
                                "messaging.inbox")),
                        row.getBoolean("retired")),
                account,
                project,
                agent)
            .stream()
            .findFirst();
    if (existing.isPresent()) {
      var retained = existing.get();
      if (!topic.equals(retained.inbox().subscription().topic().name()))
        throw new IllegalStateException("Agent inbox identity changed");
      if (retained.retired())
        jdbc.update(
            "UPDATE message_agent_inboxes SET retired_at=NULL WHERE account=? AND project=? AND agent=?",
            account,
            project,
            agent);
      return retained.inbox();
    }
    // Only first creation races an INSERT. Equal active reuse above locks without writing a row
    // version; publication and retirement require that identity lock, not an unconditional refresh.
    return jdbc
        .query(
            """
        INSERT INTO message_agent_inboxes(account,project_id,project,agent,topic)
        SELECT ?,id,name,?,? FROM projects WHERE name=?
        ON CONFLICT(account,project_id,agent) DO UPDATE SET retired_at=NULL
        RETURNING account,project,project_id,agent,topic
        """,
            (row, number) ->
                new Inbox(
                    row.getString("account"),
                    row.getString("project"),
                    row.getString("agent"),
                    new Relay.SubscriptionKey(
                        new Relay.TopicKey(row.getLong("project_id"), row.getString("topic")),
                        "messaging.inbox")),
            account,
            agent,
            topic,
            project)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Messaging project is unavailable"));
  }

  private record Retained(Inbox inbox, boolean retired) {}

  @Override
  public List<Inbox> ready(String after, int limit) {
    if (limit < 1 || limit > 256)
      throw new IllegalArgumentException("Invalid inbox discovery bound");
    return jdbc.query(
        """
        SELECT i.* FROM message_agent_inboxes i
        JOIN relay_topics t ON t.project_id=i.project_id AND t.name=i.topic
        JOIN relay_subscriptions s ON s.scope_key=t.scope_key AND s.topic=t.name AND s.subscriber='messaging.inbox'
        WHERE i.retired_at IS NULL AND s.seen_through<t.last_position
          AND (CAST(? AS text) IS NULL OR i.project_id::text||'/'||i.topic>?)
        ORDER BY i.project_id::text||'/'||i.topic LIMIT ?
        """,
        (row, number) ->
            new Inbox(
                row.getString("account"),
                row.getString("project"),
                row.getString("agent"),
                new Relay.SubscriptionKey(
                    new Relay.TopicKey(row.getLong("project_id"), row.getString("topic")),
                    "messaging.inbox")),
        after,
        after,
        limit);
  }

  @Override
  public List<Inbox> drained(String after, int limit) {
    if (limit < 1 || limit > 256) throw new IllegalArgumentException("Invalid retirement bound");
    return jdbc.query(
        """
        SELECT i.* FROM message_agent_inboxes i
        JOIN relay_topics t ON t.project_id=i.project_id AND t.name=i.topic
        JOIN relay_subscriptions s ON s.scope_key=t.scope_key AND s.topic=t.name AND s.subscriber='messaging.inbox'
        WHERE i.retired_at IS NULL AND s.seen_through=t.last_position
          AND NOT EXISTS(SELECT 1 FROM message_instances n JOIN message_routes r ON r.recipient=n.id
            WHERE n.account=i.account AND n.project=i.project AND n.agent=i.agent AND r.handled_at IS NULL)
          AND (CAST(? AS text) IS NULL OR i.project_id::text||'/'||i.topic>?)
        ORDER BY i.project_id::text||'/'||i.topic LIMIT ?
        """,
        (row, number) ->
            new Inbox(
                row.getString("account"),
                row.getString("project"),
                row.getString("agent"),
                new Relay.SubscriptionKey(
                    new Relay.TopicKey(row.getLong("project_id"), row.getString("topic")),
                    "messaging.inbox")),
        after,
        after,
        limit);
  }

  @Override
  public boolean retire(Inbox inbox) {
    // The binding row serializes this change with publication's retain(), which reactivates a
    // restored definition. Recheck pending state after waiting; no existing debt can be abandoned.
    jdbc.queryForObject(
        "SELECT topic FROM message_agent_inboxes WHERE account=? AND project_id=? AND agent=? FOR UPDATE",
        String.class,
        inbox.account(),
        inbox.subscription().topic().projectId(),
        inbox.agent());
    return jdbc.update(
            """
        UPDATE message_agent_inboxes i SET retired_at=clock_timestamp()
        WHERE i.account=? AND i.project_id=? AND i.agent=? AND i.retired_at IS NULL
          AND EXISTS(SELECT 1 FROM relay_topics t JOIN relay_subscriptions s ON s.scope_key=t.scope_key AND s.topic=t.name
            WHERE t.project_id=i.project_id AND t.name=i.topic AND s.subscriber='messaging.inbox' AND s.seen_through=t.last_position)
          AND NOT EXISTS(SELECT 1 FROM message_instances n JOIN message_routes r ON r.recipient=n.id
            WHERE n.account=i.account AND n.project=i.project AND n.agent=i.agent AND r.handled_at IS NULL)
        """,
            inbox.account(),
            inbox.subscription().topic().projectId(),
            inbox.agent())
        == 1;
  }

  @Override
  public List<Inbox> retired(String after, int limit) {
    if (limit < 1 || limit > 256) throw new IllegalArgumentException("Invalid retirement bound");
    Long projectId = null;
    String topic = null;
    if (after != null) {
      int separator = after.indexOf('/');
      if (separator < 1) throw new IllegalArgumentException("Invalid retirement cursor");
      projectId = Long.valueOf(after.substring(0, separator));
      topic = RelayPort.topic(after.substring(separator + 1));
      if (projectId < 1) throw new IllegalArgumentException("Invalid retirement project cursor");
    }
    return jdbc.query(
        """
        SELECT account,project,project_id,agent,topic FROM message_agent_inboxes
        WHERE retired_at IS NOT NULL AND (CAST(? AS bigint) IS NULL OR (project_id,topic)>(?,?))
        ORDER BY project_id,topic LIMIT ?
        """,
        (row, number) ->
            new Inbox(
                row.getString("account"),
                row.getString("project"),
                row.getString("agent"),
                new Relay.SubscriptionKey(
                    new Relay.TopicKey(row.getLong("project_id"), row.getString("topic")),
                    "messaging.inbox")),
        projectId,
        projectId,
        topic,
        limit);
  }

  @Override
  public boolean lockRetired(Inbox inbox) {
    return !jdbc.query(
            """
        SELECT topic FROM message_agent_inboxes WHERE account=? AND project_id=? AND agent=?
          AND topic=? AND retired_at IS NOT NULL FOR UPDATE
        """,
            (row, index) -> row.getString(1),
            inbox.account(),
            inbox.subscription().topic().projectId(),
            inbox.agent(),
            inbox.subscription().topic().name())
        .isEmpty();
  }

  @Override
  public void forgetRetired(Inbox inbox) {
    int removed =
        jdbc.update(
            "DELETE FROM message_agent_inboxes WHERE account=? AND project_id=? AND agent=? AND topic=? AND retired_at IS NOT NULL",
            inbox.account(),
            inbox.subscription().topic().projectId(),
            inbox.agent(),
            inbox.subscription().topic().name());
    if (removed != 1)
      throw new IllegalStateException("Messaging retirement lost its locked binding");
  }
}
