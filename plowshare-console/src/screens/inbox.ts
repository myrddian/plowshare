import { background } from '../background.ts';
import type { EventStream, EventStreamOptions } from '../events';
import { button, el, trouble } from './dom';
import type { Screen } from './screen';
import { asInboxChanged, type InboxItemView, type InboxPage } from './wire';

export interface InboxOptions {
  readonly root: HTMLElement;
  readonly openStream: (options: EventStreamOptions) => EventStream;
  readonly session: string;
}

/**
 * INBOX: what scheduled and event-started runs left for the signed-in account. The console's
 * first screen spoken entirely over frames: inbox.list, inbox.read, and the inbox.changed push.
 */
export function createInbox(options: InboxOptions): Screen {
  const element = el('section', 'inbox');
  options.root.append(element);
  let stream: EventStream | null = null;
  let stopped = false;

  async function refresh(): Promise<void> {
    if (stream === null || stopped) {
      return;
    }
    let outcome;
    try {
      outcome = await stream.ask('inbox.list', { limit: 50 });
    } catch (problem) {
      element.replaceChildren(
        trouble(
          problem instanceof Error
            ? problem.message
            : 'The inbox could not be read.',
        ),
      );
      return;
    }
    if (outcome.code !== 'OK') {
      element.replaceChildren(
        trouble(outcome.said ?? 'The inbox could not be read.'),
      );
      return;
    }
    draw(outcome.payload as InboxPage);
  }

  /** "Waiting on the socket," rather than the trouble a premature `ask` would show. */
  function showConnecting(): void {
    const waiting = el('p', 'inbox-connecting', 'connecting…');
    waiting.dataset['connecting'] = '';
    element.replaceChildren(waiting);
  }

  /**
   * Replace any trouble already shown on one item's row with this one, rather
   * than stacking a fresh paragraph under every failed retry.
   */
  function showItemTrouble(article: HTMLElement, text: string): void {
    article.querySelector('[data-trouble]')?.remove();
    article.append(trouble(text));
  }

  function draw(page: InboxPage): void {
    const heading = el('h2', 'inbox-heading');
    heading.textContent =
      page.unread === 0 ? 'inbox' : `inbox · ${page.unread} unread`;
    if (page.items.length === 0) {
      const empty = el('p', 'inbox-empty');
      empty.textContent =
        'Nothing has arrived. Scheduled runs with no conversation land here.';
      element.replaceChildren(heading, empty);
      return;
    }
    element.replaceChildren(heading, ...page.items.map(row));
  }

  function row(item: InboxItemView): HTMLElement {
    const article = el('article', 'inbox-item');
    article.dataset['item'] = item.id;
    article.dataset['unread'] = String(item.readAt === null);
    const meta = el('div', 'inbox-meta');
    meta.textContent = `${item.arrivedAt} · ${item.ending} · ${item.conversation}`;
    const answer = el('pre', 'inbox-answer');
    answer.textContent = item.answer;
    const open = button('mark-read', 'mark read');
    open.addEventListener('click', () => {
      open.disabled = true;
      background(
        (async (): Promise<void> => {
          let outcome;
          try {
            if (stream === null) {
              throw new Error(
                'the event socket is not open; "inbox.read" was not sent',
              );
            }
            outcome = await stream.ask('inbox.read', { items: [item.id] });
          } catch (problem) {
            open.disabled = false;
            showItemTrouble(
              article,
              problem instanceof Error
                ? problem.message
                : 'That item could not be marked read.',
            );
            return;
          }
          if (outcome.code !== 'OK') {
            open.disabled = false;
            showItemTrouble(
              article,
              outcome.said ?? 'That item could not be marked read.',
            );
            return;
          }
          await refresh();
        })(),
      );
    });
    open.disabled = item.readAt !== null;
    article.append(meta, answer, open);
    return article;
  }

  return {
    element: () => element,
    async load(): Promise<void> {
      if (stream === null && !stopped) {
        stream = options.openStream({
          session: options.session,
          onEvent: (frame) => {
            if (asInboxChanged(frame) !== null) background(refresh());
          },
          onStatus: (status) => {
            if (status.state === 'open') background(refresh());
          },
        });
      }
      // A freshly opened socket reports `connecting`, and `ask` rejects
      // immediately on anything but `open` -- calling `refresh` here
      // regardless would flash a trouble message that `onStatus('open')`
      // corrects a moment later. Wait for that transition instead of
      // racing it.
      if (stream !== null && stream.status().state === 'open') {
        await refresh();
      } else {
        showConnecting();
      }
    },
    destroy(): void {
      stopped = true;
      stream?.close();
      stream = null;
    },
  };
}
