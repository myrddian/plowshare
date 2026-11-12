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
    if (!nodes.has(id)) nodes.set(id, {value:'', textContent:'', disabled:false, classList:{toggle() {}, add() {}, remove() {}}, listeners:{}, attributes:{}, setAttribute(name,value) {this.attributes[name] = value;}, focus() {this.focused = true;}, scrollIntoView() {}, addEventListener(name,handler) {this.listeners[name] = handler;}, children:[], append(...children) {this.children.push(...children);}, replaceChildren(...children) {this.children = children;}, querySelector() {return null;}, querySelectorAll() {return [];}, reset() {}});
    return nodes.get(id);
  }
  const context = vm.createContext({
    document:{getElementById:node, createElement:tag=>({tag, append(){}})}, location:{hash:''}, URLSearchParams,
    CSS:{escape:value=>value}, PrivacyMarkdown:{render:source=>({source})},
    setInterval() {}, setTimeout() {}, fetch:async () => ({ok:false, status:401}),
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
  assert.match(node('pihole-saved').textContent, /Waiting to apply/);
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
  assert.match(rendered, /Partial coverage in 1 window\. Pi-hole reported 12 matching lookups/);
  assert.match(rendered, /Counts are sampled DNS lookups, not connections or bytes\. Traffic volume is under How much it sends; which host received those bytes is not observed\./);
  // Destinations are a table: one row per domain under labelled columns.
  const texts = node => [node.textContent, ...(node.children || []).flatMap(texts)].filter(Boolean);
  const rows = tree.children.find(child => child.tag === 'table').children.map(texts);
  // A domain is split into labels so it can wrap only after a dot.
  assert.deepEqual(rows, [
    ['Destination', 'Lookups', 'Reported blocked'],
    ['ads.', 'example', 'Fixture Ads', '3', '2'],
  ]);

  context.silent = {...context.activity, from_at:'2026-01-01T00:00:00Z',
    until_at:'2026-01-01T07:30:00Z', windows:90, partial_windows:0, queries_read:0,
    queries_available:0, groups:[], pihole_silent:true};
  assert.match(JSON.stringify(vm.runInContext('renderDnsActivity(silent)', context)),
    /No DNS lookups reached Pi-hole in 90 confirmed checks over 7 hours/);
  context.grouped = {...context.activity, groups:[
    {kind:'measurement', queries:4, distinct:1, destinations:[]},
    {kind:'telemetry', queries:2, distinct:1, destinations:[]},
    {kind:'local', queries:9, distinct:1, destinations:[]}]};
  assert.equal(vm.runInContext('behaviouralQueries(grouped)', context), 6);
  assert.match(JSON.stringify(vm.runInContext('renderDnsActivity(grouped)', context)),
    /Audience measurement and content recognition · 4 lookups/);

  context.traffic = {from_at:'2026-01-01T00:00:00Z', until_at:'2026-01-02T00:00:00Z',
    samples:300, to_device_bytes:1234567890, from_device_bytes:2048, resets:2};
  const traffic = JSON.stringify(vm.runInContext('renderTraffic(traffic)', context));
  assert.match(traffic, /1.2 GB to device · 2.0 KB from device/);
  assert.match(traffic, /2 intervals skipped after a counter reset/);
  assert.match(traffic, /“to device” is what the network sent the device/);
  context.traffic.resets = 1;
  assert.match(JSON.stringify(vm.runInContext('renderTraffic(traffic)', context)), /1 interval skipped after a counter reset/);
  assert.match(traffic, /counters show volume, not which hosts/i);
  assert.match(traffic, /Totals include local traffic/);
  assert.equal(vm.runInContext('formatBytes(999)', context), '999 B');
});

