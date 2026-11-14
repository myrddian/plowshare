package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Protect narrow authorization projections and the one-statement authorized extraction budget. */
class JdbcInformationCatalogueReadBudgetTest {
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final JdbcInformationCatalogueRepository repository =
      new JdbcInformationCatalogueRepository(
          jdbc, Clock.systemUTC(), new RecordedInformationEvents());
  final InformationContext context =
      new InformationContext("reader", InformationContext.Selection.project("one"))
          .withView(true)
          .within(KnowledgeReadScope.homeOnly(Home.of("one")));
  final UUID revision = UUID.randomUUID(), resource = UUID.randomUUID();

  /**
   * The fallback of a single-revision read: one statement-local admission over a one-element target
   * list joined to the row, never the per-row {@code information_readable} predicate.
   */
  void assertSelection(String sql, Object[] arguments) {
    var content = InformationSql.content(context, "r");
    assertTrue(
        sql.startsWith(
            "WITH admitted AS MATERIALIZED (SELECT revision FROM knowledge_readable_targets("),
        sql);
    assertTrue(
        sql.contains(
            " JOIN admitted ON admitted.revision=r.id WHERE r.availability='active' AND "
                + content.sql()
                + " AND r.id=?"),
        sql);
    assertFalse(sql.contains("information_readable("), "no per-row permission predicate");
    var bound = Arrays.asList(arguments);
    assertEquals(9, bound.size(), "seven target arguments, the typed view, the revision");
    assertEquals(context.account(), bound.getFirst());
    assertEquals("one", bound.get(4), "the read ceiling's home project");
    assertEquals("{" + revision + "}", bound.get(6));
    assertEquals(content.arguments(), bound.subList(7, 8));
    assertEquals(revision, bound.getLast());
  }

  /** A check that has decided {@code readable}, fetching only then, as the cached check does. */
  static KnowledgeReadabilityCheck decided(boolean readable) {
    return new KnowledgeReadabilityCheck() {
      public java.util.Optional<Boolean> readable(InformationContext context, UUID revision) {
        return java.util.Optional.of(readable);
      }

      public <T> Guarded<T> fetchIfReadable(
          InformationContext context,
          UUID revision,
          java.util.function.Supplier<java.util.Optional<T>> fetch) {
        return new Guarded.Answered<>(readable ? fetch.get() : java.util.Optional.empty());
      }
    };
  }

  JdbcInformationCatalogueRepository with(KnowledgeReadabilityCheck check) {
    return new JdbcInformationCatalogueRepository(
        jdbc,
        Clock.systemUTC(),
        mock(io.aeyer.plowshare.server.allowances.AllowanceRepository.class),
        io.aeyer.plowshare.server.cache.ProjectionCache.direct(),
        io.aeyer.plowshare.server.cache.ProjectionCache.direct(),
        io.aeyer.plowshare.server.cache.ProjectionCache.direct(),
        io.aeyer.plowshare.server.cache.ProjectionCache.direct(),
        io.aeyer.plowshare.server.cache.ProjectionCache.direct(),
        check,
        new RecordedInformationEvents());
  }

  @Test
  void aRevisionDecidedUnreadableIsAbsentWithoutAnyRowStatement() {
    var decided = with(decided(false));
    assertTrue(decided.authorizedMetadata(context, revision).isEmpty());
    assertTrue(decided.authorizedText(context, revision).isEmpty());
    assertTrue(decided.textBytes(context, revision).isEmpty());
    assertThrows(
        io.aeyer.plowshare.server.cache.ProjectionCache.Bypass.class,
        () -> decided.loadText(context, revision, 4096L),
        "a bounded load never caches an absence");
    verifyNoInteractions(jdbc);
  }

