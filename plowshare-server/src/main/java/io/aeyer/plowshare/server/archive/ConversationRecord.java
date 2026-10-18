package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.TurnCap;
import java.time.Instant;
import java.util.Objects;

/**
 * A conversation's history/lifecycle and projected allowance state.
 *
 * <p>The store reconstructs {@link Budget} from the canonical source. Separate readers retain the
 * same identity and claim one durable counter atomically; within a run, delegates share the same
 * live object as well. A status snapshot is not a reservation or permission to execute.
 *
 * <p>A child can reference the source without exposing a second owned budget here. Its source is
 * resolved through the retained relationship; turn completion stamps history without rewriting
 * spending. Turn serialization and lifecycle checks remain independent execution controls.
 *
 * @param id stable identity, {@code cnv_} under {@code MemoryIds.mint}
 * @param home the execution project, or the retained Global compatibility value for historical
 *     records and bounded SYSTEM traces. Ordinary admission selects an explicit account/project
 *     home; an absent project is not a fresh unrestricted execution scope
 * @param origin which door this conversation came through — see {@link Origin}, which carries the
 *     whole of why it is stored rather than derived. Never null: every conversation came through
 *     one of them, and a row that did not say which is a run nobody classified
 * @param lifecycle where the tree rooted here has got to, or {@code null} for a delegated child —
 *     which has no state of its own and resolves to its root through {@link
 *     ConversationStore#lifecycleOf}. <b>One state per tree, and the null is what makes that
 *     structural</b>: a child with a state of its own is a branch that can disagree with its root,
 *     which is the archived- root-with-live-branches failure said as a row. Exactly the rows with
 *     no {@link #parentId()} carry one, which {@code
 *     conversations_a_root_is_where_the_lifecycle_lives} holds in the database.
 *     <p><b>It does not subsume {@link #origin()} and the two must not be merged.</b> The origin
 *     says which retention policy applies and never changes; this says where this conversation has
 *     got to under it and moves
 * @param parentId the conversation that delegated to this one, or {@code null} for a root. Exactly
 *     the {@link Origin#DELEGATION} rows have one, which {@code
 *     conversations_a_delegation_is_what_has_a_parent} holds in the database. <b>A {@link
 *     Origin#CURATOR} ruling is a root and still shares an allowance</b>, which is why the budget's
 *     nullability below keys off the origin and not off this field
 * @param agent the one agent this conversation is, or {@code null} for a person's — which may hold
 *     several agents' turns, the property {@code ConversationController}'s javadoc has defended
 *     since conversations existed. Exactly the non-{@link Origin#TURN} rows name one. Which agent
 *     answered a particular turn is {@link TurnRecord#agent()} and is a different fact
 * @param createdAt when the conversation was opened, on the store's clock
 * @param lastTurnAt when its last turn ended, or {@code null} if none has. Absent rather than
 *     defaulted to {@link #createdAt()}, because "nobody has spoken into this yet" and "one turn,
 *     at the instant it was opened" are different facts and a conversation opened and never used is
 *     exactly the row where the difference is interesting
 * @param budget the canonical allowance projected at read time, or {@code null} for a log that
 *     shares another operation's source. The retained allowance reference still identifies its
 *     counter; null here does not mean unlimited or create a fresh allowance
 * @param turnCap the ceiling this conversation puts on each of its turns, or {@code null} for a
 *     conversation that does not decide one — in which case the agent answering the turn is capped
 *     at what its own definition asks for. <b>Three states in one nullable field</b>, and the null
 *     is the one the other two are not: {@link TurnCap#none()} is a conversation that said "no
 *     cap", which is a decision, where null is a conversation that said nothing. {@code
 *     V10__conversation_turn_cap.sql} holds the same three across two columns and refuses the
 *     fourth combination.
 *     <p><b>A fresh object per read, like {@link #budget}</b>, and unlike it that is the whole of
 *     what it is for: a run's cap is moved by an operator while the run is going, and moving one
 *     turn's ceiling must not silently re-open the conversation's for every turn after it. What the
 *     row says is what the next turn starts from
 * @param title the human name this conversation is known by, derived by {@link
 *     ConversationStore#titleFrom} from its first turn's utterance, or {@code null} for one that
 *     has no name.
 *     <p><b>Nullable permanently, and every reader has to handle it.</b> Three different rows are
 *     in that state and none of them is a failure: every conversation written before {@code
 *     V38__conversation_title.sql}, which is never backfilled; a conversation somebody opened and
 *     never spoke into, which is the same ordinary state {@link #lastTurnAt()} carries; and one
 *     whose first utterance held no line to name it with. A client that meets a null shows the
 *     {@link #id()}, which is what every client shows today.
 *     <p><b>Written once, by the first turn, and never again.</b> The write is {@code UPDATE ...
 *     WHERE id = ? AND title IS NULL} — see {@link ConversationStore#nameFromFirstTurn} — so this
 *     is what the conversation opened with rather than what it last said, and a second turn changes
 *     nothing. It is a description and not a label somebody chose: nothing renames a conversation,
 *     deliberately, because a name a person picked and a name derived from their first sentence are
 *     different things and the difference is worth deciding separately
 */
public record ConversationRecord(
    String id,
    Home home,
    Origin origin,
    ConversationLifecycle lifecycle,
    String parentId,
    String agent,
    Instant createdAt,
    Instant lastTurnAt,
    Budget budget,
    TurnCap turnCap,
    String title) {

  /**
   * Rejects the nulls that have no meaning, and only those.
   *
   * <p>{@code lastTurnAt}, {@code lifecycle}, {@code parentId}, {@code agent}, {@code budget},
   * {@code turnCap} and {@code title} are legitimately absent, and each absence means something
   * this record has to be able to hold: no turn yet, a delegated child whose state is its root's, a
   * root, a person's conversation, an allowance owned one level up, a conversation that decides no
   * ceiling, and one nothing has named. {@code budget} was checked here until every run became a
   * conversation, and it is not any more for the reason its own parameter gives. A null {@code
   * home} in particular would not be "global" — {@link Home#global()} is a {@code Home} whose
   * project is null, which is a different thing from no home at all — and letting one through would
   * reach the store as a null dereference several layers from whoever dropped it.
   */
  public ConversationRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(home, "home");
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
