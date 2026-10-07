import { afterEach, describe, expect, it, vi } from 'vitest';
import type {
  EventStream,
  EventStreamOptions,
  FrameOutcome,
  StreamStatus,
} from '../events';
import { createInbox, type InboxOptions } from './inbox';
import type { Screen } from './screen';

const owned: Screen[] = [];
function inbox(options: InboxOptions): Screen {
  const screen = createInbox(options);
  owned.push(screen);
  return screen;
}
afterEach(() => {
  for (const screen of owned.splice(0)) screen.destroy();
  vi.restoreAllMocks();
});

/**
 * A reply per frame type, either an outcome to resolve with or an `Error` to
 * reject with -- the latter is what a `ask()` call sees when the socket drops
 * mid-flight, which is exactly the case the mark-read button has to survive.
 */
function fakeStream(replies: Record<string, FrameOutcome | Error>) {
  let onEvent: (event: unknown) => void = () => {};
  let onStatus: EventStreamOptions['onStatus'];
  let state: StreamStatus = { state: 'open', attempt: 0, retryInMs: null };
  const ask = vi.fn((type: string) => {
    const reply = replies[type];
    return reply instanceof Error
      ? Promise.reject(reply)
      : Promise.resolve(reply ?? { code: 'NOT_FOUND' });
  });
  const open = (options: EventStreamOptions): EventStream => {
    onEvent = options.onEvent;
    onStatus = options.onStatus;
    return {
      status: () => state,
      close: () => {},
      ask,
    };
  };
  return {
    open,
    ask,
    push: (event: unknown) => onEvent(event),
    status(next: StreamStatus) {
      state = next;
      onStatus?.(next);
    },
  };
}

const item = {
  id: 'inb_1',
  handle: 'enzo',
  kind: 'firing',
  firing: 'fir_1',
  conversation: 'cnv_1',
  ending: 'ANSWERED',
  answer: 'three PRs need review',
  arrivedAt: '2026-09-13T09:02:00Z',
  readAt: null,
};

describe('the INBOX screen', () => {
  it('fences an old page after leaving and returning, even when its offset matches', async () => {
    const stream = fakeStream({
      'inbox.list': {
        code: 'OK',
        payload: { items: [{ ...item, answer: 'current result' }], unread: 1 },
      },
    });
    const root = document.createElement('div');
    const screen = inbox({
      root,
      openStream: stream.open,
      pollMs: null,
      session: 's',
    });
    let resolve: (reply: FrameOutcome) => void = () => {};
    stream.ask.mockReturnValueOnce(
      new Promise<FrameOutcome>((accept) => {
        resolve = accept;
      }),
    );
    const flight = screen.load();
    screen.setActive?.(false);
    screen.setActive?.(true);
    resolve({
      code: 'OK',
      payload: { items: [{ ...item, answer: 'obsolete result' }], unread: 1 },
    });
    await flight;
    expect(root.textContent).toContain('current result');
    expect(root.textContent).not.toContain('obsolete result');
    expect(stream.ask.mock.calls.every(([type]) => type === 'inbox.list')).toBe(
      true,
    );
  });

  it('disables cached receipts across disconnect and a refused membership read', async () => {
    const replies: Record<string, FrameOutcome | Error> = {
      'inbox.list': { code: 'OK', payload: { items: [item], unread: 1 } },
    };
    const stream = fakeStream(replies);
    const root = document.createElement('div');
    const screen = inbox({
      root,
      openStream: stream.open,
      pollMs: null,
      session: 's',
    });
    await screen.load();
    const control = root.querySelector<HTMLButtonElement>('[data-item] button');
    if (control === null) throw new Error('Expected read receipt control');
    stream.status({ state: 'reconnecting', attempt: 1, retryInMs: 500 });
    expect(control.disabled).toBe(true);
    control.dispatchEvent(new Event('click'));
    replies['inbox.list'] = { code: 'BAD_REQUEST', said: 'Membership revoked' };
    stream.status({ state: 'open', attempt: 0, retryInMs: null });
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Membership revoked'),
    );
    expect(control.disabled).toBe(true);
    control.dispatchEvent(new Event('click'));
    expect(stream.ask.mock.calls.every(([type]) => type === 'inbox.list')).toBe(
      true,
    );
  });

  it('pauses hidden-tab reads and receipts and obtains a new snapshot on return', async () => {
    const stream = fakeStream({
      'inbox.list': { code: 'OK', payload: { items: [item], unread: 1 } },
    });
    const root = document.createElement('div');
    const screen = inbox({
      root,
      openStream: stream.open,
      pollMs: null,
      session: 's',
    });
    await screen.load();
    const visibility = vi.spyOn(document, 'visibilityState', 'get');
    visibility.mockReturnValue('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    stream.ask.mockClear();
    stream.push({ kind: 'inbox.changed', unread: 1 });
    await screen.load();
    expect(stream.ask).not.toHaveBeenCalled();
    expect(
      root.querySelector<HTMLButtonElement>('[data-item] button')?.disabled,
    ).toBe(true);
    visibility.mockReturnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.waitFor(() =>
      expect(
        root.querySelector<HTMLButtonElement>('[data-item] button')?.disabled,
      ).toBe(false),
    );
    expect(stream.ask).toHaveBeenCalledTimes(1);
  });

  it("lists the account's items, unread first marked as such", async () => {
    const stream = fakeStream({
      'inbox.list': { code: 'OK', payload: { items: [item], unread: 1 } },
    });
    const root = document.createElement('div');
    const screen = inbox({
      root,
      openStream: stream.open,
      pollMs: null,
      session: 's',
    });
    await screen.load();
    expect(root.textContent).toContain('three PRs need review');
    expect(root.querySelector('[data-unread="true"]')).not.toBeNull();
  });

  it('marks an item read when it is opened', async () => {
    const stream = fakeStream({
      'inbox.list': { code: 'OK', payload: { items: [item], unread: 1 } },
      'inbox.read': { code: 'OK', payload: { marked: 1, unread: 0 } },
    });
    const root = document.createElement('div');
    const screen = inbox({
      root,
      openStream: stream.open,
      pollMs: null,
      session: 's',
    });
    await screen.load();
    (
      root.querySelector('[data-item="inb_1"] button') as HTMLButtonElement
    ).click();
    expect(stream.ask).toHaveBeenCalledWith('inbox.read', { items: ['inb_1'] });
  });

  it('reloads when the server says the count changed', async () => {
    const stream = fakeStream({
      'inbox.list': { code: 'OK', payload: { items: [], unread: 0 } },
    });
    const screen = inbox({
      root: document.createElement('div'),
      openStream: stream.open,
      pollMs: null,
      session: 's',
    });
    await screen.load();
    stream.push({ kind: 'inbox.changed', unread: 1 });
    expect(stream.ask).toHaveBeenCalledTimes(2);
  });

  it('says so when the socket belongs to no account', async () => {
    const stream = fakeStream({
      'inbox.list': {
        code: 'BAD_REQUEST',
        said: 'inbox.list needs a socket signed in as an account',
      },
    });
    const root = document.createElement('div');
    await inbox({
      root,
      openStream: stream.open,
      pollMs: null,
      session: 's',
    }).load();
    expect(root.textContent).toContain('signed in as an account');
  });
});

