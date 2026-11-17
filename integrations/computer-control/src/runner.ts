import { createHash, randomUUID } from 'node:crypto';
import { mkdir, open, rmdir } from 'node:fs/promises';
import { join } from 'node:path';
import { requirePayload } from 'plowshare-client-ts';
import type {
  Payloads,
  Plowshare,
  Reply,
  ToolDeclaration,
  ToolJournal,
} from 'plowshare-client-ts';
import type {
  RequestOptions,
  RequestSignal,
} from 'plowshare-client-ts/binding/acting';
import { ConnectionFault } from 'plowshare-client-ts/binding/connection';
import {
  FileToolJournal,
  toolDeploymentConfig,
} from 'plowshare-client-node/tools';
import {
  atomicPrivateWrite,
  localLock,
  privateDirectory,
  privateFile,
} from 'plowshare-client-node/connections';
import type { Clock } from './deadline.ts';
import {
  DeadlineExceeded,
  OperationDeadline,
  positiveBudget,
  systemClock,
} from './deadline.ts';
import { absolutePrivatePath, decodeComputerHostConfig } from './config.ts';
import type { ComputerHostConfig } from './config.ts';
import { computerDeclarations, createComputerProvider } from './provider.ts';
import type { ComputerProvider } from './provider.ts';
import { createBrowserBackend } from './backends/browser.ts';
import { createBrowserControlBackend } from './backends/browser-control-backend.ts';
import type { BrowserControlBackend } from './browser-control.ts';
import { browserComposition } from './browser-composition.ts';
import {
  createMacOSBackend,
  createCoordinatedMacOSBackend,
} from './backends/macos.ts';
import { createMacOSControlBackend } from './backends/macos-control.ts';
import type { NativeControlBackend } from './native-control.ts';
import type { NativeArmingBackend } from './native-arming.ts';
import { NativeArmingRequests } from './native-arming-requests.ts';
import {
  nativeComposition,
  nativeArmingComposition,
  requireDistinctControlPorts,
} from './native-composition.ts';
import type { BackendHandle } from './backends/types.ts';
import type { HostDiagnostic, HostDiagnostics } from './host-diagnostics.ts';
import {
  CatalogueLeaseExpired,
  HostCatalogueLease,
  waitForHostPoll,
  withHostAdmission,
} from './host-lifecycle.ts';

export interface CatalogueIntent {
  readonly version: 1;
  readonly identity: string;
  readonly requestId: string;
  readonly kind: 'publish' | 'withdraw';
  readonly state: 'pending' | 'confirmed' | 'not-submitted';
}
export interface CatalogueIntents {
  load(): Promise<CatalogueIntent | null>;
  save(intent: CatalogueIntent): Promise<void>;
}

function decodeIntent(value: unknown, identity: string): CatalogueIntent {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid catalogue intent');
  const row = value as Readonly<Record<string, unknown>>;
  if (
    Object.keys(row).some(
      (key) =>
        !['version', 'identity', 'requestId', 'kind', 'state'].includes(key),
    ) ||
    row['version'] !== 1 ||
    row['identity'] !== identity ||
    typeof row['requestId'] !== 'string' ||
    !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/u.test(row['requestId']) ||
    (row['kind'] !== 'publish' && row['kind'] !== 'withdraw') ||
    (row['state'] !== 'pending' &&
      row['state'] !== 'confirmed' &&
      row['state'] !== 'not-submitted')
  )
    throw new Error('Invalid catalogue intent identity or state');
  return Object.freeze({
    version: 1,
    identity,
    requestId: row['requestId'],
    kind: row['kind'],
    state: row['state'],
  });
}

