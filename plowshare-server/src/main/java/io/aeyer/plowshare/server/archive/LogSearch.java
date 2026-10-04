package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.agents.EntryKind;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a search of the conversation log found, how many there were, and what it could not look at.
 *
 * <h2>Why the reach is part of the answer and not a second call</h2>
 *
 * <p>{@code DocumentStore.coverage} exists because a filter a reader cannot see turns an empty
 * answer into a lie: a corpus holding one document that was stored while the embedding endpoint was
 * down answers every question with nothing, with every word of that document on disk. <b>A log has
 * three such filters instead of one</b>, and all three are invisible from outside the SQL — an
 * ejected payload has no text left to match, a kind that never reached a model is deliberately not
 * searched, and both of those are ordinary states rather than faults. So {@link Reach} travels with
 * every answer, in the same statement, and an empty result set is readable as "the log was searched
 * and held nothing" only when the numbers beside it say so.
 *
 * <p><b>It is not a page of {@link EntryPage}, and the difference is the snippet.</b> {@code
 * EntryPage.Row.excerpt} is the <em>opening</em> of an entry, cut at a fixed length, and its whole
 * contract is that {@code length > excerpt.length()} means "there is more of this after what you
 * can see". A hit's text is the words <em>around the match</em>, taken from wherever in the entry
 * the match happened and with the matched words marked — so it is neither a prefix nor a substring
 * of anything, and typing it as an excerpt would import a contract it cannot keep.
 *
 * @param hits what matched, best first. Never null; empty for a question nothing matched and for a
 *     page past the end, which {@link #total} tells apart
 * @param total how many entries matched in all, which is a number about the tier and not about this
 *     page. Asked in the same statement as {@link #reach}, so the four numbers describe one instant
 * @param reach what the search could and could not look at
 */
public record LogSearch(List<Hit> hits, int total, Reach reach) {

  /** A tier with nothing in it, and the answer for a question nothing could be asked of. */
  public static final LogSearch NONE = new LogSearch(List.of(), 0, new Reach(0, 0, 0));

  public LogSearch {
    hits = hits == null ? List.of() : List.copyOf(hits);
    Objects.requireNonNull(reach, "reach");
    if (total < hits.size()) {
      throw new IllegalArgumentException(
          "a page of "
              + hits.size()
              + " hits cannot come out of a search said to have"
              + " matched "
              + total);
    }
  }

  /**
   * One entry a question's words reached.
   *
   * <p><b>The conversation is on every hit because a search crosses conversations.</b> {@code
   * EntryPage.Row} declines to carry one, and is right to: every row of a page belongs to the
   * conversation that was asked for. A tier's search has no such conversation, and the id is what
   * turns a hit into something a reader can go and read — with {@code conversation_trajectory}, at
   * the ordinal this hit names.
   *
   * @param conversationId which conversation said it
   * @param ordinal where in that conversation's log it came; 1 is the first thing recorded
   * @param turnOrdinal which turn produced it, or for a {@code SUMMARY} the last turn it stands for
   * @param kind what this is. Always one of the four that carry a role, which is what {@code
   *     EntryStore.SEARCH_SQL} searches and why {@link Reach#recordedOnly} exists to account for
   *     the rest
   * @param rank what {@code ts_rank_cd} scored this entry against the question. <b>Always above
   *     zero</b> — a rank of zero is what a row the question does not really match scores, and the
   *     read refuses those rather than ranking them. <b>Not comparable across questions</b>:
   *     Postgres keeps no corpus-wide term statistics, so this is a cover density and not a
   *     calibrated relevance, and what it is good for is the order it puts one question's hits in
   * @param snippet the words around the match, with the matched words marked, bounded at {@code
   *     EntryStore.MOST_CHARACTERS_PER_SNIPPET}. <b>Not a prefix of the entry</b>: it is taken from
   *     wherever the match is, so a match at character ninety thousand is what a reader sees. Never
   *     null
   * @param length how many characters the entry really is, counted by the database where the text
   *     already is. This is the number that says how much of the entry the snippet is not showing
   * @param supersededBy the ordinal of the summary that folded this entry away, or null for one no
   *     fold covers. <b>A hit is returned either way</b> — a fold does not unsay anything, and an
   *     entry a model can no longer see is most of what a search over a log is for
   * @param handle the address {@code result_read} redeems this result at, null for every kind but
   *     {@code TOOL_RESULT} and for a result written before {@code V13} added the column. <b>The
   *     only route from a hit back to the whole text</b>, and it exists for one kind
   * @param recordedAt when this was written, or null for an entry written before {@code V16} added
   *     the column
   */
  public record Hit(
      String conversationId,
      int ordinal,
      int turnOrdinal,
      EntryKind kind,
      double rank,
      String snippet,
      int length,
      Integer supersededBy,
      UUID handle,
      Instant recordedAt,
      String sourceRevision) {
    public Hit(
        String conversationId,
        int ordinal,
        int turnOrdinal,
        EntryKind kind,
        double rank,
        String snippet,
        int length,
        Integer supersededBy,
        UUID handle,
        Instant recordedAt) {
      this(
          conversationId,
          ordinal,
          turnOrdinal,
          kind,
          rank,
          snippet,
          length,
          supersededBy,
          handle,
          recordedAt,
          null);
    }

    public Hit {
      Objects.requireNonNull(conversationId, "conversationId");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(snippet, "snippet");
    }
  }

  /**
   * What a search looked at, and what it could not.
   *
   * <p><b>The three counts partition the tier exactly.</b> Every entry is searched, or ejected, or
   * of a kind that is recorded and never said: the schema makes the partition exact rather than
   * approximate, because {@code entries_an_ejected_payload_is_gone} makes NULL content and an
   * ejection one fact, and {@code entries_only_a_tool_result_is_ejected} keeps ejection to a kind
   * that carries a role. So a reader can add them up and check them against a conversation's
   * length, which is a thing three independently computed numbers would not permit.
   *
   * @param searched entries whose words this question was actually asked of. The log, as far as any
   *     question is concerned
   * @param ejected entries whose payload a retention sweep took, keeping the row. <b>Never a hit
   *     and never silently missing</b>: the bytes are gone, so there is nothing to match, and this
   *     is the number that says so. {@code result_read} on the handle still answers when it was
   *     ejected and into which export
   * @param recordedOnly entries of a kind that never reaches a model — {@code attempt_failed},
   *     {@code runtime_note}, {@code plan}, {@code diagnostic}. <b>Not searched by choice and not
   *     by accident</b>: they are the harness's record of a run rather than anything that was said
   *     in it, and a search that returned them would be answering a different question in the same
   *     list. {@code conversation_trajectory} is where they are read
   */
  public record Reach(int searched, int ejected, int recordedOnly) {

    public Reach {
      if (searched < 0 || ejected < 0 || recordedOnly < 0) {
        throw new IllegalArgumentException(
            "a count of entries is never negative, and this one is "
                + searched
                + ", "
                + ejected
                + " and "
                + recordedOnly);
      }
    }

    /**
     * Every entry in the tier, which is what the three counts add up to. Offered here rather than
     * left to each reader, because the fact that they add up at all is this record's to state.
     */
    public int total() {
      return searched + ejected + recordedOnly;
    }
  }
}
