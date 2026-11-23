package io.aeyer.plowshare.integrations.ha;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldDescription;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.IdentityKind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Private bounded registry anchors and field shapes, never readings or SDK receipts. The separate
 * nonblocking lock prevents overlapping processes from guessing different identities. Failed
 * persistence fences further discovery; restart inspects the original atomic snapshot.
 */
public final class HomeAssistantCapabilityIdentityStore implements AutoCloseable {
  private static final int MAX_ANCHORS = 1024;
  private static final int MAX_BYTES = 4 * 1024 * 1024;
  private static final ObjectMapper CODEC =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(16)
                          .maxStringLength(4096)
                          .build())
                  .build())
          .findAndAddModules()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .build();

  /** Registry identity is hashed at the native boundary; retained metadata never stores values. */
  public record Anchor(
      String selector,
      String registryKey,
      IdentityKind identity,
      String entityId,
      String resourceId,
      String name,
      List<String> aliases,
      Optional<String> device,
      Optional<String> area,
      List<FieldDescription> fields) {
    public Anchor {
      HomeAssistantCapabilities.text(selector, 512);
      HomeAssistantCapabilities.revision(registryKey);
      if (identity != IdentityKind.REGISTRY && identity != IdentityKind.LOCATOR_ONLY)
        throw new IllegalArgumentException("Invalid retained capability identity");
      var checked =
          new HomeAssistantCapabilities.ResourceSummary(
              resourceId,
              entityId,
              name,
              aliases,
              device,
              area,
              HomeAssistantResources.Availability.MISSING,
              identity,
              fields);
      aliases = checked.aliases();
      fields = checked.matchedFields();
      device = checked.device();
      area = checked.area();
    }
  }

  public record State(int version, String owner, String namespace, List<Anchor> anchors) {
    public State {
      if (version != 1)
        throw new IllegalArgumentException("Unsupported capability identity version");
      HomeAssistantCapabilities.revision(owner);
      HomeAssistantCapabilities.revision(namespace);
      anchors = List.copyOf(anchors);
      if (anchors.size() > MAX_ANCHORS
          || anchors.stream().map(Anchor::selector).distinct().count() != anchors.size())
        throw new IllegalArgumentException("Capability identity store exceeds bound");
      if (anchors.stream()
          .anyMatch(
              value ->
                  value.identity() == IdentityKind.REGISTRY
                      && !value
                          .resourceId()
                          .equals("r_" + Json.hash(namespace + "\0" + value.registryKey()))))
        throw new IllegalArgumentException("Capability registry handle disagrees with its anchor");
    }
  }

  private final Path directory;
  private final Path file;
  private final FileChannel ownership;
  private final FileLock lock;
  private State state;
  private boolean closed;
  private boolean uncertain;

  /**
   * Losing identity state while provider receipts remain must never rebind fixed selectors. Called
   * before opening/creating an identity directory; no journal content or private payload is read.
   */
  public static void requireRetainedSnapshot(Path directory, boolean retainedProviderState)
      throws IOException {
    Objects.requireNonNull(directory);
    if (!retainedProviderState) return;
    try {
      if (!Files.readAttributes(
              directory.resolve("identities.json"),
              BasicFileAttributes.class,
              LinkOption.NOFOLLOW_LINKS)
          .isRegularFile())
        throw new IOException("Retained capability identity snapshot is not a regular file");
    } catch (IOException unavailable) {
      throw new IOException(
          "Provider receipts remain but capability identities are unavailable; restore retained state");
    }
  }

  public HomeAssistantCapabilityIdentityStore(Path requested, String owner, String namespace)
      throws IOException {
    Objects.requireNonNull(requested);
    HomeAssistantCapabilities.revision(owner);
    HomeAssistantCapabilities.revision(namespace);
    for (Path path = requested.toAbsolutePath(); path != null; path = path.getParent())
      if (Files.isSymbolicLink(path)) throw new IOException("Capability identity symlink refused");
    Files.createDirectories(requested);
    directory = requested.toRealPath();
    permissions(directory, "rwx------");
    file = directory.resolve("identities.json");
    Path ownerFile = directory.resolve("owner.lock");
    boolean retainedOwner = Files.exists(ownerFile, LinkOption.NOFOLLOW_LINKS);
    if (Files.isSymbolicLink(file) || Files.isSymbolicLink(ownerFile))
      throw new IOException("Capability identity symlink refused");
    ownership =
        FileChannel.open(
            ownerFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
    FileLock acquired;
    try {
      acquired = ownership.tryLock();
    } catch (OverlappingFileLockException busy) {
      ownership.close();
      throw new IOException("Capability identity store is already owned");
    } catch (IOException | RuntimeException failure) {
      ownership.close();
      throw failure;
    }
    if (acquired == null) {
      ownership.close();
      throw new IOException("Capability identity store is already owned");
    }
    lock = acquired;
    try {
      if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes;
        try (InputStream input =
            Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
          bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES)
          throw new IOException("Capability identity byte bound exceeded");
        try {
          state = CODEC.readValue(bytes, State.class);
        } catch (IOException | RuntimeException invalid) {
          throw new IOException("Capability identity store is invalid");
        }
        if (!state.owner().equals(owner) || !state.namespace().equals(namespace))
          throw new IOException("Capability identity store belongs to another configuration");
      } else {
        if (retainedOwner) throw new IOException("Retained capability identities are missing");
        state = new State(1, owner, namespace, List.of());
        persist(state);
      }
    } catch (IOException | RuntimeException failure) {
      try {
        lock.release();
      } finally {
        ownership.close();
      }
      throw failure;
    }
  }

  public synchronized State state() throws IOException {
    requireOpen();
    return state;
  }

  /**
   * One atomic metadata transaction per collection, completed before any new anchor is disclosed.
   */
  public synchronized void install(List<Anchor> anchors) throws IOException {
    requireOpen();
    var replacement = new State(1, state.owner(), state.namespace(), anchors);
    if (replacement.equals(state)) return;
    try {
      persist(replacement);
    } catch (IOException failure) {
      uncertain = true;
      throw failure;
    }
    state = replacement;
  }

  private void persist(State value) throws IOException {
    byte[] bytes = CODEC.writeValueAsBytes(value);
    if (bytes.length > MAX_BYTES) throw new IOException("Capability identity byte bound exceeded");
    Path temporary = Files.createTempFile(directory, "identities-", ".pending");
    try {
      permissions(temporary, "rw-------");
      try (FileChannel output =
          FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) output.write(buffer);
        output.force(true);
      }
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
        parent.force(true);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void permissions(Path path, String permissions) throws IOException {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    } catch (UnsupportedOperationException unsupported) {
      // The mandatory exclusive lock still fences ownership on non-POSIX filesystems.
    }
  }

  private void requireOpen() throws IOException {
    if (closed || uncertain)
      throw new IOException(
          "Capability identities unavailable; inspect retained state before reopening");
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    try {
      lock.release();
    } finally {
      ownership.close();
    }
  }
}
