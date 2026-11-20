import { createHash, randomInt, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import type { Server } from 'node:http';
import { lstat, mkdir, open, readdir, rm } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as pause } from 'node:timers/promises';
import { Credentials, LoginRequired } from 'plowshare-client-node/credentials';
import {
  Connections,
  atomicPrivateWrite,
  connectionName,
  localLock,
  privateDirectory,
  privateFile,
  resolveConnection,
  userConfigDirectory,
} from 'plowshare-client-node/connections';
import type {
  ConnectionRegistry,
  NamedConnection,
} from 'plowshare-client-node/connections';
import { authenticate } from 'plowshare-client-node/session';
import type { Session } from 'plowshare-client-node/session';
import {
  FileToolJournal,
  nodeToolHost,
  toolDeploymentConfig,
} from 'plowshare-client-node/tools';
import { decodeToolCall, requirePayload } from 'plowshare-client-ts';
import type {
  ImageAdmissionReceipt,
  Payloads,
  Replies,
} from 'plowshare-client-ts';
import { checkedTransport } from 'plowshare-client-ts/operations/transport';
import { computerProviderConnection } from './provider-connection.ts';
import { computerDeclarations, createComputerProvider } from './provider.ts';
import type { ComputerProvider } from './provider.ts';
import { ComputerPasses } from './runner.ts';
import { absolutePrivatePath } from './config.ts';
import { computerId, decodeCloseArguments } from './contracts.ts';
import type { ComputerBackend, ComputerOwner } from './contracts.ts';
import {
  DeadlineExceeded,
  OperationDeadline,
  positiveBudget,
  systemClock,
} from './deadline.ts';
import type { Clock } from './deadline.ts';
import { createBrowserBackend } from './backends/browser.ts';
import type { BackendHandle } from './backends/types.ts';

export interface AcceptanceConfig {
  readonly connectionName: string;
  readonly project: string;
  readonly model: string;
  readonly browserExecutable: string;
  readonly workDirectory: string;
  readonly journalDirectory: string;
  readonly evidenceFile: string;
  readonly budgetMs: number;
}
export interface AcceptanceImage {
  readonly invocation: string;
  readonly retainedInvocation: string;
  readonly call: string;
  readonly project: string;
  readonly provider: string;
  readonly account: string;
  readonly trajectoryCallLinked: boolean;
  readonly state: string;
  readonly use: string;
  readonly admission: ImageAdmissionReceipt;
  readonly capturedSha256: string;
  readonly capturedMimeType: string;
  readonly capturedWidth: number;
  readonly capturedHeight: number;
  readonly capturedByteLength: number;
  readonly artifactState: string;
  readonly artifactReceipt: ImageAdmissionReceipt | null;
}
export interface AcceptanceRound {
  readonly expected: string;
  readonly answer: string;
  readonly answered: boolean;
  readonly ending: string;
  readonly job: string;
  readonly conversation: string;
  readonly project: string;
  readonly account: string;
  readonly provider: string;
  readonly jobLinked: boolean;
  readonly trajectoryComplete: boolean;
  readonly procComplete: boolean;
  readonly images: readonly AcceptanceImage[];
  readonly wireModels: readonly string[];
  readonly procModels: readonly string[];
  readonly routeReadFailures: readonly string[];
}
interface MetadataIntent {
  readonly requestId: string;
  readonly kind: 'publish' | 'withdraw';
  state: 'pending' | 'confirmed';
}
export interface AcceptanceEvidence {
  readonly version: 1;
  readonly connectionName: string;
  readonly selectedAccount: string;
  readonly requestedModel: string;
  readonly startedAt: string;
  finishedAt: string | null;
  state: 'RUNNING' | 'CHECKS_PASSED' | 'FAILED';
  project: string | null;
  agent: string | null;
  scope: string | null;
  provider: string | null;
  currentJob: string | null;
  currentConversation: string | null;
  stage: string;
  readonly rounds: AcceptanceRound[];
  readonly catalogueIntents: MetadataIntent[];
  readonly failures: string[];
  readonly cleanup: {
    stopped: boolean;
    drained: boolean;
    browserClosed: boolean;
    scopeDisconnected: boolean;
    catalogueWithdrawn: boolean;
    fixtureClosed: boolean;
    workspaceRetained: true;
  };
}
type AcceptanceCode =
  | 'CONFIG_INVALID'
  | 'LOGIN_REQUIRED'
  | 'IDENTITY_MISMATCH'
  | 'WORKSPACE_NOT_FRESH'
  | 'VISION_NOT_PROVEN'
  | 'DEADLINE_EXPIRED'
  | 'UNCERTAIN_EFFECT'
  | 'FAILED';
