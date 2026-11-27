package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.events.FiringRecord.GoalSchedule;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Retains the exact dependency created by a dedicated Goal activation. */
interface GoalWaitStore {
  enum Kind {
    APPROVAL,
    ORCHESTRATION,
    QUESTION
  }

  enum State {
    PARKING,
    WAITING,
    CLAIMED,
    SETTLED,
    ABANDONED
  }

  record Origin(
      UUID goal,
      UUID cycle,
      String job,
      String inputJob,
      GoalSchedule schedule,
      int turn,
      int answer,
      String call) {
    public Origin {
      Objects.requireNonNull(goal);
      Objects.requireNonNull(cycle);
      if (job == null
          || job.isBlank()
          || inputJob == null
          || inputJob.isBlank()
          || turn < 1
          || answer < 1
          || call == null
          || call.isBlank()
          || call.length() > 1024) {
        throw new IllegalArgumentException("Invalid original Goal invocation");
      }
    }
  }

  record Dependency(Kind kind, String reference, UUID request, String hash, String definition) {
    public Dependency {
      Objects.requireNonNull(kind);
      boolean valid =
          switch (kind) {
            case APPROVAL ->
                reference != null && request == null && hash == null && definition == null;
            case ORCHESTRATION -> request != null && hash != null && definition != null;
            case QUESTION -> request != null && hash == null && definition == null;
          };
      if (!valid) {
        throw new IllegalArgumentException("Invalid Goal dependency identity");
      }
    }

    static Dependency approval(String approval) {
      return new Dependency(Kind.APPROVAL, approval, null, null, null);
    }

    static Dependency question(UUID question) {
      return new Dependency(Kind.QUESTION, null, question, null, null);
    }
  }

  record Wait(
      UUID id,
      Origin origin,
      Dependency dependency,
      State state,
      String continuation,
      Instant ownerEndedAt) {}

  /** Include terminal Goal conversations to fence generic continuation starters. */
  boolean dedicatedConversation(String conversation);

  /** Retain the invocation under the Goal lock before dependency dispatch or exposure. */
  Wait arm(Origin origin, Dependency dependency);

  Optional<Wait> open(UUID goal);

  /** Commit the originating turn's ending with the wait. */
  void park(Wait wait);

  /** Claim the exact wait together with the Goal continuation. */
  void claim(Wait wait, String dependency, Instant ownerEndedAt, String job);

  /** The owner appended the result to the exact conversation. */
  void settle(Wait wait, String job);

  /** Record cleanup intent under the Goal lock, for owner handling after commit. */
  void abandon(UUID goal);

  List<Wait> cleanupAfter(UUID after, int limit);

  void cleaned(UUID id);

  /** An approval question may precede its atomic call/Goal journal if the worker dies. */
  List<String> orphanedApprovals(int limit);
}
