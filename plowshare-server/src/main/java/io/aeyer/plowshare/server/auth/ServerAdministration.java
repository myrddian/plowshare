package io.aeyer.plowshare.server.auth;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Human operator account lifecycle. Every mutation and its audit entry commit together. */
@Service
public class ServerAdministration {
  private final JdbcTemplate jdbc;
  private final AdminStore accounts;
  private final PasswordHasher passwords;
  private final TokenStore tokens;
  private final ApplicationEventPublisher events;

  public ServerAdministration(
      JdbcTemplate jdbc,
      AdminStore accounts,
      PasswordHasher passwords,
      TokenStore tokens,
      ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.accounts = accounts;
    this.passwords = passwords;
    this.tokens = tokens;
    this.events = events;
  }

  public record Account(
      String handle,
      boolean enabled,
      boolean serverAdmin,
      boolean mustChangePassword,
      OffsetDateTime createdAt) {}

  public record Credential(Account account, String temporaryPassword) {
    @Override
    public String toString() {
      return "Credential[account=" + account + ", temporaryPassword=<redacted>]";
    }
  }

  public record Session(
      UUID id, boolean restricted, OffsetDateTime createdAt, OffsetDateTime expiresAt) {}

  public record Audit(
      long id,
      OffsetDateTime occurredAt,
      String actor,
      String action,
      String target,
      Boolean enabled,
      Boolean serverAdmin) {}

  public record AuditPage(List<Audit> entries, long before) {}

  public record Revoked(String handle) {}

  public record SessionsRevoked(String handle) {}

  private Account account(String handle) {
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

  public List<Account> list(String actor) {
    accounts.requireServerAdmin(actor);
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

  private <T> T change(String actor, Supplier<T> work) {
    return new TransactionTemplate(
            new DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getDataSource())))
        .execute(
            status -> {
              // Shared with initial setup; two administrators cannot concurrently remove the last
              // administrator.
              jdbc.execute("SELECT pg_advisory_xact_lock(734921865)");
              accounts.requireServerAdmin(actor);
              return work.get();
            });
  }

  private void audit(String actor, String action, Account target) {
    jdbc.update(
        "INSERT INTO admin_audit(actor,action,target,enabled,server_admin) VALUES (?,?,?,?,?)",
        actor,
        action,
        target.handle(),
        target.enabled(),
        target.serverAdmin());
  }

  public Credential create(String actor, String handle, boolean serverAdmin) {
    accounts.requireServerAdmin(actor);
    if (!handle.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
      throw new CallerFault(
          "Account handles need 1–64 letters, digits, dots, underscores or hyphens");
    String password = Tokens.mint();
    String hash = passwords.hash(password.toCharArray());
    return change(
        actor,
        () -> {
          if (accounts.byHandle(handle).isPresent())
            throw new CallerFault("That account handle already exists");
          jdbc.update(
              "INSERT INTO admins(handle,password_hash,server_admin) VALUES (?,?,?)",
              handle,
              hash,
              serverAdmin);
          events.publishEvent(new PersonalSpaces.AccountCreated(handle));
          Account created = account(handle);
          audit(actor, "account.create", created);
          return new Credential(created, password);
        });
  }

  public Account update(String actor, String handle, Boolean enabled, Boolean serverAdmin) {
    if (enabled == null && serverAdmin == null)
      throw new CallerFault("Supply enabled or serverAdmin");
    return change(
        actor,
        () -> {
          Account previous = account(handle);
          boolean nextEnabled = enabled == null ? previous.enabled() : enabled;
          boolean nextAdmin = serverAdmin == null ? previous.serverAdmin() : serverAdmin;
          if (previous.enabled()
              && previous.serverAdmin()
              && !(nextEnabled && nextAdmin)
              && jdbc.queryForObject(
                      "SELECT count(*) FROM admins WHERE enabled AND server_admin AND NOT bootstrap",
                      Long.class)
                  <= 1)
            throw new CallerFault(
                "The last enabled server administrator cannot be disabled or demoted");
          if (previous.enabled()
              && previous.serverAdmin()
              && !previous.mustChangePassword()
              && !(nextEnabled && nextAdmin)
              && jdbc.queryForObject(
                      "SELECT count(*) FROM admins WHERE enabled AND server_admin AND NOT bootstrap AND NOT must_change_password",
                      Long.class)
                  <= 1)
            throw new CallerFault(
                "Keep an enabled administrator with a completed password setup before removing this role");
          jdbc.update(
              "UPDATE admins SET enabled=?,server_admin=? WHERE handle=?",
              nextEnabled,
              nextAdmin,
              handle);
          if (!nextEnabled || previous.serverAdmin() != nextAdmin) revoke(handle);
          Account updated = account(handle);
          audit(actor, "account.update", updated);
          return updated;
        });
  }

  private void revoke(String handle) {
    jdbc.update("UPDATE admins SET session_version=session_version+1 WHERE handle=?", handle);
    jdbc.update("UPDATE auth_session_chains SET revoked=TRUE WHERE handle=?", handle);
    // Local transient credentials and sockets are retired only after the database commit.
    events.publishEvent(new SessionsRevoked(handle));
  }

  public Credential reset(String actor, String handle) {
    accounts.requireServerAdmin(actor);
    if (actor.equals(handle))
      throw new CallerFault("Change your own password through the authenticated password command");
    String password = Tokens.mint();
    String hash = passwords.hash(password.toCharArray());
    return change(
        actor,
        () -> {
          Account target = account(handle);
          jdbc.update(
              "UPDATE admins SET password_hash=?,must_change_password=TRUE WHERE handle=?",
              hash,
              handle);
          revoke(handle);
          audit(actor, "account.password.reset", target);
          return new Credential(account(handle), password);
        });
  }

  public Revoked revokeSessions(String actor, String handle) {
    return change(
        actor,
        () -> {
          Account target = account(handle);
          revoke(handle);
          audit(actor, "session.revoke", target);
          return new Revoked(handle);
        });
  }

  public List<Session> sessions(String actor, String handle) {
    accounts.requireServerAdmin(actor);
    account(handle);
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

  public AuditPage history(String actor, String handle, long before, int limit) {
    accounts.requireServerAdmin(actor);
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

  @org.springframework.transaction.event.TransactionalEventListener(
      phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
  public void retireCredentials(SessionsRevoked event) {
    tokens.retireTransientAccount(event.handle());
  }
}
