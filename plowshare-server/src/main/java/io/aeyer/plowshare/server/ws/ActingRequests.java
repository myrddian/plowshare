package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.ActingContext;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.acting.ActingContexts;
import io.aeyer.plowshare.server.acting.ActingHistory;
import io.aeyer.plowshare.server.api.TurnView;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Closed transport admission. Adding a handler never implicitly enables it for delegated work. */
@Component
public final class ActingRequests {
  private final ActingContexts contexts;
  private final ActingHistory history;
  private final io.aeyer.plowshare.server.acting.ActingWork work;
  private final io.aeyer.plowshare.server.agents.JobStore jobs;

  @org.springframework.beans.factory.annotation.Autowired
  public ActingRequests(
      ActingContexts contexts,
      ActingHistory history,
      io.aeyer.plowshare.server.acting.ActingWork work,
      io.aeyer.plowshare.server.agents.JobStore jobs) {
    this.contexts = contexts;
    this.history = history;
    this.work = work;
    this.jobs = jobs;
  }

  public ActingRequests(ActingContexts contexts, ActingHistory history) {
    this(contexts, history, null, null);
  }

  public Outcome route(
      String operation,
      Map<String, Object> payload,
      ActingContext.Selection selection,
      Asking asking) {
    if (!Set.of(
            FrameTypes.CONVERSATION_TURNS,
            FrameTypes.ACTING_TURN_START,
            FrameTypes.ACTING_TURN_RECEIPT,
            FrameTypes.JOB_STATUS,
            FrameTypes.JOB_CANCEL)
        .contains(operation))
      throw new CallerFault("This operation does not support acting authority");
    if (payload == null) throw new CallerFault("Invalid acting request fields");
    var connection = ActingFrames.connection(asking);
    var authority = contexts.resolve(connection, selection.context());
    if (FrameTypes.CONVERSATION_TURNS.equals(operation)) {
      ActingFrames.fields(payload, Set.of("conversation"));
      var answer =
          history.turns(authority, ActingFrames.text(payload, "conversation")).stream()
              .map(TurnView::of)
              .toList();
      contexts.resolve(connection, selection.context());
      return Outcome.ok(answer);
    }
    if (work == null) throw new CallerFault("Acting work is unavailable");
    if (FrameTypes.ACTING_TURN_START.equals(operation)) {
      var keys = payload.keySet();
      if (!keys.equals(Set.of("requestId", "agent", "task"))
          && !keys.equals(Set.of("requestId", "agent", "task", "conversation")))
        throw new CallerFault("Invalid acting turn fields");
      io.aeyer.plowshare.server.acting.ActingWork.Start request;
      try {
        request =
            new io.aeyer.plowshare.server.acting.ActingWork.Start(
                ActingFrames.uuid(ActingFrames.text(payload, "requestId")),
                ActingFrames.text(payload, "agent"),
                ActingFrames.text(payload, "task"),
                keys.contains("conversation") ? ActingFrames.text(payload, "conversation") : null);
      } catch (IllegalArgumentException invalid) {
        throw new CallerFault("Invalid acting turn");
      }
      return Outcome.ok(work.start(authority, request));
    }
    if (FrameTypes.ACTING_TURN_RECEIPT.equals(operation)) {
      ActingFrames.fields(payload, Set.of("requestId"));
      var answer =
          work.receipt(authority, ActingFrames.uuid(ActingFrames.text(payload, "requestId")));
      contexts.resolve(connection, selection.context());
      return Outcome.ok(answer);
    }
    if (jobs == null) throw new CallerFault("Acting jobs are unavailable");
    ActingFrames.fields(payload, Set.of("job"));
    String job = ActingFrames.text(payload, "job");
    var permission =
        FrameTypes.JOB_CANCEL.equals(operation)
            ? io.aeyer.plowshare.server.acting.ActingPermission.WORK
            : io.aeyer.plowshare.server.acting.ActingPermission.READ;
    work.requireJob(authority, job, permission);
    // Cancellation returns the existing job view. Its WRITE check cannot stand in for the
    // delegated READ check on that disclosure, even if standard roles normally include both.
    if (FrameTypes.JOB_CANCEL.equals(operation))
      work.requireJob(authority, job, io.aeyer.plowshare.server.acting.ActingPermission.READ);
    var answer = jobs.getFor(job, authority.subject());
    if (FrameTypes.JOB_CANCEL.equals(operation)) jobs.cancel(job);
    work.requireJob(authority, job, permission);
    if (FrameTypes.JOB_CANCEL.equals(operation))
      work.requireJob(authority, job, io.aeyer.plowshare.server.acting.ActingPermission.READ);
    contexts.resolve(connection, selection.context());
    return Outcome.ok(io.aeyer.plowshare.server.api.JobView.of(answer));
  }

  public void disconnected(String connection) {
    contexts.disconnected(connection);
  }

  /** Output freshness only: never calls a work-start/cancel handler or rereads turn content. */
  public void requireDisclosure(
      io.aeyer.plowshare.protocol.frames.Envelope request, Asking asking, Outcome answer) {
    var connection = ActingFrames.connection(asking);
    var authority = contexts.resolve(connection, request.actingFor().context());
    if (FrameTypes.CONVERSATION_TURNS.equals(request.type()))
      history.requireReadable(authority, ActingFrames.text(request.payload(), "conversation"));
    else if (FrameTypes.JOB_STATUS.equals(request.type())
        || FrameTypes.JOB_CANCEL.equals(request.type()))
      work.requireJob(
          authority,
          ActingFrames.text(request.payload(), "job"),
          io.aeyer.plowshare.server.acting.ActingPermission.READ);
    else if (answer.payload()
        instanceof io.aeyer.plowshare.server.acting.ActingWork.Receipt receipt)
      work.requireReceipt(authority, receipt);
    contexts.resolve(connection, request.actingFor().context());
  }
}
