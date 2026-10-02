import test from 'node:test';
import assert from 'node:assert/strict';
import { BoardClient, detailOf, swarmOf, topicsOf } from './board.ts';
import { demoState } from './demo.ts';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
const ok = (payload: unknown): Outcome => ({ code: 'OK', payload });
const sample = () => { const s = demoState(); s.mode = 'live'; s.connected = true; return s; };

test('board parsing rejects truncated or mismatched data instead of showing false empty states', () => {
  const s = demoState(), detail = s.board.details['demo-board'].value!;
  assert.equal(detailOf(detail).messages[1].body, detail.messages[1].body);
  assert.throws(() => detailOf({ ...detail, seats: [{}] }), /incomplete/);
  assert.throws(() => detailOf({ ...detail, messages: [{ ...detail.messages[0], topic: 'other' }] }), /mismatched/);
  assert.throws(() => swarmOf({ ...s.board.swarm.value, ready: [{}] }), /incomplete/);
  assert.throws(() => topicsOf({ topics: [{}], more: false, offset: 0 }), /incomplete/);
});
test('only open viewers read, failures retain snapshots, and reset fences pending reads', async () => {
  const s = sample(), before = s.board.swarm.value;
  let calls = 0, pending: ((answer: Outcome) => void) | undefined;
  const client = new BoardClient(() => s, async () => { calls++; return new Promise<Outcome>(resolve => { pending = resolve; }); }, () => {});
  await client.refresh(); assert.equal(calls, 0);
  const open = client.open('swarm'); assert.equal(calls, 1);
  pending!({ code: 'BAD_REQUEST', said: 'Upgrade the server.' }); await open;
  assert.equal(s.board.swarm.value, before); assert.equal(s.board.swarm.error, 'Upgrade the server.');
  const late = client.refresh(); client.reset(); pending!(ok({ nonsense: true })); await late;
  assert.equal(s.board.swarm.value, before); assert.equal(s.board.swarm.error, 'Upgrade the server.');
  await client.open(); await client.refresh(); assert.equal(calls, 2);
});
test('topic pages survive refresh and a failed later page preserves the displayed tree', async () => {
  const s = sample(), source = s.board.topics.value![0];
  const rows = Array.from({ length: 240 }, (_, i) => ({ ...source, topic: { ...source.topic, id: `topic-${i}` } }));
  let refuse = false;
  const client = new BoardClient(() => s, async (type, payload) => {
    assert.equal(type, 'board.topics');
    const p = payload as { offset?: number }; const offset = p.offset ?? 0;
    if (refuse && offset) return { code: 'BAD_REQUEST', said: 'Unavailable second page.' };
    return ok({ topics: rows.slice(offset, offset + 200), offset, more: offset + 200 < rows.length });
  }, () => {});
  await client.open('board'); assert.equal(s.board.topics.value?.length, 200);
  await client.more(); assert.equal(s.board.topics.value?.length, 240); assert.equal(s.board.topics.more, false);
  await client.refresh(); assert.equal(s.board.topics.value?.length, 240);
  refuse = true; await client.refresh(); assert.equal(s.board.topics.value?.length, 240); assert.match(s.board.topics.error!, /second page/);
  client.reset();
});
test('seat trajectories only admit conversations in the selected topic', async () => {
  const s = demoState(), client = new BoardClient(() => s, async () => { throw new Error('Demo must not ask the server'); }, () => {});
  await client.open('board', 'Plowshare'); assert.equal(s.board.topics.value?.length, 2); await client.select('demo-board');
  client.conversation('demo-desktop'); assert.throws(() => client.conversation('not-displayed'), /displayed/);
  await assert.rejects(client.select('not-available'), /available/);
  client.reset();
});

