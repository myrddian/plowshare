package io.aeyer.plowshare.server.archive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.CompletionOutcome;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.Invocation;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

/**
 * Every message each conversation has held, in the order it held them.
 *
 * <h2>Append and read, and one annotation that is written once</h2>
 *
 * <p>There is no update and no delete here, and there is no method that could be one. {@link
 * #append} adds a row, {@link #forConversation} and {@link #thatProjectFor} read them back — the
 * whole log and the part of it a model can be shown — and {@link #supersede} writes the single
 * annotation a fold leaves, which is the only write to an existing row this table has, is guarded
 * so that it lands at most once per row, and touches nothing an entry says. The content of an entry
 * is written once and is never edited afterwards.
 *
 * <p>That is what makes a fold an <em>append</em>. {@code CompactionStore} already says the
 * corresponding thing about {@code turns} — compacting rewrites what the model is shown and
 * rewrites nothing a person can read — and this store is where that promise stops being a
 * convention about which methods exist and becomes the whole of the surface.
 *
 * <h2>The ordinal is this store's to assign</h2>
 *
 * <p>{@link #append} takes no entry number. It computes {@code MAX(ordinal) + 1} over the
 * conversation's own entries inside the INSERT, so a caller cannot write the same place twice,
 * cannot leave a gap, and cannot number one conversation's log from another's count.
 *
 * <p><b>One writer per conversation at a time</b>, and not the optimistic scheme alone. A
 * conversation has two writers at once as a matter of course: a fold runs on its own thread after a
 * turn ends, and appends its summary and its diagnostic while the person's next turn appends its
 * utterance. Left to race, both compute the same number, {@code
 * entries_one_per_place_in_a_conversation} refuses one, and the turn's transcript swallows a failed
 * append — so an utterance, an answer or a fold's summary vanished from every later prompt,
 * measured in {@code ConversationEndToEndTest} under load. So each append first takes a
 * transaction-scoped advisory lock on its conversation, and only then reads the maximum: in READ
 * COMMITTED the INSERT's snapshot is taken after the lock is granted, so it sees every row the
 * previous holder committed — {@code RecordStore}'s scheme and its reason. Inside {@link #fold} or
 * {@link #foldWithinATurn} the append joins that transaction and the lock is held until the fold
 * commits, so a turn's append waits for the whole fold rather than numbering itself against a
 * summary nobody can see yet. Under {@link UnitOfWork#NONE} — a fixture — the lock is released as
 * soon as it is taken, which is the optimistic scheme again.
 *
 * <h2>What it does not translate</h2>
 *
 * <p>{@code entries_belong_to_a_conversation} fires for an id nothing opened and {@code
 * entries_one_per_place_in_a_conversation} fires for the concurrent second writer; neither is
 * turned into a sentence, on {@link TurnStore}'s reasoning word for word. The two situations are
 * opposite and only one of them can be reproduced from a fixture this store can drive, so a single
 * {@code catch} would give the race the missing conversation's message. The constraints are named,
 * so Postgres says which rule broke.
 *
 * <p>Framework-free apart from {@link JdbcTemplate}, and wired by {@link ArchiveConfig}, matching
 * {@link TurnStore}, {@link CompactionStore} and {@link ConversationStore}.
 */
public final class EntryStore {

  private static final String COLUMNS =
      "conversation_id, ordinal, kind, role, content, tool_call_id, tool_calls,"
          + " superseded_by, handle, turn_ordinal, recorded_at, took_ms, ejected_at,"
          + " export";

  /**
   * The log's advisory-lock space: the two-key form, so no other advisory lock this database takes
   * can collide with an entry's — {@code RecordStore.LOCK_SPACE} is 59, the migration that made its
   * table, and 11 is this one's.
   */
  static final int LOCK_SPACE = 11;

  /* Held until the transaction the append runs in commits; see the class javadoc. */
  private static final String LOCK =
      "SELECT pg_advisory_xact_lock(" + LOCK_SPACE + ", hashtext(?))";

  /*
   * INSERT ... SELECT and not a read followed by a write, for TurnStore's
   * reason: the ordinal is computed from the same statement that uses it, so
   * there is no window between deciding an entry's place and writing it, and
   * COALESCE turns the empty aggregate into the 1 that
   * entries_are_numbered_from_one requires.
   *
   * The WHERE is the whole of the per-conversation numbering. Without it every
   * conversation would be numbered from the table's global maximum.
   *
   * CAST(? AS JSONB) rather than a bare parameter: the driver binds a String
   * as varchar, and Postgres refuses to assign a varchar to a jsonb column
   * without one. The cast also does the validating -- a malformed document is
   * refused at the INSERT rather than on the read that fails to build a
   * request from it. ProposalStore.HOME_MATCHES already uses CAST(? AS TEXT)
   * for the sibling reason.
   */
  private static final String INSERT =
      """
            INSERT INTO entries (conversation_id, ordinal, kind, role, content, tool_call_id,
                                 tool_calls, handle, turn_ordinal, recorded_at, took_ms,
                                 invocation, produced_by, dispatch, model_specifier, model_pool,
                                 wire_model, completion, finish_reason, prompt_tokens,
                                 completion_tokens, reasoning_tokens, sent_at, fallback_reason,
                                 first_token_ms, speaker, speaker_name, outcome, salients, job_id)
            SELECT ?, COALESCE(MAX(ordinal), 0) + 1, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?,
                   ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?
              FROM entries
             WHERE conversation_id = ?
            RETURNING %s"""
          .formatted(COLUMNS);

  /*
   * V39's columns, read on their own and never through COLUMNS. What a model
   * call was is execution provenance: no reader that renders a conversation
   * wants it, and adding it to the row every projection reads would put it one
   * careless mapping away from a prompt.
   */
  private static final String INVOCATION_OF =
      """
            SELECT invocation, produced_by, dispatch, model_specifier, model_pool, wire_model,
                   completion, finish_reason, prompt_tokens, completion_tokens,
                   reasoning_tokens, sent_at, fallback_reason, first_token_ms
              FROM entries
             WHERE conversation_id = ?
               AND ordinal = ?
               AND invocation IS NOT NULL""";

  /*
   * The answer a turn came to is its last answer that asked for no tools, and
   * the one this reads is the latest of those before a given turn. `answer`
   * and not `refusal`: a refusal something else answered is not what the
   * conversation said, so it cannot be what a later turn followed.
   */
  private static final String LAST_ANSWER_DISPATCH =
      """
            SELECT dispatch
              FROM entries
             WHERE conversation_id = ?
               AND kind = 'answer'
               AND tool_calls IS NULL
               AND turn_ordinal < ?
             ORDER BY ordinal DESC
             LIMIT 1""";

  /*
   * WHAT A HANDLE IS REDEEMED WITH, and the two clauses are two different
   * rules rather than one compound lookup.
   *
   * `handle = ?` is the address. It is unique across the whole table --
   * entries_handle_addresses_one_row -- so this clause alone already answers
   * with at most one row, and the unique index is what serves it.
   *
   * `conversation_id = ?` is the AUTHORISATION, and it is here because
   * unguessable is not the same as unauthorised. A UUID cannot be constructed
   * by a model holding a different one, so the request is meant to be
   * inexpressible; this is the second copy of the rule, for the day a handle
   * reaches somewhere it should not by a route nobody predicted. It is
   * deliberately not a disambiguation -- see the index -- so it can be read as
   * what it is.
   *
   * WHAT IS DELIBERATELY ABSENT is `superseded_by IS NULL`. THAT_PROJECT has
   * it because a folded row must not reach a prompt; this must not, because a
   * folded row is exactly what a model redeems. The whole claim compaction now
   * makes -- a summary is a view and the detail behind the seam is still
   * there -- is this one missing clause. `role IS NOT NULL` is absent for a
   * reason that costs nothing: the handle column only exists on a
   * `tool_result`, which carries the `tool` role, so the schema has already
   * made every addressable row a projecting one.
   */
  private static final String REDEEM =
      "SELECT " + COLUMNS + " FROM entries WHERE conversation_id = ? AND handle = ?";

  /*
   * WHAT A FOLD TOOK THE REFERENCE LINE AWAY FROM, which is the exact
   * complement of THAT_PROJECT below and is written to be read beside it.
   *
   * `superseded_by IS NOT NULL` is the scope and not an optimisation. A result
   * a fold has NOT covered is in the prompt already, as its own reference line
   * carrying these same three facts, so listing it would spend a bounded page
   * on addresses the model is holding anyway.
   *
   * `handle IS NOT NULL` excludes the rows written before V13, which have no
   * address. `kind = 'tool_result'` is then redundant against the CHECK
   * entries_only_a_tool_result_is_addressable -- nothing else can carry a
   * handle -- and is kept because this is the sentence a reader checks the
   * scope against, and a scope that depends on a constraint in another file to
   * be right is one nobody can read here.
   */
  private static final String BEHIND_A_SEAM =
      " AND kind = 'tool_result' AND superseded_by IS NOT NULL AND handle IS NOT NULL";

  /*
   * HOW MANY THERE ARE, asked separately from which ones are on this page.
   *
   * COUNT(*) OVER () in the page query itself was the alternative and it is
   * wrong for the one case the sentence has to be right about: a page PAST THE
   * END returns no rows, and a window function over no rows has nothing to
   * carry the total on. That case is the one where a model most needs the
   * number -- "there are 57 and you asked for the 90th" is what tells it to
   * page back rather than to conclude the list is empty. file_read splits the
   * same pair for the same reason: a window and Span.totalLines.
   */
  private static final String COUNT_BEHIND_A_SEAM =
      "SELECT COUNT(*) FROM entries WHERE conversation_id = ?" + BEHIND_A_SEAM;

  /*
   * ONE PAGE OF THEM, newest first, and it is a projection done in SQL rather
   * than in Java on purpose.
   *
   * WHAT DOES NOT TRAVEL is `content`. The rows this reads are behind the
   * seam, so `thatProjectFor` deliberately never fetches them, and reading
   * them into Java to report a length would pull every covered result's whole
   * text across -- 34 MB for a 200-turn conversation, measured in
   * implementation rationale -- to produce three
   * short fields. `length(content)` is the whole of what the model is told
   * about the size, so the length is what the database sends.
   *
   * length() AND NOT octet_length(). Compaction.REFERENCE reports
   * String.length() -- Java chars -- for a result whose line is still in the
   * prompt, and the two numbers describe the same result to the same model.
   * Postgres `length` on text counts characters, which is the same count for
   * everything in the basic plane and one apart per astral character, where
   * Java counts a surrogate pair as two. That is the closest of the available
   * answers and the disagreement is bounded and harmless: the model uses the
   * number to decide whether a result is worth a turn.
   *
   * THE NAME OF THE TOOL IS NOT ON THE ROW. A `tool_result` carries the id of
   * the call it answers; the name lives in the `tool_calls` document of the
   * `answer` that declared it, which is the same join
   * Compaction.withResultsReferenced does in Java over the projected log. The
   * lateral is what does it here without a second read. A result nothing
   * declares yields NULL rather than being dropped -- no run this server
   * writes can produce one, since the assistant message is recorded before the
   * results it asked for, and a listing that dropped the row would withhold
   * the address as well as the name.
   *
   * ORDER BY turn_ordinal DESC, ordinal DESC is IN_CONVERSATION_ORDER
   * reversed, for the reason that ordering exists at all: `ordinal` alone is
   * arrival order and a fold's summary arrives after the turns it covers.
   * Newest first because the seam a model has just read is the nearest one,
   * and what is behind it is what it was reading a moment ago.
   */
  private static final String BEHIND_A_SEAM_NEWEST_FIRST =
      """
            SELECT (SELECT call ->> 'name'
                      FROM entries answer
                           CROSS JOIN LATERAL jsonb_array_elements(answer.tool_calls) AS call
                     WHERE answer.conversation_id = result.conversation_id
                       AND answer.kind = 'answer'
                       AND call ->> 'id' = result.tool_call_id
                     LIMIT 1)          AS tool,
                   -- COALESCE, because an ejected payload keeps its size and
                   -- loses its text: length(content) answers NULL once the
                   -- content does, and a listing that showed an ejected result
                   -- with no size would say less about it than about the row
                   -- beside it -- in the one listing whose job is to let a model
                   -- choose between them. V19 writes ejected_chars from
                   -- length(content) at the moment it nulls the other, in the
                   -- same units, so the number does not change across the
                   -- ejection.
                   COALESCE(length(result.content), result.ejected_chars) AS size,
                   result.ejected_at      AS ejected_at,
                   result.handle          AS handle
              FROM entries result
             WHERE result.conversation_id = ?%s
             ORDER BY result.turn_ordinal DESC, result.ordinal DESC
            OFFSET ? LIMIT ?"""
          .formatted(BEHIND_A_SEAM);

  /*
   * THE ORDER A CONVERSATION HAPPENED IN, WHICH IS NOT THE ORDER ITS ROWS
   * ARRIVED. `ordinal` alone is arrival order: it is MAX + 1 at the moment of
   * the INSERT, so it says when a row was written and nothing else.
   *
   * That was the same thing until folds became asynchronous. A summary is
   * appended after the turns it stands for, so under `ORDER BY ordinal` it
   * came back after them and the projection, which skips what it covers,
   * rendered the seam in exactly the right place anyway. A fold that lands
   * after a later turn's entries -- which is what asynchronous means -- breaks
   * that: the summary sorts past turns it does not stand for, and two summaries
   * that both still stand come back newest-first, so a model is shown its own
   * history backwards.
   *
   * `turn_ordinal` is what makes the order recoverable, and V11's column
   * comment is the reason it can be read this way: "which turn produced this
   * entry, OR FOR A SUMMARY THE LAST TURN IT STANDS FOR". So a summary sorts
   * among the turns it covers whichever moment it was physically written, and
   * `ordinal` only breaks the tie within one turn -- which is where it is
   * exactly right, since within a turn arrival order IS the order things
   * happened.
   *
   * NOT COVERED BY entries_one_per_place_in_a_conversation, which leads on
   * `ordinal`; V11's note that the read needs no index of its own no longer
   * holds. Left unindexed anyway: a conversation's whole log is a few hundred
   * rows at most, the primary key still cuts to the conversation, and the sort
   * is over what that scan already returned. An index added on a guess would
   * be a claim about a cost nothing here has measured.
   */
  private static final String IN_CONVERSATION_ORDER = " ORDER BY turn_ordinal, ordinal";

  /*
   * THE FILTER THE PROJECTION APPLIES, ASKED OF THE ROWS INSTEAD OF THE
   * OBJECTS. `Projection.of` skips an entry where `supersededBy() != null ||
   * !kind().projects()`, and until this clause existed every one of those rows
   * was fetched in full -- content column included -- so that Java could
   * `continue` past it.
   *
   * THE TWO PREDICATES CANNOT DISAGREE, and that is the whole of why this is a
   * refactor rather than a second opinion about what a model sees.
   * `EntryKind.projects()` is `role != null` on the enum, `append` derives the
   * role column from the kind, and `entries_role_matches_kind` derives it
   * again with a CASE that has no ELSE -- so a kind that does not project can
   * only be stored with a NULL role, and `role IS NOT NULL` selects exactly
   * the kinds `projects()` answers true for. A tool_result is on the visible
   * side of both: it carries the `tool` role and it projects, and what keeps
   * it out of a request is `Compaction.whatWasSaidAndWhatCameBack`, one layer
   * up.
   *
   * WHAT IT SAVES IS UNBOUNDED AND IS NOT URGENT. Measured against pg16 on a
   * conversation shaped like a real one --
   * implementation rationale -- 200 turns is 34 MB
   * of log to produce 25 kB of projection, and the filtered read is CONSTANT
   * across that range because folding is what holds the projected history
   * flat. 10 ms against a multi-second turn is noise; what makes it worth
   * doing is that a superseded row is never needed by this projection again,
   * by construction, and is otherwise re-read on every turn for the rest of
   * the conversation's life.
   *
   * NO INDEX, for IN_CONVERSATION_ORDER's reason and the research doc's. A
   * partial index on (conversation_id, turn_ordinal, ordinal) WHERE this
   * predicate would serve the read directly, and at these row counts the scan
   * is already sub-millisecond and the sort fits in 51 kB of memory. It would
   * be added on a guess.
   */
  private static final String THAT_PROJECT = " AND superseded_by IS NULL AND role IS NOT NULL";

