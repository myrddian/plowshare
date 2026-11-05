package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayBlob;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.blobs.BinaryBlobStorage;
import io.aeyer.plowshare.server.blobs.BlobKey;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * Byte I/O precedes short owner transactions. No decoder, model routing or trajectory is involved.
 */
public final class ProjectRelayBlobs implements RelayBlobs {
  private final RelayPorts ports;
  private final Relay relay;
  private final BinaryBlobStorage blobs;
  private final UnitOfWork work;
  private final RelayBlobProperties properties;
  private final Semaphore slots;

  public ProjectRelayBlobs(
      RelayPorts ports,
      Relay relay,
      BinaryBlobStorage blobs,
      UnitOfWork work,
      RelayBlobProperties properties) {
    this.ports = Objects.requireNonNull(ports);
    this.relay = Objects.requireNonNull(relay);
    this.blobs = Objects.requireNonNull(blobs);
    this.work = Objects.requireNonNull(work);
    this.properties = Objects.requireNonNull(properties);
    slots = new Semaphore(properties.getMaxConcurrent());
  }

  @Override
  public RelayBlob.Published publish(
      String account, RelayBlob.Publish request, long size, InputStream input) {
    if (!properties.isEnabled()) throw new CallerFault("Relay binary publication is disabled");
    Objects.requireNonNull(request);
    Objects.requireNonNull(input);
    var topic =
        ports.authorizeBlob(
            account,
            request.project(),
            request.topic(),
            RelayPortProperties.Direction.INGRESS,
            null);
    if (size < 1 || size > properties.getMaxBytes())
      throw new CallerFault("Relay blob exceeds the byte allowance");
    var ancestry =
        ports.ancestry(
            account,
            request.project(),
            request.requestId(),
            request.parentTopic(),
            request.parentEventId());
    if (!slots.tryAcquire()) throw new CallerFault("Relay binary transfer capacity is busy");
    try {
      byte[] bytes;
      try {
        bytes = input.readNBytes(Math.toIntExact(size) + 1);
      } catch (IOException failure) {
        throw new CallerFault("Relay binary upload could not be read");
      }
      if (bytes.length != size)
        throw new CallerFault("Relay binary upload length differs from its declaration");
      var key = blobs.prepare(RelayBlobClaims.scope(topic), bytes);
      var descriptor = new RelayBlob.Descriptor(key.sha256(), bytes.length, request.mediaType());
      return work.inTransaction(
          () -> {
            // Finalization may race revocation or project replacement. Preserve the original scope
            // rather than committing a publication/claim under a newly acquired grant.
            var current =
                ports.authorizeBlob(
                    account,
                    request.project(),
                    request.topic(),
                    RelayPortProperties.Direction.INGRESS,
                    null);
            if (!properties.isEnabled() || !topic.equals(current))
              throw new CallerFault("Relay binary publication authority changed");
            relay.registerTopic(topic, RelayPayload.Kind.BLOB, Relay.Policy.systemDefault());
            var publication =
                relay.publish(
                    topic,
                    new Relay.Draft(
                        request.requestId(),
                        RelayPort.publisher(account),
                        RelayValues.time(request.occurredAt()),
                        request.correlationId(),
                        request.parentEventId(),
                        new RelayPayload.Blob(descriptor),
                        ancestry));
            return new RelayBlob.Published(
                new RelayPort.Published(
                    request.requestId(),
                    request.project(),
                    request.topic(),
                    Long.toString(publication.position()),
                    publication.publishedAt()),
                descriptor);
          });
    } catch (IllegalArgumentException conflict) {
      throw new CallerFault(
          "Relay binary publication conflicts with its retained topic or request");
    } finally {
      slots.release();
    }
  }

  @Override
  public Content read(String account, RelayBlob.Read request) {
    Objects.requireNonNull(request);
    var topic =
        ports.authorizeBlob(
            account,
            request.project(),
            request.topic(),
            RelayPortProperties.Direction.EGRESS,
            request.group());
    Relay.Publication publication;
    try {
      publication =
          relay
              .retained(topic, request.eventId())
              .orElseThrow(() -> new CallerFault("Relay blob publication is unavailable"));
    } catch (IllegalArgumentException missingTopic) {
      throw new CallerFault("Relay blob publication is unavailable");
    }
    if (!(publication.event().payload() instanceof RelayPayload.Blob binary))
      throw new CallerFault("Relay publication is not binary");
    if (!slots.tryAcquire()) throw new CallerFault("Relay binary transfer capacity is busy");
    try {
      var bytes =
          blobs.read(
              new BlobKey(RelayBlobClaims.scope(topic), binary.blob().sha256()),
              RelayBlobClaims.claim(topic, publication.position()));
      var current =
          ports.authorizeBlob(
              account,
              request.project(),
              request.topic(),
              RelayPortProperties.Direction.EGRESS,
              request.group());
      if (!topic.equals(current)) throw new CallerFault("Relay binary download authority changed");
      return new Content(binary.blob(), bytes);
    } finally {
      slots.release();
    }
  }
}
