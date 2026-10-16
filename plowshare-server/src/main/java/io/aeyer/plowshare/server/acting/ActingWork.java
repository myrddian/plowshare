package io.aeyer.plowshare.server.acting;

import java.util.Objects;
import java.util.UUID;

/** Personal conversation turns with durable, non-replaying admission receipts. */
public interface ActingWork {
  /** A null conversation opens a new, subject-owned Personal conversation. No client session. */
  record Start(UUID requestId, String agent, String task, String conversation) {
    public Start {
      Objects.requireNonNull(requestId);
      agent = ActingValues.account(agent);
      if (task == null
          || task.isBlank()
          || task.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536
          || task.indexOf(0) >= 0)
        throw new IllegalArgumentException("task must contain 1..65536 characters");
      if (conversation != null
          && (conversation.length() > 128 || !conversation.matches("cnv_[A-Za-z0-9]+")))
        throw new IllegalArgumentException("invalid conversation");
    }
  }

  /** ADMITTED/STARTED are evidence, not permission to retry after process loss. */
  enum State {
    ADMITTED,
    STARTED,
    FINISHED,
    UNCERTAIN
  }

  record Receipt(UUID requestId, String conversation, String agent, State state, String job) {
    public Receipt {
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(state);
      agent = ActingValues.account(agent);
      if (conversation == null
          || (conversation.length() > 128 || !conversation.matches("cnv_[A-Za-z0-9]+"))
          || job != null && (job.length() > 128 || !job.matches("job_[A-Za-z0-9]+"))
          || (state == State.STARTED || state == State.FINISHED) && job == null)
        throw new IllegalArgumentException("invalid acting receipt");
    }
  }

  Receipt start(RequestAuthority.Delegated authority, Start request);

  /**
   * Read only; never starts or resumes work, including an admission interrupted before execution.
   */
  Receipt receipt(RequestAuthority.Delegated authority, UUID requestId);

  /** Resolve a job's retained authority before status/cancellation on the existing job store. */
  void requireJob(RequestAuthority.Delegated authority, String job, ActingPermission permission);
}
