import { checkedSender } from './fixture.test-support.ts';
import { outcomeFixtures, fixtures, replyOf } from './replies.test-support.ts';
import { field } from './json.test-support.ts';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { OperatorClient } from './operator.ts';
import { demoState } from './demo.ts';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type { Request } from 'plowshare-client-ts/operations/direct';
const administrative = outcomeFixtures(
  await readFile(
    new URL(
      '../../test-support/contracts/ws-administrative-fixtures.json',
      import.meta.url,
    ),
    'utf8',
  ),
);
const conversations = outcomeFixtures(
  await readFile(
    new URL(
      '../../test-support/contracts/ws-conversation-fixtures.json',
      import.meta.url,
    ),
    'utf8',
  ),
);
const inspection = outcomeFixtures(
  await readFile(
    new URL(
      '../../test-support/contracts/ws-inspection-fixtures.json',
      import.meta.url,
    ),
    'utf8',
  ),
);
const retrieval = fixtures(
  await readFile(
    new URL(
      '../../test-support/contracts/ws-retrieval-fixtures.json',
      import.meta.url,
    ),
    'utf8',
  ),
);
function fixture() {
  const state = demoState();
  state.mode = 'live';
  state.connected = true;
  state.handle = 'person';
  state.projects = [{ name: 'p' }];
  const calls: Request[] = [];
  let change: ((ask: Request) => Outcome | Promise<Outcome>) | undefined;
  const send = async (ask: Request): Promise<Outcome> => {
    calls.push(ask);
    return change
      ? change(ask)
      : structuredClone(
          administrative[ask.type] ??
            conversations[ask.type] ??
            inspection[ask.type] ?? {
              code: 'OK',
              payload: replyOf(retrieval, ask.type),
            },
        );
  };
  const client = new OperatorClient(
    () => state,
    checkedSender(send),
    () => {},
    checkedSender(send),
    async () => ({
      file: '/root/.plowshare/environment.yml',
      hash: 'original',
      source: 'local:\n  mode: ask\n',
    }),
    async () => {},
  );
  return {
    state,
    calls,
    client,
    change(fn: typeof change) {
      change = fn;
    },
  };
}
await test('memory creation records account provenance, exact body and server judgement after explicit review', async () => {
  const f = fixture();
  await f.client.prepare('memory-write', 'p');
  f.client.preview(f.state.operator!.identity, {
    summary: 'Build',
    scope: 'Project builds',
    body: 'Use the reviewed build command.',
  });
  assert.equal(
    f.calls.some((row) => row.type === 'memory.write'),
    false,
  );
  await f.client.apply(f.state.operator!.preview!.identity);
  const asked = f.calls.find((row) => row.type === 'memory.write')!;
  assert.deepEqual(asked.payload, {
    project: 'p',
    proposal: {
      summary: 'Build',
      scope: 'Project builds',
      body: 'Use the reviewed build command.',
      formedBy: 'person',
      formedWhere: 'p',
    },
  });
  assert.equal(f.state.operator!.notice, 'Change confirmed.');
  await assert.rejects(f.client.apply('obsolete'), /Review/);
});
await test('a changed command approval is withheld before the mutation; arbitrary ids cannot be reviewed', async () => {
  const f = fixture();
  await f.client.prepare('approval-grant');
  assert.throws(
    () =>
      f.client.preview(f.state.operator!.identity, {
        id: 'foreign',
        decision: 'once',
      }),
    /current listing/,
  );
  f.client.preview(f.state.operator!.identity, {
    id: 'a',
    decision: 'project',
    prefix: '["git","status"]',
  });
  const identity = f.state.operator!.preview!.identity;
  f.change(() => ({ code: 'OK', payload: { approvals: [] } }));
  await assert.rejects(f.client.apply(identity), /changed/);
  assert.equal(
    f.calls.some((row) => row.type === 'approval.answer'),
    false,
  );
});
await test('an unknown mutation is surfaced and neither refresh nor a repeated click replays it', async () => {
  const f = fixture();
  await f.client.prepare('memory-digest', 'p');
  f.client.preview(f.state.operator!.identity, {});
  const identity = f.state.operator!.preview!.identity;
  f.change((ask) => {
    if (ask.type === 'memory.digest') throw new Error('Lost after accepting');
    return { code: 'OK', payload: [] };
  });
  await assert.rejects(f.client.apply(identity), /may have reached/);
  await assert.rejects(f.client.apply(identity), /Review/);
  await f.client.prepare('memory-digest', 'p');
  assert.equal(f.calls.filter((row) => row.type === 'memory.digest').length, 1);
});
await test('control replacement and disconnect invalidate reviewed changes', async () => {
  const f = fixture();
  await f.client.prepare('memory-digest');
  f.client.preview(f.state.operator!.identity, {});
  const previous = f.state.operator!.preview!.identity;
  f.client.reset();
  await assert.rejects(f.client.apply(previous), /Review/);
  f.state.connected = false;
  await assert.rejects(f.client.prepare('memory-digest'), /Connect/);
});
await test('caps review uses the shared environment grammar and retains command policy', async () => {
  const f = fixture();
  await f.client.prepare('caps', 'p');
  f.client.preview(f.state.operator!.identity, { key: 'budget', value: 50 });
  assert.match(f.state.operator!.preview!.summary, /mode: ask/);
  assert.match(f.state.operator!.preview!.summary, /budget: 50/);
  assert.throws(
    () =>
      f.client.preview(f.state.operator!.identity, {
        key: 'failed-checks',
        value: 101,
      }),
    /from 1 to 100/,
  );
});

