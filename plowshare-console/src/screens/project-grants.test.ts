import { afterEach, expect, it, vi } from 'vitest';
import type { ApprovalView } from '../../../sdk/typescript/src/operations/administrative-replies.ts';
import { socketProjectGrants } from '../approvals';
import type { FrameOutcome } from '../events';
import { createProjectGrants } from './project-grants';
import type { Screen } from './screen';

const grant: ApprovalView = {
  id: 'apr_one',
  conversation: 'cnv_one',
  askedIn: 'cnv_one',
  agent: 'builder',
  side: 'server',
  command: ['echo', 'two words', '"quoted"'],
  cwd: '/fixture',
  reason: '',
  state: 'allowed',
  scope: 'project',
  prefix: ['echo', 'two words'],
  defaultPrefix: ['echo'],
  createdAt: '2026-10-08T00:00:00Z',
  answeredAt: '2026-10-08T00:01:00Z',
  commands: null,
  judged: null,
};
const screens: Screen[] = [];
afterEach(() => {
  for (const screen of screens.splice(0)) screen.destroy();
});

function fixture() {
  const root = document.createElement('section');
  const blocked = new Map<string, string>();
  let listed: unknown = { approvals: [grant] };
  let result: FrameOutcome = {
    code: 'OK',
    payload: { id: grant.id, revoked: true },
  };
  let problem: Error | undefined;
  const ask = vi.fn(
    async (type: string, _payload?: unknown): Promise<FrameOutcome> => {
      if (type === 'approval.list') return { code: 'OK', payload: listed };
      if (problem) throw problem;
      return result;
    },
  );
  const client = socketProjectGrants({
    ask,
    close: () => {},
    status: () => ({ state: 'open', attempt: 0, retryInMs: null }),
  });
  const mount = () => {
    const screen = createProjectGrants({
      root,
      project: 'fixture',
      client,
      active: true,
      blocked,
    });
    screens.push(screen);
    return screen;
  };
  const screen = mount();
  const control = () => {
    const button = root.querySelector<HTMLButtonElement>('[data-revoke]');
    if (!button) throw new Error('Expected grant revocation control');
    return button;
  };
  return {
    root,
    ask,
    client,
    screen,
    mount,
    control,
    list: (value: unknown) => {
      listed = value;
    },
    receipt: (value: FrameOutcome) => {
      result = value;
    },
    failure: (value: Error) => {
      problem = value;
    },
  };
}

it('renders argv boundaries and bounds grant presentation without pretending the reply is paged', async () => {
  const view = fixture();
  view.list({
    approvals: Array.from({ length: 65 }, (_, n) => ({
      ...grant,
      id: `apr_${n}`,
    })),
  });
  await view.screen.load();
  expect(view.root.querySelectorAll('[data-revoke]')).toHaveLength(30);
  expect(view.root.querySelector('.approved-prefix')?.textContent).toBe(
    '["echo","two words"]',
  );
  expect(view.root.textContent).toContain('server replies are unpaged');
  const next = view.root.querySelector<HTMLButtonElement>('button.next');
  if (!next) throw new Error('Expected next window');
  next.click();
  next.click();
  expect(view.root.querySelectorAll('[data-revoke]')).toHaveLength(5);
  expect(view.ask).toHaveBeenCalledOnce();
});

it.each([
  { approvals: [{ ...grant, prefix: [7] }] },
  { approvals: [{ ...grant, state: 'asked' }] },
  { approvals: [{ ...grant, scope: 'conversation' }] },
  {},
])('fails closed on a malformed or non-standing snapshot', async (payload) => {
  const view = fixture();
  await view.screen.load();
  view.list(payload);
  await view.screen.load();
  expect(view.control().disabled).toBe(true);
  expect(view.root.querySelector<HTMLElement>('[role="alert"]')?.hidden).toBe(
    false,
  );
  expect(view.root.textContent).not.toContain('No command is approved');
  view.control().click();
  expect(view.ask.mock.calls.map(([type]) => type)).toEqual([
    'approval.list',
    'approval.list',
  ]);
});

it.each([
  { code: 'OK', payload: { id: 'another', revoked: true } },
  { code: 'OK', payload: { id: grant.id, revoked: 'yes' } },
  { code: 'INTERNAL_ERROR', said: 'handler failed' },
])(
  'keeps an uncertain revocation blocked across refresh, reconnect and parent redraw',
  async (receipt) => {
    const view = fixture();
    view.receipt(receipt);
    await view.screen.load();
    view.control().click();
    view.control().click();
    await vi.waitFor(() =>
      expect(view.root.textContent).toContain('Delivery is uncertain'),
    );
    view.screen.setActive?.(false);
    view.screen.setActive?.(true);
    await view.screen.load();
    view.screen.destroy();
    const next = view.mount();
    await next.load();
    expect(view.control().disabled).toBe(true);
    expect(
      view.ask.mock.calls.filter(([type]) => type === 'approval.revoke'),
    ).toHaveLength(1);
  },
);

it('does not replay a lost receipt and reconciles the grant disappearance through a read', async () => {
  const view = fixture();
  await view.screen.load();
  view.failure(new Error('connection lost after submission'));
  view.list({ approvals: [] });
  view.control().click();
  await vi.waitFor(() =>
    expect(view.root.querySelector('[data-revoke]')).toBeNull(),
  );
  expect(
    view.ask.mock.calls.filter(([type]) => type === 'approval.revoke'),
  ).toHaveLength(1);
  expect(view.root.textContent).toContain('No command is approved');
});

it('reconciles an explicit authority refusal without reporting a successful revocation', async () => {
  const view = fixture();
  await view.screen.load();
  view.receipt({ code: 'BAD_REQUEST', said: 'Membership was revoked.' });
  view.control().click();
  await vi.waitFor(() =>
    expect(view.root.textContent).toContain('Membership was revoked.'),
  );
  expect(view.root.textContent).not.toContain('Revocation recorded');
  expect(
    view.ask.mock.calls.filter(([type]) => type === 'approval.revoke'),
  ).toHaveLength(1);
});

it('coalesces reads, fences a late response from a hidden visit, and disables inactive controls', async () => {
  const view = fixture();
  await view.screen.load();
  let finish: ((result: FrameOutcome) => void) | undefined;
  view.ask.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const reading = view.screen.load();
  await view.screen.load();
  view.screen.setActive?.(false);
  view.control().click();
  view.list({ approvals: [] });
  view.screen.setActive?.(true);
  if (!finish) throw new Error('Expected pending read');
  finish({
    code: 'OK',
    payload: { approvals: [{ ...grant, id: 'obsolete' }] },
  });
  await reading;
  expect(view.root.textContent).not.toContain('obsolete');
  expect(view.root.querySelector('[data-revoke]')).toBeNull();
  expect(view.ask.mock.calls.map(([type]) => type)).toEqual([
    'approval.list',
    'approval.list',
    'approval.list',
  ]);
});

it('does not draw after destruction or send invalid IDs through the transport', async () => {
  const view = fixture();
  let finish: ((result: FrameOutcome) => void) | undefined;
  view.ask.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const reading = view.screen.load();
  view.screen.destroy();
  const snapshot = view.root.textContent;
  if (!finish) throw new Error('Expected pending read');
  finish({ code: 'OK', payload: { approvals: [grant] } });
  await reading;
  expect(view.root.textContent).toBe(snapshot);
  view.ask.mockClear();
  await expect(view.client.revoke('')).rejects.toThrow();
  await expect(view.client.list('')).rejects.toThrow();
  expect(view.ask).not.toHaveBeenCalled();
});
