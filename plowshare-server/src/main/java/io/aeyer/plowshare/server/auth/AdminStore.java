package io.aeyer.plowshare.server.auth;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code admins} table: who this server's one console login is, and the
 * hash it checks a presented password against.
 *
 * <p>Once thinner than this. It used to hold no {@code changePassword}, no
 * {@code delete} and no listing, on the reasoning that a method with no
 * caller yet is a method nothing here can test honestly — true of {@code
 * delete} and a listing still, and no longer true of a password write:
 * {@code AuthController#changePassword} — {@code POST /v1/auth/password} — is
 * the caller {@link #changePassword(String, String)} exists for, and it is
 * the method that closes the gap the rest of this file's history left open.
 * {@code must_change_password} could be stored and read from the moment
 * {@code V37__admins.sql} shipped, and nothing before this method could ever
 * turn it back off — every admin this server ever seeds would have carried it
 * forever, on a fifteen-minute access token with no refresh and no escape
 * short of hand-editing the row. That is not a corner this class was free to
 * leave for "later": a store that can set the flag and never clear it is not
 * thin, it is incomplete in the one direction that matters.
 *
 * <p>Handle rather than a surrogate id, on {@code ProjectStore}'s reasoning for
 * {@code name} everywhere it appears: this server has exactly one login
 * identifier for an admin, nothing else in this schema references an admin row
 * by a foreign key yet, and a second column that exists only to be the primary
 * key would be a key nothing reads.
 */
@Repository
public class AdminStore {

    private final JdbcTemplate jdbc;

    public AdminStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One admin, if this server has one under that handle.
     *
     * <p>{@link Optional} and not a refusal, {@code ProjectStore.find}'s
     * choice and for the same reason: a handle a caller presents at login may
     * simply not be one this server knows, and "there is no such admin" is an
     * ordinary answer to that rather than a mistake.
     */
    public Optional<AdminRecord> byHandle(String handle) {
        return jdbc.query(
                        "SELECT handle, password_hash, must_change_password, created_at"
                                + " FROM admins WHERE handle = ?",
                        (rs, rowNum) -> new AdminRecord(
                                rs.getString("handle"),
                                rs.getString("password_hash"),
                                rs.getBoolean("must_change_password"),
                                rs.getObject("created_at", OffsetDateTime.class)),
                        handle)
                .stream()
                .findFirst();
    }

    /**
     * Create an admin. {@code must_change_password} is left to the column's
     * own default — true — and {@code created_at} to the column's own {@code
     * now()}, so this method has exactly the two things a caller actually
     * decided: who, and what their password hashes to.
     *
     * <p>A handle already in the table fails this call with the ordinary
     * primary-key violation rather than a checked exception this class
     * invents: {@link AdminSeed}, this method's only caller, always checks
     * {@link #byHandle(String)} first and never reaches this on a handle it
     * already found, so a violation here is a race between two boots rather
     * than an outcome either caller is meant to handle gracefully.
     *
     * @param handle the login name; must be non-blank, or the table's own
     *     {@code admins_handle_named} check refuses the insert
     * @param passwordHash a {@link PasswordHasher#hash(char[])} output; must
     *     be non-blank, or the table's own {@code admins_password_hash_named}
     *     check refuses the insert
     */
    public void create(String handle, String passwordHash) {
        jdbc.update(
                "INSERT INTO admins (handle, password_hash) VALUES (?, ?)",
                handle, passwordHash);
    }

    /**
     * The handle of an admin already in this table, if there is one and it is
     * not {@code handle} — {@link AdminSeed}'s question on a boot where the
     * handle it was asked to seed comes back absent: is this a fresh
     * deployment, or an operator who changed {@code PLOWSHARE_ADMIN_HANDLE}
     * against a database that already has an account under a different name.
     *
     * <p><b>Where "one admin" is enforced, not where it is defined.</b> This
     * table's own primary key stops two rows sharing a handle and nothing
     * more — {@code V37__admins.sql} does not stop {@link #create(String,
     * String)} being called twice under two different handles, and this
     * class's own header notes the schema does not need to change on the day
     * that stops being true. {@code LIMIT 1} is a deliberate choice to answer
     * "is there another one", which is all {@link AdminSeed} needs for its
     * refusal message, and not "how many are there".
     *
     * @param handle the handle {@link AdminSeed} has just confirmed absent;
     *     excluded from the search so a caller cannot be handed its own
     *     answer back
     */
    public Optional<String> handleOfAnyOtherAdmin(String handle) {
        return jdbc.query(
                        "SELECT handle FROM admins WHERE handle <> ? ORDER BY created_at ASC LIMIT 1",
                        (rs, rowNum) -> rs.getString("handle"),
                        handle)
                .stream()
                .findFirst();
    }

    /**
     * Set a new hash for {@code handle} and clear {@code must_change_password}
     * in the same write — the two must happen together, or a caller could
     * observe a row whose password already changed but which still reports
     * that it must.
     *
     * <p>Unconditional on the current hash: {@code
     * AuthController#changePassword} has already verified the caller's
     * current password with {@link PasswordHasher#matches} before this is
     * ever called, so this method has nothing left to check and nothing here
     * repeats that comparison against the database.
     *
     * <p>No return value, on {@link #create(String, String)}'s own shape: this
     * class's one caller here always calls {@link #byHandle(String)} first —
     * to find the record whose current password it is about to verify — and
     * never reaches this on a handle it has not just confirmed exists. A
     * caller that raced this against a delete this class does not yet offer
     * would update zero rows silently, which is the same trade {@link
     * #create(String, String)} already makes for a race between two boots.
     *
     * @param handle the admin whose row to update; must already exist
     * @param passwordHash a fresh {@link PasswordHasher#hash(char[])} output
     */
    public void changePassword(String handle, String passwordHash) {
        jdbc.update(
                "UPDATE admins SET password_hash = ?, must_change_password = FALSE"
                        + " WHERE handle = ?",
                passwordHash, handle);
    }
}
