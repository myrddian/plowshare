import {
  admitToolImage,
  imageToolResult,
  toolTextPart,
} from 'plowshare-client-node';
import type { ToolBinding, ToolCall, ToolResult } from 'plowshare-client-ts';
import type {
  CaptureLimits,
  ComputerOwner,
  SessionReference,
} from './contracts.ts';
import {
  decodeCapturedImage,
  decodeTargets,
  decodeOwner,
  decodeCloseArguments,
} from './contracts.ts';
import type { NativeArmingBackend } from './native-arming.ts';
import type { NativeArmStatus } from './native-arming.ts';
import {
  decodeNativeArmRequest,
  decodeNativeArmStatus,
} from './native-arming.ts';
import type { NativeHostArming } from './native-host-arming.ts';
import type {
  NativeActionResult,
  NativeControlBackend,
  NativeDelivery,
  NativeLease,
  NativeObservation,
  NativeSnapshot,
  NativeStateGuard,
  NativeState,
  NativeToolName,
  PreparedNativeAction,
} from './native-control.ts';
import {
  NativeControlError,
  decodeNativeActionResult,
  decodeNativeCleanupResult,
  decodeNativeAcquisition,
  decodePreparedNativeAction,
  decodeNativeObservation,
  decodeNativeWindows,
  sameNativeGuard,
} from './native-control.ts';
import type { NativeObserveArguments } from './native-tool-arguments.ts';
import {
  decodeNativeFocusArguments,
  decodeNativeKeyArguments,
  decodeNativePointerArguments,
  decodeNativeScrollArguments,
  decodeNativeSessionArguments,
  decodeNativeTypeArguments,
} from './native-tool-arguments.ts';
import {
  computerTextResult,
  validateComputerResult,
} from './computer-results.ts';
import { SessionError } from './sessions.ts';
import type { ComputerSession, ComputerSessions } from './sessions.ts';
import { NativeSessionReferences } from './native-session-references.ts';
import type { NativeObservationReference } from './native-session-references.ts';
import { DeadlineExceeded, OperationDeadline } from './deadline.ts';
import type { Clock } from './deadline.ts';

interface OwnedNative {
  readonly owner: ComputerOwner;
  readonly session: ComputerSession;
  readonly backend: NativeControlBackend;
  lease?: NativeLease;
  deadline?: OperationDeadline;
  running?: Promise<void>;
  cleanup?: Promise<void>;
  revoking?: Promise<NativeArmStatus>;
  helperGeneration?: string;
  publicationGeneration: number;
  publication?: {
    readonly generation: number;
    readonly controller: AbortController;
  };
  closing: boolean;
  provenEmpty?: boolean;
}
export interface NativeRuntimeOptions {
  readonly binding: ToolBinding;
  readonly sessions: ComputerSessions;
  readonly controls: readonly NativeControlBackend[];
  readonly armingControls?: readonly NativeArmingBackend[];
  readonly tools: readonly NativeToolName[];
  readonly clock: Clock;
  readonly origin: string;
  readonly currentBearer: () => Promise<string>;
  readonly http: typeof fetch;
  readonly captureLimits: CaptureLimits;
  readonly observationReserveMs: number;
  readonly cleanupBudgetMs: number;
  readonly idleCheckMs: number;
  readonly frameFreshMs?: number;
}
interface NativeMetadata {
  readonly version: 'computer-control/1';
  readonly invocationId: string;
  readonly session: ComputerSession;
  readonly actionOutcome: NativeDelivery['outcome'];
  readonly action: NativeDelivery | null;
  readonly observationOutcome: 'CAPTURED' | 'FAILED';
  readonly code?: string;
  readonly frame?: NativeObservationReference['frame'];
  readonly native?: NativeState;
  readonly snapshot?: NativeSnapshot | null;
  readonly image?: {
    readonly slot: 0;
    readonly imageId: string;
    readonly mimeType: string;
  };
  readonly truncated?: boolean;
}
function owner(call: ToolCall): ComputerOwner {
  return {
    account: call.account,
    project: call.project,
    provider: call.provider,
    run: call.run,
  };
}
function sameOwner(a: ComputerOwner, b: ComputerOwner): boolean {
  return (
    a.account === b.account &&
    a.project === b.project &&
    a.provider === b.provider &&
    a.run === b.run
  );
}
function code(error: unknown): string {
  return error instanceof NativeControlError || error instanceof SessionError
    ? error.code
    : error instanceof DeadlineExceeded
      ? 'DEADLINE_EXPIRED'
      : 'INPUT_OR_OBSERVATION_FAILED';
}
/** Waiters are bounded while the original promise remains owned for physical settlement. */
async function wait<T>(
  work: Promise<T>,
  deadline: OperationDeadline,
): Promise<T> {
  let detach = () => {};
  const expiry = new Promise<never>((_resolve, reject) => {
    const expired = () => reject(new DeadlineExceeded());
    deadline.signal.addEventListener('abort', expired, { once: true });
    detach = () => deadline.signal.removeEventListener('abort', expired);
    if (deadline.signal.aborted) expired();
  });
  try {
    const raced = Promise.race([work, expiry]);
    if (deadline.remainingMs() <= 0) deadline.cancel();
    const result = await raced;
    deadline.requireActive();
    return result;
  } finally {
    detach();
  }
}

