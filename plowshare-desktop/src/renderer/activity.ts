import { builderPanel, builderForm } from './authoring.ts';
import { scheduledPanel, rememberSchedule, scheduledAction } from './schedule-controls.ts';
import { questionControls, rememberQuestion, questionAction, recordControls } from './run-controls.ts';
import type { ActivityView, DesktopState, Request } from '../shared.ts';
import { icon, mountIcons } from './icons.ts';
import { escapeHtml as esc, markdownHtml } from './markdown.ts';
import { copyButton, installCopyControls } from './copy.ts';
import { installApprovalControls, noticeApproval } from './approvals.ts';

mountIcons(); installCopyControls();
const $ = <T extends HTMLElement = HTMLElement>(selector: string): T => document.querySelector(selector)!;
let state: DesktopState;
const approvalControls = installApprovalControls(document, () => state, async command => {
  const reply = await window.plowshare.request(command); update(reply.state); return reply;
}, render);
let view: ActivityView = 'inbox';
let inboxId = '', runId = '', requestedRun = '', detailStamp = '', localError = '';
let launchConversation = '', pendingLaunch = false, outputsStamp = '';
let marking = false, typeStamp = '', definitionId = '', projectStamp = '', scheduledId = '';
const tabs: ActivityView[] = ['inbox', 'runs', 'definitions', 'schedules', 'builder'];
const kindLabels: Record<string, string> = { run: 'Run result', orchestration: 'Orchestration', approval: 'Approval', hook: 'Hook notice', 'sync.conflict': 'Sync conflict', notice: 'Notice' };
const kindLabel = (kind: string) => kindLabels[kind] ?? kind;
const date = (value: string) => { const d = new Date(value); return Number.isNaN(d.getTime()) ? value : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }); };
const preview = (text: string) => { const paragraph = document.createElement('div'); paragraph.innerHTML = markdownHtml(text.split(/\n\s*\n/)[0]); return paragraph.textContent?.replace(/\s+/g, ' ').slice(0, 110) ?? ''; };
const capLabels: Record<string, string> = { turn_cap: 'turn limit', call_budget: 'tool call budget', time_cap: 'time limit', stuck: 'progress decision', uncovered: 'coverage decision', check_failures: 'check failures' };
const capLabel = (cap: string) => capLabels[cap] ?? cap.replace(/_/g, ' ');
const prose = (text: string) => `<div class="message-content markdown-body" data-copy-source="${esc(text)}">${copyButton('Copy text', 'answer')}${markdownHtml(text)}</div>`;
async function action(request: Request) {
  try { const reply = await window.plowshare.request(request); localError = ''; update(reply.state); }
  catch (error) { localError = error instanceof Error ? error.message : 'Request failed.'; render(); }
}
function update(next: DesktopState) {
  if (state) { rememberQuestion(state, runId, $('#activity-detail')); rememberSchedule($('#activity-detail')); }
  state = next;
  if (next.activity.view && view !== next.activity.view) { view = next.activity.view; if ((view === 'runs' || view === 'builder')) requestedRun = ''; $<HTMLInputElement>('#activity-search').value = ''; detailStamp = ''; }
  render();
}
function render() {
  if (!state) return;
  const activity = state.activity, inbox = activity.inbox, runs = activity.runs;
  $('#activity-connection').textContent = state.mode === 'demo' ? 'Offline demo · sample activity' : `${state.connection} · ${state.handle}`;
  $('#activity-title').textContent = view === 'inbox' ? 'Inbox' : view === 'runs' ? 'Runs' : view === 'definitions' ? 'Definitions' : view === 'builder' ? 'Orchestration builder' : 'Scheduled work';
  $('#activity-icon').innerHTML = icon(view === 'inbox' ? 'inbox' : 'route');
  $('#activity-caption').textContent = view === 'inbox' ? 'Results and notices across your account' : view === 'runs' ? 'Background orchestrations across your account' : view === 'definitions' ? 'Available orchestration definitions and stages' : view === 'builder' ? 'Interview, draft, validate and review an agent-driven procedure' : 'Schedules, triggers and recent firings across your account';
  $('#activity-unread').textContent = inbox.unread === undefined ? '—' : String(inbox.unread);
  for (const tab of tabs) {
    const button = $<HTMLButtonElement>(`#activity-${tab}`);
    button.setAttribute('aria-selected', String(tab === view)); button.tabIndex = tab === view ? 0 : -1;
  }
  $('#activity-panel').setAttribute('aria-labelledby', `activity-${view}`);
  const errors = [localError, view === 'inbox' ? inbox.error : view === 'runs' ? runs.error : view === 'definitions' ? activity.definitions?.error : activity.schedules?.error, state.mode === 'live' && !state.connected ? 'Reconnect in the main window to update activity. Shown information may be out of date.' : ''].filter(Boolean);
  $('#activity-error').hidden = !errors.length; $('#activity-error').textContent = errors.join(' ');
  $('#builder-form').hidden = view !== 'builder';
  $('#builder-status').hidden = view !== 'builder';
  if (view === 'builder') builderForm(state);
  const query = $<HTMLInputElement>('#activity-search').value.toLowerCase();
  const filter = $<HTMLSelectElement>('#inbox-filter').value;
  const typeSelect = $<HTMLSelectElement>('#inbox-type');
  const type = typeSelect.value;
  const kinds = [...new Set([...Object.keys(kindLabels), ...inbox.items.map(item => item.kind), ...(type ? [type] : [])])];
  const stamp = JSON.stringify(kinds);
  if (typeStamp !== stamp) {
    typeStamp = stamp;
    typeSelect.innerHTML = '<option value="">All types</option>' + kinds.map(kind => `<option value="${esc(kind)}">${esc(kindLabel(kind))}</option>`).join('');
    typeSelect.value = type;
  }
  const projects = JSON.stringify(state.projects.map(row => row.name));
  if (projects !== projectStamp) { projectStamp = projects; $('#definitions-project').innerHTML = '<option value="">Global</option>' + state.projects.map(row => `<option value="${esc(row.name)}">${esc(row.name)}</option>`).join(''); }
  $<HTMLSelectElement>('#definitions-project').value = activity.definitions?.project ?? '';
  $('#definitions-scope').hidden = view !== 'definitions';
  $('#inbox-filter-control').hidden = view !== 'inbox';
  const shownInbox = inbox.items.filter(item => (filter === 'all' || inbox.read.includes(item.id) === (filter === 'read'))
    && (!type || item.kind === type)
    && `${item.kind} ${item.answer} ${item.ending ?? ''}`.toLowerCase().includes(query));
  if (view === 'builder' && state.authoring?.conversation && state.authoring.conversation !== launchConversation) { launchConversation = state.authoring.conversation; pendingLaunch = true; runId = ''; requestedRun = ''; }
  const selectedBuilder = view === 'builder' && activity.details[runId]?.value?.run;
  const runItems = selectedBuilder && selectedBuilder.definition === 'design_orchestration' && !runs.items.some(row => row.id === selectedBuilder.id) ? [...runs.items, selectedBuilder] : runs.items;
  const shownRuns = runItems.filter(run => (view !== 'builder' || run.definition === 'design_orchestration') && `${run.definition} ${run.id} ${run.project ?? ''} ${run.state}`.toLowerCase().includes(query));
  const focusId = document.activeElement?.closest<HTMLElement>('[data-item]')?.dataset.item;
  if (view === 'inbox') {
    if (!shownInbox.some(item => item.id === inboxId)) { inboxId = shownInbox[0]?.id ?? ''; $('#activity-detail').scrollTop = 0; }
    $('#activity-list').innerHTML = shownInbox.map(item => `<button class="activity-row" data-item="${esc(item.id)}" aria-current="${item.id === inboxId}">${icon(inbox.read.includes(item.id) ? 'check' : 'inbox')}<span><strong>${esc(kindLabel(item.kind))}${item.ending ? ` · ${esc(item.ending)}` : ''}</strong><small>${esc(preview(item.answer))}</small><time>${esc(date(item.arrivedAt))} · ${inbox.read.includes(item.id) ? 'Read' : 'Unread'}</time></span></button>`).join('');
    $('#activity-list-note').textContent = inbox.loading ? 'Updating inbox…' : `${shownInbox.length} shown · ${inbox.items.length} loaded. The counter shows unread only.${inbox.more ? ' Load older items for more history.' : ''}`;
  } else if (view === 'runs' || view === 'builder') {
    if (view === 'builder' && !shownRuns.some(row => row.id === runId)) { runId = ''; requestedRun = ''; }
    if (!runId && shownRuns.length && !(view === 'builder' && pendingLaunch)) { runId = shownRuns[0].id; requestedRun = ''; }
    if (view === 'builder' && pendingLaunch && state.authoring?.conversation && shownRuns.some(row => row.callerConversation === state.authoring?.conversation)) { runId = shownRuns.find(row => row.callerConversation === state.authoring?.conversation)!.id; requestedRun = ''; pendingLaunch = false; }
    $('#activity-list').innerHTML = shownRuns.map(run => `<button class="activity-row" data-item="${esc(run.id)}" aria-current="${run.id === runId}">${icon(run.parent ? 'layers' : 'route')}<span><strong>${esc(run.definition || run.id)}</strong><small>${esc(run.project || 'Global')} · ${esc(run.state)}${run.stalledSince ? ' · stalled' : ''}${run.parent ? ` · child, depth ${run.depth}` : ''}</small><time>${esc(date(run.createdAt))}</time></span></button>`).join('');
    $('#activity-list-note').textContent = runs.loading ? 'Updating runs…' : runs.limited ? 'A live-state list reached its 200-run limit. Some active runs may be omitted.' : 'Live runs and 20 recent runs. Child runs belong to their parent.';
    if (runId && runId !== requestedRun) { requestedRun = runId; void selectRun(runId); }
  }
  if (view === 'definitions') {
    const rows = (activity.definitions?.items ?? []).filter(row => `${row.name} ${row.description ?? ''}`.toLowerCase().includes(query));
    if (!rows.some(row => row.name === definitionId)) definitionId = rows[0]?.name ?? '';
    $('#activity-list').innerHTML = rows.map(row => `<button class="activity-row" data-item="${esc(row.name)}" aria-current="${row.name === definitionId}"><span><strong>${esc(row.name)}</strong><small>${row.served ? 'Available' : 'Withheld'} · ${esc(row.tier ?? '')}</small></span></button>`).join('') || '<p class="activity-empty">No definitions to show.</p>';
    $('#activity-list-note').textContent = activity.definitions?.loading ? 'Updating definitions…' : `${rows.length} definitions`;
  }
  if (view === 'schedules') {
    const rows = [...(activity.schedules?.schedules ?? []).map(row => ({ row, kind: 'schedule' })), ...(activity.schedules?.triggers ?? []).map(row => ({ row, kind: 'trigger' }))].filter(({ row }) => row.name.toLowerCase().includes(query));
    $('#activity-list').innerHTML = `<button class="activity-row" data-item="" aria-current="${!scheduledId}">New scheduled work</button>` + rows.map(({ row, kind }) => { const key = JSON.stringify([kind, row.name]); return `<button class="activity-row" data-item="${esc(key)}" aria-current="${key === scheduledId}"><span><strong>${esc(row.name)}</strong><small>${kind} · ${row.paused ? 'Paused' : 'Active'}</small></span></button>`; }).join('');
    $('#activity-list-note').textContent = activity.schedules?.loading ? 'Updating scheduled work…' : `${rows.length} shown · up to 100 recent account firings loaded`;
  }
  $('#inbox-older').hidden = view !== 'inbox' || !inbox.more;
  $<HTMLButtonElement>('#inbox-older').disabled = Boolean(inbox.loading) || !state.connected;
  if (view !== 'definitions' && view !== 'schedules' && !$('#activity-list').children.length) {
    const source = view === 'inbox' ? inbox : runs;
    $('#activity-list').innerHTML = `<p class="activity-empty">${source.loading ? 'Loading…' : !source.loaded ? 'Activity unavailable. Try Refresh.' : query || type && view === 'inbox' ? `No matching items.${view === 'inbox' && inbox.more ? ' Load older items to look further back.' : ''}` : view === 'inbox' ? filter === 'all' ? 'No mailbox items yet.' : `No ${filter} items${inbox.more ? ' in the loaded history. Load older items to look further back' : ''}.` : 'No runs yet.'}</p>`;
  }
  if (focusId) [...$('#activity-list').querySelectorAll<HTMLButtonElement>('[data-item]')].find(button => button.dataset.item === focusId)?.focus();
  renderDetail();
  const output = view === 'builder' && runId && activity.details[runId]?.wire?.orchestration.conductorConversation;
  const outputStamp = output && JSON.stringify([runId, activity.records?.[runId]?.through, activity.details[runId]?.wire?.messages.length]);
  if (state.connected && outputStamp && outputStamp !== outputsStamp) { outputsStamp = outputStamp; void action({ action: 'builder-outputs', id: runId }); }
}
function renderDetail() {
  const activity = state.activity;
  const item = activity.inbox.items.find(item => item.id === inboxId), detail = activity.details[runId];
  const stamp = JSON.stringify([view, view === 'inbox' ? [item, activity.inbox.read.includes(inboxId), marking] : [runId, detail, activity.records?.[runId], activity.decisions?.[runId]], view === 'builder' ? state.history[detail?.wire?.orchestration.conductorConversation ?? ''] : undefined, activity.definitions, definitionId, activity.schedules, scheduledId, state.connected, state.mode, state.approvals, state.answeringApprovals, approvalControls.revision]);
  if (stamp === detailStamp) return; detailStamp = stamp;
  const content = $('#activity-detail'); const scroll = content.scrollTop;
  if (view === 'inbox') {
    const approval = item && noticeApproval(state, item);
    content.innerHTML = item ? `<header><div class="eyebrow">${esc(item.kind)}${item.ending ? ` · ${esc(item.ending)}` : ''}</div><h2>${icon('inbox')}Inbox item</h2><p>${esc(date(item.arrivedAt))}</p></header>${prose(item.answer)}<div class="activity-receipt"><button id="inbox-mark" ${marking || activity.inbox.read.includes(item.id) || (state.mode === 'live' && !state.connected) ? 'disabled' : ''}>${icon('check')}${activity.inbox.read.includes(item.id) ? 'Marked read' : marking ? 'Marking…' : 'Mark read'}</button><span>Reading here does not mark it automatically.</span></div>` : `<div class="activity-empty">${icon('inbox')}<h2>Your inbox</h2><p>Results and notices appear here, including work started from another client.</p></div>`;
    if (approval) content.querySelector('.activity-receipt')?.insertAdjacentHTML('beforebegin', approvalControls.prompt(approval));
  } else if (view === 'schedules') { content.innerHTML = scheduledPanel(state, scheduledId);
  } else if (view === 'definitions') {
    const definition = activity.definitions?.items.find(row => row.name === definitionId);
    content.innerHTML = definition ? `<header><h2>${esc(definition.name)}</h2><p>${esc(definition.tier ?? '')} · ${definition.served ? 'Available' : 'Withheld'}</p></header>${definition.description ? prose(definition.description) : ''}${definition.withheld ? `<p class="activity-warning">${esc(definition.withheld)}</p>` : ''}<section><h3>Stages</h3>${definition.stages.map(stage => `<article class="activity-message"><h4>${esc(stage.id)}</h4>${prose(stage.doneWhen)}${stage.mayReturnTo.length ? `<p>May return to: ${esc(stage.mayReturnTo.join(', '))}</p>` : ''}</article>`).join('')}</section><section><h3>Triggers</h3><p>${esc(definition.triggers.join(', ') || 'None')}</p></section>` : '<p class="activity-empty">Choose a definition.</p>';
  } else if (detail?.value) {
    const { run, stages, messages, children } = detail.value;
    content.innerHTML = `${view === 'builder' ? builderPanel(state, runId) : ''}<header><div class="eyebrow">${esc(run.project || 'Global')} · ${esc(run.tier)} · ${esc(run.state)}${detail.loading ? ' · updating' : ''}</div><h2>${icon('route')}${esc(run.definition || run.id)}</h2><p class="activity-id">${esc(run.id)}</p></header>${detail.error ? `<p class="error-banner">${esc(detail.error)}</p>` : ''}
      ${run.parent ? `<p class="activity-relation">Child run · depth ${run.depth} · <button data-run="${esc(run.parent)}">${icon('layers')}Parent ${esc(run.parent)}</button></p>` : ''}
      ${run.stalledSince ? `<p class="activity-warning">${icon('alert')}Marked stalled ${esc(date(run.stalledSince))}</p>` : ''}
      ${run.pendingCap ? `<p class="activity-warning">${icon('alert')}Waiting on a ${esc(capLabel(run.pendingCap))}</p>` : ''}
      ${run.waitingFor ? `<p class="activity-relation">Waiting on ${esc(run.waitingFor)}</p>` : ''}
      ${run.result ? `<section><h3>Result</h3>${prose(run.result)}</section>` : ''}${run.failure ? `<section><h3>Why it stopped</h3>${prose(run.failure)}</section>` : ''}
      ${stages.length ? `<section><h3>${icon('layers')}Stages</h3><ul class="activity-stages">${stages.map(stage => `<li>${icon(stage.status === 'done' ? 'check' : 'activity')}<div><strong>${esc(stage.text)}</strong><small>${esc(stage.status)}${stage.stage ? ` · ${esc(stage.stage)}` : ''}</small>${stage.summary ? `<p>${esc(stage.summary)}</p>` : ''}</div></li>`).join('')}</ul></section>` : ''}
      ${children.length ? `<section><h3>Child runs</h3><div class="activity-children">${children.map(child => `<button data-run="${esc(child.id)}">${icon('layers')}<span>${esc(child.id)} · ${esc(child.state)}</span>${icon('external')}</button>`).join('')}</div></section>` : ''}
      ${messages.length ? `<section><h3>Questions and answers</h3>${messages.map(message => `<article class="activity-message"><div class="eyebrow">${esc(message.kind)} · ${esc(message.author)}</div>${prose(message.text)}${message.structure ? `<div class="activity-options">${prose(message.structure.lead)}${message.structure.questions.map(question => `<h4>${esc(question.header)}</h4>${prose(question.question)}<ul>${question.options.map(option => `<li><strong>${esc(option.label)}</strong> ${esc(option.description)}${option.preview ? `<pre>${esc(option.preview)}</pre>` : ''}</li>`).join('')}</ul>`).join('')}${message.structure.draft ? `<h4>Draft · ${esc(message.structure.draft.name)}</h4><p>${esc(message.structure.draft.path)}</p>${prose(message.structure.draft.text)}` : ''}</div>` : ''}</article>`).join('')}</section>` : ''}
      ${questionControls(state, runId)}${recordControls(state, runId)}`;
  } else content.innerHTML = `<div class="activity-empty">${icon('route')}<h2>${detail?.loading ? 'Loading run…' : 'Choose a run'}</h2><p>${esc(detail?.error || 'Inspect stages, child runs and results here.')}</p></div>`;
  content.scrollTop = scroll;
}
async function selectRun(id: string) { await action({ action: 'run-detail', id }); if (state.connected && runId === id && (state.activity.view === 'runs' || state.activity.view === 'builder')) await action({ action: 'run-record', id }); }
async function tab(next: ActivityView) { view = next; if (view === 'runs' || view === 'builder') { requestedRun = ''; runId = ''; } detailStamp = ''; $<HTMLInputElement>('#activity-search').value = ''; await action({ action: 'activity-view', view }); }
for (const next of tabs) {
  $(`#activity-${next}`).addEventListener('click', () => void tab(next));
  $(`#activity-${next}`).addEventListener('keydown', event => { if (['ArrowLeft', 'ArrowRight'].includes((event as KeyboardEvent).key)) { event.preventDefault(); const other = tabs[(tabs.indexOf(next) + ((event as KeyboardEvent).key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length]; $(`#activity-${other}`).focus(); void tab(other); } });
}
$('#activity-search').addEventListener('input', render);
$('#inbox-filter').addEventListener('change', () => { detailStamp = ''; render(); });
$('#inbox-type').addEventListener('change', () => { detailStamp = ''; render(); });
$('#inbox-older').addEventListener('click', () => void action({ action: 'inbox-more' }));
$('#activity-refresh').addEventListener('click', () => { outputsStamp = ''; void action({ action: 'activity-refresh' }); });
$('#activity-list').addEventListener('click', event => {
  const id = (event.target as HTMLElement).closest<HTMLElement>('[data-item]')?.dataset.item; if (id === undefined) return;
  if (view === 'schedules') { rememberSchedule($('#activity-detail')); scheduledId = id; } else if (view === 'definitions') definitionId = id; else if (view === 'inbox') inboxId = id; else { pendingLaunch = false; runId = id; requestedRun = id; void selectRun(id); }
  detailStamp = ''; $('#activity-detail').scrollTop = 0; render();
});
document.addEventListener('click', event => {
  const target = event.target as HTMLElement;
  rememberQuestion(state, runId, $('#activity-detail'));
  rememberSchedule($('#activity-detail'));
  const scheduled = view === 'schedules' ? scheduledAction(state, scheduledId, target) : undefined; if (scheduled) void action(scheduled);
  const decision = questionAction(state, runId, target);
  if (decision === 'render') { detailStamp = ''; renderDetail(); } else if (decision) void action(decision);
  const actor = target.closest<HTMLElement>('[data-run-actor]')?.dataset.runActor;
  if (actor === 'caller' || actor === 'conductor') void action({ action: 'run-trajectory', id: runId, actor });
  if (target.closest('#run-record-refresh')) void action({ action: 'run-record', id: runId });
  if (target.closest('#run-record-older')) { const oldest = state.activity.records?.[runId]?.oldest; if (oldest) void action({ action: 'run-record', id: runId, before: oldest }); }
  const link = target.closest<HTMLElement>('[data-web-link]')?.dataset.webLink;
  if (link) { event.preventDefault(); void action({ action: 'open-link', url: link }); }
  const child = target.closest<HTMLElement>('[data-run]')?.dataset.run;
  if (child) { runId = child; requestedRun = child; detailStamp = ''; $('#activity-detail').scrollTop = 0; void selectRun(child); render(); }
  if (target.closest('#inbox-mark') && !marking) {
    marking = true; renderDetail();
    void action({ action: 'inbox-read', id: inboxId }).finally(() => { marking = false; renderDetail(); });
  }
});
$('#definitions-project').addEventListener('change', () => void action({ action: 'run-definitions', project: $<HTMLSelectElement>('#definitions-project').value || undefined }));
$('#activity-detail').addEventListener('input', () => { rememberQuestion(state, runId, $('#activity-detail')); rememberSchedule($('#activity-detail')); });
$('#activity-detail').addEventListener('change', event => { const select = (event.target as HTMLElement).closest<HTMLSelectElement>('#run-record-filter'); if (select) void action({ action: 'run-record', id: runId, kinds: select.value ? select.value.split(',') : [] }); });
window.plowshare.subscribe(update);
void window.plowshare.request({ action: 'bootstrap' }).then(reply => { view = reply.view ?? 'inbox'; update(reply.state); });

$('#builder-prepare').addEventListener('click', () => void action({ action: 'builder-prepare', project: $<HTMLSelectElement>('#builder-project').value }));
$('#builder-project').addEventListener('change', () => { builderForm(state); });
$('#builder-agent').addEventListener('change', () => { builderForm(state); });
$('#builder-start').addEventListener('click', () => void action({ action: 'builder-start', project: $<HTMLSelectElement>('#builder-project').value, agent: $<HTMLSelectElement>('#builder-agent').value, intent: $<HTMLTextAreaElement>('#builder-intent').value, revision: $<HTMLSelectElement>('#builder-revision').value || undefined }));
$('#builder-caller').addEventListener('click', () => { if (state.authoring?.conversation) void action({ action: 'builder-trajectory', conversation: state.authoring.conversation }); });
