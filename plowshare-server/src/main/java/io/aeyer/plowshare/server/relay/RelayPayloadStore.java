package io.aeyer.plowshare.server.relay;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * Content-addressed storage for Relay payload bytes. Identical content is held once however many
 * publications and admissions refer to it.
 */
public interface RelayPayloadStore {
  /**
   * Encodes and stores the payload and returns its digest. The content is stored once: storing
   * identical content again adds no row and leaves the bytes as they are, but re-arms the staging
   * window when the stored date is earlier than the new one. The row is created unreferenced and
   * protected from {@link #sweep} only by its staging window, so the caller must reference it
   * promptly.
   */
  String store(RelayPayload payload, Instant now);

  /**
   * Returns the stored payloads for the digests in one query, keyed by digest. Digests without a
   * row are absent from the result; callers decide whether that is an error.
   */
  Map<String, RelayStoredPayload> load(Collection<String> digests);

  /**
   * Examines up to {@code limit} payloads whose {@code ejection_at} has matured and removes those
   * that are unreachable. Returns how many rows were removed.
   */
  int sweep(Instant now, int limit);
}