function decodeCommand(
  name: Exclude<NativeToolName, 'windows'>,
  value: unknown,
) {
  switch (name) {
    case 'focus':
      return { kind: name, args: decodeNativeFocusArguments(value) };
    case 'pointer':
      return { kind: name, args: decodeNativePointerArguments(value) };
    case 'type':
      return { kind: name, args: decodeNativeTypeArguments(value) };
    case 'key':
      return { kind: name, args: decodeNativeKeyArguments(value) };
    case 'scroll':
      return { kind: name, args: decodeNativeScrollArguments(value) };
  }
}

/**
 * Provider-owned native lifecycle on the original SDK call boundary. Backend leases
 * and durable ownership markers remain the physical authority; this map pins their
 * cleanup capability even after a caller expires or the session becomes fenced.
 */
export class NativeToolRuntime {
  readonly nativeArming: NativeHostArming;
  private readonly entries = new Map<string, OwnedNative>();
  private readonly enabled: ReadonlySet<NativeToolName>;
  private readonly controls: ReadonlySet<NativeControlBackend>;
  private stopped = false;
  private readonly references: NativeSessionReferences;
  private maintenance: (() => void) | undefined;
  constructor(private readonly options: NativeRuntimeOptions) {
    this.references = new NativeSessionReferences({
      sessions: options.sessions,
      clock: options.clock,
      ...(options.frameFreshMs === undefined
        ? {}
        : { frameFreshMs: options.frameFreshMs }),
    });
    this.enabled = new Set(options.tools);
    this.controls = new Set(options.controls);
    const resolve = (
      rawOwner: ComputerOwner,
      rawReference: SessionReference,
    ) => {
      const owner = decodeOwner(rawOwner),
        reference = decodeCloseArguments(rawReference);
      const entry = this.entries.get(reference.sessionId);
      if (
        this.stopped ||
        owner.project !== options.binding.project ||
        owner.provider !== options.binding.provider ||
        entry === undefined ||
        !sameOwner(owner, entry.owner) ||
        entry.session.generation !== reference.generation ||
        entry.lease === undefined
      )
        throw new SessionError('NO_SESSION');
      const backend = options.armingControls?.find(
        (candidate) => candidate === entry.backend,
      );
      if (backend === undefined)
        throw new NativeControlError('CONTROL_DISABLED');
      return { entry, backend, lease: entry.lease };
    };
    this.nativeArming = Object.freeze({
      sessions: () =>
        Object.freeze(
          [...this.entries.values()]
            .filter(
              (entry) =>
                options.armingControls?.some(
                  (backend) => backend === entry.backend,
                ) === true,
            )
            .map((entry) =>
              Object.freeze({
                owner: Object.freeze({ ...entry.owner }),
                session: Object.freeze({
                  sessionId: entry.session.sessionId,
                  generation: entry.session.generation,
                }),
                targetId: entry.session.targetId,
                closing: entry.closing,
              }),
            ),
        ),
      request: async (owner, reference, rawRequest, deadline) => {
        const request = decodeNativeArmRequest(rawRequest);
        const { entry, backend, lease } = resolve(owner, reference);
        deadline.requireActive();
        if (entry.closing) throw new NativeControlError('FENCED');
        return this.armOperation(entry, deadline, async () => {
          this.references.invalidate(entry.owner, entry.session);
          const status = this.armStatus(
            entry,
            await backend.nativeArming.request(lease, request, deadline),
          );
          if (
            status.state !== 'PENDING' ||
            status.requestId !== request.requestId
          ) {
            this.fence(entry);
            throw new NativeControlError('CLEANUP_UNCERTAIN');
          }
          return status;
        });
      },
      status: async (owner, reference, deadline) => {
        const { entry, backend, lease } = resolve(owner, reference);
        if (entry.closing) throw new NativeControlError('FENCED');
        return this.armOperation(entry, deadline, async () =>
          this.armStatus(
            entry,
            await backend.nativeArming.status(lease, deadline),
          ),
        );
      },
      revoke: async (owner, reference, deadline) => {
        const { entry, backend, lease } = resolve(owner, reference);
        deadline.requireActive();
        if (entry.revoking !== undefined) return wait(entry.revoking, deadline);
        entry.publicationGeneration++;
        entry.publication?.controller.abort();
        this.references.invalidate(entry.owner, entry.session);
        // Do not join withEntry: revocation must reach the helper while an input
        // owns the ordinary lane. It does not assert physical input settlement.
        const work = (async () => {
          try {
            const value = await backend.nativeArming.revoke(lease, deadline);
            const status = this.armStatus(entry, value);
            if (status.state !== 'REVOKED') {
              this.fence(entry);
              throw new NativeControlError('CLEANUP_UNCERTAIN');
            }
            deadline.requireActive();
            if (this.stopped || entry.closing)
              throw new NativeControlError('FENCED');
            return status;
          } catch (error) {
            this.fence(entry);
            throw error;
          }
        })();
        entry.revoking = work;
        // Keep physical ownership even if this host waiter expires. Drain joins
        // the original promise; no second revocation is submitted for cleanup.
        const settled = () => {
          if (entry.revoking === work) delete entry.revoking;
        };
        // Both branches account only for physical completion. The original
        // rejection remains owned by the caller and cleanup waiter.
        void work.then(settled, settled);
        try {
          return await wait(work, deadline);
        } catch (error) {
          this.fence(entry);
          throw error;
        }
      },
    } satisfies NativeHostArming);
  }
  private async armOperation(
    entry: OwnedNative,
    deadline: OperationDeadline,
    operation: () => Promise<NativeArmStatus>,
  ): Promise<NativeArmStatus> {
    let started = false;
    const work = this.withEntry(entry, deadline, async () => {
      started = true;
      try {
        return await operation();
      } catch (error) {
        this.fence(entry);
        throw error;
      }
    });
    try {
      return await wait(work, deadline);
    } catch (error) {
      if (started) this.fence(entry);
      throw error;
    }
  }
  private assertPublication(entry: OwnedNative): void {
    if (
      entry.publication?.controller.signal.aborted ||
      (entry.publication !== undefined &&
        entry.publication.generation !== entry.publicationGeneration)
    )
      throw new NativeControlError('STALE_OBSERVATION');
  }
  private armStatus(entry: OwnedNative, value: unknown): NativeArmStatus {
    const status = decodeNativeArmStatus(value),
      actual = status.binding.lease,
      expected = entry.lease;
    if (
      expected === undefined ||
      actual.leaseId !== expected.leaseId ||
      actual.sessionId !== expected.sessionId ||
      actual.generation !== expected.generation ||
      actual.targetId !== expected.targetId ||
      (entry.helperGeneration !== undefined &&
        entry.helperGeneration !== status.binding.helperGeneration)
    ) {
      this.fence(entry);
      throw new NativeControlError('CLEANUP_UNCERTAIN');
    }
    entry.helperGeneration = status.binding.helperGeneration;
    return status;
  }
  controlsBackend(value: object): value is NativeControlBackend {
    return this.options.controls.some((backend) => backend === value);
  }
  hasSession(reference: SessionReference): boolean {
    return this.entries.has(reference.sessionId);
  }
  private entry(call: ToolCall, reference: SessionReference): OwnedNative {
    const entry = this.entries.get(reference.sessionId);
    if (
      call.project !== this.options.binding.project ||
      call.provider !== this.options.binding.provider ||
      entry === undefined ||
      entry.session.generation !== reference.generation ||
      !sameOwner(owner(call), entry.owner)
    )
      throw new SessionError('NO_SESSION');
    return entry;
  }
  private assertTool(entry: OwnedNative, name: NativeToolName): void {
    if (
      !this.enabled.has(name) ||
      !entry.backend.nativeControl.allowedTools.includes(name)
    )
      throw new NativeControlError('CONTROL_DISABLED');
  }
  private async current(
    entry: OwnedNative,
    deadline: OperationDeadline,
  ): Promise<void> {
    deadline.requireActive();
    if (this.stopped) throw new NativeControlError('FENCED');
    const targets = decodeTargets(await entry.backend.targets(deadline));
    deadline.requireActive();
    if (
      !targets.some(
        (target) =>
          target.targetId === entry.session.targetId &&
          target.backend === 'macos',
      )
    )
      throw new NativeControlError('TARGET_GONE');
    this.references.lease(entry.owner, entry.session);
  }
  private failure(
    call: ToolCall,
    error: unknown,
    uncertain: boolean,
    session?: ComputerSession,
  ): ToolResult {
    return computerTextResult(
      call,
      this.options.binding.account,
      {
        version: 'computer-control/1',
        invocationId: call.invocationId,
        ...(session === undefined ? {} : { session }),
        code: code(error),
        actionOutcome: uncertain ? 'UNCERTAIN' : 'NOT_DISPATCHED',
        observationOutcome: 'FAILED',
      },
      uncertain ? 'UNKNOWN' : 'REJECTED',
    );
  }
  private fence(entry: OwnedNative): void {
    // A concurrent stop may already have proved quiescence and removed this entry.
    if (!this.entries.has(entry.session.sessionId)) return;
    this.references.fence(entry.owner, entry.session);
    this.references.beginClose(entry.owner, entry.session);
    entry.closing = true;
    this.scheduleCleanup(entry);
  }
  private async withEntry<T>(
    entry: OwnedNative,
    deadline: OperationDeadline,
    work: () => Promise<T>,
  ): Promise<T> {
    // The underlying promise, not a timed-out SDK waiter, owns busy state and cleanup ordering.
    let resolve = () => {};
    const running = new Promise<void>((done) => {
      resolve = done;
    });
    if (entry.running !== undefined || entry.revoking !== undefined)
      throw new SessionError('BUSY');
    const publication = {
      generation: entry.publicationGeneration,
      controller: new AbortController(),
    };
    entry.publication = publication;
    entry.running = running;
    entry.deadline = deadline;
    try {
      const result = await this.options.sessions.withSession(
        entry.owner,
        entry.session,
        deadline,
        work,
      );
      this.assertPublication(entry);
      return result;
    } finally {
      delete entry.deadline;
      delete entry.running;
      if (entry.publication === publication) delete entry.publication;
      resolve();
      this.armMaintenance();
    }
  }
  private async cleanup(
    entry: OwnedNative,
    deadline: OperationDeadline,
  ): Promise<void> {
    if (entry.cleanup === undefined) {
      deadline.requireActive();
      const lease = entry.lease;
      if (lease === undefined)
        throw new NativeControlError('CLEANUP_UNCERTAIN');
      const running = entry.running;
      entry.cleanup = (async () => {
        const result = decodeNativeCleanupResult(
          await entry.backend.nativeControl.release(lease, deadline),
        );
        if (entry.revoking !== undefined)
          // The host already retains the original refusal/UNKNOWN. Cleanup needs
          // settlement plus its own helper-stop/release proof, not a late status.
          await wait(
            entry.revoking.then(
              () => undefined,
              () => undefined,
            ),
            deadline,
          );
        if (
          result.physicalSettlement !== 'settled' ||
          !result.heldInputsReleased ||
          !result.helperStopped
        )
          throw new NativeControlError('CLEANUP_UNCERTAIN');
        if (running !== undefined) await running;
        deadline.requireActive();
        this.references.releaseAfterQuiescence(entry.owner, entry.session);
        this.entries.delete(entry.session.sessionId);
        this.armMaintenance();
      })();
    }
    await wait(entry.cleanup, deadline);
  }
  private scheduleCleanup(entry: OwnedNative): void {
    entry.closing = true;
    this.armMaintenance();
    if (entry.cleanup !== undefined || entry.lease === undefined) return;
    const deadline = new OperationDeadline(
      this.options.cleanupBudgetMs,
      this.options.clock,
    );
    // A failed/late release remains memoized and fenced. Never retry removal or cleanup
    // under a fresh identity merely because this waiter expired.
    this.cleanup(entry, deadline).then(
      () => deadline.dispose(),
      () => deadline.dispose(),
    );
  }
  reap(): void {
    for (const pending of this.references.pendingCleanup()) {
      const entry = this.entries.get(pending.session.sessionId);
      if (entry !== undefined) {
        if (pending.reason === 'idle') entry.deadline?.cancel();
        this.scheduleCleanup(entry);
      }
    }
  }
  private armMaintenance(): void {
    const needed =
      !this.stopped &&
      [...this.entries.values()].some((entry) => !entry.closing);
    if (!needed) {
      this.maintenance?.();
      this.maintenance = undefined;
      return;
    }
    if (this.maintenance !== undefined) return;
    // Local bounded memory maintenance, not a server poll. Exact idle expiry is
    // still checked synchronously before every effect; cleanup scheduling may lag
    // by <=1s plus event-loop delay and retains physical ownership throughout.
    this.maintenance = this.options.clock.schedule(
      this.options.idleCheckMs,
      () => {
        this.maintenance = undefined;
        this.reap();
        this.armMaintenance();
      },
    );
  }
  stop(): void {
    this.stopped = true;
    this.maintenance?.();
    this.maintenance = undefined;
    for (const entry of this.entries.values()) {
      entry.deadline?.cancel();
      this.references.beginClose(entry.owner, entry.session);
      entry.closing = true;
      this.scheduleCleanup(entry);
    }
  }
  async drain(deadline: OperationDeadline): Promise<void> {
    deadline.requireActive();
    this.reap();
    for (const entry of [...this.entries.values()]) {
      if (!this.stopped && entry.cleanup === undefined) continue;
      deadline.requireActive();
      await this.cleanup(entry, deadline);
    }
    deadline.requireActive();
  }

