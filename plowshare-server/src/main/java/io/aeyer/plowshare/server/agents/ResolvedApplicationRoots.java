package io.aeyer.plowshare.server.agents;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Memoises {@link ApplicationResources#root} per project id for one operation.
 *
 * <p>Resolving a root reads the project, its manifest and its workspace fence. One definition
 * lookup asks for the same root several times (key, stamps, directories, tool revision), so the
 * lookup creates one instance, passes it down and drops it. It is deliberately never stored in a
 * field: a root cached beyond one operation would outlive a workspace move or manifest change, and
 * the only freshness guarantee here is "once per operation".
 *
 * <p>A root that throws is not memoised; every request repeats the delegate and rethrows, so a
 * refusal is never turned into a later success or a stale answer. {@link #directory} feeds the
 * memoised root to the delegate, which still performs its own final fence re-check.
 *
 * <p>Instances are confined to the calling thread and are not thread-safe.
 */
final class ResolvedApplicationRoots implements ApplicationResources {
  private final ApplicationResources delegate;
  private final Map<Long, Optional<Path>> roots = new HashMap<>();

  ResolvedApplicationRoots(ApplicationResources delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public Optional<Path> root(Long projectId) {
    Optional<Path> known = roots.get(projectId);
    if (known != null) return known;
    Optional<Path> resolved = delegate.root(projectId);
    roots.put(projectId, resolved);
    return resolved;
  }

  @Override
  public Optional<Path> directory(Long projectId, String relative) {
    return delegate.directory(projectId, relative, root(projectId));
  }

  @Override
  public Optional<Path> directory(Long projectId, String relative, Optional<Path> root) {
    return delegate.directory(projectId, relative, root);
  }
}
