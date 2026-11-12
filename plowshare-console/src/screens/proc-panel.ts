import { procText } from '../../../sdk/typescript/src/operations/proc.ts';
import type {
  ProcQuery,
  ProcOperation,
  ProcReplies,
  ProcConversation,
  ProcInspection,
  ProcQueue,
  ProcQueues,
} from '../../../sdk/typescript/src/operations/proc.ts';

export interface ProcReader {
  read<K extends ProcOperation>(
    type: K,
    query: ProcQuery,
  ): Promise<ProcReplies[K]>;
}
export interface ProcPanel {
  refresh(): Promise<void>;
  destroy(): void;
}

type ProcScope = Pick<ProcQuery, 'conversation' | 'root' | 'causation'>;
type ProcView = Exclude<ProcOperation, 'proc.inspect'>;
type ProcTone = 'live' | 'warn' | 'unknown' | 'done' | 'idle';

function node<K extends keyof HTMLElementTagNameMap>(
  kind: K,
  text?: string,
  className?: string,
): HTMLElementTagNameMap[K] {
  const result = document.createElement(kind);
  if (text !== undefined) result.textContent = text;
  if (className !== undefined) result.className = className;
  return result;
}

/**
 * Presentation only: the server's state word stays visible beside the colour. Unrecognised
 * states are shown neutrally instead of being guessed into "running" or "finished".
 */
function tone(state: string): ProcTone {
  switch (state) {
    case 'RUNNING':
    case 'PROCESSING':
    case 'QUEUED':
    case 'RECORDED_ACTIVE':
      return 'live';
    case 'BLOCKED':
    case 'FAILED':
    case 'CANCELLED':
      return 'warn';
    case 'UNKNOWN':
      return 'unknown';
    case 'COMPLETED':
    case 'RESULT_RECORDED':
      return 'done';
    default:
      return 'idle';
  }
}

function chip(text: string, kind: ProcTone): HTMLElement {
  const result = node('span', text, 'proc-badge');
  result.dataset.tone = kind;
  return result;
}

function badge(state: string, ending?: string | null): HTMLElement {
  return chip(ending ? `${state} / ${ending}` : state, tone(state));
}

/**
 * A checkpoint carries counters, not a state word. Its badge names the counter that most needs
 * the operator, in the order failed, blocked, running, pending; nothing is inferred beyond the
 * numbers the server reported, and unknown occupancy never reads as running.
 */
function queueStanding(queue: ProcQueue): { text: string; tone: ProcTone } {
  if (queue.failed > 0) return { text: `${queue.failed} FAILED`, tone: 'warn' };
  if (queue.blocked > 0)
    return { text: `${queue.blocked} BLOCKED`, tone: 'warn' };
  if (queue.running !== null && queue.running > 0)
    return { text: `${queue.running} RUNNING`, tone: 'live' };
  if (queue.pending > 0)
    return { text: `${queue.pending} PENDING`, tone: 'live' };
  if (queue.total > 0 && queue.completed + queue.skipped >= queue.total)
    return { text: 'COMPLETE', tone: 'done' };
  return { text: 'NO WORK', tone: 'idle' };
}

/** A slim native meter: no inline style (Desktop's CSP forbids it) and a spoken value. */
function meter(label: string, value: number, max: number): HTMLProgressElement {
  const result = node('progress', undefined, 'proc-meter');
  result.max = Math.max(1, max);
  result.value = Math.min(Math.max(0, value), result.max);
  result.setAttribute('aria-label', label);
  return result;
}

/** Elapsed wall time between two recorded instants, or undefined when either is unusable. */
function elapsed(from: string, to: string | null): string | undefined {
  if (to === null) return undefined;
  const span = Date.parse(to) - Date.parse(from);
  if (!Number.isFinite(span) || span < 0) return undefined;
  if (span < 1000) return `${span}ms`;
  if (span < 60000) return `${(span / 1000).toFixed(1)}s`;
  if (span < 3600000)
    return `${Math.floor(span / 60000)}m ${Math.floor((span % 60000) / 1000)}s`;
  return `${Math.floor(span / 3600000)}h ${Math.floor((span % 3600000) / 60000)}m`;
}

/**
 * A short age measured against the server snapshot, so a skewed local clock cannot make
 * retained work look recent. The exact local time stays available as the element's title.
 */
