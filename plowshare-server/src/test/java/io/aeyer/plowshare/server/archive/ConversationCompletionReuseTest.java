package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ConversationCompletionReuseTest {
  @Test
  void successfulCompletionComparesSourceInWriteAndKeepsAFreshPostWriteProjection() {
    var jdbc = mock(JdbcTemplate.class);
    var allowances = mock(AllowanceRepository.class);
    var now = Instant.parse("2026-10-20T00:00:00Z");
    var budget = Budget.restored(UUID.randomUUID(), 10, 3);
    var record =
        new ConversationRecord(
            "conversation",
            Home.global(),
            Origin.TURN,
            ConversationLifecycle.ACTIVE,
            null,
            null,
            now,
            now,
            null,
            null,
            null);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<ConversationRecord>>any(),
            any(Object[].class)))
        .thenReturn(List.of(record));
    var store = new ConversationStore(jdbc, () -> now, () -> "unused", allowances);
    assertSame(record, store.turnEnded("conversation", budget));
    var order = inOrder(jdbc);
    order
        .verify(jdbc)
        .update(
            "UPDATE conversations SET last_turn_at=? WHERE id=? AND allowance_id=?",
            now.atOffset(ZoneOffset.UTC),
            "conversation",
            budget.identity());
    order
        .verify(jdbc)
        .query(
            contains("FROM conversation_allowance_state"),
            org.mockito.ArgumentMatchers.<RowMapper<ConversationRecord>>any(),
            eq("conversation"));
    verifyNoMoreInteractions(jdbc);
    verifyNoInteractions(allowances); // No mutable Budget restoration just to compare its UUID.
  }

  @Test
  void failedIdentityGuardStillExplainsWrongSourceWithoutReturningAConversation() {
    var jdbc = mock(JdbcTemplate.class);
    var allowances = mock(AllowanceRepository.class);
    var actual = new AllowanceId(UUID.randomUUID());
    when(jdbc.query(
            contains("SELECT allowance_id"),
            org.mockito.ArgumentMatchers.<RowMapper<AllowanceId>>any(),
            any(Object[].class)))
        .thenReturn(List.of(actual));
    when(allowances.restore(actual)).thenReturn(Budget.restored(actual.value(), 10, 2));
    var store = new ConversationStore(jdbc, Instant::now, () -> "unused", allowances);
    var thrown =
        assertThrows(
            ArchiveException.class,
            () -> store.turnEnded("conversation", Budget.restored(UUID.randomUUID(), 10, 3)));
    assertEquals("Turn has a different retained allowance source", thrown.getMessage());
    verify(jdbc, never())
        .query(
            contains("FROM conversation_allowance_state"),
            org.mockito.ArgumentMatchers.<RowMapper<ConversationRecord>>any(),
            any(Object[].class));
  }
}
