package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.ExecutionPublicationObserver;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayPorts;
import io.aeyer.plowshare.server.relay.RelayPublishingRepository;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit export/return policy around messaging's native lifecycle. Broker append, source request
 * and retained binding commit together; a remote observation never selects or resurrects a run.
 */
public final class RelayMessageExports implements MessageExports, ExecutionPublicationObserver {
  private final MessageExportProperties properties;
  private final MessageExportRepository repository;
  private final NativeMessageExports nativeMessages;
  private final Relay relay;
  private final RelayPorts ports;
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final UnitOfWork work;
  private final int maximum;

  public RelayMessageExports(
      MessageExportProperties properties,
      MessageExportRepository repository,
      NativeMessageExports nativeMessages,
      Relay relay,
      RelayPorts ports,
      ProjectMembers members,
      ProjectWorkspaces projects,
      UnitOfWork work,
      int maximum) {
    this.properties = Objects.requireNonNull(properties);
    this.repository = Objects.requireNonNull(repository);
    this.nativeMessages = Objects.requireNonNull(nativeMessages);
    this.relay = Objects.requireNonNull(relay);
    this.ports = Objects.requireNonNull(ports);
    this.members = Objects.requireNonNull(members);
    this.projects = Objects.requireNonNull(projects);
    this.work = Objects.requireNonNull(work);
    if (maximum < 1 || maximum > 32)
      throw new IllegalArgumentException("Invalid return effect limit");
    this.maximum = maximum;
  }

  @Override
  public void beforeAppend(RelayPublishingRepository.Intent intent) {
    var configured = properties.request(intent.projectName(), intent.request().topic());
    if (configured.isEmpty()) return;
    var policy = configured.orElseThrow();
    // GrantedRelayPublishing already proved the exporting actor's live work/grant authority.
    // Check the different adapter principal exactly once; configuration alone grants nothing.
    var destination =
        ports.authorizePublisher(policy.adapterAccount(), policy.project(), policy.returnTopic());
    if (!destination.equals(new Relay.TopicKey(intent.project(), policy.returnTopic())))
      throw refused();
    var topic = topic(destination);
    if (topic.kind() != RelayPayload.Kind.TEXT || topic.generation() == null) throw refused();
    var proven = repository.source(intent.owner());
    if (!proven.project().equals(policy.project())) throw refused();
    var source =
        new NativeMessageExports.Source(
            proven.owner(),
            proven.project(),
            proven.origin(),
            proven.participant(),
            intent.causation());
    var nativeRequest =
        nativeMessages.export(source, policy.channel(), intent.id(), intent.request().text());
    repository.bind(
        new MessageExportRepository.Binding(
            intent.id(),
            intent.project(),
            policy,
            topic.generation(),
            nativeRequest,
            intent.causation()));
  }

  @Override
  public void require(Command command) {
    authorize(command, binding(command, false));
  }

  @Override
  public Receipt accept(Command command) {
    return work.inTransaction(
        () -> {
          repository.lockReturn(command.value().requestId());
          // An immutable receipt is recovered before source lifecycle checks: no execution is
          // replayed.
          var prior = repository.receipt(command);
          if (prior.isPresent()) return prior.orElseThrow();
          var binding = binding(command, true);
          authorize(command, binding);
          // Owning request ancestry survives broker expiry. An external SDK root or body assertion
          // cannot reset it, and the authenticated return event supplies the new parent identity.
          var cause = binding.causation().next(command.returnEvent(), maximum);
          var returned =
              nativeMessages.returnReply(
                  binding.nativeRequest(),
                  new NativeMessageExports.Return(
                      command.value().requestId(),
                      command.value().body(),
                      command.value().finalReply(),
                      cause));
          var receipt = new Receipt(returned);
          repository.save(command, receipt, cause);
          return receipt;
        });
  }

  @Override
  public Optional<Receipt> inspect(Command command) {
    return repository.receipt(command);
  }

  private MessageExportRepository.Binding binding(Command command, boolean lock) {
    var binding =
        repository
            .binding(command.value().sourceEvent(), lock)
            .orElseThrow(RelayMessageExports::refused);
    var policy = binding.policy();
    if (binding.projectId() != command.projectId()
        || !policy.project().equals(command.project())
        || !policy.channel().equals(command.channel())
        || !policy.returnTopic().equals(command.returnTopic())
        || !policy.adapterAccount().equals(command.adapterAccount())) throw refused();
    return binding;
  }

  private void authorize(Command command, MessageExportRepository.Binding binding) {
    if (binding.causation().depth() >= maximum) throw refused();
    var current =
        properties
            .returns(command.project(), command.returnTopic(), command.channel())
            .orElseThrow(RelayMessageExports::refused);
    if (!current.equals(binding.policy())) throw refused();
    var topic = new Relay.TopicKey(command.projectId(), command.returnTopic());
    if (!topic.equals(
        ports.authorizePublisher(
            command.adapterAccount(), command.project(), command.returnTopic()))) throw refused();
    var metadata = topic(topic);
    if (metadata.kind() != RelayPayload.Kind.TEXT
        || !binding.returnGeneration().equals(metadata.generation())) throw refused();
    String account = binding.nativeRequest().account();
    // The port already resolved this principal's current project/Personal authority. Resolve a
    // second snapshot only when the exporter is a different principal from the returning adapter.
    if (!account.equals(command.adapterAccount())
        && (!members.mayWork(command.project(), account)
            || projects
                .personalOwner(command.project())
                .filter(owner -> !owner.equals(account))
                .isPresent())) throw refused();
  }

  private Relay.Topic topic(Relay.TopicKey key) {
    try {
      return relay.topic(key);
    } catch (IllegalArgumentException unavailable) {
      throw refused();
    }
  }

  private static MessageRefused refused() {
    return new MessageRefused("Native return authority or binding is unavailable");
  }
}