  @Test
  void aRevisionDecidedReadableIsFetchedByIdWithoutPermissionSql() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(12L));
    var decided = with(decided(true));
    assertTrue(decided.authorizedMetadata(context, revision).isEmpty());
    assertEquals(java.util.Optional.of(12L), decided.textBytes(context, revision));
    assertThrows(
        io.aeyer.plowshare.server.cache.ProjectionCache.Bypass.class,
        () -> decided.loadMetadata(context, revision, 2048L));
    var sql = ArgumentCaptor.forClass(String.class);
    var args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, times(2)).queryForList(sql.capture(), args.capture());
    verify(jdbc).queryForList(sql.capture(), eq(Long.class), args.capture());
    var statements = sql.getAllValues();
    assertEquals(
        JdbcInformationCatalogueRepository.SELECT + " WHERE r.availability='active' AND r.id=?",
        statements.get(0));
    assertEquals(
        "WITH projected AS MATERIALIZED ("
            + JdbcInformationCatalogueRepository.SELECT
            + " WHERE r.availability='active' AND r.id=?)"
            + " SELECT * FROM projected WHERE octet_length(to_jsonb(projected)::text)<=?",
        statements.get(1));
    assertEquals(
        "SELECT "
            + JdbcInformationCatalogueRepository.TEXT_BYTES
            + " FROM information_revision_allowances r WHERE r.availability='active' AND r.id=?",
        statements.get(2));
    for (String statement : statements) {
      assertFalse(statement.contains("information_readable("), statement);
      assertFalse(statement.contains("knowledge_"), statement);
      assertFalse(statement.contains("typed_content"), "typed content is decided in code");
    }
    assertEquals(List.of(revision), Arrays.asList(args.getAllValues().get(0)));
    assertEquals(List.of(revision, 2048L), Arrays.asList(args.getAllValues().get(1)));
    assertEquals(List.of(revision), Arrays.asList(args.getAllValues().get(2)));
  }

  @Test
  void readabilityDoesNotHydrateDiscardedMetadataOrExtraction() {
    when(jdbc.queryForList(anyString(), eq(Integer.class), any(Object[].class)))
        .thenReturn(List.of(1));
    assertTrue(repository.readable(context, revision));
    var sql = ArgumentCaptor.forClass(String.class);
    var args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForList(sql.capture(), eq(Integer.class), args.capture());
    verifyNoMoreInteractions(jdbc);
    assertTrue(
        sql.getValue()
            .startsWith(
                "WITH admitted AS MATERIALIZED (SELECT revision FROM knowledge_readable_targets("));
    assertFalse(
        sql.getValue().contains("information_readable("),
        "a targeted check must not resolve authority per dependency");
    assertFalse(sql.getValue().contains("extracted_text"));
    assertFalse(sql.getValue().contains("information_content_type_versions"));
    var content = InformationSql.content(context, "r");
    assertTrue(sql.getValue().endsWith(" AND " + content.sql() + " AND r.id=?"));
    var bound = Arrays.asList(args.getValue());
    assertEquals(context.account(), bound.getFirst());
    assertEquals("{" + revision + "}", bound.get(6));
    assertEquals(revision, bound.getLast());
  }

  @Test
  void metadataAdmissionUsesOneStatementWithoutExtractedTextAndDenialReturnsNoSnapshot() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    assertTrue(repository.authorizedMetadata(context, revision).isEmpty());
    var sql = ArgumentCaptor.forClass(String.class);
    var args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForList(sql.capture(), args.capture());
    verifyNoMoreInteractions(jdbc);
    assertFalse(sql.getValue().contains("extracted_text"));
    assertSelection(sql.getValue(), args.getValue());
  }

  @Test
  void extractionMapsItsIdentityInTheSameStatementAsCurrentAuthorization() throws Exception {
    var result = mock(ResultSet.class);
    when(result.getObject(1, UUID.class)).thenReturn(revision);
    when(result.getObject(2, UUID.class)).thenReturn(resource);
    when(result.getString(3)).thenReturn("code");
    when(result.getString(4)).thenReturn("java");
    when(result.getString(5)).thenReturn("legacy-source-fingerprint");
    when(result.getString(6)).thenReturn("extraction-fingerprint");
    when(result.getLong(7)).thenReturn(3L);
    when(result.getString(8)).thenReturn("retained text");
    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
        .thenAnswer(
            call -> {
              RowMapper<Object> mapper = call.getArgument(1);
              return List.of(mapper.mapRow(result, 0));
            });
    var text = repository.authorizedText(context, revision).orElseThrow();
    assertEquals(context, text.context());
    assertEquals(resource, text.resource());
    assertEquals("legacy-source-fingerprint", text.contentHash());
    assertEquals("extraction-fingerprint", text.textHash());
    assertEquals(3, text.processingGeneration());
    assertEquals("retained text", text.text());
    var sql = ArgumentCaptor.forClass(String.class);
    var args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).query(sql.capture(), ArgumentMatchers.<RowMapper<Object>>any(), args.capture());
    verifyNoMoreInteractions(jdbc);
    assertSelection(sql.getValue(), args.getValue());
  }
}
