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
    if (!nodes.has(id)) nodes.set(id, {value:'', textContent:'', disabled:false, classList:{toggle() {}}, listeners:{}, attributes:{}, setAttribute(name,value) {this.attributes[name] = value;}, focus() {this.focused = true;}, scrollIntoView() {}, addEventListener(name,handler) {this.listeners[name] = handler;}, children:[], replaceChildren(...children) {this.children = children;}, querySelector() {return null;}, querySelectorAll() {return [];}, reset() {}});
    return nodes.get(id);
  }
  const context = vm.createContext({
    document:{getElementById:node, createElement:tag=>({tag, append(){}})}, location:{hash:''}, URLSearchParams,
    CSS:{escape:value=>value}, PrivacyMarkdown:{render:source=>({source})},
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

test('DNS profiling is a separate setting and shows blocked lookups as DNS evidence', async () => {
  const {context, node} = await dashboard();
  vm.runInContext("renderMonitorSettings({editable:true,pihole_configured:false,detail:''})", context);
  assert.equal(node('dns-profiles').disabled, true);
  vm.runInContext("renderMonitorSettings({editable:true,pihole_configured:true,detail:''})", context);
  assert.equal(node('dns-profiles').disabled, false);
  node('dns-profiles').checked = true;
  node('dns-profiles').listeners.input();
  assert.equal(node('quick-scan').checked, true);
  node('quick-scan').checked = false;
  node('quick-scan').listeners.input();
  assert.equal(node('dns-profiles').checked, false);

  context.document.createElement = tag => ({
    tag, children: [], append(...children) { this.children.push(...children); },
  });
  context.activity = {
    from_at:'2026-01-01T00:00:00Z', until_at:'2026-01-01T00:05:00Z',
    windows:2, partial_windows:1, queries_read:8, queries_available:12,
    groups:[{kind:'advertising', queries:3, distinct:1,
      destinations:[{domain:'ads.example', provider:'Fixture Ads', queries:3, blocked:2}]}],
  };
  const tree = vm.runInContext('renderDnsActivity(activity)', context);
  const rendered = JSON.stringify(tree);
  assert.match(rendered, /Partial coverage in 1 windows/);
  assert.match(rendered, /Counts are sampled DNS lookups, not connections or bytes\. Traffic volume is under How much it sends; which host received those bytes is not observed\./);
  // Destinations are a table: one row per domain under labelled columns.
  const texts = node => [node.textContent, ...(node.children || []).flatMap(texts)].filter(Boolean);
  const rows = tree.children.find(child => child.tag === 'table').children.map(texts);
  assert.deepEqual(rows, [
    ['Destination', 'Queries', 'Reported blocked'],
    ['ads.example', 'Fixture Ads', '3', '2'],
  ]);

  context.silent = {...context.activity, from_at:'2026-01-01T00:00:00Z',
    until_at:'2026-01-01T07:30:00Z', windows:90, partial_windows:0, queries_read:0,
    queries_available:0, groups:[], pihole_silent:true};
  assert.match(JSON.stringify(vm.runInContext('renderDnsActivity(silent)', context)),
    /No DNS queries reached Pi-hole in 90 confirmed checks over 7 hours/);
  context.grouped = {...context.activity, groups:[
    {kind:'measurement', queries:4, distinct:1, destinations:[]},
    {kind:'telemetry', queries:2, distinct:1, destinations:[]},
    {kind:'local', queries:9, distinct:1, destinations:[]}]};
  assert.equal(vm.runInContext('behaviouralQueries(grouped)', context), 6);
  assert.match(JSON.stringify(vm.runInContext('renderDnsActivity(grouped)', context)),
    /Audience measurement & content recognition · 4 queries/);

  context.traffic = {from_at:'2026-01-01T00:00:00Z', until_at:'2026-01-02T00:00:00Z',
    samples:300, to_device_bytes:1234567890, from_device_bytes:2048, resets:2};
  const traffic = JSON.stringify(vm.runInContext('renderTraffic(traffic)', context));
  assert.match(traffic, /1.2 GB to device · 2.0 KB from device/);
  assert.match(traffic, /2 intervals skipped after a counter reset/);
  assert.match(traffic, /counters show volume, not which hosts/i);
  assert.match(traffic, /Totals include local traffic/);
  assert.equal(vm.runInContext('formatBytes(999)', context), '999 B');
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
  vm.runInContext("overview = {health:{schedule:{paused:false, owner:'installer'}}, schedule_control:{available:true,pending:false}, pending_deployment:null}; refresh = async () => {}; renderScheduleControl(overview.health.schedule, overview.schedule_control, null);", context);
}

test('pause sends one service control without a password or redeployment', async () => {
  const {context, node} = await dashboard();
  scheduleFixture(context);
  const requests = [];
  context.fetch = async (url, options) => {
    requests.push({url, body:JSON.parse(options.body)});
    return {ok:true, json:async () => ({schedule:{paused:true,owner:'installer'},control:{available:true,pending:false}})};
  };
  await vm.runInContext('controlSchedule(true)', context);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/schedule/control');
  assert.deepEqual(requests[0].body, {paused:true});
  assert.equal(node('pause-schedule').disabled, true);
  assert.equal(node('resume-schedule').disabled, false);
  assert.match(node('schedule-control-detail').textContent, /paused/);
});

test('lost control reply fences both mutations and check only reads', async () => {
  const {context, node} = await dashboard();
  scheduleFixture(context);
  let sends = 0;
  context.fetch = async () => {sends++; throw new Error('fixture reply lost');};
  await vm.runInContext('controlSchedule(true)', context);
  assert.equal(node('pause-schedule').disabled, true);
  assert.equal(node('resume-schedule').disabled, true);
  await vm.runInContext('controlSchedule(true)', context);
  assert.equal(sends, 1);
  context.fetch = async (url, options) => {
    assert.equal(url, '/api/schedule/check');
    assert.deepEqual(JSON.parse(options.body), {});
    return {ok:true, json:async () => ({schedule:{paused:true,owner:'installer'},control:{available:true,pending:false}})};
  };
  await vm.runInContext('controlSchedule(null)', context);
  assert.equal(node('resume-schedule').disabled, false);
});

test('pending or unavailable control capability disables mutations', async () => {
  const {context, node} = await dashboard();
  vm.runInContext("renderScheduleControl({paused:true}, {available:false,pending:false}, null)", context);
  assert.equal(node('pause-schedule').disabled, true);
  assert.equal(node('resume-schedule').disabled, true);
  vm.runInContext("renderScheduleControl({paused:true}, {available:true,pending:true}, null)", context);
  assert.equal(node('resume-schedule').disabled, true);
  assert.equal(node('check-schedule').disabled, false);
});

test('confirmed resume survives failed display refresh without another send', async () => {
  const {context, node} = await dashboard();
  scheduleFixture(context);
  vm.runInContext("overview.health.schedule.paused = true; renderScheduleControl(overview.health.schedule, overview.schedule_control, null); refresh = async () => {throw new Error('fixture read failed');};", context);
  let sends = 0;
  context.fetch = async () => {sends++; return {ok:true,json:async () => ({schedule:{paused:false,owner:'installer'},control:{available:true,pending:false}})};};
  await vm.runInContext('controlSchedule(false)', context);
  assert.equal(sends, 1);
  assert.equal(node('resume-schedule').disabled, true);
  assert.match(node('schedule-control-detail').textContent, /running/);
  assert.match(node('notice').textContent, /Display refresh failed/);
});


test('view navigation preserves settings drafts and unknown control fences without any requests', async () => {
  const {context, node} = await dashboard();
  let requests = 0;
  context.fetch = async () => {requests++; throw new Error('Navigation must not send requests');};
  node('targets').value = '192.0.2.8';
  node('pihole-password').value = 'unsaved-fixture';
  vm.runInContext("scheduleControlUnknown = true; showView('settings');", context);
  node('tab-evidence').listeners.click();
  node('tab-settings').listeners.click();
  assert.equal(node('view-settings').hidden, false);
  assert.equal(node('view-evidence').hidden, true);
  assert.equal(node('tab-settings').attributes['aria-selected'], 'true');
  assert.equal(node('targets').value, '192.0.2.8');
  assert.equal(node('pihole-password').value, 'unsaved-fixture');
  assert.equal(vm.runInContext('scheduleControlUnknown', context), true);
  assert.equal(requests, 0);
});

test('tabs support keyboard selection, wraparound and a single tab stop', async () => {
  const {node} = await dashboard();
  let prevented = 0;
  node('tab-overview').listeners.keydown({key:'ArrowLeft',preventDefault() {prevented++;}});
  assert.equal(node('tab-settings').focused, true);
  assert.equal(node('tab-settings').tabIndex, 0);
  assert.equal(node('tab-overview').tabIndex, -1);
  node('tab-settings').listeners.keydown({key:'Home',preventDefault() {prevented++;}});
  assert.equal(node('view-overview').hidden, false);
  assert.equal(node('view-settings').hidden, true);
  node('tab-overview').listeners.keydown({key:'End',preventDefault() {prevented++;}});
  assert.equal(node('view-settings').hidden, false);
  assert.equal(prevented, 3);
});


test('reports open in Findings as Markdown without replacing selected collection evidence', async () => {
  const {context, node} = await dashboard();
  const requests = [];
  const source = '# Review\n\n**Keep evidence**';
  vm.runInContext("selected = 'retained-scan';", context);
  node('evidence').children = [{source:'retained evidence'}];
  context.fetch = async (url, options) => {requests.push({url,options}); return {ok:true,json:async()=>({text:source})};};
  await vm.runInContext("showReport('fixture-report')", context);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/reports/fixture-report');
  assert.equal(requests[0].options.method, undefined);
  assert.equal(node('view-findings').hidden, false);
  assert.equal(node('view-evidence').hidden, true);
  assert.equal(node('report-content').children[0].source, source);
  assert.equal(node('evidence').children[0].source, 'retained evidence');
  assert.equal(vm.runInContext('selected',context), 'retained-scan');
});


test('device inventory keeps candidates separate and searches the selected group', async () => {
  const {node} = await dashboard();
  const known = {dataset:{presence:'observed', search:'10.1.2.10 tv'}};
  const silent = {dataset:{presence:'candidate', search:'10.1.2.22'}};
  node('device-cards').querySelectorAll = () => [known, silent];
  node('device-presence').value = 'observed';
  node('device-presence').listeners.change();
  assert.equal(known.hidden, false);
  assert.equal(silent.hidden, true);
  node('device-search').value = '10.1.2.22';
  node('device-search').listeners.input();
  assert.equal(node('device-filter-empty').hidden, false);
  node('device-presence').value = 'candidate';
  node('device-presence').listeners.change();
  assert.equal(known.hidden, true);
  assert.equal(silent.hidden, false);
  assert.equal(node('device-filter-empty').hidden, true);
});

test('whole-subnet selection prepares scope without saving, enabling or starting work', async () => {
  const {context, node} = await dashboard();
  let requests = 0;
  context.fetch = async () => {requests++; throw new Error('Scope selection must not send requests');};
  node('network').value = '192.168.0.0/24';
  node('enabled').checked = false;
  node('use-subnet').listeners.click();
  assert.equal(node('targets').value, '192.168.0.0/24');
  assert.equal(node('enabled').checked, false);
  assert.match(node('settings-detail').textContent, /no scan has started/);
  assert.equal(requests, 0);
  vm.runInContext("renderMonitorSettings({editable:true,pending:false,pihole_configured:true,detail:'Saved settings are active.'})",context);
  assert.match(node('settings-detail').textContent,/not saved/);
  assert.equal(node('targets').value,'192.168.0.0/24');
});

test('research sends the observed identity only to the local controller route and the reviewed model only to search', async () => {
  const {context, node} = await dashboard();
  const calls = [];
  context.fetch = async (url, options) => {
    const body = JSON.parse(options.body);
    calls.push({url,body});
    if (url.endsWith('/product')) return {ok:true,json:async()=>({available:true,product:{
      manufacturer:'Ubiquiti',model:'USW-Pro',firmware:'1.2.3',firmware_updatable:true,
    }})};
    return {ok:true,json:async()=>({total:0,advisories:[],checked_at:'2026-01-01T00:00:00Z'})};
  };
  vm.runInContext("advisoriesAvailable = true; openDeviceResearch({address:'192.168.1.10',name:'Private',identity:{source:'unifi',mac:'02:00:00:00:00:01',device_id:'mac:02:00:00:00:00:01',hostname:'private-device.lan'}});", context);
  assert.equal(node('research-model').value, '');
  assert.equal(calls.length, 0);
  await node('research-unifi').listeners.click();
  assert.match(node('research-unifi-result').textContent, /Firmware 1.2.3/);
  assert.equal(node('research-model').value, 'Ubiquiti USW-Pro');
  await node('research-form').listeners.submit({preventDefault(){}});
  assert.deepEqual(calls, [
    {url:'/api/devices/192.168.1.10/product',body:{device_id:'mac:02:00:00:00:00:01'}},
    {url:'/api/devices/192.168.1.10/advisories',body:{model:'Ubiquiti USW-Pro'}},
  ]);
  vm.runInContext("openDeviceProfile = async () => {};", context);
  await node('research-profile').listeners.click();
  assert.equal(node('profile-model').value, 'Ubiquiti USW-Pro');
  assert.equal(calls.length, 2);
});

test('a reviewed background draft fills only empty profile fields without saving or using a guessed model', async () => {
  const {context, node} = await dashboard();
  const requests = [];
  const entry = {
    request_id:'11111111-1111-4111-8111-111111111111', phase:'review',
    identity:{address:'192.168.1.2'}, model:null,
    draft:{name:'Fixture switch',model_hypothesis:'Unconfirmed X1',purpose:'Switching',expected_services:'HTTPS',notes:'Check firmware'},
  };
  context.fetch = async (url, options) => {
    requests.push({url, method:options.method});
    return {ok:true,json:async()=>entry};
  };
  context.entry = entry;
  node('profile-purpose').value = 'Operator-provided purpose';
  vm.runInContext("selectedResearch = entry; openDeviceProfile = async () => {};", context);
  await node('background-draft-editor').listeners.click();
  assert.equal(node('profile-name').value, 'Fixture switch');
  assert.equal(node('profile-model').value, '');
  assert.equal(node('profile-purpose').value, 'Operator-provided purpose');
  assert.equal(node('profile-notes').value, 'Check firmware');
  assert.deepEqual(requests, [{url:'/api/profile-research/' + entry.request_id + '/editor',method:undefined}]);
});

test('device profile selection follows a confirmed MAC when its former IP is reused', async () => {
  const {context} = await dashboard();
  const profiles = [
    {profile:{profile_id:'first',fields:{device_mac:'02:00:00:00:00:01',addresses:['192.168.1.10']}}},
    {profile:{profile_id:'second',fields:{device_mac:'02:00:00:00:00:02',addresses:['192.168.1.20']}}},
  ];
  context.profiles = profiles;
  vm.runInContext("loadProfiles = async () => profiles; editProfile = async (view) => { selectedProfile = view.profile.profile_id; };", context);
  await vm.runInContext("openDeviceProfile('192.168.1.20','02:00:00:00:00:01')", context);
  assert.equal(vm.runInContext('selectedProfile', context), 'first');
});
