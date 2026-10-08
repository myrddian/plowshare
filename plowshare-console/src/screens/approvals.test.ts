import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ApprovalView } from '../../../sdk/typescript/src/operations/administrative-replies.ts';
import type { EventStreamOptions, FrameOutcome, StreamStatus } from '../events';
import { socketApprovals } from '../approvals';
import { createApprovals } from './approvals';
import type { Screen } from './screen';

const approval: ApprovalView = {
  id: 'apr_1',
  conversation: 'cnv_1',
  askedIn: 'cnv_child',
  agent: 'reviewer',
  side: 'server',
  command: ['echo', '<img src=x onerror=alert(1)>'],
  cwd: '/fixture',
  reason: 'Review this exact command',
  state: 'asked',
  scope: null,
  prefix: null,
  defaultPrefix: ['echo'],
  createdAt: '2026-10-07T00:00:00Z',
  answeredAt: null,
  commands: null,
  judged: null,
};

let screen: Screen | undefined;
afterEach(() => {
  screen?.destroy();
  vi.useRealTimers();
  vi.restoreAllMocks();
  document.body.replaceChildren();
});

function fixture(
  initial: StreamStatus['state'] = 'open',
  pollMs: number | null = null,
) {
  let requests: readonly ApprovalView[] = [approval];
  let state = initial;
  let subscriber: EventStreamOptions | undefined;
  let listError: Error | undefined;
  let decision: FrameOutcome | Error = {
    code: 'OK',
    payload: {
      id: 'apr_1',
      state: 'allowed',
      job: 'job_1',
      busy: false,
      note: null,
    },
  };
  const ask = vi.fn(async (type: string): Promise<FrameOutcome> => {
    if (type === 'approval.list') {
      if (listError) throw listError;
      return { code: 'OK', payload: { approvals: requests } };
    }
    if (decision instanceof Error) throw decision;
    if (decision.code === 'OK') requests = [];
    return decision;
  });
  const close = vi.fn();
  const root = document.createElement('main');
  screen = createApprovals({
    root,
    session: 's',
    pollMs,
    openStream: (options) => {
      subscriber = options;
      // Exercise the synchronous status announcement used by shell.multiplex.
      options.onStatus?.({ state, attempt: 0, retryInMs: null });
      return {
        ask,
        close,
        status: () => ({ state, attempt: 0, retryInMs: null }),
      };
    },
  });
  return {
    root,
    screen,
    ask,
    close,
    requests: (next: readonly ApprovalView[]) => {
      requests = next;
    },
    failure: (next: Error | undefined) => {
      listError = next;
    },
    decision: (next: FrameOutcome | Error) => {
      decision = next;
    },
    status: (next: StreamStatus['state']) => {
      state = next;
      subscriber?.onStatus?.({ state, attempt: 0, retryInMs: null });
    },
    push: () =>
      subscriber?.onEvent({ job: 'job_1', kind: 'ended', agent: 'reviewer' }),
    click: (label: string) => {
      const control = [...root.querySelectorAll('button')].find(
        (node) => node.textContent === label,
      );
      if (!control) throw new Error(`Missing ${label}`);
      control.click();
    },
  };
}

