import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import type {
  EventStream,
  EventStreamOptions,
  FrameOutcome,
  StreamStatus,
} from '../events';
import { createProjects, LEASH_NOTE, NOTHING_APPROVED } from './projects';
import type { Screen, Transport } from './screen';
import type { ProjectView } from './wire';

let server: { projects: ProjectView[] };
let root: HTMLElement;
let screen: Screen;
let get: ReturnType<typeof vi.fn>;
let post: ReturnType<typeof vi.fn>;

function project(over: Partial<ProjectView>): ProjectView {
  return {
    name: 'payments',
    workspace: '/srv/checkouts/payments',
    // Empty by default and not absent: lending nothing is what most
    // projects do, and a fixture whose default was the exotic case would
    // make every test that did not think about it a test of the exotic one.
    lent: [],
    exclusions: ['/opt/plowshare', '/srv/checkouts/payments/.env'],
    ...over,
  };
}

function transport(): Transport {
  get = vi.fn(async (path: string): Promise<unknown> => {
    if (path === '/v1/projects') {
      return server.projects;
    }
    throw new ApiError(`${path} answered 404`, 404);
  });
  post = vi.fn(async (path: string, payload?: unknown): Promise<unknown> => {
    if (/^\/v1\/projects\/[^/]+\/workspace$/.test(path)) {
      const workspace = (payload as { workspace: string }).workspace;
      server.projects = server.projects.map((one) => ({ ...one, workspace }));
      return server.projects[0];
    }
    throw new ApiError(`${path} answered 404`, 404);
  });
  return { get, post } as unknown as Transport;
}

/** Every exclusion rendered for the first project, in the order it was drawn. */
function exclusions(): string[] {
  return [...root.querySelectorAll('[data-exclusion]')].map(
    (node) => node.textContent ?? '',
  );
}

beforeEach(() => {
  server = { projects: [project({})] };
  root = document.createElement('main');
  document.body.replaceChildren(root);
  screen = createProjects({ root, transport: transport() });
});

describe('the leash a project is actually on', () => {
  it('draws the effective list and says it is the effective one', async () => {
    // ProjectView.exclusions is ProjectStore.effectiveExclusions' answer and
    // never ProjectRecord.exclusions(). Slice 3b's argument is that a person
    // cannot reason about a leash whose mandatory parts are invisible, so
    // the screen that shows the leash must show all of it.
    await screen.load();

    expect(exclusions()).toEqual([
      '/opt/plowshare',
      '/srv/checkouts/payments/.env',
    ]);
    const list = root.querySelector('[data-exclusions]');
    expect(list?.getAttribute('data-exclusions')).toBe('effective');
  });

  it('says part of the list is the server’s and immovable, and that it cannot say which', async () => {
    // The wire carries one list and marks nothing in it: ProjectView
    // sends List<String> with no per-entry flag and no companion field.
    // The screen states the property at the level the answer supports
    // it rather than deriving a marking that would be wrong in the one
    // direction that matters — see projects.ts's header.
    await screen.load();

    const note = root.querySelector('[data-leash-note]')?.textContent ?? '';
    expect(note).toBe(LEASH_NOTE);
    expect(note).toContain('as this server enforces them');
    expect(note).toContain('does not mark which');
    expect(note).toContain('applied to every project');
  });

  it('neither counts the server’s own paths nor names one', async () => {
    // ProjectStore.mandatoryExclusions derives its answer from a set of
    // candidates and drops any that lies inside another, so the ordinary
    // arrangement collapses to a single path. Every comment in this
    // repository that stated a number for them was wrong and had to be
    // fixed -- the count has now grown twice; a screen that stated one would
    // be wrong on the same day.
    await screen.load();

    const note = root.querySelector('[data-leash-note]')?.textContent ?? '';
    expect(note).not.toMatch(/\d/);
    expect(note).not.toMatch(/\b(one|two|three|four|both|neither|either)\b/i);
    for (const path of exclusions()) {
      expect(note).not.toContain(path);
    }
    // And the marking is on the list rather than on any entry in it: an
    // entry marked immovable that the row could in fact drop is the error
    // that teaches an operator a false rule about their own server.
    for (const node of root.querySelectorAll('[data-exclusion]')) {
      expect(node.getAttribute('data-mandatory')).toBeNull();
      expect(node.textContent).not.toMatch(/cannot|immovable|mandatory/i);
    }
  });

  it('shows every project’s whole leash and not only the first', async () => {
    server.projects = [
      project({ name: 'payments', exclusions: ['/opt/plowshare', '/a'] }),
      project({
        name: 'ledger',
        workspace: '/srv/ledger',
        exclusions: ['/opt/plowshare'],
      }),
    ];

    await screen.load();

    const cards = [...root.querySelectorAll('[data-project]')];
    expect(cards.map((node) => node.getAttribute('data-project'))).toEqual([
      'payments',
      'ledger',
    ]);
    expect(cards[1]?.querySelectorAll('[data-exclusion]')).toHaveLength(1);
    // A path on every project is not thereby marked as one no project can
    // remove. Two projects fencing off the same directory is a coincidence
    // the wire cannot be told from the rule.
    const shared = [...root.querySelectorAll('[data-exclusion]')].filter(
      (node) => node.textContent === '/opt/plowshare',
    );
    expect(shared).toHaveLength(2);
    for (const node of shared) {
      expect(node.getAttribute('data-mandatory')).toBeNull();
    }
  });
});

