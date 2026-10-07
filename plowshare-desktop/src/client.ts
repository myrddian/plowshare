import { connectionAccount } from 'plowshare-client-node/connections';
import { jobNotification } from 'plowshare-client-ts/operations/push';
import {
  decodeReply,
  decodeRequest,
} from 'plowshare-client-ts/operations/schema';
import { isList, errorMessage } from 'plowshare-client-ts/binding/values';
import {
  UsageClient,
  decodeUsageCall,
  UsageWatch,
  USAGE_REPORTS,
} from 'plowshare-client-ts/operations/usage';
import { OperatorClient } from './operator.ts';
import { readCapsFile, saveCapsFile } from './caps.ts';
import type { SavedJob } from './job-store.ts';
import {
  InformationClient,
  decodeInformationCall,
} from 'plowshare-client-ts/operations/information';
import { ApplicationFilesClient } from './application-files.ts';
import { LibraryClient } from './library.ts';
import {
  resultOf,
  VALIDATED_OPERATIONS,
  type Request as WsRequest,
} from 'plowshare-client-ts/operations/direct';
import { ScheduleClient } from './schedules.ts';
import { RunClient } from './runs.ts';
import { inspectionConversation } from './shared.ts';
import {
  Credentials,
  CredentialError,
  savedOrLogin,
} from 'plowshare-client-node/credentials';
import { fieldsOf } from 'plowshare-client-ts/operations/response';
import {
  acceptedJobOf,
  jobStatusOf,
} from 'plowshare-client-ts/binding/job-view';
import { randomUUID } from 'node:crypto';
import {
  finishSetup,
  signIn,
  openSocket,
  openFiles,
  SignInRefused,
} from 'plowshare-client-ts/binding/auth';
import type { Connection } from 'plowshare-client-ts/binding/connection';
import type { CheckedAnswer as Outcome } from 'plowshare-client-ts/operations/response';
import type {
  Operation,
  Payloads,
} from 'plowshare-client-ts/operations/direct';
import { JobLifecycle } from 'plowshare-client-ts/jobs';
import type { Ticket } from 'plowshare-client-ts/jobs';
import {
  listingProjects,
  listingConversations,
  listingAgents,
  opening,
  speaking,
  streaming,
  checking,
  continuing,
  cancelling,
  followingLogs,
  readingTrace,
  measuring,
  readingLogTail,
  readingLogBefore,
  listingMyApprovals,
  answeringApproval,
} from 'plowshare-client-ts/operations/session';
import {
  projects,
  conversations,
  agents,
  opened,
  backPageOf,
  streamed,
  approvalsOf,
  answeredOf,
  appendedOf,
  logThrough,
} from 'plowshare-client-ts/operations/views';
import { loadOf, reached } from 'plowshare-client-ts/operations/inspection';
import { BoardClient } from './board.ts';
import { emptyBoard } from './board-shared.ts';
import { FilePresence } from './files.ts';
import { DesktopSync } from './sync.ts';
import type { FileOpener } from './files.ts';
import { ActivityClient } from './activity.ts';
import { activeJob, homeKey, contextKey, emptyActivity } from './shared.ts';
import type {
  DesktopState,
  Job,
  Reply,
  Request,
  Conversation,
} from './shared.ts';
import { demoState, demoAnswer } from './demo.ts';

const RUNNING = new Set(['starting', 'running', 'cancelling']);
class ServerRefusal extends Error {}
export function validatedBase(value: unknown): string {
  if (typeof value !== 'string') throw new Error('Enter a server URL.');
  const url = new URL(value);
  if (
    !['http:', 'https:'].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== '/'
  ) {
    throw new Error(
      'Use an explicit HTTP or HTTPS server origin, without credentials or a path.',
    );
  }
  return url.origin;
}
function text(value: unknown, name: string, max = 100_000): string {
  if (typeof value !== 'string' || !value.trim() || value.length > max)
    throw new Error(`Invalid ${name}.`);
  return value;
}
export function validatedLogin(request: {
  base: unknown;
  handle: unknown;
  password: unknown;
}) {
  const handle =
    request.handle === ''
      ? ''
      : connectionAccount(text(request.handle, 'handle', 64));
  const password =
    request.password === '' ? '' : text(request.password, 'password', 4096);
  if (password && !handle) throw new Error('Enter a handle with the password.');
  return { base: validatedBase(request.base), handle, password };
}
function projectOf(request: Request): string | undefined {
  if (!('project' in request) || request.project === undefined)
    return undefined;
  return text(request.project, 'project', 512);
}
function ok(answer: Outcome, expected = 'OK'): Outcome {
  if (answer.code !== expected)
    throw new ServerRefusal(
      answer.said ?? `The server answered ${answer.code}.`,
    );
  return answer;
}
export function emptyConnectionState(
  base: string,
  handle: string,
): DesktopState {
  return {
    mode: 'live',
    connected: false,
    connection: 'Disconnected',
    base,
    handle,
    files: { status: 'off' },
    projects: [],
    agents: {},
    conversations: [],
    history: {},
    jobs: [],
    approvals: [],
    contexts: {},
    board: emptyBoard(),
    activity: emptyActivity(),
  };
}
export interface Connected {
  connection: Connection;
  session: string;
  handle?: string;
  serverAdmin?: boolean;
  openFiles?: FileOpener;
  bearer?: () => Promise<string>;
  spawn?: (
    push: (value: unknown) => void,
    closed: () => void,
  ) => Promise<Connected>;
}
export type Connector = (
  base: string,
  handle: string,
  password: string,
  push: (value: unknown) => void,
  closed: () => void,
) => Promise<Connected>;

/** Use the shared neutral auth/envelope/operation code; Electron stays outside it. */
export class ConnectionUnavailable extends Error {}

export const serverConnector: Connector = async (
  base,
  handle,
  password,
  push,
  closed,
) => {
  const door = {
    base,
    fetch: async (url: string, init: Parameters<typeof fetch>[1]) => {
      try {
        return await fetch(url, {
          ...init,
          signal: AbortSignal.timeout(15_000),
          redirect: 'error',
        });
      } catch {
        throw new ConnectionUnavailable(
          `Could not reach ${base}. Check that your Plowshare server is running.`,
        );
      }
    },
  };
  const store = new Credentials(
    base,
    undefined,
    undefined,
    handle || undefined,
  );
  const signed = await savedOrLogin(
    door,
    store,
    password ? handle : undefined,
    password || undefined,
  ).catch((error: unknown) => {
    if (error instanceof SignInRefused)
      throw new CredentialError(
        'Sign-in failed. Check your handle and password.',
      );
    throw error;
  });
  if (signed.setupRequired)
    throw new Error(
      'Finish first-run setup with plowshare-cli setup, or use the first administrator form below.',
    );
  if (signed.mustChangePassword)
    throw new Error(
      'Change the initial password through plowshare-cli login or the TUI before connecting.',
    );
  const open: Parameters<typeof openSocket>[0]['open'] = (url) =>
    new Promise((resolve, reject) => {
      const socket = new WebSocket(url);
      const timer = setTimeout(() => {
        socket.close();
        reject(
          new Error('The server WebSocket did not open within 15 seconds.'),
        );
      }, 15_000);
      socket.addEventListener(
        'open',
        () => {
          clearTimeout(timer);
          resolve(socket);
        },
        { once: true },
      );
      socket.addEventListener(
        'error',
        () => {
          clearTimeout(timer);
          reject(new Error('Could not open the server WebSocket.'));
        },
        { once: true },
      );
    });
  let tokens = signed.tokens;
  let authentication = Promise.resolve();
  function serial<T>(work: () => Promise<T>): Promise<T> {
    const result = authentication.then(work);
    authentication = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }
  // Every project has a session, while refresh rotation has one owner per login.
  const spawn = async (
    incoming: (value: unknown) => void,
    gone: () => void,
  ): Promise<Connected> => {
    const session = randomUUID();
    const result = await serial(() =>
      openSocket(
        {
          ...door,
          renew: () => store.renew(door),
          session,
          onPush: incoming,
          onClose: gone,
          open,
        },
        tokens,
        (rotated) => {
          tokens = rotated;
        },
      ),
    );
    const authority = await result.connection
      .ask('admin.status', {})
      .catch((error: unknown) => {
        result.connection.close();
        throw error;
      });
    const serverAdmin =
      authority.code === 'OK' &&
      fieldsOf(authority.payload)['serverAdmin'] === true;
    return {
      connection: result.connection,
      session,
      handle: (await store.session()).handle,
      serverAdmin,
      spawn,
      bearer: () =>
        serial(async () => {
          tokens = await store.renew(door);
          return tokens.access;
        }),
      openFiles: (claim) =>
        serial(async () => {
          const files = await openFiles(
            { ...door, renew: () => store.renew(door), session, open },
            tokens,
            claim,
            (rotated) => {
              tokens = rotated;
            },
          );
          return files.socket;
        }),
    };
  };
  return spawn(push, closed);
};

