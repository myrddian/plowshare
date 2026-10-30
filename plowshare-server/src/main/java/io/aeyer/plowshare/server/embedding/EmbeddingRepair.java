package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.llm.accounting.CallLifecycle;
import java.util.Objects;
import java.util.UUID;

/** Durable metadata only. A transport-start checkpoint is not proof of provider acceptance. */
public final class EmbeddingRepair {
  private EmbeddingRepair() {}

  public enum Phase {
    PREPARED,
    ACCOUNTED,
    QUEUED,
    RUNNING,
    ATTEMPT_STARTED,
    RESPONSE_RECEIVED,
    EXECUTION_FINISHED,
    RESULT_VALIDATED,
    PUBLISHING,
    PUBLISHED,
    NOT_DISPATCHED,
    FAILED,
    BLOCKED,
    SUPERSEDED,
    OUTCOME_UNKNOWN;

    public boolean terminal() {
      return switch (this) {
        case PUBLISHED, NOT_DISPATCHED, FAILED, BLOCKED, SUPERSEDED, OUTCOME_UNKNOWN -> true;
        default -> false;
      };
    }
  }

  public enum Reason {
    NONE,
    LEGACY_UNCORRELATED,
    CAPACITY_EXHAUSTED,
    AUTHORIZATION_REJECTED,
    ACCOUNTING_UNAVAILABLE,
    DISPATCH_FAILED,
    TRANSPORT_OUTCOME_UNKNOWN,
    PROVIDER_REFUSED,
    INVALID_RESULT,
    PUBLICATION_FAILED,
    SOURCE_OR_PROFILE_CHANGED,
    INTERRUPTED,
    PROCESS_LOST;
  }

  public record Attempt(UUID id, EmbeddingProfile profile, EmbeddingWorkRepository.Source source) {
    public Attempt {
      Objects.requireNonNull(id);
      Objects.requireNonNull(profile);
      Objects.requireNonNull(source);
    }
  }

  /** Nullable receipt fields mean not yet observed, never success. */
  public record Progress(
      Phase phase, UUID callId, UUID wireAttemptId, Integer httpStatus, CallLifecycle outcome) {
    public Progress {
      Objects.requireNonNull(phase);
      if (phase.terminal())
        throw new IllegalArgumentException("Use the outcome transaction for terminal phases");
      if (httpStatus != null && (httpStatus < 100 || httpStatus > 599))
        throw new IllegalArgumentException("Invalid HTTP status");
    }

    public static Progress phase(Phase phase) {
      return new Progress(phase, null, null, null, null);
    }
  }
}
