package io.aeyer.plowshare.server.fetch;

import java.time.Instant;

/**
 * One fetched page, as {@link FetchedPageStore} holds it: the whole of what {@link
 * PageExtractor#extract} produced, plus the two clocks {@code fetched_pages} keeps beside it.
 *
 * <p>{@code text} is the page's entire extracted prose, not a window of it — windowing is a later
 * reader's concern, over this record's own {@code text}, and no part of this slice does it. See
 * {@code V35__fetched_pages.sql} for why the row holds a whole page rather than pre-cut chunks.
 *
 * @param urlKey the key this row is filed under, a {@link UrlKey#of} value
 * @param url the URL this page was fetched from, kept beside the key — {@code
 *     V35__fetched_pages.sql}'s own comment argues why, unlike a stored search's query text, this
 *     is not a fact worth hiding
 * @param title the page's title, exactly as {@link ExtractedPage#title} produced it
 * @param text the page's whole extracted prose
 * @param fetchedAt when this page was fetched — the clock {@code purgeExpired}'s TTL half measures
 *     against
 * @param lastRead the last time an agent read this page back, starting equal to {@code fetchedAt} —
 *     the clock {@code purgeExpired}'s liveness half measures against; see that method's own
 *     comment for why the two clocks are separate fields rather than one
 * @param byteSize the size, in bytes, of {@code text}'s UTF-8 encoding
 */
public record FetchedPage(
    String urlKey,
    String url,
    String title,
    String text,
    Instant fetchedAt,
    Instant lastRead,
    long byteSize) {}
