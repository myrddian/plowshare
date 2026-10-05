package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.Provenance;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.testpeer.TestRooting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A project names a workspace, and the paths no project may ever reach.
 *
 * <h2>The fixture is deliberately not named like the real thing</h2>
 *
 * <p>The configuration file here is {@code plowshare.yml} and the sampling directory is {@code
 * profiles}, neither of which is what the server ships ({@code application.yml}, and {@code
 * sampling} — {@code LlmProperties}' default). That is the point: a {@link ProjectStore} that
 * ignored its constructor arguments and hardcoded either real name would pass a test whose fixture
 * used the real names, and this project has already shipped one fixture whose value coincided with
 * the default it was meant to detect. Both live under a fresh {@code @TempDir}, so no hardcoded
 * absolute path can be right either.
 *
 * <h2>What is measured here rather than assumed</h2>
 *
 * <ul>
 *   <li>the {@code TEXT[]} round trip through {@code Connection.createArrayOf}, including a path
 *       holding a comma, braces, a quote and a backslash — every character that means something
 *       inside a Postgres array literal;
 *   <li>{@code Files.isDirectory} following a symlink, and {@code Files.exists} with {@code
 *       NOFOLLOW_LINKS} seeing a dangling one. The store's two refusals are built on that pair and
 *       the comment there cites these two tests by name.
 * </ul>
 */
@Tag("full-db")
@Testcontainers
class ProjectStoreTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  /**
   * Where this process was started, which is now one of the mandatory exclusions: Spring Boot
   * always searches {@code file:./} and {@code file:./config/}, so the directory beside the server
   * holds configuration this server loads whatever it is named. {@code ProjectStore
   * .mandatoryExclusions}' javadoc carries the measurement.
   *
   * <p>Written the same way the production code writes it, which is a concession this file
   * otherwise avoids — a JVM cannot change its working directory, so there is no other spelling
   * available. The tests that pin the <em>rule</em> use the overload that takes it and build the
   * whole arrangement under a {@code @TempDir} instead.
   */
  private static final Path SERVER_DIRECTORY = Path.of("").toAbsolutePath().normalize();

  @TempDir Path tmp;

  private Path repo;
  private Path configFile;
  private Path samplingDir;

  /**
   * Where this fixture's deployment writes its operator token.
   *
   * <p><b>Not under a dot-prefixed directory, and that is the whole reason this entry is in the
   * list at all.</b> {@code FileAccess.permits} refuses every path with a hidden component below
   * its root, so the shipped {@code ~/.config/plowshare/console-token} is already unreachable;
   * {@code plowshare.auth.token-file} is configurable, and an operator who points it somewhere
   * ordinary loses that protection with no warning. A fixture that hid it would be asserting the
   * predicate a second time and would pass with this entry deleted.
   */
  private Path tokenFile;

  /**
   * Where this fixture's deployment writes ejected conversation payloads.
   *
   * <p><b>Outside the working directory, and outside every other entry</b>, because that is the
   * arrangement the entry exists for. At the shipped default the directory is {@code exports},
   * relative, so it lands under the working directory and is dropped from the answer as a
   * descendant of it — a fixture that used the default would pass with this entry deleted, which is
   * exactly how it came to be missing.
   */
  private Path exportDir;

  /**
   * The root of the tree this fixture's deployment owns, and it is <b>disjoint from the export
   * directory above</b> on purpose.
   *
   * <p>On a real deployment the exports are <em>inside</em> it and the collapse drops the export
   * entry, which is the arrangement that makes the operator's list short and is its own test. A
   * fixture in that arrangement could not tell the two entries apart: deleting either one would
   * leave the other covering the same paths. Disjoint, each has to be there on its own account.
   */
  private Path dataDir;

  private ProjectStore store;

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(dataSource()).load().migrate();
    jdbc = new JdbcTemplate(dataSource());
  }

  /**
   * A fresh {@link DriverManagerDataSource} every time it is asked for, as {@code
   * ProposalStoreTest} does: the durability test needs one that shares nothing with the store under
   * test, and a shared field would quietly give it the same object.
   */
  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @BeforeEach
  void freshProjects() throws IOException {
    // CASCADE since V14, and the reason is the point of V14: `memories` and
    // `conversations` reference this table now, so Postgres refuses a plain
    // TRUNCATE of it whether or not those tables hold anything. This class
    // holds no memories and no conversations of its own to lose — every
    // test here defines a project that has never been written into.
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, projects CASCADE");
    repo = Files.createDirectory(tmp.resolve("repo"));
    configFile = Files.writeString(tmp.resolve("plowshare.yml"), "a fixture, not a key");
    samplingDir = Files.createDirectory(tmp.resolve("profiles"));
    tokenFile = Files.writeString(tmp.resolve("console-token"), "a fixture, not a token");
    exportDir = Files.createDirectory(tmp.resolve("ejected"));
    dataDir = Files.createDirectory(tmp.resolve("owned"));
    store = new ProjectStore(jdbc, configFile, samplingDir, tokenFile, exportDir, dataDir);
  }

  // --- what a project is ----------------------------------------------------

  /**
   * Read back through a second store over a second connection, so the answer came from Postgres and
   * not from anything the first store kept.
   */
  @Test
  void a_project_names_a_workspace_and_survives_a_restart() {
    store.define("payments", repo, List.of());

    ProjectStore afterRestart =
        new ProjectStore(
            new JdbcTemplate(dataSource()), configFile, samplingDir, tokenFile, exportDir, dataDir);
    assertEquals(repo, afterRestart.find("payments").orElseThrow().workspace());
  }

  @Test
  void a_project_with_no_row_is_an_ordinary_empty_answer() {
    assertTrue(
        store.find("payments").isEmpty(),
        "asking whether a project has a workspace is a question, not a mistake");
  }

  /**
   * {@link ProjectStore#exists(long)} is the one-bit question {@code DefinitionResolver} asks by id
   * on every project-tier cache miss -- see its own javadoc for why it is a {@code SELECT 1} and
   * not a decoded {@link ProjectRecord}. Found and empty, the same pair {@link #rootedElsewhere}
   * gets below for the same reason: an existence-shaped single-column query earns its own
   * instrument rather than riding along on some other test's assertion.
   */
  @Test
  void a_defined_project_exists_by_its_id() {
    store.define("payments", repo, List.of());

    assertTrue(
        store.exists(id("payments")),
        "a row define() just inserted has to be found by the id it was inserted under");
  }

  @Test
  void an_id_nothing_ever_inserted_does_not_exist() {
    assertFalse(
        store.exists(Long.MAX_VALUE),
        "an id no row was ever given is an ordinary absence, not a mistake -- and"
            + " Long.MAX_VALUE is not a value this table's BIGINT IDENTITY column"
            + " will ever hand out, so no test ordering can make this collide");
  }

  // --- id(String), exists(long)'s inverse ------------------------------------

  /**
   * {@link ProjectStore#id} is what {@code AgentController} translates a request's own {@code
   * project} field through, so this is the one test that pins the translation is really the row's
   * own surrogate key and not, say, a hash of the name or the name reflected back.
   */
  @Test
  void a_defined_projects_id_resolves_by_name() {
    store.define("payments", repo, List.of());

    assertEquals(
        id("payments"),
        store.id("payments"),
        "the id a caller building a DefinitionResolver.Caller gets has to be the same"
            + " one exists(long) and every project-tier path key on");
  }

  /**
   * An ordinary absence, on {@link #a_project_with_no_row_is_an_ordinary_empty_answer}'s own
   * reasoning: a project nothing has been written to is a question and not a mistake, so this
   * answers {@code null} rather than throwing — which is what lets {@code
   * requests.RequestedProjectId.lenient} read it as "no project tier to read" and fall back to the
   * boot set.
   */
  @Test
  void an_undefined_projects_id_is_an_ordinary_null() {
    assertNull(store.id("payments"));
  }

  /**
   * {@link #a_project_name_is_required(String)}'s own refusal, reused here because {@link
   * ProjectStore#id} calls the same {@code named} a blank or padded name is refused by everywhere
   * else in this class.
   */
  @Test
  void a_blank_or_padded_name_is_refused_when_resolving_its_id() {
    assertThrows(ValidationException.class, () -> store.id(""));
    assertThrows(ValidationException.class, () -> store.id(" payments"));
    assertThrows(ValidationException.class, () -> store.id("payments "));
  }

  /**
   * A project rooted on another machine still resolves its id — the one claim {@link
   * ProjectStore#id}'s own javadoc makes and nothing before this test measured: <b>no {@code
   * IS_THIS_SERVERS}</b>, unlike every leash method in this file, because a project's definitions
   * tier is server-side regardless of which machine holds its files. A store that quietly added the
   * filter back — the mistake every other method in this class exists to make on purpose — would
   * answer {@code null} here instead of the row's real id, and this is the one test that would
   * catch it.
   */
  @Test
  void a_project_rooted_elsewhere_still_resolves_its_id() {
    store.rootOn("ledger", "bench.local", "/srv/ledger");

    assertEquals(
        id("ledger"),
        store.id("ledger"),
        "a project's definitions tier does not depend on which machine holds its"
            + " files, so the id must resolve the same as any other project's");
  }

  /**
   * By name, and the {@code ORDER BY} that does it is load-bearing.
   *
   * <p>{@code GET /v1/projects} answers in whatever order this returns, so this is the order the
   * console's projects screen renders in — and {@code ProjectControllerTest} mocks the store, so if
   * the ordering is not asserted here it is asserted nowhere and dropping the clause changes a
   * screen with every suite still green.
   *
   * <p><b>Defined in an order no sort would produce, which is what makes the assertion an
   * assertion.</b> A fixture inserted alphabetically is satisfied by insertion order — and
   * insertion order is exactly what a small Postgres table hands back when nothing asks for
   * anything else, so such a fixture would pass against the very statement it is here to catch.
   * Three rows rather than two, so that the expected order is not also the reverse of the insertion
   * order: a mutant that ordered by name descending has somewhere to be wrong.
   */
  @Test
  void the_projects_come_back_by_name() throws IOException {
    Path ledgerRepo = Files.createDirectory(tmp.resolve("ledger-checkout"));
    store.define("payments", repo, List.of());
    store.define("archivist", tmp, List.of());
    store.define("ledger", ledgerRepo, List.of());

    assertEquals(
        List.of("archivist", "ledger", "payments"),
        store.all().stream().map(ProjectRecord::name).toList(),
        "the console renders this list in the order it arrives in");
  }

  /**
   * Re-defining a project is one write, not forget-then-define, and it replaces <b>both</b> halves
   * of the row.
   *
   * <p>This javadoc said "moving a workspace" until {@code moveWorkspace} arrived and took that
   * word: replacing the exclusions is exactly what a move must not do, and {@code
   * moving_a_workspace_keeps_the_projects_own_ exclusions} is the test that says so. The two are
   * the contrast, and this one is the half where wiping them is the point — a caller writing a
   * whole definition has said what the exclusions are.
   */
  @Test
  void redefining_a_project_moves_its_workspace_and_replaces_its_exclusions() throws IOException {
    Path moved = Files.createDirectory(tmp.resolve("moved"));
    store.define("payments", repo, List.of(repo.resolve("secrets")));

    store.define("payments", moved, List.of(moved.resolve("vendor")));

    ProjectRecord now = store.find("payments").orElseThrow();
    assertEquals(moved, now.workspace());
    assertEquals(List.of(moved.resolve("vendor")), now.exclusions());
    assertEquals(1, store.all().size(), "redefining is an update, not a second row");
  }

  /**
   * The rule is a <em>location</em>, not a filename, and this is where that is said in one
   * expression.
   *
   * <p>Hermetic, through the overload that takes the working directory: the public one reads {@code
   * Path.of("")}, which a JVM cannot change, so a test driving it could only assert against
   * whatever directory Gradle started in. Here the whole arrangement — a server directory, its
   * configuration, its sampling profiles — is built under a {@code @TempDir}.
   *
   * <p>The fixture's config file is named {@code plowshare.yml} and its sampling directory {@code
   * samplingprofiles}, neither of which is what the server ships, on this file's stated convention:
   * a fixture named like the default is one a hardcoded default would still pass.
   */
  @Test
  void the_exclusions_name_the_directory_spring_loads_configuration_from() throws IOException {
    // All three deliberately disjoint, so all three survive and the whole
    // derivation is visible at once. The arrangement where they nest is the
    // ordinary one and is its own test below.
    Path serverDirectory = Files.createDirectory(tmp.resolve("srv"));
    Path elsewhere = Files.createDirectory(tmp.resolve("etc"));
    Path config = Files.writeString(elsewhere.resolve("plowshare.yml"), "not a key");
    Path profiles = Files.createDirectory(tmp.resolve("samplingprofiles"));
    Path ejected = Files.createDirectory(tmp.resolve("ejectedpayloads"));
    Path owned = Files.createDirectory(tmp.resolve("serverowned"));

    List<Path> excluded =
        ProjectStore.mandatoryExclusionsStartedIn(
            config, profiles, null, ejected, owned, serverDirectory);

    assertEquals(List.of(serverDirectory, config, profiles, ejected, owned), excluded);
  }

  /**
   * And the working directory is in the set even when the operator's configuration is somewhere
   * else entirely.
   *
   * <p>The premise the security review measured: Spring Boot searches {@code file:./} and {@code
   * file:./config/} whatever {@code plowshare.workspace.config-file} says, so a rule derived only
   * from the configured path leaves every {@code application*} beside the server readable. <b>The
   * fixture puts the configuration outside the server directory precisely so that a rule built from
   * the configured path alone would produce a different answer</b> — it would return two paths,
   * neither of them the working directory.
   *
   * <p><b>It was not testing that until the token file was added, and the signature change is what
   * exposed it.</b> This called the three-argument overload with {@code serverDirectory} in the
   * <em>sampling</em> slot, so the working directory it actually used was {@code Path.of("")} — the
   * module directory Gradle started in — and {@code excluded.contains(serverDirectory)} passed
   * because the sampling directory was in the list, not because the working directory was. A test
   * named for a rule it did not exercise. The overload here now names the working directory
   * outright, which is what the package-private overload exists for.
   */
  @Test
  void the_working_directory_is_excluded_even_when_the_config_lives_elsewhere() throws IOException {
    Path serverDirectory = Files.createDirectory(tmp.resolve("srv"));
    Path elsewhere = Files.createDirectory(tmp.resolve("etc"));
    Path config = Files.writeString(elsewhere.resolve("plowshare.yml"), "not a key");

    List<Path> excluded =
        ProjectStore.mandatoryExclusionsStartedIn(
            config,
            serverDirectory.resolve("sampling"),
            null,
            serverDirectory.resolve("exports"),
            serverDirectory.resolve("data"),
            serverDirectory);

    assertTrue(excluded.contains(serverDirectory), excluded.toString());
    assertTrue(excluded.contains(config), excluded.toString());
  }

  /**
   * The default arrangement puts all three inside one another, and the set says so once rather than
   * three times: a duplicate would make the length of this list a number nothing could predict.
   */
  @Test
  void the_ordinary_arrangement_collapses_to_the_directory_that_holds_them_all()
      throws IOException {
    Path serverDirectory = Files.createDirectory(tmp.resolve("srv"));

    assertEquals(
        List.of(serverDirectory),
        ProjectStore.mandatoryExclusionsStartedIn(
            serverDirectory.resolve("application.yml"),
            serverDirectory.resolve("sampling"),
            serverDirectory.resolve("console-token"),
            serverDirectory.resolve("exports"),
            serverDirectory.resolve("data"),
            serverDirectory));
  }

  // --- moving a workspace, which keeps the exclusions ------------------------

  /**
   * Moving a workspace keeps the fence an operator already put up.
   *
   * <p><b>This is why it is not {@link ProjectStore#define} with the old exclusions passed back
   * in.</b> {@code define} replaces them, so a person pointing a project at a new checkout through
   * it would silently drop every path they had fenced off — and the caller that read them first in
   * order to pass them back would be two statements with a window between them, which is the shape
   * the upsert exists to avoid.
   *
   * <p>The fixture's exclusion is written under the <em>old</em> workspace, so a move that quietly
   * re-derived the list from the new one is a different answer rather than the same one.
   *
   * <p><b>And the lent roots, which is the same rule and was the same prose.</b> {@code
   * project_workspace_set}'s description promises a person that "the directories somebody lent stay
   * lent", {@code moveWorkspace}'s javadoc says it, and the statement names only {@code workspace}
   * — three sentences and no instrument. The lent root is also written under the old workspace, on
   * the exclusion's reasoning: it makes a move that re-derived the list a visibly different answer,
   * and it is the arrangement a person actually leaves behind when a checkout moves and what was
   * lent beside it does not.
   */
  @Test
  void moving_a_workspace_keeps_the_projects_own_exclusions_and_lent_roots() throws IOException {
    Path moved = Files.createDirectory(tmp.resolve("moved"));
    Path lent = Files.createDirectory(repo.resolve("vendor"));
    store.defineLending("payments", repo, List.of(lent), List.of(repo.resolve("secrets")));

    store.moveWorkspace("payments", moved);

    ProjectRecord now = store.find("payments").orElseThrow();
    assertEquals(moved, now.workspace());
    assertEquals(List.of(repo.resolve("secrets")), now.exclusions());
    assertEquals(
        List.of(lent),
        now.lent(),
        "the lent root survives the move and is not re-derived: moving a checkout"
            + " leaves what was lent beside it pointing where it pointed, which"
            + " is a cost this file records rather than a convenience");
    assertEquals(List.of(moved, lent), now.roots());
    assertEquals(1, store.all().size(), "moving is an update, not a second row");
  }

  /**
   * Two rows, because one cannot tell a predicate from its absence: without the {@code WHERE}, this
   * moves every project on the server onto one directory.
   */
  @Test
  void moving_one_projects_workspace_leaves_another_alone() throws IOException {
    Path moved = Files.createDirectory(tmp.resolve("moved"));
    store.define("payments", repo, List.of());
    store.define("billing", tmp, List.of());

    store.moveWorkspace("payments", moved);

    assertEquals(moved, store.find("payments").orElseThrow().workspace());
    assertEquals(tmp, store.find("billing").orElseThrow().workspace());
  }

  /**
   * A move is a move and never a create.
   *
   * <p>{@code define} upserts, deliberately; this does not, and the difference is what a person is
   * told when they mistype a project name. An upsert here would answer "done" and leave a second
   * project named {@code paymnets} with a leash nobody meant to grant.
   */
  @Test
  void moving_the_workspace_of_a_project_that_has_none_says_so() {
    ArchiveException e =
        assertThrows(ArchiveException.class, () -> store.moveWorkspace("payments", repo));

    assertTrue(e.getMessage().contains("payments"), e.getMessage());
    assertEquals(
        ArchiveException.class,
        e.getClass(),
        "a project with no workspace is absent, never refused");
    assertTrue(store.all().isEmpty(), "a refused move must not have created anything");
  }

  /**
   * The same two refusals {@code define} makes, because the path is checked where it is written and
   * not where it is typed.
   */
  @Test
  void a_moved_workspace_that_is_not_a_directory_is_refused() throws IOException {
    Path file = Files.writeString(tmp.resolve("notadir"), "x");
    store.define("payments", repo, List.of());

    ValidationException e =
        assertThrows(ValidationException.class, () -> store.moveWorkspace("payments", file));

    assertTrue(e.getMessage().contains("payments"), e.getMessage());
    assertEquals(
        repo,
        store.find("payments").orElseThrow().workspace(),
        "a refused move must leave the old leash in place");
  }

  /**
   * {@code defined_at} follows a move as well as a re-definition, which is not free: the move is a
   * hand-written UPDATE rather than the upsert, so the column has to be set there too, and
   * forgetting it would date a workspace changed this morning to whenever the project was first
   * named. Its pair below asks the same question of the other statement.
   */
  @Test
  void moving_a_workspace_dates_the_row_to_the_move() {
    store.define("payments", repo, List.of());
    OffsetDateTime first = definedAt("payments");

    store.moveWorkspace("payments", tmp);

    assertTrue(
        definedAt("payments").isAfter(first),
        "defined_at describes the definition in the row, which has just changed");
  }

  /**
   * The row is the current definition, so its instant is when that definition was made — not when
   * the project was first named.
   */
  @Test
  void redefining_a_project_dates_the_row_to_the_new_definition() {
    store.define("payments", repo, List.of());
    OffsetDateTime first = definedAt("payments");

    store.define("payments", tmp, List.of());

    assertTrue(
        definedAt("payments").isAfter(first),
        "defined_at describes the definition in the row, which has just changed");
  }

  /**
   * <b>Two projects, because one cannot tell a predicate from its absence.</b>
   *
   * <p>Every other test here leaves at most one row in the table, and with one row a {@code WHERE
   * name = ?} is indistinguishable from no {@code WHERE} at all. Measured: deleting the predicate
   * from {@code find} and from {@code forget} left the whole suite green, and the {@code forget}
   * version means <em>delete every project's workspace on the server</em>. This is the same
   * blindness that hid the missing {@code absolute()} on the exclusion list — a fixture holding
   * exactly one of something, twice now. When a query filters, the fixture has to give it something
   * to filter out.
   *
   * <p>The two rows differ in workspace <em>and</em> exclusions, and both lookups are asserted, so
   * no ordering of an unfiltered result can satisfy them both: whichever row an unpredicated {@code
   * SELECT} returns first, one of the two assertions is wrong.
   */
  @Test
  void one_projects_workspace_is_not_another_projects() throws IOException {
    Path ledgerRepo = Files.createDirectory(tmp.resolve("ledger-repo"));
    store.define("payments", repo, List.of(repo.resolve("cards")));
    store.define("ledger", ledgerRepo, List.of(ledgerRepo.resolve("gl")));

    assertEquals(repo, store.find("payments").orElseThrow().workspace());
    assertEquals(List.of(repo.resolve("cards")), store.find("payments").orElseThrow().exclusions());
    assertEquals(ledgerRepo, store.find("ledger").orElseThrow().workspace());
    assertEquals(
        List.of(ledgerRepo.resolve("gl")), store.find("ledger").orElseThrow().exclusions());

    // The upsert has the same shape of risk: it must update the row it
    // conflicts on and no other.
    store.define("payments", tmp, List.of());
    assertEquals(
        ledgerRepo,
        store.find("ledger").orElseThrow().workspace(),
        "redefining one project leaves every other project's workspace alone");

    store.forget("payments");
    assertTrue(store.find("payments").isEmpty());
    assertEquals(
        List.of("ledger"),
        store.all().stream().map(ProjectRecord::name).toList(),
        "forget deletes the project it was given, not the server's other workspaces");
  }

  @Test
  void a_forgotten_project_has_no_workspace() {
    store.define("payments", repo, List.of());

    store.forget("payments");

    assertTrue(store.find("payments").isEmpty());
  }

  /**
   * <b>The row survives, and that is what a foreign key made necessary.</b> Until V14 {@code
   * forget} deleted it, and could, because nothing pointed at this table; {@code
   * memories.project_id} does now, and a delete would either be refused outright or — with the
   * wrong {@code ON DELETE} — move a project's whole archive into the global tier.
   *
   * <p>What is asserted is the id and not merely the row: keeping it is what makes a project that
   * is forgotten and later re-defined the <em>same</em> project, still holding everything it ever
   * remembered, rather than a new one wearing its name.
   */
  @Test
  void forgetting_a_workspace_keeps_the_project_and_its_id() {
    store.define("payments", repo, List.of());
    Long before =
        jdbc.queryForObject("SELECT id FROM projects WHERE name = 'payments'", Long.class);

    store.forget("payments");

    assertEquals(
        before, jdbc.queryForObject("SELECT id FROM projects WHERE name = 'payments'", Long.class));
    store.define("payments", repo, List.of());
    assertEquals(
        before,
        jdbc.queryForObject("SELECT id FROM projects WHERE name = 'payments'", Long.class),
        "and re-defining it is the same project, not a new one with the old name");
  }

  /**
   * The exclusions go with the workspace: an exclusion is a hole in a leash, and a project with no
   * leash has nothing for them to be a hole in.
   */
  @Test
  void forgetting_a_workspace_drops_the_exclusions_with_it() throws IOException {
    Path secrets = Files.createDirectory(repo.resolve("secrets"));
    store.define("payments", repo, List.of(secrets));

    store.forget("payments");
    store.define("payments", repo, List.of());

    assertEquals(List.of(), store.find("payments").orElseThrow().exclusions());
  }

  /**
   * A project that only holds memories has a row since V14 — it has to, for the memories to point
   * at — and every answer this store gives about it is the answer it gave when the project had no
   * row at all.
   *
   * <p>That is the whole of what {@code HAS_A_WORKSPACE} buys: the table's meaning changed and this
   * class's contract did not.
   */
  @Test
  void a_project_that_has_only_been_written_into_has_no_workspace() {
    new MemoryStore(jdbc)
        .save(
            Memory.formed(
                "mem_ledger",
                "s",
                "sc",
                "b",
                new Provenance(Instant.EPOCH, "probe", "test"),
                Home.of("ledger")));

    assertEquals(
        1,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM projects WHERE name = 'ledger'", Integer.class),
        "the row is there, because the memory points at it");
    assertTrue(
        store.find("ledger").isEmpty(),
        "and the store still answers as it did when there was no row");
    assertEquals(List.of(), store.all());
    assertThrows(ArchiveException.class, () -> store.moveWorkspace("ledger", repo));
    assertThrows(ArchiveException.class, () -> store.forget("ledger"));
    assertThrows(ArchiveException.class, () -> store.effectiveExclusions("ledger"));
  }

  // --- lending, and taking it back ------------------------------------------

  /**
   * A define writes a whole definition, so it <em>replaces</em> what the project was lent.
   *
   * <p><b>The rule was argued at length and measured by nothing.</b> {@code UPSERT}'s comment says
   * a define that kept the lent roots would leave a project reaching directories the operator's new
   * definition never mentioned, and {@code defineLending}'s javadoc says the same one level up —
   * but the only fixtures that redefined a project with lent roots forgot it first, and a {@code
   * forget} empties the column, so {@code lent = projects.lent || EXCLUDED.lent} passed the whole
   * suite. It is the sharper mutation of the two available: a define that merged would grow every
   * leash it touched, and the operator reading their own new definition back would see the answer
   * they asked for.
   *
   * <p>No {@code forget} between the two definitions, for exactly that reason. Both are asserted
   * through {@code find}, because here the store does have an answer to give.
   */
  @Test
  void redefining_a_project_replaces_what_it_was_lent() throws IOException {
    Path first = Files.createDirectory(tmp.resolve("first"));
    Path second = Files.createDirectory(tmp.resolve("second"));
    store.defineLending("payments", repo, List.of(first), List.of());

    store.defineLending("payments", repo, List.of(second), List.of());

    assertEquals(
        List.of(second),
        store.find("payments").orElseThrow().lent(),
        "the second definition's list and not both lists: a define says what the"
            + " project is, and the verb that adds without rewriting is lend");
    assertEquals(List.of(repo, second), store.find("payments").orElseThrow().roots());

    store.define("payments", repo, List.of());

    assertEquals(
        List.of(),
        store.find("payments").orElseThrow().lent(),
        "and the three-argument form lends nothing, which is a definition that"
            + " takes the lent roots away rather than one that is silent about"
            + " them");
  }

  /**
   * {@code forget} clears {@code lent} as it clears {@code exclusions}.
   *
   * <p><b>The two are cleared together and they are not cleared for the same reason, which is why
   * this is its own test rather than a second assertion on the one above.</b> A stale exclusion
   * left behind would fence off more than an operator meant — recoverable, visible, and the
   * argument {@code forgetting_a_workspace_drops_the_exclusions_with_it} makes. A stale lent root
   * would <em>reach</em> more, and would be handed to whoever defined the name next without either
   * of them ever being told it was there.
   *
   * <p><b>The column is read directly, and that is a departure from this file's rule with a
   * reason.</b> The rule is that what is asserted is what the store answers, because a column
   * nothing reads is not a leash — and here the store has no answer to give: {@code find} carries
   * {@code workspace IS NOT NULL}, so a forgotten project comes back empty whatever {@code lent}
   * holds. The read-back-through-a-re-define spelling this test had first is <b>not</b> an
   * instrument for the statement's {@code lent = '{}'} at all: the {@code UPSERT} writes {@code
   * lent} too, so a {@code forget} that left the roots in the row passes it. Measured — the whole
   * suite stays green with the clause deleted. So both are asserted, and they are two different
   * rules: the column, which is this method's, and the leash a re-definition gets, which is {@code
   * defineLending}'s.
   */
  @Test
  void forgetting_a_workspace_drops_the_lent_roots_with_it() throws IOException {
    Path second = Files.createDirectory(tmp.resolve("second"));
    store.defineLending("payments", repo, List.of(second), List.of());
    assertEquals(
        List.of(second),
        store.find("payments").orElseThrow().lent(),
        "the premise: there is something to lose");

    store.forget("payments");

    assertEquals(
        List.of(),
        jdbc.queryForList(
            "SELECT unnest(lent) FROM projects WHERE name = 'payments'", String.class),
        "the row itself holds no lent root any more: a forgotten project is one this"
            + " server has no place for, and a place with an empty middle is what a"
            + " surviving root would make it");

    store.define("payments", repo, List.of());
    assertEquals(List.of(), store.find("payments").orElseThrow().lent());
    assertEquals(
        List.of(repo),
        store.find("payments").orElseThrow().roots(),
        "and the leash a re-definition gets is the workspace alone, not the workspace"
            + " plus a directory the previous definition happened to lend");
  }

  /**
   * A lent root that does not exist is refused when it is set, and the refusal names which one.
   *
   * <p><b>Naming it is the whole assertion.</b> A lend carries several paths, so "it does not
   * exist" without a path in it is a sentence an operator can only act on by bisecting their own
   * command — and the two roots here are both plausible, so a message that named the first would be
   * as useless as one that named neither. The absent one is deliberately in the middle position for
   * that reason.
   *
   * <p><b>Refused, unlike an exclusion, and the asymmetry is deliberate.</b> An exclusion naming a
   * path that does not exist yet fences it off from the moment it appears, which is what an
   * operator wants. A lent root naming one grants nothing and reports nothing: {@code FileAccess}
   * would carry a root covering no file, {@code file_roots} would advertise it to a model, and the
   * first refusal a person saw would be about the file rather than about the typo.
   */
  @Test
  void a_lent_root_that_does_not_exist_is_refused_and_named() throws IOException {
    Path first = Files.createDirectory(tmp.resolve("first"));
    Path absent = tmp.resolve("gone");
    Path last = Files.createDirectory(tmp.resolve("last"));

    ValidationException refused =
        assertThrows(
            ValidationException.class,
            () -> store.defineLending("payments", repo, List.of(first, absent, last), List.of()));

    assertTrue(refused.getMessage().contains(absent.toString()), refused.getMessage());
    assertTrue(refused.getMessage().contains("does not exist"), refused.getMessage());
    assertFalse(
        refused.getMessage().contains(first.toString()),
        "the refusal names the root that is wrong and not the ones that are fine,"
            + " or an operator has to bisect their own command: "
            + refused);
    assertTrue(
        store.find("payments").isEmpty(),
        "and validation runs before the write, so a refused lend leaves no row");
  }

  /**
   * The other half of the pair {@code validWorkspace} has made since V3: a file is not an absent
   * directory, and an operator fixes the two differently. Said of a lent root because the message
   * is composed there from a different noun, so a fixture on the workspace alone would leave this
   * spelling unmeasured.
   */
  @Test
  void a_lent_root_that_is_a_file_is_refused_as_not_a_directory() throws IOException {
    Path file = Files.writeString(tmp.resolve("notadir"), "x");

    ValidationException refused =
        assertThrows(ValidationException.class, () -> store.lend("payments", List.of(file)));

    assertTrue(refused.getMessage().contains("not a directory"), refused.getMessage());
    assertFalse(refused.getMessage().contains("does not exist"), refused.getMessage());
  }

  /**
   * Lending adds and does not replace, and it does not move the project.
   *
   * <p>The second half is what the whole column exists for: {@code workspace} is the {@code <PATH>}
   * of the canonical name, so a lend that reached its directory by rewriting the workspace would
   * rename the project. Asserted on the row rather than only through the record, because it is the
   * row {@code Presence} and {@code projects_machine_has_a_place} read.
   */
  @Test
  void lending_adds_a_root_without_moving_the_project() throws IOException {
    Path second = Files.createDirectory(tmp.resolve("second"));
    Path third = Files.createDirectory(tmp.resolve("third"));
    store.defineLending("payments", repo, List.of(second), List.of(repo.resolve("keys")));

    ProjectRecord after = store.lend("payments", List.of(third));

    assertEquals(List.of(second, third), after.lent());
    assertEquals(repo, after.workspace());
    assertEquals(
        repo.toString(),
        jdbc.queryForObject(
            "SELECT workspace FROM projects WHERE name = 'payments'", String.class));
    assertEquals(
        List.of(repo.resolve("keys")),
        after.exclusions(),
        "and the fence it already had is still there: a lend that rewrote the whole"
            + " definition would drop it, which is define's job and not this"
            + " one's");
  }

  /**
   * <b>A lent root does not extend the project's identity.</b> The canonical name and the {@code
   * ?root=} channel parameter are byte-identical with and without one.
   *
   * <p><b>This is the test the column's whole shape exists for.</b> The obvious design was to
   * pluralise {@code workspace}, and it fails here: {@code workspace} is the {@code <PATH>} of
   * {@code <MACHINE>/<PATH>/<PROJ_NAME>}, so an identity composed from a list would rename the
   * project whenever the list reordered — and an array's order is whatever the last writer passed.
   * V30 keeps {@code workspace} scalar for exactly this, and this is what would go red if anything
   * started reading {@code lent} on the way to a name.
   *
   * <p><b>Both artefacts, because they are composed by different code from the same string.</b>
   * {@code Presence.canonicalName()} is the server's, built from what a client declared; {@code
   * TestRooting.of} is the client's, and its {@code root()} is the value that goes on the query
   * string as {@code ?root=}. A change that left one alone and moved the other would be two halves
   * of an identity disagreeing, which is the failure neither half can report on its own.
   *
   * <p><b>Asserted against the column and not against the record</b>, because the column is what a
   * presence and {@code projects_machine_has_a_place} actually read. A record whose {@code
   * workspace()} was right while the row had moved would be the one arrangement this could not see.
   *
   * <p>Three lent roots and not one, and one of them outside the workspace: a join rule that
   * produced an unchanged name for a single root inside the tree would be a rule that had merely
   * not been exercised.
   */
  @Test
  void a_lent_root_does_not_extend_the_projects_identity() throws IOException {
    Path inside = Files.createDirectory(repo.resolve("inside"));
    Path second = Files.createDirectory(tmp.resolve("second"));
    Path third = Files.createDirectory(tmp.resolve("third"));
    store.define("payments", repo, List.of());

    String placeBefore = workspaceOf("payments");
    String nameBefore = new Presence("s", "bench.local", placeBefore, "payments").canonicalName();
    String rootParamBefore = TestRooting.of(Path.of(placeBefore), "payments").root();

    store.lend("payments", List.of(inside, second, third));

    String placeAfter = workspaceOf("payments");
    assertEquals(
        placeBefore,
        placeAfter,
        "the row's place did not move, which is the only thing an identity reads");
    assertEquals(
        nameBefore,
        new Presence("s", "bench.local", placeAfter, "payments").canonicalName(),
        "the canonical name is byte-identical: a name composed from the roots would"
            + " rename this project every time the array reordered");
    assertEquals(
        rootParamBefore,
        TestRooting.of(Path.of(placeAfter), "payments").root(),
        "and so is the ?root= a client puts on the channel, which is the other half"
            + " of the same identity and is composed by different code");

    assertEquals(
        List.of(repo, inside, second, third),
        store.find("payments").orElseThrow().roots(),
        "while the leash really did grow, or this test is measuring a lend that"
            + " never happened");
  }

  /**
   * A root already lent is not lent twice. Not a rule anything is protected by — {@code
   * FileAccess.permits} answers identically either way — but a {@code file_roots} listing that
   * showed a model the same directory twice because a person retried a command is noise the model
   * has to reason about.
   */
  @Test
  void lending_a_root_the_project_already_has_does_not_repeat_it() throws IOException {
    Path second = Files.createDirectory(tmp.resolve("second"));
    store.defineLending("payments", repo, List.of(second), List.of());

    assertEquals(List.of(second), store.lend("payments", List.of(second)).lent());
    assertEquals(
        List.of(second),
        store.lend("payments", List.of(second, second)).lent(),
        "including a repeat inside one call, which the statement's own comparison"
            + " cannot see because it only looks at what is already stored");
  }

  /**
   * Unlending takes back what it names, keeps the order of what survives, and is silent about a
   * root the project never had.
   *
   * <p><b>Silent by decision, and the decision is not {@code forget}'s.</b> {@code forget} refuses
   * a name no row holds because it answers with <em>nothing</em>, so silence there is
   * indistinguishable from success. This answers with the row, so an operator who mistyped a path
   * reads the unchanged list straight back — and telling the two apart would need the array as it
   * was before the statement, which Postgres has no {@code OLD} in {@code RETURNING} to give and
   * which a second read would reintroduce the window for.
   *
   * <p>The surviving order is asserted because it is what {@code ProjectRecord.roots()} renders and
   * what {@code file_roots_renders_every_lent_root_with_the_workspace_first} depends on one layer
   * up.
   */
  @Test
  void unlending_takes_back_only_what_it_names() throws IOException {
    Path first = Files.createDirectory(tmp.resolve("first"));
    Path second = Files.createDirectory(tmp.resolve("second"));
    Path third = Files.createDirectory(tmp.resolve("third"));
    Path never = Files.createDirectory(tmp.resolve("never"));
    store.defineLending("payments", repo, List.of(first, second, third), List.of());

    ProjectRecord after = store.unlend("payments", List.of(second, never));

    assertEquals(
        List.of(first, third),
        after.lent(),
        "what it named is gone, what it did not name is in the order it was in, and"
            + " a root the project never lent is not an error");
    assertEquals(repo, after.workspace());
  }

  /**
   * <b>A lend is one statement, so there is no window for a second one to land in.</b>
   *
   * <p><b>This test exists because the argument it holds was unfalsified.</b> {@code LEND}
   * concatenates inside the {@code UPDATE} and its comment argues at length that the obvious
   * spelling — read the array, merge in Java, write it back — loses a root whenever two operators
   * lend at once: both read the same list and the second write discards the first's directory. That
   * argument was measured by nothing. Replacing the statement with exactly the read-then-write it
   * warns against left this whole suite, {@code LocalProviderTest} and {@code
   * ProjectControllerTest} green, which means the comment was doing the work of a rule while being
   * the only thing that held it.
   *
   * <p><b>The statement count is the instrument, because it is the rule.</b> A race has no
   * deterministic test — the read-then-write version passes a concurrency fixture most of the time,
   * which is worse than failing — so what is asserted is not "the race does not happen" but "there
   * is no second statement for it to happen between". {@code LocalProviderTest.one_call_
   * reads_the_projects_table_once_so_a_forget_has_no_window_to_land_in} holds the same shape one
   * layer up and for the same reason.
   *
   * <p><b>Both overloads are counted, and both have to be.</b> {@code lend} reaches the database
   * through {@code query(PreparedStatementCreator, ResultSetExtractor)}; a read-then-write would
   * reach {@code find} through {@code query(String, RowMapper, Object...)}. Counting only the first
   * would report 1 for the mutant as readily as for the real thing.
   *
   * <p>Unlend is counted too. It has no merge and so no window of its own — which is exactly why it
   * would be the easy one to "simplify" into a read and a write while nothing noticed.
   */
  @Test
  void lending_is_one_statement_so_a_second_lend_has_no_window_to_land_in() throws IOException {
    Path first = Files.createDirectory(tmp.resolve("first"));
    Path second = Files.createDirectory(tmp.resolve("second"));
    AtomicInteger statements = new AtomicInteger();
    JdbcTemplate counting =
        new JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public <T> T query(PreparedStatementCreator creator, ResultSetExtractor<T> reader) {
            statements.incrementAndGet();
            return super.query(creator, reader);
          }

          @Override
          public <T> List<T> query(
              String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
            statements.incrementAndGet();
            return super.query(sql, mapper, args);
          }
        };
    ProjectStore counted =
        new ProjectStore(counting, configFile, samplingDir, tokenFile, exportDir, dataDir);
    counted.define("payments", repo, List.of());

    statements.set(0);
    assertEquals(
        List.of(first),
        counted.lend("payments", List.of(first)).lent(),
        "and it is still the right answer");
    assertEquals(
        1,
        statements.get(),
        "a lend that read the row first would count two, and the read is the window:"
            + " two operators lending at once would each see the same list and the"
            + " second write would discard the first's directory");

    statements.set(0);
    assertEquals(
        List.of(first, second),
        counted.lend("payments", List.of(first, second)).lent(),
        "including the deduplication against what is already stored, which is the"
            + " part that looks like it needs a read and does not");
    assertEquals(1, statements.get());

    statements.set(0);
    assertEquals(List.of(first), counted.unlend("payments", List.of(second)).lent());
    assertEquals(1, statements.get());
  }

  /**
   * <b>Two lends really do land, with one of them made to wait for the other.</b>
   *
   * <p><b>This is the rule the statement count above only stands in for.</b> That test holds "there
   * is no second statement for a race to happen between", which kills the read-then-write spelling
   * and is worth keeping — but it is true of a {@code lend} that is one statement while its
   * <em>caller</em> does the read, and it says nothing about the thing the design actually rests
   * on: that Postgres re-evaluates {@code lent || (…)} against the row as the concurrent writer
   * left it, rather than against the row this statement first saw.
   *
   * <p><b>Not a flaky race, because the lock supplies the ordering.</b> Connection A lends {@code
   * first} and does not commit, so it holds the row. A second thread lends {@code second} through
   * an ordinary connection and blocks — the test waits for {@code pg_stat_activity} to say so
   * rather than for a duration, so nothing here depends on how fast the container is. Committing A
   * is what releases it, and the assertion is on the row both touched. The interleaving that would
   * make this pass by luck is the one the wait excludes.
   *
   * <p><b>What the read-then-write spelling does here, and it is not a refusal.</b> Under {@code
   * READ COMMITTED} the loser's {@code SELECT} cannot see A's uncommitted array, so it merges
   * {@code second} into an empty list, blocks on the {@code UPDATE}, and writes {@code {second}}
   * over a row that by then says {@code {first}}. Nothing fails: {@code first} is simply gone, in
   * the one column whose contents are what an agent may read.
   */
  @Test
  void a_second_lend_waiting_on_the_first_keeps_both_roots() throws Exception {
    Path first = Files.createDirectory(tmp.resolve("first"));
    Path second = Files.createDirectory(tmp.resolve("second"));
    store.define("payments", repo, List.of());

    // suppressClose, because JdbcTemplate releases the connection after every
    // statement and a released one here would commit and end the test's whole
    // premise. The transaction is this test's to end, and it ends it below.
    SingleConnectionDataSource held =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    held.setAutoCommit(false);
    AtomicReference<Throwable> failed = new AtomicReference<>();
    Thread waiting = new Thread(() -> store.lend("payments", List.of(second)), "second-lend");
    waiting.setUncaughtExceptionHandler((thread, thrown) -> failed.set(thrown));
    try {
      ProjectStore uncommitted =
          new ProjectStore(
              new JdbcTemplate(held), configFile, samplingDir, tokenFile, exportDir, dataDir);
      assertEquals(
          List.of(first),
          uncommitted.lend("payments", List.of(first)).lent(),
          "the premise: A has written and is holding the row");
      assertEquals(
          List.of(),
          store.find("payments").orElseThrow().lent(),
          "and nobody else can see it yet, which is exactly the list a"
              + " read-then-write lend would merge into");

      waiting.start();
      awaitBlockedOnTheProjectsRow();

      held.getConnection().commit();
      waiting.join(30_000);
    } finally {
      held.destroy();
    }

    assertFalse(waiting.isAlive(), "the second lend never came back after the commit");
    assertNull(failed.get(), "the second lend failed rather than waiting: " + failed.get());
    assertEquals(
        List.of(first, second),
        store.find("payments").orElseThrow().lent(),
        "both roots, in the order they were committed: the second statement resumed"
            + " against the row the first one left, which is what concatenating"
            + " inside the UPDATE buys and what merging in Java loses");
  }

  /**
   * Waits until Postgres reports a backend blocked on a lock over {@code projects}, which is the
   * second lend having reached its {@code UPDATE}.
   *
   * <p>Polling a fact rather than sleeping a guess: a fixed pause would be either flaky on a loaded
   * container or slow on every other run, and the property the caller needs is not "some time
   * passed" but "the second writer is now waiting on the first". A timeout here is a real failure —
   * nothing else in that test can be true if this never happens.
   */
  private static void awaitBlockedOnTheProjectsRow() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      Integer blocked =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                  + " AND query LIKE '%projects%'",
              Integer.class);
      if (blocked != null && blocked > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "no backend ever waited on a lock over `projects`, so the"
            + " second lend did not reach its UPDATE while the first held the row — the"
            + " test's ordering never happened and its assertion would mean nothing");
  }

  /**
   * The {@code lent} round trip, over the two SQL surfaces {@code exclusions} never crosses.
   *
   * <p>{@code an_exclusion_list_round_trips_through_the_text_array_column} measures the same four
   * characters through {@code setArray} alone. Lending takes each path through {@code
   * unnest(?::text[])} and back out of {@code array_agg}, and matches it with {@code = ANY
   * (?::text[])} in two places: the subquery that keeps {@code LEND} from appending a root twice,
   * and the filter {@code UNLEND} rebuilds the array through. A quoting fault on any of them shows
   * up as a root stored under a different spelling, a deduplication that stops deduplicating, or an
   * unlend that silently removes nothing — three quiet answers rather than an error, which is why
   * all three are exercised here on the same awkward paths.
   */
  @Test
  void a_lent_root_list_round_trips_through_the_two_array_surfaces() throws IOException {
    List<Path> awkward =
        List.of(
            Files.createDirectory(tmp.resolve("a,comma")),
            Files.createDirectory(tmp.resolve("{braced}")),
            Files.createDirectory(tmp.resolve("a \"quoted\" name")),
            Files.createDirectory(tmp.resolve("back\\slash")));
    store.define("payments", repo, List.of());

    assertEquals(
        awkward,
        store.lend("payments", awkward).lent(),
        "in through unnest and back out of array_agg, in the order they were sent");
    assertEquals(
        awkward,
        store.find("payments").orElseThrow().lent(),
        "and that is what the column holds, read back by a plain SELECT");

    assertEquals(
        awkward,
        store.lend("payments", awkward).lent(),
        "= ANY over the stored array still matches them, so a second lend of the same"
            + " four adds nothing: a quoting fault here would double the list");

    assertEquals(
        List.of(awkward.get(0), awkward.get(2)),
        store.unlend("payments", List.of(awkward.get(1), awkward.get(3))).lent(),
        "and the same predicate takes back exactly the two it names, keeping the"
            + " order of what survives");
  }

  /**
   * A directory that has gone can still be taken back, which is the commonest reason to want to: a
   * checkout deleted or a mount unmounted would otherwise be stuck in the row with no verb able to
   * remove it.
   */
  @Test
  void unlending_a_root_that_has_vanished_is_allowed() throws IOException {
    Path going = Files.createDirectory(tmp.resolve("going"));
    store.defineLending("payments", repo, List.of(going), List.of());
    Files.delete(going);

    assertEquals(List.of(), store.unlend("payments", List.of(going)).lent());
  }

  /**
   * A lend that lends nothing is a caller that lost its list rather than an operator's intent, and
   * answering "done" to it is the one outcome nobody goes back and checks.
   */
  @Test
  void lending_or_unlending_nothing_is_refused_rather_than_answered() {
    store.define("payments", repo, List.of());

    assertThrows(ValidationException.class, () -> store.lend("payments", List.of()));
    assertThrows(ValidationException.class, () -> store.unlend("payments", List.of()));
  }

  /**
   * A project with no workspace on this server has no place to lend a directory at, and the refusal
   * is the same one {@code forget} and {@code moveWorkspace} give — {@code IS_THIS_SERVERS} is in
   * all three statements.
   */
  @Test
  void lending_to_a_project_with_no_workspace_is_refused() throws IOException {
    Path second = Files.createDirectory(tmp.resolve("second"));

    assertThrows(ArchiveException.class, () -> store.lend("nobody", List.of(second)));
    assertThrows(ArchiveException.class, () -> store.unlend("nobody", List.of(second)));
  }

  // --- a project whose files are on another machine ---------------------------

  /**
   * <b>The gap V15 closes, asserted at its narrowest point.</b>
   *
   * <p>{@code define} validates the workspace against the <em>server's</em> disk, so before this
   * there was no way to write down a project whose files are on a laptop: the design spec's §13.4
   * found that "define a project at a path that only exists on my laptop" is refused at definition
   * time and is therefore not a thing an operator can type. The thing presence exists for could not
   * be expressed.
   *
   * <p><b>The path is deliberately one that does not exist here</b>, and it is built under {@code
   * tmp} so that it cannot accidentally exist on any host this runs on. Nothing validates it and
   * nothing may: the server does not have that disk, and a check it cannot perform would be
   * theatre.
   */
  @Test
  void a_project_can_be_rooted_on_a_machine_this_server_has_no_disk_of() {
    String onlyThere = tmp.resolve("only-on-the-laptop").toString();

    store.rootOn("ledger", "bench.local", onlyThere);

    assertEquals(
        "bench.local",
        store
            .rootedElsewhere("ledger")
            .orElseThrow(() -> new AssertionError("the row does not say which machine holds it")),
        "the machine is stored rather than inferred, which is the whole of §5");
  }

  /**
   * A server-rooted project answers empty, and that is what makes the column a discriminator rather
   * than a second name for the workspace.
   */
  @Test
  void a_project_this_server_roots_is_not_rooted_elsewhere() {
    store.define("payments", repo, List.of());

    assertEquals(
        Optional.empty(),
        store.rootedElsewhere("payments"),
        "NULL means this server, so a defined project is not somewhere else");
  }

  @Test
  void a_project_with_no_row_at_all_is_not_rooted_elsewhere() {
    assertEquals(
        Optional.empty(),
        store.rootedElsewhere("ledger"),
        "an absence is not a claim about a machine");
  }

  /**
   * <b>The silent failure the pair of columns exists to prevent.</b>
   *
   * <p>A laptop rooting a path this server also happens to have -- {@code
   * /Users/example/proj/ledger} is on both machines the day somebody clones the same repository
   * twice -- would otherwise hand {@code LocalProvider} a leash over the <em>server's</em> copy,
   * under a project whose files are somewhere else entirely. Nothing would fail; an agent would
   * simply read the wrong tree. So {@code find} is the door {@code LocalProvider} reads a workspace
   * through and it carries {@code machine IS NULL}.
   *
   * <p>The fixture roots the project at a directory that <em>does</em> exist here, which is the
   * only spelling of this test that can fail.
   */
  @Test
  void a_workspace_on_another_machine_is_never_answered_as_this_servers_leash() {
    store.rootOn("ledger", "bench.local", repo.toString());

    assertEquals(
        Optional.empty(),
        store.find("ledger"),
        "the server holds no grant for a project whose files are elsewhere, even when a"
            + " directory of that name happens to sit on this disk");
  }

  /**
   * <b>The listing is every project with a place, and not only the server's.</b> It used to carry
   * the leash predicate, so a project rooted from a laptop with `/here` was in the table and never
   * in {@code project.list} — which made the machine that listing names always null, and the
   * terminal's `/project` and startup offer, which look for their own machine in it, unreachable. A
   * listing grants nothing; {@link ProjectStore#find} is the leash and is unchanged above.
   */
  @Test
  void a_project_rooted_on_another_machine_is_listed() {
    store.rootOn("ledger", "bench.local", repo.toString());

    assertEquals(
        List.of("ledger"),
        store.all().stream().map(ProjectRecord::name).toList(),
        "a project a client rooted exists, and a person asking which projects exist is"
            + " told about it");
    assertEquals(Optional.of("bench.local"), store.rootedElsewhere("ledger"));
  }

  /**
   * Re-declaring is an ordinary write, because a presence is declared afresh on every reconnect and
   * a client that moved its checkout is the case it is for.
   */
  @Test
  void the_same_machine_declaring_again_moves_the_root_rather_than_being_refused() {
    store.rootOn("ledger", "bench.local", "/srv/ledger");

    store.rootOn("ledger", "bench.local", "/srv/ledger-2");

    assertEquals(
        "/srv/ledger-2",
        jdbc.queryForObject(
            "SELECT workspace FROM projects WHERE name = ?", String.class, "ledger"));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM projects", Integer.class), "one row, not two");
  }

  /**
   * <b>A second machine is refused, and the sentence names the one that holds it.</b>
   *
   * <p>The registry refuses two <em>live</em> claims on one project; this is the same rule for the
   * durable half, and it is the half that catches the claim nothing is competing with -- a laptop
   * declaring a project the desk rooted last week and has since closed. §8.1: a project exists in
   * exactly one location, and two machines asserting they are one is a conflict rather than a
   * choice.
   */
  @Test
  void a_second_machine_claiming_a_rooted_project_is_refused_and_told_which_holds_it() {
    store.rootOn("ledger", "bench.local", "/srv/ledger");

    ArchiveRefusedException refused =
        assertThrows(
            ArchiveRefusedException.class,
            () -> store.rootOn("ledger", "desk.local", "/home/example/ledger"));

    assertTrue(
        refused.getMessage().contains("bench.local"),
        "the machine that holds it is named, because closing or renaming one of the two"
            + " is the only thing an operator can do: "
            + refused.getMessage());
    assertEquals(
        "bench.local",
        store.rootedElsewhere("ledger").orElseThrow(),
        "and the incumbent row is untouched");
  }

  /**
   * <b>A presence may not claim a project this server roots.</b>
   *
   * <p>The decision, and it is the same shape as the refusal above rather than a special case: a
   * server-rooted row is a location somebody established -- {@code define} checked that directory
   * on this disk -- and an archive has been filed under it by agents reading those files. Letting a
   * laptop claim the name would put one id over two places, which is the one thing §8.1 rules out,
   * and would hand a project's whole archive to another machine's files with nothing able to tell
   * afterwards.
   *
   * <p>It names the server as the holder, and the workspace, because the fix is on this side:
   * {@code project_forget} drops the workspace and leaves the archive, after which the row is
   * placeless and the presence can claim it and keep every memory.
   */
  @Test
  void a_presence_may_not_claim_a_project_this_server_roots() {
    store.define("payments", repo, List.of());

    ArchiveRefusedException refused =
        assertThrows(
            ArchiveRefusedException.class,
            () -> store.rootOn("payments", "bench.local", "/home/example/payments"));

    assertTrue(
        refused.getMessage().contains(repo.toString()),
        "the workspace this server holds is named: " + refused.getMessage());
    assertEquals(repo, store.find("payments").orElseThrow().workspace(), "and nothing was written");
  }

  /**
   * The escape route the refusal above names, driven end to end.
   *
   * <p>A project that lived here and has moved to a laptop is an ordinary thing to have happen, and
   * it must not cost the archive. {@code forget} nulls the workspace and keeps the row -- which is
   * V14's gain -- so the row becomes placeless, the presence can claim it, and the id never
   * changes.
   */
  @Test
  void a_project_forgotten_here_can_then_be_rooted_on_the_machine_it_moved_to() {
    store.define("payments", repo, List.of());
    long id = id("payments");

    store.forget("payments");
    store.rootOn("payments", "bench.local", "/home/example/payments");

    assertEquals("bench.local", store.rootedElsewhere("payments").orElseThrow());
    assertEquals(
        id,
        id("payments"),
        "the same project, so every memory and conversation filed under it is still" + " its own");
  }

  /**
   * <b>A placeless row is claimed rather than duplicated, and this is the upgrade path for every
   * project that already exists.</b>
   *
   * <p>A project an agent wrote a memory into has a row with no workspace -- {@code
   * ProjectIds.toWrite} registers it -- and V14 registered every name {@code memories} and {@code
   * conversations} already held. V15 leaves all of them with a NULL machine, which is not "the
   * server" but "no place at all", and that is exactly what makes them claimable.
   */
  @Test
  void a_project_that_had_no_place_keeps_its_id_when_a_presence_gives_it_one() {
    jdbc.update("INSERT INTO projects (name) VALUES (?)", "ledger");
    long id = id("ledger");

    store.rootOn("ledger", "bench.local", "/home/example/ledger");

    assertEquals(
        id,
        id("ledger"),
        "claiming a project is not creating one: the archive already filed under this"
            + " id stays the same project's");
  }

  /**
   * {@code define} keeps validating and now says why it cannot apply.
   *
   * <p>Not the CHECK constraint and not "that directory does not exist": both would be true and
   * neither says the thing an operator has to act on, which is that this project's files are on
   * another machine and no directory on this one is the right answer for it.
   */
  @Test
  void defining_a_project_rooted_on_another_machine_is_refused_by_naming_that_machine() {
    store.rootOn("ledger", "bench.local", "/home/example/ledger");

    ArchiveRefusedException refused =
        assertThrows(ArchiveRefusedException.class, () -> store.define("ledger", repo, List.of()));

    assertTrue(
        refused.getMessage().contains("bench.local"),
        "the machine that holds the project is named: " + refused.getMessage());
    assertEquals(
        "/home/example/ledger",
        jdbc.queryForObject(
            "SELECT workspace FROM projects WHERE name = ?", String.class, "ledger"),
        "and the upsert did not overwrite the place");
  }

  /**
   * The other two writes that would corrupt the pair, refused the same way.
   *
   * <p>{@code moveWorkspace} pointing a laptop-rooted project at a directory here would leave a row
   * saying the files are on {@code bench.local} at a path only this server has; {@code forget}
   * would drop the path and leave a machine with no place on it, which {@code
   * projects_machine_has_a_place} refuses outright. Both are the server editing somebody else's
   * location.
   */
  @Test
  void this_server_may_not_move_or_forget_a_workspace_on_another_machine() {
    store.rootOn("ledger", "bench.local", "/home/example/ledger");

    assertTrue(
        assertThrows(ArchiveRefusedException.class, () -> store.moveWorkspace("ledger", repo))
            .getMessage()
            .contains("bench.local"));
    assertTrue(
        assertThrows(ArchiveRefusedException.class, () -> store.forget("ledger"))
            .getMessage()
            .contains("bench.local"));
  }

  /**
   * A machine with no path on it names a box and not a place, and the database refuses it rather
   * than the store remembering to.
   */
  @Test
  void the_database_refuses_a_machine_with_no_place_on_it() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO projects (name, machine) VALUES (?, ?)", "ledger", "bench.local"),
        "projects_machine_has_a_place is what stops a row looking rooted to every"
            + " query that tests `machine IS NOT NULL` while naming nowhere");
  }

  /**
   * The blank-machine rule V3 wrote for the workspace, held by the database for the same reason:
   * NULL and '' are different things and only the first is a meaning.
   */
  @Test
  void the_database_refuses_a_blank_machine_name() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO projects (name, machine, workspace) VALUES (?, ?, ?)",
                "ledger",
                "",
                "/home/example/ledger"));
  }

  /**
   * A rename is name-agnostic and says nothing about where the files are, so a project rooted
   * elsewhere moves exactly as any other does.
   */
  @Test
  void a_project_rooted_elsewhere_can_still_be_renamed() {
    store.rootOn("ledger", "bench.local", "/home/example/ledger");

    store.rename("ledger", "accounts");

    assertEquals("bench.local", store.rootedElsewhere("accounts").orElseThrow());
  }

  // --- moving a project -----------------------------------------------------

  /**
   * <b>The whole point of V14, asserted rather than assumed.</b>
   *
   * <p>A project's canonical name is {@code <MACHINE>/<PATH>/<PROJ_NAME>}, so moving a project to
   * another machine changes its name — and before the surrogate key that meant rewriting every
   * {@code memories} row and every {@code conversations} row of one project, in one transaction, or
   * leaving the project split across two names with half its history in each. It is now one row and
   * one column, and every reference is untouched.
   *
   * <p>The archive is read back <em>by the new name</em>, through the ordinary store methods,
   * because that is the claim: a memory written in {@code payments} answers a recall in {@code
   * bench.local/srv/payments/payments} without anything having touched the memory.
   */
  @Test
  void a_moved_project_carries_its_memories_and_its_conversations_with_it() {
    MemoryStore memories = new MemoryStore(jdbc);
    ConversationStore conversations = new ConversationStore(jdbc);
    store.define("payments", repo, List.of());
    memories.save(
        Memory.formed(
            "mem_one",
            "s",
            "sc",
            "b",
            new Provenance(Instant.EPOCH, "probe", "test"),
            Home.of("payments")));
    conversations.open(Home.of("payments"), Budget.of(10));

    store.rename("payments", "bench.local/srv/payments/payments");

    Home moved = Home.of("bench.local/srv/payments/payments");
    assertEquals(
        List.of("mem_one"),
        memories.loadAll(moved).stream().map(Memory::id).toList(),
        "the memory followed the project, because it never named it");
    assertEquals(1, conversations.inHome(moved).size(), "and so did the conversation");
    assertEquals(
        List.of(),
        memories.loadAll(Home.of("payments")),
        "and nothing was left behind under the old name");
  }

  /**
   * One row and one column means <b>one id</b>, and that is what makes the sentence above true
   * rather than a coincidence of two backfills.
   */
  @Test
  void a_moved_project_is_the_same_project_and_keeps_its_id() {
    store.define("payments", repo, List.of());
    Long before =
        jdbc.queryForObject("SELECT id FROM projects WHERE name = 'payments'", Long.class);

    store.rename("payments", "moved");

    assertEquals(
        before, jdbc.queryForObject("SELECT id FROM projects WHERE name = 'moved'", Long.class));
    assertEquals(1, store.all().size(), "a move is an update, not a second row");
  }

  /** A move changes the name and nothing else: the leash it had is the leash it has. */
  @Test
  void a_moved_project_keeps_its_workspace_and_its_exclusions() throws IOException {
    Path secrets = Files.createDirectory(repo.resolve("secrets"));
    store.define("payments", repo, List.of(secrets));

    store.rename("payments", "moved");

    ProjectRecord now = store.find("moved").orElseThrow();
    assertEquals(repo, now.workspace());
    assertEquals(List.of(secrets), now.exclusions());
    assertTrue(store.find("payments").isEmpty(), "and the old name answers for nothing");
  }

  /**
   * <b>The refusal that has to be a sentence rather than a constraint violation.</b>
   *
   * <p>{@code projects.name} is {@code UNIQUE NOT NULL} since V14, so the database refuses this
   * either way — and what it refuses with is {@code duplicate key value violates unique constraint
   * "projects_name_is_unique"}, which tells an operator nothing about what they were about to do.
   * Moving onto a name that is taken would put two projects' archives under one id if it succeeded;
   * it must say so.
   */
  @Test
  void moving_a_project_onto_a_name_that_is_taken_is_refused_and_names_both() {
    store.define("payments", repo, List.of());
    store.define("ledger", tmp, List.of());

    ArchiveRefusedException refused =
        assertThrows(ArchiveRefusedException.class, () -> store.rename("payments", "ledger"));

    assertTrue(
        refused.getMessage().contains("ledger"),
        "the name that is taken is named: " + refused.getMessage());
    assertTrue(
        refused.getMessage().contains("payments"),
        "and so is the project that did not move: " + refused.getMessage());
    assertEquals(repo, store.find("payments").orElseThrow().workspace(), "and nothing moved");
    assertEquals(
        tmp,
        store.find("ledger").orElseThrow().workspace(),
        "and the project already there is untouched");
  }

  /**
   * A project that has never been given a workspace still has an archive, and moving it is exactly
   * as ordinary — which is what {@code HAS_A_WORKSPACE} being absent from this statement means. The
   * three methods that carry that predicate answer about a <em>leash</em>; a move is about an
   * <em>identity</em>, and a project with no leash still has one.
   */
  @Test
  void a_project_that_has_only_been_written_into_can_still_be_moved() {
    MemoryStore memories = new MemoryStore(jdbc);
    memories.save(
        Memory.formed(
            "mem_ledger",
            "s",
            "sc",
            "b",
            new Provenance(Instant.EPOCH, "probe", "test"),
            Home.of("ledger")));

    store.rename("ledger", "moved");

    assertEquals(
        List.of("mem_ledger"),
        memories.loadAll(Home.of("moved")).stream().map(Memory::id).toList());
    assertTrue(
        store.find("moved").isEmpty(),
        "and it still has no workspace, because a move does not grant one");
  }

  /**
   * Silence would let a mistyped name read as a project successfully moved — {@code forget}'s
   * argument, and the answer is {@code forget}'s type: a name no row holds is <em>absent</em> and
   * never refused.
   */
  @Test
  void moving_a_project_that_does_not_exist_says_so() {
    ArchiveException e =
        assertThrows(ArchiveException.class, () -> store.rename("payments", "moved"));

    assertTrue(
        e.getMessage().contains("payments"),
        "the refusal names the project the caller asked about: " + e.getMessage());
    assertEquals(
        ArchiveException.class,
        e.getClass(),
        "a project that is not there is absent, never refused");
  }

  /**
   * The {@code WHERE} is the whole of the difference between moving one project and renaming the
   * table.
   */
  @Test
  void one_projects_move_is_not_another_projects() throws IOException {
    Path ledgerRepo = Files.createDirectory(tmp.resolve("ledger-checkout"));
    store.define("payments", repo, List.of());
    store.define("ledger", ledgerRepo, List.of());

    store.rename("payments", "moved");

    assertEquals(
        List.of("ledger", "moved"), store.all().stream().map(ProjectRecord::name).toList());
  }

  /**
   * Both names, on the way in, for {@code named}'s reason: no row can hold a padded name, so
   * answering anything but a refusal would be a lie.
   */
  @Test
  void a_padded_name_is_refused_on_both_ends_of_a_move() {
    store.define("payments", repo, List.of());

    assertThrows(ValidationException.class, () -> store.rename(" payments", "moved"));
    assertThrows(ValidationException.class, () -> store.rename("payments", "moved "));
    assertEquals(
        repo,
        store.find("payments").orElseThrow().workspace(),
        "and neither refusal wrote anything");
  }

  /**
   * Silence would let a mistyped name read as a workspace successfully removed — the one outcome an
   * operator would not think to check.
   */
  @Test
  void forgetting_a_project_that_has_no_workspace_says_so() {
    ArchiveException e = assertThrows(ArchiveException.class, () -> store.forget("payments"));
    assertTrue(
        e.getMessage().contains("payments"),
        "the refusal must name the project the caller asked about");
    // The exact class. A project with no workspace is ABSENT as far as this
    // method is concerned, and assertThrows takes ArchiveRefusedException
    // too -- so without this the narrowing would pass unnoticed. (This store
    // had no refusals at all until `rename` gained one for a name already
    // taken; see ArchiveRefusedException for the full classification.)
    assertEquals(
        ArchiveException.class,
        e.getClass(),
        "a project with no workspace is absent, never refused");
  }

  // --- validating the workspace ---------------------------------------------

  @Test
  void a_workspace_that_is_not_a_directory_is_refused_when_it_is_set() throws IOException {
    Path file = Files.writeString(tmp.resolve("notadir"), "x");
    ValidationException e =
        assertThrows(ValidationException.class, () -> store.define("payments", file, List.of()));
    assertTrue(
        e.getMessage().contains("not a directory"),
        "the refusal must name what is wrong, not just that something is");
    assertFalse(
        e.getMessage().contains("does not exist"),
        "a file that exists must not be described as absent");
  }

  @Test
  void a_workspace_that_does_not_exist_is_refused_when_it_is_set() {
    ValidationException e =
        assertThrows(
            ValidationException.class,
            () -> store.define("payments", tmp.resolve("nope"), List.of()));
    assertTrue(e.getMessage().contains("does not exist"));
    assertFalse(e.getMessage().contains("not a directory"));
  }

  @Test
  void a_refused_workspace_is_not_written() {
    assertThrows(
        ValidationException.class, () -> store.define("payments", tmp.resolve("nope"), List.of()));

    assertTrue(
        store.find("payments").isEmpty(),
        "validation runs before the write, so a refused define leaves no row");
  }

  /**
   * Measured, not assumed: {@code Files.isDirectory} follows a symlink, so an operator who links a
   * checkout into place has a workspace. Task 2's containment core is where that becomes
   * interesting — the stored path is the link, so a real-path step belongs there rather than here.
   */
  @Test
  void a_workspace_reached_through_a_symlink_is_accepted() throws IOException {
    Path link = Files.createSymbolicLink(tmp.resolve("link"), repo);

    ProjectRecord defined = store.define("payments", link, List.of());

    assertEquals(
        link,
        defined.workspace(),
        "the link is stored as the operator named it, not resolved through");
  }

  /**
   * The other half of the same measurement, and the reason the existence test is {@code
   * NOFOLLOW_LINKS}: a dangling symlink is sitting right there, so calling it absent would send an
   * operator looking for a path they can see.
   */
  @Test
  void a_workspace_that_is_a_dangling_symlink_is_refused_as_not_a_directory() throws IOException {
    Path link = Files.createSymbolicLink(tmp.resolve("dangling"), tmp.resolve("gone"));

    ValidationException e =
        assertThrows(ValidationException.class, () -> store.define("payments", link, List.of()));
    assertTrue(e.getMessage().contains("not a directory"));
    assertFalse(
        e.getMessage().contains("does not exist"), "the link exists; only its target does not");
  }

  @Test
  void a_workspace_is_required() {
    ValidationException e =
        assertThrows(ValidationException.class, () -> store.define("payments", null, List.of()));
    assertTrue(e.getMessage().contains("workspace"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void a_project_name_is_required(String name) {
    ValidationException e =
        assertThrows(ValidationException.class, () -> store.define(name, repo, List.of()));
    assertTrue(
        e.getMessage().contains("required"),
        "no name was given, which is a different mistake from a badly spelled one");
    assertFalse(
        e.getMessage().contains("whitespace"),
        "an all-space name is absent, not a name with something trimmable on the end");
  }

  /**
   * <b>Refused, not stripped</b>, and the reason is in another class: {@code Home.of} does not
   * strip (`Home.java:36-42`) and the three controllers hand it the raw request field, so {@code
   * Home.of(" payments ")} really is the project {@code " payments "} and its memories are stored
   * under that string. A store that stripped here would key workspaces by {@code "payments"} and
   * memories by {@code " payments "}, and a job in the padded tier would be handed the unpadded
   * project's workspace — the two leashes disagreeing, which is the one thing the spec's
   * non-escalation argument says cannot happen. Refusing makes the pair unbuildable.
   */
  @ParameterizedTest
  @ValueSource(strings = {"  payments", "payments  ", "\tpayments", "payments\n"})
  void a_project_name_with_whitespace_at_one_end_is_refused_rather_than_stripped(String padded) {
    // Each end on its own, not one fixture padded at both: a guard written
    // as stripLeading alone still refuses "  payments  ", so a both-ends
    // fixture cannot tell the two halves of this rule apart.
    ValidationException e =
        assertThrows(ValidationException.class, () -> store.define(padded, repo, List.of()));

    assertTrue(
        e.getMessage().contains("whitespace"),
        "the refusal must name what is wrong with the name that was given");
    assertFalse(
        e.getMessage().contains("required"),
        "a name was given; it is spelled in a way that would key two projects");
    assertTrue(store.all().isEmpty(), "nothing was written under either spelling");
  }

  /**
   * Internal spaces are not the problem — {@code Home} keeps them, so the two leashes agree about
   * them.
   */
  @Test
  void a_project_name_with_a_space_inside_it_is_an_ordinary_name() {
    store.define("payments api", repo, List.of());

    assertEquals(repo, store.find("payments api").orElseThrow().workspace());
  }

  /**
   * Every way in, not just {@code define}. A lookup that quietly answered "empty" for a padded name
   * would be the confident-empty-answer failure again: no row can ever hold such a name, so the
   * only honest reply is that the name itself is the mistake.
   */
  @Test
  void a_padded_name_is_refused_on_every_way_in_and_not_answered_empty() {
    store.define("payments", repo, List.of());

    assertThrows(ValidationException.class, () -> store.find("  payments "));
    assertThrows(ValidationException.class, () -> store.forget("payments  "));
    assertThrows(ValidationException.class, () -> store.effectiveExclusions(" payments"));
  }

  /** The store validates first; this is what stops anything that bypasses it. */
  @Test
  void the_table_refuses_a_blank_name_or_a_blank_workspace() {
    DataIntegrityViolationException unnamed =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO projects (name, workspace) VALUES (?, ?)", "", repo.toString()));
    assertTrue(
        unnamed.getMessage().contains("projects_name_named"),
        "the blank name is what the row was refused for");

    DataIntegrityViolationException homeless =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO projects (name, workspace) VALUES (?, ?)", "payments", ""));
    assertTrue(
        homeless.getMessage().contains("projects_workspace_named"),
        "the blank workspace is what the row was refused for");
  }

  /**
   * A relative workspace is pinned to an absolute one when it is defined, so the leash cannot move
   * later because the process was started elsewhere.
   */
  @Test
  void a_workspace_is_stored_absolute_and_normalised() {
    Path roundabout = repo.resolve("..").resolve("repo");

    ProjectRecord defined = store.define("payments", roundabout, List.of());

    assertEquals(repo, defined.workspace());
    assertEquals(repo, store.find("payments").orElseThrow().workspace());
  }

  // --- the project's own exclusions -----------------------------------------

  /**
   * The exclusion half of {@link #a_workspace_is_stored_absolute_and_normalised}, and the asymmetry
   * it closes was a live survivor.
   *
   * <p>Every other exclusion fixture in this file is built from {@code @TempDir}, which hands out
   * an absolute path — so dropping {@code absolute()} from the exclusion loop left every test in
   * this file and in {@code ArchiveUnavailableTest} green. What that hides is silent rather than
   * loud: a relative exclusion is stored verbatim, matches no absolute candidate task 2 compares it
   * against, and so fences off nothing while reporting nothing.
   */
  @Test
  void an_exclusion_is_stored_absolute_and_normalised() {
    Path relative = Path.of("secrets");
    Path roundabout = repo.resolve("build").resolve("..").resolve("vendor");

    store.define("payments", repo, List.of(relative, roundabout));

    assertEquals(
        List.of(relative.toAbsolutePath().normalize(), repo.resolve("vendor")),
        store.find("payments").orElseThrow().exclusions());
  }

  /**
   * The {@code TEXT[]} round trip, measured on the characters that mean something inside a Postgres
   * array literal. A hand-built {@code '{...}'} would have to escape all four, and this is the test
   * that says the driver is doing it instead.
   */
  @Test
  void an_exclusion_list_round_trips_through_the_text_array_column() {
    List<Path> awkward =
        List.of(
            repo.resolve("a,comma"),
            repo.resolve("{braced}"),
            repo.resolve("a \"quoted\" name"),
            repo.resolve("back\\slash"));

    store.define("payments", repo, awkward);

    assertEquals(awkward, store.find("payments").orElseThrow().exclusions());
  }

  @Test
  void an_empty_exclusion_list_comes_back_empty() {
    store.define("payments", repo, List.of());

    assertEquals(List.of(), store.find("payments").orElseThrow().exclusions());
  }

  /**
   * An exclusion naming a path that is not there yet fences it off from the moment it appears.
   * Refusing one would mean an operator can only fence off what already exists, which is backwards
   * for the case that matters.
   */
  @Test
  void an_exclusion_need_not_exist() {
    Path future = repo.resolve("generated").resolve("credentials.json");

    store.define("payments", repo, List.of(future));

    assertEquals(List.of(future), store.find("payments").orElseThrow().exclusions());
  }

  /**
   * The empty path is not "no exclusion" — measured on JDK 21, {@code
   * Path.of("").toAbsolutePath().normalize()} is the process's working directory exactly, so
   * storing one would fence off the server's whole checkout. It fails closed, which is why this is
   * a small hole rather than a hole, but it is the one non-blank rule V3 did not carry that V1 and
   * V2 do.
   */
  @Test
  void an_exclusion_may_not_be_the_empty_path() {
    ValidationException e =
        assertThrows(
            ValidationException.class, () -> store.define("payments", repo, List.of(Path.of(""))));
    assertTrue(e.getMessage().contains("empty path"));

    // Path.of("  ") is a directory named two spaces, not a blank path, and
    // is none of this rule's business.
    store.define("payments", repo, List.of(Path.of("  ")));
    assertEquals(
        List.of(Path.of("  ").toAbsolutePath().normalize()),
        store.find("payments").orElseThrow().exclusions());
  }

  /** The store refuses it first; this stops anything that bypasses the store. */
  @Test
  void the_table_refuses_a_blank_exclusion() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO projects (name, workspace, exclusions)"
                        // CAST, for the reason ProposalStore's HOME_MATCHES gives about
                        // its own: with a bare `?` the driver sends this array literal
                        // as untyped text and Postgres refuses the statement outright —
                        // measured here as a BadSqlGrammarException, which is this test
                        // being wrong rather than the constraint firing.
                        + " VALUES (?, ?, CAST(? AS TEXT[]))",
                    "payments",
                    repo.toString(),
                    "{\"\"}"));
    assertTrue(
        refused.getMessage().contains("projects_exclusions_named"),
        "the blank exclusion is what the row was refused for");
  }

  @Test
  void an_exclusion_list_is_required_and_may_not_hold_a_null() {
    ValidationException missing =
        assertThrows(ValidationException.class, () -> store.define("payments", repo, null));
    assertTrue(missing.getMessage().contains("exclusions"));

    List<Path> withNull = new ArrayList<>(Arrays.asList(repo.resolve("a"), null));
    ValidationException holed =
        assertThrows(ValidationException.class, () -> store.define("payments", repo, withNull));
    assertTrue(holed.getMessage().contains("exclusions"));
  }

  /**
   * The record copies, and this is asserted against the record directly.
   *
   * <p>Going through {@link ProjectStore#define} would not test it: {@code define} builds its own
   * list while normalising, so the end-to-end version of this passes with the record's copy
   * removed. Two copies, either one enough — which is exactly the shape that leaves a mutant alive.
   */
  @Test
  void a_record_does_not_change_when_the_list_it_was_built_from_does() {
    List<Path> mutable = new ArrayList<>(List.of(repo.resolve("secrets")));

    ProjectRecord record = new ProjectRecord("payments", repo, List.of(), mutable);
    mutable.add(repo.resolve("everything-else"));

    assertEquals(
        List.of(repo.resolve("secrets")),
        record.exclusions(),
        "a mutable exclusions list would be a leash its holder could lengthen");
  }

  // --- the ones nobody may override -----------------------------------------

  /** The plan's own test: whatever a row says, the server's configuration is excluded. */
  @Test
  void a_row_cannot_grant_itself_the_servers_configuration() {
    store.define("sneaky", repo, List.of());

    List<Path> effective = store.effectiveExclusions("sneaky");

    assertTrue(
        effective.contains(configFile.toAbsolutePath().normalize()),
        "the server's own config is excluded from every workspace");
  }

  /**
   * The sharper version, and the one that says <em>why</em> the two are not stored in the row: a
   * workspace can be set to the directory the configuration lives in, and the exclusion still
   * stands.
   *
   * <p>This is what "not overridable by a row" has to mean. A row has no field that could remove
   * them, so the only way to reach the config through a project is to point a workspace at it —
   * which is what this does.
   */
  @Test
  void a_workspace_that_contains_the_config_still_cannot_reach_it() {
    store.define("sneaky", tmp, List.of(tmp.resolve("something-else")));

    List<Path> effective = store.effectiveExclusions("sneaky");

    assertEquals(
        tmp,
        store.find("sneaky").orElseThrow().workspace(),
        "the workspace really is the directory the config is in");
    assertTrue(
        effective.contains(configFile.toAbsolutePath().normalize()),
        "the config is excluded even when the workspace is its own directory");
  }

  /**
   * A row cannot grant itself this server's operator token either — and the refusal has to come
   * from this list, because the fixture's token file is not hidden.
   *
   * <p>{@code FileAccess.permits} refuses every path with a dot-prefixed component below its root,
   * so the shipped {@code ~/.config/plowshare/console-token} is unreachable on the strength of
   * where it sits. {@code plowshare.auth.token-file} is configurable and an operator who moves it
   * somewhere ordinary loses that with no warning, which is the whole of why this entry exists as
   * well. <b>The fixture is that deployment: its token file is {@code <tmp>/console-token}, where
   * no predicate helps.</b>
   */
  @Test
  void a_row_cannot_grant_itself_the_operator_token_wherever_it_is_configured() {
    store.define("sneaky", tmp, List.of());

    assertTrue(
        store.effectiveExclusions("sneaky").contains(tokenFile.toAbsolutePath().normalize()),
        "a complete and permanent credential for every gated route on this server");
  }

  /**
   * Nor this server's ejected conversation payloads, wherever the operator put them.
   *
   * <p><b>The same shape as the token file one line up, one kind of secret along.</b> The export
   * directory was fenced before this entry existed — but only because its shipped default, {@code
   * exports}, is relative and lands under the working directory, which is already excluded. An
   * operator who sets {@code PLOWSHARE_EXPORT_DIR} to an absolute path, which is the ordinary thing
   * to do when payloads outgrow the server's disk, moved it outside every entry in the list. <b>The
   * fixture is that deployment:</b> {@code exportDir} is a directory of its own that nothing else
   * covers, so this assertion fails the moment the entry is taken out.
   */
  @Test
  void a_row_cannot_grant_itself_the_ejected_payloads_wherever_they_are_written() {
    store.define("sneaky", tmp, List.of());

    assertTrue(
        store.effectiveExclusions("sneaky").contains(exportDir.toAbsolutePath().normalize()),
        "an ejected payload is the file body a tool returned, written in the"
            + " open so it survives this server -- which is not the same as"
            + " being readable through it");
  }

  /**
   * Nor the tree this server owns, which is where all of that ends up.
   *
   * <p><b>Named on its own account and not only through what is inside it.</b> On a real deployment
   * the exports live under this root and the collapse drops their entry — so the fixture keeps the
   * two disjoint, and this assertion is about the root rather than about anything it happens to
   * contain today. That is the point of naming a root: {@code projects/&lt;id&gt;/images/} is
   * reserved and not built, and a rule stated over the leaves would have to be restated the day it
   * is.
   */
  @Test
  void a_row_cannot_grant_itself_the_tree_this_server_owns() {
    store.define("sneaky", tmp, List.of());

    assertTrue(
        store.effectiveExclusions("sneaky").contains(dataDir.toAbsolutePath().normalize()),
        "the data directory is where every server-owned file about a project"
            + " goes, and naming the root is what keeps the rule true when a"
            + " subdirectory is added");
  }

  /**
   * No project workspace may reach the definitions directories either, though nothing here names
   * them specifically.
   *
   * <p>Spec §6: the data root is excluded by root and not by contents, so this holds the day {@code
   * bots/} exists and needs no second thing kept in step. Asserted for definitions specifically
   * because the guarantee is now load-bearing for them and not only for exports — task 10 retired
   * the standalone directory key this deployment used to fence definitions with on exactly this
   * argument, and this is the test that measures the argument held rather than merely assuming it.
   *
   * <p><b>This test must pass with no production change.</b> That is the point: {@link
   * ProjectStore#mandatoryExclusions} already names the data directory's root, so a subdirectory
   * {@code global/agents/} or {@code global/bots/} inside it is covered before it is ever created —
   * nothing about the fence has to know the definitions tree's own shape. The directory is created
   * here only so the path this asserts against is the real one {@code DataLayout.botsFor(null)}
   * would resolve to, not so the fence needs it to exist.
   */
  @Test
  void no_workspace_may_reach_the_definitions_directories() throws IOException {
    Path bots = dataDir.resolve("global").resolve("bots");
    Files.createDirectories(bots);

    List<Path> excluded =
        ProjectStore.mandatoryExclusions(configFile, samplingDir, tokenFile, exportDir, dataDir);

    assertTrue(
        excluded.stream()
            .anyMatch(mandatory -> bots.toAbsolutePath().normalize().startsWith(mandatory)),
        "global/bots was not covered by " + excluded);
  }

  /** The project's own exclusions are not lost to the mandatory ones. */
  @Test
  void a_projects_own_exclusions_are_kept_alongside_the_mandatory_ones() {
    Path secrets = repo.resolve("secrets");
    store.define("payments", repo, List.of(secrets));

    List<Path> effective = store.effectiveExclusions("payments");

    // The whole list, not a contains-and-size pair: order is part of the
    // answer — the mandatory ones first, so an operator reading a refusal
    // meets the rule before the project's own preferences — and a contains
    // assertion leaves that ordering an accident a HashSet would satisfy
    // just as well.
    assertEquals(
        List.of(
            SERVER_DIRECTORY,
            configFile.toAbsolutePath().normalize(),
            samplingDir.toAbsolutePath().normalize(),
            tokenFile.toAbsolutePath().normalize(),
            exportDir.toAbsolutePath().normalize(),
            dataDir.toAbsolutePath().normalize(),
            secrets),
        effective,
        "the mandatory ones first, then the row's own");
  }

  /**
   * The overload a caller uses when it has already read the row.
   *
   * <p>It exists so that a caller needing the workspace <em>and</em> the exclusions reads once: two
   * calls leave a window a {@link ProjectStore#forget} can land in, where the second raises for a
   * project the first had just answered for. {@code LocalProvider} is that caller.
   *
   * <p>Two projects with different exclusions, so an overload that ignored its argument and
   * answered for whichever row came back first would be caught — one row cannot tell a used
   * argument from an unused one.
   */
  @Test
  void the_same_rule_applies_to_a_row_already_read_as_to_one_looked_up_by_name() {
    Path minePrivate = repo.resolve("mine");
    Path yoursPrivate = tmp.resolve("yours");
    store.define("payments", repo, List.of(minePrivate));
    store.define("ledger", tmp, List.of(yoursPrivate));

    ProjectRecord payments = store.find("payments").orElseThrow();

    assertEquals(
        store.effectiveExclusions("payments"),
        store.effectiveExclusions(payments),
        "one read and two reads must answer the same thing, or the overload"
            + " is a second copy of the rule rather than the same one");
    assertEquals(
        List.of(
            SERVER_DIRECTORY,
            configFile.toAbsolutePath().normalize(),
            samplingDir.toAbsolutePath().normalize(),
            tokenFile.toAbsolutePath().normalize(),
            exportDir.toAbsolutePath().normalize(),
            dataDir.toAbsolutePath().normalize(),
            minePrivate),
        store.effectiveExclusions(payments),
        "the mandatory ones are applied to the row that was handed in, and it"
            + " is that row's own exclusion that follows it");
  }

  /**
   * A row is free to list a mandatory exclusion too, and the answer must not then carry it twice.
   */
  @Test
  void a_row_that_repeats_a_mandatory_exclusion_does_not_double_it() {
    store.define("payments", repo, List.of(configFile));

    List<Path> effective = store.effectiveExclusions("payments");

    assertEquals(
        6,
        effective.size(),
        "the server directory, the config, the sampling directory, the operator"
            + " token file, the export directory and the data directory, once"
            + " each");
    assertTrue(effective.contains(configFile.toAbsolutePath().normalize()));
  }

  /**
   * The mandatory exclusions are a real containment set, so answering with them for a project that
   * does not exist would let a typo look like a working answer.
   */
  @Test
  void the_exclusions_of_a_project_with_no_workspace_are_refused_rather_than_guessed() {
    ArchiveException e =
        assertThrows(ArchiveException.class, () -> store.effectiveExclusions("payments"));
    assertTrue(e.getMessage().contains("payments"));
  }

  /**
   * <b>The row does not carry the mandatory ones, and that is the mechanism.</b> They are computed
   * on every read instead, so changing where the server's configuration lives does not leave old
   * rows pointing at the old place — and no row can be edited to drop them, because no row holds
   * them.
   */
  @Test
  void the_mandatory_exclusions_are_not_stored_in_the_row() {
    store.define("payments", repo, List.of());

    List<String> stored =
        jdbc.queryForList(
            "SELECT unnest(exclusions) FROM projects WHERE name = ?", String.class, "payments");

    assertEquals(List.of(), stored, "a stored default is a default a row can be edited out of");
  }

  /**
   * The rule as one expression, with no store and no database behind it. The server directory
   * leads, because a refusal should name the location before it names the files inside it.
   */
  @Test
  void the_mandatory_exclusions_are_the_server_directory_and_this_deployments_own_five() {
    assertEquals(
        List.of(
            SERVER_DIRECTORY,
            configFile.toAbsolutePath().normalize(),
            samplingDir.toAbsolutePath().normalize(),
            tokenFile.toAbsolutePath().normalize(),
            exportDir.toAbsolutePath().normalize(),
            dataDir.toAbsolutePath().normalize()),
        ProjectStore.mandatoryExclusions(configFile, samplingDir, tokenFile, exportDir, dataDir));
  }

  /**
   * And a deployment that keeps no export fences off no path for one.
   *
   * <p>Null is the blank {@code plowshare.conversations.retention.export-directory}, which {@code
   * ArchiveConfig.payloadExport} reads as {@code PayloadExport.NONE} — the operator who wants the
   * liability gone rather than moved. Substituting the shipped {@code exports} default here would
   * name a directory this deployment never writes to, in the list an operator reads, and would say
   * nothing true about anything.
   */
  @Test
  void a_deployment_that_keeps_no_export_names_no_path_for_one() {
    assertEquals(
        List.of(
            SERVER_DIRECTORY,
            configFile.toAbsolutePath().normalize(),
            samplingDir.toAbsolutePath().normalize(),
            tokenFile.toAbsolutePath().normalize(),
            dataDir.toAbsolutePath().normalize()),
        ProjectStore.mandatoryExclusions(configFile, samplingDir, tokenFile, null, dataDir));
  }

  /**
   * And a deployment that keeps no data directory at all fences off no path for one either.
   *
   * <p>Null here is the blank {@code plowshare.data.dir}, which is the state of every Spring
   * context in this repository that did not come through {@code PlowshareServerApplication.main} —
   * {@code DataProperties} argues why the blank has to be the default rather than a value in {@code
   * application.yml}. Substituting the shipped {@code data} would name a directory such a
   * deployment never creates.
   */
  @Test
  void a_deployment_that_owns_no_tree_names_no_path_for_one() {
    assertEquals(
        List.of(
            SERVER_DIRECTORY,
            configFile.toAbsolutePath().normalize(),
            samplingDir.toAbsolutePath().normalize(),
            tokenFile.toAbsolutePath().normalize(),
            exportDir.toAbsolutePath().normalize()),
        ProjectStore.mandatoryExclusions(configFile, samplingDir, tokenFile, exportDir, null));
  }

  /**
   * And a deployment that writes no operator token fences off no path for one.
   *
   * <p>Null is the blank {@code plowshare.auth.token-file}, which is every context that did not
   * come through {@code PlowshareServerApplication.main} — {@code AuthConfig.announce} returns
   * before minting anything, so there is no file to name. The alternative, substituting {@code
   * AuthConfig.defaultTokenFile()}, would put the running operator's real credential path into the
   * exclusions of every test context in this repository and into the list an operator is shown by a
   * deployment that never writes there.
   */
  @Test
  void a_deployment_that_writes_no_operator_token_names_no_path_for_one() {
    assertEquals(
        List.of(
            SERVER_DIRECTORY,
            configFile.toAbsolutePath().normalize(),
            samplingDir.toAbsolutePath().normalize(),
            exportDir.toAbsolutePath().normalize(),
            dataDir.toAbsolutePath().normalize()),
        ProjectStore.mandatoryExclusions(configFile, samplingDir, null, exportDir, dataDir));
  }

  /**
   * A relative {@code plowshare.llm.sampling-directory} is the shipped default, so the mandatory
   * exclusions have to be absolute before anything compares a path against it.
   */
  @Test
  void the_mandatory_exclusions_are_absolute_even_when_the_server_was_configured_relatively() {
    List<Path> mandatory =
        ProjectStore.mandatoryExclusions(
            Path.of("conf/plowshare.yml"),
            Path.of("sampling"),
            Path.of("conf/console-token"),
            Path.of("exports"),
            Path.of("data"));

    assertTrue(
        mandatory.stream().allMatch(Path::isAbsolute),
        "a relative exclusion would match nothing an absolute candidate is compared to");
    // One path, not five: a relative config, a relative sampling directory
    // and the relative `exports` and `data` the server ships all resolve
    // INSIDE the working directory, so the collapse leaves the directory
    // that holds them. That is the shipped arrangement, and it is exactly
    // why the export directory could be missing from this list for as long
    // as it was: at the default it changes no answer.
    assertEquals(List.of(SERVER_DIRECTORY), mandatory);
  }

  // --- helpers --------------------------------------------------------------

  /**
   * A project's surrogate key, which is what "the same project" means since V14: the memories and
   * conversations are filed against this and not against the name, the workspace or the machine.
   */
  private long id(String name) {
    return Objects.requireNonNull(
        jdbc.queryForObject("SELECT id FROM projects WHERE name = ?", Long.class, name));
  }

  /**
   * The place as the row holds it, which is the string an identity is composed from. Read out of
   * the column and never off the record, because the column is what a presence and {@code
   * projects_machine_has_a_place} read and a record can be right while the row has moved.
   */
  private String workspaceOf(String name) {
    return Objects.requireNonNull(
        jdbc.queryForObject("SELECT workspace FROM projects WHERE name = ?", String.class, name));
  }

  private OffsetDateTime definedAt(String name) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT defined_at FROM projects WHERE name = ?", OffsetDateTime.class, name));
  }
}
