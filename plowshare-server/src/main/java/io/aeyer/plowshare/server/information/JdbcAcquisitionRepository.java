package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.faults.*;
import io.aeyer.plowshare.server.hooks.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Token-fenced URL queue persistence. No fetch or hook runs while its row lock is held. */
public final class JdbcAcquisitionRepository implements AcquisitionRepository {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public JdbcAcquisitionRepository(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.clock = Objects.requireNonNull(clock);
  }

  private OffsetDateTime now() {
    return clock.instant().atOffset(ZoneOffset.UTC);
  }

  private Ticket ticket(Map<String, Object> row) {
    Status status =
        new Status(
            (UUID) row.get("id"),
            (String) row.get("url"),
            (String) row.get("source_name"),
            (String) row.get("corpus"),
            (String) row.get("state"),
            (UUID) row.get("revision_id"),
            ((Number) row.get("attempt")).intValue(),
            (String) row.get("error"),
            ((Number) row.get("allowance_total")).intValue(),
            ((java.sql.Timestamp) row.get("created_at")).toInstant(),
            gate(row.get("pre_gate")),
            gate(row.get("post_gate")));
    return new Ticket(
        status,
        (String) row.get("account"),
        (UUID) row.get("request_id"),
        (String) row.get("project_name"),
        (String) row.get("log_id"),
        (String) row.get("caller_session"),
        (UUID) row.get("token"));
  }

  private static Gate gate(Object value) {
    if (value == null) return null;
    try {
      return JSON.readValue(value.toString(), Gate.class);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("invalid persisted acquisition gate", invalid);
    }
  }

  public UUID submit(
      InformationContext context,
      UUID request,
      String fingerprint,
      String url,
      String name,
      String session,
      int total,
      String corpus) {
    Objects.requireNonNull(context);
    Objects.requireNonNull(request);
    if (total < 1
        || url == null
        || url.isBlank()
        || name == null
        || name.isBlank()
        || fingerprint == null
        || !fingerprint.matches("[a-f0-9]{64}")
        || !Set.of("code", "documents").contains(corpus))
      throw new IllegalArgumentException("invalid acquisition admission");
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
        Boolean.class,
        context.account() + ":acquire:" + request);
    var prior =
        jdbc.queryForList(
            "SELECT id,fingerprint FROM information_acquisitions WHERE account=? AND request_id=?",
            context.account(),
            request);
    if (!prior.isEmpty()) {
      if (!fingerprint.equals(prior.getFirst().get("fingerprint")))
        throw new CallerFault("requestId was already used for a different acquisition");
      return (UUID) prior.getFirst().get("id");
    }
    if (jdbc.queryForObject(
            "SELECT count(*) FROM information_requests WHERE account=? AND request_id=?",
            Integer.class,
            context.account(),
            request)
        != 0) throw new CallerFault("requestId was already used for information intake");
    UUID ticket = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_acquisitions(id,account,request_id,scope,project_name,fingerprint,url,source_name,caller_session,allowance_total,corpus) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        ticket,
        context.account(),
        request,
        context.selection().scope().name().toLowerCase(Locale.ROOT),
        context.selection().project(),
        fingerprint,
        url,
        name,
        session,
        total,
        corpus);
    return ticket;
  }

  public Ticket owned(String account, UUID id) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM information_acquisitions WHERE id=? AND account=?",
            Objects.requireNonNull(id),
            Objects.requireNonNull(account));
    if (rows.isEmpty()) throw new NotFoundFault("acquisition is unavailable to this account");
    return ticket(rows.getFirst());
  }

  public List<Status> list(String account, String project, String corpus, int limit, int offset) {
    Objects.requireNonNull(account);
    if (limit < 1 || limit > 100 || offset < 0 || !Set.of("code", "documents").contains(corpus))
      throw new IllegalArgumentException("invalid acquisition page");
    return jdbc
        .queryForList(
            "SELECT * FROM information_acquisitions WHERE account=? AND project_name IS NOT DISTINCT FROM ? AND corpus=? ORDER BY created_at DESC,id OFFSET ? LIMIT ?",
            account,
            project,
            corpus,
            offset,
            limit)
        .stream()
        .map(row -> ticket(row).status())
        .toList();
  }

  public void retry(UUID id, String account) {
    jdbc.update(
        "UPDATE information_acquisitions SET state='queued',error=NULL WHERE id=? AND account=? AND state IN ('failed','blocked')",
        Objects.requireNonNull(id),
        Objects.requireNonNull(account));
  }

  public Optional<Ticket> claim() {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM information_acquisitions WHERE state='queued' OR (state='running' AND lease_until<?) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
            now());
    if (rows.isEmpty()) return Optional.empty();
    var found = new LinkedHashMap<>(rows.getFirst());
    UUID token = UUID.randomUUID();
    found.put("token", token);
    found.put("attempt", ((Number) found.get("attempt")).intValue() + 1);
    jdbc.update(
        "UPDATE information_acquisitions SET state='running',token=?,lease_until=?,attempt=? WHERE id=?",
        token,
        clock.instant().plusSeconds(300).atOffset(ZoneOffset.UTC),
        found.get("attempt"),
        found.get("id"));
    found.put("state", "running");
    return Optional.of(ticket(found));
  }

  public void fence(Ticket row) {

    if (jdbc.queryForList(
            "SELECT id FROM information_acquisitions WHERE id=? AND token=? AND state='running' AND lease_until>=? FOR UPDATE",
            row.status().id(),
            row.token(),
            now())
        .isEmpty()) throw new InformationLifecycle.StaleLease();
  }

  public void finish(Ticket row, String state, String error) {

    jdbc.update(
        "UPDATE information_acquisitions SET state=?,error=?,token=NULL,lease_until=NULL WHERE id=? AND token=? AND state='running'",
        state,
        error,
        row.status().id(),
        row.token());
  }

  public void opened(Ticket row, String log) {
    jdbc.update(
        "UPDATE information_acquisitions SET log_id=? WHERE id=?",
        Objects.requireNonNull(log),
        row.status().id());
  }

  public void preGate(Ticket row, Gate gate) {
    jdbc.update(
        "UPDATE information_acquisitions SET pre_gate=CAST(? AS jsonb) WHERE id=?",
        InformationJson.json(gate),
        row.status().id());
  }

  public void postGate(Ticket row, Gate gate) {
    jdbc.update(
        "UPDATE information_acquisitions SET post_gate=CAST(? AS jsonb) WHERE id=?",
        InformationJson.json(gate),
        row.status().id());
  }

  public void admitted(Ticket row, UUID revision) {
    jdbc.update(
        "UPDATE information_acquisitions SET revision_id=? WHERE id=?",
        Objects.requireNonNull(revision),
        row.status().id());
  }

  public Retained retained(UUID revision) {
    return jdbc.queryForObject(
        "SELECT resource_id,source_uri FROM information_revisions WHERE id=?",
        (r, n) -> new Retained(r.getObject("resource_id", UUID.class), r.getString("source_uri")),
        Objects.requireNonNull(revision));
  }

  public void finishRecords(Ticket row, List<HookRecord> records) {
    jdbc.update(
        "UPDATE information_acquisitions SET finish_records=CAST(? AS jsonb) WHERE id=? AND token=?",
        InformationJson.json(List.copyOf(records)),
        row.status().id(),
        row.token());
  }
}
