package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;

/**
 * The live-child fence, over the containment relation's own read.
 *
 * <p>It holds the containment read and <strong>nothing else</strong>, and that absence is the
 * design rather than an accident of what it happens to need: there is no collaborator here through
 * which a destruction, a principal revocation or a filesystem removal could be reached, so
 * "activation does not destroy a live child" is a property of this object and not a behaviour a
 * later edit could quietly change. A reflective assertion in {@code ContainedDestructionTest} keeps
 * it that way.
 */
public final class SourceContainmentFence implements ContainmentFence {
  private final ContainedApplicationRepository records;

  public SourceContainmentFence(ContainedApplicationRepository records) {
    this.records = Objects.requireNonNull(records);
  }

  @Override
  public void requireNoLiveChildren(String project) {
    if (records.liveChildrenOfProject(project) > 0)
      throw new CallerFault(
          "This Application holds a live contained Application; destroy it before changing the"
              + " revision that authorized it");
  }
}
