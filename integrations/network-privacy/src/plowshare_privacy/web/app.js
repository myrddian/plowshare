'use strict';

// The web bearer exists only in this tab's memory. All untrusted evidence is text.
let bearer = '';
let authenticated = false;
let selected = null;
let refreshPending = false;
let settingsLoaded = false;
let settingsPending = false;
let overview = null;
let preferencesLoaded = false;
let scheduleDraft = null;
let notified = null;
let deviceSignature = null;
let findingSignature = null;
let profileEdit = null;
let profileBlocked = false;
const byId = (name) => document.getElementById(name);
const phases = {queued:'Queued',collected:'Collected',uploading:'Upload outcome pending',uploaded:'Evidence retained',publishing:'Publication outcome pending',done:'Evidence published'};

function notice(message, error = false) {
  byId('notice').textContent = message;
  byId('notice').classList.toggle('error', error);
}
function element(tag, content, className) {
  const node = document.createElement(tag);
  if (content !== undefined) node.textContent = content;
  if (className) node.className = className;
  return node;
}
async function api(path, options = {}) {
  const response = await fetch(path, { ...options, headers: {...(bearer ? {Authorization: `Bearer ${bearer}`} : {}), 'Content-Type': 'application/json'}, cache:'no-store'});
  if (!response.ok) {
    if (response.status === 401) throw new Error('Dashboard token was refused. Reconnect with the configured web token.');
    const failure = await response.json().catch(() => null);
    if (failure?.error) throw new Error(failure.error);
    if (response.status === 409) throw new Error('Finish or reconcile the current work before trying this action.');
    throw new Error(`Request unavailable (${response.status}). Existing evidence is preserved.`);
  }
  return response.json();
}
function listItems(parent, title, values) {
  parent.append(element('h3', title));
  if (!values.length) {parent.append(element('p', 'None recorded.')); return;}
  const list = element('ul');
  values.forEach(value => list.append(element('li', value)));
  parent.append(list);
}
async function showEvidence(scanId) {
  const result = await api(`/api/scans/${encodeURIComponent(scanId)}`);
  selected = scanId;
  const evidence = result.evidence;
  const panel = byId('evidence');
  panel.replaceChildren();
  byId('selection').textContent = evidence.mode === 'fixture' ? 'Synthetic fixture' : 'Collected observations';
  panel.append(element('p', `Observed ${new Date(evidence.snapshot.observed_at).toLocaleString()}`));
  const devices = evidence.snapshot.devices || [];
  if (devices.length) {
    panel.append(element('h3', 'Observed devices'));
    const table = element('table', undefined, 'identity-table');
    const header = element('tr');
    ['Device', 'Address', 'MAC / identity', 'Observed association'].forEach(title => header.append(element('th', title)));
    table.append(header);
    devices.forEach(device => {
      const row = element('tr');
      [device.label || device.hostname || 'Unnamed device', device.address, device.mac || device.device_id, device.last_seen ? `${device.source} · ${new Date(device.last_seen).toLocaleString()}` : 'Configured address; MAC not observed'].forEach(value => row.append(element('td', value)));
      table.append(row);
    });
    panel.append(table);
  }
  if (evidence.snapshot.dns_window) {
    const window = evidence.snapshot.dns_window;
    panel.append(element('p', `Pi-hole DNS window: ${new Date(window.from_at).toLocaleString()} – ${new Date(window.until_at).toLocaleString()}. Read ${window.queries_read} of ${window.queries_available} matching recorded queries${window.complete ? '.' : '; coverage is incomplete. See gaps below.'}`));
  }
  listItems(panel, 'Changes against the previous collection', evidence.changes);
  listItems(panel, 'Coverage & gaps', evidence.issues);
  panel.append(element('h3', 'Evidence revision'));
  panel.append(element('p', result.revision || 'Not yet retained in Plowshare.'));
  panel.append(element('h3', 'Observations'));
  panel.append(element('pre', JSON.stringify(evidence.snapshot, null, 2)));
  if (overview) {
    const scan = (await api('/api/scans')).find(value => value.scan_id === scanId);
    if (scan?.investigation) panel.append(element('p', `Investigation: ${scan.investigation.reason}`));
    const manual = overview.investigations.find(value => value.scan_id === scanId && value.phase !== 'refused');
    if (manual) panel.append(element('p', `Explicit investigation: ${manual.phase}${manual.run_id ? ` · ${manual.run_id}` : ' · inspect its retained receipt'}`));
    else if (scan?.phase === 'done' && scan.investigation?.requested === false) {
      const investigate = element('button', 'Investigate this evidence');
      investigate.addEventListener('click', async () => {
        if (investigate.dataset.reviewed !== 'yes') {
          investigate.dataset.reviewed = 'yes'; investigate.textContent = 'Confirm one explicit investigation';
          panel.append(element('p', 'This starts one investigation of the retained evidence and may incur model costs beyond the automatic limit. Confirm above to submit it.'));
          return;
        }
        investigate.disabled = true;
        try {await api('/api/investigations', {method:'POST', body:JSON.stringify({scan_id:scanId, request_id:crypto.randomUUID()})}); await refresh(); await showEvidence(scanId);} catch (error) {notice(error.message, true);}
      });
      panel.append(investigate);
    }
  }
  const reports = await api('/api/reports');
  const associated = reports.filter(value => (value.inputs || []).includes(result.revision));
  panel.append(element('h3', 'Reports citing this evidence'));
  if (!associated.length) panel.append(element('p', 'No retained report cites this revision yet.'));
  for (const report of associated) {
    const button = element('button', report.title, 'entry');
    button.addEventListener('click', () => showReport(report.revision).catch(error => notice(error.message, true)));
    panel.append(button);
  }
}
async function showReport(revision) {
  const result = await api(`/api/reports/${encodeURIComponent(revision)}`);
  selected = null;
  byId('selection').textContent = 'Retained agent report';
  byId('evidence').replaceChildren(element('pre', result.text));
  byId('evidence').scrollIntoView({behavior:'smooth', block:'nearest'});
}
function renderCollections(scans) {
  const container = byId('collections');
  container.replaceChildren();
  if (!scans.length) {container.append(element('p', 'No collections yet. Request a scan or wait for the configured schedule.', 'empty')); return;}
  scans.forEach(scan => {
    const button = element('button', undefined, `entry${selected === scan.scan_id ? ' selected' : ''}`);
    button.type = 'button';
    const row = element('span', undefined, 'row');
    row.append(element('span', new Date(scan.occurred_at).toLocaleString()), element('span', phases[scan.phase] || scan.phase, 'badge'));
    button.append(row, element('small', `${scan.changes.length} comparison entries · ${scan.issues.length} coverage notes${scan.mode === 'fixture' ? ' · fixture' : ''}`));
    button.disabled = scan.phase === 'queued';
    button.addEventListener('click', () => {showEvidence(scan.scan_id).then(() => renderCollections(scans)).catch(error => notice(error.message, true));});
    container.append(button);
  });
}
function renderReports(reports) {
  const container = byId('reports');
  container.replaceChildren();
  if (!reports.length) {container.append(element('p', 'No retained investigations yet. Collection and agent completion are separate stages.', 'empty')); return;}
  reports.forEach(report => {
    const button = element('button', undefined, 'entry');
    const row = element('span', undefined, 'row');
    row.append(element('span', report.title), element('span', report.status, 'badge'));
    button.append(row);
    button.addEventListener('click', () => {showReport(report.revision).catch(error => notice(error.message, true));});
    container.append(button);
  });
}
async function refresh() {
  if ((!bearer && !authenticated) || refreshPending) return;
  refreshPending = true;
  byId('refresh').disabled = true;
  try {
    const [status, scans, reportResult] = await Promise.all([api('/api/status'), api('/api/scans'), api('/api/reports').then(value => ({value}), error => ({error}))]);
    if (!Array.isArray(scans) || typeof status.state !== 'string' || !Array.isArray(status.requests)) throw new Error('Unexpected dashboard response.');
    byId('mode').textContent = status.collection_enabled === false ? 'Collection not configured' : status.mode === 'fixture' ? 'Synthetic fixture mode' : 'TCP collection mode';
    byId('device-profiles').hidden = !status.profiles_available;
    byId('project').textContent = status.project;
    byId('collector').textContent = status.collector;
    byId('state').textContent = status.state === 'attention_required' ? 'Needs attention' : status.state.replaceAll('_', ' ');
    byId('detail').textContent = status.detail;
    byId('change-count').textContent = scans.length ? scans[0].changes.filter(change => !change.startsWith('Baseline')).length : '—';
    byId('report-count').textContent = reportResult.error ? 'Unavailable' : reportResult.value.length;
    byId('scan').disabled = status.state === 'attention_required' || status.collection_enabled === false;
    renderCollections(scans);
    if (reportResult.error) byId('reports').replaceChildren(element('p', 'Retained agent reports are temporarily unavailable. Local evidence remains readable.', 'empty'));
    else if (Array.isArray(reportResult.value)) renderReports(reportResult.value);
    else throw new Error('Unexpected reports response.');
    if (status.settings_available && !settingsLoaded) await loadSettings();
    if (status.settings_available && !settingsPending) {
      const settings = await api('/api/monitoring');
      byId('save-monitoring').disabled = !settings.editable;
      byId('settings-detail').textContent = settings.detail;
    }
    try {overview = await api('/api/overview'); renderOverview(overview);} catch (error) {byId('health').replaceChildren(element('p', error.message));}
    byId('dashboard').hidden = false;
    byId('connect').hidden = true;
    const unknown = status.requests.find(request => request.state === 'pending');
    if (unknown) notice(`Request ${unknown.request_id} needs reconciliation. It has not been resent.`, true);
    else if (reportResult.error) notice(reportResult.error.message, true);
    else notice(status.state === 'attention_required' || status.collection_enabled === false ? status.detail : 'Evidence is retained before an investigation is requested.', status.state === 'attention_required');
  } finally {refreshPending = false; byId('refresh').disabled = false;}
}
byId('connect').addEventListener('submit', event => {
  event.preventDefault();
  bearer = byId('token').value;
  byId('token').value = '';
  notice('Reading collector status and retained evidence…');
  refresh().catch(error => {bearer = ''; notice(error.message, true);});
});
byId('refresh').addEventListener('click', () => {refresh().catch(error => notice(error.message, true));});
byId('disconnect').addEventListener('click', async () => {
  try {await api('/api/session/logout', {method:'POST', body:'{}'});} catch (error) {notice(error.message, true); return;}
  profileEdit = null; profileBlocked = false; byId('profile-form').hidden = true; byId('profile-markdown').hidden = true;
  for (const id of ['profile-name','profile-owner','profile-model','profile-purpose','profile-expected','profile-notes']) byId(id).value = '';
  for (const id of ['profile-records','profile-intents','profile-history','profile-markdown','profile-addresses']) byId(id).replaceChildren();
  bearer = ''; authenticated = false; selected = null; settingsLoaded = false; preferencesLoaded = false; overview = null; scheduleDraft = null; deviceSignature = null; findingSignature = null; notified = null;
  for (const id of ['health','checklist','recovery','device-cards','moves','findings']) byId(id).replaceChildren();
  for (const id of ['pihole-origin','pihole-password','administrator-password','zone']) byId(id).value = '';
  byId('schedule-draft').hidden = true;
  byId('monitoring').hidden = true; byId('devices').replaceChildren();
  byId('network').value = ''; byId('targets').value = ''; byId('ports').value = ''; byId('device-labels').value = '';
  byId('dashboard').hidden = true; byId('connect').hidden = false;
  byId('collections').replaceChildren(); byId('reports').replaceChildren(); byId('evidence').replaceChildren();
  byId('mode').textContent = 'Not connected'; notice('Disconnected. Private evidence has been cleared from view.');
});
byId('scan').addEventListener('click', async () => {
  byId('scan').disabled = true;
  try {
    if (!globalThis.crypto?.randomUUID) throw new Error('Scan requests need a secure browser context: use HTTPS or a loopback development listener.');
    const requestId = crypto.randomUUID();
    notice('Requesting a collection…');
    await api('/api/scans', {method:'POST', body:JSON.stringify({request_id:requestId})});
    notice('Scan requested. Evidence will appear here when collection finishes.');
    await refresh();
  } catch (error) {notice(error.message, true);}
  finally {byId('scan').disabled = false;}
});


