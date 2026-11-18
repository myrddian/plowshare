package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.auth.AccountAdministrationRepository;
import io.aeyer.plowshare.server.auth.ServiceCredentials;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Objects;

/** Owner-only policy management. Reviewer/model tools do not expose this capability. */
public final class PersonalApprovalPolicies implements ApprovalPolicies {
  private final ApprovalPolicyRepository policies;
  private final AccountAdministrationRepository accounts;
  private final AvailableApprovalBots bots;

  public PersonalApprovalPolicies(
      ApprovalPolicyRepository policies,
      AccountAdministrationRepository accounts,
      AvailableApprovalBots bots) {
    this.policies = Objects.requireNonNull(policies);
    this.accounts = Objects.requireNonNull(accounts);
    this.bots = Objects.requireNonNull(bots);
  }

  @Override
  public Capabilities capabilities() {
    return new Capabilities(List.of(ApprovalPolicy.Kind.values()), true, false);
  }

  @Override
  public ApprovalPolicy read(String owner) {
    requireOwner(owner);
    return policies.read(owner);
  }

  @Override
  public ApprovalPolicy save(String owner, ApprovalPolicy expected) {
    requireOwner(owner);
    Objects.requireNonNull(expected);
    if (expected.rules().stream()
        .anyMatch(rule -> rule.effect() == ApprovalPolicy.Effect.PERSONAL_READ_INTENT))
      throw new CallerFault("Set Personal read intent in the deployment security policy");
    if (expected.mode() == ApprovalPolicy.Mode.AUTO)
      Objects.requireNonNull(bots.selected(owner, expected.bot()), "Selected approval bot");
    return policies.save(owner, expected);
  }

  private void requireOwner(String owner) {
    if (owner == null
        || owner.isBlank()
        || ServiceCredentials.principal(owner)
        || !accounts.account(owner).enabled())
      throw new CallerFault("Approval policy management requires an active personal user account");
  }
}
