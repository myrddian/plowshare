import { ApiError } from '../api';
import { button, el, labelled, problemText, trouble } from './dom';
import type { Screen, Transport } from './screen';

/**
 * The one door out of `must_change_password`: current password, new
 * password, confirmation, gone.
 *
 * <h2>Why this exists at all</h2>
 *
 * Every admin `AuthController`'s seed creates starts flagged
 * `must_change_password`, and a flagged session is minted with an access
 * token and **no refresh token** -- see that class's note on the flag for why
 * withholding the refresh cookie is deliberate. Fifteen minutes later that
 * access token is dead and there is nothing to renew it with, so a flagged
 * admin with no way to clear the flag signs in again every fifteen minutes
 * forever. This screen is the only thing on any interface that clears it.
 *
 * <h2>Why this request carries no handle</h2>
 *
 * It used to: `POST /v1/auth/password` took a handle in the body, back when
 * a grant this store issues carried a chain id and nothing that said whose
 * it was, so a valid session proved *a* caller was authenticated and nothing
 * about *which* admin it was. That is no longer true -- a session now knows
 * whose it is, and `AuthController.changePassword` reads the row to check
 * and update straight off the session rather than off anything the caller
 * names. There is no third field on this form for a handle, and nothing for
 * `main.ts` to carry to it either: the current-password check {@link
 * #WRONG_CURRENT_PASSWORD} covers is the only identity check this request
 * still needs, and it runs against whichever admin the session already
 * says it is.
 *
 * <h2>The match check is a typo check, not a security check</h2>
 *
 * {@link #PASSWORDS_DO_NOT_MATCH} is caught here, before {@link #submit} is
 * ever called, purely so that a person who mistyped their new password does
 * not find out by having it silently become something they cannot reproduce.
 * It proves nothing about what was typed being a good password, or about who
 * is typing it -- the server's own checks, {@link PasswordPolicy} and the
 * current-password verification below, are what actually decide either
 * question, and both run again on the server regardless of what this check
 * decided. A person editing this page's JavaScript in devtools to skip it
 * gains nothing: the server would simply refuse whatever mismatched pair
 * arrived, the same as it refuses one that never went through this check at
 * all.
 *
 * <h2>400 and 401 read differently on purpose</h2>
 *
 * `AuthController.changePassword`'s own note is explicit about why these two
 * differ where `login.ts`'s two failure cases do not: an unknown handle
 * and a wrong current password are indistinguishable 401s for the same
 * enumeration reason `login.ts` argues at length, but the new password is
 * only ever looked at *after* the current one has already verified who is
 * asking. There is nothing left to enumerate by then, so {@link
 * #UNACCEPTABLE_NEW_PASSWORD} can say exactly what went wrong without leaking
 * anything a caller could not already see for themselves, and folding it into
 * the same generic refusal as a wrong current password would hide the one
 * piece of information a legitimate caller here actually needs.
 *
 * <h2>Why this calls `fetch` directly, not `transport.post` or `api.request`</h2>
 *
 * Both take the retry-on-401 machinery `api.ts`'s header describes:
 * `request()` treats any 401 as an expired access token, spends the tab's
 * refresh cookie trying to renew it, and retries once. A wrong current
 * password is not an expired session, the same distinction `login.ts` draws
 * for its own 401 -- and the screen this endpoint answers to has, by
 * construction, no refresh cookie to spend anyway: a flagged session is
 * minted with none, which is the whole mechanism {@link #WHY_HERE} is
 * warning about. Routing through `request()` would spend a round trip on a
 * refresh that cannot succeed before finally reporting the wrong-password
 * refusal, on every mistyped attempt, for no benefit.
 *
 * <h2>After success: the server logs this session out, on purpose</h2>
 *
 * `AuthController.changePassword`'s note on its own success path: the new
 * hash is written, the chain behind whichever access credential made this
 * request is revoked, and both cookies come back expired. That is not a bug
 * to route around -- it is how the new password gets proven immediately
 * rather than trusted on faith -- so {@link PasswordOptions#onChanged} is
 * expected to return the browser to `login.ts`'s screen, and {@link
 * #SESSION_WILL_END} says so before the button is ever pressed. A screen that
 * changed a password and then acted surprised at being signed out would read
 * as broken; this one says up front what is about to happen.
 *
 * <h2>The password's whole lifetime in this module</h2>
 *
 * The same discipline `login.ts` states for its one field, applied to three:
 * each password lives in its input's `.value` -- never the `value`
 * *attribute*, never `localStorage` or `sessionStorage`, never a URL -- from
 * the keystroke that put it there to the moment {@link #attempt} either hands
 * it to `JSON.stringify` or decides not to. Every field that was part of a
 * request is cleared the instant that request's answer settles, so no
 * attempt that reached the server -- accepted or refused -- leaves a
 * password sitting in the DOM for the next screen this tab shows. The one
 * exception is deliberate and stays within this same discipline rather than
 * breaking it: the client-side match check refusing to submit at all clears
 * only the new and confirm fields, since nothing left the browser on that
 * path and the current password is exactly as safe sitting in its own
 * `.value` as it was the instant before the button was pressed. See {@link
 * clearMismatchedFields}.
 */

