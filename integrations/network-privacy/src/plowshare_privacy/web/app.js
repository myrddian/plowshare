'use strict';

// The web bearer exists only in this tab's memory. All untrusted evidence is text.
let bearer = '';
let authenticated = false;
let selected = null;
let selectedReport = null;
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
let advisoriesConfigured = false;
// The open device's retained supplemental facts, so the lookup form starts from
// the model the device reported instead of asking for it again.
let deviceSupplemental = null;
let researchDevice = null;
let selectedResearch = null;
let researchBusy = false;
let issueEdit = null;
let issueBlocked = false;
let issuesAvailable = false;
let assessmentsAvailable = false;
let assessmentSettingsLoaded = false;
// The latest status read; the attention band and Settings summary use it.
let latestStatus = null;
// Device ID to its assessment summary, from the latest overview. Only devices
// in the current inventory are shown, so a ledger entry for a device that has
// left monitoring never inflates a count.
let assessmentIndex = new Map();
const byId = (name) => document.getElementById(name);

const views = {
  overview: ['Network overview', 'What is on the network, who it talks to, how much it sends and what cannot be seen.'],
  devices: ['Your devices', 'Device observations, profiles and linked privacy issues.'],
  findings: ['Findings and investigations', 'Review observed changes and read investigation reports.'],
  evidence: ['Collection evidence', 'Retained observations, comparisons and coverage gaps.'],
  settings: ['Monitoring settings', 'Devices, connections and investigation preferences.'],
};

// Switching a view changes presentation only. Keep forms and retained outcomes
// mounted so navigation cannot clear a draft, resubmit work or reset a pending control.
let currentView = 'overview';
let restoring = false;
// The fragment carries the open view and device so reload and Back restore
// them. It is never sent to the server and holds no credential.
// The fragment keeps the tab and, on Devices only, the open device and the
// list's filter and order, so reload, Back and a shared link restore them.
// Filter changes replace the entry rather than adding one per click.
function recordLocation(replace = false) {
  if (restoring || !globalThis.history?.pushState) return;
  const parts = [];
  if (currentView !== 'overview') parts.push('tab=' + currentView);
  if (currentView === 'devices') {
    if (selectedDevice) parts.push('device=' + encodeURIComponent(selectedDevice));
    parts.push(...listParams());
  }
  const target = parts.length ? '#' + parts.join('&') : '';
  if (location.hash !== target) history[replace ? 'replaceState' : 'pushState'](null, '', location.pathname + target);
}
// Only defaults are left out; anything else is an allowlisted value.
function listParams() {
  const parts = [];
  if (signalFilter !== 'all') parts.push('show=' + signalFilter);
  if (byId('device-presence').value === 'candidate') parts.push('presence=candidate');
  if (byId('device-sort').value !== 'attention') parts.push('order=' + byId('device-sort').value);
  return parts;
}
function applyListParams(params) {
  const show = params.get('show'), order = params.get('order');
  setSignalFilter(Object.hasOwn(signalNames, show || '') ? show : 'all');
  byId('device-presence').value = params.get('presence') === 'candidate' ? 'candidate' : 'observed';
  const sort = Object.hasOwn(inventoryOrders, order || '') ? order : 'attention';
  if (byId('device-sort').value !== sort) {byId('device-sort').value = sort; if (overview) renderInventory(overview);}
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

// Two message channels. condition() is the collector's standing state: every
// refresh rewrites it. notice() reports the outcome of something the operator
// did; it stays until it is replaced or dismissed, and its bar is fixed to the
// viewport so it is readable while scrolled or with the device pane open.
function notice(message, error = false) {
  byId('notice-bar').hidden = !message;
  byId('notice-bar').classList.toggle('error', error);
  byId('notice').textContent = message;
}
function condition(message, error = false) {
  byId('condition').textContent = message;
  byId('condition').classList.toggle('error', error);
}
byId('notice-dismiss').addEventListener('click', () => notice(''));
function element(tag, content, className) {
  const node = document.createElement(tag);
  if (content !== undefined) node.textContent = content;
  if (className) node.className = className;
  return node;
}
// One way to write counts, dates and states, so the same fact reads the same
// in every view. Dates follow the browser's locale with the month spelled
// out, in the household time zone the collector reports (the one assessment
// reports and daily limits use), with the zone named on every time. A
// date-only value is a calendar day and is never shifted by time zone.
const countFormat = new Intl.NumberFormat();
let householdZone = 'UTC';
let whenFormat, dayFormat;
// dateStyle cannot be combined with timeZoneName, so the parts are explicit.
// The household zone is a dashboard setting. The browser's own zone list
// suggests names; the collector validates whatever is saved.
const browserZone = (() => {try {return new Intl.DateTimeFormat().resolvedOptions().timeZone || null;} catch (error) {return null;}})();
let zoneNamesListed = false, zoneBusy = false;
function knownZones() {
  try {return typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : [];} catch (error) {return [];}
}
function renderZoneSetting(status) {
  const panel = byId('household-zone');
  panel.hidden = !status.settings_available || typeof status.time_zone !== 'string';
  if (panel.hidden) return;
  const configured = !!status.time_zone_configured;
  byId('zone-state').textContent = configured ? status.time_zone : 'Not set · UTC in use';
  if (!zoneNamesListed) {
    zoneNamesListed = true;
    byId('zone-names').replaceChildren(...knownZones().map(name => {const option = element('option'); option.value = name; return option;}));
  }
  const input = byId('zone-input');
  if (!zoneBusy && document.activeElement !== input && !input.dataset?.edited) input.value = configured ? status.time_zone : '';
  const browser = byId('zone-browser');
  browser.hidden = !browserZone || (configured && browserZone === status.time_zone);
  if (browserZone) browser.textContent = `Use this browser’s zone (${browserZone})`;
  byId('zone-clear').hidden = !configured;
}
async function saveZone(zone) {
  if (zoneBusy) return;
  const listed = knownZones();
  if (zone !== null && listed.length && !listed.includes(zone) && zone !== 'UTC') {
    notice(`${zone} is not a time zone this browser knows. Choose a name from the list, such as Europe/London.`, true);
    return;
  }
  zoneBusy = true;
  const button = byId(zone === null ? 'zone-clear' : 'zone-save');
  setBusy(button, true);
  try {
    const result = await api('/api/time-zone', {method:'POST', body:JSON.stringify({timeZone:zone})});
    if (byId('zone-input').dataset) delete byId('zone-input').dataset.edited;
    setHouseholdZone(result.time_zone);
    deviceSignature = '';
    notice(result.time_zone_configured ? `Time zone set to ${result.time_zone}. Dates now show in it.` : 'Time zone removed. The collector uses UTC.');
    await refresh();
  } catch (error) {notice(error.message, true);}
  finally {zoneBusy = false; setBusy(button, false);}
}
byId('zone-form').addEventListener('submit', event => {event.preventDefault(); saveZone(byId('zone-input').value.trim());});
byId('zone-input').addEventListener('input', () => {if (byId('zone-input').dataset) byId('zone-input').dataset.edited = '1';});
byId('zone-browser').addEventListener('click', () => {if (browserZone) {byId('zone-input').value = browserZone; saveZone(browserZone);}});
byId('zone-clear').addEventListener('click', () => saveZone(null));
function setHouseholdZone(zone) {
  try {
    whenFormat = new Intl.DateTimeFormat(undefined, {year:'numeric', month:'short', day:'numeric', hour:'numeric', minute:'2-digit', timeZone:zone, timeZoneName:'short'});
    dayFormat = new Intl.DateTimeFormat(undefined, {year:'numeric', month:'short', day:'numeric', timeZone:zone});
    householdZone = zone;
  } catch (error) {
    // The collector validated the zone; a browser without it shows UTC, and
    // every time still names its zone, so nothing reads as household time.
    if (zone === 'UTC') throw error;
    setHouseholdZone('UTC');
  }
}
setHouseholdZone('UTC');
const calendarDayFormat = new Intl.DateTimeFormat(undefined, {dateStyle:'medium', timeZone:'UTC'});
function formatCount(value) {return countFormat.format(value);}
function plural(count, one, many) {return `${formatCount(count)} ${count === 1 ? one : many}`;}
function formatWhen(stamp) {
  if (!stamp) return '';
  const date = new Date(stamp);
  return Number.isNaN(date.getTime()) ? String(stamp) : whenFormat.format(date);
}
function formatDay(stamp) {
  if (!stamp) return '';
  const date = new Date(stamp);
  if (Number.isNaN(date.getTime())) return String(stamp);
  return (/^\d{4}-\d{2}-\d{2}$/.test(String(stamp)) ? calendarDayFormat : dayFormat).format(date);
}
function formatRange(from, until) {return `${formatWhen(from)} to ${formatWhen(until)}`;}
// A state word from the collector or Plowshare, shown in sentence case.
function statusLabel(value) {
  const text = String(value ?? '').replaceAll('_', ' ').trim();
  return text ? text[0].toUpperCase() + text.slice(1) : 'Unknown';
}
// The control that is working says so; markBusy is for a control whose
// disabled state is decided elsewhere.
function markBusy(button, busy) {button.setAttribute?.('aria-busy', String(busy));}
function setBusy(button, busy) {button.disabled = busy; markBusy(button, busy);}
// A domain may wrap only after a dot, so a label is never split mid-word and
// copied text is still the exact name.
function domainName(domain) {
  const name = element('span', undefined, 'domain');
  const labels = String(domain).split('.');
  labels.forEach((label, index) => {
    if (index) name.append(element('wbr'));
    name.append(element('span', index < labels.length - 1 ? label + '.' : label));
  });
  return name;
}
// Work that spends model calls is confirmed in place: the first press says
// what it costs, above the button that then sends it. Cancel restores the
// first button and nothing is sent.
function confirmedAction(label, className, confirmLabel, cost, action, disabled = false) {
  const group = element('div', undefined, 'actions confirm');
  const start = element('button', label, className);
  start.type = 'button';
  start.disabled = disabled;
  start.addEventListener('click', () => {
    const go = element('button', confirmLabel);
    const cancel = element('button', 'Cancel', 'quiet');
    go.type = 'button'; cancel.type = 'button';
    const restore = () => {group.replaceChildren(start); start.focus();};
    cancel.addEventListener('click', restore);
    go.addEventListener('click', async () => {
      setBusy(go, true); cancel.disabled = true;
      try {await action();} catch (error) {notice(error.message, true);}
      finally {if (group.isConnected) restore();}
    });
    group.replaceChildren(element('p', cost, 'hint'), go, cancel);
    go.focus();
  });
  group.append(start);
  return group;
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
  if (!values.length) {parent.append(element('p', 'None recorded.', 'hint')); return;}
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
  panel.append(element('p', `Observed ${formatWhen(evidence.snapshot.observed_at)}`));
  const devices = evidence.snapshot.devices || [];
  if (devices.length) {
    panel.append(element('h3', 'Observed devices'));
    const table = element('table', undefined, 'data-table');
    const header = element('tr');
    ['Device', 'Address', 'MAC or identity', 'Observed association'].forEach(title => header.append(element('th', title)));
    table.append(header);
    devices.forEach(device => {
      const row = element('tr');
      row.append(element('td', device.label || device.hostname || 'Unnamed device'), element('td', device.address, 'data'),
        element('td', device.mac || device.device_id, 'data'),
        element('td', device.last_seen ? `${device.source} · ${formatWhen(device.last_seen)}` : 'Configured address; MAC not observed'));
      table.append(row);
    });
    panel.append(table);
  }
  if (evidence.snapshot.dns_window) {
    const window = evidence.snapshot.dns_window;
    panel.append(element('p', `Pi-hole DNS window: ${formatRange(window.from_at, window.until_at)}. Read ${formatCount(window.queries_read)} of ${formatCount(window.queries_available)} matching recorded lookups${window.complete ? '.' : '; coverage is incomplete. See gaps below.'}`, 'hint source'));
  }
  listItems(panel, 'Changes against the previous collection', evidence.changes);
  listItems(panel, 'Coverage and gaps', evidence.issues);
  const raw = element('details');
  raw.append(element('summary', 'Details · evidence revision and raw observations'),
    element('p', result.revision ? `Evidence revision ${result.revision}` : 'Not yet retained in Plowshare.', 'hint'),
    element('pre', JSON.stringify(evidence.snapshot, null, 2)));
  if (overview) {
    const scan = (await api('/api/scans')).find(value => value.scan_id === scanId);
    if (scan?.investigation) panel.append(element('p', `Investigation: ${scan.investigation.reason}`));
    const manual = overview.investigations.find(value => value.scan_id === scanId && value.phase !== 'refused');
    if (manual) panel.append(element('p', `Explicit investigation: ${statusLabel(manual.phase)}${manual.run_id ? '' : ' · inspect its retained receipt'}`));
    else if (scan?.phase === 'done' && scan.investigation?.requested === false) {
      panel.append(confirmedAction('Investigate this evidence', undefined, 'Confirm investigation',
        'This starts one investigation of the retained evidence and may incur model costs beyond the automatic limit.',
        async () => {
          await api('/api/investigations', {method:'POST', body:JSON.stringify({scan_id:scanId, request_id:crypto.randomUUID()})});
          await refresh(); await showEvidence(scanId);
        }));
    }
    if (manual?.run_id) raw.append(element('p', `Investigation run ${manual.run_id}`, 'hint'));
  }
  const reports = await api('/api/reports');
  const associated = reports.filter(value => (value.inputs || []).includes(result.revision));
  panel.append(element('h3', 'Reports citing this evidence'));
  if (!associated.length) panel.append(element('p', 'No retained report cites this revision yet.', 'empty'));
  for (const report of associated) {
    const button = element('button', report.title, 'entry');
    button.type = 'button';
    button.addEventListener('click', () => showReport(report.revision).catch(error => notice(error.message, true)));
    panel.append(button);
  }
  panel.append(raw);
}
async function showReport(revision) {
  const result = await api(`/api/reports/${encodeURIComponent(revision)}`);
  const document = foldDetails(PrivacyMarkdown.render(result.text));
  showView('findings', true);
  selectedReport = revision;
  for (const entry of byId('reports').querySelectorAll('.entry')) {
    const current = entry.dataset.revision === revision;
    entry.classList.toggle('selected', current);
    entry.setAttribute('aria-current', String(current));
  }
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
    button.setAttribute('aria-current', String(selected === scan.scan_id));
    const row = element('span', undefined, 'entry-line');
    row.append(element('span', formatWhen(scan.occurred_at)), element('span', phases[scan.phase] || statusLabel(scan.phase), 'badge'));
    button.append(row, element('small', `${plural(scan.change_count ?? scan.changes.length, 'comparison entry', 'comparison entries')} · ${plural(scan.issue_count ?? scan.issues.length, 'coverage note', 'coverage notes')}${scan.mode === 'fixture' ? ' · fixture' : ''}`));
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
    const button = element('button', undefined, `entry${selectedReport === report.revision ? ' selected' : ''}`);
    button.type = 'button';
    button.setAttribute('aria-current', String(selectedReport === report.revision));
    button.dataset.revision = report.revision; button.dataset.title = report.title;
    const row = element('span', undefined, 'entry-line');
    row.append(element('span', report.title), element('span', statusLabel(report.status), 'badge'));
    button.append(row);
    button.addEventListener('click', () => {showReport(report.revision).catch(error => notice(error.message, true));});
    container.append(button);
  });
}
async function refresh() {
  if ((!bearer && !authenticated) || refreshPending) return;
  refreshPending = true;
  setBusy(byId('refresh'), true);
  try {
    const [status, scans, reportResult] = await Promise.all([api('/api/status'), api('/api/scans'), api('/api/reports').then(value => ({value}), error => ({error}))]);
    if (!Array.isArray(scans) || typeof status.state !== 'string' || !Array.isArray(status.requests)) throw new Error('Unexpected dashboard response.');
    latestStatus = status;
    byId('mode').textContent = status.collection_enabled === false ? 'Collection disabled' : status.mode === 'fixture' ? 'Synthetic fixture mode' : 'TCP collection mode';
    // Sample data must never pass for the household's own network.
    byId('mode').classList.toggle('synthetic', status.mode === 'fixture');
    byId('fixture-banner').hidden = status.mode !== 'fixture';
    byId('device-profiles').hidden = !status.profiles_available;
    byId('background-research').hidden = !status.profile_research_available;
    if (!researchBusy) byId('background-research-enabled').checked = !!status.profile_research_enabled;
    const waitingDrafts = status.profile_drafts_waiting || 0;
    byId('background-research-count').textContent = waitingDrafts ? formatCount(waitingDrafts) + ' awaiting review' : '';
    if (typeof status.time_zone === 'string' && status.time_zone !== householdZone) {
      setHouseholdZone(status.time_zone);
      deviceSignature = '';
    }
    renderZoneSetting(status);
    if (Number.isInteger(status.max_ports)) {
      maxPorts = status.max_ports;
      byId('ports-help').textContent = `Up to ${maxPorts} ports, used both to find devices and to watch them. Each extra port adds time to Find devices.`;
    }
    advisoriesAvailable = !!status.advisories_available;
    advisoriesConfigured = !!status.advisories_configured;
    byId('research-search-status').textContent = advisoriesAvailable
      ? 'Advisory search sends only this public model name to NVD. Review it before searching.'
      : advisoriesConfigured
        ? 'Searching here needs device profiles, which are off on this collector. Automatic lookups still appear under What we know.'
        : 'No advisory source is configured on this collector, so advisories cannot be searched. You can still review product clues and edit a profile.';
    if (!researchDevice) byId('research-search').disabled = !advisoriesAvailable;
    issuesAvailable = !!status.issues_available;
    assessmentsAvailable = !!status.device_assessments_available;
    byId('background-assessments').hidden = !assessmentsAvailable;
    if (assessmentsAvailable && !assessmentSettingsLoaded) await loadAssessmentSettings();
    byId('privacy-issues').hidden = !issuesAvailable;
    byId('project').textContent = status.project;
    byId('collector').textContent = status.collector;
    byId('state').textContent = status.state === 'attention_required' ? 'Needs attention' : status.collection_enabled === false ? 'Disabled' : statusLabel(status.state);
    byId('detail').textContent = status.state !== 'attention_required' && status.collection_enabled === false ? 'Enable collection in Settings when you are ready.' : status.detail;
    byId('change-count').textContent = scans.length ? formatCount(scans[0].change_count ?? scans[0].changes.filter(change => !change.startsWith('Baseline')).length) : '—';
    byId('report-count').textContent = reportResult.error ? 'Unavailable' : formatCount(reportResult.value.length);
    scanState.blocked = status.state === 'attention_required' || status.collection_enabled === false;
    applyScanState();
    renderCollections(scans);
    if (reportResult.error) byId('reports').replaceChildren(element('p', 'Retained agent reports are temporarily unavailable. Local evidence remains readable.', 'empty'));
    else if (Array.isArray(reportResult.value)) renderReports(reportResult.value);
    else throw new Error('Unexpected reports response.');
    if (status.settings_available && !settingsLoaded) await loadSettings();
    if (status.settings_available && !settingsPending) {
      const settings = await api('/api/monitoring');
      renderMonitorSettings(settings);
    }
    try {overview = await api('/api/overview'); renderOverview(overview);} catch (error) {byId('health').replaceChildren(); byId('health-detail').textContent = error.message; byId('health-summary').textContent = 'Status unavailable';}
    byId('dashboard').hidden = false;
    byId('connect').hidden = true;
    const unknown = status.requests.find(request => request.state === 'pending');
    // The standing condition only: a refresh never clears what an action reported.
    if (unknown) condition(`Request ${unknown.request_id} needs reconciliation. It has not been resent.`, true);
    else if (reportResult.error) condition(reportResult.error.message, true);
    else condition(status.state === 'attention_required' ? status.detail : '', status.state === 'attention_required');
    await pollAssessment().catch(error => notice(error.message, true));
    await pollDeepScan().catch(error => notice(error.message, true));
  } finally {refreshPending = false; setBusy(byId('refresh'), false);}
}
byId('connect').addEventListener('submit', event => {
  event.preventDefault();
  bearer = byId('token').value;
  byId('token').value = '';
  condition('Reading collector status and retained evidence…');
  refresh().catch(error => {bearer = ''; condition(error.message, true);});
});
byId('refresh').addEventListener('click', () => {refresh().catch(error => condition(error.message, true));});
byId('disconnect').addEventListener('click', async () => {
  try {await api('/api/session/logout', {method:'POST', body:'{}'});} catch (error) {notice(error.message, true); return;}
  clearIssueEditor();
  clearDeviceResearch();
  clearBackgroundResearch();
  profileEdit = null; profileBlocked = false; byId('profile-form').hidden = true; byId('profile-markdown').hidden = true;
  for (const id of ['profile-name','profile-owner','profile-model','profile-purpose','profile-expected','profile-notes']) byId(id).value = '';
  for (const id of ['profile-records','profile-intents','profile-history','profile-markdown','profile-addresses','profile-issue-links']) byId(id).replaceChildren();
  monitoringDraft = false;
  bearer = ''; authenticated = false; selected = null; selectedReport = null; settingsLoaded = false; preferencesLoaded = false; overview = null; scheduleDraft = null; liveCron = null; deviceSignature = null; findingSignature = null; notified = null;
  for (const id of ['health','checklist','recovery','recent-runs','device-cards','moves','findings','q-third','q-silent','q-volume']) byId(id).replaceChildren();
  for (const id of ['q-network','q-third-coverage','device-shown','health-detail']) byId(id).textContent = '';
  byId('device-search').value = ''; byId('device-presence').value = 'observed'; byId('device-sort').value = 'attention';
  Object.assign(scanState, {blocked:false, pending:false, busy:false}); paneSignature = null;
  latestStatus = null; assessmentIndex = new Map(); healthOpened = false;
  for (const id of ['attention-items','health-connections','health-deployment','background-usage']) byId(id).replaceChildren();
  byId('attention').hidden = true; byId('attention-clear').hidden = true; byId('background-work').hidden = true;
  setSignalFilter('all');
  selectedDevice = null; deviceSupplemental = null; deepScanPane = null; deepScanBusy = null; setHouseholdZone('UTC');
  byId('deep-scan-group').hidden = true; byId('deep-scan-limit').value = ''; byId('deep-scan-limit').disabled = false; byId('deep-scan-save').disabled = false; byId('deep-scan-detail').textContent = '';
  byId('household-zone').hidden = true; byId('zone-input').value = '';
  for (const id of deviceParts) byId(id).replaceChildren();
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
  byId('mode').textContent = 'Not connected'; condition('Connect to view this collector’s private evidence.'); notice('Disconnected. Private evidence has been cleared from view.');
});
// Request a scan is off while the collector cannot collect, while saved
// settings wait to apply, and while a request is in flight. Every path that
// changes one of these goes through applyScanState, so none re-enables it.
const scanState = {blocked:false, pending:false, busy:false};
function applyScanState() {
  byId('scan').disabled = scanState.blocked || scanState.pending || scanState.busy;
  markBusy(byId('scan'), scanState.busy);
}
byId('scan').addEventListener('click', async () => {
  if (scanState.busy) return;
  scanState.busy = true; applyScanState();
  try {
    if (!globalThis.crypto?.randomUUID) throw new Error('Scan requests need a secure browser context: use HTTPS or a loopback development listener.');
    const requestId = crypto.randomUUID();
    await api('/api/scans', {method:'POST', body:JSON.stringify({request_id:requestId})});
    notice('Scan requested. Its evidence appears under Evidence when the scan finishes.');
    await refresh();
  } catch (error) {notice(error.message, true);}
  finally {scanState.busy = false; applyScanState();}
});