describe('an answer with nothing in it', () => {
  it('renders no projects as an answer rather than as a failure', async () => {
    server.projects = [];

    await screen.load();

    const empty = root.querySelector('[data-empty]');
    expect(empty).not.toBeNull();
    expect(empty?.textContent).toContain('no local file access');
    expect(root.querySelector('[data-trouble]')).toBeNull();
  });

  it('refuses to read an empty exclusion list as an open project', async () => {
    // This server adds its own paths before answering, so it does not
    // produce this. Rendering it as "searched, and there was nothing" would
    // be the one reading that is both reassuring and unsafe.
    server.projects = [project({ exclusions: [] })];

    await screen.load();

    // Scoped to the leash section, because the card has a second empty
    // state that is honest: a project lending nothing renders `[data-empty]`
    // in its `lent` section, and that IS "searched, and there was nothing".
    // The two must not be read as one -- an unscoped query here would fail
    // over the honest one and would have to be relaxed, which would take the
    // assertion about the dangerous one with it.
    expect(root.querySelector('.leash [data-empty]')).toBeNull();
    expect(root.querySelector('[data-trouble]')?.textContent).toContain(
      'unknown rather than',
    );
  });
});

describe('what else the project reaches', () => {
  it('lists every lent root beside the workspace', async () => {
    server.projects = [
      project({ lent: ['/srv/checkouts/payments/.github', '/srv/shared'] }),
    ];

    await screen.load();

    expect(
      [...root.querySelectorAll('[data-lent-root]')].map((e) => e.textContent),
    ).toEqual(['/srv/checkouts/payments/.github', '/srv/shared']);
    // And the workspace is still its own row: it is the <PATH> of the
    // project's full name, so a screen that merged the two would make every
    // path after the first look like somewhere the project might be.
    expect(
      root.querySelector('[data-field="workspace"]')?.textContent,
    ).toContain('/srv/checkouts/payments');
  });

  it('keeps the order the server sent, which is workspace-first', async () => {
    // FileAccess resolves by deepest covering root, so order cannot change
    // what is permitted -- what it carries is which directory the project's
    // own place is, and file_roots renders the same order to a model. A
    // screen that sorted would be a second, disagreeing answer.
    server.projects = [project({ lent: ['/z-last', '/a-first'] })];

    await screen.load();

    expect(
      [...root.querySelectorAll('[data-lent-root]')].map((e) => e.textContent),
    ).toEqual(['/z-last', '/a-first']);
  });

  it('says a project lending nothing lends nothing, rather than saying nothing', async () => {
    // The opposite of the empty exclusion list one section up, and the
    // reason the two empty states are not one helper: the server adds no
    // lending of its own, so an empty list here is the plain truth about
    // most projects. Rendering it as trouble would teach a person to
    // distrust the honest answer.
    server.projects = [project({ lent: [] })];

    await screen.load();

    expect(root.querySelector('.lent [data-empty]')?.textContent).toContain(
      'Nothing else is lent',
    );
    expect(root.querySelector('.lent [data-trouble]')).toBeNull();
  });

  it('renders a project whose lent list the answer did not carry', async () => {
    // A console is deployed with the server it talks to, but api.get
    // validates nothing by its own javadoc, and a field this build knows
    // about must reach the screen as missing rather than throw over the
    // page -- the same rule the exclusions half is held to.
    server.projects = [project({ lent: null })];

    await expect(screen.load()).resolves.toBeUndefined();

    expect(root.querySelectorAll('[data-lent-root]')).toHaveLength(0);
    expect(root.textContent).toContain('/srv/checkouts/payments');
  });
});

