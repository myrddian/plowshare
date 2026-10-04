package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import java.util.Objects;

/**
 * {@code job.stream} — ask to be sent the tokens as a model produces them, or to stop being sent
 * them.
 *
 * <h2>The first frame with no HTTP twin but one</h2>
 *
 * <p>{@code conversation.latest} was the first; this is the second, and for a sharper reason. A
 * token stream is not a thing a request-response endpoint can express at all: there is no {@code
 * GET} that means "and keep telling me". The subscription only makes sense on a connection that
 * stays open, so it belongs to the socket and nowhere else.
 *
 * <h2>Why it says nothing back but {@code OK}</h2>
 *
 * <p>There is nothing to report. The answer to "am I watching" is the frames that arrive
 * afterwards, and a payload restating what was just asked would be a client's own request read back
 * to it.
 *
 * <h2>What a caller is buying</h2>
 *
 * <p><b>Deltas are droppable and arrive in their hundreds.</b> A subscriber is asking for a typing
 * effect, not for a record: what a run produced is still {@code Outcome.text} from the final model
 * call, and a client that assembled its own answer out of these would disagree with the outcome the
 * first time one went missing. {@code JobDelta}'s javadoc is the long form.
 */
public final class JobStreamHandler implements FrameHandler {

  private final Watchers watchers;

  public JobStreamHandler(Watchers watchers) {
    this.watchers = Objects.requireNonNull(watchers, "watchers");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String session = asking.sessionId();
    if (session == null || session.isBlank()) {
      // Unreachable over this channel -- a socket with no session never
      // attaches -- and written rather than assumed, because the same
      // handler would answer over any future transport that had one.
      throw new CallerFault(
          "'session' is required: a token stream is delivered to a listener, and"
              + " this frame names none. Nothing was subscribed.");
    }
    // ABSENT MEANS ON, which is the only defaulting in this handler and is
    // the way round that makes `job.stream` with an empty payload mean the
    // obvious thing. Turning it off is the deliberate act and says so.
    Object asked = payload.get("on");
    boolean on = !(asked instanceof Boolean flag) || flag;
    watchers.wants(session, on);
    return Outcome.ok();
  }
}
