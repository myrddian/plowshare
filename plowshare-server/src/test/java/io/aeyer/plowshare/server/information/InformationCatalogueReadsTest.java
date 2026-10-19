package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Operation-local reuse must reduce reads without giving an earlier result authority over a later
 * one.
 */
class InformationCatalogueReadsTest {
  final InformationCatalogueRepository repository = mock(InformationCatalogueRepository.class);
  final InformationAccess access =
      new InformationAccess(new EffectiveProjectPermissions(mock(ProjectMembers.class)));
  final InformationCatalogue catalogue =
      new InformationCatalogue(
          repository, UnitOfWork.NONE, access, mock(InformationLogAccess.class));
  final InformationContext context =
      new InformationContext("reader", InformationContext.Selection.personal()).withView(true);
  final UUID revision = UUID.randomUUID(), resource = UUID.randomUUID();

  Information.Revision metadata(String owner, String type, String policy) {
    return new Information.Revision(
        revision,
        resource,
        1,
        "Title",
        "text/plain",
        type,
        "text",
        null,
        "source-hash",
        "extraction-hash",
        20L,
        Instant.parse("2026-10-01T12:00:00Z"),
        "active",
        false,
        2,
        null,
        10,
        0,
        "source.txt",
        "source",
        owner,
        List.of("tag"),
        List.of(),
        false,
        null,
        null,
        Map.of("group", List.of("tag")),
        "manual",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        policy);
  }

  InformationCatalogueRepository.AuthorizedText text(String value) {
    return new InformationCatalogueRepository.AuthorizedText(
        context, revision, resource, "code", "java", "source-hash", "extraction-hash", 2, value);
  }

  @Test
  void statusReusesAuthorizedMetadataAndResolvesConfigurationOnceAcrossHistoricalSteps() {
    var metadata = metadata("reader", "code", null);
    when(repository.authorizedMetadata(context, revision))
        .thenReturn(
            Optional.of(new InformationCatalogueRepository.AuthorizedMetadata(context, metadata)));
    String expected =
        InformationCatalogue.sha256(
            ("derive-config:"
                    + io.aeyer.plowshare.server.documents.CodeDerivation.VERSION
                    + ":"
                    + io.aeyer.plowshare.server.documents.CodeOutline.VERSION)
                .getBytes(StandardCharsets.UTF_8));
    when(repository.steps(revision))
        .thenReturn(
            List.of(
                new Information.Step("derive", "ready", 1, null, 1, expected, null, null, null),
                new Information.Step("derive", "ready", 1, null, 2, expected, null, null, null),
                new Information.Step(
                    "embed", "ready", 1, null, 2, "embed-config", null, null, null)));
    when(repository.progress(revision))
        .thenReturn(
            new Information.Progress(
                new Information.Completion(1, 1), new Information.Completion(2, 1)));
    when(repository.revisionEvents(revision)).thenReturn(List.of());
    when(repository.inputs(revision)).thenReturn(List.of());
    var loads = new AtomicInteger();
    catalogue.useConfiguration(
        () -> {
          loads.incrementAndGet();
          return Map.of("derive", "derive-config", "embed", "embed-config");
        });

    var shown = catalogue.status(context, revision);
    assertEquals(1, loads.get());
    assertTrue(shown.canManage());
    assertTrue(shown.steps().stream().allMatch(step -> Boolean.TRUE.equals(step.compatible())));
    verify(repository).authorizedMetadata(context, revision);
    verify(repository).steps(revision);
    verify(repository).progress(revision);
    verify(repository).revisionEvents(revision);
    verify(repository).inputs(revision);
    verifyNoMoreInteractions(repository);
    assertThrows(
        UnsupportedOperationException.class, () -> shown.tagGroups().get("group").add("mutation"));
    assertThrows(UnsupportedOperationException.class, () -> shown.steps().clear());
  }