describe('a wire this build was not written against', () => {
  it('renders a project whose exclusions the answer did not carry', async () => {
    // api.get validates nothing, by its own javadoc, and a screen should
    // render a missing field as missing rather than throw over the page.
    server.projects = [
      {
        name: 'payments',
        workspace: '/srv/payments',
      } as unknown as ProjectView,
      {
        name: 'ledger',
        workspace: '/srv/ledger',
        lent: null,
        exclusions: null,
      },
    ];

    await expect(screen.load()).resolves.toBeUndefined();

    expect([...root.querySelectorAll('[data-project]')]).toHaveLength(2);
    expect(root.textContent).toContain('/srv/ledger');
  });

  it('renders a project carrying a field this build has never heard of', async () => {
    // Projects carry no enum on the wire — there is no constant here for a
    // server to add one to — so the shape of the same rule is an unexpected
    // field, which must reach the screen as an ignored one rather than as a
    // failure.
    server.projects = [
      { ...project({}), tier: 'something_later' } as unknown as ProjectView,
    ];

    await expect(screen.load()).resolves.toBeUndefined();

    expect(root.querySelector('[data-project="payments"]')).not.toBeNull();
    expect(root.textContent).not.toContain('something_later');
  });

  it('says the listing failed rather than drawing a server with no projects on it', async () => {
    get.mockRejectedValue(new ApiError('/v1/projects answered 500', 500));

    await screen.load();

    expect(root.querySelector('[data-empty]')).toBeNull();
    expect(root.querySelector('[data-trouble]')?.textContent).toContain(
      'answered 500',
    );
  });
});

describe('rendering is escaping', () => {
  it('lands a payload in a name, a workspace and a path in the DOM as text', async () => {
    const payload = '<img src=x onerror=alert(1)>';
    server.projects = [
      project({
        name: payload,
        workspace: `/srv/${payload}`,
        exclusions: [`/opt/${payload}`],
      }),
    ];

    await screen.load();

    expect(root.querySelector('img')).toBeNull();
    // Read off the attribute rather than selected for: jsdom has no
    // `CSS.escape`, and a selector assembled around a payload would be this
    // test doing the very interpolation the screen refuses to do.
    expect(
      [...root.querySelectorAll('[data-project]')].map((node) =>
        node.getAttribute('data-project'),
      ),
    ).toEqual([payload]);
    expect(exclusions()).toEqual([`/opt/${payload}`]);
    // And not double-encoded on the way in, which is the other way to get
    // this wrong: an operator must read the path they typed, entities and
    // all, or they cannot tell it from the one they meant to type.
    expect(root.textContent).not.toContain('&lt;');
    expect(root.textContent).toContain(payload);
  });
});

describe('moving a workspace', () => {
  it('sends the directory that was typed and re-reads the listing', async () => {
    // ProjectView warns that a console filling an edit form from a listing
    // and posting it back would write this server's own paths into the row.
    // moveWorkspace keeps the row's exclusions in one statement, which is
    // why it is the only verb this screen offers.
    await screen.load();
    const where = root.querySelector(
      '[data-input="workspace"]',
    ) as HTMLInputElement;
    where.value = '  /srv/checkouts/payments-next  ';
    get.mockClear();

    (root.querySelector('button.move') as HTMLButtonElement).click();
    await vi.waitFor(() =>
      expect(root.textContent).toContain('/srv/checkouts/payments-next'),
    );

    expect(post).toHaveBeenCalledWith('/v1/projects/payments/workspace', {
      workspace: '/srv/checkouts/payments-next',
    });
    expect(get).toHaveBeenCalledWith('/v1/projects');
    expect(post).toHaveBeenCalledTimes(1);
  });

  it('escapes the project name into the path rather than into the URL’s shape', async () => {
    server.projects = [project({ name: 'a/b?c' })];
    await screen.load();
    const where = root.querySelector(
      '[data-input="workspace"]',
    ) as HTMLInputElement;
    where.value = '/srv/x';

    (root.querySelector('button.move') as HTMLButtonElement).click();
    await vi.waitFor(() => expect(post).toHaveBeenCalled());

    expect(post).toHaveBeenCalledWith('/v1/projects/a%2Fb%3Fc/workspace', {
      workspace: '/srv/x',
    });
  });

  it('sends nothing at all for an empty directory', async () => {
    await screen.load();

    (root.querySelector('button.move') as HTMLButtonElement).click();

    expect(post).not.toHaveBeenCalled();
    expect(root.querySelector('[data-trouble]')?.textContent).toContain(
      'Nothing was sent',
    );
  });
});

