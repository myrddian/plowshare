package io.aeyer.plowshare.server.archive;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Where a conversation's history was folded up, and how far back each fold reaches.
 *
 * <h2>Two reads, and they are two different questions</h2>
 *
 * <p>{@link #latest} answers <em>what the model is shown</em>: the newest fold is the only one that
 * still stands, because each one summarises the previous one along with everything said since.
 * {@link #forConversation} answers <em>what happened</em>: every fold this conversation has ever
 * had, in the order they fell, which is what lets a person see each seam rather than only the last.
 *
 * <p>The second is what makes a compaction an event rather than a silence, and it is the reason
 * there are two methods instead of one. A store that offered only {@code latest} would keep the
 * rows and hide all but one of them, which is the same loss with a longer audit trail.
 *
 * <h2>What this store does not do</h2>
 *
 * <p><b>It never deletes a turn</b>, and there is no method here that could. The whole of a
 * compaction is a row saying "turns 1 to N are shown as this summary"; the turns stay in {@code
 * turns} at their own ordinals and are read back through {@link TurnStore#forConversation}. If a
 * delete path is ever wanted, it is a decision about a person's history and belongs in the commit
 * that makes it, not in a convenience method waiting for one.
 *
 * <p><b>It does not translate its two constraints into sentences</b>, on {@link TurnStore}'s
 * reasoning exactly. {@code compactions_reach_a_turn_that_was_spoken} fires for a reach nothing was
 * said at, and {@code compactions_one_per_reach_in_a_conversation} fires for the same fold written
 * twice; both arrive as {@code DataIntegrityViolationException}, both are opposite faults, and only
 * one of them can be reproduced from a fixture this store can drive. The constraints are named, so
 * Postgres says which rule broke.
 *
 * <p>Framework-free apart from {@link JdbcTemplate}, and wired by {@link ArchiveConfig}, matching
 * {@link TurnStore}, {@link ConversationStore} and {@link ProposalStore}.
 */
public final class CompactionStore {

  private static final String COLUMNS = "conversation_id, through_ordinal, summary";

  private final JdbcTemplate jdbc;

  public CompactionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Write down that turns 1 through {@code throughOrdinal} are now shown as {@code summary}.
   *
   * @param conversationId the conversation whose history was folded up
   * @param throughOrdinal the last turn the summary stands for. Must name a turn that was really
   *     spoken in this conversation
   * @param summary what the model is shown in their place. Never blank — {@code
   *     compactions_summary_says_something} refuses one, and {@code agents.Compaction} refuses to
   *     reach this method with one
   * @return the row as it was written
   * @throws ValidationException if the conversation id cannot be named
   * @throws org.springframework.dao.DataIntegrityViolationException if no such turn was spoken, or
   *     this reach has already been folded. Untranslated, and the class javadoc says why
   */
  public CompactionRecord record(String conversationId, int throughOrdinal, String summary) {
    String conversation = ConversationStore.named(conversationId);
    return ArchiveUnavailableException.translating(
        "write down a compaction",
        () ->
            jdbc.queryForObject(
                "INSERT INTO compactions (conversation_id, through_ordinal, summary)"
                    + " VALUES (?, ?, ?) RETURNING "
                    + COLUMNS,
                ROW_MAPPER,
                conversation,
                throughOrdinal,
                summary));
  }

  /**
   * The fold that still stands, or empty for a conversation nothing has compacted.
   *
   * <p>The newest reach wins, and "newest" is the largest {@code through_ordinal} rather than the
   * last row inserted: there is no instant column to order by — {@code V8__compactions.sql} says
   * why — and a fold always reaches further than the one before it, since it is taken over a
   * conversation that has since grown.
   *
   * <p><b>Empty rather than a refusal for a conversation with no folds</b>, which is every
   * conversation for most of its life. {@link TurnStore#forConversation} makes the same choice
   * about an empty history and for the same reason; refusing would make "nothing has been
   * compacted" indistinguishable from "no such conversation".
   *
   * @throws ValidationException if the conversation id cannot be named — an id nothing dropped, for
   *     the reason {@code TurnStore.forConversation} gives: a null one would otherwise come back as
   *     "nothing compacted"
   */
  public Optional<CompactionRecord> latest(String conversationId) {
    String conversation = ConversationStore.named(conversationId);
    return ArchiveUnavailableException.translating(
        "read a conversation's compactions",
        () ->
            jdbc
                .query(
                    "SELECT "
                        + COLUMNS
                        + " FROM compactions WHERE conversation_id = ?"
                        + " ORDER BY through_ordinal DESC LIMIT 1",
                    ROW_MAPPER,
                    conversation)
                .stream()
                .findFirst());
  }

  /**
   * Every fold this conversation has had, in the order they fell.
   *
   * <p>The transcript's side of the seam. A person reading a conversation back sees each compaction
   * where it happened, and reads what was behind it out of {@link TurnStore#forConversation} — the
   * turns are still there.
   *
   * @throws ValidationException if the conversation id cannot be named
   */
  public List<CompactionRecord> forConversation(String conversationId) {
    String conversation = ConversationStore.named(conversationId);
    return ArchiveUnavailableException.translating(
        "read a conversation's compactions",
        () ->
            jdbc.query(
                "SELECT "
                    + COLUMNS
                    + " FROM compactions WHERE conversation_id = ?"
                    + " ORDER BY through_ordinal",
                ROW_MAPPER,
                conversation));
  }

  private static final RowMapper<CompactionRecord> ROW_MAPPER =
      (rs, rowNum) ->
          new CompactionRecord(
              rs.getString("conversation_id"),
              rs.getInt("through_ordinal"),
              rs.getString("summary"));
}
