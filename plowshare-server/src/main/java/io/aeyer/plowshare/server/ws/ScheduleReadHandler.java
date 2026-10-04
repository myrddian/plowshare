package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.ScheduleReader;
import java.util.Map;
import java.util.Objects;

/**
 * {@code schedule.read} — one sentence read into a proposed schedule and trigger, saving nothing.
 * The client shows the proposal to a person and saves it with {@code schedule.define} and {@code
 * trigger.define}, which check it again.
 *
 * <p><b>Signed in, though nothing is written.</b> A reading costs a model call, and the only thing
 * a proposal is for is the two verbs that do need an account; answering a socket that could not
 * save the result would spend a call on a proposal nobody can confirm.
 *
 * <p>The refusals that need no model — no account, no sentence — are made here, before {@link
 * ScheduleReader} is reached.
 *
 * <p><b>A project and a conversation together are accepted, unlike {@code trigger.define}.</b> A
 * client knows both the tier it is in and the conversation it is in, and which of the two a reading
 * will use is not known until the sentence has been read: "here" means the conversation, anything
 * else the inbox under the project. The trigger the client finally saves names only one of them.
 */
public final class ScheduleReadHandler implements FrameHandler {

  record Body(String text, String zone, String project, String conversation) {}

  private final ScheduleReader reader;

  public ScheduleReadHandler(ScheduleReader reader) {
    this.reader = Objects.requireNonNull(reader, "reader");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String type = FrameTypes.SCHEDULE_READ;
    asking.requireHandle(type);
    String text =
        Payloads.required(
            payload, "text", type, "the sentence saying what should happen, and when");
    Body body = Payloads.as(payload, Body.class, type);
    var request =
        new ScheduleReader.Request(text, body.zone(), body.project(), body.conversation());
    return Outcome.ok(
        reader.accountingEnabled()
            ? reader.read(request, asking.requireHandle(type))
            : reader.read(request));
  }
}