// The collector reports its port bound; finding devices and monitoring share it.
let maxPorts = 16;
function portSelection() {
  const ports = byId('ports').value.split(',').map(value => value.trim()).filter(Boolean).map(Number);
  if (ports.length > maxPorts || ports.some(port => !Number.isInteger(port) || port < 1 || port > 65535) || new Set(ports).size !== ports.length) throw new Error(`Enter up to ${maxPorts} unique TCP ports between 1 and 65535.`);
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
    const label = element('label', undefined, 'check');
    const checkbox = element('input'); checkbox.type = 'checkbox'; checkbox.value = device.address; checkbox.checked = targets.has(device.address);
    checkbox.addEventListener('change', () => {
      const draft = byId('targets').value;
      const selectedTargets = new Set(draft.includes('/') ? [...container.querySelectorAll('input:checked')].map(input => input.value) : draft.split(',').map(value => value.trim()).filter(Boolean));
      monitoringDraft = true;
      if (checkbox.checked) selectedTargets.add(device.address); else selectedTargets.delete(device.address);
      byId('targets').value = [...selectedTargets].join(', ');
      if (selectedTargets.size) byId('enabled').checked = true;
    });
    const description = element('span', device.address, 'data');
    description.append(element('small', device.open_ports.length ? `Open ports: ${device.open_ports.join(', ')}` : 'Responded; selected ports closed'));
    label.append(checkbox, description); container.append(label);
  });
  byId('discovery-detail').textContent = `${plural(report.devices.length, 'responding address', 'responding addresses')}. Select up to 256 devices, or monitor the whole /24 subnet. ${report.coverage}`;
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
  const sources = [settings.pihole_configured && 'Pi-hole v6 for identity and DNS', settings.unifi_configured && 'UniFi for connected-device identity'].filter(Boolean);
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
  advertising:'Advertising', measurement:'Audience measurement and content recognition',
  telemetry:'Vendor telemetry and logging', streaming:'Streaming services',
  unclassified:'Unclassified', connectivity:'Connectivity checks and time',
  local:'Local names and reverse lookups',
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
  section.append(element('p', `UniFi byte counters · ${formatRange(traffic.from_at, traffic.until_at)} · ${plural(traffic.samples, 'quick check', 'quick checks')}`, 'hint source'));
  if (traffic.resets) section.append(element('p', `${plural(traffic.resets, 'interval', 'intervals')} skipped after a counter reset (reconnect or controller restart). Totals are a lower bound.`, 'limit'));
  section.append(element('p', 'Direction follows UniFi: “to device” is what the network sent the device. Counters show volume, not which hosts received it.', 'limit'));
  section.append(element('p', 'Totals include local traffic, such as a camera recording to a local video recorder or a backup to a NAS, not only internet traffic.', 'limit'));
  return section;
}
function renderDnsActivity(activity) {
  const labels = destinationLabels;
  const section = element('section', undefined, 'readout dns');
  section.append(element('h4', `DNS activity · ${plural(activity.queries_read, 'sampled lookup', 'sampled lookups')} in the last 24 hours`));
  if (activity.pihole_silent) {
    const hours = Math.floor((new Date(activity.until_at) - new Date(activity.from_at)) / 3600000);
    section.append(element('p', `No DNS lookups reached Pi-hole in ${plural(activity.windows, 'confirmed check', 'confirmed checks')} over ${hours} hours while this device was on the network. It may be idle, or it may use its own DNS servers or DNS over HTTPS, which Pi-hole cannot see.`, 'gap-note'));
  }
  section.append(element('p', `Pi-hole · ${formatRange(activity.from_at, activity.until_at)} · ${plural(activity.windows, 'observed window', 'observed windows')}`, 'hint source'));
  if (activity.partial_windows) section.append(element('p', `Partial coverage in ${plural(activity.partial_windows, 'window', 'windows')}. Pi-hole reported ${formatCount(activity.queries_available)} matching lookups in the sampled windows.`, 'limit'));
  section.append(element('p', 'Counts are sampled DNS lookups, not connections or bytes. Traffic volume is under How much it sends; which host received those bytes is not observed.', 'limit'));
  const most = Math.max(1, ...activity.groups.flatMap(group => group.destinations.map(entry => entry.queries)));
  for (const group of activity.groups) {
    const behavioural = behaviouralKinds.has(group.kind);
    section.append(element('h5', `${labels[group.kind] || statusLabel(group.kind)} · ${plural(group.queries, 'lookup', 'lookups')}`, behavioural ? `kind kind-${group.kind}` : 'kind'));
    if (group.destinations.length) {
      const table = element('table', undefined, 'data-table');
      const head = element('tr');
      head.append(element('th', 'Destination'), element('th', 'Lookups', 'num'), element('th', 'Reported blocked', 'num'));
      table.append(head);
      for (const entry of group.destinations) {
        const row = element('tr');
        const name = element('td', undefined, 'destination');
        name.append(domainName(entry.domain));
        if (entry.provider) name.append(element('span', entry.provider, 'provider'));
        name.append(shareBar(entry.queries / most, behavioural ? `share seg-${group.kind}` : 'share'));
        row.append(name, element('td', formatCount(entry.queries), 'num'), element('td', formatCount(entry.blocked || 0), entry.blocked ? 'num' : 'num zero'));
        table.append(row);
      }
      section.append(table);
    }
    if (group.distinct > group.destinations.length) {
      section.append(element('p', `Showing ${formatCount(group.destinations.length)} of ${formatCount(group.distinct)} destinations`, 'hint'));
    }
  }
  return section;
}
// Device rows carry their own search text and signals so filtering never
// re-reads evidence. Candidates stay a separate mode of the same list.
let signalFilter = 'all';
const signalNames = {all:'All observed', review:'Needs your review', third:'Ad, measurement and telemetry lookups', silent:'No DNS via Pi-hole'};
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
  const narrowed = bySignal || presence === 'candidate' || byId('device-sort').value !== 'attention';
  byId('filters-toggle').textContent = narrowed ? 'Filter and order · in use' : 'Filter and order';
  byId('device-shown').textContent = cards.length
    ? `${formatCount(shown)} of ${formatCount(group)} ${presence === 'candidate' ? 'candidate addresses' : 'observed devices'}` : '';
  const empty = byId('device-filter-empty');
  empty.hidden = !cards.length || cards.some(card => !card.hidden);
  empty.textContent = query ? 'No addresses match this search.'
    : bySignal ? `No observed device shows “${signalNames[signalFilter]}”.`
    : presence === 'candidate' ? 'No candidate addresses remain in this scope.'
    : 'No devices observed yet. Candidate addresses are checked on each scan.';
}
byId('filters-toggle').addEventListener('click', () => {
  const open = byId('filters-toggle').getAttribute('aria-expanded') !== 'true';
  byId('filters-toggle').setAttribute('aria-expanded', String(open));
  byId('device-filters').classList.toggle('open', open);
});
for (const signal of Object.keys(signalNames)) byId(`signal-${signal}`).addEventListener('click', () => {setSignalFilter(signal); filterDevices(); recordLocation(true);});
byId('device-sort').addEventListener('change', () => {if (overview) {renderInventory(overview); filterDevices();} recordLocation(true);});
byId('device-search').addEventListener('input', filterDevices);
byId('device-presence').addEventListener('change', () => {filterDevices(); recordLocation(true);});
byId('discover-form').addEventListener('submit', async event => {
  event.preventDefault();
  setBusy(byId('discover'), true);
  byId('discovery-detail').textContent = 'Looking for responding devices…';
  try {
    const report = await api('/api/discovery', {method:'POST', body:JSON.stringify({network:byId('network').value.trim(), ports:portSelection()})});
    renderDevices(report);
    notice('Discovery finished. Choose the devices you want to monitor.');
  } catch (error) {byId('discovery-detail').textContent = error.message; notice(error.message, true);}
  finally {setBusy(byId('discover'), false);}
});
byId('monitor-form').addEventListener('submit', async event => {
  event.preventDefault(); settingsPending = true;
  setBusy(byId('save-monitoring'), true);
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
  finally {settingsPending = false; setBusy(byId('save-monitoring'), false);}
});

