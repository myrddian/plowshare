package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** File authoring and enrollment use the event WebSocket; no HTTP or client-side scheduler. */
@Component
public final class ScheduleFileFrames implements FrameArea {
  private final ObjectProvider<ScheduleDefinitions> definitions;

  public ScheduleFileFrames(ObjectProvider<ScheduleDefinitions> definitions) {
    this.definitions = definitions;
  }

  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.SCHEDULE_SAVE,
        this::save,
        FrameTypes.SCHEDULE_SYNC,
        this::sync,
        FrameTypes.SCHEDULE_FILES,
        this::files);
  }

  private ScheduleDefinitions service() {
    var service = definitions.getIfAvailable();
    if (service == null) throw new CallerFault("Schedule file authoring is unavailable");
    return service;
  }

  private Outcome save(Map<String, Object> payload, Asking asking) {
    var request =
        ScheduleDefinitionCodec.read(
            ScheduleDefinitionCodec.write(payload), ScheduledWork.Save.class);
    return Outcome.ok(service().save(asking.requireHandle(FrameTypes.SCHEDULE_SAVE), request));
  }

  private Outcome sync(Map<String, Object> payload, Asking asking) {
    var request =
        ScheduleDefinitionCodec.read(
            ScheduleDefinitionCodec.write(payload), ScheduledWork.Sync.class);
    return Outcome.ok(service().sync(asking.requireHandle(FrameTypes.SCHEDULE_SYNC), request));
  }

  private Outcome files(Map<String, Object> payload, Asking asking) {
    if (!payload.isEmpty()) throw new CallerFault("schedule.files takes no fields");
    return Outcome.ok(service().list(asking.requireHandle(FrameTypes.SCHEDULE_FILES)));
  }
}
