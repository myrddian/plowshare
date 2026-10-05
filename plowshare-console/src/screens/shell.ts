import { socketTransport } from '../transport';
import { background } from '../background.ts';
import { createUsage } from './usage';
import {
  openEventStream,
  type EventStream,
  type EventStreamOptions,
  type FrameOutcome,
  type StreamStatus,
} from '../events';
import { mountStyles } from '../repl/styles';
import { createChat } from './chat';
import { createConfig } from './config';
import { createInformation } from './information';
import { createDocuments } from './documents';
import { el } from './dom';
import { createInbox } from './inbox';
import { createJobs } from './jobs';
import { createMemory } from './memory';
import { createProjects } from './projects';
import { createProposals } from './proposals';
import type { Screen, Transport } from './screen';
import { asInboxChanged, type InboxPage } from './wire';

/**
 * The eight views, and the moving between them.
 *
 * <h2>No router, and no framework</h2>
 *
 * A rail of grouped buttons, one host element per view, and `hidden` on the
 * seven that are not showing. The whole of the state is which name is current.
 * What a router would add here is a URL grammar this console has no second
 * page to need and a dependency on somebody else's history handling; what it
 * would cost is that `auth.ts` already rewrites this page's URL once, on the
 * one navigation that matters, and a library that also owned the URL would be
 * a second writer of it.
 *
 * The current view is kept in `location.hash` through `history.replaceState`,
 * which is the same call `auth.ts` makes and for a compatible reason: replacing
 * rather than pushing means a reload comes back to the view somebody was on and
 * the browser's back button still leaves the console, which is where it went
 * before there were six views. The hash is read once, at start; a hash typed
 * into the bar afterwards is not followed, because nothing here listens for
 * `hashchange` and a half-implemented history is worse than none.
 *
 * <h2>Every view is built once and never torn down</h2>
 *
 * Leaving `chat` must not end the conversation that is in it, drop its
 * scrollback, or close its socket — it is the reason this console exists and it
 * is one click away from everywhere, which is only true if coming back lands on
 * what was left. So a view is built on the first visit and kept: `Screen.load`
 * runs once, and every screen carries its own reload for the times a person
 * wants the server asked again.
 *
 * <h2>One socket for the tab, because the server allows exactly one</h2>
 *
 * **This is a structural fact and not a tidiness preference.**
 * `SessionRegistry.attach` keeps one {@code LISTENER} per session and
 * `EventChannelHandler` closes the connection it displaced, so a second socket
 * opened under this tab's session id would kill the first. And
 * `EventChannelHandler.publish` routes an event to *the session that started
 * the job* and to no other, so a second socket under a different id would
 * receive nothing at all. Two live listeners in one tab is not a thing that can
 * be arranged from here.
 *
 * So {@link multiplex} opens one real socket for the tab and hands `chat`,
 * `jobs` and now `inbox` a handle onto it, alongside the rail's own badge. A
 * screen's `close()` unsubscribes that screen; only {@link Shell.destroy}
 * closes the socket.
 *
 * **What follows for the jobs screen, said rather than left to be discovered:**
 * it is live for runs *this tab* started and for no others. A run started by
 * the command-line client publishes to that client's session, and this console
 * never sees a frame for it. Which is exactly why the screen's rows come from
 * `GET /v1/jobs` and a frame only moves the poll forward — the design that
 * survives a dropped event is the same design that survives an event addressed
 * to somebody else.
 */

/**
 * The views, in the order they appear in the rail. `inbox` is first.
 *
 * Grouped by what a person is looking at rather than by which endpoint
 * answers: `inbox` is what arrived while nobody was watching, `chat` is one
 * conversation, picked, had and read back all at once — the three screens
 * `sessions`, `trajectory` and the REPL used to be, before this task folded
 * them into one tab of their own — `documents` is the corpus, and the last
 * five (`jobs` included) are the archive and the machinery around it.
 */
export const VIEWS = [
  'inbox',
  'chat',
  'documents',
  'information',
  'usage',
  'jobs',
  'proposals',
  'memory',
  'projects',
  'config',
] as const;

export type ViewName = (typeof VIEWS)[number];

/** The word in the rail, per view. */
const LABELS: Readonly<Record<ViewName, string>> = Object.freeze({
  inbox: 'inbox',
  chat: 'chat',
  documents: 'documents',
  information: 'information',
  usage: 'usage',
  jobs: 'jobs',
  proposals: 'proposals',
  memory: 'memory',
  projects: 'projects',
  config: 'config',
});