export class AcceptanceError extends Error {
  constructor(
    readonly code: AcceptanceCode,
    options?: ErrorOptions,
  ) {
    super(code, options);
    this.name = 'AcceptanceError';
  }
}
export function acceptanceFailureCode(error: unknown): AcceptanceCode {
  if (error instanceof AcceptanceError) return error.code;
  if (error instanceof LoginRequired) return 'LOGIN_REQUIRED';
  if (error instanceof DeadlineExceeded) return 'DEADLINE_EXPIRED';
  return 'FAILED';
}

function object(value: unknown): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new AcceptanceError('CONFIG_INVALID');
  return value as Readonly<Record<string, unknown>>;
}
function withinPath(root: string, path: string): boolean {
  const selected = relative(root, path);
  return (
    selected === '' ||
    (selected !== '..' &&
      !selected.startsWith('..' + sep) &&
      !isAbsolute(selected))
  );
}

/** Named SDK connection selection is explicit; no endpoint, token or account override is accepted. */
export function decodeAcceptanceConfig(value: unknown): AcceptanceConfig {
  try {
    const row = object(value),
      keys = [
        'connectionName',
        'project',
        'model',
        'browserExecutable',
        'workDirectory',
        'journalDirectory',
        'evidenceFile',
        'budgetMs',
      ];
    if (Object.keys(row).some((key) => !keys.includes(key)))
      throw new Error('Unknown acceptance field');
    if (typeof row['connectionName'] !== 'string')
      throw new Error('Missing named connection');
    const project = computerId(row['project']);
    if (
      project.toLowerCase().startsWith('personal:') ||
      project.toLowerCase().startsWith('client:')
    )
      throw new Error('Acceptance needs a fresh ordinary project label');
    const workDirectory = absolutePrivatePath(row['workDirectory']),
      journalDirectory = absolutePrivatePath(row['journalDirectory']);
    const evidenceFile = absolutePrivatePath(row['evidenceFile']);
    if (
      withinPath(workDirectory, journalDirectory) ||
      withinPath(journalDirectory, workDirectory) ||
      withinPath(workDirectory, evidenceFile) ||
      withinPath(journalDirectory, evidenceFile) ||
      evidenceFile === workDirectory ||
      evidenceFile === journalDirectory
    )
      throw new Error(
        'Acceptance state must be separated from the controlled workspace and journal',
      );
    const budget = row['budgetMs'] === undefined ? 180_000 : row['budgetMs'];
    if (typeof budget !== 'number')
      throw new Error('Invalid acceptance budget');
    return Object.freeze({
      connectionName: connectionName(row['connectionName']),
      project,
      model: computerId(row['model']),
      browserExecutable: absolutePrivatePath(row['browserExecutable']),
      workDirectory,
      journalDirectory,
      evidenceFile,
      budgetMs: positiveBudget(budget),
    });
  } catch (cause) {
    throw new AcceptanceError('CONFIG_INVALID', { cause });
  }
}

function sameAdmission(
  a: ImageAdmissionReceipt,
  b: ImageAdmissionReceipt,
): boolean {
  return (
    a.request.invocation === b.request.invocation &&
    a.request.ordinal === b.request.ordinal &&
    a.request.requestId === b.request.requestId &&
    a.project === b.project &&
    a.owner === b.owner &&
    a.producer === b.producer &&
    a.imageId === b.imageId &&
    a.mimeType === b.mimeType &&
    a.width === b.width &&
    a.height === b.height &&
    a.byteLength === b.byteLength &&
    a.sha256 === b.sha256 &&
    a.admittedAt === b.admittedAt
  );
}
/** A status/answer alone cannot establish image delivery or the actual model route.
 * Retained descriptors and route records prove bounded public observations, not
 * an inaccessible next-request pixel manifest. The changing pixel challenge is
 * the behavioral check that complements those records.
 */
export function acceptanceRoundPassed(
  round: AcceptanceRound,
  model: string,
): boolean {
  const routes = [...round.wireModels, ...round.procModels];
  return (
    /^\d{8}$/u.test(round.expected) &&
    round.answered &&
    round.ending === 'ANSWERED' &&
    round.answer.trim() === round.expected &&
    round.jobLinked &&
    round.trajectoryComplete &&
    round.images.length > 0 &&
    round.procComplete &&
    round.procModels.length > 0 &&
    routes.every((route) => route === model) &&
    round.images.every((image) => {
      const receipt = image.admission;
      return (
        image.state === 'COMPLETED' &&
        image.use === 'OBSERVATION' &&
        image.trajectoryCallLinked &&
        image.invocation === image.retainedInvocation &&
        receipt.request.invocation === image.invocation &&
        image.project === round.project &&
        image.provider === round.provider &&
        image.account === round.account &&
        receipt.project === round.project &&
        receipt.owner === round.account &&
        receipt.producer === round.account &&
        image.artifactState === 'COMMITTED' &&
        image.artifactReceipt !== null &&
        sameAdmission(receipt, image.artifactReceipt) &&
        /^[0-9a-f]{64}$/u.test(receipt.sha256) &&
        receipt.sha256 === image.capturedSha256 &&
        receipt.imageId === 'img_' + receipt.sha256.slice(0, 32) &&
        receipt.mimeType === 'image/png' &&
        image.capturedMimeType === receipt.mimeType &&
        receipt.width === image.capturedWidth &&
        receipt.height === image.capturedHeight &&
        receipt.byteLength === image.capturedByteLength &&
        receipt.width > 0 &&
        receipt.height > 0 &&
        receipt.byteLength > 0
      );
    })
  );
}

