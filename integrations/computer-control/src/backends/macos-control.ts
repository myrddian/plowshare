import { randomUUID } from 'node:crypto';
import type {
  NativeAcquireRequest,
  NativeAcquisition,
  NativeActionResult,
  NativeCleanupResult,
  NativeControl,
  NativeControlBackend,
  NativeControlPolicy,
  NativeFocusRequest,
  NativeLease,
  NativeObservation,
  NativeObserveRequest,
  NativePerformRequest,
  NativeToolName,
  NativeWindows,
} from '../native-control.ts';
import {
  NativeControlError,
  decodeNativeAcquireRequest,
  decodeNativeAcquisition,
  decodeNativeActionResult,
  decodeNativeControlPolicy,
  decodeNativeFocusRequest,
  decodeNativeLease,
  decodeNativeObservation,
  decodeNativeObserveRequest,
  decodeNativePerformRequest,
  decodeNativeWindows,
} from '../native-control.ts';
import type { ComputerTarget } from '../contracts.ts';
import { decodeTargets } from '../contracts.ts';
import { OperationDeadline } from '../deadline.ts';
import type { NativeControlProcess } from './native-control-process.ts';
import { SpawnNativeControlProcess } from './native-control-process.ts';
import { nativeBrokerCodec } from './native-broker-protocol.ts';
import type {
  NativeBrokerConfiguration,
  NativeBrokerReply,
  NativeBrokerRequest,
} from './native-broker-protocol.ts';
import { decodeMacOSBackendConfig } from './macos.ts';
import type { MacOSBackendConfig } from './macos.ts';
import { NativeDirectoryOwnership } from './native-ownership.ts';
import { awaitBrowserWork } from './browser-control-rpc.ts';

export interface MacOSControlBackendConfig extends MacOSBackendConfig {
  readonly control: NativeControlPolicy;
  readonly maxWindows?: number;
  readonly maxNodes?: number;
  readonly maxVisitedNodes?: number;
}
export interface MacOSControlBackendHandle {
  readonly backend: NativeControlBackend;
  close(deadline: OperationDeadline): Promise<void>;
}
export type NativeControlProcessFactory = (
  deadline: OperationDeadline,
) => NativeControlProcess;

function row(value: unknown): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new NativeControlError('INVALID_ARGUMENT');
  const row = value as Readonly<Record<string, unknown>>;
  if (
    Object.keys(row).some(
      (key) =>
        ![
          'helperExecutable',
          'ownershipDirectory',
          'cleanupBudgetMs',
          'targets',
          'control',
          'maxWindows',
          'maxNodes',
          'maxVisitedNodes',
        ].includes(key),
    )
  )
    throw new NativeControlError('INVALID_ARGUMENT');
  return row;
}
function limit(value: unknown, fallback: number, maximum: number): number {
  const n = value === undefined ? fallback : value;
  if (typeof n !== 'number' || !Number.isSafeInteger(n) || n < 1 || n > maximum)
    throw new NativeControlError('INVALID_ARGUMENT');
  return n;
}
export function decodeMacOSControlBackendConfig(
  value: unknown,
): MacOSControlBackendConfig {
  const config = row(value);
  if (config['cleanupBudgetMs'] === null)
    throw new NativeControlError('INVALID_ARGUMENT');
  const base = decodeMacOSBackendConfig({
    helperExecutable: config['helperExecutable'],
    ownershipDirectory: config['ownershipDirectory'],
    targets: config['targets'],
    ...(config['cleanupBudgetMs'] === undefined
      ? {}
      : { cleanupBudgetMs: config['cleanupBudgetMs'] }),
  });
  if (base.targets.some((target) => !target.surfaceId.startsWith('display:')))
    throw new NativeControlError('INVALID_ARGUMENT');
  const maxNodes = limit(config['maxNodes'], 200, 200),
    maxVisitedNodes = limit(config['maxVisitedNodes'], 2000, 10000);
  if (maxVisitedNodes < maxNodes)
    throw new NativeControlError('INVALID_ARGUMENT');
  return Object.freeze({
    ...base,
    control: decodeNativeControlPolicy(config['control']),
    maxWindows: limit(config['maxWindows'], 16, 32),
    maxNodes,
    maxVisitedNodes,
  });
}

