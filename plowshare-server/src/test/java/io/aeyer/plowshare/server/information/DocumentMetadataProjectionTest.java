package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.protocol.InformationNavigation.Document;
import io.aeyer.plowshare.protocol.InformationNavigation.Documents;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Request contracts, transport compatibility and statement budgets; SQL authority has DB coverage.
 */
class DocumentMetadataProjectionTest {
  final InformationContext context =
      new InformationContext(
              "reader",
              new InformationContext.Selection(InformationContext.Scope.PROJECT, "research", false))
          .withView(true);
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final JdbcInformationNavigationRepository repository =
      new JdbcInformationNavigationRepository(jdbc, mock(ProjectMembers.class));
  final List<String> statements = new ArrayList<>();
  final UUID resource = UUID.randomUUID(),
      revision = UUID.randomUUID(),
      type = UUID.randomUUID(),
      version = UUID.randomUUID();

  @Test
  void invalidProjectionAndUnsupportedViewFailAtTheBoundary() {
    for (var fields :
        List.of(
            Map.<String, Object>of(
                "scope", Map.of("kind", "personal"), "projection", "metadata-v1"),
            Map.<String, Object>of(
                "scope",
                Map.of("kind", "personal"),
                "view",
                "content-types-v1",
                "projection",
                "everything"),
            Map.<String, Object>of(
                "scope",
                Map.of("kind", "personal"),
                "view",
                "content-types-v1",
                "projection",
                true)))
      assertThrows(
          CallerFault.class, () -> InformationNavigationInputs.command("documents.list", fields));
    var accepted =
        assertInstanceOf(
            InformationRequest.DocumentList.class,
            InformationNavigationInputs.command(
                "documents.list",
                Map.of(
                    "scope",
                    Map.of("kind", "personal"),
                    "view",
                    "content-types-v1",
                    "projection",
                    "metadata-v1")));
    assertEquals("metadata-v1", accepted.projection());
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.documentsWithMetadata(context, null, null, null, 101, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.documentsWithMetadata(context, null, "related", null, 20, 0));
    verifyNoInteractions(jdbc);
  }

  @Test
  void legacyHeadersKeepTheirWireShapeAndMetadataNeedsAnExplicitMarker() throws Exception {
    var header =
        new Document(
            resource,
            revision,
            "Title",
            "manual.md",
            List.of("manual"),
            new InformationRequest.Scope("project", "research", false),
            "reader");
    var json = JsonMapper.builder().build();
    String encoded = json.writeValueAsString(new Documents(List.of(header), 1, 0));
    assertFalse(encoded.contains("contentMetadata"));
    assertFalse(encoded.contains("projection"));
    var metadata =
        new InformationContent.Metadata(
            new InformationContent.Identity(
                type, version, 1, "incident", "Incident", "a".repeat(64)),
            SchemaCodec.fingerprint(DocumentMetadata.empty()),
            DocumentMetadata.empty());
    var projected =
        new Document(
            resource,
            revision,
            "Title",
            "manual.md",
            List.of(),
            header.scope(),
            "reader",
            metadata);
    assertThrows(IllegalArgumentException.class, () -> new Documents(List.of(projected), 1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InformationContent.Metadata(metadata.type(), "b".repeat(64), metadata.metadata()));
    String page = json.writeValueAsString(new Documents(List.of(projected), 1, 0, "metadata-v1"));
    assertTrue(page.contains("contentMetadata"));
    assertFalse(page.contains("schema"));
    assertFalse(page.contains("extractedText"));
  }

  @Test
  void oneStatementMapsBoundedMetadataWithoutSchemasOrPerRowReads() throws Exception {
    var row = row();
    when(row.getObject("type_version", UUID.class)).thenReturn(version);
    when(row.getObject("type_id", UUID.class)).thenReturn(type);
    when(row.getInt("ordinal")).thenReturn(1);
    when(row.getString("slug")).thenReturn("incident");
    when(row.getString("label")).thenReturn("Incident");
    when(row.getString("definition_hash")).thenReturn("a".repeat(64));
    var fields =
        new SchemaValue.ObjectValue(
            Map.of("severity", new SchemaValue.Text("high"), "note", new SchemaValue.Null()));
    when(row.getString("metadata_hash")).thenReturn(SchemaCodec.fingerprint(fields));
    when(row.getString("metadata_json")).thenReturn(SchemaCodec.compactJson(fields));
    reads(row, 50);
    var result = repository.documentsWithMetadata(context, null, null, null, 50, 0);
    assertEquals(50, result.documents().size());
    assertEquals(fields, result.documents().getFirst().contentMetadata().metadata());
    assertEquals("metadata-v1", result.projection());
    assertEquals(1, statements.size());
    String query = statements.getFirst();
    assertEquals(1, query.split("knowledge_readable_selection", -1).length - 1);
    assertTrue(
        query.indexOf("LIMIT ? OFFSET ?")
            < query.indexOf("LEFT JOIN information_revision_content"));
    assertTrue(query.contains("AND NOT c.redacted"));
    assertFalse(query.contains("schema_definition"));
    assertFalse(query.contains("information_projection_reuse"));
    assertFalse(query.contains("extracted_text"));
    verify(jdbc)
        .query(
            eq(query),
            org.mockito.ArgumentMatchers.<RowMapper<Document>>any(),
            any(Object[].class));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void absentBindingsRemainAbsentAndOversizedPagesFailBeforeMetadataDecoding() throws Exception {
    var row = row();
    reads(row, 1);
    assertNull(
        repository
            .documentsWithMetadata(context, null, null, null, 1, 0)
            .documents()
            .getFirst()
            .contentMetadata());
    when(row.getLong("metadata_bytes")).thenReturn(524289L);
    assertThrows(
        CallerFault.class, () -> repository.documentsWithMetadata(context, null, null, null, 1, 0));
    verify(row, never()).getString("metadata_json");
  }

  ResultSet row() throws Exception {
    var row = mock(ResultSet.class);
    when(row.getObject("resource", UUID.class)).thenReturn(resource);
    when(row.getObject("revision", UUID.class)).thenReturn(revision);
    when(row.getString("title")).thenReturn("Discovery title");
    when(row.getString("source_name")).thenReturn("manual.md");
    when(row.getString("owner")).thenReturn("reader");
    when(row.getString("tags")).thenReturn("[\"manual\"]");
    when(row.getString("visibility")).thenReturn("home");
    when(row.getString("project_name")).thenReturn("research");
    return row;
  }

  void reads(ResultSet row, int count) {
    doAnswer(
            call -> {
              statements.add(call.getArgument(0));
              RowMapper<Document> mapper = call.getArgument(1);
              var documents = new ArrayList<Document>();
              for (int i = 0; i < count; i++) documents.add(mapper.mapRow(row, i));
              return documents;
            })
        .when(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<Document>>any(),
            any(Object[].class));
  }
}
