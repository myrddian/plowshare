package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileSource;
import io.aeyer.plowshare.server.documents.CodeOutline;
import io.aeyer.plowshare.server.documents.CodeProjection;
import io.aeyer.plowshare.server.information.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Bridges verified snapshots to the gated, immutable information pipeline. */
public final class CodeWorkspaceIndex {
  private final CodeWorkspaceStore store;
  private final InformationCatalogue catalogue;
  private final InformationLifecycle lifecycle;

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
    UUID revision = store.indexed(scope, key, source.sha256(), CodeOutline.VERSION);
    return revision == null
        ? null
        : catalogue.codeProjection(context(scope), revision, source.sha256());
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
