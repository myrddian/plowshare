import type { ComputerOwner, SessionReference } from './contracts.ts';
import { computerId, decodeOwner } from './contracts.ts';
import type {
  NativeActionTarget,
  NativeElement,
  NativeElementTarget,
  NativeLease,
  NativeObservation,
  NativeSnapshot,
  NativeState,
  NativeStateGuard,
  NativeWindow,
  NativeWindows,
  PreparedNativeTarget,
} from './native-control.ts';
import {
  decodeNativeActionTarget,
  decodeNativeLease,
  decodeNativeObservation,
  decodeNativeWindows,
  requireReadyNativeState,
} from './native-control.ts';
import type { Clock } from './deadline.ts';
import { positiveBudget, systemClock } from './deadline.ts';
import { framePoint } from './geometry.ts';
import { SessionError } from './sessions.ts';
import type {
  ComputerSessions,
  ComputerSession,
  FrameReference,
} from './sessions.ts';

export interface NativeObservationReference {
  readonly frame: FrameReference;
  readonly state: NativeState;
  readonly snapshot: NativeSnapshot | null;
}
export interface PreparedNativeSessionTarget {
  readonly expected: NativeStateGuard;
  readonly target: PreparedNativeTarget;
}
export interface NativeCleanupObligation {
  readonly owner: ComputerOwner;
  readonly session: ComputerSession;
  readonly lease: NativeLease | null;
  readonly busy: boolean;
  readonly reason: 'closing' | 'idle' | 'fenced';
}
export interface NativeReferenceOptions {
  readonly sessions: ComputerSessions;
  readonly clock?: Clock;
  /** Must match the common ComputerSessions frameFreshMs setting. */
  readonly frameFreshMs?: number;
}
interface Entry {
  readonly owner: ComputerOwner;
  readonly session: ComputerSession;
  lease?: NativeLease;
  observation?: {
    readonly value: NativeObservationReference;
    readonly expiresAt: number;
  };
  windows?: { readonly value: NativeWindows; readonly expiresAt: number };
}
function sameOwner(a: ComputerOwner, b: ComputerOwner): boolean {
  return (
    a.account === b.account &&
    a.project === b.project &&
    a.run === b.run &&
    a.provider === b.provider
  );
}

/**
 * Native metadata is separate from browser state/leases. Common sessions own
 * exclusivity, physical withSession occupancy, expiry and cleanup obligations.
 * This companion retains only bounded model-visible native references, never
 * pixels or raw AX handles. Hosts must release native sessions through this
 * companion after backend quiescence, not remove the common session out-of-band.
 */
