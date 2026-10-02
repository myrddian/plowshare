import { JobJournal } from './job-store.ts';
import type { LibraryView } from './library-shared.ts';
import { inspectionConversation } from './shared.ts';
import type { BoardView } from './board-shared.ts';
import { app, BrowserWindow, ipcMain, protocol, net, session, shell, clipboard, dialog } from 'electron';
import { join } from 'node:path';
import { mkdirSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { DesktopWorkspace } from './workspace.ts';
import { ProjectConfig } from './project-config.ts';
import { ConnectionConfig, desktopConfigDirectory } from './connection-config.ts';
import { serverConnector } from './client.ts';
import { hostname } from 'node:os';
import { thisMachine } from 'plowshare-client-node/marker';
import type { Request, DesktopState, ActivityView } from './shared.ts';
import { webAddress } from './renderer/markdown.ts';
import { desktopProfile } from './profile.ts';

const rendererUrl = 'plowshare://app/index.html';
const boardUrl = 'plowshare://app/board.html';
const trajectoryUrl = 'plowshare://app/trajectory.html';
const libraryUrl = 'plowshare://app/library.html';
const libraryActions = ['library-view', 'library-refresh', 'library-documents', 'library-document', 'library-memory', 'library-chunk', 'library-citations', 'library-stance', 'library-search', 'library-maintain', 'library-conversation'];
const activityUrl = 'plowshare://app/activity.html';
protocol.registerSchemesAsPrivileged([{ scheme: 'plowshare', privileges: { standard: true, secure: true, supportFetchAPI: true } }]);
app.setName('Plowshare');
const profile = desktopProfile(app.isPackaged, app.getAppPath(), app.getPath('appData'), process.env['PLOWSHARE_DESKTOP_PROFILE']);
mkdirSync(profile, { recursive: true });
app.setPath('userData', profile);
let window: BrowserWindow | undefined;
const trajectories = new Map<string, { window: BrowserWindow; conversation: string }>();
let activity: { window: BrowserWindow; view: ActivityView } | undefined;
let libraryWindow: BrowserWindow | undefined;
let boardWindow: BrowserWindow | undefined;
let identity = '';
const identityOf = (state: DesktopState) => state.mode === 'demo' ? 'demo' : `${state.base}|${state.handle}`;
const configDirectory = desktopConfigDirectory();
const client = new DesktopWorkspace(state => {
  const nextIdentity = identityOf(state);
  if (identity && identity !== nextIdentity) { closeTrajectories(); activity?.window.close(); boardWindow?.close(); libraryWindow?.close(); }
  identity = nextIdentity;
  if (window && !window.isDestroyed()) window.webContents.send('plowshare:state', state);
  if (boardWindow && !boardWindow.isDestroyed()) boardWindow.webContents.send('plowshare:state', state);
  if (libraryWindow && !libraryWindow.isDestroyed()) libraryWindow.webContents.send('plowshare:state', state);
  for (const held of trajectories.values()) {
    if (!held.window.isDestroyed()) held.window.webContents.send('plowshare:state', state);
  }
  if (activity && !activity.window.isDestroyed()) activity.window.webContents.send('plowshare:state', state);
}, new ProjectConfig(configDirectory), serverConnector, new ConnectionConfig(configDirectory), new JobJournal(configDirectory));

function closeTrajectories() {
  for (const held of [...trajectories.values()]) held.window.close();
}
function secureWindow(view: BrowserWindow) {
  view.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  view.webContents.on('will-navigate', event => event.preventDefault());
  view.webContents.on('will-attach-webview', event => event.preventDefault());
}
function openTrajectory(conversation: string) {
  if (typeof conversation !== 'string' || !inspectionConversation(client.state, conversation)) {
    throw new Error('Choose an available conversation to inspect.');
  }
  const existing = trajectories.get(conversation);
  if (existing && !existing.window.isDestroyed()) {
    if (existing.window.isMinimized()) existing.window.restore();
    existing.window.show(); existing.window.focus(); return;
  }
  const view = new BrowserWindow({
    width: 1480, height: 900, minWidth: 900, minHeight: 620,
    title: 'Trajectory · Plowshare', backgroundColor: '#151515', titleBarStyle: 'hiddenInset',
    trafficLightPosition: { x: 18, y: 18 }, show: false,
    webPreferences: { preload: join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true },
  });
  trajectories.set(conversation, { window: view, conversation });
  void client.followView(`trajectory:${conversation}`, conversation);
  secureWindow(view);
  view.once('ready-to-show', () => view.show());
  view.on('closed', () => {
    trajectories.delete(conversation);
    void client.followView(`trajectory:${conversation}`);
  });
  void view.loadURL(trajectoryUrl);
}

function openActivity(tab: ActivityView) {
  if (tab !== 'inbox' && tab !== 'runs' && tab !== 'definitions' && tab !== 'schedules' && tab !== 'builder') throw new Error('Choose an Activity view.');
  if (activity && !activity.window.isDestroyed()) {
    activity.view = tab;
    if (activity.window.isMinimized()) activity.window.restore();
    activity.window.show(); activity.window.focus(); void client.dispatch({ action: 'activity-view', view: tab }); return;
  }
  const view = new BrowserWindow({ width: 1160, height: 800, minWidth: 860, minHeight: 620,
    title: 'Activity · Plowshare', backgroundColor: '#151515', titleBarStyle: 'hiddenInset',
    trafficLightPosition: { x: 18, y: 18 }, show: false,
    webPreferences: { preload: join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true },
  });
  activity = { window: view, view: tab }; secureWindow(view);
  view.once('ready-to-show', () => view.show());
  view.on('closed', () => { if (activity?.window === view) { activity = undefined; client.runs.pause(); void client.activity.open(); } });
  void client.dispatch({ action: 'activity-view', view: tab });
  void view.loadURL(activityUrl);
}

async function openLibrary(tab: LibraryView, project: string | null = null) {
  if (!['documents','memories','search'].includes(tab)) throw new Error('Choose a Library view.');
  if (project !== null && !client.state.projects.some(row => row.name === project)) throw new Error('Choose an available project.');
  if (libraryWindow && !libraryWindow.isDestroyed()) {
    if (libraryWindow.isMinimized()) libraryWindow.restore();
    libraryWindow.show(); libraryWindow.focus(); await client.dispatch({action:'library-view',view:tab,project}); return;
  }
  const native = new BrowserWindow({width:1240,height:860,minWidth:860,minHeight:620,title:'Library · Plowshare',backgroundColor:'#151515',titleBarStyle:'hiddenInset',trafficLightPosition:{x:18,y:18},show:false,
    webPreferences:{preload:join(__dirname,'preload.cjs'),contextIsolation:true,nodeIntegration:false,sandbox:true,webSecurity:true}});
  libraryWindow=native; secureWindow(native); native.once('ready-to-show',()=>native.show());
  native.on('closed',()=>{if(libraryWindow===native) libraryWindow=undefined;});
  const reading = client.dispatch({action:'library-view',view:tab,project}); void native.loadURL(libraryUrl); await reading;
}

function openBoard(view: BoardView, project?: string) {
  if (view !== 'board' && view !== 'swarm') throw new Error('Choose Board or Swarm.');
  if (project !== undefined && (typeof project !== 'string' || !client.state.projects.some(p => p.name === project))) throw new Error('Choose an available project.');
  if (boardWindow && !boardWindow.isDestroyed()) {
    if (boardWindow.isMinimized()) boardWindow.restore();
    boardWindow.show(); boardWindow.focus(); void client.board.open(view, project); return;
  }
  const native = new BrowserWindow({ width: 1360, height: 900, minWidth: 900, minHeight: 640,
    title: 'Board & Swarm · Plowshare', backgroundColor: '#151515', titleBarStyle: 'hiddenInset',
    trafficLightPosition: { x: 18, y: 18 }, show: false,
    webPreferences: { preload: join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true },
  });
  boardWindow = native; secureWindow(native);
  native.once('ready-to-show', () => native.show());
  native.on('closed', () => { if (boardWindow === native) { boardWindow = undefined; void client.board.open(); } });
  void client.board.open(view, project); void native.loadURL(boardUrl);
}

function openWindow() {
  window = new BrowserWindow({
    width: 1440, height: 960, minWidth: 860, minHeight: 640,
    title: 'Plowshare', backgroundColor: '#10110f', titleBarStyle: 'hiddenInset',
    trafficLightPosition: { x: 18, y: 18 }, show: false,
    webPreferences: { preload: join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true },
  });
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', event => event.preventDefault());
  window.webContents.on('will-attach-webview', event => event.preventDefault());
  window.once('ready-to-show', () => window?.show());
  window.on('closed', () => { window = undefined; void client.followView('chat'); closeTrajectories(); activity?.window.close(); boardWindow?.close(); libraryWindow?.close(); });
  void window.loadURL(rendererUrl);
}

void app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  session.defaultSession.setPermissionCheckHandler(() => false);
  protocol.handle('plowshare', request => {
    const url = new URL(request.url);
    const file = url.pathname.slice(1);
    if (url.host !== 'app' || !['index.html', 'trajectory.html', 'activity.html', 'style.css', 'desktop.css', 'app.js', 'trajectory.js', 'activity.js', 'board.html', 'board.css', 'board.js', 'library.html', 'library.js', 'library.css'].includes(file)) return new Response('Not found', { status: 404 });
    return net.fetch(pathToFileURL(join(__dirname, 'renderer', file)).href);
  });
  ipcMain.handle('plowshare:request', (event, request: Request) => {
    const main = window && !window.isDestroyed() && event.sender === window.webContents;
    const held = [...trajectories.values()].find(row => !row.window.isDestroyed() && row.window.webContents === event.sender);
    const account = activity && !activity.window.isDestroyed() && event.sender === activity.window.webContents;
    const board = boardWindow && !boardWindow.isDestroyed() && event.sender === boardWindow.webContents;
    const library = libraryWindow && !libraryWindow.isDestroyed() && event.sender === libraryWindow.webContents;
    if ((!main && !held && !account && !board && !library) || event.senderFrame !== event.sender.mainFrame || event.senderFrame.url !== (main ? rendererUrl : held ? trajectoryUrl : account ? activityUrl : board ? boardUrl : libraryUrl)) {
      throw new Error('Desktop requests must come from a registered application window.');
    }
    if (request?.action === 'open-link') {
      const address = typeof request.url === 'string' && request.url.length < 8192 ? webAddress(request.url) : undefined;
      if (!address) throw new Error('Only http or https links without embedded credentials can be opened.');
      return shell.openExternal(address).then(() => ({ state: client.state }));
    }
    if (request?.action === 'copy-text') {
      if (typeof request.text !== 'string' || request.text.length > 1048576) throw new Error('Copy text must be a string of at most 1,048,576 characters.');
      clipboard.writeText(request.text);
      return { state: client.state };
    }
    if (request?.action?.startsWith('operator-') && !main) throw new Error('Workspace controls belong to the main window.');
    if (library) {
      if (request?.action === 'bootstrap') return {state:client.state};
      if (!libraryActions.includes(request?.action)) throw new Error('Library windows can only read Library information and maintain displayed memories.');
      if (request.action === 'library-conversation') { openTrajectory(client.library.conversation(request.id)); return {state:client.state}; }
      return client.dispatch(request);
    }
    if (libraryActions.includes(request?.action)) throw new Error('Open Library to inspect documents and memories.');
    if (board) {
      if (request?.action === 'bootstrap') return { state: client.state, boardView: client.state.board.view };
      if (request?.action === 'board-trajectory') { client.board.conversation(request.conversation); openTrajectory(request.conversation); return { state: client.state }; }
      if (!['board-view', 'board-refresh', 'board-more', 'board-topic'].includes(request?.action)) throw new Error('Board windows can only inspect displayed board and swarm information.');
      return client.dispatch(request);
    }
    if (['board-view', 'board-refresh', 'board-more', 'board-topic', 'board-trajectory'].includes(request?.action)) throw new Error('Open Board or Swarm to inspect its topics.');
    if (held) {
      if (request?.action === 'bootstrap') return { state: client.state, conversation: held.conversation };
      if (request?.action === 'answer') {
        const approval = client.state.approvals.find(row => row.id === request.id && row.state === 'asked');
        if (!approval || (approval.conversation !== held.conversation && approval.askedIn !== held.conversation)) throw new Error('Only this trajectory’s pending approvals can be answered here.');
        return client.dispatch(request);
      }
      if (request?.action !== 'history' || request.conversation !== held.conversation) throw new Error('Trajectory windows can only read their own conversation.');
      return client.dispatch(request);
    }
    if (account) {
      if (request?.action === 'bootstrap') return { state: client.state, view: activity!.view };
      if (request?.action === 'answer' || request?.action === 'approvals-refresh') return client.dispatch(request);
      if (!['builder-outputs', 'builder-trajectory', 'builder-prepare', 'builder-start', 'activity-view', 'activity-refresh', 'inbox-read', 'inbox-more', 'run-detail', 'run-definitions', 'run-record', 'run-answer', 'run-cancel', 'run-trajectory', 'schedule-refresh', 'schedule-preview', 'schedule-save', 'schedule-change', 'schedule-fire'].includes(request?.action)) throw new Error('Activity windows can only manage account activity.');
      if (request.action === 'builder-trajectory') { if (client.state.authoring?.conversation !== request.conversation) throw new Error('Choose the authoring caller conversation.'); openTrajectory(request.conversation); return { state: client.state }; }
      if (request.action === 'run-trajectory') { openTrajectory(client.runs.conversation(request.id, request.actor)); return { state: client.state }; }
      if (request.action === 'activity-view') {
        if (request.view !== 'inbox' && request.view !== 'runs' && request.view !== 'definitions' && request.view !== 'schedules' && request.view !== 'builder') throw new Error('Choose an Activity view.');
        activity!.view = request.view;
      }
      return client.dispatch(request);
    }
    if (['builder-outputs', 'builder-trajectory', 'builder-prepare', 'builder-start', 'activity-view', 'activity-refresh', 'inbox-read', 'inbox-more', 'run-detail', 'run-definitions', 'run-record', 'run-answer', 'run-cancel', 'run-trajectory', 'schedule-refresh', 'schedule-preview', 'schedule-save', 'schedule-change', 'schedule-fire'].includes(request?.action)) throw new Error('Open Activity to manage account activity.');
    if (request?.action === 'library') return openLibrary(request.view,request.project ?? null).then(()=>({state:client.state}));
    if (request?.action === 'activity') { openActivity(request.view); return { state: client.state }; }
    if (request?.action === 'trajectory') {
      openTrajectory(request.conversation);
      return { state: client.state };
    }
    if (request?.action === 'board-inspection') { openBoard(request.view, request.project); return { state: client.state }; }
    if (request?.action === 'files-choose') {
      const token = client.selectionToken;
      if (!client.state.connected) throw new Error('Connect to the server before choosing files.');
      if (request.project !== undefined && (typeof request.project !== 'string' || !client.state.projects.some(row => row.name === request.project))) throw new Error('Choose an available project.');
      return dialog.showOpenDialog(window!, { title: 'Allow project file access', defaultPath: client.state.projectFolders?.find(row => row.name === request.project && row.machine === thisMachine(process.env, hostname()))?.path ?? client.state.projects.find(row => row.name === request.project && row.machine === thisMachine(process.env, hostname()))?.workspace, buttonLabel: 'Allow file access', properties: ['openDirectory'], message: 'Allow Plowshare agents to read and change files in this folder. Access is remembered on this computer. Local commands follow its environment settings.' }).then(choice => {
        if (client.selectionToken !== token) throw new Error('The connection changed while choosing files.');
        if (choice.canceled || !choice.filePaths[0]) return { state: client.state };
        return client.rootDirectory(choice.filePaths[0], request.project);
      });
    }
    return client.dispatch(request);
  });
  openWindow();
  app.on('activate', () => { if (!window) openWindow(); });
});
app.on('window-all-closed', () => app.quit());
let quitting = false;
app.on('before-quit', event => {
  if (quitting) return;
  event.preventDefault(); quitting = true;
  // Stop local commands and withdraw presence before ending the process.
  void client.shutdown().finally(() => app.quit());
});
