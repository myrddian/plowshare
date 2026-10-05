import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { createMemory, describeMemoryState, EMPTY_INDEX } from './memory';
import type { Screen, Transport } from './screen';
import type { MemoryView, RecallResponse, TocEntry } from './wire';

let server: {
  index: TocEntry[];
  bodies: Record<string, MemoryView>;
  recall: RecallResponse;
};
let root: HTMLElement;
let screen: Screen;
let get: ReturnType<typeof vi.fn>;
let post: ReturnType<typeof vi.fn>;

function entry(over: Partial<TocEntry>): TocEntry {
  return {
    id: 'mem-1',
    summary: 'service tokens are minted per call',
    scope: 'auth, service-to-service calls',
    unsearchable: false,
    ...over,
  };
}

function memory(over: Partial<MemoryView>): MemoryView {
  return {
    id: 'mem-1',
    summary: 'service tokens are minted per call',
    scope: 'auth, service-to-service calls',
    formed: { at: '2026-08-01T10:00:00Z', by: 'scribe', where: 'payments' },
    state: 'active',
    pinned: false,
    uses: 4,
    lastUsed: '2026-08-29T10:00:00Z',
    body: 'the whole of what the summary stands for',
    supersedes: null,
    supersededBy: null,
    invalidation: null,
    home: { project: 'payments' },
    ...over,
  };
}

function transport(): Transport {
  get = vi.fn(async (path: string): Promise<unknown> => {
    if (path.startsWith('/v1/memories/index')) {
      return server.index;
    }
    const match = /^\/v1\/memories\/([^/]+)$/.exec(path);
    if (match !== null) {
      const found = server.bodies[decodeURIComponent(match[1] as string)];
      if (found === undefined) {
        throw new ApiError(`${path} answered 404`, 404);
      }
      return found;
    }
    throw new ApiError(`${path} answered 404`, 404);
  });
  post = vi.fn(async (path: string, payload?: unknown): Promise<unknown> => {
    if (path === '/v1/memories/recall') {
      return server.recall;
    }
    const match = /^\/v1\/memories\/([^/]+)\/invalidate$/.exec(path);
    if (match !== null) {
      const id = decodeURIComponent(match[1] as string);
      const was = server.bodies[id] as MemoryView;
      const dead: MemoryView = {
        ...was,
        state: 'invalidated',
        invalidation: {
          at: '2026-09-02T10:00:00Z',
          by: (payload as { by: string }).by,
          reason: (payload as { reason: string }).reason,
        },
      };
      server.bodies[id] = dead;
      server.index = server.index.filter((one) => one.id !== id);
      return dead;
    }
    throw new ApiError(`${path} answered 404`, 404);
  });
  return { get, post } as unknown as Transport;
}

/**
 * Every path the screen fetched a whole memory from.
 *
 * The index's own path is excluded by name rather than by shape: `/v1/memories/
 * index` is one segment like any id, so a pattern alone would count the very
 * read whose cheapness these tests are about.
 */
function bodyReads(): string[] {
  return get.mock.calls
    .map((call) => call[0] as string)
    .filter((path) => !path.startsWith('/v1/memories/index'))
    .filter((path) => /^\/v1\/memories\/[^/?]+$/.test(path));
}

function card(id: string): HTMLElement | null {
  return root.querySelector(`[data-memory="${id}"]`);
}

beforeEach(() => {
  server = {
    index: [
      entry({ id: 'mem-1' }),
      entry({
        id: 'mem-2',
        summary: 'the ledger closes on the last business day',
      }),
      entry({ id: 'mem-3', summary: 'refunds go through the same gateway' }),
    ],
    bodies: {
      'mem-1': memory({}),
      'mem-2': memory({ id: 'mem-2', body: 'what mem-2 stands for' }),
      'mem-3': memory({ id: 'mem-3', body: 'what mem-3 stands for' }),
    },
    recall: { question: 'x', limit: 10, memories: [], unsearchable: 0 },
  };
  root = document.createElement('main');
  document.body.replaceChildren(root);
  screen = createMemory({ root, transport: transport(), project: 'payments' });
});

