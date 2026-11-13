package io.aeyer.plowshare.a2a.relay;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sole durable owner of external dispatch evidence; a Relay lease alone is insufficient. */
public interface DispatchJournal extends AutoCloseable {
  /** Pin the broker group incarnation; losing/recreating a cursor needs explicit reconciliation. */
  void generation(String generation) throws IOException;

  Optional<RelayWork.Entry> find(UUID requestId);

  List<RelayWork.Entry> entries();

  /** Commits and fsyncs before returning; failures forbid the next side effect. */
  void save(RelayWork.Entry entry) throws IOException;

  @Override
  void close() throws IOException;
}
