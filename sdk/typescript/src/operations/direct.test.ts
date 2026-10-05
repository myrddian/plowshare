import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  dispatch,
  MEMORY_OPERATIONS,
  parseDirect,
  request,
  waitForJob,
} from './direct.ts';
import type { Operation, Request } from './direct.ts';
import type { Outcome } from '../binding/envelope.ts';

function parsed(line: string, project?: string): Request {
  const value = parseDirect(line, project);
  if (value.kind !== 'request') throw new Error(JSON.stringify(value));
  return value.request;
}

const cases: readonly [string, Operation, unknown][] = [
  ['memory index', 'memory.index', {}],
  ['memory read mem_1', 'memory.read', { memory: 'mem_1' }],
  [
    'memory recall how to build?',
    'memory.recall',
    { question: 'how to build?' },
  ],
  [
    'memory write {"proposal":{"summary":"build","scope":"repo","body":"pnpm build","formedBy":"person","formedWhere":""}}',
    'memory.write',
    {
      proposal: {
        summary: 'build',
        scope: 'repo',
        body: 'pnpm build',
        formedBy: 'person',
        formedWhere: '',
      },
    },
  ],
  [
    'memory navigate how to build?',
    'memory.navigate',
    { question: 'how to build?' },
  ],
  ['memory digest', 'memory.digest', {}],
  ['memory curate {"project":"repo"}', 'agent.curate', { project: 'repo' }],
  ['memory proposals', 'proposal.list', {}],
  [
    'memory resolve {"proposal":"prp_1","accept":false,"by":"person","reason":"stale"}',
    'proposal.resolve',
    { proposal: 'prp_1', accept: false, by: 'person', reason: 'stale' },
  ],
  ['memory reconsider', 'proposal.reconsider', {}],
  [
    'memory invalidate {"memory":"mem_1","reason":"stale","by":"person"}',
    'memory.invalidate',
    { memory: 'mem_1', reason: 'stale', by: 'person' },
  ],
  ['memory reembed', 'memory.reembed', {}],
  ['search build errors', 'conversation.search', { q: 'build errors' }],
  ['job status job_1', 'job.status', { job: 'job_1' }],
  ['job cancel job_1', 'job.cancel', { job: 'job_1' }],
];

describe('direct commands use the server WS contracts', () => {
  it.each(cases)(
    '%s builds %s and sends it exactly once',
    async (line, type, payload) => {
      const asked = parsed(line);
      expect(asked).toEqual({ type, payload });
      const calls: unknown[] = [];
      const answer: Outcome = {
        code: 'NOT_FOUND',
        said: 'the server sentence',
        payload: { future: true },
      };
      const result = await dispatch(
        {
          async ask(type, payload) {
            calls.push({ type, payload });
            return answer;
          },
        },
        asked,
      );
      expect(calls).toEqual([{ type, payload }]);
      expect(result).toEqual({ kind: 'refused', outcome: answer });
      expect(result.outcome).toBe(answer);
    },
  );

  it('keeps global scope explicit and never fills server defaults', () => {
    expect(parsed('memory recall question', 'repo').payload).toEqual({
      question: 'question',
      project: 'repo',
    });
    expect(
      parsed('memory recall {"question":"question","project":null}', 'repo')
        .payload,
    ).toEqual({ question: 'question', project: null });
    expect(parsed('memory index').payload).toEqual({});
    expect(parsed('memory curate', 'repo').payload).toEqual({
      project: 'repo',
    });
    expect(
      parsed('search {"q":"build","offset":20,"limit":10}', 'repo').payload,
    ).toEqual({ q: 'build', offset: 20, limit: 10, project: 'repo' });
    expect(parsed('memory read mem_1', 'repo').payload).toEqual({
      memory: 'mem_1',
    });
  });

  it.each([
    'memory curate',
    'memory read',
    'memory recall',
    'memory nope',
    'memory write {',
    'memory index {"project":" "}',
    'memory curate {"project":null}',
    'memory resolve {"proposal":"p","by":"me"}',
    'memory resolve {"proposal":"p","accept":true}',
    'memory invalidate {"memory":"m","by":"me"}',
    'memory write {"verdict":{},"proposal":{}}',
    'memory recall {"question":"x","limit":1.5}',
    'search {"q":"x","offset":-1}',
    'job cancel',
  ])('refuses malformed input before dispatch: %s', (line) => {
    expect(parseDirect(line).kind).toBe('usage');
  });

  it('does not intercept utterances or similarly named commands', () => {
    expect(parseDirect('what does memory recall do?').kind).toBe('unhandled');
    expect(parseDirect('memoryful').kind).toBe('unhandled');
  });

  it('holds each memory discriminator to FrameTypes.java', () => {
    const java = readFileSync(
      new URL(
        '../../../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameTypes.java',
        import.meta.url,
      ),
      'utf8',
    );
    expect(Object.keys(MEMORY_OPERATIONS)).toHaveLength(12);
    for (const type of Object.values(MEMORY_OPERATIONS))
      expect(java).toContain(`"${type}"`);
  });
});

