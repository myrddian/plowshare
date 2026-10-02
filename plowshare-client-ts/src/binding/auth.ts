import { connect } from './connection.ts'
import type { Connection, Socket } from './connection.ts'
import { FILES_PATH } from './files.ts'

/**
 * The door, and the one ordering above it that a client gets wrong.
 *
 * <h2>Why there is HTTP in a socket-only binding</h2>
 *
 * <p>Spec §4 says no `fetch` for application data, and nothing here is
 * application data: these four requests are the whole of what happens
 * <i>before</i> a socket exists. §2.2 of the auth design says why the socket
 * cannot authenticate itself — a WebSocket begins as an HTTP upgrade, that
 * upgrade is the only moment headers and cookies exist, and `new WebSocket(url)`
 * takes a URL and a subprotocol list and nothing else. So a credential the
 * upgrade <i>can</i> carry has to be fetched over HTTP first, which is the
 * ticket, and getting a ticket needs a live access token, which is the login and
 * the refresh.
 *
 * <h2>`fetch` is injected, on the socket's reasoning and with the socket's
 * consequence</h2>
 *
 * <p>{@link Fetching} is a structural interface describing only what is called:
 * a URL, a method, a flat header record, a string body, and back a status,
 * two header readers and `json()`. It is not `typeof fetch`, and naming that
 * would mean `@types/node` or a DOM `lib` in this project — the edit
 * `src/binding/tsconfig.json` explains at length and `src/neutrality.test.ts`
 * lists first among the four that blind the guards. That a real `fetch` does
 * satisfy this is not left as a claim: `src/platform/fits.ts` assigns one to
 * {@link Fetching} and `tsc -b` checks it.
 *
 * <h2>The awkward member is `getSetCookie`, and it is the server's shape</h2>
 *
 * <p>`POST /v1/auth/login` learned to answer a non-browser caller in the body.
 * <b>`POST /v1/auth/refresh` never did</b>: it reads the refresh token from the
 * `ps_refresh` cookie and answers 204 with `Set-Cookie` and no body, and
 * `AuthControllerTest.no_exchange_or_refresh_response_body_carries_a_token`
 * holds it there deliberately. A client with no cookie jar therefore has to
 * write one header and read two, which is what {@link refresh} does and why
 * {@link Heard} names `getSetCookie` — `headers.get('set-cookie')` joins
 * multiple values with a comma, and a cookie's own attributes may contain one.
 *
 * <h2>Spelled-out names, and where they came from</h2>
 *
 * <p>`ps_access` and `ps_refresh` are {@link AuthProperties} defaults on the
 * server and can be configured away from them, so they are options here rather
 * than constants, defaulted to what the server defaults to.
 */

/** A request this module makes: the whole of what it asks a `fetch` to do. */
export interface Sent {
    readonly method: string
    readonly headers: Readonly<Record<string, string>>
    /** JSON, already serialised. Absent on a GET and on the two POSTs with no
     *  body of their own. */
    readonly body?: string
}

/** The response headers this module reads, and no others. */
export interface Heard {
    /** Case-insensitively, the way HTTP means it and every implementation
     *  does it. */
    get(name: string): string | null

    /**
     * Every `Set-Cookie` as its own string.
     *
     * <b>Not a browser's `fetch`.</b> A browser forbids reading this header at
     * all, which is what makes `HttpOnly` worth anything there. This binding is
     * for a terminal, where there is no cookie jar to hide a cookie in and the
     * tokens are held in memory either way — so the member is required rather
     * than optional, and the console (which has a jar, and needs none of this)
     * is not a consumer of this file.
     */
    getSetCookie(): string[]
}

/** What a `fetch` answers, in the three parts this module reads. */
export interface Answer {
    readonly status: number
    readonly headers: Heard
    json(): Promise<unknown>
}

/** The injected `fetch`. See the class note; `src/platform/fits.ts` proves a
 *  real one satisfies it. */
export type Fetching = (url: string, sent: Sent) => Promise<Answer>

