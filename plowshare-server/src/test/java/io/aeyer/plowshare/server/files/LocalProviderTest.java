package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.GlobSpellings;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.archive.ProjectStore;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The seam, driven against a real {@link ProjectStore} and a real disk.
 *
 * <h2>Why this test has a database in it</h2>
 *
 * <p>A fake store would be a helper blind in the dimension half of this file is about. Three of the
 * rules here are rules about {@code ProjectStore} specifically — that the exclusions come from
 * {@code effectiveExclusions} and never from the row, that a name with edge whitespace is refused
 * rather than answered, that a workspace moved in the table moves the leash — and each of them is a
 * rule a stub would satisfy by construction whatever the provider did.
 *
 * <h2>The fixture is deliberately not named like the real thing</h2>
 *
 * <p>The configuration file is {@code plowshare.yml} and the sampling directory is {@code
 * profiles}, neither of which is what the server ships ({@code application.yml}, and {@code
 * sampling}). {@code ProjectStoreTest} says why: a provider that hardcoded either real name would
 * pass a suite whose fixture used the real names.
 *
 * <p>{@link #linked} is a second spelling of {@link #real}, for the reason {@code FileAccessTest}
 * keeps one — on a host whose temp directory is not itself reached through a symlink, it is the
 * only thing that catches a comparison canonicalised on one side.
 *
 * <h2>What is measured here rather than assumed — an index, not a second copy</h2>
 *
 * <p>Each row names the fact, the test that holds it, and <b>the one place the argument lives</b>.
 * Nothing here restates a measurement: this list was three copies of the same sentences a moment
 * ago, with no place designated as the one to edit, which is how a fact goes stale in two of its
 * three homes.
 *
 * <ul>
 *   <li>Java's {@code **} is not Python's &mdash; {@code
 *       a_double_star_pattern_also_matches_a_file_at_the_top_of_the_root}, argued in {@code
 *       LocalProvider.matchers};
 *   <li>{@code **} that is not a whole component must not be elided &mdash; {@code
 *       a_star_star_that_is_not_a_whole_component_is_left_to_mean_what_it_says}, argued in {@code
 *       GlobSpellings.recursiveAt};
 *   <li>a malformed pattern is unchecked &mdash; {@code
 *       a_pattern_that_will_not_compile_is_a_refusal_and_not_a_crash}, argued at the {@code catch}
 *       in {@code LocalProvider.matchers};
 *   <li>{@code Files.walk} lists symlinks without following them, and reports an unreadable
 *       directory lazily &mdash; {@code
 *       a_directory_symlink_inside_the_root_is_listed_but_not_walked_through} and {@code
 *       a_directory_that_cannot_be_listed_is_named_rather_than_silently_skipped}, argued in {@code
 *       LocalProvider.walk};
 *   <li>a strict decoder reports where {@code new String} substitutes &mdash; {@code
 *       bytes_that_are_not_utf8_are_named_rather_than_returned_as_replacements}, argued at the
 *       decode in {@code LocalProvider.read};
 *   <li>{@code newInputStream} opens a directory, and {@code AccessDeniedException} arrives at the
 *       open rather than at the attribute read &mdash; {@code a_directory_is_not_a_file} and {@code
 *       a_file_the_server_may_not_open_is_a_refusal_and_not_an_outage}, argued at the same two
 *       places in {@code LocalProvider.read};
 *   <li>nothing in the platform bounds a read &mdash; {@code
 *       a_file_of_four_megabytes_is_ordinary_and_is_read} and {@code
 *       a_file_too_large_to_hold_in_memory_is_refused_with_its_size}, argued on {@code
 *       LocalProvider.MAX_FILE_BYTES};
 *   <li>{@code String.lines} keeps no terminator, ends a line on {@code \r\n} as readily as on
 *       {@code \n}, and adds no empty last line for a file that ends in one &mdash; {@code
 *       a_last_line_with_no_newline_is_a_line_and_the_only_one_of_its_kind}, {@code
 *       a_crlf_file_comes_back_as_lines_without_the_carriage_returns} and {@code
 *       an_empty_file_is_an_empty_window_and_not_a_refusal}, argued in {@code LocalProvider.lines}.
 * </ul>
 *
 * <h2>Two tests here depend on the host, and now say so out loud</h2>
 *
 * <p>{@code a_directory_that_cannot_be_listed_is_named_rather_than_silently_skipped} and {@code
 * a_file_the_server_may_not_open_is_a_refusal_and_not_an_outage} are the only instruments for their
 * two rules, and both need the running user to be subject to POSIX permissions. Each checks that
 * premise first, through {@link #assertTheSealHolds}, and <b>fails</b> when it does not hold —
 * which as root it will not, the mode bits having no effect on that user.
 *
 * <p>Skipping was the previous answer and was the wrong one. It is the honest report only to a
 * reader who checks the {@code skipped} count, and nobody checks a count that is almost always
 * zero; on any root CI image these two rules went unchecked behind a green run. So this suite's "0
 * skipped" stops being a fact about whichever host happened to run it and becomes something the
 * build asserts.
 */
@Testcontainers
class LocalProviderTest {

  /**
   * The pgvector image, as {@code ProjectStoreTest} uses: V1's first line is CREATE EXTENSION
   * vector and this class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Grant READ = new Grant(Scope.WORKSPACE, Mode.READ);
  private static final Grant WRITE = new Grant(Scope.WORKSPACE, Mode.WRITE);

  /**
   * The window every test that is not about windows asks for: the first one of the file, which is
   * what {@code FileRequest.window()} makes of a frame that named none. Every fixture in this file
   * below the windowing section is a handful of lines, so this returns all of them and those tests
   * go on asking what they asked before — which file may be read, and what its bytes decode to.
   */
  private static final Window FIRST = Window.of(0, Window.MAX_WINDOW_LINES);

  /**
   * How long a line is in the one fixture built out of megabytes, counting the newline that ends
   * it. Named because that test divides by it to say how many lines four megabytes is, and a
   * literal in both places is two numbers that can disagree.
   */
  private static final int LINE = 64;

  private static JdbcTemplate jdbc;

  @TempDir Path tmp;

  /** The tree everything really lives in. */
  private Path real;

  /** A second, equally valid spelling of {@link #real}. */
  private Path linked;

  /** The directory a project points at. */
  private Path repo;

  /** A directory beside the workspace that no project names. */
  private Path outside;

  private Path configFile;
  private Path samplingDir;

  /**
   * A project's own {@code bots/} tier, inside {@link #dataDir} — where agent and bot definitions
   * actually live since task 10 retired the standalone directory key that used to fence them
   * directly. Not a mandatory exclusion of its own any more; covered because it is a descendant of
   * {@link #dataDir}, which is.
   */
  private Path agentsDir;

  /**
   * Where this fixture's deployment writes its operator token — see {@link #freshProjects} for why
   * it is not hidden.
   */
  private Path tokenFile;

  /**
   * Where this fixture's deployment writes ejected conversation payloads — see {@link
   * #freshProjects} for why it is not under the server's own directory.
   */
  private Path exportDir;

  /**
   * The root of the tree this fixture's deployment owns — disjoint from {@link #exportDir}, so that
   * neither entry can stand in for the other.
   */
  private Path dataDir;

  private ProjectStore store;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void freshProjects() throws IOException {
    // CASCADE since V14: `memories` and `conversations` reference this
    // table now, so Postgres refuses a plain TRUNCATE of it whether or not
    // they hold anything. Nothing here holds a memory or a conversation.
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, projects CASCADE");
    real = Files.createDirectory(tmp.resolve("real"));
    linked = Files.createSymbolicLink(tmp.resolve("linked"), real);
    repo = Files.createDirectory(real.resolve("repo"));
    outside = Files.createDirectory(real.resolve("outside"));
    Path server = Files.createDirectory(real.resolve("srv"));
    configFile = Files.writeString(server.resolve("plowshare.yml"), "a fixture, not a key");
    samplingDir = Files.createDirectory(server.resolve("profiles"));
    // Deliberately NOT under a dot-prefixed directory. The shipped path is
    // ~/.config/plowshare/console-token, which `FileAccess.permits` already
    // refuses on its own; this entry in the list is for the deployment that
    // moved the property somewhere ordinary, so the fixture is that
    // deployment.
    tokenFile = Files.writeString(server.resolve("console-token"), "a fixture, not a token");
    // AND DELIBERATELY NOT UNDER `server`. The shipped default is `exports`,
    // relative, which lands inside the working directory and is therefore
    // covered by the working-directory exclusion whatever this list says --
    // so a fixture that put it beside the configuration would pass with the
    // entry deleted. This is the deployment that set PLOWSHARE_EXPORT_DIR to
    // a path of its own, which is the arrangement the entry exists for.
    exportDir = Files.createDirectory(real.resolve("ejected"));
    // A ROOT OF ITS OWN, not the parent of `ejected`. On a real deployment
    // the exports are inside this tree and the collapse drops their entry --
    // so a fixture in that arrangement would pass with either entry deleted,
    // the survivor covering the same paths. Here each has to be there on its
    // own account.
    dataDir = Files.createDirectory(real.resolve("owned"));
    // Nested under dataDir rather than a sibling of it, matching
    // DataLayout's real shape (global/bots/) -- the fence this file's own
    // tests measure is the data root's now, not a standalone entry.
    agentsDir = Files.createDirectories(dataDir.resolve("global").resolve("bots"));
    store = new ProjectStore(jdbc, configFile, samplingDir, tokenFile, exportDir, dataDir);
  }

  /**
   * The grants are always passed explicitly, never defaulted: the mode is what three tests in this
   * file are about, and a helper that chose it would be blind in exactly that dimension.
   */
  private LocalProvider provider(String project, Grant... grants) {
    return new LocalProvider(store, Home.of(project), List.of(grants));
  }

  /**
   * The premise the two permission tests stand on: a {@code chmod 000} this process is actually
   * subject to.
   *
   * <p><b>A failure here is a fact about the host and not about {@code LocalProvider}.</b> The two
   * callers are the only instruments for their {@code AccessDeniedException} clauses, and both need
   * the running user to be denied by the mode bits they just set. Run as root — the default in most
   * CI images — the bits do not apply, the refusal can never be raised, and each caller's {@code
   * assertThrows} has nothing to see.
   *
   * <p>This was an {@code Assumptions.assumeFalse} until slice 3c. On such a host both tests
   * skipped, the run stayed green, and the total said nothing about two rules that had gone
   * unchecked; only a reader who noticed {@code skipped} was 2 rather than 0 would have known.
   * Failing is the honest report — a guard that passes because it could not run is worse than no
   * guard — and it is why {@code skipped} across this suite is expected to stay 0.
   *
   * @param sealed the file or directory just set to mode {@code 000}
   * @param deniedOperation what the provider is about to be asked to do to it, as a gerund phrase
   *     that reads after "the AccessDeniedException that"
   */
  private static void assertTheSealHolds(Path sealed, String deniedOperation) {
    assertFalse(
        Files.isReadable(sealed),
        sealed.getFileName()
            + " was set to mode 000 and this process can still read it,"
            + " so the AccessDeniedException that "
            + deniedOperation
            + " has to raise"
            + " cannot occur and this test cannot do its job on this host. The likely"
            + " cause is that the suite is running as root, which POSIX mode bits do"
            + " not constrain: check with `id -u`, which prints 0. An ACL granting"
            + " what the mode bits deny, or a filesystem mounted without permission"
            + " support, has the same effect. Run the suite as an unprivileged user —"
            + " in a container image, add a non-root USER — and fix the host rather"
            + " than this assertion: a guard that passes because it could not run is"
            + " worse than no guard.");
  }

  // --- what the provider is ------------------------------------------------

  @Test
  void the_provider_names_itself_so_an_ambiguity_can_be_reported() {
    assertEquals(
        "local",
        provider("payments", READ).name(),
        "two providers covering one path are refused with both named, and a"
            + " provider with no name cannot appear in that sentence");
  }

  @Test
  void model_file_tools_preserve_project_policy_but_can_edit_the_cli_executable()
      throws IOException {
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", READ, WRITE);
    Path manifest = repo.resolve("plowshare");
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.write(manifest, "{\"version\":1,\"name\":\"payments\"}"));
    Files.writeString(
        manifest, "{\"version\":1,\"name\":\"payments\",\"caps\":{\"autoIncrease\":false}}");
    assertThrows(WorkspaceRefusedException.class, () -> provider.delete(manifest));
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.write(manifest, "{\"caps\":{\"autoIncrease\":true}}"));
    Files.writeString(manifest, "#!/bin/sh\necho test\n");
    provider.write(manifest, "#!/bin/sh\necho changed\n");
    assertTrue(Files.readString(manifest).contains("changed"));
  }

  // --- roots ---------------------------------------------------------------

  @Test
  void a_projects_workspace_is_the_root_its_jobs_reach() throws IOException {
    store.define("payments", linked.resolve("repo"), List.of());

    assertEquals(
        List.of(repo.toRealPath()),
        provider("payments", READ).roots(),
        "the root is advertised as the directory it is, not as the spelling"
            + " the projects row happens to hold");
  }

  @Test
  void the_leash_is_read_again_on_every_call_so_moving_a_workspace_moves_it() throws IOException {
    Path moved = Files.createDirectory(real.resolve("moved"));
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", READ);
    assertEquals(List.of(repo.toRealPath()), provider.roots());

    store.define("payments", moved, List.of());

    assertEquals(
        List.of(moved.toRealPath()),
        provider.roots(),
        "roots() may change between calls: a workspace moved in the table is"
            + " the leash moving, and a provider that snapshotted it at"
            + " construction would keep the old one for the rest of the job");
  }

  @Test
  void one_call_reads_the_projects_table_once_so_a_forget_has_no_window_to_land_in()
      throws IOException {
    // The workspace and the exclusions are two questions, and asking them as
    // two reads leaves a gap: a `forget` between them makes the second raise
    // ArchiveException — a THIRD exception type out of a seam whose contract
    // is that there are two, arriving on the one path the per-call re-read
    // exists to serve. ProjectStore.effectiveExclusions(ProjectRecord) is
    // what closes it.
    //
    // The query count is the instrument because it is the rule. A race has
    // no deterministic test, so the thing to hold is not "the race does not
    // happen" but "there is no second read for it to happen between".
    // Counting query(String, RowMapper, Object...) specifically — the
    // overload `find` uses. Not all database traffic: `all()` takes another
    // and the writes go through `update`. It is the path this test is about.
    AtomicInteger reads = new AtomicInteger();
    JdbcTemplate counting =
        new JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            reads.incrementAndGet();
            return super.query(sql, mapper, args);
          }
        };
    ProjectStore counted =
        new ProjectStore(counting, configFile, samplingDir, tokenFile, exportDir, dataDir);
    counted.define("payments", repo, List.of(repo.resolve("keys")));
    reads.set(0);

    assertEquals(
        List.of(repo.toRealPath()),
        new LocalProvider(counted, Home.of("payments"), List.of(READ)).roots(),
        "and it is still the right answer, exclusions and all");
    assertEquals(1, reads.get(), "one read, so there is no window between two of them");
  }

  @Test
  void forgetting_a_project_revokes_the_leash_of_a_job_already_running() {
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", READ);
    assertFalse(provider.roots().isEmpty());

    store.forget("payments");

    assertEquals(List.of(), provider.roots());
    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class, () -> provider.read(repo.resolve("notes.md"), FIRST));
    assertTrue(
        refused.getMessage().contains("no workspace is defined"),
        "the state is named: a revoked workspace is the operator's to restore,"
            + " and 'outside' would send the model hunting for a path");
    assertFalse(
        refused.getMessage().contains("outside"),
        "and it is not described as an out-of-scope path, which is the other"
            + " refusal this one shares a type with");
  }

  /**
   * And <b>moving</b> a project's workspace moves the leash of a job already running, which is the
   * half the revocation test above does not cover.
   *
   * <p>Two different answers rather than an answer and an absence: after the move the old tree is
   * outside every root and the new one is inside, so a provider that had cached anything would fail
   * one of the two assertions whichever way it cached. That is what makes this the pair to {@code
   * forgetting_a_project_revokes_the_leash_of_a_job_already_running} rather than a second spelling
   * of it.
   *
   * <p>It is also the behaviour {@code project_workspace_set}'s answer promises a person in words —
   * <em>"runs already going pick this up at their next file request"</em>. That sentence said the
   * opposite until a review caught it, and it said it with nothing behind it; this is the
   * something.
   */
  @Test
  void moving_a_project_s_workspace_moves_the_leash_of_a_job_already_running() throws IOException {
    Path moved = Files.createDirectory(tmp.resolve("moved"));
    Files.writeString(moved.resolve("notes.md"), "on the new disk\n");
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", READ);
    assertEquals(List.of(repo.toRealPath()), provider.roots());

    store.moveWorkspace("payments", moved);

    assertEquals(List.of(moved.toRealPath()), provider.roots());
    assertEquals(
        List.of("on the new disk"), provider.read(moved.resolve("notes.md"), FIRST).lines());
    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class, () -> provider.read(repo.resolve("notes.md"), FIRST));
    assertTrue(
        refused.getMessage().contains("outside"),
        "the old tree is now an out-of-scope path and is named as one: " + refused.getMessage());
  }

  /**
   * <b>The attacker's side of the configuration exclusion: a workspace above this server's own
   * working directory reaches none of the four places Spring Boot loads configuration from.</b>
   *
   * <p>Driven against the <em>real</em> working directory rather than a fixture one, because that
   * is what the rule is about and a JVM cannot change it. The workspace is this process's parent
   * directory, which is the premise the escalation needs and which {@code ProjectStore.define}
   * accepts — it accepts {@code /}.
   *
   * <p>Four locations and both verbs, because they fail differently. Measured before the fix, with
   * the old rule that named one filename: {@code application.yml} was refused and {@code
   * config/application.yml}, {@code application.properties} and {@code application-prod.yml} were
   * all <em>permitted</em> — and the first of those outranks the excluded one in Spring's own
   * precedence order. The write half is the sharper one: {@link LocalProvider#write} creates
   * missing parents, so a permitted write here would let an agent author a configuration file that
   * redirects {@code plowshare.llm.sampling-directory} at the next boot.
   *
   * <p><b>The control matters as much as the refusals.</b> A rule that refused everything would
   * pass every assertion above it, so this also reads a real file that is inside the workspace and
   * outside the server's directory. If that ever fails, the exclusion has stopped being targeted
   * and has become a workspace that grants nothing.
   */
  @Test
  void a_workspace_above_this_server_reaches_none_of_spring_s_config_locations()
      throws IOException {
    Path serverDirectory = Path.of("").toAbsolutePath().normalize();
    Path above = serverDirectory.getParent();
    store.define("payments", above, List.of());
    LocalProvider provider = provider("payments", WRITE);

    List<Path> candidates =
        List.of(
                "application.yml",
                "config/application.yml",
                "application.properties",
                "application-prod.yml",
                "config/application-prod.properties")
            .stream()
            .map(serverDirectory::resolve)
            .toList();
    // WHAT WAS ALREADY HERE, RECORDED BEFORE ANYTHING IS ASSERTED.
    //
    // `config/application.yml` is Spring's own documented configuration
    // location, and an operator keeping this deployment's addresses and keys
    // there is doing the ordinary thing -- `.gitignore` carries `config/`
    // for exactly that. This test used to assert every candidate did not
    // EXIST and then delete each one unconditionally, which was sound only
    // while nobody used the convention. The first time somebody did, this
    // test failed on their file and then DELETED IT, along with the
    // directory holding it. Measured on 2026-09-07, on a real file.
    //
    // So: absence is asserted only for what was absent, the bytes of
    // anything present are compared instead, and the cleanup below removes
    // only what this test created.
    Map<Path, byte[]> existing = new LinkedHashMap<>();
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate)) {
        existing.put(candidate, Files.readAllBytes(candidate));
      }
    }
    try {
      for (Path candidate : candidates) {
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider.read(candidate, FIRST),
            "read of " + candidate + " must be refused");
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider.write(candidate, "plowshare:\n  agents:\n    dir: /tmp\n"),
            "write of " + candidate + " must be refused");
        byte[] before = existing.get(candidate);
        if (before == null) {
          assertFalse(
              Files.exists(candidate), "a refused write must not have created " + candidate);
        } else {
          assertArrayEquals(
              before,
              Files.readAllBytes(candidate),
              "a refused write must not have changed " + candidate);
        }
      }
    } finally {
      // This test writes at the REAL working directory, which is inside
      // the repository. Every write above is refused, so nothing is
      // created -- but a mutation run is exactly the case where one is
      // not, and the mutant that drops the working directory from the
      // exclusions really does author a config file in the checkout. It
      // did, once, before this clause existed. Cleaning up after the
      // assertions rather than before keeps the failure visible and keeps
      // the tree clean for whatever runs next.
      for (Path candidate : candidates) {
        // Only what this test could have created. A candidate recorded
        // above was somebody else's before this ran and stays theirs.
        if (existing.containsKey(candidate)) {
          continue;
        }
        Files.deleteIfExists(candidate);
        Path parent = candidate.getParent();
        if (!parent.equals(serverDirectory)
            && existing.keySet().stream().noneMatch(kept -> kept.getParent().equals(parent))) {
          // And only an empty directory: deleteIfExists on a
          // non-empty one throws rather than recursing, which is the
          // behaviour wanted here -- a directory holding somebody's
          // file is not this test's to remove.
          try {
            Files.deleteIfExists(parent);
          } catch (java.nio.file.DirectoryNotEmptyException held) {
            // Somebody's, not ours.
          }
        }
      }
    }

    // The control: a real read, inside the workspace and outside the server's
    // own directory. `settings.gradle.kts` is at the repository root and
    // this process runs in a module below it -- Gradle starts a test with
    // the module directory as its working directory, which is the same fact
    // `Path.of("src/main/resources/agents")` relies on in AgentsConfigTest.
    assertEquals(List.of(above.toRealPath()), provider.roots());
    assertFalse(
        provider.read(above.resolve("settings.gradle.kts"), FIRST).lines().isEmpty(),
        "the workspace still reaches what is not the server's own directory");
  }

  // --- a project lent more than one directory -------------------------------

  /**
   * <b>The acceptance test for the whole feature.</b> A second lent root makes {@code
   * .github/workflows/ci.yml} readable, and the same file is refused without it.
   *
   * <p><b>Both halves, in one test, on one fixture.</b> The refusal alone would pass on a provider
   * that reaches nothing at all; the read alone would pass on a provider that reaches everything,
   * hidden components included, which is exactly what {@code 809338d} took away. Only the pair says
   * that the lent root is what moved.
   *
   * <p><b>A dot-prefixed directory and not an ordinary one, because that is the case that motivated
   * this.</b> {@code 809338d} made a hidden path component unreachable unless a root names it and
   * called the cost "recoverable by adding them as explicit roots" — a recovery that, for a project
   * on the server's disk, did not exist: {@code ProjectStore.define} took one workspace and {@code
   * LocalProvider} built {@code FileAccess} from a singleton list. An ordinary subdirectory would
   * still be readable through the workspace, so it would measure the column and not the leash.
   *
   * <p><b>The lent root sits INSIDE the workspace here</b>, which is the weaker fixture of the two
   * available and is the right one for this assertion: it means the difference between the two
   * halves cannot be "the file was somewhere else entirely". {@code
   * a_lent_root_outside_the_workspace_is_reached} carries the other half.
   */
  @Test
  void a_second_lent_root_makes_a_hidden_directory_readable() throws IOException {
    Path workflows = Files.createDirectories(repo.resolve(".github/workflows"));
    Path ci = Files.writeString(workflows.resolve("ci.yml"), "on: [push]\n");

    store.define("payments", repo, List.of());
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider("payments", READ).read(ci, FIRST),
        "without the lent root the hidden component makes this unreachable, which is"
            + " the state 809338d created and this feature exists to recover from");

    store.defineLending("payments", repo, List.of(repo.resolve(".github")), List.of());
    LocalProvider lending = provider("payments", READ);

    assertEquals(
        List.of("on: [push]"),
        lending.read(ci, FIRST).lines(),
        "the lent root names the hidden component, so it is consent by construction"
            + " and the file below it is readable");
  }

  /**
   * A lent root may sit outside the workspace, which is the feature and not an edge of it.
   *
   * <p><b>Inside-only would have made this the per-project unhide list that was considered and
   * declined</b>, in different words: a rule that a lent root must be a descendant of the workspace
   * turns lending into "un-hide a subdirectory I already reach". A directory somewhere else is what
   * a person means by lending a second one, and it is what {@code plowshare --workspace a,b} has
   * always meant on the client.
   *
   * <p>{@link #outside} is the fixture's directory that no project names, so this also measures
   * that lending is what changed the answer: the same path is refused for a project that has only
   * the workspace, three lines up.
   */
  @Test
  void a_lent_root_outside_the_workspace_is_reached() throws IOException {
    Path notes = Files.writeString(outside.resolve("notes.md"), "not in the repo\n");

    store.define("payments", repo, List.of());
    assertThrows(
        WorkspaceRefusedException.class, () -> provider("payments", READ).read(notes, FIRST));

    store.defineLending("payments", repo, List.of(outside), List.of());

    assertEquals(
        List.of("not in the repo"),
        provider("payments", READ).read(notes, FIRST).lines(),
        "a lent root is not required to be inside the workspace: containment here"
            + " would be the unhide list this design was chosen over");
  }

  /**
   * A project forgotten and defined again does not reach what the old definition lent it.
   *
   * <p><b>Asserted at the enforcement point, which is the half a column cannot give.</b> {@code
   * ProjectStoreTest.forgetting_a_workspace_drops_the_lent_roots_with_it} reads {@code lent} out of
   * the row, because after a {@code forget} the store has no answer to give about a project it has
   * no place for. A fence checked by inspecting a list still passes when the thing consulting the
   * list stops consulting it, so the question here is the operator's: after the name is given back
   * its directories, can a job open the old file?
   *
   * <p><b>Re-defined <em>lending something else</em>, and that is what makes this an
   * instrument.</b> A project with no workspace reaches nothing whatever the row holds — {@code
   * forgetting_a_project_revokes_the_leash_of_a_job_already_running} covers that state — so the
   * interesting arrangement is the one after the name is defined again. The second definition lends
   * a different directory, so a definition that <em>added</em> to the lent roots rather than
   * replacing them is caught here and would not be by a re-definition that lent nothing: the old
   * root would come back reachable beside the new one, which is precisely the state an operator
   * handing the name on cannot see.
   */
  @Test
  void a_forgotten_project_does_not_reach_what_it_used_to_be_lent() throws IOException {
    Path second = Files.createDirectory(real.resolve("second"));
    Path notes = Files.writeString(outside.resolve("notes.md"), "not in the repo\n");
    Path fresh = Files.writeString(second.resolve("fresh.md"), "the new definition's\n");
    store.defineLending("payments", repo, List.of(outside), List.of());
    assertEquals(
        List.of("not in the repo"),
        provider("payments", READ).read(notes, FIRST).lines(),
        "the premise: there is a reach to lose");

    store.forget("payments");
    store.defineLending("payments", repo, List.of(second), List.of());
    LocalProvider redefined = provider("payments", READ);

    assertEquals(
        List.of(repo.toRealPath(), second.toRealPath()),
        redefined.roots(),
        "the leash the new definition gets is its own, and the old lent root is not" + " in it");
    assertEquals(
        List.of("the new definition's"),
        redefined.read(fresh, FIRST).lines(),
        "the new lent root does reach, so a provider that reached nothing at all"
            + " cannot pass the refusal below by accident");
    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> redefined.read(notes, FIRST),
            "and the refusal is the whole point: a lent root that outlived the forget"
                + " would hand this directory to whoever defined the name next,"
                + " neither of them ever having been told it was there");
    assertTrue(
        refused.getMessage().contains("outside every root"),
        "named as an out-of-scope path rather than as a missing workspace, because"
            + " the project has one: "
            + refused.getMessage());
  }

  /**
   * {@code file_roots} renders every lent root, and renders the workspace first.
   *
   * <p><b>The order is asserted and not merely the membership</b>, because it is the one thing
   * about {@code ProjectRecord.roots()} a caller can observe. {@code FileAccess.permits} resolves
   * by deepest covering root, so order cannot change what is permitted and a set-comparison here
   * would pass on a {@code roots()} that sorted, reversed or shuffled. What the order buys is that
   * a model reading this list reads the project's own place first — and that {@code
   * Plowshare.rooting}'s {@code get(0)} and this list agree about which directory "the place"
   * means.
   */
  @Test
  void file_roots_renders_every_lent_root_with_the_workspace_first() throws IOException {
    Path second = Files.createDirectory(real.resolve("second"));
    Path third = Files.createDirectory(real.resolve("third"));
    store.defineLending("payments", repo, List.of(third, second), List.of());

    assertEquals(
        List.of(repo.toRealPath(), third.toRealPath(), second.toRealPath()),
        provider("payments", READ).roots(),
        "the workspace leads, and the lent roots keep the order they were given"
            + " rather than an order this provider invented");
  }

  /**
   * A grep with no path searches every lent root, under one budget.
   *
   * <p><b>The budget is the assertion, so the fixture has to reach it.</b> {@code
   * LocalProvider.grep} <em>is</em> a loop over the roots — a per-root loop is not the mutation, it
   * is the implementation — and what makes the budget one budget is the single {@code into} list
   * every {@code sweep} appends to, which {@link Needle#find} caps at {@link Needle#MAX_MATCHES}.
   * So the mutant this test exists to kill is a loop that gives each root its own list and
   * concatenates the results, and the only fixture that can tell the two apart is one where <b>no
   * single root reaches the cap and the three together exceed it</b>: twenty matches in each, sixty
   * in all, against a cap of fifty. The real provider stops at fifty and reports {@link
   * Found#MATCHES}; the mutant returns sixty and reports {@code END}. An earlier fixture planted
   * one match per root and asserted {@code END}, which every implementation of a plural grep
   * returns.
   *
   * <p><b>Every root is still shown to have been searched</b>, and the cap does not cost that: the
   * roots are walked in {@code roots()} order, so the first two contribute twenty each and the cap
   * falls ten matches into the third. A provider that searched only the workspace, only the last
   * root, or only the lent ones fails on the count before it reaches the verdict.
   */
  @Test
  void a_grep_with_no_path_searches_every_lent_root_under_one_budget() throws IOException {
    Path second = Files.createDirectory(real.resolve("second"));
    int perRoot = 20;
    Files.writeString(repo.resolve("notes.md"), "the token is here\n".repeat(perRoot));
    Files.writeString(second.resolve("elsewhere.md"), "the token is over here\n".repeat(perRoot));
    Files.writeString(outside.resolve("further.md"), "the token is further away\n".repeat(perRoot));
    store.defineLending("payments", repo, List.of(second, outside), List.of());

    Found found = provider("payments", READ).grep(new Needle("token", false), null);

    assertEquals(
        sorted(
            List.of(
                repo.toRealPath().resolve("notes.md"),
                second.toRealPath().resolve("elsewhere.md"),
                outside.toRealPath().resolve("further.md"))),
        sorted(found.matches().stream().map(m -> Path.of(m.path())).distinct().toList()),
        matched(found));
    assertEquals(
        Needle.MAX_MATCHES,
        found.matches().size(),
        "one budget across the three roots and not one each: 3 x "
            + perRoot
            + " matches are available, no root holds "
            + Needle.MAX_MATCHES
            + " on its own,"
            + " and a provider that gave each root a list of its own would answer"
            + " with all "
            + (3 * perRoot));
    assertEquals(
        Found.MATCHES,
        found.stoppedBy(),
        "and it says so: a per-root budget would never have been reached here, so the"
            + " same mutant would report END and a model would read a truncated"
            + " answer as a complete one");
  }

  /**
   * A mandatory exclusion still beats a lent root.
   *
   * <p><b>This is the test that says lending did not become a way round the one rule this
   * repository keeps without exceptions.</b> Lending the directory the server's own configuration
   * and console token sit in must reach neither.
   *
   * <p>The mechanism is {@code FileAccess.of}, which drops any root a covering exclusion covers —
   * and it drops a lent root by exactly the argument it drops a workspace by, because it cannot
   * tell them apart and must not be able to. {@code
   * a_workspace_set_inside_the_agents_directory_grants_nothing} is this test's twin one column
   * across; the reason both exist is that the mutation which lets {@code lent} past the exclusions
   * leaves that one green.
   *
   * <p><b>Two mechanisms, and both are asserted here because either alone is survivable by a
   * mutant.</b> They are not the same rule:
   *
   * <ul>
   *   <li>a lent root that an exclusion covers <em>at least as specifically</em> is <b>dropped by
   *       {@code FileAccess.of}</b> and never appears in {@code roots()} — {@code agentsDir} below;
   *   <li>a lent root <em>above</em> an exclusion is <b>kept</b>, and each excluded path under it
   *       is refused by {@code permits} on longest match — the server's directory below, which
   *       holds the fixture's three excluded paths but is not itself one of them.
   * </ul>
   *
   * <p>Asserting only the first would let a mutant that dropped the root and granted the tree pass;
   * asserting only the second would let one that refused every path by dropping every root pass. So
   * the workspace's own file is read at the end: without it, "the token is unreachable" is also
   * true of a leash that reaches nothing at all, which is the shape a wrong fix takes.
   */
  @Test
  void a_mandatory_exclusion_still_beats_a_lent_root() throws IOException {
    Path server = tokenFile.getParent();
    Files.writeString(repo.resolve("notes.md"), "ordinary\n");
    Path beside = Files.writeString(server.resolve("lm-key-ish"), "beside the token\n");
    store.defineLending("payments", repo, List.of(server, agentsDir), List.of());
    LocalProvider provider = provider("payments", WRITE);

    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.read(tokenFile, FIRST),
        "the operator token is a complete credential for every gated route, and"
            + " lending the directory it sits in must not be a way to it");
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.read(configFile, FIRST),
        "nor the configuration, which holds the model API key");
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.write(agentsDir.resolve("mine.md"), "tools: [agent_run]"),
        "nor the definitions tier, where a written definition is a tool grant with a"
            + " delay fuse — and lending it by name must not be the way in");
    assertFalse(
        Files.exists(agentsDir.resolve("mine.md")),
        "and the refused write did not create the definition on its way past");

    assertEquals(
        List.of(repo.toRealPath(), server.toRealPath()),
        provider.roots(),
        "the root an exclusion covers exactly is dropped, the root merely above one"
            + " is kept, and the workspace is untouched: a provider that dropped"
            + " all three would pass every refusal above while granting nothing");
    assertEquals(
        List.of("beside the token"),
        provider.read(beside, FIRST).lines(),
        "and the kept root really does reach: the mandatory exclusion is the token"
            + " FILE and not the directory it sits in, so a file beside it is"
            + " readable — which is the cost of lending a configuration"
            + " directory, stated where somebody can see it rather than assumed"
            + " away by 'that root reaches nothing'");
    assertFalse(provider.read(repo.resolve("notes.md"), FIRST).lines().isEmpty());
  }

  // --- the five states in which there is no root ---------------------------

  @Test
  void a_workspace_set_inside_the_agents_directory_grants_nothing() throws IOException {
    // The escalation longest match creates when the root came from outside
    // the server. `agents` sits inside the data directory, which is
    // excluded for every project, and a projects row whose workspace is a
    // directory inside it is the more specific statement about that
    // subtree — so `permits` alone would hand the agent every definition,
    // and write access there is a tool grant with a delay fuse, effective
    // at the next boot.
    //
    // FileAccess.of is what drops it. This test is what proves this caller
    // passed the workspace as a workspace: withServerOwned would keep it,
    // and nothing else in this file could tell.
    Path mine = Files.createDirectory(agentsDir.resolve("mine"));
    Files.writeString(mine.resolve("anything.md"), "name: mine");
    store.define("sneaky", mine, List.of());
    LocalProvider provider = provider("sneaky", WRITE);

    assertEquals(List.of(), provider.roots());
    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider.read(mine.resolve("anything.md"), FIRST));
    assertTrue(
        refused.getMessage().contains("no project may reach"),
        "the state is named rather than reported as an ordinary out-of-scope"
            + " path: the workspace is set, it is simply unusable");
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.write(mine.resolve("anything.md"), "tools: [agent_run]"));
  }

  @Test
  void an_agent_that_declared_no_grant_reaches_nothing_and_its_definition_is_named() {
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments");

    assertEquals(List.of(), provider.roots());
    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class, () -> provider.read(repo.resolve("notes.md"), FIRST));
    assertTrue(
        refused.getMessage().contains("scopes"),
        "an agent granted nothing is told which file to change, not told to"
            + " ask an operator for a workspace that is already set");

    // Two of the five states hold at once here, and the order they are
    // checked in is the answer given. The definition comes first because it
    // is the only one of the five whose fix is the agent's own file — and
    // without this line the order is a claim in a comment that nothing holds.
    WorkspaceRefusedException inGlobal =
        assertThrows(
            WorkspaceRefusedException.class,
            () ->
                new LocalProvider(store, Home.global(), List.of())
                    .read(repo.resolve("notes.md"), FIRST));
    assertTrue(
        inGlobal.getMessage().contains("scopes"),
        "an agent that declared no grant would reach no file in any tier, so"
            + " naming the tier would send the reader to move a job that"
            + " would be refused wherever it ran");
  }

  @Test
  void a_global_job_has_no_workspace_and_says_so() {
    store.define("payments", repo, List.of());
    LocalProvider provider = new LocalProvider(store, Home.global(), List.of(READ));

    assertEquals(List.of(), provider.roots());
    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class, () -> provider.read(repo.resolve("notes.md"), FIRST));
    assertTrue(
        refused.getMessage().contains("global"),
        "global is the cross-project tier; no single filesystem means"
            + " everywhere, and the tier is what the reader has to change");
  }

  @Test
  void a_project_whose_name_can_never_have_a_workspace_stops_the_job() {
    // Home.of does not strip (HomeTest holds that), so " payments " is a
    // creatable, writable memory tier — and ProjectStore refuses that name
    // on every way in, so no workspace can ever be defined for it. A job
    // there must stop rather than run on with silently no file access, which
    // is what ProjectStore.find's javadoc asks the caller routing a job to
    // its files to decide. It is decided here, and this is the decision.
    LocalProvider provider = new LocalProvider(store, Home.of(" payments "), List.of(READ));

    WorkspaceUnavailableException gone =
        assertThrows(WorkspaceUnavailableException.class, provider::roots);
    assertTrue(
        gone.getMessage().contains("whitespace at one end"),
        "the reason travels with it, because the fix is to rename the tier"
            + " and nothing in the job can do that");
    assertFalse(
        gone.getMessage().contains("no longer"),
        "and it is not reported as a workspace that has disappeared, which is"
            + " the other thing this type says");
    assertThrows(
        WorkspaceUnavailableException.class, () -> provider.read(repo.resolve("notes.md"), FIRST));
  }

  // --- a root that has vanished --------------------------------------------

  @Test
  void a_provider_whose_root_has_vanished_says_that_rather_than_finding_nothing()
      throws IOException {
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", READ);
    Files.delete(repo);

    WorkspaceUnavailableException gone =
        assertThrows(WorkspaceUnavailableException.class, () -> provider.glob("**/*.java"));
    assertTrue(
        gone.getMessage().contains("no longer there"),
        "a root that vanished is named, not silently uncovered — otherwise an"
            + " empty result reads as 'there are no java files'");
    assertFalse(
        gone.getMessage().contains("no longer a directory"),
        "a directory that is gone must not be described as one that is still"
            + " there and is the wrong kind of thing");
  }

  @Test
  void a_root_replaced_by_a_file_is_named_as_that_rather_than_as_absent() throws IOException {
    // The matched pair of the test above, and it needs to be a pair:
    // measured, Files.isDirectory is false for both, while Files.exists is
    // true for this one and false for that one. A provider that reported
    // both as absent would send an operator looking for a path that is
    // sitting right there — ProjectStore.validWorkspace refuses a dangling
    // symlink on exactly this argument.
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", READ);
    Files.delete(repo);
    Files.writeString(repo, "a file where a directory was");

    WorkspaceUnavailableException gone =
        assertThrows(WorkspaceUnavailableException.class, () -> provider.glob("**/*.java"));
    assertTrue(gone.getMessage().contains("no longer a directory"));
    assertFalse(gone.getMessage().contains("no longer there"));
  }

  @Test
  void every_call_reports_a_vanished_root_and_not_only_the_one_that_searches() throws IOException {
    // Trap: closing this in glob alone leaves file_read answering "no file
    // at X" and file_write creating the workspace again as a side effect of
    // creating the directories above its target.
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", WRITE);
    Files.delete(repo);

    assertThrows(WorkspaceUnavailableException.class, provider::roots);
    assertThrows(
        WorkspaceUnavailableException.class, () -> provider.read(repo.resolve("notes.md"), FIRST));
    assertThrows(
        WorkspaceUnavailableException.class, () -> provider.write(repo.resolve("notes.md"), "x"));
    assertFalse(
        Files.exists(repo), "and the write did not recreate the tree on its way past the check");
  }

  // --- read ----------------------------------------------------------------

  @Test
  void a_file_inside_the_root_is_read() throws IOException {
    Files.writeString(repo.resolve("notes.md"), "héllo");
    store.define("payments", repo, List.of());

    assertEquals(
        List.of("héllo"),
        provider("payments", READ).read(repo.resolve("notes.md"), FIRST).lines(),
        "and the bytes come back decoded as UTF-8, not as latin-1");
  }

  @Test
  void a_path_outside_every_root_is_refused_and_says_so() throws IOException {
    Files.writeString(outside.resolve("secret"), "s");
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", READ).read(outside.resolve("secret"), FIRST));
    assertTrue(refused.getMessage().contains("outside"));
    assertFalse(
        refused.getMessage().contains("no file at"),
        "a file that exists must not be described as absent — the two refusals"
            + " share a type and an operator fixes them differently");
  }

  @Test
  void a_missing_file_inside_the_root_is_named_as_missing_and_not_as_out_of_scope() {
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", READ).read(repo.resolve("notyet.md"), FIRST));
    assertTrue(refused.getMessage().contains("no file at"));
    assertFalse(
        refused.getMessage().contains("outside"),
        "the model is told to check the name, not to check the leash");
  }

  @Test
  void a_symlink_out_of_the_root_is_refused_by_the_provider_too() throws IOException {
    // The doubled enforcement the spec asks for, in the process that opens
    // the file: FileAccessTest already proves `permits` answers false here,
    // and this proves the provider asks it before it opens anything.
    Files.writeString(outside.resolve("secret"), "s");
    Files.createSymbolicLink(repo.resolve("escape"), outside);
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", READ).read(repo.resolve("escape/secret"), FIRST));
    assertTrue(
        refused.getMessage().contains("outside"),
        "containment is about the resolved target, not the path typed");
  }

  @Test
  void a_symlink_to_a_file_inside_the_root_is_read_as_the_file_it_points_at() throws IOException {
    // The path that is checked has to be the path that is opened. A
    // provider that settled the candidate textually — absolute and
    // normalised, without following anything — would hand the open a
    // symlink, and the open refuses to follow one: an agent whose
    // repository has a symlinked file in it would be told that a file it can
    // see is "not a regular file". `permits` canonicalises internally, so
    // containment alone cannot tell the two apart.
    Files.writeString(repo.resolve("real.md"), "the contents");
    Files.createSymbolicLink(repo.resolve("alias.md"), repo.resolve("real.md"));
    store.define("payments", repo, List.of());

    assertEquals(
        List.of("the contents"),
        provider("payments", READ).read(repo.resolve("alias.md"), FIRST).lines());
  }

  @Test
  void a_link_chain_the_platform_gives_up_on_is_refused_by_where_it_really_ends()
      throws IOException {
    // FileAccess.canonical stops after a fixed number of hops and answers
    // with the link it stopped at, which is still inside the root — its own
    // test measures that. Asking `permits` about that answer resolves the
    // rest of the chain and finds where it goes; asking `permits` about the
    // path as it was typed does not, and the refusal that then arrives is
    // "not a regular file", which says nothing about the leash at all.
    Path chain = Files.createDirectory(repo.resolve("chain"));
    for (int link = 1; link < 45; link++) {
      Files.createSymbolicLink(chain.resolve("l" + link), chain.resolve("l" + (link + 1)));
    }
    Files.createSymbolicLink(chain.resolve("l45"), outside.resolve("target"));
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", READ).read(chain.resolve("l1"), FIRST));
    assertTrue(
        refused.getMessage().contains("outside"),
        "the refusal is about containment, which is what it is about");
  }

  @Test
  void a_directory_symlink_inside_the_root_is_listed_but_not_walked_through() throws IOException {
    // LocalProvider.walk owns why. The link has to point INSIDE the root:
    // one pointing outside is dropped by `permits` either way, so it could
    // not tell a following walk from a non-following one.
    Path src = Files.createDirectory(repo.resolve("src"));
    Files.writeString(src.resolve("A.java"), "a");
    Files.createSymbolicLink(repo.resolve("mirror"), src);
    store.define("payments", repo, List.of());

    assertEquals(
        List.of(repo.toRealPath().resolve("src/A.java")),
        sorted(provider("payments", READ).glob("**/*.java")),
        "one file, once, under the name it actually has");
  }

  @Test
  void the_config_is_unreachable_from_a_workspace_that_contains_it() throws IOException {
    // The whole of item 2 of what task 1 handed this task: the mandatory
    // exclusions are not in the row, so a provider built from
    // ProjectRecord.exclusions() is a containment check a projects row can
    // switch off. This row lists no exclusions of its own and its workspace
    // is the directory the configuration lives in.
    store.define("payments", configFile.getParent(), List.of());
    LocalProvider provider = provider("payments", READ);

    assertFalse(
        provider.roots().isEmpty(),
        "the workspace itself is fine — only the two paths inside it are not");
    assertThrows(WorkspaceRefusedException.class, () -> provider.read(configFile, FIRST));
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.read(samplingDir.resolve("scribe.md"), FIRST));
  }

  @Test
  void a_projects_own_exclusions_are_enforced_alongside_the_mandatory_ones() throws IOException {
    // The other half of the same argument. A provider built only from
    // ProjectStore.mandatoryExclusions — the static rule, with no row behind
    // it — passes the test above and fails this one.
    Path keys = Files.createDirectory(repo.resolve("keys"));
    Files.writeString(keys.resolve("api.txt"), "not a real key");
    Files.writeString(repo.resolve("notes.md"), "n");
    store.define("payments", repo, List.of(keys));
    LocalProvider provider = provider("payments", READ);

    assertThrows(
        WorkspaceRefusedException.class, () -> provider.read(keys.resolve("api.txt"), FIRST));
    assertEquals(
        List.of("n"),
        provider.read(repo.resolve("notes.md"), FIRST).lines(),
        "and the rest of the workspace is untouched");
  }

  // --- hidden means hidden, at the server's enforcement point ---------------

  @Test
  void a_hidden_file_under_a_workspace_is_not_readable_here_either() throws IOException {
    // The predicate at the point where a model actually reaches this
    // server's disk. FileAccessTest holds the rule; this holds that
    // LocalProvider goes through it, on all three of the tools that name a
    // path — a read, a stat and a write, since `permitted` is one call and a
    // caller that skipped it on one verb would be caught by no other test in
    // this file.
    Files.writeString(repo.resolve(".env"), "LM_API_KEY=notreal\n");
    Files.writeString(repo.resolve("notes.md"), "n");
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", WRITE);

    assertThrows(WorkspaceRefusedException.class, () -> provider.read(repo.resolve(".env"), FIRST));
    assertThrows(WorkspaceRefusedException.class, () -> provider.stat(repo.resolve(".env")));
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.write(repo.resolve(".ssh/authorized_keys"), "ssh-ed25519 no"),
        "and a write cannot create one either — a hidden directory that could be"
            + " written into is a hidden directory an agent can put a key in");
    assertEquals(
        List.of("n"),
        provider.read(repo.resolve("notes.md"), FIRST).lines(),
        "the rest of the workspace is untouched");
  }

  @Test
  void the_console_token_is_unreachable_from_a_workspace_over_a_home_directory()
      throws IOException {
    // THE ORIGINAL FINDING, at the enforcement point it was found at. The
    // precondition is a workspace whose roots cover ~/.config/plowshare/,
    // which is what an operator who points a project at $HOME does — and
    // nothing warns that this particular workspace choice is the one that
    // matters.
    //
    // A @TempDir stands in for $HOME. Nothing in this suite goes near the
    // real ~/.config/plowshare/, which holds a live credential.
    Path home = Files.createDirectory(real.resolve("home"));
    Path config = Files.createDirectories(home.resolve(".config/plowshare"));
    Path token = Files.writeString(config.resolve("console-token"), "not a real token\n");
    store.define("payments", home, List.of());
    LocalProvider provider = provider("payments", READ);

    assertFalse(
        provider.roots().isEmpty(),
        "the workspace itself is fine — it is what is inside it that is not");
    assertThrows(WorkspaceRefusedException.class, () -> provider.read(token, FIRST));
    assertEquals(
        List.of(),
        provider.glob("**/console-token"),
        "and a glob does not name it, since a name is what tells a model to ask");
    assertEquals(
        List.of(),
        provider.grep(new Needle("not a real token", false), null).matches(),
        "nor does a grep hand back the line, which is the whole file");
  }

  @Test
  void a_glob_does_not_list_what_is_hidden_and_a_grep_does_not_read_it() throws IOException {
    // ENUMERATION, not just reading, and it is the half that would go
    // missing first: FileSearch checks per candidate during the walk, and a
    // walk that checked once at the top would be a way to read exactly what
    // a read refuses. Both tools, because they are two call sites of one
    // walk and a fixture proving one proves nothing about the other.
    Path git = Files.createDirectories(repo.resolve(".git/objects"));
    Files.writeString(git.resolve("A.java"), "// the token is in here\n");
    Files.writeString(repo.resolve(".env"), "the token is in here too\n");
    Files.createDirectory(repo.resolve("src"));
    Files.writeString(repo.resolve("src/B.java"), "// the token is mentioned here\n");
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", READ);

    assertEquals(
        List.of(repo.toRealPath().resolve("src/B.java")),
        sorted(provider.glob("**/*.java")),
        "the hidden directory is walked and every candidate under it dropped");
    assertEquals(
        List.of(in("src/B.java")),
        provider.grep(new Needle("token", false), null).matches().stream()
            .map(Found.Match::path)
            .toList());
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.read(git.resolve("A.java"), FIRST),
        "and what the search left out is what a read refuses, which is the pair"
            + " that makes the search no way around the read");
  }

  @Test
  void a_configured_token_file_outside_a_hidden_directory_is_still_unreachable()
      throws IOException {
    // The second mechanism, and why it is not redundant with the first.
    // plowshare.auth.token-file is configurable: this fixture's deployment
    // writes its token to srv/console-token, where no dot-prefixed component
    // protects it. The predicate does nothing here and the mandatory
    // exclusion is the whole of the fence.
    store.define("payments", tokenFile.getParent(), List.of());
    LocalProvider provider = provider("payments", READ);

    assertFalse(
        provider.roots().isEmpty(),
        "the workspace is the server's own directory, which is a real workspace");
    assertThrows(WorkspaceRefusedException.class, () -> provider.read(tokenFile, FIRST));
    assertEquals(
        List.of(),
        provider.grep(new Needle("not a token", false), null).matches(),
        "and it is absent from a search over the directory it sits in");
  }

  @Test
  void an_export_directory_an_operator_moved_is_still_unreachable() throws IOException {
    // THE GAP, AT THE ENFORCEMENT POINT. Asserting that
    // ProjectStore.mandatoryExclusions contains this path would go on
    // passing if `permits` stopped consulting the list at all, which is the
    // failure that matters: the fence is not the list, it is the refusal.
    //
    // The arrangement is the live exposure spelled out. `exportDir` is
    // absolute and outside the server's own directory -- what an operator
    // gets by setting PLOWSHARE_EXPORT_DIR when the payloads outgrow the
    // disk the server is installed on -- and the workspace is the directory
    // above it, which is an ordinary thing for a workspace to be. Nothing
    // dot-prefixed is involved, so FileAccess.permits' hidden-component rule
    // does nothing here and this entry is the whole of the fence.
    Path tree = Files.createDirectories(exportDir.resolve("cnv_a1/payloads/cnv_a1"));
    Path ejected = Files.writeString(tree.resolve("0001.txt"), "what the tool returned");
    Files.writeString(
        exportDir.resolve("cnv_a1/manifest.jsonl"), "{\"conversation\":\"cnv_a1\",\"chars\":22}\n");
    Files.writeString(repo.resolve("notes.md"), "n");
    store.define("payments", real, List.of());
    LocalProvider provider = provider("payments", WRITE);

    assertFalse(
        provider.roots().isEmpty(),
        "the workspace itself is fine -- it is what is inside it that is not");
    assertThrows(WorkspaceRefusedException.class, () -> provider.read(ejected, FIRST));
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.read(exportDir.resolve("cnv_a1/manifest.jsonl"), FIRST),
        "and the manifest as much as the payload: it names every conversation,"
            + " every project and every handle in the tree");
    assertEquals(
        List.of(),
        provider.glob("**/*.txt"),
        "nor is it named, since a name is what tells a model to ask");
    assertEquals(
        List.of(),
        provider.grep(new Needle("what the tool returned", false), null).matches(),
        "and a grep does not hand back the line, which is the whole payload");
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.write(exportDir.resolve("cnv_a1/manifest.jsonl"), "{}"),
        "and a write cannot forge the record of what was ejected either");
    assertEquals(
        List.of("n"),
        provider.read(repo.resolve("notes.md"), FIRST).lines(),
        "the rest of the workspace is untouched");
  }

  @Test
  void the_tree_this_server_owns_is_unreachable_by_the_root_and_not_by_its_leaves()
      throws IOException {
    // THE ROOT, and the point is what is inside it that no other entry
    // names. `projects/<id>/images/` is reserved and not built; the layout
    // marker is not an export; a subdirectory added next year is neither. A
    // fence stated over the leaves would have to be restated each time, and
    // the export directory is the standing proof that it would not be.
    //
    // `dataDir` is disjoint from `exportDir` in this fixture on purpose. On
    // a real deployment the exports are inside this tree and the collapse
    // drops their entry, which would let either one stand in for the other.
    Path marker = Files.writeString(dataDir.resolve("layout.properties"), "layout-version=1\n");
    Path reserved = Files.createDirectories(dataDir.resolve("projects/7/images"));
    Files.writeString(reserved.resolve("a.png"), "not really a png");
    Files.writeString(repo.resolve("notes.md"), "n");
    store.define("payments", real, List.of());
    LocalProvider provider = provider("payments", WRITE);

    assertThrows(WorkspaceRefusedException.class, () -> provider.read(marker, FIRST));
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.read(reserved.resolve("a.png"), FIRST),
        "including what the tree only reserves: an agent names an image and the"
            + " server attaches the bytes -- it never reads the store");
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.write(dataDir.resolve("projects/7/exports/forged.txt"), "x"),
        "and a write cannot put anything into it either, which is what stops an"
            + " agent planting a payload for the next reader of the archive");
    assertEquals(List.of(), provider.glob("**/layout.properties"));
    assertEquals(
        List.of("n"),
        provider.read(repo.resolve("notes.md"), FIRST).lines(),
        "the rest of the workspace is untouched");
  }

  @Test
  void a_directory_is_not_a_file() throws IOException {
    Files.createDirectory(repo.resolve("src"));
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", READ).read(repo.resolve("src"), FIRST));
    assertTrue(
        refused.getMessage().contains("is a directory, so there is nothing to read"),
        "measured: Files.newInputStream(dir, NOFOLLOW_LINKS) opens on this"
            + " host, so nothing but an explicit attribute check catches it");
  }

  @Test
  void bytes_that_are_not_utf8_are_named_rather_than_returned_as_replacements() throws IOException {
    // LocalProvider.read owns why. Bytes chosen to be invalid UTF-8 rather
    // than merely non-ASCII: 0xff is not a lead byte in any position.
    Files.write(repo.resolve("logo.png"), new byte[] {(byte) 0xff, (byte) 0xd8, 0x00, 'a'});
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", READ).read(repo.resolve("logo.png"), FIRST));
    assertTrue(refused.getMessage().contains("not UTF-8 text"));
  }

  @Test
  void a_file_the_server_may_not_open_is_a_refusal_and_not_an_outage() throws IOException {
    // The difference between "this one file" and "the disk". A permission on
    // one file is not the workspace becoming unreachable, so the run goes on
    // and the model is told which path it was.
    //
    // This is the instrument for the AccessDeniedException clause, which is
    // a named NIO type and therefore a structural refusal rather than a
    // claim about which errno is common. Measured: readAttributes on a
    // mode-000 file succeeds and the open is what raises, so the clause has
    // to sit around the open.
    Path sealed = Files.writeString(repo.resolve("sealed.md"), "s");
    Files.setPosixFilePermissions(sealed, PosixFilePermissions.fromString("---------"));
    store.define("payments", repo, List.of());

    try {
      // Inside the try, so that a host where the seal does not hold gets
      // its permissions put back like any other exit from this method.
      assertTheSealHolds(sealed, "opening it");
      WorkspaceRefusedException refused =
          assertThrows(
              WorkspaceRefusedException.class,
              () -> provider("payments", READ).read(sealed, FIRST));
      assertTrue(
          refused.getMessage().contains("could not be opened"),
          "its own sentence, because it is its own clause");
      assertFalse(
          refused.getMessage().contains("could not be read"),
          "and not the untyped residue's, which is where the errnos that"
              + " are NOT the caller's mistake land");
    } finally {
      Files.setPosixFilePermissions(sealed, PosixFilePermissions.fromString("rw-------"));
    }
  }

  @Test
  void a_file_of_four_megabytes_is_ordinary_and_is_read() throws IOException {
    // The accepted side of MAX_FILE_BYTES, named absolutely. Without it the
    // only thing holding that constant is `a_file_inside_the_root_is_read`,
    // which reads five characters — so every value from six bytes upwards
    // passed, and the refusal test's fixture is built from the constant and
    // moves with it.
    //
    // AND the cliff, which this fixture never proved. It used to assert that
    // four megabytes came back, and four megabytes coming back is what killed
    // the session: the same string went on to a channel that closes at 1009
    // far below it. Both halves are asserted here now, because they are one
    // rule with two sides — the file has to be READABLE at this size, and
    // what comes back from reading it has to be one window of it.
    Path big = repo.resolve("generated.sql");
    byte[] chunk = new byte[1024 * 1024];
    Arrays.fill(chunk, (byte) 'a');
    // Line-structured, where the megabyte used to be one character repeated.
    // A file with no newline in it is a single line, and this test is about
    // a file that is large rather than about a line that is: a four-megabyte
    // line is now refused outright by Window.cut, so the old fixture would
    // fail here for a reason that has nothing to do with MAX_FILE_BYTES.
    // Before that refusal existed it was worse — the line came back whole
    // and the fixture proved the opposite of what is asserted below.
    for (int at = LINE - 1; at < chunk.length; at += LINE) {
      chunk[at] = '\n';
    }
    try (OutputStream out = Files.newOutputStream(big)) {
      for (int megabyte = 0; megabyte < 4; megabyte++) {
        out.write(chunk);
      }
    }
    store.define("payments", repo, List.of());

    Span window = provider("payments", READ).read(big, FIRST);

    assertEquals(
        4 * 1024 * 1024 / LINE,
        window.totalLines(),
        "four megabytes is a large file and not an unreasonable one, and every"
            + " line of it was counted");
    assertEquals(
        "a".repeat(LINE - 1),
        window.lines().get(0),
        "the window starts at the top of the file and carries its real lines");
    assertTrue(window.more());
    assertEquals(
        Span.BYTES,
        window.stoppedBy(),
        "the ceiling and not the line allowance, which is the answer that tells a"
            + " caller a wider limit would come back the same size");
    int carried = String.join("\n", window.lines()).getBytes(StandardCharsets.UTF_8).length;
    assertTrue(
        carried <= Window.MAX_WINDOW_BYTES,
        "a four-megabyte file came back as "
            + carried
            + " bytes. The window is in"
            + " the read, so what a socket has to carry is bounded by the window"
            + " and not by the file — which is the whole of this slice");
  }

  @Test
  void a_file_too_large_to_hold_in_memory_is_refused_with_its_size() throws IOException {
    // MAX_FILE_BYTES owns why. This is the REFUSED side, and its fixture is
    // built from the constant — so it moves with the value and holds only
    // that some limit exists. `a_file_of_four_megabytes_is_ordinary_and_is_read`
    // is what pins the number.
    Path fat = repo.resolve("huge.log");
    byte[] chunk = new byte[1024 * 1024];
    Arrays.fill(chunk, (byte) 'a');
    try (OutputStream out = Files.newOutputStream(fat)) {
      for (int written = 0; written <= LocalProvider.MAX_FILE_BYTES; written += chunk.length) {
        out.write(chunk);
      }
    }
    store.define("payments", repo, List.of());

    // A one-line window, and not the first window, because that is the
    // question windowing raises about this constant: MAX_FILE_BYTES bounds
    // what is held in memory, the whole file is still read before it is
    // cut, and so a narrow window is refused exactly as a wide one is. A
    // provider that seeked to the window instead would answer this.
    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", READ).read(fat, Window.of(0, 1)));
    assertTrue(
        refused.getMessage().contains(Long.toString(Files.size(fat))),
        "the size it found and the limit it holds, both, so a reader can tell"
            + " whether the file is the problem or the limit is");
    assertTrue(refused.getMessage().contains(Long.toString(LocalProvider.MAX_FILE_BYTES)));
  }

  // --- the window -----------------------------------------------------------

  /**
   * A file of {@code count} numbered lines, each ending in a newline.
   *
   * <p>Numbered rather than identical so that an off-by-one in the offset is visible in the
   * assertion rather than hidden behind lines that all look alike.
   */
  private Path numbered(String name, int count) throws IOException {
    StringBuilder text = new StringBuilder();
    for (int line = 0; line < count; line++) {
      text.append("line ").append(line).append('\n');
    }
    return Files.writeString(repo.resolve(name), text.toString());
  }

  @Test
  void a_window_inside_the_file_carries_its_lines_and_nothing_else() throws IOException {
    numbered("notes.md", 10);
    store.define("payments", repo, List.of());

    Span window = provider("payments", READ).read(repo.resolve("notes.md"), Window.of(3, 4));

    assertEquals(List.of("line 3", "line 4", "line 5", "line 6"), window.lines());
    assertEquals(3, window.offset(), "echoed, so the reply is legible without the request");
    assertEquals(
        10,
        window.totalLines(),
        "the whole file was counted even though four lines of it were carried");
    assertTrue(window.more());
    assertEquals(Span.LINES, window.stoppedBy());
  }

  @Test
  void a_window_reaching_the_last_line_says_the_file_is_finished() throws IOException {
    numbered("notes.md", 10);
    store.define("payments", repo, List.of());

    Span window = provider("payments", READ).read(repo.resolve("notes.md"), Window.of(6, 50));

    assertEquals(List.of("line 6", "line 7", "line 8", "line 9"), window.lines());
    assertFalse(window.more());
    assertEquals(
        Span.END,
        window.stoppedBy(),
        "which is what stops a model paging off the end of a file it has finished");
  }

  /**
   * The ordinary way a caller paging forward learns to stop. A refusal here would cost it a turn to
   * discover what an empty answer tells it for free, and the total is still the truth about the
   * file, which is what makes an offset well past the end recoverable rather than merely tolerated.
   */
  @Test
  void an_offset_past_the_end_is_an_empty_window_and_not_a_refusal() throws IOException {
    numbered("notes.md", 10);
    store.define("payments", repo, List.of());

    Span window = provider("payments", READ).read(repo.resolve("notes.md"), Window.of(400, 10));

    assertTrue(window.lines().isEmpty());
    assertEquals(10, window.totalLines(), "and the file is still ten lines long");
    assertFalse(window.more());
    assertEquals(Span.END, window.stoppedBy());
  }

  @Test
  void an_empty_file_is_an_empty_window_and_not_a_refusal() throws IOException {
    Files.writeString(repo.resolve("empty.md"), "");
    store.define("payments", repo, List.of());

    Span window = provider("payments", READ).read(repo.resolve("empty.md"), FIRST);

    assertTrue(window.lines().isEmpty());
    assertEquals(
        0,
        window.totalLines(),
        "measured: \"\".lines() is empty, so a file with nothing in it is no lines"
            + " rather than one blank one");
    assertFalse(window.more());
    assertEquals(Span.END, window.stoppedBy());
  }

  /**
   * The case the line count is most easily wrong about, and the one Window charges a byte it may
   * not have.
   *
   * <p>Both spellings of the same three lines are read, because the assertion worth making is that
   * they are <b>indistinguishable</b>. A provider that split on {@code \n} itself would report four
   * lines for the terminated file — a trailing empty one that is not in it — and a model told a
   * file has a line it does not have pages once into nothing.
   */
  @Test
  void a_last_line_with_no_newline_is_a_line_and_the_only_one_of_its_kind() throws IOException {
    Files.writeString(repo.resolve("bare.md"), "one\ntwo\nthree");
    Files.writeString(repo.resolve("ended.md"), "one\ntwo\nthree\n");
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", READ);

    byte[] onDisk = Files.readAllBytes(repo.resolve("bare.md"));
    assertEquals(
        (byte) 'e',
        onDisk[onDisk.length - 1],
        "the fixture has to actually lack the newline, or this test asserts nothing");

    Span bare = provider.read(repo.resolve("bare.md"), FIRST);
    Span ended = provider.read(repo.resolve("ended.md"), FIRST);

    assertEquals(List.of("one", "two", "three"), bare.lines());
    assertEquals(3, bare.totalLines(), "the last line is a line, newline or no newline");
    assertEquals(bare.lines(), ended.lines(), "and the terminated file is the same file");
    assertEquals(bare.totalLines(), ended.totalLines());
    assertFalse(bare.more());
  }

  /**
   * A terminator is not part of the line it ends, and since a reply carries the lines and nothing
   * beside them, a carriage return that is not in a line is not delivered.
   *
   * <p>This is a real change of what a reader is handed and it is the intended one: the alternative
   * shows a model a trailing {@code \r} on every line of a file it did not write and charges the
   * byte ceiling for each of them. Nothing reconstructs a file from a window — {@code file_write}
   * is handed its own content — so nothing depends on a window being byte-exact.
   */
  @Test
  void a_crlf_file_comes_back_as_lines_without_the_carriage_returns() throws IOException {
    Path dos =
        Files.write(
            repo.resolve("dos.txt"), "alpha\r\nbeta\r\ngamma\r\n".getBytes(StandardCharsets.UTF_8));
    store.define("payments", repo, List.of());

    Span window = provider("payments", READ).read(dos, FIRST);

    assertEquals(List.of("alpha", "beta", "gamma"), window.lines());
    assertEquals(3, window.totalLines(), "three lines and not six: a CRLF ends one line, not two");
    assertTrue(
        new String(Files.readAllBytes(dos), StandardCharsets.UTF_8).contains("\r"),
        "and the fixture really is a CRLF file, or this test asserts nothing");
  }

  @Test
  void a_stat_counts_the_lines_a_read_of_the_same_file_reports() throws IOException {
    numbered("notes.md", 137);
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", READ);

    Span counted = provider.stat(repo.resolve("notes.md"));

    assertEquals(
        provider.read(repo.resolve("notes.md"), FIRST).totalLines(),
        counted.totalLines(),
        "the two are counted the same way, or planning a read from a stat plans"
            + " against a file that is not there");
    assertEquals(137, counted.totalLines());
    assertTrue(counted.lines().isEmpty(), "a stat moves no text; that is what it is for");
    assertTrue(counted.more(), "and the file it did not carry is still there to be read");
    assertEquals(
        Span.LINES,
        counted.stoppedBy(),
        "END exactly when nothing follows is an invariant a caller relies on, and"
            + " something follows a stat of a file with lines in it");
  }

  @Test
  void a_stat_of_an_empty_file_is_the_end_of_it() throws IOException {
    Files.writeString(repo.resolve("empty.md"), "");
    store.define("payments", repo, List.of());

    Span counted = provider("payments", READ).stat(repo.resolve("empty.md"));

    assertEquals(0, counted.totalLines());
    assertFalse(counted.more());
    assertEquals(
        Span.END, counted.stoppedBy(), "the other half of the invariant: nothing follows, so END");
  }

  /**
   * A stat is not a cheaper way past the guards. Every refusal a read raises is raised here, or a
   * job could measure a file it may not see — and the line count of a file is not nothing, since it
   * is the difference between a one-line marker and a database dump.
   */
  @Test
  void a_stat_refuses_everything_a_read_refuses() throws IOException {
    Files.writeString(outside.resolve("secret"), "s");
    Files.createDirectory(repo.resolve("src"));
    Files.write(repo.resolve("logo.png"), new byte[] {(byte) 0xff, (byte) 0xd8, 0x00, 'a'});
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", READ);

    assertTrue(
        assertThrows(
                WorkspaceRefusedException.class, () -> provider.stat(outside.resolve("secret")))
            .getMessage()
            .contains("outside"));
    assertTrue(
        assertThrows(WorkspaceRefusedException.class, () -> provider.stat(repo.resolve("src")))
            .getMessage()
            .contains("is a directory, so there is nothing to read"));
    assertTrue(
        assertThrows(WorkspaceRefusedException.class, () -> provider.stat(repo.resolve("logo.png")))
            .getMessage()
            .contains("not UTF-8 text"));
    assertTrue(
        assertThrows(
                WorkspaceRefusedException.class, () -> provider.stat(repo.resolve("notyet.md")))
            .getMessage()
            .contains("no file at"));
  }

  // --- glob ----------------------------------------------------------------

  @Test
  void a_pattern_lists_the_files_below_a_root_by_their_whole_path() throws IOException {
    Path src = Files.createDirectories(repo.resolve("src/deep"));
    Files.writeString(src.resolve("A.java"), "a");
    Files.writeString(repo.resolve("src/B.java"), "b");
    Files.writeString(repo.resolve("README.md"), "r");
    store.define("payments", repo, List.of());

    assertEquals(
        List.of(
            repo.toRealPath().resolve("src/B.java"), repo.toRealPath().resolve("src/deep/A.java")),
        sorted(provider("payments", READ).glob("src/**/*.java")),
        "an absolute path is what file_read takes, so it is what glob hands back");
  }

  @Test
  void a_double_star_pattern_also_matches_a_file_at_the_top_of_the_root() throws IOException {
    // LocalProvider.matchers owns why. What this fixture needs is a file at
    // BOTH depths: the top-level one is what Java's reading loses, and the
    // nested one is what an elision-only implementation would lose, so one
    // of them alone would leave half the rule untested.
    Files.writeString(repo.resolve("A.java"), "a");
    Files.writeString(repo.resolve("B.md"), "b");
    Files.writeString(Files.createDirectory(repo.resolve("src")).resolve("C.java"), "c");
    store.define("payments", repo, List.of());

    assertEquals(
        List.of(repo.toRealPath().resolve("A.java"), repo.toRealPath().resolve("src/C.java")),
        sorted(provider("payments", READ).glob("**/*.java")),
        "zero directories is a match, as it is in the language the model"
            + " learned this syntax from");
  }

  @Test
  void a_star_star_that_is_not_a_whole_component_is_left_to_mean_what_it_says() throws IOException {
    // GlobSpellings.recursiveAt owns why. The fixture needs the file the
    // over-eager elision would invent (`srcMain.java`, top level) beside
    // the one the pattern really means, or it cannot tell an invented hit
    // from a missing one.
    Files.writeString(repo.resolve("srcMain.java"), "m");
    Files.writeString(Files.createDirectory(repo.resolve("srcdir")).resolve("A.java"), "a");
    store.define("payments", repo, List.of());

    assertEquals(
        List.of(repo.toRealPath().resolve("srcdir/A.java")),
        sorted(provider("payments", READ).glob("src**/*.java")));
  }

  @Test
  void a_pattern_matching_nothing_is_an_empty_list_and_not_a_refusal() {
    store.define("payments", repo, List.of());

    assertEquals(
        List.of(),
        provider("payments", READ).glob("**/*.java"),
        "an empty answer is reserved for 'searched, and there was nothing' —"
            + " which is exactly what happened here");
  }

  @Test
  void a_glob_with_no_root_to_search_says_so_rather_than_matching_nothing() {
    // The distinction the empty list above is being kept clean for. This job
    // did not search: there was nowhere to search, and Excalibur's own agent
    // spent 15 of its 16 turns inventing patterns against exactly this.
    LocalProvider provider = provider("payments", READ);

    WorkspaceRefusedException refused =
        assertThrows(WorkspaceRefusedException.class, () -> provider.glob("**/*.java"));
    assertTrue(refused.getMessage().contains("no workspace is defined"));
  }

  @Test
  void a_symlink_that_leaves_the_root_is_not_listed() throws IOException {
    // Every read tool applies the containment check, not just file_read: a
    // listing tool that skips it is a read tool with no boundary, and the
    // name it hands back is a name file_read would then refuse.
    Files.writeString(outside.resolve("Secret.java"), "s");
    Files.createSymbolicLink(repo.resolve("Escape.java"), outside.resolve("Secret.java"));
    Files.writeString(repo.resolve("Kept.java"), "k");
    store.define("payments", repo, List.of());

    assertEquals(
        List.of(repo.toRealPath().resolve("Kept.java")),
        sorted(provider("payments", READ).glob("*.java")),
        "the filter is on the resolved path, so the link lands outside and is"
            + " dropped even though its own name is inside the root");
  }

  @Test
  void an_excluded_subtree_is_not_listed_even_though_the_walk_reaches_it() throws IOException {
    Path keys = Files.createDirectory(repo.resolve("keys"));
    Files.writeString(keys.resolve("api.java"), "not a real key");
    Files.writeString(repo.resolve("Kept.java"), "k");
    store.define("payments", repo, List.of(keys));

    assertEquals(
        List.of(repo.toRealPath().resolve("Kept.java")),
        sorted(provider("payments", READ).glob("**/*.java")));
  }

  @Test
  void directories_are_not_listed_however_well_their_names_match() throws IOException {
    Files.createDirectory(repo.resolve("build.java"));
    Files.writeString(repo.resolve("Real.java"), "r");
    store.define("payments", repo, List.of());

    assertEquals(
        List.of(repo.toRealPath().resolve("Real.java")),
        sorted(provider("payments", READ).glob("*.java")),
        "a directory handed to file_read is a refusal one turn later");
  }

  @Test
  void an_absolute_pattern_is_refused_with_what_to_write_instead() {
    // Excalibur's case: `/etc/*` is the ordinary next guess for a model that
    // has just been told file_read takes absolute paths. Measured on JDK 21,
    // an absolute glob does not throw here as it does in Python — it simply
    // matches no relative path, so the failure would be a confident empty
    // answer rather than an error.
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class, () -> provider("payments", READ).glob("/etc/*"));
    assertTrue(
        refused.getMessage().contains("etc/*"),
        "the pattern that would have worked is in the refusal, because a"
            + " model told only 'no absolute patterns' guesses again");
  }

  @Test
  void a_pattern_that_is_nothing_at_all_is_refused_before_the_walk() {
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(WorkspaceRefusedException.class, () -> provider("payments", READ).glob("   "));
    assertTrue(
        refused.getMessage().contains("pattern is required"),
        "measured: getPathMatcher(\"glob:   \") compiles and matches a file"
            + " literally named three spaces, so an empty pattern would"
            + " otherwise be an ordinary search that found nothing");
  }

  @Test
  void a_pattern_at_the_wildcard_cap_still_finds_its_file() throws IOException {
    // THE TEST THAT PINS THE CAP, and the refusal below cannot: that one
    // builds its pattern from the constant, so the fixture moves with the
    // value and every value passes it. Measured — MAX_RECURSIVE_WILDCARDS
    // of 1 passed this whole file before this test existed, while silently
    // refusing ordinary patterns like `src/**/test/**/*.java`.
    //
    // Four separators, written as a literal on purpose. If the constant is
    // ever lowered, this fails and says so.
    Path deep = Files.createDirectories(repo.resolve("x/a/y/b/z/c/w"));
    Files.writeString(deep.resolve("F.java"), "f");
    store.define("payments", repo, List.of());

    assertEquals(
        List.of(deep.toRealPath().resolve("F.java")),
        sorted(provider("payments", READ).glob("**/a/**/b/**/c/**/*.java")),
        "a pattern with four recursive segments is an ordinary pattern");
  }

  @Test
  void a_pattern_with_more_recursive_wildcards_than_the_server_expands_is_refused() {
    // The expansion below is 2^n matchers, so n has to be bounded somewhere.
    store.define("payments", repo, List.of());
    String silly = "**/".repeat(GlobSpellings.MAX_RECURSIVE_WILDCARDS + 1) + "*.java";

    WorkspaceRefusedException refused =
        assertThrows(WorkspaceRefusedException.class, () -> provider("payments", READ).glob(silly));
    assertTrue(
        refused.getMessage().contains(Integer.toString(GlobSpellings.MAX_RECURSIVE_WILDCARDS + 1)),
        "the count it found is in the refusal, not only the limit");
  }

  @Test
  void a_pattern_that_will_not_compile_is_a_refusal_and_not_a_crash() {
    // Measured: getPathMatcher raises PatternSyntaxException, which is
    // unchecked — so an implementation that did not translate it would let
    // it out of this seam untyped, and RemoteProvider could never reproduce
    // a JDK regex type over a socket.
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", READ).glob("src/[unclosed"));
    assertTrue(refused.getMessage().contains("src/[unclosed"));
  }

  @Test
  void a_directory_that_cannot_be_listed_is_named_rather_than_silently_skipped()
      throws IOException {
    Path locked = Files.createDirectory(repo.resolve("locked"));
    Files.writeString(locked.resolve("Hidden.java"), "h");
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
    Files.writeString(repo.resolve("Seen.java"), "s");
    store.define("payments", repo, List.of());

    try {
      // Inside the try, for the same reason as in the sealed-file test.
      assertTheSealHolds(locked, "listing it");
      WorkspaceRefusedException refused =
          assertThrows(
              WorkspaceRefusedException.class, () -> provider("payments", READ).glob("**/*.java"));
      assertTrue(
          refused.getMessage().contains("locked"),
          "a partial listing returned as a complete one is the confident"
              + " empty answer in miniature: the directory that was"
              + " skipped is named so the model can narrow the pattern");
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void two_thousand_matches_are_an_ordinary_answer() throws IOException {
    // The accepted side of MAX_MATCHES, named absolutely: a repository-wide
    // `**/*.java` returns thousands, and refusing that would be a limit
    // pretending to be a safeguard. The refusal test below builds its
    // fixture from the constant and so holds only that some limit exists.
    Path many = Files.createDirectory(repo.resolve("many"));
    for (int file = 0; file < 2_000; file++) {
      Files.createFile(many.resolve(file + ".java"));
    }
    store.define("payments", repo, List.of());

    assertEquals(2_000, provider("payments", READ).glob("**/*.java").size());
  }

  @Test
  void more_matches_than_the_limit_is_refused_rather_than_quietly_truncated() throws IOException {
    // MAX_MATCHES owns why. The REFUSED side, with a fixture built from the
    // constant: `two_thousand_matches_are_an_ordinary_answer` is what pins
    // the number.
    Path many = Files.createDirectory(repo.resolve("many"));
    for (int file = 0; file <= LocalProvider.MAX_MATCHES; file++) {
      Files.createFile(many.resolve(file + ".java"));
    }
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class, () -> provider("payments", READ).glob("**/*.java"));
    assertTrue(refused.getMessage().contains(Integer.toString(LocalProvider.MAX_MATCHES)));
  }

  // --- write ---------------------------------------------------------------

  @Test
  void a_write_lands_inside_the_workspace_and_creates_the_directories_above_it()
      throws IOException {
    store.define("payments", repo, List.of());

    provider("payments", WRITE).write(repo.resolve("src/deep/New.java"), "class New {}");

    assertEquals("class New {}", Files.readString(repo.resolve("src/deep/New.java")));
  }

  @Test
  void a_read_grant_does_not_carry_write_and_the_refusal_names_the_mode() throws IOException {
    Files.writeString(repo.resolve("notes.md"), "before");
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", READ).write(repo.resolve("notes.md"), "after"));
    assertTrue(refused.getMessage().contains("read-only"));
    assertFalse(
        refused.getMessage().contains("outside"),
        "the path was inside the workspace, so describing it as out of scope"
            + " would point the reader at the projects row instead of at"
            + " the agent's scopes");
    assertEquals("before", Files.readString(repo.resolve("notes.md")));
  }

  @Test
  void a_write_grant_carries_read_as_well() throws IOException {
    Files.writeString(repo.resolve("notes.md"), "n");
    store.define("payments", repo, List.of());

    assertEquals(
        List.of("n"),
        provider("payments", WRITE).read(repo.resolve("notes.md"), FIRST).lines(),
        "write implies read, or every writing agent would have to declare both");
  }

  @Test
  void a_write_outside_the_workspace_is_refused_as_out_of_scope_and_not_as_read_only() {
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", WRITE).write(outside.resolve("planted"), "x"));
    assertTrue(refused.getMessage().contains("outside"));
    assertFalse(
        refused.getMessage().contains("read-only"),
        "the grant was write; naming the mode here would be an error message"
            + " describing a situation that does not hold");
    assertFalse(Files.exists(outside.resolve("planted")));
  }

  @Test
  void a_read_only_agent_writing_out_of_scope_hears_about_the_path_not_the_mode() {
    // LocalProvider.write owns why the order is what it is. Both refusals
    // hold here at once, which is the only arrangement that can tell which
    // one is checked first — a read grant AND a path outside the workspace.
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", READ).write(outside.resolve("planted"), "x"));
    assertTrue(refused.getMessage().contains("outside"));
    assertFalse(refused.getMessage().contains("read-only"));
  }

  @Test
  void a_write_through_a_dangling_link_out_of_the_root_creates_nothing() throws IOException {
    // Measured in FileAccessTest: writing to a link whose target is absent
    // creates that target. The link is inside the workspace and the byte
    // would land outside it.
    Files.createSymbolicLink(repo.resolve("gate"), outside.resolve("gone"));
    store.define("payments", repo, List.of());

    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider("payments", WRITE).write(repo.resolve("gate"), "x"));
    assertFalse(Files.exists(outside.resolve("gone")));
  }

  @Test
  void a_write_over_a_directory_is_refused_by_name() throws IOException {
    Files.createDirectory(repo.resolve("src"));
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", WRITE).write(repo.resolve("src"), "x"));
    assertTrue(refused.getMessage().contains("is a directory"));
  }

  @Test
  void a_write_below_something_that_is_not_a_directory_is_refused_by_name() throws IOException {
    // The other failure of a write inside the leash: `notes.md` is a file, so
    // the directories above `notes.md/x` cannot be created. Inside the root
    // and permitted, so neither the containment check nor the mode check
    // reaches it — this is what the open's own failure is translated for.
    Files.writeString(repo.resolve("notes.md"), "n");
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", WRITE).write(repo.resolve("notes.md/x"), "x"));
    assertTrue(refused.getMessage().contains("could not be written"));
    assertEquals(
        "n",
        Files.readString(repo.resolve("notes.md")),
        "and the file that was in the way is untouched");
  }

  @Test
  void a_write_replaces_the_whole_file_rather_than_appending_to_it() throws IOException {
    Files.writeString(repo.resolve("notes.md"), "a much longer previous version");
    store.define("payments", repo, List.of());

    provider("payments", WRITE).write(repo.resolve("notes.md"), "short");

    assertEquals(
        "short",
        Files.readString(repo.resolve("notes.md")),
        "truncating is the point: a shorter write that left the tail behind"
            + " would produce a file neither version ever had");
  }

  @Test
  void a_write_is_utf8_on_the_way_out_as_the_read_is_on_the_way_in() throws IOException {
    store.define("payments", repo, List.of());

    provider("payments", WRITE).write(repo.resolve("notes.md"), "héllo");

    assertEquals(
        "héllo",
        new String(Files.readAllBytes(repo.resolve("notes.md")), StandardCharsets.UTF_8),
        "the platform default charset is not a thing a server may depend on");
  }

  // --- grep ----------------------------------------------------------------

  @Test
  void a_search_with_no_path_walks_every_root_and_reports_where_each_line_is() throws IOException {
    Files.writeString(repo.resolve("notes.md"), "one\nthe workspace here\nthree\n");
    Files.createDirectory(repo.resolve("src"));
    Files.writeString(repo.resolve("src/A.java"), "class A {}\n// workspace again\n");
    Files.writeString(repo.resolve("quiet.txt"), "nothing of interest\n");
    store.define("payments", repo, List.of());

    Found found = provider("payments", READ).grep(new Needle("workspace", false), null);

    assertEquals(2, found.matches().size(), matched(found));
    assertFalse(found.capped());
    assertEquals(Found.END, found.stoppedBy());
    assertEquals(
        Set.of(in("notes.md") + ":1", in("src/A.java") + ":1"),
        found.matches().stream()
            .map(m -> m.path() + ":" + m.offset())
            .collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  void an_excluded_file_is_absent_from_a_search_that_names_no_path() throws IOException {
    // THE LEASH, and the one test that must exist. The exclusion is a per-path
    // check applied to every file the walk reaches, not once to the request —
    // a search that checked at the top would hand back the contents of the
    // very files a read of them refuses.
    Path keys = Files.createDirectory(repo.resolve("keys"));
    Files.writeString(keys.resolve("secrets.txt"), "the token is on this line\n");
    Files.writeString(repo.resolve("notes.md"), "the token is mentioned here too\n");
    store.define("payments", repo, List.of(keys));
    LocalProvider provider = provider("payments", READ);

    Found found = provider.grep(new Needle("token", false), null);

    assertEquals(
        List.of(in("notes.md")),
        found.matches().stream().map(Found.Match::path).toList(),
        matched(found));
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.read(keys.resolve("secrets.txt"), FIRST),
        "and the file the search left out is one a read refuses, which is the"
            + " pair that makes the search no way around the read");
  }

  @Test
  void the_config_is_absent_from_a_search_over_a_workspace_that_contains_it() throws IOException {
    // The other exclusion, and the one no projects row can switch off: the
    // mandatory ones. A provider whose walk asked ProjectRecord.exclusions()
    // passes the test above and hands back the server's own configuration
    // here.
    Files.writeString(samplingDir.resolve("scribe.md"), "grant: workspace\n");
    store.define("payments", configFile.getParent(), List.of());

    Found found = provider("payments", READ).grep(new Needle("fixture", false), null);

    assertEquals(List.of(), found.matches(), matched(found));
  }

  @Test
  void a_search_under_a_named_directory_stays_under_it() throws IOException {
    Files.createDirectory(repo.resolve("src"));
    Files.writeString(repo.resolve("src/A.java"), "// wanted\n");
    Files.writeString(repo.resolve("elsewhere.md"), "// wanted\n");
    store.define("payments", repo, List.of());

    Found found = provider("payments", READ).grep(new Needle("wanted", false), repo.resolve("src"));

    assertEquals(
        List.of(in("src/A.java")),
        found.matches().stream().map(Found.Match::path).toList(),
        matched(found));
  }

  @Test
  void a_search_of_a_path_outside_every_root_is_refused_as_a_read_of_it_would_be()
      throws IOException {
    Files.writeString(outside.resolve("theirs.md"), "wanted\n");
    store.define("payments", repo, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () ->
                provider("payments", READ)
                    .grep(new Needle("wanted", false), outside.resolve("theirs.md")));

    assertTrue(refused.getMessage().contains("outside every root"), refused.getMessage());
  }

  @Test
  void a_search_with_nowhere_to_look_says_so_rather_than_matching_nothing() {
    // FileProvider's standing rule, at the tool where breaking it is worst: a
    // confident empty answer here tells a model the thing it is looking for
    // is nowhere in the workspace.
    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", READ).grep(new Needle("wanted", false), null));

    assertTrue(refused.getMessage().contains("no workspace is defined"), refused.getMessage());
  }

  @Test
  void a_needle_that_is_nowhere_is_an_empty_answer_and_not_a_refusal() throws IOException {
    Files.writeString(repo.resolve("notes.md"), "one\ntwo\n");
    store.define("payments", repo, List.of());

    Found found = provider("payments", READ).grep(new Needle("wanted", false), null);

    assertEquals(List.of(), found.matches());
    assertEquals(Found.END, found.stoppedBy(), "searched, and there was nothing");
  }

  @Test
  void a_file_that_is_not_text_is_skipped_by_a_walk_and_refused_when_it_is_named()
      throws IOException {
    // The two halves of one decision, together because the difference is the
    // decision. A tree with an image in it is every real tree, so a walk that
    // failed on one would be a tool that cannot be used without a path — and a
    // model that named the file itself asked about that file and gets the
    // sentence.
    Path binary = repo.resolve("logo.bin");
    Files.write(binary, new byte[] {(byte) 0xff, (byte) 0xfe, 'w', 'a', 'n', 't', 'e', 'd'});
    Files.writeString(repo.resolve("notes.md"), "wanted, in text this time\n");
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", READ);

    Found found = provider.grep(new Needle("wanted", false), null);

    assertEquals(
        List.of(in("notes.md")),
        found.matches().stream().map(Found.Match::path).toList(),
        matched(found));
    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider.grep(new Needle("wanted", false), binary));
    assertTrue(refused.getMessage().contains("not UTF-8 text"), refused.getMessage());
  }

  @Test
  void a_search_that_spends_its_allowance_says_so() throws IOException {
    StringBuilder many = new StringBuilder();
    for (int at = 0; at < Needle.MAX_MATCHES * 3; at++) {
      many.append("wanted ").append(at).append('\n');
    }
    Files.writeString(repo.resolve("many.md"), many.toString());
    store.define("payments", repo, List.of());

    Found found = provider("payments", READ).grep(new Needle("wanted", false), null);

    assertEquals(Needle.MAX_MATCHES, found.matches().size());
    assertTrue(found.capped());
    assertEquals(
        Found.MATCHES,
        found.stoppedBy(),
        "narrow the needle is a different instruction from read the next window");
  }

  @Test
  void the_offset_a_match_carries_is_the_offset_a_read_of_it_takes() throws IOException {
    // The whole point of the tool, asserted through the seam rather than in
    // the arithmetic alone: one search, then one read, with no counting in
    // between. A provider that reported a one-based line here would send
    // every follow-up read one line late.
    StringBuilder plan = new StringBuilder();
    for (int at = 0; at < 400; at++) {
      plan.append(at == 317 ? "## Task 10 — the heading" : "filler " + at).append('\n');
    }
    Files.writeString(repo.resolve("plan.md"), plan.toString());
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", READ);

    Found found = provider.grep(new Needle("## Task 10", false), null);

    assertEquals(1, found.matches().size(), matched(found));
    Found.Match hit = found.matches().get(0);
    assertEquals(
        List.of("## Task 10 — the heading"),
        provider.read(Path.of(hit.path()), Window.of(hit.offset(), 1)).lines(),
        "the number in the answer opens the window that holds the line");
  }

  @Test
  void a_line_too_long_to_return_is_cut_and_the_match_says_it_was() throws IOException {
    Files.writeString(
        repo.resolve("bundle.min.js"),
        "var a=1;" + "x".repeat(Needle.MAX_LINE_CHARS * 4) + "wanted\n");
    store.define("payments", repo, List.of());

    Found found = provider("payments", READ).grep(new Needle("var a=1", false), null);

    assertEquals(1, found.matches().size(), matched(found));
    assertTrue(
        found.matches().get(0).truncated(),
        "one minified bundle is otherwise a single match whose line is the file");
    assertEquals(Needle.MAX_LINE_CHARS, found.matches().get(0).line().length());
  }

  @Test
  void a_directory_that_cannot_be_listed_stops_a_search_rather_than_shortening_it()
      throws IOException {
    // A tree searched in part and reported as searched in full is the
    // confident empty answer. Told apart from a FILE that cannot be read,
    // which is skipped — the two are one line apart in the implementation.
    Path sealed = Files.createDirectory(repo.resolve("sealed"));
    Files.writeString(sealed.resolve("inside.md"), "wanted\n");
    Files.setPosixFilePermissions(sealed, PosixFilePermissions.fromString("---------"));
    store.define("payments", repo, List.of());

    try {
      assertTheSealHolds(sealed, "listing it");
      WorkspaceRefusedException refused =
          assertThrows(
              WorkspaceRefusedException.class,
              () -> provider("payments", READ).grep(new Needle("wanted", false), null));
      assertTrue(
          refused.getMessage().contains("could not be searched in full"), refused.getMessage());
      assertTrue(refused.getMessage().contains("sealed"), refused.getMessage());
    } finally {
      Files.setPosixFilePermissions(sealed, PosixFilePermissions.fromString("rwx------"));
    }
  }

  // --- edit: what it hands back ---------------------------------------------------

  /**
   * An edit hands back the lines its new text occupies, numbered as this provider's own read
   * numbers them — the same {@code Replacement} view a client sends, because both sides run that
   * class.
   */
  @Test
  void an_edit_returns_the_changed_lines_as_a_read_of_the_file_numbers_them() throws IOException {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      text.append("l").append(i).append("\r\n");
    }
    Path file = Files.writeString(repo.resolve("A.txt"), text);
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", WRITE);

    Changed edited = provider.edit(file, "l10\r\n", "A\r\nB\r\n");

    assertEquals(
        "The new text is on lines 10 to 11 now, shown with the 3 lines either side:\n"
            + "[Lines 7 to 14 of 21, counting from 0 as offset does.]\n"
            + "l7\nl8\nl9\nA\nB\nl11\nl12\nl13",
        FileWords.edited(edited.facts()));
    assertNull(edited.oldSentence(), "this server reports facts, as a client does");
    assertEquals(
        List.of("l7", "l8", "l9", "A", "B", "l11", "l12", "l13"),
        provider.read(file, Window.of(7, 8)).lines());
  }

  @Test
  void an_edit_that_misses_is_refused_with_the_closest_lines_and_changes_nothing()
      throws IOException {
    Path file = Files.writeString(repo.resolve("A.java"), "a();\nif (x) {\n    go();\n}\n");
    store.define("payments", repo, List.of());
    LocalProvider provider = provider("payments", WRITE);

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider.edit(file, "if (x) {\n    stop();\n}", "y"));

    assertTrue(
        refused.getMessage().startsWith("the text to replace is not in " + file + ";"),
        refused.getMessage());
    assertTrue(
        refused
            .getMessage()
            .endsWith(
                "The closest lines in the file now are:\n"
                    + "[Lines 1 to 3 of 4, counting from 0 as offset does.]\n"
                    + "if (x) {\n    go();\n}"),
        refused.getMessage());
    assertEquals("a();\nif (x) {\n    go();\n}\n", Files.readString(file));
  }

  @Test
  void an_edit_of_no_file_says_how_to_create_one() {
    store.define("payments", repo, List.of());
    Path file = repo.resolve("Nowhere.java");

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider("payments", WRITE).edit(file, "a", "b"));

    assertEquals(
        "there is no file at "
            + file
            + ", so nothing was edited; to create it,"
            + " send {\"path\", \"content\"} with the whole file",
        refused.getMessage());
    assertEquals(
        FileResult.noFile(FileRequest.EDIT, file.toString()),
        ((FileRefusedException) refused).facts());
    assertFalse(Files.exists(file));
  }

  /**
   * One file of the fixture tree, spelled as a search reports it.
   *
   * <p>Real-pathed, and that is the point rather than tidiness: this host's temp directory is
   * reached through a symlink, the roots a provider walks come out of {@code FileAccess} already
   * canonical, and an expectation written as {@code @TempDir} hands the path out compares two
   * spellings of one file. {@code linked} beside {@code real} in this fixture exists for the same
   * reason one level up.
   */
  private String in(String name) throws IOException {
    return repo.toRealPath().resolve(name).toString();
  }

  /** What a failure should print: the matches, rather than a bare count. */
  private static String matched(Found found) {
    return found.matches().stream().map(m -> m.path() + ":" + m.offset() + " " + m.line()).toList()
        + " "
        + found.stoppedBy();
  }

  private static List<Path> sorted(List<Path> paths) {
    return paths.stream().sorted().toList();
  }
}
