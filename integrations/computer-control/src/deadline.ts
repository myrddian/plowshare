/** Injectable clock: wall time translates a wire deadline once; active work uses monotonic time. */
export interface Clock {
  wallNow(): number;
  monotonicNow(): number;
  schedule(delayMs: number, callback: () => void): () => void;
}

export const systemClock: Clock = {
  wallNow: () => Date.now(),
  monotonicNow: () => performance.now(),
  schedule(delayMs, callback) {
    const timer = setTimeout(callback, delayMs);
    return () => clearTimeout(timer);
  },
};

export class DeadlineExceeded extends Error {
  constructor() {
    super('Computer operation deadline expired');
    this.name = 'DeadlineExceeded';
  }
}

export function positiveBudget(value: number): number {
  if (!Number.isSafeInteger(value) || value < 1 || value > 300_000)
    throw new Error(
      'Operation budget must be a positive finite duration at most 300 seconds',
    );
  return value;
}

/** Strict UTC serialization also rejects Date.parse's rollover acceptance. */
export function isoTimestamp(value: unknown): string {
  if (typeof value !== 'string') throw new Error('Invalid UTC timestamp');
  const match =
    /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/u.exec(value);
  if (match === null || match[1] === undefined)
    throw new Error('Invalid UTC timestamp');
  // Java Instant uses variable fractional precision; Date operates at milliseconds, rounded down.
  const canonical = `${match[1]}.${(match[2] ?? '').padEnd(3, '0').slice(0, 3)}Z`;
  const parsed = Date.parse(canonical);
  if (!Number.isFinite(parsed) || new Date(parsed).toISOString() !== canonical)
    throw new Error('Invalid UTC timestamp');
  return value;
}

/**
 * One finite budget. Expiry signals cancellation but does not claim a physical operation stopped.
 * Callers retain their target lease until the underlying operation actually settles.
 */
export class OperationDeadline {
  readonly signal: AbortSignal;
  private readonly controller = new AbortController();
  private readonly endsAt: number;
  private readonly cancelTimer: () => void;
  private readonly detachParent: () => void;

  constructor(
    budgetMs: number,
    private readonly clock: Clock = systemClock,
    parent?: OperationDeadline,
  ) {
    positiveBudget(budgetMs);
    parent?.requireActive();
    const remaining = Math.min(budgetMs, parent?.remainingMs() ?? budgetMs);
    this.endsAt = clock.monotonicNow() + remaining;
    this.signal = this.controller.signal;
    const expired = () => this.controller.abort(new DeadlineExceeded());
    this.cancelTimer = clock.schedule(remaining, expired);
    if (parent === undefined) this.detachParent = () => {};
    else {
      parent.signal.addEventListener('abort', expired, { once: true });
      this.detachParent = () =>
        parent.signal.removeEventListener('abort', expired);
      // Close the check/listener race if a clock callback expired the parent during construction.
      if (parent.signal.aborted) expired();
    }
  }

  remainingMs(): number {
    return this.signal.aborted
      ? 0
      : Math.max(0, this.endsAt - this.clock.monotonicNow());
  }

  requireActive(): void {
    if (this.remainingMs() <= 0) {
      this.controller.abort(new DeadlineExceeded());
      throw new DeadlineExceeded();
    }
  }

  child(budgetMs: number): OperationDeadline {
    return new OperationDeadline(budgetMs, this.clock, this);
  }

  /** Request host shutdown cancellation without claiming the underlying work has settled. */
  cancel(): void {
    this.controller.abort(new DeadlineExceeded());
  }

  /** Dispose only after owned physical work settles; it releases timers, not target ownership. */
  dispose(): void {
    this.cancelTimer();
    this.detachParent();
  }
}

export function fromCallDeadline(
  deadline: string,
  budgetMs: number,
  clock: Clock = systemClock,
  parent?: OperationDeadline,
): OperationDeadline {
  positiveBudget(budgetMs);
  const remaining = Date.parse(isoTimestamp(deadline)) - clock.wallNow();
  if (remaining <= 0) throw new DeadlineExceeded();
  return new OperationDeadline(
    Math.min(budgetMs, Math.ceil(remaining)),
    clock,
    parent,
  );
}
