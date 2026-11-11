'use strict';

// The web bearer exists only in this tab's memory. All untrusted evidence is text.
let bearer = '';
let authenticated = false;
let selected = null;
let refreshPending = false;
let settingsLoaded = false;
let settingsPending = false;
let monitoringDraft = false;
let piholePending = false;
let monitorEditable = false;
let overview = null;
let preferencesLoaded = false;
let scheduleDraft = null;
let scheduleControlBusy = false;
let scheduleControlUnknown = false;
let notified = null;
let deviceSignature = null;
let findingSignature = null;
let profileEdit = null;
let profileBlocked = false;
let advisoriesAvailable = false;
let researchDevice = null;
let selectedResearch = null;
let researchBusy = false;
let issueEdit = null;
let issueBlocked = false;
let issuesAvailable = false;
let assessmentsAvailable = false;
let assessmentSettingsLoaded = false;
const byId = (name) => document.getElementById(name);

const views = {
  overview: ['Network overview', 'What is on the network, who it talks to, how much it sends and what cannot be seen.'],
  devices: ['Your devices', 'Device observations, profiles and linked privacy issues.'],
  findings: ['Findings & investigations', 'Review observed changes and read investigation reports.'],
  evidence: ['Collection evidence', 'Retained observations, comparisons and coverage gaps.'],
  settings: ['Monitoring settings', 'Devices, connections and investigation preferences.'],
};

// Switching a view changes presentation only. Keep forms and retained outcomes
// mounted so navigation cannot clear a draft, resubmit work or reset a pending control.
let currentView = 'overview';
let restoring = false;
// The fragment carries the open view and device so reload and Back restore
// them. It is never sent to the server and holds no credential.
function recordLocation() {
  if (restoring || !globalThis.history?.pushState) return;
  const parts = [];
  if (currentView !== 'overview') parts.push('tab=' + currentView);
  if (selectedDevice) parts.push('device=' + encodeURIComponent(selectedDevice));
  const target = parts.length ? '#' + parts.join('&') : '';
  if (location.hash !== target) history.pushState(null, '', location.pathname + target);
}
function showView(name, focusHeading = false) {
  if (!Object.hasOwn(views, name)) throw new Error('Unknown dashboard view.');
  currentView = name;
  for (const view of Object.keys(views)) {
    const active = view === name;
    byId(`view-${view}`).hidden = !active;
    byId(`tab-${view}`).setAttribute('aria-selected', String(active));
    byId(`tab-${view}`).tabIndex = active ? 0 : -1;
  }
  byId('page-title').textContent = views[name][0];
  byId('page-description').textContent = views[name][1];
  byId('page-title').scrollIntoView({block:'start'});
  if (focusHeading) byId('page-title').focus({preventScroll:true});
  recordLocation();
}
for (const name of Object.keys(views)) {
  const tab = byId(`tab-${name}`);
  tab.addEventListener('click', () => showView(name));
  tab.addEventListener('keydown', event => {
    const names = Object.keys(views);
    const index = names.indexOf(name);
    const next = {ArrowRight:(index + 1) % names.length, ArrowLeft:(index + names.length - 1) % names.length, Home:0, End:names.length - 1}[event.key];
    if (next === undefined) return;
    event.preventDefault();
    showView(names[next]);
    byId(`tab-${names[next]}`).focus();
  });
}
byId('open-settings').addEventListener('click', () => showView('settings', true));
byId('open-findings').addEventListener('click', () => showView('findings', true));
byId('open-devices').addEventListener('click', () => showView('devices', true));
byId('close-report').addEventListener('click', () => byId('report-panel').classList.toggle('reading', false));
byId('close-evidence').addEventListener('click', () => byId('evidence-panel').classList.toggle('reading', false));
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
  showView('evidence', true);
  byId('evidence-panel').classList.toggle('reading', true);
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
  const document = PrivacyMarkdown.render(result.text);
  showView('findings', true);
  const report = byId('reports').querySelector(`[data-revision="${CSS.escape(revision)}"]`);
  byId('report-selection').textContent = report ? report.dataset.title : 'Retained report';
  byId('report-content').replaceChildren(document);
  byId('report-panel').classList.toggle('reading', true);
  byId('report-content').scrollTop = 0;
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
    button.append(row, element('small', `${scan.change_count ?? scan.changes.length} comparison entries · ${scan.issue_count ?? scan.issues.length} coverage notes${scan.mode === 'fixture' ? ' · fixture' : ''}`));
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
    button.dataset.revision = report.revision; button.dataset.title = report.title;
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
    byId('mode').textContent = status.collection_enabled === false ? 'Collection disabled' : status.mode === 'fixture' ? 'Synthetic fixture mode' : 'TCP collection mode';
    // Sample data must never pass for the household's own network.
    byId('mode').classList.toggle('synthetic', status.mode === 'fixture');
    byId('fixture-banner').hidden = status.mode !== 'fixture';
    byId('device-profiles').hidden = !status.profiles_available;
    byId('background-research').hidden = !status.profile_research_available;
    if (!researchBusy) byId('background-research-enabled').checked = !!status.profile_research_enabled;
    const waitingDrafts = status.profile_drafts_waiting || 0;
    byId('background-research-count').textContent = waitingDrafts ? String(waitingDrafts) + ' awaiting review' : '';
    advisoriesAvailable = !!status.advisories_available;
    byId('research-search-status').textContent = advisoriesAvailable
      ? 'Advisory search sends only this public model name to NVD. Review it before searching.'
      : 'Enable the collector’s NVD lookup to search here. You can still review product clues and edit a profile.';
    if (!researchDevice) byId('research-search').disabled = !advisoriesAvailable;
    issuesAvailable = !!status.issues_available;
    assessmentsAvailable = !!status.device_assessments_available;
    byId('background-assessments').hidden = !assessmentsAvailable;
    if (assessmentsAvailable && !assessmentSettingsLoaded) await loadAssessmentSettings();
    byId('privacy-issues').hidden = !issuesAvailable;
    byId('project').textContent = status.project;
    byId('collector').textContent = status.collector;
    byId('state').textContent = status.state === 'attention_required' ? 'Needs attention' : status.collection_enabled === false ? 'Disabled' : status.state.replaceAll('_', ' ');
    byId('detail').textContent = status.state !== 'attention_required' && status.collection_enabled === false ? 'Enable collection in Settings when you are ready.' : status.detail;
    byId('change-count').textContent = scans.length ? scans[0].change_count ?? scans[0].changes.filter(change => !change.startsWith('Baseline')).length : '—';
    byId('report-count').textContent = reportResult.error ? 'Unavailable' : reportResult.value.length;
    byId('scan').disabled = status.state === 'attention_required' || status.collection_enabled === false;
    renderCollections(scans);
    if (reportResult.error) byId('reports').replaceChildren(element('p', 'Retained agent reports are temporarily unavailable. Local evidence remains readable.', 'empty'));
    else if (Array.isArray(reportResult.value)) renderReports(reportResult.value);
    else throw new Error('Unexpected reports response.');
    if (status.settings_available && !settingsLoaded) await loadSettings();
    if (status.settings_available && !settingsPending) {
      const settings = await api('/api/monitoring');
      renderMonitorSettings(settings);
    }
    try {overview = await api('/api/overview'); renderOverview(overview);} catch (error) {byId('health').replaceChildren(element('p', error.message)); byId('health-summary').textContent = 'Status unavailable';}
    byId('dashboard').hidden = false;
    byId('connect').hidden = true;
    const unknown = status.requests.find(request => request.state === 'pending');
    if (unknown) notice(`Request ${unknown.request_id} needs reconciliation. It has not been resent.`, true);
    else if (reportResult.error) notice(reportResult.error.message, true);
    else notice(status.state === 'attention_required' ? status.detail : '', status.state === 'attention_required');
    await pollAssessment().catch(error => notice(error.message, true));
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
  clearIssueEditor();
  clearDeviceResearch();
  clearBackgroundResearch();
  profileEdit = null; profileBlocked = false; byId('profile-form').hidden = true; byId('profile-markdown').hidden = true;
  for (const id of ['profile-name','profile-owner','profile-model','profile-purpose','profile-expected','profile-notes']) byId(id).value = '';
  for (const id of ['profile-records','profile-intents','profile-history','profile-markdown','profile-addresses','profile-issue-links']) byId(id).replaceChildren();
  monitoringDraft = false;
  bearer = ''; authenticated = false; selected = null; settingsLoaded = false; preferencesLoaded = false; overview = null; scheduleDraft = null; deviceSignature = null; findingSignature = null; notified = null;
  for (const id of ['health','checklist','recovery','recent-runs','device-cards','moves','findings','q-third','q-silent','q-volume']) byId(id).replaceChildren();
  for (const id of ['q-network','q-third-coverage','device-shown']) byId(id).textContent = '';
  byId('device-search').value = ''; byId('device-presence').value = 'observed'; byId('device-sort').value = 'inventory';
  setSignalFilter('all');
  selectedDevice = null; byId('device-view').hidden = true;
  for (const id of ['device-facts','device-analysis','device-history']) byId(id).replaceChildren();
  syncPane();
  byId('fixture-banner').hidden = true; byId('mode').classList.toggle('synthetic', false);
  for (const id of ['pihole-origin','pihole-password','administrator-password','zone']) byId(id).value = '';
  byId('schedule-draft').hidden = true;
  renderScheduleControl(null, null, null);
  byId('monitoring').hidden = true; byId('devices').replaceChildren();
  byId('network').value = ''; byId('targets').value = ''; byId('ports').value = ''; byId('device-labels').value = '';
  showView('overview');
  byId('dashboard').hidden = true; byId('connect').hidden = false;
  byId('collections').replaceChildren(); byId('reports').replaceChildren(); byId('evidence').replaceChildren(); byId('report-content').replaceChildren(); byId('report-selection').textContent = 'Select a report';
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
      const draft = byId('targets').value;
      const selectedTargets = new Set(draft.includes('/') ? [...container.querySelectorAll('input:checked')].map(input => input.value) : draft.split(',').map(value => value.trim()).filter(Boolean));
      monitoringDraft = true;
      if (checkbox.checked) selectedTargets.add(device.address); else selectedTargets.delete(device.address);
      byId('targets').value = [...selectedTargets].join(', ');
      if (selectedTargets.size) byId('enabled').checked = true;
    });
    const description = element('span', device.address);
    description.append(element('small', device.open_ports.length ? `Open ports: ${device.open_ports.join(', ')}` : 'Responded; selected ports closed'));
    label.append(checkbox, description); container.append(label);
  });
  byId('discovery-detail').textContent = `${report.devices.length} responding addresses. Select up to 256 devices, or monitor the whole /24 subnet. ${report.coverage}`;
}
async function loadSettings() {
  const settings = await api('/api/monitoring');
  const report = await api('/api/discovery');
  byId('enabled').checked = settings.enabled;
  byId('quick-scan').checked = settings.quick_scan === true;
  byId('dns-profiles').checked = settings.dns_profiles === true;
  byId('device-descriptions').checked = settings.device_descriptions === true;
  byId('targets').value = settings.targets.join(', ');
  byId('ports').value = settings.ports.join(',');
  byId('device-labels').value = (settings.labels || []).map(label => `${label.address}=${label.label}`).join('\n');
  const sources = [settings.pihole_configured && 'Pi-hole v6 for identity and DNS', settings.unifi_configured && 'UniFi for connected-client identity'].filter(Boolean);
  byId('identity-source').textContent = sources.length
    ? 'Configured sources: ' + sources.join(', ') + '. Names below are your own labels.'
    : 'Add your own labels or connect Pi-hole or UniFi for observed device identity.';
  renderDevices(report);
  if (settings.ports.length) byId('ports').value = settings.ports.join(',');
  byId('monitoring').hidden = false;
  renderMonitorSettings(settings);
  settingsLoaded = true;
}
byId('use-subnet').addEventListener('click', () => {
  const network = byId('network').value.trim();
  if (!network) {notice('Enter your private subnet first.', true); return;}
  byId('targets').value = network;
  monitoringDraft = true;
  for (const checkbox of byId('devices').querySelectorAll('input')) checkbox.checked = false;
  byId('settings-detail').textContent = 'Subnet scope prepared. Save monitoring to validate and retain it; no scan has started.';
});
// DNS profiles and device descriptions both run after quick checks, so each
// needs quick checks on and turning quick checks off clears them.
for (const id of ['targets','ports','device-labels','enabled','quick-scan','dns-profiles','device-descriptions']) byId(id).addEventListener('input', () => {
  if (id === 'quick-scan' && !byId('quick-scan').checked) {byId('dns-profiles').checked = false; byId('device-descriptions').checked = false;}
  if ((id === 'dns-profiles' || id === 'device-descriptions') && byId(id).checked) byId('quick-scan').checked = true;
  monitoringDraft = true;
  byId('settings-detail').textContent = 'Monitoring changes are not saved. Choose Save monitoring to retain them.';
});
// Destination groups arrive in privacy-first order from the collector.
const destinationLabels = {
  advertising:'Advertising', measurement:'Audience measurement & content recognition',
  telemetry:'Vendor telemetry & logging', streaming:'Streaming services',
  unclassified:'Unclassified', connectivity:'Connectivity checks & time',
  local:'Local names & reverse lookups',
};
const behaviouralKinds = new Set(['advertising', 'measurement', 'telemetry']);
function behaviouralQueries(activity) {
  return (activity?.groups || []).filter(group => behaviouralKinds.has(group.kind))
    .reduce((total, group) => total + group.queries, 0);
}
function formatBytes(value) {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let amount = value, unit = 0;
  while (amount >= 1000 && unit < units.length - 1) {amount /= 1000; unit += 1;}
  return `${unit ? amount.toFixed(amount < 10 ? 1 : 0) : amount} ${units[unit]}`;
}
// These two renderers are exercised by the web tests in a minimal DOM. Keep them
// to element(), append and property assignment; style is optional there.
function shareBar(fraction, className) {
  const bar = element('span', undefined, className);
  if (bar.style) bar.style.width = `${Math.max(1, Math.round(100 * fraction))}%`;
  return bar;
}
function renderTraffic(traffic) {
  const section = element('section', undefined, 'readout traffic');
  section.append(element('h4', `Traffic · ${formatBytes(traffic.to_device_bytes)} to device · ${formatBytes(traffic.from_device_bytes)} from device`));
  const bounds = [traffic.from_at, traffic.until_at].map(value => new Date(value).toLocaleString()).join(' to ');
  section.append(element('p', `UniFi client byte counters · ${bounds} · ${traffic.samples} quick checks`, 'hint source'));
  if (traffic.resets) section.append(element('p', `${traffic.resets} intervals skipped after a counter reset (reconnect or controller restart). Totals are a lower bound.`, 'limit'));
  section.append(element('p', 'Direction follows UniFi: "to device" is what the network sent the client. Counters show volume, not which hosts received it.', 'limit'));
  section.append(element('p', 'Totals include local traffic, such as a camera recording to a local video recorder or a backup to a NAS, not only internet traffic.', 'limit'));
  return section;
}
function renderDnsActivity(activity) {
  const labels = destinationLabels;
  const section = element('section', undefined, 'readout dns');
  section.append(element('h4', `DNS activity · ${activity.queries_read} sampled queries in the last 24 hours`));
  if (activity.pihole_silent) {
    const hours = Math.floor((new Date(activity.until_at) - new Date(activity.from_at)) / 3600000);
    section.append(element('p', `No DNS queries reached Pi-hole in ${activity.windows} confirmed checks over ${hours} hours while this device was on the network. It may be idle, or it may use its own DNS servers or DNS over HTTPS, which Pi-hole cannot see.`, 'gap-note'));
  }
  const bounds = [activity.from_at, activity.until_at].map(value => new Date(value).toLocaleString()).join(' to ');
  section.append(element('p', `Pi-hole · ${bounds} · ${activity.windows} observed windows`, 'hint source'));
  if (activity.partial_windows) section.append(element('p', `Partial coverage in ${activity.partial_windows} windows. Pi-hole reported ${activity.queries_available} matching queries in the sampled windows.`, 'limit'));
  section.append(element('p', 'Counts are sampled DNS lookups, not connections or bytes. Traffic volume is under How much it sends; which host received those bytes is not observed.', 'limit'));
  const most = Math.max(1, ...activity.groups.flatMap(group => group.destinations.map(entry => entry.queries)));
  for (const group of activity.groups) {
    const behavioural = behaviouralKinds.has(group.kind);
    section.append(element('h5', `${labels[group.kind] || group.kind} · ${group.queries} queries`, behavioural ? `kind kind-${group.kind}` : 'kind'));
    if (group.destinations.length) {
      const table = element('table', undefined, 'destinations');
      const head = element('tr');
      head.append(element('th', 'Destination'), element('th', 'Queries', 'num'), element('th', 'Reported blocked', 'num'));
      table.append(head);
      for (const entry of group.destinations) {
        const row = element('tr');
        const name = element('td', undefined, 'destination');
        name.append(element('span', entry.domain, 'domain'));
        if (entry.provider) name.append(element('span', entry.provider, 'provider'));
        name.append(shareBar(entry.queries / most, behavioural ? `share seg-${group.kind}` : 'share'));
        row.append(name, element('td', String(entry.queries), 'num'), element('td', String(entry.blocked || 0), entry.blocked ? 'num' : 'num zero'));
        table.append(row);
      }
      section.append(table);
    }
    if (group.distinct > group.destinations.length) {
      section.append(element('p', `Showing ${group.destinations.length} of ${group.distinct} destinations`, 'hint'));
    }
  }
  return section;
}
// Device rows carry their own search text and signals so filtering never
// re-reads evidence. Candidates stay a separate mode of the same list.
let signalFilter = 'all';
const signalNames = {all:'All observed', third:'Ad, measurement or telemetry lookups', silent:'No DNS via Pi-hole'};
function setSignalFilter(name) {
  signalFilter = name;
  for (const signal of Object.keys(signalNames)) byId(`signal-${signal}`).setAttribute('aria-pressed', String(signal === name));
}
function filterDevices() {
  const query = byId('device-search').value.trim().toLowerCase();
  const presence = byId('device-presence').value;
  const cards = [...byId('device-cards').querySelectorAll('.device-card')];
  const bySignal = presence === 'observed' && signalFilter !== 'all';
  let shown = 0, group = 0;
  for (const card of cards) {
    const inGroup = card.dataset.presence === presence;
    card.hidden = !inGroup || !card.dataset.search.includes(query)
      || (bySignal && !(card.dataset.signals || '').split(' ').includes(signalFilter));
    if (inGroup) group += 1;
    if (!card.hidden) shown += 1;
  }
  byId('device-cards').classList.toggle('showing-candidates', presence === 'candidate');
  byId('device-cards').classList.toggle('showing-none', shown === 0);
  byId('device-signals').hidden = presence === 'candidate';
  byId('device-sort').disabled = presence === 'candidate';
  const narrowed = bySignal || presence === 'candidate' || byId('device-sort').value !== 'inventory';
  byId('filters-toggle').textContent = narrowed ? 'Filter and order · in use' : 'Filter and order';
  byId('device-shown').textContent = cards.length
    ? `${shown} of ${group} ${presence === 'candidate' ? 'candidate addresses' : 'observed devices'}` : '';
  const empty = byId('device-filter-empty');
  empty.hidden = !cards.length || cards.some(card => !card.hidden);
  empty.textContent = query ? 'No addresses match this search.'
    : bySignal ? `No observed device shows “${signalNames[signalFilter]}”.`
    : presence === 'candidate' ? 'No candidate addresses remain in this scope.'
    : 'No devices observed yet. Candidate addresses are checked on each sweep.';
}
byId('filters-toggle').addEventListener('click', () => {
  const open = byId('filters-toggle').getAttribute('aria-expanded') !== 'true';
  byId('filters-toggle').setAttribute('aria-expanded', String(open));
  byId('device-filters').classList.toggle('open', open);
});
for (const signal of Object.keys(signalNames)) byId(`signal-${signal}`).addEventListener('click', () => {setSignalFilter(signal); filterDevices();});
byId('device-sort').addEventListener('change', () => {if (overview) {renderInventory(overview); filterDevices();}});
byId('device-search').addEventListener('input', filterDevices);
byId('device-presence').addEventListener('change', filterDevices);
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
      if (separator < 1 || !name  || Object.hasOwn(labels, address)) throw new Error('Use one selected address=name per line, without duplicate addresses.');
      labels[address] = name;
    }
    const result = await api('/api/monitoring', {method:'POST', body:JSON.stringify({enabled:byId('enabled').checked, targets, ports:portSelection(), labels, quickScan:byId('quick-scan').checked,dnsProfiles:byId('dns-profiles').checked,deviceDescriptions:byId('device-descriptions').checked})});
    monitoringDraft = false;
    renderMonitorSettings(result);
    notice(result.pending ? 'Monitoring settings saved. They are waiting to apply; existing scan work keeps its original configuration.' : 'Monitoring settings saved.');
    await refresh().catch(error => notice('Monitoring settings were saved, but dashboard refresh failed. ' + error.message, true));
  } catch (error) {notice(error.message, true);}
  finally {settingsPending = false; byId('save-monitoring').disabled = false;}
});

