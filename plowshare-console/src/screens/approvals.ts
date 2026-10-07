import type { ApprovalView } from '../../../sdk/typescript/src/operations/administrative-replies.ts';
import {
  ApprovalRefused,
  socketApprovals,
  type ApprovalDecision,
  type Approvals,
} from '../approvals';
import { background } from '../background';
import type { EventStream, EventStreamOptions } from '../events';
import { asJobEvent } from '../repl/wire';
import { button, el, field, nothing, problemText } from './dom';
import type { Screen } from './screen';
import { recordLinks } from './record-link';

export interface ApprovalsOptions {
  readonly root: HTMLElement;
  readonly session: string;
  readonly openStream: (options: EventStreamOptions) => EventStream;
  /** Periodic reconciliation covers requests raised outside this tab. */
  readonly pollMs?: number | null;
}

/** Pending work across the signed-in account, independent of the selected chat.
 * Pushes only prompt reads. Lost decisions stay blocked while reads reconcile;
 * reconnecting never resubmits them. */
export function createApprovals(options: ApprovalsOptions): Screen {
  const element = el('section', 'screen pending-approvals');
  const head = el('header', 'screen-head');
  const reload = button('reload', 'Refresh approvals');
  const status = el('p', 'approval-status');
  status.setAttribute('role', 'status');
  const error = el('p', 'trouble');
  error.setAttribute('role', 'alert');
  error.hidden = true;
  const body = el('div', 'screen-body');
  const rows = el('div', 'approval-rows');
  const previous = button('previous', 'Previous approvals');
  const next = button('next', 'Next approvals');
  head.append(el('h2', 'screen-title', 'approvals'), reload, previous, next);
  previous.addEventListener('click', () => {
    offset = Math.max(0, offset - windowSize);
    draw();
  });
  next.addEventListener('click', () => {
    offset += windowSize;
    draw();
  });
  body.append(
    el(
      'p',
      'note',
      'Pending requests across your account. Review the command and its context before answering.',
    ),
    status,
    error,
    rows,
  );
  element.append(head, body);
  options.root.replaceChildren(element);

  let stream: EventStream | null = null;
  let client: Approvals | null = null;
  let stopped = false;
  let active = true;
  let offset = 0;
  const windowSize = 30;
  let reading = false;
  let asked = false;
  let held: readonly ApprovalView[] = [];
  let timer: ReturnType<typeof setTimeout> | null = null;
  const pollMs = options.pollMs === undefined ? 5000 : options.pollMs;
  // IDs remain blocked across redraws. An uncertain decision cannot become a
  // fresh button merely because a poll still sees the original pending row.
  const blocked = new Map<string, string>();

  function draw(): void {
    const pending = held.filter((request) => request.state === 'asked');
    offset = Math.min(
      offset,
      Math.max(0, Math.ceil(pending.length / windowSize) - 1) * windowSize,
    );
    previous.disabled = offset === 0;
    next.disabled = offset + windowSize >= pending.length;
    rows.replaceChildren(
      ...(pending.length === 0
        ? [nothing('No approvals are waiting for your account.')]
        : pending.slice(offset, offset + windowSize).map(row)),
    );
  }

  function row(request: ApprovalView): HTMLElement {
    const card = el('article', 'approval');
    card.dataset['approval'] = request.id;
    card.append(el('h3', 'approval-head', request.agent));
    // JSON argv preserves argument boundaries, including spaces and quotes.
    for (const command of request.commands ?? [request.command]) {
      card.append(el('pre', 'approval-command', JSON.stringify(command)));
    }
    card.append(
      field('request', request.id),
      field('conversation', request.conversation),
      field('asked in', request.askedIn),
      field('runs on', request.side),
      field('working directory', request.cwd),
      field('reason', request.reason),
      field('requested at', request.createdAt),
    );
    if (request.judged !== null)
      card.append(field('command review', request.judged));
    const actions = el('div', 'approval-actions');
    for (const [decision, label] of [
      ['once', 'Allow once'],
      ['deny', 'Deny'],
    ] as const) {
      const control = button('approval-decision', label);
      control.disabled = blocked.has(request.id);
      control.addEventListener('click', () =>
        background(answer(request.id, decision)),
      );
      actions.append(control);
    }
    card.append(recordLinks(request.askedIn || request.conversation), actions);
    const note = blocked.get(request.id);
    if (note !== undefined) card.append(el('p', 'approval-note', note));
    return card;
  }

  async function answer(id: string, decision: ApprovalDecision): Promise<void> {
    if (stopped || client === null || blocked.has(id)) return;
    blocked.set(id, 'Sending decision…');
    draw();
    try {
      const receipt = await client.answer(id, decision);
      if (stopped) return;
      blocked.set(id, 'Decision recorded. Refreshing pending requests…');
      status.textContent =
        `${id}: ${receipt.state}. ` +
        (receipt.busy
          ? 'The decision is recorded; the conversation is busy.'
          : receipt.job === null
            ? 'The server accepted the decision.'
            : `Continuation job: ${receipt.job}.`) +
        (receipt.note === null ? '' : ` ${receipt.note}`);
    } catch (problem) {
      if (stopped) return;
      if (problem instanceof ApprovalRefused) {
        blocked.delete(id);
        status.textContent = problem.message;
      } else {
        const note =
          'Delivery is uncertain. This decision was not replayed. Refresh to inspect pending requests before taking further action.';
        blocked.set(id, note);
        status.textContent = note;
      }
      draw();
    }
    await refresh();
  }

  async function refresh(): Promise<void> {
    if (
      stopped ||
      !active ||
      client === null ||
      stream?.status().state !== 'open'
    )
      return;
    if (reading) {
      asked = true;
      return;
    }
    reading = true;
    reload.disabled = true;
    element.setAttribute('aria-busy', 'true');
    try {
      do {
        asked = false;
        try {
          const snapshot = await client.list();
          if (stopped) return;
          held = snapshot;
          error.hidden = true;
          draw();
        } catch (problem) {
          if (stopped) return;
          // Keep the previous rows. An unreadable snapshot is not an empty queue.
          error.textContent = problemText(
            problem,
            'Approvals could not be read.',
          );
          error.hidden = false;
        }
      } while (asked && !stopped && stream.status().state === 'open');
    } finally {
      reading = false;
      reload.disabled = false;
      element.setAttribute('aria-busy', 'false');
    }
  }

  function poll(): void {
    if (stopped || !active || timer !== null || pollMs === null) return;
    timer = setTimeout(() => {
      timer = null;
      background(refresh().finally(poll));
    }, pollMs);
  }

  reload.addEventListener('click', () => background(refresh()));
  return {
    element: () => element,
    setActive(next) {
      active = next;
      if (timer !== null) clearTimeout(timer);
      timer = null;
      if (active && !stopped) background(refresh().finally(poll));
    },
    async load() {
      if (stopped) return;
      if (stream === null) {
        rows.replaceChildren(
          el('p', 'note', 'Connecting to read pending approvals…'),
        );
        stream = options.openStream({
          session: options.session,
          onEvent: (frame) => {
            if (asJobEvent(frame) !== null) background(refresh());
          },
          onStatus: (next) => {
            if (next.state === 'open') background(refresh());
          },
        });
        client = socketApprovals(stream);
      }
      await refresh();
      poll();
    },
    destroy() {
      stopped = true;
      if (timer !== null) clearTimeout(timer);
      stream?.close();
      stream = null;
      client = null;
    },
  };
}
