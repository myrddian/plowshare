import { agentWire, conversationWire, entryWire, entryPageWire, contextWire, approvalWire } from './wire-fixtures.ts';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DesktopClient, validatedBase } from './client.ts';
import type { Connector } from './client.ts';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import { contextKey, stoppedJob } from './shared.ts';

function fixture() {
  let push: (value: unknown) => void = () => {};
  let closed: () => void = () => {};
  let calls: { type: string; payload: Record<string, unknown> }[] = [];
  let onRun: (payload: Record<string, unknown>) => Promise<Outcome> = async () => ({ code: 'ACCEPTED', payload: { id: 'job-one' } });
  let onStatus: (payload: Record<string, unknown>) => Outcome | Promise<Outcome> = payload => ({ code: 'OK', payload: { id: payload.job, state: 'RUNNING' } });
  let onHistory: () => Outcome = () => ({ code: 'OK', payload: entryPageWire() });
  let approvalRows: unknown[] = [];
  let onApproval: () => Outcome | Promise<Outcome> = () => ({ code: 'OK' });
  let onInformation: (type: string,payload: Record<string,unknown>) => Outcome | Promise<Outcome> = () => ({code:"OK",payload:[]});
  let projects: unknown = [];
  let onAgents: () => Outcome | Promise<Outcome> = () => ({ code: 'OK', payload: [agentWire()] });
  let onApprovals: (() => Outcome) | undefined;
  let onContext: (payload: Record<string, unknown>) => Outcome | Promise<Outcome> = () => ({ code: 'OK', payload: contextWire() });
  let onTrajectory: (payload: Record<string, unknown>) => Outcome | Promise<Outcome> = () => ({ code: 'OK', payload: entryPageWire() });
  let onFollow: (payload: Record<string, unknown>) => Outcome | Promise<Outcome> = () => ({ code: 'OK' });
  let onUsage: (type:string,payload:Record<string,unknown>)=>Promise<Outcome>|Outcome=()=>({code:'OK'});
  const connector: Connector = async (_base, _handle, _password, incoming, gone) => {
    push = incoming; closed = gone;
    return { session: 'desktop-session', connection: {
      close() {},
      async ask(type, unknownPayload) {
        const payload = (unknownPayload ?? {}) as Record<string, unknown>;
        calls.push({ type, payload });
        if(type.startsWith('usage.'))return onUsage(type,payload);
        if(type.startsWith('information.'))return onInformation(type,payload);
        if (type === 'agent.run') return onRun(payload);
        if (type === 'job.cancel') return { code: 'OK', payload: { id: payload.job, state: 'RUNNING' } };
        if (type === 'job.status') return onStatus(payload);
        if (type === 'conversation.context') return onContext(payload);
        if (type === 'agent.list') return onAgents();
        if (type === 'project.list') return { code: 'OK', payload: projects };
        if (type === 'conversation.list') return { code: 'OK', payload: [conversationWire('first'), conversationWire('second')] };
        if (type === 'conversation.trajectory') return onTrajectory(payload);
        if (type === 'conversation.follow') return onFollow(payload);
        if (type === 'approval.list') return onApprovals ? onApprovals() : { code: 'OK', payload: { approvals: approvalRows } };
        if (type === 'approval.answer') return onApproval();
        if (type === 'inbox.list') return { code: 'OK', payload: { items: [], unread: 0 } };
        if (type === 'orchestration.list') return { code: 'OK', payload: { orchestrations: [] } };
        return { code: 'OK' };
      },
    } };
  };
  const client = new DesktopClient(() => {}, connector);
  return { client, calls, agents: (value: typeof onAgents) => { onAgents = value; }, projects: (value: unknown) => { projects = value; }, rawApprovals: (value: () => Outcome) => { onApprovals = value; }, push: (value: unknown) => push(value), closed: () => closed(),
    run: (value: typeof onRun) => { onRun = value; }, status: (value: typeof onStatus) => { onStatus = value; },
    usage: (value: typeof onUsage)=>{onUsage=value;},
    information: (value: typeof onInformation) => {onInformation=value;},
    history: (value: typeof onHistory) => { onTrajectory = value; },
    context: (value: typeof onContext) => { onContext = value; },
    trajectory: (value: typeof onTrajectory) => { onTrajectory = value; }, follow: (value: typeof onFollow) => { onFollow = value; },
    approvals: (value: typeof approvalRows) => { approvalRows = value; }, answer: (value: typeof onApproval) => { onApproval = value; } };
}
const connect = (client: DesktopClient) => client.dispatch({ action: 'connect', base: 'http://127.0.0.1:8080', handle: 'tester', password: 'fixture-password' });

test('an Inbox change discovers approvals from another client without an active local job', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  f.approvals([approvalWire({ id: 'background-approval' })]);
  f.push({ kind: 'inbox.changed', unread: 1 });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.client.state.approvals[0]?.id, 'background-approval');
  assert.equal(f.calls.some(call => call.type === 'agent.run'), false);
});

