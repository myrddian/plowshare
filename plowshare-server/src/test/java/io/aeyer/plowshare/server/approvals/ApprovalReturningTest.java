package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ApprovalReturningTest {
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final Instant now = Instant.parse("2026-10-01T12:00:00Z");
  final RunApprovalStore store = new RunApprovalStore(jdbc, () -> now);
  final ApprovalEvents events = mock(ApprovalEvents.class);

  RunApproval approval(String state) {
    return new RunApproval(
        "approval",
        1,
        "conversation",
        "conversation",
        "reader",
        "bot",
        "server",
        List.of("inspect"),
        null,
        "Inspect",
        state,
        null,
        null,
        null,
        null,
        null,
        now);
  }

  @Test
  void askPublishesTheInsertedSnapshotWithoutASecondRead() {
    var value = approval(RunApproval.ASKED);
    store.useEvents(events);
    when(jdbc.queryForObject(
            startsWith("INSERT INTO run_approvals"),
            ArgumentMatchers.<RowMapper<RunApproval>>any(),
            any(Object[].class)))
        .thenReturn(value);
    assertSame(
        value,
        store.ask(
            1,
            "conversation",
            "conversation",
            "reader",
            "bot",
            "server",
            List.of("inspect"),
            null,
            "Inspect"));
    verify(jdbc)
        .queryForObject(
            contains("RETURNING"),
            ArgumentMatchers.<RowMapper<RunApproval>>any(),
            any(Object[].class));
    verify(events).asked(value);
    verifyNoMoreInteractions(jdbc, events);
  }

  @Test
  void winningAnswersPublishExactlyTheirReturnedRowAndLosingAnswersDoNotSettle() {
    var value = approval(RunApproval.ALLOWED);
    var settled = new AtomicInteger();
    store.useEvents(events);
    store.whenSettled(id -> settled.incrementAndGet());
    when(jdbc.query(
            startsWith("UPDATE run_approvals"),
            ArgumentMatchers.<RowMapper<RunApproval>>any(),
            any(Object[].class)))
        .thenReturn(List.of(value))
        .thenReturn(List.of());
    assertTrue(store.allow("approval", RunApproval.ONCE, null, "reader"));
    assertFalse(store.allow("approval", RunApproval.ONCE, null, "reader"));
    assertEquals(1, settled.get());
    verify(events).answered(value);
    verify(jdbc, times(2))
        .query(
            contains("RETURNING"),
            ArgumentMatchers.<RowMapper<RunApproval>>any(),
            any(Object[].class));
    verifyNoMoreInteractions(jdbc, events);
  }
}
