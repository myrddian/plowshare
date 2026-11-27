package io.aeyer.plowshare.server.goals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AccessRequest.Resource;
import io.aeyer.plowshare.server.access.AuthorityAction;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.GrantRecipient;
import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.access.OperationAuthority.Support;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.GoalTestActivation;
import io.aeyer.plowshare.server.agents.GoalTurn.Decision;
import io.aeyer.plowshare.server.agents.GoalTurn.DecisionType;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Pace;
import io.aeyer.plowshare.server.agents.RunAdmission;
import io.aeyer.plowshare.server.agents.Turn.Refused;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.goals.ConversationGoals.Action;
import io.aeyer.plowshare.server.goals.ConversationGoals.Control;
import io.aeyer.plowshare.server.goals.ConversationGoals.Reason;
import io.aeyer.plowshare.server.goals.ConversationGoals.Start;
import io.aeyer.plowshare.server.goals.ConversationGoals.State;
import io.aeyer.plowshare.server.goals.ConversationGoals.Status;
import io.aeyer.plowshare.server.messaging.MessageService.InstanceView;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class GoalServiceTest {
  private static final String ACTOR = "operator";
  private static final String CONVERSATION = "cnv_fixture";
  private static final Outcome PROGRESS =
      new Outcome(Outcome.Ending.ANSWERED, "Step checked", 1, 1, "");

  private final Store store = new Store();
  private final Runtime runtime = new Runtime();
  private GoalService goals = service();

  @Test
  void continues_without_a_client_and_after_restart_at_the_committed_frontier() {
    Status started = start(3);
    goals.dispatch();
    runtime.finish(PROGRESS, null);
    assertEquals(State.READY, status().state());
    assertEquals(1, status().lastTurn());
    assertTrue(runtime.opening.contains("Audit the fixture"));
    assertTrue(runtime.opening.contains("Activation 1 of 3"));

    goals = service();
    goals.dispatch();
    assertTrue(runtime.opening.contains("Activation 2 of 3"));
    runtime.finish(
        answer("Verified"), new Decision(DecisionType.COMPLETE, "Verified", List.of("turn 1")));

    assertEquals(started.goal(), status().goal());
    assertEquals(State.COMPLETE, status().state());
    assertEquals(2, status().activations());
    assertEquals(2, status().lastTurn());
    assertEquals(2, status().modelCallsSpent());
    goals.dispatch();
    assertEquals(2, runtime.dispatched);
  }

  @Test
  void terminal_job_without_settlement_blocks_and_is_never_replayed() {
    start(4);
    goals.dispatch();
    runtime.invocation = null;
    goals = service();
    goals.dispatch();
    var blocked = status();
    assertEquals(State.BLOCKED, blocked.state());
    assertEquals(Reason.UNCONFIRMED_TURN, blocked.reason());
    assertEquals("job_1", blocked.lastJob());
    assertThrows(Refused.class, () -> control(Action.RESUME, blocked, null));
    goals.dispatch();
    assertEquals(1, runtime.dispatched);
  }

  @Test
  void pause_drains_current_turn_but_stop_fences_effects_and_late_completion() {
    start(3);
    goals.dispatch();
    assertEquals(State.PAUSING, control(Action.PAUSE, status(), null).state());
    runtime.invocation.check().run();
    runtime.finish(PROGRESS, null);
    assertEquals(State.PAUSED, status().state());
    goals.dispatch();
    assertEquals(1, runtime.dispatched);

    control(Action.RESUME, status(), null);
    goals.dispatch();
    var stopping = control(Action.CANCEL, status(), null);
    assertEquals(State.STOPPING, stopping.state());
    assertEquals("job_2", runtime.cancelled);
    assertThrows(CallerFault.class, runtime.invocation.check()::run);
    runtime.finish(
        answer("Too late"), new Decision(DecisionType.COMPLETE, "Too late", List.of("turn 1")));
    assertEquals(State.CANCELLED, status().state());
  }

  @Test
  void user_message_busy_and_budgets_do_not_create_extra_activations() {
    start(1);
    runtime.foreground = true;
    goals.dispatch();
    assertEquals(0, runtime.dispatched);
    runtime.foreground = false;
    goals.dispatch();
    runtime.finish(PROGRESS, null);
    assertEquals(Reason.ACTIVATION_LIMIT, status().reason());
    assertThrows(Refused.class, () -> control(Action.RESUME, status(), null));
    assertEquals(State.READY, control(Action.RESUME, status(), 2).state());

    runtime.revoked = true;
    goals.dispatch();
    assertEquals(Reason.AUTHORITY_OR_SOURCE_CHANGED, status().reason());
    assertEquals(1, runtime.dispatched);
  }

  @Test
  void repeated_mutation_returns_exact_receipt_and_foreign_controls_are_refused() {
    var request =
        new Start(CONVERSATION, UUID.randomUUID(), "worker", "Audit the fixture", null, 4);
    var receipt = goals.start(ACTOR, request);
    goals.dispatch();
    assertEquals(State.RUNNING, status().state());
    store.skipNextReceipt = true;
    assertEquals(receipt, goals.start(ACTOR, request));
    assertEquals(receipt, goals.get(ACTOR, CONVERSATION, request.requestId()));
    assertThrows(NotFoundFault.class, () -> goals.get("other", CONVERSATION, request.requestId()));
    assertThrows(
        CallerFault.class,
        () ->
            goals.start(
                ACTOR,
                new Start(CONVERSATION, request.requestId(), "worker", "Different task", null, 4)));
    var versioned =
        new Control(CONVERSATION, UUID.randomUUID(), receipt.goal(), status().version(), null);
    assertThrows(NotFoundFault.class, () -> goals.control("other", Action.CANCEL, versioned));
    var pause = goals.control(ACTOR, Action.PAUSE, versioned);
    runtime.finish(PROGRESS, null);
    store.skipNextReceipt = true;
    assertEquals(pause, goals.control(ACTOR, Action.PAUSE, versioned));
    assertThrows(
        Refused.class,
        () ->
            goals.control(
                ACTOR,
                Action.RESUME,
                new Control(
                    CONVERSATION, UUID.randomUUID(), receipt.goal(), pause.version(), null)));
  }

  private GoalService service() {
    return new GoalService(
        store, runtime, new GoalProperties(true, 3, 10, 1, 5, 2, 8, Duration.ofMinutes(1)));
  }

  private Status start(int limit) {
    return goals.start(
        ACTOR,
        new Start(
            CONVERSATION,
            UUID.randomUUID(),
            "worker",
            "Audit the fixture",
            "Check all items",
            limit));
  }

  private Status control(Action action, Status status, Integer ceiling) {
    return goals.control(
        ACTOR,
        action,
        new Control(CONVERSATION, UUID.randomUUID(), status.goal(), status.version(), ceiling));
  }

  private Status status() {
    return goals.get(ACTOR, CONVERSATION, null);
  }

  private static Outcome answer(String text) {
    return new Outcome(Outcome.Ending.ANSWERED, text, 1, 1, "", Pace.NONE, true);
  }

  private final class Runtime implements GoalExecution {
    @Override
    public List<GoalHandoffs.Candidate> handoffCandidates(GoalHandoffs.Caller caller) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AgentDefinition handoffTarget(GoalHandoffs.Caller caller, String agent) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Prepared prepareHandoff(
        GoalHandoffs.Caller caller, InstanceView instance, AgentDefinition target) {
      throw new UnsupportedOperationException();
    }

    private final Budget budget = Budget.of(8);
    private final Home home = Home.of("project");
    private final ConversationRecord conversation =
        new ConversationRecord(
            CONVERSATION,
            home,
            Origin.TURN,
            ConversationLifecycle.ACTIVE,
            null,
            null,
            Instant.EPOCH,
            null,
            budget,
            null,
            null);
    private final AccountId account = new AccountId(UUID.randomUUID());
    private final Admission authority =
        new Admission(
            UUID.randomUUID(),
            ACTOR,
            account,
            new ScopeId(UUID.randomUUID()),
            home,
            GrantRecipient.account(account),
            List.of(new Support(AuthorityAction.READ, UUID.randomUUID())));
    private final AgentDefinition agent =
        new AgentDefinition(
            "worker",
            "Worker",
            "model",
            List.of(),
            List.of(),
            List.of(),
            4,
            8,
            "Audit the project.");
    private RunAdmission invocation;
    private int dispatched;
    private String opening;
    private String cancelled;
    private boolean foreground;
    private boolean revoked;

    @Override
    public ConversationRecord conversation(String id) {
      return conversation;
    }

    @Override
    public Prepared prepare(String executor, ConversationRecord record, String name) {
      return new Prepared(record, agent, authority);
    }

    @Override
    public Prepared restore(GoalRepository.Binding binding) {
      if (revoked) throw new CallerFault("Revoked");
      return new Prepared(conversation, agent, authority);
    }

    @Override
    public Prepared prepareDedicated(String executor, InstanceView inbox) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void require(Prepared prepared) {}

    @Override
    public void requireTargets(Prepared prepared, Home target, List<Resource> resources) {}

    @Override
    public boolean idle(String id) {
      return invocation == null && !foreground;
    }

    @Override
    public Optional<String> start(
        GoalRepository.Binding binding, Prepared prepared, String prompt, RunAdmission admission) {
      String job = "job_" + ++dispatched;
      admission.check().run();
      admission.started().accept(job);
      invocation = admission;
      opening = prompt;
      return Optional.of(job);
    }

    @Override
    public void cancel(String job) {
      cancelled = job;
    }

    @Override
    public boolean active(String job) {
      return invocation != null && job.equals("job_" + dispatched);
    }

    void finish(Outcome result, Decision decision) {
      assertTrue(budget.trySpend());
      GoalTestActivation.settle(
          invocation.goal(), "job_" + dispatched, dispatched, result, decision, () -> {});
      invocation.ended().run();
      invocation = null;
    }
  }

  private static final class Store implements GoalRepository {
    record RequestKey(String actor, UUID request) {}

    private final Map<RequestKey, Receipt> receipts = new HashMap<>();
    private Goal goal;
    private boolean skipNextReceipt;

    @Override
    public Optional<Goal> latest(String conversation) {
      return Optional.ofNullable(goal);
    }

    @Override
    public Optional<Goal> find(UUID id) {
      return Optional.ofNullable(goal).filter(found -> found.binding().id().equals(id));
    }

    @Override
    public boolean running(UUID id, String job) {
      return find(id)
          .filter(found -> job.equals(found.progress().job()))
          .filter(found -> Set.of(State.RUNNING, State.PAUSING).contains(found.progress().state()))
          .isPresent();
    }

    @Override
    public Optional<Receipt> receipt(String actor, UUID request) {
      if (skipNextReceipt) {
        skipNextReceipt = false;
        return Optional.empty();
      }
      return Optional.ofNullable(receipts.get(new RequestKey(actor, request)));
    }

    @Override
    public Status request(
        String actor,
        UUID request,
        String conversation,
        String fingerprint,
        Supplier<Status> change) {
      return receipts
          .computeIfAbsent(
              new RequestKey(actor, request), key -> new Receipt(fingerprint, change.get()))
          .status();
    }

    @Override
    public Goal insert(Binding binding, int limit) {
      goal =
          new Goal(
              binding,
              1,
              new Progress(State.READY, 0, limit, null, null, null, null, null, 0, null));
      return goal;
    }

    @Override
    public Optional<Goal> change(Goal before, Progress after) {
      if (!before.equals(goal)) return Optional.empty();
      goal = new Goal(before.binding(), before.version() + 1, after);
      return Optional.of(goal);
    }

    @Override
    public void settle(UUID id, String job, Runnable writeTurn, Function<Goal, Settlement> next) {
      assertEquals(job, goal.progress().job());
      writeTurn.run();
      change(goal, next.apply(goal).progress()).orElseThrow();
    }

    @Override
    public List<Goal> scan(UUID after, int limit) {
      assertFalse(limit < 1);
      return goal != null
              && Set.of(State.READY, State.RUNNING, State.PAUSING, State.STOPPING)
                  .contains(goal.progress().state())
          ? List.of(goal)
          : List.of();
    }
  }
}
