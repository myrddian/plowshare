package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.server.embedding.EmbeddingProfile;
import io.aeyer.plowshare.server.embedding.EmbeddingQuery;
import io.aeyer.plowshare.server.embedding.QuerySources;
import java.time.Instant;
import java.util.List;

/** Owns current tier authority, privacy/read fences, ranking and bounded use telemetry. */
public interface MemoryReadRepository {
  record Selection(QuerySources.Pin pin, List<Home> tiers) {
    public Selection {
      tiers = List.copyOf(tiers);
    }
  }

  Selection pin(MemoryQueries.Request request);

  void require(MemoryQueries.Request request, Selection expected);

  /** Called inside the short result transaction, never while provider IO is in progress. */
  void lockRead(MemoryQueries.Request request, Selection expected);

  List<Memory> searchByVector(float[] query, Home home, int limit);

  List<Memory> searchByVector(EmbeddingQuery query, Home home, int limit);

  int countUnsearchable(Home home);

  int countUnsearchable(EmbeddingProfile profile, Home home);

  /** Updates only read telemetry, never stale content/lifecycle fields from the returned object. */
  Memory used(Memory memory, Instant at);
}
