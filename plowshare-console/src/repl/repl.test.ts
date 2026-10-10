import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import type {
  EventStream,
  EventStreamOptions,
  FrameOutcome,
  StreamStatus,
} from '../events';
import { createRepl, type Repl, type Transport } from './repl';
import type {
  AgentView,
  CompactionView,
  ConversationView,
  JobEventFrame,
  JobView,
  TurnView,
} from './wire';

/** What the fake server holds. Every field is what one endpoint answers. */
interface Fixture {
  agents: AgentView[];
  conversations: ConversationView[];
  turns: TurnView[];
  compactions: CompactionView[];
  job: JobView | null;
}

let server: Fixture;
let root: HTMLElement;
let repl: Repl;
let listener: ((event: unknown) => void) | null;
let closed: boolean;
let get: ReturnType<typeof vi.fn>;
let post: ReturnType<typeof vi.fn>;
/** What the last grant carried, so a test can read the body rather than a call. */
let granted: unknown;
/** What the socket answers a request frame with. Rejects unless a test says otherwise. */
let asker: ReturnType<typeof vi.fn>;

function turn(over: Partial<TurnView>): TurnView {
  return {
    ordinal: 1,
    utterance: 'a question',
    answer: 'an answer',
    ending: 'ANSWERED',
    promptTokens: 12,
    ...over,
  };
}

function job(over: Partial<JobView>): JobView {
  return {
    id: 'job_000001',
    agent: 'interlocutor',
    state: 'DONE',
    cancelRequested: false,
    conversation: null,
    outcome: null,
    limits: {
      maxTurns: 12,
      noTurnCap: false,
      maxModelCalls: 20,
      noBudget: false,
      modelCallsSpent: 3,
    },
    ...over,
  };
}

/**
 * A run that stopped at the conversation's budget, with the limits the server
 * would actually report for one.
 *
 * `modelCallsSpent === maxModelCalls` is not decoration: a `CALL_BUDGET` ending
 * *means* the allowance is gone, so any other pair here is a state no server
 * emits, and a test written over one proves nothing about the arithmetic the
 * grant does. The `CALL_BUDGET` test used to run against the default fixture --
 * 20 allowed, 3 spent -- where every wrong answer happens to be a positive
 * number and the assertion was `toBeDefined()`.
 */
function stoppedAtItsBudget(allowance: number): JobView {
  return job({
    limits: {
      maxTurns: 12,
      noTurnCap: false,
      maxModelCalls: allowance,
      noBudget: false,
      modelCallsSpent: allowance,
    },
    outcome: {
      ending: 'CALL_BUDGET',
      resumable: true,
      answered: false,
      text: '',
      steps: 12,
      modelCalls: allowance,
      detail: '',
    },
  });
}

/**
 * A finished run, not offered for continuing.
 *
 * `resumable` is the server's bit and false is the ordinary value of it: an
 * answered run has nothing to continue, and the two endings that do are named
 * by the tests that use them. Nothing here reads the ending to decide it, which
 * is the whole point of the field.
 */
function finished(ending: string, text: string, modelCalls = 1): JobView {
  return job({
    outcome: {
      ending,
      answered: ending === 'ANSWERED',
      resumable: false,
      text,
      steps: 1,
      modelCalls,
      detail: '',
    },
  });
}

/** A finished run the server says a person should be offered a grant on. */
function offered(ending: string, text: string): JobView {
  return job({
    outcome: {
      ending,
      answered: false,
      resumable: true,
      text,
      steps: 12,
      modelCalls: 12,
      detail: '',
    },
  });
}

function frame(over: Partial<JobEventFrame>): JobEventFrame {
  return {
    job: 'job_000001',
    kind: 'started',
    agent: 'interlocutor',
    tool: null,
    ending: null,
    steps: 0,
    modelCalls: 0,
    ...over,
  };
}

/** The transport, routed by path. Nothing here touches the network. */
function transport(): Transport {
  get = vi.fn(async (path: string): Promise<unknown> => {
    if (path.startsWith('/v1/agents?') || path === '/v1/agents') {
      return server.agents;
    }
    if (path.startsWith('/v1/conversations?') || path === '/v1/conversations') {
      return server.conversations;
    }
    if (path.endsWith('/turns')) {
      return server.turns;
    }
    if (path.endsWith('/compactions')) {
      return server.compactions;
    }
    if (path.startsWith('/v1/jobs/')) {
      if (server.job === null) {
        throw new ApiError(`${path} answered 404`, 404);
      }
      return server.job;
    }
    throw new ApiError(`${path} answered 404`, 404);
  });
  post = vi.fn(async (path: string, payload?: unknown): Promise<unknown> => {
    if (path === '/v1/conversations') {
      // The allowance is the server's now, and this fixture answers with
      // one the request did not carry -- which is exactly what the real
      // endpoint does with `plowshare.conversations.default-budget`.
      const opened: ConversationView = {
        id: 'conv-new',
        project: null,
        maxModelCalls: 240,
        modelCallsSpent: 0,
      };
      server.conversations = [...server.conversations, opened];
      return opened;
    }
    if (path.startsWith('/v1/agents/') && path.endsWith('/runs')) {
      return { id: 'job_000001', agent: 'interlocutor' };
    }
    if (path.endsWith('/resume')) {
      granted = payload;
      return { id: 'job_000002', agent: 'interlocutor' };
    }
    throw new ApiError(`${path} answered 404`, 404);
  });
  return { get, post } as unknown as Transport;
}

/** The status handler the REPL subscribed with, so a test can open the socket late. */
let watcher: ((status: StreamStatus) => void) | undefined;