await test('Board top-up reviews a higher total, never an additive grant or a closed child topic', async () => {
  const f = fixture();
  await f.client.prepare('board-topup');
  assert.throws(
    () =>
      f.client.preview(f.state.operator!.identity, {
        id: 'root',
        maxModelCalls: 20,
      }),
    /above the current/,
  );
  f.client.preview(f.state.operator!.identity, {
    id: 'root',
    maxModelCalls: 140,
  });
  assert.deepEqual(f.state.operator!.preview!.payload, {
    topic: 'root',
    maxModelCalls: 140,
  });
  assert.match(f.state.operator!.preview!.summary, /new total/);
});
await test('a saved cap file remains confirmed when the server reload reply is lost', async () => {
  const f = fixture();
  await f.client.prepare('caps', 'p');
  f.client.preview(f.state.operator!.identity, { key: 'budget', value: 50 });
  let reads = 0;
  f.change((ask) => {
    if (ask.type === 'orchestration.caps' && ++reads > 1)
      throw new Error('Reload reply lost');
    return administrative[ask.type] ?? { code: 'NOT_FOUND' };
  });
  await assert.rejects(
    f.client.apply(f.state.operator!.preview!.identity),
    /saved locally.*unconfirmed/,
  );
  assert.match(f.state.operator!.notice!, /saved locally/);
});
await test('message defaults and stops use the selected project instance and retain opening idempotency', async () => {
  const f = fixture();
  const instance = {
    id: 'ins_one',
    project: 'p',
    agent: 'reviewer',
    conversation: 'cnv_one',
    lifetime: 'persistent',
    defaultInstance: false,
    active: true,
    archived: false,
    state: 'idle',
    pending: 0,
    job: null,
    createdAt: '2026-10-03T00:00:00Z',
  };
  f.change((ask) =>
    ask.type === 'message.instances'
      ? {
          code: 'OK',
          payload: { instances: [instance], more: false, offset: 0 },
        }
      : ask.type === 'agent.list'
        ? {
            code: 'OK',
            payload: [
              {
                name: 'reviewer',
                description: 'Reviews',
                model: 'test',
                bot: false,
                exported: true,
                delegable: true,
                tools: [],
                maxTurns: 4,
                maxModelCalls: 8,
              },
            ],
          }
        : {
            code: 'OK',
            payload: {
              ...instance,
              defaultInstance: ask.type === 'message.instance.default',
            },
          },
  );
  await f.client.prepare('message-default', 'p');
  assert.throws(
    () => f.client.preview(f.state.operator!.identity, { id: 'foreign' }),
    /current listing/,
  );
  f.client.preview(f.state.operator!.identity, { id: 'ins_one' });
  await f.client.apply(f.state.operator!.preview!.identity);
  assert.deepEqual(
    f.calls.find((ask) => ask.type === 'message.instance.default')!.payload,
    { instance: 'ins_one' },
  );
});

