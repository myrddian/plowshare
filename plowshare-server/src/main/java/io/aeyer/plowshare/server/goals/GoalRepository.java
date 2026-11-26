package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.goals.ConversationGoals.Reason;
import io.aeyer.plowshare.server.goals.ConversationGoals.State;
import io.aeyer.plowshare.server.goals.ConversationGoals.Status;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Durable state and receipts. A start claim is recorded before dispatch and counted exactly once.
 */
interface GoalRepository {
  record Binding(
      UUID id,
      String conversation,
      UUID admission,
      String executor,
      String agent,
      String fingerprint,
      UUID allowance,
      String objective,
      String criteria) {}

  record Progress(
      State state,
      int activations,
      int limit,
      String job,
      String lastJob,
      Integer lastTurn,
      Reason reason,
      String detail,
      int noProgress,
      String answerDigest) {
    Progress move(State next, Reason why, String note, String activeJob, int ceiling) {
      return new Progress(
          next,
          activations,
          ceiling,
          activeJob,
          lastJob,
          lastTurn,
          why,
          note,
          noProgress,
          answerDigest);
    }
  }

  record Goal(Binding binding, int version, Progress progress) {}

  record Receipt(String fingerprint, Status status) {}

  Optional<Goal> latest(String conversation);

  Optional<Goal> find(UUID id);

  boolean running(UUID id, String job);

  Optional<Receipt> receipt(String actor, UUID request);

  /** Claims the request and commits its transition and receipt in the same SQL transaction. */
  Status request(
      String actor, UUID request, String conversation, String fingerprint, Supplier<Status> change);

  Goal insert(Binding binding, int limit);

  /** Returns empty if another transition already changed this version. */
  Optional<Goal> change(Goal before, Progress after);

  /** Acquires the exact goal row and writes its turn ending and next state in one transaction. */
  void settle(UUID id, String job, Runnable writeTurn, Function<Goal, Progress> next);

  List<Goal> scan(UUID after, int limit);
}
