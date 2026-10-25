package io.aeyer.plowshare.server.applications;

/**
 * The one question a parent revision change must ask about containment.
 *
 * <p>A parent revision change <strong>refuses</strong> while any child is live. Letting children
 * survive the change, and destroying them silently during an ordinary update, were both rejected by
 * <a href="../../../../../../../../docs/decisions/0042-nested-application-containment.md">ADR
 * 0042</a>: each produces the orphan the design names as failure — a child running under
 * configuration that no longer exists, or a child removed by an operation nobody asked to remove
 * anything. Refusing leaves the container's operator holding both facts and the choice.
 *
 * <p>Deliberately one method and deliberately a refusal rather than a count: the caller is
 * activation, which has no business deciding what a non-zero answer means, and a boolean read that
 * a call site may forget to act on is the shape this interface exists to avoid.
 *
 * <p><strong>It must be asked under the project row lock.</strong> The answer is only as good as
 * the lock that holds it: outside one, a child row can land between the question and the activation
 * that relied on it. Creation takes the same container project row lock, so the two contend.
 */
public interface ContainmentFence {
  /**
   * Refuses when this project's authority scope holds a live contained Application beneath it.
   *
   * @throws io.aeyer.plowshare.server.faults.CallerFault naming the destruction the caller must
   *     perform first. An ordinary project with no children — which is every pre-existing project —
   *     passes.
   */
  void requireNoLiveChildren(String project);
}
