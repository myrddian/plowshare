package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Single owner, atomic snapshots and fsync. Unfinished entries are never evicted. */
public final class Journal implements AutoCloseable {
  public static final int MAX_ENTRIES = 1024, MAX_TOMBSTONES = 8192, MAX_BYTES = 8 * 1024 * 1024;
  private final Path directory, file;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private ObjectNode data;
  private boolean closed;
  private final Supplier<Instant> clock;

  public Journal(Path directory) throws IOException {
    this(directory, Instant::now);
  }

  public Journal(Path directory, Supplier<Instant> clock) throws IOException {
    this.clock = Objects.requireNonNull(clock);
    Path requested = directory.toAbsolutePath().normalize();
    if (Files.isSymbolicLink(requested)) throw new IOException("journal directory symlink refused");
    Files.createDirectories(requested);
    // Resolve parent aliases once (macOS /var is /private/var). All subsequent
    // I/O uses this canonical directory, rather than following the alias again.
    this.directory = requested.toRealPath();
    try {
      Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
    }
    file = this.directory.resolve("journal.json");
    if (Files.isSymbolicLink(file) || Files.isSymbolicLink(this.directory.resolve("owner.lock")))
      throw new IOException("journal symlink refused");
    lockChannel =
        FileChannel.open(
            this.directory.resolve("owner.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = lockChannel.tryLock();
    } catch (OverlappingFileLockException busy) {
      lockChannel.close();
      throw new IOException("journal is already owned");
    }
    if (acquired == null) {
      lockChannel.close();
      throw new IOException("journal is already owned");
    }
    lock = acquired;
    try {
      if (Files.exists(file)) {
        if (Files.size(file) > MAX_BYTES) throw new IOException("journal exceeds limit");
        JsonNode parsed = Json.MAPPER.readTree(Files.readString(file));
        if (parsed == null
            || !parsed.isObject()
            || !parsed.path("records").isObject()
            || !parsed.path("state").isObject()
            || parsed.path("version").asInt() != 1) throw new IOException("journal is invalid");
        data = (ObjectNode) parsed;
      } else {
        data = Json.object();
        data.put("version", 1);
        data.set("records", Json.object());
        data.set("state", Json.object());
      }
      if (!data.has("tombstones")) data.set("tombstones", Json.object());
      if (!data.has("causes")) data.set("causes", Json.object());
      if (!data.path("causes").isObject() || data.path("causes").size() > Feedback.MAX_TOTAL)
        throw new IOException("journal causal index is invalid or exceeds bounds");
      Map<String, Integer> causeCounts = new HashMap<>();
      for (var entries = data.path("causes").fields(); entries.hasNext(); ) {
        var entry = entries.next();
        JsonNode cause = entry.getValue();
        Json.fields(
            cause,
            "binding",
            "fingerprint",
            "context",
            "operation",
            "expiresAt",
            "ambiguous",
            "depth",
            "parent");
        String binding = Json.text(cause, "binding"),
            context = Feedback.context(cause.path("context"));
        Json.text(cause, "fingerprint");
        Json.text(cause, "operation");
        if (context == null
            || !entry.getKey().equals(Feedback.key(binding, context))
            || !cause.path("expiresAt").isIntegralNumber()
            || !cause.path("expiresAt").canConvertToLong()
            || cause.has("depth")
                && (!cause.path("depth").isIntegralNumber()
                    || !cause.path("depth").canConvertToInt()
                    || cause.path("depth").asInt() < 0
                    || cause.path("depth").asInt() > Feedback.MAX_DEPTH)
            || (cause.path("depth").asInt(0) > 0) != cause.has("parent")
            || cause.has("parent")
                && (Feedback.context(cause.path("parent")) == null
                    || context.equals(cause.path("parent").asText()))
            || cause.has("ambiguous") && !cause.path("ambiguous").isBoolean()
            || causeCounts.merge(binding, 1, Integer::sum) > Feedback.MAX_PER_BINDING)
          throw new IOException("journal causal index is invalid or exceeds bounds");
      }
      if (!data.path("tombstones").isObject()
          || data.path("tombstones").size() > MAX_TOMBSTONES
          || data.path("records").size() > MAX_ENTRIES)
        throw new IOException("journal index is invalid or exceeds bounds");
    } catch (IOException | RuntimeException failure) {
      lock.release();
      lockChannel.close();
      throw failure;
    }
  }

  public synchronized JsonNode record(String id) {
    return data.path("records").path(id).deepCopy();
  }

  public synchronized List<Map.Entry<String, JsonNode>> records() {
    List<Map.Entry<String, JsonNode>> result = new ArrayList<>();
    data.path("records")
        .fields()
        .forEachRemaining(e -> result.add(Map.entry(e.getKey(), e.getValue().deepCopy())));
    return result;
  }

  public synchronized JsonNode state(String binding) {
    JsonNode n = data.path("state").path(binding);
    return n.isMissingNode() ? Json.object() : n.deepCopy();
  }

  /** Bind recovery to the same server/credential without storing the credential. */
  public synchronized void bindOwner(String fingerprint) throws IOException {
    ensureOpen();
    if (data.has("ownerFingerprint")) {
      if (!data.path("ownerFingerprint").asText().equals(fingerprint))
        throw new IOException(
            "journal belongs to another connection credential; inspect before changing"
                + " ownership");
      return;
    }
    ObjectNode previous = data;
    data = data.deepCopy();
    data.put("ownerFingerprint", fingerprint);
    try {
      commit(Map.of(), null, null);
    } catch (IOException | RuntimeException failed) {
      data = previous;
      throw failed;
    }
  }

  public synchronized void write(String id, JsonNode record) throws IOException {
    commit(Map.of(id, record), null, null);
  }

  public synchronized void commit(Map<String, JsonNode> records, String binding, JsonNode state)
      throws IOException {
    ensureOpen();
    ObjectNode next = data.deepCopy(), all = (ObjectNode) next.path("records");
    for (var entry : records.entrySet()) {
      if (data.path("tombstones").has(entry.getKey()))
        throw new IOException("retained duplicate identity cannot be replaced");
      all.set(entry.getKey(), stamp(entry.getValue(), data.path("records").path(entry.getKey())));
    }
    if (binding != null) {
      if (state == null
          || !state.isObject()
          || Json.MAPPER.writeValueAsBytes(state).length > 64 * 1024)
        throw new IOException("script state exceeds limit");
      ((ObjectNode) next.path("state")).set(binding, state.deepCopy());
    }
    persist(next);
  }

  private ObjectNode stamp(JsonNode record, JsonNode prior) {
    if (!record.isObject()) throw new IllegalArgumentException("journal record must be an object");
    ObjectNode copy = (ObjectNode) record.deepCopy();
    long now = clock.get().toEpochMilli();
    if (!copy.has("createdAt")) copy.put("createdAt", prior.path("createdAt").asLong(now));
    if (settled(copy)) {
      copy.put("settledAt", settled(prior) ? prior.path("settledAt").asLong(now) : now);
    } else copy.remove("settledAt");
    return copy;
  }

  private void persist(ObjectNode next) throws IOException {
    ensureOpen();
    if (next.path("records").size() > MAX_ENTRIES)
      throw new IOException("journal is full; retain or prune completed entries explicitly");
    if (next.path("tombstones").size() > MAX_TOMBSTONES)
      throw new IOException(
          "duplicate identity index is full; retain the configured recovery window");
    byte[] bytes = Json.MAPPER.writeValueAsBytes(next);
    if (bytes.length > MAX_BYTES) throw new IOException("journal exceeds limit");
    Path temporary = Files.createTempFile(directory, "journal-", ".tmp");
    try {
      try {
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
      }
      try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) out.write(buffer);
        out.force(true);
      }
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) {
        dir.force(true);
      }
      data = next;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /**
   * Explicit maintenance; only settled entries may be removed. Keep observed runs until delivery
   * settles.
   */
  public synchronized void prune(Set<String> ids) throws IOException {
    ObjectNode next = data.deepCopy();
    ObjectNode records = (ObjectNode) next.path("records");
    Set<String> protectedIds = protectedRecords(records);
    for (String id : ids) {
      if (!records.has(id) || protectedIds.contains(id))
        throw new IOException("unfinished journal entry or lineage cannot be pruned");
      records.remove(id);
    }
    // Persist through the same atomic path, without publishing a partial mutation.
    ObjectNode previous = data;
    data = next;
    try {
      commit(Map.of(), null, null);
    } catch (IOException | RuntimeException failure) {
      data = previous;
      throw failure;
    }
  }

