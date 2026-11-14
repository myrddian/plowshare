package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.tools.RelayToolAuthority;
import io.aeyer.plowshare.server.relay.tools.RelayToolProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
  private final RelayPublicationSignals signals;
  private final RelayToolAuthority tools;
  private final RegisteredTopics registered;
  // Accounts whose publisher row exists. Same bound as the topic memo; a row is never deleted
  // by this process, so no invalidation is needed.
  private final BoundedRecents<String> publishers =
      new BoundedRecents<>(LruRegisteredTopics.DEFAULT_CAPACITY);

  public ProjectRelayPorts(
      Relay relay,
      RelayPortRepository repository,
      RelayPortProperties properties,
      ProjectMembers members,
      ProjectWorkspaces projects,
      int maxDepth) {
    this(relay, repository, properties, members, projects, maxDepth, new RelayToolProperties());
  }

  public ProjectRelayPorts(
      Relay relay,
      RelayPortRepository repository,
      RelayPortProperties properties,
      ProjectMembers members,
      ProjectWorkspaces projects,
      int maxDepth,
      RelayToolAuthority tools) {
    this(
        relay,
        repository,
        properties,
        members,
        projects,
        maxDepth,
        tools,
        new LocalRelayPublicationSignals());
  }

  /**
   * Registers before reading, then awaits committed local/remote hints with bounded reconciliation.
   */
  public ProjectRelayPorts(
      Relay relay,
      RelayPortRepository repository,
      RelayPortProperties properties,
      ProjectMembers members,
      ProjectWorkspaces projects,
      int maxDepth,
      RelayToolAuthority tools,
      RelayPublicationSignals signals) {
    this(
        relay,
        repository,
        properties,
        members,
        projects,
        maxDepth,
        tools,
        signals,
        new LruRegisteredTopics());
  }

  /**
   * {@code registered} lets this process skip re-registering a topic it already registered; it is
   * only a hint, repaired by one register-and-retry when a publish finds the topic row gone.
   */
  public ProjectRelayPorts(
      Relay relay,
      RelayPortRepository repository,
      RelayPortProperties properties,
      ProjectMembers members,
      ProjectWorkspaces projects,
      int maxDepth,
      RelayToolAuthority tools,
      RelayPublicationSignals signals,
      RegisteredTopics registered) {
    this.registered = Objects.requireNonNull(registered);
    this.signals = Objects.requireNonNull(signals);
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
    return require(account, project, topic, direction, group, true);
  }

  /** Registers once per process; the memo is recorded only after registration succeeds. */
  private void register(Relay.TopicKey key) {
    if (registered.known(key)) return;
    relay.registerTopic(key, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    registered.remember(key);
  }

  @Override
  public Relay.TopicKey authorizePublisher(String account, String project, String topic) {
    return require(account, project, topic, RelayPortProperties.Direction.INGRESS, null);
  }

  @Override
  public Map<String, Relay.TopicKey> authorizePublishers(
      String account, String project, List<String> topics) {
    RelayPort.identity(account);
    var names = List.copyOf(topics);
    if (names.isEmpty() || names.size() > 32)
      throw new IllegalArgumentException("Invalid topic authorization bound");
    names.forEach(RelayPort::topic);
    var owner = projects.personalOwner(project);
    boolean mayWork =
        owner.filter(value -> !value.equals(account)).isEmpty()
            && members.mayWork(project, account);
    // A live replacement publishes a whole immutable policy. Both topic checks must use that
    // same snapshot rather than combine grants from different replacements during this batch.
    var grants = properties.getBindings();
    if (!mayWork
        || names.stream()
            .anyMatch(
                topic ->
                    !grants.contains(
                        new RelayPortProperties.Binding(
                            project,
                            topic,
                            account,
                            RelayPortProperties.Direction.INGRESS,
                            List.of()))))
      throw new CallerFault("Relay port is unavailable to this account");
    Long id = projects.id(project);
    if (id == null) throw new CallerFault("Relay project unavailable");
    var authorized = new LinkedHashMap<String, Relay.TopicKey>();
    names.forEach(topic -> authorized.put(topic, new Relay.TopicKey(id, topic)));
    return Map.copyOf(authorized);
  }

  private Relay.TopicKey require(
      String account,
      String project,
      String topic,
      RelayPortProperties.Direction direction,
      String group,
      boolean allowTools) {
    RelayPort.identity(account);
    var owner = projects.personalOwner(project);
    if (owner.isPresent() && !owner.get().equals(account)
        || !members.mayWork(project, account)
        || !(properties.permits(account, project, topic, direction, group)
            || allowTools && tools.permits(account, project, topic, direction, group)))
      throw new CallerFault("Relay port is unavailable to this account");
    Long id = projects.id(project);
    if (id == null) throw new CallerFault("Relay project unavailable");
    var key = new Relay.TopicKey(id, topic);
    if (direction == RelayPortProperties.Direction.EGRESS
        && topic.startsWith("tool.")
        && tools.permits(account, project, topic, direction, group))
      // Deliberately not memoized: unlike publish, the egress callers (subscribe, consume,
      // observe) have no repair path if the topic is deleted after being memoized.
      relay.registerTopic(key, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    return key;
  }

  @Override
  public Relay.TopicKey authorizeBlob(
      String account,
      String project,
      String topic,
      RelayPortProperties.Direction direction,
      String group) {
    if (topic.startsWith("tool."))
      throw new CallerFault("Relay tool topics require their text protocol");
    return require(account, project, topic, direction, group, false);
  }

  @Override
  public RelayCausation ancestry(
      String account, String project, String requestId, String parentTopic, String parentEventId) {
    if (parentTopic == null) return new RelayCausation(requestId, null, 0);
    var parent =
        relay
            .retained(
                require(account, project, parentTopic, RelayPortProperties.Direction.EGRESS, null),
                parentEventId)
            .orElseThrow(() -> new CallerFault("Relay parent is unavailable"));
    var ancestry = parent.event().causation();
    if (ancestry == null || ancestry.depth() < 0 || ancestry.depth() >= maxDepth)
      throw new CallerFault("Relay parent has no remaining effect budget");
    return new RelayCausation(ancestry.rootId(), parent.event().eventId(), ancestry.depth() + 1);
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
    RelayCausation causation =
        ancestry(
            account,
            request.project(),
            request.requestId(),
            request.parentTopic(),
            request.parentEventId());
    // Parent/source inspection may involve IO. Recheck both live WRITE eligibility and the exact
    // project identity before either publishing mutation; later grants cannot retarget this call.
    var current =
        require(
            account,
            request.project(),
            request.topic(),
            RelayPortProperties.Direction.INGRESS,
            null);
    if (!topic.equals(current)) throw new CallerFault("Relay publication project changed");
    if (!publishers.contains(account)) {
      repository.rememberPublisher(account);
      publishers.add(account);
    }
    register(topic);
    Instant occurred = RelayValues.time(request.occurredAt());
    var draft =
        new Relay.Draft(
            request.requestId(),
            RelayPort.publisher(account),
            occurred,
            request.correlationId(),
            request.parentEventId(),
            new RelayPayload.Text(request.text()),
            causation);
    Relay.Publication publication;
    try {
      publication = relay.publish(topic, draft);
    } catch (RelayTopicMissingException missing) {
      // The topic was deleted elsewhere after it was memoized. The failed append found no topic
      // row under its lock and rolled back before appending, so one register-and-retry cannot
      // duplicate a publication. A second miss propagates. Inside an enclosing transaction the
      // append joins it, so the throw may have marked it rollback-only and a retry could never
      // commit: forget the key and rethrow instead.
      registered.forget(topic);
      if (TransactionSynchronizationManager.isActualTransactionActive()) throw missing;
      register(topic);
      publication = relay.publish(topic, draft);
    }
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
    return observe(account, request, budget, () -> {});
  }

  @Override
  public RelayPort.Batch observe(
      String account, RelayPort.Consume request, RelayReadBudget budget, Runnable validity) {
    Objects.requireNonNull(budget);
    Objects.requireNonNull(validity).run();
    long deadline =
        System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(request.waitMs() == null ? 0 : request.waitMs());
    var selected =
        require(
            account,
            request.project(),
            request.topic(),
            RelayPortProperties.Direction.EGRESS,
            request.group());
    if (deadline - System.nanoTime() <= 0)
      return repository.consume(selected, account, request, budget);
    // This is only an in-memory waiter address; it creates no durable subscriber or cursor.
    try (var waiting = signals.listen(new Relay.SubscriptionKey(selected, "port.wait"))) {
      var topic = selected;
      UUID generation = null;
      while (true) {
        validity.run();
        var consumed = repository.consumeObserved(topic, account, request, budget, generation);
        generation = consumed.generation();
        var batch = consumed.batch();
        validity.run();
        long remaining = deadline - System.nanoTime();
        if (batch.status() != RelayPort.Status.EMPTY || remaining <= 0) return batch;
        try {
          // Clock-only lease expiry and missed hints still reconcile. Transport health never
          // authorizes delivery; every subsequent read repeats current port/membership checks.
          long interval =
              Math.min(
                  Duration.ofSeconds(1).toNanos(),
                  signals.reconciliationWait(Duration.ofMillis(100)).toNanos());
          waiting.await(Duration.ofNanos(Math.min(remaining, interval)));
          topic =
              require(
                  account,
                  request.project(),
                  request.topic(),
                  RelayPortProperties.Direction.EGRESS,
                  request.group());
          if (!selected.equals(topic)) throw new CallerFault("Relay consumption project changed");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new CallerFault("Relay consumption interrupted");
        }
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

  private Relay.TopicKey sdkGroup(String account, String project, String topic, String group) {
    // Tool providers retain their own lifecycle, including when they hold a dynamic port grant.
    if (topic.startsWith("tool."))
      throw new CallerFault("Relay tool groups have their own lifecycle");
    return require(account, project, topic, RelayPortProperties.Direction.EGRESS, group, false);
  }

  @Override
  public RelayPort.GroupDeleted deleteGroup(String account, RelayPort.DeleteGroup request) {
    return repository.deleteGroup(
        sdkGroup(account, request.project(), request.topic(), request.group()), account, request);
  }

  @Override
  public RelayPort.GroupState inspectGroup(String account, RelayPort.GroupQuery query) {
    return repository.inspectGroup(
        sdkGroup(account, query.project(), query.topic(), query.group()), account, query);
  }
}
