package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.EventPayloadCodec;
import io.aeyer.plowshare.server.events.EventPublication;
import io.aeyer.plowshare.server.events.FiringRecord;
import io.aeyer.plowshare.server.events.FiringStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code event.fire} — raise an event by hand, published through Relay like a tick.
 *
 * <p>Needs a signed-in account, and {@code ProjectAuthorization} requires that account to be a
 * server administrator: the operation is in its {@code SERVER} set and resolves no project. Unlike
 * a tick's, this event's data is written by a person, and it reaches a model inside the trigger's
 * utterance: {@code Dispatcher.utterance} fences it as data, which is the same treatment the
 * webhook adapter must strengthen. Requiring an account at least keeps that data's author one of
 * this server's own, and the account is also what attributes the publication.
 *
 * <p>The event no longer reaches the firing inbox by calling it. {@code RelayEventPublication}
 * appends it to the family's own Relay topic and the inbox copies it as that topic's subscriber, so
 * a hand-raised event and a scheduled one now travel the same substrate — decision 0037.
 */
public final class EventFireHandler implements FrameHandler {

  record Body(String event, Map<String, Object> data) {}

  private final EventPublication publication;
  private final FiringStore firings;

  public EventFireHandler(EventPublication publication, FiringStore firings) {
    this.publication = Objects.requireNonNull(publication, "publication");
    this.firings = Objects.requireNonNull(firings, "firings");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String actor = asking.requireHandle(FrameTypes.EVENT_FIRE);
    String event =
        Payloads.required(
            payload, "event", FrameTypes.EVENT_FIRE, "the event name triggers listen for");
    Body body = Payloads.as(payload, Body.class, FrameTypes.EVENT_FIRE);
    List<FiringRecord> created =
        publication.fire(event, EventPayloadCodec.manual(body.data()), actor);
    // Re-read: fire answers each firing as it arrived, before dispatch moved it.
    return Outcome.ok(
        created.stream().map(f -> FiringView.of(firings.find(f.id()).orElse(f))).toList());
  }
}