test('the same approval cannot be submitted twice while another window is answering it', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  f.approvals([approvalWire()]); await f.client.dispatch({ action: 'approvals-refresh' });
  let finish!: (value: Outcome) => void;
  f.answer(() => new Promise(resolve => { finish = resolve; }));
  const answer = f.client.dispatch({ action: 'answer', id: 'approval-one', decision: 'once' });
  await assert.rejects(f.client.dispatch({ action: 'answer', id: 'approval-one', decision: 'deny' }), /already being answered/);
  assert.deepEqual(f.client.state.answeringApprovals, ['approval-one']);
  assert.equal(f.calls.filter(row => row.type === 'approval.answer').length, 1);
  f.approvals([]); finish({ code: 'OK', payload: { id: 'approval-one', state: 'allowed', job: null, busy: false, note: null } });
  await answer;
  assert.deepEqual(f.client.state.answeringApprovals, []);
});

test('manual Refresh reloads bots and their default, preserving the previous roster on refusal', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  f.agents(() => ({ code: 'OK', payload: [agentWire({ name: 'cathy' })] }));
  await f.client.dispatch({ action: 'refresh' });
  assert.equal(f.client.state.agents[''][0].name, 'cathy');
  assert.equal(f.client.state.agents[''][0].preferred, true);
  f.agents(() => ({ code: 'BAD_REQUEST', said: 'Local bot discovery unavailable' }));
  await assert.rejects(f.client.dispatch({ action: 'refresh' }), /Local bot discovery unavailable/);
  assert.equal(f.client.state.agents[''][0].name, 'cathy');
});

test('an older bot refresh cannot displace a newer scope roster', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  let release!: (value: Outcome) => void;
  let asked!: () => void;
  const pending = new Promise<void>(resolve => { asked = resolve; });
  f.agents(() => { asked(); return new Promise(resolve => { release = resolve; }); });
  const refreshing = f.client.dispatch({ action: 'refresh' });
  await pending;
  f.agents(() => ({ code: 'OK', payload: [agentWire({ name: 'cathy' })] }));
  await f.client.dispatch({ action: 'scope' });
  release({ code: 'OK', payload: [agentWire({ name: 'stale', preferred: false })] });
  await refreshing;
  assert.equal(f.client.state.agents[''][0].name, 'cathy');
});

test('server configuration accepts origins and rejects credentials, paths and other protocols', () => {
  assert.equal(validatedBase('http://localhost:8080/'), 'http://localhost:8080');
  for (const input of ['file:///tmp', 'https://user:password@example.com', 'https://example.com/path', 'https://example.com?token=a']) assert.throws(() => validatedBase(input));
});

test('a disconnected submission without a returned handle is not polled or replayed after reconnect', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  let release!: (answer: Outcome) => void;
  f.run(() => new Promise(resolve => { release = resolve; }));
  const submitted = f.client.dispatch({ action: 'run', conversation: 'first', agent: 'bot', text: 'Task' });
  const failed = assert.rejects(submitted, /connection changed/);
  while (!release) await new Promise(resolve => setTimeout(resolve, 0));
  f.closed(); await connect(f.client);
  release({ code: 'ACCEPTED', payload: { id: 'late-handle' } }); await failed;
  assert.equal(f.client.state.jobs[0].status, 'unknown');
  assert.equal(f.calls.filter(call => call.type === 'agent.run').length, 1);
  assert.equal(f.calls.filter(call => call.type === 'job.status').length, 0);
});

test('reconnect preserves a pending cancellation until a durable outcome arrives', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  await f.client.dispatch({ action: 'run', conversation: 'first', agent: 'bot', text: 'Task' });
  await f.client.dispatch({ action: 'cancel', job: 'job-one' });
  f.closed(); await connect(f.client);
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(f.client.state.jobs[0].status, 'cancelling');
  assert.equal(f.calls.filter(call => call.type === 'job.cancel').length, 1);
});

test('a trajectory read refusal after submission does not turn an accepted job into a refused submission', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  f.history(() => f.calls.some(call => call.type === 'agent.run') ? { code: 'NOT_FOUND', said: 'Trajectory unavailable.' } : { code: 'OK', payload: entryPageWire() });
  await assert.rejects(f.client.dispatch({ action: 'run', conversation: 'first', agent: 'bot', text: 'Task' }), /Trajectory unavailable/);
  assert.equal(f.client.state.jobs[0].id, 'job-one');
  assert.equal(f.client.state.jobs[0].status, 'running');
});

test('a delayed status reply from the old connection cannot finish a reconnected job', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  let release!: (answer: Outcome) => void;
  f.status(() => new Promise(resolve => { release = resolve; }));
  await f.client.dispatch({ action: 'run', conversation: 'first', agent: 'bot', text: 'Task' });
  f.closed(); f.status(() => ({ code: 'OK', payload: { id: 'job-one', state: 'RUNNING' } }));
  await connect(f.client); await new Promise(resolve => setTimeout(resolve, 0));
  release({ code: 'OK', payload: { id: 'job-one', state: 'DONE', outcome: { ending: 'ANSWERED', text: 'Obsolete result' } } });
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(f.client.state.jobs[0].status, 'running');
  assert.notEqual(f.client.state.jobs[0].text, 'Obsolete result');
});