describe('account-wide console approvals', () => {
  it('reads once on an already-open shared socket and renders exact command context as text', async () => {
    const view = fixture();
    await view.screen.load();
    expect(view.ask).toHaveBeenCalledExactlyOnceWith('approval.list', {
      mine: true,
    });
    expect(view.root.textContent).toContain('cnv_child');
    expect(view.root.textContent).toContain('/fixture');
    expect(view.root.querySelector('img')).toBeNull();
    expect(view.root.querySelector('pre')?.textContent).toBe(
      JSON.stringify(approval.command),
    );
  });

  it('shows command sets completely and excludes answered requests', async () => {
    const view = fixture();
    view.requests([
      {
        ...approval,
        commands: [
          ['echo', 'one'],
          ['echo', 'two'],
        ],
      },
      { ...approval, id: 'old', state: 'allowed' },
    ]);
    await view.screen.load();
    expect(view.root.querySelectorAll('pre')).toHaveLength(2);
    expect(view.root.querySelector('[data-approval="old"]')).toBeNull();
  });

  it('waits for connection and reconciles requests raised while disconnected', async () => {
    const view = fixture('connecting');
    await view.screen.load();
    expect(view.ask).not.toHaveBeenCalled();
    expect(view.root.textContent).toContain('Connecting');
    view.status('open');
    await vi.waitFor(() =>
      expect(view.root.querySelector('[data-approval]')).not.toBeNull(),
    );
    view.status('reconnecting');
    view.requests([]);
    view.status('open');
    await vi.waitFor(() =>
      expect(view.root.querySelector('[data-empty]')).not.toBeNull(),
    );
  });

  it.each(['Allow once', 'Deny'])(
    'sends %s only once and preserves the server continuation receipt',
    async (label) => {
      const view = fixture();
      await view.screen.load();
      view.click(label);
      view.click(label);
      await vi.waitFor(() => expect(view.root.textContent).toContain('job_1'));
      expect(
        view.ask.mock.calls.filter(([type]) => type === 'approval.answer'),
      ).toHaveLength(1);
      expect(view.ask).toHaveBeenCalledWith('approval.answer', {
        id: 'apr_1',
        decision: label === 'Deny' ? 'deny' : 'once',
      });
      expect(view.root.querySelector('[data-empty]')).not.toBeNull();
    },
  );

  it('says the decision stands when continuation is busy', async () => {
    const view = fixture();
    view.decision({
      code: 'OK',
      payload: {
        id: 'apr_1',
        state: 'allowed',
        job: null,
        busy: true,
        note: 'Wait for the current turn.',
      },
    });
    await view.screen.load();
    view.click('Allow once');
    await vi.waitFor(() =>
      expect(view.root.textContent).toContain('conversation is busy'),
    );
    expect(view.root.textContent).toContain('Wait for the current turn.');
  });

  it('shows a refusal and permits an explicit new decision', async () => {
    const view = fixture();
    view.decision({
      code: 'BAD_REQUEST',
      said: 'This request belongs to another account.',
    });
    await view.screen.load();
    view.click('Allow once');
    await vi.waitFor(() =>
      expect(view.root.textContent).toContain('another account'),
    );
    expect(
      [
        ...view.root.querySelectorAll<HTMLButtonElement>('.approval button'),
      ].every((node) => !node.disabled),
    ).toBe(true);
  });

  it.each([
    new Error('Socket dropped'),
    {
      code: 'INTERNAL_ERROR',
      said: 'The handler failed after recording the decision.',
    },
    {
      code: 'OK',
      payload: {
        id: 'other',
        state: 'allowed',
        job: null,
        busy: false,
        note: null,
      },
    },
    { code: 'OK', payload: { id: 'apr_1', state: 'allowed' } },
  ])(
    'blocks uncertain decisions across reconnects without replaying them',
    async (response) => {
      const view = fixture();
      view.decision(response);
      await view.screen.load();
      view.click('Allow once');
      await vi.waitFor(() =>
        expect(view.root.textContent).toContain('Delivery is uncertain'),
      );
      view.requests([approval]);
      view.status('reconnecting');
      view.status('open');
      await vi.waitFor(() =>
        expect(view.root.querySelector('[data-approval]')).not.toBeNull(),
      );
      expect(
        [
          ...view.root.querySelectorAll<HTMLButtonElement>('.approval button'),
        ].every((node) => node.disabled),
      ).toBe(true);
      expect(
        view.ask.mock.calls.filter(([type]) => type === 'approval.answer'),
      ).toHaveLength(1);
    },
  );

  it('preserves the snapshot and replaces the alert when a refresh fails', async () => {
    const view = fixture();
    await view.screen.load();
    view.failure(new Error('Offline'));
    await view.screen.load();
    await view.screen.load();
    expect(view.root.querySelector('[data-approval]')).not.toBeNull();
    expect(view.root.querySelectorAll('[role="alert"]')).toHaveLength(1);
    view.failure(undefined);
    await view.screen.load();
    expect(view.root.querySelector<HTMLElement>('[role="alert"]')?.hidden).toBe(
      true,
    );
  });

  it('polls outside-tab work and stops on destruction', async () => {
    vi.useFakeTimers();
    const view = fixture('open', 100);
    await view.screen.load();
    view.requests([]);
    await vi.advanceTimersByTimeAsync(100);
    expect(view.root.querySelector('[data-empty]')).not.toBeNull();
    view.screen.destroy();
    await vi.advanceTimersByTimeAsync(500);
    expect(view.ask).toHaveBeenCalledTimes(2);
    expect(view.close).toHaveBeenCalledOnce();
  });
});

