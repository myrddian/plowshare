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
import { reconciliation } from './reconciliation';

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
  const receiptStatus = el('div', 'approval-receipt');
  receiptStatus.setAttribute('role', 'status');
  const selectionStatus = el('p', 'approval-selection');
  selectionStatus.setAttribute('role', 'status');
  selectionStatus.tabIndex = -1;
  selectionStatus.hidden = true;
  const error = el('p', 'trouble');
  error.setAttribute('role', 'alert');
  error.hidden = true;
  const body = el('div', 'screen-body');
  const rows = el('div', 'approval-rows');
  const previous = button('previous', 'Previous approvals');
  const next = button('next', 'Next approvals');
  head.append(el('h2', 'screen-title', 'approvals'), reload, previous, next);
  previous.addEventListener('click', () => {
    selected = null;
    offset = Math.max(0, offset - windowSize);
    draw();
  });
  next.addEventListener('click', () => {
    selected = null;
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
    selectionStatus,
    receiptStatus,
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
  let selected: string | null = null;
  let focusSelection = false;
  let epoch = 0;
  let fresh = false;
  let held: readonly ApprovalView[] = [];
  // IDs remain blocked across redraws. An uncertain decision cannot become a
  // fresh button merely because a poll still sees the original pending row.
  const blocked = new Map<string, string>();
  const refresh = reconciliation({
    available: canRead,
    pollMs: options.pollMs === undefined ? 5000 : options.pollMs,
    read,
  });

  function draw(): void {
    const pending = held.filter((request) => request.state === 'asked');
    const position = pending.findIndex((request) => request.id === selected);
    if (selected !== null && fresh && position >= 0)
      offset = Math.floor(position / windowSize) * windowSize;
    selectionStatus.hidden = selected === null;
    if (selected !== null) {
      selectionStatus.textContent = !fresh
        ? `Request ${selected} has not been verified against current server state. Refresh or reconnect before deciding.`
        : position < 0
          ? `Request ${selected} is not in your current pending approval list. Refresh or choose another pending request.`
          : `Selected request ${selected}. Review its command and context before answering.`;
    }
    const focused = options.root.ownerDocument.activeElement;
    const focusedId = focused?.matches('.approval-head')
      ? focused.closest<HTMLElement>('[data-approval]')?.dataset['approval']
      : undefined;
    offset = Math.min(
      offset,
      Math.max(0, Math.ceil(pending.length / windowSize) - 1) * windowSize,
    );
    previous.disabled = offset === 0;
    next.disabled = offset + windowSize >= pending.length;
    rows.replaceChildren(
      ...(pending.length === 0
        ? [
            fresh
              ? nothing('No approvals are waiting for your account.')
              : el('p', 'note', 'Connecting to read current approvals…'),
          ]
        : pending.slice(offset, offset + windowSize).map(row)),
    );
    if (focusedId !== undefined)
      findCard(focusedId)
        ?.querySelector<HTMLElement>('h3')
        ?.focus({ preventScroll: true });
    if (focusSelection && fresh && selected !== null) {
      const heading = findCard(selected)?.querySelector<HTMLElement>('h3');
      (heading ?? selectionStatus).focus();
      focusSelection = false;
    }
  }

  function findCard(id: string): HTMLElement | undefined {
    return [...rows.querySelectorAll<HTMLElement>('[data-approval]')].find(
      (card) => card.dataset['approval'] === id,
    );
  }

  function row(request: ApprovalView): HTMLElement {
    const card = el('article', 'approval');
    card.dataset['approval'] = request.id;
    card.dataset['selected'] = String(request.id === selected);
    const title = el('h3', 'approval-head', `${request.agent} · ${request.id}`);
    title.tabIndex = -1;
    card.append(title);
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
      control.disabled = !canRead() || !fresh || blocked.has(request.id);
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
    if (!canRead() || !fresh || client === null || blocked.has(id)) return;
    if (!held.some((request) => request.id === id && request.state === 'asked'))
      return;
    blocked.set(id, 'Sending decision…');
    draw();
    try {
      const receipt = await client.answer(id, decision);
      if (stopped) return;
      blocked.set(id, 'Decision recorded. Refreshing pending requests…');
      receiptStatus.replaceChildren(
        el(
          'p',
          '',
          `${id}: ${receipt.state}. ` +
            (receipt.busy
              ? 'The decision is recorded; the conversation is busy.'
              : receipt.job === null
                ? 'The server accepted the decision.'
                : `Continuation job: ${receipt.job}.`) +
            (receipt.note === null ? '' : ` ${receipt.note}`),
        ),
        recordLinks(null, receipt.job),
      );
    } catch (problem) {
      if (stopped) return;
      if (problem instanceof ApprovalRefused) {
        blocked.delete(id);
        fresh = false;
        receiptStatus.replaceChildren(el('p', 'trouble', problem.message));
      } else {
        const note =
          'Delivery is uncertain. This decision was not replayed. Refresh to inspect pending requests before taking further action.';
        blocked.set(id, note);
        receiptStatus.replaceChildren(el('p', 'trouble', `${id}: ${note}`));
      }
      draw();
    }
    await refresh.refresh();
  }

  async function read(): Promise<void> {
    const authority = client;
    if (authority === null) return;
    const stamp = epoch;
    fresh = false;
    reload.disabled = true;
    element.setAttribute('aria-busy', 'true');
    status.textContent =
      held.length === 0
        ? 'Reading current pending approvals…'
        : 'Refreshing approvals. The displayed snapshot may be stale.';
    draw();
    try {
      const snapshot = await authority.list();
      if (!canRead() || stamp !== epoch) return;
      held = snapshot;
      fresh = true;
      error.hidden = true;
      draw();
      status.textContent = `${held.filter((request) => request.state === 'asked').length} pending approvals reconciled from server state.`;
    } catch (problem) {
      if (!canRead() || stamp !== epoch) return;
      // Preserve context for inspection, with decisions disabled. Failure to
      // verify the selected request is never evidence that it was answered.
      error.textContent = problemText(problem, 'Approvals could not be read.');
      error.hidden = false;
      status.textContent =
        'Current approval authority could not be read. Refresh to retry; cached requests may be stale.';
    } finally {
      if (!stopped) {
        reload.disabled = false;
        element.setAttribute('aria-busy', 'false');
      }
    }
  }

  function canRead(): boolean {
    return (
      !stopped &&
      active &&
      options.root.ownerDocument.visibilityState !== 'hidden' &&
      stream?.status().state === 'open' &&
      !stream.status().signedOut
    );
  }

  /** Navigation, tab visibility and socket loss invalidate authorization hints
   * immediately. A fresh retained read is required before another decision. */
  function pause(): void {
    ++epoch;
    fresh = false;
    status.textContent =
      'The displayed approvals may be stale. Reconnect or refresh before deciding.';
    draw();
  }

  function visibilityChanged(): void {
    pause();
    refresh.setActive(
      active && options.root.ownerDocument.visibilityState !== 'hidden',
    );
  }
  options.root.ownerDocument.addEventListener(
    'visibilitychange',
    visibilityChanged,
  );

  reload.addEventListener('click', () => background(refresh.refresh()));
  return {
    element: () => element,
    async showRecord(id) {
      if (stopped) return;
      selected = id;
      focusSelection = true;
      // A deep link selects context, never authority or an answer. Invalidate
      // any older snapshot and await the shared, coalesced read owner.
      pause();
      await refresh.refresh();
    },
    setActive(next) {
      if (stopped || active === next) return;
      active = next;
      pause();
      refresh.setActive(
        active && options.root.ownerDocument.visibilityState !== 'hidden',
      );
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
            if (asJobEvent(frame) !== null) background(refresh.refresh());
          },
          onStatus: (next) => {
            if (stopped) return;
            pause();
            if (next.state === 'open' && !next.signedOut)
              background(refresh.refresh());
          },
        });
        client = socketApprovals(stream);
      }
      await refresh.refresh();
    },
    destroy() {
      stopped = true;
      ++epoch;
      options.root.ownerDocument.removeEventListener(
        'visibilitychange',
        visibilityChanged,
      );
      refresh.stop();
      stream?.close();
      stream = null;
      client = null;
    },
  };
}
