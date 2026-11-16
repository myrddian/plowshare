import { randomUUID } from 'node:crypto';
import {
  BrowserControlError,
  decodeBrowserAcquireRequest,
  decodeBrowserActionResult,
  decodeBrowserCleanupResult,
  decodeBrowserControlPolicy,
  decodeBrowserFocusRequest,
  decodeBrowserLease,
  decodeBrowserNavigateRequest,
  decodeBrowserObservation,
  decodeBrowserObserveRequest,
  decodeBrowserPerformRequest,
  decodeBrowserWindows,
  permittedBrowserUrl,
} from '../browser-control.ts';
import type {
  BrowserAcquireRequest,
  BrowserActionResult,
  BrowserCleanupResult,
  BrowserControl,
  BrowserControlBackend,
  BrowserControlPolicy,
  BrowserFocusRequest,
  BrowserLease,
  BrowserNavigateRequest,
  BrowserObservation,
  BrowserObserveRequest,
  BrowserPerformRequest,
  BrowserToolName,
  BrowserWindows,
} from '../browser-control.ts';
import { BackendError, decodeTargets } from '../contracts.ts';
import type { ComputerTarget } from '../contracts.ts';
import { OperationDeadline } from '../deadline.ts';
import { decodeBrowserBackendConfig } from './browser.ts';
import { awaitBrowserWork } from './browser-control-rpc.ts';
import type { BrowserBackendConfig, BrowserTargetConfig } from './browser.ts';
import { DirectoryBrowserOwnership } from './browser-ownership.ts';
import type {
  BrowserOwnership,
  BrowserOwnershipLease,
} from './browser-ownership.ts';
import { launchBrowserControlDriver } from './browser-control-driver.ts';