describe('the approval SDK boundary', () => {
  it('rejects malformed nested snapshots before they replace visible data', async () => {
    const ask = vi.fn(async () => ({
      code: 'OK',
      payload: { approvals: [{ ...approval, command: [42] }] },
    }));
    const client = socketApprovals({
      ask,
      close: () => {},
      status: () => ({ state: 'open', attempt: 0, retryInMs: null }),
    });
    await expect(client.list()).rejects.toThrow('Unreadable');
  });

  it('rejects invalid decision input without any transport side effect', async () => {
    const ask = vi.fn();
    const client = socketApprovals({
      ask,
      close: () => {},
      status: () => ({ state: 'open', attempt: 0, retryInMs: null }),
    });
    await expect(client.answer('', 'once')).rejects.toThrow();
    expect(ask).not.toHaveBeenCalled();
  });
});

it('disables stale approval controls while disconnected and requires a fresh read before another decision', async () => {
  const view = fixture();
  await view.screen.load();
  view.status('closed');
  view.click('Allow once');
  expect(view.ask).toHaveBeenCalledOnce();
  expect(
    view.root.querySelector<HTMLButtonElement>('.approval-decision')?.disabled,
  ).toBe(true);
  view.status('open');
  await vi.waitFor(() => expect(view.ask).toHaveBeenCalledTimes(2));
  await vi.waitFor(() =>
    expect(
      view.root.querySelector<HTMLButtonElement>('.approval-decision')
        ?.disabled,
    ).toBe(false),
  );
});

it('pauses hidden-tab reads and fences a pending approval snapshot after returning', async () => {
  vi.useFakeTimers();
  const view = fixture('open', 100);
  await view.screen.load();
  let finish: ((result: FrameOutcome) => void) | undefined;
  view.ask.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const pending = view.screen.load();
  const visibility = vi.spyOn(document, 'visibilityState', 'get');
  visibility.mockReturnValue('hidden');
  document.dispatchEvent(new Event('visibilitychange'));
  view.click('Allow once');
  await vi.advanceTimersByTimeAsync(500);
  expect(view.ask).toHaveBeenCalledTimes(2);
  view.requests([]);
  visibility.mockReturnValue('visible');
  document.dispatchEvent(new Event('visibilitychange'));
  if (!finish) throw new Error('Expected pending read');
  finish({
    code: 'OK',
    payload: { approvals: [{ ...approval, id: 'obsolete' }] },
  });
  await pending;
  expect(view.root.textContent).not.toContain('obsolete');
  expect(view.root.querySelector('[data-approval]')).toBeNull();
  expect(
    view.ask.mock.calls.filter(([type]) => type === 'approval.answer'),
  ).toHaveLength(0);
  visibility.mockRestore();
});