export class NativeSessionReferences {
  private readonly entries = new Map<string, Entry>();
  private readonly clock: Clock;
  private readonly freshMs: number;
  constructor(private readonly options: NativeReferenceOptions) {
    this.clock = options.clock ?? systemClock;
    this.freshMs = positiveBudget(options.frameFreshMs ?? 5000);
  }
  open(owner: ComputerOwner, targetId: string): ComputerSession {
    if (this.entries.size >= 128) throw new SessionError('BUSY');
    const checked = decodeOwner(owner),
      session = this.options.sessions.openControlled(checked, targetId);
    this.entries.set(session.sessionId, { owner: checked, session });
    return session;
  }
  hasSession(reference: SessionReference): boolean {
    const entry = this.entries.get(reference.sessionId);
    return (
      entry !== undefined && entry.session.generation === reference.generation
    );
  }
  require(owner: ComputerOwner, reference: SessionReference): ComputerSession {
    const entry = this.entry(owner, reference);
    return this.options.sessions.require(entry.owner, entry.session);
  }
  /** Retain late acquisition evidence for cleanup without clearing the common fence. */
  bindLease(
    owner: ComputerOwner,
    reference: SessionReference,
    value: NativeLease,
  ): void {
    const entry = this.entry(owner, reference),
      lease = decodeNativeLease(value);
    if (
      lease.sessionId !== entry.session.sessionId ||
      lease.generation !== entry.session.generation ||
      lease.targetId !== entry.session.targetId
    )
      throw new SessionError('NO_LEASE');
    if (entry.lease !== undefined) throw new SessionError('BUSY');
    entry.lease = lease;
  }
  lease(owner: ComputerOwner, reference: SessionReference): NativeLease {
    this.require(owner, reference);
    const lease = this.entry(owner, reference).lease;
    if (lease === undefined) throw new SessionError('NO_LEASE');
    return lease;
  }
  /** Host-only cleanup capability; null never proves that acquisition/resources stopped. */
  cleanupLease(
    owner: ComputerOwner,
    reference: SessionReference,
  ): NativeLease | null {
    return this.entry(owner, reference).lease ?? null;
  }
  beginClose(
    owner: ComputerOwner,
    reference: SessionReference,
  ): NativeCleanupObligation {
    const entry = this.entry(owner, reference),
      pending = this.options.sessions.beginClose(entry.owner, entry.session);
    this.clear(entry);
    return Object.freeze({
      owner: entry.owner,
      session: entry.session,
      lease: entry.lease ?? null,
      busy: pending.busy,
      reason: pending.reason,
    });
  }
  pendingCleanup(): readonly NativeCleanupObligation[] {
    const pending: NativeCleanupObligation[] = [];
    for (const obligation of this.options.sessions.pendingCleanup()) {
      const entry = this.entries.get(obligation.session.sessionId);
      if (entry === undefined) continue;
      this.clear(entry);
      pending.push(
        Object.freeze({
          owner: entry.owner,
          session: entry.session,
          lease: entry.lease ?? null,
          busy: obligation.busy,
          reason: obligation.reason,
        }),
      );
    }
    return Object.freeze(pending);
  }
  fence(owner: ComputerOwner, reference: SessionReference): void {
    const entry = this.entry(owner, reference);
    this.options.sessions.fence(entry.owner, entry.session);
    this.clear(entry);
  }
  /** Only explicit proven NOT_ACQUIRED or backend quiescence authorizes this host-only removal. */
  releaseAfterQuiescence(
    owner: ComputerOwner,
    reference: SessionReference,
  ): void {
    const entry = this.entry(owner, reference);
    this.options.sessions.releaseAfterQuiescence(entry.owner, entry.session);
    this.entries.delete(entry.session.sessionId);
  }
  recordObservation(
    owner: ComputerOwner,
    reference: SessionReference,
    value: NativeObservation,
  ): NativeObservationReference {
    const entry = this.active(owner, reference),
      observed = decodeNativeObservation(value),
      now = this.clock.monotonicNow();
    const remaining = Math.min(
      this.remaining(observed.image.capturedAt),
      observed.snapshot === null
        ? this.freshMs
        : this.remaining(observed.snapshot.capturedAt),
    );
    const frame = this.options.sessions.recordFrame(
      entry.owner,
      entry.session,
      observed.state.surfaceId,
      observed.image,
    );
    const recorded = Object.freeze({
      frame,
      state: observed.state,
      snapshot: observed.snapshot,
    });
    entry.observation = { value: recorded, expiresAt: now + remaining };
    return recorded;
  }
  resolveFrame(
    owner: ComputerOwner,
    reference: SessionReference,
    frameId: string,
  ): NativeStateGuard {
    const entry = this.active(owner, reference),
      observed = this.observation(entry);
    this.options.sessions.checkFrame(
      entry.owner,
      entry.session,
      frameId,
      observed.state.layoutGeneration,
    );
    requireReadyNativeState(observed.state);
    return this.guard(observed.state);
  }
  /** Read-only subtree lookup may operate without asserting input readiness. */
  resolveElement(
    owner: ComputerOwner,
    reference: SessionReference,
    value: NativeElementTarget,
  ): NativeElement {
    const target = decodeNativeActionTarget(value),
      observed = this.observation(this.active(owner, reference));
    if (
      target.kind !== 'element' ||
      observed.snapshot === null ||
      observed.snapshot.snapshotId !== target.snapshotId
    )
      throw new SessionError('STALE_OBSERVATION');
    const element = observed.snapshot.nodes.find(
      (node) => node.elementId === target.elementId,
    );
    if (element === undefined) throw new SessionError('STALE_OBSERVATION');
    return element;
  }
  prepareTarget(
    owner: ComputerOwner,
    reference: SessionReference,
    value: NativeActionTarget,
  ): PreparedNativeSessionTarget {
    const entry = this.active(owner, reference),
      target = decodeNativeActionTarget(value),
      observed = this.observation(entry);
    requireReadyNativeState(observed.state);
    const expected = this.guard(observed.state);
    if (target.kind === 'element') {
      this.resolveElement(owner, reference, target);
      return Object.freeze({ expected, target });
    }
    this.options.sessions.checkFrame(
      entry.owner,
      entry.session,
      target.frameId,
      observed.state.layoutGeneration,
    );
    const point = framePoint(
        observed.frame.transform,
        observed.frame.width,
        observed.frame.height,
        target.x,
        target.y,
      ),
      bounds = observed.state.display.bounds;
    if (
      Math.abs(point.x) > 1e7 ||
      Math.abs(point.y) > 1e7 ||
      point.x < bounds.x ||
      point.x > bounds.x + bounds.width ||
      point.y < bounds.y ||
      point.y > bounds.y + bounds.height
    )
      throw new SessionError('STALE_OBSERVATION');
    return Object.freeze({
      expected,
      target: Object.freeze({ kind: 'point', ...point }),
    });
  }
  recordWindows(
    owner: ComputerOwner,
    reference: SessionReference,
    value: NativeWindows,
  ): NativeWindows {
    const entry = this.active(owner, reference),
      windows = decodeNativeWindows(value),
      remaining = this.remaining(windows.capturedAt);
    entry.windows = {
      value: windows,
      expiresAt: this.clock.monotonicNow() + remaining,
    };
    return windows;
  }
  resolveWindow(
    owner: ComputerOwner,
    reference: SessionReference,
    windowsId: string,
    surfaceId: string,
  ): NativeWindow {
    const windows = this.active(owner, reference).windows;
    if (
      windows === undefined ||
      windows.value.windowsId !== computerId(windowsId) ||
      windows.expiresAt <= this.clock.monotonicNow()
    )
      throw new SessionError('STALE_OBSERVATION');
    const window = windows.value.windows.find(
      (window) => window.surfaceId === computerId(surfaceId),
    );
    if (window === undefined || window.state !== 'available')
      throw new SessionError('STALE_OBSERVATION');
    return window;
  }
  /** Invalidate before dispatch; a failed post-observation must not resurrect prior references. */
  invalidate(owner: ComputerOwner, reference: SessionReference): void {
    this.clear(this.entry(owner, reference));
  }
  private entry(owner: ComputerOwner, reference: SessionReference): Entry {
    const checked = decodeOwner(owner),
      sessionId = computerId(reference.sessionId),
      generation = computerId(reference.generation),
      entry = this.entries.get(sessionId);
    if (
      entry === undefined ||
      entry.session.generation !== generation ||
      !sameOwner(checked, entry.owner)
    )
      throw new SessionError('NO_SESSION');
    return entry;
  }
  private active(owner: ComputerOwner, reference: SessionReference): Entry {
    this.require(owner, reference);
    const entry = this.entry(owner, reference);
    if (entry.lease === undefined) throw new SessionError('NO_LEASE');
    return entry;
  }
  private observation(entry: Entry): NativeObservationReference {
    const observed = entry.observation;
    if (
      observed === undefined ||
      observed.expiresAt <= this.clock.monotonicNow()
    )
      throw new SessionError('STALE_OBSERVATION');
    this.options.sessions.checkFrame(
      entry.owner,
      entry.session,
      observed.value.frame.frameId,
      observed.value.state.layoutGeneration,
    );
    return observed.value;
  }
  private remaining(capturedAt: string): number {
    const age = this.clock.wallNow() - Date.parse(capturedAt);
    if (!Number.isFinite(age) || age < 0 || age >= this.freshMs)
      throw new SessionError('STALE_OBSERVATION');
    return this.freshMs - age;
  }
  private guard(state: NativeStateGuard): NativeStateGuard {
    return Object.freeze({
      surfaceId: state.surfaceId,
      applicationId: state.applicationId,
      windowId: state.windowId,
      layoutGeneration: state.layoutGeneration,
      focusGeneration: state.focusGeneration,
      displayGeneration: state.displayGeneration,
      interactionGeneration: state.interactionGeneration,
    });
  }
  private clear(entry: Entry): void {
    delete entry.observation;
    delete entry.windows;
  }
}
