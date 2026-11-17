import { randomUUID } from 'node:crypto';
import type {
  NativeAcquireRequest,
  NativeAcquisition,
  NativeActionResult,
  NativeCleanupResult,
  NativeControl,
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
import type { ComputerTarget, ComputerOwner } from '../contracts.ts';
import type {
  NativeArmingBackend,
  NativeArming,
  NativeArmRequest,
  NativeArmStatus,
} from '../native-arming.ts';
import {
  decodeNativeArmRequest,
  decodeNativeArmStatus,
} from '../native-arming.ts';
import { decodeTargets } from '../contracts.ts';
import { OperationDeadline } from '../deadline.ts';
import type { NativeControlProcess } from './native-control-process.ts';
import { SpawnNativeControlProcess } from './native-control-process.ts';
import {
  nativeBrokerCodec,
  NATIVE_BROKER_VERSION,
} from './native-broker-protocol.ts';
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
  readonly backend: NativeArmingBackend;
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
  readonly owner: ComputerOwner;
  /** Set before any acquire transport attempt. Never inferred from its response. */
  acquireSubmitted: boolean;
  helperGeneration?: string;
  revokeRunning?: Promise<NativeArmStatus>;
  process?: NativeControlProcess;
  phase: 'opening' | 'active' | 'closing' | 'fenced';
  busy: boolean;
  running: Promise<unknown>;
  cancel?: () => void;
  cleanup?: Promise<NativeCleanupResult>;
  processClose?: Promise<NativeCleanupResult>;
}
/** Proven host-side refusal before a private helper command can be submitted. */
class LocalNativeRefusal extends NativeControlError {}
const uncertainCleanup: NativeCleanupResult = Object.freeze({
  physicalSettlement: 'uncertain',
  heldInputsReleased: false,
  helperStopped: false,
});