  /*
   * WHEN A TURN'S PROMPT WAS ASSEMBLED, which is the number the read below is
   * entirely built on. MIN(ordinal) over a turn is that turn's FIRST entry,
   * and JobRuntime records the utterance -- `transcript.record(
   * LoggedEntry.utterance(userPrompt, transcript.speaker()))` -- before the
   * loop makes its first model call and before anything else of that turn
   * exists. So the first entry of turn N is the utterance, and its ordinal is
   * the moment [system][history][utterance] was handed to a model.
   *
   * NULL FOR A TURN NO ENTRY BELONGS TO, and the caller raises rather than
   * substituting: see thatProjectedAt. A COALESCE here would turn "there is no
   * such turn" into a number and the read into an answer.
   */
  private static final String WHEN_A_TURN_WAS_ASSEMBLED =
      "SELECT MIN(ordinal) FROM entries WHERE conversation_id = ? AND turn_ordinal = ?";

  /*
   * THE SAME FILTER ASKED OF A MOMENT THAT HAS PASSED, and it is a different
   * predicate rather than THAT_PROJECT with a turn bound on it. The difference
   * is the whole of why this constant exists, so it is argued first.
   *
   * `superseded_by` IS NOT A FACT ABOUT TURN N. It is a fact about the row as
   * it stands right now, and it records every fold that has EVER run in this
   * conversation. THAT_PROJECT reads it as "covered, so not shown", which is
   * true of the next prompt and is exactly wrong of a turn in the past: a fold
   * reaching turn 40 would hide rows turn 12 was plainly shown, and this read
   * exists to serve a screen whose entire job is auditing what a turn was
   * sent. A read like that being confidently wrong is worse than its being
   * slow or absent.
   *
   * SO THE QUESTION IS WHEN THE FOLD WAS WRITTEN, AND `turn_ordinal` CANNOT
   * ANSWER IT. This is the trap the first version of this predicate fell into,
   * and it is written out because it is the kind of thing that gets put back.
   * A summary's turn_ordinal is its REACH and not its moment -- V11's column
   * comment, "or for a summary the last turn it stands for" -- and Compaction
   * sets that reach to `spoken.getLast().ordinal() - 1` from inside the ENDING
   * CALLBACK OF THE TURN AFTER IT. So a summary reaching turn 9 is written at
   * the close of turn 10, and `fold.turn_ordinal <= N` treats it as standing
   * for turn 9's prompt and for turn 10's, when it existed for neither. Asked
   * as of turn 9, that predicate answers with a single summary that folds away
   * turn 9's own utterance -- a correctly assembled history of a moment that
   * did not happen, on every fold a conversation ever takes.
   *
   * `ordinal` IS THE CHRONOLOGY, and IN_CONVERSATION_ORDER says why in the
   * other direction: `ordinal` is MAX + 1 at the moment of the INSERT, so it
   * says WHEN a row was written and nothing else -- which is useless for
   * ordering a conversation and is exactly the fact wanted here.
   * `superseded_by` holds the summary's `ordinal` (SUPERSEDE writes it), and
   * `entries_a_fold_covers_what_came_before_it` guarantees superseded_by >
   * ordinal, so a covered row's `superseded_by` IS the moment its fold
   * arrived, on the same scale as every other row's. No join is needed to read
   * it, and none is used.
   *
   * SO THE PREDICATE IS TWO COMPARISONS AGAINST ONE NUMBER. With P the ordinal
   * WHEN_A_TURN_WAS_ASSEMBLED answers with:
   *
   *   `ordinal <= P`        -- what had been written by then, and it stops at
   *                            the utterance. Turn N's own answer and its tool
   *                            results carry later ordinals and are what the
   *                            turn CAME TO rather than what was in front of
   *                            the model, so bounding here excludes them
   *                            without a second clause. `turn_ordinal <= N`
   *                            would have included them, which is the second
   *                            thing that version got wrong.
   *   `superseded_by > P`   -- the fold was written after this turn's prompt
   *                            was assembled, so this row was still standing
   *                            when the model read it.
   *
   * NEITHER DEPENDS ON WHAT A SUMMARY'S TURN NUMBER MEANS, which is the point:
   * the reach can be redefined and this read stays right.
   *
   * A DANGLING `superseded_by` IS NOT A CASE, and the constraint rather than an
   * argument is what settles it: entries_are_superseded_by_an_entry is a
   * composite FOREIGN KEY onto (conversation_id, ordinal) of this same table,
   * so the pointer always resolves to a real row of this conversation. That is
   * also what makes `superseded_by` usable as a moment at all. Were it
   * reachable, showing the row would be the wrong call on an audit read --
   * refusing and saying the log contradicts itself would be right -- and it is
   * moot because the database will not hold one.
   *
   * `role IS NOT NULL` IS THAT_PROJECT'S OTHER HALF, UNCHANGED, and it means
   * what it means there: EntryKind.projects() asked of the rows, and a kind
   * that carries no role was never shown to a model at any turn.
   *
   * IT IS ALLOWED TO BE SLOWER THAN THE HOT PATH AND IT IS: two statements
   * rather than one, and the first of them scans a conversation for a MIN over
   * an unindexed column. THAT_PROJECT serves every turn of every conversation;
   * this serves a person opening one screen. The cost is a conversation's whole
   * row count -- a few hundred -- and it buys the refusal in thatProjectedAt,
   * which a single statement could not make: a query that computed P inline
   * would answer a turn no entry belongs to with an empty list, and an empty
   * list here reads as "that turn was shown nothing". NO INDEX on turn_ordinal
   * for it, for THAT_PROJECT's reason and IN_CONVERSATION_ORDER's -- an index
   * added here would be a claim about a cost nothing has measured, and this is
   * the read that could least justify one.
   */
  private static final String THAT_PROJECTED_AT =
      "SELECT "
          + COLUMNS
          + " FROM entries WHERE conversation_id = ?"
          + " AND role IS NOT NULL"
          + " AND ordinal <= ?"
          + " AND (superseded_by IS NULL OR superseded_by > ?)"
          + IN_CONVERSATION_ORDER;

  /*
   * WHAT THE LEARNER HAS NOT BEEN GIVEN YET, and it is written to be read
   * beside THAT_PROJECT because it is very nearly its complement: what a model
   * can no longer see is exactly what is worth extracting before the summary
   * stands in for it for good.
   *
   * `superseded_by IS NOT NULL` IS NOT HERE, because it is the one clause that
   * is not true of every tree: see ONLY_FOLDED below, which holds it and the
   * condition it is true under. V20 declines to put it in a CHECK and says why
   * at length -- it is already known to be wrong for a run that never folds
   * and is then archived -- so the rule lives in these two constants, in one
   * place, readable, and the widening this file's split makes possible needed
   * no migration because of that refusal.
   *
   * `learned_at IS NULL` is the queue. It is what makes a pass idempotent (a
   * marked row selects again for nobody), resumable (a pass that died halfway
   * leaves its unmarked half selected) and free of state outside the table.
   *
   * `role IS NOT NULL` is EntryKind.projects() asked of the rows, exactly as
   * THAT_PROJECT asks it, and here it drops what the harness said to itself: a
   * `diagnostic` about a fold's own arithmetic, a `runtime_note`, an
   * `attempt_failed`. A fold covers those -- SUPERSEDE is over a span and not
   * over a list of kinds -- so without this clause every pass would spend a
   * model call asking whether this server's notes to itself are worth
   * remembering.
   *
   * `kind <> 'tool_result'` IS THE ONE THAT IS A JUDGEMENT AND NOT A RULE, and
   * the design says to revisit it. Results are where the facts are; they are
   * also 34 MB of a 200-turn conversation's log against 1.9 MB of projected
   * history (implementation rationale), so a
   * learner that read them would be a much larger and more expensive thing than
   * one that reads what was said about them. They are also, under retention,
   * exactly what is ejected -- so a window built on them would be a window
   * built on the material most likely to have gone, and its value would depend
   * on racing a sweep it is deliberately not coupled to. Nothing is lost that
   * cannot be found: a folded result keeps its handle, and `result_read`
   * redeems it.
   */
  private static final String MINEABLE =
      " AND learned_at IS NULL AND role IS NOT NULL AND kind <> 'tool_result'";

  /*
   * AND THE HALF THAT IS TRUE ONLY WHILE SOMEBODY CAN STILL SPEAK.
   *
   * "Only folded" was never a claim about folding. It is the claim that a live
   * row is still in front of the model, so there is a later moment to mine it
   * in and nothing is lost by waiting -- and that claim has exactly one
   * premise, which is that the conversation goes on. `Turn.speak` refuses a
   * turn into anything that is not ACTIVE, so for every other state the
   * premise is false: there is no later moment, and a folded-only queue is not
   * being careful, it is dropping the material.
   *
   * WHAT IT DROPPED IS NOT AN EDGE CASE. Tool results do not push a fold --
   * `Compaction.foldIfItWouldNotFit` measures what a turn sent and what it
   * added, and a result is neither -- so "ask a question, read one 100 000-
   * character file, answer, done" ends with nothing superseded at all, and
   * neither does a one-turn summariser. Those runs never entered the queue on
   * any terms, which is why `archived` and `to_be_ejected` were decorative:
   * LearningWindow.rank orders a shortlist, and they were never on it.
   *
   * SO THE CONDITION IS `ConversationLifecycle.acceptsWork` AND NOT A LIST OF
   * TWO STATES. V20 names `archived` and `to_be_ejected` because those are the
   * two a person or a policy moves a conversation to, but the rule it argues
   * from is "a conversation nobody will speak into again has no later moment
   * to be mined in", and `ejected` is that more completely than either -- its
   * payloads have already gone. Naming the two would have left the third
   * excluded by an omission rather than by a reason. One method answers it,
   * `Turn` asks the same one, and a fifth state added to the enum is a
   * compile error there and a correct answer here.
   *
   * WHAT IS NOT CONDITIONAL IS `learned_at IS NULL`, and that is what makes the
   * widening safe rather than a loop. It is factored out above rather than
   * repeated, so a live row handed over once is marked and leaves this queue on
   * exactly the terms a folded one does -- `markLearned` names ordinals and
   * never asks whether a row was folded, and `SUPERSEDE` never clears the
   * stamp, so a live row that is learned and then folded stays out. The queue
   * only ever shrinks.
   */
  private static final String ONLY_FOLDED = " AND superseded_by IS NOT NULL";

  /**
   * The queue's predicate for one tree, which is the whole of the widening.
   *
   * @param standing where the ROOT of the tree has got to -- never a child's own column, which is
   *     NULL by {@code conversations_a_root_is_where_the_lifecycle_lives}. A caller reads it with
   *     {@code ConversationStore.rootOf}, so a branch of an archived tree is as finished as the
   *     tree is
   */
  private static String awaitingTheLearner(ConversationLifecycle standing) {
    return standing.acceptsWork() ? MINEABLE + ONLY_FOLDED : MINEABLE;
  }

  /*
   * WHICH CONVERSATIONS HOLD ANY, which is the read a window provider starts
   * from: it is cheaper to ask the queue which conversations are in it than to
   * ask every conversation whether it is.
   *
   * TWO BRANCHES, AND THEY ARE THE TWO SENTENCES THE DESIGN ALREADY SAYS. The
   * first is "material has stopped being visible": folded, unlearned,
   * whatever the tree is doing. The second is "there is no later moment":
   * unlearned, in a tree nobody can speak into again, folded or not. A
   * conversation in both appears twice and the outer GROUP BY makes it one
   * candidate.
   *
   * A UNION ALL AND NOT ONE PREDICATE WITH AN `OR` IN IT, and this is the
   * decision that made the widening free of a migration.
   * `entries_awaiting_the_learner` is a PARTIAL index, written by V20 against
   * `superseded_by IS NOT NULL AND learned_at IS NULL`. A single predicate
   * reading `learned_at IS NULL AND (superseded_by IS NOT NULL OR ...)` does
   * not imply that, so the index would have stopped covering the query it was
   * created for and would have gone on existing, being maintained on every
   * write, and serving nothing -- a silently unused partial index, which is
   * the shape `chunks_by_vector`'s tie-break turned out to have. Split, the
   * first branch is the index's own predicate verbatim and is served by it;
   * `LearningQueueTest
   * .the_folded_half_of_the_queue_is_still_served_by_its_partial_index` reads
   * this constant and EXPLAINs it, so a later edit that merges the branches
   * fails rather than quietly costing the index.
   *
   * AND THE SECOND BRANCH IS DRIVEN FROM THE OTHER SIDE. Its recursion is
   * ConversationStore.TREE_UNDER run from every finished root at once instead
   * of from one, seeded by `conversations_under_retention` -- the same index
   * the retention sweep selects on, and partial on `lifecycle IS NOT NULL`,
   * which is exactly the roots -- and walked down `conversations_children`.
   * The join into `entries` is then on `conversation_id`, the leading column
   * of that table's PRIMARY KEY. The whole widened half is therefore served by
   * indexes that already exist, which is why V20's refusal to add the
   * `learned_at => superseded_by` CHECK is the only thing this needed from the
   * schema.
   *
   * `lifecycle <> 'active'` IS `ConversationLifecycle.acceptsWork` WRITTEN IN
   * SQL, and it is the one place in this file the two have to be kept in step
   * by hand -- awaitingTheLearner(standing) asks the method. They are the same
   * sentence and a divergence is a conversation in the shortlist that the
   * per-conversation read then offers nothing from, which is the race `next`
   * already treats as empty. `lifecycle IS NOT NULL` beside it is what makes
   * the seed the roots: a child's column is NULL and NULL <> 'active' is NULL,
   * so the guard is belt and braces rather than load-bearing -- but a reader
   * should not have to work that out.
   *
   * GROUP BY and not DISTINCT, because the ordering needs an aggregate. OLDEST
   * MATERIAL FIRST -- MIN(recorded_at) -- so a conversation whose span has been
   * waiting longest is offered before one that folded a moment ago, which is
   * the only ordering that does not starve anything. NULLS FIRST puts the rows
   * written before V16__entry_timing.sql at the front, which is the same
   * answer: they are the oldest rows this table holds.
   *
   * The id is the tie-break, so two conversations that folded in the same
   * millisecond come back in the same order on every call. A window that
   * depended on which row the planner reached first would be a window nobody
   * could write a test for.
   */
  static final String CONVERSATIONS_AWAITING_THE_LEARNER =
      """
            WITH RECURSIVE finished(id, depth) AS (
                SELECT id, 1 FROM conversations
                 WHERE lifecycle IS NOT NULL AND lifecycle <> 'active'
              UNION ALL
                SELECT child.id, finished.depth + 1
                  FROM finished
                  JOIN conversations child ON child.parent_id = finished.id
                 WHERE finished.depth < %3$d
            )
            SELECT conversation_id
              FROM (
                    SELECT conversation_id, MIN(recorded_at) AS oldest
                      FROM entries
                     WHERE TRUE%1$s%2$s
                     GROUP BY conversation_id
                     UNION ALL
                    SELECT conversation_id, MIN(recorded_at)
                      FROM entries
                      JOIN finished ON finished.id = entries.conversation_id
                     WHERE TRUE%1$s
                     GROUP BY conversation_id
                   ) queued
             GROUP BY conversation_id
             ORDER BY MIN(oldest) NULLS FIRST, conversation_id
             LIMIT ?"""
          .formatted(MINEABLE, ONLY_FOLDED, ConversationStore.DEEPEST_DELEGATION);

  /*
   * MARKING WHAT WAS HANDED OVER.
   *
   * `learned_at IS NULL` makes it write-once, which is ejectPayload's clause
   * for ejectPayload's reason: when the learner was given a row is a fact about
   * when it happened, and a second pass that restamped it would be claiming
   * work it did not do.
   *
   * The ordinals are named explicitly rather than the span being described
   * again, and that is the point of the whole shape: the system marks THE ROWS
   * IT GAVE. A statement that re-derived the window here could mark a row the
   * pass never saw -- one that arrived between the read and the write -- and
   * `learned_at` would stop being a fact anybody observed.
   */
  private static final String MARK_LEARNED =
      "UPDATE entries SET learned_at = ? WHERE conversation_id = ?"
          + " AND learned_at IS NULL AND ordinal IN (%s)";

  /**
   * How much of a tool result or diagnostic a page carries.
   *
   * <p>Tool results and diagnostics stay bounded in the database, as do tool arguments. Utterances,
   * answers, refusals and conversation summaries travel whole: readers need their complete text,
   * including long Markdown answers. Applying this cap to an answer made a streamed response lose
   * its ending when the desktop replaced it with durable history.
   *
   * <p>The entry's real length travels beside the excerpt, so a page never silently shortens
   * anything: {@code EntryPage.Row.cut()} is the question, and the whole text is still reachable
   * through {@code result_read} for the one kind that has a handle.
   */
  public static final int MOST_CHARACTERS_PER_ENTRY = 8_000;

  /** Every kind an entry can be: a reading narrowed by nothing. */
  public static final Set<EntryKind> EVERY_KIND =
      Collections.unmodifiableSet(EnumSet.allOf(EntryKind.class));

