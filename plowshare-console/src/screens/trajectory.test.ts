import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import type { ConversationView } from '../repl/wire';
import type { Screen, Transport } from './screen';
import {
  createTrajectory,
  describeKind,
  NO_AGENT_NAMED,
  NOTHING_LOGGED,
  NOT_PICKED,
  PAGE,
  projectionNote,
  PROJECTION_EMPTY,
  PROJECTION_NOTE,
  showingOf,
  TURN_BLOCK_AS_SENT,
  TURN_BLOCK_NOT_RECORDED,
  TURN_NO_UTTERANCE,
  TURN_UTTERANCE_EJECTED,
  TURN_UTTERANCE_EMPTY,
  turnSaid,
  turnTook,
  turnTotal,
  type Trajectory,
} from './trajectory';
import type {
  ContextView,
  EntryPageView,
  EntryView,
  ProjectionView,
  Unavailable,
} from './wire';

/**
 * The two readings of one conversation, and the numbers underneath them.
 *
 * <h2>What these tests are really holding down</h2>
 *
 * Three properties, and each of them is a way this screen could lie:
 *
 * - **The two tabs are two server readings and not one list filtered twice.**
 *   A chat is `pageOfProjection` and a trajectory is `pageOfLog`; their totals
 *   differ for the same conversation, and the difference is what folding and the
 *   roleless kinds took out. A screen that fetched one and filtered it in the
 *   browser would report the wrong total for whichever tab it did not fetch.
 * - **Every bound is rendered as a bound.** The page is capped, `excerpt` is cut
 *   at one cap and `toolCalls[].arguments` at another, and each capped answer
 *   carries both numbers. A screen that showed the short thing without the long
 *   number is a screen that says a page is a conversation.
 * - **Four numbers are unavailable and are rendered as their reasons.** Not as
 *   zero, not as a dash, and above all not as an estimate. The tests below look
 *   for the reason text and separately assert that no digit appears in those
 *   slots.
 */

let server: {
  conversations: ConversationView[];
  chat: EntryPageView;
  trajectory: EntryPageView;
  context: ContextView;
  /**
   * What `/projection` answers, or `null` for a deployment where the read
   * fails.
   *
   * `null` is the default, because that is the state every test written
   * before this route existed was passing against, and the screen draws that
   * failure as a note rather than a trouble. A test that wants the panel
   * says so by setting this.
   */
  projection: ProjectionView | null;
};
let asked: string[];
let root: HTMLElement;
let screen: Screen;
let get: ReturnType<typeof vi.fn>;

function entry(over: Partial<EntryView>): EntryView {
  return {
    ordinal: 1,
    turnOrdinal: 1,
    kind: 'utterance',
    excerpt: 'what a person said',
    length: 18,
    cut: false,
    ejectedAt: null,
    supersededBy: null,
    toolCallId: null,
    toolCalls: [],
    handle: null,
    recordedAt: '2026-09-01T10:00:00Z',
    tookMillis: null,
    dispatch: null,
    wireModel: null,
    completion: null,
    outcome: null,
    ...over,
  };
}

function page(
  entries: EntryView[],
  over: Partial<EntryPageView> = {},
): EntryPageView {
  return { entries, total: entries.length, offset: 0, limit: PAGE, ...over };
}

// One, not four. The other three are counted now -- on whatever basis the
// deployment can manage -- and each says which basis that was.
const WHY_NOT: Unavailable[] = [
  {
    component: 'cacheHitRate',
    reason: "This endpoint's 'usage' carries no cached_tokens field.",
  },
];

function context(over: Partial<ContextView> = {}): ContextView {
  return {
    sent: 4211,
    sentAtTurn: 6,
    turns: 8,
    turnsMeasured: 6,
    measuredTurns: [],
    systemPromptTokens: null,
    toolTokens: null,
    messageTokens: null,
    cacheHitRate: null,
    unavailable: WHY_NOT,
    prefix: {
      agent: 'interlocutor',
      model: 'gpt-oss-20b',
      systemPromptCharacters: 1720,
      toolCharacters: 6480,
      tools: [
        { name: 'file_read', characters: 910 },
        { name: 'file_write', characters: 1040 },
      ],
    },
    ...over,
  };
}

function transport(): Transport {
  get = vi.fn(async (path: string): Promise<unknown> => {
    asked.push(path);
    if (path.startsWith('/v1/conversations?') || path === '/v1/conversations') {
      return server.conversations;
    }
    if (path.includes('/chat')) {
      return server.chat;
    }
    if (path.includes('/trajectory')) {
      return server.trajectory;
    }
    if (path.includes('/context')) {
      return server.context;
    }
    if (path.includes('/projection')) {
      if (server.projection === null) {
        throw new ApiError(`${path} answered 404`, 404);
      }
      return server.projection;
    }
    throw new ApiError(`${path} answered 404`, 404);
  });
  return { get, post: vi.fn() } as unknown as Transport;
}

function build(): Screen {
  return createTrajectory({ root, transport: transport() });
}

function rows(): HTMLElement[] {
  return [...root.querySelectorAll('[data-entry]')] as HTMLElement[];
}

function row(ordinal: number): HTMLElement {
  return root.querySelector(`[data-entry="${ordinal}"]`) as HTMLElement;
}

function tab(name: string): HTMLButtonElement {
  return root.querySelector(`[data-tab="${name}"]`) as HTMLButtonElement;
}

function text(): string {
  return root.textContent ?? '';
}

