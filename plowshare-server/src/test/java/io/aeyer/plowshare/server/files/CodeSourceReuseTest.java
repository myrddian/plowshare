package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.information.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeSourceReuseTest {
  @TempDir Path temp;

  @Test
  void reconnectKeepsRetainedIdentityButChangesLiveSessionFence() throws Exception {
    Path root = temp.toRealPath();
    Path path = Files.writeString(root.resolve("A.java"), "class A {}");
    var provider =
        spy(
            LocalProvider.over(
                FileAccess.of(List.of(root), List.of()),
                List.of(new Grant(Scope.WORKSPACE, Mode.READ))));
    doReturn("remote:old-session").when(provider).identity();
    var map = new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false);
    var before = map.reconcile(Home.of("project"), "**").files().getFirst();
    doReturn("remote:new-session").when(provider).identity();
    var after = map.reconcile(Home.of("project"), "**").files().getFirst();
    assertNotEquals(before.key(), after.key());
    assertEquals(before.indexSource().retainedKey(), after.indexSource().retainedKey());
    assertEquals(before.fingerprint(), after.fingerprint());
    assertThrows(WorkspaceRefusedException.class, () -> map.verifyRead(Home.of("project"), before));
    map.verifyRead(Home.of("project"), after);
    assertNotEquals(
        before.indexSource().retainedKey(),
        WorkspaceValues.retainedKey("other-provider", root, path));
    assertNotEquals(
        before.indexSource().retainedKey(),
        WorkspaceValues.retainedKey("local", root, root.resolve("B.java")));
  }

  @Test
  void retainedHitUsesCurrentCatalogueAuthorizationAndDoesNotAdmitOrDrain() {
    var store = mock(CodeWorkspaceStore.class);
    var catalogue = mock(InformationCatalogue.class);
    var lifecycle = mock(InformationLifecycle.class);
    var scope = new CodeWorkspaceStore.Scope(Home.of("project"), "alice", "coder", "new-session");
    String hash = "a".repeat(64);
    UUID revision = UUID.randomUUID();
    var source =
        new CodeWorkspaceStore.IndexSource(
            "live-session-key", "stable-source-key", new FileSource(10, hash, null, null));
    when(store.indexed(scope, source.retainedKey(), hash, CodeOutline.VERSION))
        .thenReturn(revision);
    var expected =
        new CodeProjection(
            revision,
            "class A {}",
            new CodeOutline("ready", null, "java", CodeOutline.VERSION, hash, List.of()));
    when(catalogue.codeProjection(any(), eq(revision), eq(hash))).thenReturn(expected);
    var index = new CodeWorkspaceIndex(store, catalogue, lifecycle);
    assertSame(expected, index.cached(scope, source));
    verify(catalogue).codeProjection(any(), eq(revision), eq(hash));
    verifyNoMoreInteractions(catalogue);
    verifyNoInteractions(lifecycle);
    when(catalogue.codeProjection(any(), eq(revision), eq(hash)))
        .thenThrow(new WorkspaceRefusedException("revoked"));
    assertThrows(WorkspaceRefusedException.class, () -> index.cached(scope, source));
    verifyNoInteractions(lifecycle);
  }

  @Test
  void reconnectOf207UnchangedFilesDoesNoIntakeOrSyntaxWork() throws Exception {
    Path root = temp.toRealPath();
    for (int file = 0; file < 207; file++)
      Files.writeString(root.resolve("F" + file + ".java"), "class F" + file + " {}");
    var provider =
        spy(
            LocalProvider.over(
                FileAccess.of(List.of(root), List.of()),
                List.of(new Grant(Scope.WORKSPACE, Mode.READ))));
    doReturn("remote:before").when(provider).identity();
    var store = mock(CodeWorkspaceStore.class);
    var catalogue = mock(InformationCatalogue.class);
    var lifecycle = mock(InformationLifecycle.class);
    var retained = new HashMap<String, UUID>();
    var projections = new HashMap<UUID, CodeProjection>();
    when(store.indexed(any(), anyString(), anyString(), anyString()))
        .thenAnswer(
            call ->
                retained.get(
                    call.getArgument(1, String.class) + ":" + call.getArgument(2, String.class)));
    doAnswer(
            call -> {
              retained.put(
                  call.getArgument(1, String.class) + ":" + call.getArgument(2, String.class),
                  call.getArgument(4));
              return null;
            })
        .when(store)
        .index(any(), anyString(), anyString(), anyString(), any());
    when(catalogue.admitCodeSnapshot(any(), any(), anyString(), any(), anyString(), anyString()))
        .thenAnswer(
            call -> {
              UUID revision = UUID.randomUUID();
              byte[] bytes = call.getArgument(3);
              String hash = FileContents.sha256(bytes);
              projections.put(
                  revision,
                  new CodeProjection(
                      revision,
                      new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                      new CodeOutline(
                          "ready", null, "java", CodeOutline.VERSION, hash, List.of())));
              return new InformationCatalogue.Admission(revision, UUID.randomUUID(), true);
            });
    when(catalogue.codeProjection(any(), any(), anyString()))
        .thenAnswer(call -> projections.get(call.getArgument(1)));
    var index = new CodeWorkspaceIndex(store, catalogue, lifecycle);
    try (var monitor =
        new CodeWorkspaceMonitor(
                store, ignored -> new ProviderRouter(home -> List.of(provider)), true)
            .indexing(() -> index)) {
      var first =
          new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false)
              .observing(monitor.observations("coder", "session", "alice"))
              .reconcile(Home.of("project"), "**");
      assertEquals(207, projections.size());
      assertEquals(207, first.files().size());
      clearInvocations(provider, catalogue, lifecycle);
      doReturn("remote:after").when(provider).identity();
      var after =
          new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false)
              .observing(monitor.observations("coder", "session", "alice"))
              .reconcile(Home.of("project"), "**");
      assertEquals(207, after.measurements().get("index_hits"));
      assertEquals(0, after.measurements().get("snapshot_reads"));
      assertEquals(
          first.files().stream().map(WorkspaceCodeMap.Entry::revision).sorted().toList(),
          after.files().stream().map(WorkspaceCodeMap.Entry::revision).sorted().toList());
      verify(provider, times(207)).fingerprint(any());
      verify(provider, never()).snapshot(any(), any(), anyInt());
      verify(catalogue, never())
          .admitCodeSnapshot(any(), any(), anyString(), any(), anyString(), anyString());
      verifyNoInteractions(lifecycle);
      assertEquals(207, projections.size());
    }
  }
}
