import { checkedSender } from './fixture.test-support.ts';
import { present } from './fixture.test-support.ts';
import { runWire as run, statusWire } from './run-fixtures.ts';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ActivityClient } from './activity.ts';
import { demoState } from './demo.ts';
import { emptyActivity } from './shared.ts';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type { Ask } from 'plowshare-client-ts/operations/client-views';

const item = (id: string) => ({
  id,
  handle: 'fixture',
  firing: null,
  conversation: null,
  ending: null,
  readAt: null,
  arrivedAt: '2026-10-01T00:00:00Z',
  kind: 'notice',
  answer: `Notice ${id}`,
});
const tick = () => new Promise((resolve) => setTimeout(resolve, 0));
function fixture() {
  let state = {
    ...demoState(),
    mode: 'live' as const,
    connected: true,
    activity: emptyActivity(),
  };
  const calls: Ask[] = [];
  let respond: (ask: Ask) => Outcome | Promise<Outcome> = (ask) =>
    ask.type === 'inbox.list'
      ? { code: 'OK', payload: { items: [item('one')], unread: 1 } }
      : { code: 'OK', payload: { orchestrations: [] } };
  const client = new ActivityClient(
    () => state,
    checkedSender(async (ask) => {
      calls.push(ask);
      return respond(ask);
    }),
    () => {},
  );
  return {
    client,
    calls,
    get state() {
      return state;
    },
    respond: (value: typeof respond) => {
      respond = value;
    },
    replace: () => {
      client.reset(true);
      state = { ...state, activity: emptyActivity() };
    },
  };
}
await test('startup and account counts never receipt unseen inbox items', async (t) => {
  const f = fixture();
  t.after(() => f.client.reset());
  await f.client.start();
  assert.equal(f.state.activity.inbox.unread, 1);
  assert.deepEqual(f.state.activity.inbox.items, []);
  assert.deepEqual(f.calls.find((ask) => ask.type === 'inbox.list')?.payload, {
    unread: true,
    limit: 1,
  });
  f.client.push({ kind: 'inbox.changed', unread: 7 });
  assert.equal(f.state.activity.inbox.unread, 7);
  f.client.push({ kind: 'inbox.changed', unread: -1 });
  assert.equal(f.state.activity.inbox.unread, 7);
  assert.equal(
    f.calls.some((ask) => ask.type === 'inbox.read'),
    false,
  );
  await assert.rejects(f.client.mark('one'), /displayed/);
});
await test('only a validated displayed item receives an explicit receipt; failure preserves content', async (t) => {
  const f = fixture();
  t.after(() => f.client.reset());
  await f.client.open('inbox');
  await assert.rejects(f.client.mark('unseen'), /displayed/);
  f.respond(() => ({ code: 'BAD_REQUEST', said: 'Receipt refused' }));
  await assert.rejects(f.client.mark('one'), /Receipt refused/);
  assert.equal(present(f.state.activity.inbox.items[0]).id, 'one');
  assert.deepEqual(f.state.activity.inbox.read, []);
  f.respond((ask) => {
    if (ask.type === 'inbox.list')
      return { code: 'OK', payload: { items: [], unread: 0 } };
    assert.equal(ask.type, 'inbox.read');
    assert.deepEqual(ask.payload, { items: ['one'] });
    // Real server pushes precede the acknowledgement; keep the displayed item intact.
    f.client.push({ kind: 'inbox.changed', unread: 0 });
    return { code: 'OK', payload: { marked: 1, unread: 0 } };
  });
  await f.client.mark('one');
  await tick();
  assert.equal(present(f.state.activity.inbox.items[0]).id, 'one');
  assert.equal(f.state.activity.inbox.unread, 0);
  assert.deepEqual(f.state.activity.inbox.read, ['one']);
});
await test('malformed inbox pages preserve previous items and send no receipts', async (t) => {
  const f = fixture();
  t.after(() => f.client.reset());
  await f.client.open('inbox');
  f.respond((ask) =>
    ask.type === 'inbox.list'
      ? {
          code: 'OK',
          payload: { items: [item('two'), { id: 'broken' }], unread: 2 },
        }
      : { code: 'OK', payload: { orchestrations: [] } },
  );
  await f.client.refresh();
  assert.deepEqual(
    f.state.activity.inbox.items.map((item) => item.id),
    ['one'],
  );
  assert.match(f.state.activity.inbox.error!, /Unreadable/);
  assert.equal(
    f.calls.some((ask) => ask.type === 'inbox.read'),
    false,
  );
});
await test('the mailbox includes persisted read items even with a zero unread counter', async (t) => {
  const f = fixture();
  t.after(() => f.client.reset());
  const readItem = { ...item('old'), readAt: '2026-10-01T01:00:00Z' };
  f.respond((ask) => {
    assert.equal(ask.type, 'inbox.list');
    assert.deepEqual(ask.payload, { unread: false, offset: 0, limit: 21 });
    return { code: 'OK', payload: { items: [readItem], unread: 0 } };
  });
  await f.client.open('inbox');
  await f.client.open();
  await f.client.open('inbox');
  assert.deepEqual(
    f.state.activity.inbox.items.map((row) => row.id),
    [readItem.id],
  );
  assert.equal(
    present(f.state.activity.inbox.items[0]).readAt,
    readItem.readAt,
  );
  assert.deepEqual(f.state.activity.inbox.read, ['old']);
  assert.equal(f.state.activity.inbox.unread, 0);
  assert.equal(f.state.activity.inbox.more, false);
  await f.client.mark('old');
  assert.equal(
    f.calls.some((ask) => ask.type === 'inbox.read'),
    false,
  );
});
await test('older mailbox pages survive refresh and read receipts without shifting unread offsets', async (t) => {
  const f = fixture();
  t.after(() => f.client.reset());
  const rows = Array.from({ length: 45 }, (_, i) => ({
    ...item(`item-${i}`),
    ...(i === 0 ? {} : { readAt: '2026-10-01T01:00:00Z' }),
  }));
  let unread = 1;
  f.respond((ask) => {
    if (ask.type === 'inbox.read') {
      present(rows[0]).readAt = '2026-10-02T00:00:00Z';
      unread = 0;
      return { code: 'OK', payload: { marked: 1, unread } };
    }
    if (ask.type === 'orchestration.list')
      return { code: 'OK', payload: { orchestrations: [] } };
    const {
      offset = 0,
      limit,
      unread: onlyUnread,
    } = ask.payload as { offset?: number; limit: number; unread: boolean };
    const page = onlyUnread ? rows.filter((row) => !row.readAt) : rows;
    return {
      code: 'OK',
      payload: { items: page.slice(offset, offset + limit), unread },
    };
  });
  await f.client.open('inbox');
  assert.equal(f.state.activity.inbox.items.length, 20);
  assert.equal(f.state.activity.inbox.more, true);
  await f.client.mark('item-0');
  await f.client.refresh();
  assert.equal(present(f.state.activity.inbox.items[0]).id, 'item-0');
  assert.equal(f.state.activity.inbox.unread, 0);
  await f.client.older();
  assert.equal(f.state.activity.inbox.items.length, 40);
  await f.client.older();
  assert.equal(f.state.activity.inbox.items.length, 45);
  assert.equal(f.state.activity.inbox.more, false);
  await f.client.refresh();
  assert.equal(f.state.activity.inbox.items.length, 45);
  assert.equal(f.state.activity.inbox.read.length, 45);
  await assert.rejects(f.client.older(), /No older/);
});
await test('paging beyond the server cap rejects a broken later page without losing loaded read history', async (t) => {
  const f = fixture();
  t.after(() => f.client.reset());
  const rows = Array.from({ length: 205 }, (_, i) => ({
    ...item(`read-${i}`),
    readAt: '2026-10-01T01:00:00Z',
  }));
  let broken = false;
  f.respond((ask) => {
    const { offset = 0, limit } = ask.payload as {
      offset?: number;
      limit: number;
    };
    assert.ok(limit <= 200);
    return {
      code: 'OK',
      payload: {
        items:
          broken && offset === 200
            ? [{ id: 'bad' }]
            : rows.slice(offset, offset + limit),
        unread: 0,
      },
    };
  });
  await f.client.open('inbox');
  for (let i = 0; i < 8; i++) await f.client.older();
  assert.equal(f.state.activity.inbox.items.length, 180);
  broken = true;
  await f.client.older();
  assert.equal(f.state.activity.inbox.items.length, 180);
  assert.equal(f.state.activity.inbox.read.length, 180);
  assert.match(f.state.activity.inbox.error!, /Unreadable/);
  broken = false;
  await f.client.older();
  assert.equal(f.state.activity.inbox.items.length, 205);
  assert.equal(f.state.activity.inbox.more, false);
  assert.equal(
    f.calls.some((ask) => ask.type === 'inbox.read'),
    false,
  );
});
await test('a count pushed during startup wins over an older response', async (t) => {
  const f = fixture();
  t.after(() => f.client.reset());
  let release: (answer: Outcome) => void = () => {};
  f.respond((ask) =>
    ask.type === 'inbox.list'
      ? new Promise((resolve) => {
          release = resolve;
        })
      : { code: 'OK', payload: { orchestrations: [] } },
  );
  const starting = f.client.start();
  f.client.push({ kind: 'inbox.changed', unread: 3 });
  release({ code: 'OK', payload: { items: [], unread: 0 } });
  await starting;
  assert.equal(f.state.activity.inbox.unread, 3);
});
await test('a push during a displayed inbox read triggers one further read, rather than overwrite the new count', async (t) => {
  const f = fixture();
  t.after(() => f.client.reset());
  let release: (answer: Outcome) => void = () => {};
  f.respond(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const opening = f.client.open('inbox');
  f.client.push({ kind: 'inbox.changed', unread: 2 });
  f.respond(() => ({
    code: 'OK',
    payload: { items: [item('two')], unread: 2 },
  }));
  release({ code: 'OK', payload: { items: [item('one')], unread: 1 } });
  await opening;
  assert.deepEqual(
    f.state.activity.inbox.items.map((item) => item.id),
    ['two'],
  );
  assert.equal(f.state.activity.inbox.unread, 2);
  assert.equal(f.calls.length, 2);
});
await test('a late quiet count cannot overwrite a newer displayed page count', async (t) => {
  const f = fixture();
  t.after(() => f.client.reset());
  let release: (answer: Outcome) => void = () => {};
  f.respond((ask) =>
    ask.type === 'inbox.list'
      ? new Promise((resolve) => {
          release = resolve;
        })
      : { code: 'OK', payload: { orchestrations: [] } },
  );
  const starting = f.client.start();
  f.respond(() => ({
    code: 'OK',
    payload: { items: [item('two')], unread: 2 },
  }));
  await f.client.open('inbox');
  release({ code: 'OK', payload: { items: [], unread: 0 } });
  await starting;
  assert.equal(f.state.activity.inbox.unread, 2);
  assert.deepEqual(
    f.state.activity.inbox.items.map((item) => item.id),
    ['two'],
  );
});
await test('old active roots are merged with recent child runs; partial read refusal retains all previous states', async (t) => {
  const f = fixture();
  t.after(() => f.client.reset());
  f.respond((ask) => {
    const payload = ask.payload as { state?: string };
    return {
      code: 'OK',
      payload: {
        orchestrations:
          payload.state === 'running'
            ? [
                run('old-root', 'running', {
                  createdAt: '2020-01-01T00:00:00Z',
                  project: 'Other',
                }),
              ]
            : payload.state
              ? []
              : [
                  run('finished-child', 'finished', {
                    parent: 'old-root',
                    depth: 1,
                  }),
                ],
      },
    };
  });
  await f.client.open('runs');
  assert.deepEqual(
    f.state.activity.runs.items.map((run) => run.id),
    ['old-root', 'finished-child'],
  );
  assert.equal(
    f.calls.filter((ask) => ask.type === 'orchestration.list').length,
    4,
  );
  f.respond((ask) =>
    (ask.payload as { state?: string }).state === 'waiting'
      ? { code: 'BAD_REQUEST', said: 'State refused' }
      : { code: 'OK', payload: { orchestrations: [] } },
  );
  await f.client.refresh();
  assert.equal(f.state.activity.runs.items.length, 2);
  assert.equal(f.state.activity.runs.error, 'State refused');
});
await test('selected run status follows its child and record pushes, without sending decisions or cancellations', async (t) => {
  const f = fixture();
  t.after(() => f.client.reset());
  let phase = 'running';
  f.respond((ask) =>
    ask.type === 'orchestration.status'
      ? {
          code: 'OK',
          payload: statusWire('root', phase, {
            todos: [
              {
                id: 'todo-1',
                parent: null,
                position: 0,
                text: 'Read sources',
                status: 'in_progress',
                summary: null,
                locked: false,
                stage: null,
                updatedAt: '2026-10-01T00:00:00Z',
              },
            ],
            children: [{ id: 'child', state: phase }],
          }),
        }
      : ask.type === 'orchestration.list'
        ? { code: 'OK', payload: { orchestrations: [run('root')] } }
        : { code: 'OK', payload: { items: [], unread: 0 } },
  );
  await f.client.open('runs');
  await f.client.select('root');
  phase = 'asking';
  f.client.push({
    kind: 'orchestration.changed',
    orchestration: 'child',
    state: 'asking',
  });
  await tick();
  assert.equal(
    present(f.state.activity.details.root).value?.run.state,
    'asking',
  );
  const statusCount = f.calls.filter(
    (ask) => ask.type === 'orchestration.status',
  ).length;
  f.client.push({ kind: 'orchestration.recorded', root: 'root', through: 5 });
  await tick();
  assert.equal(
    f.calls.filter((ask) => ask.type === 'orchestration.status').length,
    statusCount + 1,
  );
  await assert.rejects(f.client.select('another-account'), /available/);
  assert.equal(
    f.calls.some((ask) =>
      ['orchestration.answer', 'orchestration.cancel', 'agent.run'].includes(
        ask.type,
      ),
    ),
    false,
  );
});
await test('reset and account replacement reject stale reads and receipts', async (t) => {
  const f = fixture();
  t.after(() => f.client.reset());
  await f.client.open('inbox');
  let release: (answer: Outcome) => void = () => {};
  f.respond(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const marking = f.client.mark('one');
  f.replace();
  release({ code: 'OK', payload: { marked: 1, unread: 0 } });
  await marking;
  assert.equal(f.state.activity.inbox.unread, undefined);
  assert.deepEqual(f.state.activity.inbox.read, []);
  const reading = f.client.open('inbox');
  f.replace();
  release({ code: 'OK', payload: { items: [item('stale')], unread: 1 } });
  await reading;
  assert.equal(f.state.activity.inbox.loaded, false);
  assert.deepEqual(f.state.activity.inbox.items, []);
});
await test('a zero receipt never marks the displayed item read', async (t) => {
  const f = fixture();
  t.after(() => f.client.reset());
  await f.client.open('inbox');
  f.respond(() => ({ code: 'OK', payload: { marked: 0, unread: 1 } }));
  await assert.rejects(f.client.mark('one'), /not confirmed/);
  assert.deepEqual(f.state.activity.inbox.read, []);
  assert.equal(f.state.activity.inbox.unread, 1);
  assert.equal(f.calls.filter((ask) => ask.type === 'inbox.read').length, 1);
});

await test('asking runs load questions without opening Activity, with at most four status reads', async () => {
  const f = fixture();
  let active = 0,
    peak = 0;
  const asking = Array.from({ length: 7 }, (_, i) =>
    run(`question-${i}`, 'asking'),
  );
  f.respond(async (ask) => {
    if (ask.type === 'inbox.list')
      return { code: 'OK', payload: { items: [], unread: 0 } };
    if (ask.type === 'orchestration.list')
      return { code: 'OK', payload: { orchestrations: asking } };
    const id = (ask.payload as { id: string }).id;
    active++;
    peak = Math.max(peak, active);
    await tick();
    active--;
    return { code: 'OK', payload: statusWire(id) };
  });
  await f.client.refresh();
  assert.equal(f.state.activity.view, undefined);
  assert.equal(Object.keys(f.state.activity.details).length, 7);
  assert.equal(peak, 4);
  assert.equal(
    f.calls.filter((ask) => ask.type === 'orchestration.status').length,
    7,
  );
  assert.equal(
    present(present(f.state.activity.details['question-0']).wire?.messages[0])
      .text,
    'Which sources?',
  );
  assert.equal(
    f.calls.some((ask) => ask.type === 'orchestration.answer'),
    false,
  );
});
await test('question read failures retain loaded questions and account switches discard late reads', async () => {
  const f = fixture();
  let fail = false;
  f.respond((ask) =>
    ask.type === 'inbox.list'
      ? { code: 'OK', payload: { items: [], unread: 0 } }
      : ask.type === 'orchestration.list'
        ? {
            code: 'OK',
            payload: { orchestrations: [run('question', 'asking')] },
          }
        : fail
          ? { code: 'BAD_REQUEST', said: 'Status unavailable' }
          : { code: 'OK', payload: statusWire('question') },
  );
  await f.client.refresh();
  fail = true;
  await f.client.refresh();
  assert.equal(
    present(present(f.state.activity.details.question).wire?.messages[0]).text,
    'Which sources?',
  );
  assert.match(
    present(f.state.activity.details.question).error!,
    /Status unavailable/,
  );
  let release!: (reply: Outcome) => void;
  f.respond((ask) =>
    ask.type === 'inbox.list'
      ? { code: 'OK', payload: { items: [], unread: 0 } }
      : ask.type === 'orchestration.list'
        ? {
            code: 'OK',
            payload: { orchestrations: [run('question', 'asking')] },
          }
        : new Promise((resolve) => {
            release = resolve;
          }),
  );
  const refreshing = f.client.refresh();
  await tick();
  f.replace();
  release({ code: 'OK', payload: statusWire('question') });
  await refreshing;
  assert.deepEqual(f.state.activity.details, {});
});
