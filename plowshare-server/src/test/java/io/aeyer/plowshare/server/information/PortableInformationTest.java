package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
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
          new InformationAccess(new EffectiveProjectPermissions(personalDefaults())),
          work,
          mock(InformationTransitions.class));
  final UUID revision = UUID.randomUUID(), resource = UUID.randomUUID();
  final InformationRequest.Selection selected = InformationRequest.Selection.personal();

  /**
   * A personal selection resolves to the account's personal project and is governed by that
   * project's permissions like any other. The grant is an account default: production's {@code
   * authority_project_role} answers MANAGER to an account on its own personal project.
   */
  static ProjectMembers personalDefaults(String... handles) {
    var members = mock(ProjectMembers.class);
    for (String handle : handles.length == 0 ? new String[] {"owner", "writer"} : handles)
      when(members.role(io.aeyer.plowshare.server.personal.PersonalSpaces.name(handle), handle))
          .thenReturn(Optional.of(ProjectRole.MANAGER));
    return members;
  }

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

  private static final InformationTransitions INLINE =
      new InformationTransitions() {
        @Override
        public <T extends InformationGateResult> T execute(
            InformationContext context,
            UUID request,
            String operation,
            InformationGateIdentity identity,
            List<UUID> sources,
            String session,
            Class<T> resultType,
            Supplier<T> transition) {
          return transition.get();
        }

        @Override
        public <T extends InformationGateResult> T executePrepared(
            InformationContext context,
            UUID request,
            String operation,
            InformationGateIdentity identity,
            List<UUID> sources,
            String session,
            Class<T> resultType,
            Supplier<Prepared<T>> prepare) {
          return prepare.get().transition().get();
        }
      };

  @Test
  void project_import_revises_another_creators_document_but_rechecks_mandatory_write_fences() {
    var members = mock(ProjectMembers.class);
    when(members.role("one", "writer")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    var importing =
        new PortableInformation(
            catalogue,
            records,
            content,
            mock(InformationContentTypes.class),
            new InformationContentSettings(true),
            repository,
            navigation,
            mock(InformationNavigationService.class),
            new InformationAccess(new EffectiveProjectPermissions(members)),
            work,
            INLINE);
    var context =
        new InformationContext("writer", InformationContext.Selection.project("one"))
            .withView(true);
    when(content.head("project:one", "shared.md", true))
        .thenReturn(
            Optional.of(
                new InformationContentRepository.Head(
                    resource, revision, "creator", "source", "active")));
    byte[] bytes = "imported body".getBytes(StandardCharsets.UTF_8);
    UUID foreignResource = UUID.randomUUID(),
        foreignRevision = UUID.randomUUID(),
        next = UUID.randomUUID();
    var descriptor =
        new InformationInterchange.Descriptor(
            foreignResource,
            foreignRevision,
            1,
            "foreign.md",
            "Foreign",
            "documents/foreign.md",
            "text/markdown",
            InformationInterchange.hash(bytes),
            List.of("imported"),
            null,
            new InformationInterchange.Provenance(
                "foreign-creator",
                "source",
                "2026-10-16T00:00:00Z",
                InformationInterchange.hash(bytes),
                List.of(),
                List.of(),
                null,
                null,
                null));
    var bundle =
        new InformationInterchange.Bundle(
            InformationInterchange.FORMAT,
            UUID.randomUUID(),
            List.of(
                new InformationInterchange.Document(
                    descriptor, Base64.getEncoder().encodeToString(bytes))),
            List.of(),
            InformationInterchange.COVERAGE);
    var request =
        new InformationRequest.InterchangeImport(
            new InformationRequest.Selection(
                new InformationRequest.Scope("project", "one", true),
                "documents",
                null,
                InformationContent.VIEW),
            UUID.randomUUID(),
            new InformationInterchange.ImportPlan(
                bundle,
                List.of(
                    new InformationInterchange.Destination(
                        foreignResource, "shared.md", "revise", revision)),
                List.of()));
    when(records.admit(eq(context), any(), any()))
        .thenReturn(new InformationCatalogue.Admission(next, resource, true));
    var result =
        assertInstanceOf(
            InformationResponse.InterchangeImported.class,
            importing.execute("writer", request, null));
    assertEquals(next, result.value().mappings().getFirst().revision());
    verify(content).requireWrite(context, revision);
    verify(records).tags(context, next, List.of("imported"));
    verify(records, never()).share(any(), any(), anyBoolean());

    clearInvocations(records);
    doThrow(new NotFoundFault("mandatory restriction"))
        .when(content)
        .requireWrite(context, revision);
    assertThrows(NotFoundFault.class, () -> importing.execute("writer", request, null));
    verify(records, never()).admit(any(), any(), any());
    when(members.role("one", "writer")).thenReturn(Optional.of(ProjectRole.VIEWER));
    assertThrows(CallerFault.class, () -> importing.execute("writer", request, null));
    verify(records, never()).admit(any(), any(), any());
  }

  @Test
  void exportRetainsDependencySnapshotForProvenanceInsteadOfQueryingItTwice() {
    byte[] bytes = "# Retained".getBytes(StandardCharsets.UTF_8);
    row("markdown", "text/markdown", bytes);
    var response =
        assertInstanceOf(
            InformationResponse.InterchangeBundle.class,
            service.execute(
                "owner",
                new InformationRequest.InterchangeExport(selected, List.of(revision)),
                null));
    assertEquals(
        List.of(),
        response.value().documents().getFirst().descriptor().provenance().dependencies());
    verify(repository, times(1)).dependencies(revision);
    verify(catalogue, times(1)).requireReadable(any(), eq(revision));
    verify(navigation).snapshot();
  }
}
