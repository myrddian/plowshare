package io.aeyer.plowshare.server.union;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.data.DataLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.http.server.GitServlet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class HubServletTest {

  @TempDir static Path data;
  @TempDir Path client;

  static Hubs hubs;
  static UnionGate gate;
  static ConfigurableApplicationContext app;
  static int port;
  static final java.util.concurrent.ConcurrentHashMap<
          String, io.aeyer.plowshare.server.archive.ProjectRole>
      grants = new java.util.concurrent.ConcurrentHashMap<>();
  static final java.util.Set<String> removed = java.util.concurrent.ConcurrentHashMap.newKeySet();
  static final java.util.Set<String> revokeDuringReceive =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  static final java.util.concurrent.ConcurrentHashMap<String, Integer> writes =
      new java.util.concurrent.ConcurrentHashMap<>();

  @BeforeAll
  static void start() {
    hubs = new Hubs(new DataLayout(data).initialise(), name -> (long) Math.abs(name.hashCode()));
    gate =
        new UnionGate(
            hubs, advanced -> {}, Instant::now, Duration.ofSeconds(1), Duration.ofMinutes(2));
    app =
        new SpringApplicationBuilder(Wiring.class)
            .web(WebApplicationType.SERVLET)
            .run("--server.port=0", "--server.address=127.0.0.1");
    port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  @Configuration
  @ImportAutoConfiguration(ServletWebServerFactoryAutoConfiguration.class)
  static class Wiring {
    @Bean
    ServletRegistrationBean<GitServlet> hub() {
      return new ServletRegistrationBean<>(
          HubServlet.build(
              hubs,
              gate,
              project -> List.of(),
              1_000,
              10_000_000,
              (project, account, role) -> {
                if (role == io.aeyer.plowshare.server.archive.ProjectRole.CONTRIBUTOR
                    && revokeDuringReceive.contains(project)
                    && writes.merge(project, 1, Integer::sum) == 3)
                  grants.put(project, io.aeyer.plowshare.server.archive.ProjectRole.VIEWER);
                var grant =
                    grants.getOrDefault(
                        project, io.aeyer.plowshare.server.archive.ProjectRole.MANAGER);
                if (removed.contains(project) || !grant.allows(role))
                  throw new io.aeyer.plowshare.server.faults.CallerFault("Project access refused");
              }),
          HubServlet.MAPPING);
    }
  }

  @Test
  void the_project_is_read_from_the_path() {
    assertEquals("ledger", HubServlet.projectOf("/ledger.git/info/refs"));
    assertEquals("my-proj", HubServlet.projectOf("/my-proj.git/git-receive-pack"));
    assertEquals("site.github.io", HubServlet.projectOf("/site.github.io.git/git-receive-pack"));
  }

  @Test
  void a_push_while_offline_is_refused() throws Exception {
    String project = hubFor("offline");
    Path repo = commitIn("a.txt", "one");
    Result pushed = git(repo, "push", url(project), "HEAD:refs/heads/main");
    assertNotEquals(0, pushed.exit());
    assertTrue(pushed.output().contains("not syncing"), pushed.output());
  }

  @Test
  void a_push_while_syncing_lands_in_the_tree() throws Exception {
    String project = hubFor("syncing");
    gate.begin(project, "s1");
    Path repo = commitIn("src/a.txt", "one");
    Result pushed = git(repo, "push", url(project), "HEAD:refs/heads/main");
    assertEquals(0, pushed.exit(), pushed.output());
    assertEquals(
        "one", Files.readString(hubs.of(project).orElseThrow().tree().resolve("src/a.txt")));
  }

  @Test
  void a_hidden_file_is_refused_by_name() throws Exception {
    String project = hubFor("hidden");
    gate.begin(project, "s1");
    Path repo = commitIn(".env", "SECRET=1");
    Result pushed = git(repo, "push", url(project), "HEAD:refs/heads/main");
    assertNotEquals(0, pushed.exit());
    assertTrue(pushed.output().contains(".env"), pushed.output());
  }

  @Test
  void a_non_fast_forward_is_refused_and_other_refs_are_refused() throws Exception {
    String project = hubFor("nff");
    gate.begin(project, "s1");
    assertEquals(
        0, git(commitIn("a.txt", "one"), "push", url(project), "HEAD:refs/heads/main").exit());
    Path other = commitIn("a.txt", "two");
    assertNotEquals(0, git(other, "push", url(project), "HEAD:refs/heads/main").exit());
    assertNotEquals(0, git(other, "push", url(project), "HEAD:refs/heads/feature").exit());
  }

  @Test
  void a_push_to_a_dotted_project_name_is_gated_by_that_project() throws Exception {
    hubFor("site");
    String project = hubFor("site.github.io");
    gate.begin("site", "s1");
    Path repo = commitIn("index.html", "one");
    Result blocked = git(repo, "push", url(project), "HEAD:refs/heads/main");
    assertNotEquals(0, blocked.exit(), blocked.output());
    assertTrue(blocked.output().contains("not syncing"), blocked.output());

    gate.begin(project, "s2");
    Result pushed = git(repo, "push", url(project), "HEAD:refs/heads/main");
    assertEquals(0, pushed.exit(), pushed.output());
    assertEquals(
        "one", Files.readString(hubs.of(project).orElseThrow().tree().resolve("index.html")));
  }

  // HubServlet.build's ObjectChecker is deliberately plain now (no setSafeForWindows/
  // setSafeForMacOS — see the coordinator's revised ruling: those flags also reject ordinary
  // macOS/Linux names). So a Unicode-confusable ".git" variant is refused by SyncRules'
  // path-string screen (reservedSegment's NFC fold + ignorable-code-point strip), not by JGit's
  // checker — this test proves the path rule, not a checker flag. `git add`/checkout is enough
  // here since ".gi<ZWNJ>t" doesn't collide (case-insensitively or otherwise) with this repo's
  // own ".git"; plumbing is only needed for the ".GIT"/case-collision style variants.
  @Test
  void a_push_holding_a_unicode_confusable_git_directory_is_refused() throws Exception {
    String project = hubFor("dotgit");
    gate.begin(project, "s1");
    Path repo = commitFiles(Map.of(".gi‌t/config", "hostile\n"));
    Result pushed = git(repo, "push", url(project), "HEAD:refs/heads/main");
    assertNotEquals(0, pushed.exit(), pushed.output());
  }

  // The revised ruling: names that are perfectly legal on macOS and Linux — a reserved-on-Windows
  // device stem, a bare ':', a trailing '.', a trailing space, a '?', and two names in one tree
  // that differ only by case — must NOT stop a push, now that setSafeForWindows/setSafeForMacOS
  // are off. Built entirely with plumbing (hash-object/mktree/commit-tree): README and readme
  // can't coexist as actual directory entries on this machine's case-insensitive filesystem, and
  // plumbing sidesteps that (and every other name-legality quirk of the local working tree)
  // regardless of platform, so no Assumptions skip is needed.
  @Test
  void a_push_holding_names_legal_on_macos_and_linux_is_accepted() throws Exception {
    String project = hubFor("legalnames");
    gate.begin(project, "s1");
    Path repo = Files.createTempDirectory(client, "r");
    Assumptions.assumeTrue(
        git(repo, "init", "-q", "-b", "main").exit() == 0, "git is not installed");
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("aux.js", "aux\n");
    entries.put("con.txt", "con\n");
    entries.put("a:b.txt", "colon\n");
    entries.put("q?.md", "question\n");
    entries.put("notes.", "trailing dot\n");
    entries.put("trail ", "trailing space\n");
    entries.put("README", "upper\n");
    entries.put("readme", "lower\n");
    StringBuilder treeInput = new StringBuilder();
    for (Map.Entry<String, String> entry : entries.entrySet()) {
      String blob = gitIn(repo, entry.getValue(), "hash-object", "-w", "--stdin").output().trim();
      treeInput
          .append("100644 blob ")
          .append(blob)
          .append('\t')
          .append(entry.getKey())
          .append('\n');
    }
    String rootTree = gitIn(repo, treeInput.toString(), "mktree").output().trim();
    Result commitResult =
        git(
            repo,
            "-c",
            "user.name=t",
            "-c",
            "user.email=t@t",
            "commit-tree",
            rootTree,
            "-m",
            "legal names");
    assertEquals(0, commitResult.exit(), commitResult.output());
    String commit = commitResult.output().trim();
    Result pushed = git(repo, "push", url(project), commit + ":refs/heads/main");
    assertEquals(0, pushed.exit(), pushed.output());
  }

  @Test
  void a_push_to_a_project_name_needing_percent_encoding_lands_in_that_project() throws Exception {
    String project = hubFor("my proj");
    gate.begin(project, "s1");
    Path repo = commitIn("a.txt", "spaced");
    Result pushed =
        git(
            repo,
            "push",
            "http://127.0.0.1:" + port + UnionFrames.url(project),
            "HEAD:refs/heads/main");
    assertEquals(0, pushed.exit(), pushed.output());
    assertEquals(
        "spaced", Files.readString(hubs.of(project).orElseThrow().tree().resolve("a.txt")));
  }

  @Test
  void a_client_can_fetch_main() throws Exception {
    String project = hubFor("fetch");
    gate.begin(project, "s1");
    assertEquals(
        0, git(commitIn("a.txt", "one"), "push", url(project), "HEAD:refs/heads/main").exit());
    Result listed = git(client, "ls-remote", url(project));
    assertTrue(listed.output().contains("refs/heads/main"), listed.output());
  }

  private String hubFor(String project) {
    hubs.of(project).orElseThrow().create();
    return project;
  }

  private String url(String project) {
    return "http://127.0.0.1:" + port + "/v1/sync/" + project + ".git";
  }

  private Path commitIn(String path, String content) throws Exception {
    return commitFiles(Map.of(path, content));
  }

  private Path commitFiles(Map<String, String> files) throws Exception {
    Path repo = Files.createTempDirectory(client, "r");
    Assumptions.assumeTrue(
        git(repo, "init", "-q", "-b", "main").exit() == 0, "git is not installed");
    for (Map.Entry<String, String> entry : files.entrySet()) {
      Path file = repo.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue());
    }
    git(repo, "add", "-A");
    git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c");
    return repo;
  }

  record Result(int exit, String output) {}

  private static Result git(Path dir, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    Process process =
        new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new Result(process.waitFor(), output);
  }

  /** Like {@link #git}, but feeds {@code input} on stdin — for plumbing like hash-object/mktree. */
  private static Result gitIn(Path dir, String input, String... args)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    Process process =
        new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
    process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new Result(process.waitFor(), output);
  }

  @Test
  void a_viewer_can_fetch_but_cannot_push_and_removal_blocks_fetch() throws Exception {
    String project = hubFor("rbac-viewer");
    gate.begin(project, "s1");
    Path repo = commitIn("a.txt", "initial");
    assertEquals(0, git(repo, "push", url(project), "HEAD:refs/heads/main").exit());
    grants.put(project, io.aeyer.plowshare.server.archive.ProjectRole.VIEWER);
    assertEquals(0, git(repo, "ls-remote", url(project)).exit());
    Result denied = git(repo, "push", url(project), "HEAD:refs/heads/main");
    assertNotEquals(0, denied.exit());
    removed.add(project);
    assertNotEquals(0, git(repo, "ls-remote", url(project)).exit());
  }

  @Test
  void a_downgrade_during_receive_refuses_the_pack_before_refs_or_tree_change() throws Exception {
    String project = hubFor("rbac-receive");
    gate.begin(project, "s1");
    Path repo = commitIn("a.txt", "must not land");
    revokeDuringReceive.add(project);
    Result denied = git(repo, "push", url(project), "HEAD:refs/heads/main");
    assertNotEquals(0, denied.exit(), denied.output());
    assertTrue(denied.output().contains("Project write access refused"), denied.output());
    assertTrue(hubs.of(project).orElseThrow().main().isEmpty());
    assertTrue(Files.notExists(hubs.of(project).orElseThrow().tree().resolve("a.txt")));
  }
}