describe('marking an item read can fail', () => {
  it(
    'keeps uncertain read receipts blocked and shows trouble with no' +
      ' unhandled rejection',
    async () => {
      const stream = fakeStream({
        'inbox.list': { code: 'OK', payload: { items: [item], unread: 1 } },
        'inbox.read': new Error(
          'the event socket is not open; "inbox.read" was not sent',
        ),
      });
      const root = document.createElement('div');
      const screen = inbox({
        root,
        openStream: stream.open,
        pollMs: null,
        session: 's',
      });
      await screen.load();

      const button = root.querySelector(
        '[data-item="inb_1"] button',
      ) as HTMLButtonElement;
      button.click();
      expect(
        root.querySelector<HTMLButtonElement>('[data-item="inb_1"] button')
          ?.disabled,
      ).toBe(true);

      await vi.waitFor(() =>
        expect(
          root.querySelector('[data-item="inb_1"] [data-trouble]')?.textContent,
        ).toContain('not open'),
      );
      expect(
        root.querySelector('[data-item="inb_1"] [data-trouble]')?.textContent,
      ).toContain('not open');
    },
  );

  it('re-enables the button and shows trouble when the server refuses the mark', async () => {
    const stream = fakeStream({
      'inbox.list': { code: 'OK', payload: { items: [item], unread: 1 } },
      'inbox.read': {
        code: 'BAD_REQUEST',
        said: 'inbox.read needs a socket signed in as an account',
      },
    });
    const root = document.createElement('div');
    const screen = inbox({
      root,
      openStream: stream.open,
      pollMs: null,
      session: 's',
    });
    await screen.load();

    const button = root.querySelector(
      '[data-item="inb_1"] button',
    ) as HTMLButtonElement;
    button.click();

    await vi.waitFor(() =>
      expect(
        root.querySelector('[data-item="inb_1"] [data-trouble]')?.textContent,
      ).toContain('signed in as an account'),
    );
    expect(
      root.querySelector('[data-item="inb_1"] [data-trouble]')?.textContent,
    ).toContain('signed in as an account');
  });
});

describe('the first load, before the socket has finished connecting', () => {
  it('does not ask while still connecting, and asks once the socket opens', async () => {
    let onStatus: ((status: StreamStatus) => void) | undefined;
    let state: StreamStatus['state'] = 'connecting';
    const ask = vi.fn(() =>
      Promise.resolve({ code: 'OK', payload: { items: [], unread: 0 } }),
    );
    const open = (options: EventStreamOptions): EventStream => {
      onStatus = options.onStatus;
      return {
        status: (): StreamStatus => ({
          state,
          attempt: 0,
          retryInMs: null,
        }),
        close: () => {},
        ask,
      };
    };
    const root = document.createElement('div');
    const screen = inbox({
      root,
      openStream: open,
      pollMs: null,
      session: 's',
    });

    await screen.load();

    expect(ask).not.toHaveBeenCalled();
    expect(root.querySelector('[data-trouble]')).toBeNull();

    state = 'open';
    onStatus?.({ state: 'open', attempt: 0, retryInMs: null });
    await vi.waitFor(() => expect(ask).toHaveBeenCalledTimes(1));
  });
});
