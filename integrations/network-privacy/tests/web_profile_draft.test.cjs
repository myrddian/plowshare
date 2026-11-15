const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../network-privacy-watch/orchestrations/profile_network_device.js'), 'utf8');
const context = vm.createContext({llmJson: {parse(raw) {
  return {pass: true, value: JSON.parse(raw)};
}}});
vm.runInContext(source.replace(/^export /gm, '') + '\nglobalThis.runStep = step;', context);

const draft = () => ({name: 'Fixture device', model_hypothesis: '', purpose: '',
  expected_services: '', notes: '', questions: ''});
function validate(value) {
  return context.runStep({todos: [{stageId: 'draft', id: 'fixture', status: 'in_progress'}],
    state: {delegated: true}, result: JSON.stringify(value)});
}

test('profile draft fields count words and preserve valid text beyond old character limits', () => {
  for (const [key, limit] of Object.entries({name: 128, model_hypothesis: 128,
    purpose: 512, expected_services: 1024, notes: 1024, questions: 512})) {
    const value = {...draft(), [key]: 'word '.repeat(limit)};
    assert.equal(validate(value).state.draft[key], value[key]);
    assert.throws(() => validate({...value, [key]: value[key] + 'extra'}),
      new RegExp('Invalid profile draft field ' + key));
  }
});

test('profile draft counters handle Unicode whitespace consistently', () => {
  const value = {...draft(), model_hypothesis: 'word\t\n\u00a0\u0085\ufeff'.repeat(128)};
  assert.equal(validate(value).state.draft.model_hypothesis, value.model_hypothesis);
  assert.throws(() => validate({...value, name: '\ufeff'}), /Invalid profile draft field name/);
});

test('word budgets retain byte, type, control-character and markup boundaries', () => {
  assert.throws(() => validate({...draft(), notes: 'é'.repeat(40000)}), /response size/);
  for (const notes of [null, 'bad\x01text', '<tool_call>untrusted']) {
    assert.throws(() => validate({...draft(), notes}), /Invalid profile draft field notes/);
  }
});
