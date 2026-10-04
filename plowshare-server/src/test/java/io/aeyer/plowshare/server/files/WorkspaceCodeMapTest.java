package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceCodeMapTest {
  @TempDir Path temp;

  private LocalProvider local(Path root, List<Path> excluded) {
    return LocalProvider.over(
        FileAccess.of(List.of(root), excluded), List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)));
  }

  @Test
  void repeated_external_edits_additions_renames_and_deletes_reconcile_without_git()
      throws Exception {
    Path root = temp.toRealPath(), file = root.resolve("First.java");
    Files.writeString(file, "class First { void oldName() {} }");
    var map =
        new WorkspaceCodeMap(
            new ProviderRouter(home -> List.of(local(root, List.of()))), () -> false);
    var first = map.reconcile(Home.global(), "**");
    assertEquals("observed", first.state());
    assertEquals(
        List.of("First", "oldName"),
        first.files().getFirst().outline().symbols().stream().map(s -> s.name()).toList());
    Files.writeString(file, "class First { void newName() {} }");
    var second = map.reconcile(Home.global(), null);
    assertNotEquals(
        first.files().getFirst().fingerprint(), second.files().getFirst().fingerprint());
    assertThrows(
        WorkspaceRefusedException.class,
        () -> map.verifyRead(Home.global(), first.files().getFirst()));
    Files.writeString(file, "class First { void thirdName() {} }");
    assertTrue(map.reconcile(Home.global(), null).files().getFirst().text().contains("thirdName"));
    Files.move(file, root.resolve("Moved.java"));
    Files.writeString(root.resolve("Extra.py"), "def extra():\n    pass\n");
    assertEquals(
        List.of("Extra.py", "Moved.java"),
        map.reconcile(Home.global(), null).files().stream()
            .map(e -> e.path().getFileName().toString())
            .sorted()
            .toList());
    Files.delete(root.resolve("Moved.java"));
    assertEquals(1, map.reconcile(Home.global(), null).files().size());
  }

  @Test
  void revoked_grants_exclusions_and_failed_hashes_never_reuse_old_entries() throws Exception {
    Path root = temp.toRealPath(), privateDir = Files.createDirectory(root.resolve("secret"));
    Files.writeString(privateDir.resolve("Hidden.java"), "class Hidden {}");
    Path visible = Files.writeString(root.resolve("Visible.java"), "class Visible {}");
    FileProvider provider = spy(local(root, List.of(privateDir)));
    AtomicReference<List<FileProvider>> current = new AtomicReference<>(List.of(provider));
    var map = new WorkspaceCodeMap(new ProviderRouter(home -> current.get()), () -> false);
    assertEquals(
        List.of(visible),
        map.reconcile(Home.global(), "**").files().stream()
            .map(WorkspaceCodeMap.Entry::path)
            .toList());
    doThrow(new WorkspaceUnavailableException("lost disk")).when(provider).fingerprint(visible);
    var failed = map.reconcile(Home.global(), null);
    assertEquals("partial", failed.state());
    assertTrue(failed.files().isEmpty());
    assertFalse(failed.issues().isEmpty());
    current.set(List.of(local(root, List.of(privateDir))));
    assertEquals(1, map.reconcile(Home.global(), null).files().size());
    current.set(List.of());
    var revoked = map.reconcile(Home.global(), null);
    assertEquals("partial", revoked.state());
    assertTrue(revoked.files().isEmpty());
  }

  @Test
  void active_mutation_invalidates_answers_and_an_older_scan_cannot_overwrite_newer_state()
      throws Exception {
    Path root = temp.toRealPath(), file = Files.writeString(root.resolve("A.java"), "class A {}");
    FileProvider provider = spy(local(root, List.of()));
    var map = new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false);
    var view = map.reconcile(Home.global(), "**");
    map.beforeMutation();
    assertThrows(WorkspaceRefusedException.class, () -> map.verify(view));
    var dirty = map.reconcile(Home.global(), null);
    assertEquals("dirty", dirty.state());
    assertTrue(dirty.files().isEmpty());
    map.afterMutation(Home.global(), List.of(file));
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return call.callRealMethod();
            })
        .doCallRealMethod()
        .when(provider)
        .fingerprint(file);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var older = executor.submit(() -> map.reconcile(Home.global(), null));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      Files.writeString(file, "class Newer {}");
      var newer = map.reconcile(Home.global(), null);
      release.countDown();
      var settled = older.get(5, TimeUnit.SECONDS);
      assertEquals(newer.generation(), settled.generation());
      assertTrue(settled.files().getFirst().text().contains("Newer"));
      map.verify(newer);
    } finally {
      release.countDown();
    }
  }

  @Test
  void oversized_invalid_and_unsupported_sources_have_explicit_status() throws Exception {
    Path root = temp.toRealPath();
    Files.writeString(root.resolve("Huge.java"), "x".repeat(WorkspaceCodeMap.MAX_FILE_BYTES + 1));
    Files.write(root.resolve("Binary.java"), new byte[] {0, 1});
    Files.writeString(root.resolve("Module.rs"), "fn main() {}");
    var map =
        new WorkspaceCodeMap(
            new ProviderRouter(home -> List.of(local(root, List.of()))), () -> false);
    var view = map.reconcile(Home.global(), "**");
    assertEquals("partial", view.state());
    assertEquals(2, view.files().size());
    assertEquals(
        Set.of("limited", "unsupported"),
        new HashSet<>(view.files().stream().map(e -> e.outline().status()).toList()));
    assertNull(
        view.files().stream()
            .filter(e -> e.path().endsWith("Huge.java"))
            .findFirst()
            .orElseThrow()
            .text());
  }

  @Test
  void exclusion_during_inventory_verification_discards_previously_visible_source()
      throws Exception {
    Path root = temp.toRealPath(),
        file = Files.writeString(root.resolve("Secret.java"), "class Secret {}");
    FileProvider provider = spy(local(root, List.of()));
    doReturn(List.of(file)).doReturn(List.of()).when(provider).glob("**");
    var map = new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false);
    var view = map.reconcile(Home.global(), "**");
    assertEquals("partial", view.state());
    assertTrue(view.files().isEmpty());
    assertTrue(view.issues().getFirst().contains("inventory_changed"));
  }

  @Test
  void raw_local_snapshots_refuse_stale_hashes_and_paths_outside_the_grant() throws Exception {
    Path root = Files.createDirectory(temp.resolve("root")).toRealPath();
    var provider = local(root, List.of());
    Path file = Files.writeString(root.resolve("A.java"), "class A {}");
    var fingerprint = provider.fingerprint(file);
    assertArrayEquals(Files.readAllBytes(file), provider.snapshot(file, fingerprint, 100));
    Files.writeString(file, "class B {}");
    assertThrows(
        WorkspaceUnavailableException.class, () -> provider.snapshot(file, fingerprint, 100));
    Path outside = Files.writeString(temp.resolve("Outside.java"), "class Outside {}");
    assertThrows(WorkspaceRefusedException.class, () -> provider.fingerprint(outside));
  }
}
