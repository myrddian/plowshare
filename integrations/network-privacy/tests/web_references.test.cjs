'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

// The reference library list and its attention item run in the shipped script
// against a small DOM boundary. Sample data only.
function dashboard(fetch = async () => ({ok:false, status:401})) {
  const nodes = new Map();
  function node(id) {
    if (!nodes.has(id)) nodes.set(id, {id, value:'', textContent:'', hidden:false, open:false, disabled:false, classList:{toggle() {}}, listeners:{}, setAttribute() {}, focus() {}, scrollIntoView() {}, addEventListener(name,handler) {this.listeners[name] = handler;}, children:[], append(...children) {this.children.push(...children);}, replaceChildren(...children) {this.children = children;}, querySelector() {return null;}, querySelectorAll() {return [];}, reset() {}});
    return nodes.get(id);
  }
  const create = tag => ({tag, children:[], textContent:'', className:'', listeners:{}, append(...children) {this.children.push(...children);}, replaceChildren(...children) {this.children = children;}, addEventListener(name, handler) {this.listeners[name] = handler;}});
  const context = vm.createContext({
    document:{getElementById:node, createElement:create},
    location:{hash:''}, URLSearchParams, CSS:{escape:value=>value}, PrivacyMarkdown:{render:source=>({source})},
    setInterval() {}, fetch,
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/plowshare_privacy/web/app.js'), 'utf8'), context);
  return {context, node, run:code => vm.runInContext(code, context)};
}
const texts = node => [node.textContent, ...(node.children || []).flatMap(texts)].filter(Boolean);
const buttons = node => [...(node.tag === 'button' ? [node] : []), ...(node.children || []).flatMap(buttons)];

const URL = 'https://docs.example.test/admin-api';
function overview(references) {
  return {devices:[], assessments:{summaries:[], usage:{background_limit:2, used_today:0, daily_ceiling:12}},
    health:{connected:true, detail:'', recovery:[], pihole:'Configured', unifi:'Configured'},
    quick_presence:{enabled:true}, system:{deployment:{credentials:[]}}, references};
}
function row(phase, extra = {}) {
  return {url:URL, product:'caddy', title:'Caddy admin API', origin:'seed', phase, label:phase, error:null, retired:false, untag_failed:false, ...extra};
}

test('attention shows reference failures', () => {
  const {context, node, run} = dashboard();
  const band = value => {context.value = value; run('renderAttention(value)'); return node('attention-items').children.map(item => texts(item).join(' | '));};
  assert.deepEqual(band(overview({failed:0, blocked:0})), []);
  assert.deepEqual(band(overview(null)), []);
  let lines = band(overview({failed:1, blocked:1}));
  assert.equal(lines.length, 1);
  assert.match(lines[0], /^2 \| Reference docs need a check \|/);
  assert.match(lines[0], /Open Settings$/);
  lines = band(overview({failed:0, blocked:1}));
  assert.match(lines[0], /^1 \| Reference docs need a check/);
});

test('reference rows never render server text as markup and retry only failed or blocked rows', () => {
  const {node, run} = dashboard();
  run("renderReferences({references:[" + [row('failed', {error:'<b>bad</b>'}), row('tagged'), row('blocked')].map(JSON.stringify).join(',') + "], error:'Waiting <i>'})");
  const list = node('reference-list');
  assert.equal(list.children.length, 3);
  assert.ok(texts(list).includes('<b>bad</b>'));
  assert.deepEqual(list.children.map(item => buttons(item).length), [1, 0, 1]);
  assert.equal(node('reference-detail').textContent, 'Waiting <i>');
});

test('retry button posts url', async () => {
  const calls = [];
  const {node, run} = dashboard(async (path, options = {}) => {
    calls.push([path, options.method, options.body]);
    return {ok:true, status:200, json:async () => ({references:[row('queued')], error:null})};
  });
  run("renderReferences({references:[" + JSON.stringify(row('failed')) + "], error:null})");
  const [retry] = buttons(node('reference-list').children[0]);
  retry.listeners.click();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls.find(call => call[0] === '/api/references/retry'), ['/api/references/retry', 'POST', JSON.stringify({url:URL})]);
  assert.equal(buttons(node('reference-list').children[0]).length, 0);
});

test('the list is fetched when Settings is shown, not on every overview refresh', async () => {
  const calls = [];
  const {context, run} = dashboard(async path => {
    calls.push(path);
    return {ok:true, status:200, json:async () => ({references:[row('tagged')], error:null})};
  });
  context.value = overview({failed:0, blocked:0});
  // An overview refresh on another tab only reports counts.
  run("overview = value; currentView = 'overview'; followReferences(value)");
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls.filter(path => path === '/api/references').length, 0);
  run("showView('settings')");
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls.filter(path => path === '/api/references').length, 1);
});
