package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.a2a.Ingress;
import io.aeyer.plowshare.a2a.IngressResponseTooLarge;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Direct-indexed lifetime operation receipts and overwriteable bounded task projections. The owner
 * descriptor and POSIX lock are shared with the outbound journal implementation. Receipts grow on
 * disk until explicit binding retirement; they are never silently evicted to permit replay.
 */
public final class FileIngressJournal implements IngressJournal {
  private final Path directory;
  private final FileDispatchJournal owner;
  private boolean failed;
  private Projection recentProjection;
  private ReturnBoundary returnBoundary;
  private boolean returnBoundaryLoaded;

  public FileIngressJournal(Path directory, String fingerprint, String identity)
      throws IOException {
    this.directory = directory;
    owner = new FileDispatchJournal(directory, fingerprint, identity, fingerprint);
  }

  private FileIngressJournal(Path directory, FileDispatchJournal owner) {
    this.directory = directory;
    this.owner = owner;
  }

  public static FileIngressJournal rotate(
      Path directory, String fingerprint, String identity, String previous) throws IOException {
    return new FileIngressJournal(
        directory,
        FileDispatchJournal.rotate(directory, fingerprint, identity, fingerprint, previous));
  }

  public String ownerFingerprint() {
    return owner.ownerFingerprint();
  }

