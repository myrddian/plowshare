package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.protocol.InformationNavigation.*;
import io.aeyer.plowshare.server.archive.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Pure boundary, authority and durable receipt behavior needs no PostgreSQL instance. */
class DocumentNavigationTest {
  final InformationNavigationRepository repository = mock(InformationNavigationRepository.class);
  final ProjectMembers members = mock(ProjectMembers.class);
  final InformationAccess access = new InformationAccess(members);
  final DocumentNavigation service =
      new DocumentNavigation(repository, access, UnitOfWork.NONE, true);
  final InformationRequest.Selection selection = InformationRequest.Selection.personal();

  @Test
  void disabledWritesPerformNoMutationOrReceiptLookup() {
    var disabled = new DocumentNavigation(repository, access, UnitOfWork.NONE, false);
    var command =
        new InformationRequest.NavigationViewDelete(
            selection, UUID.randomUUID(), UUID.randomUUID(), 1L);
    assertThrows(RuntimeException.class, () -> disabled.execute("author", command));
    verifyNoInteractions(repository);
  }

  @Test
  void recoveredPolicyReceiptStillChecksCurrentManagementAuthority() {
    var target = new PolicyTarget("platform", null, null);
    UUID request = UUID.randomUUID();
    var settings = new PolicySettings(Policy.DEFAULT, Policy.NONE, true, "Approved label");
    var command =
        new InformationRequest.NavigationPolicySet(
            selection, request, target, 0L, settings, "Initial policy");
    doThrow(new io.aeyer.plowshare.server.faults.CallerFault("demoted"))
        .when(repository)
        .requireManager("author", target);
    assertThrows(RuntimeException.class, () -> service.execute("author", command));
    verify(repository, never()).receipt(any(), any());
    verify(repository, never()).setPolicy(any(), any(), anyLong(), any());
  }

  @Test
  void receiptRecoveryDoesNotReplayMutationAndDifferentPayloadConflicts() {
    UUID request = UUID.randomUUID(), id = UUID.randomUUID();
    var command = new InformationRequest.NavigationViewDelete(selection, request, id, 1L);
    var receipt =
        new InformationNavigationRepository.Receipt(null, null, null, new Changed(id, 2, false));
    when(repository.fingerprint(command)).thenReturn("same");
    when(repository.receipt("author", request))
        .thenReturn(
            Optional.of(new InformationNavigationRepository.StoredReceipt("same", receipt)));
    assertInstanceOf(
        InformationResponse.NavigationChanged.class, service.execute("author", command));
    verify(repository, never()).delete(any(), any(), anyLong());
    when(repository.fingerprint(command)).thenReturn("different");
    assertThrows(RuntimeException.class, () -> service.execute("author", command));
  }

  @Test
  void roleAndScopeAreCheckedOnEveryReadAndSavedScopeCannotEscapeServiceCeiling() {
    when(members.mayUse("private", "author")).thenReturn(false);
    var selection =
        new InformationRequest.Selection(
            new InformationRequest.Scope("project", "private", false), "documents", null);
    assertThrows(
        RuntimeException.class,
        () ->
            service.execute(
                "author", new InformationRequest.DocumentList(selection, null, null, null, 20, 0)));
    verify(repository, never()).documents(any(), any(), any(), any(), anyInt(), anyInt());
    var path = new Path(List.of(new ScopeStep(InformationRequest.Scope.personal())));
    var command =
        new InformationRequest.NavigationViewSave(
            selection, UUID.randomUUID(), UUID.randomUUID(), 0L, "Saved", path);
    when(members.mayUse("private", "@service/fixture")).thenReturn(true);
    assertThrows(RuntimeException.class, () -> service.execute("@service/fixture", command));
    verify(repository, never()).save(any(), any(), anyLong(), any(), any());
  }

  @Test
  void strictBoundaryRejectsNullUnknownNestedValuesAndScalarCoercion() {
    UUID id = UUID.randomUUID();
    var common = Map.<String, Object>of("scope", Map.of("kind", "personal"));
    var input = new LinkedHashMap<>(common);
    input.put("target", Map.of("resource", id.toString()));
    assertInstanceOf(
        InformationRequest.DocumentGet.class,
        InformationNavigationInputs.command("documents.get", input));
    for (Object invalid :
        List.of(
            Map.of("resource", "short"),
            Map.of("resource", id.toString(), "grant", true),
            Map.of("resource", id.toString(), "revision", 1))) {
      input.put("target", invalid);
      assertThrows(
          RuntimeException.class,
          () -> InformationNavigationInputs.command("documents.get", input));
    }
    input.remove("target");
    input.put("relatedTo", null);
    assertThrows(
        RuntimeException.class, () -> InformationNavigationInputs.command("documents.list", input));
    input.remove("relatedTo");
    input.put("limit", 1.5);
    assertThrows(
        RuntimeException.class, () -> InformationNavigationInputs.command("documents.list", input));
    input.put("limit", "20");
    assertThrows(
        RuntimeException.class, () -> InformationNavigationInputs.command("documents.list", input));
    var policy =
        Map.of(
            "scope",
            Map.of("kind", "personal"),
            "requestId",
            id.toString(),
            "target",
            Map.of("kind", "platform"),
            "expectedVersion",
            0,
            "reason",
            "Initialise",
            "settings",
            Map.of(
                "defaults",
                Map.of("ownerOnly", false),
                "mandatory",
                Map.of("ownerOnly", false, "referenceHidden", false, "propagate", false),
                "allowOverrides",
                true,
                "referenceLabel",
                "Label"));
    assertThrows(
        RuntimeException.class, () -> InformationNavigationInputs.command("policy.set", policy));
  }

  @Test
  void pathAlternativesRejectUnknownKindsAndMultipleScopes() {
    var scope = new ScopeStep(InformationRequest.Scope.personal());
    assertThrows(RuntimeException.class, () -> new Path(List.of(scope, scope)));
    assertThrows(
        RuntimeException.class,
        () -> new Path(List.of(new DocumentStep(new Target(UUID.randomUUID(), null)))));
    assertThrows(RuntimeException.class, () -> new Path(Collections.nCopies(33, scope)));
  }
}
