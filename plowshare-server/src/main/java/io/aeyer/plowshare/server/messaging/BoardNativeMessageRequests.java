package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.board.BoardBridge;
import io.aeyer.plowshare.server.board.BoardBridgeRepository;
import io.aeyer.plowshare.server.board.BoardNativeRequests;
import io.aeyer.plowshare.server.relay.ExecutionPublicationObserver;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPublisherAccounts;
import io.aeyer.plowshare.server.relay.RelayPublishingProperties;
import io.aeyer.plowshare.server.relay.RelayPublishingRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge integration only: native ownership never enters forum topics, seats or public log links.
 */
public final class BoardNativeMessageRequests
    implements BoardNativeRequests, ExecutionPublicationObserver {
  private static final Logger LOG = LoggerFactory.getLogger(BoardNativeMessageRequests.class);
  private final BoardMessageExportProperties properties;
  private final MessageExportProperties external;
  private final BoardNativeExportRepository repository;
  private final NativeExecutionSources sources;
  private final NativeMessageExports messages;
  private final RelayPublishingProperties grants;
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final int maximum;
  private final UnitOfWork work;
  private final Duration backoff;
  private final Supplier<Instant> clock;

  public BoardNativeMessageRequests(
      BoardMessageExportProperties properties,
      MessageExportProperties external,
      BoardNativeExportRepository repository,
      NativeExecutionSources sources,
      NativeMessageExports messages,
      RelayPublishingProperties grants,
      ProjectMembers members,
      ProjectWorkspaces projects,
      int maximum,
      UnitOfWork work,
      Duration backoff,
      Supplier<Instant> clock) {
    this.properties = Objects.requireNonNull(properties);
    this.external = Objects.requireNonNull(external);
    this.repository = Objects.requireNonNull(repository);
    this.sources = Objects.requireNonNull(sources);
    this.messages = Objects.requireNonNull(messages);
    this.grants = Objects.requireNonNull(grants);
    this.members = Objects.requireNonNull(members);
    this.projects = Objects.requireNonNull(projects);
    if (maximum < 1 || maximum > 32)
      throw new IllegalArgumentException("Invalid native board effect bound");
    this.maximum = maximum;
    this.work = Objects.requireNonNull(work);
    this.backoff = Objects.requireNonNull(backoff);
    this.clock = Objects.requireNonNull(clock);
    if (backoff.isNegative() || backoff.isZero())
      throw new IllegalArgumentException("Invalid board outcome backoff");
    requireDisjoint();
  }

  private void requireDisjoint() {
    for (var policy : properties.getPolicies())
      if (external.request(policy.project(), policy.requestTopic()).isPresent())
        throw new IllegalArgumentException("Board and adapter exports cannot own the same topic");
  }

  @Override
  public void beforeAppend(RelayPublishingRepository.Intent intent) {
    var selected = properties.request(intent.projectName(), intent.request().topic());
    if (selected.isEmpty()) return;
    requireDisjoint();
    var policy = selected.orElseThrow();
    var proven = sources.source(intent.owner());
    if (!proven.project().equals(policy.project()))
      throw new MessageRefused("Board export project differs from its source");
    var source =
        new NativeMessageExports.Source(
            proven.owner(),
            proven.project(),
            proven.origin(),
            proven.participant(),
            intent.causation());
    var request =
        messages.export(source, "board-" + policy.channel(), intent.id(), intent.request().text());
    repository.bind(
        new BoardNativeExportRepository.Binding(
            intent.id(),
            intent.project(),
            policy,
            intent.owner().agentName(),
            request,
            intent.causation()));
  }

  @Override
  public Principal actor(
      Relay.Publication publication,
      String project,
      String channel,
      String receiver,
      RelayPublisherAccounts.Execution execution,
      boolean inspection) {
    UUID event;
    try {
      event = UUID.fromString(publication.event().eventId());
      if (!event.toString().equals(publication.event().eventId())) throw new Unavailable();
    } catch (IllegalArgumentException invalid) {
      throw new Unavailable();
    }
    var binding = repository.binding(event).orElseThrow(Unavailable::new);
    var policy = binding.policy();
    if (binding.projectId() != publication.topic().projectId()
        || !policy.project().equals(project)
        || !policy.requestTopic().equals(publication.topic().name())
        || !policy.channel().equals(channel)
        || !policy.receiver().equals(receiver)
        || !execution.account().equals(binding.nativeRequest().account())
        || !execution.definition().equals(binding.definition())) throw new Unavailable();
    boolean authorized =
        inspection
            || properties.request(project, policy.requestTopic()).filter(policy::equals).isPresent()
                && grants.permits(project, execution.definition(), policy.requestTopic());
    return new Principal(
        new BoardBridge.Agent(execution.account(), execution.definition(), event), authorized);
  }

  @Override
  public Reply reply(BoardBridgeRepository.Pending pending) {
    if (!(pending.destination() instanceof BoardBridgeRepository.NativeDestination destination))
      throw new IllegalArgumentException("Native reply requires its retained destination");
    var retained = repository.binding(destination.sourceEvent());
    if (retained.isEmpty()) return Reply.UNAVAILABLE;
    var binding = retained.orElseThrow();
    var policy = binding.policy();
    var cause = pending.causation();
    if (!properties
            .request(policy.project(), policy.requestTopic())
            .filter(policy::equals)
            .isPresent()
        || cause == null
        || cause.depth() != binding.causation().depth() + 1
        || cause.depth() >= maximum
        || !cause.rootId().equals(binding.causation().rootId())
        || !destination.sourceEvent().toString().equals(cause.parentId())) return Reply.UNAVAILABLE;
    String account = binding.nativeRequest().account();
    if (!members.mayWork(policy.project(), account)
        || projects
            .personalOwner(policy.project())
            .filter(owner -> !owner.equals(account))
            .isPresent()) return Reply.UNAVAILABLE;
    var identity =
        UUID.nameUUIDFromBytes(
            ("board-native-reply/1\n"
                    + pending.requestId()
                    + "\n"
                    + pending.message()
                    + "\n"
                    + pending.terminal())
                .getBytes(StandardCharsets.UTF_8));
    String body =
        (pending.terminal() ? "Board closed" : "Board answer")
            + " by "
            + pending.author()
            + ":\n\n"
            + pending.body();
    var result =
        messages.tryReturnReply(
            binding.nativeRequest(),
            new NativeMessageExports.Return(
                identity, body, pending.terminal(), cause.next(identity.toString(), maximum)));
    if (result instanceof NativeMessageExports.Accepted) return Reply.PUBLISHED;
    if (result instanceof NativeMessageExports.Unavailable) return Reply.UNAVAILABLE;
    if (result instanceof NativeMessageExports.Capacity)
      throw new IllegalStateException("Native board reply capacity remains unavailable");
    throw new IllegalStateException("Native board reply returned no outcome");
  }

  @Override
  public Refusal refuse(UUID delivery, BoardBridge.Agent actor) {
    return work.inTransaction(() -> repository.refuse(delivery, actor));
  }

  @Override
  public boolean refused(BoardBridge.Agent actor) {
    return repository.refused(actor);
  }

  @Override
  public boolean accepted(BoardBridge.Agent actor) {
    return repository.accepted(actor);
  }

  @Override
  public boolean publishNextOutcome() {
    try {
      return work.inTransaction(
          () -> {
            var selected = repository.pendingOutcome();
            if (selected.isEmpty()) return false;
            var outcome = selected.orElseThrow();
            var binding = outcome.binding();
            var policy = binding.policy();
            try {
              if (binding.causation().depth() >= maximum
                  || !properties
                      .request(policy.project(), policy.requestTopic())
                      .filter(policy::equals)
                      .isPresent()
                  || !members.mayWork(policy.project(), binding.nativeRequest().account())
                  || projects
                      .personalOwner(policy.project())
                      .filter(owner -> !owner.equals(binding.nativeRequest().account()))
                      .isPresent()) {
                repository.unavailable(outcome);
                return true;
              }
              var returned =
                  new NativeMessageExports.Return(
                      outcome.returnId(),
                      "The board request was refused. No forum action was accepted.",
                      true,
                      binding.causation().next(outcome.returnId().toString(), maximum));
              var attempt = messages.tryReturnReply(binding.nativeRequest(), returned);
              if (attempt instanceof NativeMessageExports.Accepted accepted)
                repository.published(outcome, accepted.message());
              else if (attempt instanceof NativeMessageExports.Unavailable)
                repository.unavailable(outcome);
              else if (attempt instanceof NativeMessageExports.Capacity)
                throw new IllegalStateException(
                    "Native board outcome capacity remains unavailable");
              else throw new IllegalStateException("Native board outcome returned no result");
              return true;
            } catch (RuntimeException failure) {
              throw new OutcomeFailed(outcome, failure);
            }
          });
    } catch (OutcomeFailed failure) {
      LOG.warn(
          "Native board outcome category=RETURN_FAILED delivery={} source={}; debt remains pending",
          failure.outcome.delivery(),
          failure.outcome.binding().sourceEvent());
      work.inTransaction(
          () -> {
            repository.defer(failure.outcome, clock.get().plus(backoff));
            return true;
          });
      throw failure;
    }
  }

  private static final class OutcomeFailed extends IllegalStateException {
    private final BoardNativeExportRepository.Outcome outcome;

    OutcomeFailed(BoardNativeExportRepository.Outcome outcome, RuntimeException cause) {
      super("Native board outcome remains pending for delivery=" + outcome.delivery(), cause);
      this.outcome = outcome;
    }
  }
}
