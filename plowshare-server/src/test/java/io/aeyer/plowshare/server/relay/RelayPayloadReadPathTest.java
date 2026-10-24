package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.transport.SegmentedMessages;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Every reader now takes its payload bytes from {@code relay_payloads} through {@code
 * payload_digest}, in the same query that reads the row. Two things are checked: that a page of
 * records costs one query rather than one per record, and that the byte budget which decides page
 * size and the projection bypass is the one the dropped column used to compute.
 *
 * <p>These are mocked reads, so the SQL is never parsed. The assertions on captured query text are
 * deliberately weak guards against the one failure mocks structurally cannot see -- a query naming
 * a column the schema no longer has. Actual PostgreSQL behaviour is the full-db tier's concern.
 */
class RelayPayloadReadPathTest {
  private static final Instant T0 = Instant.parse("2026-10-25T10:00:00Z");
  private static final Relay.TopicKey TOPIC = new Relay.TopicKey(7, "events");
  private static final int BUDGET = SegmentedMessages.MAX_MESSAGE_BYTES - 1024 * 1024;

  private final List<Query> queries = new ArrayList<>();
  private JdbcTemplate jdbc;

  /** A store that refuses every read: a reader that joins correctly never calls it. */
  private final RelayPayloadStore store =
      new RelayPayloadStore() {
        @Override
        public String store(RelayPayload payload, Instant now) {
          return RelayPort.hash(RelayPayloadCodec.write(payload));
        }

        @Override
        public RelayStoredPayload load(String digest) {
          return fail("a reader fetched a payload per record instead of joining");
        }

        @Override
        public Map<String, RelayStoredPayload> load(Collection<String> digests) {
          return fail("a reader fetched payloads outside its own query");
        }

        @Override
        public int sweep(Instant now, int limit) {
          throw new UnsupportedOperationException();
        }
      };

