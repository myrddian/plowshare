/**
 * Every call this console makes to the server, and the one place that knows
 * what to do about a 401.
 *
 * Two rules shape all of it, and both come from how the server's tokens work.
 *
 * **The credential is never in JavaScript's hands.** `ps_access` is `HttpOnly`
 * and `Path=/v1`; the browser attaches it and nothing here can read it. So
 * every request says `credentials: 'same-origin'` and carries no `Authorization`
 * header. That is not a style choice -- the CLI uses the header and the browser
 * cannot, because a browser cannot set headers on a WebSocket upgrade, which is
 * why the cookie exists at all.
 *
 * **A refresh is spent exactly once, and is never retried.** `POST
 * /v1/auth/refresh` rotates both cookies, and presenting a refresh token that
 * has already been spent RETIRES THE WHOLE CHAIN on the server. A client that
 * retries a refresh, or that lets two requests refresh concurrently, logs
 * itself out -- and it logs itself out in a way that looks like the server's
 * fault. Hence {@link refreshOnce}'s single-flight latch below, and hence the
 * absence of any retry loop anywhere in this file.
 */

/** `POST /v1/auth/refresh`: rotates both cookies, 204, no body. */
export const REFRESH_PATH = '/v1/auth/refresh'

/**
 * What the person is told when the session is gone and cannot be recovered.
 *
 * It names the remedy, because the remedy is not guessable. That remedy
 * changed once a password could sign a browser in on its own: reloading now
 * lands on `login.ts`'s screen rather than on a page with nothing to do but
 * fetch a fresh bootstrap URL, so the ordinary way back in is to sign in
 * again there, and the bootstrap URL is named only for whoever holds none of
 * the passwords that would let them.
 */
export const SIGNED_OUT =
    'This console is no longer signed in. Reload this page and sign in again on the screen it'
    + ' lands on. If nobody knows a password yet, reopen the bootstrap URL the server printed'
    + ' when it started — the line beginning "Plowshare console:" — and restart the server if'
    + ' that token has already been spent; it is single-use.'

/**
 * A response the server refused, carried with the status that says how.
 *
 * <h2>Why {@link #said} is separate from the message</h2>
 *
 * The message is what a screen shows and is one of two quite different
 * strings: the sentence this server wrote about the refusal, or `"<path>
 * answered <status>"` synthesized here when there was none. **A caller cannot
 * tell those apart by looking**, and one kind of caller has to: the REPL keeps
 * hand-written sentences enumerating the situations the server has for a
 * status, and those are right only when the server said nothing. Left to guess,
 * it guessed by status -- and threw away every 409 sentence the server sent,
 * which is the case the body was carried through for.
 *
 * So the sentence is carried a second time, on its own, and is `null` when
 * there was not one. A fallback is then warranted exactly when this is `null`,
 * which is a question the code can ask instead of a rule a comment has to
 * assert.
 */
export class ApiError extends Error {
    readonly status: number

    /**
     * The server's own sentence about this refusal, or `null` if it sent none.
     *
     * When it is a string it is also the {@link Error#message}; the duplication
     * is deliberate, so that a screen with nothing clever to do keeps working
     * off `message` alone.
     */
    readonly said: string | null

    /**
     * @param said the server's sentence, or `null`. Defaulted rather than
     *     required because the two {@link SIGNED_OUT} throws above and every
     *     test fake in this codebase construct an error this console wrote
     *     itself, which is the `null` case and should not have to say so.
     */
    constructor(message: string, status: number, said: string | null = null) {
        super(message)
        this.name = 'ApiError'
        this.status = status
        this.said = said
    }
}

/**
 * The refresh in flight, or null.
 *
 * **This latch is the whole defence against a self-inflicted logout.** Two
 * screens polling at once both meet the 401 that follows an access cookie
 * expiring; without this they would both `POST /v1/auth/refresh`, the second
 * with the cookie the first has already spent, and the server would retire the
 * chain and refuse everything afterwards. Sharing one promise makes the second
 * caller wait for the first caller's answer instead of asking its own question.
 *
 * Cleared in a `finally` so that a later 401 -- a genuinely new expiry, an hour
 * on -- gets a genuinely new refresh rather than the stale answer to the last
 * one.
 */
let refreshInFlight: Promise<boolean> | null = null

/**
 * Rotate the cookie pair, at most once concurrently.
 *
 * @returns whether the server issued a new pair. A rejection is reported as
 *     `false` rather than thrown: a transport failure and a refused refresh
 *     leave the caller with the same single option, which is to stop.
 */
