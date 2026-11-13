package io.aeyer.plowshare.a2a.relay;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Locked, private, atomic snapshots on a configured durable POSIX volume. No automatic eviction.
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
        if (Files.isSymbolicLink(file) || Files.size(file) > RelayWorkCodec.MAX_JOURNAL_BYTES)
          throw new IOException("Invalid A2A journal file");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
          snapshot =
              RelayWorkCodec.snapshot(input.readNBytes(RelayWorkCodec.MAX_JOURNAL_BYTES + 1));
        }
        if (!snapshot.owner().equals(fingerprint))
          throw new IOException("A2A journal belongs to a different adapter binding");
      } else {
        if (existed)
          throw new IOException("A2A journal is missing; refusing to forget dispatch evidence");
        snapshot = new RelayWork.Snapshot("plowshare-a2a-journal/1", fingerprint, null, Map.of());
        persist(snapshot);
      }
    } catch (IOException | RuntimeException failed) {
      lock.release();
      owner.close();
      throw failed;
    }
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
    commit(
        new RelayWork.Snapshot(
            snapshot.schema(), snapshot.owner(), generation, snapshot.entries()));
  }

  @Override
  public synchronized Optional<RelayWork.Entry> find(UUID id) {
    ensureOpen();
    return Optional.ofNullable(snapshot.entries().get(id.toString()));
  }

  @Override
  public synchronized List<RelayWork.Entry> entries() {
    ensureOpen();
    return List.copyOf(snapshot.entries().values());
  }

  @Override
  public synchronized void save(RelayWork.Entry entry) throws IOException {
    ensureOpen();
    var existing = snapshot.entries().get(entry.requestId().toString());
    if (existing == null) {
      if (snapshot.generation() == null
          || entry.phase() != RelayWork.Phase.INTENT
          || snapshot.entries().size() >= 1024)
        throw new IOException("Invalid new dispatch or journal capacity exhausted");
    } else if (!existing.requestId().equals(entry.requestId())
        || !existing.hash().equals(entry.hash())
        || !existing.sourceEvent().equals(entry.sourceEvent())
        || !existing.producer().equals(entry.producer())
        || !existing.peer().equals(entry.peer())
        || existing.action() != entry.action()
        || !Objects.equals(existing.task(), entry.task())
        || !Objects.equals(existing.context(), entry.context())
        || !existing.occurredAt().equals(entry.occurredAt())
        || entry.phase().ordinal() != existing.phase().ordinal() + 1
        || existing.observation() != null && !existing.observation().equals(entry.observation()))
      throw new IOException("Conflicting retained dispatch");
    var records = new HashMap<>(snapshot.entries());
    records.put(entry.requestId().toString(), entry);
    commit(
        new RelayWork.Snapshot(
            snapshot.schema(), snapshot.owner(), snapshot.generation(), records));
  }

  private void commit(RelayWork.Snapshot next) throws IOException {
    try {
      persist(next);
      snapshot = next;
    } catch (IOException failure) {
      failed = true;
      throw failure;
    }
  }

  private void persist(RelayWork.Snapshot next) throws IOException {
    byte[] bytes = RelayWorkCodec.snapshot(next);
    // Reserve the maximum observation size for every intent before allowing peer I/O.
    long reserved =
        next.entries().values().stream().filter(e -> e.phase() == RelayWork.Phase.INTENT).count()
            * RelayWorkCodec.MAX_ENTRY_BYTES;
    if (bytes.length + reserved > RelayWorkCodec.MAX_JOURNAL_BYTES)
      throw new IOException("A2A journal capacity exhausted; no new external dispatch allowed");
    Path temporary =
        Files.createTempFile(
            directory,
            "dispatch-",
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
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      try (var folder = FileChannel.open(directory, StandardOpenOption.READ)) {
        folder.force(true);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private void ensureOpen() {
    if (closed || failed) throw new IllegalStateException("A2A journal closed");
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
