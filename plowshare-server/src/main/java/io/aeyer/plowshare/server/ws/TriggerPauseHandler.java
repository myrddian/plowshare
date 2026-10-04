package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.events.TriggerStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import java.util.Objects;

/**
 * {@code trigger.pause} — pause or resume a trigger this account defined.
 *
 * <p>Pausing is the stop button, so it also refuses what the trigger already had waiting: a paused
 * trigger stops listening, but a drain would otherwise still start its queued firings. They are
 * refused rather than held; unpausing starts nothing from before.
 */
public final class TriggerPauseHandler implements FrameHandler {

  record Body(String trigger, Boolean paused) {}

  private final TriggerStore triggers;
  private final FiringStore firings;

  public TriggerPauseHandler(TriggerStore triggers, FiringStore firings) {
    this.triggers = Objects.requireNonNull(triggers, "triggers");
    this.firings = Objects.requireNonNull(firings, "firings");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String handle = asking.requireHandle(FrameTypes.TRIGGER_PAUSE);
    String name =
        Payloads.required(
            payload, "trigger", FrameTypes.TRIGGER_PAUSE, "the name trigger.list answers with");
    Body body = Payloads.as(payload, Body.class, FrameTypes.TRIGGER_PAUSE);
    if (body.paused() == null) {
      throw new CallerFault(
          FrameTypes.TRIGGER_PAUSE + " needs 'paused' as true or false. Nothing was changed.");
    }
    // Pause first: it is the ownership check, and another account's queue must be left alone.
    triggers.pause(name, body.paused(), handle);
    if (body.paused()) {
      firings.refuseWaiting(name, "trigger paused");
    }
    return new Outcome(Code.NO_CONTENT, null, null);
  }
}
