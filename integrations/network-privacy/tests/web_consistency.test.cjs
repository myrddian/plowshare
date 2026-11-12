'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

// Messages that must not contradict each other or the collector, run in the
// shipped script against a small DOM boundary. Sample data only.
function dashboard() {
  const nodes = new Map();
  function node(id) {
    if (!nodes.has(id)) nodes.set(id, {id, value:'', textContent:'', hidden:false, open:false, disabled:false, classList:{toggle() {}}, listeners:{}, setAttribute() {}, focus() {}, scrollIntoView() {}, addEventListener(name,handler) {this.listeners[name] = handler;}, children:[], append(...children) {this.children.push(...children);}, replaceChildren(...children) {this.children = children;}, querySelector() {return null;}, querySelectorAll() {return [];}, reset() {}});
    return nodes.get(id);
  }
  const create = tag => ({tag, children:[], textContent:'', listeners:{}, append(...children) {this.children.push(...children);}, addEventListener(name, handler) {this.listeners[name] = handler;}});
  const context = vm.createContext({
    document:{getElementById:node, createElement:create},
    location:{hash:''}, URLSearchParams, CSS:{escape:value=>value}, PrivacyMarkdown:{render:source=>({source})},
    setInterval() {}, fetch:async () => ({ok:false, status:401}),
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/plowshare_privacy/web/app.js'), 'utf8'), context);
  const run = (code, values = {}) => {Object.assign(context, values); return vm.runInContext(code, context);};
  return {run, node};
}

test('every time is written in the household zone and names it', () => {
  const {run} = dashboard();
  const utc = run("formatWhen('2026-11-11T13:30:00Z')");
  assert.match(utc, /UTC/);
  run("setHouseholdZone('Australia/Sydney')");
  const sydney = run("formatWhen('2026-11-11T13:30:00Z')");
  // 13:30 UTC on the 11th is 00:30 on the 12th in Sydney.
  assert.match(sydney, /12/);
  assert.match(sydney, /AEDT|GMT\+11/);
  assert.match(run("formatDay('2026-11-11T13:30:00Z')"), /12/);
  // A calendar day is never shifted.
  assert.match(run("formatDay('2026-11-11')"), /11/);
  // A zone the browser does not know falls back to UTC, still named.
  run("setHouseholdZone('Not/AZone')");
  assert.equal(run('householdZone'), 'UTC');
});

test('a silent full scan says when a later check answered', () => {
  const {run} = dashboard();
  const scan = device => run('fullScanText(device)', {device});
  const base = {last_seen:'2026-11-11T09:00:00Z', services:[{port:443, status:'filtered'}], last_response_at:null};
  assert.match(scan(base), /no answer on the checked ports$/);
  assert.match(scan({...base, last_response_at:'2026-11-11T09:30:00Z'}), /no answer then; a later check got one$/);
  assert.match(scan({...base, services:[{port:443, status:'open'}], last_response_at:'2026-11-11T09:30:00Z'}), /answered on the checked ports$/);
  assert.equal(scan({...base, last_seen:null}), 'Awaiting first full scan');
});

test('a single clue never claims that the name and port agree', () => {
  const {run} = dashboard();
  const panel = detection => run('renderDeviceDetection(detection)', {detection});
  const named = panel({confidence:'corroborated', device_type:'Camera', clues:['Observed device name suggests camera']});
  assert.equal(named.children.length, 2);
  const both = panel({confidence:'corroborated', device_type:'Camera', clues:['name', 'port']});
  assert.match(both.children[2].textContent, /name and an open port/);
});

test('tool and agent counts come from the collector, and every missing tool is named', () => {
  const {run} = dashboard();
  const tools = agents => run('toolVisibility(agents)', {agents});
  assert.equal(tools({tool_visibility:'projected', tools:[], missing_tools:['network_scan', 'network_scope', 'network_device']}),
    '0 of 3 visible · missing: network_scan, network_scope, network_device');
  assert.equal(tools({tool_visibility:'projected', tools:['a', 'b'], missing_tools:[]}), '2 of 2 visible');
  assert.equal(tools({tool_visibility:'awaiting_context', tools:[], missing_tools:[]}), 'Awaiting the first Ask conversation');
});

test('disabled Pause and Resume always say why', () => {
  const {run} = dashboard();
  const why = (schedule, control, pending, deployment) => run('scheduleBlocked(schedule, control, pending, deployment)', {schedule, control, pending, deployment});
  const schedule = {paused:false}, control = {available:true, pending:false};
  assert.equal(why(schedule, control, false, null), null);
  assert.match(why(schedule, {available:false}, false, null), /started without schedule control/);
  assert.match(why(null, control, false, null), /could not be read/);
  assert.match(why(schedule, control, false, 'deployment-1'), /Check retained deployment/);
  assert.match(why(schedule, control, true, null), /unconfirmed/);
});

test('the advisory lookup starts from the reported model and points at the automatic result', () => {
  const {run} = dashboard();
  const device = {address:'192.0.2.10', identity:{device_id:'mac:02:00:00:00:00:01'}};
  const section = {descriptions:[{manufacturer:'Sony', model_name:'BRAVIA KD-55X85K'}],
    advisories:{status:'searched', model:'Sony BRAVIA KD-55X85K', checked_at:'2026-11-11T09:00:00Z'}};
  run('deviceSupplemental = {device_id: device.identity.device_id, section}', {device, section});
  assert.equal(run('reportedModel(device)'), 'Sony BRAVIA KD-55X85K');
  assert.match(run('productHint(device)'), /^Advisories for Sony BRAVIA KD-55X85K were looked up .* under What we know/);
  // Without a lookup, the device's own description supplies the model.
  run('deviceSupplemental.section = {...section, advisories:null}');
  assert.equal(run('reportedModel(device)'), 'Sony BRAVIA KD-55X85K');
  assert.match(run('productHint(device)'), /^Confirm the product with UniFi/);
  // Another device's facts are never used.
  assert.equal(run("reportedModel({address:'192.0.2.11', identity:{device_id:'mac:02:00:00:00:00:02'}})"), '');
});

test('the device pane folds the report section that repeats What we know', () => {
  const {run} = dashboard();
  const heading = (tag, text) => ({tagName:tag, textContent:text});
  const supplemental = heading('H2', 'Supplemental information');
  const paragraph = {tagName:'P', textContent:'Sony BRAVIA'};
  const tail = heading('H2', 'Evidence');
  supplemental.nextElementSibling = paragraph; paragraph.nextElementSibling = tail; tail.nextElementSibling = null;
  const replaced = [];
  supplemental.replaceWith = node => replaced.push(node);
  tail.replaceWith = () => {};
  const article = {children:[supplemental, paragraph, tail]};
  run('foldDetails(article, paneFolded)', {article});
  assert.equal(replaced.length, 1);
  assert.equal(replaced[0].children[0].textContent, 'Details · supplemental information, also under What we know');
});

test('the household zone is set from Settings and applies to every date at once', async () => {
  const {run, node} = dashboard();
  const sent = [];
  run('api = async (path, options) => {sent.push([path, JSON.parse(options.body)]); return {time_zone:"Australia/Sydney", time_zone_configured:true};}; refresh = async () => {};', {sent});
  run('renderZoneSetting({settings_available:true, time_zone:"UTC", time_zone_configured:false})');
  assert.equal(node('household-zone').hidden, false);
  assert.equal(node('zone-state').textContent, 'Not set · UTC in use');
  assert.equal(node('zone-clear').hidden, true);
  await run('saveZone("Australia/Sydney")');
  assert.deepEqual(JSON.parse(JSON.stringify(sent)), [['/api/time-zone', {timeZone:'Australia/Sydney'}]]);
  assert.equal(run('householdZone'), 'Australia/Sydney');
  assert.match(node('notice').textContent, /Time zone set to Australia\/Sydney/);
  // A name the browser does not know is refused before anything is sent.
  await run('saveZone("Mars/Olympus")');
  assert.equal(sent.length, 1);
  assert.match(node('notice').textContent, /not a time zone this browser knows/);
  // Without dashboard settings the panel stays hidden.
  run('renderZoneSetting({settings_available:false, time_zone:"UTC"})');
  assert.equal(node('household-zone').hidden, true);
});

test('Request a scan stays off while settings wait, even after a request finishes', async () => {
  const {run, node} = dashboard();
  run('api = async () => ({}); refresh = async () => {}; globalThis.crypto = {randomUUID: () => "id"};');
  run('scanState.pending = true; applyScanState()');
  assert.equal(node('scan').disabled, true);
  run('scanState.pending = false; applyScanState()');
  assert.equal(node('scan').disabled, false);
  run('scanState.blocked = true; applyScanState()');
  await node('scan').listeners.click();
  // The click did nothing while blocked; the finally of a real request never re-enables it either.
  assert.equal(node('scan').disabled, true);
  run('scanState.blocked = false; applyScanState()');
  const pending = node('scan').listeners.click();
  assert.equal(node('scan').disabled, true);
  run('scanState.pending = true');
  await pending;
  assert.equal(node('scan').disabled, true);
});

test('the list filter and order travel in the URL, and device= only on Devices', () => {
  const {run, node} = dashboard();
  const pushed = [];
  run('location.pathname = "/"; history = {pushState(a, b, url) {pushed.push(["push", url]); location.hash = url.slice(1);}, replaceState(a, b, url) {pushed.push(["replace", url]); location.hash = url.slice(1);}};', {pushed});
  node('device-presence').value = 'observed'; node('device-sort').value = 'attention';
  run('currentView = "devices"; selectedDevice = "192.0.2.10"; setSignalFilter("review"); recordLocation(true)');
  assert.deepEqual([...pushed.at(-1)], ['replace', '/#tab=devices&device=192.0.2.10&show=review']);
  node('device-sort').value = 'name';
  run('recordLocation(true)');
  assert.equal(pushed.at(-1)[1], '/#tab=devices&device=192.0.2.10&show=review&order=name');
  // On another tab the open device and list state are not in the URL.
  run('currentView = "findings"; recordLocation()');
  assert.deepEqual([...pushed.at(-1)], ['push', '/#tab=findings']);
  // Restoring checks every value against its allowlist.
  run('applyListParams(new URLSearchParams("show=silent&order=bogus&presence=candidate"))');
  assert.equal(run('signalFilter'), 'silent');
  assert.equal(node('device-sort').value, 'attention');
  assert.equal(node('device-presence').value, 'candidate');
});