export interface AcceptanceDependencies {
  readonly registry?: ConnectionRegistry;
  readonly authenticate?: (
    connection: NamedConnection,
    signal: AbortSignal,
  ) => Promise<Session>;
  readonly clock?: Clock;
}
async function bounded<T>(
  work: () => Promise<T>,
  deadline: OperationDeadline,
): Promise<T> {
  deadline.requireActive();
  let detach = () => {};
  const expiry = new Promise<never>((_resolve, reject) => {
    const expired = () => reject(new DeadlineExceeded());
    deadline.signal.addEventListener('abort', expired, { once: true });
    detach = () => deadline.signal.removeEventListener('abort', expired);
    if (deadline.signal.aborted) expired();
  });
  try {
    const raced = Promise.race([
      Promise.resolve().then(() => {
        deadline.requireActive();
        return work();
      }),
      expiry,
    ]);
    if (deadline.remainingMs() <= 0) deadline.cancel();
    const value = await raced;
    deadline.requireActive();
    return value;
  } catch (error) {
    if (deadline.remainingMs() <= 0) deadline.cancel();
    throw error;
  } finally {
    detach();
  }
}
async function checkpoint(
  file: string,
  evidence: AcceptanceEvidence,
): Promise<void> {
  await atomicPrivateWrite(file, evidence);
  const parent = await open(dirname(file), 'r');
  try {
    await parent.sync();
  } finally {
    await parent.close();
  }
}

/**
 * Physical file writes are uncancellable: serialize immutable snapshots even
 * after a caller's wait expires. A later failure checkpoint waits for the old
 * writer within its own budget; it never races a stale rename. CHECKS_PASSED in
 * a file is provisional: only a successful runAcceptance return / CLI zero exit
 * alongside that exact evidence establishes completed acceptance.
 */
export class AcceptanceCheckpoints {
  private physical: Promise<void> = Promise.resolve();
  constructor(
    private readonly file: string,
    private readonly write: (
      file: string,
      snapshot: AcceptanceEvidence,
    ) => Promise<void> = checkpoint,
  ) {}
  async save(
    record: AcceptanceEvidence,
    deadline: OperationDeadline,
  ): Promise<void> {
    deadline.requireActive();
    const snapshot = structuredClone(record),
      previous = this.physical;
    const writing = previous
      .catch(() => {})
      .then(async () => {
        deadline.requireActive();
        await this.write(this.file, snapshot);
      });
    this.physical = writing;
    // Queue ownership survives an expired caller that never adopts this promise.
    // Its failure is already represented by that caller's expired checkpoint;
    // later saves still await this exact physical promise before admission.
    void writing.catch(() => {});
    await bounded(() => writing, deadline);
  }
}

interface PixelFixture {
  readonly server: Server;
  readonly url: string;
  next(): { readonly expected: string; readonly generation: number };
  waitPaint(generation: number, deadline: OperationDeadline): Promise<void>;
}
/** Acceptance-only loopback fixture, never a deployment origin or computer-control fallback. */
async function pixelFixture(
  deadline: OperationDeadline,
): Promise<PixelFixture> {
  let generation = 0,
    expected = '',
    painted = -1;
  const server = createServer((request, reply) => {
    reply.setHeader('Cache-Control', 'no-store');
    if (request.url === '/challenge') {
      reply.setHeader('Content-Type', 'application/json');
      reply.end(JSON.stringify({ generation, digits: expected }));
      return;
    }
    if (
      request.method === 'POST' &&
      request.url?.startsWith('/painted?generation=')
    ) {
      const value = request.url.slice('/painted?generation='.length);
      if (/^\d{1,3}$/u.test(value)) painted = Math.max(painted, Number(value));
      reply.writeHead(204);
      reply.end();
      return;
    }
    reply.setHeader('Content-Type', 'text/html; charset=utf-8');
    reply.end(
      '<!doctype html><canvas width="640" height="240"></canvas><script>const c=document.querySelector("canvas").getContext("2d");let last=-1;async function draw(){const v=await(await fetch("/challenge",{cache:"no-store"})).json();if(v.generation!==last){last=v.generation;c.fillStyle="white";c.fillRect(0,0,640,240);c.fillStyle="black";c.font="72px monospace";c.fillText(v.digits,100,145);requestAnimationFrame(()=>requestAnimationFrame(()=>fetch("/painted?generation="+v.generation,{method:"POST"})))}}draw();setInterval(draw,100)</script>',
    );
  });
  try {
    await bounded(
      () =>
        new Promise<void>((resolve, reject) => {
          server.once('error', reject);
          server.listen(0, '127.0.0.1', resolve);
        }),
      deadline,
    );
  } catch (error) {
    server.close();
    throw error;
  }
  const address = server.address();
  if (address === null || typeof address === 'string')
    throw new Error('Fixture did not bind');
  return {
    server,
    url: `http://127.0.0.1:${address.port}/`,
    next() {
      let next: string;
      do {
        next = randomInt(10_000_000, 100_000_000).toString();
      } while (next === expected);
      expected = next;
      generation++;
      return { expected, generation };
    },
    async waitPaint(value, remaining) {
      while (painted < value) {
        remaining.requireActive();
        await pause(20, undefined, { signal: remaining.signal });
      }
    },
  };
}

