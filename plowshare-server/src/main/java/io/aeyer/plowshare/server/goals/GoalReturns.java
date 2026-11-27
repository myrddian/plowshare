package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.agents.SendMessageTool;
import io.aeyer.plowshare.server.events.GoalInbox;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.goals.ConversationGoals.Reason;
import io.aeyer.plowshare.server.goals.ConversationGoals.State;
import io.aeyer.plowshare.server.goals.GoalAgents.Answer;
import io.aeyer.plowshare.server.goals.GoalAgents.Answered;
import io.aeyer.plowshare.server.goals.GoalRepository.Binding;
import io.aeyer.plowshare.server.goals.GoalRepository.Goal;
import io.aeyer.plowshare.server.goals.GoalRepository.Progress;
import io.aeyer.plowshare.server.goals.GoalReturnStore.Delivery;
import io.aeyer.plowshare.server.goals.GoalReturnStore.HumanAnswer;
import io.aeyer.plowshare.server.goals.GoalReturnStore.Intent;
import io.aeyer.plowshare.server.goals.GoalReturnStore.Kind;
import io.aeyer.plowshare.server.goals.GoalReturnStore.Route;
import io.aeyer.plowshare.server.goals.GoalWaitStore.Wait;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.messaging.MessageService.GoalAnswerClosed;
import io.aeyer.plowshare.server.messaging.MessageService.GoalAnswerReady;
import io.aeyer.plowshare.server.messaging.MessageService.GoalReturn;
import io.aeyer.plowshare.server.messaging.MessageService.GoalReturnKind;
import io.aeyer.plowshare.server.messaging.MessageService.GoalReturnUnavailable;
import io.aeyer.plowshare.server.messaging.MessageService.Instance;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Commits Goal-owned return intents and lets the selected owner publish them after settlement. */
final class GoalReturns {
  private static final Logger LOG = LoggerFactory.getLogger(GoalReturns.class);
  private static final UUID FIRST = new UUID(0, 0);
  private final GoalReturnStore returns;
  private final GoalRepository goals;
  private final GoalAgentStore transactions;
  private final GoalWaitStore waits;
  private final GoalExecution runtime;
  private final GoalInbox inbox;
  private final MessageService messages;
  private final InformationJobs information;
  private UUID deliveryCursor = FIRST;
  private UUID cleanupCursor = FIRST;

  GoalReturns(
      GoalReturnStore returns,
      GoalRepository goals,
      GoalAgentStore transactions,
      GoalWaitStore waits,
      GoalExecution runtime,
      GoalInbox inbox,
      MessageService messages,
      InformationJobs information) {
    this.returns = returns;
    this.goals = goals;
    this.transactions = transactions;
    this.waits = waits;
    this.runtime = runtime;
    this.inbox = inbox;
    this.messages = messages;
    this.information = information;
  }

  /** The launch already holds the account mailbox serialization lock before creating the Goal. */
  Route prepareRoute(
      Binding binding, GoalHandoffs.Caller caller, GoalAgents.Start request, boolean human) {
    if (human) {
      if (request.replyExpected())
        throw new CallerFault("A human Goal handoff returns through its account Inbox");
      return new Route(binding.id(), null);
    }
    if (request.replyExpected()) {
      if (caller == null
          || caller.originalMessage() == null
          || !caller.definition().tools().contains(SendMessageTool.NAME))
        throw new CallerFault("A correlated Goal handoff requires a granted Messaging route");
      var bot =
          messages.openGoalReturn(
              binding.executor(),
              request.project(),
              caller.conversation(),
              caller.definition(),
              caller.originalMessage(),
              binding.instance(),
              request.requestId());
      return new Route(binding.id(), bot);
    }
    return null;
  }

  void bind(Route route) {
    returns.bind(route);
  }

  String launchMessage(UUID goal) {
    return returns
        .route(goal)
        .map(Route::bot)
        .map(MessageService.GoalReturn::message)
        .orElseThrow(GoalReturns::missing);
  }

  boolean hasRoute(UUID goal) {
    return returns.route(goal).isPresent();
  }

  void stageQuestion(UUID question, Goal goal, String text) {
    returns.stage(
        question,
        goal.binding().id(),
        Kind.QUESTION,
        goal.version() + 1,
        GoalText.require(text, "question", 4000));
  }