function refreshOnce(): Promise<boolean> {
    if (refreshInFlight === null) {
        refreshInFlight = fetch(REFRESH_PATH, {
            method: 'POST',
            credentials: 'same-origin',
        })
            .then((response) => response.status === 204)
            .catch(() => false)
            .finally(() => {
                refreshInFlight = null
            })
    }
    return refreshInFlight
}

/**
 * One request, with one refresh and one retry behind it and nothing more.
 *
 * The sequence, and the count of `fetch` calls each branch spends, because
 * "never loop" is a claim about a number:
 *
 * - anything but 401 -- **1 call**, answered or thrown.
 * - 401, refresh 204, retry succeeds -- **3 calls**.
 * - 401, refresh 204, retry 401 -- **3 calls**, then {@link SIGNED_OUT}. The
 *   second 401 is not refreshed again; a fresh access cookie that is refused
 *   immediately is not an expiry.
 * - 401, refresh refused -- **2 calls**, then {@link SIGNED_OUT}. The refresh
 *   is not retried, ever: see this file's header.
 *
 * @param path an absolute path on this origin, like `/v1/jobs`. Never a full
 *     URL: a caller that could name a host could send the cookie somewhere else
 * @param init anything `fetch` takes; `credentials` is set here and a caller's
 *     value for it is deliberately overwritten rather than merged
 */
export async function request(path: string, init: RequestInit = {}): Promise<Response> {
    const send = (): Promise<Response> => fetch(path, { ...init, credentials: 'same-origin' })

    const first = await send()
    if (first.status !== 401) {
        return first
    }
    if (!(await refreshOnce())) {
        throw new ApiError(SIGNED_OUT, 401)
    }
    const retried = await send()
    if (retried.status === 401) {
        throw new ApiError(SIGNED_OUT, 401)
    }
    return retried
}

/**
 * `GET path`, parsed as JSON.
 *
 * The return type is the caller's assertion and not a checked fact -- nothing
 * here validates the shape the server sent. That is deliberate at this layer:
 * the transport's job is the credential and the retry, and a screen that
 * renders a field the server stopped sending should show a missing field rather
 * than have this function throw over the whole page.
 */
export async function get<T>(path: string): Promise<T> {
    return body<T>(await ok(await request(path), path))
}

/**
 * `POST path`, with an optional JSON body, parsed as JSON.
 *
 * A 204 -- which several of this server's write endpoints answer with -- comes
 * back as `undefined`, because there is nothing to parse. A caller expecting
 * nothing should type the call as `post<void>`.
 */
export async function post<T>(path: string, payload?: unknown): Promise<T> {
    const init: RequestInit = payload === undefined
        ? { method: 'POST' }
        : {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        }
    return body<T>(await ok(await request(path, init), path))
}

/**
 * `PUT path`, with a JSON body, parsed as JSON.
 *
 * One endpoint on this server takes a `PUT` and it is the one that moves a
 * conversation's lifecycle. **The verb is part of what that endpoint means** --
 * `ConversationController.lifecycle` is explicit that a caller is *putting* the
 * row into a state and that asking twice is asking once, which is why archiving
 * an already-archived conversation is refused rather than swallowed -- so it is
 * spelled here rather than folded into {@link post} with a method argument.
 *
 * There is no body-less form, because there is no body-less caller: a state to
 * move to is the whole of the request.
 */
export async function put<T>(path: string, payload: unknown): Promise<T> {
    return body<T>(await ok(await request(path, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    }), path))
}

/** The one code whose `detail` is written by no one and may hold anything. */
const UNWRITTEN = 'internal_error'

