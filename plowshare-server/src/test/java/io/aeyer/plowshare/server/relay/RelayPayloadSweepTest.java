package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Reclamation by reachability against a mocked template. The horizon SQL itself needs PostgreSQL
 * and belongs to the full-db tier; here the template answers with the horizon it would compute and
 * the assertions cover what the store does with it.
 */
class RelayPayloadSweepTest {
  private static final Instant NOW = Instant.parse("2026-10-25T10:42:17Z");
  private static final String DIGEST = "a".repeat(64);
  private static final Duration STAGING = Duration.ofHours(1);
  private static final Duration CEILING = Duration.ofHours(24);

  private static RowMapper<Object> anyMapper() {
    return any();
  }

  private JdbcTemplate jdbc;
  private JdbcRelayPayloadStore store;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    store = new JdbcRelayPayloadStore(jdbc, UnitOfWork.NONE, STAGING, CEILING, Duration.ofDays(30));
    when(jdbc.queryForList(anyString(), eq(String.class), any(), anyInt()))
        .thenReturn(List.of(DIGEST));
  }

  /** The delete matches nothing, as it does while a publication or admission references it. */
  private void referencedWith(Instant furthestRemoval, boolean unsettledAdmission) {
    when(jdbc.update(anyString(), eq(DIGEST))).thenReturn(0);
    when(jdbc.query(anyString(), anyMapper(), any(Object[].class)))
        .thenAnswer(
            call -> {
              var row = mock(ResultSet.class);
              when(row.getTimestamp(1))
                  .thenReturn(furthestRemoval == null ? null : Timestamp.from(furthestRemoval));
              when(row.getBoolean(2)).thenReturn(unsettledAdmission);
              return List.of(call.<RowMapper<?>>getArgument(1).mapRow(row, 0));
            });
  }

  private Instant rescheduledTo() {
    var ejection = ArgumentCaptor.forClass(Timestamp.class);
    verify(jdbc).update(contains("SET ejection_at"), ejection.capture(), eq(DIGEST));
    return ejection.getValue().toInstant();
  }

  @Test
  void deletesAnUnreferencedMaturedPayload() {
    when(jdbc.update(anyString(), eq(DIGEST))).thenReturn(1);
    assertEquals(1, store.sweep(NOW, 10));
    verify(jdbc, never()).query(anyString(), anyMapper(), any(Object[].class));
    verify(jdbc, never()).update(contains("SET ejection_at"), any(), any());
  }

  @Test
  void keepsAPayloadReferencedByAPublication() {
    referencedWith(NOW.plus(Duration.ofHours(5)), false);
    assertEquals(0, store.sweep(NOW, 10));
    assertNotNull(rescheduledTo());
  }

  @Test
  void keepsAPayloadReferencedOnlyByAnAdmission() {
    referencedWith(NOW.plus(Duration.ofHours(5)), true);
    assertEquals(0, store.sweep(NOW, 10));
    assertNotNull(rescheduledTo());
  }

  @Test
  void ignoresAPayloadWhoseEjectionIsInTheFuture() {
    when(jdbc.queryForList(anyString(), eq(String.class), any(), anyInt())).thenReturn(List.of());
    assertEquals(0, store.sweep(NOW, 10));
    var cutoff = ArgumentCaptor.forClass(Object.class);
    verify(jdbc)
        .queryForList(contains("ejection_at<=?"), eq(String.class), cutoff.capture(), eq(10));
    assertEquals(Timestamp.from(NOW), cutoff.getValue());
    verify(jdbc, never()).update(anyString(), any(Object.class));
  }

  @Test
  void extendsASurvivorToItsFurthestReferenceHorizonTruncatedDownToTheHour() {
    referencedWith(Instant.parse("2026-10-25T16:12:17Z"), false);
    store.sweep(NOW, 10);
    // Truncated down: the horizon is a maximum deferral, so a too-early date costs one wasted
    // examination while a too-late one would leave unreachable bytes a bucket longer.
    assertEquals(Instant.parse("2026-10-25T16:00:00Z"), rescheduledTo());
  }

  @Test
  void clampsASurvivorExtensionToTheDeferralCeiling() {
    referencedWith(NOW.plus(Duration.ofDays(3650)), false);
    store.sweep(NOW, 10);
    assertEquals(Instant.parse("2026-10-26T10:00:00Z"), rescheduledTo());
  }

  @Test
  void fallsBackToTheStagingWindowForAnUnsettledAdmission() {
    referencedWith(null, true);
    store.sweep(NOW, 10);
    assertEquals(Instant.parse("2026-10-25T11:00:00Z"), rescheduledTo());
  }

  @Test
  void anUnsettledAdmissionDoesNotShortenALongerPublicationHorizon() {
    referencedWith(Instant.parse("2026-10-25T16:12:17Z"), true);
    store.sweep(NOW, 10);
    assertEquals(Instant.parse("2026-10-25T16:00:00Z"), rescheduledTo());
  }

  @Test
  void neverReschedulesIntoThePastWhenRetentionHasElapsedButPruneHasNotRun() {
    referencedWith(NOW.minus(Duration.ofDays(2)), false);
    store.sweep(NOW, 10);
    // Still referenced, so it must leave the candidate set until the next hour boundary.
    assertEquals(Instant.parse("2026-10-25T11:00:00Z"), rescheduledTo());
  }

  @Test
  void neverReschedulesToNowEvenWhenTheHorizonTruncatesToTheCurrentHour() {
    referencedWith(Instant.parse("2026-10-25T10:50:00Z"), false);
    store.sweep(NOW, 10);
    assertEquals(Instant.parse("2026-10-25T11:00:00Z"), rescheduledTo());
  }

  @Test
  void honoursTheConfiguredBatchLimit() {
    when(jdbc.queryForList(anyString(), eq(String.class), any(), anyInt())).thenReturn(List.of());
    store.sweep(NOW, 7);
    verify(jdbc).queryForList(anyString(), eq(String.class), any(), eq(7));
    assertThrows(IllegalArgumentException.class, () -> store.sweep(NOW, 1001));
  }
}
