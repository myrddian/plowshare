package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Where the payload write sits relative to the append's transaction, observed with recording fakes.
 * Real transactions and contention are the full-db tier's concern.
 */
class RelayPayloadWritePlacementTest {
  private static final Instant T0 = Instant.parse("2026-10-25T10:00:00Z");
  private static final Relay.TopicKey TOPIC = new Relay.TopicKey(7, "events");

  private final List<String> events = new ArrayList<>();
  private final List<String> stored = new ArrayList<>();
  private JdbcTemplate jdbc;
  private JdbcRelayRepository repository;
  private boolean alreadyRetained;

  /** Records each store call, and the order it happens in relative to transactions. */
  private final RelayPayloadStore store =
      new RelayPayloadStore() {
        @Override
        public String store(RelayPayload payload, Instant now) {
          events.add("store");
          String digest = RelayPort.hash(RelayPayloadCodec.write(payload));
          stored.add(digest);
          return digest;
        }

        @Override
        public RelayStoredPayload load(String digest) {
          throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, RelayStoredPayload> load(Collection<String> digests) {
          throw new UnsupportedOperationException();
        }

        @Override
        public int sweep(Instant now, int limit) {
          throw new UnsupportedOperationException();
        }
      };

  private final UnitOfWork transactions =
      new UnitOfWork() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
          events.add("begin");
          return work.get();
        }
      };

  @BeforeEach
  void setUp() throws SQLException {
    jdbc = mock(JdbcTemplate.class);
    repository = new JdbcRelayRepository(jdbc, transactions, store);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    answerQueries(
        (sql, mapper) -> {
          if (sql.contains("FROM relay_topics")) return List.of(mapper.mapRow(topicRow(), 0));
          if (sql.startsWith("SELECT 1 FROM relay_publications"))
            return alreadyRetained ? List.of(mapper.mapRow(mock(ResultSet.class), 0)) : List.of();
          if (sql.contains("FROM relay_publications") && alreadyRetained)
            return List.of(mapper.mapRow(publicationRow(), 0));
          return List.of();
        });
  }

  /** Maps SQL text to the rows a query returns, built through the caller's own row mapper. */
  private interface Rows {
    List<Object> answer(String sql, RowMapper<Object> mapper) throws SQLException;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void answerQueries(Rows rows) {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            call -> rows.answer(call.getArgument(0), (RowMapper<Object>) call.getArgument(1)));
  }

  private static ResultSet topicRow() throws SQLException {
    var row = mock(ResultSet.class);
    when(row.getString("scope_key")).thenReturn(RelayScopeCodec.write(TOPIC));
    when(row.getString("name")).thenReturn("events");
    when(row.getString("payload_kind")).thenReturn("TEXT");
    when(row.getLong("retention_seconds")).thenReturn(3600L);
    when(row.getLong("last_position")).thenReturn(4L);
    return row;
  }

  private static ResultSet publicationRow() throws SQLException {
    var row = mock(ResultSet.class);
    when(row.getLong("position")).thenReturn(4L);
    when(row.getObject("published_at", OffsetDateTime.class))
        .thenReturn(T0.atOffset(ZoneOffset.UTC));
    when(row.getObject("occurred_at", OffsetDateTime.class))
        .thenReturn(T0.atOffset(ZoneOffset.UTC));
    when(row.getString("event_id")).thenReturn("e1");
    when(row.getString("publisher")).thenReturn("publisher");
    when(row.getInt("schema_version")).thenReturn(1);
    when(row.getString("payload")).thenReturn(RelayPayloadCodec.write(draft().payload()));
    return row;
  }

  private static Relay.Draft draft() {
    return new Relay.Draft("e1", "publisher", T0, null, null, new RelayPayload.Text("hello"), null);
  }

  private List<Object> insertArguments() {
    var sql = ArgumentCaptor.forClass(String.class);
    var args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, atLeastOnce()).update(sql.capture(), args.capture());
    for (int i = 0; i < sql.getAllValues().size(); i++)
      if (sql.getAllValues().get(i).contains("INSERT INTO relay_publications"))
        return java.util.Arrays.asList(args.getAllValues().get(i));
    return fail("no publication insert");
  }

  private String insertSql() {
    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object[].class));
    return sql.getAllValues().stream()
        .filter(s -> s.contains("INSERT INTO relay_publications"))
        .findFirst()
        .orElseGet(() -> fail("no publication insert"));
  }

  @Test
  void appendCommitsThePayloadBeforeTheTopicTransaction() {
    repository.append(TOPIC, draft(), T0);
    assertEquals(List.of("store", "begin"), events);
  }

  @Test
  void appendWritesTheDigestReturnedByTheStore() {
    repository.append(TOPIC, draft(), T0);
    String digest = RelayPort.hash(RelayPayloadCodec.write(draft().payload()));
    assertTrue(insertSql().contains("payload_digest"));
    assertTrue(insertArguments().contains(digest));
  }

  @Test
  void anIdempotentRepublishStoresNothing() {
    alreadyRetained = true;
    var original = repository.append(TOPIC, draft(), T0);
    assertEquals(4L, original.position());
    assertEquals(List.of(), stored);
    assertEquals(List.of("begin"), events);
  }

  @Test
  void aFailedAppendLeavesTheCommittedPayloadWithoutReportingIt() {
    var boom = new IllegalStateException("insert failed");
    when(jdbc.update(startsWith("INSERT INTO relay_publications"), any(Object[].class)))
        .thenThrow(boom);
    var thrown =
        assertThrows(IllegalStateException.class, () -> repository.append(TOPIC, draft(), T0));
    // The original failure surfaces unchanged: the orphaned payload is not an error, and the
    // sweep reclaims it once its staging window has passed.
    assertSame(boom, thrown);
    assertEquals(1, stored.size());
  }

  @Test
  void retryingAFailedAppendReusesTheSameDigest() {
    when(jdbc.update(startsWith("INSERT INTO relay_publications"), any(Object[].class)))
        .thenThrow(new IllegalStateException("insert failed"))
        .thenReturn(1);
    assertThrows(IllegalStateException.class, () -> repository.append(TOPIC, draft(), T0));
    repository.append(TOPIC, draft(), T0);
    assertEquals(2, stored.size());
    assertEquals(stored.get(0), stored.get(1));
  }

  @Test
  void admissionCopiesTheDigestAndStoresNoBytes() throws SQLException {
    var deliveries = new JdbcRelayDeliveryRepository(jdbc, transactions);
    var subscription = new Relay.SubscriptionKey(TOPIC, "group");
    var routing =
        new RelayDeliveries.SourcePin(
            "relay-a/routes.js", "export default 1;", RelayPort.hash("export default 1;"));
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
          if (sql.contains("FROM relay_publications"))
            return List.of(mapper.mapRow(publicationRow(), 0));
          return List.of();
        });
    deliveries.admit(
        new RelayDeliveries.AdmissionKey(subscription, 1),
        new RelayDeliveries.Decision(routing, List.of()),
        T0);
    assertEquals(List.of(), stored);
    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object[].class));
    String insert =
        sql.getAllValues().stream()
            .filter(s -> s.contains("INSERT INTO relay_admissions"))
            .findFirst()
            .orElseGet(() -> fail("no admission insert"));
    assertTrue(insert.contains("payload_digest"));
    assertTrue(insert.contains("FROM relay_publications"), "the digest is copied, not recomputed");
  }
}
