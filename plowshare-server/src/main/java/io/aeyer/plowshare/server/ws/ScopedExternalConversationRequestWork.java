package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Same-thread Ex routing and bounded reply ownership; repositories own actual SQL-unit budgets. */
public final class ScopedExternalConversationRequestWork
    implements ExternalConversationRequestWork {
  private final ExternalConversationCompletions completions;

  public ScopedExternalConversationRequestWork(ExternalConversationCompletions completions) {
    this.completions = Objects.requireNonNull(completions);
  }

  @Override
  public <T> T within(Supplier<T> action) {
    return Objects.requireNonNull(action).get();
  }

  @Override
  public ExternalConversationCompletions.Request begin(
      long admittedAtNanos, Consumer<Outcome> reply) {
    return completions.begin(admittedAtNanos, reply);
  }
}