/**
 * The injected socket opener, which <b>resolves only once the socket is
 * open</b>.
 *
 * <p>A promise, and this is the one thing about it worth arguing. {@link Socket}
 * has no `open` subscription — `connection.ts` names the omission and its
 * reason — so this module cannot wait for readiness itself, and a `send` before
 * the handshake completes throws `InvalidStateError` on a real `WebSocket`
 * while passing silently against any fake. Rather than widen {@link Socket} for
 * one caller, the wait belongs to whoever constructed the thing: six lines, and
 * they are written out and driven against a real server in
 * `src/real-socket.test.ts` so that task 7 copies something measured rather
 * than something suggested.
 */
export type Opening = (url: string) => Promise<Socket>

/**
 * What a client holds, and the absence that matters.
 *
 * <p><b>`refresh` is absent, not null and not `undefined`</b>, when the session
 * cannot be renewed — the same discipline `envelope.ts` applies to `said`, for
 * the same reason: `'refresh' in tokens` is then a question worth asking, and
 * the compiler's `exactOptionalPropertyTypes` means the type says "absent or a
 * string" rather than "sometimes there and holding nothing".
 */
export interface Tokens {
    readonly access: string
    readonly refresh?: string
}

/**
 * A sign-in's whole result: the tokens, and whether this admin must still
 * change their password.
 *
 * <p><b>Both, although one nearly implies the other, and the redundancy is
 * deliberate.</b> The server withholds the refresh token exactly when the flag
 * is set, so today `refresh === undefined` and `mustChangePassword` carry the
 * same bit. They are not the same fact: one is a property of the session (it
 * cannot be renewed), the other is an instruction to a person (change your
 * password). Collapsing them would make the day the server withholds a refresh
 * token for some other reason a day when this binding tells someone to change a
 * password that is perfectly fine.
 */
export interface Session {
    readonly tokens: Tokens
    readonly mustChangePassword: boolean
}

/** What `GET /v1/auth/session` answers: the two questions a client has on
 *  waking up with tokens it did not watch being issued. */
export interface Standing {
    /** Whether the access token is still one the gate accepts. */
    readonly signedIn: boolean

    /**
     * Whether that session must change its password before it dies.
     *
     * `false` when {@link signedIn} is false, which is the one place this
     * interface is less than honest: the server answers the second question
     * only for a caller that passed the first, so the value there is not "no",
     * it is "unasked". A caller with no session has a sign-in to do before the
     * question means anything.
     */
    readonly mustChangePassword: boolean
}

/** What {@link openSocket} is given on top of a {@link Door}. */
export interface SocketDoor extends Door {
    /** A platform credential store may coordinate and persist renewal. */
    readonly renew?: () => Promise<Tokens>
    readonly open: Opening

    /**
     * The id this client listens under, and it is not optional.
     *
     * <p><b>A socket opened without one is closed by the server the moment it
     * opens</b>, with "a listener on a session nothing can name is one no job
     * will ever reach". A ticket gets past the gate; this says who is
     * listening, and a running job publishes to a listener registered under an
     * id rather than to a connection.
     *
     * <p>Generated by the caller rather than here, on the same reasoning
     * `fetch` and the socket are injected: this module builds no ids, holds no
     * clock and reads no environment, so `crypto.randomUUID` belongs to the
     * composition root that already owns `process`. It is also what lets a test
     * name a session it can then assert on.
     */
    readonly session: string

    readonly onPush?: (push: unknown) => void

    /**
     * The socket went away. Handed straight to `ConnectOptions.onClose`, which
     * is where the argument for it is.
     *
     * <p>Carried here rather than left to the caller to subscribe itself,
     * because the caller never sees the socket: {@link openSocket} constructs
     * it, hands it to `connect`, and returns a {@link Connection}. A listener
     * the caller cannot register is a listener that has to travel with the
     * options, exactly as `onPush` does.
     */
    readonly onClose?: () => void
}

/** What {@link openSocket} hands back: the connection, and the pair the
 *  refresh rotated to. See that function for why the tokens are returned. */
