package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileSource;
import io.aeyer.plowshare.server.documents.CodeOutline;
import io.aeyer.plowshare.server.documents.CodeProjection;
import io.aeyer.plowshare.server.information.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Bridges verified snapshots to the gated, immutable information pipeline. */
public final class CodeWorkspaceIndex implements CodeIndexes {
  private final CodeWorkspaceStore store;
  private final InformationCatalogue catalogue;
  private final InformationLifecycle lifecycle;
  private io.aeyer.plowshare.server.reads.ReadAdmission admission =
      io.aeyer.plowshare.server.reads.ReadAdmission.direct();

  public CodeWorkspaceIndex useAdmission(io.aeyer.plowshare.server.reads.ReadAdmission admission) {
    this.admission = Objects.requireNonNull(admission);
    return this;
  }

  public CodeWorkspaceIndex(
      CodeWorkspaceStore store, InformationCatalogue catalogue, InformationLifecycle lifecycle) {
    this.store = Objects.requireNonNull(store);
    this.catalogue = Objects.requireNonNull(catalogue);
    this.lifecycle = Objects.requireNonNull(lifecycle);
  }

  private InformationContext context(CodeWorkspaceStore.Scope scope) {
    return new InformationContext(
        scope.owner(),
        scope.home().isGlobal()
            ? InformationContext.Selection.personal()
            : InformationContext.Selection.project(scope.home().project()),
        InformationContext.Corpus.CODE);
  }

  public CodeProjection cached(CodeWorkspaceStore.Scope scope, String key, FileSource source) {
    return admission.read(
        scope.owner(),
        io.aeyer.plowshare.server.reads.ReadAdmission.Work.AUTHORITY,
        () -> {
          UUID revision = store.indexed(scope, key, source.sha256(), CodeOutline.VERSION);
          return revision == null
              ? null
              : catalogue.codeProjection(context(scope), revision, source.sha256());
        });
  }

  public java.util.Map<String, CodeMapObservations.IndexState> states(
      CodeWorkspaceStore.Scope scope, java.util.List<CodeWorkspaceStore.IndexSource> sources) {
    if (sources == null || sources.size() > 100)
      throw new IllegalArgumentException("Code index page must have at most 100 sources");
    return admission.read(
        scope.owner(),
        io.aeyer.plowshare.server.reads.ReadAdmission.Work.MAINTENANCE,
        () -> loadStates(scope, sources));
  }

  private java.util.Map<String, CodeMapObservations.IndexState> loadStates(
      CodeWorkspaceStore.Scope scope, java.util.List<CodeWorkspaceStore.IndexSource> sources) {
    var revisions = store.indexed(scope, sources, CodeOutline.VERSION);
    var hashes = new java.util.HashMap<UUID, String>();
    for (var source : sources) {
      UUID revision = revisions.get(source.key());
      if (revision != null) hashes.put(revision, source.source().sha256());
    }
    if (hashes.isEmpty()) return java.util.Map.of();
    var states = catalogue.codeStates(context(scope), hashes);
    var result = new java.util.HashMap<String, CodeMapObservations.IndexState>();
    revisions.forEach(
        (key, revision) -> {
          var state = states.get(revision);
          if (state != null) result.put(key, new CodeMapObservations.IndexState(revision, state));
        });
    return java.util.Map.copyOf(result);
  }

  public CodeProjection retain(
      CodeWorkspaceStore.Scope scope, WorkspaceCodeMap.Entry file, byte[] bytes) {
    if (bytes.length != file.fingerprint().size()
        || !FileContents.sha256(bytes).equals(file.fingerprint().sha256()))
      throw new WorkspaceRefusedException("retained code differs from verified snapshot");
    String identity = scope.id() + ":" + CodeWorkspaceStore.sourceKey(file.key());
    String name = "workspace-code/" + identity + "/" + file.path().getFileName();
    UUID request =
        UUID.nameUUIDFromBytes(
            (identity + ":" + file.fingerprint().sha256() + ":" + CodeOutline.VERSION)
                .getBytes(StandardCharsets.UTF_8));
    var admitted =
        catalogue.admitCodeSnapshot(
            context(scope), request, name, bytes, "workspace-code:" + identity, scope.session());
    store.index(
        scope, file.key(), file.fingerprint().sha256(), CodeOutline.VERSION, admitted.revision());
    // Exactly the two deterministic stages. Contended leases remain queued; blocked gates stay
    // blocked.
    for (int stage = 0; stage < 2; stage++)
      if (!lifecycle.drainCodeSyntax(admitted.revision())) break;
    return catalogue.codeProjection(
        context(scope), admitted.revision(), file.fingerprint().sha256());
  }
}
