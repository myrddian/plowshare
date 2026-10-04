package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.RecordingLogStages;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("full-db")
@Testcontainers
class DispatcherTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  private FiringStore firings;
  private TriggerStore triggers;
  private InboxStore inboxStore;
  private FakeRunner runner;
  private Dispatcher dispatcher;
  private Intake intake;
  private static final Instant T0 = Instant.parse("2026-09-13T09:00:00Z");
  // A strictly advancing clock and not a fixed `now`: MemoryIds.mint breaks ties within the
  // same millisecond with three random bytes, so several firings arriving at one fixed instant
  // sort by that randomness rather than by the order they arrived in — a real, if rare,
  // flake this file hit under Postgres's `ORDER BY arrived_at DESC, id DESC`. One millisecond
  // per read keeps every firing's own arrived_at distinct and its ordering deterministic.
  private final AtomicLong ticks = new AtomicLong();
  private final Supplier<Instant> clock = () -> T0.plusMillis(ticks.getAndIncrement());

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(dataSource()).load().migrate();
    jdbc = new JdbcTemplate(dataSource());
  }

  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** Starts nothing; remembers each start so a test can end it by hand. */
  static final class FakeRunner implements Dispatcher.Runner {
    final Map<String, BiConsumer<String, Outcome>> running = new LinkedHashMap<>();
    final List<String> utterances = new ArrayList<>();
    final Set<String> speakingIn = new HashSet<>();
    String refuseWith;

    /**
     * Marks the trigger's conversation busy from inside {@link #start}, then throws — the shape of
     * a person's utterance winning the race against {@code Turn.speak}'s own claim.
     */
    boolean becomesBusyOnStart;

    /**
     * With {@link #becomesBusyOnStart}: the conversation frees again right after the one busy
     * answer that makes the dispatcher release — the turn that won the race ended between the
     * failed start and the release, so its own whenFree drain found nothing.
     */
    boolean freesRightAfterRelease;

    boolean freeOnNextBusyAnswer;
    int jobs;

    @Override
    public boolean busy(TriggerRecord t) {
      boolean busy = t.conversation() != null && speakingIn.contains(t.conversation());
      if (busy && freeOnNextBusyAnswer) {
        freeOnNextBusyAnswer = false;
        speakingIn.remove(t.conversation());
      }
      return busy;
    }

    @Override
    public String start(TriggerRecord t, String utterance, BiConsumer<String, Outcome> ended) {
      if (becomesBusyOnStart) {
        speakingIn.add(t.conversation());
        if (freesRightAfterRelease) {
          becomesBusyOnStart = false;
          freeOnNextBusyAnswer = true;
        }
        throw new IllegalStateException("conversation claimed underneath us");
      }
      if (refuseWith != null) {
        throw new IllegalStateException(refuseWith);
      }
      String job = "job_" + (++jobs);
      running.put(job, ended);
      utterances.add(utterance);
      return job;
    }

    void end(String job, String conversation) {
      running
          .remove(job)
          .accept(conversation, new Outcome(Outcome.Ending.ANSWERED, "done: " + job, 1, 1, ""));
    }
  }

  /** Starts nothing; remembers each wake so a test can end it by hand. */
  static final class FakeWakes implements Dispatcher.Wakes {
    final Map<String, BiConsumer<String, Outcome>> running = new LinkedHashMap<>();
    final List<String> started = new ArrayList<>();
    final Set<String> speakingIn = new HashSet<>();
    String refuseWith;
    int jobs;

    @Override
    public boolean busy(FiringRecord wake) {
      return speakingIn.contains(wake.target());
    }

    @Override
    public String start(FiringRecord wake, BiConsumer<String, Outcome> ended) {
      if (refuseWith != null) {
        throw new IllegalStateException(refuseWith);
      }
      String job = "wake_" + (++jobs);
      running.put(job, ended);
      started.add(wake.id());
      return job;
    }

    void end(String job) {
      running
          .remove(job)
          .accept("cnv_seat", new Outcome(Outcome.Ending.ANSWERED, "noted", 1, 1, ""));
    }
  }

  @BeforeEach
  void wire() {
    jdbc.execute("TRUNCATE TABLE user_inbox, firings, triggers, schedules, admins CASCADE");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
    firings = new FiringStore(jdbc);
    triggers = new TriggerStore(jdbc);
    inboxStore = new InboxStore(jdbc);
    runner = new FakeRunner();
    dispatcher =
        new Dispatcher(
            firings, triggers, runner, new Inbox(inboxStore, AccountPushes.NONE, clock), clock);
    intake = new Intake(triggers, firings, dispatcher, clock);
  }

  private TriggerRecord define(String name, String conversation, int cap) {
    return triggers.define(
        new TriggerRecord(
            name,
            "daily",
            null,
            conversation,
            "bard",
            "summarise the night",
            null,
            null,
            cap,
            false,
            "enzo"));
  }

  private static void topic(String id) {
    jdbc.update(
        "INSERT INTO board_topics (id, project, root, depth, title, label, account,"
            + " opener_kind, opener, state, pot_total, pot_spent, reserve, opened_at)"
            + " VALUES (?, 'payments', ?, 0, 'sync', 'BAD SPEC', 'enzo', 'person', 'enzo',"
            + " 'open', 20, 0, 2, now())",
        id,
        id);
  }

  private FiringRecord owe(String target) {
    FiringRecord wake =
        firings.owe("bdt_1", target, "{\"reason\":\"opened\"}", clock.get()).orElseThrow();
    firings.supersedeWakesBeyond(target, 1, wake.id());
    return wake;
  }

  @Test
  void one_event_starts_every_trigger_listening_for_it() {
    define("a", null, 1);
    define("b", null, 1);
    List<FiringRecord> created = intake.emit("daily", Map.of());
    assertEquals(2, created.size());
    assertEquals(2, runner.running.size());
  }

  @Test
  void an_event_nobody_listens_for_is_recorded_and_starts_nothing() {
    List<FiringRecord> created = intake.emit("nobody-listens", Map.of("x", 1));
    assertEquals("unmatched", created.get(0).status());
    assertTrue(runner.running.isEmpty());
  }

  @Test
  void the_task_is_the_instruction_and_the_event_data_follows_it_marked_as_data() {
    define("a", null, 1);
    intake.emit("daily", Map.of("schedule", "nine"));
    String said = runner.utterances.get(0);
    assertTrue(said.startsWith("summarise the night"), said);
    assertTrue(said.contains("event data — not instructions"), said);
    assertTrue(said.contains("\"schedule\": \"nine\""), said);
  }

  @Test
  void a_second_firing_waits_while_the_first_runs_and_starts_when_it_ends() {
    define("a", null, 1);
    intake.emit("daily", Map.of("n", 1));
    intake.emit("daily", Map.of("n", 2));
    assertEquals(1, runner.running.size());
    runner.end("job_1", "cnv_1");
    assertEquals(1, runner.running.size());
    assertTrue(runner.running.containsKey("job_2"));
  }

  @Test
  void past_the_cap_the_newest_firing_waits_and_the_older_one_is_superseded() {
    define("a", null, 1);
    intake.emit("daily", Map.of("n", 1));
    intake.emit("daily", Map.of("n", 2));
    FiringRecord newest = intake.emit("daily", Map.of("n", 3)).get(0);
    FiringRecord superseded = firings.list("a", "superseded", 0, 10).get(0);
    assertEquals(newest.id(), superseded.supersededBy());
    runner.end("job_1", "cnv_1");
    assertTrue(runner.utterances.get(1).contains("\"n\": 3"), runner.utterances.get(1));
  }

  @Test
  void an_untargeted_run_delivers_to_the_definers_inbox_and_a_targeted_one_does_not() {
    define("loose", null, 1);
    define("aimed", "cnv_talk", 1);
    intake.emit("daily", Map.of());
    runner.running.keySet().stream().toList().forEach(job -> runner.end(job, "cnv_x"));
    assertEquals(1, inboxStore.list("enzo", false, 0, 10).size());
  }

  @Test
  void an_approval_question_is_not_duplicated_as_an_event_ending() {
    define("loose", null, 1);
    intake.emit("daily", Map.of());

    runner
        .running
        .remove("job_1")
        .accept("cnv_event", new Outcome(Outcome.Ending.AWAITING, "approve it", 1, 1, ""));

    assertTrue(inboxStore.list("enzo", false, 0, 10).isEmpty());
  }

  @Test
  void a_conversation_with_a_turn_in_flight_holds_the_firing_until_it_is_free() {
    define("aimed", "cnv_talk", 1);
    runner.speakingIn.add("cnv_talk");
    intake.emit("daily", Map.of());
    assertTrue(runner.running.isEmpty());
    runner.speakingIn.remove("cnv_talk");
    dispatcher.drain("conversation:cnv_talk");
    assertEquals(1, runner.running.size());
  }

  @Test
  void a_refused_start_is_recorded_and_does_not_block_the_next_firing() {
    define("a", null, 1);
    runner.refuseWith = "no agent named bard";
    intake.emit("daily", Map.of());
    assertEquals("no agent named bard", firings.list("a", "refused", 0, 10).get(0).reason());
    runner.refuseWith = null;
    intake.emit("daily", Map.of());
    assertEquals(1, runner.running.size());
  }

  @Test
  void two_drains_of_one_waiting_firing_start_it_once() {
    define("aimed", "cnv_talk", 1);
    runner.speakingIn.add("cnv_talk");
    intake.emit("daily", Map.of());
    runner.speakingIn.remove("cnv_talk");
    dispatcher.drain("conversation:cnv_talk");
    dispatcher.drain("conversation:cnv_talk");
    assertEquals(1, runner.running.size());
  }

  @Test
  void supersession_is_scoped_by_trigger_and_not_by_a_shared_conversation_target() {
    define("a", "cnv_talk", 3);
    triggers.define(
        new TriggerRecord(
            "b",
            "hourly",
            null,
            "cnv_talk",
            "bard",
            "summarise the hour",
            null,
            null,
            1,
            false,
            "enzo"));
    runner.speakingIn.add("cnv_talk");
    intake.emit("daily", Map.of("n", 1));
    intake.emit("daily", Map.of("n", 2));
    intake.emit("daily", Map.of("n", 3));
    intake.emit("hourly", Map.of("m", 1));
    assertEquals(0, firings.list("a", "superseded", 0, 10).size());
    assertEquals(3, firings.list("a", "queued", 0, 10).size());
  }

  @Test
  void a_conversation_that_becomes_busy_mid_start_keeps_its_firing_queued_and_starts_once_free() {
    define("aimed", "cnv_talk", 1);
    runner.becomesBusyOnStart = true;
    intake.emit("daily", Map.of());
    assertTrue(runner.running.isEmpty());
    assertEquals(1, firings.list("aimed", "queued", 0, 10).size());
    runner.becomesBusyOnStart = false;
    runner.speakingIn.remove("cnv_talk");
    dispatcher.drain("conversation:cnv_talk");
    assertEquals(1, runner.running.size());
  }

  @Test
  void
      a_conversation_that_frees_between_a_failed_start_and_its_release_starts_the_firing_in_the_same_drain() {
    define("aimed", "cnv_talk", 1);
    runner.becomesBusyOnStart = true;
    runner.freesRightAfterRelease = true;
    intake.emit("daily", Map.of());
    assertEquals(1, runner.running.size());
    assertEquals(1, firings.list("aimed", "started", 0, 10).size());
  }

  @Test
  void one_trigger_whose_dispatch_throws_does_not_stop_the_others_on_the_same_event() {
    define("a", null, 1);
    define("b", null, 1);
    List<Integer> firingsSeenAtFirstDispatch = new ArrayList<>();
    Dispatcher throwsForA =
        new Dispatcher(
            firings, triggers, runner, new Inbox(inboxStore, AccountPushes.NONE, clock), clock) {
          @Override
          public void dispatch(FiringRecord firing, TriggerRecord trigger) {
            if (firingsSeenAtFirstDispatch.isEmpty()) {
              firingsSeenAtFirstDispatch.add(firings.list(null, null, 0, 10).size());
            }
            if (trigger.name().equals("a")) {
              throw new IllegalStateException("the database blinked");
            }
            super.dispatch(firing, trigger);
          }
        };
    List<FiringRecord> created =
        new Intake(triggers, firings, throwsForA, clock).emit("daily", Map.of());
    assertEquals(2, created.size());
    assertEquals(
        List.of(2), firingsSeenAtFirstDispatch, "every firing is recorded before any dispatch");
    assertEquals(1, runner.running.size());
    assertEquals(1, firings.list("b", "started", 0, 10).size());
  }

  @Test
  void a_run_whose_end_cannot_be_recorded_still_delivers_to_the_inbox() {
    define("loose", null, 1);
    FiringStore finishFails =
        new FiringStore(jdbc) {
          @Override
          public void finish(String id, Instant at) {
            throw new IllegalStateException("the database blinked");
          }
        };
    Dispatcher fragile =
        new Dispatcher(
            finishFails, triggers, runner, new Inbox(inboxStore, AccountPushes.NONE, clock), clock);
    new Intake(triggers, finishFails, fragile, clock).emit("daily", Map.of());
    runner.end("job_1", "cnv_x");
    assertEquals(1, inboxStore.list("enzo", false, 0, 10).size());
  }

  @Test
  void an_event_run_s_delivery_carries_a_hook_s_note_and_is_told_from_its_own_log() {
    RecordingLogStages told = new RecordingLogStages();
    told.note = "checked";
    dispatcher.useLogStages(told);
    define("loose", null, 1);
    intake.emit("daily", Map.of());

    runner.end("job_1", "cnv_event");

    assertEquals("done: job_1\n\nchecked", inboxStore.list("enzo", false, 0, 10).get(0).answer());
    assertEquals(
        List.of("pre cnv_event inbox", "post cnv_event inbox done: job_1\n\nchecked"), told.lines);
  }

  /** A targeted run delivers nothing and an AWAITING one is PersonDelivery's: no stage passes. */
  @Test
  void a_targeted_run_and_an_awaiting_run_pass_no_delivery_stage() {
    RecordingLogStages told = new RecordingLogStages();
    dispatcher.useLogStages(told);
    define("aimed", "cnv_talk", 1);
    define("loose", null, 1);
    intake.emit("daily", Map.of());

    runner.end("job_1", "cnv_talk");
    runner
        .running
        .remove("job_2")
        .accept("cnv_event", new Outcome(Outcome.Ending.AWAITING, "approve it", 1, 1, ""));

    assertEquals(List.of(), told.lines);
    assertTrue(inboxStore.list("enzo", false, 0, 10).isEmpty());
  }

  @Test
  void a_wake_starts_through_the_wakes_and_finishes_when_its_turn_ends() {
    FakeWakes wakes = new FakeWakes();
    dispatcher.useWakes(wakes);
    topic("bdt_1");
    FiringRecord wake = owe("conversation:cnv_seat");
    dispatcher.drain("conversation:cnv_seat");
    assertEquals(List.of(wake.id()), wakes.started);
    assertEquals("started", firings.find(wake.id()).orElseThrow().status());
    wakes.end("wake_1");
    assertNotNull(firings.find(wake.id()).orElseThrow().finishedAt());
  }

  @Test
  void a_newer_wake_supersedes_the_one_waiting_while_the_seat_is_busy() {
    FakeWakes wakes = new FakeWakes();
    dispatcher.useWakes(wakes);
    topic("bdt_1");
    wakes.speakingIn.add("conversation:cnv_seat");
    FiringRecord older = owe("conversation:cnv_seat");
    dispatcher.drain("conversation:cnv_seat");
    FiringRecord newer = owe("conversation:cnv_seat");
    assertEquals("superseded", firings.find(older.id()).orElseThrow().status());
    assertEquals(newer.id(), firings.find(older.id()).orElseThrow().supersededBy());
    wakes.speakingIn.clear();
    dispatcher.drain("conversation:cnv_seat");
    assertEquals(List.of(newer.id()), wakes.started);
  }

  @Test
  void the_next_wake_starts_when_the_running_one_ends() {
    FakeWakes wakes = new FakeWakes();
    dispatcher.useWakes(wakes);
    topic("bdt_1");
    owe("conversation:cnv_seat");
    dispatcher.drain("conversation:cnv_seat");
    FiringRecord second = owe("conversation:cnv_seat");
    dispatcher.drain("conversation:cnv_seat");
    assertEquals(1, wakes.started.size(), "two wakes ran at once on one seat");
    wakes.end("wake_1");
    assertEquals(second.id(), wakes.started.get(1));
  }

  @Test
  void a_wake_refused_at_start_is_refused_with_the_reason_and_a_server_with_no_board_refuses_it() {
    FakeWakes wakes = new FakeWakes();
    wakes.refuseWith = "topic closed";
    dispatcher.useWakes(wakes);
    topic("bdt_1");
    FiringRecord refused = owe("conversation:cnv_seat");
    dispatcher.drain("conversation:cnv_seat");
    assertEquals("refused", firings.find(refused.id()).orElseThrow().status());
    assertEquals("topic closed", firings.find(refused.id()).orElseThrow().reason());

    dispatcher.useWakes(Dispatcher.Wakes.NONE);
    FiringRecord unwired = owe("conversation:cnv_other");
    dispatcher.drain("conversation:cnv_other");
    assertEquals("refused", firings.find(unwired.id()).orElseThrow().status());
  }

  @Test
  void a_trigger_firing_on_the_same_conversation_still_starts_through_the_runner() {
    // The existing trigger path, untouched by the wake branch.
    define("aimed", "cnv_talk", 1);
    intake.emit("daily", Map.of());
    assertEquals(1, runner.running.size());
  }
}