export interface Opened {
    readonly connection: Connection
    readonly tokens: Tokens
}

/** Where the server is, how to reach it, and what it calls its cookies. */
export interface Door {
    /** Origin, with no trailing slash: `http://127.0.0.1:8080`. */
    readonly base: string

    readonly fetch: Fetching

    /** `AuthProperties.accessCookie`, whose default this matches. */
    readonly accessCookie?: string

    /** `AuthProperties.refreshCookie`, whose default this matches. */
    readonly refreshCookie?: string
}

/**
 * <b>Sign-in failed, and that is the whole of what this says.</b>
 *
 * <p>The server answers an unknown handle and a wrong password identically —
 * same 401, same empty body, and the same Argon2id cost, so that not even the
 * timing distinguishes them — precisely so that neither confirms which handles
 * exist. That property is server-side and this class is what keeps it from
 * being undone client-side: a binding that rendered one of those as "no such
 * user" would publish what the server went to some length not to say, and the
 * person reading the screen would never know the server had been careful.
 *
 * <p>It is also what a throttled attempt answers, and what a malformed one
 * answers. There is nothing in a 401 from this endpoint to tell them apart, so
 * there is nothing here to say about which it was.
 */
export class SignInRefused extends Error {
    constructor() {
        // <b>What to check, without saying which was wrong.</b> Running this
        // against a real server for the first time printed "sign-in failed"
        // and stopped, which is true, unhelpful, and the opposite of what the
        // unreachable case does one branch away -- that one names the address
        // it tried and the variable holding it.
        //
        // Naming both variables gives a person somewhere to look while
        // preserving the property the sentence above exists for: the server
        // answers a 401 identically for an unknown handle and a wrong
        // password, so neither confirms a handle exists, and this says nothing
        // that narrows it either. "One of these two" is not a hint about which.
        super('sign-in failed. Check PLOWSHARE_HANDLE and PLOWSHARE_PASSWORD —'
            + ' the server refuses both the same way, so this cannot say which of'
            + ' the two to fix.')
        this.name = 'SignInRefused'
    }
}

/**
 * <b>This session must change its password before it can do anything that
 * outlives its access token.</b>
 *
 * <p>Its own class rather than a refusal, because every other way of surfacing
 * it is a lie about what happened. A flagged admin holds an access token and no
 * refresh token at all — the server mints the pair and delivers only half of it
 * — so {@link refresh} has nothing to present and would answer 401 for someone
 * who <i>is</i> signed in; and {@link ticket} answers 403 for such a session,
 * which a caller reading statuses would report as "forbidden" for a person
 * whose only problem is a password they have not changed yet.
 *
 * <p>Both of those are the same one fact, so both raise this, and
 * {@link openSocket} raises it before sending anything at all when the tokens
 * it was handed have no refresh half. The view's job on catching it is to
 * prompt for a new password — `POST /v1/auth/password`, which is not this
 * task's — rather than to retry.
 */
export class MustChangePassword extends Error {
    constructor() {
        super('this session must change its password before it can be renewed'
            + ' or open a socket')
        this.name = 'MustChangePassword'
    }
}

/*
 * The five names this client shares with the server, spelled as the server
 * spells them and exported for one reason: `src/mirrors-the-server.test.ts`
 * holds each against the Java that declares it. A header name is the quietest
 * possible thing to get wrong — the server ignores an unrecognised one, answers
 * the 204 it answers a browser, and this client fails a step later complaining
 * about a status — so the drift is worth a test that crosses the language
 * boundary, exactly as `Code` and `CURRENT_VERSION` already are.
 */

/** `AuthController.TOKEN_DELIVERY_HEADER`. */
export const TOKEN_DELIVERY_HEADER = 'X-Plowshare-Token-Delivery'

/** `AuthController.TOKEN_DELIVERY_BODY`: the one value that means anything. */
export const TOKEN_DELIVERY_BODY = 'body'

/** `AuthController.MUST_CHANGE_PASSWORD_HEADER`. */
export const MUST_CHANGE_PASSWORD_HEADER = 'X-Plowshare-Must-Change-Password'

