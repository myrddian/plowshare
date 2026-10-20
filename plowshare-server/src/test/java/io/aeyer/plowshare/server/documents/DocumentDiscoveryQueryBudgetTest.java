package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.information.InformationAccess;
import io.aeyer.plowshare.server.information.InformationContext;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

/** Discovery shares authority within each SQL statement; targeted reads keep their narrow fence. */
class DocumentDiscoveryQueryBudgetTest {
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final InformationAccess access = mock(InformationAccess.class);
  final InformationContext context =
      new InformationContext("reader", InformationContext.Selection.project("one"))
          .within(KnowledgeReadScope.homeOnly(Home.of("one")));

  DocumentStore reader() {
    when(access.admitted(context)).thenReturn(context);
    return new DocumentStore(jdbc, UnitOfWork.NONE).scoped(access, context);
  }

  @Test
  void listingBindsOneAuthoritySelectionBeforePagingAndPreservesItsContentAndReportFences() {
    reader().page("alpha", 10, 20);
    var sql = ArgumentCaptor.forClass(String.class);
    var args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).query(sql.capture(), ArgumentMatchers.<RowMapper<Object>>any(), args.capture());
    verifyNoMoreInteractions(jdbc);
    assertDiscovery(sql.getValue());
    var bound = Arrays.asList(args.getValue());
    assertEquals(List.of("%alpha%", "%alpha%"), bound.subList(0, 2));
    assertEquals("reader", bound.get(2));
    assertEquals("project", bound.get(3));
    assertEquals("one", bound.get(4));
    assertEquals("one", bound.get(6));
    assertEquals(false, bound.get(7));
    assertEquals(false, bound.get(bound.size() - 3), "Legacy content remains excluded");
    assertEquals(List.of(10, 20), bound.subList(bound.size() - 2, bound.size()));
    assertTrue(sql.getValue().contains("report.status<>'final'"));
  }

  @Test
  void totalsAndPreEmbeddingCoverageUseTheBatchedSelectionToo() {
    var reader = reader();
    assertEquals(0, reader.count(null));
    reader.coverage();
    var scalar = ArgumentCaptor.forClass(String.class);
    var coverage = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForObject(scalar.capture(), eq(Integer.class), any(Object[].class));
    verify(jdbc)
        .query(
            coverage.capture(),
            ArgumentMatchers.<ResultSetExtractor<DocumentStore.Coverage>>any(),
            any(Object[].class));
    verifyNoMoreInteractions(jdbc);
    assertDiscovery(scalar.getValue());
    assertDiscovery(coverage.getValue());
  }

  @Test
  void targetedReadDoesNotExpandToAWholeCorpusSelection() {
    assertTrue(reader().find(UUID.randomUUID()).isEmpty());
    assertTrue(reader().find("named source").isEmpty());
    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, times(2))
        .query(sql.capture(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class));
    for (String query : sql.getAllValues()) {
      assertFalse(query.contains("knowledge_readable_selection"));
      assertTrue(query.contains("information_readable(information_row.id"));
    }
    assertTrue(sql.getValue().contains("report.status<>'final'"));
  }

  @Test
  void retrievalPreflightUsesBoundedExistenceInTheActualCorpusAndDocumentSelection() {
    var reader = reader();
    assertFalse(reader.hasRetrievable(null));
    UUID document = UUID.randomUUID();
    assertFalse(reader.hasRetrievable(document));
    var sql = ArgumentCaptor.forClass(String.class);
    var args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, times(2)).queryForObject(sql.capture(), eq(Boolean.class), args.capture());
    verifyNoMoreInteractions(jdbc);
    for (String query : sql.getAllValues()) {
      assertTrue(query.startsWith("SELECT EXISTS(SELECT 1 FROM chunks"));
      assertFalse(query.contains("count(*)"));
      assertFalse(query.contains("ORDER BY"));
      assertFalse(query.contains("<=>"));
    }
    assertDiscovery(sql.getAllValues().getFirst());
    assertFalse(sql.getValue().contains("knowledge_readable_selection"));
    assertTrue(sql.getValue().contains("p.document_id=?"));
    assertEquals(document, args.getValue()[0]);
  }

  private static void assertDiscovery(String sql) {
    assertEquals(1, sql.split("knowledge_readable_selection", -1).length - 1);
    assertFalse(sql.contains("information_readable("));
    assertTrue(sql.contains("information_row.id IN (SELECT revision"));
    assertTrue(sql.contains("information_revision_content"));
    assertTrue(sql.contains("processing_policy='explicit_only'"));
  }
}
