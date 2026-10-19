package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.embedding.EmbeddingWorkRepository;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Bounded set operations and projection budgets; actual PostgreSQL semantics have tagged tests. */
class InformationRepositoryQueryReuseTest {
  @Test
  void logStateJoinsProjectOnceAndKeepsPersonalAndProjectBindingExplicit() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var row = mock(ResultSet.class);
    when(row.getString(6)).thenReturn("project");
    when(row.getString(1)).thenReturn("log");
    when(row.getInt(2)).thenReturn(10);
    when(row.getInt(3)).thenReturn(3);
    when(row.getString(4)).thenReturn("session");
    when(row.getBoolean(5)).thenReturn(true);
    var arguments = new ArrayList<List<Object>>();
    when(jdbc.queryForObject(
            anyString(),
            ArgumentMatchers.<RowMapper<InformationStageRepository.LogState>>any(),
            any(Object[].class)))
        .thenAnswer(
            call -> {
              assertTrue(call.<String>getArgument(0).contains("LEFT JOIN projects p ON p.id=?"));
              arguments.add(Arrays.asList(((Object[]) call.getRawArguments()[2]).clone()));
              return call.<RowMapper<InformationStageRepository.LogState>>getArgument(1)
                  .mapRow(row, 0);
            });
    var repository = new JdbcInformationStageRepository(jdbc);
    UUID revision = UUID.randomUUID();
    assertEquals("project", repository.logState(revision, 7L).project());
    repository.logState(revision, null);
    assertEquals(List.of(7L, revision, 7L), arguments.getFirst());
    assertEquals(Arrays.asList(null, revision, null), arguments.getLast());
    verify(jdbc, times(2))
        .queryForObject(
            anyString(),
            ArgumentMatchers.<RowMapper<InformationStageRepository.LogState>>any(),
            any(Object[].class));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void recursiveInputBindingUsesOneBoundCohortAndPreservesEmptyAndLargeInputs() {
    var jdbc = mock(JdbcTemplate.class);
    var repository = new JdbcJobInformationRepository(jdbc);
    var context = new InformationContext("owner", InformationContext.Selection.personal());
    repository.bind("job", context, List.of());
    verifyNoInteractions(jdbc);
    var ids = java.util.stream.IntStream.range(0, 257).mapToObj(i -> UUID.randomUUID()).toList();
    repository.bind("job", context, ids);
    var sql = org.mockito.ArgumentCaptor.forClass(String.class);
    var arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, times(2)).update(sql.capture(), arguments.capture());
    assertTrue(
        sql.getAllValues().stream()
            .allMatch(value -> value.contains("CROSS JOIN selected s ON CONFLICT DO NOTHING")));
    assertTrue(
        sql.getValue().contains("UNION SELECT e.parent"),
        "Recursive UNION must still terminate cycles");
    assertEquals(InformationJson.json(ids.subList(0, 256)), arguments.getAllValues().getFirst()[1]);
    assertEquals(InformationJson.json(ids.subList(256, 257)), arguments.getValue()[1]);
  }

  private InformationLifecycle.Lease lease(String stage) {
    return new InformationLifecycle.Lease(
        UUID.randomUUID(), UUID.randomUUID(), 2, stage, 1, UUID.randomUUID(), "owner", null);
  }

  @Test
  void chunkOwnershipIsOneSetCheckAndRejectsAnyMissingOrForeignChunk() {
    var jdbc = mock(JdbcTemplate.class);
    var repository =
        new JdbcInformationProcessingRepository(
            jdbc, Clock.systemUTC(), mock(ProjectMembers.class));
    var lease = lease("embed");
    var first =
        EmbeddingWorkRepository.Key.of(
            EmbeddingWorkRepository.Store.CHUNKS, UUID.randomUUID().toString());
    var second =
        EmbeddingWorkRepository.Key.of(
            EmbeddingWorkRepository.Store.CHUNKS, UUID.randomUUID().toString());
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(2L)
        .thenReturn(1L);
    repository.requireEmbeddingTargets(lease, List.of(first, second, first));
    assertThrows(
        IllegalStateException.class,
        () -> repository.requireEmbeddingTargets(lease, List.of(first, second)));
    verify(jdbc, times(2))
        .queryForObject(
            contains("c.id IN (SELECT value::uuid"),
            eq(Long.class),
            eq(lease.revision()),
            eq(
                InformationJson.json(
                    List.of(UUID.fromString(first.id()), UUID.fromString(second.id())))));
    assertThrows(
        IllegalStateException.class,
        () -> repository.requireEmbeddingTargets(lease("summary_embed"), List.of(first)));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void unresolvedEmbeddingChecksOneArrayOverlapAndDoesNoQueryForEmptyTargets() {
    var jdbc = mock(JdbcTemplate.class);
    var repository =
        new JdbcInformationProcessingRepository(
            jdbc, Clock.systemUTC(), mock(ProjectMembers.class));
    var lease = lease("embed");
    var targets =
        List.of(
            EmbeddingWorkRepository.Key.of(
                EmbeddingWorkRepository.Store.CHUNKS, UUID.randomUUID().toString()),
            EmbeddingWorkRepository.Key.of(
                EmbeddingWorkRepository.Store.CHUNKS, UUID.randomUUID().toString()));
    repository.requireEmbeddingClear(lease, List.of());
    verifyNoInteractions(jdbc);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
    assertThrows(
        IllegalStateException.class, () -> repository.requireEmbeddingClear(lease, targets));
    verify(jdbc)
        .queryForObject(
            contains("preparation_targets && ARRAY"),
            eq(Boolean.class),
            eq(lease.revision()),
            eq(lease.generation()),
            anyString());
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void sweepKeepsLiveAuthorityButResetsOneBoundSetInsteadOfOneUpdatePerRow() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
        .thenAnswer(
            call -> {
              RowMapper<Object> mapper = call.getArgument(1);
              var result = new ArrayList<Object>();
              for (UUID id : ids) {
                var row = mock(ResultSet.class);
                when(row.getObject("id", UUID.class)).thenReturn(id);
                when(row.getLong("generation")).thenReturn(2L);
                when(row.getString("owner_handle")).thenReturn("owner");
                when(row.getString("project_name")).thenReturn("project");
                result.add(mapper.mapRow(row, result.size()));
              }
              return result;
            });
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
    var repository =
        new JdbcInformationProcessingRepository(
            jdbc, Clock.systemUTC(), mock(ProjectMembers.class));
    assertEquals(3, repository.sweepUntagged().size());
    var bound = org.mockito.ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(contains("FROM (VALUES (?::uuid,?::bigint)"), bound.capture());
    assertEquals(
        List.of(ids.get(0), 2L, ids.get(1), 2L, ids.get(2), 2L, "autoTag"),
        Arrays.asList(bound.getValue()));
    verify(jdbc)
        .queryForObject("SELECT information_system_scope_valid(?)", Boolean.class, "project");
  }

  private Map<String, Object> acquisitionRow() {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", UUID.randomUUID());
    row.put("url", "https://fixture.invalid/item");
    row.put("source_name", "item");
    row.put("corpus", "documents");
    row.put("state", "queued");
    row.put("attempt", 0);
    row.put("allowance_total", 10);
    row.put("created_at", Timestamp.from(Instant.EPOCH));
    row.put("account", "owner");
    row.put("request_id", UUID.randomUUID());
    return row;
  }

  @Test
  void acquisitionListsDecodeOnlyStatusAndIgnoreUnusedMalformedContentIntent() {
    var jdbc = mock(JdbcTemplate.class);
    var row = acquisitionRow();
    row.put("content_intent", "not JSON; the list never selects this field");
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
    var result =
        new JdbcAcquisitionRepository(jdbc, Clock.systemUTC())
            .list("owner", null, "documents", 20, 0);
    assertEquals("queued", result.getFirst().state());
    verify(jdbc)
        .queryForList(
            argThat(
                sql ->
                    !sql.contains("SELECT *")
                        && !sql.contains("content_intent")
                        && !sql.contains("caller_session")),
            eq("owner"),
            isNull(),
            eq("documents"),
            eq(0),
            eq(20));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void acquisitionNewSubmitReturnsDatabaseDefaultsWithoutOwnedRefetch() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
    when(jdbc.queryForMap(startsWith("INSERT INTO information_acquisitions"), any(Object[].class)))
        .thenReturn(acquisitionRow());
    var result =
        new JdbcAcquisitionRepository(jdbc, Clock.systemUTC())
            .submitRetained(
                new InformationContext("owner", InformationContext.Selection.personal()),
                UUID.randomUUID(),
                "a".repeat(64),
                "https://fixture.invalid/item",
                "item",
                null,
                10,
                "documents",
                null);
    assertEquals(Instant.EPOCH, result.status().createdAt());
    verify(jdbc).queryForMap(endsWith("RETURNING *"), any(Object[].class));
    verify(jdbc, never()).queryForList(contains("WHERE id=? AND account=?"), any(Object[].class));
  }

  @Test
  void newGateClaimReturnsInsertedTokenStateWithoutReceiptReread() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    var stored = new LinkedHashMap<String, Object>();
    stored.put("token", UUID.randomUUID());
    stored.put("state", "reserved");
    when(jdbc.queryForMap(startsWith("INSERT INTO information_write_gates"), any(Object[].class)))
        .thenReturn(stored);
    var receipt =
        new JdbcInformationGateRepository(jdbc, Clock.systemUTC())
            .claim(
                "owner",
                UUID.randomUUID(),
                "a".repeat(64),
                "intake",
                InformationCatalogue.Admission.class);
    assertEquals(stored.get("token"), receipt.token());
    assertEquals(InformationGateRepository.State.RESERVED, receipt.state());
    verify(jdbc)
        .queryForMap(endsWith("RETURNING token,state,response,log_id"), any(Object[].class));
    verify(jdbc, never()).queryForMap(startsWith("SELECT"), any(Object[].class));
  }
}
