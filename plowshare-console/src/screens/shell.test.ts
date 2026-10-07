import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import type { EventStream, EventStreamOptions, StreamStatus } from '../events';
import {
  createShell,
  multiplex,
  VIEWS,
  viewFromHash,
  type Shell,
} from './shell';
import type { Transport } from './screen';

let root: HTMLElement;
let shell: Shell;
let get: ReturnType<typeof vi.fn>;
let post: ReturnType<typeof vi.fn>;
/** Every real socket the shell asked for, with the session it asked under. */
let sockets: { session: string; closed: boolean }[];
let listeners: ((event: unknown) => void)[];
/** The status callback the multiplexed socket was opened with. */
let statuses: ((status: StreamStatus) => void) | null;

function transport(): Transport {
  get = vi.fn(async (path: string): Promise<unknown> => {
    if (path === '/v1/agents') {
      return [{ name: 'interlocutor', tools: [], calls: [], scopes: [] }];
    }
    if (path.startsWith('/v1/conversations')) {
      return [];
    }
    if (path === '/v1/projects') {
      return [];
    }
    if (path === '/v1/jobs') {
      return [];
    }
    if (path.startsWith('/v1/proposals')) {
      return [];
    }
    if (path.startsWith('/v1/memories/index')) {
      return [];
    }
    throw new ApiError(`${path} answered 404`, 404);
  });
  post = vi.fn(async (path: string): Promise<unknown> => {
    throw new ApiError(`${path} answered 404`, 404);
  });
  return { get, post } as unknown as Transport;
}

function opener(options: EventStreamOptions): EventStream {
  const record = { session: options.session, closed: false };
  sockets.push(record);
  listeners.push(options.onEvent);
  statuses = options.onStatus ?? null;
  // A real socket announces as soon as it is up, which is what makes the
  // question "what does a view built later get told?" a question at all.
  options.onStatus?.({ state: 'open', attempt: 0, retryInMs: null });
  return {
    status: (): StreamStatus => ({
      state: 'open',
      attempt: 0,
      retryInMs: null,
    }),
    close: (): void => {
      record.closed = true;
    },
    ask: () => Promise.reject(new Error('not in this test')),
  };
}

function nav(name: string): HTMLButtonElement {
  return root.querySelector(`[data-nav="${name}"]`) as HTMLButtonElement;
}

function host(name: string): HTMLElement {
  return root.querySelector(`[data-view="${name}"]`) as HTMLElement;
}

/** A window double: the hash the shell reads and rewrites, and nothing else. */
function scopeAt(hash: string): Window {
  const rewritten: string[] = [];
  return {
    location: { hash },
    history: {
      pushState: (_state: unknown, _title: string, url: string): void => {
        rewritten.push(url);
      },
    },
    rewritten,
  } as unknown as Window & { rewritten: string[] };
}

let scope: Window & { rewritten: string[] };

beforeEach(() => {
  root = document.createElement('main');
  document.body.replaceChildren(root);
  sockets = [];
  listeners = [];
  statuses = null;
  scope = scopeAt('') as Window & { rewritten: string[] };
  shell = createShell({
    root,
    transport: transport(),
    openStream: opener,
    session: 'session-under-test',
    scope,
    // No poll anywhere: a suite that let a timer run would be a suite whose
    // failures depend on how long a machine took.
    pollMs: null,
  });
});

afterEach(() => {
  shell.destroy();
});

