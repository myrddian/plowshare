package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.embedding.*;
import java.time.Instant;
import java.util.List;

/** Owns write identity, source/privacy fences and bounded memory publication. */
public interface MemoryWriteRepository {
  QuerySources.Pin pin(MemoryWrites.Request request);

  void require(MemoryWrites.Request request, QuerySources.Pin pin, boolean processing);

  /** Short scope-before-source fence; never held across provider IO. */
  void lock(MemoryWrites.Request request, QuerySources.Pin pin, boolean processing);

  List<Memory> searchByVector(float[] query, Home home, int limit);

  List<Memory> searchByVector(EmbeddingQuery query, Home home, int limit);

  Memory used(Memory memory, Instant at);

  Memory current(String id);

  void provenance(String id, MemoryWrites.Learning source);

  /** Source identity/revision, home and live/private state are checked atomically. */
  void publishLegacy(
      MemoryWrites.Request request,
      QuerySources.Pin pin,
      EmbeddingWorkRepository.Source expected,
      float[] vector,
      Runnable validity);
}