test('saved Pi-hole tuning is shown read-only and the password source is named, never its value', async () => {
  const {context, node} = await dashboard();
  context.document.createElement = tag => ({tag, children:[], append(...children) {this.children.push(...children);}});
  const tuning = {lookback_seconds:7200, query_limit:128, max_identity_age_seconds:86400, timeout_seconds:2.5};
  context.settings = {editable:true, pending:false, pihole_configured:true, detail:'',
    pihole:{password_source:'environment', password_environment:'FIXTURE_PIHOLE_PASSWORD', ...tuning}};
  vm.runInContext('renderMonitorSettings(settings)', context);
  const saved = node('pihole-saved').textContent;
  assert.match(saved, /environment variable FIXTURE_PIHOLE_PASSWORD/);
  assert.doesNotMatch(saved, /stored/);
  const facts = node('pihole-tuning');
  assert.equal(facts.hidden, false);
  assert.deepEqual(facts.children.map(item => [item.tag, item.textContent]), [
    ['dt', 'DNS lookup window'], ['dd', '2 hours'],
    ['dt', 'Lookups read per device'], ['dd', '128 lookups'],
    ['dt', 'Maximum identity age'], ['dd', '1 day'],
    ['dt', 'Request timeout'], ['dd', `${new Intl.NumberFormat().format(2.5)} seconds`],
  ]);
  context.settings.pihole = {password_source:'file', password_environment:null, ...tuning};
  vm.runInContext('renderMonitorSettings(settings)', context);
  assert.match(node('pihole-saved').textContent, /password is stored in a private file on this collector/);
  vm.runInContext("renderMonitorSettings({editable:true,pending:false,pihole_configured:false,pihole:null,detail:''})", context);
  assert.match(node('pihole-saved').textContent, /no Pi-hole connection is configured yet/);
  assert.equal(node('pihole-tuning').hidden, true);
  assert.deepEqual(node('pihole-tuning').children, []);
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
  vm.runInContext("renderPiHoleSaved({password_source:'file', password_environment:null})", context);
  node('pihole-password').value = 'fixture-invalid';
  context.fetch = async () => ({ok:true, json:async () => ({saved:false, connected:false, issues:['Authentication failed']})});
  await vm.runInContext('piholeConnection(false)', context);
  assert.match(node('pihole-saved').textContent, /password is stored in a private file/);
  assert.match(node('pihole-detail').textContent, /Connection test failed/);
});

test('unknown save outcome does not claim the password was saved', async () => {
  const {context, node} = await dashboard();
  node('pihole-password').value = 'fixture-secret';
  context.fetch = async () => {throw new Error('fixture disconnect');};
  await vm.runInContext('piholeConnection(true)', context);
  assert.match(node('pihole-detail').textContent, /Save was not confirmed/);
  assert.equal(node('pihole-password').value, '');
  assert.doesNotMatch(node('pihole-saved').textContent, /password is stored/);
});

function scheduleFixture(context) {
  vm.runInContext("overview = {health:{schedule:{paused:false, owner:'installer'}}, schedule_control:{available:true,pending:false}, pending_deployment:null}; refresh = async () => {}; renderScheduleControl(overview.health.schedule, overview.schedule_control, null);", context);
}

test('the live schedule is written in words and preselected without changing it', async () => {
  const {context, node} = await dashboard();
  const run = source => vm.runInContext(source, context);
  assert.equal(run("scheduleLabel('0 */15 * * * *')"), 'Every 15 minutes');
  assert.equal(run("scheduleLabel('0 0 * * * *')"), 'Hourly');
  assert.equal(run("scheduleLabel('0 0 9 * * *')"), 'Daily at 09:00');
  assert.equal(run("scheduleLabel('0 5 */2 * * *')"), 'Custom: 0 5 */2 * * *');
  context.document.createElement = tag => ({tag, children:[], classList:{toggle() {}}, setAttribute() {}, addEventListener() {},
    append(...children) {this.children.push(...children);}, replaceChildren(...children) {this.children = children;}});
  const texts = list => list.children.map(item => item.textContent);
  const overviewWith = schedule => ({
    health:{connected:true, recovery:[], checklist:[], runs:[], schedule, pihole:'Optional: not configured', agents:null,
      checked_at:'2026-11-12T08:00:00Z', detail:'', last_contact:null, last_scan:null, credential_expires_at:null},
    devices:[], candidates:[], findings:[], quick_presence:null, schedule_control:{available:true, pending:false}, pending_deployment:null,
    preferences:{automatic_investigations:false, daily_limit:3, cooldown_minutes:60, browser_notifications:false},
  });
  const schedule = {name:'network_scan', cron:'0 0 * * * *', zone:'Europe/London', paused:false, next_at:null};
  context.value = overviewWith(schedule);
  run('overview = value; renderOverview(value)');
  // System health, the Overview schedule panel and Settings agree.
  const health = texts(node('health'));
  assert.equal(health[health.indexOf('Schedule') + 1], 'Hourly · Europe/London');
  assert.deepEqual(texts(node('schedule-facts')), ['Frequency', 'Hourly', 'Time zone', 'Europe/London']);
  assert.equal(node('frequency').value, 'hourly');
  assert.equal(node('frequency-current').hidden, true);
  assert.equal(node('frequency-current').disabled, true);

  // A draft choice survives a refresh while the live schedule is unchanged.
  node('frequency').value = 'daily';
  run('renderOverview(value)');
  assert.equal(node('frequency').value, 'daily');

  // A schedule that is not a preset keeps its own value: saving unchanged
  // reviews the live cron, not "Every 15 minutes".
  context.value = overviewWith({...schedule, cron:'0 5 */2 * * *'});
  run('overview = value; renderOverview(value)');
  assert.equal(node('frequency').value, 'current');
  assert.equal(node('frequency-current').hidden, false);
  assert.equal(node('frequency-current').disabled, false);
  assert.equal(node('frequency-current').textContent, 'Custom (current): 0 5 */2 * * *');
  assert.deepEqual(texts(node('schedule-facts')), ['Frequency', 'Custom: 0 5 */2 * * *', 'Time zone', 'Europe/London']);
  const requests = [];
  node('administrator-password').value = 'fixture-admin';
  context.fetch = async (url, options) => {requests.push({url, body:JSON.parse(options.body)}); throw new Error('fixture stop');};
  await node('schedule-form').listeners.submit({preventDefault() {}});
  assert.deepEqual(requests.map(item => [item.url, item.body.preset]), [['/api/schedule/review', 'current']]);

  context.value = overviewWith(null);
  run('overview = value; renderOverview(value)');
  assert.deepEqual(texts(node('schedule-facts')), ['Frequency', 'Unavailable']);
});

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

