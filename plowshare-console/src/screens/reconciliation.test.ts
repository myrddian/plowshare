import { describe, expect, it, vi } from 'vitest';
import { reconciliation } from './reconciliation';

describe('snapshot refresh ownership', () => {
  it('coalesces a burst into one trailing read and does not overlap reads', async () => {
    let finish: (() => void) | undefined;
    let reads = 0;
    const read = vi.fn(async () => {
      reads += 1;
      if (reads === 1)
        await new Promise<void>((resolve) => {
          finish = resolve;
        });
    });
    const owner = reconciliation({ read, available: () => true, pollMs: null });
    const initial = owner.refresh();
    const trailing: Promise<void>[] = [];
    for (let index = 0; index < 100; index += 1) trailing.push(owner.refresh());
    expect(read).toHaveBeenCalledTimes(1);
    finish?.();
    await Promise.all([initial, ...trailing]);
    expect(read).toHaveBeenCalledTimes(2);
    owner.stop();
  });

  it('drops a queued refresh after teardown', async () => {
    let finish: (() => void) | undefined;
    const read = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    const owner = reconciliation({ read, available: () => true, pollMs: null });
    const initial = owner.refresh();
    const trailing = owner.refresh();
    owner.stop();
    finish?.();
    await Promise.all([initial, trailing]);
    expect(read).toHaveBeenCalledOnce();
  });
});