  private record Query(String sql, List<Object> arguments) {}

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void answerQueries(Rows rows) {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              Object[] arguments = (Object[]) call.getRawArguments()[2];
              queries.add(new Query(sql, Arrays.asList(arguments)));
              return rows.answer(sql, (RowMapper<Object>) call.getArgument(1));
            });
  }

  private interface Rows {
    List<Object> answer(String sql, RowMapper<Object> mapper) throws SQLException;
  }

  private Query only(String fragment) {
    var matching = queries.stream().filter(query -> query.sql().contains(fragment)).toList();
    assertEquals(
        1,
        matching.size(),
        () -> "expected exactly one query containing " + fragment + ": " + matching);
    return matching.getFirst();
  }

  /**
   * The guard this branch learned to need: a mocked query is never parsed, so a column the schema
   * no longer has is invisible until runtime. Every changed read is checked for the join and for
   * the absence of the dropped column.
   */
  private static void readsThroughTheStore(String sql) {
    assertTrue(sql.contains("JOIN relay_payloads"), () -> "no payload join in: " + sql);
    assertFalse(sql.contains("p.payload::text"), () -> "selects the dropped column in: " + sql);
    assertFalse(sql.contains("a.payload::text"), () -> "selects the dropped column in: " + sql);
  }

  /** The running sum keeps the per-row headroom and now comes from the stored byte length. */
  private static void budgetsFromStoredByteLength(Query page) {
    assertFalse(
        page.sql().contains("octet_length(payload"),
        () -> "still sizes the dropped column in: " + page.sql());
    assertTrue(page.sql().contains("byte_length"), () -> "no stored length in: " + page.sql());
    assertTrue(page.sql().contains("+ 4096"), () -> "lost the per-row headroom in: " + page.sql());
    assertTrue(
        page.arguments().contains(BUDGET),
        () -> "page budget moved away from " + BUDGET + ": " + page.arguments());
  }

  // --- the broker read page -------------------------------------------------

  private JdbcRelayRepository broker() {
    return new JdbcRelayRepository(jdbc, UnitOfWork.NONE, store);
  }

  private void answerBrokerRead(int records) {
    answerQueries(
        (sql, mapper) -> {
          if (sql.contains("FROM relay_topics")) return List.of(mapper.mapRow(topicRow(), 0));
          if (sql.contains("FROM relay_subscriptions"))
            return List.of(mapper.mapRow(subscriptionRow(), 0));
          if (sql.contains("relay_publications")) {
            var rows = new ArrayList<>();
            for (int i = 1; i <= records; i++) rows.add(mapper.mapRow(publicationRow(i), 0));
            return rows;
          }
          return List.of();
        });
  }

  @Test
  void publicationPageIssuesOneJoinNotOneQueryPerRecord() {
    answerBrokerRead(3);
    var read = broker().read(new Relay.SubscriptionKey(TOPIC, "group"), 100);
    assertEquals(3, read.publications().size());
    assertEquals(
        "payload 1", ((RelayPayload.Text) read.publications().getFirst().event().payload()).text());
    readsThroughTheStore(only("relay_publications").sql());
  }

  @Test
  void pageBoundariesMatchTheOctetLengthBudgetTheyReplace() {
    answerBrokerRead(1);
    broker().read(new Relay.SubscriptionKey(TOPIC, "group"), 100);
    budgetsFromStoredByteLength(only("relay_publications"));
  }

  @Test
  void retainedLookupReadsThroughTheStore() {
    answerQueries(
        (sql, mapper) -> {
          if (sql.contains("FROM relay_topics")) return List.of(mapper.mapRow(topicRow(), 0));
          if (sql.contains("relay_publications"))
            return List.of(mapper.mapRow(publicationRow(1), 0));
          return List.of();
        });
    var found = broker().retained(TOPIC, "event-1");
    assertEquals("payload 1", ((RelayPayload.Text) found.orElseThrow().event().payload()).text());
    readsThroughTheStore(only("relay_publications").sql());
  }

  @Test
  void appendNoLongerWritesThePayloadBytesIntoThePublication() {
    answerQueries(
        (sql, mapper) -> {
          if (sql.contains("FROM relay_topics")) return List.of(mapper.mapRow(topicRow(), 0));
          return List.of();
        });
    var draft =
        new Relay.Draft(
            "event-9", "publisher", T0, null, null, new RelayPayload.Text("bytes"), null);
    broker().append(TOPIC, draft, T0);
    var sql = org.mockito.ArgumentCaptor.forClass(String.class);
    var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, atLeastOnce()).update(sql.capture(), args.capture());
    String insert = null;
    List<Object> arguments = null;
    for (int i = 0; i < sql.getAllValues().size(); i++)
      if (sql.getAllValues().get(i).contains("INSERT INTO relay_publications")) {
        insert = sql.getAllValues().get(i);
        arguments = Arrays.asList(args.getAllValues().get(i));
      }
    assertNotNull(insert, "no publication insert");
    assertTrue(insert.contains("payload_digest"));
    assertFalse(insert.contains(",payload,"), "still writes a payload column: " + insert);
    assertFalse(
        arguments.contains(RelayPayloadCodec.write(draft.payload())),
        "the encoded bytes are the store's to write, not the publication's");
  }

  // --- the inspection log page --------------------------------------------

  @Test
  void logPageIssuesOneJoinNotOneQueryPerRecord() {
    var cache = new RecordingCache<RelayLogRepository.Snapshot>(0);
    answerLogRead(3);
    var snapshot = log(cache).read(TOPIC, 0, 16, "reader");
    assertEquals(3, snapshot.events().size());
    readsThroughTheStore(only("relay_publications").sql());
  }

  @Test
  void logPageBoundariesMatchTheOctetLengthBudgetTheyReplace() {
    answerLogRead(1);
    log(new RecordingCache<>(0)).read(TOPIC, 0, 16, "reader");
    budgetsFromStoredByteLength(only("relay_publications"));
  }

  @Test
  void projectionBypassTriggersAtTheSameThreshold() {
    // 32768 is the repository's own floor for a bounded page read; the bound it derives from a
    // larger limit must stay (maxWeight - 32768) / 64, and the joined length must not widen the
    // record that the bounded projection sizes.
    var cache = new RecordingCache<RelayLogRepository.Snapshot>(32768 + 64 * 1000);
    answerLogRead(1);
    var snapshot = log(cache).read(TOPIC, 0, 16, "reader");
    assertEquals(1, snapshot.events().size());
    var bounded =
        queries.stream()
            .filter(query -> query.sql().contains("projection_bytes"))
            .filter(query -> query.sql().contains("relay_publications"))
            .findFirst()
            .orElseGet(() -> fail("no bounded events projection"));
    assertEquals(1000L, bounded.arguments().getLast(), "the projection bound moved");
    assertTrue(
        bounded.sql().contains("octet_length(to_jsonb(projected)::text)"),
        "the bounded projection must keep sizing the projected record");
    // The joined length is a helper for the window, not part of the projected record: leaking it
    // would change the bytes the bound is compared against.
    assertFalse(
        bounded.sql().contains("payload_bytes,page_bytes")
            || bounded.sql().contains("byte_length,page_bytes"),
        () -> "the payload length leaked into the projected record: " + bounded.sql());
  }

  @Test
  void anOversizedProjectionStillFallsBackToTheCompletePage() {
    var cache = new RecordingCache<RelayLogRepository.Snapshot>(32768 + 64 * 1000);
    answerLogRead(1, 10_000_000L);
    var snapshot = log(cache).read(TOPIC, 0, 16, "reader");
    assertEquals(1, cache.bypasses);
    assertEquals(1, snapshot.events().size());
    assertEquals(
        "payload 1", ((RelayPayload.Text) snapshot.events().getFirst().event().payload()).text());
  }

  @Test
  void latestReadsThroughTheStore() throws SQLException {
    answerLogRead(1);
    var head = log(new RecordingCache<>(0)).latest(TOPIC);
    assertEquals(4L, head.position());
    assertEquals(
        "payload 1",
        ((RelayPayload.Text) head.publication().orElseThrow().event().payload()).text());
    readsThroughTheStore(only("relay_publications").sql());
  }

  @Test
  void snapshotWeightCountsDecodedPayloadsAsBefore() {
    var small = new RecordingCache<RelayLogRepository.Snapshot>(0);
    answerLogRead(1);
    log(small).read(TOPIC, 0, 16, "reader");
    queries.clear();
    var large = new RecordingCache<RelayLogRepository.Snapshot>(0);
    answerLogRead(1, 0L, "x".repeat(1000));
    log(large).read(TOPIC, 0, 16, "reader");
    long growth = large.weights.getFirst() - small.weights.getFirst();
    // textWeight charges four bytes per character of the decoded text, as it did when the bytes
    // came from the publication's own column.
    assertEquals(4L * (1000 - "payload 1".length()), growth);
  }

  private JdbcRelayLogRepository log(ProjectionCache<RelayLogRepository.Snapshot> pages) {
    return new JdbcRelayLogRepository(jdbc, UnitOfWork.NONE, ProjectionCache.direct(), pages);
  }

  private void answerLogRead(int records) {
    answerLogRead(records, 0L);
  }

  private void answerLogRead(int records, long projectionBytes) {
    answerLogRead(records, projectionBytes, null);
  }

  private void answerLogRead(int records, long projectionBytes, String text) {
    answerQueries(
        (sql, mapper) -> {
          if (sql.contains("FROM relay_topics")) {
            var row = topicRow();
            when(row.getLong("projection_rows")).thenReturn(1L);
            return List.of(mapper.mapRow(row, 0));
          }
          if (sql.contains("relay_publications")) {
            var rows = new ArrayList<>();
            for (int i = 1; i <= records; i++) {
              var row = publicationRow(i);
              if (text != null)
                when(row.getString("payload"))
                    .thenReturn(RelayPayloadCodec.write(new RelayPayload.Text(text)));
              when(row.getLong("projection_rows")).thenReturn((long) records);
              when(row.getLong("projection_bytes")).thenReturn(projectionBytes);
              rows.add(mapper.mapRow(row, 0));
            }
            return rows;
          }
          return List.of();
        });
  }

  // --- the admission read -------------------------------------------------

  @Test
  void admissionReadIssuesOneJoinNotOneQueryPerRecord() {
    answerQueries(
        (sql, mapper) -> {
          if (sql.contains("relay_admissions")) return List.of(mapper.mapRow(admissionRow(), 0));
          return List.of();
        });
    var deliveries = new JdbcRelayDeliveryRepository(jdbc, UnitOfWork.NONE);
    var key = new RelayDeliveries.AdmissionKey(new Relay.SubscriptionKey(TOPIC, "group"), 1);
    var admission = deliveries.admission(key).orElseThrow();
    assertEquals(
        "payload 1", ((RelayPayload.Text) admission.publication().event().payload()).text());
    readsThroughTheStore(only("relay_admissions").sql());
  }

  @Test
  void admitReadsThePublicationThroughTheStoreAndWritesNoBytes() throws SQLException {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    answerQueries(
        (sql, mapper) -> {
          if (sql.contains("FROM relay_topics")) {
            var row = mock(ResultSet.class);
            when(row.getString("payload_kind")).thenReturn("TEXT");
            when(row.getLong("last_position")).thenReturn(1L);
            return List.of(mapper.mapRow(row, 0));
          }
          if (sql.contains("FROM relay_subscriptions")) {
            var row = mock(ResultSet.class);
            when(row.getLong("seen_through")).thenReturn(0L);
            return List.of(mapper.mapRow(row, 0));
          }
          if (sql.contains("relay_publications"))
            return List.of(mapper.mapRow(publicationRow(1), 0));
          return List.of();
        });
    var deliveries = new JdbcRelayDeliveryRepository(jdbc, UnitOfWork.NONE);
    var routing =
        new RelayDeliveries.SourcePin(
            "relay-a/routes.js", "export default 1;", RelayPort.hash("export default 1;"));
    deliveries.admit(
        new RelayDeliveries.AdmissionKey(new Relay.SubscriptionKey(TOPIC, "group"), 1),
        new RelayDeliveries.Decision(routing, List.of()),
        T0);
    readsThroughTheStore(only("relay_publications").sql());
    var sql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object[].class));
    String insert =
        sql.getAllValues().stream()
            .filter(statement -> statement.contains("INSERT INTO relay_admissions"))
            .findFirst()
            .orElseGet(() -> fail("no admission insert"));
    assertTrue(insert.contains("payload_digest"), "the digest is still copied");
    assertFalse(insert.contains(",payload,"), () -> "still writes a payload column: " + insert);
  }

  // --- the store's own resolution ------------------------------------------

  @Test
  void aJsonbRenderingDigestAndAnApplicationDigestBothResolve() {
    // V192 backfilled a digest over PostgreSQL's JSONB rendering while store() hashes the
    // application's serialization, so equivalent content can be held under two digests. Both must
    // resolve; whether the two renderings actually diverge is the full-db tier's proof.
    var jdbcStore =
        new JdbcRelayPayloadStore(
            jdbc,
            UnitOfWork.NONE,
            java.time.Duration.ofHours(1),
            java.time.Duration.ofHours(24),
            java.time.Duration.ofDays(7));
    String rendered = "a".repeat(64);
    String application = "b".repeat(64);
    doAnswer(
            call -> {
              org.springframework.jdbc.core.RowCallbackHandler handler = call.getArgument(1);
              for (String digest : List.of(rendered, application)) {
                var row = mock(ResultSet.class);
                when(row.getString(1)).thenReturn(digest);
                when(row.getString(2)).thenReturn("{\"text\": \"same\"}");
                when(row.getLong(3)).thenReturn(18L);
                handler.processRow(row);
              }
              return null;
            })
        .when(jdbc)
        .query(
            anyString(),
            any(org.springframework.jdbc.core.RowCallbackHandler.class),
            any(Object[].class));
    var loaded = jdbcStore.load(List.of(rendered, application));
    assertEquals(2, loaded.size());
    assertEquals("{\"text\": \"same\"}", loaded.get(rendered).json());
    assertEquals("{\"text\": \"same\"}", loaded.get(application).json());
  }

  // --- fixtures -----------------------------------------------------------

  private static ResultSet topicRow() throws SQLException {
    var row = mock(ResultSet.class);
    when(row.getString("scope_key")).thenReturn(RelayScopeCodec.write(TOPIC));
    when(row.getString("name")).thenReturn(TOPIC.name());
    when(row.getString("payload_kind")).thenReturn("TEXT");
    when(row.getLong("retention_seconds")).thenReturn(3600L);
    when(row.getLong("last_position")).thenReturn(4L);
    when(row.getObject("generation", UUID.class)).thenReturn(new UUID(0, 1));
    return row;
  }

  private static ResultSet subscriptionRow() throws SQLException {
    var row = mock(ResultSet.class);
    when(row.getLong("seen_through")).thenReturn(0L);
    when(row.getObject("seen_at", OffsetDateTime.class)).thenReturn(T0.atOffset(ZoneOffset.UTC));
    when(row.getObject("generation", UUID.class)).thenReturn(new UUID(0, 2));
    return row;
  }

  private static ResultSet publicationRow(int position) throws SQLException {
    var row = mock(ResultSet.class);
    when(row.getLong("position")).thenReturn((long) position);
    when(row.getString("event_id")).thenReturn("event-" + position);
    when(row.getString("publisher")).thenReturn("publisher");
    when(row.getInt("schema_version")).thenReturn(1);
    when(row.getString("payload"))
        .thenReturn(RelayPayloadCodec.write(new RelayPayload.Text("payload " + position)));
    when(row.getObject(anyString(), eq(OffsetDateTime.class)))
        .thenReturn(T0.atOffset(ZoneOffset.UTC));
    return row;
  }

  private static ResultSet admissionRow() throws SQLException {
    var row = publicationRow(1);
    when(row.getString("scope_key")).thenReturn(RelayScopeCodec.write(TOPIC));
    when(row.getString("topic")).thenReturn(TOPIC.name());
    when(row.getString("subscriber")).thenReturn("group");
    when(row.getLong("publication_position")).thenReturn(1L);
    when(row.getString("payload_kind")).thenReturn("TEXT");
    when(row.getString("routing_path")).thenReturn("relay-a/routes.js");
    when(row.getString("routing_source")).thenReturn("export default 1;");
    when(row.getString("routing_hash")).thenReturn(RelayPort.hash("export default 1;"));
    when(row.getInt("branch_count")).thenReturn(0);
    return row;
  }

  /** Records the weight the repository computes and mirrors the real cache's bypass fallback. */
  private static final class RecordingCache<T> implements ProjectionCache<T> {
    private final long maxWeight;
    final List<Long> weights = new ArrayList<>();
    int bypasses;

    RecordingCache(long maxWeight) {
      this.maxWeight = maxWeight;
    }

    @Override
    public boolean enabled() {
      return maxWeight > 0;
    }

    @Override
    public long maxWeight() {
      return maxWeight;
    }

    @Override
    public T read(
        SnapshotCache.Key key,
        boolean cacheable,
        Supplier<T> boundedLoader,
        Supplier<T> uncachedLoader,
        ToLongFunction<T> weight) {
      T value;
      if (cacheable) {
        try {
          value = boundedLoader.get();
        } catch (ProjectionCache.Bypass refused) {
          bypasses++;
          value = uncachedLoader.get();
        }
      } else {
        value = uncachedLoader.get();
      }
      weights.add(weight.applyAsLong(value));
      return value;
    }
  }
}
