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
const fact = (root: HTMLElement, key: string) =>
  root.querySelector(`[data-proc-fact="${key}"] dd`)?.textContent;
const control = (root: HTMLElement, label: string) => {
  const found = root.querySelector<HTMLSelectElement>(
    `select[aria-label="${label}"]`,
  );
  if (!found) throw new Error(`Missing control: ${label}`);
  return found;
};
const second: ProcConversation = {
  ...conversation,
  id: 'cnv_second',
  agent: 'document_pipeline',
  state: 'RUNNING',
  ending: null,
  cause: 'second-cause',
};
afterEach(() => {
  vi.useRealTimers();
  document.body.replaceChildren();
});
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
    expect(fact(root, 'cause')).toBe('call-source');
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
    expect(root.textContent).not.toContain('call-source');
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
        queues: [
          checkpoint,
          {
            ...checkpoint,
            id: 'revision:unbound',
            conversation: null,
            resource: 'Scan.pdf',
            running: 0,
            failed: 1,
          },
        ],
        pools: [
          {
            name: 'local-gpu',
            lane: 'interactive',
            queued: 3,
            running: 1,
            capacity: 2,
          },
        ],
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
    const view = control(root, 'Audit view');
    view.value = 'proc.queues';
    view.dispatchEvent(new Event('change'));
    await tick();
    // Whole-selection totals sit in their own strip, outside the paged rows.
    const totals = root.querySelector('[aria-label="Full-selection totals"]');
    const page = root.querySelector('.proc-results');
    if (!totals || !page) throw new Error('Missing totals or page');
    expect(page.contains(totals)).toBe(false);
    expect(totals.textContent).toContain('100 / 105 paragraphs');
    expect(
      [...totals.querySelectorAll('.proc-tally')].map((t) => t.textContent),
    ).toEqual(['5 pending', 'Unknown running']);
    const total = totals.querySelector('progress');
    expect([total?.value, total?.max]).toEqual([100, 105]);
    // Checkpoints are rows: a meter, the counters, and the owning conversation to open.
    const row = page.querySelector<HTMLElement>(
      'button.proc-row[data-conversation="cnv_fixture"]',
    );
    if (!row) throw new Error('Missing checkpoint row');
    expect(row.querySelector('strong')?.textContent).toBe('Notes.md');
    expect(row.querySelector('.proc-badge')?.textContent).toBe('1 PENDING');
    expect(row.textContent).toContain('1 / 2 paragraphs');
    expect(row.querySelector('progress')?.getAttribute('aria-label')).toBe(
      'Notes.md completed',
    );
    expect(row.querySelector('.proc-trace')?.textContent).toBe('Trace');
    // A checkpoint with no conversation has nothing to open, and failures lead its badge.
    const unbound = [...page.querySelectorAll('.proc-row')].find((r) =>
      r.textContent?.includes('Scan.pdf'),
    );
    expect(unbound?.tagName).toBe('DIV');
    expect(unbound?.getAttribute('data-tone')).toBe('warn');
    expect(unbound?.querySelector('.proc-badge')?.textContent).toBe('1 FAILED');
    expect(unbound?.textContent).toContain('No conversation');
    expect(root.querySelector('table')).toBeNull();
    const pools = root.querySelector('[aria-label="Local model pools"]');
    expect(pools?.textContent).toContain('local-gpu');
    expect(pools?.textContent).toContain('1 / 2 running');
    expect(pools?.textContent).toContain('3 queued');
    expect(button(root, 'Next page').disabled).toBe(false);
    // Tracing opens the conversation beside the list; the page stays where it was.
    row.click();
    await tick();
    expect(page.contains(row)).toBe(true);
    expect(row.getAttribute('aria-current')).toBe('true');
    expect(root.querySelector('.proc-detail .proc-id')?.textContent).toBe(
      'cnv_fixture',
    );
    // The strips belong to the queue view only.
    view.value = 'proc.list';
    view.dispatchEvent(new Event('change'));
    await tick();
    expect(totals.hasAttribute('hidden')).toBe(true);
    expect(pools?.hasAttribute('hidden')).toBe(true);
    expect(totals.textContent).toBe('');
    panel.destroy();
  });

  it('lists model calls and tool receipts as rows that open their conversation beside the list', async () => {
    const call: ProcReplies['proc.calls']['calls'][number] = {
      id: 'call_fixture',
      conversation: 'cnv_fixture',
      root: 'cnv_root',
      run: null,
      owner: 'alice',
      principal: 'SYSTEM',
      project: 'research',
      agent: 'information_tagger',
      operation: 'chat.completion',
      pool: 'local',
      model: 'fixture-model',
      lane: 'interactive',
      route: 'local',
      runtime: 'fixture',
      state: 'FAILED',
      createdAt: '2026-10-19T00:59:00Z',
      endedAt: '2026-10-19T00:59:02.500Z',
      attempts: 2,
      observations: [],
    };
    const rowReplies: ProcReplies = {
      ...replies,
      'proc.calls': {
        ...calls,
        calls: [
          call,
          { ...call, id: 'call_unbound', conversation: null, agent: null },
        ],
      },
      'proc.tools': {
        ...replies['proc.tools'],
        tools: [
          {
            id: 'tool_fixture',
            conversation: 'cnv_fixture',
            job: 'job_fixture',
            name: 'file_read',
            state: 'RESULT_RECORDED',
            recordedAt: null,
          },
        ],
      },
    };
    const reader: ProcReader = { read: async (type) => rowReplies[type] };
    const root = document.createElement('div');
    document.body.append(root);
    const panel = mountProcPanel(root, reader);
    const view = control(root, 'Audit view');
    view.value = 'proc.calls';
    view.dispatchEvent(new Event('change'));
    await tick();
    expect(root.querySelector('[role="status"]')?.textContent).toContain(
      '2 model calls on this page',
    );
    const [bound, unbound] = root.querySelectorAll<HTMLElement>(
      '.proc-results .proc-row',
    );
    if (!bound || !unbound) throw new Error('Missing call rows');
    expect(bound.dataset.tone).toBe('warn');
    expect(bound.querySelector('strong')?.textContent).toBe(
      'information_tagger',
    );
    expect(bound.querySelector('.proc-badge')?.textContent).toBe('FAILED');
    for (const text of [
      'chat.completion',
      'local / fixture-model',
      '1m ago',
      '2 attempts',
      '2.5s',
      'cnv_fixture',
      'Trace',
    ])
      expect(bound.textContent).toContain(text);
    expect(unbound.tagName).toBe('DIV');
    expect(unbound.querySelector('strong')?.textContent).toBe(
      'chat.completion',
    );
    expect(unbound.textContent).toContain('Unbound call');
    expect(unbound.querySelector('.proc-trace')).toBeNull();
    // Selecting keeps the page and survives a poll with focus on the same call.
    bound.focus();
    bound.click();
    await tick();
    expect(bound.isConnected).toBe(true);
    expect(fact(root, 'cause')).toBe('call-source');
    await panel.refresh();
    await tick();
    expect(document.activeElement?.getAttribute('data-proc-focus')).toBe(
      'call_fixture',
    );
    expect(document.activeElement?.getAttribute('aria-current')).toBe('true');

    view.value = 'proc.tools';
    view.dispatchEvent(new Event('change'));
    await tick();
    expect(root.querySelector('[role="status"]')?.textContent).toContain(
      '1 tool receipt on this page',
    );
    const receipt = root.querySelector<HTMLElement>(
      '.proc-results button.proc-row',
    );
    if (!receipt) throw new Error('Missing tool row');
    expect(receipt.dataset.tone).toBe('done');
    for (const text of [
      'file_read',
      'RESULT_RECORDED',
      'Job job_fixture',
      'Time not recorded',
      'cnv_fixture',
      'Trace',
    ])
      expect(receipt.textContent).toContain(text);
    receipt.click();
    await tick();
    expect(receipt.getAttribute('aria-current')).toBe('true');
    expect(root.querySelector('.proc-detail .proc-id')?.textContent).toBe(
      'cnv_fixture',
    );
    panel.destroy();
  });

  it('names the empty state of every view', async () => {
    const reader: ProcReader = { read: async (type) => replies[type] };
    const root = document.createElement('div');
    const panel = mountProcPanel(root, reader);
    const view = control(root, 'Audit view');
    for (const [type, text] of [
      ['proc.queues', 'No retained checkpoints in this selection.'],
      ['proc.calls', 'No retained model calls in this selection.'],
      ['proc.tools', 'No retained tool receipts in this selection.'],
    ] as const) {
      view.value = type;
      view.dispatchEvent(new Event('change'));
      await tick();
      expect(root.querySelector('.proc-results')?.textContent).toContain(text);
      expect(root.querySelector('.proc-detail')?.textContent).toContain(
        'Select a row',
      );
    }
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
    const listed = root.querySelector('[data-conversation="cnv_fixture"]');
    expect(listed?.querySelector('strong')?.textContent).toBe(
      'information_tagger',
    );
    expect(listed?.querySelector('.proc-badge')?.textContent).toBe(
      'COMPLETED / ANSWERED',
    );
    expect(listed?.textContent).toContain('alice');
    expect(asked[0]?.query).toEqual({
      visibility: 'mine',
      limit: 50,
      order: 'newest',
    });
    button(root, 'information_tagger').click();
    await tick();
    expect(fact(root, 'principal')).toBe('SYSTEM');
    expect(fact(root, 'cause')).toBe('call-source');
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
    expect(root.textContent).not.toContain('information_tagger');
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

  it('inspects the selected row beside the list and keeps the list in place', async () => {
    const twoReplies: ProcReplies = {
      ...replies,
      'proc.list': {
        ...conversations,
        conversations: [conversation, second],
        total: 2,
      },
    };
    const reader: ProcReader = {
      async read(type, query) {
        if (type !== 'proc.inspect') return twoReplies[type];
        const chosen = query.conversation === second.id ? second : conversation;
        const inspected: ProcReplies['proc.inspect'] = {
          ...inspection,
          conversations: { ...conversations, conversations: [chosen] },
        };
        return inspected as ProcReplies[typeof type];
      },
    };
    const root = document.createElement('div');
    document.body.append(root);
    const panel = mountProcPanel(root, reader);
    await panel.refresh();
    const workspace = root.querySelector('.proc-workspace');
    const list = root.querySelector('.proc-results');
    const detail = root.querySelector('.proc-detail');
    if (!workspace || !list || !detail) throw new Error('Missing panes');
    expect([...workspace.children]).toEqual([list.parentElement, detail]);
    expect(detail.textContent).toContain('Select a row');
    const rows = [
      ...list.querySelectorAll<HTMLButtonElement>('button.proc-row'),
    ];
    const [first, other] = rows;
    if (!first || !other) throw new Error('Missing rows');
    first.focus();
    first.click();
    await tick();
    expect(list.querySelectorAll('button.proc-row')).toHaveLength(2);
    expect(first.isConnected).toBe(true);
    expect(first.getAttribute('aria-current')).toBe('true');
    expect(detail.querySelector('.proc-id')?.textContent).toBe('cnv_fixture');
    expect(detail.querySelector('.proc-callout')?.textContent).toContain(
      'not a loop verdict',
    );
    expect(
      [...detail.querySelectorAll('details.proc-section > summary')].map(
        (summary) => summary.firstChild?.textContent,
      ),
    ).toEqual([
      'Conversations and descendants',
      'Queues and iteration progress',
      'Model calls and attempts',
      'Text record',
      'Tool receipts',
    ]);
    // Arrow keys move the selection; the inspection follows without leaving the list.
    first.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }),
    );
    await tick();
    expect(document.activeElement).toBe(other);
    expect(other.getAttribute('aria-current')).toBe('true');
    expect(first.hasAttribute('aria-current')).toBe(false);
    expect(fact(root, 'cause')).toBe('second-cause');
    // A poll replaces both panes but keeps the selection, focus and inspection.
    await panel.refresh();
    await tick();
    const focusedRow = document.activeElement;
    expect(focusedRow?.getAttribute('data-conversation')).toBe('cnv_second');
    expect(focusedRow?.getAttribute('aria-current')).toBe('true');
    expect(fact(root, 'cause')).toBe('second-cause');
    button(root, 'Close').click();
    expect(root.querySelector('[aria-current]')).toBeNull();
    expect(detail.textContent).toContain('Select a row');
    panel.destroy();
  });

  it('lists newest first by default and restarts paging when the order changes', async () => {
    const asked: { type: string; query: ProcQuery }[] = [];
    const paged: ProcReplies = {
      ...replies,
      'proc.list': { ...conversations, cursor: 'cursor-fixture' },
    };
    const reader: ProcReader = {
      read: async (type, query) => {
        asked.push({ type, query });
        return paged[type];
      },
    };
    const root = document.createElement('div');
    const panel = mountProcPanel(root, reader);
    await panel.refresh();
    const order = control(root, 'Audit order');
    expect(order.value).toBe('newest');
    expect(button(root, 'First page').disabled).toBe(true);
    button(root, 'Next page').click();
    await tick();
    expect(asked.at(-1)?.query).toEqual({
      visibility: 'mine',
      after: 'cursor-fixture',
      limit: 50,
      order: 'newest',
    });
    expect(button(root, 'First page').disabled).toBe(false);
    order.value = 'oldest';
    order.dispatchEvent(new Event('change'));
    await tick();
    expect(asked.at(-1)?.query).toEqual({
      visibility: 'mine',
      limit: 50,
      order: 'oldest',
    });
    // Inspections carry the same order, so the detail starts from the same end as the list.
    button(root, 'information_tagger').click();
    await tick();
    expect(asked.at(-1)?.type).toBe('proc.inspect');
    expect(asked.at(-1)?.query.order).toBe('oldest');
    panel.destroy();
  });

  it('sorts every view: the order stays enabled, travels with each page and restarts paging', async () => {
    const asked: { type: string; query: ProcQuery }[] = [];
    const paged: ProcReplies = {
      'proc.list': { ...conversations, cursor: 'cursor-fixture' },
      'proc.inspect': inspection,
      'proc.queues': { ...queues, cursor: 'cursor-fixture' },
      'proc.calls': { ...calls, cursor: 'cursor-fixture' },
      'proc.tools': { ...replies['proc.tools'], cursor: 'cursor-fixture' },
    };
    const reader: ProcReader = {
      read: async (type, query) => {
        asked.push({ type, query });
        return paged[type];
      },
    };
    const root = document.createElement('div');
    const panel = mountProcPanel(root, reader);
    const view = control(root, 'Audit view');
    const order = control(root, 'Audit order');
    expect(order.disabled).toBe(false);
    for (const type of ['proc.queues', 'proc.calls', 'proc.tools'] as const) {
      order.value = 'newest';
      view.value = type;
      view.dispatchEvent(new Event('change'));
      await tick();
      expect(order.disabled).toBe(false);
      expect(asked.at(-1)).toEqual({
        type,
        query: { visibility: 'mine', limit: 50, order: 'newest' },
      });
      button(root, 'Next page').click();
      await tick();
      expect(asked.at(-1)).toEqual({
        type,
        query: {
          visibility: 'mine',
          after: 'cursor-fixture',
          limit: 50,
          order: 'newest',
        },
      });
      // A cursor belongs to the direction that produced it, so a new order starts over.
      order.value = 'oldest';
      order.dispatchEvent(new Event('change'));
      await tick();
      expect(asked.at(-1)).toEqual({
        type,
        query: { visibility: 'mine', limit: 50, order: 'oldest' },
      });
      expect(button(root, 'First page').disabled).toBe(true);
    }
    panel.destroy();
  });

  it('hides server visibility from accounts that are not server administrators', () => {
    const reader: ProcReader = { read: async (type) => replies[type] };
    const root = document.createElement('div');
    const panel = mountProcPanel(root, reader, { serverAdmin: false });
    expect(
      [...control(root, 'Audit visibility').options].map((o) => o.value),
    ).toEqual(['mine']);
    panel.destroy();
  });
});