class MacOSController implements NativeArmingBackend, NativeControl {
  readonly desktopIsolation = 'native-broker';
  readonly nativeControl: NativeControl = this;
  readonly nativeArming: NativeArming = {
    request: (lease, request, deadline) =>
      this.armRequest(lease, request, deadline),
    status: (lease, deadline) => this.armStatus(lease, deadline),
    revoke: (lease, deadline) => this.armRevoke(lease, deadline),
  };
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
      owner: checked.owner,
      acquireSubmitted: false,
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
      // Protocol 3 adds mandatory arming/revoke semantics. A read-only handshake
      // rejects older helpers before they can claim the global desktop domain.
      const preflight = await entry.process.request(
        { operation: 'preflight' },
        budget,
      );
      budget.requireActive();
      if (
        this.closed ||
        entry.phase !== 'opening' ||
        preflight.operation !== 'preflight' ||
        preflight.version !== NATIVE_BROKER_VERSION
      )
        throw new NativeControlError('UNSUPPORTED_ACTION');
      const configuration: NativeBrokerConfiguration = {
        ownershipDirectory: this.config.ownershipDirectory,
        target,
        control: this.config.control,
        maxWindows: this.config.maxWindows ?? 16,
        maxNodes: this.config.maxNodes ?? 200,
        maxVisitedNodes: this.config.maxVisitedNodes ?? 2000,
        cleanupBudgetMs: this.config.cleanupBudgetMs ?? 2000,
      };
      entry.acquireSubmitted = true;
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
      // Lost/invalid read-only handshake is not an uncertain input/acquisition.
      // Cancel publication first so a late preflight cannot proceed to acquire.
      const expired = budget.signal.aborted || budget.remainingMs() <= 0;
      budget.cancel();
      if (!entry.acquireSubmitted && entry.process !== undefined) {
        const cleanup = new OperationDeadline(
          this.config.cleanupBudgetMs ?? 2000,
        );
        try {
          const result = await this.cleanup(entry, cleanup);
          if (result.physicalSettlement === 'settled')
            return {
              outcome: 'NOT_ACQUIRED',
              code: expired ? 'DEADLINE_EXPIRED' : 'UNSUPPORTED_ACTION',
            };
        } finally {
          cleanup.dispose();
        }
      }
      entry.phase = 'fenced';
      entry.ownership.fence();
      return {
        outcome: 'UNCERTAIN',
        lease,
        code: expired ? 'DEADLINE_EXPIRED' : 'FENCED',
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
  private armReply(
    entry: LeaseEntry,
    reply: NativeBrokerReply,
    operation: 'arm_request' | 'arm_status' | 'arm_revoke',
    deadline: OperationDeadline,
    requestId?: string,
  ): NativeArmStatus {
    if (reply.operation === 'failure') {
      if (reply.result.physicalSettlement === 'uncertain') {
        entry.phase = 'fenced';
        entry.ownership.fence();
      }
      throw new NativeControlError(reply.result.code);
    }
    try {
      deadline.requireActive();
      if (reply.operation !== operation) throw new NativeControlError('FENCED');
      const status = decodeNativeArmStatus(reply.result),
        binding = status.binding;
      if (
        operation === 'arm_request' &&
        (status.state !== 'PENDING' || status.requestId !== requestId)
      )
        throw new NativeControlError('FENCED');
      if (operation === 'arm_revoke' && status.state !== 'REVOKED')
        throw new NativeControlError('FENCED');
      if (
        binding.lease.leaseId !== entry.lease.leaseId ||
        binding.lease.targetId !== entry.lease.targetId ||
        binding.lease.sessionId !== entry.lease.sessionId ||
        binding.lease.generation !== entry.lease.generation ||
        binding.helperGeneration === entry.lease.generation ||
        (entry.helperGeneration !== undefined &&
          binding.helperGeneration !== entry.helperGeneration)
      ) {
        entry.phase = 'fenced';
        entry.ownership.fence();
        throw new NativeControlError('FENCED');
      }
      entry.helperGeneration = binding.helperGeneration;
      return status;
    } catch (error) {
      entry.phase = 'fenced';
      entry.ownership.fence();
      throw error;
    }
  }
  private async armRequest(
    lease: NativeLease,
    request: NativeArmRequest,
    deadline: OperationDeadline,
  ): Promise<NativeArmStatus> {
    const checked = decodeNativeArmRequest(request),
      entry = this.owned(lease);
    const reply = await this.request(
      lease,
      {
        operation: 'arm_request',
        lease: entry.lease,
        owner: entry.owner,
        request: checked,
      },
      deadline,
    );
    return this.armReply(
      entry,
      reply,
      'arm_request',
      deadline,
      checked.requestId,
    );
  }
  private async armStatus(
    lease: NativeLease,
    deadline: OperationDeadline,
  ): Promise<NativeArmStatus> {
    const entry = this.owned(lease);
    const reply = await this.request(
      lease,
      { operation: 'arm_status', lease: entry.lease },
      deadline,
    );
    return this.armReply(entry, reply, 'arm_status', deadline);
  }
  private async armRevoke(
    lease: NativeLease,
    deadline: OperationDeadline,
  ): Promise<NativeArmStatus> {
    const entry = this.owned(lease);
    deadline.requireActive();
    if (entry.process === undefined || entry.phase === 'opening')
      throw new LocalNativeRefusal('NO_LEASE');
    if (entry.revokeRunning !== undefined)
      return awaitBrowserWork(entry.revokeRunning, deadline);
    if (entry.phase === 'fenced') throw new LocalNativeRefusal('FENCED');
    const process = entry.process;
    const work = Promise.resolve().then(async () => {
      deadline.requireActive();
      const reply = await process.revoke(entry.lease, deadline);
      return this.armReply(entry, reply, 'arm_revoke', deadline);
    });
    entry.revokeRunning = work;
    // A caller timeout is not physical command settlement. Keep the original
    // control promise attached until its own lifecycle actually settles.
    void work.then(
      () => {
        if (entry.revokeRunning === work) delete entry.revokeRunning;
      },
      () => {
        if (entry.revokeRunning === work) delete entry.revokeRunning;
      },
    );
    try {
      return await awaitBrowserWork(work, deadline);
    } catch (error) {
      entry.phase = 'fenced';
      entry.ownership.fence();
      throw error;
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
  private closeProcess(
    entry: LeaseEntry,
    deadline: OperationDeadline,
  ): Promise<NativeCleanupResult> {
    if (entry.processClose !== undefined) return entry.processClose;
    const process = entry.process;
    if (process === undefined) return Promise.resolve(uncertainCleanup);
    entry.processClose = Promise.resolve().then(() => process.close(deadline));
    return entry.processClose;
  }
  private async finishCleanup(
    entry: LeaseEntry,
    deadline: OperationDeadline,
  ): Promise<NativeCleanupResult> {
    const opening = entry.phase === 'opening';
    if (opening) entry.cancel?.();
    entry.phase = 'closing';
    if (entry.process === undefined) {
      entry.ownership.fence();
      entry.phase = 'fenced';
      return uncertainCleanup;
    }
    try {
      if (!entry.acquireSubmitted) {
        // Only preflight was allowed onto stdin. Actual child exit/reap plus
        // settlement proves there was no acquire, held input or global claim;
        // an old helper need not understand v3 stop/revoke to prove this case.
        const stopped = await awaitBrowserWork(
          this.closeProcess(entry, deadline),
          deadline,
        );
        await awaitBrowserWork(Promise.allSettled([entry.running]), deadline);
        if (!stopped.helperStopped)
          throw new NativeControlError('CLEANUP_UNCERTAIN');
        deadline.requireActive();
        await entry.ownership.releaseAfterQuiescence(deadline);
        this.entry = undefined;
        return {
          physicalSettlement: 'settled',
          heldInputsReleased: true,
          helperStopped: true,
        };
      }
      if (!opening) await this.armRevoke(entry.lease, deadline);
      await awaitBrowserWork(Promise.allSettled([entry.running]), deadline);
      const result = await awaitBrowserWork(
        this.closeProcess(entry, deadline),
        deadline,
      );
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
      // Even failed revocation/drain must initiate physical child shutdown once.
      // The process capability owns its finite kill/reap budget; keep its promise
      // and this durable fence when the caller's cleanup deadline is exhausted.
      entry.running = Promise.allSettled([
        entry.running,
        entry.revokeRunning ?? Promise.resolve(),
        this.closeProcess(entry, deadline),
      ]);
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
