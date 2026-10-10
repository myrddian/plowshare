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
  document.body.replaceChildren();
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

function control(root: HTMLElement, selector: string): HTMLButtonElement {
  const found = root.querySelector<HTMLButtonElement>(selector);
  if (found === null) throw new Error(`Expected button ${selector}`);
  return found;
}

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
    const control = root.querySelector<HTMLButtonElement>(
      '.inbox-reader .mark-read',
    );
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
      root.querySelector<HTMLButtonElement>('.inbox-reader .mark-read')
        ?.disabled,
    ).toBe(true);
    visibility.mockReturnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.waitFor(() =>
      expect(
        root.querySelector<HTMLButtonElement>('.inbox-reader .mark-read')
          ?.disabled,
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

  it('marks an item read only after explicit acknowledgement', async () => {
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
    control(root, '.mark-read').click();
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

      const button = control(root, '.mark-read');
      button.click();
      expect(
        root.querySelector<HTMLButtonElement>('.inbox-reader .mark-read')
          ?.disabled,
      ).toBe(true);

      await vi.waitFor(() =>
        expect(
          root.querySelector('.inbox-reader [data-trouble]')?.textContent,
        ).toContain('not open'),
      );
      expect(
        root.querySelector('.inbox-reader [data-trouble]')?.textContent,
      ).toContain('not open');
    },
  );

  it('requires a fresh authorized read after a refused receipt', async () => {
    const replies: Record<string, FrameOutcome | Error> = {
      'inbox.list': { code: 'OK', payload: { items: [item], unread: 1 } },
      'inbox.read': { code: 'BAD_REQUEST', said: 'Receipt refused' },
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
    control(root, '.mark-read').click();
    await vi.waitFor(() =>
      expect(root.querySelector('[role="alert"]')?.textContent).toContain(
        'Receipt refused',
      ),
    );
    expect(control(root, '.mark-read').disabled).toBe(true);
    control(root, '.mark-read').dispatchEvent(new Event('click'));
    expect(
      stream.ask.mock.calls.filter(([type]) => type === 'inbox.read'),
    ).toHaveLength(1);
    replies['inbox.list'] = { code: 'BAD_REQUEST', said: 'Membership revoked' };
    control(root, '.reload').click();
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Membership revoked'),
    );
    expect(control(root, '.mark-read').disabled).toBe(true);
    replies['inbox.list'] = {
      code: 'OK',
      payload: { items: [item], unread: 1 },
    };
    control(root, '.reload').click();
    await vi.waitFor(() =>
      expect(control(root, '.mark-read').disabled).toBe(false),
    );
    expect(
      stream.ask.mock.calls.filter(([type]) => type === 'inbox.read'),
    ).toHaveLength(1);
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

describe('bounded inbox inspection and filtering', () => {
  function view(replies: Record<string, FrameOutcome | Error>) {
    const stream = fakeStream(replies);
    const root = document.createElement('div');
    const screen = inbox({
      root,
      openStream: stream.open,
      pollMs: null,
      session: 's',
    });
    return { stream, root, screen };
  }

  function filter(root: HTMLElement, value: string): void {
    const select = root.querySelector<HTMLSelectElement>('.inbox-filter');
    if (select === null) throw new Error('Expected inbox filter');
    select.value = value;
    select.dispatchEvent(new Event('change'));
  }

  it('bounds repeated previews, reads full selected text and never marks inspection read', async () => {
    const long = 'Saved result '.repeat(100) + '<script>private tail</script>';
    const { root, stream, screen } = view({
      'inbox.list': {
        code: 'OK',
        payload: {
          items: [item, { ...item, id: 'inb_2', answer: long }],
          unread: 2,
        },
      },
    });
    document.body.append(root);
    await screen.load();
    expect(root.querySelectorAll('.inbox-answer')).toHaveLength(1);
    expect(
      root.querySelector('[data-item="inb_2"] .inbox-preview')?.textContent,
    ).toHaveLength(241);
    expect(root.querySelector('.inbox-answer')?.textContent).toBe(item.answer);
    control(root, '[data-item="inb_2"] .inbox-select').click();
    expect(root.querySelector('.inbox-answer')?.textContent).toBe(long);
    expect(root.querySelector('script')).toBeNull();
    expect(root.ownerDocument.activeElement).toBe(
      root.querySelector('.inbox-reader-title'),
    );
    expect(
      root.querySelector('.record-links a')?.getAttribute('href'),
    ).toContain('cnv_1');
    expect(stream.ask.mock.calls.every(([type]) => type === 'inbox.list')).toBe(
      true,
    );
  });

  it('keeps reading position and keyboard focus when a retained selection refreshes', async () => {
    const { root, stream, screen } = view({
      'inbox.list': { code: 'OK', payload: { items: [item], unread: 1 } },
    });
    document.body.append(root);
    await screen.load();
    control(root, '.inbox-select').click();
    const answer = root.querySelector<HTMLElement>('.inbox-answer');
    const rows = root.querySelector<HTMLElement>('.inbox-rows');
    if (answer === null || rows === null)
      throw new Error('Expected inbox reading panes');
    answer.scrollTop = 100;
    rows.scrollTop = 50;
    stream.push({ kind: 'inbox.changed', unread: 1 });
    await vi.waitFor(() =>
      expect(root.querySelector('.inbox-answer')).not.toBe(answer),
    );
    expect(root.querySelector<HTMLElement>('.inbox-answer')?.scrollTop).toBe(
      100,
    );
    expect(rows.scrollTop).toBe(50);
    expect(document.activeElement).toBe(
      root.querySelector('.inbox-reader-title'),
    );
    control(root, '.inbox-select').focus();
    stream.push({ kind: 'inbox.changed', unread: 1 });
    await vi.waitFor(() => expect(stream.ask).toHaveBeenCalledTimes(3));
    expect(document.activeElement).toBe(control(root, '.inbox-select'));
  });

  it('rejects obsolete receipt controls after moving to a different page', async () => {
    const replies: Record<string, FrameOutcome | Error> = {
      'inbox.list': {
        code: 'OK',
        payload: {
          items: Array.from({ length: 50 }, (_, index) => ({
            ...item,
            id: `inb_${index}`,
          })),
          unread: 51,
        },
      },
    };
    const { root, stream, screen } = view(replies);
    await screen.load();
    const obsolete = control(root, '.mark-read');
    replies['inbox.list'] = {
      code: 'OK',
      payload: { items: [{ ...item, id: 'inb_next' }], unread: 51 },
    };
    control(root, '.next').click();
    await vi.waitFor(() =>
      expect(root.querySelector('.inbox-reader-title')?.textContent).toContain(
        'inb_next',
      ),
    );
    obsolete.dispatchEvent(new Event('click'));
    expect(stream.ask.mock.calls.every(([type]) => type === 'inbox.list')).toBe(
      true,
    );
  });

  it('removes a selected result that is absent from the reconciled page', async () => {
    const replies: Record<string, FrameOutcome | Error> = {
      'inbox.list': { code: 'OK', payload: { items: [item], unread: 1 } },
    };
    const { root, stream, screen } = view(replies);
    await screen.load();
    replies['inbox.list'] = {
      code: 'OK',
      payload: {
        items: [{ ...item, id: 'inb_new', answer: 'New retained result' }],
        unread: 1,
      },
    };
    stream.push({ kind: 'inbox.changed', unread: 1 });
    await vi.waitFor(() =>
      expect(root.textContent).toContain('no longer in this page'),
    );
    expect(root.querySelector('.inbox-answer')).toBeNull();
    expect(root.textContent).not.toContain(item.answer);
    expect(root.querySelector('.mark-read')).toBeNull();
    control(root, '.inbox-select').click();
    expect(root.querySelector('.inbox-answer')?.textContent).toBe(
      'New retained result',
    );
  });

  it('filters on the server before paging and resets the offset when the filter changes', async () => {
    const items = Array.from({ length: 50 }, (_, index) => ({
      ...item,
      id: `inb_${index}`,
    }));
    const { root, stream, screen } = view({
      'inbox.list': { code: 'OK', payload: { items, unread: 75 } },
    });
    await screen.load();
    expect(stream.ask).toHaveBeenLastCalledWith('inbox.list', {
      offset: 0,
      limit: 50,
      unread: false,
    });
    control(root, '.next').click();
    await vi.waitFor(() => expect(root.textContent).toContain('page 2'));
    expect(stream.ask).toHaveBeenLastCalledWith('inbox.list', {
      offset: 50,
      limit: 50,
      unread: false,
    });
    filter(root, 'unread');
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Unread deliveries · page 1'),
    );
    expect(stream.ask).toHaveBeenLastCalledWith('inbox.list', {
      offset: 0,
      limit: 50,
      unread: true,
    });
    expect(control(root, '.previous').disabled).toBe(true);
    expect(root.querySelectorAll('[data-item]')).toHaveLength(50);
  });

  it('fences a late filter reply even after returning to the same query', async () => {
    const { root, stream, screen } = view({
      'inbox.list': {
        code: 'OK',
        payload: {
          items: [{ ...item, answer: 'Current all result' }],
          unread: 1,
        },
      },
    });
    await screen.load();
    let resolve: (reply: FrameOutcome) => void = () => {};
    stream.ask.mockReturnValueOnce(
      new Promise<FrameOutcome>((accept) => {
        resolve = accept;
      }),
    );
    control(root, '.reload').click();
    filter(root, 'unread');
    filter(root, 'all');
    resolve({
      code: 'OK',
      payload: {
        items: [{ ...item, answer: 'Obsolete all result' }],
        unread: 1,
      },
    });
    await vi.waitFor(() =>
      expect(root.querySelector('.inbox-answer')?.textContent).toBe(
        'Current all result',
      ),
    );
    expect(root.textContent).not.toContain('Obsolete all result');
    expect(stream.ask).toHaveBeenCalledTimes(3);
  });

  it('offers All deliveries for an empty unread page and keeps paging bounded', async () => {
    const { root, stream, screen } = view({
      'inbox.list': { code: 'OK', payload: { items: [], unread: 0 } },
    });
    await screen.load();
    filter(root, 'unread');
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Choose All deliveries'),
    );
    expect(control(root, '.next').disabled).toBe(true);
    expect(control(root, '.previous').disabled).toBe(true);
    expect(root.querySelector('.mark-read')).toBeNull();
    expect(stream.ask).toHaveBeenCalledTimes(2);
  });

  it('labels cached results stale and never replays an uncertain receipt on reconnect', async () => {
    const { root, stream, screen } = view({
      'inbox.list': { code: 'OK', payload: { items: [item], unread: 1 } },
      'inbox.read': new Error('Connection lost'),
    });
    await screen.load();
    control(root, '.mark-read').click();
    await vi.waitFor(() =>
      expect(root.textContent).toContain('receipt was not replayed'),
    );
    stream.status({ state: 'reconnecting', attempt: 1, retryInMs: 500 });
    expect(root.querySelector('[role="status"]')?.textContent).toContain(
      'may be stale',
    );
    expect(root.querySelector('.inbox-answer')?.textContent).toBe(item.answer);
    stream.status({ state: 'open', attempt: 0, retryInMs: null });
    await vi.waitFor(() =>
      expect(
        stream.ask.mock.calls.filter(([type]) => type === 'inbox.list'),
      ).toHaveLength(2),
    );
    expect(control(root, '.mark-read').disabled).toBe(true);
    control(root, '.mark-read').dispatchEvent(new Event('click'));
    expect(
      stream.ask.mock.calls.filter(([type]) => type === 'inbox.read'),
    ).toHaveLength(1);
  });
});