function stream(options: EventStreamOptions): EventStream {
  listener = options.onEvent;
  watcher = options.onStatus;
  return {
    status: (): StreamStatus => ({
      state: 'open',
      attempt: 0,
      retryInMs: null,
    }),
    close: (): void => {
      closed = true;
    },
    ask: (type: string, payload?: unknown) =>
      asker(type, payload) as Promise<FrameOutcome>,
  };
}

function build(): Repl {
  return createRepl({
    root,
    transport: transport(),
    openStream: stream,
    session: 'session-under-test',
    // No poll: a suite that let a timer run would be a suite whose failures
    // depend on how long a machine took. Every test drives `reconcile`.
    pollMs: null,
  });
}

/** Every scrollback line, in order, as role and text. */
function lines(): { role: string | null; text: string }[] {
  return [...root.querySelectorAll('.scrollback .entry')].map((node) => ({
    role: node.getAttribute('data-role'),
    text: node.textContent ?? '',
  }));
}

/** The grant control, by the attribute it is found by rather than by its words. */
function grant(): HTMLButtonElement {
  return root.querySelector('[data-grant]') as HTMLButtonElement;
}

function finish(): HTMLButtonElement {
  return root.querySelector('[data-finish]') as HTMLButtonElement;
}

function said(role: string): string {
  return lines()
    .filter((line) => line.role === role)
    .map((line) => line.text)
    .join('\n');
}

beforeEach(() => {
  server = {
    agents: [
      {
        name: 'code_reviewer',
        tools: [],
        calls: [],
        scopes: [],
        served: true,
        withheld: [],
      },
      {
        name: 'interlocutor',
        tools: [],
        calls: [],
        scopes: [],
        served: true,
        withheld: [],
      },
    ],
    conversations: [
      { id: 'conv-a', project: null, maxModelCalls: 20, modelCallsSpent: 3 },
    ],
    turns: [],
    compactions: [],
    job: null,
  };
  root = document.createElement('main');
  document.body.replaceChildren(root);
  listener = null;
  closed = false;
  granted = null;
  asker = vi.fn(() => Promise.reject(new Error('not in this test')));
  repl = build();
});

afterEach(() => {
  repl.destroy();
});

describe('resuming a conversation', () => {
  it('renders the history from the server, so a reload is not amnesia', async () => {
    // The whole reason GET /v1/conversations/{id}/turns exists: a terminal
    // keeps its own scrollback and a reloaded browser tab has none, so
    // without this a refresh shows an empty conversation while the server
    // goes on holding the whole of it.
    server.turns = [
      turn({ ordinal: 1, utterance: 'first question', answer: 'first answer' }),
      turn({
        ordinal: 2,
        utterance: 'second question',
        answer: 'second answer',
      }),
    ];

    await repl.resume('conv-a');

    expect(root.textContent).toContain('first question');
    expect(root.textContent).toContain('second answer');
    expect(get).toHaveBeenCalledWith('/v1/conversations/conv-a/turns');
    expect(get).toHaveBeenCalledWith('/v1/conversations/conv-a/compactions');
  });

  it('renders the compaction seam where the history was folded', async () => {
    server.turns = [turn({ ordinal: 1 }), turn({ ordinal: 2 })];
    server.compactions = [{ throughOrdinal: 1, summary: 'the fold' }];

    await repl.resume('conv-a');

    const seam = root.querySelector('[data-seam]');
    expect(seam).not.toBeNull();
    expect(seam?.getAttribute('data-seam')).toBe('1');
    expect(said('seam')).toContain('the fold');
  });

  it('escapes nothing into markup on the way in', async () => {
    server.turns = [
      turn({ answer: 'the file said <img src=x onerror=alert(1)>' }),
    ];

    await repl.resume('conv-a');

    expect(root.querySelector('img')).toBeNull();
    expect(root.textContent).toContain('<img src=x onerror=alert(1)>');
  });

  it('opens the prompt only once there is a conversation to say something into', async () => {
    const input = root.querySelector('textarea') as HTMLTextAreaElement;
    expect(input.disabled).toBe(true);

    await repl.resume('conv-a');

    expect(input.disabled).toBe(false);
  });
});

