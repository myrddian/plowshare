package io.aeyer.plowshare.server.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Conditional writes and connection-first row locks serialize configuration and credential intents.
 */
public final class JdbcProviderCredentialRepository implements ProviderCredentialRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcProviderCredentialRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = ProviderJson.closed(mapper);
  }

  private <T> T decode(String json, Class<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("Invalid persisted provider contract");
    }
  }

  private String encode(Object dto) {
    try {
      return mapper.writeValueAsString(dto);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("Cannot persist provider contract");
    }
  }

  @Override
  public void lockProject(String project) {
    io.aeyer.plowshare.protocol.ProviderCredentials.project(project);
    if (jdbc.queryForList("SELECT id FROM projects WHERE name=? FOR UPDATE", Long.class, project)
        .isEmpty())
      throw new CallerFault("Connection UUID is retained or project no longer exists");
  }

  private Connection connection(ResultSet row, int number) throws SQLException {
    return new Connection(
        row.getObject("id", UUID.class),
        row.getString("project"),
        row.getObject("revision", UUID.class),
        row.getBoolean("enabled"),
        row.getBoolean("deleted"),
        decode(row.getString("definition"), Definition.class));
  }

  @Override
  public List<Connection> list(String project) {
    io.aeyer.plowshare.protocol.ProviderCredentials.project(project);
    return List.copyOf(
        jdbc.query(
            "SELECT c.*,p.name AS project FROM provider_connections c JOIN projects p ON p.id=c.project_id WHERE p.name=? ORDER BY c.id LIMIT 257",
            this::connection,
            project));
  }

  @Override
  public Optional<Connection> connection(String project, UUID id, boolean lock) {
    io.aeyer.plowshare.protocol.ProviderCredentials.project(project);
    Objects.requireNonNull(id);
    return jdbc
        .query(
            "SELECT c.*,p.name AS project FROM provider_connections c JOIN projects p ON p.id=c.project_id WHERE p.name=? AND c.id=?"
                + (lock ? " FOR UPDATE OF c" : ""),
            this::connection,
            project,
            id)
        .stream()
        .findFirst();
  }

  @Override
  public void create(Connection value) {
    Objects.requireNonNull(value);
    int inserted =
        jdbc.update(
            "INSERT INTO provider_connections(id,project_id,revision,enabled,deleted,definition) SELECT ?,p.id,?,?,?,?::jsonb FROM projects p WHERE p.name=? ON CONFLICT(id) DO NOTHING",
            value.connection(),
            value.revision(),
            value.enabled(),
            value.deleted(),
            encode(value.definition()),
            value.project());
    if (inserted != 1)
      throw new CallerFault("Connection UUID is retained or project no longer exists");
  }

  @Override
  public void update(Connection value, UUID expectedRevision) {
    Objects.requireNonNull(value);
    Objects.requireNonNull(expectedRevision);
    int changed =
        jdbc.update(
            "UPDATE provider_connections c SET revision=?,enabled=?,deleted=?,definition=?::jsonb FROM projects p WHERE c.project_id=p.id AND p.name=? AND c.id=? AND c.revision=?",
            value.revision(),
            value.enabled(),
            value.deleted(),
            encode(value.definition()),
            value.project(),
            value.connection(),
            expectedRevision);
    if (changed != 1) throw new CallerFault("Provider connection revision changed; inspect status");
  }

  private Stored credential(ResultSet row, int number) throws SQLException {
    String key = row.getString("key_id");
    var metadata =
        new Credential(
            row.getObject("connection_id", UUID.class),
            row.getString("project"),
            row.getString("subject"),
            row.getObject("generation", UUID.class),
            row.getObject("connection_revision", UUID.class),
            State.valueOf(row.getString("state")),
            row.getObject("expires_at", OffsetDateTime.class),
            key);
    return new Stored(
        metadata,
        key == null
            ? null
            : new CredentialCipher.Sealed(key, row.getString("nonce"), row.getString("ciphertext")),
        row.getObject("attempt", UUID.class),
        row.getString("state_hash"),
        row.getString("initiator"),
        row.getObject("intent_expires_at", OffsetDateTime.class));
  }

  private static final String CREDENTIAL =
      "SELECT g.*,p.name AS project FROM provider_credentials g JOIN provider_connections c ON c.id=g.connection_id JOIN projects p ON p.id=c.project_id ";

  @Override
  public Optional<Stored> credential(UUID connection, String subject, boolean lock) {
    Objects.requireNonNull(connection);
    io.aeyer.plowshare.protocol.ProviderCredentials.subject(subject);
    return jdbc
        .query(
            CREDENTIAL
                + "WHERE g.connection_id=? AND g.subject=?"
                + (lock ? " FOR UPDATE OF g" : ""),
            this::credential,
            connection,
            subject)
        .stream()
        .findFirst();
  }

  @Override
  public void store(Stored value, UUID expectedGeneration) {
    Objects.requireNonNull(value);
    var m = value.metadata();
    if (m.generation() == null)
      throw new IllegalArgumentException("Do not persist absent credentials");
    var sealed = value.material();
    Object[] fields = {
      m.generation(),
      m.connectionRevision(),
      m.state().name(),
      m.expiresAt(),
      m.keyId(),
      sealed == null ? null : sealed.nonce(),
      sealed == null ? null : sealed.ciphertext(),
      value.attempt(),
      value.stateHash(),
      value.initiator(),
      value.intentExpiresAt(),
      m.connection(),
      m.subject()
    };
    int changed;
    if (expectedGeneration == null) {
      changed =
          jdbc.update(
              "INSERT INTO provider_credentials(generation,connection_revision,state,expires_at,key_id,nonce,ciphertext,attempt,state_hash,initiator,intent_expires_at,connection_id,subject) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(connection_id,subject) DO NOTHING",
              fields);
    } else {
      Object[] update = Arrays.copyOf(fields, 14);
      update[13] = expectedGeneration;
      changed =
          jdbc.update(
              "UPDATE provider_credentials SET generation=?,connection_revision=?,state=?,expires_at=?,key_id=?,nonce=?,ciphertext=?,attempt=?,state_hash=?,initiator=?,intent_expires_at=? WHERE connection_id=? AND subject=? AND generation=?",
              update);
    }
    if (changed != 1)
      throw new CallerFault("Provider credential generation changed; inspect status");
  }

  @Override
  public void revokeAll(UUID connection, UUID connectionRevision) {
    Objects.requireNonNull(connection);
    Objects.requireNonNull(connectionRevision);
    // Configuration revision is sufficient to fence callbacks; each subject also gets a fresh
    // generation.
    jdbc.update(
        "UPDATE provider_credentials SET generation=gen_random_uuid(),connection_revision=?,state='REVOKED',expires_at=NULL,key_id=NULL,nonce=NULL,ciphertext=NULL,attempt=NULL,state_hash=NULL,initiator=NULL,intent_expires_at=NULL WHERE connection_id=?",
        connectionRevision,
        connection);
  }

  @Override
  public Optional<Stored> authorization(String stateHash) {
    if (stateHash == null || !stateHash.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid authorization state digest");
    return jdbc.query(CREDENTIAL + "WHERE g.state_hash=?", this::credential, stateHash).stream()
        .findFirst();
  }

  @Override
  public void commit(String actor, Receipt receipt) {
    io.aeyer.plowshare.protocol.ProviderCredentials.subject(actor);
    Objects.requireNonNull(receipt);
    if (jdbc.update(
            "INSERT INTO provider_credential_receipts(request_id,actor,project_name,receipt) VALUES (?,?,?,?::jsonb) ON CONFLICT(request_id) DO NOTHING",
            receipt.requestId(),
            actor,
            receipt.connection().project(),
            encode(receipt))
        != 1)
      throw new CallerFault("Mutation UUID already committed; read its receipt without replay");
  }

  @Override
  public Optional<Committed> receipt(UUID requestId) {
    Objects.requireNonNull(requestId);
    return jdbc
        .query(
            "SELECT actor,receipt FROM provider_credential_receipts WHERE request_id=?",
            (row, n) -> new Committed(row.getString(1), decode(row.getString(2), Receipt.class)),
            requestId)
        .stream()
        .findFirst();
  }
}
