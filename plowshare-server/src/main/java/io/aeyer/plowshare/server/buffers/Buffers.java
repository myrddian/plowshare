package io.aeyer.plowshare.server.buffers;

import io.aeyer.plowshare.server.fetch.FetchProperties;
import io.aeyer.plowshare.server.fetch.FetchedPageStore;
import io.aeyer.plowshare.server.search.ResultSetStore;
import io.aeyer.plowshare.server.search.SearchProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * One reclamation pass over both database-backed buffers a fetched-and-cached answer can sit in:
 * {@link FetchedPageStore} and {@link ResultSetStore}. {@code POST /v1/buffers/purge} is the door;
 * this is the room behind it.
 *
 * <h2>Its own package, not {@code fetch} or {@code search}</h2>
 *
 * <p>This class reads from both packages and belongs to neither. Filing it under {@code fetch}
 * would put a result set's retention under a fetched page's name, and the reverse is exactly as
 * wrong the other way; {@code buffers} names the thing both tables actually are — a page or a
 * result set held so an agent does not pay for the same fetch twice — without claiming either one
 * is the primary case.
 *
 * <h2>A collaborator behind a controller, on {@link io.aeyer.plowshare.server.archive.Retention}'s
 * own shape</h2>
 *
 * <p>{@code RetentionController} holds only {@code Retention} and delegates; {@code
 * BufferPurgeController} holds only this class and does the same. Two reasons beyond mirroring a
 * sibling: the alternative is a web-layer method computing {@code now - ttl} against two properties
 * classes and calling two stores directly, which is clock arithmetic and live-config reads leaking
 * into the layer whose only job is HTTP; and without this seam pulled out, a controller test cannot
 * be written the way {@code RetentionControllerTest} is — against a mocked collaborator, answering
 * "is there a door" without standing up a database to answer it.
 *
 * <h2>It is an operation an operator calls, not a timer this server starts</h2>
 *
 * <p>{@code RetentionController}'s javadoc and {@link io.aeyer.plowshare.server.archive.Retention}
 * carry this argument at length for the sibling verb, and it applies here unchanged. This server
 * has no scheduler — {@code V17__conversation_origin.sql} refuses a {@code schedule} origin on the
 * ground that a value nothing can write does not belong in a constraint, and the same is true of a
 * timer this class would have to invent for itself. A {@code @Scheduled} method or a timer started
 * from a bean fires in every Testcontainers test that stands up a {@link FetchedPageStore} or a
 * {@link ResultSetStore} against a real database — on a clock no test controls — which is exactly
 * the async-test-with-a-sleep failure this project forbids, arriving through the container instead
 * of through a test. The operator already has cron, a systemd timer, a Kubernetes CronJob, or a
 * person who runs a script; what this server owes them is an operation safe to call twice, which
 * {@link #purge()} is, on the same grounds {@code Retention.sweep()} is: every row it deletes is
 * guarded by the same predicate it was written to be purged by, so a second call against
 * already-purged rows finds nothing to do and reports nothing done.
 *
 * <h2>A sibling of {@code POST /v1/retention/sweep}, not part of it</h2>
 *
 * <p>That sweep stages mark-then-export-then-null because its bytes leave the database — a payload
 * is exported to a file before its row is nulled, so a person has a window to cancel the move. A
 * fetched page or a stored result set is derived data: it can be fetched, or searched, again, at
 * the cost of a round trip rather than the cost of a conversation's history. So neither table needs
 * staging, and folding this into {@code Retention.sweep()} would put two different retention
 * policies — one that protects irrecoverable bytes, one that discards a cache — behind a single
 * verb, which would make neither policy legible from its own report.
 *
 * <h2>The two purges keep different arities, and that is deliberate</h2>
 *
 * <p>{@link FetchedPageStore#purgeExpired} takes two thresholds — {@code ttl} and {@code
 * liveWindow} — and its own javadoc argues at length why: they answer two different questions, "is
 * this fetch old enough to distrust" and "is anybody still reading it," and folding them into one
 * bound would only be correct if those two questions always had the same answer, which is exactly
 * the case {@code fetched_pages} exists to tell apart. {@link ResultSetStore#purgeExpired} takes
 * one threshold because a result set has no second question to ask — nothing in this server marks
 * one as presently being read. This class calls each store with its own arity rather than
 * normalising both to one signature; a normalised {@code purge(now, ttl)} offered to both stores
 * would either drop the liveness guard {@code fetched_pages} was built to keep, or invent a
 * liveness column {@code search_result_sets} has never needed. Keeping them apart here is what
 * keeps that guard real one layer up, at the only caller that could have erased it.
 */
public class Buffers {

  private final FetchedPageStore pages;
  private final ResultSetStore resultSets;
  private final FetchProperties fetchProperties;
  private final SearchProperties searchProperties;
  private final Clock clock;

  public Buffers(
      FetchedPageStore pages,
      ResultSetStore resultSets,
      FetchProperties fetchProperties,
      SearchProperties searchProperties,
      Clock clock) {
    this.pages = Objects.requireNonNull(pages, "pages");
    this.resultSets = Objects.requireNonNull(resultSets, "resultSets");
    this.fetchProperties = Objects.requireNonNull(fetchProperties, "fetchProperties");
    this.searchProperties = Objects.requireNonNull(searchProperties, "searchProperties");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Purge both buffers of what is expired as of now, and say how many rows each lost.
   *
   * <p>Reads {@link FetchProperties#ttlNow()} and {@link FetchProperties#liveWindowNow()} — the
   * live-config accessors, not the bound getters — because a purge run at 3am should see whatever
   * an operator retuned a running server to since boot, on the same terms {@code
   * SearchProperties.resultSetTtlNow()} already does for the result set half of this call. Both
   * reads and both deletes happen against one {@code now}, taken once from {@link #clock}, so a
   * purge that straddles a wall-clock instant does not judge one buffer's rows a moment later than
   * the other's.
   *
   * <p><b>Safe to call twice.</b> {@link FetchedPageStore#purgeExpired} and {@link
   * ResultSetStore#purgeExpired} both delete only rows that match their own expiry predicate as of
   * the {@code now} given them, so a second call in the same second, or the same hour, finds
   * whatever the first one already removed already gone and reports zero for it.
   *
   * @return how many fetched pages and how many result sets this call removed, so an operator has
   *     an account of a run that deleted data rather than a silent success
   */
  public BufferPurgeReport purge() {
    Instant now = clock.instant();
    int fetchedPages =
        pages.purgeExpired(now, fetchProperties.ttlNow(), fetchProperties.liveWindowNow());
    int resultSetsPurged = resultSets.purgeExpired(now, searchProperties.resultSetTtlNow());
    return new BufferPurgeReport(fetchedPages, resultSetsPurged);
  }

  /**
   * What one purge did.
   *
   * @param fetchedPages how many rows {@link FetchedPageStore#purgeExpired} removed
   * @param resultSets how many rows {@link ResultSetStore#purgeExpired} removed
   */
  public record BufferPurgeReport(int fetchedPages, int resultSets) {}
}
