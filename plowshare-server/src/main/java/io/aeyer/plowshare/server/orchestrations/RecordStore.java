package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The orchestration record — {@code orchestration_record}, V59. Framework-free apart from {@link
 * JdbcTemplate} and {@link UnitOfWork}, like {@link OrchestrationStore}, so {@code TodosConfig} and
 * the engine's config can both ask for it with nothing wider behind it.
 *
 * <h2>The ordinal is this store's to assign, one writer per tree at a time</h2>
 *
 * <p>{@code MAX(ordinal) + 1} inside the INSERT, one number per root — but not {@code
 * EntryStore.append}'s optimistic scheme alone. A tree has many writers at once: its conductor's
 * turn, every sub-agent's tool calls, the todo board, the approval store and the stall sweep. Left
 * to race, two of them compute the same number, the primary key refuses one, and under enough
 * contention a bounded retry runs out and the row is lost — measured, as {@code RecordStoreTest}'s
 * race test failing one run in a few with rows missing. So each append is its own short transaction
 * that first takes a transaction-scoped advisory lock on its root, and only then reads the maximum:
 * in READ COMMITTED the INSERT's snapshot is taken after the lock is granted, so it sees every row
 * the previous holder committed. Writers in one tree queue for microseconds; different trees never
 * wait on each other, but for the rare two roots whose hashes collide. The retry stays as a belt,
 * for a writer this lock does not know about.
 *
 * <p><b>Call it outside any transaction of your own.</b> {@link UnitOfWork#inTransaction} joins one
 * already open, and then the lock is held until that one commits, and a failure here would roll
 * that work back — the thing the record must never do. Every source today tells the record after
 * its own commit.
 *
 * <h2>Two walks up</h2>
 *
 * <p>{@link #treeOfRun} climbs {@code orchestrations.parent} to the root. {@link #placeOf} first
 * climbs {@code conversations.parent_id} — {@link OrchestrationStore}'s {@code QUIET_SINCE} walks
 * the same edge down — to the conversation at the top, which is a run's conductor conversation when
 * the conversation is inside a tree, and then climbs to that run's root.
 */
public class RecordStore {

  /** The {@code before} that reads from the record's end. */
  public static final int FROM_THE_END = Integer.MAX_VALUE;

  /** How long any one text or detail may be, in characters. The table checks it too. */
  static final int MOST_CHARACTERS = 400;

  /** How long a body may be, in characters. The table checks it too (V64). */
  static final int MOST_BODY = 16_000;

  /** How many times a write that raced another for its ordinal tries again. */
  static final int MOST_ATTEMPTS = 8;

  /** How deep either walk goes before it stops: {@code ConversationStore.DEEPEST_DELEGATION}. */
  static final int DEEPEST = 64;

  /** A run's tree: its root, and the account that root answers to ({@code caller_handle}). */
  public record Tree(String root, String handle) {}

  /**
   * Where a conversation works: the tree, the run whose conductor conversation it is or is under,
   * and whether it <em>is</em> that conductor conversation.
   */
  public record Place(String root, String handle, String run, boolean conductor) {}

  private static final String COLUMNS = "ordinal, at, run, actor, kind, text, detail, body";

  /**
   * The record's advisory-lock space: the two-key form, so no other advisory lock this database
   * ever takes can collide with a record's. 59 is the migration that made the table.
   */
  static final int LOCK_SPACE = 59;

  private static final String LOCK =
      "SELECT pg_advisory_xact_lock(" + LOCK_SPACE + ", hashtext(?))";

  private static final String INSERT =
      """
            INSERT INTO orchestration_record
                        (root, ordinal, at, run, actor, kind, text, detail, conversation, body)
            SELECT ?, COALESCE(MAX(ordinal), 0) + 1, ?, ?, ?, ?, ?, ?, ?, ?
              FROM orchestration_record
             WHERE root = ?
            RETURNING ordinal""";

  /**
   * The outcome set, and the record's highest ordinal read in the same statement: the push a settle
   * sends names it, and a second round trip for it would be one per tool call.
   */
  private static final String SETTLE =
      "UPDATE orchestration_record SET detail = ?,"
          + " body = COALESCE(?, body)"
          + " WHERE root = ? AND ordinal = ? AND kind = 'tool_call' AND detail IS NULL"
          + " RETURNING (SELECT MAX(ordinal) FROM orchestration_record WHERE root = ?)";

  private static final String THROUGH =
      "SELECT COALESCE(MAX(ordinal), 0) FROM orchestration_record WHERE root = ?";

  private static final String DETAIL_OF =
      "SELECT detail FROM orchestration_record"
          + " WHERE root = ? AND run = ? AND kind = ? ORDER BY ordinal LIMIT 1";

  private static final String LATEST_OF =
      "SELECT "
          + COLUMNS
          + " FROM orchestration_record"
          + " WHERE root = ? AND run = ? AND kind = ? ORDER BY ordinal DESC LIMIT 1";

  private static final String TREE_OF_RUN =
      """
            WITH RECURSIVE up(id, parent, caller_handle, depth) AS (
                SELECT id, parent, caller_handle, 1 FROM orchestrations WHERE id = ?
              UNION ALL
                SELECT o.id, o.parent, o.caller_handle, up.depth + 1
                  FROM orchestrations o JOIN up ON o.id = up.parent
                 WHERE up.depth < %d
            )
            SELECT id, caller_handle FROM up WHERE parent IS NULL"""
          .formatted(DEEPEST);

  private static final String RUN_OF_CONVERSATION =
      """
            WITH RECURSIVE up(id, parent_id, depth) AS (
                SELECT id, parent_id, 1 FROM conversations WHERE id = ?
              UNION ALL
                SELECT c.id, c.parent_id, up.depth + 1
                  FROM conversations c JOIN up ON c.id = up.parent_id
                 WHERE up.depth < %d
            )
            SELECT o.id AS run, o.conductor_conversation = ? AS conductor
              FROM up JOIN orchestrations o ON o.conductor_conversation = up.id
             WHERE up.parent_id IS NULL"""
          .formatted(DEEPEST);

  private static final String AFTER = " AND ordinal > ?";
  private static final String BEFORE = " AND ordinal < ?";

  /**
   * A specific delegate's tool lines, by the conversation column rather than by its actor's name or
   * an ordinal cutoff (spec 2026-09-29 §1a, fixed after review): two delegations to the same agent
   * overlap in a run whenever the first is still {@code AWAITING} an approval when the second is
   * dispatched, and a name or a "latest delegated line" only pins the wrong one of the two. Each
   * delegation gets its own child conversation, so this is exact regardless.
   */
  private static final String TOOL_LINES_OF =
      "SELECT "
          + COLUMNS
          + " FROM orchestration_record WHERE root = ? AND run = ? AND conversation = ?"
          + " AND kind = 'tool_call' ORDER BY ordinal";

  private final JdbcTemplate jdbc;
  private final Supplier<Instant> clock;
  private final UnitOfWork work;

  /**
   * @param work a real transaction: with {@link UnitOfWork#NONE} the lock is released as soon as it
   *     is taken, and appends are back to racing
   */
  public RecordStore(JdbcTemplate jdbc, Supplier<Instant> clock, UnitOfWork work) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.work = Objects.requireNonNull(work, "work");
  }

  /**
   * Write one row at the end of {@code root}'s record, with no conversation of its own — every kind
   * but {@code tool_call} and {@code delegated}. See {@link #append(String, String, String,
   * RecordKind, String, String, String)}.
   *
   * @return the ordinal it was given
   * @throws IllegalArgumentException if {@code text} is blank once made one line
   */
  public int append(
      String root, String run, String actor, RecordKind kind, String text, String detail) {
    return append(root, run, actor, kind, text, detail, null);
  }

  /**
   * Write one row at the end of {@code root}'s record.
   *
   * @param conversation the conversation the event belongs to — a {@code tool_call}'s caller, or a
   *     {@code delegated} line's own delegate — or null for a kind with no one conversation more
   *     its own than the run's. Read back by {@link #toolLinesOf}, and by nothing else: it names a
   *     delegation precisely where {@code actor} alone cannot (spec 2026-09-29 §1a).
   * @return the ordinal it was given
   * @throws IllegalArgumentException if {@code text} is blank once made one line
   */
  public int append(
      String root,
      String run,
      String actor,
      RecordKind kind,
      String text,
      String detail,
      String conversation) {
    return append(root, run, actor, kind, text, detail, conversation, null);
  }

  /**
   * Write one row at the end of {@code root}'s record, with the whole text a person reads behind
   * its line (V64).
   *
   * @param conversation as {@link #append(String, String, String, RecordKind, String, String,
   *     String)} takes it
   * @param body the whole text, newlines kept: stripped, cut past {@link #MOST_BODY} with {@code …}
   *     as a line is cut, and none when blank or null
   * @return the ordinal it was given
   * @throws IllegalArgumentException if {@code text} is blank once made one line
   */
  public int append(
      String root,
      String run,
      String actor,
      RecordKind kind,
      String text,
      String detail,
      String conversation,
      String body) {
    String line = oneLine(text);
    if (line.isEmpty()) {
      throw new IllegalArgumentException("a record row says something; this one was blank");
    }
    String extra = detail == null || oneLine(detail).isEmpty() ? null : oneLine(detail);
    String whole = whole(body);
    OffsetDateTime at = clock.get().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    for (int attempt = 1; ; attempt++) {
      try {
        // Each attempt its own transaction: a refused INSERT aborts the one it is in.
        return work.inTransaction(
            () -> {
              jdbc.query(LOCK, (ResultSet rs) -> null, root);
              Integer ordinal =
                  jdbc.queryForObject(
                      INSERT,
                      Integer.class,
                      root,
                      at,
                      run,
                      actor,
                      kind.wire(),
                      line,
                      extra,
                      conversation,
                      whole,
                      root);
              return Objects.requireNonNull(ordinal, "ordinal");
            });
      } catch (DuplicateKeyException raced) {
        if (attempt >= MOST_ATTEMPTS) {
          throw raced;
        }
      }
    }
  }

  /**
   * Set a tool line's outcome, once.
   *
   * @return the record's highest ordinal as it was set, or empty when this call set nothing — a row
   *     that is not a tool line, or already has an outcome
   */
  public OptionalInt settle(String root, int ordinal, String outcome) {
    return settle(root, ordinal, outcome, null);
  }

  /**
   * Set a tool line's outcome, once, with what the call answered behind it — the tail of a {@code
   * run} that did not end ok, which {@link DelegationFacts} reads back. A tool line's body is that
   * and nothing else; V64's other rows keep theirs as they were.
   *
   * @param output the body, bounded as {@link #append}'s is; null leaves the line without one
   * @return as {@link #settle(String, int, String)}
   */
  public OptionalInt settle(String root, int ordinal, String outcome, String output) {
    List<Integer> through =
        jdbc.queryForList(
            SETTLE, Integer.class, oneLine(outcome), whole(output), root, ordinal, root);
    return through.isEmpty() ? OptionalInt.empty() : OptionalInt.of(through.get(0));
  }

  /** The record's highest ordinal, or 0 for a tree nothing was recorded in. */
  public int through(String root) {
    Integer through = jdbc.queryForObject(THROUGH, Integer.class, root);
    return through == null ? 0 : through;
  }

  /** The {@code detail} of {@code run}'s first row of {@code kind}, when it has one. */
  public Optional<String> detailOf(String root, String run, RecordKind kind) {
    List<String> found =
        jdbc.query(DETAIL_OF, (rs, n) -> rs.getString("detail"), root, run, kind.wire());
    return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.get(0));
  }

  /** {@code run}'s newest row of {@code kind}, when it has one. */
  public Optional<RecordRow> latestOf(String root, String run, RecordKind kind) {
    return jdbc.query(LATEST_OF, ROW, root, run, kind.wire()).stream().findFirst();
  }

  /** The rows of {@code kinds} after {@code after}, in ordinal order. */
  public RecordPage pageAfter(String root, int after, Set<RecordKind> kinds, int most) {
    if (after < 0) {
      throw new IllegalArgumentException("a record is read after ordinal 0 or later, not " + after);
    }
    return page(root, AFTER, after, kinds, false, most);
  }

  /**
   * The rows of {@code kinds} before {@code before}, newest first; {@link #FROM_THE_END} for the
   * tail.
   */
  public RecordPage pageBefore(String root, int before, Set<RecordKind> kinds, int most) {
    if (before < 1) {
      throw new IllegalArgumentException(
          "a record is read back from ordinal 1 or later, not " + before);
    }
    return page(root, BEFORE, before, kinds, true, most);
  }

  /**
   * One delegate's own tool lines in {@code run}, oldest first — the ones written under {@code
   * conversation}, its own child conversation, and nobody else's.
   *
   * @param root the tree
   * @param run the run the lines belong to
   * @param conversation the delegate's own conversation, as {@link #append} wrote it onto each of
   *     its tool_call rows
   * @return the rows
   */
  public List<RecordRow> toolLinesOf(String root, String run, String conversation) {
    return jdbc.query(TOOL_LINES_OF, ROW, root, run, conversation);
  }

  /** The tree {@code run} is in, or empty for a run nothing holds. */
  public Optional<Tree> treeOfRun(String run) {
    return jdbc
        .query(
            TREE_OF_RUN,
            (rs, n) -> new Tree(rs.getString("id"), rs.getString("caller_handle")),
            run)
        .stream()
        .findFirst();
  }

  /** Where {@code conversation} works, or empty for one inside no run tree. */
  public Optional<Place> placeOf(String conversation) {
    record Found(String run, boolean conductor) {}
    List<Found> found =
        jdbc.query(
            RUN_OF_CONVERSATION,
            (rs, n) -> new Found(rs.getString("run"), rs.getBoolean("conductor")),
            conversation,
            conversation);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    Found at = found.get(0);
    return treeOfRun(at.run())
        .map(tree -> new Place(tree.root(), tree.handle(), at.run(), at.conductor()));
  }

  /**
   * Both readings, which differ in one clause: {@code EntryStore.page}'s body, reach first so the
   * page is of the record as it was then — but no {@code COUNT(*)}. A tree's record is a tool line
   * per call and runs to thousands of rows, a count walks every one of them on each read, and each
   * push is a read; whether more lie beyond the page is all a reader uses, and one row past it
   * answers that.
   */
  private RecordPage page(
      String root, String side, int ordinal, Set<RecordKind> kinds, boolean backwards, int most) {
    if (most < 1 || kinds.isEmpty()) {
      throw new IllegalArgumentException(
          "a page of the record holds 1 row or more and reads"
              + " at least one kind, not "
              + most
              + " and "
              + kinds);
    }
    List<String> named =
        kinds.containsAll(RecordKind.EVERY)
            ? List.of()
            : kinds.stream().sorted().map(RecordKind::wire).toList();
    String where =
        " WHERE root = ?"
            + side
            + " AND ordinal <= ?"
            + (named.isEmpty()
                ? ""
                : " AND kind IN ("
                    + String.join(", ", Collections.nCopies(named.size(), "?"))
                    + ")");
    int through = through(root);
    List<Object> bound = new ArrayList<>(List.of(root, ordinal, through));
    bound.addAll(named);
    bound.add(most + 1);
    List<RecordRow> read =
        jdbc.query(
            "SELECT "
                + COLUMNS
                + " FROM orchestration_record"
                + where
                + (backwards ? " ORDER BY ordinal DESC" : " ORDER BY ordinal")
                + " LIMIT ?",
            ROW,
            bound.toArray());
    boolean beyond = read.size() > most;
    return new RecordPage(
        beyond ? read.subList(0, most) : read, read.size(), through, backwards ? beyond : null);
  }

  /** One line: whitespace collapsed, and no longer than {@link #MOST_CHARACTERS}. */
  static String oneLine(String text) {
    String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
    return flat.length() <= MOST_CHARACTERS ? flat : flat.substring(0, MOST_CHARACTERS - 1) + "…";
  }

  /**
   * A body: stripped, its own newlines kept, no longer than {@link #MOST_BODY}; null when there is
   * nothing in it.
   */
  static String whole(String body) {
    String kept = body == null ? "" : body.strip();
    if (kept.isEmpty()) {
      return null;
    }
    return kept.length() <= MOST_BODY ? kept : kept.substring(0, MOST_BODY - 1) + "…";
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static final RowMapper<RecordRow> ROW =
      (rs, n) ->
          new RecordRow(
              rs.getInt("ordinal"),
              instant(rs, "at"),
              rs.getString("run"),
              rs.getString("actor"),
              RecordKind.of(rs.getString("kind"))
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "orchestration_record holds a kind this build does not know")),
              rs.getString("text"),
              rs.getString("detail"),
              rs.getString("body"));
}
