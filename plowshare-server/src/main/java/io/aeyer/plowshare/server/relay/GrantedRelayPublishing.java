package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.access.OperationFence;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Checks current authority before any publication or receipt disclosure; does not hold connections.
 */
public final class GrantedRelayPublishing implements RelayPublishing {
  private final RelayPublishingRepository repository;
  private final RelayPublishingProperties grants;
  private final Definitions definitions;
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final int maximum;
  private final Supplier<Instant> clock;

  public GrantedRelayPublishing(
      RelayPublishingRepository repository,
      RelayPublishingProperties grants,
      Definitions definitions,
      ProjectMembers members,
      ProjectWorkspaces projects,
      int maximum,
      Supplier<Instant> clock) {
    this.repository = Objects.requireNonNull(repository);
    this.grants = Objects.requireNonNull(grants);
    this.definitions = Objects.requireNonNull(definitions);
    this.members = Objects.requireNonNull(members);
    this.projects = Objects.requireNonNull(projects);
    if (maximum < 1 || maximum > 32) throw new IllegalArgumentException("Invalid effect limit");
    this.maximum = maximum;
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Receipt publish(
      String project, Execution execution, Request request, OperationFence fence) {
    Objects.requireNonNull(execution);
    Objects.requireNonNull(fence);
    var owner = execution.owner();
    String call = execution.call();
    Objects.requireNonNull(request);
    RelayPort.identity(project);
    RelayPort.identity(call);
    if (owner == null
        || owner.status() != UsageAttribution.Status.ATTRIBUTED
        || owner.scope() != UsageAttribution.Scope.PROJECT
        || owner.runs().id() == null
        || owner.conversations().id() == null
        || owner.turnOrdinal() == null
        || owner.turnOrdinal() < 1
        || owner.stepOrdinal() == null
        || owner.stepOrdinal() < 1
        || owner.agentName() == null)
      throw new CallerFault("Relay publication requires an authenticated project execution");
    if (!grants.permits(project, owner.agentName(), request.topic())
        || !members.mayWork(project, owner.accountHandle())
        || projects
            .personalOwner(project)
            .filter(p -> !p.equals(owner.accountHandle()))
            .isPresent())
      throw new CallerFault("Relay publication is not granted to this execution");
    Long id = projects.id(project);
    if (id == null
        || !Long.toString(id).equals(owner.projectId())
        || !definitions.permits(id, execution))
      throw new CallerFault("Relay publication definition or project authority is unavailable");
    RelayPort.identity(owner.accountHandle());
    RelayPort.identity(owner.runs().id());
    RelayPort.identity(owner.conversations().id());
    var ancestry =
        repository
            .ancestry(id, owner)
            .filter(value -> value.depth() >= 0 && value.depth() < maximum)
            .orElseThrow(
                () -> new CallerFault("Relay execution ancestry is unavailable or exhausted"));
    var causation = ancestry.next(owner.runs().id(), maximum);
    // Include every harness coordinate; topic/text are fingerprinted, not part of the call
    // identity.
    // Changing a target under the same call must fail instead of acquiring another event UUID.
    String identity =
        "relay-publish/v1\n"
            + owner.accountHandle()
            + "\n"
            + id
            + "\n"
            + owner.agentName()
            + "\n"
            + owner.conversations().id()
            + "\n"
            + owner.runs().id()
            + "\n"
            + owner.turnOrdinal()
            + "\n"
            + owner.stepOrdinal()
            + "\n"
            + call;
    UUID event = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    String fingerprint = RelayPort.hash(identity + "\n" + request.topic() + "\n" + request.text());
    return repository.publish(
        new RelayPublishingRepository.Intent(
            event, id, project, owner, call, request, fingerprint, clock.get(), causation),
        fence);
  }
}