const tick = () => new Promise(resolve => setTimeout(resolve, 0));
const entry = entryWire;
const page = (entries: ReturnType<typeof entry>[], through: number, more = false, total = entries.length): Outcome => ({ code: 'OK', payload: entryPageWire(entries, through, { more, total }) });
const followSets = (f: ReturnType<typeof fixture>) => f.calls.filter(call => call.type === 'conversation.follow').map(call => call.payload.conversations);

test('chat and native inspection owners follow their union, including shared views and complete removal', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  await f.client.dispatch({ action: 'select', conversation: 'first' });
  await f.client.followView('trajectory:second', 'second');
  await f.client.dispatch({ action: 'select', conversation: 'second' });
  await f.client.followView('trajectory:second');
  assert.deepEqual(followSets(f), [[], ['first'], ['first', 'second'], ['second']]);
  await f.client.dispatch({ action: 'select' });
  assert.deepEqual(followSets(f).at(-1), []);
  assert.equal(f.client.state.liveHistory?.status, 'ready');
  await assert.rejects(f.client.dispatch({ action: 'select', conversation: 'not-loaded' }), /available conversation/);
});

test('subscription changes serialize and an old acknowledgement cannot displace the latest views', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  let release: (outcome: Outcome) => void = () => {};
  f.follow(() => new Promise(resolve => { release = resolve; }));
  const first = f.client.dispatch({ action: 'select', conversation: 'first' });
  const second = f.client.dispatch({ action: 'select', conversation: 'second' });
  const inspection = f.client.followView('trajectory:second', 'second');
  const close = f.client.followView('trajectory:second');
  assert.deepEqual(followSets(f).at(-1), ['first']);
  f.follow(() => ({ code: 'OK' })); release({ code: 'OK' });
  await Promise.all([first, second, inspection, close]);
  assert.deepEqual(followSets(f).slice(-2), [['first'], ['second']]);
});

test('closing inspection removes its follow without waiting for a pending history read or cancelling a job', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  let release: (outcome: Outcome) => void = () => {};
  f.trajectory(() => new Promise(resolve => { release = resolve; }));
  const opening = f.client.followView('trajectory:first', 'first');
  await tick();
  await f.client.followView('trajectory:first');
  assert.deepEqual(followSets(f).at(-1), []);
  assert.equal(f.calls.some(call => call.type === 'job.cancel'), false);
  release(page([], 0)); await opening;
});

test('catch-up pages every entry kind by ordinal and retains loaded earlier scrollback', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  f.trajectory(() => page(Array.from({ length: 21 }, (_, i) => entry(i + 80)), 100, true));
  await f.client.dispatch({ action: 'select', conversation: 'first' });
  f.trajectory(() => page(Array.from({ length: 79 }, (_, i) => entry(i + 1)), 100));
  await f.client.dispatch({ action: 'history', conversation: 'first', before: 80 });
  const later = Array.from({ length: 205 }, (_, i) => entry(i + 101, i % 2 ? 'tool_result' : 'answer'));
  f.trajectory(payload => {
    assert.equal(payload.after, 100); assert.equal(payload.kinds, undefined);
    const offset = Number(payload.offset);
    return page(later.slice(offset, offset + 64), 305, false, later.length);
  });
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 305 });
  await tick();
  const history = f.client.state.history.first;
  assert.equal(history.entries.length, 305);
  assert.equal(history.through, 305); assert.equal(history.oldest, 1); assert.equal(history.more, false);
  assert.deepEqual(f.calls.filter(call => call.type === 'conversation.trajectory' && call.payload.after === 100).map(call => call.payload.offset), [0, 64, 128, 192]);
  const count = f.calls.length;
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 305 });
  f.push({ kind: 'conversation.appended', conversation: 'second', through: 50 });
  await tick(); assert.equal(f.calls.length, count);
});

test('pushes during catch-up are coalesced and read after the pending snapshot', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  f.trajectory(() => page([entry(1), entry(2)], 2));
  await f.client.dispatch({ action: 'select', conversation: 'first' });
  let release: (outcome: Outcome) => void = () => {};
  f.trajectory(() => new Promise(resolve => { release = resolve; }));
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 3 });
  await tick();
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 5 });
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 5 });
  f.trajectory(payload => { assert.equal(payload.after, 3); return page([entry(4), entry(5)], 5); });
  release(page([entry(3)], 3)); await tick();
  assert.equal(f.client.state.history.first.through, 5);
  assert.deepEqual(f.client.state.history.first.entries.map(row => row.ordinal), [1, 2, 3, 4, 5]);
});

test('growth during paged reads cannot advance the cursor past a newer unobserved turn', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  f.trajectory(() => page([entry(1)], 1));
  await f.client.dispatch({ action: 'select', conversation: 'first' });
  f.trajectory(payload => {
    if (payload.after === 4) return page([entry(5)], 5);
    assert.equal(payload.after, 1);
    return payload.offset === 0 ? page([entry(2), entry(3)], 4, false, 3)
      : page([entry(4), entry(5)], 5, false, 4);
  });
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 4 }); await tick();
  assert.equal(f.client.state.history.first.through, 5);
  assert.deepEqual(f.client.state.history.first.entries.map(row => row.ordinal), [1, 2, 3, 4, 5]);
  assert.equal(f.calls.some(call => call.type === 'conversation.trajectory' && call.payload.after === 4), true);
});

