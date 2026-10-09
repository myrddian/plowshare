package io.aeyer.plowshare.protocol.transport;

/** Acknowledges one accepted byte range only; it does not acknowledge an application operation. */
public record SegmentCredit(String kind, int version, String transferId, int segmentNumber) {
  public SegmentCredit {
    if (!"transport.credit".equals(kind)
        || version != 1
        || segmentNumber < 1
        || segmentNumber > SegmentedMessages.MAX_MESSAGE_BYTES / SegmentedMessages.CHUNK_BYTES)
      throw new IllegalArgumentException("invalid packet credit");
    MessageSegment.identity(transferId);
  }
}
