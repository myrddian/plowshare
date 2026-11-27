package io.aeyer.plowshare.server.goals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.events.ScheduleSql;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.goals.ConversationGoals.Reason;
import io.aeyer.plowshare.server.goals.ConversationGoals.State;
import io.aeyer.plowshare.server.goals.GoalAgents.Lifecycle;
import io.aeyer.plowshare.server.goals.GoalAgents.Listing;
import io.aeyer.plowshare.server.goals.GoalAgents.Page;
import io.aeyer.plowshare.server.goals.GoalAgents.ReceiptRequest;
import io.aeyer.plowshare.server.goals.GoalAgents.RelayWake;
import io.aeyer.plowshare.server.goals.GoalAgents.Result;
import io.aeyer.plowshare.server.goals.GoalAgents.ScheduleWake;
import io.aeyer.plowshare.server.goals.GoalAgents.Started;
import io.aeyer.plowshare.server.goals.GoalAgents.Status;
import io.aeyer.plowshare.server.goals.GoalAgents.StatusRequest;
import io.aeyer.plowshare.server.information.InformationLogSql;
import io.aeyer.plowshare.server.information.InformationLogVisibility;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;

final class JdbcGoalDiscoveryStore implements GoalDiscoveryStore {
  private static final int PAGE_OBJECTIVE_CHARS = 512;
  private static final int FULL_OBJECTIVE_CHARS = 16_000;
  private static final int WAKE_LIMIT = 16;
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final TypeReference<List<ScheduleWake>> SCHEDULES = new TypeReference<>() {};
  private static final TypeReference<List<RelayWake>> LISTENERS = new TypeReference<>() {};
  private static final TypeReference<List<String>> EVIDENCE = new TypeReference<>() {};

  // Authorize the exact current project in the same statement that reads the rows. These are the
  // direct-USER branches of the central project-role check. Resolve the live role once per project.
  // Goal and conversation ownership are independent of the retained execution grant.
  private static final String SCOPE =
      """
      scoped_project AS MATERIALIZED (
        SELECT p.id,p.name,a.handle
        FROM projects p JOIN admins a ON a.handle=? AND a.account_kind='USER'
        WHERE %s AND account_active(a.handle)
          AND (p.personal_owner IS NULL OR p.personal_owner=a.handle)
          AND (p.name NOT LIKE 'personal:%%' OR p.name=?)
          AND CASE WHEN p.name LIKE 'client:%%' THEN EXISTS (
            SELECT 1 FROM project_members m WHERE m.project_id=p.id AND m.handle=a.handle)
            ELSE authority_project_role(p.name,a.handle) IS NOT NULL END
      ), scoped_goal AS NOT MATERIALIZED (
        SELECT g.id,g.conversation,g.instance,g.agent,g.objective,g.state,g.reason,g.detail,
          g.version,g.calling_agent,g.calling_conversation,g.created_order,p.name AS project,
          p.id AS project_id,p.handle
        FROM scoped_project p
        JOIN conversation_goals g ON g.executor=p.handle AND g.instance IS NOT NULL
        JOIN conversations c ON c.id=g.conversation AND c.project_id=p.id
        JOIN message_instances i ON i.id=g.instance AND i.conversation=c.id AND i.account=p.handle
          AND i.project=p.name AND i.agent=g.agent AND i.lifetime='persistent' AND NOT i.is_default
      )
      """;

  private static final String PROJECT_SCOPE = "WITH " + SCOPE.formatted("p.name=?");
  private static final String CONVERSATION_SCOPE =
      "WITH requested(log) AS MATERIALIZED (SELECT unnest(?::text[])),"
          + SCOPE.formatted(
              "p.id IN (SELECT project_id FROM conversations JOIN requested ON id=log)");

  // Old handoffs did not inherit the originating conversation's input-log edges.
  private static final String READABLE_SELECTION =
      """
      SELECT *, %s AND (calling_agent IS NULL OR (
        calling_conversation IS NOT NULL
        AND EXISTS (SELECT 1 FROM conversations parent
          WHERE parent.id=calling_conversation AND parent.project_id=picked.project_id)
        AND %s)) AS readable
      FROM picked
      """;

  private static final String BATCH_READABLE =
      READABLE_SELECTION.formatted(
          "conversation NOT IN (SELECT log FROM denied)",
          "calling_conversation NOT IN (SELECT log FROM denied)");