/** One unresolved metadata mutation survives restart; it is never replaced merely to renew a lease. */
export class FileCatalogueIntents implements CatalogueIntents {
  constructor(
    private readonly directory: string,
    private readonly identity: string,
  ) {
    absolutePrivatePath(directory);
    if (!/^[0-9a-f]{64}$/u.test(identity))
      throw new Error('Invalid catalogue journal identity');
  }
  async load(): Promise<CatalogueIntent | null> {
    const file = join(this.directory, this.identity + '.catalogue.json');
    await privateFile(file);
    try {
      const handle = await open(file, 'r');
      try {
        const buffer = new Uint8Array(4097);
        const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
        if (bytesRead > 4096)
          throw new Error('Catalogue metadata exceeds its 4096-byte bound');
        return decodeIntent(
          JSON.parse(
            new TextDecoder('utf-8', { fatal: true }).decode(
              buffer.subarray(0, bytesRead),
            ),
          ),
          this.identity,
        );
      } finally {
        await handle.close();
      }
    } catch (error) {
      if (error instanceof Error && 'code' in error && error.code === 'ENOENT')
        return null;
      throw error;
    }
  }
  async save(value: CatalogueIntent): Promise<void> {
    const intent = decodeIntent(value, this.identity),
      previous = await this.load();
    if (
      previous?.state === 'pending' &&
      (previous.requestId !== intent.requestId || previous.kind !== intent.kind)
    )
      throw new Error('Unresolved catalogue intent cannot be replaced');
    if (previous === null && intent.state !== 'pending')
      throw new Error('A new catalogue intent must be pending');
    if (
      previous !== null &&
      previous.requestId === intent.requestId &&
      (previous.kind !== intent.kind ||
        (previous.state !== 'pending' && previous.state !== intent.state))
    )
      throw new Error('Resolved catalogue evidence is immutable');
    if (
      previous !== null &&
      previous.requestId !== intent.requestId &&
      intent.state !== 'pending'
    )
      throw new Error(
        'A fresh metadata identity requires a new pending intent',
      );
    await atomicPrivateWrite(
      join(this.directory, this.identity + '.catalogue.json'),
      intent,
    );
    const directory = await open(this.directory, 'r');
    try {
      await directory.sync();
    } finally {
      await directory.close();
    }
  }
}

function signalTogether(
  first: RequestSignal,
  second: RequestSignal | undefined,
): RequestSignal {
  if (second === undefined) return first;
  return {
    get aborted() {
      return first.aborted || second.aborted;
    },
    addEventListener(type, listener, options) {
      first.addEventListener(type, listener, options);
      second.addEventListener(type, listener, options);
    },
    removeEventListener(type, listener) {
      first.removeEventListener(type, listener);
      second.removeEventListener(type, listener);
    },
  };
}

async function wait<T>(
  work: Promise<T>,
  deadline: OperationDeadline,
  expired: () => void,
): Promise<T> {
  let detach = () => {};
  const cancellation = new Promise<never>((_resolve, reject) => {
    const abort = () => {
      expired();
      reject(new DeadlineExceeded());
    };
    deadline.signal.addEventListener('abort', abort, { once: true });
    detach = () => deadline.signal.removeEventListener('abort', abort);
    if (deadline.signal.aborted) abort();
  });
  try {
    const raced = Promise.race([work, cancellation]);
    if (deadline.remainingMs() <= 0) deadline.cancel();
    return await raced;
  } finally {
    detach();
  }
}

/** Serialized SDK passes share queueing/transmit/reply cancellation; physical passes stay owned. */
export class ComputerPasses {
  private active: OperationDeadline | undefined;
  private physical: Promise<unknown> | undefined;
  readonly connection: Pick<Plowshare, 'request'>;
  constructor(
    connection: Pick<Plowshare, 'request'>,
    private readonly requestBudgetMs: number,
  ) {
    positiveBudget(requestBudgetMs);
    this.connection = {
      request: async <K extends keyof Payloads>(
        type: K,
        payload: Payloads[K],
        options?: RequestOptions,
      ): Promise<Reply<K>> => {
        const deadline = this.active;
        if (deadline === undefined || deadline.remainingMs() <= 0)
          throw new ConnectionFault(
            'NOT_SUBMITTED',
            'Computer pass expired before this request was submitted',
          );
        const result = await connection.request(type, payload, {
          ...options,
          signal: signalTogether(deadline.signal, options?.signal),
          timeoutMs: Math.max(
            1,
            Math.min(
              this.requestBudgetMs,
              Math.floor(deadline.remainingMs()),
              options?.timeoutMs ?? this.requestBudgetMs,
            ),
          ),
        });
        if (deadline.remainingMs() <= 0) {
          deadline.cancel();
          throw new ConnectionFault(
            'RESPONSE_LOST',
            'Computer pass expired after possible submission; reconcile original effects',
          );
        }
        return result;
      },
    };
  }
  cancel(): void {
    this.active?.cancel();
  }
  currentDeadline(): OperationDeadline | undefined {
    return this.active;
  }
  async run<T>(
    deadline: OperationDeadline,
    operation: () => Promise<T>,
    expired: () => void,
  ): Promise<T> {
    if (this.active !== undefined)
      throw new Error('An earlier computer pass is still physically active');
    deadline.requireActive();
    this.active = deadline;
    const work = Promise.resolve()
      .then(async () => {
        deadline.requireActive();
        const result = await operation();
        deadline.requireActive();
        return result;
      })
      .finally(() => {
        this.active = undefined;
        this.physical = undefined;
        deadline.dispose();
      });
    this.physical = work;
    return wait(work, deadline, expired);
  }
  async drain(deadline: OperationDeadline): Promise<void> {
    if (this.physical !== undefined) {
      // Its original receipt/metadata intent owns any failure; drain establishes settlement only.
      await wait(
        this.physical.then(
          () => undefined,
          () => undefined,
        ),
        deadline,
        () => {},
      );
    }
  }
}

