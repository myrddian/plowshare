package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.protocol.HostedToolScopes;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Reconciles results outside transactions; an existing intent is never submitted to Relay again.
 */
public final class ProjectRelayToolInvocations implements RelayToolInvocations {
  /**
   * Longest single wait: bounds how late a missed hint, a cancellation or a clock-only expiry is
   * noticed.
   */
  static final Duration CEILING = Duration.ofSeconds(1);

  /**
   * Minimum gap between one waiter's retained-result reads. Wake-ups may be coarse (any relay
   * publication anywhere), so this keeps a waiter at the old 100 ms poll rate at most.
   */
  static final Duration READ_GAP = Duration.ofMillis(100);

  private final RelayToolRepository repository;
  private final Relay relay;
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final Supplier<Instant> clock;
  private final int maximum;
  private final HostedInvocationAuthority hosting;
  private final RelayToolWakeups wakeups;
  private final Duration staleness;

  public ProjectRelayToolInvocations(
      RelayToolRepository repository,
      Relay relay,
      ProjectMembers members,
      ProjectWorkspaces projects,
      Supplier<Instant> clock,
      int maximum) {
    this(repository, relay, members, projects, clock, maximum, HostedInvocationAuthority.NONE);
  }

  public ProjectRelayToolInvocations(
      RelayToolRepository repository,
      Relay relay,
      ProjectMembers members,
      ProjectWorkspaces projects,
      Supplier<Instant> clock,
      int maximum,
      HostedInvocationAuthority hosting) {
    this(
        repository,
        relay,
        members,
        projects,
        clock,
        maximum,
        hosting,
        RelayToolWakeups.TIMED,
        Duration.ZERO);
  }

