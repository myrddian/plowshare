package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Intent/receipt coordinator. Owning API calls happen between committed repository operations,
 * never inside a broker transaction. Preparation is the boundary after which replay is forbidden.
 */
public final class ReceiverRelayDispatch implements RelayDispatch {
  private final RelayDeliveries deliveries;
  private final RelayReceivers receivers;
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final Boundary boundary;

  public ReceiverRelayDispatch(
      RelayDeliveries deliveries,
      RelayReceivers receivers,
      ProjectMembers members,
      ProjectWorkspaces projects,
      Boundary boundary) {
    this.deliveries = Objects.requireNonNull(deliveries);
    this.receivers = Objects.requireNonNull(receivers);
    this.members = Objects.requireNonNull(members);
    this.projects = Objects.requireNonNull(projects);
    this.boundary = Objects.requireNonNull(boundary);
  }

  @Override
  public Optional<RelayDeliveries.Delivery> next(
      RelayProjectFiles.Access access,
      Relay.SubscriptionKey subscription,
      String worker,
      Duration lease) {
    return next(
        access, subscription, worker, lease, () -> deliveries.claim(subscription, worker, lease));
  }

  @Override
  public Optional<RelayDeliveries.Delivery> next(
      RelayProjectFiles.Access access,
      Relay.SubscriptionKey subscription,
      String worker,
      Duration lease,
      ClaimSource claims) {
    Objects.requireNonNull(claims);
    boundary.requireOutsideTransaction();
    require(access, subscription);
    RelayValues.identity(worker, "worker");
    RelayDeliveries.requireLease(lease);
    if (Thread.currentThread().isInterrupted()) return Optional.empty();
    var claimed = claims.claim();
    if (claimed.isEmpty()) return Optional.empty();
    var delivery = claimed.get();
    if (!delivery.key().subscription().equals(subscription) || !worker.equals(delivery.worker()))
      throw new IllegalStateException("Relay claim differs from authorized subscription or worker");
    var claim = delivery.claim();
    var receiver = receivers.find(delivery.branch().receiver());
    if (receiver.isEmpty()) return Optional.of(deliveries.failed(claim, "receiver.unavailable"));
    if (delivery.branch().handler() != null && !receiver.get().handlesScript())
      return Optional.of(deliveries.failed(claim, "receiver.handler.unsupported"));
    try {
      receiver.get().require(new RelayReceiver.Request(access, delivery));
    } catch (RelayReceiver.Refused refused) {
      return Optional.of(deliveries.failed(claim, refused.code()));
    } catch (RuntimeException failed) {
      return Optional.of(deliveries.failed(claim, "receiver.preflight.failed"));
    }
    if (Thread.currentThread().isInterrupted())
      return Optional.of(deliveries.failed(claim, "consumer.stopped"));
    var prepared = deliveries.prepareDispatch(claim);
    var request = new RelayReceiver.Request(access, prepared);
    // Authority can change while preflight/intent is recorded. Recheck before invoking effects;
    // refusal at this point is still definitely before any receiver dispatch.
    try {
      require(access, subscription);
      receiver.get().require(request);
    } catch (RelayReceiver.Refused refused) {
      return Optional.of(deliveries.failed(claim, refused.code()));
    } catch (RuntimeException failed) {
      return Optional.of(deliveries.failed(claim, "receiver.preflight.failed"));
    }
    // A slow live preflight must not turn an expired intent into a new effect. Renew checks the
    // fence and unexpired lease atomically immediately before handing work to the receiver.
    request = new RelayReceiver.Request(access, deliveries.renew(claim, lease));
    boundary.requireOutsideTransaction();
    if (Thread.currentThread().isInterrupted())
      return Optional.of(deliveries.failed(claim, "consumer.stopped"));
    RelayReceiver.Result result;
    try {
      result = Objects.requireNonNull(receiver.get().dispatch(request), "receiver result");
    } catch (RuntimeException failed) {
      // A mutating API may have accepted before it threw. Do not treat the exception as no effect,
      // and never retain its message/cause, which may quote sensitive source or event content.
      result = new RelayReceiver.Uncertain("receiver.exception");
    }
    // Keep receipt persistence outside the dispatch catch: a failed broker write must not turn a
    // known destination receipt into a different outcome. Lease recovery/inspection reconciles it.
    var settled =
        switch (result) {
          case RelayReceiver.Settled known ->
              switch (known.resolution()) {
                case RelayDeliveries.Accepted accepted ->
                    deliveries.accepted(claim, accepted.receipt());
                case RelayDeliveries.Failed failed -> deliveries.failed(claim, failed.code());
              };
          case RelayReceiver.Uncertain uncertain -> deliveries.uncertain(claim, uncertain.code());
        };
    return Optional.of(settled);
  }

  @Override
  public RelayDeliveries.Delivery reconcile(
      RelayProjectFiles.Access access, RelayDeliveries.DeliveryKey key) {
    boundary.requireOutsideTransaction();
    require(access, key.subscription());
    var current =
        deliveries.delivery(key).orElseThrow(() -> new CallerFault("No retained Relay delivery"));
    if (current.state() == RelayDeliveries.State.ACCEPTED
        || current.state() == RelayDeliveries.State.FAILED) return current;
    var resolution = inspect(access, key);
    return resolution
        .map(known -> deliveries.reconcile(key, known))
        .orElseGet(
            () ->
                deliveries
                    .delivery(key)
                    .orElseThrow(() -> new CallerFault("No retained Relay delivery")));
  }

  @Override
  public Optional<RelayDeliveries.Resolution> inspect(
      RelayProjectFiles.Access access, RelayDeliveries.DeliveryKey key) {
    boundary.requireOutsideTransaction();
    require(access, key.subscription());
    var delivery =
        deliveries.delivery(key).orElseThrow(() -> new CallerFault("No retained Relay delivery"));
    if (delivery.state() == RelayDeliveries.State.ACCEPTED)
      return Optional.of(new RelayDeliveries.Accepted(delivery.receipt()));
    if (delivery.state() == RelayDeliveries.State.FAILED)
      return Optional.of(new RelayDeliveries.Failed(delivery.failureCode()));
    if (delivery.state() != RelayDeliveries.State.UNCERTAIN
        && delivery.state() != RelayDeliveries.State.ABANDONED_UNCERTAIN)
      throw new CallerFault("Relay reconciliation requires an uncertain dispatch");
    var receiver =
        receivers
            .find(delivery.branch().receiver())
            .orElseThrow(
                () ->
                    new CallerFault("The registered Relay receiver is unavailable for inspection"));
    if (delivery.branch().handler() != null && !receiver.handlesScript())
      throw new CallerFault("The registered Relay receiver cannot inspect handler execution");
    Optional<RelayDeliveries.Resolution> resolution;
    try {
      var request = new RelayReceiver.Request(access, delivery);
      receiver.require(request);
      resolution = Objects.requireNonNull(receiver.inspect(request), "receiver inspection");
    } catch (RuntimeException failed) {
      throw new CallerFault("Relay receiver inspection failed; uncertainty is retained");
    }
    require(access, key.subscription());
    return resolution;
  }

  private void require(RelayProjectFiles.Access access, Relay.SubscriptionKey subscription) {
    Objects.requireNonNull(access, "access");
    Objects.requireNonNull(subscription, "subscription");
    if (!new Relay.ProjectScope(access.projectId()).equals(subscription.topic().scope())
        || !members.mayWork(access.project(), access.account())
        || !Objects.equals(projects.id(access.project()), access.projectId()))
      throw new CallerFault(
          "Relay dispatch requires live contributor access to the owning project");
  }
}
