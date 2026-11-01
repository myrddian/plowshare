package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.server.documents.CodeProjection;
import java.util.List;
import java.util.Map;

/**
 * Gated immutable code indexing, separate from workspace/provider authorization and observations.
 */
public interface CodeIndexes {
  /** Foreground navigation; returns null while syntax is pending, never a stale projection. */
  CodeProjection cached(CodeWorkspaceStore.Scope scope, CodeWorkspaceStore.IndexSource source);

  /** At most 100 current exact source/hash/parser statuses; no text or declarations are loaded. */
  Map<String, CodeMapObservations.IndexState> states(
      CodeWorkspaceStore.Scope scope, List<CodeWorkspaceStore.IndexSource> sources);

  /** Retains one verified source through the existing gated intake and deterministic stages. */
  CodeProjection retain(CodeWorkspaceStore.Scope scope, WorkspaceCodeMap.Entry file, byte[] bytes);
}
