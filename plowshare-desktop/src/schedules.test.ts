import { checkedSender } from './fixture.test-support.ts';
import { present } from './fixture.test-support.ts';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { definitionFromProposal } from 'plowshare-client-ts/operations/schedule-files';
import { ScheduleClient, scheduleIdentity } from './schedules.ts';
import { demoState } from './demo.ts';
import { emptyActivity } from './shared.ts';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type {
  ScheduleProposal,
  ScheduleRecord,
  TriggerRecord,
} from 'plowshare-client-ts/operations/administrative-replies';
const proposal: ScheduleProposal = {
  cron: '0 0 9 * * *',
  zone: 'Australia/Melbourne',
  when: 'Every day at 9am',
  agent: 'assistant',
  task: 'Read the news',
  intoConversation: false,
  project: null,
  conversation: null,
  nextFires: ['2026-10-03T09:00:00+10:00'],
  names: { schedule: 'morning', trigger: 'morning-run', event: 'morning.tick' },
};
const schedule: ScheduleRecord = {
  name: 'morning',
  cron: proposal.cron,
  zone: proposal.zone,
  emits: proposal.names.event,
  paused: false,
  nextFireAt: present(proposal.nextFires[0]),
  definedBy: 'fixture',
};
const trigger: TriggerRecord = {
  name: proposal.names.trigger,
  event: proposal.names.event,
  project: null,
  conversation: null,
  agent: proposal.agent,
  task: proposal.task,
  maxModelCalls: null,
  maxTurns: null,
  queueCap: 3,
  paused: false,
  definedBy: 'fixture',
};
const file = {
  name: 'morning',
  project: null,
  source: 'server',
  path: 'schedules/morning.json',
  internalName: 'scheduled-1-morning',
  definition: definitionFromProposal(proposal),
  status: 'active',
  error: null,
};
function fixture() {
  const state = {
    ...demoState(),
    mode: 'live' as const,
    connected: true,
    activity: emptyActivity(),
  };
  const calls: { type: string; payload: unknown }[] = [];
  let reply: (ask: (typeof calls)[number]) => Outcome | Promise<Outcome> = (
    ask,
  ) => ({
    code: 'OK',
    payload:
      ask.type === 'schedule.read'
        ? proposal
        : ask.type === 'schedule.save'
          ? file
          : [],
  });
  const client = new ScheduleClient(
    () => state,
    checkedSender(async (ask) => {
      calls.push(ask);
      return reply(ask);
    }),
    () => {},
  );
  return {
    state,
    client,
    calls,
    reply: (fn: typeof reply) => {
      reply = fn;
    },
  };
}
await test('preview is read-only and save creates one monitored definition file', async () => {
  const f = fixture();
  await f.client.preview('Read the news every morning', proposal.zone);
  assert.deepEqual(
    f.calls.map((call) => call.type),
    ['schedule.read'],
  );
  await f.client.save(scheduleIdentity(proposal));
  assert.deepEqual(f.calls[1], {
    type: 'schedule.save',
    payload: {
      name: 'morning',
      project: null,
      source: 'server',
      definition: file.definition,
      overwrite: false,
    },
  });
  assert.deepEqual(
    f.calls
      .slice(2)
      .map((call) => call.type)
      .sort(),
    ['firing.list', 'schedule.files', 'schedule.list', 'trigger.list'],
  );
  assert.equal(f.state.activity.schedules!.proposal, undefined);
  assert.match(f.state.activity.schedules!.notice!, /monitoring/);
});
await test('failed or foreign previews cannot replace the reviewed scope or enable save', async () => {
  const f = fixture();
  f.reply(() => ({ code: 'OK', payload: { ...proposal, project: 'foreign' } }));
  await assert.rejects(
    f.client.preview('Read news', proposal.zone),
    /different destination/,
  );
  assert.equal(f.state.activity.schedules!.proposal, undefined);
  await assert.rejects(f.client.save(scheduleIdentity(proposal)), /Review/);
  await assert.rejects(
    f.client.preview('Read news', proposal.zone, 'foreign'),
    /available project/,
  );
});
await test('file conflicts are preserved and no split definition or rollback is sent', async () => {
  const f = fixture();
  await f.client.preview('Daily news', proposal.zone);
  f.reply(() => ({ code: 'CONFLICT', said: 'The file already exists' }));
  await assert.rejects(
    f.client.save(scheduleIdentity(proposal)),
    /already exists/,
  );
  assert.equal(
    f.calls.filter((call) => call.type === 'schedule.save').length,
    1,
  );
  assert.equal(
    f.calls.some(
      (call) => call.type.endsWith('.define') || call.type.endsWith('.forget'),
    ),
    false,
  );
  assert.match(f.state.activity.schedules!.error!, /not be replayed/);
});
await test('refused projection keeps diagnostics and does not report success', async () => {
  const f = fixture();
  await f.client.preview('Daily news', proposal.zone);
  f.reply(() => ({
    code: 'OK',
    payload: { ...file, status: 'refused', error: 'Workspace offline' },
  }));
  await assert.rejects(
    f.client.save(scheduleIdentity(proposal)),
    /Workspace offline/,
  );
  assert.equal(
    f.calls.filter((call) => call.type === 'schedule.save').length,
    1,
  );
});
await test('connection replacement cannot replay a file save or refresh stale state', async () => {
  const f = fixture();
  await f.client.preview('Daily news', proposal.zone);
  let release!: (value: Outcome) => void;
  f.reply(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const saving = f.client.save(scheduleIdentity(proposal));
  await new Promise((resolve) => setTimeout(resolve, 0));
  f.client.reset();
  f.state.connected = false;
  release({ code: 'OK', payload: file });
  await assert.rejects(saving, /connection changed/);
  assert.equal(
    f.calls.filter((call) => call.type === 'schedule.save').length,
    1,
  );
  assert.equal(
    f.calls.some((call) => call.type === 'schedule.list'),
    false,
  );
});
await test('edited skill, routing and workspace source are saved as reviewed', async () => {
  const f = fixture();
  await f.client.preview('Daily review', proposal.zone);
  const definition = {
    ...file.definition,
    action: {
      kind: 'skill' as const,
      agent: 'assistant',
      name: 'review',
      input: 'exact\narguments',
      mode: 'NEW' as const,
    },
    target: {
      kind: 'message' as const,
      project: null,
      conversation: null,
      to: null,
      route: 'review-route',
    },
  };
  await f.client.save(scheduleIdentity(proposal), definition, 'workspace', 'p');
  assert.deepEqual(present(f.calls[1]).payload, {
    name: 'morning',
    project: 'p',
    source: 'workspace',
    definition,
    overwrite: false,
  });
});
await test('malformed account lists retain the complete previous schedule/trigger/firing snapshot', async () => {
  const f = fixture();
  f.reply((ask) => ({
    code: 'OK',
    payload:
      ask.type === 'schedule.list'
        ? [schedule]
        : ask.type === 'trigger.list'
          ? [trigger]
          : [],
  }));
  await f.client.refresh();
  const value = f.state.activity.schedules!;
  f.reply((ask) => ({
    code: 'OK',
    payload: ask.type === 'trigger.list' ? [{ name: 'broken' }] : [],
  }));
  await f.client.refresh();
  assert.deepEqual(value.schedules, [schedule]);
  assert.deepEqual(value.triggers, [trigger]);
  assert.match(value.error!, /incomplete|Unreadable/);
});
await test('pause/remove/fire reread the displayed identity and never start an agent turn', async () => {
  const f = fixture();
  f.reply((ask) =>
    ['schedule.pause', 'trigger.forget'].includes(ask.type)
      ? { code: 'NO_CONTENT', payload: null }
      : {
          code: 'OK',
          payload:
            ask.type === 'schedule.list'
              ? [schedule]
              : ask.type === 'trigger.list'
                ? [trigger]
                : [],
        },
  );
  await f.client.refresh();
  await f.client.change(
    'schedule',
    schedule.name,
    scheduleIdentity(schedule),
    true,
  );
  await f.client.change('trigger', trigger.name, scheduleIdentity(trigger));
  await f.client.fire(trigger.name, scheduleIdentity(trigger));
  assert.deepEqual(
    f.calls.find((call) => call.type === 'schedule.pause')!.payload,
    { schedule: 'morning', paused: true },
  );
  assert.deepEqual(
    f.calls.find((call) => call.type === 'event.fire')!.payload,
    { event: 'morning.tick' },
  );
  assert.equal(
    f.calls.some((call) => call.type === 'agent.run'),
    false,
  );
  await assert.rejects(
    f.client.change('trigger', 'foreign', 'anything'),
    /displayed/,
  );
});
await test('stale displayed configuration blocks pause/removal and event emission', async () => {
  const f = fixture();
  f.reply((ask) => ({
    code: 'OK',
    payload:
      ask.type === 'schedule.list'
        ? [schedule]
        : ask.type === 'trigger.list'
          ? [trigger]
          : [],
  }));
  await f.client.refresh();
  f.reply((ask) => ({
    code: 'OK',
    payload:
      ask.type === 'schedule.list'
        ? [{ ...schedule, cron: 'changed' }]
        : [{ ...trigger, task: 'changed' }],
  }));
  await assert.rejects(
    f.client.change(
      'schedule',
      schedule.name,
      scheduleIdentity(schedule),
      true,
    ),
    /changed elsewhere/,
  );
  await assert.rejects(
    f.client.fire(trigger.name, scheduleIdentity(trigger)),
    /changed elsewhere/,
  );
  assert.equal(
    f.calls.some((call) =>
      ['schedule.pause', 'event.fire'].includes(call.type),
    ),
    false,
  );
});

await test('Application schedule controls use one runtime operation without changing source or triggers', async () => {
  const f = fixture();
  let paused = true;
  const managed = { ...schedule, name: file.internalName, paused };
  f.reply((ask) => {
    if (ask.type === 'schedule.pause') {
      if (
        typeof ask.payload !== 'object' ||
        ask.payload === null ||
        !('paused' in ask.payload) ||
        typeof ask.payload.paused !== 'boolean'
      )
        throw new Error('Invalid pause request');
      paused = ask.payload.paused;
      return { code: 'NO_CONTENT', payload: null };
    }
    return {
      code: 'OK',
      payload:
        ask.type === 'schedule.list'
          ? [{ ...managed, paused }]
          : ask.type === 'schedule.files'
            ? [file]
            : [],
    };
  });
  await f.client.refresh();
  await f.client.change(
    'schedule',
    managed.name,
    scheduleIdentity(managed),
    false,
  );
  const active = { ...managed, paused: false };
  await f.client.change(
    'schedule',
    active.name,
    scheduleIdentity(active),
    true,
  );
  assert.deepEqual(
    f.calls
      .filter((call) => call.type === 'schedule.pause')
      .map((call) => call.payload),
    [
      { schedule: file.internalName, paused: false },
      { schedule: file.internalName, paused: true },
    ],
  );
  assert.equal(
    f.calls.some((call) =>
      [
        'trigger.pause',
        'schedule.save',
        'application.deploy',
        'agent.run',
      ].includes(call.type),
    ),
    false,
  );
});
