import { describe, expect, it, vi } from 'vitest';
import { createSchedules } from './schedules';
import type { EventStreamOptions } from '../events';
import { definitionFromProposal } from '../../../sdk/typescript/src/operations/schedule-files.ts';

function fixture() {
  let paused = true,
    lost = false,
    refused = false,
    invalid = false;
  const root = document.createElement('div');
  const close = vi.fn();
  const schedule = () => ({
    name: 'scheduled-1-scan',
    cron: '0 */15 * * * *',
    zone: 'UTC',
    emits: 'scan',
    paused,
    nextFireAt: '2026-01-01T00:15:00Z',
    definedBy: 'manager',
  });
  const source = () => ({
    name: '<script>scan</script>',
    project: 'project',
    source: 'server',
    path: 'schedules/scan.json',
    internalName: 'scheduled-1-scan',
    status: 'active',
    error: null,
    definition: definitionFromProposal({
      cron: schedule().cron,
      zone: 'UTC',
      agent: 'collector',
      task: 'Collect',
      project: 'project',
    }),
  });
  const ask = vi.fn(async (type: string, payload?: unknown) => {
    if (type === 'schedule.list')
      return { code: 'OK', payload: invalid ? [{}] : [schedule()] };
    if (type === 'schedule.files') return { code: 'OK', payload: [source()] };
    if (refused)
      return { code: 'BAD_REQUEST', said: 'Manager access required.' };
    if (
      type === 'schedule.pause' &&
      typeof payload === 'object' &&
      payload !== null &&
      'paused' in payload &&
      typeof payload.paused === 'boolean'
    ) {
      paused = payload.paused;
      if (lost) throw new Error('Reply lost.');
      return { code: 'NO_CONTENT', payload: null };
    }
    throw new Error('Unexpected request');
  });
  let opened: EventStreamOptions | undefined;
  const screen = createSchedules({
    root,
    session: 'shared',
    openStream: (options) => {
      opened = options;
      return {
        ask,
        close,
        status: () => ({ state: 'open', attempt: 0, retryInMs: null }),
      };
    },
  });
  const control = () =>
    root.querySelector<HTMLButtonElement>('.schedule-control');
  return {
    root,
    ask,
    close,
    screen,
    control,
    opened: () => opened,
    lose: () => {
      lost = true;
    },
    refuse: () => {
      refused = true;
    },
    invalidate: () => {
      invalid = true;
    },
  };
}

describe('schedule runtime controls', () => {
  it('uses one shared socket and confirms both resume and pause through retained reads', async () => {
    const f = fixture();
    await f.screen.load();
    expect(f.opened()?.session).toBe('shared');
    expect(f.root.querySelector('script')).toBeNull();
    expect(f.root.textContent).toContain('<script>scan</script>');
    expect(f.control()?.textContent).toBe('Resume');
    f.control()?.click();
    await vi.waitFor(() => expect(f.control()?.textContent).toBe('Pause'));
    expect(f.ask).toHaveBeenCalledWith('schedule.pause', {
      schedule: 'scheduled-1-scan',
      paused: false,
    });
    f.control()?.click();
    await vi.waitFor(() => expect(f.control()?.textContent).toBe('Resume'));
    expect(f.ask).toHaveBeenCalledWith('schedule.pause', {
      schedule: 'scheduled-1-scan',
      paused: true,
    });
    expect(
      f.ask.mock.calls.filter((call) => call[0] === 'schedule.pause'),
    ).toHaveLength(2);
    expect(
      f.ask.mock.calls.every((call) =>
        ['schedule.list', 'schedule.files', 'schedule.pause'].includes(call[0]),
      ),
    ).toBe(true);
    f.screen.destroy();
    expect(f.close).toHaveBeenCalledOnce();
  });

  it('fences a lost reply and reconciles by reading rather than repeating the mutation', async () => {
    const f = fixture();
    await f.screen.load();
    f.lose();
    f.control()?.click();
    await vi.waitFor(() => expect(f.root.textContent).toContain('Reply lost.'));
    expect(f.control()?.disabled).toBe(true);
    f.control()?.click();
    await f.screen.load();
    expect(f.control()?.textContent).toBe('Pause');
    expect(f.control()?.disabled).toBe(false);
    expect(
      f.ask.mock.calls.filter((call) => call[0] === 'schedule.pause'),
    ).toHaveLength(1);
  });

  it('shows an authorization refusal without claiming a state change', async () => {
    const f = fixture();
    await f.screen.load();
    f.refuse();
    f.control()?.click();
    await vi.waitFor(() =>
      expect(f.root.textContent).toContain('Manager access required.'),
    );
    expect(f.control()?.textContent).toBe('Resume');
    expect(f.control()?.disabled).toBe(false);
  });

  it('retains the last confirmed state when a malformed listing is refused', async () => {
    const f = fixture();
    await f.screen.load();
    f.invalidate();
    await f.screen.load();
    expect(f.root.querySelector('[role=alert]')?.textContent).toContain(
      'Unreadable',
    );
    expect(f.control()?.textContent).toBe('Resume');
  });
});
