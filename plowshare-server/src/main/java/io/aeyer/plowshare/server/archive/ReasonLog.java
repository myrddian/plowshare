package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.VerdictKind;
import java.time.Instant;
import java.util.List;

/** Immutable write judgements, committed in the same unit of work as their memory mutation. */
public interface ReasonLog {
  public record Entry(
      String memoryId, Instant filedAt, VerdictKind kind, String targetId, String reason) {
    public Entry {
      memoryId = ArchiveValues.identity(memoryId, "memory id");
      java.util.Objects.requireNonNull(filedAt, "filedAt");
      java.util.Objects.requireNonNull(kind, "kind");
      targetId = targetId == null ? null : ArchiveValues.identity(targetId, "target id");
      reason = ArchiveValues.text(reason, "reason", 1048576);
    }
  }

  /** The archive owns this write and its encompassing transaction. */
  void record(Entry entry);

  /** Oldest first, with a stable tie-break for equal clocks. */
  List<Entry> forMemory(String memoryId);
}
