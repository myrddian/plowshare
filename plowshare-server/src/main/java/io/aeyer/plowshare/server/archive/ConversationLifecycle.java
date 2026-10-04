package io.aeyer.plowshare.server.archive;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a conversation has got to, and what may follow what.
 *
 * <h2>Why it is not called {@code Lifecycle}, which is the whole point</h2>
 *
 * <p>Because {@link Lifecycle} is taken, by the memory archive, three files over, and <b>that class
 * is the one this design was explicitly corrected away from</b>. An earlier draft of the retention
 * spec borrowed it — its name, its {@code MemoryState.COLD}, and its promise that <em>"nothing here
 * deletes anything"</em> — and the owner's correction is recorded at the top of the document:
 * memories are not part of this design, the log and the memory archive are different systems with
 * different purposes, and <b>a log may delete; that is what logs do</b>. Every guarantee that other
 * class makes is the archive's and governs nothing here.
 *
 * <p>The collision is worth leaving visible rather than routing around with a package. Two states
 * called "cold" in one codebase, one of which means "kept for ever, out of the index" and the other
 * "the bytes are gone", is exactly the conflation the correction was about, and a reader who
 * reaches for the wrong import meets a compile error instead of a guarantee that does not hold.
 *
 * <h2>One state per tree, held on the root</h2>
 *
 * <p>A delegated child has no state of its own: it resolves to the root of its tree, and the root's
 * state decides the whole tree. {@code conversations_a_root_is_where_the_lifecycle_lives} is that
 * rule in the database — a child's column is NULL and there is nothing for it to disagree with —
 * and {@link ConversationStore#lifecycleOf} is the walk that answers for one. The alternative, a
 * state on every row kept in step by whoever writes them, has exactly one failure mode and it is
 * the one this shape makes unwritable: <b>an archived root with live branches</b>.
 *
 * <h2>It does not subsume {@link Origin}</h2>
 *
 * <p>Two columns, two jobs, and they will go on being tempting to merge. {@code origin} says
 * <em>which policy applies</em> and is written once and never recomputed; this says <em>where this
 * conversation has got to</em> under it, and it moves. A single column would have to spell every
 * pair of the two.
 *
 * <h2>Why the transitions are here and not in a CHECK</h2>
 *
 * <p>A {@code CHECK} cannot see the value a column is moving away from, so the ordering this enum
 * is named for is not something {@code V19__conversation_lifecycle.sql} could hold. The two ways to
 * say it in Postgres are a trigger and a conditional UPDATE, and this schema has chosen between
 * them twice already — V17 declines a trigger for cycles because it "would be a second mechanism
 * guarding a rule one writer already holds", and {@code ConversationStore.turnEnded} holds
 * "spending never falls" as a clause on the UPDATE itself. {@link #reachedFrom()} is what {@link
 * ConversationStore#moveTo} splices into its own {@code WHERE}, so a move from a state this table
 * does not allow changes no row and is reported as the refusal it is — enforced in the database,
 * without a second mechanism, with the table of what may follow what in the one place a reader
 * looks for it.
 *
 * <p><b>Nothing holds this enum and {@code conversations_lifecycle_is_known} together at compile
 * time</b>, which is the situation {@link Origin}, {@code EntryKind}, {@code Outcome.Ending} and
 * {@code ProposalState} are all already in. {@code
 * ConversationStoreTest.every_lifecycle_state_this_server_can_write_ is_one_this_table_holds}
 * enumerates {@link #values()} and drives one of each, so the build that adds a constant without a
 * migration is what fails.
 */
public enum ConversationLifecycle {

  /**
   * Being used, and the state every conversation is opened in.
   *
   * <p>The only one a turn may be spoken into and the only one a stopped run may be resumed from —
   * see {@link #acceptsWork()}, and {@code agents.Turn}, which asks.
   */
  ACTIVE("active"),

  /**
   * A person is done with it.
   *
   * <p><b>Optional, and not a gate.</b> It is the one state in this enum that a <em>person</em>
   * sets: §5 of the retention design says a person <em>chooses</em> to archive a {@link
   * Origin#TURN} conversation, and nobody ever archives a {@link Origin#SUBMISSION} or a {@link
   * Origin#CURATOR} — policy marks those directly, so {@link #ACTIVE} to {@link #TO_BE_EJECTED} is
   * a legal edge and this state is not on the path. Making it a gate would have made it mean two
   * different things depending on who set it: "somebody decided" on one origin and "the sweep
   * passed by" on another, in one column, which is the shape {@link Origin}'s own javadoc refuses
   * one column over.
   *
   * <p><b>Reversible.</b> {@link #ARCHIVED} to {@link #ACTIVE} is unarchiving, and a person who
   * archives a conversation and then wants to go on talking has not made a decision anybody should
   * have to live with.
   */
  ARCHIVED("archived"),

  /**
   * Marked for ejection, and the payload is still here.
   *
   * <p><b>This state is the whole of what the staged path buys.</b> Mark, then export, then null
   * the payload: nothing vanishes in one step, and a marked-but-unexported conversation is one
   * somebody can still say no about. {@link #TO_BE_EJECTED} to {@link #ACTIVE} is that no —
   * cancelling before the bytes go — and it is the recoverability the staging exists to buy rather
   * than a convenience on top of it.
   *
   * <p>It accepts no work. A conversation somebody is about to eject the working of is not one to
   * start a new turn in; the way back is to cancel the mark first, which says what was actually
   * decided.
   */
  TO_BE_EJECTED("to_be_ejected"),

  /**
   * The payloads are gone.
   *
   * <p><b>Terminal.</b> {@link #reachedFrom()} is empty for every other state once a conversation
   * is here, because there is nothing to return to: the bytes were nulled, and a transition back to
   * {@link #ACTIVE} would be a row claiming a history it no longer holds. What is <em>not</em> gone
   * is the record — every {@code entries} row is still there with its ordinal, its handle, its size
   * and its timing, and {@code result_read} answers for a handle rather than failing on it.
   */
  EJECTED("ejected");

  private final String wireName;

  ConversationLifecycle(String wireName) {
    this.wireName = wireName;
  }

  /**
   * What the column holds — lower case, as {@link Origin}, {@code EntryKind} and {@code
   * ProposalState} all spell theirs.
   */
  public String wireName() {
    return wireName;
  }

  /**
   * Whether a conversation in this state may be spoken into and resumed.
   *
   * <p><b>Said as one question because it is one rule</b>, and the two callers in {@code
   * agents.Turn} give different reasons for asking it — an utterance is a person starting something
   * new in a conversation they have put away, and a resumption is a run continuing inside one. Both
   * are work, and only {@link #ACTIVE} takes work.
   *
   * <p><b>Without this the state is decorative</b>, which is the failure the design names in as
   * many words: a state added as a listing filter that quietly does nothing else. An archived
   * conversation that still accepted turns would be a label.
   */
  public boolean acceptsWork() {
    return this == ACTIVE;
  }

  /**
   * The states a move <em>to</em> this one may come from, empty for one no move reaches.
   *
   * <p>Read as a column of the transition table rather than a row — "what may precede this" and not
   * "what may follow that" — because that is the shape the SQL needs: {@link
   * ConversationStore#moveTo} knows where it is going and asks the database to refuse if the row is
   * not somewhere this set names.
   *
   * <p>The three properties the design asks to be settled here rather than by convention are all
   * readable off it:
   *
   * <ul>
   *   <li>{@link #ARCHIVED} <b>is optional</b>: {@link #TO_BE_EJECTED} is reached from {@link
   *       #ACTIVE} as well, so policy can mark a submission or a curator trace directly and nobody
   *       has to archive something on its behalf;
   *   <li><b>two edges reverse</b>: {@link #ACTIVE} is reached from {@link #ARCHIVED} (unarchive)
   *       and from {@link #TO_BE_EJECTED} (cancel before export);
   *   <li>{@link #EJECTED} <b>is terminal</b>: nothing names it as a predecessor.
   * </ul>
   *
   * <p>A fresh {@link EnumSet} per call. These are small and the alternative is a static field per
   * constant that a caller could hand to something that mutates it; {@code Turn.CONTINUABLE} is
   * shared because it is one set, and this is four.
   */
  public Set<ConversationLifecycle> reachedFrom() {
    return switch (this) {
      case ACTIVE -> EnumSet.of(ARCHIVED, TO_BE_EJECTED);
      case ARCHIVED -> EnumSet.of(ACTIVE);
      case TO_BE_EJECTED -> EnumSet.of(ACTIVE, ARCHIVED);
      case EJECTED -> EnumSet.of(TO_BE_EJECTED);
    };
  }

  /**
   * The constant a row's value spells, or a refusal naming what was read.
   *
   * <p>{@link Origin#of}'s shape and its reason: a state this server cannot read back is a row
   * written successfully and unreadable for ever, so the one place that can produce that fault is
   * the one place that parses it.
   *
   * @throws IllegalArgumentException if nothing is spelled that
   */
  public static ConversationLifecycle of(String wireName) {
    for (ConversationLifecycle lifecycle : values()) {
      if (lifecycle.wireName.equals(wireName)) {
        return lifecycle;
      }
    }
    throw new IllegalArgumentException(
        "no conversation lifecycle state is spelled '"
            + wireName
            + "'; this row was"
            + " written by something that knows a state this server does not, and"
            + " conversations_lifecycle_is_known should have refused it");
  }
}