export class DesktopClient {
  private background(work: Promise<unknown>): void {
    void work.catch((reason: unknown) => {
      this.state.backgroundError = errorMessage(reason);
      this.emit();
    });
  }

  state = demoState();
  readonly sync = new DesktopSync(
    (type, payload) => this.ask(type, payload),
    (value) => {
      this.state.sync = value;
      this.emit();
    },
  );
  readonly activity = new ActivityClient(
    () => this.state,
    (ask) => this.send(ask),
    () => this.emit(),
  );
  readonly applicationFiles = new ApplicationFilesClient(
    () => this.state,
    (ask) => this.send(ask),
    () => this.emit(),
  );
  readonly library = new LibraryClient(
    () => this.state,
    (ask) => this.send(ask),
    () => this.emit(),
  );
  readonly schedules = new ScheduleClient(
    () => this.state,
    (ask) => this.send(ask),
    () => this.emit(),
  );
  readonly runs = new RunClient(
    () => this.state,
    (ask) => this.send(ask),
    () => this.emit(),
  );
  readonly operator = new OperatorClient(
    () => this.state,
    (ask) => this.send(ask),
    () => this.emit(),
    (ask, conversation, agent) => this.operatorSubmit(ask, conversation, agent),
    (project) => readCapsFile(this.capsRoot(project)),
    async (project, file, key, value) => {
      await saveCapsFile(this.capsRoot(project), file, key, value);
    },
  );
  private capsRoot(project: string) {
    if (
      this.state.files.status !== 'ready' ||
      this.state.files.project !== project ||
      !this.state.files.root
    )
      throw new Error(
        'Connect this project’s local folder before editing caps.',
      );
    return this.state.files.root;
  }
  private async operatorSubmit(
    ask: WsRequest,
    conversation = '',
    agent: string = ask.type,
  ): Promise<Outcome> {
    const submits = [
      'memory.digest',
      'agent.curate',
      'conversation.resume',
      'approval.answer',
    ].includes(ask.type);
    if (!submits) {
      const answer = ok(await this.send(ask));
      await this.refreshAfterControl(ask);
      return answer;
    }
    if (
      this.state.jobs.some(
        (row) =>
          row.agent === agent &&
          row.conversation === conversation &&
          activeJob(row),
      )
    )
      throw new Error(
        'This operation has active or uncertain work. Inspect its job status before starting more.',
      );
    const generation = this.generation;
    const job: Job = {
      id: `pending-${randomUUID()}`,
      agent,
      conversation,
      source: 'maintenance',
      task: ask.type,
      status: 'starting',
      text: '',
      events: [],
    };
    const ticket = this.lifecycle.begin(conversation || undefined);
    this.tickets.set(job, ticket);
    this.state.jobs.push(job);
    this.emit();
    try {
      const answer = await this.send(ask);
      if (!['OK', 'ACCEPTED'].includes(answer.code)) ok(answer);
      const handle =
        answer.code === 'ACCEPTED'
          ? acceptedJobOf(answer)?.id
          : fieldsOf(answer.payload)['job'];
      if (typeof handle === 'string' && handle) {
        const held = this.lifecycle.accept(ticket, handle).events;
        job.id = handle;
        job.status = 'running';
        for (const event of held) this.push(event);
        this.poll(handle);
        this.emit();
      } else {
        this.lifecycle.forget(ticket);
        this.tickets.delete(job);
        this.state.jobs = this.state.jobs.filter((row) => row !== job);
        this.emit();
      }
      await this.refreshAfterControl(ask);
      return answer;
    } catch (error) {
      if (generation === this.generation) {
        this.lifecycle.failed(ticket, error instanceof ServerRefusal);
        job.status = error instanceof ServerRefusal ? 'interrupted' : 'unknown';
        job.detail = errorMessage(error);
        this.emit();
      }
      throw error;
    }
  }
  private async refreshAfterControl(ask: WsRequest) {
    if (
      ![
        'conversation.lifecycle',
        'approval.answer',
        'approval.revoke',
      ].includes(ask.type)
    )
      return;
    const generation = this.generation;
    try {
      if (ask.type === 'conversation.lifecycle')
        await this.loadScope(
          this.state.operator?.project ?? this.options.project,
        );
      await this.refresh();
    } catch (error) {
      if (generation === this.generation && this.state.operator) {
        this.state.operator.error = `Change confirmed; workspace refresh failed: ${errorMessage(error)}`;
        this.emit();
      }
    }
  }
  readonly board = new BoardClient(
    () => this.state,
    (type, payload) => this.ask(type, payload),
    () => this.emit(),
  );
  private connection: Connection | undefined;
  private session = '';
  private lifecycle = new JobLifecycle();
  private tickets = new Map<Job, Ticket>();
  private get generation() {
    return this.lifecycle.generation;
  }
  private peer?: Connected['spawn'];
  async openPeer(
    push: (value: unknown) => void,
    closed: () => void,
  ): Promise<Connected> {
    const peer = this.peer;
    const generation = this.generation;
    if (!peer || !this.state.connected)
      throw new Error('Connect to the server before opening a project.');
    const opened = await peer(push, closed);
    if (generation !== this.generation) {
      opened.connection.close();
      throw new Error('The connection changed while opening the project.');
    }
    return opened;
  }
  files: FilePresence | undefined;
  private fileClosing: Promise<void> = Promise.resolve();
  private closeFiles() {
    const syncing = this.sync.close();
    const files = this.files;
    this.files = undefined;
    this.state.files = { status: 'off' };
    this.fileClosing = Promise.all([
      this.fileClosing,
      syncing,
      files?.close(),
    ]).then(() => undefined);
  }
  async shutdown() {
    this.dispose();
    await this.fileClosing;
  }
  async rootDirectory(directory: string, project?: string) {
    const files = this.files;
    if (!files || !this.state.connected)
      throw new Error('Connect to the server before choosing files.');
    const name = await files.choose(directory, project);
    if (this.files !== files)
      throw new Error('The connection changed while choosing files.');
    await this.readProjects();
    if (
      this.state.projects.find((row) => row.name === name)?.kind === 'personal'
    )
      await this.sync.ready();
    await this.loadScope(name);
    this.emit();
    return { state: structuredClone(this.state), rootProject: name };
  }
  /** Main composition may retain a conversation validated by an account inspector. */
  private readonly retainedConversations = new Map<string, Conversation>();

  retainConversation(row: Conversation) {
    if (this.options.project && row.project !== this.options.project)
      throw new Error('The conversation belongs to another project.');
    if (!this.state.conversations.some((value) => value.id === row.id))
      this.state.conversations.push(structuredClone(row));
    // An inspector can reach logs omitted from chat lists. Keep that metadata across listing
    // refreshes within this authenticated connection, without persisting a second log runtime.
    this.retainedConversations.delete(row.id);
    this.retainedConversations.set(row.id, structuredClone(row));
    while (this.retainedConversations.size > 1000)
      this.retainedConversations.delete(
        this.retainedConversations.keys().next().value!,
      );
  }
  private timers = new Map<string, ReturnType<typeof setInterval>>();
  private demoTimers = new Map<string, ReturnType<typeof setInterval>>();
  private scopeReads = new Map<string, number>();
  private agentReads = new Map<string, number>();
  private approvalRefresh:
    | {
        generation: number;
        dirty: boolean;
        promise: Promise<void>;
      }
    | undefined;
  private contextReads = new Map<string, number>();
  private views = new Map<string, string>();
  private followed = '';
  private followFlight: Promise<void> | undefined;
  private historyReads = new Map<string, Promise<void>>();
  private catchups = new Map<string, Promise<void>>();
  private dirtyHistories = new Set<string>();