describe('the agent picker', () => {
  it('lists what the server runs and lands on the default', async () => {
    await repl.start();

    const picker = root.querySelector('[data-agents]') as HTMLSelectElement;
    expect([...picker.options].map((option) => option.value)).toEqual([
      'code_reviewer',
      'interlocutor',
    ]);
    expect(picker.value).toBe('interlocutor');
  });

  // GET /v1/agents answers per project since task 10, on the same
  // DefinitionResolver.forCaller a run already resolves through -- so a
  // console scoped to a project has to ask scoped, the same way it already
  // does for GET /v1/conversations, or it offers a picker built from the
  // boot set while a run into that project resolves something wider.
  it('scopes the listing to the project this console is opened in', async () => {
    const scoped = createRepl({
      root,
      transport: transport(),
      openStream: stream,
      session: 'session-under-test',
      project: 'payments',
      pollMs: null,
    });

    await scoped.start();

    expect(get).toHaveBeenCalledWith('/v1/agents?project=payments');
    scoped.destroy();
  });

  it('falls back to whatever this server has when the default is not among them', async () => {
    server.agents = [
      {
        name: 'code_reviewer',
        tools: [],
        calls: [],
        scopes: [],
        served: true,
        withheld: [],
      },
    ];

    await repl.start();

    expect(
      (root.querySelector('[data-agents]') as HTMLSelectElement).value,
    ).toBe('code_reviewer');
  });

  // A disabled agent is on the list on purpose -- the server sends it rather
  // than dropping it, because an agent that silently ceases to exist is met at
  // first use with no explanation. What the picker owes it is a row that says
  // so and cannot be chosen: selecting it would post a turn that comes back
  // 400, which is the same silence one screen later.
  it('shows a disabled agent, says why, and will not let it be chosen', async () => {
    server.agents = [
      {
        name: 'code_reviewer',
        tools: [],
        calls: [],
        scopes: [],
        served: true,
        withheld: [],
      },
      {
        name: 'interlocutor',
        tools: [],
        calls: [],
        scopes: [],
        served: false,
        withheld: [
          "the agent file interlocutor.md lists the tool 'memory_grep'",
        ],
      },
    ];

    await repl.start();

    const picker = root.querySelector('[data-agents]') as HTMLSelectElement;
    const rows = [...picker.options];
    expect(rows.map((option) => option.value)).toEqual([
      'code_reviewer',
      'interlocutor',
    ]);
    const broken = rows[1] as HTMLOptionElement;
    expect(broken.disabled).toBe(true);
    expect(broken.textContent).toContain('disabled');
    expect(broken.title).toContain('memory_grep');
    // And the default, which is the interlocutor, does not land on it.
    expect(picker.value).toBe('code_reviewer');
  });

  // An agent that lost one item of a grant is a different state from a
  // disabled one, and the picker has to render it as one: the row is
  // selectable, the default lands on it, and what it lost is on the tooltip
  // the disabled rows already use. A bot with one unusable line in its
  // `tools:` is still a bot, which is the whole of what the drop rule buys;
  // greying it out here would spend that on the one screen a person looks at.
  it('keeps a served agent choosable and says what was taken from it', async () => {
    server.agents = [
      {
        name: 'interlocutor',
        tools: [],
        calls: [],
        scopes: [],
        served: true,
        withheld: [
          'interlocutor: project_move: the agent file interlocutor.md lists' +
            " the tool 'project_move', which is a real tool on the MCP surface",
        ],
      },
    ];

    await repl.start();

    const picker = root.querySelector('[data-agents]') as HTMLSelectElement;
    const row = picker.options[0] as HTMLOptionElement;
    expect(row.disabled).toBe(false);
    expect(row.textContent).toBe('interlocutor');
    expect(row.title).toContain('project_move');
    expect(picker.value).toBe('interlocutor');
  });

  it('submits the turn to the agent that is chosen', async () => {
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('say something');

    expect(post).toHaveBeenCalledWith('/v1/agents/interlocutor/runs', {
      task: 'say something',
      session: 'session-under-test',
      conversation: 'conv-a',
    });
  });
});

describe('a turn', () => {
  it('shows what the person typed at once, marked as the person’s', async () => {
    await repl.resume('conv-a');
    await repl.submit('what is in this workspace');

    const first = lines().find((line) =>
      line.text.includes('what is in this workspace'),
    );
    expect(first?.role).toBe('utterance');
  });

  it('renders the run’s progress as the events arrive', async () => {
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');

    listener?.(frame({ kind: 'started' }));
    listener?.(frame({ kind: 'model_call', steps: 0, modelCalls: 1 }));
    listener?.(frame({ kind: 'tool_called', tool: 'file_read' }));

    const activity = root.querySelector('[data-activity]') as HTMLElement;
    expect(activity.textContent).toContain('file_read');
    expect(activity.querySelector('[data-role="tool"]')).not.toBeNull();
    expect(activity.querySelector('[data-role="model"]')).not.toBeNull();
  });

  it('ignores an event for another job on the same session', async () => {
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');

    listener?.(
      frame({ job: 'job_000009', kind: 'tool_called', tool: 'somebody_elses' }),
    );

    expect(
      (root.querySelector('[data-activity]') as HTMLElement).textContent,
    ).not.toContain('somebody_elses');
  });

  it('ignores a frame of a kind it has never heard of', async () => {
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');

    expect(() => listener?.(frame({ kind: 'something_later' }))).not.toThrow();
    expect(() => listener?.('not an event')).not.toThrow();
    expect(() => listener?.(null)).not.toThrow();
  });

  it('reconciles against the job endpoint when the ended frame arrives', async () => {
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ answer: 'what it came to' })];
    server.job = finished('ANSWERED', 'what it came to');

    listener?.(
      frame({ kind: 'ended', ending: 'ANSWERED', steps: 1, modelCalls: 1 }),
    );
    await vi.waitFor(() =>
      expect(root.textContent).toContain('what it came to'),
    );

    expect(get).toHaveBeenCalledWith('/v1/jobs/job_000001');
  });
});