await test('delivery cancellation requires a listed pending message and rechecks its state before submission', async () => {
  const f = fixture();
  const instance = {
    id: 'ins_one',
    project: 'p',
    agent: 'reviewer',
    conversation: 'cnv_one',
    lifetime: 'persistent',
    defaultInstance: true,
    active: true,
    archived: false,
    state: 'awaiting',
    pending: 1,
    job: null,
    createdAt: '2026-10-03T00:00:00Z',
  };
  const delivery = {
    message: 'bdm_one',
    sender: 'ins_sender',
    recipient: 'ins_one',
    replyTo: null,
    replyExpected: true,
    finalReply: false,
    generated: false,
    state: 'awaiting',
    ending: null,
    reply: null,
    job: null,
    deadlineAt: null,
    postedAt: '2026-10-03T00:00:00Z',
    body: 'Review this.',
  };
  let finished = false;
  f.change((ask) => ({
    code: 'OK',
    payload:
      ask.type === 'message.instances'
        ? { instances: [instance], offset: 0, more: false }
        : ask.type === 'message.deliveries'
          ? { deliveries: [delivery], offset: ask.payload.offset, more: false }
          : {
              ...delivery,
              ...(finished ? { state: 'handled', ending: 'ANSWERED' } : {}),
            },
  }));
  await f.client.prepare('message-deliveries', 'p');
  await assert.rejects(
    f.client.messages(f.state.operator!.identity, 'foreign'),
    /current listing/,
  );
  await f.client.messages(f.state.operator!.identity, 'ins_one', 200);
  assert.equal(f.state.operator!.data.messageOffset, 200);
  assert.throws(
    () => f.client.preview(f.state.operator!.identity, { id: 'foreign' }),
    /current listing/,
  );
  f.client.preview(f.state.operator!.identity, { id: 'bdm_one' });
  finished = true;
  await assert.rejects(
    f.client.apply(f.state.operator!.preview!.identity),
    /changed/,
  );
  assert.equal(
    f.calls.some((ask) => ask.type === 'message.cancel'),
    false,
  );
  finished = false;
  await f.client.messages(f.state.operator!.identity, 'ins_one');
  f.client.preview(f.state.operator!.identity, { id: 'bdm_one' });
  await f.client.apply(f.state.operator!.preview!.identity);
  assert.deepEqual(
    f.calls.find((ask) => ask.type === 'message.cancel')!.payload,
    { message: 'bdm_one' },
  );
});
await test('a late message page cannot replace a reviewed cancellation', async () => {
  const f = fixture();
  const wire = outcomeFixtures(
    await readFile(
      new URL(
        '../../test-support/contracts/ws-messaging-fixtures.json',
        import.meta.url,
      ),
      'utf8',
    ),
  );
  let delayed: ((outcome: Outcome) => void) | undefined;
  f.change((ask) =>
    ask.type === 'message.deliveries' && ask.payload.offset === 200
      ? new Promise<Outcome>((resolve) => {
          delayed = resolve;
        })
      : (wire[ask.type] ?? { code: 'NOT_FOUND' }),
  );
  await f.client.prepare('message-deliveries', 'p');
  await f.client.messages(f.state.operator!.identity, 'ins_reviewer');
  const page = f.client.messages(
    f.state.operator!.identity,
    'ins_reviewer',
    200,
  );
  f.client.preview(f.state.operator!.identity, { id: 'bdm_request' });
  delayed!({
    code: 'OK',
    payload: { deliveries: [], offset: 200, more: false },
  });
  await page;
  assert.equal(
    field(f.state.operator!.preview!.payload, ['message']),
    'bdm_request',
  );
  assert.equal(f.state.operator!.data.messageOffset, 0);
});
