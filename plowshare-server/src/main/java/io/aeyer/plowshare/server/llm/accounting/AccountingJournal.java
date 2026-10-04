package io.aeyer.plowshare.server.llm.accounting;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

/**
 * Single-writer, bounded metadata journal. Each acknowledged append is fsynced; only committed
 * projection advances the checkpoint. Payloads remain on disk, with bounded frame metadata in
 * memory. A torn final frame is truncated; checksum corruption is refused, including in the final
 * frame. The I/O timeout bounds lock admission, not a kernel fsync already in progress.
 */
public final class AccountingJournal implements AutoCloseable {
  static final int SEGMENT_HEADER = 32;
  static final int FRAME_HEADER = 16;
  static final int MAX_EVENT_BYTES = 1024 * 1024;
  private static final int MAGIC = 0x5053414a;
  private static final int VERSION = 1;
  private static final int CHECKPOINT_BYTES = 36;
  private static final java.util.Set<Path> WRITERS =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final Path directory;
  private final long maxBytes;
  private final long segmentBytes;
  private final long lockTimeoutMillis;
  private final ObjectMapper mapper;
  private final Sync sync;
  private final ReentrantLock mutex = new ReentrantLock();
  private final ArrayDeque<Frame> pending = new ArrayDeque<>();
  private final List<Path> segments = new ArrayList<>();
  private FileChannel lockChannel;
  private FileLock writerLock;
  private Path ownedDirectory;
  private FileChannel active;
  private Path activePath;
  private UUID journalId;
  private long acknowledged;
  private long written;
  private long bytes;
  private boolean closed;
  private volatile Health health = new Health(0, null, 0, 0, 0, 0, Problem.NONE, null);

  public AccountingJournal(
      Path directory, long maxBytes, long segmentBytes, Duration ioTimeout, ObjectMapper mapper) {
    this(directory, maxBytes, segmentBytes, ioTimeout, mapper, channel -> channel.force(true));
  }

  /** Package-local fault injection tests actual write/commit/ack crash boundaries. */
  AccountingJournal(
      Path directory,
      long maxBytes,
      long segmentBytes,
      Duration ioTimeout,
      ObjectMapper mapper,
      Sync sync) {
    if (segmentBytes < 1024
        || maxBytes < segmentBytes
        || ioTimeout == null
        || ioTimeout.isNegative()
        || ioTimeout.isZero()
        || ioTimeout.toMillis() < 1) {
      throw new IllegalArgumentException("invalid journal capacity or lock timeout");
    }
    this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    this.maxBytes = maxBytes;
    this.segmentBytes = segmentBytes;
    this.lockTimeoutMillis = ioTimeout.toMillis();
    this.mapper =
        mapper
            .copy()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    this.sync = Objects.requireNonNull(sync);
    try {
      if (Files.isSymbolicLink(this.directory)) {
        throw new IOException("journal directory is a symbolic link");
      }
      Files.createDirectories(this.directory);
      if (Files.getFileStore(this.directory).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
      }
      // Opening/closing another descriptor can disturb process-scoped OS locks on some
      // platforms. Refuse duplicate JVM writers before opening that second descriptor.
      Path identity = this.directory.toRealPath();
      if (!WRITERS.add(identity)) {
        throw new AccountingUnavailableException(Problem.WRITER_LOCKED);
      }
      ownedDirectory = identity;
      lockChannel =
          open(
              this.directory.resolve("journal.lock"),
              StandardOpenOption.CREATE,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE);
      try {
        writerLock = lockChannel.tryLock();
      } catch (OverlappingFileLockException held) {
        throw new AccountingUnavailableException(Problem.WRITER_LOCKED);
      }
      if (writerLock == null) {
        throw new AccountingUnavailableException(Problem.WRITER_LOCKED);
      }
      Path checkpoint = this.directory.resolve("checkpoint");
      if (Files.exists(checkpoint, LinkOption.NOFOLLOW_LINKS)) {
        readCheckpoint();
      } else {
        try (var paths = Files.list(this.directory)) {
          if (paths.anyMatch(path -> !path.getFileName().toString().equals("journal.lock"))) {
            throw new AccountingUnavailableException(Problem.CORRUPT);
          }
        }
        journalId = UUID.randomUUID();
        checkpoint(0);
      }
      load();
      reclaim();
      publish(Problem.NONE, null);
    } catch (IOException | RuntimeException failed) {
      release();
      if (failed instanceof AccountingUnavailableException refused) {
        throw refused;
      }
      throw new AccountingUnavailableException(Problem.IO_ERROR);
    }
  }

