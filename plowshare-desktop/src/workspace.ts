import type { JobStore, SavedJob } from './job-store.ts';
import { AUTHORING, authoringReady, authoringRequest } from 'plowshare-client-ts/operations/authoring';
import { homeKey } from './shared.ts';
import { inspectionConversation } from './shared.ts';
import { realpath } from 'node:fs/promises';
import { hostname } from 'node:os';
import { isAbsolute } from 'node:path';
import { DesktopClient, serverConnector } from './client.ts';
import type { Connector } from './client.ts';
import { identifyFolder } from './files.ts';
import { personalDirectory, readPersonal } from 'plowshare-client-node/personal';
import { thisMachine } from 'plowshare-client-node/marker';
import type { ProjectStore, SavedProject } from './project-config.ts';
import type { DesktopState, Reply, Request } from './shared.ts';
import type { ConnectionStore } from './connection-config.ts';
import { savedLoginServers } from 'plowshare-client-node/credentials';

/** Desktop composition: one account inspector and an ordinary client per project. */
export class DesktopWorkspace {
  readonly control: DesktopClient;
  private children = new Map<string, DesktopClient>();
  private openings = new Map<string, Promise<DesktopClient>>();
  private attachments = new Map<string, Promise<void>>();
  private requests = new Set<Promise<unknown>>();
  private closing = false;
  private operatorOwner?: DesktopClient;
  private owners = new Map<string, DesktopClient>();
  private viewReads = new Map<string, number>();
  private saved: SavedProject[] = [];
  private errors = new Map<string, string>();
  private configError?: string;
  private scope = '';
  private personal?: DesktopState['personal'];
  private authoring?: DesktopState['authoring'];
  private authoringIdentity = '';
  private authoringBusy = false;
  private changed: (state: DesktopState) => void;
  private store: ProjectStore;
  private token = {};
  private connected = false;
  private transitioning = false;
  private initialized = false;
  private restoring?: Promise<Reply>;
  private connectionStore?: ConnectionStore;
  private jobStore?: JobStore;
  private jobRestoring = false;
  private unrestoredJobs: SavedJob[] = [];
  private lastReceipts = '';
  constructor(changed: (state: DesktopState) => void, store: ProjectStore, connector: Connector = serverConnector, connectionStore?: ConnectionStore, jobStore?: JobStore) {
    this.changed = changed; this.store = store; this.connectionStore = connectionStore; this.jobStore = jobStore;
    this.control = new DesktopClient(state => {
      const lost = this.connected && !state.connected;
      this.connected = state.connected;
      if (lost && !this.transitioning) {
        this.token = {};
        for (const child of this.children.values()) void child.dispatch({ action: 'disconnect' }).catch(() => undefined);
      }
      this.emit();
    }, connector);
  }
  get selectionToken() { return this.token; }
  get library() { return this.control.library; }
  get runs() { return this.control.runs; }
  spawnedConversation(parent: string, key: string) {
    return this.control.runs.spawnedConversation(parent, key, this.state.history[parent]?.entries ?? []);
  }
  get activity() { return this.control.activity; }
  get board() { return this.control.board; }
  get state(): DesktopState {
    const state = structuredClone(this.control.state);
    state.personal = structuredClone(this.personal);
    if (this.operatorOwner) state.operator = structuredClone(this.operatorOwner.state.operator);
    if (this.authoringIdentity === `${state.base}|${state.handle}`) state.authoring = structuredClone(this.authoring);
    if (state.mode !== 'live') return state;
    if (this.transitioning) { state.connected = false; if (this.control.state.connected) state.connection = 'Opening projects…'; }
    const byId = <T extends { id: string }>(rows: T[]) => [...new Map(rows.map(row => [row.id, row])).values()];
    for (const child of this.children.values()) {
      // Account refreshes are authoritative; an older child snapshot must not hide a moved server path.
      state.projects = [...new Map([...child.state.projects, ...state.projects].map(row => [row.name, row])).values()];
      state.conversations = byId([...state.conversations, ...child.state.conversations]);
      state.jobs = byId([...state.jobs, ...child.state.jobs]);
      state.approvals = byId([...state.approvals, ...child.state.approvals]);
      state.answeringApprovals = [...new Set([...(state.answeringApprovals ?? []), ...(child.state.answeringApprovals ?? [])])];
      Object.assign(state.agents, child.state.agents);
      Object.assign(state.history, child.state.history);
      Object.assign(state.contexts, child.state.contexts);
      state.contextSnapshots = { ...state.contextSnapshots, ...child.state.contextSnapshots };
    }
    state.projects = [...new Map([...this.saved.map(row => ({ name: row.name })), ...state.projects].map(row => [row.name, row])).values()];
    state.projectFolders = this.saved.map(row => {
      const child = this.children.get(row.name);
      const files = child?.state.files ?? { status: 'off' as const };
      return { name: row.name, path: row.path, machine: row.machine, enabled: row.enabled,
        connected: child?.state.connected ?? false, files, sync: child?.state.sync,
        ...(this.errors.has(row.name) ? { error: this.errors.get(row.name) } : {}) };
    });
    if (state.personal && this.errors.has(state.personal.project)) state.personal.error = this.errors.get(state.personal.project);
    state.projectConfigError = this.configError;
    state.localMachine = thisMachine(process.env, hostname());
    state.projectPreparing = [...this.attachments.keys()];
    state.files = this.children.get(this.scope)?.state.files ?? { status: 'off' };
    state.sync = this.children.get(this.scope)?.state.sync;
    state.liveHistory = this.children.get(this.scope)?.state.liveHistory ?? state.liveHistory;
    return state;
  }
  private emit() {
    this.changed(this.state);
    if (!this.jobStore || this.jobRestoring || this.transitioning || this.closing || this.control.state.mode !== 'live') return;
    const { base, handle } = this.control.state;
    if (!handle) return;
    const rows = [...this.unrestoredJobs, ...this.control.savedJobs(), ...[...this.children.values()].flatMap(child => child.savedJobs())].filter(row => !row.project?.startsWith('client:'));
    const receipt = JSON.stringify([base, handle, rows]); if (receipt === this.lastReceipts) return;
    this.lastReceipts = receipt;
    const token = this.token;
    void this.jobStore.save(base, handle, rows).catch(error => {
      if (token !== this.token) return;
      this.control.state.jobRecoveryError = `Job recovery could not be saved: ${String(error)}`; this.changed(this.state);
    });
  }
  private same(token: object) { if (token !== this.token) throw new Error('The account connection changed.'); }
  private requireLive() { if (this.closing || !this.control.state.connected) throw new Error('Connect to the server before opening a project.'); }
  private track<T>(work: Promise<T>): Promise<T> {
    this.requests.add(work);
    return work.finally(() => this.requests.delete(work));
  }
  private async clientFor(project?: string): Promise<DesktopClient> {
    if (!project || this.control.state.mode === 'demo') return this.control;
    this.requireLive();
    if (!this.state.projects.some(row => row.name === project) && !this.state.conversations.some(row => row.project === project)) throw new Error('Choose an available project.');
    const token = this.token;
    const existing = this.children.get(project);
    if (existing?.state.connected) return existing;
    const underway = this.openings.get(project); if (underway) return underway;
    let child = existing;
    if (!child) {
      child = new DesktopClient(() => {
        if (this.children.get(project) === child) {
          if (child?.state.connection.startsWith('Connection lost')) this.errors.set(project, 'Project connection lost. Reconnect files to restore this project.');
          this.emit();
        }
      },
        (_base, _handle, _password, push, closed) => this.control.openPeer(push, closed), { project, accountActivity: false });
      this.children.set(project, child);
    }
    const selected = child;
    const operation = (async () => {
      await selected.dispatch({ action: 'connect', base: this.control.state.base, handle: this.control.state.handle, password: 'authenticated-project-session' });
      this.same(token); return selected;
    })();
    this.openings.set(project, operation);
    try { return await operation; }
    finally { if (this.openings.get(project) === operation) this.openings.delete(project); }
  }
  private projectOfConversation(id: string) { return this.state.conversations.find(row => row.id === id)?.project; }
  private async conversationOwner(id: string) {
    const row = this.state.conversations.find(value => value.id === id);
    if (!row) {
      // A delegated conversation inherits the owning parent's authenticated session.
      // It need not be listed as a top-level chat, and must not be rerouted globally.
      const owner = [this.control, ...this.children.values()].find(client => inspectionConversation(client.state, id));
      if (owner) return owner;
    }
    if (!row) throw new Error('Choose an available conversation.');
    const owner = await this.clientFor(row.project);
    owner.retainConversation(row);
    return owner;
  }
  async followView(view: string, conversation?: string) {
    const read = (this.viewReads.get(view) ?? 0) + 1; this.viewReads.set(view, read);
    const token = this.token;
    if (!conversation) {
      const previous = this.owners.get(view); this.owners.delete(view);
      await previous?.followView(view); return;
    }
    if (!inspectionConversation(this.state, conversation)) throw new Error('Choose an available conversation to follow.');
    const owner = await this.conversationOwner(conversation);
    if (this.viewReads.get(view) !== read || token !== this.token) return;
    const previous = this.owners.get(view);
    if (previous && previous !== owner) { this.owners.delete(view); await previous.followView(view); }
    if (this.viewReads.get(view) !== read || token !== this.token) return;
    this.owners.set(view, owner); await owner.followView(view, conversation);
  }
  private async closeChildren(clear = false) {
    if (clear) this.owners.clear();
    this.viewReads.clear();
    const children = [...this.children.values()];
    this.openings.clear();
    this.attachments.clear();
    if (clear) this.children.clear();
    await Promise.allSettled(children.map(child => child.dispatch({ action: 'disconnect' })));
  }
  async shutdown() {
    await this.jobStore?.flush();
    this.closing = true; this.transitioning = true; this.token = {};
    const pending = [...this.requests, ...this.openings.values(), ...this.attachments.values()];
    await Promise.allSettled([...this.children.values()].map(child => child.shutdown()));
    await this.control.shutdown();
    // Authentication must finish releasing its credential lock before Electron exits.
    await Promise.allSettled(pending);
  }
  rootDirectory(directory: string, project?: string, selectProject = true): Promise<Reply> {
    return this.track(this.root(directory, project, selectProject));
  }
  private async root(directory: string, project?: string, selectProject = true): Promise<Reply> {
    this.requireLive(); const token = this.token;
    const folder = await identifyFolder(directory, project); this.same(token);
    if (folder.kind === 'DISJOINT') {
      const held = [...this.children.entries()].find(([key, value]) => key.startsWith('client:') && value.state.files.root === folder.root);
      if (held) { if (selectProject) this.scope = held[0]; this.emit(); return {state:this.state,rootProject:held[0]}; }
      let key: string | undefined;
      const child = new DesktopClient(() => { if (key && this.children.get(key) === child) this.emit(); },
        (_base, _handle, _password, push, closed) => this.control.openPeer(push, closed), {accountActivity:false});
      try {
        await child.dispatch({action:'connect',base:this.control.state.base,handle:this.control.state.handle,password:'authenticated-project-session'}); this.same(token);
        const attached = await child.rootDirectory(folder.root, folder.project); this.same(token);
        key = attached.rootProject;
        const old = this.children.get(key); if (old) await old.shutdown();
        this.children.set(key,child);
        const mapping: SavedProject = {server:this.control.state.base,account:this.control.state.handle,name:key,path:folder.root,machine:thisMachine(process.env,hostname()),enabled:true};
        if (!key.startsWith('client:')) await this.store.put(mapping);
        this.same(token); this.saved = [...this.saved.filter(row=>row.name!==key),mapping];
        this.errors.delete(key); if (selectProject) this.scope=key; this.emit();
        return {state:this.state,rootProject:key};
      } catch (error) { if (key && this.children.get(key) === child) this.children.delete(key); await child.shutdown(); throw error; }
    }
    const mapping: SavedProject = { server: this.control.state.base, account: this.control.state.handle,
      name: folder.project, path: folder.root, machine: thisMachine(process.env, hostname()), enabled: true };
    const previous = this.saved.find(row => row.name === mapping.name);
    await this.store.put(mapping); this.same(token);
    this.saved = [...this.saved.filter(row => row.name !== mapping.name), mapping];
    this.errors.delete(mapping.name); if (selectProject) this.scope = mapping.name; this.emit();
    try { await this.startProject(mapping, token); }
    catch (error) {
      if (previous && token === this.token) {
        await this.store.put(previous); this.same(token);
        this.saved = [...this.saved.filter(row => row.name !== previous.name), previous]; this.emit();
      }
      throw error;
    }
    return { state: this.state, rootProject: mapping.name };
  }
  private async mountPersonal(token: object) {
    const project = this.control.state.projects.find(row => row.kind === 'personal');
    if (!project) { this.personal = undefined; return; }
    this.personal = { project: project.name };
    try {
      const root = await personalDirectory(this.control.state.base, this.control.state.handle, project.name); this.same(token);
      this.personal.root = root;
      const mapping: SavedProject = { server: this.control.state.base, account: this.control.state.handle,
        name: project.name, path: root, machine: thisMachine(process.env, hostname()), enabled: true };
      this.saved = [...this.saved.filter(row => row.name !== project.name), mapping];
      this.scope = project.name;
    } catch (error) { this.personal.error = error instanceof Error ? error.message : String(error); }
  }