describe('eight views and no router', () => {
  it('offers every view in the nav, with inbox first', () => {
    expect(
      [...root.querySelectorAll<HTMLElement>('[data-nav]')].map(
        (node) => node.dataset['nav'],
      ),
    ).toEqual([...VIEWS]);
  });

  it('lands on the work overview and marks it as the one showing', async () => {
    await shell.start();

    expect(shell.current()).toBe('overview');
    expect(host('overview').hidden).toBe(false);
    expect(host('jobs').hidden).toBe(true);
    expect(nav('overview').getAttribute('aria-current')).toBe('page');
    expect(nav('jobs').getAttribute('aria-current')).toBeNull();
  });

  it('reaches chat in one action from every other view', async () => {
    await shell.start();

    for (const name of VIEWS) {
      await shell.show(name);
      // The nav is on the page in every view, so chat is one click.
      nav('chat').click();
      await vi.waitFor(() => expect(shell.current()).toBe('chat'));
      expect(host('chat').hidden).toBe(false);
    }
  });

  it('builds a view on its first visit and not before', async () => {
    // Not /v1/projects: chat's own picker reads that on its first visit
    // too, so it cannot tell "built" from "not built" here. The memory
    // index is asked for by nothing but the memory screen.
    await shell.start();
    expect(get).not.toHaveBeenCalledWith('/v1/memories/index');

    await shell.show('memory');

    expect(get).toHaveBeenCalledWith('/v1/memories/index');
    expect(host('memory').querySelector('.screen.memory')).not.toBeNull();
  });

  it('keeps a view that has been left, rather than rebuilding it', async () => {
    // Leaving chat must not end the conversation in it or close its
    // socket: it is the reason this console exists, and coming back has to
    // land on what was left.
    await shell.start();
    await shell.show('chat');
    const chat = host('chat').firstElementChild;
    await shell.show('jobs');
    await shell.show('chat');

    expect(host('chat').firstElementChild).toBe(chat);
    // One listing of the agents, from the one build.
    expect(
      get.mock.calls.filter((call) => call[0] === '/v1/agents'),
    ).toHaveLength(1);
  });

  it('starts on the view the hash names, and rewrites it on every switch', async () => {
    const other = scopeAt('#memory') as Window & { rewritten: string[] };
    const another = createShell({
      root,
      transport: transport(),
      openStream: opener,
      session: 's',
      scope: other,
      pollMs: null,
    });

    await another.start();

    expect(another.current()).toBe('memory');
    expect(other.rewritten).toEqual([]);
    await another.show('jobs');
    expect(other.rewritten).toEqual(['#jobs']);
    another.destroy();
  });

  it('ignores a hash that names no view', () => {
    expect(viewFromHash('#jobs')).toBe('jobs');
    expect(viewFromHash('jobs')).toBe('jobs');
    expect(viewFromHash('#nothing-like-this')).toBeNull();
    expect(viewFromHash('')).toBeNull();
    expect(viewFromHash('#constructor')).toBeNull();
  });

  it('switches even when the hash cannot be rewritten', async () => {
    // replaceState throws in a document with an opaque origin. A console
    // that would not change view because it could not rewrite decoration
    // would be broken over decoration.
    const hostile = scopeAt('') as Window & { rewritten: string[] };
    (hostile.history as unknown as { pushState: () => void }).pushState =
      () => {
        throw new Error('SecurityError');
      };
    const another = createShell({
      root,
      transport: transport(),
      openStream: opener,
      session: 's',
      scope: hostile,
      pollMs: null,
    });

    await another.start();
    await another.show('projects');

    expect(another.current()).toBe('projects');
    another.destroy();
  });

  it('draws a screen that could not read rather than failing the switch', async () => {
    get.mockRejectedValue(new ApiError('/v1/projects answered 500', 500));

    await expect(shell.show('projects')).resolves.toBeUndefined();

    expect(shell.current()).toBe('projects');
    expect(host('projects').querySelector('[data-trouble]')).not.toBeNull();
  });
});

describe('the rail, grouped by what it is beside', () => {
  it('groups the rail, with the system section apart from the work', async () => {
    await shell.start();
    expect(root.querySelector('[data-rail-group="work"]')).not.toBeNull();
    expect(root.querySelector('[data-rail-group="system"]')).not.toBeNull();
  });

  it("shows the socket at the rail's foot and not inside a view", async () => {
    await shell.start();
    expect(root.querySelector('.rail [data-stream]')).not.toBeNull();
  });
});

describe('the stream’s state', () => {
  it('says a reconnect is happening rather than going still', async () => {
    // Ported from repl.test.ts: the label moved out of the REPL header and
    // into the rail's foot, but the behaviour it protects -- a reconnect
    // announces itself rather than leaving the socket's state to look
    // still -- is unchanged.
    await shell.start();

    statuses?.({ state: 'reconnecting', attempt: 3, retryInMs: 4000 });

    const label = root.querySelector('.rail [data-stream]') as HTMLElement;
    expect(label.dataset['state']).toBe('reconnecting');
    expect(label.textContent).toContain('4s');
  });
});

