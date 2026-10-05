package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayControl;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authorizes controls before receipt lookup; removal additionally proves current inactivity. */
public final class ProjectRelayOperations implements RelayOperations {
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final RelayRouting routing;
  private final RelayDispatch dispatch;
  private final RelayOperationRepository repository;
  private final RelayPublicationSignals signals;
  private final UnitOfWork transactions;

  public ProjectRelayOperations(
      ProjectMembers members,
      ProjectWorkspaces projects,
      RelayRouting routing,
      RelayDispatch dispatch,
      RelayOperationRepository repository,
      RelayPublicationSignals signals,
      UnitOfWork transactions) {
    this.members = Objects.requireNonNull(members);
    this.projects = Objects.requireNonNull(projects);
    this.routing = Objects.requireNonNull(routing);
    this.dispatch = Objects.requireNonNull(dispatch);
    this.repository = Objects.requireNonNull(repository);
    this.signals = Objects.requireNonNull(signals);
    this.transactions = Objects.requireNonNull(transactions);
  }

  @Override
  public RelayControl.Result operate(String account, RelayControl.Request request) {
    Objects.requireNonNull(request);
    RelayValues.identity(account, "operator account");
    var owner = projects.personalOwner(request.project());
    if (owner.isPresent() && !owner.get().equals(account)
        || !members.mayManage(request.project(), account))
      throw new CallerFault("Relay controls require project manager access");
    var id = projects.id(request.project());
    if (id == null || id < 1) throw new CallerFault("Relay control project is unavailable");
    var access = new RelayProjectFiles.Access(account, request.project(), id);
    var retained = repository.receipt(access, request);
    if (retained.isPresent()) return retained.get();
    if (request.action() == RelayControl.Action.REMOVE_SUBSCRIPTION
        || request.action() == RelayControl.Action.REMOVE_TOPIC) {
      // Offline/invalid configuration cannot prove inactivity. Never treat a read refusal as empty.
      var configuration = routing.load(access);
      var target =
          request.subscriber() == null
              ? null
              : new Relay.SubscriptionKey(
                  new Relay.TopicKey(id, request.topic()), request.subscriber());
      boolean active =
          request.action() == RelayControl.Action.REMOVE_TOPIC
              && configuration.policies().containsKey(request.topic());
      for (var bundle : configuration.relays())
        for (var declared : bundle.manifest().subscriptions())
          if (request.action() == RelayControl.Action.REMOVE_TOPIC
              ? declared.topic().equals(request.topic())
              : bundle.key(id, declared).equals(target)) active = true;
      if (active)
        throw new CallerFault("Deactivate this Relay subscription or topic before removing it");
    }
    Optional<RelayDeliveries.Resolution> resolution = Optional.empty();
    var topic = new Relay.TopicKey(id, request.topic());
    if (request.action() == RelayControl.Action.RECONCILE) {
      resolution =
          dispatch.inspect(
              access,
              new RelayDeliveries.DeliveryKey(
                  new Relay.SubscriptionKey(topic, request.subscriber()),
                  UUID.fromString(request.deliveryId())));
    }
    // Manager authority is live and may change during source reads or receiver inspection.
    if (!members.mayManage(request.project(), account)
        || !Objects.equals(projects.id(request.project()), id)
        || projects
            .personalOwner(request.project())
            .filter(value -> !value.equals(account))
            .isPresent()) throw new CallerFault("Relay control authority changed");
    var result = repository.apply(access, request, resolution);
    transactions.afterCommit(() -> signals.published(topic));
    return result;
  }
}
