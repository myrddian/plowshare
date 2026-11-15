import { background } from '../background.ts';
import type { EventStream, EventStreamOptions } from '../events';
import { button, el, input, trouble } from './dom';
import type { Screen } from './screen';
import { asInboxChanged } from './wire';
import { decodeReply } from '../../../sdk/typescript/src/operations/schema.ts';
import type {
  InboxItem as InboxItemView,
  InboxPage,
} from '../../../sdk/typescript/src/operations/administrative-replies.ts';

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
  const view = el('section', 'inbox');
  const element = el('div', 'inbox-content');
  const selector = el('form', 'inbox-selector');
  const application = input('Application project', 'Application project');
  application.className = 'inbox-project';
  application.placeholder = 'Application project';
  application.setAttribute('aria-label', 'Application project');
  const inspect = button('application-inbox', 'Inspect application inbox');
  const own = button('personal-inbox', 'My inbox');
  let project: string | undefined;
  selector.append(application, inspect, own);
  view.append(selector, element);
  options.root.append(view);
  function inspectApplication(): void {
    const requested = application.value.trim();
    if (!requested) {
      element.replaceChildren(trouble('Enter an application project.'));
      return;
    }
    project = requested;
    background(refresh());
  }
  selector.addEventListener('submit', (event) => {
    event.preventDefault();
    inspectApplication();
  });
  inspect.addEventListener('click', inspectApplication);
  own.addEventListener('click', () => {
    project = undefined;
    background(refresh());
  });
  let stream: EventStream | null = null;
  let stopped = false;
  let refreshVersion = 0;

  async function refresh(): Promise<void> {
    if (stream === null || stopped) {
      return;
    }
    const selectedProject = project;
    const version = ++refreshVersion;
    let outcome;
    try {
      outcome = await stream.ask('inbox.list', {
        limit: 50,
        ...(selectedProject ? { project: selectedProject } : {}),
      });
    } catch (problem) {
      if (stopped || version !== refreshVersion || selectedProject !== project)
        return;
      element.replaceChildren(
        trouble(
          problem instanceof Error
            ? problem.message
            : 'The inbox could not be read.',
        ),
      );
      return;
    }
    if (stopped || version !== refreshVersion || selectedProject !== project)
      return;
    if (outcome.code !== 'OK') {
      element.replaceChildren(
        trouble(outcome.said ?? 'The inbox could not be read.'),
      );
      return;
    }
    try {
      draw(decodeReply('inbox.list', outcome.payload), selectedProject);
    } catch (problem) {
      element.replaceChildren(
        trouble(
          problem instanceof Error
            ? problem.message
            : 'Invalid inbox response.',
        ),
      );
    }
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

  function draw(page: InboxPage, selectedProject: string | undefined): void {
    const heading = el('h2', 'inbox-heading');
    heading.textContent = `${selectedProject ? selectedProject + ' · application inbox' : 'inbox'}${page.unread ? ' · ' + page.unread + ' unread' : ''}`;
    if (page.items.length === 0) {
      const empty = el('p', 'inbox-empty');
      empty.textContent =
        'Nothing has arrived. Scheduled runs with no conversation land here.';
      element.replaceChildren(heading, empty);
      return;
    }
    element.replaceChildren(
      heading,
      ...page.items.map((item) => row(item, selectedProject)),
    );
  }

  function row(
    item: InboxItemView,
    selectedProject: string | undefined,
  ): HTMLElement {
    const article = el('article', 'inbox-item');
    article.dataset['item'] = item.id;
    article.dataset['unread'] = String(item.readAt === null);
    const meta = el('div', 'inbox-meta');
    meta.textContent = [
      item.arrivedAt,
      item.ending ?? item.kind,
      item.conversation,
    ]
      .filter(Boolean)
      .join(' · ');
    const answer = el('pre', 'inbox-answer');
    answer.textContent = item.answer ?? '';
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
            outcome = await stream.ask('inbox.read', {
              items: [item.id],
              ...(selectedProject ? { project: selectedProject } : {}),
            });
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
          try {
            decodeReply('inbox.read', outcome.payload);
          } catch (problem) {
            open.disabled = false;
            showItemTrouble(
              article,
              problem instanceof Error
                ? problem.message
                : 'Invalid inbox acknowledgement.',
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
    element: () => view,
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
      view.remove();
    },
  };
}
