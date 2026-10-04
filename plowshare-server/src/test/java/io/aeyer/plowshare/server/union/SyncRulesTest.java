package io.aeyer.plowshare.server.union;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncRulesTest {

  @TempDir Path dir;

  @Test
  void ordinary_paths_are_allowed() {
    assertTrue(SyncRules.allowed("src/a.ts", List.of()));
  }

  @Test
  void hidden_paths_need_the_allowlist() {
    assertFalse(SyncRules.allowed(".env", List.of()));
    assertFalse(SyncRules.allowed("config/.npmrc", List.of()));
    assertTrue(SyncRules.allowed(".eslintrc", List.of(".eslintrc")));
    assertTrue(SyncRules.allowed(".github/workflows/ci.yml", List.of(".github/")));
    assertFalse(SyncRules.allowed(".githubx/a", List.of(".github/")));
  }

  @Test
  void git_and_plowshare_directories_are_never_allowed() {
    assertFalse(SyncRules.allowed(".git/config", List.of(".git/")));
    assertFalse(SyncRules.allowed(".plowshare/project", List.of(".plowshare/")));
  }

  @Test
  void git_and_plowshare_directories_are_refused_at_any_depth() {
    assertFalse(SyncRules.allowed("sub/.plowshare/project", List.of("sub/.plowshare/")));
    assertFalse(SyncRules.allowed("vendor/lib/.git/HEAD", List.of("vendor/lib/.git/")));
  }

  @Test
  void git_and_plowshare_directories_are_refused_regardless_of_case_unicode_or_stream_suffix() {
    assertFalse(SyncRules.allowed(".GIT/config", List.of()));
    assertFalse(SyncRules.allowed("sub/.Git/HEAD", List.of()));
    assertFalse(SyncRules.allowed(".gi‌t/x", List.of(".GIT/")));
    assertFalse(SyncRules.allowed(".git::$INDEX_ALLOCATION/x", List.of()));
    assertTrue(SyncRules.allowed(".github/workflows/ci.yml", List.of(".github/")));
  }

  // A bare ':' in an otherwise ordinary name is legal on macOS and Linux (a timestamp is a
  // common case) — allowed() must not refuse it outright the way an earlier revision did; only
  // reservedSegment's own cut-at-first-':' normalisation keeps a `.git::$DATA`-shaped name
  // refused.
  @Test
  void a_bare_colon_in_an_ordinary_name_is_allowed() {
    assertTrue(SyncRules.allowed("2026-09-14T10:00.md", List.of()));
    assertFalse(SyncRules.allowed(".git::$INDEX_ALLOCATION/x", List.of()));
  }

  @Test
  void reservedSegment_folds_case_unicode_and_the_stream_suffix() {
    assertTrue(SyncRules.reservedSegment(".git"));
    assertTrue(SyncRules.reservedSegment(".GIT"));
    assertTrue(SyncRules.reservedSegment(".git."));
    assertTrue(SyncRules.reservedSegment(".git "));
    assertTrue(SyncRules.reservedSegment("git~1"));
    assertTrue(SyncRules.reservedSegment(".gi‌t"));
    assertTrue(SyncRules.reservedSegment(".GIT::$INDEX_ALLOCATION"));
    assertTrue(SyncRules.reservedSegment(".PLOWSHARE."));
    assertFalse(SyncRules.reservedSegment(".github"));
    assertFalse(SyncRules.reservedSegment(".gitignore"));
  }

  @Test
  void a_commit_holding_a_hidden_file_is_a_breach() throws Exception {
    Hub hub = committed(".env", "SECRET=1");
    try (Repository repo = hub.openBare()) {
      Optional<SyncRules.Breach> breach =
          SyncRules.check(repo, null, hub.main().orElseThrow(), List.of(), 1_000);
      assertEquals(".env", breach.orElseThrow().path());
    }
  }

  @Test
  void a_commit_holding_a_file_over_the_cap_is_a_breach() throws Exception {
    Hub hub = committed("big.bin", "x".repeat(2_000));
    try (Repository repo = hub.openBare()) {
      assertEquals(
          "big.bin",
          SyncRules.check(repo, null, hub.main().orElseThrow(), List.of(), 1_000)
              .orElseThrow()
              .path());
    }
  }

  @Test
  void a_clean_commit_passes() throws Exception {
    Hub hub = committed("src/a.ts", "ok");
    try (Repository repo = hub.openBare()) {
      assertEquals(
          Optional.empty(),
          SyncRules.check(repo, null, hub.main().orElseThrow(), List.of(), 1_000));
    }
  }

  @Test
  void only_what_a_commit_adds_or_changes_over_its_parent_is_checked() throws Exception {
    Hub hub = committed("big.bin", "x".repeat(2_000));
    String parent = hub.main().orElseThrow();
    Files.writeString(hub.tree().resolve("b.txt"), "ok");
    String child = hub.commitTree("t", "t").orElseThrow();
    try (Repository repo = hub.openBare()) {
      assertEquals(
          Optional.empty(),
          SyncRules.check(repo, parent, child, List.of(), 1_000),
          "a file that was already over the cap is not this push's breach");
      assertEquals(
          "big.bin", SyncRules.check(repo, null, child, List.of(), 1_000).orElseThrow().path());
    }
  }

  @Test
  void a_modified_file_over_the_cap_is_still_a_breach_against_its_parent() throws Exception {
    Hub hub = committed("big.bin", "small");
    String parent = hub.main().orElseThrow();
    Files.writeString(hub.tree().resolve("big.bin"), "x".repeat(2_000));
    String child = hub.commitTree("t", "t").orElseThrow();
    try (Repository repo = hub.openBare()) {
      assertEquals(
          "big.bin", SyncRules.check(repo, parent, child, List.of(), 1_000).orElseThrow().path());
    }
  }

  private Hub committed(String path, String content) throws Exception {
    Hub hub = new Hub(dir.resolve("p"));
    hub.create();
    Path file = hub.tree().resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
    hub.commitTree("t", "t").orElseThrow();
    return hub;
  }
}