/** What this screen says when the new password does not match its confirmation. */
export const PASSWORDS_DO_NOT_MATCH =
  'New password and confirmation do not match.';

/** What this screen says for the endpoint's 401: the current password is wrong. */
export const WRONG_CURRENT_PASSWORD = 'That current password is wrong.';

/** What this screen says for the endpoint's 400: the new password is not acceptable. */
export const UNACCEPTABLE_NEW_PASSWORD = 'That is not an acceptable password.';

/** What this screen says for anything else: a transport failure, or a status neither above. */
export const SOMETHING_WENT_WRONG =
  'The password could not be changed. Try again.';

/** Said once, before anyone has typed anything, so the demand does not read as a bug. */
export const WHY_HERE =
  'This account still has the password it was created with, and the console will keep' +
  ' signing you out every fifteen minutes until it changes.';

/** Said once, so a successful change is not mistaken for being thrown out. */
export const SESSION_WILL_END =
  'Changing the password ends this session on purpose, so the new one is proven right away.' +
  ' You will land back on the sign-in screen -- sign in there with the new password.';

export interface PasswordOptions {
  /** Where the form is built. Its children are replaced. */
  readonly root: HTMLElement;
  /**
   * Called once the server has revoked this session over the change --
   * see this file's header on why that revocation happens. The caller is
   * expected to mount `login.ts`'s screen in response.
   */
  readonly onChanged: () => void;
  /** Unused by this screen today; carried for the shape every screen shares. */
  readonly transport?: Transport;
  /**
   * How the change reaches the server, so a test can replace the one call
   * that distinguishes a 400 from a 401 without stubbing `fetch`. Defaults
   * to {@link changePassword}. See this file's header for why
   * `transport.post` and `api.request` cannot be used here.
   *
   * @throws an {@link ApiError} whose message is one of
   *     {@link WRONG_CURRENT_PASSWORD}, {@link UNACCEPTABLE_NEW_PASSWORD}
   *     or {@link SOMETHING_WENT_WRONG} -- never the server's own words,
   *     for `login.ts`'s reason: a server that later starts writing a
   *     sentence for one of these codes must not have it leak past a
   *     screen that already decided what to say for that status.
   */
  readonly changePassword?: (
    currentPassword: string,
    newPassword: string,
  ) => Promise<void>;
}

/**
 * `POST /v1/auth/password`, cookies only: the same direct `fetch` `login.ts`
 * uses and for the reasons this file's header gives.
 */
