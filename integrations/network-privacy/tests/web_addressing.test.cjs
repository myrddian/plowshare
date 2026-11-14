'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

// Address assignment in the device pane, the device list and the Evidence
// view, run in the shipped script against a small DOM boundary. Sample data only.
function dashboard() {
  const nodes = new Map();
  function node(id) {
    if (!nodes.has(id)) nodes.set(id, {id, value:'', textContent:'', hidden:false, open:false, disabled:false, classes:new Set(), classList:{toggle() {}, add() {}, remove() {}}, listeners:{}, attributes:{}, setAttribute(name,value) {this.attributes[name] = value;}, focus() {}, scrollIntoView() {}, addEventListener(name,handler) {this.listeners[name] = handler;}, children:[], append(...children) {this.children.push(...children);}, replaceChildren(...children) {this.children = children;}, querySelector() {return null;}, querySelectorAll() {return [];}, reset() {}});
    return nodes.get(id);
  }
  const create = tag => ({tag, children:[], textContent:'', dataset:{}, listeners:{}, attributes:{}, setAttribute(name, value) {this.attributes[name] = value;}, append(...children) {this.children.push(...children);}, addEventListener(name, handler) {this.listeners[name] = handler;}});
  const context = vm.createContext({
    document:{getElementById:node, createElement:create},
    location:{hash:''}, URLSearchParams, CSS:{escape:value=>value}, PrivacyMarkdown:{render:source=>({source})},
    setInterval() {}, fetch:async () => ({ok:false, status:401}),
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/plowshare_privacy/web/app.js'), 'utf8'), context);
  return {context, node};
}
const texts = node => [node.textContent, ...(node.children || []).flatMap(texts)].filter(Boolean);
const call = (context, source, value) => {context.value = value; return vm.runInContext(source, context);};

const TABLE = [
  ['dhcp_dynamic', 'dhcp_lease', 'Dynamic (DHCP lease)'],
  ['dhcp_dynamic', 'inside_dhcp_range', 'Dynamic (inside the DHCP range)'],
  ['dhcp_reserved', 'dhcp_reservation', 'Reserved (DHCP reservation)'],
  ['dhcp_reserved', 'controller_fixed_ip', 'Reserved (fixed IP in the controller)'],
  ['static', 'outside_dhcp_range', 'Static (outside the DHCP range)'],
  ['static', 'no_lease_or_reservation', 'Static inside the DHCP range (no lease or reservation)'],
  ['unknown', null, 'Unknown'],
];

test('addressing labels follow the mode and basis table', () => {
  const {context} = dashboard();
  for (const [mode, basis, label] of TABLE) {
    assert.equal(call(context, 'addressingLabel(value)', {mode, basis}), label);
  }
  assert.equal(call(context, 'addressingLabel(value)', {mode:'static', basis:'novel'}), 'Unknown');
  assert.equal(call(context, 'addressingLabel(value)', {mode:'novel'}), 'Unknown');
  assert.deepEqual(['dhcp_dynamic', 'dhcp_reserved', 'static', 'unknown', 'novel'].map(mode => call(context, 'addressingShort(value)', mode)),
    ['Dynamic', 'Reserved', 'Static', 'Unknown', 'Unknown']);
});

test('the pane shows assignment, lease end and change', () => {
  const {context} = dashboard();
  const section = call(context, 'renderAddressing(value)', {mode:'dhcp_dynamic', basis:'dhcp_lease', services:['pihole'],
    expires_at:'2026-11-14T04:02:45Z', observed_at:'2026-11-14T03:30:00Z', previous_mode:'dhcp_reserved',
    changed_at:'2026-11-14T03:10:11Z', changed_in_latest:true});
  const lines = texts(section);
  const when = value => vm.runInContext(`formatWhen(${JSON.stringify(value)})`, context);
  assert.equal(section.className, 'readout addressing');
  // The label is the Assignment fact beside it; the readout does not repeat it.
  assert.deepEqual(lines, ['Address assignment', 'Source: pihole', `Lease recorded to end ${when('2026-11-14T04:02:45Z')}`,
    `Recorded ${when('2026-11-14T03:30:00Z')}`, `Changed from Reserved to Dynamic ${when('2026-11-14T03:10:11Z')}`]);
  assert.ok(!lines.some(line => line.startsWith('Lease ends ')));
  // Nothing recorded means no lease line, no source and no change line.
  const bare = texts(call(context, 'renderAddressing(value)', {mode:'static', basis:'outside_dhcp_range', services:[],
    expires_at:null, observed_at:'2026-11-14T03:30:00Z', previous_mode:null, changed_at:null}));
  assert.deepEqual(bare, ['Address assignment', `Recorded ${when('2026-11-14T03:30:00Z')}`]);
});

test('no addressing renders nothing', () => {
  const {context} = dashboard();
  assert.equal(call(context, 'renderAddressing(value)', null), null);
});

test('the device pane carries an Assignment fact only when addressing is known', () => {
  const {context, node} = dashboard();
  const base = {name:'TV', address:'192.0.2.10', identity:null, last_known_identity:null, presence:null, detection:null,
    dns_activity:null, dns:[], traffic:null, services:[], history:[], last_response_at:null};
  const render = value => {context.value = value; vm.runInContext('renderDeviceView(value)', context);};
  render({...base, addressing:{mode:'dhcp_reserved', basis:'dhcp_reservation', services:['pihole'], expires_at:null, previous_mode:null, changed_at:null}});
  // The section belongs to the identity block, not the traffic readout.
  const identity = texts(node('device-identity'));
  assert.ok(identity.includes('Assignment'));
  assert.ok(identity.includes('Address assignment'));
  assert.ok(!texts(node('device-facts')).includes('Address assignment'));
  render({...base, addressing:null});
  const without = [...texts(node('device-identity')), ...texts(node('device-facts'))];
  assert.ok(!without.includes('Assignment'));
  assert.ok(!without.includes('Address assignment'));
});

test('the device list can be searched by assignment', () => {
  const {context, node} = dashboard();
  const device = (name, address, addressing) => ({name, address, identity:null, last_known_identity:null, detection:null, dns_activity:null,
    traffic:null, addressing});
  const value = {devices:[
    device('Static one', '192.0.2.10', {mode:'static', basis:'outside_dhcp_range'}),
    device('Inside range', '192.0.2.11', {mode:'static', basis:'no_lease_or_reservation'}),
    device('Plain', '192.0.2.12', null)], candidates:[]};
  context.value = value;
  vm.runInContext('assessmentIndex = new Map(); renderInventory(value)', context);
  const cards = node('device-cards').children.filter(card => card.tag === 'article');
  assert.ok(cards[0].dataset.search.includes('static'));
  assert.ok(!cards[2].dataset.search.includes('static'));
  const tagsOf = card => texts(card).filter(line => line === 'Static in DHCP range');
  assert.deepEqual(tagsOf(cards[0]), []);
  assert.deepEqual(tagsOf(cards[1]), ['Static in DHCP range']);
  assert.deepEqual(tagsOf(cards[2]), []);
  // Tokens the attention band's multi-device actions search for.
  assert.ok(cards[1].dataset.search.split(' ').includes('static-in-dhcp-range'));
  assert.ok(!cards[0].dataset.search.includes('static-in-dhcp-range'));
  assert.ok(!cards[0].dataset.search.includes('assignment-changed'));
});

test('a changed assignment is searchable by its token', () => {
  const {context, node} = dashboard();
  const changed = {mode:'static', basis:'outside_dhcp_range', previous_mode:'dhcp_dynamic', changed_at:'2026-11-14T03:10:11Z', changed_in_latest:true};
  context.value = {devices:[
    {name:'Moved', address:'192.0.2.10', identity:null, last_known_identity:null, detection:null, dns_activity:null, traffic:null, addressing:changed},
    {name:'Earlier', address:'192.0.2.11', identity:null, last_known_identity:null, detection:null, dns_activity:null, traffic:null,
      addressing:{...changed, changed_in_latest:false}}], candidates:[]};
  vm.runInContext('assessmentIndex = new Map(); renderInventory(value)', context);
  const cards = node('device-cards').children.filter(card => card.tag === 'article');
  assert.ok(cards[0].dataset.search.split(' ').includes('assignment-changed'));
  assert.ok(!cards[1].dataset.search.includes('assignment-changed'));
});

test('the Evidence view lists assignments', async () => {
  const {context, node} = dashboard();
  const evidence = addressing => ({evidence:{mode:'collected', snapshot:{observed_at:'2026-11-14T03:00:00Z', devices:[], addressing}, changes:[], issues:[]}, revision:'r1'});
  const show = async addressing => {
    context.fetch = async url => ({ok:true, status:200, json:async () => (String(url).startsWith('/api/scans/') ? evidence(addressing) : [])});
    await vm.runInContext("showEvidence('scan-1')", context);
    return texts(node('evidence'));
  };
  const lines = await show([{address:'192.0.2.10', mac:null, mode:'dhcp_reserved', basis:'dhcp_reservation', services:[], expires_at:null},
    {address:'192.0.2.11', mac:null, mode:'unknown', basis:null, services:[], expires_at:null}]);
  assert.ok(lines.includes('Address assignment'));
  assert.ok(lines.includes('192.0.2.10 · Reserved (DHCP reservation)'));
  assert.ok(lines.includes('192.0.2.11 · Unknown'));
  assert.ok(!(await show([])).includes('Address assignment'));
});

const modeDevice = (name, address, addressing) => ({name, address, identity:{mac:'02:00:00:00:00:' + address.slice(-2), device_id:'mac:' + address}, addressing});
const state = (mode, basis, extra = {}) => ({mode, basis, services:[], expires_at:null, observed_at:null, previous_mode:null, changed_at:null, changed_in_latest:false, ...extra});
const fullOverview = (devices, extra = {}) => ({devices, candidates:[], assessments:{summaries:[], usage:null},
  health:{connected:true, detail:'', recovery:[], pihole:'Configured', unifi:'Configured'}, quick_presence:{enabled:true}, system:null, ...extra});
const attention = (context, node, value) => {
  context.value = value;
  vm.runInContext('assessmentIndex = new Map(); renderAttention(value); renderAddressingSummary(value.devices)', context);
  return node('attention-items').children.map(item => texts(item));
};

test('the overview summarises assignment across devices', () => {
  const {context, node} = dashboard();
  context.value = [modeDevice('A', '192.0.2.10', state('dhcp_dynamic', 'dhcp_lease')), modeDevice('B', '192.0.2.11', state('dhcp_reserved', 'dhcp_reservation')),
    modeDevice('C', '192.0.2.12', state('static', 'outside_dhcp_range')), modeDevice('D', '192.0.2.13', null)];
  vm.runInContext('renderAddressingSummary(value)', context);
  assert.equal(node('addressing-summary').textContent, 'Address assignment (latest collection): 1 dynamic · 1 reserved · 1 static · 0 unknown · 1 not reported');
  assert.equal(node('addressing-summary').hidden, false);
  context.value = [modeDevice('A', '192.0.2.10', state('novel', null)), modeDevice('B', '192.0.2.11', state('unknown', null))];
  vm.runInContext('renderAddressingSummary(value)', context);
  assert.match(node('addressing-summary').textContent, /0 dynamic · 0 reserved · 0 static · 2 unknown · 0 not reported$/);
});

test('the summary is hidden when no device reports assignment', () => {
  const {context, node} = dashboard();
  context.value = [modeDevice('A', '192.0.2.10', null), modeDevice('B', '192.0.2.11', null)];
  vm.runInContext('renderAddressingSummary(value)', context);
  assert.equal(node('addressing-summary').hidden, true);
});

test('a static address inside the DHCP range is an attention item', () => {
  const {context, node} = dashboard();
  const rows = attention(context, node, fullOverview([modeDevice('TV', '192.0.2.10', state('static', 'no_lease_or_reservation')),
    modeDevice('Cam', '192.0.2.11', state('static', 'no_lease_or_reservation')), modeDevice('Hub', '192.0.2.12', state('static', 'outside_dhcp_range'))]));
  assert.equal(rows.length, 1);
  assert.equal(rows[0][1], 'Static address inside the DHCP range');
  assert.equal(rows[0][2], 'TV and Cam use an address the DHCP server can hand out, with no lease or reservation; that can clash with another device.');
  assert.equal(rows[0][0], '2');
});

test('a mode change in the latest collection is an attention item', () => {
  const {context, node} = dashboard();
  const changed = state('dhcp_dynamic', 'dhcp_lease', {previous_mode:'dhcp_reserved', changed_at:'2026-11-14T03:10:11Z', changed_in_latest:true});
  const older = state('dhcp_dynamic', 'dhcp_lease', {previous_mode:'dhcp_reserved', changed_at:'2026-11-13T03:10:11Z', changed_in_latest:false});
  const rows = attention(context, node, fullOverview([modeDevice('TV', '192.0.2.10', changed), modeDevice('Cam', '192.0.2.11', older)]));
  assert.equal(rows.length, 1);
  assert.equal(rows[0][1], 'Address assignment changed');
  assert.equal(rows[0][2], 'TV changed how their address is assigned in the latest collection.');
  assert.equal(attention(context, node, fullOverview([modeDevice('Cam', '192.0.2.11', older)])).length, 0);
});

test('addressing items follow breach and review items', () => {
  const {context, node} = dashboard();
  const tv = modeDevice('TV', '192.0.2.10', state('static', 'no_lease_or_reservation', {previous_mode:'dhcp_dynamic', changed_at:'2026-11-14T03:10:11Z', changed_in_latest:true}));
  context.value = fullOverview([tv], {assessments:{summaries:[{device_id:'mac:192.0.2.10', state:'review', pending:null, processing:false, potential_breaches:1, worth_checking:0, requested_at:null}], usage:null}});
  vm.runInContext('assessmentIndex = new Map(value.assessments.summaries.map(item => [item.device_id, item])); renderAttention(value)', context);
  const titles = node('attention-items').children.map(item => texts(item)[1]);
  assert.deepEqual(titles, ['Potential breach', 'Assessment awaiting your review', 'Static address inside the DHCP range', 'Address assignment changed']);
});

test('unmonitored devices never raise addressing items', () => {
  const {context, node} = dashboard();
  const stray = modeDevice('Stray', '192.0.2.99', state('static', 'no_lease_or_reservation', {previous_mode:'dhcp_dynamic', changed_in_latest:true}));
  const rows = attention(context, node, fullOverview([modeDevice('TV', '192.0.2.10', null)], {candidates:[stray]}));
  assert.equal(rows.length, 0);
});

test('addressing items for several devices search the list by their token', () => {
  const {context, node} = dashboard();
  const both = state('static', 'no_lease_or_reservation', {previous_mode:'dhcp_dynamic', changed_at:'2026-11-14T03:10:11Z', changed_in_latest:true});
  context.value = fullOverview([modeDevice('TV', '192.0.2.10', both), modeDevice('Cam', '192.0.2.11', both)]);
  vm.runInContext('assessmentIndex = new Map(); renderAttention(value)', context);
  const buttons = node('attention-items').children.map(item => item.children.find(child => child.tag === 'button'));
  const tokens = [];
  for (const button of buttons) {
    node('device-search').value = 'leftover';
    vm.runInContext("setSignalFilter('third')", context);
    button.listeners.click();
    tokens.push(node('device-search').value);
    // The list filters are cleared, never narrowed to assessments needing review.
    assert.equal(vm.runInContext('signalFilter', context), 'all');
    assert.equal(node('device-presence').value, 'observed');
  }
  assert.deepEqual(tokens, ['static-in-dhcp-range', 'assignment-changed']);
});

test('an addressing item for one device opens that device', () => {
  const {context, node} = dashboard();
  const both = state('static', 'no_lease_or_reservation', {previous_mode:'dhcp_dynamic', changed_at:'2026-11-14T03:10:11Z', changed_in_latest:true});
  context.value = fullOverview([modeDevice('TV', '192.0.2.10', both)]);
  vm.runInContext('assessmentIndex = new Map(); renderAttention(value)', context);
  const labels = node('attention-items').children.map(item => item.children.find(child => child.tag === 'button').textContent);
  assert.deepEqual(labels, ['Open TV', 'Open TV']);
});
