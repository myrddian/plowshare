package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore.NewOrchestration;
import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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

/**
 * {@link StallSweep} over a real Postgres, so {@code stalled_since}'s compare-and-set and {@code
 * quietSince}'s walk of the delegation tree are proved against the database's own rules, not a mock
 * that cannot enforce {@code V55}'s own CHECK. Spec 2026-09-27 §4.
 */
@Tag("full-db")
@Testcontainers
class StallSweepTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant T0 = Instant.parse("2026-09-27T09:00:00Z");
  private static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);

  private static final List<StageRules.Stage> STAGES =
      List.of(new StageRules.Stage("goal", List.of()));

  private static JdbcTemplate jdbc;

  private final AtomicReference<Instant> clock = new AtomicReference<>(T0);
  private final Map<String, List<RunApproval>> openApprovalsByConversation = new HashMap<>();
  private final RecordingInbox inbox = new RecordingInbox();

  private OrchestrationStore store;
  private ConversationStore conversations;
  private EntryStore entries;
  private StallSweep sweep;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    jdbc = new JdbcTemplate(ds);
  }

  @BeforeEach
  void fresh() {
    jdbc.execute(
        "TRUNCATE TABLE orchestration_messages, orchestrations, user_inbox, entries,"
            + " turns, conversations, admins CASCADE");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('broken', 'h')");
    clock.set(T0);
    openApprovalsByConversation.clear();
    inbox.notices.clear();
    store = new OrchestrationStore(jdbc, clock::get);
    conversations = new ConversationStore(jdbc, clock::get, null);
    entries = new EntryStore(jdbc, clock::get);
    sweep =
        new StallSweep(
            store,
            conversation -> openApprovalsByConversation.getOrDefault(conversation, List.of()),
            inbox,
            FIFTEEN_MINUTES);
  }

  /** A fresh conductor conversation, the way the harness opens one for a run — spec §5. */
  private String conductorConversation() {
    return conversations
        .log(Origin.ORCHESTRATION, Home.of("story"), "code_implementation", null, Budget.of(40))
        .id();
  }

  private OrchestrationRecord running(String callerHandle) {
    return running(conductorConversation(), callerHandle, null, 0);
  }

  private OrchestrationRecord running(
      String conductor, String callerHandle, String parent, int depth) {
    return store.insert(
        new NewOrchestration(
            "code_implementation",
            Tier.PROJECT,
            "sha256:9f86d081884c7d659a2feaa0c55ad015",
            "---\nname: code_implementation\n---\nbody",
            "project",
            STAGES,
            2,
            "story",
            conductor,
            null,
            "code_implementation",
            callerHandle,
            "s-laptop",
            parent,
            depth));
  }

  private static RunApproval openApproval(String conversation) {
    return new RunApproval(
        "apr_1",
        1L,
        conversation,
        conversation,
        "coder",
        "local",
        List.of("./gradlew", "test"),
        "/repo",
        "a check",
        RunApproval.ASKED,
        null,
        null,
        null,
        null,
        T0);
  }

  // --- reported once per stall --------------------------------------------------------------

  @Test
  void a_run_quiet_for_sixteen_minutes_is_reported_once() {
    OrchestrationRecord run = running("enzo");

    assertEquals(1, sweep.sweep(T0.plus(16, ChronoUnit.MINUTES)));

    assertEquals(1, inbox.notices.size());
    RecordingInbox.Notice notice = inbox.notices.get(0);
    assertEquals("enzo", notice.handle());
    assertEquals(Delivery.INBOX_KIND, notice.kind());
    assertEquals(
        "`"
            + run.id()
            + "` (`code_implementation`) has done nothing for 15 minutes:"
            + " `/runs "
            + run.id()
            + "` to look, `/cancel "
            + run.id()
            + "` to stop it",
        notice.text());
    assertEquals(Optional.of(T0), store.stalledSince(run.id()));

    assertEquals(
        0,
        sweep.sweep(T0.plus(20, ChronoUnit.MINUTES)),
        "already marked, so a second sweep reports nothing");
    assertEquals(1, inbox.notices.size(), "and delivers nothing a second time");
  }

  @Test
  void activity_after_the_stall_clears_it_and_a_later_quiet_period_reports_again() {
    OrchestrationRecord run = running("enzo");
    assertEquals(1, sweep.sweep(T0.plus(16, ChronoUnit.MINUTES)));

    clock.set(T0.plus(20, ChronoUnit.MINUTES));
    entries.append(
        run.conductorConversation(), 1, LoggedEntry.utterance("back at it", Speaker.person(null)));

    assertEquals(
        0,
        sweep.sweep(T0.plus(21, ChronoUnit.MINUTES)),
        "active again, so nothing is newly reported");
    assertEquals(
        Optional.empty(),
        store.stalledSince(run.id()),
        "the mark is cleared once activity is newer than it");

    assertEquals(
        0,
        sweep.sweep(T0.plus(30, ChronoUnit.MINUTES)),
        "quiet again, but not yet fifteen minutes since the new activity");
    assertEquals(
        1,
        sweep.sweep(T0.plus(36, ChronoUnit.MINUTES)),
        "fifteen minutes quiet since the new activity: a new stall");
    assertEquals(2, inbox.notices.size());
  }

  /**
   * Fix round 1, #1. A sweep is not guaranteed to run every minute on the dot, so nothing
   * guarantees one lands in the window while the run is briefly active between two stalls. If
   * {@code markStalled} refused a later {@code since} just because an earlier mark was still on the
   * row, the second, later stall would be silently dropped — this is exactly that: no sweep at all
   * between the activity at 20 minutes and the next one at 40, so the earlier mark from 16 minutes
   * is still there when the run is found quiet again.
   */
  @Test
  void a_new_stall_is_reported_even_when_no_sweep_saw_the_run_active_in_between() {
    OrchestrationRecord run = running("enzo");
    assertEquals(
        1, sweep.sweep(T0.plus(16, ChronoUnit.MINUTES)), "the first stall, quiet since createdAt");

    clock.set(T0.plus(20, ChronoUnit.MINUTES));
    entries.append(
        run.conductorConversation(), 1, LoggedEntry.utterance("back at it", Speaker.person(null)));

    assertEquals(
        1,
        sweep.sweep(T0.plus(40, ChronoUnit.MINUTES)),
        "a later, genuinely new stall, even though nothing ever cleared the earlier mark");
    assertEquals(2, inbox.notices.size());
    assertEquals(Optional.of(T0.plus(20, ChronoUnit.MINUTES)), store.stalledSince(run.id()));
  }

  @Test
  void a_run_quiet_for_fourteen_minutes_is_not_reported() {
    OrchestrationRecord run = running("enzo");

    assertEquals(0, sweep.sweep(T0.plus(14, ChronoUnit.MINUTES)));

    assertTrue(inbox.notices.isEmpty());
    assertEquals(Optional.empty(), store.stalledSince(run.id()));
  }

  // --- excused: an open approval, or a live child -------------------------------------------

  @Test
  void a_run_with_an_open_approval_in_its_conductor_conversation_is_not_reported() {
    OrchestrationRecord run = running("enzo");
    openApprovalsByConversation.put(
        run.conductorConversation(), List.of(openApproval(run.conductorConversation())));

    assertEquals(0, sweep.sweep(T0.plus(16, ChronoUnit.MINUTES)));

    assertTrue(inbox.notices.isEmpty());
    assertEquals(Optional.empty(), store.stalledSince(run.id()));
  }

  /**
   * Final review: an acceptance command's approval is asked at the spec stage and the conductor
   * goes on meanwhile, so it excuses a quiet run only once that run is at its acceptance stage and
   * waiting on it — never a run quiet at plan or phases.
   */
  @Test
  void an_acceptance_approval_excuses_a_run_only_at_its_acceptance_stage() {
    OrchestrationRecord run = running("enzo");
    RunApproval acceptance =
        new RunApproval(
            "apr_2",
            1L,
            run.conductorConversation(),
            run.conductorConversation(),
            "implement_specification",
            "local",
            List.of("python", "-m", "rpg.main"),
            "/repo",
            AcceptanceGate.why("3\n", 0, "Main Menu"),
            RunApproval.ASKED,
            null,
            null,
            null,
            null,
            T0);
    openApprovalsByConversation.put(run.conductorConversation(), List.of(acceptance));
    java.util.concurrent.atomic.AtomicBoolean there =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    StallSweep scoped =
        new StallSweep(
            store,
            conversation -> openApprovalsByConversation.getOrDefault(conversation, List.of()),
            inbox,
            FIFTEEN_MINUTES,
            OrchestrationRecorder.NONE,
            each -> there.get());

    there.set(true);
    assertEquals(0, scoped.sweep(T0.plus(16, ChronoUnit.MINUTES)), "waiting at acceptance");

    there.set(false);
    assertEquals(1, scoped.sweep(T0.plus(17, ChronoUnit.MINUTES)), "quiet at an earlier stage");
    assertEquals(1, inbox.notices.size());
  }

  @Test
  void a_run_is_at_acceptance_only_while_its_acceptance_required_stage_is_in_progress() {
    OrchestrationRecord run =
        new OrchestrationRecord(
            "orc_1",
            "implement_specification",
            Tier.SHIPPED,
            "sha256:x",
            "src",
            "test",
            List.of(
                new StageRules.Stage("spec", List.of(), false, "written"),
                new StageRules.Stage("acceptance", List.of(), false, "required")),
            2,
            0,
            "rpg",
            "cnv_1",
            null,
            "interlocutor",
            "enzo",
            null,
            null,
            0,
            null,
            OrchestrationState.RUNNING,
            null,
            null,
            null,
            0,
            0,
            false,
            null,
            T0,
            null);
    TodoItem spec =
        new TodoItem(
            "td_1", "cnv_1", null, 0, "spec", TodoStatus.IN_PROGRESS, null, true, "spec", T0);
    TodoItem acceptance =
        new TodoItem(
            "td_2",
            "cnv_1",
            null,
            1,
            "acceptance",
            TodoStatus.PENDING,
            null,
            true,
            "acceptance",
            T0);

    assertEquals(false, StallSweep.atAcceptance(run, List.of(spec, acceptance)));
    assertEquals(
        true,
        StallSweep.atAcceptance(
            run,
            List.of(
                new TodoItem(
                    "td_1", "cnv_1", null, 0, "spec", TodoStatus.DONE, "s", true, "spec", T0),
                new TodoItem(
                    "td_2",
                    "cnv_1",
                    null,
                    1,
                    "acceptance",
                    TodoStatus.IN_PROGRESS,
                    null,
                    true,
                    "acceptance",
                    T0))));
  }

  /**
   * The child is itself a {@code running} row the sweep also visits — quiet by its own right, with
   * no child or approval to excuse it, so it is reported on its own row (spec §4's own "a stalled
   * child is reported itself"). What this test proves is narrower: the parent, whose excuse is that
   * very child, is not.
   */
  @Test
  void a_run_with_a_live_child_is_not_reported() {
    OrchestrationRecord parent = running("enzo");
    running(conductorConversation(), "enzo", parent.id(), 1);

    sweep.sweep(T0.plus(16, ChronoUnit.MINUTES));

    assertEquals(
        Optional.empty(),
        store.stalledSince(parent.id()),
        "a parent with a live child is quiet by design, not stalled");
    assertTrue(
        inbox.notices.stream().noneMatch(n -> n.text().contains(parent.id())),
        "the parent itself is never named in a notice");
  }

  /** Rule 3, the same deadlock seen from the sweep: a child asking its parent is not work. */
  @Test
  void a_parent_whose_only_live_child_is_asking_it_is_reported() {
    OrchestrationRecord parent = running("enzo");
    OrchestrationRecord child = running(conductorConversation(), "enzo", parent.id(), 1);
    jdbc.update(
        "UPDATE orchestrations SET state = 'asking', pending_cap = 'turn_cap'" + " WHERE id = ?",
        child.id());

    assertEquals(1, sweep.sweep(T0.plus(16, ChronoUnit.MINUTES)));
    assertEquals(Optional.of(T0), store.stalledSince(parent.id()));
  }

  @Test
  void a_parent_whose_child_asks_the_person_whether_it_goes_on_is_still_excused() {
    OrchestrationRecord parent = running("enzo");
    OrchestrationRecord child = running(conductorConversation(), "enzo", parent.id(), 1);
    jdbc.update(
        "UPDATE orchestrations SET state = 'asking', pending_cap = 'stuck'" + " WHERE id = ?",
        child.id());

    assertEquals(0, sweep.sweep(T0.plus(16, ChronoUnit.MINUTES)));
  }

  @Test
  void an_excused_run_that_was_already_marked_stalled_is_cleared() {
    OrchestrationRecord parent = running("enzo");
    assertEquals(
        1, sweep.sweep(T0.plus(16, ChronoUnit.MINUTES)), "quiet and not yet excused: reported");
    assertEquals(Optional.of(T0), store.stalledSince(parent.id()));

    running(conductorConversation(), "enzo", parent.id(), 1);
    sweep.sweep(T0.plus(17, ChronoUnit.MINUTES));

    assertEquals(
        Optional.empty(),
        store.stalledSince(parent.id()),
        "a live child excuses it now, so the earlier mark no longer holds");
  }

  // --- quietSince walks the whole delegation tree -------------------------------------------

  @Test
  void a_delegation_entry_five_minutes_ago_keeps_a_conductor_from_being_reported() {
    String conductor = conductorConversation();
    OrchestrationRecord run = running(conductor, "enzo", null, 0);

    clock.set(T0.plus(10, ChronoUnit.MINUTES));
    entries.append(
        conductor,
        1,
        LoggedEntry.utterance("the conductor's own last entry", Speaker.person(null)));

    String delegated =
        conversations
            .log(Origin.DELEGATION, Home.of("story"), "code_implementation", conductor, null)
            .id();
    clock.set(T0.plus(35, ChronoUnit.MINUTES));
    entries.append(
        delegated,
        1,
        LoggedEntry.utterance("the delegated child, five minutes ago", Speaker.person(null)));

    Instant now = T0.plus(40, ChronoUnit.MINUTES);
    assertEquals(
        0,
        sweep.sweep(now),
        "the conductor's own entry is thirty minutes old, but the delegated child's is"
            + " only five, and quietSince reads the whole tree");
    assertTrue(inbox.notices.isEmpty());
    assertEquals(Optional.empty(), store.stalledSince(run.id()));
  }

  // --- no account -----------------------------------------------------------------------------

  @Test
  void a_run_with_no_caller_handle_is_marked_but_not_delivered() {
    OrchestrationRecord run = running(null);

    assertEquals(
        1,
        sweep.sweep(T0.plus(16, ChronoUnit.MINUTES)),
        "still counted as newly reported, just with nobody to tell");

    assertTrue(inbox.notices.isEmpty(), "no account to deliver to");
    assertEquals(Optional.of(T0), store.stalledSince(run.id()), "the mark is still written");
  }

  // --- fix round 1, #2: one run's failure does not stop the sweep --------------------------

  /**
   * A refused delivery is this run's own problem, not the sweep's: the other run is still judged
   * and reported in the same pass, and the failed run's mark is put back so the next sweep — not
   * this one — tries to tell it again, rather than leaving it silently marked reported to nobody.
   */
  @Test
  void a_delivery_failure_for_one_run_does_not_stop_the_others_and_clears_its_own_mark() {
    OrchestrationRecord broken = running("broken");
    OrchestrationRecord fine = running("enzo");
    ThrowingInbox throwing = new ThrowingInbox("broken");
    StallSweep isolated =
        new StallSweep(
            store,
            conversation -> openApprovalsByConversation.getOrDefault(conversation, List.of()),
            throwing,
            FIFTEEN_MINUTES);

    int reported = isolated.sweep(T0.plus(16, ChronoUnit.MINUTES));

    assertEquals(1, reported, "only the run whose delivery actually succeeded is counted");
    assertEquals(1, throwing.notices.size());
    assertEquals("enzo", throwing.notices.get(0).handle());
    assertEquals(
        Optional.empty(),
        store.stalledSince(broken.id()),
        "marked, then unmarked once its own delivery failed, so the next sweep retries it");
    assertEquals(
        Optional.of(T0),
        store.stalledSince(fine.id()),
        "the other run's own mark is untouched by its neighbour's failure");
  }

  // --- fix round 1, #3: only running() is ever visited --------------------------------------

  @Test
  void a_quiet_run_that_is_asking_is_not_reported() {
    OrchestrationRecord run = running("enzo");
    assertTrue(store.moveTo(run.id(), OrchestrationState.RUNNING, OrchestrationState.ASKING));

    assertEquals(0, sweep.sweep(T0.plus(16, ChronoUnit.MINUTES)));

    assertTrue(inbox.notices.isEmpty());
    assertEquals(Optional.empty(), store.stalledSince(run.id()));
  }

  @Test
  void a_quiet_run_that_is_waiting_on_a_child_is_not_reported() {
    OrchestrationRecord run = running("enzo");
    OrchestrationRecord child = running(conductorConversation(), "enzo", run.id(), 1);
    assertTrue(store.waitFor(run.id(), child.id()));

    sweep.sweep(T0.plus(16, ChronoUnit.MINUTES));

    assertEquals(
        Optional.empty(),
        store.stalledSince(run.id()),
        "a waiting run is not among running(), so the sweep never visits its row");
    assertTrue(
        inbox.notices.stream().noneMatch(n -> n.text().contains(run.id())),
        "the waiting run itself is never named in a notice");
  }

  // --- the record ---------------------------------------------------------------------------

  @Test
  void a_stall_is_recorded_once_with_the_notice_the_person_reads() {
    List<String> recorded = new ArrayList<>();
    StallSweep recording =
        new StallSweep(
            store,
            conversation -> openApprovalsByConversation.getOrDefault(conversation, List.of()),
            inbox,
            FIFTEEN_MINUTES,
            new OrchestrationRecorder() {
              @Override
              public void stalled(OrchestrationRecord run, String notice) {
                recorded.add(run.id() + " " + notice);
              }
            });
    OrchestrationRecord run = running("enzo");

    recording.sweep(T0.plus(16, ChronoUnit.MINUTES));
    recording.sweep(T0.plus(20, ChronoUnit.MINUTES));

    assertEquals(List.of(run.id() + " has done nothing for 15 minutes"), recorded);
  }

  // --- fakes ------------------------------------------------------------------------------

  private static final class RecordingInbox implements InboxPort {
    record Notice(String handle, String kind, String text) {}

    final List<Notice> notices = new ArrayList<>();

    @Override
    public void notify(String handle, String kind, String text) {
      notices.add(new Notice(handle, kind, text));
    }
  }

  /**
   * Delivers normally for every handle but {@code failsFor}, which it refuses instead — fix round
   * 1, #2's own fake for a single run's delivery going wrong mid-sweep.
   */
  private static final class ThrowingInbox implements InboxPort {
    final List<RecordingInbox.Notice> notices = new ArrayList<>();
    private final String failsFor;

    ThrowingInbox(String failsFor) {
      this.failsFor = failsFor;
    }

    @Override
    public void notify(String handle, String kind, String text) {
      if (handle.equals(failsFor)) {
        throw new IllegalStateException("delivery is down for " + handle);
      }
      notices.add(new RecordingInbox.Notice(handle, kind, text));
    }
  }
}
