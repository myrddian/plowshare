import type {
  CaptureLimits,
  CapturedImage,
  ComputerOwner,
  SessionReference,
} from './contracts.ts';
import {
  computerId,
  BackendError,
  decodeCapturedImage,
  decodeOwner,
  DEFAULT_CAPTURE_LIMITS,
  validateCaptureLimits,
} from './contracts.ts';
import type {
  BrowserActionTarget,
  BrowserLease,
  BrowserObservation,
  BrowserSnapshot,
  BrowserState,
  BrowserStateGuard,
  BrowserWindows,
  PreparedBrowserTarget,
} from './browser-control.ts';
import {
  decodeBrowserActionTarget,
  decodeBrowserLease,
  decodeBrowserObservation,
  decodeBrowserWindows,
} from './browser-control.ts';
import type { Clock, OperationDeadline } from './deadline.ts';
import { positiveBudget, systemClock } from './deadline.ts';
import type { AffineTransform } from './geometry.ts';
import { framePoint } from './geometry.ts';

export type SessionErrorCode =
  | 'NO_SESSION'
  | 'BUSY'
  | 'FENCED'
  | 'STALE_OBSERVATION'
  | 'FRAME_LIMIT'
  | 'CLEANUP_REQUIRED'
  | 'NO_LEASE';
export class SessionError extends Error {
  constructor(readonly code: SessionErrorCode) {
    super(code);
    this.name = 'SessionError';
  }
}

export interface ComputerSession extends SessionReference {
  readonly targetId: string;
}
export interface FrameReference {
  readonly frameId: string;
  readonly surfaceId: string;
  readonly width: number;
  readonly height: number;
  readonly transform: AffineTransform;
  readonly capturedAt: string;
  readonly layoutGeneration: string;
}
export interface SessionOptions {
  readonly generation: string;
  readonly id: () => string;
  readonly clock?: Clock;
  readonly idleMs?: number;
  readonly frameFreshMs?: number;
  readonly maxSessions?: number;
  readonly maxRuns?: number;
  readonly maxImagesPerRun?: number;
  readonly captureLimits?: CaptureLimits;
}
export interface BrowserObservationReference {
  readonly frame: FrameReference;
  readonly state: BrowserState;
  readonly snapshot: BrowserSnapshot | null;
}
export interface PreparedBrowserSessionTarget {
  readonly expected: BrowserStateGuard;
  readonly target: PreparedBrowserTarget;
}
export interface BrowserCleanupObligation {
  readonly owner: ComputerOwner;
  readonly session: ComputerSession;
  readonly lease: BrowserLease | null;
  readonly busy: boolean;
  readonly reason: 'closing' | 'idle' | 'fenced';
}
interface ControlledSession {
  lease?: BrowserLease;
  observation?: {
    readonly value: BrowserObservationReference;
    readonly expiresAt: number;
  };
  windows?: { readonly value: BrowserWindows; readonly expiresAt: number };
  cleanupReason?: BrowserCleanupObligation['reason'];
}
interface HeldSession {
  readonly handle: ComputerSession;
  readonly owner: ComputerOwner;
  controlled?: ControlledSession;
  idleAt: number;
  busy: boolean;
  closing: boolean;
  fenced: boolean;
  frame?: { readonly value: FrameReference; readonly expiresAt: number };
}

function ownerKey(owner: ComputerOwner): string {
  return JSON.stringify([
    owner.account,
    owner.project,
    owner.run,
    owner.provider,
  ]);
}
function boundedCount(value: number, max: number): number {
  if (!Number.isSafeInteger(value) || value < 1 || value > max)
    throw new Error('Invalid session capacity');
  return value;
}

/**
 * Bounded process-local session state. Native OS locks remain the backend host's responsibility.
 * `withSession` holds occupancy until its actual promise settles, even when an SDK caller times out.
 */
export class ComputerSessions {
  private readonly generation: string;
  private readonly clock: Clock;
  private readonly idleMs: number;
  private readonly frameFreshMs: number;
  private readonly maxSessions: number;
  private readonly maxRuns: number;
  private readonly maxImages: number;
  private readonly captureLimits: CaptureLimits;
  private readonly sessions = new Map<string, HeldSession>();
  private readonly targets = new Map<string, string>();
  private readonly runImages = new Map<string, number>();

