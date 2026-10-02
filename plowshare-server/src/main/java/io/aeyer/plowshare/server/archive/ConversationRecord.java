package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.TurnCap;
import java.time.Instant;
import java.util.Objects;

/**
 * One row of {@code conversations}: the long-lived thing a person talks to, and
 * the allowance every turn in it spends from.
 *
 * <h2>{@link Budget} and not two integers, which is a decision with a cost</h2>
 *
 * <p>The row holds {@code budget_total} and {@code budget_spent}, so a record
 * carrying an {@code int} pair would be the more literal translation of it —
 * and it would put the mistake {@code Budget}'s own javadoc is written against
 * back at every call site. That class exists because "passing a number down
 * would give the child a fresh copy of the parent's allowance"; a caller holding
 * a total and a count has to remember to say {@code Budget.resumed(total,
 * spent)} rather than {@code Budget.of(total)}, and the one that forgets hands a
 * conversation a fresh allowance and nothing anywhere fails. Handing back the
 * object means the only budget a caller can get is the right one.
 *
 * <p><b>The cost is a package dependency that did not exist before</b>, and it
 * is named here rather than left for somebody to find: this is the first import
 * of {@code server.agents} by {@code server.archive}, and {@code agents} already
 * imports {@code archive} in five files, so the two packages now refer to each
 * other. Nothing in the build forbids it — {@code InvariantsTest} guards Spring
 * in the client, the {@code OkHttpClient} holders, the reference box's address
 * and the key prefix, and no layering rule. The tidier repair is that {@code
 * Budget} belongs to neither package: it is a value that jobs, delegation trees
 * and now conversations all share. Moving it is a rename across every signature
 * that carries one and is not this task's.
 *
 * <h2>A snapshot of a row, and the live sharing is one level up</h2>
 *
 * <p>Two calls to {@link ConversationStore#find} return two <em>different</em>
 * {@link Budget} objects at the same count, because each is built from what the
 * row said when it was read. That is what a row snapshot is, and it is not the
 * sharing {@code Budget} is for: within a turn, one object is shared by
 * reference down the delegation tree exactly as {@code Curator.pass} shares one
 * across a pass, and {@link ConversationStore#turnEnded} writes what it came to
 * back onto the row. <b>Two turns running concurrently on one conversation would
 * each hold their own object and each see the whole remainder</b>; the row is
 * where that has to be settled, and {@code turnEnded} refusing to lower a row's
 * spending is the half of it this task can honestly build. Whether a
 * conversation may have two turns in flight at all is the task that builds
 * turns' question.
 *
 * @param id stable identity, {@code cnv_} under {@code MemoryIds.mint}
 * @param home the project this conversation is held in, or {@code
 *     Home.global()}. Typed as {@link Home} and not as a bare project string,
 *     for the reason {@link Proposal#home()} gives: the global tier stays the
 *     absence of a project rather than a name a project could take. Global is
 *     ordinary here — {@code RequestedHome.in} mints one for every
 *     submission that names no project — and {@code V6__conversations.sql} says
 *     why the column is nullable rather than carrying the plan's suggested NOT
 *     NULL
 * @param origin which door this conversation came through — see {@link Origin},
 *     which carries the whole of why it is stored rather than derived. Never
 *     null: every conversation came through one of them, and a row that did not
 *     say which is a run nobody classified
 * @param lifecycle where the tree rooted here has got to, or {@code null} for a
 *     delegated child — which has no state of its own and resolves to its root
 *     through {@link ConversationStore#lifecycleOf}. <b>One state per tree, and
 *     the null is what makes that structural</b>: a child with a state of its
 *     own is a branch that can disagree with its root, which is the archived-
 *     root-with-live-branches failure said as a row. Exactly the rows with no
 *     {@link #parentId()} carry one, which {@code
 *     conversations_a_root_is_where_the_lifecycle_lives} holds in the database.
 *
 *     <p><b>It does not subsume {@link #origin()} and the two must not be
 *     merged.</b> The origin says which retention policy applies and never
 *     changes; this says where this conversation has got to under it and moves
 * @param parentId the conversation that delegated to this one, or {@code null}
 *     for a root. Exactly the {@link Origin#DELEGATION} rows have one, which
 *     {@code conversations_a_delegation_is_what_has_a_parent} holds in the
 *     database. <b>A {@link Origin#CURATOR} ruling is a root and still shares an
 *     allowance</b>, which is why the budget's nullability below keys off the
 *     origin and not off this field
 * @param agent the one agent this conversation is, or {@code null} for a
 *     person's — which may hold several agents' turns, the property {@code
 *     ConversationController}'s javadoc has defended since conversations
 *     existed. Exactly the non-{@link Origin#TURN} rows name one. Which agent
 *     answered a particular turn is {@link TurnRecord#agent()} and is a
 *     different fact
 * @param createdAt when the conversation was opened, on the store's clock
 * @param lastTurnAt when its last turn ended, or {@code null} if none has.
 *     Absent rather than defaulted to {@link #createdAt()}, because "nobody has
 *     spoken into this yet" and "one turn, at the instant it was opened" are
 *     different facts and a conversation opened and never used is exactly the
 *     row where the difference is interesting
 * @param budget the allowance at the count the row records, or {@code null} for
 *     a conversation that spends an allowance it does not own.
 *
 *     <p><b>Nullable is the one thing about this record that had to change when
 *     every run became a conversation, and it is structural rather than
 *     defensive.</b> A delegated child spends its parent's {@link Budget} by
 *     reference and a curator's ruling spends the pass's; a row carrying a copy
 *     of either would be a second allowance of the same size, and the first
 *     thing to sum this column would count every model call twice — with both
 *     rows holding plausible numbers, which is what makes that failure quiet.
 *     So such a row holds nothing at all and {@code
 *     conversations_an_allowance_is_owned_or_shared} refuses to let it hold
 *     anything. {@link Origin#ownsItsAllowance()} is the same rule in Java, and
 *     the two must agree
 * @param turnCap the ceiling this conversation puts on each of its turns, or
 *     {@code null} for a conversation that does not decide one — in which case
 *     the agent answering the turn is capped at what its own definition asks
 *     for. <b>Three states in one nullable field</b>, and the null is the one
 *     the other two are not: {@link TurnCap#none()} is a conversation that said
 *     "no cap", which is a decision, where null is a conversation that said
 *     nothing. {@code V10__conversation_turn_cap.sql} holds the same three
 *     across two columns and refuses the fourth combination.
 *
 *     <p><b>A fresh object per read, like {@link #budget}</b>, and unlike it
 *     that is the whole of what it is for: a run's cap is moved by an operator
 *     while the run is going, and moving one turn's ceiling must not silently
 *     re-open the conversation's for every turn after it. What the row says is
 *     what the next turn starts from
 * @param title the human name this conversation is known by, derived by {@link
 *     ConversationStore#titleFrom} from its first turn's utterance, or {@code
 *     null} for one that has no name.
 *
 *     <p><b>Nullable permanently, and every reader has to handle it.</b> Three
 *     different rows are in that state and none of them is a failure: every
 *     conversation written before {@code V38__conversation_title.sql}, which is
 *     never backfilled; a conversation somebody opened and never spoke into,
 *     which is the same ordinary state {@link #lastTurnAt()} carries; and one
 *     whose first utterance held no line to name it with. A client that meets a
 *     null shows the {@link #id()}, which is what every client shows today.
 *
 *     <p><b>Written once, by the first turn, and never again.</b> The write is
 *     {@code UPDATE ... WHERE id = ? AND title IS NULL} — see {@link
 *     ConversationStore#nameFromFirstTurn} — so this is what the conversation
 *     opened with rather than what it last said, and a second turn changes
 *     nothing. It is a description and not a label somebody chose: nothing
 *     renames a conversation, deliberately, because a name a person picked and a
 *     name derived from their first sentence are different things and the
 *     difference is worth deciding separately
 */
public record ConversationRecord(
        String id, Home home, Origin origin, ConversationLifecycle lifecycle, String parentId, String agent,
        Instant createdAt, Instant lastTurnAt, Budget budget, TurnCap turnCap, String title) {

    /**
     * Rejects the nulls that have no meaning, and only those.
     *
     * <p>{@code lastTurnAt}, {@code lifecycle}, {@code parentId}, {@code agent},
     * {@code budget}, {@code turnCap} and {@code title} are legitimately absent,
     * and each absence means something this record has to be able to hold: no
     * turn yet, a delegated child whose state is its root's, a root, a person's
     * conversation, an allowance owned one level up, a conversation that decides
     * no ceiling, and one nothing has named. {@code budget} was checked here
     * until every run became a conversation, and it is not any more for the
     * reason its own parameter gives.
     * A null {@code home} in particular would not be "global" — {@link
     * Home#global()} is a {@code Home} whose project is null, which is a
     * different thing from no home at all — and letting one through would reach
     * the store as a null dereference several layers from whoever dropped it.
     */
    public ConversationRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