test('history refusal preserves data and cursor and a manual refresh retries without agent mutations', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  f.trajectory(() => page([entry(1), entry(2)], 2));
  await f.client.dispatch({ action: 'select', conversation: 'first' });
  f.trajectory(() => ({ code: 'INTERNAL_ERROR', said: 'History temporarily unavailable' }));
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 3 }); await tick();
  assert.equal(f.client.state.history.first.through, 2);
  assert.equal(f.client.state.history.first.entries.length, 2);
  assert.match(f.client.state.history.first.error!, /temporarily unavailable/);
  f.trajectory(() => page([entry(3)], 3));
  await f.client.dispatch({ action: 'refresh' });
  assert.equal(f.client.state.history.first.through, 3); assert.equal(f.client.state.history.first.error, undefined);
  assert.equal(f.calls.some(call => ['agent.run', 'job.cancel', 'approval.answer'].includes(call.type)), false);
});

test('an old server cannot silently downgrade multi-view following or disable foreground operations', async t => {
  const f = fixture(); t.after(() => f.client.dispose());
  f.follow(() => ({ code: 'BAD_REQUEST', said: 'conversation is required' }));
  await connect(f.client);
  await f.client.dispatch({ action: 'select', conversation: 'first' });
  assert.equal(f.client.state.connected, true);
  assert.equal(f.client.state.liveHistory?.status, 'unavailable');
  assert.match(f.client.state.liveHistory?.detail ?? '', /Update the server/);
  assert.equal(f.calls.filter(call => call.type === 'conversation.follow').every(call => Array.isArray(call.payload.conversations) && !('conversation' in call.payload)), true);
  await f.client.dispatch({ action: 'run', conversation: 'first', agent: 'bot', text: 'Task' });
  assert.equal(f.client.state.jobs[0].status, 'running');
});

test('reconnect restores the complete view set and catches up missed history without resubmitting work', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  f.trajectory(() => page([entry(1), entry(2)], 2));
  await f.client.dispatch({ action: 'select', conversation: 'first' });
  await f.client.followView('trajectory:second', 'second');
  f.closed();
  f.push({ kind: 'conversation.appended', conversation: 'first', through: 4 });
  assert.equal(f.client.state.history.first.through, 2);
  f.trajectory(payload => { assert.equal(payload.after, 2); return page([entry(3), entry(4)], 4); });
  await connect(f.client); await tick();
  assert.deepEqual(followSets(f).at(-1), ['first', 'second']);
  assert.equal(f.client.state.history.first.through, 4); assert.equal(f.client.state.history.second.through, 4);
  assert.equal(f.calls.some(call => call.type === 'agent.run'), false);
  f.trajectory(() => page([], 0));
  await f.client.dispatch({ action: 'connect', base: 'http://127.0.0.1:8080', handle: 'another', password: 'fixture-password' });
  assert.deepEqual(followSets(f).at(-1), []);
  assert.equal(f.client.state.history.first, undefined);
});

test('late subscription acknowledgements and history reads cannot cross a mode change', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  let release: (outcome: Outcome) => void = () => {};
  f.trajectory(() => new Promise(resolve => { release = resolve; }));
  const selecting = f.client.dispatch({ action: 'select', conversation: 'first' });
  await tick(); await f.client.dispatch({ action: 'demo' });
  release(page([entry(500)], 500)); await selecting;
  assert.equal(f.client.state.mode, 'demo'); assert.equal(f.client.state.history.first, undefined);
  f.trajectory(() => page([], 0)); await connect(f.client);
  f.follow(() => new Promise(resolve => { release = resolve; }));
  const subscribing = f.client.dispatch({ action: 'select', conversation: 'second' });
  await f.client.dispatch({ action: 'demo' }); release({ code: 'OK' }); await subscribing;
  assert.equal(f.client.state.mode, 'demo'); assert.equal(f.client.state.liveHistory, undefined);
});

test('early events and simultaneous runs are attributed by job handle, independent of view selection', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  const releases = new Map<string, () => void>();
  f.run(payload => new Promise(resolve => {
    const id = `job-${payload.conversation}`;
    f.push({ job: id, kind: 'started', agent: 'bot' });
    f.push({ job: id, part: 'THINKING', text: `thinking-${payload.conversation}` });
    f.push({ job: id, part: 'ANSWER', text: `answer-${payload.conversation}` });
    releases.set(String(payload.conversation), () => resolve({ code: 'ACCEPTED', payload: { id } }));
  }));
  const first = f.client.dispatch({ action: 'run', conversation: 'first', agent: 'bot', text: 'First task' });
  const second = f.client.dispatch({ action: 'run', conversation: 'second', agent: 'bot', text: 'Second task' });
  await tick();
  releases.get('second')!(); await second;
  releases.get('first')!(); await first;
  assert.equal(f.client.state.jobs.find(row => row.conversation === 'first')?.text, 'answer-first');
  assert.equal(f.client.state.jobs.find(row => row.conversation === 'second')?.text, 'answer-second');
  assert.equal(f.client.state.jobs.find(row => row.conversation === 'first')?.thinking, 'thinking-first');
  assert.equal(f.client.state.jobs.find(row => row.conversation === 'second')?.thinking, 'thinking-second');
  f.push({ job: 'job-first', part: 'THINKING', text: ' continued' });
  assert.equal(f.client.state.jobs.find(row => row.conversation === 'first')?.thinking, 'thinking-first continued');
  assert.equal(f.client.state.jobs.find(row => row.conversation === 'first')?.text, 'answer-first');
  assert.equal(f.calls.find(call => call.type === 'agent.run')?.payload.session, 'desktop-session');
});

