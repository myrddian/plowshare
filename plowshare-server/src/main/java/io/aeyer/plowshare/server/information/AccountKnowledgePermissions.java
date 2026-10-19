package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.access.AuthorityAction;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;

/** Live account-home admission; repositories additionally lock authority and resource policy. */
@FunctionalInterface
public interface AccountKnowledgePermissions {
  boolean allows(String actor, AccountId account, AuthorityAction action);

  /** Isolated legacy fixtures have no account-home authority. Production supplies the live gate. */
  AccountKnowledgePermissions DENY = (actor, account, action) -> false;
}
