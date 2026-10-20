import { describe, expect, it, vi } from 'vitest';
import { createSchedules } from './schedules';
import type { EventStreamOptions } from '../events';
import { definitionFromProposal } from '../../../sdk/typescript/src/operations/schedule-files.ts';

function fixture(paged = false) {
  let paused = true,
    lost = false,
    refused = false,
    invalid = false,
    pageFailure = false;
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
  const scheduledRows = () =>
    paged
      ? Array.from({ length: 125 }, (_, index) => ({
          ...schedule(),
          name: `scheduled-${String(index).padStart(3, '0')}`,
        }))
      : [schedule()];
  const fileRows = () =>
    paged
      ? Array.from({ length: 108 }, (_, index) => ({
          ...source(),
          name: `file-${index}`,
          internalName: `scheduled-${String(index).padStart(3, '0')}`,
        }))
      : [source()];
  let heldFiles: Promise<void> | undefined;
  const ask = vi.fn(async (type: string, payload?: unknown) => {
    if (type === 'schedule.files' && heldFiles) await heldFiles;
    if (type === 'schedule.list' || type === 'schedule.files') {
      const after =
        typeof payload === 'object' &&
        payload !== null &&
        'after' in payload &&
        typeof payload.after === 'string'
          ? payload.after
          : undefined;
      if (pageFailure && after !== undefined)
        return { code: 'BAD_REQUEST', said: 'Account page unavailable' };
      if (invalid && type === 'schedule.list')
        return { code: 'OK', payload: [{}] };
      const rows = type === 'schedule.list' ? scheduledRows() : fileRows();
      const start =
        after === undefined
          ? 0
          : rows.findIndex(
              (row) =>
                ('internalName' in row ? row.internalName : row.name) === after,
            ) + 1;
      return { code: 'OK', payload: rows.slice(start, start + 51) };
    }
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
    holdFiles: (held: Promise<void>) => {
      heldFiles = held;
    },
    lose: () => {
      lost = true;
    },
    refuse: () => {
      refused = true;
    },
    failPage: (value: boolean) => {
      pageFailure = value;
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
    expect(
      f.root.querySelector('.schedule-pages [role=alert]')?.textContent,
    ).toContain('Unreadable');
    expect(f.control()?.textContent).toBe('Resume');
  });
});

it('Console independently pages schedules and files with visible accessible bounded controls', async () => {
  const f = fixture(true);
  await f.screen.load();
  expect(f.root.querySelectorAll('.schedule-row')).toHaveLength(50);
  expect(f.root.querySelectorAll('.schedule-file')).toHaveLength(50);
  expect(f.ask).toHaveBeenCalledWith('schedule.list', { limit: 51 });
  expect(f.ask).toHaveBeenCalledWith('schedule.files', { limit: 51 });
  f.root
    .querySelector<HTMLButtonElement>('[aria-label="More schedules"]')
    ?.click();
  await vi.waitFor(() =>
    expect(
      f.root.querySelector('.schedule-row')?.getAttribute('data-schedule'),
    ).toBe('scheduled-050'),
  );
  expect(f.ask).toHaveBeenCalledWith('schedule.list', {
    limit: 51,
    after: 'scheduled-049',
  });
  expect(f.root.querySelector('.schedule-file')?.textContent).toContain(
    'file-0',
  );
  f.root
    .querySelector<HTMLButtonElement>('[aria-label="More schedule files"]')
    ?.click();
  await vi.waitFor(() =>
    expect(f.root.querySelector('.schedule-file')?.textContent).toContain(
      'file-50',
    ),
  );
  expect(f.ask).toHaveBeenCalledWith('schedule.files', {
    limit: 51,
    after: 'scheduled-049',
  });
  f.root
    .querySelector<HTMLButtonElement>('[aria-label="Previous schedules"]')
    ?.click();
  await vi.waitFor(() =>
    expect(
      f.root.querySelector('.schedule-row')?.getAttribute('data-schedule'),
    ).toBe('scheduled-000'),
  );
  expect(f.root.querySelector('.schedule-file')?.textContent).toContain(
    'file-50',
  );
  await f.screen.load();
  expect(f.root.querySelector('.schedule-file')?.textContent).toContain(
    'file-0',
  );
  f.screen.destroy();
});
it('Console retains displayed rows and cursor on page refusal and never replays a mutation', async () => {
  const f = fixture(true);
  await f.screen.load();
  f.failPage(true);
  f.root
    .querySelector<HTMLButtonElement>('[aria-label="More schedules"]')
    ?.click();
  await vi.waitFor(() =>
    expect(f.root.textContent).toContain('Account page unavailable'),
  );
  expect(
    f.root.querySelector('.schedule-row')?.getAttribute('data-schedule'),
  ).toBe('scheduled-000');
  f.failPage(false);
  f.root
    .querySelector<HTMLButtonElement>('[aria-label="More schedules"]')
    ?.click();
  await vi.waitFor(() =>
    expect(
      f.root.querySelector('.schedule-row')?.getAttribute('data-schedule'),
    ).toBe('scheduled-050'),
  );
  expect(
    f.ask.mock.calls.filter(
      (call) =>
        call[0] === 'schedule.list' &&
        typeof call[1] === 'object' &&
        call[1] !== null &&
        'after' in call[1],
    ),
  ).toHaveLength(2);
  expect(f.ask.mock.calls.some((call) => call[0] === 'schedule.pause')).toBe(
    false,
  );
  f.screen.destroy();
});

it('renders schedules and allows their navigation while the file collection remains pending', async () => {
  const f = fixture(true);
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  f.holdFiles(held);
  const load = f.screen.load();
  await vi.waitFor(() =>
    expect(f.root.querySelectorAll('.schedule-row')).toHaveLength(50),
  );
  const more = f.root.querySelector<HTMLButtonElement>(
    '[aria-label="More schedules"]',
  );
  expect(more?.disabled).toBe(false);
  more?.click();
  await vi.waitFor(() =>
    expect(
      f.root.querySelector('.schedule-row')?.getAttribute('data-schedule'),
    ).toBe('scheduled-050'),
  );
  release();
  await load;
  expect(
    f.root.querySelector('.schedule-row')?.getAttribute('data-schedule'),
  ).toBe('scheduled-050');
  expect(f.root.querySelectorAll('.schedule-file')).toHaveLength(50);
  f.screen.destroy();
});
