package io.aeyer.plowshare.server.information;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Answers {@link InformationCatalogueRepository#readable} without per-row permission SQL when it
 * can. Empty means "ask SQL": the caller must then run the original statement, which is the only
 * oracle. A present answer equals that statement's answer for the same committed state.
 */
public interface KnowledgeReadabilityCheck {
  Optional<Boolean> readable(InformationContext context, UUID revision);

  /**
   * Reads one revision's row only if this check decides the revision readable, so the row read
   * itself carries no permission predicate.
   *
   * <p>{@code fetch} must read the row by id with only non-permission predicates (availability,
   * size guards); it is called at most once, and only after a readable verdict. The outcome is
   * {@link Guarded.Declined} whenever {@link #readable} would be empty, and also when the decision
   * can no longer be vouched for after the fetch: the caller must then run its permission-filtered
   * statement, exactly as if this check did not exist. An {@link Guarded.Answered} outcome holds
   * the fetched row, or nothing when the revision is unreadable (then {@code fetch} never ran).
   *
   * <p>Implementations that answer must make the fetched row agree with the decision: a data-side
   * write committed between the decision and the fetch must not let the row be returned when the
   * permission-filtered statement, run at the fetch, would have withheld it. {@link
   * CachedKnowledgeReadability} confirms its facts after the fetch for that reason.
   */
  <T> Guarded<T> fetchIfReadable(
      InformationContext context, UUID revision, Supplier<Optional<T>> fetch);

  /** Every check goes to SQL. */
  static KnowledgeReadabilityCheck none() {
    return new KnowledgeReadabilityCheck() {
      @Override
      public Optional<Boolean> readable(InformationContext context, UUID revision) {
        return Optional.empty();
      }

      @Override
      public <T> Guarded<T> fetchIfReadable(
          InformationContext context, UUID revision, Supplier<Optional<T>> fetch) {
        return new Guarded.Declined<>();
      }
    };
  }

  /** The outcome of {@link #fetchIfReadable}. */
  sealed interface Guarded<T> {
    /** Undecided or no longer confirmed: the permission-filtered statement must answer. */
    record Declined<T>() implements Guarded<T> {}

    /** Decided: the fetched row of a readable revision, or empty for an unreadable one. */
    record Answered<T>(Optional<T> row) implements Guarded<T> {
      public Answered {
        Objects.requireNonNull(row, "row");
      }
    }
  }
}
