import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FrameOutcome } from '../events';
import { socketTransport } from '../transport';
import { createDocuments } from './documents';
import type { Screen } from './screen';

let screen: Screen;
let root: HTMLElement;

function mount(
  ask: (type: string, payload?: unknown) => Promise<FrameOutcome>,
): Screen {
  const stream = {
    ask,
    close: () => {},
    status: () => ({ state: 'open' as const, attempt: 0, retryInMs: null }),
  };
  return createDocuments({
    root,
    transport: socketTransport(() => stream),
    pollMs: 100,
  });
}

beforeEach(() => {
  vi.useFakeTimers();
  root = document.createElement('main');
  document.body.replaceChildren(root);
});

afterEach(() => {
  screen.destroy();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('document refresh ownership', () => {
  it('suspends hidden navigation and reconciles once on return without submitting work', async () => {
    const ask = vi.fn(
      async (_type: string, _payload?: unknown): Promise<FrameOutcome> => ({
        code: 'OK',
        payload: [],
      }),
    );
    screen = mount(ask);
    await screen.load();
    await vi.advanceTimersByTimeAsync(100);
    expect(ask).toHaveBeenCalledTimes(2);
    screen.setActive?.(false);
    await vi.advanceTimersByTimeAsync(500);
    expect(ask).toHaveBeenCalledTimes(2);
    screen.setActive?.(true);
    await vi.advanceTimersByTimeAsync(0);
    expect(ask).toHaveBeenCalledTimes(3);
    screen.setActive?.(true);
    await vi.advanceTimersByTimeAsync(100);
    expect(ask).toHaveBeenCalledTimes(4);
    expect(ask.mock.calls.every(([type]) => type === 'job.list')).toBe(true);
    screen.destroy();
    await vi.advanceTimersByTimeAsync(500);
    expect(ask).toHaveBeenCalledTimes(4);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('coalesces pending refreshes and discards a late response from a hidden visit', async () => {
    let answer: ((reply: FrameOutcome) => void) | undefined;
    const pending = new Promise<FrameOutcome>((resolve) => {
      answer = resolve;
    });
    const ask = vi
      .fn(async (_type: string, _payload?: unknown): Promise<FrameOutcome> => ({
        code: 'OK',
        payload: [],
      }))
      .mockImplementationOnce(() => pending);
    screen = mount(ask);
    const first = screen.load();
    await screen.load();
    screen.setActive?.(false);
    screen.setActive?.(true);
    expect(ask).toHaveBeenCalledOnce();
    if (!answer) throw new Error('Expected pending read');
    answer({ code: 'FORBIDDEN', said: 'old selection' });
    await first;
    await vi.advanceTimersByTimeAsync(0);
    expect(ask).toHaveBeenCalledTimes(2);
    expect(root.textContent).not.toContain('old selection');
    expect(root.querySelector('[data-trouble]')).toBeNull();
  });

  it('pauses when the browser tab is hidden and reconciles when it becomes visible', async () => {
    const ask = vi.fn(
      async (_type: string, _payload?: unknown): Promise<FrameOutcome> => ({
        code: 'OK',
        payload: [],
      }),
    );
    screen = mount(ask);
    await screen.load();
    const visibility = vi.spyOn(document, 'visibilityState', 'get');
    visibility.mockReturnValue('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.advanceTimersByTimeAsync(500);
    expect(ask).toHaveBeenCalledOnce();
    visibility.mockReturnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.advanceTimersByTimeAsync(0);
    expect(ask).toHaveBeenCalledTimes(2);
    screen.destroy();
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.advanceTimersByTimeAsync(500);
    expect(ask).toHaveBeenCalledTimes(2);
  });

  it('does not redraw or schedule a timer when a read completes after destruction', async () => {
    let answer: ((reply: FrameOutcome) => void) | undefined;
    screen = mount(
      () =>
        new Promise((resolve) => {
          answer = resolve;
        }),
    );
    const load = screen.load();
    await vi.advanceTimersByTimeAsync(0);
    const snapshot = root.textContent;
    screen.destroy();
    if (!answer) throw new Error('Expected pending read');
    answer({ code: 'FORBIDDEN', said: 'late failure' });
    await load;
    expect(root.textContent).toBe(snapshot);
    expect(vi.getTimerCount()).toBe(0);
  });
});
