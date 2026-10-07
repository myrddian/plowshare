import type {
  InboxItem,
  InboxPage,
} from '../../../sdk/typescript/src/operations/administrative-replies.ts';
import { background } from '../background';
import type { EventStream, EventStreamOptions } from '../events';
import { socketWorkRecords, WorkRefused } from '../work';
import { button, el, field, labelled, nothing, problemText } from './dom';
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
  const filter = document.createElement('select');
  filter.className = 'inbox-filter';
  for (const [value, label] of [
    ['all', 'All deliveries'],
    ['unread', 'Unread deliveries'],
  ] as const) {
    const option = document.createElement('option');
    option.value = value;
    option.textContent = label;
    filter.append(option);
  }
  const reader = el('section', 'inbox-reader');
  reader.setAttribute('aria-label', 'Selected delivery');
  reader.append(nothing('Select a delivery to inspect its saved result.'));
  const layout = el('div', 'inbox-layout');
  layout.append(rows, reader);
  const previous = button('previous', 'Previous inbox page');
  const next = button('next', 'Next inbox page');
  const paging = el('div', 'inbox-paging');
  paging.append(previous, next);
  previous.disabled = true;
  next.disabled = true;
  body.append(labelled('Show', filter), status, error, layout, paging);
  element.append(head, body);
  options.root.replaceChildren(element);
  let stream: EventStream | null = null;
  let stopped = false,
    offset = 0;
  let active = true;
  let epoch = 0;
  let fresh = false;
  let held: InboxPage | null = null;
  let selected: string | null = null;
  let unreadOnly = false;
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
      status.textContent =
        held === null
          ? 'Reading saved deliveries…'
          : 'Refreshing saved deliveries. The displayed snapshot may be stale.';
      element.setAttribute('aria-busy', 'true');
      try {
        const page = await socketWorkRecords(socket).inbox(
          pageOffset,
          limit,
          unreadOnly,
        );
        if (!canRead() || stamp !== epoch || pageOffset !== offset) return;
        held = page;
        for (const item of page.items) {
          if (item.readAt !== null) blocked.delete(item.id);
        }
        if (selected === null) selected = page.items[0]?.id ?? null;
        fresh = true;
        error.hidden = true;
        draw();
        status.textContent = `${unreadOnly ? 'Unread' : 'All'} deliveries · page ${pageOffset / limit + 1}. Saved deliveries are reconciled from server state.`;
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
    const scrollTop = rows.scrollTop;
    const focused = options.root.ownerDocument.activeElement;
    const focusedRow =
      focused instanceof HTMLElement && focused.matches('.inbox-select')
        ? focused.closest<HTMLElement>('[data-item]')?.dataset['item']
        : undefined;
    heading.textContent =
      held.unread === 0 ? 'inbox' : `inbox · ${held.unread} unread`;
    rows.replaceChildren(
      ...(held.items.length === 0
        ? [
            nothing(
              unreadOnly
                ? 'No unread deliveries in this page. Choose All deliveries to inspect saved results.'
                : 'No saved deliveries in this inbox page.',
            ),
          ]
        : held.items.map(row)),
    );
    rows.scrollTop = scrollTop;
    previous.disabled = offset === 0;
    next.disabled = held.items.length < limit;
    drawReader();
    if (focusedRow !== undefined) {
      const row = [...rows.querySelectorAll<HTMLElement>('[data-item]')].find(
        (node) => node.dataset['item'] === focusedRow,
      );
      row
        ?.querySelector<HTMLButtonElement>('.inbox-select')
        ?.focus({ preventScroll: true });
    }
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
    const inspect = button('inbox-select', `Inspect ${item.id}`);
    inspect.setAttribute('aria-pressed', String(selected === item.id));
    inspect.addEventListener('click', () => {
      selected = item.id;
      draw();
      reader.querySelector<HTMLElement>('h3')?.focus();
    });
    // Only summaries are repeated across the page. The reader keeps the full
    // retained text for one selection without performing a receipt mutation.
    const text = item.answer ?? item.about ?? 'No result text was supplied.';
    const preview = el(
      'p',
      'inbox-preview',
      text.length > 240 ? `${text.slice(0, 240)}…` : text,
    );
    article.append(
      meta,
      el('p', 'inbox-read-state', item.readAt === null ? 'Unread' : 'Read'),
      preview,
      inspect,
    );
    return article;
  }

  function drawReader(): void {
    const item = held?.items.find((item) => item.id === selected);
    const answer = reader.querySelector<HTMLElement>('.inbox-answer');
    const scrollTop =
      reader.dataset['record'] === item?.id ? (answer?.scrollTop ?? 0) : 0;
    const focused = options.root.ownerDocument.activeElement;
    // Polling must not steal keyboard focus or reset a long result's reading
    // position. Restore only the same control on the same retained selection.
    const focusSelector =
      reader.dataset['record'] === item?.id && reader.contains(focused)
        ? ['h3', '.mark-read', 'a'].find((selector) =>
            focused?.matches(selector),
          )
        : undefined;
    if (item === undefined) {
      delete reader.dataset['record'];
      reader.replaceChildren(
        nothing(
          selected === null
            ? 'Select a delivery to inspect its saved result.'
            : 'The selected delivery is no longer in this page. Select another delivery or change the filter.',
        ),
      );
      return;
    }
    reader.dataset['record'] = item.id;
    const title = el('h3', 'inbox-reader-title', `Delivery ${item.id}`);
    title.tabIndex = -1;
    const mark = button('mark-read', 'Mark read');
    mark.disabled =
      !canRead() || !fresh || item.readAt !== null || blocked.has(item.id);
    mark.addEventListener('click', () => background(markRead(item.id)));
    reader.replaceChildren(
      title,
      field('kind', item.kind),
      field('outcome', item.ending ?? 'Not supplied'),
      field('arrived at', item.arrivedAt),
      field('read at', item.readAt ?? 'Unread'),
      ...(item.firing === null ? [] : [field('firing', item.firing)]),
      recordLinks(item.conversation),
      el(
        'pre',
        'inbox-answer',
        item.answer ?? item.about ?? 'No result text was supplied.',
      ),
      mark,
    );
    const message = blocked.get(item.id);
    if (message !== undefined) {
      const note = el('p', 'trouble', message);
      note.dataset['trouble'] = '';
      reader.append(note);
    }
    const result = reader.querySelector<HTMLElement>('.inbox-answer');
    if (result !== null) result.scrollTop = scrollTop;
    if (focusSelector !== undefined)
      reader
        .querySelector<HTMLElement>(focusSelector)
        ?.focus({ preventScroll: true });
  }

  async function markRead(id: string): Promise<void> {
    if (!canRead() || !fresh || stream === null || blocked.has(id)) return;
    if (!held?.items.some((item) => item.id === id && item.readAt === null))
      return;
    blocked.set(id, 'Sending read receipt…');
    draw();
    try {
      const receipt = await socketWorkRecords(stream).markRead(id);
      if (stopped) return;
      blocked.set(
        id,
        receipt.marked === 0
          ? 'The server did not change an unread delivery. Refresh to inspect current state.'
          : 'Read receipt accepted. Waiting for reconciled read state.',
      );
    } catch (problem) {
      if (stopped) return;
      if (problem instanceof WorkRefused) {
        blocked.delete(id);
        // A refused receipt invalidates cached authority. Only a successful
        // authorized read can permit another explicit attempt.
        pause();
        error.textContent = `${problem.message} Refresh to read current inbox state before retrying.`;
        error.dataset['trouble'] = '';
        error.hidden = false;
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
  function changePage(nextOffset: number): void {
    pause();
    offset = nextOffset;
    selected = null;
    held = null;
    heading.textContent = 'inbox';
    error.hidden = true;
    status.textContent = 'Waiting to read this inbox page from the server.';
    rows.replaceChildren(nothing('Reading this inbox page…'));
    drawReader();
    previous.disabled = true;
    next.disabled = true;
    background(refresh.refresh());
  }
  filter.addEventListener('change', () => {
    unreadOnly = filter.value === 'unread';
    changePage(0);
  });
  previous.addEventListener('click', () => {
    changePage(Math.max(0, offset - limit));
  });
  next.addEventListener('click', () => {
    changePage(offset + limit);
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
    for (const control of reader.querySelectorAll<HTMLButtonElement>(
      '.mark-read',
    ))
      control.disabled = true;
  }

  /** Page, visibility and connection changes invalidate in-flight reads even
   * when a later page has the same offset. Receipts remain explicit effects. */
  function pause(): void {
    ++epoch;
    fresh = false;
    disableReceipts();
    status.textContent =
      held === null
        ? 'Waiting for a connection to read saved deliveries.'
        : 'The displayed inbox snapshot may be stale. Reconnect or refresh to read current state.';
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
