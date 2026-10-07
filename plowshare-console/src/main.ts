import { background } from './background.ts';
import { bootstrapFromUrl, type BootstrapOutcome } from './auth';
import { createLogin } from './screens/login';
import { createPassword } from './screens/password';
import { createShell } from './screens/shell';
import { mountStyles } from './repl/styles';

/**
 * The entry point: spend the bootstrap token, then gate the console behind a
 * session.
 *
 * The first part has not changed and is the one thing that must happen on
 * every first load, whatever is built on top of it -- `auth.ts` spends the
 * token that is in the URL and gets it out of the URL, and the strip happens
 * whether the exchange worked or not.
 *
 * **The shell is no longer built unconditionally.** Before password auth
 * existed there was nothing to gate on: a browser either had the cookie
 * `auth.ts` had just set or it did not, and either way the shell was the only
 * thing to show -- an ungated screen surfaced `api.ts`'s own `SIGNED_OUT`
 * sentence the first time it tried to read something. Now that a session can
 * be started with a password instead of only a bootstrap token, a browser
 * with no cookie at all deserves a form to type one into rather than a shell
 * that fails on its first request. {@link probeSession} asks `GET
 * /v1/auth/session` -- the same one-bit read `AuthController`'s class note
 * "A fifth door" describes -- and {@link gate} acts on the answer:
 *
 * 1. **A session, not flagged** -- the shell.
 * 2. **A session, flagged `must_change_password`** -- `password.ts`'s screen,
 *    reached whether the flag was learned from this probe on a reload or
 *    from `login.ts`'s own response header on a fresh sign-in. Nothing about
 *    which admin it is needs to travel with that flag -- see `password.ts`'s
 *    own header for why the endpoint it posts to no longer asks.
 * 3. **No session** -- `login.ts`'s form. Signing in there calls back with
 *    the same flag `AuthController.login` puts on its own response header,
 *    so a fresh sign-in reaches the same fork as a reload would without
 *    asking the server a second time.
 * 4. **Session status unavailable** -- a retryable availability screen. A
 *    network failure or unhealthy server does not establish that a session ended.
 *
 * `absent`, `exchanged` and `refused` -- {@link BootstrapOutcome} -- are a
 * separate question from the four above and orthogonal to it: they say
 * whether *this page load* just spent a bootstrap token, not whether a
 * session exists now. A `refused` token still gates on {@link probeSession}
 * rather than assuming signed-out, because the cookie in the jar from an
 * earlier exchange may still be good -- the same reasoning this file held
 * before the gate existed, just carried out by an actual probe instead of an
 * assumption.
 *
 * There is no `innerHTML` here and there will not be one. Everything below is
 * `createElement` and `textContent`.
 */
const MESSAGES: Readonly<Record<BootstrapOutcome, string>> = {
  exchanged: '',
  absent: '',
  refused:
    'That bootstrap token was refused — it is single-use, so a reload spends nothing.' +
    ' If this console cannot reach the server below, restart it and open the URL it' +
    ' prints.',
};

/** `GET /v1/auth/session`'s two questions, folded into one answer for {@link gate}. */
export type SessionState =
  'signed-out' | 'signed-in' | 'flagged' | 'unavailable';

/**
 * `GET /v1/auth/session`: is there a session, and must it still change its
 * password.
 *
 * A raw `fetch` and not `api.request`, for `login.ts`'s reason: this call can
 * meet a 401 with no session behind it at all, which is not the expired-access
 * case `api.request`'s refresh-and-retry exists for, and firing a refresh
 * attempt on every signed-out page load would spend a cookie this probe has
 * no business touching. `credentials: 'same-origin'` is still set, because an
 * existing `ps_access` cookie is exactly what this call is trying to find.
 *
 * An unreachable or unhealthy server is distinct from signed-out. The gate
 * offers a read-only retry without asking the operator to enter credentials
 * into a screen that has not established whether a session already exists.
 */
export async function probeSession(): Promise<SessionState> {
  let response: Response;
  try {
    response = await fetch('/v1/auth/session', {
      method: 'GET',
      credentials: 'same-origin',
    });
  } catch {
    return 'unavailable';
  }
  if (response.status === 401 || response.status === 403) return 'signed-out';
  if (response.status !== 204) return 'unavailable';
  return response.headers.get('X-Plowshare-Must-Change-Password') === 'true'
    ? 'flagged'
    : 'signed-in';
}

