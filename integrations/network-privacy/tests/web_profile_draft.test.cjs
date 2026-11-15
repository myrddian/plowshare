const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../network-privacy-watch/orchestrations/profile_network_device.js'), 'utf8');
function step(result, state = {delegated: true}) {
  const context = vm.createContext({modelOutput: {recover() { return result; }}});
  vm.runInContext(source.replace(/^export /gm, '') + '\nglobalThis.runStep = step;', context);
  return context.runStep({todos: [{stageId: 'draft', id: 'fixture', status: 'in_progress'}],
    state, result: 'retained model text'});
}

test('an accepted shared-policy result is retained for operator review', () => {
  const value = {name: 'Fixture', notes: 'Unconfirmed device'};
  const result = step({action: 'ACCEPT', value});
  assert.equal(result.state.draft, value);
  assert.equal(result.command.tool, 'todo_write');
  assert.equal(result.command.arguments.ops[0].status, 'done');
});

test('summarisation uses its journaled command without restarting the profiler', () => {
  const checkpoint = {original: 'paid response', action: 'SUMMARISE'};
  const command = {tool: 'agent_run', arguments: {agent: 'privacy_output_recovery',
    task: 'Shorten only the supplied fields', recovery: true}};
  const result = step({action: 'SUMMARISE', checkpoint, command});
  assert.equal(result.command, command);
  assert.equal(result.state.recovery, checkpoint);
  assert.equal(result.state.delegated, true);
});

test('exhausted repair requests review without completing the stage or saving a profile', () => {
  const result = step({action: 'NEEDS_REVIEW', reason: 'Word budget still exceeded'});
  assert.equal(result.command.tool, 'orchestration_ask');
  assert.match(result.command.arguments.question, /No profile has been saved/);
  assert.equal(result.state.review, 'Word budget still exceeded');
  assert.throws(() => step({action: 'STOP', reason: 'Host refusal'}), /Host refusal/);
});