test('Swarm reads member activity without board detail, and trajectories come directly from listed members', async () => {
  const s = sample(), frames: { type: string; payload: unknown }[] = [], snapshot = s.board.swarm.value!;
  s.board.selected = 'demo-child';
  const client = new BoardClient(() => s, async (type, payload) => {
    frames.push({ type, payload });
    if (type === 'swarm.status') return ok(snapshot);
    assert.equal(type, 'conversation.trajectory');
    return ok({ entries: [{ ordinal: 1, turnOrdinal: 1, kind: 'answer', state: 'stands', excerpt: 'Researching conflicts' }], through: 1 });
  }, () => {});
  await client.open('swarm', 'Plowshare');
  assert.equal(frames.filter(f => f.type === 'conversation.trajectory').length, 3);
  assert.equal(frames.some(f => f.type.startsWith('board.')), false);
  assert.equal(s.board.activity!['demo-swarm-researcher'].value!.entries[0].text, 'Researching conflicts');
  client.conversation('demo-swarm-spec_writer');
  assert.throws(() => client.conversation('demo-global'), /displayed/);
  client.reset();
});
test('member activity refusal keeps previous actions and closed views fence pending batches', async () => {
  const s = sample(), snapshot = s.board.swarm.value!, before = s.board.activity!['demo-swarm-researcher'].value;
  const pending: ((answer: Outcome) => void)[] = [];
  let calls = 0;
  const client = new BoardClient(() => s, async type => {
    calls++;
    if (type === 'swarm.status') return ok(snapshot);
    return new Promise<Outcome>(resolve => pending.push(resolve));
  }, () => {});
  const opening = client.open('swarm');
  for (let i = 0; i < 20; i++) await Promise.resolve();
  assert.equal(pending.length, 3);
  pending.splice(0).forEach(resolve => resolve({ code: 'BAD_REQUEST', said: 'Activity refused' })); await opening;
  assert.equal(s.board.activity!['demo-swarm-researcher'].value, before);
  assert.equal(s.board.activity!['demo-swarm-researcher'].error, 'Activity refused');
  snapshot.seats.push(...snapshot.seats.slice(0, 3).map((s, i) => ({ ...s, seat: { ...s.seat, occupant: `extra-${i}`, conversation: `extra-${i}` } })));
  const refresh = client.refresh(); for (let i = 0; i < 20; i++) await Promise.resolve();
  assert.equal(pending.length, 4);
  client.reset(); const count = calls;
  pending.splice(0).forEach(resolve => resolve(ok({ entries: [], through: 0 }))); await refresh;
  assert.equal(calls, count);
  assert.equal(s.board.activity!['demo-swarm-researcher'].value, before);
  assert.equal(s.board.activity!['demo-swarm-researcher'].loading, false);
});
test('member paging bounds log reads and trajectory access to the displayed prefix', async () => {
  const s = sample(), snapshot = s.board.swarm.value!;
  const source = snapshot.seats[0];
  snapshot.seats = Array.from({ length: 45 }, (_, i) => ({ ...source, seat: { ...source.seat, occupant: `member-${String(i).padStart(2, '0')}`, conversation: `seat-${i}` } }));
  let activityReads = 0, active = 0, peak = 0;
  const client = new BoardClient(() => s, async type => {
    if (type === 'swarm.status') return ok(snapshot);
    activityReads++; active++; peak = Math.max(peak, active);
    await new Promise(resolve => setTimeout(resolve, 1)); active--;
    return ok({ entries: [], through: 0 });
  }, () => {});
  await client.open('swarm'); assert.equal(activityReads, 40); assert.ok(peak <= 4);
  assert.throws(() => client.conversation('seat-44'), /displayed/);
  await client.more(); assert.equal(activityReads, 85);
  client.conversation('seat-44');
  client.reset();
});

test('demo members open their own recorded actions in the trajectory', async () => {
  const s = demoState(), client = new BoardClient(() => s, async () => { throw new Error('No network in demo'); }, () => {});
  await client.open('swarm');
  client.conversation('demo-swarm-researcher');
  client.conversation('demo-swarm-spec_writer');
  assert.equal(s.history['demo-swarm-researcher'].entries[1].calls![0].name, 'search');
  assert.equal(s.history['demo-swarm-spec_writer'].entries[1].calls![0].name, 'board_post');
  client.reset();
});
