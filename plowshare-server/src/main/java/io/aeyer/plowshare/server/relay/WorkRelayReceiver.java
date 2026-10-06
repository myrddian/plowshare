package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.board.BoardMessaging;
import io.aeyer.plowshare.server.orchestrations.*;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram;
import io.aeyer.plowshare.server.session.ProjectPresences;
import io.aeyer.plowshare.server.session.SessionOwners;
import java.util.Objects;
import java.util.Optional;

/** Adapts pinned Relay work to normal event jobs and idempotent orchestration starts. */
public final class WorkRelayReceiver implements RelayReceiver {
  private final String receiver;
  private final RelayForwardingHistory history;
  private final int maximum;
  private final WorkCallers callers;
  private final EventRuns jobs;
  private final GrantedOrchestrations grants;
  private final OrchestrationStarts orchestrations;
  private final OrchestrationStore runs;
  private final RelayExecutions executions;
  private final RelayProjectFiles files;
  private final ProjectWorkspaces projects;
  private final ProjectPresences presences;
  private final SessionOwners sessions;
  private final BoardMessaging.Routing routing;

  public WorkRelayReceiver(
      String receiver,
      WorkCallers callers,
      EventRuns jobs,
      GrantedOrchestrations grants,
      OrchestrationStarts orchestrations,
      OrchestrationStore runs,
      RelayExecutions executions,
      RelayProjectFiles files,
      ProjectWorkspaces projects,
      ProjectPresences presences,
      SessionOwners sessions,
      BoardMessaging.Routing routing,
      RelayForwardingHistory history,
      int maximum) {
    if (!java.util.Set.of("agent.run", "script.run", "orchestration.start").contains(receiver))
      throw new IllegalArgumentException("unsupported work receiver");
    this.receiver = receiver;
    this.history = Objects.requireNonNull(history);
    RelayValues.limit(maximum, 32);
    this.maximum = maximum;
    this.callers = Objects.requireNonNull(callers);
    this.jobs = Objects.requireNonNull(jobs);
    this.grants = Objects.requireNonNull(grants);
    this.orchestrations = Objects.requireNonNull(orchestrations);
    this.runs = Objects.requireNonNull(runs);
    this.executions = Objects.requireNonNull(executions);
    this.files = Objects.requireNonNull(files);
    this.projects = Objects.requireNonNull(projects);
    this.presences = Objects.requireNonNull(presences);
    this.sessions = Objects.requireNonNull(sessions);
    this.routing = Objects.requireNonNull(routing);
  }

  private record Resolved(
      AgentDefinition agent, Home home, String session, OrchestrationDefinition definition) {}

  public void require(Request request) {
    resolve(request);
    if (!RelayEffectBound.inspecting(request))
      RelayEffectBound.next(history, request.delivery().publication(), maximum);
  }

  private Resolved resolve(Request request) {
    var branch = request.delivery().branch();
    if (!branch.receiver().equals(receiver)) throw new Refused("receiver-mismatch");
    RelayWork.require(receiver, branch.work(), branch.handler() != null);
    files.requireAccess(request.access());
    var work = branch.work();
    String project = work.project() == null ? request.access().project() : work.project();
    String account = request.access().account();
    // Existing messaging policy is two-sided; project membership alone cannot open a route.
    routing.require(account, request.access().project(), project);
    callers.requireWork(project, account);
    var destinationId = projects.id(project);
    if (destinationId == null) throw new Refused("destination-unavailable");
    files.requireAccess(new RelayProjectFiles.Access(account, project, destinationId));
    String session = null;
    if (projects.personalOwner(project).isEmpty() && projects.find(project).isEmpty()) {
      var presence = presences.serving(project).orElseThrow(() -> new Refused("workspace-offline"));
      if (!project.equals(presence.project())
          || !sessions.accountOf(presence.session()).filter(account::equals).isPresent())
        throw new Refused("workspace-owner-mismatch");
      session = presence.session();
    }
    callers.requireSession(session, account);
    var agent = callers.requireAgent(work.agent(), callers.callerFor(project, session, account));
    Home home = io.aeyer.plowshare.server.requests.RequestedHome.in(project);
    OrchestrationDefinition definition = null;
    if (receiver.equals("orchestration.start")) {
      definition = grants.granted(agent, home, null, session, account).get(work.definition());
      if (definition == null) throw new Refused("orchestration-not-granted");
    } else if (receiver.equals("script.run")) {
      if (!ScriptProgram.isScript(branch.handler().source())) throw new Refused("handler-format");
      // Validate exports in the pure sandbox before recording intent. No manifest field is a grant.
      ScriptProgram.manifest(branch.handler().source());
      agent = scripted(agent, branch.handler().source());
    }
    return new Resolved(agent, home, session, definition);
  }

  public boolean handlesScript() {
    return receiver.equals("script.run");
  }

  public Result dispatch(Request request) {
    if (request.delivery().state() != RelayDeliveries.State.DISPATCHING)
      throw new IllegalArgumentException("work requires prepared dispatch intent");
    var known =
        executions.find(
            request.access().account(), request.access().projectId(), request.identity());
    if (known.isPresent()) return accepted(known.get().receipt());
    var resolved = resolve(request);
    var causation = RelayEffectBound.next(history, request.delivery().publication(), maximum);
    if (!executions.begin(request)) return new Uncertain("execution-intent-without-receipt");
    String message = RelayRouteCodec.event(request.delivery().publication());
    RelayDeliveries.Receipt receipt;
    String conversation;
    if (receiver.equals("orchestration.start")) {
      var run =
          orchestrations.start(
              new Orchestrations.Start(
                  resolved.definition(),
                  resolved.home(),
                  message,
                  null,
                  null,
                  resolved.agent().name(),
                  request.access().account(),
                  resolved.session(),
                  null,
                  0,
                  causation),
              request.identity(),
              message);
      receipt = new RelayDeliveries.Receipt("orchestration", run.id());
      conversation = run.conductorConversation();
    } else {
      var run =
          jobs.submitEvent(
              resolved.agent(),
              message,
              resolved.home(),
              resolved.session(),
              null,
              TurnCap.of(resolved.agent().maxTurns()),
              request.access().account(),
              Speaker.event("relay " + request.identity()),
              (id, outcome) -> {},
              causation);
      if (run.conversation() == null)
        throw new IllegalStateException("Relay work requires a durable conversation");
      receipt = new RelayDeliveries.Receipt("job", run.id());
      conversation = run.conversation();
    }
    executions.accepted(request, receipt, conversation);
    return accepted(receipt);
  }

  public Optional<RelayDeliveries.Resolution> inspect(Request request) {
    resolve(request);
    var receipt =
        executions.find(
            request.access().account(), request.access().projectId(), request.identity());
    if (receipt.isPresent())
      return Optional.of(new RelayDeliveries.Accepted(receipt.get().receipt()));
    // Orchestration starts have their own atomic receipt, even if the Relay receipt write failed.
    // Event job absence cannot prove no job was launched and is deliberately inconclusive.
    if (!receiver.equals("orchestration.start")) return Optional.empty();
    return runs.startReceipt(request.access().account(), request.identity())
        .map(
            found ->
                new RelayDeliveries.Accepted(
                    new RelayDeliveries.Receipt("orchestration", found.id())));
  }

  private static Result accepted(RelayDeliveries.Receipt receipt) {
    return new Settled(new RelayDeliveries.Accepted(receipt));
  }

  private static AgentDefinition scripted(AgentDefinition agent, String source) {
    return agent.withPrompt(source);
  }
}
