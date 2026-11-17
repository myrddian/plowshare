package io.aeyer.plowshare.integrations.ha;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderJournal.CatalogueConfirmed;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderJournal.CatalogueIntent;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderJournal.Delivery;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.RelayTools;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exclusive durable SDK receipts and one catalogue publication intent. Settled, acknowledged reads
 * may be compacted into durable broker-position fences; UNKNOWN and incomplete receipts remain. Its
 * size reserves room for every retained receipt's maximum SDK request/result.
 */
public final class HomeAssistantToolJournal implements HomeAssistantProviderJournal {
  public static final int MAX_RECEIPTS = 32;
  private static final int MAX_BYTES = 16 * 1024 * 1024;
  private static final ObjectMapper CODEC =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(64)
                          .maxStringLength(MAX_CATALOGUE_TEXT)
                          .build())
                  .build())
          .findAndAddModules()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .build();

  private record StoredReceipt(
      String invocationId,
      String request,
      RelayTools.Phase phase,
      RelayTools.State resultState,
      String resultText,
      String occurredAt,
      Delivery delivery,
      boolean acknowledged) {
    StoredReceipt {
      RelayPort.uuid(invocationId);
    }

    private static StoredReceipt of(RelayTools.Receipt receipt, StoredReceipt previous) {
      return new StoredReceipt(
          receipt.invocationId(),
          receipt.request(),
          receipt.phase(),
          receipt.result().map(RelayTools.Result::state).orElse(null),
          receipt.result().map(RelayTools.Result::text).orElse(null),
          receipt.occurredAt().orElse(null),
          previous == null ? null : previous.delivery(),
          previous != null && previous.acknowledged());
    }

    private RelayTools.Receipt value() {
      if ((resultState == null) != (resultText == null))
        throw new IllegalArgumentException("Invalid retained HA tool result");
      var receipt =
          new RelayTools.Receipt(
              invocationId,
              request,
              phase,
              resultState == null
                  ? Optional.empty()
                  : Optional.of(new RelayTools.Result(resultState, resultText)),
              Optional.ofNullable(occurredAt));
      var call = RelayTools.decodeCall(request);
      if (!call.invocationId().equals(invocationId))
        throw new IllegalArgumentException("Invalid retained HA invocation identity");
      if (acknowledged && delivery == null
          || delivery != null
              && (!delivery.invocationId().equals(invocationId)
                  || !delivery
                      .topic()
                      .equals("tool." + call.provider() + "." + call.tool() + ".request")))
        throw new IllegalArgumentException("Invalid retained HA broker delivery");
      return receipt;
    }
  }

  /**
   * A cursor incarnation is mandatory for retirement; positions never transfer to its successor.
   */
  private record ReplayFence(String topic, String generation, long retiredThrough) {
    ReplayFence {
      RelayPort.topic(topic);
      RelayPort.uuid(generation);
      if (retiredThrough < 0) throw new IllegalArgumentException("Invalid HA retired position");
    }
  }

  private record Snapshot(
      int version,
      String identity,
      String owner,
      List<StoredReceipt> receipts,
      List<ReplayFence> fences,
      CatalogueIntent publishing,
      CatalogueConfirmed confirmed) {
    Snapshot {
      if (version != 2
          || identity == null
          || !identity.matches("[0-9a-f]{64}")
          || owner == null
          || !owner.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid HA tool journal binding");
      receipts = List.copyOf(receipts);
      if (receipts.size() > MAX_RECEIPTS
          || receipts.stream().map(StoredReceipt::invocationId).distinct().count()
              != receipts.size())
        throw new IllegalArgumentException("HA tool journal exceeds its receipt bound");
      receipts.forEach(StoredReceipt::value);
      fences = List.copyOf(fences);
      if (fences.size() > 128
          || fences.stream().map(ReplayFence::topic).distinct().count() != fences.size())
        throw new IllegalArgumentException("Invalid HA replay fences");
      for (var receipt : receipts)
        if (receipt.delivery() != null && receipt.delivery().generation() != null)
          if (fences.stream()
              .noneMatch(
                  fence ->
                      fence.topic().equals(receipt.delivery().topic())
                          && fence.generation().equals(receipt.delivery().generation())))
            throw new IllegalArgumentException("Missing HA delivery generation fence");
    }
  }

  private final Path directory;
  private final Path file;
  private final FileChannel ownership;
  private final FileLock lock;
  private Snapshot snapshot;
  private boolean closed;
  private boolean uncertain;

  /**
   * Opens a private local journal with a nonblocking exclusive lock. The owner fingerprint binds
   * server/HA credential configuration separately from the SDK's exact declaration identity.
   * Rotation/reconfiguration must not silently adopt an old journal under another owner.
   */
  public HomeAssistantToolJournal(Path requested, String identity, String owner)
      throws IOException {
    Objects.requireNonNull(requested);
    if (Files.isSymbolicLink(requested)) throw new IOException("HA tool journal symlink refused");
    Files.createDirectories(requested);
    directory = requested.toRealPath();
    privatePermissions(directory, "rwx------");
    file = directory.resolve("tools.json");
    Path ownerFile = directory.resolve("owner.lock");
    if (Files.isSymbolicLink(file) || Files.isSymbolicLink(ownerFile))
      throw new IOException("HA tool journal symlink refused");
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
      throw new IOException("HA tool journal is already owned");
    } catch (IOException | RuntimeException failed) {
      try {
        ownership.close();
      } catch (IOException cleanup) {
        failed.addSuppressed(cleanup);
      }
      throw failed;
    }
    if (acquired == null) {
      ownership.close();
      throw new IOException("HA tool journal is already owned");
    }
    lock = acquired;
    try {
      if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        byte[] content;
        try (InputStream input =
            Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
          content = input.readNBytes(MAX_BYTES + 1);
        }
        if (content.length > MAX_BYTES)
          throw new IOException("HA tool journal exceeds its byte bound");
        try {
          snapshot = CODEC.readValue(content, Snapshot.class);
        } catch (JsonProcessingException malformed) {
          throw new IOException("HA tool journal is invalid");
        }
        if (!snapshot.identity().equals(identity) || !snapshot.owner().equals(owner))
          throw new IOException("HA tool journal belongs to another provider configuration");
      } else {
        snapshot = new Snapshot(2, identity, owner, List.of(), List.of(), null, null);
        persist(snapshot);
      }
    } catch (IOException | RuntimeException failed) {
      lock.release();
      ownership.close();
      throw failed;
    }
  }

  @Override
  public String identity() {
    return snapshot.identity();
  }

  @Override
  public synchronized List<RelayTools.Receipt> all() throws IOException {
    requireOpen();
    return snapshot.receipts().stream().map(StoredReceipt::value).toList();
  }

  @Override
  public synchronized void save(RelayTools.Receipt receipt) throws IOException {
    requireOpen();
    Objects.requireNonNull(receipt);
    List<StoredReceipt> receipts = new ArrayList<>(snapshot.receipts());
    int index = -1;
    for (int i = 0; i < receipts.size(); i++)
      if (receipts.get(i).invocationId().equals(receipt.invocationId())) index = i;
    if (index < 0) {
      if (receipt.phase() != RelayTools.Phase.EXECUTING || receipts.size() >= MAX_RECEIPTS)
        throw new IOException("HA tool receipt capacity or initial phase refused");
      receipts.add(StoredReceipt.of(receipt, null));
    } else {
      RelayTools.Receipt previous = receipts.get(index).value();
      if (!previous.request().equals(receipt.request()) || !transition(previous, receipt))
        throw new IOException("HA tool receipt identity or transition changed");
      receipts.set(index, StoredReceipt.of(receipt, receipts.get(index)));
    }
    installRetiring(receipts, snapshot.fences());
  }

  @Override
  public synchronized void checkDelivery(Delivery delivery) throws IOException {
    requireOpen();
    Objects.requireNonNull(delivery);
    var fence =
        snapshot.fences().stream()
            .filter(value -> value.topic().equals(delivery.topic()))
            .findFirst();
    if (fence.isEmpty()) return;
    var selected = fence.orElseThrow();
    if (!selected.generation().equals(delivery.generation()))
      throw new IOException(
          "HA request cursor generation changed or is unavailable; retain its journal");
    if (Long.parseLong(delivery.position()) <= selected.retiredThrough()
        && snapshot.receipts().stream().noneMatch(value -> delivery.equals(value.delivery())))
      throw new IOException("HA request position was retired; no handler may execute it again");
  }

  @Override
  public synchronized void captureDelivery(Delivery delivery) throws IOException {
    recordDelivery(delivery, false);
  }

  @Override
  public synchronized void acknowledged(Delivery delivery) throws IOException {
    recordDelivery(delivery, true);
  }

  private void recordDelivery(Delivery delivery, boolean acknowledged) throws IOException {
    checkDelivery(delivery);
    List<StoredReceipt> receipts = new ArrayList<>(snapshot.receipts());
    int selected = -1;
    for (int i = 0; i < receipts.size(); i++)
      if (receipts.get(i).invocationId().equals(delivery.invocationId())) selected = i;
    if (selected < 0) throw new IOException("No retained HA intent for broker delivery evidence");
    StoredReceipt before = receipts.get(selected);
    if (before.delivery() != null && !before.delivery().equals(delivery))
      throw new IOException("HA invocation broker coordinates changed");
    receipts.set(
        selected,
        new StoredReceipt(
            before.invocationId(),
            before.request(),
            before.phase(),
            before.resultState(),
            before.resultText(),
            before.occurredAt(),
            delivery,
            before.acknowledged() || acknowledged));
    List<ReplayFence> fences = new ArrayList<>(snapshot.fences());
    if (delivery.generation() != null
        && fences.stream().noneMatch(value -> value.topic().equals(delivery.topic())))
      fences.add(new ReplayFence(delivery.topic(), delivery.generation(), 0));
    installRetiring(receipts, fences);
  }

  private void installRetiring(List<StoredReceipt> receipts, List<ReplayFence> existing)
      throws IOException {
    List<StoredReceipt> retained = new ArrayList<>();
    List<ReplayFence> fences = new ArrayList<>(existing);
    for (var receipt : receipts) {
      // DONE confirms publication, not necessarily ACK or a known external outcome. Both
      // independent
      // proofs are required. UNKNOWN/incomplete/legacy-generation evidence never retires by age.
      if (receipt.phase() != RelayTools.Phase.DONE
          || !receipt.acknowledged()
          || receipt.resultState() == RelayTools.State.UNKNOWN
          || receipt.delivery().generation() == null) {
        retained.add(receipt);
        continue;
      }
      var delivery = receipt.delivery();
      int index = -1;
      for (int i = 0; i < fences.size(); i++)
        if (fences.get(i).topic().equals(delivery.topic())) index = i;
      if (index < 0 || !fences.get(index).generation().equals(delivery.generation()))
        throw new IOException("HA settled delivery has no matching cursor fence");
      fences.set(
          index,
          new ReplayFence(
              delivery.topic(),
              delivery.generation(),
              Math.max(fences.get(index).retiredThrough(), Long.parseLong(delivery.position()))));
    }
    // Receipt removal and its replay watermark commit in the same atomic snapshot. A failed fsync
    // latches uncertainty; restart sees either the full receipt or the durable replacement fence.
    install(
        new Snapshot(
            2,
            snapshot.identity(),
            snapshot.owner(),
            retained,
            fences,
            snapshot.publishing(),
            snapshot.confirmed()));
  }

  public synchronized Optional<CatalogueIntent> publishing() throws IOException {
    requireOpen();
    return Optional.ofNullable(snapshot.publishing());
  }

  public synchronized Optional<CatalogueConfirmed> confirmed() throws IOException {
    requireOpen();
    return Optional.ofNullable(snapshot.confirmed());
  }

  public synchronized void beginCatalogue(RelayPort.Publish publication, boolean withdrawal)
      throws IOException {
    requireOpen();
    if (snapshot.publishing() != null)
      throw new IOException("HA catalogue publication requires read-only reconciliation");
    if (snapshot.confirmed() != null
        && snapshot.confirmed().receipt().requestId().equals(publication.requestId()))
      throw new IOException("HA catalogue publication identity was already used");
    install(
        new Snapshot(
            2,
            snapshot.identity(),
            snapshot.owner(),
            snapshot.receipts(),
            snapshot.fences(),
            new CatalogueIntent(publication, withdrawal),
            snapshot.confirmed()));
  }

  public synchronized void confirmCatalogue(RelayPort.Published receipt) throws IOException {
    requireOpen();
    CatalogueIntent intent =
        Objects.requireNonNull(snapshot.publishing(), "No HA catalogue intent");
    if (!intent.request().requestId().equals(receipt.requestId())
        || !intent.request().project().equals(receipt.project())
        || !intent.request().topic().equals(receipt.topic()))
      throw new IOException("Foreign HA catalogue receipt");
    install(
        new Snapshot(
            2,
            snapshot.identity(),
            snapshot.owner(),
            snapshot.receipts(),
            snapshot.fences(),
            null,
            new CatalogueConfirmed(receipt, intent.withdrawal())));
  }

  /** Only the SDK's proven NOT_SUBMITTED disposition permits removing an unsent intent. */
  public synchronized void notSubmittedCatalogue(String requestId) throws IOException {
    requireOpen();
    if (snapshot.publishing() == null
        || !snapshot.publishing().request().requestId().equals(requestId))
      throw new IOException("Foreign HA catalogue intent");
    install(
        new Snapshot(
            2,
            snapshot.identity(),
            snapshot.owner(),
            snapshot.receipts(),
            snapshot.fences(),
            null,
            snapshot.confirmed()));
  }

  private static boolean transition(RelayTools.Receipt previous, RelayTools.Receipt next) {
    if (previous.equals(next)) return true;
    return switch (previous.phase()) {
      case EXECUTING -> next.phase() == RelayTools.Phase.READY;
      case READY ->
          next.phase() == RelayTools.Phase.PUBLISHING && previous.result().equals(next.result());
      case PUBLISHING ->
          (next.phase() == RelayTools.Phase.DONE || next.phase() == RelayTools.Phase.READY)
              && previous.result().equals(next.result())
              && (next.phase() == RelayTools.Phase.READY
                  || previous.occurredAt().equals(next.occurredAt()));
      case DONE -> false;
    };
  }

  private void install(Snapshot replacement) throws IOException {
    try {
      persist(replacement);
      snapshot = replacement;
    } catch (IOException failed) {
      // A rename/directory-fsync failure may have replaced the file already. Never continue from
      // an older in-memory receipt and accidentally execute work whose intent reached disk.
      uncertain = true;
      throw failed;
    }
  }

  private void persist(Snapshot replacement) throws IOException {
    byte[] bytes = CODEC.writeValueAsBytes(replacement);
    if (bytes.length > MAX_BYTES) throw new IOException("HA tool journal exceeds its byte bound");
    Path pending = Files.createTempFile(directory, "tools-", ".pending");
    try {
      privatePermissions(pending, "rw-------");
      try (FileChannel output =
          FileChannel.open(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) output.write(buffer);
        output.force(true);
      }
      Files.move(
          pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
        parent.force(true);
      }
    } finally {
      Files.deleteIfExists(pending);
    }
  }

  private static void privatePermissions(Path path, String permissions) throws IOException {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    } catch (UnsupportedOperationException unsupported) {
      // The exclusive file lock remains mandatory on filesystems without POSIX permissions.
    }
  }

  private void requireOpen() throws IOException {
    if (closed) throw new IOException("HA tool journal is closed");
    if (uncertain)
      throw new IOException(
          "HA tool journal write is uncertain; reopen retained state before continuing");
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