// A local launcher opens a fresh, process-scoped dashboard bearer. Fragments are
// not sent to the server. Remove it immediately; never persist either credential.
// The selected device stays in the fragment so reload and Back restore it.
const fragment = new URLSearchParams(location.hash.slice(1));
const handoff = fragment.get('token');
let pendingDevice = fragment.get('device');
let pendingTab = Object.hasOwn(views, fragment.get('tab') || '') ? fragment.get('tab') : pendingDevice ? 'devices' : null;
// The list's filter and order apply once the first overview arrives; values
// are checked against the allowlists then.
let pendingList = pendingTab === 'devices' ? fragment : null;
if (location.hash) {
  const listed = ['show', 'presence', 'order'].filter(key => /^[a-z]{1,16}$/.test(fragment.get(key) || '')).map(key => key + '=' + fragment.get(key));
  const kept = [pendingTab && 'tab=' + pendingTab, ...(pendingTab === 'devices' ? [pendingDevice && 'device=' + encodeURIComponent(pendingDevice), ...listed] : [])].filter(Boolean);
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
  if ((bearer || authenticated) && (!document.hidden || overview?.preferences.browser_notifications) && !settingsPending) refresh().catch(error => condition(error.message, true));
}, 5000);

// The full scan and quick checks are separate observations. Say which one
// answered, so a silent full scan never reads as contradicting a later answer.
function fullScanText(device) {
  if (!device.last_seen) return 'Awaiting first full scan';
  const responded = device.services.some(item => item.status === 'open' || item.status === 'closed');
  const answeredLater = !responded && device.last_response_at && Date.parse(device.last_response_at) > Date.parse(device.last_seen);
  return `${formatWhen(device.last_seen)} · ${responded ? 'answered on the checked ports' : answeredLater ? 'no answer then; a later check got one' : 'no answer on the checked ports'}`;
}
function renderDeviceDetection(detection) {
  const panel = element('div', undefined, 'detection');
  panel.append(element('h4', detection.confidence === 'unknown' ? 'Device type unknown'
    : 'Possible device type: ' + detection.device_type));
  if (detection.clues.length) {
    const clues = element('ul');
    clues.append(...detection.clues.map(clue => element('li', clue)));
    panel.append(clues);
  }
  // Only a name clue and a port clue together corroborate a type.
  if (detection.confidence === 'corroborated' && detection.clues.length > 1) {
    panel.append(element('p', 'The observed name and an open port point to the same type.', 'hint'));
  }
  return panel;
}
function renderExposures(detection) {
  const nodes = detection.exposures.map(exposure => element('p',
    'TCP ' + exposure.ports.join(', ') + ' · Possible ' + exposure.possible_service + '. ' + exposure.risk_check));
  nodes.push(element('p', 'Open ports suggest services. Verify the protocol, model and firmware before assessing vulnerabilities.', 'limit'));
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
  return behaviouralGroups(activity).map(group => `${shortKinds[group.kind]} ${formatCount(group.queries)}`).join(' · ');
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
function resetCount(resets) {return plural(resets, 'counter reset', 'counter resets');}
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
  const more = element('button', `${formatCount(hidden)} more ${noun} in the device list`, 'text-link');
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
    head.append(deviceLink(device), element('span', undefined, 'leader'), element('span', plural(behaviouralQueries(device.dns_activity), 'lookup', 'lookups'), 'figure'));
    const providers = behaviouralGroups(device.dns_activity).flatMap(group => group.destinations)
      .sort((a, b) => b.queries - a.queries).slice(0, 3).map(entry => entry.provider || entry.domain);
    const who = element('p', behaviouralBreakdown(device.dns_activity), 'leaf-note');
    if (providers.length) who.append(element('span', ` — ${providers.join(', ')}`, 'who'));
    row.append(head, segmentBar(device.dns_activity, most), who);
    third.append(row);
  }
  if (!talkers.length) third.append(element('p', profiled.length
    ? `No device with a DNS profile looked up a destination in these groups (${formatCount(profiled.length)} profiled).`
    : 'No per-device DNS profiles yet. They need Pi-hole and quick presence checks; see Settings.', 'empty'));
  leafMore(third, talkers.length - limit, 'devices', () => showInventory('third', 'third'));
  const unprofiled = value.devices.length - profiled.length;
  const partial = profiled.filter(device => device.dns_activity.partial_windows).length;
  byId('q-third-coverage').textContent = [
    unprofiled && profiled.length ? `${formatCount(unprofiled)} of ${plural(value.devices.length, 'observed device', 'observed devices')} ${unprofiled === 1 ? 'has' : 'have'} no DNS profile and cannot appear here.` : '',
    partial ? `${plural(partial, 'device', 'devices')} had partial coverage in some windows; open a device for detail.` : '',
  ].filter(Boolean).join(' ');

  const silent = byId('q-silent'); silent.replaceChildren();
  const quiet = value.devices.filter(device => device.dns_activity?.pihole_silent);
  for (const device of quiet.slice(0, limit)) {
    const row = element('div', undefined, 'leaf-row gap');
    const head = element('div', undefined, 'leaf-line');
    head.append(deviceLink(device), element('span', undefined, 'leader'), element('span', `quiet ${silentHours(device.dns_activity)} h`, 'figure'));
    row.append(head, element('p', `No DNS via Pi-hole in ${plural(device.dns_activity.windows, 'confirmed check', 'confirmed checks')}`, 'leaf-note'));
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
  if (!senders.length) volume.append(element('p', trafficGap(value), 'empty'));
  leafMore(volume, senders.length - limit, 'devices with counters', () => showInventory('all', 'from'));

  const types = new Map(); let unknown = 0, identified = 0;
  for (const device of value.devices) {
    if (knownIdentity(device).known) identified += 1;
    if (!device.detection || device.detection.confidence === 'unknown') unknown += 1;
    else types.set(device.detection.device_type, (types.get(device.detection.device_type) || 0) + 1);
  }
  const tally = [...types].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([name, count]) => `${name} ${formatCount(count)}`);
  if (unknown) tally.push(`type unknown ${unknown}`);
  byId('q-network').textContent = value.devices.length
    ? `${formatCount(identified)} with an identity observed by UniFi or Pi-hole, ${formatCount(value.devices.length - identified)} by address only. Possible types from names and open ports: ${tally.join(' · ')}.`
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
// Every block in the side pane is one of these: a titled section that the
// pane's section menu can jump to. `nav` is its short name in that menu.
function paneSection(title, nav) {
  const section = element('section', undefined, 'pane-section');
  const heading = element('h3', title);
  heading.tabIndex = -1;
  section.dataset.nav = nav;
  section.append(heading);
  return section;
}
function renderPaneNav() {
  byId('pane-nav').replaceChildren(...[...byId('device-view').querySelectorAll('.pane-section')].map(section => {
    const link = element('button', section.dataset.nav, 'text-link');
    link.type = 'button';
    link.addEventListener('click', () => {
      section.scrollIntoView({block:'start'});
      section.querySelector('h3').focus({preventScroll:true});
    });
    return link;
  }));
}
function actionButton(label, className, action) {
  const button = element('button', label, className);
  button.type = 'button';
  button.addEventListener('click', () => Promise.resolve().then(action).catch(error => notice(error.message, true)));
  return button;
}

// The side pane is the one place detail opens: a device, its profile editor,
// an issue or an agent draft. Nothing scrolls the page to a distant form. An
// editor or reader replaces the device view in place: it opens at the top of
// the pane, Back returns to the device where it was scrolled, and focus goes
// back to the control that opened it.
let selectedDevice = null;
const deviceParts = ['device-identity','device-assessment','device-facts','device-analysis','device-history'];
const editorParts = ['profile-form','profile-markdown','device-research','background-draft','issue-form','issue-markdown'];
let paneReturn = null;
function paneEditing() {return editorParts.some(id => byId(id).hidden === false);}
function syncPane() {
  const visible = id => byId(id).hidden === false;
  const editing = paneEditing();
  const device = selectedDevice && overview ? overview.devices.find(item => item.address === selectedDevice) : null;
  const body = byId('pane-body');
  const opened = editing && !paneReturn;
  if (opened) paneReturn = {scroll: body.scrollTop, opener: document.activeElement};
  byId('device-view').hidden = !device || editing;
  byId('pane-nav').hidden = !device || editing;
  byId('pane-back').hidden = !device || !editing;
  // Without a device, an editor names what it edits; editProfile has already
  // moved the selection to the profile's own device when it is monitored.
  byId('pane-title').textContent = device ? device.name
    : visible('issue-form') || visible('issue-markdown') ? 'Privacy issue'
    : visible('background-draft') ? 'Agent research draft'
    : profileEdit ? (profileEdit.name ? 'Profile: ' + profileEdit.name : 'New device profile') : 'Device profile';
  byId('side-pane').hidden = !device && !editing;
  if (opened) body.scrollTop = 0;
  if (!editing && paneReturn) {
    const back = paneReturn; paneReturn = null;
    if (device) body.scrollTop = back.scroll;
    if (back.opener?.isConnected) back.opener.focus({preventScroll:true});
    else if (device) byId('pane-title').focus({preventScroll:true});
  }
}
function closeEditors() {
  profileEdit = null; byId('profile-form').hidden = true; byId('profile-markdown').hidden = true; byId('profile-history').replaceChildren();
  issueEdit = null; byId('issue-form').hidden = true; byId('issue-markdown').hidden = true; byId('issue-history').replaceChildren();
  clearBackgroundDraft();
  clearDeviceResearch();
  syncPane();
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
  for (const id of deviceParts) byId(id).replaceChildren();
  closeEditors();
  markSelectedRow(); syncPane();
  if (record) recordLocation();
  if (address) {
    const card = [...byId('device-cards').querySelectorAll('.device-card')].find(item => item.dataset.address === address);
    card?.querySelector('.device-summary')?.focus({preventScroll:true});
  }
}
byId('pane-close').addEventListener('click', () => closePane());
byId('pane-back').addEventListener('click', closeEditors);
// Escape steps back once: out of an editor to its device, then out of the pane.
byId('side-pane').addEventListener('keydown', event => {
  if (event.key !== 'Escape') return;
  if (selectedDevice && paneEditing()) closeEditors(); else closePane();
});
globalThis.addEventListener?.('popstate', () => {
  const params = new URLSearchParams(location.hash.slice(1));
  const tab = params.get('tab'), address = params.get('device');
  restoring = true;
  try {
    const view = Object.hasOwn(views, tab || '') ? tab : address ? 'devices' : 'overview';
    if (view !== currentView) showView(view);
    applyListParams(params); filterDevices();
    if (address && overview?.devices.some(item => item.address === address)) openDevice(address, false);
    else if (selectedDevice) closePane(false);
  } finally {restoring = false;}
});

