package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.Pictures;
import io.aeyer.plowshare.server.agents.Runs;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.approvals.ApprovalDelivery;
import io.aeyer.plowshare.server.approvals.ConductorContinuation;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.RunApprovalStore;
import io.aeyer.plowshare.server.archive.ProjectStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class ApprovalFramesTest {

  private RunApprovalStore store;
  private Callers callers;
  private ProjectStore projects;
  private Runs runs;
  private ApprovalDelivery delivery;
  private FrameRouter router;
  private ApprovalFrames frames;
  private io.aeyer.plowshare.server.agents.RecordingLogStages told;

  /** What the orchestration engine says it continued: conversation and utterance, in order. */
  private final List<String> continuedByTheEngine = new java.util.ArrayList<>();

  /** The conductor conversations the engine claims as its own. */
  private final java.util.Set<String> conducted = new java.util.HashSet<>();

  /** The state of each approval the engine was handed, in order. */
  private final List<String> statesTheEngineSaw = new java.util.ArrayList<>();

  private static final RunApproval ASKED =
      new RunApproval(
          "apr_1",
          7L,
          "cnv_1",
          "cnv_1",
          "coder",
          "local",
          List.of("./gradlew", "test", "--tests", "Foo"),
          "/repo",
          null,
          RunApproval.ASKED,
          null,
          null,
          null,
          null,
          Instant.parse("2026-09-15T09:00:00Z"));

  @BeforeEach
  void setUp() {
    store = mock(RunApprovalStore.class);
    projects = mock(ProjectStore.class);
    runs = mock(Runs.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<ConductorContinuation> conductors = mock(ObjectProvider.class);
    when(conductors.getIfAvailable(any()))
        .thenReturn(
            (approval, utterance) -> {
              if (!conducted.contains(approval.conversation())) {
                return false;
              }
              continuedByTheEngine.add(approval.conversation());
              continuedByTheEngine.add(utterance);
              statesTheEngineSaw.add(approval.state());
              return true;
            });
    frames =
        new ApprovalFrames(
            store,
            projects,
            callers = mock(Callers.class),
            runs,
            mock(Pictures.class),
            delivery = mock(ApprovalDelivery.class),
            conductors);
    told = new io.aeyer.plowshare.server.agents.RecordingLogStages();
    frames.useLogStages(told);
    router = new FrameRoutingConfig().frameRouter(List.of(frames));
    when(store.find("apr_1")).thenReturn(Optional.of(ASKED));
  }

  @Test
  void message_approvals_use_the_transport_continuation_instead_of_an_ordinary_run() {
    var messaging = mock(io.aeyer.plowshare.server.agents.Messaging.class);
    frames.useMessaging(messaging);
    when(store.find("apr_1")).thenReturn(Optional.of(ASKED));
    when(store.allow(eq("apr_1"), eq("once"), any(), any())).thenReturn(true);
    when(messaging.continueApproved(any(), anyString())).thenReturn(true);
    Outcome answered =
        frames.answer(java.util.Map.of("id", "apr_1", "decision", "once"), new Asking("session"));
    assertEquals(Code.OK, answered.code());
    verify(messaging).continueApproved(any(), anyString());
    verify(runs, never()).continueApproved(anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  void an_answer_checks_project_access_before_changing_the_approval() {
    org.mockito.Mockito.doThrow(new io.aeyer.plowshare.server.faults.CallerFault("not a member"))
        .when(callers)
        .requireConversationProject("cnv_1", "enzo");
    assertEquals(
        Code.BAD_REQUEST,
        route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_1\",\"decision\":\"deny\"}").code());
    verify(store, org.mockito.Mockito.never()).deny(any(), any());
    verify(runs, org.mockito.Mockito.never()).start(any(), any());
  }

  private Outcome route(String type, String json) {
    return router.route(FrameParity.frame(type, json), new Asking("tab-1", "enzo"));
  }

  private Outcome routeAs(String handle, String type, String json) {
    return router.route(FrameParity.frame(type, json), new Asking("tab-1", handle));
  }

  @Test
  void answering_records_the_decision_and_continues_the_conversation_with_what_was_decided() {
    when(store.allow("apr_1", RunApproval.CONVERSATION, null, "enzo")).thenReturn(true);
    when(runs.start(any(), any())).thenReturn(new Runs.Started("job_9", "coder"));

    Outcome outcome =
        route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_1\",\"decision\":\"conversation\"}");

    assertEquals(Code.OK, outcome.code());
    assertEquals(
        new ApprovalFrames.Answered("apr_1", RunApproval.ALLOWED, "job_9", false, null),
        outcome.payload());
    ArgumentCaptor<Runs.Ask> asked = ArgumentCaptor.forClass(Runs.Ask.class);
    verify(runs).start(asked.capture(), any());
    assertEquals("coder", asked.getValue().agent());
    assertEquals("cnv_1", asked.getValue().conversation());
    assertEquals(
        "tab-1", asked.getValue().session(), "the answering socket's files are the ones reached");
    assertTrue(
        asked.getValue().task().startsWith("[approval] The person allowed"),
        asked.getValue().task());
    assertEquals(Speaker.approval("apr_1"), asked.getValue().speaker());
    assertEquals("enzo", asked.getValue().callerHandle());
  }

  @Test
  void an_inbox_question_is_owned_and_continues_the_machine_conversation() {
    RunApproval unattended =
        new RunApproval(
            "apr_event",
            7L,
            "cnv_event",
            "cnv_event",
            "enzo",
            "coder",
            "server",
            List.of("./gradlew", "test"),
            "/repo",
            null,
            RunApproval.ASKED,
            null,
            null,
            null,
            null,
            null,
            Instant.now());
    when(store.find("apr_event")).thenReturn(Optional.of(unattended));
    when(store.allow("apr_event", RunApproval.ONCE, null, "enzo")).thenReturn(true);
    when(runs.continueApproved(
            eq("cnv_event"), eq("coder"), anyString(), eq(Speaker.approval("apr_event")), any()))
        .thenReturn(new Runs.Started("job_10", "coder"));

    Outcome outcome =
        route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_event\",\"decision\":\"once\"}");

    assertEquals(Code.OK, outcome.code());
    verify(runs)
        .continueApproved(
            eq("cnv_event"), eq("coder"), anyString(), eq(Speaker.approval("apr_event")), any());
    verify(runs, never()).start(any(), any());
    verify(callers, never()).callerForConversation("cnv_event", "tab-1");
  }

  /**
   * Measured 2026-09-25: an approval raised under an orchestration is written against the
   * conductor's conversation, and the ordinary continuation would look its agent — the
   * orchestration's name — up as an agent, and report the ending to nobody that routes it.
   */
  /**
   * Final review F2: the engine is handed the approval as the answer left it, so it can tell a
   * person's allowing of a run's check from their denying of it and say which to the conductor.
   */
  @Test
  void the_engine_is_handed_the_approval_in_the_state_the_answer_left_it() {
    RunApproval underAConductor =
        new RunApproval(
            "apr_orc",
            7L,
            "cnv_conductor",
            "cnv_conductor",
            "enzo",
            "code_implementation",
            "local",
            List.of("pytest", "-q"),
            "/repo",
            null,
            RunApproval.ASKED,
            null,
            null,
            null,
            null,
            null,
            Instant.now());
    conducted.add("cnv_conductor");
    when(store.find("apr_orc")).thenReturn(Optional.of(underAConductor));
    when(store.deny("apr_orc", "enzo")).thenReturn(true);
    when(store.allow("apr_orc", RunApproval.ONCE, null, "enzo")).thenReturn(true);

    route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_orc\",\"decision\":\"deny\"}");
    route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_orc\",\"decision\":\"once\"}");

    assertEquals(List.of(RunApproval.DENIED, RunApproval.ALLOWED), statesTheEngineSaw);
  }

  @Test
  void an_approval_raised_under_a_conductor_continues_it_through_the_engine() {
    RunApproval underAConductor =
        new RunApproval(
            "apr_orc",
            7L,
            "cnv_conductor",
            "cnv_coder",
            "enzo",
            "code_implementation",
            "local",
            List.of("pytest", "-q"),
            "/repo",
            null,
            RunApproval.ASKED,
            null,
            null,
            null,
            null,
            null,
            Instant.now());
    conducted.add("cnv_conductor");
    when(store.find("apr_orc")).thenReturn(Optional.of(underAConductor));
    when(store.allow("apr_orc", RunApproval.ONCE, null, "enzo")).thenReturn(true);

    Outcome outcome =
        route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_orc\",\"decision\":\"once\"}");

    assertEquals(Code.OK, outcome.code());
    assertEquals("cnv_conductor", continuedByTheEngine.get(0));
    assertTrue(continuedByTheEngine.get(1).startsWith("[approval] The person allowed"));
    verify(runs, never())
        .continueApproved(anyString(), anyString(), anyString(), any(Speaker.class), any());
    verify(runs, never()).start(any(), any());
  }

  @Test
  void listing_mine_names_every_open_question_on_the_account() {
    when(store.openFor("enzo")).thenReturn(List.of(ASKED));

    Outcome mine = route(FrameTypes.APPROVAL_LIST, "{\"mine\":true}");

    assertEquals(Code.OK, mine.code());
    assertEquals(
        List.of("apr_1"),
        ((ApprovalFrames.Listed) mine.payload())
            .approvals().stream().map(ApprovalFrames.View::id).toList());
    assertEquals(
        Code.BAD_REQUEST,
        route(FrameTypes.APPROVAL_LIST, "{\"mine\":true,\"conversation\":\"cnv_1\"}").code());
  }

  @Test
  void another_account_cannot_answer_an_inbox_question() {
    RunApproval unattended =
        new RunApproval(
            "apr_event",
            7L,
            "cnv_event",
            "cnv_event",
            "enzo",
            "coder",
            "server",
            List.of("./gradlew", "test"),
            "/repo",
            null,
            RunApproval.ASKED,
            null,
            null,
            null,
            null,
            null,
            Instant.now());
    when(store.find("apr_event")).thenReturn(Optional.of(unattended));

    Outcome outcome =
        routeAs("mara", FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_event\",\"decision\":\"once\"}");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    verify(store, never()).allow(anyString(), anyString(), any(), anyString());
  }

  /**
   * V67: an acceptance set is listed as one approval carrying every command, and answered once or
   * for the conversation — never for the project, whose one prefix covers one command.
   */
  @Test
  void a_set_is_listed_with_its_commands_and_cannot_be_allowed_for_the_project() {
    List<List<String>> set = List.of(List.of("./gradlew", "test"), List.of("./gradlew", "run"));
    RunApproval asked =
        new RunApproval(
            "apr_set",
            7L,
            "cnv_1",
            "cnv_1",
            "enzo",
            "implement_specification",
            "local",
            List.of(),
            "/repo",
            "reason",
            RunApproval.ASKED,
            null,
            null,
            null,
            null,
            null,
            Instant.now(),
            set,
            "unsure");
    when(store.openFor("enzo")).thenReturn(List.of(asked));
    when(store.find("apr_set")).thenReturn(Optional.of(asked));

    ApprovalFrames.View view =
        ((ApprovalFrames.Listed) route(FrameTypes.APPROVAL_LIST, "{\"mine\":true}").payload())
            .approvals()
            .get(0);

    assertEquals(set, view.commands());
    assertEquals(List.of(), view.command());
    assertEquals("unsure", view.judged());

    Outcome project =
        route(
            FrameTypes.APPROVAL_ANSWER,
            "{\"id\":\"apr_set\",\"decision\":\"project\",\"prefix\":[\"./gradlew\"]}");

    assertEquals(Code.BAD_REQUEST, project.code());
    verify(store, never()).allow(anyString(), anyString(), any(), anyString());
  }

  @Test
  void a_project_approval_needs_a_prefix_that_leads_the_command() {
    Outcome wrong =
        route(
            FrameTypes.APPROVAL_ANSWER,
            "{\"id\":\"apr_1\",\"decision\":\"project\",\"prefix\":[\"./gradlew\",\"publish\"]}");

    assertEquals(Code.BAD_REQUEST, wrong.code());
    verify(store, never()).allow(anyString(), anyString(), any(), anyString());

    when(store.allow("apr_1", RunApproval.PROJECT, List.of("./gradlew", "test"), "enzo"))
        .thenReturn(true);
    when(runs.start(any(), any())).thenReturn(new Runs.Started("job_9", "coder"));
    assertEquals(
        Code.OK,
        route(
                FrameTypes.APPROVAL_ANSWER,
                "{\"id\":\"apr_1\",\"decision\":\"project\",\"prefix\":[\"./gradlew\",\"test\"]}")
            .code());
  }

  @Test
  void a_question_already_answered_is_refused_and_nothing_continues() {
    when(store.deny("apr_1", "enzo")).thenReturn(false);

    Outcome outcome = route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_1\",\"decision\":\"deny\"}");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    verify(runs, never()).start(any(), any());
  }

  /**
   * An acceptance approval the harness withdrew when its section changed (answered_by {@code
   * superseded}) is not "already answered: it is denied" — nobody denied it; it is gone.
   */
  @Test
  void answering_a_withdrawn_acceptance_approval_says_it_was_superseded() {
    RunApproval withdrawn =
        new RunApproval(
            "apr_1",
            7L,
            "cnv_1",
            "cnv_1",
            "coder",
            "local",
            List.of("python", "-m", "rpg.main"),
            "/repo",
            null,
            RunApproval.DENIED,
            null,
            null,
            RunApproval.SUPERSEDED,
            Instant.parse("2026-09-15T09:01:00Z"),
            Instant.parse("2026-09-15T09:00:00Z"));
    // Asked when the answer is read, withdrawn by the time the refusal reads it back.
    when(store.find("apr_1")).thenReturn(Optional.of(ASKED)).thenReturn(Optional.of(withdrawn));
    when(store.allow("apr_1", RunApproval.ONCE, null, "enzo")).thenReturn(false);

    Outcome outcome = route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_1\",\"decision\":\"once\"}");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    assertEquals(
        "approval apr_1 was superseded by a changed acceptance section; nothing was" + " changed",
        outcome.said());
    verify(runs, never()).start(any(), any());
  }

  /** An approval withdrawn because its run ended (answered_by {@code run ended}) says so. */
  @Test
  void answering_an_approval_withdrawn_as_its_run_ended_says_so() {
    RunApproval withdrawn =
        new RunApproval(
            "apr_1",
            7L,
            "cnv_1",
            "cnv_1",
            "coder",
            "local",
            List.of("make", "check"),
            "/repo",
            null,
            RunApproval.DENIED,
            null,
            null,
            RunApproval.RUN_ENDED,
            Instant.parse("2026-09-15T09:01:00Z"),
            Instant.parse("2026-09-15T09:00:00Z"));
    when(store.find("apr_1")).thenReturn(Optional.of(ASKED)).thenReturn(Optional.of(withdrawn));
    when(store.allow("apr_1", RunApproval.ONCE, null, "enzo")).thenReturn(false);

    Outcome outcome = route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_1\",\"decision\":\"once\"}");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    assertEquals(
        "approval apr_1 was withdrawn: the run it was asked for has ended; nothing was"
            + " changed",
        outcome.said());
    verify(runs, never()).start(any(), any());
  }

  @Test
  void a_busy_conversation_keeps_the_decision_and_says_so() {
    when(store.deny("apr_1", "enzo")).thenReturn(true);
    when(runs.start(any(), any()))
        .thenThrow(new Turn.Refused("conversation cnv_1 already has a turn in flight"));

    Outcome outcome = route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_1\",\"decision\":\"deny\"}");

    ApprovalFrames.Answered answered = (ApprovalFrames.Answered) outcome.payload();
    assertTrue(answered.busy());
    assertEquals(RunApproval.DENIED, answered.state());
  }

  @Test
  void an_unknown_decision_answers_nothing() {
    assertEquals(
        Code.BAD_REQUEST,
        route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_1\",\"decision\":\"always\"}").code());
  }

  @Test
  void listing_names_exactly_one_of_a_conversation_or_a_project() {
    when(store.open("cnv_1")).thenReturn(List.of(ASKED));
    when(projects.id("payments")).thenReturn(7L);

    Outcome open = route(FrameTypes.APPROVAL_LIST, "{\"conversation\":\"cnv_1\"}");
    assertEquals(
        List.of("apr_1"),
        ((ApprovalFrames.Listed) open.payload())
            .approvals().stream().map(ApprovalFrames.View::id).toList());
    assertEquals(
        List.of("./gradlew", "test"),
        ((ApprovalFrames.Listed) open.payload()).approvals().get(0).defaultPrefix());
    assertEquals(Code.OK, route(FrameTypes.APPROVAL_LIST, "{\"project\":\"payments\"}").code());
    verify(store).standing(eq(7L));
    assertEquals(Code.BAD_REQUEST, route(FrameTypes.APPROVAL_LIST, "{}").code());
  }

  @Test
  void revoking_answers_whether_there_was_a_standing_approval() {
    when(store.revoke("apr_1")).thenReturn(true);

    Outcome outcome = route(FrameTypes.APPROVAL_REVOKE, "{\"id\":\"apr_1\"}");

    assertEquals(new ApprovalFrames.Revoked("apr_1", true), outcome.payload());
    verify(callers).callerForConversation("cnv_1", "tab-1");
  }

  @Test
  void listing_a_projects_approvals_passes_the_same_check_a_run_naming_it_does() {
    when(projects.id("payments")).thenReturn(7L);

    route(FrameTypes.APPROVAL_LIST, "{\"project\":\"payments\"}");

    verify(callers).callerFor("payments", "tab-1", "enzo");
  }

  // --- approval.post (spec 2026-09-28-hooks-reach-the-log, slice 3) -------------------------

  @Test
  void an_answer_tells_approval_post_after_the_store_changed_and_before_the_run_goes_on() {
    when(store.allow("apr_1", RunApproval.CONVERSATION, null, "enzo")).thenReturn(true);
    List<String> toldWhenContinued = new java.util.ArrayList<>();
    when(runs.start(any(), any()))
        .thenAnswer(
            invocation -> {
              toldWhenContinued.addAll(told.lines);
              return new Runs.Started("job_9", "coder");
            });

    route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_1\",\"decision\":\"conversation\"}");

    assertEquals(
        List.of("approval cnv_1 apr_1 allow conversation"),
        toldWhenContinued,
        "told before the continuation starts");
    assertEquals(List.of("approval cnv_1 apr_1 allow conversation"), told.lines);
  }

  @Test
  void a_denial_tells_approval_post_with_no_scope() {
    when(store.deny("apr_1", "enzo")).thenReturn(true);
    when(runs.start(any(), any())).thenReturn(new Runs.Started("job_9", "coder"));

    route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_1\",\"decision\":\"deny\"}");

    assertEquals(List.of("approval cnv_1 apr_1 deny null"), told.lines);
  }

  @Test
  void an_answer_that_changed_nothing_tells_nobody() {
    when(store.allow("apr_1", RunApproval.ONCE, null, "enzo")).thenReturn(false);
    when(store.find("apr_1"))
        .thenReturn(Optional.of(ASKED))
        .thenReturn(
            Optional.of(
                new RunApproval(
                    "apr_1",
                    7L,
                    "cnv_1",
                    "cnv_1",
                    "coder",
                    "local",
                    List.of("./gradlew", "test"),
                    "/repo",
                    null,
                    RunApproval.DENIED,
                    null,
                    null,
                    "someone",
                    Instant.EPOCH,
                    Instant.EPOCH)));

    route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_1\",\"decision\":\"once\"}");

    assertEquals(List.of(), told.lines);
  }

  @Test
  void a_revoke_tells_approval_post_only_when_something_was_revoked() {
    RunApproval standing =
        new RunApproval(
            "apr_p",
            7L,
            "cnv_1",
            "cnv_1",
            "coder",
            "local",
            List.of("./gradlew", "test"),
            "/repo",
            null,
            RunApproval.ALLOWED,
            RunApproval.PROJECT,
            List.of("./gradlew"),
            "enzo",
            Instant.EPOCH,
            Instant.EPOCH);
    when(store.find("apr_p")).thenReturn(Optional.of(standing));
    when(store.revoke("apr_p")).thenReturn(true, false);

    route(FrameTypes.APPROVAL_REVOKE, "{\"id\":\"apr_p\"}");
    route(FrameTypes.APPROVAL_REVOKE, "{\"id\":\"apr_p\"}");

    assertEquals(List.of("approval cnv_1 apr_p revoke project"), told.lines);
  }

  /**
   * A LogStages that breaks its no-throw contract must not leave a person's answer stored and the
   * run never continued: the answer goes on, the revoke still says it revoked, and the failure is a
   * warning (spec 2026-09-28-hooks-reach-the-log §2.4, approval.post fails open).
   */
  @Test
  void a_log_stage_that_throws_on_approval_post_is_warned_about_and_the_run_still_goes_on() {
    frames.useLogStages(
        new io.aeyer.plowshare.server.agents.LogStages() {
          @Override
          public void approvalAnswered(String log, String approval, String decision, String scope) {
            throw new IllegalStateException("the hooks are not well");
          }
        });
    when(store.allow("apr_1", RunApproval.CONVERSATION, null, "enzo")).thenReturn(true);
    when(runs.start(any(), any())).thenReturn(new Runs.Started("job_9", "coder"));
    RunApproval standing =
        new RunApproval(
            "apr_p",
            7L,
            "cnv_1",
            "cnv_1",
            "coder",
            "local",
            List.of("./gradlew", "test"),
            "/repo",
            null,
            RunApproval.ALLOWED,
            RunApproval.PROJECT,
            List.of("./gradlew"),
            "enzo",
            Instant.EPOCH,
            Instant.EPOCH);
    when(store.find("apr_p")).thenReturn(Optional.of(standing));
    when(store.revoke("apr_p")).thenReturn(true);
    ch.qos.logback.classic.Logger framesLog =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ApprovalFrames.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
        new ch.qos.logback.core.read.ListAppender<>();
    captured.start();
    framesLog.addAppender(captured);
    Outcome answered;
    Outcome revoked;
    try {
      answered =
          route(FrameTypes.APPROVAL_ANSWER, "{\"id\":\"apr_1\",\"decision\":\"conversation\"}");
      revoked = route(FrameTypes.APPROVAL_REVOKE, "{\"id\":\"apr_p\"}");
    } finally {
      framesLog.detachAppender(captured);
    }

    assertEquals(
        new ApprovalFrames.Answered("apr_1", RunApproval.ALLOWED, "job_9", false, null),
        answered.payload());
    verify(runs).start(any(), any());
    assertEquals(new ApprovalFrames.Revoked("apr_p", true), revoked.payload());
    List<String> warned =
        captured.list.stream()
            .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
            .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
            .toList();
    assertEquals(2, warned.size(), warned.toString());
    assertTrue(
        warned.get(0).contains("apr_1") && warned.get(0).contains("the hooks are not well"),
        warned.get(0));
    assertTrue(
        warned.get(1).contains("apr_p") && warned.get(1).contains("the hooks are not well"),
        warned.get(1));
  }
}
