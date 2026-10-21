export interface InformationRefreshClock {
  schedule(delay: number, action: () => void): () => void;
}

/** Hints are invalidation, never data or authority. The owner hides retained read
 * surfaces immediately, then reconciles through ordinary authorized operations.
 * Generation checks also fence callbacks already queued when a timer is cancelled.
 */
export class InformationRefresh {
  private identity: string | undefined;
  private revision = 0;
  private generation = 0;
  private requestGeneration = 0;
  private pending: (() => void) | undefined;
  private reconciliation: (() => void) | undefined;
  private invalidated = false;

  private readonly clock: InformationRefreshClock;
  private readonly invalidate: (contextChanged: boolean) => void;
  private readonly refresh: () => void;

  constructor(
    clock: InformationRefreshClock,
    invalidate: (contextChanged: boolean) => void,
    refresh: () => void,
  ) {
    this.clock = clock;
    this.invalidate = invalidate;
    this.refresh = refresh;
  }

  observe(identity: string | undefined, revision: number): void {
    if (identity !== this.identity) {
      const previous = this.identity;
      this.stop();
      this.identity = identity;
      this.revision = revision;
      if (previous !== undefined || identity !== undefined)
        this.invalidateOnce(true);
      if (identity !== undefined) {
        this.request(0);
        this.reconcile();
      }
    } else if (identity !== undefined && revision !== this.revision) {
      this.revision = revision;
      this.invalidateOnce(false);
      this.request(250);
    }
  }

  private invalidateOnce(contextChanged: boolean): void {
    // Hints in one pending debounce batch need only fence and hide once. A new
    // hint after readStarted still invalidates that read, including permission
    // revocation while a response is in flight. Identity changes always fence it.
    if (!this.invalidated || contextChanged) this.invalidate(contextChanged);
    this.invalidated = true;
  }

  private request(delay: number): void {
    // The first hint owns the deadline: a continuous burst cannot starve refresh.
    if (this.pending) return;
    const generation = this.generation;
    const request = ++this.requestGeneration;
    this.pending = this.clock.schedule(delay, () => {
      if (
        generation !== this.generation ||
        request !== this.requestGeneration ||
        this.identity === undefined
      )
        return;
      this.pending = undefined;
      this.refresh();
    });
  }

  private reconcile(): void {
    const generation = this.generation;
    this.reconciliation = this.clock.schedule(60_000, () => {
      if (generation !== this.generation || this.identity === undefined) return;
      // No new invalidation was received. Reconcile through authorized reads
      // without blanking an otherwise unchanged reader every minute.
      this.request(0);
      this.reconcile();
    });
  }

  /** Any actual read also satisfies a hint queued during the preceding read. */
  readStarted(): void {
    this.invalidated = false;
    this.requestGeneration++;
    this.pending?.();
    this.pending = undefined;
  }

  /** Manual refresh remains available and consumes an already coalesced hint. */
  refreshNow(): void {
    this.readStarted();
    if (this.identity !== undefined) this.refresh();
  }

  stop(): void {
    this.generation++;
    this.requestGeneration++;
    this.pending?.();
    this.reconciliation?.();
    this.pending = this.reconciliation = undefined;
    this.identity = undefined;
    this.invalidated = false;
  }
}
