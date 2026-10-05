import { displayText } from '../../../sdk/typescript/src/binding/values.ts';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createPassword,
  PASSWORDS_DO_NOT_MATCH,
  SESSION_WILL_END,
  SOMETHING_WENT_WRONG,
  UNACCEPTABLE_NEW_PASSWORD,
  WHY_HERE,
  WRONG_CURRENT_PASSWORD,
} from './password';
import type { Screen } from './screen';

let root: HTMLElement;
let screen: Screen;
let onChanged: ReturnType<typeof vi.fn>;

function currentField(): HTMLInputElement {
  return root.querySelector('[data-input="current"]') as HTMLInputElement;
}

function newField(): HTMLInputElement {
  return root.querySelector('[data-input="new"]') as HTMLInputElement;
}

function confirmField(): HTMLInputElement {
  return root.querySelector('[data-input="confirm"]') as HTMLInputElement;
}

function submitButton(): HTMLButtonElement {
  return root.querySelector('.submit') as HTMLButtonElement;
}

function trouble(): string | null {
  return root.querySelector('[data-trouble]')?.textContent ?? null;
}

/**
 * Every value held in a `Storage`, or `[]` when this jsdom build has no
 * `localStorage`/`sessionStorage` at all -- see `login.test.ts`'s own copy of
 * this helper for why the absence itself is a pass here too.
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

function fillAndSubmit(current: string, next: string, confirm: string): void {
  currentField().value = current;
  newField().value = next;
  confirmField().value = confirm;
  submitButton().click();
}

beforeEach(() => {
  root = document.createElement('main');
  document.body.replaceChildren(root);
  onChanged = vi.fn();
});

afterEach(() => {
  screen.destroy();
});

describe('createPassword', () => {
  it('renders three password fields and says why the change is demanded', async () => {
    screen = createPassword({ root, onChanged, changePassword: vi.fn() });
    await screen.load();

    expect(currentField().type).toBe('password');
    expect(newField().type).toBe('password');
    expect(confirmField().type).toBe('password');
    expect(root.textContent ?? '').toContain(WHY_HERE);
  });

  it('says the session is about to end, before anyone has pressed the button', async () => {
    screen = createPassword({ root, onChanged, changePassword: vi.fn() });
    await screen.load();

    expect(root.textContent ?? '').toContain(SESSION_WILL_END);
  });

  it('catches mismatched new passwords before any request is made', async () => {
    const changePassword = vi.fn(async () => undefined);
    screen = createPassword({ root, onChanged, changePassword });
    await screen.load();

    fillAndSubmit('current-pw', 'new-one', 'new-two');

    expect(trouble()).toBe(PASSWORDS_DO_NOT_MATCH);
    expect(changePassword).not.toHaveBeenCalled();
    expect(onChanged).not.toHaveBeenCalled();
  });

  it('calls onChanged once the server confirms the change', async () => {
    const changePassword = vi.fn(async () => undefined);
    screen = createPassword({ root, onChanged, changePassword });
    await screen.load();

    fillAndSubmit('old-secret', 'new-secret', 'new-secret');
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
  });

  it('renders a 401 as the current password being wrong', async () => {
    const changePassword = vi.fn(async () => {
      throw Object.assign(new Error(WRONG_CURRENT_PASSWORD), { status: 401 });
    });
    screen = createPassword({ root, onChanged, changePassword });
    await screen.load();

    fillAndSubmit('wrong-current', 'new-secret', 'new-secret');
    await vi.waitFor(() => expect(trouble()).not.toBeNull());

    expect(trouble()).toBe(WRONG_CURRENT_PASSWORD);
    expect(onChanged).not.toHaveBeenCalled();
  });

  it('renders a 400 as the new password not being acceptable, unlike a 401', async () => {
    const changePassword = vi.fn(async () => {
      throw Object.assign(new Error(UNACCEPTABLE_NEW_PASSWORD), {
        status: 400,
      });
    });
    screen = createPassword({ root, onChanged, changePassword });
    await screen.load();

    fillAndSubmit('old-secret', 'placeholder', 'placeholder');
    await vi.waitFor(() => expect(trouble()).not.toBeNull());

    expect(trouble()).toBe(UNACCEPTABLE_NEW_PASSWORD);
    expect(trouble()).not.toBe(WRONG_CURRENT_PASSWORD);
    expect(onChanged).not.toHaveBeenCalled();
  });

  it('falls back to a generic message for a failure that names neither status', async () => {
    const changePassword = vi.fn(async () => {
      throw new Error('');
    });
    screen = createPassword({ root, onChanged, changePassword });
    await screen.load();

    fillAndSubmit('old-secret', 'new-secret', 'new-secret');
    await vi.waitFor(() => expect(trouble()).not.toBeNull());

    expect(trouble()).toBe(SOMETHING_WENT_WRONG);
  });

  it('re-enables the form after a refusal, so a retry is possible', async () => {
    const changePassword = vi.fn(async () => {
      throw Object.assign(new Error(WRONG_CURRENT_PASSWORD), { status: 401 });
    });
    screen = createPassword({ root, onChanged, changePassword });
    await screen.load();

    fillAndSubmit('wrong-current', 'new-secret', 'new-secret');
    await vi.waitFor(() => expect(trouble()).not.toBeNull());

    expect(submitButton().disabled).toBe(false);
  });

  it('no password appears in any attribute, storage, or URL after a successful change', async () => {
    const changePassword = vi.fn(async () => undefined);
    screen = createPassword({ root, onChanged, changePassword });
    await screen.load();

    const current = 'hunter2-current-does-not-belong-anywhere';
    const next = 'hunter3-new-does-not-belong-anywhere';
    fillAndSubmit(current, next, next);
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());

    for (const secret of [current, next]) {
      expect(root.innerHTML).not.toContain(secret);
      expect(document.location.href).not.toContain(secret);
      expect(storedValues(globalThis.localStorage)).not.toContain(secret);
      expect(storedValues(globalThis.sessionStorage)).not.toContain(secret);
    }
    expect(currentField().getAttribute('value')).toBeNull();
    expect(newField().getAttribute('value')).toBeNull();
    expect(confirmField().getAttribute('value')).toBeNull();
    expect(currentField().value).toBe('');
    expect(newField().value).toBe('');
    expect(confirmField().value).toBe('');
  });

  it('no password appears anywhere when the attempt is refused', async () => {
    const changePassword = vi.fn(async () => {
      throw Object.assign(new Error(WRONG_CURRENT_PASSWORD), { status: 401 });
    });
    screen = createPassword({ root, onChanged, changePassword });
    await screen.load();

    const secret = 'another-secret-value';
    fillAndSubmit(secret, 'new-secret', 'new-secret');
    await vi.waitFor(() => expect(trouble()).not.toBeNull());

    expect(root.innerHTML).not.toContain(secret);
    expect(root.innerHTML).not.toContain('new-secret');
    expect(currentField().value).toBe('');
    expect(newField().value).toBe('');
  });

  it(
    'no password appears in HTML, storage, or a URL when the client-side match check ' +
      'refuses first',
    async () => {
      screen = createPassword({
        root,
        onChanged,
        changePassword: vi.fn(),
      });
      await screen.load();

      const secret = 'typo-secret-one';
      fillAndSubmit(secret, secret, 'typo-secret-two');

      expect(root.innerHTML).not.toContain(secret);
      expect(root.innerHTML).not.toContain('typo-secret-two');
      expect(document.location.href).not.toContain(secret);
      expect(storedValues(globalThis.localStorage)).not.toContain(secret);
      expect(storedValues(globalThis.sessionStorage)).not.toContain(secret);
    },
  );

  it(
    'clears only the new and confirm fields on a mismatch, leaving the current ' +
      'password that never left the browser where it was',
    async () => {
      screen = createPassword({
        root,
        onChanged,
        changePassword: vi.fn(),
      });
      await screen.load();

      fillAndSubmit('current-secret', 'new-one', 'new-two');

      expect(trouble()).toBe(PASSWORDS_DO_NOT_MATCH);
      expect(currentField().value).toBe('current-secret');
      expect(newField().value).toBe('');
      expect(confirmField().value).toBe('');
    },
  );
});

/**
 * The default `changePassword` -- the one this screen uses when a caller
 * supplies no `changePassword` option -- goes around `Transport` and calls
 * `fetch` itself, for the reason this file's header gives. Exercised the way
 * `login.test.ts` exercises the real `login` call: by stubbing the global
 * rather than injecting a fake.
 */