/** `AuthProperties.accessCookie`'s default; {@link Door} can override it. */
export const ACCESS_COOKIE = 'ps_access'

/** `AuthProperties.refreshCookie`'s default; {@link Door} can override it. */
export const REFRESH_COOKIE = 'ps_refresh'

/**
 * Exchange a handle and a password for a pair, in the body rather than in
 * cookies.
 *
 * <p><b>The header is the whole of the request for a body</b>, and it is
 * deliberately one nothing sends by accident: `X-Plowshare-Token-Delivery:
 * body`. An `Accept`-based trigger was tried on the server and removed, because
 * axios's and jQuery's default `Accept` headers both name
 * `application/json` concretely — so any page built on either would have been
 * handed both tokens where its own JavaScript could read them, which is the one
 * thing `HttpOnly` exists to survive. A header this endpoint invented is
 * evidence of a caller that read the contract; a header an HTTP stack assembles
 * is not.
 *
 * @throws SignInRefused on 401, saying only that, for the reason that class
 *     carries
 */
export async function signIn(door: Door, handle: string, password: string): Promise<Session> {
    const answer = await door.fetch(`${door.base}/v1/auth/login`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            [TOKEN_DELIVERY_HEADER]: TOKEN_DELIVERY_BODY,
        },
        body: JSON.stringify({ handle, password }),
    })
    if (answer.status === 401) {
        throw new SignInRefused()
    }
    if (answer.status !== 200) {
        throw new Error(`POST /v1/auth/login answered ${answer.status}`)
    }
    const body = asRecord(await answer.json(), '/v1/auth/login')
    const access = stringIn(body, 'access', '/v1/auth/login')
    const renewal = body['refresh']
    const mustChangePassword = body['mustChangePassword'] === true
    return {
        // Absent, not null: see `Tokens`. The server sends `"refresh": null`
        // for a flagged admin and JSON has no way to send an absence, so the
        // absence is made here, once, where the shape stops being JSON.
        tokens: typeof renewal === 'string' ? { access, refresh: renewal } : { access },
        mustChangePassword,
    }
}

/**
 * A password this server refused. The new one, not the old.
 *
 * <p><b>Its own type because the two 400s a caller can cause are different
 * mistakes.</b> A wrong current password is a 401 and means "that is not your
 * password"; this is a 400 and means "that one is on a list of passwords
 * nobody may have". Telling somebody to check their typing when the real
 * answer is "not that word" is the kind of unhelpfulness that makes people
 * try the same thing twice.
 */
export class PasswordRefused extends Error {

    constructor() {
        super('that password is one this server will not accept — it is on a short'
            + ' list of obvious ones. Nothing was changed.')
        this.name = 'PasswordRefused'
    }
}

/**
 * Change the password of whoever this access token belongs to.
 *
 * <h2>Whose password, and why the caller cannot say</h2>
 *
 * <p>There is no handle in this request. The server reads it off the token that
 * authenticated the call — `TokenStore.handleFor` on the very access token —
 * so the row that changes is the one the session was issued for and never one a
 * caller named. That is worth knowing here because it is also why this cannot
 * be used to rescue a <i>different</i> account.
 *
 * <h2>It ends the session it was made with</h2>
 *
 * <p><b>The server revokes every access token presented</b>, which is correct —
 * a password change should not leave the old credential working — and means the
 * token this was called with is dead the moment it returns. A caller has to
 * sign in again with the new password. `main.ts` does exactly that, and the
 * alternative was a client that appeared to work until its first frame.
 *
 * @throws SignInRefused on 401. The current password did not match — <b>or the
 *     handle is locked out</b>, which this endpoint checks before it checks
 *     anything else and answers identically, so a client cannot tell the two
 *     apart and must not pretend to
 * @throws PasswordRefused on 400, which is the new password being an obvious
 *     one rather than anything about the old
 */
