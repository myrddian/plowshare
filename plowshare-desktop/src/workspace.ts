import type {
  FileStoreRegistry,
  FileStoreState,
} from 'plowshare-client-node/filestores';
import { connectionName } from 'plowshare-client-node/connections';
import { errorMessage } from 'plowshare-client-ts/binding/values';
import type { JobStore, SavedJob } from './job-store.ts';
import {
  AUTHORING,
  authoringReady,
  authoringRequest,
} from 'plowshare-client-ts/operations/authoring';
import { homeKey } from './shared.ts';
import { inspectionConversation } from './shared.ts';
import { realpath } from 'node:fs/promises';
import { hostname } from 'node:os';
import { isAbsolute } from 'node:path';
import {
  DesktopClient,
  serverConnector,
  emptyConnectionState,
  validatedLogin,
} from './client.ts';
import type { Connector } from './client.ts';
import { identifyFolder } from './files.ts';
import {
  preparePersonalStore,
  recreatePersonalStore,
  readPersonal,
} from 'plowshare-client-node/personal';
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
  private operatorOwner: DesktopClient | undefined;
  private owners = new Map<string, DesktopClient>();
  private viewReads = new Map<string, number>();
  private saved: SavedProject[] = [];
  private errors = new Map<string, string>();
  private configError: string | undefined;
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
  private restoring: Promise<Reply> | undefined;
  private connectionStore: ConnectionStore | undefined;
  private jobStore: JobStore | undefined;
  private jobRestoring = false;
  private unrestoredJobs: SavedJob[] = [];
  private lastReceipts = '';
  private namedConnections: NonNullable<DesktopState['namedConnections']> = [];
  private localPreferences: DesktopState['localPreferences'];
  private selectedConnection: string | undefined;
  private localFileStores: FileStoreState | undefined;
  private fileStores: FileStoreRegistry | undefined;
  private switches: Promise<unknown> = Promise.resolve();
  constructor(
    changed: (state: DesktopState) => void,
    store: ProjectStore,
    connector: Connector = serverConnector,
    connectionStore?: ConnectionStore,
    jobStore?: JobStore,
    fileStores?: FileStoreRegistry,
  ) {
    this.changed = changed;
    this.store = store;
    this.connectionStore = connectionStore;
    this.jobStore = jobStore;
    this.fileStores = fileStores;
    this.control = new DesktopClient((state) => {
      const lost = this.connected && !state.connected;
      this.connected = state.connected;
      if (lost && !this.transitioning) {
        this.token = {};
        for (const child of this.children.values())
          void child.dispatch({ action: 'disconnect' }).catch(() => undefined);
      }
      this.emit();
    }, connector);
  }
  get selectionToken() {
    return this.token;
  }
  get library() {
    return this.control.library;
  }
  get runs() {
    return this.control.runs;
  }
  spawnedConversation(parent: string, key: string) {
    return this.control.runs.spawnedConversation(
      parent,
      key,
      this.state.history[parent]?.entries ?? [],
    );
  }
  get activity() {
    return this.control.activity;
  }
  get board() {
    return this.control.board;
  }
  get state(): DesktopState {
    const state = structuredClone(this.control.state);
    if (this.localFileStores)
      state.localFileStores = structuredClone(this.localFileStores);
    if (this.localPreferences !== undefined)
      state.localPreferences = structuredClone(this.localPreferences);
    state.namedConnections = structuredClone(this.namedConnections);
    if (this.selectedConnection !== undefined)
      state.selectedConnection = this.selectedConnection;
    state.personal = structuredClone(this.personal);
    if (this.operatorOwner)
      state.operator = structuredClone(this.operatorOwner.state.operator);
    if (this.authoringIdentity === `${state.base}|${state.handle}`)
      state.authoring = structuredClone(this.authoring);
    if (state.mode !== 'live') return state;
    if (this.transitioning) {
      state.connected = false;
      if (this.control.state.connected) state.connection = 'Opening projects…';
    }
    const byId = <T extends { id: string }>(rows: T[]) => [
      ...new Map(rows.map((row) => [row.id, row])).values(),
    ];
    // The account listing is authoritative for registered projects. Saved folders and
    // older sessions cannot restore revoked access, including after restarting the desktop.
    // Private client attachments are visible only through their own session; a listing error
    // preserves the last known view while the server continues to recheck every request.
    const accountProjects = new Set(state.projects.map((row) => row.name));
    const unavailableProjects = new Set(
      state.projectListError
        ? []
        : [
            ...this.saved.map((row) => row.name),
            ...[...this.children.values()].flatMap((child) =>
              child.state.projects.map((row) => row.name),
            ),
          ].filter(
            (name) => !name.startsWith('client:') && !accountProjects.has(name),
          ),
    );
    for (const [project, child] of this.children) {
      if (unavailableProjects.has(project)) continue;
      // Account refreshes are authoritative; an older child snapshot must not hide a moved server path.
      state.projects = [
        ...new Map(
          [
            ...child.state.projects.filter(
              (row) => !unavailableProjects.has(row.name),
            ),
            ...state.projects,
          ].map((row) => [row.name, row]),
        ).values(),
      ];
      state.conversations = byId([
        ...state.conversations,
        ...child.state.conversations,
      ]).filter((row) => !row.project || !unavailableProjects.has(row.project));
      state.jobs = byId([...state.jobs, ...child.state.jobs]);
      state.approvals = byId([...state.approvals, ...child.state.approvals]);
      state.answeringApprovals = [
        ...new Set([
          ...(state.answeringApprovals ?? []),
          ...(child.state.answeringApprovals ?? []),
        ]),
      ];
      Object.assign(state.agents, child.state.agents);
      Object.assign(state.history, child.state.history);
      Object.assign(state.contexts, child.state.contexts);
      state.contextSnapshots = {
        ...state.contextSnapshots,
        ...child.state.contextSnapshots,
      };
    }
    state.projects = [
      ...new Map(
        [
          ...this.saved.map((row) => ({ name: row.name })),
          ...state.projects,
        ].map((row) => [row.name, row]),
      ).values(),
    ].filter((row) => !unavailableProjects.has(row.name));
    state.projectFolders = this.saved
      .filter((row) => !unavailableProjects.has(row.name))
      .map((row) => {
        const child = this.children.get(row.name);
        const files = child?.state.files ?? { status: 'off' as const };
        const sync = child?.state.sync;
        const error = this.errors.get(row.name);
        return {
          name: row.name,
          path: row.path,
          machine: row.machine,
          enabled: row.enabled,
          connected: child?.state.connected ?? false,
          files,
          ...(sync === undefined ? {} : { sync }),
          ...(error === undefined ? {} : { error }),
        };
      });
    if (state.personal && this.errors.has(state.personal.project))
      state.personal.error = this.errors.get(state.personal.project);
    state.projectConfigError = this.configError;
    state.localMachine = thisMachine(process.env, hostname());
    state.projectPreparing = [...this.attachments.keys()];
    const visibleChild = unavailableProjects.has(this.scope)
      ? undefined
      : this.children.get(this.scope);
    state.files = visibleChild?.state.files ?? {
      status: 'off',
    };
    state.sync = visibleChild?.state.sync;
    state.liveHistory = visibleChild?.state.liveHistory ?? state.liveHistory;
    return state;
  }
  private emit() {
    this.changed(this.state);
    if (
      !this.jobStore ||
      this.jobRestoring ||
      this.transitioning ||
      this.closing ||
      this.control.state.mode !== 'live'
    )
      return;
    const { base, handle } = this.control.state;
    if (!handle) return;
    const rows = [
      ...this.unrestoredJobs,
      ...this.control.savedJobs(),
      ...[...this.children.values()].flatMap((child) => child.savedJobs()),
    ].filter((row) => !row.project?.startsWith('client:'));
    const receipt = JSON.stringify([base, handle, rows]);
    if (receipt === this.lastReceipts) return;
    this.lastReceipts = receipt;
    const token = this.token;
    void this.jobStore.save(base, handle, rows).catch((error: unknown) => {
      if (token !== this.token) return;
      this.control.state.jobRecoveryError = `Job recovery could not be saved: ${errorMessage(error)}`;
      this.changed(this.state);
    });
  }
  private same(token: object) {
    if (token !== this.token)
      throw new Error('The account connection changed.');
  }
  private requireLive() {
    if (this.closing || !this.control.state.connected)
      throw new Error('Connect to the server before opening a project.');
  }
  private track<T>(work: Promise<T>): Promise<T> {
    this.requests.add(work);
    return work.finally(() => this.requests.delete(work));
  }
  private async clientFor(project?: string): Promise<DesktopClient> {
    if (!project || this.control.state.mode === 'demo') return this.control;
    this.requireLive();
    if (
      !this.state.projects.some((row) => row.name === project) &&
      !this.state.conversations.some((row) => row.project === project)
    )
      throw new Error('Choose an available project.');
    const token = this.token;
    const existing = this.children.get(project);
    if (existing?.state.connected) return existing;
    const underway = this.openings.get(project);
    if (underway) return underway;
    let child = existing;
    if (!child) {
      child = new DesktopClient(
        () => {
          if (this.children.get(project) === child) {
            if (child?.state.connection.startsWith('Connection lost'))
              this.errors.set(
                project,
                'Project connection lost. Reconnect files to restore this project.',
              );
            this.emit();
          }
        },
        (_base, _handle, _password, push, closed) =>
          this.control.openPeer(push, closed),
        { project, accountActivity: false },
      );
      this.children.set(project, child);
    }
    const selected = child;
    const operation = (async () => {
      await selected.dispatch({
        action: 'connect',
        base: this.control.state.base,
        handle: this.control.state.handle,
        password: 'authenticated-project-session',
      });
      this.same(token);
      return selected;
    })();
    this.openings.set(project, operation);
    try {
      return await operation;
    } finally {
      if (this.openings.get(project) === operation)
        this.openings.delete(project);
    }
  }
  private projectOfConversation(id: string) {
    return this.state.conversations.find((row) => row.id === id)?.project;
  }
  private async conversationOwner(id: string) {
    const row = this.state.conversations.find((value) => value.id === id);
    if (!row) {
      // A delegated conversation inherits the owning parent's authenticated session.
      // It need not be listed as a top-level chat, and must not be rerouted globally.
      const owner = [this.control, ...this.children.values()].find((client) =>
        inspectionConversation(client.state, id),
      );
      if (owner) return owner;
    }
    if (!row) throw new Error('Choose an available conversation.');
    const owner = await this.clientFor(row.project);
    owner.retainConversation(row);
    return owner;
  }
  async followView(view: string, conversation?: string) {
    const read = (this.viewReads.get(view) ?? 0) + 1;
    this.viewReads.set(view, read);
    const token = this.token;
    if (!conversation) {
      const previous = this.owners.get(view);
      this.owners.delete(view);
      await previous?.followView(view);
      return;
    }
    if (!inspectionConversation(this.state, conversation))
      throw new Error('Choose an available conversation to follow.');
    const owner = await this.conversationOwner(conversation);
    if (this.viewReads.get(view) !== read || token !== this.token) return;
    const previous = this.owners.get(view);
    if (previous && previous !== owner) {
      this.owners.delete(view);
      await previous.followView(view);
    }
    if (this.viewReads.get(view) !== read || token !== this.token) return;
    this.owners.set(view, owner);
    await owner.followView(view, conversation);
  }
  private async closeChildren(clear = false) {
    if (clear) this.owners.clear();
    this.viewReads.clear();
    const children = [...this.children.values()];
    this.openings.clear();
    this.attachments.clear();
    if (clear) this.children.clear();
    await Promise.allSettled(
      children.map(async (child) => {
        await child.dispatch({ action: 'disconnect' });
        await child.shutdown();
      }),
    );
  }
  async shutdown() {
    await this.jobStore?.flush();
    this.closing = true;
    this.transitioning = true;
    this.token = {};
    const pending = [
      ...this.requests,
      ...this.openings.values(),
      ...this.attachments.values(),
    ];
    await Promise.allSettled(
      [...this.children.values()].map((child) => child.shutdown()),
    );
    await this.control.shutdown();
    // Authentication must finish releasing its credential lock before Electron exits.
    await Promise.allSettled(pending);
  }
  rootDirectory(
    directory: string,
    project?: string,
    selectProject = true,
  ): Promise<Reply> {
    return this.track(this.root(directory, project, selectProject));
  }
  private async root(
    directory: string,
    project?: string,
    selectProject = true,
  ): Promise<Reply> {
    this.requireLive();
    const token = this.token;
    if (project && project === this.personal?.project)
      throw new Error(
        'Personal uses the selected connection’s default store. Retry Personal file access to reconnect it.',
      );
    const folder = await identifyFolder(directory, project);
    this.same(token);
    if (folder.project === this.personal?.project)
      throw new Error(
        'Personal uses the selected connection’s default store. Retry Personal file access to reconnect it.',
      );
    if (folder.kind === 'DISJOINT') {
      const held = [...this.children.entries()].find(
        ([key, value]) =>
          key.startsWith('client:') && value.state.files.root === folder.root,
      );
      if (held) {
        if (selectProject) this.scope = held[0];
        this.emit();
        return { state: this.state, rootProject: held[0] };
      }
      let key: string | undefined;
      const child = new DesktopClient(
        () => {
          if (key && this.children.get(key) === child) this.emit();
        },
        (_base, _handle, _password, push, closed) =>
          this.control.openPeer(push, closed),
        { accountActivity: false },
      );
      try {
        await child.dispatch({
          action: 'connect',
          base: this.control.state.base,
          handle: this.control.state.handle,
          password: 'authenticated-project-session',
        });
        this.same(token);
        const attached = await child.rootDirectory(folder.root, folder.project);
        this.same(token);
        key = attached.rootProject;
        const old = this.children.get(key);
        if (old) await old.shutdown();
        this.children.set(key, child);
        const mapping: SavedProject = {
          server: this.control.state.base,
          account: this.control.state.handle,
          name: key,
          path: folder.root,
          machine: thisMachine(process.env, hostname()),
          enabled: true,
        };
        if (!key.startsWith('client:')) await this.store.put(mapping);
        this.same(token);
        this.saved = [...this.saved.filter((row) => row.name !== key), mapping];
        this.errors.delete(key);
        if (selectProject) this.scope = key;
        this.emit();
        return { state: this.state, rootProject: key };
      } catch (error) {
        if (key && this.children.get(key) === child) this.children.delete(key);
        await child.shutdown();
        throw error;
      }
    }
    const mapping: SavedProject = {
      server: this.control.state.base,
      account: this.control.state.handle,
      name: folder.project,
      path: folder.root,
      machine: thisMachine(process.env, hostname()),
      enabled: true,
    };
    const previous = this.saved.find((row) => row.name === mapping.name);
    await this.store.put(mapping);
    this.same(token);
    this.saved = [
      ...this.saved.filter((row) => row.name !== mapping.name),
      mapping,
    ];
    this.errors.delete(mapping.name);
    if (selectProject) this.scope = mapping.name;
    this.emit();
    try {
      await this.startProject(mapping, token);
    } catch (error) {
      if (previous && token === this.token) {
        await this.store.put(previous);
        this.same(token);
        this.saved = [
          ...this.saved.filter((row) => row.name !== previous.name),
          previous,
        ];
        this.emit();
      }
      throw error;
    }
    return { state: this.state, rootProject: mapping.name };
  }
  private async mountPersonal(token: object, recreate = false) {
    const project = this.control.state.projects.find(
      (row) => row.kind === 'personal',
    );
    if (!project) {
      this.personal = undefined;
      return;
    }
    this.personal = { project: project.name };
    // A saved/manual folder must never override the connection-owned Personal mount.
    this.saved = this.saved.filter((row) => row.name !== project.name);
    try {
      const child = this.children.get(project.name);
      await child?.files?.withdraw();
      await child?.sync.close();
      this.same(token);
      const prepare = recreate ? recreatePersonalStore : preparePersonalStore;
      const store = await prepare(
        this.control.state.base,
        this.control.state.handle,
        project.name,
      );
      this.same(token);
      const root = store.root;
      this.personal.root = root;
      this.personal.warning = store.warning;
      const mapping: SavedProject = {
        server: this.control.state.base,
        account: this.control.state.handle,
        name: project.name,
        path: root,
        machine: thisMachine(process.env, hostname()),
        enabled: true,
      };
      this.saved = [
        ...this.saved.filter((row) => row.name !== project.name),
        mapping,
      ];
      this.scope = project.name;
    } catch (error) {
      this.same(token);
      this.personal.error =
        error instanceof Error ? error.message : errorMessage(error);
    }
  }

  private async startProject(mapping: SavedProject, token: object) {
    try {
      if (mapping.machine !== thisMachine(process.env, hostname()))
        throw new Error(
          'This saved folder belongs to another machine. Choose its location on this computer.',
        );
      if ((await realpath(mapping.path)) !== mapping.path)
        throw new Error(
          'The saved folder moved or became a symbolic link. Choose its location again.',
        );
      this.same(token);
      const child = await this.clientFor(mapping.name);
      this.same(token);
      await child.rootDirectory(mapping.path, mapping.name);
      this.same(token);
      this.control.state.projects = [
        ...this.control.state.projects.filter(
          (row) => row.name !== mapping.name,
        ),
        {
          ...this.control.state.projects.find(
            (row) => row.name === mapping.name,
          ),
          name: mapping.name,
          machine: mapping.machine,
          workspace: mapping.path,
        },
      ];
      this.errors.delete(mapping.name);
    } catch (error) {
      if (token === this.token)
        this.errors.set(
          mapping.name,
          error instanceof Error ? error.message : errorMessage(error),
        );
      throw error;
    } finally {
      if (token === this.token) this.emit();
    }
  }
  private async recoverProject(name: string, selectProject = true) {
    const token = this.token;
    const recorded = this.state.projects.find((row) => row.name === name);
    if (!recorded?.workspace || !isAbsolute(recorded.workspace))
      throw new Error(
        'This project has no recorded absolute folder. Choose a folder.',
      );
    if (recorded.machine !== thisMachine(process.env, hostname()))
      throw new Error(
        'The recorded folder belongs to another machine or the server. Choose its location on this computer.',
      );
    const root = await realpath(recorded.workspace);
    this.same(token);
    if (root !== recorded.workspace)
      throw new Error(
        'The recorded folder moved or became a symbolic link. Choose its location again.',
      );
    return this.rootDirectory(root, name, selectProject);
  }
  private async prepareProject(project?: string) {
    if (!project || this.control.state.mode !== 'live') return;
    const existing = this.attachments.get(project);
    if (existing) return existing;
    const mapping = this.saved.find((row) => row.name === project);
    if (mapping && !mapping.enabled) return; // An explicit pause is not undone by navigation.
    if (this.children.get(project)?.state.files.status === 'ready') return;
    // A failed attachment requires an explicit Connect files action. Navigation, polling and
    // run preparation must not repeatedly submit a claim the server has already refused.
    const failed = this.errors.get(project);
    if (failed) throw new Error(failed);
    // Recorded paths are offered for approval in the GUI. Navigation grants no new access.
    if (!mapping) return;
    const token = this.token;
    const operation = (async () => {
      await this.startProject(mapping, token);
    })();
    this.attachments.set(project, operation);
    this.emit();
    try {
      await operation;
    } finally {
      if (this.attachments.get(project) === operation) {
        this.attachments.delete(project);
        this.emit();
      }
    }
  }
  dispatch(request: Request): Promise<Reply> {
    if (this.closing)
      return Promise.reject(new Error('The desktop is closing.'));
    if (
      [
        'connect',
        'connection-select',
        'disconnect',
        'demo',
        'connection-rename',
        'connection-remove',
      ].includes(request.action)
    ) {
      const next = this.switches.then(() => this.dispatchRequest(request));
      this.switches = next.catch(() => undefined);
      return this.track(next);
    }
    return this.track(this.dispatchRequest(request));
  }
  private async dispatchRequest(request: Request): Promise<Reply> {
    if (!request || typeof request !== 'object')
      throw new Error('Invalid desktop request.');
    if (
      request.action === 'filestore-load' ||
      request.action === 'filestore-setup'
    ) {
      if (!this.fileStores)
        throw new Error('Local FileStore configuration is unavailable.');
      this.localFileStores =
        request.action === 'filestore-setup'
          ? await this.fileStores.initialize({
              alias: request.alias,
              root: request.root,
            })
          : await this.fileStores.load();
      this.emit();
      return { state: this.state };
    }
    if (request.action === 'bootstrap') {
      if (this.fileStores && !this.localFileStores)
        this.localFileStores = await this.fileStores.load();
      if (this.restoring) return this.restoring;
      if (this.initialized || !this.connectionStore)
        return { state: this.state };
      this.initialized = true;
      this.restoring = this.restoreConnection();
      try {
        return await this.restoring;
      } finally {
        this.restoring = undefined;
      }
    }
    if (request.action === 'connection-preferences') {
      if (
        this.transitioning ||
        this.control.state.mode !== 'live' ||
        !this.control.state.handle
      )
        throw new Error(
          'Wait for the selected connection before saving its view.',
        );
      const token = this.token;
      const { base, handle } = this.control.state;
      if (request.server !== base || request.account !== handle)
        throw new Error('The connection view belongs to another identity.');
      await this.connectionStore?.savePreferences?.(
        base,
        handle,
        request.preference,
      );
      this.same(token);
      this.localPreferences = structuredClone(request.preference);
      return { state: this.state };
    }
    if (
      request.action === 'connection-rename' ||
      request.action === 'connection-remove'
    ) {
      if (!this.connectionStore?.rename || !this.connectionStore.remove)
        throw new Error('Connection management is unavailable.');
      if (request.action === 'connection-rename') {
        await this.connectionStore.rename(request.name, request.nextName);
        if (this.selectedConnection === request.name)
          this.selectedConnection = request.nextName;
      } else {
        if (this.selectedConnection === request.name) {
          await this.dispatchRequest({ action: 'disconnect' });
          this.selectedConnection = undefined;
        }
        await this.connectionStore.remove(request.name);
      }
      await this.readConnections();
      this.emit();
      return { state: this.state };
    }
    if (request.action === 'connection-select') {
      if (!this.connectionStore?.select)
        throw new Error('Connection selection is unavailable.');
      const row = await this.connectionStore.select(request.name);
      request = {
        action: 'connect',
        base: row.server,
        handle: row.account,
        password: '',
        name: row.name,
      };
    }
    if (request.action === 'server-setup') {
      const login = await this.control.initializeAdministrator(request);
      request = { action: 'connect', ...login, password: '' };
    }
    if (request.action === 'connect') {
      this.initialized = true;
      const validated = validatedLogin(request);
      request = { ...request, ...validated };
      const requestedName =
        request.name === undefined ? undefined : connectionName(request.name);
      if (requestedName) {
        const rows = (await this.connectionStore?.list?.()) ?? [];
        const previous = rows.find((row) => row.name === requestedName);
        if (
          rows.some(
            (row) =>
              row.server === validated.base &&
              row.account === validated.handle &&
              row.name !== requestedName,
          )
        )
          throw new Error(
            'This identity already has a name. Rename that connection.',
          );
        if (
          previous &&
          (previous.server !== request.base.replace(/\/$/, '') ||
            previous.account !== request.handle)
        )
          throw new Error(
            'This name belongs to another server/account. Use a new name.',
          );
      }
      // Persist the old receipts before fencing its subscribers and file work. No server job is cancelled.
      await this.jobStore?.flush();
      this.transitioning = true;
      this.token = {};
      const token = this.token;
      const previousBase = this.control.state.base,
        previousHandle = this.control.state.handle;
      const pending = [...this.openings.values(), ...this.attachments.values()];
      await this.closeChildren(true);
      await this.control.dispatch({ action: 'disconnect' });
      await this.control.shutdown();
      await Promise.allSettled(pending);
      this.personal = undefined;
      this.scope = '';
      this.saved = [];
      this.owners.clear();
      this.localPreferences = undefined;
      this.selectedConnection = request.name;
      let login;
      try {
        login = await this.control.login(request);
      } catch (error) {
        this.transitioning = false;
        this.control.state = emptyConnectionState(request.base, request.handle);
        this.control.state.connection = 'Authentication required';
        this.emit();
        throw error;
      }
      if (this.closing) throw new Error('The desktop is closing.');
      const { base, handle } = login;
      request = { ...request, ...login };
      if (previousBase !== base || previousHandle !== handle)
        this.authoring = undefined;
      this.same(token);
      this.operatorOwner = undefined;
      this.unrestoredJobs = [];
      this.saved = [];
      this.errors.clear();
      this.configError = undefined;
      try {
        this.saved = await this.store.list(base, handle);
      } catch (error) {
        this.configError = `Project configuration could not be read: ${errorMessage(error)}`;
      }
      this.same(token);
      try {
        this.localPreferences = await this.connectionStore?.loadPreferences?.(
          base,
          handle,
        );
        this.same(token);
        try {
          await this.connectionStore?.save({
            server: base,
            account: handle,
            reconnect: true,
            ...(requestedName === undefined ? {} : { name: requestedName }),
          });
          await this.readConnections();
          this.selectedConnection = (await this.connectionStore?.load())?.name;
        } catch {
          this.control.state.connectionPersistenceError =
            'Connection details could not be saved. Reconnect manually after restarting the desktop.';
        }
        this.same(token);
        await this.control.dispatch(request);
        this.same(token);
        await this.rememberConnection(true, request.name);
        this.same(token);
        await this.mountPersonal(token);
        this.same(token);
        // Personal is the fixed account mount; other folders are explicit choices.
        await Promise.allSettled(
          this.saved
            .filter((row) => row.enabled)
            .map((row) => this.startProject(row, token)),
        );
        this.same(token);
        if (this.jobStore) {
          this.jobRestoring = true;
          try {
            const jobs = (await this.jobStore.load(base, handle)).filter(
              (row) => !row.project?.startsWith('client:'),
            );
            this.same(token);
            for (const row of jobs) {
              try {
                const owner = await this.clientFor(row.project);
                this.same(token);
                await owner.restoreJobs([row]);
              } catch (error) {
                this.same(token);
                this.unrestoredJobs.push(row);
                this.control.state.jobRecoveryError = `Receipt ${row.id} is retained, but its project could not be restored: ${errorMessage(error)}`;
              }
            }
          } catch (error) {
            this.same(token);
            this.control.state.jobRecoveryError = `Saved jobs could not be restored: ${errorMessage(error)}`;
          } finally {
            if (token === this.token) this.jobRestoring = false;
          }
        }
      } finally {
        if (token === this.token) {
          this.transitioning = false;
          this.emit();
        }
      }
      return { state: this.state };
    }
    if (request.action === 'disconnect' || request.action === 'demo') {
      this.personal = undefined;
      this.initialized = true;
      await this.rememberConnection(false);
      this.transitioning = true;
      this.token = {};
      const token = this.token;
      await this.closeChildren(request.action === 'demo');
      this.same(token);
      await this.control.dispatch(request);
      this.same(token);
      this.transitioning = false;
      this.emit();
      return { state: this.state };
    }
    if (this.transitioning)
      throw new Error('Wait for the connection switch to finish.');
    if (request.action === 'personal-recreate') {
      this.requireLive();
      const token = this.token;
      await this.mountPersonal(token, true);
      this.same(token);
      const mapping = this.saved.find(
        (row) => row.name === this.personal?.project,
      );
      if (!mapping)
        throw new Error(
          this.personal?.error || 'Personal store is unavailable.',
        );
      await this.startProject(mapping, token);
      this.same(token);
      this.emit();
      return { state: this.state };
    }
    if (request.action === 'select') {
      await this.followView('chat', request.conversation);
      return { state: this.state };
    }
    if (
      request.action === 'project-open' ||
      request.action === 'project-remove' ||
      request.action === 'files-withdraw'
    ) {
      this.requireLive();
      const token = this.token;
      const name = request.project ?? this.scope;
      if (name === this.personal?.project && request.action !== 'project-open')
        throw new Error('Personal space is always mounted for this account.');
      if (
        name === this.personal?.project &&
        request.action === 'project-open'
      ) {
        // Recovery resolves the default again, including after an initial storage failure.
        // It must never fall through to a recorded server path or a native folder picker.
        await this.mountPersonal(token);
        this.same(token);
        if (!this.personal?.root)
          throw new Error(
            this.personal?.error || 'Personal store is unavailable.',
          );
      }
      const mapping = this.saved.find((row) => row.name === name);
      if (!mapping) {
        if (request.action !== 'project-open')
          throw new Error('Add a local folder for this project first.');
        return this.recoverProject(name);
      }
      if (request.action === 'project-open') {
        const enabled = { ...mapping, enabled: true };
        if (name !== this.personal?.project) await this.store.put(enabled);
        this.same(token);
        Object.assign(mapping, enabled);
        await this.startProject(mapping, token);
      } else {
        if (request.action === 'project-remove')
          await this.store.remove(mapping.server, mapping.account, name);
        else await this.store.put({ ...mapping, enabled: false });
        this.same(token);
        await this.children.get(name)?.files?.withdraw();
        this.same(token);
        if (request.action === 'project-remove')
          this.saved = this.saved.filter((row) => row.name !== name);
        else mapping.enabled = false;
        this.errors.delete(name);
      }
      this.emit();
      return { state: this.state };
    }
    if (request.action === 'personal-bots') {
      this.requireLive();
      const token = this.token;
      if (!this.personal) throw new Error('Personal space is unavailable.');
      const project = this.personal.project;
      try {
        const owner = await this.clientFor(project);
        this.same(token);
        const latest = await owner.botConversations(project);
        this.same(token);
        this.personal.botLatest = latest;
        delete this.personal.botsError;
      } catch (error) {
        this.same(token);
        this.personal.botsError =
          error instanceof Error ? error.message : errorMessage(error);
      }
      this.emit();
      return { state: this.state };
    }
    if (request.action === 'personal-section') {
      this.requireLive();
      const token = this.token;
      if (!this.personal?.root)
        throw new Error(
          this.personal?.error || 'Personal space is unavailable.',
        );
      this.personal.section = request.section;
      this.personal.path = request.path ?? request.section;
      this.personal.entries = [];
      delete this.personal.text;
      delete this.personal.note;
      try {
        const content = await readPersonal(
          this.personal.root,
          request.section,
          request.path,
        );
        this.same(token);
        Object.assign(this.personal, content);
        delete this.personal.error;
        if (content.text === undefined) delete this.personal.text;
        if (content.note === undefined) delete this.personal.note;
      } catch (error) {
        this.same(token);
        this.personal.error = errorMessage(error);
      }
      this.emit();
      return { state: this.state };
    }
    if (request.action === 'operator-prepare') {
      this.requireLive();
      const token = this.token;
      if (request.kind === 'caps') await this.prepareProject(request.project);
      const owner = await this.clientFor(request.project);
      this.same(token);
      this.operatorOwner = owner;
      await owner.dispatch(request);
      this.same(token);
      this.emit();
      return { state: this.state };
    }
    if (
      request.action === 'operator-preview' ||
      request.action === 'operator-apply' ||
      request.action === 'operator-messages'
    ) {
      this.requireLive();
      const owner = this.operatorOwner;
      if (!owner?.state.connected) throw new Error('Read this control again.');
      await owner.dispatch(request);
      this.emit();
      return { state: this.state };
    }
    if (request.action === 'builder-outputs') {
      const wire = this.control.state.activity.details[request.id]?.wire;
      if (wire?.orchestration.definition !== AUTHORING)
        throw new Error(
          'Choose an authoring run before reading its validation outputs.',
        );
      const conversation = this.control.runs.conversation(
        request.id,
        'conductor',
      );
      const owner = await this.conversationOwner(conversation);
      await owner.dispatch({ action: 'history', conversation });
      this.emit();
      return { state: this.state };
    }
    if (
      request.action === 'builder-prepare' ||
      request.action === 'builder-start'
    ) {
      this.requireLive();
      const token = this.token;
      if (this.control.state.mode !== 'live')
        throw new Error('Connect before authoring.');
      if (request.action === 'builder-start' && this.authoringBusy)
        throw new Error('An authoring launch is already in progress.');
      const task =
        request.action === 'builder-start'
          ? authoringRequest(request.intent, request.revision)
          : undefined;
      if (request.action === 'builder-start') this.authoringBusy = true;
      let launched = false;
      try {
        await this.prepareProject(request.project);
        this.same(token);
        const owner = await this.clientFor(request.project);
        this.same(token);
        await owner.dispatch({ action: 'scope', project: request.project });
        this.same(token);
        await this.control.runs.definitions(request.project);
        this.same(token);
        if (request.action === 'builder-prepare') {
          this.emit();
          return { state: this.state };
        }
        if (
          this.control.state.activity.definitions?.error ||
          this.control.state.activity.definitions?.project !== request.project
        )
          throw new Error(
            this.control.state.activity.definitions?.error ||
              'Read this project’s definitions first.',
          );
        const caller = owner.state.agents[homeKey(request.project)]?.find(
          (row) => row.name === request.agent,
        );
        authoringReady(
          request.project,
          owner.state.files.status === 'ready' &&
            owner.state.files.project === request.project,
          caller,
          this.control.state.activity.definitions?.items.find(
            (row) => row.name === AUTHORING,
          ),
        );
        if (
          request.revision &&
          !this.control.state.activity.definitions?.items.some(
            (row) => row.name === request.revision && row.served,
          )
        )
          throw new Error('Choose an available definition to revise.');
        const identity = `${this.control.state.base}|${this.control.state.handle}`;
        const previousRun =
          this.authoringIdentity === identity && this.authoring?.conversation
            ? this.control.state.activity.runs.items.find(
                (row) =>
                  row.definition === AUTHORING &&
                  row.project === this.authoring?.project &&
                  row.callerConversation === this.authoring?.conversation,
              )
            : undefined;
        if (
          this.authoringIdentity === identity &&
          this.authoring?.status === 'unknown' &&
          !previousRun
        )
          throw new Error(
            'The previous launch is uncertain. Inspect its caller trajectory and Runs before starting another.',
          );
        const activeRun =
          previousRun &&
          ['running', 'asking', 'waiting'].includes(previousRun.state);
        const activeCaller = this.state.jobs.some(
          (row) =>
            row.conversation === this.authoring?.conversation &&
            ['starting', 'running', 'cancelling', 'unknown'].includes(
              row.status,
            ),
        );
        if (
          this.authoringIdentity === identity &&
          (activeRun || (!previousRun && activeCaller))
        )
          throw new Error(
            'The current authoring work is still active. Follow its run before starting another.',
          );
        this.authoringIdentity = identity;
        this.authoring = {
          project: request.project,
          agent: request.agent,
          intent: request.intent,
          ...(request.revision === undefined
            ? {}
            : { revision: request.revision }),
          status: 'launching',
        };
        launched = true;
        this.emit();
        const opened = await owner.dispatch({
          action: 'create',
          project: request.project,
        });
        this.same(token);
        if (!opened.conversation)
          throw new Error(
            'The server did not return an authoring conversation.',
          );
        this.authoring.conversation = opened.conversation;
        this.emit();
        if (
          owner.state.files.status !== 'ready' ||
          owner.state.files.project !== request.project
        )
          throw new Error(
            'The project folder disconnected before the authoring turn.',
          );
        // One send only. An ambiguous result is retained for inspection, never replayed.
        await owner.authoringTurn(
          opened.conversation,
          request.agent,
          `${task}\n\nConnected project workspace (data): ${JSON.stringify({ project: request.project, root: owner.state.files.root })}. Verify with file_roots; write drafts only in this run’s artifacts directory.`,
        );
        this.same(token);
        this.authoring.status = 'submitted';
        await this.control.activity.refresh();
        this.same(token);
        return { state: this.state, conversation: opened.conversation };
      } catch (error) {
        if (
          token === this.token &&
          launched &&
          this.authoring?.status === 'launching'
        ) {
          this.authoring.status = 'unknown';
          this.authoring.error =
            error instanceof Error ? error.message : errorMessage(error);
        }
        throw error;
      } finally {
        if (token === this.token && request.action === 'builder-start')
          this.authoringBusy = false;
        this.emit();
      }
    }
    let owner = this.control;
    if (request.action.startsWith('sync-') && 'project' in request) {
      this.requireLive();
      if (
        typeof request.project !== 'string' ||
        !this.saved.some((row) => row.name === request.project)
      )
        throw new Error('Choose a project with a local folder.');
      owner = await this.clientFor(request.project);
      if (
        owner.state.files.status !== 'ready' ||
        owner.state.files.project !== request.project
      )
        throw new Error('Reconnect this project’s files before synchronizing.');
    } else if (request.action === 'scope' || request.action === 'create') {
      if (request.action === 'scope') this.scope = request.project ?? '';
      try {
        await this.prepareProject(request.project);
      } catch (error) {
        if (request.action !== 'scope') throw error;
        owner = await this.clientFor(request.project);
        await owner.dispatch(request);
        return {
          state: this.state,
          notice: `Project files unavailable: ${error instanceof Error ? error.message : errorMessage(error)}. Use Connect files to choose the folder.`,
        };
      }
      owner = await this.clientFor(request.project);
    } else if (
      request.action === 'workflow-start' ||
      request.action === 'run' ||
      request.action === 'history' ||
      request.action === 'context' ||
      request.action === 'context-snapshot'
    )
      owner = await this.conversationOwner(request.conversation);
    else if (request.action === 'cancel')
      owner =
        [...this.children.values(), this.control].find((client) =>
          client.state.jobs.some((row) => row.id === request.job),
        ) ?? this.control;
    else if (request.action === 'answer') {
      const approval = this.state.approvals.find(
        (row) => row.id === request.id,
      );
      if (approval) owner = await this.conversationOwner(approval.conversation);
    }
    if (
      request.action === 'refresh' ||
      request.action === 'approvals-refresh'
    ) {
      await Promise.all([
        this.control.dispatch(request),
        ...[...this.children.values()]
          .filter((child) => child.state.connected)
          .map((child) => child.dispatch(request)),
      ]);
      this.emit();
      return { state: this.state };
    }
    if (request.action === 'run') {
      await this.prepareProject(
        this.projectOfConversation(request.conversation),
      );
      const mapping = this.saved.find(
        (row) => row.name === this.projectOfConversation(request.conversation),
      );
      if (mapping && owner.state.files.status !== 'ready')
        throw new Error(
          'Project files are disconnected. Reconnect files before sending.',
        );
    }
    const dispatchToken = this.token;
    const reply = await owner.dispatch(request);
    this.same(dispatchToken);
    if (request.action === 'answer')
      await Promise.all(
        [this.control, ...this.children.values()]
          .filter((child) => child !== owner && child.state.connected)
          .map((child) => child.dispatch({ action: 'approvals-refresh' })),
      );
    this.emit();
    return { ...reply, state: this.state };
  }
  private async rememberConnection(reconnect: boolean, name?: string) {
    if (!this.connectionStore || this.control.state.mode !== 'live') return;
    try {
      await this.connectionStore.save({
        server: this.control.state.base,
        account: this.control.state.handle,
        reconnect,
        ...(name === undefined ? {} : { name }),
      });
      const saved = await this.connectionStore.load();
      this.selectedConnection = saved?.name;
      await this.readConnections();
      delete this.control.state.connectionPersistenceError;
    } catch {
      this.control.state.connectionPersistenceError =
        'Connection details could not be saved. Reconnect manually after restarting the desktop.';
    }
  }
  private async readConnections() {
    this.namedConnections = (await this.connectionStore?.list?.()) ?? [];
  }
  private async restoreConnection(): Promise<Reply> {
    const token = this.token;
    try {
      let saved = await this.connectionStore!.load();
      await this.readConnections();
      if (!saved) {
        const servers = await savedLoginServers();
        if (servers.length === 1) saved = { ...servers[0]!, reconnect: true };
      }
      if (token !== this.token) return { state: this.state };
      if (!saved) return { state: this.state };
      this.selectedConnection = saved.name;
      this.control.state.base = saved.server;
      this.control.state.handle = saved.account;
      this.emit();
      if (saved.reconnect)
        return await this.dispatch({
          action: 'connect',
          base: saved.server,
          handle: saved.account,
          password: '',
          ...(saved.name === undefined ? {} : { name: saved.name }),
        });
      return { state: this.state };
    } catch (error) {
      const notice = `Saved connection could not be restored: ${error instanceof Error ? error.message : errorMessage(error)}`;
      this.control.state.connectionPersistenceError = notice;
      this.emit();
      return { state: this.state, notice };
    }
  }
}