beforeEach(async () => {
  asked = [];
  server = {
    conversations: [
      {
        id: 'conv-1',
        project: 'payments',
        maxModelCalls: 40,
        modelCallsSpent: 11,
      },
    ],
    chat: page(
      [
        entry({ ordinal: 1, kind: 'utterance', excerpt: 'read the ledger' }),
        entry({
          ordinal: 2,
          kind: 'answer',
          excerpt: '',
          length: 0,
          tookMillis: 1840,
          toolCalls: [
            {
              id: 'call-a',
              name: 'file_read',
              arguments: '{"path":"a.txt"}',
              length: 16,
              cut: false,
              salient: null,
              opened: null,
            },
          ],
        }),
        entry({
          ordinal: 3,
          kind: 'tool_result',
          excerpt: 'the ledger closed',
          toolCallId: 'call-a',
          handle: 'res-9',
          tookMillis: 12,
        }),
      ],
      { total: 3 },
    ),
    trajectory: page(
      [
        entry({ ordinal: 1, kind: 'utterance', excerpt: 'read the ledger' }),
        entry({
          ordinal: 2,
          kind: 'answer',
          excerpt: '',
          length: 0,
          tookMillis: 1840,
          supersededBy: 9,
          toolCalls: [
            {
              id: 'call-a',
              name: 'file_read',
              arguments: '{"path":"a.txt"}',
              length: 16,
              cut: false,
              salient: null,
              opened: null,
            },
          ],
        }),
        entry({
          ordinal: 3,
          kind: 'tool_result',
          excerpt: 'the ledger closed',
          toolCallId: 'call-a',
          handle: 'res-9',
          tookMillis: 12,
        }),
        entry({
          ordinal: 4,
          kind: 'diagnostic',
          excerpt:
            'fold fired over turns 1-4: sent 9100, added 240, working cost 3300',
          length: 66,
        }),
        entry({
          ordinal: 5,
          kind: 'attempt_failed',
          excerpt: 'the model refused',
        }),
      ],
      { total: 5 },
    ),
    context: context(),
    projection: null,
  };
  root = document.createElement('main');
  document.body.replaceChildren(root);
  screen = build();
  await screen.load();
});

describe('the two readings', () => {
  it('lands on the chat and asks the chat endpoint for it', () => {
    expect(tab('chat').getAttribute('aria-current')).toBe('page');
    expect(
      asked.some((path) => path.includes('/v1/conversations/conv-1/chat')),
    ).toBe(true);
    expect(asked.some((path) => path.includes('/trajectory'))).toBe(false);
  });

  it('asks the server again for the trajectory rather than filtering the chat', async () => {
    tab('trajectory').click();
    await vi.waitFor(() => expect(rows()).toHaveLength(5));

    expect(
      asked.some((path) =>
        path.includes('/v1/conversations/conv-1/trajectory'),
      ),
    ).toBe(true);
    // The kinds the chat drops are only reachable through the second read.
    expect(row(4).dataset['kind']).toBe('diagnostic');
    expect(row(5).dataset['kind']).toBe('attempt_failed');
  });

  it('reports each reading its own total, because they count different things', async () => {
    expect(showingOf(3, 3)).toContain('3 of 3');
    tab('trajectory').click();
    await vi.waitFor(() => expect(text()).toContain('5 of 5'));
  });

  it('marks a folded row as superseded on the trajectory, and never on the chat', async () => {
    // The chat's own rows never carry supersededBy: the fold took them out.
    expect(row(2).dataset['superseded']).toBeUndefined();

    tab('trajectory').click();
    await vi.waitFor(() => expect(row(2).dataset['superseded']).toBe('9'));
    expect(row(2).textContent).toContain('9');
  });
});

