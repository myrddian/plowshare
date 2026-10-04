package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.orchestrations.RecordPage;
import io.aeyer.plowshare.server.orchestrations.RecordReads;
import java.util.List;

/**
 * One page of a tree's record — {@link EntryPageView}'s shape, plus the root the id resolved to.
 * Backwards readings are newest first; a client turns them round. <b>{@code total} is a floor</b>
 * once the page is full — the rows and one past them, {@link RecordPage#total()}'s reason — so a
 * client pages by {@code more} and {@code oldest}, and never by it.
 */
public record RecordPageView(
    String root,
    List<RecordView> rows,
    int total,
    int limit,
    int through,
    Integer oldest,
    Boolean more) {

  public static RecordPageView of(RecordReads.Read read) {
    RecordPage page = read.page();
    return new RecordPageView(
        read.root(),
        page.rows().stream().map(RecordView::of).toList(),
        page.total(),
        read.limit(),
        page.through(),
        page.oldest(),
        page.more());
  }
}
