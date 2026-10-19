package io.aeyer.plowshare.server.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Authoritative version evidence for explicitly adopted database projections. A notification is
 * never evidence that a token is current. Owners retain their live authorization, transaction and
 * disclosure fences, and load on the caller thread. No transaction-local DTO may enter shared RAM.
 */
public interface ProjectionVersions {
  /** Fixed source vocabularies, not dynamic SQL identifiers or authorization capabilities. */
  enum Domain {
    DOCUMENTS,
    SEARCH,
    SCHEDULES,
    RELAY,
    AUTHORITY
  }

  /**
   * Counter values are allocation hints, not commit order. Pending top-level transaction IDs close
   * the late-commit hole: any captured pending transaction subsequently committing invalidates the
   * token, even if the counters do not move. Unknown transaction status also fails closed. Owners
   * must validate the token stored alongside the loaded DTO, not substitute a newly captured token
   * for an old DTO's evidence. Authority windows prevent a clock-only expiry from reusing or
   * joining an earlier visibility projection.
   *
   * <p>This value is process-local evidence; database restore/reconnect additionally requires the
   * cache's local recovery fence. Callers must not persist tokens as durable event cursors.
   */
  record Token(
      UUID database,
      Map<Domain, Long> counters,
      List<String> pendingTransactions,
      Instant windowStart,
      Instant validUntil) {
    public Token {
      Objects.requireNonNull(database);
      if (counters == null || counters.isEmpty() || counters.size() > Domain.values().length)
        throw new IllegalArgumentException("Invalid projection version domains");
      counters = Map.copyOf(counters);
      if (counters.values().stream().anyMatch(value -> value < 0))
        throw new IllegalArgumentException("Invalid projection version counter");
      if (pendingTransactions == null || pendingTransactions.size() > 4096)
        throw new IllegalArgumentException("Invalid pending transaction bound");
      pendingTransactions = List.copyOf(pendingTransactions);
      if (pendingTransactions.stream().anyMatch(value -> !transactionId(value))
          || Set.copyOf(pendingTransactions).size() != pendingTransactions.size())
        throw new IllegalArgumentException("Invalid pending transaction identity");
      if (windowStart != null && validUntil != null && !windowStart.isBefore(validUntil))
        throw new IllegalArgumentException("Invalid authority version window");
    }

    /**
     * Bounded equality-only identity for namespace, counters and time window. Pending XIDs are
     * validation evidence attached to a loaded DTO, not identity: active readers must still
     * coalesce. A cache hit must validate its stored loader token before use.
     */
    public SnapshotCache.SourceVersion sourceVersion() {
      var canonical = new StringBuilder(database.toString());
      for (Domain domain : Domain.values()) {
        if (counters.containsKey(domain))
          canonical.append('|').append(domain).append(':').append(counters.get(domain));
      }
      canonical.append('|').append(windowStart).append('|').append(validUntil);
      try {
        return new SnapshotCache.SourceVersion(
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(canonical.toString().getBytes(StandardCharsets.UTF_8))));
      } catch (NoSuchAlgorithmException unavailable) {
        throw new IllegalStateException("Required SHA-256 implementation unavailable", unavailable);
      }
    }

    public Set<SnapshotCache.Dependency> dependencies() {
      return counters.keySet().stream()
          .map(domain -> new SnapshotCache.Dependency("projection:" + domain.name()))
          .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean transactionId(String value) {
      if (value == null || value.isEmpty() || value.length() > 20) return false;
      for (int index = 0; index < value.length(); index++)
        if (value.charAt(index) < '0' || value.charAt(index) > '9') return false;
      try {
        return Long.compareUnsigned(Long.parseUnsignedLong(value), 0) > 0;
      } catch (NumberFormatException invalid) {
        return false;
      }
    }
  }

  /** Stable database namespace, independently available even when capture must bypass capacity. */
  UUID databaseIdentity();

  /**
   * Captures counters, allocates and commits a newer probe XID, then takes a fresh
   * pending-transaction snapshot. The completed-XID barrier makes active tail writers visible in
   * snapshot xip; it costs one short-lived XID per capture, not a global row lock or a commit-order
   * cursor. The caller's SQL transaction timestamp must belong to the complete captured authority
   * window, including when an expiry crosses during capture. Unsafe callers or pending-bound
   * overflow return empty; database failures propagate, without implicit source retry.
   */
  Optional<Token> capture(Set<Domain> domains);

  /** Uses fresh independent READ COMMITTED statements, never the caller's historical snapshot. */
  boolean current(Token token);

  /**
   * Caller-bound guard: only READ COMMITTED, no watched writes, and no transaction crossing an
   * authority-expiry boundary. Check before and after an inline loader, including outer
   * transactions.
   */
  boolean canShare();
}