describe('one socket for the tab', () => {
  it('opens exactly one, whatever asks for a stream', async () => {
    // SessionRegistry keeps one LISTENER per session and the handler closes
    // what it displaced, so a second socket under this tab's id would kill
    // the first. A second under a different id would receive nothing at
    // all, because publish routes to the session that started the job.
    await shell.start();
    await shell.show('jobs');

    expect(sockets).toHaveLength(1);
    expect(sockets[0]?.session).toBe('session-under-test');
  });

  it('delivers every frame to every view that subscribed', () => {
    const seen: string[] = [];
    const shared = multiplex(opener, 'tab');
    shared.open({ session: 'ignored-a', onEvent: () => seen.push('a') });
    shared.open({ session: 'ignored-b', onEvent: () => seen.push('b') });

    listeners[0]?.({ job: 'job_1', kind: 'started' });

    expect(seen).toEqual(['a', 'b']);
    expect(sockets).toHaveLength(1);
    // The session is the tab's, and never the one a view asked for: a view
    // asking for its own would be asking for a listener the server will not
    // give it.
    expect(sockets[0]?.session).toBe('tab');
  });

  it('unsubscribes a view without closing the socket the others are on', () => {
    const seen: string[] = [];
    const shared = multiplex(opener, 'tab');
    const first = shared.open({ session: 'x', onEvent: () => seen.push('a') });
    shared.open({ session: 'x', onEvent: () => seen.push('b') });

    first.close();
    listeners[0]?.({ job: 'job_1', kind: 'started' });

    expect(seen).toEqual(['b']);
    expect(sockets[0]?.closed).toBe(false);
  });

  it('tells a view built later what the socket is already doing', () => {
    const shared = multiplex(opener, 'tab');
    const said: StreamStatus[] = [];
    shared.open({ session: 'x', onEvent: () => undefined });

    shared.open({
      session: 'x',
      onEvent: () => undefined,
      onStatus: (status) => said.push(status),
    });

    // Open, and not the `connecting` a fresh view would otherwise report
    // about a socket that has been up for a minute.
    expect(said.map((status) => status.state)).toEqual(['open']);
  });

  it('closes the one socket when the shell is destroyed', async () => {
    await shell.start();
    await shell.show('jobs');

    shell.destroy();

    expect(sockets[0]?.closed).toBe(true);
  });
});

describe('the inbox badge', () => {
  it('shows the unread count the moment the socket says it changed', async () => {
    await shell.start();

    listeners[0]?.({ kind: 'inbox.changed', unread: 3 });

    expect(nav('inbox').textContent).toBe('inbox 3');
  });

  it(
    'asks the unread count exactly once when the shared socket is already open the moment' +
      ' INBOX subscribes, rather than throwing on its own status read',
    async () => {
      // multiplex.subscribe calls a fresh watcher with the socket's current
      // status *synchronously*, before `shared.open` returns. The "do
      // nothing" listener `start()` opens first makes the real socket open
      // synchronously, as this fake reports it -- so INBOX's badge, which
      // subscribes right after in the same `start()`, hits exactly the moment
      // a `badgeStream` read in its own temporal dead zone would throw.
      const ask = vi.fn((_type: string, _payload?: { unread?: boolean }) =>
        Promise.resolve({ code: 'OK', payload: { items: [], unread: 2 } }),
      );
      const alreadyOpen = (options: EventStreamOptions): EventStream => {
        options.onStatus?.({ state: 'open', attempt: 0, retryInMs: null });
        return {
          status: (): StreamStatus => ({
            state: 'open',
            attempt: 0,
            retryInMs: null,
          }),
          close: (): void => {},
          ask,
        };
      };

      const another = createShell({
        root: document.createElement('main'),
        transport: transport(),
        openStream: alreadyOpen,
        session: 's',
        scope,
        pollMs: null,
      });

      await another.start();

      expect(
        ask.mock.calls.filter(
          (call) => call[0] === 'inbox.list' && call[1]?.unread === true,
        ),
      ).toHaveLength(1);
      expect(ask).toHaveBeenCalledWith('inbox.list', {
        unread: true,
        limit: 1,
      });
      another.destroy();
    },
  );
});

describe('read-only owning-record routes', () => {
  it('restores a job deep link on reload and follows back/forward without a mutation', async () => {
    window.history.replaceState(null, '', '#jobs?record=job_saved');
    const read = transport();
    get.mockImplementation(async (path: string) => {
      if (path === '/v1/jobs/job_saved')
        return {
          id: 'job_saved',
          agent: 'reviewer',
          state: 'RUNNING',
          cancelRequested: false,
          conversation: 'cnv_saved',
          limits: null,
          outcome: null,
        };
      return [];
    });
    const another = createShell({
      root,
      transport: read,
      openStream: opener,
      scope: window,
      session: 'route-tab',
      pollMs: null,
    });
    await another.start();
    expect(root.querySelector('.job-selection')?.textContent).toContain(
      'job_saved',
    );
    expect(post).not.toHaveBeenCalled();
    await another.show('inbox');
    window.history.back();
    await vi.waitFor(() => expect(another.current()).toBe('jobs'));
    window.history.forward();
    await vi.waitFor(() => expect(another.current()).toBe('inbox'));
    expect(post).not.toHaveBeenCalled();
    another.destroy();
    window.history.replaceState(null, '', '#');
  });
});