  async open(
    call: ToolCall,
    targetId: string,
    backend: NativeControlBackend,
    deadline: OperationDeadline,
  ): Promise<ToolResult> {
    let entry: OwnedNative | undefined,
      attempted = false;
    try {
      if (
        call.project !== this.options.binding.project ||
        call.provider !== this.options.binding.provider ||
        this.stopped ||
        !this.controls.has(backend)
      )
        throw new NativeControlError('CONTROL_DISABLED');
      const session = this.references.open(owner(call), targetId);
      entry = {
        session,
        owner: owner(call),
        backend,
        closing: false,
        publicationGeneration: 0,
      };
      this.entries.set(session.sessionId, entry);
      this.armMaintenance();
      const held = entry;
      const result = computerTextResult(call, this.options.binding.account, {
        version: 'computer-control/1',
        session,
        controlled: true,
        backend: 'macos',
      });
      const acquisition = await this.withEntry(held, deadline, async () => {
        deadline.requireActive();
        attempted = true;
        const acquired = decodeNativeAcquisition(
          await backend.nativeControl.acquire(
            {
              owner: held.owner,
              session: {
                sessionId: session.sessionId,
                generation: session.generation,
              },
              targetId,
            },
            deadline,
          ),
        );
        if (acquired.outcome === 'NOT_ACQUIRED') held.provenEmpty = true;
        else if (acquired.lease !== null) {
          if (
            acquired.lease.targetId !== session.targetId ||
            acquired.lease.sessionId !== session.sessionId ||
            acquired.lease.generation !== session.generation
          )
            throw new NativeControlError('CLEANUP_UNCERTAIN');
          held.lease = acquired.lease;
          this.references.bindLease(held.owner, session, acquired.lease);
        }
        return acquired;
      });
      if (acquisition.outcome === 'NOT_ACQUIRED') {
        this.references.releaseAfterQuiescence(held.owner, held.session);
        this.entries.delete(session.sessionId);
        this.armMaintenance();
        return this.failure(
          call,
          new NativeControlError(acquisition.code),
          false,
          session,
        );
      }
      if (acquisition.outcome === 'UNCERTAIN') {
        this.fence(held);
        return this.failure(
          call,
          new NativeControlError(acquisition.code),
          true,
          session,
        );
      }
      return result;
    } catch (error) {
      if (entry !== undefined && (entry.provenEmpty || !attempted)) {
        this.references.releaseAfterQuiescence(entry.owner, entry.session);
        this.entries.delete(entry.session.sessionId);
        this.armMaintenance();
      } else if (entry !== undefined) this.fence(entry);
      return this.failure(
        call,
        error,
        attempted && entry?.provenEmpty !== true,
        entry?.session,
      );
    }
  }
  async close(
    call: ToolCall,
    reference: SessionReference,
    deadline: OperationDeadline,
  ): Promise<ToolResult> {
    let entry: OwnedNative | undefined;
    try {
      entry = this.entry(call, reference);
      this.references.beginClose(entry.owner, entry.session);
      entry.closing = true;
      this.armMaintenance();
      entry.deadline?.cancel();
      await this.cleanup(entry, deadline);
      deadline.requireActive();
      return computerTextResult(call, this.options.binding.account, {
        version: 'computer-control/1',
        closed: true,
      });
    } catch (error) {
      return this.failure(call, error, entry !== undefined, entry?.session);
    }
  }
  private async bearer(deadline: OperationDeadline): Promise<string> {
    const bearer = await this.options.currentBearer();
    deadline.requireActive();
    if (!bearer.trim() || /[\r\n]/u.test(bearer))
      throw new NativeControlError('CONTROL_DISABLED');
    return bearer;
  }
  private minimal(
    call: ToolCall,
    entry: OwnedNative,
    delivery: NativeDelivery | null,
    failureCode = 'OBSERVATION_FAILED',
    stateOverride?: ToolResult['state'],
  ): ToolResult {
    return computerTextResult(
      call,
      this.options.binding.account,
      {
        version: 'computer-control/1',
        invocationId: call.invocationId,
        session: entry.session,
        actionOutcome: delivery?.outcome ?? 'NOT_DISPATCHED',
        action: delivery,
        observationOutcome: 'FAILED',
        code: failureCode,
      },
      stateOverride ??
        (delivery?.outcome === 'UNCERTAIN'
          ? 'UNKNOWN'
          : delivery?.outcome === 'NOT_DISPATCHED'
            ? 'REJECTED'
            : 'COMPLETED'),
    );
  }
  private async publishObservation(
    call: ToolCall,
    entry: OwnedNative,
    value: NativeObservation,
    bearer: string,
    deadline: OperationDeadline,
    delivery: NativeDelivery | null,
  ): Promise<ToolResult> {
    this.assertPublication(entry);
    const observation = decodeNativeObservation(value),
      image = decodeCapturedImage(
        observation.image,
        this.options.captureLimits,
      );
    const boundedHttp: typeof fetch = (input, init) => {
      deadline.requireActive();
      this.assertPublication(entry);
      const publicationSignal = entry.publication?.controller.signal;
      return this.options.http(input, {
        ...init,
        signal: AbortSignal.any([
          deadline.signal,
          ...(init?.signal == null ? [] : [init.signal]),
          ...(publicationSignal === undefined ? [] : [publicationSignal]),
        ]),
      });
    };
    const admitted = await admitToolImage(
      this.options.origin,
      bearer,
      call,
      0,
      image.bytes,
      'OBSERVATION',
      boundedHttp,
    );
    deadline.requireActive();
    this.assertPublication(entry);
    if (
      admitted.receipt.width !== image.width ||
      admitted.receipt.height !== image.height ||
      admitted.receipt.mimeType !== image.mimeType ||
      admitted.receipt.producer !== this.options.binding.account
    )
      throw new NativeControlError('OBSERVATION_FAILED');
    let snapshot = observation.snapshot;

    // Prepare bounded visible metadata before committing refs. Only emitted nodes
    // become actionable; omitted backend handles never gain provider membership.
    const preview = {
      frameId: 'x'.repeat(256),
      surfaceId: observation.state.surfaceId,
      width: image.width,
      height: image.height,
      transform: image.transform,
      capturedAt: image.capturedAt,
      layoutGeneration: image.layoutGeneration,
    };
    const build = (
      frame: NativeObservationReference['frame'],
    ): NativeMetadata => ({
      version: 'computer-control/1',
      invocationId: call.invocationId,
      session: entry.session,
      actionOutcome: delivery?.outcome ?? 'NOT_DISPATCHED',
      action: delivery,
      observationOutcome: 'CAPTURED',
      frame,
      snapshot,
      native: observation.state,
      image: {
        slot: 0,
        imageId: admitted.receipt.imageId,
        mimeType: image.mimeType,
      },
      truncated: snapshot?.truncated === true,
    });
    const encode = (metadata: NativeMetadata) => {
      const text = JSON.stringify(metadata);
      if (text.length > 12000)
        throw new NativeControlError('OBSERVATION_FAILED');
      return validateComputerResult(
        call,
        imageToolResult(
          call,
          [toolTextPart(text), admitted.part],
          [admitted.receipt],
        ),
        this.options.binding.account,
      );
    };
    for (;;) {
      deadline.requireActive();
      try {
        encode(build(preview));
        break;
      } catch (error) {
        if (snapshot !== null && snapshot.nodes.length > 0) {
          const root = snapshot.rootElementId;
          const parents = new Set(
            snapshot.nodes.map((node) => node.parentElementId),
          );
          const removable = snapshot.nodes.findLastIndex(
            (node) => node.elementId !== root && !parents.has(node.elementId),
          );
          if (removable < 0) {
            throw error;
          }
          snapshot = {
            ...snapshot,
            nodes: snapshot.nodes.filter((_node, index) => index !== removable),
            truncated: true,
          };
          continue;
        }
        throw error;
      }
    }
    deadline.requireActive();
    const recorded = this.references.recordObservation(
      entry.owner,
      entry.session,
      { ...observation, image, snapshot },
    );
    return encode(build(recorded.frame));
  }
  async observe(
    call: ToolCall,
    args: NativeObserveArguments,
    deadline: OperationDeadline,
  ): Promise<ToolResult> {
    let started = false,
      captureSettled = false,
      entry: OwnedNative | undefined;
    try {
      entry = this.entry(call, args);
      const held = entry;
      return await this.withEntry(held, deadline, async () => {
        await this.current(held, deadline);
        // Renewal can outlive a tree reference; recheck the root after that wait.
        const bearer = await this.bearer(deadline);
        this.assertPublication(held);
        if (args.root !== undefined)
          this.references.resolveElement(held.owner, held.session, args.root);
        this.options.sessions.reserveImage(held.owner);
        started = true;
        const observed = await held.backend.nativeControl.observe(
          this.references.lease(held.owner, held.session),
          {
            surfaceId: args.surfaceId,
            structured: args.structured,
            ...(args.root === undefined ? {} : { root: args.root }),
          },
          deadline,
        );
        captureSettled = true;
        if (args.structured && observed.snapshot === null)
          throw new NativeControlError('OBSERVATION_FAILED');
        return this.publishObservation(
          call,
          held,
          observed,
          bearer,
          deadline,
          null,
        );
      });
    } catch (error) {
      if (entry !== undefined && started && !captureSettled) this.fence(entry);
      return this.failure(call, error, started, entry?.session);
    }
  }

