package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.agents.GoalTurn;
import io.aeyer.plowshare.server.agents.GoalTurn.Decision;
import io.aeyer.plowshare.server.agents.GoalTurn.DecisionType;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.agents.RunAdmission;
import io.aeyer.plowshare.server.agents.Turn.Refused;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.goals.GoalExecution.Prepared;
import io.aeyer.plowshare.server.goals.GoalRepository.Binding;
import io.aeyer.plowshare.server.goals.GoalRepository.Goal;
import io.aeyer.plowshare.server.goals.GoalRepository.Progress;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Drives bounded, server-owned turns from committed goal state. */
final class GoalService implements ConversationGoals {
  private static final Logger LOG = LoggerFactory.getLogger(GoalService.class);
  private static final UUID SCAN_START = new UUID(0, 0);
  private static final String UNCERTAIN =
      "The previous activation did not commit a settlement. Inspect its conversation and tool"
          + " receipts before starting more work.";

  private final GoalRepository repository;
  private final GoalExecution runtime;
  private final GoalProperties properties;

  // Owned by the single scheduler invocation. This is resource accounting, not recovery evidence.
  private final Set<String> activeJobs = new HashSet<>();
  private UUID cursor = SCAN_START;

  GoalService(GoalRepository repository, GoalExecution runtime, GoalProperties properties) {
    this.repository = repository;
    this.runtime = runtime;
    this.properties = properties;
  }

  @Override
  public Status start(String actor, Start request) {
    requireEnabled();
    String conversation = locator(request.conversation(), "conversation");
    String agent = locator(request.agent(), "agent");
    String objective = GoalText.require(request.objective(), "objective", 16000);
    String criteria =
        request.criteria() == null ? null : GoalText.require(request.criteria(), "criteria", 8000);
    UUID requestId = identity(request.requestId(), "requestId");
    String fingerprint =
        GoalText.digest(
            "start",
            conversation,
            agent,
            objective,
            criteria,
            nullableLimit(request.maxActivations()));
    return request(
        actor,
        requestId,
        conversation,
        fingerprint,
        () -> {
          int limit = activationLimit(request.maxActivations(), properties.defaultActivations());
          var record = runtime.conversation(conversation);
          if (!runtime.idle(conversation))
            throw new Refused("Conversation already has a turn in flight");
          var prepared = runtime.prepare(actor, record, agent);
          var binding =
              new Binding(
                  UUID.randomUUID(),
                  conversation,
                  prepared.authority().id(),
                  actor,
                  prepared.agent().name(),
                  GoalRuntime.fingerprint(prepared.agent()),
                  record.budget().identity(),
                  objective,
                  criteria);
          return repository.request(
              actor,
              requestId,
              conversation,
              fingerprint,
              () -> status(repository.insert(binding, limit), record));
        });
  }

  @Override
  public Status get(String actor, String conversation, UUID requestId) {
    requireEnabled();
    locator(conversation, "conversation");
    if (requestId != null) {
      return repository
          .receipt(actor, identity(requestId, "requestId"))
          .map(GoalRepository.Receipt::status)
          .filter(receipt -> receipt.conversation().equals(conversation))
          .orElseThrow(GoalService::missing);
    }
    var goal = repository.latest(conversation).orElseThrow(GoalService::missing);
    return status(goal, runtime.conversation(conversation));
  }

