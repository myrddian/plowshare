import { describe, expect, it, vi } from 'vitest';
import type { RequestOptions } from '../../../sdk/typescript/src/binding/acting.ts';
import { ConnectionFault } from '../../../sdk/typescript/src/binding/connection.ts';
import type {
  ConversationGoalStatus,
  GoalStatus,
} from '../../../sdk/typescript/src/operations/replies.ts';
import type { Answer } from '../../../sdk/typescript/src/operations/response.ts';
import { checkedTransport } from '../../../sdk/typescript/src/operations/transport.ts';
import { createGoalAgents } from './goal-agents';
import type { Transport } from './screen';

const firstGoal = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const secondGoal = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const generation = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
const first: GoalStatus = {
  goal: firstGoal,
  project: 'project-a',
  conversation: 'cnv_GoalA',
  instance: 'ins_GoalA',
  agent: 'goal_worker',
  objective: '<img src=x onerror=alert(1)> Check the report',
  lifecycle: 'STANDING',
  state: 'WAITING',
  version: 4,
  readable: true,
  reason: null,
  detail: 'Waiting for the next batch',
  latestResult: {
    job: 'job_GoalA',
    turn: 8,
    summary: 'Collected the report',
    evidence: ['<script> do not render this as markup </script>'],
  },
  nextScheduleAt: '2026-11-27T12:00:00Z',
  schedules: [
    {
      schedule: 'daily',
      generation,
      paused: false,
      nextFireAt: '2026-11-27T12:00:00Z',
    },
  ],
  relayListeners: [
    {
      binding: generation,
      topic: 'news.report',
      generation,
      failure: null,
      pendingBatch: {
        id: generation,
        state: 'OPEN',
        memberCount: 2,
        deadline: '2026-11-27T12:00:00Z',
      },
    },
  ],
  moreSchedules: false,
  moreRelayListeners: false,
};
const pauseReceipt: ConversationGoalStatus = {
  goal: first.goal,
  conversation: first.conversation,
  agent: 'goal_worker',
  objective: 'Check the report',
  state: 'PAUSED',
  version: 5,
  activations: 1,
  maxActivations: 8,
  lastJob: 'job_GoalA',
  lastTurn: 8,
  reason: 'OPERATOR_PAUSED',
  detail: null,
  criteria: null,
  modelCallsSpent: 1,
  modelCallLimit: 8,
};
const restricted: GoalStatus = {
  ...first,
  goal: secondGoal,
  conversation: 'cnv_Restricted',
  instance: 'ins_Restricted',
  agent: null,
  objective: null,
  detail: null,
  latestResult: null,
  nextScheduleAt: null,
  schedules: [],
  relayListeners: [],
  readable: false,
};
function fixture(
  reply: (
    type: string,
    payload?: unknown,
    options?: RequestOptions,
  ) => Promise<Answer>,
) {
  const root = document.createElement('div');
  const ask = vi.fn(reply);
  const get = vi.fn(async (path: string) => {
    if (path === '/v1/projects')
      return [{ name: 'project-a' }, { name: 'project-b' }];
    return undefined;
  });
  const screen = createGoalAgents({
    root,
    project: 'project-a',
    operations: checkedTransport({ ask }),
    // The existing trajectory owns its typed compatibility paths. Record every read.
    transport: { get } as unknown as Transport,
  });
  function control(selector: string): HTMLButtonElement {
    const found = root.querySelector<HTMLButtonElement>(selector);
    if (!found) throw new Error(`Missing control ${selector}`);
    return found;
  }
  return { root, ask, get, screen, control };
}
function ok(payload: unknown): Promise<Answer> {
  return Promise.resolve({ code: 'OK', payload });
}

