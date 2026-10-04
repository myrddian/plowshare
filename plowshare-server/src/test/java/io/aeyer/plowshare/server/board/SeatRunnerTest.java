package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.FiringRecord;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.events.TriggerRecord;
import io.aeyer.plowshare.server.events.TriggerStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@link SeatRunner} — a wake becomes a seat's turn — spec 2026-09-29 §5-§9. */
@Tag("full-db")
@Testcontainers
class SeatRunnerTest {
  private static final io.aeyer.plowshare.server.swarm.SwarmScheduler.Pools FAST_POOLS =
      new io.aeyer.plowshare.server.swarm.SwarmScheduler.Pools() {
        public List<String> serving(String model) {
          return model.equals("fast") ? List.of("spark") : List.of();
        }

        public int slots(String pool) {
          return 2;
        }

        public List<String> all() {
          return List.of("spark");
        }
      };

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource source;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  /** Records every call; answers a job id, or throws when told to. */
  static final class FakeVoice implements SeatRunner.Voice {

    record Call(
        String conversation,
        AgentDefinition member,
        String utterance,
        Budget lease,
        TurnCap wakeCap,
        Speaker speaker,
        Consumer<Outcome> ended) {}

    final List<Call> calls = new ArrayList<>();
    final Set<String> speaking = new HashSet<>();
    String refuseWith;

    @Override
    public boolean isSpeaking(String conversation) {
      return speaking.contains(conversation);
    }

    @Override
    public String speakToSeat(
        String conversation,
        AgentDefinition member,
        String utterance,
        Budget lease,
        TurnCap wakeCap,
        Speaker speaker,
        Consumer<Outcome> ended) {
      if (refuseWith != null) {
        throw new IllegalStateException(refuseWith);
      }
      calls.add(new Call(conversation, member, utterance, lease, wakeCap, speaker, ended));
      return "job_" + calls.size();
    }
  }

  @TempDir Path agents;

  private BoardFixture fixture;
  private BoardStore store;
  private FiringStore firings;
  private JdbcTemplate jdbc;
  private Board board;
  private BoardPot pot;
  private FakeVoice voice;
  private SeatRunner runner;

  @BeforeEach
  void fresh() throws Exception {
    fixture = new BoardFixture(source);
    store = fixture.store;
    firings = fixture.firings;
    jdbc = fixture.jdbc;
    board = fixture.board(BoardFixture.TWO);
    pot = new BoardPot(store);
    voice = new FakeVoice();
    for (String name : List.of("researcher", "critic", "scribe", "aristoxenus")) {
      Files.writeString(
          agents.resolve(name + ".md"),
          "---\nname: "
              + name
              + "\ndescription: "
              + name
              + "\nmodel: fast\ntools: [file_read]\n"
              + "max-turns: 4\nmax-model-calls: 8\n---\nYou help.\n");
    }
    AgentRegistry registry = AgentRegistry.of(agents, Set.of("file_read"));
    runner =
        new SeatRunner(
            store,
            board,
            pot,
            voice,
            (agent, home) -> registry.get(agent),
            () -> 12,
            fixture.clock,
            fixture.drained::add,
            FAST_POOLS);
  }

  private Board.Opened openByBot() {
    return fixture.openByBot(board);
  }

  private FiringRecord queuedWake(String occupant, Board.Opened opened) {
    BoardSeat seat = store.seat(opened.topic().id(), occupant).orElseThrow();
    return jdbc
        .query(
            "SELECT id FROM firings WHERE target = ? AND status = 'queued'",
            (rs, n) -> rs.getString(1),
            "conversation:" + seat.conversation())
        .stream()
        .findFirst()
        .flatMap(firings::find)
        .orElseThrow();
  }