  public synchronized boolean known(String id) {
    return data.path("records").has(id) || data.path("tombstones").has(id);
  }

  public synchronized int tombstoneCount() {
    return data.path("tombstones").size();
  }

  public synchronized int causeCount() {
    return data.path("causes").size();
  }

  /** Expiration is independent of payload retention; no unexpired context is evicted. */
  public synchronized void expireCauses(long now) throws IOException {
    ensureOpen();
    ObjectNode next = data.deepCopy(), causes = (ObjectNode) next.path("causes");
    List<String> expired = new ArrayList<>();
    causes
        .fields()
        .forEachRemaining(
            e -> {
              if (e.getValue().path("expiresAt").asLong(Long.MAX_VALUE) <= now)
                expired.add(e.getKey());
            });
    for (String key : expired) causes.remove(key);
    if (!expired.isEmpty()) persist(next);
  }

  /** All adapter effects execute serially; check capacity before submitting an action. */
  public synchronized void checkCauseCapacity(Configuration.Binding binding, long now)
      throws IOException {
    if (!binding.feedback().suppressPipelineStarts()) return;
    expireCauses(now);
    int count = 0;
    for (var entries = data.path("causes").elements(); entries.hasNext(); )
      if (entries.next().path("binding").asText().equals(binding.name())) count++;
    if (count >= Feedback.MAX_PER_BINDING || causeCount() >= Feedback.MAX_TOTAL)
      throw new IllegalArgumentException("causal context index is full; action was not submitted");
  }

