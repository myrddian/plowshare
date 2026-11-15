package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.events.ApplicationInboxStore.Notice;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.orchestrations.OrchestrationRecord;
import io.aeyer.plowshare.server.orchestrations.OrchestrationState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationInboxesTest {
  @TempDir Path root;
  private final ApplicationPolicy policy = mock(ApplicationPolicy.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ApplicationInboxStore store = mock(ApplicationInboxStore.class);
  private final AccountPushes pushes = mock(AccountPushes.class);
  private final InboxStore personal = mock(InboxStore.class);
  private ApplicationInboxes inboxes;

  @BeforeEach
  void setup() throws Exception {
    Files.writeString(
        root.resolve("plowshare.json"),
        "{\"inbox\":{\"attentionOrchestrations\":[\"investigate_network\",\"assess_network_device\"]}}");
    var source =
        new ApplicationPolicy.Source(
            new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.APPLICATION, Map.of()),
            Optional.of(
                new ProjectRecord("privacy", root, List.of(), List.of(), "MANAGED", List.of())));
    when(policy.source("privacy")).thenReturn(source);
    when(store.project("privacy")).thenReturn(Optional.of(1L));
    when(store.backgroundProject("cnv_source")).thenReturn(Optional.of("privacy"));
    when(members.managers("privacy")).thenReturn(List.of("manager"));
    when(store.append(anyLong(), anyString(), any(), anyList(), any())).thenReturn(List.of());
    inboxes = new ApplicationInboxes(policy, members, store, pushes, personal, () -> Instant.EPOCH);
  }

  private OrchestrationRecord run(OrchestrationState state, String definition) {
    var run = mock(OrchestrationRecord.class);
    when(run.id()).thenReturn("orc_fixture");
    when(run.project()).thenReturn("privacy");
    when(run.callerHandle()).thenReturn("producer");
    when(run.conductorConversation()).thenReturn("cnv_source");
    when(run.definitionName()).thenReturn(definition);
    when(run.state()).thenReturn(state);
    when(run.endedAt()).thenReturn(Instant.EPOCH);
    return run;
  }

  @Test
  void a_resumed_runs_new_ending_has_its_own_delivery_identity() {
    var run = run(OrchestrationState.FAILED, "privacy_tick");
    inboxes.orchestration(run, "First attempt failed", null);
    when(run.endedAt()).thenReturn(Instant.EPOCH.plusSeconds(1));
    inboxes.orchestration(run, "Second attempt failed", null);
    verify(store)
        .append(
            eq(1L), eq("orchestration:orc_fixture:1970-01-01T00:00:00Z"), any(), anyList(), any());
    verify(store)
        .append(
            eq(1L), eq("orchestration:orc_fixture:1970-01-01T00:00:01Z"), any(), anyList(), any());
  }

  @Test
  void foreground_provenance_is_never_reassigned_to_application_managers() {
    when(store.backgroundProject("cnv_source")).thenReturn(Optional.empty());
    assertFalse(
        inboxes.orchestration(
            run(OrchestrationState.ASKING, "privacy_tick"), "Decide", "question:q1"));
    verify(store, never()).append(anyLong(), anyString(), any(), anyList(), any());
    verify(members, never()).managers(anyString());
  }

  @Test
  void only_current_managers_can_answer_published_background_questions() {
    var run = run(OrchestrationState.ASKING, "privacy_tick");
    when(members.mayManage("privacy", "manager")).thenReturn(true);
    assertTrue(inboxes.manages(run, "manager"));
    when(members.mayManage("privacy", "manager")).thenReturn(false);
    assertFalse(inboxes.manages(run, "manager"));
  }

  @Test
  void routine_completion_belongs_to_application_and_never_alerts_personal_accounts() {
    assertTrue(
        inboxes.orchestration(run(OrchestrationState.FINISHED, "privacy_tick"), "Available", null));
    verify(store)
        .append(
            eq(1L),
            eq("orchestration:orc_fixture:1970-01-01T00:00:00Z"),
            eq(
                new Notice(
                    "producer",
                    "orchestration",
                    "cnv_source",
                    "finished",
                    "Available",
                    null,
                    false)),
            eq(List.of()),
            eq(Instant.EPOCH));
    verify(members, never()).managers(anyString());
    verifyNoInteractions(pushes, personal);
  }

  @Test
  void failures_and_findings_escalate_to_current_managers() {
    inboxes.orchestration(run(OrchestrationState.FAILED, "privacy_tick"), "Failed", null);
    inboxes.orchestration(run(OrchestrationState.FINISHED, "investigate_network"), "Finding", null);
    verify(store, times(2))
        .append(
            eq(1L),
            anyString(),
            argThat(Notice::attention),
            eq(List.of("manager")),
            eq(Instant.EPOCH));
  }

  @Test
  void questions_always_escalate_even_from_routine_work() {
    inboxes.orchestration(
        run(OrchestrationState.ASKING, "privacy_tick"), "Please decide", "question:q1");
    verify(store)
        .append(
            eq(1L), eq("question:q1"), argThat(Notice::attention), eq(List.of("manager")), any());
  }

  @Test
  void orchestration_start_receipts_are_application_output_even_for_investigations() {
    var trigger =
        new TriggerRecord(
            "scan",
            "scan",
            "privacy",
            null,
            "coordinator",
            "/orchestration:investigate_network inspect",
            1,
            8,
            1,
            false,
            "installer");
    var firing = mock(FiringRecord.class);
    when(firing.id()).thenReturn("fir_fixture");
    assertTrue(
        inboxes.scheduled(
            trigger,
            firing,
            "cnv_event",
            new Outcome(Outcome.Ending.ANSWERED, "receipt", 0, 0, "")));
    verify(store)
        .append(
            eq(1L),
            eq("firing:fir_fixture"),
            argThat(notice -> !notice.attention()),
            eq(List.of()),
            any());
  }

  @Test
  void project_viewers_can_inspect_but_only_managers_can_acknowledge_the_shared_inbox() {
    when(store.page(1, false, 0, 50)).thenReturn(new InboxStore.Page(List.of(), 0));
    assertEquals(0, inboxes.page("reader", "privacy", false, 0, 50).unread());
    verify(members, times(2)).requireRole("privacy", "reader", ProjectRole.VIEWER);
    doThrow(new CallerFault("Denied"))
        .when(members)
        .requireRole("reader-project", "reader", ProjectRole.VIEWER);
    assertThrows(CallerFault.class, () -> inboxes.page("reader", "reader-project", false, 0, 50));
    inboxes.read("manager", "privacy", List.of("item"));
    verify(members).requireRole("privacy", "manager", ProjectRole.MANAGER);
  }

  @Test
  void publication_uses_current_managers_and_never_accepts_caller_selected_recipients() {
    UUID request = UUID.randomUUID();
    inboxes.publish("collector", "privacy", request, "failure", "Collection stopped");
    when(members.managers("privacy")).thenReturn(List.of("replacement"));
    inboxes.publish("collector", "privacy", UUID.randomUUID(), "finding", "Changed observation");
    verify(store)
        .append(
            eq(1L), eq("publication:collector:" + request), any(), eq(List.of("manager")), any());
    verify(store).append(eq(1L), anyString(), any(), eq(List.of("replacement")), any());
    assertThrows(
        CallerFault.class,
        () -> inboxes.publish("collector", "privacy", request, "routine", "quiet"));
  }

  @Test
  void malformed_or_unavailable_application_policy_does_not_fall_back_to_installer_inbox()
      throws Exception {
    Files.writeString(root.resolve("plowshare.json"), "{\"inbox\":{\"notify\":[\"installer\"]}}");
    assertThrows(
        CallerFault.class,
        () ->
            inboxes.orchestration(
                run(OrchestrationState.FINISHED, "privacy_tick"), "Available", null));
    verifyNoInteractions(pushes);
    verify(store, never()).append(anyLong(), anyString(), any(), anyList(), any());
  }
}
