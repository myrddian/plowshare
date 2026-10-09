package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.Objects;

/**
 * Configured project ports derive publisher identity from authentication. System ingress is absent.
 */
public final class ProjectRelayPorts implements RelayPorts {
  private final Relay relay;
  private final RelayPortRepository repository;
  private final RelayPortProperties properties;
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final int maxDepth;
  private final io.aeyer.plowshare.server.relay.tools.RelayToolAuthority tools;

  public ProjectRelayPorts(
      Relay relay,
      RelayPortRepository repository,
      RelayPortProperties properties,
      ProjectMembers members,
      ProjectWorkspaces projects,
      int maxDepth) {
    this(
        relay,
        repository,
        properties,
        members,
        projects,
        maxDepth,
        new io.aeyer.plowshare.server.relay.tools.RelayToolProperties());
  }

  public ProjectRelayPorts(
      Relay relay,
      RelayPortRepository repository,
      RelayPortProperties properties,
      ProjectMembers members,
      ProjectWorkspaces projects,
      int maxDepth,
      io.aeyer.plowshare.server.relay.tools.RelayToolAuthority tools) {
    this.tools = Objects.requireNonNull(tools);
    this.relay = Objects.requireNonNull(relay);
    this.repository = Objects.requireNonNull(repository);
    this.properties = Objects.requireNonNull(properties);
    this.members = Objects.requireNonNull(members);
    this.projects = Objects.requireNonNull(projects);
    RelayValues.limit(maxDepth, 32);
    this.maxDepth = maxDepth;
  }

  private Relay.TopicKey require(
      String account,
      String project,
      String topic,
      RelayPortProperties.Direction direction,
      String group) {
    RelayPort.identity(account);
    var owner = projects.personalOwner(project);
    if (owner.isPresent() && !owner.get().equals(account)
        || !members.mayWork(project, account)
        || !(properties.permits(account, project, topic, direction, group)
            || tools.permits(account, project, topic, direction, group)))
      throw new CallerFault("Relay port is unavailable to this account");
    Long id = projects.id(project);
    if (id == null) throw new CallerFault("Relay project unavailable");
    var key = new Relay.TopicKey(id, topic);
    if (direction == RelayPortProperties.Direction.EGRESS
        && topic.startsWith("tool.")
        && tools.permits(account, project, topic, direction, group))
      relay.registerTopic(key, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    return key;
  }

  @Override
  public RelayPort.Published publish(String account, RelayPort.Publish request) {
    var topic =
        require(
            account,
            request.project(),
            request.topic(),
            RelayPortProperties.Direction.INGRESS,
            null);
    tools.validateResult(account, request);
    RelayCausation causation = new RelayCausation(request.requestId(), null, 0);
    if (request.parentTopic() != null) {
      var parentTopic =
          require(
              account,
              request.project(),
              request.parentTopic(),
              RelayPortProperties.Direction.EGRESS,
              null);
      var parent =
          relay
              .retained(parentTopic, request.parentEventId())
              .orElseThrow(() -> new CallerFault("Relay parent is unavailable"));
      var ancestry = parent.event().causation();
      if (ancestry == null || ancestry.depth() < 0 || ancestry.depth() >= maxDepth)
        throw new CallerFault("Relay parent has no remaining effect budget");
      causation =
          new RelayCausation(ancestry.rootId(), parent.event().eventId(), ancestry.depth() + 1);
    }
    relay.registerTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    Instant occurred = RelayValues.time(request.occurredAt());
    var publication =
        relay.publish(
            topic,
            new Relay.Draft(
                request.requestId(),
                RelayPort.publisher(account),
                occurred,
                request.correlationId(),
                request.parentEventId(),
                new RelayPayload.Text(request.text()),
                causation));
    return new RelayPort.Published(
        request.requestId(),
        request.project(),
        request.topic(),
        Long.toString(publication.position()),
        publication.publishedAt());
  }

  @Override
  public RelayPort.Batch consume(
      String account, RelayPort.Consume request, RelayReadBudget budget) {
    java.util.Objects.requireNonNull(budget);
    long deadline =
        System.nanoTime()
            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                request.waitMs() == null ? 0 : request.waitMs());
    while (true) {
      var topic =
          require(
              account,
              request.project(),
              request.topic(),
              RelayPortProperties.Direction.EGRESS,
              request.group());
      var batch = repository.consume(topic, account, request, budget);
      if (batch.status() != RelayPort.Status.EMPTY || System.nanoTime() >= deadline) return batch;
      try {
        Thread.sleep(100);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new CallerFault("Relay consumption interrupted");
      }
    }
  }

  @Override
  public RelayPort.Acknowledged acknowledge(String account, RelayPort.Ack request) {
    return repository.acknowledge(
        require(
            account,
            request.project(),
            request.topic(),
            RelayPortProperties.Direction.EGRESS,
            request.group()),
        account,
        request);
  }
}