describe('approved commands', () => {
  const standing = {
    id: 'apr_9',
    conversation: 'conv-a',
    agent: 'builder',
    side: 'server',
    command: ['./gradlew', 'test', '--tests', 'Foo'],
    cwd: '/repo',
    reason: null,
    state: 'allowed',
    scope: 'project',
    prefix: ['./gradlew', 'test'],
    defaultPrefix: ['./gradlew', 'test'],
    createdAt: '2026-09-15T10:00:00Z',
    answeredAt: '2026-09-15T10:01:00Z',
  };

  /** A socket answering the two frames this screen sends, from a list a revoke empties. */
  function socket(state: StreamStatus['state'] = 'open') {
    let listed = [standing];
    let onStatus: ((status: StreamStatus) => void) | undefined;
    let current = state;
    const ask = vi.fn(
      async (type: string, payload?: unknown): Promise<FrameOutcome> => {
        if (type === 'approval.list') {
          return { code: 'OK', payload: { approvals: listed } };
        }
        if (type === 'approval.revoke') {
          const id = (payload as { id: string }).id;
          listed = listed.filter((one) => one.id !== id);
          return { code: 'OK', payload: { id, revoked: true } };
        }
        return { code: 'NOT_FOUND' };
      },
    );
    const open = (options: EventStreamOptions): EventStream => {
      onStatus = options.onStatus;
      return {
        status: (): StreamStatus => ({
          state: current,
          attempt: 0,
          retryInMs: null,
        }),
        close: () => {},
        ask,
      };
    };
    const opens = (): void => {
      current = 'open';
      onStatus?.({ state: 'open', attempt: 0, retryInMs: null });
    };
    return { ask, open, opens };
  }

  it('lists the project’s standing approvals over the socket, as the prefix they allow', async () => {
    const fake = socket();
    screen = createProjects({
      root,
      transport: transport(),
      openStream: fake.open,
      session: 's',
    });
    await screen.load();

    await vi.waitFor(() =>
      expect(root.querySelector('[data-approval="apr_9"]')).not.toBeNull(),
    );
    expect(fake.ask).toHaveBeenCalledWith('approval.list', {
      project: 'payments',
    });
    expect(
      root.querySelector('[data-approval="apr_9"] .approved-prefix')
        ?.textContent,
    ).toBe('./gradlew test');
  });

  it('revokes one with approval.revoke and re-reads the list', async () => {
    const fake = socket();
    screen = createProjects({
      root,
      transport: transport(),
      openStream: fake.open,
      session: 's',
    });
    await screen.load();
    await vi.waitFor(() =>
      expect(root.querySelector('[data-revoke="apr_9"]')).not.toBeNull(),
    );

    (root.querySelector('[data-revoke="apr_9"]') as HTMLButtonElement).click();

    expect(fake.ask).toHaveBeenCalledWith('approval.revoke', { id: 'apr_9' });
    await vi.waitFor(() =>
      expect(root.querySelector('[data-approval="apr_9"]')).toBeNull(),
    );
    expect(
      root.querySelector('[data-approved="payments"] [data-empty]')
        ?.textContent,
    ).toBe(NOTHING_APPROVED);
  });

  it('waits for a connecting socket rather than showing trouble, and lists once it opens', async () => {
    const fake = socket('connecting');
    screen = createProjects({
      root,
      transport: transport(),
      openStream: fake.open,
      session: 's',
    });
    await screen.load();

    expect(
      root.querySelector('[data-approved] [data-connecting]'),
    ).not.toBeNull();
    expect(fake.ask).not.toHaveBeenCalled();

    fake.opens();
    await vi.waitFor(() =>
      expect(root.querySelector('[data-approval="apr_9"]')).not.toBeNull(),
    );
  });

  it('draws no list at all for a screen with no socket', async () => {
    await screen.load();

    expect(root.querySelector('[data-approved]')).toBeNull();
  });
});
