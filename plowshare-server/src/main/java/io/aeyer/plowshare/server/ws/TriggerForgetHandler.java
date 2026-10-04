package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.events.TriggerStore;
import java.util.Map;
import java.util.Objects;

/** {@code trigger.forget} — delete a trigger this account defined, refusing what it had waiting. */
public final class TriggerForgetHandler implements FrameHandler {

  private final TriggerStore triggers;
  private final FiringStore firings;

  public TriggerForgetHandler(TriggerStore triggers, FiringStore firings) {
    this.triggers = Objects.requireNonNull(triggers, "triggers");
    this.firings = Objects.requireNonNull(firings, "firings");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String handle = asking.requireHandle(FrameTypes.TRIGGER_FORGET);
    String name =
        Payloads.required(
            payload,
            "trigger",
            FrameTypes.TRIGGER_FORGET,
            "the name trigger.list answers with. Nothing was deleted.");
    // Forget first: it is the ownership check, and another account's trigger must keep what
    // it has waiting. firings.trigger has no foreign key, so the waiting rows outlive the
    // delete long enough to be refused here (and a drain in between refuses them the same way).
    triggers.forget(name, handle);
    firings.refuseWaiting(name, "trigger forgotten");
    return new Outcome(Code.NO_CONTENT, null, null);
  }
}