test('a profile opened from the knowledge base is edited under its own device, never another', async () => {
  const {context, node} = await dashboard();
  const tv = {address:'192.0.2.10', name:'Living room TV', identity:{source:'unifi', mac:'02:00:00:00:00:0a'}};
  const speaker = {address:'192.0.2.20', name:'Kitchen speaker', identity:{source:'unifi', mac:'02:00:00:00:00:0b'}};
  const profile = (name, fields) => ({profile:{profile_id:name, fields:{name, owner:'', model:'', purpose:'',
    expected_services:'', notes:'', addresses:[], issues:[], device_mac:null, ...fields}}, revision:'r-' + name, versions:[], evidence:[]});
  context.profileViews = {
    speaker:profile('Speaker', {device_mac:speaker.identity.mac, addresses:['192.0.2.20']}),
    tvByAddress:profile('TV notes', {addresses:['192.0.2.10']}),
    garage:profile('Garage camera', {device_mac:'02:00:00:00:00:0c', addresses:['192.0.2.30']}),
  };
  context.overview = {devices:[tv, speaker]};
  context.fetch = async url => ({ok:true, json:async () => url === '/api/monitoring' ? {targets:[]} : {}});
  vm.runInContext(`overview = globalThis.overview;
    profileReceipts = async () => {}; renderIssueLinks = async () => {};
    renderDeviceView = device => { renderedDevice = device.address; }; loadDeviceAnalysis = () => {};
    advisoryLookup = () => ({});
    var renderedDevice = null;`, context);
  vm.runInContext("openDevice('192.0.2.10')", context);
  assert.equal(node('pane-title').textContent, 'Living room TV');

  // Bound by MAC to another monitored device: the pane switches to that device.
  await vm.runInContext('editProfile(profileViews.speaker)', context);
  assert.equal(vm.runInContext('selectedDevice', context), '192.0.2.20');
  assert.equal(vm.runInContext('renderedDevice', context), '192.0.2.20');
  assert.equal(node('pane-title').textContent, 'Kitchen speaker');
  assert.equal(node('profile-form').hidden, false);
  assert.equal(node('pane-back').hidden, false);

  // An address-only profile of the open device stays under that device.
  vm.runInContext("closeEditors(); openDevice('192.0.2.10')", context);
  await vm.runInContext('editProfile(profileViews.tvByAddress)', context);
  assert.equal(vm.runInContext('selectedDevice', context), '192.0.2.10');
  assert.equal(node('pane-title').textContent, 'Living room TV');

  // Not a monitored device: the view is titled by the profile, and the
  // previous device's facts are not kept beside it.
  await vm.runInContext('editProfile(profileViews.garage)', context);
  assert.equal(vm.runInContext('selectedDevice', context), null);
  assert.equal(node('pane-title').textContent, 'Profile: Garage camera');
  assert.equal(node('device-view').hidden, true);
  assert.equal(node('pane-back').hidden, true);
  assert.deepEqual(node('device-identity').children, []);
  assert.equal(node('profile-form').hidden, false);
  assert.equal(node('side-pane').hidden, false);

  // A new profile from the knowledge base has no device either.
  vm.runInContext("closeEditors(); openDevice('192.0.2.20'); crypto = {randomUUID: () => 'new-profile'}", context);
  await vm.runInContext('editProfile(null)', context);
  assert.equal(vm.runInContext('selectedDevice', context), null);
  assert.equal(node('pane-title').textContent, 'New device profile');
});