function definition(name: string, model: string): string {
  return `---\nname: ${name}\ndescription: Controlled pixel-only computer vision acceptance fixture.\nmodel: ${JSON.stringify(model)}\nvision: true\ndynamic: true\ntools: [relay_tool_read]\ncalls: []\nscopes: []\nsampling: precise\nmax-turns: 8\nmax-model-calls: 8\n---\nUse only the granted computer observation tools. Open the configured fixture target, observe its viewport, and return exactly the eight digits visibly rendered in the image. Do not guess or infer from metadata. Do not request any other capability.\n`;
}

/**
 * Reproducible SDK-only live check. Tests inject only login/registry boundaries.
 * Persisted CHECKS_PASSED is provisional: acceptance requires this function to
 * return successfully (or CLI zero exit) alongside the matching full evidence.
 */
export async function runAcceptance(
  value: AcceptanceConfig,
  dependencies: AcceptanceDependencies = {},
): Promise<AcceptanceEvidence> {
  const config = decodeAcceptanceConfig(value),
    clock = dependencies.clock ?? systemClock;
  const overall = new OperationDeadline(config.budgetMs, clock),
    lifetime = new AbortController();
  let session: Session | undefined, evidence: AcceptanceEvidence | undefined;
  let cleanupOwned: (() => Promise<void>) | undefined;
  const checkpoints = new AcceptanceCheckpoints(config.evidenceFile);
  const save = (record: AcceptanceEvidence, deadline = overall) =>
    checkpoints.save(record, deadline);

  try {
    const selected = await bounded(
      () =>
        resolveConnection(
          dependencies.registry ?? new Connections(),
          { name: config.connectionName },
          {},
        ),
      overall,
    );
    if (selected === undefined) throw new AcceptanceError('CONFIG_INVALID');
    const authBudget = overall.child(Math.min(30_000, config.budgetMs));
    let acceptingSession = true;
    try {
      const signal = AbortSignal.any([lifetime.signal, authBudget.signal]);
      const authenticateSaved =
        dependencies.authenticate ??
        ((connection: NamedConnection, authSignal: AbortSignal) =>
          authenticate(connection.server, '', '', authSignal, () => {}, {
            credentials: new Credentials(
              connection.server,
              join(userConfigDirectory(), 'credentials'),
              authSignal,
              connection.account,
            ),
          }));
      session = await bounded(async () => {
        const opened = await authenticateSaved(selected, signal);
        // A noncooperative authentication may return after its caller timed out.
        // Retain ownership of that late socket and close it instead of leaking it.
        if (
          !acceptingSession ||
          authBudget.remainingMs() <= 0 ||
          signal.aborted
        ) {
          opened.close();
          throw new DeadlineExceeded();
        }
        return opened;
      }, authBudget);
    } finally {
      acceptingSession = false;
      authBudget.dispose();
    }
    const authenticated = session;
    const sdk = computerProviderConnection(checkedTransport(authenticated));
    const ask = async <K extends keyof Payloads>(
      type: K,
      payload: Payloads[K],
      parent: OperationDeadline = overall,
    ): Promise<Replies[K]> => {
      const step = parent.child(2000);
      try {
        return await bounded(
          async () =>
            requirePayload(
              await sdk.request(type, payload, {
                signal: step.signal,
                timeoutMs: Math.max(1, Math.floor(step.remainingMs())),
              }),
            ),
          step,
        );
      } finally {
        step.dispose();
      }
    };
    const identity = await ask('admin.status', {});
    if (identity.handle !== selected.account)
      throw new AcceptanceError('IDENTITY_MISMATCH');
    // No fixture state changes precede authenticated same-socket identity proof.
    await bounded(
      () => privateDirectory(dirname(config.evidenceFile)),
      overall,
    );
    await bounded(async () => {
      const exclusive = await open(config.evidenceFile, 'wx', 0o600);
      await exclusive.close();
    }, overall);
    evidence = {
      version: 1,
      connectionName: config.connectionName,
      selectedAccount: selected.account,
      requestedModel: config.model,
      startedAt: new Date(clock.wallNow()).toISOString(),
      finishedAt: null,
      state: 'RUNNING',
      project: null,
      agent: null,
      scope: null,
      provider: null,
      currentJob: null,
      currentConversation: null,
      stage: 'IDENTITY_VERIFIED',
      rounds: [],
      catalogueIntents: [],
      failures: [],
      cleanup: {
        stopped: false,
        drained: false,
        browserClosed: false,
        scopeDisconnected: false,
        catalogueWithdrawn: false,
        fixtureClosed: false,
        workspaceRetained: true,
      },
    };
    const record = evidence;
    await save(record);
    await bounded(async () => {
      try {
        await lstat(config.workDirectory);
        throw new AcceptanceError('WORKSPACE_NOT_FRESH');
      } catch (error) {
        if (!(
          error instanceof Error &&
          'code' in error &&
          error.code === 'ENOENT'
        ))
          throw error;
      }
      overall.requireActive();
      await mkdir(config.workDirectory, { mode: 0o700 });
      if ((await readdir(config.workDirectory)).length !== 0)
        throw new AcceptanceError('WORKSPACE_NOT_FRESH');
    }, overall);
    record.stage = 'ROOT_PENDING';
    await save(record);
    const rootBudget = overall.child(2000);
    let rooted;
    try {
      rooted = await bounded(
        () => authenticated.root(config.project, config.workDirectory),
        rootBudget,
      );
    } finally {
      rootBudget.dispose();
    }
    record.project = rooted.current.project;
    const project = rooted.current.project,
      unique = randomUUID().replaceAll('-', '').slice(0, 12);
    const agent = 'vision_accept_' + unique,
      scopeName = 'vision-accept-' + unique;
    record.agent = agent;
    record.scope = scopeName;
    record.stage = 'DEFINE_PENDING';
    await save(record);
    const defined = await ask('agent.define', {
      project,
      name: agent,
      text: definition(agent, config.model),
      overwrite: false,
    });
    if (
      defined.restartRequired ||
      !defined.agent.served ||
      !defined.agent.dynamic
    )
      throw new AcceptanceError('FAILED');
    let fixture: PixelFixture | undefined = undefined,
      browser: BackendHandle | undefined,
      provider: ComputerProvider | undefined;
    let pump: Promise<void> | undefined,
      pumpSettled = true,
      stopping = false,
      browserAttempted = false,
      pumpFailure: unknown;
    let marker: string | undefined,
      scopeAttempted = false,
      cleanupPromise: Promise<void> | undefined;
    const passes = new ComputerPasses(sdk, 2000);
    const captures: {
      readonly sha256: string;
      readonly mimeType: string;
      readonly width: number;
      readonly height: number;
      readonly byteLength: number;
    }[] = [];
    const metadata = async (
      kind: 'publish' | 'withdraw',
      deadline: OperationDeadline,
    ) => {
      deadline.requireActive();
      if (record.catalogueIntents.some((intent) => intent.state === 'pending'))
        throw new AcceptanceError('UNCERTAIN_EFFECT');
      const intent: MetadataIntent = {
        requestId: randomUUID(),
        kind,
        state: 'pending',
      };
      record.catalogueIntents.push(intent);
      await save(record, deadline);
      deadline.requireActive();
      if (provider === undefined) throw new Error('Provider not initialized');
      if (kind === 'publish')
        await provider.provider.publishCatalog(intent.requestId);
      else await provider.provider.withdrawCatalog(intent.requestId);
      intent.state = 'confirmed';
      await save(record, deadline);
    };
    // One cleanup owner exists before scope/fixture/lock acquisition. It runs
    // inside localLock when acquired and again harmlessly outside on early failure.
    // A second caller shares the same promise/budget; uncertain effects are never retried.
    cleanupOwned = () =>
      (cleanupPromise ??= (async () => {
        stopping = true;
        provider?.stop();
        passes.cancel();
        record.cleanup.stopped = true;
        const cleanup = new OperationDeadline(2000, clock);
        try {
          if (record.currentJob !== null) {
            try {
              await ask('job.cancel', { job: record.currentJob }, cleanup);
            } catch {
              record.failures.push('JOB_CANCEL_UNCERTAIN');
            }
          }
          try {
            if (pump !== undefined)
              await bounded(() => pump ?? Promise.resolve(), cleanup);
            if (provider !== undefined) await provider.drain(cleanup);
            await passes.drain(cleanup);
            record.cleanup.drained = pumpSettled;
          } catch {
            record.failures.push('PROVIDER_DRAIN_UNCERTAIN');
          }
          if (
            provider !== undefined &&
            record.cleanup.drained &&
            !record.catalogueIntents.some(
              (intent) => intent.state === 'pending',
            )
          ) {
            try {
              await passes.run(
                cleanup.child(Math.max(1, Math.floor(cleanup.remainingMs()))),
                () => metadata('withdraw', cleanup),
                () => provider?.stop(),
              );
              record.cleanup.catalogueWithdrawn = true;
            } catch {
              record.failures.push('CATALOGUE_WITHDRAW_UNCERTAIN');
            }
          }
          if (scopeAttempted) {
            try {
              await ask(
                'tool.scope.disconnect',
                { project, scope: scopeName },
                cleanup,
              );
              record.cleanup.scopeDisconnected = true;
            } catch {
              record.failures.push('SCOPE_DISCONNECT_UNCERTAIN');
            }
          }
          if (browser !== undefined) {
            try {
              await browser.close(cleanup);
              record.cleanup.browserClosed = true;
            } catch {
              record.failures.push('BROWSER_CLOSE_UNCERTAIN');
            }
          }
          if (fixture !== undefined) {
            const server = fixture.server;
            server.closeAllConnections();
            const closing = new Promise<void>((resolve, reject) =>
              server.close((error) =>
                error === undefined ? resolve() : reject(error),
              ),
            );
            void closing.catch(() => {}); // Caller may already have exhausted the cleanup budget.
            try {
              await bounded(() => closing, cleanup);
              record.cleanup.fixtureClosed = true;
            } catch {
              record.failures.push('FIXTURE_CLOSE_UNCERTAIN');
            }
          }
          if (
            marker !== undefined &&
            record.cleanup.drained &&
            (!browserAttempted || record.cleanup.browserClosed)
          ) {
            try {
              await bounded(
                () => rm(marker ?? '', { recursive: true }),
                cleanup,
              );
            } catch {
              record.failures.push('OWNERSHIP_RELEASE_UNCERTAIN');
            }
          }
        } finally {
          cleanup.dispose();
        }
      })());
    record.stage = 'SCOPE_CONNECT_PENDING';
    await save(record);
    scopeAttempted = true;
    const scope = await ask('tool.scope.connect', {
      project,
      scope: scopeName,
      provider: scopeName,
      prefix: 'computer_',
      grants: [
        'computer_targets',
        'computer_open',
        'computer_observe',
        'computer_close',
      ],
      agents: [agent],
      leaseSeconds: 300,
    });
    if (
      scope.account !== selected.account ||
      scope.project !== project ||
      scope.sourceProvider !== scopeName
    )
      throw new AcceptanceError('IDENTITY_MISMATCH');
    record.provider = scope.provider;
    const binding = {
      project: scope.project,
      provider: scope.provider,
      account: scope.account,
    };
    const journalIdentity = await bounded(
      () =>
        nodeToolHost.sha256(
          toolDeploymentConfig(binding, computerDeclarations()),
        ),
      overall,
    );
    await bounded(() => privateDirectory(config.journalDirectory), overall);
    fixture = await pixelFixture(overall);
    const canvas = fixture;
    await bounded(
      () =>
        localLock(
          config.journalDirectory,
          journalIdentity,
          async () => {
            try {
              overall.requireActive();
              const active = join(
                config.journalDirectory,
                journalIdentity + '.acceptance-active',
              );
              await bounded(() => mkdir(active, { mode: 0o700 }), overall);
              marker = active;
              browserAttempted = true;
              browser = await createBrowserBackend(
                {
                  executablePath: config.browserExecutable,
                  headless: true,
                  targets: [
                    {
                      targetId: 'fixture',
                      label: 'Controlled canvas fixture',
                      url: canvas.url,
                      viewport: { width: 640, height: 240 },
                    },
                  ],
                },
                overall,
              );
              const backend: ComputerBackend = {
                targets: (deadline) =>
                  browser === undefined
                    ? Promise.reject(new Error('Browser unavailable'))
                    : browser.backend.targets(deadline),
                async capture(request, deadline) {
                  overall.requireActive();
                  if (browser === undefined)
                    throw new Error('Browser unavailable');
                  const image = await browser.backend.capture(
                    request,
                    deadline,
                  );
                  overall.requireActive();
                  captures.push({
                    sha256: createHash('sha256')
                      .update(image.bytes)
                      .digest('hex'),
                    mimeType: image.mimeType,
                    width: image.width,
                    height: image.height,
                    byteLength: image.bytes.byteLength,
                  });
                  return image;
                },
              };
              const journal = await bounded(
                () =>
                  FileToolJournal.open(
                    config.journalDirectory,
                    journalIdentity,
                  ),
                overall,
              );
              provider = await createComputerProvider({
                connection: passes.connection,
                binding,
                journal,
                backends: [backend],
                generation: randomUUID(),
                origin: selected.server,
                currentBearer: () => authenticated.bearer(),
                parentDeadline: () => passes.currentDeadline(),
                clock,
              });
              const local = provider;
              await passes.run(
                overall.child(30_000),
                () => metadata('publish', overall),
                () => local.stop(),
              );
              let renewedAt = clock.monotonicNow();
              pumpSettled = false;
              pump = (async () => {
                while (!stopping) {
                  overall.requireActive();
                  await passes.run(
                    overall.child(30_000),
                    async () => {
                      if (clock.monotonicNow() - renewedAt >= 30_000) {
                        await metadata('publish', overall);
                        renewedAt = clock.monotonicNow();
                      }
                      await local.provider.poll();
                    },
                    () => local.stop(),
                  );
                  if (!stopping)
                    await pause(100, undefined, { signal: overall.signal });
                }
              })()
                .catch((error: unknown) => {
                  pumpFailure = error;
                  local.stop();
                })
                .finally(() => {
                  pumpSettled = true;
                });
              for (let trial = 0; trial < 2; trial++) {
                const challenge = canvas.next();
                await canvas.waitPaint(challenge.generation, overall);
                const before = new Set(
                  (await bounded(() => local.journal.all(), overall)).map(
                    (row) => row.invocationId,
                  ),
                );
                const conversation = await ask('conversation.open', {
                  project,
                  maxTurns: 8,
                  maxModelCalls: 8,
                });
                record.currentConversation = conversation.id;
                record.stage = 'MODEL_SUBMISSION_PENDING';
                await save(record);
                const started = await ask('agent.run', {
                  conversation: conversation.id,
                  agent,
                  task: 'Read the current controlled fixture screenshot through the granted computer tools. Return exactly its eight visible digits and nothing else.',
                  session: authenticated.runSession(),
                });
                record.currentJob = started.id;
                record.stage = 'MODEL_RUNNING';
                await save(record);
                let status: Replies['job.status'];
                for (;;) {
                  overall.requireActive();
                  if (pumpFailure !== undefined)
                    throw new AcceptanceError('FAILED', { cause: pumpFailure });
                  status = await ask('job.status', { job: started.id });
                  if (status.outcome != null) break;
                  if (status.state === 'PAUSED')
                    throw new AcceptanceError('FAILED');
                  await pause(250, undefined, { signal: overall.signal });
                }
                const wireModels: string[] = [],
                  procModels: string[] = [],
                  routeReadFailures: string[] = [];
                const trajectoryCalls = new Set<string>();
                let trajectoryComplete = false,
                  procComplete = false;
                try {
                  const trajectory = await ask('conversation.trajectory', {
                    conversation: conversation.id,
                    tail: true,
                    limit: 100,
                  });
                  trajectoryComplete =
                    trajectory.total <= trajectory.entries.length;
                  for (const entry of trajectory.entries) {
                    if (entry.job != null && entry.job !== started.id)
                      trajectoryComplete = false;
                    if (entry.wireModel !== null)
                      wireModels.push(entry.wireModel);
                    for (const call of entry.toolCalls)
                      trajectoryCalls.add(call.id + '\n' + call.name);
                  }
                } catch {
                  routeReadFailures.push('TRAJECTORY_UNAVAILABLE');
                }
                try {
                  const calls = await ask('proc.calls', {
                    visibility: 'mine',
                    project,
                    conversation: conversation.id,
                    limit: 100,
                  });
                  procComplete = calls.cursor === null;
                  for (const call of calls.calls) {
                    if (
                      call.conversation !== conversation.id ||
                      call.project !== project ||
                      call.agent !== agent ||
                      call.principal !== selected.account
                    )
                      procComplete = false;
                    procModels.push(call.model);
                  }
                } catch {
                  routeReadFailures.push('PROC_CALLS_UNAVAILABLE');
                }
                const images: AcceptanceImage[] = [];
                const newReceipts = (
                  await bounded(() => local.journal.all(), overall)
                ).filter((row) => !before.has(row.invocationId));
                for (const receipt of newReceipts) {
                  const call = decodeToolCall(receipt.request);
                  if (
                    call.tool !== 'computer_observe' ||
                    receipt.result === null
                  )
                    continue;
                  const retained = await ask('tool.invocation.read', {
                    project,
                    invocation: call.invocationId,
                  });
                  for (const admission of receipt.result.admissions ?? []) {
                    const part = retained.content?.parts.find(
                      (part) =>
                        part.kind === 'IMAGE' &&
                        part.imageId === admission.imageId &&
                        part.ordinal === admission.request.ordinal,
                    );
                    const captured = captures.find(
                      (image) => image.sha256 === admission.sha256,
                    );
                    const artifact = await ask(
                      'artifact.admission.status',
                      admission.request,
                    );
                    images.push({
                      invocation: call.invocationId,
                      retainedInvocation: retained.id,
                      call: call.call,
                      project: call.project,
                      provider: call.provider,
                      account: call.account,
                      trajectoryCallLinked: trajectoryCalls.has(
                        call.call + '\n' + call.tool,
                      ),
                      state: retained.state,
                      use: part?.use ?? 'MISSING',
                      admission,
                      capturedSha256: captured?.sha256 ?? '',
                      capturedMimeType: captured?.mimeType ?? '',
                      capturedWidth: captured?.width ?? 0,
                      capturedHeight: captured?.height ?? 0,
                      capturedByteLength: captured?.byteLength ?? 0,
                      artifactState: artifact.state,
                      artifactReceipt: artifact.receipt,
                    });
                  }
                }
                const round: AcceptanceRound = {
                  expected: challenge.expected,
                  answer: status.outcome.text ?? '',
                  answered: status.outcome.answered,
                  ending: status.outcome.ending,
                  job: started.id,
                  conversation: conversation.id,
                  project,
                  account: selected.account,
                  provider: scope.provider,
                  jobLinked:
                    status.id === started.id &&
                    status.conversation === conversation.id &&
                    started.conversation === conversation.id,
                  trajectoryComplete,
                  procComplete,
                  images,
                  wireModels,
                  procModels,
                  routeReadFailures,
                };
                record.rounds.push(round);
                record.stage = 'TRIAL_RECORDED';
                await save(record);
                if (!acceptanceRoundPassed(round, config.model))
                  throw new AcceptanceError('VISION_NOT_PROVEN');
                record.currentJob = null;
                for (const receipt of newReceipts) {
                  const call = decodeToolCall(receipt.request);
                  if (
                    call.tool !== 'computer_open' ||
                    receipt.result?.state !== 'COMPLETED'
                  )
                    continue;
                  const body = object(JSON.parse(receipt.result.text)),
                    handle = object(body['session']);
                  const owner: ComputerOwner = {
                    account: call.account,
                    project: call.project,
                    provider: call.provider,
                    run: call.run,
                  };
                  try {
                    local.sessions.close(
                      owner,
                      decodeCloseArguments({
                        sessionId: handle['sessionId'],
                        generation: handle['generation'],
                      }),
                    );
                  } catch (error) {
                    if (!(
                      error instanceof Error && error.message === 'NO_SESSION'
                    ))
                      throw error;
                  }
                }
              }
              // Terminal evidence is written only after bounded cleanup settles.
            } finally {
              await cleanupOwned?.();
            }
          },
          overall.signal,
        ),
      overall,
    );
    await cleanupOwned();
    record.finishedAt = new Date(clock.wallNow()).toISOString();
    if (record.failures.length > 0 || record.rounds.length !== 2)
      record.state = 'FAILED';
    else record.state = 'CHECKS_PASSED';
    const finalBudget = new OperationDeadline(2000, clock);
    try {
      await save(record, finalBudget);
    } finally {
      finalBudget.dispose();
    }
    if (record.state !== 'CHECKS_PASSED')
      throw new AcceptanceError('VISION_NOT_PROVEN');
    return record;
  } catch (error) {
    await cleanupOwned?.();
    if (evidence !== undefined) {
      evidence.state = 'FAILED';
      evidence.finishedAt = new Date(clock.wallNow()).toISOString();
      evidence.failures.push(acceptanceFailureCode(error));
      const finalBudget = new OperationDeadline(2000, clock);
      try {
        await save(evidence, finalBudget);
      } finally {
        finalBudget.dispose();
      }
    }
    throw error;
  } finally {
    overall.dispose();
    session?.close();
    lifetime.abort();
  }
}