export async function changePassword(
        door: Door, access: string, current: string, next: string): Promise<void> {
    const answer = await door.fetch(`${door.base}/v1/auth/password`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            // BEARER RATHER THAN THE COOKIE, and it is not a preference. This
            // client never holds the cookie -- it asked for the token in the
            // body at sign-in, precisely so that it need not -- and
            // `presentedAccess` reads the header first.
            Authorization: `Bearer ${access}`,
        },
        body: JSON.stringify({ currentPassword: current, newPassword: next }),
    })
    if (answer.status === 401) {
        throw new SignInRefused()
    }
    if (answer.status === 400) {
        throw new PasswordRefused()
    }
    if (answer.status !== 204) {
        throw new Error(`POST /v1/auth/password answered ${answer.status}`)
    }
}

/**
 * Rotate the pair.
 *
 * <p>The one endpoint of the four that speaks only cookies: the token goes out
 * as a `Cookie` header this module writes by hand and the new pair comes back
 * in `Set-Cookie`, because that endpoint answers 204 with no body by design and
 * was never given the body-delivery escape `login` has. So this is the client
 * half of a cookie jar, in the two lines a single cookie needs and no more —
 * no domain matching, no path matching, no store. There is exactly one cookie
 * to send, to exactly one URL.
 *
 * <p><b>Both halves of that are forbidden to a browser</b>, which is worth
 * saying where it happens rather than only in the class note: a page may
 * neither set `Cookie` on a request nor read `Set-Cookie` off a response, and
 * it needs to do neither, because its jar does both for it. This function is
 * therefore the one in this file that could not be shared with the console even
 * if the console wanted it — and the console does not, for exactly that reason.
 *
 * @throws MustChangePassword when there is no refresh token to present, which
 *     is a state the server puts an account in rather than an error
 * @throws Error when the server refuses the token: the session is over and a
 *     person has to sign in again. Not {@link SignInRefused}, which is about a
 *     password that was just typed
 */
export async function refresh(door: Door, tokens: Tokens): Promise<Tokens> {
    if (tokens.refresh === undefined) {
        throw new MustChangePassword()
    }
    const answer = await door.fetch(`${door.base}/v1/auth/refresh`, {
        method: 'POST',
        headers: { Cookie: `${door.refreshCookie ?? REFRESH_COOKIE}=${tokens.refresh}` },
    })
    if (answer.status === 401) {
        throw new Error('the refresh token was refused, so this session is over;'
            + ' sign in again')
    }
    if (answer.status !== 204) {
        throw new Error(`POST /v1/auth/refresh answered ${answer.status}`)
    }
    const jar = cookiesIn(answer.headers.getSetCookie())
    const access = jar.get(door.accessCookie ?? ACCESS_COOKIE)
    const renewal = jar.get(door.refreshCookie ?? REFRESH_COOKIE)
    if (access === undefined || access === '' || renewal === undefined || renewal === '') {
        throw new Error('POST /v1/auth/refresh answered 204 without setting both cookies,'
            + ' so there is no rotated pair to hold')
    }
    return { access, refresh: renewal }
}

/**
 * Mint a single-use, seconds-long credential the upgrade can carry.
 *
 * <p>The access token goes as `Authorization: Bearer`, which `AuthFilter`
 * accepts and which suppresses any cookie — the same precedence
 * `AuthController.presentedAccess` reads, so the credential this asks about is
 * the one that got the request through the gate.
 *
 * @throws MustChangePassword on 403, which `AuthController.ticket` answers for
 *     a restricted chain and for nothing else — so no second probe is needed to
 *     read it correctly
 */
export async function ticket(door: Door, tokens: Tokens): Promise<string> {
    const answer = await door.fetch(`${door.base}/v1/auth/ticket`, {
        method: 'POST',
        headers: bearing(tokens),
    })
    if (answer.status === 403) {
        throw new MustChangePassword()
    }
    if (answer.status !== 200) {
        throw new Error(`POST /v1/auth/ticket answered ${answer.status}`)
    }
    return stringIn(asRecord(await answer.json(), '/v1/auth/ticket'), 'ticket', '/v1/auth/ticket')
}