  public synchronized void requireHealthy() {
    ensureOpen();
    if (Files.exists(directory.resolve("ingress-import-pending"), LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException(
          "Historical provenance import is pending; finish the same explicit import before starting the listener");
  }

  @Override
  public synchronized Optional<Imported> imported(String client, UUID id) throws IOException {
    IngressWire.checkClient(client);
    Objects.requireNonNull(id);
    var retained = readKey("ingress-imports", RelayPort.hash(client + "\0" + id), Imported.class);
    if (retained.isPresent()
        && (!retained.orElseThrow().originalRequest().equals(id)
            || !retained.orElseThrow().client().equals(client)))
      throw new IOException("Foreign historical source identity");
    return retained;
  }

  @Override
  public synchronized Optional<Imported> importedExternal(UUID id) throws IOException {
    var retained = read("ingress-import-aliases", id, Imported.class);
    if (retained.isPresent() && !id.equals(retained.orElseThrow().externalRequest()))
      throw new IOException("Foreign historical external message identity");
    return retained;
  }

  @Override
  public synchronized boolean ambiguousClient(String client) throws IOException {
    ensureOpen();
    IngressWire.checkClient(client);
    Path marker = path("ingress-ambiguous-clients", RelayPort.hash(client), false);
    if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return false;
    if (!readFile(marker, String.class).equals(client))
      throw new IOException("Foreign historical ambiguity fence");
    return true;
  }

  @Override
  public synchronized void beginImport(String hash) throws IOException {
    ensureOpen();
    if (hash == null || !hash.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Invalid import fingerprint");
    Path marker = directory.resolve("ingress-import-pending");
    if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
      if (!readFile(marker, String.class).equals(hash))
        throw new IOException("A different historical import remains pending");
    } else write(marker, IngressCodec.write(hash).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public synchronized Imported validateImport(Imported source, Projection view) throws IOException {
    ensureOpen();
    var task = view.task();
    if (!source.client().equals(view.client())
        || !source.task().toString().equals(task.id())
        || !source.context().toString().equals(task.context())
        || !source.agent().equals(task.agent())
        || !source.message().equals(task.message())
        || !source.createdAt().equals(task.createdAt())
        || !source.originalRequest().toString().equals(task.requestId()))
      throw new IOException("Historical source differs from the authoritative native task");
    var existing = imported(source.client(), source.originalRequest());
    var retained = source;
    if (existing.isPresent()) {
      var old = existing.orElseThrow();
      var same =
          new Imported(
              source.originalRequest(),
              source.externalRequest(),
              source.client(),
              source.task(),
              source.context(),
              source.agent(),
              source.message(),
              source.createdAt(),
              source.source(),
              source.originalContextKnown(),
              old.importedAt());
      if (!old.equals(same))
        throw new IOException("Historical provenance conflicts with retained evidence");
      retained = old;
    }
    if (retained.externalRequest() != null) {
      var alias = importedExternal(retained.externalRequest());
      if (alias.isPresent() && !alias.orElseThrow().equals(retained))
        throw new IOException("Historical external identity is ambiguous");
    }
    return retained;
  }

  @Override
  public synchronized void importSource(Imported source, Projection view) throws IOException {
    ensureOpen();
    if (!Files.exists(directory.resolve("ingress-import-pending"), LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Historical import intent must be retained first");
    var retained = validateImport(source, view);
    var existing = imported(retained.client(), retained.originalRequest());
    byte[] bytes = IngressCodec.write(retained).getBytes(StandardCharsets.UTF_8);
    if (existing.isEmpty())
      write(
          path(
              "ingress-imports",
              RelayPort.hash(retained.client() + "\0" + retained.originalRequest()),
              true),
          bytes);
    if (retained.externalRequest() != null)
      write(path("ingress-import-aliases", retained.externalRequest(), true), bytes);
    if (!retained.originalContextKnown())
      write(
          path("ingress-ambiguous-clients", RelayPort.hash(retained.client()), true),
          IngressCodec.write(retained.client()).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public synchronized void completeImport(String hash) throws IOException {
    ensureOpen();
    Path marker = directory.resolve("ingress-import-pending");
    if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)
        || !readFile(marker, String.class).equals(hash))
      throw new IOException("Historical import completion differs from its pending evidence");
    try {
      Files.delete(marker);
      force(directory);
    } catch (IOException failure) {
      failed = true;
      throw failure;
    }
  }

  @Override
  public synchronized Optional<Submission> submission(UUID request) throws IOException {
    var value = read("ingress-operations", request, Submission.class);
    if (value.isPresent() && !value.orElseThrow().request().equals(request))
      throw new IOException("Foreign retained ingress request");
    return value;
  }

  @Override
  public synchronized Optional<Projection> projection(UUID task) throws IOException {
    var value = read("ingress-tasks", task, Projection.class);
    if (value.isPresent() && !value.orElseThrow().task().id().equals(task.toString()))
      throw new IOException("Foreign retained task projection");
    value.ifPresent(projection -> recentProjection = projection);
    return value;
  }

  @Override
  public synchronized void save(Submission next) throws IOException {
    ensureOpen();
    var previous = submission(next.request());
    if (previous.isPresent()) {
      var old = previous.orElseThrow();
      if (!old.hash().equals(next.hash())
          || !old.client().equals(next.client())
          || old.action() != next.action()
          || !old.event().equals(next.event())
          || !old.wire().equals(next.wire())
          || !old.occurredAt().equals(next.occurredAt())
          || !Objects.equals(old.originalRequest(), next.originalRequest())
          || !Objects.equals(old.context(), next.context())
          || !Objects.equals(old.source(), next.source()))
        throw new IOException("Conflicting retained ingress intent");
      boolean valid =
          old.equals(next)
              || old.phase() == Phase.SETTLED
                  && next.phase() == Phase.SETTLED
                  && Objects.equals(old.task(), next.task())
                  && Objects.equals(old.refusal(), next.refusal())
              || old.phase() == Phase.READY && next.phase() == Phase.INTENT
              || old.phase() == Phase.INTENT
                  && (next.phase() == Phase.READY
                      || next.phase() == Phase.SUBMITTED
                      || next.phase() == Phase.SETTLED)
              || old.phase() == Phase.SUBMITTED && next.phase() == Phase.SETTLED;
      if (!valid) throw new IOException("Invalid ingress evidence transition");
    } else if (next.phase() != Phase.READY)
      throw new IOException("Ingress intent must precede publication");
    write(
        path("ingress-operations", next.request(), true),
        IngressCodec.write(next).getBytes(StandardCharsets.UTF_8));
  }

  private record ReplyEvidence(UUID task, String client, long ordinal, Assembly value) {}

  @Override
  public synchronized void project(String client, long revision, IngressWire.Task page)
      throws IOException {
    ensureOpen();
    UUID id = UUID.fromString(page.id());
    var retained = projection(id);
    var header =
        new IngressWire.Task(
            page.id(),
            page.context(),
            page.requestId(),
            page.agent(),
            page.message(),
            page.state(),
            page.ending(),
            List.of(),
            page.createdAt());
    var replies = new ArrayList<String>();
    var incomplete = new LinkedHashSet<String>();
    long through = retained.map(Projection::repliesThrough).orElse(0L);
    if (retained.isPresent()) {
      var previous = retained.orElseThrow();
      if (!previous.client().equals(client)
          || !previous.task().context().equals(header.context())
          || !previous.task().requestId().equals(header.requestId())
          || !previous.task().agent().equals(header.agent())
          || !previous.task().message().equals(header.message())
          || !previous.task().createdAt().equals(header.createdAt()))
        throw new IOException("Task projection ownership changed");
      if (revision == previous.revision() && !header.equals(previous.task()))
        throw new IOException("Conflicting equal task revision");
      replies.addAll(previous.replies());
      incomplete.addAll(previous.incomplete());
      if (revision < previous.revision()) {
        revision = previous.revision();
        header = previous.task();
      }
    }
    for (var chunk : page.replies()) {
      var previous = replyEvidence(id, client, chunk.id());
      var assembled = append(previous.map(ReplyEvidence::value).orElse(null), chunk);
      long ordinal = previous.map(ReplyEvidence::ordinal).orElse(Math.addExact(through, 1));
      if (previous.isEmpty() || !previous.orElseThrow().value().equals(assembled))
        writeReply(
            replyPath(id, chunk.id(), true), new ReplyEvidence(id, client, ordinal, assembled));
      if (ordinal > through) {
        if (ordinal != through + 1)
          throw new IOException("Missing durable reply sequence evidence");
        replies.add(chunk.id());
        through = ordinal;
      }
      if (assembled.complete()) incomplete.remove(chunk.id());
      else if (replies.contains(chunk.id())) incomplete.add(chunk.id());
      // Bodies remain indexed separately for the whole binding lifetime. The public A2A view
      // supports only the latest200, so its hot metadata window can advance without losing bodies.
      while (replies.size() > 200) {
        if (incomplete.contains(replies.getFirst()))
          throw new IOException("Cannot retire an unread reply chunk from the hot view");
        replies.removeFirst();
      }
    }
    var next =
        new Projection(
            client,
            revision,
            header,
            through,
            List.copyOf(replies),
            List.copyOf(incomplete),
            retained.map(Projection::next).orElse(null),
            retained.map(Projection::resume).orElse(null),
            retained.map(Projection::terminalFollowed).orElse(false));
    write(
        path("ingress-tasks", id, true), IngressCodec.write(next).getBytes(StandardCharsets.UTF_8));
    recentProjection = next;
  }

  @Override
  public synchronized Projection checkpoint(
      Projection view, String cursor, String resume, boolean terminalFollowed) throws IOException {
    ensureOpen();
    if (cursor != null) RelayWork.identity(cursor);
    else RelayWork.identity(resume);
    UUID task = UUID.fromString(view.task().id());
    // The bridge serializes its consumer operation. A different store writer can invalidate this
    // last-result slot; only that concurrency case needs another version read before checkpoint.
    var current =
        recentProjection != null && recentProjection.task().id().equals(view.task().id())
            ? recentProjection
            : projection(task)
                .orElseThrow(() -> new IOException("Missing task checkpoint projection"));
    if (!current.equals(view))
      throw new IOException("Task checkpoint snapshot changed concurrently");
    if (cursor == null && !view.incomplete().isEmpty())
      throw new IOException("Cannot checkpoint an incomplete reply body");
    String follow = cursor == null ? resume : view.resume();
    if (view.terminalFollowed() && !terminalFollowed)
      throw new IOException("Terminal FOLLOW proof cannot regress");
    if (Objects.equals(view.next(), cursor)
        && Objects.equals(view.resume(), follow)
        && view.terminalFollowed() == terminalFollowed) return view;
    var next =
        new Projection(
            view.client(),
            view.revision(),
            view.task(),
            view.repliesThrough(),
            view.replies(),
            view.incomplete(),
            cursor,
            follow,
            terminalFollowed);
    write(
        path("ingress-tasks", task, true),
        IngressCodec.write(next).getBytes(StandardCharsets.UTF_8));
    recentProjection = next;
    return next;
  }

  private record ReturnBoundary(String generation, String through) {
    private ReturnBoundary {
      IngressWire.uuid(generation);
      if (through == null || !through.matches("0|[1-9][0-9]*") || Long.parseLong(through) < 0)
        throw new IllegalArgumentException("Invalid actual return head");
    }
  }

  @Override
  public synchronized void returnBoundary(String generation, String through) throws IOException {
    ensureOpen();
    var next = new ReturnBoundary(generation, through);
    Path file = directory.resolve("ingress-return-boundary");
    // The exclusive journal owner is the only writer. Load this separate topic pin once; the
    // dispatch owner descriptor's generation is reserved for the independent consumer group.
    if (!returnBoundaryLoaded) {
      if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
        returnBoundary = readFile(file, ReturnBoundary.class);
      returnBoundaryLoaded = true;
    }
    if (returnBoundary != null
        && (!returnBoundary.generation().equals(next.generation())
            || Long.parseLong(next.through()) < Long.parseLong(returnBoundary.through())))
      throw new IOException(
          "A2A return topic incarnation or retained head changed; reconcile before intake");
    if (next.equals(returnBoundary)) return;
    write(file, IngressCodec.write(next).getBytes(StandardCharsets.UTF_8));
    returnBoundary = next;
  }

  @Override
  public synchronized List<Ingress.Reply> replies(Projection view, int maximum) throws IOException {
    ensureOpen();
    if (maximum < 1 || maximum > 200)
      throw new IllegalArgumentException("Reply view requires1..200 rows");
    UUID task = UUID.fromString(view.task().id());
    var ids = view.replies();
    var result = new ArrayList<Ingress.Reply>();
    long bytes = 0;
    for (int index = Math.max(0, ids.size() - maximum); index < ids.size(); index++) {
      var value =
          reply(task, view.client(), ids.get(index))
              .orElseThrow(() -> new IOException("Missing immutable reply evidence"));
      bytes += value.body().getBytes(StandardCharsets.UTF_8).length;
      if (bytes > 1024 * 1024) throw new IngressResponseTooLarge();
      result.add(value.reply());
    }
    return List.copyOf(result);
  }

  private Optional<Assembly> reply(UUID task, String client, String id) throws IOException {
    return replyEvidence(task, client, id).map(ReplyEvidence::value);
  }

  private Optional<ReplyEvidence> replyEvidence(UUID task, String client, String id)
      throws IOException {
    Path file = replyPath(task, id, false);
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
    var evidence = readReply(file);
    if (!evidence.task().equals(task)
        || !evidence.client().equals(client)
        || evidence.ordinal() < 1
        || !evidence.value().id().equals(id))
      throw new IOException("Foreign immutable reply evidence");
    return Optional.of(evidence);
  }

  private static final byte[] REPLY_MAGIC =
      "PLOWSHARE-INGRESS-REPLY/1\n".getBytes(StandardCharsets.US_ASCII);
  private static final int MAX_REPLY_HEADER = 65536;

  /** The body is raw UTF8, so JSON escaping cannot multiply its portable50MiB byte ceiling. */
  private record ReplyHeader(
      String schema,
      UUID task,
      String client,
      long ordinal,
      String id,
      boolean complete,
      boolean finalReply,
      boolean generated,
      String ending,
      Instant postedAt,
      int byteLength,
      String sha256) {
    private ReplyHeader {
      if (!"plowshare-ingress-reply/1".equals(schema))
        throw new IllegalArgumentException("Unknown private reply format");
      Objects.requireNonNull(task);
      IngressWire.checkClient(client);
      new Ingress.ReplyMetadata(id, ending, postedAt);
      if (ordinal < 1
          || byteLength < 0
          || byteLength > RelayPort.MAX_TEXT_BYTES
          || sha256 == null
          || !sha256.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid private reply metadata");
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA256 unavailable", impossible);
    }
  }

  private void writeReply(Path file, ReplyEvidence evidence) throws IOException {
    var value = evidence.value();
    byte[] body = value.body().getBytes(StandardCharsets.UTF_8);
    var header =
        new ReplyHeader(
            "plowshare-ingress-reply/1",
            evidence.task(),
            evidence.client(),
            evidence.ordinal(),
            value.id(),
            value.complete(),
            value.finalReply(),
            value.generated(),
            value.ending(),
            value.postedAt(),
            body.length,
            digest(body));
    byte[] metadata = IngressCodec.write(header).getBytes(StandardCharsets.UTF_8);
    if (metadata.length > MAX_REPLY_HEADER)
      throw new IOException("Retained reply metadata exceeds bound");
    // One atomic replacement contains both ownership evidence and bytes. A failed append leaves
    // the previous committed prefix readable; only the later task checkpoint permits view ack.
    write(
        file,
        List.of(
            REPLY_MAGIC, ByteBuffer.allocate(4).putInt(metadata.length).array(), metadata, body));
  }

  private ReplyEvidence readReply(Path file) throws IOException {
    if (Files.isSymbolicLink(file) || Files.size(file) > IngressCodec.MAX_BYTES)
      throw new IOException("Invalid private reply file");
    try (var input =
        new BufferedInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))) {
      input.mark(REPLY_MAGIC.length);
      byte[] magic = input.readNBytes(REPLY_MAGIC.length);
      if (!Arrays.equals(magic, REPLY_MAGIC)) {
        // Pre-container journals used a bounded JSON value. Keep their evidence readable; the
        // next genuine append writes the new format without inventing a publication or receipt.
        input.reset();
        return IngressCodec.retained(
            input.readNBytes(IngressCodec.MAX_BYTES + 1), ReplyEvidence.class);
      }
      byte[] length = input.readNBytes(4);
      if (length.length != 4) throw new IOException("Truncated private reply header");
      int size = ByteBuffer.wrap(length).getInt();
      if (size < 1 || size > MAX_REPLY_HEADER)
        throw new IOException("Invalid private reply header bound");
      byte[] metadata = input.readNBytes(size);
      if (metadata.length != size) throw new IOException("Truncated private reply metadata");
      var header = IngressCodec.replyHeader(metadata, ReplyHeader.class);
      byte[] body = input.readNBytes(header.byteLength() + 1);
      if (body.length != header.byteLength() || !digest(body).equals(header.sha256()))
        throw new IOException("Corrupt retained reply body");
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      return new ReplyEvidence(
          header.task(),
          header.client(),
          header.ordinal(),
          new Assembly(
              header.id(),
              text,
              header.complete(),
              header.finalReply(),
              header.generated(),
              header.ending(),
              header.postedAt()));
    }
  }

  private Path replyPath(UUID task, String id, boolean create) throws IOException {
    return path("ingress-replies", RelayPort.hash(task + "\0" + id), create);
  }

  private static Assembly append(Assembly previous, IngressWire.Chunk chunk) throws IOException {
    long offset = Long.parseLong(chunk.offset());
    if (previous == null) {
      if (offset != 0) throw new IOException("Reply history starts after a missing chunk");
      return new Assembly(
          chunk.id(),
          chunk.body(),
          chunk.complete(),
          chunk.finalReply(),
          chunk.generated(),
          chunk.ending(),
          chunk.postedAt());
    }
    if (previous.finalReply() != chunk.finalReply()
        || previous.generated() != chunk.generated()
        || !Objects.equals(previous.ending(), chunk.ending())
        || !previous.postedAt().equals(chunk.postedAt()))
      throw new IOException("Immutable reply metadata changed");
    int length = previous.body().codePointCount(0, previous.body().length());
    int added = chunk.body().codePointCount(0, chunk.body().length());
    if (offset < length || previous.complete()) {
      if (offset > length
          || offset + added > length
          || !previous
              .body()
              .substring(
                  previous.body().offsetByCodePoints(0, (int) offset),
                  previous.body().offsetByCodePoints(0, (int) offset + added))
              .equals(chunk.body())
          || chunk.complete() && offset + added != length)
        throw new IOException("Conflicting or overlapping reply chunk");
      return previous;
    }
    if (offset != length) throw new IOException("Missing reply chunk");
    return new Assembly(
        previous.id(),
        previous.body() + chunk.body(),
        chunk.complete(),
        previous.finalReply(),
        previous.generated(),
        previous.ending(),
        previous.postedAt());
  }

  @Override
  public synchronized void generation(String generation) throws IOException {
    ensureOpen();
    owner.generation(generation);
  }

  @Override
  public synchronized void observedGap(String expiredThrough) throws IOException {
    ensureOpen();
    RelayPort.decimal(expiredThrough);
    // No projection is returned as current without a fresh authoritative inspect. Persist the gap
    // before advancing past unavailable updates; it is never evidence that a mutation was absent.
    write(directory.resolve("ingress-gap"), expiredThrough.getBytes(StandardCharsets.UTF_8));
  }

  private <T> Optional<T> readKey(String category, String id, Class<T> type) throws IOException {
    ensureOpen();
    Path file = path(category, id, false);
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
    return Optional.of(readFile(file, type));
  }

  private <T> Optional<T> read(String category, UUID id, Class<T> type) throws IOException {
    ensureOpen();
    Path path = path(category, id, false);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
    return Optional.of(readFile(path, type));
  }

  private <T> T readFile(Path path, Class<T> type) throws IOException {
    if (Files.isSymbolicLink(path) || Files.size(path) > IngressCodec.MAX_BYTES)
      throw new IOException("Invalid ingress journal file");
    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      return IngressCodec.retained(input.readNBytes(IngressCodec.MAX_BYTES + 1), type);
    }
  }

  private Path path(String category, UUID id, boolean create) throws IOException {
    return path(category, id.toString(), create);
  }

  private Path path(String category, String id, boolean create) throws IOException {
    Path root = directory.resolve(category), bucket = root.resolve(id.substring(0, 2));
    if (Files.isSymbolicLink(root) || Files.isSymbolicLink(bucket))
      throw new IOException("Ingress journal symlink refused");
    if (create) {
      if (!Files.exists(root)) {
        Files.createDirectory(
            root,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        force(directory);
      }
      if (!Files.exists(bucket)) {
        Files.createDirectory(
            bucket,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        force(root);
      }
    }
    return bucket.resolve(id + ".json");
  }

  private void write(Path destination, byte[] bytes) throws IOException {
    write(destination, List.of(bytes));
  }

  private void write(Path destination, List<byte[]> segments) throws IOException {
    Path temporary =
        Files.createTempFile(
            destination.getParent(),
            "ingress-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    try {
      try (var channel =
          FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
        for (var bytes : segments) {
          var buffer = ByteBuffer.wrap(bytes);
          while (buffer.hasRemaining()) channel.write(buffer);
        }
        channel.force(true);
      }
      Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      force(destination.getParent());
    } catch (IOException failure) {
      failed = true;
      throw failure;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void force(Path directory) throws IOException {
    try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private void ensureOpen() {
    if (failed)
      throw new IllegalStateException("Ingress journal failed; operator inspection required");
    owner.ownerFingerprint();
  }

  @Override
  public synchronized void close() throws IOException {
    owner.close();
  }
}
