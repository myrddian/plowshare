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

  /** Why a source area was collected. {@code SWEPT} is recovery; {@code DESTROYED} is the act. */
  enum Cause {
    DESTROYED,
    SWEPT
  }

  /**
   * One durable record that a contained source area was collected.
   *
   * <p>This is what makes collection observable, and it is the one row in this relation that
   * deliberately holds <strong>no foreign key at all</strong>. Everything it describes is being
   * deleted as it is written — the scope, the project and the {@link Retained} record all go in the
   * same cascade — so a constraint into any of them would delete the history along with the thing
   * it is history of.
   *
   * <p>It is also not a release. There is no digest, no file count and no destination here: ADR
   * 0005's retained release history records what may be activated, and a destroyed child may not be
   * activated at all. Promotion is a separate explicit act and is not this row.
   *
   * <p>It is also the receipt {@code destroy} resolves delivery uncertainty against, and {@code
   * scope} is recorded for that. Without the destroyed child's own scope there is no link from a
   * parent's collection row to its descendants' rows, so a retried destruction could only answer
   * about the one record it named — a <em>different</em> answer from the original, which is worse
   * than refusing. With it, the destroyed subtree is rebuilt exactly.
   *
   * @param scope the destroyed child's own contained scope, or {@code null} for a {@code SWEPT}
   *     stage, which had no scope to record
   * @param container the scope that held this child, or {@code null} for a {@code SWEPT} stage,
   *     which by definition had no committed record to name one
   * @param revision the child's single revision, or {@code null} for a {@code SWEPT} stage
   * @param workspace the source area this record accounts for. Its removal is marked separately by
   *     {@link #sourceCollected}, because the filesystem cannot join the transaction: an unmarked
   *     row is therefore exactly the work an interrupted removal left behind.
   * @param sourceRemoved whether those bytes are known to be gone
   */
  record Collection(
      UUID child,
      ScopeId scope,
      ScopeId container,
      String project,
      UUID revision,
      Path workspace,
      Cause cause,
      boolean sourceRemoved) {
    public Collection {
      Objects.requireNonNull(child);
      Objects.requireNonNull(cause);
      if (project == null || !project.startsWith("contained:"))
        throw new IllegalArgumentException("A collection accounts for a contained project");
      if (workspace == null || !workspace.isAbsolute())
        throw new IllegalArgumentException("A collection names an absolute server path");
      if (cause == Cause.DESTROYED && (container == null || revision == null || scope == null))
        throw new IllegalArgumentException(
            "A destroyed child names its own scope, its container and its revision");
      if (cause == Cause.SWEPT && (container != null || revision != null || scope != null))
        throw new IllegalArgumentException(
            "A swept stage had no committed record, so it names no scope, container or revision");
    }
  }

  /**
   * Every contained record in this scope's subtree, <strong>deepest first</strong>, including the
   * scope's own record.
   *
   * <p>The order is the contract and not a convenience. {@code authority_scopes.container} is
   * {@code ON DELETE RESTRICT} (V195), so a container's rows cannot be removed while a descendant
   * still references them: destroying in any other order is refused by the schema. Live and
   * destroyed alike are returned, because a record that is still present is still a row that must
   * go before its container's.
   */
  List<Retained> subtree(ScopeId scope);

  /**
   * Removes one contained Application's rows by deleting its project.
   *
   * <p>One statement, and the two-hop cascade does the rest: {@code authority_scopes.project_id} is
   * {@code ON DELETE CASCADE} (V155), and {@code contained_principals.scope_id} and {@code
   * contained_applications.scope_id} cascade from the scope (V197, V198). So the project, the
   * contained scope, the principal and this record either all go or none do, with no second code
   * path to forget one of them.
   *
   * <p>The delete is constrained to the reserved {@code contained:} prefix in SQL, so this method
   * cannot reach an ordinary Application's project — and therefore cannot reach its retained
   * revisions — whatever it is passed. Callers destroy deepest first; see {@link #subtree}.
   */
  void destroy(UUID id);

  /** Writes the collection record. Inside the destroying transaction, before the rows go. */
  void collected(Collection record);

  /** Marks a collection's source area as removed from disk. Idempotent; absence is not an error. */
  void sourceCollected(UUID child);

  /**
   * Collections whose source area has not been marked removed: the sweep's precise work list. A row
   * reaches this list only when a destruction committed and the removal that follows it did not.
   */
  List<Collection> uncollectedSources();

  /**
   * The collection record of one child, if it was ever collected.
   *
   * <p>This is what makes a retried {@code destroy} answerable. It survives the cascade that
   * removed the child's {@link Retained} row, so it can still say that the destruction happened,
   * whose child it was and what its source area was.
   */
  Optional<Collection> collection(UUID child);

  /**
   * The collection records of the children one scope directly held, so a destroyed subtree can be
   * rebuilt by descending the recorded containment edges. {@code DESTROYED} rows only; a swept
   * stage has no scope and so is on no chain.
   */
  List<Collection> collectedUnder(ScopeId container);

  /**
   * Whether a contained record exists for this project name. A staged area with none is garbage.
   */
  boolean committed(String project);

  /**
   * Live contained Applications anywhere beneath the authority scope of this <em>project name</em>.
   *
   * <p>Keyed on the project name rather than on a scope because its one caller is activation, which
   * knows the project it has just locked and must not resolve a scope to ask this. An ordinary
   * project with no children answers zero, which is every pre-existing project.
   */
  int liveChildrenOfProject(String project);

  /**
   * Takes the container's own project row lock, in the caller's transaction.
   *
   * <p>This is the same row activation locks, which is the whole point: creation and a parent
   * revision change contend on one lock, so they cannot interleave between activation's live-child
   * count and creation's insert. Without it the count could be taken a moment before a child row
   * lands and both would commit, which is the orphan this fence exists to prevent.
   */
  void lockContainer(ScopeId container);
}
