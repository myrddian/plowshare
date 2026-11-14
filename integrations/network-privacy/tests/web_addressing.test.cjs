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
  assert.equal(section.className, 'readout addressing');
  assert.ok(lines.includes('Address assignment'));
  assert.ok(lines.includes('Dynamic (DHCP lease)'));
  assert.ok(lines.includes('Source: pihole'));
  assert.ok(lines.some(line => line.startsWith('Lease ends ')));
  assert.ok(lines.some(line => line.startsWith('Changed from Reserved to Dynamic ')));
  // Nothing recorded means no lease line, no source and no change line.
  const bare = texts(call(context, 'renderAddressing(value)', {mode:'static', basis:'outside_dhcp_range', services:[],
    expires_at:null, previous_mode:null, changed_at:null}));
  assert.deepEqual(bare, ['Address assignment', 'Static (outside the DHCP range)']);
});

test('no addressing renders nothing', () => {
  const {context} = dashboard();
  assert.equal(call(context, 'renderAddressing(value)', null), null);
});

test('the device pane carries an Assignment fact only when addressing is known', () => {
  const {context, node} = dashboard();
  const base = {name:'TV', address:'192.0.2.10', identity:null, last_known_identity:null, presence:null, detection:null,
    dns_activity:null, dns:[], traffic:null, services:[], history:[], last_response_at:null};
  const lines = value => {context.value = value; vm.runInContext('renderDeviceView(value)', context); return [...texts(node('device-identity')), ...texts(node('device-facts'))];};
  const withAddressing = lines({...base, addressing:{mode:'dhcp_reserved', basis:'dhcp_reservation', services:['pihole'], expires_at:null, previous_mode:null, changed_at:null}});
  assert.ok(withAddressing.includes('Assignment'));
  assert.ok(withAddressing.includes('Address assignment'));
  const without = lines({...base, addressing:null});
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
