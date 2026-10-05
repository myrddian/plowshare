package io.aeyer.plowshare.server.auth;

import io.aeyer.plowshare.server.auth.ServerAdministration.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns account row mappings, the shared administrator lock and session revocation writes. */
@Repository
public class JdbcAccountAdministrationRepository implements AccountAdministrationRepository {
  private final JdbcTemplate jdbc;

  public JdbcAccountAdministrationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lockAdministrators() {
    jdbc.execute("SELECT pg_advisory_xact_lock(734921865)");
  }

  public Account account(String handle) {
    return jdbc
        .query(
            "SELECT handle, enabled, server_admin, must_change_password, created_at FROM admins WHERE handle=? AND NOT bootstrap AND account_kind='USER'",
            (rs, n) ->
                new Account(
                    rs.getString(1),
                    rs.getBoolean(2),
                    rs.getBoolean(3),
                    rs.getBoolean(4),
                    rs.getObject(5, OffsetDateTime.class)),
            handle)
        .stream()
        .findFirst()
        .orElseThrow(() -> new CallerFault("No permanent account has that handle"));
  }

  public List<Account> list() {
    return jdbc.query(
        "SELECT handle, enabled, server_admin, must_change_password, created_at FROM admins WHERE NOT bootstrap AND account_kind='USER' ORDER BY handle",
        (rs, n) ->
            new Account(
                rs.getString(1),
                rs.getBoolean(2),
                rs.getBoolean(3),
                rs.getBoolean(4),
                rs.getObject(5, OffsetDateTime.class)));
  }

  public void audit(String actor, String action, Account target) {
    jdbc.update(
        "INSERT INTO admin_audit(actor,action,target,enabled,server_admin) VALUES (?,?,?,?,?)",
        actor,
        action,
        target.handle(),
        target.enabled(),
        target.serverAdmin());
  }

  public List<Session> sessions(String handle) {
    return jdbc.query(
        "SELECT id,restricted,created_at,expires_at FROM auth_session_chains WHERE handle=? AND NOT revoked AND expires_at>now() ORDER BY created_at DESC,id",
        (rs, n) ->
            new Session(
                rs.getObject(1, UUID.class),
                rs.getBoolean(2),
                rs.getObject(3, OffsetDateTime.class),
                rs.getObject(4, OffsetDateTime.class)),
        handle);
  }

  public AuditPage history(String handle, long before, int limit) {
    if (before < 0 || limit < 1 || limit > 100)
      throw new CallerFault("Audit limit must be 1–100 and before must be nonnegative");
    var entries =
        jdbc.query(
            "SELECT id,occurred_at,actor,action,target,enabled,server_admin FROM admin_audit WHERE (?=0 OR id<?) AND (?='' OR target=?) ORDER BY id DESC LIMIT ?",
            (rs, n) ->
                new Audit(
                    rs.getLong(1),
                    rs.getObject(2, OffsetDateTime.class),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getObject(6, Boolean.class),
                    rs.getObject(7, Boolean.class)),
            before,
            before,
            handle,
            handle,
            limit);
    return new AuditPage(entries, entries.size() == limit ? entries.getLast().id() : 0);
  }

  @Override
  public void create(String handle, String hash, boolean administrator) {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES (?,?,?)",
        handle,
        hash,
        administrator);
  }

  @Override
  public long enabledAdministrators(boolean passwordSetupComplete) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM admins WHERE enabled AND server_admin AND NOT bootstrap"
            + (passwordSetupComplete ? " AND NOT must_change_password" : ""),
        Long.class);
  }

  @Override
  public void update(String handle, boolean enabled, boolean administrator) {
    if (jdbc.update(
            "UPDATE admins SET enabled=?,server_admin=? WHERE handle=? AND NOT bootstrap AND account_kind='USER'",
            enabled,
            administrator,
            handle)
        != 1) throw new CallerFault("No permanent account has that handle");
  }

  @Override
  public void resetPassword(String handle, String hash) {
    if (jdbc.update(
            "UPDATE admins SET password_hash=?,must_change_password=TRUE WHERE handle=? AND NOT bootstrap AND account_kind='USER'",
            hash,
            handle)
        != 1) throw new CallerFault("No permanent account has that handle");
  }

  @Override
  public void revokeSessions(String handle) {
    if (jdbc.update("UPDATE admins SET session_version=session_version+1 WHERE handle=?", handle)
        != 1) throw new CallerFault("No account has that handle");
    jdbc.update("UPDATE auth_session_chains SET revoked=TRUE WHERE handle=?", handle);
  }
}
