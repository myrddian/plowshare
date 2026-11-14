package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.access.ProjectCreatorGrants;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.auth.ServerAdministration.Account;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** Project creator changes through account administration, against mocked repositories. */
class ProjectCreatorServerAdministrationTest {
  private static final String NOT_ADMIN = "Only a server administrator may manage server resources";
  private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-11-14T00:00:00Z");

  private final AccountAdministrationRepository repository =
      mock(AccountAdministrationRepository.class);
  private final AdminStore accounts = mock(AdminStore.class);
  private final ProjectCreatorGrants creators = mock(ProjectCreatorGrants.class);
  private ServerAdministration service;

  private static Account account(String handle) {
    return new Account(handle, true, false, false, CREATED, false);
  }

  @BeforeEach
  void setup() {
    service =
        new ServerAdministration(
            repository,
            UnitOfWork.NONE,
            accounts,
            mock(PasswordHasher.class),
            new TokenStore(
                Clock.systemUTC(),
                Duration.ofMinutes(15),
                Duration.ofDays(1),
                Duration.ofSeconds(30)),
            mock(ApplicationEventPublisher.class),
            creators);
    doThrow(new CallerFault(NOT_ADMIN)).when(accounts).requireServerAdmin("mia");
    when(repository.account("sam")).thenReturn(account("sam"));
  }

  @Test
  void update_grants_and_revokes_project_creator() {
    when(creators.holds("sam")).thenReturn(true);
    Account granted = service.update("admin", "sam", null, null, true);
    var order = inOrder(repository, accounts, creators);
    order.verify(repository).lockAdministrators();
    order.verify(accounts).requireServerAdmin("admin");
    order.verify(creators).grant("admin", "sam");
    order
        .verify(repository)
        .audit(
            eq("admin"),
            eq("account.project-creator.grant"),
            argThat(target -> target.handle().equals("sam") && target.projectCreator()));
    assertTrue(granted.projectCreator());
    // Only the role changed: the account row and its sessions are untouched.
    verify(repository, never()).update(anyString(), anyBoolean(), anyBoolean());
    verify(repository, never()).revokeSessions(anyString());
    verify(repository, never()).audit(anyString(), eq("account.update"), any());

    when(creators.holds("sam")).thenReturn(false);
    Account revoked = service.update("admin", "sam", null, null, false);
    verify(creators).revoke("admin", "sam");
    verify(repository)
        .audit(
            eq("admin"),
            eq("account.project-creator.revoke"),
            argThat(target -> target.handle().equals("sam") && !target.projectCreator()));
    assertFalse(revoked.projectCreator());

    service.update("admin", "sam", false, null, null);
    verify(creators).grant(anyString(), anyString());
    verify(creators).revoke(anyString(), anyString());
    verify(repository).update("sam", false, false);
    verify(repository).audit(eq("admin"), eq("account.update"), any());
  }

  @Test
  void update_requires_some_change() {
    var refused =
        assertThrows(CallerFault.class, () -> service.update("admin", "sam", null, null, null));
    assertEquals("Supply enabled, serverAdmin or projectCreator", refused.getMessage());
    verifyNoInteractions(creators);
  }

  @Test
  void non_admin_cannot_change_project_creator() {
    for (Boolean value : List.of(true, false)) {
      var refused =
          assertThrows(CallerFault.class, () -> service.update("mia", "sam", null, null, value));
      assertEquals(NOT_ADMIN, refused.getMessage());
    }
    verifyNoInteractions(creators);
    verify(repository, never()).audit(anyString(), anyString(), any());
  }

  @Test
  void list_reads_project_creators_in_one_batch() {
    when(repository.list()).thenReturn(List.of(account("mia"), account("sam"), account("zoe")));
    when(creators.holdersAmong(List.of("mia", "sam", "zoe"))).thenReturn(Set.of("sam"));
    var listed = service.list("admin");
    assertEquals(List.of("mia", "sam", "zoe"), listed.stream().map(Account::handle).toList());
    assertEquals(
        List.of(false, true, false), listed.stream().map(Account::projectCreator).toList());
    verify(creators).holdersAmong(List.of("mia", "sam", "zoe"));
    verify(creators, never()).holds(anyString());
  }

  @Test
  void non_admin_cannot_list() {
    assertThrows(CallerFault.class, () -> service.list("mia"));
    verifyNoInteractions(creators);
  }
}