describe('the index carries summaries and a body is fetched when one is opened', () => {
  it('draws the whole index without asking for a single body', async () => {
    // TocEntry has no body deliberately: the index is read whole, so a body
    // there is a body in every prompt. A screen that fetched each one to
    // draw the list would move that cost onto a different budget and call
    // it a feature.
    await screen.load();

    expect(get).toHaveBeenCalledWith('/v1/memories/index?project=payments');
    expect(bodyReads()).toEqual([]);
    expect([...root.querySelectorAll('[data-memory]')]).toHaveLength(3);
    expect(card('mem-2')?.textContent).toContain('last business day');
    expect(root.textContent).not.toContain('what mem-2 stands for');
  });

  it('asks for one body when one memory is opened, and only for that one', async () => {
    await screen.load();

    (root.querySelector('[data-open="mem-2"]') as HTMLButtonElement).click();
    await vi.waitFor(() =>
      expect(card('mem-2')?.querySelector('[data-detail]')).not.toBeNull(),
    );

    expect(bodyReads()).toEqual(['/v1/memories/mem-2']);
    expect(card('mem-2')?.textContent).toContain('what mem-2 stands for');
    expect(card('mem-1')?.querySelector('[data-detail]')).toBeNull();
  });

  it('escapes an id into the path rather than into the URL’s shape', async () => {
    server.index = [entry({ id: 'a/b?c' })];
    server.bodies = { 'a/b?c': memory({ id: 'a/b?c' }) };
    await screen.load();

    (root.querySelector('[data-open]') as HTMLButtonElement).click();
    await vi.waitFor(() => expect(bodyReads()).toHaveLength(1));

    expect(bodyReads()).toEqual(['/v1/memories/a%2Fb%3Fc']);
  });

  it('asks for nothing at all for a body a recall already carried', async () => {
    // POST /v1/memories/recall answers with whole Memory objects, bodies
    // included, so a hit already carries what the index withholds. Fetching
    // it again would be a request for something this screen was handed.
    server.recall = {
      question: 'how do tokens work',
      limit: 10,
      memories: [memory({ id: 'mem-9', body: 'the recalled body' })],
      unsearchable: 0,
    };
    await screen.load();
    const question = root.querySelector(
      '[data-input="question"]',
    ) as HTMLInputElement;
    question.value = 'how do tokens work';
    (root.querySelector('button.ask') as HTMLButtonElement).click();
    await vi.waitFor(() => expect(card('mem-9')).not.toBeNull());

    (root.querySelector('[data-open="mem-9"]') as HTMLButtonElement).click();

    expect(card('mem-9')?.textContent).toContain('the recalled body');
    expect(bodyReads()).toEqual([]);
  });

  it('says the index carries no bodies, so the cost of opening one is not a surprise', async () => {
    await screen.load();
    expect(root.querySelector('.index-head')?.textContent).toContain(
      'index carries no bodies',
    );
  });
});