describe('the event stream is droppable, and the job endpoint is the record', () => {
  it('lands the answer for a run whose every event was dropped', async () => {
    // Deliver nothing at all, which is what a listener a burst overran
    // sees. A console that treated the stream as a log would render this
    // as a job that did nothing.
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ answer: 'it answered anyway' })];
    server.job = finished('ANSWERED', 'it answered anyway');

    await repl.reconcile();

    expect(said('answer')).toContain('it answered anyway');
  });

  it('says the stream was partial rather than leaving the gap unsaid', async () => {
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ answer: 'done' })];
    server.job = finished('ANSWERED', 'done', 4);
    listener?.(frame({ kind: 'model_call', steps: 0, modelCalls: 1 }));

    await repl.reconcile();

    const runtime = said('runtime');
    expect(runtime).toContain('GET /v1/jobs');
    expect(runtime).toContain('4 model calls');
    expect(runtime).toContain('saw 1 of them');
    // And the note is the runtime's, never dressed as something the person
    // or the agent said.
    expect(said('utterance')).not.toContain('GET /v1/jobs');
    expect(said('answer')).not.toContain('GET /v1/jobs');
  });

  it('says nothing about a gap when the stream carried the whole run', async () => {
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ answer: 'done' })];
    server.job = finished('ANSWERED', 'done', 1);
    listener?.(frame({ kind: 'model_call', steps: 0, modelCalls: 1 }));
    listener?.(
      frame({ kind: 'ended', ending: 'ANSWERED', steps: 1, modelCalls: 1 }),
    );

    await vi.waitFor(() => expect(said('answer')).toContain('done'));

    expect(said('runtime')).toBe('');
  });

  it('concludes nothing at all while the job endpoint says the run is going', async () => {
    await repl.resume('conv-a');
    await repl.submit('go');
    server.job = job({ state: 'RUNNING', outcome: null });

    await repl.reconcile();

    expect(
      (root.querySelector('textarea') as HTMLTextAreaElement).disabled,
    ).toBe(true);
    expect(said('answer')).toBe('');
  });

  it('says so when the process no longer holds the job', async () => {
    await repl.resume('conv-a');
    await repl.submit('go');
    server.job = null;

    await repl.reconcile();

    expect(said('refusal')).toContain('no longer holds that job');
    expect(
      (root.querySelector('textarea') as HTMLTextAreaElement).disabled,
    ).toBe(false);
  });
});

describe('a conversation that can take no more', () => {
  it('closes the prompt when a turn ends at the call budget', async () => {
    // CALL_BUDGET fires exactly when the shared budget is spent, and
    // Turn.speak then refuses any further utterance into that conversation.
    // A person left typing into it is typing into a refusal.
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ ending: 'CALL_BUDGET', answer: 'stopped' })];
    // The row is deliberately left reporting an allowance to spare, so that
    // the ending alone is what closes the prompt. In production the two
    // always agree -- the ending fires exactly when the shared budget is
    // gone -- and a test that let them agree here would pass on either
    // half of the reasoning.
    server.job = finished('CALL_BUDGET', 'stopped');

    await repl.reconcile();

    const input = root.querySelector('textarea') as HTMLTextAreaElement;
    expect(input.disabled).toBe(true);
    expect(root.querySelector('.hint')?.textContent).toContain(
      'spent its budget',
    );
  });

  it('closes the prompt when the row says the allowance is gone, whatever the ending', async () => {
    // The other half: a turn that answered on the last call leaves the
    // budget just as spent without ever ending CALL_BUDGET.
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ ending: 'ANSWERED' })];
    server.conversations = [
      { id: 'conv-a', project: null, maxModelCalls: 20, modelCallsSpent: 20 },
    ];
    server.job = finished('ANSWERED', 'the last thing it could say');

    await repl.reconcile();

    expect(
      (root.querySelector('textarea') as HTMLTextAreaElement).disabled,
    ).toBe(true);
  });

  it('refuses a further utterance here rather than sending one the server will refuse', async () => {
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ ending: 'CALL_BUDGET' })];
    server.job = finished('CALL_BUDGET', 'stopped');
    await repl.reconcile();
    post.mockClear();

    await repl.submit('anything else');

    expect(post).not.toHaveBeenCalled();
    expect(said('refusal')).toContain('spent its whole model-call budget');
  });

  /**
   * The case the whole refusal-body change was made for: `Turn.Refused` is a
   * 409 and it names *which* of the two situations applies. This branched on
   * the status before it looked at the sentence, so the server's answer was
   * dropped and the enumeration that settles neither was shown in its place.
   *
   * The sentence below is one `ApiExceptionHandler` really sends, and the
   * assertion is that the enumeration is *absent* as well as that the
   * sentence is there -- showing both would be the same failure, politely.
   */
  it('shows the server’s own 409 sentence rather than enumerating over it', async () => {
    await repl.resume('conv-a');
    const said409 =
      'conversation cnv_1 has spent its whole model-call budget, so it takes' +
      ' no further turns.';
    post.mockRejectedValueOnce(new ApiError(said409, 409, said409));

    await repl.submit('go');

    expect(said('refusal')).toContain(said409);
    expect(said('refusal')).not.toContain('one turn at a time');
  });

  /**
   * And the enumeration is still there for the refusal that carries no
   * sentence -- an `internal_error`, or a body from something that is not
   * this server. `said` is null for both, which is the whole discriminator;
   * the message is the synthesized one `api.ts` builds in that case.
   */
  it('enumerates the situations only when the server sent no sentence to show', async () => {
    await repl.resume('conv-a');
    post.mockRejectedValueOnce(
      new ApiError('/v1/agents/interlocutor/runs answered 409', 409),
    );

    await repl.submit('go');

    expect(said('refusal')).toContain('one turn at a time');
    expect(
      (root.querySelector('textarea') as HTMLTextAreaElement).disabled,
    ).toBe(false);
  });
});

