package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.ExternalConversationAdmission;
import io.aeyer.plowshare.server.archive.ConversationAliasProperties;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Same-thread opt-in SQL scope; its beginning is before common router preauthorization. */
public final class ScopedExternalConversationRequestWork
    implements ExternalConversationRequestWork {
  private final SqlWorkBudget sql;
  private final ConversationAliasProperties limits;
  private final ExternalConversationCompletions completions;

  public ScopedExternalConversationRequestWork(
      SqlWorkBudget sql,
      ConversationAliasProperties limits,
      ExternalConversationCompletions completions) {
    this.sql = Objects.requireNonNull(sql);
    this.limits = Objects.requireNonNull(limits);
    this.completions = Objects.requireNonNull(completions);
  }

  @Override
  public <T> T within(Supplier<T> action) {
    return sql.within(
        limits.timeout(), () -> ExternalConversationAdmission.within(limits.timeout(), action));
  }

  @Override
  public ExternalConversationCompletions.Request begin(
      long admittedAtNanos, Consumer<Outcome> reply) {
    return completions.begin(admittedAtNanos, reply);
  }
}