  @Override
  public Status control(String actor, Action action, Control request) {
    requireEnabled();
    String conversation = locator(request.conversation(), "conversation");
    UUID requestId = identity(request.requestId(), "requestId");
    UUID id = identity(request.goal(), "goal");
    if (request.version() < 1) throw new CallerFault("A positive goal version is required");
    if (action != Action.RESUME && request.maxActivations() != null)
      throw new CallerFault("Only resume can set an activation limit");
    String fingerprint =
        GoalText.digest(
            action.name(),
            conversation,
            id.toString(),
            Integer.toString(request.version()),
            nullableLimit(request.maxActivations()));
    return request(
        actor,
        requestId,
        conversation,
        fingerprint,
        () -> {
          var goal =
              repository
                  .find(id)
                  .filter(
                      found ->
                          found.binding().conversation().equals(conversation)
                              && found.binding().executor().equals(actor))
                  .orElseThrow(GoalService::missing);
          if (goal.version() != request.version()) throw changed();

          var record = runtime.conversation(conversation);
          var next =
              switch (action) {
                case PAUSE -> pause(goal.progress());
                case RESUME -> {
                  var progress = resume(goal.progress(), request.maxActivations());
                  var original = runtime.restore(goal.binding());
                  if (original.conversation().budget().exhausted())
                    throw new Refused(
                        "Extend the conversation's model-call allowance before resuming");
                  yield progress;
                }
                case CANCEL -> stop(goal.progress());
              };
          // The response is a receipt. If the process dies after this commit, the scheduler still
          // observes STOPPING; the thread cancellation flag is only a wake-up hint.
          var answer =
              repository.request(
                  actor,
                  requestId,
                  conversation,
                  fingerprint,
                  () ->
                      status(
                          repository.change(goal, next).orElseThrow(GoalService::changed), record));
          if (answer.state() == State.STOPPING) runtime.cancel(next.job());
          return answer;
        });
  }

  @Scheduled(
      fixedDelayString = "${plowshare.goals.scan-interval:PT2S}",
      scheduler = "conversationGoalScheduler")
  void dispatch() {
    if (!properties.enabled()) return;
    activeJobs.removeIf(job -> !runtime.active(job));
    var candidates = repository.scan(cursor, properties.scanSize());
    cursor =
        candidates.size() == properties.scanSize()
            ? candidates.getLast().binding().id()
            : SCAN_START;
    for (var goal : candidates) {
      try {
        if (goal.progress().state() == State.READY) {
          if (activeJobs.size() < properties.concurrency()
              && runtime.idle(goal.binding().conversation())) activate(goal);
        } else {
          reconcile(goal);
        }
      } catch (RuntimeException failure) {
        // A failed/uncertain database write cannot be evidence that a job never started.
        // Reconciliation uses the durable claim on the next pass without repeating that job.
        LOG.warn(
            "Conversation goal scheduler operation failed ({})",
            failure.getClass().getSimpleName());
      }
    }
  }

  private void activate(Goal goal) {
    Prepared prepared;
    try {
      prepared = runtime.restore(goal.binding());
    } catch (CallerFault | NotFoundFault refused) {
      pauseBeforeStart(
          goal,
          Reason.AUTHORITY_OR_SOURCE_CHANGED,
          "Goal access, scope or agent definition changed.");
      return;
    }
    if (prepared.conversation().budget().exhausted()) {
      pauseBeforeStart(
          goal, Reason.MODEL_ALLOWANCE, "Conversation model-call allowance exhausted.");
      return;
    }

    var binding = goal.binding();
    var progress = goal.progress();
    var job = new AtomicReference<String>();
    Runnable check =
        () -> {
          String current = job.get();
          if (current != null && !repository.running(binding.id(), current))
            throw new CallerFault("This activation no longer owns goal work");
          runtime.require(prepared);
        };
    var goalTurn =
        new GoalTurn(
            binding.id(),
            binding.conversation(),
            prepared.authority(),
            (id, ordinal, outcome, decision, failed, writeTurn) ->
                repository.settle(
                    binding.id(),
                    id,
                    writeTurn,
                    current -> settled(current.progress(), ordinal, outcome, decision, failed)));
    var admission =
        new RunAdmission(
            check,
            id -> {
              var running =
                  new Progress(
                      State.RUNNING,
                      progress.activations() + 1,
                      progress.limit(),
                      id,
                      id,
                      progress.lastTurn(),
                      null,
                      null,
                      progress.noProgress(),
                      progress.answerDigest());
              repository.change(goal, running).orElseThrow(GoalService::changed);
              job.set(id);
              activeJobs.add(id);
            },
            () -> {},
            binding.executor(),
            (home, references) -> runtime.requireTargets(prepared, home, references),
            goalTurn);
    try {
      runtime.start(binding, prepared, opening(binding, progress, prepared), admission);
    } catch (Refused | CallerFault refused) {
      if (job.get() == null)
        pauseBeforeStart(
            goal,
            Reason.INELIGIBLE_CONVERSATION,
            "The conversation could not admit this goal turn. Check its state or allowance.");
    }
  }

