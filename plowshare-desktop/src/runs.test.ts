import { checkedSender } from './fixture.test-support.ts';
import { present } from './fixture.test-support.ts';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RunClient } from './runs.ts';
import { statusWire, runWire } from './run-fixtures.ts';
import { demoState } from './demo.ts';
import {
  emptyActivity,
  runQuestion,
  inspectionConversation,
} from './shared.ts';
import { runStatusOf } from 'plowshare-client-ts/operations/inspection';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type { Request as Ask } from 'plowshare-client-ts/operations/direct';
import {
  stageSpans,
  questionStage,
  stageSteps,
  delegatedConversation,
  navigableCall,
  orchestrationCallId,
} from './run-navigation.ts';
import { stagesPanel } from './renderer/run-navigation.ts';
const row = (ordinal: number, detail: string | null = null) => ({
  ordinal,
  at: '2026-10-01T00:00:00Z',
  run: 'root',
  actor: 'conductor',
  kind: 'tool_call',
  text: 'Read sources',
  detail,
});
const page = (rows = [row(100)], more = false) => ({
  root: 'root',
  rows,
  total: 100,
  limit: 100,
  through: 100,
  oldest: rows[0]?.ordinal ?? null,
  more,
});
function fixture() {
  const state = {
    ...demoState(),
    mode: 'live' as const,
    connected: true,
    activity: emptyActivity(),
  };
  const initial = statusWire();
  state.activity.runs.items = [
    runStatusOf({ code: 'OK', payload: initial })!.run,
  ];
  state.activity.details.root = {
    wire: initial,
    value: runStatusOf({ code: 'OK', payload: initial })!,
  };
  const calls: Ask[] = [];
  let wire = initial;
  let reply: (ask: Ask) => Outcome | Promise<Outcome> = (ask) => ({
    code: 'OK',
    payload:
      ask.type === 'orchestration.status'
        ? wire
        : ask.type === 'orchestration.record'
          ? page()
          : { id: 'root', state: 'running' },
  });
  const client = new RunClient(
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
    wire: (value: typeof wire) => {
      wire = value;
    },
    reply: (fn: typeof reply) => {
      reply = fn;
    },
  };
}
await test('plain answers check current identity, send once and preserve confirmed receipt', async () => {
  const f = fixture();
  await f.client.answer(
    'root',
    runQuestion(present(f.state.activity.details.root).wire!),
    'Read archives',
  );
  assert.deepEqual(
    f.calls.map((call) => call.type),
    ['orchestration.status', 'orchestration.answer', 'orchestration.status'],
  );
  assert.deepEqual(present(f.calls[1]).payload, {
    id: 'root',
    answer: 'Read archives',
  });
  assert.equal(
    present(f.state.activity.decisions!.root).notice,
    'Answer recorded.',
  );
  assert.equal(
    f.calls.some((call) => call.type === 'agent.run'),
    false,
  );
});
await test('remote settlement and changed question block stale human decisions', async () => {
  const f = fixture(),
    shown = runQuestion(present(f.state.activity.details.root).wire!);
  f.wire(statusWire('root', 'running'));
  await assert.rejects(
    f.client.answer('root', shown, 'yes'),
    /changed|elsewhere/,
  );
  assert.equal(f.calls.length, 1);
  assert.equal(
    present(f.state.activity.details.root).wire!.orchestration.state,
    'running',
  );
  const g = fixture();
  g.wire(
    statusWire('root', 'asking', {
      messages: [{ ...present(statusWire().messages[0]), id: 'new-question' }],
    }),
  );
  await assert.rejects(
    g.client.answer(
      'root',
      runQuestion(present(g.state.activity.details.root).wire!),
      'yes',
    ),
    /changed/,
  );
  assert.equal(
    g.calls.some((call) => call.type === 'orchestration.answer'),
    false,
  );
});
await test('disconnect during preflight blocks a mutation and ignores late state', async () => {
  const f = fixture();
  let release!: (answer: Outcome) => void;
  f.reply(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const work = f.client.answer(
    'root',
    runQuestion(present(f.state.activity.details.root).wire!),
    'yes',
  );
  f.client.reset();
  f.state.connected = false;
  release({ code: 'OK', payload: statusWire() });
  await assert.rejects(work, /connection changed/i);
  assert.equal(f.calls.length, 1);
  assert.equal(present(f.state.activity.decisions!.root).busy, false);
});
await test('ambiguous mutation failure is visible and never replayed; a confirmed answer retains its receipt if refresh fails', async () => {
  const f = fixture();
  f.reply((ask) => {
    if (ask.type === 'orchestration.answer') throw new Error('Socket dropped');
    return { code: 'OK', payload: statusWire() };
  });
  await assert.rejects(
    f.client.answer(
      'root',
      runQuestion(present(f.state.activity.details.root).wire!),
      'yes',
    ),
    /Socket dropped/,
  );
  assert.equal(
    f.calls.filter((call) => call.type === 'orchestration.answer').length,
    1,
  );
  assert.match(present(f.state.activity.decisions!.root).error!, /Refresh/);
  const g = fixture();
  let status = 0;
  g.reply((ask) =>
    ask.type === 'orchestration.status'
      ? ++status === 1
        ? { code: 'OK', payload: statusWire() }
        : { code: 'BAD_REQUEST', said: 'Refresh failed' }
      : { code: 'OK', payload: { id: 'root', state: 'running' } },
  );
  await assert.rejects(
    g.client.answer(
      'root',
      runQuestion(present(g.state.activity.details.root).wire!),
      'yes',
    ),
    /Refresh failed/,
  );
  assert.equal(
    present(g.state.activity.decisions!.root).notice,
    'Answer recorded.',
  );
});
await test('structured answers enforce headers, multiplicity and valid options before sending', async () => {
  const f = fixture();
  const wire = statusWire('root', 'asking', {
    messages: [
      {
        ...present(statusWire().messages[0]),
        structure: {
          lead: 'Choose sources',
          questions: [
            {
              header: 'Sources',
              question: 'Where?',
              multi: false,
              options: [
                { label: 'Archives', description: 'Original materials' },
                { label: 'Books', description: 'Summaries' },
              ],
            },
          ],
        },
      },
    ],
  });
  f.state.activity.details.root = {
    wire,
    value: runStatusOf({ code: 'OK', payload: wire })!,
  };
  f.wire(wire);
  const question = runQuestion(wire);
  await assert.rejects(f.client.answer('root', question, 'yes'), /structured/);
  await assert.rejects(
    f.client.answer('root', question, undefined, [
      { header: 'Sources', chosen: ['Unknown'] },
    ]),
    /valid options/,
  );
  await assert.rejects(
    f.client.answer('root', question, undefined, [
      { header: 'Sources', chosen: ['Archives', 'Books'] },
    ]),
    /valid options/,
  );
  assert.equal(f.calls.length, 0);
  const choices = [
    { header: 'Sources', chosen: ['Archives'], note: 'Focus on this century' },
  ];
  await f.client.answer('root', question, undefined, choices);
  assert.deepEqual(present(f.calls[1]).payload, { id: 'root', choices });
});
await test('cancellation rechecks state and never starts a turn; unknown runs cannot mutate or open trajectories', async () => {
  const f = fixture();
  await f.client.cancel('root');
  assert.equal(
    f.calls.filter((call) => call.type === 'orchestration.cancel').length,
    1,
  );
  assert.equal(f.client.conversation('root', 'conductor'), 'conductor');
  assert.equal(inspectionConversation(f.state, 'conductor'), true);
  await assert.rejects(f.client.cancel('foreign'), /available/);
  assert.throws(() => f.client.conversation('foreign', 'caller'), /available/);
  const g = fixture();
  g.wire(statusWire('root', 'finished'));
  await assert.rejects(g.client.cancel('root'), /ended/);
  assert.equal(g.calls.length, 1);
});
await test('record pages merge by ordinal; failed filter change preserves the loaded snapshot and filter', async () => {
  const f = fixture();
  f.reply(() => ({ code: 'OK', payload: page([row(90), row(100)], true) }));
  await f.client.record('root');
  f.reply(() => ({ code: 'OK', payload: page([row(80), row(90)], false) }));
  await f.client.record('root', 90);
  assert.deepEqual(
    present(f.state.activity.records!.root).rows.map((row) => row.ordinal),
    [80, 90, 100],
  );
  const previous = present(f.state.activity.records!.root).rows;
  f.reply(() => ({
    code: 'OK',
    payload: { ...page(), rows: [{ ordinal: 1 }] },
  }));
  await f.client.record('root', undefined, ['tool_call']);
  assert.equal(present(f.state.activity.records!.root).rows, previous);
  assert.equal(present(f.state.activity.records!.root).kinds, undefined);
  assert.match(
    present(f.state.activity.records!.root).error!,
    /incomplete|Unreadable/,
  );
  await assert.rejects(f.client.record('root', 50), /current record/);
});
await test('out of order record reads and reset retain the latest complete snapshot', async () => {
  const f = fixture();
  const replies: ((value: Outcome) => void)[] = [];
  f.reply(() => new Promise((resolve) => replies.push(resolve)));
  const first = f.client.record('root'),
    second = f.client.record('root', undefined, ['tool_call']);
  present(replies[1])({ code: 'OK', payload: page([row(99)]) });
  await second;
  present(replies[0])({ code: 'OK', payload: page([row(50)]) });
  await first;
  assert.deepEqual(
    present(f.state.activity.records!.root).rows.map((row) => row.ordinal),
    [99],
  );
  const third = f.client.record('root');
  f.client.reset();
  present(replies[2])({ code: 'OK', payload: page([row(100)]) });
  await third;
  assert.deepEqual(
    present(f.state.activity.records!.root).rows.map((row) => row.ordinal),
    [99],
  );
});
await test('record settlement replaces an already loaded tool outcome after tail reconciliation', async () => {
  const f = fixture();
  await f.client.record('root');
  f.reply((ask) => ({
    code: 'OK',
    payload: page([
      row(100, 'after' in (ask.payload as object) ? 'Read complete' : null),
    ]),
  }));
  f.client.push({ kind: 'orchestration.recorded', root: 'root', settled: 100 });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(
    present(present(f.state.activity.records!.root).rows[0]).detail,
    'Read complete',
  );
  f.client.pause();
  const count = f.calls.length;
  f.client.push({ kind: 'orchestration.recorded', root: 'root', settled: 100 });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(f.calls.length, count);
});
await test('definitions keep withholding reasons and stages; malformed reads retain the prior scope', async () => {
  const f = fixture(),
    definition = {
      name: 'research',
      description: 'Research',
      tier: 'global',
      stages: [{ id: 'read', doneWhen: 'Sources read', mayReturnTo: [] }],
      triggers: [],
      served: false,
      withheld: 'Missing agent',
    };
  f.reply(() => ({ code: 'OK', payload: { definitions: [definition] } }));
  await f.client.definitions();
  assert.deepEqual(f.state.activity.definitions!.items, [definition]);
  f.reply(() => ({
    code: 'OK',
    payload: { definitions: [{ name: 'broken' }] },
  }));
  await f.client.definitions();
  assert.deepEqual(f.state.activity.definitions!.items, [definition]);
  await assert.rejects(f.client.definitions('foreign'), /project/);
});
await test('a second click cannot send a duplicate answer while the first preflight is pending', async () => {
  const f = fixture();
  let release!: (value: Outcome) => void;
  let reads = 0;
  f.reply((ask) =>
    ask.type === 'orchestration.status' && ++reads === 1
      ? new Promise((resolve) => {
          release = resolve;
        })
      : {
          code: 'OK',
          payload:
            ask.type === 'orchestration.status'
              ? statusWire()
              : { id: 'root', state: 'running' },
        },
  );
  const question = runQuestion(present(f.state.activity.details.root).wire!),
    first = f.client.answer('root', question, 'yes');
  await assert.rejects(
    f.client.answer('root', question, 'yes'),
    /already being sent/,
  );
  assert.equal(f.calls.length, 1);
  release({ code: 'OK', payload: statusWire() });
  await first;
  assert.equal(
    f.calls.filter((call) => call.type === 'orchestration.answer').length,
    1,
  );
});
await test('stage visits scope questions and steps by run, retain returns and refuse ambiguous stage attribution', () => {
  const f = fixture(),
    at = (n: number) =>
      new Date(Date.parse('2026-10-01T00:00:00Z') + n * 1000).toISOString();
  const wire = statusWire('root', 'asking', {
    todos: ['reading', 'review'].map((stage, i) => ({
      id: stage,
      parent: null,
      position: i,
      locked: false,
      text: stage,
      status: 'in_progress',
      stage,
      summary: null,
      updatedAt: at(0),
    })),
  });
  f.state.activity.details.root = {
    wire,
    value: runStatusOf({ code: 'OK', payload: wire })!,
  };
  const move = (
    ordinal: number,
    stage: string,
    status: string,
    run = 'root',
  ) => ({
    ...row(ordinal),
    at: at(ordinal),
    run,
    kind: 'stage_moved',
    text: `${stage}: ${status}`,
  });
  f.state.activity.navigation = {
    root: {
      rows: [
        move(1, 'reading', 'pending → in_progress'),
        move(2, 'review', 'pending → in_progress', 'child'),
        move(5, 'reading', 'in_progress → done'),
        move(6, 'review', 'pending → in_progress'),
        move(9, 'review', 'in_progress → done'),
        move(10, 'reading', 'done → in_progress'),
      ],
    },
  };
  assert.equal(stageSpans(f.state, 'root').get('reading')!.length, 2);
  assert.equal(questionStage(f.state, 'root', at(3)), 'reading');
  assert.equal(questionStage(f.state, 'root', at(7)), 'review');
  assert.equal(questionStage(f.state, 'root', at(5)), undefined);
  assert.equal(questionStage(f.state, 'root', at(11)), 'reading');
  f.state.history.conductor = {
    more: false,
    entries: [3, 7, 11].map((ordinal) => ({
      ordinal,
      turnOrdinal: 1,
      kind: 'answer',
      state: 'stands',
      recordedAt: at(ordinal),
      text: 'Complete text',
    })),
  };
  assert.deepEqual(
    stageSteps(f.state, 'conductor', { run: 'root', stage: 'reading' }).map(
      (step) => step.ordinal,
    ),
    [3, 11],
  );
  present(f.state.activity.navigation.root).rows = [
    ...present(f.state.activity.navigation.root).rows,
    move(12, 'review', 'done → in_progress'),
  ];
  assert.equal(questionStage(f.state, 'root', at(13)), undefined);
});
await test('structured questions render once under their stage, with one current answer form', () => {
  const f = fixture(),
    wire = statusWire('root', 'asking', {
      todos: [
        {
          id: 'review',
          parent: null,
          position: 0,
          locked: false,
          text: 'Review objectives',
          status: 'in_progress',
          stage: 'review',
          summary: null,
          updatedAt: '2026-09-30T00:00:00Z',
        },
      ],
      messages: [
        {
          ...present(statusWire().messages[0]),
          text: 'Unique lead. Which sources?',
          structure: {
            lead: 'Unique lead.',
            questions: [
              {
                header: 'Sources',
                question: 'Which sources?',
                multi: false,
                options: [
                  { label: 'Archives', description: 'Original materials' },
                ],
              },
            ],
          },
        },
      ],
    });
  f.state.activity.details.root = {
    wire,
    value: runStatusOf({ code: 'OK', payload: wire })!,
  };
  f.state.activity.navigation = {
    root: {
      rows: [
        {
          ...row(1),
          at: '2026-09-30T00:00:00Z',
          kind: 'stage_moved',
          text: 'review: pending → in_progress',
        },
      ],
    },
  };
  const html = stagesPanel(f.state, 'root');
  const visible = html.replace(/<[^>]*>/g, '');
  assert.equal(visible.split('Unique lead.').length - 1, 1);
  assert.equal(visible.split('Which sources?').length - 1, 1);
  assert.equal(html.split('id="run-answer-send"').length - 1, 1);
  assert.match(html, /data-run-stage="review"/);
});
await test('delegation navigation uses distinct durable child edges, rejects invented ids and disconnected history graphs', () => {
  const f = fixture(),
    entry = (ordinal: number, child: string) => ({
      ordinal,
      turnOrdinal: 1,
      kind: 'answer',
      state: 'stands',
      calls: [
        {
          id: `call-${ordinal}`,
          name: 'agent_run',
          arguments: '{"conversation":"invented"}',
          length: 27,
          cut: false,
          opened: { agent: 'analyst', conversation: child },
        },
      ],
    });
  f.state.history.conductor = {
    more: false,
    entries: [entry(1, 'one'), entry(2, 'two')],
  };
  f.state.history.one = { more: false, entries: [entry(1, 'nested')] };
  f.state.history.orphan = { more: false, entries: [entry(1, 'foreign')] };
  assert.equal(delegatedConversation(f.state, 'conductor', '1:call-1'), 'one');
  assert.equal(delegatedConversation(f.state, 'conductor', '2:call-2'), 'two');
  assert.equal(inspectionConversation(f.state, 'nested'), true);
  assert.equal(inspectionConversation(f.state, 'foreign'), false);
  assert.equal(inspectionConversation(f.state, 'invented'), false);
  assert.throws(
    () => delegatedConversation(f.state, 'conductor', 'missing'),
    /recorded delegation/,
  );
});
await test('stage navigation reads all transition pages independently of the activity filter and preserves data on refusal', async () => {
  const f = fixture();
  f.reply((ask) => ({
    code: 'OK',
    payload: page(
      [row('before' in (ask.payload as object) ? 1 : 100)],
      !('before' in (ask.payload as object)),
    ),
  }));
  await f.client.navigation('root');
  assert.deepEqual(
    present(f.state.activity.navigation!.root).rows.map((row) => row.ordinal),
    [1, 100],
  );
  assert.ok(
    f.calls.every((call) =>
      JSON.stringify(call.payload).includes('stage_moved'),
    ),
  );
  f.reply(() => ({ code: 'BAD_REQUEST', said: 'Unavailable' }));
  await f.client.navigation('root');
  assert.deepEqual(
    present(f.state.activity.navigation!.root).rows.map((row) => row.ordinal),
    [1, 100],
  );
  assert.match(
    present(f.state.activity.navigation!.root).error!,
    /Unavailable/,
  );
});
await test('stage-view navigation catches a push during a read and stops reading after the last view closes', async () => {
  const f = fixture();
  f.state.activity.records = {
    root: { root: 'root', rows: [], through: 0, oldest: null, more: false },
  };
  f.client.followNavigation('root', true);
  let release!: (answer: Outcome) => void,
    reads = 0;
  f.reply(() =>
    ++reads === 1
      ? new Promise((resolve) => {
          release = resolve;
        })
      : { code: 'OK', payload: page([row(100)]) },
  );
  const reading = f.client.navigation('root');
  f.client.push({ kind: 'orchestration.recorded', root: 'root', through: 100 });
  release({ code: 'OK', payload: page([row(99)]) });
  await reading;
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(reads, 2);
  assert.equal(
    present(present(f.state.activity.navigation!.root).rows[0]).ordinal,
    100,
  );
  f.client.followNavigation('root', false);
  f.client.pause();
  f.client.push({ kind: 'orchestration.recorded', root: 'root', through: 101 });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(reads, 2);
});

await test('spawn navigation resolves orchestration receipts through server status and refuses unrelated or partial results', async () => {
  const f = fixture();
  const entries = [
    {
      ordinal: 1,
      turnOrdinal: 1,
      kind: 'answer',
      state: 'stands',
      calls: [
        {
          id: 'start',
          name: 'orchestrate_deep_research',
          arguments: '{}',
          length: 2,
          cut: false,
        },
      ],
    },
    {
      ordinal: 2,
      turnOrdinal: 1,
      kind: 'tool_result',
      state: 'stands',
      toolCallId: 'start',
      outcome: 'ok',
      text: JSON.stringify({ id: 'root', stages: ['reading'] }),
    },
  ];
  f.state.history.caller = { more: false, entries };
  const { stepsOf } = await import('plowshare-client-ts/operations/trajectory');
  const call = stepsOf(entries).find((step) => step.kind === 'call')!;
  assert.equal(orchestrationCallId(call), 'root');
  assert.equal(navigableCall(call), true);
  assert.equal(
    await f.client.spawnedConversation('caller', '1:start'),
    'conductor',
  );
  assert.deepEqual(
    f.calls.map((call) => call.type),
    ['orchestration.status'],
  );
  for (const changed of [
    { ...call, tool: 'search' },
    { ...call, result: { ...call.result!, cut: true } },
    { ...call, result: { ...call.result!, outcome: 'failed' } },
    { ...call, result: { ...call.result!, text: 'The run is root' } },
    { ...call, result: { ...call.result!, text: '{"id":"root"}' } },
  ])
    assert.equal(orchestrationCallId(changed), undefined);
  f.wire(
    statusWire('root', 'running', {
      orchestration: {
        ...statusWire().orchestration,
        callerConversation: 'foreign',
      },
    }),
  );
  await assert.rejects(
    f.client.spawnedConversation('caller', '1:start'),
    /does not belong/,
  );
  await assert.rejects(
    f.client.spawnedConversation('caller', 'invented'),
    /recorded delegation/,
  );
});

await test('resume rechecks failure, sends one keyed WS operation and exposes the GUI action', async () => {
  const f = fixture();
  f.wire(statusWire('root', 'failed'));
  await f.client.resume('root');
  assert.deepEqual(
    f.calls.map((call) => call.type),
    ['orchestration.status', 'orchestration.resume', 'orchestration.status'],
  );
  const resume = present(f.calls[1]);
  assert.equal(resume.type, 'orchestration.resume');
  if (resume.type === 'orchestration.resume')
    assert.match(resume.payload.requestId, /^[0-9a-f-]{36}$/);
  assert.equal(
    f.calls.some((call) => call.type === 'agent.run'),
    false,
  );
  const { cancelControl } = await import('./renderer/run-controls.ts');
  assert.match(cancelControl(f.state, 'root'), /id="run-resume"/);
  f.state.connected = false;
  assert.match(cancelControl(f.state, 'root'), /disabled/);
  const g = fixture();
  g.wire(statusWire('root', 'finished'));
  await assert.rejects(g.client.resume('root'), /failed root/);
  assert.equal(
    g.calls.some((call) => call.type === 'orchestration.resume'),
    false,
  );
});
await test('explicit retry after uncertain resume retains its key; a later failure gets a new key', async () => {
  const f = fixture();
  let failure = '2026-10-05T00:00:00Z';
  f.reply((ask) => {
    if (ask.type === 'orchestration.resume') throw new Error('Socket dropped');
    return {
      code: 'OK',
      payload: statusWire('root', 'failed', {
        orchestration: runWire('root', 'failed', { endedAt: failure }),
      }),
    };
  });
  await assert.rejects(f.client.resume('root'), /Socket dropped/);
  assert.equal(
    f.calls.filter((call) => call.type === 'orchestration.resume').length,
    1,
  );
  await assert.rejects(f.client.resume('root'), /Socket dropped/);
  const requests = f.calls.filter(
    (call) => call.type === 'orchestration.resume',
  );
  assert.deepEqual(present(requests[0]).payload, present(requests[1]).payload);
  failure = '2026-10-05T00:01:00Z';
  await assert.rejects(f.client.resume('root'), /Socket dropped/);
  const newer = f.calls
    .filter((call) => call.type === 'orchestration.resume')
    .at(-1)!;
  assert.notDeepEqual(newer.payload, present(requests[0]).payload);
});
