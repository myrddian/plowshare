import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ScheduleClient, scheduleIdentity } from './schedules.ts';
import { demoState } from './demo.ts';
import { emptyActivity } from './shared.ts';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type { ScheduleProposal, ScheduleRecord, TriggerRecord } from 'plowshare-client-ts/operations/administrative-replies';
const proposal: ScheduleProposal = { cron: '0 0 9 * * *', zone: 'Australia/Melbourne', when: 'Every day at 9am', agent: 'assistant', task: 'Read the news', intoConversation: false, project: null, conversation: null, nextFires: ['2026-10-03T09:00:00+10:00'], names: { schedule: 'morning', trigger: 'morning-run', event: 'morning.tick' } };
const schedule: ScheduleRecord = { name: 'morning', cron: proposal.cron, zone: proposal.zone, emits: proposal.names.event, paused: false, nextFireAt: proposal.nextFires[0], definedBy: 'fixture' };
const trigger: TriggerRecord = { name: proposal.names.trigger, event: proposal.names.event, project: null, conversation: null, agent: proposal.agent, task: proposal.task, maxModelCalls: null, maxTurns: null, queueCap: 3, paused: false, definedBy: 'fixture' };
function fixture() {
  const state = { ...demoState(), mode: 'live' as const, connected: true, activity: emptyActivity() };
  const calls: { type: string; payload: unknown }[] = [];
  let reply: (ask: typeof calls[number]) => Outcome | Promise<Outcome> = ask => ({ code: 'OK', payload: ask.type === 'schedule.read' ? proposal : ask.type === 'schedule.define' ? schedule : ask.type === 'trigger.define' ? trigger : [] });
  const client = new ScheduleClient(() => state, async ask => { calls.push(ask); return reply(ask); }, () => {});
  return { state, client, calls, reply: (fn: typeof reply) => { reply = fn; } };
}
test('preview is read-only and save creates exactly the reviewed schedule and trigger', async () => {
  const f = fixture(); await f.client.preview('Read the news every morning', proposal.zone);
  assert.deepEqual(f.calls.map(call => call.type), ['schedule.read']); assert.deepEqual(f.state.activity.schedules!.proposal, proposal);
  await f.client.save(scheduleIdentity(proposal));
  assert.deepEqual(f.calls.slice(1,5).map(call => call.type), ['schedule.list','trigger.list','schedule.define','trigger.define']);
  assert.deepEqual(f.calls[3].payload, { schedule: 'morning', cron: proposal.cron, zone: proposal.zone, emits: 'morning.tick' });
  assert.deepEqual(f.calls[4].payload, { trigger: 'morning-run', event: 'morning.tick', agent: 'assistant', task: 'Read the news' });
  assert.equal(f.state.activity.schedules!.proposal, undefined); assert.equal(f.state.activity.schedules!.notice, 'Schedule and trigger saved.');
});
test('failed or foreign previews cannot replace the reviewed scope or enable save', async () => {
  const f = fixture(); f.reply(() => ({ code: 'OK', payload: { ...proposal, project: 'foreign' } }));
  await assert.rejects(f.client.preview('Read news', proposal.zone), /different destination/); assert.equal(f.state.activity.schedules!.proposal, undefined);
  await assert.rejects(f.client.save(scheduleIdentity(proposal)), /Review/);
  await assert.rejects(f.client.preview('Read news', proposal.zone, 'foreign'), /available project/);
});
test('duplicate names block replacing existing work', async () => {
  const f = fixture(); await f.client.preview('Daily news', proposal.zone);
  f.reply(ask => ({ code: 'OK', payload: ask.type === 'schedule.list' ? [schedule] : [] }));
  await assert.rejects(f.client.save(scheduleIdentity(proposal)), /already exists/); assert.equal(f.calls.some(call => call.type.endsWith('.define')), false);
});
test('partial save records the saved schedule, never retries or rolls it back implicitly', async () => {
  const f = fixture(); await f.client.preview('Daily news', proposal.zone);
  f.reply(ask => ask.type === 'trigger.define' ? { code: 'BAD_REQUEST', said: 'Bot unavailable' } : { code: 'OK', payload: ask.type === 'schedule.define' ? schedule : [] });
  await assert.rejects(f.client.save(scheduleIdentity(proposal)), /Bot unavailable/);
  assert.match(f.state.activity.schedules!.notice!, /trigger is not yet confirmed/); assert.match(f.state.activity.schedules!.error!, /not be replayed/);
  assert.equal(f.calls.filter(call => call.type === 'schedule.define').length, 1); assert.equal(f.calls.filter(call => call.type === 'trigger.define').length, 1);
  assert.equal(f.calls.some(call => call.type === 'schedule.forget'), false);
});
test('disconnect after saving the schedule cannot send the trigger on a replacement connection', async () => {
  const f = fixture(); await f.client.preview('Daily news', proposal.zone); let release!: (value: Outcome) => void;
  f.reply(ask => ask.type === 'schedule.define' ? new Promise(resolve => { release = resolve; }) : { code: 'OK', payload: [] });
  const saving = f.client.save(scheduleIdentity(proposal)); await new Promise(resolve => setTimeout(resolve,0));
  f.client.reset(); f.state.connected = false; release({ code: 'OK', payload: schedule }); await assert.rejects(saving, /connection changed/);
  assert.equal(f.calls.some(call => call.type === 'trigger.define'), false); assert.equal(f.state.activity.schedules!.proposal, undefined);
});
test('malformed account lists retain the complete previous schedule/trigger/firing snapshot', async () => {
  const f = fixture(); f.reply(ask => ({ code: 'OK', payload: ask.type === 'schedule.list' ? [schedule] : ask.type === 'trigger.list' ? [trigger] : [] }));
  await f.client.refresh(); const value = f.state.activity.schedules!;
  f.reply(ask => ({ code: 'OK', payload: ask.type === 'trigger.list' ? [{ name: 'broken' }] : [] })); await f.client.refresh();
  assert.deepEqual(value.schedules, [schedule]); assert.deepEqual(value.triggers, [trigger]); assert.match(value.error!, /incomplete/);
});
test('pause/remove/fire reread the displayed identity and never start an agent turn', async () => {
  const f = fixture(); f.reply(ask => ['schedule.pause','trigger.forget'].includes(ask.type) ? { code: 'NO_CONTENT', payload: null } : { code: 'OK', payload: ask.type === 'schedule.list' ? [schedule] : ask.type === 'trigger.list' ? [trigger] : [] });
  await f.client.refresh(); await f.client.change('schedule', schedule.name, scheduleIdentity(schedule), true); await f.client.change('trigger', trigger.name, scheduleIdentity(trigger)); await f.client.fire(trigger.name, scheduleIdentity(trigger));
  assert.deepEqual(f.calls.find(call => call.type === 'schedule.pause')!.payload, { schedule: 'morning', paused: true });
  assert.deepEqual(f.calls.find(call => call.type === 'event.fire')!.payload, { event: 'morning.tick' }); assert.equal(f.calls.some(call => call.type === 'agent.run'), false);
  await assert.rejects(f.client.change('trigger', 'foreign', 'anything'), /displayed/);
});
test('stale displayed configuration blocks pause/removal and event emission', async () => {
  const f = fixture(); f.reply(ask => ({ code: 'OK', payload: ask.type === 'schedule.list' ? [schedule] : ask.type === 'trigger.list' ? [trigger] : [] })); await f.client.refresh();
  f.reply(ask => ({ code: 'OK', payload: ask.type === 'schedule.list' ? [{ ...schedule, cron: 'changed' }] : [{ ...trigger, task: 'changed' }] }));
  await assert.rejects(f.client.change('schedule', schedule.name, scheduleIdentity(schedule), true), /changed elsewhere/); await assert.rejects(f.client.fire(trigger.name, scheduleIdentity(trigger)), /changed elsewhere/);
  assert.equal(f.calls.some(call => ['schedule.pause','event.fire'].includes(call.type)), false);
});
