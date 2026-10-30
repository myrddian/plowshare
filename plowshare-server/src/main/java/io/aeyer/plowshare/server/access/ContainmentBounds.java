package io.aeyer.plowshare.server.access;

/**
 * Server-configured limits on containment: how deep a chain of contained scopes may run, and how
 * many live descendants a container may hold at once. Depth and breadth each multiply a parent's
 * budget, so they are bounded separately.
 *
 * <p>These are operator configuration and never a requesting agent's argument, which is why the
 * value is injected into the component that enforces it rather than passed to {@code
 * OperationAuthority.contain}. A caller that could supply its own limit would not be limited.
 *
 * <p><strong>Absence is representable on purpose.</strong> The constructor deliberately does not
 * reject a non-positive bound, so that a server which has not configured containment can still be
 * built and still boot. The refusal belongs at the point of use instead: {@code contain} refuses
 * every hop unless {@link #configured()} holds, so an unconfigured server declines containment
 * rather than nesting without limit or failing startup for a capability it does not use. There is
 * no code path on which an absent bound becomes an unlimited one.
 *
 * @param maxDepth the greatest permitted length of a container chain; a hop producing a longer
 *     chain is refused
 * @param maxLiveDescendants the greatest permitted number of simultaneously live descendants under
 *     one container
 */
public record ContainmentBounds(int maxDepth, int maxLiveDescendants) {
  private static final ContainmentBounds ABSENT = new ContainmentBounds(0, 0);

  /** The bound a server that has not configured containment holds; every hop refuses under it. */
  public static ContainmentBounds absent() {
    return ABSENT;
  }

  /**
   * Whether both bounds were actually configured. Either one missing leaves containment
   * unconfigured as a whole: a depth limit without a breadth limit bounds nothing in aggregate, and
   * a breadth limit without a depth limit bounds nothing at depth.
   */
  public boolean configured() {
    return maxDepth > 0 && maxLiveDescendants > 0;
  }
}