  /** Keep the action result and its acknowledged context in one durable transaction. */
  public synchronized void completeAction(
      String id,
      ObjectNode record,
      Configuration.Binding binding,
      IntegrationAdapter.Result result,
      long now)
      throws IOException {
    ensureOpen();
    String context = Feedback.context(result.data().path("context").path("id"));
    if (!binding.feedback().suppressPipelineStarts()
        || !result.state().equals("COMPLETED")
        || context == null) {
      write(id, record);
      return;
    }
    expireCauses(now);
    ObjectNode next = data.deepCopy(), causes = (ObjectNode) next.path("causes");
    String key = Feedback.key(binding.name(), context);
    JsonNode existing = causes.path(key);
    if (existing.isMissingNode()) checkCauseCapacity(binding, now);
    // Capacity checking may expire entries; copy the current index before inserting.
    next.set("causes", data.path("causes").deepCopy());
    causes = (ObjectNode) next.path("causes");
    if (!existing.isMissingNode()) {
      if (!existing.path("operation").asText().equals(id)) {
        ObjectNode ambiguous = (ObjectNode) existing.deepCopy();
        ambiguous.put("ambiguous", true);
        causes.set(key, ambiguous);
        record.put(
            "causalDiagnostic", "acknowledged context identity reused; correlation disabled");
      }
    } else
      causes.set(
          key,
          Json.object()
              .put("binding", binding.name())
              .put("fingerprint", binding.fingerprint())
              .put("context", context)
              .put("operation", id)
              .put("expiresAt", now + binding.feedback().windowSeconds()));
    if (data.path("tombstones").has(id))
      throw new IOException("retained action identity cannot be replaced");
    ((ObjectNode) next.path("records")).set(id, stamp(record, data.path("records").path(id)));
    persist(next);
  }

  /** Match only observed, bounded lineage within the same binding/configuration. */
  public synchronized JsonNode cause(Configuration.Binding binding, JsonNode event, long now) {
    return Feedback.correlate(data.path("causes"), binding, event, now).match();
  }

  /** Atomic intake: duplicate checks, optional coalescing and queue admission. */
  public synchronized void enqueue(
      String id,
      ObjectNode record,
      Configuration.QueuePolicy policy,
      Configuration.Retention retention)
      throws IOException {
    ensureOpen();
    JsonNode prior = data.path("records").path(id), tombstone = data.path("tombstones").path(id);
    if (!prior.isMissingNode() || !tombstone.isMissingNode()) {
      String hash = Json.hash(record.path("event"));
      if (!prior.isMissingNode() && !prior.path("event").equals(record.path("event"))
          || !tombstone.isMissingNode() && !hash.equals(tombstone.path("eventHash").asText()))
        throw new IOException("observation identity changed payload");
      return;
    }
    ObjectNode next = data.deepCopy(), records = (ObjectNode) next.path("records");
    Map.Entry<String, JsonNode> last = null;
    int pending = 0;
    for (var iterator = records.fields(); iterator.hasNext(); ) {
      var entry = iterator.next();
      JsonNode value = entry.getValue();
      if (value.path("kind").asText().equals("handler")
          && value.path("binding").equals(record.path("binding"))) {
        last = entry;
        if (value.path("handler").asText().equals("onEvent")
            && !value.has("invocation")
            && value.path("status").asText().equals("QUEUED")) pending++;
      }
    }
    if (policy.coalesce().equals("same-state")
        && retention.enabled()
        && last != null
        && last.getValue().path("status").asText().equals("QUEUED")
        && !last.getValue().has("context")
        && last.getValue().path("fingerprint").equals(record.path("fingerprint"))
        && equivalent(last.getValue().path("event"), record.path("event"))) {
      JsonNode old = last.getValue();
      record = record.deepCopy();
      record.put("coalescedCount", old.path("coalescedCount").asLong() + 1);
      record.put(
          "coalescedSince",
          old.path("coalescedSince")
              .asLong(old.path("createdAt").asLong(clock.get().toEpochMilli())));
      remember(next, last.getKey(), old, retention.dedupeSeconds());
      records.remove(last.getKey());
      pending--;
    }
    if (pending >= policy.maxPendingEvents())
      throw new IOException(
          "selected binding event queue is full; intake stopped without dropping" + " transitions");
    records.set(id, stamp(record, Json.MAPPER.missingNode()));
    persist(next);
  }

