package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.cache.*;
import io.aeyer.plowshare.server.reads.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;

/** One bounded graph fill per account/version, with fresh fences around the consuming SQL. */
public final class JdbcInformationLogVisibility implements InformationLogVisibility {
  private static final Set<ProjectionVersions.Domain> DOMAINS =
      Set.of(ProjectionVersions.Domain.READABILITY, ProjectionVersions.Domain.AUTHORITY);
  private static final int MAX_LOGS = 32768;
  private final JdbcTemplate jdbc;
  private final ProjectionCache<Facts> cache;
  private final ProjectionVersions versions;
  private final ReadAdmission admission;

  public JdbcInformationLogVisibility(
      JdbcTemplate jdbc,
      ProjectionCache<Facts> cache,
      ProjectionVersions versions,
      ReadAdmission admission) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.cache = Objects.requireNonNull(cache);
    this.versions = Objects.requireNonNull(versions);
    this.admission = Objects.requireNonNull(admission);
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
            () -> admission.read(account, ReadAdmission.Work.AUTHORITY, () -> load(account)),
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
