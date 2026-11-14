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

  /** Compact metadata is consulted before any new effect, even after full records are archived. */
  default Optional<RelayWork.Archived> archived(UUID requestId) throws IOException {
    return Optional.empty();
  }

  default boolean ownsRemote(String producer, String peer, String task, String context)
      throws IOException {
    return entries().stream()
        .anyMatch(
            e -> {
              var observed = e.observation();
              return e.producer().equals(producer)
                  && e.peer().equals(peer)
                  && observed != null
                  && observed.result() != null
                  && context.equals(observed.context())
                  && (task == null || task.equals(observed.task()));
            });
  }

  /** Archive full terminal records without forgetting operation identities or remote ownership. */
  default int archiveConfirmed() throws IOException {
    throw new IOException("Journal does not support durable archiving");
  }

  @Override
  void close() throws IOException;
}