  public UUID journalId() {
    return journalId;
  }

  public Health health() {
    return health;
  }

  /** Returns only after the frame has been forced to disk. Does not require PostgreSQL. */
  public long append(AccountingEvent event) {
    byte[] payload;
    try {
      payload = mapper.writeValueAsBytes(Objects.requireNonNull(event));
    } catch (IOException encoding) {
      throw new AccountingUnavailableException(Problem.INVALID_EVENT);
    }
    if (payload.length > MAX_EVENT_BYTES) {
      throw new AccountingUnavailableException(Problem.INVALID_EVENT);
    }
    lock();
    try {
      requireOpen();
      if (health.problem() != Problem.NONE && health.problem() != Problem.FULL) {
        throw new AccountingUnavailableException(health.problem());
      }
      int size = FRAME_HEADER + payload.length;
      // Reuse a durable empty segment header left by a stopped append. A single event
      // may exceed the rotation target; rotating that empty header would reuse its name.
      boolean rotate =
          active == null || (active.size() > SEGMENT_HEADER && active.size() + size > segmentBytes);
      long needed = size + (rotate ? SEGMENT_HEADER : 0L);
      if (needed > maxBytes - bytes) {
        publish(Problem.FULL, null);
        throw new AccountingUnavailableException(Problem.FULL);
      }
      long sequence = Math.addExact(written, 1);
      if (rotate) {
        rotate(sequence);
      }
      long offset = active.size();
      ByteBuffer header =
          ByteBuffer.allocate(FRAME_HEADER)
              .putInt(payload.length)
              .putLong(sequence)
              .putInt(checksum(payload.length, sequence, payload));
      header.flip();
      write(active, header);
      write(active, ByteBuffer.wrap(payload));
      sync.force(active);
      pending.addLast(new Frame(sequence, activePath, offset, size, event.at()));
      written = sequence;
      bytes += size;
      publish(Problem.NONE, null);
      return sequence;
    } catch (IOException | ArithmeticException failed) {
      publish(Problem.IO_ERROR, name(activePath));
      throw new AccountingUnavailableException(Problem.IO_ERROR);
    } finally {
      mutex.unlock();
    }
  }

  /** A bounded prefix, immutable while the projector commits it without holding this lock. */
  public List<Entry> readBatch(int limit) {
    if (limit < 1 || limit > 1000) {
      throw new IllegalArgumentException("journal batch must have 1..1000 records");
    }
    lock();
    try {
      requireOpen();
      var entries = new ArrayList<Entry>();
      for (Frame frame : pending) {
        try (FileChannel file = open(frame.path(), StandardOpenOption.READ)) {
          file.position(frame.offset());
          entries.add(readFrame(file, frame.sequence(), frame.path()));
        }
        if (entries.size() == limit) {
          break;
        }
      }
      return List.copyOf(entries);
    } catch (IOException failed) {
      publish(Problem.IO_ERROR, null);
      throw new AccountingUnavailableException(Problem.IO_ERROR);
    } finally {
      mutex.unlock();
    }
  }

