package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.blobs.BlobClaim;

/** Shared deterministic publication slot for append, download and cascade release. */
final class RelayBlobClaims {
  private RelayBlobClaims() {}

  static String scope(Relay.TopicKey topic) {
    if (!(topic.scope() instanceof Relay.ProjectScope project)) {
      throw new IllegalArgumentException("Relay blobs require a project scope");
    }
    return "projects/" + project.projectId();
  }

  static BlobClaim claim(Relay.TopicKey topic, long position) {
    if (position < 1) throw new IllegalArgumentException("invalid blob publication position");
    return new BlobClaim(
        "relay-publication",
        RelayPort.hash(topic.name()) + ":" + position,
        0,
        BlobClaim.Retention.RELEASABLE);
  }
}