describe('the log reading, a third tab and not a third read', () => {
  it('renders the log reading as records, with state as its own column', async () => {
    server.trajectory = page([
      entry({ ordinal: 1, kind: 'tool_result', supersededBy: 4 }),
      entry({ ordinal: 2, kind: 'answer', ejectedAt: '2026-09-07T10:00:00Z' }),
    ]);
    tab('log').click();
    await vi.waitFor(() =>
      expect(root.querySelectorAll('[data-record]')).toHaveLength(2),
    );

    const records = [
      ...root.querySelectorAll('[data-record]'),
    ] as HTMLElement[];
    // Superseded names the entry that covered it: an edge, not an adjective.
    expect(records[0]?.dataset['supersededBy']).toBe('4');
    // Ejected is the one state where content is really gone.
    expect(records[1]?.dataset['ejected']).toBe('');
  });

  it('renders a kind the server did not send as an absence and not as a word', async () => {
    // The same discipline `figure()` holds for the numbers on this row.
    // Nothing validates that the body matched the type, and `textContent`
    // renders an absent field as the word "undefined" unless something
    // stops it -- in the one column a person scans for a state.
    server.trajectory = page([
      { ...entry({ ordinal: 1 }), kind: undefined } as unknown as EntryView,
    ]);
    tab('log').click();
    await vi.waitFor(() =>
      expect(root.querySelectorAll('[data-record]')).toHaveLength(1),
    );

    const kind = root.querySelector('[data-record="1"] .kind');
    expect(kind?.textContent).toBe('');
  });

  it('puts what each record says in its own column, which is what a log is for', async () => {
    // The version this replaces carried no excerpt at all -- "this tab is
    // for the state, and only the state" -- and rendered eighteen rows that
    // differed in an ordinal and nothing else.
    server.trajectory = page([
      entry({ ordinal: 1, excerpt: 'the first line\nand the second' }),
    ]);
    tab('log').click();
    await vi.waitFor(() =>
      expect(root.querySelectorAll('[data-record]')).toHaveLength(1),
    );

    const said = root.querySelector('[data-record="1"] .content');
    // One line in the column; the whole of it one click away and not lost.
    expect(said?.textContent).toBe('the first line');
    expect(
      root.querySelector('[data-record="1"] .full-body')?.textContent,
    ).toBe('the first line\nand the second');
  });

  it('says which way a textless row is textless, rather than leaving the cell blank', async () => {
    server.trajectory = page([
      entry({
        ordinal: 1,
        excerpt: '',
        toolCalls: [
          {
            id: 'c1',
            name: 'file_read',
            arguments: '{}',
            length: 2,
            cut: false,
            salient: null,
            opened: null,
          },
        ],
      }),
      entry({ ordinal: 2, excerpt: '', ejectedAt: '2026-09-07T10:00:00Z' }),
      entry({ ordinal: 3, excerpt: '' }),
    ]);
    tab('log').click();
    await vi.waitFor(() =>
      expect(root.querySelectorAll('[data-record]')).toHaveLength(3),
    );

    const said = (at: number) =>
      root.querySelector(`[data-record="${at}"] .content`)?.textContent ?? '';
    // Three different absences, and a blank cell would say the same
    // nothing for all three while reading as a rendering fault.
    expect(said(1)).toContain('file_read');
    expect(said(2)).toContain('ejected');
    expect(said(3)).toBe('no text recorded on this entry');
  });

  it('gives every record the same cells, so the columns stay a table', async () => {
    // The defect this pins: the row was built from the config screen's
    // stacked label/value field and laid sideways, so each row carried a
    // variable number of self-describing pairs and the state landed in a
    // different place on every row. A scanned column cannot move.
    server.trajectory = page([
      entry({ ordinal: 1 }),
      entry({ ordinal: 2, supersededBy: 4, ejectedAt: '2026-09-07T10:00:00Z' }),
    ]);
    tab('log').click();
    await vi.waitFor(() =>
      expect(root.querySelectorAll('[data-record]')).toHaveLength(2),
    );

    const cells = [
      'open-mark',
      'at',
      'turn',
      'kind',
      'content',
      'size',
      'state',
    ];
    for (const at of [1, 2]) {
      const row = root.querySelector(`[data-record="${at}"]`) as HTMLElement;
      expect(
        [...row.children]
          .filter((node) => node.tagName === 'SPAN')
          .map((node) => node.className),
      ).toEqual(cells);
    }
  });

  it('names the tools a record called, even when it also said something', async () => {
    // The defect: logBody named calls only in its no-text branch, so an
    // answer that spoke and then called tools showed its sentence alone --
    // the log carrying strictly less than the narrative reading beside it.
    server.trajectory = page([
      entry({
        ordinal: 1,
        kind: 'answer',
        excerpt: 'reading the ledger now',
        toolCalls: [
          {
            id: 'c1',
            name: 'file_read',
            arguments: '{}',
            length: 2,
            cut: false,
            salient: null,
            opened: null,
          },
        ],
      }),
    ]);
    tab('log').click();
    await vi.waitFor(() =>
      expect(root.querySelectorAll('[data-record]')).toHaveLength(1),
    );

    const said = root.querySelector(
      '[data-record="1"] .content',
    ) as HTMLElement;
    expect(said.textContent).toContain('reading the ledger now');
    expect(said.textContent).toContain('calls file_read');
  });

  it('says which target answered, and marks a fallback so it can be scanned for', async () => {
    server.trajectory = page([
      entry({
        ordinal: 1,
        kind: 'utterance',
        excerpt: 'find the owner of example.org',
      }),
      entry({
        ordinal: 2,
        kind: 'refusal',
        excerpt: "I'm sorry, but I can't help with that.",
        dispatch: 'primary',
        wireModel: 'openai/gpt-oss-120b',
        completion: 'refused',
      }),
      entry({
        ordinal: 3,
        kind: 'answer',
        excerpt: 'The registrant is IANA.',
        dispatch: 'fallback',
        wireModel: 'gpt-oss-20b-heretic',
        completion: 'answered',
      }),
    ]);
    tab('log').click();
    await vi.waitFor(() =>
      expect(root.querySelectorAll('[data-record]')).toHaveLength(3),
    );

    // The fallback answer names its model on the scanned line and carries a
    // dispatch marker; the utterance carries neither.
    const answer = root.querySelector('[data-record="3"]') as HTMLElement;
    expect(answer.dataset['dispatch']).toBe('fallback');
    expect(answer.querySelector('.content')?.textContent).toContain(
      'fallback answered (gpt-oss-20b-heretic)',
    );
    const utterance = root.querySelector('[data-record="1"]') as HTMLElement;
    expect(utterance.dataset['dispatch']).toBeUndefined();
    // The recorded refusal is still in the log, attributed to the model
    // that gave it, which is what makes the reroute auditable.
    const refusal = root.querySelector('[data-record="2"]') as HTMLElement;
    expect(refusal.dataset['dispatch']).toBe('primary');
    expect(refusal.querySelector('.full-meta')?.textContent).toContain(
      "agent's own model (openai/gpt-oss-120b)",
    );
  });

  it('names the call a result answers, and says when the page cannot', async () => {
    server.trajectory = page([
      entry({
        ordinal: 1,
        kind: 'answer',
        excerpt: '',
        toolCalls: [
          {
            id: 'c1',
            name: 'file_grep',
            arguments: '{}',
            length: 2,
            cut: false,
            salient: null,
            opened: null,
          },
        ],
      }),
      entry({
        ordinal: 2,
        kind: 'tool_result',
        excerpt: 'two hits',
        toolCallId: 'c1',
      }),
      // The call this answers is on a page this one does not cover.
      entry({
        ordinal: 3,
        kind: 'tool_result',
        excerpt: 'elsewhere',
        toolCallId: 'c9',
      }),
    ]);
    tab('log').click();
    await vi.waitFor(() =>
      expect(root.querySelectorAll('[data-record]')).toHaveLength(3),
    );

    expect(
      root.querySelector('[data-record="2"] .content')?.textContent,
    ).toContain('answers file_grep');
    // An unexplained id is worse than saying the pairing is off-page.
    expect(
      root.querySelector('[data-record="3"] .content')?.textContent,
    ).toContain('not on this page');
  });

  it('puts a word in the state column on every row, not only the damaged ones', async () => {
    // This column returned '' for any record nothing had happened to --
    // which is every record of a conversation that has never folded -- so
    // the one column the tab exists for was blank space, and an archive in
    // good order looked exactly like one whose state was not rendering.
    server.trajectory = page([
      entry({ ordinal: 1 }),
      entry({ ordinal: 2, supersededBy: 9 }),
    ]);
    tab('log').click();
    await vi.waitFor(() =>
      expect(root.querySelectorAll('[data-record]')).toHaveLength(2),
    );

    const state = (at: number) =>
      root.querySelector(`[data-record="${at}"] .state`) as HTMLElement;
    expect(state(1).textContent).toBe('stands');
    expect(state(1).dataset['stands']).toBe('');
    expect(state(2).textContent).toBe('folded→9');
    // The ordinary case is said quietly; only the exception is marked.
    expect(state(2).dataset['stands']).toBeUndefined();
  });

  it('shows that a record opens, and turns the marker when it does', async () => {
    // The detail was reachable only by clicking a line that gave no sign it
    // was clickable, which is the same as it not being there.
    server.trajectory = page([entry({ ordinal: 1, excerpt: 'a line' })]);
    tab('log').click();
    await vi.waitFor(() =>
      expect(root.querySelectorAll('[data-record]')).toHaveLength(1),
    );

    const row = root.querySelector('[data-record="1"]') as HTMLElement;
    const mark = row.querySelector('.open-mark') as HTMLElement;
    expect(mark.textContent).toBe('\u25B8');
    expect(row.dataset['open']).toBeUndefined();

    row.click();
    expect(row.dataset['open']).toBe('');
    expect(mark.textContent).toBe('\u25BE');
    // And the metadata the columns compress is what it opens onto.
    expect(row.querySelector('.full-meta')?.textContent).toContain('recorded');
  });

  it('leaves the trajectory instruments out of the log', async () => {
    // The strip, the assembled prompt and the token accounting are the
    // trajectory's. Drawn under the log they made it a trajectory with a
    // different table at the bottom.
    tab('trajectory').click();
    await vi.waitFor(() => expect(rows()).toHaveLength(5));
    expect((root.querySelector('.strip') as HTMLElement).hidden).toBe(false);

    tab('log').click();
    await vi.waitFor(() =>
      expect(root.querySelectorAll('[data-record]')).toHaveLength(5),
    );

    expect((root.querySelector('.strip') as HTMLElement).hidden).toBe(true);
    expect((root.querySelector('.projection') as HTMLElement).hidden).toBe(
      true,
    );
    expect((root.querySelector('[data-context]') as HTMLElement).hidden).toBe(
      true,
    );
    // And the heading stops naming the tab a person is not on.
    expect(root.querySelector('.screen-title')?.textContent).toBe('log');
  });

  it('does not ask the server again when the log tab is opened after the trajectory', async () => {
    tab('trajectory').click();
    await vi.waitFor(() => expect(rows()).toHaveLength(5));
    const before = get.mock.calls.length;

    tab('log').click();
    await Promise.resolve();

    expect(get.mock.calls.length).toBe(before);
  });

  it('reads the log from whichever of the two tabs fetched it first', async () => {
    // Opening log before trajectory is the one case that must fetch --
    // there is nowhere else the rows could come from -- and once it has,
    // opening trajectory afterwards must not ask again either.
    tab('log').click();
    await vi.waitFor(() =>
      expect(root.querySelectorAll('[data-record]')).toHaveLength(5),
    );
    const before = get.mock.calls.length;

    tab('trajectory').click();
    await Promise.resolve();

    expect(get.mock.calls.length).toBe(before);
    expect(rows()).toHaveLength(5);
  });
});

