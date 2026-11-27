package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.messaging.MessageService.GoalReturn;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Binds Goal return intents and answers to the immutable audience chosen at launch. */
interface GoalReturnStore {
  enum Kind {
    RESULT,
    QUESTION,
    BLOCKED,
    CANCELLED
  }

  enum Delivery {
    PENDING,
    DELIVERED,
    WITHHELD
  }

  record Route(UUID goal, GoalReturn bot) {
    boolean human() {
      return bot == null;
    }
  }

  record Intent(
      UUID id,
      Route route,
      Kind kind,
      int goalVersion,
      String body,
      Delivery delivery,
      String receipt,
      String answerMessage) {}

  record HumanAnswer(UUID question, UUID requestId, String body) {}

  /** Joins the launch transaction; a duplicate launch reuses the original route. */
  void bind(Route route);

  Optional<Route> route(UUID goal);

  /** Joins the Goal settlement and makes no delivery call. Older goals may have no route. */
  void stage(UUID id, UUID goal, Kind kind, int goalVersion, String body);

  Optional<Intent> intent(UUID id);

  Optional<Intent> questionByReceipt(String message);

  /** The delivery owner and the receipt update share the same transaction. */
  void delivered(UUID intent, String receipt);

  void withheld(UUID intent);

  List<Intent> pendingAfter(UUID after, int limit);

  List<Intent> questionsToClean(UUID after, int limit);

  void cleaned(UUID intent);

  Optional<HumanAnswer> humanAttempt(UUID goal, UUID request);

  Optional<HumanAnswer> humanAnswer(UUID question);

  /** Caller holds the Goal row lock. A distinct request cannot replace the question's answer. */
  boolean answer(UUID goal, HumanAnswer answer);

  /** Caller holds the Goal row lock. The exact native response is accepted once. */
  boolean answerFromBot(UUID question, String message);
}
