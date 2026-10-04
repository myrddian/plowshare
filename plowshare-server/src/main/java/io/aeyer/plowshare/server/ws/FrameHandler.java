package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import java.util.Map;

/**
 * Answers one frame {@code type} — the read-a-payload, hand-back-an-{@link Outcome} half of the
 * dispatcher. Everything about writing that answer back to a client is deliberately absent from
 * this contract; see {@link FrameRouter}'s class javadoc for why.
 *
 * <h2>{@code payload} is a {@code Map<String, Object>}, matching {@link
 * io.aeyer.plowshare.protocol.frames.Envelope#payload()}</h2>
 *
 * <p>The plan this interface was built from sketched {@code JsonNode} here, copying the shape a
 * generic tree would have offered. {@code plowshare-protocol} keeps {@code jackson-databind} off
 * its main classpath on purpose — see {@code Envelope}'s own class javadoc — so {@code Envelope}
 * carries a raw {@code Map<String, Object>} instead, and a handler reading anything other than what
 * {@code Envelope} actually hands it would be reading a shape that does not exist on this
 * classpath. A handler for a specific {@code type} decodes the fields it expects out of that map
 * through {@link Payloads} — {@code Payloads.as} for the request record its endpoint already binds,
 * {@code Payloads.required} for a value the endpoint took from its path. <b>Not with a mapper of
 * its own</b>: spec §3.2 makes the payload tolerant of fields this build has never heard of, and
 * tolerance implemented once per handler is tolerance fifty handlers can each forget. See {@link
 * Payloads} for that argument and for the payload-key convention.
 *
 * <h2>{@code asking} is a session and not a caller, deliberately</h2>
 *
 * <p>A frame is answered under an {@link Asking} — the session the socket was opened under, and
 * nothing else. It is <b>not</b> a {@code DefinitionResolver.Caller}, and that is the whole point:
 * a socket is opened per session and never per project, so the caller this surface could build for
 * itself would have a null project every time, and a handler that passed one down to a
 * project-scoped service would answer against the boot set while its endpoint answered against the
 * project — same status, same shape, different answer. {@link Asking}'s own javadoc carries the
 * argument; what matters here is that a handler needing a caller <b>builds it from the payload</b>,
 * through the same door its controller uses, and cannot do anything else by accident.
 *
 * <p>Nothing about a frame authenticates — spec §2 puts every credential check before the socket
 * exists — so {@link Asking} carries no identity claim of its own; it is scoping, not a grant.
 *
 * <h2>What a handler must not do</h2>
 *
 * <p>Take no {@code WebSocketSession}, no queue, no write callback — there is nothing in this
 * signature a handler could write to a socket with even if it tried. It may throw; {@link
 * FrameRouter} turns whatever escapes into the same {@link Outcome} shape {@code Faults} would have
 * produced for an HTTP controller.
 */
public interface FrameHandler {

  /**
   * @param payload this frame's own data, decoded a second time for the shape this handler's {@code
   *     type} defines — through {@link Payloads}, which is where §3.2's tolerance lives
   * @param asking which session is asking. The project, when this type needs one, comes from {@code
   *     payload}
   * @return what happened. May be a success or a failure {@link Outcome} — both are answers, and
   *     neither writes anything anywhere
   */
  Outcome handle(Map<String, Object> payload, Asking asking);
}
