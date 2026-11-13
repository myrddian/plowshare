package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Private, locked snapshots plus compact immutable receipts. Active result bodies are bounded;
 * operation and remote-ownership metadata is retained until the binding is explicitly retired.
 * Independent journal directories are not a supported replica coordination mechanism.
 */
public final class FileDispatchJournal implements DispatchJournal {
  private final Path directory;
  private final Path file;
  private final FileChannel owner;
  private final FileLock lock;
  private RelayWork.Snapshot snapshot;
  private boolean closed;
  private boolean failed;

  public FileDispatchJournal(Path requested, String fingerprint) throws IOException {
    this(requested, fingerprint, fingerprint, fingerprint, false, null);
  }

  /**
   * Normal open; changing a secret requires an explicit rotation with stable identity unchanged.
   */
  public FileDispatchJournal(
      Path requested, String fingerprint, String identity, String legacyFingerprint)
      throws IOException {
    this(requested, fingerprint, identity, legacyFingerprint, false, null);
  }

  /**
   * Explicit administrative rotation. The previous digest must match the retained owner, and the
   * stable identity descriptor must be unchanged. This does not adopt another
   * destination/principal.
   */
  public static FileDispatchJournal rotate(
      Path directory,
      String fingerprint,
      String identity,
      String legacyFingerprint,
      String expectedPrevious)
      throws IOException {
    return new FileDispatchJournal(
        directory, fingerprint, identity, legacyFingerprint, true, expectedPrevious);
  }

