import type {
  GoalPage,
  GoalStatus,
} from '../../../sdk/typescript/src/operations/replies.ts';
import type { checkedTransport } from '../../../sdk/typescript/src/operations/transport.ts';
import { ConnectionFault } from '../../../sdk/typescript/src/binding/connection.ts';
import { background } from '../background.ts';
import { button, el, field, labelled, nothing, problemText } from './dom';
import type { Screen, Transport } from './screen';
import { createTrajectory } from './trajectory';

type Control = 'pause' | 'resume' | 'cancel';
interface Intent {
  readonly requestId: string;
  readonly version: number;
  receiptMissing: boolean;
}

const terminal = new Set(['COMPLETE', 'CANCELLED', 'STOPPING']);
const pausable = new Set(['READY', 'RUNNING', 'IDLE', 'WAITING']);
const controls: Readonly<Record<Control, string>> = {
  pause: 'Pause',
  resume: 'Resume',
  cancel: 'Cancel',
};

function description(value: string): string {
  return value.toLowerCase().replaceAll('_', ' ');
}

function goalFacts(status: GoalStatus): HTMLElement[] {
  const facts = [
    field('Goal', status.goal),
    field('Conversation', status.conversation),
    field('Instance', status.instance),
    field('Lifecycle', description(status.lifecycle)),
    field('State', description(status.state)),
  ];
  if (status.reason !== null)
    facts.push(field('Reason', description(status.reason)));
  if (!status.readable) {
    facts.push(el('p', 'nothing', 'Conversation content is restricted.'));
    return facts;
  }
  if (status.agent !== null) facts.push(field('Agent', status.agent));
  if (status.detail !== null) facts.push(field('Detail', status.detail));
  facts.push(
    field(
      'Wake sources',
      `${status.schedules.length}${status.moreSchedules ? '+' : ''} schedules · ${status.relayListeners.length}${status.moreRelayListeners ? '+' : ''} Relay listeners`,
    ),
  );
  if (status.nextScheduleAt !== null)
    facts.push(field('Next schedule', status.nextScheduleAt));
  if (status.latestResult !== null)
    facts.push(field('Latest result', status.latestResult.summary));
  return facts;
}

function goalEvidence(status: GoalStatus): HTMLElement[] {
  const parts: HTMLElement[] = [];
  if (status.latestResult !== null) {
    parts.push(field('Result turn', String(status.latestResult.turn)));
    const list = el('ul', 'goal-evidence');
    list.append(
      ...status.latestResult.evidence.map((item) => el('li', '', item)),
    );
    parts.push(el('h4', '', 'Result evidence'), list);
  }
  for (const wake of status.schedules)
    parts.push(
      field(
        'Schedule',
        [
          wake.schedule,
          wake.paused ? 'paused' : (wake.nextFireAt ?? 'no next occurrence'),
        ].join(' · '),
      ),
    );
  for (const wake of status.relayListeners) {
    const batch = wake.pendingBatch;
    parts.push(
      field(
        'Relay listener',
        [
          wake.topic ?? wake.binding,
          ...(wake.failure === null ? [] : [description(wake.failure)]),
          ...(batch === null
            ? []
            : [
                `${description(batch.state)} batch, ${batch.memberCount} events, deadline ${batch.deadline}`,
              ]),
        ].join(' · '),
      ),
    );
  }
  return parts;
}