function age(at: string, snapshotAt: string): HTMLTimeElement {
  const then = Date.parse(at);
  const minutes = Math.floor((Date.parse(snapshotAt) - then) / 60000);
  const text = !Number.isFinite(minutes)
    ? at
    : minutes < 1
      ? 'now'
      : minutes < 60
        ? `${minutes}m ago`
        : minutes < 1440
          ? `${Math.floor(minutes / 60)}h ago`
          : minutes < 43200
            ? `${Math.floor(minutes / 1440)}d ago`
            : new Date(then).toLocaleDateString();
  const result = node('time', text);
  result.dateTime = at;
  result.title = Number.isFinite(then) ? new Date(then).toLocaleString() : at;
  return result;
}

function counted(count: number, unit: string): string {
  return `${count} ${unit}${count === 1 ? '' : 's'}`;
}

function local(at: string | null): string {
  return at === null ? 'Not recorded' : new Date(at).toLocaleString();
}

function table(label: string, columns: readonly string[]): HTMLTableElement {
  const result = node('table');
  result.setAttribute('aria-label', label);
  const head = node('thead');
  const header = node('tr');
  for (const column of columns) header.append(node('th', column));
  head.append(header);
  result.append(head, node('tbody'));
  return result;
}

function tableRow(target: HTMLTableElement, cells: readonly (string | Node)[]) {
  const row = node('tr');
  for (const cell of cells) {
    const item = node('td');
    item.append(cell);
    row.append(item);
  }
  target.tBodies[0]?.append(row);
}

/**
 * The same read-only audit UI runs in Console and Desktop. Every poll replaces an authoritative
 * page; epochs discard old account/filter/detail responses. Periodic reads wait until the previous
 * refresh completes, without queuing timer catch-up work. Failures retain visibly stale evidence.
 *
 * Layout: a fixed header and toolbar above two independently scrolling panes. The left pane is
 * the current page of the chosen view; the right pane inspects the selected conversation in
 * place, so selecting never moves the operator away from the list. Every view shares that frame:
 * conversations, checkpoints, model calls and tool receipts are all dense rows, and a row bound
 * to a conversation opens that conversation beside the list.
 */