describe('an answer with nothing in it', () => {
  it('renders an empty index as an answer and not as a failure', async () => {
    server.index = [];

    await screen.load();

    const empty = root.querySelector('[data-empty]');
    expect(empty?.textContent).toBe(EMPTY_INDEX);
    expect(root.querySelector('[data-trouble]')).toBeNull();
  });

  it('says searched-and-there-was-nothing only when everything could be searched', async () => {
    server.recall = { question: 'q', limit: 10, memories: [], unsearchable: 0 };
    await screen.load();
    const question = root.querySelector(
      '[data-input="question"]',
    ) as HTMLInputElement;
    question.value = 'q';

    (root.querySelector('button.ask') as HTMLButtonElement).click();
    await vi.waitFor(() =>
      expect(root.querySelector('[data-recalled] [data-empty]')).not.toBeNull(),
    );

    const said = root.querySelector(
      '[data-recalled] [data-empty]',
    ) as HTMLElement;
    expect(said.textContent).toContain('all of it could be searched');
    expect(root.querySelector('[data-recalled] [data-trouble]')).toBeNull();
  });

  it('refuses to call an empty recall empty when part of the tier could not be searched', async () => {
    // unsearchable is the field that stops an empty answer being a lie.
    // Running the two cases together is exactly the failure it exists
    // to prevent: a reader told the archive is empty stops asking.
    server.recall = { question: 'q', limit: 10, memories: [], unsearchable: 2 };
    await screen.load();
    const question = root.querySelector(
      '[data-input="question"]',
    ) as HTMLInputElement;
    question.value = 'q';

    (root.querySelector('button.ask') as HTMLButtonElement).click();
    await vi.waitFor(() =>
      expect(
        root.querySelector('[data-recalled] [data-trouble]'),
      ).not.toBeNull(),
    );

    const said = root.querySelector(
      '[data-recalled] [data-trouble]',
    ) as HTMLElement;
    expect(said.textContent).toContain(
      '2 memories this recall drew on have no embedding',
    );
    expect(said.textContent).toContain('could not be looked at');
    expect(root.querySelector('[data-recalled] [data-empty]')).toBeNull();
  });

  it('says a recall that found things was still partial', async () => {
    server.recall = {
      question: 'q',
      limit: 10,
      memories: [memory({ id: 'mem-5' })],
      unsearchable: 1,
    };
    await screen.load();
    const question = root.querySelector(
      '[data-input="question"]',
    ) as HTMLInputElement;
    question.value = 'q';

    (root.querySelector('button.ask') as HTMLButtonElement).click();
    await vi.waitFor(() => expect(card('mem-5')).not.toBeNull());

    const partial = root.querySelector('[data-partial]') as HTMLElement;
    expect(partial.dataset['partial']).toBe('1');
    expect(partial.textContent).toContain(
      '1 memory this recall drew on has no embedding',
    );
  });

  it('marks an index line nothing can reach by question', async () => {
    server.index = [entry({ id: 'mem-1', unsearchable: true })];

    await screen.load();

    expect(card('mem-1')?.getAttribute('data-unsearchable')).toBe('true');
    expect(
      card('mem-1')?.querySelector('[data-unsearchable]')?.textContent,
    ).toContain('no question reaches it');
  });
});

describe('a state this build has never heard of', () => {
  it('renders a state as itself rather than failing over one', () => {
    expect(describeMemoryState('active')).toBe('active');
    expect(describeMemoryState('cold')).toContain('working set');
    expect(describeMemoryState('archived')).toBe('archived');
    expect(describeMemoryState('constructor')).toBe('constructor');
    expect(describeMemoryState(undefined)).toContain('did not name');
  });

  it('opens a memory in a state and a shape this build was not written against', async () => {
    server.bodies['mem-1'] = {
      id: 'mem-1',
      summary: 's',
      scope: 'c',
      state: 'archived',
      body: 'b',
    } as unknown as MemoryView;
    await screen.load();

    (root.querySelector('[data-open="mem-1"]') as HTMLButtonElement).click();
    await vi.waitFor(() =>
      expect(card('mem-1')?.querySelector('[data-detail]')).not.toBeNull(),
    );

    expect(card('mem-1')?.textContent).toContain('archived');
    expect(card('mem-1')?.textContent).toContain('never');
  });
});

describe('absence is not zero', () => {
  it('says a use count nothing reported was not reported, and never that it was none', async () => {
    server.bodies['mem-1'] = memory({ uses: null, lastUsed: null });
    await screen.load();

    (root.querySelector('[data-open="mem-1"]') as HTMLButtonElement).click();
    await vi.waitFor(() =>
      expect(card('mem-1')?.querySelector('[data-detail]')).not.toBeNull(),
    );

    const detail = card('mem-1')?.querySelector('[data-detail]') as HTMLElement;
    expect(
      detail.querySelector('[data-field="recalled"] .value')?.textContent,
    ).toBe('times not reported');
    expect(
      detail.querySelector('[data-field="last recalled"] .value')?.textContent,
    ).toBe('never');
  });

  it('renders a memory recall has never returned as never, not as the epoch', async () => {
    server.bodies['mem-1'] = memory({ uses: 0, lastUsed: null });
    await screen.load();

    (root.querySelector('[data-open="mem-1"]') as HTMLButtonElement).click();
    await vi.waitFor(() =>
      expect(card('mem-1')?.querySelector('[data-detail]')).not.toBeNull(),
    );

    const detail = card('mem-1')?.querySelector('[data-detail]') as HTMLElement;
    expect(
      detail.querySelector('[data-field="recalled"] .value')?.textContent,
    ).toBe('0 times');
    expect(detail.textContent).not.toContain('1970');
  });
});

