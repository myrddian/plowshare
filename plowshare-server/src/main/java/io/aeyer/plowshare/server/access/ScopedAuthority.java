package io.aeyer.plowshare.server.access;

/** Live scoped action checks, independent of tools, resource policy and operation sandboxes. */
public interface ScopedAuthority {
  boolean allows(GrantRecipient recipient, AuthorityScope target, AuthorityAction action);

  /** Checks one specific issuing grant; another binding must not repair its narrower ceiling. */
  boolean allowsUsing(
      GrantRecipient recipient,
      AuthorityScope target,
      AuthorityAction action,
      java.util.UUID grant);
}