/**
 * Ask whether this session is still live and whether it must change its
 * password — the two questions a client waking up with stored tokens has, and
 * the reason `GET /v1/auth/session` exists.
 *
 * <p>Neither other endpoint answers them. `refresh` is not a probe: a flagged
 * admin holds no refresh token, so refresh says 401 for someone who is signed
 * in, which is exactly backwards. And any ordinary gated read answers the first
 * question and not the second, since the must-change header is otherwise
 * written only on a login response nobody has kept.
 *
 * <p>{@link openSocket} does <b>not</b> call this, deliberately: it would be a
 * third round trip to learn something the tokens in hand already say, and the
 * 403 that `ticket` answers covers the case where they are wrong.
 */
export async function standing(door: Door, tokens: Tokens): Promise<Standing> {
    const answer = await door.fetch(`${door.base}/v1/auth/session`, {
        method: 'GET',
        headers: bearing(tokens),
    })
    if (answer.status === 401) {
        return { signedIn: false, mustChangePassword: false }
    }
    if (answer.status !== 204) {
        throw new Error(`GET /v1/auth/session answered ${answer.status}`)
    }
    return {
        signedIn: true,
        mustChangePassword: answer.headers.get(MUST_CHANGE_PASSWORD_HEADER) === 'true',
    }
}

/**
 * <b>Refresh, then ticket, then open. In that order, every time, and this
 * function is where the order lives.</b>
 *
 * <h2>Why the order is the whole point</h2>
 *
 * <p>A ticket is minted against a live access token and is good for seconds.
 * After an idle period the access token is the stale half, so a reconnect that
 * goes straight for a ticket fails <b>at the ticket step, not at the socket
 * step</b> — and a client that reads that failure as "the ticket did not work"
 * retries the ticket, which fails again, forever. The spec calls this the one
 * ordering a TUI author will get wrong, and asks for a function rather than a
 * note in a README, which is this one: nothing above it has to remember,
 * because nothing above it has an opportunity to get it wrong.
 *
 * <p><b>It refreshes unconditionally rather than when the access token looks
 * expired</b>, because a client cannot tell. The token is opaque — it carries
 * no expiry a client can read — so the only way to find out is to spend a
 * request asking, and that request costs exactly what the refresh that makes
 * the question moot costs. One round trip, spent on the answer rather than on
 * the question.
 *
 * <h2>Why the rotated tokens come back</h2>
 *
 * <p>Refreshing <i>rotates</i>: the refresh token just presented is retired and
 * a new one issued. A caller that kept its original pair would present a
 * retired token on the next reconnect, and this server's `TokenStore` treats a
 * retired token being presented again as reuse — theft — and kills the whole
 * chain. So {@link Opened} carries the new pair, and dropping it on the floor
 * is not something a caller can do by accident: `connection` and `tokens`
 * arrive together.
 *
 * @throws MustChangePassword before any request when the pair has no refresh
 *     half, since both steps below would fail and the honest reason for both is
 *     the flag
 */
/** `rotated` retains the new pair even if ticketing or opening the socket subsequently fails. */
export async function openSocket(door: SocketDoor, tokens: Tokens, rotated?: (tokens: Tokens) => void): Promise<Opened> {
    // Ahead of everything, because a base that cannot become a socket URL
    // cannot become one after two round trips either, and discovering it there
    // would report a mistyped configuration as a socket that would not open.
    const socketBase = schemed(door.base)
    if (tokens.refresh === undefined) {
        // Ahead of the refresh rather than inside it, so that nothing goes out
        // on the wire for a session that cannot possibly succeed: the ticket
        // would be a 403 and the refresh a 401, and neither status is the
        // sentence a person needs to read.
        throw new MustChangePassword()
    }
    const renewed = await (door.renew?.() ?? refresh(door, tokens))
        rotated?.(renewed)
    const pass = await ticket(door, renewed)
    const socket = await door.open(eventsUrl(socketBase, pass, door.session))
    // Spread-or-nothing on each listener rather than a property holding
    // `undefined`, which `exactOptionalPropertyTypes` refuses and which is the
    // same discipline `Tokens.refresh` is kept to: absent means absent.
    const connection = connect({
        socket,
        ...(door.onPush === undefined ? {} : { onPush: door.onPush }),
        ...(door.onClose === undefined ? {} : { onClose: door.onClose }),
    })
    return { connection, tokens: renewed }
}

