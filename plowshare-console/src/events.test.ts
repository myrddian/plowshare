import { parseObject } from './json.test-support.ts';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { eventUrl, openEventStream, type StreamStatus } from './events';

/**
 * A socket that records what was done to it and fires nothing on its own.
 *
 * jsdom ships a real `WebSocket` that would try to connect, so the tests below
 * hand `openEventStream` this instead through its `open` option. What is being
 * tested is the reconnect policy and the frame handling, and both of those are
 * this module's; the transport underneath is the browser's.
 */
class FakeSocket {
  static opened: FakeSocket[] = [];

  onopen: ((event: unknown) => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: unknown) => void) | null = null;
  onclose: ((event: unknown) => void) | null = null;
  closed = false;
  /** Every frame this socket was asked to send, in order. */
  sent: string[] = [];

  constructor(readonly url: string) {
    FakeSocket.opened.push(this);
  }

  close(): void {
    this.closed = true;
  }

  /** What `ask` calls to put a frame on the wire. */
  send(frame: string): void {
    this.sent.push(frame);
  }

  /** The server accepted the upgrade. */
  open(): void {
    this.onopen?.({});
  }

  /** A frame arrived. */
  deliver(data: string): void {
    this.onmessage?.({ data } as MessageEvent);
  }

  /** The upgrade was refused, or an open socket dropped. */
  drop(): void {
    this.onerror?.({});
    this.onclose?.({});
  }
}

const open = vi.fn(
  (url: string) => new FakeSocket(url) as unknown as WebSocket,
);

/** The most recently constructed fake. */
function latest(): FakeSocket {
  const socket = FakeSocket.opened.at(-1);
  if (socket === undefined) {
    throw new Error('no socket was opened');
  }
  return socket;
}

