package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Statement budgets and typed result contracts; SQL semantics are tested separately on PostgreSQL.
 */
class JdbcInformationFacetsTest {
  private record Aggregate(int kind, String facet, String category, String value, long count) {}

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private String statement;
  private Object[] arguments;

  private void returns(List<Aggregate> rows) {
    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
        .thenAnswer(
            call -> {
              statement = call.getArgument(0);
              arguments = ((Object[]) call.getRawArguments()[2]).clone();
              RowMapper<Object> mapper = call.getArgument(1);
              var mapped = new ArrayList<Object>();
              for (int index = 0; index < rows.size(); index++)
                mapped.add(mapper.mapRow(result(rows.get(index)), index));
              return mapped;
            });
  }

  private static ResultSet result(Aggregate row) throws SQLException {
    var result = mock(ResultSet.class);
    when(result.getInt("row_kind")).thenReturn(row.kind());
    when(result.getString("facet_name")).thenReturn(row.facet());
    when(result.getString("category")).thenReturn(row.category());
    when(result.getString("value")).thenReturn(row.value());
    when(result.getLong("result_count")).thenReturn(row.count());
    return result;
  }

  private void verifySingleStatement() {
    verify(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class));
    verifyNoMoreInteractions(jdbc);
    assertTrue(statement.startsWith("WITH candidates AS MATERIALIZED ("));
    assertTrue(statement.endsWith("ORDER BY row_kind,facet_name,result_count DESC,category,value"));
    assertFalse(statement.contains("extracted_text AS"));
  }

  @Test
  void catalogueBindsSelectionAndPinnedRevisionsOnceForAllOrdinaryDimensions() {
    returns(List.of(new Aggregate(0, null, null, null, 0)));
    var context =
        new InformationContext("facet-account", InformationContext.Selection.personal())
            .withFacets(InformationInputs.facets(Map.of("tags", List.of("chosen"))));
    UUID pin = UUID.randomUUID();
    var facets =
        new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC())
            .facets(context, "source", List.of(pin));

    verifySingleStatement();
    assertArrayEquals(
        new Object[] {
          "facet-account",
          "personal",
          null,
          true,
          false,
          "document",
          "source",
          "[\"chosen\"]",
          "{" + pin + "}"
        },
        arguments);
    assertFalse(statement.contains("facet-account"));
    assertFalse(statement.contains(pin.toString()));
    assertFalse(statement.contains(" AS content_type"));
    assertEquals(0, facets.total());
    assertEquals(Set.copyOf(InformationFacets.NAMES), facets.facets().keySet());
    assertTrue(facets.facets().values().stream().allMatch(List::isEmpty));
    assertTrue(facets.hasMore().values().stream().noneMatch(Boolean::booleanValue));
    assertEquals(new Information.TagGraph(List.of(), false), facets.tagGraph());
  }

  @Test
  void navigationKeepsRelatedHeadAndNeighbourFiltersInTheSingleBoundSelection() {
    returns(List.of(new Aggregate(0, null, null, null, 0)));
    var context =
        new InformationContext("facet-account", InformationContext.Selection.personal())
            .withView(true);
    UUID related = UUID.randomUUID();
    var facets =
        new JdbcInformationNavigationRepository(jdbc, mock(ProjectMembers.class))
            .facets(context, related, "cites", "outgoing");

    verifySingleStatement();
    assertTrue(statement.contains("r.id=information_navigation_head(q.id)"));
    assertTrue(statement.contains("information_navigation_own_readable(r.id,?,?)"));
    assertTrue(statement.contains("information_reference_readable(r.id,?,?)"));
    assertTrue(statement.contains("e.source=? AND e.target=q.id AND e.relation=?"));
    assertTrue(statement.contains("LEFT JOIN information_revision_content facet_binding"));
    assertEquals(2, Arrays.stream(arguments).filter(related::equals).count());
    assertEquals("cites", arguments[arguments.length - 1]);
    assertFalse(statement.contains(related.toString()));
    assertFalse(statement.contains("'cites'"));
    assertEquals(10, facets.facets().size());
    assertTrue(facets.facets().containsKey("contentTypeVersion"));
  }

  @Test
  void typedCountsAndGraphKeepDatabaseOrderAndTrimOnlyTheSentinelRows() {
    var rows = new ArrayList<Aggregate>();
    rows.add(new Aggregate(0, null, null, null, 120));
    rows.add(new Aggregate(1, "contentType", null, "retained-type", 2));
    rows.add(new Aggregate(1, "tags", null, "database-first", 10));
    rows.add(new Aggregate(1, "tags", null, "database-second", 10));
    for (int index = 2; index < 101; index++)
      rows.add(new Aggregate(1, "tags", null, "tag-" + index, 1));
    for (int index = 0; index < 1001; index++)
      rows.add(new Aggregate(2, null, "category", "edge-" + index, 1));
    returns(rows);

    var facets =
        new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC())
            .facets(
                new InformationContext("facet-account", InformationContext.Selection.personal())
                    .withView(true),
                null,
                null);

    verifySingleStatement();
    assertEquals(120, facets.total());
    assertEquals(100, facets.facets().get("tags").size());
    assertEquals("database-first", facets.facets().get("tags").getFirst().value());
    assertEquals("database-second", facets.facets().get("tags").get(1).value());
    assertTrue(facets.hasMore().get("tags"));
    assertFalse(facets.hasMore().get("contentType"));
    assertEquals(
        List.of(new Information.FacetCount("retained-type", 2)),
        facets.facets().get("contentType"));
    assertEquals(1000, facets.tagGraph().edges().size());
    assertEquals("edge-999", facets.tagGraph().edges().getLast().tag());
    assertTrue(facets.tagGraph().hasMore());
  }

  @Test
  void aDatabaseFailureCannotBecomeAnEmptyFacetResult() {
    var failure = new DataAccessResourceFailureException("unavailable");
    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
        .thenThrow(failure);
    assertSame(
        failure,
        assertThrows(
            DataAccessResourceFailureException.class,
            () ->
                new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC())
                    .facets(
                        new InformationContext(
                            "facet-account", InformationContext.Selection.personal()),
                        null,
                        null)));
    verify(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class));
    verifyNoMoreInteractions(jdbc);
  }
}
