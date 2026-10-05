import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import type { EventStream, EventStreamOptions, StreamStatus } from '../events';
import type { JobEventFrame, JobView } from '../repl/wire';
import { describeCount } from './dom';
import { createJobs, describeState, WINDOW } from './jobs';
import type { Screen, Transport } from './screen';

let server: {
  jobs: JobView[];
  /** What `/v1/jobs/{id}/limits` refuses with, or `undefined` to accept the request. */
  refuse?: string;
};
let root: HTMLElement;
let screen: Screen;
let listener: ((event: unknown) => void) | null;
let closed: boolean;
let get: ReturnType<typeof vi.fn>;
let post: ReturnType<typeof vi.fn>;

function job(over: Partial<JobView>): JobView {
  return {
    id: 'job_000001',
    agent: 'interlocutor',
    state: 'RUNNING',
    cancelRequested: false,
    // Nothing in this task reads it; the field is here because the wire
    // carries it and this factory has to build a whole JobView.
    conversation: null,
    outcome: null,
    limits: {
      maxTurns: 16,
      noTurnCap: false,
      maxModelCalls: 40,
      noBudget: false,
      modelCallsSpent: 5,
    },
    ...over,
  };
}

function done(over: Partial<JobView> = {}): JobView {
  return job({
    state: 'DONE',
    outcome: {
      ending: 'ANSWERED',
      answered: true,
      resumable: false,
      text: 'what it came to',
      steps: 2,
      modelCalls: 5,
      detail: '',
    },
    ...over,
  });
}

/**
 * An outcome that names only `ending`/`resumable`/`answered` -- what Task 4's
 * continue control reads -- and nothing else `OutcomeView` requires.
 *
 * The cast is `absence is not zero`'s own pattern below, applied here rather
 * than repeated inline five times: `text`/`steps`/`modelCalls`/`detail` are
 * deliberately left off because nothing under test reads them, and the type
 * checker is told so once instead of once per test.
 */
