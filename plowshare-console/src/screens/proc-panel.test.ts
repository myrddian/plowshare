import { afterEach, describe, expect, it, vi } from 'vitest';
import { mountProcPanel, type ProcReader } from './proc-panel';
import type {
  ProcReplies,
  ProcConversation,
  ProcSelection,
  ProcQuery,
} from '../../../sdk/typescript/src/operations/proc.ts';

const selection: ProcSelection = {
  visibility: 'mine',
  project: null,
  conversation: null,
  root: null,
  causation: null,
  active: null,
  after: null,
  limit: 50,
};
const conversation: ProcConversation = {
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
  causationRoot: 'chain-fixture',
  causationParent: 'parent-event',
  causationDepth: 3,
  createdAt: '2026-01-01T00:00:00Z',
  lastTurnAt: '2026-01-01T00:01:00Z',
  state: 'COMPLETED',
  evidence: 'retained database snapshot',
  latestJob: null,
  ending: 'ANSWERED',
  children: 0,
  calls: 3,
  attempts: 4,
  activeCalls: 0,
  repeatedCalls: 3,
};
const conversations: ProcReplies['proc.list'] = {
  snapshotAt: '2026-10-19T01:00:00Z',
  selection,
  conversations: [conversation],
  total: 1,
  cursor: null,
  coverage: ['all retained origins'],
};
const queues: ProcReplies['proc.queues'] = {
  snapshotAt: conversations.snapshotAt,
  selection,
  queues: [],
  summaries: [],
  pools: [],
  cursor: null,
  coverage: ['counts use separate units'],
};
const calls: ProcReplies['proc.calls'] = {
  snapshotAt: conversations.snapshotAt,
  selection,
  calls: [],
  cursor: null,
  coverage: [],
};
const inspection: ProcReplies['proc.inspect'] = {
  conversations,
  queues,
  calls,
  tools: [],
  toolsTruncated: false,
  signals: ['Repeated calls warrant inspection, not a loop verdict.'],
};
const replies: ProcReplies = {
  'proc.list': conversations,
  'proc.inspect': inspection,
  'proc.calls': calls,
  'proc.queues': queues,
  'proc.tools': {
    snapshotAt: conversations.snapshotAt,
    selection,
    tools: [],
    cursor: null,
    coverage: [],
  },
};
const tick = async () => {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
};
const button = (root: HTMLElement, label: string) => {
  const found = [...root.querySelectorAll('button')].find((b) =>
    b.textContent?.includes(label),
  );
  if (!found) throw new Error(`Missing button: ${label}`);
  return found;
};
afterEach(() => vi.useRealTimers());
describe('PROC audit panel', () => {
  it('waits after a slow periodic read instead of immediately catching up missed ticks', async () => {
    vi.useFakeTimers();
    let complete: (() => void) | undefined;
    const asked: string[] = [];
    const reader: ProcReader = {
      async read(type) {
        asked.push(type);
        if (asked.length === 1)
          await new Promise<void>((resolve) => {
            complete = resolve;
          });
        return replies[type];
      },
    };
    const root = document.createElement('div');
    const panel = mountProcPanel(root, reader);
    const loading = panel.refresh();
    await vi.advanceTimersByTimeAsync(14000);
    expect(asked).toEqual(['proc.list']);
    if (!complete) throw new Error('Missing pending read');
    complete();
    await loading;
    await vi.advanceTimersByTimeAsync(4999);
    expect(asked).toEqual(['proc.list']);
    await vi.advanceTimersByTimeAsync(1);
    expect(asked).toEqual(['proc.list', 'proc.list']);
    panel.destroy();
    await vi.advanceTimersByTimeAsync(20000);
    expect(asked).toHaveLength(2);
  });

  it('joins overlapping inspections but performs a fresh read after completion', async () => {
    let complete: (() => void) | undefined;
    const asked: string[] = [];
    const reader: ProcReader = {
      async read(type) {
        asked.push(type);
        if (type === 'proc.inspect' && !complete)
          await new Promise<void>((resolve) => {
            complete = resolve;
          });
        return replies[type];
      },
    };
    const root = document.createElement('div');
    const panel = mountProcPanel(root, reader);
    await panel.refresh();
    button(root, 'information_tagger').click();
    button(root, 'information_tagger').click();
    await tick();
    expect(asked.filter((type) => type === 'proc.inspect')).toHaveLength(1);
    if (!complete) throw new Error('Missing pending inspection');
    complete();
    await tick();
    await tick();
    expect(root.textContent).toContain('Recorded cause: call-source');
    button(root, 'information_tagger').click();
    await tick();
    expect(asked.filter((type) => type === 'proc.inspect')).toHaveLength(2);
    panel.destroy();
  });

  it('does not poll another page while the selected inspection is still loading', async () => {
    vi.useFakeTimers();
    let complete: (() => void) | undefined;
    const asked: string[] = [];
    const reader: ProcReader = {
      async read(type) {
        asked.push(type);
        if (type === 'proc.inspect' && !complete)
          await new Promise<void>((resolve) => {
            complete = resolve;
          });
        return replies[type];
      },
    };
    const root = document.createElement('div');
    const panel = mountProcPanel(root, reader);
    await panel.refresh();
    button(root, 'information_tagger').click();
    await vi.advanceTimersByTimeAsync(14000);
    expect(asked).toEqual(['proc.list', 'proc.inspect']);
    if (!complete) throw new Error('Missing pending inspection');
    complete();
    await tick();
    await vi.advanceTimersByTimeAsync(4999);
    expect(asked).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(asked).toEqual([
      'proc.list',
      'proc.inspect',
      'proc.list',
      'proc.inspect',
    ]);
    panel.destroy();
  });

  it('does not share inspections across visibility changes or disclose the late result', async () => {
    let complete: (() => void) | undefined;
    const inspected: string[] = [];
    const reader: ProcReader = {
      async read(type, query) {
        if (type === 'proc.inspect') {
          inspected.push(query.visibility ?? 'mine');
          if (query.visibility === 'mine')
            await new Promise<void>((resolve) => {
              complete = resolve;
            });
          else throw new Error('Fleet permission denied');
        }
        return replies[type];
      },
    };
    const root = document.createElement('div');
    const panel = mountProcPanel(root, reader);
    await panel.refresh();
    button(root, 'information_tagger').click();
    const visibility = root.querySelector<HTMLSelectElement>(
      '[aria-label="Audit visibility"]',
    );
    if (!visibility || !complete)
      throw new Error('Missing pending inspection or visibility');
    visibility.value = 'server';
    visibility.dispatchEvent(new Event('change'));
    await tick();
    button(root, 'information_tagger').click();
    await tick();
    expect(inspected).toEqual(['mine', 'server']);
    complete();
    await tick();
    await tick();
    expect(root.textContent).toContain('Fleet permission denied');
    expect(root.textContent).not.toContain('Recorded cause: call-source');
    panel.destroy();
  });

  it('keeps whole-selection queue totals separate from resource pages and unknown occupancy', async () => {
    const checkpoint = {
      id: 'revision:paragraphs',
      unit: 'paragraphs',
      stage: 'summary',
      project: 'research',
      owner: 'alice',
      conversation: 'cnv_fixture',
      revision: 'revision',
      resource: 'Notes.md',
      total: 2,
      completed: 1,
      pending: 1,
      running: null,
      blocked: 0,
      failed: 0,
      skipped: 0,
      oldestPendingAt: null,
      leaseUntil: null,
      coverage: 'Committed checkpoints',
    };
    const queueReplies: ProcReplies = {
      ...replies,
      'proc.queues': {
        ...queues,
        queues: [checkpoint],
        summaries: [
          {
            ...checkpoint,
            id: 'summary:paragraphs',
            total: 105,
            completed: 100,
            pending: 5,
          },
        ],
        cursor: 'next-page',
      },
    };
    const reader: ProcReader = {
      async read(type) {
        return queueReplies[type];
      },
    };
    const root = document.createElement('div');
    const panel = mountProcPanel(root, reader);
    const view = root.querySelector<HTMLSelectElement>(
      '[aria-label="Audit view"]',
    );
    if (!view) throw new Error('Audit view missing');
    view.value = 'proc.queues';
    view.dispatchEvent(new Event('change'));
    await tick();
    expect(root.textContent).toContain('100 / 105 paragraphs');
    expect(root.textContent).toContain('5 pending · Unknown running');
    expect(root.querySelector('table')?.textContent).toContain('1 / 2');
    expect(button(root, 'Next page').disabled).toBe(false);
    panel.destroy();
  });
  it('shows completed internal conversations without a date cutoff and traces recorded causes', async () => {
    const asked: { type: string; query: ProcQuery }[] = [];
    const reader: ProcReader = {
      read: async (type, query) => {
        asked.push({ type, query });
        return replies[type];
      },
    };
    const root = document.createElement('div');
    const panel = mountProcPanel(root, reader);
    await panel.refresh();
    expect(root.textContent).toContain(
      'information_tagger · COMPLETED / ANSWERED',
    );
    expect(root.textContent).toContain('alice');
    expect(root.textContent).toContain('Runs as SYSTEM');
    expect(asked[0]?.query).toEqual({ visibility: 'mine', limit: 50 });
    button(root, 'information_tagger').click();
    await tick();
    expect(root.textContent).toContain('Recorded cause: call-source');
    expect(root.textContent).toContain('not a loop verdict');
    button(root, 'Trace causal chain').click();
    await tick();
    expect(asked.some((a) => a.query.causation === 'chain-fixture')).toBe(true);
    expect(asked.every((a) => a.type.startsWith('proc.'))).toBe(true);
    panel.destroy();
  });
  it('discards a late response after visibility changes and stops polling on destroy', async () => {
    vi.useFakeTimers();
    let resolve: (() => void) | undefined;
    let first = true;
    const asked: string[] = [];
    const emptyReplies: ProcReplies = {
      ...replies,
      'proc.list': { ...conversations, conversations: [], total: 0 },
    };
    const reader: ProcReader = {
      async read(type, query) {
        asked.push(query.visibility ?? 'mine');
        if (first && type === 'proc.list') {
          first = false;
          await new Promise<void>((done) => {
            resolve = done;
          });
          return replies[type];
        }
        return emptyReplies[type];
      },
    };
    const root = document.createElement('div');
    const panel = mountProcPanel(root, reader);
    const loading = panel.refresh();
    const visibility = root.querySelector<HTMLSelectElement>(
      '[aria-label="Audit visibility"]',
    );
    if (!visibility || !resolve)
      throw new Error('Missing controls or pending read');
    visibility.value = 'server';
    visibility.dispatchEvent(new Event('change'));
    resolve();
    await loading;
    await tick();
    expect(root.textContent).not.toContain('information_tagger · COMPLETED');
    expect(asked).toEqual(['mine', 'server']);
    panel.destroy();
    await vi.advanceTimersByTimeAsync(20000);
    expect(asked).toHaveLength(2);
  });
  it('marks a retained snapshot stale when the server cannot be reached', async () => {
    let unavailable = false;
    const reader: ProcReader = {
      async read(type) {
        if (unavailable) throw new Error('connection lost');
        return replies[type];
      },
    };
    const root = document.createElement('div');
    const panel = mountProcPanel(root, reader);
    await panel.refresh();
    unavailable = true;
    await panel.refresh();
    expect(root.textContent).toContain('Stale / unavailable');
    expect(root.textContent).toContain('current activity unknown');
    expect(root.textContent).toContain('information_tagger');
    panel.destroy();
  });
});
