package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResolvedApplicationRootsTest {
  private static final Path ROOT = Path.of("application");

  @Test
  void resolves_each_project_root_once_including_absent_ones() {
    var calls = new AtomicInteger();
    var roots =
        new ResolvedApplicationRoots(
            id -> {
              calls.incrementAndGet();
              return id == null ? Optional.empty() : Optional.of(ROOT);
            });

    assertEquals(Optional.of(ROOT), roots.root(7L));
    assertEquals(Optional.of(ROOT), roots.root(7L));
    assertEquals(Optional.empty(), roots.root(null));
    assertEquals(Optional.empty(), roots.root(null));
    assertEquals(2, calls.get());
  }

  @Test
  void directories_reuse_the_memoised_root() {
    var calls = new AtomicInteger();
    var roots =
        new ResolvedApplicationRoots(
            id -> {
              calls.incrementAndGet();
              return Optional.of(ROOT);
            });

    assertEquals(Optional.of(ROOT.resolve("agents")), roots.directory(7L, "agents"));
    assertEquals(Optional.of(ROOT.resolve("bots")), roots.directory(7L, "bots"));
    assertEquals(1, calls.get());
  }

  @Test
  void a_refused_root_is_not_memoised_and_is_refused_every_time() {
    var calls = new AtomicInteger();
    var roots =
        new ResolvedApplicationRoots(
            id -> {
              calls.incrementAndGet();
              throw new IllegalStateException("refused");
            });

    assertThrows(IllegalStateException.class, () -> roots.root(7L));
    assertThrows(IllegalStateException.class, () -> roots.directory(7L, "agents"));
    assertEquals(2, calls.get());
  }
}
