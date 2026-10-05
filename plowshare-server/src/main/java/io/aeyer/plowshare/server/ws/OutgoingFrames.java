package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.Outgoing;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.outgoing.OutgoingWork;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Sender-side external work. Remote protocols terminate in the adapter. */
@Component
public final class OutgoingFrames implements FrameArea {
  private final OutgoingWork work;
  private final Callers callers;

  public OutgoingFrames(OutgoingWork work, Callers callers) {
    this.work = work;
    this.callers = callers;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.OUTGOING_SEND,
        this::send,
        FrameTypes.OUTGOING_STATUS,
        this::status,
        FrameTypes.OUTGOING_CANCEL,
        this::cancel,
        FrameTypes.OUTGOING_PEERS,
        this::peers,
        FrameTypes.OUTGOING_ADVERTISE,
        this::advertise,
        FrameTypes.OUTGOING_CLAIM,
        this::claim,
        FrameTypes.OUTGOING_REPORT,
        this::report);
  }

  private String scoped(String type, String project, Asking asking) {
    String account = asking.requireHandle(type);
    callers.requireSession(asking.sessionId(), account);
    callers.requireProject(project, account);
    return account;
  }

  private Outgoing.Work owned(String type, Map<String, Object> payload, Asking asking) {
    var id = io.aeyer.plowshare.server.outgoing.OutgoingCodec.request(payload, Outgoing.Id.class);
    var found = work.get(asking.requireHandle(type), id.id());
    scoped(type, found.project(), asking);
    return found;
  }

  private Outcome send(Map<String, Object> payload, Asking asking) {
    var body =
        io.aeyer.plowshare.server.outgoing.OutgoingCodec.request(payload, Outgoing.Send.class);
    String account = scoped(FrameTypes.OUTGOING_SEND, body.project(), asking);
    if (body.conversation() != null)
      callers.requireConversationProject(body.conversation(), account);
    return new Outcome(Code.ACCEPTED, null, work.send(account, body));
  }

  private Outcome status(Map<String, Object> payload, Asking asking) {
    return Outcome.ok(owned(FrameTypes.OUTGOING_STATUS, payload, asking));
  }

  private Outcome cancel(Map<String, Object> payload, Asking asking) {
    var found = owned(FrameTypes.OUTGOING_CANCEL, payload, asking);
    return Outcome.ok(work.cancel(asking.handle(), found.id()));
  }

  private Outcome peers(Map<String, Object> payload, Asking asking) {
    var body =
        io.aeyer.plowshare.server.outgoing.OutgoingCodec.request(payload, Outgoing.PeerQuery.class);
    String project = body.project();
    return Outcome.ok(work.peers(scoped(FrameTypes.OUTGOING_PEERS, project, asking), project));
  }

  private Outcome advertise(Map<String, Object> payload, Asking asking) {
    var body =
        io.aeyer.plowshare.server.outgoing.OutgoingCodec.request(payload, Outgoing.Advertise.class);
    work.advertise(
        scoped(FrameTypes.OUTGOING_ADVERTISE, body.project(), asking),
        body.project(),
        body.peers(),
        body.agentCards());
    return Outcome.ok();
  }

  private Outcome claim(Map<String, Object> payload, Asking asking) {
    var body =
        io.aeyer.plowshare.server.outgoing.OutgoingCodec.request(payload, Outgoing.Claim.class);
    return Outcome.ok(
        work.claim(
            scoped(FrameTypes.OUTGOING_CLAIM, body.project(), asking),
            body.project(),
            body.peers(),
            asking.sessionId()));
  }

  private Outcome report(Map<String, Object> payload, Asking asking) {
    var body =
        io.aeyer.plowshare.server.outgoing.OutgoingCodec.request(payload, Outgoing.Report.class);
    if (body.id() == null)
      throw new io.aeyer.plowshare.server.faults.CallerFault("outgoing report requires id");
    owned(FrameTypes.OUTGOING_REPORT, Map.of("id", body.id()), asking);
    return Outcome.ok(work.report(asking.handle(), asking.sessionId(), body));
  }
}
