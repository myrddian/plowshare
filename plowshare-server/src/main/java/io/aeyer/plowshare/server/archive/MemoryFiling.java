package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.*;

/** The archive lifecycle needed by an admitted write, without retrieval or maintenance access. */
public interface MemoryFiling {
  int maxBodyChars();

  /**
   * Checks mutation authority inside the content transaction, commits reason/provenance together,
   * then invokes indexing after releasing the connection. Indexing must preserve committed content
   * on failure. A binding is trusted server admission, never a caller-supplied verdict capability.
   */
  WriteResult applyAdmitted(
      MemoryProposal proposal, Verdict verdict, Home home, Archive.WriteBinding binding);
}
