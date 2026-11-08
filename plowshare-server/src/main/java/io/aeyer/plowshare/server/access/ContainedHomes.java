package io.aeyer.plowshare.server.access;

/**
 * The second authorization path: whether a confined Application's own principal owns the named
 * project as its home.
 *
 * <p>This exists because {@link ScopedPermissions} cannot answer for a contained principal and must
 * not be taught to. V216 made {@code CONTAINED} an authority-free scope kind — no grant may target
 * one, and none is live against one — so {@code authority_project_role} resolves nothing for a
 * contained project, for the child and for its container alike. A contained principal additionally
 * holds no {@code authority_accounts} row (V218), so the role family's {@code legacy_handle} join
 * can never match it. Widening either would reverse a deliberate design; this answers the
 * containment question separately instead, and the caller dispatches on the principal's shape.
 *
 * <p><strong>Ownership, not reach.</strong> A true answer means "this project is this principal's
 * own contained home" and nothing more. It is not an ancestor's home, a sibling's or a
 * descendant's, and it confers no action by itself: the caller still decides which action a child
 * may take in its own home. In particular a child never changes an audience, so no caller may use
 * this to admit {@code SET_AUDIENCE}.
 *
 * <p>Implementations must fail closed: an unrecognised, non-contained or revoked principal owns
 * nothing, and an unavailable answer propagates rather than reading as ownership.
 */
public interface ContainedHomes {
  /**
   * Whether {@code actingPrincipal} owns {@code project} as its own contained home.
   *
   * <p>The principal is the <em>acting</em> axis — the child itself — never the accounting account,
   * because this is an authority question and attribution is a provenance one. Null or blank
   * arguments own nothing rather than raising, so an ordinary caller that has no contained
   * principal to offer needs no special case.
   */
  boolean ownsHome(String actingPrincipal, String project);

  /**
   * {@link #ownsHome} behind the cheap test that keeps it off the ordinary path.
   *
   * <p>Only a {@code contained:} project can be a contained home, so for every other name the
   * answer is false without a query. Every gate calls this rather than {@link #ownsHome} directly:
   * each one is reached by every ordinary request, and the first version of the information gate
   * put a round trip on that path for an answer it could not change. Keeping the guard here is what
   * stops the next gate repeating it.
   */
  default boolean ownsContainedHome(String actingPrincipal, String project) {
    if (project == null || !io.aeyer.plowshare.protocol.ContainedProjects.name(project))
      return false;
    return ownsHome(actingPrincipal, project);
  }

  /**
   * The fail-closed default, for compositions with no containment source wired.
   *
   * <p>With this in place every contained principal is refused at the gates exactly as it was
   * before the second path existed, so an incomplete composition loses the capability rather than
   * opening one.
   */
  ContainedHomes NONE = (actingPrincipal, project) -> false;
}