  constructor(private readonly options: SessionOptions) {
    this.generation = computerId(options.generation);
    this.clock = options.clock ?? systemClock;
    this.idleMs = positiveBudget(options.idleMs ?? 60_000);
    this.frameFreshMs = positiveBudget(options.frameFreshMs ?? 5_000);
    this.maxSessions = boundedCount(options.maxSessions ?? 32, 128);
    this.maxRuns = boundedCount(options.maxRuns ?? 128, 1000);
    this.maxImages = boundedCount(options.maxImagesPerRun ?? 8, 16);
    this.captureLimits = validateCaptureLimits(
      options.captureLimits ?? DEFAULT_CAPTURE_LIMITS,
    );
  }

  open(owner: ComputerOwner, targetId: string): ComputerSession {
    const checkedOwner = decodeOwner(owner);
    const checkedTarget = computerId(targetId);
    this.expireIdle();
    if (
      this.targets.has(checkedTarget) ||
      this.sessions.size >= this.maxSessions
    )
      throw new SessionError('BUSY');
    const sessionId = computerId(this.options.id());
    if (this.sessions.has(sessionId))
      throw new Error('Session identity collision');
    const handle = Object.freeze({
      sessionId,
      generation: this.generation,
      targetId: checkedTarget,
    });
    this.sessions.set(sessionId, {
      handle,
      owner: checkedOwner,
      idleAt: this.clock.monotonicNow() + this.idleMs,
      busy: false,
      closing: false,
      fenced: false,
    });
    this.targets.set(checkedTarget, sessionId);
    return handle;
  }

  require(owner: ComputerOwner, reference: SessionReference): ComputerSession {
    const held = this.held(owner, reference);
    if (held.fenced || held.closing) throw new SessionError('FENCED');
    return held.handle;
  }

  /** Controlled ownership cannot expire/remove until the host proves backend quiescence. */
  openControlled(owner: ComputerOwner, targetId: string): ComputerSession {
    const handle = this.open(owner, targetId),
      held = this.sessions.get(handle.sessionId);
    if (held === undefined) throw new Error('Missing newly opened session');
    held.controlled = {};
    return handle;
  }

  /** A late acquire capability may be retained for cleanup, but never clears closing/fencing. */
  bindBrowserLease(
    owner: ComputerOwner,
    reference: SessionReference,
    lease: BrowserLease,
  ): void {
    const { held, controlled } = this.browser(owner, reference, false),
      checked = decodeBrowserLease(lease);
    if (
      checked.sessionId !== held.handle.sessionId ||
      checked.generation !== held.handle.generation ||
      checked.targetId !== held.handle.targetId
    )
      throw new SessionError('NO_LEASE');
    if (controlled.lease !== undefined) throw new SessionError('BUSY');
    controlled.lease = checked;
  }

  browserLease(
    owner: ComputerOwner,
    reference: SessionReference,
  ): BrowserLease {
    const { controlled } = this.browser(owner, reference);
    if (controlled.lease === undefined) throw new SessionError('NO_LEASE');
    return controlled.lease;
  }

  /** Stops intake without releasing occupancy. A missing lease is NOT proof that acquire stopped. */
  beginClose(
    owner: ComputerOwner,
    reference: SessionReference,
  ): BrowserCleanupObligation {
    const { held, controlled } = this.browser(owner, reference, false);
    held.closing = true;
    controlled.cleanupReason ??= held.fenced ? 'fenced' : 'closing';
    this.clearBrowserReferences(held);
    return this.cleanupObligation(held, controlled);
  }

  /** Host-only bounded cleanup obligations. Listing never releases or retries an uncertain backend. */
  pendingCleanup(): readonly BrowserCleanupObligation[] {
    this.expireIdle();
    const pending: BrowserCleanupObligation[] = [];
    for (const held of this.sessions.values()) {
      if (held.controlled !== undefined && (held.closing || held.fenced))
        pending.push(this.cleanupObligation(held, held.controlled));
    }
    return Object.freeze(pending);
  }

