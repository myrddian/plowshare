package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.TriggerStore;
import java.util.Map;
import java.util.Objects;

/** {@code trigger.list} — every trigger. */
public final class TriggerListHandler implements FrameHandler {

  private final TriggerStore triggers;

  public TriggerListHandler(TriggerStore triggers) {
    this.triggers = Objects.requireNonNull(triggers, "triggers");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    return Outcome.ok(triggers.list());
  }
}