test('cancellation remains pending until job.status confirms an outcome', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  await f.client.dispatch({ action: 'run', conversation: 'first', agent: 'bot', text: 'Task' });
  await f.client.dispatch({ action: 'cancel', job: 'job-one' });
  assert.equal(f.client.state.jobs[0].status, 'cancelling');
  f.status(() => ({ code: 'OK', payload: { id: 'job-one', state: 'DONE', outcome: { ending: 'CANCELLED', answered: false, text: 'Stopped' } } }));
  f.push({ job: 'job-one', kind: 'ended', ending: 'CANCELLED' });
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(f.client.state.jobs[0].status, 'finished');
  assert.equal(f.client.state.jobs[0].ending, 'CANCELLED');
  assert.equal(stoppedJob(f.client.state.jobs[0]), true);
});

test('successful wire outcomes are answers, retain genuine notes and clear stale polling errors', async t => {
  for (const detail of [undefined, 'The response reached the output limit.']) {
    const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
    await f.client.dispatch({ action: 'run', conversation: 'first', agent: 'bot', text: 'Research' });
    f.client.state.jobs[0].detail = 'A previous status request failed.';
    const answer = '`orc_123` (`deep_research`) is working; its result will be delivered when it finishes.';
    f.status(() => ({ code: 'OK', payload: { id: 'job-one', state: 'DONE', outcome: { ending: 'ANSWERED', answered: true, text: answer, ...(detail === undefined ? {} : { detail }) } } }));
    f.push({ job: 'job-one', kind: 'ended', ending: 'ANSWERED' });
    await new Promise(resolve => setTimeout(resolve, 0));
    const job = f.client.state.jobs[0];
    assert.equal(job.status, 'finished');
    assert.equal(job.ending, 'ANSWERED');
    assert.equal(job.answered, true);
    assert.equal(job.text, answer);
    assert.equal(job.detail, detail);
    assert.equal(stoppedJob(job), false, 'An answered run must not be classified as stopped.');
  }
});

test('terminal pace uses the TUI reader and preserves estimates, missing measurements and measured zeros', async t => {
  const cases = [
    { wire: { toolCalls: 0, reasoningTokens: 20, reasoningEstimated: true, completionTokens: 72, firstTokenMillis: 431, tokensPerSecond: 34.4 },
      expected: { toolCalls: 0, reasoningTokens: 20, reasoningEstimated: true, completionTokens: 72, firstTokenMillis: 431, tokensPerSecond: 34.4 } },
    { wire: { toolCalls: 2, reasoningTokens: null, completionTokens: 0, firstTokenMillis: null, tokensPerSecond: null }, expected: { toolCalls: 2, completionTokens: 0 } },
    { wire: null, expected: undefined },
  ];
  for (const { wire, expected } of cases) {
    const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
    await f.client.dispatch({ action: 'run', conversation: 'first', agent: 'bot', text: 'Task' });
    f.status(() => ({ code: 'OK', payload: { id: 'job-one', state: 'DONE', outcome: { ending: 'ANSWERED', answered: true, text: 'Answer', pace: wire } } }));
    f.push({ job: 'job-one', kind: 'ended', ending: 'ANSWERED' });
    await new Promise(resolve => setTimeout(resolve, 0));
    assert.deepEqual(f.client.state.jobs[0].pace, expected);
    assert.equal(f.client.state.jobs.some(job => job.conversation === 'second' && job.pace), false);
  }
});

test('refusal preserves the server reason and does not leave a running job', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  f.run(async () => ({ code: 'CONFLICT', said: 'This conversation is already running.' }));
  await assert.rejects(f.client.dispatch({ action: 'run', conversation: 'first', agent: 'bot', text: 'Task' }), /already running/);
  assert.equal(f.client.state.jobs[0].status, 'interrupted');
});

test('disconnect leaves an uncertain outcome and never resubmits a run', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  await f.client.dispatch({ action: 'run', conversation: 'first', agent: 'bot', text: 'Task' });
  f.closed();
  assert.equal(f.client.state.connected, false);
  assert.equal(f.client.state.jobs[0].status, 'unknown');
  await connect(f.client);
  assert.equal(f.calls.filter(call => call.type === 'agent.run').length, 1);
  assert.equal(f.client.state.mode, 'live');
});

test('returning to demo drops live data and ignores old socket callbacks', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  await f.client.dispatch({ action: 'demo' });
  f.closed(); f.push({ kind: 'ended', job: 'job-one' });
  assert.equal(f.client.state.mode, 'demo');
  assert.equal(f.client.state.connection, 'Offline demo');
  assert.equal(f.client.state.conversations.some(row => row.id === 'first'), false);
});