/** Dedicated Goal Agents share the tab socket and open the existing read-only history. */
export function createGoalAgents(options: {
  readonly root: HTMLElement;
  readonly project: string | null;
  readonly operations: ReturnType<typeof checkedTransport>;
  readonly transport: Transport;
  readonly timeoutMs?: number;
}): Screen {
  const { operations, transport, root } = options;
  const timeoutMs = options.timeoutMs ?? 2000;
  const projectChoice = document.createElement('select');
  const placeholder = new Option('Choose a project', '');
  projectChoice.append(placeholder);
  const refresh = button('reload', 'Refresh Goal Agents');
  const previous = button('page-back', 'Previous page');
  const more = button('page-on', 'Next page');
  const rows = el('div', 'goal-list');
  const detail = el('section', 'goal-detail');
  const history = el('section', 'goal-history');
  detail.tabIndex = -1;
  history.tabIndex = -1;
  const alert = el('p', 'error');
  alert.setAttribute('role', 'alert');
  const state = el('p', '');
  state.setAttribute('role', 'status');
  const pages = el('nav', 'goal-pages');
  pages.setAttribute('aria-label', 'Goal Agent pages');
  pages.append(previous, more);
  root.append(
    el('h2', 'screen-title', 'Goal Agents'),
    labelled('Project', projectChoice),
    refresh,
    alert,
    state,
    rows,
    pages,
    detail,
    history,
  );

  let project = options.project;
  let closed = false;
  let revision = 0;
  let reading: AbortController | undefined;
  let readingHistory: AbortController | undefined;
  let loading = false;
  let page: GoalPage | null = null;
  let cursors: (string | undefined)[] = [undefined];
  let selected: GoalStatus | null = null;
  let writing = false;
  const pending = new Map<string, Intent>();

  function addProject(name: string, label: string) {
    if (
      !Array.from(projectChoice.options).some((choice) => choice.value === name)
    )
      projectChoice.add(new Option(label, name));
  }

  if (project !== null) {
    addProject(project, project);
    projectChoice.value = project;
  }

  function beginRead(): {
    readonly mine: number;
    readonly signal: AbortSignal;
  } {
    reading?.abort();
    reading = new AbortController();
    readingHistory?.abort();
    history.replaceChildren();
    return { mine: ++revision, signal: reading.signal };
  }

  function current(mine: number) {
    return !closed && mine === revision;
  }

  function draw() {
    const index = cursors.length - 1;
    previous.disabled = loading || index === 0;
    more.disabled = loading || page?.next == null;
    refresh.disabled = project === null;
    rows.replaceChildren();
    detail.replaceChildren();
    if (project === null) {
      state.textContent = 'Choose a project to see its Goal Agents.';
      return;
    }
    state.textContent = loading
      ? 'Loading Goal Agents…'
      : page
        ? `Page ${index + 1}`
        : '';
    if (page?.goals.length === 0)
      rows.append(nothing('This project has no Goal Agents on this page.'));
    for (const status of page?.goals ?? []) {
      const row = el('article', 'goal-row');
      row.dataset['goal'] = status.goal;
      row.append(
        el('h3', '', status.objective ?? 'Restricted Goal Agent'),
        ...goalFacts(status),
      );
      const open = button('goal-open', 'Inspect Goal Agent');
      open.setAttribute('aria-label', `Inspect Goal Agent ${status.goal}`);
      open.setAttribute(
        'aria-expanded',
        String(selected?.goal === status.goal),
      );
      open.disabled = loading;
      open.addEventListener('click', () => {
        if (page?.goals.includes(status) && !loading)
          background(inspect(status.goal, '', true));
      });
      row.append(open);
      rows.append(row);
    }
    if (selected === null) return;
    const status = selected;
    detail.append(
      el('h3', '', status.objective ?? 'Restricted Goal Agent'),
      ...goalFacts(status),
    );
    const refreshStatus = button('goal-status', 'Refresh status');
    refreshStatus.disabled = writing;
    refreshStatus.addEventListener('click', () => {
      if (selected === status && !writing) background(inspect(status.goal));
    });
    detail.append(refreshStatus);
    if (!status.readable) return;

    detail.append(...goalEvidence(status));
    const conversation = button(
      'goal-conversation',
      'Open conversation history and TODOs',
    );
    conversation.disabled = writing;
    conversation.addEventListener('click', () => {
      if (selected === status && !writing) background(openConversation(status));
    });
    detail.append(conversation);
    const intent = pending.get(status.goal);
    if (intent) {
      detail.append(
        el(
          'p',
          '',
          'Control outcome pending. Refresh status to check its receipt.',
        ),
        field('Control request', intent.requestId),
      );
      if (intent.receiptMissing) {
        const clear = button('goal-control-clear', 'Clear pending control');
        clear.title =
          'No receipt was found. Clear the local marker after reviewing the current status.';
        clear.addEventListener('click', () => {
          if (
            selected !== status ||
            pending.get(status.goal) !== intent ||
            writing
          )
            return;
          pending.delete(status.goal);
          alert.textContent = '';
          draw();
        });
        detail.append(clear);
      }
    }
    const available: Control[] = [];
    if (pausable.has(status.state)) available.push('pause');
    if (
      status.state === 'PAUSED' ||
      (status.state === 'BLOCKED' && status.reason === 'AGENT_BLOCKED')
    )
      available.push('resume');
    if (!terminal.has(status.state)) available.push('cancel');

    // Resume can explicitly raise either existing bound when the goal has exhausted it.
    const activationLimit = document.createElement('input');
    const modelLimit = document.createElement('input');
    for (const input of [activationLimit, modelLimit]) {
      input.type = 'number';
      input.min = '1';
      input.step = '1';
    }
    activationLimit.max = '1000';
    modelLimit.max = '1000000';
    if (available.includes('resume')) {
      detail.append(
        labelled('New activation limit (optional)', activationLimit),
        labelled('New model-call limit (optional)', modelLimit),
      );
    }
    for (const action of available) {
      const control = button(`goal-${action}`, controls[action]);
      control.disabled = writing || intent !== undefined;
      control.addEventListener('click', () => {
        if (!activationLimit.reportValidity() || !modelLimit.reportValidity())
          return;
        const limits = {
          ...(activationLimit.value === ''
            ? {}
            : { maxActivations: activationLimit.valueAsNumber }),
          ...(modelLimit.value === ''
            ? {}
            : { maxModelCalls: modelLimit.valueAsNumber }),
        };
        background(change(status, action, limits));
      });
      detail.append(control);
    }
  }

  async function loadPage(nextCursors: (string | undefined)[]) {
    const { mine, signal } = beginRead();
    const tier = project;
    selected = null;
    alert.textContent = '';
    if (tier === null) {
      loading = false;
      draw();
      return;
    }
    const after = nextCursors.at(-1);
    loading = true;
    draw();
    try {
      const reply = await operations.ask<'goal.list'>(
        'goal.list',
        {
          project: tier,
          ...(after === undefined ? {} : { after }),
        },
        { signal, timeoutMs },
      );
      if (!current(mine)) return;
      if (reply.code !== 'OK' || !reply.payload)
        throw new Error(reply.said ?? 'Unable to list Goal Agents.');
      page = reply.payload;
      cursors = nextCursors;
    } catch (error) {
      if (!current(mine)) return;
      page = null;
      cursors = [undefined];
      alert.textContent = problemText(error, 'Unable to list Goal Agents.');
    } finally {
      if (current(mine)) {
        loading = false;
        draw();
      }
    }
  }

  async function inspect(goal: string, notice = '', focus = false) {
    const tier = project;
    if (tier === null) return;

    const { mine, signal } = beginRead();
    selected = null;
    alert.textContent = notice;
    draw();
    try {
      const reply = await operations.ask<'goal.status'>(
        'goal.status',
        { project: tier, goal },
        { signal, timeoutMs },
      );
      if (!current(mine)) return;
      if (reply.code !== 'OK' || !reply.payload)
        throw new Error(reply.said ?? 'Unable to read Goal Agent status.');
      const status = reply.payload;
      // Replace stale previews too when the status read narrows disclosure.
      if (page)
        page = {
          ...page,
          goals: page.goals.map((row) => (row.goal === goal ? status : row)),
        };
      selected = status;
      const intent = pending.get(goal);
      if (status.readable && intent)
        await reconcile(status, intent, signal, mine);
    } catch (error) {
      if (!current(mine)) return;
      if (page)
        page = {
          ...page,
          goals: page.goals.filter((row) => row.goal !== goal),
        };
      alert.textContent = problemText(
        error,
        'Unable to read Goal Agent status.',
      );
    } finally {
      if (current(mine)) {
        draw();
        if (focus && selected) detail.focus();
      }
    }
  }

  async function reconcile(
    status: GoalStatus,
    intent: Intent,
    signal: AbortSignal,
    mine: number,
  ) {
    try {
      intent.receiptMissing = false;
      const receipt = await operations.ask<'conversation.goal.get'>(
        'conversation.goal.get',
        {
          conversation: status.conversation,
          requestId: intent.requestId,
        },
        { signal, timeoutMs },
      );
      if (!current(mine)) return;
      if (
        receipt.code === 'OK' &&
        receipt.payload?.goal === status.goal &&
        receipt.payload.version === intent.version + 1 &&
        status.version >= receipt.payload.version
      )
        pending.delete(status.goal);
      else if (receipt.code === 'NOT_FOUND') intent.receiptMissing = true;
    } catch (error) {
      if (current(mine))
        alert.textContent = problemText(
          error,
          'Unable to check the control receipt.',
        );
    }
  }

  async function change(
    status: GoalStatus,
    action: Control,
    limits: {
      readonly maxActivations?: number;
      readonly maxModelCalls?: number;
    },
  ) {
    if (
      closed ||
      writing ||
      selected !== status ||
      !status.readable ||
      pending.has(status.goal)
    )
      return;
    const mine = revision;
    const requestId = globalThis.crypto.randomUUID();
    const payload = {
      goal: status.goal,
      conversation: status.conversation,
      version: status.version,
      requestId,
    };
    pending.set(status.goal, {
      requestId,
      version: status.version,
      receiptMissing: false,
    });
    writing = true;
    draw();
    let notice = '';
    try {
      const reply = await operations.ask<`conversation.goal.${Control}`>(
        `conversation.goal.${action}`,
        {
          ...payload,
          ...(action === 'resume' ? limits : {}),
        },
        { nonBlocking: true, timeoutMs },
      );
      if (
        reply.code === 'OK' &&
        reply.payload?.version === status.version + 1
      ) {
        pending.delete(status.goal);
      } else if (
        reply.code === 'CAPACITY_EXHAUSTED' ||
        reply.code === 'CONFLICT'
      ) {
        pending.delete(status.goal);
        notice = reply.said ?? 'Goal Agent control was refused.';
      } else {
        // The router can withhold results when authority changes after the handler.
        notice = reply.said ?? 'Goal Agent control could not be confirmed.';
      }
    } catch (error) {
      if (
        error instanceof ConnectionFault &&
        error.delivery === 'NOT_SUBMITTED'
      ) {
        pending.delete(status.goal);
        notice = problemText(
          error,
          'Goal Agent control could not be submitted.',
        );
      }
    } finally {
      writing = false;
      if (
        !closed &&
        project === status.project &&
        (current(mine) || selected?.goal === status.goal)
      )
        await inspect(status.goal, notice);
      else if (!closed) draw();
    }
  }

  async function openConversation(status: GoalStatus) {
    readingHistory?.abort();
    readingHistory = new AbortController();
    const { signal } = readingHistory;
    const mine = revision;
    history.replaceChildren();
    const trajectoryHost = el('div', 'goal-trajectory');
    const tasks = el('section', 'goal-todos');
    tasks.append(el('h4', '', 'TODOs'));
    history.append(el('h3', '', status.conversation), trajectoryHost, tasks);
    const trajectory = createTrajectory({
      root: trajectoryHost,
      transport,
      ownChooser: false,
    });
    history.focus();
    const historyRead = trajectory.show(status.conversation);
    try {
      const reply = await operations.ask<'todos.read'>(
        'todos.read',
        { conversation: status.conversation },
        { signal, timeoutMs },
      );
      if (!current(mine) || signal.aborted) return;
      if (reply.code !== 'OK' || !reply.payload)
        throw new Error(reply.said ?? 'Unable to read conversation TODOs.');
      if (reply.payload.length === 0) {
        tasks.append(nothing('This conversation has no TODOs.'));
      } else {
        const list = el('ul', '');
        for (const todo of reply.payload)
          list.append(
            el(
              'li',
              '',
              `${todo.status} · ${todo.text}${todo.summary ? ` · ${todo.summary}` : ''}`,
            ),
          );
        tasks.append(list);
      }
    } catch (error) {
      if (current(mine) && !signal.aborted)
        tasks.append(
          el(
            'p',
            'error',
            problemText(error, 'Unable to read conversation TODOs.'),
          ),
        );
    } finally {
      await historyRead;
    }
  }

  projectChoice.addEventListener('change', () => {
    project = projectChoice.value || null;
    page = null;
    cursors = [undefined];
    background(loadPage(cursors));
  });
  refresh.addEventListener('click', () => background(loadPage([undefined])));
  previous.addEventListener('click', () => {
    if (!loading && cursors.length > 1)
      background(loadPage(cursors.slice(0, -1)));
  });
  more.addEventListener('click', () => {
    const anchor = page?.next;
    if (!loading && anchor != null) background(loadPage([...cursors, anchor]));
  });
  draw();
  return {
    element: () => root,
    async load() {
      try {
        const projects = await transport.get('/v1/projects');
        if (closed) return;
        for (const entry of projects ?? []) addProject(entry.name, entry.name);
      } catch (error) {
        if (closed) return;
        if (project === null) {
          alert.textContent = problemText(error, 'Unable to load projects.');
          return;
        }
      }
      if (!closed) await loadPage([undefined]);
    },
    destroy() {
      closed = true;
      reading?.abort();
      readingHistory?.abort();
      history.replaceChildren();
    },
  };
}
