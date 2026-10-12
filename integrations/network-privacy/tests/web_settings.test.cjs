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