test('unknown desktop operations and broad approval decisions are refused', async t => {
  const f = fixture(); t.after(() => f.client.dispose());
  await assert.rejects(f.client.dispatch({ action: 'ask', type: 'project.forget' } as never), /Unsupported/);
  await assert.rejects(f.client.dispatch({ action: 'answer', id: 'approval', decision: 'project' } as never), /allow once or deny/);
});

test('an approval continuation tracks the returned job and its early events without submitting an agent turn', async t => {
  const f = fixture(); t.after(() => f.client.dispose());
  f.approvals([approvalWire()]);
  await connect(f.client);
  f.answer(() => {
    f.push({ job: 'continued-job', part: 'ANSWER', text: 'Continuation result' });
    return { code: 'OK', payload: { id: 'approval-one', state: 'allowed', job: 'continued-job', busy: false, note: null } };
  });
  await f.client.dispatch({ action: 'answer', id: 'approval-one', decision: 'once' });
  assert.equal(f.client.state.jobs[0].id, 'continued-job');
  assert.equal(f.client.state.jobs[0].conversation, 'first');
  assert.equal(f.client.state.jobs[0].source, 'approval');
  assert.equal(f.client.state.jobs[0].text, 'Continuation result');
  assert.equal(f.calls.filter(call => call.type === 'agent.run').length, 0);
});


test('malformed lists and history preserve existing state instead of displaying false emptiness', async t => {
  const f = fixture(); t.after(() => f.client.dispose());
  f.projects({}); await connect(f.client);
  assert.match(f.client.state.projectListError!, /unreadable project.list/);
  f.projects([]); await connect(f.client);
  f.approvals([approvalWire({ id: 'a', command: ['true'], cwd: '/tmp', defaultPrefix: [] })]);
  await f.client.dispatch({ action: 'refresh' });
  f.rawApprovals(() => ({ code: 'OK', payload: {} }));
  await assert.rejects(f.client.dispatch({ action: 'refresh' }), /unreadable approval.list/);
  assert.equal(f.client.state.approvals[0].id, 'a');
  f.history(() => ({ code: 'OK', payload: entryPageWire([entryWire(1, 'utterance', 'kept')], 1) }));
  await f.client.dispatch({ action: 'history', conversation: 'first' });
  f.history(() => ({ code: 'OK', payload: {} }));
  await assert.rejects(f.client.dispatch({ action: 'history', conversation: 'first' }), /unreadable conversation.trajectory/);
  assert.equal(f.client.state.history['first'].entries[0].text, 'kept');
});

test('a terminal reply missing its identity or answered flag cannot finish a desktop job', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  await f.client.dispatch({ action: 'run', conversation: 'first', agent: 'bot', text: 'Task' });
  for (const payload of [
    { state: 'DONE', outcome: { ending: 'ANSWERED', answered: true, text: 'invalid' } },
    { id: 'job-one', state: 'DONE', outcome: { ending: 'ANSWERED', text: 'invalid' } },
  ]) {
    f.status(() => ({ code: 'OK', payload })); f.push({ job: 'job-one', kind: 'ended' });
    await new Promise(resolve => setTimeout(resolve, 0));
    assert.notEqual(f.client.state.jobs[0].status, 'finished');
    assert.notEqual(f.client.state.jobs[0].text, 'invalid');
  }
  f.status(() => ({ code: 'OK', payload: { id: 'job-one', state: 'DONE', outcome: { ending: 'ANSWERED', answered: true, text: 'valid' } } }));
  f.push({ job: 'job-one', kind: 'ended' }); await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(f.client.state.jobs[0].status, 'finished');
  assert.equal(f.client.state.jobs[0].text, 'valid');
});


test('context measurements use model counts and remain scoped to conversation and agent', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  f.context(payload => ({ code: 'OK', payload: contextWire(payload.agent === 'other' ? null : payload.conversation === 'first' ? 15240 : 400, {}, { model: String(payload.agent), contextLength: 131072 }) }));
  await f.client.dispatch({ action: 'context', conversation: 'first', agent: 'bot' });
  await f.client.dispatch({ action: 'context', conversation: 'second', agent: 'bot' });
  await f.client.dispatch({ action: 'context', conversation: 'first', agent: 'other' });
  assert.equal(f.client.state.contexts[contextKey('first', 'bot')].sent, 15240);
  assert.equal(f.client.state.contexts[contextKey('second', 'bot')].sent, 400);
  assert.equal(f.client.state.contexts[contextKey('first', 'other')].sent, undefined);
  assert.equal(f.client.state.contexts[contextKey('first', 'other')].limit, 131072);
  assert.equal(f.client.state.contexts[contextKey('first', 'other')].model, 'other');
  assert.deepEqual(f.calls.find(call => call.type === 'conversation.context')?.payload, { conversation: 'first', agent: 'bot' });
  f.context(() => ({ code: 'NOT_FOUND', said: 'Context unavailable' }));
  await f.client.dispatch({ action: 'context', conversation: 'first', agent: 'bot' });
  assert.equal(f.client.state.contexts[contextKey('first', 'bot')].status, 'unavailable');
  assert.equal(f.client.state.contexts[contextKey('first', 'bot')].sent, 15240);
});