describe('a conversation with no ceiling', () => {
  /** A lifted conversation on the wire: `maxModelCalls` is `null`, exactly as
   *  `ConversationView`'s own javadoc says a caller that invented a number
   *  for it would be inventing the one thing this design exists to refuse. */
  const LIFTED: ConversationView = {
    id: 'conv-lifted',
    project: null,
    maxModelCalls: null,
    modelCallsSpent: 5,
    noBudget: true,
  };

  it('renders no budget meter, not zero and not a total that was never chosen', async () => {
    server.conversations = [LIFTED];

    await repl.switchTo('conv-lifted');

    expect(root.querySelector('.budget')?.textContent).toBe('');
  });

  it('never closes the prompt for one, even if a run somehow ends at the call budget', async () => {
    // Turn.speak refuses an utterance only when Budget.trySpend refuses
    // a call, and a lifted budget's trySpend always succeeds -- so the
    // server should never actually send CALL_BUDGET for one. This
    // drives the defensive read anyway: `spent` is terminal once set,
    // and a console that trusted the invariant to hold everywhere else
    // rather than checking it here is exactly the substitution this
    // whole feature exists to refuse.
    server.conversations = [LIFTED];
    await repl.switchTo('conv-lifted');
    await repl.submit('go');
    server.turns = [turn({ ending: 'CALL_BUDGET', answer: 'stopped' })];
    server.job = finished('CALL_BUDGET', 'stopped');

    await repl.reconcile();

    const input = root.querySelector('textarea') as HTMLTextAreaElement;
    expect(input.disabled).toBe(false);
    expect(root.querySelector('.hint')?.textContent).not.toContain(
      'spent its budget',
    );
  });
});

describe('switching to another conversation', () => {
  it("does not carry the last conversation's spent verdict onto the next one", async () => {
    // `spent` is terminal for the conversation that set it and for no
    // other. A switch that only resumed would close the prompt over a
    // conversation with its whole allowance untouched, and say on the page
    // that this one had spent its budget -- a false statement on screen.
    server.conversations = [
      { id: 'conv-a', project: null, maxModelCalls: 20, modelCallsSpent: 3 },
      {
        id: 'conv-spent',
        project: null,
        maxModelCalls: 20,
        modelCallsSpent: 20,
      },
    ];

    await repl.switchTo('conv-spent');
    expect(
      (root.querySelector('textarea') as HTMLTextAreaElement).disabled,
    ).toBe(true);

    await repl.switchTo('conv-a');

    expect(
      (root.querySelector('textarea') as HTMLTextAreaElement).disabled,
    ).toBe(false);
    expect(root.querySelector('.hint')?.textContent).toBe('');
  });

  it('reads the budget of the conversation switched to, not the one left', async () => {
    server.conversations = [
      { id: 'conv-a', project: null, maxModelCalls: 20, modelCallsSpent: 3 },
      { id: 'conv-b', project: null, maxModelCalls: 90, modelCallsSpent: 11 },
    ];

    await repl.switchTo('conv-a');
    await repl.switchTo('conv-b');

    expect(root.querySelector('.budget')?.textContent).toContain('11/90');
  });

  it('draws no conversation chooser when it is not the thing that chooses', async () => {
    // `chat.ts` composes this REPL beside a sidebar that is the chooser for
    // the whole view. Two elements answering to [data-conversations] in one
    // view is a scoped querySelector resolving by DOM order.
    const host = document.createElement('div');
    document.body.append(host);
    const composed = createRepl({
      root: host,
      transport: transport(),
      openStream: stream,
      session: 'session-under-test',
      pollMs: null,
      ownChooser: false,
    });

    await composed.start();

    expect(host.querySelector('[data-conversations]')).toBeNull();
    // And no listing to fill it: `switchTo` reads the row it needs.
    expect(get).not.toHaveBeenCalledWith('/v1/conversations');
    composed.destroy();
  });
});

describe('opening a conversation', () => {
  it('opens one without naming an allowance, and resumes it', async () => {
    // The body carries the project and nothing else. What a conversation may
    // spend is `plowshare.conversations.default-budget`, which is an
    // operator's number set once for a deployment -- and a person opening a
    // conversation is starting to talk, not choosing a cost policy.
    const id = await repl.open();

    expect(post).toHaveBeenCalledWith('/v1/conversations', { project: null });
    expect(id).toBe('conv-new');
    expect(get).toHaveBeenCalledWith('/v1/conversations/conv-new/turns');
  });

  it('opens one from the button with nothing to fill in first', () => {
    const button = [...root.querySelectorAll('button')].find(
      (one) => one.textContent === 'open a conversation',
    ) as HTMLButtonElement;

    button.click();

    expect(post).toHaveBeenCalledWith('/v1/conversations', { project: null });
    expect(said('refusal')).toBe('');
  });

  it('puts no number field in front of a person anywhere on the screen', () => {
    // The field this asserts the absence of asked for model calls before the
    // button would do anything. The judgement recorded against it: "from a
    // user point of view it is messy and quite convoluted with no
    // explanation ... it should just inherit that from the system".
    expect(root.querySelector('input[type="number"]')).toBeNull();
  });

  it('shows the allowance the server chose, which is how a person learns it', async () => {
    await repl.open();

    expect(
      (root.querySelector('.budget') as HTMLElement).textContent,
    ).toContain('240');
  });
});