  private static boolean equivalent(JsonNode previous, JsonNode latest) {
    if (!previous.path("type").asText().equals("state_changed")
        || !latest.path("type").asText().equals("state_changed")
        || !previous.path("alias").isTextual()
        || !previous.path("epoch").isTextual()
        || !previous.path("availability").asText().equals("available")
        || !latest.path("availability").asText().equals("available")
        || previous.path("resync").asBoolean()
        || latest.path("resync").asBoolean()) return false;
    ObjectNode a = (ObjectNode) previous.deepCopy(), b = (ObjectNode) latest.deepCopy();
    for (String field : List.of("last_updated", "last_changed", "observed_at")) {
      a.remove(field);
      b.remove(field);
    }
    return a.equals(b);
  }

  /** Freeze a candidate only if intake has not superseded it. No adapter locks held here. */
  public synchronized JsonNode capture(String id, ObjectNode candidate) throws IOException {
    return capture(id, candidate, false);
  }

  public synchronized JsonNode capture(String id, ObjectNode candidate, boolean afterAdapterEvents)
      throws IOException {
    return capture(id, candidate, afterAdapterEvents, null, null);
  }

  /** Freeze the classification and any learned context link in the same transaction. */
  public synchronized JsonNode capture(
      String id,
      ObjectNode candidate,
      boolean afterAdapterEvents,
      Configuration.Binding binding,
      JsonNode causalEvent)
      throws IOException {
    JsonNode current = data.path("records").path(id);
    if (current.isMissingNode() || !current.path("status").asText().equals("QUEUED")) return null;
    if (afterAdapterEvents && hasQueuedAdapterEvents(candidate.path("binding").asText()))
      return null;
    if (current.has("context")) return current.deepCopy();
    if (!current.path("event").equals(candidate.path("event")))
      throw new IOException("handler input changed before capture");
    if (binding == null) commit(Map.of(id, candidate), null, null);
    else {
      ObjectNode next = data.deepCopy(), causes = (ObjectNode) next.path("causes");
      var correlation =
          Feedback.correlate(causes, binding, causalEvent, candidate.path("evaluatedAt").asLong());
      candidate.set(
          "causality",
          correlation.match().isMissingNode() ? Json.MAPPER.nullNode() : correlation.match());
      if (correlation.ambiguousKey() != null) {
        ((ObjectNode) causes.path(correlation.ambiguousKey())).put("ambiguous", true);
        candidate.put(
            "causalDiagnostic", "observed context lineage conflicts; correlation disabled");
      }
      if (correlation.descendant() != null) {
        int count = 0;
        for (var entries = causes.elements(); entries.hasNext(); )
          if (entries.next().path("binding").asText().equals(binding.name())) count++;
        if (count >= Feedback.MAX_PER_BINDING || causes.size() >= Feedback.MAX_TOTAL)
          throw new IOException("causal context index is full; observation remains queued");
        causes.set(
            Feedback.key(binding.name(), correlation.descendant().path("context").asText()),
            correlation.descendant());
      }
      ((ObjectNode) next.path("records")).set(id, stamp(candidate, current));
      persist(next);
    }
    return record(id);
  }

  /** Admission and hold consumption share a transaction; source events get priority. */
  public synchronized boolean queueTimer(
      String id, ObjectNode timer, String binding, JsonNode expectedState, JsonNode nextState)
      throws IOException {
    ensureOpen();
    if (hasQueuedAdapterEvents(binding)) return false;
    if (!state(binding).equals(expectedState))
      throw new IOException("timer state changed before admission");
    if (known(id)) throw new IOException("timer identity cannot be replaced");
    commit(Map.of(id, timer), binding, nextState);
    return true;
  }

