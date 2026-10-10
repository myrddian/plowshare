package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.ConversationContextRepository;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.AccountAdministrationRepository;
import io.aeyer.plowshare.server.auth.ServiceCredentials;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import org.springframework.stereotype.Service;

/** Context reads honor live Application grants and machine-token ceilings, not fleet authority. */
@Service
public final class OwnedConversationContext implements ConversationContextAccess {
  private final ConversationContextRepository conversations;
  private final AccountAdministrationRepository accounts;
  private final ProjectMembers members;

  public OwnedConversationContext(
      ConversationContextRepository conversations,
      AccountAdministrationRepository accounts,
      ProjectMembers members) {
    this.conversations = conversations;
    this.accounts = accounts;
    this.members = members;
  }

  @Override
  public UsageAttribution owner(String principal, String conversation) {
    if (principal == null || principal.isBlank()) throw refused();
    boolean service = ServiceCredentials.principal(principal);
    // authorityAccount checks token/account activity (including expiry and revocation). role below
    // also applies the token ceiling and the current Application manifest grant to that principal.
    if (service
        ? members.authorityAccount(principal).isEmpty()
        : !accounts.account(principal).enabled()) throw refused();
    var owned =
        conversations.ownership(conversation).orElseThrow(OwnedConversationContext::refused);
    if (!principal.equals(owned.principal())
        || service && (owned.project() == null || owned.personal())
        || owned.project() != null
            && members
                .role(owned.project(), principal)
                .filter(role -> role.allows(ProjectRole.VIEWER))
                .isEmpty()) throw refused();
    var owner =
        owned.projectId() == null
            ? UsageAttribution.global(principal, UsageAttribution.Operation.AGENT_CHAT)
            : UsageAttribution.project(
                principal, owned.projectId(), UsageAttribution.Operation.AGENT_CHAT);
    return owner.withExecution(
        UsageLineage.root(conversation), UsageLineage.NONE, UsageLineage.NONE, null, null, null);
  }

  private static CallerFault refused() {
    return new CallerFault("Conversation context is unavailable to this account");
  }
}
