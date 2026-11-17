package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
  private final Duration budget;

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
    this(jdbc, cache, versions, admission, check, Duration.ofSeconds(2));
  }

  /** Each consuming SQL unit uses {@code budget}, narrowed by an existing transaction deadline. */
  public JdbcInformationLogVisibility(
      JdbcTemplate jdbc,
      ProjectionCache<Facts> cache,
      ProjectionVersions versions,
      ReadAdmission admission,
      LogReadabilityCheck check,
      Duration budget) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.cache = Objects.requireNonNull(cache);
    this.versions = Objects.requireNonNull(versions);
    this.admission = Objects.requireNonNull(admission);
    this.check = Objects.requireNonNull(check);
    this.budget = Objects.requireNonNull(budget);
    if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException(
          "Log visibility budget must be positive and at most one minute");
  }

  @Override
  public <T> T read(String account, Function<Facts, T> reader) {
    Objects.requireNonNull(reader);
    if (account != null && account.isBlank())
      throw new IllegalArgumentException("Current account required for log visibility");
    ReadDeadline.check();
    if (account == null) return consume(reader, Facts.authoritative());
    var probe = versions.capture(DOMAINS);
    ReadDeadline.check();
    if (probe.isEmpty()) {
      // Dirty or historical caller transactions cannot share RAM. Keep the original statement's
      // authority semantics; do not launder its snapshot through a newly captured token.
      return authoritative(account, reader);
    }
    var facts =
        cache.read(
            new SnapshotCache.Key("blocked-logs", ProjectionCache.fingerprint(account)),
            true,
            () -> admission.read(FETCHER, ReadAdmission.Work.FETCH, () -> load(account)),
            value -> value.blocked().map(JdbcInformationLogVisibility::weight).orElse(1024L));
    ReadDeadline.check();
    // Oversized snapshots retain a versioned fallback marker, never a partial deny list. The
    // original statement-authoritative path retains its own SQL-unit budget.
    if (facts.blocked().isEmpty()) return authoritative(account, reader);
    var value = consume(reader, facts);
    ReadDeadline.check();
    // A new input, ancestry change, expiry or revocation between the graph hit and the page query
    // must not disclose this tentative result. A caller that became dirty/historical or crossed
    // an authority expiry still fails closed. Other domain churn may concern an unrelated tree;
    // discard the result and evaluate selection and authority in one SQL snapshot instead.
    if (!versions.canShare()) throw ReadFailedException.invalidated();
    ReadDeadline.check();
    boolean current = versions.current(probe.orElseThrow());
    ReadDeadline.check();
    if (!current) {
      var fresh = authoritative(account, reader);
      // A READ COMMITTED outer transaction retains its original now(). Do not let an expiry
      // crossed during fallback turn its historical authority into a newly disclosed result.
      if (!versions.canShare()) throw ReadFailedException.invalidated();
      ReadDeadline.check();
      return fresh;
    }
    return value;
  }

  private <T> T authoritative(String account, Function<Facts, T> reader) {
    ReadDeadline.check();
    var value =
        admission.readAuthoritative(
            account,
            () -> {
              ReadDeadline.check();
              return consume(reader, Facts.authoritative());
            });
    ReadDeadline.check();
    return value;
  }

  private <T> T consume(Function<Facts, T> reader, Facts facts) {
    // A callback is one SQL unit, not the collection workflow. In autocommit a fallback is a new
    // unit; an existing caller transaction keeps its earlier deadline across every nested unit.
    // Admission and cache waiting have their own scopes. The SQL owner still supplies cancellation.
    return ReadDeadline.within(
        budget,
        () -> {
          ReadDeadline.check();
          T value = reader.apply(facts);
          ReadDeadline.check();
          return value;
        });
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
    ReadDeadline.check();
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
    ReadDeadline.check();
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