  private boolean hasQueuedAdapterEvents(String binding) {
    for (var iterator = data.path("records").elements(); iterator.hasNext(); ) {
      var record = iterator.next();
      if (record.path("kind").asText().equals("handler")
          && record.path("binding").asText().equals(binding)
          && record.path("handler").asText().equals("onEvent")
          && record.path("status").asText().equals("QUEUED")
          && !record.has("invocation")) return true;
    }
    return false;
  }

  /** Compact only fully settled lineage components; preserve duplicate IDs for a bounded window. */
  public synchronized void maintain(Configuration.Retention policy) throws IOException {
    ensureOpen();
    if (!policy.enabled()) return;
    ObjectNode next = data.deepCopy(),
        records = (ObjectNode) next.path("records"),
        tombstones = (ObjectNode) next.path("tombstones");
    long now = clock.get().toEpochMilli();
    boolean changed = false;
    List<String> expired = new ArrayList<>();
    tombstones
        .fields()
        .forEachRemaining(
            e -> {
              if (e.getValue().path("expiresAt").asLong(Long.MAX_VALUE) <= now)
                expired.add(e.getKey());
            });
    for (String id : expired) {
      tombstones.remove(id);
      changed = true;
    }
    // Old journals have no lifecycle timestamps. Establish an age floor on first maintenance.
    for (var iterator = records.fields(); iterator.hasNext(); ) {
      var entry = iterator.next();
      if (settled(entry.getValue()) && !entry.getValue().has("settledAt")) {
        records.set(entry.getKey(), stamp(entry.getValue(), entry.getValue()));
        changed = true;
      }
    }
    Set<String> protectedIds = protectedRecords(records);
    Set<String> recent = new HashSet<>();
    records
        .fields()
        .forEachRemaining(
            e -> {
              if (!e.getValue().has("settledAt")
                  || now - e.getValue().path("settledAt").asLong() < policy.settledSeconds() * 1000)
                recent.add(e.getKey());
            });
    protectedIds.addAll(connected(records, recent));
    List<String> compact = new ArrayList<>();
    records
        .fieldNames()
        .forEachRemaining(
            id -> {
              if (!protectedIds.contains(id)) compact.add(id);
            });
    for (String id : compact) {
      remember(next, id, records.path(id), policy.dedupeSeconds());
      records.remove(id);
      changed = true;
    }
    if (changed) persist(next);
  }

  private void remember(ObjectNode next, String id, JsonNode record, long seconds) {
    ObjectNode tombstone =
        Json.object().put("expiresAt", clock.get().toEpochMilli() + seconds * 1000);
    if (record.has("event")) tombstone.put("eventHash", Json.hash(record.path("event")));
    ((ObjectNode) next.path("tombstones")).set(id, tombstone);
  }

  private static boolean settled(JsonNode record) {
    return Set.of("DONE", "FAILED", "REJECTED").contains(record.path("status").asText())
        && !record.path("state").asText().equals("UNKNOWN");
  }

  private Set<String> protectedRecords(ObjectNode records) {
    Set<String> pending = new HashSet<>();
    // A held interval has not queued its callback yet; retain its originating input.
    for (var bindings = data.path("state").elements(); bindings.hasNext(); ) {
      var state = bindings.next();
      for (var routes = state.path("routes").elements(); routes.hasNext(); ) {
        String edge = routes.next().path("hold").path("id").asText();
        if (records.has(edge)) pending.add(edge);
      }
    }
    records
        .fields()
        .forEachRemaining(
            e -> {
              if (!settled(e.getValue())) pending.add(e.getKey());
            });
    return connected(records, pending);
  }

  private static Set<String> connected(ObjectNode records, Set<String> seeds) {
    Map<String, Set<String>> edges = new HashMap<>();
    records
        .fields()
        .forEachRemaining(
            e -> {
              String parent = e.getValue().path("invocation").asText();
              if (records.has(parent)) {
                edges.computeIfAbsent(e.getKey(), ignored -> new HashSet<>()).add(parent);
                edges.computeIfAbsent(parent, ignored -> new HashSet<>()).add(e.getKey());
              }
            });
    Set<String> result = new HashSet<>(seeds);
    Deque<String> queue = new ArrayDeque<>(seeds);
    while (!queue.isEmpty())
      for (String neighbor : edges.getOrDefault(queue.removeFirst(), Set.of()))
        if (result.add(neighbor)) queue.addLast(neighbor);
    return result;
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    try {
      lock.release();
    } finally {
      lockChannel.close();
    }
  }

  private void ensureOpen() throws IOException {
    if (closed) throw new IOException("journal is closed");
  }
}
