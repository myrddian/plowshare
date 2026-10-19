package io.aeyer.plowshare.server.relay.tools;

import com.fasterxml.jackson.databind.*;
import io.aeyer.plowshare.protocol.HostedToolScopes.*;
import io.aeyer.plowshare.protocol.ToolScopes;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** All JDBC and stored JSON conversion for durable hosting. UUID tombstones cannot be adopted. */
public final class JdbcHostedToolScopeRepository implements HostedToolScopeRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public JdbcHostedToolScopeRepository(JdbcTemplate jdbc, ObjectMapper source) {
    this.jdbc = Objects.requireNonNull(jdbc);
    json =
        source
            .copy()
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("Invalid retained hosted scope");
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("Invalid hosted scope DTO");
    }
  }

  private Binding binding(ResultSet r, int n) throws SQLException {
    var d = read(r.getString("definition"), Definition.class);
    String project = r.getString("project");
    UUID id = r.getObject("id", UUID.class);
    long generation = r.getLong("generation");
    var route =
        new ToolScopes.Connection(
            project,
            d.scope(),
            d.provider(),
            io.aeyer.plowshare.protocol.HostedToolScopes.route(id, generation),
            d.executor(),
            d.prefix(),
            d.grants(),
            d.agents(),
            d.leaseSeconds());
    return new Binding(
        id,
        project,
        r.getString("owner"),
        generation,
        r.getBoolean("enabled"),
        r.getBoolean("deleted"),
        r.getBoolean("application"),
        d,
        r.getString("credential_subject"),
        route);
  }

  private static final String SELECT =
      "SELECT b.*,p.name AS project FROM hosted_tool_scopes b JOIN projects p ON p.id=b.project_id ";

  public void lockProject(String project) {
    io.aeyer.plowshare.protocol.HostedToolScopes.project(project);
    if (jdbc.queryForList("SELECT id FROM projects WHERE name=? FOR UPDATE", Long.class, project)
        .isEmpty()) throw new CallerFault("Hosted project unavailable");
  }

  public List<Binding> list(String project, String owner) {
    io.aeyer.plowshare.protocol.HostedToolScopes.project(project);
    return List.copyOf(
        jdbc.query(
            SELECT
                + "WHERE p.name=?"
                + (owner == null ? "" : " AND b.owner=?")
                + " ORDER BY b.id LIMIT 4097",
            this::binding,
            owner == null ? new Object[] {project} : new Object[] {project, owner}));
  }

  public Optional<Binding> find(UUID id, boolean lock) {
    Objects.requireNonNull(id);
    return jdbc
        .query(SELECT + "WHERE b.id=?" + (lock ? " FOR UPDATE OF b" : ""), this::binding, id)
        .stream()
        .findFirst();
  }

  public Optional<Binding> route(String project, String route, boolean lock) {
    return jdbc
        .query(
            SELECT + "WHERE p.name=? AND b.route=?" + (lock ? " FOR UPDATE OF b" : ""),
            this::binding,
            project,
            route)
        .stream()
        .findFirst();
  }

  public void save(Binding b, Long expected) {
    Objects.requireNonNull(b);
    int count;
    if (expected == null) {
      if (b.generation() != 1)
        throw new IllegalArgumentException("New host generation must be one");
      count =
          jdbc.update(
              "INSERT INTO hosted_tool_scopes(id,project_id,owner,generation,enabled,deleted,application,scope,route,connection_id,credential_subject,definition) SELECT ?,p.id,?,?,?,?,?,?,?,?,?,?::jsonb FROM projects p WHERE p.name=? ON CONFLICT(id) DO NOTHING",
              b.binding(),
              b.owner(),
              b.generation(),
              b.enabled(),
              b.deleted(),
              b.application(),
              b.definition().scope(),
              b.route().provider(),
              b.definition().connection(),
              b.credentialSubject(),
              write(b.definition()),
              b.project());
    } else {
      if (b.generation() != expected + 1)
        throw new IllegalArgumentException("Hosted generation must advance once");
      count =
          jdbc.update(
              "UPDATE hosted_tool_scopes b SET generation=?,enabled=?,deleted=?,application=?,scope=?,route=?,connection_id=?,credential_subject=?,definition=?::jsonb FROM projects p WHERE p.id=b.project_id AND p.name=? AND b.id=? AND b.owner=? AND b.generation=? AND NOT b.deleted",
              b.generation(),
              b.enabled(),
              b.deleted(),
              b.application(),
              b.definition().scope(),
              b.route().provider(),
              b.definition().connection(),
              b.credentialSubject(),
              write(b.definition()),
              b.project(),
              b.binding(),
              b.owner(),
              expected);
    }
    if (count != 1)
      throw new CallerFault("Hosted binding changed or UUID is retained; inspect status");
  }

  public Optional<Lease> lease(UUID id) {
    var b = find(id, false);
    return b.flatMap(this::lease);
  }

  @Override
  public Optional<Lease> lease(Binding binding) {
    return leases(List.of(binding)).stream().findFirst();
  }

  @Override
  public List<Lease> leases(List<Binding> bindings) {
    Objects.requireNonNull(bindings);
    var result = new ArrayList<Lease>();
    // Definitions are immutable within a generation. Match both supplied and current generations
    // in this fresh statement: a stale unlocked snapshot must not authorize an old worker.
    for (int offset = 0; offset < bindings.size(); offset += 256) {
      var cohort = bindings.subList(offset, Math.min(bindings.size(), offset + 256));
      var retained = new HashMap<UUID, Binding>();
      var arguments = new ArrayList<Object>();
      for (var binding : cohort) {
        if (retained.put(binding.binding(), binding) != null)
          throw new IllegalArgumentException("Duplicate hosted binding");
        arguments.add(binding.binding());
        arguments.add(binding.generation());
      }
      String values = String.join(",", Collections.nCopies(cohort.size(), "(?::uuid,?::bigint)"));
      result.addAll(
          jdbc.query(
              "SELECT w.* FROM (VALUES "
                  + values
                  + ") expected(id,generation)"
                  + " JOIN hosted_tool_scopes b ON b.id=expected.id AND b.generation=expected.generation"
                  + " JOIN hosted_tool_scope_workers w ON w.binding_id=b.id",
              (row, index) -> {
                var binding = retained.get(row.getObject("binding_id", UUID.class));
                if (row.getLong("binding_generation") != binding.generation())
                  throw new IllegalStateException("Worker fence generation mismatch");
                return new Lease(
                    new Registration(
                        binding,
                        row.getObject("registration", UUID.class),
                        row.getObject("registered_at", OffsetDateTime.class),
                        row.getObject("expires_at", OffsetDateTime.class)),
                    row.getString("session"),
                    row.getString("socket"),
                    row.getObject("server", UUID.class),
                    row.getLong("catalogue_after"));
              },
              arguments.toArray()));
    }
    return List.copyOf(result);
  }

  public void lease(Lease l) {
    var r = l.registration();
    jdbc.update(
        "INSERT INTO hosted_tool_scope_workers(binding_id,registration,binding_generation,session,socket,server,registered_at,expires_at,catalogue_after) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(binding_id) DO UPDATE SET registration=excluded.registration,binding_generation=excluded.binding_generation,session=excluded.session,socket=excluded.socket,server=excluded.server,registered_at=excluded.registered_at,expires_at=excluded.expires_at,catalogue_after=excluded.catalogue_after",
        r.binding().binding(),
        r.registration(),
        r.binding().generation(),
        l.session(),
        l.socket(),
        l.server(),
        r.registeredAt(),
        r.expiresAt(),
        l.catalogueAfter());
  }

  public void clearLease(UUID id) {
    jdbc.update("DELETE FROM hosted_tool_scope_workers WHERE binding_id=?", id);
  }

  public void reserve(UUID request, UUID binding, String actor, String operation) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(binding);
    int count =
        jdbc.update(
            "INSERT INTO hosted_tool_scope_mutations(request_id,binding_id,actor,operation) VALUES(?,?,?,?) ON CONFLICT(request_id) DO NOTHING",
            request,
            binding,
            actor,
            operation);
    if (count != 1)
      throw new CallerFault(
          "Hosted mutation UUID already committed; inspect status or receipt without replay");
  }

  public void receipt(String actor, Receipt receipt) {
    int count =
        jdbc.update(
            "UPDATE hosted_tool_scope_mutations SET receipt=?::jsonb WHERE request_id=? AND actor=? AND binding_id=? AND operation=? AND receipt IS NULL",
            write(receipt),
            receipt.requestId(),
            actor,
            receipt.binding().binding(),
            receipt.operation());
    if (count != 1) throw new IllegalStateException("Missing hosted mutation reservation");
  }

  public Optional<Receipt> receipt(String actor, String project, UUID request) {
    return jdbc
        .query(
            "SELECT receipt::text FROM hosted_tool_scope_mutations WHERE request_id=? AND actor=? AND receipt->'binding'->>'project'=? AND receipt IS NOT NULL",
            (r, n) -> read(r.getString(1), Receipt.class),
            request,
            actor,
            project)
        .stream()
        .findFirst();
  }
}
