'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

// The attention band, device-list assessment state and System health run in
// the shipped script against a small DOM boundary. Sample data only.
function dashboard() {
  const nodes = new Map();
  function node(id) {
    if (!nodes.has(id)) nodes.set(id, {id, value:'', textContent:'', hidden:false, open:false, disabled:false, classes:new Set(), classList:{toggle(name, on) {const set = nodes.get(id).classes; if (on) set.add(name); else set.delete(name);}}, listeners:{}, attributes:{}, setAttribute(name,value) {this.attributes[name] = value;}, focus() {}, scrollIntoView() {}, addEventListener(name,handler) {this.listeners[name] = handler;}, children:[], append(...children) {this.children.push(...children);}, replaceChildren(...children) {this.children = children;}, querySelector() {return null;}, querySelectorAll() {return [];}, reset() {}});
    return nodes.get(id);
  }
  const create = tag => ({tag, children:[], textContent:'', listeners:{}, append(...children) {this.children.push(...children);}, addEventListener(name, handler) {this.listeners[name] = handler;}});
  const context = vm.createContext({
    document:{getElementById:node, createElement:create},
    location:{hash:''}, URLSearchParams, CSS:{escape:value=>value}, PrivacyMarkdown:{render:source=>({source})},
    setInterval() {}, fetch:async () => ({ok:false, status:401}),
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/plowshare_privacy/web/app.js'), 'utf8'), context);
  return {context, node};
}
const texts = node => [node.textContent, ...(node.children || []).flatMap(texts)].filter(Boolean);

const TV = 'mac:02:00:00:00:00:01', CAMERA = 'mac:02:00:00:00:00:02', GONE = 'mac:02:00:00:00:00:09';
function device(name, address, deviceId) {
  return {name, address, identity:{mac:deviceId.slice(4), device_id:deviceId}};
}
function summary(deviceId, state, potential_breaches = 0) {
  return {device_id:deviceId, state, pending:null, processing:false, potential_breaches, worth_checking:0, requested_at:null};
}
function credential(purpose, variable, present) {return {purpose, source:'environment', variable, present};}
function system(overrides = {}) {
  return {pihole:null, unifi:null, advisory_origin:null, deployment:{server_origin:'https://plowshare.example', project:'home', collector:'home-collector',
    request_topic:'privacy.scan.requested', result_topic:'privacy.scan.completed', group:'collectors',
    probe_timeout_seconds:1.5, probe_concurrency:32, credentials:[credential('Plowshare service credential', 'PLOWSHARE_TOKEN', true)]}, ...overrides};
}
function overview(summaries, extra = {}) {
  return {devices:[device('Living room TV', '192.0.2.10', TV), device('Driveway camera', '192.0.2.11', CAMERA)],
    assessments:{summaries, usage:{background_limit:2, used_today:1, daily_ceiling:12}},
    health:{connected:true, detail:'', recovery:[], pihole:'Configured', unifi:'Configured'},
    quick_presence:{enabled:true}, system:system(), ...extra};
}
function load(context, value) {
  context.value = value;
  vm.runInContext('assessmentIndex = new Map(value.assessments.summaries.map(item => [item.device_id, item])); renderAttention(value);', context);
}

test('the attention band puts breaches and reviews first, for monitored devices only', () => {
  const {context, node} = dashboard();
  // A ledger entry for a device that left monitoring must not inflate a count.
  load(context, overview([summary(TV, 'review', 2), summary(CAMERA, 'failed'), summary(GONE, 'review', 5)]));
  const items = node('attention-items').children;
  assert.equal(node('attention').hidden, false);
  assert.equal(node('attention-clear').hidden, true);
  const lines = items.map(item => texts(item).join(' | '));
  assert.match(lines[0], /^2 \| Potential breaches \| Reported for Living room TV\./);
  assert.match(lines[1], /^1 \| Assessment awaiting your review \| Living room TV\./);
  assert.match(lines[2], /^1 \| Assessment failed \| Driveway camera\./);
  assert.equal(items.length, 3);
  assert.match(lines[0], /Open Living room TV$/);
});

test('the attention band clears itself when nothing needs the operator', () => {
  const {context, node} = dashboard();
  load(context, overview([summary(TV, 'accepted'), summary(CAMERA, 'running')]));
  assert.equal(node('attention').hidden, true);
  assert.equal(node('attention-clear').hidden, false);
  assert.deepEqual(node('attention-items').children, []);
});

test('missing credentials and connection problems join the band and open System health once', () => {
  const {context, node} = dashboard();
  const value = overview([], {system:system({deployment:{...system().deployment,
    credentials:[credential('Plowshare service credential', 'PLOWSHARE_TOKEN', true), credential('UniFi API key', 'UNIFI_KEY', false)]}})});
  value.health = {...value.health, connected:false, detail:'Plowshare checks are unavailable.'};
  load(context, value);
  const lines = node('attention-items').children.map(item => texts(item).join(' | '));
  assert.match(lines[0], /Plowshare connection \| Plowshare checks are unavailable/);
  assert.match(lines[1], /^1 \| Credential missing \| UniFi API key\./);
  context.value = value;
  vm.runInContext('renderSystem(value.system, value.health)', context);
  const deployment = node('health-deployment').children;
  const missing = deployment.find(item => item.textContent === 'Environment variable UNIFI_KEY · missing');
  assert.equal(missing.className, 'missing');
  assert.equal(node('system-health').open, true);
  // The operator may close it again; a later refresh does not reopen it.
  node('system-health').open = false;
  vm.runInContext('renderSystem(value.system, value.health)', context);
  assert.equal(node('system-health').open, false);
  // No secret value is ever part of the view.
  assert.doesNotMatch(JSON.stringify(deployment.map(item => item.textContent)), /secret/);
});

test('the device list orders breaches, then reviews, then failures, and tags each', () => {
  const {context} = dashboard();
  const value = overview([summary(TV, 'accepted', 1), summary(CAMERA, 'review')]);
  value.devices.push(device('Lamp', '192.0.2.12', 'mac:02:00:00:00:00:03'));
  load(context, value);
  const order = vm.runInContext('[...value.devices].sort(inventoryOrders.attention).map(item => item.name)', context);
  assert.deepEqual([...order], ['Driveway camera', 'Living room TV', 'Lamp']);
  const tags = vm.runInContext('assessmentTags(assessmentOf(value.devices[0])).map(tag => [tag.textContent, tag.className])', context);
  assert.deepEqual(JSON.parse(JSON.stringify(tags)), [['1 potential breach', 'tag tag-breach']]);
  const review = vm.runInContext('assessmentTags(assessmentOf(value.devices[1])).map(tag => tag.textContent)', context);
  assert.deepEqual([...review], ['Awaiting your review']);
  assert.deepEqual([...vm.runInContext('assessmentTags(assessmentOf(value.devices[2]))', context)], []);
});

test('a missing byte counter names its configured cause', () => {
  const {context} = dashboard();
  const gap = value => {context.gapValue = value; return vm.runInContext('trafficGap(gapValue)', context);};
  assert.match(gap({system:system(), quick_presence:{enabled:true}}), /UniFi is not connected/);
  const unifi = {controller:'https://192.0.2.1', site:'s', trust:'system', api_key:credential('UniFi API key', 'K', true), traffic_site:null};
  assert.match(gap({system:system({unifi}), quick_presence:{enabled:true}}), /no trafficSite/);
  assert.match(gap({system:system({unifi:{...unifi, traffic_site:'default'}}), quick_presence:{enabled:false}}), /quick presence checks, which are off/);
  assert.match(gap({system:system({unifi:{...unifi, traffic_site:'default'}}), quick_presence:{enabled:true}}), /arrive with the next quick presence checks/);
});

test('Settings summarises background limits and today’s use', () => {
  const {context, node} = dashboard();
  context.value = overview([]);
  vm.runInContext("latestStatus = {profile_research_available:true, profile_research_enabled:true, profile_research_used_today:3, profile_research_daily_limit:4, profile_drafts_waiting:1}; renderBackgroundUsage(value);", context);
  assert.equal(node('background-work').hidden, false);
  const facts = node('background-usage').children.map(item => item.textContent);
  assert.deepEqual(facts, ['Profile research', 'On', 'Research used today', '3 of 4', 'Drafts to review', '1',
    'Background assessments', 'Up to 2 a day', 'Assessments used today', '1 of 12, including those you started']);
});