function assessmentDom(context) {
  context.document.createElement = tag => ({
    tag, children: [], listeners: {}, append(...children) { this.children.push(...children); },
    replaceChildren(...children) { this.children = children; },
    addEventListener(name, handler) { this.listeners[name] = handler; },
  });
}
const assessmentTexts = node => [node.textContent, ...(node.children || []).flatMap(assessmentTexts)].filter(Boolean);
const assessmentButtons = nodes => nodes.flatMap(function walk(node) {
  return [...(node.tag === 'button' ? [node] : []), ...(node.children || []).flatMap(walk)];
});
const assessmentBase = {
  device_id:'mac:02:00:00:00:00:0a', pending:null, request_id:'11111111-1111-4111-8111-111111111111',
  run_id:'orc_fixture_1', report:null, requested_at:'2026-11-11T09:00:00Z', decided_at:null,
  review:null, concerns:[], detail:null, can_assess:false, can_accept:false, can_dismiss:false, assess_note:null,
};

test('the assessment block renders each state with plain actions', async () => {
  const {context} = await dashboard();
  assessmentDom(context);
  const render = (view, report = null) => {
    context.view = {...assessmentBase, ...view}; context.report = report;
    const nodes = vm.runInContext('renderAssessment(view, report)', context);
    return {text: nodes.flatMap(assessmentTexts).join(' | '), buttons: Array.from(assessmentButtons(nodes), button => button.textContent), nodes};
  };
  const idle = render({state:'idle', request_id:null, run_id:null, requested_at:null, can_assess:true});
  assert.match(idle.text, /Not assessed/);
  assert.deepEqual(idle.buttons, ['Assess this device']);
  const blocked = render({state:'idle', request_id:null, run_id:null, assess_note:'Another device’s assessment is running. One runs at a time.'});
  assert.deepEqual(blocked.buttons, []);
  assert.match(blocked.text, /Another device’s assessment is running/);

  const starting = render({state:'running', pending:'admission', run_id:null});
  assert.match(starting.text, /not sent again/);
  assert.deepEqual(starting.buttons, ['Check status']);
  const running = render({state:'running'});
  assert.match(running.text, /Assessment running/);
  assert.match(running.text, /orc_fixture_1/);
  assert.deepEqual(running.buttons, []);

  const review = render({state:'review', report:'22222222-2222-4222-8222-222222222222', review:'accepted', can_accept:true, can_dismiss:true}, '# Living room TV: privacy assessment');
  assert.match(review.text, /Awaiting your review/);
  assert.deepEqual(review.buttons, ['Accept and make final', 'Dismiss without finalising']);
  assert.ok(JSON.stringify(review.nodes).includes('# Living room TV: privacy assessment'));
  const refused = render({state:'review', report:'22222222-2222-4222-8222-222222222222', review:'accepted', can_accept:true, can_dismiss:true,
    detail:'Plowshare refused finalising this assessment report (FORBIDDEN): Write access is required.'}, '# Report');
  assert.match(refused.text, /Write access is required/);
  assert.deepEqual(refused.buttons, ['Accept and make final', 'Dismiss without finalising', 'Check status']);
  const accepting = render({state:'review', pending:'accept', report:'22222222-2222-4222-8222-222222222222', review:'accepted'}, '# Report');
  assert.match(accepting.text, /Accept sent, not yet confirmed/);
  assert.deepEqual(accepting.buttons, ['Check status']);

  const accepted = render({state:'accepted', report:'22222222-2222-4222-8222-222222222222', review:'accepted', decided_at:'2026-11-11T10:00:00Z', can_assess:true}, '# Report');
  assert.match(accepted.text, /Accepted/);
  assert.match(accepted.text, /final in Plowshare/);
  assert.deepEqual(accepted.buttons, ['Assess again']);
  const dismissed = render({state:'dismissed', report:'22222222-2222-4222-8222-222222222222', review:'accepted', decided_at:'2026-11-11T10:00:00Z', can_assess:true});
  assert.match(dismissed.text, /Dismissed/);
  assert.ok(!JSON.stringify(dismissed.nodes).includes('# Report'));
  assert.deepEqual(dismissed.buttons, ['Assess again']);

  const failed = render({state:'failed', detail:'The run reached its model-call or budget cap. No report was written.', can_assess:true});
  assert.match(failed.text, /Last assessment failed/);
  assert.match(failed.text, /budget cap/);
  assert.match(failed.text, /orc_fixture_1/);
  assert.deepEqual(failed.buttons, ['Retry assessment']);
  // The second agent's check never reads as the operator's own Accept.
  assert.match(review.text, /Checked by a second agent: passed\./);
  assert.doesNotMatch(review.text, /verdict|accepted/i);
  const corrected = render({state:'accepted', report:'22222222-2222-4222-8222-222222222222', review:'needs_corrections', decided_at:'2026-11-11T10:00:00Z'}, '# Report');
  assert.match(corrected.text, /Checked by a second agent: passed after one round of corrections/);
});