// A local launcher opens a fresh, process-scoped dashboard bearer. Fragments are
// not sent to the server. Remove it immediately; never persist either credential.
// The selected device stays in the fragment so reload and Back restore it.
const fragment = new URLSearchParams(location.hash.slice(1));
const handoff = fragment.get('token');
let pendingDevice = fragment.get('device');
let pendingTab = Object.hasOwn(views, fragment.get('tab') || '') ? fragment.get('tab') : pendingDevice ? 'devices' : null;
if (location.hash) {
  const kept = [pendingTab && 'tab=' + pendingTab, pendingDevice && 'device=' + encodeURIComponent(pendingDevice)].filter(Boolean);
  history.replaceState(null, '', location.pathname + (kept.length ? '#' + kept.join('&') : ''));
}
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
  clearIssueEditor();
  clearDeviceResearch();
  clearBackgroundResearch();
  profileEdit = null; profileBlocked = false; byId('profile-form').hidden = true; byId('profile-markdown').hidden = true;
  for (const id of ['profile-name','profile-owner','profile-model','profile-purpose','profile-expected','profile-notes']) byId(id).value = '';
  for (const id of ['profile-records','profile-intents','profile-history','profile-markdown','profile-addresses','profile-issue-links']) byId(id).replaceChildren();
  bearer = ''; authenticated = false;
  if (handoff) notice(error.message, true);
});
setInterval(() => {
  if ((bearer || authenticated) && (!document.hidden || overview?.preferences.browser_notifications) && !settingsPending) refresh().catch(error => notice(error.message, true));
}, 5000);

function renderDeviceDetection(detection) {
  const panel = element('div', undefined, 'detection');
  panel.append(element('h4', detection.confidence === 'unknown' ? 'Device type unknown'
    : 'Possible device type: ' + detection.device_type));
  if (detection.clues.length) {
    const clues = element('ul');
    clues.append(...detection.clues.map(clue => element('li', clue)));
    panel.append(clues);
  }
  if (detection.confidence === 'corroborated') {
    panel.append(element('p', 'The observed name and port agree.', 'hint'));
  }
  return panel;
}
function renderExposures(detection) {
  const nodes = detection.exposures.map(exposure => element('p',
    'TCP ' + exposure.ports.join(', ') + ' · Possible ' + exposure.possible_service + '. ' + exposure.risk_check, 'exposure'));
  nodes.push(element('p', 'Open ports suggest services. Verify the protocol, model, and firmware before assessing vulnerabilities.', 'limit'));
  return nodes;
}

function knownIdentity(device) {
  const current = device.identity?.source === 'configured' ? null : device.identity;
  return {current, known: current || device.last_known_identity};
}
function behaviouralGroups(activity) {
  return (activity?.groups || []).filter(group => behaviouralKinds.has(group.kind) && group.queries);
}
const shortKinds = {advertising:'Advertising', measurement:'Measurement', telemetry:'Telemetry'};
function behaviouralBreakdown(activity) {
  return behaviouralGroups(activity).map(group => `${shortKinds[group.kind]} ${group.queries}`).join(' · ');
}
// One gilt bar, split by pattern so the three kinds never rely on hue. The
// bar is decorative; callers always print the same breakdown as text.
function segmentBar(activity, scale) {
  const track = element('span', undefined, 'segbar');
  track.setAttribute('aria-hidden', 'true');
  const fill = element('span', undefined, 'segbar-fill');
  fill.style.width = `${Math.max(2, 100 * behaviouralQueries(activity) / Math.max(1, scale))}%`;
  for (const group of behaviouralGroups(activity)) {
    const segment = element('span', undefined, `seg seg-${group.kind}`);
    segment.style.flexGrow = String(group.queries);
    fill.append(segment);
  }
  track.append(fill);
  return track;
}
function resetCount(resets) {return resets === 1 ? '1 counter reset' : `${resets} counter resets`;}
function silentHours(activity) {
  return Math.floor((new Date(activity.until_at) - new Date(activity.from_at)) / 3600000);
}
function deviceLink(device) {
  const link = element('button', device.name, 'device-link');
  link.type = 'button';
  link.addEventListener('click', () => focusDevice(device.address));
  return link;
}
function leafMore(parent, hidden, noun, apply) {
  if (hidden < 1) return;
  const more = element('button', `${hidden} more ${noun} in the device list`, 'text-link');
  more.type = 'button';
  more.addEventListener('click', () => {apply(); showView('devices', true);});
  parent.append(more);
}
function showInventory(signal, sort) {
  byId('device-search').value = ''; byId('device-presence').value = 'observed';
  setSignalFilter(signal);
  if (byId('device-sort').value !== sort) {byId('device-sort').value = sort; if (overview) renderInventory(overview);}
  filterDevices();
}
function focusDevice(address) {
  showInventory('all', byId('device-sort').value);
  showView('devices');
  openDevice(address);
}