describe('a run that stopped, and the two things a person may do about it', () => {
  it('offers a grant and a finish when the server says the run is continuable', async () => {
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ ending: 'TURN_CAP', answer: 'stopped at its cap' })];
    server.job = offered('TURN_CAP', 'stopped at its cap');

    await repl.reconcile();

    const offer = root.querySelector('[data-offer]') as HTMLElement;
    expect(offer).not.toBeNull();
    expect(offer.hidden).toBe(false);
    expect(offer.textContent).toContain('12 more turns');
    expect(offer.textContent).toContain('finish');
  });

  it('offers nothing when the server does not, whatever the ending is called', async () => {
    // CANCELLED is continuable on request and never suggested, and this
    // console does not know that -- it reads the bit. A test that asserted
    // on the ending name here would be the copy of the server's list this
    // field exists to prevent.
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ ending: 'CANCELLED', answer: 'cancelled' })];
    server.job = finished('CANCELLED', 'cancelled');

    await repl.reconcile();

    expect((root.querySelector('[data-offer]') as HTMLElement).hidden).toBe(
      true,
    );
  });

  it('offers only "finish" for a resumable ending this console does not recognise', async () => {
    // TURN_CAP and CALL_BUDGET are the only two endings Turn.OFFERED sends
    // today, so this is inert in production -- but the server is free to add
    // a third, and outcome.resumable saying "yes, offer" does not by itself
    // say which field a grant would carry for it. 'SOME_FUTURE_ENDING' is an
    // obviously-fictional name standing in for whatever that ending turns
    // out to be; a real server ending here would make this the same copy of
    // the server's list that considerOffer's own javadoc refuses to keep.
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ ending: 'SOME_FUTURE_ENDING', answer: 'stopped' })];
    server.job = offered('SOME_FUTURE_ENDING', 'stopped');

    await repl.reconcile();

    expect((root.querySelector('[data-offer]') as HTMLElement).hidden).toBe(
      false,
    );
    // The grant control is withheld -- a body naming neither maxTurns nor
    // maxModelCalls would be a grant that visibly does nothing -- while
    // dismissing the offer needs no knowledge this console lacks.
    expect(grant().hidden).toBe(true);
    expect(finish().hidden).toBe(false);
  });

  it('grants turns and not model calls, and starts the run the grant returns', async () => {
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ ending: 'TURN_CAP', answer: 'stopped' })];
    server.job = offered('TURN_CAP', 'stopped');
    await repl.reconcile();

    grant().click();
    await vi.waitFor(() => expect(granted).not.toBeNull());

    expect(post).toHaveBeenCalledWith('/v1/conversations/conv-a/resume', {
      session: 'session-under-test',
      maxTurns: 12,
    });
    // No allowance in the body. Reintroducing one would undo the field this
    // screen just stopped asking for, one layer down.
    expect(granted).not.toHaveProperty('maxModelCalls');
    // And no agent: `turns.agent` records who answered, and a body naming a
    // different one is a 400 rather than a silent correction.
    expect(granted).not.toHaveProperty('agent');
    // The prompt closes again, because the conversation has a turn in flight.
    expect(
      (root.querySelector('textarea') as HTMLTextAreaElement).disabled,
    ).toBe(true);
    expect((root.querySelector('[data-offer]') as HTMLElement).hidden).toBe(
      true,
    );
  });

  it('grants model calls when the budget is what stopped the run, not turns', async () => {
    // The ending is the server's word for which number ran out; the offer has
    // to grant that one. Granting turns to a CALL_BUDGET stop buys nothing.
    //
    // resume + submit first, as every other test in this block does: reconcile
    // only polls a job this REPL itself started, so there has to be one in
    // flight (`jobId`) before setting the stopped outcome the poll answers with.
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.job = stoppedAtItsBudget(20);
    await repl.reconcile();
    (root.querySelector('[data-grant]') as HTMLButtonElement).click();
    await Promise.resolve();
    const body = post.mock.calls.at(-1)?.[1] as Record<string, unknown>;
    // 40 and not 20. `maxModelCalls` is a new TOTAL for a count the whole
    // conversation shares, not an increment on the run: sending back the 20
    // that ran out would leave 20 spent of 20 allowed, and `Turn.resume`
    // would refuse the continuation the button just promised. A grant of 20
    // has to leave 20 spendable, which is 20 on top of what was spent.
    expect(body['maxModelCalls']).toBe(40);
    expect(body['maxTurns']).toBeUndefined();
  });

  it('says how many more model calls the grant is, in the number it will buy', async () => {
    // The button names the increment and the body carries the total. A
    // button reading "grant 40 more" beside a body of 40 would be telling a
    // person they were buying twice what they get.
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.job = stoppedAtItsBudget(20);

    await repl.reconcile();

    expect(grant().textContent).toBe('grant 20 more model calls');
  });

  it('renders a stopped run in a lifted conversation without inventing a ceiling', async () => {
    // Every job in a lifted delegation tree reports `maxModelCalls: null`,
    // because JobStore shares one Budget by reference down the whole tree.
    // A TURN_CAP stop under one is still offered -- there is no allowance to
    // have run out -- and the grant is turns, which needs no budget number
    // at all.
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.job = job({
      limits: {
        maxTurns: 12,
        noTurnCap: false,
        maxModelCalls: null,
        noBudget: true,
        modelCallsSpent: 40,
      },
      outcome: {
        ending: 'TURN_CAP',
        resumable: true,
        answered: false,
        text: 'stopped',
        steps: 12,
        modelCalls: 40,
        detail: '',
      },
    });

    await repl.reconcile();

    expect(grant().hidden).toBe(false);
    expect(grant().textContent).toBe('grant 12 more turns');
    grant().click();
    await vi.waitFor(() => expect(granted).not.toBeNull());
    expect(granted).toEqual({ session: 'session-under-test', maxTurns: 12 });
  });

  it('reconciles the granted run against the job it was given', async () => {
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ ending: 'TURN_CAP', answer: 'stopped' })];
    server.job = offered('TURN_CAP', 'stopped');
    await repl.reconcile();
    grant().click();
    await vi.waitFor(() => expect(granted).not.toBeNull());
    server.turns = [
      turn({ ordinal: 2, utterance: 'go', answer: 'it got there in the end' }),
    ];
    server.job = finished('ANSWERED', 'it got there in the end');

    await repl.reconcile();

    expect(get).toHaveBeenCalledWith('/v1/jobs/job_000002');
    expect(said('answer')).toContain('it got there in the end');
  });

  it('writes nothing at all when the person finishes', async () => {
    // "Finish" is the absence of a grant. The run is already ended and
    // recorded, and making it a verb that wrote a row would invent a state
    // the log does not have.
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ ending: 'TURN_CAP', answer: 'stopped' })];
    server.job = offered('TURN_CAP', 'stopped');
    await repl.reconcile();
    post.mockClear();

    finish().click();

    expect(post).not.toHaveBeenCalled();
    expect((root.querySelector('[data-offer]') as HTMLElement).hidden).toBe(
      true,
    );
    // And the conversation is still open to be spoken into: nothing about
    // dismissing an offer closes a prompt.
    expect(
      (root.querySelector('textarea') as HTMLTextAreaElement).disabled,
    ).toBe(false);
  });

  it('says what a refused grant means without repeating a body it never had', async () => {
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ ending: 'TURN_CAP', answer: 'stopped' })];
    server.job = offered('TURN_CAP', 'stopped');
    await repl.reconcile();
    post.mockRejectedValueOnce(
      new ApiError('/v1/conversations/conv-a/resume answered 409', 409),
    );

    grant().click();
    await vi.waitFor(() => expect(said('refusal')).not.toBe(''));

    expect(said('refusal')).toContain('would not continue');
    expect(
      (root.querySelector('textarea') as HTMLTextAreaElement).disabled,
    ).toBe(false);
  });

  /**
   * And says the server's own words when it had them, for the reason the turn
   * path has the same pair: this control's refusal is a 409 too, and a 409
   * used to be answered from the enumeration whatever the server had written.
   */
  it('says what the server said about a refused grant, when the server said anything', async () => {
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.turns = [turn({ ending: 'TURN_CAP', answer: 'stopped' })];
    server.job = offered('TURN_CAP', 'stopped');
    await repl.reconcile();
    const refused =
      'conversation conv-a is already speaking a turn, so it cannot be' +
      ' granted another.';
    post.mockRejectedValueOnce(new ApiError(refused, 409, refused));

    grant().click();
    await vi.waitFor(() => expect(said('refusal')).not.toBe(''));

    expect(said('refusal')).toContain(refused);
    expect(said('refusal')).not.toContain('would not continue');
  });
});