/**
 * The response, or an {@link ApiError} carrying what the server said about it.
 *
 * <h2>Why this reads the body, having spent a long time not reading it</h2>
 *
 * This function used to put the path and the status in the message and stop
 * there, and the reason it gave was sound: "a server error body may hold
 * anything the server was holding, and this string ends up in a log, in a
 * toast, and in whatever a future screen does with a caught error." **That
 * hazard is real and is still guarded below.** What was wrong was the scope: it
 * threw away every sentence to avoid leaking one kind of them.
 *
 * `ApiExceptionHandler` answers every refusal with `{"error", "detail"}`, and
 * across its twelve handlers those details fall into three kinds, which is one
 * more kind than an earlier draft of this comment claimed.
 *
 * **Most are authored end to end.** Three different ones share the 400 on `POST
 * /v1/jobs/&#123;id&#125;/limits` and each says a different thing to do, the
 * lifecycle 409 names the states that were reachable, the config 400 names the
 * keys that are live. Reduced to "answered 400" they are all the same shrug,
 * and the owner's judgement is the one this implements: a useful error is worth
 * more than a generic one.
 *
 * **Three 503s wrap an authored sentence around a root cause's own words.**
 * `archiveUnavailable`, `embeddingUnavailable` and `configUnavailable` splice in
 * JDBC driver text or a remote endpoint's HTTP body, so part of what they carry
 * was written by nobody here. They are carried anyway, and on purpose: that
 * egress is deliberate and scrubbed at the source -- `OpenAiTransport`
 * documents it, `withheldIfItQuotesTheKey` and `withoutUserInfo` do it, and
 * `no_database_password_reaches_the_message_or_any_cause_in_the_chain` pins it
 * -- and the driver's half is the half an operator acts on. "Failed to obtain
 * JDBC Connection" without "Connection to localhost:5432 refused" sends them to
 * the wrong box.
 *
 * <h2>The one that is still withheld, and why it is the only one</h2>
 *
 * {@link #UNWRITTEN} is the code on the `@ExceptionHandler(Throwable.class)`
 * arm, the third kind and a kind of one: it formats an *arbitrary* throwable
 * with no author anywhere in it — a class name and whatever message that object
 * happened to be carrying, scrubbed by nothing because nobody knew what it
 * would be. That is precisely the "anything the server was holding" case, and
 * it is the one withheld.
 *
 * **The status is on the error either way**, because a caller branching on 401
 * or 409 must not have to parse prose to do it.
 *
 * A body that is not this server's shape — an HTML page from a proxy in front
 * of it, a truncated response, a 502 that never reached Plowshare at all — is
 * not a refusal and has no sentence in it, so it falls back to the status. What
 * "this server's shape" means is checked rather than assumed; see {@link
 * refusal}, where the first draft of that check let one common non-Plowshare
 * body through.
 */
async function ok(response: Response, path: string): Promise<Response> {
    if (response.ok) {
        return response
    }
    throw await refused(response, path)
}

/**
 * The {@link ApiError} for a response this server refused.
 *
 * **Exported because two callers cannot go through {@link ok} and must not
 * therefore answer differently.** `config.ts` sends a raw string body and
 * `documents.ts` sends multipart, so neither can use {@link put} or {@link
 * post}; both used to build this error by hand, and both then said "answered
 * 400" for refusals every other screen showed in full. The config screen's was
 * the worst one to lose -- its 400 names the keys that are live, which is the
 * whole answer to the mistake that caused it.
 *
 * A caller outside this file needs it for exactly that reason and no other: it
 * has a `Response` this server refused and wants the same sentence out of it
 * that any other screen would get.
 */
export async function refused(response: Response, path: string): Promise<ApiError> {
    const said = await refusal(response)
    return new ApiError(said ?? `${path} answered ${response.status}`, response.status, said)
}

/**
 * What the server said, or `null` if it said nothing this console may repeat.
 *
 * `null` rather than the fallback sentence, so that {@link ApiError#said} can
 * be the fact it claims to be: a caller asking "did the server write this?"
 * gets an answer, instead of a string it would have to recognise the shape of.
 *
 * <h2>What counts as this server's shape, and the body that used to slip past</h2>
 *
 * A `string` under `error` is required, and that is the whole of the check that
 * this came from `ApiExceptionHandler`. The first draft required only a
 * non-null object whose `error` was not {@link UNWRITTEN}, which no body
 * without an `error` key at all can fail -- and one such body arrives on an
 * ordinary path. The server extends `ResponseEntityExceptionHandler`, so Spring
 * answers the standard MVC exceptions with a `ProblemDetail`:
 * `{"type","title","status","detail","instance"}` -- a `detail`, and no
 * `error`. `application.yml` sets `max-file-size: 8MB`, so an upload over that
 * is a 413 in exactly that shape, and its detail was carried through with none
 * of the audit above performed on it. Not a leak that has bitten anybody, and
 * still a body reaching the page through a door this file's comment said was
 * shut.
 *
 * Reading the body consumes it, which is safe here and only here: this is the
 * throwing path, so there is no later reader to starve.
 */
async function refusal(response: Response): Promise<string | null> {
    let sent: unknown
    try {
        sent = await response.json()
    } catch {
        return null
    }
    if (typeof sent !== 'object' || sent === null) {
        return null
    }
    const said = sent as { readonly error?: unknown; readonly detail?: unknown }
    if (typeof said.error !== 'string' || said.error === UNWRITTEN) {
        return null
    }
    return typeof said.detail === 'string' && said.detail !== '' ? said.detail : null
}

/** The body as JSON, or `undefined` for the 204s this API answers with. */
async function body<T>(response: Response): Promise<T> {
    if (response.status === 204) {
        return undefined as T
    }
    return (await response.json()) as T
}

/**
 * The transport as one object, which is the shape the screens take it in.
 *
 * A named export as well as the members, so that a test can replace the whole
 * of it in one line and a screen can import exactly the verb it uses.
 */
export const api = { request, get, post, put }