describe('what a row says about itself', () => {
  it('shows the kind as the column spells it, with a sentence for a person', () => {
    expect(row(1).dataset['kind']).toBe('utterance');
    expect(row(1).textContent).toContain('utterance');
    // The two ordinals are on the row and not only in its attributes. This
    // is a regression guard with a scar: `textOf` answers '' for anything
    // that is not a string, which is right for a text field arriving null
    // and silently renders `#` with nothing after it for a number.
    expect(row(1).textContent).toContain('#1');
    expect(row(1).textContent).toContain('turn 1');
    expect(describeKind('diagnostic')).toContain('harness');
    // A kind this build has never heard of renders as itself and not as a
    // blank: the server owns the spellings and may add one.
    expect(describeKind('teleport')).toContain('teleport');
  });

  it('renders a duration where the operation had one and an absence where not', () => {
    expect(row(2).textContent).toContain('1840');
    // Absence is never a zero: an utterance is not an operation that took
    // no time, it is one nothing measured.
    expect(row(1).textContent).not.toContain('0 ms');
    expect(row(1).textContent?.toLowerCase()).toContain('no duration');
  });

  it('pairs a tool result with the call it answers', () => {
    expect(row(2).textContent).toContain('file_read');
    expect(row(2).textContent).toContain('call-a');
    expect(row(3).dataset['answers']).toBe('call-a');
    expect(row(3).textContent).toContain('res-9');
    // The tool's name is on the call and never on the result, so the pair
    // is what supplies it -- a result read alone says only an opaque id.
    expect(row(3).dataset['answersTool']).toBe('file_read');
    expect(row(3).textContent).toContain('file_read');
  });

  it('says so when the call a result answers is not on this page', async () => {
    // Paging cuts between a call and its result, and the pairing is done
    // over the page in hand. Naming no tool would be honest; naming the
    // wrong one, or leaving the reader to assume the id was unresolvable
    // for some other reason, would not.
    server.trajectory = page(
      [
        entry({
          ordinal: 1,
          kind: 'tool_result',
          excerpt: 'the ledger closed',
          toolCallId: 'call-q',
        }),
      ],
      { total: 90, offset: 40, limit: 40 },
    );
    tab('trajectory').click();
    await vi.waitFor(() => expect(row(1).dataset['answers']).toBe('call-q'));

    expect(row(1).dataset['answersTool']).toBeUndefined();
    expect(row(1).textContent?.toLowerCase()).toContain('not on this page');
  });

  it('shows an ejected payload as ejected rather than as a blank', async () => {
    server.trajectory = page(
      [
        entry({
          ordinal: 1,
          kind: 'tool_result',
          excerpt: null,
          length: 41000,
          cut: false,
          ejectedAt: '2026-09-02T11:00:00Z',
          handle: 'res-3',
        }),
      ],
      { total: 1 },
    );
    tab('trajectory').click();
    await vi.waitFor(() =>
      expect(row(1).dataset['ejected']).toBe('2026-09-02T11:00:00Z'),
    );

    expect(row(1).textContent?.toLowerCase()).toContain('ejected');
    // And not reported as cut, which it is not: there is nothing further to
    // ask this surface for.
    expect(row(1).textContent?.toLowerCase()).not.toContain('cut off at');
  });

  it('says how much of a cut entry it is not showing', async () => {
    server.trajectory = page(
      [
        entry({
          ordinal: 1,
          kind: 'answer',
          excerpt: 'the beginning of it',
          length: 9000,
          cut: true,
        }),
      ],
      { total: 1 },
    );
    tab('trajectory').click();
    await vi.waitFor(() => expect(row(1).dataset['cut']).toBe('true'));

    expect(row(1).textContent).toContain('9000');
    expect(row(1).textContent).toContain('19');
  });

  it('does not claim this console can redeem a handle', async () => {
    // `result_read` is an agent tool and there is no HTTP route that
    // redeems a handle -- the endpoints on this surface are conversations,
    // jobs, agents, projects, memories and proposals, and none of them
    // takes one. A row that said "redeemable through the handle below"
    // would be telling a person to click something that does not exist.
    server.trajectory = page(
      [
        entry({
          ordinal: 1,
          kind: 'tool_result',
          excerpt: 'the first part of a long result',
          length: 41000,
          cut: true,
          handle: 'res-7',
        }),
      ],
      { total: 1 },
    );
    tab('trajectory').click();
    await vi.waitFor(() => expect(row(1).dataset['cut']).toBe('true'));

    expect(row(1).textContent).toContain('res-7');
    expect(row(1).textContent?.toLowerCase()).toContain('not by this console');
  });

  it('bounds a call’s arguments separately from the entry’s own text', async () => {
    server.trajectory = page(
      [
        entry({
          ordinal: 1,
          kind: 'answer',
          excerpt: 'writing the file',
          length: 16,
          cut: false,
          toolCalls: [
            {
              id: 'call-z',
              name: 'file_write',
              arguments: '{"path":"big.txt","text":"aaa',
              length: 82000,
              cut: true,
              salient: null,
              opened: null,
            },
          ],
        }),
      ],
      { total: 1 },
    );
    tab('trajectory').click();
    await vi.waitFor(() => expect(rows()).toHaveLength(1));

    const call = root.querySelector('[data-call="call-z"]') as HTMLElement;
    expect(call.dataset['cut']).toBe('true');
    expect(call.textContent).toContain('82000');
    // The entry itself was not cut, and the row must not say it was.
    expect(row(1).dataset['cut']).toBe('false');
  });

  it('lands a payload in an entry as text and never as markup', async () => {
    server.trajectory = page(
      [
        entry({
          ordinal: 1,
          kind: 'tool_result',
          excerpt: '<script>alert(1)</script>',
          toolCalls: [
            {
              id: '<b>x</b>',
              name: '<img src=x onerror=1>',
              arguments: '<i>y</i>',
              length: 8,
              cut: false,
              salient: null,
              opened: null,
            },
          ],
        }),
      ],
      { total: 1 },
    );
    tab('trajectory').click();
    await vi.waitFor(() => expect(rows()).toHaveLength(1));

    expect(root.querySelector('script')).toBeNull();
    expect(root.querySelector('img')).toBeNull();
    expect(text()).toContain('<script>alert(1)</script>');
    // And not double-encoded on the way in: a person must read what the
    // tool read off the disk, entities and all.
    expect(text()).not.toContain('&lt;');
  });
});

