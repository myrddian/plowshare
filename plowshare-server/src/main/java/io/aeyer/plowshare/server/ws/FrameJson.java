package io.aeyer.plowshare.server.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * How this server writes a frame down — one mapper configuration, because a frame's payload is an
 * endpoint's answer and the two have to read the same on the wire.
 *
 * <h2>Why this exists, and what it cost to find out</h2>
 *
 * <p>The channel used to write frames with a bare {@code new ObjectMapper()}, which was true of
 * everything it had ever sent: a {@code JobEvent} is seven strings and two ints, and the two pilots
 * answered with a {@code ProjectView} and a list of {@code TurnView}s, none of which carries a
 * date. <b>The first breadth task's third endpoint does.</b> {@code EntryView.recordedAt} and
 * {@code LogSearch.Hit.recordedAt} are {@link java.time.Instant}s, and a bare mapper does not
 * serialise one at all — it raises {@code InvalidDefinitionException}, which the channel catches
 * and answers with {@code INTERNAL_ERROR}. So {@code conversation.chat}, {@code
 * conversation.trajectory} and {@code conversation.search} would have been three registered,
 * routed, tested-in-isolation frame types that failed for every real caller, and the failure would
 * have been invisible to any parity test whose own comparison used the same bare mapper.
 *
 * <p><b>Nearly every endpoint left in the plan answers with a timestamp in it</b> — documents,
 * memories, proposals, jobs — so this is not a detail of one controller's views.
 *
 * <h2>What "the same as HTTP" means here</h2>
 *
 * <p>Spring Boot's own JSON converter registers the JSR-310 module and turns {@code
 * WRITE_DATES_AS_TIMESTAMPS} off, so an {@code Instant} reaches an HTTP client as {@code
 * "2026-09-11T00:00:00Z"}. {@link #answering()} does exactly those two things. It deliberately does
 * not go further and copy Boot's whole builder: the surfaces have to agree about the shapes that
 * actually cross them, and a setting neither surface's payloads exercise would be a claim nothing
 * checks. {@code ConversationFramesTest} is what holds the agreement, by comparing one endpoint's
 * rendered body against one frame's rendered payload for a page that carries a date.
 *
 * <h2>Not the mapper a frame is read with</h2>
 *
 * <p>{@link Payloads} and {@link FrameRouter} each hold a reading mapper, and both are lenient in a
 * way this one has no opinion about — see those classes for §3.2's "the envelope is exact and the
 * payload is tolerant". This is the writing half, and the two are kept apart because a tolerance
 * about what may arrive is not a statement about what is sent.
 */
public final class FrameJson {

  private FrameJson() {}

  /**
   * A mapper that writes an outcome the way the HTTP surface writes the same object.
   *
   * <p>A new instance per call rather than one shared constant: {@code ObjectMapper} is thread-safe
   * once configured but is not immutable, and the callers are few and long-lived — one per channel
   * connection, one per test class. A shared instance would be one {@code configure} call away from
   * a test changing how production writes.
   */
  public static ObjectMapper answering() {
    return new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }
}
