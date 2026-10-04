package io.aeyer.plowshare.server.todos;

import java.util.List;
import java.util.Objects;

/**
 * Whether a locked item may change status, and what its being allowed to move brings with it.
 * Dropping, moving and renaming a locked item are refused by {@link TodoBoard} itself and never
 * reach this; only a status change does.
 *
 * <p>A locked item's status change is decided here.
 *
 * <p>{@code Allowed.consequences} are items the board writes as given, without asking again. They
 * must be items already on the list.
 *
 * <p>{@code Allowed.effects} run inside the board's transaction after its writes, so a throwing
 * effect rolls the batch back.
 *
 * <p>The board, not this policy, refuses rename, move and drop.
 *
 * <p>{@link #REFUSE_ALL} is this slice's answer, because nothing in it creates a locked item.
 * Orchestration stages (spec §4.3) replace it with the ordered-stage rules.
 */
@FunctionalInterface
public interface LockedMoves {

  LockedMoves REFUSE_ALL =
      (current, to, summary, list) ->
          new Refused(
              current.id()
                  + " is a locked item; its status is moved by the orchestration that"
                  + " owns it, not by this list");

  /** Whether this move is refused, and if not, what it brings with it. */
  Decision decide(TodoItem current, TodoStatus to, String summary, List<TodoItem> list);

  /** A locked item's status move, decided. */
  sealed interface Decision permits Refused, Allowed {}

  /** {@code why} is the sentence a {@link TodoRefused} carries back to the caller. */
  record Refused(String why) implements Decision {}

  /**
   * The move stands. {@code consequences} are items the board writes as given, without asking again
   * -- they must already be on the list. {@code effects} run inside the board's transaction after
   * its writes.
   */
  record Allowed(List<TodoItem> consequences, List<Runnable> effects) implements Decision {
    static final Allowed PLAIN = new Allowed(List.of(), List.of());

    public Allowed {
      consequences = List.copyOf(Objects.requireNonNull(consequences, "consequences"));
      effects = List.copyOf(Objects.requireNonNull(effects, "effects"));
    }
  }
}