describe('the page is a page and says so', () => {
  it('offers the next page and steps by the limit the server used', async () => {
    server.trajectory = page([entry({ ordinal: 1 })], {
      total: 240,
      offset: 0,
      limit: 40,
    });
    tab('trajectory').click();
    await vi.waitFor(() => expect(text()).toContain('240'));

    const next = root.querySelector('[data-page="next"]') as HTMLButtonElement;
    expect(next.disabled).toBe(false);
    next.click();
    // 40 and not PAGE: the server narrowed the ask and the client pages by
    // what it used, or it steps over entries it never saw.
    await vi.waitFor(() =>
      expect(asked.some((path) => path.includes('offset=40'))).toBe(true),
    );
  });

  it('offers nothing further when the page is the whole of it', () => {
    const next = root.querySelector('[data-page="next"]') as HTMLButtonElement;
    expect(next.disabled).toBe(true);
    expect(showingOf(3, 3)).not.toContain('more');
  });

  it('says a search filters the page it has and not the conversation', async () => {
    const search = root.querySelector(
      '[data-input="search"]',
    ) as HTMLInputElement;
    search.value = 'ledger closed';
    search.dispatchEvent(new Event('input'));
    await vi.waitFor(() => expect(rows()).toHaveLength(1));

    expect(rows()[0]?.dataset['entry']).toBe('3');
    expect(text().toLowerCase()).toContain('this page');
  });

  it('filters by kind without asking the server for a different page', async () => {
    tab('trajectory').click();
    await vi.waitFor(() => expect(rows()).toHaveLength(5));
    const before = asked.length;
    const kinds = root.querySelector('[data-kinds]') as HTMLSelectElement;
    kinds.value = 'diagnostic';
    kinds.dispatchEvent(new Event('change'));

    await vi.waitFor(() => expect(rows()).toHaveLength(1));
    expect(rows()[0]?.dataset['kind']).toBe('diagnostic');
    expect(asked.length).toBe(before);
  });

  it('says an empty log is empty rather than drawing nothing at all', async () => {
    server.trajectory = page([], { total: 0 });
    tab('trajectory').click();
    await vi.waitFor(() =>
      expect(root.querySelector('[data-empty]')).not.toBeNull(),
    );

    expect(text()).toContain(NOTHING_LOGGED);
  });
});

describe('what the prompt cost, and how well the server knows it', () => {
  it('shows growth between measured turns, which needs no tokenizer at all', async () => {
    server.context = context({
      measuredTurns: [
        { turn: 1, promptTokens: 1200, grewBy: null, since: null },
        { turn: 2, promptTokens: 1850, grewBy: 650, since: 1 },
        { turn: 5, promptTokens: 2400, grewBy: 550, since: 2 },
      ],
    });
    screen = build();
    await screen.load();

    const steps = [...root.querySelectorAll('.growth-step')].map(
      (n) => n.textContent ?? '',
    );
    // The first measured turn has nothing to differ from, so it contributes
    // no step -- an absence, not a growth of zero.
    expect(steps).toHaveLength(2);
    expect(steps[0]).toContain('+650');
    // Turn 5 follows turn 2, so the span is said rather than assumed to be one.
    expect(steps[1]).toContain('since turn 2');
  });

  it('calls a shrinking prompt a fold, because nothing else makes one smaller', async () => {
    server.context = context({
      measuredTurns: [
        { turn: 1, promptTokens: 9000, grewBy: null, since: null },
        { turn: 2, promptTokens: 3100, grewBy: -5900, since: 1 },
      ],
    });
    screen = build();
    await screen.load();

    const step = root.querySelector('.growth-step') as HTMLElement;
    expect(step.dataset['direction']).toBe('fell');
    expect(step.textContent).toContain('fold');
  });

  it('renders a count with the basis it was arrived at on, never as a bare figure', async () => {
    // The defect this pins: an estimate shown as a plain number cannot be
    // told from a measurement, which is why these were four refusals.
    server.context = context({
      systemPromptTokens: {
        tokens: 430,
        basis: 'ESTIMATED',
        how: 'estimated at 4.00 characters per token',
      },
      toolTokens: {
        tokens: 1620,
        basis: 'MEASURED',
        how: "the model's own tokenizer",
      },
    });
    screen = build();
    await screen.load();

    const guessed = root.querySelector(
      '[data-counted="system prompt"]',
    ) as HTMLElement;
    expect(guessed.dataset['basis']).toBe('ESTIMATED');
    expect(guessed.textContent).toContain('430 tokens');
    expect(guessed.textContent).toContain('estimated');
    expect(guessed.textContent).toContain('4.00 characters per token');

    const counted = root.querySelector(
      '[data-counted="tool block"]',
    ) as HTMLElement;
    expect(counted.dataset['basis']).toBe('MEASURED');
    expect(counted.textContent).toContain('measured');
  });

  it('leaves a slot out entirely when the server sent no count for it', async () => {
    // Absence is still absence: a slot with no number is not a slot
    // holding zero, and this surface has never rendered one as the other.
    server.context = context({ systemPromptTokens: null });
    screen = build();
    await screen.load();

    expect(root.querySelector('[data-counted="system prompt"]')).toBeNull();
  });
});

