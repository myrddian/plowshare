package io.aeyer.plowshare.server.access;

import java.util.List;
import java.util.UUID;

/** Live scoped action checks, independent of tools, resource policy and operation sandboxes. */
public interface ScopedAuthority {
  boolean allows(GrantRecipient recipient, AuthorityScope target, AuthorityAction action);

  /** Checks one specific issuing grant; another binding must not repair its narrower ceiling. */
  boolean allowsUsing(
      GrantRecipient recipient, AuthorityScope target, AuthorityAction action, UUID grant);

  /**
   * As {@link #allowsUsing(GrantRecipient, AuthorityScope, AuthorityAction, UUID)}, for a caller
   * that has already read {@code grants.live(recipient)} within the same walk or admission.
   *
   * <p>{@code live} must be that read for exactly {@code recipient}; the grant is selected from it
   * by id and every other check is unchanged, so a grant absent from {@code live} (revoked, expired
   * or foreign) is refused exactly as the id-only form refuses it. Passing a list read earlier than
   * the decision it supports is the caller's freshness choice and must stay within one operation.
   */
  boolean allowsUsing(
      GrantRecipient recipient,
      AuthorityScope target,
      AuthorityAction action,
      UUID grant,
      List<ScopedGrantRepository.Binding> live);
}
