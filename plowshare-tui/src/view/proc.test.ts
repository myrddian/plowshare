import { expect, it } from 'vitest';
import { ProcClient } from 'plowshare-client-ts/operations/proc';
import type {
  ProcConversation,
  ProcReplies,
  ProcOperation,
} from 'plowshare-client-ts/operations/proc';
import { exploreProc } from './proc.ts';
import { typed } from '../logic/session.ts';
import type { QuestionStroke } from '../logic/questions.ts';

it('routes PROC locally and allows selecting a completed internal conversation without model work', async () => {
  expect(typed('/proc')).toEqual({ kind: 'proc', command: null });
  const row: ProcConversation = {
    id: 'cnv_fixture',
    root: 'cnv_root',
    parent: 'cnv_root',
    origin: 'delegation',
    lifecycle: 'active',
    project: 'research',
    owner: 'alice',
    application: null,
    actor: null,
    actingSubject: null,
    agent: 'information_tagger',
    principal: 'SYSTEM',
    cause: 'call-source',
    resource: 'Notes.md',
    revision: 'revision-fixture',
    causationRoot: null,
    causationParent: null,
    causationDepth: null,
    createdAt: '2026-10-19T01:00:00Z',
    lastTurnAt: '2026-10-19T01:01:00Z',
    state: 'COMPLETED',
    evidence: 'retained database snapshot',
    latestJob: null,
    ending: 'ANSWERED',
    children: 0,
    calls: 1,
    attempts: 1,
    activeCalls: 0,
    repeatedCalls: 1,
  };
  const selection = {
    visibility: 'mine' as const,
    project: null,
    conversation: null,
    root: null,
    causation: null,
    active: null,
    after: null,
    limit: 50,
  };
  const page: ProcReplies['proc.list'] = {
    snapshotAt: '2026-10-19T01:00:00Z',
    selection,
    conversations: [row],
    total: 1,
    cursor: null,
    coverage: [],
  };
  const queues: ProcReplies['proc.queues'] = {
    snapshotAt: page.snapshotAt,
    selection,
    queues: [],
    summaries: [],
    pools: [],
    cursor: null,
    coverage: [],
  };
  const calls: ProcReplies['proc.calls'] = {
    snapshotAt: page.snapshotAt,
    selection,
    calls: [],
    cursor: null,
    coverage: [],
  };
  const inspection: ProcReplies['proc.inspect'] = {
    conversations: page,
    queues,
    calls,
    tools: [],
    toolsTruncated: false,
    signals: [],
  };
  const replies: ProcReplies = {
    'proc.list': page,
    'proc.inspect': inspection,
    'proc.calls': calls,
    'proc.queues': queues,
    'proc.tools': {
      snapshotAt: page.snapshotAt,
      selection,
      tools: [],
      cursor: null,
      coverage: [],
    },
  };
  const reads: ProcOperation[] = [];
  const client = new ProcClient({
    async ask(type) {
      if (!(type in replies))
        throw new Error('A PROC read must not start other operations');
      const op = type as ProcOperation;
      reads.push(op);
      return { code: 'OK', payload: replies[op] };
    },
  });
  const keys: QuestionStroke[] = [{ kind: 'enter' }, { kind: 'escape' }];
  let shown = '';
  let closed = 0;
  await exploreProc(client, {
    show: (text) => {
      shown = text;
    },
    view: async (text) => {
      shown = text;
    },
    choose: () => Promise.resolve(keys.shift()),
    close: () => {
      closed += 1;
    },
  });
  expect(shown).toContain('information_tagger');
  expect(shown).toContain('SYSTEM');
  expect(shown).toContain('COMPLETED');
  expect(reads).toEqual(['proc.list', 'proc.inspect', 'proc.list']);
  expect(closed).toBe(2);
});
