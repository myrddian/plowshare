package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectRole;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class ServiceTokenListingTest {
  @Test
  void ownerListingGroupsOrderedScopesAndRetainsEmptyTokensInOneStatement() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var rows = mock(ResultSet.class);
    UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    var current = new AtomicInteger(-1);
    when(rows.next()).thenAnswer(call -> current.incrementAndGet() < 3);
    when(rows.getObject("id", UUID.class)).thenAnswer(call -> current.get() < 2 ? first : second);
    when(rows.getString("name")).thenAnswer(call -> current.get() < 2 ? "first" : "second");
    when(rows.getString("principal_handle"))
        .thenAnswer(call -> current.get() < 2 ? "@service/first" : "@service/second");
    when(rows.getString("project"))
        .thenAnswer(call -> current.get() == 0 ? "one" : current.get() == 1 ? "two" : null);
    when(rows.getString("role")).thenReturn("VIEWER");
    var created = OffsetDateTime.parse("2026-10-01T12:00:00Z");
    when(rows.getObject("created_at", OffsetDateTime.class)).thenReturn(created);
    when(rows.getObject("expires_at", OffsetDateTime.class)).thenReturn(created.plusDays(1));
    when(jdbc.query(
            anyString(),
            ArgumentMatchers.<ResultSetExtractor<List<ServiceAccounts.Token>>>any(),
            eq("owner")))
        .thenAnswer(
            call -> {
              ResultSetExtractor<List<ServiceAccounts.Token>> mapping = call.getArgument(1);
              return mapping.extractData(rows);
            });

    var listed = new JdbcServiceAccountRepository(jdbc).tokens("owner");

    assertEquals(List.of(first, second), listed.stream().map(ServiceAccounts.Token::id).toList());
    assertEquals(
        List.of(
            new ServiceAccounts.Scope("one", ProjectRole.VIEWER),
            new ServiceAccounts.Scope("two", ProjectRole.VIEWER)),
        listed.getFirst().scopes());
    assertTrue(listed.getLast().scopes().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> listed.getFirst().scopes().clear());
    verify(jdbc)
        .query(
            contains("WHERE t.owner_handle=?"),
            ArgumentMatchers.<ResultSetExtractor<List<ServiceAccounts.Token>>>any(),
            eq("owner"));
    verifyNoMoreInteractions(jdbc);
  }
}
