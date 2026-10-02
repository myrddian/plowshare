import { mountUsagePanel, type UsagePanel } from '../../../plowshare-console/src/screens/usage-panel.ts';
import { installOperator } from './operator.ts';
import { installInformation } from './information.ts';
import { activeJob, stoppedJob, homeKey, contextKey } from '../shared.ts';
import type { DesktopState, Entry, Job, Request } from '../shared.ts';
import { markdownHtml } from './markdown.ts';
import { icon, mountIcons } from './icons.ts';
import { installNavigation } from './navigation.ts';
import type { NavigationItem } from './navigation.ts';
import { copyButton, installCopyControls } from './copy.ts';
import { describePace } from 'plowshare-client-ts/operations/pace';
import { installSync } from './sync.ts';
import { installApprovalControls, noticeApproval } from './approvals.ts';

mountIcons();
installCopyControls();

const $ = <T extends HTMLElement = HTMLElement>(selector: string): T => document.querySelector(selector)!;
const esc = (value: unknown) => String(value ?? '').replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]!);
const dialog = $<HTMLDialogElement>('#connection-dialog');
const draft = $<HTMLTextAreaElement>('#draft');
const transcript = $('#transcript');
const stage = $('#stage');
const chat = $('#chat');
const agentSelect = $<HTMLSelectElement>('#agent');
let state: DesktopState;
const approvalControls = installApprovalControls(document, () => state, request, render);
const information=installInformation(window.plowshare,()=>state,()=>scope);
let selected = '';
let selectionStamp = '';
let scope = 'Research';
let tab: 'context' | 'memory' | 'usage' = 'context';
let drafts: Record<string, string> = {};
let chosenAgents: Record<string, string> = {};
let preferences: Record<string, { selected: string; scope: string; drafts: Record<string, string>; projectExpansion?: Record<string, boolean> }> = {};
let projectExpansion: Record<string, boolean> = {};
let filterExpansion: Record<string, boolean> = {};
let identity = '';
let signature = '';
let docked = false;
let expanded = false;
let inspectorHidden = false;
let offset = { x: 0, y: 0 };
let busy = false;
let navigation: ReturnType<typeof installNavigation> | undefined;
let connectionError = '';
let sidebarHidden = false;
const historyBusy = new Set<string>();
const measurements = new Map<string, { at: number; pending: boolean }>();
try { preferences = JSON.parse(localStorage.getItem('plowshare.desktop.ui.v1') ?? '{}'); } catch { /* Start with a clean view if a local draft is unreadable. */ }