export function mountProcPanel(
  root: HTMLElement,
  reader: ProcReader,
  options: {
    project?: string;
    serverAdmin?: boolean;
    interval?: number;
    openConversation?: (id: string) => void;
    canOpenConversation?: (row: ProcConversation) => boolean;
  } = {},
): ProcPanel {
  const panel = node('section', undefined, 'proc-panel');
  const header = node('header', undefined, 'proc-header');
  const title = node('div', undefined, 'proc-title');
  const status = node('p', undefined, 'proc-status');
  status.setAttribute('role', 'status');
  title.append(
    node('span', 'PROC · read-only audit', 'proc-eyebrow'),
    node('h2', 'Processes'),
  );
  header.append(title, status);

  function select(
    label: string,
    choices: readonly (readonly [string, string])[],
  ): HTMLSelectElement {
    const result = node('select');
    result.setAttribute('aria-label', label);
    for (const [value, text] of choices) {
      const option = node('option', text);
      option.value = value;
      result.append(option);
    }
    return result;
  }
  const controls = node('div', undefined, 'proc-controls');
  controls.setAttribute('role', 'toolbar');
  controls.setAttribute('aria-label', 'Audit filters');
  const visibility = select('Audit visibility', [
    ['mine', 'My work'],
    ...(options.serverAdmin === false
      ? []
      : [['server', 'Whole server · administrator'] as const]),
  ]);
  const project = node('input');
  project.placeholder = 'All authorized projects';
  project.value = options.project ?? '';
  project.setAttribute('aria-label', 'Audit project');
  project.maxLength = 256;
  const view = select('Audit view', [
    ['proc.list', 'Conversations'],
    ['proc.queues', 'Queues / progress'],
    ['proc.calls', 'Model calls'],
    ['proc.tools', 'Tool receipts'],
  ]);
  const order = select('Audit order', [
    ['newest', 'Newest first'],
    ['oldest', 'Oldest first'],
  ]);
  const active = node('input');
  active.type = 'checkbox';
  const activeLabel = node('label', undefined, 'proc-toggle');
  activeLabel.title = 'Only work that is in flight or unresolved';
  activeLabel.append(active, node('span', 'Running only'));
  const refresh = node('button', '↻', 'proc-button proc-icon-button');
  refresh.type = 'button';
  refresh.title = 'Refresh';
  refresh.setAttribute('aria-label', 'Refresh');
  const previous = node('button', 'First page', 'proc-button');
  previous.type = 'button';
  previous.disabled = true;
  const next = node('button', 'Next page', 'proc-button');
  next.type = 'button';
  next.disabled = true;
  const paging = node('div', undefined, 'proc-paging');
  paging.append(refresh, previous, next);
  controls.append(visibility, project, view, order, activeLabel, paging);

  const workspace = node('div', undefined, 'proc-workspace');
  const listPane = node('div', undefined, 'proc-list-pane');
  const selectionLabel = node('p', undefined, 'proc-selection');
  // Whole-selection totals and pool occupancy frame the paged rows without scrolling with them.
  const totals = node('div', undefined, 'proc-strip');
  totals.setAttribute('role', 'group');
  totals.setAttribute('aria-label', 'Full-selection totals');
  totals.hidden = true;
  const body = node('div', undefined, 'proc-results');
  body.setAttribute('role', 'group');
  body.setAttribute('aria-label', 'Audit page');
  const pools = node('div', undefined, 'proc-strip proc-pools');
  pools.setAttribute('role', 'group');
  pools.setAttribute('aria-label', 'Local model pools');
  pools.hidden = true;
  listPane.append(selectionLabel, totals, body, pools);
  const detail = node('section', undefined, 'proc-detail');
  detail.setAttribute('aria-label', 'Execution inspection');
  workspace.append(listPane, detail);
  const coverage = node('details', undefined, 'proc-coverage');
  coverage.append(node('summary', 'Coverage and evidence'));
  const coverageText = node('pre');
  coverage.append(coverageText);
  panel.append(header, controls, workspace, coverage);
  root.replaceChildren(panel);

  let closed = false,
    epoch = 0,
    reading = false,
    pending = false,
    cursor: string | null = null,
    after: string | undefined;
  let scope: ProcScope = {};
  let selected: string | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let inspectionRead:
    { key: string; result: Promise<ProcInspection> } | undefined;
  // Collapsible evidence the operator opened stays open across polls and selections.
  const openSections = new Set<string>(['calls']);

  function cancelPoll() {
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
  }
  function schedulePoll() {
    cancelPoll();
    if (!closed)
      timer = setTimeout(() => {
        timer = undefined;
        run(refreshPage);
      }, options.interval ?? 5000);
  }

  /** Join only overlapping reads of the exact selection; never retain an authorization result. */
  function readInspection(selection: ProcQuery): Promise<ProcInspection> {
    const key = JSON.stringify([
      selection.visibility,
      selection.project,
      selection.conversation,
      selection.root,
      selection.causation,
      selection.active,
      selection.after,
      selection.limit,
      selection.order,
    ]);
    if (inspectionRead?.key === key) return inspectionRead.result;
    cancelPoll();
    const flight = {
      key,
      result: reader.read('proc.inspect', selection).finally(() => {
        if (inspectionRead === flight) {
          inspectionRead = undefined;
          if (!reading) schedulePoll();
        }
      }),
    };
    inspectionRead = flight;
    return flight.result;
  }

  function currentView(): ProcView {
    return view.value === 'proc.queues'
      ? 'proc.queues'
      : view.value === 'proc.calls'
        ? 'proc.calls'
        : view.value === 'proc.tools'
          ? 'proc.tools'
          : 'proc.list';
  }
  function query(): ProcQuery {
    return {
      visibility: visibility.value === 'server' ? 'server' : 'mine',
      ...(project.value.trim() ? { project: project.value.trim() } : {}),
      ...(active.checked ? { active: true } : {}),
      ...scope,
      ...(after ? { after } : {}),
      limit: 50,
    };
  }
  /**
   * Every page view honours the chosen order and its cursor continues in that direction, so the
   * order travels with each page read. Inspections send it too, so the detail pane's
   * conversation, call, queue and tool sections start from the same end as the list.
   */
  function pageQuery(): ProcQuery {
    return {
      ...query(),
      order: order.value === 'oldest' ? 'oldest' : 'newest',
    };
  }
  function stale(text: string) {
    status.textContent = text;
    status.dataset.state = 'stale';
  }
  function run(action: () => Promise<void>) {
    action().catch((error: unknown) => {
      if (!closed)
        stale(
          `Unavailable · ${error instanceof Error ? error.message : 'audit read failed'}`,
        );
    });
  }
  function button(label: string, action: () => void): HTMLButtonElement {
    const b = node('button', label, 'proc-button');
    b.type = 'button';
    b.dataset.procFocus = label;
    b.addEventListener('click', action);
    return b;
  }
  /**
   * A dense list entry. `key` restores focus across polls; `conversation` marks the selection.
   * An entry without a conversation has nothing to inspect, so it is not a control.
   */
  function entry(
    key: string,
    conversation: string | null,
    kind: ProcTone,
  ): HTMLElement {
    const result =
      conversation === null
        ? node('div', undefined, 'proc-row')
        : node('button', undefined, 'proc-row');
    result.dataset.tone = kind;
    if (result instanceof HTMLButtonElement && conversation !== null) {
      result.type = 'button';
      result.dataset.procFocus = key;
      result.dataset.conversation = conversation;
      if (conversation === selected)
        result.setAttribute('aria-current', 'true');
      result.addEventListener('click', () => run(() => inspect(conversation)));
    }
    return result;
  }
  function line(className: string, parts: readonly (string | Node)[]) {
    const result = node('span', undefined, className);
    for (const part of parts)
      result.append(typeof part === 'string' ? node('span', part) : part);
    return result;
  }
  function markSelection() {
    for (const row of body.querySelectorAll<HTMLElement>('[data-conversation]'))
      if (row.dataset.conversation === selected)
        row.setAttribute('aria-current', 'true');
      else row.removeAttribute('aria-current');
  }
  // Replacement polling must preserve the operator's keyboard position in the same selection.
  function focused(container: HTMLElement): string | undefined {
    const activeElement = document.activeElement;
    return activeElement instanceof HTMLElement &&
      container.contains(activeElement)
      ? activeElement.dataset.procFocus
      : undefined;
  }
  function restoreFocus(container: HTMLElement, key: string | undefined) {
    if (key === undefined) return;
    [...container.querySelectorAll<HTMLElement>('[data-proc-focus]')]
      .find((b) => b.dataset.procFocus === key)
      ?.focus({ preventScroll: true });
  }
  // A poll rebuilds the detail; evidence the operator scrolled inside must not jump to the top.
  function scrolled(container: HTMLElement): Map<string, number> {
    const result = new Map<string, number>();
    for (const item of container.querySelectorAll<HTMLElement>(
      '[data-proc-scroll]',
    ))
      result.set(item.dataset.procScroll ?? '', item.scrollTop);
    return result;
  }
  function restoreScroll(container: HTMLElement, saved: Map<string, number>) {
    for (const item of container.querySelectorAll<HTMLElement>(
      '[data-proc-scroll]',
    ))
      item.scrollTop = saved.get(item.dataset.procScroll ?? '') ?? 0;
  }
  function scroller(key: string, ...content: Node[]): HTMLElement {
    const result = node('div', undefined, 'proc-scroll');
    result.dataset.procScroll = key;
    result.append(...content);
    return result;
  }
  function section(
    key: string,
    label: string,
    count: string,
    ...content: Node[]
  ): HTMLDetailsElement {
    const result = node('details', undefined, 'proc-section');
    result.open = openSections.has(key);
    const summary = node('summary');
    summary.dataset.procFocus = `section:${key}`;
    summary.append(node('span', label), node('small', count));
    result.append(summary, ...content);
    result.addEventListener('toggle', () => {
      if (result.open) openSections.add(key);
      else openSections.delete(key);
    });
    return result;
  }
  function label(text: string): HTMLElement {
    return node('h3', text, 'proc-label');
  }
  function showDetail(...content: Node[]) {
    panel.dataset.detail = selected === undefined ? 'empty' : 'open';
    detail.replaceChildren(...content);
  }
  function clearDetail() {
    selected = undefined;
    markSelection();
    showDetail(
      node('p', 'Select a row to inspect its conversation here.', 'proc-empty'),
    );
  }

  /** The owning conversation of a checkpoint, call or receipt, and the cue that it opens here. */
  function owner(conversation: string | null, missing: string): Node[] {
    return conversation === null
      ? [node('span', missing, 'proc-unbound')]
      : [node('code', conversation), node('span', 'Trace', 'proc-trace')];
  }
  function tally(count: number | null, word: string, warns = false) {
    const result = node('span', `${count ?? 'Unknown'} ${word}`, 'proc-tally');
    if (count === 0) result.dataset.zero = 'true';
    else if (warns || count === null) result.dataset.tone = 'warn';
    return result;
  }
  /**
   * Completed over total as a bar and a figure, then the counters. Pending and running are always
   * stated (running may be unknown); blocked, failed and skipped appear only when they are not
   * zero, so a healthy checkpoint stays quiet and an exception stands out.
   */
  function progress(queue: ProcQueue, subject: string): HTMLElement[] {
    return [
      line('proc-progress', [
        meter(`${subject} completed`, queue.completed, queue.total),
        node('span', `${queue.completed} / ${queue.total} ${queue.unit}`),
      ]),
      line('proc-tallies', [
        tally(queue.pending, 'pending'),
        tally(queue.running, 'running'),
        ...(queue.blocked ? [tally(queue.blocked, 'blocked', true)] : []),
        ...(queue.failed ? [tally(queue.failed, 'failed', true)] : []),
        ...(queue.skipped ? [tally(queue.skipped, 'skipped')] : []),
      ]),
    ];
  }
  function queueRows(value: ProcQueues): Node[] {
    if (value.queues.length === 0)
      return [
        node('p', 'No retained checkpoints in this selection.', 'proc-empty'),
      ];
    return value.queues.map((queue) => {
      const standing = queueStanding(queue);
      const name = queue.resource ?? queue.id;
      const item = entry(queue.id, queue.conversation, standing.tone);
      const waiting = node('span', 'oldest pending ', 'proc-aside');
      if (queue.oldestPendingAt)
        waiting.append(age(queue.oldestPendingAt, value.snapshotAt));
      item.append(
        line('proc-row-head', [
          node('span', undefined, 'proc-dot'),
          node('strong', name),
          chip(standing.text, standing.tone),
        ]),
        ...progress(queue, name),
        line('proc-row-meta', [
          queue.stage,
          queue.project ?? 'No project',
          queue.owner ?? 'Unknown owner',
          ...(queue.oldestPendingAt ? [waiting] : []),
        ]),
        line('proc-row-foot', owner(queue.conversation, 'No conversation')),
      );
      return item;
    });
  }
  /** Totals cover the whole selection; they are not the sum of the checkpoint page beneath. */
  function showTotals(value: ProcQueues) {
    totals.hidden = value.summaries.length === 0;
    totals.replaceChildren(
      label('Full-selection totals'),
      ...value.summaries.map((summary) => {
        const item = node('div', undefined, 'proc-total');
        item.append(
          line('proc-total-name', [node('strong', summary.stage)]),
          ...progress(summary, `${summary.stage} ${summary.unit}`),
        );
        return item;
      }),
    );
  }
  function showPools(value: ProcQueues) {
    pools.hidden = value.pools.length === 0;
    pools.replaceChildren(
      label('Local model pools'),
      ...value.pools.map((pool) => {
        const item = node('div', undefined, 'proc-pool');
        item.append(
          line('proc-total-name', [node('strong', pool.name), pool.lane]),
          line('proc-progress', [
            meter(`${pool.name} occupancy`, pool.running, pool.capacity),
            node('span', `${pool.running} / ${pool.capacity} running`),
            tally(pool.queued, 'queued'),
          ]),
        );
        return item;
      }),
    );
  }
  function hideStrips() {
    totals.hidden = true;
    totals.replaceChildren();
    pools.hidden = true;
    pools.replaceChildren();
  }
  function conversationRows(value: ProcReplies['proc.list']): Node[] {
    if (value.conversations.length === 0)
      return [
        node(
          'p',
          'No authorized non-archived conversations in this selection.',
          'proc-empty',
        ),
      ];
    return value.conversations.map((row) => {
      const item = entry(row.id, row.id, tone(row.state));
      item.append(
        line('proc-row-head', [
          node('span', undefined, 'proc-dot'),
          node('strong', row.agent ?? row.origin),
          badge(row.state, row.ending),
        ]),
        line('proc-row-meta', [
          row.project ?? 'No project',
          row.owner ?? 'Unknown owner',
          age(row.lastTurnAt ?? row.createdAt, value.snapshotAt),
        ]),
        line('proc-row-foot', [
          node('code', row.id),
          `${counted(row.calls, 'call')}${row.activeCalls ? ` · ${row.activeCalls} active` : ''}`,
        ]),
      );
      return item;
    });
  }
  function callRows(value: ProcReplies['proc.calls']): Node[] {
    if (value.calls.length === 0)
      return [
        node('p', 'No retained model calls in this selection.', 'proc-empty'),
      ];
    return value.calls.map((call) => {
      const item = entry(call.id, call.conversation, tone(call.state));
      const took = elapsed(call.createdAt, call.endedAt);
      item.append(
        line('proc-row-head', [
          node('span', undefined, 'proc-dot'),
          node('strong', call.agent ?? call.operation),
          badge(call.state),
        ]),
        line('proc-row-meta', [
          ...(call.agent ? [call.operation] : []),
          `${call.pool} / ${call.model}`,
          age(call.createdAt, value.snapshotAt),
        ]),
        line('proc-row-meta', [
          call.project ?? 'No project',
          call.owner ?? 'Unknown owner',
          counted(call.attempts, 'attempt'),
          ...(took === undefined ? [] : [took]),
        ]),
        line('proc-row-foot', owner(call.conversation, 'Unbound call')),
      );
      return item;
    });
  }
  function toolRows(value: ProcReplies['proc.tools']): Node[] {
    if (value.tools.length === 0)
      return [
        node('p', 'No retained tool receipts in this selection.', 'proc-empty'),
      ];
    return value.tools.map((tool) => {
      const item = entry(tool.id, tool.conversation, tone(tool.state));
      item.append(
        line('proc-row-head', [
          node('span', undefined, 'proc-dot'),
          node('strong', tool.name),
          badge(tool.state),
        ]),
        line('proc-row-meta', [
          tool.job === null ? 'No job recorded' : `Job ${tool.job}`,
          ...(tool.recordedAt
            ? [age(tool.recordedAt, value.snapshotAt)]
            : ['Time not recorded']),
        ]),
        line('proc-row-foot', owner(tool.conversation, 'No conversation')),
      );
      return item;
    });
  }
  function record(key: string, text: string): HTMLElement {
    return section(key, 'Text record', '', scroller(key, node('pre', text)));
  }

  function trace(part: ProcScope) {
    scope = part;
    reset();
    run(refreshPage);
  }
  function reset() {
    ++epoch;
    after = undefined;
    cursor = null;
    next.disabled = true;
    previous.disabled = true;
    panel.dataset.view = currentView().slice('proc.'.length);
    body.replaceChildren();
    hideStrips();
    clearDetail();
    coverageText.textContent = '';
    const chosen = scope.conversation ?? scope.root ?? scope.causation;
    if (chosen)
      selectionLabel.replaceChildren(
        node('span', 'Tracing '),
        node('code', chosen),
        node('span', ' · '),
        button('All non-archived conversations', () => trace({})),
      );
    else
      selectionLabel.replaceChildren(
        node('span', 'All non-archived conversations'),
      );
  }
  function facts(row: ProcConversation): HTMLElement {
    const grid = node('dl', undefined, 'proc-facts');
    const fact = (key: string, name: string, value: string, mono = false) => {
      const item = node('div');
      item.dataset.procFact = key;
      const definition = node('dd');
      definition.append(mono ? node('code', value) : value);
      item.append(node('dt', name), definition);
      grid.append(item);
    };
    fact(
      'application',
      'Initiating Application',
      row.application ?? 'Not recorded',
    );
    fact('actor', 'Authenticating actor', row.actor ?? 'Not recorded');
    fact('subject', 'Acting subject', row.actingSubject ?? 'Not recorded');
    fact('project', 'Project', row.project ?? 'No project');
    fact('owner', 'Admission owner', row.owner ?? 'Unknown');
    fact('principal', 'Runs as', row.principal);
    fact('root', 'Root', row.root, true);
    fact('parent', 'Parent', row.parent ?? 'None', row.parent !== null);
    fact('origin', 'Origin', row.origin);
    fact('lifecycle', 'Lifecycle', row.lifecycle);
    fact('source', 'Source', row.resource ?? 'Unknown');
    fact(
      'revision',
      'Revision',
      row.revision ?? 'Unknown',
      row.revision !== null,
    );
    fact(
      'activity',
      'Activity',
      `${row.calls} direct calls · ${row.attempts} attempts · ${row.children} children · ${row.activeCalls} recorded active`,
    );
    fact('created', 'Created', local(row.createdAt));
    fact('last-turn', 'Last turn', local(row.lastTurnAt));
    fact('cause', 'Recorded cause', row.cause ?? 'Unknown');
    fact('evidence', 'Evidence', row.evidence);
    return grid;
  }
  function actions(row: ProcConversation): HTMLElement {
    const links = node('div', undefined, 'proc-actions');
    if (
      options.openConversation &&
      (options.canOpenConversation?.(row) ?? true)
    ) {
      const open = button('Open trajectory', () =>
        options.openConversation?.(row.id),
      );
      open.classList.add('proc-primary');
      links.append(open);
    }
    links.append(button('Trace root', () => trace({ root: row.root })));
    if (row.parent)
      links.append(
        button('Trace parent', () =>
          trace({ conversation: row.parent ?? row.id }),
        ),
      );
    if (row.causationRoot)
      links.append(
        button('Trace causal chain', () =>
          trace({ causation: row.causationRoot ?? '' }),
        ),
      );
    return links;
  }
  function inspected(value: ProcInspection, id: string) {
    const focus = focused(detail);
    const positions = scrolled(detail);
    const row = value.conversations.conversations.find((c) => c.id === id);
    const heading = node('header', undefined, 'proc-detail-header');
    const name = node('div', undefined, 'proc-detail-name');
    name.append(node('h3', row ? (row.agent ?? row.origin) : 'Conversation'));
    if (row) name.append(badge(row.state, row.ending));
    const close = button('Close', clearDetail);
    close.classList.add('proc-quiet');
    name.append(close);
    const identifier = node('code', id, 'proc-id');
    identifier.title = 'Conversation identifier';
    heading.append(name, identifier);
    const content: Node[] = [heading];
    if (row) content.push(actions(row), facts(row));
    if (value.signals.length) {
      const callout = node('div', undefined, 'proc-callout');
      callout.setAttribute('role', 'note');
      callout.append(
        node(
          'strong',
          'Repetition signals · investigate before concluding a loop',
        ),
      );
      const signals = node('ul');
      for (const signal of value.signals) signals.append(node('li', signal));
      callout.append(signals);
      content.push(callout);
    }
    const descendants = value.conversations.conversations.filter(
      (child) => child.id !== id,
    );
    const tree = node('div', undefined, 'proc-tree');
    for (const child of descendants) {
      const item = node('button', undefined, 'proc-row');
      item.type = 'button';
      item.dataset.procFocus = `descendant:${child.id}`;
      item.dataset.tone = tone(child.state);
      item.addEventListener('click', () => run(() => inspect(child.id)));
      item.append(
        line('proc-row-head', [
          node('span', undefined, 'proc-dot'),
          node('strong', child.agent ?? child.origin),
          badge(child.state, child.ending),
        ]),
        line('proc-row-foot', [
          node('code', child.id),
          counted(child.calls, 'call'),
        ]),
      );
      tree.append(item);
    }
    if (!descendants.length)
      tree.append(
        node('p', 'No other conversations in this tree page.', 'proc-empty'),
      );
    if (value.conversations.cursor)
      tree.append(
        button('Continue conversation tree', () => trace({ conversation: id })),
      );
    content.push(
      section(
        'tree',
        'Conversations and descendants',
        String(descendants.length),
        tree,
      ),
    );

    const queues = section(
      'queues',
      'Queues and iteration progress',
      String(value.queues.queues.length),
      scroller('queues', node('pre', procText(value.queues))),
    );
    if (value.queues.cursor)
      queues.append(
        button('Browse queue pages', () => {
          view.value = 'proc.queues';
          trace({ conversation: id });
        }),
      );
    content.push(queues);

    const callTable = table('Model calls and attempts', [
      'Agent / operation',
      'Model',
      'State',
      'Attempts',
      'Started',
    ]);
    for (const call of value.calls.calls)
      tableRow(callTable, [
        call.agent ? `${call.agent} / ${call.operation}` : call.operation,
        `${call.pool} / ${call.model}`,
        badge(call.state),
        String(call.attempts),
        age(call.createdAt, value.calls.snapshotAt),
      ]);
    const calls = section(
      'calls',
      'Model calls and attempts',
      String(value.calls.calls.length),
      value.calls.calls.length
        ? scroller('calls', callTable)
        : node('p', 'No retained model calls in this page.', 'proc-empty'),
      record('calls-text', procText(value.calls)),
    );
    if (value.calls.cursor)
      calls.append(
        button('Browse call pages', () => {
          view.value = 'proc.calls';
          trace({ conversation: id });
        }),
      );
    content.push(calls);

    const toolTable = table('Tool receipts', [
      'Tool',
      'State',
      'Conversation',
      'Job',
    ]);
    for (const tool of value.tools)
      tableRow(toolTable, [
        tool.name,
        badge(tool.state),
        node('code', tool.conversation),
        tool.job ?? 'Unknown',
      ]);
    const tools = section(
      'tools',
      'Tool receipts',
      `${value.tools.length}${value.toolsTruncated ? '+' : ''}`,
      value.tools.length
        ? scroller('tools', toolTable)
        : node('p', 'No retained tool calls in this page.', 'proc-empty'),
    );
    if (value.toolsTruncated)
      tools.append(
        button('Browse tool receipt pages', () => {
          view.value = 'proc.tools';
          trace({ conversation: id });
        }),
      );
    content.push(tools);
    showDetail(...content);
    restoreScroll(detail, positions);
    restoreFocus(detail, focus);
  }
  async function inspect(id: string) {
    const stamp = ++epoch;
    selected = id;
    markSelection();
    showDetail(node('p', `Reading ${id}…`, 'proc-empty'));
    const { after: _after, ...selection } = pageQuery();
    const value = await readInspection({
      ...selection,
      active: false,
      conversation: id,
      limit: 50,
    });
    if (closed || stamp !== epoch || selected !== id) return;
    inspected(value, id);
  }
  async function refreshPage(): Promise<void> {
    if (closed) return;
    if (reading) {
      pending = true;
      return;
    }
    cancelPoll();
    reading = true;
    const stamp = epoch;
    refresh.disabled = true;
    try {
      const type = currentView();
      const value = await reader.read(type, pageQuery());
      if (closed || stamp !== epoch) return;
      const focus = focused(body);
      cursor = value.cursor;
      next.disabled = !cursor;
      status.textContent = `Snapshot ${new Date(value.snapshotAt).toLocaleString()} · ${
        'conversations' in value
          ? `${counted(value.total, 'conversation')} in selection`
          : 'queues' in value
            ? `${counted(value.queues.length, 'checkpoint')} on this page`
            : 'calls' in value
              ? `${counted(value.calls.length, 'model call')} on this page`
              : `${counted(value.tools.length, 'tool receipt')} on this page`
      }`;
      delete status.dataset.state;
      coverageText.textContent = value.coverage.join('\n');
      if ('conversations' in value)
        body.replaceChildren(...conversationRows(value));
      else if ('queues' in value) {
        showTotals(value);
        showPools(value);
        body.replaceChildren(
          ...queueRows(value),
          record('page-text', procText(value)),
        );
      } else if ('calls' in value)
        body.replaceChildren(
          ...callRows(value),
          record('page-text', procText(value)),
        );
      else
        body.replaceChildren(
          ...toolRows(value),
          record('page-text', procText(value)),
        );
      restoreFocus(body, focus);
      if (selected) {
        const id = selected;
        const { after: _after, ...detailQuery } = pageQuery();
        const inspectedValue = await readInspection({
          ...detailQuery,
          active: false,
          conversation: id,
          limit: 50,
        });
        if (!closed && stamp === epoch && selected === id)
          inspected(inspectedValue, id);
      }
    } catch (error: unknown) {
      if (!closed && stamp === epoch)
        stale(
          `Stale / unavailable · ${error instanceof Error ? error.message : 'read failed'} · current activity unknown`,
        );
    } finally {
      reading = false;
      refresh.disabled = false;
      if (pending && !closed) {
        pending = false;
        run(refreshPage);
      } else if (!inspectionRead) schedulePoll();
    }
  }
  refresh.addEventListener('click', () => run(refreshPage));
  previous.addEventListener('click', () => {
    reset();
    run(refreshPage);
  });
  next.addEventListener('click', () => {
    if (!cursor) return;
    ++epoch;
    after = cursor;
    previous.disabled = false;
    clearDetail();
    run(refreshPage);
  });
  for (const control of [visibility, project, view, active, order])
    control.addEventListener('change', () => {
      reset();
      run(refreshPage);
    });
  // Arrow keys walk the list and inspect each row beside it, like any list/detail workspace.
  body.addEventListener('keydown', (event) => {
    const step =
      event.key === 'ArrowDown' ? 1 : event.key === 'ArrowUp' ? -1 : 0;
    if (step === 0 && event.key !== 'Home' && event.key !== 'End') return;
    const rows = [
      ...body.querySelectorAll<HTMLButtonElement>('button.proc-row'),
    ];
    const at = rows.findIndex((row) => row === document.activeElement);
    if (at < 0) return;
    event.preventDefault();
    const target =
      rows[
        event.key === 'Home'
          ? 0
          : event.key === 'End'
            ? rows.length - 1
            : Math.min(rows.length - 1, Math.max(0, at + step))
      ];
    if (!target || target === rows[at]) return;
    target.focus();
    target.click();
  });
  reset();
  schedulePoll();
  return {
    refresh: refreshPage,
    destroy() {
      if (closed) return;
      closed = true;
      ++epoch;
      cancelPoll();
      panel.remove();
    },
  };
}
