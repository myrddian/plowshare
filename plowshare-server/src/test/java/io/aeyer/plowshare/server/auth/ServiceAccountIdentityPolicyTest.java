package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** Existing identity behavior only; a named service owner is not a token/accounting principal. */
class ServiceAccountIdentityPolicyTest {
  private static final String OPERATOR = "operator", OWNER = "automation";
  private static final List<ServiceAccounts.Scope> SCOPES =
      List.of(new ServiceAccounts.Scope("research", ProjectRole.CONTRIBUTOR));
  private final ServiceAccountRepository repository = mock(ServiceAccountRepository.class);
  private final AdminStore accounts = mock(AdminStore.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final ServiceAccounts service =
      new ServiceAccounts(repository, UnitOfWork.NONE, accounts, members, events);

  @BeforeEach
  void existing_service_owner_and_token_scopes() {
    var now = OffsetDateTime.of(2026, 10, 16, 0, 0, 0, 0, ZoneOffset.UTC);
    when(repository.account(OWNER, true)).thenReturn(new ServiceAccounts.Account(OWNER, true, now));
    when(repository.lockOrdinaryProject("research")).thenReturn(true);
    // The repository returns the principal persisted for the requested token UUID.
    when(repository.token(eq(OWNER), any(UUID.class), anyBoolean()))
        .thenAnswer(
            call -> {
              UUID id = call.getArgument(1, UUID.class);
              return new ServiceAccounts.Token(
                  id, "fixture", "@service/" + id, now, now.plusDays(30), null, SCOPES);
            });
  }

  @Test
  void rotating_a_token_preserves_its_principal_but_retires_existing_sessions() {
    var original = service.issue(OPERATOR, OWNER, "worker", SCOPES, 30);
    var rotated = service.rotate(OPERATOR, OWNER, original.token().id(), 30);
    assertEquals(original.token().id(), rotated.token().id());
    assertEquals(original.token().principal(), rotated.token().principal());
    assertFalse(
        original.credential().equals(rotated.credential()), "Rotation must replace the secret");
    assertNotEquals(OWNER, rotated.token().principal());
    verify(repository)
        .issue(
            eq(original.token().id()),
            eq(OWNER),
            eq("worker"),
            eq(original.token().principal()),
            anyString(),
            any(),
            eq(SCOPES));
    verify(repository).rotate(eq(original.token().id()), anyString(), any());
    verify(repository).retirePrincipal(original.token().principal());
    verify(events)
        .publishEvent(new ServerAdministration.SessionsRevoked(original.token().principal()));
  }

  @Test
  void another_token_for_the_same_service_owner_is_a_distinct_execution_identity() {
    var first = service.issue(OPERATOR, OWNER, "first", SCOPES, 30);
    var second = service.issue(OPERATOR, OWNER, "second", SCOPES, 30);
    assertNotEquals(first.token().id(), second.token().id());
    assertNotEquals(first.token().principal(), second.token().principal());
    assertEquals("@service/" + first.token().id(), first.token().principal());
    assertEquals("@service/" + second.token().id(), second.token().principal());
    verify(repository)
        .issue(
            eq(first.token().id()),
            eq(OWNER),
            eq("first"),
            eq(first.token().principal()),
            anyString(),
            any(),
            eq(SCOPES));
    verify(repository)
        .issue(
            eq(second.token().id()),
            eq(OWNER),
            eq("second"),
            eq(second.token().principal()),
            anyString(),
            any(),
            eq(SCOPES));
    verify(accounts, times(2)).requireServerAdmin(OPERATOR);
    verify(members, times(2)).requireRole("research", OWNER, ProjectRole.CONTRIBUTOR);
  }
}
