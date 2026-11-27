package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.approvals.GoalApprovalContinuations;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.SuspendedRunCalls;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.goals.ConversationGoals.State;
import io.aeyer.plowshare.server.goals.DedicatedGoalAgents.ClaimDeferred;
import io.aeyer.plowshare.server.goals.GoalExecution.Prepared;
import io.aeyer.plowshare.server.goals.GoalRepository.Binding;
import io.aeyer.plowshare.server.goals.GoalRepository.Goal;
import io.aeyer.plowshare.server.goals.GoalWaitStore.Dependency;
import io.aeyer.plowshare.server.goals.GoalWaitStore.Kind;
import io.aeyer.plowshare.server.goals.GoalWaitStore.Origin;
import io.aeyer.plowshare.server.goals.GoalWaitStore.Wait;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrations;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrations.Caller;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrations.Owner;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrations.Request;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrations.Run;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Coordinates parked Goal activations with the owners of their exact retained dependencies. */
final class GoalWaits implements GoalApprovalContinuations {
  @FunctionalInterface
  interface WithdrawApproval {
    void withdraw(String approval);
  }

  record Ready(Wait retained, String dependency, Run result, GoalReturns.Reply reply) {
    Ready(Wait retained, String dependency, Run result) {
      this(retained, dependency, result, null);
    }

    boolean approval() {
      return retained.dependency().kind() == Kind.APPROVAL;
    }
  }

