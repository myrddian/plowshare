import type { Clock } from './deadline.ts';
import type {
  CapturedImage,
  ComputerOwner,
  ComputerTarget,
} from './contracts.ts';

export class FakeClock implements Clock {
  wall = Date.parse('2026-11-16T00:00:00.000Z');
  monotonic = 0;
  private readonly timers = new Map<
    number,
    { readonly at: number; readonly callback: () => void }
  >();
  private next = 0;
  wallNow(): number {
    return this.wall;
  }
  monotonicNow(): number {
    return this.monotonic;
  }
  schedule(delayMs: number, callback: () => void): () => void {
    const id = this.next++;
    this.timers.set(id, { at: this.monotonic + delayMs, callback });
    return () => {
      this.timers.delete(id);
    };
  }
  advance(ms: number): void {
    this.monotonic += ms;
    this.wall += ms;
    for (const [id, timer] of this.timers) {
      if (timer.at <= this.monotonic) {
        this.timers.delete(id);
        timer.callback();
      }
    }
  }
  pendingTimers(): number {
    return this.timers.size;
  }
}

export const owner: ComputerOwner = Object.freeze({
  account: 'fixture-owner',
  project: 'fixture-project',
  run: 'fixture-run',
  provider: 'fixture-provider',
});
export const target: ComputerTarget = Object.freeze({
  targetId: 'fixture-target',
  backend: 'browser',
  label: 'Fixture browser',
  capabilities: Object.freeze(['capture'] as const),
  surfaces: Object.freeze([{ id: 'viewport', label: 'Viewport' }]),
});
export function image(clock: Clock): CapturedImage {
  return {
    bytes: new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]),
    mimeType: 'image/png',
    width: 100,
    height: 80,
    transform: { a: 1, b: 0, c: 0, d: 1, e: -300, f: 50 },
    capturedAt: new Date(clock.wallNow()).toISOString(),
    layoutGeneration: 'layout-one',
  };
}