/** The shell, built the same way regardless of which branch of {@link gate} reached it. */
function mountShell(host: HTMLElement): void {
  const shell = createShell({ root: host });
  background(shell.start());
}

/**
 * `password.ts`'s screen, wired to return to `login.ts`'s on success -- see
 * this file's header on why the server ends the session over the change and
 * why that is the correct outcome rather than a bug to route around.
 */
function mountPassword(host: HTMLElement): void {
  const password = createPassword({
    root: host,
    onChanged: () => mountLogin(host),
  });
  background(password.load());
}

/**
 * What happens once a session exists: the shell for an ordinary sign-in,
 * `password.ts`'s screen for a flagged one.
 */
function afterSignIn(host: HTMLElement, mustChangePassword: boolean): void {
  if (mustChangePassword) {
    mountPassword(host);
  } else {
    mountShell(host);
  }
}

/** `login.ts`'s form, wired to {@link afterSignIn} on success. */
function mountLogin(host: HTMLElement): void {
  const login = createLogin({
    root: host,
    onSignedIn: (mustChangePassword) => afterSignIn(host, mustChangePassword),
  });
  background(login.load());
}

/**
 * The four screens {@link gate} can mount, as one object so a test can
 * replace them without stubbing `fetch` or exercising `createShell`'s real
 * socket and polling. {@link probeSession} is included for the same reason:
 * a test wants to choose the session state directly rather than construct a
 * `Response` for every one of its four outcomes.
 */
export interface GateDeps {
  readonly probe: () => Promise<SessionState>;
  readonly mountShell: (host: HTMLElement) => void;
  readonly mountLogin: (host: HTMLElement) => void;
  readonly mountPassword: (host: HTMLElement) => void;
  readonly mountUnavailable: (host: HTMLElement) => void;
}

const REAL_DEPS: GateDeps = {
  probe: probeSession,
  mountShell,
  mountLogin,
  mountPassword,
  mountUnavailable,
};

/**
 * Route `host` to one of the four screens this file's header describes, per
 * {@link probeSession}'s answer -- or per `deps.probe`'s, for a test that
 * wants to choose the answer directly.
 *
 * A `flagged` session mounts `password.ts` unconditionally: the endpoint it
 * posts to no longer needs to be told which admin it is, so there is nothing
 * this function needs in hand before it can mount that screen.
 *
 * @param deps the real screens and the real probe by default; a caller
 *     wanting to observe which screen would be mounted, without paying for
 *     what `createShell` and `createLogin` actually do, supplies fakes here
 */
export async function gate(
  host: HTMLElement,
  deps: GateDeps = REAL_DEPS,
): Promise<void> {
  const state = await deps.probe();
  if (state === 'unavailable') {
    deps.mountUnavailable(host);
  } else if (state === 'signed-out') {
    deps.mountLogin(host);
  } else if (state === 'flagged') {
    deps.mountPassword(host);
  } else {
    deps.mountShell(host);
  }
}

/** Availability failure has a visible retry and never spends refresh cookies. */
function mountUnavailable(host: HTMLElement): void {
  const panel = document.createElement('section');
  const message = document.createElement('p');
  message.setAttribute('role', 'alert');
  message.textContent =
    'The server could not be reached or is unavailable. Your session state has not been established.';
  const retry = document.createElement('button');
  retry.type = 'button';
  retry.textContent = 'Retry connection';
  retry.addEventListener('click', () => {
    retry.disabled = true;
    background(gate(host));
  });
  panel.append(message, retry);
  host.replaceChildren(panel);
}

function render(outcome: BootstrapOutcome): void {
  const root = document.getElementById('console');
  if (root === null) {
    return;
  }
  mountStyles(root.ownerDocument);
  const host = document.createElement('div');
  host.className = 'host';
  const message = MESSAGES[outcome];
  if (message === '') {
    root.replaceChildren(host);
  } else {
    const banner = document.createElement('p');
    banner.className = 'banner';
    banner.textContent = message;
    root.replaceChildren(banner, host);
  }
  background(gate(host));
}

background(bootstrapFromUrl().then(render));