describe('the economics, and what cannot be said', () => {
  it('reports what was measured, and which turn it was measured on', () => {
    const footer = root.querySelector('[data-context]') as HTMLElement;
    expect(footer.textContent).toContain('4211');
    expect(footer.textContent).toContain('6');
    expect(footer.textContent).toContain('8');
  });

  it('renders the server’s reason for each number it cannot give', () => {
    for (const why of WHY_NOT) {
      const slot = root.querySelector(
        `[data-unavailable="${why.component}"]`,
      ) as HTMLElement;
      expect(slot).not.toBeNull();
      expect(slot.textContent).toContain(why.reason);
    }
  });

  it('puts no number in an unavailable slot, and estimates neither', () => {
    for (const why of WHY_NOT) {
      const slot = root.querySelector(
        `[data-unavailable="${why.component}"]`,
      ) as HTMLElement;
      const value = slot.querySelector('.value') as HTMLElement;
      expect(value.textContent).not.toMatch(/\d/);
    }
  });

  it('reports the prefix in characters and refuses to call them tokens', () => {
    const prefix = root.querySelector('[data-prefix]') as HTMLElement;
    expect(prefix.textContent).toContain('6480');
    expect(prefix.textContent).toContain('file_read');
    expect(prefix.textContent).toContain('910');
    expect(prefix.textContent?.toLowerCase()).toContain('characters');
    expect(prefix.textContent?.toLowerCase()).not.toContain('token');
  });

  it('says a conversation that reached no model call was never measured', async () => {
    server.context = context({
      sent: null,
      sentAtTurn: null,
      turns: 1,
      turnsMeasured: 0,
    });
    await screen.load();
    const footer = root.querySelector('[data-context]') as HTMLElement;

    // Never a zero: nothing counted is not something counted and found none.
    await vi.waitFor(() =>
      expect(footer.textContent?.toLowerCase()).toContain(
        'nothing in this conversation has been measured',
      ),
    );
  });
});

describe('when the server will not answer', () => {
  it('draws the failure and does not reject the load', async () => {
    get.mockImplementation(async (path: string): Promise<unknown> => {
      if (
        path.startsWith('/v1/conversations?') ||
        path === '/v1/conversations'
      ) {
        return server.conversations;
      }
      throw new ApiError(`${path} answered 500`, 500);
    });

    await expect(screen.load()).resolves.toBeUndefined();
    expect(root.querySelector('[data-trouble]')).not.toBeNull();
  });

  it('says there is nothing to read when no conversation has been opened', async () => {
    server.conversations = [];
    asked = [];
    root = document.createElement('main');
    document.body.replaceChildren(root);
    screen = build();
    await screen.load();

    expect(root.querySelector('[data-empty]')).not.toBeNull();
    expect(asked.some((path) => path.includes('/chat'))).toBe(false);
  });
});

describe('told which conversation to read, rather than choosing one', () => {
  /**
   * A screen built the way `chat.ts` builds it: no chooser of its own, and a
   * sidebar elsewhere doing the choosing.
   */
  function composed(): Trajectory {
    asked = [];
    root = document.createElement('main');
    document.body.replaceChildren(root);
    return createTrajectory({
      root,
      transport: transport(),
      ownChooser: false,
    });
  }

  it('draws no conversation chooser of its own', async () => {
    const driven = composed();

    await driven.load();

    expect(root.querySelector('[data-conversations]')).toBeNull();
  });

  it('says nothing is picked instead of adopting the first row of a listing', async () => {
    // The failure this is here for: `load` picking `rows[0]` inside a
    // composed view draws one conversation's entries under a sidebar
    // highlighting another, with nothing on the page naming either.
    const driven = composed();

    await driven.load();

    expect(root.querySelector('[data-empty]')?.textContent).toBe(NOT_PICKED);
    expect(asked).toEqual([]);
  });

  it("reads the conversation it is told to, and drops the last one's pages", async () => {
    const driven = composed();
    await driven.load();

    await driven.show('conv-1');
    expect(
      asked.some((path) => path.includes('/v1/conversations/conv-1/chat')),
    ).toBe(true);

    server.chat = page([entry({ ordinal: 7, excerpt: 'said in conv-2' })], {
      total: 1,
    });
    await driven.show('conv-2');

    expect(
      asked.some((path) => path.includes('/v1/conversations/conv-2/chat')),
    ).toBe(true);
    expect(text()).toContain('said in conv-2');
    expect(text()).not.toContain('read the ledger');
  });
});

/**
 * A turn's summary, and the two facts it used to run together.
 *
 * The screen's own `duration` carries the rule these hold: *absence is never a
 * zero*. The turn summary broke it in both directions at once — an unmeasured
 * step was added as `0` and a total of `0` was then hidden, so a turn nobody
 * timed and a turn measured at 0 ms rendered identically, and a turn where
 * three steps of five were timed printed the three as the turn's total.
 */
