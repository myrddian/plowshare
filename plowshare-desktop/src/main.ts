import { errorMessage } from 'plowshare-client-ts/binding/values';
import { decodeDesktopRequest } from './request-boundary.ts';
import { WorkspacePage } from './workspace-page.ts';
import { JobJournal } from './job-store.ts';
import { retainedReport } from './retained-report.ts';
import type { LibraryView } from './library-shared.ts';
import { inspectionConversation, runQuestion } from './shared.ts';
import { stageSpans, type StageScope } from './run-navigation.ts';
import type { BoardView } from './board-shared.ts';
import {
  app,
  BrowserWindow,
  ipcMain,
  protocol,
  net,
  session,
  shell,
  clipboard,
  dialog,
} from 'electron';
import { join } from 'node:path';
import { mkdirSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { DesktopWorkspace } from './workspace.ts';
import { ProjectConfig } from './project-config.ts';
import {
  ConnectionConfig,
  desktopConfigDirectory,
} from './connection-config.ts';
import { serverConnector } from './client.ts';
import { hostname } from 'node:os';
import { thisMachine } from 'plowshare-client-node/marker';
import type { DesktopState, ActivityView, WorkspaceRoute } from './shared.ts';
import { webAddress } from './renderer/markdown.ts';
import { desktopProfile } from './profile.ts';

const rendererUrl = 'plowshare://app/index.html';
const applicationIcon = join(
  __dirname,
  'icons',
  process.platform === 'win32' ? 'plowshare.ico' : 'plowshare.png',
);
const boardUrl = 'plowshare://app/board.html';
const trajectoryUrl = 'plowshare://app/trajectory.html';
const relayUrl = 'plowshare://app/relay.html';
const usageUrl = 'plowshare://app/usage.html';
const libraryUrl = 'plowshare://app/library.html';
const libraryActions = [
  'library-view',
  'library-refresh',
  'library-documents',
  'library-document',
  'library-source-text',
  'library-memory',
  'library-chunk',
  'library-citations',
  'library-stance',
  'library-search',
  'library-maintain',
  'library-conversation',
];
const activityUrl = 'plowshare://app/activity.html';
protocol.registerSchemesAsPrivileged([
  {
    scheme: 'plowshare',
    privileges: { standard: true, secure: true, supportFetchAPI: true },
  },
]);
app.setName('Plowshare');
const profile = desktopProfile(
  app.isPackaged,
  app.getAppPath(),
  app.getPath('appData'),
  process.env['PLOWSHARE_DESKTOP_PROFILE'],
);
mkdirSync(profile, { recursive: true });
app.setPath('userData', profile);
let window: BrowserWindow | undefined;
const trajectories = new Map<
  string,
  { window: WorkspacePage; conversation: string; stageScope?: StageScope }
>();
const activities = new Map<
  string,
  { window: WorkspacePage; view: ActivityView; group: string }
>();
const activityGroup = (view: ActivityView) =>
  view === 'definitions' || view === 'builder' ? 'studio' : view;
let activityRunRead = 0;
const libraries = new Map<'library' | 'memories', WorkspacePage>();
const boards = new Map<BoardView, WorkspacePage>();
let usagePage: WorkspacePage | undefined;
let relayPage: WorkspacePage | undefined;
const relayConversations = new Set<string>();
let identity = '';
const activityLabels: Record<ActivityView, string> = {
  inbox: 'Mailbox',
  runs: 'Runs',
  definitions: 'Definitions',
  schedules: 'Scheduled work',
  builder: 'Orchestration builder',
};
let activePage: WorkspacePage | undefined;
let workspaceRoute: WorkspaceRoute = { kind: 'chat', label: 'Conversation' };
let workspaceBounds = { x: 0, y: 0, width: 0, height: 0 };
let workspaceVisible = false;

const identityOf = (state: DesktopState) =>
  state.mode === 'demo' ? 'demo' : `${state.base}|${state.handle}`;
const configDirectory = desktopConfigDirectory();
const client = new DesktopWorkspace(
  (state) => {
    const nextIdentity = identityOf(state);
    if (identity && identity !== nextIdentity) {
      showChat();
      closeTrajectories();
      usagePage?.close();
      relayPage?.close();
      relayConversations.clear();
      for (const page of activities.values()) page.window.close();
      for (const page of boards.values()) page.close();
      for (const page of libraries.values()) page.close();
    }
    identity = nextIdentity;
    if (window && !window.isDestroyed()) {
      window.webContents.send('plowshare:state', state);
      sendWorkspace();
    }
    for (const page of [
      ...boards.values(),
      ...libraries.values(),
      ...(usagePage ? [usagePage] : []),
      ...(relayPage ? [relayPage] : []),
    ])
      if (!page.isDestroyed()) page.webContents.send('plowshare:state', state);
    for (const held of trajectories.values()) {
      if (!held.window.isDestroyed())
        held.window.webContents.send('plowshare:state', state);
    }
    for (const page of activities.values())
      if (!page.window.isDestroyed())
        page.window.webContents.send('plowshare:state', state);
  },
  new ProjectConfig(configDirectory),
  serverConnector,
  new ConnectionConfig(configDirectory),
  new JobJournal(configDirectory),
);

function sendWorkspace() {
  if (activePage && !activePage.isDestroyed())
    workspaceRoute = activePage.route();
  if (window && !window.isDestroyed())
    window.webContents.send('plowshare:workspace', { route: workspaceRoute });
}
function showChat(manage = false) {
  activePage?.layout(workspaceBounds, false);
  activePage = undefined;
  workspaceRoute = {
    kind: manage ? 'manage' : 'chat',
    label: manage ? 'Manage work' : 'Conversation',
  };
  sendWorkspace();
}
function makePage(route: () => WorkspaceRoute) {
  return new WorkspacePage(
    window!,
    join(__dirname, 'preload.cjs'),
    route,
    (page) => {
      if (activePage !== page) activePage?.layout(workspaceBounds, false);
      activePage = page;
      page.layout(workspaceBounds, workspaceVisible);
      sendWorkspace();
    },
    (page) => {
      if (activePage === page) showChat();
    },
  );
}
function trajectoryRoute(
  conversation: string,
  stageScope?: StageScope,
): WorkspaceRoute {
  const row = client.state.conversations.find((row) => row.id === conversation);
  const run = Object.values(client.state.activity.details).find((detail) =>
    [
      detail.wire?.orchestration.conductorConversation,
      detail.wire?.orchestration.callerConversation,
    ].includes(conversation),
  );
  const project = row?.project ?? run?.wire?.orchestration.project ?? '';
  const title =
    row?.title ||
    client.state.history[conversation]?.entries
      .find((entry) => entry.kind === 'utterance')
      ?.text?.slice(0, 56) ||
    'Conversation';
  return {
    kind: 'trajectory',
    label: stageScope ? `Trajectory · ${stageScope.stage}` : 'Trajectory',
    project,
    conversation,
    title,
  };
}

function closeTrajectories() {
  for (const held of [...trajectories.values()]) held.window.close();
}
function secureWindow(view: BrowserWindow | WorkspacePage) {
  view.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  view.webContents.on('will-navigate', (event) => event.preventDefault());
  view.webContents.on('will-attach-webview', (event) => event.preventDefault());
}
function openTrajectory(conversation: string, stageScope?: StageScope) {
  if (
    typeof conversation !== 'string' ||
    !inspectionConversation(client.state, conversation)
  ) {
    throw new Error('Choose an available conversation to inspect.');
  }
  const key = stageScope
    ? JSON.stringify([conversation, stageScope.run, stageScope.stage])
    : conversation;
  const existing = trajectories.get(key);
  if (existing && !existing.window.isDestroyed()) {
    existing.window.show();
    existing.window.focus();
    return;
  }
  const view = makePage(() => trajectoryRoute(conversation, stageScope));
  trajectories.set(key, {
    window: view,
    conversation,
    ...(stageScope === undefined ? {} : { stageScope: stageScope }),
  });
  if (stageScope) client.runs.followNavigation(stageScope.run, true);
  background(client.followView(`trajectory:${key}`, conversation));
  secureWindow(view);
  view.show();
  view.on('closed', () => {
    trajectories.delete(key);
    if (
      stageScope &&
      ![...trajectories.values()].some(
        (held) => held.stageScope?.run === stageScope.run,
      )
    )
      client.runs.followNavigation(stageScope.run, false);
    background(client.followView(`trajectory:${key}`));
  });
  background(view.loadURL(trajectoryUrl));
}

function openRelay() {
  if (relayPage && !relayPage.isDestroyed()) {
    relayPage.show();
    relayPage.focus();
    return;
  }
  const page = makePage(() => ({ kind: 'relay', label: 'Relay log' }));
  relayPage = page;
  relayConversations.clear();
  secureWindow(page);
  page.show();
  page.on('closed', () => {
    if (relayPage === page) {
      relayPage = undefined;
      relayConversations.clear();
    }
  });
  background(page.loadURL(relayUrl));
}

function openUsage() {
  if (usagePage && !usagePage.isDestroyed()) {
    usagePage.show();
    usagePage.focus();
    return;
  }
  const page = makePage(() => ({ kind: 'usage', label: 'Usage' }));
  usagePage = page;
  secureWindow(page);
  page.show();
  page.on('closed', () => {
    if (usagePage === page) {
      usagePage = undefined;
      background(client.dispatch({ action: 'usage-close' }));
    }
  });
  background(page.loadURL(usageUrl));
}

function openActivity(tab: ActivityView) {
  if (!['inbox', 'runs', 'definitions', 'schedules', 'builder'].includes(tab))
    throw new Error('Choose an Activity view.');
  const group = activityGroup(tab);
  let activity = activities.get(group);
  if (activity && !activity.window.isDestroyed()) {
    activity.view = tab;
    activity.window.show();
    activity.window.focus();
    background(client.dispatch({ action: 'activity-view', view: tab }));
    return;
  }
  const page = makePage(() => ({
    kind: 'activity',
    label: activityLabels[activities.get(group)?.view ?? tab],
  }));
  activity = { window: page, view: tab, group };
  activities.set(group, activity);
  secureWindow(page);
  page.show();
  page.on('closed', () => {
    if (activities.get(group)?.window !== page) return;
    activities.delete(group);
    if (group === 'runs' || group === 'studio') {
      activityRunRead++;
      background(client.followView(`activity-run:${group}`));
    }
    if (!activities.size) {
      client.runs.pause();
      background(client.activity.open());
    }
  });
  background(client.dispatch({ action: 'activity-view', view: tab }));
  background(page.loadURL(activityUrl));
}

async function openLibrary(
  tab: LibraryView,
  project: string | null = null,
  revision?: string,
  chapter?: string,
) {
  if (!['sources', 'documents', 'memories', 'search', 'manual'].includes(tab))
    throw new Error('Choose a Library view.');
  if (
    project !== null &&
    !client.state.projects.some((row) => row.name === project)
  )
    throw new Error('Choose an available project.');
  const screen = tab === 'memories' ? 'memories' : 'library';
  const search = client.state.library?.search;
  if (
    search?.value?.kind &&
    (screen === 'memories') !==
      ['recall', 'navigate'].includes(search.value.kind)
  )
    client.state.library!.search = {};
  const existing = libraries.get(screen);
  if (existing && !existing.isDestroyed()) {
    existing.show();
    existing.focus();
    await client.dispatch({
      action: 'library-view',
      view: tab,
      project,
      ...(revision === undefined ? {} : { revision: revision }),
      ...(chapter === undefined ? {} : { chapter: chapter }),
    });
    return;
  }
  const page = makePage(() => {
    const selectedProject = client.state.library?.project;
    return {
      kind: screen,
      label: screen === 'memories' ? 'Memories' : 'Library',
      ...(selectedProject == null ? {} : { project: selectedProject }),
    };
  });
  libraries.set(screen, page);
  secureWindow(page);
  page.show();
  page.on('closed', () => {
    if (libraries.get(screen) === page) libraries.delete(screen);
  });
  const reading = client.dispatch({
    action: 'library-view',
    view: tab,
    project,
    ...(revision === undefined ? {} : { revision: revision }),
    ...(chapter === undefined ? {} : { chapter: chapter }),
  });
  background(page.loadURL(libraryUrl));
  await reading;
}

function openBoard(view: BoardView, project?: string) {
  if (view !== 'board' && view !== 'swarm')
    throw new Error('Choose Board or Swarm.');
  if (
    project !== undefined &&
    !client.state.projects.some((p) => p.name === project)
  )
    throw new Error('Choose an available project.');
  const existing = boards.get(view);
  if (existing && !existing.isDestroyed()) {
    existing.show();
    existing.focus();
    background(client.board.open(view, project));
    return;
  }
  const page = makePage(() => ({
    kind: 'board',
    label: view === 'swarm' ? 'Swarm' : 'Board',
    ...(client.state.board.project === undefined
      ? {}
      : { project: client.state.board.project }),
  }));
  boards.set(view, page);
  secureWindow(page);
  page.show();
  page.on('closed', () => {
    if (boards.get(view) === page) {
      boards.delete(view);
      if (!boards.size) background(client.board.open());
    }
  });
  background(client.board.open(view, project));
  background(page.loadURL(boardUrl));
}

function openWindow() {
  window = new BrowserWindow({
    icon: applicationIcon,
    width: 1440,
    height: 960,
    minWidth: 860,
    minHeight: 640,
    title: 'Plowshare',
    backgroundColor: '#10110f',
    titleBarStyle: 'hiddenInset',
    trafficLightPosition: { x: 18, y: 18 },
    show: false,
    webPreferences: {
      preload: join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
    },
  });
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', (event) => event.preventDefault());
  window.webContents.on('will-attach-webview', (event) =>
    event.preventDefault(),
  );
  window.once('ready-to-show', () => window?.show());
  window.on('closed', () => {
    window = undefined;
    background(client.followView('chat'));
    closeTrajectories();
    for (const page of activities.values()) page.window.close();
    for (const page of boards.values()) page.close();
    for (const page of libraries.values()) page.close();
  });
  background(window.loadURL(rendererUrl));
}

background(
  app.whenReady().then(() => {
    // Keep the Dock image consistent for native bundles and direct Electron launches.
    if (process.platform === 'darwin') app.dock?.setIcon(applicationIcon);
    session.defaultSession.setPermissionRequestHandler(
      (_contents, _permission, callback) => callback(false),
    );
    session.defaultSession.setPermissionCheckHandler(() => false);
    protocol.handle('plowshare', (request) => {
      const url = new URL(request.url);
      const file = url.pathname.slice(1);
      if (
        url.host !== 'app' ||
        ![
          'index.html',
          'trajectory.html',
          'activity.html',
          'style.css',
          'desktop.css',
          'app.js',
          'app-icon.png',
          'trajectory.js',
          'activity.js',
          'board.html',
          'board.css',
          'board.js',
          'library.html',
          'library.js',
          'library.css',
          'relay.html',
          'relay.js',
          'relay.css',
          'usage.html',
          'usage.js',
          'usage.css',
        ].includes(file)
      )
        return new Response('Not found', { status: 404 });
      return net.fetch(pathToFileURL(join(__dirname, 'renderer', file)).href);
    });
    ipcMain.handle('plowshare:request', async (event, value: unknown) => {
      const main =
        window && !window.isDestroyed() && event.sender === window.webContents;
      const held = [...trajectories.values()].find(
        (row) =>
          !row.window.isDestroyed() && row.window.webContents === event.sender,
      );
      const account = [...activities.values()].find(
        (page) =>
          !page.window.isDestroyed() &&
          event.sender === page.window.webContents,
      );
      const board = [...boards.entries()].find(
        ([, page]) => !page.isDestroyed() && event.sender === page.webContents,
      );
      const library = [...libraries.entries()].find(
        ([, page]) => !page.isDestroyed() && event.sender === page.webContents,
      );
      const relay =
        relayPage &&
        !relayPage.isDestroyed() &&
        event.sender === relayPage.webContents;
      const usage =
        usagePage &&
        !usagePage.isDestroyed() &&
        event.sender === usagePage.webContents;
      if (
        (!main &&
          !held &&
          !account &&
          !board &&
          !library &&
          !usage &&
          !relay) ||
        event.senderFrame !== event.sender.mainFrame ||
        event.senderFrame.url !==
          (main
            ? rendererUrl
            : held
              ? trajectoryUrl
              : account
                ? activityUrl
                : board
                  ? boardUrl
                  : relay
                    ? relayUrl
                    : usage
                      ? usageUrl
                      : libraryUrl)
      ) {
        throw new Error(
          'Desktop requests must come from a registered application window.',
        );
      }
      const request = decodeDesktopRequest(value);
      if (request?.action?.startsWith('workspace-')) {
        if (!main)
          throw new Error('Workspace navigation belongs to the main window.');
        if (request.action === 'workspace-chat') {
          showChat(request.manage);
          return { state: client.state };
        }
        if (request.action === 'workspace-layout') {
          const values = [request.x, request.y, request.width, request.height];
          if (
            !values.every((value) => Number.isFinite(value) && value >= 0) ||
            typeof request.visible !== 'boolean'
          )
            throw new Error('Choose valid workspace bounds.');
          const [width, height] = window!.getContentSize();
          if (width === undefined || height === undefined)
            throw new Error('Window dimensions unavailable.');
          const x = Math.min(width, Math.round(request.x)),
            y = Math.min(height, Math.round(request.y));
          workspaceBounds = {
            x,
            y,
            width: Math.min(width - x, Math.round(request.width)),
            height: Math.min(height - y, Math.round(request.height)),
          };
          workspaceVisible =
            request.visible &&
            workspaceBounds.width > 0 &&
            workspaceBounds.height > 0;
          activePage?.layout(workspaceBounds, workspaceVisible);
          return { state: client.state };
        }
        if (request.action === 'workspace-refresh') {
          activePage?.webContents.send('plowshare:workspace-refresh');
          return { state: client.state };
        }
        throw new Error('Choose a workspace navigation action.');
      }
      if (request?.action === 'personal-bots') {
        if (!main)
          throw new Error('Personal navigation belongs to the main window.');
        return client.dispatch(request);
      }
      if (request?.action === 'personal-section') {
        if (!main)
          throw new Error('Personal navigation belongs to the main window.');
        showChat();
        return client.dispatch(request);
      }
      if (main && request?.action === 'bootstrap')
        return client
          .dispatch(request)
          .then((reply) => ({ ...reply, route: workspaceRoute }));
      if (request?.action === 'open-link') {
        const address =
          typeof request.url === 'string' && request.url.length < 8192
            ? webAddress(request.url)
            : undefined;
        if (!address)
          throw new Error(
            'Only http or https links without embedded credentials can be opened.',
          );
        return shell
          .openExternal(address)
          .then(() => ({ state: client.state }));
      }
      if (request?.action === 'copy-text') {
        if (typeof request.text !== 'string' || request.text.length > 1048576)
          throw new Error(
            'Copy text must be a string of at most 1,048,576 characters.',
          );
        await clipboard.writeText(request.text);
        return { state: client.state };
      }
      if (request?.action?.startsWith('operator-') && !main)
        throw new Error('Workspace controls belong to the main window.');
      if (relay) {
        if (request.action === 'bootstrap') return { state: client.state };
        if (request.action === 'relay-read') {
          const reply = await client.dispatch(request);
          if (
            request.type === 'relay.log' &&
            reply.relay &&
            'branches' in reply.relay
          )
            for (const branch of reply.relay.branches)
              if (branch.conversation)
                relayConversations.add(branch.conversation);
          return reply;
        }
        if (request.action === 'relay-operate') return client.dispatch(request);
        if (request.action === 'relay-trajectory') {
          if (!relayConversations.has(request.conversation))
            throw new Error(
              'Choose a trajectory from an inspected Relay delivery.',
            );
          openTrajectory(request.conversation);
          return { state: client.state };
        }
        throw new Error(
          'Relay can inspect broker logs, administer project subscriptions and open owned trajectories.',
        );
      }
      if (
        request.action === 'relay-operate' ||
        request.action === 'relay-read' ||
        request.action === 'relay-trajectory'
      )
        throw new Error('Open Relay to inspect its logs.');
      if (usage) {
        if (request?.action === 'bootstrap') return { state: client.state };
        if (
          !['usage-open', 'usage-read', 'usage-close'].includes(request?.action)
        )
          throw new Error('Usage can only read account measurements.');
        return client.dispatch(request);
      }
      if (['usage-open', 'usage-read', 'usage-close'].includes(request?.action))
        throw new Error('Open Usage to read account measurements.');
      if (library) {
        if (request?.action === 'bootstrap')
          return { state: client.state, libraryScreen: library[0] };
        if (request?.action === 'information') {
          if (library[0] !== 'library')
            throw new Error('Open Library for documents and reports.');
          return client.dispatch(request);
        }
        if (!libraryActions.includes(request?.action))
          throw new Error(
            'Library windows can only read Library information and maintain displayed memories.',
          );
        if (request.action === 'library-conversation') {
          openTrajectory(client.library.conversation(request.id));
          return { state: client.state };
        }
        if (
          request.action === 'library-view' &&
          (library[0] === 'memories'
            ? !['memories', 'search'].includes(request.view)
            : request.view === 'memories')
        )
          throw new Error('Open this section from the workspace sidebar.');
        if (
          request.action === 'library-search' &&
          (library[0] === 'memories'
            ? !['recall', 'navigate'].includes(request.kind)
            : ['recall', 'navigate'].includes(request.kind))
        )
          throw new Error('Choose a search method for this section.');
        return client.dispatch(request);
      }
      if (request?.action === 'information')
        throw new Error('Open Library to read and manage sources.');
      if (libraryActions.includes(request?.action))
        throw new Error('Open Library to inspect documents and memories.');
      if (board) {
        if (request?.action === 'bootstrap')
          return { state: client.state, boardView: board[0] };
        if (request?.action === 'board-trajectory') {
          client.board.conversation(request.conversation);
          openTrajectory(request.conversation);
          return { state: client.state };
        }
        if (request.action === 'board-view' && request.view !== board[0])
          throw new Error('Open this section from the workspace sidebar.');
        if (request.action === 'board-retry') return client.dispatch(request);
        if (
          request.action === 'board-create' ||
          request.action === 'board-post' ||
          request.action === 'board-post-topics'
        ) {
          if (board[0] !== 'board')
            throw new Error('Open Board to post to the board.');
          return client.dispatch(request);
        }
        if (
          ![
            'board-view',
            'board-refresh',
            'board-more',
            'board-topic',
          ].includes(request?.action)
        )
          throw new Error(
            'Board windows can only inspect displayed board and swarm information.',
          );
        return client.dispatch(request);
      }
      if (
        [
          'board-view',
          'board-refresh',
          'board-more',
          'board-topic',
          'board-trajectory',
          'board-create',
          'board-retry',
          'board-post',
          'board-post-topics',
        ].includes(request?.action)
      )
        throw new Error('Open Board or Swarm to inspect its topics.');
      if (held) {
        if (request?.action === 'bootstrap')
          return {
            state: client.state,
            conversation: held.conversation,
            stageScope: held.stageScope,
          };
        if (request?.action === 'delegate-trajectory') {
          const reachable = new Set([held.conversation]);
          for (const parent of reachable)
            for (const entry of client.state.history[parent]?.entries ?? [])
              for (const call of entry.calls ?? [])
                if (call.opened?.conversation)
                  reachable.add(call.opened.conversation);
          if (!reachable.has(request.conversation))
            throw new Error('Choose a delegation from this trajectory.');
          return client
            .spawnedConversation(request.conversation, request.step)
            .then((conversation) => {
              openTrajectory(conversation);
              return { state: client.state };
            });
        }
        if (request?.action === 'answer') {
          const approval = client.state.approvals.find(
            (row) => row.id === request.id && row.state === 'asked',
          );
          if (
            !approval ||
            (approval.conversation !== held.conversation &&
              approval.askedIn !== held.conversation)
          )
            throw new Error(
              'Only this trajectory’s pending approvals can be answered here.',
            );
          return client.dispatch(request);
        }
        if (
          request?.action !== 'history' ||
          request.conversation !== held.conversation
        )
          throw new Error(
            'Trajectory windows can only read their own conversation.',
          );
        return client.dispatch(request);
      }
      if (account) {
        if (request?.action === 'library') {
          const run = Object.values(client.state.activity.details)
            .map((detail) => detail.value?.run)
            .find(
              (run) =>
                run &&
                retainedReport(run.result) === request.revision &&
                (run.project || null) === (request.project || null),
            );
          if (request.view !== 'sources' || !request.revision || !run)
            throw new Error('Choose the retained report of a displayed run.');
          return openLibrary(
            'sources',
            run.project || null,
            request.revision,
          ).then(() => ({ state: client.state }));
        }
        if (request?.action === 'bootstrap')
          return { state: client.state, view: account.view };
        if (
          request?.action === 'answer' ||
          request?.action === 'approvals-refresh'
        )
          return client.dispatch(request);
        if (
          ![
            'builder-outputs',
            'builder-trajectory',
            'builder-prepare',
            'builder-start',
            'activity-view',
            'activity-refresh',
            'inbox-read',
            'inbox-more',
            'run-detail',
            'run-definitions',
            'run-record',
            'run-answer',
            'run-cancel',
            'run-resume',
            'run-trajectory',
            'run-stage-trajectory',
            'delegate-trajectory',
            'schedule-refresh',
            'schedule-preview',
            'schedule-save',
            'schedule-file-save',
            'schedule-sync',
            'schedule-change',
            'schedule-fire',
          ].includes(request?.action)
        )
          throw new Error('Activity windows can only manage account activity.');
        if (
          request.action === 'activity-view' &&
          activityGroup(request.view) !== account.group
        )
          throw new Error('Open this section from the workspace sidebar.');
        if (request.action === 'delegate-trajectory') {
          const roots = Object.values(client.state.activity.details)
            .flatMap((detail) => [
              detail.wire?.orchestration.conductorConversation,
            ])
            .filter((id): id is string => Boolean(id));
          const reachable = new Set(roots);
          for (const parent of reachable)
            for (const entry of client.state.history[parent]?.entries ?? [])
              for (const call of entry.calls ?? [])
                if (call.opened?.conversation)
                  reachable.add(call.opened.conversation);
          if (!reachable.has(request.conversation))
            throw new Error('Choose a delegation from an inspected run.');
          return client
            .spawnedConversation(request.conversation, request.step)
            .then((conversation) => {
              openTrajectory(conversation);
              return { state: client.state };
            });
        }
        if (request.action === 'run-stage-trajectory') {
          const conversation = client.runs.conversation(
            request.id,
            'conductor',
          );
          if (
            !client.state.activity.details[request.id]?.value?.stages.some(
              (stage) => stage.stage === request.stage,
            )
          )
            throw new Error('Choose a stage of this run.');
          return client.runs.navigation(request.id).then(() => {
            if (
              !stageSpans(client.state, request.id).get(request.stage)?.length
            )
              throw new Error('This stage has no recorded work yet.');
            openTrajectory(conversation, {
              run: request.id,
              stage: request.stage,
            });
            return { state: client.state };
          });
        }
        if (request.action === 'run-detail') {
          const read = ++activityRunRead;
          return client.dispatch(request).then(async (reply) => {
            if (account.window.isDestroyed() || read !== activityRunRead)
              return { ...reply, state: client.state };
            const conversation =
              client.state.activity.details[request.id]?.wire?.orchestration
                .conductorConversation;
            await client.followView(
              `activity-run:${account.group}`,
              conversation ?? undefined,
            );
            return { ...reply, state: client.state };
          });
        }
        if (request.action === 'run-record')
          return client.dispatch(request).then(async (reply) => {
            await client.runs.navigation(request.id);
            return { ...reply, state: client.state };
          });
        if (
          request.action === 'activity-view' &&
          request.view !== 'runs' &&
          request.view !== 'builder'
        ) {
          activityRunRead++;
          background(client.followView(`activity-run:${account.group}`));
        }
        if (request.action === 'builder-trajectory') {
          if (client.state.authoring?.conversation !== request.conversation)
            throw new Error('Choose the authoring caller conversation.');
          openTrajectory(request.conversation);
          return { state: client.state };
        }
        if (request.action === 'run-trajectory') {
          openTrajectory(client.runs.conversation(request.id, request.actor));
          return { state: client.state };
        }
        if (request.action === 'activity-view') {
          if (
            request.view !== 'inbox' &&
            request.view !== 'runs' &&
            request.view !== 'definitions' &&
            request.view !== 'schedules' &&
            request.view !== 'builder'
          )
            throw new Error('Choose an Activity view.');
          if (activityGroup(request.view) !== account.group)
            throw new Error('Open this section from the workspace sidebar.');
          account.view = request.view;
          sendWorkspace();
        }
        return client.dispatch(request);
      }
      if (main && request?.action === 'question-refresh')
        return client.dispatch(request);
      if (main && request?.action === 'run-answer') {
        const shown = client.state.activity.details[request.id]?.wire;
        if (
          !client.state.activity.runs.items.some(
            (run) => run.id === request.id && run.state === 'asking',
          ) ||
          !shown ||
          shown.orchestration.state !== 'asking' ||
          runQuestion(shown) !== request.question
        ) {
          throw new Error(
            'Choose a current pending question before answering.',
          );
        }
        return client.dispatch(request);
      }
      if (
        [
          'builder-outputs',
          'builder-trajectory',
          'builder-prepare',
          'builder-start',
          'activity-view',
          'activity-refresh',
          'inbox-read',
          'inbox-more',
          'run-detail',
          'run-definitions',
          'run-record',
          'run-answer',
          'run-cancel',
          'run-resume',
          'run-trajectory',
          'run-stage-trajectory',
          'delegate-trajectory',
          'schedule-refresh',
          'schedule-preview',
          'schedule-save',
          'schedule-file-save',
          'schedule-sync',
          'schedule-change',
          'schedule-fire',
        ].includes(request?.action)
      )
        throw new Error('Open Activity to manage account activity.');
      if (request.action === 'relay') {
        if (!main) throw new Error('Open Relay from the workspace sidebar.');
        openRelay();
        return { state: client.state };
      }
      if (request?.action === 'usage') {
        openUsage();
        return { state: client.state };
      }
      if (request?.action === 'library')
        return openLibrary(
          request.view,
          request.project ?? null,
          request.revision,
          request.chapter,
        ).then(() => ({ state: client.state }));
      if (request?.action === 'activity') {
        openActivity(request.view);
        return { state: client.state };
      }
      if (request?.action === 'trajectory') {
        openTrajectory(request.conversation);
        return { state: client.state };
      }
      if (request?.action === 'board-inspection') {
        openBoard(request.view, request.project);
        return { state: client.state };
      }
      if (request?.action === 'files-choose') {
        const token = client.selectionToken;
        if (!client.state.connected)
          throw new Error('Connect to the server before choosing files.');
        if (
          request.project !== undefined &&
          (typeof request.project !== 'string' ||
            !client.state.projects.some((row) => row.name === request.project))
        )
          throw new Error('Choose an available project.');
        if (
          request.project === client.state.personal?.project &&
          request.project
        )
          return client.dispatch({
            action: 'project-open',
            project: request.project,
          });
        const defaultPath =
          client.state.projectFolders?.find(
            (row) =>
              row.name === request.project &&
              row.machine === thisMachine(process.env, hostname()),
          )?.path ??
          client.state.projects.find(
            (row) =>
              row.name === request.project &&
              row.machine === thisMachine(process.env, hostname()),
          )?.workspace;
        return dialog
          .showOpenDialog(window!, {
            title: 'Allow project file access',
            ...(defaultPath === undefined ? {} : { defaultPath }),
            buttonLabel: 'Allow file access',
            properties: ['openDirectory'],
            message:
              'Allow Plowshare agents to read and change files in this folder. Access is remembered on this computer. Local commands follow its environment settings.',
          })
          .then((choice) => {
            if (client.selectionToken !== token)
              throw new Error('The connection changed while choosing files.');
            if (choice.canceled || !choice.filePaths[0])
              return { state: client.state };
            return client.rootDirectory(choice.filePaths[0], request.project);
          });
      }
      return client.dispatch(request);
    });
    openWindow();
    app.on('activate', () => {
      if (!window) openWindow();
    });
  }),
);
app.on('window-all-closed', () => app.quit());
let quitting = false;
app.on('before-quit', (event) => {
  if (quitting) return;
  event.preventDefault();
  quitting = true;
  // Stop local commands and withdraw presence before ending the process.
  background(client.shutdown().finally(() => app.quit()));
});

/** Native event work reports unexpected errors through the main-window error dialog. */
function background(work: Promise<unknown>): void {
  void work.catch((reason: unknown) =>
    dialog.showErrorBox('Background task failed', errorMessage(reason)),
  );
}
