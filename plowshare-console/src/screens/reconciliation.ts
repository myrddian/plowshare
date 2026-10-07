import { background } from '../background';

/** Owns bounded read-only refreshes for a retained screen. Hidden screens stop
 * polling; revisiting or reconnecting asks for a snapshot. Only reads belong here. */
export interface Reconciliation {
  refresh(this: void): Promise<void>;
  setActive(this: void, active: boolean): void;
  stop(this: void): void;
}

export function reconciliation(options: {
  readonly read: () => Promise<void>;
  readonly available: () => boolean;
  readonly pollMs: number | null;
}): Reconciliation {
  let active = true,
    stopped = false,
    asked = false;
  let flight: Promise<void> | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  function cancelTimer(): void {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  }
  function schedule(): void {
    if (stopped || !active || options.pollMs === null || timer !== null) return;
    timer = setTimeout(() => {
      timer = null;
      background(refresh());
    }, options.pollMs);
  }
  function refresh(): Promise<void> {
    if (stopped || !active || !options.available()) {
      schedule();
      return Promise.resolve();
    }
    if (flight !== null) {
      asked = true;
      return flight;
    }
    cancelTimer();
    flight = (async () => {
      do {
        asked = false;
        await options.read();
      } while (asked && active && !stopped && options.available());
    })().finally(() => {
      flight = null;
      schedule();
    });
    return flight;
  }
  return {
    refresh,
    setActive(next) {
      active = next;
      cancelTimer();
      if (active && !stopped) background(refresh());
    },
    stop() {
      stopped = true;
      cancelTimer();
    },
  };
}
