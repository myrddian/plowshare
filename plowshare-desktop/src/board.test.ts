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

test('person posts validate the displayed project/topic and retain a stable request ID across acknowledgment failure', async () => {
  const s=sample();s.handle='person';const row=s.board.topics.value![0], project=row.topic.project;
  s.projects.push({name:project});const calls:{type:string;payload:any}[]=[];let refuse=true;
  const client=new BoardClient(()=>s,async(type,payload)=>{
    calls.push({type,payload});if(type==='board.topics')return ok({topics:[row],more:false,offset:0});
    assert.equal(type,'board.post');if(refuse)return {code:'BAD_REQUEST',said:'Lost acknowledgment'};
    const p=payload as any;return ok({requestId:p.requestId,message:{...s.board.details['demo-board'].value!.messages[0],authorKind:'person',author:'person',topic:p.topic,body:p.body}});
  },()=>{});
  const key='00000000-0000-0000-0000-000000000089';
  await assert.rejects(client.post(project,row.topic.id,'hello',key),/Choose an open topic/);
  await client.postingTopics(project);await assert.rejects(client.post('foreign',row.topic.id,'hello',key),/Choose an open topic/);
  await assert.rejects(client.post(project,row.topic.id,'hello','bad'),/request identity/);
  await assert.rejects(client.post(project,row.topic.id,'hello',key),/Lost acknowledgment/);
  assert.equal(calls.filter(row=>row.type==='board.post').length,1);assert.match(s.board.posting!.error!,/retained/);
  refuse=false;await client.post(project,row.topic.id,'hello',key);
  assert.deepEqual(calls.filter(row=>row.type==='board.post').map(row=>row.payload.requestId),[key,key]);
  assert.equal(s.board.posting!.notice,'Posted to the board.');client.reset();
});

test('member retry validates the selection, sends one WS request, and reconciles uncertain acknowledgments', async () => {
  const s = sample(), detail = s.board.details['demo-board'].value!, project = detail.topic.project;
  s.handle = 'person'; s.projects.push({name: project});
  const seat = detail.seats.find(row => row.seat.occupant !== '@opener')!;
  seat.state = 'failed'; seat.seat.failedEnding = 'TURN_CAP';
  const calls: {type: string; payload: any}[] = [];
  let malformed = true;
  const client = new BoardClient(() => s, async (type, payload) => {
    calls.push({type,payload}); assert.equal(type, 'board.retry');
    if (malformed) return ok({});
    const p = payload as any;
    return ok({requestId: p.requestId, member: p.member, maxTurns: p.maxTurns, message: {...detail.messages[0], topic: p.topic, authorKind: 'person', author: 'person', mentions: [p.member]}});
  }, () => {});
  const key = '00000000-0000-0000-0000-000000000090';
  await assert.rejects(client.retry('foreign', detail.topic.id, seat.seat.occupant, key, 40), /Connect/);
  await assert.rejects(client.retry(project, detail.topic.id, '@opener', key, 40), /failed member/);
  await assert.rejects(client.retry(project, detail.topic.id, seat.seat.occupant, key, 0), /positive whole/);
  await assert.rejects(client.retry(project, detail.topic.id, seat.seat.occupant, 'bad', 40), /identity/);
  assert.equal(calls.length, 0);
  await assert.rejects(client.retry(project, detail.topic.id, seat.seat.occupant, key, 40), /did not confirm/);
  assert.equal(calls.length, 1); assert.match(s.board.retrying!.error!, /identity is retained/);
  // The server may have already queued it; reconciliation must work with the original ID.
  seat.state = 'ready'; seat.seat.failedEnding = null; malformed = false;
  // Reload may lose the cached seat entirely (for example, after the topic closes).
  client.reset(); delete s.board.details[detail.topic.id]; s.board.swarm.value = undefined; s.projects = [];
  await client.retry(project, detail.topic.id, seat.seat.occupant, key, 40, true);
  assert.deepEqual(calls[0], calls[1]); assert.match(s.board.retrying!.notice!, /existing conversation/);
  assert.equal(s.board.retrying!.busy, false); client.reset();
});
test('member retries prevent overlapping submissions and reject mismatched receipts', async () => {
  const s = sample(), detail = s.board.details['demo-board'].value!, seat = detail.seats[0], project = detail.topic.project;
  s.handle = 'person'; s.projects.push({name:project}); seat.seat.occupant = 'researcher'; seat.state = 'failed'; seat.seat.failedEnding = 'UNAVAILABLE';
  let complete!: (v: Outcome) => void;
  const client = new BoardClient(() => s, () => new Promise(resolve => {complete = resolve;}), () => {});
  const key = '00000000-0000-0000-0000-000000000091';
  const pending = client.retry(project, detail.topic.id, 'researcher', key, 24);
  await assert.rejects(client.retry(project, detail.topic.id, 'researcher', key, 24), /wait/);
  complete(ok({requestId:key, member:'critic', maxTurns:24, message:{...detail.messages[0],author:'person',authorKind:'person',mentions:['critic']}}));
  await assert.rejects(pending, /did not confirm/); assert.equal(s.board.retrying!.busy, false); client.reset();
});
