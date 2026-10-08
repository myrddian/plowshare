package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Reconciles results outside transactions; an existing intent is never submitted to Relay again.
 */
public final class ProjectRelayToolInvocations implements RelayToolInvocations {
  private final RelayToolRepository repository;
  private final Relay relay;
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final Supplier<Instant> clock;
  private final int maximum;

  public ProjectRelayToolInvocations(
      RelayToolRepository repository,
      Relay relay,
      ProjectMembers members,
      ProjectWorkspaces projects,
      Supplier<Instant> clock,
      int maximum) {
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
            RelayToolCodec.VERSION,
            id.toString(),
            binding.project(),
            binding.provider(),
            binding.name(),
            owner.accountHandle(),
            owner.runs().id(),
            call,
            now.plusSeconds(binding.timeoutSeconds()).toString(),
            arguments.values());
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
        new RelayToolRepository.Intent(id, project, fingerprint, binding, request, now, causation);
    var stored = repository.submit(intent);
    // The first request's deadline is authoritative, including after restart or changed wall time.
    var original = stored.intent();
    if (stored.result() != null)
      return result(binding, owner.accountHandle(), project, stored.result());
    Instant deadline = Instant.parse(original.request().deadline());
    long waitUntil =
        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(binding.timeoutSeconds());
    while (true) {
      authorizeSame(binding, owner.accountHandle(), project);
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
        Thread.sleep(100);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return unknown(
            id, "Waiting was interrupted after submission; external effects may have occurred.");
      }
    }
  }

  private long authorize(RelayToolDefinition binding, String caller) {
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
    authorizeSame(binding, account, projectId);
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
    authorizeSame(binding, caller, project);
    return new Outcome(UUID.fromString(result.invocationId()), result.state(), result.text());
  }

  private static Outcome unknown(UUID id, String reason) {
    return new Outcome(
        id,
        RelayToolCodec.State.UNKNOWN,
        reason
            + " Do not repeat this work with a new call identity; reconcile the retained invocation.");
  }
}
