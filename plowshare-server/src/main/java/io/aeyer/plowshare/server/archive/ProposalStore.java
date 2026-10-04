package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.MemoryIds;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The promotion queue: what a curator proposed, and what a human decided.
 *
 * <p>Durable on purpose, and the one durable thing in this slice. Jobs live in memory and are gone
 * after a restart, which is honest because nothing is left holding a lie; a proposal is the
 * opposite case — it is a question addressed to a person who is not at the console yet, so losing
 * it loses the work rather than merely the run.
 *
 * <h2>Two guarantees, and both are in Postgres because Java cannot hold them</h2>
 *
 * <p>A nightly curator pass and a human settling the queue are two connections in two transactions,
 * and neither can see the other's uncommitted rows. So:
 *
 * <ul>
 *   <li><b>One pending proposal per memory and action</b> is the partial unique index {@code
 *       proposals_one_pending}, not a {@code SELECT} before the {@code INSERT}. Two passes both
 *       pass a Java check and file two rows.
 *   <li><b>One settlement per proposal</b> is a single conditional {@code UPDATE ... WHERE id = ?
 *       AND state = 'pending'}, not a load followed by a write. Two settlers both read {@code
 *       pending} and the later write wins in silence — and the loser is whichever human's decision
 *       was applied second, over a promotion that may already have happened.
 * </ul>
 *
 * <p>Both are measured rather than assumed, against pgvector/pgvector:pg16 on 2026-08-29, by two
 * tests that drive two open transactions and interleave them by hand. In each, the second statement
 * is issued while the first transaction is still open and <em>blocks</em> there until it commits:
 * the insert then does nothing (see {@link #propose} on why it is {@code ON CONFLICT} and not a
 * caught {@link DuplicateKeyException}), and the conditional update then matches zero rows. Each
 * test was run against a build that lacked the guarantee it names — a Java pre-check with the index
 * dropped, and a load-then-update resolve — and each failed there while every single-connection
 * test in the file went on passing.
 *
 * <h2>What this class is not</h2>
 *
 * <p>It knows nothing about promotion. {@link Archive#promote} is what decides whether a memory can
 * still become a global one — a memory superseded or invalidated between the proposal and the
 * answer is refused there, by a message naming its state — and repeating that state test here in
 * SQL would be a second copy of one rule, drifting from the first. {@code PromotionQueue} is the
 * layer that holds both.
 *
 * <p>Framework-free apart from {@link JdbcTemplate}, matching {@link MemoryStore}: no stereotype
 * annotation, because it takes a clock and an id factory that no component scan can supply. {@code
 * ArchiveConfig} wires it.
 */
public class ProposalStore {

  /** The one action there is today: make this project memory a global one. */
  public static final String PROMOTE = "promote";

  /**
   * Proposal ids, minted like memory ids so that lexicographic order is minting order — {@link
   * #pending} sorts by id alone and a human working the queue top-down meets the longest-waiting
   * question first.
   */
  private static final String PREFIX = "prp_";

  /*
   * The join is the reason there is no `project` column on `proposals`. The
   * tier belongs to the memory, and a copy on the proposal would be a second
   * spelling of one fact — the one a caller could supply wrongly, and the one
   * nothing would ever check. It is an inner join on a primary key, and the
   * foreign key makes it total: every proposal has a memory.
   *
   * A second join since V14, and it is a different kind. The memory join is
   * inner and total; the project join is LEFT and deliberately partial,
   * because a NULL `project_id` is the global tier and an inner join would
   * silently drop every global proposal from every read.
   */
  private static final String SELECT =
      """
            SELECT p.id, p.memory_id, pr.name AS project, p.action, p.reason, p.state,
                   p.created_at, p.proposed_by, p.resolved_at, p.resolved_by, p.resolution
            FROM proposals p JOIN memories m ON m.id = p.memory_id
            LEFT JOIN projects pr ON pr.id = m.project_id
            """;

  /*
   * `IS NOT DISTINCT FROM` and not `=`, for the reason MemoryStore gives at
   * length: the global tier is a NULL project and `NULL = NULL` is NULL, so a
   * plain equality returns zero global rows forever. The CAST is not
   * decoration either — with a bare `?` the driver sends an untyped NULL and
   * Postgres refuses the statement outright.
   */
  private static final String HOME_MATCHES = "m.project_id IS NOT DISTINCT FROM CAST(? AS BIGINT)";

  private final JdbcTemplate jdbc;
  private final Supplier<Instant> now;
  private final Supplier<String> idFactory;

  /** Production wiring: the real clock and real ids. */
  public ProposalStore(JdbcTemplate jdbc) {
    this(jdbc, Instant::now, null);
  }

  /**
   * @param jdbc where the rows live
   * @param now the clock, injected so a test can move time between a proposal and its settlement
   *     and assert that the two instants differ
   * @param idFactory id minting, or {@code null} for the default. Injected for the reason {@code
   *     Archive}'s is: a test asserting on queue order needs ids it chose rather than random hex.
   */
  public ProposalStore(JdbcTemplate jdbc, Supplier<Instant> now, Supplier<String> idFactory) {
    this.jdbc = jdbc;
    this.now = now;
    this.idFactory = idFactory != null ? idFactory : () -> MemoryIds.mint(PREFIX, now.get());
  }

  /**
   * File a question about a memory, if one is not already waiting.
   *
   * <p>The duplicate is refused rather than folded into the existing row, and the existing id is in
   * the message. A {@code propose} that quietly returned what was already there would let a nightly
   * pass count a proposal it did not file — and Task 9's curator reports "proposed those" as part
   * of how the run ended, so the over-count would reach a human as work done.
   *
   * <h3>{@code ON CONFLICT DO NOTHING} and not a caught violation</h3>
   *
   * <p>The obvious shape — a plain INSERT, catching {@link DuplicateKeyException} and reading the
   * blocking row to name it in the message — <b>is broken inside a transaction, and every
   * single-connection test in this file passes against it.</b> Measured on 2026-08-29 by {@code
   * two_concurrent_proposals_for_one_memory_leave_one_row}, which is the only test here that runs
   * this method with autocommit off: a failed statement aborts the whole Postgres transaction, so
   * the read inside the catch block comes back {@code SQLState 25P02, "current transaction is
   * aborted, commands ignored until end of transaction block"} — and Spring wraps that as an {@code
   * UncategorizedSQLException} which replaces the refusal on its way out. The caller is then told
   * the database is broken, about a duplicate the queue handled exactly as designed.
   *
   * <p>{@code ON CONFLICT ... DO NOTHING} raises nothing, so the transaction is still usable and
   * the blocking row can be read and named. Its conflict target repeats {@code
   * proposals_one_pending}'s predicate because a partial index is only inferable from one that
   * matches it. The index is still the guarantee: a second transaction's insert waits on it until
   * the first commits, and only then does nothing — which is what the concurrent test asserts by
   * requiring the second call to have blocked.
   *
   * <p>The read back through {@link #get} at the end is what puts the memory's own tier on the
   * returned proposal, rather than anything this method assembled. Nothing deletes a proposal, so
   * the row it reads is the row this just wrote.
   *
   * @param by who is asking. Required, unlike the {@code proposed_by} column, which is nullable
   *     only for rows filed before it existed: a question with nobody's name on it is the gap this
   *     column was added to close, so writing a new one would re-open it. {@code Curator.BY} is
   *     what a pass files under, and a row carrying it is one no person raised.
   * @throws ValidationException if the action, the reason or {@code by} is missing
   * @throws ArchiveException if no memory was ever written under this id, or if a proposal for this
   *     memory and action is already waiting
   */
  public Proposal propose(String memoryId, String action, String reason, String by) {
    // Before the INSERT, matching Archive.applyVerdict's Validation.check: a
    // malformed proposal is refused without taking a connection at all. The
    // database would refuse both of these too — proposals_action_named and
    // proposals_reason_given — but as a constraint violation from a stack
    // many layers from whoever dropped the field, and a NULL would not be
    // refused by the CHECK at all, only by NOT NULL.
    requireNonBlank("memoryId", memoryId);
    requireNonBlank("action", action);
    requireNonBlank("reason", reason);
    requireNonBlank("by", by);

    String id = idFactory.get();
    int filed;
    try {
      // The translation sits INSIDE this try, and the order is the whole
      // point: an unreachable database leaves as an
      // ArchiveUnavailableException, which the clause below does not
      // catch, while the integrity violation the clause exists for passes
      // through untranslated and is still read as the caller's mistake.
      filed =
          ArchiveUnavailableException.translating(
              "file a proposal",
              () ->
                  jdbc.update(
                      """
                            INSERT INTO proposals
                                (id, memory_id, action, reason, state, created_at, proposed_by)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (memory_id, action) WHERE state = 'pending' DO NOTHING
                            """,
                      id,
                      memoryId,
                      action,
                      reason,
                      ProposalState.PENDING.wireName(),
                      utc(now.get()),
                      by));
    } catch (DataIntegrityViolationException noSuchMemory) {
      // The foreign key. Reported as the caller's mistake rather than as a
      // database failure because that is what it is: nothing deletes a
      // memory in any state, so the only way to violate this constraint is
      // to name an id that was never written. Nothing is read after it —
      // this transaction is aborted, which is the trap the javadoc above
      // records.
      //
      // It used to also catch a null memoryId, which arrives as a NOT
      // NULL violation on the same Spring type and came back as "no memory
      // with id null" — a sentence sending the reader to look for a row
      // when the field was simply missing. requireNonBlank above refuses
      // that first.
      //
      // It is still not *only* the foreign key, and saying so is cheaper
      // than being wrong about it: DuplicateKeyException extends this
      // type, so a primary-key collision on a minted proposal id lands
      // here and reports the memory. Unreachable in production, where ids
      // carry 24 bits of randomness over a millisecond timestamp — but
      // reachable with an injected id factory, which this class supports
      // on purpose, and which every test in ProposalStoreTest uses.
      throw new ArchiveException(
          "no memory with id " + memoryId + "; a proposal names the memory it is about");
    }
    if (filed == 0) {
      throw alreadyWaiting(memoryId, action);
    }
    return get(id);
  }

  /**
   * The proposal with this id.
   *
   * @throws ArchiveException if no proposal was ever filed under this id. Unlike {@link
   *     MemoryStore#load}, which answers with an {@link Optional} because "is this memory here?" is
   *     an ordinary question — a caller holding a proposal id got it from this queue, so an id that
   *     resolves to nothing is a mistake rather than an answer.
   */
  public Proposal get(String id) {
    List<Proposal> found =
        ArchiveUnavailableException.translating(
            "read a proposal by id", () -> jdbc.query(SELECT + " WHERE p.id = ?", ROW_MAPPER, id));
    if (found.isEmpty()) {
      throw new ArchiveException("no proposal with id " + id);
    }
    return found.get(0);
  }

  /**
   * What is still waiting in one tier, longest-waiting first.
   *
   * <p>One tier at a time, like every other read in this archive: a human settling one project's
   * queue must not be handed another project's, and the global tier is not a project's business.
   */
  public List<Proposal> pending(Home home) {
    return ArchiveUnavailableException.translating(
        "list the waiting proposals in a tier",
        () ->
            jdbc.query(
                SELECT + " WHERE " + HOME_MATCHES + " AND p.state = ? ORDER BY p.id",
                ROW_MAPPER,
                ProjectIds.toRead(jdbc, home),
                ProposalState.PENDING.wireName()));
  }

  /**
   * The memories in one tier somebody has already answered a question about.
   *
   * <p>The archive's own principle one layer up. A tombstone carrying the reason a fact stopped
   * being true is what stops a future agent rediscovering it; a human deciding a memory is too
   * niche for global is exactly that, and a queue that forgot would ask again next week, and the
   * week after. It is sound only because <b>memories are immutable</b> — nothing is edited in
   * place, a changed fact is a new record with a new id — so a ruling attaches to a thing that
   * cannot quietly become a different thing.
   *
   * <p>Accepted proposals are in here too, and for a different reason from rejections: the memory
   * an accepted proposal named has since been superseded by the global record it became, so
   * proposing it again would propose a tombstone.
   *
   * <p><b>Pending proposals are not in here, and a caller filtering triage wants both.</b> "Ruled
   * on" means somebody answered, and stretching it to cover unanswered questions would make the
   * word untrue — but a curator that filtered on this alone would still spend a model call judging
   * a memory whose proposal is already on a human's screen, and then be refused by {@link
   * #propose}. Task 9's triage should subtract {@link #pending} as well.
   */
  public Set<String> ruledOn(Home home) {
    // Set.copyOf and not a LinkedHashSet, which is a retreat from a promise
    // rather than a tidy-up. The LinkedHashSet advertised an iteration order
    // over a bare DISTINCT, which is free to hash — an unbacked promise of
    // exactly the kind this class keeps finding. Adding ORDER BY did not
    // rescue it either: with the sort removed again the test written to
    // catch that still passed, because this planner returns the rows sorted
    // for its own reasons. So the honest move is the one nothing loses by —
    // no caller needs an order here, triage asks this set `contains`, and an
    // unordered Set says so in the type.
    //
    // DISTINCT stays, but it is not what makes the set unique and this
    // comment used to imply that it was. Set.copyOf collapses duplicates on
    // its own — measured: with DISTINCT removed, every test here still
    // passes. What the SQL buys is rows that never cross the wire, and one
    // memory really can be ruled on several times, because a rejection does
    // not stop it being proposed again. Uniqueness is the collection's;
    // DISTINCT is the transfer cost.
    return Set.copyOf(
        ArchiveUnavailableException.translating(
            "list the memories in a tier that have been ruled on",
            () ->
                jdbc.queryForList(
                    "SELECT DISTINCT p.memory_id FROM proposals p"
                        + " JOIN memories m ON m.id = p.memory_id"
                        + " WHERE "
                        + HOME_MATCHES
                        + " AND p.state <> ?",
                    String.class,
                    ProjectIds.toRead(jdbc, home),
                    ProposalState.PENDING.wireName())));
  }

  /**
   * Settle a waiting proposal, once.
   *
   * <p><b>One statement, and that is the guarantee.</b> The state test and the write are the same
   * UPDATE, so a second settler waits on the row lock and then matches nothing. A load followed by
   * a write passes every single-connection test and lets two people settle one proposal, with the
   * later decision overwriting the earlier — which, when the earlier one was an acceptance, means a
   * promotion has already happened under a row that now reads {@code rejected}.
   *
   * <p>This records the decision and nothing else. Accepting a promotion is {@code
   * PromotionQueue}'s to perform, because it is the archive that knows whether the memory can still
   * be promoted.
   *
   * @param accept true to record acceptance, false for rejection
   * @param resolution the settler's account, or blank for none — stored as absent rather than as an
   *     empty string, so "no comment" and "" are not two spellings of one state
   * @param by who settled it. Required, matching {@link Archive#promote}: this row is the record
   *     that a human agreed.
   * @throws ValidationException if {@code by} is missing or blank
   * @throws ArchiveException if no proposal was ever filed under this id, or if it has already been
   *     settled — naming the state it is in, so a caller can tell an acceptance from a rejection
   *     without a second query
   */
  public Proposal resolve(String id, boolean accept, String resolution, String by) {
    // Before the UPDATE, so a settlement with nobody's name on it does not
    // take a connection on its way to being refused. The database would also
    // refuse it — proposals_settlement_matches_state and
    // proposals_resolver_named — but a constraint violation names a column,
    // not the caller's mistake.
    requireNonBlank("by", by);

    ProposalState settled = accept ? ProposalState.ACCEPTED : ProposalState.REJECTED;
    int rows =
        ArchiveUnavailableException.translating(
            "settle a proposal",
            () ->
                jdbc.update(
                    """
                        UPDATE proposals
                           SET state = ?, resolved_at = ?, resolved_by = ?, resolution = ?
                         WHERE id = ? AND state = ?
                        """,
                    settled.wireName(),
                    utc(now.get()),
                    by,
                    blankToNull(resolution),
                    id,
                    ProposalState.PENDING.wireName()));

    if (rows == 0) {
      // Zero means one of two things, and this read is what tells them
      // apart. It is not part of the guarantee above: the UPDATE has
      // already decided, and nothing deletes a proposal, so this read
      // cannot change the answer — only describe it.
      throw alreadySettledOrUnknown(id);
    }
    return get(id);
  }

  /**
   * Write onto a settled proposal the account of what the archive actually did.
   *
   * <p>Package-private, and both halves of that matter. It is <em>needed</em> because the account
   * cannot be written by {@link #resolve}: an approval's account names the record the promotion
   * wrote, and that record does not exist until after the claim has been taken. It is
   * <em>restricted</em> because nothing outside this package should be able to rewrite the answer
   * on a settled proposal — {@link PromotionQueue} is the one caller, and it writes the account for
   * a promotion it has just performed.
   *
   * <p>Refuses a proposal still waiting rather than silently doing nothing: an account on a pending
   * row would be an answer to a question nobody has answered, and the CHECK in {@code
   * V2__proposals.sql} would refuse the row anyway, from a stack that names a column instead of the
   * mistake.
   */
  Proposal note(String id, String account) {
    // Read first — not to make this safe, because the UPDATE below carries
    // its own state test, but so the refusal can name the mistake. A zero
    // row count here means "no such proposal" or "still waiting", and those
    // are different things to be told. This guard used to reuse resolve's
    // alreadySettledOrUnknown, which has no branch for a waiting row, and so
    // answered a pending proposal with "proposal prp_1 was already pending
    // by null; a settled proposal is not re-opened by settling it again" —
    // a sentence describing a settlement that never happened, with a literal
    // null where a name would go, guarded by a test that asserted only the
    // id and so could not see any of it.
    Proposal existing = get(id);
    if (existing.state() == ProposalState.PENDING) {
      throw new ArchiveRefusedException(
          "proposal "
              + id
              + " is still waiting, so there is no"
              + " settlement on it to account for; an account on a waiting proposal would"
              + " be an answer to a question nobody has answered");
    }
    int rows =
        ArchiveUnavailableException.translating(
            "record the account of a settled proposal",
            () ->
                jdbc.update(
                    "UPDATE proposals SET resolution = ? WHERE id = ? AND state <> ?",
                    blankToNull(account),
                    id,
                    ProposalState.PENDING.wireName()));
    if (rows == 0) {
      // The state test stays on the UPDATE even though the read above
      // already made it, and this branch is what it is for: a release()
      // landing between the two. Removing the test survives every other
      // test in this class — measured — so the branch is held down by
      // an_account_written_while_the_claim_is_given_back_records_nothing,
      // which drives exactly that interleaving through a store that
      // releases on its way out of the read.
      throw new ArchiveRefusedException(
          "proposal "
              + id
              + " went back to waiting while its"
              + " account was being written; nothing was recorded");
    }
    return get(id);
  }

  /**
   * Put a settled proposal back in the queue.
   *
   * <p><b>Two callers, and they are here for different reasons.</b> The first is the compensating
   * half of a claim: {@link PromotionQueue} settles a proposal <em>before</em> promoting, because
   * that conditional UPDATE is the only thing that makes a promotion happen once, and when the
   * archive then refuses the promotion the claim bought nothing and the row must not go on saying a
   * human approved something that did not happen. The second is {@link PromotionQueue#reconsider},
   * which is not undoing a claim at all: it is an operator asking that a ruling the curator made by
   * itself be put in front of a person. What the two share is exactly this statement — the row goes
   * back to {@code pending}, and the waiting place it vacated may already be taken.
   *
   * <p>Package-private for the same reason as {@link #note}, and more sharply: this is the one
   * operation that undoes a settlement, and the design's whole account of the queue rests on
   * settlements being final to everybody else. It is not "resolve twice" — {@code resolve} still
   * refuses that — it is a caller inside this package putting one row back.
   *
   * <p>Conditional on the row being settled, so that a release racing anything else cannot clear a
   * settlement it did not take. What it deliberately does <em>not</em> check is who settled it:
   * {@code by} is on the row, but the only caller releases a claim it took microseconds earlier in
   * the same method, and a name check there would be a guard against a caller that does not exist.
   *
   * <p><b>This can fail, and the caller has to be ready for it.</b> Returning a proposal to {@code
   * pending} puts it back in the {@code (memory_id, action)} waiting place it vacated when it was
   * claimed — and that place may already be occupied, because claiming it is exactly what freed it.
   * Then {@code proposals_one_pending} refuses the release and the row stays settled for work that
   * did not happen. {@code
   * ProposalStoreTest.releasing_a_claim_whose_waiting_place_was_taken_is_refused} reaches that
   * deterministically, without any concurrency at all.
   */
  Proposal release(String id) {
    // Read first, for the reason note() gives, and for a second one: the
    // refusal below names the memory and the action, and they cannot be
    // looked up *after* the UPDATE fails — a failed statement aborts the
    // transaction, which is the trap propose() records at length.
    Proposal claimed = get(id);
    if (claimed.state() == ProposalState.PENDING) {
      throw new ArchiveRefusedException(
          "proposal "
              + id
              + " is still waiting; nobody"
              + " has claimed it, so there is no claim to give back");
    }
    int rows;
    try {
      // Inside the try for the same reason as propose()'s: the duplicate
      // key below is the database answering, and only a failure to reach
      // it at all is translated.
      rows =
          ArchiveUnavailableException.translating(
              "give back a claim on a proposal",
              () ->
                  jdbc.update(
                      """
                            UPDATE proposals
                               SET state = ?, resolved_at = NULL, resolved_by = NULL,
                                   resolution = NULL
                             WHERE id = ? AND state <> ?
                            """,
                      ProposalState.PENDING.wireName(),
                      id,
                      ProposalState.PENDING.wireName()));
    } catch (DuplicateKeyException placeTaken) {
      throw new ArchiveRefusedException(
          "proposal "
              + id
              + " cannot be released: another"
              + " proposal to "
              + claimed.action()
              + " memory "
              + claimed.memoryId()
              + " has taken the waiting place this one vacated when it was claimed, and"
              + " only one may be waiting. It is left "
              + claimed.state().wireName()
              + ".");
    }
    if (rows == 0) {
      // Same shape as note()'s, and reachable the same way: a second
      // release between the read above and this write. Held down by
      // a_claim_given_back_twice_says_somebody_else_got_there_first.
      throw new ArchiveRefusedException(
          "proposal "
              + id
              + " was released by somebody else"
              + " while this claim was being given back");
    }
    return get(id);
  }

  /**
   * The ids of every settled proposal in one tier that one settler answered under one account.
   *
   * <p>A query and its two refusals, which is what every other method in this class is. <b>What it
   * deliberately is not is the operation</b>: putting those rows back is a loop over {@link
   * #release} that has to carry a partial-failure report, and this class's own "What this class is
   * not" hands that to {@link PromotionQueue}. {@code PromotionQueue.reconsider} is the caller, and
   * it is where the argument about who may ask lives.
   *
   * <p>Deliberately <em>generic</em> — this class never learns what a curator is — so the two
   * strings arrive from the layer that decides who may ask. Nothing in {@code archive} can name
   * {@code Curator.BY} and {@code Curator.KEPT} together, and that is the point rather than an
   * accident of packaging.
   *
   * @param settledBy the {@code resolved_by} to match, exactly
   * @param resolution the {@code resolution} to match, exactly. Both, and not just the settler: one
   *     settler can reject a row for more than one reason, and only its <em>self-answered</em> ones
   *     are the machine's to hand back — a rejection it made under any other account is a decision
   *     somebody meant, filed under the same name. <b>This is not what keeps an accepted row
   *     out</b>, and an earlier version of this sentence said it was: {@code p.state = 'rejected'}
   *     below excludes those, and a curator's acceptance carries the promotion's account rather
   *     than {@code Curator.KEPT} anyway. The code was right and the reason was not.
   * @throws ValidationException if either predicate is missing or blank
   */
  List<String> rulingsSettledUnder(Home home, String settledBy, String resolution) {
    requireNonBlank("settledBy", settledBy);
    requireNonBlank("resolution", resolution);

    return ArchiveUnavailableException.translating(
        "find the rulings a settler made by itself",
        () ->
            jdbc.queryForList(
                "SELECT p.id FROM proposals p JOIN memories m ON m.id = p.memory_id"
                    + " WHERE "
                    + HOME_MATCHES
                    + " AND p.state = ?"
                    + " AND p.resolved_by = ? AND p.resolution = ? ORDER BY p.id",
                String.class,
                ProjectIds.toRead(jdbc, home),
                ProposalState.REJECTED.wireName(),
                settledBy,
                resolution));
  }

  private ArchiveException alreadySettledOrUnknown(String id) {
    Proposal existing;
    try {
      existing = get(id);
    } catch (ArchiveException unknown) {
      return unknown;
    }
    return new ArchiveRefusedException(
        "proposal "
            + id
            + " was already "
            + existing.state().wireName()
            + " by "
            + existing.resolvedBy()
            + "; a settled proposal is not re-opened by settling it again");
  }

  /**
   * The refusal for a proposal the index would not take, naming the row that blocked it.
   *
   * <p>The id is looked up rather than left out, because the caller's next move is to look at what
   * is already queued and a message without it costs them a query to find out which row they have
   * to settle.
   *
   * <p><b>The second branch is untested and is reachable, which is why it is written at all.</b> It
   * needs the blocking proposal to be settled by somebody else between this method's insert and
   * this read — a third transaction landing inside one method call. {@code interleaved} in the test
   * drives two connections around whole calls and cannot interleave inside one, and nothing else
   * here can either; a message reading "already waiting as null" is what the branch exists to
   * avoid, and this is the record that no test holds it down.
   */
  private ArchiveRefusedException alreadyWaiting(String memoryId, String action) {
    List<String> blocking =
        ArchiveUnavailableException.translating(
            "find the proposal already waiting on a memory",
            () ->
                jdbc.queryForList(
                    "SELECT id FROM proposals"
                        + " WHERE memory_id = ? AND action = ? AND state = ?",
                    String.class,
                    memoryId,
                    action,
                    ProposalState.PENDING.wireName()));
    if (blocking.isEmpty()) {
      return new ArchiveRefusedException(
          "a proposal to "
              + action
              + " memory "
              + memoryId
              + " was waiting when this one was filed and has since been settled; filing"
              + " it again may now succeed");
    }
    return new ArchiveRefusedException(
        "a proposal to "
            + action
            + " memory "
            + memoryId
            + " is already waiting as "
            + blocking.get(0)
            + "; settle that one rather than filing a second");
  }

  private static void requireNonBlank(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new ValidationException("field '" + field + "' is required and must not be empty");
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  /**
   * Row to record. One mapper, so there is one place the column names live.
   *
   * <p>{@code project} is the name from the {@code projects} row the joined memory points at, and
   * NULL is the global tier — reached through the LEFT join, since a global memory points at
   * nothing. {@code Home.of} would refuse the null rather than mean it, which is what keeps
   * "global" from becoming a string a project could take.
   */
  private static final RowMapper<Proposal> ROW_MAPPER =
      (rs, rowNum) ->
          new Proposal(
              rs.getString("id"),
              rs.getString("memory_id"),
              rs.getString("project") == null ? Home.global() : Home.of(rs.getString("project")),
              rs.getString("action"),
              rs.getString("reason"),
              ProposalState.fromWireName(rs.getString("state")),
              instant(rs, "created_at"),
              rs.getString("proposed_by"),
              instant(rs, "resolved_at"),
              rs.getString("resolved_by"),
              rs.getString("resolution"));

  /*
   * OffsetDateTime on both sides, never java.sql.Timestamp, for the reason
   * MemoryStore gives: Timestamp carries no zone and comes back through the
   * JVM's default calendar, so a server outside UTC round-trips a shifted
   * instant. Here that would put a settlement's recorded hour hours away from
   * when the human actually answered.
   */
  private static OffsetDateTime utc(Instant instant) {
    // No null branch, unlike MemoryStore's: every instant written here comes
    // from the clock, so there is no absent one to encode. A defensive null
    // check would be a branch no test could reach and no caller could cause.
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
