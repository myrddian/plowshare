'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

// The deep port scan UI runs in the shipped script against a small DOM
// boundary. Sample data only.
function dashboard() {
  const nodes = new Map();
  function node(id) {
    if (!nodes.has(id)) nodes.set(id, {id, value:'', textContent:'', hidden:false, open:false, disabled:false, classList:{toggle() {}}, listeners:{}, setAttribute() {}, focus() {}, scrollIntoView() {}, addEventListener(name,handler) {this.listeners[name] = handler;}, children:[], append(...children) {this.children.push(...children);}, replaceChildren(...children) {this.children = children;}, querySelector() {return null;}, querySelectorAll() {return [];}, reset() {}});
    return nodes.get(id);
  }
  const create = tag => ({tag, children:[], textContent:'', listeners:{}, append(...children) {this.children.push(...children);}, replaceChildren(...children) {this.children = children;}, addEventListener(name, handler) {this.listeners[name] = handler;}});
  const context = vm.createContext({
    document:{getElementById:node, createElement:create},
    location:{hash:''}, URLSearchParams, CSS:{escape:value=>value}, PrivacyMarkdown:{render:source=>({source})},
    setInterval() {}, fetch:async () => ({ok:false, status:401}),
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/plowshare_privacy/web/app.js'), 'utf8'), context);
  const run = (code, values = {}) => {Object.assign(context, values); return vm.runInContext(code, context);};
  return {run, node, context};
}
const texts = node => [node.textContent, ...(node.children || []).flatMap(texts)].filter(Boolean);
const classes = node => [node.className, ...(node.children || []).flatMap(classes)].filter(Boolean);
const buttons = node => [...(node.tag === 'button' ? [node] : []), ...(node.children || []).flatMap(buttons)];

const TV = 'mac:02:00:00:00:00:01', CAMERA = 'mac:02:00:00:00:00:02';
const FINISHED = '2026-11-12T10:00:00Z';
function device(name, address, deviceId, exposures = []) {
  return {name, address, identity:{mac:deviceId.slice(4), device_id:deviceId}, detection:{exposures}};
}
function result(deviceId, overrides = {}) {
  return {device_id:deviceId, address:'192.0.2.10', request_id:'r', trigger:'operator', report:null, started_at:'2026-11-12T09:00:00Z',
    finished_at:FINISHED, state:'complete', open_ports:[443, 8008, 9000], unanswered:3, detail:null, ...overrides};
}
function view(deviceId, overrides = {}) {
  const latest = overrides.latest ?? null;
  // The newest complete result; a test sets it itself when a later attempt did not complete.
  return {device_id:deviceId, available:true, state:'idle', trigger:null, checked:0, total:65535, open_so_far:[], estimated_finish:null,
    latest, latest_complete:latest?.state === 'complete' ? latest : null, detail:null, ...overrides};
}
function summary(deviceId, requested_at) {
  return {device_id:deviceId, state:'accepted', pending:null, processing:false, potential_breaches:0, worth_checking:0, requested_at};
}
function overview(views, summaries = [], extra = {}) {
  return {devices:[device('Living room TV', '192.0.2.10', TV), device('Driveway camera', '192.0.2.11', CAMERA)],
    assessments:{summaries, usage:{background_limit:2, used_today:1, daily_ceiling:12}},
    deep_scans:{views, usage:{automatic_limit:3, used_today:1}, available:true, detail:null, configured_ports:[443]},
    health:{connected:true, detail:'', recovery:[], pihole:'Configured', unifi:'Configured'},
    quick_presence:{enabled:true},
    system:{deployment:{credentials:[]}}, ...extra};
}
function load(run, value) {
  return run('overview = value; assessmentIndex = new Map(value.assessments.summaries.map(item => [item.device_id, item]));', {value});
}

test('newOpenPorts ignores configured ports', () => {
  const {run} = dashboard();
  const ports = (latest, configured) => [...run('newOpenPorts(view, configured)', {view:view(TV, {latest}), configured})];
  assert.deepEqual(ports(result(TV), [443, 8008]), [9000]);
  assert.deepEqual(ports(result(TV), [443, 8008, 9000]), []);
  // Only a complete scan has meaningful ports.
  assert.deepEqual(ports(result(TV, {state:'failed'}), []), []);
  // A later attempt that did not complete does not hide the complete scan's ports.
  const earlier = result(TV, {finished_at:'2026-11-12T08:00:00Z'});
  assert.deepEqual([...run('newOpenPorts(v, [443, 8008])', {v:view(TV, {latest:result(TV, {state:'cancelled', open_ports:[]}), latest_complete:earlier})})], [9000]);
  assert.deepEqual(ports(null, []), []);
  assert.deepEqual([...run('newOpenPorts(null, [])')], []);
});

test('the attention band lists devices with unassessed new ports and clears after an assessment', () => {
  const {run, node} = dashboard();
  const band = value => {load(run, value); run('renderAttention(value)', {value}); return node('attention-items').children.map(item => texts(item).join(' | '));};
  const open = view(TV, {latest:result(TV)});
  // Never assessed: requested_at is absent.
  let lines = band(overview([open]));
  assert.equal(lines.length, 1);
  assert.match(lines[0], /^1 \| New open ports \| Living room TV answered on ports outside the usual checks\. \| Open Living room TV$/);
  // Assessed before the scan finished: still needs a look.
  lines = band(overview([open], [summary(TV, '2026-11-12T09:30:00Z')]));
  assert.equal(lines.length, 1);
  // Assessed after the scan finished: settled.
  lines = band(overview([open], [summary(TV, '2026-11-12T10:05:00Z')]));
  assert.deepEqual(lines, []);
  // Only the usual ports answered.
  assert.deepEqual(band(overview([view(TV, {latest:result(TV, {open_ports:[443]})})])), []);
  // No scanner at all.
  assert.deepEqual(band(overview([], [], {deep_scans:null})), []);
  // Two devices make one counted item that names both.
  lines = band(overview([open, view(CAMERA, {latest:result(CAMERA)})]));
  assert.match(lines[0], /^2 \| New open ports \| Living room TV and Driveway camera answered/);
});

test('deepScanNeedsReview compares the assessment request with the scan finish', () => {
  const {run} = dashboard();
  const value = overview([view(TV, {latest:result(TV)})]);
  load(run, value);
  const needs = requested => run('deepScanNeedsReview(value.devices[0], value)', {value:{...value, assessments:{...value.assessments, summaries:requested === undefined ? [] : [summary(TV, requested)]}}});
  assert.equal(needs(undefined), true);
  assert.equal(needs(null), true);
  assert.equal(needs('2026-11-12T09:59:59Z'), true);
  assert.equal(needs('2026-11-12T10:00:01Z'), false);
});

test('the pane shows progress, the result and Assess again', () => {
  const {run} = dashboard();
  const render = (value, index = 0) => {
    load(run, value);
    const block = {tag:'div', children:[], textContent:'', append(...children) {this.children.push(...children);}, replaceChildren(...children) {this.children = children;}};
    run('selectedDevice = value.devices[index].address; assessmentsAvailable = true; renderDeepScan(value.devices[index], block)', {value, index, block});
    return block;
  };
  // Idle.
  let block = render(overview([]));
  assert.deepEqual(buttons(block).map(item => [item.textContent, item.className]), [['Deep-scan this device', 'secondary']]);
  // Queued.
  block = render(overview([view(TV, {state:'queued', trigger:'automatic'})]));
  assert.ok(texts(block).includes('Waiting for its turn'));
  assert.deepEqual(buttons(block).map(item => [item.textContent, item.className]), [['Cancel', 'quiet']]);
  // Running, with the finish a little over two minutes away.
  const finish = new Date(Date.now() + 130000).toISOString();
  block = render(overview([view(TV, {state:'running', trigger:'operator', checked:12000, open_so_far:[443, 9000], estimated_finish:finish})]));
  const progress = texts(block).find(line => /ports checked/.test(line));
  assert.match(progress, /^12,000 of 65,535 ports checked · 2 open ports so far · about 3 minutes left$/);
  assert.deepEqual(buttons(block).map(item => item.textContent), ['Cancel']);
  // Result: open ports with the usual service wording, the rest plain, and the unanswered count.
  const value = overview([view(TV, {latest:result(TV)})]);
  value.devices[0].detection.exposures = [{ports:[8008], possible_service:'Chromecast control', risk_check:'Check the firmware.'}];
  block = render(value);
  const lines = texts(block);
  assert.ok(lines.some(line => /^TCP 8008 · Possible Chromecast control\. Check the firmware\./.test(line)));
  assert.ok(lines.includes('TCP 9000 · outside the usual checks'), lines.join('\n'));
  assert.ok(lines.includes('3 ports did not answer'));
  assert.deepEqual(buttons(block).map(item => item.textContent), ['Deep-scan this device', 'Assess again']);
  // Once assessed after the scan, Assess again goes.
  const settled = overview([view(TV, {latest:result(TV)})], [summary(TV, '2026-11-12T10:30:00Z')]);
  assert.deepEqual(buttons(render(settled)).map(item => item.textContent), ['Deep-scan this device']);
  // A failed scan uses the error style; a cancelled one does not.
  block = render(overview([view(TV, {latest:result(TV, {state:'failed', open_ports:[], detail:'The probe could not start.'})})]));
  assert.ok(classes(block).some(name => /error/.test(name)));
  assert.ok(texts(block).includes('The probe could not start.'));
  block = render(overview([view(TV, {latest:result(TV, {state:'cancelled', open_ports:[], detail:'You cancelled it.'})})]));
  assert.ok(texts(block).includes('You cancelled it.'));
  assert.ok(!classes(block).some(name => /error/.test(name)));
});

test('the pane request and cancel buttons post to the device', async () => {
  const {run} = dashboard();
  const value = overview([]);
  load(run, value);
  const calls = [];
  const block = {tag:'div', children:[], textContent:'', append(...children) {this.children.push(...children);}, replaceChildren(...children) {this.children = children;}};
  run(`api = async (path, options) => {calls.push([path, options.method, options.body]); return queuedView;};
    selectedDevice = value.devices[0].address; renderDeepScan(value.devices[0], block);`,
    {value, block, calls, queuedView:view(TV, {state:'queued', trigger:'operator'})});
  await buttons(block)[0].listeners.click();
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.deepEqual(JSON.parse(JSON.stringify(calls)), [[`/api/devices/${encodeURIComponent(TV)}/deep-scan/request`, 'POST', '{}']]);
  assert.ok(texts(block).includes('Waiting for its turn'));
});

test('Settings disables the field without an identity source', () => {
  const {run, node} = dashboard();
  run('renderDeepScanSettings(value)', {value:{automatic_limit:2, used_today:0, available:false, detail:null}});
  assert.equal(node('deep-scan-limit').disabled, true);
  assert.equal(node('deep-scan-limit').value, '2');
  assert.equal(node('deep-scan-detail').textContent, 'Deep port scans need UniFi or Pi-hole to confirm each device’s address.');
  run('renderDeepScanSettings(value)', {value:{automatic_limit:0, used_today:0, available:true, detail:null}});
  assert.equal(node('deep-scan-limit').disabled, false);
  assert.equal(node('deep-scan-detail').textContent, 'Automatic deep scans are off.');
  run('renderDeepScanSettings(value)', {value:{automatic_limit:1, used_today:0, available:true, detail:null}});
  assert.equal(node('deep-scan-detail').textContent, 'Up to 1 automatic deep scan a day.');
});

test('background work summarises automatic deep scans', () => {
  const {run, node} = dashboard();
  const lines = value => {
    run('latestStatus = {profile_research_available:false}; renderBackgroundUsage(value)', {value});
    return node('background-usage').children.map(item => item.textContent);
  };
  let shown = lines(overview([]));
  assert.ok(shown.includes('Automatic deep scans used today'));
  assert.ok(shown.includes('1 of 3'));
  const off = overview([]);
  off.deep_scans.usage.automatic_limit = 0;
  assert.ok(lines(off).includes('Off'));
  assert.equal(node('background-work').hidden, false);
  shown = lines(overview([], [], {deep_scans:null, assessments:null}));
  assert.deepEqual(shown, []);
  assert.equal(node('background-work').hidden, true);
});

// A pane is opened on the Living room TV and the overview is then replaced, as
// each five-second refresh does.
function open(run, value, fetches) {
  load(run, value);
  const block = {tag:'div', children:[], textContent:'', rebuilt:0, append(...children) {this.children.push(...children);}, replaceChildren(...children) {this.rebuilt++; this.children = children;}};
  run(`api = async (path, options) => {gets.push([path, options?.method || 'GET']); return next();};
    selectedDevice = value.devices[0].address; assessmentsAvailable = true; renderDeepScan(value.devices[0], block);`,
    {value, block, gets:fetches.gets, next:() => fetches.reply});
  return block;
}
function refreshed(run, value) {load(run, value);}

test('the attention item survives a later cancelled scan and the listing keeps its ports', () => {
  const {run, node} = dashboard();
  const cancelled = result(TV, {state:'cancelled', open_ports:[], detail:'You cancelled it.', finished_at:'2026-11-12T11:00:00Z'});
  const value = overview([view(TV, {latest:cancelled, latest_complete:result(TV)})]);
  load(run, value);
  run('renderAttention(value)', {value});
  assert.match(texts(node('attention-items').children[0]).join(' | '), /New open ports \| Living room TV answered/);
  const fetches = {gets:[], reply:null};
  const block = open(run, value, fetches);
  const lines = texts(block);
  assert.ok(lines.some(line => /Last deep scan was cancelled/.test(line)));
  assert.ok(lines.includes('You cancelled it.'));
  assert.ok(lines.includes('TCP 9000 · outside the usual checks'));
  assert.ok(lines.some(line => /^Last complete deep scan finished/.test(line)));
});

test('polling re-reads only while a scan is active and rebuilds only the block on a change', async () => {
  const {run} = dashboard();
  const fetches = {gets:[], reply:null};
  // Idle and unchanged: nothing is read and nothing is rebuilt.
  let value = overview([view(TV, {latest:result(TV)})]);
  const block = open(run, value, fetches);
  assert.equal(block.rebuilt, 1);
  await run('pollDeepScan()');
  assert.equal(fetches.gets.length, 0);
  assert.equal(block.rebuilt, 1);
  // The overview now says it is running: the device's view is read once, and the block rebuilt.
  value = overview([view(TV, {state:'running', trigger:'operator', checked:100, latest:result(TV)})]);
  refreshed(run, value);
  fetches.reply = view(TV, {state:'running', trigger:'operator', checked:200, latest:result(TV)});
  await run('pollDeepScan()');
  assert.deepEqual(JSON.parse(JSON.stringify(fetches.gets)), [[`/api/devices/${encodeURIComponent(TV)}/deep-scan`, 'GET']]);
  assert.equal(block.rebuilt, 2);
  assert.ok(texts(block).some(line => /^200 of 65,535 ports checked/.test(line)));
  // Still running with the same answer: read again, but no rebuild.
  await run('pollDeepScan()');
  assert.equal(fetches.gets.length, 2);
  assert.equal(block.rebuilt, 2);
  // It finishes: the read gives a settled view and the block rebuilds into the result.
  fetches.reply = view(TV, {latest:result(TV, {open_ports:[443, 9000]})});
  refreshed(run, overview([fetches.reply]));
  await run('pollDeepScan()');
  assert.equal(block.rebuilt, 3);
  assert.ok(buttons(block).some(item => item.textContent === 'Deep-scan this device'));
  // Settled again: no further reads.
  await run('pollDeepScan()');
  assert.equal(fetches.gets.length, 3);
  assert.equal(block.rebuilt, 3);
  // A change visible only in the overview rebuilds without a read.
  refreshed(run, overview([view(TV, {latest:result(TV, {open_ports:[443, 9000, 9100]})})]));
  await run('pollDeepScan()');
  assert.equal(fetches.gets.length, 3);
  assert.equal(block.rebuilt, 4);
});

test('polling does nothing once another device or no device is open', async () => {
  const {run} = dashboard();
  const fetches = {gets:[], reply:view(TV, {state:'running'})};
  const block = open(run, overview([view(TV, {state:'running'})]), fetches);
  run("selectedDevice = '192.0.2.11'");
  await run('pollDeepScan()');
  assert.equal(fetches.gets.length, 0);
  assert.equal(block.rebuilt, 1);
  run('selectedDevice = null');
  await run('pollDeepScan()');
  assert.equal(fetches.gets.length, 0);
});

test('Assess again goes when the assessment is requested, though the scan view is unchanged', async () => {
  const {run} = dashboard();
  const fetches = {gets:[], reply:null};
  const scan = view(TV, {latest:result(TV)});
  const block = open(run, overview([scan]), fetches);
  assert.ok(buttons(block).some(item => item.textContent === 'Assess again'));
  assert.ok(texts(block).some(line => /has not seen/.test(line)));
  // The operator assesses: the summary's requested_at moves past the scan; the scan view is the same.
  refreshed(run, overview([scan], [summary(TV, '2026-11-12T10:30:00Z')]));
  await run('pollDeepScan()');
  assert.equal(fetches.gets.length, 0);
  assert.equal(block.rebuilt, 2);
  assert.deepEqual(buttons(block).map(item => item.textContent), ['Deep-scan this device']);
  assert.ok(!texts(block).some(line => /has not seen/.test(line)));
});

test('disconnect clears the deep-scan state and the Settings group', async () => {
  const {run, node} = dashboard();
  const fetches = {gets:[], reply:null};
  open(run, overview([view(TV, {latest:result(TV)})]), fetches);
  run('deepScanBusy = "request"');
  run('renderDeepScanSettings(value)', {value:{automatic_limit:3, used_today:0, available:false, detail:null}});
  node('deep-scan-group').hidden = false;
  assert.equal(run('deepScanPane !== null'), true);
  run('api = async () => ({})');
  await node('disconnect').listeners.click();
  assert.equal(run('deepScanPane'), null);
  assert.equal(run('deepScanBusy'), null);
  assert.equal(node('deep-scan-group').hidden, true);
  assert.equal(node('deep-scan-limit').value, '');
  assert.equal(node('deep-scan-limit').disabled, false);
  assert.equal(node('deep-scan-detail').textContent, '');
});

test('a request that completes after disconnect neither redraws nor refreshes', async () => {
  const {run, node} = dashboard();
  const fetches = {gets:[], reply:null};
  const block = open(run, overview([]), fetches);
  let release;
  run('api = () => new Promise(resolve => {release = resolve;}); refresh = async () => {refreshes++;}; refreshes = 0;', {release:null});
  const pending = run('deepScanAction("request")');
  run('deepScanPane = null');
  run('release({})') ;
  await pending;
  assert.equal(run('refreshes'), 0);
  assert.equal(block.rebuilt, 2);
});
