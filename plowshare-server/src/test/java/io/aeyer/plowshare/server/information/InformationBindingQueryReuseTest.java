package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import java.sql.ResultSet;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** A binding and its definition/header belong to one typed read snapshot. */
class InformationBindingQueryReuseTest {
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final InformationContentTypeRepository types = mock(InformationContentTypeRepository.class);
  final JdbcInformationContentRepository repository =
      new JdbcInformationContentRepository(jdbc, types);
  final UUID revision = UUID.randomUUID(), version = UUID.randomUUID(), type = UUID.randomUUID();
  final SchemaDefinition schema = SchemaDefinition.fromWire(Map.of("type", "object"));
  final SchemaValue.ObjectValue metadata = DocumentMetadata.empty();
  final List<String> statements = new ArrayList<>();
  ResultSet row;

  @BeforeEach
  void setup() throws Exception {
    row = mock(ResultSet.class);
    when(row.getObject("binding_revision", UUID.class)).thenReturn(revision);
    when(row.getBoolean("contract_available")).thenReturn(true);
    when(row.getObject("type_id", UUID.class)).thenReturn(type);
    when(row.getObject("id", UUID.class)).thenReturn(version);
    when(row.getInt("ordinal")).thenReturn(1);
    when(row.getString("slug")).thenReturn("memo");
    when(row.getString("label")).thenReturn("Memo");
    when(row.getString("description")).thenReturn("");
    when(row.getString("profile")).thenReturn(DocumentMetadata.PROFILE);
    when(row.getString("schema_definition")).thenReturn(SchemaCodec.compactJson(schema));
    when(row.getString("presentation")).thenReturn("{}");
    when(row.getString("metadata")).thenReturn("{}");
    when(row.getString("metadata_hash")).thenReturn(SchemaCodec.fingerprint(metadata));
    when(row.getString("definition_hash"))
        .thenReturn(SchemaCodec.documentFingerprint(schema, DocumentMetadata.PROFILE, metadata));
    when(row.getString("availability")).thenReturn("active");
    when(row.getString("processing_policy")).thenReturn("automatic");
    when(row.getLong("generation")).thenReturn(3L);
    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              statements.add(sql);
              if (!sql.contains("binding_revision")) return List.of();
              return List.of(call.<RowMapper<Object>>getArgument(1).mapRow(row, 0));
            });
    when(jdbc.queryForObject(
            anyString(),
            ArgumentMatchers.<RowMapper<InformationContent.View>>any(),
            any(Object[].class)))
        .thenAnswer(
            call -> {
              statements.add(call.getArgument(0));
              return call.<RowMapper<InformationContent.View>>getArgument(1).mapRow(row, 0);
            });
  }

  @Test
  void joinedBindingLoadsContractAndMetadataOnceWithoutNestedTypeRepositoryCalls() {
    var binding = repository.binding(revision).orElseThrow();
    assertEquals(version, binding.contract().version());
    assertEquals(metadata, binding.metadata());
    assertEquals(1, statements.size());
    assertTrue(statements.getFirst().contains("LEFT JOIN information_content_type_versions"));
    verifyNoInteractions(types);
  }

  @Test
  void viewUsesOneHeaderBindingReadPlusItsTwoBoundedStageLists() {
    var view = repository.view(revision);
    assertEquals(version, Objects.requireNonNull(view.contentType()).contract().version());
    assertEquals(3, statements.size());
    assertTrue(statements.getFirst().contains("r.processing_policy"));
    assertTrue(statements.getFirst().contains("binding_revision"));
    assertTrue(view.reused().isEmpty() && view.authorisedStages().isEmpty());
    verifyNoInteractions(types);
  }

  @Test
  void deletedViewsFailBeforeAnyStageOrMetadataChildReadsAndInvalidBindingsStillFailClosed()
      throws Exception {
    when(row.getString("availability")).thenReturn("deleted");
    assertThrows(
        io.aeyer.plowshare.server.faults.NotFoundFault.class, () -> repository.view(revision));
    assertEquals(1, statements.size());
    when(row.getBoolean("contract_available")).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> repository.binding(revision));
    verifyNoInteractions(types);
  }
}
