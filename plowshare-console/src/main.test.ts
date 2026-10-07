import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { gate, probeSession, type GateDeps, type SessionState } from './main';

/**
 * `gate`'s branching, held apart from `render`/`bootstrapFromUrl` and from
 * what `createShell`/`createLogin`/`createPassword` actually build.
 *
 * `main.ts`'s top-level `void bootstrapFromUrl().then(render)` runs once, on
 * import, and is not exercised here: this file's jsdom URL carries no
 * `?token=`, so `bootstrapFromUrl` reads `'absent'` without a network call,
 * and `render` finds no `#console` element in this test's empty `document`
 * and returns having done nothing. That import-time effect is inert in this
 * environment rather than disabled for it -- there is no test seam guarding
 * it and none was added, since the three outcomes below are a property of
 * {@link gate} and not of whether the module happened to run its top-level
 * line first.
 *
 * `gate` takes an optional {@link GateDeps} for exactly this file: the real
 * default builds a live shell with a real socket and a real `createLogin`
 * that would need `fetch` and `EventSource` mocked out from under three
 * modules to exercise safely. Faking the three mount functions instead means
 * this file tests what it is actually supposed to -- which of the three
 * `gate` reaches for a given session state -- without also having to make
 * `createShell`'s polling behave. This is the one refactor `main.ts` needed
 * to become testable: `gate` did not previously take a `deps` parameter, and
 * `probeSession` and `gate` were not exported.
 */
function fakeDeps(probe: () => Promise<SessionState>): GateDeps & {
  readonly mountShell: ReturnType<typeof vi.fn>;
  readonly mountLogin: ReturnType<typeof vi.fn>;
  readonly mountPassword: ReturnType<typeof vi.fn>;
  readonly mountUnavailable: ReturnType<typeof vi.fn>;
} {
  return {
    probe,
    mountShell: vi.fn(),
    mountLogin: vi.fn(),
    mountPassword: vi.fn(),
    mountUnavailable: vi.fn(),
  };
}

let host: HTMLElement;

beforeEach(() => {
  host = document.createElement('div');
});

describe('gate', () => {
  it('mounts the shell for a session that is not flagged', async () => {
    const deps = fakeDeps(async () => 'signed-in');
    await gate(host, deps);

    expect(deps.mountShell).toHaveBeenCalledWith(host);
    expect(deps.mountLogin).not.toHaveBeenCalled();
    expect(deps.mountPassword).not.toHaveBeenCalled();
  });

  it(
    'mounts the password-change screen unconditionally for a session flagged ' +
      'must_change_password',
    async () => {
      const deps = fakeDeps(async () => 'flagged');
      await gate(host, deps);

      expect(deps.mountPassword).toHaveBeenCalledTimes(1);
      expect(deps.mountPassword).toHaveBeenCalledWith(host);
      expect(deps.mountShell).not.toHaveBeenCalled();
      expect(deps.mountLogin).not.toHaveBeenCalled();
    },
  );

  it('mounts the login form when there is no session at all', async () => {
    const deps = fakeDeps(async () => 'signed-out');
    await gate(host, deps);

    expect(deps.mountLogin).toHaveBeenCalledWith(host);
    expect(deps.mountShell).not.toHaveBeenCalled();
    expect(deps.mountPassword).not.toHaveBeenCalled();
  });
});

/**
 * `probeSession`'s own mapping from a real `fetch` response to a
 * {@link SessionState}, composed with the real `gate` -- so the fourth
 * outcome ("a probe failure lands on login, not a blank page") is pinned
 * end to end rather than only at `gate`'s branch, which by itself cannot
 * tell "signed-out because 401" from "signed-out because the network never
 * answered": both are `probeSession`'s job to fold together, and it is
 * exercised here doing exactly that.
 */
describe('probeSession, and gate acting on what it reports', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('a 204 with no flag reads as signed in, and gates to the shell', async () => {
    fetchMock.mockResolvedValue(
      new Response(null, {
        status: 204,
        headers: { 'X-Plowshare-Must-Change-Password': 'false' },
      }),
    );

    expect(await probeSession()).toBe('signed-in');

    const deps = fakeDeps(probeSession);
    await gate(host, deps);
    expect(deps.mountShell).toHaveBeenCalledWith(host);
  });

  it(
    'a 204 flagged must_change_password reads as flagged, and gates to the password ' +
      'screen',
    async () => {
      fetchMock.mockResolvedValue(
        new Response(null, {
          status: 204,
          headers: { 'X-Plowshare-Must-Change-Password': 'true' },
        }),
      );

      expect(await probeSession()).toBe('flagged');

      const deps = fakeDeps(probeSession);
      await gate(host, deps);
      expect(deps.mountPassword).toHaveBeenCalledWith(host);
    },
  );

  it('a 401 reads as signed out, and gates to the login form', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));

    expect(await probeSession()).toBe('signed-out');

    const deps = fakeDeps(probeSession);
    await gate(host, deps);
    expect(deps.mountLogin).toHaveBeenCalledWith(host);
  });

  it('a transport failure remains distinct from signed out and offers an availability screen', async () => {
    fetchMock.mockRejectedValue(new TypeError('offline'));

    await expect(probeSession()).resolves.toBe('unavailable');

    const deps = fakeDeps(probeSession);
    await gate(host, deps);
    expect(deps.mountUnavailable).toHaveBeenCalledWith(host);
    expect(deps.mountLogin).not.toHaveBeenCalled();
    expect(deps.mountShell).not.toHaveBeenCalled();
    expect(deps.mountPassword).not.toHaveBeenCalled();
  });
});

/**
 * End to end through the real screens rather than `fakeDeps`: `gate(host)`
 * with no `deps` argument wires `mountPassword` and `mountLogin` to the real
 * `createPassword`/`createLogin`, with only `fetch` stubbed, the same way
 * `password.test.ts` exercises the real `changePassword` call. This pins
 * that a real password change still lands back on `login.ts`'s screen now
 * that no handle travels between the two.
 */
describe('a real password change, end to end through gate', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn(async (path: string) => {
      if (path === '/v1/auth/session') {
        return new Response(null, {
          status: 204,
          headers: { 'X-Plowshare-Must-Change-Password': 'true' },
        });
      }
      if (path === '/v1/auth/password') {
        return new Response(null, { status: 204 });
      }
      throw new Error(`this test did not expect a fetch to ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('returns to login once the change goes through', async () => {
    await gate(host);

    const current = host.querySelector(
      '[data-input="current"]',
    ) as HTMLInputElement;
    const next = host.querySelector('[data-input="new"]') as HTMLInputElement;
    const confirm = host.querySelector(
      '[data-input="confirm"]',
    ) as HTMLInputElement;
    const submit = host.querySelector('.submit') as HTMLButtonElement;
    current.value = 'old-secret';
    next.value = 'new-secret';
    confirm.value = 'new-secret';
    submit.click();

    await vi.waitFor(() =>
      expect(host.querySelector('[data-login]')).not.toBeNull(),
    );
  });
});
