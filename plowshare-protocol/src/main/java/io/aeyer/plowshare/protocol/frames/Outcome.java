package io.aeyer.plowshare.protocol.frames;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * What the server said back to one request: {@code { code, said?, payload? }}, per spec §3.3.
 *
 * <h2>{@link #said} is absent, and absent is not the same thing as null</h2>
 *
 * <p>The client design's own record of the mistake this guards against: a client's hand-written
 * sentences are correct only when {@link #said} truly carries nothing, and guessing a sentence by
 * status code once "threw away every 409 sentence the server sent." A client that cannot tell "the
 * server said nothing, so show your own sentence for this {@link #code}" apart from "the server
 * said this, and it is empty" will eventually show the wrong one with total confidence — an empty
 * string and a missing key look identical to code that checks {@code said == null}, but they do not
 * look identical on the wire, and {@code @JsonInclude(NON_NULL)} on this record is what keeps that
 * true here: a null {@link #said} is not serialised as {@code "said": null}, it is omitted
 * entirely, so a client parsing the frame sees no key at all rather than a key it has to remember
 * means the same thing as absence.
 *
 * <p>Defaulting {@link #said} to {@code ""} instead was the alternative this class does not take.
 * An empty string is a sentence — a server that said nothing and a server that said the empty
 * string would be indistinguishable to a client checking {@code said.isEmpty()}, which is exactly
 * the ambiguity {@code null} plus omission on the wire is built to remove.
 *
 * <h2>{@link #payload} is {@code Object}, not a typed shape</h2>
 *
 * <p>Every frame type answers with a different payload shape, and this record is the one wrapper
 * every one of them travels inside — a {@code FrameHandler} hands back whatever its own
 * request/response pair defines and this class does not need to know it. It serialises as whatever
 * Jackson does with the concrete object a handler put here; nothing in this module inspects it.
 *
 * @param code what the caller switches on. Never absent — see {@link Code} for the shared
 *     vocabulary this comes from
 * @param said why, in the server's own words, when {@link #code} alone does not say enough to act
 *     on and the caller's hand-written sentence for this code would be wrong. {@code null} — and
 *     therefore absent from the wire — when the server has nothing to add to what {@link #code}
 *     already means
 * @param payload the answer's own shape, for a {@link Code#OK} outcome that carries one. {@code
 *     null} — and therefore absent from the wire — for a failure, and for a success with nothing to
 *     hand back
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Outcome(Code code, String said, Object payload) {

  public Outcome {
    Objects.requireNonNull(code, "an outcome with no code is not an answer to anything");
  }

  /** A success carrying no sentence and no payload. */
  public static Outcome ok() {
    return new Outcome(Code.OK, null, null);
  }

  /** A success carrying a payload and no sentence. */
  public static Outcome ok(Object payload) {
    return new Outcome(Code.OK, null, payload);
  }

  /**
   * A failure, with what the server actually said and no payload. {@code code} is never {@link
   * Code#OK} in practice, though nothing here refuses that — the caller supplying the code is the
   * one place this class trusts, exactly as {@link Code} trusts {@code Faults} to have picked
   * correctly.
   */
  public static Outcome failed(Code code, String said) {
    return new Outcome(code, said, null);
  }
}
