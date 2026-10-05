import { isCode } from './codes.ts';
import type { Code } from './codes.ts';

/**
 * One frame on the wire, in either direction, and the reading of the two shapes
 * that travel on it.
 *
 * Mirrored from `plowshare-protocol/.../frames/Envelope.java` and `Outcome.java`
 * — spec §3.1 and §3.3 — and held to that by `src/mirrors-the-server.test.ts`,
 * which reads those files rather than trusting this comment.
 */

/**
 * The only protocol version this build speaks.
 *
 * <b>A handshake, not data</b> (spec §3.2). The server checks it in
 * `Envelope`'s canonical constructor, where nothing can walk around it, and a
 * frame naming any other version is refused rather than read: it is not data
 * this build reads differently, it is a different protocol being proposed. This
 * client owes the same exactness in the other direction — see
 * `connection.ts`'s refusal — while remaining tolerant of payload fields it
 * does not recognise, which is the other half of §3.2 and is deliberately not
 * the same rule.
 */
export const CURRENT_VERSION = 'plowshare-v1';

/**
 * The envelope of §3.1.
 *
 * `protocol_version` is spelled the way the wire spells it rather than the way
 * the Java field is named, because this interface is a description of JSON and
 * not of the record it came from — `Envelope.java` carries
 * `@JsonProperty("protocol_version")` for exactly this reason and the mirror
 * test asserts that it still does.
 *
 * @property id client-generated, and the only thing that correlates an answer
 *     to the request that caused it. The server keeps no record of it
 * @property type a dotted discriminator, such as `conversation.turns`
 * @property protocol_version always {@link CURRENT_VERSION} on a frame this
 *     client sends
 * @property payload the frame's own data, in the shape its `type` defines. On a
 *     response this is the {@link Outcome}
 */
export interface Envelope {
  readonly id: string;
  readonly type: string;
  readonly protocol_version: string;
  readonly payload: unknown;
}

/**
 * What the server said back to one request: `{ code, said?, payload? }` (§3.3).
 *
 * <h2>`said` is absent when the server said nothing, and stays absent</h2>
 *
 * <p>Not `''`, and not a key holding `undefined`. The client design's §5.2
 * records what this guards: a client's own hand-written sentences are correct
 * only when `said` is truly missing, and guessing a sentence by status once
 * <b>"threw away every 409 sentence the server sent."</b> An empty string is a
 * sentence, so defaulting to one makes "the server said nothing" and "the
 * server said nothing in particular" indistinguishable to any caller checking
 * emptiness. The server's `@JsonInclude(NON_NULL)` keeps the key off the wire;
 * {@link outcomeIn} keeps it off the object, so `'said' in outcome` is a
 * question worth asking at both ends.
 *
 * <p>The module compiles with `exactOptionalPropertyTypes`, so `said?: string`
 * here means "absent or a string" and never "present and undefined". That is
 * the compiler holding the same line.
 */
export interface Outcome {
  readonly code: Code;
  readonly said?: string;
  readonly payload?: unknown;
}

/**
 * <b>Whether this frame is an answer to something this client sent.</b>
 *
 * <h2>THE ONE PLACE THE TWO SHAPES ARE TOLD APART. Change it here or nowhere.</h2>
 *
 * <p>Two shapes really do travel on this socket, and the asymmetry is
 * deliberate rather than an oversight: a <b>response</b> is an envelope
 * carrying the request's own `id`, and a <b>push</b> is a bare `JobEvent`
 * serialised straight to JSON with no envelope around it at all.
 * `/v1/events` publishes the bare shape because <b>the web console consumes
 * exactly that today</b>, and enveloping it would be a wire-format change to a
 * live consumer made by a slice that promised none —
 * `EventChannelHandler`'s own javadoc says so, and names the sentence this
 * function implements: <i>"a client on this socket tells the two apart by
 * whether the frame has a `protocol_version`."</i>
 *
 * <p>So the discrimination is <b>by shape</b>, and it is written once, in a
 * function with a name, rather than inlined at each place a frame arrives.
 * Enveloping the pushes is its own slice and lands when the console moves,
 * which §4 of the socket spec puts last. <b>On that day this function is the
 * only thing that changes</b> — it starts reading `id`'s presence, or stops
 * existing — and nothing else in this module has an opinion to correct.
 *
 * <p>Not by `id`: spec §3.1 says a server push carries none, but a bare
 * `JobEvent` has no envelope for an `id` to be absent *from*, and a future
 * `JobEvent` field called `id` would quietly turn every event into an answer to
 * a request nobody made.
 */
export function isAnswer(frame: unknown): frame is Record<string, unknown> {
  return (
    typeof frame === 'object' && frame !== null && 'protocol_version' in frame
  );
}

/** The frame this client puts on the wire for one request. */
export function asking(id: string, type: string, payload: unknown): Envelope {
  return {
    id,
    type,
    protocol_version: CURRENT_VERSION,
    // `{}` rather than an omitted key when a caller has nothing to send: the
    // server hands `Envelope.payload()` to a handler that reads fields off
    // it, and a null map there is a different thing to be wrong about than
    // an empty one.
    payload: payload ?? {},
  };
}

/**
 * The outcome inside a response frame's payload, read tolerantly.
 *
 * Tolerant is §3.2's rule for this layer: three fields are read and every other
 * key is ignored, so a payload gaining one does not break a client built before
 * it. What is *not* tolerated is a code this build cannot name — see
 * `isCode`'s comment — because the code is the thing a caller switches on, and
 * an answer whose meaning is unknown has no safe default to stand in for it.
 *
 * @throws Error if the payload is not an object, or names a code this build
 *     does not know. `connection.ts` turns that into the failure of the one ask
 *     it belongs to
 */
export function outcomeIn(payload: unknown): Outcome {
  if (typeof payload !== 'object' || payload === null) {
    throw new Error(
      'a response carried no { code } payload to read an outcome from',
    );
  }
  const body = payload as Record<string, unknown>;
  const code = body['code'];
  if (!isCode(code)) {
    throw new Error('the server answered with a code this build does not know');
  }
  const said = body['said'];
  if (said !== undefined && said !== null && typeof said !== 'string')
    throw new Error('a response carried an unreadable sentence');
  const carried = body['payload'];
  return {
    code,
    // Spread-or-nothing, so absence survives. An object built with
    // `said: undefined` has the key, and `'said' in outcome` would answer
    // true for a server that said nothing at all.
    ...(typeof said === 'string' ? { said } : {}),
    ...(carried === undefined || carried === null ? {} : { payload: carried }),
  };
}
