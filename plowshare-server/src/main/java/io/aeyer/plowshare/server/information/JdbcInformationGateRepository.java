package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.hooks.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persisted receipt codec and token fencing. Hook execution belongs to the owning service. */
public final class JdbcInformationGateRepository implements InformationGateRepository {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public JdbcInformationGateRepository(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.clock = Objects.requireNonNull(clock);
  }

  private OffsetDateTime now() {
    return clock.instant().atOffset(ZoneOffset.UTC);
  }

  private OffsetDateTime expiry() {
    return clock.instant().plusSeconds(300).atOffset(ZoneOffset.UTC);
  }

  private static <T extends InformationGateResult> Receipt<T> receipt(
      Map<String, Object> row, Class<T> type) {
    T response = null;
    if (row.get("response") != null)
      try {
        response = JSON.readValue(row.get("response").toString(), type);
      } catch (java.io.IOException invalid) {
        throw new IllegalStateException("invalid stored transition receipt", invalid);
      }
    return new Receipt<>(
        (UUID) row.get("token"),
        State.valueOf(row.get("state").toString().toUpperCase(Locale.ROOT)),
        response,
        (String) row.get("log_id"));
  }

  public <T extends InformationGateResult> Receipt<T> claim(
      String account, UUID request, String fingerprint, String operation, Class<T> resultType) {
    Objects.requireNonNull(account);
    Objects.requireNonNull(request);
    Objects.requireNonNull(resultType);
    if (fingerprint == null
        || !fingerprint.matches("[a-f0-9]{64}")
        || !Set.of(
                "intake",
                "evidence.record",
                "record.report",
                "migration.release",
                "migration.adopt")
            .contains(operation)) throw new IllegalArgumentException("invalid gate identity");
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
        Boolean.class,
        account + ":write-gates:" + request);
    var prior =
        jdbc.queryForList(
            "SELECT * FROM information_write_gates WHERE account=? AND request_id=? FOR UPDATE",
            account,
            request);
    if (!prior.isEmpty()) {
      var row = prior.getFirst();
      if (!fingerprint.equals(row.get("fingerprint")))
        throw new CallerFault("requestId was already used for a different prepared transition");
      if (row.get("state").equals("completed")) return receipt(row, resultType);
      if (row.get("state").equals("blocked")) throw new CallerFault((String) row.get("error"));
      if (((OffsetDateTime) row.get("lease_until")).toInstant().isAfter(clock.instant()))
        throw new CallerFault("transition is in progress; reconcile with the same requestId");
      UUID token = UUID.randomUUID();
      jdbc.update(
          "UPDATE information_write_gates SET token=?,lease_until=? WHERE account=? AND request_id=?",
          token,
          expiry(),
          account,
          request);
      var claimed = new LinkedHashMap<>(row);
      claimed.put("token", token);
      return receipt(claimed, resultType);
    }
    UUID token = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_write_gates(account,request_id,fingerprint,operation,token,lease_until) VALUES(?,?,?,?,?,?)",
        account,
        request,
        fingerprint,
        operation,
        token,
        expiry());
    return receipt(
        jdbc.queryForMap(
            "SELECT * FROM information_write_gates WHERE account=? AND request_id=?",
            account,
            request),
        resultType);
  }

  public void fence(String account, UUID request, UUID token) {
    if (jdbc.queryForList(
            "SELECT request_id FROM information_write_gates WHERE account=? AND request_id=? AND token=? AND lease_until>=? FOR UPDATE",
            account,
            request,
            token,
            now())
        .isEmpty())
      throw new CallerFault("transition lease expired; reconcile with the same requestId");
  }

  public String openedLog(String account, UUID request) {
    return jdbc.queryForObject(
        "SELECT log_id FROM information_write_gates WHERE account=? AND request_id=?",
        String.class,
        account,
        request);
  }

  public void opened(String account, UUID request, String log) {
    jdbc.update(
        "UPDATE information_write_gates SET log_id=? WHERE account=? AND request_id=?",
        Objects.requireNonNull(log),
        account,
        request);
  }

  public void approved(String account, UUID request) {
    jdbc.update(
        "UPDATE information_write_gates SET state='approved' WHERE account=? AND request_id=?",
        account,
        request);
  }

  public void completed(String account, UUID request, InformationGateResult result) {
    jdbc.update(
        "UPDATE information_write_gates SET state='completed',response=CAST(? AS jsonb) WHERE account=? AND request_id=?",
        InformationJson.json(Objects.requireNonNull(result)),
        account,
        request);
  }

  public void blocked(String account, UUID request, UUID token, String error) {
    jdbc.update(
        "UPDATE information_write_gates SET state='blocked',error=? WHERE account=? AND request_id=? AND token=? AND state<>'completed'",
        error,
        account,
        request,
        token);
  }

  public void preGate(String account, UUID request, UUID token, Gate gate) {
    jdbc.update(
        "UPDATE information_write_gates SET pre_gate=CAST(? AS jsonb) WHERE account=? AND request_id=? AND token=?",
        InformationJson.json(Objects.requireNonNull(gate)),
        account,
        request,
        token);
  }

  public void postGate(String account, UUID request, UUID token, Gate gate) {
    jdbc.update(
        "UPDATE information_write_gates SET post_gate=CAST(? AS jsonb) WHERE account=? AND request_id=? AND token=?",
        InformationJson.json(Objects.requireNonNull(gate)),
        account,
        request,
        token);
  }

  public void finishRecords(String account, UUID request, UUID token, List<HookRecord> records) {
    jdbc.update(
        "UPDATE information_write_gates SET finish_records=CAST(? AS jsonb) WHERE account=? AND request_id=? AND token=?",
        InformationJson.json(List.copyOf(records)),
        account,
        request,
        token);
  }
}
