package io.aeyer.plowshare.server.auth;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Operator-managed service identities and independently revocable project credentials. */
@Service
public class ServiceAccounts {
  private final JdbcTemplate jdbc;
  private final AdminStore accounts;
  private final ProjectMembers members;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;

  public ServiceAccounts(
      JdbcTemplate jdbc,
      AdminStore accounts,
      ProjectMembers members,
      ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.accounts = accounts;
    this.members = members;
    this.events = events;
    transactions =
        new TransactionTemplate(
            new DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getDataSource())));
  }

  public record Account(String handle, boolean enabled, OffsetDateTime createdAt) {}

  public record Scope(String project, ProjectRole role) {}

  public record Token(
      UUID id,
      String name,
      String principal,
      OffsetDateTime createdAt,
      OffsetDateTime expiresAt,
      OffsetDateTime revokedAt,
      List<Scope> scopes) {}

  public record Credential(Token token, String credential) {
    @Override
    public String toString() {
      return "Credential[token=" + token + ", credential=<redacted>]";
    }
  }

  private Account account(String handle, boolean lock) {
    return jdbc
        .query(
            "SELECT handle,enabled,created_at FROM admins WHERE handle=? AND account_kind='SERVICE'"
                + (lock ? " FOR UPDATE" : ""),
            (rs, n) ->
                new Account(
                    rs.getString(1), rs.getBoolean(2), rs.getObject(3, OffsetDateTime.class)),
            handle)
        .stream()
        .findFirst()
        .orElseThrow(() -> new CallerFault("No service account has that handle"));
  }

  public List<Account> list(String actor) {
    accounts.requireServerAdmin(actor);
    return jdbc.query(
        "SELECT handle,enabled,created_at FROM admins WHERE account_kind='SERVICE' ORDER BY handle",
        (rs, n) ->
            new Account(rs.getString(1), rs.getBoolean(2), rs.getObject(3, OffsetDateTime.class)));
  }

  private void audit(String actor, String action, String target) {
    jdbc.update(
        "INSERT INTO admin_audit(actor,action,target) VALUES (?,?,?)", actor, action, target);
  }

  public Account create(String actor, String handle) {
    accounts.requireServerAdmin(actor);
    if (handle == null || !handle.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
      throw new CallerFault("Supply a valid account handle");
    return transactions.execute(
        status -> {
          jdbc.execute("SELECT pg_advisory_xact_lock(734921865)");
          accounts.requireServerAdmin(actor);
          if (accounts.byHandle(handle).isPresent())
            throw new CallerFault("That account handle already exists");
          jdbc.update(
              "INSERT INTO admins(handle,password_hash,must_change_password,server_admin,account_kind) VALUES (?,'!service-account',FALSE,FALSE,'SERVICE')",
              handle);
          audit(actor, "service.account.create", handle);
          return account(handle, false);
        });
  }

  private void retire(String principal) {
    jdbc.update("UPDATE admins SET session_version=session_version+1 WHERE handle=?", principal);
    events.publishEvent(new ServerAdministration.SessionsRevoked(principal));
  }

  public Account update(String actor, String handle, boolean enabled) {
    return transactions.execute(
        status -> {
          jdbc.execute("SELECT pg_advisory_xact_lock(734921865)");
          accounts.requireServerAdmin(actor);
          account(handle, true);
          jdbc.update("UPDATE admins SET enabled=? WHERE handle=?", enabled, handle);
          if (!enabled) {
            var principals =
                jdbc.queryForList(
                    "UPDATE service_tokens SET revoked_at=COALESCE(revoked_at,now()) WHERE owner_handle=? RETURNING principal_handle",
                    String.class,
                    handle);
            principals.forEach(this::retire);
          }
          audit(actor, "service.account.update", handle);
          return account(handle, false);
        });
  }

  private List<Scope> scopes(UUID id) {
    return jdbc.query(
        "SELECT p.name,s.role FROM service_token_scopes s JOIN projects p ON p.id=s.project_id WHERE s.token_id=? ORDER BY p.name",
        (rs, n) -> new Scope(rs.getString(1), ProjectRole.parse(rs.getString(2))),
        id);
  }

  private Token token(String handle, UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT id,name,principal_handle,created_at,expires_at,revoked_at FROM service_tokens WHERE owner_handle=? AND id=?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, n) ->
                new Token(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getObject(4, OffsetDateTime.class),
                    rs.getObject(5, OffsetDateTime.class),
                    rs.getObject(6, OffsetDateTime.class),
                    scopes(id)),
            handle,
            id)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new CallerFault("No token with that ID belongs to this service account"));
  }

  public List<Token> tokens(String actor, String handle) {
    accounts.requireServerAdmin(actor);
    account(handle, false);
    return jdbc
        .queryForList(
            "SELECT id FROM service_tokens WHERE owner_handle=? ORDER BY created_at,id",
            UUID.class,
            handle)
        .stream()
        .map(id -> token(handle, id, false))
        .toList();
  }

  private OffsetDateTime expiry(int days) {
    if (days < 1 || days > 365) throw new CallerFault("expiresInDays must be 1–365");
    return OffsetDateTime.now(ZoneOffset.UTC).plusDays(days);
  }

  private void validateScopes(String handle, List<Scope> scopes) {
    if (scopes == null || scopes.isEmpty() || scopes.size() > 100)
      throw new CallerFault("Supply 1–100 project scopes");
    if (scopes.stream()
        .anyMatch(scope -> scope == null || scope.project() == null || scope.role() == null))
      throw new CallerFault("Token scopes need project and role");
    var names = new java.util.HashSet<String>();
    for (Scope scope :
        scopes.stream().sorted(java.util.Comparator.comparing(Scope::project)).toList()) {
      String project = scope.project();
      if (project == null
          || scope.role() == null
          || !names.add(project)
          || project.startsWith("personal:")
          || project.startsWith("Personal:")
          || project.startsWith("client:"))
        throw new CallerFault("Token scopes must name distinct ordinary server projects and roles");
      if (jdbc.queryForList(
              "SELECT id FROM projects WHERE name=? AND personal_owner IS NULL FOR SHARE",
              Long.class,
              project)
          .isEmpty()) throw new CallerFault("No ordinary server project has that name");
      members.requireRole(project, handle, scope.role());
    }
  }

  public Credential issue(String actor, String handle, String name, List<Scope> scopes, int days) {
    OffsetDateTime expires = expiry(days);
    if (name == null || name.isBlank() || name.length() > 64)
      throw new CallerFault("Token name needs 1–64 characters");
    return transactions.execute(
        status -> {
          jdbc.execute("SELECT pg_advisory_xact_lock(734921865)");
          accounts.requireServerAdmin(actor);
          if (!account(handle, true).enabled())
            throw new CallerFault("Enable this service account before issuing a token");
          validateScopes(handle, scopes);
          if (!jdbc.queryForList(
                  "SELECT 1 FROM service_tokens WHERE owner_handle=? AND name=?",
                  Integer.class,
                  handle,
                  name)
              .isEmpty())
            throw new CallerFault("That token name exists; rotate its credential instead");
          UUID id = UUID.randomUUID();
          String principal = "@service/" + id;
          String credential = ServiceCredentials.PREFIX + Tokens.mint();
          jdbc.update(
              "INSERT INTO admins(handle,password_hash,must_change_password,server_admin,account_kind) VALUES (?,'!service-token',FALSE,FALSE,'SERVICE_TOKEN')",
              principal);
          jdbc.update(
              "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at) VALUES (?,?,?,?,?,?)",
              id,
              handle,
              name,
              principal,
              Tokens.hash(credential),
              expires);
          for (Scope scope : scopes)
            jdbc.update(
                "INSERT INTO service_token_scopes(token_id,project_id,role) SELECT ?,id,? FROM projects WHERE name=?",
                id,
                scope.role().name(),
                scope.project());
          audit(actor, "service.token.create", handle + "/" + id);
          return new Credential(token(handle, id, false), credential);
        });
  }

  public Credential rotate(String actor, String handle, UUID id, int days) {
    OffsetDateTime expires = expiry(days);
    return transactions.execute(
        status -> {
          jdbc.execute("SELECT pg_advisory_xact_lock(734921865)");
          accounts.requireServerAdmin(actor);
          if (!account(handle, true).enabled())
            throw new CallerFault("Enable this service account before rotating a token");
          Token previous = token(handle, id, true);
          validateScopes(handle, previous.scopes());
          String credential = ServiceCredentials.PREFIX + Tokens.mint();
          jdbc.update(
              "UPDATE service_tokens SET digest=?,expires_at=?,revoked_at=NULL WHERE id=?",
              Tokens.hash(credential),
              expires,
              id);
          retire(previous.principal());
          audit(actor, "service.token.rotate", handle + "/" + id);
          return new Credential(token(handle, id, false), credential);
        });
  }

  public Token revoke(String actor, String handle, UUID id) {
    return transactions.execute(
        status -> {
          jdbc.execute("SELECT pg_advisory_xact_lock(734921865)");
          accounts.requireServerAdmin(actor);
          account(handle, true);
          Token previous = token(handle, id, true);
          jdbc.update(
              "UPDATE service_tokens SET revoked_at=COALESCE(revoked_at,now()) WHERE id=?", id);
          retire(previous.principal());
          audit(actor, "service.token.revoke", handle + "/" + id);
          return token(handle, id, false);
        });
  }
}
