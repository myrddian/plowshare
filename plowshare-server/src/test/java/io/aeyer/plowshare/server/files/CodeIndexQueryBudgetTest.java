package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.documents.CodeOutline;
import io.aeyer.plowshare.server.information.*;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeIndexQueryBudgetTest {
  @TempDir Path temp;

  @Test
  void aHundredUnchangedSourcesUseOneIndexLookupAndOneCurrentMetadataBatch() {
    var store = mock(CodeWorkspaceStore.class);
    var catalogue = mock(InformationCatalogue.class);
    var scope = new CodeWorkspaceStore.Scope(Home.of("project"), "reader", "coder", "session");
    var sources = new ArrayList<CodeWorkspaceStore.IndexSource>();
    var revisions = new HashMap<String, UUID>();
    var hashes = new HashMap<UUID, String>();
    var states = new HashMap<UUID, CodeOutline>();
    for (int file = 0; file < 100; file++) {
      String key = "source-" + file;
      String hash = FileContents.sha256(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      UUID revision = UUID.randomUUID();
      sources.add(new CodeWorkspaceStore.IndexSource(key, new FileSource(10, hash, null, null)));
      revisions.put(key, revision);
      hashes.put(revision, hash);
      states.put(
          revision, new CodeOutline("ready", null, "java", CodeOutline.VERSION, hash, List.of()));
    }
    when(store.indexed(scope, sources, CodeOutline.VERSION)).thenReturn(revisions);
    when(catalogue.codeStates(any(), eq(hashes))).thenReturn(states);
    var index = new CodeWorkspaceIndex(store, catalogue, mock(InformationLifecycle.class));
    assertEquals(100, index.states(scope, sources).size());
    verify(store).indexed(scope, sources, CodeOutline.VERSION);
    verify(catalogue).codeStates(any(), eq(hashes));
    verifyNoMoreInteractions(store, catalogue);
  }

  @Test
  void saturationDefersTheClaimWithoutPublishingAnEmptyManifest() throws Exception {
    Path root = temp.toRealPath();
    Files.writeString(root.resolve("A.java"), "class A {}");
    var provider =
        LocalProvider.over(
            FileAccess.of(List.of(root), List.of()),
            List.of(new Grant(Scope.WORKSPACE, Mode.READ)));
    var store = mock(CodeWorkspaceStore.class);
    var indexes = mock(CodeIndexes.class);
    var scope = new CodeWorkspaceStore.Scope(Home.global(), "reader", "coder", "session");
    var ticket = new CodeMapObservations.Ticket("workspace", 1);
    when(store.claim()).thenReturn(Optional.of(new CodeWorkspaceStore.Scan(ticket, scope, "**")));
    when(indexes.states(eq(scope), anyList())).thenThrow(new ReadUnavailableException());
    try (var monitor =
        new CodeWorkspaceMonitor(
                store, ignored -> new ProviderRouter(home -> List.of(provider)), true)
            .indexing(() -> indexes)) {
      assertTrue(monitor.pollOnce());
      verify(store).defer(ticket);
      verify(store, never()).publish(any(), any());
      verify(store, never()).unavailable(any(), anyString());
    }
  }
}
