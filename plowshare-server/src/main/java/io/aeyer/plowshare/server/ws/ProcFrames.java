package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.proc.ProcAudit;
import io.aeyer.plowshare.server.proc.ProcQuery;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Read-only process audit on the existing authenticated event socket. */
@Component
public class ProcFrames implements FrameArea {
  public static final Set<String> OPERATIONS =
      Set.of(
          FrameTypes.PROC_LIST,
          FrameTypes.PROC_INSPECT,
          FrameTypes.PROC_CALLS,
          FrameTypes.PROC_TOOLS,
          FrameTypes.PROC_QUEUES);
  private final ProcAudit audit;

  public ProcFrames(ProcAudit audit) {
    this.audit = audit;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.PROC_LIST,
            (p, a) -> Outcome.ok(audit.list(a.requireHandle(FrameTypes.PROC_LIST), query(p))),
        FrameTypes.PROC_INSPECT,
            (p, a) -> Outcome.ok(audit.inspect(a.requireHandle(FrameTypes.PROC_INSPECT), query(p))),
        FrameTypes.PROC_CALLS,
            (p, a) -> Outcome.ok(audit.calls(a.requireHandle(FrameTypes.PROC_CALLS), query(p))),
        FrameTypes.PROC_QUEUES,
            (p, a) -> Outcome.ok(audit.queues(a.requireHandle(FrameTypes.PROC_QUEUES), query(p))),
        FrameTypes.PROC_TOOLS,
            (p, a) -> Outcome.ok(audit.tools(a.requireHandle(FrameTypes.PROC_TOOLS), query(p))));
  }

  private ProcQuery query(Map<String, Object> payload) {
    var allowed =
        Set.of(
            "visibility",
            "project",
            "conversation",
            "root",
            "causation",
            "active",
            "after",
            "limit");
    for (var field : payload.entrySet()) {
      if (!allowed.contains(field.getKey()) || field.getValue() == null)
        throw new CallerFault("Unsupported PROC field");
      if (field.getKey().equals("active")) {
        if (!(field.getValue() instanceof Boolean))
          throw new CallerFault("PROC active must be boolean");
      } else if (field.getKey().equals("limit")) {
        if (!(field.getValue() instanceof Number n) || n.doubleValue() != n.intValue())
          throw new CallerFault("PROC limit must be an integer");
      } else if (!(field.getValue() instanceof String))
        throw new CallerFault("PROC selectors must be strings");
    }
    return Payloads.as(payload, ProcQuery.class, "proc");
  }
}
