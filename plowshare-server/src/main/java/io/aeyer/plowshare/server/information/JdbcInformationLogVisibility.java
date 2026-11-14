package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.cache.*;
import io.aeyer.plowshare.server.reads.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Log visibility for two kinds of caller.
 *
 * <p><b>Targeted checks</b> ({@link #readable}) are answered per log tree by {@code check} (design
 * slice 2, {@link CachedLogReadability}): shared, item-invalidated tree facts evaluated in Java, so
 * an agent step in one tree reloads nothing for any other tree or account. When it declines, the
 * caller's {@code information_log_readable} predicate decides, exactly as before the cache.
 *
 * <p><b>Collection reads</b> ({@link #read}) still consume one bounded deny set per account,
 * versioned on READABILITY and AUTHORITY, with fresh fences around the consuming SQL. Their
 * consumers filter arbitrary pages of conversations, orchestrations and inbox items with {@code NOT
 * IN (deny set)} before ordering and LIMIT, so the logs they will touch are not known in advance;
 * deriving the set from trees would mean loading every tree, which is the whole-table scan the deny
 * set already performs once per account and version.
 */
public final class JdbcInformationLogVisibility implements InformationLogVisibility {
  /** The cache's FETCH principal: deny-set loads use this cache's slots, not the account's. */
  static final String FETCHER = "information-log-visibility";

  private static final Set<ProjectionVersions.Domain> DOMAINS =
      Set.of(ProjectionVersions.Domain.READABILITY, ProjectionVersions.Domain.AUTHORITY);
  private static final int MAX_LOGS = 32768;
  private final JdbcTemplate jdbc;
  private final ProjectionCache<Facts> cache;
  private final ProjectionVersions versions;
  private final ReadAdmission admission;
  private final LogReadabilityCheck check;

  /** Targeted checks always use the caller's predicate. */
  public JdbcInformationLogVisibility(
      JdbcTemplate jdbc,
      ProjectionCache<Facts> cache,
      ProjectionVersions versions,
      ReadAdmission admission) {
    this(jdbc, cache, versions, admission, LogReadabilityCheck.none());
  }

  /** Targeted checks ask {@code check} first and the caller's predicate when it declines. */
  public JdbcInformationLogVisibility(
      JdbcTemplate jdbc,
      ProjectionCache<Facts> cache,
      ProjectionVersions versions,
      ReadAdmission admission,
      LogReadabilityCheck check) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.cache = Objects.requireNonNull(cache);
    this.versions = Objects.requireNonNull(versions);
    this.admission = Objects.requireNonNull(admission);
    this.check = Objects.requireNonNull(check);
  }

  @Override
  public <T> T read(String account, Function<Facts, T> reader) {
    if (account == null) return reader.apply(Facts.authoritative());
    if (account.isBlank())
      throw new IllegalArgumentException("Current account required for log visibility");
    Objects.requireNonNull(reader);
    var probe = versions.capture(DOMAINS);
    if (probe.isEmpty()) {
      // Dirty or historical caller transactions cannot share RAM. Keep the original statement's
      // authority semantics; do not launder its snapshot through a newly captured token.
      return admission.read(
          account, ReadAdmission.Work.AUTHORITY, () -> reader.apply(Facts.authoritative()));
    }
    var facts =
        cache.read(
            new SnapshotCache.Key("blocked-logs", ProjectionCache.fingerprint(account)),
            true,
            () -> admission.read(FETCHER, ReadAdmission.Work.FETCH, () -> load(account)),
            value -> value.blocked().map(JdbcInformationLogVisibility::weight).orElse(1024L));
    // Oversized snapshots retain a versioned fallback marker, never a partial deny list. The
    // original statement-authoritative path remains available under the same physical budget.
    var value =
        facts.blocked().isEmpty()
            ? admission.read(account, ReadAdmission.Work.AUTHORITY, () -> reader.apply(facts))
            : reader.apply(facts);
    // A new input, ancestry change, expiry or revocation between the graph hit and the page query
    // must not disclose the result. No retries: the callback might hold caller-specific locks.
    if (!versions.canShare() || !versions.current(probe.orElseThrow()))
      throw new ReadUnavailableException();
    return value;
  }

  /**
   * Answers a targeted check from the log's trees ({@link LogReadabilityCheck}); whenever that
   * declines — no account or log, a writing or historical caller transaction, an unaddressable or
   * oversized tree, a cache that cannot serve, or a stale confirmation — the caller's {@code
   * authoritative} predicate decides. The per-account deny set is not consulted, so a targeted
   * check never loads it.
   */
  @Override
  public boolean readable(String account, String log, BooleanSupplier authoritative) {
    Objects.requireNonNull(authoritative);
    if (account == null || log == null) return authoritative.getAsBoolean();
    if (account.isBlank())
      throw new IllegalArgumentException("Current account required for log visibility");
    var verdict = check.readable(account, log);
    return verdict.isPresent() ? verdict.get() : authoritative.getAsBoolean();
  }

  private Facts load(String account) {
    var logs =
        jdbc.queryForList(
            "WITH blocked AS MATERIALIZED (SELECT log FROM information_unreadable_logs(?) LIMIT ?),"
                + " sized AS MATERIALIZED (SELECT count(*) AS n,1024+coalesce(sum(96+4*length(log)),0) AS weight,"
                + " coalesce(bool_and(log IS NOT NULL AND length(log)<=4096),true) AS valid FROM blocked)"
                + " SELECT log FROM blocked,sized WHERE n<=? AND weight<=? AND valid"
                + " UNION ALL SELECT NULL::text FROM sized WHERE n>? OR weight>? OR NOT valid",
            String.class,
            account,
            MAX_LOGS + 1,
            MAX_LOGS,
            cache.maxWeight(),
            MAX_LOGS,
            cache.maxWeight());
    // Never truncate a deny set into an allow result or hydrate a value beyond its byte budget.
    if (logs.size() > MAX_LOGS
        || logs.stream().anyMatch(id -> id == null || id.length() > 4096)
        || weight(logs) > cache.maxWeight()) return Facts.authoritative();
    return new Facts(Optional.of(logs));
  }

  private static long weight(List<String> logs) {
    return 1024L + logs.stream().mapToLong(id -> 96L + 4L * id.length()).sum();
  }
}