/**
 * Which of the rail's two sections a view sits in.
 *
 * `work` is a conversation and the record around it; `system` is the
 * machinery a person consults rather than works in. Six and two, today --
 * `projects` and `config` are the views about the console's own
 * configuration rather than about a conversation or its archive.
 */
const GROUPS: Readonly<Record<ViewName, 'work' | 'system'>> = Object.freeze({
  inbox: 'work',
  chat: 'work',
  documents: 'work',
  information: 'work',
  usage: 'work',
  jobs: 'work',
  proposals: 'work',
  memory: 'work',
  projects: 'system',
  config: 'system',
});

export interface ShellOptions {
  readonly root: HTMLElement;
  readonly transport?: Transport;
  /** Defaults to the real socket. */
  readonly openStream?: (options: EventStreamOptions) => EventStream;
  /** The listener session id for the whole tab. Minted when absent. */
  readonly session?: string;
  /** The project tier the screens start on, or null for global. */
  readonly project?: string | null;
  /**
   * Passed to the two screens that poll — jobs and documents; `null` for a
   * shell that never polls.
   *
   * One number for both, because both are polling the same listing for the
   * same reason: `GET /v1/jobs` is the record and an event is not, and an
   * ingest is the case where no event exists at all.
   */
  readonly pollMs?: number | null;
  /** The window whose hash is read and rewritten. The real one by default. */
  readonly scope?: Window;
}

export interface Shell {
  /** Build the view if it has not been built, show it, and hide the rest. */
  show(name: ViewName): Promise<void>;
  /** Which view is showing. */
  current(): ViewName;
  /** The element this shell built. */
  element(): HTMLElement;
  /** Start on the view the URL names, or on chat. */
  start(): Promise<void>;
  /** Close the socket and every view. Idempotent. */
  destroy(): void;
}

/**
 * The listener id this tab attaches under.
 *
 * Copied in behaviour from `repl.ts`'s own, and now minted here instead:
 * the id belongs to the tab rather than to one view, because the tab is what
 * gets one listener.
 */