/**
 * `ws://…/v1/events?ticket=…` — one of the two URLs in this client that carry a
 * credential. The other is `/v1/files`, built by `filesUrl` below from a ticket
 * minted the same way and for the same reasons.
 *
 * <p>A ticket and never a session token — auth design §2.3, rule 1 — and only
 * because a ticket is single-use and expires in seconds, which is what makes
 * its appearance in an access log close to worthless by the time anyone reads
 * one. It is escaped rather than trusted to be URL-safe: a token's alphabet is
 * the server's business and a client that assumed one would break quietly, with
 * a ticket that reaches the gate as a different string than the one minted.
 *
 * <h2>`session` is required, and omitting it closes the socket immediately</h2>
 *
 * <p><b>Found by running this against a real server, not by a test.</b>
 * `EventChannelHandler` reads a `session` parameter and closes any socket that
 * opens without one — "a listener on a session nothing can name is one no job
 * will ever reach" — because a listener is registered *under* a session id, and
 * that is how a running job finds the client to publish to. A ticket gets past
 * the gate; it does not say who is listening.
 *
 * <p>The composition test did not catch it because its server accepted whatever
 * this client sent. A fake that validates nothing tests a client against its own
 * assumptions, which is why that fake now refuses an upgrade with no session,
 * exactly as the real handler does.
 */
function eventsUrl(socketBase: string, pass: string, session: string): string {
    // ENCODED BY HAND RATHER THAN THROUGH `URLSearchParams`, AND THE COMPILER
    // IS WHY.
    //
    // This used `new URLSearchParams({...})`, which compiled only because the
    // test files in this directory were dragging Node's globals into the whole
    // project -- `types: []` here is supposed to mean exactly that no platform
    // type is nameable, and it had quietly stopped meaning it. Splitting the
    // tests into their own project on 2026-09-12 made the compiler say so.
    //
    // `encodeURIComponent` is not a platform type: it is an ES built-in, in
    // `lib.es5` and therefore in this project's own `lib`. So this needs
    // nothing declared, nothing injected and nothing assumed about where it
    // runs -- which is the rule this directory already keeps for `Socket` and
    // `fetch`, met one more time.
    //
    // The two values are the whole query: a ticket and a session, both opaque
    // strings this client was handed. Neither is a structure that needs a
    // builder.
    const ticket = encodeURIComponent(pass)
    const listening = encodeURIComponent(session)
    return `${socketBase}/v1/events?ticket=${ticket}&session=${listening}`
}

/** What this client says it roots, on the upgrade — `FileChannelHandler`'s "all three or none". */
export interface Claim {
    readonly project: string
    readonly machine: string
    /** Absolute on this machine. The server never resolves it. */
    readonly root: string
}

export interface FilesOpened {
    readonly socket: Socket
    /** The rotated pair. The one passed in is retired and must not be presented again. */
    readonly tokens: Tokens
}

/**
 * The file channel, opened with a claim — {@link openSocket}'s three steps for the
 * other path.
 *
 * <p><b>It refreshes too, and has to.</b> A person roots a project minutes or hours
 * after signing in, and the access token that signed them in has long expired by
 * then; a ticket asked for with it would be a 401 that reads as "your session is
 * gone". The cost is the one {@link Opened} already documents: the pair rotates,
 * and a caller that kept the old one kills its own chain on the next open.
 *
 * <p>Returns the raw socket rather than a served one: what answers is the view's to
 * choose, and `channel.serve` takes it from there.
 *
 * @param renewed told the rotated pair the moment the refresh returns it — before
 *     the ticket and the open, either of which can still throw
 */
