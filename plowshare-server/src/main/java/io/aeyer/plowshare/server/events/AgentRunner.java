package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.EventRuns;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.agents.TurnEligibility;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedTurnCap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;

/** The production Runner: the same doors a person's run goes through, with no session. */
public final class AgentRunner implements Dispatcher.Runner {

  /** Instruction from a saved schedule, minted only on the server's scheduled firing path. */
  public sealed interface ScheduledTask permits SavedTask {
    String owner();

    String instruction();

    /**
     * Whether the saved instruction comes from an account-owned trigger or a server-side Personal
     * schedule. Shared or workspace-editable schedule files cannot speak for their registrar.
     */
    boolean personalFileReview();
  }

  private record SavedTask(String owner, String instruction, boolean personalFileReview)
      implements ScheduledTask {}

  private final Callers callers;
  private final EventRuns jobs;
  private final Turn turns;

  public AgentRunner(Callers callers, EventRuns jobs, Turn turns) {
    this.callers = Objects.requireNonNull(callers, "callers");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.turns = Objects.requireNonNull(turns, "turns");
  }

  private PersonalSpaces personal;

  @Autowired
  public void usePersonalSpaces(PersonalSpaces personal) {
    this.personal = personal;
  }

  private Supplier<ScheduleDefinitions> definitions;
  private Supplier<ScheduleDefinitions.Authority> scheduleAuthority;
  private Supplier<MessageService> messages;

  /** Installed at composition; ordinary event triggers retain the same path. */
  public void useSchedules(
      Supplier<ScheduleDefinitions> definitions,
      Supplier<ScheduleDefinitions.Authority> authority,
      Supplier<MessageService> messages) {
    this.definitions = definitions;
    this.scheduleAuthority = authority;
    this.messages = messages;
  }

  @Override
  public Dispatcher.Started start(
      TriggerRecord trigger,
      FiringRecord firing,
      String utterance,
      BiConsumer<String, Outcome> ended) {
    var configured = definitions == null ? null : definitions.get();
    var managed =
        configured == null
            ? Optional.<ScheduledWork.File>empty()
            : configured.managed(trigger.name(), trigger.definedBy());
    if (managed.isEmpty()) {
      if (configured != null && configured.requiresDefinition(trigger.name(), trigger.definedBy()))
        throw new IllegalStateException("The registered schedule source is missing");
      return new Dispatcher.Started.Job(
          start(
              trigger,
              utterance,
              null,
              ended,
              scheduledTask(trigger, firing, trigger.task(), true)));
    }
    var file = managed.get();
    if (!file.status().equals("active") || file.definition() == null || file.definition().paused())
      throw new IllegalStateException("The schedule source is suspended or paused");
    var source = configured.sourceOf(trigger.name(), trigger.definedBy()).orElseThrow();
    if (!Objects.equals(source.account(), trigger.definedBy()))
      throw new IllegalStateException("Schedule source cannot change its execution principal");
    scheduleAuthority.get().validate(source, file.definition());
    if (file.definition().target().kind().equals("message")) {
      var messaging = messages.get();
      if (messaging == null)
        throw new IllegalStateException("Messaging is unavailable on this server");
      var task =
          messaging.receiveScheduled(
              trigger.definedBy(),
              source.project(),
              trigger.name(),
              firing.id(),
              file.definition());
      ended.accept(
          null,
          new Outcome(
              Outcome.Ending.ANSWERED,
              "Scheduled message accepted as task "
                  + task.id()
                  + " (message "
                  + task.message()
                  + "); inspect message.delivery for downstream handling.",
              0,
              0,
              ""));
      return new Dispatcher.Started.Message(task.message());
    }
    // Explicit command inputs are pinned as written; event metadata never becomes skill arguments.
    var action = file.definition().action();
    String task = action.utterance();
    boolean agentAction = action.kind().equals("agent");
    return new Dispatcher.Started.Job(
        start(
            trigger,
            agentAction ? utterance : task,
            configured.executionSession(source),
            ended,
            // An agent's rendered input came from the trigger snapshot. Do not vouch for it
            // if the registered definition changed in the meantime.
            agentAction && !task.equals(trigger.task())
                ? null
                : scheduledTask(
                    trigger,
                    firing,
                    task,
                    source.source().equals("server")
                        && PersonalSpaces.name(source.account()).equals(source.project()))));
  }

  @Override
  public boolean busy(TriggerRecord trigger) {
    return !TurnEligibility.decide(
            TurnEligibility.Reason.READY,
            () -> trigger.conversation() != null && turns.isSpeaking(trigger.conversation()),
            () -> false)
        .eligible();
  }

  @Override
  public String start(TriggerRecord trigger, String utterance, BiConsumer<String, Outcome> ended) {
    return start(trigger, utterance, null, ended, null);
  }

  private static ScheduledTask scheduledTask(
      TriggerRecord trigger, FiringRecord firing, String task, boolean personalFileReview) {
    return firing.data() instanceof EventPayload.Scheduled && trigger.definedBy() != null
        ? new SavedTask(trigger.definedBy(), task, personalFileReview)
        : null;
  }

  private String start(
      TriggerRecord trigger,
      String utterance,
      String session,
      BiConsumer<String, Outcome> ended,
      ScheduledTask scheduled) {
    if (trigger.conversation() == null) {
      TurnCap cap = RequestedTurnCap.in(trigger.maxTurns(), null, "this trigger's run");
      var home =
          personal == null
              ? RequestedHome.in(trigger.project())
              : personal.home(trigger.project(), trigger.definedBy());
      callers.requireWork(home.project(), trigger.definedBy());
      AgentDefinition definition =
          callers.requireAgent(
              trigger.agent(), callers.callerFor(home.project(), session, trigger.definedBy()));
      return jobs.submitTriggeredEvent(
              definition,
              utterance,
              home,
              session,
              trigger.maxModelCalls(),
              cap,
              trigger.definedBy(),
              Speaker.event(trigger.name()),
              ended,
              scheduled)
          .id();
    }
    String conversation = trigger.conversation();
    callers.requireConversationWork(conversation, trigger.definedBy());
    AgentDefinition definition =
        callers.requireAgent(trigger.agent(), callers.callerForConversation(conversation, session));
    // A trigger's limits apply to untargeted runs only: a turn in a conversation runs under
    // that conversation's ceilings, as a person's own turn there does. trigger.define refuses
    // limits beside a conversation; null here also covers a row stored before it did.
    //
    // The trigger speaks, not the account that defined it: the task is the harness's words
    // on the trigger's behalf, and a reader of the log must never be told the person said it.
    return turns.speakForExecutor(
        conversation,
        definition,
        utterance,
        session,
        null,
        Speaker.event(trigger.name()),
        outcome -> ended.accept(conversation, outcome),
        trigger.definedBy(),
        scheduled);
  }
}
