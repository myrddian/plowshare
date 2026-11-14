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

  /**
   * Opens a checking session over a live-grant list the caller already read for {@code recipient}.
   * The session may remember, for its own lifetime only, facts that are expensive to re-derive and
   * cannot differ within one decision: it must hold no state that outlives the object, and a caller
   * must discard it when the walk or admission it serves ends.
   */
  Walk walk(GrantRecipient recipient, List<ScopedGrantRepository.Binding> live);

  /** One walk's checks; same contract per call as the five-argument {@code allowsUsing}. */
  interface Walk {
    boolean allows(AuthorityScope target, AuthorityAction action, UUID grant);
  }
}