describe('dispatch preserves completion uncertainty', () => {
  async function answer(asked: Request, outcome: Outcome) {
    return dispatch(
      {
        async ask() {
          return outcome;
        },
      },
      asked,
    );
  }
  it('returns accepted handles without polling or reporting completion', async () => {
    for (const type of ['memory.digest', 'agent.curate'] as const) {
      const asked =
        type === 'memory.digest'
          ? request(type, {})
          : request(type, { project: 'repo' });
      expect(
        await answer(asked, {
          code: 'ACCEPTED',
          payload: { id: 'job_1', agent: 'curator' },
        }),
      ).toMatchObject({ kind: 'accepted', job: 'job_1' });
      expect(
        await answer(asked, { code: 'ACCEPTED', payload: { id: '' } }),
      ).toMatchObject({ kind: 'invalid-response' });
    }
    expect(
      await answer(request('memory.read', { memory: 'm' }), {
        code: 'ACCEPTED',
        payload: { id: 'job_1' },
      }),
    ).toMatchObject({ kind: 'invalid-response' });
  });
  it('preserves incomplete navigation as an OK finding with its full payload', async () => {
    const outcome: Outcome = {
      code: 'OK',
      payload: {
        level: 'lesson',
        complete: false,
        text: 'allowance spent',
        ids: ['m'],
        modelCalls: 4,
        future: 123,
      },
    };
    expect(
      await answer(request('memory.navigate', { question: 'build?' }), outcome),
    ).toEqual({ kind: 'incomplete', outcome });
    expect(
      await answer(request('memory.navigate', { question: 'build?' }), {
        code: 'OK',
        payload: {},
      }),
    ).toMatchObject({ kind: 'invalid-response' });
  });
  it('cancellation intent stays separate from a terminal outcome', async () => {
    const running: Outcome = {
      code: 'OK',
      payload: { id: 'job_1', state: 'RUNNING', cancelRequested: true },
    };
    expect(
      await answer(request('job.cancel', { job: 'job_1' }), running),
    ).toMatchObject({ kind: 'cancelling' });
    expect(
      await answer(request('job.status', { job: 'job_1' }), running),
    ).toMatchObject({ kind: 'running' });
    const terminal: Outcome = {
      code: 'OK',
      payload: {
        id: 'job_1',
        state: 'DONE',
        outcome: { ending: 'CANCELLED', answered: false },
      },
    };
    expect(
      await answer(request('job.status', { job: 'job_1' }), terminal),
    ).toMatchObject({ kind: 'completed' });
    expect(
      await answer(request('job.cancel', { job: 'job_1' }), terminal),
    ).toMatchObject({ kind: 'completed' });
    expect(
      await answer(request('job.status', { job: 'other' }), terminal),
    ).toMatchObject({ kind: 'invalid-response' });
    expect(
      await answer(request('job.status', { job: 'job_1' }), {
        code: 'OK',
        payload: { id: 'job_1', state: 'DONE' },
      }),
    ).toMatchObject({ kind: 'invalid-response' });
  });
  it('propagates connection loss with no replay or fabricated server refusal', async () => {
    let calls = 0;
    const dropped = new Error('socket closed after submission');
    await expect(
      dispatch(
        {
          async ask() {
            calls++;
            throw dropped;
          },
        },
        request('memory.digest', {}),
      ),
    ).rejects.toBe(dropped);
    expect(calls).toBe(1);
  });
});

describe('explicit shared waiting', () => {
  it('waits on matching durable status outcomes, with pacing supplied by the caller', async () => {
    const calls: unknown[] = [];
    const answers: Outcome[] = [
      { code: 'OK', payload: { id: 'j', state: 'RUNNING' } },
      {
        code: 'OK',
        payload: {
          id: 'j',
          state: 'FINISHED',
          outcome: { ending: 'ANSWERED', answered: true },
        },
      },
    ];
    const result = await waitForJob(
      {
        async ask(type, payload) {
          calls.push({ type, payload });
          return answers.shift()!;
        },
      },
      'j',
      async () => {
        calls.push('pause');
      },
    );
    expect(result.kind).toBe('completed');
    expect(calls).toEqual([
      { type: 'job.status', payload: { job: 'j' } },
      'pause',
      { type: 'job.status', payload: { job: 'j' } },
    ]);
  });
  it('stops on a refused or mismatched status rather than hiding it in endless retries', async () => {
    for (const outcome of [
      { code: 'NOT_FOUND', said: 'gone' },
      { code: 'OK', payload: { id: 'other', state: 'RUNNING' } },
    ] as const) {
      let reads = 0;
      const result = await waitForJob(
        {
          async ask() {
            reads++;
            return outcome;
          },
        },
        'j',
        async () => {
          throw new Error('must not pause');
        },
      );
      expect(['refused', 'invalid-response']).toContain(result.kind);
      expect(reads).toBe(1);
    }
  });
  it('interruption while pacing detaches without cancellation or replay', async () => {
    const types: string[] = [],
      stopped = new Error('interrupted');
    await expect(
      waitForJob(
        {
          async ask(type) {
            types.push(type);
            return { code: 'OK', payload: { id: 'j', state: 'RUNNING' } };
          },
        },
        'j',
        async () => {
          throw stopped;
        },
      ),
    ).rejects.toBe(stopped);
    expect(types).toEqual(['job.status']);
  });
});