function portSelection() {
  const ports = byId('ports').value.split(',').map(value => value.trim()).filter(Boolean).map(Number);
  if (ports.length > 8 || ports.some(port => !Number.isInteger(port) || port < 1 || port > 65535) || new Set(ports).size !== ports.length) throw new Error('Enter up to eight unique TCP ports between 1 and 65535.');
  return ports;
}
function renderDevices(report) {
  if (!report) return;
  byId('network').value = report.network;
  byId('ports').value = report.ports.join(',');
  const targets = new Set(byId('targets').value.split(',').map(value => value.trim()).filter(Boolean));
  const container = byId('devices');
  container.replaceChildren();
  report.devices.forEach(device => {
    const label = element('label', undefined, 'device');
    const checkbox = element('input'); checkbox.type = 'checkbox'; checkbox.value = device.address; checkbox.checked = targets.has(device.address);
    checkbox.addEventListener('change', () => {
      const selectedTargets = new Set(byId('targets').value.split(',').map(value => value.trim()).filter(Boolean));
      if (checkbox.checked) selectedTargets.add(device.address); else selectedTargets.delete(device.address);
      byId('targets').value = [...selectedTargets].join(', ');
      if (selectedTargets.size) byId('enabled').checked = true;
    });
    const description = element('span', device.address);
    description.append(element('small', device.open_ports.length ? `Open ports: ${device.open_ports.join(', ')}` : 'Responded; selected ports closed'));
    label.append(checkbox, description); container.append(label);
  });
  byId('discovery-detail').textContent = `${report.devices.length} responding addresses. Select up to 32 devices. ${report.coverage}`;
}
async function loadSettings() {
  const settings = await api('/api/monitoring');
  const report = await api('/api/discovery');
  byId('enabled').checked = settings.enabled;
  byId('targets').value = settings.targets.join(', ');
  byId('ports').value = settings.ports.join(',');
  byId('device-labels').value = (settings.labels || []).map(label => `${label.address}=${label.label}`).join('\n');
  byId('identity-source').textContent = settings.pihole_configured ? 'Pi-hole v6 is configured. A scan records fresh device associations and DNS activity; names below remain your own labels.' : 'Add your own device names, or connect Pi-hole v6 using the setup guide for observed hostnames, MAC addresses and DNS activity.';
  renderDevices(report);
  if (settings.ports.length) byId('ports').value = settings.ports.join(',');
  byId('monitoring').hidden = false;
  settingsLoaded = true;
}
byId('discover-form').addEventListener('submit', async event => {
  event.preventDefault();
  byId('discover').disabled = true;
  byId('discovery-detail').textContent = 'Looking for responding devices…';
  try {
    const report = await api('/api/discovery', {method:'POST', body:JSON.stringify({network:byId('network').value.trim(), ports:portSelection()})});
    renderDevices(report);
    notice('Discovery finished. Choose the devices you want to monitor.');
  } catch (error) {byId('discovery-detail').textContent = error.message; notice(error.message, true);}
  finally {byId('discover').disabled = false;}
});
byId('monitor-form').addEventListener('submit', async event => {
  event.preventDefault(); settingsPending = true;
  byId('save-monitoring').disabled = true;
  try {
    const targets = byId('targets').value.split(',').map(value => value.trim()).filter(Boolean);
    const labels = {};
    for (const line of byId('device-labels').value.split('\n').map(value => value.trim()).filter(Boolean)) {
      const separator = line.indexOf('=');
      const address = line.slice(0, separator).trim();
      const name = line.slice(separator + 1).trim();
      if (separator < 1 || !name || !targets.includes(address) || Object.hasOwn(labels, address)) throw new Error('Use one selected address=name per line, without duplicate addresses.');
      labels[address] = name;
    }
    const result = await api('/api/monitoring', {method:'POST', body:JSON.stringify({enabled:byId('enabled').checked, targets, ports:portSelection(), labels})});
    await refresh();
    notice(result.enabled ? 'Monitoring saved. Choose Request a scan to collect your first evidence.' : 'Collection is disabled. Your evidence is preserved.');
  } catch (error) {notice(error.message, true);}
  finally {settingsPending = false; byId('save-monitoring').disabled = false;}
});