// What was observed, in the order the operator asks about it: what the device
// is, what it talks to, how much it sends, then its open ports. Each block
// keeps the source and the limits of its own numbers. The agent assessment
// sits between identity and the observations, so its state and decisions are
// near the top of a long pane.
let paneSignature = null;
function renderDeviceView(device) {
  paneSignature = JSON.stringify(device);
  const {current, known} = knownIdentity(device);
  const identity = paneSection('What it is', 'What it is');
  const facts = element('dl', undefined, 'facts');
  fact(facts, 'Address', device.address, 'data');
  if (known) {
    if (known.mac) fact(facts, 'MAC address', known.mac, 'data');
    if (known.hostname) fact(facts, 'Hostname', known.hostname, 'data');
    if (known.vendor) fact(facts, 'Vendor', known.vendor);
    const association = [known.hostname, known.mac, known.vendor].filter(Boolean).join(' · ') || 'Address observed';
    const observedAt = formatWhen(known.observed_at);
    const source = known.source === 'unifi' ? 'UniFi' : 'Pi-hole';
    fact(facts, 'Identity source', (current ? '' : 'Last known ') + source + ' association · ' + association + ' · ' + observedAt);
  } else {
    fact(facts, 'Identity source', 'Address only; no observed identity');
  }
  const presence = device.presence;
  if (presence?.checked > 0) {
    const percent = Math.round(100 * presence.seen / presence.checked);
    const subject = presence.address_only ? 'Address seen' : 'Device seen';
    const last = presence.last_seen ? ' · Last seen ' + formatWhen(presence.last_seen) : '';
    fact(facts, 'Presence', `${subject} in ${formatCount(presence.seen)} of ${plural(presence.checked, 'quick check', 'quick checks')} (${percent}%) over 24 hours${last}`);
  } else if (presence?.last_seen) {
    fact(facts, 'Presence', 'Last quick sighting ' + formatWhen(presence.last_seen));
  }
  identity.append(facts);
  if (device.detection) identity.append(renderDeviceDetection(device.detection));

  const talks = paneSection('What it talks to', 'Talks to');
  if (device.dns_activity) talks.append(renderDnsActivity(device.dns_activity));
  else talks.append(element('p', 'No Pi-hole DNS profile for this device. Its destinations are unknown, not absent.', 'hint unmeasured'));
  // A disclosure with nothing in it is not shown.
  if (device.dns.length) {
    const destinations = element('details');
    destinations.append(element('summary', `Details · ${plural(device.dns.length, 'DNS destination', 'DNS destinations')} sampled in the last full scan`));
    for (const query of device.dns) {
      const line = element('p', undefined, 'hint');
      line.append(domainName(query.domain), element('span', plural(query.queries, 'sampled lookup', 'sampled lookups')));
      destinations.append(line);
    }
    talks.append(destinations);
  }

  const sends = paneSection('How much it sends', 'Sends');
  if (device.traffic) sends.append(renderTraffic(device.traffic));
  else sends.append(element('p', (overview ? trafficGap(overview) : 'No UniFi byte counters for this device.') + ' Volume is unknown, not zero.', 'hint unmeasured'));

  const ports = paneSection('Open ports and exposures', 'Ports');
  const sweep = element('dl', undefined, 'facts');
  fact(sweep, 'Checked ports', device.services.length ? device.services.map(item => `${item.port}: ${item.status}`).join(' · ') : 'No checked ports recorded.', device.services.length ? 'data' : undefined);
  fact(sweep, 'Last full scan', fullScanText(device));
  if (device.last_response_at) {
    fact(sweep, 'TCP answers', `First ${formatWhen(device.first_response_at)} · Latest ${formatWhen(device.last_response_at)}`);
  }
  ports.append(sweep);
  if (device.detection) ports.append(...renderExposures(device.detection));
  byId('device-identity').replaceChildren(identity);
  byId('device-facts').replaceChildren(talks, sends, ports);

  const history = paneSection('Evidence history', 'Evidence');
  history.append(element('p', `${plural(device.history.length, 'collection', 'collections')} at this address. Reading one opens it in the Evidence view.`, 'hint'));
  const entries = element('div', undefined, 'entries');
  for (const id of [...device.history].reverse()) {
    entries.append(actionButton(`Read collection ${id.slice(0,8)}`, 'entry', () => showEvidence(id)));
  }
  history.append(entries);
  byId('device-history').replaceChildren(history);
  renderPaneNav();
}

