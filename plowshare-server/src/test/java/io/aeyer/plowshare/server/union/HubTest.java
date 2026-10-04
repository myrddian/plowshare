package io.aeyer.plowshare.server.union;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HubTest {

  @TempDir Path dir;
  @TempDir Path clientDir;

  @Test
  void a_new_hub_is_bare_with_no_main() throws Exception {
    Hub hub = new Hub(dir.resolve("7"));
    assertFalse(hub.exists());
    hub.create();
    assertTrue(hub.exists());
    assertTrue(Files.isDirectory(hub.bare()));
    assertEquals(Optional.empty(), hub.main());
  }

  @Test
  void a_commit_pushed_from_a_client_is_checked_out_into_the_tree() throws Exception {
    Hub hub = new Hub(dir.resolve("7"));
    hub.create();
    String pushed = pushFile(hub, "src/a.ts", "one\n");
    assertEquals(Optional.of(pushed), hub.main());
    hub.resetTree(pushed);
    assertEquals("one\n", Files.readString(hub.tree().resolve("src/a.ts")));
  }

  @Test
  void a_dirty_tree_commits_as_the_named_author_and_a_clean_one_does_not() throws Exception {
    Hub hub = new Hub(dir.resolve("7"));
    hub.create();
    hub.resetTree(pushFile(hub, "src/a.ts", "one\n"));
    assertEquals(Optional.empty(), hub.commitTree("nightly-bot", "run r1"));

    Files.writeString(hub.tree().resolve("src/a.ts"), "two\n");
    Files.writeString(hub.tree().resolve("src/b.ts"), "new\n");
    Optional<String> committed = hub.commitTree("nightly-bot", "run r1");

    assertTrue(committed.isPresent());
    assertEquals(committed, hub.main());
    try (Git git = Git.open(hub.bare().toFile())) {
      PersonIdent author = git.log().setMaxCount(1).call().iterator().next().getAuthorIdent();
      assertEquals("nightly-bot", author.getName());
    }
  }

  @Test
  void a_deleted_file_is_committed_as_a_deletion() throws Exception {
    Hub hub = new Hub(dir.resolve("7"));
    hub.create();
    hub.resetTree(pushFile(hub, "gone.txt", "x\n"));
    Files.delete(hub.tree().resolve("gone.txt"));
    String commit = hub.commitTree("bot", "run r2").orElseThrow();
    hub.resetTree(commit);
    assertFalse(Files.exists(hub.tree().resolve("gone.txt")));
  }

  @Test
  void reset_records_the_commit_the_tree_is_at_and_a_commit_moves_it() throws Exception {
    Hub hub = new Hub(dir.resolve("7"));
    hub.create();
    assertEquals(Optional.empty(), hub.treeAt());
    String pushed = pushFile(hub, "a.txt", "one\n");
    hub.resetTree(pushed);
    assertEquals(Optional.of(pushed), hub.treeAt());
    Files.writeString(hub.tree().resolve("a.txt"), "two\n");
    String committed = hub.commitTree("bot", "run r1").orElseThrow();
    assertEquals(Optional.of(committed), hub.treeAt());
  }

  @Test
  void a_push_the_tree_was_never_reset_to_strands_the_dirty_tree_instead_of_reverting_it()
      throws Exception {
    Hub hub = new Hub(dir.resolve("7"));
    hub.create();
    String first = pushFile(hub, "a.txt", "one\n");
    hub.resetTree(first);
    // main moves without resetTree: pushed() threw, or the server died before post-receive.
    String second = pushFile(hub, "b.txt", "pushed\n");
    Files.writeString(hub.tree().resolve("c.txt"), "dirty\n");

    assertEquals(Optional.empty(), hub.commitTree("bot", "run r1"));

    assertEquals(Optional.of(second), hub.main(), "main must not be moved by a stale tree");
    try (Repository repo = hub.openBare();
        RevWalk walk = new RevWalk(repo)) {
      List<Ref> stranded = repo.getRefDatabase().getRefsByPrefix(Hub.STRANDED);
      assertEquals(1, stranded.size());
      RevCommit commit = walk.parseCommit(stranded.get(0).getObjectId());
      assertEquals(first, commit.getParent(0).getName());
      try (TreeWalk found = TreeWalk.forPath(repo, "c.txt", commit.getTree())) {
        assertTrue(found != null, "the stranded commit holds the dirty file");
        assertEquals("dirty\n", new String(repo.open(found.getObjectId(0)).getBytes()));
      }
    }
    assertEquals(Optional.of(second), hub.treeAt());
    assertEquals("pushed\n", Files.readString(hub.tree().resolve("b.txt")));
    assertFalse(Files.exists(hub.tree().resolve("c.txt")), "the tree now matches main");
  }

  @Test
  void a_stale_but_clean_tree_is_reset_to_main_without_stranding() throws Exception {
    Hub hub = new Hub(dir.resolve("7"));
    hub.create();
    hub.resetTree(pushFile(hub, "a.txt", "one\n"));
    String second = pushFile(hub, "b.txt", "pushed\n");

    assertEquals(Optional.empty(), hub.commitTree("bot", "run r1"));

    assertEquals(Optional.of(second), hub.main());
    try (Repository repo = hub.openBare()) {
      assertTrue(repo.getRefDatabase().getRefsByPrefix(Hub.STRANDED).isEmpty());
    }
    assertEquals("pushed\n", Files.readString(hub.tree().resolve("b.txt")));
  }

  @Test
  void a_fresh_hub_has_no_client_commit_time() throws Exception {
    Hub hub = new Hub(dir.resolve("7"));
    hub.create();

    assertEquals(Optional.empty(), hub.lastClientCommitTime());
  }

  @Test
  void main_holding_only_server_commits_has_no_client_commit_time() throws Exception {
    Hub hub = new Hub(dir.resolve("7"));
    hub.create();
    Files.writeString(hub.tree().resolve("a.txt"), "x");
    hub.commitTree("nightly-bot", "run r1").orElseThrow();

    assertEquals(Optional.empty(), hub.lastClientCommitTime());
  }

  @Test
  void last_client_commit_time_skips_server_commits() throws Exception {
    Hub hub = new Hub(dir.resolve("7"));
    hub.create();
    String pushed = pushFile(hub, "src/a.ts", "one\n");
    hub.resetTree(pushed);
    Files.writeString(hub.tree().resolve("src/a.ts"), "two\n");
    hub.commitTree("nightly-bot", "run r1").orElseThrow();

    Instant pushedCommitterTime = committerTimeOf(hub, pushed);

    assertEquals(Optional.of(pushedCommitterTime), hub.lastClientCommitTime());
  }

  /**
   * The committer time JGit itself records for {@code commitId}, read straight off the hub -- the
   * independent read the test compares against.
   */
  private static Instant committerTimeOf(Hub hub, String commitId) throws Exception {
    try (Repository repo = hub.openBare();
        RevWalk walk = new RevWalk(repo)) {
      return walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(commitId))
          .getCommitterIdent()
          .getWhenAsInstant();
    }
  }

  @Test
  void delete_removes_the_hub_and_the_tree() throws Exception {
    Hub hub = new Hub(dir.resolve("7"));
    hub.create();
    hub.resetTree(pushFile(hub, "a", "x"));
    hub.delete();
    assertFalse(Files.exists(dir.resolve("7")));
  }

  /** Commits one file in a throwaway clone and pushes it to the hub's main. */
  private String pushFile(Hub hub, String path, String content) throws Exception {
    Path work = Files.createTempDirectory(clientDir, "c");
    try (Git client =
        Git.cloneRepository()
            .setURI(hub.bare().toUri().toString())
            .setDirectory(work.toFile())
            .call()) {
      Path file = work.resolve(path);
      Files.createDirectories(file.getParent() == null ? work : file.getParent());
      Files.writeString(file, content);
      client.add().addFilepattern(".").call();
      String id =
          client
              .commit()
              .setMessage("c")
              .setAuthor("enzo@laptop", "enzo@laptop")
              .setCommitter("enzo@laptop", "enzo@laptop")
              .call()
              .getName();
      client
          .push()
          .setRemote("origin")
          .setRefSpecs(new org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/main"))
          .call();
      return id;
    }
  }
}
