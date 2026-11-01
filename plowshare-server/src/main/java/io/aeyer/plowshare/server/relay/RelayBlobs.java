package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayBlob;
import java.io.InputStream;

/** Authenticated binary ingress/egress; the broker continues to carry descriptors only. */
public interface RelayBlobs {
  /**
   * Single attempt. Caller owns the stream; reconcile uncertain delivery with the same request
   * ID/bytes.
   */
  RelayBlob.Published publish(
      String account, RelayBlob.Publish request, long size, InputStream bytes);

  /**
   * Current egress grant and an exact retained publication, not a batch token or hash, authorize
   * reads.
   */
  Content read(String account, RelayBlob.Read request);

  /** Private response value; never put binary bodies into log/protocol JSON. */
  record Content(RelayBlob.Descriptor descriptor, byte[] bytes) {
    public Content {
      java.util.Objects.requireNonNull(descriptor);
      bytes = bytes.clone();
      if (bytes.length != descriptor.byteLength())
        throw new IllegalArgumentException("invalid blob content length");
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