// A local launcher opens a fresh, process-scoped dashboard bearer. Fragments are
// not sent to the server. Remove it immediately; never persist either credential.
const handoff = new URLSearchParams(location.hash.slice(1)).get('token');
if (location.hash) history.replaceState(null, '', location.pathname);
async function connectLocal() {
  if (handoff) {
    bearer = handoff;
    await api('/api/session', {method:'POST', body:'{}'});
    bearer = '';
  } else {
    // A reload keeps the HttpOnly browser session without asking for a token.
    await api('/api/status');
  }
  authenticated = true;
  await refresh();
}
connectLocal().catch(error => {
  profileEdit = null; profileBlocked = false; byId('profile-form').hidden = true; byId('profile-markdown').hidden = true;
  for (const id of ['profile-name','profile-owner','profile-model','profile-purpose','profile-expected','profile-notes']) byId(id).value = '';
  for (const id of ['profile-records','profile-intents','profile-history','profile-markdown','profile-addresses']) byId(id).replaceChildren();
  bearer = ''; authenticated = false;
  if (handoff) notice(error.message, true);
});
setInterval(() => {
  if ((bearer || authenticated) && (!document.hidden || overview?.preferences.browser_notifications) && !settingsPending) refresh().catch(error => notice(error.message, true));
}, 5000);

