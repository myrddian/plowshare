package io.aeyer.plowshare.testpeer;

import java.nio.file.Path;
import java.util.List;

/** Mutable roots supplied only by server test fixtures, never a client runtime. */
public final class TestWorkspace {
  private volatile List<Path> roots = List.of();

  public List<Path> roots() {
    return roots;
  }

  public void set(List<Path> roots) {
    this.roots =
        roots.stream()
            .map(
                root -> {
                  try {
                    return root.toRealPath();
                  } catch (java.io.IOException absent) {
                    return root.toAbsolutePath().normalize();
                  }
                })
            .toList();
  }
}