// The Overview altarpiece answers the four questions from data already in the
// overview. It ranks observations; it never derives a score from them.
function renderTriptych(value) {
  const limit = 6;
  const profiled = value.devices.filter(device => device.dns_activity);

  const third = byId('q-third'); third.replaceChildren();
  const talkers = value.devices.filter(device => behaviouralQueries(device.dns_activity) > 0)
    .sort((a, b) => behaviouralQueries(b.dns_activity) - behaviouralQueries(a.dns_activity));
  const most = talkers.length ? behaviouralQueries(talkers[0].dns_activity) : 1;
  for (const device of talkers.slice(0, limit)) {
    const row = element('div', undefined, 'leaf-row talker');
    const head = element('div', undefined, 'leaf-line');
    head.append(deviceLink(device), element('span', undefined, 'leader'), element('span', `${behaviouralQueries(device.dns_activity)} lookups`, 'figure'));
    const providers = behaviouralGroups(device.dns_activity).flatMap(group => group.destinations)
      .sort((a, b) => b.queries - a.queries).slice(0, 3).map(entry => entry.provider || entry.domain);
    const who = element('p', behaviouralBreakdown(device.dns_activity), 'leaf-note');
    if (providers.length) who.append(element('span', ` — ${providers.join(', ')}`, 'who'));
    row.append(head, segmentBar(device.dns_activity, most), who);
    third.append(row);
  }
  if (!talkers.length) third.append(element('p', profiled.length
    ? `None of the ${profiled.length} devices with a DNS profile looked up a destination in these groups.`
    : 'No per-device DNS profiles yet. They need Pi-hole and quick presence checks; see Settings.', 'empty'));
  leafMore(third, talkers.length - limit, 'devices', () => showInventory('third', 'third'));
  const unprofiled = value.devices.length - profiled.length;
  const partial = profiled.filter(device => device.dns_activity.partial_windows).length;
  byId('q-third-coverage').textContent = [
    unprofiled && profiled.length ? `${unprofiled} of ${value.devices.length} observed devices have no DNS profile and cannot appear here.` : '',
    partial ? `${partial} devices had partial coverage in some windows; open a device for detail.` : '',
  ].filter(Boolean).join(' ');

  const silent = byId('q-silent'); silent.replaceChildren();
  const quiet = value.devices.filter(device => device.dns_activity?.pihole_silent);
  for (const device of quiet.slice(0, limit)) {
    const row = element('div', undefined, 'leaf-row gap');
    const head = element('div', undefined, 'leaf-line');
    head.append(deviceLink(device), element('span', undefined, 'leader'), element('span', `quiet ${silentHours(device.dns_activity)} h`, 'figure'));
    row.append(head, element('p', `No DNS via Pi-hole in ${device.dns_activity.windows} confirmed checks`, 'leaf-note'));
    silent.append(row);
  }
  if (!quiet.length) silent.append(element('p', profiled.length
    ? 'No device is reported without DNS via Pi-hole.'
    : 'No per-device DNS profiles yet, so silence cannot be assessed.', 'empty'));
  leafMore(silent, quiet.length - limit, 'devices', () => showInventory('silent', 'inventory'));

  const volume = byId('q-volume'); volume.replaceChildren();
  const senders = value.devices.filter(device => device.traffic)
    .sort((a, b) => b.traffic.from_device_bytes - a.traffic.from_device_bytes);
  const largest = senders.length ? Math.max(1, senders[0].traffic.from_device_bytes) : 1;
  for (const device of senders.slice(0, limit)) {
    const row = element('div', undefined, 'leaf-row sender');
    const head = element('div', undefined, 'leaf-line');
    head.append(deviceLink(device), element('span', undefined, 'leader'), element('span', `${formatBytes(device.traffic.from_device_bytes)} from device`, 'figure'));
    const track = element('span', undefined, 'segbar'); track.setAttribute('aria-hidden', 'true');
    const fill = element('span', undefined, 'segbar-fill plain');
    fill.style.width = `${Math.max(2, 100 * device.traffic.from_device_bytes / largest)}%`;
    track.append(fill);
    const resets = device.traffic.resets ? ` · lower bound; ${resetCount(device.traffic.resets)}` : '';
    row.append(head, track, element('p', `${formatBytes(device.traffic.to_device_bytes)} to device${resets}`, 'leaf-note'));
    volume.append(row);
  }
  if (!senders.length) volume.append(element('p', 'No UniFi byte counters yet. They need UniFi and quick presence checks.', 'empty'));
  leafMore(volume, senders.length - limit, 'devices with counters', () => showInventory('all', 'from'));

  const types = new Map(); let unknown = 0, identified = 0;
  for (const device of value.devices) {
    if (knownIdentity(device).known) identified += 1;
    if (!device.detection || device.detection.confidence === 'unknown') unknown += 1;
    else types.set(device.detection.device_type, (types.get(device.detection.device_type) || 0) + 1);
  }
  const tally = [...types].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([name, count]) => `${name} ${count}`);
  if (unknown) tally.push(`type unknown ${unknown}`);
  byId('q-network').textContent = value.devices.length
    ? `${identified} with an identity observed by UniFi or Pi-hole, ${value.devices.length - identified} by address only. Possible types from names and open ports: ${tally.join(' · ')}.`
    : 'Choose addresses in Settings to begin monitoring.';
  if (!triptychOpened) {
    // The single page-load moment: the wings open once, never on later refreshes.
    triptychOpened = true;
    byId('triptych').classList.add('unveil');
    setTimeout(() => byId('triptych').classList.remove('unveil'), 1200);
  }
}
let triptychOpened = false;

function cell(label, className) {
  const node = element('span', undefined, `cell ${className}`);
  node.append(element('span', label, 'cell-label'));
  return node;
}
function fact(list, term, value, className) {
  list.append(element('dt', term), element('dd', value, className));
}
function paneSection(title) {
  const section = element('section', undefined, 'pane-section');
  section.append(element('h3', title));
  return section;
}
function actionButton(label, className, action) {
  const button = element('button', label, className);
  button.type = 'button';
  button.addEventListener('click', () => Promise.resolve().then(action).catch(error => notice(error.message, true)));
  return button;
}

// The side pane is the one place detail opens: a device, its profile editor,
// an issue or an agent draft. Nothing scrolls the page to a distant form.
let selectedDevice = null;
const paneParts = ['device-view','profile-form','profile-markdown','device-research','background-draft','issue-form','issue-markdown'];
function syncPane() {
  const visible = id => byId(id).hidden === false;
  const device = selectedDevice && overview ? overview.devices.find(item => item.address === selectedDevice) : null;
  byId('pane-title').textContent = device ? device.name
    : visible('issue-form') || visible('issue-markdown') ? 'Privacy issue'
    : visible('background-draft') ? 'Agent research draft' : 'Device profile';
  byId('side-pane').hidden = !paneParts.some(visible);
}
function closeEditors() {
  profileEdit = null; byId('profile-form').hidden = true; byId('profile-markdown').hidden = true; byId('profile-history').replaceChildren();
  issueEdit = null; byId('issue-form').hidden = true; byId('issue-markdown').hidden = true; byId('issue-history').replaceChildren();
  clearBackgroundDraft();
  clearDeviceResearch();
}
function markSelectedRow() {
  for (const card of byId('device-cards').querySelectorAll('.device-card')) {
    const current = selectedDevice !== null && card.dataset.address === selectedDevice;
    card.classList.toggle('selected', current);
    const summary = card.querySelector('.device-summary');
    if (summary) summary.setAttribute('aria-current', String(current));
  }
}
function openDevice(address, record = true) {
  const device = overview?.devices.find(item => item.address === address);
  if (!device) return;
  const changed = selectedDevice !== address;
  if (changed) closeEditors();
  selectedDevice = address;
  renderDeviceView(device);
  byId('device-view').hidden = false;
  markSelectedRow(); syncPane();
  if (changed) {
    if (record) recordLocation();
    byId('pane-body').scrollTop = 0;
    byId('pane-title').focus({preventScroll:true});
    loadDeviceAnalysis(device);
  }
}
function closePane(record = true) {
  const address = selectedDevice;
  selectedDevice = null;
  byId('device-view').hidden = true;
  for (const id of ['device-facts','device-analysis','device-history']) byId(id).replaceChildren();
  closeEditors();
  markSelectedRow(); syncPane();
  if (record) recordLocation();
  if (address) {
    const card = [...byId('device-cards').querySelectorAll('.device-card')].find(item => item.dataset.address === address);
    card?.querySelector('.device-summary')?.focus({preventScroll:true});
  }
}
byId('pane-close').addEventListener('click', () => closePane());
byId('side-pane').addEventListener('keydown', event => {if (event.key === 'Escape') closePane();});
globalThis.addEventListener?.('popstate', () => {
  const params = new URLSearchParams(location.hash.slice(1));
  const tab = params.get('tab'), address = params.get('device');
  restoring = true;
  try {
    const view = Object.hasOwn(views, tab || '') ? tab : address ? 'devices' : 'overview';
    if (view !== currentView) showView(view);
    if (address && overview?.devices.some(item => item.address === address)) openDevice(address, false);
    else if (selectedDevice) closePane(false);
  } finally {restoring = false;}
});

// What was observed, in the order the operator asks about it: what the device
// is, what it talks to, how much it sends, then its open ports. Each block
// keeps the source and the limits of its own numbers.
function renderDeviceView(device) {
  const {current, known} = knownIdentity(device);
  const identity = paneSection('What it is');
  const facts = element('dl', undefined, 'facts');
  fact(facts, 'Address', device.address, 'data');
  if (known) {
    if (known.mac) fact(facts, 'MAC address', known.mac, 'data');
    if (known.hostname) fact(facts, 'Hostname', known.hostname, 'data');
    if (known.vendor) fact(facts, 'Vendor', known.vendor);
    const association = [known.hostname, known.mac, known.vendor].filter(Boolean).join(' · ') || 'Address observed';
    const observedAt = new Date(known.observed_at).toLocaleString();
    const source = known.source === 'unifi' ? 'UniFi' : 'Pi-hole';
    fact(facts, 'Identity source', (current ? '' : 'Last known ') + source + ' association · ' + association + ' · ' + observedAt);
  } else {
    fact(facts, 'Identity source', 'Address only; no observed identity');
  }
  const presence = device.presence;
  if (presence?.checked > 0) {
    const percent = Math.round(100 * presence.seen / presence.checked);
    const subject = presence.address_only ? 'Address seen' : 'Device seen';
    const last = presence.last_seen ? ' · Last seen ' + new Date(presence.last_seen).toLocaleString() : '';
    fact(facts, 'Presence', `${subject} in ${presence.seen} of ${presence.checked} quick checks (${percent}%) over 24 hours${last}`);
  } else if (presence?.last_seen) {
    fact(facts, 'Presence', 'Last quick sighting ' + new Date(presence.last_seen).toLocaleString());
  }
  identity.append(facts);
  if (device.detection) identity.append(renderDeviceDetection(device.detection));

  const talks = paneSection('What it talks to');
  if (device.dns_activity) talks.append(renderDnsActivity(device.dns_activity));
  else talks.append(element('p', 'No Pi-hole DNS profile for this device. Its destinations are unknown, not absent.', 'hint unmeasured'));
  const destinations = element('details'); destinations.append(element('summary', `Last full sweep · ${device.dns.length} sampled DNS destinations`));
  for (const query of device.dns) destinations.append(element('p', `${query.domain}: ${query.queries} sampled queries`, 'hint'));
  talks.append(destinations);

  const sends = paneSection('How much it sends');
  if (device.traffic) sends.append(renderTraffic(device.traffic));
  else sends.append(element('p', 'No UniFi byte counters for this device. Volume is unknown, not zero.', 'hint unmeasured'));

  const ports = paneSection('Open ports and exposures');
  const sweep = element('dl', undefined, 'facts');
  fact(sweep, 'Checked ports', device.services.length ? device.services.map(item => `${item.port}: ${item.status}`).join(' · ') : 'No checked ports recorded.', device.services.length ? 'data' : undefined);
  const responded = device.services.some(item => item.status === 'open' || item.status === 'closed');
  fact(sweep, 'Full sweep', device.last_seen ? `Last checked ${new Date(device.last_seen).toLocaleString()} · ${responded ? 'TCP response' : 'No TCP response'}` : 'Awaiting first full sweep');
  if (device.last_response_at) {
    fact(sweep, 'TCP responses', `First TCP response ${new Date(device.first_response_at).toLocaleString()} · Last ${new Date(device.last_response_at).toLocaleString()}`);
  }
  ports.append(sweep);
  if (device.detection) ports.append(...renderExposures(device.detection));
  byId('device-facts').replaceChildren(identity, talks, sends, ports);

  const history = paneSection('Evidence history');
  history.append(element('p', `${device.history.length} ${device.history.length === 1 ? 'collection' : 'collections'} at this address. Reading one opens it in the Evidence view.`, 'hint'));
  for (const id of [...device.history].reverse()) {
    history.append(actionButton(`Read collection ${id.slice(0,8)}`, 'entry', () => showEvidence(id)));
  }
  byId('device-history').replaceChildren(history);
}

