package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.archive.CompactionRecord;

/**
 * One seam in a conversation's history, as the party that spoke it reads it back.
 *
 * <p><b>There is no {@code conversationId}.</b> {@code CompactionRecord} carries one because it is
 * a row; every seam in this answer belongs to the conversation in the path, so repeating it on each
 * element would be a field that cannot vary — a column of one value, which reads to a client as
 * something worth comparing. {@code TurnView} declines its own for the same reason. (This paragraph
 * used to borrow the rule from {@code ConversationView}, which stated it about a field it did not
 * yet carry; that field exists now that a conversation can be read back, and the rule is written
 * out here rather than cited from somewhere it no longer applies.)
 *
 * @param throughOrdinal the last turn this summary stands for. Every turn from the conversation's
 *     first through this one was folded into it, and <b>all of them are still in {@code turns} at
 *     their own ordinals with their own text</b> — a compaction deletes nothing. What this number
 *     is for is telling a reader how far back the seam reaches, so that they know which part of
 *     what they are being shown is a summary of something rather than the thing
 * @param summary what the model wrote in place of those turns. Model prose, and the one field in
 *     this package that is: it is disclosed here because it is already what the <em>next</em> turn
 *     is shown in place of the history it replaces, and a person who cannot read it cannot tell a
 *     summary that kept what mattered from one that quietly dropped it. That is the design's whole
 *     mitigation for a compaction — "a bad summary is information loss wearing a receipt, which is
 *     worse than an obvious truncation because it looks like continuity" — and it is worth nothing
 *     if the only way to read the seam is a SQL client
 */
public record CompactionView(int throughOrdinal, String summary) {

  public static CompactionView of(CompactionRecord fold) {
    return new CompactionView(fold.throughOrdinal(), fold.summary());
  }
}
