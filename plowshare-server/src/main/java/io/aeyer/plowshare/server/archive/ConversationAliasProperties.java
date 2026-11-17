package io.aeyer.plowshare.server.archive;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Finite alias SQL budgets; nested callers retain the earlier enclosing ReadDeadline. */
@ConfigurationProperties("plowshare.conversation-aliases")
public record ConversationAliasProperties(
    @DefaultValue("2s") Duration timeout, @DefaultValue("1s") Duration queryTimeout) {
  public ConversationAliasProperties {
    Objects.requireNonNull(timeout);
    Objects.requireNonNull(queryTimeout);
    if (timeout.toMillis() < 1
        || timeout.compareTo(Duration.ofMinutes(1)) > 0
        || queryTimeout.toMillis() < 1
        || queryTimeout.compareTo(Duration.ofSeconds(1)) > 0)
      throw new IllegalArgumentException(
          "Finite alias and at-most-one-second query budgets required");
  }
}
