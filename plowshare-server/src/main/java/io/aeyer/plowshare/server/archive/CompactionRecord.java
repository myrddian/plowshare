package io.aeyer.plowshare.server.archive;

import java.util.Objects;

/**
 * One row of {@code compactions}: how far back a summary reaches, and what it says.
 *
 * <h2>The reach is the whole of the identity</h2>
 *
 * <p>A compaction always starts at the conversation's first turn — the second one summarises the
 * first one's summary together with everything said since — so there is no start to carry and
 * {@link #throughOrdinal} alone says which turns this stands for. {@code V8__compactions.sql}
 * declines the column for the same reason it declines an instant: it would hold 1 in every row this
 * server can write.
 *
 * <p><b>The turns it stands for are not in this record and are not gone.</b> They are {@code turns}
 * rows 1 through {@link #throughOrdinal}, unchanged, and reading them is how a person sees behind
 * the seam. This record is what the model is shown <em>instead</em>; it is not what happened.
 *
 * @param conversationId the conversation whose history was compacted
 * @param throughOrdinal the last turn this summary stands for. Turns 1 through this one are shown
 *     to the model as {@link #summary} rather than verbatim. Never below 1 — {@code
 *     compactions_reach_a_turn_that_was_spoken} points the pair at a real {@code turns} row, whose
 *     own {@code turns_are_numbered_from_one} carries it
 * @param summary what the model is shown in their place. Never null and never blank: a blank one is
 *     not a short history but a compaction that lost everything and said nothing about it, while
 *     its seam sentence went on claiming those turns had been summarised
 */
public record CompactionRecord(String conversationId, int throughOrdinal, String summary) {

  public CompactionRecord {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(summary, "summary");
  }
}