beforeEach(() => {
  FakeSocket.opened = [];
  open.mockClear();
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('eventUrl', () => {
  it('goes to the origin the page came from, carrying the session id', () => {
    // jsdom serves this test from http://localhost:3000 by default, so the
    // host is read rather than written down here.
    expect(eventUrl('a session')).toBe(
      `ws://${location.host}/v1/events?session=a+session`,
    );
  });

  it('uses wss when the page is https, which a browser requires anyway', () => {
    const secure = {
      location: { protocol: 'https:', host: 'example:8443' },
    } as Window;

    expect(eventUrl('s', secure)).toBe(
      'wss://example:8443/v1/events?session=s',
    );
  });
});

describe('openEventStream', () => {
  it('opens the socket with a URL and nothing else, because it cannot send a header', () => {
    // `new WebSocket(url)` takes a URL and an optional subprotocol list and
    // nothing else. This is the constraint the whole cookie design exists
    // for, and this assertion is what stops someone "fixing" the auth here
    // by inventing a header this call has nowhere to put.
    const stream = openEventStream({ session: 's', onEvent: vi.fn(), open });

    expect(open).toHaveBeenCalledTimes(1);
    expect(open.mock.calls[0]).toHaveLength(1);
    expect(stream.status().state).toBe('connecting');

    stream.close();
  });

  it('hands over decoded frames and stays up for one it cannot decode', () => {
    // A dropped or malformed event is one event lost, not a broken socket.
    // The stream behind it is still carrying the rest, and taking the
    // connection down would turn one bad frame into a gap.
    const onEvent = vi.fn<(event: unknown) => void>();
    const onMalformed = vi.fn();
    const stream = openEventStream({
      session: 's',
      onEvent,
      onMalformed,
      open,
    });
    latest().open();

    latest().deliver('{"kind":"started","job":"7"}');
    latest().deliver('not json');
    latest().deliver('{"kind":"ended","job":"7"}');

    expect(onEvent.mock.calls.map(([event]) => event)).toEqual([
      { kind: 'started', job: '7' },
      { kind: 'ended', job: '7' },
    ]);
    expect(onMalformed).toHaveBeenCalledTimes(1);
    expect(stream.status().state).toBe('open');

    stream.close();
  });

  it('reports a reconnecting state with the delay, rather than going quiet', () => {
    // The spec's rule for this screen is that a gap must be visible. A
    // status a screen can render -- "reconnecting, next try in 1s" -- is the
    // difference between a stream that is down and a system that looks idle.
    const seen: StreamStatus[] = [];
    const stream = openEventStream({
      session: 's',
      onEvent: vi.fn(),
      open,
      onStatus: (status) => seen.push(status),
      baseDelayMs: 1000,
      maxDelayMs: 4000,
    });

    latest().open();
    latest().drop();

    expect(seen.map((status) => status.state)).toEqual([
      'open',
      'reconnecting',
    ]);
    expect(seen.at(-1)?.retryInMs).toBe(1000);

    stream.close();
  });

  it('doubles the delay to a ceiling and stays there', () => {
    const stream = openEventStream({
      session: 's',
      onEvent: vi.fn(),
      open,
      baseDelayMs: 1000,
      maxDelayMs: 4000,
    });

    const delays: (number | null)[] = [];
    for (let attempt = 0; attempt < 5; attempt += 1) {
      latest().drop();
      delays.push(stream.status().retryInMs);
      vi.advanceTimersByTime(10_000);
    }

    expect(delays).toEqual([1000, 2000, 4000, 4000, 4000]);
  });

  it('counts one attempt for the error and close a refused upgrade fires together', () => {
    // A browser answers a refused upgrade with `error` and then `close`.
    // Treating both as failures would halve the backoff and double the
    // traffic against a server that is already refusing.
    const stream = openEventStream({
      session: 's',
      onEvent: vi.fn(),
      open,
      baseDelayMs: 1000,
      maxDelayMs: 60_000,
    });

    latest().drop();

    expect(stream.status().attempt).toBe(1);
    expect(stream.status().retryInMs).toBe(1000);

    stream.close();
  });

  it('resets the backoff once a connection succeeds', () => {
    const stream = openEventStream({
      session: 's',
      onEvent: vi.fn(),
      open,
      baseDelayMs: 1000,
      maxDelayMs: 60_000,
    });

    latest().drop();
    latest().drop();
    vi.advanceTimersByTime(10_000);
    vi.advanceTimersByTime(10_000);
    latest().open();

    expect(stream.status()).toEqual({
      state: 'open',
      attempt: 0,
      retryInMs: null,
    });

    stream.close();
  });

  it('stays closed when it is closed, and schedules nothing more', () => {
    // The close this call causes must not come back through the failure
    // path and reconnect a stream somebody deliberately stopped.
    const stream = openEventStream({ session: 's', onEvent: vi.fn(), open });
    latest().open();
    const socket = latest();

    stream.close();
    socket.drop();
    vi.advanceTimersByTime(120_000);

    expect(socket.closed).toBe(true);
    expect(open).toHaveBeenCalledTimes(1);
    expect(stream.status().state).toBe('closed');
  });

  it('does not reconnect after a close that races a pending retry', () => {
    const stream = openEventStream({
      session: 's',
      onEvent: vi.fn(),
      open,
      baseDelayMs: 1000,
      maxDelayMs: 60_000,
    });

    latest().drop();
    stream.close();
    vi.advanceTimersByTime(120_000);

    expect(open).toHaveBeenCalledTimes(1);
    expect(stream.status().state).toBe('closed');
  });
});

describe('ask', () => {
  it('sends an envelope and resolves with the reply that carries its id', async () => {
    const stream = openEventStream({ session: 's', onEvent: vi.fn(), open });
    latest().open();

    const asked = stream.ask('inbox.list', { unread: true });

    const sent = parseObject(latest().sent[0] as string);
    expect(sent).toMatchObject({
      type: 'inbox.list',
      protocol_version: 'plowshare-v1',
      payload: { unread: true },
    });
    latest().deliver(
      JSON.stringify({
        id: sent.id,
        type: 'inbox.list',
        protocol_version: 'plowshare-v1',
        payload: { code: 'OK', payload: { items: [], unread: 0 } },
      }),
    );

    await expect(asked).resolves.toEqual({
      code: 'OK',
      payload: { items: [], unread: 0 },
    });
    stream.close();
  });

  it('hands a frame with no protocol_version to onEvent, not to a waiting ask', async () => {
    const events: unknown[] = [];
    const stream = openEventStream({
      session: 's',
      onEvent: (e) => events.push(e),
      open,
    });
    latest().open();

    // Left outstanding on purpose: this test is about what a bare push does
    // to a waiting `ask`, not about how the stream ends. Caught so a
    // `close()` elsewhere in the suite cannot turn this into an unhandled
    // rejection.
    stream.ask('inbox.list').catch(() => undefined);
    latest().deliver(JSON.stringify({ kind: 'inbox.changed', unread: 2 }));

    expect(events).toEqual([{ kind: 'inbox.changed', unread: 2 }]);
    stream.close();
  });

  it('refuses to ask on a socket that is not open', async () => {
    const stream = openEventStream({ session: 's', onEvent: vi.fn(), open });

    await expect(stream.ask('inbox.list')).rejects.toThrow(/not open/);

    stream.close();
  });

  it('rejects what is still waiting when the stream closes', async () => {
    const stream = openEventStream({ session: 's', onEvent: vi.fn(), open });
    latest().open();

    const asked = stream.ask('inbox.list');
    stream.close();

    await expect(asked).rejects.toThrow(/closed/);
  });

  it('ignores an enveloped reply whose id nothing is waiting on', async () => {
    // Stale (an answer to a request this connection already gave up on) or
    // simply foreign -- either way, there is nobody to hand it to and it is
    // not a push, so it is neither delivered to `onEvent` nor allowed to
    // resolve a wait it was never the answer to.
    const onEvent = vi.fn<(event: unknown) => void>();
    const stream = openEventStream({ session: 's', onEvent, open });
    latest().open();

    const asked = stream.ask('inbox.list');
    const sent = parseObject(latest().sent[0] as string);

    latest().deliver(
      JSON.stringify({
        id: 'an-id-nobody-issued',
        type: 'inbox.list',
        protocol_version: 'plowshare-v1',
        payload: { code: 'OK', payload: { items: [], unread: 9 } },
      }),
    );

    expect(onEvent).not.toHaveBeenCalled();

    // The real reply still resolves the ask that is actually waiting.
    latest().deliver(
      JSON.stringify({
        id: sent.id,
        type: 'inbox.list',
        protocol_version: 'plowshare-v1',
        payload: { code: 'OK', payload: { items: [], unread: 1 } },
      }),
    );
    await expect(asked).resolves.toEqual({
      code: 'OK',
      payload: { items: [], unread: 1 },
    });

    stream.close();
  });

  it('rejects a correlated malformed outcome instead of stranding its request', async () => {
    const stream = openEventStream({ session: 's', onEvent: vi.fn(), open });
    latest().open();
    const asked = stream.ask('inbox.list', {});
    const sent = parseObject(latest().sent[0] as string);
    latest().deliver(
      JSON.stringify({
        id: sent.id,
        type: 'inbox.list',
        protocol_version: 'plowshare-v1',
        payload: { code: 'made-up' },
      }),
    );
    await expect(asked).rejects.toThrow(/delivery is uncertain/);
    expect(latest().sent).toHaveLength(1);
    stream.close();
  });
  it('gives up on a reply that never comes, and ignores it if it arrives late', async () => {
    const onEvent = vi.fn<(event: unknown) => void>();
    const stream = openEventStream({
      session: 's',
      onEvent,
      open,
      askTimeoutMs: 1_000,
    });
    latest().open();

    const asked = stream.ask('approval.answer', {
      id: 'apr_1',
      decision: 'once',
    });
    const sent = parseObject(latest().sent[0] as string);
    vi.advanceTimersByTime(1_000);

    await expect(asked).rejects.toThrow(/not answered within 1000ms/);
    latest().deliver(
      JSON.stringify({
        id: sent.id,
        type: 'approval.answer',
        protocol_version: 'plowshare-v1',
        payload: { code: 'OK', payload: {} },
      }),
    );
    expect(onEvent).not.toHaveBeenCalled();
    stream.close();
  });

  it('rejects an ask whose frame the socket would not send', async () => {
    const stream = openEventStream({ session: 's', onEvent: vi.fn(), open });
    latest().open();
    latest().send = (): void => {
      throw new Error('the socket is closing');
    };

    await expect(
      stream.ask('approval.list', { conversation: 'c' }),
    ).rejects.toThrow(/closing/);
    stream.close();
  });
});

it('forwards uncorrelated accounting envelopes while retaining request correlation', async () => {
  const onEvent = vi.fn<(event: unknown) => void>(),
    stream = openEventStream({ session: 's', onEvent, open });
  latest().open();
  const promise = stream.ask('usage.models', {}),
    request = parseObject(latest().sent.at(-1)!);
  const push = {
    id: null,
    type: 'usage.updated',
    protocol_version: 'plowshare-v1',
    payload: { subscription: 's', revision: 1 },
  };
  latest().deliver(JSON.stringify(push));
  expect(onEvent).toHaveBeenCalledWith(push);
  latest().deliver(
    JSON.stringify({
      id: request.id,
      type: 'usage.models',
      protocol_version: 'plowshare-v1',
      payload: { code: 'OK', payload: { value: 'snapshot' } },
    }),
  );
  expect(await promise).toMatchObject({
    code: 'OK',
    payload: { value: 'snapshot' },
  });
  stream.close();
});

describe('browser session recovery before reconnect', () => {
  it('stops reconnecting when HTTP recovery establishes a signed-out session', async () => {
    const recoverSession = vi.fn(async () => 'signed-out' as const);
    const stream = openEventStream({
      session: 's',
      onEvent: vi.fn(),
      open,
      recoverSession,
    });
    latest().open();
    latest().drop();
    await vi.advanceTimersByTimeAsync(60000);
    expect(recoverSession).toHaveBeenCalledOnce();
    expect(stream.status()).toMatchObject({ state: 'closed', signedOut: true });
    expect(open).toHaveBeenCalledOnce();
    stream.close();
  });

  it('reconnects read-only after refresh but never replays a lost frame', async () => {
    const stream = openEventStream({
      session: 's',
      onEvent: vi.fn(),
      open,
      recoverSession: async () => 'ready',
      baseDelayMs: 100,
    });
    latest().open();
    const pending = stream.ask('approval.answer', {
      id: 'apr_1',
      decision: 'once',
    });
    const rejected = expect(pending).rejects.toThrow('socket closed');
    latest().drop();
    await rejected;
    await vi.advanceTimersByTimeAsync(100);
    latest().open();
    expect(FakeSocket.opened).toHaveLength(2);
    expect(latest().sent).toHaveLength(0);
    stream.close();
  });

  it('does not reconnect if teardown happens during session recovery', async () => {
    let finish: ((state: 'ready') => void) | undefined;
    const stream = openEventStream({
      session: 's',
      onEvent: vi.fn(),
      open,
      recoverSession: () =>
        new Promise<'ready'>((resolve) => {
          finish = resolve;
        }),
    });
    latest().drop();
    stream.close();
    finish?.('ready');
    await vi.advanceTimersByTimeAsync(60000);
    expect(open).toHaveBeenCalledOnce();
  });
});
