package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/** Inbox ownership is a project FK, never an impersonated human or a shared service handle. */
public final class JdbcApplicationInboxStore implements ApplicationInboxStore {
  private static final String COLUMNS =
      "id,handle,kind,firing,conversation,ending,answer,arrived_at,read_at,about";
  private final JdbcTemplate jdbc;
  private final UnitOfWork work;

  public JdbcApplicationInboxStore(JdbcTemplate jdbc, UnitOfWork work) {
    this.jdbc = jdbc;
    this.work = work;
  }

  @Override
  public Optional<Long> project(String name) {
    return jdbc.queryForList("SELECT id FROM projects WHERE name=?", Long.class, name).stream()
        .findFirst();
  }

  @Override
  public Optional<String> backgroundProject(String source) {
    if (source == null) return Optional.empty();
    // A child's immediate caller is another conductor, even when its root was started in a
    // person's TURN. Resolve both ancestry chains as sets rather than following one SQL read
    // per parent. A truncated/cyclic chain has no root and cannot authorize a new destination.
    return jdbc
        .queryForList(
            """
        WITH RECURSIVE logs AS (
          SELECT id,parent_id,project_id,origin,1 AS depth FROM conversations WHERE id=?
          UNION ALL SELECT c.id,c.parent_id,c.project_id,c.origin,l.depth+1
          FROM logs l JOIN conversations c ON c.id=l.parent_id WHERE l.depth<64
        ), root_log AS (SELECT * FROM logs WHERE parent_id IS NULL), runs AS (
          SELECT o.id,o.parent,o.caller_conversation,1 AS depth FROM root_log l
          JOIN orchestrations o ON o.conductor_conversation=l.id
          UNION ALL SELECT o.id,o.parent,o.caller_conversation,r.depth+1
          FROM runs r JOIN orchestrations o ON o.id=r.parent WHERE r.depth<64
        ), root_run AS (SELECT * FROM runs WHERE parent IS NULL), caller_logs AS (
          SELECT c.id,c.parent_id,c.origin,1 AS depth FROM root_run r
          JOIN conversations c ON c.id=r.caller_conversation
          UNION ALL SELECT c.id,c.parent_id,c.origin,l.depth+1
          FROM caller_logs l JOIN conversations c ON c.id=l.parent_id WHERE l.depth<64
        ), caller_root AS (SELECT * FROM caller_logs WHERE parent_id IS NULL)
        SELECT p.name FROM root_log l JOIN projects p ON p.id=l.project_id
        LEFT JOIN root_run r ON true LEFT JOIN caller_root c ON true
        WHERE l.origin<>'turn' AND (l.origin<>'orchestration'
          OR (r.id IS NOT NULL AND (r.caller_conversation IS NULL OR c.origin<>'turn')))
        """,
            String.class,
            source)
        .stream()
        .findFirst();
  }

  @Override
  public List<String> append(
      long project, String key, Notice notice, List<String> managers, Instant at) {
    if (project < 1 || key == null || key.isBlank() || key.length() > 512)
      throw new IllegalArgumentException("Application and stable delivery key required");
    List<String> recipients = List.copyOf(managers);
    if (!notice.attention() && !recipients.isEmpty())
      throw new IllegalArgumentException("Routine application output has no personal recipients");
    return work.inTransaction(
        () -> {
          String id = identity(project, key, "application");
          int created =
              jdbc.update(
                  "INSERT INTO user_inbox(id,handle,kind,conversation,ending,answer,about,"
                      + "arrived_at,application_project_id,application_attention) VALUES(?,?,?,?,?,?,?,?,?,?) "
                      + "ON CONFLICT(id) DO NOTHING",
                  id,
                  notice.author(),
                  notice.kind(),
                  notice.source(),
                  notice.ending(),
                  notice.text(),
                  notice.about(),
                  Timestamp.from(at),
                  project,
                  notice.attention());
          if (created == 0) {
            Notice retained =
                jdbc.queryForObject(
                    "SELECT handle,kind,conversation,ending,answer,about,"
                        + "application_attention FROM user_inbox WHERE id=? AND application_project_id=?",
                    (row, n) ->
                        new Notice(
                            row.getString(1),
                            row.getString(2),
                            row.getString(3),
                            row.getString(4),
                            row.getString(5),
                            row.getString(6),
                            row.getBoolean(7)),
                    id,
                    project);
            if (!notice.equals(retained))
              throw new IllegalStateException("Conflicting application inbox delivery identity");
            return List.of();
          }
          var inserted = new ArrayList<String>();
          for (String manager : recipients.stream().distinct().toList()) {
            // The application publishes this output to its Managers; it does not grant access to
            // the private source log. Inspection of that log still uses ordinary log authorization.
            if (jdbc.update(
                    "INSERT INTO user_inbox(id,handle,kind,ending,answer,about,arrived_at,"
                        + "attention_copy_project,attention_copy_of) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
                    attentionIdentity(project, key, manager),
                    manager,
                    notice.kind().equals("run") ? "application" : notice.kind(),
                    notice.ending(),
                    notice.text(),
                    notice.about(),
                    Timestamp.from(at),
                    project,
                    id)
                == 1) inserted.add(manager);
          }
          return List.copyOf(inserted);
        });
  }