  private async startProject(mapping: SavedProject, token: object) {
    try {
      if (mapping.machine !== thisMachine(process.env, hostname())) throw new Error('This saved folder belongs to another machine. Choose its location on this computer.');
      if (await realpath(mapping.path) !== mapping.path) throw new Error('The saved folder moved or became a symbolic link. Choose its location again.');
      this.same(token);
      const child = await this.clientFor(mapping.name); this.same(token);
      await child.rootDirectory(mapping.path, mapping.name); this.same(token);
      this.control.state.projects = [...this.control.state.projects.filter(row => row.name !== mapping.name),
        { ...this.control.state.projects.find(row => row.name === mapping.name), name: mapping.name, machine: mapping.machine, workspace: mapping.path }];
      this.errors.delete(mapping.name);
    } catch (error) {
      if (token === this.token) this.errors.set(mapping.name, error instanceof Error ? error.message : String(error));
      throw error;
    } finally { if (token === this.token) this.emit(); }
  }
  private async recoverProject(name: string, selectProject = true) {
    const token = this.token;
    const recorded = this.state.projects.find(row => row.name === name);
    if (!recorded?.workspace || !isAbsolute(recorded.workspace)) throw new Error('This project has no recorded absolute folder. Choose a folder.');
    if (recorded.machine !== thisMachine(process.env, hostname())) throw new Error('The recorded folder belongs to another machine or the server. Choose its location on this computer.');
    const root = await realpath(recorded.workspace); this.same(token);
    if (root !== recorded.workspace) throw new Error('The recorded folder moved or became a symbolic link. Choose its location again.');
    return this.rootDirectory(root, name, selectProject);
  }
  private async prepareProject(project?: string) {
    if (!project || this.control.state.mode !== 'live') return;
    const existing = this.attachments.get(project); if (existing) return existing;
    const mapping = this.saved.find(row => row.name === project);
    if (mapping && !mapping.enabled) return; // An explicit pause is not undone by navigation.
    if (this.children.get(project)?.state.files.status === 'ready') return;
    // Recorded paths are offered for approval in the GUI. Navigation grants no new access.
    if (!mapping) return;
    const token = this.token;
    const operation = (async () => {
      await this.startProject(mapping, token);
    })();
    this.attachments.set(project, operation);
    this.emit();
    try { await operation; }
    finally { if (this.attachments.get(project) === operation) { this.attachments.delete(project); this.emit(); } }
  }
  dispatch(request: Request): Promise<Reply> {
    if (this.closing) return Promise.reject(new Error('The desktop is closing.'));
    return this.track(this.dispatchRequest(request));
  }
  private async dispatchRequest(request: Request): Promise<Reply> {
    if (!request || typeof request !== 'object') throw new Error('Invalid desktop request.');
    if (request.action === 'bootstrap') {
      if (this.restoring) return this.restoring;
      if (this.initialized || !this.connectionStore) return { state: this.state };
      this.initialized = true;
      this.restoring = this.restoreConnection();
      try { return await this.restoring; } finally { this.restoring = undefined; }
    }
    if (request.action === 'server-setup') {
      const login = await this.control.initializeAdministrator(request);
      request = { action: 'connect', ...login, password: '' };
    }
    if (request.action === 'connect') {
      this.initialized = true;
      const login = await this.control.login(request);
      if (this.closing) throw new Error('The desktop is closing.');
      const { base, handle } = login;
      request = { ...request, ...login };
      const sameAccount = this.control.state.mode === 'live' && this.control.state.base === base && this.control.state.handle === handle;
      this.transitioning = true; this.token = {}; const token = this.token;
      await this.closeChildren(!sameAccount); this.same(token);
      this.operatorOwner = undefined; this.unrestoredJobs = []; this.saved = []; this.errors.clear(); this.configError = undefined;
      try { this.saved = await this.store.list(base, handle); }
      catch (error) { this.configError = `Project configuration could not be read: ${String(error)}`; }
      this.same(token);
      try {
        await this.control.dispatch(request); this.same(token);
        await this.rememberConnection(true); this.same(token);
        await this.mountPersonal(token); this.same(token);
        // Personal is the fixed account mount; other folders are explicit choices.
        await Promise.allSettled(this.saved.filter(row => row.enabled).map(row => this.startProject(row, token)));
        this.same(token);
        if (this.jobStore) {
          this.jobRestoring = true;
          try {
            const jobs = (await this.jobStore.load(base, handle)).filter(row => !row.project?.startsWith('client:')); this.same(token);
            for (const row of jobs) {
              try { const owner = await this.clientFor(row.project); this.same(token); await owner.restoreJobs([row]); }
              catch (error) { this.same(token); this.unrestoredJobs.push(row); this.control.state.jobRecoveryError = `Receipt ${row.id} is retained, but its project could not be restored: ${String(error)}`; }
            }
          } catch (error) { this.same(token); this.control.state.jobRecoveryError = `Saved jobs could not be restored: ${String(error)}`; }
          finally { if (token === this.token) this.jobRestoring = false; }
        }
      } finally { if (token === this.token) { this.transitioning = false; this.emit(); } }
      return { state: this.state };
    }
    if (request.action === 'disconnect' || request.action === 'demo') {
      this.personal = undefined;
      this.initialized = true;
      await this.rememberConnection(false);
      this.transitioning = true; this.token = {}; const token = this.token;
      await this.closeChildren(request.action === 'demo'); this.same(token);
      await this.control.dispatch(request); this.same(token);
      this.transitioning = false; this.emit(); return { state: this.state };
    }
    if (request.action === 'select') { await this.followView('chat', request.conversation); return { state: this.state }; }
    if (request.action === 'project-open' || request.action === 'project-remove' || request.action === 'files-withdraw') {
      this.requireLive(); const token = this.token;
      const name = request.project ?? this.scope;
      if (name === this.personal?.project && request.action !== 'project-open') throw new Error('Personal space is always mounted for this account.');
      const mapping = this.saved.find(row => row.name === name);
      if (!mapping) {
        if (request.action !== 'project-open') throw new Error('Add a local folder for this project first.');
        return this.recoverProject(name);
      }
      if (request.action === 'project-open') {
        const enabled = { ...mapping, enabled: true };
        if (name !== this.personal?.project) await this.store.put(enabled); this.same(token);
        Object.assign(mapping, enabled); await this.startProject(mapping, token);
      } else {
        if (request.action === 'project-remove') await this.store.remove(mapping.server, mapping.account, name);
        else await this.store.put({ ...mapping, enabled: false });
        this.same(token);
        await this.children.get(name)?.files?.withdraw(); this.same(token);
        if (request.action === 'project-remove') this.saved = this.saved.filter(row => row.name !== name);
        else mapping.enabled = false;
        this.errors.delete(name);
      }
      this.emit(); return { state: this.state };
    }
    if (request.action === 'personal-bots') {
      this.requireLive(); const token = this.token;
      if (!this.personal) throw new Error('Personal space is unavailable.');
      const project = this.personal.project;
      try {
        const owner = await this.clientFor(project); this.same(token);
        const latest = await owner.botConversations(project); this.same(token);
        this.personal.botLatest = latest;
        delete this.personal.botsError;
      } catch (error) { this.same(token); this.personal.botsError = error instanceof Error ? error.message : String(error); }
      this.emit(); return { state: this.state };
    }
    if (request.action === 'personal-section') {
      this.requireLive(); const token = this.token;
      if (!this.personal?.root) throw new Error(this.personal?.error || 'Personal space is unavailable.');
      this.personal.section = request.section;
      this.personal.path = request.path ?? request.section;
      this.personal.entries = [];
      delete this.personal.text; delete this.personal.note;
      try {
        const content = await readPersonal(this.personal.root, request.section, request.path); this.same(token);
        Object.assign(this.personal, content); delete this.personal.error;
        if (content.text === undefined) delete this.personal.text;
        if (content.note === undefined) delete this.personal.note;
      } catch (error) { this.same(token); this.personal.error = String(error); }
      this.emit(); return { state: this.state };
    }
    if (request.action === 'operator-prepare') {
      this.requireLive(); const token = this.token;
      if (request.kind === 'caps') await this.prepareProject(request.project);
      const owner = await this.clientFor(request.project); this.same(token); this.operatorOwner = owner;
      await owner.dispatch(request); this.same(token); this.emit(); return { state: this.state };
    }
    if (request.action === 'operator-preview' || request.action === 'operator-apply' || request.action === 'operator-messages') {
      this.requireLive(); const owner = this.operatorOwner;
      if (!owner?.state.connected) throw new Error('Read this control again.');
      await owner.dispatch(request); this.emit(); return { state: this.state };
    }
    if (request.action === 'builder-outputs') {
      const wire = this.control.state.activity.details[request.id]?.wire;
      if (wire?.orchestration.definition !== AUTHORING) throw new Error('Choose an authoring run before reading its validation outputs.');
      const conversation = this.control.runs.conversation(request.id, 'conductor');
      const owner = await this.conversationOwner(conversation);
      await owner.dispatch({ action: 'history', conversation }); this.emit(); return { state: this.state };
    }
    if (request.action === 'builder-prepare' || request.action === 'builder-start') {
      this.requireLive(); const token = this.token;
      if (this.control.state.mode !== 'live') throw new Error('Connect before authoring.');
      if (request.action === 'builder-start' && this.authoringBusy) throw new Error('An authoring launch is already in progress.');
      const task = request.action === 'builder-start' ? authoringRequest(request.intent, request.revision) : undefined;
      if (request.action === 'builder-start') this.authoringBusy = true;
      let launched = false;
      try {
        await this.prepareProject(request.project); this.same(token);
        const owner = await this.clientFor(request.project); this.same(token);
        await owner.dispatch({ action: 'scope', project: request.project }); this.same(token);
        await this.control.runs.definitions(request.project); this.same(token);
        if (request.action === 'builder-prepare') { this.emit(); return { state: this.state }; }
        if (this.control.state.activity.definitions?.error || this.control.state.activity.definitions?.project !== request.project) throw new Error(this.control.state.activity.definitions?.error || 'Read this project’s definitions first.');
        const caller = owner.state.agents[homeKey(request.project)]?.find(row => row.name === request.agent);
        authoringReady(request.project, owner.state.files.status === 'ready' && owner.state.files.project === request.project,
          caller, this.control.state.activity.definitions?.items.find(row => row.name === AUTHORING));
        if (request.revision && !this.control.state.activity.definitions?.items.some(row => row.name === request.revision && row.served)) throw new Error('Choose an available definition to revise.');
        const identity = `${this.control.state.base}|${this.control.state.handle}`;
        const previousRun = this.authoringIdentity === identity && this.authoring?.conversation ? this.control.state.activity.runs.items.find(row => row.definition === AUTHORING && row.project === this.authoring?.project && row.callerConversation === this.authoring?.conversation) : undefined;
        if (this.authoringIdentity === identity && this.authoring?.status === 'unknown' && !previousRun) throw new Error('The previous launch is uncertain. Inspect its caller trajectory and Runs before starting another.');
        const activeRun = previousRun && ['running', 'asking', 'waiting'].includes(previousRun.state);
        const activeCaller = this.state.jobs.some(row => row.conversation === this.authoring?.conversation && ['starting', 'running', 'cancelling', 'unknown'].includes(row.status));
        if (this.authoringIdentity === identity && (activeRun || !previousRun && activeCaller)) throw new Error('The current authoring work is still active. Follow its run before starting another.');
        this.authoringIdentity = identity;
        this.authoring = { project: request.project, agent: request.agent, intent: request.intent, revision: request.revision, status: 'launching' }; launched = true; this.emit();
        const opened = await owner.dispatch({ action: 'create', project: request.project }); this.same(token);
        if (!opened.conversation) throw new Error('The server did not return an authoring conversation.');
        this.authoring.conversation = opened.conversation; this.emit();
        if (owner.state.files.status !== 'ready' || owner.state.files.project !== request.project) throw new Error('The project folder disconnected before the authoring turn.');
        // One send only. An ambiguous result is retained for inspection, never replayed.
        await owner.authoringTurn(opened.conversation, request.agent, `${task}\n\nConnected project workspace (data): ${JSON.stringify({ project: request.project, root: owner.state.files.root })}. Verify with file_roots; write drafts only in this run’s artifacts directory.`); this.same(token);
        this.authoring.status = 'submitted';
        await this.control.activity.refresh(); this.same(token);
        return { state: this.state, conversation: opened.conversation };
      } catch (error) {
        if (launched && this.authoring?.status === 'launching') { this.authoring.status = 'unknown'; this.authoring.error = error instanceof Error ? error.message : String(error); }
        throw error;
      } finally { if (request.action === 'builder-start') this.authoringBusy = false; this.emit(); }
    }
    let owner = this.control;
    if (request.action.startsWith('sync-') && 'project' in request) {
      this.requireLive();
      if (typeof request.project !== 'string' || !this.saved.some(row => row.name === request.project)) throw new Error('Choose a project with a local folder.');
      owner = await this.clientFor(request.project);
      if (owner.state.files.status !== 'ready' || owner.state.files.project !== request.project) throw new Error('Reconnect this project’s files before synchronizing.');
    } else if (request.action === 'scope' || request.action === 'create') {
      if (request.action === 'scope') this.scope = request.project ?? '';
      try { await this.prepareProject(request.project); }
      catch (error) {
        if (request.action !== 'scope') throw error;
        owner = await this.clientFor(request.project);
        await owner.dispatch(request);
        return { state: this.state, notice: `Project files unavailable: ${error instanceof Error ? error.message : String(error)}. Use Connect files to choose the folder.` };
      }
      owner = await this.clientFor(request.project);
    } else if (request.action === 'workflow-start' || request.action === 'run' || request.action === 'history' || request.action === 'context' || request.action === 'context-snapshot') owner = await this.conversationOwner(request.conversation);
    else if (request.action === 'cancel') owner = [...this.children.values(), this.control].find(client => client.state.jobs.some(row => row.id === request.job)) ?? this.control;
    else if (request.action === 'answer') {
      const approval = this.state.approvals.find(row => row.id === request.id);
      if (approval) owner = await this.conversationOwner(approval.conversation);
    }
    if (request.action === 'refresh' || request.action === 'approvals-refresh') {
      await Promise.all([this.control.dispatch(request), ...[...this.children.values()].filter(child => child.state.connected).map(child => child.dispatch(request))]);
      this.emit(); return { state: this.state };
    }
    if (request.action === 'run') {
      await this.prepareProject(this.projectOfConversation(request.conversation));
      const mapping = this.saved.find(row => row.name === this.projectOfConversation(request.conversation));
      if (mapping && owner.state.files.status !== 'ready') throw new Error('Project files are disconnected. Reconnect files before sending.');
    }
    const reply = await owner.dispatch(request);
    if (request.action === 'answer') await Promise.all([this.control, ...this.children.values()].filter(child => child !== owner && child.state.connected).map(child => child.dispatch({ action: 'approvals-refresh' })));
    this.emit(); return { ...reply, state: this.state };
  }
  private async rememberConnection(reconnect: boolean) {
    if (!this.connectionStore || this.control.state.mode !== 'live') return;
    try {
      await this.connectionStore.save({ server: this.control.state.base, account: this.control.state.handle, reconnect });
      delete this.control.state.connectionPersistenceError;
    } catch {
      this.control.state.connectionPersistenceError = 'Connection details could not be saved. Reconnect manually after restarting the desktop.';
    }
  }
  private async restoreConnection(): Promise<Reply> {
    const token = this.token;
    try {
      let saved = await this.connectionStore!.load();
      if (!saved) {
        const servers = await savedLoginServers();
        if (servers.length === 1) saved = { ...servers[0]!, reconnect: true };
      }
      if (token !== this.token) return { state: this.state };
      if (!saved) return { state: this.state };
      this.control.state.base = saved.server;
      this.control.state.handle = saved.account;
      this.emit();
      if (saved.reconnect) return await this.dispatch({ action: 'connect', base: saved.server, handle: '', password: '' });
      return { state: this.state };
    } catch (error) {
      const notice = `Saved connection could not be restored: ${error instanceof Error ? error.message : String(error)}`;
      this.control.state.connectionPersistenceError = notice;
      this.emit();
      return { state: this.state, notice };
    }
  }
}
