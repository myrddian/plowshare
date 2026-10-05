package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.EventPayloadCodec;
import io.aeyer.plowshare.server.events.FiringRecord;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.events.Intake;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code event.fire} — raise an event by hand, through the same intake a tick uses.
 *
 * <p>Needs a signed-in account. Unlike a tick's, this event's data is written by a person, and it
 * reaches a model inside the trigger's utterance: {@code Dispatcher.utterance} fences it as data,
 * which is the same treatment the webhook adapter must strengthen. Requiring an account at least
 * keeps that data's author one of this server's own.
 */
public final class EventFireHandler implements FrameHandler {

  record Body(String event, Map<String, Object> data) {}

  private final Intake intake;
  private final FiringStore firings;

  public EventFireHandler(Intake intake, FiringStore firings) {
    this.intake = Objects.requireNonNull(intake, "intake");
    this.firings = Objects.requireNonNull(firings, "firings");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    asking.requireHandle(FrameTypes.EVENT_FIRE);
    String event =
        Payloads.required(
            payload, "event", FrameTypes.EVENT_FIRE, "the event name triggers listen for");
    Body body = Payloads.as(payload, Body.class, FrameTypes.EVENT_FIRE);
    List<FiringRecord> created = intake.emit(event, EventPayloadCodec.manual(body.data()));
    // Re-read: emit answers each firing as it arrived, before dispatch moved it.
    return Outcome.ok(
        created.stream().map(f -> FiringView.of(firings.find(f.id()).orElse(f))).toList());
  }
}
