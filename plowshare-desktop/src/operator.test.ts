import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { OperatorClient } from './operator.ts';
import { demoState } from './demo.ts';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type { Request } from 'plowshare-client-ts/operations/direct';
const administrative = JSON.parse(await readFile(new URL('../../test-support/contracts/ws-administrative-fixtures.json', import.meta.url), 'utf8'));
const conversations = JSON.parse(await readFile(new URL('../../test-support/contracts/ws-conversation-fixtures.json', import.meta.url), 'utf8'));
const inspection = JSON.parse(await readFile(new URL('../../test-support/contracts/ws-inspection-fixtures.json', import.meta.url), 'utf8'));
const retrieval = JSON.parse(await readFile(new URL('../../test-support/contracts/ws-retrieval-fixtures.json', import.meta.url), 'utf8')).replies;
function fixture() {
  const state = demoState(); state.mode = 'live'; state.connected = true; state.handle = 'person'; state.projects = [{ name: 'p' }];
  const calls: Request[] = []; let change: ((ask: Request) => Outcome | Promise<Outcome>) | undefined;
  const send = async (ask: Request): Promise<Outcome> => { calls.push(ask); return change ? change(ask) : structuredClone(administrative[ask.type] ?? conversations[ask.type] ?? inspection[ask.type] ?? { code: 'OK', payload: retrieval[ask.type] }); };
  const client = new OperatorClient(() => state, send, () => {}, send, async () => ({ file: '/root/.plowshare/environment.yml', hash: 'original', source: 'local:\n  mode: ask\n' }), async () => {});
  return { state, calls, client, change(fn: typeof change) { change = fn; } };
}
test('memory creation records account provenance, exact body and server judgement after explicit review', async () => {
  const f = fixture(); await f.client.prepare('memory-write', 'p');
  f.client.preview(f.state.operator!.identity, { summary: 'Build', scope: 'Project builds', body: 'Use the reviewed build command.' });
  assert.equal(f.calls.some(row => row.type === 'memory.write'), false);
  await f.client.apply(f.state.operator!.preview!.identity);
  const asked = f.calls.find(row => row.type === 'memory.write')!;
  assert.deepEqual(asked.payload, { project: 'p', proposal: { summary: 'Build', scope: 'Project builds', body: 'Use the reviewed build command.', formedBy: 'person', formedWhere: 'p' } });
  assert.equal(f.state.operator!.notice, 'Change confirmed.');
  await assert.rejects(f.client.apply('obsolete'), /Review/);
});
test('a changed command approval is withheld before the mutation; arbitrary ids cannot be reviewed', async () => {
  const f = fixture(); await f.client.prepare('approval-grant');
  assert.throws(() => f.client.preview(f.state.operator!.identity, { id: 'foreign', decision: 'once' }), /current listing/);
  f.client.preview(f.state.operator!.identity, { id: 'a', decision: 'project', prefix: '["git","status"]' });
  const identity = f.state.operator!.preview!.identity;
  f.change(() => ({ code:'OK', payload:{ approvals:[] } }));
  await assert.rejects(f.client.apply(identity), /changed/);
  assert.equal(f.calls.some(row => row.type === 'approval.answer'), false);
});
test('an unknown mutation is surfaced and neither refresh nor a repeated click replays it', async () => {
  const f = fixture(); await f.client.prepare('memory-digest', 'p'); f.client.preview(f.state.operator!.identity, {});
  const identity = f.state.operator!.preview!.identity;
  f.change(ask => { if (ask.type === 'memory.digest') throw new Error('Lost after accepting'); return { code:'OK', payload:[] }; });
  await assert.rejects(f.client.apply(identity), /may have reached/);
  await assert.rejects(f.client.apply(identity), /Review/);
  await f.client.prepare('memory-digest', 'p');
  assert.equal(f.calls.filter(row => row.type === 'memory.digest').length, 1);
});
test('control replacement and disconnect invalidate reviewed changes', async () => {
  const f = fixture(); await f.client.prepare('memory-digest'); f.client.preview(f.state.operator!.identity, {});
  const previous = f.state.operator!.preview!.identity;
  f.client.reset(); await assert.rejects(f.client.apply(previous), /Review/);
  f.state.connected = false; await assert.rejects(f.client.prepare('memory-digest'), /Connect/);
});
test('caps review uses the shared environment grammar and retains command policy', async () => {
  const f = fixture(); await f.client.prepare('caps', 'p');
  f.client.preview(f.state.operator!.identity, { key:'budget', value:50 });
  assert.match(f.state.operator!.preview!.summary, /mode: ask/); assert.match(f.state.operator!.preview!.summary, /budget: 50/);
  assert.throws(() => f.client.preview(f.state.operator!.identity, { key:'failed-checks', value:101 }), /from 1 to 100/);
});

test('Board top-up reviews a higher total, never an additive grant or a closed child topic', async () => {
  const f = fixture(); await f.client.prepare('board-topup');
  assert.throws(() => f.client.preview(f.state.operator!.identity, {id:'root',maxModelCalls:20}), /above the current/);
  f.client.preview(f.state.operator!.identity, {id:'root',maxModelCalls:140});
  assert.deepEqual(f.state.operator!.preview!.payload, {topic:'root',maxModelCalls:140});
  assert.match(f.state.operator!.preview!.summary, /new total/);
});
test('a saved cap file remains confirmed when the server reload reply is lost', async () => {
  const f = fixture(); await f.client.prepare('caps','p'); f.client.preview(f.state.operator!.identity,{key:'budget',value:50});
  let reads=0; f.change(ask => { if(ask.type==='orchestration.caps' && ++reads>1) throw new Error('Reload reply lost'); return administrative[ask.type]; });
  await assert.rejects(f.client.apply(f.state.operator!.preview!.identity), /saved locally.*unconfirmed/);
  assert.match(f.state.operator!.notice!, /saved locally/);
});
