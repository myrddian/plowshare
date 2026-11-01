package io.aeyer.plowshare.protocol;

import java.time.Instant;
import java.util.Objects;

/** Explicit binary HTTP contracts; broker/SDK log records contain descriptors rather than bytes. */
public final class RelayBlob {
  public static final int MAX_BYTES = 50 * 1024 * 1024;

  private RelayBlob() {}

  /** Full SHA-256 is content identity. Media type is declared delivery metadata, not validation. */
  public record Descriptor(String sha256, long byteLength, String mediaType) {
    public Descriptor {
      if (sha256 == null
          || !sha256.matches("[0-9a-f]{64}")
          || byteLength < 1
          || byteLength > MAX_BYTES) {
        throw new IllegalArgumentException("invalid Relay blob descriptor");
      }
      RelayBlob.mediaType(mediaType);
    }
  }

  /** Multipart metadata; account, storage scope and publisher are always server-derived. */
  public record Publish(
      String requestId,
      String project,
      String topic,
      Instant occurredAt,
      String mediaType,
      String correlationId,
      String parentTopic,
      String parentEventId) {
    public Publish {
      RelayPort.validatePublication(
          requestId, project, topic, occurredAt, correlationId, parentTopic, parentEventId);
      RelayBlob.mediaType(mediaType);
    }
  }

  public record Published(RelayPort.Published publication, Descriptor blob) {
    public Published {
      Objects.requireNonNull(publication);
      Objects.requireNonNull(blob);
    }
  }

  /** Current egress grant and exact retained publication are required independently of batching. */
  public record Read(String project, String topic, String group, String eventId) {
    public Read {
      RelayPort.identity(project);
      RelayPort.topic(topic);
      RelayPort.group(group);
      RelayPort.uuid(eventId);
    }
  }

  public static String mediaType(String value) {
    if (value == null
        || value.length() > 255
        || !value.matches("[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}")) {
      throw new IllegalArgumentException("invalid Relay blob media type");
    }
    return value;
  }
}
