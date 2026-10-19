package io.aeyer.plowshare.server.auth;

import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.ServiceAccounts.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

/** Owns service token row mappings and scope locks; no credential minting or permission policy. */
@Repository
public class JdbcServiceAccountRepository implements ServiceAccountRepository {
  private final JdbcTemplate jdbc;

  public JdbcServiceAccountRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Account account(String handle, boolean lock) {
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

  public List<Scope> scopes(UUID id) {
    return jdbc.query(
        "SELECT p.name,s.role FROM service_token_scopes s JOIN projects p ON p.id=s.project_id WHERE s.token_id=? ORDER BY p.name",
        (rs, n) -> new Scope(rs.getString(1), ProjectRole.parse(rs.getString(2))),
        id);
  }

  public Token token(String handle, UUID id, boolean lock) {
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

  public void audit(String actor, String action, String target) {
    jdbc.update(
        "INSERT INTO admin_audit(actor,action,target) VALUES (?,?,?)", actor, action, target);
  }

  public List<Account> list() {
    return jdbc.query(
        "SELECT handle,enabled,created_at FROM admins WHERE account_kind='SERVICE' ORDER BY handle",
        (rs, n) ->
            new Account(rs.getString(1), rs.getBoolean(2), rs.getObject(3, OffsetDateTime.class)));
  }

  public List<Token> tokens(String handle) {
    // Display rows and all their scopes share one statement snapshot. The owner predicate is on
    // tokens, so an empty scope set survives the LEFT JOIN without adopting another owner's row.
    return jdbc.query(
        "SELECT t.id,t.name,t.principal_handle,t.created_at,t.expires_at,t.revoked_at,p.name AS project,s.role"
            + " FROM service_tokens t LEFT JOIN service_token_scopes s ON s.token_id=t.id"
            + " LEFT JOIN projects p ON p.id=s.project_id WHERE t.owner_handle=?"
            + " ORDER BY t.created_at,t.id,p.name",
        (ResultSetExtractor<List<Token>>)
            rows -> {
              var tokens = new ArrayList<Token>();
              Token current = null;
              var scopes = new ArrayList<Scope>();
              while (rows.next()) {
                UUID id = rows.getObject("id", UUID.class);
                if (current == null || !current.id().equals(id)) {
                  if (current != null) tokens.add(withScopes(current, scopes));
                  scopes.clear();
                  current =
                      new Token(
                          id,
                          rows.getString("name"),
                          rows.getString("principal_handle"),
                          rows.getObject("created_at", OffsetDateTime.class),
                          rows.getObject("expires_at", OffsetDateTime.class),
                          rows.getObject("revoked_at", OffsetDateTime.class),
                          List.of());
                }
                String project = rows.getString("project");
                if (project != null)
                  scopes.add(new Scope(project, ProjectRole.parse(rows.getString("role"))));
              }
              if (current != null) tokens.add(withScopes(current, scopes));
              return List.copyOf(tokens);
            },
        handle);
  }

  private static Token withScopes(Token token, List<Scope> scopes) {
    return new Token(
        token.id(),
        token.name(),
        token.principal(),
        token.createdAt(),
        token.expiresAt(),
        token.revokedAt(),
        List.copyOf(scopes));
  }

  @Override
  public void lockAdministrators() {
    jdbc.execute("SELECT pg_advisory_xact_lock(734921865)");
  }

  @Override
  public void create(String handle) {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin,account_kind) VALUES (?,'!service-account',FALSE,FALSE,'SERVICE')",
        handle);
  }

  @Override
  public void update(String handle, boolean enabled) {
    if (jdbc.update(
            "UPDATE admins SET enabled=? WHERE handle=? AND account_kind='SERVICE'",
            enabled,
            handle)
        != 1) throw new CallerFault("No service account has that handle");
  }

  @Override
  public List<String> revokeOwnedTokens(String handle) {
    return jdbc.queryForList(
        "UPDATE service_tokens SET revoked_at=COALESCE(revoked_at,now()) WHERE owner_handle=? RETURNING principal_handle",
        String.class,
        handle);
  }

  @Override
  public void retirePrincipal(String principal) {
    if (jdbc.update("UPDATE admins SET session_version=session_version+1 WHERE handle=?", principal)
        != 1) throw new CallerFault("No service principal has that handle");
  }

  @Override
  public boolean lockOrdinaryProject(String project) {
    return !jdbc.queryForList(
            "SELECT id FROM projects WHERE name=? AND personal_owner IS NULL FOR SHARE",
            Long.class,
            project)
        .isEmpty();
  }

  @Override
  public boolean hasTokenName(String handle, String name) {
    return !jdbc.queryForList(
            "SELECT 1 FROM service_tokens WHERE owner_handle=? AND name=?",
            Integer.class,
            handle,
            name)
        .isEmpty();
  }

  @Override
  public void issue(
      UUID id,
      String handle,
      String name,
      String principal,
      String digest,
      OffsetDateTime expires,
      List<Scope> scopes) {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin,account_kind) VALUES (?,'!service-token',FALSE,FALSE,'SERVICE_TOKEN')",
        principal);
    jdbc.update(
        "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at) VALUES (?,?,?,?,?,?)",
        id,
        handle,
        name,
        principal,
        digest,
        expires);
    for (Scope scope : scopes) {
      if (jdbc.update(
              "INSERT INTO service_token_scopes(token_id,project_id,role) SELECT ?,id,? FROM projects WHERE name=? AND personal_owner IS NULL",
              id,
              scope.role().name(),
              scope.project())
          != 1) throw new CallerFault("No ordinary server project has that name");
    }
  }

  @Override
  public boolean applicationOwned(UUID id) {
    return !jdbc.queryForList(
            "SELECT 1 FROM application_identities WHERE token_id=?", Integer.class, id)
        .isEmpty();
  }

  @Override
  public void rotate(UUID id, String digest, OffsetDateTime expires) {
    if (jdbc.update(
            "UPDATE service_tokens SET digest=?,expires_at=?,revoked_at=NULL WHERE id=?",
            digest,
            expires,
            id)
        != 1) throw new CallerFault("No service token has that ID");
  }

  @Override
  public void revoke(UUID id) {
    if (jdbc.update(
            "UPDATE service_tokens SET revoked_at=COALESCE(revoked_at,now()) WHERE id=?", id)
        != 1) throw new CallerFault("No service token has that ID");
  }
}