test('Accept waits, disabled, while Plowshare processes the report and returns by polling', async () => {
  const {context} = await dashboard();
  assessmentDom(context);
  const report = '22222222-2222-4222-8222-222222222222';
  let view = {...assessmentBase, state:'review', report, review:'accepted', can_accept:false, can_dismiss:true, processing:true};
  const requests = [];
  context.fetch = async url => {
    requests.push(url);
    if (url.endsWith('/report')) return {ok:true, json:async () => ({revision:report, text:'# Report'})};
    return {ok:true, json:async () => view};
  };
  context.block = context.document.createElement('div');
  vm.runInContext("selectedDevice = '192.0.2.10'; deviceAssessment = {address:'192.0.2.10', deviceId:'mac:02:00:00:00:00:0a', block, view:null, report:null};", context);
  await vm.runInContext('reloadAssessment()', context);
  const shown = () => {
    const nodes = context.block.children;
    return {text: nodes.flatMap(assessmentTexts).join(' | '),
      buttons: Array.from(assessmentButtons(nodes), button => [button.textContent, Boolean(button.disabled)])};
  };
  let rendered = shown();
  assert.match(rendered.text, /Report is being processed…/);
  assert.deepEqual(rendered.buttons, [['Accept and make final', true], ['Dismiss without finalising', false]]);
  // The refresh keeps reading the state while processing.
  const before = requests.length;
  view = {...view, processing:false, can_accept:true};
  await vm.runInContext('pollAssessment()', context);
  assert.ok(requests.length > before);
  rendered = shown();
  assert.doesNotMatch(rendered.text, /being processed/);
  assert.deepEqual(rendered.buttons, [['Accept and make final', false], ['Dismiss without finalising', false]]);
  const count = requests.length;
  await vm.runInContext('pollAssessment()', context);
  assert.equal(requests.length, count);

  // A finalise refused while processing is a short notice, not an error.
  view = {...view, processing:true, can_accept:false,
    detail:'Plowshare is still processing this report, so it was not made final. Accept returns when processing is done; try again shortly.'};
  await vm.runInContext('reloadAssessment()', context);
  const notices = [];
  (function walk(node) { if (node.tag === 'p' && /still processing/.test(node.textContent || '')) notices.push(node.className); (node.children || []).forEach(walk); })({children:context.block.children});
  assert.deepEqual(notices, ['hint']);
});

