package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** The store's SQL contract against a mocked template; PostgreSQL behavior is the full-db tier. */
class RelayPayloadStoreTest {
  private static final Instant NOW = Instant.parse("2026-10-25T10:42:17.123456Z");
  private JdbcTemplate jdbc;
  private JdbcRelayPayloadStore store;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    store =
        new JdbcRelayPayloadStore(
            jdbc, UnitOfWork.NONE, Duration.ofHours(1), Duration.ofHours(24), Duration.ofDays(30));
  }

  @Test
  void storeReturnsLowercaseHexDigestOfTheEncodedPayload() {
    var payload = new RelayPayload.Text("hello");
    String digest = store.store(payload, NOW);
    assertTrue(digest.matches("[0-9a-f]{64}"));
    assertEquals(RelayPort.hash(RelayPayloadCodec.write(payload)), digest);
    var args = ArgumentCaptor.forClass(Object.class);
    verify(jdbc)
        .update(anyString(), args.capture(), args.capture(), args.capture(), args.capture());
    assertEquals(digest, args.getAllValues().get(0));
    assertEquals(RelayPayloadCodec.write(payload), args.getAllValues().get(1));
  }

  @Test
  void storeIsIdempotentForIdenticalContent() {
    String first = store.store(new RelayPayload.Text("same"), NOW);
    String second = store.store(new RelayPayload.Text("same"), NOW);
    assertEquals(first, second);
    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, times(2)).update(sql.capture(), any(Object[].class));
    assertTrue(sql.getValue().contains("ON CONFLICT (digest) DO NOTHING"));
    assertNotEquals(first, store.store(new RelayPayload.Text("different"), NOW));
  }

  private Timestamp ejectionFor(Instant now) {
    var template = mock(JdbcTemplate.class);
    var local =
        new JdbcRelayPayloadStore(
            template,
            UnitOfWork.NONE,
            Duration.ofHours(1),
            Duration.ofHours(24),
            Duration.ofDays(30));
    local.store(new RelayPayload.Text("x"), now);
    var args = ArgumentCaptor.forClass(Object.class);
    verify(template)
        .update(anyString(), args.capture(), args.capture(), args.capture(), args.capture());
    return (Timestamp) args.getAllValues().get(3);
  }

  @Test
  void storeRoundsEjectionUpToTheNextHourBoundary() {
    assertEquals(
        Timestamp.from(Instant.parse("2026-10-25T12:00:00Z")),
        ejectionFor(Instant.parse("2026-10-25T10:42:17.123456Z")));
  }

  @Test
  void storeNeverEjectsBeforeNowPlusTheStagingWindow() {
    var now = Instant.parse("2026-10-25T10:59:59Z");
    var ejection = ejectionFor(now).toInstant();
    assertFalse(ejection.isBefore(Instant.parse("2026-10-25T11:59:59Z")));
    assertEquals(Instant.parse("2026-10-25T12:00:00Z"), ejection);
  }

  @Test
  void storeLeavesAnExactHourBoundaryUnchanged() {
    assertEquals(
        Timestamp.from(Instant.parse("2026-10-25T11:00:00Z")),
        ejectionFor(Instant.parse("2026-10-25T10:00:00Z")));
  }

  @Test
  void loadMissingDigestThrows() {
    assertThrows(IllegalStateException.class, () -> store.load("a".repeat(64)));
  }

  @Test
  void loadRejectsMalformedDigestBeforeQuerying() {
    assertThrows(IllegalArgumentException.class, () -> store.load("not-a-digest"));
    verifyNoInteractions(jdbc);
  }

  @Test
  void batchLoadIssuesOneQueryForManyDigests() {
    var digests = List.of("a".repeat(64), "b".repeat(64), "c".repeat(64));
    doAnswer(
            call -> {
              var handler = (RowCallbackHandler) call.getArgument(1);
              var row = mock(ResultSet.class);
              when(row.getString(1)).thenReturn("a".repeat(64));
              when(row.getString(2)).thenReturn("{\"text\": \"hi\"}");
              when(row.getLong(3)).thenReturn(14L);
              handler.processRow(row);
              return null;
            })
        .when(jdbc)
        .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    var loaded = store.load(digests);
    verify(jdbc, times(1)).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    assertEquals(Set.of("a".repeat(64)), loaded.keySet());
    assertEquals(14L, loaded.get("a".repeat(64)).byteLength());
  }

  @Test
  void sweepSelectsMaturedCandidatesWithTheBatchLimitAndDeletesEach() {
    when(jdbc.queryForList(anyString(), eq(String.class), any(), eq(2)))
        .thenReturn(List.of("a".repeat(64), "b".repeat(64)));
    when(jdbc.update(anyString(), any(Object.class))).thenReturn(1);
    assertEquals(2, store.sweep(NOW, 2));
    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), eq(String.class), any(), eq(2));
    assertTrue(sql.getValue().contains("ejection_at<=?"));
    assertTrue(sql.getValue().contains("ORDER BY ejection_at"));
    var delete = ArgumentCaptor.forClass(String.class);
    verify(jdbc, times(2)).update(delete.capture(), any(Object.class));
    // Reachability is checked by the delete itself, against both referencing tables.
    assertTrue(
        delete
            .getValue()
            .contains("NOT EXISTS (SELECT 1 FROM relay_publications r WHERE r.payload_digest"));
    assertTrue(
        delete
            .getValue()
            .contains("NOT EXISTS (SELECT 1 FROM relay_admissions a WHERE a.payload_digest"));
  }

  @Test
  void sweepRejectsLimitAboveOneThousand() {
    assertThrows(IllegalArgumentException.class, () -> store.sweep(NOW, 1001));
    verifyNoInteractions(jdbc);
  }

  @Test
  void propertiesRejectNonPositiveStagingWindow() {
    var properties = new RelayProperties();
    assertEquals(Duration.ofHours(1), properties.getPayloadStagingWindow());
    assertThrows(
        IllegalArgumentException.class, () -> properties.setPayloadStagingWindow(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> properties.setPayloadStagingWindow(Duration.ofSeconds(-1)));
  }

  @Test
  void propertiesRejectSweepBatchAboveOneThousand() {
    var properties = new RelayProperties();
    assertEquals(100, properties.getPayloadSweepBatch());
    assertThrows(IllegalArgumentException.class, () -> properties.setPayloadSweepBatch(1001));
    assertThrows(IllegalArgumentException.class, () -> properties.setPayloadSweepBatch(0));
  }

  @Test
  void propertiesRejectDeferralCeilingOutsideItsRange() {
    var properties = new RelayProperties();
    assertEquals(Duration.ofHours(24), properties.getPayloadDeferralCeiling());
    assertThrows(
        IllegalArgumentException.class,
        () -> properties.setPayloadDeferralCeiling(Duration.ofMinutes(59)));
    assertThrows(
        IllegalArgumentException.class,
        () -> properties.setPayloadDeferralCeiling(Duration.ofDays(31)));
    assertThrows(NullPointerException.class, () -> properties.setPayloadDeferralCeiling(null));
  }
}
