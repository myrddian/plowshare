/**
 * The way in: spend the bootstrap token that is in this page's URL, and then
 * get it out of the URL.
 *
 * The server prints one line when it starts:
 *
 *     Plowshare console: http://127.0.0.1:8091/?token=<48 hex chars>
 *
 * Visiting that is how a browser is admitted. `POST /v1/auth` exchanges the
 * token for the `ps_access` / `ps_refresh` cookie pair and answers **204 with
 * no body** -- the pair arrives only as `Set-Cookie`, and both cookies are
 * `HttpOnly`, so nothing in this module ever sees a token value after the one
 * that came in the URL. That is the point rather than an inconvenience: this
 * console renders model output, file contents and memory bodies, and an XSS
 * takes whatever JavaScript is holding.
 */

/** The query parameter the server's startup line puts the token in. */
export const TOKEN_PARAM = 'token'

/** `POST /v1/auth`: the one endpoint that turns a bootstrap token into cookies. */
export const AUTH_PATH = '/v1/auth'

/**
 * What happened, for a caller that wants to say so on the screen.
 *
 * - `exchanged` -- 204, and the cookies are set.
 * - `refused` -- anything else, including a network failure. The commonest
 *   cause is a token that has already been spent: it is single-use, so a
 *   reload of the bootstrap URL is a 401 rather than a second admission.
 * - `absent` -- there was no token in the URL. Not a failure: it is the
 *   ordinary state of every navigation after the first, when the cookie is
 *   already in the jar.
 */
export type BootstrapOutcome = 'exchanged' | 'refused' | 'absent'

/**
 * Spend the token in the address bar, then remove it from the address bar.
 *
 * **The strip happens whatever the exchange did**, which is a deliberate
 * choice and not an oversight in the ordering. A token left in the URL is a
 * token in the browser's history, in the `Referer` of whatever this page
 * navigates to next, and on the screen in front of whoever is looking at it.
 * A token that has been *refused* is worth nothing and should not be left
 * lying there either. The one case this costs something is a network failure,
 * where the token is still unspent and is now gone from the bar -- and the
 * remedy for that is the line the server printed, which is still in the
 * terminal.
 *
 * `history.replaceState` and not `pushState`: the entry being replaced is the
 * one holding the token, and pushing would leave it behind in the history as
 * the previous entry, which is most of what this call exists to prevent.
 *
 * Only the token parameter is removed. Anything else in the query string is a
 * caller's and is put back, in case a later screen ever routes on one.
 *
 * @param scope the window to read and rewrite; the real one by default. It is a
 *     parameter so that a test can hand over a double, and NOT so that a caller
 *     can point this at another frame.
 */
export async function bootstrapFromUrl(scope: Window = window): Promise<BootstrapOutcome> {
    const url = new URL(scope.location.href)
    const token = url.searchParams.get(TOKEN_PARAM)
    if (token === null) {
        return 'absent'
    }
    try {
        const response = await fetch(AUTH_PATH, {
            method: 'POST',
            // Same-origin and not `include`: this page and this API are one
            // origin -- served from the jar in production, and behind the Vite
            // proxy in development, which is the whole reason that proxy
            // exists. `include` would additionally send credentials on a
            // cross-origin request, which is a capability nothing here needs.
            credentials: 'same-origin',
            // The server's `@PostMapping(consumes = APPLICATION_JSON_VALUE)`
            // answers 415 to a request that carries a body without this. It
            // answers 401 to one carrying no body at all, which is a different
            // path and not the one this call is on.
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token }),
        })
        return response.status === 204 ? 'exchanged' : 'refused'
    } catch {
        // A `fetch` rejection is a transport failure -- the server is not
        // there, or the page was closed mid-flight. It is reported as a
        // refusal because there is nothing a caller could do differently, and
        // the error object is deliberately not carried out of this function:
        // it was constructed from a request whose body held the token.
        return 'refused'
    } finally {
        strip(scope, url)
    }
}

/** Rewrite the address bar to the same URL without the token parameter. */
function strip(scope: Window, url: URL): void {
    url.searchParams.delete(TOKEN_PARAM)
    const query = url.searchParams.toString()
    scope.history.replaceState(
        scope.history.state,
        '',
        url.pathname + (query === '' ? '' : `?${query}`) + url.hash,
    )
}
