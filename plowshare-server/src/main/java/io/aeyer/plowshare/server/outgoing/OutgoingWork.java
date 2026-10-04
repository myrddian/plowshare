package io.aeyer.plowshare.server.outgoing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Outgoing;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Durable outbox: each initial send is claimed once. Only remote task reads may be reclaimed. No
 * A2A transport, credentials or remote filesystem promises live in this service.
 */
@Service
public final class OutgoingWork {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELED", "REJECTED");
  private static final Set<String> REPORTED =
      Set.of(
          "WORKING",
          "INPUT_REQUIRED",
          "AUTH_REQUIRED",
          "COMPLETED",
          "FAILED",
          "CANCELED",
          "REJECTED",
          "UNKNOWN");
  private static final String SELECT =
      "SELECT w.*, p.name AS project FROM outgoing_work w LEFT JOIN projects p ON p.id=w.project_id ";
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;

  public OutgoingWork(JdbcTemplate jdbc, UnitOfWork transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  public static void peer(String peer) {
    if (peer == null || !peer.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}"))
      throw new CallerFault("outgoing peer must be a configured name, not a URL");
  }

  private Long project(String name) {
    if (name == null) return null;
    var found = jdbc.queryForList("SELECT id FROM projects WHERE name=?", Long.class, name);
    if (found.isEmpty()) throw new CallerFault("outgoing project does not exist");
    return found.getFirst();
  }

  public Outgoing.Peers peers(String account, String home) {
    Long project = project(home);
    var details =
        jdbc.query(
            "SELECT peer,agent_card FROM outgoing_peers WHERE account=? AND project_key=? AND advertised_at>now()-interval '2 minutes' ORDER BY peer",
            (row, ignored) ->
                new Outgoing.Peer(
                    row.getString("peer"),
                    row.getString("agent_card") == null
                        ? null
                        : decode(row.getString("agent_card"))),
            account,
            project == null ? 0 : project);
    return new Outgoing.Peers(details.stream().map(Outgoing.Peer::peer).toList(), details);
  }

  public void advertise(String account, String home, List<String> peers) {
    advertise(account, home, peers, null);
  }

  public void advertise(
      String account, String home, List<String> peers, Map<String, Map<String, Object>> cards) {
    if (peers == null || peers.isEmpty() || peers.size() > 32)
      throw new CallerFault("advertise between one and 32 peers");
    peers.forEach(OutgoingWork::peer);
    if (cards != null) {
      if (!peers.containsAll(cards.keySet()))
        throw new CallerFault("agent cards must belong to advertised peers");
      if (encode(cards).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512 * 1024)
        throw new CallerFault("outgoing agent cards exceed 512 KiB");
      for (var card : cards.values()) {
        if (card == null
            || !(card.get("name") instanceof String name)
            || name.isBlank()
            || !(card.get("description") instanceof String)
            || !(card.get("skills") instanceof List<?>))
          throw new CallerFault("outgoing agent card requires name, description and skills");
        if (encode(card).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64 * 1024)
          throw new CallerFault("outgoing agent card exceeds 64 KiB");
      }
    }
    Long project = project(home);
    transactions.inTransaction(
        () -> {
          for (String peer : peers)
            jdbc.update(
                "INSERT INTO outgoing_peers(account,project_key,peer,agent_card) VALUES(?,?,?,?::jsonb) ON CONFLICT(account,project_key,peer) DO UPDATE SET advertised_at=now(),agent_card=EXCLUDED.agent_card",
                account,
                project == null ? 0 : project,
                peer,
                cards == null || cards.get(peer) == null ? null : encode(cards.get(peer)));
          return null;
        });
  }

  public Outgoing.Work send(String account, Outgoing.Send send) {
    if (send.requestId() == null)
      throw new CallerFault("outgoing.send requires a stable requestId UUID");
    peer(send.peer());
    if (send.message() == null || send.message().isEmpty())
      throw new CallerFault("outgoing.send requires a message object");
    String message = encode(send.message());
    if (message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256 * 1024)
      throw new CallerFault("outgoing message exceeds 256 KiB");
    Long project = project(send.project());
    if (send.conversation() != null
        && !Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM conversations WHERE id=? AND project_id IS NOT DISTINCT FROM CAST(? AS BIGINT))",
                Boolean.class,
                send.conversation(),
                project)))
      throw new CallerFault("outgoing conversation must belong to its project");
    return transactions.inTransaction(
        () -> {
          // Serializes submissions for this receipt before checking/inserting it.
          jdbc.queryForObject(
              "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
              Object.class,
              account + ":" + send.requestId());
          var prior =
              jdbc.query(
                  SELECT + "WHERE w.account=? AND w.request_id=?",
                  this::read,
                  account,
                  send.requestId());
          if (!prior.isEmpty()) {
            var old = prior.getFirst();
            if (!old.peer().equals(send.peer())
                || !Objects.equals(old.project(), send.project())
                || !Objects.equals(old.conversation(), send.conversation())
                || !old.message().equals(decode(message)))
              throw new CallerFault(
                  "outgoing requestId already names different work; nothing was sent");
            return old;
          }
          if (!peers(account, send.project()).peers().contains(send.peer()))
            throw new CallerFault("outgoing peer is not advertised for this account and project");
          UUID id = UUID.randomUUID();
          jdbc.update(
              "INSERT INTO outgoing_work(id,account,request_id,peer,project_id,conversation,message,state) VALUES(?,?,?,?,?,?,?::jsonb,'QUEUED')",
              id,
              account,
              send.requestId(),
              send.peer(),
              project,
              send.conversation(),
              message);
          return get(account, id);
        });
  }

  public Outgoing.Work get(String account, UUID id) {
    if (id == null) throw new CallerFault("outgoing work needs an id");
    var found = jdbc.query(SELECT + "WHERE w.account=? AND w.id=?", this::read, account, id);
    if (found.isEmpty()) throw new CallerFault("no outgoing work is owned by this account");
    return found.getFirst();
  }

  public Outgoing.Claimed claim(String account, String home, List<String> peers, String worker) {
    if (peers == null || peers.isEmpty() || peers.size() > 32)
      throw new CallerFault("claim requires one to 32 configured peers");
    peers.forEach(OutgoingWork::peer);
    Long project = project(home);
    return transactions.inTransaction(
        () -> {
          String marks = String.join(",", Collections.nCopies(peers.size(), "?"));
          var args = new ArrayList<Object>();
          args.add(account);
          args.add(project);
          args.addAll(peers);
          var ids =
              jdbc.queryForList(
                  "SELECT id FROM outgoing_work WHERE account=? AND project_id IS NOT DISTINCT FROM CAST(? AS BIGINT) AND peer IN ("
                      + marks
                      + ") AND (state='QUEUED' OR (remote_task IS NOT NULL AND state IN ('DISPATCHED','WORKING','INPUT_REQUIRED','AUTH_REQUIRED','UNKNOWN') AND lease_until<now())) ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                  UUID.class,
                  args.toArray());
          if (ids.isEmpty()) return new Outgoing.Claimed(null, null);
          UUID id = ids.getFirst();
          Outgoing.Work before = get(account, id);
          jdbc.update(
              "UPDATE outgoing_work SET state=CASE WHEN state='QUEUED' THEN 'DISPATCHED' ELSE state END,worker=?,lease_until=now()+interval '30 seconds',revision=revision+1 WHERE id=?",
              worker,
              id);
          return new Outgoing.Claimed(
              get(account, id),
              before.state().equals("QUEUED")
                  ? "send"
                  : before.cancelRequested() ? "cancel" : "observe");
        });
  }

  public Outgoing.Work report(String account, String worker, Outgoing.Report report) {
    if (!REPORTED.contains(Objects.toString(report.state(), "")))
      throw new CallerFault("invalid outgoing reported state");
    if (report.error() != null && report.error().length() > 2000)
      throw new CallerFault("outgoing diagnostic exceeds 2000 characters");
    if (report.result() != null
        && encode(report.result()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            > 1024 * 1024) throw new CallerFault("outgoing result exceeds 1 MiB");
    return transactions.inTransaction(
        () -> {
          jdbc.queryForList(
              "SELECT id FROM outgoing_work WHERE account=? AND id=? FOR UPDATE",
              UUID.class,
              account,
              report.id());
          Outgoing.Work old = get(account, report.id());
          if (old.revision() != report.revision() || TERMINAL.contains(old.state()))
            throw new CallerFault("outgoing observation is stale or work is terminal");
          if (old.remoteTask() != null && !old.remoteTask().equals(report.remoteTask()))
            throw new CallerFault("outgoing remote task identity cannot change");
          if (old.remoteContext() != null && !old.remoteContext().equals(report.remoteContext()))
            throw new CallerFault("outgoing remote context identity cannot change");
          if (Set.of("WORKING", "INPUT_REQUIRED", "AUTH_REQUIRED").contains(report.state())
              && (report.remoteTask() == null || report.remoteTask().isBlank()))
            throw new CallerFault(
                "nonterminal outgoing observation requires a remote task identity");
          if (jdbc.update(
                  "UPDATE outgoing_work SET state=?,remote_task=?,remote_context=?,result=?::jsonb,error=?,lease_until=now()+interval '2 seconds',revision=revision+1 WHERE id=? AND account=? AND worker=? AND revision=?",
                  report.state(),
                  report.remoteTask(),
                  report.remoteContext(),
                  report.result() == null ? null : encode(report.result()),
                  report.error(),
                  report.id(),
                  account,
                  worker,
                  report.revision())
              != 1) throw new CallerFault("only the claiming adapter may report outgoing work");
          return get(account, report.id());
        });
  }

  public Outgoing.Work cancel(String account, UUID id) {
    transactions.inTransaction(
        () -> {
          get(account, id);
          jdbc.update(
              "UPDATE outgoing_work SET cancel_requested=true,state=CASE WHEN state='QUEUED' THEN 'CANCELED' ELSE state END WHERE id=? AND account=? AND state NOT IN ('COMPLETED','FAILED','CANCELED','REJECTED')",
              id,
              account);
          return null;
        });
    return get(account, id);
  }

  private Outgoing.Work read(ResultSet row, int ignored) throws SQLException {
    return new Outgoing.Work(
        row.getObject("id", UUID.class),
        row.getObject("request_id", UUID.class),
        row.getString("peer"),
        row.getString("project"),
        row.getString("conversation"),
        decode(row.getString("message")),
        row.getString("state"),
        row.getBoolean("cancel_requested"),
        row.getString("remote_task"),
        row.getString("remote_context"),
        row.getString("result") == null ? null : decode(row.getString("result")),
        row.getString("error"),
        row.getLong("revision"),
        row.getTimestamp("created_at").toInstant());
  }

  private static String encode(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (java.io.IOException invalid) {
      throw new CallerFault("outgoing payload is not JSON");
    }
  }

  private static Map<String, Object> decode(String value) {
    try {
      return JSON.readValue(value, MAP);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("unreadable outgoing record", invalid);
    }
  }
}
