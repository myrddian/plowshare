package io.aeyer.plowshare.server.messaging;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.MemoryIds;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.messaging.MessageService.Instance;
import io.aeyer.plowshare.server.messaging.MessageService.Route;
import io.aeyer.plowshare.server.relay.RelayCausationCodec;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns the messaging SQL and row codecs; coordination locks remain held until caller commit. */
public final class JdbcMessagingRepository implements MessagingRepository {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final JdbcTemplate jdbc;
  private final AllowanceRepository allowances;

  public JdbcMessagingRepository(JdbcTemplate jdbc) {
    this(jdbc, new JdbcAllowanceRepository(jdbc));
  }

  public JdbcMessagingRepository(JdbcTemplate jdbc, AllowanceRepository allowances) {
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

  private static String json(MessageIngress.Source value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (IOException invalid) {
      throw new IllegalStateException("cannot encode ingress provenance");
    }
  }

  private static MessageIngress.Source source(String value) {
    if (value == null) return null;
    try {
      return JSON.readValue(value, MessageIngress.Source.class);
    } catch (IOException invalid) {
      throw new IllegalStateException("invalid persisted ingress provenance");
    }
  }

  public Optional<Route> route(String message) {

    return jdbc
        .query(
            "SELECT message, sender, recipient, reply_to, reply_expected, final,"
                + " generated, ending, handled_at IS NOT NULL,relay_causation FROM message_routes WHERE message = ?",
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
                    rs.getBoolean(9),
                    RelayCausationCodec.read(rs.getString(10))),
            message)
        .stream()
        .findFirst();
  }

  @Override
  public Map<String, Admission> admissions(List<AdmissionRequest> messages) {
    var selected = List.copyOf(messages);
    if (selected.isEmpty()) return Map.of();
    if (selected.size() > 32) throw new IllegalArgumentException("Invalid message admission batch");
    return jdbc
        .query(
            connection -> {
              var statement =
                  connection.prepareStatement(
                      """
          SELECT r.message,r.sender,r.recipient,r.reply_to,r.reply_expected,r.final,r.generated,
                 r.ending,r.handled_at,r.relay_causation,CASE WHEN r.message=ANY(?) THEN m.body END AS inline_body,
                 i.account,i.project,i.agent,i.conversation,i.lifetime,i.active
          FROM message_routes r JOIN message_records m ON m.id=r.message
          JOIN message_instances i ON i.id=r.recipient WHERE r.message=ANY(?)
          """);
              statement.setArray(
                  1,
                  connection.createArrayOf(
                      "text",
                      selected.stream()
                          .filter(AdmissionRequest::inlineBody)
                          .map(AdmissionRequest::message)
                          .toArray()));
              statement.setArray(
                  2,
                  connection.createArrayOf(
                      "text", selected.stream().map(AdmissionRequest::message).toArray()));
              return statement;
            },
            (row, number) ->
                new Admission(
                    new Route(
                        row.getString("message"),
                        row.getString("sender"),
                        row.getString("recipient"),
                        row.getString("reply_to"),
                        row.getBoolean("reply_expected"),
                        row.getBoolean("final"),
                        row.getBoolean("generated"),
                        row.getString("ending"),
                        row.getTimestamp("handled_at") != null,
                        RelayCausationCodec.read(row.getString("relay_causation"))),
                    new Instance(
                        row.getString("recipient"),
                        row.getString("account"),
                        row.getString("project"),
                        row.getString("agent"),
                        row.getString("conversation"),
                        row.getString("lifetime"),
                        row.getBoolean("active")),
                    Optional.ofNullable(row.getString("inline_body"))))
        .stream()
        .collect(Collectors.toUnmodifiableMap(value -> value.route().message(), value -> value));
  }

  public List<String> retainedBindings(String account, String project, String name) {

    return jdbc.queryForList(
        "SELECT instance FROM message_route_bindings"
            + " WHERE account = ? AND source_project = ? AND route_name = ?",
        String.class,
        account,
        project,
        name);
  }