test('late context responses cannot replace a newer measurement or cross a mode change', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  let release: (value: Outcome) => void = () => {};
  f.context(() => new Promise(resolve => { release = resolve; }));
  const older = f.client.dispatch({ action: 'context', conversation: 'first', agent: 'bot' });
  f.context(() => ({ code: 'OK', payload: contextWire(200, {}, { contextLength: 1000 }) }));
  await f.client.dispatch({ action: 'context', conversation: 'first', agent: 'bot' });
  release({ code: 'OK', payload: contextWire(100, {}, { contextLength: 1000 }) });
  await older;
  assert.equal(f.client.state.contexts[contextKey('first', 'bot')].sent, 200);
  f.context(() => new Promise(resolve => { release = resolve; }));
  const late = f.client.dispatch({ action: 'context', conversation: 'first', agent: 'bot' });
  await f.client.dispatch({ action: 'demo' });
  release({ code: 'OK', payload: contextWire(999, {}, { contextLength: 1000 }) });
  await late;
  assert.equal(f.client.state.contexts[contextKey('first', 'bot')], undefined);
});


test('information uses the existing authenticated socket, pins its scope and preserves refusals', async t => {
  const f=fixture();t.after(()=>f.client.dispose());await connect(f.client);
  const id='same-receipt';
  f.information(()=>({code:'BAD_REQUEST',said:'An input became unavailable.'}));
  await assert.rejects(f.client.dispatch({action:'information',operation:'record.report',scope:{kind:'project',project:'research'},payload:{requestId:id,scope:{kind:'shared'}}}),/An input became unavailable/);
  const sent=f.calls.find(call=>call.type==='information.record.report')!;
  assert.deepEqual(sent.payload.scope,{kind:'project',project:'research'});assert.equal(sent.payload.requestId,id);
  assert.equal(f.calls.filter(call=>call.type==='information.record.report').length,1);
});

test('a document question is tracked and polled without attributing it to the current conversation', async t=>{
  const f=fixture();t.after(()=>f.client.dispose());await connect(f.client);
  f.information(()=>({code:'ACCEPTED',payload:{job:'document-answer',revision:'source'}}));
  f.status(()=>({code:'OK',payload:{id:'document-answer',state:'FINISHED',outcome:{ending:'ANSWERED',answered:true,text:'Grounded answer'}}}));
  await f.client.dispatch({action:'information',operation:'ask',scope:{kind:'personal'},payload:{revision:'source',question:'What does it establish?'}});
  await new Promise(resolve=>setTimeout(resolve,0));
  const job=f.client.state.jobs.find(job=>job.id==='document-answer')!;
  assert.equal(job.source,'information');assert.equal(job.revision,'source');assert.equal(job.conversation,'');
  assert.equal(job.text,'Grounded answer');assert.equal(job.status,'finished');
  assert.equal(f.calls.some(call=>call.type==='conversation.trajectory' && call.payload.conversation===''),false);
});
test('full wire validation preserves the desktop roster and context when required metadata is missing', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  f.agents(() => ({ code: 'OK', payload: [{ name: 'partial', bot: true, served: true }] }));
  await assert.rejects(f.client.dispatch({ action: 'refresh' }), /unreadable agent.list/);
  assert.equal(f.client.state.agents[''][0].name, 'bot');
  f.context(() => ({ code: 'OK', payload: contextWire(100, {}, { contextLength: 1000 }) }));
  await f.client.dispatch({ action: 'context', conversation: 'first', agent: 'bot' });
  f.context(() => ({ code: 'OK', payload: { sent: 200, prefix: { contextLength: 1000 } } }));
  await f.client.dispatch({ action: 'context', conversation: 'first', agent: 'bot' });
  assert.equal(f.client.state.contexts[contextKey('first','bot')].sent, 100); assert.equal(f.client.state.contexts[contextKey('first','bot')].status, 'unavailable');
});
test('a damaged approval acknowledgement leaves its decision uncertain and is never replayed', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  f.approvals([approvalWire()]); await f.client.dispatch({ action: 'refresh' });
  f.answer(() => ({ code: 'OK', payload: { id: 'approval-one', state: 'allowed', job: 'continued-job', busy: false } }));
  await assert.rejects(f.client.dispatch({ action: 'answer', id: 'approval-one', decision: 'once' }), /unreadable approval.answer/);
  assert.equal(f.calls.filter(call => call.type === 'approval.answer').length, 1); assert.equal(f.calls.some(call => call.type === 'agent.run'), false);
  assert.equal(f.client.state.jobs.at(-1)?.status, 'unknown');
});

test('authoring admits a served role with a builder grant while ordinary chat keeps its bot selection', async t => {
  const f = fixture(); t.after(() => f.client.dispose());
  f.agents(() => ({ code: 'OK', payload: [agentWire({ name: 'interlocutor', bot: false, orchestrations: ['design_orchestration'] })] }));
  await connect(f.client);
  await assert.rejects(f.client.dispatch({ action: 'run', conversation: 'first', agent: 'interlocutor', text: 'Ordinary chat' }), /conversational/);
  await f.client.authoringTurn('first', 'interlocutor', 'Start the granted design_orchestration');
  assert.equal(f.calls.filter(row => row.type === 'agent.run').length, 1);
});

