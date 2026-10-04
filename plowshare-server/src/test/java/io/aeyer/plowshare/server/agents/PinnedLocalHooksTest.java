package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.LocalHookSetStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.hooks.HookFile;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.Tier;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Spec 2026-09-30-local-hooks-are-served decisions 3, 4 and 7, over a real Postgres. */
@Testcontainers
class PinnedLocalHooksTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Home LEDGER = Home.of("ledger");
  private static final String SESSION = "tui-1";

  /** The account {@link #SESSION} is signed in as, and every log here is owned by. */
  private static final String OWNER = "enzo";

  private static final String DIR = ".plowshare/hooks";
  private static final String GUARD = "export default { name: 'guard', stages: {} }";

  /** A channel that must not be asked: an inherited or sessionless log reads nothing. */
  private static final SessionChannel UNASKED =
      (session, request) -> {
        throw new AssertionError("the session was asked for " + request.op());
      };

  private static JdbcTemplate jdbc;

  private ConversationStore conversations;
  private LocalHookSetStore sets;
  private final AtomicReference<SessionChannel> serving = new AtomicReference<>();
  private PinnedLocalHooks pins;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void fresh() {
    jdbc.execute("TRUNCATE TABLE entries, turns, conversations, local_hook_sets CASCADE");
    conversations = new ConversationStore(jdbc);
    sets = new LocalHookSetStore(jdbc);
    serving.set(laptop(GUARD));
    pins =
        new PinnedLocalHooks(
            session -> new ChannelHooks(serving.get(), session).read(),
            SESSION::equals,
            (project, session) -> "ledger".equals(project) && SESSION.equals(session),
            PinnedLocalHooks.Accounts.all(PinnedLocalHooksTest::accountOf),
            conversations,
            sets);
  }

  /** {@link #SESSION} is signed in as {@link #OWNER}; any other session as nobody. */
  private static Optional<String> accountOf(String session) {
    return SESSION.equals(session) ? Optional.of(OWNER) : Optional.empty();
  }

  private static FakeFiles laptop(String guard) {
    return new FakeFiles()
        .withListing(DIR, List.of("10-guard.ts"))
        .withFile(DIR + "/10-guard.ts", guard);
  }

  private String turnLog() {
    return conversations.open(LEDGER, Budget.of(5), null, OWNER).id();
  }

  private static LogStages.LogOpened opened(String log, String session) {
    return new LogStages.LogOpened(log, Origin.TURN, LEDGER, null, false, null, session, null);
  }

  @Test
  void the_snapshot_is_taken_at_open_and_survives_the_session_closing_and_the_files_changing() {
    String first = turnLog();

    assertEquals(List.of(), pins.pin(opened(first, SESSION)));
    String hash = conversations.localHooksOf(first).orElseThrow();
    serving.set(laptop("export default { name: 'edited', stages: {} }"));
    String second = turnLog();
    pins.pin(opened(second, SESSION));
    serving.set(new FakeFiles().thatIsClosed());

    assertEquals(
        Optional.of(hash), conversations.localHooksOf(first), "the first log keeps its set");
    assertEquals(Optional.of(List.of(new HookFile("10-guard.ts", GUARD))), sets.find(hash));
    assertNotEquals(
        Optional.of(hash),
        conversations.localHooksOf(second),
        "an edit takes effect at the next log");
  }

  @Test
  void two_logs_with_the_same_files_share_one_row() {
    String one = turnLog();
    String two = turnLog();

    pins.pin(opened(one, SESSION));
    pins.pin(opened(two, SESSION));

    assertEquals(conversations.localHooksOf(one), conversations.localHooksOf(two));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM local_hook_sets", Integer.class));
  }

  @Test
  void a_delegated_child_inherits_its_parent_s_hash_without_asking_the_session() {
    String parent =
        conversations.log(Origin.SUBMISSION, LEDGER, "scribe", null, Budget.of(5), OWNER).id();
    pins.pin(
        new LogStages.LogOpened(
            parent, Origin.SUBMISSION, LEDGER, "scribe", false, null, SESSION, null));
    String child = conversations.log(Origin.DELEGATION, LEDGER, "scribe", parent, null).id();
    serving.set(UNASKED);

    pins.pin(
        new LogStages.LogOpened(
            child, Origin.DELEGATION, LEDGER, "scribe", false, parent, null, parent));

    assertEquals(conversations.localHooksOf(parent), conversations.localHooksOf(child));
    assertTrue(conversations.localHooksOf(child).isPresent());
  }

  @Test
  void a_nested_orchestration_child_inherits_its_parent_conductor_s_hash() {
    String parent =
        conversations
            .log(Origin.ORCHESTRATION, LEDGER, "conductor", null, Budget.of(5), OWNER)
            .id();
    pins.pin(
        new LogStages.LogOpened(
            parent, Origin.ORCHESTRATION, LEDGER, "conductor", false, null, SESSION, null));
    String child =
        conversations
            .log(Origin.ORCHESTRATION, LEDGER, "conductor", null, Budget.of(5), OWNER)
            .id();
    serving.set(UNASKED);

    pins.pin(
        new LogStages.LogOpened(
            child, Origin.ORCHESTRATION, LEDGER, "conductor", false, null, null, parent));

    assertTrue(conversations.localHooksOf(parent).isPresent(), "the parent was pinned");
    assertEquals(conversations.localHooksOf(parent), conversations.localHooksOf(child));
  }

  /** Pins over {@link #SESSION} whose liveness and rooting are the test's to say. */
  private PinnedLocalHooks pinsWhere(
      java.util.function.Predicate<String> live,
      java.util.function.BiPredicate<String, String> roots) {
    return new PinnedLocalHooks(
        session -> new ChannelHooks(serving.get(), session).read(),
        live,
        roots,
        PinnedLocalHooks.Accounts.all(PinnedLocalHooksTest::accountOf),
        conversations,
        sets);
  }

  /**
   * Final review M6: a failure nobody expected is said at log.open escaped and clipped, as every
   * other reason that may carry a session's text is; a message is not trusted for being ours.
   */
  @Test
  void an_unexpected_failure_is_said_escaped_and_clipped() {
    String log = turnLog();
    String hostile = "\u001b[31m" + "x".repeat(10_000);
    PinnedLocalHooks throwing =
        new PinnedLocalHooks(
            session -> {
              throw new IllegalStateException(hostile);
            },
            SESSION::equals,
            (project, session) -> true,
            PinnedLocalHooks.Accounts.all(PinnedLocalHooksTest::accountOf),
            conversations,
            sets);

    List<HookRecord> said = throwing.pin(opened(log, SESSION));

    assertEquals(1, said.size());
    String reason = said.get(0).reason();
    assertTrue(
        reason.startsWith(
            "this log's local hooks could not be snapshotted, so it has"
                + " none: IllegalStateException: \\u001b[31m"),
        reason);
    assertTrue(reason.length() < 400, "clipped: " + reason.length());
    assertEquals(
        said,
        List.of(LocalHooks.unsnapshotted(new IllegalStateException(hostile))),
        "the one wording both catch-alls use");
  }

  /** Final review M10: the global tier is refused by its own guard, whatever roots says. */
  @Test
  void the_global_tier_has_none_even_where_every_project_is_rooted() {
    serving.set(UNASKED);
    String global = conversations.open(Home.global(), Budget.of(5), null, OWNER).id();

    List<HookRecord> said =
        pinsWhere(SESSION::equals, (project, session) -> true)
            .pin(
                new LogStages.LogOpened(
                    global, Origin.TURN, Home.global(), null, false, null, SESSION, null));

    assertEquals(List.of(), said);
    assertEquals(Optional.empty(), conversations.localHooksOf(global));
  }

  /** Final review M10: a session with no file channel now is not read, even if it roots. */
  @Test
  void a_session_that_is_not_live_has_none_even_where_it_roots_the_project() {
    serving.set(UNASKED);
    String log = turnLog();

    List<HookRecord> said =
        pinsWhere(session -> false, (project, session) -> true).pin(opened(log, SESSION));

    assertEquals(List.of(), said);
    assertEquals(Optional.empty(), conversations.localHooksOf(log));
  }

  @Test
  void a_session_that_goes_while_it_is_read_gives_no_tier_and_says_why_at_log_open() {
    String log = turnLog();
    serving.set(new FakeFiles().thatIsClosed());

    List<HookRecord> said = pins.pin(opened(log, SESSION));

    assertEquals(Optional.empty(), conversations.localHooksOf(log));
    assertEquals(1, said.size());
    HookRecord record = said.get(0);
    assertEquals(Tier.LOCAL, record.tier());
    assertEquals(Stage.LOG_OPEN, record.stage());
    assertEquals(HookRecord.FAILED, record.decision());
    assertEquals(HookFile.WHOLE_SET, record.hook());
    assertTrue(record.reason().contains("not connected"), record.reason());
  }

  @Test
  void a_set_over_a_bound_is_not_snapshotted_at_all() {
    List<String> names = IntStream.rangeClosed(1, 33).mapToObj(i -> i + ".ts").toList();
    FakeFiles many = new FakeFiles().withListing(DIR, names);
    names.forEach(name -> many.withFile(DIR + "/" + name, GUARD));
    serving.set(many);
    String log = turnLog();

    List<HookRecord> said = pins.pin(opened(log, SESSION));

    assertEquals(Optional.empty(), conversations.localHooksOf(log));
    assertTrue(said.get(0).reason().contains("32"), said.get(0).reason());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM local_hook_sets", Integer.class));
  }

  @Test
  void no_session_an_unrooted_one_a_dead_one_or_the_global_tier_has_none_and_nothing_is_said() {
    serving.set(UNASKED);
    String log = turnLog();
    String global = conversations.open(Home.global(), Budget.of(5)).id();

    assertEquals(List.of(), pins.pin(opened(log, null)));
    assertEquals(List.of(), pins.pin(opened(log, "tui-2")), "not live, so not rooted");
    assertEquals(
        List.of(),
        pins.pin(
            new LogStages.LogOpened(
                global, Origin.TURN, Home.global(), null, false, null, SESSION, null)));
    assertEquals(Optional.empty(), conversations.localHooksOf(log));
  }

  @Test
  void a_live_session_that_does_not_root_the_log_s_project_has_none_and_nothing_is_said() {
    serving.set(UNASKED);
    Home elsewhere = Home.of("elsewhere");
    String log = conversations.open(elsewhere, Budget.of(5)).id();

    assertEquals(
        List.of(),
        pins.pin(
            new LogStages.LogOpened(
                log, Origin.TURN, elsewhere, null, false, null, SESSION, null)));
    assertEquals(Optional.empty(), conversations.localHooksOf(log));
  }

  /**
   * A caller may name any live session, so a live, rooting session signed in as another account
   * lends this log nothing, and nothing is said, as for an unrooted one. A log nobody owns is lent
   * nothing either.
   */
  @Test
  void
      a_live_rooting_session_of_another_account_or_a_log_nobody_owns_has_none_and_nothing_is_said() {
    serving.set(UNASKED);
    PinnedLocalHooks theirs =
        new PinnedLocalHooks(
            session -> new ChannelHooks(serving.get(), session).read(),
            SESSION::equals,
            (project, session) -> true,
            PinnedLocalHooks.Accounts.all(session -> Optional.of("mallory")),
            conversations,
            sets);
    String mine = turnLog();
    String nobodys = conversations.open(LEDGER, Budget.of(5)).id();

    assertEquals(List.of(), theirs.pin(opened(mine, SESSION)));
    assertEquals(List.of(), pins.pin(opened(nobodys, SESSION)));
    assertEquals(Optional.empty(), conversations.localHooksOf(mine));
    assertEquals(Optional.empty(), conversations.localHooksOf(nobodys));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM local_hook_sets", Integer.class));
  }

  /** Pins over {@link #serving} with the three accounts given, rooting everything. */
  private PinnedLocalHooks pinsAs(
      Function<String, Optional<String>> bound,
      Function<String, Optional<String>> listener,
      Function<String, Optional<String>> provider) {
    return new PinnedLocalHooks(
        session -> new ChannelHooks(serving.get(), session).read(),
        SESSION::equals,
        (project, session) -> true,
        new PinnedLocalHooks.Accounts(bound, listener, provider),
        conversations,
        sets);
  }

  /**
   * The hooks are read over the file channel, so its account is held to the owner too, as is the
   * account the session is held by: a session whose listener is the owner's but whose file channel
   * another account opened lends nothing, nor one with no file-channel account, nor one held by
   * another account (Enzo's decision of 2026-09-30).
   */
  @Test
  void a_session_whose_file_channel_or_holder_is_another_account_s_has_none_and_nothing_is_said() {
    serving.set(UNASKED);
    Function<String, Optional<String>> owner = PinnedLocalHooksTest::accountOf;
    String log = turnLog();

    assertEquals(
        List.of(),
        pinsAs(owner, owner, session -> Optional.of("mallory")).pin(opened(log, SESSION)));
    assertEquals(
        List.of(), pinsAs(owner, owner, session -> Optional.empty()).pin(opened(log, SESSION)));
    assertEquals(
        List.of(),
        pinsAs(session -> Optional.of("mallory"), owner, owner).pin(opened(log, SESSION)));
    assertEquals(Optional.empty(), conversations.localHooksOf(log));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM local_hook_sets", Integer.class));
  }

  /**
   * The read can take its whole deadline, and the file channel can drop and be taken meanwhile:
   * what came back is pinned only if the session and its file channel are still the owner's.
   */
  @Test
  void a_file_channel_that_changes_account_during_the_read_pins_nothing_and_says_nothing() {
    AtomicReference<String> provider = new AtomicReference<>(OWNER);
    PinnedLocalHooks racing =
        new PinnedLocalHooks(
            session -> {
              ChannelHooks.Served served = new ChannelHooks(serving.get(), session).read();
              provider.set("mallory");
              return served;
            },
            SESSION::equals,
            (project, session) -> true,
            new PinnedLocalHooks.Accounts(
                PinnedLocalHooksTest::accountOf,
                PinnedLocalHooksTest::accountOf,
                session -> Optional.of(provider.get())),
            conversations,
            sets);
    String log = turnLog();

    assertEquals(List.of(), racing.pin(opened(log, SESSION)));
    assertEquals(Optional.empty(), conversations.localHooksOf(log));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM local_hook_sets", Integer.class));
  }

  @Test
  void a_session_with_no_hooks_and_a_child_of_a_log_with_none_have_none_and_nothing_is_said() {
    serving.set(new FakeFiles());
    String parent =
        conversations.log(Origin.SUBMISSION, LEDGER, "scribe", null, Budget.of(5), OWNER).id();
    String child = conversations.log(Origin.DELEGATION, LEDGER, "scribe", parent, null).id();

    assertEquals(
        List.of(),
        pins.pin(
            new LogStages.LogOpened(
                parent, Origin.SUBMISSION, LEDGER, "scribe", false, null, SESSION, null)));
    assertEquals(
        List.of(),
        pins.pin(
            new LogStages.LogOpened(
                child, Origin.DELEGATION, LEDGER, "scribe", false, parent, null, parent)));
    assertEquals(Optional.empty(), conversations.localHooksOf(parent));
    assertEquals(Optional.empty(), conversations.localHooksOf(child));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM local_hook_sets", Integer.class));
  }

  @Test
  void a_reader_that_breaks_its_contract_gives_no_tier_and_says_why_rather_than_throwing() {
    PinnedLocalHooks breaking =
        new PinnedLocalHooks(
            session -> {
              throw new IllegalStateException("the reader broke");
            },
            SESSION::equals,
            (project, session) -> true,
            PinnedLocalHooks.Accounts.all(PinnedLocalHooksTest::accountOf),
            conversations,
            sets);
    String log = turnLog();

    List<HookRecord> said = breaking.pin(opened(log, SESSION));

    assertEquals(Optional.empty(), conversations.localHooksOf(log));
    assertEquals(1, said.size());
    assertEquals(HookRecord.FAILED, said.get(0).decision());
    assertTrue(said.get(0).reason().contains("the reader broke"), said.get(0).reason());
  }
}
