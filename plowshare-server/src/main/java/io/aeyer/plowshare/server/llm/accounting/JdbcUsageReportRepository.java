package io.aeyer.plowshare.server.llm.accounting;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Usage.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Authorized usage reads over atomic rows and compatible daily repair summaries. */
@Repository
public class JdbcUsageReportRepository implements UsageReportRepository {
  private static final Map<String, String> DIMENSIONS =
      Map.of(
          "day",
          "to_char(c.created_at AT TIME ZONE 'UTC','YYYY-MM-DD')",
          "model",
          "c.wire_model",
          "pool",
          "c.pool",
          "agent",
          "c.agent_name",
          "operation",
          "c.operation",
          "project",
          "c.project_id",
          "run",
          "c.run_id");
  private static final String ATTEMPT_FIELDS =
      "attempt_id::text AS attempt_id,attempt_number,outcome,http_status,"
          + "finish_reason,input_tokens::text AS input_tokens,output_tokens::text AS output_tokens,"
          + "provider_total_tokens::text AS provider_total_tokens,cache_read_tokens::text AS cache_read_tokens,"
          + "cache_write_tokens::text AS cache_write_tokens,reasoning_tokens::text AS reasoning_tokens,"
          + "usage_source,usage_coverage,cost_kind,cost_amount::text AS cost_amount,cost_currency,price_version,"
          + "first_output_millis,duration_millis,start_accounting_millis,cost_result->'reasons' AS cost_reasons";

  private record Where(String sql, List<Object> args) {}

  private record ReportSources(Where calls, Optional<Where> repairs) {}

  private record Metric(String name, String calls, String repairs) {}

  private static final List<Metric> METRICS = metrics();

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final Clock clock;
  private final TransactionTemplate reads;
  private final byte[] cursorKey = new byte[32];

  @org.springframework.beans.factory.annotation.Autowired
  public JdbcUsageReportRepository(
      JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json) {
    this(jdbc, manager, json, Clock.systemUTC());
  }

  public JdbcUsageReportRepository(
      JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json, Clock clock) {
    this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
    this.jdbc.setQueryTimeout(5);
    this.json = json.copy().findAndRegisterModules();
    this.clock = clock;
    reads = new TransactionTemplate(manager);
    reads.setReadOnly(true);
    reads.setTimeout(10);
    reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    new java.security.SecureRandom().nextBytes(cursorKey);
  }

  public Report report(String account, Resolved resolved) {
    return read(
        () ->
            reads.execute(
                status -> {
                  Where where = where(account, resolved);
                  ReportSources sources = reportSources(where, resolved);
                  var totals = aggregate(sources, List.of(), 0, 1).getFirst();
                  List<Map<String, Object>> groups = List.of();
                  String cursor = null;
                  if (!resolved.filter().groupBy().isEmpty()) {
                    int offset = offset(account, resolved);
                    var page =
                        aggregate(
                            sources,
                            resolved.filter().groupBy(),
                            offset,
                            resolved.filter().limit() + 1);
                    boolean more = page.size() > resolved.filter().limit();
                    if (resolved.filter().groupBy().contains("run"))
                      attachRunAncestors(where, page);
                    groups = bounded(page.stream().limit(resolved.filter().limit()).toList());
                    more |= page.size() > groups.size();
                    if (more)
                      cursor = cursor(account, resolved, Integer.toString(offset + groups.size()));
                  }
                  return new Report(
                      resolved,
                      decode(totals, Aggregate.class),
                      groups.stream().map(row -> decode(row, Aggregate.class)).toList(),
                      cursor,
                      health());
                }));
  }