function mintSession(): string {
  const source = globalThis.crypto as { randomUUID?: () => string } | undefined;
  if (source !== undefined && typeof source.randomUUID === 'function') {
    return `console-${source.randomUUID()}`;
  }
  return `console-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

/** A name this shell knows, or null. */
export function viewFromHash(hash: string): ViewName | null {
  const wanted = hash.replace(/^#/, '');
  return (VIEWS as readonly string[]).includes(wanted)
    ? (wanted as ViewName)
    : null;
}

/** A handle on the tab's one socket: what views subscribe to, and who may close it. */
export interface Shared {
  /** What a view is handed in place of `openEventStream`. */
  readonly open: (options: EventStreamOptions) => EventStream;
  /** Close the one real socket. Only the shell calls this. */
  readonly stop: () => void;
}

/**
 * One real socket, any number of subscribers.
 *
 * The caller's `session` is deliberately ignored in favour of the tab's: a view
 * that asked for its own would be asking for the second listener the server
 * will not give it, and honouring the request would break the first view rather
 * than the second. There is one id per tab and this is where it is decided.
 */
export function multiplex(
  open: (options: EventStreamOptions) => EventStream,
  session: string,
): Shared {
  const listeners = new Set<(event: unknown) => void>();
  const watchers = new Set<(status: StreamStatus) => void>();
  let real: EventStream | null = null;
  let last: StreamStatus = { state: 'connecting', attempt: 0, retryInMs: null };

  const subscribe = (options: EventStreamOptions): EventStream => {
    listeners.add(options.onEvent);
    const watcher = options.onStatus;
    if (watcher !== undefined) {
      watchers.add(watcher);
    }
    if (real === null) {
      real = open({
        session,
        onEvent: (event: unknown): void => {
          // A copy, because a listener that unsubscribes while the
          // set is being walked would otherwise change it underfoot.
          for (const listener of [...listeners]) {
            listener(event);
          }
        },
        onStatus: (status: StreamStatus): void => {
          last = status;
          for (const each of [...watchers]) {
            each(status);
          }
        },
      });
    }
    // The state so far, so a view built after the socket opened is not left
    // saying `connecting` about a socket that has been open for a minute.
    watcher?.(last);
    return {
      status: (): StreamStatus => last,
      close: (): void => {
        listeners.delete(options.onEvent);
        if (watcher !== undefined) {
          watchers.delete(watcher);
        }
      },
      ask: (type: string, payload?: unknown): Promise<FrameOutcome> =>
        real === null
          ? Promise.reject(
              new Error(`the event socket is not open; "${type}" was not sent`),
            )
          : real.ask(type, payload),
    };
  };
  return {
    open: subscribe,
    stop: (): void => {
      real?.close();
      real = null;
    },
  };
}

export function createShell(options: ShellOptions): Shell {
  const scope = options.scope ?? window;
  const session = options.session ?? mintSession();
  let project = options.project ?? null;
  const openReal = options.openStream ?? openEventStream;
  const shared = multiplex(openReal, session);
  let borrowed: EventStream | undefined;
  const transport: Transport =
    options.transport ??
    socketTransport(
      () => (borrowed ??= shared.open({ session, onEvent: () => {} })),
    );

  mountStyles(options.root.ownerDocument);

  const shell = el('div', 'shell');
  const rail = el('nav', 'rail');
  const railGroups: Readonly<Record<'work' | 'system', HTMLElement>> = {
    work: el('div', 'rail-group'),
    system: el('div', 'rail-group'),
  };
  railGroups.work.dataset['railGroup'] = 'work';
  railGroups.system.dataset['railGroup'] = 'system';
  const stage = el('div', 'stage');
  const buttons = new Map<ViewName, HTMLButtonElement>();
  const hosts = new Map<ViewName, HTMLElement>();
  const built = new Map<ViewName, Screen>();
  let showing: ViewName = 'chat';
  let stopped = false;

  for (const name of VIEWS) {
    // No class of its own: every rule that dresses these reaches them as
    // `.rail button`, and `[data-nav]` is what the tests find them by. A
    // class nothing selects is a hook a reader has to check for.
    const control = document.createElement('button');
    control.type = 'button';
    control.textContent = LABELS[name];
    control.dataset['nav'] = name;
    control.addEventListener('click', () => {
      background(show(name));
    });
    buttons.set(name, control);
    railGroups[GROUPS[name]].append(control);

    const host = el('div', 'view');
    host.dataset['view'] = name;
    host.hidden = true;
    hosts.set(name, host);
    stage.append(host);
  }

  /**
   * The unread count on the INBOX nav button, kept live off the tab's one
   * socket rather than polled.
   *
   * The subscription itself is opened from {@link Shell.start} and not here,
   * on the same rule the "do nothing" listener below it already follows: a
   * shell that is built but never started should not yet have reached for the
   * network, and `multiplex`'s one real socket is created by whichever
   * subscriber asks for it first.
   *
   * `badgeStream` is declared with `let` and assigned from `shared.open`'s
   * return value rather than read inside the object literal that produces it:
   * `multiplex.subscribe` calls a fresh watcher with the socket's current
   * status *synchronously*, before `open` returns, so a socket that is
   * already open by the time INBOX subscribes would read `badgeStream` in its
   * temporal dead zone. Assigning first and asking after -- once, only if the
   * status handed back at open time was already `'open'` -- avoids both the
   * `ReferenceError` and a call this same status will otherwise cause `open`
   * to fire only from a later transition.
   */
  let badgeStream: EventStream | null = null;
  const badge = (unread: number): void => {
    const control = buttons.get('inbox');
    if (control !== undefined) {
      control.textContent =
        unread === 0 ? LABELS.inbox : `${LABELS.inbox} ${unread}`;
      control.dataset['unread'] = String(unread);
    }
  };
  const askUnread = (): void => {
    background(
      badgeStream?.ask('inbox.list', { unread: true, limit: 1 }).then(
        (outcome) => {
          if (outcome.code === 'OK')
            badge((outcome.payload as InboxPage).unread);
        },
        () => {},
      ),
    );
  };

  /**
   * The tab's one socket, read at the rail's foot rather than inside any one
   * view.
   *
   * It used to be the REPL header's business, because the REPL was the only
   * screen a person watched a running job through. `jobs` reads the same
   * socket now, and neither owns it -- {@link multiplex} does, for the whole
   * tab -- so the state that describes it belongs at the level that is always
   * on screen, not folded into whichever view happens to be showing.
   */
  const streamLabel = el('span', 'stream');
  streamLabel.dataset['stream'] = '';
  function showStream(status: StreamStatus): void {
    streamLabel.dataset['state'] = status.state;
    if (status.state === 'reconnecting' && status.retryInMs !== null) {
      streamLabel.textContent = `stream reconnecting, next try in ${Math.round(status.retryInMs / 100) / 10}s`;
      return;
    }
    streamLabel.textContent = `stream ${status.state}`;
  }
  const foot = el('div', 'rail-foot');
  foot.append(streamLabel);
  showStream({ state: 'connecting', attempt: 0, retryInMs: null });

  rail.append(railGroups.work, railGroups.system, foot);
  shell.append(rail, stage);
  options.root.replaceChildren(shell);

  /**
   * The builders, one per view.
   *
   * `chat` is not adapted here the way the REPL once was: it already
   * implements {@link Screen} on its own, because it is the thing that owns
   * the REPL now. This function hands it the same socket handle and session
   * the REPL used to be handed directly.
   */
  function build(name: ViewName, host: HTMLElement): Screen {
    if (name === 'inbox') {
      return createInbox({ root: host, openStream: shared.open, session });
    }
    if (name === 'chat') {
      return createChat({
        root: host,
        transport,
        openStream: shared.open,
        session,
        project,
      });
    }
    if (name === 'usage')
      return createUsage({
        root: host,
        openStream: shared.open,
        session,
        project,
      });
    if (name === 'information')
      return createInformation({
        root: host,
        openStream: shared.open,
        session,
        project,
      });
    if (name === 'documents') {
      // The corpus is not the archive and not a conversation: it makes no
      // model calls on the read side and its ingest is a job of its own.
      return createDocuments({
        root: host,
        transport,
        ...(options.pollMs === undefined ? {} : { pollMs: options.pollMs }),
      });
    }
    if (name === 'projects') {
      return createProjects({
        root: host,
        transport,
        openStream: shared.open,
        session,
      });
    }
    if (name === 'jobs') {
      return createJobs({
        root: host,
        transport,
        openStream: shared.open,
        session,
        ...(options.pollMs === undefined ? {} : { pollMs: options.pollMs }),
      });
    }
    if (name === 'proposals') {
      return createProposals({ root: host, transport, project });
    }
    if (name === 'config') {
      return createConfig({ root: host, transport });
    }
    return createMemory({ root: host, transport, project });
  }

  async function show(name: ViewName): Promise<void> {
    showing = name;
    for (const each of VIEWS) {
      const host = hosts.get(each) as HTMLElement;
      host.hidden = each !== name;
      const control = buttons.get(each) as HTMLButtonElement;
      if (each === name) {
        control.setAttribute('aria-current', 'page');
      } else {
        control.removeAttribute('aria-current');
      }
    }
    remember(name);
    if (built.has(name) || stopped) {
      return;
    }
    const host = hosts.get(name) as HTMLElement;
    const screen = build(name, host);
    built.set(name, screen);
    // Never rejects, by `Screen.load`'s own contract, so the switch cannot
    // be taken down by whatever the server said. A screen that could not
    // read draws that instead.
    await screen.load().catch(() => undefined);
  }

  /**
   * Put the current view in the URL, and never fail over it.
   *
   * `replaceState` throws in a document with an opaque origin -- a `file:`
   * page, a sandboxed frame -- and a console that would not switch views
   * because it could not rewrite a hash would be broken over decoration.
   */
  function remember(name: ViewName): void {
    try {
      scope.history.replaceState(null, '', `#${name}`);
    } catch {
      // Nothing to do and nothing to say: the view has already switched.
    }
  }

  return {
    show,
    current: () => showing,
    element: () => shell,
    async start(): Promise<void> {
      // A listener that does nothing: the rail wants the socket's status
      // and none of its frames, and `shared.open` requires both a
      // subscriber to deliver a status to and a caller to count as the
      // tab's first. Opened here rather than at construction, the same
      // point the REPL used to open its own -- a shell that is built but
      // never started should not yet have reached for the network.
      shared.open({ session, onEvent: () => undefined, onStatus: showStream });
      badgeStream = shared.open({
        session,
        onEvent: (frame) => {
          const changed = asInboxChanged(frame);
          if (changed !== null) {
            badge(changed.unread);
          }
        },
        onStatus: (status) => {
          if (status.state === 'open' && badgeStream !== null) askUnread();
        },
      });
      if (badgeStream.status().state === 'open') {
        askUnread();
      }
      if (project === null) {
        try {
          const projects = await transport.get('/v1/projects');
          project =
            projects?.find((row) => row.kind === 'personal')?.name ?? null;
        } catch {
          /* Individual screens retain their own visible connection failures. */
        }
      }
      return show(viewFromHash(scope.location.hash) ?? 'chat');
    },
    destroy(): void {
      stopped = true;
      for (const screen of built.values()) {
        screen.destroy();
      }
      built.clear();
      badgeStream?.close();
      shared.stop();
    },
  };
}
