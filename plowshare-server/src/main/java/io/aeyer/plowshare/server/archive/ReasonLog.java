package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.VerdictKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Why each write was filed the way it was, kept where it can be asked again.
 *
 * <p>The spec asked for this and nothing delivered it: every scribe fallback "says so in the {@code
 * reason} … <b>So a year later the archive can distinguish 'no scribe judged this' from 'the scribe
 * was busy'.</b>" {@link Archive#applyVerdict} put the reason in the returned {@code WriteResult}
 * and nowhere else, so seventeen distinct sentences reached an HTTP response and stopped there.
 *
 * <p>{@code V5__memory_reasons.sql} carries the whole of why this is a table rather than a column
 * on {@code memories}, and the three questions 3a left open — whether a merge attaches to the
 * target, whether a demotion earns an entry, whether it reads per tier. They are answered there,
 * once, beside the schema that implements them.
 *
 * <p>Framework-free apart from {@link JdbcTemplate}, matching {@link ProposalStore} and {@link
 * MemoryStore}: {@code ArchiveConfig} wires it.
 */
public class ReasonLog {

  /**
   * One judgement, as it was filed.
   *
   * @param memoryId the memory this write landed in — for a merge, the target, because a merge
   *     writes no new row
   * @param filedAt the archive's clock at the moment of the write, so the entries for one memory
   *     read in order
   * @param kind what the verdict said this write was
   * @param targetId the memory the verdict named, or {@code null} for {@code NEW}, which names
   *     nothing. Equal to {@code memoryId} for a merge, by construction rather than by coincidence.
   * @param reason the verdict's own prose. Never null — {@code Verdict} refuses one — and permitted
   *     to be blank, because {@code Verdict} permits that and this log must never be the thing that
   *     fails a write.
   */
  public record Entry(
      String memoryId, Instant filedAt, VerdictKind kind, String targetId, String reason) {}

  private final JdbcTemplate jdbc;

  public ReasonLog(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * File one judgement.
   *
   * <p>Package-private: {@link Archive#applyVerdict} is the only writer, and it writes inside the
   * same unit of work as the memory. A caller outside this package could otherwise record a
   * judgement for a write that did not happen, or one that did and then rolled back — and this
   * log's only value is that it agrees with the rows.
   */
  void record(Entry entry) {
    ArchiveUnavailableException.translating(
        "record why a write was filed as it was",
        () ->
            jdbc.update(
                "INSERT INTO memory_reasons (memory_id, filed_at, kind, target_id, reason)"
                    + " VALUES (?, ?, ?, ?, ?)",
                entry.memoryId(),
                utc(entry.filedAt()),
                entry.kind().wireName(),
                entry.targetId(),
                entry.reason()));
  }

  /**
   * Everything filed against one memory, oldest first.
   *
   * <p>The question the spec asks, and the only one this table has. Ordered by the archive's clock
   * and then by the surrogate key, because the clock is injected and two writes in one test really
   * can share an instant — an ordering that depended on the planner would be an unbacked promise of
   * exactly the kind {@code ProposalStore.ruledOn} records retreating from.
   *
   * <p><b>Dropping the whole {@code ORDER BY} is caught</b> — {@code
   * entries_come_back_oldest_first_even_when_they_were_not_written_in_that_order} files a merge
   * stamped an hour before the write it merges into, so insertion order and clock order disagree.
   * <b>Dropping only the {@code , id} tiebreak is not, and cannot be.</b> Recorded rather than left
   * to look like a gap: {@code id} is an identity column on a table nothing ever updates, so id
   * order and insertion order are the same order, and the tiebreak can only ever agree with what
   * the planner already returns for equal instants. No fixture can make them disagree — that is a
   * proof of equivalence and not a missing test.
   *
   * <p>Kept anyway, and not on "defence", which this project declines elsewhere. The contract above
   * is <em>oldest first</em>, and {@code filed_at} alone leaves rows sharing an instant unordered —
   * a state an injected clock produces routinely. The line makes the promise total without resting
   * on {@code id} staying insertion-ordered, which is a property of the column type rather than of
   * this query.
   *
   * <p><b>An id nothing was written under answers with an empty list rather than raising</b>,
   * unlike {@link Archive#get} and {@link ProposalStore#get}. "What is recorded about this memory"
   * has an honest empty answer — a memory written before this table existed has no entries and is
   * not missing — so refusing would make the absence of history indistinguishable from the absence
   * of the memory, which is the collapse this whole table exists to undo one level down.
   *
   * <p><b>No production caller yet, and that is stated rather than left to be noticed.</b> The
   * reader this exists for is an operator asking a question a year later; putting it behind an HTTP
   * surface is task 10's, and inventing one here would be the endpoint-with-no-caller this slice
   * keeps deleting. What is <em>not</em> deferrable is the read itself: a store that can only be
   * written is a table nothing in the archive can answer from, and the spec's sentence is about the
   * archive being able to answer.
   */
  public List<Entry> forMemory(String memoryId) {
    return ArchiveUnavailableException.translating(
        "read why a memory's writes were filed as they were",
        () ->
            jdbc.query(
                "SELECT memory_id, filed_at, kind, target_id, reason FROM memory_reasons"
                    + " WHERE memory_id = ? ORDER BY filed_at, id",
                ROW_MAPPER,
                memoryId));
  }

  private static final RowMapper<Entry> ROW_MAPPER =
      (rs, rowNum) ->
          new Entry(
              rs.getString("memory_id"),
              instant(rs, "filed_at"),
              VerdictKind.fromWireName(rs.getString("kind")),
              rs.getString("target_id"),
              rs.getString("reason"));

  /*
   * OffsetDateTime on both sides and never java.sql.Timestamp, for the reason
   * MemoryStore and ProposalStore both give: Timestamp carries no zone and
   * comes back through the JVM's default calendar, so a server outside UTC
   * round-trips a shifted instant.
   */
  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  /**
   * No null branch, unlike the identical helper in {@code MemoryStore} and {@code ProposalStore}:
   * their instants are nullable columns and this one is {@code filed_at TIMESTAMPTZ NOT NULL}. A
   * ternary copied along with the method would be a line no mutant could kill, which this project
   * takes as a line carrying no rule.
   */
  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }
}
