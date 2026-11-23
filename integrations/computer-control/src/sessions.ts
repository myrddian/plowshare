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
import type { Clock, OperationDeadline } from './deadline.ts';
import { positiveBudget, systemClock } from './deadline.ts';
import type { AffineTransform } from './geometry.ts';

export type SessionErrorCode =
  'NO_SESSION' | 'BUSY' | 'FENCED' | 'STALE_OBSERVATION' | 'FRAME_LIMIT';
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
interface HeldSession {
  readonly handle: ComputerSession;
  readonly owner: ComputerOwner;
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
      )
        held.fenced = true;
      throw error;
    } finally {
      deadline.signal.removeEventListener('abort', fence);
      held.busy = false;
      if (
        !held.fenced &&
        (held.closing || held.idleAt <= this.clock.monotonicNow())
      )
        this.remove(held);
    }
  }

  /** Closing an occupied target fences new work; it is never freed before physical settlement. */
  close(owner: ComputerOwner, reference: SessionReference): void {
    const held = this.held(owner, reference);
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
    this.held(owner, reference).fenced = true;
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
      if (held.busy) {
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
