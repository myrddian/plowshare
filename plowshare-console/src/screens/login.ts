import { ApiError } from '../api'
import { button, el, labelled, trouble } from './dom'
import type { Screen, Transport } from './screen'

/**
 * The door itself: a handle, a password, and one sentence for a refusal.
 *
 * <h2>Why this never calls {@link Transport.post}, or even `api.request`</h2>
 *
 * Every other screen's writes go through `transport.post`, which parses the
 * body and throws away the response. `POST /v1/auth/login` answers with
 * neither a body worth parsing nor nothing to say -- `AuthController.login`
 * writes `X-Plowshare-Must-Change-Password` on the response itself, precisely
 * because there is no login response left to read it from on a later reload
 * (see that class's note on `GET /v1/auth/session`). Reading a header is not a
 * thing {@link Transport} can do, so this screen goes around it exactly the
 * way `config.ts` goes around `transport.put` for the one write that is not
 * JSON: a small function of its own, {@link login}, called through the
 * `login` option so a test can replace it without stubbing `fetch`.
 *
 * Nor does it go through `api.request`, unlike every other screen's escape
 * hatch. `request`'s whole point is the single-flight refresh-and-retry
 * `api.ts`'s header describes, and that machinery is for a request that
 * expected a live session and met a 401 because the access cookie expired.
 * A login attempt is the opposite case: there may be no session at all, and a
 * wrong password is not an expired one. Routing it through `request` would,
 * on every rejected attempt, also fire `POST /v1/auth/refresh` -- spending
 * any refresh cookie a browser happened to be holding from an older session
 * for no reason connected to the password just typed, and adding a second
 * round trip to a form whose whole reason to hurry is that it is throttled.
 * {@link login} calls `fetch` directly instead, `credentials: 'same-origin'`
 * and nothing more, the same way `auth.ts`'s `bootstrapFromUrl` does for its
 * own credential-establishing call that also has no session to refresh yet.
 *
 * <h2>No `X-Plowshare-Token-Delivery` header, on purpose and permanently</h2>
 *
 * Setting that header is how a CLI asks `AuthController.login` to put the
 * cookie pair in the JSON body as well as in `Set-Cookie`. This screen is a
 * browser tab that renders model output, file contents and memory bodies --
 * exactly the surface `auth.ts`'s header argues an XSS would take whatever
 * JavaScript is holding -- so it never sends that header and the pair never
 * enters JavaScript here. This is not a detail to revisit for convenience; it
 * is the one property this screen exists to keep.
 *
 * <h2>One sentence for every refusal, and the reason it is only one</h2>
 *
 * `AuthController.login`'s own class note is explicit that an unknown handle
 * and a real handle with the wrong password answer identically -- same
 * status, same empty body, same cost paid in `PasswordHasher.matches` either
 * way -- because a caller that could tell the two apart could enumerate every
 * handle this server has. {@link LOGIN_FAILED} is the only string this screen
 * ever shows for a refusal, whatever the failure actually was, so that the
 * server's discipline is not undone by a client that is more talkative than
 * the endpoint it is calling.
 *
 * <h2>No "forgot password"</h2>
 *
 * There is no recovery flow on the server -- `AuthController` has three doors
 * for a caller with no session and none of them resets anything -- so this
 * form offers no control that would promise one. An affordance for a flow
 * that does not exist is a worse answer than the form simply not having it.
 *
 * <h2>The password's whole lifetime in this module</h2>
 *
 * It lives in `passwordField.value` -- a DOM property, never written to the
 * `value` *attribute*, never to `localStorage` or `sessionStorage`, and never
 * appended to a URL -- from the moment a person types it to the moment
 * {@link attempt} hands it to `JSON.stringify` for the one `fetch` body it is
 * ever part of. It is cleared from the field the instant that call returns,
 * on success or on refusal alike, so no failed attempt leaves a typed password
 * sitting in the DOM waiting for the next screen this tab shows.
 */

