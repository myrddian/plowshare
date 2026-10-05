import { displayText } from '../../sdk/typescript/src/binding/values.ts';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { bootstrapFromUrl } from './auth';

/**
 * `history.replaceState` and not an assignment to `location.search`, which is
 * how the plan wrote this: assigning to any part of `location` is a NAVIGATION,
 * and jsdom answers one with "Not implemented: navigation" and leaves the URL
 * where it was -- so the test would be arranging nothing and then asserting it.
 * `replaceState` is also the call the code under test makes, so the fixture and
 * the subject move the URL the same way.
 */
function addressBar(url: string): void {
  history.replaceState(null, '', url);
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn(async () => new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', fetchMock);
  addressBar('/');
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('bootstrapFromUrl', () => {
  it('spends the bootstrap token and then strips it from the address bar', async () => {
    // A token that stays in the URL is a token in history, in the referrer,
    // and on the screen. It is spent the moment it is used and must vanish
    // with it.
    addressBar('/?token=abc');

    await expect(bootstrapFromUrl()).resolves.toBe('exchanged');

    expect(fetchMock).toHaveBeenCalledWith(
      '/v1/auth',
      expect.objectContaining({
        method: 'POST',
        credentials: 'same-origin',
      }),
    );
    expect(location.search).toBe('');
  });

  it('sends the token as JSON, because the endpoint refuses a body without a type', async () => {
    // AuthController's @PostMapping names consumes = application/json. A
    // body arriving without that Content-Type is a 415, which reads as a
    // server fault and is a client one.
    addressBar('/?token=abc');

    await bootstrapFromUrl();

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(new Headers(init.headers).get('Content-Type')).toBe(
      'application/json',
    );
    expect(JSON.parse(displayText(init.body))).toEqual({ token: 'abc' });
  });

  it('strips the token even when the exchange is refused', async () => {
    // The token is single-use, so a refusal usually means it was already
    // spent -- and a spent secret left in the address bar is still a secret
    // in the history and in the next referrer. There is nothing to keep it
    // for.
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));
    addressBar('/?token=abc');

    await expect(bootstrapFromUrl()).resolves.toBe('refused');

    expect(location.search).toBe('');
  });

  it('strips the token when the exchange never reaches the server', async () => {
    // A transport failure leaves the token unspent, and this still removes
    // it. The remedy is the line the server printed, which is still in the
    // terminal; a live token on the screen has no such second copy.
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    addressBar('/?token=abc');

    await expect(bootstrapFromUrl()).resolves.toBe('refused');

    expect(location.search).toBe('');
  });

  it('keeps every other query parameter', async () => {
    // The token is removed by name and the query string is not simply
    // discarded, so that a later screen routing on a parameter is not
    // broken by the first load of the session.
    addressBar('/?screen=jobs&token=abc&job=17#seam');

    await bootstrapFromUrl();

    expect(location.search).toBe('?screen=jobs&job=17');
    expect(location.hash).toBe('#seam');
  });

  it('asks the server nothing when there is no token to spend', async () => {
    // The ordinary state of every navigation after the first: the cookie is
    // in the jar and there is no token in the URL. A POST here would spend
    // nothing and 401, which would look like a failure and is not one.
    addressBar('/');

    await expect(bootstrapFromUrl()).resolves.toBe('absent');

    expect(fetchMock).not.toHaveBeenCalled();
  });
});