  private changed: (state: DesktopState) => void;
  private connector: Connector;
  private options: { project?: string; accountActivity?: boolean };
  constructor(
    changed: (state: DesktopState) => void,
    connector: Connector = serverConnector,
    privateOptions: { project?: string; accountActivity?: boolean } = {},
  ) {
    this.changed = changed;
    this.connector = connector;
    this.options = privateOptions;
  }
  async login(request: { base: unknown; handle: unknown; password: unknown }) {
    const login = validatedLogin(request);
    if (!login.password && this.connector === serverConnector) {
      const saved = await new Credentials(
        login.base,
        undefined,
        undefined,
        login.handle || undefined,
      ).session();
      if (login.handle && login.handle !== saved.handle)
        throw new Error(
          'The saved session belongs to another account. Enter its handle or sign in with a password.',
        );
      login.handle = saved.handle;
    }
    return login;
  }
  async initializeAdministrator(
    request: Extract<Request, { action: 'server-setup' }>,
  ) {
    const base = validatedBase(request.base);
    const door = {
      base,
      fetch: (url: string, init: Parameters<typeof fetch>[1]) =>
        fetch(url, {
          ...init,
          signal: AbortSignal.timeout(30_000),
          redirect: 'error' as const,
        }),
    };
    const temporary = text(
      request.temporaryPassword,
      'temporary password',
      4096,
    );
    const signed = await signIn(door, 'admin', temporary);
    if (!signed.setupRequired)
      throw new Error(
        'Setup is already complete. Sign in with your administrator account.',
      );
    const handle = text(request.handle, 'administrator handle', 64),
      password = text(request.password, 'administrator password', 4096);
    await finishSetup(door, signed.tokens.access, temporary, handle, password);
    await new Credentials(base, undefined, undefined, handle).login(
      door,
      handle,
      password,
    );
    return { base, handle };
  }
  private readonly usageClient = new UsageClient({
    ask: (type, payload) => this.ask(type, payload),
  });
  readonly usage = new UsageWatch(this.usageClient, (usage) => {
    this.state.usage = usage;
    this.emit();
  });
  savedJobs(): SavedJob[] {
    return this.state.jobs.filter(activeJob).map((job) => {
      const ticket = this.tickets.get(job),
        handle = ticket && this.lifecycle.snapshot(ticket)?.handle;
      const project =
        this.state.conversations.find((row) => row.id === job.conversation)
          ?.project ?? this.options.project;
      return {
        id: job.id,
        ...(handle ? { handle } : {}),
        conversation: job.conversation,
        agent: job.agent,
        ...(project ? { project } : {}),
        ...(job.source ? { source: job.source } : {}),
        ...(job.revision ? { revision: job.revision } : {}),
      };
    });
  }
  restoreJobs(rows: SavedJob[]): Promise<void> {
    if (!this.state.connected)
      return Promise.reject(new Error('Connect before restoring jobs.'));
    for (const row of rows) {
      if (this.state.jobs.some((job) => job.id === row.id)) continue;
      const job: Job = {
        id: row.id,
        conversation: row.conversation,
        agent: row.agent,
        task: 'Recovered work',
        ...(row.source === undefined ? {} : { source: row.source }),
        ...(row.revision === undefined ? {} : { revision: row.revision }),
        status: 'unknown',
        text: '',
        events: [],
        detail: row.handle
          ? 'Reconciling saved job receipt…'
          : 'Submission outcome is unknown; inspect the conversation before starting more work.',
      };
      this.state.jobs.push(job);
      const ticket = this.lifecycle.begin(job.conversation || undefined);
      this.tickets.set(job, ticket);
      if (row.handle) {
        this.lifecycle.accept(ticket, row.handle);
        this.poll(row.handle);
      } else this.lifecycle.failed(ticket, false);
    }
    this.emit();
    return Promise.resolve();
  }
  private emit() {
    this.changed(structuredClone(this.state));
  }
  private ask<K extends Operation>(
    type: K,
    payload: Payloads[K],
  ): Promise<Outcome> {
    if (!this.connection || !this.state.connected)
      throw new Error('Connect to the server first.');
    const connection = this.connection;
    const generation = this.generation;
    const request = decodeRequest(type, payload);
    return connection.ask(request.type, request.payload).then((answer) => {
      if (generation !== this.generation)
        throw new Error(
          'The connection changed while this request was running.',
        );
      if (
        VALIDATED_OPERATIONS.includes(request.type) &&
        resultOf(request, answer).kind === 'invalid-response'
      )
        throw new Error(
          `The server returned unreadable ${type} information (incomplete response). The previous snapshot is retained; refresh before retrying.`,
        );
      return answer.code === 'OK' || answer.code === 'ACCEPTED'
        ? {
            code: answer.code,
            payload: decodeReply(request.type, answer.payload),
            ...(answer.said === undefined ? {} : { said: answer.said }),
          }
        : {
            code: answer.code,
            ...(answer.said === undefined ? {} : { said: answer.said }),
          };
    });
  }
  private async readProjects() {
    const generation = this.generation;
    try {
      const rows = projects(ok(await this.send(listingProjects())));
      if (!rows)
        throw new Error('The server returned an unreadable project listing.');
      this.state.projects = rows;
      if (
        this.state.applicationFiles &&
        !rows.some(
          (row) =>
            row.name === this.state.applicationFiles?.project &&
            row.kind === 'application',
        )
      )
        this.applicationFiles.reset();
      this.state.projectListError = undefined;
    } catch (error) {
      if (generation !== this.generation) throw error;
      this.state.projectListError = `Server projects unavailable; using saved folders: ${errorMessage(error)}`;
    }
  }
  private async send(request: WsRequest) {
    return this.ask(request.type, request.payload);
  }
  dispose() {
    this.retainedConversations.clear();
    this.state.answeringApprovals = [];
    this.usage.disconnected();
    this.peer = undefined;
    this.closeFiles();
    this.activity.reset();
    this.runs.reset();
    this.schedules.reset();
    this.applicationFiles.reset();
    this.library.reset();
    this.board.reset();
    this.operator.reset();
    this.lifecycle.reset(true);
    this.connection?.close();
    this.connection = undefined;
    for (const timer of [...this.timers.values(), ...this.demoTimers.values()])
      clearInterval(timer);
    this.timers.clear();
    this.demoTimers.clear();
    this.scopeReads.clear();
    this.agentReads.clear();
    this.contextReads.clear();
    this.followed = '';
    this.followFlight = undefined;
    this.historyReads.clear();
    this.catchups.clear();
    this.dirtyHistories.clear();
  }
  private disconnected() {
    this.retainedConversations.clear();
    this.state.answeringApprovals = [];
    this.usage.disconnected();
    this.peer = undefined;
    this.closeFiles();
    this.activity.reset();
    this.runs.reset();
    this.schedules.reset();
    this.applicationFiles.reset();
    this.library.reset();
    this.board.reset();
    // Invalidate every pending read and subscription acknowledgement from this socket.
    this.lifecycle.reset(true);
    this.followed = '';
    this.followFlight = undefined;
    this.historyReads.clear();
    this.catchups.clear();
    this.dirtyHistories.clear();
    this.state.connected = false;
    this.state.liveHistory = {
      status: 'unavailable',
      detail: 'Reconnect to restore live conversation updates.',
    };
    this.state.connection = 'Connection lost · reconnect to reconcile';
    for (const job of this.state.jobs)
      if (RUNNING.has(job.status)) {
        job.status = 'unknown';
        job.detail =
          'The server may still be running this job. Its request will not be replayed.';
      }
    for (const timer of this.timers.values()) clearInterval(timer);
    this.timers.clear();
    this.emit();
  }
  async dispatch(request: Request): Promise<Reply> {
    if (!request || typeof request !== 'object')
      throw new Error('Invalid desktop request.');
    let conversation: string | undefined;
    let notice: string | undefined;
    switch (request.action) {
      case 'application-files':
        await this.applicationFiles.list(request.project, request.path);
        break;
      case 'application-file-read':
        await this.applicationFiles.read(request.project, request.path);
        break;
      case 'application-file-save':
        await this.applicationFiles.save(
          request.project,
          request.path,
          request.text,
          request.revision,
        );
        if (request.path === 'plowshare.json') await this.readProjects();
        break;
      case 'relay-operate': {
        const answer = await this.ask('relay.operate', request.payload);
        if (answer.code !== 'OK')
          throw new Error(answer.said ?? 'Relay control refused');
        return {
          state: structuredClone(this.state),
          relayControl: decodeReply('relay.operate', answer.payload),
        };
      }
      case 'relay-read': {
        const answer = await this.ask(request.type, request.payload);
        if (answer.code !== 'OK')
          throw new Error(answer.said ?? 'Relay log unavailable');
        const relay = decodeReply(request.type, answer.payload);
        // Owned Relay receipts supply the same trusted metadata as Board inspection. Register
        // it before opening a trajectory so workspace and client follow guards remain intact.
        if ('branches' in relay)
          for (const branch of relay.branches) {
            if (branch.conversation && branch.conversationProject)
              this.retainConversation({
                id: branch.conversation,
                project: branch.conversationProject,
                title: `Relay · ${branch.name}`,
              });
          }
        return {
          state: structuredClone(this.state),
          relay,
        };
      }
      case 'usage-open':
        await this.usage.open(request.type, request.filter);
        break;
      case 'usage-close':
        await this.usage.close();
        break;
      case 'usage-read':
        if (
          ![
            ...USAGE_REPORTS,
            'usage.calls',
            'conversation.context.count',
          ].includes(request.type)
        )
          throw new Error('Unknown usage read.');
        return {
          state: structuredClone(this.state),
          usage: await this.usageClient.invoke(
            decodeUsageCall(request.type, request.payload),
          ),
        };
      case 'information': {
        const client = new InformationClient(
          { ask: (type, payload) => this.ask(type, payload) },
          request.scope,
        );
        const informationCall = decodeInformationCall(
          request.operation,
          request.payload ?? {},
          request.scope,
        );
        if (informationCall.operation !== 'ask')
          return {
            state: structuredClone(this.state),
            information: await client.invoke(informationCall),
          };
        const generation = this.generation;
        const job: Job = {
          id: `pending-${randomUUID()}`,
          conversation: '',
          source: 'information',
          revision: informationCall.payload.revision,
          agent: 'ask',
          task: informationCall.payload.question,
          status: 'starting',
          text: '',
          events: [],
        };
        const ticket = this.lifecycle.begin();
        this.tickets.set(job, ticket);
        this.state.jobs.push(job);
        this.emit();
        try {
          const information = await client.call('ask', informationCall.payload);
          const id = fieldsOf(information)['job'];
          if (typeof id !== 'string' || !id.trim())
            throw new Error(
              'The accepted document question carried no job handle.',
            );
          const held = this.lifecycle.accept(ticket, id).events;
          job.id = id;
          job.status = 'running';
          for (const event of held) this.push(event);
          this.poll(id);
          this.emit();
          return { state: structuredClone(this.state), information };
        } catch (error) {
          if (generation === this.generation) {
            if (!this.lifecycle.snapshot(ticket)?.handle) {
              this.lifecycle.failed(ticket, error instanceof ServerRefusal);
              job.status =
                error instanceof ServerRefusal ? 'interrupted' : 'unknown';
            }
            job.detail =
              error instanceof Error ? error.message : errorMessage(error);
            this.emit();
          }
          throw error;
        }
      }
      case 'sync-refresh':
        await this.sync.refresh();
        break;
      case 'sync-inspect':
        await this.sync.inspect(text(request.path, 'conflict path', 4096));
        break;
      case 'sync-change':
        if (!['on', 'off', 'now'].includes(request.kind))
          throw new Error('Choose a sync operation.');
        await this.sync.run(
          request.kind,
          text(request.identity, 'sync identity'),
        );
        break;
      case 'sync-resolve':
        if (!['mine', 'theirs', 'done'].includes(request.how))
          throw new Error('Choose a conflict resolution.');
        if (
          request.text !== undefined &&
          (typeof request.text !== 'string' || request.text.length > 524288)
        )
          throw new Error('The merged text is too large.');
        await this.sync.resolve(
          request.how,
          text(request.identity, 'conflict identity'),
          request.text,
        );
        break;
      case 'operator-prepare':
        await this.operator.prepare(request.kind, request.project);
        break;
      case 'operator-messages':
        await this.operator.messages(
          request.identity,
          request.instance,
          request.offset,
        );
        break;
      case 'operator-preview':
        this.operator.preview(request.identity, request.input);
        break;
      case 'operator-apply':
        await this.operator.apply(request.identity);
        break;
      case 'bootstrap':
        break;
      case 'demo':
        this.dispose();
        this.lifecycle.reset();
        this.tickets.clear();
        this.activity.reset(true);
        this.views.clear();
        this.state = demoState();
        break;
      case 'disconnect':
        this.dispose();
        this.disconnected();
        this.state.connection = 'Disconnected · server jobs are not cancelled';
        this.emit();
        await this.fileClosing;
        break;
      case 'server-setup': {
        const login = await this.initializeAdministrator(request);
        return this.dispatch({ action: 'connect', ...login, password: '' });
      }
      case 'project-access': {
        if (!this.state.connected)
          throw new Error('Connect to the server first.');
        if (
          ![
            'project.access',
            'project.member.add',
            'project.member.remove',
            'project.member.role',
          ].includes(request.operation)
        )
          throw new Error('Unknown project access operation.');
        const result = ok(
          await this.send(
            decodeRequest(request.operation, {
              project: text(request.project, 'project name', 512),
              ...(request.handle ? { handle: request.handle } : {}),
              ...(request.role ? { role: request.role } : {}),
            }),
          ),
        );
        if (request.operation !== 'project.access') {
          await this.readProjects();
          this.emit();
        }
        return {
          state: structuredClone(this.state),
          administration: decodeReply(request.operation, result.payload),
        };
      }
      case 'server-admin': {
        const operations = [
          'admin.pricing.list',
          'admin.pricing.set',
          'admin.accounts',
          'admin.account.create',
          'admin.account.update',
          'admin.account.reset',
          'admin.sessions',
          'admin.session.revoke',
          'admin.audit',
          'admin.service.accounts',
          'admin.service.account.create',
          'admin.service.account.update',
          'admin.service.tokens',
          'admin.service.token.create',
          'admin.service.token.rotate',
          'admin.service.token.revoke',
        ];
        if (!operations.includes(request.operation))
          throw new Error('Unknown server administration operation.');
        if (!this.state.connected)
          throw new Error('Connect as a server administrator first.');
        const status = ok(
          await this.send({ type: 'admin.status', payload: {} }),
        ).payload as { serverAdmin: boolean };
        this.state.serverAdmin = status.serverAdmin;
        this.emit();
        if (!status.serverAdmin)
          throw new Error('Only a server administrator may manage accounts.');
        const result = ok(
          await this.send(
            decodeRequest(request.operation, request.payload ?? {}),
          ),
        );
        return {
          state: structuredClone(this.state),
          administration: decodeReply(request.operation, result.payload),
        };
      }
      case 'server-project-create': {
        if (!this.state.connected || !this.state.serverAdmin)
          throw new Error(
            'Connect as a server administrator to add a server project.',
          );
        const type = request.type ?? 'MANAGED';
        if (type !== 'MANAGED' && type !== 'DISJOINT')
          throw new Error('Choose MANAGED or DISJOINT.');
        if (
          request.writePaths !== undefined &&
          (!isList(request.writePaths) ||
            request.writePaths.some((path) => typeof path !== 'string'))
        )
          throw new Error('Writable areas must be a list of relative paths.');
        ok(
          await this.send({
            type: 'project.create',
            payload: {
              name: text(request.name, 'project name', 512),
              type,
              ...(request.workspace?.trim()
                ? {
                    workspace: text(
                      request.workspace,
                      'server directory',
                      4096,
                    ),
                  }
                : {}),
              ...(request.writePaths === undefined
                ? {}
                : { writePaths: request.writePaths }),
            },
          }),
        );
        await this.readProjects();
        this.emit();
        break;
      }
      case 'connect': {
        const { base, handle, password } = await this.login(request);
        const previous = this.state;
        this.dispose();
        if (
          previous.mode !== 'live' ||
          previous.base !== base ||
          previous.handle !== handle
        ) {
          await this.usage.close();
          this.views.clear();
          this.activity.reset(true);
        }
        this.state =
          previous.mode === 'live' &&
          previous.base === base &&
          previous.handle === handle
            ? { ...previous, connected: false, connection: 'Connecting…' }
            : emptyConnectionState(base, handle);
        this.state.connection = 'Connecting…';
        if (this.state.jobs !== previous.jobs) {
          this.lifecycle.reset();
          this.tickets.clear();
        }
        for (const job of this.state.jobs)
          if (RUNNING.has(job.status)) job.status = 'unknown';
        this.emit();
        const generation = this.generation;
        try {
          await this.fileClosing;
          if (generation !== this.generation)
            throw new Error('Connection attempt superseded.');
          const opened = await this.connector(
            base,
            handle,
            password,
            (push) => {
              if (generation === this.generation) this.push(push);
            },
            () => {
              if (generation === this.generation) this.disconnected();
            },
          );
          if (generation !== this.generation) {
            opened.connection.close();
            throw new Error('Connection attempt superseded.');
          }
          this.connection = opened.connection;
          this.session = opened.session;
          this.peer = opened.spawn;
          if (opened.openFiles)
            this.files = new FilePresence(
              opened.openFiles,
              (value) => {
                if (generation === this.generation) {
                  this.state.files = value;
                  if (
                    value.status === 'ready' &&
                    value.project &&
                    value.root &&
                    value.machine
                  ) {
                    if (value.project.startsWith('client:')) this.sync.stop();
                    else
                      this.sync.attach(
                        {
                          project: value.project,
                          root: value.root,
                          machine: value.machine,
                        },
                        base,
                        opened.handle ?? handle,
                        opened.bearer,
                      );
                  } else this.sync.stop();
                  this.emit();
                }
              },
              () => this.sync.changedFiles(),
              opened.connection,
            );
          this.state.handle = opened.handle ?? handle;
          this.state.connected = true;
          this.state.connection = 'Connected';
          this.state.serverAdmin = opened.serverAdmin === true;
          ok(await this.send(streaming(true)));
          await this.readProjects();
          await this.loadScope(this.options.project);
          await this.refresh();
          await this.syncFollows();
          if (this.options.accountActivity !== false) {
            await this.activity.start();
            await this.board.reconnect();
          }
          await this.usage.reconnect();
          if (generation !== this.generation)
            throw new Error('Connection attempt superseded.');
          for (const job of this.state.jobs) {
            const ticket = this.tickets.get(job);
            if (
              job.status === 'unknown' &&
              ticket &&
              this.lifecycle.snapshot(ticket)?.handle
            )
              this.poll(job.id);
          }
        } catch (error) {
          if (generation === this.generation) {
            this.connection?.close();
            this.connection = undefined;
            this.disconnected();
            this.state.connection =
              error instanceof ConnectionUnavailable
                ? 'Offline · reconnect when the server is available'
                : error instanceof CredentialError
                  ? 'Authentication required'
                  : 'Connection failed';
            this.emit();
          }
          throw error;
        }
        break;
      }
      case 'files-withdraw':
        if (!this.files)
          throw new Error('Connect to the server before managing files.');
        await this.files.withdraw();
        break;
      case 'board-post-topics':
        await this.board.postingTopics(request.project, request.more);
        break;
      case 'board-create':
        await this.board.create(
          request.project,
          request.title,
          request.label,
          request.body,
          request.requestId,
          request.maxModelCalls,
        );
        break;
      case 'board-retry':
        await this.board.retry(
          request.project,
          request.topic,
          request.member,
          request.requestId,
          request.maxTurns,
          request.reconcile,
        );
        break;
      case 'board-post':
        await this.board.post(
          request.project,
          request.topic,
          request.body,
          request.requestId,
        );
        break;
      case 'board-view':
        await this.board.open(request.view, projectOf(request));
        break;
      case 'board-refresh':
        await this.board.refresh();
        break;
      case 'board-more':
        await this.board.more();
        break;
      case 'board-topic':
        await this.board.select(text(request.topic, 'topic', 512));
        break;
      case 'scope':
        await this.loadScope(projectOf(request));
        break;
      case 'activity-view':
        await this.refresh();
        if (request.view !== 'runs' && request.view !== 'builder')
          this.runs.pause();
        await this.activity.open(request.view);
        if (request.view === 'definitions' && this.state.connected)
          await this.runs.definitions(this.state.activity.definitions?.project);
        if (request.view === 'schedules' && this.state.connected)
          await this.schedules.refresh();
        break;
      case 'activity-refresh':
        await this.refresh();
        await this.activity.refresh();
        if (this.state.activity.view === 'definitions' && this.state.connected)
          await this.runs.definitions(this.state.activity.definitions?.project);
        if (this.state.activity.view === 'schedules' && this.state.connected)
          await this.schedules.refresh();
        break;
      case 'question-refresh':
        await this.activity.refresh();
        break;
      case 'inbox-read':
        await this.activity.mark(text(request.id, 'inbox item', 512));
        break;
      case 'inbox-more':
        await this.activity.older();
        break;
      case 'run-detail':
        await this.activity.select(text(request.id, 'run', 512));
        break;
      case 'library-view':
        await this.library.open(
          request.view,
          request.project,
          request.revision,
          request.chapter,
        );
        break;
      case 'library-refresh':
        await this.library.refresh();
        break;
      case 'library-documents':
        await this.library.documents(request.query, request.more);
        break;
      case 'library-source-text':
        await this.library.sourceText(request.id, request.offset);
        break;
      case 'library-document':
        await this.library.document(request.id);
        break;
      case 'library-memory':
        await this.library.memory(request.id);
        break;
      case 'library-chunk':
        await this.library.chunk(request.id);
        break;
      case 'library-citations':
        await this.library.citations();
        break;
      case 'library-stance':
        await this.library.stance(request.claim);
        break;
      case 'library-search':
        await this.library.search(
          request.kind,
          request.query,
          request.mode,
          request.more,
        );
        break;
      case 'library-maintain':
        await this.library.maintain(
          request.kind,
          request.identity,
          request.reason,
          request.accept,
        );
        break;
      case 'schedule-refresh':
        await this.schedules.refresh();
        break;
      case 'schedule-preview':
        await this.schedules.preview(
          request.text,
          request.zone,
          projectOf(request),
          request.conversation,
        );
        break;
      case 'schedule-save':
        await this.schedules.save(
          request.identity,
          request.definition,
          request.source,
          projectOf(request),
        );
        break;
      case 'schedule-file-save':
        await this.schedules.saveFile(
          request.name,
          request.definition,
          request.source,
          projectOf(request),
          request.overwrite,
          request.identity,
        );
        break;
      case 'schedule-sync':
        await this.schedules.sync(request.source, projectOf(request));
        break;
      case 'schedule-change':
        await this.schedules.change(
          request.kind,
          request.name,
          request.identity,
          request.paused,
        );
        break;
      case 'schedule-fire':
        await this.schedules.fire(request.trigger, request.identity);
        break;
      case 'run-definitions':
        await this.runs.definitions(projectOf(request));
        break;
      case 'run-record':
        await this.runs.record(
          text(request.id, 'run', 512),
          request.before,
          request.kinds,
        );
        break;
      case 'run-answer':
        await this.runs.answer(
          text(request.id, 'run', 512),
          request.question,
          request.answer,
          request.choices,
        );
        break;
      case 'run-resume':
        await this.runs.resume(text(request.id, 'run', 512));
        break;
      case 'run-cancel':
        await this.runs.cancel(text(request.id, 'run', 512));
        break;
      case 'create': {
        const project = projectOf(request);
        if (this.state.mode === 'demo') {
          conversation = `demo-${randomUUID()}`;
          this.state.conversations.unshift({
            id: conversation,
            ...(project ? { project } : {}),
            title: 'New conversation',
          });
          this.state.history[conversation] = { entries: [], more: false };
        } else {
          const row = opened(ok(await this.send(opening(project))));
          if (!row)
            throw new Error('The server did not return a conversation.');
          this.state.conversations.unshift(row);
          conversation = row.id;
          this.state.history[conversation] = {
            entries: [],
            more: false,
            through: 0,
          };
        }
        break;
      }
      case 'history':
        await this.history(
          text(request.conversation, 'conversation', 512),
          request.before,
        );
        break;
      case 'select':
        await this.followView('chat', request.conversation);
        break;
      case 'context-snapshot':
        await this.snapshot(
          text(request.conversation, 'conversation', 512),
          text(request.agent, 'agent', 512),
          request.measure === true,
        );
        break;
      case 'context':
        await this.measure(
          text(request.conversation, 'conversation', 512),
          text(request.agent, 'agent', 512),
        );
        break;
      case 'workflow-start':
        notice = await this.startWorkflow(request);
        break;
      case 'run':
        await this.run(
          text(request.conversation, 'conversation', 512),
          text(request.agent, 'agent', 512),
          text(request.text, 'message'),
        );
        break;
      case 'cancel': {
        const id = text(request.job, 'job', 512);
        const job = this.state.jobs.find((j) => j.id === id);
        if (!job || !activeJob(job))
          throw new Error('This job is no longer active.');
        if (this.state.mode === 'demo') {
          this.stopDemo(job, 'cancelled');
        } else {
          const answer = ok(await this.send(cancelling(id)));
          const ticket = this.tickets.get(job);
          if (ticket) {
            this.lifecycle.cancelling(ticket);
            this.lifecycle.observe(ticket, answer);
          }
          if (job.status !== 'finished') {
            job.status = 'cancelling';
            this.poll(id);
          }
        }
        break;
      }
      case 'approvals-refresh':
        await this.refresh();
        break;
      case 'refresh':
        if (this.state.mode === 'live' && this.state.connected) {
          await this.readProjects();
          await Promise.all(
            Object.keys(this.state.agents).map((key) =>
              this.readAgents(key || undefined),
            ),
          );
        }
        await this.refresh();
        if (this.options.accountActivity !== false)
          await this.activity.refresh();
        this.followed = '';
        await this.syncFollows();
        await Promise.all(
          [...new Set(this.views.values())].map((id) => this.catchUp(id)),
        );
        break;
      case 'answer': {
        const id = text(request.id, 'approval', 512);
        if (!['once', 'deny'].includes(request.decision))
          throw new Error('Choose allow once or deny.');
        if (this.state.mode === 'live')
          notice = await this.answerApproval(id, request.decision);
        else
          this.state.approvals = this.state.approvals.filter(
            (row) => row.id !== id,
          );
        await this.refresh();
        break;
      }
      default:
        throw new Error('Unsupported desktop action.');
    }
    this.emit();
    return {
      state: structuredClone(this.state),
      ...(conversation ? { conversation } : {}),
      ...(notice ? { notice } : {}),
    };
  }
  /** Main-process view ownership; renderers cannot register arbitrary native views. */
  async followView(view: string, conversation?: string) {
    if (conversation !== undefined) {
      text(conversation, 'conversation', 512);
      if (!inspectionConversation(this.state, conversation))
        throw new Error('Choose an available conversation to follow.');
      this.views.set(view, conversation);
    } else this.views.delete(view);
    if (this.state.mode === 'demo' || !this.state.connected) return;
    await this.syncFollows();
    if (conversation && [...this.views.values()].includes(conversation)) {
      const reading = this.catchups.get(conversation);
      if (reading) await reading;
      else if (
        this.state.liveHistory?.status !== 'ready' ||
        this.state.history[conversation]?.through === undefined
      )
        await this.catchUp(conversation);
    }
  }
  private syncFollows(): Promise<void> {
    if (this.followFlight) return this.followFlight;
    const generation = this.generation;
    const work = async () => {
      while (
        generation === this.generation &&
        this.state.connected &&
        this.state.mode === 'live'
      ) {
        const ids = [...new Set(this.views.values())].sort();
        const signature = JSON.stringify(ids);
        if (signature === this.followed) return;
        this.state.liveHistory = { status: 'updating' };
        this.emit();
        try {
          ok(await this.send(followingLogs(ids)));
          if (generation !== this.generation) return;
          this.followed = signature;
          this.state.liveHistory = { status: 'ready' };
          this.emit();
          // Read after subscribing, including events that preceded its acknowledgement.
          // History reads do not hold up removal of a closed view's subscription.
          for (const id of ids)
            if ([...this.views.values()].includes(id))
              this.background(this.catchUp(id));
        } catch (error) {
          if (generation === this.generation) {
            this.state.liveHistory = {
              status: 'unavailable',
              detail: `Live history unavailable. Update the server to support multiple conversation follows, or refresh manually. ${error instanceof Error ? error.message : errorMessage(error)}`,
            };
            this.emit();
          }
          return; // Never downgrade to single-log follows or replay any agent mutation.
        }
      }
    };
    const flight = work();
    this.followFlight = flight;
    this.background(
      flight.finally(() => {
        if (this.followFlight === flight) this.followFlight = undefined;
      }),
    );
    return flight;
  }
  private catchUp(id: string): Promise<void> {
    this.dirtyHistories.add(id);
    const existing = this.catchups.get(id);
    if (existing) return existing;
    const generation = this.generation;
    const work = async () => {
      do {
        this.dirtyHistories.delete(id);
        try {
          await this.history(id);
        } catch (error) {
          if (generation === this.generation) {
            this.state.history[id] ??= { entries: [], more: false };
            this.state.history[id].error =
              error instanceof Error ? error.message : errorMessage(error);
            this.emit();
          }
          return;
        }
      } while (
        generation === this.generation &&
        this.state.connected &&
        this.dirtyHistories.has(id) &&
        [...this.views.values()].includes(id)
      );
    };
    const flight = work();
    this.catchups.set(id, flight);
    this.background(
      flight.finally(() => {
        if (this.catchups.get(id) === flight) this.catchups.delete(id);
      }),
    );
    return flight;
  }
  private async answerApproval(id: string, decision: 'once' | 'deny') {
    if (this.state.answeringApprovals?.includes(id))
      throw new Error('This approval is already being answered.');
    const approval = this.state.approvals.find(
      (row) => row.id === id && row.state === 'asked',
    );
    if (!approval)
      throw new Error(
        'Refresh the conversation to read this approval before answering it.',
      );
    const generation = this.generation;
    this.state.answeringApprovals = [
      ...(this.state.answeringApprovals ?? []),
      id,
    ];
    const job: Job = {
      id: `pending-${randomUUID()}`,
      conversation: approval.conversation,
      agent: approval.agent,
      task: `Approval ${id}: ${decision === 'once' ? 'allow once' : 'deny'}`,
      source: 'approval',
      logAfter: this.logThrough(approval.conversation),
      status: 'starting',
      text: '',
      events: [],
    };
    const ticket = this.lifecycle.begin(job.conversation);
    this.tickets.set(job, ticket);
    this.state.jobs.push(job);
    this.emit();
    try {
      const answer = answeredOf(
        ok(await this.send(answeringApproval(id, decision))),
        id,
      );
      if (!answer)
        throw new Error(
          'The approval answer carried no continuation information. Inspect it before retrying.',
        );
      this.state.approvals = this.state.approvals.filter(
        (row) => row.id !== id,
      );
      if (answer.job) {
        const held = this.lifecycle.accept(ticket, answer.job).events;
        job.id = answer.job;
        job.status = 'running';
        for (const event of held) this.push(event);
        this.poll(job.id);
        await this.history(job.conversation);
      } else {
        this.lifecycle.forget(ticket);
        this.tickets.delete(job);
        this.state.jobs = this.state.jobs.filter((row) => row !== job);
      }
      return answer.note ? `Approval recorded. ${answer.note}` : undefined;
    } catch (error) {
      if (generation === this.generation) {
        if (!this.lifecycle.snapshot(ticket)?.handle) {
          this.lifecycle.failed(ticket, error instanceof ServerRefusal);
          if (error instanceof ServerRefusal) {
            this.lifecycle.forget(ticket);
            this.tickets.delete(job);
            this.state.jobs = this.state.jobs.filter((row) => row !== job);
          } else {
            job.status = 'unknown';
            job.detail =
              'The approval may have been recorded. Inspect its state before answering again.';
          }
        } else
          job.detail =
            error instanceof Error ? error.message : errorMessage(error);
        this.emit();
      }
      throw error;
    } finally {
      if (generation === this.generation) {
        this.state.answeringApprovals = this.state.answeringApprovals?.filter(
          (value) => value !== id,
        );
        this.emit();
      }
    }
  }
  private async loadScope(project?: string) {
    if (this.state.mode === 'demo') return;
    const key = homeKey(project);
    const read = (this.scopeReads.get(key) ?? 0) + 1;
    this.scopeReads.set(key, read);
    const agentRead = (this.agentReads.get(key) ?? 0) + 1;
    this.agentReads.set(key, agentRead);
    const [listed, roster] = await Promise.all([
      this.send(listingConversations(project)),
      this.send(listingAgents(project)),
    ]);
    const rows = conversations(ok(listed));
    const served = agents(ok(roster));
    if (!rows || !served)
      throw new Error('The server returned an unreadable listing.');
    if (read !== this.scopeReads.get(key)) return;
    this.state.conversations = [
      ...this.state.conversations.filter((row) => homeKey(row.project) !== key),
      ...rows,
      ...[...this.retainedConversations.values()].filter(
        (row) =>
          homeKey(row.project) === key &&
          !rows.some((listed) => listed.id === row.id),
      ),
    ];
    if (agentRead === this.agentReads.get(key)) this.state.agents[key] = served;
  }
  private async readAgents(project?: string) {
    if (this.state.mode === 'demo' || !this.state.connected) return;
    const key = homeKey(project);
    const read = (this.agentReads.get(key) ?? 0) + 1;
    this.agentReads.set(key, read);
    const served = agents(ok(await this.send(listingAgents(project))));
    if (!served)
      throw new Error('The server returned an unreadable agent listing.');
    if (read === this.agentReads.get(key)) this.state.agents[key] = served;
  }
  async botConversations(project: string): Promise<Record<string, string>> {
    const latest: Record<string, string> = {};
    for (const bot of (this.state.agents[project] ?? []).filter(
      (row) => row.bot,
    )) {
      const answer = ok(await this.send(continuing(bot.name, project)));
      if (answer.payload == null) continue;
      const conversation = opened(answer);
      if (
        !conversation ||
        !this.state.conversations.some(
          (row) => row.id === conversation.id && row.project === project,
        )
      )
        continue;
      latest[bot.name] = conversation.id;
    }
    return latest;
  }
  private async history(id: string, before?: number) {
    if (this.state.mode === 'demo') return;
    if (before !== undefined && (!Number.isSafeInteger(before) || before < 0))
      throw new Error('Invalid history cursor.');
    const generation = this.generation;
    const read = (this.historyReads.get(id) ?? Promise.resolve())
      .catch(() => {})
      .then(async () => {
        if (generation !== this.generation)
          throw new Error('The connection changed while reading history.');
        const previous = this.state.history[id];
        const after = before === undefined ? previous?.through : undefined;
        const held = new Map(
          (previous?.entries ?? []).map((entry) => [entry.ordinal, entry]),
        );
        let offset = 0;
        let through: number | undefined;
        let latest = 0;
        let oldest = previous?.oldest;
        let more = previous?.more ?? false;
        for (;;) {
          const answer = ok(
            await this.send(
              before !== undefined
                ? readingLogBefore(id, before)
                : after === undefined
                  ? readingLogTail(id)
                  : readingTrace(id, after, offset),
            ),
          );
          const page = backPageOf(answer);
          const reported = logThrough(answer);
          const raw = fieldsOf(answer.payload)['entries'];
          if (
            !page ||
            reported === undefined ||
            !isList(raw) ||
            page.entries.length !== raw.length
          )
            throw new Error('The server returned an unreadable trajectory.');
          through ??= reported;
          latest = Math.max(latest, reported);
          for (const entry of page.entries) held.set(entry.ordinal, entry);
          if (before !== undefined || after === undefined) {
            oldest = Math.min(oldest ?? Infinity, page.oldest ?? Infinity);
            more =
              previous?.oldest !== undefined &&
              before === undefined &&
              previous.oldest < (page.oldest ?? Infinity)
                ? previous.more
                : page.more;
            break;
          }
          offset += page.entries.length;
          if (offset >= (page.total ?? offset)) break;
          if (!page.entries.length)
            throw new Error(
              'The server returned an incomplete history page. Refresh to retry.',
            );
        }
        if (generation !== this.generation) return;
        this.state.history[id] = {
          entries: [...held.values()].sort(
            (a, b) => a.turnOrdinal - b.turnOrdinal || a.ordinal - b.ordinal,
          ),
          more,
          ...(oldest === undefined || oldest === Infinity ? {} : { oldest }),
          ...(before !== undefined
            ? previous?.through === undefined
              ? {}
              : { through: previous.through }
            : { through }),
        };
        if (before === undefined && latest > (through ?? 0))
          this.dirtyHistories.add(id);
        this.emit();
      });
    this.historyReads.set(id, read);
    try {
      await read;
    } finally {
      if (this.historyReads.get(id) === read) this.historyReads.delete(id);
    }
  }
  private async snapshot(
    conversation: string,
    agent: string,
    measure: boolean,
  ) {
    if (this.state.mode === 'demo') return;
    const key = contextKey(conversation, agent),
      readKey = `snapshot:${key}`;
    const sequence = (this.contextReads.get(readKey) ?? 0) + 1;
    this.contextReads.set(readKey, sequence);
    const generation = this.generation;
    this.state.contextSnapshots ??= {};
    this.state.contextSnapshots[key] = {
      ...(this.state.contextSnapshots[key]?.value === undefined
        ? {}
        : { value: this.state.contextSnapshots[key]?.value }),
      loading: true,
    };
    this.emit();
    try {
      const value = await this.usageClient.call(
        'conversation.context.snapshot',
        { conversation, agent, measure },
      );
      if (
        generation !== this.generation ||
        sequence !== this.contextReads.get(readKey)
      )
        return;
      this.state.contextSnapshots[key] = { value };
    } catch (reason) {
      if (
        generation === this.generation &&
        sequence === this.contextReads.get(readKey)
      )
        this.state.contextSnapshots[key] = {
          ...(this.state.contextSnapshots[key]?.value === undefined
            ? {}
            : { value: this.state.contextSnapshots[key]?.value }),
          error:
            reason instanceof Error ? reason.message : errorMessage(reason),
        };
    }
  }
  private async measure(conversation: string, agent: string) {
    if (this.state.mode === 'demo') return;
    const key = contextKey(conversation, agent);
    const sequence = (this.contextReads.get(key) ?? 0) + 1;
    this.contextReads.set(key, sequence);
    const generation = this.generation;
    try {
      const answer = await this.send(measuring(conversation, agent));
      if (
        generation !== this.generation ||
        sequence !== this.contextReads.get(key)
      )
        return;
      const load = loadOf(answer);
      this.state.contexts[key] =
        load === undefined
          ? {
              ...this.state.contexts[key],
              status: 'unavailable',
              detail:
                answer.said ??
                'The server did not provide a context measurement.',
            }
          : { ...load, status: 'ready' };
    } catch {
      if (
        generation === this.generation &&
        sequence === this.contextReads.get(key)
      ) {
        this.state.contexts[key] = {
          ...this.state.contexts[key],
          status: 'unavailable',
          detail: 'Context measurement unavailable.',
        };
      }
    }
  }
  async authoringTurn(id: string, agent: string, task: string) {
    return this.run(id, agent, task, true);
  }
  private workflowStarts = new Set<string>();
  private async startWorkflow(
    input: Extract<Request, { action: 'workflow-start' }>,
  ) {
    if (!this.state.connected || this.state.mode !== 'live')
      throw new Error('Connect before starting a workflow.');
    const conversation = this.state.conversations.find(
      (row) => row.id === input.conversation,
    );
    if (!conversation) throw new Error('Open a conversation first.');
    const agent = this.state.agents[homeKey(conversation.project)]?.find(
      (row) => row.name === input.agent && row.served && row.bot,
    );
    if (
      !agent?.commands?.some(
        (command) =>
          command.kind === 'orchestration' && command.name === input.definition,
      )
    )
      throw new Error('Choose a workflow granted to this agent.');
    text(input.text, 'workflow request');
    if (
      typeof input.requestId !== 'string' ||
      !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(input.requestId)
    )
      throw new Error('Retain a valid workflow request ID.');
    if (this.workflowStarts.has(input.requestId))
      throw new Error('This workflow launch is already in progress.');
    this.workflowStarts.add(input.requestId);
    try {
      const answer = ok(
        await this.send({
          type: 'orchestration.start',
          payload: {
            agent: input.agent,
            definition: input.definition,
            request: input.text,
            requestId: input.requestId,
            ...(conversation.project ? { project: conversation.project } : {}),
          },
        }),
        'ACCEPTED',
      );
      const receipt = answer.payload as
        { id?: string; requestId?: string } | undefined;
      if (!receipt?.id || receipt.requestId !== input.requestId)
        throw new Error(
          'The server did not confirm this workflow receipt. Retry with the retained request ID.',
        );
      return `Started ${input.definition} (${receipt.id}). Follow its progress and questions in Runs.`;
    } catch (error) {
      throw new Error(
        `${error instanceof Error ? error.message : errorMessage(error)} The request ID is retained; retrying this launch recovers the same run.`,
        { cause: error },
      );
    } finally {
      this.workflowStarts.delete(input.requestId);
    }
  }
  private async run(
    id: string,
    agent: string,
    task: string,
    authoring = false,
  ) {
    const conversation = this.state.conversations.find((row) => row.id === id);
    if (!conversation) throw new Error('Open a conversation first.');
    // Establish the pre-turn watermark before submitting; an unloaded log is not empty.
    if (
      this.state.mode === 'live' &&
      this.state.history[id]?.through === undefined
    )
      await this.history(id);
    if (
      this.state.jobs.some((job) => job.conversation === id && activeJob(job))
    )
      throw new Error(
        'This conversation already has an active or uncertain job.',
      );
    const roster = this.state.agents[homeKey(conversation.project)] ?? [];
    if (
      !roster.some(
        (row) =>
          row.name === agent &&
          row.served &&
          (authoring
            ? row.orchestrations.includes('design_orchestration')
            : row.bot),
      )
    )
      throw new Error(
        authoring
          ? 'Choose a served caller granted design_orchestration.'
          : 'Choose a served conversational agent.',
      );
    const generation = this.generation;
    const job: Job = {
      id: `pending-${randomUUID()}`,
      conversation: id,
      agent,
      task,
      logAfter: this.logThrough(id),
      status: 'starting',
      text: '',
      events: [],
    };
    const ticket =
      this.state.mode === 'live' ? this.lifecycle.begin(id) : undefined;
    if (ticket) this.tickets.set(job, ticket);
    this.state.jobs.push(job);
    this.emit();
    try {
      if (this.state.mode === 'demo') {
        this.startDemo(job);
        return;
      }
      const answer = ok(
        await this.send(speaking(id, agent, task, this.session)),
        'ACCEPTED',
      );
      const handle = acceptedJobOf(answer)?.id;
      if (handle === undefined)
        throw new Error('The accepted run did not carry a job handle.');
      const held = this.lifecycle.accept(ticket!, handle).events;
      job.id = handle;
      job.status = 'running';
      for (const event of held) this.push(event);
      this.poll(handle);
      await this.history(id);
    } catch (error) {
      if (generation === this.generation) {
        // A transport failure can happen after the server accepted a mutation.
        if (ticket && !this.lifecycle.snapshot(ticket)?.handle) {
          this.lifecycle.failed(ticket, error instanceof ServerRefusal);
          job.status =
            error instanceof ServerRefusal ? 'interrupted' : 'unknown';
        }
        job.detail =
          error instanceof Error ? error.message : errorMessage(error);
        this.emit();
      }
      throw error;
    }
  }
  private logThrough(id: string) {
    const history = this.state.history[id];
    return (
      history?.through ??
      Math.max(0, ...(history?.entries ?? []).map((entry) => entry.ordinal))
    );
  }
  private push(value: unknown) {
    if (this.usage.push(value)) return;
    const event = fieldsOf(value);
    if (event['kind'] === 'inbox.changed')
      void this.refresh()
        .then(() => this.emit())
        .catch(() => {});
    if (typeof event['job'] !== 'string') {
      if (this.options.accountActivity !== false) {
        this.activity.push(value);
        this.runs.push(value);
      }
      const appended = appendedOf(value);
      if (
        appended &&
        [...this.views.values()].includes(appended.conversation) &&
        appended.through >
          (this.state.history[appended.conversation]?.through ?? -1)
      )
        this.background(this.catchUp(appended.conversation));
      return;
    }
    const notice = jobNotification(event);
    if (!notice) return;
    const delivery = this.lifecycle.push(notice);
    if (!delivery) return;
    const id = event['job'];
    const job = [...this.tickets].find(
      ([, ticket]) => ticket === delivery.ticket,
    )?.[0];
    if (!job) return;
    const delta = streamed(event);
    if (delta?.part === 'answer') {
      job.text += delta.text;
      job.phase = 'answer';
    } else if (delta?.part === 'thinking') {
      job.thinking = (job.thinking ?? '') + delta.text;
      job.phase = 'thinking';
    } else if (!delta) {
      job.events.push(notice);
      if (job.events.length > 200) job.events.shift();
    }
    if (event['kind'] === 'tool_called') {
      job.phase = 'tool';
      job.tool = typeof event['tool'] === 'string' ? event['tool'] : undefined;
    }
    if (event['kind'] === 'model_call') {
      job.phase = 'thinking';
      job.tool = undefined;
    }
    if (event['kind'] === 'ended') this.readJob(id).catch(() => {});
    this.emit();
  }
  private poll(id: string) {
    if (this.timers.has(id)) return;
    const generation = this.generation;
    let busy = false;
    this.timers.set(
      id,
      setInterval(() => {
        void (async () => {
          if (busy || generation !== this.generation) return;
          busy = true;
          try {
            await this.readJob(id);
            await this.refresh();
          } catch (error) {
            if (generation !== this.generation) return;
            const job = this.state.jobs.find((row) => row.id === id);
            if (job)
              job.detail =
                error instanceof Error ? error.message : errorMessage(error);
          } finally {
            busy = false;
          }
        })().catch((cause: unknown) => {
          const job = this.state.jobs.find((row) => row.id === id);
          if (job) job.detail = errorMessage(cause);
          this.emit();
        });
      }, 2000),
    );
    void this.readJob(id).catch(() => {});
  }
  private async readJob(id: string) {
    const generation = this.generation;
    const answer = ok(await this.send(checking(id)));
    const job = this.state.jobs.find((row) => row.id === id);
    if (!job) return;
    const ticket = this.tickets.get(job);
    if (!ticket || generation !== this.generation) return;
    const status = jobStatusOf(answer, id);
    if (
      status &&
      status.conversation != null &&
      status.conversation !== job.conversation
    )
      throw new Error(
        'The returned job belongs to another conversation; completion is unknown.',
      );
    const tracked = this.lifecycle.observe(ticket, answer, generation);
    if (status === undefined)
      throw new Error(
        'The server returned an unreadable job status; completion is unknown.',
      );
    const outcome = tracked?.outcome;
    const payload = fieldsOf(answer.payload);
    const ended = reached(answer, id);
    if (ended) {
      job.answered = ended.answered;
      job.allowance = ended.allowance;
      job.pace = ended.pace;
    } else {
      const limits = fieldsOf(payload['limits']);
      if (
        typeof limits['modelCallsSpent'] === 'number' &&
        typeof limits['noBudget'] === 'boolean'
      ) {
        job.allowance = {
          modelCallsSpent: limits['modelCallsSpent'],
          noBudget: limits['noBudget'],
          noTurnCap: limits['noTurnCap'] === true,
          ...(typeof limits['maxModelCalls'] === 'number'
            ? { maxModelCalls: limits['maxModelCalls'] }
            : {}),
          ...(typeof limits['maxTurns'] === 'number'
            ? { maxTurns: limits['maxTurns'] }
            : {}),
        };
      }
    }
    const ending = outcome?.ending;
    if (tracked?.state === 'finished' && typeof ending === 'string') {
      job.status = 'finished';
      job.ending = ending;
      if (typeof outcome?.text === 'string') job.text = outcome.text;
      job.detail = ended?.detail;
      clearInterval(this.timers.get(id));
      this.timers.delete(id);
      if (job.conversation) {
        await this.history(job.conversation);
        this.background(
          this.measure(job.conversation, job.agent).then(() => this.emit()),
        );
      }
    } else if (
      tracked?.state === 'running' ||
      tracked?.state === 'cancelling'
    ) {
      if (job.status === 'unknown') job.detail = undefined;
      job.status = tracked.state;
    }
    this.emit();
  }
  private refresh(): Promise<void> {
    if (this.state.mode === 'demo' || !this.state.connected)
      return Promise.resolve();
    const generation = this.generation;
    if (this.approvalRefresh?.generation === generation) {
      this.approvalRefresh.dirty = true;
      return this.approvalRefresh.promise;
    }
    const flight = { generation, dirty: false, promise: Promise.resolve() };
    this.approvalRefresh = flight;
    flight.promise = (async () => {
      do {
        flight.dirty = false;
        const listed = approvalsOf(ok(await this.send(listingMyApprovals())));
        if (!listed)
          throw new Error(
            'The server returned an unreadable approval listing.',
          );
        if (generation === this.generation && this.state.connected)
          this.state.approvals = listed;
      } while (
        flight.dirty &&
        generation === this.generation &&
        this.state.connected
      );
    })().finally(() => {
      if (this.approvalRefresh === flight) this.approvalRefresh = undefined;
    });
    return flight.promise;
  }
  private startDemo(job: Job) {
    job.id = `demo-job-${randomUUID()}`;
    job.status = 'running';
    job.phase = 'thinking';
    const history = this.state.history[job.conversation];
    if (history === undefined)
      throw new Error('demo conversation history is unavailable');
    const ordinal = (history.entries.at(-1)?.ordinal ?? 0) + 1;
    history.entries.push({
      ordinal,
      turnOrdinal: ordinal,
      kind: 'utterance',
      state: 'stands',
      speaker: 'person',
      text: job.task,
    });
    const conversation = this.state.conversations.find(
      (row) => row.id === job.conversation,
    )!;
    if (conversation.title === 'New conversation')
      this.state.conversations = this.state.conversations.map((row) =>
        row.id === conversation.id
          ? { ...row, title: job.task.slice(0, 52) }
          : row,
      );
    job.events.push(
      { kind: 'started', agent: job.agent, job: job.id },
      {
        kind: 'model_call',
        agent: job.agent,
        job: job.id,
        steps: 1,
        modelCalls: 1,
      },
    );
    const answer = demoAnswer(job.task);
    let offset = 0;
    let thinkingTicks = 10;
    this.demoTimers.set(
      job.id,
      setInterval(() => {
        // A visibly labelled demo pause lets the thinking indicator be tried offline.
        if (thinkingTicks-- > 0) return;
        job.phase = 'answer';
        offset += 25;
        job.text = answer.slice(0, offset);
        if (offset >= answer.length) this.stopDemo(job, 'answered');
        this.emit();
      }, 70),
    );
  }
  private stopDemo(job: Job, ending: string) {
    clearInterval(this.demoTimers.get(job.id));
    this.demoTimers.delete(job.id);
    job.status = 'finished';
    job.ending = ending;
    job.answered = ending === 'answered';
    job.events.push({ kind: 'ended', job: job.id, ending });
    if (ending === 'answered') {
      const history = this.state.history[job.conversation];
      if (history === undefined)
        throw new Error('demo conversation history is unavailable');
      const ordinal = (history.entries.at(-1)?.ordinal ?? 0) + 1;
      history.entries.push({
        ordinal,
        turnOrdinal: ordinal - 1,
        kind: 'answer',
        state: 'stands',
        text: job.text,
      });
    }
  }
}