function renderOverview(value) {
  const health = value.health;
  byId('deployment-state').textContent = value.pending_deployment ? `Deployment ${value.pending_deployment} needs reconciliation. Enter the administrator password and choose Check retained deployment.` : 'No unsettled schedule deployment.';
  const grid = byId('health'); grid.replaceChildren();
  const checks = [['Collector', byId('state').textContent], ['Plowshare', health.connected ? 'Read checks passed' : 'Needs attention'], ['Last contact', health.last_contact ? new Date(health.last_contact).toLocaleString() : 'Awaiting contact'], ['Last completed scan', health.last_scan ? new Date(health.last_scan).toLocaleString() : 'None yet'], ['Next scheduled scan', health.schedule ? (health.schedule.paused ? 'Paused' : health.schedule.next_at || 'Awaiting next fire') : 'Not registered'], ['Pi-hole', health.pihole], ['Project agents', health.agents ? `${health.agents.served.length}/3 available${health.agents.unavailable.length ? ' · missing: ' + health.agents.unavailable.join(', ') : ''}` : 'Unavailable'], ['Dynamic tool visibility', health.agents ? `${health.agents.tools.length}/6 visible${health.agents.missing_tools.length ? ' · missing: ' + health.agents.missing_tools.join(', ') : ''}` : 'Unavailable'], ['Service credential expiry', health.credential_expires_at || 'Not supplied']];
  for (const [name, detail] of checks) {const card = element('article'); card.append(element('strong', name), element('p', detail)); grid.append(card);}
  grid.append(element('p', `Checked ${new Date(health.checked_at).toLocaleString()}. ${health.detail}`, 'hint'));
  byId('checklist').replaceChildren(...health.checklist.map(item => element('li', item)));
  byId('recovery').replaceChildren(...health.recovery.map(item => element('p', item, 'notice error')));
  const runs = health.runs.map(run => `${run.state} · ${run.id}`);
  if (runs.length) listItems(byId('recovery'), 'Recent project investigations', runs);
  const nextDeviceSignature = JSON.stringify(value.devices);
  if (nextDeviceSignature !== deviceSignature) {
  deviceSignature = nextDeviceSignature;
  const cards = byId('device-cards'); cards.replaceChildren();
  if (!value.devices.length) cards.append(element('p', 'Choose your devices below to begin.'));
  for (const device of value.devices) {
    const card = element('article', undefined, 'device-card');
    card.append(element('h3', device.name), element('p', device.address));
    card.append(element('p', device.identity ? `${device.identity.mac || device.identity.device_id}${device.identity.vendor ? ' · ' + device.identity.vendor : ''}` : 'Address only; physical identity not observed', 'hint'));
    card.append(element('p', device.last_seen ? `Last evidence ${new Date(device.last_seen).toLocaleString()}` : 'Awaiting first evidence', 'hint'));
    card.append(element('p', device.services.map(item => `${item.port}: ${item.status}`).join(' · '), 'hint'));
    const destinations = element('details'); destinations.append(element('summary', `${device.dns.length} sampled DNS destinations`)); for (const query of device.dns) destinations.append(element('p', `${query.domain}: ${query.queries} sampled queries`, 'hint')); card.append(destinations);
    const history = element('details'); history.append(element('summary', `${device.history.length} collections at this address`));
    for (const id of [...device.history].reverse()) {const button = element('button', `Read ${id.slice(0,8)}`, 'entry'); button.addEventListener('click', () => showEvidence(id).catch(error => notice(error.message, true))); history.append(button);}
    const profile = element('button', 'Device profile');
    profile.addEventListener('click', () => openDeviceProfile(device.address).catch(error => notice(error.message, true)));
    card.append(history, profile); cards.append(card);
  }
  }
  const nextFindingSignature = JSON.stringify(value.findings);
  if (nextFindingSignature !== findingSignature) {
  findingSignature = nextFindingSignature;
  const findings = byId('findings'); findings.replaceChildren();
  if (!value.findings.length) findings.append(element('p', 'No comparison findings yet. A baseline establishes the first observations.', 'empty'));
  for (const finding of value.findings) {
    const card = element('article', undefined, 'finding'); card.append(element('h3', finding.title), element('p', finding.explanation, 'hint'));
    const open = element('button', 'Read evidence', 'secondary'); open.addEventListener('click', () => showEvidence(finding.scan_id).catch(error => notice(error.message, true)));
    const acknowledge = element('button', finding.expected ? 'Expected · undo' : 'Mark expected', 'secondary');
    acknowledge.addEventListener('click', async () => {acknowledge.disabled = true; try {await api('/api/expected', {method:'POST', body:JSON.stringify({id:finding.id,expected:!finding.expected})}); await refresh();} catch (error) {notice(error.message,true); acknowledge.disabled = false;}});
    const actions = element('div', undefined, 'actions'); actions.append(open, acknowledge); card.append(actions); findings.append(card);
  }
  }
  if (!preferencesLoaded) {
    byId('automatic').checked = value.preferences.automatic_investigations;
    byId('daily-limit').value = value.preferences.daily_limit;
    byId('cooldown').value = value.preferences.cooldown_minutes;
    byId('notifications').checked = value.preferences.browser_notifications;
    if (health.schedule) {byId('zone').value = health.schedule.zone; byId('schedule-paused').checked = health.schedule.paused;}
    preferencesLoaded = true;
  }
  const latest = value.findings.find(item => !item.expected);
  if (latest && notified !== null && notified !== latest.scan_id && value.preferences.browser_notifications && globalThis.Notification?.permission === 'granted') new Notification('Network Privacy Watch', {body:'New observations are ready for review in your private dashboard.'});
  if (latest) notified = latest.scan_id;
}
byId('preferences-form').addEventListener('submit', async event => {
  event.preventDefault();
  try {
    let notificationHint = '';
    if (byId('notifications').checked && globalThis.Notification && Notification.permission !== 'granted') await Notification.requestPermission();
    if (byId('notifications').checked && (!globalThis.Notification || Notification.permission !== 'granted')) {byId('notifications').checked = false; notificationHint = ' Browser notifications are unavailable or were declined; findings remain in the dashboard.';}
    await api('/api/preferences', {method:'POST', body:JSON.stringify({automatic_investigations:byId('automatic').checked,daily_limit:Number(byId('daily-limit').value),cooldown_minutes:Number(byId('cooldown').value),browser_notifications:byId('notifications').checked})});
    await refresh(); notice('Preferences saved. Existing evidence and publication decisions are unchanged.' + notificationHint);
  } catch (error) {notice(error.message,true);}
});
async function piholeConnection(save) {
  const password = byId('pihole-password').value; byId('pihole-password').value = '';
  try {
    const result = await api('/api/pihole', {method:'POST', body:JSON.stringify({origin:byId('pihole-origin').value.trim(),password,save})});
    byId('pihole-detail').textContent = `${result.connected ? (result.saved ? 'Connection saved.' : 'Connection read succeeded. Enter the password again to save.') : 'Connection check failed.'} ${result.issues.join(' ')}`;
    if (result.saved) {settingsLoaded = false; await refresh();}
  } catch (error) {byId('pihole-detail').textContent = error.message;}
}
byId('pihole-test').addEventListener('click', () => piholeConnection(false));
byId('pihole-form').addEventListener('submit', event => {event.preventDefault(); piholeConnection(true);});
byId('recover').addEventListener('click', async () => {
  byId('recover').disabled = true;
  try {await api('/api/recovery', {method:'POST', body:'{}'}); await refresh(); notice('Retained receipts checked. Collection can resume; no uncertain effect was resent.');} catch (error) {notice(error.message,true);} finally {byId('recover').disabled = false;}
});
byId('check-moves').addEventListener('click', async () => {
  byId('check-moves').disabled = true;
  try {
    const moves = await api('/api/device-moves', {method:'POST', body:'{}'}); byId('moves').replaceChildren();
    if (!moves.length) byId('moves').append(element('p', 'No fresh reassociation found in the latest discovery. Run Find devices before checking again.', 'hint'));
    for (const move of moves) {
      const button = element('button', `Confirm ${move.device_id}: ${move.old} → ${move.new}`, 'entry');
      button.addEventListener('click', async () => {button.disabled = true; try {await api('/api/device-moves/accept', {method:'POST', body:JSON.stringify(move)}); settingsLoaded = false; await refresh(); byId('moves').replaceChildren();} catch (error) {notice(error.message,true); button.disabled = false;}}); byId('moves').append(button);
    }
  } catch (error) {notice(error.message,true);} finally {byId('check-moves').disabled = false;}
});
byId('schedule-form').addEventListener('submit', async event => {
  event.preventDefault(); scheduleDraft = null; byId('schedule-draft').hidden = true;
  const password = byId('administrator-password').value; byId('administrator-password').value = '';
  try {
    scheduleDraft = await api('/api/schedule/review', {method:'POST', body:JSON.stringify({preset:byId('frequency').value,zone:byId('zone').value.trim(),paused:byId('schedule-paused').checked,password})});
    byId('schedule-review').textContent = `${scheduleDraft.paused ? 'Paused' : 'Enabled'} · ${scheduleDraft.cron} · ${scheduleDraft.zone}. Based on revision ${scheduleDraft.expected_revision}. Source digest ${scheduleDraft.source_digest}. Enter the administrator password again to deploy.`;
    byId('schedule-store').replaceChildren(...scheduleDraft.stores.map(store => {const option = element('option', store); option.value = store; return option;}));
    const sources = element('details'); sources.append(element('summary', 'Review full Application source'));
    for (const file of scheduleDraft.sources) {sources.append(element('h3', file.path), element('pre', file.text));}
    byId('schedule-review').append(sources);
    byId('schedule-draft').hidden = false;
  } catch (error) {notice(error.message,true);}
});
byId('deploy-schedule').addEventListener('click', async () => {
  if (!scheduleDraft || !byId('administrator-password').value) {notice('Review a revision and enter the administrator password to deploy.',true); return;}
  const password = byId('administrator-password').value; byId('administrator-password').value = ''; byId('deploy-schedule').disabled = true;
  try {const result = await api('/api/schedule/deploy', {method:'POST',body:JSON.stringify({review_id:scheduleDraft.id,store:byId('schedule-store').value,password})}); scheduleDraft = null; byId('schedule-draft').hidden = true; await refresh(); notice(`Application revision ${result.revision} retained. Schedule activation may take a moment.`);} catch (error) {notice(error.message,true);} finally {byId('deploy-schedule').disabled = false;}
});

