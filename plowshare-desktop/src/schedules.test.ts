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
await test('available collections publish while files are held and retain state on isolated refusal', async () => {
  const f = fixture();
  let finish!: (answer: Outcome) => void;
  const held = new Promise<Outcome>((resolve) => {
    finish = resolve;
  });
  f.reply((ask) =>
    ask.type === 'schedule.files'
      ? held
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
  const refresh = f.client.refresh();
  await new Promise<void>((resolve) => setImmediate(resolve));
  assert.equal(f.state.activity.schedules?.schedules[0]?.name, schedule.name);
  assert.equal(f.state.activity.schedules?.triggers[0]?.name, trigger.name);
  assert.equal(f.state.activity.schedules?.schedulePage?.loading, false);
  assert.equal(f.state.activity.schedules?.filePage?.loading, true);
  finish({ code: 'BAD_REQUEST', said: 'File source unavailable' });
  await refresh;
  assert.match(
    f.state.activity.schedules?.panels?.files?.error ?? '',
    /File source unavailable/,
  );
  assert.equal(f.state.activity.schedules?.schedules[0]?.name, schedule.name);
  assert.equal(f.state.activity.schedules?.loading, false);
});

await test('reset fences held panel success and failure without clearing replacement state', async () => {
  const f = fixture();
  let finish!: (answer: Outcome) => void;
  const held = new Promise<Outcome>((resolve) => {
    finish = resolve;
  });
  f.reply(() => held);
  const refresh = f.client.refresh();
  f.client.reset();
  finish({ code: 'BAD_REQUEST', said: 'Obsolete source error' });
  await refresh;
  assert.equal(f.state.activity.schedules?.panels, undefined);
  assert.equal(f.state.activity.schedules?.error, undefined);
  assert.equal(f.state.activity.schedules?.loading, false);
});
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
await test('malformed account collection retains its snapshot while valid collections update', async () => {
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
  assert.deepEqual(value.schedules, []);
  assert.deepEqual(value.triggers, [trigger]);
  assert.match(value.panels?.triggers?.error ?? '', /incomplete|Unreadable/);
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

const scheduleRows = Array.from({ length: 125 }, (_, index) => ({
  ...schedule,
  name: `schedule-${String(index).padStart(3, '0')}`,
}));
const fileRows = Array.from({ length: 108 }, (_, index) => ({
  ...file,
  internalName: `internal-${String(index).padStart(3, '0')}`,
}));
function cursor(payload: unknown): string | undefined {
  if (
    typeof payload === 'object' &&
    payload !== null &&
    'after' in payload &&
    typeof payload.after === 'string'
  )
    return payload.after;
  return undefined;
}
function listing(ask: { type: string; payload: unknown }): Outcome {
  const after = cursor(ask.payload);
  const rows =
    ask.type === 'schedule.list'
      ? scheduleRows
      : ask.type === 'schedule.files'
        ? fileRows
        : [];
  const start =
    after === undefined
      ? 0
      : rows.findIndex(
          (row) =>
            ('internalName' in row ? row.internalName : row.name) === after,
        ) + 1;
  return { code: 'OK', payload: rows.slice(start, start + 51) };
}
await test('schedule and file pages independently use 50 visible rows and the last visible unique key', async () => {
  const f = fixture();
  f.reply(listing);
  await f.client.refresh();
  const value = f.state.activity.schedules!;
  assert.equal(value.schedules.length, 50);
  assert.equal(value.files?.length, 50);
  assert.deepEqual(value.schedulePage, {
    number: 1,
    more: true,
    loading: false,
  });
  assert.deepEqual(value.filePage, { number: 1, more: true, loading: false });
  const start = f.calls.length;
  await f.client.page('schedules', 'next');
  assert.deepEqual(f.calls.slice(start), [
    { type: 'schedule.list', payload: { limit: 51, after: 'schedule-049' } },
  ]);
  assert.equal(value.schedules[0]?.name, 'schedule-050');
  assert.equal(value.files?.[0]?.internalName, 'internal-000');
  await f.client.page('files', 'next');
  assert.deepEqual(f.calls.at(-1), {
    type: 'schedule.files',
    payload: { limit: 51, after: 'internal-049' },
  });
  assert.equal(value.files?.[0]?.internalName, 'internal-050');
  await f.client.page('schedules', 'next');
  assert.equal(value.schedules.length, 25);
  assert.equal(value.schedulePage?.more, false);
  await f.client.page('schedules', 'previous');
  assert.equal(value.schedules[0]?.name, 'schedule-050');
  await f.client.page('schedules', 'first');
  assert.equal(value.schedules[0]?.name, 'schedule-000');
  assert.equal(value.files?.[0]?.internalName, 'internal-050');
});
await test('page refusal preserves current rows and cursor so retry cannot skip unseen data', async () => {
  const f = fixture();
  f.reply(listing);
  await f.client.refresh();
  const value = f.state.activity.schedules!,
    shown = value.schedules;
  f.reply(() => ({
    code: 'BAD_REQUEST',
    said: 'Current account access refused',
  }));
  await f.client.page('schedules', 'next');
  assert.equal(value.schedules, shown);
  assert.equal(value.schedulePage?.number, 1);
  assert.match(value.schedulePage?.error ?? '', /account access refused/);
  f.reply(listing);
  await f.client.page('schedules', 'next');
  assert.equal(value.schedules[0]?.name, 'schedule-050');
  assert.equal(cursor(f.calls.at(-1)?.payload), 'schedule-049');
});
await test('refresh and account reset fence delayed page replies without replacing new rows', async () => {
  const f = fixture();
  f.reply(listing);
  await f.client.refresh();
  let release: ((outcome: Outcome) => void) | undefined;
  f.reply((ask) =>
    cursor(ask.payload) === undefined
      ? listing(ask)
      : new Promise((resolve) => {
          release = resolve;
        }),
  );
  const pending = f.client.page('schedules', 'next');
  await new Promise((resolve) => setTimeout(resolve, 0));
  f.reply((ask) => ({
    code: 'OK',
    payload:
      ask.type === 'schedule.list'
        ? [{ ...schedule, name: 'new-current' }]
        : [],
  }));
  await f.client.refresh();
  if (!release) throw new Error('Expected pending page');
  release({ code: 'OK', payload: scheduleRows.slice(50, 101) });
  await pending;
  assert.equal(f.state.activity.schedules?.schedules[0]?.name, 'new-current');
  assert.equal(f.state.activity.schedules?.schedulePage?.number, 1);
  f.reply(listing);
  await f.client.refresh();
  f.reply(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const replaced = f.client.page('files', 'next');
  await new Promise((resolve) => setTimeout(resolve, 0));
  f.client.reset();
  f.state.handle = 'another-account';
  release({ code: 'OK', payload: fileRows.slice(50, 101) });
  await replaced;
  assert.equal(f.state.activity.schedules?.filePage?.number, 1);
});
await test('visible second-page mutation rechecks only its bounded page before submission', async () => {
  const f = fixture();
  f.reply((ask) =>
    ask.type === 'schedule.pause'
      ? { code: 'NO_CONTENT', payload: null }
      : listing(ask),
  );
  await f.client.refresh();
  await f.client.page('schedules', 'next');
  const shown = f.state.activity.schedules!.schedules[0]!;
  const start = f.calls.length;
  await f.client.change('schedule', shown.name, scheduleIdentity(shown), true);
  assert.deepEqual(f.calls[start], {
    type: 'schedule.list',
    payload: { limit: 51, after: 'schedule-049' },
  });
  assert.equal(f.calls[start + 1]?.type, 'schedule.pause');
  assert.equal(
    f.calls.filter((row) => row.type === 'schedule.pause').length,
    1,
  );
});
await test('displaced second-page selection fails closed without a mutation', async () => {
  const f = fixture();
  f.reply(listing);
  await f.client.refresh();
  await f.client.page('schedules', 'next');
  const shown = f.state.activity.schedules!.schedules[0]!;
  f.reply(() => ({ code: 'OK', payload: [] }));
  await assert.rejects(
    f.client.change('schedule', shown.name, scheduleIdentity(shown), true),
    /changed elsewhere/,
  );
  assert.equal(
    f.calls.some((row) => row.type === 'schedule.pause'),
    false,
  );
});

for (const section of ['schedules', 'files'] as const) {
  const key = section === 'schedules' ? 'name' : 'internalName';
  const type = section === 'schedules' ? 'schedule.list' : 'schedule.files';
  const example = section === 'schedules' ? scheduleRows[50]! : fileRows[50]!;
  const after = section === 'schedules' ? 'schedule-049' : 'internal-049';
  for (const [label, rows] of [
    [
      'oversized',
      Array.from({ length: 52 }, (_, index) => ({
        ...example,
        [key]: `oversized-${index}`,
      })),
    ],
    ['duplicate', [example, example]],
    ['inclusive cursor', [{ ...example, [key]: after }]],
    ['blank key', [{ ...example, [key]: '   ' }]],
    ['long key', [{ ...example, [key]: 'x'.repeat(513) }]],
  ] as const) {
    await test(`${section} rejects ${label} pages without losing rows or advancing the cursor`, async () => {
      const f = fixture();
      f.reply(listing);
      await f.client.refresh();
      const value = f.state.activity.schedules!;
      const shown = section === 'schedules' ? value.schedules : value.files;
      f.reply((ask) =>
        ask.type === type ? { code: 'OK', payload: rows } : listing(ask),
      );
      await f.client.page(section, 'next');
      assert.equal(
        section === 'schedules' ? value.schedules : value.files,
        shown,
      );
      const page =
        section === 'schedules' ? value.schedulePage : value.filePage;
      assert.equal(page?.number, 1);
      assert.equal(page?.more, true);
      assert.match(page?.error ?? '', /page|incomplete|Unreadable/);
      f.reply(listing);
      await f.client.page(section, 'next');
      assert.equal(cursor(f.calls.at(-1)?.payload), after);
      assert.equal(
        (section === 'schedules' ? value.schedulePage : value.filePage)?.number,
        2,
      );
    });
  }
}
await test('invalid file refresh retains that collection while valid schedules reset to page one', async () => {
  const f = fixture();
  f.reply(listing);
  await f.client.refresh();
  await f.client.page('schedules', 'next');
  const value = f.state.activity.schedules!,
    shownSchedules = value.schedules,
    shownFiles = value.files;
  f.reply((ask) =>
    ask.type === 'schedule.files'
      ? { code: 'OK', payload: [file, file] }
      : listing(ask),
  );
  await f.client.refresh();
  assert.notEqual(value.schedules, shownSchedules);
  assert.equal(value.files, shownFiles);
  assert.equal(value.schedulePage?.number, 1);
  assert.match(value.filePage?.error ?? '', /page|incomplete|Unreadable/);
});