const applicabilityLabels = [['potentially_affected','Potentially affected'],['confirmed_affected','Confirmed affected'],['not_affected','Not affected'],['mitigated','Mitigated'],['unresolved','Unresolved']];
const mitigationLabels = [['proposed','Proposed'],['accepted','Accepted'],['applied','Applied by operator'],['verified','Verified by operator'],['reverted','Reverted by operator']];
// Profile documents end with a machine-readable record. Fold it away after
// rendering so the operator's notes are not buried under JSON and identifiers.
function collapseRecordMetadata(article) {
  const heading = [...article.children].find(node => /^H[1-6]$/.test(node.tagName) && node.textContent.trim() === 'Record metadata');
  if (!heading) return article;
  const fold = element('details', undefined, 'record-metadata');
  fold.append(element('summary', 'Record metadata'));
  let next = heading.nextElementSibling;
  while (next && next.tagName !== 'PRE') next = next.nextElementSibling;
  if (!next) return article;
  heading.replaceWith(fold);
  fold.append(next);
  return article;
}
function renderProfileDocument(text) {
  return collapseRecordMetadata(PrivacyMarkdown.render(text));
}
// The operator's profile, linked issues and any agent draft are read once when
// a device is opened and again after a save; the five-second refresh leaves
// them. Notes are the operator's own; nothing here is presented as analysis
// unless an agent produced it.
async function loadDeviceAnalysis(device) {
  const panel = byId('device-analysis');
  const stale = () => selectedDevice !== device.address;
  const notes = paneSection('Your notes and expectations');
  const known = paneSection('What we know about this device');
  const assessment = paneSection('Agent assessment');
  const product = paneSection('Product and advisories');
  product.append(element('p', 'Confirm the product with UniFi, then search the public NVD advisory database by model name. No agent is involved.', 'hint'),
    actionButton('Look up product and advisories', 'secondary', () => openDeviceResearch(device)));
  const waiting = paneSection('Your notes and expectations');
  waiting.append(element('p', 'Reading the saved profile…', 'hint'));
  panel.replaceChildren(waiting);
  const mac = device.identity?.mac || null;
  try {
    if (byId('device-profiles').hidden) {
      notes.append(element('p', 'Device profiles are unavailable on this collector.', 'hint'));
    } else {
      const {profiles} = await api('/api/profiles');
      const confirmed = mac ? profiles.filter(view => view.profile.fields.device_mac === mac) : [];
      const matches = confirmed.length ? confirmed
        : profiles.filter(view => !view.profile.fields.device_mac && view.profile.fields.addresses.includes(device.address));
      if (matches.length > 1) {
        notes.append(element('p', 'Several profiles claim this device. Review their retained documents in the device knowledge base before editing.', 'limit'));
      } else if (matches.length === 1) {
        const view = matches[0];
        const source = await api(`/api/profiles/${view.profile.profile_id}/${view.revision}`);
        notes.append(element('p', confirmed.length
          ? 'Your saved profile, confirmed for this MAC address. It records what you expect of the device; it is not an observation or an assessment.'
          : 'Your saved profile, associated by address only. A matching address alone does not establish device identity. It records what you expect of the device; it is not an observation or an assessment.', 'hint'));
        const reader = element('div', undefined, 'document-reader');
        reader.append(renderProfileDocument(source.text));
        notes.append(actionButton('Edit profile', undefined, () => editProfile(view, device.address, null)), reader);
        const links = view.profile.fields.issues || [];
        notes.append(element('h4', 'Linked privacy issues'));
        if (links.length) {
          const titles = issuesAvailable
            ? new Map((await api('/api/issues')).issues.map(item => [item.issue.issue_id, item.issue.fields.title])) : new Map();
          for (const link of links) {
            const issue = element('article', undefined, 'linked-issue');
            const label = (choices, value) => (choices.find(choice => choice[0] === value) || [value, value])[1];
            issue.append(element('h5', titles.get(link.issue_id) || `Retained issue ${link.issue_id}`),
              element('p', `${label(applicabilityLabels, link.applicability)} · Mitigation: ${label(mitigationLabels, link.mitigation_status)}`, 'status-line'));
            if (link.notes) issue.append(element('p', link.notes, 'hint'));
            issue.append(actionButton('Read issue and mitigation plan', 'secondary', () => readIssue(link.issue_id, link.revision)));
            notes.append(issue);
          }
        } else {
          notes.append(element('p', 'No privacy issue is linked to this profile. Link one from Edit profile.', 'hint'));
        }
      } else {
        notes.append(element('p', 'You have not written a profile for this device. A profile records what the device is and what you expect it to do.', 'hint'),
          actionButton('Write a profile for this device', undefined, () => openDeviceProfile(device.address, mac)));
      }
    }
  } catch (error) {
    notes.append(element('p', error.message, 'notice error'));
  }
  try {
    if (mac && device.identity?.device_id) {
      known.append(...renderSupplemental(await api(`/api/devices/${encodeURIComponent(device.identity.device_id)}/supplemental`)));
    } else {
      known.append(element('p', 'Not available: this device is known by its address only. Device descriptions need a confirmed MAC address.', 'hint'));
    }
  } catch (error) {
    known.append(element('p', error.message, 'notice error'));
  }
  try {
    const block = element('div', undefined, 'assessment-block');
    assessment.append(block);
    await openAssessment(device, block);
    if (!byId('background-research').hidden) {
      const queue = await api('/api/profile-research');
      const entry = queue.entries.find(item => item.address === device.address);
      assessment.append(element('h4', 'Agent research draft'));
      if (entry) {
        assessment.append(element('p', `${researchPhases[entry.phase] || entry.phase}. The agent drafts a profile from device-name, vendor and service clues. It is a draft to review, not an assessment of behaviour.`, 'hint'),
          actionButton('Review the agent’s draft', 'secondary', () => openBackgroundDraft(entry.request_id)));
      } else {
        assessment.append(element('p', queue.enabled
          ? 'No draft for this device. Background research drafts profiles from recent quick sightings, one at a time; it cannot be requested for one device.'
          : 'No draft for this device. Background profile research is off; enable it under Background device profiles.', 'hint'));
      }
    }
  } catch (error) {
    assessment.append(element('p', error.message, 'notice error'));
  }
  if (!stale()) panel.replaceChildren(notes, known, assessment, product);
}

// What the device said about itself, public advisories for its model and
// vendor features worth checking, from the collector's retained facts. Every
// value is shown with textContent: device-reported strings are untrusted.
const describedBy = {upnp:'UPnP', mdns:'mDNS'};
const modelFrom = {profile:'your profile', unifi:'the UniFi controller', device:'the device’s own description'};
function shortDate(stamp) {
  return stamp ? new Date(stamp).toLocaleDateString() : '';
}
function renderSupplemental(body) {
  const section = body.supplemental;
  const nodes = [];
  if (section.described_at === null) {
    nodes.push(element('p', body.enabled
      ? 'Not described yet. The collector asks each monitored device to describe itself at most once a day, after quick checks.'
      : 'Device descriptions are off — turn them on in Settings.', 'hint'));
  } else if (!section.descriptions.length) {
    nodes.push(element('p', `The device did not describe itself when last asked (${shortDate(section.described_at)}). Many devices do not; that is not a finding.`, 'hint'));
  }
  if (section.descriptions.length) {
    nodes.push(element('h4', 'Reported by the device'));
    for (const item of section.descriptions) {
      const list = element('dl', undefined, 'facts');
      if (item.manufacturer) fact(list, 'Manufacturer', item.manufacturer);
      if (item.model_name) fact(list, 'Model', item.model_name);
      if (item.model_number) fact(list, 'Model number', item.model_number, 'data');
      if (item.firmware) fact(list, 'Firmware', item.firmware, 'data');
      if (item.services.length) fact(list, 'Advertises', item.services.join(' · '));
      fact(list, 'Source', `${describedBy[item.source] || item.source} on the local network · ${shortDate(item.observed_at)}`);
      nodes.push(list);
    }
    nodes.push(element('p', 'These are the device’s own claims, not confirmed identity.', 'limit'));
  }
  nodes.push(element('h4', 'Public security advisories'));
  const lookup = section.advisories;
  const from = lookup?.model ? ` · model ${lookup.model} from ${modelFrom[lookup.model_source] || 'an unknown source'}` : '';
  if (!lookup) {
    nodes.push(element('p', 'Not looked up yet.', 'hint'));
  } else if (lookup.status === 'model_unknown') {
    nodes.push(element('p', `Not looked up: no public model name was available (${shortDate(lookup.checked_at)}).`, 'hint'));
  } else if (lookup.status === 'not_configured') {
    nodes.push(element('p', 'Not looked up: no advisory source is configured on this collector.', 'hint'));
  } else if (lookup.status === 'unavailable') {
    nodes.push(element('p', `The NVD lookup did not complete (${shortDate(lookup.checked_at)}${from}). ${lookup.unavailable || ''} Advisories are unknown, not none.`, 'hint unmeasured'));
  } else {
    nodes.push(element('p', `NVD · checked ${shortDate(lookup.checked_at)}${from} · ${lookup.total ? lookup.total + ' found' : 'none found'}`, 'hint'));
    for (const item of lookup.advisories) {
      const entry = element('article', undefined, 'known-item');
      const facts = [item.severity ? item.severity[0] + item.severity.slice(1).toLowerCase() : 'Severity not rated', 'published ' + item.published_on];
      if (item.known_exploited) facts.push('known exploited');
      entry.append(element('p', item.cve, 'data'), element('p', facts.join(' · '), 'status-line'), element('p', item.description, 'hint'));
      nodes.push(entry);
    }
    const unlisted = (lookup.total || 0) - lookup.advisories.length;
    if (unlisted > 0) nodes.push(element('p', `${unlisted} more not listed here.`, 'hint'));
    if (lookup.total) nodes.push(element('p', 'An advisory is about the model, not proof that this device is affected. Check whether the firmware it names matches.', 'limit'));
  }
  if (section.vendor_notes.length) {
    nodes.push(element('h4', 'Worth checking'));
    for (const note of section.vendor_notes) {
      const entry = element('article', undefined, 'known-item');
      entry.append(element('h5', `${note.feature} (${note.applies_to})`), element('p', note.collects),
        element('p', `To turn it off: ${note.turn_off.replace(/\.$/, '')}. Menus may vary by model year.`, 'hint'));
      const sources = element('p', 'Source: ', 'hint');
      note.sources.forEach((source, index) => {
        if (index) sources.append(document.createTextNode('; '));
        if (/^https:\/\//.test(source.url)) {
          const link = element('a', source.title);
          link.href = source.url; link.target = '_blank'; link.rel = 'noopener noreferrer';
          sources.append(link);
        } else {
          sources.append(document.createTextNode(source.title));
        }
      });
      entry.append(sources);
      nodes.push(entry);
    }
    nodes.push(element('p', 'Features this manufacturer ships, not observations of this device.', 'limit'));
  }
  return nodes;
}

