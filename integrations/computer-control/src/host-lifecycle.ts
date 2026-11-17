import type { Clock } from './deadline.ts';
import { OperationDeadline, positiveBudget } from './deadline.ts';

export class CatalogueLeaseExpired extends Error {
  constructor() {
    super('The declared catalogue availability lease expired; intake stopped');
    this.name = 'CatalogueLeaseExpired';
  }
}

/** A pending metadata send cannot extend availability before its receipt is confirmed. */
export interface CataloguePublication {
  readonly deadline: OperationDeadline;
  readonly beganAt: number;
}

/**
 * A conservative local availability ceiling, not a negotiated server lease or
 * native input grant. Each pass inherits the earlier confirmed publication's
 * monotonic cutoff; a later renewal never extends work already admitted.
 */
export class HostCatalogueLease {
  private current: CataloguePublication | undefined;
  private pending: CataloguePublication | undefined;
  private detach = () => {};
  private stopped = false;
  private expired = false;

  constructor(
    private readonly leaseMs: number,
    private readonly renewalMs: number,
    private readonly clock: Clock,
    private readonly onExpired: () => void,
  ) {
    positiveBudget(leaseMs);
    positiveBudget(renewalMs);
    if (renewalMs >= leaseMs)
      throw new Error('Catalogue renewal must precede its declared lease');
  }

  begin(): CataloguePublication {
    if (this.stopped || this.pending !== undefined)
      throw new Error('Catalogue publication is stopped or already pending');
    const publication = Object.freeze({
      beganAt: this.clock.monotonicNow(),
      deadline: new OperationDeadline(this.leaseMs, this.clock),
    });
    this.pending = publication;
    return publication;
  }

  confirm(publication: CataloguePublication): void {
    if (this.stopped || publication !== this.pending)
      throw new Error('Catalogue publication is no longer admissible');
    // A late response cannot bridge a period in which the previous availability
    // ceiling already expired, even if its timer callback has not run yet.
    if (this.current !== undefined) this.requireActive();
    publication.deadline.requireActive();
    this.detach();
    this.current?.deadline.dispose();
    this.current = publication;
    this.pending = undefined;
    const expired = () => this.expire();
    publication.deadline.signal.addEventListener('abort', expired, {
      once: true,
    });
    this.detach = () =>
      publication.deadline.signal.removeEventListener('abort', expired);
    // A monotonic check also closes the check/listener race without relying on
    // timely timer callbacks. An expired confirmation never revives intake.
    this.requireActive();
  }

  private expire(): void {
    if (this.expired || this.stopped) return;
    this.expired = true;
    this.stop();
    this.onExpired();
  }

  requireActive(): void {
    if (this.expired) throw new CatalogueLeaseExpired();
    if (this.stopped || this.current === undefined)
      throw new Error('Catalogue availability has not been confirmed');
    try {
      this.current.deadline.requireActive();
    } catch {
      this.expire();
      throw new CatalogueLeaseExpired();
    }
  }

  pass(budgetMs: number): OperationDeadline {
    positiveBudget(budgetMs);
    this.requireActive();
    const current = this.current;
    if (current === undefined)
      throw new Error('Catalogue availability has not been confirmed');
    return current.deadline.child(budgetMs);
  }

  renewalDue(): boolean {
    this.requireActive();
    return (
      this.current !== undefined &&
      this.clock.monotonicNow() - this.current.beganAt >= this.renewalMs
    );
  }

  idleDelay(pollIntervalMs: number): number {
    positiveBudget(pollIntervalMs);
    this.requireActive();
    const current = this.current;
    if (current === undefined)
      throw new Error('Catalogue availability has not been confirmed');
    return Math.max(
      0,
      Math.min(
        pollIntervalMs,
        current.beganAt + this.renewalMs - this.clock.monotonicNow(),
        current.deadline.remainingMs(),
      ),
    );
  }

  stop(): void {
    this.stopped = true;
    this.detach();
    this.current?.deadline.dispose();
    this.pending?.deadline.dispose();
    this.pending = undefined;
  }
}

/** Idle lifetime waits use the same injected clock and wake immediately on stop. */
export function waitForHostPoll(
  clock: Clock,
  delayMs: number,
  signal: AbortSignal,
): Promise<void> {
  if (!Number.isFinite(delayMs) || delayMs < 0 || delayMs > 300_000)
    throw new Error('Invalid bounded host idle wait');
  if (signal.aborted || delayMs === 0) return Promise.resolve();
  return new Promise<void>((resolve) => {
    let done = false;
    let cancel = () => {};
    const finish = () => {
      if (done) return;
      done = true;
      cancel();
      signal.removeEventListener('abort', finish);
      resolve();
    };
    signal.addEventListener('abort', finish, { once: true });
    if (signal.aborted) finish();
    else {
      cancel = clock.schedule(delayMs, finish);
      if (done) cancel();
    }
  });
}

/**
 * Bound only admission, then await the admitted long-lived host normally. The
 * original filesystem/SDK lock promise remains observed after a timeout; its
 * late callback must check the same cancelled deadline before taking ownership.
 */
export async function withHostAdmission(
  deadline: OperationDeadline,
  signal: AbortSignal,
  work: (admitted: () => void) => Promise<void>,
): Promise<void> {
  deadline.requireActive();
  signal.throwIfAborted();
  let entered = false;
  let acknowledge = () => {};
  const admitted = new Promise<void>((resolve) => {
    acknowledge = resolve;
  });
  const physical = Promise.resolve().then(async () => {
    deadline.requireActive();
    signal.throwIfAborted();
    await work(() => {
      deadline.requireActive();
      signal.throwIfAborted();
      if (entered)
        throw new Error('Computer host admission was already accepted');
      entered = true;
      deadline.dispose();
      acknowledge();
    });
  });
  let detach = () => {};
  const cancelled = new Promise<never>((_resolve, reject) => {
    const abort = () => {
      deadline.cancel();
      reject(new Error('Computer host admission expired or stopped'));
    };
    deadline.signal.addEventListener('abort', abort, { once: true });
    signal.addEventListener('abort', abort, { once: true });
    detach = () => {
      deadline.signal.removeEventListener('abort', abort);
      signal.removeEventListener('abort', abort);
    };
    if (deadline.signal.aborted || signal.aborted) abort();
  });
  try {
    // Keeping physical in this race observes its eventual rejection even when
    // admission timed out. No new session, request identity or lock is created.
    await Promise.race([admitted, physical, cancelled]);
    if (!entered) throw new Error('Computer host ended before admission');
  } catch (error) {
    deadline.cancel();
    throw error;
  } finally {
    detach();
  }
  await physical;
}