/** Cleanup timeout never releases ownership of an unsettled original promise. */
export class ComputerCleanup {
  private readonly physical = new Set<Promise<void>>();

  pending(): number {
    return this.physical.size;
  }

  async run(
    deadline: OperationDeadline,
    operation: () => Promise<void>,
  ): Promise<void> {
    deadline.requireActive();
    const work = Promise.resolve().then(async () => {
      deadline.requireActive();
      await operation();
      deadline.requireActive();
    });
    this.physical.add(work);
    const settled = work.then(
      () => {
        this.physical.delete(work);
      },
      (error: unknown) => {
        this.physical.delete(work);
        throw error;
      },
    );
    await wait(settled, deadline, () => {});
  }
}

export class CatalogueRecoveryRequired extends Error {
  constructor() {
    super(
      'Original catalogue or tool-result evidence requires reconciliation; no publication was replayed',
    );
    this.name = 'CatalogueRecoveryRequired';
  }
}

export interface ComputerHostOptions {
  readonly config: ComputerHostConfig;
  readonly connection: Pick<Plowshare, 'request'>;
  readonly currentBearer: () => Promise<string>;
  readonly signal: AbortSignal;
  readonly backends?: readonly BackendHandle[];
  readonly browserControls?: readonly BrowserControlBackend[];
  readonly nativeControls?: readonly NativeControlBackend[];
  readonly nativeArmingControls?: readonly NativeArmingBackend[];
  readonly journal?: ToolJournal;
  readonly intents?: CatalogueIntents;
  readonly clock?: Clock;
  /** Earlier startup unit already consumed by authentication; never reset at the host boundary. */
  readonly startupDeadline?: OperationDeadline;
  readonly maxPasses?: number;
  readonly onReady?: () => void;
  readonly diagnostics?: HostDiagnostics;
}

function position(value: string): bigint {
  if (!/^(?:0|[1-9][0-9]{0,18})$/u.test(value))
    throw new CatalogueRecoveryRequired();
  const parsed = BigInt(value);
  if (parsed > 9_223_372_036_854_775_807n)
    throw new CatalogueRecoveryRequired();
  return parsed;
}

