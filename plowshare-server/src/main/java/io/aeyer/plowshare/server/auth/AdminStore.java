package io.aeyer.plowshare.server.auth;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Password accounts and persistent server roles. The historical table name is retained for
 * compatibility.
 */
@Repository
public class AdminStore {

  private final JdbcTemplate jdbc;
  private org.springframework.context.ApplicationEventPublisher events;

  @org.springframework.beans.factory.annotation.Autowired
  public void useEvents(org.springframework.context.ApplicationEventPublisher events) {
    this.events = events;
  }

  public AdminStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One admin, if this server has one under that handle.
   *
   * <p>{@link Optional} and not a refusal, {@code ProjectStore.find}'s choice and for the same
   * reason: a handle a caller presents at login may simply not be one this server knows, and "there
   * is no such admin" is an ordinary answer to that rather than a mistake.
   */
  public Optional<AdminRecord> byHandle(String handle) {
    return jdbc
        .query(
            "SELECT handle, password_hash, must_change_password, created_at, server_admin, bootstrap, enabled, account_kind"
                + " FROM admins WHERE handle = ?",
            (rs, rowNum) ->
                new AdminRecord(
                    rs.getString("handle"),
                    rs.getString("password_hash"),
                    rs.getBoolean("must_change_password"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getBoolean("server_admin"),
                    rs.getBoolean("bootstrap"),
                    rs.getBoolean("enabled"),
                    rs.getString("account_kind")),
            handle)
        .stream()
        .findFirst();
  }

  /**
   * Create an admin. {@code must_change_password} is left to the column's own default — true — and
   * {@code created_at} to the column's own {@code now()}, so this method has exactly the two things
   * a caller actually decided: who, and what their password hashes to.
   *
   * <p>A handle already in the table fails this call with the ordinary primary-key violation rather
   * than a checked exception this class invents: {@link AdminSeed}, this method's only caller,
   * always checks {@link #byHandle(String)} first and never reaches this on a handle it already
   * found, so a violation here is a race between two boots rather than an outcome either caller is
   * meant to handle gracefully.
   *
   * @param handle the login name; must be non-blank, or the table's own {@code admins_handle_named}
   *     check refuses the insert
   * @param passwordHash a {@link PasswordHasher#hash(char[])} output; must be non-blank, or the
   *     table's own {@code admins_password_hash_named} check refuses the insert
   */
  @org.springframework.transaction.annotation.Transactional
  public void create(String handle, String passwordHash) {
    jdbc.update(
        "INSERT INTO admins (handle, password_hash, server_admin) VALUES (?, ?, TRUE)",
        handle,
        passwordHash);
    if (events != null)
      events.publishEvent(
          new io.aeyer.plowshare.server.personal.PersonalSpaces.AccountCreated(handle));
  }

  /**
   * The handle of an admin already in this table, if there is one and it is not {@code handle} —
   * {@link AdminSeed}'s question on a boot where the handle it was asked to seed comes back absent:
   * is this a fresh deployment, or an operator who changed {@code PLOWSHARE_ADMIN_HANDLE} against a
   * database that already has an account under a different name.
   *
   * <p><b>Where "one admin" is enforced, not where it is defined.</b> This table's own primary key
   * stops two rows sharing a handle and nothing more — {@code V37__admins.sql} does not stop {@link
   * #create(String, String)} being called twice under two different handles, and this class's own
   * header notes the schema does not need to change on the day that stops being true. {@code LIMIT
   * 1} is a deliberate choice to answer "is there another one", which is all {@link AdminSeed}
   * needs for its refusal message, and not "how many are there".
   *
   * @param handle the handle {@link AdminSeed} has just confirmed absent; excluded from the search
   *     so a caller cannot be handed its own answer back
   */
  public Optional<String> handleOfAnyOtherAdmin(String handle) {
    return jdbc
        .query(
            "SELECT handle FROM admins WHERE handle <> ? ORDER BY created_at ASC LIMIT 1",
            (rs, rowNum) -> rs.getString("handle"),
            handle)
        .stream()
        .findFirst();
  }

  /**
   * Set a new hash for {@code handle} and clear {@code must_change_password} in the same write —
   * the two must happen together, or a caller could observe a row whose password already changed
   * but which still reports that it must.
   *
   * <p>Unconditional on the current hash: {@code AuthController#changePassword} has already
   * verified the caller's current password with {@link PasswordHasher#matches} before this is ever
   * called, so this method has nothing left to check and nothing here repeats that comparison
   * against the database.
   *
   * <p>No return value, on {@link #create(String, String)}'s own shape: this class's one caller
   * here always calls {@link #byHandle(String)} first — to find the record whose current password
   * it is about to verify — and never reaches this on a handle it has not just confirmed exists. A
   * caller that raced this against a delete this class does not yet offer would update zero rows
   * silently, which is the same trade {@link #create(String, String)} already makes for a race
   * between two boots.
   *
   * @param handle the admin whose row to update; must already exist
   * @param passwordHash a fresh {@link PasswordHasher#hash(char[])} output
   */
  public void changePassword(String handle, String passwordHash) {
    jdbc.update(
        "UPDATE admins SET password_hash = ?, must_change_password = FALSE" + " WHERE handle = ?",
        passwordHash,
        handle);
  }

  public boolean changePassword(String handle, String passwordHash, String expectedHash) {
    return jdbc.update(
            "UPDATE admins SET password_hash=?,must_change_password=FALSE WHERE handle=? AND password_hash=? AND enabled",
            passwordHash,
            handle,
            expectedHash)
        == 1;
  }

  public boolean isServerAdmin(String handle) {
    return handle != null
        && !jdbc.queryForList(
                "SELECT 1 FROM admins WHERE handle = ? AND enabled AND server_admin AND NOT bootstrap AND account_kind='USER'",
                Integer.class,
                handle)
            .isEmpty();
  }

  public boolean isEnabled(String handle) {
    return handle != null
        && !jdbc.queryForList(
                "SELECT 1 FROM admins WHERE handle=? AND account_active(handle)",
                Integer.class,
                handle)
            .isEmpty();
  }

  public long sessionVersion(String handle) {
    return jdbc.queryForObject(
        "SELECT session_version FROM admins WHERE handle=?", Long.class, handle);
  }

  public boolean sessionCurrent(String handle, long version) {
    return !jdbc.queryForList(
            "SELECT 1 FROM admins WHERE handle=? AND account_active(handle) AND session_version=?",
            Integer.class,
            handle,
            version)
        .isEmpty();
  }

  public void requireServerAdmin(String handle) {
    if (!isServerAdmin(handle))
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Only a server administrator may manage server resources");
  }

  private <T> T setupTransaction(java.util.function.Supplier<T> work) {
    return new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getDataSource())))
        .execute(
            status -> {
              // Serializes first boot and setup across server processes sharing this database.
              jdbc.execute("SELECT pg_advisory_xact_lock(734921865)");
              return work.get();
            });
  }

  /** Never retain the plaintext. Pending setup gets a fresh credential on restart. */
  public boolean bootstrap(String passwordHash) {
    return setupTransaction(
        () -> {
          if (!jdbc.queryForList("SELECT 1 FROM admins WHERE NOT bootstrap", Integer.class)
              .isEmpty()) return false;
          jdbc.update(
              "INSERT INTO admins(handle, password_hash, server_admin, bootstrap) VALUES ('admin', ?, FALSE, TRUE)"
                  + " ON CONFLICT (handle) DO UPDATE SET password_hash = EXCLUDED.password_hash WHERE admins.bootstrap",
              passwordHash);
          return true;
        });
  }

  /** Consumes the temporary account and creates the chosen administrator in one commit. */
  public boolean finishSetup(
      String bootstrapHandle, String expectedHash, String handle, String passwordHash) {
    return setupTransaction(
        () -> {
          int consumed =
              jdbc.update(
                  "DELETE FROM admins WHERE handle = ? AND bootstrap AND password_hash = ?",
                  bootstrapHandle,
                  expectedHash);
          if (consumed != 1) return false;
          jdbc.update(
              "INSERT INTO admins(handle, password_hash, must_change_password, server_admin) VALUES (?, ?, FALSE, TRUE)",
              handle,
              passwordHash);
          if (events != null)
            events.publishEvent(
                new io.aeyer.plowshare.server.personal.PersonalSpaces.AccountCreated(handle));
          return true;
        });
  }
}
