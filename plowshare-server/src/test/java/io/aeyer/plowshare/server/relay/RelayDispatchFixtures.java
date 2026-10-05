package io.aeyer.plowshare.server.relay;

import java.time.Instant;
import java.util.UUID;

final class RelayDispatchFixtures {
  static final RelayProjectFiles.Access ACCESS =
      new RelayProjectFiles.Access("operator", "project", 9);
  static final Relay.SubscriptionKey SUB =
      new Relay.SubscriptionKey(new Relay.TopicKey(9, "release.observed"), "relay.notices.release");
  static final RelayDeliveries.Receipt RECEIPT =
      new RelayDeliveries.Receipt("relay.publication", "9:release.forwarded:1");

  private RelayDispatchFixtures() {}

  static RelayDeliveries.Delivery delivery(RelayDeliveries.State state, boolean handler) {
    return delivery(state, handler, "release.forwarded");
  }

  static RelayDeliveries.Delivery delivery(
      RelayDeliveries.State state, boolean handler, String publishTo) {
    var publication =
        new Relay.Publication(
            SUB.topic(),
            1,
            Instant.EPOCH,
            new Relay.Draft(
                "release-event",
                "release-source",
                Instant.EPOCH,
                "cycle",
                null,
                new RelayPayload.Text("released")));
    boolean active =
        state == RelayDeliveries.State.CLAIMED || state == RelayDeliveries.State.DISPATCHING;
    return new RelayDeliveries.Delivery(
        new RelayDeliveries.DeliveryKey(
            SUB, UUID.fromString("26e48bfd-0664-47ab-b3a1-88984c02e8fa")),
        new RelayDeliveries.AdmissionKey(SUB, 1),
        publication,
        RelayDeliveries.SourcePin.of("notices/routes.js", "export function route(){return [];}"),
        new RelayDeliveries.Branch(
            "forward",
            "relay.publish",
            handler
                ? RelayDeliveries.SourcePin.of("notices/scripts/review.js", "// pinned handler")
                : null,
            publishTo),
        state,
        1,
        state == RelayDeliveries.State.READY ? null : "worker",
        active ? Instant.EPOCH.plusSeconds(30) : null,
        Instant.EPOCH,
        Instant.EPOCH,
        state == RelayDeliveries.State.ACCEPTED ? RECEIPT : null,
        state == RelayDeliveries.State.FAILED || state == RelayDeliveries.State.UNCERTAIN
            ? "receiver.exception"
            : null);
  }
}