  async withSession<T>(
    owner: ComputerOwner,
    reference: SessionReference,
    deadline: OperationDeadline,
    operation: (session: ComputerSession) => Promise<T>,
  ): Promise<T> {
    deadline.requireActive();
    const held = this.held(owner, reference);
    if (held.closing || held.fenced) throw new SessionError('FENCED');
    if (held.busy) throw new SessionError('BUSY');
    held.busy = true;
    held.idleAt = this.clock.monotonicNow() + this.idleMs;
    const fence = () => {
      held.fenced = true;
      if (held.controlled !== undefined)
        held.controlled.cleanupReason ??= 'fenced';
      this.clearBrowserReferences(held);
    };
    deadline.signal.addEventListener('abort', fence, { once: true });
    try {
      const result = await operation(held.handle);
      // No late success can escape after input/capture physically settles beyond the caller budget.
      deadline.requireActive();
      if (held.fenced || held.closing) throw new SessionError('FENCED');
      held.idleAt = this.clock.monotonicNow() + this.idleMs;
      return result;
    } catch (error) {
      // A failed helper drain can settle its JS promise while physical work remains unproved.
      if (
        error instanceof BackendError &&
        error.physicalSettlement === 'uncertain'
      ) {
        held.fenced = true;
        if (held.controlled !== undefined)
          held.controlled.cleanupReason ??= 'fenced';
        this.clearBrowserReferences(held);
      }
      throw error;
    } finally {
      deadline.signal.removeEventListener('abort', fence);
      held.busy = false;
      if (
        !held.fenced &&
        (held.closing || held.idleAt <= this.clock.monotonicNow())
      ) {
        if (held.controlled === undefined) this.remove(held);
        else {
          held.closing = true;
          held.controlled.cleanupReason ??= 'idle';
          this.clearBrowserReferences(held);
        }
      }
    }
  }

  /** Closing an occupied target fences new work; it is never freed before physical settlement. */
  close(owner: ComputerOwner, reference: SessionReference): void {
    const held = this.held(owner, reference);
    if (held.controlled !== undefined) {
      this.beginClose(owner, reference);
      throw new SessionError('CLEANUP_REQUIRED');
    }
    if (held.fenced) throw new SessionError('FENCED');
    held.closing = true;
    if (held.busy) {
      held.fenced = true;
      throw new SessionError('BUSY');
    }
    this.remove(held);
  }

  /** Explicitly fence a failed backend; only close after its owner establishes physical quiescence. */
  fence(owner: ComputerOwner, reference: SessionReference): void {
    const held = this.held(owner, reference);
    held.fenced = true;
    if (held.controlled !== undefined)
      held.controlled.cleanupReason ??= 'fenced';
    this.clearBrowserReferences(held);
  }

  /** The host calls this only after backend cleanup establishes quiescence; not a model argument. */
  releaseAfterQuiescence(
    owner: ComputerOwner,
    reference: SessionReference,
  ): void {
    const held = this.held(owner, reference);
    if (held.busy) throw new SessionError('BUSY');
    this.remove(held);
  }

  recordFrame(
    owner: ComputerOwner,
    reference: SessionReference,
    surfaceId: string,
    image: CapturedImage,
  ): FrameReference {
    const held = this.held(owner, reference);
    if (held.closing || held.fenced) throw new SessionError('FENCED');
    const checked = decodeCapturedImage(image, this.captureLimits);
    const age = this.clock.wallNow() - Date.parse(checked.capturedAt);
    if (age < 0 || age >= this.frameFreshMs)
      throw new SessionError('STALE_OBSERVATION');
    const frame = Object.freeze({
      frameId: computerId(this.options.id()),
      surfaceId: computerId(surfaceId),
      width: checked.width,
      height: checked.height,
      transform: checked.transform,
      capturedAt: checked.capturedAt,
      layoutGeneration: checked.layoutGeneration,
    });
    held.frame = {
      value: frame,
      expiresAt: this.clock.monotonicNow() + this.frameFreshMs - age,
    };
    return frame;
  }

  checkFrame(
    owner: ComputerOwner,
    reference: SessionReference,
    frameId: string,
    layoutGeneration: string,
  ): FrameReference {
    const held = this.held(owner, reference);
    if (held.fenced || held.closing) throw new SessionError('FENCED');
    if (
      held.frame === undefined ||
      held.frame.value.frameId !== computerId(frameId) ||
      held.frame.value.layoutGeneration !== computerId(layoutGeneration) ||
      held.frame.expiresAt <= this.clock.monotonicNow()
    )
      throw new SessionError('STALE_OBSERVATION');
    return held.frame.value;
  }

