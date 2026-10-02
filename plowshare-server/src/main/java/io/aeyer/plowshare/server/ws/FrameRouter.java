package io.aeyer.plowshare.server.ws;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.Fault;
import io.aeyer.plowshare.server.faults.Faults;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Turns one inbound frame into an {@link Outcome} — a dotted {@code type} to
 * a {@link FrameHandler}, and every way that can fail to a wire-shaped
 * answer instead of a crash.
 *
 * <h2>This class never writes to a socket, and that is structural rather
 * than a rule somebody has to remember</h2>
 *
 * <p>The dispatcher survey names exactly this as the risk most likely to
 * bite: a dispatcher that offers one generic "send the response" convenience
 * gets called from the wrong thread on the wrong channel, and a push-style
 * channel silently re-acquires the blocking-write hazard {@code
 * EventChannelHandler} was built to remove (Tomcat's {@code
 * DEFAULT_BLOCKING_SEND_TIMEOUT}, 20 000 ms, on a stalled listener's own
 * turn). The fix here is not a comment saying "don't call socket methods
 * from here" — it is that neither {@link #route(Envelope, Asking)}
 * nor {@link #route(String, Asking)} nor {@link
 * FrameHandler#handle} accepts a {@code WebSocketSession}, a queue, or any
 * other handle a socket write could be attempted through. There is no
 * reference to write with, so the constraint holds even for code that never
 * read this paragraph. What a channel handler does with the {@link Outcome}
 * this class hands back — write it inline (a genuine request/response wait,
 * the file channel's shape) or offer it to a bounded queue and let a drain
 * thread absorb the actual write latency (the event channel's shape) — stays
 * that channel's own decision, undisturbed by anything here.
 *
 * <h2>Registration is a hand-built map, not a scan</h2>
 *
 * <p>The survey's Part 3 recommendation, followed rather than VoidCore's
 * reflection-over-{@code @JsonSubTypes} trick: Plowshare's frame {@code type}
 * is a flat string, the way {@code FileRequest.op} already is, not a sealed
 * type with an annotation to reflect over, so a literal {@code Map<String,
 * FrameHandler>} written out in source is no less honest a single source of
 * truth than a scan, and it is something a reader can open and read without
 * running the app. That literal is now one map per area rather than one for
 * the whole server — see {@link FrameArea} for why fifty types in one {@code
 * @Configuration} method was a shape seven parallel tasks could not share —
 * but it is still literal, and {@link FrameRoutingConfig} only unions what the
 * areas name. This
 * constructor is that map's only consumer of a raw {@code String} key: every
 * key is checked against {@link FrameTypes#requireWellFormed(String)} before
 * it is stored, so a typo'd registration fails the boot instead of failing
 * whichever request first names it correctly.
 *
 * <h2>Every failure resolves through {@link Faults}, including the two this
 * class handles before a handler ever runs</h2>
 *
 * <p>The plan's own instruction is not to write a second exception-to-status
 * mapping, and this class holds to it more strictly than the letter of that
 * sentence requires: it picks no {@link Code} itself for anything a handler
 * threw, and even the two failures that happen before dispatch — a frame
 * that will not parse, and one naming the wrong {@code protocol_version} —
 * are represented as a {@link CallerFault} and handed to {@link
 * Faults#of(Throwable)} rather than answered with a locally-chosen {@code
 * Code.BAD_REQUEST} literal. The one exception is an unregistered {@code
 * type}: that is this router's own routing decision, not a caught exception,
 * and reaching for {@code api.NotFoundException} or a fabricated one just to
 * have something to hand {@code Faults} would misuse a type whose own class
 * javadoc scopes it to "a handle the caller holds does not resolve — a job
 * id this process does not know". An unregistered type is answered with
 * {@link Code#NOT_FOUND} directly instead — the same status the table would
 * have given a job id nothing recognised, read from the one shared
 * vocabulary rather than reconstructed.
 *
 * <h2>A malformed frame does not take the session down</h2>
 *
 * <p>{@code FileChannelHandler.handleTextMessage} already measured why: an
 * exception escaping a {@code WebSocketHandler}'s text-message callback is
 * decorated by Spring's {@code ExceptionWebSocketHandlerDecorator} into a
 * session-wide {@code SERVER_ERROR} close, ending every other outstanding
 * request that session was holding for one bad frame. {@link #route(String,
 * Asking)} matches that class's own double guard rather than inventing a
 * second policy: a frame that fails to parse at all (a
 * syntax error, a wrong {@code protocol_version}, a missing {@code type}) is
 * caught before it becomes an exception a caller of this method ever sees,
 * and a frame that parses to the JSON literal {@code null} — which {@code
 * ObjectMapper} returns as a Java {@code null} with no exception thrown at
 * all, the exact gap {@code FileChannelHandler}'s own javadoc measured — is
 * checked for explicitly rather than dereferenced.
 */
public final class FrameRouter {

    /**
     * Deliberately as lenient as {@code FileChannelHandler}'s own mapper —
     * see {@code EnvelopeTest}'s "the mapper is built here to match a
     * channel's" — because the client and the server ship separately and
     * must tolerate the other side adding a field neither this build nor
     * this frame's own {@link FrameHandler} needs to know about. This is a
     * property of every frame reaching this router, not a per-channel
     * choice, so unlike the survey's note about a channel's own leniency
     * setting, one mapper here is enough.
     */
    private static final ObjectMapper JSON =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Map<String, FrameHandler> handlers;

    /**
     * @param handlers every type this router knows, keyed by the dotted
     *     discriminator a frame's {@code type} names. Copied defensively;
     *     mutating the map handed in afterwards has no effect
     * @throws IllegalArgumentException if any key is not a dotted
     *     discriminator, per {@link FrameTypes#requireWellFormed(String)}
     * @throws NullPointerException if {@code handlers} is null, or holds a
     *     null key or a null handler
     */
    public FrameRouter(Map<String, FrameHandler> handlers) {
        handlers.keySet().forEach(FrameTypes::requireWellFormed);
        this.handlers = Map.copyOf(handlers);
    }

    /**
     * Every type this router claims — the surface a client can actually reach,
     * as opposed to every constant {@link FrameTypes} happens to name.
     *
     * <p>Exists so that the spec §3.7 parity check can be held against the real
     * table rather than against a second list written down for the purpose,
     * which is the defect {@code ParityTest}'s own javadoc warns about: a
     * fixture "would pass on the day a tool was deleted". Nothing in {@code
     * src/main} calls this, and it is not a hole in the routing path — a
     * dispatcher that offered a way to enumerate handlers <em>to a caller</em>
     * would be the beginning of the dynamic-dispatch shape this class's header
     * argues against. It is a read of a map that is already immutable.
     *
     * @return the registered types, unmodifiable
     */
    public Set<String> types() {
        return handlers.keySet();
    }

    /**
     * Routes an already-parsed, already-valid envelope to whichever {@link
     * FrameHandler} claims {@link Envelope#type()}.
     *
     * <p><b>What this catches is every {@link RuntimeException} and not every
     * {@link Throwable}</b>, so the promise is "no frame a client sends
     * provokes an exception that costs it the socket" and not a promise about
     * an {@link Error}. An {@code OutOfMemoryError} or a {@code
     * StackOverflowError} raised under a handler still travels out to Spring's
     * {@code ExceptionWebSocketHandlerDecorator} and closes the session —
     * deliberately, since the alternative is answering a frame with a 500 from
     * inside a JVM that has just said it cannot continue, on a thread whose
     * stack may be unusable, and then going on to accept the next frame.
     * Widening the catch would buy a worse failure, not a narrower one.
     *
     * <p>Two of the four failures this class must cover happen here: no
     * handler is registered for {@code request.type()} ({@link
     * Code#NOT_FOUND}, naming the type — see the class javadoc for why this
     * one answer is not routed through {@link Faults}), and the handler that
     * is registered throws (whatever {@link Faults#of(Throwable)} says for
     * it, never the exception's own stack trace — {@code Faults}'s catch-all
     * builds a sentence naming the exception's simple name and message, not
     * {@code Throwable.printStackTrace()}).
     *
     * @param request a frame whose {@link Envelope#protocolVersion()} has
     *     already been checked — its own compact constructor refuses to
     *     exist otherwise, so there is nothing left for this method to
     *     re-check
     * @param asking which session is asking; a handler that needs a project
     *     reads it out of the payload, per {@link Asking}
     * @return what the handler answered, or a failure {@link Outcome} if no
     *     handler claims this type or the one that does threw
     */
    public Outcome route(Envelope request, Asking asking) {
        FrameHandler handler = handlers.get(request.type());
        if (handler == null) {
            return Outcome.failed(Code.NOT_FOUND,
                    "no handler is registered for type \"" + request.type() + "\"");
        }
        try {
            return handler.handle(request.payload(), asking);
        } catch (RuntimeException thrown) {
            Fault fault = Faults.of(thrown);
            return Outcome.failed(fault.code(), fault.detail());
        }
    }

    /**
     * Parses {@code rawFrame} into an {@link Envelope} first, so the two
     * failures a handler never gets a chance to cause — a frame that will
     * not parse at all, and one naming the wrong {@code protocol_version} —
     * are answered here rather than as an exception escaping {@link
     * #route(Envelope, Asking)}. This is the entry point
     * a channel handler calls with the raw text a socket handed it; see the
     * class javadoc's "a malformed frame does not take the session down"
     * section for the double guard this matches.
     *
     * @param rawFrame the frame's raw JSON text, exactly as read off the wire
     * @param asking which session is asking
     * @return what the handler answered, a caller-fault {@link Outcome} if
     *     {@code rawFrame} would not parse or named the wrong {@code
     *     protocol_version}, or whatever {@link #route(Envelope, Asking)}
     *     would answer for the parsed envelope
     */
    public Outcome route(String rawFrame, Asking asking) {
        Envelope request;
        try {
            request = JSON.readValue(rawFrame, Envelope.class);
        } catch (IOException notAFrame) {
            return refuse(notAFrame);
        }
        if (request == null) {
            // Measured the same way FileChannelHandler measured it for its own
            // reply type on Jackson 2.17: readValue("null", Envelope.class)
            // returns a Java null with no exception thrown at all. The catch
            // above cannot see this; this is the second half of the guard.
            return refuse(null);
        }
        return route(request, asking);
    }

    /**
     * Builds the caller-fault {@link Outcome} for a frame that never became
     * an {@link Envelope} — either {@code cause} is the parse failure
     * ({@code null} for the JSON-literal-{@code null} case {@link
     * #route(String, Asking)} checks explicitly), or, when
     * {@code cause}'s root cause is the {@link IllegalArgumentException}
     * {@link Envelope}'s own compact constructor throws for a mismatched
     * {@code protocol_version}, that exception's own message — which already
     * names both {@link Envelope#CURRENT_VERSION} and the version the frame
     * proposed — is reused rather than restated.
     */
    private Outcome refuse(IOException cause) {
        Throwable rootCause = cause == null ? null : cause.getCause();
        CallerFault fault = rootCause instanceof IllegalArgumentException versionMismatch
                ? new CallerFault(versionMismatch.getMessage(), versionMismatch)
                : new CallerFault(describe(cause), cause);
        Fault mapped = Faults.of(fault);
        return Outcome.failed(mapped.code(), mapped.detail());
    }

    private static String describe(IOException cause) {
        if (cause == null) {
            return "that frame parsed to nothing rather than to an envelope — the JSON literal"
                    + " \"null\", or an empty body";
        }
        String detail = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        return "this server could not read that frame as " + Envelope.CURRENT_VERSION + ": "
                + detail;
    }
}
