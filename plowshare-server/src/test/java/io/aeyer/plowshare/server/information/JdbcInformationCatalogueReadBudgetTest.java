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
      new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC());
  final InformationContext context =
      new InformationContext("reader", InformationContext.Selection.project("one"))
          .withView(true)
          .within(KnowledgeReadScope.homeOnly(Home.of("one")));
  final UUID revision = UUID.randomUUID(), resource = UUID.randomUUID();

  void assertSelection(String sql, Object[] arguments) {
    var selected = InformationSql.read(context, "r");
    assertTrue(sql.contains("r.availability='active' AND " + selected.sql() + " AND r.id=?"));
    var expected = new java.util.ArrayList<>(selected.arguments());
    expected.add(revision);
    assertEquals(expected, Arrays.asList(arguments));
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
    assertTrue(sql.getValue().startsWith("SELECT 1 FROM information_revision_allowances r WHERE "));
    assertFalse(sql.getValue().contains("extracted_text"));
    assertFalse(sql.getValue().contains("information_content_type_versions"));
    assertSelection(sql.getValue(), args.getValue());
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
