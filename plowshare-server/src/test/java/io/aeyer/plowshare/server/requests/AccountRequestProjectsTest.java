package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Existing token metadata supplies a context, never a new permission or a first-project fallback.
 */
class AccountRequestProjectsTest {
  final IdentityRepository identities = mock(IdentityRepository.class);
  final ServiceAccountRepository tokens = mock(ServiceAccountRepository.class);
  final ProjectMembers members = mock(ProjectMembers.class);
  final io.aeyer.plowshare.server.applications.ApplicationIdentityRepository applications =
      mock(io.aeyer.plowshare.server.applications.ApplicationIdentityRepository.class);
  final Instant now = Instant.parse("2026-10-18T00:00:00Z");
  final UUID id = UUID.randomUUID();
  final String actor = "@service/" + id;
  final AccountRequestProjects projects =
      new AccountRequestProjects(
          identities, tokens, members, applications, Clock.fixed(now, ZoneOffset.UTC));

  void account(String handle, IdentityRepository.AccountKind kind) {
    when(identities.account(handle))
        .thenReturn(
            new IdentityRepository.Account(
                new AuthorityIds.AccountId(UUID.randomUUID()),
                handle,
                kind,
                kind == IdentityRepository.AccountKind.BUILTIN));
  }

  ServiceAccounts.Token token(List<ServiceAccounts.Scope> scopes, boolean revoked, long seconds) {
    return new ServiceAccounts.Token(
        id,
        "application",
        actor,
        now.atOffset(ZoneOffset.UTC),
        now.plusSeconds(seconds).atOffset(ZoneOffset.UTC),
        revoked ? now.atOffset(ZoneOffset.UTC) : null,
        scopes);
  }

  @BeforeEach
  void fixture() {
    account(actor, IdentityRepository.AccountKind.SERVICE_TOKEN);
    when(members.authorityAccount(actor)).thenReturn(Optional.of("application"));
    when(tokens.applicationOwned(id)).thenReturn(true);
    when(applications.find("global", false))
        .thenReturn(
            Optional.of(
                new io.aeyer.plowshare.protocol.ApplicationIdentity.Binding(
                    "global",
                    new io.aeyer.plowshare.protocol.ApplicationIdentity.Declaration(
                        "application", "CONTRIBUTOR"),
                    id,
                    actor,
                    io.aeyer.plowshare.protocol.ApplicationIdentity.State.ACTIVE,
                    true,
                    now.plusSeconds(60).atOffset(ZoneOffset.UTC),
                    null,
                    UUID.randomUUID())));
    when(tokens.token("application", id, false))
        .thenReturn(
            token(
                List.of(new ServiceAccounts.Scope("global", ProjectRole.CONTRIBUTOR)), false, 60));
    when(identities.project("global"))
        .thenReturn(new AuthorityScope.Project(new AuthorityIds.ScopeId(UUID.randomUUID()), 1));
    when(members.role("global", actor)).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
  }

  @Test
  void exactApplicationProjectIncludingOrdinaryNameGlobalIsNotGlobalExecution() {
    assertEquals(Optional.of("global"), projects.implicit(actor));
    var spaces =
        new io.aeyer.plowshare.server.personal.PersonalSpaces(
            mock(io.aeyer.plowshare.server.personal.PersonalSpaceRepository.class),
            UnitOfWork.NONE,
            io.aeyer.plowshare.server.data.DataLayout.NONE);
    spaces.useRequestProjects(projects);
    assertEquals(io.aeyer.plowshare.protocol.Home.of("global"), spaces.home(null, actor));
    assertFalse(spaces.home(null, actor).isGlobal());
    assertThrows(CallerFault.class, () -> spaces.home("", actor));
    when(members.role("global", actor)).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> projects.implicit(actor));
    verify(members, never()).assign(anyString(), anyString(), any(), anyString(), anyBoolean());
  }

  @ParameterizedTest
  @EnumSource(IdentityRepository.AccountKind.class)
  void onlyHumansGetPersonalAndGenericServiceAccountsCannotBorrowIt(
      IdentityRepository.AccountKind kind) {
    account("principal", kind);
    if (kind == IdentityRepository.AccountKind.USER)
      assertEquals(Optional.empty(), projects.implicit("principal"));
    else assertThrows(CallerFault.class, () -> projects.implicit("principal"));
    verifyNoInteractions(tokens);
  }

  @Test
  void genericTokenAndAmbiguousOrMissingScopeRefuseInsteadOfTakingFirst() {
    when(tokens.applicationOwned(id)).thenReturn(false);
    assertThrows(CallerFault.class, () -> projects.implicit(actor));
    when(tokens.applicationOwned(id)).thenReturn(true);
    for (var scopes :
        List.of(
            List.<ServiceAccounts.Scope>of(),
            List.of(
                new ServiceAccounts.Scope("global", ProjectRole.CONTRIBUTOR),
                new ServiceAccounts.Scope("second", ProjectRole.MANAGER)))) {
      when(tokens.token("application", id, false)).thenReturn(token(scopes, false, 60));
      assertThrows(CallerFault.class, () -> projects.implicit(actor));
    }
    verify(identities, never()).project(anyString());
  }

  @Test
  void expiryRevocationAndDisabledOwnerRefuseWithoutSubstitutingAnIdentity() {
    when(tokens.token("application", id, false))
        .thenReturn(
            token(List.of(new ServiceAccounts.Scope("global", ProjectRole.CONTRIBUTOR)), false, 0));
    assertThrows(CallerFault.class, () -> projects.implicit(actor));
    when(tokens.token("application", id, false))
        .thenReturn(
            token(List.of(new ServiceAccounts.Scope("global", ProjectRole.CONTRIBUTOR)), true, 60));
    assertThrows(CallerFault.class, () -> projects.implicit(actor));
    when(members.authorityAccount(actor)).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> projects.implicit(actor));
    verify(identities, never()).project(anyString());
  }

  @Test
  void aScopeCannotSubstituteForTheExactApplicationBinding() {
    when(applications.find("global", false)).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> projects.implicit(actor));
    verify(identities, never()).project(anyString());
  }

  @Test
  void missingScopeOrStorageFailurePropagatesWithoutPersonalFallback() {
    when(identities.project("global")).thenThrow(new CallerFault("unavailable"));
    assertThrows(CallerFault.class, () -> projects.implicit(actor));
    var outage = new IllegalStateException("database unavailable");
    when(tokens.token("application", id, false)).thenThrow(outage);
    assertSame(outage, assertThrows(IllegalStateException.class, () -> projects.implicit(actor)));
  }
}
