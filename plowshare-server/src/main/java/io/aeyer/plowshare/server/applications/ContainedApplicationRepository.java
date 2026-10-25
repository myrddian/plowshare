package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the containment relation's own rows: the child project, its {@code CONTAINED} authority
 * scope and the creation record that makes a retry idempotent and a subtree countable.
 *
 * <p>It exists because no other repository may create one of these. {@code ProjectStore.ordinary}
 * reserves the {@code contained:} prefix case-insensitively, precisely so that a project with no
 * authority scope cannot be made through an ordinary path, and V195 excludes that prefix from
 * {@code authority_map_project} so a child never auto-acquires an addressable {@code PROJECT}
 * scope. The explicit {@code CONTAINED} insert therefore has exactly one home, and this is it.
 *
 * <p>{@link #create} inserts the project row, the scope row and the record as one statement
 * sequence inside the caller's transaction, so there is no state in which a child project exists
 * without its scope, or a scope without its record. Nothing here issues a principal, retains an
 * allowance or stages a file: those belong to their own owners, joined into the same unit of work
 * by the service.
 */
public interface ContainedApplicationRepository {
  /** A retained child. {@code live} is false once its container has destroyed it. */
  record Retained(
      UUID id,
      ScopeId scope,
      long projectId,
      String project,
      ScopeId container,
      UUID requestId,
      String fingerprint,
      UUID revision,
      AllowanceId allowance,
      Integer ceiling,
      boolean live) {
    public Retained {
      Objects.requireNonNull(id);
      Objects.requireNonNull(scope);
      Objects.requireNonNull(container);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(revision);
      Objects.requireNonNull(allowance);
      if (project == null || !project.startsWith("contained:") || projectId <= 0)
        throw new IllegalArgumentException("A contained record needs its prefixed project");
      if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("A contained record needs its request fingerprint");
      if (ceiling == null || ceiling < 0)
        throw new IllegalArgumentException("A contained record needs an explicit ceiling");
    }
  }

  /** Everything the durable creation needs, all of it already validated by the service. */
  record Creation(
      ScopeId container,
      UUID requestId,
      String fingerprint,
      String project,
      Path workspace,
      UUID revision,
      AllowanceId allowance,
      Integer ceiling) {
    public Creation {
      Objects.requireNonNull(container);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(revision);
      Objects.requireNonNull(allowance);
      if (project == null || !project.startsWith("contained:"))
        throw new IllegalArgumentException("A contained project carries the reserved prefix");
      if (workspace == null || !workspace.isAbsolute())
        throw new IllegalArgumentException("A contained workspace is an absolute server path");
      if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("A contained creation needs its request fingerprint");
      if (ceiling == null || ceiling < 0)
        throw new IllegalArgumentException("A contained creation needs an explicit ceiling");
    }
  }

  /**
   * The record a previous identical request left, by container and request UUID. Absence is an
   * ordinary answer and never authorizes a replay by itself; the caller compares fingerprints.
   */
  Optional<Retained> receipt(ScopeId container, UUID requestId);

  /**
   * Live descendants anywhere beneath this container, not merely its immediate children. Depth and
   * breadth each multiply a parent's budget, so the aggregate is what the breadth bound limits.
   */
  int liveDescendants(ScopeId container);

  /** Inserts the project, the contained scope and the record together; refuses a duplicate. */
  Retained create(Creation creation);

  Optional<Retained> find(UUID id);

  /**
   * The live Applications this container holds <em>directly</em>, oldest first.
   *
   * <p>Direct and not the subtree, deliberately. A container addresses what it created; reaching a
   * grandchild is a read of that grandchild, authorized by walking the containment chain from the
   * grandchild upwards, which is the direction the stored rows answer and the direction a sibling
   * can never travel. Returning the transitive subtree here would hand a reader rows it has not
   * asked to be authorized for, and no caller needs them.
   */
  List<Retained> children(ScopeId container);

  /**
   * The record a contained authority scope belongs to, if it is a contained scope at all.
   *
   * <p>This is the containment relation read in the one direction containment permits: a scope's
   * own row names its container, so an ancestry walk only ever climbs. Absence is an ordinary
   * answer meaning "not a contained scope" — an uncontained parent, or a scope that never existed —
   * and it authorizes nothing by itself.
   */
  Optional<Retained> ofScope(ScopeId scope);

  /** Records an authorized ceiling change; the allowance itself is the allowance owner's row. */
  void ceiling(UUID id, Integer calls);
}