  @Test
  void a_wake_speaks_to_the_seat_as_its_member_on_a_lease_capped_per_wake() {
    Board.Opened opened = openByBot();
    FiringRecord wake = queuedWake("researcher", opened);
    runner.start(wake, (conversation, outcome) -> {});
    FakeVoice.Call call = voice.calls.get(0);
    assertEquals("researcher", call.member().name());
    assertEquals(12, call.lease().limit());
    assertEquals("board " + opened.topic().id(), call.speaker().name());
    assertTrue(
        call.utterance()
            .startsWith(
                "Board · [BAD SPEC / NEED INFO] \"sync between"
                    + " devices\" · topic id: "
                    + opened.topic().id()
                    + " · woken because a new topic opened"),
        call.utterance());
    assertTrue(call.utterance().contains("1 unread"), call.utterance());
    assertEquals(12, pot.leased(opened.topic().id()));
  }

  @Test
  void the_end_of_a_wake_settles_its_lease_and_counts_a_silent_wake() {
    Board.Opened opened = openByBot();
    List<Outcome> ends = new ArrayList<>();
    runner.start(queuedWake("researcher", opened), (conversation, outcome) -> ends.add(outcome));
    FakeVoice.Call call = voice.calls.get(0);
    call.lease().trySpend();
    call.ended().accept(new Outcome(Outcome.Ending.ANSWERED, "noted", 1, 1, ""));
    assertEquals(1, ends.size());
    assertEquals(1, store.topic(opened.topic().id()).orElseThrow().potSpent());
    assertEquals(0, pot.leased(opened.topic().id()));
    assertEquals(1, store.seat(opened.topic().id(), "researcher").orElseThrow().silentWakes());
  }

  @Test
  void a_wake_that_posted_is_not_silent() {
    Board.Opened opened = openByBot();
    runner.start(queuedWake("researcher", opened), (conversation, outcome) -> {});
    FakeVoice.Call call = voice.calls.get(0);
    board.post(
        new Board.Post(
            opened.topic().id(),
            BoardMessage.BY_MEMBER,
            "researcher",
            call.conversation(),
            3,
            BoardMessage.POST,
            null,
            "Found it.",
            null,
            List.of(),
            false));
    call.ended().accept(new Outcome(Outcome.Ending.ANSWERED, "done", 2, 2, ""));
    assertEquals(0, store.seat(opened.topic().id(), "researcher").orElseThrow().silentWakes());
  }

  @Test
  void a_failed_wake_is_noted_on_the_topic_and_marked_on_the_seat() {
    Board.Opened opened = openByBot();
    runner.start(queuedWake("critic", opened), (conversation, outcome) -> {});
    voice.calls.get(0).ended().accept(new Outcome(Outcome.Ending.STUCK, "stuck", 3, 3, ""));
    assertEquals("STUCK", store.seat(opened.topic().id(), "critic").orElseThrow().failedEnding());
    BoardMessage note = store.messages(opened.topic().id()).getLast();
    assertEquals(BoardMessage.NOTE, note.kind());
    assertTrue(note.body().contains("critic") && note.body().contains("STUCK"), note.body());
  }

  /**
   * Spec §9 names CALL_FAILURES beside STUCK: a wake whose model calls kept failing stopped for a
   * reason the seat cannot fix by being woken again. Counted silent instead, it would be re-woken
   * by the next reply and fail the same way, spending the pot each time.
   */
  @Test
  void a_wake_ended_by_call_failures_is_noted_on_the_topic_and_marked_on_the_seat() {
    Board.Opened opened = openByBot();
    runner.start(queuedWake("critic", opened), (conversation, outcome) -> {});
    voice
        .calls
        .get(0)
        .ended()
        .accept(new Outcome(Outcome.Ending.CALL_FAILURES, "the model kept failing", 3, 3, ""));
    BoardSeat critic = store.seat(opened.topic().id(), "critic").orElseThrow();
    assertEquals("CALL_FAILURES", critic.failedEnding());
    assertEquals(0, critic.silentWakes(), "a failed wake was counted as a silent one");
    BoardMessage note = store.messages(opened.topic().id()).getLast();
    assertEquals(BoardMessage.NOTE, note.kind());
    assertTrue(
        note.body().contains("critic") && note.body().contains("CALL_FAILURES"), note.body());
  }

