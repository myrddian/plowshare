import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from './api';

/** Every path `fetch` was called with, in order. */
let calls: string[];
let fetchMock: ReturnType<typeof vi.fn>;
let storage: Storage;

/**
 * A server whose access cookie has expired and whose refresh still works.
 *
 * Written as a behaving fake rather than a queue of canned responses, because
 * the concurrency test needs two requests interleaving against one server, and
 * a queue would answer them in an order that depends on scheduling.
 */
function serverWithExpiredAccess(refreshAnswers = 204): void {
  let signedIn = false;
  fetchMock.mockImplementation(async (path: string) => {
    calls.push(path);
    if (path === '/v1/auth/session')
      return new Response(null, { status: signedIn ? 204 : 401 });
    if (path === '/v1/auth/refresh') {
      signedIn = refreshAnswers === 204;
      return new Response(null, { status: refreshAnswers });
    }
    return signedIn
      ? new Response('[]', {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      : new Response(null, { status: 401 });
  });
}

beforeEach(() => {
  const saved = new Map<string, string>();
  storage = {
    get length() {
      return saved.size;
    },
    clear: () => saved.clear(),
    getItem: (key) => saved.get(key) ?? null,
    setItem: (key, value) => {
      saved.set(key, value);
    },
    removeItem: (key) => {
      saved.delete(key);
    },
    key: (index) => [...saved.keys()][index] ?? null,
  };
  vi.stubGlobal('localStorage', storage);
  calls = [];
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  Object.defineProperty(navigator, 'locks', {
    configurable: true,
    value: {
      request: (_name: string, callback: () => Promise<boolean>) => callback(),
    },
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  Reflect.deleteProperty(navigator, 'locks');
});

describe('api', () => {
  it('retries exactly once through refresh on a 401, and not forever', async () => {
    serverWithExpiredAccess();

    await expect(api.get('/v1/jobs')).resolves.toEqual([]);

    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(calls).toEqual([
      '/v1/jobs',
      '/v1/auth/session',
      '/v1/auth/refresh',
      '/v1/jobs',
    ]);
  });

  it('attaches one fresh intent per rotation without persisting it or resubmitting after loss', async () => {
    const intents: string[] = [];
    let signedIn = false;
    let loseResponse = false;
    fetchMock.mockImplementation(async (path: string, init?: RequestInit) => {
      calls.push(path);
      if (path === '/v1/auth/session')
        return new Response(null, { status: signedIn ? 204 : 401 });
      if (path === '/v1/auth/refresh') {
        const intent = new Headers(init?.headers).get(
          'X-Plowshare-Refresh-Intent',
        );
        if (intent === null) throw new Error('refresh omitted its intent');
        intents.push(intent);
        if (loseResponse) throw new TypeError('response lost');
        signedIn = true;
        return new Response(null, { status: 204 });
      }
      return signedIn
        ? new Response('[]', { status: 200 })
        : new Response(null, { status: 401 });
    });
    await api.get('/v1/jobs');
    expect(intents[0]).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(storage.length).toBe(0);
    signedIn = false;
    loseResponse = true;
    await expect(api.get('/v1/jobs')).rejects.toThrow('response lost');
    expect(intents[1]).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(intents[1]).not.toBe(intents[0]);
    expect(storage.getItem('plowshare-session-refresh-uncertain')).toBe(
      'pending',
    );
    await expect(api.get('/v1/jobs')).rejects.toThrow(/uncertain/);
    expect(intents).toHaveLength(2);
  });

  it('gives up rather than looping when the refresh itself is refused', async () => {
    // THE SELF-INFLICTED LOGOUT THIS GUARDS AGAINST: a spent refresh token
    // retires the whole chain on the server, so a client that retries a
    // refused refresh is not being resilient -- it is destroying the
    // credential it still has. Two calls, and the second one is the last.
    serverWithExpiredAccess(401);

    await expect(api.get('/v1/jobs')).rejects.toThrow(/sign in again/);

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(calls).toEqual(['/v1/jobs', '/v1/auth/session', '/v1/auth/refresh']);
  });

  it('gives up when the retry is refused too, without a second refresh', async () => {
    // A freshly rotated access cookie that is refused immediately is not an
    // expiry, and refreshing again would be the loop the count above exists
    // to rule out.
    fetchMock.mockImplementation(async (path: string) => {
      calls.push(path);
      return path === '/v1/auth/refresh'
        ? new Response(null, { status: 204 })
        : new Response(null, { status: 401 });
    });

    await expect(api.get('/v1/jobs')).rejects.toThrow(/sign in again/);

    expect(calls).toEqual([
      '/v1/jobs',
      '/v1/auth/session',
      '/v1/auth/refresh',
      '/v1/jobs',
    ]);
  });

  it('refreshes once for two requests that expire together', async () => {
    // Two screens polling at once both meet the same 401. Two refreshes
    // would present the same refresh cookie twice, the second one after the
    // first had spent it -- which retires the chain and logs the console
    // out. One shared in-flight refresh is what stops that.
    serverWithExpiredAccess();

    await expect(
      Promise.all([api.get('/v1/jobs'), api.get('/v1/projects')]),
    ).resolves.toEqual([[], []]);

    expect(calls.filter((path) => path === '/v1/auth/refresh')).toHaveLength(1);
    expect(calls).toHaveLength(6);
  });

  it('refreshes again for an expiry that comes later', async () => {
    // The shared promise is cleared when it settles, so the latch above is
    // a concurrency guard and not a once-per-page-load guard.
    serverWithExpiredAccess();
    await api.get('/v1/jobs');

    serverWithExpiredAccess();
    await api.get('/v1/jobs');

    expect(calls.filter((path) => path === '/v1/auth/refresh')).toHaveLength(2);
  });

  it('does not refresh anything over a failure that is not a 401', async () => {
    // A 500 is the server having a bad time, not the credential being
    // stale. Refreshing over one spends a refresh token to fix nothing.
    fetchMock.mockImplementation(async (path: string) => {
      calls.push(path);
      return new Response(null, { status: 500 });
    });

    await expect(api.get('/v1/jobs')).rejects.toBeInstanceOf(ApiError);
    await expect(api.get('/v1/jobs')).rejects.toMatchObject({ status: 500 });

    expect(calls).toEqual(['/v1/jobs', '/v1/jobs']);
  });

  it('sends the cookie on every request and an Authorization header on none', async () => {
    // credentials: same-origin is what attaches ps_access. The header is
    // the CLI's way in and must never appear here: nothing in this module
    // can read a token, which is the whole point of HttpOnly.
    serverWithExpiredAccess();

    await api.get('/v1/jobs');

    for (const [, init] of fetchMock.mock.calls as [string, RequestInit][]) {
      expect(init.credentials).toBe('same-origin');
      expect(new Headers(init.headers).has('Authorization')).toBe(false);
    }
  });

  it('reads a 204 as nothing rather than failing to parse it', async () => {
    // Several of this server's write endpoints answer 204 with no body,
    // including both auth paths. response.json() on one of those throws.
    fetchMock.mockImplementation(async (path: string) => {
      calls.push(path);
      return new Response(null, { status: 204 });
    });

    await expect(
      api.post('/v1/proposals/7/resolve', { ruling: 'accept' }),
    ).resolves.toBeUndefined();
  });
});

/**
 * A server that refuses with the body its handlers really send.
 *
 * `ApiExceptionHandler` answers every deliberate refusal with
 * `{"error": "<code>", "detail": "<sentence>"}`, and the sentence is the point:
 * three different refusals share the 400 on `POST /v1/jobs/{id}/limits`, and
 * each tells an operator a different thing to do about it.
 */
function serverRefusing(status: number, code: string, detail: string): void {
  fetchMock.mockImplementation(async (path: string) => {
    calls.push(path);
    return new Response(JSON.stringify({ error: code, detail }), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  });
}

describe('a refusal reaches the person who caused it', () => {
  it('carries the sentence the server wrote, not only the status', async () => {
    serverRefusing(
      400,
      'bad_request',
      'job job_1 has already finished, so there is' +
        ' nothing left for a limit to bound. Nothing was changed.',
    );

    await expect(api.post('/v1/jobs/job_1/limits', {})).rejects.toThrow(
      /already finished/,
    );
  });

  it('keeps the status, so a caller can still branch on it', async () => {
    serverRefusing(409, 'conflict', 'conversation cnv_1 is already archived.');

    await expect(
      api.put('/v1/conversations/cnv_1/lifecycle', {}),
    ).rejects.toMatchObject({ status: 409 });
  });

  it('says nothing about the detail of an internal error', async () => {
    // The hazard this file's `ok` was written for. `internal_error` is the
    // one handler that formats an arbitrary throwable instead of a written
    // sentence, so its detail may hold whatever the server was holding.
    serverRefusing(
      500,
      'internal_error',
      'the Plowshare server failed to answer this' +
        ' request: IllegalStateException: /var/secrets/token.key',
    );

    await expect(api.get('/v1/jobs')).rejects.toThrow(/answered 500/);
    await expect(api.get('/v1/jobs')).rejects.not.toThrow(/token\.key/);
  });

  it('falls back to the status when the body is not the shape this server sends', async () => {
    // A proxy in front of the server, an HTML error page, a truncated
    // response: none of those is this server refusing, and none of them has
    // a sentence to show.
    fetchMock.mockImplementation(async (path: string) => {
      calls.push(path);
      return new Response('<html>502 Bad Gateway</html>', { status: 502 });
    });

    await expect(api.get('/v1/jobs')).rejects.toThrow(/answered 502/);
  });

  /**
   * The JSON body that is not this server's shape, which is the case the
   * first version of the shape check did not actually check.
   *
   * `ApiExceptionHandler` extends `ResponseEntityExceptionHandler`, so Spring
   * answers the standard MVC exceptions itself with a `ProblemDetail` --
   * `{"type","title","status","detail","instance"}`, which has a `detail` and
   * no `error` at all. `application.yml` caps uploads at `max-file-size: 8MB`,
   * so this is what a 9MB file really comes back as, and a check that only
   * asked whether `error` was `internal_error` passed it straight through.
   */
  it('falls back to the status for a Spring ProblemDetail, which has no error code', async () => {
    fetchMock.mockImplementation(async (path: string) => {
      calls.push(path);
      return new Response(
        JSON.stringify({
          type: 'about:blank',
          title: 'Payload Too Large',
          status: 413,
          detail: 'Maximum upload size exceeded',
          instance: '/v1/documents',
        }),
        {
          status: 413,
          headers: { 'Content-Type': 'application/problem+json' },
        },
      );
    });

    await expect(api.get('/v1/documents')).rejects.toThrow(/answered 413/);
    await expect(api.get('/v1/documents')).rejects.not.toThrow(/upload size/);
  });

  /**
   * The discriminator the REPL branches on, asserted here rather than left to
   * be inferred from a message: `said` is the server's sentence or it is
   * null, and nothing else. A caller cannot tell those apart from `message`,
   * and one that tried to guess by status is what this pair exists to stop.
   */
  it('marks which errors carry the server’s own words and which do not', async () => {
    serverRefusing(409, 'conflict', 'conversation cnv_1 is already archived.');
    await expect(api.get('/v1/conversations')).rejects.toMatchObject({
      said: 'conversation cnv_1 is already archived.',
    });

    serverRefusing(
      500,
      'internal_error',
      'the Plowshare server failed to answer this' +
        ' request: IllegalStateException: /var/secrets/token.key',
    );
    await expect(api.get('/v1/jobs')).rejects.toMatchObject({ said: null });
  });
});

describe('refresh coordination across browser tabs', () => {
  afterEach(() => {
    Reflect.deleteProperty(navigator, 'locks');
  });

  it('serializes independent tab refresh owners and rechecks the shared access cookie', async () => {
    let tail = Promise.resolve();
    const lock = vi.fn((_name: string, callback: () => Promise<boolean>) => {
      const result = tail.then(callback);
      tail = result.then(() => undefined);
      return result;
    });
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: { request: lock },
    });
    let signedIn = false;
    fetchMock.mockImplementation(async (path: string) => {
      calls.push(path);
      if (path === '/v1/auth/refresh') {
        signedIn = true;
        return new Response(null, { status: 204 });
      }
      if (path === '/v1/auth/session')
        return new Response(null, { status: signedIn ? 204 : 401 });
      return signedIn
        ? new Response('[]', { status: 200 })
        : new Response(null, { status: 401 });
    });
    const first = await import('./api');
    vi.resetModules();
    const second = await import('./api');
    await Promise.all([
      first.api.get('/v1/jobs'),
      second.api.get('/v1/projects'),
    ]);
    expect(lock).toHaveBeenCalledTimes(2);
    expect(calls.filter((path) => path === '/v1/auth/refresh')).toHaveLength(1);
  });
});

describe('session recovery availability', () => {
  it('requires current, direct authority for every probe and refuses redirect replay of refresh', async () => {
    vi.resetModules();
    const isolated = await import('./api');
    fetchMock.mockImplementation(async (path: string, init: RequestInit) => {
      calls.push(path);
      expect(init.cache).toBe('no-store');
      expect(init.redirect).toBe('error');
      expect(init.credentials).toBe('same-origin');
      if (path === '/v1/auth/refresh') {
        // Fetch rejects a redirect rather than resending this POST. Delivery
        // to the first endpoint is still uncertain, so another owner may only probe.
        throw new TypeError('redirect refused');
      }
      return new Response(null, { status: 401 });
    });
    await expect(isolated.recoverSession()).resolves.toBe('unavailable');
    vi.resetModules();
    const reloaded = await import('./api');
    await expect(reloaded.recoverSession()).resolves.toBe('unavailable');
    expect(calls.filter((path) => path === '/v1/auth/refresh')).toHaveLength(1);
    expect(storage.getItem('plowshare-session-refresh-uncertain')).toBe(
      'pending',
    );
  });

  it('reconciles an uncertain refresh only with an uncached, direct session probe', async () => {
    vi.resetModules();
    const isolated = await import('./api');
    storage.setItem('plowshare-session-refresh-uncertain', 'pending');
    fetchMock.mockImplementation(async (path: string, init: RequestInit) => {
      calls.push(path);
      expect(init.cache).toBe('no-store');
      expect(init.redirect).toBe('error');
      return new Response(null, { status: 204 });
    });
    await isolated.reconcileSessionRefresh();
    expect(calls).toEqual(['/v1/auth/session']);
    expect(storage.getItem('plowshare-session-refresh-uncertain')).toBeNull();
  });

  it.each(['lost', 'unhealthy'])(
    'keeps an uncertain %s refresh distinct from sign-out and does not replay it',
    async (failure) => {
      vi.resetModules();
      const isolated = await import('./api');
      fetchMock.mockImplementation(async (path: string) => {
        calls.push(path);
        if (path === '/v1/auth/refresh') {
          if (failure === 'lost') throw new Error('connection lost');
          return new Response(null, { status: 503 });
        }
        return new Response(null, { status: 401 });
      });
      await expect(isolated.recoverSession()).resolves.toBe('unavailable');
      await expect(isolated.recoverSession()).resolves.toBe('unavailable');
      expect(calls.filter((path) => path === '/v1/auth/refresh')).toHaveLength(
        1,
      );
    },
  );

  it('does not rotate when the locked session probe is unhealthy', async () => {
    vi.resetModules();
    const isolated = await import('./api');
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: {
        request: (_name: string, callback: () => Promise<boolean>) =>
          callback(),
      },
    });
    fetchMock
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(new Response(null, { status: 503 }));
    try {
      await expect(isolated.recoverSession()).resolves.toBe('unavailable');
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(fetchMock).not.toHaveBeenCalledWith(
        '/v1/auth/refresh',
        expect.anything(),
      );
    } finally {
      Reflect.deleteProperty(navigator, 'locks');
    }
  });
});

it('fails closed without cross-tab Web Locks instead of rotating a shared cookie', async () => {
  Reflect.deleteProperty(navigator, 'locks');
  vi.resetModules();
  const first = await import('./api');
  vi.resetModules();
  const second = await import('./api');
  fetchMock.mockResolvedValue(new Response(null, { status: 401 }));
  await Promise.all([
    expect(first.recoverSession()).resolves.toBe('unavailable'),
    expect(second.recoverSession()).resolves.toBe('unavailable'),
  ]);
  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(
    fetchMock.mock.calls.every(([path]) => path === '/v1/auth/session'),
  ).toBe(true);
});

it('shares uncertain rotation across tab owners and reloads until a successful access probe reconciles it', async () => {
  vi.resetModules();
  const first = await import('./api');
  vi.resetModules();
  const second = await import('./api');
  let signedIn = false;
  let allowRotation = false;
  let fixtureReads = 0;
  fetchMock.mockImplementation(async (path: string) => {
    calls.push(path);
    if (path === '/fixture')
      return new Response(null, { status: fixtureReads++ === 0 ? 401 : 204 });
    if (path === '/v1/auth/refresh') {
      if (!allowRotation) throw new Error('lost rotation reply');
      signedIn = true;
    }
    return new Response(null, { status: signedIn ? 204 : 401 });
  });
  await expect(first.recoverSession()).resolves.toBe('unavailable');
  await expect(second.recoverSession()).resolves.toBe('unavailable');
  vi.resetModules();
  const reloaded = await import('./api');
  await expect(reloaded.recoverSession()).resolves.toBe('unavailable');
  expect(calls.filter((path) => path === '/v1/auth/refresh')).toHaveLength(1);
  signedIn = true;
  await expect(reloaded.recoverSession()).resolves.toBe('ready');
  // The successful access probe clears the shared uncertainty marker; a
  // genuinely later expiry may rotate its current cookie pair.
  await expect(reloaded.api.request('/fixture')).resolves.toMatchObject({
    status: 204,
  });
  signedIn = false;
  allowRotation = true;
  await expect(reloaded.recoverSession()).resolves.toBe('ready');
  expect(calls.filter((path) => path === '/v1/auth/refresh')).toHaveLength(2);
});

it('does not spend a rotating cookie when uncertainty cannot be recorded', async () => {
  vi.resetModules();
  const isolated = await import('./api');
  fetchMock.mockResolvedValue(new Response(null, { status: 401 }));
  const store = vi.spyOn(storage, 'setItem').mockImplementation(() => {
    throw new Error('storage unavailable');
  });
  try {
    await expect(isolated.api.get('/fixture')).rejects.toThrow(
      'storage unavailable',
    );
    expect(
      fetchMock.mock.calls.every(([path]) => path !== '/v1/auth/refresh'),
    ).toBe(true);
  } finally {
    store.mockRestore();
  }
});