  private void reconcile(Goal goal) {
    var state = goal.progress();
    if (runtime.active(state.job())) {
      if (state.state() == State.STOPPING) runtime.cancel(state.job());
      return;
    }
    if (!runtime.idle(goal.binding().conversation())) return;
    repository.change(
        goal, state.move(State.BLOCKED, Reason.UNCONFIRMED_TURN, UNCERTAIN, null, state.limit()));
  }

  private Progress settled(
      Progress progress, int ordinal, Outcome outcome, Decision decision, boolean journalFailed) {
    State state;
    Reason reason = null;
    String detail = null;
    int noProgress = progress.noProgress();
    String digest = progress.answerDigest();
    if (progress.state() == State.STOPPING) {
      state = State.CANCELLED;
      reason = Reason.STOP_REQUESTED;
      if (journalFailed) detail = "Some activation evidence could not be recorded.";
    } else if (journalFailed) {
      state = State.BLOCKED;
      reason = Reason.UNCONFIRMED_TURN;
      detail = UNCERTAIN;
    } else if (outcome.ending() == Ending.ANSWERED && outcome.requested() && decision != null) {
      state = decision.state() == DecisionType.COMPLETE ? State.COMPLETE : State.BLOCKED;
      reason = state == State.BLOCKED ? Reason.AGENT_BLOCKED : null;
      detail = decision.explanation();
    } else if (progress.state() == State.PAUSING) {
      state = State.PAUSED;
      reason = Reason.OPERATOR_PAUSED;
    } else if (outcome.ending() != Ending.ANSWERED
        || !outcome.detail().isEmpty()
        || outcome.requested()) {
      state = State.PAUSED;
      reason = outcome.ending() == Ending.CALL_BUDGET ? Reason.MODEL_ALLOWANCE : Reason.TURN_FAILED;
      detail = outcome.ending().name();
    } else if (progress.activations() >= progress.limit()) {
      state = State.PAUSED;
      reason = Reason.ACTIVATION_LIMIT;
    } else {
      String answerDigest = GoalText.digest(outcome.text().strip());
      noProgress = outcome.text().isBlank() || answerDigest.equals(digest) ? noProgress + 1 : 0;
      digest = answerDigest;
      state = noProgress >= properties.maxNoProgress() ? State.PAUSED : State.READY;
      if (state == State.PAUSED) reason = Reason.NO_PROGRESS;
    }
    return new Progress(
        state,
        progress.activations(),
        progress.limit(),
        null,
        progress.lastJob(),
        ordinal,
        reason,
        detail,
        noProgress,
        digest);
  }

  private void pauseBeforeStart(Goal goal, Reason reason, String detail) {
    repository.change(
        goal, goal.progress().move(State.PAUSED, reason, detail, null, goal.progress().limit()));
  }

  private static Progress pause(Progress progress) {
    return switch (progress.state()) {
      case READY ->
          progress.move(State.PAUSED, Reason.OPERATOR_PAUSED, null, null, progress.limit());
      case RUNNING ->
          progress.move(
              State.PAUSING, Reason.OPERATOR_PAUSED, null, progress.job(), progress.limit());
      default -> throw new Refused("This goal is unable to pause in its current state");
    };
  }

  private Progress resume(Progress progress, Integer limit) {
    if (progress.state() != State.PAUSED
        && !(progress.state() == State.BLOCKED && progress.reason() == Reason.AGENT_BLOCKED))
      throw new Refused("This goal is unable to resume in its current state");
    int ceiling = activationLimit(limit, progress.limit());
    if (ceiling < progress.limit() || ceiling <= progress.activations())
      throw new Refused("Supply a larger goal activation limit to resume");
    return new Progress(
        State.READY,
        progress.activations(),
        ceiling,
        null,
        progress.lastJob(),
        progress.lastTurn(),
        null,
        null,
        0,
        null);
  }