  private FileDispatchJournal(
      Path requested,
      String fingerprint,
      String identity,
      String legacyFingerprint,
      boolean rotate,
      String expectedPrevious)
      throws IOException {
    if (Files.isSymbolicLink(requested)) throw new IOException("Journal symlink refused");
    Files.createDirectories(requested);
    directory = requested.toRealPath();
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    file = directory.resolve("dispatch.json");
    Path lockFile = directory.resolve("dispatch.lock");
    boolean existed = Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS);
    owner =
        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
    FileLock acquired;
    try {
      acquired = owner.tryLock();
    } catch (OverlappingFileLockException conflict) {
      owner.close();
      throw new IOException("A2A journal already owned");
    }
    if (acquired == null) {
      owner.close();
      throw new IOException("A2A journal already owned");
    }
    lock = acquired;
    try {
      Files.setPosixFilePermissions(lockFile, PosixFilePermissions.fromString("rw-------"));
      if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        snapshot = RelayWorkCodec.snapshot(read(file, RelayWorkCodec.MAX_JOURNAL_BYTES));
        if (snapshot.schema().equals("plowshare-a2a-journal/1")) {
          // v1 stored only a combined digest, so identity cannot be reconstructed after credentials
          // have changed. Restore the exact legacy configuration once to establish the descriptor.
          if (!snapshot.owner().equals(legacyFingerprint))
            throw new IOException(
                "Restore matching legacy A2A configuration once before rotating its credentials");
          snapshot =
              new RelayWork.Snapshot(
                  "plowshare-a2a-journal/2",
                  fingerprint,
                  identity,
                  null,
                  null,
                  snapshot.generation(),
                  snapshot.entries());
          persist(snapshot);
        }
        if (!snapshot.identity().equals(identity))
          throw new IOException("A2A journal destination or principal identity changed");
        if (rotate) {
          if (expectedPrevious == null || !snapshot.owner().equals(expectedPrevious))
            throw new IOException("Previous A2A owner digest does not match");
          commit(
              new RelayWork.Snapshot(
                  snapshot.schema(),
                  fingerprint,
                  identity,
                  snapshot.owner(),
                  Instant.now(),
                  snapshot.generation(),
                  snapshot.entries()));
        } else if (!snapshot.owner().equals(fingerprint))
          throw new IOException(
              "A2A credentials changed; use explicit rotate-config with the retained owner digest");
      } else {
        if (existed || rotate)
          throw new IOException("A2A journal is missing; refusing to forget dispatch evidence");
        snapshot =
            new RelayWork.Snapshot(
                "plowshare-a2a-journal/2", fingerprint, identity, null, null, null, Map.of());
        persist(snapshot);
      }
    } catch (IOException | RuntimeException failure) {
      lock.release();
      owner.close();
      throw failure;
    }
  }

  public synchronized String ownerFingerprint() {
    ensureOpen();
    return snapshot.owner();
  }

  @Override
  public synchronized void generation(String generation) throws IOException {
    ensureOpen();
    Objects.requireNonNull(generation, "Broker must return group generation");
    if (snapshot.generation() != null) {
      if (!snapshot.generation().equals(generation))
        throw new IOException("A2A Relay group incarnation changed; reconcile before consuming");
      return;
    }
    commit(with(snapshot.entries(), generation));
  }

  @Override
  public synchronized Optional<RelayWork.Entry> find(UUID requestId) {
    ensureOpen();
    return Optional.ofNullable(snapshot.entries().get(requestId.toString()));
  }

  @Override
  public synchronized List<RelayWork.Entry> entries() {
    ensureOpen();
    return List.copyOf(snapshot.entries().values());
  }

  @Override
  public synchronized Optional<RelayWork.Archived> archived(UUID requestId) throws IOException {
    ensureOpen();
    Path path = receiptPath(requestId, false);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
    var value = RelayWorkCodec.archived(read(path, RelayWorkCodec.MAX_COMPACT_BYTES));
    if (!value.requestId().equals(requestId)) throw new IOException("Foreign compact A2A receipt");
    return Optional.of(value);
  }

  @Override
  public synchronized boolean ownsRemote(String producer, String peer, String task, String context)
      throws IOException {
    if (DispatchJournal.super.ownsRemote(producer, peer, task, context)) return true;
    var expected = new RelayWork.RemoteOwnership(producer, peer, task, context);
    Path path = ownershipPath(expected, false);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
    if (!RelayWorkCodec.ownership(read(path, RelayWorkCodec.MAX_COMPACT_BYTES)).equals(expected))
      throw new IOException("Conflicting A2A remote ownership proof");
    return true;
  }

  @Override
  public synchronized void save(RelayWork.Entry entry) throws IOException {
    ensureOpen();
    var existing = snapshot.entries().get(entry.requestId().toString());
    if (existing == null) {
      if (archived(entry.requestId()).isPresent())
        throw new IOException("A2A operation identity is already archived");
      if (snapshot.entries().size() >= 768) archiveConfirmed();
      if (snapshot.generation() == null
          || snapshot.entries().size() >= 1024
          || entry.phase() != RelayWork.Phase.INTENT && !entry.rejection())
        throw new IOException("Invalid new dispatch or unsettled journal capacity exhausted");
    } else {
      if (!sameIntent(existing, entry)) throw new IOException("Conflicting retained dispatch");
      boolean observed =
          existing.phase() == RelayWork.Phase.INTENT
              && entry.phase() == RelayWork.Phase.OBSERVED
              && entry.replyAttempts() == existing.replyAttempts();
      boolean publishing =
          existing.phase() == RelayWork.Phase.OBSERVED
              && entry.phase() == RelayWork.Phase.REPLY_INTENT
              && entry.replyAttempts() == existing.replyAttempts() + 1
              && entry.resolution() == null;
      boolean confirmed =
          existing.phase() == RelayWork.Phase.REPLY_INTENT
              && entry.phase() == RelayWork.Phase.CONFIRMED
              && entry.replyAttempts() == existing.replyAttempts();
      boolean notSubmitted =
          existing.phase() == RelayWork.Phase.REPLY_INTENT
              && entry.phase() == RelayWork.Phase.OBSERVED
              && entry.replyAttempts() == existing.replyAttempts()
              && "NOT_SUBMITTED".equals(entry.resolution());
      boolean abandoned =
          existing.phase() == RelayWork.Phase.REPLY_INTENT
              && entry.phase() == RelayWork.Phase.ABANDONED
              && entry.replyAttempts() == existing.replyAttempts();
      if (!(observed || publishing || confirmed || notSubmitted || abandoned)
          || existing.observation() != null && !existing.observation().equals(entry.observation()))
        throw new IOException("Invalid retained dispatch transition");
    }
    var entries = new HashMap<>(snapshot.entries());
    entries.put(entry.requestId().toString(), entry);
    var next = with(entries, snapshot.generation());
    if (existing == null) {
      byte[] bytes;
      try {
        bytes = encoded(next);
      } catch (CapacityExceeded full) {
        // Large settled bodies can exhaust bytes long before the count watermark. Compact before
        // reserving another effect, then rebuild from the now-smaller retained active snapshot.
        archiveConfirmed();
        entries = new HashMap<>(snapshot.entries());
        entries.put(entry.requestId().toString(), entry);
        next = with(entries, snapshot.generation());
        bytes = encoded(next);
      }
      commit(next, bytes);
    } else commit(next);
  }

  /**
   * Receipts and ownership proofs are fsynced before removing a full record. A crash can leave both
   * forms, which is harmless; it can never leave neither. Compact metadata is not evicted.
   */
  @Override
  public synchronized int archiveConfirmed() throws IOException {
    ensureOpen();
    var entries = new HashMap<>(snapshot.entries());
    int archived = 0;
    for (var entry : snapshot.entries().values()) {
      if (!entry.terminal()) continue;
      var compact = RelayWork.Archived.of(entry);
      Path receipt = receiptPath(entry.requestId(), true);
      if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
        if (!RelayWorkCodec.archived(read(receipt, RelayWorkCodec.MAX_COMPACT_BYTES))
            .equals(compact)) throw new IOException("Conflicting compact A2A receipt");
      } else writeAtomic(receipt, RelayWorkCodec.archived(compact));
      if (compact.remoteContext() != null) {
        retainOwnership(
            new RelayWork.RemoteOwnership(
                compact.producer(), compact.peer(), null, compact.remoteContext()));
        if (compact.remoteTask() != null)
          retainOwnership(
              new RelayWork.RemoteOwnership(
                  compact.producer(),
                  compact.peer(),
                  compact.remoteTask(),
                  compact.remoteContext()));
      }
      entries.remove(entry.requestId().toString());
      archived++;
    }
    if (archived > 0) commit(with(entries, snapshot.generation()));
    return archived;
  }

  private void retainOwnership(RelayWork.RemoteOwnership proof) throws IOException {
    Path path = ownershipPath(proof, true);
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      if (!RelayWorkCodec.ownership(read(path, RelayWorkCodec.MAX_COMPACT_BYTES)).equals(proof))
        throw new IOException("Conflicting remote ownership evidence");
    } else writeAtomic(path, RelayWorkCodec.ownership(proof));
  }

  private Path receiptPath(UUID id, boolean create) throws IOException {
    return bucket("receipts", id.toString().substring(0, 2), create).resolve(id + ".json");
  }

  private Path ownershipPath(RelayWork.RemoteOwnership proof, boolean create) throws IOException {
    String hash =
        RelayPort.hash(
            proof.producer()
                + "\0"
                + proof.peer()
                + "\0"
                + Objects.toString(proof.task(), "")
                + "\0"
                + proof.context());
    return bucket("ownership", hash.substring(0, 2), create).resolve(hash + ".json");
  }

  private Path bucket(String category, String prefix, boolean create) throws IOException {
    Path root = directory.resolve(category);
    Path result = root.resolve(prefix);
    if (Files.isSymbolicLink(root) || Files.isSymbolicLink(result))
      throw new IOException("A2A archive symlink refused");
    if (create) {
      if (!Files.exists(root)) {
        Files.createDirectory(
            root,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        forceDirectory(directory);
      }
      if (!Files.exists(result)) {
        Files.createDirectory(
            result,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        forceDirectory(root);
      }
    }
    return result;
  }

  private static boolean sameIntent(RelayWork.Entry a, RelayWork.Entry b) {
    return a.requestId().equals(b.requestId())
        && a.hash().equals(b.hash())
        && a.sourceEvent().equals(b.sourceEvent())
        && a.producer().equals(b.producer())
        && a.peer().equals(b.peer())
        && a.action() == b.action()
        && Objects.equals(a.task(), b.task())
        && Objects.equals(a.context(), b.context())
        && a.occurredAt().equals(b.occurredAt());
  }

  private RelayWork.Snapshot with(Map<String, RelayWork.Entry> entries, String generation) {
    return new RelayWork.Snapshot(
        snapshot.schema(),
        snapshot.owner(),
        snapshot.identity(),
        snapshot.previousOwner(),
        snapshot.migratedAt(),
        generation,
        entries);
  }

  private void commit(RelayWork.Snapshot next) throws IOException {
    commit(next, encoded(next));
  }

  private void commit(RelayWork.Snapshot next, byte[] bytes) throws IOException {
    try {
      writeAtomic(file, bytes);
      snapshot = next;
    } catch (IOException failure) {
      failed = true;
      throw failure;
    }
  }

  private void persist(RelayWork.Snapshot value) throws IOException {
    writeAtomic(file, encoded(value));
  }

  private static byte[] encoded(RelayWork.Snapshot value) throws IOException {
    byte[] bytes;
    try {
      bytes = RelayWorkCodec.snapshot(value);
    } catch (RelayWorkCodec.JournalCapacityExceeded overflow) {
      throw new CapacityExceeded();
    }
    long reserved =
        value.entries().values().stream().filter(e -> e.phase() == RelayWork.Phase.INTENT).count()
            * RelayWorkCodec.MAX_ENTRY_BYTES;
    if (bytes.length + reserved > RelayWorkCodec.MAX_JOURNAL_BYTES) throw new CapacityExceeded();
    return bytes;
  }

  private static final class CapacityExceeded extends IOException {
    CapacityExceeded() {
      super("Unsettled A2A journal capacity exhausted; archive settled work before dispatch");
    }
  }

  private static byte[] read(Path file, int maximum) throws IOException {
    if (Files.isSymbolicLink(file) || Files.size(file) > maximum)
      throw new IOException("Invalid A2A journal file");
    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes(maximum + 1);
      if (bytes.length > maximum) throw new IOException("A2A journal file exceeds its bound");
      return bytes;
    }
  }

  private static void writeAtomic(Path destination, byte[] bytes) throws IOException {
    Path directory = destination.getParent();
    Path temporary =
        Files.createTempFile(
            directory,
            "a2a-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    try {
      try (var output =
          FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) output.write(buffer);
        output.force(true);
      }
      Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      forceDirectory(directory);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void forceDirectory(Path directory) throws IOException {
    try (var folder = FileChannel.open(directory, StandardOpenOption.READ)) {
      folder.force(true);
    }
  }

  private void ensureOpen() {
    if (closed || failed) throw new IllegalStateException("A2A journal closed or failed");
  }

  @Override
  public synchronized void close() throws IOException {
    if (!closed) {
      closed = true;
      try {
        lock.release();
      } finally {
        owner.close();
      }
    }
  }
}