/** What this screen says about every refusal, and the only thing it says. */
export const LOGIN_FAILED = 'Sign-in failed. Check the handle and password.'

export interface LoginOptions {
    /** Where the form is built. Its children are replaced. */
    readonly root: HTMLElement
    /** Called once a session exists, with the flag `login` read off the header. */
    readonly onSignedIn: (mustChangePassword: boolean) => void
    /** Unused by this screen today; carried for the shape every screen shares. */
    readonly transport?: Transport
    /**
     * How the credentials reach the server, so a test can replace the one call
     * that has to read a response header rather than a parsed body. Defaults
     * to {@link login}. See this file's header for why `transport.post`
     * cannot be used here.
     *
     * @returns whether the new session must still change its password
     * @throws whatever the server refused with, or a transport failure -- this
     *     screen shows {@link LOGIN_FAILED} for either, never the rejection's
     *     own message
     */
    readonly login?: (handle: string, password: string) => Promise<boolean>
}

/**
 * `POST /v1/auth/login`, cookies only: no `X-Plowshare-Token-Delivery`
 * header, so the pair never reaches this function at all.
 *
 * Calls `fetch` directly rather than `api.request` or `api.post` -- see this
 * file's header for why the retry-on-401 machinery both of those carry does
 * not belong on a login attempt, and why there is no body worth parsing on
 * the 204 this gets back on success in any case.
 */
async function login(handle: string, password: string): Promise<boolean> {
    const response = await fetch('/v1/auth/login', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ handle, password }),
    })
    if (!response.ok) {
        // Deliberately not this server's own sentence, if it ever sent one:
        // `AuthController.login` answers a refused attempt with an empty
        // body today, but even a future sentence must not reach this screen
        // -- see this file's header on why every refusal says one thing.
        throw new ApiError(LOGIN_FAILED, response.status)
    }
    return response.headers.get('X-Plowshare-Must-Change-Password') === 'true'
}

export function createLogin(options: LoginOptions): Screen {
    const submit = options.login ?? login

    const shell = el('section', 'screen login')
    const head = el('header', 'screen-head')
    const title = el('h2', 'screen-title', 'sign in')
    head.append(title)

    const body = el('div', 'login-form')
    body.dataset['login'] = ''

    const handleField = document.createElement('input')
    handleField.type = 'text'
    handleField.autocomplete = 'username'
    handleField.dataset['input'] = 'handle'

    const passwordField = document.createElement('input')
    passwordField.type = 'password'
    passwordField.autocomplete = 'current-password'
    passwordField.dataset['input'] = 'password'

    const signIn = button('submit', 'sign in')

    body.append(labelled('handle', handleField), labelled('password', passwordField), signIn)
    shell.append(head, body)
    options.root.replaceChildren(shell)

    let problem: HTMLElement | null = null

    function clearProblem(): void {
        if (problem !== null) {
            problem.remove()
            problem = null
        }
    }

    function attempt(): void {
        if (signIn.disabled) {
            return
        }
        clearProblem()
        signIn.disabled = true
        const handle = handleField.value
        const password = passwordField.value
        void submit(handle, password)
            .then((mustChangePassword) => {
                passwordField.value = ''
                options.onSignedIn(mustChangePassword)
            })
            .catch(() => {
                passwordField.value = ''
                signIn.disabled = false
                problem = trouble(LOGIN_FAILED)
                body.append(problem)
            })
    }

    signIn.addEventListener('click', () => attempt())
    for (const field of [handleField, passwordField]) {
        field.addEventListener('keydown', (event) => {
            if (event.key === 'Enter') {
                attempt()
            }
        })
    }

    return {
        element: () => shell,
        async load(): Promise<void> {
            // Nothing to read on mount: the form has no state the server owns
            // until somebody submits it.
        },
        destroy(): void {
            // Nothing running: no socket, no timer, no in-flight poll.
        },
    }
}