function persist() {
  if (!identity) return;
  preferences[identity] = { selected, scope, drafts, projectExpansion };
  try { localStorage.setItem('plowshare.desktop.ui.v1', JSON.stringify(preferences)); } catch { /* Drafts still remain in this window if storage is full. */ }
}
function key(next: DesktopState) { return next.mode === 'demo' ? 'demo' : `${next.base}|${next.handle}`; }
function current() { return state?.conversations.find(row => row.id === selected); }
function title(id: string) {
  const conversation = state.conversations.find(row => row.id === id);
  return conversation?.title || state.history[id]?.entries.find(entry => entry.kind === 'utterance')?.text?.slice(0, 56) || 'New conversation';
}
function selectedJob(): Job | undefined { return state?.jobs.filter(job => job.conversation === selected).at(-1); }
function update(next: DesktopState) {
  if (!state?.connected && next.connected) measurements.clear();
  const nextIdentity = key(next);
  if (nextIdentity !== identity) {
    persist(); identity = nextIdentity;
    const saved = preferences[identity];
    drafts = saved?.drafts ?? {}; selected = saved?.selected ?? ''; scope = saved?.scope ?? (next.mode === 'demo' ? 'Research' : '');
    projectExpansion = saved?.projectExpansion ?? {}; filterExpansion = {};
    chosenAgents = {}; signature = ''; measurements.clear();
  }
  state = next;
  const opening = state.mode === 'live' && /^(Connecting|Opening projects)/.test(state.connection);
  if (next.jobRecoveryError) error(next.jobRecoveryError);
  if (next.connectionPersistenceError) error(next.connectionPersistenceError);
  else if (connectionError && $('#error span').textContent === connectionError) $('#error').hidden = true;
  connectionError = next.connectionPersistenceError ?? '';
  if (!opening && !state.projects.some(row => row.name === scope)) scope = '';
  const rows = state.conversations.filter(row => homeKey(row.project) === scope);
  if (!opening && !rows.some(row => row.id === selected)) selected = rows[0]?.id ?? '';
  if (draft.dataset.conversation !== selected) { draft.value = drafts[selected] ?? ''; draft.dataset.conversation = selected; signature = ''; }
  render(); persist();
  followSelection();
}
function followSelection() {
  if (state.mode !== 'live' || !state.connected) { selectionStamp = ''; return; }
  const stamp = JSON.stringify([identity, selected]);
  if (stamp === selectionStamp) return;
  selectionStamp = stamp;
  action({ action: 'select', ...(selected ? { conversation: selected } : {}) });
}
function error(message: string) { $('#error span').textContent = message.replace(/^Error invoking remote method '[^']+': Error: /, ''); $('#error').hidden = false; }
async function request(command: Request) {
  const reply = await window.plowshare.request(command);
  update(reply.state);
  if (reply.notice) error(reply.notice);
  return reply;
}
function action(command: Request) { void request(command).catch(reason => error(reason.message ?? String(reason))); }
async function loadHistory(id = selected, before?: number) {
  if (!id || historyBusy.has(id)) return;
  historyBusy.add(id);
  try { await request({ action: 'history', conversation: id, ...(before === undefined ? {} : { before }) }); }
  catch (reason) { error(reason instanceof Error ? reason.message : String(reason)); }
  finally { historyBusy.delete(id); }
}
async function choose(id: string) {
  const row = state.conversations.find(row => row.id === id); if (!row) return;
  const previousScope = scope;
  if (selected) drafts[selected] = draft.value;
  selected = id; scope = homeKey(row.project); projectExpansion[scope] = true; filterExpansion[scope] = true; draft.dataset.conversation = selected; draft.value = drafts[selected] ?? '';
  signature = ''; render(); persist(); followSelection();
  if (state.mode === 'live' && state.connected && (scope !== previousScope || !state.agents[scope])) {
    try { await request({ action: 'scope', ...(scope ? { project: scope } : {}) }); }
    catch (reason) { error(reason instanceof Error ? reason.message : String(reason)); }
  }
  await loadHistory(id);
}
async function chooseScope(nextScope: string) {
  if (selected) drafts[selected] = draft.value;
  scope = nextScope; projectExpansion[scope] = true; filterExpansion[scope] = true; selected = ''; signature = '';
  update(state);
  try { await request({ action: 'scope', ...(scope ? { project: scope } : {}) }); await loadHistory(); }
  catch (reason) { error(reason instanceof Error ? reason.message : String(reason)); }
}
async function newConversation() {
  if (busy || (state.mode === 'live' && !state.connected)) return;
  busy = true;
  try {
    const reply = await request({ action: 'create', ...(scope ? { project: scope } : {}) });
    if (reply.conversation) await choose(reply.conversation);
    draft.focus();
  } catch (reason) { error(reason instanceof Error ? reason.message : String(reason)); }
  finally { busy = false; render(); }
}
function openConnect() {
  $<HTMLInputElement>('#server-url').value = state.base;
  $<HTMLInputElement>('#handle').value = state.handle;
  $<HTMLInputElement>('#password').value = '';
  $('#connect-error').hidden = true;
  $('#disconnect').hidden = state.mode !== 'live' || !state.connected;
  if (!dialog.open) dialog.showModal();
}

function render() {
  if (!state) return;
  renderFiles();
  const filter = $<HTMLInputElement>('#conversation-filter').value.toLowerCase();
  const rows = state.conversations.filter(row => homeKey(row.project) === scope);
  $('#conversation-count').textContent = String(rows.length);
  const conversationRows = (project: string) => state.conversations.filter(row => homeKey(row.project) === project && title(row.id).toLowerCase().includes(filter)).map(row => {
    const job = state.jobs.find(job => job.conversation === row.id && activeJob(job));
    return `<button class="conversation-row" data-conversation="${esc(row.id)}" ${row.id === selected ? 'aria-current="page"' : ''}><span class="conversation-icon ${job ? 'is-active' : ''}">${icon(job ? 'activity' : 'message')}</span><div><span class="row-title">${esc(title(row.id))}</span>${job ? `<small>${esc(job.status)}</small>` : ''}</div></button>`;
  }).join('');
  $('#conversations').innerHTML = ['', ...state.projects.map(row => row.name)].map((project, index) => {
    const folder = state.projectFolders?.find(row => row.name === project);
    const recorded = state.projects.find(row => row.name === project);
    const path = recorded?.workspace ?? folder?.path;
    const machine = recorded?.workspace ? recorded.machine ?? 'Server' : folder?.machine;
    const location = path ? `${machine ?? 'Server'} · ${path}` : '';
    const expanded = filter ? filterExpansion[project] ?? true : projectExpansion[project] ?? scope === project;
    const items = expanded ? conversationRows(project) : '';
    return `<section class="project-group"><div class="project-heading" ${scope === project ? 'data-current="true"' : ''}><button class="project-toggle icon-button" data-project-toggle="${esc(project)}" aria-expanded="${expanded}" aria-controls="project-conversations-${index}" aria-label="${expanded ? 'Collapse' : 'Expand'} ${esc(project || 'Global workspace')}">${icon('chevron')}</button><button class="project-row" data-project="${esc(project)}" ${scope === project ? 'aria-current="true"' : ''} title="${esc([location, folder?.error].filter(Boolean).join(' · ') || 'Open project')}"><span class="project-name">${esc(project || 'Global workspace')}${location ? `<small class="project-location">${esc(location)}</small>` : ''}</span>${folder ? `<small class="project-presence ${folder.files.status}" aria-label="${esc(folder.error ? 'Files unavailable' : folder.files.status)}">${icon(folder.files.status === 'ready' ? 'check' : folder.error || folder.files.status === 'lost' ? 'alert' : 'folder')}</small>` : ''}</button></div><div id="project-conversations-${index}" class="project-conversations" ${expanded ? '' : 'hidden'}>${items || '<p class="no-running">No conversations loaded.</p>'}</div></section>`;
  }).join('');
  const jobs = state.jobs.filter(activeJob);
  $('#running-count').textContent = String(jobs.length);
  $('#inbox-count').textContent = state.activity.inbox.unread === undefined ? '—' : String(state.activity.inbox.unread);
  $('#inbox-open').title = state.activity.inbox.error || 'Open account inbox';
  $('#runs-count').textContent = state.activity.runs.loaded ? String(state.activity.runs.items.filter(run => !run.parent && ['running', 'asking', 'waiting'].includes(run.state)).length) : '—';
  $('#runs-open').title = state.activity.runs.error || 'Open account runs';
  $('#running-jobs').innerHTML = jobs.map(job => `<button class="running-row" ${job.source==='information'?'data-information-job':`data-conversation="${esc(job.conversation)}"`}>${icon('activity')}<span>${esc(job.source==='information'?job.task:title(job.conversation))}</span></button>`).join('') || '<div class="no-running">No active runs.</div>';
  $('#scope-caption').textContent = scope || 'Global workspace';
  $('#title-caption').textContent = selected ? title(selected) : 'New conversation';
  $('#chat-title').textContent = selected ? title(selected) : 'An open field';
  $('#connection-label').textContent = state.connection;
  $('#footer-status').textContent = state.mode === 'demo' ? 'Offline demo · local data' : `${state.connection} · ${state.base}`;
  $('#chat-footer-mode').textContent = state.mode === 'demo' ? 'Local demo · no model calls' : 'Plowshare server · WebSocket';
  $('#notice').hidden = state.mode === 'live' && state.connected;
  if (state.mode === 'live') {
    $('#notice .demo-mark').textContent = 'OFFLINE';
    $('#notice>span:nth-child(2)').textContent = 'Connection unavailable. Server jobs may continue; reconnect before sending.';
  } else {
    $('#notice .demo-mark').textContent = 'DEMO';
    $('#notice>span:nth-child(2)').textContent = 'Sample workspace · local replies';
  }
  $('#connect-sidebar .button-label').textContent = state.mode === 'live' ? 'Connection settings' : 'Connect server';
  const roster = state.agents[scope] ?? [];
  const bots = roster.filter(row => row.served && row.bot);
  const preferred = roster.find(row => row.preferred);
  const previousAgent = chosenAgents[selected] ?? (preferred ? preferred.served && preferred.bot ? preferred.name : '' : bots[0]?.name ?? '');
  const unmet = preferred && (!preferred.served || !preferred.bot) && !chosenAgents[selected];
  agentSelect.innerHTML = (unmet ? `<option value="">Default ${esc(preferred.name)} unavailable · choose an agent</option>` : '') + bots.map(row => `<option value="${esc(row.name)}">${esc(row.name)}${row.model ? ` · ${esc(row.model)}` : ''}</option>`).join('') || '<option value="">No conversational agent</option>';
  agentSelect.value = bots.some(row => row.name === previousAgent) ? previousAgent : '';
  draft.placeholder = agentSelect.value ? `Message ${agentSelect.value}…` : 'Choose an agent to continue…';
  const job = selectedJob(); const active = job !== undefined && activeJob(job);
  const available = state.mode === 'demo' || state.connected;
  const preparing = state.projectPreparing?.includes(scope) === true;
  $<HTMLButtonElement>('#send').disabled = !available || preparing || active || busy || !draft.value.trim() || !agentSelect.value;
  $<HTMLButtonElement>('#new-conversation').disabled = !available || preparing || busy;
  $('#cancel').hidden = !active || job?.status === 'starting' || job?.id.startsWith('pending-') === true || !available;
  $<HTMLButtonElement>('#cancel').disabled = job?.status === 'cancelling';
  const statusText = active ? job.status === 'unknown' ? 'Outcome uncertain' : job.status === 'cancelling' ? 'Stopping…' : job.phase === 'thinking' ? 'Reasoning…' : job.phase === 'answer' ? 'Responding…' : job.phase === 'tool' ? `Using ${job.tool || 'a tool'}…` : 'Working…' : !available ? 'Reconnect to send' : preparing ? 'Connecting project files…' : 'Ready';
  if ($('#composer-status-text').textContent !== statusText) $('#composer-status-text').textContent = statusText;
  // Keep the indicator mounted: streamed deltas must not restart its CSS animation.
  const indicator = $('#run-indicator');
  indicator.hidden = !available || !job || !['starting', 'running'].includes(job.status);
  indicator.dataset.phase = job?.phase ?? 'working';
  $<HTMLButtonElement>('#trajectory-open').disabled = !selected;
  renderTranscript(); renderInspector(); renderApprovals(); renderStatus(); measureContext();
  fitDraft();
  navigation?.refresh();
}
function fitDraft() {
  // Bound growth so long drafts cannot push the composer out of a compact window.
  const maximum = Math.min(160, Math.floor(window.innerHeight * .2));
  draft.style.height = '0px';
  const height = Math.max(44, Math.min(maximum, draft.scrollHeight));
  draft.style.height = `${height}px`;
  draft.style.overflowY = draft.scrollHeight > height ? 'auto' : 'hidden';
}
function reflectLatest() { $('#jump-latest').hidden = transcript.scrollHeight - transcript.clientHeight - transcript.scrollTop < 160; }
transcript.addEventListener('scroll', reflectLatest, { passive: true });
$('#jump-latest').addEventListener('click', () => { transcript.scrollTop = transcript.scrollHeight; reflectLatest(); });
function measureContext(force = false) {
  const agent = agentSelect.value;
  if (!selected || !agent || state.mode !== 'live' || !state.connected) return;
  const key = `${identity}:${contextKey(selected, agent)}`;
  const previous = measurements.get(key);
  if (previous?.pending || (!force && previous && Date.now() - previous.at < (selectedJob() && activeJob(selectedJob()!) ? 15000 : 60000))) return;
  const reading = { at: Date.now(), pending: true };
  measurements.set(key, reading);
  void request({ action: 'context', conversation: selected, agent }).catch(() => {})
    .finally(() => { if (measurements.get(key) === reading) reading.pending = false; });
}
const compact = (n: number) => new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 }).format(n).toLowerCase();
function renderStatus() {
  const reading = state.contexts[contextKey(selected, agentSelect.value)];
  const agent = (state.agents[scope] ?? []).find(row => row.name === agentSelect.value);
  const job = selectedJob();
  const sent = reading?.sent; const limit = reading?.limit;
  const measured = sent !== undefined && Number.isFinite(sent) && sent >= 0;
  const sized = limit !== undefined && Number.isFinite(limit) && limit > 0;
  const percent = measured && sized ? Math.floor(sent / limit * 100) : undefined;
  const stale = reading?.status === 'unavailable' || (state.mode === 'live' && !state.connected);
  const sample = reading?.sample ? 'Sample · ' : '';
  $('#context-model').textContent = reading?.model || agent?.model || '';
  $('#context-model').title = reading?.model || agent?.model || '';
  $('#context-model').parentElement!.hidden = !$('#context-model').textContent;
  $('#context-label').textContent = `${sample}${stale && measured ? 'Last measured · ' : ''}${measured ? `${compact(sent)}${sized ? ` / ${compact(limit)}` : ' tokens'}${percent === undefined ? '' : ` · ${percent}%`}` : `Context not measured${sized ? ` · max ${compact(limit)}` : ''}`}`;
  $('#context-status').title = reading?.detail || 'Model-reported tokens in the newest measured prompt. Your unsent draft is not included.';
  const meter = $<HTMLProgressElement>('#context-meter'); meter.hidden = percent === undefined;
  meter.value = Math.min(percent ?? 0, 100);
  meter.setAttribute('aria-label', `${sample}${stale ? 'Last measured context' : 'Context'} ${percent ?? 'unknown'} percent`);
  $('#context-status').dataset.level = percent !== undefined && percent >= 85 ? 'high' : 'normal';
  $('#context-status').dataset.stale = String(stale);
  const allowance = job?.allowance;
  $('#call-budget').hidden = !allowance;
  $('#call-budget').textContent = allowance ? `Calls ${allowance.modelCallsSpent}${allowance.maxModelCalls === undefined ? allowance.noBudget ? ' · no ceiling' : '' : ` / ${allowance.maxModelCalls}`}` : '';
  $('#call-budget').title = 'Model calls spent across the delegation tree; separate from context tokens.';
  const measuredRun = state.jobs.filter(row => row.conversation === selected && row.pace !== undefined).at(-1);
  const pace = $('#run-pace'); pace.hidden = !measuredRun;
  pace.title = measuredRun ? `Latest measured run by ${measuredRun.agent}. Measurements become available when the run finishes.` : '';
  const metrics = measuredRun?.pace;
  if (metrics) {
    const titles = {
      tools: 'Tool calls requested by this run’s model.',
      thinking: metrics.reasoningTokens === undefined ? 'Thinking tokens were not measured.' : metrics.reasoningEstimated ? 'Thinking tokens include a server estimate (~).' : 'Thinking tokens reported by the model.',
      responding: 'Output tokens generated across this run’s model calls. A dash means not measured.',
      waiting: 'Time to the first thinking or answer delta, including queueing and prompt processing. A dash means not measured.',
      speed: 'Generated tokens per second, as measured by the server. A dash means not measured.',
    };
    pace.innerHTML = `<span class="pace-label">Last measured run</span> ${describePace(metrics).map(part =>
      `<span class="pace-stat" data-pace="${part.kind}" title="${esc(titles[part.kind])}" ${part.kind === 'tools' ? `aria-label="${metrics.toolCalls} tool calls"` : ''}>${part.kind === 'tools' ? icon('gear') : ''}${esc(part.text)}</span>`).join(' ')}`;
  } else pace.innerHTML = '';
  const alerts: { label: string; text: string; kind: string }[] = [];
  if (state.connected && state.liveHistory?.status === 'unavailable') alerts.push({ label: 'Live updates unavailable', text: state.liveHistory.detail || 'Refresh manually to read later conversation turns.', kind: 'warning' });
  else if (state.connected && state.history[selected]?.error) alerts.push({ label: 'History catch-up unavailable', text: state.history[selected].error!, kind: 'warning' });
  if (percent !== undefined && percent >= 85 && !stale) alerts.push({ label: 'Context near limit', text: 'This conversation is nearing the selected model’s context limit.', kind: 'warning' });
  if (!agentSelect.value) alerts.push({ label: 'Agent unavailable', text: 'Choose a served conversational agent before sending.', kind: 'warning' });
  if (job?.status === 'unknown') alerts.push({ label: 'Outcome uncertain', text: job.detail || 'The job may still be running on the server. Reconnect to check its state.', kind: 'warning' });
  else if (job?.status === 'interrupted') alerts.push({ label: 'Request interrupted', text: job.detail || 'Inspect the trajectory before retrying.', kind: 'warning' });
  else if (job && stoppedJob(job)) alerts.push({ label: `Run stopped · ${job.ending?.toLowerCase().replaceAll('_', ' ') ?? 'no answer'}`, text: [job.text, job.detail].filter(Boolean).join(' — ') || 'The run ended without a completed answer.', kind: 'warning' });
  else if (job?.status === 'finished' && job.detail) alerts.push({ label: 'Answer note', text: job.detail, kind: 'note' });
  const panel = $('#conversation-alerts'); panel.hidden = !alerts.length;
  panel.innerHTML = alerts.map(row => `<div class="conversation-alert ${row.kind}">${icon(row.kind === 'warning' ? 'alert' : 'file')}<strong>${esc(row.label)}</strong><span>${esc(row.text)}</span></div>`).join('');
}
function renderTranscript() {
  const history = state.history[selected];
  const job = selectedJob();
  // Durable arrivals after this job's watermark wait until its stream is settled.
  // Ordinals identify deliveries; equal text in a later harness turn is still a new turn.
  const entries = (history?.entries ?? []).filter(entry => !job || !activeJob(job) || job.logAfter === undefined || entry.ordinal <= job.logAfter);
  const nextSignature = JSON.stringify([selected, entries, history?.more, job?.text, job?.status, job?.phase, job?.tool, job?.events.length, job?.detail, state.approvals, state.answeringApprovals, approvalControls.revision]);
  if (signature === nextSignature) return;
  signature = nextSignature;
  const nearBottom = transcript.scrollHeight - transcript.clientHeight - transcript.scrollTop < 100;
  const oldTop = transcript.scrollTop;
  const wasEmpty = !transcript.childElementCount;
  let content = history?.more ? `<button class="load-earlier" id="load-earlier">${icon('history')}Load earlier entries</button>` : '';
  content += entries.map(entry => {
    if (!entry.text || (entry.kind === 'answer' && (entry.asked ?? 0) > 0) || !['utterance', 'answer', 'summary'].includes(entry.kind)) return '';
    const kind = entry.kind === 'utterance' ? 'person' : entry.kind === 'summary' ? 'summary' : 'answer';
    const label = kind === 'person' ? entry.speaker === 'harness' ? `Harness · ${entry.speakerName ?? 'external'}` : 'You' : kind === 'summary' ? 'Conversation summary' : 'Plowshare';
    const approval = noticeApproval(state, { kind: 'approval', answer: entry.text });
    const ownApproval = approval && (approval.conversation === selected || approval.askedIn === selected);
    return message(kind, label, entry.text, entry.cut ? `Excerpt shown · ${entry.length ?? ''} characters in source` : '') + (ownApproval ? approvalControls.prompt(approval) : '');
  }).join('');
  if (job && activeJob(job)) {
    if (job.source !== 'approval') content += message('person', 'You', job.task);
    const waiting = job.status === 'unknown' ? 'The connection was lost. This job may still be running on the server.'
      : job.status === 'cancelling' ? 'Stopping…' : job.phase === 'thinking' ? 'Reasoning…'
      : job.phase === 'tool' ? `Using ${job.tool || 'a tool'}…` : 'Working on your request…';
    content += `<article class="message answer live" data-copy-source="${esc(job.text)}"><div class="message-label"><span class="answer-mark">${icon('sparkles')}</span><span>${esc(job.agent)} · ${esc(job.status)}</span>${job.text ? copyButton('Copy answer as Markdown', 'answer') : ''}</div><div class="message-body markdown-body">${markdownHtml(job.text || waiting)}${job.status === 'running' && job.phase === 'answer' ? '<span class="stream-caret"></span>' : ''}</div>${job.detail ? `<p class="history-note">${esc(job.detail)}</p>` : ''}</article>`;
  }
  if (!content) content = `<div class="empty-chat"><div class="empty-mark">${icon('sparkles')}</div><h2>What are we thinking about?</h2><p>Ask a question, explore your sources, or work through something together.</p><div class="suggestions"><button data-suggestion="Help me explore an idea and find useful sources.">${icon('search')}Explore an idea</button><button data-suggestion="Help me turn these notes into a clear plan.">${icon('file')}Make a plan</button><button data-suggestion="Help me understand a problem step by step.">${icon('route')}Work through a problem</button></div></div>`;
  transcript.innerHTML = content;
  transcript.classList.toggle('is-empty', !!transcript.querySelector('.empty-chat'));
  if (nearBottom || wasEmpty) transcript.scrollTop = transcript.scrollHeight;
  else transcript.scrollTop = oldTop;
  reflectLatest();
}
function message(kind: string, label: string, body: string, note = '') {
  return `<article class="message ${kind}" ${kind === 'answer' ? `data-copy-source="${esc(body)}"` : ''}><div class="message-label">${kind === 'answer' ? `<span class="answer-mark">${icon('sparkles')}</span>` : ''}<span>${esc(label)}</span>${kind === 'answer' ? copyButton('Copy answer as Markdown', 'answer') : ''}</div><div class="message-body ${kind === 'answer' ? 'markdown-body' : ''}">${kind === 'answer' ? markdownHtml(body) : esc(body)}</div>${note ? `<p class="history-note">${esc(note)}</p>` : ''}</article>`;
}
let usagePanel: UsagePanel | undefined;
let usageIdentity = '';
function renderInspector() {
  document.querySelectorAll<HTMLButtonElement>('[data-tab]').forEach(button => button.setAttribute('aria-selected', String(button.dataset.tab === tab)));
  $('#inspector-content').setAttribute('aria-labelledby', `tab-${tab}`);
  const conversation = current();
  const agent = (state.agents[scope] ?? []).find(row => row.name === agentSelect.value);
  const reading = state.contexts[contextKey(selected, agentSelect.value)];
  if (tab === 'usage') {
    const view = `${identity}:${scope}:${selected}:${agentSelect.value}`;
    if (!usagePanel || usageIdentity !== view) {
      usagePanel?.destroy(); usageIdentity = view;
      const root = $('#inspector-content'); root.dataset.stamp = ''; root.dataset.view = '';
      usagePanel = mountUsagePanel(root, {
        select: async (type, filter) => { await window.plowshare.request({action:'usage-open',type,filter}); },
        read: async (type, payload) => (await window.plowshare.request({action:'usage-read',type,payload})).usage,
        conversation: () => selected, agent: () => agentSelect.value,
        project: scope === 'Global' ? null : scope, storage: localStorage,
      });
    }
    if (state.usage) usagePanel.update(state.usage);
    return;
  }
  if (usagePanel) { usagePanel.destroy(); usagePanel = undefined; void window.plowshare.request({action:'usage-close'}); }
  let content = '';
  if (tab === 'context') {
    content = `<section class="context-section thread-context"><div class="eyebrow section-label">${icon('message')}Conversation</div><h3>${esc(selected ? title(selected) : 'New conversation')}</h3><span class="scope-badge">${icon('folder')}${esc(conversation?.project ?? (scope || 'Global'))}</span>${selected ? `<details class="context-details" data-context-details="identity"><summary>Conversation details</summary><div class="context-key"><span>ID</span><code>${esc(selected)}</code></div></details>` : ''}</section>`;
    const stale = reading?.status === 'unavailable' || (state.mode === 'live' && !state.connected);
    content += `<section class="context-section"><div class="section-heading"><span class="eyebrow section-label">${icon('layers')}Context window</span>${reading?.sample ? '<span class="context-tag">Sample</span>' : stale ? '<span class="context-tag">Last measured</span>' : ''}</div><div class="context-key"><span>Prompt tokens</span><strong>${reading?.sent === undefined ? 'Not measured' : esc(reading.sent.toLocaleString())}</strong></div><div class="context-key"><span>Model limit</span><strong>${reading?.limit === undefined ? 'Unavailable' : esc(reading.limit.toLocaleString())}</strong></div><p class="context-note">${esc(reading?.detail || 'Unsent drafts are not included.')}</p></section>`;
    content += `<section class="context-section"><div class="eyebrow section-label">${icon('sparkles')}Agent</div><h3>${esc(agent?.name ?? 'No agent selected')}</h3>${agent?.description ? `<p>${esc(agent.description)}</p>` : ''}${agent?.model ? `<div class="context-key"><span>Model</span><strong>${esc(agent.model)}</strong></div>` : ''}<details class="context-details" data-context-details="tools"><summary>${icon('tool')}Declared tools <span>${agent?.tools.length ?? 0}</span></summary><div class="tool-tags">${agent?.tools.map(tool => `<code>${esc(tool)}</code>`).join('') || '<p>No tools declared.</p>'}</div></details></section>`;
    if (state.mode === 'demo') content += `<section class="context-section"><div class="section-heading"><span class="eyebrow section-label">${icon('book')}Sources</span><span class="context-tag">Sample</span></div><div class="document-card"><h3><span class="doc-icon">${icon('file')}</span>Program overview</h3><p>Historical background</p></div><div class="document-card"><h3><span class="doc-icon">${icon('file')}</span>Research notes</h3><p>Questions and observations</p></div><p class="context-note">Design samples · no files connected.</p></section>`;
    else content += `<section class="context-section"><div class="eyebrow section-label">${icon('book')}Sources</div><p>Document browsing is not connected yet.</p></section>`;
  } else {
    content = `<section class="context-section"><div class="eyebrow section-label">${icon('brain')}Memory</div><h3>${state.mode === 'demo' ? 'Remembered context' : 'Memory is not connected yet'}</h3><p>${state.mode === 'demo' ? 'Sample knowledge for this workspace.' : 'Browsing and maintenance are planned.'}</p></section>`;
    if (state.mode === 'demo') content += '<section class="context-section"><div class="section-heading"><span class="eyebrow">Project lesson</span><span class="context-tag">Sample</span></div><h3>Keep questions connected to sources</h3><p>Useful conclusions should retain their provenance, and uncertainty should remain visible.</p></section>';
    content += `<details class="context-details" data-context-details="planned"><summary>Planned memory controls</summary><p>Read and recall · navigate provenance · digest and curate · review proposals. Conversation search and memory navigation remain separate actions.</p></details>`;
  }
  const element = $('#inspector-content');
  // Preserve disclosures during context refreshes; token streaming does not rebuild this pane.
  const view = `${identity}:${selected}:${tab}`;
  if (element.dataset.stamp !== content || element.dataset.view !== view) {
    const sameView = element.dataset.view === view;
    const open = sameView ? new Set([...element.querySelectorAll<HTMLDetailsElement>('details[open]')].map(row => row.dataset.contextDetails)) : new Set();
    element.innerHTML = content; element.dataset.stamp = content; element.dataset.view = view;
    element.querySelectorAll<HTMLDetailsElement>('details').forEach(row => { row.open = open.has(row.dataset.contextDetails); });
  }
}
function renderApprovals() {
  const approvals = state.approvals.filter(row => row.state === 'asked');
  const bar = $('#approval-bar'); bar.hidden = !approvals.length;
  bar.innerHTML = approvals.map(row => approvalControls.prompt(row, row.conversation === selected ? '' : title(row.conversation))).join('');
}

