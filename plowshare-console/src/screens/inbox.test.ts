import { describe, expect, it, vi } from 'vitest';
import type {
  EventStream,
  EventStreamOptions,
  FrameOutcome,
  StreamStatus,
} from '../events';
import { createInbox } from './inbox';

/**
 * A reply per frame type, either an outcome to resolve with or an `Error` to
 * reject with -- the latter is what a `ask()` call sees when the socket drops
 * mid-flight, which is exactly the case the mark-read button has to survive.
 */
function fakeStream(replies: Record<string, FrameOutcome | Error>) {
  let onEvent: (event: unknown) => void = () => {};
  const ask = vi.fn((type: string) => {
    const reply = replies[type];
    return reply instanceof Error
      ? Promise.reject(reply)
      : Promise.resolve(reply ?? { code: 'NOT_FOUND' });
  });
  const open = (options: EventStreamOptions): EventStream => {
    onEvent = options.onEvent;
    return {
      status: () => ({ state: 'open', attempt: 0, retryInMs: null }),
      close: () => {},
      ask,
    };
  };
  return { open, ask, push: (event: unknown) => onEvent(event) };
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
  it("lists the account's items, unread first marked as such", async () => {
    const stream = fakeStream({
      'inbox.list': { code: 'OK', payload: { items: [item], unread: 1 } },
    });
    const root = document.createElement('div');
    const screen = createInbox({
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
    const screen = createInbox({
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
    const screen = createInbox({
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
    await createInbox({
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
      const screen = createInbox({
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
    const screen = createInbox({
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
    const screen = createInbox({
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
