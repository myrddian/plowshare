package io.aeyer.plowshare.server.acting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.applications.ApplicationIdentityRepository;
import java.sql.Array;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcActingTokenReadsTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final JdbcActingRepository repository =
      new JdbcActingRepository(jdbc, mock(ApplicationIdentityRepository.class), () -> true);
  private final List<String> statements = new ArrayList<>();
  private final List<Object> bindings = new ArrayList<>();

  @Test
  void listingHydratesEveryScopeInOneSubjectBoundStatementWithoutSelectingProofs()
      throws Exception {
    var first = row(false, "research");
    var second = row(true, "research", "manual");
    returning(first, second);
    var tokens = repository.tokens("alice");
    assertEquals(2, tokens.size());
    assertEquals(Set.of("research", "manual"), tokens.get(1).scope().projects());
    assertFalse(tokens.getFirst().revoked());
    assertTrue(tokens.get(1).revoked());
    assertThrows(UnsupportedOperationException.class, () -> tokens.clear());
    assertThrows(
        UnsupportedOperationException.class, () -> tokens.get(1).scope().projects().clear());
    assertEquals(List.of("alice"), bindings);
    assertEquals(1, statements.size());
    String sql = statements.getFirst();
    assertTrue(sql.endsWith("WHERE t.subject=? ORDER BY t.created_at DESC,t.id LIMIT 256"));
    assertTrue(sql.contains("WHERE s.token_id=t.id ORDER BY p.name LIMIT 257"));
    assertFalse(sql.contains("digest"));
    assertFalse(sql.contains("external_subject"));
    verify(first.getArray("projects")).free();
    verify(second.getArray("projects")).free();
    verify(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<ActingRepository.Token>>any(),
            any(Object[].class));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void directAndDigestLookupsUseTheSameSingleStatementProjection() throws Exception {
    returning(row(false, "research"));
    UUID id = UUID.randomUUID();
    String digest = "a".repeat(64);
    assertTrue(repository.token(id).isPresent());
    assertTrue(repository.proof(digest).isPresent());
    assertEquals(List.of(id, digest), bindings);
    assertTrue(statements.getFirst().endsWith("WHERE t.id=?"));
    assertTrue(statements.getLast().endsWith("WHERE t.digest=?"));
    assertFalse(
        statements
            .getLast()
            .substring(0, statements.getLast().indexOf("FROM acting_tokens t"))
            .contains("digest"));
    assertThrows(IllegalArgumentException.class, () -> repository.proof("bad"));
    assertEquals(2, statements.size());
  }

  @Test
  void corruptOversizedScopeFailsClosedAndFreesTheJdbcArray() throws Exception {
    var corrupt = row(false, IntStream.range(0, 257).mapToObj(i -> "p" + i).toArray(String[]::new));
    returning(corrupt);
    assertThrows(IllegalArgumentException.class, () -> repository.tokens("alice"));
    verify(corrupt.getArray("projects")).free();
    assertEquals(1, statements.size());
  }

  @Test
  void databaseFailureIsNotReplayed() {
    when(jdbc.query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<ActingRepository.Token>>any(),
            any(Object[].class)))
        .thenThrow(new DataAccessResourceFailureException("unavailable"));
    assertThrows(DataAccessResourceFailureException.class, () -> repository.tokens("alice"));
    verify(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<ActingRepository.Token>>any(),
            any(Object[].class));
  }

  private void returning(ResultSet... rows) {
    doAnswer(
            call -> {
              statements.add(call.getArgument(0));
              bindings.add(call.getArgument(2));
              RowMapper<ActingRepository.Token> mapper = call.getArgument(1);
              var tokens = new ArrayList<ActingRepository.Token>();
              for (var row : rows) tokens.add(mapper.mapRow(row, tokens.size()));
              return tokens;
            })
        .when(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<ActingRepository.Token>>any(),
            any(Object[].class));
  }

  private ResultSet row(boolean revoked, String... projects) throws Exception {
    var row = mock(ResultSet.class);
    var array = mock(Array.class);
    when(array.getArray()).thenReturn(projects);
    when(row.getArray("projects")).thenReturn(array);
    when(row.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
    when(row.getString("application")).thenReturn("bridge");
    when(row.getString("actor")).thenReturn("@service/" + UUID.randomUUID());
    when(row.getString("grant_name")).thenReturn("users");
    when(row.getString("subject")).thenReturn("alice");
    when(row.getString("ceiling")).thenReturn("CONTRIBUTOR");
    when(row.getObject("expires_at", OffsetDateTime.class))
        .thenReturn(OffsetDateTime.parse("2026-10-20T00:00:00Z"));
    when(row.getBoolean("revoked")).thenReturn(revoked);
    return row;
  }
}
