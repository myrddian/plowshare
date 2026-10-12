'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

// Exercise the shipped event handlers with a small DOM boundary, not a second
// implementation of save behavior. No network or household data is involved.
async function dashboard() {
  const nodes = new Map();
  function node(id) {
    if (!nodes.has(id)) nodes.set(id, {value:'', textContent:'', disabled:false, classList:{toggle() {}}, addEventListener() {}, replaceChildren() {}, reset() {}});
    return nodes.get(id);
  }
  const context = vm.createContext({
    document:{getElementById:node}, location:{hash:''}, URLSearchParams,
    setInterval() {}, fetch:async () => ({ok:false, status:401}),
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/plowshare_privacy/web/app.js'), 'utf8'), context);
  await new Promise(resolve => setImmediate(resolve));
  return {context, node};
}

test('saved settings remain editable while activation is pending', async () => {
  const {context, node} = await dashboard();
  vm.runInContext("renderMonitorSettings({editable:true,pending:true,pihole_configured:true,detail:'Settings saved. Collector is disconnected.'})", context);
  assert.equal(node('pihole-save').disabled, false);
  assert.equal(node('save-monitoring').disabled, false);
  assert.match(node('pihole-saved').textContent, /password are stored/);
  assert.match(node('pihole-blocker').textContent, /Settings saved/);
  assert.equal(node('scan').disabled, true);
});

test('save uses one request and retains confirmation after a failed refresh', async () => {
  const {context, node} = await dashboard();
  const requests = [];
  node('pihole-origin').value = 'https://pihole.example';
  node('pihole-password').value = 'fixture-secret';
  context.fetch = async (url, options) => {
    requests.push({url, body:JSON.parse(options.body)});
    return {ok:true, json:async () => ({saved:true, connected:null, pending:true, issues:[]})};
  };
  vm.runInContext("refresh = async () => {throw new Error('fixture read failure');}", context);
  await vm.runInContext('piholeConnection(true)', context);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/pihole');
  assert.equal(requests[0].body.save, true);
  assert.equal(node('pihole-password').value, '');
  assert.match(node('pihole-detail').textContent, /password saved/);
  assert.match(node('pihole-detail').textContent, /not tested/);
  assert.match(node('pihole-saved').textContent, /Waiting to apply/);
  assert.match(node('notice').textContent, /Pi-hole was saved/);
});

test('failed optional test leaves the saved indicator intact', async () => {
  const {context, node} = await dashboard();
  vm.runInContext('renderPiHoleSaved(true)', context);
  node('pihole-password').value = 'fixture-invalid';
  context.fetch = async () => ({ok:true, json:async () => ({saved:false, connected:false, issues:['Authentication failed']})});
  await vm.runInContext('piholeConnection(false)', context);
  assert.match(node('pihole-saved').textContent, /password are stored/);
  assert.match(node('pihole-detail').textContent, /Connection test failed/);
});

test('unknown save outcome does not claim the password was saved', async () => {
  const {context, node} = await dashboard();
  node('pihole-password').value = 'fixture-secret';
  context.fetch = async () => {throw new Error('fixture disconnect');};
  await vm.runInContext('piholeConnection(true)', context);
  assert.match(node('pihole-detail').textContent, /Save was not confirmed/);
  assert.equal(node('pihole-password').value, '');
  assert.doesNotMatch(node('pihole-saved').textContent, /password are stored/);
});

function scheduleFixture(context) {
  vm.runInContext("overview = {health:{schedule:{paused:false, owner:'installer'}}, schedule_control:{account:'installer',pending:false}, pending_deployment:null}; refresh = async () => {}; renderScheduleControl(overview.health.schedule, overview.schedule_control, null);", context);
}

test('pause sends one control, clears password and confirms without redeployment', async () => {
  const {context, node} = await dashboard();
  scheduleFixture(context);
  node('schedule-control-password').value = 'fixture-password';
  const requests = [];
  context.fetch = async (url, options) => {
    requests.push({url, body:JSON.parse(options.body)});
    return {ok:true, json:async () => ({schedule:{paused:true,owner:'installer'},control:{account:'installer',pending:false}})};
  };
  await vm.runInContext('controlSchedule(true)', context);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/schedule/control');
  assert.equal(requests[0].body.paused, true);
  assert.equal(node('schedule-control-password').value, '');
  assert.equal(node('pause-schedule').disabled, true);
  assert.equal(node('resume-schedule').disabled, false);
  assert.match(node('schedule-control-detail').textContent, /paused/);
});

test('lost control reply fences both mutations and check only reads', async () => {
  const {context, node} = await dashboard();
  scheduleFixture(context);
  node('schedule-control-password').value = 'fixture-password';
  let sends = 0;
  context.fetch = async () => {sends++; throw new Error('fixture reply lost');};
  await vm.runInContext('controlSchedule(true)', context);
  assert.equal(node('pause-schedule').disabled, true);
  assert.equal(node('resume-schedule').disabled, true);
  node('schedule-control-password').value = 'fixture-password';
  await vm.runInContext('controlSchedule(true)', context);
  assert.equal(sends, 1);
  context.fetch = async (url, options) => {
    assert.equal(url, '/api/schedule/check');
    assert.deepEqual(JSON.parse(options.body), {password:'fixture-password'});
    return {ok:true, json:async () => ({schedule:{paused:true,owner:'installer'},control:{account:'installer',pending:false}})};
  };
  await vm.runInContext('controlSchedule(null)', context);
  assert.equal(node('resume-schedule').disabled, false);
});

test('pending or differently owned schedule cannot be controlled', async () => {
  const {context, node} = await dashboard();
  vm.runInContext("renderScheduleControl({paused:true,owner:'service'}, {account:'installer',pending:false}, null)", context);
  assert.equal(node('pause-schedule').disabled, true);
  assert.equal(node('resume-schedule').disabled, true);
  assert.match(node('schedule-control-account').textContent, /another account/);
  vm.runInContext("renderScheduleControl({paused:true,owner:'installer'}, {account:'installer',pending:true}, null)", context);
  assert.equal(node('resume-schedule').disabled, true);
  assert.equal(node('check-schedule').disabled, false);
});

test('confirmed resume survives failed display refresh without another send', async () => {
  const {context, node} = await dashboard();
  scheduleFixture(context);
  vm.runInContext("overview.health.schedule.paused = true; renderScheduleControl(overview.health.schedule, overview.schedule_control, null); refresh = async () => {throw new Error('fixture read failed');};", context);
  node('schedule-control-password').value = 'fixture-password';
  let sends = 0;
  context.fetch = async () => {sends++; return {ok:true,json:async () => ({schedule:{paused:false,owner:'installer'},control:{account:'installer',pending:false}})};};
  await vm.runInContext('controlSchedule(false)', context);
  assert.equal(sends, 1);
  assert.equal(node('resume-schedule').disabled, true);
  assert.match(node('schedule-control-detail').textContent, /running/);
  assert.match(node('notice').textContent, /Display refresh failed/);
});