  void stageProgress(Goal current, Progress next) {
    UUID goal = current.binding().id();
    int version = current.version() + 1;
    if (next.state() == State.COMPLETE && next.detail() != null) {
      returns.stage(UUID.randomUUID(), goal, Kind.RESULT, version, next.detail());
    } else if (next.state() == State.BLOCKED && next.reason() == Reason.AGENT_BLOCKED) {
      returns.stage(UUID.randomUUID(), goal, Kind.BLOCKED, version, next.detail());
    } else if (next.state() == State.BLOCKED && next.reason() == Reason.UNCONFIRMED_TURN) {
      returns.stage(
          UUID.randomUUID(),
          goal,
          Kind.BLOCKED,
          version,
          "The Goal Agent could not confirm an earlier activation or dependency.");
    } else if (next.state() == State.CANCELLED) {
      returns.stage(
          UUID.randomUUID(), goal, Kind.CANCELLED, version, "The Goal Agent was cancelled.");
    }
  }

  Answered answer(String account, Goal observed, Answer request) {
    if (request.question() == null
        || FIRST.equals(request.question())
        || request.requestId() == null
        || FIRST.equals(request.requestId()))
      throw new CallerFault("Supply exact nonzero question and request UUIDs");
    var body = GoalText.require(request.answer(), "answer", 16000);
    if (!StandardCharsets.UTF_8.newEncoder().canEncode(body))
      throw new CallerFault("The Goal answer must be valid Unicode text");
    var goal = observed.binding().id();
    var prepared = runtime.restore(observed.binding());
    return transactions.transaction(
        () -> {
          var current = goals.lock(goal).orElseThrow(GoalReturns::missing);
          if (!current.binding().executor().equals(account)) throw missing();
          runtime.require(prepared);
          information.requireLog(current.binding().conversation(), account);
          if (returns.route(goal).filter(Route::human).isEmpty()) throw missing();
          var previous = returns.humanAttempt(goal, request.requestId());
          if (previous.isPresent()) {
            var receipt = previous.orElseThrow();
            if (!receipt.question().equals(request.question()) || !receipt.body().equals(body))
              throw new CallerFault("This request ID already belongs to a different Goal answer");
          } else {
            if (!questionOpen(current, request.question())
                || returns
                    .intent(request.question())
                    .filter(i -> i.delivery() == Delivery.DELIVERED)
                    .isEmpty()) throw missing();
            if (!returns.answer(
                goal, new HumanAnswer(request.question(), request.requestId(), body)))
              throw new CallerFault("This Goal question already has an answer");
          }
          return new Answered(
              goal, current.binding().conversation(), request.question(), request.requestId());
        });
  }

  /** The Messaging owner has already pinned its native question and locked the mailbox. */
  boolean stageGoalAnswer(Instance instance, MessageService.Route message) {
    if (message.replyTo() == null
        || !message.finalReply()
        || message.generated()
        || message.replyExpected()) return false;
    var selected = returns.questionByReceipt(message.replyTo());
    if (selected.isEmpty()) return false;
    var question = selected.orElseThrow();
    var route = question.route();
    var bot = route.bot();
    if (bot == null
        || !bot.sender().equals(message.sender())
        || !bot.recipient().equals(message.recipient())
        || !instance.id().equals(bot.recipient())) return false;
    return transactions.transaction(
        () -> {
          var current = goals.lock(route.goal()).orElseThrow(GoalReturns::missing);
          if (!questionOpen(current, question.id())) return false;
          try {
            runtime.restore(current.binding());
            information.requireLog(current.binding().conversation(), current.binding().executor());
          } catch (CallerFault | NotFoundFault unavailable) {
            return false;
          }
          return returns.answerFromBot(question.id(), message.message());
        });
  }

  record Reply(
      String identity,
      String body,
      String sourceConversation,
      GoalReturn bot,
      String questionMessage) {
    boolean human() {
      return bot == null;
    }
  }

  Optional<Reply> reply(Wait wait) {
    var id = wait.dependency().request();
    var intent =
        returns
            .intent(id)
            .filter(item -> item.route().goal().equals(wait.origin().goal()))
            .orElseThrow(GoalWaits.Unconfirmed::new);
    if (intent.route().human()) {
      var answer = returns.humanAnswer(id);
      if (answer.isPresent()) {
        var human = answer.orElseThrow();
        return Optional.of(new Reply(human.requestId().toString(), human.body(), null, null, null));
      }
    } else if (intent.delivery() == Delivery.DELIVERED) {
      var receipt = messages.goalAnswer(intent.route().bot(), intent.receipt());
      if (receipt instanceof GoalAnswerClosed) throw new GoalWaits.Unconfirmed();
      if (receipt instanceof GoalAnswerReady ready) {
        var answer = ready.message();
        if (!answer.id().equals(intent.answerMessage())) throw new GoalWaits.Unconfirmed();
        var source =
            messages
                .instance(intent.route().bot().sender())
                .map(Instance::conversation)
                .orElseThrow(GoalWaits.Unconfirmed::new);
        return Optional.of(
            new Reply(answer.id(), answer.body(), source, intent.route().bot(), intent.receipt()));
      }
    }
    if (intent.delivery() == Delivery.WITHHELD) throw new GoalWaits.Unconfirmed();
    return Optional.empty();
  }