  private static String attentionIdentity(long project, String key, String account) {
    // Preserve existing attention IDs while separating the account named "application" from
    // the original row's namespace.
    return identity(
        project, key, account.equals("application") ? "\0attention:application" : account);
  }

  private static String identity(long project, String key, String recipient) {
    try {
      return "inb_app_"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          (project + "\0" + key + "\0" + recipient)
                              .getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required", impossible);
    }
  }

  @Override
  public InboxStore.Page page(long project, boolean unread, int offset, int limit) {
    if (project < 1 || offset < 0 || limit < 1 || limit > 200)
      throw new IllegalArgumentException("Invalid application inbox page");
    return jdbc.query(
        "WITH visible AS MATERIALIZED (SELECT id,arrived_at,read_at"
            + " FROM user_inbox WHERE application_project_id=? AND settled_at IS NULL), "
            + "page_keys AS (SELECT id,arrived_at FROM visible "
            + (unread ? "WHERE read_at IS NULL " : "")
            + "ORDER BY arrived_at DESC,id DESC OFFSET ? LIMIT ?), page AS (SELECT i."
            + COLUMNS.replace(",", ",i.")
            + " FROM page_keys selected JOIN user_inbox i ON i.id=selected.id) "
            + "SELECT (SELECT count(*) FROM visible WHERE read_at IS NULL) AS unread_total,page.* "
            + "FROM (SELECT 1) seed LEFT JOIN page ON true ORDER BY page.arrived_at DESC,page.id DESC",
        (ResultSetExtractor<InboxStore.Page>)
            rows -> {
              var items = new ArrayList<InboxItem>();
              int count = 0;
              while (rows.next()) {
                count = rows.getInt("unread_total");
                if (rows.getString("id") != null) items.add(item(rows));
              }
              return new InboxStore.Page(items, count);
            },
        project,
        offset,
        limit);
  }

  private static InboxItem item(ResultSet row) throws SQLException {
    Timestamp read = row.getTimestamp("read_at");
    return new InboxItem(
        row.getString("id"),
        row.getString("handle"),
        row.getString("kind"),
        row.getString("firing"),
        row.getString("conversation"),
        row.getString("ending"),
        row.getString("answer"),
        row.getTimestamp("arrived_at").toInstant(),
        read == null ? null : read.toInstant(),
        row.getString("about"));
  }

  @Override
  public boolean containsSubscriptionItem(long project, String item, String kind) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM user_inbox
          WHERE application_project_id=? AND id=? AND kind=? AND settled_at IS NULL AND about IS NULL)
        """,
            Boolean.class,
            project,
            item,
            kind));
  }

  @Override
  public Optional<InboxItem> subscriptionItem(long project, String item) {
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + " FROM user_inbox WHERE application_project_id=? AND id=?"
                + " AND settled_at IS NULL AND about IS NULL AND octet_length(answer)<=32768",
            (row, index) -> item(row),
            project,
            item)
        .stream()
        .findFirst();
  }

  @Override
  public int read(long project, List<String> ids, Instant at) {
    if (project < 1 || ids == null || ids.isEmpty() || ids.size() > 200)
      throw new IllegalArgumentException("Choose 1..200 application inbox items");
    return jdbc.update(
        "UPDATE user_inbox SET read_at=? WHERE application_project_id=? "
            + "AND read_at IS NULL AND settled_at IS NULL AND id=ANY(?)",
        statement -> {
          statement.setTimestamp(1, Timestamp.from(at));
          statement.setLong(2, project);
          statement.setArray(3, statement.getConnection().createArrayOf("text", ids.toArray()));
        });
  }

  @Override
  public int unread(long project) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM user_inbox "
                + "WHERE application_project_id=? AND read_at IS NULL AND settled_at IS NULL",
            Integer.class,
            project));
  }

  @Override
  public boolean claimQuestionAlert(
      long project, String key, String manager, Instant at, Duration retry) {
    return jdbc.update(
            "UPDATE user_inbox SET question_alert_attempted_at=? WHERE id=? AND handle=? "
                + "AND application_project_id IS NULL AND kind='approval' AND read_at IS NULL AND settled_at IS NULL "
                + "AND (question_alert_attempted_at IS NULL OR question_alert_attempted_at<=?)",
            Timestamp.from(at),
            attentionIdentity(project, key, manager),
            manager,
            Timestamp.from(at.minus(retry)))
        == 1;
  }

  @Override
  public boolean published(long project, String author, UUID request) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM user_inbox "
                + "WHERE application_project_id=? AND id=? AND handle=?)",
            Boolean.class,
            project,
            identity(project, "publication:" + author + ":" + request, "application"),
            author));
  }
}
