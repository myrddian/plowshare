package io.aeyer.plowshare.server.information;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Leased durable invalidations. A crash reclaims a batch, so recipients may see duplicates. */
public interface InformationEventRepository {
  record Event(long sequence, UUID revision, int generation, String action) {}

  /** Claims at most 100 ordered events for 30 seconds atomically using the singleton cursor. */
  List<Event> claim(UUID token, Instant now);

  List<String> recipients(long sequence);

  boolean readable(UUID revision, String account);

  /** Advances only the token still owning the lease; stale completion cannot overwrite a cursor. */
  boolean complete(UUID token, long sequence);
}