describe('what a turn took', () => {
  it('adds up a turn every step of which was timed', () => {
    expect(turnTook([40, 200, 12])).toBe('3 steps · 252 ms');
    expect(turnTotal([40, 200, 12])).toBe('whole');
  });

  it('says nothing was timed rather than printing a total of nothing', () => {
    expect(turnTook([null, undefined, null])).toBe(
      '3 steps · nothing here was timed',
    );
    expect(turnTotal([null, null, null])).toBe('none');
  });

  /**
   * The measurement that proves the point: a turn really measured at 0 ms is
   * a turn somebody timed, and it must not read like the one above.
   */
  it('reports a measured zero as a measurement, because that is what it is', () => {
    expect(turnTook([0, 0])).toBe('2 steps · 0 ms');
    expect(turnTotal([0, 0])).toBe('whole');
  });

  it('says a partial total is partial, in the sentence and not only in a colour', () => {
    const said = turnTook([40, null, 12, null]);

    expect(said).toContain('52 ms');
    expect(said).toContain('2 of them that were timed');
    expect(said).toContain("not the turn's total");
    expect(turnTotal([40, null])).toBe('partial');
  });

  it('draws the state on the element, so the sheet can dim a partial', async () => {
    server.trajectory = page(
      [
        entry({
          ordinal: 1,
          turnOrdinal: 1,
          kind: 'utterance',
          excerpt: 'go on',
        }),
        entry({
          ordinal: 2,
          turnOrdinal: 1,
          kind: 'answer',
          excerpt: 'a',
          tookMillis: 90,
        }),
      ],
      { total: 2 },
    );
    tab('trajectory').click();
    await vi.waitFor(() => expect(rows()).toHaveLength(2));

    const took = root.querySelector('.turn-took') as HTMLElement;
    expect(took.dataset['total']).toBe('partial');
    expect(took.textContent).toContain("not the turn's total");
  });
});

/**
 * The line a person recognises a turn by, and why there are three answers.
 *
 * An utterance row that is not on the page and an utterance row that is on the
 * page with its payload ejected are different facts. They shared one branch,
 * and that branch's sentence asserted the first — so on an ejected
 * conversation every turn claimed to be "a continued run, or one the page
 * begins after", which is a positive false statement about each of them.
 */
describe('what a turn is recognised by', () => {
  it('says the page begins after the utterance only when there is no row', () => {
    expect(turnSaid(undefined)).toEqual({
      text: TURN_NO_UTTERANCE,
      state: 'absent',
    });
  });

  it('is the utterance itself when there is one, and states nothing', () => {
    expect(turnSaid(entry({ excerpt: 'read the ledger' }))).toEqual({
      text: 'read the ledger',
      state: null,
    });
  });

  it('says the row is here and the text is gone, for an ejected utterance', () => {
    expect(
      turnSaid(entry({ excerpt: null, ejectedAt: '2026-09-02T11:00:00Z' })),
    ).toEqual({ text: TURN_UTTERANCE_EJECTED, state: 'ejected' });
  });

  it('does not call an empty row an ejected one', () => {
    expect(turnSaid(entry({ excerpt: '', ejectedAt: null }))).toEqual({
      text: TURN_UTTERANCE_EMPTY,
      state: 'empty',
    });
  });

  it('draws an ejected turn as ejected rather than as a run that continued', async () => {
    server.trajectory = page(
      [
        entry({
          ordinal: 1,
          turnOrdinal: 1,
          kind: 'utterance',
          excerpt: null,
          ejectedAt: '2026-09-02T11:00:00Z',
          length: 44,
        }),
        entry({
          ordinal: 2,
          turnOrdinal: 1,
          kind: 'answer',
          excerpt: 'answered',
        }),
      ],
      { total: 2 },
    );
    tab('trajectory').click();
    await vi.waitFor(() => expect(rows()).toHaveLength(2));

    const said = root.querySelector('.turn-said') as HTMLElement;
    expect(said.dataset['said']).toBe('ejected');
    expect(said.textContent).toBe(TURN_UTTERANCE_EJECTED);
    expect(said.textContent).not.toContain('a continued run');
  });
});

/**
 * What the model is shown, and the two things the panel would not say.
 *
 * An empty projection rendered zero nodes — the one thing an empty answer is
 * not allowed to be, because a section that drew nothing is indistinguishable
 * from a section that failed to draw. And `ProjectionView.agent` was fetched
 * and dropped, though the field exists precisely so a reader comparing two
 * projections can tell whether they were computed against the same agent.
 */