export interface BrowserControlBackendConfig extends BrowserBackendConfig {
  readonly ownershipDirectory: string;
  readonly control: BrowserControlPolicy;
  readonly maxTabs?: number;
  readonly maxDomNodes?: number;
  readonly maxVisitedNodes?: number;
  readonly pixelsPerLine?: number;
}
export interface BrowserControlBackendHandle {
  readonly backend: BrowserControlBackend;
  close(deadline: OperationDeadline): Promise<void>;
}
/** Every lease gets a fresh isolated context; no vendor objects cross this boundary. */
export interface ControlledBrowserContext {
  windows(deadline: OperationDeadline): Promise<BrowserWindows>;
  observe(
    request: BrowserObserveRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserObservation>;
  focus(
    request: BrowserFocusRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult>;
  navigate(
    request: BrowserNavigateRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult>;
  perform(
    request: BrowserPerformRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult>;
  close(deadline: OperationDeadline): Promise<BrowserCleanupResult>;
}
export interface BrowserControlDriver {
  open(
    target: BrowserTargetConfig,
    deadline: OperationDeadline,
  ): Promise<ControlledBrowserContext>;
  close(deadline: OperationDeadline): Promise<void>;
}
export type BrowserControlDriverFactory = (
  config: BrowserControlBackendConfig,
  deadline: OperationDeadline,
) => Promise<BrowserControlDriver>;

function record(value: unknown): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new BrowserControlError('INVALID_ARGUMENT');
  const row = value as Readonly<Record<string, unknown>>;
  const fields = [
    'executablePath',
    'headless',
    'cleanupBudgetMs',
    'targets',
    'ownershipDirectory',
    'control',
    'maxTabs',
    'maxDomNodes',
    'maxVisitedNodes',
    'pixelsPerLine',
  ];
  if (Object.keys(row).some((key) => !fields.includes(key)))
    throw new BrowserControlError('INVALID_ARGUMENT');
  return row;
}
function limit(value: unknown, fallback: number, maximum: number): number {
  const selected = value === undefined ? fallback : value;
  if (
    typeof selected !== 'number' ||
    !Number.isSafeInteger(selected) ||
    selected < 1 ||
    selected > maximum
  )
    throw new BrowserControlError('INVALID_ARGUMENT');
  return selected;
}
/** Explicit opt-in; the observation-only factory and its configuration remain unchanged. */
export function decodeBrowserControlBackendConfig(
  value: unknown,
): BrowserControlBackendConfig {
  const row = record(value);
  if (row['cleanupBudgetMs'] === null)
    throw new BrowserControlError('INVALID_ARGUMENT');
  const base = decodeBrowserBackendConfig({
    executablePath: row['executablePath'],
    headless: row['headless'],
    targets: row['targets'],
    ...(row['cleanupBudgetMs'] === undefined
      ? {}
      : { cleanupBudgetMs: row['cleanupBudgetMs'] }),
  });
  const directory = row['ownershipDirectory'];
  if (typeof directory !== 'string')
    throw new BrowserControlError('INVALID_ARGUMENT');
  // The capability validates the absolute/private storage boundary before use.
  new DirectoryBrowserOwnership(directory);
  const control = decodeBrowserControlPolicy(row['control']);
  for (const target of base.targets) permittedBrowserUrl(control, target.url);
  const maxDomNodes = limit(row['maxDomNodes'], 200, 200);
  const maxVisitedNodes = limit(row['maxVisitedNodes'], 2000, 10000);
  if (maxVisitedNodes < maxDomNodes)
    throw new BrowserControlError('INVALID_ARGUMENT');
  return Object.freeze({
    ...base,
    ownershipDirectory: directory,
    control,
    maxTabs: limit(row['maxTabs'], 8, 32),
    maxDomNodes,
    maxVisitedNodes,
    pixelsPerLine: limit(row['pixelsPerLine'], 40, 256),
  });
}

interface LeaseRecord {
  readonly lease: BrowserLease;
  readonly cancellation: OperationDeadline;
  marker?: BrowserOwnershipLease;
  context?: ControlledBrowserContext;
  phase: 'opening' | 'active' | 'fenced' | 'releasing';
  busy: boolean;
  work: Promise<unknown>;
  cancelActive?: () => void;
}

class ControlledBackend implements BrowserControlBackend, BrowserControl {
  readonly browserControl: BrowserControl = this;
  readonly allowedTools: readonly BrowserToolName[];
  private readonly leases = new Map<string, LeaseRecord>();
  private readonly targetsList: readonly ComputerTarget[];
  private closing = false;
  constructor(
    private readonly config: BrowserControlBackendConfig,
    private readonly driver: BrowserControlDriver,
    private readonly ownership: BrowserOwnership,
  ) {
    this.allowedTools = config.control.tools;
    this.targetsList = decodeTargets(
      config.targets.map((target) => ({
        targetId: target.targetId,
        backend: 'browser',
        label: target.label,
        surfaces: [{ id: 'viewport', label: 'Lease initial browser viewport' }],
        capabilities: ['capture', 'structured', 'pointer', 'keyboard'],
      })),
    );
  }
  targets(deadline: OperationDeadline): Promise<readonly ComputerTarget[]> {
    deadline.requireActive();
    if (this.closing)
      return Promise.reject(new BrowserControlError('TARGET_GONE'));
    return Promise.resolve(this.targetsList);
  }
  capture(): Promise<never> {
    return Promise.reject(new BrowserControlError('NO_LEASE'));
  }
  async acquire(
    request: BrowserAcquireRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserLease> {
    const checked = decodeBrowserAcquireRequest(request);
    deadline.requireActive();
    if (this.closing) throw new BrowserControlError('TARGET_GONE');
    const target = this.config.targets.find(
      (target) => target.targetId === checked.targetId,
    );
    if (target === undefined) throw new BrowserControlError('TARGET_GONE');
    if (this.leases.has(target.targetId)) throw new BrowserControlError('BUSY');
    const lease = Object.freeze({
      leaseId: randomUUID(),
      targetId: target.targetId,
      ...checked.session,
    });
    const cancellation = new OperationDeadline(
      Math.max(1, Math.ceil(deadline.remainingMs())),
      undefined,
      deadline,
    );
    const entry: LeaseRecord = {
      lease,
      cancellation,
      phase: 'opening',
      busy: true,
      work: Promise.resolve(),
    };
    this.leases.set(target.targetId, entry);
    const opening = (async () => {
      entry.marker = await this.ownership.acquire(
        target.targetId,
        cancellation,
      );
      cancellation.requireActive();
      if (this.closing || entry.phase !== 'opening')
        throw new BrowserControlError('FENCED');
      const context = await this.driver.open(target, cancellation);
      entry.context = context;
      cancellation.requireActive();
      if (this.closing || entry.phase !== 'opening')
        throw new BrowserControlError('FENCED');
    })();
    entry.work = opening;
    try {
      await awaitBrowserWork(opening, cancellation);
      entry.phase = 'active';
      entry.busy = false;
      return lease;
    } catch {
      entry.phase = 'fenced';
      entry.marker?.fence();
      // If a non-cancellable open completes late, retain its marker and own its
      // cleanup. It can never become a usable session or clear the durable fence.
      entry.work = opening
        .catch(() => {
          entry.phase = 'fenced';
          entry.marker?.fence();
        })
        .then(async () => {
          if (entry.context !== undefined) {
            const cleanup = new OperationDeadline(
              this.config.cleanupBudgetMs ?? 2000,
            );
            try {
              await entry.context.close(cleanup);
            } finally {
              cleanup.dispose();
            }
          }
        });
      entry.work.catch(() => {
        entry.marker?.fence();
      });
      throw new BackendError('CAPTURE_FAILED', 'uncertain');
    } finally {
      cancellation.dispose();
    }
  }
  private entry(lease: BrowserLease): LeaseRecord {
    const checked = decodeBrowserLease(lease);
    const entry = this.leases.get(checked.targetId);
    if (
      entry === undefined ||
      entry.lease.leaseId !== checked.leaseId ||
      entry.lease.sessionId !== checked.sessionId ||
      entry.lease.generation !== checked.generation
    )
      throw new BrowserControlError('NO_LEASE');
    return entry;
  }
  private requireTool(tool: BrowserToolName): void {
    if (!this.allowedTools.includes(tool))
      throw new BrowserControlError('CONTROL_DISABLED');
  }
  private async using<T>(
    lease: BrowserLease,
    deadline: OperationDeadline,
    operation: (
      context: ControlledBrowserContext,
      budget: OperationDeadline,
    ) => Promise<T>,
  ): Promise<T> {
    deadline.requireActive();
    const entry = this.entry(lease);
    if (this.closing || entry.phase !== 'active')
      throw new BrowserControlError('FENCED');
    if (entry.busy) throw new BrowserControlError('BUSY');
    const context = entry.context;
    if (context === undefined) throw new BrowserControlError('FENCED');
    entry.busy = true;
    const budget = deadline.child(
      Math.max(1, Math.ceil(deadline.remainingMs())),
    );
    entry.cancelActive = () => budget.cancel();
    const work = Promise.resolve().then(() => {
      budget.requireActive();
      if (this.closing || entry.phase !== 'active')
        throw new BrowserControlError('FENCED');
      return operation(context, budget);
    });
    entry.work = work;
    // The driver receives the original operation deadline. A separate bounded
    // settlement allowance lets it return known delivery plus failed capture,
    // or complete physical cleanup, without a caller timeout erasing that proof.
    const settlement = new OperationDeadline(
      Math.min(
        300000,
        Math.max(1, Math.ceil(budget.remainingMs())) +
          (this.config.cleanupBudgetMs ?? 2000),
      ),
    );
    try {
      return await awaitBrowserWork(work, settlement);
    } catch (error) {
      if (error instanceof BrowserControlError && !budget.signal.aborted)
        throw error;
      entry.phase = 'fenced';
      const cleanup = new OperationDeadline(
        this.config.cleanupBudgetMs ?? 2000,
      );
      let settled: boolean;
      try {
        const result = await awaitBrowserWork(context.close(cleanup), cleanup);
        await awaitBrowserWork(Promise.allSettled([work]), cleanup);
        settled =
          result.physicalSettlement === 'settled' && result.heldInputsReleased;
      } catch {
        settled = false;
      } finally {
        cleanup.dispose();
      }
      if (!settled) entry.marker?.fence();
      throw new BackendError(
        budget.signal.aborted ? 'DEADLINE_EXPIRED' : 'CAPTURE_FAILED',
        settled ? 'settled' : 'uncertain',
      );
    } finally {
      entry.busy = false;
      delete entry.cancelActive;
      budget.dispose();
      settlement.dispose();
    }
  }
  windows(
    lease: BrowserLease,
    deadline: OperationDeadline,
  ): Promise<BrowserWindows> {
    this.requireTool('windows');
    return this.using(lease, deadline, async (context, budget) =>
      decodeBrowserWindows(await context.windows(budget)),
    );
  }
  observe(
    lease: BrowserLease,
    request: BrowserObserveRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserObservation> {
    const checked = decodeBrowserObserveRequest(request);
    return this.using(lease, deadline, async (context, budget) =>
      decodeBrowserObservation(await context.observe(checked, budget)),
    );
  }
  private async action(
    lease: BrowserLease,
    deadline: OperationDeadline,
    operation: (
      context: ControlledBrowserContext,
      budget: OperationDeadline,
    ) => Promise<BrowserActionResult>,
  ): Promise<BrowserActionResult> {
    return this.using(lease, deadline, async (context, budget) => {
      const entry = this.entry(lease);
      try {
        const result = decodeBrowserActionResult(
          await operation(context, budget),
        );
        if (result.delivery.outcome === 'UNCERTAIN') {
          entry.phase = 'fenced';
          if (result.delivery.physicalSettlement === 'uncertain')
            entry.marker?.fence();
        }
        return result;
      } catch {
        entry.phase = 'fenced';
        const cleanup = new OperationDeadline(
          this.config.cleanupBudgetMs ?? 2000,
        );
        let physicalSettlement: 'settled' | 'uncertain' = 'uncertain';
        try {
          const result = await awaitBrowserWork(
            context.close(cleanup),
            cleanup,
          );
          if (
            result.physicalSettlement === 'settled' &&
            result.heldInputsReleased
          )
            physicalSettlement = 'settled';
        } catch {
          physicalSettlement = 'uncertain';
        } finally {
          cleanup.dispose();
        }
        if (physicalSettlement === 'uncertain') entry.marker?.fence();
        return {
          delivery: {
            outcome: 'UNCERTAIN',
            physicalSettlement,
            code: 'INPUT_FAILED',
          },
          observation: { state: 'FAILED', code: 'OBSERVATION_FAILED' },
          dialog: null,
        };
      }
    });
  }
  focus(
    lease: BrowserLease,
    request: BrowserFocusRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult> {
    this.requireTool('focus');
    const checked = decodeBrowserFocusRequest(request);
    return this.action(lease, deadline, (context, budget) =>
      context.focus(checked, budget),
    );
  }
  navigate(
    lease: BrowserLease,
    request: BrowserNavigateRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult> {
    this.requireTool('navigate');
    const checked = decodeBrowserNavigateRequest(request);
    permittedBrowserUrl(this.config.control, checked.url);
    if (checked.dialogDecision?.promptText !== undefined)
      this.requireTool('type');
    return this.action(lease, deadline, (context, budget) =>
      context.navigate(checked, budget),
    );
  }
  perform(
    lease: BrowserLease,
    request: BrowserPerformRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult> {
    const checked = decodeBrowserPerformRequest(request);
    this.requireTool(
      ['move', 'click', 'double_click', 'drag'].includes(checked.action.kind)
        ? 'pointer'
        : checked.action.kind === 'type'
          ? 'type'
          : checked.action.kind === 'key'
            ? 'key'
            : 'scroll',
    );
    if (checked.dialogDecision?.promptText !== undefined)
      this.requireTool('type');
    return this.action(lease, deadline, (context, budget) =>
      context.perform(checked, budget),
    );
  }
  async release(
    lease: BrowserLease,
    deadline: OperationDeadline,
  ): Promise<BrowserCleanupResult> {
    const entry = this.entry(lease);
    if (entry.phase === 'releasing')
      return { physicalSettlement: 'uncertain', heldInputsReleased: false };
    entry.phase = 'releasing';
    entry.cancellation.cancel();
    entry.cancelActive?.();
    try {
      if (entry.context === undefined)
        throw new BrowserControlError('CLEANUP_UNCERTAIN');
      const result = decodeBrowserCleanupResult(
        await awaitBrowserWork(entry.context.close(deadline), deadline),
      );
      if (result.physicalSettlement !== 'settled')
        throw new BrowserControlError('CLEANUP_UNCERTAIN');
      await awaitBrowserWork(Promise.allSettled([entry.work]), deadline);
      if (entry.marker === undefined)
        throw new BrowserControlError('CLEANUP_UNCERTAIN');
      await entry.marker.releaseAfterQuiescence(deadline);
      this.leases.delete(lease.targetId);
      return result;
    } catch {
      entry.phase = 'fenced';
      entry.marker?.fence();
      return { physicalSettlement: 'uncertain', heldInputsReleased: false };
    }
  }
  async close(deadline: OperationDeadline): Promise<void> {
    this.closing = true;
    let uncertain = false;
    for (const entry of this.leases.values()) {
      const result = await this.release(entry.lease, deadline);
      if (result.physicalSettlement !== 'settled') uncertain = true;
    }
    await awaitBrowserWork(this.driver.close(deadline), deadline);
    if (uncertain) throw new BackendError('CAPTURE_FAILED', 'uncertain');
  }
}

export async function createBrowserControlBackend(
  config: BrowserControlBackendConfig,
  deadline: OperationDeadline,
  factory: BrowserControlDriverFactory = launchBrowserControlDriver,
  ownership?: BrowserOwnership,
): Promise<BrowserControlBackendHandle> {
  deadline.requireActive();
  const checked = decodeBrowserControlBackendConfig(config);
  const work = Promise.resolve().then(() => {
    deadline.requireActive();
    return factory(checked, deadline);
  });
  let driver: BrowserControlDriver;
  try {
    driver = await awaitBrowserWork(work, deadline);
  } catch {
    const cleanup = work.then(
      async (late) => {
        const budget = new OperationDeadline(checked.cleanupBudgetMs ?? 2000);
        try {
          await late.close(budget);
        } finally {
          budget.dispose();
        }
      },
      () => {},
    );
    cleanup.catch(() => {}); // No handle is published; the retained late owner has no reusable target.
    throw new BackendError('CAPTURE_FAILED', 'uncertain');
  }
  const backend = new ControlledBackend(
    checked,
    driver,
    ownership ?? new DirectoryBrowserOwnership(checked.ownershipDirectory),
  );
  return { backend, close: (deadline) => backend.close(deadline) };
}
