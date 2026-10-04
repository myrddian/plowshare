package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.RecordPageView;
import io.aeyer.plowshare.server.orchestrations.RecordReads;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code orchestration.record} — a run tree's record, one page at a time; spec 2026-09-28, the
 * orchestration record §3. The frame equivalent of {@code GET /v1/orchestrations/{id}/record}; both
 * call {@link RecordReads}, which holds every rule.
 */
@Component
public class RecordFrames implements FrameArea {

  private final RecordReads reads;

  public RecordFrames(RecordReads reads) {
    this.reads = Objects.requireNonNull(reads, "reads");
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(FrameTypes.ORCHESTRATION_RECORD, this::record);
  }

  /** What {@code orchestration.record} carries; every field but {@code root} optional. */
  record RecordWindow(
      String root,
      Integer after,
      Integer before,
      Boolean tail,
      Integer limit,
      List<String> kinds) {}

  Outcome record(Map<String, Object> payload, Asking asking) {
    RecordWindow asked = Payloads.as(payload, RecordWindow.class, FrameTypes.ORCHESTRATION_RECORD);
    String handle = asking.requireHandle(FrameTypes.ORCHESTRATION_RECORD);
    return Outcome.ok(
        RecordPageView.of(
            reads.read(
                handle,
                asked.root(),
                asked.after(),
                asked.before(),
                asked.tail(),
                asked.limit(),
                asked.kinds())));
  }
}