  // Only the current eligible topic and its exact Goal reader group disclose the source label.
  private static final String LIVE_RELAY_LABEL =
      """
      SELECT t.name FROM relay_topics t JOIN relay_subscriptions s
        ON s.scope_key=t.scope_key AND s.topic=t.name
      WHERE t.project_id=g.project_id AND l.project_id=g.project_id
        AND t.name=l.topic AND t.generation=l.generation
        AND (t.topic_owner='GENERAL_PURPOSE' OR t.goal_feed)
        AND t.payload_kind IN ('EMPTY','TEXT')
        AND s.subscriber='goal.'||l.id::text AND s.generation=l.cursor_generation
        AND s.reader_account IS NULL
      """;

  private static final String LIST_SELECTION =
      """
      SELECT * FROM scoped_goal
      WHERE (?::uuid IS NULL OR created_order<(
          SELECT created_order FROM scoped_goal WHERE id=?))
      ORDER BY created_order DESC LIMIT ?
      """;
  private static final String STATUS_SELECTION = "SELECT * FROM scoped_goal WHERE id=?";

  private static final String STATUS =
      """
      , picked AS MATERIALIZED (%s), denied AS MATERIALIZED (%s),
      visible AS MATERIALIZED (%s)
      SELECT g.id,g.project,g.conversation,g.instance,g.version,g.state,g.reason,g.readable,
        CASE WHEN g.readable THEN g.agent END AS agent,
        CASE WHEN g.readable THEN left(g.objective,?) END AS objective,
        CASE WHEN g.readable THEN g.detail END AS detail,
        (EXISTS (SELECT 1 FROM goal_schedule_bindings b WHERE b.goal=g.id)
          OR EXISTS (SELECT 1 FROM goal_agent_listeners l WHERE l.goal=g.id AND l.removed_at IS NULL))
          AS standing,
        r.job AS result_job,r.turn AS result_turn,r.summary AS result_summary,
        to_jsonb(r.evidence)::text AS result_evidence,
        wakes.next_at,
        wakes.items::text AS schedules,wakes.more AS more_schedules,
        listeners.items::text AS relay_listeners,listeners.more AS more_relay_listeners
      FROM visible g
      LEFT JOIN goal_agent_latest_results r ON r.goal=g.id AND g.readable
      LEFT JOIN LATERAL (
        WITH registered AS MATERIALIZED (
          SELECT b.schedule,b.generation,b.paused,
            CASE WHEN NOT b.paused THEN timer.next_fire_at END AS next_at
          FROM goal_schedule_bindings b
          JOIN schedule_files f ON f.internal_name=b.schedule AND f.generation=b.generation
          LEFT JOIN schedules timer ON timer.name=b.schedule AND timer.file_managed
            AND timer.emits=f.internal_name AND timer.defined_by=g.handle
            AND timer.cron=f.definition->>'cron' AND NOT timer.paused
            AND goal_schedule_timer_zone_matches(f.definition->>'zone',timer.zone)
            AND %s
          WHERE g.readable AND b.goal=g.id
        ), bounded AS (
          SELECT schedule,generation,paused,next_at AS "nextFireAt",
                 row_number() OVER (ORDER BY schedule) AS ordinal
          FROM registered ORDER BY schedule LIMIT %d
        )
        SELECT (SELECT min(next_at) FROM registered) AS next_at,
          jsonb_agg(to_jsonb(bounded)-'ordinal' ORDER BY ordinal)
            FILTER (WHERE ordinal<=%d) AS items,count(*)>%d AS more
        FROM bounded
      ) wakes ON TRUE
      LEFT JOIN LATERAL (
        WITH bounded AS MATERIALIZED (
          SELECT l.id,l.project_id,l.topic,l.generation,l.failure,l.cursor_generation,
                 row_number() OVER (ORDER BY l.id) AS ordinal
          FROM goal_agent_listeners l
          WHERE g.readable AND l.goal=g.id AND l.removed_at IS NULL
          ORDER BY l.id LIMIT %d
        ), wake AS (
          SELECT l.id AS binding,live.name AS topic,l.generation,l.failure,l.ordinal,
            CASE WHEN pending.id IS NOT NULL THEN to_jsonb(pending) END AS "pendingBatch"
          FROM bounded l
          LEFT JOIN LATERAL (%s) live ON TRUE
          LEFT JOIN LATERAL (
            SELECT b.id,b.state,b.member_count AS "memberCount",b.deadline
            FROM goal_agent_relay_batches b
            WHERE b.listener=l.id AND l.failure IS NULL AND live.name IS NOT NULL
              AND b.state IN ('OPEN','READY') AND b.member_count>0
            ORDER BY b.first_sequence LIMIT 1
          ) pending ON TRUE
        )
        SELECT jsonb_agg(to_jsonb(wake)-'ordinal' ORDER BY ordinal)
          FILTER (WHERE ordinal<=%d) AS items,count(*)>%d AS more
        FROM wake
      ) listeners ON TRUE
      ORDER BY g.created_order DESC
      """;

