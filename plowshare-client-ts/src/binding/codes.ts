/**
 * The one status vocabulary the socket and the HTTP surface share, mirrored
 * from `plowshare-protocol/.../frames/Code.java`.
 *
 * <h2>Mirrored, and held to it by a test that reads the Java</h2>
 *
 * <p>Spec §3.3: the dispatcher reuses `ApiExceptionHandler`'s existing
 * exception-to-status mapping rather than writing a second one, because <b>two
 * mappings over one set of exceptions is the drift this migration most
 * risks</b>. A hand-copied list over here is that second mapping wearing a
 * client's clothes, so `src/mirrors-the-server.test.ts` reads `Code.java` and
 * compares the sets. A constant added, renamed or dropped over there fails this
 * module's suite rather than reaching a person as a code their client ignores.
 *
 * <h2>Sixteen, and the last four are the ones a table of failures could not
 * name</h2>
 *
 * <p>Twelve failures — one per distinct `(status, slug)` pair the handler's
 * table produces — plus `OK`, `ACCEPTED`, `NO_CONTENT` and `CREATED`. The
 * successes arrived late on the Java side and the gap is recorded in
 * `implementation rationale` §18: the enum had been derived from a table that enumerates
 * what went wrong, so it could say no success but 200 while six endpoints
 * across four controllers answer 202.
 *
 * <p><b>`ACCEPTED` is not `OK` and this client must never fold them.</b> A 202
 * is a job handle to poll; a client that reads it as a finished answer tells a
 * person their work is done when it has not started. `NO_CONTENT` is likewise
 * not `OK` with an empty payload — one is an answer about the world, the other
 * is a statement that this frame never had one to give — and `CREATED` is kept
 * apart because the HTTP surface keeps it apart: `AgentController.define`
 * answers 200 or 201 from one method depending on whether the write created the
 * definition or replaced it, and collapsing them drops a fact a caller can read
 * today.
 *
 * <h2>Names, not numbers</h2>
 *
 * <p>The wire value is the enum constant's name — Jackson writes
 * `"code": "ACCEPTED"` — so that is what this module models. `httpStatus()` and
 * `slug()` exist on the Java side for the surface that still has a status line;
 * <b>this client has no such surface and so does not carry them</b>. Mirroring
 * the numbers here would invite exactly the reconstruction §3.3 forbids: a
 * client computing an answer from the other surface's vocabulary.
 */

/**
 * Every code the server can answer with, in `Code.java`'s own order.
 *
 * A `readonly` tuple rather than an enum: `Code` below is derived from it, so
 * the list and the type cannot disagree, and the runtime array is what both
 * {@link isCode} and the mirror test need.
 */
export const CODES = [
    'OK',
    'ACCEPTED',
    'NO_CONTENT',
    'CREATED',
    'BAD_REQUEST',
    'NOT_FOUND',
    'CONFLICT',
    'UNSUPPORTED_DOCUMENT',
    'IMAGE_NOT_STORED_EMPTY',
    'IMAGE_NOT_STORED_UNRECOGNISED',
    'IMAGE_NOT_STORED_TOO_LARGE',
    'VALIDATION_FAILED',
    'ARCHIVE_UNAVAILABLE',
    'EMBEDDING_UNAVAILABLE',
    'CONFIG_UNAVAILABLE',
    'MODEL_UNAVAILABLE',
    'INTERNAL_ERROR',
] as const

/** What a caller switches on. */
export type Code = (typeof CODES)[number]

/**
 * The four successes, kept as a list so that {@link succeeded} is not a guess.
 *
 * <b>This is not the collapse the brief forbids.</b> The four stay four
 * everywhere they are carried; this answers only the narrower question "did
 * anything go wrong", which every caller asks before it asks which success it
 * got. A caller that needs to know whether to poll still reads {@link
 * Code}`.ACCEPTED` itself.
 */
export const SUCCESSES: readonly Code[] = ['OK', 'ACCEPTED', 'NO_CONTENT', 'CREATED']

/**
 * Whether a value off the wire is a code this build knows.
 *
 * Spec §3.2 is tolerant in the payload and exact on the envelope, and a code is
 * neither: it is the vocabulary a caller switches on. An unrecognised *field*
 * is ignored here as the spec says; an unrecognised *code* is refused by
 * `connection.ts`, because there is no safe thing to do with an answer whose
 * meaning this build cannot name — and guessing `INTERNAL_ERROR` would report a
 * server failure that did not happen.
 */
export function isCode(value: unknown): value is Code {
    return typeof value === 'string' && (CODES as readonly string[]).includes(value)
}

/** Whether this code is one of the four successes. See {@link SUCCESSES}. */
export function succeeded(code: Code): boolean {
    return SUCCESSES.includes(code)
}