describe('invalidating one', () => {
  it('sends the reason and the name, and re-reads the index', async () => {
    await screen.load();
    (root.querySelector('[data-open="mem-1"]') as HTMLButtonElement).click();
    await vi.waitFor(() =>
      expect(card('mem-1')?.querySelector('[data-detail]')).not.toBeNull(),
    );
    (root.querySelector('[data-input="by"]') as HTMLInputElement).value =
      'enzo';
    (
      root.querySelector(
        '[data-input="invalidation reason"]',
      ) as HTMLInputElement
    ).value = 'the gateway changed';

    (root.querySelector('button.invalidate') as HTMLButtonElement).click();
    await vi.waitFor(() => expect(card('mem-1')).toBeNull());

    expect(post).toHaveBeenCalledWith('/v1/memories/mem-1/invalidate', {
      reason: 'the gateway changed',
      by: 'enzo',
    });
    expect([...root.querySelectorAll('[data-memory]')]).toHaveLength(2);
  });

  it('sends nothing at all without a reason and a name', async () => {
    await screen.load();
    (root.querySelector('[data-open="mem-1"]') as HTMLButtonElement).click();
    await vi.waitFor(() =>
      expect(card('mem-1')?.querySelector('[data-detail]')).not.toBeNull(),
    );

    (root.querySelector('button.invalidate') as HTMLButtonElement).click();

    expect(post).not.toHaveBeenCalled();
    expect(card('mem-1')?.textContent).toContain('an absence teaches nobody');
  });
});

describe('rendering is escaping', () => {
  it('lands a payload in a summary, a scope and a body in the DOM as text', async () => {
    const payload = '<img src=x onerror=alert(1)>';
    server.index = [
      entry({ id: 'mem-1', summary: payload, scope: `when ${payload}` }),
    ];
    server.bodies['mem-1'] = memory({ body: `the note said ${payload}` });

    await screen.load();
    (root.querySelector('[data-open="mem-1"]') as HTMLButtonElement).click();
    await vi.waitFor(() =>
      expect(card('mem-1')?.querySelector('[data-detail]')).not.toBeNull(),
    );

    expect(root.querySelector('img')).toBeNull();
    expect(card('mem-1')?.querySelector('.memory-summary')?.textContent).toBe(
      payload,
    );
    expect(card('mem-1')?.querySelector('.scope')?.textContent).toBe(
      `when ${payload}`,
    );
    expect(card('mem-1')?.querySelector('pre.body')?.textContent).toBe(
      `the note said ${payload}`,
    );
    // And not double-encoded: the person must read what the memory says,
    // entities and all.
    expect(root.textContent).not.toContain('&lt;');
  });
});

describe('a read that could not be made', () => {
  it('says the index failed rather than drawing an archive with nothing in it', async () => {
    get.mockRejectedValue(new ApiError('/v1/memories/index answered 503', 503));

    await screen.load();

    expect(root.querySelector('[data-empty]')).toBeNull();
    expect(root.querySelector('[data-trouble]')?.textContent).toContain(
      'answered 503',
    );
  });

  it('offers the open again when a body could not be read', async () => {
    await screen.load();
    delete server.bodies['mem-1'];

    (root.querySelector('[data-open="mem-1"]') as HTMLButtonElement).click();
    await vi.waitFor(() =>
      expect(card('mem-1')?.querySelector('[data-trouble]')).not.toBeNull(),
    );

    expect(
      (root.querySelector('[data-open="mem-1"]') as HTMLButtonElement).disabled,
    ).toBe(false);
    expect(card('mem-1')?.querySelector('[data-detail]')).toBeNull();
  });
});