  private final JdbcTemplate jdbc;
  private final SqlWorkBudget sql;
  private final Duration ceiling;
  private final InformationLogVisibility visibility;

  JdbcGoalDiscoveryStore(
      JdbcTemplate jdbc,
      SqlWorkBudget sql,
      SqlWorkBudgetProperties budgets,
      InformationLogVisibility visibility) {
    this.jdbc = jdbc;
    this.sql = sql;
    this.ceiling = budgets.queryCeiling();
    this.visibility = visibility;
  }

  @Override
  public Page list(String account, Listing request) {
    var rows =
        select(
            account,
            request.project(),
            LIST_SELECTION,
            PAGE_OBJECTIVE_CHARS,
            request.after(),
            request.after(),
            request.limit() + 1);
    if (rows.size() <= request.limit()) return new Page(rows, null);
    return new Page(rows.subList(0, request.limit()), rows.get(request.limit() - 1).goal());
  }

  @Override
  public Optional<Status> status(String account, StatusRequest request) {
    return select(
            account, request.project(), STATUS_SELECTION, FULL_OBJECTIVE_CHARS, request.goal())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Started> receipt(String account, ReceiptRequest request) {
    return sql.within(
        ceiling,
        () ->
            jdbc
                .query(
                    PROJECT_SCOPE
                        + """
                        SELECT g.id,g.conversation,g.instance,r.request
                        FROM scoped_goal g JOIN goal_agent_launch_requests r
                          ON r.goal=g.id AND r.executor=g.handle
                        WHERE r.request=?
                        """,
                    (row, index) ->
                        new Started(
                            row.getObject("id", UUID.class),
                            row.getString("conversation"),
                            row.getString("instance"),
                            1,
                            row.getObject("request", UUID.class)),
                    account,
                    request.project(),
                    PersonalSpaces.name(account),
                    request.requestId())
                .stream()
                .findFirst());
  }

  private List<Status> select(
      String account, String project, String selection, int objectiveChars, Object... selectors) {
    return visibility.read(
        account,
        facts -> {
          var denied = InformationLogSql.unreadable(account, facts);
          var args = new ArrayList<>();
          args.addAll(List.of(account, project, PersonalSpaces.name(account)));
          for (Object selected : selectors) args.add(selected);
          args.addAll(denied.arguments());
          args.add(objectiveChars);
          var statement =
              PROJECT_SCOPE
                  + STATUS.formatted(
                      selection,
                      denied.sql(),
                      BATCH_READABLE,
                      ScheduleSql.emitterAuthorized("timer"),
                      WAKE_LIMIT + 1,
                      WAKE_LIMIT,
                      WAKE_LIMIT,
                      WAKE_LIMIT + 1,
                      LIVE_RELAY_LABEL,
                      WAKE_LIMIT,
                      WAKE_LIMIT);
          return sql.within(ceiling, () -> jdbc.query(statement, this::status, args.toArray()));
        });
  }

  @Override
  public Visibility visibility(String account, String conversation) {
    return selectVisibility(account, List.of(conversation)).getFirst();
  }

  @Override
  public void requireLogs(String account, List<String> conversations) {
    if (conversations.isEmpty()) return;
    if (conversations.size() > 256) throw new CallerFault("Too many conversations to follow");
    if (selectVisibility(account, conversations).stream()
        .anyMatch(access -> access == Visibility.UNAVAILABLE || access == Visibility.RESTRICTED))
      throw new NotFoundFault("Goal Agent conversation is unavailable");
  }

  private List<Visibility> selectVisibility(String account, List<String> conversations) {
    // Ordinary logs keep their existing scope and never enter the retained-input walk here.
    String principal = account == null ? "" : account;
    var query =
        CONVERSATION_SCOPE
            + """
        , picked AS MATERIALIZED (SELECT * FROM scoped_goal WHERE conversation IN (SELECT log FROM requested))
        """
            + """
        , checked AS MATERIALIZED (%s)
        SELECT CASE
          WHEN EXISTS (SELECT 1 FROM checked WHERE conversation=req.log AND readable) THEN 'READABLE'
          WHEN EXISTS (SELECT 1 FROM picked WHERE conversation=req.log) THEN 'RESTRICTED'
          WHEN EXISTS (SELECT 1 FROM conversation_goals
            WHERE conversation=req.log AND instance IS NOT NULL) THEN 'UNAVAILABLE'
          ELSE 'ORDINARY' END
        FROM requested req
        """
                .formatted(
                    READABLE_SELECTION.formatted(
                        "information_log_readable(conversation,handle)",
                        "information_log_readable(calling_conversation,handle)"));
    return sql.within(
        ceiling,
        () ->
            jdbc.query(
                query,
                (row, index) -> Visibility.valueOf(row.getString(1)),
                new SqlArrayValue("text", conversations.toArray()),
                principal,
                account == null ? "" : PersonalSpaces.name(account)));
  }

  @Override
  public void requireDisclosure(String account, String project, List<Disclosure> goals) {
    if (goals.isEmpty()) return;
    var ids = goals.stream().map(Disclosure::goal).toArray();
    if (goals.stream().noneMatch(Disclosure::readable)) {
      var owned =
          sql.within(
              ceiling,
              () ->
                  jdbc.queryForObject(
                      PROJECT_SCOPE + "SELECT count(*) FROM scoped_goal WHERE id=ANY(?::uuid[])",
                      Integer.class,
                      account,
                      project,
                      PersonalSpaces.name(account),
                      new SqlArrayValue("uuid", ids)));
      if (owned == null || owned != goals.size())
        throw new NotFoundFault("Goal Agent reply is unavailable");
      return;
    }
    var protectedGoals = goals.stream().map(Disclosure::readable).toArray();
    var namedListeners = goals.stream().flatMap(goal -> goal.namedListeners().stream()).toArray();
    visibility.read(
        account,
        facts -> {
          var denied = InformationLogSql.unreadable(account, facts);
          var args =
              new ArrayList<Object>(
                  List.of(
                      account,
                      project,
                      PersonalSpaces.name(account),
                      new SqlArrayValue("uuid", ids),
                      new SqlArrayValue("boolean", protectedGoals)));
          args.addAll(denied.arguments());
          args.add(goals.size());
          args.add(new SqlArrayValue("uuid", namedListeners));
          args.add(namedListeners.length);
          var query =
              PROJECT_SCOPE
                  + """
              , picked AS MATERIALIZED (
                  SELECT g.*, requested.protected FROM scoped_goal g
                  JOIN unnest(?::uuid[],?::boolean[]) AS requested(id,protected) ON requested.id=g.id
                ),
                denied AS MATERIALIZED (%s), checked AS MATERIALIZED (%s)
              SELECT (SELECT count(*) FROM checked WHERE NOT protected OR readable)=?
                AND (SELECT count(*) FROM goal_agent_listeners l
                  JOIN checked g ON g.id=l.goal AND g.readable
                  WHERE l.id=ANY(?::uuid[]) AND l.removed_at IS NULL
                  AND EXISTS (%s))=?
              """
                      .formatted(denied.sql(), BATCH_READABLE, LIVE_RELAY_LABEL);
          boolean current =
              sql.within(
                  ceiling,
                  () ->
                      Boolean.TRUE.equals(
                          jdbc.queryForObject(query, Boolean.class, args.toArray())));
          if (!current) throw new NotFoundFault("Goal Agent reply is unavailable");
          return Boolean.TRUE;
        });
  }

  private Status status(ResultSet row, int index) throws SQLException {
    var resultJob = row.getString("result_job");
    var result =
        resultJob == null
            ? null
            : new Result(
                resultJob,
                row.getInt("result_turn"),
                row.getString("result_summary"),
                read(row, "result_evidence", EVIDENCE));
    var reason = row.getString("reason");
    var next = row.getObject("next_at", OffsetDateTime.class);
    return new Status(
        row.getObject("id", UUID.class),
        row.getString("project"),
        row.getString("conversation"),
        row.getString("instance"),
        row.getString("agent"),
        row.getString("objective"),
        row.getBoolean("standing") ? Lifecycle.STANDING : Lifecycle.AD_HOC,
        State.valueOf(row.getString("state")),
        row.getInt("version"),
        row.getBoolean("readable"),
        reason == null ? null : Reason.valueOf(reason),
        row.getString("detail"),
        result,
        next == null ? null : next.toInstant(),
        read(row, "schedules", SCHEDULES),
        read(row, "relay_listeners", LISTENERS),
        row.getBoolean("more_schedules"),
        row.getBoolean("more_relay_listeners"));
  }

  private static <T> List<T> read(ResultSet row, String column, TypeReference<List<T>> type)
      throws SQLException {
    var encoded = row.getString(column);
    if (encoded == null) return List.of();
    try {
      return Objects.requireNonNull(JSON.readValue(encoded, type));
    } catch (JsonProcessingException invalid) {
      throw new SQLException("Invalid Goal Agent projection", invalid);
    }
  }
}