test('assessment actions post once, reread the view and poll only while running', async () => {
  const {context} = await dashboard();
  assessmentDom(context);
  const requests = [];
  let view = {...assessmentBase, state:'idle', request_id:null, run_id:null, can_assess:true};
  context.fetch = async (url, options) => {
    requests.push({url, method:options.method || 'GET', body:options.body});
    if (url.endsWith('/assess')) view = {...assessmentBase, state:'running'};
    if (url.endsWith('/accept')) return {ok:false, status:409, json:async () => ({error:'Plowshare refused finalising this assessment report.'})};
    if (url.endsWith('/report')) return {ok:true, json:async () => ({revision:view.report, text:'# Living room TV: privacy assessment'})};
    return {ok:true, json:async () => view};
  };
  context.block = context.document.createElement('div');
  vm.runInContext("selectedDevice = '192.0.2.10'; deviceAssessment = {address:'192.0.2.10', deviceId:'mac:02:00:00:00:00:0a', block, view:null, report:null};", context);
  await vm.runInContext('reloadAssessment()', context);
  assert.deepEqual(requests.map(item => item.url), ['/api/devices/mac%3A02%3A00%3A00%3A00%3A00%3A0a/assessment']);
  await vm.runInContext("assessmentAction('assess')", context);
  assert.deepEqual(requests.slice(1).map(item => [item.method, item.url, item.body]), [
    ['POST', '/api/devices/mac%3A02%3A00%3A00%3A00%3A00%3A0a/assessment/assess', '{}'],
    ['GET', '/api/devices/mac%3A02%3A00%3A00%3A00%3A00%3A0a/assessment', undefined],
  ]);
  await vm.runInContext('pollAssessment()', context);
  assert.equal(requests.length, 4);
  view = {...assessmentBase, state:'review', report:'22222222-2222-4222-8222-222222222222', review:'accepted', can_accept:true, can_dismiss:true};
  await vm.runInContext('pollAssessment()', context);
  assert.equal(requests.at(-1).url, '/api/devices/mac%3A02%3A00%3A00%3A00%3A00%3A0a/assessment/report');
  const count = requests.length;
  await vm.runInContext('pollAssessment()', context);
  assert.equal(requests.length, count);
  await vm.runInContext("assessmentAction('accept')", context);
  assert.match(context.document.getElementById('notice').textContent, /refused finalising/);
  assert.equal(requests.filter(item => item.url.endsWith('/accept')).length, 1);
  assert.equal(requests.at(-1).url, '/api/devices/mac%3A02%3A00%3A00%3A00%3A00%3A0a/assessment');
});

test('work that spends model calls is confirmed in place before it is sent', async () => {
  const {context} = await dashboard();
  assessmentDom(context);
  let sent = 0;
  context.send = async () => {sent += 1;};
  const group = vm.runInContext("confirmedAction('Assess this device', undefined, 'Confirm assessment', 'This uses model calls.', send)", context);
  const labels = () => group.children.map(child => child.textContent);
  assert.deepEqual(labels(), ['Assess this device']);
  const start = group.children[0];
  start.focus = () => {};
  context.document.createElement = (create => tag => ({...create(tag), focus() {}}))(context.document.createElement);
  start.listeners.click();
  // The cost is stated above the button that sends the work.
  assert.deepEqual(labels(), ['This uses model calls.', 'Confirm assessment', 'Cancel']);
  assert.equal(sent, 0);
  group.children[2].listeners.click();
  assert.deepEqual(labels(), ['Assess this device']);
  assert.equal(sent, 0);
  start.listeners.click();
  await group.children[1].listeners.click();
  assert.equal(sent, 1);
});

test('an action message survives a refresh and stays until it is dismissed', async () => {
  const {context, node} = await dashboard();
  vm.runInContext("notice('Scan requested.'); condition('Collector needs attention.', true); condition('');", context);
  assert.equal(node('notice').textContent, 'Scan requested.');
  assert.equal(node('notice-bar').hidden, false);
  assert.equal(node('condition').textContent, '');
  node('notice-dismiss').listeners.click();
  assert.equal(node('notice').textContent, '');
  assert.equal(node('notice-bar').hidden, true);
  const source = fs.readFileSync(path.join(__dirname, '../src/plowshare_privacy/web/app.js'), 'utf8');
  const start = source.indexOf('async function refresh()');
  // The periodic refresh writes the standing condition only.
  assert.doesNotMatch(source.slice(start, source.indexOf('await pollAssessment()', start)), /\bnotice\(/);
});

test('counts, dates and state words are written one way', async () => {
  const {context} = await dashboard();
  const run = source => vm.runInContext(source, context);
  assert.equal(run("plural(1, 'window', 'windows')"), '1 window');
  assert.equal(run("plural(0, 'window', 'windows')"), '0 windows');
  assert.equal(run("plural(1790, 'lookup', 'lookups')"), `${new Intl.NumberFormat().format(1790)} lookups`);
  assert.equal(run("statusLabel('potentially_affected')"), 'Potentially affected');
  assert.equal(run("statusLabel('draft')"), 'Draft');
  assert.equal(run("formatWhen('')"), '');
  assert.equal(run("formatWhen('not a date')"), 'not a date');
  // A date-only value is a calendar day in every time zone.
  assert.equal(run("formatDay('2026-02-03')"), new Intl.DateTimeFormat(undefined, {dateStyle:'medium', timeZone:'UTC'}).format(new Date('2026-02-03')));
  assert.doesNotMatch(run("formatWhen('2026-11-12T08:38:15Z')"), /:15/);
});
