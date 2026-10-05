package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedTurnCap;
import java.util.Objects;
import java.util.function.BiConsumer;

/** The production Runner: the same doors a person's run goes through, with no session. */
public final class AgentRunner implements Dispatcher.Runner {

  private final Callers callers;
  private final JobStore jobs;
  private final Turn turns;

  public AgentRunner(Callers callers, JobStore jobs, Turn turns) {
    this.callers = Objects.requireNonNull(callers, "callers");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.turns = Objects.requireNonNull(turns, "turns");
  }

  private io.aeyer.plowshare.server.personal.PersonalSpaces personal;

  @org.springframework.beans.factory.annotation.Autowired
  public void usePersonalSpaces(io.aeyer.plowshare.server.personal.PersonalSpaces personal) {
    this.personal = personal;
  }

  private java.util.function.Supplier<ScheduleDefinitions> definitions;
  private java.util.function.Supplier<ScheduleDefinitions.Authority> scheduleAuthority;
  private java.util.function.Supplier<io.aeyer.plowshare.server.board.BoardMessaging> messages;

  /** Installed at composition; ordinary event triggers retain the same path. */
  public void useSchedules(
      java.util.function.Supplier<ScheduleDefinitions> definitions,
      java.util.function.Supplier<ScheduleDefinitions.Authority> authority,
      java.util.function.Supplier<io.aeyer.plowshare.server.board.BoardMessaging> messages) {
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
            ? java.util.Optional.<io.aeyer.plowshare.protocol.ScheduledWork.File>empty()
            : configured.managed(trigger.name(), trigger.definedBy());
    if (managed.isEmpty()) {
      if (configured != null && configured.requiresDefinition(trigger.name(), trigger.definedBy()))
        throw new IllegalStateException("The registered schedule source is missing");
      return new Dispatcher.Started.Job(start(trigger, utterance, ended));
    }
    var file = managed.get();
    if (!file.status().equals("active") || file.definition() == null || file.definition().paused())
      throw new IllegalStateException("The schedule source is suspended or paused");
    var source = configured.sourceOf(trigger.name(), trigger.definedBy()).orElseThrow();
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
    return new Dispatcher.Started.Job(
        start(
            trigger,
            file.definition().action().kind().equals("agent")
                ? utterance
                : file.definition().action().utterance(),
            configured.executionSession(source),
            ended));
  }

  @Override
  public boolean busy(TriggerRecord trigger) {
    return trigger.conversation() != null && turns.isSpeaking(trigger.conversation());
  }

  @Override
  public String start(TriggerRecord trigger, String utterance, BiConsumer<String, Outcome> ended) {
    return start(trigger, utterance, null, ended);
  }

  private String start(
      TriggerRecord trigger, String utterance, String session, BiConsumer<String, Outcome> ended) {
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
      return jobs.submitEvent(
              definition,
              utterance,
              home,
              session,
              trigger.maxModelCalls(),
              cap,
              trigger.definedBy(),
              Speaker.event(trigger.name()),
              ended)
          .id();
    }
    String conversation = trigger.conversation();
    callers.requireConversationProject(conversation, trigger.definedBy());
    AgentDefinition definition =
        callers.requireAgent(trigger.agent(), callers.callerForConversation(conversation, session));
    // A trigger's limits apply to untargeted runs only: a turn in a conversation runs under
    // that conversation's ceilings, as a person's own turn there does. trigger.define refuses
    // limits beside a conversation; null here also covers a row stored before it did.
    //
    // The trigger speaks, not the account that defined it: the task is the harness's words
    // on the trigger's behalf, and a reader of the log must never be told the person said it.
    return turns.speak(
        conversation,
        definition,
        utterance,
        session,
        null,
        Speaker.event(trigger.name()),
        outcome -> ended.accept(conversation, outcome));
  }
}