describe('selected approval records', () => {
  async function select(
    view: ReturnType<typeof fixture>,
    id: string,
  ): Promise<void> {
    if (!view.screen.showRecord)
      throw new Error('Expected approval record selection');
    await view.screen.showRecord(id);
  }

  it('opens the selected request in its bounded window without answering it', async () => {
    const view = fixture();
    view.requests(
      Array.from({ length: 75 }, (_, index) => ({
        ...approval,
        id: `apr_${index}`,
      })),
    );
    document.body.append(view.root);
    await view.screen.load();
    await select(view, 'apr_65');
    const selected = view.root.querySelector('[data-approval="apr_65"]');
    expect(view.root.querySelectorAll('[data-approval]')).toHaveLength(15);
    expect(selected?.getAttribute('data-selected')).toBe('true');
    expect(document.activeElement).toBe(selected?.querySelector('h3'));
    expect(
      view.root.querySelector('.approval-selection')?.textContent,
    ).toContain('Selected request apr_65');
    expect(
      view.ask.mock.calls.every(([type]) => type === 'approval.list'),
    ).toBe(true);
    view.click('Previous approvals');
    expect(view.root.querySelectorAll('[data-approval]')).toHaveLength(30);
    expect(view.root.querySelector('[data-selected="true"]')).toBeNull();
  });

  it('distinguishes an absent selected request from unreadable current authority', async () => {
    const view = fixture();
    await view.screen.load();
    await select(view, 'apr_1');
    view.failure(new Error('Membership revoked'));
    await view.screen.load();
    expect(
      view.root.querySelector('.approval-selection')?.textContent,
    ).toContain('has not been verified');
    expect(view.root.querySelector('.approval-status')?.textContent).toContain(
      'authority could not be read',
    );
    expect(view.root.querySelector('[data-selected="true"]')).not.toBeNull();
    expect(
      [
        ...view.root.querySelectorAll<HTMLButtonElement>('.approval-decision'),
      ].every((control) => control.disabled),
    ).toBe(true);
    view.failure(undefined);
    view.requests([{ ...approval, state: 'allowed' }]);
    await view.screen.load();
    expect(
      view.root.querySelector('.approval-selection')?.textContent,
    ).toContain('not in your current pending approval list');
    expect(view.root.querySelector('[data-approval]')).toBeNull();
    expect(view.root.querySelector('.approval-receipt')?.textContent).toBe('');
    expect(
      view.ask.mock.calls.every(([type]) => type === 'approval.list'),
    ).toBe(true);
  });

  it('awaits reconciliation of the latest route and discards a delayed older snapshot', async () => {
    const view = fixture();
    await view.screen.load();
    let finish: (result: FrameOutcome) => void = () => {};
    view.ask.mockReturnValueOnce(
      new Promise<FrameOutcome>((resolve) => {
        finish = resolve;
      }),
    );
    const older = select(view, 'apr_old');
    const latest = select(view, 'apr_latest');
    view.requests([{ ...approval, id: 'apr_latest' }]);
    finish({
      code: 'OK',
      payload: {
        approvals: [
          { ...approval, id: 'apr_old', reason: 'Obsolete request context' },
        ],
      },
    });
    await Promise.all([older, latest]);
    expect(
      view.root
        .querySelector('[data-approval="apr_latest"]')
        ?.getAttribute('data-selected'),
    ).toBe('true');
    expect(view.root.textContent).not.toContain('Obsolete request context');
    expect(view.ask).toHaveBeenCalledTimes(3);
    expect(
      view.ask.mock.calls.every(([type]) => type === 'approval.list'),
    ).toBe(true);
  });

  it('rejects a removed request even if an old detached decision control is activated', async () => {
    const view = fixture();
    await view.screen.load();
    const obsolete = view.root.querySelector('.approval-decision');
    if (!obsolete) throw new Error('Expected decision control');
    view.requests([]);
    await view.screen.load();
    obsolete.dispatchEvent(new Event('click'));
    expect(
      view.ask.mock.calls.every(([type]) => type === 'approval.list'),
    ).toBe(true);
  });

  it('keeps a refused decision disabled while the fresh authorization read is pending or refused', async () => {
    const view = fixture();
    await view.screen.load();
    let finish: (result: FrameOutcome) => void = () => {};
    view.ask.mockResolvedValueOnce({
      code: 'BAD_REQUEST',
      said: 'Approval is stale',
    });
    view.ask.mockReturnValueOnce(
      new Promise<FrameOutcome>((resolve) => {
        finish = resolve;
      }),
    );
    view.click('Allow once');
    await vi.waitFor(() =>
      expect(
        view.ask.mock.calls.filter(([type]) => type === 'approval.list'),
      ).toHaveLength(2),
    );
    expect(view.root.querySelector('.approval-receipt')?.textContent).toContain(
      'Approval is stale',
    );
    expect(
      [
        ...view.root.querySelectorAll<HTMLButtonElement>('.approval-decision'),
      ].every((control) => control.disabled),
    ).toBe(true);
    finish({ code: 'BAD_REQUEST', said: 'Membership revoked' });
    await vi.waitFor(() =>
      expect(view.root.textContent).toContain('Membership revoked'),
    );
    view.click('Allow once');
    expect(
      view.ask.mock.calls.filter(([type]) => type === 'approval.answer'),
    ).toHaveLength(1);
    await view.screen.load();
    expect(
      [
        ...view.root.querySelectorAll<HTMLButtonElement>('.approval-decision'),
      ].every((control) => !control.disabled),
    ).toBe(true);
    expect(
      view.ask.mock.calls.filter(([type]) => type === 'approval.answer'),
    ).toHaveLength(1);
  });

  it('keeps decision receipt and current pending snapshot separate and links the returned job', async () => {
    const view = fixture();
    await view.screen.load();
    await select(view, 'apr_1');
    view.click('Allow once');
    await vi.waitFor(() =>
      expect(
        view.root.querySelector('.approval-receipt')?.textContent,
      ).toContain('Continuation job: job_1'),
    );
    expect(
      view.root.querySelector('.approval-receipt a')?.getAttribute('href'),
    ).toBe('#jobs?record=job_1');
    expect(
      view.root.querySelector('.approval-selection')?.textContent,
    ).toContain('not in your current pending approval list');
    expect(view.root.querySelector('.approval-status')?.textContent).toContain(
      '0 pending approvals',
    );
    await view.screen.load();
    expect(view.root.querySelector('.approval-receipt')?.textContent).toContain(
      'Continuation job: job_1',
    );
    expect(
      view.ask.mock.calls.filter(([type]) => type === 'approval.answer'),
    ).toHaveLength(1);
  });
});