  private static Progress stop(Progress progress) {
    return switch (progress.state()) {
      case READY, PAUSED, BLOCKED ->
          progress.move(
              State.CANCELLED,
              progress.reason() == Reason.UNCONFIRMED_TURN
                  ? Reason.UNCONFIRMED_TURN
                  : Reason.STOP_REQUESTED,
              progress.detail(),
              null,
              progress.limit());
      case RUNNING, PAUSING ->
          progress.move(
              State.STOPPING, Reason.STOP_REQUESTED, null, progress.job(), progress.limit());
      default -> throw new Refused("This goal is unable to stop in its current state");
    };
  }

  private int activationLimit(Integer requested, int fallback) {
    int limit = requested == null ? fallback : requested;
    if (limit < 1 || limit > properties.maxActivations())
      throw new CallerFault("Goal activation limit is outside the configured range");
    return limit;
  }

  private static String opening(Binding binding, Progress progress, Prepared prepared) {
    return "Continue this user-authorized goal in the current conversation. Use the retained work "
        + "and current evidence. A progress reply keeps the goal active. After verifying the "
        + "whole objective, use goal_update; use BLOCK for an obstacle requiring input.\n\n"
        + "Objective:\n"
        + binding.objective()
        + (binding.criteria() == null ? "" : "\n\nCompletion criteria:\n" + binding.criteria())
        + "\n\nActivation "
        + (progress.activations() + 1)
        + " of "
        + progress.limit()
        + ". Conversation model calls remaining: "
        + prepared.conversation().budget().remaining()
        + ".";
  }

  private static Status status(Goal goal, ConversationRecord record) {
    var binding = goal.binding();
    var progress = goal.progress();
    var budget = record.budget();
    return new Status(
        binding.id(),
        binding.conversation(),
        binding.agent(),
        binding.objective(),
        binding.criteria(),
        progress.state(),
        goal.version(),
        progress.activations(),
        progress.limit(),
        progress.lastJob(),
        progress.lastTurn(),
        progress.reason(),
        progress.detail(),
        budget == null ? null : budget.spent(),
        budget == null || !budget.capped() ? null : budget.limit());
  }

  private Status request(
      String actor,
      UUID request,
      String conversation,
      String fingerprint,
      Supplier<Status> submit) {
    var previous = repository.receipt(actor, request);
    if (previous.isPresent()) return replay(previous.get(), fingerprint, conversation);
    try {
      return submit.get();
    } catch (Refused | CallerFault | NotFoundFault refused) {
      // A concurrent duplicate can commit while this caller is checking busy/version/source.
      // Recover the exact receipt without resubmitting or treating an SQL failure as a refusal.
      return repository
          .receipt(actor, request)
          .map(receipt -> replay(receipt, fingerprint, conversation))
          .orElseThrow(() -> refused);
    }
  }

  private static Status replay(
      GoalRepository.Receipt receipt, String fingerprint, String conversation) {
    if (!receipt.fingerprint().equals(fingerprint)
        || !receipt.status().conversation().equals(conversation))
      throw new CallerFault("Request ID already belongs to a different goal command");
    return receipt.status();
  }

  private void requireEnabled() {
    if (!properties.enabled()) throw new Refused("Conversation goals are disabled on this server");
  }

  private static String locator(String value, String label) {
    GoalText.require(value, label, 1024);
    if (!value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl))
      throw new CallerFault("Supply an exact " + label + " identifier");
    return value;
  }

  private static UUID identity(UUID value, String label) {
    if (value == null || value.equals(SCAN_START))
      throw new CallerFault("A nonzero " + label + " UUID is required");
    return value;
  }

  private static String nullableLimit(Integer limit) {
    return limit == null ? null : limit.toString();
  }

  private static Refused changed() {
    return new Refused("Goal changed. Read the current version before submitting a new command.");
  }

  private static NotFoundFault missing() {
    return new NotFoundFault("Goal or goal receipt is unavailable");
  }
}
