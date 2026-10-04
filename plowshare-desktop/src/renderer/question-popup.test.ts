import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pendingQuestions, questionInScope } from './question-popup.ts';
import { questionControls } from './run-controls.ts';
import { demoState } from '../demo.ts';
import { emptyActivity, runQuestion } from '../shared.ts';
import { runStatusOf } from 'plowshare-client-ts/operations/inspection';
import { statusWire, runWire } from '../run-fixtures.ts';
function fixture() {
  const state = { ...demoState(), mode: 'live' as const, connected: true, activity: emptyActivity() };
  const add = (id: string, status = 'asking', project: string | null = null) => {
    const wire = statusWire(id, status, { orchestration: runWire(id, status, { project }) });
    const value = runStatusOf({ code: 'OK', payload: wire })!;
    state.activity.runs.items.push(value.run); state.activity.details[id] = { wire, value };
    return wire;
  };
  return { state, add };
}
test('question queue includes children and excludes unread, foreign or settled status', () => {
  const f = fixture(); f.add('root'); f.add('child', 'asking', 'Other'); f.add('settled', 'finished');
  f.state.activity.details.child.wire = { ...f.state.activity.details.child.wire!, orchestration: { ...f.state.activity.details.child.wire!.orchestration, parent: 'root' } };
  f.state.activity.runs.items.push(runStatusOf({ code: 'OK', payload: statusWire('unread') })!.run);
  const mismatched = f.add('mismatched'); f.state.activity.details.mismatched.wire = { ...mismatched, orchestration: { ...mismatched.orchestration, id: 'foreign' } };
  const stale = f.add('stale'); f.state.activity.details.stale.wire = { ...stale, orchestration: { ...stale.orchestration, state: 'running' } };
  assert.deepEqual(pendingQuestions(f.state).map(row => row.id), ['root', 'child']);
  assert.equal(pendingQuestions(f.state)[0].question, runQuestion(f.state.activity.details.root.wire!));
  f.state.activity.runs.items = []; assert.deepEqual(pendingQuestions(f.state), []);
});
test('automatic popups follow the active workspace or exact caller conversation', () => {
  assert.equal(questionInScope(runWire('root', 'asking', { project: 'Research' }), 'Research', ''), true);
  assert.equal(questionInScope(runWire('child', 'asking', { project: 'Other', callerConversation: 'selected' }), 'Research', 'selected'), true);
  assert.equal(questionInScope(runWire('unrelated', 'asking', { project: 'Other' }), 'Research', 'selected'), false);
  assert.equal(questionInScope(runWire('global'), '', ''), true);
});
test('plain and structured controls disable offline and sending decisions, with no selected default', () => {
  const f = fixture(), wire = f.add('root'); assert.match(questionControls(f.state, 'root'), /Your answer/);
  const structured: typeof wire = { ...wire, messages: [{ ...wire.messages[0], structure: { lead: 'Choose scope', questions: [{ header: 'Scope', question: 'Which parts?', multi: true, options: [{ label: 'Backend', description: 'Server work' }, { label: 'UI', description: 'Desktop work', preview: 'Changes <safe>' }] }] } }] };
  f.state.activity.details.root.wire = structured;
  f.state.activity.details.root.value = runStatusOf({ code: 'OK', payload: structured })!;
  const controls = questionControls(f.state, 'root');
  assert.equal((controls.match(/type="checkbox"/g) ?? []).length, 2);
  assert.doesNotMatch(controls, /<input[^>]* checked(?:\s|>)/); assert.match(controls, /Changes &lt;safe&gt;/);
  assert.match(controls, /Another answer/); assert.match(controls, /Note/);
  f.state.connected = false; assert.match(questionControls(f.state, 'root'), /id="run-answer-send" disabled/);
  f.state.connected = true; f.state.activity.decisions = { root: { busy: true } };
  assert.match(questionControls(f.state, 'root'), /id="run-answer-send" disabled>Sending/);
});
