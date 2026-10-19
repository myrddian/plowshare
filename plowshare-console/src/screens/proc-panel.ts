import { procText } from '../../../sdk/typescript/src/operations/proc.ts';
import type {
  ProcQuery,
  ProcOperation,
  ProcReplies,
  ProcConversation,
  ProcInspection,
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

function node<K extends keyof HTMLElementTagNameMap>(
  kind: K,
  text?: string,
): HTMLElementTagNameMap[K] {
  const result = document.createElement(kind);
  if (text !== undefined) result.textContent = text;
  return result;
}

/**
 * The same read-only audit UI runs in Console and Desktop. Every poll replaces an authoritative
 * page; epochs discard old account/filter/detail responses. Failures retain visibly stale evidence.
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
  const panel = node('section');
  panel.className = 'proc-panel';
  const heading = node('h2', 'Processes / PROC');
  const intro = node(
    'p',
    'All non-archived conversations, including internal work and completed executions.',
  );
  const controls = node('div');
  controls.className = 'proc-controls';
  const visibility = node('select');
  visibility.setAttribute('aria-label', 'Audit visibility');
  for (const [value, label] of [
    ['mine', 'My work'],
    ['server', 'Server · administrator'],
  ]) {
    if (value === 'server' && options.serverAdmin === false) continue;
    const option = node('option', label);
    option.value = value ?? '';
    visibility.append(option);
  }
  const project = node('input');
  project.placeholder = 'All authorized projects';
  project.value = options.project ?? '';
  project.setAttribute('aria-label', 'Audit project');
  project.maxLength = 256;
  const active = node('input');
  active.type = 'checkbox';
  const activeLabel = node('label', 'In flight / unresolved only ');
  activeLabel.append(active);
  const view = node('select');
  view.setAttribute('aria-label', 'Audit view');
  for (const [value, label] of [
    ['proc.list', 'Conversations'],
    ['proc.queues', 'Queues / progress'],
    ['proc.calls', 'Model calls'],
    ['proc.tools', 'Tool receipts'],
  ]) {
    const option = node('option', label);
    option.value = value ?? '';
    view.append(option);
  }
  const refresh = node('button', 'Refresh');
  refresh.type = 'button';
  const previous = node('button', 'First page');
  previous.type = 'button';
  const next = node('button', 'Next page');
  next.type = 'button';
  next.disabled = true;
  controls.append(
    visibility,
    project,
    view,
    activeLabel,
    refresh,
    previous,
    next,
  );
  const status = node('p');
  status.setAttribute('role', 'status');
  status.className = 'proc-status';
  const selectionLabel = node('p');
  selectionLabel.className = 'proc-selection';
  const body = node('div');
  body.className = 'proc-results';
  const detail = node('section');
  detail.className = 'proc-detail';
  detail.setAttribute('aria-label', 'Execution inspection');
  const coverage = node('details');
  coverage.append(node('summary', 'Coverage and evidence'));
  const coverageText = node('pre');
  coverage.append(coverageText);
  panel.append(
    heading,
    intro,
    controls,
    status,
    selectionLabel,
    body,
    detail,
    coverage,
  );
  root.replaceChildren(panel);
  let closed = false,
    epoch = 0,
    reading = false,
    pending = false,
    cursor: string | null = null,
    after: string | undefined;
  let scope: Pick<ProcQuery, 'conversation' | 'root' | 'causation'> = {};
  let selected: string | undefined;

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
  function run(action: () => Promise<void>) {
    action().catch((error: unknown) => {
      if (!closed)
        status.textContent = `Unavailable · ${error instanceof Error ? error.message : 'audit read failed'}`;
    });
  }
  function button(label: string, action: () => void): HTMLButtonElement {
    const b = node('button', label);
    b.type = 'button';
    b.dataset.procFocus = label;
    b.addEventListener('click', action);
    return b;
  }
  // Replacement polling must preserve the operator's keyboard position in the same selection.
  function focused(container: HTMLElement): string | undefined {
    const activeElement = document.activeElement;
    return activeElement instanceof HTMLButtonElement &&
      container.contains(activeElement)
      ? activeElement.dataset.procFocus
      : undefined;
  }
  function restoreFocus(container: HTMLElement, key: string | undefined) {
    if (key === undefined) return;
    [...container.querySelectorAll<HTMLButtonElement>('button')]
      .find((b) => b.dataset.procFocus === key)
      ?.focus({ preventScroll: true });
  }
  function queueTable(value: ProcQueues): HTMLElement {
    const block = node('section');
    block.append(node('h3', 'Full-selection totals'));
    const totals = node('div');
    totals.className = 'proc-totals';
    for (const summary of value.summaries) {
      const item = node('div');
      item.append(
        node(
          'strong',
          `${summary.completed} / ${summary.total} ${summary.unit}`,
        ),
        node('p', summary.stage),
        node(
          'p',
          `${summary.pending} pending · ${summary.running ?? 'Unknown'} running · ${summary.blocked} blocked`,
        ),
      );
      totals.append(item);
    }
    block.append(totals, node('h3', 'Resource checkpoints · current page'));
    const table = node('table');
    table.setAttribute('aria-label', 'Queue resource checkpoints');
    const head = node('thead'),
      header = node('tr');
    for (const label of [
      'Source / stage',
      'Completed / total',
      'Pending / running',
      'Blocked / failed / skipped',
    ])
      header.append(node('th', label));
    head.append(header);
    table.append(head);
    const rows = node('tbody');
    for (const queue of value.queues) {
      const row = node('tr'),
        source = node('td');
      source.append(
        node('strong', queue.resource ?? queue.id),
        node('p', `${queue.stage} · ${queue.unit}`),
        node(
          'p',
          `${queue.project ?? 'No project'} · ${queue.owner ?? 'Unknown owner'}`,
        ),
      );
      if (queue.conversation) {
        const conversation = queue.conversation;
        source.append(
          button('Trace ' + conversation, () =>
            run(() => inspect(conversation)),
          ),
        );
      }
      const progress = node('td', `${queue.completed} / ${queue.total}`);
      const pendingCell = node(
        'td',
        `${queue.pending} / ${queue.running ?? 'Unknown'}`,
      );
      row.append(
        source,
        progress,
        pendingCell,
        node('td', `${queue.blocked} / ${queue.failed} / ${queue.skipped}`),
      );
      rows.append(row);
    }
    table.append(rows);
    block.append(table);
    if (!value.queues.length)
      block.append(node('p', 'No retained checkpoints in this selection.'));
    if (value.pools.length) {
      block.append(node('h3', 'Local model pools'));
      for (const pool of value.pools)
        block.append(
          node(
            'p',
            `${pool.name} · ${pool.lane} · ${pool.queued} queued · ${pool.running} running / ${pool.capacity} capacity`,
          ),
        );
    }
    return block;
  }
  function trace(part: Pick<ProcQuery, 'conversation' | 'root' | 'causation'>) {
    scope = part;
    reset();
    run(refreshPage);
  }
  function reset() {
    ++epoch;
    after = undefined;
    cursor = null;
    selected = undefined;
    next.disabled = true;
    body.replaceChildren();
    detail.replaceChildren();
    coverageText.textContent = '';
    selectionLabel.replaceChildren();
    const chosen = scope.conversation ?? scope.root ?? scope.causation;
    if (chosen)
      selectionLabel.append(
        node('span', `Tracing ${chosen} `),
        button('All non-archived conversations', () => trace({})),
      );
  }
  function facts(row: ProcConversation): HTMLElement {
    const block = node('div');
    block.className = 'proc-conversation-facts';
    block.append(
      node(
        'p',
        `Initiating Application: ${row.application ?? 'Not recorded'} · authenticating actor: ${row.actor ?? 'Not recorded'} · acting subject: ${row.actingSubject ?? 'Not recorded'}`,
      ),
      node(
        'p',
        `${row.project ?? 'No project'} · admission owner ${row.owner ?? 'Unknown'} · principal ${row.principal}`,
      ),
      node(
        'p',
        `Root ${row.root} · ${row.origin} · lifecycle ${row.lifecycle}`,
      ),
      node(
        'p',
        `Source ${row.resource ?? 'Unknown'} · revision ${row.revision ?? 'Unknown'}`,
      ),
      node(
        'p',
        `${row.calls} direct calls · ${row.attempts} attempts · ${row.children} children`,
      ),
      node('p', row.evidence),
    );
    const links = node('div');
    links.className = 'proc-controls';
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
    if (
      options.openConversation &&
      (options.canOpenConversation?.(row) ?? true)
    )
      links.append(
        button('Open trajectory', () => options.openConversation?.(row.id)),
      );
    block.append(links, node('p', `Recorded cause: ${row.cause ?? 'Unknown'}`));
    return block;
  }
  function inspected(value: ProcInspection, id: string) {
    const focus = focused(detail);
    detail.replaceChildren(node('h3', `Inspect ${id}`));
    const row = value.conversations.conversations.find((c) => c.id === id);
    if (row) detail.append(facts(row));
    if (value.signals.length)
      detail.append(
        node('h3', 'Repetition signals · investigate before concluding a loop'),
        node('pre', value.signals.join('\n')),
      );
    detail.append(node('h3', 'Conversations and descendants'));
    for (const child of value.conversations.conversations)
      detail.append(
        button(
          `${child.id} · ${child.agent ?? child.origin} · ${child.state}`,
          () => run(() => inspect(child.id)),
        ),
      );
    if (value.conversations.cursor)
      detail.append(
        button('Continue conversation tree', () => trace({ conversation: id })),
      );
    detail.append(
      node('h3', 'Queues and iteration progress'),
      node('pre', procText(value.queues)),
    );
    if (value.queues.cursor)
      detail.append(
        button('Browse queue pages', () => {
          view.value = 'proc.queues';
          trace({ conversation: id });
        }),
      );
    detail.append(
      node('h3', 'Model calls and attempts'),
      node('pre', procText(value.calls)),
    );
    if (value.calls.cursor)
      detail.append(
        button('Browse call pages', () => {
          view.value = 'proc.calls';
          trace({ conversation: id });
        }),
      );
    detail.append(
      node('h3', 'Tool receipts'),
      node(
        'pre',
        value.tools.length
          ? value.tools
              .map(
                (t) =>
                  `${t.name} · ${t.state} · ${t.conversation} · job ${t.job ?? 'Unknown'}`,
              )
              .join('\n')
          : 'No retained tool calls in this page.',
      ),
    );
    if (value.toolsTruncated)
      detail.append(
        button('Browse tool receipt pages', () => {
          view.value = 'proc.tools';
          trace({ conversation: id });
        }),
      );
    restoreFocus(detail, focus);
  }
  async function inspect(id: string) {
    const stamp = ++epoch;
    selected = id;
    detail.replaceChildren(node('p', `Reading ${id}…`));
    const { after: _after, ...selection } = query();
    const value = await reader.read('proc.inspect', {
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
    reading = true;
    const stamp = epoch;
    refresh.disabled = true;
    try {
      const type =
        view.value === 'proc.queues'
          ? 'proc.queues'
          : view.value === 'proc.calls'
            ? 'proc.calls'
            : view.value === 'proc.tools'
              ? 'proc.tools'
              : 'proc.list';
      const value = await reader.read(type, query());
      if (closed || stamp !== epoch) return;
      const focus = focused(body);
      body.replaceChildren();
      cursor = value.cursor;
      next.disabled = !cursor;
      status.textContent = `Snapshot ${new Date(value.snapshotAt).toLocaleString()} · ${'total' in value ? `${value.total} conversations in selection` : 'authorized audit page'}`;
      coverageText.textContent = value.coverage.join('\n');
      if ('conversations' in value) {
        const table = node('table');
        table.setAttribute('aria-label', 'Non-archived conversation audit');
        const header = node('tr');
        for (const name of [
          'State / agent',
          'Conversation / project',
          'Admission owner / principal',
          'Recorded activity',
        ])
          header.append(node('th', name));
        const head = node('thead');
        head.append(header);
        table.append(head);
        const rows = node('tbody');
        table.append(rows);
        for (const row of value.conversations) {
          const item = node('tr');
          item.dataset.conversation = row.id;
          const state = node('td');
          state.append(
            button(
              `${row.agent ?? row.origin} · ${row.state}${row.ending ? ` / ${row.ending}` : ''}`,
              () => run(() => inspect(row.id)),
            ),
            node('p', row.evidence),
          );
          const source = node('td');
          source.append(
            node('code', row.id),
            node('p', row.project ?? 'No project'),
            node('p', `Created ${new Date(row.createdAt).toLocaleString()}`),
          );
          if (row.resource) source.append(node('p', row.resource));
          const actor = node('td');
          actor.append(
            node('p', row.owner ?? 'Unknown'),
            node('p', `Runs as ${row.principal}`),
          );
          const activity = node('td');
          activity.append(
            node('p', `${row.calls} calls / ${row.attempts} attempts`),
            node(
              'p',
              `${row.children} children · ${row.activeCalls} recorded active calls`,
            ),
          );
          item.append(state, source, actor, activity);
          rows.append(item);
        }
        body.append(table);
        if (value.conversations.length === 0)
          body.append(
            node(
              'p',
              'No authorized non-archived conversations in this selection.',
            ),
          );
      } else if ('queues' in value) {
        body.append(queueTable(value));
      } else {
        body.append(node('pre', procText(value)));
        if ('calls' in value)
          for (const call of value.calls)
            if (call.conversation) {
              const conversation = call.conversation;
              body.append(
                button(
                  `Trace ${call.agent ?? call.operation} · ${conversation}`,
                  () => run(() => inspect(conversation)),
                ),
              );
            }
        if ('tools' in value)
          for (const tool of value.tools)
            body.append(
              button(`Trace ${tool.name} · ${tool.conversation}`, () =>
                run(() => inspect(tool.conversation)),
              ),
            );
      }
      restoreFocus(body, focus);
      if (selected) {
        const id = selected;
        const { after: _after, ...detailQuery } = query();
        const inspectedValue = await reader.read('proc.inspect', {
          ...detailQuery,
          active: false,
          conversation: id,
        });
        if (!closed && stamp === epoch && selected === id)
          inspected(inspectedValue, id);
      }
    } catch (error: unknown) {
      if (!closed && stamp === epoch)
        status.textContent = `Stale / unavailable · ${error instanceof Error ? error.message : 'read failed'} · current activity unknown`;
    } finally {
      reading = false;
      refresh.disabled = false;
      if (pending && !closed) {
        pending = false;
        run(refreshPage);
      }
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
    selected = undefined;
    detail.replaceChildren();
    run(refreshPage);
  });
  for (const control of [visibility, project, view, active])
    control.addEventListener('change', () => {
      reset();
      run(refreshPage);
    });
  const timer = setInterval(() => run(refreshPage), options.interval ?? 5000);
  return {
    refresh: refreshPage,
    destroy() {
      if (closed) return;
      closed = true;
      ++epoch;
      clearInterval(timer);
      panel.remove();
    },
  };
}