  /**
   * Projector-only authority: acknowledge this prefix only after its database transaction commits.
   */
  public void acknowledge(long sequence) {
    lock();
    try {
      requireOpen();
      if (sequence < acknowledged || sequence > written) {
        throw new IllegalArgumentException("invalid journal acknowledgement");
      }
      if (sequence > acknowledged) {
        checkpoint(sequence);
        acknowledged = sequence;
        while (!pending.isEmpty() && pending.getFirst().sequence() <= sequence) {
          pending.removeFirst();
        }
      }
      reclaim();
      publish(health.problem() == Problem.FULL ? Problem.NONE : health.problem(), health.segment());
    } catch (IOException failed) {
      publish(Problem.IO_ERROR, null);
      throw new AccountingUnavailableException(Problem.IO_ERROR);
    } finally {
      mutex.unlock();
    }
  }

  /** Reconcile an uncertain append before retrying buffered terminal metadata; never inference. */
  public void recover() {
    lock();
    try {
      requireOpen();
      closeActive();
      readCheckpoint();
      load();
      if (active != null) {
        sync.force(active);
      }
      reclaim();
      forceDirectory();
      publish(Problem.NONE, null);
    } catch (IOException failed) {
      publish(Problem.IO_ERROR, null);
      throw new AccountingUnavailableException(Problem.IO_ERROR);
    } finally {
      mutex.unlock();
    }
  }

  private void load() throws IOException {
    pending.clear();
    segments.clear();
    bytes = 0;
    written = acknowledged;
    try (var paths = Files.list(directory)) {
      for (Path path : paths.toList()) {
        String name = path.getFileName().toString();
        if (name.matches("[0-9]{20}\\.seg")) {
          segments.add(path);
        } else if (!List.of("journal.lock", "checkpoint", "checkpoint.tmp").contains(name)) {
          corrupt(path);
        }
      }
    }
    segments.sort(Path::compareTo);
    long previous = -1;
    for (int index = 0; index < segments.size(); index++) {
      Path path = segments.get(index);
      boolean last = index == segments.size() - 1;
      try (FileChannel file = open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        if (file.size() < SEGMENT_HEADER) {
          if (!last) {
            corrupt(path);
          }
          file.truncate(0);
          sync.force(file);
          continue;
        }
        ByteBuffer header = read(file, SEGMENT_HEADER);
        if (header.getInt() != MAGIC
            || header.getInt() != VERSION
            || !new UUID(header.getLong(), header.getLong()).equals(journalId)) {
          corrupt(path);
        }
        long first = header.getLong();
        if (!path.getFileName().toString().equals(segmentName(first))) {
          corrupt(path);
        }
        boolean firstFrame = true;
        while (file.position() < file.size()) {
          long offset = file.position();
          if (file.size() - offset < FRAME_HEADER) {
            if (!last) {
              corrupt(path);
            }
            file.truncate(offset);
            sync.force(file);
            break;
          }
          ByteBuffer prefix = read(file, FRAME_HEADER);
          int length = prefix.getInt();
          long sequence = prefix.getLong();
          int expectedCrc = prefix.getInt();
          if (length < 1
              || length > MAX_EVENT_BYTES
              || sequence < 1
              || (firstFrame && sequence != first)
              || (previous >= 0 && sequence != previous + 1)
              || (sequence > acknowledged && sequence != written + 1)) {
            corrupt(path);
          }
          if (file.size() - file.position() < length) {
            if (!last) {
              corrupt(path);
            }
            file.truncate(offset);
            sync.force(file);
            break;
          }
          byte[] payload = read(file, length).array();
          if (checksum(length, sequence, payload) != expectedCrc) {
            corrupt(path);
          }
          AccountingEvent event = decode(payload, path);
          previous = sequence;
          firstFrame = false;
          if (sequence > acknowledged) {
            pending.addLast(new Frame(sequence, path, offset, FRAME_HEADER + length, event.at()));
            written = sequence;
          }
        }
        bytes += file.size();
      }
    }
    // A partial last segment header contains no complete event; remove it, not a valid frame.
    if (!segments.isEmpty()) {
      Path last = segments.getLast();
      if (Files.size(last) == 0) {
        Files.delete(last);
        segments.removeLast();
        forceDirectory();
      }
    }
    if (!segments.isEmpty()) {
      activePath = segments.getLast();
      active = open(activePath, StandardOpenOption.READ, StandardOpenOption.WRITE);
      active.position(active.size());
    }
  }

