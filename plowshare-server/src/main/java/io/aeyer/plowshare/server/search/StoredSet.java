package io.aeyer.plowshare.server.search;

import io.aeyer.plowshare.protocol.search.Hit;
import java.time.Instant;
import java.util.List;

/**
 * One search's results, as {@link ResultSetStore#get} reads them back: the whole set, in the order
 * the provider returned it, so a caller can slice a page out of {@link #hits} without a second
 * read.
 *
 * @param providerKey which provider answered the search that produced this set — {@code
 *     search_result_sets}'s own comment on why this is not a foreign key to {@code
 *     search_providers}
 * @param hits every hit the provider returned, in its own order
 * @param fetchedAt when the search that produced this set ran — the clock {@link
 *     ResultSetStore#get} and {@link ResultSetStore#purgeExpired} measure a TTL against, not the
 *     moment this particular read happened
 */
public record StoredSet(String providerKey, List<Hit> hits, Instant fetchedAt) {}
