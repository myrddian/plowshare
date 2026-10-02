package io.aeyer.plowshare.server.orchestrations;

import java.util.List;

/**
 * One page of a tree's record — {@code archive.EntryPage}'s shape, for the same two readings.
 *
 * @param rows forward readings in ordinal order; backwards readings newest first
 * @param total how many rows of the kinds read lie on this side of the ordinal read from — <b>at
 *     most one more than the page holds</b>: the rows, and one past them when there are more.
 *     Exact while the page is not full; "at least" once it is. Counting the rest would walk the
 *     whole record on every read, and nothing reads further than whether there is more
 * @param through the whole record's highest ordinal, whatever this page holds
 * @param more on a backwards reading, whether a row of the kinds read lies before {@link
 *     #oldest()}; {@code null} on a forward reading
 */
public record RecordPage(List<RecordRow> rows, int total, int through, Boolean more) {

    public RecordPage {
        rows = List.copyOf(rows);
        if (total < rows.size()) {
            throw new IllegalArgumentException("a page of " + rows.size()
                    + " rows cannot come out of a record said to hold " + total);
        }
    }

    /** The smallest ordinal on this page — the next backwards reading's {@code before}. */
    public Integer oldest() {
        return rows.stream().map(RecordRow::ordinal).min(Integer::compare).orElse(null);
    }
}
