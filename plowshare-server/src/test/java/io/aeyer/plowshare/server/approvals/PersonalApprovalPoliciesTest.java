package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.auth.AccountAdministrationRepository;
import io.aeyer.plowshare.server.auth.ServerAdministration.Account;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersonalApprovalPoliciesTest {
  private final ApprovalPolicyRepository repository = mock(ApprovalPolicyRepository.class);
  private final AccountAdministrationRepository accounts =
      mock(AccountAdministrationRepository.class);
  private final AvailableApprovalBots bots = mock(AvailableApprovalBots.class);
  private final ApprovalPolicies policies =
      new PersonalApprovalPolicies(repository, accounts, bots);

  @Test
  void serviceAndDisabledAccountsCannotManagePolicy() {
    assertThrows(CallerFault.class, () -> policies.read("@service/token"));
    verifyNoInteractions(accounts, repository, bots);
    when(accounts.account("alice")).thenReturn(account(false));
    assertThrows(CallerFault.class, () -> policies.save("alice", ApprovalPolicy.manual()));
    verifyNoInteractions(repository, bots);
  }

  @Test
  void ownerIdentityIsNeverTakenFromPolicyFields() {
    when(accounts.account("alice")).thenReturn(account(true));
    when(repository.read("alice")).thenReturn(ApprovalPolicy.manual());
    assertEquals(ApprovalPolicy.manual(), policies.read("alice"));
    policies.save("alice", ApprovalPolicy.manual());
    verify(repository).save("alice", ApprovalPolicy.manual());
    verifyNoInteractions(bots);
  }

  @Test
  void unavailableBotCannotEnableAutoOrWriteAReplacementPolicy() {
    when(accounts.account("alice")).thenReturn(account(true));
    when(bots.selected("alice", "missing")).thenThrow(new CallerFault("Bot unavailable"));
    assertThrows(
        CallerFault.class,
        () ->
            policies.save(
                "alice", new ApprovalPolicy(0, ApprovalPolicy.Mode.AUTO, "missing", List.of())));
    verifyNoInteractions(repository);
  }

  private static Account account(boolean enabled) {
    return new Account(
        "alice", enabled, false, false, OffsetDateTime.parse("2026-11-16T00:00:00Z"), false);
  }
}
