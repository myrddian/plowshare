import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FrameOutcome, EventStreamOptions, StreamStatus } from '../events';
import { socketTransport } from '../transport';
import { createOverview } from './overview';
import type { Screen } from './screen';

const firing = {
  id: 'fir_1',
  event: 'schedule.due',
  data: '{}',
  schedule: 'scheduled-one',
  fireAt: '2026-10-07T00:00:00Z',
  trigger: 'trigger-one',
  target: null,
  status: 'queued',
  supersededBy: null,
  reason: null,
  jobId: null,
  arrivedAt: '2026-10-07T00:00:00Z',
  startedAt: null,
  finishedAt: null,
  topic: null,
};
const saved = {
  id: 'inb_1',
  handle: 'fixture',
  kind: 'firing',
  firing: 'fir_1',
  conversation: 'cnv_saved',
  ending: 'ANSWERED',
  answer: 'A retained result',
  arrivedAt: '2026-10-07T00:00:00Z',
  readAt: null,
};
const job = {
  id: 'job_1',
  agent: 'reviewer',
  state: 'RUNNING',
  cancelRequested: true,
  conversation: 'cnv_saved',
  outcome: null,
  limits: null,
};
let screen: Screen | undefined;
afterEach(() => {
  screen?.destroy();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

function fixture(pollMs: number | null = null) {
  let options: EventStreamOptions | undefined;
  let state: StreamStatus['state'] = 'open';
  const replies: Record<string, FrameOutcome> = {
    'job.list': { code: 'OK', payload: [job] },
    'approval.list': { code: 'OK', payload: { approvals: [] } },
    'inbox.list': { code: 'OK', payload: { items: [saved], unread: 1 } },
    'firing.list': { code: 'OK', payload: [firing] },
  };
  const ask = vi.fn(
    async (type: string, _payload?: unknown): Promise<FrameOutcome> =>
      replies[type] ?? { code: 'NOT_FOUND' },
  );
  const wire = {
    ask,
    close: vi.fn(),
    status: () => ({ state, attempt: 0, retryInMs: null }),
  };
  const root = document.createElement('main');
  screen = createOverview({
    root,
    session: 's',
    transport: socketTransport(() => wire),
    pollMs,
    openStream: (next) => {
      options = next;
      next.onStatus?.(wire.status());
      return wire;
    },
  });
  return {
    root,
    screen,
    replies,
    ask,
    wire,
    status(next: StreamStatus['state']) {
      state = next;
      options?.onStatus?.(wire.status());
    },
    push() {
      options?.onEvent({ job: 'job_1', kind: 'ended', agent: 'reviewer' });
    },
  };
}

describe('the work overview', () => {
  it('rejects a late result from a prior connection and shows the recovered retained record', async () => {
    const view = fixture();
    await view.screen.load();
    let resolve: (reply: FrameOutcome) => void = () => {};
    view.ask.mockImplementationOnce(
      () =>
        new Promise<FrameOutcome>((accept) => {
          resolve = accept;
        }),
    );
    const flight = view.screen.load();
    view.status('reconnecting');
    view.replies['inbox.list'] = {
      code: 'OK',
      payload: { items: [{ ...saved, answer: 'recovered result' }], unread: 1 },
    };
    view.status('open');
    resolve({ code: 'OK', payload: [{ ...job, id: 'job_obsolete' }] });
    await flight;
    expect(view.root.textContent).not.toContain('job_obsolete');
    expect(view.root.textContent).toContain('recovered result');
    expect(view.root.getAttribute('aria-busy')).not.toBe('true');
  });

  it('does not poll or render late replies while the browser tab is hidden', async () => {
    const view = fixture();
    await view.screen.load();
    const visibility = vi.spyOn(document, 'visibilityState', 'get');
    visibility.mockReturnValue('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    view.ask.mockClear();
    view.push();
    await view.screen.load();
    expect(view.ask).not.toHaveBeenCalled();
    expect(view.root.textContent).toContain('may be stale');
    visibility.mockReturnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.waitFor(() => expect(view.ask).toHaveBeenCalledTimes(4));
  });

  it('separates admission, running state, cancellation request and retained results', async () => {
    const view = fixture();
    await view.screen.load();
    expect(view.root.textContent).toContain('queued');
    expect(view.root.textContent).toContain('RUNNING');
    expect(view.root.textContent).toContain('Cancellation requested');
    expect(view.root.textContent).toContain('A retained result');
    expect(view.root.textContent).toContain('not historic job listing');
    expect(
      view.root.querySelector<HTMLAnchorElement>(
        'a[href="#chat?record=cnv_saved"]',
      )?.textContent,
    ).toContain('retained');
    expect(view.ask.mock.calls.map(([type]) => type).sort()).toEqual([
      'approval.list',
      'firing.list',
      'inbox.list',
      'job.list',
    ]);
  });

  it('shows terminal cancelled and failure outcomes using the reported ending', async () => {
    const view = fixture();
    view.replies['job.list'] = {
      code: 'OK',
      payload: [
        {
          ...job,
          id: 'cancelled',
          state: 'DONE',
          cancelRequested: false,
          outcome: {
            ending: 'CANCELLED',
            answered: false,
            resumable: false,
            text: '',
            steps: 1,
            modelCalls: 1,
            detail: '',
          },
        },
        {
          ...job,
          id: 'failed',
          state: 'DONE',
          cancelRequested: false,
          outcome: {
            ending: 'UNAVAILABLE',
            answered: false,
            resumable: false,
            text: '',
            steps: 1,
            modelCalls: 1,
            detail: 'Dependency failed',
          },
        },
      ],
    };
    await view.screen.load();
    expect(view.root.textContent).toContain('CANCELLED');
    expect(view.root.textContent).toContain('UNAVAILABLE');
    expect(view.root.textContent).not.toContain('Cancellation requested');
  });

  it('reconciles a result after dropped events/reconnect without resubmitting anything', async () => {
    const view = fixture();
    await view.screen.load();
    view.status('reconnecting');
    view.replies['inbox.list'] = {
      code: 'OK',
      payload: {
        items: [{ ...saved, answer: 'Finished while away' }],
        unread: 1,
      },
    };
    view.status('open');
    await vi.waitFor(() =>
      expect(view.root.textContent).toContain('Finished while away'),
    );
    view.push();
    await vi.waitFor(() => expect(view.ask).toHaveBeenCalledTimes(12));
    expect(
      view.ask.mock.calls.every(([type]) =>
        ['job.list', 'approval.list', 'inbox.list', 'firing.list'].includes(
          type,
        ),
      ),
    ).toBe(true);
  });

  it('keeps an unavailable queue distinct from an empty queue', async () => {
    const view = fixture();
    view.replies['firing.list'] = {
      code: 'BAD_REQUEST',
      said: 'Membership was revoked.',
    };
    view.replies['inbox.list'] = {
      code: 'OK',
      payload: { items: [{ ...saved, answer: 42 }], unread: 1 },
    };
    await view.screen.load();
    expect(view.root.textContent).toContain('Membership was revoked');
    expect(view.root.textContent).toContain('Unreadable');
    expect(view.root.textContent).not.toContain('0 unread');
  });

  it('bounds job rendering and pages admissions using public offsets', async () => {
    const view = fixture();
    view.replies['job.list'] = {
      code: 'OK',
      payload: Array.from({ length: 100 }, (_, index) => ({
        ...job,
        id: `job_${index}`,
      })),
    };
    await view.screen.load();
    expect(
      view.root.querySelectorAll('.work-summary:first-child .work-row'),
    ).toHaveLength(20);
    view.root.querySelector<HTMLButtonElement>('.next')?.click();
    await vi.waitFor(() =>
      expect(view.ask).toHaveBeenCalledWith('firing.list', {
        offset: 30,
        limit: 30,
      }),
    );
  });

  it('stops hidden polling and reconciles on revisit', async () => {
    vi.useFakeTimers();
    const view = fixture(100);
    await view.screen.load();
    view.screen.setActive?.(false);
    await vi.advanceTimersByTimeAsync(1000);
    expect(view.ask).toHaveBeenCalledTimes(4);
    view.screen.setActive?.(true);
    await vi.advanceTimersByTimeAsync(0);
    expect(view.ask).toHaveBeenCalledTimes(8);
    view.screen.destroy();
    await vi.advanceTimersByTimeAsync(1000);
    expect(view.ask).toHaveBeenCalledTimes(8);
  });
});

it('reconciles the selected inspector from a fresh record and clears it on an unavailable read', async () => {
  const view = fixture();
  await view.screen.load();
  view.root
    .querySelector<HTMLButtonElement>('.work-row .inspect-record')!
    .click();
  const inspector = view.root.querySelector('.work-inspection')!;
  expect(inspector.textContent).toContain('RUNNING');
  view.replies['job.list'] = {
    code: 'OK',
    payload: [
      {
        ...job,
        state: 'DONE',
        cancelRequested: false,
        outcome: {
          ending: 'CANCELLED',
          answered: false,
          resumable: false,
          text: '',
          steps: 1,
          modelCalls: 1,
          detail: '',
          pace: null,
        },
      },
    ],
  };
  view.status('reconnecting');
  view.status('open');
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(inspector.textContent).toContain('CANCELLED');
  expect(inspector.textContent).not.toContain('RUNNING');
  view.replies['job.list'] = { code: 'FORBIDDEN' };
  view.push();
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(inspector.textContent).toContain('could not be read');
  expect(inspector.textContent).not.toContain('CANCELLED');
  expect(
    view.ask.mock.calls.every(([type]) =>
      ['job.list', 'approval.list', 'inbox.list', 'firing.list'].includes(type),
    ),
  ).toBe(true);
});