describe('the projection', () => {
  function projection(over: Partial<ProjectionView> = {}): ProjectionView {
    return {
      agent: 'interlocutor',
      messages: [
        { role: 'system', content: 'you are an interlocutor' },
        { role: 'user', content: 'read the ledger' },
      ],
      ...over,
    };
  }

  it('names the agent it was computed against, so two readings are comparable', async () => {
    server.projection = projection();
    screen = build();

    await screen.load();

    const named = await vi.waitFor(
      () => root.querySelector('.projection-agent') as HTMLElement,
    );
    expect(named.dataset['agent']).toBe('interlocutor');
    expect(named.textContent).toContain('interlocutor');
  });

  it('says no agent was named rather than leaving the question unanswerable', async () => {
    server.projection = projection({ agent: null });
    screen = build();

    await screen.load();

    const named = await vi.waitFor(
      () => root.querySelector('.projection-agent') as HTMLElement,
    );
    expect(named.dataset['agent']).toBeUndefined();
    expect(named.textContent).toBe(NO_AGENT_NAMED);
  });

  it('renders a sentence for an empty projection and never nothing at all', async () => {
    server.projection = projection({ messages: [] });
    screen = build();

    await screen.load();

    const said = await vi.waitFor(
      () => root.querySelector('.projection [data-empty]') as HTMLElement,
    );
    expect(said.textContent).toBe(PROJECTION_EMPTY);
    expect(root.querySelector('.projection-panel')).toBeNull();
  });

  /**
   * A body that is not an array is a shape this console does not have.
   * Reading `.length` off it threw into a `.catch` that swallows, so the
   * panel went blank with no sentence anywhere saying why — the same failure
   * as the empty case and harder to see.
   */
  it('survives a messages field that is not a list, and still says something', async () => {
    server.projection = { agent: 'interlocutor', messages: null };
    screen = build();

    await screen.load();

    const said = await vi.waitFor(
      () => root.querySelector('.projection [data-empty]') as HTMLElement,
    );
    expect(said.textContent).toBe(PROJECTION_EMPTY);
  });

  it('draws the panel when there are messages, shut, with the note inside it', async () => {
    server.projection = projection();
    screen = build();

    await screen.load();

    const panel = await vi.waitFor(
      () => root.querySelector('.projection-panel') as HTMLDetailsElement,
    );
    expect(panel.open).toBe(false);
    expect(panel.textContent).toContain('2 messages');
    expect(panel.textContent).toContain('you are an interlocutor');
  });

  /**
   * Which sentence the panel draws, and the state it puts on the element.
   *
   * The panel asks for the whole conversation today, so what it draws is the
   * next-prompt note — and it draws it because the response said `turn` was
   * absent, not because of what this screen asked for. That is the property
   * worth pinning: the reading is off the answer, so a per-turn answer
   * arriving through this same panel is labelled correctly the moment one
   * does.
   *
   * And no `data-block` at all here. Both of its values are claims about a
   * turn that was sent something, and this answer is of no turn; the attribute
   * used to be written unconditionally, so the whole-conversation note was
   * painted with the per-turn "not a recording" caveat.
   */
  it('labels a next-prompt projection as the next prompt and not as a recording', async () => {
    server.projection = projection();
    screen = build();

    await screen.load();

    const note = await vi.waitFor(
      () => root.querySelector('.projection-panel .note') as HTMLElement,
    );
    expect(note.textContent).toBe(PROJECTION_NOTE);
    expect(note.dataset['block']).toBeUndefined();
  });

  /** A turn's answer does carry the state, which is what the attribute is
   *  for: the sentence and the element say the same thing, and the stylesheet
   *  draws the caveat from the second. */
  it('marks a turn whose block was not recorded on the element as well', async () => {
    server.projection = projection({ turn: 12, systemBlockAsSent: false });
    screen = build();

    await screen.load();

    const note = await vi.waitFor(
      () => root.querySelector('.projection-panel .note') as HTMLElement,
    );
    expect(note.textContent).toBe(TURN_BLOCK_NOT_RECORDED);
    expect(note.dataset['block']).toBe('not-recorded');
  });

  /** And the other value, so neither is the default the other falls back to. */
  it('marks a turn whose block was recorded as sent', async () => {
    server.projection = projection({ turn: 12, systemBlockAsSent: true });
    screen = build();

    await screen.load();

    const note = await vi.waitFor(
      () => root.querySelector('.projection-panel .note') as HTMLElement,
    );
    expect(note.textContent).toBe(TURN_BLOCK_AS_SENT);
    expect(note.dataset['block']).toBe('as-sent');
  });

  /**
   * A turn whose block was recorded, and one whose was not, get different
   * sentences — which is the whole of what the recording buys a reader.
   *
   * Driven through the pure function rather than through a fetch, because the
   * console has no control that asks for a turn yet: the route takes `?turn=`
   * and this panel does not send one. What is being held here is that the
   * three answers the route can give map to three sentences and that none of
   * them is the others, so the label is right the day the control arrives.
   */
  it('says a recorded block is the one the turn was sent', () => {
    expect(
      projectionNote(projection({ turn: 12, systemBlockAsSent: true })),
    ).toBe(TURN_BLOCK_AS_SENT);
  });

  it("says an unrecorded block is today's file and not what the turn was sent", () => {
    expect(
      projectionNote(projection({ turn: 12, systemBlockAsSent: false })),
    ).toBe(TURN_BLOCK_NOT_RECORDED);
    expect(projectionNote(projection({ turn: 12 }))).toBe(
      TURN_BLOCK_NOT_RECORDED,
    );
  });

  /**
   * The per-turn sentence is not the whole-conversation one.
   *
   * They are adjacent and they say different things: the next-prompt note is
   * about a prompt nobody has been sent, and the per-turn one is about a
   * history that IS a record with one message in it that is not. Reusing the
   * first for the second would tell a reader the whole answer is provisional,
   * which is a larger claim than the true one.
   */
  it('does not reuse the whole-conversation note for a turn', () => {
    expect(TURN_BLOCK_NOT_RECORDED).not.toBe(PROJECTION_NOTE);
    expect(TURN_BLOCK_AS_SENT).not.toBe(PROJECTION_NOTE);
    expect(TURN_BLOCK_AS_SENT).not.toBe(TURN_BLOCK_NOT_RECORDED);
  });

  /** A body with no turn is the next prompt whatever the flag says: only a
   *  past turn can have been sent anything, so a stray `true` beside no turn
   *  is a server that contradicted itself and is not read as a recording. */
  it('reads a projection with no turn as the next prompt', () => {
    expect(projectionNote(projection({ systemBlockAsSent: true }))).toBe(
      PROJECTION_NOTE,
    );
    expect(projectionNote(null)).toBe(PROJECTION_NOTE);
  });
});

/**
 * Two picks in flight, and which one the page ends up showing.
 *
 * Every read here is fired against a conversation and lands whenever the
 * server answers. Nothing ordered them, so the answer for the conversation
 * picked *first* could arrive *last* and write its projection under the second
 * one's rows — a console for auditing what a run did, quietly mixing two runs.
 */
describe('two picks racing', () => {
  it('draws the projection of the conversation last picked, not last answered', async () => {
    const held: Array<() => void> = [];
    const slow = vi.fn(async (path: string): Promise<unknown> => {
      asked.push(path);
      if (path.includes('/projection')) {
        // conv-1's answer is held open until conv-2's has landed.
        if (path.includes('conv-1')) {
          await new Promise<void>((go) => {
            held.push(go);
          });
          return {
            agent: 'first-agent',
            messages: [{ role: 'user', content: 'A' }],
          };
        }
        return {
          agent: 'second-agent',
          messages: [{ role: 'user', content: 'B' }],
        };
      }
      if (path.includes('/chat')) {
        return server.chat;
      }
      if (path.includes('/context')) {
        return server.context;
      }
      throw new ApiError(`${path} answered 404`, 404);
    });
    const driven = createTrajectory({
      root,
      transport: { get: slow, post: vi.fn() } as unknown as Transport,
      ownChooser: false,
    });
    await driven.load();

    const older = driven.show('conv-1');
    await driven.show('conv-2');
    await vi.waitFor(() =>
      expect(
        (root.querySelector('.projection-agent') as HTMLElement).dataset[
          'agent'
        ],
      ).toBe('second-agent'),
    );
    for (const go of held) {
      go();
    }
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();

    await older;
    const named = root.querySelectorAll('.projection-agent');
    expect(named).toHaveLength(1);
    expect((named[0] as HTMLElement).dataset['agent']).toBe('second-agent');
  });
});