export async function acceptanceMain(args: readonly string[]): Promise<void> {
  if (args.length !== 1) throw new AcceptanceError('CONFIG_INVALID');
  const file = absolutePrivatePath(args[0]);
  const loading = new OperationDeadline(2000);
  let config: AcceptanceConfig;
  try {
    config = await bounded(async () => {
      await privateFile(file);
      loading.requireActive();
      const handle = await open(file, 'r');
      try {
        loading.requireActive();
        const bytes = new Uint8Array(65_537),
          result = await handle.read(bytes, 0, bytes.length, 0);
        loading.requireActive();
        if (result.bytesRead > 65_536)
          throw new AcceptanceError('CONFIG_INVALID');
        const source = new TextDecoder('utf-8', { fatal: true }).decode(
          bytes.subarray(0, result.bytesRead),
        );
        return decodeAcceptanceConfig(JSON.parse(source));
      } finally {
        await handle.close();
      }
    }, loading);
  } finally {
    loading.dispose();
  }
  await runAcceptance(config);
}

const entry = process.argv[1];
if (entry !== undefined && import.meta.url === pathToFileURL(entry).href) {
  try {
    await acceptanceMain(process.argv.slice(2));
    process.stdout.write(
      'computer-control acceptance: PASSED. Private evidence retained.\n',
    );
  } catch (error) {
    process.stderr.write(
      `computer-control acceptance: ${acceptanceFailureCode(error)}. No uncertain operation was replayed.\n`,
    );
    process.exitCode = 1;
  }
}
