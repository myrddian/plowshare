package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.server.archive.ProjectRole;

/** Permission checks for an already authenticated Application acting for a verified subject. */
public interface ActingAuthorization {
  /**
   * Rechecks live identity, consent and scope, returning the effective role at this check. Throws a
   * caller refusal on insufficient authority; source failures propagate. The result is not a lease
   * or a replacement for agent/tool, resource ownership, filesystem or transaction checks.
   *
   * <p>Direct requests deliberately do not fit this signature and keep existing authorization.
   */
  ProjectRole require(
      RequestAuthority.Delegated authority, ActingPermission permission, ActingTarget target);
}
