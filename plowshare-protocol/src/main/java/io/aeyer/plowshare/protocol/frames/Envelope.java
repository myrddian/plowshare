package io.aeyer.plowshare.protocol.frames;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aeyer.plowshare.protocol.Window;
import java.util.Map;
import java.util.Objects;

/**
 * One frame on the WebSocket wire, in either direction: {@code { id, type,
 * protocol_version, payload }}, per spec §3.1.
 *
 * <h2>Why this lives in {@code plowshare-protocol} and not in {@code ws/}</h2>
 *
 * <p>The server and the TypeScript client must agree on one shape, and this is
 * the module that already exists for exactly that — {@code plowshare-protocol}
 * is what {@code FileRequest} and {@code FileReply} live in for the file
 * channel, for the same reason. A type declared inside {@code ws/} would be
 * reachable only from the server, and the client would be left to hand-write
 * its own idea of what a frame looks like — which is the failure mode {@code
 * FileReply}'s own javadoc calls "the hand-written-JSON-on-both-sides
 * failure."
 *
 * <h2>{@code protocol_version} is exact, and enforced where nothing can walk
 * around it</h2>
 *
 * <p>Spec §3.2: the envelope is a handshake and the payload is data, and the
 * two rules that follow from that would otherwise collide if stated apart —
 * this layer is exact, the payload underneath it is tolerant of fields it does
 * not recognise. {@link #CURRENT_VERSION} is {@code "plowshare-v1"}, and it is
 * checked in the canonical constructor rather than by a caller who remembers
 * to ask, for {@link Window}'s reason: <b>a cap enforced only in a factory is
 * a cap the wire can walk around</b>, and the canonical constructor is
 * reachable by anything that can name this record — Jackson binding a frame
 * from a client built against a different release, most of all. A client
 * naming any other version is not sending data this build reads differently;
 * it is proposing a different handshake, and construction itself refuses it
 * before the frame is ever routed rather than accepting it and misreading it
 * later. So a version mismatch arriving over the wire is never seen as an
 * {@code Envelope} at all: Jackson's binding throws out of this constructor,
 * and whichever layer parses the frame — a later task's, not this record's —
 * turns that failure into a refusal naming both versions.
 *
 * <h2>{@code seq} and {@code mac} are not fields here, and that is argued
 * rather than forgotten</h2>
 *
 * <p>Spec §3.1 departs from VoidCore on this point deliberately. VoidCore
 * reserves both, on the argument that "adding required fields later is a
 * breaking change; reserving optional fields now is free" — but a field a
 * later version ignores and a still-later version requires is a breaking
 * change regardless of whether the name was reserved in advance, so the
 * reservation buys an agreed name and nothing else; {@link #protocolVersion}
 * is what actually handles the break, and it is already here. VoidCore
 * reserved {@code seq} and {@code mac} for per-message session authentication;
 * this design has no per-message authentication to reserve for, because auth
 * happens once, before the socket exists (§2), and no frame authenticates.
 * Add them when there is a concrete v2 that needs them — not before, and not
 * as insurance.
 *
 * <h2>{@link #payload} is a {@code Map<String, Object>}, not a {@code
 * JsonNode}</h2>
 *
 * <p>{@code plowshare-protocol}'s build file keeps Jackson databind off the
 * main classpath on purpose — see its own comment: "a protocol module that can
 * open a JDBC connection is one that eventually will," and the same argument
 * holds for any dependency reachable from a stdio client process meant to hold
 * no durable state. {@code JsonNode} lives in {@code jackson-databind}; this
 * module depends on {@code jackson-annotations} alone on the main classpath,
 * so a record that named {@code JsonNode} in a public signature would not
 * compile without widening that dependency for both the server and the client
 * on this task's own judgement — which the task this record was built for
 * says not to do without saying so. A raw, untyped {@code Map<String,
 * Object>} is reachable from annotations alone and is deserialised the same
 * way {@code JsonNode} would have been: as whatever the payload's own type
 * needs, decoded a second time once the frame's {@code type} has resolved
 * which shape that is.
 *
 * @param id client-generated, and what correlates a response to the request
 *     that caused it. {@code null} — and therefore absent from the wire — on
 *     a server-initiated push, which answers no request and has nothing to
 *     correlate with
 * @param type a dotted discriminator naming what this frame is, such as
 *     {@code "conversation.turns"}. Routed on verbatim; never absent
 * @param protocolVersion the handshake version this frame claims. Checked
 *     against {@link #CURRENT_VERSION} by the canonical constructor; see the
 *     class javadoc's second section. Named {@code protocol_version} on the
 *     wire
 * @param payload the frame's own data, in the shape its {@link #type} defines.
 *     Decoded a second time by whichever {@code FrameHandler} claims this
 *     {@link #type}; this record does not interpret it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Envelope(
        String id,
        String type,
        @JsonProperty("protocol_version") String protocolVersion,
        Map<String, Object> payload) {

    /**
     * The only protocol version this build speaks. Spec §3.1: exact, because
     * this layer is a handshake and not data — see the class javadoc.
     */
    public static final String CURRENT_VERSION = "plowshare-v1";

    /**
     * The cap is an invariant of the type, not a courtesy of a factory — see
     * the class javadoc. {@link #type} is required for the same reason a
     * frame with no destination cannot be routed anywhere.
     */
    public Envelope {
        Objects.requireNonNull(type, "a frame with no type routes nowhere");
        if (!CURRENT_VERSION.equals(protocolVersion)) {
            throw new IllegalArgumentException(
                    "this build speaks protocol_version \"" + CURRENT_VERSION
                            + "\"; a frame naming \"" + protocolVersion
                            + "\" is proposing a different handshake and is refused rather"
                            + " than read as data");
        }
    }
}