$('#board-open').addEventListener('click', () => action({ action: 'board-inspection', view: 'board', ...(scope ? { project: scope } : {}) }));
$('#swarm-open').addEventListener('click', () => action({ action: 'board-inspection', view: 'swarm', ...(scope ? { project: scope } : {}) }));
$('#library-open').addEventListener('click', () => action({action:'library',view:'documents',project:scope || null}));
$('#inbox-open').addEventListener('click', () => action({ action: 'activity', view: 'inbox' }));
$('#runs-open').addEventListener('click', () => action({ action: 'activity', view: 'runs' }));
$('#trajectory-open').addEventListener('click', () => { if (selected) action({ action: 'trajectory', conversation: selected }); });
document.addEventListener('click', event => {
  const link = (event.target as HTMLElement).closest<HTMLElement>('[data-web-link]');
  if (link?.dataset.webLink) { event.preventDefault(); action({ action: 'open-link', url: link.dataset.webLink }); }
});
$('#connection-button').addEventListener('click', openConnect);
$('#connect-sidebar').addEventListener('click', openConnect);
$('#notice-connect').addEventListener('click', openConnect);
$('#close-dialog').addEventListener('click', () => dialog.close());
$('#dismiss-error').addEventListener('click', () => { $('#error').hidden = true; });
$('#new-conversation').addEventListener('click', () => void newConversation());
$('#conversations').addEventListener('click', event => {
  const toggle = (event.target as HTMLElement).closest<HTMLElement>('[data-project-toggle]');
  if (toggle) {
    const project = toggle.dataset.projectToggle!;
    const expansion = $<HTMLInputElement>('#conversation-filter').value ? filterExpansion : projectExpansion;
    expansion[project] = toggle.getAttribute('aria-expanded') !== 'true';
    render(); persist(); return;
  }
  const project = (event.target as HTMLElement).closest<HTMLElement>('[data-project]')?.dataset.project;
  if (project !== undefined) { void chooseScope(project); return; }
  const id = (event.target as HTMLElement).closest<HTMLElement>('[data-conversation]')?.dataset.conversation;
  if (id) void choose(id);
});
$('#running-jobs').addEventListener('click', event => { if((event.target as HTMLElement).closest('[data-information-job]')){information.open();return;}const id = (event.target as HTMLElement).closest<HTMLElement>('[data-conversation]')?.dataset.conversation; if (id) void choose(id); });
$('#conversation-filter').addEventListener('input', () => { filterExpansion = {}; render(); });
agentSelect.addEventListener('change', () => { chosenAgents[selected] = agentSelect.value; render(); });
draft.addEventListener('input', () => { drafts[selected] = draft.value; persist(); render(); });
draft.addEventListener('keydown', event => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); $<HTMLFormElement>('#composer').requestSubmit(); } });
$('#composer').addEventListener('submit', async event => {
  event.preventDefault(); if ($<HTMLButtonElement>('#send').disabled || busy) return;
  const message = draft.value.trim(); const agent = agentSelect.value; if (!message) return;
  if (!selected) await newConversation();
  if (!selected) return;
  const conversation = selected;
  busy = true; drafts[conversation] = ''; draft.value = ''; persist(); render();
  try { await request({ action: 'run', conversation, agent, text: message }); }
  catch (reason) { drafts[conversation] = message; if (selected === conversation) draft.value = message; error(reason instanceof Error ? reason.message : String(reason)); }
  finally { busy = false; persist(); render(); }
});
$('#cancel').addEventListener('click', () => { const job = selectedJob(); if (job) action({ action: 'cancel', job: job.id }); });
transcript.addEventListener('click', event => {
  const button = (event.target as HTMLElement).closest<HTMLElement>('button'); if (!button) return;
  if (button.dataset.suggestion) { draft.value = button.dataset.suggestion; drafts[selected] = draft.value; persist(); render(); draft.focus(); }
  if (button.id === 'load-earlier') void loadHistory(selected, state.history[selected]?.oldest);
});
$('#refresh').addEventListener('click', async () => {
  try { await request({ action: 'scope', ...(scope ? { project: scope } : {}) }); await request({ action: 'refresh' }); await loadHistory(); }
  catch (reason) { error(reason instanceof Error ? reason.message : String(reason)); }
  measureContext(true);
});
document.querySelectorAll<HTMLElement>('[data-tab]').forEach(button => {
  button.addEventListener('click', () => { tab = button.dataset.tab as typeof tab; renderInspector(); });
  button.addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
    const tabs = [...document.querySelectorAll<HTMLButtonElement>('[data-tab]')];
    const next = tabs[(tabs.indexOf(button as HTMLButtonElement) + (event.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
    next.click(); next.focus();
  });
});
function move() { chat.style.transform = docked || expanded ? 'none' : `translate(${offset.x}px, ${offset.y}px)`; }
$('#dock').addEventListener('click', () => {
  docked = !docked; chat.classList.toggle('docked', docked); chat.classList.toggle('floating', !docked);
  $('#dock').setAttribute('aria-label', docked ? 'Float chat' : 'Dock chat'); $('#dock').title = docked ? 'Float chat' : 'Dock chat'; move();
  $('#dock').innerHTML = icon(docked ? 'float' : 'dock'); $('#dock').setAttribute('aria-pressed', String(docked));
});
$('#expand').addEventListener('click', () => {
  expanded = !expanded; stage.classList.toggle('expanded', expanded);
  $('#expand').setAttribute('aria-label', expanded ? 'Restore chat size' : 'Expand chat');
  $('#expand').title = expanded ? 'Restore chat size' : 'Expand chat';
  $('#expand').innerHTML = icon(expanded ? 'minimize' : 'maximize'); $('#expand').setAttribute('aria-pressed', String(expanded));
  reflectInspector(); move();
});
$('#inspector-toggle').addEventListener('click', () => {
  showInspector(getComputedStyle($('#inspector')).display === 'none');
});
function showInspector(show: boolean) {
  if (show && expanded) $('#expand').click();
  inspectorHidden = !show;
  stage.classList.toggle('inspector-hidden', !show);
  stage.classList.toggle('show-inspector', show);
  reflectInspector();
  if (!show) $('#inspector-toggle').focus();
  else if (window.innerWidth <= 1010) $('#inspector-close').focus();
}
function reflectInspector() {
  const shown = getComputedStyle($('#inspector')).display !== 'none';
  $('#inspector-toggle').setAttribute('aria-expanded', String(shown));
  $('#context-scrim').hidden = !shown || window.innerWidth > 1010;
}
$('#inspector-close').addEventListener('click', () => showInspector(false));
$('#context-scrim').addEventListener('click', () => showInspector(false));
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && !dialog.open && !navigation?.isOpen() && window.innerWidth <= 1010 && $('#inspector-toggle').getAttribute('aria-expanded') === 'true') { event.preventDefault(); showInspector(false); }
});
window.addEventListener('resize', () => { reflectInspector(); fitDraft(); reflectLatest(); });
new ResizeObserver(reflectLatest).observe(transcript);
reflectInspector();
const grip = $('#chat-grip');
let drag: { x: number; y: number; left: number; top: number } | undefined;
const clamp = (value: number) => Math.max(-12, Math.min(12, value));
grip.addEventListener('pointerdown', event => { if (docked || expanded) return; drag = { x: event.clientX, y: event.clientY, left: offset.x, top: offset.y }; grip.setPointerCapture(event.pointerId); });
grip.addEventListener('pointermove', event => { if (!drag) return; offset = { x: clamp(drag.left + event.clientX - drag.x), y: clamp(drag.top + event.clientY - drag.y) }; move(); });
grip.addEventListener('pointerup', () => { drag = undefined; });
grip.addEventListener('pointercancel', () => { drag = undefined; });
grip.addEventListener('keydown', event => {
  if (docked || expanded || !event.key.startsWith('Arrow')) return;
  event.preventDefault(); offset.x = clamp(offset.x + (event.key === 'ArrowRight' ? 4 : event.key === 'ArrowLeft' ? -4 : 0)); offset.y = clamp(offset.y + (event.key === 'ArrowDown' ? 4 : event.key === 'ArrowUp' ? -4 : 0)); move();
});
function setSidebar(hidden: boolean) {
  sidebarHidden = hidden;
  $('.workspace').classList.toggle('sidebar-hidden', hidden); $('#sidebar').hidden = hidden;
  $('#sidebar-toggle').setAttribute('aria-expanded', String(!hidden));
  $('#sidebar-toggle').setAttribute('aria-label', hidden ? 'Show sidebar' : 'Hide sidebar');
  $('#sidebar-toggle').title = `${hidden ? 'Show' : 'Hide'} sidebar · ⌘/Ctrl B`;
  try { localStorage.setItem('plowshare.desktop.layout.v1', JSON.stringify({ sidebarHidden: hidden })); } catch { /* The view still changes when profile storage is unavailable. */ }
  reflectInspector(); fitDraft(); reflectLatest();
}
try { sidebarHidden = JSON.parse(localStorage.getItem('plowshare.desktop.layout.v1') ?? '{}').sidebarHidden === true; } catch { /* Use the workspace layout. */ }
setSidebar(sidebarHidden);
$('#information-button').addEventListener('click',()=>{try{information.open();}catch(reason){error(reason instanceof Error?reason.message:String(reason));}});
$('#sidebar-toggle').addEventListener('click', () => setSidebar(!sidebarHidden));
document.addEventListener('keydown', event => {
  if (!(event.metaKey || event.ctrlKey) || event.altKey || event.isComposing || document.querySelector('dialog[open]') || navigation?.isOpen()) return;
  if (event.key.toLowerCase() === 'n') { event.preventDefault(); void newConversation(); }
  if (event.key.toLowerCase() === 'b') {
    event.preventDefault();
    if ($('#sidebar').contains(document.activeElement)) $('#sidebar-toggle').focus();
    setSidebar(!sidebarHidden);
  }
});
navigation = installNavigation((): NavigationItem[] => {
  if (!state) return [];
  const available = state.mode === 'demo' || state.connected;
  return [
    ...[...state.conversations].sort((a, b) => Number(b.id === selected) - Number(a.id === selected)).map(row => ({
      id: `conversation:${row.id}`, title: title(row.id), detail: `${row.project || 'Global workspace'}${row.id === selected ? ' · Current' : ''}`,
      group: 'Conversations' as const, icon: 'message' as const, run: async () => { await choose(row.id); draft.focus(); },
    })),
    ...['', ...state.projects.map(row => row.name)].map(name => ({
      id: `workspace:${name}`, title: name || 'Global workspace', detail: name === scope ? 'Current workspace' : 'Switch workspace',
      group: 'Workspaces' as const, icon: 'folder' as const, disabled: !available,
      run: async () => { await chooseScope(name); draft.focus(); },
    })),
    { id: 'new', title: 'New conversation', detail: scope || 'Global workspace', group: 'Actions', icon: 'plus', shortcut: '⌘/Ctrl N', disabled: !available || busy, run: newConversation },
    { id: 'compose', title: 'Focus message', detail: 'Return to your draft', group: 'Actions', icon: 'message', run: () => draft.focus() },
    { id: 'board', title: 'Open board', detail: 'Inspect topics, messages and decisions', group: 'Actions', icon: 'layers', run: () => action({ action: 'board-inspection', view: 'board', ...(scope ? { project: scope } : {}) }) },
    { id: 'swarm', title: 'Open swarm', detail: 'Inspect active seats and the model queue', group: 'Actions', icon: 'activity', run: () => action({ action: 'board-inspection', view: 'swarm', ...(scope ? { project: scope } : {}) }) },
    { id: 'trajectory', title: 'Open trajectory', detail: 'Inspect this conversation in its own window', group: 'Actions', icon: 'route', disabled: !selected, run: () => action({ action: 'trajectory', conversation: selected }) },
    { id: 'context', title: 'Toggle context panel', detail: 'Model usage, agent and sources', group: 'Actions', icon: 'panel', run: () => $('#inspector-toggle').click() },
    { id: 'sidebar', title: sidebarHidden ? 'Show sidebar' : 'Hide sidebar', detail: 'Conversation navigation', group: 'Actions', icon: 'sidebar', shortcut: '⌘/Ctrl B', run: () => setSidebar(!sidebarHidden) },
    { id: 'files', title: 'Project files', detail: 'Connect or disconnect a local folder', group: 'Actions', icon: 'folder', run: () => $('#files-open').click() },
    { id: 'connection', title: 'Connection settings', detail: state.mode === 'demo' ? 'Connect a Plowshare server' : state.base, group: 'Actions', icon: 'server', run: openConnect },
  ];
}, error);
$('#connection-form').addEventListener('submit', async event => {
  event.preventDefault();
  const button = $<HTMLButtonElement>('#submit-connection'); button.disabled = true; button.innerHTML = `${icon('server')}Connecting…`;
  $('#connect-error').hidden = true;
  const password = $<HTMLInputElement>('#password').value; $<HTMLInputElement>('#password').value = '';
  try {
    await request({ action: 'connect', base: $<HTMLInputElement>('#server-url').value, handle: $<HTMLInputElement>('#handle').value, password });
    dialog.close();
    await request({ action: 'scope', ...(scope ? { project: scope } : {}) }); await loadHistory();
  } catch (reason) { $('#connect-error').textContent = reason instanceof Error ? reason.message : String(reason); $('#connect-error').hidden = false; }
  finally { button.disabled = false; button.innerHTML = `${icon('server')}Connect`; }
});
$('#use-demo').addEventListener('click', async () => { await request({ action: 'demo' }); dialog.close(); });
$('#disconnect').addEventListener('click', async () => { await request({ action: 'disconnect' }); dialog.close(); });
window.plowshare.subscribe(update);
void request({ action: 'bootstrap' }).then(async () => {
  if (state.mode === 'live' && state.connected) {
    await request({ action: 'scope', ...(scope ? { project: scope } : {}) });
    await loadHistory();
  }
}).catch(reason => error(reason.message));