// The agent assessment of one device. The collector's ledger holds its state;
// the report text is read once per revision. While a run is starting or
// running, or an Accept is unconfirmed, the five-second refresh rereads only
// the state. Nothing here resends a request whose outcome is unknown: Check
// status only reads.
let deviceAssessment = null;
let assessmentBusy = false;
function assessmentPath(deviceId, suffix = '') {
  return `/api/devices/${encodeURIComponent(deviceId)}/assessment${suffix}`;
}
function when(stamp) {
  return stamp ? new Date(stamp).toLocaleString() : '';
}
function renderAssessment(view, report) {
  const nodes = [];
  const button = (label, action, className) => {
    const control = actionButton(label, className, () => assessmentAction(action));
    control.disabled = assessmentBusy;
    return control;
  };
  const verdict = view.review === 'needs_corrections' ? 'accepted after one correction round' : 'accepted';
  const run = view.run_id ? element('p', 'Run ', 'hint') : null;
  if (run) run.append(element('span', view.run_id, 'data'));
  if (view.state === 'idle') {
    nodes.push(element('p', 'Not assessed', 'status-line'),
      element('p', 'An agent reads this device’s observations (what it is, who it talks to and how much it sends) and explains them in plain language. A second agent checks each concern against the same data. The report stays a draft until you accept it.', 'hint'));
  } else if (view.state === 'running') {
    nodes.push(element('p', view.pending === 'admission' ? 'Starting' : 'Assessment running', 'status-line'),
      element('p', view.pending === 'admission'
        ? 'Plowshare has not confirmed the start. It is not sent again; its admission receipt settles it.'
        : `Requested ${when(view.requested_at)}. This view updates while the run continues.`, 'hint'));
    if (run) nodes.push(run);
  } else if (view.state === 'review') {
    nodes.push(element('p', view.pending === 'accept' ? 'Accept sent, not yet confirmed' : 'Awaiting your review', 'status-line'),
      element('p', view.pending === 'accept'
        ? 'Plowshare has not confirmed the report is final. Accept is not sent again; Check status reads it.'
        : `Assessed ${when(view.requested_at)}. Reviewer verdict: ${verdict}. Accept makes this report final in Plowshare. Dismiss hides it here and leaves it in Plowshare as a draft.`, 'hint'));
  } else if (view.state === 'accepted') {
    nodes.push(element('p', 'Accepted ' + when(view.decided_at), 'status-line'),
      element('p', `This report is final in Plowshare. Assessed ${when(view.requested_at)}; reviewer verdict: ${verdict}.`, 'hint'));
  } else if (view.state === 'dismissed') {
    nodes.push(element('p', 'Dismissed ' + when(view.decided_at), 'status-line'),
      element('p', 'You dismissed this draft. It stays in Plowshare as history and is not shown here.', 'hint'));
  } else if (view.state === 'failed') {
    nodes.push(element('p', 'Last assessment failed', 'status-line'));
    if (run) nodes.push(run);
  }
  if (view.detail) nodes.push(element('p', view.detail, view.state === 'failed' || (view.state === 'review' && !view.pending) ? 'notice error' : 'hint'));
  // Decisions sit with the state line, above a report that can be long.
  const actions = element('div', undefined, 'row');
  if (view.can_accept) actions.append(button('Accept and make final', 'accept'));
  if (view.can_dismiss) actions.append(button('Dismiss without finalising', 'dismiss', 'secondary'));
  if (view.pending || (view.state === 'review' && view.detail)) actions.append(button('Check status', 'check', 'secondary'));
  if (view.can_assess) {
    actions.append(view.state === 'idle' ? button('Assess this device', 'assess')
      : view.state === 'failed' ? button('Retry', 'assess')
      : button('Assess again', 'assess', 'secondary'));
  }
  nodes.push(actions);
  if (!view.can_assess && view.assess_note) nodes.push(element('p', view.assess_note, 'hint'));
  if (report && (view.state === 'review' || view.state === 'accepted')) {
    const reader = element('div', undefined, 'document-reader');
    reader.append(PrivacyMarkdown.render(report));
    nodes.push(reader);
  }
  return nodes;
}
function showAssessment() {
  if (!deviceAssessment || deviceAssessment.address !== selectedDevice || !deviceAssessment.view) return;
  deviceAssessment.block.replaceChildren(...renderAssessment(deviceAssessment.view, deviceAssessment.report?.text || null));
}
async function reloadAssessment() {
  const current = deviceAssessment;
  if (!current) return;
  const view = await api(assessmentPath(current.deviceId));
  let report = current.report;
  if (view.report && (view.state === 'review' || view.state === 'accepted') && report?.revision !== view.report) {
    report = await api(assessmentPath(current.deviceId, '/report'));
  }
  if (deviceAssessment !== current) return;
  current.view = view; current.report = report;
  showAssessment();
}
async function openAssessment(device, block) {
  deviceAssessment = null;
  if (!assessmentsAvailable) {
    block.append(element('p', 'Device assessments are not enabled on this collector.', 'hint'));
    return;
  }
  const deviceId = device.identity?.mac ? device.identity.device_id : null;
  if (!deviceId) {
    block.append(element('p', 'Not assessed. An assessment needs a confirmed MAC address; this device is known by its address only.', 'hint'));
    return;
  }
  deviceAssessment = {address: device.address, deviceId, block, view: null, report: null};
  await reloadAssessment();
}
async function assessmentAction(action) {
  const current = deviceAssessment;
  if (!current || assessmentBusy) return;
  assessmentBusy = true; showAssessment();
  try {
    await api(assessmentPath(current.deviceId, '/' + action), {method:'POST', body:'{}'});
  } catch (error) {
    notice(error.message, true);
  } finally {
    assessmentBusy = false;
  }
  await reloadAssessment();
}
async function pollAssessment() {
  const view = deviceAssessment?.view;
  if (!view || deviceAssessment.address !== selectedDevice || assessmentBusy) return;
  if (view.state === 'running' || view.pending) await reloadAssessment();
}
async function loadAssessmentSettings() {
  const settings = await api('/api/device-assessments');
  byId('assessment-limit').value = String(settings.background_limit);
  byId('assessment-settings-detail').textContent = settings.detail || (settings.background_limit ? `Up to ${settings.background_limit} background assessments a day.` : 'Background assessment is off.');
  assessmentSettingsLoaded = true;
}
byId('assessment-settings').addEventListener('submit', async event => {
  event.preventDefault();
  try {
    const limit = Number(byId('assessment-limit').value);
    if (!Number.isInteger(limit)) throw new Error('Choose a whole number of assessments a day.');
    await api('/api/device-assessments', {method:'POST', body:JSON.stringify({background_limit:limit})});
    await loadAssessmentSettings();
  } catch (error) {
    byId('assessment-settings-detail').textContent = error.message;
  }
});

const inventoryOrders = {
  third: (a, b) => behaviouralQueries(b.dns_activity) - behaviouralQueries(a.dns_activity),
  from: (a, b) => (b.traffic?.from_device_bytes ?? -1) - (a.traffic?.from_device_bytes ?? -1),
  to: (a, b) => (b.traffic?.to_device_bytes ?? -1) - (a.traffic?.to_device_bytes ?? -1),
  name: (a, b) => a.name.localeCompare(b.name),
};
function renderInventory(value) {
  const cards = byId('device-cards'); cards.replaceChildren();
  if (!value.devices.length && !value.candidates.length) cards.append(element('p', 'Choose addresses in Settings to begin monitoring.', 'empty'));
  if (value.devices.length) {
    const head = element('div', undefined, 'ledger-head');
    head.setAttribute('aria-hidden', 'true');
    for (const [title, className] of [['Device', 'c-name'], ['Address', 'c-address'], ['Signals', 'c-signals'], ['Ad, measurement, telemetry lookups', 'c-third'], ['To device', 'c-to'], ['From device', 'c-from']]) head.append(element('span', title, className));
    cards.append(head);
  }
  const order = inventoryOrders[byId('device-sort').value];
  const devices = order ? [...value.devices].sort(order) : value.devices;
  const most = Math.max(1, ...value.devices.map(device => behaviouralQueries(device.dns_activity)));
  let thirdCount = 0, silentCount = 0;
  for (const device of devices) {
    const card = element('article', undefined, 'device-card');
    const {known} = knownIdentity(device);
    const tracking = behaviouralQueries(device.dns_activity);
    const flags = [];
    if (device.dns_activity?.pihole_silent) flags.push('No DNS via Pi-hole');
    if (tracking) flags.push(`${tracking} ad, measurement or telemetry lookups`);
    const signals = [];
    if (device.dns_activity?.pihole_silent) {signals.push('silent'); silentCount += 1;}
    if (tracking) {signals.push('third'); thirdCount += 1;}
    card.dataset.presence = 'observed';
    card.dataset.address = device.address;
    card.dataset.signals = signals.join(' ');
    card.dataset.search = [device.name, device.address, known?.mac, known?.hostname, known?.vendor, device.detection?.device_type, ...flags].filter(Boolean).join(' ').toLowerCase();

    const summary = element('button', undefined, 'device-summary');
    summary.type = 'button';
    summary.setAttribute('aria-controls', 'side-pane');
    const name = element('span', undefined, 'cell c-name');
    name.append(element('span', device.name, 'device-name'));
    const about = [device.detection && (device.detection.confidence === 'unknown' ? 'Type unknown' : 'Possible type: ' + device.detection.device_type), !known && 'Address only'].filter(Boolean);
    if (about.length) name.append(element('span', about.join(' · '), 'device-type'));
    const address = cell('Address', 'c-address'); address.append(element('span', device.address, 'data'));
    const signalCell = cell('Signals', 'c-signals');
    if (device.dns_activity?.pihole_silent) signalCell.append(element('span', 'No DNS via Pi-hole', 'tag tag-silent'));
    if (signalCell.children.length === 1) signalCell.append(element('span', 'None', 'nil'));
    const third = cell('Ad, measurement, telemetry lookups', 'c-third');
    if (!device.dns_activity) third.append(element('span', 'No DNS profile', 'nil'));
    else {
      if (tracking) third.append(segmentBar(device.dns_activity, most));
      third.append(element('span', String(tracking), tracking ? 'data' : 'data zero'), element('span', 'lookups', 'unit'));
      if (tracking) third.append(element('span', behaviouralBreakdown(device.dns_activity), 'breakdown'));
    }
    const to = cell('To device', 'c-to'), from = cell('From device', 'c-from');
    to.append(element('span', device.traffic ? formatBytes(device.traffic.to_device_bytes) : 'No counters', device.traffic ? 'data' : 'nil'));
    from.append(element('span', device.traffic ? formatBytes(device.traffic.from_device_bytes) : 'No counters', device.traffic ? 'data' : 'nil'));
    if (device.traffic?.resets) {
      // A counter reset is a data-quality note on the byte totals, not a signal.
      for (const total of [to, from]) {
        const mark = element('span', undefined, 'reset-mark');
        mark.title = `Lower bound; ${resetCount(device.traffic.resets)}`;
        mark.append(element('span', `lower bound; ${resetCount(device.traffic.resets)}`, 'vh'));
        total.append(mark);
      }
    }
    summary.append(name, address, signalCell, third, to, from);
    summary.addEventListener('click', () => {if (selectedDevice === device.address) closePane(); else openDevice(device.address);});
    card.append(summary);
    cards.append(card);
  }
  byId('signal-all').textContent = `${signalNames.all} · ${value.devices.length}`;
  byId('signal-third').textContent = `${signalNames.third} · ${thirdCount}`;
  byId('signal-silent').textContent = `${signalNames.silent} · ${silentCount}`;
  for (const candidate of value.candidates) {
    const card = element('article', undefined, 'device-card candidate');
    card.dataset.presence = 'candidate';
    card.dataset.search = [candidate.address, candidate.label].filter(Boolean).join(' ').toLowerCase();
    card.append(element('h3', candidate.address, 'data'), element('p', candidate.label || 'Candidate address'));
    card.append(element('p', candidate.last_checked_at
      ? 'Last checked ' + new Date(candidate.last_checked_at).toLocaleString()
      : 'Awaiting first sweep', 'hint'));
    cards.append(card);
  }
  // Keep an open device view on fresh observations; drop it if the device left.
  if (selectedDevice) {
    const device = value.devices.find(item => item.address === selectedDevice);
    if (device) {renderDeviceView(device); markSelectedRow(); syncPane();} else closePane();
  }
}

