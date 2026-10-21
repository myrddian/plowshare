package io.aeyer.plowshare.server.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.board.BoardMessaging.Instance;
import io.aeyer.plowshare.server.board.BoardMessaging.Route;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns the messaging SQL and row codecs; coordination locks remain held until caller commit. */
public final class JdbcBoardMessagingRepository implements BoardMessagingRepository {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private final JdbcTemplate jdbc;
  private final AllowanceRepository allowances;

  public JdbcBoardMessagingRepository(JdbcTemplate jdbc) {
    this(jdbc, new JdbcAllowanceRepository(jdbc));
  }

  public JdbcBoardMessagingRepository(JdbcTemplate jdbc, AllowanceRepository allowances) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.allowances = Objects.requireNonNull(allowances);
  }

  private static void changed(int count) {
    if (count != 1) throw new IllegalStateException("messaging transition lost its row");
  }

  private static void page(int offset, int limit) {
    if (offset < 0 || limit < 1 || limit > 201)
      throw new IllegalArgumentException("invalid messaging page");
  }

  private static String json(io.aeyer.plowshare.protocol.Incoming.Source value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("cannot encode ingress provenance", invalid);
    }
  }

  private static io.aeyer.plowshare.protocol.Incoming.Source source(String value) {
    try {
      return JSON.readValue(value, io.aeyer.plowshare.protocol.Incoming.Source.class);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("invalid persisted ingress provenance", invalid);
    }
  }

  public Optional<Route> route(String message) {

    return jdbc
        .query(
            "SELECT message, sender, recipient, reply_to, reply_expected, final,"
                + " generated, ending, handled_at IS NOT NULL FROM board_message_routes WHERE message = ?",
            (rs, n) ->
                new Route(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getBoolean(5),
                    rs.getBoolean(6),
                    rs.getBoolean(7),
                    rs.getString(8),
                    rs.getBoolean(9)),
            message)
        .stream()
        .findFirst();
  }

  public List<String> retainedBindings(String account, String project, String name) {

    return jdbc.queryForList(
        "SELECT instance FROM board_message_route_bindings"
            + " WHERE account = ? AND source_project = ? AND route_name = ?",
        String.class,
        account,
        project,
        name);
  }

  public void bindRoute(String account, String project, String name, String instance) {

    changed(
        jdbc.update(
            "INSERT INTO board_message_route_bindings (account, source_project, route_name, instance) VALUES (?, ?, ?, ?)",
            account,
            project,
            name,
            instance));
  }

  public void lockConversation(String id) {

    jdbc.queryForList("SELECT id FROM conversations WHERE id = ? FOR UPDATE", String.class, id);
  }

  public void lockAddress(String key) {
    if (key == null
        || key.length() > 4096
        || key.indexOf('\0') >= 0
        || !key.matches(
            "(?:source|default|mailbox|route|external|external-context|external-task|send|continuation):.+"))
      throw new IllegalArgumentException("invalid messaging coordination identity");
    jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, key);
  }

  public int activeInstances(String account, String project) {

    return jdbc.queryForObject(
        "SELECT count(*) FROM board_message_instances WHERE account = ? AND project = ? AND active",
        Integer.class,
        account,
        project);
  }

  public void insertInstance(Instance instance, boolean isDefault) {

    changed(
        jdbc.update(
            "INSERT INTO board_message_instances (id, account, project, agent, conversation, topic, lifetime, is_default) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            instance.id(),
            instance.account(),
            instance.project(),
            instance.agent(),
            instance.conversation(),
            instance.topic(),
            instance.lifetime(),
            isDefault));
  }

  public void passiveSender(String id, String client) {

    changed(
        jdbc.update(
            "UPDATE board_message_instances SET active=FALSE,agent=? WHERE id=?",
            "external:" + client,
            id));
  }

  public void insertExternalContext(
      java.util.UUID context,
      String account,
      io.aeyer.plowshare.protocol.Incoming.Receive request,
      String sender,
      String recipient) {

    changed(
        jdbc.update(
            "INSERT INTO message_external_contexts(id,account,project,client,agent,sender,recipient) VALUES(?,?,?,?,?,?,?)",
            context,
            account,
            request.project(),
            request.client(),
            request.agent(),
            sender,
            recipient));
  }

  public List<String> replies(String message) {

    return jdbc.queryForList(
        "SELECT message FROM (SELECT r.message,b.posted_at FROM board_message_routes r JOIN board_messages b ON b.id=r.message WHERE r.reply_to=? ORDER BY b.posted_at DESC,r.message DESC LIMIT 200) recent ORDER BY posted_at,message",
        String.class,
        message);
  }

  public int lastTurn(String conversation) {

    return jdbc.queryForObject(
        "SELECT COALESCE(max(turn_ordinal), 0) FROM entries WHERE conversation_id = ?",
        Integer.class,
        conversation);
  }

  public boolean queued(String id) {

    return jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM firings WHERE data->>'direct_message' = ?)",
        Boolean.class,
        id);
  }

  public Optional<PriorSend> priorSend(String sender, String call) {
    return jdbc
        .query(
            "SELECT message,send_fingerprint FROM board_message_routes WHERE sender=? AND call_id=?",
            (row, ordinal) ->
                new PriorSend(row.getString("message"), row.getString("send_fingerprint")),
            sender,
            call)
        .stream()
        .findFirst();
  }

  public boolean legacySendTurn(String sender, int turn) {
    if (turn < 0) throw new IllegalArgumentException("invalid handling turn");
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM board_message_routes WHERE sender=? AND call_id LIKE ? AND send_fingerprint IS NULL)",
            Boolean.class,
            sender,
            turn + ":%"));
  }

  public boolean terminated(String message) {

    return jdbc.queryForObject(
        "SELECT termination IS NOT NULL FROM board_message_routes WHERE message = ?",
        Boolean.class,
        message);
  }

  public int pendingCount(String recipient) {

    return jdbc.queryForObject(
        "SELECT count(*) FROM board_message_routes" + " WHERE recipient = ? AND handled_at IS NULL",
        Integer.class,
        recipient);
  }

  public void insertRoute(Route route, String call, String fingerprint) {

    changed(
        jdbc.update(
            "INSERT INTO board_message_routes (message, sender, recipient, reply_to, reply_expected, final, generated, ending, call_id, send_fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            route.message(),
            route.sender(),
            route.recipient(),
            route.replyTo(),
            route.replyExpected(),
            route.finalReply(),
            route.generated(),
            route.ending(),
            call,
            fingerprint));
  }

  public void deadline(String message, Instant deadline) {

    changed(
        jdbc.update(
            "UPDATE board_message_routes SET deadline_at = ? WHERE message = ?",
            java.sql.Timestamp.from(deadline),
            message));
  }

  public void addAllowance(String topic, int allowance) {
    if (allowance < 1) throw new IllegalArgumentException("allowance must be positive");
    java.util.UUID source =
        jdbc.queryForObject(
            "SELECT allowance_id FROM board_topics WHERE id=?", java.util.UUID.class, topic);
    allowances.add(new AllowanceId(source), allowance);
  }

  public void handled(String message) {

    changed(
        jdbc.update(
            "UPDATE board_message_routes SET handled_at = now() WHERE message = ?", message));
  }

  public Optional<String> externalCommand(String message) {

    return jdbc
        .queryForList(
            "SELECT command || ' ' || body FROM message_external_tasks WHERE message=? AND command IS NOT NULL",
            String.class,
            message)
        .stream()
        .findFirst();
  }

  public void lockRequest(String message) {

    jdbc.queryForObject(
        "SELECT message FROM board_message_routes WHERE message = ? FOR UPDATE",
        String.class,
        message);
  }

  public Optional<String> finalReply(String message) {

    return jdbc
        .queryForList(
            "SELECT message FROM board_message_routes WHERE reply_to = ? AND final",
            String.class,
            message)
        .stream()
        .findFirst();
  }

  public void awaiting(String message) {

    changed(
        jdbc.update("UPDATE board_message_routes SET awaiting = TRUE WHERE message = ?", message));
  }

  public void endRequest(String message, Outcome.Ending ending) {

    changed(
        jdbc.update(
            "UPDATE board_message_routes SET handled_at = now(), awaiting = FALSE, ending = COALESCE(ending, ?) WHERE message = ?",
            ending.name(),
            message));
  }

  public void deactivate(String id) {

    changed(jdbc.update("UPDATE board_message_instances SET active = FALSE WHERE id = ?", id));
  }

  public boolean otherStarted(String recipient, String message) {

    return jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM board_message_routes"
            + " WHERE recipient = ? AND message <> ? AND started_at IS NOT NULL AND handled_at IS NULL)",
        Boolean.class,
        recipient,
        message);
  }

  public boolean approvalQueued(String approval) {

    return jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM firings" + " WHERE data->>'message_approval' = ?)",
        Boolean.class,
        approval);
  }

  public List<String> lockHandling(String recipient) {

    return jdbc.queryForList(
        "SELECT message FROM board_message_routes WHERE recipient = ?"
            + " AND started_at IS NOT NULL AND handled_at IS NULL FOR UPDATE",
        String.class,
        recipient);
  }

  public Instant deadline(String message) {

    return jdbc.queryForObject(
        "SELECT deadline_at FROM board_message_routes WHERE message = ?",
        (row, n) -> row.getTimestamp(1) == null ? null : row.getTimestamp(1).toInstant(),
        message);
  }

  public void started(String message) {

    changed(
        jdbc.update(
            "UPDATE board_message_routes SET started_at = COALESCE(started_at, now()), awaiting = FALSE WHERE message = ?",
            message));
  }

  public List<String> interrupted() {

    return jdbc.queryForList(
        "SELECT r.message FROM board_message_routes r"
            + " JOIN firings f ON f.data->>'direct_message' = r.message"
            + " WHERE r.handled_at IS NULL AND f.status = 'started' AND f.finished_at IS NOT NULL"
            + " AND (f.reason IS NOT NULL OR (NOT r.awaiting AND NOT EXISTS (SELECT 1 FROM firings pending"
            + " WHERE pending.data->>'direct_message' = r.message AND (pending.status = 'queued'"
            + " OR (pending.status = 'started' AND pending.finished_at IS NULL)))))",
        String.class);
  }

  public List<String> answeredApprovals() {

    return jdbc.queryForList(
        "SELECT a.id FROM run_approvals a"
            + " JOIN board_message_instances i ON i.conversation = a.conversation"
            + " JOIN board_message_routes r ON r.recipient = i.id"
            + " WHERE r.started_at IS NOT NULL AND r.handled_at IS NULL AND i.active"
            + " AND a.state IN ('allowed', 'denied') AND a.answered_at >= r.started_at"
            + " AND NOT EXISTS (SELECT 1 FROM firings f WHERE f.data->>'message_approval' = a.id)",
        String.class);
  }

  public Optional<String> instanceJob(String topic) {

    return jdbc
        .queryForList(
            "SELECT job_id FROM firings WHERE topic = ? AND status = 'started'"
                + " AND finished_at IS NULL AND job_id IS NOT NULL ORDER BY started_at DESC LIMIT 1",
            String.class,
            topic)
        .stream()
        .findFirst();
  }

  public boolean awaitingAny(String recipient) {

    return jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM board_message_routes"
            + " WHERE recipient = ? AND handled_at IS NULL AND awaiting)",
        Boolean.class,
        recipient);
  }

  public boolean openingDefault(String id) {

    return jdbc.queryForObject(
        "SELECT open_default FROM board_message_instances WHERE id = ?", Boolean.class, id);
  }

  public void clearDefault(String account, String project, String agent) {

    jdbc.update(
        "UPDATE board_message_instances SET is_default = FALSE WHERE account = ? AND project = ? AND agent = ? AND is_default",
        account,
        project,
        agent);
  }

  public void recordOpening(String id, String requestId, boolean makeDefault) {

    changed(
        jdbc.update(
            "UPDATE board_message_instances SET request_id = ?, open_default = ? WHERE id = ?",
            requestId,
            makeDefault,
            id));
  }

  public void makeDefault(String id) {

    changed(jdbc.update("UPDATE board_message_instances SET is_default = TRUE WHERE id = ?", id));
  }

  public List<String> deliveries(String id, int offset, int limit) {
    page(offset, limit);
    return jdbc.queryForList(
        "SELECT r.message FROM board_message_routes r JOIN board_messages b ON b.id = r.message"
            + " WHERE r.sender = ? OR r.recipient = ? ORDER BY b.posted_at DESC, r.message DESC OFFSET ? LIMIT ?",
        String.class,
        id,
        id,
        offset,
        limit);
  }

  public List<DeliveryListing> deliveryPage(String id, int offset, int limit) {
    page(offset, limit);
    return List.copyOf(
        jdbc.query(
            """
        WITH route_page AS MATERIALIZED (
          SELECT r.*,b.posted_at,b.body FROM board_message_routes r
          JOIN board_messages b ON b.id=r.message WHERE r.sender=? OR r.recipient=?
          ORDER BY b.posted_at DESC,r.message DESC OFFSET ? LIMIT ?
        ), jobs AS (
          SELECT DISTINCT ON(p.message) p.message,f.job_id FROM firings f JOIN route_page p
          ON f.data->>'direct_message'=p.message
          WHERE f.job_id IS NOT NULL AND f.job_id<>'pending'
          ORDER BY p.message,f.started_at DESC NULLS LAST,f.id DESC
        ), running AS (
          SELECT DISTINCT p.message FROM firings f JOIN route_page p ON f.data->>'direct_message'=p.message
          WHERE f.status='started' AND f.finished_at IS NULL AND f.job_id IS NOT NULL
        ), final_replies AS (
          SELECT r.reply_to,r.message FROM board_message_routes r JOIN route_page p ON p.message=r.reply_to WHERE r.final
        )
        SELECT p.*,s.account AS sender_account,s.project AS sender_project,s.agent AS sender_agent,
          s.conversation AS sender_conversation,s.topic AS sender_topic,s.lifetime AS sender_lifetime,
          s.active AS sender_active,t.account AS recipient_account,t.project AS recipient_project,
          t.agent AS recipient_agent,t.conversation AS recipient_conversation,t.topic AS recipient_topic,
          t.lifetime AS recipient_lifetime,t.active AS recipient_active,j.job_id,
          run.message IS NOT NULL AS running,reply.message AS final_reply
        FROM route_page p JOIN board_message_instances s ON s.id=p.sender
          JOIN board_message_instances t ON t.id=p.recipient LEFT JOIN jobs j ON j.message=p.message
          LEFT JOIN running run ON run.message=p.message LEFT JOIN final_replies reply ON reply.reply_to=p.message
        ORDER BY p.posted_at DESC,p.message DESC
        """,
            (row, number) ->
                new DeliveryListing(
                    new Route(
                        row.getString("message"),
                        row.getString("sender"),
                        row.getString("recipient"),
                        row.getString("reply_to"),
                        row.getBoolean("reply_expected"),
                        row.getBoolean("final"),
                        row.getBoolean("generated"),
                        row.getString("ending"),
                        row.getTimestamp("handled_at") != null),
                    deliveryInstance(row, "sender"),
                    deliveryInstance(row, "recipient"),
                    new DeliveryState(
                        row.getBoolean("awaiting"),
                        row.getString("termination"),
                        row.getTimestamp("deadline_at") == null
                            ? null
                            : row.getTimestamp("deadline_at").toInstant()),
                    Optional.ofNullable(row.getString("job_id")),
                    row.getBoolean("running"),
                    Optional.ofNullable(row.getString("final_reply")),
                    row.getTimestamp("posted_at").toInstant(),
                    row.getString("body")),
            id,
            id,
            offset,
            limit));
  }

  private static Instance deliveryInstance(java.sql.ResultSet row, String side)
      throws java.sql.SQLException {
    // Only these two code-owned prefixes reach column lookup, never a caller's identifier.
    return new Instance(
        row.getString(side),
        row.getString(side + "_account"),
        row.getString(side + "_project"),
        row.getString(side + "_agent"),
        row.getString(side + "_conversation"),
        row.getString(side + "_topic"),
        row.getString(side + "_lifetime"),
        row.getBoolean(side + "_active"));
  }

  public Optional<String> messageJob(String message) {

    return jdbc
        .queryForList(
            "SELECT job_id FROM firings WHERE data->>'direct_message' = ?"
                + " AND job_id IS NOT NULL AND job_id <> 'pending' ORDER BY started_at DESC NULLS LAST, id DESC LIMIT 1",
            String.class,
            message)
        .stream()
        .findFirst();
  }

  public void terminate(String message, Termination state) {

    changed(
        jdbc.update(
            "UPDATE board_message_routes SET termination = ? WHERE message = ?",
            state.name().toLowerCase(java.util.Locale.ROOT),
            message));
  }

  public void refuseQueued(String message, String reason) {

    jdbc.update(
        "UPDATE firings SET status = 'refused', reason = ? WHERE data->>'direct_message' = ? AND status = 'queued'",
        reason,
        message);
  }

  public List<String> runningJobs(String message) {

    return jdbc.queryForList(
        "SELECT job_id FROM firings WHERE data->>'direct_message' = ? AND status = 'started'"
            + " AND finished_at IS NULL AND job_id IS NOT NULL",
        String.class,
        message);
  }

  public void stop(String id, boolean archive) {

    changed(
        jdbc.update(
            "UPDATE board_message_instances SET active = FALSE, is_default = FALSE, archived_at = CASE WHEN ? THEN COALESCE(archived_at, now()) ELSE archived_at END WHERE id = ?",
            archive,
            id));
  }

  public List<String> pending(String id) {

    return jdbc.queryForList(
        "SELECT message FROM board_message_routes WHERE recipient = ? AND handled_at IS NULL",
        String.class,
        id);
  }

  public List<String> expired(Instant now) {

    return jdbc.queryForList(
        "SELECT message FROM board_message_routes WHERE handled_at IS NULL"
            + " AND deadline_at <= ? ORDER BY deadline_at LIMIT 200",
        String.class,
        java.sql.Timestamp.from(now));
  }

  private List<Instance> instances(String where, Object... args) {
    return jdbc.query(
        "SELECT id, account, project, agent, conversation, topic, lifetime, active"
            + " FROM board_message_instances WHERE "
            + where,
        (rs, n) ->
            new Instance(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getBoolean(8)),
        args);
  }

  public Optional<Instance> instance(String id) {
    return instances("id = ?", id).stream().findFirst();
  }

  public List<Instance> byConversationAgent(String conversation, String agent) {
    return instances("conversation = ? AND agent = ?", conversation, agent);
  }

  public List<Instance> byConversation(String conversation) {
    return instances("conversation = ? ORDER BY id", conversation);
  }

  public List<Instance> transport(String conversation) {
    return instances("conversation = ? AND lifetime <> 'caller'", conversation);
  }

  public List<Instance> defaults(String account, String project, String agent) {
    return instances(
        "account = ? AND project = ? AND agent = ? AND is_default", account, project, agent);
  }

  public List<Instance> opening(String account, String project, String requestId) {
    return instances("account = ? AND project = ? AND request_id = ?", account, project, requestId);
  }

  public List<InstanceListing> listing(
      String account, String project, boolean archived, int offset, int limit) {
    page(offset, limit);
    // Bound raw instance rows before aggregation/visibility filtering, including the caller's
    // lookahead row. Routes and started jobs are read in sets, never from an instance row mapper.
    return List.copyOf(
        jdbc.query(
            """
        WITH instance_page AS MATERIALIZED (
          SELECT id,account,project,agent,conversation,topic,lifetime,active,is_default,
            archived_at IS NOT NULL AS archived,created_at
          FROM board_message_instances WHERE account=? AND project=? AND (? OR archived_at IS NULL)
          ORDER BY created_at,id OFFSET ? LIMIT ?
        ), route_counts AS (
          SELECT r.recipient,count(*) AS pending,bool_or(r.awaiting) AS awaiting
          FROM board_message_routes r JOIN instance_page p ON p.id=r.recipient
          WHERE r.handled_at IS NULL GROUP BY r.recipient
        ), jobs AS (
          SELECT DISTINCT ON(f.topic) f.topic,f.job_id
          FROM firings f JOIN instance_page p ON p.topic=f.topic
          WHERE f.status='started' AND f.finished_at IS NULL AND f.job_id IS NOT NULL
          ORDER BY f.topic,f.started_at DESC,f.id
        )
        SELECT p.*,coalesce(r.pending,0) AS pending,coalesce(r.awaiting,false) AS awaiting,j.job_id
        FROM instance_page p LEFT JOIN route_counts r ON r.recipient=p.id
        LEFT JOIN jobs j ON j.topic=p.topic ORDER BY p.created_at,p.id
        """,
            (row, number) ->
                new InstanceListing(
                    new Instance(
                        row.getString("id"),
                        row.getString("account"),
                        row.getString("project"),
                        row.getString("agent"),
                        row.getString("conversation"),
                        row.getString("topic"),
                        row.getString("lifetime"),
                        row.getBoolean("active")),
                    new InstanceState(
                        row.getBoolean("is_default"),
                        row.getBoolean("archived"),
                        row.getTimestamp("created_at").toInstant()),
                    row.getBoolean("awaiting"),
                    row.getInt("pending"),
                    Optional.ofNullable(row.getString("job_id"))),
            account,
            project,
            archived,
            offset,
            limit));
  }

  public InstanceState instanceState(String id) {
    return jdbc.queryForObject(
        "SELECT is_default, archived_at IS NOT NULL, created_at FROM board_message_instances WHERE id = ?",
        (row, n) ->
            new InstanceState(
                row.getBoolean(1), row.getBoolean(2), row.getTimestamp(3).toInstant()),
        id);
  }

  public DeliveryState deliveryState(String message) {
    return jdbc.queryForObject(
        "SELECT awaiting, termination, deadline_at FROM board_message_routes WHERE message = ?",
        (row, n) ->
            new DeliveryState(
                row.getBoolean(1),
                row.getString(2),
                row.getTimestamp(3) == null ? null : row.getTimestamp(3).toInstant()),
        message);
  }

  public Optional<ExternalPrior> priorExternal(
      String account, io.aeyer.plowshare.protocol.Incoming.Receive request) {
    return jdbc
        .query(
            "SELECT t.id, t.request_context, t.body, t.command, c.agent, t.source = CAST(? AS jsonb) AS matches FROM message_external_tasks t JOIN message_external_contexts c ON c.id=t.context WHERE t.account=? AND t.project=? AND t.client=? AND t.request_id=?",
            (row, n) ->
                new ExternalPrior(
                    row.getObject(1, java.util.UUID.class),
                    row.getObject(2, java.util.UUID.class),
                    row.getString(3),
                    row.getString(4),
                    row.getString(5),
                    row.getBoolean(6)),
            json(request.source()),
            account,
            request.project(),
            request.client(),
            request.requestId())
        .stream()
        .findFirst();
  }

  public Optional<ExternalContext> externalContext(
      java.util.UUID context,
      String account,
      io.aeyer.plowshare.protocol.Incoming.Receive request) {
    return jdbc
        .query(
            "SELECT sender,recipient FROM message_external_contexts WHERE id=? AND account=? AND project=? AND client=? AND agent=?",
            (row, n) -> new ExternalContext(row.getString(1), row.getString(2)),
            context,
            account,
            request.project(),
            request.client(),
            request.agent())
        .stream()
        .findFirst();
  }

  public void insertExternalTask(
      java.util.UUID id,
      java.util.UUID context,
      String account,
      io.aeyer.plowshare.protocol.Incoming.Receive request,
      String message) {
    changed(
        jdbc.update(
            "INSERT INTO message_external_tasks(id,context,account,project,client,request_id,request_context,body,command,source,message) VALUES(?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?)",
            id,
            context,
            account,
            request.project(),
            request.client(),
            request.requestId(),
            request.context(),
            request.body(),
            request.command(),
            json(request.source()),
            message));
  }

  public Optional<ExternalTask> externalTask(
      String account, String project, String client, java.util.UUID id) {
    return jdbc
        .query(
            "SELECT t.context,c.agent,t.message,t.source,t.created_at FROM message_external_tasks t JOIN message_external_contexts c ON c.id=t.context WHERE t.id=? AND t.account=? AND t.project=? AND t.client=?",
            (row, n) ->
                new ExternalTask(
                    row.getObject(1, java.util.UUID.class),
                    row.getString(2),
                    row.getString(3),
                    source(row.getString(4)),
                    row.getTimestamp(5).toInstant()),
            id,
            account,
            project,
            client)
        .stream()
        .findFirst();
  }
}