describe('Goal Agents screen', () => {
  it('renders authorized discovery and opens only the selected conversation history and TODOs', async () => {
    const { root, ask, get, screen, control } = fixture((type, payload) => {
      if (type === 'goal.list') return ok({ goals: [first], next: firstGoal });
      if (type === 'goal.status') return ok(first);
      if (type === 'todos.read')
        return ok([
          {
            id: 'task-1',
            parent: null,
            position: 0,
            text: 'Check logs',
            status: 'OPEN',
            summary: null,
            locked: false,
            stage: null,
            updatedAt: '2026-11-27T12:00:00Z',
          },
        ]);
      return Promise.reject(
        new Error(`Unexpected request ${type} ${JSON.stringify(payload)}`),
      );
    });
    await screen.load();
    expect(ask).toHaveBeenCalledWith(
      'goal.list',
      { project: 'project-a' },
      expect.objectContaining({ timeoutMs: 2000 }),
    );
    expect(root.textContent).toContain(first.objective);
    expect(root.textContent).toContain('Collected the report');
    expect(root.textContent).toContain('2026-11-27T12:00:00Z');
    control('.goal-open').click();
    await vi.waitFor(() =>
      expect(root.textContent).toContain('open batch, 2 events'),
    );
    expect(root.textContent).toContain(first.latestResult?.evidence[0]);
    expect(root.querySelector('img,script')).toBeNull();
    control('.goal-conversation').click();
    await vi.waitFor(() => expect(root.textContent).toContain('Check logs'));
    expect(ask).toHaveBeenCalledWith(
      'todos.read',
      { conversation: 'cnv_GoalA' },
      expect.anything(),
    );
    expect(get).toHaveBeenCalledWith(
      expect.stringMatching(/^\/v1\/conversations\/cnv_GoalA\/chat\?/u),
    );
    expect(root.querySelector('textarea')).toBeNull();
    screen.destroy();
  });

  it('keeps stable cursors and withholds all history and control actions on a restricted row', async () => {
    const { root, ask, screen, control } = fixture((type, payload) => {
      if (type === 'goal.status') return ok(restricted);
      return ok(
        payload && typeof payload === 'object' && 'after' in payload
          ? { goals: [restricted], next: null }
          : { goals: [first], next: firstGoal },
      );
    });
    await screen.load();
    control('.page-on').click();
    await vi.waitFor(() =>
      expect(root.textContent).toContain('Restricted Goal Agent'),
    );
    expect(ask).toHaveBeenLastCalledWith(
      'goal.list',
      { project: 'project-a', after: firstGoal },
      expect.anything(),
    );
    control('.goal-open').click();
    await vi.waitFor(() => expect(control('.goal-status')).toBeTruthy());
    expect(
      root.querySelector(
        '.goal-conversation,.goal-pause,.goal-resume,.goal-cancel',
      ),
    ).toBeNull();
    expect(ask.mock.calls.some(([type]) => type === 'todos.read')).toBe(false);
    control('.page-back').click();
    await vi.waitFor(() => expect(root.textContent).toContain(first.objective));
    expect(ask).toHaveBeenLastCalledWith(
      'goal.list',
      { project: 'project-a' },
      expect.anything(),
    );
    screen.destroy();
  });

  it('drops the old project page when its pending reply lands after a project switch', async () => {
    let resolveOld: (answer: Answer) => void = () => {
      throw new Error('No previous response');
    };
    const old = new Promise<Answer>((resolve) => {
      resolveOld = resolve;
    });
    const { root, ask, screen } = fixture((type, payload) =>
      type === 'goal.list' &&
      payload &&
      typeof payload === 'object' &&
      'project' in payload &&
      payload.project === 'project-a'
        ? old
        : ok({ goals: [], next: null }),
    );
    const boot = screen.load();
    await vi.waitFor(() => expect(ask).toHaveBeenCalledTimes(1));
    const choice = root.querySelector('select');
    if (!choice) throw new Error('Missing project control');
    choice.value = 'project-b';
    choice.dispatchEvent(new Event('change'));
    await vi.waitFor(() =>
      expect(root.textContent).toContain('This project has no Goal Agents'),
    );
    expect(ask.mock.calls[0]?.[2]?.signal?.aborted).toBe(true);
    resolveOld({ code: 'OK', payload: { goals: [first], next: null } });
    await boot;
    expect(root.textContent).not.toContain(first.objective);
    expect(ask).toHaveBeenLastCalledWith(
      'goal.list',
      { project: 'project-b' },
      expect.anything(),
    );
    screen.destroy();
  });

  it('can cancel a paused Goal whose optional resume limit is invalid', async () => {
    const paused: GoalStatus = { ...first, state: 'PAUSED' };
    const { root, ask, screen, control } = fixture((type) => {
      if (type === 'goal.list') return ok({ goals: [paused], next: null });
      if (type === 'goal.status') return ok(paused);
      return Promise.resolve({ code: 'CONFLICT' });
    });
    await screen.load();
    control('.goal-open').click();
    await vi.waitFor(() => expect(control('.goal-resume')).toBeTruthy());
    const limit = root.querySelector<HTMLInputElement>('input[type="number"]');
    if (!limit) throw new Error('Missing resume limit');
    limit.value = '1001';
    expect(limit.checkValidity()).toBe(false);

    control('.goal-resume').click();
    expect(
      ask.mock.calls.some(([type]) => type === 'conversation.goal.resume'),
    ).toBe(false);
    control('.goal-cancel').click();
    await vi.waitFor(() => {
      const payload = ask.mock.calls.find(
        ([type]) => type === 'conversation.goal.cancel',
      )?.[1];
      expect(payload).toMatchObject({
        goal: firstGoal,
        conversation: first.conversation,
        version: first.version,
      });
      expect(payload).not.toHaveProperty('maxActivations');
      expect(payload).not.toHaveProperty('maxModelCalls');
    });
    screen.destroy();
  });

  it('reconciles after a lost control reply without resubmitting its effect', async () => {
    let saved = '';
    let committed = false;
    let availableReceipt = false;
    const { root, ask, screen, control } = fixture((type, payload) => {
      switch (type) {
        case 'goal.list':
          return ok({ goals: [first], next: null });
        case 'goal.status':
          return ok(
            committed ? { ...first, state: 'PAUSED', version: 5 } : first,
          );
        case 'conversation.goal.pause':
          if (
            !payload ||
            typeof payload !== 'object' ||
            !('requestId' in payload) ||
            typeof payload.requestId !== 'string'
          )
            throw new Error('Expected stable request ID');
          saved = payload.requestId;
          committed = true;
          return Promise.reject(
            new ConnectionFault('RESPONSE_LOST', 'Control reply lost'),
          );
        case 'conversation.goal.get':
          return availableReceipt
            ? ok(pauseReceipt)
            : Promise.resolve({ code: 'NOT_FOUND' });
        default:
          return Promise.reject(new Error(`Unexpected request ${type}`));
      }
    });
    await screen.load();
    control('.goal-open').click();
    await vi.waitFor(() => expect(control('.goal-pause')).toBeTruthy());
    control('.goal-pause').click();
    await vi.waitFor(() => expect(control('.goal-resume').disabled).toBe(true));
    expect(root.textContent).toContain('Control outcome pending');
    expect(ask).toHaveBeenCalledWith(
      'conversation.goal.pause',
      {
        goal: firstGoal,
        conversation: first.conversation,
        version: 4,
        requestId: saved,
      },
      expect.objectContaining({ nonBlocking: true }),
    );
    availableReceipt = true;
    control('.goal-status').click();
    await vi.waitFor(() =>
      expect(control('.goal-resume').disabled).toBe(false),
    );
    expect(ask).toHaveBeenLastCalledWith(
      'conversation.goal.get',
      {
        conversation: first.conversation,
        requestId: saved,
      },
      expect.anything(),
    );
    expect(
      ask.mock.calls.filter(([type]) => type === 'conversation.goal.pause'),
    ).toHaveLength(1);
    screen.destroy();
  });

  it.each([4, 5])(
    'redacts the list and details when a receipt restricts access after status version %i',
    async (statusVersion) => {
      const sensitiveNotice = 'Private refusal detail';
      let submitted = false;
      let accessRestored = false;
      const { root, ask, screen, control } = fixture((type) => {
        switch (type) {
          case 'goal.list':
            return ok({ goals: [first], next: null });
          case 'goal.status':
            if (!submitted) return ok(first);
            return ok({
              ...first,
              state:
                statusVersion === 4 && !accessRestored ? 'WAITING' : 'PAUSED',
              version: accessRestored ? 5 : statusVersion,
            });
          case 'conversation.goal.pause':
            submitted = true;
            if (statusVersion === 4)
              return Promise.resolve({
                code: 'BAD_REQUEST',
                said: sensitiveNotice,
              });
            return Promise.reject(
              new ConnectionFault('RESPONSE_LOST', 'Control reply lost'),
            );
          case 'conversation.goal.get':
            return ok(
              accessRestored
                ? pauseReceipt
                : {
                    ...pauseReceipt,
                    agent: '',
                    objective: '',
                    lastJob: null,
                    lastTurn: null,
                  },
            );
          default:
            throw new Error(`Unexpected operation ${type}`);
        }
      });
      try {
        await screen.load();
        control('.goal-open').click();
        await vi.waitFor(() =>
          expect(control('.goal-pause').disabled).toBe(false),
        );
        expect(root.textContent).toContain(first.objective);
        expect(root.textContent).toContain('Result evidence');

        control('.goal-pause').click();
        await vi.waitFor(() =>
          expect(
            root.textContent?.match(/Conversation content is restricted\./gu),
          ).toHaveLength(2),
        );
        for (const text of [
          first.objective,
          first.detail,
          first.latestResult?.summary,
          first.latestResult?.evidence[0],
          first.schedules[0]?.schedule,
          first.relayListeners[0]?.topic,
          sensitiveNotice,
          'Control outcome pending',
        ]) {
          if (text) expect(root.textContent).not.toContain(text);
        }
        expect(
          root.querySelector(
            '.goal-conversation,.goal-pause,.goal-resume,.goal-cancel',
          ),
        ).toBeNull();
        const pause = ask.mock.calls.find(
          ([type]) => type === 'conversation.goal.pause',
        )?.[1];
        if (!pause || typeof pause !== 'object' || !('requestId' in pause))
          throw new Error('Expected the original control request ID');
        expect(ask).toHaveBeenCalledWith(
          'conversation.goal.get',
          {
            conversation: first.conversation,
            requestId: pause.requestId,
          },
          expect.anything(),
        );

        accessRestored = true;
        control('.goal-status').click();
        await vi.waitFor(() =>
          expect(control('.goal-resume').disabled).toBe(false),
        );
        expect(
          ask.mock.calls.filter(([type]) => type === 'conversation.goal.get'),
        ).toHaveLength(statusVersion === 4 ? 2 : 1);
        expect(
          ask.mock.calls.filter(([type]) => type === 'conversation.goal.pause'),
        ).toHaveLength(1);
      } finally {
        screen.destroy();
      }
    },
  );

  it('refreshes the same goal when a control settles during receipt inspection', async () => {
    let committed = false;
    let resolveControl: (answer: Answer) => void = () => {
      throw new Error('No pending control');
    };
    let resolveReceipt: (answer: Answer) => void = () => {
      throw new Error('No pending receipt');
    };
    const controlReply = new Promise<Answer>((resolve) => {
      resolveControl = resolve;
    });
    const staleReceipt = new Promise<Answer>((resolve) => {
      resolveReceipt = resolve;
    });
    const { ask, screen, control } = fixture((type) => {
      switch (type) {
        case 'goal.list':
          return ok({ goals: [first], next: null });
        case 'goal.status':
          return ok(
            committed ? { ...first, state: 'PAUSED', version: 5 } : first,
          );
        case 'conversation.goal.pause':
          return controlReply;
        case 'conversation.goal.get':
          return staleReceipt;
        default:
          throw new Error(`Unexpected operation ${type}`);
      }
    });
    try {
      await screen.load();
      control('.goal-open').click();
      await vi.waitFor(() =>
        expect(control('.goal-pause').disabled).toBe(false),
      );
      control('.goal-pause').click();
      control('.goal-open').click();
      await vi.waitFor(() =>
        expect(
          ask.mock.calls.filter(([type]) => type === 'conversation.goal.get'),
        ).toHaveLength(1),
      );

      committed = true;
      resolveControl({ code: 'OK', payload: pauseReceipt });
      await vi.waitFor(() =>
        expect(
          ask.mock.calls.filter(([type]) => type === 'goal.status'),
        ).toHaveLength(3),
      );
      expect(
        ask.mock.calls.find(([type]) => type === 'conversation.goal.get')?.[2]
          ?.signal?.aborted,
      ).toBe(true);
      resolveReceipt({ code: 'OK', payload: pauseReceipt });
      await staleReceipt;
      await vi.waitFor(() =>
        expect(control('.goal-resume').disabled).toBe(false),
      );
      expect(
        ask.mock.calls.filter(([type]) => type === 'conversation.goal.pause'),
      ).toHaveLength(1);
    } finally {
      screen.destroy();
    }
  });
});