interface LeaseEntry {
  readonly lease: NativeLease;
  readonly ownership: NativeDirectoryOwnership;
  process?: NativeControlProcess;
  phase: 'opening' | 'active' | 'closing' | 'fenced';
  busy: boolean;
  running: Promise<unknown>;
  cancel?: () => void;
  cleanup?: Promise<NativeCleanupResult>;
}
/** Proven host-side refusal before a private helper command can be submitted. */
class LocalNativeRefusal extends NativeControlError {}
const uncertainCleanup: NativeCleanupResult = Object.freeze({
  physicalSettlement: 'uncertain',
  heldInputsReleased: false,
  helperStopped: false,
});

class MacOSController implements NativeControlBackend, NativeControl {
  readonly desktopIsolation = 'native-broker';
  readonly nativeControl: NativeControl = this;
  readonly allowedTools: readonly NativeToolName[];
  private entry: LeaseEntry | undefined;
  private closed = false;
  private readonly targetList: readonly ComputerTarget[];
  constructor(
    private readonly config: MacOSControlBackendConfig,
    private readonly factory: NativeControlProcessFactory,
  ) {
    this.allowedTools = config.control.tools;
    this.targetList = decodeTargets(
      config.targets.map((target) => ({
        targetId: target.targetId,
        label: target.label,
        backend: 'macos',
        surfaces: [{ id: target.surfaceId, label: target.label }],
        capabilities: [
          'capture',
          'structured',
          ...(config.control.tools.some(
            (tool) => tool === 'pointer' || tool === 'scroll',
          )
            ? ['pointer']
            : []),
          ...(config.control.tools.some(
            (tool) => tool === 'type' || tool === 'key',
          )
            ? ['keyboard']
            : []),
        ],
      })),
    );
  }
  targets(deadline: OperationDeadline): Promise<readonly ComputerTarget[]> {
    deadline.requireActive();
    if (this.closed)
      return Promise.reject(new NativeControlError('TARGET_GONE'));
    return Promise.resolve(this.targetList);
  }
  capture(): Promise<never> {
    return Promise.reject(new NativeControlError('NO_LEASE'));
  }
  private owned(lease: NativeLease): LeaseEntry {
    const checked = decodeNativeLease(lease),
      entry = this.entry;
    if (
      entry === undefined ||
      entry.lease.leaseId !== checked.leaseId ||
      entry.lease.targetId !== checked.targetId ||
      entry.lease.sessionId !== checked.sessionId ||
      entry.lease.generation !== checked.generation
    )
      throw new NativeControlError('NO_LEASE');
    return entry;
  }
  async acquire(
    request: NativeAcquireRequest,
    deadline: OperationDeadline,
  ): Promise<NativeAcquisition> {
    const checked = decodeNativeAcquireRequest(request);
    deadline.requireActive();
    if (this.closed) return { outcome: 'NOT_ACQUIRED', code: 'TARGET_GONE' };
    if (this.entry !== undefined)
      return { outcome: 'NOT_ACQUIRED', code: 'BUSY' };
    const target = this.config.targets.find(
      (target) => target.targetId === checked.targetId,
    );
    if (target === undefined)
      return { outcome: 'NOT_ACQUIRED', code: 'TARGET_GONE' };
    const lease = Object.freeze({
      leaseId: randomUUID(),
      targetId: target.targetId,
      ...checked.session,
    });
    const entry: LeaseEntry = {
      lease,
      ownership: new NativeDirectoryOwnership(this.config.ownershipDirectory),
      phase: 'opening',
      busy: true,
      running: Promise.resolve(),
    };
    this.entry = entry;
    const budget = deadline.child(
      Math.max(1, Math.ceil(deadline.remainingMs())),
    );
    entry.cancel = () => budget.cancel();
    const work = (async () => {
      if ((await entry.ownership.acquire(lease, budget)) === 'busy') {
        return { outcome: 'NOT_ACQUIRED', code: 'BUSY' } as const;
      }
      budget.requireActive();
      if (this.closed || entry.phase !== 'opening')
        throw new NativeControlError('FENCED');
      entry.process = this.factory(budget);
      const configuration: NativeBrokerConfiguration = {
        ownershipDirectory: this.config.ownershipDirectory,
        target,
        control: this.config.control,
        maxWindows: this.config.maxWindows ?? 16,
        maxNodes: this.config.maxNodes ?? 200,
        maxVisitedNodes: this.config.maxVisitedNodes ?? 2000,
        cleanupBudgetMs: this.config.cleanupBudgetMs ?? 2000,
      };
      const reply = await entry.process.request(
        { operation: 'acquire', lease, configuration },
        budget,
      );
      if (reply.operation !== 'acquire') throw new NativeControlError('FENCED');
      const acquisition = decodeNativeAcquisition(reply.result);
      if (
        acquisition.outcome === 'ACQUIRED' &&
        (acquisition.lease.leaseId !== lease.leaseId ||
          acquisition.lease.targetId !== lease.targetId ||
          acquisition.lease.sessionId !== lease.sessionId ||
          acquisition.lease.generation !== lease.generation)
      )
        throw new NativeControlError('FENCED');
      return acquisition;
    })();
    entry.running = work;
    try {
      const acquisition = await awaitBrowserWork(work, budget);
      if (this.closed || entry.phase !== 'opening')
        throw new NativeControlError('FENCED');
      if (acquisition.outcome === 'ACQUIRED') {
        entry.phase = 'active';
        entry.busy = false;
        return acquisition;
      }
      if (acquisition.outcome === 'NOT_ACQUIRED') {
        if (entry.process === undefined) {
          this.entry = undefined;
          return acquisition;
        }
        const cleanup = new OperationDeadline(
          this.config.cleanupBudgetMs ?? 2000,
        );
        try {
          const result = await this.cleanup(entry, cleanup);
          if (result.physicalSettlement === 'settled') return acquisition;
        } finally {
          cleanup.dispose();
        }
      }
      entry.phase = 'fenced';
      entry.ownership.fence();
      return {
        outcome: 'UNCERTAIN',
        lease,
        code:
          acquisition.outcome === 'UNCERTAIN'
            ? acquisition.code
            : 'CLEANUP_UNCERTAIN',
      };
    } catch {
      entry.phase = 'fenced';
      entry.ownership.fence();
      return {
        outcome: 'UNCERTAIN',
        lease,
        code: budget.signal.aborted ? 'DEADLINE_EXPIRED' : 'FENCED',
      };
    } finally {
      delete entry.cancel;
      entry.busy = false;
      budget.dispose();
    }
  }
  private async request(
    lease: NativeLease,
    command: NativeBrokerRequest,
    deadline: OperationDeadline,
  ): Promise<NativeBrokerReply> {
    const entry = this.owned(lease);
    try {
      deadline.requireActive();
    } catch {
      throw new LocalNativeRefusal('DEADLINE_EXPIRED');
    }
    if (this.closed || entry.phase !== 'active' || entry.process === undefined)
      throw new LocalNativeRefusal('FENCED');
    if (entry.busy) throw new LocalNativeRefusal('BUSY');
    entry.busy = true;
    const process = entry.process,
      budget = deadline.child(Math.max(1, Math.ceil(deadline.remainingMs())));
    entry.cancel = () => budget.cancel();
    let submitted = false;
    const work = Promise.resolve().then(() => {
      budget.requireActive();
      if (this.closed || entry.phase !== 'active')
        throw new NativeControlError('FENCED');
      submitted = true;
      return process.request(command, budget);
    });
    entry.running = work;
    try {
      const reply = await awaitBrowserWork(work, budget);
      if (
        reply.operation === 'failure' &&
        reply.result.physicalSettlement === 'uncertain'
      ) {
        entry.phase = 'fenced';
        entry.ownership.fence();
      }
      return reply;
    } catch (error) {
      if (!submitted)
        throw new LocalNativeRefusal(
          budget.signal.aborted ? 'DEADLINE_EXPIRED' : 'FENCED',
        );
      entry.phase = 'fenced';
      entry.ownership.fence();
      throw error;
    } finally {
      entry.busy = false;
      delete entry.cancel;
      budget.dispose();
    }
  }
  private tool(name: NativeToolName): void {
    if (!this.allowedTools.includes(name))
      throw new NativeControlError('CONTROL_DISABLED');
  }
  async windows(
    lease: NativeLease,
    deadline: OperationDeadline,
  ): Promise<NativeWindows> {
    this.tool('windows');
    const reply = await this.request(
      lease,
      { operation: 'windows', lease },
      deadline,
    );
    if (reply.operation === 'failure')
      throw new NativeControlError(reply.result.code);
    if (reply.operation !== 'windows') throw new NativeControlError('FENCED');
    return decodeNativeWindows(reply.result);
  }
  async observe(
    lease: NativeLease,
    request: NativeObserveRequest,
    deadline: OperationDeadline,
  ): Promise<NativeObservation> {
    const checked = decodeNativeObserveRequest(request),
      reply = await this.request(
        lease,
        { operation: 'observe', lease, request: checked },
        deadline,
      );
    if (reply.operation === 'failure')
      throw new NativeControlError(reply.result.code);
    if (reply.operation !== 'observe') throw new NativeControlError('FENCED');
    return decodeNativeObservation(reply.result);
  }
  private async action(
    lease: NativeLease,
    command: NativeBrokerRequest,
    deadline: OperationDeadline,
  ): Promise<NativeActionResult> {
    try {
      const reply = await this.request(lease, command, deadline);
      if (reply.operation !== 'focus' && reply.operation !== 'perform')
        throw new NativeControlError('FENCED');
      const result = decodeNativeActionResult(reply.result);
      if (result.delivery.outcome === 'UNCERTAIN') {
        const entry = this.owned(lease);
        entry.phase = 'fenced';
        if (result.delivery.physicalSettlement === 'uncertain')
          entry.ownership.fence();
      }
      return result;
    } catch (error) {
      if (error instanceof LocalNativeRefusal)
        return {
          delivery: {
            outcome: 'NOT_DISPATCHED',
            physicalSettlement: 'settled',
            code: error.code,
          },
          observation: { state: 'FAILED', code: 'OBSERVATION_FAILED' },
        };
      const entry = this.owned(lease);
      entry.phase = 'fenced';
      entry.ownership.fence();
      return {
        delivery: {
          outcome: 'UNCERTAIN',
          physicalSettlement: 'uncertain',
          code: 'INPUT_FAILED',
        },
        observation: { state: 'FAILED', code: 'OBSERVATION_FAILED' },
      };
    }
  }
  focus(
    lease: NativeLease,
    request: NativeFocusRequest,
    deadline: OperationDeadline,
  ): Promise<NativeActionResult> {
    this.tool('focus');
    return this.action(
      lease,
      { operation: 'focus', lease, request: decodeNativeFocusRequest(request) },
      deadline,
    );
  }
  perform(
    lease: NativeLease,
    request: NativePerformRequest,
    deadline: OperationDeadline,
  ): Promise<NativeActionResult> {
    const checked = decodeNativePerformRequest(request),
      kind = checked.action.kind;
    this.tool(
      ['move', 'click', 'double_click', 'drag'].includes(kind)
        ? 'pointer'
        : kind === 'type'
          ? 'type'
          : kind === 'key'
            ? 'key'
            : 'scroll',
    );
    return this.action(
      lease,
      { operation: 'perform', lease, request: checked },
      deadline,
    );
  }
  private async cleanup(
    entry: LeaseEntry,
    deadline: OperationDeadline,
  ): Promise<NativeCleanupResult> {
    if (entry.cleanup !== undefined)
      return awaitBrowserWork(entry.cleanup, deadline);
    entry.cleanup = this.finishCleanup(entry, deadline);
    return entry.cleanup;
  }
  private async finishCleanup(
    entry: LeaseEntry,
    deadline: OperationDeadline,
  ): Promise<NativeCleanupResult> {
    entry.cancel?.();
    entry.phase = 'closing';
    if (entry.process === undefined) {
      entry.ownership.fence();
      entry.phase = 'fenced';
      return uncertainCleanup;
    }
    try {
      const result = await awaitBrowserWork(
        entry.process.close(deadline),
        deadline,
      );
      await awaitBrowserWork(Promise.allSettled([entry.running]), deadline);
      if (
        result.physicalSettlement !== 'settled' ||
        !result.helperStopped ||
        !result.heldInputsReleased
      )
        throw new NativeControlError('CLEANUP_UNCERTAIN');
      await entry.ownership.releaseAfterQuiescence(deadline);
      this.entry = undefined;
      return result;
    } catch {
      entry.ownership.fence();
      entry.phase = 'fenced';
      return uncertainCleanup;
    }
  }
  release(
    lease: NativeLease,
    deadline: OperationDeadline,
  ): Promise<NativeCleanupResult> {
    return this.cleanup(this.owned(lease), deadline);
  }
  async close(deadline: OperationDeadline): Promise<void> {
    this.closed = true;
    if (
      this.entry !== undefined &&
      (await this.cleanup(this.entry, deadline)).physicalSettlement !==
        'settled'
    )
      throw new NativeControlError('CLEANUP_UNCERTAIN');
  }
}

export function createMacOSControlBackend(
  config: MacOSControlBackendConfig,
  deadline: OperationDeadline,
  factory?: NativeControlProcessFactory,
): Promise<MacOSControlBackendHandle> {
  deadline.requireActive();
  const checked = decodeMacOSControlBackendConfig(config);
  const backend = new MacOSController(
    checked,
    factory ??
      ((budget) =>
        new SpawnNativeControlProcess(
          checked.helperExecutable,
          nativeBrokerCodec,
          budget,
          checked.cleanupBudgetMs ?? 2000,
        )),
  );
  return Promise.resolve({ backend, close: (budget) => backend.close(budget) });
}
