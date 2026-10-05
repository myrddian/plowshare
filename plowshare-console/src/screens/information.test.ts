import { describe, it, expect, vi } from 'vitest';
import { createInformation } from './information';
import type { EventStreamOptions } from '../events';

describe('scoped information screen', () => {
  it('uses the existing socket for catalogue and controls and clears stale content on refusal', async () => {
    const root = document.createElement('div');
    let options: EventStreamOptions | undefined;
    const ask = vi.fn(async (type: string) =>
      type === 'information.list'
        ? {
            code: 'OK',
            payload: [{ id: 'retained', title: '<img src=x onerror=bad()>' }],
          }
        : { code: 'BAD_REQUEST', said: 'Permission revoked.' },
    );
    const close = vi.fn();
    const screen = createInformation({
      root,
      session: 'pinned',
      project: 'research',
      openStream: (value) => {
        options = value;
        return {
          ask,
          close,
          status: () => ({ state: 'open', attempt: 0, retryInMs: null }),
        };
      },
    });
    await screen.load();
    expect(options?.session).toBe('pinned');
    expect(ask).toHaveBeenCalledWith('information.list', {
      offset: 0,
      limit: 20,
      scope: { kind: 'project', project: 'research' },
    });
    expect(root.querySelector('img')).toBeNull();
    const revisionButton = [...root.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('<img'),
    )!;
    revisionButton.click();
    await vi.waitFor(() =>
      expect(root.querySelector('[role=alert]')?.textContent).toBe(
        'Permission revoked.',
      ),
    );
    expect(root.textContent).not.toContain('<img');
    screen.destroy();
    expect(close).toHaveBeenCalledOnce();
    expect(root.childElementCount).toBe(0);
  });
  it('does not render a response from a selection left while the request was pending', async () => {
    const root = document.createElement('div');
    let resolve:
      ((value: { code: string; payload: unknown }) => void) | undefined;
    const ask = vi.fn(
      () =>
        new Promise<{ code: string; payload: unknown }>((finish) => {
          resolve = finish;
        }),
    );
    const screen = createInformation({
      root,
      session: 'pinned',
      project: null,
      openStream: () => ({
        ask,
        close() {},
        status: () => ({ state: 'open', attempt: 0, retryInMs: null }),
      }),
    });
    const loading = screen.load();
    screen.destroy();
    resolve!({
      code: 'OK',
      payload: [{ id: 'old', title: 'private stale title' }],
    });
    await loading;
    expect(root.textContent).toBe('');
  });
});