  void claimReply(Reply reply, Runnable claim) {
    if (!messages.withGoalAnswer(reply.bot(), reply.questionMessage(), reply.identity(), claim))
      throw new GoalWaits.Unconfirmed();
  }

  void inherit(Reply reply, Binding binding) {
    information.inherit(reply.sourceConversation(), binding.conversation(), binding.executor());
  }

  private boolean questionOpen(Goal goal, UUID question) {
    var progress = goal.progress();
    if (progress.state() != State.WAITING && progress.state() != State.PAUSED) return false;
    return waits
        .open(goal.binding().id())
        .filter(
            wait ->
                wait.state() == GoalWaitStore.State.WAITING
                    && wait.dependency().kind() == GoalWaitStore.Kind.QUESTION
                    && question.equals(wait.dependency().request()))
        .isPresent();
  }

  void deliver(int limit) {
    var pending = returns.pendingAfter(deliveryCursor, limit);
    deliveryCursor = pending.size() == limit ? pending.getLast().id() : FIRST;
    for (var intent : pending) {
      try {
        try {
          publish(intent);
        } catch (CallerFault | NotFoundFault unavailable) {
          withhold(intent);
        }
      } catch (RuntimeException failure) {
        LOG.warn(
            "Goal return delivery could not confirm its receipt ({})",
            failure.getClass().getSimpleName());
      }
    }
    var cleanup = returns.questionsToClean(cleanupCursor, limit);
    cleanupCursor = cleanup.size() == limit ? cleanup.getLast().id() : FIRST;
    for (var intent : cleanup) {
      try {
        if (intent.route().human()) {
          var binding = goals.find(intent.route().goal()).orElseThrow().binding();
          inbox.settleQuestion(binding.executor(), binding.id(), intent.id());
        } else {
          messages.settleGoalQuestion(intent.route().bot(), intent.receipt());
        }
        returns.cleaned(intent.id());
      } catch (RuntimeException failure) {
        LOG.warn(
            "Goal Inbox question cleanup could not be confirmed ({})",
            failure.getClass().getSimpleName());
      }
    }
  }

  private void publish(Intent intent) {
    var goal = goals.find(intent.route().goal()).orElseThrow(GoalReturns::missing);
    var binding = goal.binding();
    var prepared = runtime.restore(binding);
    if (intent.route().human()) {
      transactions.transaction(
          () -> {
            requireCurrent(intent, prepared);
            var receipt =
                inbox.publish(
                    binding.executor(),
                    binding.id(),
                    binding.conversation(),
                    intent.id(),
                    GoalInbox.Kind.valueOf(intent.kind().name()));
            returns.delivered(intent.id(), receipt);
            return null;
          });
    } else {
      var attempt =
          messages.publishGoalReturn(
              intent.route().bot(),
              intent.id(),
              intent.body(),
              GoalReturnKind.valueOf(intent.kind().name()),
              receipt -> {
                requireCurrent(intent, prepared);
                returns.delivered(intent.id(), receipt);
              });
      if (attempt instanceof GoalReturnUnavailable) withhold(intent);
    }
  }

  private void withhold(Intent intent) {
    // Close a terminal native request even when its body has lost disclosure authority. The
    // caller can inspect the UNAVAILABLE outcome, and no new recipient run is dispatched.
    if (!intent.route().human()
        && (intent.kind() == Kind.RESULT || intent.kind() == Kind.CANCELLED))
      messages.withholdGoalReturn(intent.route().bot(), () -> returns.withheld(intent.id()));
    else returns.withheld(intent.id());
  }

  private void requireCurrent(Intent intent, GoalExecution.Prepared prepared) {
    // Native Messenger calls this with mailbox/request locks held. The Goal never tries to
    // acquire those locks while settling or answering a question.
    var current = goals.lock(intent.route().goal()).orElseThrow(GoalReturns::missing);
    runtime.require(prepared);
    information.requireLog(current.binding().conversation(), current.binding().executor());
    if (intent.kind() == Kind.QUESTION && !questionOpen(current, intent.id())) throw missing();
    if (intent.kind() == Kind.BLOCKED
        && (current.progress().state() != State.BLOCKED
            || current.version() != intent.goalVersion())) throw missing();
  }

  private static NotFoundFault missing() {
    return new NotFoundFault("Goal return or question is unavailable");
  }
}