async function reconcileCatalogue(
  connection: Pick<Plowshare, 'request'>,
  intent: CatalogueIntent,
  config: ComputerHostConfig,
  declarations: readonly ToolDeclaration[],
  store: CatalogueIntents,
  deadline: OperationDeadline,
): Promise<void> {
  const expected = JSON.stringify(
    intent.kind === 'publish'
      ? { version: 'plowshare-tool-catalog/3', tools: declarations }
      : { version: 'plowshare-tool-catalog/1', tools: [] },
  );
  const publisher =
    'sdk:' + createHash('sha256').update(config.binding.account).digest('hex');
  let after = '0';
  let generation: string | null = null;
  const seenEvents = new Set<string>();
  for (let pageNumber = 0; pageNumber < 100; pageNumber++) {
    deadline.requireActive();
    const page = requirePayload(
      await connection.request('relay.log', {
        project: config.binding.project,
        system: false,
        topic: `tool.${config.binding.provider}.catalog`,
        after,
        limit: 100,
      }),
    );
    deadline.requireActive();
    const topic = `tool.${config.binding.provider}.catalog`;
    const currentGeneration = page.topic.generation ?? null;
    if (
      page.events.length > 100 ||
      page.scope.project !== config.binding.project ||
      page.scope.system ||
      page.topic.name !== topic ||
      page.after !== after ||
      (pageNumber > 0 && currentGeneration !== generation)
    )
      throw new CatalogueRecoveryRequired();
    generation = currentGeneration;
    let previous = position(after);
    for (const event of page.events) {
      if (seenEvents.has(event.eventId)) throw new CatalogueRecoveryRequired();
      seenEvents.add(event.eventId);
      const current = position(event.position);
      if (current <= previous || current > position(page.topic.through))
        throw new CatalogueRecoveryRequired();
      previous = current;
    }
    const last = page.events.at(-1);
    if (last !== undefined && page.next !== last.position)
      throw new CatalogueRecoveryRequired();
    if (position(page.next) < position(after))
      throw new CatalogueRecoveryRequired();
    const event = page.events.find(
      (event) => event.eventId === intent.requestId,
    );
    if (event !== undefined) {
      if (
        event.publisher !== publisher ||
        event.payload.kind !== 'TEXT' ||
        event.payload.text !== expected ||
        event.correlationId !== null ||
        event.causationId !== null
      )
        throw new CatalogueRecoveryRequired();
      deadline.requireActive();
      await store.save({ ...intent, state: 'confirmed' });
      deadline.requireActive();
      return;
    }
    // A visible exact UUID is positive evidence even if older unrelated records
    // expired. Missing/gapped history is never non-submission or resend authority.
    if (last === undefined) break;
    after = last.position;
  }
  throw new CatalogueRecoveryRequired();
}

async function catalogue(
  provider: ComputerProvider,
  store: CatalogueIntents,
  kind: CatalogueIntent['kind'],
  deadline: OperationDeadline,
): Promise<void> {
  deadline.requireActive();
  const intent: CatalogueIntent = {
    version: 1,
    identity: provider.journalIdentity,
    requestId: randomUUID(),
    kind,
    state: 'pending',
  };
  await store.save(intent);
  deadline.requireActive();
  try {
    if (kind === 'publish')
      await provider.provider.publishCatalog(intent.requestId);
    else await provider.provider.withdrawCatalog(intent.requestId);
  } catch (error) {
    if (error instanceof ConnectionFault && error.delivery === 'NOT_SUBMITTED')
      await store.save({ ...intent, state: 'not-submitted' });
    throw error;
  }
  await store.save({ ...intent, state: 'confirmed' });
}

/**
 * Reusable foreground/embedded host lifecycle over the caller's existing SDK connection.
 * Supplied handles remain caller-owned if admission or a foreign marker refuses
 * startup. Ownership transfers after acquiring this host's marker while the
 * original startup deadline is active. Uncertain cleanup retains its fence.
 */
