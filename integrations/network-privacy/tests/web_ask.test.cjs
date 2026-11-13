'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

// The Ask tab, its attention items and its Settings group run in the shipped
// script against a small DOM boundary. Sample data only.
function dashboard() {
  const nodes = new Map();
  function node(id) {
    if (!nodes.has(id)) nodes.set(id, {id, value:'', textContent:'', hidden:false, open:false, disabled:false, className:'', classList:{toggle() {}}, listeners:{}, setAttribute() {}, focus() {}, scrollIntoView() {}, addEventListener(name,handler) {this.listeners[name] = handler;}, children:[], append(...children) {this.children.push(...children);}, replaceChildren(...children) {this.children = children;}, querySelector() {return null;}, querySelectorAll() {return [];}, reset() {}});
    return nodes.get(id);
  }
  const create = tag => ({tag, children:[], textContent:'', listeners:{}, isConnected:true, focus() {}, append(...children) {this.children.push(...children);}, replaceChildren(...children) {this.children = children;}, addEventListener(name, handler) {this.listeners[name] = handler;}});
  const context = vm.createContext({
    document:{getElementById:node, createElement:create},
    location:{hash:''}, URLSearchParams, CSS:{escape:value=>value}, PrivacyMarkdown:{render:source=>({tag:'markdown', source, children:[]})},
    setInterval() {}, fetch:async () => ({ok:false, status:401}),
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/plowshare_privacy/web/app.js'), 'utf8'), context);
  const run = (code, values = {}) => {Object.assign(context, values); return vm.runInContext(code, context);};
  return {run, node, context};
}
const texts = node => [node.textContent, node.source, ...(node.children || []).flatMap(texts)].filter(Boolean);
const find = (node, test_) => [...(test_(node) ? [node] : []), ...(node.children || []).flatMap(child => find(child, test_))];
const buttons = node => find(node, item => item.tag === 'button');
const everyKey = node => [...Object.keys(node), ...(node.children || []).flatMap(everyKey)];

const WHEN = '2026-11-12T10:00:00Z';
function entry(index, author, text, kind, extra = {}) {
  return {index, author, text, at:WHEN, kind, calls:[], answers:null, tool:null, reference:null, sender:null, job:null, ...extra};
}
function askView(overrides = {}) {
  return {available:true, conversation:'c1', turn_state:'idle', entries:[], waiting:[], done:[], paused:false, unread:false, detail:null, ...overrides};
}
function show(run, view) {run('askConversation = view; renderAsk()', {view});}

test('the Ask tab never renders reply HTML and folds JSON', () => {
  const {run, node} = dashboard();
  const hostile = '<img src=x onerror=alert(1)> **bold**';
  const json = '{"status":"requested","specialist":"security"}';
  show(run, askView({turn_state:'answered', entries:[
    entry(0, 'user', 'What changed overnight?', 'question'),
    entry(1, 'agent', hostile, 'answer'),
    entry(2, 'tool', 'TOOL RESULT SECRET', 'tool_result', {answers:'call-1', tool:'send_message'}),
    entry(3, 'agent', json, 'answer'),
  ]}));
  const log = node('ask-conversation');
  // Reply text reaches the page only through the Markdown renderer.
  const rendered = find(log, item => item.tag === 'markdown').map(item => item.source);
  assert.deepEqual([...rendered], [hostile]);
  assert.ok(!everyKey(log).includes('innerHTML'));
  // A tool entry never shows as an answer.
  assert.ok(!texts(log).some(line => line.includes('TOOL RESULT SECRET')));
  // A JSON-looking reply is folded under Details and not rendered as Markdown.
  const folds = find(log, item => item.tag === 'details');
  assert.equal(folds.length, 1);
  assert.match(texts(folds[0])[0], /^Details/);
  assert.ok(texts(folds[0]).some(line => line.includes('"specialist"')));
  assert.ok(!rendered.includes(json));
  // The user's own words are text, not Markdown.
  assert.ok(texts(log).includes('What changed overnight?'));
});

test('an unchanged reading is not rebuilt', () => {
  const {run, node} = dashboard();
  const view = askView({entries:[entry(0, 'user', 'Hello', 'question')]});
  show(run, view);
  const first = node('ask-conversation').children;
  show(run, JSON.parse(JSON.stringify(view)));
  assert.equal(node('ask-conversation').children, first);
  show(run, askView({entries:[...view.entries, entry(1, 'agent', 'Hi', 'answer')]}));
  assert.notEqual(node('ask-conversation').children, first);
});

test('sending waits while a turn runs but the box stays open for a draft', () => {
  const {run, node} = dashboard();
  for (const state of ['sending', 'running']) {
    show(run, askView({turn_state:state}));
    assert.equal(node('ask-input').disabled, false, state);
    assert.equal(node('ask-send').disabled, true, state);
    // The working row says it is working; the hint is kept for problems.
    assert.equal(node('ask-hint').textContent, '');
    assert.equal(node('ask-working').hidden, false, state);
    assert.equal(node('ask-state').textContent, state === 'sending' ? 'Sending' : 'Working');
  }
  show(run, askView({turn_state:'unconfirmed', detail:'Internal journal wording'}));
  assert.equal(node('ask-input').disabled, false);
  assert.equal(node('ask-send').disabled, false);
  assert.equal(node('ask-hint').textContent, 'Not confirmed as sent. Ask again if you still need it.');
  show(run, askView({turn_state:'answered'}));
  assert.equal(node('ask-input').disabled, false);
  assert.equal(node('ask-hint').textContent, '');
  assert.equal(node('ask-working').hidden, true);
  assert.equal(node('ask-state').textContent, 'Ready');
  // The service's own account of a stop is shown; a failure is an error.
  show(run, askView({turn_state:'failed', detail:'The question was not sent to Plowshare.'}));
  assert.equal(node('ask-hint').textContent, 'The question was not sent to Plowshare.');
  assert.match(node('ask-hint').className, /notice error/);
  // An unavailable journal cannot take a question.
  show(run, askView({available:false, detail:'The Ask journal needs inspection'}));
  assert.equal(node('ask-input').disabled, true);
  assert.equal(node('ask-hint').textContent, 'The Ask journal needs inspection');
});

test('a question is sent once, kept on refusal and cleared on success', async () => {
  const {run, node} = dashboard();
  const sent = [];
  run('api = async (path, options) => {sent.push([path, JSON.parse(options.body)]); if (fail) throw new Error("Ask is busy"); return reply;}', {sent, fail:true, reply:askView({turn_state:'running'})});
  node('ask-input').value = 'Is the camera talking to anyone new?';
  const submit = {prevented:false, preventDefault() {this.prevented = true;}};
  await node('ask-form').listeners.submit(submit);
  assert.equal(submit.prevented, true);
  assert.deepEqual(JSON.parse(JSON.stringify(sent)), [['/api/ask', {question:'Is the camera talking to anyone new?'}]]);
  // A refused question goes back in the box and leaves no bubble behind.
  assert.equal(node('ask-input').value, 'Is the camera talking to anyone new?');
  assert.equal(node('ask-send').disabled, false);
  assert.ok(!find(node('ask-conversation'), item => item.className === 'ask-entry ask-question ask-pending').length);
  run('fail = false');
  await node('ask-form').listeners.submit(submit);
  assert.equal(node('ask-input').value, '');
  // The reply is the new reading: sending waits for the answer, drafting does not.
  assert.equal(node('ask-send').disabled, true);
  assert.equal(node('ask-input').disabled, false);
  // A blank question is never sent.
  sent.length = 0;
  node('ask-input').value = '   ';
  await node('ask-form').listeners.submit(submit);
  assert.equal(sent.length, 0);
});

test('the side column lists waiting and done work in household words', () => {
  const {run, node} = dashboard();
  show(run, askView({
    waiting:[
      {kind:'message', target:'privacy_analyst', about:'Check the Driveway camera firmware', since:WHEN},
      {kind:'message', target:'privacy_reviewer', about:'Second look', since:''},
      {kind:'message', target:'privacy_coordinator', about:'Plan', since:WHEN},
      {kind:'message', target:'new_safety_helper', about:'Odd one', since:WHEN},
    ],
    done:[
      {kind:'orchestration', target:'investigate_network', about:'Look at the TV lookups', since:WHEN},
      {kind:'orchestration', target:'orchestrate_deep_dive', about:'Older look', since:WHEN},
    ],
  }));
  const waiting = node('ask-waiting'), done = node('ask-done');
  const when = run('formatWhen(when)', {when:WHEN});
  const heads = find(waiting, item => item.tag === 'h4').map(item => item.textContent);
  assert.deepEqual([...heads], ['The analyst', 'The reviewer', 'The coordinator', 'New safety helper']);
  assert.deepEqual(find(done, item => item.tag === 'h4').map(item => item.textContent), ['Network investigation', 'Deep dive']);
  for (const area of [waiting, done]) assert.ok(!texts(area).some(line => line.includes('_')));
  assert.ok(texts(waiting).includes('Check the Driveway camera firmware'));
  assert.ok(texts(waiting).some(line => line.includes(when)));
  assert.ok(texts(waiting).includes('Since: not recorded'));
  assert.ok(texts(done).includes('Look at the TV lookups'));
  // No report link: the report arrives in the conversation and under Findings.
  assert.equal(buttons(done).length, 0);
  show(run, askView());
  assert.deepEqual(texts(node('ask-waiting')), ['Nothing is waiting on anyone.']);
  assert.deepEqual(texts(node('ask-done')), ['Nothing has finished yet.']);
});

test('paused shows Top up and the attention item', async () => {
  const {run, node} = dashboard();
  show(run, askView({turn_state:'capped', paused:true, detail:'The assistant has used its allowance for now. Top up to let it continue.'}));
  const side = node('ask-pause');
  assert.equal(side.hidden, false);
  assert.ok(texts(side).includes('Paused: allowance used'));
  const top = buttons(side);
  assert.equal(top.length, 1);
  assert.equal(top[0].textContent, 'Top up');
  // Primary: no variant class. Top up spends model calls, so it confirms first.
  assert.ok(!top[0].className);
  const calls = [];
  run('api = async (path, options) => {calls.push([path, options.method]); return reply;}', {calls, reply:askView({turn_state:'answered'})});
  top[0].listeners.click();
  assert.equal(calls.length, 0);
  const confirm = buttons(find(side, item => item.className === 'actions confirm')[0]).find(button => button.textContent === 'Top up and continue');
  await confirm.listeners.click();
  assert.deepEqual(JSON.parse(JSON.stringify(calls)), [['/api/ask/top-up', 'POST']]);
  assert.equal(node('ask-pause').hidden, true);
  // A turn-cap stop is not a pause: no Top up, only the detail.
  show(run, askView({turn_state:'capped', paused:false, detail:'This request took too many steps and stopped. Ask again, more narrowly.'}));
  assert.equal(node('ask-pause').hidden, true);
  assert.equal(node('ask-hint').textContent, 'This request took too many steps and stopped. Ask again, more narrowly.');
});

function overview(ask) {
  return {devices:[], assessments:null, health:{connected:true, detail:'', recovery:[], pihole:'Configured', unifi:'Configured'},
    quick_presence:{enabled:true}, system:{pihole:null, unifi:null, advisory_origin:null, deployment:{credentials:[]}}, ask};
}
const titles = node => find(node, item => item.tag === 'h3').map(item => item.textContent);

test('an unread answer badges the tab and adds the attention item', () => {
  const {run, node} = dashboard();
  run('latestStatus = {ask_available:true}; overview = value; renderAskBadge(value.ask); renderAttention(value)', {value:overview({unread:true, paused:false, turn_state:'answered', detail:null})});
  assert.equal(node('ask-badge').hidden, false);
  assert.deepEqual([...titles(node('attention-items'))], ['Privacy Watch has an answer']);
  const item = node('attention-items').children[0];
  buttons(item)[0].listeners.click();
  assert.equal(run('currentView'), 'ask');
  // Nothing unread: no badge, no item.
  run('renderAskBadge(value.ask); renderAttention(value)', {value:overview({unread:false, paused:false, turn_state:'answered', detail:null})});
  assert.equal(node('ask-badge').hidden, true);
  assert.deepEqual([...titles(node('attention-items'))], []);
  // No Ask configured at all.
  run('renderAskBadge(value.ask); renderAttention(value)', {value:overview(null)});
  assert.equal(node('ask-badge').hidden, true);
  assert.deepEqual([...titles(node('attention-items'))], []);
});

test('a paused assistant adds its own attention item that opens Ask', () => {
  const {run, node} = dashboard();
  run('renderAttention(value)', {value:overview({unread:false, paused:true, turn_state:'capped', detail:'The assistant has used its allowance for now. Top up to let it continue.'})});
  assert.deepEqual([...titles(node('attention-items'))], ['Privacy Watch is paused']);
  buttons(node('attention-items').children[0])[0].listeners.click();
  assert.equal(run('currentView'), 'ask');
});

test('the conversation is read only while the Ask tab is open', async () => {
  const {run} = dashboard();
  const gets = [];
  run('api = async path => {gets.push(path); return reply;}', {gets, reply:askView({unread:false, entries:[entry(0, 'agent', 'Hi', 'answer')]})});
  run('overview = value', {value:overview({unread:true, paused:false, turn_state:'running', detail:null})});
  await run('readAsk()');
  assert.equal(gets.length, 0);
  run("showView('ask')");
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual([...gets].includes('/api/ask'), true);
  // Reading marks the answer read, so the badge and attention item go.
  assert.equal(run('overview.ask.unread'), false);
  // Without Ask configured there is nothing to read.
  gets.length = 0;
  run('overview = value', {value:overview(null)});
  await run('readAsk()');
  assert.equal(gets.length, 0);
});

test('Settings carries the Ask limits', async () => {
  const {run, node} = dashboard();
  const saved = [];
  run('api = async (path, options = {}) => {if (options.method === "POST") {saved.push([path, JSON.parse(options.body)]); return JSON.parse(options.body);} return {max_model_calls:40, max_turns:30, top_up_calls:20};}', {saved});
  await run('loadAskSettings()');
  assert.equal(node('ask-max-calls').value, '40');
  assert.equal(node('ask-max-turns').value, '30');
  assert.equal(node('ask-top-up').value, '20');
  node('ask-max-calls').value = '60'; node('ask-max-turns').value = '25'; node('ask-top-up').value = '10';
  await node('ask-settings').listeners.submit({preventDefault() {}});
  assert.deepEqual(JSON.parse(JSON.stringify(saved)), [['/api/ask/settings', {max_model_calls:60, max_turns:25, top_up_calls:10}]]);
  // A value outside 1 to 200 is refused before it is sent.
  saved.length = 0;
  node('ask-top-up').value = '0';
  await node('ask-settings').listeners.submit({preventDefault() {}});
  assert.equal(saved.length, 0);
  assert.match(node('ask-settings-detail').textContent, /1 to 200/);
  node('ask-top-up').value = '10'; node('ask-max-turns').value = '2.5';
  await node('ask-settings').listeners.submit({preventDefault() {}});
  assert.equal(saved.length, 0);
});

test('disconnect clears Ask state', async () => {
  const {run, node} = dashboard();
  show(run, askView({unread:true, turn_state:'capped', paused:true, entries:[entry(0, 'user', 'Private question', 'question')], waiting:[{kind:'message', target:'x', about:'y', since:WHEN}]}));
  node('ask-input').value = 'half-typed';
  node('ask-badge').hidden = false;
  node('tab-ask').hidden = false;
  run('askSettingsLoaded = true; askSending = true; overview = value', {value:overview({unread:true, paused:true, turn_state:'capped', detail:null})});
  run('api = async () => ({})');
  await node('disconnect').listeners.click();
  assert.equal(run('askConversation'), null);
  assert.equal(run('askSettingsLoaded'), false);
  assert.equal(run('askSending'), false);
  assert.equal(node('ask-conversation').children.length, 0);
  assert.equal(node('ask-waiting').children.length, 0);
  assert.equal(node('ask-done').children.length, 0);
  assert.equal(node('ask-input').value, '');
  assert.equal(node('ask-badge').hidden, true);
  assert.equal(node('tab-ask').hidden, true);
  assert.equal(node('ask-pause').hidden, true);
  assert.equal(node('ask-group').hidden, true);
  assert.ok(!texts(node('ask-conversation')).some(line => line.includes('Private question')));
});

test('fenced or prefixed specialist JSON folds and specialists are named', () => {
  const {run, node} = dashboard();
  const fenced = '```json\n{"verdict":"fine"}\n```';
  const prefixed = 'Result: {"verdict":"fine"}';
  const words = 'The camera looks normal.';
  show(run, askView({turn_state:'answered', entries:[
    entry(0, 'agent', fenced, 'message', {sender:'privacy_analyst'}),
    entry(1, 'agent', prefixed, 'message', {sender:'privacy_reviewer'}),
    entry(2, 'agent', words, 'message', {sender:'privacy_coordinator'}),
  ]}));
  const log = node('ask-conversation');
  const folds = find(log, item => item.tag === 'details');
  assert.equal(folds.length, 2);
  for (const fold of folds) assert.equal(texts(fold)[0], 'Details · specialist reply');
  const rendered = find(log, item => item.tag === 'markdown').map(item => item.source);
  assert.deepEqual([...rendered], [words]);
  const heads = texts(log);
  for (const line of ['The analyst replied', 'The reviewer replied', 'The coordinator replied']) assert.ok(heads.includes(line), line);
  assert.ok(!heads.some(line => line.includes('privacy_')));
});

test('a running turn shows the reading error instead of still working', () => {
  const {run, node} = dashboard();
  const detail = 'The Ask conversation could not be read (TransportError). Your questions were kept.';
  show(run, askView({turn_state:'running', detail}));
  assert.equal(node('ask-hint').textContent, detail);
  show(run, askView({turn_state:'running', detail:null}));
  assert.equal(node('ask-hint').textContent, '');
  assert.equal(node('ask-working').hidden, false);
});

test('an unavailable Ask explains itself instead of reading', () => {
  const {run, node} = dashboard();
  run('latestStatus = {ask_available:false, ask_detail:"Ask needs the operator store."}; askConversation = null; renderAsk()');
  assert.deepEqual(texts(node('ask-conversation')), ['Ask needs the operator store.']);
  run('latestStatus = {ask_available:true}; askConversation = null; renderAsk()');
  assert.deepEqual(texts(node('ask-conversation')), ['Reading the conversation…']);
});

test('the step setting is labelled Steps per request', () => {
  const html = fs.readFileSync(path.join(__dirname, '../src/plowshare_privacy/web/index.html'), 'utf8');
  assert.match(html, /<label>Steps per request<input id="ask-max-turns"/);
  assert.ok(!html.includes('<label>Turns<'));
  assert.match(html, /runaway guard/);
});

test('the question shows at once while it is being sent', async () => {
  const {run, node} = dashboard();
  let release;
  run('api = async () => new Promise(resolve => {release = resolve;})', {});
  run('askConversation = view', {view:askView({entries:[entry(0, 'agent', 'Earlier answer', 'answer')]})});
  node('ask-input').value = 'Which device is new?';
  const sending = node('ask-form').listeners.submit({preventDefault() {}});
  const log = node('ask-conversation');
  const pending = find(log, item => item.className === 'ask-entry ask-question ask-pending');
  assert.equal(pending.length, 1);
  assert.ok(texts(pending[0]).includes('Which device is new?'));
  assert.ok(texts(pending[0]).includes('Sending…'));
  assert.equal(node('ask-input').value, '');
  assert.equal(node('ask-working').hidden, false);
  assert.equal(node('ask-working-text').textContent, 'Sending your question');
  run('release(reply)', {reply:askView({turn_state:'running', entries:[entry(0, 'agent', 'Earlier answer', 'answer'), entry(1, 'user', 'Which device is new?', 'question')]})});
  await sending;
  assert.equal(find(node('ask-conversation'), item => item.className === 'ask-entry ask-question ask-pending').length, 0);
  assert.equal(node('ask-working-text').textContent, 'Thinking');
});

test('the working row names the step in hand in household words', () => {
  const {run, node} = dashboard();
  const question = entry(0, 'user', 'Is anything exposed?', 'question');
  const devices = entry(1, 'agent', '', 'answer', {calls:[{id:'c1', tool:'network_devices', target:null, about:null, reply:false}]});
  show(run, askView({turn_state:'running', entries:[question, devices]}));
  assert.equal(node('ask-working-text').textContent, 'Reading the device list');
  const result = entry(2, 'tool', '{}', 'tool_result', {answers:'c1', tool:'network_devices'});
  show(run, askView({turn_state:'running', entries:[question, devices, result]}));
  assert.equal(node('ask-working-text').textContent, 'Weighing what it found');
  const handOff = entry(3, 'agent', '', 'answer', {calls:[{id:'c2', tool:'send_message', target:'privacy_analyst', about:'Look', reply:false}]});
  show(run, askView({turn_state:'running', entries:[question, devices, result, handOff]}));
  assert.equal(node('ask-working-text').textContent, 'Asking the analyst');
  const other = entry(4, 'agent', '', 'answer', {calls:[{id:'c3', tool:'information_search', target:null, about:null, reply:false}]});
  show(run, askView({turn_state:'running', entries:[question, other]}));
  assert.equal(node('ask-working-text').textContent, 'Using information search');
  // The timer counts from the question.
  assert.match(node('ask-working-time').textContent, /^\d+:\d\d$/);
});

test('Enter sends, Shift+Enter does not, and nothing sends while a turn runs', () => {
  const {run, node} = dashboard();
  let submitted = 0;
  node('ask-form').requestSubmit = () => {submitted++;};
  show(run, askView());
  const key = (extra = {}) => {const event = {key:'Enter', shiftKey:false, isComposing:false, prevented:false, preventDefault() {this.prevented = true;}, ...extra}; node('ask-input').listeners.keydown(event); return event;};
  assert.equal(key().prevented, true);
  assert.equal(submitted, 1);
  assert.equal(key({shiftKey:true}).prevented, false);
  assert.equal(key({isComposing:true}).prevented, false);
  assert.equal(submitted, 1);
  show(run, askView({turn_state:'running'}));
  key();
  assert.equal(submitted, 1);
});

test('a running turn is followed faster only while the tab is open', async () => {
  const {run} = dashboard();
  const gets = [];
  run('api = async path => {gets.push(path); return reply;}', {gets, reply:askView({turn_state:'running'})});
  run('authenticated = true; overview = value; askConversation = running', {value:overview({unread:false, paused:false, turn_state:'running', detail:null}), running:askView({turn_state:'running'})});
  run('followAsk()');
  assert.equal(gets.length, 0);
  run("currentView = 'ask'; askReadAt = 0; followAsk()");
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(gets.length, 1);
  // Just read: the next tick waits.
  run('followAsk()');
  assert.equal(gets.length, 1);
  // An idle conversation is not read by the tick.
  run('askConversation = idle; askReadAt = 0; followAsk()', {idle:askView()});
  assert.equal(gets.length, 1);
});

test('a reply that lands while reading earlier answers offers a jump instead of scrolling', () => {
  const {run, node} = dashboard();
  const log = node('ask-conversation');
  show(run, askView({entries:[entry(0, 'user', 'Hi', 'question')]}));
  Object.assign(log, {scrollHeight:2000, clientHeight:400, scrollTop:100});
  show(run, askView({entries:[entry(0, 'user', 'Hi', 'question'), entry(1, 'agent', 'Hello', 'answer')]}));
  assert.equal(node('ask-jump').hidden, false);
  assert.equal(log.scrollTop, 100);
  node('ask-jump').listeners.click();
  assert.equal(log.scrollTop, 2000);
  assert.equal(node('ask-jump').hidden, true);
});
