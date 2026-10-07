import type {
  InboxItem,
  InboxPage,
} from '../../../sdk/typescript/src/operations/administrative-replies.ts';
import { background } from '../background';
import type { EventStream, EventStreamOptions } from '../events';
import { socketWorkRecords, WorkRefused } from '../work';
import { button, el, nothing, problemText } from './dom';
import { reconciliation } from './reconciliation';
import { recordLinks } from './record-link';
import type { Screen } from './screen';
import { asInboxChanged } from './wire';

export interface InboxOptions {
  readonly root: HTMLElement;
  readonly openStream: (options: EventStreamOptions) => EventStream;
  readonly session: string;
  readonly pollMs?: number | null;
}

/** Retained account deliveries are read from the server, including after reload.
 * A read receipt is explicit user input; polling never marks an item read. */
export function createInbox(options: InboxOptions): Screen {
  const element = el('section', 'screen inbox');
  const heading = el('h2', 'screen-title', 'inbox');
  const reload = button('reload', 'Refresh inbox');
  const head = el('header', 'screen-head');
  head.append(heading, reload);
  const body = el('div', 'screen-body');
  const status = el('p', 'inbox-status');
  status.setAttribute('role', 'status');
  const error = el('p', 'trouble');
  error.setAttribute('role', 'alert');
  error.hidden = true;
  const rows = el('div', 'inbox-rows');
  const previous = button('previous', 'Previous inbox page');
  const next = button('next', 'Next inbox page');
  const paging = el('div', 'inbox-paging');
  paging.append(previous, next);
  body.append(status, error, rows, paging);
  element.append(head, body);
  options.root.replaceChildren(element);
  let stream: EventStream | null = null;
  let stopped = false,
    offset = 0;
  let active = true;
  let epoch = 0;
  let fresh = false;
  let held: InboxPage | null = null;
  const limit = 50;
  const blocked = new Map<string, string>();
  const refresh = reconciliation({
    available: canRead,
    pollMs: options.pollMs === undefined ? 5000 : options.pollMs,
    async read() {
      const socket = stream;
      if (socket === null) return;
      const pageOffset = offset;
      const stamp = epoch;
      fresh = false;
      disableReceipts();
      element.setAttribute('aria-busy', 'true');
      try {
        const page = await socketWorkRecords(socket).inbox(pageOffset, limit);
        if (!canRead() || stamp !== epoch || pageOffset !== offset) return;
        held = page;
        fresh = true;
        error.hidden = true;
        draw();
        status.textContent = `Inbox page ${pageOffset / limit + 1}. Saved deliveries are reconciled from server state.`;
      } catch (problem) {
        if (!canRead() || stamp !== epoch || pageOffset !== offset) return;
        error.textContent = problemText(
          problem,
          'The inbox could not be read.',
        );
        error.dataset['trouble'] = '';
        error.hidden = false;
        status.textContent =
          held === null
            ? 'No inbox snapshot is available. Refresh to retry.'
            : 'The displayed inbox snapshot may be stale. Refresh to retry.';
      } finally {
        element.setAttribute('aria-busy', 'false');
      }
    },
  });

  function draw(): void {
    if (held === null) return;
    heading.textContent =
      held.unread === 0 ? 'inbox' : `inbox · ${held.unread} unread`;
    rows.replaceChildren(
      ...(held.items.length === 0
        ? [nothing('No saved deliveries in this inbox page.')]
        : held.items.map(row)),
    );
    previous.disabled = offset === 0;
    next.disabled = held.items.length < limit;
  }

  function row(item: InboxItem): HTMLElement {
    const article = el('article', 'inbox-item');
    article.dataset['item'] = item.id;
    article.dataset['unread'] = String(item.readAt === null);
    const meta = el(
      'div',
      'inbox-meta',
      `${item.arrivedAt} · ${item.ending ?? item.kind}`,
    );
    const answer = el(
      'pre',
      'inbox-answer',
      item.answer ?? item.about ?? 'No result text was supplied.',
    );
    const mark = button('mark-read', 'mark read');
    mark.disabled =
      !canRead() || !fresh || item.readAt !== null || blocked.has(item.id);
    mark.addEventListener('click', () => background(markRead(item.id)));
    article.append(meta, answer, recordLinks(item.conversation), mark);
    const message = blocked.get(item.id);
    if (message !== undefined) {
      const note = el('p', 'trouble', message);
      note.dataset['trouble'] = '';
      article.append(note);
    }
    return article;
  }

  async function markRead(id: string): Promise<void> {
    if (!canRead() || !fresh || stream === null || blocked.has(id)) return;
    blocked.set(id, 'Sending read receipt…');
    draw();
    try {
      await socketWorkRecords(stream).markRead(id);
      if (stopped) return;
      blocked.set(id, 'Read receipt recorded. Refreshing…');
    } catch (problem) {
      if (stopped) return;
      if (problem instanceof WorkRefused) {
        blocked.delete(id);
        status.textContent = problem.message;
        // A confirmed refusal permits an explicit retry and remains on the row.
        draw();
        const article = [
          ...rows.querySelectorAll<HTMLElement>('[data-item]'),
        ].find((node) => node.dataset['item'] === id);
        const note = el('p', 'trouble', problem.message);
        note.dataset['trouble'] = '';
        article?.append(note);
        return;
      }
      blocked.set(
        id,
        problemText(problem, 'Read receipt delivery is uncertain.') +
          ' Delivery is uncertain; the receipt was not replayed.',
      );
      draw();
      return;
    }
    await refresh.refresh();
  }

  reload.addEventListener('click', () => background(refresh.refresh()));
  previous.addEventListener('click', () => {
    pause();
    offset = Math.max(0, offset - limit);
    background(refresh.refresh());
  });
  next.addEventListener('click', () => {
    pause();
    offset += limit;
    background(refresh.refresh());
  });

  function canRead(): boolean {
    return (
      !stopped &&
      active &&
      options.root.ownerDocument.visibilityState !== 'hidden' &&
      stream?.status().state === 'open' &&
      !stream.status().signedOut
    );
  }

  function disableReceipts(): void {
    for (const control of rows.querySelectorAll<HTMLButtonElement>('button'))
      control.disabled = true;
  }

  /** Page, visibility and connection changes invalidate in-flight reads even
   * when a later page has the same offset. Receipts remain explicit effects. */
  function pause(): void {
    ++epoch;
    fresh = false;
    disableReceipts();
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

  return {
    element: () => element,
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
        const waiting = el('p', 'inbox-connecting', 'connecting…');
        waiting.dataset['connecting'] = '';
        rows.replaceChildren(waiting);
        stream = options.openStream({
          session: options.session,
          onEvent: (frame) => {
            if (asInboxChanged(frame) !== null) background(refresh.refresh());
          },
          onStatus: (next) => {
            if (stopped) return;
            pause();
            if (next.state === 'open' && !next.signedOut)
              background(refresh.refresh());
          },
        });
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
    },
  };
}
