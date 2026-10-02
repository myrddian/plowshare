import { memberAction, memberActions, memberKey, SWARM_PAGE, swarmMembers, type SwarmMember } from 'plowshare-client-ts/operations/swarm';
import type { DesktopState, Request } from '../shared.ts';
import type { BoardView, TopicSummary, SeatView } from '../board-shared.ts';
import { icon, mountIcons } from './icons.ts';
import { escapeHtml as esc, markdownHtml } from './markdown.ts';
import { copyButton, installCopyControls } from './copy.ts';

mountIcons(); installCopyControls();
const $ = <T extends HTMLElement = HTMLElement>(selector: string): T => document.querySelector(selector)!;
let state: DesktopState;
let view: BoardView = 'board', requested = '', stamp = '', localError = '', selectedMember = '';
let members: SwarmMember[] = [];
const date = (v: string) => { const d = new Date(v); return Number.isNaN(d.getTime()) ? v : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }); };
const prose = (text: string) => `<div class="message-content" data-copy-source="${esc(text)}">${copyButton('Copy text', 'answer')}${markdownHtml(text)}</div>`;
const wait = (ms: number) => ms < 60000 ? `${Math.floor(ms / 1000)}s` : `${Math.floor(ms / 60000)}m`;
async function action(request: Request) {
  try { const reply = await window.plowshare.request(request); localError = ''; update(reply.state); }
  catch (error) { localError = error instanceof Error ? error.message : String(error); render(); }
}
function update(next: DesktopState) {
  const nextView = next.board.view ?? view;
  if (nextView !== view) { $<HTMLInputElement>('#board-search').value = ''; stamp = ''; }
  state = next; view = nextView; render();
}
function seatsHtml(seats: SeatView[]) {
  return `<div class="board-seats">${seats.map(s => `<button class="board-seat" data-conversation="${esc(s.seat.conversation)}" title="Inspect ${esc(s.seat.occupant)} trajectory"><strong>${esc(s.seat.occupant === '@opener' ? 'Opener' : s.seat.occupant)} ${icon('external')}</strong><span class="seat-state ${['running','ready','owed','failed','blocked','held'].includes(s.state) ? s.state : ''}">${icon(s.state === 'passed' ? 'check' : 'activity')}${esc(s.state)}${s.position ? ` · queue ${s.position}` : ''}</span>${s.waitedMillis !== null ? `<small>Waiting ${wait(s.waitedMillis)}${s.overdue ? ' · overdue' : ''}</small>` : ''}${s.seat.failedEnding ? `<small>Ending: ${esc(s.seat.failedEnding)}</small>` : ''}${s.reason ? `<small>${esc(s.reason)}</small>` : ''}${s.job ? `<small>Job ${esc(s.job)}</small>` : ''}${s.seat.silentWakes ? `<small>${s.seat.silentWakes} silent wakes</small>` : ''}</button>`).join('')}</div>`;
}
function rowsInTree(rows: TopicSummary[]) {
  const byParent = new Map<string | null, TopicSummary[]>(), ids = new Set(rows.map(r => r.topic.id));
  for (const row of rows) { const p = row.topic.parent && ids.has(row.topic.parent) ? row.topic.parent : null; byParent.set(p, [...(byParent.get(p) ?? []), row]); }
  const out: TopicSummary[] = [], seen = new Set<string>();
  const visit = (p: string | null) => { for (const r of (byParent.get(p) ?? []).sort((a,b) => Number(a.topic.state === 'closed') - Number(b.topic.state === 'closed') || b.topic.openedAt.localeCompare(a.topic.openedAt))) { if (seen.has(r.topic.id)) continue; seen.add(r.topic.id); out.push(r); visit(r.topic.id); } };
  visit(null); for (const row of rows) if (!seen.has(row.topic.id)) out.push(row);
  return out;
}
function render() {
  if (!state) return;
  const b = state.board, reading = view === 'board' ? b.topics : b.swarm;
  $('#board-title').textContent = view === 'swarm' ? 'Swarm members' : 'Project board';
  $('#board-description').textContent = view === 'swarm' ? 'See what each member is doing and inspect its trajectory' : 'Inspect the conversation behind the work';
  $('#board-panel').classList.toggle('swarm-view', view === 'swarm');
  $('#board-browser').setAttribute('aria-label', view === 'swarm' ? 'Current swarm members' : 'Board topics');
  $('#board-topics').setAttribute('aria-label', view === 'swarm' ? 'Members' : 'Topics');
  $('#board-detail').setAttribute('aria-label', view === 'swarm' ? 'Member activity' : 'Topic inspection');
  $<HTMLInputElement>('#board-search').placeholder = view === 'swarm' ? 'Find members, actions or topics…' : 'Find topics…';
  $('#board-search').setAttribute('aria-label', view === 'swarm' ? 'Search members' : 'Search topics');
  $('#board-connection').textContent = state.mode === 'demo' ? 'Offline demo · sample swarm' : `${state.connection} · ${state.handle}`;
  for (const tab of ['board','swarm'] as const) { const button = $(`#view-${tab}`); button.setAttribute('aria-selected', String(view === tab)); button.tabIndex = view === tab ? 0 : -1; }
  $('#board-panel').setAttribute('aria-labelledby', `view-${view}`);
  const errors = [localError, reading.error, state.mode === 'live' && !state.connected ? 'Reconnect in the main window. Shown information may be out of date.' : ''].filter(Boolean);
  $('#board-error').hidden = !errors.length; $('#board-error').textContent = errors.join(' ');
  $('#swarm-summary').hidden = view !== 'swarm';
  const swarm = b.swarm.value;
  if (view === 'swarm') $('#swarm-summary').innerHTML = swarm ? `${swarm.pools.map(p => `<div class="swarm-pool"><strong>${esc(p.pool)}</strong><meter min="0" max="${Math.max(1,p.slots)}" value="${p.used}"></meter><span>${p.used} / ${p.slots} swarm slots</span></div>`).join('') || '<p>No pools declare swarm slots.</p>'}<div class="swarm-queue"><strong>${swarm.ready.length} queued model calls for your account</strong><br>Pool occupancy is shared across all accounts.<br>Queue positions are arrival order; fair sharing can change service order.</div>` : '<p class="board-empty">Loading swarm snapshot…</p>';
  const project = $<HTMLSelectElement>('#board-project'), projectFocused = document.activeElement === project;
  const projectValue = b.project ?? '';
  const projectNames = [...new Set([...state.projects.map(p => p.name), ...(b.topics.value ?? []).map(r => r.topic.project), ...(swarm?.topics ?? []).map(r => r.topic.project), ...(projectValue ? [projectValue] : [])])].sort();
  const options = `<option value="">All projects</option>${projectNames.map(p => `<option value="${esc(p)}">${esc(p)}</option>`).join('')}`;
  if (!projectFocused && project.innerHTML !== options) project.innerHTML = options;
  project.value = projectValue;
  const query = $<HTMLInputElement>('#board-search').value.toLowerCase(), filter = $<HTMLSelectElement>('#board-state').value;
  const all = view === 'board' ? b.topics.value : swarm?.topics;
  const rows = (all ?? []).filter(r => (!projectValue || r.topic.project === projectValue) && `${r.topic.title} ${r.topic.label} ${r.topic.project} ${r.topic.id}`.toLowerCase().includes(query)
    && (view === 'swarm' || filter === 'all' || (filter === 'active' ? r.topic.state !== 'closed' : r.topic.state === 'closed')));
  $('#board-state').hidden = view === 'swarm';
  $('#board-updated').textContent = state.mode === 'demo' ? 'Sample data · no agents are running' : reading.loading ? 'Updating…' : reading.updatedAt ? `Updated ${date(reading.updatedAt)} · refreshes every 4 seconds while open` : 'Waiting for a server snapshot';
  if (view === 'swarm') { renderMembers(query, projectValue); return; }
  const focused = document.activeElement?.closest<HTMLElement>('[data-topic]')?.dataset.topic;
  $('#board-topics').innerHTML = rowsInTree(rows).map(r => {
    return `<button class="topic-row ${r.topic.parent ? 'child' : ''}" data-topic="${esc(r.topic.id)}" aria-current="${r.topic.id === b.selected}"><span class="topic-label">${esc(r.topic.label)} · ${esc(r.topic.state)}</span><strong>${r.topic.parent ? '↳ ' : ''}${esc(r.topic.title)}</strong><span class="topic-meta">${icon('folder')}${esc(r.topic.project)} · ${r.messages} msgs · ${r.documents} docs</span></button>`;
  }).join('') || `<div class="board-empty">${reading.loading ? 'Loading topics…' : reading.error ? 'Topics unavailable. Try Refresh.' : all ? query || filter !== 'all' || projectValue ? 'No matching topics.' : 'No topics yet. Ask a board-enabled agent in a project conversation to start research.' : 'Open a connection or try Refresh.'}</div>`;
  if (focused) [...$('#board-topics').querySelectorAll<HTMLElement>('[data-topic]')].find(el => el.dataset.topic === focused)?.focus();
  $('#board-more').textContent = 'Load more topics';
  $('#board-more').hidden = view !== 'board' || !b.topics.more; $<HTMLButtonElement>('#board-more').disabled = !!b.topics.loading;
  $('#board-list-note').textContent = b.topics.more ? `${all?.length ?? 0} topics loaded. Load more to expand the tree.` : `${all?.length ?? 0} topics · open topics first, with their children.`;
  $('#board-updated').textContent = state.mode === 'demo' ? 'Sample data · no agents are running' : reading.loading ? 'Updating…' : reading.updatedAt ? `Updated ${date(reading.updatedAt)} · refreshes every 4 seconds while open` : 'Waiting for a server snapshot';
  if (!b.selected && rows[0] && requested !== rows[0].topic.id) { requested = rows[0].topic.id; void action({ action: 'board-topic', topic: requested }); }
  renderDetail();
}
function renderMembers(query: string, project: string) {
  const b = state.board, snapshot = b.swarm.value;
  const all = snapshot ? swarmMembers(snapshot, project || undefined, b.activity) : [];
  // The page bound also bounds activity reads; search covers the loaded member prefix.
  const loaded = all.slice(0, b.memberLimit ?? SWARM_PAGE);
  members = loaded.filter(m => `${m.seat.seat.occupant} ${m.seat.state} ${m.topic?.topic.title ?? m.seat.seat.topic} ${m.topic?.topic.project ?? ''} ${memberAction(m)}`.toLowerCase().includes(query));
  const focused = document.activeElement?.closest<HTMLElement>('[data-member]')?.dataset.member;
  if (!members.some(m => memberKey(m.seat) === selectedMember)) selectedMember = members[0] ? memberKey(members[0].seat) : '';
  $('#board-topics').innerHTML = members.map(m => {
    const s = m.seat, current = memberKey(s) === selectedMember;
    const model = snapshot?.ready.find(r => r.topic === s.seat.topic && r.member === s.seat.occupant)?.specifier;
    return `<button class="swarm-member" data-member="${esc(memberKey(s))}" data-conversation="${esc(s.seat.conversation)}" aria-current="${current}" title="Inspect ${esc(s.seat.occupant)} trajectory">
      <span class="swarm-member-heading"><strong>${esc(s.seat.occupant === '@opener' ? m.topic?.topic.opener ?? 'Opener' : s.seat.occupant)}</strong><span class="seat-state ${['running','ready','owed','failed','blocked','held'].includes(s.state) ? s.state : ''}">${esc(s.state)}${s.position ? ` · queue ${s.position}` : ''}</span></span>
      <span class="swarm-member-action"><small>Latest recorded action${m.activity?.error ? ' · stale' : ''}</small><span>${esc(memberAction(m))}</span></span>
      <span class="topic-meta">${esc(m.topic?.topic.project ?? 'Topic')} · ${esc(m.topic?.topic.title ?? s.seat.topic)}</span>
      ${model ? `<small class="swarm-member-wait">${esc(model)} · waiting ${wait(s.waitedMillis ?? 0)}${s.overdue ? ' · overdue' : ''}</small>` : ''}
      <span class="swarm-member-link">Inspect trajectory ${icon('external')}</span></button>`;
  }).join('') || `<div class="board-empty">${b.swarm.loading ? 'Loading members…' : b.swarm.error ? 'Members unavailable. Try Refresh.' : query ? 'No matching members in the loaded list.' : 'No swarm members in this scope.'}</div>`;
  if (focused) [...$('#board-topics').querySelectorAll<HTMLElement>('[data-member]')].find(el => el.dataset.member === focused)?.focus();
  $('#board-more').hidden = loaded.length >= all.length; $<HTMLButtonElement>('#board-more').disabled = !!b.swarm.loading;
  $('#board-more').textContent = 'Load more members';
  $('#board-list-note').textContent = `${loaded.length} of ${all.length} members loaded · members on current topics${snapshot?.more ? ' · topic names/scope limited to the first 200 active topics' : ''}`;
  const selected = members.find(m => memberKey(m.seat) === selectedMember), content = $('#board-detail');
  const nextStamp = JSON.stringify(['swarm', selected, state.connected]);
  if (nextStamp === stamp) return; stamp = nextStamp;
  content.dataset.topic = '';
  if (!selected) { content.innerHTML = '<div class="board-empty"><h2>Swarm activity</h2><p>Members appear here when a topic has agent seats.</p></div>'; return; }
  const s = selected.seat, actions = memberActions(selected.activity?.value);
  content.innerHTML = `<header><div class="eyebrow">${esc(s.state)} · ${esc(selected.topic?.topic.project ?? s.seat.topic)}</div><h2>${esc(s.seat.occupant)}</h2><p class="board-subtitle">${esc(selected.topic?.topic.title ?? s.seat.topic)}</p></header>
    <button class="swarm-trajectory" data-conversation="${esc(s.seat.conversation)}">${icon('route')}Inspect trajectory</button>
    ${s.reason ? `<p>${esc(s.reason)}</p>` : ''}${s.seat.failedEnding ? `<p class="error-banner">${esc(s.seat.failedEnding)}</p>` : ''}
    <h3>Recent recorded actions</h3>${selected.activity?.error ? `<p class="error-banner">${esc(selected.activity.error)} · previous actions may be out of date.</p>` : ''}
    <ol class="swarm-actions">${actions.map(a => `<li><small>Entry ${a.ordinal}</small><p>${esc(a.text)}</p></li>`).join('') || `<li>${esc(memberAction(selected))}</li>`}</ol>
    <p class="board-subtitle">Latest log entries, refreshed while this view is open. Open the trajectory to inspect tool calls, results and live updates.</p>`;
}
function renderDetail() {
  const b = state.board, selected = b.selected, reading = selected ? b.details[selected] : undefined, d = reading?.value;
  const nextStamp = JSON.stringify([selected, d, reading?.error, state.connected]); if (nextStamp === stamp) return; stamp = nextStamp;
  const content = $('#board-detail'), previous = content.dataset.topic, scroll = content.scrollTop;
  const nearEnd = scroll + content.clientHeight >= content.scrollHeight - 80;
  content.dataset.topic = selected ?? '';
  if (!d) { content.innerHTML = `<div class="board-empty">${icon('layers')}<h2>${selected ? reading?.loading ? 'Loading topic…' : 'Topic unavailable' : 'Project board'}</h2><p>${esc(reading?.error ?? 'Choose a topic to inspect its messages, decisions and seats.')}</p></div>`; return; }
  const root = d.root, budget = root.potTotal ?? 0, spent = root.potSpent ?? 0;
  const ancestors: { id: string; title: string }[] = [], seen = new Set<string>(); let parent = d.topic.parent;
  const topics = [...(b.topics.value ?? []), ...(b.swarm.value?.topics ?? [])].map(r => r.topic);
  while (parent && !seen.has(parent)) { seen.add(parent); const p = topics.find(t => t.id === parent) ?? b.details[parent]?.value?.topic; ancestors.unshift({ id: parent, title: p?.title ?? parent }); parent = p?.parent ?? null; }
  content.innerHTML = `<nav class="board-breadcrumb" aria-label="Topic ancestors">${ancestors.map(p => `<button data-topic="${esc(p.id)}">${esc(p.title)}</button> / `).join('')}<span>${esc(d.topic.title)}</span></nav><header><div class="eyebrow">${esc(d.topic.project)} · ${esc(d.topic.label)} · ${esc(d.topic.state)}</div><h2>${esc(d.topic.title)}</h2><p class="board-subtitle">Opened by ${esc(d.topic.opener)} · ${esc(date(d.topic.openedAt))} · ${esc(d.topic.id)}${d.topic.closedAt ? ` · closed ${esc(date(d.topic.closedAt))}` : ''}</p></header>${reading?.error ? `<p class="error-banner">${esc(reading.error)}</p>` : ''}
    <div class="board-budget"><meter min="0" max="${Math.max(1,budget)}" value="${spent}"></meter><strong>${spent} / ${budget} model calls spent</strong><small>${root.reserve ?? 0} closing reserve${d.topic.parent ? ' · shared root budget' : ''}</small></div>
    <h3>${icon('activity')}Seats</h3>${d.seats.length ? seatsHtml(d.seats) : '<p class="board-subtitle">No agent seats on this topic.</p>'}
    ${d.topic.resolution ? `<section class="board-resolution"><div class="eyebrow">Resolution</div>${prose(d.topic.resolution)}</section>` : ''}
    <h3>${icon('message')}Messages · ${d.messages.length}</h3>${d.messages.map(m => {
      const reply = m.replyTo ? d.messages.find(p => p.id === m.replyTo) : undefined, decision = d.decisions.find(c => c.request === m.id);
      let depth = 0, parent = reply; const visited = new Set([m.id]);
      while (parent && !visited.has(parent.id) && depth < 6) { visited.add(parent.id); depth++; parent = d.messages.find(p => p.id === parent!.replyTo); }
      return `<article class="board-message ${m.replyTo ? `reply reply-${depth}` : ''}" id="message-${esc(m.id)}"><div class="board-message-meta"><span class="board-kind">${esc(m.kind.toUpperCase())}</span>${m.conversation ? `<button data-conversation="${esc(m.conversation)}">${esc(m.author)} ${icon('route')}</button>` : `<strong>${esc(m.author)}</strong>`}<time>${esc(date(m.postedAt))}</time>${m.alert ? '<span class="board-alert">⚑ Alert</span>' : ''}</div>${reply ? `<p class="board-reply">Reply to ${esc(reply.author)} · ${esc(reply.title ?? reply.kind)} · <button data-message="${esc(reply.id)}">View message</button></p>` : ''}${m.title ? `<h4>${esc(m.title)}</h4>` : ''}${prose(m.body)}${m.mentions.length ? `<p class="board-mentions">Mentions ${m.mentions.map(name => `@${esc(name)}`).join(' ')}</p>` : ''}${decision ? `<div class="board-decision"><strong>${decision.approved ? 'Approved' : 'Refused'}</strong><span>${esc(decision.reason)}</span>${decision.child ? `<button data-topic="${esc(decision.child)}">${icon('layers')}Inspect child topic</button>` : ''}</div>` : m.kind === 'request' ? '<p class="board-subtitle">Awaiting a decision</p>' : ''}</article>`;
    }).join('') || '<p class="board-subtitle">No messages yet.</p>'}`;
  content.scrollTop = previous === selected ? nearEnd ? content.scrollHeight : scroll : 0;
}
for (const next of ['board','swarm'] as const) {
  $(`#view-${next}`).addEventListener('click', () => void action({ action: 'board-view', view: next, project: state.board.project }));
  $(`#view-${next}`).addEventListener('keydown', event => { if (['ArrowLeft','ArrowRight'].includes((event as KeyboardEvent).key)) { event.preventDefault(); const other = next === 'board' ? 'swarm' : 'board'; $(`#view-${other}`).focus(); void action({ action: 'board-view', view: other, project: state.board.project }); } });
}
$('#board-search').addEventListener('input', render); $('#board-state').addEventListener('change', render);
$('#board-project').addEventListener('change', () => { requested = ''; stamp = ''; void action({ action: 'board-view', view, project: $<HTMLSelectElement>('#board-project').value || undefined }); });
$('#board-refresh').addEventListener('click', () => void action({ action: 'board-refresh' }));
$('#board-more').addEventListener('click', () => void action({ action: 'board-more' }));
document.addEventListener('click', event => {
  const target = event.target as HTMLElement;
  const member = target.closest<HTMLElement>('[data-member]')?.dataset.member;
  if (member) { selectedMember = member; stamp = ''; render(); }
  const topic = target.closest<HTMLElement>('[data-topic]')?.dataset.topic;
  if (topic) { requested = topic; void action({ action: 'board-topic', topic }); }
  const conversation = target.closest<HTMLElement>('[data-conversation]')?.dataset.conversation;
  if (conversation) void action({ action: 'board-trajectory', conversation });
  const message = target.closest<HTMLElement>('[data-message]')?.dataset.message;
  if (message) document.getElementById(`message-${message}`)?.scrollIntoView({ block: 'start', behavior: 'smooth' });
  const url = target.closest<HTMLElement>('[data-web-link]')?.dataset.webLink;
  if (url) { event.preventDefault(); void action({ action: 'open-link', url }); }
});
window.plowshare.subscribe(update);
void window.plowshare.request({ action: 'bootstrap' }).then(reply => { view = reply.boardView ?? 'board'; update(reply.state); });