  static final class Unconfirmed extends RuntimeException {
    Unconfirmed() {
      super("The original Goal dependency could not be safely reconciled.");
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(GoalWaits.class);
  private static final UUID FIRST = new UUID(0, 0);
  private final GoalWaitStore waits;
  private final GoalRepository goals;
  private final GoalAgentStore inbox;
  private final SuspendedRunCalls calls;
  private final WithdrawApproval approvals;
  private final GoalOrchestrations orchestrations;
  private final InformationJobs information;
  private final GoalReturns returns;
  private UUID cleanupCursor = FIRST;

  GoalWaits(
      GoalWaitStore waits,
      GoalRepository goals,
      GoalAgentStore inbox,
      SuspendedRunCalls calls,
      WithdrawApproval approvals,
      GoalOrchestrations orchestrations,
      InformationJobs information) {
    this(waits, goals, inbox, calls, approvals, orchestrations, information, null);
  }

  GoalWaits(
      GoalWaitStore waits,
      GoalRepository goals,
      GoalAgentStore inbox,
      SuspendedRunCalls calls,
      WithdrawApproval approvals,
      GoalOrchestrations orchestrations,
      InformationJobs information,
      GoalReturns returns) {
    this.waits = waits;
    this.goals = goals;
    this.inbox = inbox;
    this.calls = calls;
    this.approvals = approvals;
    this.orchestrations = orchestrations;
    this.information = information;
    this.returns = returns;
  }

  @Override
  public boolean ownsConversation(String conversation) {
    return waits.dedicatedConversation(conversation);
  }

  Optional<Wait> open(UUID goal) {
    return waits.open(goal);
  }

  void retainApproval(Origin origin, String approval, Runnable retainCall) {
    inbox.transaction(
        () -> {
          requireOrigin(origin);
          // The approval call's journal and the Goal pointer have one commit. Notification
          // delivery is owned by the harness after this turn's settlement.
          retainCall.run();
          waits.arm(origin, Dependency.approval(approval));
          return null;
        });
  }

  void retainQuestion(Origin origin) {
    inbox.transaction(
        () -> {
          requireOrigin(origin);
          waits.arm(origin, Dependency.question(UUID.randomUUID()));
          return null;
        });
  }

  void startOrchestration(
      Origin origin, Prepared prepared, String conversation, String name, String request) {
    var caller = caller(prepared, conversation);
    var definition =
        orchestrations
            .granted(caller, name)
            .orElseThrow(() -> new CallerFault("The Goal Agent is not granted this orchestration"));
    var submitted = new Request(UUID.randomUUID(), definition.name(), request, definition.hash());
    var armed =
        inbox.transaction(
            () -> {
              requireOrigin(origin);
              return waits.arm(
                  origin,
                  new Dependency(
                      Kind.ORCHESTRATION,
                      null,
                      submitted.invocation(),
                      submitted.expectedHash(),
                      submitted.name()));
            });
    try {
      // The owning start may allocate a conductor and request a job. No Goal SQL lock is held.
      // If its reply is lost, only the immutable owner receipt can recover this invocation.
      orchestrations.start(
          caller,
          submitted,
          () -> {
            // The owner calls this inside its acceptance transaction. The Goal lock linearizes
            // accepting the exact child against cancellation without spanning child execution.
            requireOrigin(origin);
            waits
                .open(origin.goal())
                .filter(
                    current ->
                        current.id().equals(armed.id())
                            && current.state() == GoalWaitStore.State.PARKING)
                .orElseThrow(ClaimDeferred::new);
          });
    } catch (RuntimeException uncertain) {
      LOG.warn(
          "Goal orchestration start could not confirm its receipt ({})",
          uncertain.getClass().getSimpleName());
    }
  }

  private void requireOrigin(Origin origin) {
    goals
        .lock(origin.goal())
        .filter(goal -> goal.cycle() != null && goal.cycle().id().equals(origin.cycle()))
        .filter(goal -> origin.job().equals(goal.progress().job()))
        .filter(
            goal ->
                goal.progress().state() == State.RUNNING
                    || goal.progress().state() == State.PAUSING)
        .orElseThrow(ClaimDeferred::new);
  }

  Optional<Ready> ready(Goal goal, Wait wait, Prepared prepared) {
    if (wait.state() != GoalWaitStore.State.WAITING
        || goal.cycle() == null
        || !wait.origin().cycle().equals(goal.cycle().id())) throw new Unconfirmed();
    var dependency = wait.dependency();
    if (dependency.kind() == Kind.QUESTION) {
      if (returns == null) throw new Unconfirmed();
      return returns.reply(wait).map(reply -> new Ready(wait, reply.identity(), null, reply));
    }
    if (dependency.kind() == Kind.ORCHESTRATION) {
      Run result =
          orchestrations
              .inspect(
                  caller(prepared, goal.binding().conversation()),
                  dependency.request(),
                  dependency.definition(),
                  dependency.hash())
              .orElseThrow(Unconfirmed::new);
      return result.endedAt() == null
          ? Optional.empty()
          : Optional.of(new Ready(wait, result.id(), result));
    }
    var call =
        calls
            .find(dependency.reference())
            .filter(
                found ->
                    found.conversation().equals(goal.binding().conversation())
                        && Objects.equals(found.owner(), goal.binding().executor())
                        && found.session() == null
                        && found.tool().name().equals("file_read")
                        && found.tool().id().equals(wait.origin().call()))
            .orElseThrow(Unconfirmed::new);
    if (!call.released() || RunApproval.ASKED.equals(call.decision())) return Optional.empty();
    if ("started".equals(call.state()) && call.result() == null) throw new Unconfirmed();
    return Optional.of(new Ready(wait, call.approval(), null));
  }

  void allowClaim(Ready ready, Runnable claim) {
    if (ready != null && ready.reply() != null && !ready.reply().human())
      returns.claimReply(ready.reply(), claim);
    else claim.run();
  }

  void claim(Ready ready, Binding binding, Prepared prepared, String job) {
    var endedAt = ready.result() == null ? null : ready.result().endedAt();
    if (ready.result() != null) {
      var dependency = ready.retained().dependency();
      if (!orchestrations.stillTerminal(
          caller(prepared, binding.conversation()),
          dependency.request(),
          dependency.definition(),
          dependency.hash(),
          ready.result())) throw new ClaimDeferred();
      // The result's original inputs remain a disclosure requirement in subsequent turns.
      information.inherit(
          ready.result().conversation(), binding.conversation(), binding.executor());
    }
    if (ready.reply() != null && !ready.reply().human()) returns.inherit(ready.reply(), binding);
    waits.claim(ready.retained(), ready.dependency(), endedAt, job);
  }

  boolean stillTerminal(Ready ready, Prepared prepared, String conversation) {
    var dependency = ready.retained().dependency();
    var caller = caller(prepared, conversation);
    return orchestrations.stillGranted(caller, dependency.definition(), dependency.hash())
        && orchestrations.stillTerminal(
            caller,
            dependency.request(),
            dependency.definition(),
            dependency.hash(),
            ready.result());
  }

  void requireReadable(Binding binding) {
    information.requireLog(binding.conversation(), binding.executor());
  }

  private static Caller caller(Prepared prepared, String conversation) {
    return new Caller(conversation, prepared.agent(), prepared.authority());
  }

  void completed(Ready ready, String job) {
    inbox.transaction(
        () -> {
          // Cancel and continuation cleanup share the Goal lock. The owner has already
          // journaled its result, so losing this race cannot authorize another execution.
          var goal = goals.lock(ready.retained().origin().goal()).orElseThrow();
          if (job.equals(goal.progress().job()) && goal.progress().state() != State.STOPPING)
            waits.settle(ready.retained(), job);
          return null;
        });
  }

  void park(Wait wait) {
    waits.park(wait);
  }

  void abandon(UUID goal) {
    waits.abandon(goal);
  }

  void cleanup(int limit) {
    for (String orphan : waits.orphanedApprovals(limit)) {
      try {
        approvals.withdraw(orphan);
      } catch (RuntimeException failure) {
        LOG.warn(
            "Orphaned Goal approval could not be withdrawn ({})",
            failure.getClass().getSimpleName());
      }
    }
    var abandoned = waits.cleanupAfter(cleanupCursor, limit);
    cleanupCursor = abandoned.size() == limit ? abandoned.getLast().id() : FIRST;
    for (var wait : abandoned) {
      try {
        var goal = goals.find(wait.origin().goal()).orElseThrow();
        if (goal.progress().job() != null) continue;
        var dependency = wait.dependency();
        var binding = goal.binding();
        if (dependency.kind() == Kind.APPROVAL) {
          approvals.withdraw(dependency.reference());
          calls.retireGoalCall(binding.conversation(), dependency.reference());
        } else if (dependency.kind() == Kind.ORCHESTRATION) {
          orchestrations.cancel(
              new Owner(
                  binding.conversation(), binding.agent(), binding.executor(), binding.admission()),
              dependency.request(),
              dependency.definition(),
              dependency.hash());
        }
        waits.cleaned(wait.id());
      } catch (RuntimeException failure) {
        LOG.warn(
            "Goal dependency cleanup could not finish ({})", failure.getClass().getSimpleName());
      }
    }
  }
}