  @Test
  void nonownerStatusDoesNotLoadOwnerEventsOrDiscardASecondOwnerRow() {
    when(repository.authorizedMetadata(context, revision))
        .thenReturn(
            Optional.of(
                new InformationCatalogueRepository.AuthorizedMetadata(
                    context, metadata("other", "document", null))));
    when(repository.steps(revision)).thenReturn(List.of());
    when(repository.inputs(revision)).thenReturn(List.of());
    catalogue.useConfiguration(
        () -> {
          throw new AssertionError("empty steps need no configuration");
        });
    var shown = catalogue.status(context, revision);
    assertFalse(shown.canManage());
    verify(repository).authorizedMetadata(context, revision);
    verify(repository).steps(revision);
    verify(repository).progress(revision);
    verify(repository).inputs(revision);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void hiddenStatusRetainsTheWriteLockFallbackAndDoesNotResolveConfiguration() {
    when(repository.authorizedMetadata(context, revision)).thenReturn(Optional.empty());
    when(repository.lockResource(context, revision, false))
        .thenReturn(
            new InformationCatalogueRepository.Resource(
                resource,
                "account:reader",
                "source.txt",
                "reader",
                null,
                null,
                new ScopeId(UUID.randomUUID()),
                Optional.empty()));
    when(repository.metadata(revision)).thenReturn(metadata("reader", "document", null));
    when(repository.steps(revision))
        .thenReturn(
            List.of(
                new Information.Step(
                    "derive",
                    "failed",
                    1,
                    "private error",
                    2,
                    "private fingerprint",
                    Instant.now(),
                    null,
                    null)));
    catalogue.useConfiguration(
        () -> {
          throw new AssertionError("hidden status must not load processing configuration");
        });
    var shown = catalogue.status(context, revision);
    assertEquals("source.txt", shown.title());
    assertNull(shown.steps().getFirst().error());
    assertNull(shown.steps().getFirst().fingerprint());
    assertNull(shown.steps().getFirst().compatible());
    assertNull(shown.progress());
    verify(repository).authorizedMetadata(context, revision);
    verify(repository).lockResource(context, revision, false);
    verify(repository).metadata(revision);
    verify(repository).steps(revision);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void hiddenStatusWithoutWriteAuthorityNeverLoadsMetadataOrSteps() {
    when(repository.authorizedMetadata(context, revision)).thenReturn(Optional.empty());
    when(repository.lockResource(context, revision, false)).thenThrow(new NotFoundFault("hidden"));
    assertThrows(NotFoundFault.class, () -> catalogue.status(context, revision));
    verify(repository).authorizedMetadata(context, revision);
    verify(repository).lockResource(context, revision, false);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void boundedWindowUsesOneAuthorizedExtractionAndRetainsUtf16Offsets() {
    when(repository.authorizedText(context, revision)).thenReturn(Optional.of(text("a🔎bc")));
    var shown = catalogue.window(context, revision, 1, 2);
    assertEquals("🔎", shown.text());
    assertEquals(5, shown.total());
    assertEquals("code", shown.documentType());
    assertEquals("java", shown.documentSubtype());
    verify(repository).authorizedText(context, revision);
    verifyNoMoreInteractions(repository);
    assertThrows(CallerFault.class, () -> catalogue.window(context, revision, -1, 1));
    verifyNoMoreInteractions(repository);
  }

  @Test
  void textAndPassageMatchingReuseTheSnapshotButNewOperationsReauthorize() {
    when(repository.authorizedText(context, revision))
        .thenReturn(Optional.of(text("one retained passage")))
        .thenReturn(Optional.empty());
    var located = catalogue.locateWindow(context, revision, "retained");
    assertTrue(located.matched());
    assertEquals(4, located.start());
    assertThrows(NotFoundFault.class, () -> catalogue.text(context, revision));
    verify(repository, times(2)).authorizedText(context, revision);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void codeLocationNeedsOneExtractionAndOneSpanAndNoRepeatedSelectionRead() {
    UUID chunk = UUID.randomUUID();
    when(repository.authorizedText(context, revision))
        .thenReturn(Optional.of(text("code passage")));
    when(repository.codeSpan(revision, chunk))
        .thenReturn(Optional.of(new InformationCatalogueRepository.Span(5, 12)));
    var shown = catalogue.locateCodeWindow(context, revision, chunk);
    assertEquals("passage", shown.text());
    assertEquals("java", shown.documentSubtype());
    verify(repository).authorizedText(context, revision);
    verify(repository).codeSpan(revision, chunk);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void missingCodeLocationUsesOnlyANarrowReadabilityCheckAndNeverLoadsExtraction() {
    UUID chunk = UUID.randomUUID();
    when(repository.codeSpan(revision, chunk)).thenReturn(Optional.empty());
    when(repository.readable(context, revision)).thenReturn(true);
    var shown = catalogue.locateCodeWindow(context, revision, chunk);
    assertFalse(shown.matched());
    assertNull(shown.text());
    assertNull(shown.start());
    verify(repository).codeSpan(revision, chunk);
    verify(repository).readable(context, revision);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void missingCodeLocationOnAnUnreadableRevisionCannotDiscloseAnUnmatchedResult() {
    UUID chunk = UUID.randomUUID();
    when(repository.codeSpan(revision, chunk)).thenReturn(Optional.empty());
    when(repository.readable(context, revision)).thenReturn(false);
    assertThrows(NotFoundFault.class, () -> catalogue.locateCodeWindow(context, revision, chunk));
    verify(repository).codeSpan(revision, chunk);
    verify(repository).readable(context, revision);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void aKnownCodeLocationCannotEscapeTheExtractionQuerysCurrentAuthorization() {
    UUID chunk = UUID.randomUUID();
    when(repository.codeSpan(revision, chunk))
        .thenReturn(Optional.of(new InformationCatalogueRepository.Span(0, 4)));
    when(repository.authorizedText(context, revision)).thenReturn(Optional.empty());
    assertThrows(NotFoundFault.class, () -> catalogue.locateCodeWindow(context, revision, chunk));
    verify(repository).codeSpan(revision, chunk);
    verify(repository).authorizedText(context, revision);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void absentAndUnextractedReadsRemainDistinctAndCannotFallBackToUnscopedRows() {
    when(repository.authorizedText(context, revision))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(text(null)));
    assertThrows(NotFoundFault.class, () -> catalogue.window(context, revision, 0, 1));
    assertThrows(CallerFault.class, () -> catalogue.text(context, revision));
    verify(repository, times(2)).authorizedText(context, revision);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void unauthorizedSelectionFailsBeforeRepositoryRead() {
    var selected =
        new InformationContext("reader", InformationContext.Selection.project("private"));
    assertThrows(CallerFault.class, () -> catalogue.window(selected, revision, 0, 1));
    assertThrows(CallerFault.class, () -> catalogue.status(selected, revision));
    assertThrows(
        CallerFault.class, () -> catalogue.locateCodeWindow(selected, revision, UUID.randomUUID()));
    verifyNoInteractions(repository);
  }

  @Test
  void extractionIdentityAcceptsOpaqueLegacyFingerprintsAndRejectsMalformedSnapshots() {
    var retained = text(null);
    assertEquals("source-hash", retained.contentHash());
    assertEquals("extraction-hash", retained.textHash());
    assertEquals(2, retained.processingGeneration());
    for (String invalid : List.of("", "bad\nhash", "x".repeat(1025))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new InformationCatalogueRepository.AuthorizedText(
                  context, revision, resource, "document", "text", invalid, null, 1, null));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new InformationCatalogueRepository.AuthorizedText(
                  context, revision, resource, "document", "text", "legacy", invalid, 1, null));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InformationCatalogueRepository.AuthorizedText(
                context, revision, resource, "document", "text", "legacy", null, 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InformationCatalogueRepository.AuthorizedText(
                context, revision, resource, "document", "bad subtype", "legacy", null, 1, null));
    assertThrows(
        NullPointerException.class,
        () ->
            new InformationCatalogueRepository.AuthorizedText(
                context, revision, null, "document", "text", "legacy", null, 1, null));
  }
}
