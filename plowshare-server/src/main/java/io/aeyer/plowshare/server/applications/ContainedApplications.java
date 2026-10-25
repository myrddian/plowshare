package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.protocol.ApplicationDeployment.File;
import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Parent-authorized creation of a confined child Application.
 *
 * <p>This is ADR 0005's deployment lifecycle with its admission rule and its destination replaced,
 * and nothing else: the package limits, the request-UUID receipt and the staging-before-commit
 * order are the same. What differs is who may ask and where the source lands. There is no
 * server-administrator check and no destination FileStore {@code MANAGER} check on this path — the
 * parent's own retained admission, narrowed by {@code OperationAuthority.contain}, is the
 * authority, and the server allocates the source area under its configured containment root. The
 * administrator-gated {@code application.deploy} path is untouched and keeps both of its checks.
 *
 * <p>Nesting is opt-in: the creating Application's own manifest must declare it, so no existing
 * Application acquires the capability by upgrade alone.
 *
 * <p><strong>A refusal is total.</strong> Every validation — the package, the child's declared
 * surfaces against the parent's hold, the breadth bound, the ceiling — completes before any durable
 * write and before any byte is written under the containment root. What follows runs in one unit of
 * work whose failure rolls back the project row, the contained scope, the principal, the allowance
 * and the containment record together, and whose staged source is collected on the way out.
 * Honouring a child's declaration of a surface beyond its parent's hold, and accepting the package
 * while silently dropping that declaration, are both failures.
 */
public interface ContainedApplications {
  /**
   * A creation request as the frame has already converted and bounded it.
   *
   * @param conversation the parent's own live conversation; its retained admission and its budget
   *     are the authority and the ceiling bound for this creation, and neither is caller-supplied
   * @param requestId the caller's idempotency key, unique within this container
   * @param ceiling the child's own allowance ceiling, which the parent sets and may later reduce to
   *     zero. Never absent: {@link io.aeyer.plowshare.server.allowances.AllowanceSnapshot}
   *     documents an absent ceiling as <em>unlimited</em>, so a contained child created without one
   *     would be unbounded. Zero is a child that cannot make a call, which is a permitted and
   *     useful state.
   * @param requestedDestination accepted for request-shape compatibility with ADR 0005 and
   *     deliberately ignored. A parent typically holds no FileStore {@code MANAGER} over its own
   *     retained source, so proxying a destination could not yield a legal one.
   * @param writableAreas runtime writable areas the child asks for; in this slice a child's
   *     writable area is allocated under its own server-owned root, so any entry here names a
   *     FileStore outside the parent's fence and is refused
   * @param files the child's complete source package
   */
  record Create(
      String conversation,
      UUID requestId,
      Integer ceiling,
      FileStoreReference requestedDestination,
      List<FileStoreReference> writableAreas,
      List<File> files) {
    public Create {
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(files, "files");
      writableAreas = List.copyOf(writableAreas == null ? List.of() : writableAreas);
      files = List.copyOf(files);
      if (conversation == null || conversation.isBlank() || conversation.length() > 1024)
        throw new IllegalArgumentException(
            "A contained Application is created inside the creating Application's conversation");
      // Absent is unlimited, so it is not representable here. Negative is not a ceiling at all.
      if (ceiling == null || ceiling < 0)
        throw new IllegalArgumentException(
            "A contained Application needs an explicit non-negative allowance ceiling");
    }
  }

  /**
   * The child as its container addresses it afterwards: read, reconfigure, destroy.
   *
   * <p>A boundary DTO, so every component is a plain wire value. {@code scope} is the authority
   * scope's identifier and deliberately <em>not</em> {@link ScopeId}: that is an internal value
   * object with no {@code @JsonValue}, so carrying one here would have published {@code {"value":
   * "..."}} as the public contract shape and let an internal type travel outward through five
   * generated SDKs. Internal callers wrap it back into a {@link ScopeId} where they need the typed
   * form; the conversion belongs at the boundary and the boundary is this record.
   */
  record Child(UUID id, UUID scope, String project, UUID revision) {
    public Child {
      Objects.requireNonNull(id);
      Objects.requireNonNull(scope);
      Objects.requireNonNull(revision);
      if (project == null || !project.startsWith("contained:"))
        throw new IllegalArgumentException("A contained project carries the reserved prefix");
    }
  }

  /** Validates, allocates and admits a child, or refuses leaving nothing behind. */
  Child create(String account, Create request);

  /**
   * Lowers or raises a child's own ceiling under its container's authority. Zero disables the
   * child's calls; {@code null} is refused, because absent means unlimited.
   *
   * <p>It takes a conversation for the same two reasons {@link #create} does, and they are the
   * reasons the two paths must not differ. The conversation's retained admission is the container's
   * authority, and it must be acting as the scope this child is recorded under — so a container
   * reconfigures its own children and nothing else. The conversation's budget is the pool the new
   * ceiling is bounded against, under exactly the bound creation applies: without it a container
   * could create a child with a legal ceiling and raise it past its own remaining allowance a
   * moment later, which would make the creation bound decorative. A raise is therefore permitted
   * <em>within</em> the container's remaining allowance rather than refused as a class.
   */
  void ceiling(String account, String conversation, UUID child, Integer calls);
}