  /**
   * The {@code before} that reads from the log's end: past any ordinal a conversation will reach,
   * so {@code ordinal < before} holds for every row.
   */
  public static final int FROM_THE_END = Integer.MAX_VALUE;

  /* The conversation's highest ordinal: what `conversation.appended` and a page's `through`
   * say. Served by the primary key, which leads on (conversation_id, ordinal). */
  private static final String THROUGH =
      "SELECT COALESCE(MAX(ordinal), 0) FROM entries WHERE conversation_id = ?";

  /*
   * HOW MANY THERE ARE, asked separately from which ones are on this page, for
   * COUNT_BEHIND_A_SEAM's reason exactly: a page past the end returns no rows,
   * and a window function over no rows has nothing to carry the total on --
   * which is the one case where the number is what tells a caller to page back
   * instead of concluding the conversation is empty.
   *
   * %s is the reading's whole narrowing, built by `page` out of this file's
   * own clauses: which side of an ordinal (AFTER or BEFORE), no further than the
   * reach read first (REACHED), then nothing (the
   * log), THAT_PROJECT (what a model is shown) or DRAWN_ANSWERS_ONLY, then the
   * kinds asked for.
   * The same string narrows the count and the page it describes, so the two
   * cannot be counting different things.
   *
   * AFTER with 0 reads everything: pageOfLog and pageOfProjection both call
   * through here with after fixed at 0, so this is one clause serving both the
   * whole-log read and the since-an-ordinal read rather than a second query
   * for the second case.
   */
  private static final String COUNT_OF =
      "SELECT COUNT(*) FROM entries" + " WHERE conversation_id = ?%s";

  /* What came after an ordinal -- a forward reading's side of it. */
  private static final String AFTER = " AND ordinal > ?";

  /* What came before an ordinal -- a backwards reading's side of it. */
  private static final String BEFORE = " AND ordinal < ?";

  /* Nothing past the reach read first: every reading is of the log as it was
   * then, which is what `page` argues. */
  private static final String REACHED = " AND ordinal <= ?";

  /*
   * ONLY WHAT A CHAT DRAWS OF THE ANSWERS: an answer that asked for tools is
   * the model on its way to one, and no chat shows it. Left in, a reading of
   * "the last forty" spends most of its forty on them in a tool-heavy
   * conversation and the reader hides them afterwards -- measured in the TUI's
   * fixture as eleven turns out of a forty-row tail. Out here, before LIMIT, so
   * the limit is filled with rows that are drawn and COUNT_OF counts the same.
   *
   * An empty list is written as NULL (`append`), and jsonb_array_length is
   * asked anyway so an empty array written by anything else reads the same.
   */
  private static final String DRAWN_ANSWERS_ONLY =
      " AND (kind <> 'answer'" + " OR COALESCE(jsonb_array_length(tool_calls), 0) = 0)";

  /*
   * NEWEST FIRST, by arrival. A backwards reading answers "the last N", which
   * is a question about when rows were written, so it is ordinal and not
   * IN_CONVERSATION_ORDER: the primary key (conversation_id, ordinal) is
   * unique and is walked backwards, and LIMIT stops the walk at N rows rather
   * than sorting the conversation first. A summary written late is therefore
   * the newest row of its reading, which is where it arrived.
   */
  private static final String NEWEST_FIRST = " ORDER BY ordinal DESC";

  /*
   * ONE PAGE OF ENTRIES, in conversation order, with tool and diagnostic text
   * cut in the database and transcript text kept whole.
   *
   * left(content, ?) AND length(content) for large payloads, which is the
   * whole reason this is a query of its own and not `forConversation` with a
   * LIMIT on it. BEHIND_A_SEAM_NEWEST_FIRST already argues it and the
   * arithmetic is the same one: reading a covered result into Java to report
   * its length pulls its whole text across -- 34 MB for a 200-turn
   * conversation, measured in
   * implementation rationale -- and a page that
   * shows 8 000 characters of it has no use for the other 92 000.
   *
   * length() AND NOT octet_length(), for that query's reason: Compaction's
   * reference line reports Java chars for the same result, and the two numbers
   * describe the same entry to the same reader.
   *
   * left() COUNTS THE SAME CHARACTERS length() DOES, so `length > excerpt
   * length` is exactly "there is more of this", except across an astral
   * character, where Postgres counts one and Java counts two. The excerpt is
   * then shorter in Java's counting than the cap, which reads as a row that
   * was cut and was, and no text is lost either way.
   *
   * ORDER BY is IN_CONVERSATION_ORDER or NEWEST_FIRST, appended by the caller,
   * and OFFSET/LIMIT come after it: a page taken before the sort would be a page of arrival
   * order sorted afterwards, which is a different set of rows.
   */
  private static final String PAGE_OF =
      """
            SELECT ordinal, turn_ordinal, kind,
                   CASE WHEN kind IN ('utterance', 'answer', 'refusal', 'summary')
                        THEN content ELSE left(content, ?) END AS excerpt,
                   COALESCE(length(content), ejected_chars) AS length, ejected_at,
                   superseded_by, tool_call_id,
                   (SELECT jsonb_agg(jsonb_build_object(
                                       'id',        call ->> 'id',
                                       'name',      call ->> 'name',
                                       'arguments', left(call ->> 'arguments', ?),
                                       'length',    length(call ->> 'arguments'),
                                       'salient',   entries.salients ->> (call ->> 'id'),
                                       'opened',    (SELECT jsonb_build_object(
                                                              'conversation', child.id,
                                                              'agent',        child.agent)
                                                       FROM conversations child
                                                      WHERE child.parent_id = entries.conversation_id
                                                        AND child.opened_by_call = call ->> 'id'
                                                      LIMIT 1))
                                     ORDER BY asked)
                      FROM jsonb_array_elements(entries.tool_calls)
                           WITH ORDINALITY AS each(call, asked)) AS tool_calls,
                   handle, recorded_at, took_ms,
                   dispatch, wire_model, completion, speaker, speaker_name, outcome, job_id
              FROM entries
             WHERE conversation_id = ?%s""";

  /**
   * The text search configuration this log is stemmed and questioned in.
   *
   * <p><b>Welded into {@code V23} as well, and it has to be.</b> A generated column may not read
   * {@code default_text_search_config}, which is a session setting, so the migration spells {@code
   * english} as a literal and this constant is what makes the query side spell the same word. <b>A
   * mismatch does not fail</b> — the query parses, the operator runs, rows come back or do not —
   * matching just quietly stops, which is the vector half's model coupling in a place with no width
   * to check. {@code LogSearchTest.the_migration_and_the_query_stem_by_the_same_configuration} is
   * that check.
   *
   * <p>Its own constant and not {@code DocumentStore.TEXT_SEARCH_CONFIGURATION} although the two
   * spell the same word today. They are two corpora with two frozen migrations, and one of them
   * changing configuration is a rewrite of one column and a rebuild of one index — a shared
   * constant would make that a change to both, silently.
   */
  static final String TEXT_SEARCH_CONFIGURATION = "english";

  /**
   * The most of one hit's surroundings a search shows.
   *
   * <p><b>A backstop under a bound that is counted in words.</b> {@code ts_headline} bounds a
   * snippet by words, which is the right unit for something meant to be read around a match and the
   * wrong one for anything that has to fit somewhere: a "word" is whatever the text had between two
   * spaces, so a minified bundle on one line makes sixty words an enormous string. This is the same
   * cut in the same units the rest of this class uses, applied in the database so the text never
   * crosses to be shortened afterwards.
   *
   * <p>Much smaller than {@link #MOST_CHARACTERS_PER_ENTRY}, and the reason is the shape of the
   * answer rather than the shape of a row: a page shows one conversation in order and a search
   * shows up to a hundred hits from anywhere, so an eight-thousand-character allowance per row
   * would be eight hundred thousand characters of answer. The entry's real length travels beside
   * every snippet, and {@code result_read} on the handle is what gives the whole of a tool result
   * back.
   */
  public static final int MOST_CHARACTERS_PER_SNIPPET = 1_000;

  /*
   * HOW MUCH OF AN ENTRY SURROUNDS A MATCH, in ts_headline's own unit.
   *
   * MaxFragments IS WHAT MAKES THIS A SEARCH RESULT RATHER THAN AN OPENING.
   * With it at 0 ts_headline returns the head of the document, which is what
   * PAGE_OF's left(content, ?) already gives and is exactly wrong here: the
   * whole reason to search a 100 000-character tool result is that the match
   * is as likely to be at character ninety thousand as at the first. At 2 the
   * function selects fragments around the query's own lexemes and joins them,
   * so a hit shows what was hit and, when the words are far apart, both
   * places.
   *
   * StartSel AND StopSel ARE OVERRIDDEN BECAUSE THE DEFAULTS ARE HTML. <b> and
   * </b> around a matched word is markup for a browser; every reader of this
   * answer is a terminal, a model or a JSON client, and none of them renders
   * it. Square brackets are what the rest of this codebase already uses to
   * mark a thing inside prose -- FileTools' "[Cut off here: ...]".
   */
  private static final String SNIPPET_OPTIONS =
      "StartSel=\"[\", StopSel=\"]\", MaxWords=60, MinWords=25, MaxFragments=2";

  /*
   * WHAT A SEARCH LOOKED AT AND WHAT IT COULD NOT, plus how many matched, in
   * ONE statement.
   *
   * ONE STATEMENT AND NOT FOUR, for DocumentStore.coverage's reason exactly:
   * four counts asked separately are four questions with writes between them,
   * and the arithmetic a reader does on them -- "so the tier is this big" --
   * would be over a total that was never true. Here that matters more than it
   * does for a corpus, because the three reach counts are asserted to PARTITION
   * the tier, and a partition assembled out of four instants is not one.
   *
   * THE THREE ARE EXCLUSIVE AND EXHAUSTIVE, and the schema is what makes that
   * exact rather than approximate. `entries_an_ejected_payload_is_gone` makes
   * `content IS NULL` and `ejected_at IS NOT NULL` one fact, so the first
   * FILTER's `content IS NOT NULL` and the second's predicate cannot both be
   * true and cannot both be false for a row with a role;
   * `entries_only_a_tool_result_is_ejected` keeps every ejected row on the
   * role-carrying side, so the third FILTER cannot overlap the second.
   *
   * `matched` REPEATS THE READ'S PREDICATE and is not `count(*)` over a
   * subquery of it, because the two have to agree about what a hit is and the
   * cheapest way to make them agree is to write the same three clauses. The
   * expensive way -- a window function on the read itself -- cannot answer at
   * all for a page past the end, which is COUNT_OF's argument one method over.
   */
  static final String REACH_SQL =
      """
            SELECT count(*) FILTER (WHERE e.role IS NOT NULL AND e.content IS NOT NULL)
                       AS searched,
                   count(*) FILTER (WHERE e.ejected_at IS NOT NULL) AS ejected,
                   count(*) FILTER (WHERE e.role IS NULL) AS recorded_only,
                   count(*) FILTER (WHERE e.role IS NOT NULL AND e.text_search @@ q
                                      AND ts_rank_cd(e.text_search, q) > 0) AS matched
              FROM websearch_to_tsquery('%s', ?) AS q
              CROSS JOIN entries e
              JOIN conversations c ON c.id = e.conversation_id
             WHERE c.project_id IS NOT DISTINCT FROM CAST(? AS BIGINT) /*information*/"""
          .formatted(TEXT_SEARCH_CONFIGURATION);

  /*
   * ONE PAGE OF WHAT A QUESTION'S WORDS REACHED, best first.
   *
   * `role IS NOT NULL` IS THE WHOLE OF WHICH KINDS ARE SEARCHABLE, and it is
   * half of THAT_PROJECT rather than a new list of kinds on purpose. The
   * schema derives `role` from `kind` with a CASE that has no ELSE
   * (`entries_role_matches_kind`) and `EntryKind.projects()` is `role != null`
   * on the enum, so this clause selects exactly the four kinds that are the
   * conversation -- and a ninth kind added later is invisible to search until
   * somebody classifies it, which is EntryKind's own preference for a missing
   * message over a leaked one, arriving here for free.
   *
   * THAT_PROJECT'S OTHER HALF IS DELIBERATELY ABSENT. `superseded_by IS NULL`
   * is what a projection needs and is the opposite of what a search does: a
   * fold does not unsay anything, and the part of a long conversation a model
   * can no longer see is most of what somebody searching a log is after. A hit
   * carries `superseded_by` so a reader knows which of the two it is looking
   * at.
   *
   * AND THE `ts_rank_cd(...) > 0` PREDICATE IS LOAD-BEARING, which is V21's
   * finding transferred and not a decoration. `websearch_to_tsquery` reads a
   * leading dash as negation, so a question containing `-alpha` becomes
   * `!'alpha'` -- which MOST ROWS SATISFY, at rank 0. `ts_rank_cd` is a plain
   * function and answers 0 rather than declining to answer, so without this
   * clause a question that named no word to match would come back as the whole
   * tier, ranked, with a LIMIT scooping up whichever rows the scan reached
   * first and every one of them carrying a real-looking rank.
   *
   * THE TIE IS BROKEN IN SQL, WHICH THE VECTOR HALF MAY NOT DO. A GIN index
   * supplies the FILTER and the ranking is a Sort over the bitmap, so a second
   * ORDER BY key costs the plan nothing -- measured, and asserted in
   * LogSearchTest. An HNSW index supplies the ORDER instead, which is why
   * DocumentStore.SEARCH_SQL breaks its ties in Java. It is required rather
   * than tidy: a LIMIT over an unbroken tie takes an arbitrary subset of the
   * tied rows, so the candidate SET and not merely its order would vary
   * between two runs of one question. The key is conversation order --
   * IN_CONVERSATION_ORDER, with the conversation in front of it because a
   * search crosses conversations -- so equally ranked hits read in the order
   * they were said.
   *
   * ts_headline IS COMPUTED ON THE OUTSIDE OF THE LIMIT, and that is not a
   * style choice. It re-parses the whole document it is given, so computing it
   * in the same SELECT as the filter would re-parse every entry that matched
   * and then throw all but a page of them away. Here the subquery ranks,
   * orders and cuts to the page, and the outer SELECT parses the handful of
   * rows that survived.
   *
   * THE TIER JOIN IS `conversations`, because `entries` has no project column
   * and V14 moved the project onto the conversation. HOME_MATCHES' predicate,
   * spelled the same way, for the reason ConversationStore gives: `= NULL` is
   * NULL, so a plain equality would return nothing for the global tier, which
   * is the ordinary case.
   *
   * IT DOES NOT FILTER ON `origin`, unlike ConversationStore.inHome. That read
   * lists what a person opened; this one searches what was said, and the work
   * a delegated run did is said in a child conversation. A search that omitted
   * those would answer "nothing" about a tier in which the thing was
   * discussed at length, one conversation down -- which is the confident-empty
   * answer the reach counts exist to prevent, arriving through a WHERE clause
   * instead.
   */
  static final String SEARCH_SQL =
      """
            SELECT h.conversation_id, h.ordinal, h.turn_ordinal, h.kind, h.rank,
                   left(ts_headline('%s', h.content, h.query, '%s'), ?) AS snippet,
                   h.length, h.superseded_by, h.handle, h.recorded_at, md5(h.content) AS source_revision
              FROM (SELECT e.conversation_id, e.ordinal, e.turn_ordinal, e.kind, e.content,
                           length(e.content) AS length, e.superseded_by, e.handle,
                           e.recorded_at, q AS query, ts_rank_cd(e.text_search, q) AS rank
                      FROM websearch_to_tsquery('%s', ?) AS q
                      CROSS JOIN entries e
                      JOIN conversations c ON c.id = e.conversation_id
                     WHERE c.project_id IS NOT DISTINCT FROM CAST(? AS BIGINT) /*information*/
                       AND e.role IS NOT NULL
                       AND e.text_search @@ q
                       AND ts_rank_cd(e.text_search, q) > 0
                     ORDER BY rank DESC, e.conversation_id, e.turn_ordinal, e.ordinal
                     OFFSET ? LIMIT ?) AS h
             ORDER BY h.rank DESC, h.conversation_id, h.turn_ordinal, h.ordinal"""
          .formatted(TEXT_SEARCH_CONFIGURATION, SNIPPET_OPTIONS, TEXT_SEARCH_CONFIGURATION);