export async function runComputerHost(
  options: ComputerHostOptions,
): Promise<void> {
  const config = decodeComputerHostConfig(options.config),
    clock = options.clock ?? systemClock;
  if (
    options.diagnostics !== undefined &&
    (typeof options.diagnostics.emit !== 'function' ||
      typeof options.diagnostics.unavailable !== 'boolean')
  )
    throw new Error('Invalid computer host diagnostics capability');
  if (
    options.browserControls !== undefined &&
    (options.backends === undefined || config.browserControl === undefined)
  )
    throw new Error(
      'Injected browser controls require matching explicit backend handles and controlled configuration',
    );
  if (
    options.backends !== undefined &&
    config.browserControl !== undefined &&
    options.browserControls === undefined
  )
    throw new Error(
      'Controlled host injection requires its exact browser control backend capabilities',
    );
  if (
    options.nativeControls !== undefined &&
    (options.backends === undefined || config.macosControl === undefined)
  )
    throw new Error(
      'Injected native controls require matching explicit backend handles and controlled configuration',
    );
  if (
    options.backends !== undefined &&
    config.macosControl !== undefined &&
    options.nativeControls === undefined
  )
    throw new Error(
      'Controlled host injection requires its exact native control backend capabilities',
    );
  if (
    options.maxPasses !== undefined &&
    (!Number.isSafeInteger(options.maxPasses) ||
      options.maxPasses < 1 ||
      options.maxPasses > 1_000_000)
  )
    throw new Error('Invalid finite computer host pass count');
  const browserTools = config.browserControl?.control.tools ?? [];
  const nativeTools = config.macosControl?.control.tools ?? [];
  const injectedHandles =
    options.backends === undefined
      ? undefined
      : Object.freeze([...options.backends]);
  const injectedControls =
    injectedHandles === undefined
      ? undefined
      : browserComposition(
          injectedHandles.map((handle) => handle.backend),
          options.browserControls === undefined ? [] : options.browserControls,
          browserTools,
        ).controls;
  const injectedNativeControls =
    injectedHandles === undefined
      ? undefined
      : nativeComposition(
          injectedHandles.map((handle) => handle.backend),
          options.nativeControls === undefined ? [] : options.nativeControls,
          nativeTools,
        ).controls;
  requireDistinctControlPorts(
    injectedControls ?? [],
    injectedNativeControls ?? [],
  );
  const injectedArming = nativeArmingComposition(
    injectedNativeControls ?? [],
    options.nativeArmingControls ?? [],
  );
  if (
    injectedArming.some(
      (backend) => !injectedNativeControls?.includes(backend),
    ) ||
    new Set(injectedArming).size !== injectedArming.length ||
    (config.nativeArming !== undefined &&
      injectedHandles !== undefined &&
      injectedArming.length !== injectedNativeControls?.length)
  )
    throw new Error(
      'Injected arming requires the exact configured native backend capabilities',
    );
  const declarations = computerDeclarations(
    config.prefix,
    {
      controlBudgetMs: config.budgets.controlMs,
      observeBudgetMs: config.budgets.observeMs,
    },
    browserTools,
    nativeTools,
  );
  const identity = createHash('sha256')
    .update(toolDeploymentConfig(config.binding, declarations))
    .digest('hex');
  const initialStartup =
    options.startupDeadline?.child(config.budgets.startupMs) ??
    new OperationDeadline(config.budgets.startupMs, clock);
  const admission = initialStartup.child(config.budgets.controlMs);
  try {
    await withHostAdmission(admission, options.signal, async (admitted) => {
      await privateDirectory(config.journalDirectory);
      admission.requireActive();
      await localLock(
        config.journalDirectory,
        identity,
        async () => {
          admission.requireActive();
          options.signal.throwIfAborted();
          admitted();
          const activeMarker = join(
            config.journalDirectory,
            identity + '.active',
          );
          const handles: BackendHandle[] = [...(injectedHandles ?? [])];
          const controls: BrowserControlBackend[] = [
            ...(injectedControls ?? []),
          ];
          const nativeControls: NativeControlBackend[] = [
            ...(injectedNativeControls ?? []),
          ];
          const armingControls: NativeArmingBackend[] = [...injectedArming];
          const passes = new ComputerPasses(
            options.connection,
            config.budgets.controlMs,
          );
          const resources = new ComputerCleanup();
          type CloseOutcome =
            | { readonly kind: 'QUIET' }
            | { readonly kind: 'FAILED'; readonly error: unknown };
          const closing = new Map<BackendHandle, Promise<CloseOutcome>>();
          const closeOwned = (
            handle: BackendHandle,
            deadline: OperationDeadline,
          ): Promise<CloseOutcome> => {
            const existing = closing.get(handle);
            if (existing !== undefined) return existing;
            const result = resources
              .run(deadline, () => handle.close(deadline))
              .then(
                (): CloseOutcome => ({ kind: 'QUIET' }),
                (error: unknown): CloseOutcome => ({ kind: 'FAILED', error }),
              );
            closing.set(handle, result);
            return result;
          };
          let cleanupDeadline: OperationDeadline | undefined;
          let markerOwned = false;
          let handlesOwned = false;
          const retainHandle = async (
            handle: BackendHandle,
            deadline: OperationDeadline,
          ) => {
            handles.push(handle);
            if (cleanupDeadline !== undefined) {
              const result = await closeOwned(handle, cleanupDeadline);
              if (result.kind === 'FAILED') throw result.error;
            }
            deadline.requireActive();
          };
          let provider: ComputerProvider | undefined,
            published = false;
          let originalFailure: Error | undefined;
          let cleanupFailure: AggregateError | undefined;
          let startupDeadline: OperationDeadline | undefined;
          let lease: HostCatalogueLease | undefined;
          let leaseFailure: CatalogueLeaseExpired | undefined;
          let diagnosticFailure: Error | undefined;
          const lifetime = new AbortController();
          const stop = () => {
            lifetime.abort();
            lease?.stop();
            provider?.stop();
            startupDeadline?.cancel();
            passes.cancel();
          };
          const report = (event: HostDiagnostic) => {
            try {
              options.diagnostics?.emit(event);
            } catch (cause) {
              // A custom sink violating its nonthrowing contract may stop intake,
              // but must never escape a timer or bypass physical cleanup.
              if (diagnosticFailure === undefined) {
                diagnosticFailure = new Error(
                  'Computer host diagnostic sink failed',
                  { cause },
                );
                stop();
              }
            }
          };
          options.signal.addEventListener('abort', stop, { once: true });
          try {
            report({ stage: 'startup', code: 'STARTING' });
            const startup = initialStartup.child(config.budgets.startupMs);
            startupDeadline = startup;
            try {
              await passes.run(
                startup,
                async () => {
                  options.signal.throwIfAborted();
                  lifetime.signal.throwIfAborted();
                  startup.requireActive();
                  try {
                    await mkdir(activeMarker, { mode: 0o700 });
                    markerOwned = true;
                  } catch (cause) {
                    throw new Error(
                      'A prior computer host remains active or fenced; inspect it before recovery',
                      { cause },
                    );
                  }
                  startup.requireActive();
                  handlesOwned = true;
                  const markerParent = await open(config.journalDirectory, 'r');
                  try {
                    startup.requireActive();
                    await markerParent.sync();
                  } finally {
                    await markerParent.close();
                  }
                  startup.requireActive();
                  if (injectedHandles === undefined) {
                    if (config.browser !== undefined)
                      await retainHandle(
                        await createBrowserBackend(config.browser, startup),
                        startup,
                      );
                    if (config.browserControl !== undefined) {
                      const handle = await createBrowserControlBackend(
                        config.browserControl,
                        startup,
                      );
                      await retainHandle(handle, startup);
                      controls.push(handle.backend);
                    }
                    if (config.macosControl !== undefined) {
                      const handle = await createMacOSControlBackend(
                        config.macosControl,
                        startup,
                      );
                      await retainHandle(handle, startup);
                      nativeControls.push(handle.backend);
                      armingControls.push(handle.backend);
                    }
                    if (config.macos !== undefined)
                      await retainHandle(
                        config.macosControl === undefined
                          ? createMacOSBackend(config.macos)
                          : await createCoordinatedMacOSBackend(
                              config.macos,
                              startup,
                            ),
                        startup,
                      );
                  }
                  startup.requireActive();
                  const journal =
                    options.journal ??
                    (await FileToolJournal.open(
                      config.journalDirectory,
                      identity,
                    ));
                  startup.requireActive();
                  const created = await createComputerProvider({
                    connection: passes.connection,
                    binding: config.binding,
                    journal,
                    backends: handles.map((handle) => handle.backend),
                    browserControls: controls,
                    browserTools,
                    nativeControls,
                    nativeArmingControls: armingControls,
                    nativeTools,
                    generation: randomUUID(),
                    origin: config.origin,
                    currentBearer: options.currentBearer,
                    clock,
                    prefix: config.prefix,
                    controlBudgetMs: config.budgets.controlMs,
                    observeBudgetMs: config.budgets.observeMs,
                    browserObservationReserveMs:
                      config.budgets.browserObservationReserveMs ?? 3000,
                    nativeObservationReserveMs:
                      config.budgets.nativeObservationReserveMs ?? 3000,
                    cleanupBudgetMs: config.budgets.cleanupMs,
                    ...(config.sessions === undefined
                      ? {}
                      : {
                          frameFreshMs: config.sessions.frameFreshMs,
                          idleMs: config.sessions.idleMs,
                        }),
                    parentDeadline: () => passes.currentDeadline(),
                  });
                  provider = created;
                  if (lifetime.signal.aborted || startup.remainingMs() <= 0) {
                    created.stop();
                    const cleanup = cleanupDeadline;
                    if (cleanup !== undefined && cleanup.remainingMs() > 0)
                      await resources.run(cleanup, () =>
                        created.drain(cleanup),
                      );
                  }
                  startup.requireActive();
                },
                stop,
              );
            } finally {
              startupDeadline = undefined;
            }
            if (provider === undefined)
              throw new Error('Computer provider setup did not complete');
            const running = provider;
            const intents =
              options.intents ??
              new FileCatalogueIntents(config.journalDirectory, identity);
            lease = new HostCatalogueLease(
              config.catalogue.leaseSeconds * 1000,
              config.catalogue.renewalMs,
              clock,
              () => {
                leaseFailure = new CatalogueLeaseExpired();
                stop();
                report({ stage: 'catalogue', code: 'CATALOGUE_EXPIRED' });
              },
            );
            const availability = lease;
            const publish = async (deadline: OperationDeadline) => {
              const publication = availability.begin();
              await catalogue(running, intents, 'publish', deadline);
              deadline.requireActive();
              availability.confirm(publication);
            };
            const perform = <T>(
              work: (deadline: OperationDeadline) => Promise<T>,
            ) => {
              // The earlier confirmed catalogue lease, not a delayed timer, caps
              // each admitted pass. Successful renewal cannot extend this pass.
              const deadline = availability.pass(config.budgets.passMs);
              return passes.run(
                deadline,
                () => work(deadline),
                () => running.stop(),
              );
            };
            const initialMetadata = initialStartup.child(config.budgets.passMs);
            await passes.run(
              initialMetadata,
              async () => {
                const pending = await intents.load();
                initialMetadata.requireActive();
                if (pending?.state === 'pending')
                  await reconcileCatalogue(
                    passes.connection,
                    pending,
                    config,
                    declarations,
                    intents,
                    initialMetadata,
                  );
                if (pending?.state === 'pending')
                  report({ stage: 'catalogue', code: 'CATALOGUE_RECONCILED' });
                await running.provider.reconcile();
                initialMetadata.requireActive();
                if (
                  (await running.journal.all()).some(
                    (row) => row.phase === 'publishing',
                  )
                )
                  throw new CatalogueRecoveryRequired();
                initialMetadata.requireActive();
                await publish(initialMetadata);
              },
              () => running.stop(),
            );
            initialStartup.requireActive();
            options.signal.throwIfAborted();
            lifetime.signal.throwIfAborted();
            published = true;
            report({ stage: 'serving', code: 'READY' });
            lifetime.signal.throwIfAborted();
            options.onReady?.();
            initialStartup.dispose();
            let completed = 0;
            const armingRequests =
              config.nativeArming === undefined
                ? undefined
                : new NativeArmingRequests(config.nativeArming.durationMs);
            while (
              !lifetime.signal.aborted &&
              (options.maxPasses === undefined || completed < options.maxPasses)
            ) {
              await perform(async (deadline) => {
                if (availability.renewalDue()) {
                  await publish(deadline);
                  report({ stage: 'catalogue', code: 'CATALOGUE_RENEWED' });
                }
                availability.requireActive();
                await running.provider.poll();
                if (
                  armingRequests !== undefined &&
                  running.nativeArming !== undefined
                ) {
                  const armingDeadline = deadline.child(
                    config.budgets.controlMs,
                  );
                  try {
                    await armingRequests.present(
                      running.nativeArming,
                      armingDeadline,
                    );
                  } finally {
                    armingDeadline.dispose();
                  }
                }
              });
              completed++;
              if (
                options.maxPasses === undefined ||
                completed < options.maxPasses
              ) {
                if (!lifetime.signal.aborted)
                  await waitForHostPoll(
                    clock,
                    availability.idleDelay(config.pollIntervalMs),
                    lifetime.signal,
                  );
              }
            }
            if (leaseFailure !== undefined) throw leaseFailure;
            if (diagnosticFailure !== undefined) throw diagnosticFailure;
          } catch (error) {
            if (!options.signal.aborted) {
              originalFailure =
                leaseFailure ??
                diagnosticFailure ??
                (error instanceof Error
                  ? error
                  : new Error('Computer host operation failed', {
                      cause: error,
                    }));
              report(
                originalFailure instanceof CatalogueLeaseExpired
                  ? { stage: 'catalogue', code: 'CATALOGUE_EXPIRED' }
                  : originalFailure instanceof CatalogueRecoveryRequired
                    ? { stage: 'catalogue', code: 'CATALOGUE_UNCERTAIN' }
                    : originalFailure instanceof ConnectionFault
                      ? { stage: 'serving', code: 'TRANSPORT_LOST' }
                      : published
                        ? { stage: 'serving', code: 'HOST_FAILED' }
                        : { stage: 'startup', code: 'STARTUP_FAILED' },
              );
            }
          } finally {
            options.signal.removeEventListener('abort', stop);
            stop();
            const cleanup = new OperationDeadline(
              config.budgets.cleanupMs,
              clock,
            );
            cleanupDeadline = cleanup;
            // Start cancellation/close while the original setup/handler is still
            // owned. No backend can starve behind an uncooperative SDK drain.
            const closeWork = new Set<Promise<CloseOutcome>>();
            if (handlesOwned)
              for (const handle of [...handles].reverse())
                closeWork.add(closeOwned(handle, cleanup));
            const errors: unknown[] = [];
            let metadataFailure: unknown;
            report({ stage: 'cleanup', code: 'STOPPING' });
            try {
              try {
                await resources.run(cleanup, () => passes.drain(cleanup));
                if (provider !== undefined) {
                  const running = provider;
                  await resources.run(cleanup, () => running.drain(cleanup));
                }
              } catch (error) {
                errors.push(error);
              }
              // Results are immutable and already observed. A late settlement
              // never retries close or removes a previously retained marker.
              for (const pending of closing.values()) closeWork.add(pending);
              for (const result of await Promise.all(closeWork))
                if (result.kind === 'FAILED') errors.push(result.error);
              if (errors.length === 0 && provider !== undefined && published) {
                const running = provider;
                const intents =
                  options.intents ??
                  new FileCatalogueIntents(config.journalDirectory, identity);
                try {
                  const withdrawal = cleanup.child(
                    Math.max(1, Math.floor(cleanup.remainingMs())),
                  );
                  await passes.run(
                    withdrawal,
                    () => catalogue(running, intents, 'withdraw', withdrawal),
                    () => running.stop(),
                  );
                } catch (error) {
                  metadataFailure = error;
                  // A timed-out metadata pass can still own a late journal write.
                  // Do not release the lock/marker until that original pass settles.
                  try {
                    await resources.run(cleanup, () => passes.drain(cleanup));
                  } catch (drainError) {
                    errors.push(drainError);
                  }
                }
              }
              if (errors.length === 0 && markerOwned) {
                try {
                  await resources.run(cleanup, async () => {
                    cleanup.requireActive();
                    await rmdir(activeMarker);
                    cleanup.requireActive();
                    const parent = await open(config.journalDirectory, 'r');
                    try {
                      cleanup.requireActive();
                      await parent.sync();
                    } finally {
                      await parent.close();
                    }
                  });
                } catch (error) {
                  errors.push(error);
                }
              }
              if (errors.length > 0) {
                report({ stage: 'cleanup', code: 'CLEANUP_UNCERTAIN' });
                cleanupFailure = new AggregateError(
                  [
                    ...(originalFailure === undefined ? [] : [originalFailure]),
                    ...errors,
                  ],
                  // rmdir may have succeeded before a parent fsync failed; never
                  // promise that a marker still exists after durability failure.
                  'Computer host cleanup is uncertain; inspect ownership and retained receipts',
                );
              } else if (metadataFailure !== undefined) {
                report({ stage: 'cleanup', code: 'RECOVERY_REQUIRED' });
                cleanupFailure = new AggregateError(
                  [
                    ...(originalFailure === undefined ? [] : [originalFailure]),
                    metadataFailure,
                  ],
                  'Computer host metadata requires reconciliation; original identities retained',
                );
              } else {
                report({ stage: 'cleanup', code: 'STOPPED' });
              }
            } finally {
              cleanup.dispose();
            }
          }
          if (cleanupFailure !== undefined) throw cleanupFailure;
          if (originalFailure !== undefined) throw originalFailure;
          if (diagnosticFailure !== undefined) throw diagnosticFailure;
        },
        admission.signal,
      );
    });
  } finally {
    admission.dispose();
    initialStartup.dispose();
  }
}
