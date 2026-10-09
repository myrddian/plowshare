package io.aeyer.plowshare.protocol.transport;

import java.util.Base64;
import java.util.UUID;

/** One validated byte range of an ordinary encoded application message, not a domain event. */
public record MessageSegment(
    String kind,
    int version,
    String transferId,
    int segmentNumber,
    int segmentCount,
    int byteOffset,
    int totalBytes,
    String sha256,
    String data) {
  public MessageSegment {
    if (!"transport.segment".equals(kind) || version != 1)
      throw new IllegalArgumentException("unsupported packet");
    identity(transferId);
    if (totalBytes <= 0
        || totalBytes > SegmentedMessages.MAX_MESSAGE_BYTES
        || segmentCount
            != (totalBytes + SegmentedMessages.CHUNK_BYTES - 1) / SegmentedMessages.CHUNK_BYTES
        || segmentNumber < 1
        || segmentNumber > segmentCount
        || byteOffset != (segmentNumber - 1) * SegmentedMessages.CHUNK_BYTES
        || sha256 == null
        || !sha256.matches("[a-f0-9]{64}")
        || data == null
        || data.length() > 87384) throw new IllegalArgumentException("invalid segment metadata");
    byte[] decoded = Base64.getDecoder().decode(data);
    if (decoded.length != Math.min(SegmentedMessages.CHUNK_BYTES, totalBytes - byteOffset)
        || !Base64.getEncoder().encodeToString(decoded).equals(data))
      throw new IllegalArgumentException("invalid segment bytes");
  }

  static void identity(String value) {
    if (value == null || !UUID.fromString(value).toString().equals(value))
      throw new IllegalArgumentException("invalid transfer identity");
  }
}