  public void bindRoute(String account, String project, String name, String instance) {

    changed(
        jdbc.update(
            "INSERT INTO message_route_bindings (account, source_project, route_name, instance) VALUES (?, ?, ?, ?)",
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
        "SELECT count(*) FROM message_instances WHERE account = ? AND project = ? AND active",
        Integer.class,
        account,
        project);
  }

  public void insertInstance(Instance instance, boolean isDefault) {

    changed(
        jdbc.update(
            "INSERT INTO message_instances (id, account, project, agent, conversation, lifetime, is_default) VALUES (?, ?, ?, ?, ?, ?, ?)",
            instance.id(),
            instance.account(),
            instance.project(),
            instance.agent(),
            instance.conversation(),
            instance.lifetime(),
            isDefault));
  }

  public void passiveSender(String id, String client) {

    changed(
        jdbc.update(
            "UPDATE message_instances SET active=FALSE,agent=? WHERE id=?",
            "external:" + client,
            id));
  }

  public void insertExternalContext(
      UUID context,
      String account,
      MessageIngress.Request request,
      String sender,
      String recipient) {

    changed(
        jdbc.update(
            "INSERT INTO message_external_contexts(id,account,project,channel,client,agent,sender,recipient) VALUES(?,?,?,?,?,?,?,?)",
            context,
            account,
            request.project(),
            request.channel(),
            request.client(),
            request.agent(),
            sender,
            recipient));
  }

  public List<String> replies(String message) {

    return jdbc.queryForList(
        "SELECT message FROM (SELECT r.message,b.posted_at FROM message_routes r JOIN message_records b ON b.id=r.message WHERE r.reply_to=? ORDER BY b.posted_at DESC,r.message DESC LIMIT 200) recent ORDER BY posted_at,message",
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
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT runnable FROM message_routes WHERE message=?", Boolean.class, id));
  }

  public Optional<PriorSend> priorSend(String sender, String call) {
    return jdbc
        .query(
            "SELECT message,send_fingerprint FROM message_routes WHERE sender=? AND call_id=?",
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
            "SELECT EXISTS(SELECT 1 FROM message_routes WHERE sender=? AND call_id LIKE ? AND send_fingerprint IS NULL)",
            Boolean.class,
            sender,
            turn + ":%"));
  }

  public boolean terminated(String message) {

    return jdbc.queryForObject(
        "SELECT termination IS NOT NULL FROM message_routes WHERE message = ?",
        Boolean.class,
        message);
  }

  public int pendingCount(String recipient) {

    return jdbc.queryForObject(
        "SELECT count(*) FROM message_routes" + " WHERE recipient = ? AND handled_at IS NULL",
        Integer.class,
        recipient);
  }

  public void insertRoute(
      Route route, String call, String fingerprint, int calls, boolean runnable) {
    if (calls < 1) throw new IllegalArgumentException("Positive message allowance required");
    var allowance = Budget.of(calls).snapshot();
    allowances.retain(allowance);

    changed(
        jdbc.update(
            """
            INSERT INTO message_routes (message,sender,recipient,reply_to,reply_expected,final,
              generated,ending,call_id,send_fingerprint,allowance_id,runnable,relay_causation)
            SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb FROM message_records b
            JOIN message_instances s ON s.id=? JOIN message_instances d ON d.id=b.recipient
            WHERE b.id=? AND b.recipient=? AND s.account=d.account
              AND (CAST(? AS text) IS NULL OR EXISTS(SELECT 1 FROM message_routes original
                WHERE original.message=? AND original.sender=d.id AND original.recipient=s.id))
            """,
            route.message(),
            route.sender(),
            route.recipient(),
            route.replyTo(),
            route.replyExpected(),
            route.finalReply(),
            route.generated(),
            route.ending(),
            call,
            fingerprint,
            allowance.id().value(),
            runnable,
            RelayCausationCodec.write(route.causation()),
            route.sender(),
            route.message(),
            route.recipient(),
            route.replyTo(),
            route.replyTo()));
  }

  public void deadline(String message, Instant deadline) {

    changed(
        jdbc.update(
            "UPDATE message_routes SET deadline_at = ? WHERE message = ?",
            Timestamp.from(deadline),
            message));
  }

  public void addAllowance(String message, int calls) {
    if (calls < 1) throw new IllegalArgumentException("allowance must be positive");
    allowances.add(allowance(message), calls);
  }

  public AllowanceId allowance(String message) {
    return new AllowanceId(
        jdbc.queryForObject(
            "SELECT allowance_id FROM message_routes WHERE message=?", UUID.class, message));
  }

  public MessageRecord insertMessage(String recipient, String body, Instant at) {
    var message = new MessageRecord(MemoryIds.mint("bdm_", at), recipient, body, at);
    changed(
        jdbc.update(
            "INSERT INTO message_records(id,recipient,body,posted_at) VALUES(?,?,?,?)",
            message.id(),
            message.recipient(),
            message.body(),
            Timestamp.from(at)));
    return message;
  }

  public Optional<MessageRecord> message(String id) {
    return jdbc
        .query(
            "SELECT id,recipient,body,posted_at FROM message_records WHERE id=?",
            (row, number) ->
                new MessageRecord(
                    row.getString("id"),
                    row.getString("recipient"),
                    row.getString("body"),
                    row.getTimestamp("posted_at").toInstant()),
            id)
        .stream()
        .findFirst();
  }

  public void published(String message) {
    changed(
        jdbc.update(
            "UPDATE message_routes SET published=true WHERE message=? AND NOT published", message));
  }

  public boolean isPublished(String message) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT published FROM message_routes WHERE message=?", Boolean.class, message));
  }

