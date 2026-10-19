package io.aeyer.plowshare.server.acting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ActingContext;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.ws.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Strict transport decoding completes before typed work admission or job effects. */
class ActingWorkFramesTest {
  final ActingContexts contexts = mock(ActingContexts.class);
  final ActingHistory history = mock(ActingHistory.class);
  final ActingWork work = mock(ActingWork.class);
  final JobStore jobs = mock(JobStore.class);
  final ActingRequests requests = new ActingRequests(contexts, history, work, jobs);
  final ActingContext.Selection selection = new ActingContext.Selection(UUID.randomUUID());
  final String actor = "@service/" + UUID.randomUUID();
  final Asking asking = new Asking("session", actor, "physical");
  final RequestAuthority.Delegated authority =
      new RequestAuthority.Delegated(
          actor,
          "alice",
          new RequestAuthority.Delegation(
              "bridge",
              "users",
              UUID.randomUUID(),
              new ActingScope(ProjectRole.CONTRIBUTOR, Set.of(), true)));

  ActingWorkFramesTest() {
    when(contexts.resolve(any(), eq(selection.context()))).thenReturn(authority);
  }

  @Test
  void malformed_null_extra_and_wrong_typed_fields_never_reach_admission() {
    assertThrows(
        CallerFault.class,
        () -> requests.route(FrameTypes.ACTING_TURN_START, null, selection, asking));
    var valid =
        Map.<String, Object>of(
            "requestId", UUID.randomUUID().toString(), "agent", "chat", "task", "Hello");
    var invalid = new ArrayList<Map<String, Object>>();
    invalid.add(Map.of());
    for (var change :
        List.of(
            Map.<String, Object>of("project", "research"),
            Map.<String, Object>of("session", "bridge-files"),
            Map.<String, Object>of("agent", "bad name"),
            Map.<String, Object>of("task", " "),
            Map.<String, Object>of("task", 1),
            Map.<String, Object>of("conversation", "other"),
            Map.<String, Object>of("requestId", "bad"))) {
      var values = new HashMap<>(valid);
      values.putAll(change);
      invalid.add(values);
    }
    for (var values : invalid)
      assertThrows(
          CallerFault.class,
          () -> requests.route(FrameTypes.ACTING_TURN_START, values, selection, asking));
    verifyNoInteractions(work, jobs);
  }

  @Test
  void receipt_recovery_only_reads_and_retains_the_selected_authority() {
    UUID request = UUID.randomUUID();
    var receipt =
        new ActingWork.Receipt(
            request, "cnv_ABC123", "chat", ActingWork.State.STARTED, "job_ABC123");
    when(work.receipt(authority, request)).thenReturn(receipt);
    assertSame(
        receipt,
        requests
            .route(
                FrameTypes.ACTING_TURN_RECEIPT,
                Map.of("requestId", request.toString()),
                selection,
                asking)
            .payload());
    verify(work).receipt(authority, request);
    verify(work, never()).start(any(), any());
    verifyNoInteractions(jobs);
  }

  @Test
  void refused_retained_job_authority_prevents_cancellation_or_read() {
    doThrow(new CallerFault("denied")).when(work).requireJob(any(), anyString(), any());
    for (String operation : List.of(FrameTypes.JOB_CANCEL, FrameTypes.JOB_STATUS))
      assertThrows(
          CallerFault.class,
          () -> requests.route(operation, Map.of("job", "job_ABC123"), selection, asking));
    verifyNoInteractions(jobs);
  }

  @Test
  void cancellationNeedsWriteAndReadButNoCreationOrInference() {
    var job = mock(io.aeyer.plowshare.server.agents.Job.class);
    when(job.state()).thenReturn(io.aeyer.plowshare.server.agents.Job.State.RUNNING);
    when(jobs.getFor("job_ABC123", "alice")).thenReturn(job);
    requests.route(FrameTypes.JOB_CANCEL, Map.of("job", "job_ABC123"), selection, asking);
    var ordered = inOrder(work, jobs);
    ordered.verify(work).requireJob(authority, "job_ABC123", ActingPermission.WORK);
    ordered.verify(work).requireJob(authority, "job_ABC123", ActingPermission.READ);
    ordered.verify(jobs).getFor("job_ABC123", "alice");
    ordered.verify(jobs).cancel("job_ABC123");
    ordered.verify(work).requireJob(authority, "job_ABC123", ActingPermission.WORK);
    ordered.verify(work).requireJob(authority, "job_ABC123", ActingPermission.READ);
    verify(work, never()).requireJob(any(), anyString(), eq(ActingPermission.CONVERSATION_CREATE));
    verify(work, never()).requireJob(any(), anyString(), eq(ActingPermission.RUN_MODEL));
  }

  @Test
  void missingReadPreventsCancellationAndRevocationDuringCancelWithholdsTheView() {
    doThrow(new CallerFault("no read"))
        .when(work)
        .requireJob(authority, "job_ABC123", ActingPermission.READ);
    assertThrows(
        CallerFault.class,
        () ->
            requests.route(FrameTypes.JOB_CANCEL, Map.of("job", "job_ABC123"), selection, asking));
    verifyNoInteractions(jobs);
    reset(work);
    when(jobs.getFor("job_ABC123", "alice"))
        .thenReturn(mock(io.aeyer.plowshare.server.agents.Job.class));
    doAnswer(
            call -> {
              doThrow(new CallerFault("read revoked"))
                  .when(work)
                  .requireJob(authority, "job_ABC123", ActingPermission.READ);
              return null;
            })
        .when(jobs)
        .cancel("job_ABC123");
    assertThrows(
        CallerFault.class,
        () ->
            requests.route(FrameTypes.JOB_CANCEL, Map.of("job", "job_ABC123"), selection, asking));
    verify(jobs).cancel("job_ABC123");
    verify(jobs).getFor("job_ABC123", "alice");
  }

  @Test
  void unsupported_mutations_never_resolve_or_downgrade_selection() {
    clearInvocations(contexts);
    assertThrows(
        CallerFault.class, () -> requests.route(FrameTypes.AGENT_RUN, Map.of(), selection, asking));
    verifyNoInteractions(contexts, work, jobs);
  }
}