byId('check-deployment').addEventListener('click', async () => {
  const password = byId('administrator-password').value; byId('administrator-password').value = '';
  if (!password) {notice('Enter the configured deployment administrator password to inspect its retained receipt.', true); return;}
  byId('check-deployment').disabled = true;
  try {const result = await api('/api/schedule/reconcile', {method:'POST', body:JSON.stringify({password})}); await refresh(); notice(result.revision ? `Confirmed active revision ${result.revision}. No deployment was resent.` : 'No unsettled schedule deployment.');} catch (error) {notice(error.message, true);} finally {byId('check-deployment').disabled = false;}
});


async function profileReceipts() {
  const intents = await api('/api/profile-intents');
  profileBlocked = intents.some(value => value.phase === 'pending');
  byId('save-profile').disabled = profileBlocked;
  byId('new-profile').disabled = profileBlocked;
  const pending = intents.filter(value => value.phase === 'pending');
  byId('profile-intents').replaceChildren(...(pending.length ? pending.map(value => element('p', `Pending write: ${value.request_id}`)) : [element('p', 'No unsettled profile writes.')]));
  if (profileBlocked) byId('profile-detail').textContent = 'A profile write has an unknown outcome. Check retained writes; it will not be resent.';
}
async function loadProfiles() {
  await profileReceipts();
  const result = await api('/api/profiles');
  const records = byId('profile-records'); records.replaceChildren();
  if (!result.profiles.length) records.append(element('p', 'No device profiles in this project yet.', 'empty'));
  for (const view of result.profiles) {
    const button = element('button', `${view.profile.fields.name} · ${view.profile.fields.addresses.join(', ')}`, 'entry');
    button.addEventListener('click', () => editProfile(view).catch(error => notice(error.message, true))); records.append(button);
  }
  if (!profileBlocked) byId('profile-detail').textContent = `${result.profiles.length} device profiles loaded from Plowshare. Older revisions remain available below each profile.`;
  return result.profiles;
}
async function editProfile(view, address = null) {
  await profileReceipts();
  const settings = await api('/api/monitoring');
  const fields = view ? view.profile.fields : {name:'',owner:'',model:'',purpose:'',expected_services:'',notes:'',addresses:address ? [address] : []};
  profileEdit = {id:view ? view.profile.profile_id : crypto.randomUUID(), revision:view ? view.revision : null};
  for (const [id, key] of [['profile-name','name'],['profile-owner','owner'],['profile-model','model'],['profile-purpose','purpose'],['profile-expected','expected_services'],['profile-notes','notes']]) byId(id).value = fields[key];
  const choices = [...new Set([...settings.targets, ...fields.addresses])];
  byId('profile-addresses').replaceChildren(...choices.map(value => {const option = element('option', value); option.value = value; option.selected = fields.addresses.includes(value); return option;}));
  byId('profile-editor-title').textContent = view ? `Edit ${fields.name}` : 'Create a device profile';
  byId('profile-form').hidden = false; byId('device-profiles').open = true;
  const history = byId('profile-history'); history.replaceChildren(); byId('profile-markdown').hidden = true;
  if (view) {
    history.append(element('h3', 'Retained profile revisions'));
    for (const version of [...view.versions].sort((a,b) => b.ordinal - a.ordinal)) {
      const read = element('button', `Revision ${version.ordinal} · ${new Date(version.created_at).toLocaleString()}`, 'entry');
      read.title = version.revision;
      read.addEventListener('click', async () => {try {const source = await api(`/api/profiles/${view.profile.profile_id}/${version.revision}`); byId('profile-markdown').textContent = source.text; byId('profile-markdown').hidden = false;} catch(error) {notice(error.message,true);}}); history.append(read);
    }
    history.append(element('h3', 'Recent retained evidence at associated addresses'));
    if (!view.evidence.length) history.append(element('p', 'No matching evidence among the latest 50 local collections.'));
    for (const revision of view.evidence) {
      const read = element('button', revision, 'entry');
      read.addEventListener('click', async () => {try {const scans = await api('/api/scans'); const scan = scans.find(value => value.revision === revision); if (!scan) throw new Error('Evidence is outside the current local history window.'); await showEvidence(scan.scan_id);} catch(error) {notice(error.message,true);}}); history.append(read);
    }
  }
  byId('device-profiles').scrollIntoView({behavior:'smooth',block:'nearest'});
}
async function openDeviceProfile(address) {
  const profiles = await loadProfiles();
  const matches = profiles.filter(value => value.profile.fields.addresses.includes(address));
  if (matches.length > 1) throw new Error('Several profiles claim this address. Review their retained documents before editing.');
  await editProfile(matches[0] || null, address);
}
byId('load-profiles').addEventListener('click', () => loadProfiles().catch(error => notice(error.message,true)));
byId('new-profile').addEventListener('click', () => editProfile(null).catch(error => notice(error.message,true)));
byId('cancel-profile').addEventListener('click', () => {profileEdit = null; byId('profile-form').hidden = true;});
byId('profile-form').addEventListener('submit', async event => {
  event.preventDefault(); if (!profileEdit || profileBlocked) return;
  byId('save-profile').disabled = true;
  const fields = {name:byId('profile-name').value,owner:byId('profile-owner').value,model:byId('profile-model').value,purpose:byId('profile-purpose').value,expected_services:byId('profile-expected').value,notes:byId('profile-notes').value,addresses:[...byId('profile-addresses').selectedOptions].map(value => value.value)};
  try {
    const receipt = await api('/api/profiles', {method:'POST',body:JSON.stringify({profile_id:profileEdit.id,request_id:crypto.randomUUID(),expected_revision:profileEdit.revision,fields})});
    if (receipt.phase === 'confirmed') {profileEdit = null; byId('profile-form').hidden = true; notice('Device profile saved in Plowshare. Processing may still be pending; load profiles again when ready.');}
    else notice(`Profile write ${receipt.request_id} needs reconciliation. It has not been resent.`, true);
  } catch(error) {notice(error.message,true);} finally {await profileReceipts().catch(() => {byId('save-profile').disabled = true;});}
});
byId('reconcile-profiles').addEventListener('click', async () => {try {await api('/api/profiles/reconcile',{method:'POST',body:'{}'}); await loadProfiles(); notice('Retained profile writes checked; no mutation was resent.');} catch(error) {notice(error.message,true);}});
