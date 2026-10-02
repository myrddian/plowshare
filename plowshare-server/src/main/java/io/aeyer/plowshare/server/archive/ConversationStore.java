package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.MemoryIds;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.TurnCap;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The conversations this server holds, and what each has spent.
 *
 * <h2>What this row holds, and what {@link TurnStore} holds beside it</h2>
 *
 * <p>The allowance and the fact that several turns share it, and nothing else.
 * {@code V6__conversations.sql} carries the rest of the argument, including the
 * two places that schema went against the plan and why.
 *
 * <p><b>The turns themselves are a table now, and this paragraph used to say
 * they could not be.</b> It read "a turn is a job, and {@code agents.JobStore}
 * keeps jobs in a map for the life of the process — so a turn has no row for
 * this table to point at", which conflated the run with the record of it. The
 * run really is in memory and really does not survive a restart; what a turn
 * <em>came to</em> is exactly the kind of thing {@code JobStore} says belongs in
 * Postgres. {@link TurnStore} holds it, {@code V7__turns.sql} points at this
 * table rather than the other way round, and no column here changed.
 *
 * <h2>The budget is {@code agents.Budget} and there is no second one</h2>
 *
 * <p>{@link ConversationRecord} says why the record carries the object rather
 * than the pair of integers the row holds, and what that costs in package
 * dependencies. The rule this class adds on top is one write:
 * {@link #turnEnded} <b>cannot lower a conversation's spending</b>. A record is
 * a snapshot, so a caller holding a stale one and writing it back would return
 * calls to a budget that has already spent them — which is the one accounting
 * error a person could not see, because the number that comes back is still a
 * plausible number.
 *
 * <p>Framework-free apart from {@link JdbcTemplate} and wired by a
 * configuration class rather than annotated, matching {@link ProposalStore},
 * {@link ProjectStore} and {@link ReasonLog}: it takes a clock a component scan
 * has nothing to supply.
 *
 * <p><b>This class shipped with no production caller at all, and both reads have
 * one now.</b> The paragraph here used to say so and to defend the read side
 * anyway — "a store that can only be written is a table nothing can answer
 * from", {@code ReasonLog.forMemory}'s argument about the same situation — with
 * {@link #find} and {@link #inHome} present and driven by tests alone. That was
 * the right call and it is discharged: {@code Turn.speak} finds a conversation
 * before every utterance, and slice 4's {@code GET /v1/conversations} answers a
 * console's opening screen out of {@link #inHome}, whose {@code ORDER BY
 * created_at, id} became the order that screen lists them in.
 */
public final class ConversationStore {

    /** The third caller of {@code MemoryIds}' scheme, after {@code mem_} and
     *  {@code prp_}. Its javadoc says the split exists for exactly this. */
    public static final String PREFIX = "cnv_";

    /*
     * Qualified, and joined to `projects`, since V14 moved the project from a
     * name on this row to a reference. A conversation still carries a Home made
     * of a name -- that is what the API takes and what a person types -- so the
     * name is resolved in the query, which is what lets `find(id)`, told no home
     * at all, still answer with one.
     *
     * LEFT, because a global conversation has a NULL project_id and an inner
     * join would drop every one of them. V6 spends a paragraph arguing that the
     * global tier is the ordinary shape of a caller who did not name a project;
     * an inner join here would delete that tier from every read.
     */
    private static final String COLUMNS =
            "c.id, p.name AS project, c.origin, c.lifecycle, c.parent_id, c.agent,"
                    + " c.created_at, c.last_turn_at, c.budget_total, c.budget_spent,"
                    + " c.budget_lifted, c.turn_cap, c.turn_cap_lifted, c.title";

    private static final String FROM_CONVERSATIONS =
            " FROM conversations c LEFT JOIN projects p ON p.id = c.project_id";

    /*
     * `IS NOT DISTINCT FROM` and not `=`, and the CAST is not decoration —
     * ProposalStore.HOME_MATCHES carries the measurement: the global tier is a
     * NULL project, `NULL = NULL` is NULL, so a plain equality returns zero
     * global rows for ever; and with a bare `?` the driver sends an untyped NULL
     * and Postgres refuses the statement outright.
     *
     * Not shared with that constant because it qualifies a different table's
     * column (`m.project_id`, from a join this query does not make). The rule is
     * the same and the text cannot be.
     *
     * A project id since V14 and not a name. `ProjectIds.toRead` is what builds
     * the parameter, and its javadoc carries the reason it cannot simply be
     * `home.project()` any more: a project nothing has written to has to resolve
     * to a value that matches no row, because the value that matches NULL rows
     * is the global tier.
     */
    private static final String HOME_MATCHES =
            "project_id IS NOT DISTINCT FROM CAST(? AS BIGINT)";

    /**
     * How far up or down a delegation tree either recursive query will walk.
     *
     * <p><b>A stop and not a limit somebody chose.</b> Nothing in this server
     * can build a tree anywhere near this deep — a delegated child is started by
     * a tool call inside a run, so the depth is bounded by how many agents call
     * each other, and {@code AgentRegistry.load} refuses a cycle in the callee
     * graph outright at boot. What this is for is the row a psql session, a
     * later migration or a second writer could leave behind: {@code
     * conversations_nothing_delegated_to_itself} sees the one-row cycle and
     * Postgres cannot express the longer one without a trigger, which V17
     * declines. A recursive query that met a cycle would not fail; it would run
     * for ever holding a connection, and a retention sweep is exactly the caller
     * that would meet it unattended.
     *
     * <p>64 because it is far past anything reachable and far short of anything
     * expensive: the walk is one indexed lookup per level.
     */
    static final int DEEPEST_DELEGATION = 64;

    /*
     * THE STATE OF THE TREE ONE CONVERSATION BELONGS TO, walked up `parent_id`.
     *
     * The non-recursive term is the conversation itself; each recursive step
     * replaces it with its parent, and the walk stops at the row that carries a
     * lifecycle -- which is the root, by
     * conversations_a_root_is_where_the_lifecycle_lives.
     *
     * `depth < ?` IS THE STOP, and it is written as a column carried along
     * rather than as a LIMIT: a LIMIT bounds the rows returned and not the rows
     * visited, so a cycle would still be walked for ever producing them. Postgres
     * has a CYCLE clause that would also do it; a counter says the same thing
     * without asking a reader to know that syntax, and the number it compares
     * against is a constant this file names and explains.
     */
    private static final String ROOTS_STATE = """
            WITH RECURSIVE ancestry(id, depth) AS (
                SELECT id, 1 FROM conversations WHERE id = ?
              UNION ALL
                SELECT parent.id, ancestry.depth + 1
                  FROM ancestry
                  JOIN conversations child ON child.id = ancestry.id
                  JOIN conversations parent ON parent.id = child.parent_id
                 WHERE ancestry.depth < %d
            )
            SELECT c.lifecycle
              FROM ancestry
              JOIN conversations c ON c.id = ancestry.id
             WHERE c.lifecycle IS NOT NULL""".formatted(DEEPEST_DELEGATION);

    /*
     * THE ROOT ITSELF, walked up `parent_id`, and it is ROOTS_STATE with the
     * whole row on the end instead of the one column.
     *
     * The two are kept apart rather than one being written in terms of the
     * other, because the projection is the difference: ROOTS_STATE answers a
     * sweep that wants a state and joins nothing, and this joins `projects` for
     * the home the row carries. A shared constant would give the sweep a join it
     * has no use for on the read it makes most often.
     */
    private static final String ROOT_OF = """
            WITH RECURSIVE ancestry(id, depth) AS (
                SELECT id, 1 FROM conversations WHERE id = ?
              UNION ALL
                SELECT parent.id, ancestry.depth + 1
                  FROM ancestry
                  JOIN conversations child ON child.id = ancestry.id
                  JOIN conversations parent ON parent.id = child.parent_id
                 WHERE ancestry.depth < %d
            )
            SELECT %s%s
              JOIN ancestry ON ancestry.id = c.id
             WHERE c.lifecycle IS NOT NULL""".formatted(
                    DEEPEST_DELEGATION, COLUMNS, FROM_CONVERSATIONS);

    /*
     * EVERY CONVERSATION IN ONE TREE, walked down `parent_id`, the root first.
     *
     * The same depth guard for the same reason, and the same reason it is a
     * counter rather than a LIMIT.
     */
    private static final String TREE_UNDER = """
            WITH RECURSIVE tree(id, depth) AS (
                SELECT id, 1 FROM conversations WHERE id = ?
              UNION ALL
                SELECT child.id, tree.depth + 1
                  FROM tree
                  JOIN conversations child ON child.parent_id = tree.id
                 WHERE tree.depth < %d
            )
            SELECT id FROM tree ORDER BY depth, id""".formatted(DEEPEST_DELEGATION);

    private final JdbcTemplate jdbc;
    private final Supplier<Instant> now;
    private final Supplier<String> idFactory;

    /** Production wiring: the real clock and real ids. */
    public ConversationStore(JdbcTemplate jdbc) {
        this(jdbc, Instant::now, null);
    }

    /**
     * @param jdbc where the rows live
     * @param now the clock, injected so a test can move time between opening a
     *     conversation and ending a turn in it and assert that the two instants
     *     differ — {@code ProposalStore}'s reason exactly
     * @param idFactory id minting, or {@code null} for the default. Injected for
     *     the reason {@code Archive}'s and {@code ProposalStore}'s are: a test
     *     asserting on the order a listing comes back in needs ids it chose
     *     rather than random hex
     */
    public ConversationStore(
            JdbcTemplate jdbc, Supplier<Instant> now, Supplier<String> idFactory) {
        this.jdbc = jdbc;
        this.now = now;
        this.idFactory = idFactory != null ? idFactory : () -> MemoryIds.mint(PREFIX, now.get());
    }

    /**
     * Open a conversation in {@code home} with {@code budget} to spend.
     *
     * <p>The budget is written at whatever it has already spent rather than at
     * zero. Nothing opens a conversation with a part-spent budget today, and the
     * alternative is worse than unused: an INSERT that hardcoded {@code 0} would
     * silently discard a caller's count, and the row would then be the only
     * record of an allowance that had already been drawn on.
     *
     * @param home the project, or {@link Home#global()}. Global is ordinary —
     *     see {@code V6__conversations.sql} — and is stored as a NULL project
     * @param budget the allowance every turn in this conversation spends from
     * @return the row as it was written, with a budget at the same count
     * @throws NullPointerException if either argument is null. Not a {@link
     *     ValidationException}: a null here is a caller that dropped an
     *     argument, not a field a user left out of a request
     */
    public ConversationRecord open(Home home, Budget budget) {
        return open(home, budget, null);
    }

    /**
     * Open a conversation that also decides how many turns each of its turns
     * may take.
     *
     * <p>The overload above states {@code this conversation does not decide the
     * turn cap}, which is the ordinary shape and the one every caller had before
     * there was a middle level to decide it at: the agent answering each turn is
     * capped at what its own definition asks for.
     *
     * @param turnCap the ceiling for every turn in this conversation, {@link
     *     TurnCap#none()} for a conversation whose turns are to run with no cap,
     *     or {@code null} for one that does not decide. The three states are the
     *     three the columns hold; see {@code V10__conversation_turn_cap.sql},
     *     which refuses the fourth
     */
    public ConversationRecord open(Home home, Budget budget, TurnCap turnCap) {
        return open(home, budget, turnCap, null);
    }

    /**
     * The same, naming the account that owns it — the one a hook's {@code notify} reaches (spec
     * 2026-09-28-hooks-reach-the-log decision 8).
     *
     * @param owner the signed-in account opening it, or {@code null} when the request carried none
     */
    public ConversationRecord open(Home home, Budget budget, TurnCap turnCap, String owner) {
        Objects.requireNonNull(budget, "budget");
        return write(Origin.TURN, home, null, null, budget, turnCap, owner, null);
    }

    /**
     * Open the conversation a run that is nobody's turn keeps its log in.
     *
     * <h2>The door every other run comes through, and why it is not {@link
     * #open}</h2>
     *
     * <p>{@link #open} is a person opening a conversation to speak into: it
     * takes the allowance somebody chose, it names no agent, and what it writes
     * is the row a listing shows. This is the machine's version — a delegated
     * child, a curator's ruling, a submission — and every one of those three
     * differs from a person's conversation in the same three ways, which is why
     * they share one method and not one method each.
     *
     * <p><b>The allowance is where this is dangerous and where it is settled.</b>
     * A {@link Origin#DELEGATION} spends its parent's {@link Budget} by
     * reference and a {@link Origin#CURATOR} ruling spends the pass's, so
     * passing one in here would write a second copy of a shared object: two rows
     * at the same total, and every model call counted twice by the first thing
     * to sum the column. So {@code owned} must be null for exactly those two,
     * and this method refuses the other combinations rather than resolving them
     * — {@code conversations_an_allowance_is_owned_or_shared} refuses them again
     * one layer down, which is this project's usual doubling.
     *
     * @param origin which door. Never {@link Origin#TURN}: that is {@link
     *     #open}'s, it takes a turn cap and an allowance somebody chose, and a
     *     caller reaching this method with it would be opening a person's
     *     conversation with no agent through the door for one agent's run
     * @param home the tier this run answers from
     * @param agent the agent whose run this is. Required, because every origin
     *     this door writes is one agent's from end to end — {@code
     *     conversations_a_person_s_conversation_names_no_agent}
     * @param parent the conversation that delegated to this one, or {@code null}.
     *     Required for {@link Origin#DELEGATION} and refused for everything
     *     else, which is the same predicate the database holds
     * @param owned the allowance this conversation owns, or {@code null} when it
     *     spends one it does not. {@link Origin#ownsItsAllowance()} decides
     *     which, and a mismatch is refused here
     * @return the row as it was written
     */
    public ConversationRecord log(
            Origin origin, Home home, String agent, String parent, Budget owned) {
        return log(origin, home, agent, parent, owned, null);
    }

    /**
     * {@link #log(Origin, Home, String, String, Budget)}, naming the account that owns the log.
     *
     * @param owner the caller's handle, or {@code null}. A delegated child given none inherits its
     *     parent's, in the INSERT itself
     */
    public ConversationRecord log(
            Origin origin, Home home, String agent, String parent, Budget owned, String owner) {
        return log(origin, home, agent, parent, owned, owner, null);
    }

    /**
     * {@link #log(Origin, Home, String, String, Budget, String)}, naming the tool call that
     * opened this child (spec 2026-09-29 §2.3). {@code openedBy} is refused on a root: only a
     * delegation has a call above it.
     */
    public ConversationRecord log(
            Origin origin, Home home, String agent, String parent, Budget owned, String owner,
            String openedBy) {
        Objects.requireNonNull(origin, "origin");
        if (origin == Origin.TURN) {
            throw new IllegalArgumentException(
                    "a conversation of origin 'turn' is a person's and is opened with open(),"
                            + " which takes the allowance they chose and names no agent; this"
                            + " door writes the log of one agent's run");
        }
        if (agent == null || agent.isBlank()) {
            throw new ValidationException(
                    "field 'agent' is required and must not be empty: a " + origin.wireName()
                            + " conversation is one agent's run from end to end, and a row that"
                            + " did not say which is one nothing can resume or price");
        }
        if ((origin == Origin.DELEGATION) != (parent != null)) {
            throw new IllegalArgumentException(origin == Origin.DELEGATION
                    ? "a delegated child names the conversation that delegated to it; without it"
                            + " the tree has a severed branch and nothing can resolve this run"
                            + " to the root whose origin decides them all"
                    : "a " + origin.wireName() + " conversation is a root and has no delegator;"
                            + " only a delegation names a parent");
        }
        if (origin.ownsItsAllowance() != (owned != null)) {
            throw new IllegalArgumentException(origin.ownsItsAllowance()
                    ? "a " + origin.wireName() + " conversation owns the allowance it spends, so"
                            + " it is written with one"
                    : "a " + origin.wireName() + " conversation spends an allowance it does not"
                            + " own -- a delegated child spends its parent's by reference, a"
                            + " curator's ruling spends the pass's -- so its row holds no budget"
                            + " at all. Writing one here would be a second copy of a shared"
                            + " object, and the first thing to sum the column would count every"
                            + " model call twice");
        }
        if (openedBy != null && parent == null) {
            throw new IllegalArgumentException(
                    "a root conversation was opened by no call; only a delegation names one");
        }
        // No turn cap. A cap is a conversation-level decision a person takes
        // about their own turns; one agent's run is capped by TurnCap.from(its
        // definition), resolved where the run is started, and a column here
        // would be a second answer to a question that already has one.
        return write(origin, home, agent, parent, owned, null, owner, openedBy);
    }

    private ConversationRecord write(
            Origin origin, Home home, String agent, String parent, Budget budget,
            TurnCap turnCap, String owner, String openedBy) {
        Objects.requireNonNull(home, "home");
        Instant at = now.get();
        String id = idFactory.get();
        // Three states and never a fourth, the same shape the turn cap takes
        // four lines down. `limit()` is asked only of a budget that has one --
        // it refuses an uncapped one rather than answering with a number that
        // is not there -- so the ceiling is read behind `capped()`, and the
        // count is read either way, because a conversation with no ceiling
        // still has spending and that is the whole of what V31 separates.
        boolean budgetLifted = budget != null && !budget.capped();
        Integer total = budget == null || budgetLifted ? null : budget.limit();
        Integer spent = budget == null ? null : budget.spent();
        String originName = origin.wireName();
        // Two columns and never both, exactly as the CHECK below them says. A
        // cap that names a number is a number and no lifting; a lifted one is
        // the boolean and no number; a conversation that does not decide is
        // neither.
        Integer cap = turnCap != null && turnCap.capped() ? turnCap.turns() : null;
        boolean lifted = turnCap != null && !turnCap.capped();
        // The lifecycle lives on the ROOT of a tree, so a delegated child is
        // written with none at all and resolves to its root's through
        // lifecycleOf. Not "the parent's value copied down", which is the same
        // thing until somebody archives the root and the copies are a set of
        // rows that disagree with it -- see ConversationLifecycle, and
        // conversations_a_root_is_where_the_lifecycle_lives, which refuses the
        // copy rather than trusting this line.
        //
        // ACTIVE and not a caller's choice: a conversation that has been opened
        // has got exactly as far as being open, whichever door it came through.
        // Every other state is somewhere it is MOVED to, by a person or by a
        // sweep, and moveTo is the one door for that.
        String state = parent == null ? ConversationLifecycle.ACTIVE.wireName() : null;
        ArchiveUnavailableException.translating("open a conversation",
                () -> jdbc.update(
                        "INSERT INTO conversations"
                                + " (id, project_id, origin, lifecycle, parent_id, agent,"
                                + " created_at, last_turn_at, budget_total, budget_spent,"
                                + " budget_lifted, turn_cap, turn_cap_lifted, opened_by_call,"
                                + " owner_handle)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?,"
                                // The named owner, or a delegated child's parent's (amendment 2).
                                + " COALESCE(CAST(? AS TEXT), (SELECT p.owner_handle"
                                + " FROM conversations p WHERE p.id = CAST(? AS TEXT))))",
                        // Registered before the row that references it, and
                        // inside this supplier so that a database which is down
                        // is reported as "open a conversation": `project_id` is
                        // a foreign key now, and V6's promise that talking never
                        // waits on somebody having run `define` is kept by
                        // writing the project down rather than refusing.
                        id, ProjectIds.toWrite(jdbc, home), originName, state, parent, agent,
                        utc(at), total, spent, budgetLifted, cap, lifted, openedBy, owner,
                        parent));
        // No title, and it is not a field this door takes. A conversation is
        // named by the first thing said in it, and nothing has been said in this
        // one yet -- `nameFromFirstTurn` is what fills the column, from
        // `TurnStore.record`, and until it runs the null here is exactly true.
        return new ConversationRecord(id, home, origin,
                state == null ? null : ConversationLifecycle.ACTIVE, parent, agent, at, null,
                readBudget(total, spent, budgetLifted), readBack(cap, lifted), null);
    }

    /**
     * One conversation, if this server has it.
     *
     * <p>{@link Optional} and not a refusal, the same choice {@link
     * ProjectStore#find} makes and the opposite of {@code JobStore.get}'s: an id
     * a caller holds may be one a person typed, and "there is no conversation
     * here" is an ordinary answer to that.
     */
    public Optional<ConversationRecord> find(String id) {
        String wanted = named(id);
        List<ConversationRecord> found = ArchiveUnavailableException.translating(
                "read a conversation by id",
                () -> jdbc.query(
                        "SELECT " + COLUMNS + FROM_CONVERSATIONS + " WHERE c.id = ?",
                        ROW_MAPPER, wanted));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** The account a hook's notify reaches, or empty for a log nobody owns (decision 8). */
    public Optional<String> ownerOf(String id) {
        String wanted = named(id);
        return ArchiveUnavailableException.translating("read a conversation's owner",
                () -> jdbc.query("SELECT owner_handle FROM conversations"
                                + " WHERE id = ? AND owner_handle IS NOT NULL",
                        (rs, row) -> rs.getString(1), wanted).stream().findFirst());
    }

    /** The log's fixed opening, whole, or empty for a log with none (decision 9). */
    public Optional<String> opening(String id) {
        String wanted = named(id);
        return ArchiveUnavailableException.translating("read a log's opening",
                () -> jdbc.query("SELECT b.body FROM conversations c"
                                + " JOIN system_blocks b ON b.hash = c.opening_block WHERE c.id = ?",
                        (rs, row) -> rs.getString(1), wanted).stream().findFirst());
    }

    /**
     * Fix the log's opening to a stored block, once.
     *
     * @param block a {@link TurnStore#remember} hash
     * @return whether this call wrote it; false when the log already has one, which never changes
     */
    public boolean fixOpening(String id, String block) {
        String wanted = named(id);
        Objects.requireNonNull(block, "block");
        return ArchiveUnavailableException.translating("fix a log's opening",
                () -> jdbc.update("UPDATE conversations SET opening_block = ?"
                        + " WHERE id = ? AND opening_block IS NULL", block, wanted)) == 1;
    }

    /**
     * Pin the log to the local hook set it opened with, once (spec
     * 2026-09-30-local-hooks-are-served decision 3): {@link #fixOpening}'s {@code IS NULL}-guarded
     * write, because every fire in the log must see the same set.
     *
     * @param hash a {@link LocalHookSetStore#remember} hash
     * @return whether this call wrote it; false when the log already has one, which never changes
     */
    public boolean pinLocalHooks(String id, String hash) {
        String wanted = named(id);
        Objects.requireNonNull(hash, "hash");
        return ArchiveUnavailableException.translating("pin a log's local hooks",
                () -> jdbc.update("UPDATE conversations SET local_hooks = ?"
                        + " WHERE id = ? AND local_hooks IS NULL", hash, wanted)) == 1;
    }

    /** The local hook set this log opened with, or empty for a log with none (decision 4). */
    public Optional<String> localHooksOf(String id) {
        String wanted = named(id);
        return ArchiveUnavailableException.translating("read a log's local hooks",
                () -> jdbc.query("SELECT local_hooks FROM conversations"
                                + " WHERE id = ? AND local_hooks IS NOT NULL",
                        (rs, row) -> rs.getString(1), wanted).stream().findFirst());
    }

    /** Claim the log's {@code log.close}: true for the one caller that closed it. */
    public boolean closeLog(String id, Instant at) {
        String wanted = named(id);
        Objects.requireNonNull(at, "at");
        return ArchiveUnavailableException.translating("close a log",
                () -> jdbc.update("UPDATE conversations SET log_closed_at = ?"
                        + " WHERE id = ? AND log_closed_at IS NULL", utc(at), wanted)) == 1;
    }

    /**
     * Every conversation in one home that <b>a person opened</b>, oldest first.
     *
     * <h2>The filter, which this read did not need until V17</h2>
     *
     * <p>Until every run got a conversation, {@link #open} was this table's only
     * writer and a home's rows and a person's rows were the same set. A
     * delegated child, a curator's ruling and a submission are all conversations
     * now — a curator pass over a few hundred memories is a few hundred of them
     * — so an unfiltered listing would be a machine's log with somebody's own
     * conversations somewhere in it.
     *
     * <p><b>On {@code origin} and not on "has no parent"</b>, which is the whole
     * reason {@link Origin} is a column rather than something inferred: a {@link
     * Origin#SUBMISSION} is parentless too and is not a person's conversation.
     *
     * <p>The value is spliced rather than bound, which is the one place in this
     * class that happens. It is {@link Origin#TURN}'s own {@code wireName()} —
     * an enum constant, never a caller's string — so there is nothing here for a
     * parameter to protect against, and binding it would put a {@code ?} in a
     * query whose answer is the same on every call.
     *
     * <p>Ordered by {@code created_at} and then by {@code id}, not by {@code id}
     * alone. The ids sort by minting time, but only to the millisecond the clock
     * gave them, and the clock is injected — two conversations opened at one
     * instant in a test are separated by three random bytes and nothing else, so
     * {@code ORDER BY id} would be a promise the fixture decides. The tiebreak is
     * kept for the reason {@code ReasonLog.forMemory} keeps its: {@code
     * created_at} alone leaves rows sharing an instant unordered, which an
     * injected clock produces routinely.
     *
     * <p><b>This is the query the whole {@code project_id} column exists for</b>,
     * and the global tier is one of its answers rather than a hole in it: {@link
     * Home#global()} reads the rows with no project at all, through {@code IS
     * NOT DISTINCT FROM}. A name no project has is the other end of that and is
     * not the same answer — see {@code ProjectIds}, which is what stops a typo
     * being answered with every global conversation on the server.
     */
    public List<ConversationRecord> inHome(Home home) {
        return inHome(home, ConversationLifecycle.ACTIVE);
    }

    /**
     * The same listing, of the conversations a person has put in one state.
     *
     * <h2>Why the lifecycle filters here at all</h2>
     *
     * <p>Archiving has to bite or it is decorative, and the listing is where a
     * person sees whether it did. {@code Turn.speak} refusing an utterance is
     * the half that stops work; this is the half that takes the row off the
     * screen. The two are not interchangeable — a state that only filtered a
     * listing would be exactly the label the design warns about, and a state
     * that only refused turns would leave a person looking at conversations
     * they had put away and could not use.
     *
     * <p><b>It composes with the origin filter rather than replacing it</b>, and
     * the two answer different questions: {@code origin} decides <em>whose</em>
     * conversation this is — a person's, not a machine's log — and the lifecycle
     * decides <em>where it has got to</em>. A machine's log is left out of this
     * listing whatever state it is in, and a person's archived conversation is
     * left out of the default listing while still being a person's. Both clauses
     * are spliced rather than bound, for the reason the origin one already was:
     * each is an enum constant's own {@code wireName()} and never a caller's
     * string, so there is nothing for a parameter to protect against.
     *
     * <p><b>Every row this answers with is a root</b>, because {@link
     * Origin#TURN} is, so the state is read off the row and no walk is needed.
     *
     * @param showing which state to list. {@link ConversationLifecycle#ACTIVE} is what a
     *     console opens on; the other three are how a person finds a
     *     conversation again after putting it away. <b>A state that can be
     *     entered and not left is not reversible in practice</b> — {@code
     *     Lifecycle.ARCHIVED} is documented as reversible, and unarchiving needs
     *     an id somebody can still get hold of
     */
    public List<ConversationRecord> inHome(Home home, ConversationLifecycle showing) {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(showing, "showing");
        return ArchiveUnavailableException.translating("list a home's conversations",
                () -> jdbc.query(
                        "SELECT " + COLUMNS + FROM_CONVERSATIONS + " WHERE " + HOME_MATCHES
                                + " AND origin = '" + Origin.TURN.wireName() + "'"
                                + " AND lifecycle = '" + showing.wireName() + "'"
                                + " ORDER BY c.created_at, c.id",
                        ROW_MAPPER, ProjectIds.toRead(jdbc, home)));
    }

    /**
     * The newest conversation in one home that this agent has answered in, which
     * is the one a bot continues.
     *
     * <h2>The agent is on the turn, and it cannot be on this row</h2>
     *
     * <p>The design this read comes from said "the newest in this tier whose
     * {@code agent} is the bot", meaning this table's own {@code agent} column
     * — and that column is NULL for exactly the rows wanted. {@code
     * conversations_a_person_s_conversation_names_no_agent} is a CHECK that
     * {@code (origin = 'turn') = (agent IS NULL)}: a person may put two agents'
     * turns in one conversation, so the row names none and {@code turns.agent}
     * names who answered each turn. V17 spends a section on that split and this
     * read is the first caller to depend on which side of it holds what.
     *
     * <p>So the match is an {@code EXISTS} over {@code turns}, and what it means
     * is exactly what it says: <b>a conversation this agent has spoken in</b>.
     * That is a slightly wider claim than "this bot's conversation" and there is
     * no narrower one to make — nothing records who a conversation was opened
     * <em>for</em>, and a conversation holding two agents' turns is a shape the
     * schema deliberately allows. A bot talked to from this client cannot
     * produce one, because who answers is settled at sign-in and every turn of
     * the session names it.
     *
     * <h2>The three filters, each of which is another read's rule</h2>
     *
     * <ul>
     *   <li><b>the tier</b>, as {@link #inHome} filters it and through the same
     *       {@code IS NOT DISTINCT FROM}, so the global tier is an answer and
     *       not a hole;
     *   <li><b>{@link Origin#TURN}</b>, so a delegated child is not offered as
     *       somewhere to carry on talking. It is the one row that would pass a
     *       turns-only match, since a child names the agent on its own row as
     *       well as on its turn;
     *   <li><b>{@link ConversationLifecycle#ACTIVE}</b>, because {@code
     *       Turn.speak} refuses to speak into an archived conversation — an
     *       archived one offered here would be an id whose every utterance comes
     *       back refused.
     * </ul>
     *
     * <p><b>Newest by {@code created_at} and then by {@code id}, descending</b>
     * — {@link #inHome}'s ordering read backwards, and the tiebreak is kept for
     * its reason: the clock is injected and two conversations opened at one
     * instant are separated by three random bytes, so {@code ORDER BY id} alone
     * would be a promise a fixture decides. <b>The shared ordering is not
     * changed</b>: this is a separate read, so the console's opening screen goes
     * on listing oldest first.
     *
     * <p><b>{@code LIMIT 1} here rather than a client taking the last of a
     * list.</b> With no way to branch there is only ever one to continue, and
     * answering the question the caller asked is what keeps a terminal from
     * re-sorting a tier it was handed.
     *
     * @param home the tier to look in
     * @param agent the agent whose conversation is wanted
     * @return the conversation to continue, or empty when this agent has never
     *     answered in this tier — an ordinary state, and the one a first message
     *     opens a conversation out of
     * @throws ValidationException if the agent cannot be named
     */
    public Optional<ConversationRecord> latestFor(Home home, String agent) {
        Objects.requireNonNull(home, "home");
        if (agent == null || agent.isBlank()) {
            // Refused rather than run. A blank matches no turn, so the query
            // would answer "no conversation yet" to a caller that named nobody
            // — and the caller would open a second conversation on the strength
            // of it, every time.
            throw new ValidationException("field 'agent' is required and must not be empty");
        }
        List<ConversationRecord> found = ArchiveUnavailableException.translating(
                "find the conversation to continue",
                () -> jdbc.query(
                        "SELECT " + COLUMNS + FROM_CONVERSATIONS + " WHERE " + HOME_MATCHES
                                + " AND c.origin = '" + Origin.TURN.wireName() + "'"
                                + " AND c.lifecycle = '"
                                + ConversationLifecycle.ACTIVE.wireName() + "'"
                                + " AND EXISTS (SELECT 1 FROM turns t"
                                + " WHERE t.conversation_id = c.id AND t.agent = ?)"
                                + " ORDER BY c.created_at DESC, c.id DESC LIMIT 1",
                        ROW_MAPPER, ProjectIds.toRead(jdbc, home), agent));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * Where the tree this conversation belongs to has got to.
     *
     * <h2>The walk, which is what "children resolve to the root" costs</h2>
     *
     * <p>A root answers off its own column. A delegated child has none — {@code
     * conversations_a_root_is_where_the_lifecycle_lives} refuses it one — so
     * this walks up {@code parent_id} until it reaches the row that carries a
     * state. <b>That walk is the price of the property, and the property is that
     * an archived root cannot have live branches</b>: with a state on every row
     * this method would be a column read and the divergence would be a row
     * somebody forgot to update.
     *
     * <p><b>Bounded, and the bound is not decoration.</b> {@code
     * conversations_nothing_delegated_to_itself} stops the one-row cycle, and a
     * longer one cannot be produced by this server because a child's id is
     * minted after its parent's is read — but a recursive query that met one
     * would not fail, it would run for ever holding a connection. {@link
     * #DEEPEST_DELEGATION} is what turns that into a query that stops.
     *
     * @param id the conversation asked about, root or child alike
     * @return the state, or empty if no conversation has that id — and also, on
     *     a tree deeper than the bound, which is a database that has a cycle in
     *     it rather than an answer this method may guess at
     * @throws ValidationException if the id cannot be named
     */
    /**
     * The root of the tree this conversation belongs to, itself included.
     *
     * <h2>{@link #lifecycleOf}'s walk, answering with the row instead of the
     * column</h2>
     *
     * <p>Two callers want two different things off the same walk and it is worth
     * two methods rather than one that answers with more than it was asked. A
     * retention sweep wants the state and nothing else; the learning window
     * wants the state <em>and</em> which tree it belongs to, because ranking a
     * shortlist of conversations by their trees means being able to say that two
     * of them are the same tree.
     *
     * <p>The same recursion, the same depth stop, and the same reason it is a
     * counter rather than a LIMIT — see {@link #DEEPEST_DELEGATION}. It ends on
     * the row carrying a lifecycle, which is the root by {@code
     * conversations_a_root_is_where_the_lifecycle_lives}, so the returned record
     * always has a non-null {@link ConversationRecord#lifecycle()}.
     *
     * @param id the conversation asked about, root or child alike. A root
     *     answers with itself, in one indexed lookup
     * @return the root, or empty for an id nothing holds — and for a tree deeper
     *     than the bound, which is a database with a cycle in it rather than an
     *     answer this method may guess at
     * @throws ValidationException if the id cannot be named
     */
    public Optional<ConversationRecord> rootOf(String id) {
        String wanted = named(id);
        return ArchiveUnavailableException.translating("read the root of a conversation tree",
                () -> jdbc.query(ROOT_OF, ROW_MAPPER, wanted).stream().findFirst());
    }

    public Optional<ConversationLifecycle> lifecycleOf(String id) {
        String wanted = named(id);
        return ArchiveUnavailableException.translating("read a conversation's lifecycle",
                () -> jdbc.query(ROOTS_STATE, (rs, rowNum) -> ConversationLifecycle.of(rs.getString(1)),
                        wanted).stream().findFirst());
    }

    /**
     * Every conversation in the tree rooted at {@code rootId}, the root itself
     * first.
     *
     * <h2>Downward, where {@link #lifecycleOf} goes up</h2>
     *
     * <p>Ejection acts on a tree: the root is what carries the state, and the
     * payloads that have to go are in every conversation under it. A delegated
     * child's tool results are the same file bodies its parent's are — {@code
     * code_reviewer} reading a repository is exactly the run that stores the
     * most — so a sweep that ejected only the root's payloads would leave the
     * larger half behind and mark the tree done.
     *
     * <p><b>Root first, then breadth</b>, which is the order the recursion
     * produces and is worth having: an exporter writing a tree out puts the
     * conversation somebody started at the top of the manifest.
     *
     * @param rootId the root. A child's id answers with the subtree under that
     *     child, which is a true answer to a question nothing in this server
     *     asks — the sweep resolves to roots first
     * @return the ids, empty for an id nothing holds
     */
    public List<String> treeOf(String rootId) {
        String wanted = named(rootId);
        return ArchiveUnavailableException.translating("read a conversation tree",
                () -> jdbc.queryForList(TREE_UNDER, String.class, wanted));
    }

    /**
     * Every root in one state, whatever its origin.
     *
     * <p>What the eject stage of a sweep reads. <b>No origin</b>, and that is
     * the difference between the two stages: <em>marking</em> is per-origin
     * because the policy is, and <em>ejecting</em> is not, because by then
     * somebody or something has already decided about this conversation. A
     * person marks a {@link Origin#TURN} conversation by hand and a policy marks
     * a curator trace by age; both are then the same job, and an eject stage
     * that asked the origin again would be re-deciding a decision that has been
     * taken.
     */
    public List<String> rootsAt(ConversationLifecycle state) {
        Objects.requireNonNull(state, "state");
        return ArchiveUnavailableException.translating("select conversations for retention",
                () -> jdbc.queryForList(
                        "SELECT id FROM conversations WHERE lifecycle = ?"
                                + " ORDER BY created_at, id",
                        String.class, state.wireName()));
    }

    /**
     * The roots of one origin, in one state, last touched before {@code before}.
     *
     * <p>What a retention sweep selects. <b>{@code COALESCE(last_turn_at,
     * created_at)} and not one of them</b>: a curator's ruling and a person's
     * conversation both stamp a last turn, and a submission's row is written
     * once and never updated — {@code Turn.requireResumable} carries the reason
     * — so a rule reading {@code last_turn_at} alone would find every submission
     * ageless and never select one, and a rule reading {@code created_at} alone
     * would count a conversation somebody spoke into yesterday as old because it
     * was opened last year.
     *
     * <p>Roots only, which the {@code lifecycle IS NOT NULL} in the index and
     * the equality here both say: a child is reached through its root and never
     * selected on its own, or a tree would be ejected in pieces by whichever
     * sweep happened to see each piece.
     *
     * @param origin whose policy is being applied
     * @param state where those roots have got to now
     * @param before the instant a root has to be older than, or {@code null} for
     *     no age condition at all — which is what the eject stage wants, since a
     *     conversation already marked has had its age decided
     */
    public List<String> roots(Origin origin, ConversationLifecycle state, Instant before) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(state, "state");
        return ArchiveUnavailableException.translating("select conversations for retention",
                () -> jdbc.queryForList(
                        "SELECT id FROM conversations WHERE lifecycle = ? AND origin = ?"
                                + " AND (CAST(? AS TIMESTAMPTZ) IS NULL"
                                + " OR COALESCE(last_turn_at, created_at) < CAST(?"
                                + " AS TIMESTAMPTZ))"
                                + " ORDER BY created_at, id",
                        String.class, state.wireName(), origin.wireName(),
                        before == null ? null : utc(before), before == null ? null : utc(before)));
    }

    /**
     * Move one conversation's tree to {@code target}, or refuse and say why.
     *
     * <h2>A compare-and-set, and the transition table is the comparison</h2>
     *
     * <p>{@link #turnEnded}'s shape exactly, for {@link #turnEnded}'s reason: the
     * database is the authority, not a read the caller took a moment ago. The
     * {@code WHERE} names every state {@link ConversationLifecycle#reachedFrom()} allows the
     * move to come from, so a second caller that got there first leaves this one
     * changing no row rather than both landing — and a move nothing allows, such
     * as anything out of {@link ConversationLifecycle#EJECTED}, names an empty set and can
     * never match.
     *
     * <p><b>{@code parent_id IS NULL} is in the WHERE and is not redundant.</b>
     * A child's column is NULL, so it would match no state name anyway; saying
     * it here is what makes the refusal below able to tell "this is a child" from
     * "this root is somewhere else", which are opposite corrections.
     *
     * <p>The states are spliced rather than bound — enum constants' own
     * {@code wireName()}, never a caller's string, the same judgement {@link
     * #inHome} makes about the origin.
     *
     * @param id the root of the tree to move. A child is refused rather than
     *     redirected: moving something on a caller's behalf that they did not
     *     name is how a person archives one conversation and finds another one
     *     gone
     * @param target where to move it
     * @return the row as it now stands
     * @throws ArchiveException if no conversation has that id — which is a
     *     caller naming something that is not there, and 404 at the door
     * @throws ArchiveRefusedException if it is a delegated child, if it is the
     *     {@code conductor_conversation} of an orchestration still running, or if
     *     the move is not one this table allows from where the row actually is.
     *     All three are a row that IS there and a caller who is wrong about it,
     *     which is what 409 means; and they are separate sentences rather than
     *     one because an operator does entirely different things about them —
     *     "you named a branch, name the tree", "that run has not ended yet", and
     *     "somebody got there first, or you have asked for an edge that does not
     *     exist"
     */
    public ConversationRecord moveTo(String id, ConversationLifecycle target) {
        String wanted = named(id);
        Objects.requireNonNull(target, "target");
        String from = target.reachedFrom().stream()
                .map(state -> "'" + state.wireName() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        int rows = ArchiveUnavailableException.translating("move a conversation's lifecycle",
                () -> jdbc.update(
                        "UPDATE conversations SET lifecycle = ? WHERE id = ?"
                                + " AND parent_id IS NULL"
                                // A live orchestration's own conversation cannot be archived or
                                // ejected out from under it -- expressed in SQL only, on purpose:
                                // this class must not import the orchestrations package, so the
                                // guard is a NOT EXISTS against the table rather than a call to
                                // OrchestrationStore.
                                + " AND NOT EXISTS (SELECT 1 FROM orchestrations o"
                                + " WHERE o.conductor_conversation = conversations.id"
                                + " AND o.ended_at IS NULL)"
                                + (from.isEmpty() ? " AND FALSE" : " AND lifecycle IN (" + from
                                        + ")"),
                        target.wireName(), wanted));
        if (rows == 0) {
            throw refusal(wanted, target);
        }
        return find(wanted).orElseThrow(() -> new ArchiveException(
                "conversation " + wanted + " was updated and is no longer there"));
    }

    /**
     * Which of the three it was, asked only once the write has already declined
     * — {@link #turnEnded}'s ordering, so the common path is one statement.
     */
    private ArchiveException refusal(String id, ConversationLifecycle target) {
        ConversationRecord row = find(id).orElse(null);
        if (row == null) {
            return new ArchiveException("no conversation has the id " + id);
        }
        if (row.lifecycle() == null) {
            return new ArchiveRefusedException("conversation " + id + " is a delegated child of "
                    + row.parentId() + ", and a lifecycle belongs to the root of a tree rather"
                    + " than to one conversation in it -- one state per tree is what stops an"
                    + " archived root having live branches. Move " + row.parentId() + " instead,"
                    + " or the root above it, and this run goes with it.");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM orchestrations WHERE conductor_conversation = ?"
                        + " AND ended_at IS NULL)",
                Boolean.class, id))) {
            return new ArchiveRefusedException("conversation " + id + " is the conversation of a"
                    + " live orchestration; it can be archived once that run has ended");
        }
        return new ArchiveRefusedException("conversation " + id + " is "
                + row.lifecycle().wireName()
                + " and cannot become " + target.wireName() + " from there. What may become "
                + target.wireName() + " is: "
                + (target.reachedFrom().isEmpty()
                        ? "nothing -- " + target.wireName() + " is terminal"
                        : target.reachedFrom().stream().map(ConversationLifecycle::wireName)
                                .collect(java.util.stream.Collectors.joining(", ")))
                + ".");
    }

    /**
     * Record what a turn spent, and stamp when it ended.
     *
     * <p>One conditional UPDATE, and the condition is the rule: {@code
     * budget_spent} may rise and may stay where it is, and may never fall. A
     * plain {@code SET budget_spent = ?} would let a caller holding a stale
     * {@link ConversationRecord} — one read before another turn ran — hand back
     * calls that have already been made, and the row would come back a plausible
     * number that is simply wrong. The database is the authority on how much of
     * a conversation has been used, exactly as {@code ProposalStore.resolve}
     * makes it the authority on whether a proposal is still waiting.
     *
     * <p><b>The ceiling is not restated here.</b> {@code
     * conversations_spent_within_budget} refuses a write past the total, and
     * {@link Budget#trySpend} refuses to produce one; a third copy of the same
     * predicate in this WHERE clause would be a line no fixture could reach,
     * because the two above it fire first.
     *
     * <p><b>The total is written as well as the spending, and that sentence
     * used to say the opposite.</b> It read "a conversation's total is what it
     * was opened with, and a turn that could change it would be a turn that
     * grants itself more" — and the second half of that is still exactly right,
     * which is why the first half had to change rather than the rule. A turn
     * cannot change the total: nothing a model does moves a {@link Budget}'s
     * limit, and no tool takes one. <b>An operator can</b>, through {@code POST
     * /v1/jobs/&#123;id&#125;/limits}, on the live object this conversation's
     * turn is spending — and a raise that was not written here would be granted
     * for one turn and silently gone at the next, with the row still saying what
     * somebody decided against.
     *
     * <p>That cannot write a total below the row's spending, which would break
     * {@code conversations_spent_within_budget}: the endpoint refuses to lower a
     * budget under what has already been spent, naming cancellation as the verb
     * for stopping a run, so the number arriving here is never one this table
     * would refuse.
     *
     * <p><b>A budget with no ceiling writes the absence of one</b>, rather than
     * this method reaching for a limit that is not there — {@link
     * Budget#limit()} refuses an uncapped budget instead of answering with a
     * number nobody set. The count is written exactly as it is for every other
     * conversation, and the condition on it does not change: spending rises or
     * stays, ceiling or no ceiling, so a stale caller is caught the same way.
     *
     * @param id the conversation
     * @param budget the allowance as the turn left it, at the limit it now
     *     stands at, or with none if the ceiling was lifted
     * @return the row as it now stands
     * @throws ArchiveException if no conversation has that id, or if this write
     *     would lower its spending. Two situations and two sentences: the first
     *     is a caller naming something that is not there, the second is a caller
     *     naming something that is and being out of date about it, and an
     *     operator does entirely different things about them
     */
    public ConversationRecord turnEnded(String id, Budget budget) {
        String wanted = named(id);
        Objects.requireNonNull(budget, "budget");
        Instant at = now.get();
        int spent = budget.spent();
        // The ceiling is asked for only when there is one. An uncapped budget
        // refuses `limit()` rather than answering with a number nobody set, so
        // the write puts the absence in the row -- no total and the boolean --
        // which is the same three-state encoding `write` uses on the way in.
        //
        // The guard below is unchanged and is right for both: spending may rise
        // and may stay where it is and may never fall, whether or not anything
        // is stopping it, and it is what makes this write safe against a caller
        // holding a record read before another turn ran.
        boolean lifted = !budget.capped();
        Integer total = lifted ? null : budget.limit();
        int rows = ArchiveUnavailableException.translating("record the end of a turn",
                () -> jdbc.update(
                        "UPDATE conversations SET budget_total = ?, budget_spent = ?,"
                                + " budget_lifted = ?, last_turn_at = ?"
                                + " WHERE id = ? AND budget_spent <= ?",
                        total, spent, lifted, utc(at), wanted, spent));
        if (rows == 0) {
            // Which of the two it was, asked only once the write has already
            // declined — so the common path is one statement, and the second
            // read happens where somebody is about to read a message anyway.
            throw find(wanted)
                    .map(stale -> new ArchiveException("conversation " + wanted + " has already"
                            + " spent " + stale.budget().spent() + " model calls, and this turn"
                            + " reports " + spent + "; a conversation's spending does not go"
                            + " down, so this write is holding a record read before another"
                            + " turn ran"))
                    .orElseGet(() -> new ArchiveException(
                            "no conversation has the id " + wanted));
        }
        return find(wanted).orElseThrow(() -> new ArchiveException(
                "conversation " + wanted + " was updated and is no longer there"));
    }

    /**
     * The refusal an id gets before it reaches SQL.
     *
     * <p>Blank and null are one message here where {@code ProjectStore.named}
     * makes them two, and the difference is what the value is: a project name is
     * something a person types and mistypes, so "you left it out" and "you
     * padded it" are different mistakes with different repairs. A conversation
     * id is minted by this class and carried around by machines, so every way of
     * arriving here with an unusable one is the same fault — something dropped
     * it.
     *
     * <p><b>Package-private rather than private, because {@link TurnStore} asks
     * the same question about the same identifier.</b> A second copy of this
     * method would be a second sentence about one situation, and the two would
     * drift the first time either was reworded — the correction-applied-to-one-
     * copy failure this project keeps sweeping for. It stays here rather than
     * moving to {@link Validation}, whose subject is the shape of a write
     * proposal, and it stays out of the public surface because the two callers
     * are the two stores of this one id.
     */
    static String named(String id) {
        if (id == null || id.isBlank()) {
            throw new ValidationException(
                    "field 'conversation' is required and must not be empty");
        }
        return id;
    }

    // --- the name a conversation is known by ---------------------------------

    /*
     * The whole of the write, and its shape IS the design.
     *
     * `AND title IS NULL` is what makes "the first turn names it" true. Without
     * it this would have to know that it is writing the FIRST turn -- which
     * means reading the ordinal, or reading the row, and either way holding two
     * statements open around a decision. With it, the caller knows nothing: it
     * offers a name on every turn, and the row accepts exactly the first one.
     *
     * NOT A READ-THEN-WRITE, and the difference is not merely a saved round
     * trip. Two turns landing together would both read a null title and both
     * write, and the row would end up holding whichever arrived second -- a
     * conversation renamed by a race, intermittently, in a way no fixture could
     * be made to reproduce. Here the second UPDATE matches no row and does
     * nothing, which is the same answer it gives to the hundredth turn.
     *
     * NOTHING RENAMES A CONVERSATION, so there is no second statement that can
     * write this column. That is deliberate: a title derived from the first
     * thing somebody said is a DESCRIPTION, and a name a person chose is a
     * LABEL, and letting one silently become the other is a decision that has
     * not been taken.
     */
    private static final String NAME_IT =
            "UPDATE conversations SET title = ? WHERE id = ? AND title IS NULL";

    /**
     * How wide a name is allowed to be.
     *
     * <p><b>72 because a title is a subject line</b>, which is a form that has
     * already been settled: a git subject, a mail subject and a listing row are
     * all one line naming one thing, and 72 is the width that convention arrived
     * at for them. Long enough that an ordinary first question survives whole —
     * "why did the deploy roll back?" is 28 — and short enough that a column of
     * them scans as a column rather than as prose.
     *
     * <p><b>The narrowest surface that renders one decides it, and that surface
     * is a terminal.</b> The TUI lists a conversation as a name beside its id,
     * and {@code cnv_} plus sixteen hex digits is twenty characters; 72 leaves
     * an 80-column terminal room to put the id on the next line or to truncate
     * the name again for itself, which it is free to do. A wider stored value
     * would simply be one every client had to cut, which is the same work done
     * three times and disagreed about — the thing the column exists to stop.
     *
     * <p><b>Not a {@code VARCHAR(72)} in the schema.</b> {@code
     * V38__conversation_title.sql} says why: the database would then hold a
     * second copy of this number, in a file that cannot be edited once it has
     * shipped, and widening the name would mean the derivation and the column
     * disagreeing until somebody wrote another migration.
     */
    static final int TITLE_WIDTH = 72;

    /**
     * How far back from the cut a word boundary may be and still be used.
     *
     * <p>The boundary is a <em>preference</em> and not a rule. A first line is
     * quite often one long unbroken thing — a pasted URL, a stack frame, a
     * base64 blob — and a derivation that insisted on a boundary would answer
     * such a line with whatever came before its first space, which for those
     * three is nothing at all.
     *
     * <p>12 because an English word averages about five characters, so this is
     * roughly two of them: a boundary further back than that throws away more of
     * the name than the ragged edge was ever going to cost.
     */
    static final int NEAREST_BOUNDARY = 12;

    /**
     * The name to give a conversation whose first turn said this, or {@code
     * null} when there is nothing in it to name one with.
     *
     * <h2>The first non-blank line, and not the first sentence</h2>
     *
     * <p>An utterance is a message rather than a sentence: it opens with the
     * question and carries the paste, the stack trace and four paragraphs of
     * context underneath. A first <em>sentence</em> would run through the
     * newline into the paste, because a paste has no full stops; the whole
     * utterance would put a stack trace in a listing. The first line a person
     * typed is where they put the subject, and it is the only part of an
     * utterance that reliably is one.
     *
     * <p>{@link String#lines()} and not a split on {@code \n}, so a CRLF
     * utterance — every one from a Windows terminal — is the same lines as an LF
     * one rather than lines ending in a stray carriage return, which a listing
     * renders as a cursor jump back to the start of the row.
     *
     * <h2>The heading marks are stripped, and only those</h2>
     *
     * <p>{@code # Ask about soil} is somebody writing markdown into a chat box,
     * and the {@code #} is structure rather than a word: it says "this line is
     * the heading of what follows", which is the one thing every value in this
     * column already is. Left in, it would render as furniture in front of every
     * title a markdown-writing person produced, and a listing sorted or scanned
     * by eye would have a column of hashes down its left edge.
     *
     * <p><b>A leading {@code -} or {@code *} is NOT stripped</b>, and the line
     * has to be drawn somewhere so it is drawn where ambiguity starts. Those two
     * are a bullet in markdown and a minus sign, a glob or a footnote everywhere
     * else, and telling them apart is a parser — a derivation with a parser in
     * it is one that can disagree with itself about the same line. A run of
     * {@code #} at the start of a line <em>followed by whitespace</em> has no
     * other reading, so that is the whole of the stripping, and {@code C# or
     * F#?} keeps both of its hashes.
     *
     * <h2>Null and never the empty string</h2>
     *
     * <p>An utterance of nothing but whitespace names nothing. {@code
     * turns_utterance_is_not_blank} refuses the empty string and nothing else,
     * so a turn whose utterance is three spaces and a newline is a row {@code
     * turns} holds quite happily, and this is the ordinary answer to it rather
     * than a defensive branch. A null title is a conversation with no name,
     * which a client renders as its id; an empty one is a conversation whose
     * name is nothing, which renders as a blank row a person cannot tell from a
     * broken one — and {@code conversations_a_title_is_named} refuses it one
     * layer down.
     *
     * <p><b>No ellipsis on a cut name.</b> The column holds the conversation's
     * name and every client truncates again to its own width; a stored {@code …}
     * would then land in the middle of a line, marking a place the text did not
     * in fact end. Signalling truncation is the renderer's, where the width that
     * caused it is known.
     *
     * @param utterance what was said, whole and as it arrived. Null and blank
     *     are both answered rather than refused: this is a derivation and not a
     *     validation, and the caller has a turn to write either way
     * @return a name of at most {@link #TITLE_WIDTH} characters, never blank, or
     *     {@code null} when the utterance held no line to make one from
     */
    static String titleFrom(String utterance) {
        if (utterance == null) {
            return null;
        }
        String line = utterance.lines()
                .map(String::strip)
                .filter(stripped -> !stripped.isEmpty())
                .findFirst()
                .orElse(null);
        if (line == null) {
            return null;
        }
        String named = unmarked(line);
        // Blank again after the marks came off -- a line of nothing but hashes
        // cannot reach this, since the strip needs whitespace after the run, but
        // the check is what keeps the CHECK below unreachable rather than merely
        // unreached.
        return named.isEmpty() ? null : shortened(named);
    }

    /**
     * Write this name onto the conversation, if it does not have one.
     *
     * <p><b>One statement, and a second call changes nothing.</b> {@code
     * NAME_IT} carries the whole of why; in short, {@code AND title IS NULL} is
     * what makes "the first turn names it" true without this method knowing
     * which turn it is, and without a window for two turns landing together to
     * race in.
     *
     * <p><b>It answers rather than refuses when there is no name to give.</b> A
     * turn whose utterance was nothing but whitespace leaves the conversation
     * unnamed, which is a state the column holds and the caller has nothing to
     * do about.
     *
     * <p><b>Static and taking the template, exactly as {@link #named} is static
     * and shared.</b> {@link TurnStore} is the caller — a name is a fact about a
     * conversation, and the first turn is the only thing that knows it — and
     * handing that store a {@code ConversationStore} to reach one statement
     * would be a constructor argument in fifteen places for a single UPDATE.
     * {@code ProjectIds.toWrite(jdbc, home)} is the same shape, one table over,
     * for the same reason. What must not happen is a second copy of {@code
     * NAME_IT} in {@code TurnStore}: the conditional write is the design, and a
     * copy of it is the thing that gets relaxed later by somebody who does not
     * know that.
     *
     * @param jdbc the template to write through, so that the write lands on the
     *     same connection the turn was written on
     * @param id the conversation being named
     * @param utterance the first turn's utterance, whole
     * @throws ValidationException if the conversation id cannot be named
     */
    static void nameFromFirstTurn(JdbcTemplate jdbc, String id, String utterance) {
        String wanted = named(id);
        String title = titleFrom(utterance);
        if (title == null) {
            return;
        }
        ArchiveUnavailableException.translating("name a conversation",
                () -> jdbc.update(NAME_IT, title, wanted));
    }

    /**
     * The line without its markdown heading marks.
     *
     * <p>A run of {@code #} at the very start, and only when whitespace follows
     * it, which is what makes the reading unambiguous — see {@link #titleFrom}
     * for why the stripping stops there and does not reach bullets or emphasis.
     */
    private static String unmarked(String line) {
        int hashes = 0;
        while (hashes < line.length() && line.charAt(hashes) == '#') {
            hashes++;
        }
        if (hashes == 0 || hashes == line.length()
                || !Character.isWhitespace(line.charAt(hashes))) {
            return line;
        }
        return line.substring(hashes).strip();
    }

    /**
     * The line at no more than {@link #TITLE_WIDTH}, on a word boundary when one
     * is within {@link #NEAREST_BOUNDARY} of the cut.
     *
     * <p>{@code lastIndexOf} over the prefix and not a split into words: the cut
     * is a position and the question is where the nearest boundary before it is,
     * which is one search rather than a list nothing else needs.
     */
    private static String shortened(String line) {
        if (line.length() <= TITLE_WIDTH) {
            return line;
        }
        String cut = line.substring(0, TITLE_WIDTH);
        int boundary = cut.lastIndexOf(' ');
        if (boundary >= TITLE_WIDTH - NEAREST_BOUNDARY) {
            cut = cut.substring(0, boundary);
        }
        // Stripped again: a hard cut can land on the space after a word, and a
        // name with a trailing space is one whose width nobody can see.
        return cut.strip();
    }

    /**
     * The two columns as the one thing they mean.
     *
     * <p>Static and shared by the insert and the row mapper, so that a
     * conversation opened and a conversation read back cannot disagree about
     * which of the three states it is in — the drift that a second copy of these
     * three lines would introduce at the first rewording of either.
     */
    private static ConversationLifecycle lifecycle(String wireName) {
        return wireName == null ? null : ConversationLifecycle.of(wireName);
    }

    private static TurnCap readBack(Integer turnCap, boolean lifted) {
        if (turnCap != null) {
            return TurnCap.of(turnCap);
        }
        return lifted ? TurnCap.none() : null;
    }

    /**
     * The three budget states as the one thing they mean, shared by the insert
     * and the row mapper for {@link #readBack}'s reason exactly.
     *
     * <p><b>The two absences are different absences and the boolean is what
     * tells them apart.</b> No total and no lifting is a conversation that
     * spends an allowance it does not own — {@code
     * V17__conversation_origin.sql} section 4, and {@code null} here is what
     * keeps a delegated child from being handed a second copy of its parent's
     * budget. No total <em>with</em> lifting is a conversation that owns its
     * allowance and put no ceiling on it, and it comes back as a {@link Budget}
     * carrying the count, because spend is a measurement and stays true when
     * the ceiling stops existing.
     *
     * <p>Plain values and not a {@link ResultSet}, so the decision is a function
     * of the three columns rather than of a cursor. That is what lets the
     * insert's return value and a later read go through the same three lines
     * instead of agreeing by hand.
     */
    private static Budget readBudget(Integer total, Integer spent, boolean lifted) {
        if (total != null) {
            return Budget.resumed(total, spent);
        }
        return lifted ? Budget.lifted(spent) : null;
    }

    private static final RowMapper<ConversationRecord> ROW_MAPPER = (rs, rowNum) -> {
        String project = rs.getString("project");
        // getObject and not getInt: getInt answers 0 for a NULL column, and 0 is
        // the one number a turn cap can never be -- so a conversation that does
        // not decide would come back as one that had decided something the CHECK
        // refuses, and TurnCap.of would throw reading its own row.
        Integer turnCap = rs.getObject("turn_cap", Integer.class);
        // getObject for the same reason turn_cap takes it: getInt answers 0 for a
        // NULL column, and a conversation that shares its parent's allowance
        // would come back claiming a total of zero -- which
        // conversations_budget_is_spendable refuses and Budget.of would throw
        // reading its own row. The absence has to survive the read.
        Integer total = rs.getObject("budget_total", Integer.class);
        // And getObject on the count for the third time, which V31 is what
        // makes necessary: until then a NULL total meant a NULL count too, so
        // the count was only ever read where it was known to be there. A row
        // that shares an allowance holds neither, and getInt would turn that
        // into a spend of zero -- indistinguishable from a lifted conversation
        // that has made no calls yet, which is the one distinction the boolean
        // exists to carry.
        Integer spent = rs.getObject("budget_spent", Integer.class);
        return new ConversationRecord(
                rs.getString("id"),
                // NULL is the global tier and not a missing home. Home.of would
                // refuse it, which is the correct behaviour for a request field
                // and the wrong one for this column.
                project == null ? Home.global() : Home.of(project),
                Origin.of(rs.getString("origin")),
                // NULL is a delegated child, whose state is its root's, and not
                // a state this server failed to read. Lifecycle.of would refuse
                // it, which is the correct behaviour for a root's column and the
                // wrong one for a child's.
                lifecycle(rs.getString("lifecycle")),
                rs.getString("parent_id"),
                rs.getString("agent"),
                instant(rs, "created_at"),
                instant(rs, "last_turn_at"),
                readBudget(total, spent, rs.getBoolean("budget_lifted")),
                readBack(turnCap, rs.getBoolean("turn_cap_lifted")),
                // Straight through, null included. A conversation with no name
                // is an ordinary row -- every one written before V38, and every
                // one nobody has spoken into -- and a fallback invented here
                // would be a name this server made up, travelling on the wire
                // as indistinguishable from one a person's own words produced.
                rs.getString("title"));
    };

    /*
     * OffsetDateTime on both sides and never java.sql.Timestamp, for the reason
     * MemoryStore, ProposalStore and ReasonLog all give: Timestamp carries no
     * zone and comes back through the JVM's default calendar, so a server
     * outside UTC round-trips a shifted instant.
     */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** The null branch is here and not in {@code ReasonLog}'s copy because
     *  {@code last_turn_at} really is nullable: a conversation nobody has spoken
     *  into has no last turn. */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