  @Test
  void a_spent_pot_refuses_the_wake_and_exhausts_the_topic() {
    // A pot of 2 keeps 1 for the openers; spending the other leaves a member nothing.
    Board.Opened opened =
        board.open(
            new Board.Open(
                Home.of("payments"), "t", "L", "b", "enzo", BoardTopic.BY_PERSON, "enzo", null, 2));
    store.spend(opened.topic().id(), 1);
    FiringRecord wake = queuedWake("researcher", opened);
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class, () -> runner.start(wake, (conversation, outcome) -> {}));
    assertTrue(refused.getMessage().contains("budget"), refused.getMessage());
    runner.settled(wake); // The dispatcher checks lifecycle after durably refusing the firing.
    assertEquals(BoardTopic.EXHAUSTED, store.topic(opened.topic().id()).orElseThrow().state());
    assertTrue(voice.calls.isEmpty());
  }

  @Test
  void a_start_the_seat_refuses_gives_the_lease_back() {
    Board.Opened opened = openByBot();
    voice.refuseWith = "conversation busy";
    // The exception that propagates is the seat's own refusal, unchanged — not merely "some
    // RuntimeException" was thrown, and not the settle path's own bookkeeping replacing it.
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> runner.start(queuedWake("researcher", opened), (c, o) -> {}));
    assertEquals("conversation busy", refused.getMessage());
    assertEquals(0, pot.leased(opened.topic().id()));
  }

  @Test
  void an_unknown_reason_refuses_the_wake_before_anything_is_leased() {
    Board.Opened opened = openByBot();
    BoardSeat seat = store.seat(opened.topic().id(), "researcher").orElseThrow();
    // Owed directly, bypassing Board/WakeRules, which never write a reason this server does
    // not know: this is the shape a corrupt or forward-incompatible row would take.
    FiringRecord bogus =
        firings
            .owe(
                opened.topic().id(),
                "conversation:" + seat.conversation(),
                "{\"reason\":\"bogus\"}",
                fixture.clock.get())
            .orElseThrow();
    assertThrows(
        IllegalArgumentException.class, () -> runner.start(bogus, (conversation, outcome) -> {}));
    assertEquals(0, pot.leased(opened.topic().id()));
    assertTrue(voice.calls.isEmpty());
  }

  /** No trigger fires here; a trigger's firing reaching this would be a wiring mistake. */
  private static final Dispatcher.Runner NO_TRIGGERS =
      new Dispatcher.Runner() {
        @Override
        public boolean busy(TriggerRecord trigger) {
          return false;
        }

        @Override
        public String start(
            TriggerRecord trigger, String utterance, BiConsumer<String, Outcome> ended) {
          throw new IllegalStateException("no trigger belongs in this test");
        }
      };

  private static final SwarmDefinitions.SwarmDefinition THREE =
      new SwarmDefinitions.SwarmDefinition(
          List.of("researcher", "critic", "scribe"), 20, Map.of(), "test");

  private FiringRecord only(String occupant, Board.Opened opened, String status) {
    BoardSeat seat = store.seat(opened.topic().id(), occupant).orElseThrow();
    return jdbc
        .query(
            "SELECT id FROM firings WHERE target = ? AND status = ?",
            (rs, n) -> rs.getString(1),
            "conversation:" + seat.conversation(),
            status)
        .stream()
        .findFirst()
        .flatMap(firings::find)
        .orElseThrow(() -> new AssertionError(occupant + " has no " + status + " wake"));
  }

  /**
   * The final review's I-2: a pot merely leased out is not a spent one. Budget 20, reserve 2 and a
   * wake cap of 12 leave three members 18 calls between them — 12 to the first wake and 6 to the
   * second, so the third finds nothing to lease while both run. That wake waits, queued, rather
   * than being refused and exhausting a topic that has spent nothing; the first settle drains it.
   * Exhausted comes only once the pot is truly spent. Through the real dispatcher, because "waits"
   * is the dispatcher leaving a busy wake queued.
   */
  @Test
  void a_pot_merely_leased_out_leaves_a_wake_queued_until_a_settle_starts_it() {
    Dispatcher dispatcher =
        new Dispatcher(
            firings,
            new TriggerStore(jdbc),
            NO_TRIGGERS,
            new Inbox(new InboxStore(jdbc), AccountPushes.NONE, Instant::now),
            fixture.clock);
    Board three =
        new Board(
            store,
            project -> THREE,
            fixture.conversations,
            firings,
            dispatcher::drain,
            fixture.work,
            () -> 10,
            fixture.clock);
    AgentRegistry registry = AgentRegistry.of(agents, Set.of("file_read"));
    SeatRunner seats =
        new SeatRunner(
            store,
            three,
            pot,
            voice,
            (agent, home) -> registry.get(agent),
            () -> 12,
            fixture.clock,
            dispatcher::drain,
            FAST_POOLS);
    dispatcher.useWakes(seats);

    Board.Opened opened =
        three.open(
            new Board.Open(
                Home.of("payments"),
                "sync",
                "L",
                "What does sync mean?",
                "enzo",
                BoardTopic.BY_PERSON,
                "enzo",
                null,
                null));
    String root = opened.topic().id();
    assertEquals(2, voice.calls.size(), "the first two members should be running");
    assertEquals(12, voice.calls.get(0).lease().limit());
    assertEquals(6, voice.calls.get(1).lease().limit());
    assertEquals(
        BoardTopic.OPEN,
        store.topic(root).orElseThrow().state(),
        "a pot merely leased out exhausted the topic");
    only("scribe", opened, "queued");

    // The first wake spends one call and ends: its settle frees 11, and the scribe starts.
    FakeVoice.Call first = voice.calls.get(0);
    first.lease().trySpend();
    first.ended().accept(new Outcome(Outcome.Ending.ANSWERED, "noted", 1, 1, ""));
    assertEquals(3, voice.calls.size(), "the settle did not start the waiting wake");
    assertEquals(11, voice.calls.get(2).lease().limit());
    assertEquals(
        store.seat(root, "scribe").orElseThrow().conversation(), voice.calls.get(2).conversation());
    assertEquals(BoardTopic.OPEN, store.topic(root).orElseThrow().state());

    // The other two spend everything they hold: 1 + 6 + 11 = 18, all a member may reach.
    for (FakeVoice.Call call : List.of(voice.calls.get(1), voice.calls.get(2))) {
      while (call.lease().trySpend()) {
        // spending the whole lease
      }
      call.ended().accept(new Outcome(Outcome.Ending.ANSWERED, "noted", 1, 1, ""));
    }
    assertEquals(18, store.topic(root).orElseThrow().potSpent());
    assertEquals(
        BoardTopic.EXHAUSTED,
        store.topic(root).orElseThrow().state(),
        "settling the last member allowance exhausts the root immediately");

    // Now the pot is truly spent for a member: the next wake is refused and exhausts it.
    three.post(
        new Board.Post(
            root,
            BoardMessage.BY_PERSON,
            "enzo",
            null,
            null,
            BoardMessage.POST,
            null,
            "@researcher, one more thing",
            null,
            List.of("researcher"),
            false));
    assertEquals(3, voice.calls.size());
    FiringRecord refused = only("researcher", opened, "refused");
    assertTrue(refused.reason().contains("budget"), refused.reason());
    assertEquals(BoardTopic.EXHAUSTED, store.topic(root).orElseThrow().state());
  }

  /**
   * The race busy() cannot close: another wake leases the last of the pot between the dispatcher
   * asking busy() and asking start(). start() throws without refusing — nothing is exhausted — and
   * busy() now answers true, which is what makes the dispatcher put the wake back in its queue
   * rather than record the throw as a refusal.
   */
  @Test
  void a_start_that_finds_the_pot_leased_out_throws_without_exhausting_and_is_then_busy() {
    Board three = fixture.board(THREE);
    Board.Opened opened =
        three.open(
            new Board.Open(
                Home.of("payments"),
                "sync",
                "L",
                "What does sync mean?",
                "enzo",
                BoardTopic.BY_PERSON,
                "enzo",
                null,
                null));
    String root = opened.topic().id();
    runner.start(queuedWake("researcher", opened), (conversation, outcome) -> {});
    runner.start(queuedWake("critic", opened), (conversation, outcome) -> {});
    FiringRecord scribe = queuedWake("scribe", opened);
    assertThrows(
        IllegalStateException.class, () -> runner.start(scribe, (conversation, outcome) -> {}));
    assertEquals(BoardTopic.OPEN, store.topic(root).orElseThrow().state());
    assertEquals(2, voice.calls.size());
    assertTrue(runner.busy(scribe), "the dispatcher would refuse a wake that only waits");
  }

  /**
   * Exhausted is enforced, not just recorded: a member's wake on an exhausted root is refused even
   * when its pot has calls left (the reserve, or a top-up still to come), while the opener's is not
   * — the reserve exists so an opener can still close the topic.
   */
  @Test
  void an_exhausted_root_refuses_a_member_wake_but_not_an_opener_wake() {
    Board.Opened opened = openByBot();
    String root = opened.topic().id();
    store.exhaust(root);
    FiringRecord member = queuedWake("researcher", opened);
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class, () -> runner.start(member, (conversation, outcome) -> {}));
    assertTrue(refused.getMessage().contains("budget"), refused.getMessage());
    assertTrue(voice.calls.isEmpty());
    assertEquals(0, pot.leased(root));
    assertFalse(runner.busy(member), "a refused wake must reach start, not wait forever");

    board.post(
        new Board.Post(
            root,
            BoardMessage.BY_MEMBER,
            "critic",
            null,
            null,
            BoardMessage.POST,
            null,
            "Sync means last-writer-wins.",
            opened.opening().id(),
            List.of(),
            false));
    runner.start(queuedWake(BoardSeat.OPENER, opened), (conversation, outcome) -> {});
    assertEquals(1, voice.calls.size());
    assertEquals("aristoxenus", voice.calls.get(0).member().name());
  }

  /** Wakes run the member's normal tools and grants. */
  @Test
  void a_member_whose_definition_now_acts_runs_with_its_normal_tools() throws Exception {
    Board.Opened opened = openByBot();
    Path edited = Files.createDirectories(agents.resolve("edited"));
    Files.writeString(
        edited.resolve("researcher.md"),
        "---\nname: researcher"
            + "\ndescription: researcher\nmodel: fast\ntools: [file_read, file_edit]\n"
            + "max-turns: 4\nmax-model-calls: 8\n---\nYou help.\n");
    AgentRegistry now = AgentRegistry.of(edited, Set.of("file_read", "file_edit"));
    SeatRunner reading =
        new SeatRunner(
            store,
            board,
            pot,
            voice,
            (agent, home) -> now.get(agent),
            () -> 12,
            fixture.clock,
            fixture.drained::add,
            FAST_POOLS);
    reading.start(queuedWake("researcher", opened), (c, o) -> {});
    assertEquals(1, voice.calls.size());
    assertTrue(voice.calls.getFirst().member().tools().contains("file_edit"));
    assertTrue(pot.leased(opened.topic().id()) > 0);
  }

  @Test
  void busy_is_whether_the_seat_has_a_turn_in_flight() {
    Board.Opened opened = openByBot();
    FiringRecord wake = queuedWake("researcher", opened);
    voice.speaking.add(store.seat(opened.topic().id(), "researcher").orElseThrow().conversation());
    assertTrue(runner.busy(wake));
  }

  @Test
  void a_cap_owes_one_fresh_wake_for_unread_addressed_messages() {
    Board.Opened opened = openByBot();
    runner.start(queuedWake("researcher", opened), (c, o) -> {});
    board.post(
        new Board.Post(
            opened.topic().id(),
            BoardMessage.BY_MEMBER,
            "critic",
            null,
            null,
            BoardMessage.POST,
            null,
            "@researcher?",
            null,
            List.of("researcher"),
            false));
    jdbc.update("UPDATE firings SET status = 'refused' WHERE status = 'queued'");
    voice.calls.getFirst().ended().accept(new Outcome(Outcome.Ending.TURN_CAP, "cap", 12, 12, ""));
    BoardSeat seat = store.seat(opened.topic().id(), "researcher").orElseThrow();
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM firings WHERE target = ? AND status = 'queued'",
            Integer.class,
            "conversation:" + seat.conversation()));
  }

  @Test
  void an_answer_without_reading_does_not_re_owe_the_opening() {
    Board.Opened opened = openByBot();
    runner.start(queuedWake("researcher", opened), (c, o) -> {});
    jdbc.update("UPDATE firings SET status = 'refused' WHERE status = 'queued'");
    voice.calls.getFirst().ended().accept(new Outcome(Outcome.Ending.ANSWERED, "done", 1, 1, ""));
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM firings WHERE status = 'queued'", Integer.class));
  }

  @Test
  void a_member_whose_model_has_no_swarm_pool_is_refused_before_leasing() throws Exception {
    Board.Opened opened = openByBot();
    Files.writeString(
        agents.resolve("researcher.md"),
        "---\nname: researcher\ndescription: d\nmodel: elsewhere\ntools: []\nmax-turns: 4\nmax-model-calls: 8\n---\nYou help.");
    AgentRegistry now = AgentRegistry.of(agents, Set.of("file_read"));
    SeatRunner reading =
        new SeatRunner(
            store,
            board,
            pot,
            voice,
            (agent, home) -> now.get(agent),
            () -> 12,
            fixture.clock,
            fixture.drained::add,
            FAST_POOLS);
    var refused =
        assertThrows(
            IllegalStateException.class,
            () -> reading.start(queuedWake("researcher", opened), (c, o) -> {}));
    assertTrue(refused.getMessage().contains("elsewhere"), refused.getMessage());
    assertEquals(0, pot.leased(opened.topic().id()));
  }

  @Test
  void a_failed_charge_is_not_spent_and_completion_releases_the_lease() {
    Board.Opened opened = openByBot();
    BoardStore flaky =
        new BoardStore(jdbc, fixture.clock) {
          private boolean first = true;

          @Override
          public void spend(String root, int calls) {
            if (first) {
              first = false;
              throw new IllegalStateException("once");
            }
            super.spend(root, calls);
          }
        };
    BoardPot retryPot = new BoardPot(flaky);
    var registry = AgentRegistry.of(agents, Set.of("file_read"));
    var retry =
        new SeatRunner(
            store,
            board,
            retryPot,
            voice,
            (agent, home) -> registry.get(agent),
            () -> 12,
            fixture.clock,
            fixture.drained::add,
            FAST_POOLS);
    retry.start(queuedWake("researcher", opened), (c, o) -> {});
    assertThrows(IllegalStateException.class, voice.calls.getFirst().lease()::trySpend);
    assertEquals(0, voice.calls.getFirst().lease().spent());
    assertEquals(0, store.topic(opened.topic().id()).orElseThrow().potSpent());
    assertTrue(voice.calls.getFirst().lease().trySpend());
    voice.calls.getFirst().ended().accept(new Outcome(Outcome.Ending.ANSWERED, "done", 1, 1, ""));
    assertEquals(0, retryPot.leased(opened.topic().id()));
    assertEquals(1, store.topic(opened.topic().id()).orElseThrow().potSpent());
  }

  @Test
  void a_child_member_is_refused_when_its_root_is_exhausted() {
    Board.Opened opened = openByBot();
    String child = "bdt_child";
    jdbc.update(
        "INSERT INTO board_topics (id,project,parent,root,depth,title,label,account,opener_kind,opener,state,opened_at) VALUES (?, 'payments', ?, ?, 1, 'child', 'L', 'enzo', 'member', 'researcher', 'open', now())",
        child,
        opened.topic().id(),
        opened.topic().id());
    String chat =
        fixture
            .conversations
            .log(
                io.aeyer.plowshare.server.archive.Origin.BOARD,
                Home.of("payments"),
                "critic",
                null,
                null,
                "enzo")
            .id();
    store.seatIfAbsent(child, "critic", chat);
    store.exhaust(opened.topic().id());
    var wake = firings.owe(child, "conversation:" + chat, "{}", fixture.clock.get()).orElseThrow();
    assertThrows(IllegalStateException.class, () -> runner.start(wake, (c, o) -> {}));
    assertEquals(0, pot.leased(opened.topic().id()));
  }

  @Test
  void closing_stops_an_in_flight_seat_after_its_current_step() {
    Board.Opened opened = openByBot();
    runner.start(queuedWake("researcher", opened), (c, o) -> {});
    var cap = voice.calls.getFirst().wakeCap();
    assertFalse(cap.stops(1));
    board.close(
        new Board.Close(
            opened.topic().id(),
            BoardMessage.BY_OPENER,
            "aristoxenus",
            null,
            "decided",
            List.of()));
    assertTrue(cap.stops(1));
    voice.calls.getFirst().ended().accept(new Outcome(Outcome.Ending.TURN_CAP, "closed", 1, 1, ""));
    assertEquals(0, pot.leased(opened.topic().id()));
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM firings WHERE status = 'queued'", Integer.class));
  }

  @Test
  void an_explicit_retry_uses_its_limit_on_the_existing_seat_and_preserves_shared_accounting() {
    var opened = openByBot();
    var before = store.seat(opened.topic().id(), "researcher").orElseThrow();
    store.recordFailure(opened.topic().id(), "researcher", "TURN_CAP");
    board.retry(opened.topic().id(), "researcher", "enzo", 24);
    runner.start(queuedWake("researcher", opened), (c, o) -> {});
    var call = voice.calls.getFirst();
    assertEquals(before.conversation(), call.conversation());
    assertEquals(24, call.wakeCap().turns());
    assertEquals(18, call.lease().limit()); // budget 20 less reserve 2
    assertEquals(0, store.topic(opened.topic().id()).orElseThrow().potSpent());
    assertTrue(call.lease().trySpend());
    assertEquals(1, store.topic(opened.topic().id()).orElseThrow().potSpent());
    call.ended().accept(new Outcome(Outcome.Ending.ANSWERED, "done", 1, 1, ""));
    assertEquals(0, pot.leased(opened.topic().id()));
    assertEquals(20, store.topic(opened.topic().id()).orElseThrow().potTotal());
  }

  @Test
  void a_later_mention_coalesces_without_losing_the_explicit_queued_retry_limit() {
    var opened = openByBot();
    store.recordFailure(opened.topic().id(), "researcher", "TURN_CAP");
    board.retry(opened.topic().id(), "researcher", "enzo", 40);
    board.post(
        new Board.Post(
            opened.topic().id(),
            BoardMessage.BY_PERSON,
            "enzo",
            null,
            null,
            BoardMessage.POST,
            null,
            "One more question",
            null,
            List.of("researcher"),
            false));
    runner.start(queuedWake("researcher", opened), (c, o) -> {});
    assertEquals(40, voice.calls.getFirst().wakeCap().turns());
    assertEquals(1, voice.calls.size());
  }
}