export async function openFiles(
        door: Door & { readonly open: Opening; readonly session: string; readonly renew?: () => Promise<Tokens> },
        tokens: Tokens, claim: Claim,
        renewed?: (tokens: Tokens) => void): Promise<FilesOpened> {
    const socketBase = schemed(door.base)
    if (tokens.refresh === undefined) {
        throw new MustChangePassword()
    }
    const rotated = await (door.renew?.() ?? refresh(door, tokens))
    // HANDED OVER BEFORE ANYTHING ELSE CAN FAIL. Once the refresh has answered,
    // the pair passed in is retired whatever happens next; a ticket or an open
    // that throws would otherwise take the only copy of the new pair with it,
    // and the caller's next open would present a dead token and end the chain.
    renewed?.(rotated)
    const pass = await ticket(door, rotated)
    const socket = await door.open(filesUrl(socketBase, pass, door.session, claim))
    return { socket, tokens: rotated }
}

/**
 * Every value escaped, the root above all: a path may hold `&`, `#`, a space.
 *
 * <p>`ready=1` asks the server to say when the claim has landed — see
 * `channel.serve`'s `onReady`. Asked for rather than always sent, because the
 * Java client reads every frame on this socket as a request.
 */
function filesUrl(socketBase: string, pass: string, session: string, claim: Claim): string {
    return `${socketBase}${FILES_PATH}?ticket=${encodeURIComponent(pass)}`
        + `&session=${encodeURIComponent(session)}`
        + `&project=${encodeURIComponent(claim.project)}`
        + `&machine=${encodeURIComponent(claim.machine)}`
        + `&root=${encodeURIComponent(claim.root)}`
        + '&ready=1&source=1'
}

/**
 * `http` → `ws`, `https` → `wss`, and anything else refused by name.
 *
 * Refused rather than assumed, because the assumption fails silently: slicing a
 * fixed number of characters off a base that is neither would produce a URL
 * that is wrong in a way no error mentions, and the failure would surface as a
 * socket that will not open with nothing pointing at the cause. A `Door`'s base
 * is written by a person, once, and this is where a typo in it is legible.
 */
function schemed(base: string): string {
    if (base.startsWith('https:')) {
        return `wss:${base.slice('https:'.length)}`
    }
    if (base.startsWith('http:')) {
        return `ws:${base.slice('http:'.length)}`
    }
    throw new Error(`this door's base must be an http:// or https:// origin, not "${base}"`)
}

/** `Authorization: Bearer`, which `AuthFilter` reads ahead of any cookie. */
function bearing(tokens: Tokens): Readonly<Record<string, string>> {
    return { Authorization: `Bearer ${tokens.access}` }
}

/**
 * The name and value of each `Set-Cookie`, attributes discarded.
 *
 * Everything after the first `;` is `Path`, `Max-Age`, `HttpOnly` and
 * `SameSite` — the browser's business, and this client is not one. The value is
 * taken up to that semicolon and not trimmed further: a cookie value is
 * whatever the server put there.
 */
function cookiesIn(headers: readonly string[]): Map<string, string> {
    const jar = new Map<string, string>()
    for (const header of headers) {
        const pair = header.split(';')[0] ?? ''
        const at = pair.indexOf('=')
        if (at > 0) {
            jar.set(pair.slice(0, at).trim(), pair.slice(at + 1).trim())
        }
    }
    return jar
}

/** A JSON body that has to be an object for anything else here to read it. */
function asRecord(body: unknown, path: string): Record<string, unknown> {
    if (typeof body !== 'object' || body === null) {
        throw new Error(`${path} answered with no JSON object to read`)
    }
    return body as Record<string, unknown>
}

/** One string field of a body, named in the failure rather than left to a
 *  `undefined` that surfaces three calls later. */
function stringIn(body: Record<string, unknown>, field: string, path: string): string {
    const value = body[field]
    if (typeof value !== 'string' || value === '') {
        throw new Error(`${path} answered without a "${field}" this client can use`)
    }
    return value
}