  /*
   * ordinal < ? excludes the summary entry itself, which carries the reach as
   * its own turn_ordinal and would otherwise fold itself away the moment it
   * arrived.
   *
   * turn_ordinal > ? is the lower bound, and it is what makes a fold cover the
   * span since the last fold rather than the whole history again. The previous
   * summary carries that number as its own turn_ordinal, so it falls outside
   * the range and stays live -- which is the point: two summaries in front of
   * a model, each span summarised once, and a prompt prefix that does not move
   * under the fold. supersede()'s javadoc argues both halves.
   *
   * superseded_by IS NULL is what makes this write-once, and it still earns
   * its place now that the ranges are disjoint in the ordinary case. They are
   * not disjoint in every case: a fold the log could not be given leaves a
   * span nothing covers, the next fold's lower bound is still the older
   * standing summary's reach, and that fold's range therefore includes ground
   * an earlier one already covered. Without this clause it would repoint those
   * rows at itself -- losing which fold first covered them, which is a fact
   * about when it happened. Skipping is all the projection needs; a row is
   * superseded or it is not.
   */
  private static final String SUPERSEDE =
      """
            UPDATE entries
               SET superseded_by = ?
             WHERE conversation_id = ?
               AND turn_ordinal > ?
               AND turn_ordinal <= ?
               AND ordinal < ?
               AND superseded_by IS NULL""";

  /*
   * COALESCE, so a conversation nothing has folded answers 0 rather than NULL
   * and the caller has a number instead of a case. MAX over an empty set is
   * NULL and not 0, and 0 is a turn no entry can carry --
   * entries_belong_to_a_turn_numbered_from_one -- so the substitution invents
   * nothing.
   *
   * superseded_by IS NULL is what makes this the STANDING reach and not merely
   * the newest one. A log written before folds became incremental has each
   * summary covered by the one after it, so exactly one of them stands, and
   * that one is how far the log is really folded.
   */
  /*
   * A STANDING IN-TURN SUMMARY REACHES THE TURN BEFORE ITS OWN. A fold inside
   * turn N takes every earlier turn no fold stood for yet along with N's older
   * steps (spec 2026-09-30-fold-at-60-and-80 §1), so once it stands the log is
   * folded through N - 1 -- and through N - 1 already, if nothing earlier was
   * left to take. It carries N and not N - 1 as its own turn_ordinal because it
   * is read inside turn N, between that turn's request and its kept steps.
   */
  private static final String FOLDED_THROUGH =
      """
            SELECT COALESCE(MAX(CASE kind WHEN 'summary' THEN turn_ordinal
                                          ELSE turn_ordinal - 1 END), 0)
              FROM entries
             WHERE conversation_id = ?
               AND kind IN ('summary', 'turn_summary')
               AND superseded_by IS NULL""";

  /*
   * WHAT A FOLD INSIDE TURN N COVERS, in one statement: every row of a turn
   * after `since` and before N -- the between-turn fold's range, one turn
   * short of the turn in progress -- and the rows of N itself from the first
   * folded step's answer to the last folded step's last result. `ordinal < ?`
   * keeps the summary off itself, `superseded_by IS NULL` keeps a row's first
   * fold its answer, and `kind <> 'turn_summary'` leaves an earlier in-turn
   * summary standing beside this one, as a between-turn summary stands beside
   * the next: each span is summarised once. The opening request and whatever
   * the turn opened with sit before the first folded step, so the range never
   * reaches them.
   */
  private static final String SUPERSEDE_WITHIN_A_TURN =
      """
            UPDATE entries
               SET superseded_by = ?
             WHERE conversation_id = ?
               AND ordinal < ?
               AND superseded_by IS NULL
               AND ((turn_ordinal > ? AND turn_ordinal < ?)
                    OR (turn_ordinal = ? AND ordinal >= ? AND ordinal <= ?
                        AND kind <> 'turn_summary'))""";

  /**
   * One writer and one reader for every call on every thread.
   *
   * <p>Jackson documents both as immutable and safe to share once configured, which is the same
   * claim {@code ModelJson} and {@code ToolArguments} rest on and measured. Jobs run concurrently
   * on virtual threads, so this is shared by everything.
   */
  private static final ObjectWriter CALLS_OUT =
      new ObjectMapper().writerFor(new TypeReference<List<ToolCall>>() {});

  /**
   * The cut calls a page's subselect builds, which are {@code EntryPage.Asked} and not {@code
   * ToolCall}: the arguments on one may be two thirds of what was sent, and that is not a value a
   * dispatcher may be handed.
   */
  private static final ObjectReader ASKED_IN =
      new ObjectMapper().readerFor(new TypeReference<List<EntryPage.Asked>>() {});

  private static final ObjectReader CALLS_IN =
      new ObjectMapper().readerFor(new TypeReference<List<ToolCall>>() {});

  private Supplier<ConversationSearch> retrieval;

  public EntryStore retrieving(Supplier<ConversationSearch> retrieval) {
    this.retrieval = retrieval;
    return this;
  }

  public io.aeyer.plowshare.server.api.LogSearchView searchView(
      Home home, String question, int skip, int most, String mode, String snapshot) {
    return searchView(home, question, skip, most, mode, snapshot, null);
  }

  public io.aeyer.plowshare.server.api.LogSearchView searchView(
      Home home,
      String question,
      int skip,
      int most,
      String mode,
      String snapshot,
      UsageAttribution owner) {
    if (mode == null && snapshot == null)
      return io.aeyer.plowshare.server.api.LogSearchView.of(
          search(home, question, skip, most), skip, most);
    ConversationSearch.mode(mode);
    if (retrieval == null) throw new ValidationException("Semantic retrieval is not configured");
    return owner == null
        ? retrieval.get().search(home, question, skip, most, mode, snapshot)
        : retrieval.get().search(home, question, skip, most, mode, snapshot, owner);
  }

  private final JdbcTemplate jdbc;
  private boolean informationScoped;
  private boolean protectInformationLearning;

  public void protectInformationLearning() {
    protectInformationLearning = true;
  }

  private String informationAccount;
  private final Supplier<Instant> now;
  private final UnitOfWork transactions;

  /**
   * The real clock and no transaction boundary, which is a fixture and is said rather than
   * disguised.
   *
   * <p>{@link UnitOfWork#NONE}, on that field's own terms: every method here but the folds is one
   * statement, or {@link #append}'s lock and its one statement, so a test asserting on this table's
   * rules against a real Postgres in autocommit sees exactly what a transactional store would show
   * it — short of two appends racing, which only a real boundary serialises. <b>Not for production
   * wiring.</b> {@code ArchiveConfig.entryStore} takes the constructor below, and {@code
   * CompactionTest.a_fold_whose_supersede_fails_leaves_no_summary_behind_and_
   * the_next_fold_takes_the_span} is what fails when a fold runs without a boundary around it.
   */
  public EntryStore forAccount(String account) {
    EntryStore copy = new EntryStore(jdbc, now, transactions);
    copy.informationScoped = true;
    copy.informationAccount = account;
    if (retrieval != null) copy.retrieval = () -> retrieval.get().forAccount(account);
    return copy;
  }

  private void requireInformation(String log) {
    if (informationScoped
        && !jdbc.queryForObject(
            "SELECT information_log_readable(?,?)", Boolean.class, log, informationAccount))
      throw new io.aeyer.plowshare.server.faults.NotFoundFault(
          "log inputs are unavailable to this account");
  }

  private <T> T informationQuery(
      String sql, org.springframework.jdbc.core.ResultSetExtractor<T> mapper, Object... args) {
    String marker = "/*information*/";
    int at = sql.indexOf(marker);
    java.util.List<Object> bound = new java.util.ArrayList<>(java.util.Arrays.asList(args));
    if (informationScoped) {
      int before = (int) sql.substring(0, at).chars().filter(c -> c == '?').count();
      bound.add(before, informationAccount);
    }
    return jdbc.query(
        sql.replace(marker, informationScoped ? " AND information_log_readable(c.id,?)" : ""),
        mapper,
        bound.toArray());
  }

  private <T> java.util.List<T> informationRows(
      String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
    return informationQuery(
        sql, new org.springframework.jdbc.core.RowMapperResultSetExtractor<>(mapper), args);
  }

  public EntryStore(JdbcTemplate jdbc) {
    this(jdbc, Instant::now, UnitOfWork.NONE);
  }

  /**
   * The same, with the clock chosen. See below for {@code now}, and above for why this one binds
   * {@link UnitOfWork#NONE}.
   */
  public EntryStore(JdbcTemplate jdbc, Supplier<Instant> now) {
    this(jdbc, now, UnitOfWork.NONE);
  }

  /** Production wiring: the real clock, and a real transaction boundary for {@link #fold}. */
  public EntryStore(JdbcTemplate jdbc, UnitOfWork transactions) {
    this(jdbc, Instant::now, transactions);
  }

