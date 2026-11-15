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

  Optional<Memory> load(String id, Reader reader);

  /** One authorized batch; caller resolves missing ids before any telemetry or disclosure. */
  List<Memory> load(List<String> ids, Reader reader);

  List<TocEntry> index(Home home, Reader reader);

  /** Short disclosure transaction, ordered identically to recall and write publication. */
  void lockDisclosure();

  List<Memory> loadAll(Home home);

  void saveEmbedding(String id, float[] embedding);

  List<TocEntry> index(Home home);

  List<Memory> unsearchable(Home home);
}
