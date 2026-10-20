package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.FileAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real temporary files exercise publication and participating writers without a database. */
class LocalProviderMutationTest {
  @TempDir Path root;

  private FileProvider provider() {
    return LocalProvider.over(
        FileAccess.of(List.of(root), List.of()), List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)));
  }

  @Test
  void replacementPreservesModeAndRefusesHardLinkAliases() throws Exception {
    Path file = Files.writeString(root.resolve("file"), "original");
    var mode = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-----");
    Files.setPosixFilePermissions(file, mode);
    provider().write(file, "replacement");
    assertEquals("replacement", Files.readString(file));
    assertEquals(mode, Files.getPosixFilePermissions(file));
    Path alias = Files.createLink(root.resolve("alias"), file);
    assertThrows(WorkspaceRefusedException.class, () -> provider().write(file, "unsafe"));
    assertEquals("replacement", Files.readString(alias));
  }

  @Test
  void replacementRetainsExtendedMetadataAndAcl() throws Exception {
    Path file = Files.writeString(root.resolve("metadata"), "original");
    var attributes =
        Files.getFileAttributeView(
            file, java.nio.file.attribute.UserDefinedFileAttributeView.class);
    org.junit.jupiter.api.Assumptions.assumeTrue(attributes != null);
    attributes.write("plowshare.fixture", java.nio.ByteBuffer.wrap(new byte[] {1, 2, 3}));
    boolean mac = System.getProperty("os.name").startsWith("Mac");
    if (mac)
      assertEquals(
          0,
          new ProcessBuilder("chmod", "+a", "everyone deny execute", file.toString())
              .start()
              .waitFor());
    provider().write(file, "changed");
    var retained =
        Files.getFileAttributeView(
            file, java.nio.file.attribute.UserDefinedFileAttributeView.class);
    var value = java.nio.ByteBuffer.allocate(3);
    assertEquals(3, retained.read("plowshare.fixture", value));
    assertArrayEquals(new byte[] {1, 2, 3}, value.array());
    if (mac) {
      var listing = new ProcessBuilder("ls", "-le", file.toString()).start();
      assertTrue(
          new String(
                  listing.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
              .contains("deny execute"));
      assertEquals(0, listing.waitFor());
    }
  }

  @Test
  void invalidEncodingDoesNotTruncateAndUnpublishedStagesAreRemoved() throws Exception {
    Path file = Files.writeString(root.resolve("file"), "original");
    assertThrows(WorkspaceRefusedException.class, () -> provider().write(file, "\ud800"));
    assertEquals("original", Files.readString(file));
    try (var files = Files.list(root)) {
      assertEquals(List.of(file), files.toList());
    }
  }

  @Test
  void publicationFailureLeavesOriginalAndRemovesStage() throws Exception {
    Path file = Files.writeString(root.resolve("file"), "original");
    assertThrows(
        IllegalStateException.class,
        () ->
            AtomicFileWrites.replace(
                file,
                "changed",
                () -> {
                  throw new IllegalStateException("revoked");
                }));
    assertEquals("original", Files.readString(file));
    try (var files = Files.list(root)) {
      assertEquals(List.of(file), files.toList());
    }
  }

  @Test
  void independentProvidersPreserveConcurrentDisjointEditsAndTextFidelity() throws Exception {
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 20; i++) {
        Path file = Files.writeString(root.resolve("file" + i), "\ufeffone\r\ntwo");
        var first = provider();
        var second = provider();
        var start = new CountDownLatch(1);
        var a =
            pool.submit(
                () -> {
                  start.await();
                  return first.edit(file, "one", "ONE");
                });
        var b =
            pool.submit(
                () -> {
                  start.await();
                  return second.edit(file, "two", "TWO");
                });
        start.countDown();
        a.get(5, TimeUnit.SECONDS);
        b.get(5, TimeUnit.SECONDS);
        assertEquals("\ufeffONE\r\nTWO", Files.readString(file));
      }
    }
  }

  @Test
  void concurrentReadersOnlyObserveCompleteVersions() throws Exception {
    String old = "a".repeat(131072), next = "b".repeat(131072);
    Path file = Files.writeString(root.resolve("file"), old);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var writer =
          pool.submit(
              () -> {
                for (int i = 0; i < 30; i++) provider().write(file, i % 2 == 0 ? next : old);
              });
      for (int i = 0; i < 200; i++) {
        String observed = Files.readString(file);
        assertTrue(observed.equals(old) || observed.equals(next));
      }
      writer.get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void createAndMissingEditKeepTheirExistingRefusals() throws Exception {
    Path file = root.resolve("file");
    assertThrows(WorkspaceRefusedException.class, () -> provider().edit(file, "one", "two"));
    assertFalse(Files.exists(file));
    provider().create(file, "original");
    assertThrows(WorkspaceRefusedException.class, () -> provider().create(file, "changed"));
    assertEquals("original", Files.readString(file));
  }
}