  /**
   * @param jdbc where the rows live
   * @param now the clock every entry is stamped from, injected for {@code ConversationStore}'s
   *     reason: the stamp is on {@link EntryRecord}, so a test that asserts on it has to be able to
   *     choose it. {@code DEFAULT now()} in the column was the alternative and is a clock nothing
   *     in a test can pull on — {@code V6__conversations.sql} settled the same question the same
   *     way for {@code created_at}
   * @param transactions what makes {@link #fold}'s two statements one, which is the only place in
   *     this class that has two. {@link UnitOfWork} argues why the mechanism arrives from outside
   *     rather than being named here
   */
  public EntryStore(JdbcTemplate jdbc, Supplier<Instant> now, UnitOfWork transactions) {
    this.jdbc = jdbc;
    this.now = now;
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  /**
   * Record one thing that happened, at the next place in its conversation's log.
   *
   * <p>The role is derived from the kind here rather than taken from a caller, which is the point
   * of storing it at all: two columns that can disagree would be two answers to one question, and
   * {@code entries_role_matches_kind} refuses the disagreement anyway. A caller cannot make an
   * invisible kind visible by asking for a role.
   *
   * @param conversationId the conversation this was held in
   * @param turnOrdinal which turn produced it — the count of turns already in the conversation plus
   *     one, which is what the turn's own ordinal will be if it lands. <b>Not checked against
   *     {@code turns}</b>, and it cannot be: entries are written as a turn runs and a turn's row is
   *     written when it ends, so the run that most needs this log has no row to point at. {@code
   *     V11__entries.sql} argues it at length
   * @param entry what happened, in the shape its kind allows
   * @return the entry as it was written, carrying the ordinal it was given
   * @throws ValidationException if the conversation id cannot be named
   * @throws org.springframework.dao.DataIntegrityViolationException if no conversation has that id.
   *     Untranslated, and the class javadoc says why
   */
  public EntryRecord append(String conversationId, int turnOrdinal, LoggedEntry entry) {
    return append(conversationId, turnOrdinal, entry, null);
  }

  /** Records the immutable owning job; null means an unbound or legacy entry, never a guess. */
  public EntryRecord append(String conversationId, int turnOrdinal, LoggedEntry entry, String job) {
    if (job != null) ArchiveValues.identity(job, "job id");
    String conversation = ConversationStore.named(conversationId);
    String kind = entry.kind().wireName();
    String role = entry.kind().role().map(chatRole -> chatRole.wireName()).orElse(null);
    String calls = entry.toolCalls().isEmpty() ? null : written(entry.toolCalls());
    // Minted here and never taken from a caller, which is `ordinal`'s rule
    // and V13's own argument for it: a caller that could choose a handle
    // could choose a predictable one, or one already in use. Only a
    // `tool_result` gets one, because a handle is an address a MODEL may ask
    // for content at and a result is the only thing a model is ever shown a
    // reference to instead of itself. `entries_only_a_tool_result_is_
    // addressable` holds the same rule in the table, so this line and the
    // constraint would have to be wrong together.
    UUID handle = entry.kind() == EntryKind.TOOL_RESULT ? UUID.randomUUID() : null;
    // Stamped here and never taken from a caller, which is `ordinal`'s rule
    // and `handle`'s: an entry whose time its writer could choose is a log
    // whose order a writer could contradict. Read once per append, so two
    // entries of one turn carry the two moments they were written rather
    // than one moment the batch was.
    OffsetDateTime at = utc(now.get());
    Invocation call = entry.invocation();
    Speaker speaker = entry.speaker();
    String salients = entry.salients().isEmpty() ? null : salientsWritten(entry.salients());
    // Its own short transaction, or the fold's it is part of: the lock first, then the
    // INSERT, whose snapshot is taken after the lock is granted. See the class javadoc.
    return transactions.inTransaction(
        () ->
            ArchiveUnavailableException.translating(
                "record a conversation entry",
                () -> {
                  jdbc.query(LOCK, (ResultSet rs) -> null, conversation);
                  return jdbc.queryForObject(
                      INSERT,
                      ROW_MAPPER,
                      conversation,
                      kind,
                      role,
                      entry.content(),
                      entry.toolCallId(),
                      calls,
                      handle,
                      turnOrdinal,
                      at,
                      entry.tookMillis(),
                      call == null ? null : call.id(),
                      call == null ? null : call.agent(),
                      call == null ? null : call.dispatch().wireName(),
                      call == null ? null : call.specifier(),
                      call == null ? null : call.pool(),
                      call == null ? null : call.wireModel(),
                      call == null ? null : call.outcome().wireName(),
                      call == null ? null : call.finishReason(),
                      call == null ? null : call.usage().promptTokens(),
                      call == null ? null : call.usage().completionTokens(),
                      call == null ? null : call.usage().reasoningTokens(),
                      call == null ? null : utc(call.sentAt()),
                      call == null ? null : call.fallbackReason(),
                      call == null ? null : call.firstTokenMillis(),
                      speaker == null ? null : speaker.kind().wireName(),
                      speaker == null ? null : speaker.name(),
                      entry.outcome(),
                      salients,
                      job,
                      conversation);
                }));
  }

  /**
   * Which model call produced one entry, or empty for an entry no call produced and for one written
   * before V39.
   *
   * <p>The audit read: an answer's text is what the conversation holds, and this is who wrote it.
   * {@code usage.totalTokens} is not stored and comes back null — it is the sum of the two that
   * are.
   *
   * @throws ValidationException if the conversation id cannot be named
   */
  public Optional<Invocation> invocationOf(String conversationId, int ordinal) {
    requireInformation(conversationId);
    String conversation = ConversationStore.named(conversationId);
    return ArchiveUnavailableException.translating(
        "read which model call produced an entry",
        () ->
            jdbc.query(INVOCATION_OF, INVOCATION_MAPPER, conversation, ordinal).stream()
                .findFirst());
  }

  /**
   * Whether the answer a conversation's latest earlier turn came to was a fallback's.
   *
   * <p>Asked for one reason, which is a measurement: a primary model that refuses <em>after</em>
   * reading an answer another model wrote is a different fact from one that refuses a fresh
   * question, and telemetry that could not tell them apart could not say whether a fallback's
   * answers are provoking the refusals that follow them. It is also the signal a future affinity
   * rule would route on; nothing routes on it yet.
   *
   * @param beforeTurn the turn being asked about; only turns before it count
   * @throws ValidationException if the conversation id cannot be named
   */
  public boolean lastAnswerWasAFallback(String conversationId, int beforeTurn) {
    requireInformation(conversationId);
    String conversation = ConversationStore.named(conversationId);
    return ArchiveUnavailableException.translating(
        "read who answered the last turn",
        () ->
            jdbc
                .query(
                    LAST_ANSWER_DISPATCH,
                    (rs, row) -> rs.getString("dispatch"),
                    conversation,
                    beforeTurn)
                .stream()
                .findFirst()
                .map(Invocation.Dispatch.FALLBACK.wireName()::equals)
                .orElse(false));
  }

  /**
   * The result a handle addresses, if this conversation is the one that holds it.
   *
   * <h2>Two rules in one query, and neither is the other</h2>
   *
   * <p><b>The handle is the address</b> and it is unique across the table, so it alone already
   * names at most one row. <b>The conversation is the authorisation</b>, and it is asked because
   * unguessable is not the same as unauthorised: a UUID means a handle for another conversation
   * cannot be <em>constructed</em>, and this clause is the second copy of the same rule for the day
   * one reaches somewhere it should not by a route nobody predicted. Doubled enforcement is this
   * project's pattern, and the two halves are worth naming separately because a reader who thinks
   * the conversation clause is there to tell rows apart will one day remove it as redundant.
   *
   * <h2>A folded result is still redeemable, and that is the point</h2>
   *
   * <p>This does <b>not</b> filter {@code superseded_by IS NULL}. {@link #thatProjectFor} does,
   * because a covered row must not reach a prompt; this must not, because a covered row is exactly
   * what a model reaches for. The whole claim compaction now makes — the summary is a view, the log
   * is complete, and the detail behind a seam is still addressable — <em>is</em> that missing
   * clause.
   *
   * <p><b>An empty answer is not an error and must not be raised as one.</b> A handle nothing was
   * written under, a handle belonging to another conversation, and a handle a model invented are
   * the same fact from this side and are all things a model does; the tool that calls this turns
   * the empty answer into prose the model can correct on its next turn. See {@code AgentTool} for
   * why a caller's mistake is never an exception.
   *
   * @param conversationId the conversation asking. Never widened, and never inferred from the row:
   *     the caller says which conversation it is entitled to read, and a row outside it is not
   *     returned
   * @param handle the address, as the model sent it and this store minted it. A null answers empty
   *     rather than raising — a model that sent no handle is a model that made a mistake
   * @return the entry, or empty if no result in that conversation is addressed by that handle
   * @throws ValidationException if the conversation id cannot be named
   */
  public Optional<EntryRecord> redeem(String conversationId, UUID handle) {
    requireInformation(conversationId);
    String conversation = ConversationStore.named(conversationId);
    if (handle == null) {
      return Optional.empty();
    }
    return ArchiveUnavailableException.translating(
        "redeem a stored tool result",
        () -> jdbc.query(REDEEM, ROW_MAPPER, conversation, handle).stream().findFirst());
  }

  /**
   * The stored tool results a fold has covered, newest first, one page at a time.
   *
   * <h2>Behind a seam and nowhere else</h2>
   *
   * <p>{@code superseded_by IS NOT NULL} is the whole scope, and it is the exact complement of
   * {@link #thatProjectFor}'s. A result a fold has not covered is <em>already</em> in the prompt as
   * its own reference line — the tool, the size and the handle — so listing it would spend the page
   * on addresses the model is holding anyway and crowd out the ones it is not. What a fold takes
   * away is the line and not the row, and this is the read that gives the line back at a bounded
   * cost.
   *
   * <p><b>{@code handle IS NOT NULL} is not tidiness.</b> Results written before {@code
   * V13__entry_handles.sql} have no address, and a listing entry with nothing to redeem is a line
   * that costs context and can return nothing.
   *
   * <h2>Which tool ran is not on the row</h2>
   *
   * <p>A {@code tool_result} carries the id of the call it answers and not the name of what ran, so
   * the name comes from the {@code answer} that declared the call, out of its {@code tool_calls}
   * document. That is the same join {@code Compaction.withResultsReferenced} does in Java over the
   * projected log, and it cannot be done that way here: the log this reads is <em>behind</em> the
   * seam, and reading it into Java would mean fetching every covered row's {@code content} — 34 MB
   * for a 200-turn conversation, measured in {@code implementation rationale} — in order to report
   * a length and throw the text away. So the projection happens in SQL, and {@code length(content)}
   * travels instead of {@code content}.
   *
   * <p><b>A name is left NULL rather than invented</b> when nothing in the log declares that call,
   * which no run this server writes can produce — the assistant message is recorded before the
   * results it asked for — and which a fixture certainly can. The tool that renders it says so; a
   * listing that dropped the row would take away the address as well as the name.
   *
   * @param conversationId the conversation asking, which is the only one it is entitled to read.
   *     Named again in the WHERE for {@link #redeem}'s reason exactly: unguessable is not the same
   *     as unauthorised
   * @param skip how many of the most recent to pass over, from 0
   * @param most how many to return, which the caller has already bounded
   * @return the page and the total, {@link StoredResults#NONE} for a conversation nothing has
   *     folded
   * @throws ValidationException if the conversation id cannot be named
   * @throws IllegalArgumentException if {@code skip} is negative or {@code most} is not positive,
   *     both of which are the caller's bug: a model's bad argument is refused as prose one layer
   *     up, in the tool
   */
  public StoredResults storedResultsBehindASeam(String conversationId, int skip, int most) {
    requireInformation(conversationId);
    String conversation = ConversationStore.named(conversationId);
    if (skip < 0 || most < 1) {
      throw new IllegalArgumentException(
          "a page of stored results starts at 0 or later and holds 1 or more, not "
              + skip
              + " and "
              + most);
    }
    return ArchiveUnavailableException.translating(
        "list a conversation's stored results",
        () -> {
          // The total first, so that a page past the end still comes
          // back with the number the model needs to page back from.
          Integer total = jdbc.queryForObject(COUNT_BEHIND_A_SEAM, Integer.class, conversation);
          if (total == null || total == 0) {
            return StoredResults.NONE;
          }
          return new StoredResults(
              jdbc.query(BEHIND_A_SEAM_NEWEST_FIRST, LISTING_MAPPER, conversation, skip, most),
              total);
        });
  }

  /**
   * The tool results in one conversation whose payload is still here.
   *
   * <h2>What a retention sweep reads before it takes anything away</h2>
   *
   * <p>The whole row, {@code content} included, which every other bounded read in this class goes
   * out of its way not to do — {@code BEHIND_A_SEAM_NEWEST_FIRST} sends {@code length(content)}
   * instead, and {@code PAGE_OF} sends {@code left(content, ?)}, both because pulling a
   * conversation's results into Java to report a number is 34 MB for a 200-turn conversation.
   * <b>Here the bytes are the point</b>: an export writes them out, and there is no way to write a
   * file whose content stays in the database.
   *
   * <p>It is nevertheless the read that most needs an argument about size, and the argument is that
   * it is per conversation and not per sweep: a sweep ejects one conversation at a time and this
   * answers one conversation's results, so the peak is a conversation's worth of tool output rather
   * than a night's. The alternative — a sweep-wide read joined across every conversation it
   * selected — would hold the whole of a retention run in memory to write it out one file at a
   * time.
   *
   * <p><b>{@code ejected_at IS NULL} is what makes a sweep re-runnable.</b> A sweep that was
   * interrupted after writing some files and nulling some rows is resumed by running it again: what
   * is already gone is not selected, and what is still here is exported again over the file that
   * may already be there. Nothing is exported twice into two places and nothing is nulled without
   * having been written.
   *
   * <p><b>Not narrowed to what is behind a seam.</b> {@code storedResultsBehindASeam} is a listing
   * for a model that is still working; this is a sweep on a conversation nobody is working in. The
   * payload of a result whose reference line is still standing is exactly as large and exactly as
   * much of a liability as one a fold has covered, and a sweep that took only the folded ones would
   * leave a short conversation — one that never folded at all, which is what "ask a question, read
   * one 100 KB file, answer, done" is — untouched for ever. That case is the whole reason ejection
   * reaches payloads through tagging rather than through folding.
   *
   * @param conversationId the conversation being swept
   * @return the rows, in conversation order, empty for one that holds none
   * @throws ValidationException if the id cannot be named
   */
  public List<EntryRecord> payloadsHeldBy(String conversationId) {
    requireInformation(conversationId);
    String conversation = ConversationStore.named(conversationId);
    return ArchiveUnavailableException.translating(
        "read a conversation's payloads",
        () ->
            jdbc.query(
                "SELECT "
                    + COLUMNS
                    + " FROM entries WHERE conversation_id = ?"
                    + " AND kind = 'tool_result' AND ejected_at IS NULL"
                    + " ORDER BY ordinal",
                ROW_MAPPER,
                conversation));
  }

  /**
   * Take one payload out of the row, keeping the row.
   *
   * <h2>Demote the record, eject the payload</h2>
   *
   * <p>{@code content} goes and everything else stays: the ordinal, the turn, the handle, the call
   * this answered, when it happened, how long it took, and — through {@code ejected_chars} — how
   * large it was. So the trajectory has no hole in it, no ordinal skips, no {@code superseded_by}
   * dangles, and {@code result_read} on the handle answers with an account of where the bytes went
   * rather than with "there is nothing at that address".
   *
   * <p><b>{@code ejected_chars = length(content)} in the same statement that nulls it.</b> Postgres
   * evaluates every SET expression against the row as it was before the update, so this reads the
   * text it is about to remove. Doing it in two statements would be two chances to keep only one
   * half, and doing it in Java would mean the number came from a read that could be stale.
   *
   * <p><b>{@code ejected_at IS NULL} in the WHERE makes this write-once</b>, on {@link
   * #supersede}'s reasoning: a second sweep over the same row must leave the first ejection's date
   * and export standing rather than restamping them with today's. When a payload went, and where it
   * went, are facts about when it happened.
   *
   * @param conversationId the conversation the handle belongs to. Named again for {@link #redeem}'s
   *     reason: unguessable is not the same as unauthorised, and a sweep is the one caller that
   *     could reach across conversations by accident
   * @param ordinal which entry, by its place in the conversation's log. <b>Not the handle</b>,
   *     although the handle is what a model addresses this row with: a result written before {@code
   *     V13__entry_handles.sql} has none, and those are the oldest payloads on any server that has
   *     been running a while — exactly the ones a retention sweep reaches first. Keying on the
   *     handle would make the rows most in need of ejecting the ones a sweep cannot touch
   * @param at when it went, on the caller's clock
   * @param export where the bytes were written, or {@code null} for a deployment that keeps no
   *     export. <b>Not defaulted to a placeholder</b> — {@code entries_an_export_is_named} refuses
   *     a blank, and a model told an ejected payload is "in " with nothing after it is worse served
   *     than one told there is no export
   * @return whether a payload was there to eject, so a caller can tell "done" from "already done"
   *     rather than counting both as work
   */
  public boolean ejectPayload(String conversationId, int ordinal, Instant at, String export) {
    String conversation = ConversationStore.named(conversationId);
    Objects.requireNonNull(at, "at");
    int rows =
        ArchiveUnavailableException.translating(
            "eject a stored tool result",
            () ->
                jdbc.update(
                    "UPDATE entries SET ejected_chars = length(content), content = NULL,"
                        + " ejected_at = ?, export = ? WHERE conversation_id = ?"
                        + " AND ordinal = ? AND kind = 'tool_result'"
                        + " AND ejected_at IS NULL",
                    utc(at),
                    export,
                    conversation,
                    ordinal));
    return rows == 1;
  }

  /**
   * Everything one conversation has held, in the order it held it.
   *
   * <p><b>Superseded entries are returned too, and so is every kind no model is shown.</b> This is
   * the log and not the projection: what a fold covers is still a fact about the conversation, and
   * {@code Projection} is what decides that a covered entry does not reach a model. A store that
   * filtered them would make the log unreadable behind its own seams, which is the loss {@code
   * CompactionStore} declines to take one table over.
   *
   * <p><b>What a turn's history is read with is {@link #thatProjectFor}</b>, which asks the
   * narrower question and fetches only the rows that answer it. Two methods and not one filtered
   * two ways, because the two are two questions: this one is what happened, that one is what a
   * model is told, and only the second is bounded by folding. Nothing in production reads the whole
   * log today — this method's callers are the tests that assert what a fold did to it, which is
   * behaviour {@link #supersede} would otherwise have no reader for.
   *
   * <p><b>This paragraph used to name resumption as the third caller that would want it back, and
   * resumption landed without wanting it.</b> A resumed run needs the tool results of the run it
   * continues, and {@link #thatProjectFor} already answers with every one of them: a {@code
   * tool_result} carries the {@code tool} role, so it is on the visible side of that predicate, and
   * what keeps results out of an ordinary turn's prompt is {@code
   * Compaction.whatWasSaidAndWhatCameBack} one layer up. What it would additionally have got from
   * this method — superseded rows, and kinds that carry no role — is either dropped by {@code
   * Projection} anyway or is a turn a fold covered, which the turn being continued cannot be: it is
   * the conversation's last, and a fold reaches at most the one before that. So the second reading
   * is a function over the rows this store already answers with, and it lives beside the narrowing
   * it declines to apply.
   *
   * <p>A conversation nobody has spoken into answers with an empty list rather than raising —
   * {@code TurnStore.forConversation}'s choice, for its reason: "this conversation has nothing in
   * it" is a true and ordinary state. Which is exactly why an unusable id is refused instead of
   * answered, since a dropped identifier would otherwise come back as that same empty list.
   *
   * <p><b>In conversation order and not in arrival order</b>, which is the whole of what {@code
   * ORDER BY turn_ordinal, ordinal} is for; see {@link #IN_CONVERSATION_ORDER}.
   *
   * @throws ValidationException if the conversation id cannot be named
   */
  public List<EntryRecord> forConversation(String conversationId) {
    requireInformation(conversationId);
    String conversation = ConversationStore.named(conversationId);
    return ArchiveUnavailableException.translating(
        "read a conversation's entries",
        () ->
            jdbc.query(
                "SELECT "
                    + COLUMNS
                    + " FROM entries WHERE conversation_id = ?"
                    + IN_CONVERSATION_ORDER,
                ROW_MAPPER,
                conversation));
  }

  /**
   * Immutable complete log data for skill handoff; prefix/system blocks live in turns, not entries.
   */
  public io.aeyer.plowshare.server.agents.SkillContextLog snapshotForSkill(String conversationId) {
    requireInformation(conversationId);
    String conversation = ConversationStore.named(conversationId);
    return io.aeyer.plowshare.server.agents.SkillContextLogs.read(
        jdbc.queryForObject(
            "SELECT COALESCE(jsonb_agg(to_jsonb(e)-'text_search' ORDER BY turn_ordinal, ordinal), '[]'::jsonb)::text "
                + "FROM entries e WHERE conversation_id = ?",
            String.class,
            conversation));
  }

  /**
   * The entries of one conversation a model can be shown, in the order it would read them.
   *
   * <p><b>The read behind every turn's history.</b> {@code Compaction} is the caller and {@link
   * io.aeyer.plowshare.server.agents.Projection} is what it hands the answer to; nothing else in
   * this server wants this question.
   *
   * <p><b>The same filter the projection applies, asked of the rows.</b> An entry a fold covered
   * and an entry whose kind carries no role are the two things {@code Projection.of} skips, and
   * they are skipped here so that they are never fetched — the saving is the whole point, and it is
   * unbounded: the projected history is held flat by folding, while the log it was being read out
   * of grows for as long as the conversation runs. {@code THAT_PROJECT} carries the proof that the
   * two predicates are one sentence, and the measurements.
   *
   * <p><b>The projection still applies its own copy</b>, which now costs nothing because it runs
   * over rows this query did not return. It is kept because doubled enforcement is this store's
   * pattern rather than an accident — {@code append} derives the role and {@code
   * entries_role_matches_kind} derives it again — and because {@code Projection.of} is a function
   * of a list and must be right about a list anybody hands it, {@link #forConversation}'s included.
   *
   * <p><b>Not a replacement for {@link #forConversation}</b>, which is still the log: what a fold
   * covered is still a fact about the conversation, a {@code diagnostic} is the only record that
   * the machinery acted on this conversation at a particular moment, and an {@code attempt_failed}
   * is how a stopped run is legible at all. Those are read by everything that asks what happened
   * rather than what a model was told.
   *
   * <p><b>This is also what a resumed run reads</b>, and it is the same rows asked the same
   * question: {@code Compaction.whatWasSaidAndWhatTheRunLearned} takes what this answers with and
   * applies the ordinary narrowing to everything older than the turn being continued, leaving that
   * turn whole. The tool results it needs are already here — a {@code tool_result} carries the
   * {@code tool} role — so no third read was wanted after all; {@link #forConversation} says the
   * same thing from the other side.
   *
   * <p>A conversation nobody has spoken into, and one whose every entry a fold has covered, both
   * answer with an empty list; {@link #forConversation} says why that is not confused with a
   * missing conversation.
   *
   * @throws ValidationException if the conversation id cannot be named
   */
  public List<EntryRecord> thatProjectFor(String conversationId) {
    requireInformation(conversationId);
    String conversation = ConversationStore.named(conversationId);
    return ArchiveUnavailableException.translating(
        "read what a conversation projects",
        () ->
            jdbc.query(
                "SELECT "
                    + COLUMNS
                    + " FROM entries WHERE conversation_id = ?"
                    + THAT_PROJECT
                    + IN_CONVERSATION_ORDER,
                ROW_MAPPER,
                conversation));
  }

  /**
   * The entries one turn of this conversation was shown, in the order it read them: the history it
   * opened with and its own utterance, and nothing it went on to produce.
   *
   * <p><b>{@link #thatProjectFor} answers what a model can be shown now; this answers what a model
   * was shown then</b>, and the two are different questions rather than one question with a bound
   * on it. That is why this is a second method and a second predicate instead of a parameter on the
   * first: the live path asks the first question on every turn of every conversation and would
   * otherwise carry an argument it always passes the same value for.
   *
   * <h2>Two traps, and one number that avoids both</h2>
   *
   * <p><b>{@code superseded_by} is not a fact about turn N</b>, and {@code turn_ordinal} is not a
   * clock. A fold records itself on the rows it covered and notes nowhere when it ran, so replaying
   * {@code THAT_PROJECT} with {@code turn_ordinal <= N} hides rows turn N was plainly shown — and a
   * summary's {@code turn_ordinal} is its <em>reach</em>, set to the turn before the one whose
   * ending wrote it, so comparing against that applies a fold to the two turns that could not have
   * seen it. Both produce the same failure: a correctly assembled history of a moment that did not
   * happen, on the screen whose whole purpose is auditing what did.
   *
   * <p><b>What both are replaced by is one ordinal.</b> {@code WHEN_A_TURN_WAS_ASSEMBLED} answers
   * with the turn's first entry — its utterance, which {@code JobRuntime} records before the first
   * model call — and that is the moment the turn's prompt existed. A row was in that prompt when it
   * was written by then ({@code ordinal <= P}) and its fold was not ({@code superseded_by > P}).
   * {@link #THAT_PROJECTED_AT} carries the whole argument, the constraints that make {@code
   * superseded_by} readable as a moment, and the cost.
   *
   * <p><b>The turn's own answer is not here, and that is the bound doing its job.</b> An answer and
   * its tool results carry ordinals after the utterance, and they are what the turn came to rather
   * than what was in front of the model when it started. {@link #thatProjectFor} does return them,
   * which is right for the next prompt and wrong for this question.
   *
   * <p><b>Roleless kinds are still never shown</b>, at this turn or any other: that half of the
   * predicate is {@code THAT_PROJECT}'s, word for word, and carries {@code THAT_PROJECT}'s proof
   * that it is {@code EntryKind.projects()} asked of the rows.
   *
   * <h2>The rows come back superseded, and the renderer has to be told</h2>
   *
   * <p><b>What this answers with is not a set of rows with {@code superseded_by IS NULL}</b>, and
   * could not be: returning the rows a later fold covered is the entire purpose of the read. So
   * every caller gets rows carrying a non-null {@code superseded_by} that are nonetheless part of
   * the answer, and a caller that re-checks that column drops exactly the rows this predicate went
   * to the trouble of finding — which is a turn answered with an empty history and a screen
   * asserting that is what it was shown.
   *
   * <p>{@code Projection.Superseded} is where that contract is made explicit: {@code
   * Compaction.projectionAsOf} passes {@code WAS_WEIGHED_BY_THE_READ} with these rows, because the
   * weighing happened here, in SQL, against the one number that can do it. The hot path passes the
   * other value and reads the column, which is right for it — {@code thatProjectFor} filters on
   * {@code superseded_by IS NULL} and its rows carry none.
   *
   * <h2>What it is still not</h2>
   *
   * <p>These are the rows, and the rows are not the request. What a turn was <em>sent</em> also
   * depended on the agent's definition at that moment, which nothing in this table records — {@code
   * Compaction.projectionAsOf} and {@code ProjectionView} both say so from their own side. This
   * answers the half the log really does hold.
   *
   * @param conversationId the conversation to read
   * @param turn the turn to read it as of, from 1. The caller has already established that this
   *     turn happened; a turn a conversation never reached is refused as prose in the route,
   *     because an empty answer here would read as "that turn was shown nothing"
   * @return the entries, which are never none: a turn has an utterance, and a turn whose entire
   *     history a fold had already covered still answers with the summary standing in its place.
   *     <b>Rows a later fold covered are in it and carry their {@code superseded_by}</b> — see
   *     above; they are the answer and not leftovers to be filtered again
   * @throws ValidationException if the conversation id cannot be named
   * @throws IllegalArgumentException if {@code turn} is below 1, which is the caller's bug rather
   *     than a turn with no rows: {@code entries_belong_to_a_turn_numbered_from_one} is where the
   *     floor comes from, and {@link #page} refuses its own bounds the same way
   * @throws IllegalStateException if no entry belongs to that turn. The caller established the turn
   *     from {@code turns} and the log disagrees, which is the archive contradicting itself rather
   *     than anybody's mistake — and the alternative is the empty list, which on this read would
   *     say the turn was shown nothing
   */
  public List<EntryRecord> thatProjectedAt(String conversationId, int turn) {
    requireInformation(conversationId);
    String conversation = ConversationStore.named(conversationId);
    if (turn < 1) {
      throw new IllegalArgumentException(
          "a conversation's turns are numbered from 1, so there is no projection as of"
              + " turn "
              + turn);
    }
    return ArchiveUnavailableException.translating(
        "read what a conversation projected at a turn",
        () -> {
          Integer assembled =
              jdbc.queryForObject(WHEN_A_TURN_WAS_ASSEMBLED, Integer.class, conversation, turn);
          if (assembled == null) {
            throw new IllegalStateException(
                "conversation "
                    + conversation
                    + " holds no entry belonging to"
                    + " turn "
                    + turn
                    + ", so there is no moment its prompt"
                    + " was assembled at and no history to answer with. The"
                    + " turns table and the log disagree about which turns"
                    + " this conversation had.");
          }
          return jdbc.query(THAT_PROJECTED_AT, ROW_MAPPER, conversation, assembled, assembled);
        });
  }

  /**
   * One page of everything a conversation has held, in the order it held it.
   *
   * <p><b>{@link #forConversation}, bounded, and the bound is why it exists separately.</b> That
   * method is the log and has no limit on it, which was right while its only callers were tests
   * asserting what a fold did; a trajectory read by a person or by a foreign harness is a listing,
   * and a listing that answers with everything is a listing that empties a context window. Rows are
   * bounded by {@code most}; tool results, diagnostics and tool arguments by {@link
   * #MOST_CHARACTERS_PER_ENTRY}. Transcript text is returned whole so readers can display complete
   * messages.
   *
   * <p><b>Superseded entries are returned, and so is every kind no model is shown.</b> That is the
   * whole of what this reading is for — what a fold covered, a {@code diagnostic}, an {@code
   * attempt_failed} and the timings are the things a projection has no room for and a reader asking
   * what happened came for. {@link #pageOfProjection} is the other question.
   *
   * @param conversationId the conversation to read
   * @param skip how many entries to pass over, from 0
   * @param most how many to return, which the caller has already bounded
   * @return the page, the total, and how far the whole log reaches. An empty page is not {@link
   *     EntryPage#NONE} in general: {@code through} is read whatever the page holds, so a reading
   *     that holds nothing of a log that has something — every row past {@code after}, or none that
   *     projects — still says how far the log goes. Only a conversation nothing has been said in
   *     answers with a page equal to {@code NONE}
   * @throws ValidationException if the conversation id cannot be named — a dropped identifier must
   *     not read as a conversation nobody has spoken into
   * @throws IllegalArgumentException if {@code skip} is negative or {@code most} is not positive,
   *     both of which are the caller's bug: a model's bad argument is refused as prose one layer
   *     up, in the tool
   */
  /** Exact identity read for search evidence, independent of arrival/display ordering. */
  public EntryPage entryAt(String conversationId, int ordinal) {
    requireInformation(conversationId);
    if (ordinal < 1) throw new ValidationException("An entry ordinal starts at 1");
    return page(
        conversationId,
        " AND ordinal = ?",
        ordinal,
        "",
        EVERY_KIND,
        IN_CONVERSATION_ORDER,
        false,
        0,
        1,
        "read a retained evidence entry",
        null);
  }

  public EntryPage pageOfLog(String conversationId, int skip, int most) {
    requireInformation(conversationId);
    return pageOfLogAfter(conversationId, 0, EVERY_KIND, false, skip, most);
  }

  /**
   * One page of what was written to a conversation's log after an ordinal, in conversation order —
   * what a client that has shown the log through {@code after} has not seen.
   *
   * <p><b>An ordinal and not an offset</b>: the log is read in {@code (turn_ordinal, ordinal)}
   * order, so a summary written late sorts back among the turns it covers, and "the first {@code
   * after} rows" would not be the rows numbered up to {@code after}.
   *
   * <p><b>{@code kinds} narrows the rows and the count alike</b>, so a reader that draws three
   * kinds is sent those three and told how many of them there are — and never the tool results and
   * hooks between them it would only have hidden. {@code through} is still the whole log's: a
   * reader that has drawn this page has seen everything up to it that it would ever draw.
   *
   * @param after the highest ordinal already read; 0 reads everything
   * @param kinds the kinds to read, {@link #EVERY_KIND} for all of them
   * @param drawn true to leave out every answer that asked for tools, from the rows and the count —
   *     the answers a chat never draws
   * @return the page; {@code total} counts the entries of {@code kinds} after {@code after}, {@code
   *     through} is the whole log's highest ordinal
   * @throws IllegalArgumentException if {@code after} is negative or {@code kinds} is empty, which
   *     reads nothing and is the caller's bug
   * @see #pageOfLog for the remaining parameters, the refusals and the bounds
   */
  public EntryPage pageOfLogAfter(
      String conversationId, int after, Set<EntryKind> kinds, boolean drawn, int skip, int most) {
    requireInformation(conversationId);
    return pageOfLogAfter(conversationId, after, kinds, drawn, skip, most, null);
  }

  /**
   * {@link #pageOfLogAfter}, read as of a {@code through} already read — or as of now, for null.
   * The seam a test lands a row in: between the moment the log's reach is read and the moment the
   * page is.
   */
  EntryPage pageOfLogAfter(
      String conversationId,
      int after,
      Set<EntryKind> kinds,
      boolean drawn,
      int skip,
      int most,
      Integer asOf) {
    if (after < 0) {
      throw new IllegalArgumentException(
          "a page of entries reads after ordinal 0 or later, not " + after);
    }
    return page(
        conversationId,
        AFTER,
        after,
        drawn ? DRAWN_ANSWERS_ONLY : "",
        kinds,
        IN_CONVERSATION_ORDER,
        false,
        skip,
        most,
        "read what was added to a conversation's log",
        asOf);
  }

  /**
   * One page of a conversation's log read backwards from an ordinal, <b>newest first</b> — the tail
   * a reader shows on opening, and each "earlier" after it.
   *
   * <p><b>Why backwards and not an offset from a total.</b> Reading the last forty forwards means
   * knowing where the log ends in rows of the kinds wanted — a count, and then an offset that is
   * stale the moment a row lands between the two. Reading below an ordinal is one walk of the
   * primary key from the end, stopped by {@code LIMIT}, and names its own next step: the page's
   * {@link EntryPage#oldest()} is the next reading's {@code before}.
   *
   * <p>The rows come back newest first by {@code ordinal} and the reader turns them round; {@link
   * EntryPage#more()} says whether any entry of {@code kinds} lies before the oldest of them. A
   * fold's summary is an ordinary row of kind {@code summary} and is read here like any other,
   * where it was written.
   *
   * @param before read only entries with a smaller ordinal; {@link #FROM_THE_END} for the tail
   * @param kinds the kinds to read and count, {@link #EVERY_KIND} for all
   * @param drawn true to leave out every answer that asked for tools, from the rows, the count and
   *     so from {@link EntryPage#more()} — never from {@code through}
   * @return the page, newest first; {@code total} counts the entries of {@code kinds} before {@code
   *     before}, {@code through} is the whole log's
   * @throws IllegalArgumentException if {@code before} is less than 1 or {@code kinds} is empty
   * @see #pageOfLog for the remaining parameters, the refusals and the bounds
   */
  public EntryPage pageOfLogBefore(
      String conversationId, int before, Set<EntryKind> kinds, boolean drawn, int skip, int most) {
    requireInformation(conversationId);
    return pageOfLogBefore(conversationId, before, kinds, drawn, skip, most, null);
  }

  /**
   * {@link #pageOfLogBefore}, as of a {@code through} already read; see {@link
   * #pageOfLogAfter(String, int, Set, boolean, int, int, Integer)}.
   */
  EntryPage pageOfLogBefore(
      String conversationId,
      int before,
      Set<EntryKind> kinds,
      boolean drawn,
      int skip,
      int most,
      Integer asOf) {
    if (before < 1) {
      throw new IllegalArgumentException(
          "a backwards page of entries reads before ordinal 1 or later, not " + before);
    }
    return page(
        conversationId,
        BEFORE,
        before,
        drawn ? DRAWN_ANSWERS_ONLY : "",
        kinds,
        NEWEST_FIRST,
        true,
        skip,
        most,
        "read back through a conversation's log",
        asOf);
  }

  /**
   * One page of the entries a model can be shown, in the order it would read them.
   *
   * <p><b>{@link #thatProjectFor}, bounded, and the same filter asked of the same rows.</b> {@code
   * THAT_PROJECT} is what makes the two agree, and it is applied to the count as well as to the
   * page, so a caller is never told there are more of something than it can page to.
   *
   * <p>This is the reading a client renders as the conversation: superseded rows gone, roleless
   * kinds gone, in the order the model reads them. It is <em>not</em> what a request contains —
   * {@code Compaction} substitutes a reference line for an older turn's tool result one layer up,
   * and this page shows the result — which is a difference worth knowing rather than one worth
   * hiding, since the entry a reference stands for is exactly what a person wanting to check the
   * reference is after.
   *
   * @see #pageOfLog for the parameters, the refusals and the bounds, which are the same in both and
   *     are argued there
   */
  public EntryPage pageOfProjection(String conversationId, int skip, int most) {
    requireInformation(conversationId);
    return page(
        conversationId,
        AFTER,
        0,
        THAT_PROJECT,
        EVERY_KIND,
        IN_CONVERSATION_ORDER,
        false,
        skip,
        most,
        "read a page of what a conversation projects",
        null);
  }

  /**
   * The conversation's highest entry ordinal, or 0 for a conversation nothing was said in.
   *
   * <p>{@code foldedThrough}'s call shape exactly, asked of {@code ordinal} rather than of the
   * standing fold: this is what a page's own {@code through} answers with, and what {@code
   * conversation.appended} pushes after a turn commits.
   *
   * @throws ValidationException if the conversation id cannot be named
   */
  public int through(String conversationId) {
    requireInformation(conversationId);
    String conversation = ConversationStore.named(conversationId);
    Integer through =
        ArchiveUnavailableException.translating(
            "read how far a conversation's log reaches",
            () -> jdbc.queryForObject(THROUGH, Integer.class, conversation));
    return through == null ? 0 : through;
  }

  /**
   * The two pages, which differ in one clause and in nothing else.
   *
   * <p>One body rather than two, because the alternative is two copies of an ordering, a bound
   * check, a count and a windowed read whose only job is to agree with each other. The clause is
   * the parameter and it is a constant of this class either way — never anything a caller composes
   * — so nothing reaches the SQL that was not written in this file.
   *
   * <p><b>{@code through} is read even when the page it describes is empty.</b> A page after the
   * log's own reach ({@code after == through}) has nothing to show and still owes a caller how far
   * the log goes — that is the one case an empty {@code total} does not already mean "conversation
   * nothing has been said in", so {@code EntryPage.NONE} is wrong for it and this reads {@code
   * through} unconditionally rather than only on the non-empty path.
   *
   * <p><b>And it bounds the reading.</b> The reach, the count and the page are three statements,
   * and a turn can commit between any two of them. Unbounded, a row landing after the count is on
   * the page and not in the total — one more row than the reading holds, which {@link EntryPage}
   * refuses, so the read failed — and {@code more} is reckoned from a total the page is not of. So
   * both are asked of {@code ordinal <= through}, the reach read first: the page is the log as it
   * was then, and what landed since is the next reading's, which {@code through} tells a caller to
   * make.
   *
   * @param asOf the reach to read as of, or null to read it now — the first statement, which a test
   *     replaces to land a row after it
   */
  private EntryPage page(
      String conversationId,
      String side,
      int ordinal,
      String narrowing,
      Set<EntryKind> kinds,
      String order,
      boolean backwards,
      int skip,
      int most,
      String doing,
      Integer asOf) {
    String conversation = ConversationStore.named(conversationId);
    if (skip < 0 || most < 1 || kinds.isEmpty()) {
      throw new IllegalArgumentException(
          "a page of entries starts at 0 or later, holds 1 or more and reads at least"
              + " one kind, not "
              + skip
              + ", "
              + most
              + " and "
              + kinds);
    }
    // Placeholders only, one per kind, so the text is still this file's own and every
    // value is bound. Enum order, so one set of kinds is always one statement.
    List<String> named =
        kinds.containsAll(EVERY_KIND)
            ? List.of()
            : kinds.stream().sorted().map(EntryKind::wireName).toList();
    String where =
        side
            + REACHED
            + narrowing
            + (named.isEmpty()
                ? ""
                : " AND kind IN ("
                    + String.join(", ", Collections.nCopies(named.size(), "?"))
                    + ")");
    return ArchiveUnavailableException.translating(
        doing,
        () -> {
          Integer reach =
              asOf != null ? asOf : jdbc.queryForObject(THROUGH, Integer.class, conversation);
          int through = reach == null ? 0 : reach;
          List<Object> bound = new ArrayList<>();
          bound.add(conversation);
          bound.add(ordinal);
          bound.add(through);
          bound.addAll(named);
          // The total first, so that a page past the end still comes back with
          // the number a caller needs to page back from.
          Integer total =
              jdbc.queryForObject(COUNT_OF.formatted(where), Integer.class, bound.toArray());
          if (total == null || total == 0) {
            return new EntryPage(List.of(), 0, through, backwards ? false : null);
          }
          List<Object> paged = new ArrayList<>();
          paged.add(MOST_CHARACTERS_PER_ENTRY);
          paged.add(MOST_CHARACTERS_PER_ENTRY);
          paged.addAll(bound);
          paged.add(skip);
          paged.add(most);
          List<EntryPage.Row> rows =
              jdbc.query(
                  PAGE_OF.formatted(where) + order + " OFFSET ? LIMIT ?",
                  PAGE_MAPPER,
                  paged.toArray());
          // Newest first and unique by ordinal, so everything this reading holds past the
          // rows skipped and the rows shown is older than all of them: no second query.
          return new EntryPage(rows, total, through, backwards ? total > skip + rows.size() : null);
        });
  }

  /**
   * The entries in one tier whose words a question named, best cover first, beside what the search
   * could not look at.
   *
   * <h2>What this answers that no other read can</h2>
   *
   * <p>{@link #forConversation} and {@link #pageOfLog} both begin by naming a conversation, so
   * every question this log could be asked until now was a question about a conversation somebody
   * had already identified. <b>"Where was this said" had no answer at all</b> short of reading
   * every conversation back — {@code entries.content} carried no index of any kind before {@code
   * V23}, which is what {@code implementation rationale} §3.3 records.
   *
   * <h2>A tier, and why not a conversation</h2>
   *
   * <p>The scope is one {@link Home} — the same tier {@code ConversationStore.inHome} lists and
   * {@code conversation_list} shows — and that is a measurement rather than a preference. Against
   * 200 000 entries in 501 conversations, a search of a tier is a bitmap scan on {@code
   * entries_by_text}; the same search narrowed to one conversation is served by the primary key
   * alone, because one conversation is a few hundred rows in sixteen heap blocks and there is
   * nothing left for a GIN scan to improve on. So a per-conversation search would be a read that
   * never touched the index built for it, answering a question {@link #pageOfLog} already answers
   * by paging. {@code V23} records the same numbers where the index is created.
   *
   * <p>It stays inside the tier because every other read of this archive does. A search that
   * crossed the boundary would be the one place a project's conversations and the global tier's
   * were mixed, and mixing them in a <em>search</em> is where it would be least visible.
   *
   * <h2>Four kinds and not eight</h2>
   *
   * <p>What is searched is what was said: {@code utterance}, {@code answer}, {@code tool_result}
   * and {@code summary} — the kinds that carry a role. {@code attempt_failed}, {@code
   * runtime_note}, {@code plan} and {@code diagnostic} are the harness's record <em>about</em> a
   * run rather than anything said in it, and they are counted as {@link
   * LogSearch.Reach#recordedOnly} rather than dropped in silence. <b>A fold is not a filter</b>: a
   * superseded entry is still a hit and says which summary covered it.
   *
   * <h2>What it says about an ejected payload</h2>
   *
   * <p>Nothing can be a hit that has no text, and V19's whole point is that an ejected payload has
   * none. So it is counted — {@link LogSearch.Reach#ejected} — in the same statement as everything
   * else, and the surfaces above render that count as prose. This is {@code result_list}'s register
   * rather than {@code result_read}'s: a listing shows an ejected result <em>as ejected</em>
   * instead of omitting it, and a search cannot show the row at all without inventing a match, so
   * it shows the number.
   *
   * <h2>Why no running agent is given this</h2>
   *
   * <p>It reaches a person and a foreign harness — {@code GET /v1/entries/search} and {@code
   * conversation_search} — and <b>deliberately not a model mid-run</b>, although the case for it is
   * real: an agent whose history has been folded could search back for what it said a hundred turns
   * ago, which is more than {@code result_list} gives it.
   *
   * <p>The reason it is declined here rather than granted is measured and is not about tidiness. A
   * new agent tool is a new schema and a new description in every request that agent makes, and
   * {@code implementation rationale} records redemption flipping 5/5 → 0/5 on a two-paragraph
   * change to model-facing text with <b>1 892 tests passing either way</b>. Nothing in this suite
   * can tell whether adding a fifth tool to {@code interlocutor}'s surface makes it stop calling
   * {@code file_grep}; only a live run can. So the surfaces that go to a reader ship now, the grant
   * waits for the run that would validate it, and when it comes it is additive — this method is
   * already the whole of what such a tool would call.
   *
   * @param home which tier to search. {@link io.aeyer.plowshare.protocol.Home#global()} is
   *     ordinary; a project nothing has ever been said in is an ordinary question with an empty
   *     answer, and never the global tier's rows under another name — see {@link ProjectIds}
   * @param question the question in prose, parsed into a {@code tsquery} by Postgres. A question
   *     that parses to nothing — only stopwords, or only a negation — matches nothing, which is the
   *     answer and not a failure
   * @param skip how many hits to pass over, from 0
   * @param most how many to return, which the caller has already bounded
   * @return the page, how many matched in all, and what was out of reach. {@link LogSearch#NONE}
   *     for a tier holding nothing
   * @throws IllegalArgumentException if {@code skip} is negative or {@code most} is not positive,
   *     both of which are the caller's bug — a model's bad argument is refused as prose one layer
   *     up, in the tool
   */
  public LogSearch search(Home home, String question, int skip, int most) {
    Objects.requireNonNull(home, "home");
    Objects.requireNonNull(question, "question");
    if (skip < 0 || most < 1) {
      throw new IllegalArgumentException(
          "a page of hits starts at 0 or later and holds 1 or more, not " + skip + " and " + most);
    }
    return ArchiveUnavailableException.translating(
        "search a tier's conversation log",
        () -> {
          Long project = ProjectIds.toRead(jdbc, home);
          Counted counted = informationQuery(REACH_SQL, COUNTED, question, project);
          if (counted == null || counted.reach.total() == 0) {
            return LogSearch.NONE;
          }
          if (counted.matched == 0) {
            // The reach still travels: "nothing matched" and "there was
            // nothing here to match against" are different conclusions, and
            // the caller is the one that has to tell a reader which.
            return new LogSearch(List.of(), 0, counted.reach);
          }
          return new LogSearch(
              informationRows(
                  SEARCH_SQL,
                  HIT_MAPPER,
                  MOST_CHARACTERS_PER_SNIPPET,
                  question,
                  project,
                  skip,
                  most),
              counted.matched,
              counted.reach);
        });
  }

  /**
   * The four numbers {@code REACH_SQL} answers with, kept together because they came out of one
   * statement and describe one instant.
   */
  private record Counted(LogSearch.Reach reach, int matched) {}

  private static final ResultSetExtractor<Counted> COUNTED =
      rs ->
          rs.next()
              ? new Counted(
                  new LogSearch.Reach(
                      rs.getInt("searched"), rs.getInt("ejected"), rs.getInt("recorded_only")),
                  rs.getInt("matched"))
              : null;

  /*
   * `snippet` is left(ts_headline(...), ?) and `length` is length(content),
   * which is a different pair from PAGE_MAPPER's and is why LogSearch.Hit is
   * not EntryPage.Row: an excerpt is the opening of an entry and a snippet is
   * the middle of one, so `length > snippet.length()` says how much of the
   * entry is elsewhere rather than how much of it comes after.
   *
   * `content IS NOT NULL` holds for every row this mapper sees -- an ejected
   * payload carries no lexemes and cannot match -- so `snippet` is never NULL
   * and Hit's requireNonNull is a claim about the SQL rather than a guard on a
   * caller.
   */
  private static final RowMapper<LogSearch.Hit> HIT_MAPPER =
      (rs, rowNum) ->
          new LogSearch.Hit(
              rs.getString("conversation_id"),
              rs.getInt("ordinal"),
              rs.getInt("turn_ordinal"),
              EntryKind.of(rs.getString("kind")),
              rs.getDouble("rank"),
              rs.getString("snippet"),
              rs.getInt("length"),
              rs.getObject("superseded_by", Integer.class),
              rs.getObject("handle", UUID.class),
              instant(rs, "recorded_at"),
              rs.getString("source_revision"));

  /**
   * Mark everything a fold covers as covered by it.
   *
   * <p>Everything belonging to a turn after {@code sinceTurnOrdinal} and not after {@code
   * throughTurnOrdinal} that is not already covered, and nothing at or after the summary itself.
   * The summary entry carries the reach as its own {@code turn_ordinal} — it stands for those turns
   * — so without the ordinal bound it would fold itself away the moment it was written.
   *
   * <p><b>A fold covers the span since the last fold and not the whole history again</b>, which is
   * what {@code sinceTurnOrdinal} buys and it buys two things. The previous summary carries {@code
   * sinceTurnOrdinal} as its own turn ordinal, so it falls outside {@code (since, through]} and
   * <b>stays live</b>: the model reads summary₁ then summary₂ then the recent turns, each span of
   * raw turns having been summarised exactly once instead of being carried through successive lossy
   * passes. And the prompt prefix {@code system + summary₁} is then byte-identical across the fold,
   * which is the only shape the endpoint credits — it reuses work on a prompt that <em>extends</em>
   * one it has and gives no partial credit for a shared prefix otherwise. {@code Compaction}
   * measures that at about 30x and cites where.
   *
   * <p><b>It never lowers the reach and never uncovers anything.</b> There is no argument that
   * would take an entry out of a fold: the fold was paid for, the model has been shown the summary,
   * and the turns it covers are still there to read.
   *
   * @param conversationId the conversation that folded
   * @param sinceTurnOrdinal the last turn the <em>previous</em> standing fold reaches, exclusive —
   *     {@link #foldedThrough} is where it comes from, and 0 for a conversation whose log no fold
   *     has touched, which covers turns 1 to {@code throughTurnOrdinal} exactly as an unbounded
   *     fold did
   * @param throughTurnOrdinal the last turn the summary stands for
   * @param summaryOrdinal the ordinal of the summary entry itself, as {@link #append} returned it
   * @return how many entries this fold newly covered. Zero is legitimate — a conversation that
   *     folded before it recorded any entries at all — and is not an error
   * @throws ValidationException if the conversation id cannot be named
   */
  public int supersede(
      String conversationId, int sinceTurnOrdinal, int throughTurnOrdinal, int summaryOrdinal) {
    String conversation = ConversationStore.named(conversationId);
    return ArchiveUnavailableException.translating(
        "fold a conversation's entries away",
        () ->
            jdbc.update(
                SUPERSEDE,
                summaryOrdinal,
                conversation,
                sinceTurnOrdinal,
                throughTurnOrdinal,
                summaryOrdinal));
  }

  /**
   * A fold, written down: the summary appended and the span it stands for pointed at it, as one
   * write that either lands whole or does not land.
   *
   * <h2>Why this is a method here and not two calls in {@code Compaction}</h2>
   *
   * <p>It was two calls, and the argument for leaving them two was an <em>ordering</em> argument:
   * append first, so that a failure between them leaves a summary nothing points at — an entry that
   * projects in front of a history that also projects, which is redundant and readable — rather
   * than entries pointing at a summary that does not exist, which {@code
   * entries_are_superseded_by_an_entry} refuses outright. The ordering is still the right way round
   * and it is kept below. What was wrong was the claim that the state it leaves is one the next
   * turn walks out of.
   *
   * <p><b>It is not. It is terminal.</b> {@link #foldedThrough} answers from the newest
   * <em>standing</em> summary, and the orphan is one — so the log reports itself folded through
   * {@code throughTurnOrdinal} while every raw turn from one to there still projects. The next fold
   * reads that reach as its lower bound, finds the projection opening at turn one rather than at
   * {@code since + 1}, and refuses the span rather than summarising it under the wrong turn
   * numbers: {@code Compaction.theNumbersDisagree} is right to refuse and the refusal never stops,
   * because nothing in either record moves the two numbers back into agreement. One WARN a turn, a
   * prompt that grows every turn, and a conversation that folds again only if somebody edits the
   * table. {@code Compaction.foldIfItWouldNotFit}'s {@code already .throughOrdinal() >= through}
   * guard reads {@code compactions} and not this log, so it does not catch it either.
   *
   * <p><b>So the window is closed rather than survived.</b> The rejected alternative was to teach
   * {@code Compaction.theSpan} to recognise "the log is ahead of its own supersede" and repair by
   * covering the uncovered range: that is a second, subtler writer of {@code superseded_by} whose
   * whole job is to clean up after a partial write this seam can simply refuse to make, and it
   * would leave the orphan summary in the log either way. A boundary is the smaller thing and it is
   * the one this codebase already has.
   *
   * <p><b>A failure here is still not a failed fold.</b> The caller logs and swallows exactly as it
   * did; what changes is what it is swallowing. Nothing is written, {@link #foldedThrough} still
   * answers with the previous reach, and the next fold takes the wider span — which is the
   * self-repair the old javadoc claimed and only now has.
   *
   * @param conversationId the conversation that folded
   * @param sinceTurnOrdinal the last turn the previous standing fold reaches, exclusive — {@link
   *     #supersede} carries what it is for
   * @param throughTurnOrdinal the last turn this summary stands for, which is also the turn ordinal
   *     the summary entry is appended at
   * @param summary the summary entry itself
   * @return the summary as it was written, carrying the ordinal it was given
   * @throws ValidationException if the conversation id cannot be named
   */
  public EntryRecord fold(
      String conversationId, int sinceTurnOrdinal, int throughTurnOrdinal, LoggedEntry summary) {
    return transactions.inTransaction(
        () -> {
          // Appended before it is pointed at, and that has not changed: the
          // other order writes `superseded_by` values for a row that does not
          // exist yet, which the foreign key refuses inside the transaction
          // just as it refuses outside one.
          EntryRecord written = append(conversationId, throughTurnOrdinal, summary);
          supersede(conversationId, sinceTurnOrdinal, throughTurnOrdinal, written.ordinal());
          return written;
        });
  }

  /**
   * A fold inside a turn, written down: the in-turn summary appended at the turn in progress and
   * everything it stands for pointed at it, as one write that lands whole or not at all — {@link
   * #fold}'s boundary and its reason (spec 2026-09-30-fold-at-60-and-80 §2: a mid-turn fold is in
   * the log like a between-turn one, the full entries stay, and a projection rebuilt from the log
   * reproduces it).
   *
   * <p><b>What it covers</b>: every row of a turn after {@code sinceTurnOrdinal} and before {@code
   * turnOrdinal}, and the rows of {@code turnOrdinal} from {@code fromOrdinal} to {@code
   * throughOrdinal} inclusive — the older steps, from the first folded step's answer to the last
   * folded step's last result — less an earlier in-turn summary, which stands. See {@code
   * SUPERSEDE_WITHIN_A_TURN}. A range with {@code fromOrdinal > throughOrdinal} folds no step,
   * which is a fold that took only earlier turns.
   *
   * <p><b>How far the log is folded afterwards</b> is the turn before {@code turnOrdinal}: {@link
   * #foldedThrough} reads a standing in-turn summary that way.
   *
   * @param conversationId the conversation folding
   * @param sinceTurnOrdinal how far the log was already folded, as {@link #foldedThrough} answered
   *     before this fold
   * @param turnOrdinal the turn in progress, which the summary is appended at
   * @param fromOrdinal the first row of the turn's folded steps
   * @param throughOrdinal the last row of the turn's folded steps
   * @param summary the in-turn summary entry itself
   * @return the summary as it was written, carrying the ordinal it was given
   * @throws ValidationException if the conversation id cannot be named
   */
  public EntryRecord foldWithinATurn(
      String conversationId,
      int sinceTurnOrdinal,
      int turnOrdinal,
      int fromOrdinal,
      int throughOrdinal,
      LoggedEntry summary) {
    String conversation = ConversationStore.named(conversationId);
    return transactions.inTransaction(
        () -> {
          // Appended before it is pointed at, for fold's reason: the foreign key refuses the
          // other order.
          EntryRecord written = append(conversationId, turnOrdinal, summary);
          ArchiveUnavailableException.translating(
              "fold a turn's older steps away",
              () ->
                  jdbc.update(
                      SUPERSEDE_WITHIN_A_TURN,
                      written.ordinal(),
                      conversation,
                      written.ordinal(),
                      sinceTurnOrdinal,
                      turnOrdinal,
                      turnOrdinal,
                      fromOrdinal,
                      throughOrdinal));
          return written;
        });
  }

  /**
   * How far this conversation's log has actually been folded: the last turn the newest standing
   * summary reaches, or zero for a log no fold has touched.
   *
   * <p><b>The lower bound of the next fold, and it is read from the log rather than from {@code
   * compactions} on purpose.</b> The two can disagree, and when they do this is the one that is
   * right. {@code Compaction.foldTheLog} logs and swallows, so a database that refused the summary
   * entry leaves a {@code compactions} row claiming a reach the log never got; a next fold that
   * trusted that row would start above a span nothing covers and leave those turns projecting for
   * ever, behind a seam that says they were folded. Asking the log instead makes the failed fold's
   * ground part of the next one, which is the repair {@code Compaction} already documents and
   * {@code CompactionTest.a_fold_the_log_could_not_be_given_is_taken_again_when_the_ reach_moves}
   * already runs.
   *
   * <p><b>Standing and not merely newest.</b> Before folds became incremental each summary
   * superseded the one before it, so a log written by an older build has exactly one summary that
   * is not itself covered — and that one is the reach. {@code superseded_by IS NULL} is what makes
   * this answer the same question on both shapes of history.
   *
   * <p><b>A standing in-turn summary counts, one turn short of its own.</b> A fold inside turn N
   * takes every earlier turn along with N's older steps, so it stands for the turns before N; see
   * {@code FOLDED_THROUGH}.
   *
   * <p>Zero and not empty. A conversation with no fold has been folded through turn zero, which is
   * a number the caller can use rather than a case it has to branch on, and {@code
   * entries_belong_to_a_turn_numbered_from_one} makes zero unambiguous: no entry can carry it.
   *
   * @throws ValidationException if the conversation id cannot be named
   */
  public int foldedThrough(String conversationId) {
    String conversation = ConversationStore.named(conversationId);
    return ArchiveUnavailableException.translating(
        "read how far a conversation is folded",
        () -> jdbc.queryForObject(FOLDED_THROUGH, Integer.class, conversation));
  }

  /**
   * What one conversation holds that the learner has not been given, in the order the conversation
   * held it.
   *
   * <h2>The third reader, and it needed to be one</h2>
   *
   * <p>{@link #thatProjectFor} filters superseded rows away, which is exactly the material this
   * wants; {@link #forConversation} answers with the whole log, which is more than a bounded window
   * and includes every kind. The resumption work considered a third reader and rejected it, on the
   * ground that resumption did not need one — it does not, and this does: neither existing
   * predicate can express <em>folded, unlearned, and worth a model call</em>. See {@link #MINEABLE}
   * and {@link #ONLY_FOLDED}, which argue each of the four clauses separately because they are four
   * different kinds of decision — and which are two constants rather than one because exactly one
   * of the four is conditional on the tree.
   *
   * <p><b>Folded, or anything at all once nobody can speak into the tree.</b> {@code standing} is
   * what decides between those, and it is the root's own state: an {@code ACTIVE} tree offers only
   * what a fold has covered, because its live rows are still in front of a model and there is a
   * later window for them. Every other state has no later window, so what it offers is everything
   * it has not handed over yet.
   *
   * <p><b>The whole {@code content}, like {@link #payloadsHeldBy} and unlike every other bounded
   * read here.</b> The text is the point — a window is what a model is shown — and the size
   * argument that makes the other reads send {@code length(content)} instead does not apply,
   * because the one kind that is measured in tens of thousands of characters is the one this
   * predicate excludes.
   *
   * @param conversationId the conversation to draw from
   * @param standing where the ROOT of this conversation's tree has got to, which the caller
   *     resolves with {@code ConversationStore.rootOf}. Never the conversation's own {@code
   *     lifecycle} column unless it is a root: a delegated child carries NULL and is as finished as
   *     its tree is
   * @param most how many rows at most, which is the caller's bound and not this store's: {@code
   *     learner.LearningWindow} holds the cap and applies a second one in characters over what
   *     comes back
   * @return the rows, in conversation order, empty for a conversation with nothing waiting — which
   *     is every ACTIVE conversation that has never folded
   * @throws NullPointerException if {@code standing} is absent, which is a caller that has not
   *     resolved the tree — never a default, because the safe-looking default is the one that mines
   *     a conversation somebody can still speak into
   * @throws ValidationException if the conversation id cannot be named
   * @throws IllegalArgumentException if {@code most} is not positive, which is the caller's bug:
   *     nothing here takes a bound from a model
   */
  public List<EntryRecord> awaitingTheLearner(
      String conversationId, ConversationLifecycle standing, int most) {
    String conversation = ConversationStore.named(conversationId);
    Objects.requireNonNull(standing, "standing");
    if (most < 1) {
      throw new IllegalArgumentException("a learning window holds 1 row or more, not " + most);
    }
    return ArchiveUnavailableException.translating(
        "read what is awaiting the learner",
        () ->
            jdbc.query(
                "SELECT "
                    + COLUMNS
                    + " FROM entries WHERE conversation_id = ?"
                    + awaitingTheLearner(standing)
                    + IN_CONVERSATION_ORDER
                    + " LIMIT ?",
                ROW_MAPPER,
                conversation,
                most));
  }

  /**
   * How many rows one conversation has waiting, asked separately from which ones this window holds.
   *
   * <p>{@code COUNT_BEHIND_A_SEAM}'s reason exactly, and the same shape: a window is capped in rows
   * and again in characters, so what it holds is routinely less than what is waiting, and a window
   * that reported only its own size could not be told apart from the end of the queue. The
   * learner's diagnostic names both numbers — {@code FileTools} and {@code ResultTools.Listing}
   * both do, for the same reason — so an operator reading it can see a conversation being drained
   * rather than a conversation that was mined once.
   *
   * @throws ValidationException if the conversation id cannot be named
   */
  public int countAwaitingTheLearner(String conversationId, ConversationLifecycle standing) {
    String conversation = ConversationStore.named(conversationId);
    Objects.requireNonNull(standing, "standing");
    return ArchiveUnavailableException.translating(
        "count what is awaiting the learner",
        () ->
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM entries WHERE conversation_id = ?"
                    + awaitingTheLearner(standing),
                Integer.class,
                conversation));
  }