const filesDialog = $<HTMLDialogElement>('#files-dialog');
const renderSync = installSync(request);
let filePicking = false;
let addingProject = false;
let fileAccessError = '';
let fileAccessScope = '';
const deniedFileAccess = new Set<string>();
const fileAccessKey = () => `${identity}:${scope}`;
function renderFiles() {
  renderSync(state, scope, addingProject);
  const folder = state.projectFolders?.find(row => row.name === scope);
  const recorded = state.projects.find(row => row.name === scope);
  const recordedLocation = recorded?.workspace ? `${recorded.machine ?? 'Server'} · ${recorded.workspace}` : '';
  const localLocation = folder ? `${folder.machine} · ${folder.path}` : '';
  const recoverable = !!recorded?.workspace && !!state.localMachine && recorded.machine === state.localMachine;
  const files = folder?.files ?? (state.files.project === scope ? state.files : { status: 'off' as const });
  const ready = files.status === 'ready';
  if (fileAccessScope !== scope) { fileAccessScope = scope; fileAccessError = ''; }
  const prompt = $('#file-access-prompt');
  const denied = deniedFileAccess.has(fileAccessKey());
  prompt.hidden = state.mode !== 'live' || !state.connected || ready || denied;
  const connecting = files.status === 'opening' || state.projectPreparing?.includes(scope);
  $('#file-access-title').textContent = connecting ? 'Connecting file access…' : files.status === 'lost' ? 'Restore file access' : 'Allow file access';
  $('#file-access-description').textContent = folder || recoverable
    ? `Allow agents to read and change files in ${folder?.path ?? recorded!.workspace}. Access is remembered on this computer; disconnect it in Project files.`
    : `Choose a local folder${scope ? ` for ${scope}` : ' to add a project'}. Agents can read and change files inside it. Access is remembered on this computer.`;
  $('#file-access-error').textContent = fileAccessError || folder?.error || files.detail || '';
  $('#file-access-error').hidden = !$('#file-access-error').textContent;
  $('#file-access-allow').textContent = connecting ? 'Connecting…' : 'Approve';
  $<HTMLButtonElement>('#file-access-allow').disabled = filePicking || !!connecting;
  $<HTMLButtonElement>('#file-access-deny').disabled = filePicking || !!connecting;
  $('#files-label').textContent = files.status === 'opening' ? 'Connecting files…' : ready ? files.project === scope ? 'Files connected' : `Files: ${files.project}` : denied ? 'File access denied' : files.status === 'lost' ? 'Files disconnected' : 'Connect files';
  $('#files-open').title = files.root ? `${files.project} · ${files.root}${files.detail ? ` · ${files.detail}` : ''}` : 'Connect a local project folder';
  $('#files-open').dataset.status = files.status;
  $('#files-title').textContent = addingProject ? 'Add project' : 'Project file access';
  $('#files-description').textContent = addingProject ? 'Choose a folder. Its Plowshare marker or folder name identifies the project on the server.' : ready ? `Serving ${files.project}${files.project !== scope ? ' · another workspace' : ''}` : files.status === 'opening' ? `Connecting ${files.project}…` : files.status === 'lost' ? `File access lost for ${files.project}. Choose the folder again.` : `Choose a folder for ${scope || 'a new project'}.`;
  $('#files-root').textContent = addingProject ? '' : [recordedLocation, localLocation && localLocation !== recordedLocation ? `Saved folder: ${localLocation}` : ''].filter(Boolean).join('\n') || files.root || '';
  $('#files-detail').textContent = state.projectConfigError ?? state.projectListError ?? (addingProject ? '' : folder?.error ?? files.detail ?? '');
  $('#files-withdraw').hidden = addingProject || !ready;
  $('#files-reopen').hidden = addingProject || (!folder && !recoverable) || ready;
  $('#files-reopen').innerHTML = `${icon('refresh')}${folder ? 'Reconnect files' : 'Connect recorded folder'}`;
  $('#files-forget').hidden = addingProject || !folder;
  $<HTMLButtonElement>('#files-reopen').disabled = filePicking || !state.connected;
  $<HTMLButtonElement>('#files-forget').disabled = filePicking || !state.connected;
  $<HTMLButtonElement>('#files-withdraw').disabled = filePicking;
  $<HTMLButtonElement>('#files-choose').disabled = filePicking || files.status === 'opening' || !state.connected;
}
$('#project-add').addEventListener('click', () => { addingProject = true; $('#files-error').hidden = true; renderFiles(); filesDialog.showModal(); });
$('#files-open').addEventListener('click', () => { deniedFileAccess.delete(fileAccessKey()); addingProject = false; $('#files-error').hidden = true; renderFiles(); filesDialog.showModal(); });
$('#files-close').addEventListener('click', () => filesDialog.close());
$('#file-access-allow').addEventListener('click', async () => {
  const project = scope;
  const saved = state.projectFolders?.some(row => row.name === project);
  const recorded = state.projects.find(row => row.name === project);
  const recoverable = !!recorded?.workspace && !!state.localMachine && recorded.machine === state.localMachine;
  filePicking = true; fileAccessError = ''; renderFiles();
  try {
    const reply = await request(saved || recoverable ? { action: 'project-open', project } : { action: 'files-choose', ...(project ? { project } : {}) });
    if (reply.rootProject) await chooseScope(reply.rootProject);
  } catch (reason) {
    if (scope === project) fileAccessError = reason instanceof Error ? reason.message : String(reason);
    else error(reason instanceof Error ? reason.message : String(reason));
  } finally { filePicking = false; renderFiles(); }
});
$('#file-access-deny').addEventListener('click', async () => {
  const project = scope;
  const key = fileAccessKey();
  filePicking = true; fileAccessError = ''; renderFiles();
  try {
    if (state.projectFolders?.some(row => row.name === project)) await request({ action: 'files-withdraw', project });
    deniedFileAccess.add(key);
  } catch (reason) {
    if (scope === project) fileAccessError = reason instanceof Error ? reason.message : String(reason);
    else error(reason instanceof Error ? reason.message : String(reason));
  } finally { filePicking = false; renderFiles(); }
});
$('#files-choose').addEventListener('click', async () => {
  filePicking = true; $('#files-error').hidden = true; renderFiles();
  try {
    const reply = await request({ action: 'files-choose', ...(!addingProject && scope ? { project: scope } : {}) });
    if (reply.rootProject) { await chooseScope(reply.rootProject); filesDialog.close(); }
  } catch (error) { $('#files-error').textContent = String(error); $('#files-error').hidden = false; }
  finally { filePicking = false; renderFiles(); }
});
$('#files-withdraw').addEventListener('click', async () => {
  filePicking = true; $('#files-error').hidden = true; renderFiles();
  try { await request({ action: 'files-withdraw', ...(scope ? { project: scope } : {}) }); }
  catch (error) { $('#files-error').textContent = String(error); $('#files-error').hidden = false; }
  finally { filePicking = false; renderFiles(); }
});

for (const [selector, actionName] of [['#files-reopen', 'project-open'], ['#files-forget', 'project-remove']] as const) {
  $(selector).addEventListener('click', async () => {
    filePicking = true; $('#files-error').hidden = true; renderFiles();
    try { await request({ action: actionName, project: scope }); }
    catch (error) { $('#files-error').textContent = String(error); $('#files-error').hidden = false; }
    finally { filePicking = false; renderFiles(); }
  });
}

const operatorControls = installOperator(window.plowshare, () => state, () => scope);
$('#controls-button').addEventListener('click', () => { try { operatorControls.open(); } catch (reason) { error(reason instanceof Error ? reason.message : String(reason)); } });
