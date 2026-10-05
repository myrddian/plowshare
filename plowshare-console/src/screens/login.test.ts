import { displayText } from '../../../sdk/typescript/src/binding/values.ts';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createLogin, LOGIN_FAILED } from './login';
import type { Screen } from './screen';

let root: HTMLElement;
let screen: Screen;
let onSignedIn: ReturnType<typeof vi.fn>;

function handleField(): HTMLInputElement {
  return root.querySelector('[data-input="handle"]') as HTMLInputElement;
}

function passwordField(): HTMLInputElement {
  return root.querySelector('[data-input="password"]') as HTMLInputElement;
}

function submitButton(): HTMLButtonElement {
  return root.querySelector('.submit') as HTMLButtonElement;
}

function trouble(): string | null {
  return root.querySelector('[data-trouble]')?.textContent ?? null;
}

/**
 * Every value held in a `Storage`, or `[]` when this jsdom build has no
 * `localStorage` at all -- this project's vitest/jsdom combination leaves
 * `window.localStorage` `undefined` rather than an empty store, so the
 * absence itself is a pass and not a thing to work around.
 */
function storedValues(storage: Storage | undefined): string[] {
  if (storage === undefined) {
    return [];
  }
  const values: string[] = [];
  for (let i = 0; i < storage.length; i += 1) {
    const key = storage.key(i) as string;
    values.push(storage.getItem(key) ?? '');
  }
  return values;
}

function fillAndSubmit(handle: string, password: string): void {
  handleField().value = handle;
  passwordField().value = password;
  submitButton().click();
}

beforeEach(() => {
  root = document.createElement('main');
  document.body.replaceChildren(root);
  onSignedIn = vi.fn();
});

afterEach(() => {
  screen.destroy();
});

describe('createLogin', () => {
  it('renders a handle field and a password field typed as a password', async () => {
    screen = createLogin({ root, onSignedIn, login: vi.fn() });
    await screen.load();

    expect(handleField()).not.toBeNull();
    expect(passwordField().type).toBe('password');
  });

  it('offers no "forgot password" control', async () => {
    screen = createLogin({ root, onSignedIn, login: vi.fn() });
    await screen.load();

    expect(root.textContent?.toLowerCase() ?? '').not.toContain('forgot');
  });

  it('submits the typed handle and password to the login call', async () => {
    const login = vi.fn(async () => false);
    screen = createLogin({ root, onSignedIn, login });
    await screen.load();

    fillAndSubmit('operator', 's3cret');
    await Promise.resolve();

    expect(login).toHaveBeenCalledWith('operator', 's3cret');
  });

  it('calls onSignedIn with the mustChangePassword flag on success', async () => {
    const login = vi.fn(async () => true);
    screen = createLogin({ root, onSignedIn, login });
    await screen.load();

    fillAndSubmit('operator', 's3cret');
    await vi.waitFor(() => expect(onSignedIn).toHaveBeenCalledWith(true));
  });

  it('shows only the generic refusal on failure, never a distinguishing reason', async () => {
    // The server answers an unknown handle and a wrong password
    // identically -- this screen must not reintroduce a distinction the
    // server deliberately declines to make.
    const login = vi.fn(async () => {
      throw new Error('no such admin: operator');
    });
    screen = createLogin({ root, onSignedIn, login });
    await screen.load();

    fillAndSubmit('operator', 'wrong');
    await vi.waitFor(() => expect(trouble()).not.toBeNull());

    expect(trouble()).toBe(LOGIN_FAILED);
    expect(root.textContent ?? '').not.toContain('no such admin');
    expect(onSignedIn).not.toHaveBeenCalled();
  });

  it('re-enables the form after a refusal, so a retry is possible', async () => {
    const login = vi.fn(async () => {
      throw new Error('refused');
    });
    screen = createLogin({ root, onSignedIn, login });
    await screen.load();

    fillAndSubmit('operator', 'wrong');
    await vi.waitFor(() => expect(trouble()).not.toBeNull());

    expect(submitButton().disabled).toBe(false);
  });

  it('the password appears in no attribute, no storage, and no URL after submit', async () => {
    const login = vi.fn(async () => false);
    screen = createLogin({ root, onSignedIn, login });
    await screen.load();

    const secret = 'hunter2-does-not-belong-anywhere';
    fillAndSubmit('operator', secret);
    await vi.waitFor(() => expect(onSignedIn).toHaveBeenCalled());

    expect(passwordField().getAttribute('value')).toBeNull();
    expect(root.innerHTML).not.toContain(secret);
    expect(document.location.href).not.toContain(secret);
    expect(storedValues(globalThis.localStorage)).not.toContain(secret);
    expect(storedValues(globalThis.sessionStorage)).not.toContain(secret);
    // The field itself is cleared once the attempt settles, so the value
    // does not linger even in the live DOM property.
    expect(passwordField().value).toBe('');
  });

  it('the password appears nowhere even when the attempt is refused', async () => {
    const login = vi.fn(async () => {
      throw new Error('refused');
    });
    screen = createLogin({ root, onSignedIn, login });
    await screen.load();

    const secret = 'another-secret-value';
    fillAndSubmit('operator', secret);
    await vi.waitFor(() => expect(trouble()).not.toBeNull());

    expect(root.innerHTML).not.toContain(secret);
    expect(passwordField().value).toBe('');
  });
});

/**
 * The default `login` -- the one this screen uses when a caller supplies no
 * `login` option -- goes around `Transport` and calls `fetch` itself, for the
 * reason this file's header gives: it has to read a response header, and
 * `Transport.post` throws the response away after parsing its body. That seam
 * is exercised here the way `auth.test.ts` exercises `bootstrapFromUrl`'s own
 * direct `fetch` call: by stubbing the global rather than injecting a fake.
 */
describe('the real login call', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('posts JSON to /v1/auth/login with no token-delivery header', async () => {
    fetchMock.mockResolvedValue(
      new Response(null, {
        status: 204,
        headers: { 'X-Plowshare-Must-Change-Password': 'false' },
      }),
    );
    screen = createLogin({ root, onSignedIn });
    await screen.load();

    fillAndSubmit('operator', 's3cret');
    await vi.waitFor(() => expect(onSignedIn).toHaveBeenCalled());

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/v1/auth/login');
    expect(init.credentials).toBe('same-origin');
    expect(
      new Headers(init.headers).get('X-Plowshare-Token-Delivery'),
    ).toBeNull();
    expect(JSON.parse(displayText(init.body))).toEqual({
      handle: 'operator',
      password: 's3cret',
    });
  });

  it('reads mustChangePassword off the response header, not a body', async () => {
    fetchMock.mockResolvedValue(
      new Response(null, {
        status: 204,
        headers: { 'X-Plowshare-Must-Change-Password': 'true' },
      }),
    );
    screen = createLogin({ root, onSignedIn });
    await screen.load();

    fillAndSubmit('operator', 's3cret');
    await vi.waitFor(() => expect(onSignedIn).toHaveBeenCalledWith(true));
  });

  it('shows the generic refusal on a 401, and calls fetch exactly once', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));
    screen = createLogin({ root, onSignedIn });
    await screen.load();

    fillAndSubmit('operator', 'wrong');
    await vi.waitFor(() => expect(trouble()).not.toBeNull());

    expect(trouble()).toBe(LOGIN_FAILED);
    // One call, and no refresh chase: `AuthController.login`'s 401 is a
    // refusal of the credentials just presented, not an expired session,
    // and there is no cookie yet for `api.request`'s retry to spend.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
