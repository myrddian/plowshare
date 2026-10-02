package io.aeyer.plowshare.server.auth;

import java.time.OffsetDateTime;

/**
 * One row of {@code admins}, read back.
 *
 * <p>{@code passwordHash} is the Argon2id encoded string {@link
 * PasswordHasher#hash(char[])} produced — never a plaintext password, which
 * this server never stores at all. {@code mustChangePassword} is what a later
 * task enforces: a login by an account still carrying it succeeds but must not
 * permit anything else until the password is actually changed. This task only
 * stores the flag; see {@code V37__admins.sql} for why it defaults true.
 */
public record AdminRecord(
        String handle,
        String passwordHash,
        boolean mustChangePassword,
        OffsetDateTime createdAt) {
}
