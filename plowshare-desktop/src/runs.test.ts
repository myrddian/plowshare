import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RunClient } from './runs.ts';
import { runWire, statusWire } from './run-fixtures.ts';
import { demoState } from './demo.ts';
import { emptyActivity, runQuestion, inspectionConversation } from './shared.ts';
import { runStatusOf } from 'plowshare-client-ts/operations/inspection';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type { Request as Ask } from 'plowshare-client-ts/operations/direct';
const row = (ordinal: number, detail: string | null = null) => ({ ordinal, at: '2026-10-01T00:00:00Z', run: 'root', actor: 'conductor', kind: 'tool_call', text: 'Read sources', detail });
const page = (rows = [row(100)], more = false) => ({ root: 'root', rows, total: 100, limit: 100, through: 100, oldest: rows[0]?.ordinal ?? null, more });
function fixture() {
  const state = { ...demoState(), mode: 'live' as const, connected: true, activity: emptyActivity() };
  const initial = statusWire(); state.activity.runs.items = [runStatusOf({ code: 'OK', payload: initial })!.run];
  state.activity.details.root = { wire: initial, value: runStatusOf({ code: 'OK', payload: initial })! };
  const calls: Ask[] = []; let wire = initial;
  let reply: (ask: Ask) => Outcome | Promise<Outcome> = ask => ({ code: 'OK', payload: ask.type === 'orchestration.status' ? wire : ask.type === 'orchestration.record' ? page() : { id: 'root', state: 'running' } });
  const client = new RunClient(() => state, async ask => { calls.push(ask as Ask); return reply(ask as Ask); }, () => {});
  return { state, client, calls, wire: (value: typeof wire) => { wire = value; }, reply: (fn: typeof reply) => { reply = fn; } };
}
test('plain answers check current identity, send once and preserve confirmed receipt', async () => {
  const f = fixture(); await f.client.answer('root', runQuestion(f.state.activity.details.root.wire!), 'Read archives');
  assert.deepEqual(f.calls.map(call => call.type), ['orchestration.status','orchestration.answer','orchestration.status']);
  assert.deepEqual(f.calls[1].payload, { id: 'root', answer: 'Read archives' });
  assert.equal(f.state.activity.decisions!.root.notice, 'Answer recorded.');
  assert.equal(f.calls.some(call => call.type === 'agent.run'), false);
});
test('remote settlement and changed question block stale human decisions', async () => {
  const f = fixture(), shown = runQuestion(f.state.activity.details.root.wire!);
  f.wire(statusWire('root', 'running')); await assert.rejects(f.client.answer('root', shown, 'yes'), /changed|elsewhere/);
  assert.equal(f.calls.length, 1); assert.equal(f.state.activity.details.root.wire!.orchestration.state, 'running');
  const g = fixture(); g.wire(statusWire('root', 'asking', { messages: [{ ...statusWire().messages[0], id: 'new-question' }] }));
  await assert.rejects(g.client.answer('root', runQuestion(g.state.activity.details.root.wire!), 'yes'), /changed/);
  assert.equal(g.calls.some(call => call.type === 'orchestration.answer'), false);
});
test('disconnect during preflight blocks a mutation and ignores late state', async () => {
  const f = fixture(); let release!: (answer: Outcome) => void; f.reply(() => new Promise(resolve => { release = resolve; }));
  const work = f.client.answer('root', runQuestion(f.state.activity.details.root.wire!), 'yes');
  f.client.reset(); f.state.connected = false; release({ code: 'OK', payload: statusWire() });
  await assert.rejects(work, /connection changed/i); assert.equal(f.calls.length, 1); assert.equal(f.state.activity.decisions!.root.busy, false);
});
test('ambiguous mutation failure is visible and never replayed; a confirmed answer retains its receipt if refresh fails', async () => {
  const f = fixture(); f.reply(ask => { if (ask.type === 'orchestration.answer') throw new Error('Socket dropped'); return { code: 'OK', payload: statusWire() }; });
  await assert.rejects(f.client.answer('root', runQuestion(f.state.activity.details.root.wire!), 'yes'), /Socket dropped/);
  assert.equal(f.calls.filter(call => call.type === 'orchestration.answer').length, 1); assert.match(f.state.activity.decisions!.root.error!, /Refresh/);
  const g = fixture(); let status = 0; g.reply(ask => ask.type === 'orchestration.status' ? ++status === 1 ? { code: 'OK', payload: statusWire() } : { code: 'BAD_REQUEST', said: 'Refresh failed' } : { code: 'OK', payload: { id: 'root', state: 'running' } });
  await assert.rejects(g.client.answer('root', runQuestion(g.state.activity.details.root.wire!), 'yes'), /Refresh failed/);
  assert.equal(g.state.activity.decisions!.root.notice, 'Answer recorded.');
});
test('structured answers enforce headers, multiplicity and valid options before sending', async () => {
  const f = fixture(); const wire = statusWire('root', 'asking', { messages: [{ ...statusWire().messages[0], structure: { lead: 'Choose sources', questions: [{ header: 'Sources', question: 'Where?', multi: false, options: [{ label: 'Archives', description: 'Original materials' }, { label: 'Books', description: 'Summaries' }] }] } }] });
  f.state.activity.details.root = { wire, value: runStatusOf({ code: 'OK', payload: wire })! }; f.wire(wire);
  const question = runQuestion(wire);
  await assert.rejects(f.client.answer('root', question, 'yes'), /structured/);
  await assert.rejects(f.client.answer('root', question, undefined, [{ header: 'Sources', chosen: ['Unknown'] }]), /valid options/);
  await assert.rejects(f.client.answer('root', question, undefined, [{ header: 'Sources', chosen: ['Archives','Books'] }]), /valid options/);
  assert.equal(f.calls.length, 0);
  const choices = [{ header: 'Sources', chosen: ['Archives'], note: 'Focus on this century' }];
  await f.client.answer('root', question, undefined, choices); assert.deepEqual(f.calls[1].payload, { id: 'root', choices });
});
test('cancellation rechecks state and never starts a turn; unknown runs cannot mutate or open trajectories', async () => {
  const f = fixture(); await f.client.cancel('root'); assert.equal(f.calls.filter(call => call.type === 'orchestration.cancel').length, 1);
  assert.equal(f.client.conversation('root', 'conductor'), 'conductor'); assert.equal(inspectionConversation(f.state, 'conductor'), true);
  await assert.rejects(f.client.cancel('foreign'), /available/); assert.throws(() => f.client.conversation('foreign', 'caller'), /available/);
  const g = fixture(); g.wire(statusWire('root','finished')); await assert.rejects(g.client.cancel('root'), /ended/); assert.equal(g.calls.length, 1);
});
test('record pages merge by ordinal; failed filter change preserves the loaded snapshot and filter', async () => {
  const f = fixture(); f.reply(() => ({ code: 'OK', payload: page([row(90),row(100)], true) })); await f.client.record('root');
  f.reply(() => ({ code: 'OK', payload: page([row(80),row(90)], false) })); await f.client.record('root', 90);
  assert.deepEqual(f.state.activity.records!.root.rows.map(row => row.ordinal), [80,90,100]);
  const previous = f.state.activity.records!.root.rows;
  f.reply(() => ({ code: 'OK', payload: { ...page(), rows: [{ ordinal: 1 }] } })); await f.client.record('root', undefined, ['tool_call']);
  assert.equal(f.state.activity.records!.root.rows, previous); assert.equal(f.state.activity.records!.root.kinds, undefined); assert.match(f.state.activity.records!.root.error!, /incomplete/);
  await assert.rejects(f.client.record('root', 50), /current record/);
});
test('out of order record reads and reset retain the latest complete snapshot', async () => {
  const f = fixture(); const replies: ((value: Outcome) => void)[] = []; f.reply(() => new Promise(resolve => replies.push(resolve)));
  const first = f.client.record('root'), second = f.client.record('root', undefined, ['tool_call']);
  replies[1]({ code: 'OK', payload: page([row(99)]) }); await second; replies[0]({ code: 'OK', payload: page([row(50)]) }); await first;
  assert.deepEqual(f.state.activity.records!.root.rows.map(row => row.ordinal), [99]);
  const third = f.client.record('root'); f.client.reset(); replies[2]({ code: 'OK', payload: page([row(100)]) }); await third;
  assert.deepEqual(f.state.activity.records!.root.rows.map(row => row.ordinal), [99]);
});
test('record settlement replaces an already loaded tool outcome after tail reconciliation', async () => {
  const f = fixture(); await f.client.record('root');
  f.reply(ask => ({ code: 'OK', payload: page([row(100, 'after' in (ask.payload as object) ? 'Read complete' : null)]) }));
  f.client.push({ kind: 'orchestration.recorded', root: 'root', settled: 100 });
  await new Promise(resolve => setTimeout(resolve, 0)); assert.equal(f.state.activity.records!.root.rows[0].detail, 'Read complete');
  f.client.pause(); const count = f.calls.length; f.client.push({ kind: 'orchestration.recorded', root: 'root', settled: 100 }); await new Promise(resolve => setTimeout(resolve, 0)); assert.equal(f.calls.length, count);
});
test('definitions keep withholding reasons and stages; malformed reads retain the prior scope', async () => {
  const f = fixture(), definition = { name: 'research', description: 'Research', tier: 'global', stages: [{ id: 'read', doneWhen: 'Sources read', mayReturnTo: [] }], triggers: [], served: false, withheld: 'Missing agent' };
  f.reply(() => ({ code: 'OK', payload: { definitions: [definition] } })); await f.client.definitions(); assert.deepEqual(f.state.activity.definitions!.items, [definition]);
  f.reply(() => ({ code: 'OK', payload: { definitions: [{ name: 'broken' }] } })); await f.client.definitions(); assert.deepEqual(f.state.activity.definitions!.items, [definition]);
  await assert.rejects(f.client.definitions('foreign'), /project/);
});
test('a second click cannot send a duplicate answer while the first preflight is pending', async () => {
  const f = fixture(); let release!: (value: Outcome) => void; let reads = 0;
  f.reply(ask => ask.type === 'orchestration.status' && ++reads === 1 ? new Promise(resolve => { release = resolve; }) : { code: 'OK', payload: ask.type === 'orchestration.status' ? statusWire() : { id: 'root', state: 'running' } });
  const question = runQuestion(f.state.activity.details.root.wire!), first = f.client.answer('root', question, 'yes');
  await assert.rejects(f.client.answer('root', question, 'yes'), /already being sent/); assert.equal(f.calls.length, 1);
  release({ code: 'OK', payload: statusWire() }); await first; assert.equal(f.calls.filter(call => call.type === 'orchestration.answer').length, 1);
});
