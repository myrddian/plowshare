package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.Incoming;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.CallerAccess;
import io.aeyer.plowshare.server.board.BoardMessaging;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Protocol-neutral adapter ingress. The bridge owns external-client authentication. */
@Component
public final class IncomingFrames implements FrameArea {
  private final ObjectProvider<BoardMessaging> messages;
  private final CallerAccess access;
  private final io.aeyer.plowshare.server.agents.Callers callers;
  private final io.aeyer.plowshare.server.agents.SkillResolver skills;
  private final ObjectProvider<io.aeyer.plowshare.server.agents.OrchestrationResolver>
      orchestrations;

  public IncomingFrames(
      ObjectProvider<BoardMessaging> messages,
      CallerAccess access,
      io.aeyer.plowshare.server.agents.Callers callers,
      io.aeyer.plowshare.server.agents.SkillResolver skills,
      ObjectProvider<io.aeyer.plowshare.server.agents.OrchestrationResolver> orchestrations) {
    this.messages = messages;
    this.access = access;
    this.callers = callers;
    this.skills = skills;
    this.orchestrations = orchestrations;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.INCOMING_CATALOG,
        this::catalog,
        FrameTypes.INCOMING_RECEIVE,
        this::receive,
        FrameTypes.INCOMING_STATUS,
        (p, a) -> read(p, a, false),
        FrameTypes.INCOMING_CANCEL,
        (p, a) -> read(p, a, true));
  }

  private String owner(Asking asking, String type, String project) {
    String account = asking.requireHandle(type);
    access.requireSession(asking.sessionId(), account);
    access.requireProject(project, account);
    return account;
  }

  private Outcome catalog(Map<String, Object> payload, Asking asking) {
    String project =
        Payloads.required(
            payload, "project", FrameTypes.INCOMING_CATALOG, "the configured project");
    owner(asking, FrameTypes.INCOMING_CATALOG, project);
    String name =
        Payloads.required(
            payload, "agent", FrameTypes.INCOMING_CATALOG, "the configured receiving agent");
    var caller = callers.callerFor(project, null);
    var definition = callers.requireAgent(name, caller);
    var procedures = orchestrations.getIfAvailable();
    var commands =
        io.aeyer.plowshare.server.agents.CommandCatalog.of(
            definition,
            skills.forCaller(caller),
            procedures == null ? Map.of() : procedures.forCaller(caller));
    return Outcome.ok(
        Map.of(
            "name",
            definition.name(),
            "description",
            definition.description(),
            "served",
            true,
            "commands",
            commands));
  }

  private Outcome receive(Map<String, Object> payload, Asking asking) {
    var body = Payloads.as(payload, Incoming.Receive.class, FrameTypes.INCOMING_RECEIVE);
    String account = owner(asking, FrameTypes.INCOMING_RECEIVE, body.project());
    if (body.project() == null || body.project().isBlank())
      throw new io.aeyer.plowshare.server.board.Board.Refused("A project is required for ingress.");
    var caller = callers.callerFor(body.project(), null);
    var definition = callers.requireAgent(body.agent(), caller);
    if (body.command() != null) {
      var procedures = orchestrations.getIfAvailable();
      var catalog =
          io.aeyer.plowshare.server.agents.CommandCatalog.of(
              definition,
              skills.forCaller(caller),
              procedures == null ? Map.of() : procedures.forCaller(caller));
      String name = body.command().split(" ", 2)[0];
      if (catalog.stream().noneMatch(command -> command.command().equals(name)))
        throw new io.aeyer.plowshare.server.board.Board.Refused(
            "The incoming command is not granted to this agent.");
    }
    return Outcome.ok(messages.getObject().receive(account, body));
  }

  private Outcome read(Map<String, Object> payload, Asking asking, boolean cancel) {
    String type = cancel ? FrameTypes.INCOMING_CANCEL : FrameTypes.INCOMING_STATUS;
    var body = Payloads.as(payload, Incoming.Id.class, type);
    String account = owner(asking, type, body.project());
    return Outcome.ok(
        cancel
            ? messages.getObject().cancelExternal(account, body.project(), body.client(), body.id())
            : messages.getObject().externalTask(account, body.project(), body.client(), body.id()));
  }
}