describe('a console that is not signed in', () => {
  it('shows the transport’s own sentence rather than an empty server', async () => {
    // api.ts's SIGNED_OUT names the remedy, because the remedy is not
    // guessable: there is no login form and no password, only the line the
    // server printed. A catch that answered an empty list would render a
    // deployment with no agents in it.
    get.mockRejectedValue(
      new ApiError(
        'This console is no longer signed in. Reopen the' +
          ' bootstrap URL the server printed.',
        401,
      ),
    );

    await repl.start();

    expect(said('refusal')).toContain('bootstrap URL');
  });
});

describe('the stream’s state', () => {
  // The reconnect-announces-itself assertion that used to live here moved to
  // shell.test.ts along with the label: the rail's foot is where a person
  // reads the socket's state now, and the REPL header carries neither the
  // element nor the behaviour any more. This test stays -- it is about the
  // REPL's own socket lifecycle, not about where its state is displayed.
  it('closes the socket when the REPL is destroyed', async () => {
    await repl.start();
    repl.destroy();
    expect(closed).toBe(true);
  });
});

describe('a run that asked before running a command', () => {
  const asked = {
    id: 'apr_1',
    conversation: 'conv-a',
    agent: 'interlocutor',
    side: 'server',
    command: ['./gradlew', 'test', '--tests', 'Foo'],
    cwd: '/repo',
    reason: '',
    askedIn: 'conv-a',
    commands: null,
    judged: null,
    state: 'asked',
    scope: null,
    prefix: null,
    defaultPrefix: ['./gradlew', 'test'],
    createdAt: '2026-09-15T10:00:00Z',
    answeredAt: null,
  };

  /** The socket as the server would answer these three frames. */
  function answering(answered: Record<string, unknown>): void {
    asker = vi.fn(async (type: string): Promise<FrameOutcome> => {
      if (type === 'approval.list') {
        return { code: 'OK', payload: { approvals: [asked] } };
      }
      if (type === 'approval.answer') {
        return {
          code: 'OK',
          payload: {
            id: 'apr_1',
            state: 'allowed',
            job: null,
            busy: false,
            note: null,
            ...answered,
          },
        };
      }
      return { code: 'NOT_FOUND' };
    });
  }

  async function endAwaiting(): Promise<void> {
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('run the tests');
    server.turns = [
      turn({ answer: 'waiting for a person', ending: 'AWAITING' }),
    ];
    server.job = finished('AWAITING', 'waiting for a person');
    await repl.reconcile();
  }

  function block(): HTMLElement {
    return root.querySelector(
      '[data-approvals] [data-approval="apr_1"]',
    ) as HTMLElement;
  }

  it('lists the conversation’s open questions over the socket and draws one block each', async () => {
    answering({
      id: 'apr_1',
      state: 'allowed',
      job: 'job_000002',
      busy: false,
      note: null,
    });

    await endAwaiting();

    expect(asker).toHaveBeenCalledWith('approval.list', {
      conversation: 'conv-a',
    });
    expect(block()).not.toBeNull();
    expect(block().querySelector('[data-approval-command]')?.textContent).toBe(
      JSON.stringify([asked.command], null, 2),
    );
    expect((root.querySelector('[data-approvals]') as HTMLElement).hidden).toBe(
      false,
    );
  });

  it('asks nothing for a turn that did not end waiting', async () => {
    await repl.start();
    await repl.resume('conv-a');
    await repl.submit('go');
    server.job = finished('ANSWERED', 'done');
    await repl.reconcile();

    expect(asker).not.toHaveBeenCalled();
    expect((root.querySelector('[data-approvals]') as HTMLElement).hidden).toBe(
      true,
    );
  });

  it('sends a once answer with no prefix, and follows the job it continued', async () => {
    answering({
      id: 'apr_1',
      state: 'allowed',
      job: 'job_000002',
      busy: false,
      note: null,
    });
    await endAwaiting();
    get.mockClear();

    (
      block().querySelector('[data-decision="once"]') as HTMLButtonElement
    ).click();
    await vi.waitFor(() => expect(block().dataset['answered']).toBe('once'));

    expect(asker).toHaveBeenCalledWith('approval.answer', {
      id: 'apr_1',
      decision: 'once',
    });
    expect(
      (root.querySelector('textarea') as HTMLTextAreaElement).disabled,
    ).toBe(true);
    // Followed the way a submitted job is: the same poll of the job endpoint.
    server.job = job({ id: 'job_000002', outcome: null });
    await repl.reconcile();
    expect(get).toHaveBeenCalledWith('/v1/jobs/job_000002');
    expect(post).not.toHaveBeenCalledWith(
      expect.stringContaining('approval') as unknown,
      expect.anything(),
    );
  });

  it('sends the prefix the chips chose with a project answer', async () => {
    answering({
      id: 'apr_1',
      state: 'allowed',
      job: 'job_000002',
      busy: false,
      note: null,
    });
    await endAwaiting();

    (
      block().querySelector('[data-decision="project"]') as HTMLButtonElement
    ).click();
    (block().querySelector('[data-chip="0"]') as HTMLButtonElement).click();
    (
      block().querySelector('[data-confirm-prefix]') as HTMLButtonElement
    ).click();
    await vi.waitFor(() => expect(block().dataset['answered']).toBe('project'));

    expect(asker).toHaveBeenCalledWith('approval.answer', {
      id: 'apr_1',
      decision: 'project',
      prefix: ['./gradlew'],
    });
  });

  it('shows the server’s note when the conversation was busy, and follows nothing', async () => {
    answering({
      id: 'apr_1',
      state: 'denied',
      job: null,
      busy: true,
      note: 'conv-a already has a turn in flight',
    });
    await endAwaiting();

    (
      block().querySelector('[data-decision="deny"]') as HTMLButtonElement
    ).click();
    await vi.waitFor(() => expect(block().dataset['answered']).toBe('deny'));

    expect(block().querySelector('[data-approval-note]')?.textContent).toBe(
      'conv-a already has a turn in flight',
    );
    expect(
      (root.querySelector('textarea') as HTMLTextAreaElement).disabled,
    ).toBe(false);
  });

  it('gives the question back when the server refuses the answer', async () => {
    await endAwaiting();
    asker = vi.fn(async (): Promise<FrameOutcome> => ({
      code: 'BAD_REQUEST',
      said: 'approval apr_1 was already answered: it is allowed.',
    }));

    // The list was refused by the default asker; draw it again with this one.
    asker.mockResolvedValueOnce({
      code: 'OK',
      payload: { approvals: [asked] },
    });
    await repl.resume('conv-a');
    const once = block().querySelector(
      '[data-decision="once"]',
    ) as HTMLButtonElement;
    once.click();

    await vi.waitFor(() => expect(once.disabled).toBe(false));
    expect(block().textContent).toContain('already answered');
  });

  it('keeps a malformed successful receipt uncertain and never follows its claimed job', async () => {
    answering({ id: 'another-request', job: 'job_wrong' });
    await endAwaiting();
    const once = block().querySelector<HTMLButtonElement>(
      '[data-decision="once"]',
    )!;
    once.click();
    await vi.waitFor(() =>
      expect(block().textContent).toContain('delivery is uncertain'),
    );
    expect(once.disabled).toBe(true);
    expect(block().dataset['answered']).toBeUndefined();
    expect(
      asker.mock.calls.filter(([type]) => type === 'approval.answer'),
    ).toHaveLength(1);
    expect(root.textContent).not.toContain('job_wrong');
  });

  it('shows a question again when a conversation is opened on a turn still waiting', async () => {
    answering({
      id: 'apr_1',
      state: 'allowed',
      job: null,
      busy: false,
      note: null,
    });
    server.turns = [turn({ ending: 'AWAITING' })];
    await repl.start();

    await repl.switchTo('conv-a');

    expect(asker).toHaveBeenCalledWith('approval.list', {
      conversation: 'conv-a',
    });
    expect(block()).not.toBeNull();
  });

  it('lists a waiting turn’s questions once a socket that was still connecting opens', async () => {
    server.turns = [turn({ ending: 'AWAITING' })];
    await repl.start();
    await repl.switchTo('conv-a');
    expect(block()).toBeNull();

    answering({
      id: 'apr_1',
      state: 'allowed',
      job: null,
      busy: false,
      note: null,
    });
    watcher?.({ state: 'open', attempt: 0, retryInMs: null });

    await vi.waitFor(() => expect(block()).not.toBeNull());
  });
});
