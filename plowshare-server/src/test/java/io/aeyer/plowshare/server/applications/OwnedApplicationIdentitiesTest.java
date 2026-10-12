package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ApplicationIdentity.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Domain lifecycle and authorization tests use mocked specialist repositories. */
class OwnedApplicationIdentitiesTest {
  final ApplicationIdentityRepository repository = mock(ApplicationIdentityRepository.class);
  final ServiceAccountRepository services = mock(ServiceAccountRepository.class);
  final AdminStore accounts = mock(AdminStore.class);
  final ProjectMembers members = mock(ProjectMembers.class);
  final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  final ApplicationIdentities identities =
      new OwnedApplicationIdentities(
          repository, services, accounts, members, projects, UnitOfWork.NONE, event -> {});
  final Declaration declaration = new Declaration("worker", "CONTRIBUTOR");
  final UUID token = UUID.randomUUID();
  final Binding binding =
      new Binding(
          "app",
          declaration,
          token,
          "@service/" + token,
          State.RESERVED,
          false,
          OffsetDateTime.now().plusDays(30),
          null,
          null);

  @Test
  void reservationReusesOnlyItsExactOwnedDeclaration() {
    when(repository.find("app", true)).thenReturn(Optional.of(binding));
    assertEquals(binding, identities.reserve("operator", "app", declaration));
    assertThrows(
        CallerFault.class,
        () -> identities.reserve("operator", "app", new Declaration("other", "CONTRIBUTOR")));
    verify(services, never()).create(anyString());
    verify(services, never())
        .issue(any(), anyString(), anyString(), anyString(), anyString(), any(), anyList());
  }

  @Test
  void ordinaryManagerAndServiceTokenCannotReserveOrIssueIdentities() {
    doThrow(new CallerFault("server administrator required"))
        .when(accounts)
        .requireServerAdmin("manager");
    assertThrows(CallerFault.class, () -> identities.reserve("manager", "app", declaration));
    assertThrows(
        CallerFault.class,
        () -> identities.issue("manager", new Issue("app", UUID.randomUUID(), 30, false)));
    verifyNoInteractions(repository, services, projects);
  }

  @Test
  void sourceCannotRemoveOrSubstituteItsOwnedExecutionAccount() {
    when(repository.find("app", true)).thenReturn(Optional.of(binding));
    assertThrows(CallerFault.class, () -> identities.activate("operator", "app", null, null));
    assertThrows(
        CallerFault.class,
        () -> identities.activate("operator", "app", declaration, "@service/other"));
    verifyNoInteractions(members);
    verify(repository, never()).activate(any());
  }

  @Test
  void unchangedUpdatesDoNotRestoreRevokedMembership() {
    var active =
        new Binding(
            "app",
            declaration,
            token,
            binding.principal(),
            State.ACTIVE,
            true,
            binding.expiresAt(),
            null,
            null);
    when(repository.find("app", true)).thenReturn(Optional.of(active));
    assertEquals(
        active.principal(),
        identities.activate("operator", "app", declaration, active.principal()));
    verify(members, never()).assign(anyString(), anyString(), any(), anyString(), anyBoolean());
    verify(repository).activate(active);
  }

  @Test
  void committedCredentialRequestsReturnMetadataOnlyAndNeverMintAnotherSecret() {
    var request = new Issue("app", UUID.randomUUID(), 30, false);
    var receipt =
        new Receipt(request.requestId(), "app", token, binding.principal(), binding.expiresAt());
    when(repository.receipt(request.requestId()))
        .thenReturn(
            Optional.of(
                new ApplicationIdentityRepository.IssueRecord("operator", request, receipt)));
    assertEquals(receipt, identities.receipt("operator", "app", request.requestId()));
    assertThrows(CallerFault.class, () -> identities.issue("operator", request));
    assertThrows(CallerFault.class, () -> identities.receipt("other", "app", request.requestId()));
    assertThrows(
        CallerFault.class,
        () -> identities.issue("operator", new Issue("other", request.requestId(), 30, false)));
    verify(repository, never()).issued(anyString(), any(), any(), anyString());
  }

  @Test
  void dormantReservationsCannotIssueBeforeSourceActivation() {
    when(repository.find("app", true)).thenReturn(Optional.of(binding));
    assertThrows(
        CallerFault.class,
        () -> identities.issue("operator", new Issue("app", UUID.randomUUID(), 30, false)));
    verify(repository, never()).issued(anyString(), any(), any(), anyString());
  }

  @Test
  void strictManifestResolutionPreservesHumanGrantsAndRefusesForeignPrincipals() {
    String source =
        "{\"version\":1,\"name\":\"app\",\"service\":{\"handle\":\"worker\",\"role\":\"CONTRIBUTOR\"},\"access\":{\"accounts\":[{\"handle\":\"manager\",\"role\":\"MANAGER\"}]}}";
    String resolved = ApplicationServiceManifest.resolve(source, binding.principal());
    assertTrue(resolved.contains(binding.principal()));
    assertEquals(declaration, ApplicationServiceManifest.declaration(resolved).orElseThrow());
    assertDoesNotThrow(
        () -> io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy.parse(resolved, "app"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ApplicationServiceManifest.resolve(resolved, "@service/other"));
    for (String invalid :
        List.of(
            source.replace("CONTRIBUTOR", "MANAGER"),
            source.replace("\"role\":\"CONTRIBUTOR\"", "\"role\":\"CONTRIBUTOR\",\"extra\":true")))
      assertThrows(
          IllegalArgumentException.class, () -> ApplicationServiceManifest.declaration(invalid));
  }
}
