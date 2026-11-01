package io.aeyer.plowshare.server.applications;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live staging claims of this process.
 *
 * <p>A set and nothing more. Concurrent because the sweep runs on the scheduler's thread while
 * creations run on request threads, and bounded by the breadth bound times the number of concurrent
 * creations, each entry removed by the {@code finally} that released it.
 */
public final class InProcessContainedStaging implements ContainedStaging {
  private final Set<String> claims = ConcurrentHashMap.newKeySet();

  @Override
  public void claim(String project) {
    claims.add(Objects.requireNonNull(project));
  }

  @Override
  public void release(String project) {
    claims.remove(project);
  }

  @Override
  public boolean staging(String project) {
    return claims.contains(project);
  }

  @Override
  public Set<String> claimed() {
    return Set.copyOf(claims);
  }
}