const applicabilityLabels = [['potentially_affected','Potentially affected'],['confirmed_affected','Confirmed affected'],['not_affected','Not affected'],['mitigated','Mitigated'],['unresolved','Unresolved']];
const mitigationLabels = [['proposed','Proposed'],['accepted','Accepted'],['applied','Applied by operator'],['verified','Verified by operator'],['reverted','Reverted by operator']];
// Rendered documents carry sections written for the record rather than the
// reader: identifiers, raw state words and JSON. Each is folded into the same
// closed "Details" disclosure after rendering, so the prose is not buried and
// nothing is removed. A record's JSON block folds alone; the other sections
// fold up to the next heading of the same or a higher level.
const foldedSections = new Set(['Record metadata', 'Evidence', 'Privacy issues and mitigation decisions']);
// In the device pane the report's supplemental section repeats "What we know",
// which shows the same retained facts live; it folds there with a pointer.
const paneFolded = new Map([['Supplemental information', 'Details · supplemental information, also under What we know']]);
function foldDetails(article, extra = new Map()) {
  if (!article?.children) return article;
  const headings = [...article.children].filter(node => /^H[1-6]$/.test(node.tagName) && (foldedSections.has(node.textContent.trim()) || extra.has(node.textContent.trim())));
  for (const heading of headings) {
    const title = heading.textContent.trim();
    const members = [];
    let next = heading.nextElementSibling;
    if (title === 'Record metadata') {
      while (next && next.tagName !== 'PRE') next = next.nextElementSibling;
      if (next) members.push(next);
    } else {
      while (next && !(/^H[1-6]$/.test(next.tagName) && next.tagName <= heading.tagName)) {
        members.push(next); next = next.nextElementSibling;
      }
    }
    if (!members.length) continue;
    const fold = element('details', undefined, 'record-details');
    fold.append(element('summary', extra.get(title) || `Details · ${title[0].toLowerCase()}${title.slice(1)}`));
    heading.replaceWith(fold);
    fold.append(...members);
  }
  return article;
}
function renderProfileDocument(text) {
  return foldDetails(PrivacyMarkdown.render(text));
}
// The operator's profile, linked issues and any agent draft are read once when
// a device is opened and again after a save; the five-second refresh leaves
// them. Notes are the operator's own; nothing here is presented as analysis
// unless an agent produced it.
async function loadDeviceAnalysis(device) {
  const panel = byId('device-analysis');
  const stale = () => selectedDevice !== device.address;
  const notes = paneSection('Your notes and expectations', 'Notes');
  const known = paneSection('What we know about this device', 'Known');
  const assessment = paneSection('Agent assessment', 'Assessment');
  const product = paneSection('Product and advisories', 'Advisories');
  const waitingAssessment = paneSection('Agent assessment', 'Assessment');
  waitingAssessment.append(element('p', 'Reading the assessment…', 'hint'));
  const waiting = paneSection('Your notes and expectations', 'Notes');
  waiting.append(element('p', 'Reading the saved profile…', 'hint'));
  byId('device-assessment').replaceChildren(waitingAssessment);
  panel.replaceChildren(waiting);
  renderPaneNav();
  const mac = device.identity?.mac || null;
  try {
    if (byId('device-profiles').hidden) {
      notes.append(element('p', 'Device profiles are unavailable on this collector.', 'hint unmeasured'));
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
        notes.append(reader, actionButton('Edit profile', 'secondary', () => editProfile(view, device.address, null)));
        const links = view.profile.fields.issues || [];
        notes.append(element('h4', 'Linked privacy issues'));
        if (links.length) {
          const titles = issuesAvailable
            ? new Map((await api('/api/issues')).issues.map(item => [item.issue.issue_id, item.issue.fields.title])) : new Map();
          for (const link of links) {
            const issue = element('article', undefined, 'card');
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
          actionButton('Create profile for this device', 'secondary', () => openDeviceProfile(device.address, mac)));
      }
    }
  } catch (error) {
    notes.append(element('p', error.message, 'notice error'));
  }
  try {
    if (mac && device.identity?.device_id) {
      const body = await api(`/api/devices/${encodeURIComponent(device.identity.device_id)}/supplemental`);
      if (!stale()) deviceSupplemental = {device_id:device.identity.device_id, section:body.supplemental};
      known.append(...renderSupplemental(body));
    } else {
      known.append(element('p', 'Not available: this device is known by its address only. Device descriptions need a confirmed MAC address.', 'hint unmeasured'));
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
  if (stale()) return;
  product.append(element('p', productHint(device), 'hint'),
    actionButton('Look up product and advisories', 'secondary', () => openDeviceResearch(device)));
  // The deep scan belongs with the assessment: its result is what asks for one.
  const sections = [assessment];
  if (overview?.deep_scans) {
    const deep = paneSection('Deep port scan', 'Deep scan');
    const deepBlock = element('div', undefined, 'deep-scan-block');
    deep.append(deepBlock);
    renderDeepScan(device, deepBlock);
    sections.push(deep);
  }
  byId('device-assessment').replaceChildren(...sections);
  panel.replaceChildren(known, notes, product);
  renderPaneNav();
}

// What the device said about itself, public advisories for its model and
// vendor features worth checking, from the collector's retained facts. Every
// value is shown with textContent: device-reported strings are untrusted.
const describedBy = {upnp:'UPnP', mdns:'mDNS'};
const modelFrom = {profile:'your profile', unifi:'the UniFi controller', device:'the device’s own description'};
function renderSupplemental(body) {
  const section = body.supplemental;
  const nodes = [];
  if (section.described_at === null) {
    nodes.push(element('p', body.enabled
      ? 'Not described yet. The collector asks each monitored device to describe itself at most once a day, after quick checks.'
      : 'Device descriptions are off — turn them on in Settings.', 'hint'));
  } else if (!section.descriptions.length) {
    nodes.push(element('p', `The device did not describe itself when last asked (${formatDay(section.described_at)}). Many devices do not; that is not a finding.`, 'hint'));
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
      fact(list, 'Source', `${describedBy[item.source] || item.source} on the local network · ${formatDay(item.observed_at)}`);
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
    nodes.push(element('p', `Not looked up: no public model name was available (${formatDay(lookup.checked_at)}).`, 'hint'));
  } else if (lookup.status === 'not_configured') {
    nodes.push(element('p', 'Not looked up: no advisory source is configured on this collector.', 'hint'));
  } else if (lookup.status === 'unavailable') {
    nodes.push(element('p', `The NVD lookup did not complete (${formatDay(lookup.checked_at)}${from}). ${lookup.unavailable || ''} Advisories are unknown, not none.`, 'hint unmeasured'));
  } else {
    nodes.push(element('p', `NVD · checked ${formatDay(lookup.checked_at)}${from} · ${lookup.total ? formatCount(lookup.total) + ' found' : 'none found'}`, 'hint source'));
    for (const item of lookup.advisories) {
      const entry = element('article', undefined, 'card');
      const facts = [item.severity ? item.severity[0] + item.severity.slice(1).toLowerCase() : 'Severity not rated', 'published ' + formatDay(item.published_on)];
      if (item.known_exploited) facts.push('known exploited');
      entry.append(element('p', item.cve, 'data'), element('p', facts.join(' · '), 'status-line'), element('p', item.description, 'hint'));
      nodes.push(entry);
    }
    const unlisted = (lookup.total || 0) - lookup.advisories.length;
    if (unlisted > 0) nodes.push(element('p', `${formatCount(unlisted)} more not listed here.`, 'hint'));
    if (lookup.total) nodes.push(element('p', 'An advisory is about the model, not proof that this device is affected. Check whether the firmware it names matches.', 'limit'));
  }
  if (section.vendor_notes.length) {
    nodes.push(element('h4', 'Worth checking'));
    for (const note of section.vendor_notes) {
      const entry = element('article', undefined, 'card');
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
// running, its report is still being processed, or an Accept is unconfirmed,
// the five-second refresh rereads only the state. Nothing here resends a request whose outcome is unknown: Check
// status only reads.
let deviceAssessment = null;
let assessmentBusy = null;
function assessmentPath(deviceId, suffix = '') {
  return `/api/devices/${encodeURIComponent(deviceId)}/assessment${suffix}`;
}
function renderAssessment(view, report) {
  const nodes = [];
  const button = (label, action, className) => {
    const control = actionButton(label, className, () => assessmentAction(action));
    control.disabled = Boolean(assessmentBusy);
    if (assessmentBusy === action) markBusy(control, true);
    return control;
  };
  // The second agent's check, in words that cannot be mistaken for the
  // operator's own Accept below it.
  const checked = view.review === 'needs_corrections'
    ? 'Checked by a second agent: passed after one round of corrections'
    : 'Checked by a second agent: passed';
  const run = view.run_id ? element('details') : null;
  if (run) run.append(element('summary', 'Details · run identifier'), element('p', view.run_id, 'hint data'));
  if (view.state === 'idle') {
    nodes.push(element('p', 'Not assessed', 'status-line'),
      element('p', 'An agent reads this device’s observations (what it is, who it talks to and how much it sends) and explains them in plain language. A second agent checks each concern against the same data. The report stays a draft until you accept it.', 'hint'));
  } else if (view.state === 'running') {
    nodes.push(element('p', view.pending === 'admission' ? 'Starting' : 'Assessment running', 'status-line'),
      element('p', view.pending === 'admission'
        ? 'Plowshare has not confirmed the start. It is not sent again; its admission receipt settles it.'
        : `Requested ${formatWhen(view.requested_at)}. This view updates while the run continues.`, 'hint'));
    if (run) nodes.push(run);
  } else if (view.state === 'review') {
    nodes.push(element('p', view.pending === 'accept' ? 'Accept sent, not yet confirmed' : 'Awaiting your review', 'status-line'),
      element('p', view.pending === 'accept'
        ? 'Plowshare has not confirmed the report is final. Accept is not sent again; Check status reads it.'
        : `Assessed ${formatWhen(view.requested_at)}. ${checked}. Accept makes this report final in Plowshare. Dismiss hides it here and leaves it in Plowshare as a draft.`, 'hint'));
    // Plowshare refuses to finalise a report it is still processing.
    if (view.processing && !view.pending && !view.detail) nodes.push(element('p', 'Report is being processed… Accept becomes available when Plowshare finishes.', 'hint'));
  } else if (view.state === 'accepted') {
    nodes.push(element('p', 'Accepted ' + formatWhen(view.decided_at), 'status-line'),
      element('p', `This report is final in Plowshare. Assessed ${formatWhen(view.requested_at)}. ${checked}.`, 'hint'));
  } else if (view.state === 'dismissed') {
    nodes.push(element('p', 'Dismissed ' + formatWhen(view.decided_at), 'status-line'),
      element('p', 'You dismissed this draft. It stays in Plowshare as history and is not shown here.', 'hint'));
  } else if (view.state === 'failed') {
    nodes.push(element('p', 'Last assessment failed', 'status-line'));
    if (run) nodes.push(run);
  }
  // A refusal while the report is processing is a wait, not an error.
  if (view.detail) nodes.push(element('p', view.detail, view.state === 'failed' || (view.state === 'review' && !view.pending && !view.processing) ? 'notice error' : 'hint'));
  // Decisions sit with the state line, above a report that can be long.
  const actions = element('div', undefined, 'actions');
  if (view.can_accept) actions.append(button('Accept and make final', 'accept'));
  else if (view.state === 'review' && view.processing && !view.pending) {
    const waiting = button('Accept and make final', 'accept');
    waiting.disabled = true;
    actions.append(waiting);
  }
  if (view.can_dismiss) actions.append(button('Dismiss without finalising', 'dismiss', 'danger'));
  if (view.pending || (view.state === 'review' && view.detail)) actions.append(button('Check status', 'check', 'secondary'));
  if (view.can_assess) {
    const [label, className] = view.state === 'idle' ? ['Assess this device']
      : view.state === 'failed' ? ['Retry assessment'] : ['Assess again', 'secondary'];
    actions.append(confirmedAction(label, className, 'Confirm assessment',
      'This starts one agent assessment of this device. It uses model calls and counts toward today’s assessment limit.',
      () => assessmentAction('assess'), Boolean(assessmentBusy)));
  }
  if (actions.children.length) nodes.push(actions);
  if (!view.can_assess && view.assess_note) nodes.push(element('p', view.assess_note, 'hint'));
  // The report can be long. It is open while it awaits a decision and folded
  // once it is final, so the sections below stay within reach.
  if (report && (view.state === 'review' || view.state === 'accepted')) {
    const reader = element('div', undefined, 'document-reader');
    reader.append(foldDetails(PrivacyMarkdown.render(report), paneFolded));
    const fold = element('details');
    fold.open = view.state === 'review';
    fold.append(element('summary', 'Details · the assessment report'), reader);
    nodes.push(fold);
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
    block.append(element('p', 'Device assessments are not enabled on this collector.', 'hint unmeasured'));
    return;
  }
  const deviceId = device.identity?.mac ? device.identity.device_id : null;
  if (!deviceId) {
    block.append(element('p', 'Not assessed. An assessment needs a confirmed MAC address; this device is known by its address only.', 'hint unmeasured'));
    return;
  }
  deviceAssessment = {address: device.address, deviceId, block, view: null, report: null};
  await reloadAssessment();
}
async function assessmentAction(action) {
  const current = deviceAssessment;
  if (!current || assessmentBusy) return;
  assessmentBusy = action; showAssessment(); showDeepScan();
  try {
    await api(assessmentPath(current.deviceId, '/' + action), {method:'POST', body:'{}'});
  } catch (error) {
    notice(error.message, true);
  } finally {
    assessmentBusy = null;
  }
  await reloadAssessment();
  showDeepScan();
  // The device list and Overview counts read the same ledger; show the change
  // there now rather than at the next background refresh.
  refresh().catch(error => condition(error.message, true));
}
async function pollAssessment() {
  const view = deviceAssessment?.view;
  if (!view || deviceAssessment.address !== selectedDevice || assessmentBusy) return;
  if (view.state === 'running' || view.pending || view.processing) await reloadAssessment();
}
async function loadAssessmentSettings() {
  const settings = await api('/api/device-assessments');
  byId('assessment-limit').value = String(settings.background_limit);
  byId('assessment-settings-detail').textContent = settings.detail || (settings.background_limit ? `Up to ${plural(settings.background_limit, 'background assessment', 'background assessments')} a day.` : 'Background assessment is off.');
  // The limit just read is newer than the overview's; Settings shows it too.
  if (overview?.assessments) {overview.assessments.usage.background_limit = settings.background_limit; renderBackgroundUsage(overview);}
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

// ---- Deep port scans ----
// A scan probes all 65,535 TCP ports of one device the collector has confirmed
// by MAC. The overview carries every scanned device's view; the open device's
// view is re-read through the existing five-second refresh only while it is
// queued or running, and only the deep-scan block is rebuilt, so the sections
// the operator has open and the focus stay where they are. This file is large
// (about 2,700 lines); this block is kept whole so it can move to its own
// script when the dashboard is next split.
let deepScanPane = null;
let deepScanBusy = null;
const deepScanStateText = {
  cancelled: 'Last deep scan was cancelled',
  interrupted: 'Last deep scan was interrupted',
  unreliable: 'Last deep scan could not be trusted',
  address_changed: 'Not scanned: the device’s address changed',
  failed: 'Last deep scan failed',
};
const deepScanUnavailable = 'Deep port scans need UniFi or Pi-hole to confirm each device’s address.';
function deepScanViewIn(device, value) {
  const id = device.identity?.mac ? device.identity.device_id : null;
  return id ? (value?.deep_scans?.views || []).find(item => item.device_id === id) || null : null;
}
function deepScanOf(device) {return deepScanViewIn(device, overview);}
// Open ports from the newest complete scan that the usual checks do not cover.
// A later cancelled or failed attempt does not hide them.
function newOpenPorts(view, configuredPorts) {
  if (!view?.latest_complete) return [];
  const usual = new Set(configuredPorts || []);
  return view.latest_complete.open_ports.filter(port => !usual.has(port));
}
// True while a scan found ports the usual checks miss and no assessment has
// been requested since it finished.
function deepScanNeedsReview(device, value) {
  const view = deepScanViewIn(device, value);
  if (!newOpenPorts(view, value?.deep_scans?.configured_ports).length) return false;
  const id = device.identity.device_id;
  const requested = (value.assessments?.summaries || []).find(item => item.device_id === id)?.requested_at
    ?? assessmentOf(device)?.requested_at ?? null;
  return requested === null || Date.parse(requested) < Date.parse(view.latest_complete.finished_at);
}
function timeLeftText(stamp) {
  if (!stamp) return '';
  const minutes = Math.ceil((Date.parse(stamp) - Date.now()) / 60000);
  return minutes <= 1 ? 'less than a minute left' : `about ${plural(minutes, 'minute', 'minutes')} left`;
}
function deepScanPath(deviceId, action) {
  return `/api/devices/${encodeURIComponent(deviceId)}/deep-scan${action}`;
}
function deepScanNodes(device, view, scans) {
  const nodes = [];
  if (!scans) return [element('p', 'Deep port scans are not enabled on this collector.', 'hint unmeasured')];
  if (!device.identity?.mac) return [element('p', 'Not scanned. A deep scan needs a confirmed MAC address; this device is known by its address only.', 'hint unmeasured')];
  const latest = view.latest;
  const actions = element('div', undefined, 'actions');
  const act = (label, className, action) => {
    const control = actionButton(label, className, () => deepScanAction(action));
    control.disabled = Boolean(deepScanBusy);
    return control;
  };
  if (view.state === 'running') {
    nodes.push(element('p', [`${formatCount(view.checked)} of ${formatCount(view.total)} ports checked`, `${plural(view.open_so_far.length, 'open port', 'open ports')} so far`, timeLeftText(view.estimated_finish)].filter(Boolean).join(' · '), 'status-line'));
    actions.append(act('Cancel', 'quiet', 'cancel'));
  } else if (view.state === 'queued') {
    nodes.push(element('p', 'Waiting for its turn', 'status-line'));
    actions.append(act('Cancel', 'quiet', 'cancel'));
  } else {
    const start = act('Deep-scan this device', 'secondary', 'request');
    start.disabled = start.disabled || !view.available;
    actions.append(start);
  }
  if (view.state === 'idle' && !latest && !view.latest_complete) {
    nodes.push(element('p', 'Not deep-scanned. This checks every TCP port of the device, not only the usual ones, and takes several minutes.', 'hint'));
  }
  // The last attempt's state and detail, when it did not complete.
  if (latest && latest.state !== 'complete') {
    nodes.push(element('p', `${deepScanStateText[latest.state] || 'Last deep scan did not complete'} · ${formatWhen(latest.finished_at)}`, 'status-line'));
    if (latest.detail) nodes.push(element('p', latest.detail, latest.state === 'failed' ? 'notice error' : 'hint'));
  }
  // Ports come only from the newest complete scan.
  const complete = view.latest_complete;
  if (complete) {
    nodes.push(element('p', `${latest === complete ? 'Last' : 'Last complete'} deep scan finished ${formatWhen(complete.finished_at)}`, 'status-line'));
    const usual = new Set(scans.configured_ports);
    if (complete.open_ports.length) {
      const list = element('ul');
      for (const port of complete.open_ports) {
        const exposure = (device.detection?.exposures || []).find(item => item.ports.includes(port));
        const note = exposure ? ` · Possible ${exposure.possible_service}. ${exposure.risk_check}` : '';
        list.append(element('li', `TCP ${port}${note}${usual.has(port) ? '' : ' · outside the usual checks'}`));
      }
      nodes.push(list);
    } else {
      nodes.push(element('p', 'No open ports.', 'hint'));
    }
    nodes.push(element('p', `${plural(complete.unanswered, 'port', 'ports')} did not answer`, 'hint'));
  }
  // The collector's reason scanning cannot start or is waiting is plain text.
  if (view.detail) nodes.push(element('p', view.detail, 'hint'));
  if (deepScanNeedsReview(device, overview) && assessmentsAvailable) {
    nodes.push(element('p', 'This scan found open ports that the agent assessment has not seen.', 'hint'));
    actions.append(confirmedAction('Assess again', 'secondary', 'Confirm assessment',
      'This starts one agent assessment of this device. It uses model calls and counts toward today’s assessment limit.',
      () => assessmentAction('assess'), Boolean(assessmentBusy)));
  }
  nodes.push(actions);
  return nodes;
}
// What the block shows beyond the scan view: whether Assess again is offered
// and whether an assessment is in flight. A change in either rebuilds it.
function deepScanSignature(pane, view) {
  return JSON.stringify([view, deepScanNeedsReview(pane.device, overview), Boolean(assessmentBusy)]);
}
function showDeepScan() {
  const pane = deepScanPane;
  if (!pane || pane.address !== selectedDevice) return;
  pane.signature = deepScanSignature(pane, pane.view);
  pane.block.replaceChildren(...deepScanNodes(pane.device, pane.view || deepScanIdle(pane.deviceId), overview?.deep_scans));
}
function deepScanIdle(deviceId) {
  return {device_id:deviceId, available:Boolean(overview?.deep_scans?.available), state:'idle', trigger:null, checked:0, total:65535,
    open_so_far:[], estimated_finish:null, latest:null, latest_complete:null, detail:overview?.deep_scans?.detail ?? null};
}
function renderDeepScan(device, block) {
  const deviceId = device.identity?.mac ? device.identity.device_id : null;
  deepScanPane = deviceId ? {address:device.address, deviceId, device, block, view:deepScanOf(device)} : null;
  if (deepScanPane) {showDeepScan(); return;}
  block.replaceChildren(...deepScanNodes(device, null, overview?.deep_scans));
}
async function deepScanAction(action) {
  const pane = deepScanPane;
  if (!pane || deepScanBusy) return;
  deepScanBusy = action; showDeepScan();
  try {
    const next = await api(deepScanPath(pane.deviceId, '/' + action), {method:'POST', body:'{}'});
    if (deepScanPane === pane) pane.view = next;
  } catch (error) {
    notice(error.message, true);
  } finally {
    deepScanBusy = null;
  }
  // Disconnecting or opening another device while this was in flight left it
  // with nothing to update or refresh.
  if (deepScanPane !== pane) return;
  showDeepScan();
  // The attention band and device list read the same views.
  refresh().catch(error => condition(error.message, true));
}
// Runs after each refresh: re-read the open device's view while a scan is
// queued or running, otherwise take the overview's, and rebuild only this
// block when it changed.
async function pollDeepScan() {
  const pane = deepScanPane;
  if (!pane || pane.address !== selectedDevice || deepScanBusy || !overview?.deep_scans) return;
  const active = item => item?.state === 'running' || item?.state === 'queued';
  const fromOverview = deepScanOf(pane.device);
  const next = active(pane.view) || active(fromOverview)
    ? await api(deepScanPath(pane.deviceId, ''))
    : fromOverview;
  if (deepScanPane !== pane || deepScanSignature(pane, next) === pane.signature) return;
  pane.view = next; showDeepScan();
}
function renderDeepScanSettings(value) {
  const field = byId('deep-scan-limit');
  field.value = String(value.automatic_limit);
  field.disabled = !value.available;
  byId('deep-scan-save').disabled = !value.available;
  byId('deep-scan-detail').textContent = !value.available ? deepScanUnavailable
    : value.detail || (value.automatic_limit ? `Up to ${plural(value.automatic_limit, 'automatic deep scan', 'automatic deep scans')} a day.` : 'Automatic deep scans are off.');
}
byId('deep-scan-settings').addEventListener('submit', async event => {
  event.preventDefault();
  try {
    const limit = Number(byId('deep-scan-limit').value);
    if (!Number.isInteger(limit)) throw new Error('Choose a whole number of deep scans a day.');
    const saved = await api('/api/deep-scans', {method:'POST', body:JSON.stringify({automatic_limit:limit})});
    renderDeepScanSettings(saved);
    if (overview?.deep_scans) {overview.deep_scans.usage = {automatic_limit:saved.automatic_limit, used_today:saved.used_today}; renderBackgroundUsage(overview);}
  } catch (error) {
    byId('deep-scan-detail').textContent = error.message;
  }
});
// ---- end Deep port scans ----

const inventoryOrders = {
  attention: (a, b) => attentionRank(a) - attentionRank(b),
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
    for (const [title, className] of [['Device', 'c-name'], ['Address', 'c-address'], ['Signals', 'c-signals'], ['Ad, measurement and telemetry lookups', 'c-third'], ['To device', 'c-to'], ['From device', 'c-from']]) head.append(element('span', title, className));
    cards.append(head);
  }
  const order = inventoryOrders[byId('device-sort').value];
  const devices = order ? [...value.devices].sort(order) : value.devices;
  const most = Math.max(1, ...value.devices.map(device => behaviouralQueries(device.dns_activity)));
  let thirdCount = 0, silentCount = 0, reviewCount = 0;
  for (const device of devices) {
    const card = element('article', undefined, 'device-card');
    const {known} = knownIdentity(device);
    const tracking = behaviouralQueries(device.dns_activity);
    const flags = [];
    if (device.dns_activity?.pihole_silent) flags.push('No DNS via Pi-hole');
    if (tracking) flags.push(`${tracking} ad, measurement and telemetry lookups`);
    const signals = [];
    if (device.dns_activity?.pihole_silent) {signals.push('silent'); silentCount += 1;}
    if (tracking) {signals.push('third'); thirdCount += 1;}
    const assessment = assessmentOf(device);
    if (needsReview(assessment)) {signals.push('review'); reviewCount += 1;}
    card.dataset.presence = 'observed';
    card.dataset.address = device.address;
    card.dataset.signals = signals.join(' ');
    const assessmentLabels = assessmentTags(assessment);
    card.dataset.search = [device.name, device.address, known?.mac, known?.hostname, known?.vendor, device.detection?.device_type, ...flags, ...assessmentLabels.map(tag => tag.textContent)].filter(Boolean).join(' ').toLowerCase();

    const summary = element('button', undefined, 'device-summary');
    summary.type = 'button';
    summary.setAttribute('aria-controls', 'side-pane');
    const name = element('span', undefined, 'cell c-name');
    name.append(element('span', device.name, 'device-name'));
    const about = [device.detection && (device.detection.confidence === 'unknown' ? 'Type unknown' : 'Possible type: ' + device.detection.device_type), !known && 'Address only'].filter(Boolean);
    if (about.length) name.append(element('span', about.join(' · '), 'device-type'));
    const address = cell('Address', 'c-address'); address.append(element('span', device.address, 'data'));
    const signalCell = cell('Signals', 'c-signals');
    signalCell.append(...assessmentLabels);
    if (device.dns_activity?.pihole_silent) signalCell.append(element('span', 'No DNS via Pi-hole', 'tag tag-silent'));
    if (signalCell.children.length === 1) signalCell.append(element('span', 'None', 'nil'));
    const third = cell('Ad, measurement and telemetry lookups', 'c-third');
    if (!device.dns_activity) third.append(element('span', 'No DNS profile', 'nil'));
    else {
      if (tracking) third.append(segmentBar(device.dns_activity, most));
      third.append(element('span', formatCount(tracking), tracking ? 'data' : 'data zero'), element('span', tracking === 1 ? 'lookup' : 'lookups', 'unit'));
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
  byId('signal-all').textContent = `${signalNames.all} · ${formatCount(value.devices.length)}`;
  byId('signal-review').textContent = `${signalNames.review} · ${formatCount(reviewCount)}`;
  byId('signal-review').hidden = !assessmentsAvailable;
  byId('signal-third').textContent = `${signalNames.third} · ${formatCount(thirdCount)}`;
  byId('signal-silent').textContent = `${signalNames.silent} · ${formatCount(silentCount)}`;
  for (const candidate of value.candidates) {
    const card = element('article', undefined, 'device-card candidate');
    card.dataset.presence = 'candidate';
    card.dataset.search = [candidate.address, candidate.label].filter(Boolean).join(' ').toLowerCase();
    card.append(element('h3', candidate.address, 'data'), element('p', candidate.label || 'Candidate address'));
    card.append(element('p', candidate.last_checked_at
      ? 'Last checked ' + formatWhen(candidate.last_checked_at)
      : 'Awaiting first scan', 'hint'));
    cards.append(card);
  }
  // Keep an open device view on fresh observations; drop it if the device left.
  // A refresh rebuilds the pane only when this device's own record changed, and
  // then keeps what the operator had open, scrolled to and focused.
  if (selectedDevice) {
    const device = value.devices.find(item => item.address === selectedDevice);
    if (!device) closePane();
    else {
      if (JSON.stringify(device) !== paneSignature) keepingPaneState(() => renderDeviceView(device));
      markSelectedRow(); syncPane();
    }
  }
}
// The observed parts of the pane, rebuilt by renderDeviceView. The analysis and
// assessment parts are read once per device and are not touched here.
const observedParts = ['device-identity', 'device-facts', 'device-history'];
function keepingPaneState(render) {
  const parts = observedParts.map(byId);
  const label = node => node?.querySelector?.('summary')?.textContent ?? node?.textContent ?? '';
  const opened = new Set(parts.flatMap(part => [...(part.querySelectorAll?.('details[open]') || [])].map(label)));
  const active = document.activeElement;
  const focused = parts.some(part => part.contains?.(active)) ? {tag:active.tagName, text:active.textContent} : null;
  const pane = byId('pane-body'), scroll = pane.scrollTop;
  render();
  for (const part of parts) {
    for (const details of part.querySelectorAll?.('details') || []) if (opened.has(label(details))) details.open = true;
  }
  if (focused) {
    const match = parts.flatMap(part => [...(part.querySelectorAll?.(focused.tag) || [])]).find(node => node.textContent === focused.text);
    match?.focus?.({preventScroll:true});
  }
  pane.scrollTop = scroll;
}

// The schedule presets the collector offers, by the cron each deploys
// (schedule_editor.PRESETS). Any other live cron is shown as itself.
const schedulePresets = [['15m', '0 */15 * * * *', 'Every 15 minutes'], ['hourly', '0 0 * * * *', 'Hourly'], ['daily', '0 0 9 * * *', 'Daily at 09:00']];
function scheduleLabel(cron) {
  const preset = schedulePresets.find(([, value]) => value === cron);
  return preset ? preset[2] : 'Custom: ' + cron;
}
// The frequency choice starts at the live schedule, and follows it only when
// the live schedule changes, so a refresh does not undo an unsaved choice. A
// cron that is not a preset is kept by the "current" choice, so a review
// without changes cannot replace it with a preset.
let liveCron = null;
function renderSchedule(schedule) {
  const facts = byId('schedule-facts'); facts.replaceChildren();
  if (!schedule) {fact(facts, 'Frequency', 'Unavailable'); return;}
  fact(facts, 'Frequency', scheduleLabel(schedule.cron));
  fact(facts, 'Time zone', schedule.zone);
  if (schedule.cron === liveCron) return;
  liveCron = schedule.cron;
  const preset = schedulePresets.find(([, value]) => value === schedule.cron);
  const current = byId('frequency-current');
  current.hidden = current.disabled = Boolean(preset);
  current.textContent = preset ? 'Custom (current)' : 'Custom (current): ' + schedule.cron;
  byId('frequency').value = preset ? preset[0] : 'current';
}

// Assessment state as the device list shows it. A breach count is shown only
// for a report awaiting review or accepted; the collector counts concerns the
// second agent did not refute.
function assessmentOf(device) {
  return device.identity?.mac ? assessmentIndex.get(device.identity.device_id) || null : null;
}
function needsReview(summary) {return Boolean(summary) && (summary.state === 'review' || summary.state === 'failed');}
function attentionRank(device) {
  const summary = assessmentOf(device);
  if (!summary) return 5;
  if (summary.state === 'review') return summary.potential_breaches ? 0 : 1;
  if (summary.state === 'failed') return 2;
  if (summary.potential_breaches) return 3;
  return summary.state === 'running' ? 4 : 5;
}
function assessmentTags(summary) {
  if (!summary) return [];
  const tags = [];
  if (summary.potential_breaches) tags.push(element('span', plural(summary.potential_breaches, 'potential breach', 'potential breaches'), 'tag tag-breach'));
  if (summary.state === 'review') tags.push(element('span', summary.pending === 'accept' ? 'Accept unconfirmed' : 'Awaiting your review', 'tag tag-review'));
  else if (summary.state === 'running') tags.push(element('span', summary.pending === 'admission' ? 'Assessment starting' : 'Assessment running', 'tag'));
  else if (summary.state === 'failed') tags.push(element('span', 'Assessment failed', 'tag tag-failed'));
  else if (summary.state === 'accepted' && !summary.potential_breaches) tags.push(element('span', 'Assessed', 'tag'));
  return tags;
}
function missingCredentials(system) {
  return (system?.deployment.credentials || []).filter(item => !item.present);
}
// Why a device or the Overview has no byte counters, from the configuration
// rather than a guess: no UniFi, no trafficSite, or quick checks off.
function trafficGap(value) {
  const unifi = value.system?.unifi;
  if (value.system && !unifi) return 'No byte counters: UniFi is not connected to this collector.';
  if (unifi && !unifi.traffic_site) return 'No byte counters: they are off because the UniFi connection has no trafficSite in the collector configuration.';
  if (!value.quick_presence?.enabled) return 'No byte counters: they are read during quick presence checks, which are off.';
  return 'No UniFi byte counters yet. They arrive with the next quick presence checks.';
}
function deviceNames(devices) {
  const shown = devices.slice(0, 2).map(device => device.name);
  return devices.length > 2 ? `${shown.join(', ')} and ${plural(devices.length - 2, 'other', 'others')}` : shown.join(' and ');
}
function openSystemHealth() {
  showView('overview');
  const panel = byId('system-health');
  panel.open = true;
  panel.scrollIntoView?.({block:'start'});
  panel.querySelector('summary')?.focus?.();
}
// One row of the attention band: a figure, what it is, and the one action
// that deals with it. The band lists only what needs the operator; it is
// rebuilt from each refresh, so a settled item leaves it on its own.
function attentionItem(figure, title, detail, label, action) {
  const item = element('li', undefined, 'attention-item');
  item.append(element('span', figure, 'attention-figure'));
  const text = element('div', undefined, 'attention-text');
  text.append(element('h3', title), element('p', detail, 'hint'));
  const button = element('button', label, 'secondary');
  button.type = 'button';
  button.addEventListener('click', action);
  item.append(text, button);
  return item;
}
function reviewAction(devices) {
  if (devices.length === 1) return ['Open ' + devices[0].name, () => focusDevice(devices[0].address)];
  return ['Show them in the device list', () => {showInventory('review', 'attention'); showView('devices', true);}];
}
function renderAttention(value) {
  const items = [];
  const observed = value.devices.filter(device => assessmentOf(device));
  const breached = observed.filter(device => assessmentOf(device).potential_breaches > 0);
  const waiting = observed.filter(device => assessmentOf(device).state === 'review');
  const failed = observed.filter(device => assessmentOf(device).state === 'failed');
  if (breached.length) {
    const total = breached.reduce((sum, device) => sum + assessmentOf(device).potential_breaches, 0);
    items.push(attentionItem(formatCount(total), total === 1 ? 'Potential breach' : 'Potential breaches',
      `Reported for ${deviceNames(breached)}. A second agent checked each against the same data; read the report before acting on it.`, ...reviewAction(breached)));
  }
  if (waiting.length) items.push(attentionItem(formatCount(waiting.length), waiting.length === 1 ? 'Assessment awaiting your review' : 'Assessments awaiting your review',
    `${deviceNames(waiting)}. Accept makes a report final; Dismiss leaves it as a draft.`, ...reviewAction(waiting)));
  if (failed.length) items.push(attentionItem(formatCount(failed.length), failed.length === 1 ? 'Assessment failed' : 'Assessments failed',
    `${deviceNames(failed)}. The device shows why and offers a retry.`, ...reviewAction(failed)));
  const unseen = value.devices.filter(device => deepScanNeedsReview(device, value));
  if (unseen.length) items.push(attentionItem(formatCount(unseen.length), 'New open ports',
    `${deviceNames(unseen)} answered on ports outside the usual checks.`, ...reviewAction(unseen)));
  const drafts = latestStatus?.profile_drafts_waiting || 0;
  if (drafts) items.push(attentionItem(formatCount(drafts), drafts === 1 ? 'Profile draft awaiting review' : 'Profile drafts awaiting review',
    'Background research drafted device profiles. Review each before it is saved.', 'Open drafts', () => {
      showView('devices');
      byId('background-research').scrollIntoView?.({block:'start'});
      loadBackgroundResearch().catch(error => notice(error.message, true));
    }));
  const health = value.health;
  if (!health.connected) items.push(attentionItem('!', 'Plowshare connection', health.detail, 'Open System health', openSystemHealth));
  if (health.recovery.length) items.push(attentionItem(formatCount(health.recovery.length), 'Retained work needs a check', health.recovery[0], 'Open System health', openSystemHealth));
  const missing = missingCredentials(value.system);
  if (missing.length) items.push(attentionItem(formatCount(missing.length), missing.length === 1 ? 'Credential missing' : 'Credentials missing',
    missing.map(item => item.purpose).join(', ') + '. The collector cannot use a connection without it.', 'Open System health', openSystemHealth));
  byId('attention-items').replaceChildren(...items);
  byId('attention').hidden = items.length === 0;
  byId('attention-clear').hidden = items.length > 0;
}
const trustNames = {ca_file:'Private certificate authority file', pinned:'Pinned certificate fingerprint', system:'System trust store'};
function credentialText(item) {
  const where = item.source === 'file' ? 'Private file' : 'Environment variable ' + item.variable;
  return `${where} · ${item.present ? 'present' : 'missing'}`;
}
function credentialFact(list, term, item) {
  fact(list, term, credentialText(item), item.present ? undefined : 'missing');
}
// System health in three fact lists: whether it works, where it connects, and
// how it is deployed. Opens itself once when something there needs attention.
let healthOpened = false;
function renderSystem(system, health) {
  const connections = byId('health-connections'); connections.replaceChildren();
  const deployment = byId('health-deployment'); deployment.replaceChildren();
  if (!system) return;
  if (system.pihole) {
    fact(connections, 'Pi-hole', system.pihole.origin, 'data');
    credentialFact(connections, 'Pi-hole password', system.pihole.password);
    fact(connections, 'Pi-hole status', health.pihole);
  } else fact(connections, 'Pi-hole', 'Not connected. Connect it under Settings for device names and DNS lookups.');
  if (system.unifi) {
    fact(connections, 'UniFi controller', system.unifi.controller, 'data');
    fact(connections, 'UniFi site', system.unifi.site, 'data');
    fact(connections, 'Certificate trust', trustNames[system.unifi.trust]);
    credentialFact(connections, 'UniFi API key', system.unifi.api_key);
    fact(connections, 'Byte counters', system.unifi.traffic_site ? 'On, from site ' + system.unifi.traffic_site : 'Off. Set trafficSite in the UniFi connection to count bytes to and from each device.');
    fact(connections, 'UniFi status', health.unifi);
  } else fact(connections, 'UniFi', 'Not connected. Set it in the collector configuration for device identity and byte counters.');
  fact(connections, 'Advisory source', system.advisory_origin ? 'National Vulnerability Database (NVD), ' + system.advisory_origin : 'Off. Set PLOWSHARE_PRIVACY_NVD_ORIGIN for the collector to look up public advisories.');
  const plan = system.deployment;
  fact(deployment, 'Plowshare server', plan.server_origin, 'data');
  fact(deployment, 'Project', plan.project, 'data');
  fact(deployment, 'Collector', plan.collector, 'data');
  fact(deployment, 'Relay topics', `Requests on ${plan.request_topic}, results on ${plan.result_topic}`);
  fact(deployment, 'Relay group', plan.group, 'data');
  fact(deployment, 'Outgoing peer', plan.outgoing_peer || 'None');
  fact(deployment, 'Time zone', plan.time_zone ? `${plan.time_zone}, for every date shown and the daily limits` : 'UTC: not set. Choose one under Settings › Household time zone', plan.time_zone ? 'data' : undefined);
  fact(deployment, 'Port checks', `${plural(plan.probe_timeout_seconds, 'second', 'seconds')} timeout, ${formatCount(plan.probe_concurrency)} at a time`);
  for (const item of plan.credentials) credentialFact(deployment, item.purpose, item);
  const problem = !health.connected || health.recovery.length > 0 || missingCredentials(system).length > 0;
  if (problem && !healthOpened) {byId('system-health').open = true; healthOpened = true;}
}
// Settings repeats the background limits and today's use, read-only; the
// controls stay with the drafts and reports they produce on the Devices tab.
function renderBackgroundUsage(value) {
  const list = byId('background-usage'); list.replaceChildren();
  const research = latestStatus?.profile_research_available;
  const usage = value.assessments?.usage;
  const deep = value.deep_scans?.usage;
  byId('background-work').hidden = !research && !usage && !deep;
  if (research) {
    fact(list, 'Profile research', latestStatus.profile_research_enabled ? 'On' : 'Off');
    fact(list, 'Research used today', `${formatCount(latestStatus.profile_research_used_today)} of ${formatCount(latestStatus.profile_research_daily_limit)}`);
    fact(list, 'Drafts to review', formatCount(latestStatus.profile_drafts_waiting || 0));
  }
  if (usage) {
    fact(list, 'Background assessments', usage.background_limit ? `Up to ${formatCount(usage.background_limit)} a day` : 'Off');
    fact(list, 'Assessments used today', `${formatCount(usage.used_today)} of ${formatCount(usage.daily_ceiling)}, including those you started`);
  }
  if (deep) fact(list, 'Automatic deep scans used today', deep.automatic_limit ? `${formatCount(deep.used_today)} of ${formatCount(deep.automatic_limit)}` : 'Off');
}

function renderOverview(value) {
  const health = value.health;
  assessmentIndex = new Map((value.assessments?.summaries || []).map(item => [item.device_id, item]));
  byId('device-count').textContent = formatCount(value.devices.length);
  byId('candidate-count').textContent = formatCount(value.candidates.length);
  const missing = missingCredentials(value.system);
  byId('health-summary').textContent = !health.connected ? 'Connection needs attention' : health.recovery.length ? 'Retained work needs attention' : missing.length ? plural(missing.length, 'credential is missing', 'credentials are missing') : 'Connected to Plowshare';
  byId('system-health').classList.toggle('attention-needed', !health.connected || health.recovery.length > 0 || missing.length > 0);
  byId('setup-checklist').hidden = health.checklist.length === 0;
  renderScheduleControl(health.schedule, value.schedule_control, value.pending_deployment);
  renderSchedule(health.schedule);
  byId('deployment-state').textContent = value.pending_deployment ? `Deployment ${value.pending_deployment} needs reconciliation. Enter the administrator password and choose Check retained deployment.` : 'No unsettled schedule deployment.';
  const grid = byId('health'); grid.replaceChildren();
  const checks = [['Collector', byId('state').textContent], ['Plowshare', health.connected ? 'Read checks passed' : 'Needs attention'], ['Last contact', health.last_contact ? formatWhen(health.last_contact) : 'Awaiting contact'], ['Last completed scan', health.last_scan ? formatWhen(health.last_scan) : 'None yet'], ['Schedule', health.schedule ? scheduleLabel(health.schedule.cron) + ' · ' + health.schedule.zone : 'Not found or unavailable'], ['Schedule runtime', health.schedule ? (health.schedule.paused ? 'Paused' : health.schedule.next_at ? 'Next scan ' + formatWhen(health.schedule.next_at) : 'Awaiting next scan') : 'Not found or unavailable'], ['Project agents', health.agents ? `${health.agents.served.length} of ${health.agents.served.length + health.agents.unavailable.length} available${health.agents.unavailable.length ? ' · missing: ' + health.agents.unavailable.join(', ') : ''}` : 'Unavailable'], ['Dynamic tool visibility', health.agents ? toolVisibility(health.agents) : 'Unavailable'], ['Service credential expiry', health.credential_expires_at ? formatWhen(health.credential_expires_at) : 'Not supplied']];
  for (const [name, detail] of checks) fact(grid, name, detail);
  renderSystem(value.system, health);
  byId('health-detail').textContent = `Checked ${formatWhen(health.checked_at)}. ${health.detail}`;
  byId('checklist').replaceChildren(...health.checklist.map(item => element('li', item)));
  byId('recovery').replaceChildren(...health.recovery.map(item => element('p', item, 'notice error')));
  const runs = byId('recent-runs'); runs.replaceChildren();
  if (!health.runs.length) runs.append(element('p', 'No project investigations yet.', 'empty'));
  for (const run of health.runs) {
    const row = element('div', undefined, 'record');
    const line = element('div', undefined, 'entry-line');
    line.append(element('span', statusLabel(run.state), 'badge'), element('code', run.id));
    row.append(line);
    runs.append(row);
  }
  const presenceStatus = value.quick_presence;
  const quickStatus = byId('quick-presence-status');
  quickStatus.textContent = presenceStatus?.enabled
    ? (presenceStatus.last_checked ? 'Last quick check ' + formatWhen(presenceStatus.last_checked) + '.' : 'Waiting for the first quick check.')
      + (presenceStatus.issues.length ? ' ' + presenceStatus.issues.join(' ') : '')
    : 'Quick presence checks are off.';
  renderAttention(value);
  renderBackgroundUsage(value);
  byId('deep-scan-group').hidden = !value.deep_scans;
  // Never overwrite a limit the operator is typing.
  if (value.deep_scans && document.activeElement !== byId('deep-scan-limit')) renderDeepScanSettings({...value.deep_scans.usage, available:value.deep_scans.available, detail:value.deep_scans.detail});
  const nextDeviceSignature = JSON.stringify([value.devices, value.candidates, value.assessments?.summaries || null]);
  if (nextDeviceSignature !== deviceSignature) {
  deviceSignature = nextDeviceSignature;
  renderInventory(value);
  renderTriptych(value);
  }
  if (pendingTab || pendingDevice) {
    const tab = pendingTab, address = pendingDevice, list = pendingList; pendingTab = null; pendingDevice = null; pendingList = null;
    restoring = true;
    try {
      if (list) applyListParams(list);
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
    const card = element('article', undefined, 'record'); card.append(element('h3', finding.title), PrivacyMarkdown.render(finding.explanation));
    if (finding.expected) card.append(element('p', 'Marked expected', 'status-line'));
    const open = element('button', 'Read evidence', 'secondary'); open.type = 'button'; open.addEventListener('click', () => showEvidence(finding.scan_id).catch(error => notice(error.message, true)));
    const acknowledge = element('button', finding.expected ? 'Undo expected' : 'Mark expected', 'secondary'); acknowledge.type = 'button';
    acknowledge.addEventListener('click', async () => {setBusy(acknowledge, true); try {await api('/api/expected', {method:'POST', body:JSON.stringify({id:finding.id,expected:!finding.expected})}); await refresh();} catch (error) {notice(error.message,true); setBusy(acknowledge, false);}});
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
byId('open-background').addEventListener('click', () => {
  showView('devices');
  const target = byId('background-assessments').hidden ? byId('background-research') : byId('background-assessments');
  target.scrollIntoView?.({block:'start'});
});
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
// A whole number of the largest unit that divides it: 7200 is "2 hours".
function formatDuration(seconds) {
  for (const [size, one, many] of [[86400, 'day', 'days'], [3600, 'hour', 'hours'], [60, 'minute', 'minutes']]) {
    if (seconds >= size && seconds % size === 0) return plural(seconds / size, one, many);
  }
  return plural(seconds, 'second', 'seconds');
}
// `pihole` is the saved Pi-hole view: where its password comes from and its
// tuning, which is collector configuration that a dashboard save keeps. The
// password itself never reaches the browser; an environment variable is named.
function renderPiHoleSaved(pihole, pending = false) {
  const source = !pihole ? null
    : pihole.password_source === 'environment' ? `Saved connection: Pi-hole is configured. Its password is read from the environment variable ${pihole.password_environment} on this collector.`
    : pihole.password_source === 'file' ? 'Saved connection: Pi-hole is configured. Its password is stored in a private file on this collector.'
    : 'Saved connection: Pi-hole is configured.';
  byId('pihole-saved').textContent = source ? source + (pending ? ' Waiting to apply saved settings.' : '') : 'Saved connection: no Pi-hole connection is configured yet.';
  const facts = byId('pihole-tuning'); facts.replaceChildren();
  const tuned = Boolean(pihole && Number.isFinite(pihole.lookback_seconds));
  facts.hidden = !tuned;
  if (!tuned) return;
  fact(facts, 'DNS lookup window', formatDuration(pihole.lookback_seconds));
  fact(facts, 'Lookups read per device', plural(pihole.query_limit, 'lookup', 'lookups'));
  fact(facts, 'Maximum identity age', formatDuration(pihole.max_identity_age_seconds));
  fact(facts, 'Request timeout', plural(pihole.timeout_seconds, 'second', 'seconds'));
}
let piholeSaved = null;
function renderMonitorSettings(settings) {
  monitorEditable = settings.editable;
  byId('save-monitoring').disabled = !monitorEditable;
  byId('quick-scan').disabled = !monitorEditable;
  byId('dns-profiles').disabled = !monitorEditable || !settings.pihole_configured;
  byId('device-descriptions').disabled = !monitorEditable;
  byId('settings-detail').textContent = monitoringDraft ? 'Monitoring changes are not saved. Choose Save monitoring to retain them. ' + settings.detail : settings.detail;
  piholeSaved = settings.pihole || (settings.pihole_configured ? {} : null);
  renderPiHoleSaved(piholeSaved, settings.pending);
  byId('pihole-save').disabled = !monitorEditable || piholePending;
  byId('pihole-blocker').textContent = settings.pending || !monitorEditable ? settings.detail : '';
  scanState.pending = !!settings.pending;
  applyScanState();
}
async function piholeConnection(save) {
  if (piholePending) return;
  piholePending = true;
  byId('pihole-test').disabled = true; byId('pihole-save').disabled = true;
  markBusy(byId(save ? 'pihole-save' : 'pihole-test'), true);
  const password = byId('pihole-password').value; byId('pihole-password').value = '';
  byId('pihole-detail').textContent = save ? 'Saving connection and password…' : 'Testing connection; your password will not be saved…';
  try {
    const result = await api('/api/pihole', {method:'POST', body:JSON.stringify({origin:byId('pihole-origin').value.trim(),password,save})});
    const message = result.saved ? 'Pi-hole connection and password saved on this collector. Connection was not tested.' + (result.pending ? ' Saved settings are waiting to apply; existing work keeps its original configuration.' : '') : result.connected ? 'Connection test passed. Your password was not saved; enter it again and choose Save connection to save it.' : 'Connection test failed. Your saved settings are unchanged.';
    byId('pihole-detail').textContent = `${message} ${result.issues.join(' ')} The password field was cleared for privacy.`;
    if (result.saved) {
      renderPiHoleSaved({...piholeSaved, password_source:'file', password_environment:null}, result.pending); settingsLoaded = false;
      // A later health/read failure cannot undo the confirmed save response.
      await refresh().catch(error => notice('Pi-hole was saved, but dashboard refresh failed. ' + error.message, true));
    }
  } catch (error) {byId('pihole-detail').textContent = `${save ? 'Save was not confirmed. ' : 'Connection test failed. '}${error.message} The password field was cleared; enter it again to retry.`;}
  finally {piholePending = false; setBusy(byId('pihole-test'), false); markBusy(byId('pihole-save'), false); byId('pihole-save').disabled = !monitorEditable;}
}
byId('pihole-test').addEventListener('click', () => piholeConnection(false));
byId('pihole-form').addEventListener('submit', event => {event.preventDefault(); piholeConnection(true);});
byId('recover').addEventListener('click', async () => {
  setBusy(byId('recover'), true);
  try {await api('/api/recovery', {method:'POST', body:'{}'}); await refresh(); notice('Retained receipts checked. Collection can resume; no uncertain effect was resent.');} catch (error) {notice(error.message,true);} finally {setBusy(byId('recover'), false);}
});
byId('check-moves').addEventListener('click', async () => {
  setBusy(byId('check-moves'), true);
  try {
    const moves = await api('/api/device-moves', {method:'POST', body:'{}'}); byId('moves').replaceChildren();
    if (!moves.length) byId('moves').append(element('p', 'No fresh reassociation found in the latest discovery. Run Find devices before checking again.', 'empty'));
    for (const move of moves) {
      const button = element('button', `Confirm ${move.device_id}: ${move.old} → ${move.new}`, 'entry'); button.type = 'button';
      button.addEventListener('click', async () => {setBusy(button, true); try {await api('/api/device-moves/accept', {method:'POST', body:JSON.stringify(move)}); settingsLoaded = false; await refresh(); byId('moves').replaceChildren();} catch (error) {notice(error.message,true); setBusy(button, false);}}); byId('moves').append(button);
    }
  } catch (error) {notice(error.message,true);} finally {setBusy(byId('check-moves'), false);}
});
function renderScheduleControl(schedule, control, pendingDeployment) {
  byId('schedule-runtime').textContent = schedule ? (schedule.paused ? 'Paused' : 'Running') : 'Unavailable';

  const pending = scheduleControlUnknown || Boolean(control && control.pending);
  const available = Boolean(control && control.available && schedule);
  byId('pause-schedule').disabled = scheduleControlBusy || pending || Boolean(pendingDeployment) || !available || schedule.paused;
  byId('resume-schedule').disabled = scheduleControlBusy || pending || Boolean(pendingDeployment) || !available || !schedule.paused;
  byId('check-schedule').disabled = scheduleControlBusy || !control || !control.available;
  // When neither Pause nor Resume can be used, say why; the outcome of the
  // operator's last action stays in place otherwise.
  const blocked = scheduleBlocked(schedule, control, pending, pendingDeployment);
  if (blocked) byId('schedule-control-detail').textContent = blocked;
}
function scheduleBlocked(schedule, control, pending, pendingDeployment) {
  if (pending) return 'A schedule change is unconfirmed. Check schedule state before another change; checking never resends the control.';
  if (!control || !control.available) return 'Pause and Resume are unavailable: this collector was started without schedule control.';
  if (!schedule) return 'Pause and Resume are unavailable: the project schedule could not be read. Check the Plowshare connection under System health.';
  if (pendingDeployment) return 'Pause and Resume wait until the unsettled schedule deployment is checked. Use Check retained deployment under Settings › Change schedule frequency.';
  return null;
}
function toolVisibility(agents) {
  if (agents.tool_visibility === 'awaiting_context') return 'Awaiting first project conversation';
  const total = agents.tools.length + agents.missing_tools.length;
  return `${agents.tools.length} of ${total} visible` + (agents.missing_tools.length ? ' · missing: ' + agents.missing_tools.join(', ') : '');
}

async function controlSchedule(paused) {
  if (scheduleControlBusy || (paused === null ? byId('check-schedule').disabled : byId(paused ? 'pause-schedule' : 'resume-schedule').disabled)) return;
  scheduleControlBusy = true;
  const acting = byId(paused === null ? 'check-schedule' : paused ? 'pause-schedule' : 'resume-schedule');
  markBusy(acting, true);
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
    markBusy(acting, false);
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
    byId('schedule-review').textContent = `${scheduleDraft.paused ? 'Paused' : 'Enabled'} · ${scheduleLabel(scheduleDraft.cron)} · ${scheduleDraft.zone}. Based on revision ${scheduleDraft.expected_revision}. Source digest ${scheduleDraft.source_digest}. Enter the administrator password again to deploy.`;
    byId('schedule-store').replaceChildren(...scheduleDraft.stores.map(store => {const option = element('option', store); option.value = store; return option;}));
    const sources = element('details'); sources.append(element('summary', 'Details · full Application source'));
    for (const file of scheduleDraft.sources) {sources.append(element('h3', file.path), element('pre', file.text));}
    byId('schedule-review').append(sources);
    byId('schedule-draft').hidden = false;
  } catch (error) {notice(error.message,true);}
});
byId('deploy-schedule').addEventListener('click', async () => {
  if (!scheduleDraft || !byId('administrator-password').value) {notice('Review a revision and enter the administrator password to deploy.',true); return;}
  const password = byId('administrator-password').value; byId('administrator-password').value = ''; setBusy(byId('deploy-schedule'), true);
  try {const result = await api('/api/schedule/deploy', {method:'POST',body:JSON.stringify({review_id:scheduleDraft.id,store:byId('schedule-store').value,password})}); scheduleDraft = null; byId('schedule-draft').hidden = true; await refresh(); notice(`Application revision ${result.revision} retained. Schedule activation may take a moment.`);} catch (error) {notice(error.message,true);} finally {setBusy(byId('deploy-schedule'), false);}
});

byId('check-deployment').addEventListener('click', async () => {
  const password = byId('administrator-password').value; byId('administrator-password').value = '';
  if (!password) {notice('Enter the configured deployment administrator password to inspect its retained receipt.', true); return;}
  setBusy(byId('check-deployment'), true);
  try {const result = await api('/api/schedule/reconcile', {method:'POST', body:JSON.stringify({password})}); await refresh(); notice(result.revision ? `Confirmed active revision ${result.revision}. No deployment was resent.` : 'No unsettled schedule deployment.');} catch (error) {notice(error.message, true);} finally {setBusy(byId('check-deployment'), false);}
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
    const button = element('button', `${view.profile.fields.name} · ${view.profile.fields.addresses.join(', ')}`, 'entry'); button.type = 'button';
    button.addEventListener('click', () => editProfile(view).catch(error => notice(error.message, true))); records.append(button);
  }
  if (!profileBlocked) byId('profile-detail').textContent = `${plural(result.profiles.length, 'device profile', 'device profiles')} loaded from Plowshare. Older revisions remain available below each profile.`;
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
  if (!queue.entries.length) list.append(element('p', 'No drafts or research activity to review.', 'empty'));
  for (const entry of queue.entries) {
    const name = entry.label || entry.model || entry.address;
    const button = element('button', name + ' · ' + (researchPhases[entry.phase] || statusLabel(entry.phase)), 'entry');
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
  output.replaceChildren(element('p', [entry.identity.address, entry.identity.mac, researchPhases[entry.phase] || statusLabel(entry.phase)].filter(Boolean).join(' · '), 'hint source'));
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
  byId('research-title').textContent = 'Product and advisories';
  byId('research-observation').textContent = '';
  byId('research-unifi-result').textContent = '';
  byId('research-results').replaceChildren();
}
function supplementalFor(device) {
  return deviceSupplemental && deviceSupplemental.device_id === device.identity?.device_id ? deviceSupplemental.section : null;
}
// The model to search for: the one the last automatic lookup used, else what
// the device reported about itself. Still the operator's to confirm.
function reportedModel(device) {
  const section = supplementalFor(device);
  if (!section) return '';
  if (section.advisories?.model) return section.advisories.model;
  const described = section.descriptions.find(item => item.model_name);
  return described ? [described.manufacturer, described.model_name].filter(Boolean).join(' ') : '';
}
function productHint(device) {
  const lookup = supplementalFor(device)?.advisories;
  if (lookup?.status === 'searched' && lookup.model) {
    return `Advisories for ${lookup.model} were looked up ${formatDay(lookup.checked_at)} and are shown under What we know. Search here to check a different model, or confirm the product with UniFi.`;
  }
  return 'Confirm the product with UniFi, then search the public NVD advisory database by model name. No agent is involved.';
}
function openDeviceResearch(device) {
  clearDeviceResearch();
  researchDevice = device;
  byId('research-model').value = reportedModel(device);
  byId('research-title').textContent = 'Product and advisories';
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
  setBusy(byId('research-unifi'), true);
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
    if (device === researchDevice) setBusy(byId('research-unifi'), false); else markBusy(byId('research-unifi'), false);
  }
});
byId('research-form').addEventListener('submit', async event => {
  event.preventDefault();
  const device = researchDevice;
  const model = byId('research-model').value.trim();
  if (!device || !model || !advisoriesAvailable) return;
  const results = byId('research-results');
  setBusy(byId('research-search'), true);
  results.replaceChildren(element('p', 'Checking NVD…', 'hint'));
  try {
    const page = await api(researchPath(device, 'advisories'), {method:'POST', body:JSON.stringify({model})});
    if (device === researchDevice) renderAdvisoryPage(results, page);
  } catch (error) {
    if (device === researchDevice) results.replaceChildren(element('p', error.message, 'notice error'));
  } finally {
    markBusy(byId('research-search'), false);
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
    ? 'Showing ' + formatCount(page.advisories.length) + ' of ' + formatCount(page.total) + ' keyword matches.'
    : 'No keyword matches found.';
  results.replaceChildren(element('p', 'NVD · checked ' + formatWhen(page.checked_at) + ' · ' + detail, 'hint source'),
    element('p', 'A keyword match is about the model name, not proof that this device is affected. Check the affected model, firmware and configuration in each advisory.', 'limit'));
  for (const item of page.advisories) {
    const link = element('a', item.cve);
    link.href = 'https://nvd.nist.gov/vuln/detail/' + encodeURIComponent(item.cve);
    link.target = '_blank'; link.rel = 'noopener noreferrer';
    const entry = element('article', undefined, 'card');
    const metadata = [item.severity ? item.severity[0] + item.severity.slice(1).toLowerCase() : 'Severity not rated',
      'published ' + formatDay(item.published_on), item.known_exploited ? 'known exploited' : null].filter(Boolean);
    const name = element('p', undefined, 'data'); name.append(link);
    entry.append(name, element('p', metadata.join(' · '), 'status-line'), element('p', item.description, 'hint'));
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
    setBusy(button, true);
    results.replaceChildren(element('p', 'Checking NVD…', 'hint'));
    try {
      const path = '/api/profiles/' + encodeURIComponent(view.profile.profile_id)
        + '/' + encodeURIComponent(view.revision) + '/advisories';
      const page = await api(path, {method:'POST', body:JSON.stringify({model})});
      renderAdvisoryPage(results, page);
    } catch (error) {
      results.replaceChildren(element('p', error.message, 'notice error'));
    } finally {
      setBusy(button, false);
    }
  });
  panel.append(button, results);
  return panel;
}

// The monitored device a profile describes, or null. A confirmed MAC names
// it. An address alone does not establish identity, so an address-only
// profile belongs only to the open device it was opened from.
function profileDevice(fields, deviceMac) {
  const devices = overview?.devices || [];
  const mac = fields.device_mac || deviceMac;
  if (mac) return devices.find(device => knownIdentity(device).known?.mac === mac) || null;
  return devices.find(device => device.address === selectedDevice && fields.addresses.includes(device.address)) || null;
}
async function editProfile(view, address = null, deviceMac = null) {
  await profileReceipts();
  const settings = await api('/api/monitoring');
  const fields = view ? view.profile.fields : {name:'',owner:'',model:'',purpose:'',expected_services:'',notes:'',addresses:address ? [address] : []};
  // The pane's title and device facts must be those of the profile being
  // edited. Switching first, because opening or closing a device closes
  // any editor that is open.
  const owner = profileDevice(fields, deviceMac);
  if (owner && owner.address !== selectedDevice) openDevice(owner.address);
  else if (!owner && selectedDevice) closePane();
  profileEdit = {id:view ? view.profile.profile_id : crypto.randomUUID(), revision:view ? view.revision : null, deviceMac:view?.profile.fields.device_mac || deviceMac, name:fields.name};
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
      const read = element('button', `Revision ${version.ordinal} · ${formatWhen(version.created_at)}`, 'entry'); read.type = 'button';
      read.title = version.revision;
      read.addEventListener('click', async () => {try {const source = await api(`/api/profiles/${view.profile.profile_id}/${version.revision}`); byId('profile-markdown').replaceChildren(renderProfileDocument(source.text)); byId('profile-markdown').hidden = false; syncPane(); byId('profile-markdown').scrollIntoView({block:'nearest'});} catch(error) {notice(error.message,true);}}); history.append(read);
    }
    history.append(element('h3', 'Recent retained evidence at associated addresses'));
    if (!view.evidence.length) history.append(element('p', 'No matching evidence among the latest 50 local collections.', 'empty'));
    for (const revision of view.evidence) {
      const read = element('button', `Read collection ${revision.slice(0,8)}`, 'entry'); read.type = 'button'; read.title = revision;
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
  setBusy(byId('save-profile'), true);
  const fields = {name:byId('profile-name').value,owner:byId('profile-owner').value,model:byId('profile-model').value,purpose:byId('profile-purpose').value,expected_services:byId('profile-expected').value,notes:byId('profile-notes').value,addresses:[...byId('profile-addresses').selectedOptions].map(value => value.value),issues:readIssueLinks(),device_mac:profileEdit.deviceMac || null};
  try {
    const receipt = await api('/api/profiles', {method:'POST',body:JSON.stringify({profile_id:profileEdit.id,request_id:crypto.randomUUID(),expected_revision:profileEdit.revision,fields})});
    if (receipt.phase === 'confirmed') {profileEdit = null; byId('profile-form').hidden = true; byId('profile-markdown').hidden = true; byId('profile-history').replaceChildren(); syncPane(); const shown = selectedDevice && overview?.devices.find(item => item.address === selectedDevice); if (shown) loadDeviceAnalysis(shown); notice('Device profile saved in Plowshare. Processing may still be pending; load profiles again when ready.');}
    else notice(`Profile write ${receipt.request_id} needs reconciliation. It has not been resent.`, true);
  } catch(error) {notice(error.message,true);} finally {markBusy(byId('save-profile'), false); await profileReceipts().catch(() => {byId('save-profile').disabled = true;});}
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
  const row = element('div', undefined, 'card');
  const url = element('input'); url.type = 'url'; url.required = true; url.maxLength = 1024; url.value = source.url; url.dataset.field = 'url';
  labelled(row, 'HTTPS source URL', url);
  const date = element('input'); date.type = 'date'; date.value = source.published_on || ''; date.dataset.field = 'published_on'; labelled(row, 'Published on (optional)', date);
  const remove = element('button', 'Remove source', 'quiet'); remove.type = 'button'; remove.addEventListener('click', () => row.remove()); row.append(remove); byId('issue-sources').append(row);
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
const mechanismLabels = {firewall:'Router or firewall rule', pihole:'Pi-hole domain block', segmentation:'Network segmentation', device_setting:'Device privacy setting'};
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
    const card = element('article', undefined, 'record');
    const mechanism = view.issue.fields.mitigation.mechanism;
    card.append(element('h3', view.issue.fields.title), element('p', `Sources checked ${formatDay(view.issue.fields.checked_on)} · ${mechanismLabels[mechanism] || statusLabel(mechanism)}`, 'hint source'));
    const edit = element('button', 'Edit issue and mitigation', 'secondary'); edit.type = 'button'; edit.addEventListener('click', () => editIssue(view).catch(error => notice(error.message,true))); card.append(edit); records.append(card);
  }
  if (!issueBlocked) byId('issue-detail').textContent = `${plural(result.issues.length, 'shared issue', 'shared issues')} loaded. Link reviewed plans from a device profile.`;
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
      const read = element('button', `Revision ${version.ordinal} · ${formatWhen(version.created_at)}`, 'entry'); read.type = 'button';
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
  byId('issue-markdown').replaceChildren(foldDetails(PrivacyMarkdown.render(source.text))); byId('issue-markdown').hidden = false; syncPane();
  byId('issue-markdown').scrollIntoView({block:'nearest'});
}
async function renderIssueLinks(links, evidence) {
  if (!issuesAvailable) {
    if (links.length) throw new Error('The linked privacy issue catalogue is unavailable. Your retained links are preserved.');
    byId('profile-issue-links').replaceChildren(element('p', 'Privacy issue management is unavailable on this collector.', 'hint unmeasured'));
    return;
  }
  const result = await api('/api/issues');
  const heads = new Map(result.issues.map(v => [v.issue.issue_id,v]));
  const identities = [...new Set([...links.map(v => v.issue_id), ...heads.keys()])];
  const parent = byId('profile-issue-links'); parent.replaceChildren();
  if (!identities.length) parent.append(element('p', 'No shared privacy issue yet. Create one under Privacy issues and mitigations, then reopen this profile to link it.', 'empty'));
  for (const identity of identities) {
    const old = links.find(v => v.issue_id === identity); const head = heads.get(identity);
    const card = element('details', undefined, 'card issue-link'); card.open = !!old;
    card.dataset.issue = identity; card.dataset.revision = old?.revision || head.revision;
    card.append(element('summary', head ? head.issue.fields.title : `Retained issue ${identity}`));
    const enabled = element('input'); enabled.type = 'checkbox'; enabled.checked = !!old; enabled.dataset.field = 'linked'; labelled(card, 'Link this issue to this device', enabled);
    const applicability = element('select'); applicability.dataset.field = 'applicability'; applicability.replaceChildren(...optionList(applicabilityLabels, old?.applicability || 'potentially_affected')); labelled(card, 'Device applicability', applicability);
    const status = element('select'); status.dataset.field = 'status'; status.replaceChildren(...optionList(mitigationLabels,old?.mitigation_status || 'proposed')); labelled(card, 'Mitigation progress', status);
    const notes = element('textarea'); notes.rows = 3; notes.maxLength = 512; notes.value = old?.notes || ''; notes.dataset.field = 'notes'; labelled(card, 'Assessment, action taken and verification result', notes);
    const revisions = element('select'); revisions.multiple = true; revisions.dataset.field = 'evidence'; revisions.replaceChildren(...[...new Set([...evidence,...(old?.evidence || [])])].map(value => {const option = element('option', value); option.value = value; option.selected = old?.evidence.includes(value); return option;})); labelled(card, 'Supporting evidence revisions (up to four)', revisions);
    const read = element('button', 'Read issue and mitigation plan', 'secondary'); read.type = 'button'; read.addEventListener('click', () => readIssue(identity,card.dataset.revision).catch(error => notice(error.message,true))); card.append(read);
    if (head && old && head.revision !== old.revision) {
      const updated = element('p', 'A newer issue revision is available. This device still references the reviewed plan.', 'hint');
      const adopt = element('button', 'Review and use latest plan', 'secondary'); adopt.type = 'button'; adopt.addEventListener('click', async () => {
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
  event.preventDefault(); if (!issueEdit || issueBlocked) return; setBusy(byId('save-issue'), true);
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
  } catch(error) {notice(error.message,true);} finally {markBusy(byId('save-issue'), false); await issueReceipts().catch(() => {byId('save-issue').disabled = true;});}
});