  /**
   * @param wakeups hints that end a wait early; every wait is still capped at {@link #CEILING}
   * @param staleness how long a successful authorization may be reused while waiting. Zero (for
   *     example when notifications are not trusted) authorizes on every wake, at most once per
   *     ceiling.
   */
  public ProjectRelayToolInvocations(
      RelayToolRepository repository,
      Relay relay,
      ProjectMembers members,
      ProjectWorkspaces projects,
      Supplier<Instant> clock,
      int maximum,
      HostedInvocationAuthority hosting,
      RelayToolWakeups wakeups,
      Duration staleness) {
    if (staleness == null || staleness.isNegative())
      throw new IllegalArgumentException("Staleness bound must not be negative");
    this.wakeups = Objects.requireNonNull(wakeups);
    this.staleness = staleness;
    this.hosting = Objects.requireNonNull(hosting);
    if (maximum < 1 || maximum > 32)
      throw new IllegalArgumentException("Tool effect limit must be 1 through 32");
    this.maximum = maximum;
    this.repository = Objects.requireNonNull(repository);
    this.relay = Objects.requireNonNull(relay);
    this.members = Objects.requireNonNull(members);
    this.projects = Objects.requireNonNull(projects);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Outcome invoke(
      RelayToolDefinition binding,
      RelayToolDefinition.Arguments arguments,
      UsageAttribution owner,
      String call,
      BooleanSupplier cancelled) {
    binding.validate(arguments);
    if (binding.resultProfile() != null
        && io.aeyer.plowshare.protocol.HostedToolScopes.hosted(binding.provider()))
      throw new CallerFault("Hosted content results are not supported by this worker protocol");
    if (owner.status() != UsageAttribution.Status.ATTRIBUTED
        || owner.scope() != UsageAttribution.Scope.PROJECT
        || owner.runs().id() == null
        || owner.conversations().id() == null
        || owner.turnOrdinal() == null
        || owner.turnOrdinal() < 1
        || owner.stepOrdinal() == null)
      throw new CallerFault("Relay tools require an authenticated project execution");
    RelayPort.identity(owner.runs().id());
    RelayPort.identity(call);
    long project = authorize(binding, owner.accountHandle());
    Instant authorizedAt = clock.get();
    if (!Long.toString(project).equals(owner.projectId()))
      throw new CallerFault("Tool execution belongs to a different project");
    if (cancelled.getAsBoolean())
      throw new CallerFault("Tool cancelled before submission; nothing was published");
    String identity =
        owner.accountHandle()
            + "\n"
            + project
            + "\n"
            + owner.runs().id()
            + "\n"
            + owner.turnOrdinal()
            + "\n"
            + owner.stepOrdinal()
            + "\n"
            + binding.name()
            + "\n"
            + call;
    UUID id = UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String fingerprint =
        RelayPort.hash(
            identity
                + "\n"
                + RelayToolCodec.write(binding)
                + "\n"
                + RelayToolCodec.write(arguments));
    Instant now = clock.get().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var request =
        new RelayToolCodec.Request(
            binding.resultProfile() != null
                ? RelayToolCodec.CONTENT_VERSION
                : binding.inputSchema() == null
                    ? RelayToolCodec.VERSION
                    : RelayToolCodec.NESTED_VERSION,
            id.toString(),
            binding.project(),
            binding.provider(),
            binding.name(),
            owner.accountHandle(),
            owner.runs().id(),
            call,
            now.plusSeconds(binding.timeoutSeconds()).toString(),
            arguments.values(),
            binding.inputSchema() == null
                ? null
                : io.aeyer.plowshare.protocol.SchemaCodec.fingerprint(binding.inputSchema()),
            binding.resultProfile());
    var ancestry =
        repository
            .ancestry(project, owner)
            .filter(value -> value.depth() >= 0 && value.depth() < maximum - 1)
            .orElseThrow(
                () ->
                    new CallerFault(
                        "Tool execution has no remaining verified Relay effect budget"));
    var causation = ancestry.next(owner.runs().id(), maximum);
    var intent =
        new RelayToolRepository.Intent(
            id, project, fingerprint, binding, request, now, causation, owner.agentName(), null);
    var stored = repository.submit(intent);
    // The first request's deadline is authoritative, including after restart or changed wall time.
    var original = stored.intent();
    if (stored.result() != null)
      return result(binding, owner.accountHandle(), project, stored.result());
    Instant deadline = Instant.parse(original.request().deadline());
    long waitUntil =
        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(binding.timeoutSeconds());
    // Registered before the first read and closed on every exit (result, timeout, cancellation,
    // refusal, failure). Hints only end a wait early; every pass re-reads the retained result.
    try (var wake = wakeups.open()) {
      boolean authority = false;
      Instant readAt = null;
      while (true) {
        // The admission decision is reused inside the staleness bound until an authority change
        // is signalled, and only while the hints are trustworthy: with the listener down a change
        // may go unannounced, so every wake re-authorizes. A refusal is never reused.
        Duration reuse = wake.trusted() ? staleness : Duration.ZERO;
        if (authority || !clock.get().isBefore(authorizedAt.plus(reuse))) {
          if (original.hosted() != null) {
            if (!hosting.permits(binding, owner.accountHandle()))
              return unknown(id, "Hosted authority or availability ended after submission.");
            if (HostedToolScopes.hosted(binding.provider())) {
              // permits() above is this authorization's single hosted check.
              if (!Objects.equals(projects.id(binding.project()), project))
                throw new CallerFault("Relay tool project identity changed");
            } else authorizeSame(binding, owner.accountHandle(), project);
          } else authorizeSame(binding, owner.accountHandle(), project);
          authorizedAt = clock.get();
          authority = false;
        }
        readAt = clock.get();
        var publication =
            relay.retained(
                new Relay.TopicKey(project, binding.results()),
                RelayToolCodec.resultRequestId(id).toString());
        if (publication.isPresent()) {
          var event = publication.get().event();
          if (!event.publisher().equals(RelayPort.publisher(binding.account()))
              || !id.toString().equals(event.correlationId())
              || !id.toString().equals(event.causationId())
              || event.causation() == null
              || !event.causation().equals(original.causation().next(id.toString(), maximum))
              || !(event.payload() instanceof RelayPayload.Text text))
            return unknown(id, "Provider result identity could not be verified.");
          RelayToolCodec.Result completion;
          try {
            completion = RelayToolCodec.result(text.text());
            if (!completion.invocationId().equals(id.toString())
                || !completion.project().equals(binding.project())
                || !completion.provider().equals(binding.provider())
                || !completion.tool().equals(binding.name()))
              return unknown(id, "Provider result belongs to a different binding.");
          } catch (IllegalArgumentException invalid) {
            return unknown(id, "Provider result violates the tool contract.");
          }
          var completed = repository.finish(original, completion);
          return result(binding, owner.accountHandle(), project, completed.result());
        }
        if (cancelled.getAsBoolean()
            || !clock.get().isBefore(deadline)
            || System.nanoTime() >= waitUntil)
          return unknown(id, "Waiting ended after submission; external effects may have occurred.");
        try {
          long remaining = waitUntil - System.nanoTime();
          authority |=
              wake.await(Duration.ofNanos(Math.max(1, Math.min(remaining, CEILING.toNanos()))));
          // A wake inside the read gap waits out the remainder (still bounded by the deadline and
          // cancellation) instead of reading again; authority hints keep accumulating meanwhile.
          while (true) {
            Duration left = Duration.between(clock.get(), readAt.plus(READ_GAP));
            long budget = waitUntil - System.nanoTime();
            if (left.isNegative()
                || left.isZero()
                || budget <= 0
                || cancelled.getAsBoolean()
                || !clock.get().isBefore(deadline)) break;
            authority |=
                wake.await(Duration.ofNanos(Math.max(1, Math.min(budget, left.toNanos()))));
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return unknown(
              id, "Waiting was interrupted after submission; external effects may have occurred.");
        }
      }
    }
  }

  private long authorize(RelayToolDefinition binding, String caller) {
    if (io.aeyer.plowshare.protocol.HostedToolScopes.hosted(binding.provider())) {
      if (!hosting.permits(binding, caller))
        throw new CallerFault("Hosted tool unavailable to this execution");
      Long id = projects.id(binding.project());
      if (id == null) throw new CallerFault("Hosted project unavailable");
      return id;
    }
    if (!members.mayWork(binding.project(), caller)
        || !members.mayWork(binding.project(), binding.account())
        || projects
            .personalOwner(binding.project())
            .filter(owner -> !owner.equals(caller) || !owner.equals(binding.account()))
            .isPresent())
      throw new CallerFault("Relay tool is unavailable to this execution or provider");
    Long project = projects.id(binding.project());
    if (project == null || project < 1) throw new CallerFault("Relay tool project is unavailable");
    return project;
  }

  @Override
  public Outcome read(String project, String account, UUID invocation) {
    if (!members.mayWork(project, account)
        || projects.personalOwner(project).filter(owner -> !owner.equals(account)).isPresent())
      throw new CallerFault("Tool invocation is unavailable to this account");
    Long projectId = projects.id(project);
    if (projectId == null) throw new CallerFault("Tool invocation project is unavailable");
    var stored =
        repository
            .find(projectId, account, invocation)
            .orElseThrow(() -> new CallerFault("Tool invocation is unavailable to this account"));
    var original = stored.intent();
    var binding = original.binding();
    if (original.hosted() == null) authorizeSame(binding, account, projectId);
    else if (!original.hosted().owner().equals(account)
        || !original.hosted().executor().equals(binding.account()))
      throw new CallerFault("Retained hosted provenance invalid");
    if (stored.result() != null) return result(binding, account, projectId, stored.result());
    var retained =
        relay.retained(
            new Relay.TopicKey(projectId, binding.results()),
            RelayToolCodec.resultRequestId(invocation).toString());
    if (retained.isEmpty()) return unknown(invocation, "No verified provider result is retained.");
    var event = retained.get().event();
    if (!event.publisher().equals(RelayPort.publisher(binding.account()))
        || !invocation.toString().equals(event.correlationId())
        || !invocation.toString().equals(event.causationId())
        || event.causation() == null
        || !event.causation().equals(original.causation().next(invocation.toString(), maximum))
        || !(event.payload() instanceof RelayPayload.Text text))
      return unknown(invocation, "Provider result identity could not be verified.");
    RelayToolCodec.Result completion;
    try {
      completion = RelayToolCodec.result(text.text());
      if (!completion.invocationId().equals(invocation.toString())
          || !completion.project().equals(project)
          || !completion.provider().equals(binding.provider())
          || !completion.tool().equals(binding.name()))
        return unknown(invocation, "Provider result belongs to a different binding.");
    } catch (IllegalArgumentException invalid) {
      return unknown(invocation, "Provider result violates the tool contract.");
    }
    return result(binding, account, projectId, repository.finish(original, completion).result());
  }

  private void authorizeSame(RelayToolDefinition binding, String caller, long project) {
    if (authorize(binding, caller) != project)
      throw new CallerFault("Relay tool project identity changed");
  }

  private Outcome result(
      RelayToolDefinition binding, String caller, long project, RelayToolCodec.Result result) {
    if (!io.aeyer.plowshare.protocol.HostedToolScopes.hosted(binding.provider()))
      authorizeSame(binding, caller, project);
    else if (!members.mayWork(binding.project(), caller)
        || projects.personalOwner(binding.project()).filter(p -> !p.equals(caller)).isPresent()
        || !Objects.equals(projects.id(binding.project()), project))
      throw new CallerFault("Hosted result unavailable to this owner");
    return new Outcome(
        UUID.fromString(result.invocationId()), result.state(), result.text(), result.content());
  }

  private static Outcome unknown(UUID id, String reason) {
    return new Outcome(
        id,
        RelayToolCodec.State.UNKNOWN,
        reason
            + " Do not repeat this work with a new call identity; reconcile the retained invocation.");
  }
}
