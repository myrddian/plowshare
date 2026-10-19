package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.server.embedding.DualEmbeddings;
import java.util.List;
import java.util.Optional;

/**
 * Archive persistence and its existing scoped read/write contracts.
 *
 * <p>Consumers depend on this contract so repository exception translation may use interface
 * proxies. Content/lifecycle writes preserve stored vectors unless explicitly replacing them;
 * loading by identity retains tombstones, while discovery applies the repository's state fences.
 */
public interface MemoryRepository extends MemoryReadRepository, MemoryWriteRepository {
  /** Composition binds the existing dual-embedding publisher before workers start. */
  void useDualEmbeddings(DualEmbeddings embeddings);

  /** Composition enables the mandatory information privacy fences before serving requests. */
  void protectInformation();

  void save(Memory memory);

  Optional<Memory> load(String id);

  List<Memory> loadAll(Home home);

  void saveEmbedding(String id, float[] embedding);

  List<TocEntry> index(Home home);

  List<Memory> unsearchable(Home home);
}