test('authoring role refuses missing grants and retains lost replies without resubmitting', async t => {
  const f = fixture(); t.after(() => f.client.dispose()); await connect(f.client);
  await assert.rejects(f.client.authoringTurn('first', 'bot', 'Design'), /granted/);
  assert.equal(f.calls.filter(row => row.type === 'agent.run').length, 0);
  f.agents(() => ({ code: 'OK', payload: [agentWire({ name: 'interlocutor', bot: false, orchestrations: ['design_orchestration'] })] }));
  await f.client.dispatch({ action: 'refresh' });
  f.run(() => { throw new Error('Lost after accepting'); });
  await assert.rejects(f.client.authoringTurn('first', 'interlocutor', 'Design'), /Lost/);
  assert.equal(f.client.state.jobs.at(-1)?.status, 'unknown');
  await assert.rejects(f.client.authoringTurn('first', 'interlocutor', 'Design'), /uncertain/);
  assert.equal(f.calls.filter(row => row.type === 'agent.run').length, 1);
});

test('a document answer keeps early progress and uncertain submission is never replayed', async t => {
  const f=fixture();t.after(()=>f.client.dispose());await connect(f.client);
  f.information(async()=>{
    f.push({job:'document-answer',part:'ANSWER',text:'Early grounded text'});
    return {code:'ACCEPTED',payload:{job:'document-answer',revision:'source'}};
  });
  await f.client.dispatch({action:'information',operation:'ask',scope:{kind:'personal'},payload:{revision:'source',question:'Question'}});
  const job=f.client.state.jobs.find(row=>row.id==='document-answer')!;
  assert.equal(job.text,'Early grounded text');
  assert.equal(job.conversation,'');
  f.information(async()=>{throw new Error('socket lost after submission')});
  await assert.rejects(f.client.dispatch({action:'information',operation:'ask',scope:{kind:'personal'},payload:{revision:'source',question:'Second question'}}),/socket lost/);
  assert.equal(f.client.state.jobs.at(-1)?.status,'unknown');
  const count=f.calls.filter(call=>call.type==='information.ask').length;
  f.closed();await connect(f.client);
  assert.equal(f.calls.filter(call=>call.type==='information.ask').length,count);
});

test('usage subscriptions reconcile across disconnect and close without issuing agent runs',async t=>{
  const f=fixture();t.after(()=>f.client.dispose());await connect(f.client);let revision=0;
  const totals={calls:'1',attempts:'1',active_calls:'0',incomplete_attempts:'0',unknown_cost_attempts:'0',input_tokens:'10',output_tokens:'2',input_tokens_known:'1',output_tokens_known:'1',costs:{USD:'0.00001'},usage_complete:true,cost_complete:true,complete:true};
  const report=(conversation:string)=>({filters:{type:'usage.conversation',filter:{conversation}},totals,groups:[],cursor:null,health:{watermark:'1',as_of:'2026-10-02T00:00:00Z',capture_enabled:true,historical_usage:'not_imported'}});
  f.usage((type,payload)=>type==='usage.unsubscribe'?{code:'OK',payload:{}}:{code:'OK',payload:{subscription:'s'+(++revision),revision:0,filters:report(String(payload.conversation)).filters,report:report(String(payload.conversation))}});
  await f.client.dispatch({action:'usage-open',type:'usage.conversation',filter:{conversation:'first'}});
  assert.equal(f.client.state.usage?.report?.totals.input_tokens,'10');
  f.push({id:null,type:'usage.updated',protocol_version:'plowshare-v1',payload:{subscription:'s1',revision:2,report:{...report('first'),totals:{...totals,input_tokens:'20'}}}});
  f.push({id:null,type:'usage.updated',protocol_version:'plowshare-v1',payload:{subscription:'s1',revision:1,report:report('first')}});
  assert.equal(f.client.state.usage?.report?.totals.input_tokens,'20');
  f.closed();assert.equal(f.client.state.usage?.stale,true);await connect(f.client);assert.equal(revision,2);assert.equal(f.client.state.usage?.report?.totals.input_tokens,'10');
  await f.client.dispatch({action:'usage-open',type:'usage.conversation',filter:{conversation:'second'}});assert.equal(f.client.state.usage?.report?.filters.filter.conversation,'second');
  await f.client.dispatch({action:'usage-close'});assert.equal(f.client.state.usage?.report,undefined);assert.equal(f.calls.filter(c=>c.type==='usage.unsubscribe').length,2);assert.equal(f.calls.filter(c=>c.type==='agent.run').length,0);
});

test('restored job status cannot attach another conversation outcome to the saved receipt', async t => {
  const f=fixture();t.after(()=>f.client.dispose());await connect(f.client);
  f.status(()=>({code:'OK',payload:{id:'saved-job',conversation:'second',state:'DONE',outcome:{ending:'ANSWERED',answered:true,text:'Other conversation result'}}}));
  await f.client.restoreJobs([{id:'saved-job',handle:'saved-job',conversation:'first',agent:'alice'}]);
  await new Promise(resolve=>setTimeout(resolve,20));
  assert.equal(f.client.state.jobs[0].status,'unknown');
  assert.equal(f.client.state.jobs[0].text,'');
  assert.equal(f.calls.some(call=>call.type==='agent.run'),false);
});