  public Audit calls(String account, Resolved resolved) {
    return read(
        () ->
            reads.execute(
                status -> {
                  Where w = where(account, resolved);
                  var args = new ArrayList<>(w.args());
                  String tail = "";
                  if (resolved.filter().cursor() != null) {
                    String[] key = decoded(account, resolved).split("\\|", -1);
                    try {
                      if (key.length != 2) throw new IllegalArgumentException();
                      tail = " AND (c.created_at,c.call_id) > (?,?::uuid)";
                      args.add(Timestamp.from(Instant.parse(key[0])));
                      args.add(UUID.fromString(key[1]).toString());
                    } catch (RuntimeException invalid) {
                      throw new CallerFault("invalid usage cursor");
                    }
                  }
                  args.add(resolved.filter().limit() + 1);
                  var rows =
                      jdbc.queryForList(
                          "SELECT c.call_id::text AS call_id,c.created_at,c.ended_at,c.lifecycle,c.pool,c.wire_model,"
                              + "c.model_family,c.billing_route,c.price_version,c.project_id,c.scope,c.conversation_id,c.run_id,c.orchestration_id,"
                              + "c.agent_name,c.operation,c.turn_ordinal::text AS turn_ordinal,c.step_ordinal::text AS step_ordinal,c.attempt_count,"
                              + "c.queue_millis,c.admission_accounting_millis,c.preflight_observation FROM inference_calls c WHERE "
                              + w.sql()
                              + tail
                              + " ORDER BY c.created_at,c.call_id LIMIT ?",
                          args.toArray());
                  boolean more = rows.size() > resolved.filter().limit();
                  var selected = rows.stream().limit(resolved.filter().limit()).toList();
                  var observations =
                      attemptsForCalls(
                          selected.stream().map(row -> row.get("call_id").toString()).toList());
                  var page =
                      bounded(
                          selected.stream()
                              .map(
                                  row -> {
                                    var safe = new LinkedHashMap<String, Object>(row);
                                    safe.put(
                                        "created_at",
                                        ((Timestamp) row.get("created_at")).toInstant().toString());
                                    if (row.get("ended_at") instanceof Timestamp time)
                                      safe.put("ended_at", time.toInstant().toString());
                                    if (row.get("preflight_observation") != null)
                                      safe.put(
                                          "preflight_observation",
                                          readJson(row.get("preflight_observation").toString()));
                                    var attempts =
                                        observations.getOrDefault(
                                            row.get("call_id").toString(), List.of());
                                    if (attempts.size() > 50)
                                      safe.put(
                                          "attempt_cursor",
                                          cursor(
                                              account,
                                              resolved,
                                              "attempt|" + row.get("call_id") + "|50"));
                                    safe.put("attempts", attempts.stream().limit(50).toList());
                                    safe.put("attempts_truncated", attempts.size() > 50);
                                    return (Map<String, Object>) safe;
                                  })
                              .toList());
                  more |= rows.size() > page.size();
                  String next =
                      more
                          ? cursor(
                              account,
                              resolved,
                              rows.get(page.size() - 1).get("created_at") instanceof Timestamp ts
                                  ? ts.toInstant() + "|" + rows.get(page.size() - 1).get("call_id")
                                  : "")
                          : null;
                  return new Audit(
                      resolved,
                      page.stream().map(row -> decode(row, Call.class)).toList(),
                      next,
                      health());
                }));
  }

