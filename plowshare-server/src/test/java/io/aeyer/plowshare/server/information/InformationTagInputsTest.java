package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InformationTagInputsTest {
  @Test
  void completedReceiptIsRecoveredBeforeRecheckingItsStaleTagPrecondition() {
    var repository = mock(InformationCatalogueRepository.class);
    var permissions = mock(io.aeyer.plowshare.server.access.ScopedPermissions.class);
    var access = new InformationAccess(permissions);
    var context = new InformationContext("owner", InformationContext.Selection.personal());
    UUID revision = UUID.randomUUID(), request = UUID.randomUUID();
    var resource =
        new InformationCatalogueRepository.Resource(
            UUID.randomUUID(),
            "account:owner",
            "source",
            "owner",
            null,
            null,
            new io.aeyer.plowshare.server.access.AuthorityIds.ScopeId(UUID.randomUUID()),
            java.util.Optional.empty());
    when(repository.lockResource(context, revision, false)).thenReturn(resource);
    when(repository.reserve(eq(context), eq(revision), eq(request), anyString()))
        .thenReturn(
            new InformationCatalogueRepository.Command(request, UUID.randomUUID(), true, true));
    var catalogue =
        new InformationCatalogue(
            repository,
            io.aeyer.plowshare.server.archive.UnitOfWork.NONE,
            access,
            mock(InformationLogAccess.class));
    catalogue.replaceTags(context, revision, List.of("original"), 0, request);
    verify(repository).lockResource(context, revision, false);
    verify(repository).reserve(eq(context), eq(revision), eq(request), anyString());
    verifyNoMoreInteractions(repository);
  }

  private Map<String, Object> input() {
    return new HashMap<>(
        Map.of(
            "revision",
            UUID.randomUUID().toString(),
            "requestId",
            UUID.randomUUID().toString(),
            "tags",
            List.of(" Topic "),
            "expectedTagsVersion",
            0L));
  }

  @Test
  void rejectsMissingNullFractionalNegativeUnsafeAndCoercedVersionsBeforeBusinessLogic() {
    for (Object value :
        List.of(-1L, 9007199254740992L, 0.5, Double.NaN, Double.POSITIVE_INFINITY, "0", false)) {
      var fields = input();
      fields.put("expectedTagsVersion", value);
      assertThrows(CallerFault.class, () -> InformationTagInputs.replacement(fields));
    }
    var fields = input();
    fields.remove("expectedTagsVersion");
    assertThrows(CallerFault.class, () -> InformationTagInputs.replacement(fields));
    fields.put("expectedTagsVersion", null);
    assertThrows(CallerFault.class, () -> InformationTagInputs.replacement(fields));
  }

  @Test
  void acceptsIntegralNativeSdkJsonNumbersWithoutAcceptingCoercion() {
    var fields = input();
    fields.put("expectedTagsVersion", 4.0);
    assertEquals(4L, InformationTagInputs.replacement(fields).expectedVersion());
  }

  @Test
  void validatesIdentifiersFieldsAndCanonicalizesTagsBeforeReservation() {
    var fields = input();
    assertEquals(List.of("topic"), InformationTagInputs.replacement(fields).tags());
    fields.put("force", true);
    assertThrows(CallerFault.class, () -> InformationTagInputs.replacement(fields));
    fields.remove("force");
    fields.put("revision", "1-1-1-1-1");
    assertThrows(CallerFault.class, () -> InformationTagInputs.replacement(fields));
  }

  @Test
  void expectedVersionParticipatesInTheNewFingerprintWithoutChangingLegacyEncoding() {
    var context = InformationContext.Selection.personal();
    UUID revision = UUID.randomUUID();
    var tags = List.of("topic");
    String legacy =
        InformationCatalogueFingerprint.command(
            "tags", revision, context, new InformationCommandParameters.Tags(tags));
    String first =
        InformationCatalogueFingerprint.command(
            "tags.replace",
            revision,
            context,
            new InformationCommandParameters.TagsReplace(tags, 0));
    String second =
        InformationCatalogueFingerprint.command(
            "tags.replace",
            revision,
            context,
            new InformationCommandParameters.TagsReplace(tags, 1));
    assertNotEquals(legacy, first);
    assertNotEquals(first, second);
    assertEquals(
        legacy,
        InformationCatalogueFingerprint.command(
            "tags", revision, context, new InformationCommandParameters.Tags(tags)));
  }
}