  public boolean admit(String message) {
    return jdbc.update(
            "UPDATE message_routes SET inbox_admitted=true WHERE message=? AND NOT inbox_admitted AND handled_at IS NULL AND runnable",
            message)
        == 1;
  }

  public void handled(String message) {

    changed(jdbc.update("UPDATE message_routes SET handled_at = now() WHERE message = ?", message));
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
        "SELECT message FROM message_routes WHERE message = ? FOR UPDATE", String.class, message);
  }

  public Optional<String> finalReply(String message) {

    return jdbc
        .queryForList(
            "SELECT message FROM message_routes WHERE reply_to = ? AND final",
            String.class,
            message)
        .stream()
        .findFirst();
  }

  public void awaiting(String message) {

    changed(jdbc.update("UPDATE message_routes SET awaiting = TRUE WHERE message = ?", message));
  }

  public void endRequest(String message, Outcome.Ending ending) {

    changed(
        jdbc.update(
            "UPDATE message_routes SET handled_at = now(), awaiting = FALSE, ending = COALESCE(ending, ?) WHERE message = ?",
            ending.name(),
            message));
  }

  public void deactivate(String id) {

    changed(jdbc.update("UPDATE message_instances SET active = FALSE WHERE id = ?", id));
  }

  public boolean otherStarted(String recipient, String message) {

    return jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM message_routes"
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
        "SELECT message FROM message_routes WHERE recipient = ?"
            + " AND started_at IS NOT NULL AND handled_at IS NULL FOR UPDATE",
        String.class,
        recipient);
  }

  public Instant deadline(String message) {

    return jdbc.queryForObject(
        "SELECT deadline_at FROM message_routes WHERE message = ?",
        (row, n) -> row.getTimestamp(1) == null ? null : row.getTimestamp(1).toInstant(),
        message);
  }

  public void started(String message) {

    changed(
        jdbc.update(
            "UPDATE message_routes SET started_at = COALESCE(started_at, now()), awaiting = FALSE WHERE message = ?",
            message));
  }

  public List<String> interrupted() {

    return jdbc.queryForList(
        "SELECT r.message FROM message_routes r"
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
            + " JOIN message_instances i ON i.conversation = a.conversation"
            + " JOIN message_routes r ON r.recipient = i.id"
            + " WHERE r.started_at IS NOT NULL AND r.handled_at IS NULL AND i.active"
            + " AND a.state IN ('allowed', 'denied') AND a.answered_at >= r.started_at"
            + " AND NOT EXISTS (SELECT 1 FROM firings f WHERE f.data->>'message_approval' = a.id)",
        String.class);
  }

  public Optional<String> instanceJob(String topic) {

    return jdbc
        .queryForList(
            "SELECT job_id FROM firings WHERE message_instance = ? AND status = 'started'"
                + " AND finished_at IS NULL AND job_id IS NOT NULL ORDER BY started_at DESC LIMIT 1",
            String.class,
            topic)
        .stream()
        .findFirst();
  }

  public boolean awaitingAny(String recipient) {

    return jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM message_routes"
            + " WHERE recipient = ? AND handled_at IS NULL AND awaiting)",
        Boolean.class,
        recipient);
  }

  public boolean openingDefault(String id) {

    return jdbc.queryForObject(
        "SELECT open_default FROM message_instances WHERE id = ?", Boolean.class, id);
  }

  public void clearDefault(String account, String project, String agent) {

    jdbc.update(
        "UPDATE message_instances SET is_default = FALSE WHERE account = ? AND project = ? AND agent = ? AND is_default",
        account,
        project,
        agent);
  }

  public void recordOpening(String id, String requestId, boolean makeDefault) {

    changed(
        jdbc.update(
            "UPDATE message_instances SET request_id = ?, open_default = ? WHERE id = ?",
            requestId,
            makeDefault,
            id));
  }

  public void makeDefault(String id) {

    changed(jdbc.update("UPDATE message_instances SET is_default = TRUE WHERE id = ?", id));
  }

  public List<String> deliveries(String id, int offset, int limit) {
    page(offset, limit);
    return jdbc.queryForList(
        "SELECT r.message FROM message_routes r JOIN message_records b ON b.id = r.message"
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
          SELECT r.*,b.posted_at,b.body FROM message_routes r
          JOIN message_records b ON b.id=r.message WHERE r.sender=? OR r.recipient=?
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
          SELECT r.reply_to,r.message FROM message_routes r JOIN route_page p ON p.message=r.reply_to WHERE r.final
        )
        SELECT p.*,s.account AS sender_account,s.project AS sender_project,s.agent AS sender_agent,
          s.conversation AS sender_conversation,s.lifetime AS sender_lifetime,
          s.active AS sender_active,t.account AS recipient_account,t.project AS recipient_project,
          t.agent AS recipient_agent,t.conversation AS recipient_conversation,
          t.lifetime AS recipient_lifetime,t.active AS recipient_active,j.job_id,
          run.message IS NOT NULL AS running,reply.message AS final_reply
        FROM route_page p JOIN message_instances s ON s.id=p.sender
          JOIN message_instances t ON t.id=p.recipient LEFT JOIN jobs j ON j.message=p.message
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
                        row.getTimestamp("handled_at") != null,
                        RelayCausationCodec.read(row.getString("relay_causation"))),
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

  private static Instance deliveryInstance(ResultSet row, String side) throws SQLException {
    // Only these two code-owned prefixes reach column lookup, never a caller's identifier.
    return new Instance(
        row.getString(side),
        row.getString(side + "_account"),
        row.getString(side + "_project"),
        row.getString(side + "_agent"),
        row.getString(side + "_conversation"),
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
            "UPDATE message_routes SET termination = ? WHERE message = ?",
            state.name().toLowerCase(Locale.ROOT),
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
            "UPDATE message_instances SET active = FALSE, is_default = FALSE, archived_at = CASE WHEN ? THEN COALESCE(archived_at, now()) ELSE archived_at END WHERE id = ?",
            archive,
            id));
  }

  public List<String> pending(String id) {

    return jdbc.queryForList(
        "SELECT message FROM message_routes WHERE recipient = ? AND handled_at IS NULL",
        String.class,
        id);
  }

  public List<String> expired(Instant now) {

    return jdbc.queryForList(
        "SELECT message FROM message_routes WHERE handled_at IS NULL"
            + " AND deadline_at <= ? ORDER BY deadline_at LIMIT 200",
        String.class,
        Timestamp.from(now));
  }

  private List<Instance> instances(String where, Object... args) {
    return jdbc.query(
        "SELECT id, account, project, agent, conversation, lifetime, active"
            + " FROM message_instances WHERE "
            + where,
        (rs, n) ->
            new Instance(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getBoolean(7)),
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
          SELECT id,account,project,agent,conversation,lifetime,active,is_default,
            archived_at IS NOT NULL AS archived,created_at
          FROM message_instances WHERE account=? AND project=? AND (? OR archived_at IS NULL)
          ORDER BY created_at,id OFFSET ? LIMIT ?
        ), route_counts AS (
          SELECT r.recipient,count(*) AS pending,bool_or(r.awaiting) AS awaiting
          FROM message_routes r JOIN instance_page p ON p.id=r.recipient
          WHERE r.handled_at IS NULL GROUP BY r.recipient
        ), jobs AS (
          SELECT DISTINCT ON(f.message_instance) f.message_instance,f.job_id
          FROM firings f JOIN instance_page p ON p.id=f.message_instance
          WHERE f.status='started' AND f.finished_at IS NULL AND f.job_id IS NOT NULL
          ORDER BY f.message_instance,f.started_at DESC,f.id
        )
        SELECT p.*,coalesce(r.pending,0) AS pending,coalesce(r.awaiting,false) AS awaiting,j.job_id
        FROM instance_page p LEFT JOIN route_counts r ON r.recipient=p.id
        LEFT JOIN jobs j ON j.message_instance=p.id ORDER BY p.created_at,p.id
        """,
            (row, number) ->
                new InstanceListing(
                    new Instance(
                        row.getString("id"),
                        row.getString("account"),
                        row.getString("project"),
                        row.getString("agent"),
                        row.getString("conversation"),
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
        "SELECT is_default, archived_at IS NOT NULL, created_at FROM message_instances WHERE id = ?",
        (row, n) ->
            new InstanceState(
                row.getBoolean(1), row.getBoolean(2), row.getTimestamp(3).toInstant()),
        id);
  }

  public DeliveryState deliveryState(String message) {
    return jdbc.queryForObject(
        "SELECT awaiting, termination, deadline_at FROM message_routes WHERE message = ?",
        (row, n) ->
            new DeliveryState(
                row.getBoolean(1),
                row.getString(2),
                row.getTimestamp(3) == null ? null : row.getTimestamp(3).toInstant()),
        message);
  }

  public Optional<ExternalPrior> priorExternal(String account, MessageIngress.Request request) {
    return jdbc
        .query(
            "SELECT t.id, t.request_context, t.body, t.command, c.agent, t.native_source = CAST(? AS jsonb) AS matches FROM message_external_tasks t JOIN message_external_contexts c ON c.id=t.context WHERE t.account=? AND t.project=? AND t.channel=? AND t.client=? AND t.request_id=?",
            (row, n) ->
                new ExternalPrior(
                    row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class),
                    row.getString(3),
                    row.getString(4),
                    row.getString(5),
                    row.getBoolean(6)),
            json(request.source()),
            account,
            request.project(),
            request.channel(),
            request.client(),
            request.requestId())
        .stream()
        .findFirst();
  }

  public Optional<ExternalContext> externalContext(
      UUID context, String account, MessageIngress.Request request) {
    return jdbc
        .query(
            "SELECT sender,recipient FROM message_external_contexts WHERE id=? AND account=? AND project=? AND channel=? AND client=? AND agent=?",
            (row, n) -> new ExternalContext(row.getString(1), row.getString(2)),
            context,
            account,
            request.project(),
            request.channel(),
            request.client(),
            request.agent())
        .stream()
        .findFirst();
  }

  public void insertExternalTask(
      UUID id, UUID context, String account, MessageIngress.Request request, String message) {
    changed(
        jdbc.update(
            "INSERT INTO message_external_tasks(id,context,account,project,channel,client,request_id,request_context,body,command,source,native_source,message) VALUES(?,?,?,?,?,?,?,?,?,?,'null'::jsonb,CAST(? AS jsonb),?)",
            id,
            context,
            account,
            request.project(),
            request.channel(),
            request.client(),
            request.requestId(),
            request.context(),
            request.body(),
            request.command(),
            json(request.source()),
            message));
  }

  public Optional<ExternalTask> externalTask(
      String account, String project, String channel, String client, UUID id) {
    return externalTask(account, project, channel, client, id, false);
  }

  @Override
  public Optional<ExternalTask> lockExternalTask(
      String account, String project, String channel, String client, UUID id) {
    return externalTask(account, project, channel, client, id, true);
  }

  private Optional<ExternalTask> externalTask(
      String account, String project, String channel, String client, UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT t.context,t.request_id,t.revision,c.agent,t.message,t.native_source,t.created_at FROM message_external_tasks t JOIN message_external_contexts c ON c.id=t.context WHERE t.id=? AND t.account=? AND t.project=? AND t.channel=? AND t.client=?"
                + (lock ? " FOR UPDATE OF t" : ""),
            (row, n) ->
                new ExternalTask(
                    row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class),
                    row.getLong(3),
                    row.getString(4),
                    row.getString(5),
                    source(row.getString(6)),
                    row.getTimestamp(7).toInstant()),
            id,
            account,
            project,
            channel,
            client)
        .stream()
        .findFirst();
  }
}