  private List<Map<String, Object>> bounded(List<Map<String, Object>> rows) {
    int bytes = 0;
    var page = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      try {
        int size = json.writeValueAsBytes(row).length;
        if (bytes + size > 384 * 1024) break;
        bytes += size;
        page.add(row);
      } catch (Exception invalid) {
        throw new IllegalStateException("invalid usage report metadata");
      }
    }
    if (!rows.isEmpty() && page.isEmpty())
      throw new CallerFault("usage row exceeds the report bound; read its attempts separately");
    return List.copyOf(page);
  }

  private List<Map<String, Object>> attemptRows(String call, int after, int limit) {
    var rows =
        jdbc.queryForList(
            "SELECT "
                + ATTEMPT_FIELDS
                + " FROM inference_attempts WHERE call_id=?::uuid AND attempt_number>? ORDER BY attempt_number LIMIT ?",
            call,
            after,
            limit);
    decodeCostReasons(rows);
    return rows;
  }

  /** Per-call indexed LIMIT 51 retains each independent truncation cursor, not a global cap. */
  private Map<String, List<Map<String, Object>>> attemptsForCalls(List<String> calls) {
    var found = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (int start = 0; start < calls.size(); start += 128) {
      var selected = calls.subList(start, Math.min(start + 128, calls.size()));
      var rows =
          jdbc.queryForList(
              "SELECT chosen.call_id::text AS parent_call,attempt.* FROM (VALUES "
                  + String.join(",", Collections.nCopies(selected.size(), "(?::uuid)"))
                  + ") chosen(call_id) CROSS JOIN LATERAL (SELECT "
                  + ATTEMPT_FIELDS
                  + " FROM inference_attempts WHERE call_id=chosen.call_id ORDER BY attempt_number LIMIT 51) attempt"
                  + " ORDER BY chosen.call_id,attempt.attempt_number",
              selected.toArray());
      decodeCostReasons(rows);
      for (var row : rows) {
        String call = row.remove("parent_call").toString();
        found.computeIfAbsent(call, ignored -> new ArrayList<>()).add(row);
      }
    }
    return found;
  }

  private void decodeCostReasons(List<Map<String, Object>> rows) {
    for (var row : rows)
      if (row.get("cost_reasons") != null) {
        try {
          row.put("cost_reasons", json.readValue(row.get("cost_reasons").toString(), List.class));
        } catch (Exception invalid) {
          throw new IllegalStateException("invalid accounting cost coverage");
        }
      }
  }

  public AttemptPage attempts(
      String account, Resolved resolved, String call, String attemptCursor) {
    try {
      UUID.fromString(call);
    } catch (RuntimeException invalid) {
      throw new CallerFault("invalid usage call selector");
    }
    return read(
        () ->
            reads.execute(
                status -> {
                  Where w = where(account, resolved);
                  var args = new ArrayList<>(w.args());
                  args.add(call);
                  if (jdbc.queryForList(
                          "SELECT 1 FROM inference_calls c WHERE "
                              + w.sql()
                              + " AND c.call_id=?::uuid",
                          args.toArray())
                      .isEmpty())
                    throw new CallerFault("usage target is unavailable to this account");
                  int after = 0;
                  if (attemptCursor != null) {
                    Filter f = resolved.filter();
                    var cursorQuery =
                        new Resolved(
                            resolved.type(),
                            new Filter(
                                f.conversation(),
                                f.project(),
                                f.agent(),
                                f.run(),
                                f.orchestration(),
                                f.model(),
                                f.pool(),
                                f.route(),
                                f.scope(),
                                f.from(),
                                f.to(),
                                f.groupBy(),
                                attemptCursor,
                                f.limit()));
                    try {
                      String[] position = decoded(account, cursorQuery).split("\\|", -1);
                      if (position.length != 3
                          || !position[0].equals("attempt")
                          || !position[1].equals(call)) throw new IllegalArgumentException();
                      after = Integer.parseInt(position[2]);
                      if (after < 0) throw new IllegalArgumentException();
                    } catch (RuntimeException invalid) {
                      throw new CallerFault("invalid usage attempt cursor");
                    }
                  }
                  var rows = attemptRows(call, after, resolved.filter().limit() + 1);
                  var page = bounded(rows.stream().limit(resolved.filter().limit()).toList());
                  String next =
                      rows.size() > page.size()
                          ? cursor(
                              account,
                              resolved,
                              "attempt|" + call + "|" + page.getLast().get("attempt_number"))
                          : null;
                  return new AttemptPage(
                      resolved,
                      call,
                      page.stream().map(row -> decode(row, Attempt.class)).toList(),
                      next,
                      health());
                }));
  }

  private ContextCount readJson(String value) {
    try {
      return json.readValue(value, ContextCount.class);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("invalid stored prompt observation", invalid);
    }
  }

  private UsageAttribution attribution(String value) {
    try {
      return json.readValue(value, UsageAttribution.class);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("invalid captured usage attribution", invalid);
    }
  }

  private <T> T decode(Map<String, Object> row, Class<T> type) {
    try {
      return json.convertValue(row, type);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("invalid persisted usage report", invalid);
    }
  }

  public void requireConversation(String account, String conversation) {
    read(
        () -> {
          requireConversationRows(account, conversation);
          return null;
        });
  }

  private void requireConversationRows(String account, String conversation) {
    requireAccount(account);
    var rows =
        jdbc.queryForList(
            "SELECT c.owner_handle,c.project_id::text AS project_id FROM conversations c WHERE c.id=?",
            conversation);
    if (rows.isEmpty()) throw new CallerFault("usage target is unavailable to this account");
    var row = rows.getFirst();
    if (!account.equals(row.get("owner_handle"))
        || row.get("project_id") != null && !member(account, row.get("project_id").toString()))
      throw new CallerFault("usage target is unavailable to this account");
  }

  public UsageAttribution countOwner(String account, String conversation) {
    return read(
        () ->
            reads.execute(
                status -> {
                  requireConversationRows(account, conversation);
                  String project =
                      jdbc.queryForObject(
                          "SELECT project_id::text FROM conversations WHERE id=?",
                          String.class,
                          conversation);
                  var owner =
                      project == null
                          ? UsageAttribution.global(account, UsageAttribution.Operation.AGENT_CHAT)
                          : UsageAttribution.project(
                              account, project, UsageAttribution.Operation.AGENT_CHAT);
                  return owner.withExecution(
                      UsageLineage.root(conversation),
                      UsageLineage.NONE,
                      UsageLineage.NONE,
                      null,
                      null,
                      null);
                }));
  }

  private static <T> T read(java.util.function.Supplier<T> query) {
    try {
      return query.get();
    } catch (org.springframework.dao.NonTransientDataAccessResourceException
        | org.springframework.dao.TransientDataAccessException
        | org.springframework.dao.RecoverableDataAccessException
        | org.springframework.transaction.CannotCreateTransactionException unavailable) {
      throw new UsageUnavailableException(unavailable);
    }
  }

  private boolean member(String account, String project) {
    return !jdbc.queryForList(
            "SELECT 1 FROM project_members WHERE handle=? AND project_id::text=?", account, project)
        .isEmpty();
  }

  private void requireAccount(String account) {
    if (account == null
        || jdbc.queryForList(
                "SELECT 1 FROM admins WHERE handle=? AND enabled AND NOT bootstrap AND account_kind='USER'",
                account)
            .isEmpty()) throw new CallerFault("usage needs a current signed-in account");
  }

  private Where where(String account, Resolved r) {
    if (!r.equals(UsageFilters.resolve(r.type(), r.filter(), clock)))
      throw new CallerFault("usage filters must be resolved before reading");
    requireAccount(account);
    Filter f = r.filter();
    var args = new ArrayList<Object>();
    String sql = "c.created_at>=? AND c.created_at<?";
    args.add(Timestamp.from(f.from()));
    args.add(Timestamp.from(f.to()));
    // Fleet statistics require current server authority, including on subscription refresh.
    if (r.type().equals("usage.pools")
        && jdbc.queryForList(
                "SELECT 1 FROM admins WHERE handle=? AND enabled AND server_admin AND NOT bootstrap AND account_kind='USER'",
                account)
            .isEmpty()) throw new CallerFault("Fleet usage requires a server administrator");
    if (!r.type().equals("usage.pools")) {
      sql +=
          " AND (c.account_handle=? OR c.project_id IN (SELECT project_id::text FROM project_members WHERE handle=?))"
              + " AND (c.project_id IS NULL OR NOT EXISTS(SELECT 1 FROM projects p WHERE p.id::text=c.project_id)"
              + " OR c.project_id IN (SELECT project_id::text FROM project_members WHERE handle=?))";
      args.add(account);
      args.add(account);
      args.add(account);
      if (f.conversation() != null || f.run() != null || f.orchestration() != null) {
        sql +=
            " AND c.account_handle=? AND (c.conversation_id IS NULL OR NOT EXISTS(SELECT 1 FROM conversations s WHERE s.id=c.conversation_id)"
                + " OR EXISTS(SELECT 1 FROM conversations s WHERE s.id=c.conversation_id AND s.owner_handle=?))";
        args.add(account);
        args.add(account);
      }
    }
    if (f.project() != null) {
      var ids =
          jdbc.queryForList(
              "SELECT p.id::text FROM projects p JOIN project_members m ON m.project_id=p.id WHERE p.name=? AND m.handle=?",
              String.class,
              f.project(),
              account);
      if (ids.isEmpty()) throw new CallerFault("usage target is unavailable to this account");
      sql += " AND c.project_id=?";
      args.add(ids.getFirst());
    }
    if (f.conversation() != null || f.run() != null || f.orchestration() != null) {
      String column =
          f.conversation() != null ? "conversation" : f.run() != null ? "run" : "orchestration";
      String target =
          f.conversation() != null
              ? f.conversation()
              : f.run() != null ? f.run() : f.orchestration();
      // Source-independent history survives retention; a current source still controls revocation.
      if (column.equals("conversation")
          && !jdbc.queryForList("SELECT 1 FROM conversations WHERE id=?", target).isEmpty())
        requireConversation(account, target);
      else {
        String source =
            column.equals("run")
                ? "SELECT attribution FROM inference_run_ownership WHERE attribution->'runs'->>'id'=?"
                : column.equals("orchestration")
                    ? "SELECT attribution FROM inference_run_ownership WHERE attribution->'orchestrations'->>'id'=?"
                    : "SELECT attribution FROM inference_run_ownership WHERE conversation_id=?";
        var owners = jdbc.queryForList(source, String.class, target);
        var historic =
            jdbc.queryForList(
                "SELECT 1 FROM inference_calls c WHERE c."
                    + column
                    + "_id=? AND c.account_handle=? LIMIT 1",
                target,
                account);
        boolean owned =
            owners.stream().anyMatch(v -> account.equals(attribution(v).accountHandle()));
        if (!owned && historic.isEmpty())
          throw new CallerFault("usage target is unavailable to this account");
        for (String snapshot : owners) {
          UsageAttribution attribution = attribution(snapshot);
          if (!account.equals(attribution.accountHandle())) continue;
          String project = attribution.projectId();
          if (project != null
              && !jdbc.queryForList("SELECT 1 FROM projects WHERE id::text=?", project).isEmpty()
              && !member(account, project.toString()))
            throw new CallerFault("usage target is unavailable to this account");
          String convo = attribution.conversations().id();
          if (convo != null
              && !jdbc.queryForList("SELECT 1 FROM conversations WHERE id=?", convo).isEmpty())
            requireConversation(account, convo.toString());
        }
        if (owners.isEmpty()) {
          var projects =
              jdbc.queryForList(
                  "SELECT DISTINCT project_id FROM inference_calls WHERE "
                      + column
                      + "_id=? AND account_handle=? AND project_id IS NOT NULL",
                  String.class,
                  target,
                  account);
          for (String project : projects)
            if (!jdbc.queryForList("SELECT 1 FROM projects WHERE id::text=?", project).isEmpty()
                && !member(account, project))
              throw new CallerFault("usage target is unavailable to this account");
        }
      }
    }
    sql = selector(sql, args, "conversation", f.conversation(), f.scope());
    sql = selector(sql, args, "run", f.run(), f.scope());
    sql = selector(sql, args, "orchestration", f.orchestration(), f.scope());
    for (var selection :
        List.of(
            new String[] {"agent_name", f.agent()},
            new String[] {"wire_model", f.model()},
            new String[] {"pool", f.pool()},
            new String[] {"billing_route", f.route()}))
      if (selection[1] != null) {
        sql += " AND c." + selection[0] + "=?";
        args.add(selection[1]);
      }
    return new Where(sql, args);
  }

  private String selector(
      String sql, List<Object> args, String column, String value, String scope) {
    if (value == null) return sql;
    args.add(value);
    if (scope.equals("subtree")) {
      args.add(value);
      return sql + " AND (c." + column + "_id=? OR ?=ANY(c.ancestor_" + column + "_ids))";
    }
    return sql + " AND c." + column + "_id=?";
  }

  private static List<Metric> metrics() {
    var metrics =
        new ArrayList<>(
            List.of(
                new Metric("calls", "count(DISTINCT c.call_id)", "c.calls"),
                new Metric("attempts", "count(a.attempt_id)", "c.calls"),
                new Metric(
                    "incomplete_attempts",
                    "count(a.attempt_id) FILTER(WHERE a.ended_at IS NULL OR a.input_tokens IS NULL OR a.usage_coverage='INVALID' OR c.lane='CHAT' AND a.output_tokens IS NULL)",
                    "c.incomplete_attempts"),
                new Metric(
                    "active_calls",
                    "count(DISTINCT c.call_id) FILTER(WHERE c.ended_at IS NULL)",
                    "0"),
                new Metric(
                    "failed_attempts",
                    "count(a.attempt_id) FILTER(WHERE a.outcome IN ('FAILED','REFUSED','CANCELLED','INTERRUPTED'))",
                    "0"),
                new Metric(
                    "global_calls",
                    "count(DISTINCT c.call_id) FILTER(WHERE c.scope='GLOBAL')",
                    "CASE WHEN c.project_id IS NULL THEN c.calls ELSE 0 END"),
                new Metric(
                    "system_calls",
                    "count(DISTINCT c.call_id) FILTER(WHERE c.attribution_status='SYSTEM')",
                    "c.calls"),
                new Metric(
                    "legacy_calls",
                    "count(DISTINCT c.call_id) FILTER(WHERE c.attribution_status='LEGACY_UNATTRIBUTED')",
                    "0"),
                new Metric(
                    "unknown_cost_attempts",
                    "count(a.attempt_id) FILTER(WHERE a.cost_kind IN ('UNKNOWN','PARTIAL'))",
                    "c.unknown_cost_attempts")));
    for (String field :
        List.of(
            "input_tokens",
            "output_tokens",
            "provider_total_tokens",
            "cache_read_tokens",
            "cache_write_tokens",
            "reasoning_tokens")) {
      metrics.add(new Metric(field, "COALESCE(sum(a." + field + "),0)", "c." + field));
      metrics.add(new Metric(field + "_known", "count(a." + field + ")", "c." + field + "_known"));
    }
    return List.copyOf(metrics);
  }

  private static ReportSources reportSources(Where calls, Resolved resolved) {
    Filter filter = resolved.filter();
    // Fleet authorization has already been checked by where(). Scoped reads and their live
    // identity/lineage filters continue to use atomic facts.
    if (!resolved.type().equals("usage.pools")
        || filter.project() != null
        || filter.agent() != null
        || filter.conversation() != null
        || filter.run() != null
        || filter.orchestration() != null
        || filter.groupBy().contains("agent")
        || filter.groupBy().contains("run")) return new ReportSources(calls, Optional.empty());

    Instant firstDay = filter.from().truncatedTo(ChronoUnit.DAYS);
    if (!firstDay.equals(filter.from())) firstDay = firstDay.plus(1, ChronoUnit.DAYS);
    Instant endDay = filter.to().truncatedTo(ChronoUnit.DAYS);
    if (!firstDay.isBefore(endDay)) return new ReportSources(calls, Optional.empty());

    var callArgs = new ArrayList<>(calls.args());
    callArgs.add(Timestamp.from(firstDay));
    callArgs.add(Timestamp.from(endDay));
    var raw =
        new Where(
            calls.sql()
                + " AND NOT (c.created_at>=? AND c.created_at<? AND inference_repair_rollup_eligible(c))",
            List.copyOf(callArgs));
    var repairArgs = new ArrayList<Object>();
    repairArgs.add(LocalDate.ofInstant(firstDay, ZoneOffset.UTC));
    repairArgs.add(LocalDate.ofInstant(endDay, ZoneOffset.UTC));
    String repairSql = "c.day>=? AND c.day<?";
    if (filter.model() != null) {
      repairSql += " AND c.wire_model=?";
      repairArgs.add(filter.model());
    }
    if (filter.pool() != null) {
      repairSql += " AND c.pool=?";
      repairArgs.add(filter.pool());
    }
    if (filter.route() != null) {
      repairSql += " AND c.billing_route=?";
      repairArgs.add(filter.route());
    }
    return new ReportSources(raw, Optional.of(new Where(repairSql, List.copyOf(repairArgs))));
  }

  private static String repairDimension(String group) {
    return switch (group) {
      case "day" -> "to_char(c.day,'YYYY-MM-DD')";
      case "operation" -> "'EMBEDDING_REPAIR'::text";
      default -> DIMENSIONS.get(group);
    };
  }

  private List<Map<String, Object>> aggregate(
      ReportSources sources, List<String> groups, int offset, int limit) {
    String dimensions = groups.stream().map(DIMENSIONS::get).collect(Collectors.joining(","));
    String selections =
        groups.stream().map(g -> DIMENSIONS.get(g) + " AS " + g).collect(Collectors.joining(","));
    String groupNames = String.join(",", groups);
    var args = new ArrayList<>(sources.calls().args());
    boolean useRepairs = sources.repairs().isPresent();
    String sums =
        METRICS.stream()
            .map(
                metric ->
                    "("
                        + metric.calls()
                        + ")"
                        + (useRepairs ? "" : "::text")
                        + " AS "
                        + metric.name())
            .collect(Collectors.joining(","));
    String query =
        "SELECT "
            + prefix(selections)
            + sums
            + " FROM inference_calls c LEFT JOIN inference_attempts a ON a.call_id=c.call_id WHERE "
            + sources.calls().sql()
            + groupBy(dimensions);
    if (useRepairs) {
      Where repairs = sources.repairs().orElseThrow();
      String rolledFields = METRICS.stream().map(Metric::repairs).collect(Collectors.joining(","));
      String rolledDimensions =
          groups.stream()
              .map(JdbcUsageReportRepository::repairDimension)
              .collect(Collectors.joining(","));
      String merged =
          METRICS.stream()
              .map(metric -> "coalesce(sum(" + metric.name() + "),0)::text AS " + metric.name())
              .collect(Collectors.joining(","));
      query =
          "SELECT "
              + prefix(groupNames)
              + merged
              + " FROM ("
              + query
              + " UNION ALL SELECT "
              + prefix(rolledDimensions)
              + rolledFields
              + " FROM inference_daily_embedding_repairs c WHERE "
              + repairs.sql()
              + ") contributions"
              + groupBy(groupNames);
      args.addAll(repairs.args());
    }
    if (!groups.isEmpty())
      query += " ORDER BY " + String.join(" NULLS FIRST,", groups) + " NULLS FIRST";
    args.add(limit);
    args.add(offset);
    var rows = jdbc.queryForList(query + " LIMIT ? OFFSET ?", args.toArray());

    if (rows.isEmpty()) return rows;
    var costs = costs(sources, groups, rows, dimensions, selections);
    for (var row : rows) {
      var byCurrency = new TreeMap<String, String>();
      for (var cost : costs)
        if (groups.stream().allMatch(g -> Objects.equals(row.get(g), cost.get(g))))
          byCurrency.put(
              cost.get("cost_currency").toString(),
              new BigDecimal(cost.get("amount").toString()).toPlainString());
      row.put("costs", byCurrency);
      row.put(
          "usage_complete",
          "0".equals(row.get("incomplete_attempts")) && "0".equals(row.get("active_calls")));
      row.put(
          "cost_complete",
          "0".equals(row.get("unknown_cost_attempts")) && "0".equals(row.get("active_calls")));
      row.put(
          "complete",
          "0".equals(row.get("incomplete_attempts"))
              && "0".equals(row.get("unknown_cost_attempts"))
              && "0".equals(row.get("active_calls")));
    }
    return rows;
  }

  private List<Map<String, Object>> costs(
      ReportSources sources,
      List<String> groups,
      List<Map<String, Object>> rows,
      String dimensions,
      String selections) {
    // Monetary sums remain separated by currency; no cross-currency grand total.
    var args = new ArrayList<>(sources.calls().args());
    String query =
        "SELECT "
            + prefix(selections)
            + "a.cost_currency, sum(a.cost_amount) AS amount"
            + " FROM inference_calls c JOIN inference_attempts a ON a.call_id=c.call_id WHERE "
            + sources.calls().sql()
            + costPage(groups, rows, DIMENSIONS::get, args)
            + " AND a.cost_amount IS NOT NULL AND a.cost_currency IS NOT NULL"
            + " GROUP BY "
            + prefix(dimensions)
            + "a.cost_currency";
    if (sources.repairs().isPresent()) {
      Where repairs = sources.repairs().orElseThrow();
      String groupNames = prefix(String.join(",", groups));
      String rolledDimensions =
          groups.stream()
              .map(JdbcUsageReportRepository::repairDimension)
              .collect(Collectors.joining(","));
      args.addAll(repairs.args());
      query =
          "SELECT "
              + groupNames
              + "cost_currency,sum(amount) AS amount FROM ("
              + query
              + " UNION ALL SELECT "
              + prefix(rolledDimensions)
              + "c.cost_currency,c.cost_amount"
              + " FROM inference_daily_embedding_repairs c WHERE "
              + repairs.sql()
              + " AND c.cost_currency IS NOT NULL"
              + costPage(groups, rows, JdbcUsageReportRepository::repairDimension, args)
              + ") contributions GROUP BY "
              + groupNames
              + "cost_currency";
    }
    return jdbc.queryForList(query, args.toArray());
  }

  private static String costPage(
      List<String> groups,
      List<Map<String, Object>> rows,
      Function<String, String> dimension,
      List<Object> args) {
    if (groups.isEmpty()) return "";
    var clauses = new ArrayList<String>();
    for (var row : rows) {
      var equalities = new ArrayList<String>();
      for (String group : groups) {
        equalities.add(dimension.apply(group) + " IS NOT DISTINCT FROM ?::text");
        args.add(row.get(group));
      }
      clauses.add("(" + String.join(" AND ", equalities) + ")");
    }
    return " AND (" + String.join(" OR ", clauses) + ")";
  }

  private static String prefix(String csv) {
    return csv.isEmpty() ? "" : csv + ",";
  }

  private static String groupBy(String csv) {
    return csv.isEmpty() ? "" : " GROUP BY " + csv;
  }

  /**
   * Captured ancestry includes parents with no inference; only paths from authorized selected calls
   * are returned.
   */
  private void attachRunAncestors(Where where, List<Map<String, Object>> groups) {
    var runs =
        groups.stream().map(row -> row.get("run")).filter(Objects::nonNull).distinct().toList();
    if (runs.isEmpty()) return;
    var args = new ArrayList<>(where.args());
    args.addAll(runs);
    var paths =
        jdbc.queryForList(
            "SELECT DISTINCT c.run_id,to_json(c.ancestor_run_ids)::text AS path "
                + "FROM inference_calls c WHERE "
                + where.sql()
                + " AND c.run_id IN ("
                + String.join(",", Collections.nCopies(runs.size(), "?"))
                + ")",
            args.toArray());
    for (var row : groups)
      paths.stream()
          .filter(path -> Objects.equals(path.get("run_id"), row.get("run")))
          .findFirst()
          .ifPresent(
              path -> {
                try {
                  row.put("ancestor_runs", json.readValue(path.get("path").toString(), List.class));
                } catch (Exception invalid) {
                  throw new IllegalStateException("invalid captured usage ancestry");
                }
              });
  }

  private Health health() {

    var row =
        jdbc.queryForMap(
            "SELECT min(tracking_started_at) AS tracking_started_at,max(last_projected_at) AS last_projected_at,"
                + "COALESCE(sum(projected_sequence),0)::text AS watermark FROM inference_accounting_journals");
    for (String key : List.of("tracking_started_at", "last_projected_at"))
      if (row.get(key) instanceof Timestamp ts) row.put(key, ts.toInstant().toString());
    row.put("as_of", clock.instant().toString());
    row.put("historical_usage", "not_imported");
    return new Health(
        instant(row.get("tracking_started_at")),
        instant(row.get("last_projected_at")),
        (String) row.get("watermark"),
        clock.instant(),
        "not_imported",
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static Instant instant(Object value) {
    return value == null ? null : Instant.parse(value.toString());
  }

  private int offset(String account, Resolved r) {
    if (r.filter().cursor() == null) return 0;
    try {
      int value = Integer.parseInt(decoded(account, r));
      if (value < 0 || value > 1000000) throw new NumberFormatException();
      return value;
    } catch (RuntimeException invalid) {
      throw new CallerFault("invalid usage cursor");
    }
  }

  private String identity(String account, Resolved r) throws Exception {
    Filter f = r.filter();
    return json.writeValueAsString(
        List.of(
            account,
            r.type(),
            new Filter(
                f.conversation(),
                f.project(),
                f.agent(),
                f.run(),
                f.orchestration(),
                f.model(),
                f.pool(),
                f.route(),
                f.scope(),
                f.from(),
                f.to(),
                f.groupBy(),
                null,
                f.limit())));
  }

  private byte[] signature(String value) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
    return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
  }

  private String cursor(String account, Resolved r, String position) {
    try {
      String data = identity(account, r) + "\n" + position;
      return Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(data.getBytes(StandardCharsets.UTF_8))
          + "."
          + Base64.getUrlEncoder().withoutPadding().encodeToString(signature(data));
    } catch (Exception invalid) {
      throw new IllegalStateException("cannot encode usage cursor");
    }
  }

  private String decoded(String account, Resolved r) {
    try {
      String[] parts = r.filter().cursor().split("\\.", -1);
      if (parts.length != 2) throw new IllegalArgumentException();
      String data = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
      if (!java.security.MessageDigest.isEqual(
          signature(data), Base64.getUrlDecoder().decode(parts[1])))
        throw new IllegalArgumentException();
      int split = data.lastIndexOf('\n');
      if (split < 0 || !data.substring(0, split).equals(identity(account, r)))
        throw new IllegalArgumentException();
      return data.substring(split + 1);
    } catch (Exception invalid) {
      throw new CallerFault("invalid usage cursor");
    }
  }
}