  private Entry readFrame(FileChannel file, long expectedSequence, Path path) throws IOException {
    ByteBuffer header = read(file, FRAME_HEADER);
    int length = header.getInt();
    long sequence = header.getLong();
    int crc = header.getInt();
    if (length < 1 || length > MAX_EVENT_BYTES || sequence != expectedSequence) {
      corrupt(path);
    }
    byte[] payload = read(file, length).array();
    if (checksum(length, sequence, payload) != crc) {
      corrupt(path);
    }
    return new Entry(sequence, decode(payload, path));
  }

  private AccountingEvent decode(byte[] bytes, Path path) {
    try {
      return mapper.readValue(bytes, AccountingEvent.class);
    } catch (IOException | RuntimeException invalid) {
      corrupt(path);
      throw new AssertionError("unreachable");
    }
  }

  private void rotate(long first) throws IOException {
    closeActive();
    activePath = directory.resolve(segmentName(first));
    active =
        open(
            activePath,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE);
    ByteBuffer header =
        ByteBuffer.allocate(SEGMENT_HEADER)
            .putInt(MAGIC)
            .putInt(VERSION)
            .putLong(journalId.getMostSignificantBits())
            .putLong(journalId.getLeastSignificantBits())
            .putLong(first);
    header.flip();
    write(active, header);
    sync.force(active);
    forceDirectory();
    segments.add(activePath);
    bytes += SEGMENT_HEADER;
  }

  private void reclaim() throws IOException {
    Path firstPending = pending.isEmpty() ? null : pending.getFirst().path();
    boolean removed = false;
    for (Path path : List.copyOf(segments)) {
      if (firstPending != null && path.compareTo(firstPending) >= 0) {
        break;
      }
      if (path.equals(activePath)) {
        closeActive();
      }
      long size = Files.size(path);
      Files.delete(path);
      bytes -= size;
      segments.remove(path);
      removed = true;
    }
    if (removed) {
      forceDirectory();
    }
  }

  private void readCheckpoint() throws IOException {
    try (FileChannel file = open(directory.resolve("checkpoint"), StandardOpenOption.READ)) {
      if (file.size() != CHECKPOINT_BYTES) {
        corrupt(directory.resolve("checkpoint"));
      }
      byte[] bytes = read(file, CHECKPOINT_BYTES).array();
      ByteBuffer data = ByteBuffer.wrap(bytes);
      if (data.getInt() != MAGIC || data.getInt() != VERSION) {
        corrupt(directory.resolve("checkpoint"));
      }
      UUID id = new UUID(data.getLong(), data.getLong());
      long sequence = data.getLong();
      CRC32C crc = new CRC32C();
      crc.update(bytes, 0, CHECKPOINT_BYTES - 4);
      if ((int) crc.getValue() != data.getInt()
          || sequence < 0
          || (journalId != null && !journalId.equals(id))) {
        corrupt(directory.resolve("checkpoint"));
      }
      journalId = id;
      acknowledged = sequence;
    }
  }