describe('the real changePassword call', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('posts both passwords, and no handle, as JSON to /v1/auth/password', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    screen = createPassword({ root, onChanged });
    await screen.load();

    fillAndSubmit('old-secret', 'new-secret', 'new-secret');
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/v1/auth/password');
    expect(init.credentials).toBe('same-origin');
    expect(JSON.parse(displayText(init.body))).toEqual({
      currentPassword: 'old-secret',
      newPassword: 'new-secret',
    });
  });

  it('renders the 401 and 400 the server actually answers with, distinctly', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 401 }));
    screen = createPassword({ root, onChanged });
    await screen.load();

    fillAndSubmit('wrong', 'new-secret', 'new-secret');
    await vi.waitFor(() => expect(trouble()).toBe(WRONG_CURRENT_PASSWORD));

    fetchMock.mockResolvedValueOnce(new Response(null, { status: 400 }));
    fillAndSubmit('old-secret', 'placeholder', 'placeholder');
    await vi.waitFor(() => expect(trouble()).toBe(UNACCEPTABLE_NEW_PASSWORD));
  });

  it('reports a transport failure with the generic message, not a raw error', async () => {
    fetchMock.mockRejectedValue(new TypeError('network down'));
    screen = createPassword({ root, onChanged });
    await screen.load();

    fillAndSubmit('old-secret', 'new-secret', 'new-secret');
    await vi.waitFor(() => expect(trouble()).not.toBeNull());

    expect(trouble()).toBe(SOMETHING_WENT_WRONG);
    expect(root.textContent ?? '').not.toContain('network down');
  });
});