  /**
   * Record only metadata that is actually exposed to the model, after capture/admission
   * geometry checks. Callers may trim nodes and set truncated, preserving snapshotId/guard.
   * No pixels or undisclosed element bindings are retained in the session store.
   */
  recordBrowserObservation(
    owner: ComputerOwner,
    reference: SessionReference,
    value: BrowserObservation,
  ): BrowserObservationReference {
    const { held, controlled } = this.browser(owner, reference);
    this.browserLease(owner, reference);
    const checked = decodeBrowserObservation(value),
      now = this.clock.monotonicNow();
    const snapshotRemaining =
      checked.snapshot === null
        ? this.frameFreshMs
        : this.referenceRemaining(checked.snapshot.capturedAt);
    const frame = this.recordFrame(
      owner,
      reference,
      checked.state.surfaceId,
      checked.image,
    );
    const recorded = Object.freeze({
      frame,
      state: checked.state,
      snapshot: checked.snapshot,
    });
    controlled.observation = {
      value: recorded,
      expiresAt: Math.min(
        held.frame?.expiresAt ?? now,
        now + snapshotRemaining,
      ),
    };
    return recorded;
  }

  resolveBrowserFrame(
    owner: ComputerOwner,
    reference: SessionReference,
    frameId: string,
  ): BrowserStateGuard {
    const observation = this.browserObservation(owner, reference);
    this.checkFrame(
      owner,
      reference,
      frameId,
      observation.state.layoutGeneration,
    );
    return this.guard(observation.state);
  }

  prepareBrowserTarget(
    owner: ComputerOwner,
    reference: SessionReference,
    value: BrowserActionTarget,
  ): PreparedBrowserSessionTarget {
    const target = decodeBrowserActionTarget(value),
      observed = this.browserObservation(owner, reference);
    const expected = this.guard(observed.state);
    if (target.kind === 'point') {
      this.checkFrame(
        owner,
        reference,
        target.frameId,
        observed.state.layoutGeneration,
      );
      const point = framePoint(
        observed.frame.transform,
        observed.frame.width,
        observed.frame.height,
        target.x,
        target.y,
      );
      return Object.freeze({
        expected,
        target: Object.freeze({ kind: 'point', ...point }),
      });
    }
    if (
      observed.snapshot === null ||
      observed.snapshot.snapshotId !== target.snapshotId ||
      !observed.snapshot.nodes.some(
        (node) => node.elementId === target.elementId,
      )
    )
      throw new SessionError('STALE_OBSERVATION');
    return Object.freeze({ expected, target });
  }

  recordBrowserWindows(
    owner: ComputerOwner,
    reference: SessionReference,
    value: BrowserWindows,
  ): BrowserWindows {
    const { controlled } = this.browser(owner, reference);
    this.browserLease(owner, reference);
    const checked = decodeBrowserWindows(value),
      remaining = this.referenceRemaining(checked.capturedAt);
    controlled.windows = {
      value: checked,
      expiresAt: this.clock.monotonicNow() + remaining,
    };
    return checked;
  }

  /** Explicit window lane for focus and quarantined navigation; never accepts caller-supplied guards. */
  resolveBrowserWindow(
    owner: ComputerOwner,
    reference: SessionReference,
    windowsId: string,
    surfaceId: string,
  ): BrowserState {
    const { controlled } = this.browser(owner, reference),
      windows = controlled.windows;
    if (
      windows === undefined ||
      windows.value.windowsId !== computerId(windowsId) ||
      windows.expiresAt <= this.clock.monotonicNow()
    )
      throw new SessionError('STALE_OBSERVATION');
    const state = windows.value.tabs.find(
      (tab) => tab.state.surfaceId === computerId(surfaceId),
    )?.state;
    if (state === undefined || state.readiness === 'closed')
      throw new SessionError('STALE_OBSERVATION');
    return state;
  }

  /** Invoke immediately on input/focus/navigation intent, before any physical dispatch. */
  invalidateBrowserObservations(
    owner: ComputerOwner,
    reference: SessionReference,
  ): void {
    this.clearBrowserReferences(this.browser(owner, reference, false).held);
  }