function resumableOutcome(over: {
  ending: string;
  resumable: boolean;
}): JobView['outcome'] {
  return { answered: false, ...over } as unknown as JobView['outcome'] & object;
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

function transport(): Transport {
  get = vi.fn(async (path: string): Promise<unknown> => {
    if (path === '/v1/jobs') {
      return server.jobs;
    }
    throw new ApiError(`${path} answered 404`, 404);
  });
  post = vi.fn(async (path: string, payload?: unknown): Promise<unknown> => {
    const cancelled = /^\/v1\/jobs\/([^/]+)\/cancel$/.exec(path);
    if (cancelled !== null) {
      const id = decodeURIComponent(cancelled[1] as string);
      server.jobs = server.jobs.map((one) =>
        one.id === id ? { ...one, cancelRequested: true } : one,
      );
      return server.jobs.find((one) => one.id === id);
    }
    const raised = /^\/v1\/jobs\/([^/]+)\/limits$/.exec(path);
    if (raised !== null) {
      if (server.refuse !== undefined) {
        throw new ApiError(server.refuse, 400);
      }
      const id = decodeURIComponent(raised[1] as string);
      return server.jobs.find((one) => one.id === id);
    }
    if (path.startsWith('/v1/conversations/') && path.endsWith('/resume')) {
      if (server.refuse !== undefined) {
        throw new ApiError(server.refuse, 409);
      }
      return { id: 'job_resumed', agent: 'interlocutor' };
    }
    void payload;
    throw new ApiError(`${path} answered 404`, 404);
  });
  return { get, post } as unknown as Transport;
}

function stream(options: EventStreamOptions): EventStream {
  listener = options.onEvent;
  return {
    status: (): StreamStatus => ({
      state: 'open',
      attempt: 0,
      retryInMs: null,
    }),
    close: (): void => {
      closed = true;
    },
    ask: () => Promise.reject(new Error('not in this test')),
  };
}

/** Every drawn run, in the order it was drawn. */
function rows(): Element[] {
  return [...root.querySelectorAll('[data-job]')];
}

function row(id: string): Element | null {
  return rows().find((node) => node.getAttribute('data-job') === id) ?? null;
}

beforeEach(() => {
  server = { jobs: [job({})] };
  root = document.createElement('main');
  document.body.replaceChildren(root);
  listener = null;
  closed = false;
  screen = createJobs({
    root,
    transport: transport(),
    openStream: stream,
    session: 'session-under-test',
    // No poll: a suite that let a timer run would be a suite whose failures
    // depend on how long a machine took. Every test drives the read.
    pollMs: null,
  });
});

afterEach(() => {
  screen.destroy();
});

describe('the stream is droppable and the endpoint is the record', () => {
  it('lands the outcome of a run whose only frame was that it started', async () => {
    // The plan's own case: deliver `started` and then nothing at all, which
    // is what a listener a burst overran sees. A screen that treated the
    // stream as a log would render this as a run that did nothing.
    await screen.load();
    server.jobs = [
      done({
        outcome: {
          ending: 'ANSWERED',
          answered: true,
          resumable: false,
          text: 'it answered anyway',
          steps: 1,
          modelCalls: 3,
          detail: '',
        },
      }),
    ];

    listener?.(frame({ kind: 'started' }));
    await vi.waitFor(() =>
      expect(row('job_000001')?.getAttribute('data-state')).toBe('DONE'),
    );

    expect(row('job_000001')?.textContent).toContain('it answered anyway');
    expect(row('job_000001')?.textContent).toContain('3 model calls');
    expect(row('job_000001')?.querySelector('[data-outcome]')).not.toBeNull();
  });

  it('draws nothing at all from what a frame says', async () => {
    // A frame moves the poll forward and moves nothing else. Everything on
    // a row comes from GET /v1/jobs, which is what makes a dropped frame
    // cost a moment's staleness rather than a wrong row.
    await screen.load();

    listener?.(frame({ kind: 'tool_called', tool: 'file_read' }));
    listener?.(frame({ kind: 'ended', ending: 'ANSWERED' }));
    await vi.waitFor(() => expect(get.mock.calls.length).toBeGreaterThan(1));

    expect(root.textContent).not.toContain('file_read');
    // The endpoint still says it is going, so that is what the screen says.
    expect(row('job_000001')?.getAttribute('data-state')).toBe('RUNNING');
    expect(row('job_000001')?.querySelector('[data-outcome]')).toBeNull();
  });

  it('lists a run this tab was never sent a frame for', async () => {
    server.jobs = [done({ id: 'job_000007', agent: 'code_reviewer' })];

    await screen.load();

    expect(row('job_000007')?.textContent).toContain('what it came to');
    expect(listener).not.toBeNull();
  });

  it('re-reads once for a burst rather than once per frame', async () => {
    await screen.load();
    get.mockClear();

    listener?.(frame({ kind: 'started' }));
    listener?.(frame({ kind: 'model_call' }));
    listener?.(frame({ kind: 'tool_called', tool: 'file_read' }));
    listener?.(frame({ kind: 'model_call' }));
    await vi.waitFor(() => expect(get).toHaveBeenCalled());
    await vi.waitFor(() => expect(get.mock.calls.length).toBeLessThan(4));

    expect(get.mock.calls.length).toBeGreaterThan(0);
    expect(get.mock.calls.length).toBeLessThan(4);
  });

  it('ignores a frame that is not a job event', async () => {
    await screen.load();
    get.mockClear();

    expect(() => listener?.('not an event')).not.toThrow();
    expect(() => listener?.(null)).not.toThrow();
    expect(get).not.toHaveBeenCalled();
  });

  it('keeps the rows it had when the listing cannot be read', async () => {
    // An unreadable answer is not a server with no jobs on it. Emptying the
    // list here would be this screen concluding from a failure the way it
    // refuses to conclude from silence.
    await screen.load();
    get.mockRejectedValue(new ApiError('/v1/jobs answered 503', 503));

    await screen.load();

    expect(row('job_000001')).not.toBeNull();
    expect(root.querySelector('[data-trouble]')?.textContent).toContain(
      'answered 503',
    );
    expect(root.querySelector('[data-empty]')).toBeNull();
  });
});

describe('a cancel is a request and the state is not the request', () => {
  it('shows the request beside a run that is still running', async () => {
    // JobView reports cancelRequested separately because a cancelled run
    // stays RUNNING until its next turn boundary. A caller that could not
    // see the request would think its cancel had been lost.
    server.jobs = [job({ state: 'RUNNING', cancelRequested: true })];

    await screen.load();

    const card = row('job_000001');
    expect(card?.getAttribute('data-state')).toBe('RUNNING');
    expect(card?.querySelector('[data-job-state]')?.textContent).toBe(
      'running',
    );
    expect(
      card?.querySelector('[data-cancel-requested]')?.textContent,
    ).toContain('next turn boundary');
  });

  it('says nothing about a stop nobody asked for', async () => {
    await screen.load();
    expect(
      row('job_000001')?.querySelector('[data-cancel-requested]'),
    ).toBeNull();
  });

  it('asks the server to stop it and then re-reads the listing', async () => {
    await screen.load();

    (
      root.querySelector('[data-cancel="job_000001"]') as HTMLButtonElement
    ).click();
    await vi.waitFor(() =>
      expect(
        row('job_000001')?.querySelector('[data-cancel-requested]'),
      ).not.toBeNull(),
    );

    expect(post).toHaveBeenCalledWith('/v1/jobs/job_000001/cancel');
    expect(get).toHaveBeenCalledWith('/v1/jobs');
  });

  it('escapes a job id into the path rather than into the URL’s shape', async () => {
    server.jobs = [job({ id: 'a/b?c' })];
    await screen.load();

    (root.querySelector('[data-cancel]') as HTMLButtonElement).click();
    await vi.waitFor(() => expect(post).toHaveBeenCalled());

    expect(post).toHaveBeenCalledWith('/v1/jobs/a%2Fb%3Fc/cancel');
  });
});

describe('what bounds a run, and what does not', () => {
  it('shows both bounds, and says which have no ceiling', async () => {
    server.jobs = [
      job({
        limits: {
          maxTurns: null,
          noTurnCap: true,
          maxModelCalls: 40,
          noBudget: false,
          modelCallsSpent: 12,
        },
      }),
    ];
    await screen.load();
    const row = root.querySelector('[data-job]') as HTMLElement;
    expect(row.textContent).toContain('no turn cap');
    expect(row.textContent).toContain('12');
    expect(row.textContent).toContain('40');
  });

  it('never prints a ceiling that is not there', async () => {
    // The absence is a decision, not a zero: Stage 2 gave both bounds three
    // states precisely so a screen could not invent this number.
    //
    // The controller's ruling on this test: the brief's original assertion
    // was `expect(row.textContent).not.toMatch(/\b0\b/)` across the whole
    // row, and that fails on any legitimate zero in it -- a spend of 0, an
    // id with a 0 in it -- which is not the claim being guarded. What is
    // guarded is one field, the ceiling, so this asserts on that field
    // alone rather than chasing a phantom over the whole row.
    server.jobs = [
      job({
        limits: {
          maxTurns: 16,
          noTurnCap: false,
          maxModelCalls: null,
          noBudget: true,
          modelCallsSpent: 9,
        },
      }),
    ];
    await screen.load();
    const row = root.querySelector('[data-job]') as HTMLElement;
    const ceiling = row.querySelector(
      '[data-field="model call ceiling"] .value',
    );
    expect(ceiling?.textContent).toBe('no ceiling');
  });

  it('says a job has no limits on this handle rather than an empty row', async () => {
    // A curator pass carries its budget across a whole pass and has none on
    // this handle -- the third state, at the whole-`limits` level rather
    // than on either field within it.
    server.jobs = [job({ limits: null })];
    await screen.load();
    const row = root.querySelector('[data-job]') as HTMLElement;
    expect(row.textContent).toContain('no limits on this handle');
  });
});

describe("raising a running run's ceiling", () => {
  it('raises the ceiling on a running job, by the pre-filled default amount', async () => {
    // The path alone does not pin the arithmetic -- a body carrying the
    // fixed default itself, rather than the ceiling plus that default,
    // would pass a path-only assertion and still be the exact
    // transcription slip Stage 2 made once, where a grant looked like it
    // gave 20 and gave nothing. The default job's limits are `maxTurns: 16`
    // and `maxModelCalls: 40`; the pre-filled amount is 20, so a raise
    // with nothing typed asks for 36 and 60.
    server.jobs = [job({ id: 'job_1', state: 'RUNNING' })];
    await screen.load();
    (root.querySelector('[data-raise="job_1"]') as HTMLButtonElement).click();
    await Promise.resolve();
    expect(post.mock.calls.at(-1)?.[0]).toBe('/v1/jobs/job_1/limits');
    expect(post.mock.calls.at(-1)?.[1]).toEqual({
      maxTurns: 36,
      maxModelCalls: 60,
    });
  });

  it('raises the ceiling by whatever the operator typed, not the pre-filled default', async () => {
    // The owner's own instruction: a pausing condition is one "the
    // user can correct to allow more budget again", and correcting
    // means choosing the amount. This is that choice, exercised.
    server.jobs = [job({ id: 'job_1', state: 'RUNNING' })];
    await screen.load();
    const amount = root.querySelector(
      '[data-input="raise by"]',
    ) as HTMLInputElement;
    amount.value = '5';
    (root.querySelector('[data-raise="job_1"]') as HTMLButtonElement).click();
    await Promise.resolve();
    expect(post.mock.calls.at(-1)?.[0]).toBe('/v1/jobs/job_1/limits');
    expect(post.mock.calls.at(-1)?.[1]).toEqual({
      maxTurns: 21,
      maxModelCalls: 45,
    });
  });

  it('sends no request for an amount that is not a positive number', async () => {
    server.jobs = [job({ id: 'job_1', state: 'RUNNING' })];
    await screen.load();
    const amount = root.querySelector(
      '[data-input="raise by"]',
    ) as HTMLInputElement;
    post.mockClear();

    for (const bad of ['0', '-3', 'lots', '']) {
      amount.value = bad;
      (root.querySelector('[data-raise="job_1"]') as HTMLButtonElement).click();
      await Promise.resolve();
    }

    expect(post).not.toHaveBeenCalled();
    expect(
      (root.querySelector('[data-trouble]') as HTMLElement).textContent,
    ).toContain('positive whole number');
  });

  it('rejects a fraction or trailing garbage rather than silently truncating it', async () => {
    // `Number.parseInt` reads a numeric prefix and stops: `'3.7'` parses to
    // `3` and `'5abc'` parses to `5`, so a control built on it alone would
    // pass validation and send a number the operator never typed -- the
    // exact silent substitution this control exists to rule out, this time
    // built from truncated keystrokes instead of an invented default. This
    // is the reviewer's own probe: each of these must produce no POST.
    server.jobs = [job({ id: 'job_1', state: 'RUNNING' })];
    await screen.load();
    const amount = root.querySelector(
      '[data-input="raise by"]',
    ) as HTMLInputElement;
    post.mockClear();

    for (const bad of ['3.7', '5abc', '1e3']) {
      amount.value = bad;
      (root.querySelector('[data-raise="job_1"]') as HTMLButtonElement).click();
      await Promise.resolve();
    }

    expect(post).not.toHaveBeenCalled();
    expect(
      (root.querySelector('[data-trouble]') as HTMLElement).textContent,
    ).toContain('positive whole number');
  });

  it('accepts an amount with surrounding whitespace, and nothing else that is not exact', async () => {
    // The one shape of "wrong" a person plausibly produces by accident
    // rather than by typing the wrong thing at all -- a stray space bar
    // press either side of the digits.
    server.jobs = [job({ id: 'job_1', state: 'RUNNING' })];
    await screen.load();
    const amount = root.querySelector(
      '[data-input="raise by"]',
    ) as HTMLInputElement;
    amount.value = ' 7 ';
    (root.querySelector('[data-raise="job_1"]') as HTMLButtonElement).click();
    await Promise.resolve();
    expect(post.mock.calls.at(-1)?.[1]).toEqual({
      maxTurns: 23,
      maxModelCalls: 47,
    });
  });

  it('renders whatever ApiError problemText is handed, rather than inventing its own text', async () => {
    // This does not exercise the server producing that sentence -- it
    // cannot, from a fake transport, and it does not need to: `api.ts`
    // carries a refusal's own detail through now, and `api.test.ts`
    // pins that against a real `fetch` response where the behaviour
    // actually lives. What this test pins is the half that is this
    // screen's: whatever text a `.catch` is handed via `problemText`,
    // this control renders it rather than writing its own over the top.
    // The sentence below is one of the three genuine refusals this
    // endpoint makes, so the pair of tests together cover the route
    // from the server's words to the page.
    server.refuse =
      'job job_1 has already finished, so there is nothing left' +
      ' for a limit to bound. Nothing was changed.';
    server.jobs = [job({ id: 'job_1', state: 'RUNNING' })];
    await screen.load();
    (root.querySelector('[data-raise="job_1"]') as HTMLButtonElement).click();
    await Promise.resolve();
    expect(
      (root.querySelector('[data-trouble]') as HTMLElement).textContent,
    ).toContain('already finished');
  });

  it('offers nothing to raise on a run whose budget has no ceiling and no turn cap', async () => {
    // Not a client-side prediction of the server's refusal -- see
    // `jobs.ts` -- but also not an offer that could only ever be
    // refused: a run with neither bound to raise has nothing this
    // control could ask the server to move.
    server.jobs = [
      job({
        id: 'job_1',
        state: 'RUNNING',
        limits: {
          maxTurns: null,
          noTurnCap: true,
          maxModelCalls: null,
          noBudget: true,
          modelCallsSpent: 3,
        },
      }),
    ];
    await screen.load();
    expect(root.querySelector('[data-raise="job_1"]')).toBeNull();
  });

  it('raises only the turn cap on a run whose budget has no ceiling', async () => {
    // The pre-existing gap: `raisesTurns`/`raisesBudget` are independent,
    // and nothing pinned the body for a run with only one real ceiling --
    // the case most likely to break silently under a later edit, since
    // both the everything-raisable and nothing-raisable cases already had
    // coverage and this one sat between them.
    server.jobs = [
      job({
        id: 'job_1',
        state: 'RUNNING',
        limits: {
          maxTurns: 16,
          noTurnCap: false,
          maxModelCalls: null,
          noBudget: true,
          modelCallsSpent: 3,
        },
      }),
    ];
    await screen.load();
    (root.querySelector('[data-raise="job_1"]') as HTMLButtonElement).click();
    await Promise.resolve();
    expect(post.mock.calls.at(-1)?.[1]).toEqual({ maxTurns: 36 });
  });

  it('raises only the model-call budget on a run with no turn cap', async () => {
    server.jobs = [
      job({
        id: 'job_1',
        state: 'RUNNING',
        limits: {
          maxTurns: null,
          noTurnCap: true,
          maxModelCalls: 40,
          noBudget: false,
          modelCallsSpent: 3,
        },
      }),
    ];
    await screen.load();
    (root.querySelector('[data-raise="job_1"]') as HTMLButtonElement).click();
    await Promise.resolve();
    expect(post.mock.calls.at(-1)?.[1]).toEqual({ maxModelCalls: 60 });
  });

  it("offers nothing on a job that is not one agent's run", async () => {
    server.jobs = [job({ id: 'job_1', state: 'RUNNING', limits: null })];
    await screen.load();
    expect(root.querySelector('[data-raise="job_1"]')).toBeNull();
  });

  it('offers nothing on a run that has already finished', async () => {
    // The control is offered only for a RUNNING job -- the server refuses
    // otherwise, and a button that always refuses teaches people to ignore
    // refusals.
    server.jobs = [done({ id: 'job_1' })];
    await screen.load();
    expect(root.querySelector('[data-raise="job_1"]')).toBeNull();
  });
});

describe('continuing a run that stopped unattended', () => {
  it('offers to continue a stopped run the server says is resumable', async () => {
    server.jobs = [
      job({
        id: 'job_1',
        state: 'DONE',
        conversation: 'cnv_9',
        outcome: resumableOutcome({ ending: 'TURN_CAP', resumable: true }),
      }),
    ];
    await screen.load();
    (
      root.querySelector('[data-continue="job_1"]') as HTMLButtonElement
    ).click();
    await Promise.resolve();
    expect(post.mock.calls.at(-1)?.[0]).toBe('/v1/conversations/cnv_9/resume');
  });

  it('offers nothing when the server did not say resumable', async () => {
    // Which endings continue is the server's decision and the table is in
    // `Turn`. A copy here would offer a grant the server refuses.
    server.jobs = [
      job({
        id: 'job_1',
        state: 'DONE',
        conversation: 'cnv_9',
        outcome: resumableOutcome({ ending: 'STUCK', resumable: false }),
      }),
    ];
    await screen.load();
    expect(root.querySelector('[data-continue="job_1"]')).toBeNull();
  });

  it('offers nothing when the run names no conversation', async () => {
    server.jobs = [
      job({
        id: 'job_1',
        state: 'DONE',
        conversation: null,
        outcome: resumableOutcome({ ending: 'TURN_CAP', resumable: true }),
      }),
    ];
    await screen.load();
    expect(root.querySelector('[data-continue="job_1"]')).toBeNull();
  });

  it('grants the number that stopped the run, sharing the mapping repl.ts uses', async () => {
    // `grantFieldFor` in `src/grant.ts` is the shared answer to which field
    // a grant should carry for `TURN_CAP`; this pins that jobs.ts actually
    // asks it rather than holding a second copy of the same table.
    server.jobs = [
      job({
        id: 'job_1',
        state: 'DONE',
        conversation: 'cnv_9',
        outcome: resumableOutcome({ ending: 'TURN_CAP', resumable: true }),
        limits: {
          maxTurns: 16,
          noTurnCap: false,
          maxModelCalls: 40,
          noBudget: false,
          modelCallsSpent: 5,
        },
      }),
    ];
    await screen.load();
    (
      root.querySelector('[data-continue="job_1"]') as HTMLButtonElement
    ).click();
    await Promise.resolve();
    expect(post.mock.calls.at(-1)?.[1]).toEqual({
      session: 'session-under-test',
      maxTurns: 16,
    });
  });

  it('grants a new total of model calls for a run that stopped at the budget', async () => {
    server.jobs = [
      job({
        id: 'job_1',
        state: 'DONE',
        conversation: 'cnv_9',
        outcome: resumableOutcome({ ending: 'CALL_BUDGET', resumable: true }),
        limits: {
          maxTurns: 16,
          noTurnCap: false,
          maxModelCalls: 40,
          noBudget: false,
          modelCallsSpent: 40,
        },
      }),
    ];
    await screen.load();
    (
      root.querySelector('[data-continue="job_1"]') as HTMLButtonElement
    ).click();
    await Promise.resolve();
    // A budget is conversation-cumulative, so the wire wants a new total
    // and not the increment -- `repl.ts`'s own `grantTotal` discipline,
    // restated at this call site: 40 already spent plus the 40-call cap
    // that stopped the run leaves 40 more to spend rather than none.
    expect(post.mock.calls.at(-1)?.[1]).toEqual({
      session: 'session-under-test',
      maxModelCalls: 80,
    });
  });
});

describe('a state or an ending this build has never heard of', () => {
  it('renders a state as itself rather than failing over one', () => {
    expect(describeState('RUNNING')).toBe('running');
    expect(describeState('PAUSED')).toBe('PAUSED');
    expect(describeState('constructor')).toBe('constructor');
    expect(describeState(undefined)).toContain('did not name');
  });

  it('draws a run in a state and an ending this build was not written against', async () => {
    server.jobs = [
      job({
        state: 'SOMETHING_LATER',
        outcome: {
          ending: 'SOMETHING_LATER',
          answered: false,
          resumable: false,
          text: 'as far as it got',
          steps: 1,
          modelCalls: 1,
          detail: 'why',
        },
      }),
    ];

    await expect(screen.load()).resolves.toBeUndefined();

    const card = row('job_000001');
    expect(card?.getAttribute('data-state')).toBe('SOMETHING_LATER');
    expect(card?.querySelector('[data-job-state]')?.textContent).toBe(
      'SOMETHING_LATER',
    );
    expect(card?.textContent).toContain('ended SOMETHING_LATER');
    expect(card?.textContent).toContain('stopped without reaching an answer');
  });
});

describe('absence is not zero', () => {
  it('says a count was not reported rather than reporting it as none', () => {
    expect(describeCount(null, 'step', 'steps')).toBe('steps not reported');
    expect(describeCount(undefined, 'step', 'steps')).not.toContain('0');
    expect(describeCount(0, 'step', 'steps')).toBe('0 steps');
    expect(describeCount(1, 'model call', 'model calls')).toBe('1 model call');
  });

  it('draws an outcome whose counts the answer did not carry', async () => {
    server.jobs = [
      done({
        outcome: {
          ending: 'ANSWERED',
          answered: true,
          text: 'x',
          detail: '',
        } as unknown as JobView['outcome'] & object,
      }),
    ];

    await screen.load();

    const card = row('job_000001');
    expect(card?.textContent).toContain('steps not reported');
    expect(card?.textContent).toContain('model calls not reported');
    expect(card?.textContent).not.toContain('0 steps');
  });
});

describe('a list with nothing in it', () => {
  it('renders no jobs as an answer and names why a restart looks like this', async () => {
    server.jobs = [];

    await screen.load();

    const empty = root.querySelector('[data-empty]');
    expect(empty?.textContent).toContain('archive');
    expect(root.querySelector('[data-trouble]')).toBeNull();
    expect(rows()).toHaveLength(0);
  });
});

describe('a list nothing reaps', () => {
  it('draws a window of the most recent and says how many are held', async () => {
    server.jobs = Array.from({ length: WINDOW + 5 }, (_, at) =>
      done({
        id: `job_${String(at).padStart(6, '0')}`,
      }),
    );

    await screen.load();

    expect(rows()).toHaveLength(WINDOW);
    const note = root.querySelector('[data-window]') as HTMLElement;
    expect(note.dataset['held']).toBe(String(WINDOW + 5));
    expect(note.dataset['window']).toBe(String(WINDOW));
    // Newest last on the wire, newest first on the screen: this list is
    // read to see what just happened.
    expect(rows()[0]?.getAttribute('data-job')).toBe(
      `job_${String(WINDOW + 4).padStart(6, '0')}`,
    );
  });

  it('draws all of them when somebody asks', async () => {
    server.jobs = Array.from({ length: WINDOW + 5 }, (_, at) =>
      done({
        id: `job_${String(at).padStart(6, '0')}`,
      }),
    );
    await screen.load();

    (root.querySelector('button.widen') as HTMLButtonElement).click();

    expect(rows()).toHaveLength(WINDOW + 5);
    expect(
      (root.querySelector('button.widen') as HTMLButtonElement).hidden,
    ).toBe(true);
  });

  it('offers no widening when everything is already drawn', async () => {
    await screen.load();
    expect(
      (root.querySelector('button.widen') as HTMLButtonElement).hidden,
    ).toBe(true);
    expect(
      (root.querySelector('[data-window]') as HTMLElement).textContent,
    ).toContain('all drawn');
  });
});

describe('rendering is escaping', () => {
  it('lands a payload in an agent name and in an outcome in the DOM as text', async () => {
    const payload = '<img src=x onerror=alert(1)>';
    server.jobs = [
      done({
        agent: payload,
        outcome: {
          ending: 'ANSWERED',
          answered: true,
          resumable: false,
          text: `the file said ${payload}`,
          steps: 1,
          modelCalls: 1,
          detail: payload,
        },
      }),
    ];

    await screen.load();

    expect(root.querySelector('img')).toBeNull();
    expect(row('job_000001')?.querySelector('.agent')?.textContent).toBe(
      payload,
    );
    expect(
      row('job_000001')?.querySelector('[data-outcome] .body')?.textContent,
    ).toBe(`the file said ${payload}`);
    // And not double-encoded: the person must read what the run said,
    // entities and all.
    expect(root.textContent).not.toContain('&lt;');
  });
});

/**
 * What this screen does with the socket, and what it deliberately does not.
 *
 * The reconnect assertion that used to live here has moved to `shell.test.ts`:
 * the socket belongs to the tab rather than to this screen -- `multiplex` owns
 * it and `chat` reads the same one -- so its state is said once, at the rail's
 * foot. What stays here is this screen's own half of it.
 */
describe('the stream’s state', () => {
  it("says nothing about the socket, which is the rail's to report", async () => {
    await screen.load();

    expect(root.querySelector('[data-stream]')).toBeNull();
  });

  it('closes the socket when the screen is destroyed', async () => {
    await screen.load();
    screen.destroy();
    expect(closed).toBe(true);
  });
});
