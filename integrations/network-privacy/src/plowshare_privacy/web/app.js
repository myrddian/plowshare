'use strict';

// The web bearer exists only in this tab's memory. All untrusted evidence is text.
let bearer = '';
let selected = null;
let refreshPending = false;
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
  const response = await fetch(path, { ...options, headers: {Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json'}, cache:'no-store'});
  if (!response.ok) {
    if (response.status === 401) throw new Error('Dashboard token was refused. Reconnect with the configured web token.');
    if (response.status === 409) throw new Error('Request delivery is uncertain. Inspect its saved identity; it has not been resent.');
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
  listItems(panel, 'Changes against the previous collection', evidence.changes);
  listItems(panel, 'Coverage & gaps', evidence.issues);
  panel.append(element('h3', 'Evidence revision'));
  panel.append(element('p', result.revision || 'Not yet retained in Plowshare.'));
  panel.append(element('h3', 'Observations'));
  panel.append(element('pre', JSON.stringify(evidence.snapshot, null, 2)));
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
  if (!bearer || refreshPending) return;
  refreshPending = true;
  byId('refresh').disabled = true;
  try {
    const [status, scans, reportResult] = await Promise.all([api('/api/status'), api('/api/scans'), api('/api/reports').then(value => ({value}), error => ({error}))]);
    if (!Array.isArray(scans) || typeof status.state !== 'string' || !Array.isArray(status.requests)) throw new Error('Unexpected dashboard response.');
    byId('mode').textContent = status.mode === 'fixture' ? 'Synthetic fixture mode' : 'TCP collection mode';
    byId('project').textContent = status.project;
    byId('collector').textContent = status.collector;
    byId('state').textContent = status.state === 'attention_required' ? 'Needs attention' : status.state.replaceAll('_', ' ');
    byId('detail').textContent = status.detail;
    byId('change-count').textContent = scans.length ? scans[0].changes.filter(change => !change.startsWith('Baseline')).length : '—';
    byId('report-count').textContent = reportResult.error ? 'Unavailable' : reportResult.value.length;
    byId('scan').disabled = status.state === 'attention_required';
    renderCollections(scans);
    if (reportResult.error) byId('reports').replaceChildren(element('p', 'Retained agent reports are temporarily unavailable. Local evidence remains readable.', 'empty'));
    else if (Array.isArray(reportResult.value)) renderReports(reportResult.value);
    else throw new Error('Unexpected reports response.');
    byId('dashboard').hidden = false;
    byId('connect').hidden = true;
    const unknown = status.requests.find(request => request.state === 'pending');
    if (unknown) notice(`Request ${unknown.request_id} needs reconciliation. It has not been resent.`, true);
    else if (reportResult.error) notice(reportResult.error.message, true);
    else notice(status.state === 'attention_required' ? status.detail : 'Evidence is retained before an investigation is requested.', status.state === 'attention_required');
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
byId('disconnect').addEventListener('click', () => {
  bearer = ''; selected = null;
  byId('dashboard').hidden = true; byId('connect').hidden = false;
  byId('collections').replaceChildren(); byId('reports').replaceChildren(); byId('evidence').replaceChildren();
  byId('mode').textContent = 'Not connected'; notice('Disconnected. Private evidence has been cleared from view.');
});
byId('scan').addEventListener('click', async () => {
  byId('scan').disabled = true;
  try {
    if (!globalThis.crypto?.randomUUID) throw new Error('Scan requests need a secure browser context: use HTTPS or a loopback development listener.');
    const requestId = crypto.randomUUID();
    notice(`Publishing scan request ${requestId}…`);
    await api('/api/scans', {method:'POST', body:JSON.stringify({request_id:requestId})});
    notice(`Request ${requestId} is available through Relay. Collection has not yet completed.`);
  } catch (error) {notice(error.message, true);}
  finally {byId('scan').disabled = false;}
});
