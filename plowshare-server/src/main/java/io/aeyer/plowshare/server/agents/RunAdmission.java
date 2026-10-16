package io.aeyer.plowshare.server.agents;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Authority retained by one admitted turn, independent of a socket. Checks may throw and must never
 * be downgraded to ordinary execution. The job callback persists its identity before the executor
 * can run; a failed callback prevents execution. Neither callback may contain credentials.
 */
public record RunAdmission(
    Runnable check,
    Consumer<String> started,
    Runnable ended,
    String subject,
    RunAuthority authority) {
  public RunAdmission {
    Objects.requireNonNull(check);
    Objects.requireNonNull(started);
    Objects.requireNonNull(ended);
    Objects.requireNonNull(authority);
    if (subject == null || subject.isBlank())
      throw new IllegalArgumentException("subject required");
  }
}