  async windows(
    call: ToolCall,
    deadline: OperationDeadline,
  ): Promise<ToolResult> {
    try {
      const args = decodeNativeSessionArguments(call.arguments),
        entry = this.entry(call, args);
      this.assertTool(entry, 'windows');
      return await this.withEntry(entry, deadline, async () => {
        await this.current(entry, deadline);
        let windows = decodeNativeWindows(
          await entry.backend.nativeControl.windows(
            this.references.lease(entry.owner, entry.session),
            deadline,
          ),
        );
        for (;;) {
          try {
            const result = computerTextResult(
              call,
              this.options.binding.account,
              {
                version: 'computer-control/1',
                session: entry.session,
                windows,
              },
            );
            this.assertPublication(entry);
            this.references.recordWindows(entry.owner, entry.session, windows);
            return result;
          } catch (error) {
            if (windows.windows.length === 0) throw error;
            windows = {
              ...windows,
              windows: windows.windows.slice(0, -1),
              truncated: true,
            };
          }
        }
      });
    } catch (error) {
      return this.failure(call, error, false);
    }
  }

  async action(
    call: ToolCall,
    name: Exclude<NativeToolName, 'windows'>,
    deadline: OperationDeadline,
  ): Promise<ToolResult> {
    let entry: OwnedNative | undefined,
      attempted = false;
    let outcome: NativeActionResult | undefined;
    try {
      const sessions = this.references;
      let expected: NativeStateGuard | undefined;
      let action: PreparedNativeAction | undefined;
      let focus:
        { readonly windowsId: string; readonly surfaceId: string } | undefined;
      // Decode once at the boundary, before resolving or acting on any target.
      const command = decodeCommand(name, call.arguments);
      entry = this.entry(call, command.args);
      const held = entry;
      this.assertTool(held, name);
      return await this.withEntry(held, deadline, async () => {
        await this.current(held, deadline);
        // Credential renewal can wait. Resolve freshness-sensitive references only
        // after it settles, immediately before the synchronous input preflight.
        const bearer = await this.bearer(deadline);
        this.assertPublication(held);
        const prepare = (
          target: Parameters<NativeSessionReferences['prepareTarget']>[2],
        ) => sessions.prepareTarget(held.owner, held.session, target);
        if (command.kind === 'focus') {
          focus = {
            windowsId: command.args.windowsId,
            surfaceId: command.args.surfaceId,
          };
          sessions.resolveWindow(
            held.owner,
            held.session,
            focus.windowsId,
            focus.surfaceId,
          );
        } else if (command.kind === 'pointer') {
          const args = command.args,
            gesture = args.action;

          if (gesture.kind === 'drag') {
            expected = sessions.resolveFrame(
              held.owner,
              held.session,
              gesture.frameId,
            );
            const points = gesture.points
              .map((point) => {
                const prepared = prepare({
                  kind: 'point',
                  frameId: gesture.frameId,
                  x: point.x,
                  y: point.y,
                });
                if (
                  !sameNativeGuard(
                    expected ?? prepared.expected,
                    prepared.expected,
                  ) ||
                  prepared.target.kind !== 'point'
                )
                  throw new NativeControlError('STALE_OBSERVATION');
                return { ...prepared.target, atMs: point.atMs };
              })
              .map(({ x, y, atMs }) => ({ x, y, atMs }));
            action = {
              kind: 'drag',
              points,
              button: gesture.button,
              modifiers: gesture.modifiers,
            };
          } else {
            const prepared = prepare(gesture.target);
            expected = prepared.expected;
            action =
              gesture.kind === 'move'
                ? { kind: 'move', target: prepared.target }
                : { ...gesture, target: prepared.target };
          }
        } else if (command.kind === 'type') {
          const args = command.args,
            prepared = prepare(args.action.target);
          const element = sessions.resolveElement(
            held.owner,
            held.session,
            args.action.target,
          );
          if (element.secure) throw new NativeControlError('SENSITIVE_TARGET');
          if (!element.editable || !element.enabled)
            throw new NativeControlError('NOT_EDITABLE');
          if (!element.focused) throw new NativeControlError('FOCUS_MISMATCH');
          expected = prepared.expected;
          action = args.action;
        } else if (command.kind === 'key') {
          const args = command.args;
          expected = sessions.resolveFrame(
            held.owner,
            held.session,
            args.frameId,
          );
          if (args.action.expectedFocus !== null) {
            const focused = prepare(args.action.expectedFocus);
            if (!sameNativeGuard(expected, focused.expected))
              throw new NativeControlError('STALE_OBSERVATION');
            const element = sessions.resolveElement(
              held.owner,
              held.session,
              args.action.expectedFocus,
            );
            if (element.secure)
              throw new NativeControlError('SENSITIVE_TARGET');
          }
          action = args.action;
        } else if (command.kind === 'scroll') {
          const args = command.args,
            prepared = prepare(args.target);
          expected = prepared.expected;
          action = {
            kind: 'scroll',
            target: prepared.target,
            dx: args.dx,
            dy: args.dy,
            units: args.units,
          };
        }
        if (action !== undefined) action = decodePreparedNativeAction(action);
        // Reserve a known-valid minimal result and image occurrence BEFORE any input.
        this.minimal(call, held, {
          outcome: 'DISPATCHED',
          physicalSettlement: 'settled',
          mechanism: 'cg-event',
        });
        this.minimal(call, held, {
          outcome: 'UNCERTAIN',
          physicalSettlement: 'uncertain',
          code: 'INPUT_FAILED',
        });
        this.minimal(
          call,
          held,
          {
            outcome: 'PARTIAL',
            physicalSettlement: 'settled',
            delivered: {
              kind: 'type',
              dispatchedUtf16Units: 4096,
              cleared: true,
            },
            code: 'OBSERVATION_FAILED',
          },
          'X'.repeat(64),
        );
        deadline.requireActive();
        if (deadline.remainingMs() <= this.options.observationReserveMs)
          throw new DeadlineExceeded();
        this.options.sessions.reserveImage(held.owner);
        const lease = sessions.lease(held.owner, held.session);
        sessions.invalidate(held.owner, held.session);
        attempted = true;
        const control = held.backend.nativeControl;
        let raw: NativeActionResult;
        if (focus !== undefined)
          raw = await control.focus(
            lease,
            {
              invocationId: call.invocationId,
              windowsId: focus.windowsId,
              surfaceId: focus.surfaceId,
              observationReserveMs: this.options.observationReserveMs,
            },
            deadline,
          );
        else if (action !== undefined && expected !== undefined)
          raw = await control.perform(
            lease,
            {
              invocationId: call.invocationId,
              expected,
              action,
              observationReserveMs: this.options.observationReserveMs,
            },
            deadline,
          );
        else throw new NativeControlError('INVALID_ARGUMENT');
        const result = decodeNativeActionResult(raw);
        outcome = result;
        if (result.delivery.outcome === 'UNCERTAIN') {
          this.fence(held);
          return this.minimal(
            call,
            held,
            result.delivery,
            result.delivery.code,
          );
        }
        if (result.delivery.outcome === 'NOT_DISPATCHED')
          return this.minimal(
            call,
            held,
            result.delivery,
            result.delivery.code,
          );
        if (result.observation.state === 'FAILED')
          return this.minimal(
            call,
            held,
            result.delivery,
            result.observation.code,
          );
        try {
          return await this.publishObservation(
            call,
            held,
            result.observation.value,
            bearer,
            deadline,
            result.delivery,
          );
        } catch (error) {
          // Established input is never retyped/recaptured after binary admission or
          // optional metadata failure. The original admission slot remains uncertain.
          deadline.requireActive();
          return this.minimal(call, held, result.delivery, code(error));
        }
      });
    } catch (error) {
      if (entry !== undefined && attempted) this.fence(entry);
      if (entry !== undefined && outcome !== undefined) {
        const failure =
          outcome.delivery.outcome === 'UNCERTAIN' ||
          outcome.delivery.outcome === 'NOT_DISPATCHED'
            ? outcome.delivery.code
            : code(error);
        // No late success/image disclosure. Keep established local delivery proof
        // even when the SDK deadline can only retain an immutable UNKNOWN receipt.
        const revokedPublication =
          error instanceof NativeControlError &&
          error.code === 'STALE_OBSERVATION' &&
          !deadline.signal.aborted &&
          deadline.remainingMs() > 0;
        return this.minimal(
          call,
          entry,
          outcome.delivery,
          failure,
          revokedPublication ? undefined : 'UNKNOWN',
        );
      }
      return this.failure(call, error, attempted, entry?.session);
    }
  }
}