  /**
   * The conversations holding anything the learner has not been given, the ones that have been
   * waiting longest first.
   *
   * <p><b>Where a pass starts.</b> It is the queue asked which conversations are in it, rather than
   * every conversation asked whether it is, and it is bounded because the answer is a shortlist to
   * rank and not a work list to get through: {@code learner.LearningWindow} resolves each of these
   * to the root of its tree and lets the tree's lifecycle decide which one is mined this pass.
   *
   * <p>Oldest material first, so nothing starves. It is not the final order — a tree marked for
   * ejection overtakes everything, because that is the last chance to mine it before the payload
   * leaves — and this is what that ordering is applied to.
   *
   * @param most how many conversations to consider at most
   * @throws IllegalArgumentException if {@code most} is not positive
   */
  public List<String> conversationsAwaitingTheLearner(int most) {
    if (most < 1) {
      throw new IllegalArgumentException(
          "a shortlist of conversations holds 1 or more, not " + most);
    }
    return ArchiveUnavailableException.translating(
        "read which conversations are awaiting the learner",
        () ->
            jdbc.queryForList(
                protectInformationLearning
                    ? CONVERSATIONS_AWAITING_THE_LEARNER.replace(
                        ") queued", ") queued WHERE information_log_readable(conversation_id,NULL)")
                    : CONVERSATIONS_AWAITING_THE_LEARNER,
                String.class,
                most));
  }

