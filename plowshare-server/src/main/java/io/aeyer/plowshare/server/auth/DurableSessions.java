package io.aeyer.plowshare.server.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Account sessions backed by Postgres. Tokens are stored only as digests.
 * Chain row locks serialize refresh and revocation across server instances.
 * The operator/bootstrap paths continue to use TokenStore's ephemeral store. */
public final class DurableSessions {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration accessLifetime;
    private final Duration refreshLifetime;
    private final Duration chainLifetime;

    public DurableSessions(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
            Duration accessLifetime, Duration refreshLifetime) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
        this.accessLifetime = accessLifetime;
        this.refreshLifetime = refreshLifetime;
        this.chainLifetime = accessLifetime.compareTo(refreshLifetime) >= 0 ? accessLifetime : refreshLifetime;
    }

    public TokenStore.Pair issue(String handle, boolean restricted) {
        return transaction.execute(status -> {
            Instant now = clock.instant();
            jdbc.update("DELETE FROM auth_session_chains WHERE expires_at <= ?", Timestamp.from(now));
            UUID chain = UUID.randomUUID();
            jdbc.update("INSERT INTO auth_session_chains (id, handle, restricted, expires_at) VALUES (?, ?, ?, ?)",
                    chain, handle, restricted, Timestamp.from(now.plus(chainLifetime)));
            return issueOn(chain, now);
        });
    }

    private TokenStore.Pair issueOn(UUID chain, Instant now) {
        String access = Tokens.mint(), refresh = Tokens.mint();
        jdbc.update("INSERT INTO auth_session_grants (digest, chain_id, kind, expires_at) VALUES (?, ?, 'access', ?)",
                Tokens.hash(access), chain, Timestamp.from(now.plus(accessLifetime)));
        jdbc.update("INSERT INTO auth_session_grants (digest, chain_id, kind, expires_at) VALUES (?, ?, 'refresh', ?)",
                Tokens.hash(refresh), chain, Timestamp.from(now.plus(refreshLifetime)));
        return new TokenStore.Pair(access, refresh);
    }

    private record Access(String handle, boolean restricted) {}
    private Optional<Access> access(String presented) {
        if (presented == null || presented.isBlank()) return Optional.empty();
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.query("SELECT c.handle, c.restricted FROM auth_session_grants g"
                + " JOIN auth_session_chains c ON c.id = g.chain_id"
                + " WHERE g.digest = ? AND g.kind = 'access' AND g.expires_at > ?"
                + " AND c.expires_at > ? AND NOT c.revoked",
                (rs, n) -> new Access(rs.getString(1), rs.getBoolean(2)), Tokens.hash(presented), now, now)
                .stream().findFirst();
    }
    public boolean valid(String presented) { return access(presented).isPresent(); }
    public Optional<String> handle(String presented) { return access(presented).map(Access::handle); }
    public boolean restricted(String presented) { return access(presented).map(Access::restricted).orElse(false); }

    private record Grant(UUID chain, Instant expiresAt, Instant chainExpiresAt, boolean revoked) {}
    public Optional<TokenStore.Pair> refresh(String presented) {
        if (presented == null || presented.isBlank()) return Optional.empty();
        String digest = Tokens.hash(presented);
        return transaction.execute(status -> {
            // Lock the chain first for both rotation and revocation. Locking only
            // the grant would let different refresh generations resurrect a logout.
            Optional<Grant> found = jdbc.query("SELECT c.id, g.expires_at, c.expires_at, c.revoked"
                    + " FROM auth_session_grants g JOIN auth_session_chains c ON c.id = g.chain_id"
                    + " WHERE g.digest = ? AND g.kind = 'refresh' FOR UPDATE OF c",
                    (rs, n) -> new Grant(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(),
                            rs.getTimestamp(3).toInstant(), rs.getBoolean(4)), digest)
                    .stream().findFirst();
            if (found.isEmpty()) return Optional.empty();
            Grant grant = found.get();
            Instant now = clock.instant();
            if (grant.revoked() || !now.isBefore(grant.expiresAt()) || !now.isBefore(grant.chainExpiresAt())) return Optional.empty();
            // Re-read spent after the chain lock: a waiter may have observed the
            // old grant version before another transaction committed its rotation.
            boolean spent = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT spent FROM auth_session_grants WHERE digest = ?", Boolean.class, digest));
            if (spent) {
                jdbc.update("UPDATE auth_session_chains SET revoked = TRUE WHERE id = ?", grant.chain());
                return Optional.empty();
            }
            jdbc.update("UPDATE auth_session_grants SET spent = TRUE WHERE digest = ?", digest);
            jdbc.update("UPDATE auth_session_chains SET expires_at = ? WHERE id = ?", Timestamp.from(now.plus(chainLifetime)), grant.chain());
            // Retain unexpired spent grants for reuse detection, prune old generations.
            jdbc.update("DELETE FROM auth_session_grants WHERE chain_id = ? AND expires_at <= ?", grant.chain(), Timestamp.from(now));
            return Optional.of(issueOn(grant.chain(), now));
        });
    }
    public void revoke(String presented) {
        if (presented == null || presented.isBlank()) return;
        // UPDATE takes the same chain row lock as refresh; its effect survives restart.
        jdbc.update("UPDATE auth_session_chains SET revoked = TRUE WHERE id IN"
                + " (SELECT chain_id FROM auth_session_grants WHERE digest = ? AND kind = 'access')", Tokens.hash(presented));
    }
}