  /**
   * Debit capture intent, not successful delivery. Caller restores counts from its durable journal
   * before serving work; close/open cannot reset a run's allowance. No persistence is implied here.
   */
  reserveImage(owner: ComputerOwner): void {
    const key = ownerKey(decodeOwner(owner));
    const count = this.runImages.get(key) ?? 0;
    if (count >= this.maxImages) throw new SessionError('FRAME_LIMIT');
    if (!this.runImages.has(key) && this.runImages.size >= this.maxRuns)
      throw new SessionError('BUSY');
    this.runImages.set(key, count + 1);
  }

  restoreImageCount(owner: ComputerOwner, count: number): void {
    const key = ownerKey(decodeOwner(owner));
    if (!Number.isSafeInteger(count) || count < 0 || count > 1000)
      throw new Error('Invalid restored image count');
    if (!this.runImages.has(key) && this.runImages.size >= this.maxRuns)
      throw new SessionError('BUSY');
    this.runImages.set(key, Math.max(this.runImages.get(key) ?? 0, count));
  }

  private browser(
    owner: ComputerOwner,
    reference: SessionReference,
    active = true,
  ): { readonly held: HeldSession; readonly controlled: ControlledSession } {
    const held = this.held(owner, reference);
    if (held.controlled === undefined) throw new SessionError('NO_SESSION');
    if (active && (held.closing || held.fenced))
      throw new SessionError('FENCED');
    return { held, controlled: held.controlled };
  }

  private browserObservation(
    owner: ComputerOwner,
    reference: SessionReference,
  ): BrowserObservationReference {
    const { controlled } = this.browser(owner, reference);
    this.browserLease(owner, reference);
    if (
      controlled.observation === undefined ||
      controlled.observation.expiresAt <= this.clock.monotonicNow()
    )
      throw new SessionError('STALE_OBSERVATION');
    const observation = controlled.observation.value;
    this.checkFrame(
      owner,
      reference,
      observation.frame.frameId,
      observation.state.layoutGeneration,
    );
    return observation;
  }

  private guard(state: BrowserStateGuard): BrowserStateGuard {
    return Object.freeze({
      surfaceId: state.surfaceId,
      documentId: state.documentId,
      layoutGeneration: state.layoutGeneration,
      focusGeneration: state.focusGeneration,
    });
  }

  private referenceRemaining(capturedAt: string): number {
    const age = this.clock.wallNow() - Date.parse(capturedAt);
    if (!Number.isFinite(age) || age < 0 || age >= this.frameFreshMs)
      throw new SessionError('STALE_OBSERVATION');
    return this.frameFreshMs - age;
  }

  private clearBrowserReferences(held: HeldSession): void {
    delete held.frame;
    if (held.controlled !== undefined) {
      delete held.controlled.observation;
      delete held.controlled.windows;
    }
  }

  private cleanupObligation(
    held: HeldSession,
    controlled: ControlledSession,
  ): BrowserCleanupObligation {
    return Object.freeze({
      owner: held.owner,
      session: held.handle,
      lease: controlled.lease ?? null,
      busy: held.busy,
      reason: held.fenced ? 'fenced' : (controlled.cleanupReason ?? 'closing'),
    });
  }

  private held(owner: ComputerOwner, reference: SessionReference): HeldSession {
    const checkedOwner = decodeOwner(owner);
    const sessionId = computerId(reference.sessionId);
    const generation = computerId(reference.generation);
    this.expireIdle();
    const held = this.sessions.get(sessionId);
    if (
      held === undefined ||
      generation !== this.generation ||
      ownerKey(held.owner) !== ownerKey(checkedOwner)
    )
      throw new SessionError('NO_SESSION');
    return held;
  }

  private expireIdle(): void {
    const now = this.clock.monotonicNow();
    for (const held of this.sessions.values()) {
      if (held.idleAt > now) continue;
      if (held.controlled !== undefined) {
        held.closing = true;
        if (held.busy) held.fenced = true;
        held.controlled.cleanupReason ??= held.busy ? 'fenced' : 'idle';
        this.clearBrowserReferences(held);
      } else if (held.busy) {
        held.closing = true;
        held.fenced = true;
      } else if (!held.fenced) this.remove(held);
    }
  }

  private remove(held: HeldSession): void {
    this.sessions.delete(held.handle.sessionId);
    this.targets.delete(held.handle.targetId);
  }
}
