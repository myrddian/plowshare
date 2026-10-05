package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.board.BoardMessaging;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;

/** Reuses caller catalogs and messaging route policy; JSON cannot grant a command or a project. */
public final class ScheduleActionAuthority implements ScheduleDefinitions.Authority {
  private final ScheduleFiles files;
  private final Callers callers;
  private final SkillResolver skills;
  private final OrchestrationResolver orchestrations;
  private final BoardMessaging.Routing routing;
  private final java.util.function.Supplier<BoardMessaging> messages;

  public ScheduleActionAuthority(
      ScheduleFiles files,
      Callers callers,
      SkillResolver skills,
      OrchestrationResolver orchestrations,
      BoardMessaging.Routing routing,
      java.util.function.Supplier<BoardMessaging> messages) {
    this.files = files;
    this.callers = callers;
    this.skills = skills;
    this.orchestrations = orchestrations;
    this.routing = routing;
    this.messages = messages;
  }

  public void validate(ScheduleDefinitionStore.Source source, ScheduledWork definition) {
    var target = definition.target();
    String session = files.executionSession(source);
    String project = target.project() == null ? source.project() : target.project();
    if (target.kind().equals("message")) {
      if (source.project() == null)
        throw new CallerFault("Message schedules need a source project");
      var address =
          routing.resolve(
              source.account(), source.project(), target.project(), target.to(), target.route());
      project = address.project();
      String agent = address.to();
      if (agent.startsWith("ins_")) {
        var messaging = messages.get();
        if (messaging == null) throw new CallerFault("Messaging is unavailable");
        var instance =
            messaging
                .instance(agent)
                .filter(i -> i.account().equals(source.account()) && i.active())
                .orElseThrow(() -> new CallerFault("No accessible message instance"));
        project = instance.project();
        agent = instance.agent();
      }
      if (!definition.action().agent().equals(agent))
        throw new CallerFault("The scheduled action agent must match the message destination");
    }
    if (!target.kind().equals("conversation")
        && source.project() != null
        && !Objects.equals(source.project(), project))
      routing.require(source.account(), source.project(), project);
    DefinitionResolver.Caller caller;
    if (target.kind().equals("conversation")) {
      callers.requireConversationProject(target.conversation(), source.account());
      caller = callers.callerForConversation(target.conversation(), session);
      if (source.projectId() != null && !source.projectId().equals(caller.projectId()))
        throw new CallerFault(
            "Use a configured message route for a conversation in another project");
    } else {
      callers.requireWork(project, source.account());
      caller =
          callers.callerFor(
              project,
              java.util.Objects.equals(project, source.project()) ? session : null,
              source.account());
    }
    var agent = callers.requireAgent(definition.action().agent(), caller);
    var action = definition.action();
    if (action.kind().equals("agent")) return;
    var catalog =
        CommandCatalog.of(
            agent,
            skills.forCaller(caller),
            orchestrations == null ? java.util.Map.of() : orchestrations.forCaller(caller));
    var command =
        catalog.stream()
            .filter(c -> c.kind().equals(action.kind()) && c.name().equals(action.name()))
            .findFirst()
            .orElseThrow(() -> new CallerFault("This agent is not granted the scheduled command"));
    if (action.kind().equals("skill")) {
      if (command.mode() == null && action.mode() == null)
        throw new CallerFault("Choose a context mode for this scheduled skill");
      if (command.mode() != null && action.mode() != null && !command.mode().equals(action.mode()))
        throw new CallerFault("The skill's declared mode conflicts with the schedule");
    }
  }
}