  /**
   * Mark the rows the learner was handed.
   *
   * <h2>The system marks what it gave, and that is the load-bearing half</h2>
   *
   * <p>The alternative design — the agent chooses its window and reports back what it read — makes
   * this stamp exactly as reliable as a model's self-report of its own attention. Because the
   * window is computed here, the caller passes the ordinals it actually handed over and the marking
   * is an observation rather than a claim. That is why this takes a list of ordinals and not a
   * span: a span would be re-derived, and a re-derivation can pick up a row that arrived after the
   * read.
   *
   * <p><b>Write-once</b>, on {@link #ejectPayload}'s clause and for its reason. A row already
   * marked keeps the instant it was first given, and this reports how many rows actually moved so a
   * caller can tell "done" from "already done".
   *
   * <p><b>An empty window is not a statement.</b> A pass over nothing is a legitimate and common
   * outcome — most windows produce no memory at all — and it is not worth a round trip, nor an
   * {@code IN ()} the database would refuse to parse.
   *
   * @param conversationId the conversation the rows belong to. Named again in the WHERE for {@link
   *     #redeem}'s reason: a pass that reached across conversations by accident would mark somebody
   *     else's log as mined
   * @param ordinals which rows, by their place in that conversation's log
   * @param at when they were given, on the caller's clock
   * @return how many rows this call moved
   * @throws ValidationException if the conversation id cannot be named
   */
  public int markLearned(String conversationId, List<Integer> ordinals, Instant at) {
    String conversation = ConversationStore.named(conversationId);
    Objects.requireNonNull(ordinals, "ordinals");
    Objects.requireNonNull(at, "at");
    if (ordinals.isEmpty()) {
      return 0;
    }
    Object[] arguments = new Object[ordinals.size() + 2];
    arguments[0] = utc(at);
    arguments[1] = conversation;
    for (int i = 0; i < ordinals.size(); i++) {
      arguments[i + 2] = ordinals.get(i);
    }
    String places = String.join(",", Collections.nCopies(ordinals.size(), "?"));
    return ArchiveUnavailableException.translating(
        "mark entries as learned", () -> jdbc.update(MARK_LEARNED.formatted(places), arguments));
  }