function renderOverview(value) {
  const health = value.health;
  byId('device-count').textContent = value.devices.length;
  byId('candidate-count').textContent = value.candidates.length;
  byId('health-summary').textContent = !health.connected ? 'Connection needs attention' : health.recovery.length ? 'Retained work needs attention' : 'Connected to Plowshare';
  byId('setup-checklist').hidden = health.checklist.length === 0;
  renderScheduleControl(health.schedule, value.schedule_control, value.pending_deployment);
  byId('deployment-state').textContent = value.pending_deployment ? `Deployment ${value.pending_deployment} needs reconciliation. Enter the administrator password and choose Check retained deployment.` : 'No unsettled schedule deployment.';
  const grid = byId('health'); grid.replaceChildren();
  const checks = [['Collector', byId('state').textContent], ['Plowshare', health.connected ? 'Read checks passed' : 'Needs attention'], ['Last contact', health.last_contact ? new Date(health.last_contact).toLocaleString() : 'Awaiting contact'], ['Last completed scan', health.last_scan ? new Date(health.last_scan).toLocaleString() : 'None yet'], ['Schedule runtime', health.schedule ? (health.schedule.paused ? 'Paused' : health.schedule.next_at || 'Awaiting next fire') : 'Not found or unavailable'], ['Pi-hole', health.pihole], ['Project agents', health.agents ? `${health.agents.served.length}/3 available${health.agents.unavailable.length ? ' · missing: ' + health.agents.unavailable.join(', ') : ''}` : 'Unavailable'], ['Dynamic tool visibility', health.agents ? (health.agents.tool_visibility === 'awaiting_context' ? 'Awaiting first project conversation' : `${health.agents.tools.length}/6 visible${health.agents.missing_tools.length ? ' · missing: ' + health.agents.missing_tools.join(', ') : ''}`) : 'Unavailable'], ['Service credential expiry', health.credential_expires_at || 'Not supplied']];
  for (const [name, detail] of checks) {const card = element('article'); card.append(element('strong', name), element('p', detail)); grid.append(card);}
  if (health.unifi) {
    const card = element('article');
    card.append(element('strong', 'UniFi'), element('p', health.unifi));
    grid.append(card);
  }
  grid.append(element('p', `Checked ${new Date(health.checked_at).toLocaleString()}. ${health.detail}`, 'hint'));
  byId('checklist').replaceChildren(...health.checklist.map(item => element('li', item)));
  byId('recovery').replaceChildren(...health.recovery.map(item => element('p', item, 'notice error')));
  const runs = byId('recent-runs'); runs.replaceChildren();
  if (!health.runs.length) runs.append(element('p', 'No project investigations yet.', 'empty'));
  for (const run of health.runs) {
    const row = element('div', undefined, 'activity-row');
    row.append(element('span', run.state, 'badge'), element('code', run.id));
    runs.append(row);
  }
  const presenceStatus = value.quick_presence;
  const quickStatus = byId('quick-presence-status');
  quickStatus.textContent = presenceStatus?.enabled
    ? (presenceStatus.last_checked ? 'Last quick check ' + new Date(presenceStatus.last_checked).toLocaleString() + '.' : 'Waiting for the first quick check.')
      + (presenceStatus.issues.length ? ' ' + presenceStatus.issues.join(' ') : '')
    : 'Quick presence checks are off.';
  const nextDeviceSignature = JSON.stringify([value.devices, value.candidates]);
  if (nextDeviceSignature !== deviceSignature) {
  deviceSignature = nextDeviceSignature;
  renderInventory(value);
  renderTriptych(value);
  }
  if (pendingTab || pendingDevice) {
    const tab = pendingTab, address = pendingDevice; pendingTab = null; pendingDevice = null;
    restoring = true;
    try {
      if (tab) showView(tab);
      if (address && value.devices.some(device => device.address === address)) openDevice(address, false);
    } finally {restoring = false;}
    recordLocation();
  }
  filterDevices();
  const nextFindingSignature = JSON.stringify(value.findings);
  if (nextFindingSignature !== findingSignature) {
  findingSignature = nextFindingSignature;
  const findings = byId('findings'); findings.replaceChildren();
  if (!value.findings.length) findings.append(element('p', 'No comparison findings yet. A baseline establishes the first observations.', 'empty'));
  for (const finding of value.findings) {
    const card = element('article', undefined, 'finding'); card.append(element('h3', finding.title), PrivacyMarkdown.render(finding.explanation));
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
function renderPiHoleSaved(configured, pending = false) {
  byId('pihole-saved').textContent = configured ? 'Saved connection: Pi-hole connection and password are stored on this collector.' + (pending ? ' Waiting to apply saved settings.' : '') : 'Saved connection: no Pi-hole connection is configured yet.';
}
function renderMonitorSettings(settings) {
  monitorEditable = settings.editable;
  byId('save-monitoring').disabled = !monitorEditable;
  byId('quick-scan').disabled = !monitorEditable;
  byId('dns-profiles').disabled = !monitorEditable || !settings.pihole_configured;
  byId('device-descriptions').disabled = !monitorEditable;
  byId('settings-detail').textContent = monitoringDraft ? 'Monitoring changes are not saved. Choose Save monitoring to retain them. ' + settings.detail : settings.detail;
  renderPiHoleSaved(settings.pihole_configured, settings.pending);
  byId('pihole-save').disabled = !monitorEditable || piholePending;
  byId('pihole-blocker').textContent = settings.pending || !monitorEditable ? settings.detail : '';
  if (settings.pending) byId('scan').disabled = true;
}
async function piholeConnection(save) {
  if (piholePending) return;
  piholePending = true;
  byId('pihole-test').disabled = true; byId('pihole-save').disabled = true;
  const password = byId('pihole-password').value; byId('pihole-password').value = '';
  byId('pihole-detail').textContent = save ? 'Saving connection and password…' : 'Testing connection; your password will not be saved…';
  try {
    const result = await api('/api/pihole', {method:'POST', body:JSON.stringify({origin:byId('pihole-origin').value.trim(),password,save})});
    const message = result.saved ? 'Pi-hole connection and password saved on this collector. Connection was not tested.' + (result.pending ? ' Saved settings are waiting to apply; existing work keeps its original configuration.' : '') : result.connected ? 'Connection test passed. Your password was not saved; enter it again and choose Save connection to save it.' : 'Connection test failed. Your saved settings are unchanged.';
    byId('pihole-detail').textContent = `${message} ${result.issues.join(' ')} The password field was cleared for privacy.`;
    if (result.saved) {
      renderPiHoleSaved(true, result.pending); settingsLoaded = false;
      // A later health/read failure cannot undo the confirmed save response.
      await refresh().catch(error => notice('Pi-hole was saved, but dashboard refresh failed. ' + error.message, true));
    }
  } catch (error) {byId('pihole-detail').textContent = `${save ? 'Save was not confirmed. ' : 'Connection test failed. '}${error.message} The password field was cleared; enter it again to retry.`;}
  finally {piholePending = false; byId('pihole-test').disabled = false; byId('pihole-save').disabled = !monitorEditable;}
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
function renderScheduleControl(schedule, control, pendingDeployment) {
  byId('schedule-runtime').textContent = schedule ? (schedule.paused ? 'Paused' : 'Running') : 'Unavailable';

  const pending = scheduleControlUnknown || Boolean(control && control.pending);
  const available = Boolean(control && control.available && schedule);
  byId('pause-schedule').disabled = scheduleControlBusy || pending || Boolean(pendingDeployment) || !available || schedule.paused;
  byId('resume-schedule').disabled = scheduleControlBusy || pending || Boolean(pendingDeployment) || !available || !schedule.paused;
  byId('check-schedule').disabled = scheduleControlBusy || !control || !control.available;
  if (pending) byId('schedule-control-detail').textContent = 'A schedule change is unconfirmed. Check schedule state before another change; checking never resends the control.';
}

async function controlSchedule(paused) {
  if (scheduleControlBusy || (paused === null ? byId('check-schedule').disabled : byId(paused ? 'pause-schedule' : 'resume-schedule').disabled)) return;
  scheduleControlBusy = true;
  renderScheduleControl(overview && overview.health.schedule, overview && overview.schedule_control, overview && overview.pending_deployment);
  try {
    const result = await api(paused === null ? '/api/schedule/check' : '/api/schedule/control', {method:'POST',body:JSON.stringify(paused === null ? {} : {paused})});
    scheduleControlUnknown = false;
    if (overview) {overview.health.schedule = result.schedule; overview.schedule_control = result.control;}
    byId('schedule-control-detail').textContent = result.control.pending ? 'The requested state is not confirmed yet. No control was resent; check again later.' : result.schedule.paused ? 'Monitoring schedule is paused. Existing work can finish.' : 'Monitoring schedule is running. It will fire at the next scheduled time; missed scans are not replayed.';
    notice(byId('schedule-control-detail').textContent, result.control.pending);
    // A subsequent display read must not turn a confirmed control into an
    // ambiguous failure or enable a second mutation while the first is pending.
    try {await refresh();} catch (error) {notice(`${byId('schedule-control-detail').textContent} Display refresh failed: ${error.message}`, true);}
  } catch (error) {
    if (paused !== null) scheduleControlUnknown = true;
    byId('schedule-control-detail').textContent = `${error.message} Check schedule state before another change.`;
    notice(byId('schedule-control-detail').textContent, true);
  } finally {
    scheduleControlBusy = false;
    renderScheduleControl(overview && overview.health.schedule, overview && overview.schedule_control, overview && overview.pending_deployment);
  }
}
byId('pause-schedule').addEventListener('click', () => controlSchedule(true));
byId('resume-schedule').addEventListener('click', () => controlSchedule(false));
byId('check-schedule').addEventListener('click', () => controlSchedule(null));

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
function clearBackgroundDraft() {
  selectedResearch = null;
  byId('background-draft').hidden = true; syncPane();
  byId('background-draft-title').textContent = '';
  byId('background-draft-content').replaceChildren();
  byId('background-draft-model').value = '';
  byId('background-draft-feedback').value = '';
}
function clearBackgroundResearch() {
  clearBackgroundDraft();
  byId('background-research-list').replaceChildren();
  byId('background-research-detail').textContent = '';
  byId('background-research-count').textContent = '';
  byId('background-research-enabled').checked = false;
}
const researchPhases = {queued:'Queued for research',pending:'Awaiting admission receipt',running:'Research running',review:'Ready for review',failed:'Needs attention'};
async function loadBackgroundResearch() {
  const queue = await api('/api/profile-research');
  const list = byId('background-research-list');
  if (!researchBusy) byId('background-research-enabled').checked = queue.enabled;
  byId('background-research-detail').textContent = queue.detail || (queue.enabled ? 'Background research enabled.' : 'Background research paused.');
  list.replaceChildren();
  if (!queue.entries.length) list.append(element('p', 'No drafts or research activity to review.', 'hint'));
  for (const entry of queue.entries) {
    const name = entry.label || entry.model || entry.address;
    const button = element('button', name + ' · ' + researchPhases[entry.phase], 'entry');
    button.type = 'button';
    button.addEventListener('click', () => openBackgroundDraft(entry.request_id).catch(error => notice(error.message, true)));
    list.append(button);
  }
}
function backgroundDraftPath(id) {return '/api/profile-research/' + encodeURIComponent(id);}
async function openBackgroundDraft(id) {
  const entry = await api(backgroundDraftPath(id));
  selectedResearch = entry;
  const draft = entry.draft;
  byId('background-draft-title').textContent = entry.identity.hostname || entry.model || entry.identity.address;
  byId('background-draft-model').value = entry.model || '';
  byId('background-draft-feedback').value = '';
  const output = byId('background-draft-content');
  output.replaceChildren(element('p', [entry.identity.address, entry.identity.mac, researchPhases[entry.phase], entry.run_id].filter(Boolean).join(' · '), 'hint'));
  if (draft) {
    const nvdCoverage = {
      searched: 'NVD searched for the confirmed model. Keyword results require an applicability check.',
      model_unconfirmed: 'NVD lookup awaits a confirmed public model.',
      not_configured: 'NVD lookup is disabled on this collector.',
      unavailable: 'NVD lookup was unavailable during this run.',
    };
    output.append(element('p', nvdCoverage[entry.nvd_status] || 'NVD coverage unavailable.', 'hint'));
    for (const [heading, value] of [
      ['Name', draft.name], ['Model hypothesis', draft.model_hypothesis],
      ['Purpose', draft.purpose], ['Expected services', draft.expected_services],
      ['Research notes', draft.notes], ['Questions for you', draft.questions],
    ]) if (value) output.append(element('h4', heading), element('p', value));
    for (const cve of entry.advisory_ids) {
      const link = element('a', cve, 'entry');
      link.href = 'https://nvd.nist.gov/vuln/detail/' + encodeURIComponent(cve);
      link.target = '_blank'; link.rel = 'noopener noreferrer';
      output.append(link);
    }
  }
  byId('background-draft-research').hidden = !['review','failed'].includes(entry.phase);
  byId('background-draft-editor').hidden = entry.phase !== 'review' || byId('device-profiles').hidden;
  byId('background-draft-reviewed').hidden = entry.phase !== 'review';
  byId('background-draft-dismiss').hidden = !['review','failed','queued'].includes(entry.phase);
  byId('background-draft').hidden = false; syncPane();
  byId('background-draft').scrollIntoView({block:'nearest'});
}
byId('background-research-load').addEventListener('click', () => loadBackgroundResearch().catch(error => notice(error.message, true)));
byId('background-research-enabled').addEventListener('change', async () => {
  const toggle = byId('background-research-enabled');
  if (researchBusy) return;
  researchBusy = true; toggle.disabled = true;
  try {
    const saved = await api('/api/profile-research', {method:'POST',body:JSON.stringify({enabled:toggle.checked})});
    toggle.checked = saved.enabled;
    await loadBackgroundResearch();
  } catch (error) {notice(error.message, true);}
  finally {researchBusy = false; toggle.disabled = false;}
});
async function reviewBackgroundDraft(action) {
  if (!selectedResearch || researchBusy) return;
  const id = selectedResearch.request_id;
  const model = action === 'research' ? byId('background-draft-model').value.trim() || null : null;
  const feedback = action === 'research' ? byId('background-draft-feedback').value.trim() : '';
  researchBusy = true;
  try {
    await api(backgroundDraftPath(id), {method:'POST',body:JSON.stringify({action,model,feedback})});
    if (selectedResearch?.request_id === id) clearBackgroundDraft();
    await loadBackgroundResearch();
  } catch (error) {notice(error.message, true);}
  finally {researchBusy = false;}
}
byId('background-draft').addEventListener('submit', event => {
  event.preventDefault(); reviewBackgroundDraft('research');
});
byId('background-draft-reviewed').addEventListener('click', () => reviewBackgroundDraft('reviewed'));
byId('background-draft-dismiss').addEventListener('click', () => reviewBackgroundDraft('dismissed'));
byId('background-draft-editor').addEventListener('click', async () => {
  if (!selectedResearch || researchBusy) return;
  const id = selectedResearch.request_id;
  researchBusy = true;
  try {
    const entry = await api(backgroundDraftPath(id) + '/editor');
    await openDeviceProfile(entry.identity.address, entry.identity.mac, true);
    const draft = entry.draft;
    for (const [field, value] of [['name',draft.name],['model',entry.model],['purpose',draft.purpose],
      ['expected',draft.expected_services],['notes',draft.notes]]) {
      const input = byId('profile-' + field);
      if (value && !input.value.trim()) input.value = value;
    }
    notice('Draft copied into empty profile fields. Confirm the identity and review before saving.');
  } catch (error) {notice(error.message, true);}
  finally {researchBusy = false;}
});

function clearDeviceResearch() {
  researchDevice = null;
  byId('device-research').hidden = true; syncPane();
  byId('research-model').value = '';
  byId('research-title').textContent = 'Research a device';
  byId('research-observation').textContent = '';
  byId('research-unifi-result').textContent = '';
  byId('research-results').replaceChildren();
}
function openDeviceResearch(device) {
  clearDeviceResearch();
  researchDevice = device;
  byId('research-title').textContent = 'Research ' + device.name;
  const identity = device.identity?.source === 'configured' ? null : device.identity;
  const known = identity || device.last_known_identity;
  const clues = [known?.hostname, known?.vendor, ...((device.detection?.clues) || [])].filter(Boolean);
  byId('research-observation').textContent = clues.length
    ? 'Observed clues. Confirm the product before searching. ' + clues.join(' · ')
    : 'No model evidence yet. Enter the public model from the device label or its management page.';
  byId('research-unifi').disabled = !identity?.mac;
  byId('research-search').disabled = !advisoriesAvailable;
  byId('research-profile').disabled = byId('device-profiles').hidden;
  byId('device-research').hidden = false; syncPane();
  byId('device-research').scrollIntoView({block:'nearest'});
}
function researchPath(device, operation) {
  return '/api/devices/' + encodeURIComponent(device.address) + '/' + operation;
}
byId('close-research').addEventListener('click', clearDeviceResearch);
byId('research-unifi').addEventListener('click', async () => {
  const device = researchDevice;
  if (!device?.identity?.mac) return;
  const result = byId('research-unifi-result');
  byId('research-unifi').disabled = true;
  result.textContent = 'Checking the controller…';
  try {
    const {available, product} = await api(researchPath(device, 'product'), {
      method:'POST', body:JSON.stringify({device_id:device.identity.device_id})
    });
    if (device !== researchDevice) return;
    if (!product) {
      result.textContent = available
        ? 'No unique online adopted UniFi device matched the observed MAC.'
        : 'UniFi is unavailable on this collector.';
      return;
    }
    const model = product.manufacturer + ' ' + product.model;
    const firmware = product.firmware ? ' · Firmware ' + product.firmware : '';
    const update = product.firmware_updatable ? ' · UniFi reports an update is available' : '';
    result.textContent = 'Confirmed by UniFi · ' + model + firmware + update;
    if (!byId('research-model').value.trim()) byId('research-model').value = model;
  } catch (error) {
    if (device === researchDevice) result.textContent = error.message;
  } finally {
    if (device === researchDevice) byId('research-unifi').disabled = false;
  }
});
byId('research-form').addEventListener('submit', async event => {
  event.preventDefault();
  const device = researchDevice;
  const model = byId('research-model').value.trim();
  if (!device || !model || !advisoriesAvailable) return;
  const results = byId('research-results');
  byId('research-search').disabled = true;
  results.replaceChildren(element('p', 'Checking NVD…'));
  try {
    const page = await api(researchPath(device, 'advisories'), {method:'POST', body:JSON.stringify({model})});
    if (device === researchDevice) renderAdvisoryPage(results, page);
  } catch (error) {
    if (device === researchDevice) results.replaceChildren(element('p', error.message, 'notice error'));
  } finally {
    if (device === researchDevice) byId('research-search').disabled = !advisoriesAvailable;
  }
});
byId('research-profile').addEventListener('click', async () => {
  const device = researchDevice;
  const model = byId('research-model').value.trim();
  if (!device || !model) return;
  try {
    await openDeviceProfile(device.address, device.identity?.mac || null);
    if (device === researchDevice) byId('profile-model').value = model;
  } catch (error) {notice(error.message, true);}
});
function renderAdvisoryPage(results, page) {
  const detail = page.total
    ? 'Showing ' + page.advisories.length + ' of ' + page.total + ' keyword matches.'
    : 'No keyword matches found.';
  results.replaceChildren(element('p', detail + ' Checked '
    + new Date(page.checked_at).toLocaleString() + '. Check the affected model, firmware and configuration in each advisory.', 'hint'));
  for (const item of page.advisories) {
    const link = element('a', item.cve);
    link.href = 'https://nvd.nist.gov/vuln/detail/' + encodeURIComponent(item.cve);
    link.target = '_blank'; link.rel = 'noopener noreferrer';
    const entry = element('article');
    const metadata = [item.published_on, item.severity ? 'CVSS ' + item.severity.toLowerCase() : null,
      item.known_exploited ? 'CISA lists known exploitation' : null].filter(Boolean);
    entry.append(link, element('p', metadata.join(' · '), 'hint'), element('p', item.description));
    results.append(entry);
  }
}

function advisoryLookup(view) {
  const panel = element('section');
  panel.append(element('h3', 'Product advisories'));
  const model = view.profile.fields.model.trim();
  if (!model || !advisoriesAvailable) {
    panel.append(element('p', model ? 'Advisory lookup is unavailable on this collector.'
      : 'Save the public manufacturer and exact model to look up advisories.', 'hint'));
    return panel;
  }
  panel.append(element('p', 'This lookup sends the saved public model name to NVD: ' + model + '.', 'hint'));
  const results = element('div');
  results.setAttribute('aria-live', 'polite');
  const button = element('button', 'Look up advisories for saved model', 'secondary');
  button.type = 'button';
  button.addEventListener('click', async () => {
    button.disabled = true;
    results.replaceChildren(element('p', 'Checking NVD…'));
    try {
      const path = '/api/profiles/' + encodeURIComponent(view.profile.profile_id)
        + '/' + encodeURIComponent(view.revision) + '/advisories';
      const page = await api(path, {method:'POST', body:JSON.stringify({model})});
      renderAdvisoryPage(results, page);
    } catch (error) {
      results.replaceChildren(element('p', error.message, 'notice error'));
    } finally {
      button.disabled = false;
    }
  });
  panel.append(button, results);
  return panel;
}

async function editProfile(view, address = null, deviceMac = null) {
  await profileReceipts();
  const settings = await api('/api/monitoring');
  const fields = view ? view.profile.fields : {name:'',owner:'',model:'',purpose:'',expected_services:'',notes:'',addresses:address ? [address] : []};
  profileEdit = {id:view ? view.profile.profile_id : crypto.randomUUID(), revision:view ? view.revision : null, deviceMac:view?.profile.fields.device_mac || deviceMac};
  byId('profile-mac').textContent = profileEdit.deviceMac
    ? 'Confirmed device MAC · ' + profileEdit.deviceMac
    : 'Address-only profile. A matching address alone does not establish device identity.';
  for (const [id, key] of [['profile-name','name'],['profile-owner','owner'],['profile-model','model'],['profile-purpose','purpose'],['profile-expected','expected_services'],['profile-notes','notes']]) byId(id).value = fields[key];
  const macs = [...new Set((overview?.devices || []).filter(device => fields.addresses.includes(device.address))
    .map(device => device.identity?.mac).filter(Boolean))];
  byId('profile-bind-mac').hidden = !!profileEdit.deviceMac || macs.length !== 1;
  profileEdit.observedMac = macs.length === 1 ? macs[0] : null;
  const choices = [...new Set([...settings.targets, ...fields.addresses])];
  byId('profile-addresses').replaceChildren(...choices.map(value => {const option = element('option', value); option.value = value; option.selected = fields.addresses.includes(value); return option;}));
  await renderIssueLinks(fields.issues || [], view?.evidence || []);
  byId('profile-editor-title').textContent = view ? `Edit ${fields.name}` : 'Create a device profile';
  // The editor opens in the side pane, in place; the page does not move.
  if (byId('view-devices').hidden) showView('devices');
  byId('profile-form').hidden = false; syncPane();
  const history = byId('profile-history'); history.replaceChildren(); byId('profile-markdown').hidden = true;
  if (view) {
    history.append(advisoryLookup(view));
    history.append(element('h3', 'Retained profile revisions'));
    for (const version of [...view.versions].sort((a,b) => b.ordinal - a.ordinal)) {
      const read = element('button', `Revision ${version.ordinal} · ${new Date(version.created_at).toLocaleString()}`, 'entry');
      read.title = version.revision;
      read.addEventListener('click', async () => {try {const source = await api(`/api/profiles/${view.profile.profile_id}/${version.revision}`); byId('profile-markdown').replaceChildren(renderProfileDocument(source.text)); byId('profile-markdown').hidden = false; syncPane(); byId('profile-markdown').scrollIntoView({block:'nearest'});} catch(error) {notice(error.message,true);}}); history.append(read);
    }
    history.append(element('h3', 'Recent retained evidence at associated addresses'));
    if (!view.evidence.length) history.append(element('p', 'No matching evidence among the latest 50 local collections.'));
    for (const revision of view.evidence) {
      const read = element('button', revision, 'entry');
      read.addEventListener('click', async () => {try {const scans = await api('/api/scans'); const scan = scans.find(value => value.revision === revision); if (!scan) throw new Error('Evidence is outside the current local history window.'); await showEvidence(scan.scan_id);} catch(error) {notice(error.message,true);}}); history.append(read);
    }
  }
  byId('profile-form').scrollIntoView({block:'nearest'});
  byId('profile-name').focus({preventScroll:true});
}
async function openDeviceProfile(address, mac = null, bind = false) {
  const profiles = await loadProfiles();
  const matches = profiles.filter(value => mac
    ? value.profile.fields.device_mac === mac
    : !value.profile.fields.device_mac && value.profile.fields.addresses.includes(address));
  const legacy = profiles.some(value => !value.profile.fields.device_mac && value.profile.fields.addresses.includes(address));
  if (matches.length > 1) throw new Error('Several profiles claim this device. Review their retained documents before editing.');
  if (mac && !matches.length && legacy) throw new Error('This address has a legacy profile. Review it in the knowledge base before confirming a MAC association.');
  await editProfile(matches[0] || null, address, bind ? mac : null);
}

byId('profile-bind-mac').addEventListener('click', () => {
  if (!profileEdit || profileEdit.deviceMac || !profileEdit.observedMac) return;
  profileEdit.deviceMac = profileEdit.observedMac;
  byId('profile-mac').textContent = 'Confirming MAC on save · ' + profileEdit.deviceMac;
  byId('profile-bind-mac').hidden = true;
});
byId('load-profiles').addEventListener('click', () => loadProfiles().catch(error => notice(error.message,true)));
byId('new-profile').addEventListener('click', () => editProfile(null).catch(error => notice(error.message,true)));
byId('cancel-profile').addEventListener('click', () => {profileEdit = null; byId('profile-form').hidden = true; byId('profile-markdown').hidden = true; byId('profile-history').replaceChildren(); syncPane();});
byId('profile-form').addEventListener('submit', async event => {
  event.preventDefault(); if (!profileEdit || profileBlocked) return;
  byId('save-profile').disabled = true;
  const fields = {name:byId('profile-name').value,owner:byId('profile-owner').value,model:byId('profile-model').value,purpose:byId('profile-purpose').value,expected_services:byId('profile-expected').value,notes:byId('profile-notes').value,addresses:[...byId('profile-addresses').selectedOptions].map(value => value.value),issues:readIssueLinks(),device_mac:profileEdit.deviceMac || null};
  try {
    const receipt = await api('/api/profiles', {method:'POST',body:JSON.stringify({profile_id:profileEdit.id,request_id:crypto.randomUUID(),expected_revision:profileEdit.revision,fields})});
    if (receipt.phase === 'confirmed') {profileEdit = null; byId('profile-form').hidden = true; byId('profile-markdown').hidden = true; byId('profile-history').replaceChildren(); syncPane(); const shown = selectedDevice && overview?.devices.find(item => item.address === selectedDevice); if (shown) loadDeviceAnalysis(shown); notice('Device profile saved in Plowshare. Processing may still be pending; load profiles again when ready.');}
    else notice(`Profile write ${receipt.request_id} needs reconciliation. It has not been resent.`, true);
  } catch(error) {notice(error.message,true);} finally {await profileReceipts().catch(() => {byId('save-profile').disabled = true;});}
});
byId('reconcile-profiles').addEventListener('click', async () => {try {await api('/api/profiles/reconcile',{method:'POST',body:'{}'}); await loadProfiles(); notice('Retained profile writes checked; no mutation was resent.');} catch(error) {notice(error.message,true);}});

// Shared issue revisions are deliberately pinned by each device's reviewed link.
// All text stays inert; citations are displayed rather than fetched by this app.
function clearIssueEditor() {
  issueEdit = null; issueBlocked = false; issuesAvailable = false;
  byId('issue-form').reset(); byId('issue-form').hidden = true; byId('issue-markdown').hidden = true; syncPane();
  for (const id of ['issue-records','issue-intents','issue-history','issue-markdown','issue-sources']) byId(id).replaceChildren();
}
function optionList(choices, selected) {
  return choices.map(([value, label]) => {const option = element('option', label); option.value = value; option.selected = value === selected; return option;});
}
function labelled(parent, title, control) {
  const label = element('label', title); label.append(control); parent.append(label); return control;
}
function sourceRow(source = {url:'',published_on:null}) {
  if (byId('issue-sources').children.length >= 4) return;
  const row = element('div', undefined, 'citation-row');
  const url = element('input'); url.type = 'url'; url.required = true; url.maxLength = 1024; url.value = source.url; url.dataset.field = 'url';
  labelled(row, 'HTTPS source URL', url);
  const date = element('input'); date.type = 'date'; date.value = source.published_on || ''; date.dataset.field = 'published_on'; labelled(row, 'Published on (optional)', date);
  const remove = element('button', 'Remove source', 'secondary'); remove.type = 'button'; remove.addEventListener('click', () => row.remove()); row.append(remove); byId('issue-sources').append(row);
}
function ruleHelp(normalize = false) {
  const mechanism = byId('issue-mechanism').value;
  if (normalize) {
    byId('issue-direction').value = mechanism === 'firewall' || mechanism === 'pihole' ? 'outbound' : 'not_applicable';
    byId('issue-protocol').value = mechanism === 'firewall' ? 'tcp' : 'not_applicable';
    byId('issue-action').value = mechanism === 'device_setting' ? 'disable' : mechanism === 'segmentation' ? 'restrict' : 'block';
    byId('issue-ports').value = ''; byId('issue-source-kind').value = 'device'; byId('issue-destination-kind').value = 'peer'; byId('issue-source').value = ''; byId('issue-destination').value = '';
  }
  const firewall = mechanism === 'firewall';
  byId('issue-direction').disabled = !firewall; byId('issue-protocol').disabled = !firewall; byId('issue-action').disabled = mechanism === 'pihole';
  byId('issue-source-kind').disabled = !firewall; byId('issue-destination-kind').hidden = !firewall;
  if (!firewall) {byId('issue-source-kind').value = 'device'; byId('issue-destination-kind').value = 'peer';}
  byId('issue-source').disabled = byId('issue-source-kind').value === 'device';
  byId('issue-source-address-label').hidden = byId('issue-source').disabled;
  byId('issue-destination-choice-label').hidden = !firewall;
  byId('issue-destination').disabled = firewall && byId('issue-destination-kind').value === 'device';
  byId('issue-destination-address-label').hidden = byId('issue-destination').disabled;
  byId('issue-ports').disabled = mechanism !== 'firewall' || !['tcp','udp'].includes(byId('issue-protocol').value);
  if (byId('issue-ports').disabled) byId('issue-ports').value = '';
  byId('issue-rule-help').textContent = mechanism === 'firewall' ? 'Specify direction, an exact IP/CIDR or internet, protocol and destination ports. Broad blocks may break essential services. Choose This device as the source for outbound rules or destination for inbound rules. Lateral rules require an explicit private peer. Ports are destination ports in the chosen direction.' : mechanism === 'pihole' ? 'Blocks one exact DNS domain for the linked device. Pi-hole does not enforce port rules; direct-IP and alternative-DNS traffic need separate controls.' : 'Describe the network zone/access boundary or exact privacy setting. Apply through the owning router or device interface.';
}
async function issueReceipts() {
  const intents = await api('/api/issue-intents'); issueBlocked = intents.some(v => v.phase === 'pending');
  byId('save-issue').disabled = issueBlocked; byId('new-issue').disabled = issueBlocked;
  const pending = intents.filter(v => v.phase === 'pending');
  byId('issue-intents').replaceChildren(...(pending.length ? pending.map(v => element('p', `Pending write: ${v.request_id}`)) : [element('p', 'No unsettled issue writes.')]));
  if (issueBlocked) byId('issue-detail').textContent = 'An issue write has an unknown outcome. Check retained writes before saving another change.';
}
async function loadIssues() {
  await issueReceipts(); const result = await api('/api/issues');
  const records = byId('issue-records'); records.replaceChildren();
  if (!result.issues.length) records.append(element('p', 'No shared privacy issues yet. Add an issue with source citations and a proposed mitigation.', 'empty'));
  for (const view of result.issues) {
    const card = element('article', undefined, 'finding');
    card.append(element('h3', view.issue.fields.title), element('p', `Sources checked ${view.issue.fields.checked_on} · ${view.issue.fields.mitigation.mechanism}`, 'hint'));
    const edit = element('button', 'Review issue & mitigation'); edit.addEventListener('click', () => editIssue(view).catch(error => notice(error.message,true))); card.append(edit); records.append(card);
  }
  if (!issueBlocked) byId('issue-detail').textContent = `${result.issues.length} shared issues loaded. Link reviewed plans from a device profile.`;
  return result.issues;
}
async function editIssue(view = null) {
  await issueReceipts(); byId('issue-form').reset(); byId('issue-sources').replaceChildren();
  issueEdit = {id:view ? view.issue.issue_id : crypto.randomUUID(), revision:view ? view.revision : null};
  const fields = view?.issue.fields;
  byId('issue-editor-title').textContent = fields ? `Edit ${fields.title}` : 'Create a privacy issue';
  for (const [id,key] of [['issue-title','title'],['issue-description','description'],['issue-models','affected_models'],['issue-firmware','firmware'],['issue-conditions','conditions'],['issue-checked','checked_on']]) byId(id).value = fields ? fields[key] : key === 'checked_on' ? new Date().toISOString().slice(0,10) : '';
  for (const source of fields?.sources || [{url:'',published_on:null}]) sourceRow(source);
  if (fields) {
    for (const key of ['mechanism','direction','action','source','destination','protocol','reason','impact','verification','rollback']) byId(`issue-${key}`).value = fields.mitigation[key];
    for (const endpoint of ['source','destination']) {byId(`issue-${endpoint}-kind`).value = fields.mitigation[endpoint] === 'linked_device' ? 'device' : 'peer'; if (fields.mitigation[endpoint] === 'linked_device') byId(`issue-${endpoint}`).value = '';}
    byId('issue-ports').value = fields.mitigation.ports.join(', '); ruleHelp();
  } else ruleHelp(true);
  byId('issue-history').replaceChildren(); byId('issue-markdown').hidden = true;
  if (view) {
    byId('issue-history').append(element('h3', 'Retained issue revisions'));
    for (const version of [...view.versions].sort((a,b) => b.ordinal - a.ordinal)) {
      const read = element('button', `Revision ${version.ordinal} · ${new Date(version.created_at).toLocaleString()}`, 'entry');
      read.addEventListener('click', () => readIssue(view.issue.issue_id, version.revision).catch(error => notice(error.message,true))); byId('issue-history').append(read);
    }
  }
  if (byId('view-devices').hidden) showView('devices');
  byId('issue-form').hidden = false; syncPane();
  byId('issue-form').scrollIntoView({block:'nearest'});
  byId('issue-title').focus({preventScroll:true});
}
async function readIssue(identity, revision) {
  const source = await api(`/api/issues/${identity}/${revision}`);
  byId('issue-markdown').replaceChildren(PrivacyMarkdown.render(source.text)); byId('issue-markdown').hidden = false; syncPane();
  byId('issue-markdown').scrollIntoView({block:'nearest'});
}
async function renderIssueLinks(links, evidence) {
  if (!issuesAvailable) {
    if (links.length) throw new Error('The linked privacy issue catalogue is unavailable. Your retained links are preserved.');
    byId('profile-issue-links').replaceChildren(element('p', 'Privacy issue management is unavailable on this collector.'));
    return;
  }
  const result = await api('/api/issues');
  const heads = new Map(result.issues.map(v => [v.issue.issue_id,v]));
  const identities = [...new Set([...links.map(v => v.issue_id), ...heads.keys()])];
  const parent = byId('profile-issue-links'); parent.replaceChildren();
  if (!identities.length) parent.append(element('p', 'Create a shared privacy issue below, then reload this profile to link it.'));
  for (const identity of identities) {
    const old = links.find(v => v.issue_id === identity); const head = heads.get(identity);
    const card = element('details', undefined, 'issue-link'); card.open = !!old;
    card.dataset.issue = identity; card.dataset.revision = old?.revision || head.revision;
    card.append(element('summary', head ? head.issue.fields.title : `Retained issue ${identity}`));
    const enabled = element('input'); enabled.type = 'checkbox'; enabled.checked = !!old; enabled.dataset.field = 'linked'; labelled(card, 'Link this issue to this device', enabled);
    const applicability = element('select'); applicability.dataset.field = 'applicability'; applicability.replaceChildren(...optionList(applicabilityLabels, old?.applicability || 'potentially_affected')); labelled(card, 'Device applicability', applicability);
    const status = element('select'); status.dataset.field = 'status'; status.replaceChildren(...optionList(mitigationLabels,old?.mitigation_status || 'proposed')); labelled(card, 'Mitigation progress', status);
    const notes = element('textarea'); notes.rows = 3; notes.maxLength = 512; notes.value = old?.notes || ''; notes.dataset.field = 'notes'; labelled(card, 'Assessment, action taken and verification result', notes);
    const revisions = element('select'); revisions.multiple = true; revisions.dataset.field = 'evidence'; revisions.replaceChildren(...[...new Set([...evidence,...(old?.evidence || [])])].map(value => {const option = element('option', value); option.value = value; option.selected = old?.evidence.includes(value); return option;})); labelled(card, 'Supporting evidence revisions (up to four)', revisions);
    const read = element('button', 'Read reviewed issue & plan'); read.type = 'button'; read.addEventListener('click', () => readIssue(identity,card.dataset.revision).catch(error => notice(error.message,true))); card.append(read);
    if (head && old && head.revision !== old.revision) {
      const updated = element('p', 'A newer issue revision is available. This device still references the reviewed plan.', 'hint');
      const adopt = element('button', 'Review and use latest plan'); adopt.type = 'button'; adopt.addEventListener('click', async () => {
        try {await readIssue(identity,head.revision); card.dataset.revision = head.revision; status.value = 'proposed'; applicability.value = 'unresolved'; notes.value = ''; revisions.selectedIndex = -1; updated.textContent = 'Latest plan selected. Review applicability and progress, then save the device profile.'; adopt.disabled = true;} catch(error) {notice(error.message,true);}
      }); card.append(updated,adopt);
    }
    card.append(element('p', 'Progress is recorded in profile history. Saving does not apply a rule. Explain confirmed issues and applied, verified or reverted actions.', 'hint')); parent.append(card);
  }
}
function readIssueLinks() {
  return [...byId('profile-issue-links').querySelectorAll('.issue-link')].filter(card => card.querySelector('[data-field="linked"]').checked).map(card => ({issue_id:card.dataset.issue,revision:card.dataset.revision,applicability:card.querySelector('[data-field="applicability"]').value,mitigation_status:card.querySelector('[data-field="status"]').value,notes:card.querySelector('[data-field="notes"]').value,evidence:[...card.querySelector('[data-field="evidence"]').selectedOptions].map(v => v.value)}));
}
byId('load-issues').addEventListener('click', () => loadIssues().catch(error => notice(error.message,true)));
byId('new-issue').addEventListener('click', () => editIssue().catch(error => notice(error.message,true)));
byId('cancel-issue').addEventListener('click', () => {issueEdit = null; byId('issue-form').hidden = true; byId('issue-markdown').hidden = true; byId('issue-history').replaceChildren(); syncPane();});
byId('add-issue-source').addEventListener('click', () => sourceRow());
byId('issue-mechanism').addEventListener('change', () => ruleHelp(true));
byId('issue-protocol').addEventListener('change', () => ruleHelp());
for (const endpoint of ['source','destination']) byId(`issue-${endpoint}-kind`).addEventListener('change', () => ruleHelp());
byId('issue-direction').addEventListener('change', () => {
  if (byId('issue-mechanism').value === 'firewall') {byId('issue-source-kind').value = byId('issue-direction').value === 'inbound' ? 'peer' : 'device'; byId('issue-destination-kind').value = byId('issue-direction').value === 'inbound' ? 'device' : 'peer'; ruleHelp();}
});
byId('reconcile-issues').addEventListener('click', async () => {try {await api('/api/issues/reconcile',{method:'POST',body:'{}'}); await loadIssues(); notice('Retained issue writes checked; no change was resent.');} catch(error) {notice(error.message,true);}});
byId('issue-form').addEventListener('submit', async event => {
  event.preventDefault(); if (!issueEdit || issueBlocked) return; byId('save-issue').disabled = true;
  const fields = {};
  for (const [id,key] of [['issue-title','title'],['issue-description','description'],['issue-models','affected_models'],['issue-firmware','firmware'],['issue-conditions','conditions'],['issue-checked','checked_on']]) fields[key] = byId(id).value;
  fields.sources = [...byId('issue-sources').children].map(row => ({url:row.querySelector('[data-field="url"]').value,published_on:row.querySelector('[data-field="published_on"]').value || null}));
  fields.mitigation = {};
  for (const key of ['mechanism','direction','action','source','destination','protocol','reason','impact','verification','rollback']) fields.mitigation[key] = byId(`issue-${key}`).value;
  fields.mitigation.source = byId('issue-source-kind').value === 'device' ? 'linked_device' : byId('issue-source').value;
  fields.mitigation.destination = byId('issue-mechanism').value === 'firewall' && byId('issue-destination-kind').value === 'device' ? 'linked_device' : byId('issue-destination').value;
  fields.mitigation.ports = byId('issue-ports').value.trim() ? byId('issue-ports').value.split(',').map(value => Number(value.trim())) : [];
  try {
    const receipt = await api('/api/issues',{method:'POST',body:JSON.stringify({issue_id:issueEdit.id,request_id:crypto.randomUUID(),expected_revision:issueEdit.revision,fields})});
    if (receipt.phase === 'confirmed') {issueEdit = null; byId('issue-form').hidden = true; syncPane(); notice('Privacy issue saved in Plowshare. Load it again when processing completes, then link it from device profiles.');}
    else notice(`Issue write ${receipt.request_id} needs reconciliation. It has not been resent.`,true);
  } catch(error) {notice(error.message,true);} finally {await issueReceipts().catch(() => {byId('save-issue').disabled = true;});}
});
