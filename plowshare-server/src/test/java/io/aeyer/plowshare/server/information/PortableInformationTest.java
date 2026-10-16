package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Export authorization must finish before any source bytes can leave a dependency closure. */
class PortableInformationTest {
  final InformationReadAccess catalogue = mock(InformationReadAccess.class);
  final InformationCatalogueRepository records = mock(InformationCatalogueRepository.class);
  final InformationContentRepository content = mock(InformationContentRepository.class);
  final InformationInterchangeRepository repository = mock(InformationInterchangeRepository.class);
  final InformationNavigationRepository navigation = mock(InformationNavigationRepository.class);
  final UnitOfWork work =
      new UnitOfWork() {
        public <T> T inTransaction(Supplier<T> action) {
          return action.get();
        }
      };
  final PortableInformation service =
      new PortableInformation(
          catalogue,
          records,
          content,
          mock(InformationContentTypes.class),
          new InformationContentSettings(true),
          repository,
          navigation,
          mock(InformationNavigationService.class),
          new InformationAccess(mock(ProjectMembers.class)),
          work,
          mock(InformationTransitions.class));
  final UUID revision = UUID.randomUUID(), resource = UUID.randomUUID();
  final InformationRequest.Selection selected = InformationRequest.Selection.personal();

  Information.Revision row(String subtype, String media, byte[] bytes) {
    var row = mock(Information.Revision.class);
    when(row.id()).thenReturn(revision);
    when(row.resourceId()).thenReturn(resource);
    when(row.ordinal()).thenReturn(1);
    when(row.sourceName()).thenReturn("source.md");
    when(row.title()).thenReturn("Source");
    when(row.documentType()).thenReturn("document");
    when(row.documentSubtype()).thenReturn(subtype);
    when(row.availability()).thenReturn("active");
    when(row.mediaType()).thenReturn(media);
    when(row.contentHash()).thenReturn(InformationInterchange.hash(bytes));
    when(row.tags()).thenReturn(List.of());
    when(row.author()).thenReturn("owner");
    when(row.kind()).thenReturn("source");
    when(row.createdAt()).thenReturn(Instant.parse("2026-10-16T00:00:00Z"));
    when(records.metadata(revision)).thenReturn(row);
    when(records.bytes(revision)).thenReturn(bytes);
    when(records.citations(revision)).thenReturn(List.of());
    when(content.binding(revision)).thenReturn(Optional.empty());
    when(repository.dependencies(revision)).thenReturn(List.of());
    when(repository.origin(revision)).thenReturn(Optional.empty());
    return row;
  }

  @Test
  void denied_required_dependency_refuses_the_whole_export_before_reading_bytes() {
    UUID hidden = UUID.randomUUID();
    when(repository.dependencies(revision)).thenReturn(List.of(hidden));
    doThrow(new NotFoundFault("unavailable")).when(catalogue).requireReadable(any(), eq(hidden));
    assertThrows(
        NotFoundFault.class,
        () ->
            service.execute(
                "owner",
                new InformationRequest.InterchangeExport(selected, List.of(revision)),
                null));
    verify(records, never()).bytes(any());
    verify(records, never()).metadata(any());
    verify(repository, never()).relationships(any(), anyList());
  }

  @Test
  void original_markdown_preserves_bytes_and_rejects_invalid_utf8() {
    byte[] body = "\uFEFF# Safety 🔎\r\nKeep exact bytes.\r\n".getBytes(StandardCharsets.UTF_8);
    row("markdown", "text/plain; charset=utf-8", body);
    var readable =
        assertInstanceOf(
                InformationResponse.InterchangeReadable.class,
                service.execute(
                    "owner", new InformationRequest.InterchangeReadable(selected, revision), null))
            .value();
    assertEquals("original_markdown", readable.representation());
    assertArrayEquals(body, readable.markdown().getBytes(StandardCharsets.UTF_8));
    row("markdown", "text/markdown", new byte[] {(byte) 0xc3, 0x28});
    assertThrows(
        CallerFault.class,
        () ->
            service.execute(
                "owner", new InformationRequest.InterchangeReadable(selected, revision), null));
  }

  @Test
  void non_markdown_extraction_is_labelled_and_original_bytes_are_not_substituted() {
    row("pdf", "application/pdf", new byte[] {1, 2, 3});
    when(records.text(revision)).thenReturn("Extracted text");
    var readable =
        assertInstanceOf(
                InformationResponse.InterchangeReadable.class,
                service.execute(
                    "owner", new InformationRequest.InterchangeReadable(selected, revision), null))
            .value();
    assertEquals("extraction", readable.representation());
    assertEquals("Extracted text", readable.markdown());
    when(records.text(revision)).thenReturn(null);
    assertThrows(
        CallerFault.class,
        () ->
            service.execute(
                "owner", new InformationRequest.InterchangeReadable(selected, revision), null));
  }

  @Test
  void origin_reads_recheck_access_before_touching_retained_foreign_metadata() {
    doThrow(new NotFoundFault("unavailable")).when(catalogue).requireReadable(any(), eq(revision));
    assertThrows(
        NotFoundFault.class,
        () ->
            service.execute(
                "owner", new InformationRequest.InterchangeOrigin(selected, revision), null));
    verify(repository, never()).origin(any());
  }
}