  /**
   * The tool calls, as the JSON array the column holds.
   *
   * <p>Unchecked to the caller, because there is nothing a caller could do about it and nothing
   * that can produce it: {@code ToolCall} is three strings, all non-null by its own constructor,
   * and Jackson cannot fail to write those. An {@code IllegalStateException} rather than a swallow,
   * so that a shape this method cannot serialise is loud at the write rather than silently absent
   * from the row.
   */
  private static String written(List<ToolCall> calls) {
    try {
      return CALLS_OUT.writeValueAsString(calls);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException(
          "tool calls could not be written as JSON, which three non-null strings" + " cannot do",
          impossible);
    }
  }

  private static final ObjectMapper SALIENTS_OUT = new ObjectMapper();

  /** Call id -> salient argument, as the JSONB `salients` column holds it. */
  private static String salientsWritten(Map<String, String> salients) {
    try {
      return SALIENTS_OUT.writeValueAsString(new TreeMap<>(salients));
    } catch (JsonProcessingException notWritten) {
      throw new IllegalStateException("a map of strings did not serialise", notWritten);
    }
  }

  /**
   * The tool calls a row holds, or an empty list for a row with none.
   *
   * <p>A failure here is a row this build cannot read back, which is the same class of fault as an
   * unknown {@code kind}: it is thrown rather than softened, so it surfaces at the read that found
   * it instead of as a request quietly missing the calls an answer declared.
   */
  /**
   * The cut calls of one page row, or none.
   *
   * <p>Null is "asked for nothing" here exactly as it is in {@link #read}: an answer with no calls
   * stores NULL rather than {@code []}, and {@code jsonb_agg} over no rows produces NULL for the
   * same fact.
   */
  private static List<EntryPage.Asked> asked(String json) {
    if (json == null) {
      return List.of();
    }
    try {
      return ASKED_IN.readValue(json);
    } catch (JsonProcessingException unreadable) {
      throw new IllegalStateException(
          "a page could not read back an entry's tool calls: " + unreadable.getOriginalMessage(),
          unreadable);
    }
  }

  private static List<ToolCall> read(String json) {
    if (json == null) {
      return List.of();
    }
    try {
      return CALLS_IN.readValue(json);
    } catch (JsonProcessingException unreadable) {
      throw new IllegalStateException(
          "an entry's tool_calls could not be read back: " + unreadable.getOriginalMessage(),
          unreadable);
    }
  }

  /*
   * getObject and not getInt for superseded_by: getInt reads a SQL NULL as 0,
   * and 0 is not a possible ordinal -- entries_are_numbered_from_one -- so
   * every uncovered entry would come back claiming to be folded away by an
   * entry that cannot exist, and the projection would return nothing at all.
   * The same trap TurnStore names for prompt_tokens, with a louder failure.
   */
  private static final RowMapper<Invocation> INVOCATION_MAPPER =
      (rs, rowNum) ->
          new Invocation(
              rs.getObject("invocation", UUID.class),
              rs.getString("produced_by"),
              Invocation.Dispatch.of(rs.getString("dispatch")),
              rs.getString("model_specifier"),
              rs.getString("model_pool"),
              rs.getString("wire_model"),
              CompletionOutcome.of(rs.getString("completion")),
              rs.getString("finish_reason"),
              // getObject for took_ms's reason: a count the endpoint never gave is
              // not a count of zero.
              new TokenUsage(
                  rs.getObject("prompt_tokens", Integer.class),
                  rs.getObject("completion_tokens", Integer.class),
                  null,
                  rs.getObject("reasoning_tokens", Integer.class)),
              instant(rs, "sent_at"),
              rs.getString("fallback_reason"),
              rs.getObject("first_token_ms", Long.class));

  private static final RowMapper<EntryRecord> ROW_MAPPER =
      (rs, rowNum) ->
          new EntryRecord(
              rs.getString("conversation_id"),
              rs.getInt("ordinal"),
              EntryKind.of(rs.getString("kind")),
              rs.getString("content"),
              rs.getString("tool_call_id"),
              read(rs.getString("tool_calls")),
              rs.getObject("superseded_by", Integer.class),
              rs.getObject("handle", UUID.class),
              rs.getInt("turn_ordinal"),
              instant(rs, "recorded_at"),
              // getObject and not getLong, on superseded_by's reasoning and with a
              // quieter failure than its one: getLong reads a SQL NULL as 0, and 0
              // is a legitimate duration here -- an operation that finished inside
              // a millisecond -- so an unmeasured entry would come back claiming to
              // have been measured at nothing, which is exactly the confusion
              // between an absence and a measurement this column was added to
              // avoid.
              rs.getObject("took_ms", Long.class),
              instant(rs, "ejected_at"),
              rs.getString("export"));

  /*
   * OffsetDateTime on both sides and never java.sql.Timestamp, for the reason
   * MemoryStore, ProposalStore, ReasonLog and ConversationStore all give:
   * Timestamp carries no zone and comes back through the JVM's default
   * calendar, so a server outside UTC round-trips a shifted instant.
   */
  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  /**
   * Null-aware, because `recorded_at` really is nullable: an entry written before V16 has no stamp,
   * and NULL there means "older than the mechanism" rather than "happened at no time". {@code
   * ConversationStore}'s copy, for {@code last_turn_at}.
   */
  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  /*
   * `tool` may be NULL and is read as one: a result whose call nothing in the
   * log declares is listed without a name rather than dropped, and the tool
   * that renders it says which of the two it is looking at. `size` is
   * length(content) and never the content itself; see
   * BEHIND_A_SEAM_NEWEST_FIRST for why the length is computed where the text
   * already is.
   */
  /*
   * `excerpt` is the whole transcript text or left(content, ...) for other
   * kinds, and `length` is length(content), which is
   * the pair EntryPage.Row splits and PAGE_OF argues. superseded_by and took_ms
   * are read with getObject for ROW_MAPPER's two reasons -- 0 is not a possible
   * ordinal, and 0 IS a possible duration -- and recorded_at through the
   * null-aware reader, because a row older than V16 has no stamp.
   */
  private static final RowMapper<EntryPage.Row> PAGE_MAPPER =
      (rs, rowNum) ->
          new EntryPage.Row(
              rs.getInt("ordinal"),
              rs.getInt("turn_ordinal"),
              EntryKind.of(rs.getString("kind")),
              // `excerpt` is left(NULL, ...) -- so, NULL -- for an ejected
              // payload, and it is passed through as one rather than substituted
              // with a blank: a page that showed an ejected result as empty text
              // would be telling a reader a tool returned nothing.
              rs.getString("excerpt"),
              rs.getInt("length"),
              instant(rs, "ejected_at"),
              rs.getObject("superseded_by", Integer.class),
              rs.getString("tool_call_id"),
              asked(rs.getString("tool_calls")),
              rs.getObject("handle", UUID.class),
              instant(rs, "recorded_at"),
              rs.getObject("took_ms", Long.class),
              // V39, and null on every row but an answer or a refusal, and on
              // every one written before the migration. A page shows which target
              // answered; the rest of the provenance is invocationOf's to hand an
              // audit. dispatch and completion are stored spellings, read as the
              // strings they are rather than parsed here -- a page is not the
              // place a value this table already constrained is validated again.
              rs.getString("dispatch"),
              rs.getString("wire_model"),
              rs.getString("completion"),
              // V57. An utterance written before it has no speaker and reads as a person's.
              Speaker.read(
                  EntryKind.of(rs.getString("kind")),
                  rs.getString("speaker"),
                  rs.getString("speaker_name")),
              // V62, and null for every row but a TOOL_RESULT and for one written before it.
              rs.getString("outcome"),
              rs.getString("job_id"));

  private static final RowMapper<StoredResults.Result> LISTING_MAPPER =
      (rs, rowNum) ->
          new StoredResults.Result(
              rs.getString("tool"),
              rs.getInt("size"),
              instant(rs, "ejected_at"),
              rs.getObject("handle", UUID.class));
}