async function changePassword(
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  let response: Response;
  try {
    response = await fetch('/v1/auth/password', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ currentPassword, newPassword }),
    });
  } catch {
    throw new ApiError(SOMETHING_WENT_WRONG, 0);
  }
  if (response.status === 204) {
    return;
  }
  if (response.status === 401) {
    throw new ApiError(WRONG_CURRENT_PASSWORD, 401);
  }
  if (response.status === 400) {
    throw new ApiError(UNACCEPTABLE_NEW_PASSWORD, 400);
  }
  throw new ApiError(SOMETHING_WENT_WRONG, response.status);
}

export function createPassword(options: PasswordOptions): Screen {
  const submit = options.changePassword ?? changePassword;

  const shell = el('section', 'screen password');
  const head = el('header', 'screen-head');
  const title = el('h2', 'screen-title', 'change password');
  head.append(title);

  const why = el('p', 'why', WHY_HERE);

  const body = el('div', 'password-form');
  body.dataset['password'] = '';

  const currentField = document.createElement('input');
  currentField.type = 'password';
  currentField.autocomplete = 'current-password';
  currentField.dataset['input'] = 'current';

  const newField = document.createElement('input');
  newField.type = 'password';
  newField.autocomplete = 'new-password';
  newField.dataset['input'] = 'new';

  const confirmField = document.createElement('input');
  confirmField.type = 'password';
  confirmField.autocomplete = 'new-password';
  confirmField.dataset['input'] = 'confirm';

  const notice = el('p', 'notice', SESSION_WILL_END);
  const change = button('submit', 'change password');

  body.append(
    labelled('current password', currentField),
    labelled('new password', newField),
    labelled('confirm new password', confirmField),
    notice,
    change,
  );
  shell.append(head, why, body);
  options.root.replaceChildren(shell);

  let problem: HTMLElement | null = null;

  function clearProblem(): void {
    if (problem !== null) {
      problem.remove();
      problem = null;
    }
  }

  /**
   * Every field, back to empty. Called whenever a request actually left
   * this tab and its answer has settled -- see this file's header on the
   * password's lifetime for why this runs on every such path out of
   * {@link attempt}, not only the successful one. The one path that never
   * reaches the network, the mismatch check below, uses {@link
   * clearMismatchedFields} instead.
   */
  function clearFields(): void {
    currentField.value = '';
    newField.value = '';
    confirmField.value = '';
  }

  /**
   * The new and confirm fields, back to empty -- not the current password
   * field. Used only by the mismatch check below: nothing has left this
   * tab on that path, so the current password typed a moment ago is still
   * exactly what it was, and wiping it too would cost a retype it did
   * nothing to earn. Leaving it in `currentField.value` is not a new
   * exposure -- it is the same DOM property this file's header already
   * counts as the password's rightful home while an attempt is in flight,
   * and it is cleared the same as everything else the moment any attempt
   * actually reaches the server.
   */
  function clearMismatchedFields(): void {
    newField.value = '';
    confirmField.value = '';
  }

  function attempt(): void {
    if (change.disabled) {
      return;
    }
    clearProblem();
    const current = currentField.value;
    const next = newField.value;
    const confirm = confirmField.value;

    // The typo check this file's header describes: it runs before
    // `submit` is ever called, and it exists only to catch a mistyped
    // new password before it becomes one nobody can reproduce. It is not
    // a substitute for the server's own checks, both of which still run
    // on every request that gets past it.
    if (next !== confirm) {
      clearMismatchedFields();
      problem = trouble(PASSWORDS_DO_NOT_MATCH);
      body.append(problem);
      return;
    }

    change.disabled = true;
    void submit(current, next)
      .then(() => {
        clearFields();
        options.onChanged();
      })
      .catch((err: unknown) => {
        clearFields();
        change.disabled = false;
        problem = trouble(problemText(err, SOMETHING_WENT_WRONG));
        body.append(problem);
      });
  }

  change.addEventListener('click', () => attempt());
  for (const field of [currentField, newField, confirmField]) {
    field.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') {
        attempt();
      }
    });
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
  };
}
