package io.aeyer.plowshare.protocol.transport;

import java.io.IOException;

/** Typed packet writes. Implementations serialize writes, including credits, on one socket. */
public interface PacketWire {
  void segment(MessageSegment segment) throws IOException;

  void credit(SegmentCredit credit) throws IOException;
}