  private void checkpoint(long sequence) throws IOException {
    ByteBuffer data =
        ByteBuffer.allocate(CHECKPOINT_BYTES)
            .putInt(MAGIC)
            .putInt(VERSION)
            .putLong(journalId.getMostSignificantBits())
            .putLong(journalId.getLeastSignificantBits())
            .putLong(sequence);
    CRC32C crc = new CRC32C();
    crc.update(data.array(), 0, CHECKPOINT_BYTES - 4);
    data.putInt((int) crc.getValue()).flip();
    Path temp = directory.resolve("checkpoint.tmp");
    try (FileChannel file =
        open(
            temp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      write(file, data);
      sync.force(file);
    }
    Files.move(
        temp,
        directory.resolve("checkpoint"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
    forceDirectory();
  }

  private FileChannel open(Path path, OpenOption... options) throws IOException {
    var set = new HashSet<OpenOption>(List.of(options));
    set.add(LinkOption.NOFOLLOW_LINKS);
    return Files.getFileStore(directory).supportsFileAttributeView("posix")
        ? FileChannel.open(
            path,
            set,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        : FileChannel.open(path, set);
  }

  private void forceDirectory() throws IOException {
    try (FileChannel file = FileChannel.open(directory, StandardOpenOption.READ)) {
      sync.force(file);
    }
  }

  private static ByteBuffer read(FileChannel file, int length) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length);
    while (buffer.hasRemaining()) {
      if (file.read(buffer) < 0) {
        throw new IOException("incomplete journal frame");
      }
    }
    return buffer.flip();
  }

  private static void write(FileChannel file, ByteBuffer data) throws IOException {
    while (data.hasRemaining()) {
      file.write(data);
    }
  }

  private static int checksum(int length, long sequence, byte[] payload) {
    CRC32C crc = new CRC32C();
    crc.update(ByteBuffer.allocate(12).putInt(length).putLong(sequence).array());
    crc.update(payload);
    return (int) crc.getValue();
  }

  private static String segmentName(long first) {
    return String.format(Locale.ROOT, "%020d.seg", first);
  }

  private static String name(Path path) {
    return path == null ? null : path.getFileName().toString();
  }

  private void corrupt(Path path) {
    publish(Problem.CORRUPT, name(path));
    throw new AccountingUnavailableException(Problem.CORRUPT);
  }

  private void publish(Problem problem, String segment) {
    health =
        new Health(
            pending.size(),
            pending.isEmpty() ? null : pending.getFirst().at(),
            bytes,
            maxBytes,
            acknowledged,
            written,
            problem,
            segment);
  }

  private void lock() {
    try {
      if (!mutex.tryLock(lockTimeoutMillis, TimeUnit.MILLISECONDS)) {
        throw new AccountingUnavailableException(Problem.BUSY);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AccountingUnavailableException(Problem.BUSY);
    }
  }

  private void requireOpen() {
    if (closed) {
      throw new AccountingUnavailableException(Problem.CLOSED);
    }
  }

  private void closeActive() throws IOException {
    if (active != null) {
      active.close();
    }
    active = null;
    activePath = null;
  }

  private void release() {
    try {
      closeActive();
    } catch (IOException ignored) {
      /* Already refusing admission. */
    }
    try {
      if (writerLock != null) {
        writerLock.close();
      }
    } catch (IOException ignored) {
      /* Channel closes next. */
    }
    try {
      if (lockChannel != null) {
        lockChannel.close();
      }
    } catch (IOException ignored) {
      /* No further writes. */
    }
    if (ownedDirectory != null) {
      WRITERS.remove(ownedDirectory);
      ownedDirectory = null;
    }
  }

  @Override
  public void close() {
    mutex.lock();
    try {
      closed = true;
      release();
      publish(Problem.CLOSED, null);
    } finally {
      mutex.unlock();
    }
  }

  interface Sync {
    void force(FileChannel channel) throws IOException;
  }

  private record Frame(long sequence, Path path, long offset, int size, Instant at) {}

  public record Entry(long sequence, AccountingEvent event) {
    public Entry {
      if (sequence < 1) {
        throw new IllegalArgumentException("journal sequences start at one");
      }
      Objects.requireNonNull(event, "event");
    }
  }

  public record Health(
      long pendingEvents,
      Instant oldestPendingAt,
      long bytes,
      long maxBytes,
      long acknowledgedSequence,
      long writtenSequence,
      Problem problem,
      String segment) {}

  public enum Problem {
    NONE,
    FULL,
    IO_ERROR,
    CORRUPT,
    WRITER_LOCKED,
    BUSY,
    INVALID_EVENT,
    CLOSED
  }
}
