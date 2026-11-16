import { createHash, randomUUID } from 'node:crypto';
import { mkdir, open, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { setTimeout as pause } from 'node:timers/promises';
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
import {
  nativeComposition,
  requireDistinctControlPorts,
} from './native-composition.ts';
import type { BackendHandle } from './backends/types.ts';

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

export interface ComputerHostOptions {
  readonly config: ComputerHostConfig;
  readonly connection: Pick<Plowshare, 'request'>;
  readonly currentBearer: () => Promise<string>;
  readonly signal: AbortSignal;
  readonly backends?: readonly BackendHandle[];
  readonly browserControls?: readonly BrowserControlBackend[];
  readonly nativeControls?: readonly NativeControlBackend[];
  readonly journal?: ToolJournal;
  readonly intents?: CatalogueIntents;
  readonly clock?: Clock;
  /** Earlier startup unit already consumed by authentication; never reset at the host boundary. */
  readonly startupDeadline?: OperationDeadline;
  readonly maxPasses?: number;
  readonly onReady?: () => void;
}

async function reconcileCatalogue(
  connection: Pick<Plowshare, 'request'>,
  intent: CatalogueIntent,
  config: ComputerHostConfig,
  declarations: readonly ToolDeclaration[],
  store: CatalogueIntents,
): Promise<void> {
  const expected = JSON.stringify(
    intent.kind === 'publish'
      ? { version: 'plowshare-tool-catalog/3', tools: declarations }
      : { version: 'plowshare-tool-catalog/1', tools: [] },
  );
  const publisher =
    'sdk:' + createHash('sha256').update(config.binding.account).digest('hex');
  let after = '0';
  for (let pageNumber = 0; pageNumber < 100; pageNumber++) {
    const page = requirePayload(
      await connection.request('relay.log', {
        project: config.binding.project,
        system: false,
        topic: `tool.${config.binding.provider}.catalog`,
        after,
        limit: 100,
      }),
    );
    const event = page.events.find(
      (event) => event.eventId === intent.requestId,
    );
    if (event !== undefined) {
      if (
        event.publisher !== publisher ||
        event.payload.kind !== 'TEXT' ||
        event.payload.text !== expected
      )
        throw new Error(
          'Retained catalogue evidence conflicts with its original intent',
        );
      await store.save({ ...intent, state: 'confirmed' });
      return;
    }
    const last = page.events.at(-1);
    if (last === undefined || last.position === after) break;
    after = last.position;
  }
  throw new Error(
    'Catalogue publication is uncertain; inspect its original request before restarting',
  );
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
 * It takes ownership of supplied backend handles. A private active marker survives uncertain cleanup.
 */
export async function runComputerHost(
  options: ComputerHostOptions,
): Promise<void> {
  const config = decodeComputerHostConfig(options.config),
    clock = options.clock ?? systemClock;
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
    await privateDirectory(config.journalDirectory);
    await localLock(
      config.journalDirectory,
      identity,
      async () => {
        admission.requireActive();
        admission.dispose();
        const activeMarker = join(
          config.journalDirectory,
          identity + '.active',
        );
        try {
          await mkdir(activeMarker, { mode: 0o700 });
        } catch (cause) {
          throw new Error(
            'A prior computer host remains active or fenced; inspect it before recovery',
            { cause },
          );
        }
        const markerParent = await open(config.journalDirectory, 'r');
        try {
          await markerParent.sync();
        } finally {
          await markerParent.close();
        }
        const handles: BackendHandle[] = [...(injectedHandles ?? [])];
        const controls: BrowserControlBackend[] = [...(injectedControls ?? [])];
        const nativeControls: NativeControlBackend[] = [
          ...(injectedNativeControls ?? []),
        ];
        const passes = new ComputerPasses(
          options.connection,
          config.budgets.controlMs,
        );
        let provider: ComputerProvider | undefined,
          published = false,
          clean = false;
        let originalFailure: Error | undefined;
        let cleanupFailure: AggregateError | undefined;
        let startupDeadline: OperationDeadline | undefined;
        const stop = () => {
          provider?.stop();
          startupDeadline?.cancel();
          passes.cancel();
        };
        options.signal.addEventListener('abort', stop, { once: true });
        try {
          const startup = initialStartup;
          startupDeadline = startup;
          try {
            options.signal.throwIfAborted();
            if (injectedHandles === undefined) {
              if (config.browser !== undefined)
                handles.push(
                  await createBrowserBackend(config.browser, startup),
                );
              if (config.browserControl !== undefined) {
                const handle = await createBrowserControlBackend(
                  config.browserControl,
                  startup,
                );
                handles.push(handle);
                controls.push(handle.backend);
              }
              if (config.macosControl !== undefined) {
                const handle = await createMacOSControlBackend(
                  config.macosControl,
                  startup,
                );
                handles.push(handle);
                nativeControls.push(handle.backend);
              }
              if (config.macos !== undefined)
                handles.push(
                  config.macosControl === undefined
                    ? createMacOSBackend(config.macos)
                    : await createCoordinatedMacOSBackend(
                        config.macos,
                        startup,
                      ),
                );
            }
            const journal =
              options.journal ??
              (await FileToolJournal.open(config.journalDirectory, identity));
            provider = await createComputerProvider({
              connection: passes.connection,
              binding: config.binding,
              journal,
              backends: handles.map((handle) => handle.backend),
              browserControls: controls,
              browserTools,
              nativeControls,
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
            startup.requireActive();
          } finally {
            startupDeadline = undefined;
          }
          const running = provider;
          const intents =
            options.intents ??
            new FileCatalogueIntents(config.journalDirectory, identity);
          const perform = <T>(
            work: (deadline: OperationDeadline) => Promise<T>,
            budgetMs = config.budgets.passMs,
          ) => {
            const deadline = new OperationDeadline(budgetMs, clock);
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
                );
              await running.provider.reconcile();
              initialMetadata.requireActive();
              await catalogue(running, intents, 'publish', initialMetadata);
            },
            () => running.stop(),
          );
          initialStartup.requireActive();
          published = true;
          options.onReady?.();
          initialStartup.dispose();
          let renewedAt = clock.monotonicNow(),
            completed = 0;
          while (
            !options.signal.aborted &&
            (options.maxPasses === undefined || completed < options.maxPasses)
          ) {
            await perform(async (deadline) => {
              if (
                clock.monotonicNow() - renewedAt >=
                config.catalogue.renewalMs
              ) {
                await catalogue(running, intents, 'publish', deadline);
                renewedAt = clock.monotonicNow();
              }
              await running.provider.poll();
            });
            completed++;
            if (
              options.maxPasses === undefined ||
              completed < options.maxPasses
            ) {
              try {
                await pause(config.pollIntervalMs, undefined, {
                  signal: options.signal,
                });
              } catch (error) {
                if (!options.signal.aborted) throw error;
              }
            }
          }
        } catch (error) {
          if (!options.signal.aborted)
            originalFailure =
              error instanceof Error
                ? error
                : new Error('Computer host operation failed', { cause: error });
        } finally {
          options.signal.removeEventListener('abort', stop);
          stop();
          const cleanup = new OperationDeadline(
            config.budgets.cleanupMs,
            clock,
          );
          const errors: unknown[] = [];
          try {
            try {
              await passes.drain(cleanup);
              await provider?.drain(cleanup);
            } catch (error) {
              errors.push(error);
            }
            // Provider drain settles JS handlers, not native quiescence. Backend close/fences are separate.
            for (const handle of handles.reverse()) {
              try {
                await handle.close(cleanup);
              } catch (error) {
                errors.push(error);
              }
            }
            if (errors.length === 0 && provider !== undefined && published) {
              const running = provider,
                intents =
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
                errors.push(error);
              }
            }
            if (errors.length)
              cleanupFailure = new AggregateError(
                originalFailure === undefined
                  ? errors
                  : [originalFailure, ...errors],
                'Computer host cleanup is uncertain; ownership marker retained',
              );
            else clean = true;
          } finally {
            cleanup.dispose();
            if (clean) await rm(activeMarker, { recursive: true });
          }
        }
        if (cleanupFailure !== undefined) throw cleanupFailure;
        if (originalFailure !== undefined) throw originalFailure;
      },
      admission.signal,
    );
  } finally {
    admission.dispose();
    initialStartup.dispose();
  }
}
