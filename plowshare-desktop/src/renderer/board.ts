import { errorMessage } from 'plowshare-client-ts/binding/values';
import { isObject } from 'plowshare-client-ts/binding/values';
import { ownedEvent, background } from './events.ts';
import { installPaneResize } from './pane-resize.ts';
import {
  memberAction,
  memberActions,
  memberKey,
  SWARM_PAGE,
  swarmMembers,
  type SwarmMember,
} from 'plowshare-client-ts/operations/swarm';
import type { DesktopState, Request } from '../shared.ts';
import type { BoardView, TopicSummary, SeatView } from '../board-shared.ts';
import { icon, mountIcons } from './icons.ts';
import { escapeHtml as esc, markdownHtml } from './markdown.ts';
import { copyButton, installCopyControls } from './copy.ts';

mountIcons();
installCopyControls();
const $ = <T extends HTMLElement = HTMLElement>(selector: string): T =>
  document.querySelector(selector)!;
installPaneResize({
  container: $('#board-panel'),
  pane: $('#board-browser'),
  other: $('#board-detail'),
  key: 'board',
  label: 'Resize board browser',
  property: '--board-width',
  minimum: 240,
});
let state: DesktopState;
let initialized = false;
let view: BoardView = 'board',
  requested = '',
  stamp = '',
  localError = '',
  selectedMember = '';
let members: SwarmMember[] = [];
const date = (v: string) => {
  const d = new Date(v);
  return Number.isNaN(d.getTime())
    ? v
    : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
};
const prose = (text: string) =>
  `<div class="message-content" data-copy-source="${esc(text)}">${copyButton('Copy text', 'answer')}${markdownHtml(text)}</div>`;
const wait = (ms: number) =>
  ms < 60000 ? `${Math.floor(ms / 1000)}s` : `${Math.floor(ms / 60000)}m`;
async function action(request: Request) {
  try {
    const reply = await window.plowshare.request(request);
    localError = '';
    update(reply.state);
  } catch (error) {
    localError = error instanceof Error ? error.message : errorMessage(error);
    render();
  }
}
function update(next: DesktopState) {
  state = next;
  if (initialized && next.board.view === view) render();
}
function retryButton(s: SeatView, project?: string) {
  return s.state === 'failed' &&
    s.seat.failedEnding &&
    s.seat.occupant !== '@opener' &&
    project
    ? `<button class="secondary-button member-retry" data-retry-member="${esc(s.seat.occupant)}" data-retry-topic="${esc(s.seat.topic)}" data-retry-project="${esc(project)}" ${!state.connected ? 'disabled' : ''}>Retry member…</button>`
    : '';
}
function seatsHtml(seats: SeatView[], project: string) {
  return `<div class="board-seats">${seats.map((s) => `<div class="board-seat-row"><button class="board-seat" data-conversation="${esc(s.seat.conversation)}" title="Inspect ${esc(s.seat.occupant)} trajectory"><strong>${esc(s.seat.occupant === '@opener' ? 'Opener' : s.seat.occupant)} ${icon('external')}</strong><span class="seat-state ${['running', 'ready', 'owed', 'failed', 'blocked', 'held'].includes(s.state) ? s.state : ''}">${icon(s.state === 'passed' ? 'check' : 'activity')}${esc(s.state)}${s.position ? ` · queue ${s.position}` : ''}</span>${s.waitedMillis !== null ? `<small>Waiting ${wait(s.waitedMillis)}${s.overdue ? ' · overdue' : ''}</small>` : ''}${s.seat.failedEnding ? `<small>Ending: ${esc(s.seat.failedEnding)}</small>` : ''}${s.reason ? `<small>${esc(s.reason)}</small>` : ''}${s.job ? `<small>Job ${esc(s.job)}</small>` : ''}${s.seat.silentWakes ? `<small>${s.seat.silentWakes} silent wakes</small>` : ''}</button>${retryButton(s, project)}</div>`).join('')}</div>`;
}
function rowsInTree(rows: TopicSummary[]) {
  const byParent = new Map<string | null, TopicSummary[]>(),
    ids = new Set(rows.map((r) => r.topic.id));
  for (const row of rows) {
    const p =
      row.topic.parent && ids.has(row.topic.parent) ? row.topic.parent : null;
    byParent.set(p, [...(byParent.get(p) ?? []), row]);
  }
  const out: TopicSummary[] = [],
    seen = new Set<string>();
  const visit = (p: string | null) => {
    for (const r of (byParent.get(p) ?? []).sort(
      (a, b) =>
        Number(a.topic.state === 'closed') -
          Number(b.topic.state === 'closed') ||
        b.topic.openedAt.localeCompare(a.topic.openedAt),
    )) {
      if (seen.has(r.topic.id)) continue;
      seen.add(r.topic.id);
      out.push(r);
      visit(r.topic.id);
    }
  };
  visit(null);
  for (const row of rows) if (!seen.has(row.topic.id)) out.push(row);
  return out;
}
function render() {
  if (!initialized) return;
  $('#board-actions').hidden = view !== 'board';
  $<HTMLButtonElement>('#board-create-open').disabled = !state.connected;
  renderOpening();
  $<HTMLButtonElement>('#board-post-open').disabled = !state.connected;
  renderPosting();
  renderRetry();
  if (!state) return;
  const b = state.board,
    reading = view === 'board' ? b.topics : b.swarm;
  $('#board-title-icon').innerHTML = icon(
    view === 'swarm' ? 'swarm' : 'layers',
  );
  $('#board-title').textContent =
    view === 'swarm' ? 'Swarm members' : 'Project board';
  $('#board-description').textContent =
    view === 'swarm'
      ? 'See what each member is doing and inspect its trajectory'
      : 'Inspect the conversation behind the work';
  $('#board-panel').classList.toggle('swarm-view', view === 'swarm');
  $('#board-browser').setAttribute(
    'aria-label',
    view === 'swarm' ? 'Current swarm members' : 'Board topics',
  );
  $('#board-topics').setAttribute(
    'aria-label',
    view === 'swarm' ? 'Members' : 'Topics',
  );
  $('#board-detail').setAttribute(
    'aria-label',
    view === 'swarm' ? 'Member activity' : 'Topic inspection',
  );
  $<HTMLInputElement>('#board-search').placeholder =
    view === 'swarm' ? 'Find members, actions or topics…' : 'Find topics…';
  $('#board-search').setAttribute(
    'aria-label',
    view === 'swarm' ? 'Search members' : 'Search topics',
  );
  $('#board-connection').textContent =
    state.mode === 'demo'
      ? 'Offline demo · sample swarm'
      : `${state.connection} · ${state.handle}`;
  for (const tab of ['board', 'swarm'] as const) {
    const button = $(`#view-${tab}`);
    button.setAttribute('aria-selected', String(view === tab));
    button.tabIndex = view === tab ? 0 : -1;
  }
  $('#board-panel').setAttribute('aria-labelledby', `view-${view}`);
  const errors = [
    localError,
    reading.error,
    state.mode === 'live' && !state.connected
      ? 'Reconnect in the main window. Shown information may be out of date.'
      : '',
  ].filter(Boolean);
  $('#board-error').hidden = !errors.length;
  $('#board-error').textContent = errors.join(' ');
  $('#swarm-summary').hidden = view !== 'swarm';
  const swarm = b.swarm.value;
  if (view === 'swarm')
    $('#swarm-summary').innerHTML = swarm
      ? `${swarm.pools.map((p) => `<div class="swarm-pool"><strong>${esc(p.pool)}</strong><meter min="0" max="${Math.max(1, p.slots)}" value="${p.used}"></meter><span>${p.used} / ${p.slots} swarm slots</span></div>`).join('') || '<p>No pools declare swarm slots.</p>'}<div class="swarm-queue"><strong>${swarm.ready.length} queued model calls for your account</strong><br>Pool occupancy is shared across all accounts.<br>Queue positions are arrival order; fair sharing can change service order.</div>`
      : '<p class="board-empty">Loading swarm snapshot…</p>';
  const project = $<HTMLSelectElement>('#board-project'),
    projectFocused = document.activeElement === project;
  const projectValue = b.project ?? '';
  const projectNames = [
    ...new Set([
      ...state.projects.map((p) => p.name),
      ...(b.topics.value ?? []).map((r) => r.topic.project),
      ...(swarm?.topics ?? []).map((r) => r.topic.project),
      ...(projectValue ? [projectValue] : []),
    ]),
  ].sort();
  const options = `<option value="">All projects</option>${projectNames.map((p) => `<option value="${esc(p)}">${esc(p)}</option>`).join('')}`;
  if (!projectFocused && project.innerHTML !== options)
    project.innerHTML = options;
  project.value = projectValue;
  const query = $<HTMLInputElement>('#board-search').value.toLowerCase(),
    filter = $<HTMLSelectElement>('#board-state').value;
  const all = view === 'board' ? b.topics.value : swarm?.topics;
  const rows = (all ?? []).filter(
    (r) =>
      (!projectValue || r.topic.project === projectValue) &&
      `${r.topic.title} ${r.topic.label} ${r.topic.project} ${r.topic.id}`
        .toLowerCase()
        .includes(query) &&
      (view === 'swarm' ||
        filter === 'all' ||
        (filter === 'active'
          ? r.topic.state !== 'closed'
          : r.topic.state === 'closed')),
  );
  $('#board-state').hidden = view === 'swarm';
  $('#board-updated').textContent =
    state.mode === 'demo'
      ? 'Sample data · no agents are running'
      : reading.loading
        ? 'Updating…'
        : reading.updatedAt
          ? `Updated ${date(reading.updatedAt)} · refreshes every 4 seconds while open`
          : 'Waiting for a server snapshot';
  if (view === 'swarm') {
    renderMembers(query, projectValue);
    return;
  }
  const focused =
    document.activeElement?.closest<HTMLElement>('[data-topic]')?.dataset.topic;
  $('#board-topics').innerHTML =
    rowsInTree(rows)
      .map((r) => {
        return `<button class="topic-row ${r.topic.parent ? 'child' : ''}" data-topic="${esc(r.topic.id)}" aria-current="${r.topic.id === b.selected}"><span class="topic-label">${esc(r.topic.label)} · ${esc(r.topic.state)}</span><strong>${r.topic.parent ? '↳ ' : ''}${esc(r.topic.title)}</strong><span class="topic-meta">${icon('folder')}${esc(r.topic.project)} · ${r.messages} msgs · ${r.documents} docs</span></button>`;
      })
      .join('') ||
    `<div class="board-empty">${reading.loading ? 'Loading topics…' : reading.error ? 'Topics unavailable. Try Refresh.' : all ? (query || filter !== 'all' || projectValue ? 'No matching topics.' : 'No topics yet. Ask a board-enabled agent in a project conversation to start research.') : 'Open a connection or try Refresh.'}</div>`;
  if (focused)
    [...$('#board-topics').querySelectorAll<HTMLElement>('[data-topic]')]
      .find((el) => el.dataset.topic === focused)
      ?.focus();
  $('#board-more').textContent = 'Load more topics';
  $('#board-more').hidden = view !== 'board' || !b.topics.more;
  $<HTMLButtonElement>('#board-more').disabled = !!b.topics.loading;
  $('#board-list-note').textContent = b.topics.more
    ? `${all?.length ?? 0} topics loaded. Load more to expand the tree.`
    : `${all?.length ?? 0} topics · open topics first, with their children.`;
  $('#board-updated').textContent =
    state.mode === 'demo'
      ? 'Sample data · no agents are running'
      : reading.loading
        ? 'Updating…'
        : reading.updatedAt
          ? `Updated ${date(reading.updatedAt)} · refreshes every 4 seconds while open`
          : 'Waiting for a server snapshot';
  if (!b.selected && rows[0] && requested !== rows[0].topic.id) {
    requested = rows[0].topic.id;
    background(action({ action: 'board-topic', topic: requested }));
  }
  renderDetail();
}
function renderMembers(query: string, project: string) {
  const b = state.board,
    snapshot = b.swarm.value;
  const all = snapshot
    ? swarmMembers(snapshot, project || undefined, b.activity)
    : [];
  // The page bound also bounds activity reads; search covers the loaded member prefix.
  const loaded = all.slice(0, b.memberLimit ?? SWARM_PAGE);
  members = loaded.filter((m) =>
    `${m.seat.seat.occupant} ${m.seat.state} ${m.topic?.topic.title ?? m.seat.seat.topic} ${m.topic?.topic.project ?? ''} ${memberAction(m)}`
      .toLowerCase()
      .includes(query),
  );
  const focused =
    document.activeElement?.closest<HTMLElement>('[data-member]')?.dataset
      .member;
  if (!members.some((m) => memberKey(m.seat) === selectedMember))
    selectedMember = members[0] ? memberKey(members[0].seat) : '';
  $('#board-topics').innerHTML =
    members
      .map((m) => {
        const s = m.seat,
          current = memberKey(s) === selectedMember;
        const model = snapshot?.ready.find(
          (r) => r.topic === s.seat.topic && r.member === s.seat.occupant,
        )?.specifier;
        return `<button class="swarm-member" data-member="${esc(memberKey(s))}" data-conversation="${esc(s.seat.conversation)}" aria-current="${current}" title="Inspect ${esc(s.seat.occupant)} trajectory">
      <span class="swarm-member-heading"><strong>${esc(s.seat.occupant === '@opener' ? (m.topic?.topic.opener ?? 'Opener') : s.seat.occupant)}</strong><span class="seat-state ${['running', 'ready', 'owed', 'failed', 'blocked', 'held'].includes(s.state) ? s.state : ''}">${esc(s.state)}${s.position ? ` · queue ${s.position}` : ''}</span></span>
      <span class="swarm-member-action"><small>Latest recorded action${m.activity?.error ? ' · stale' : ''}</small><span>${esc(memberAction(m))}</span></span>
      <span class="topic-meta">${esc(m.topic?.topic.project ?? 'Topic')} · ${esc(m.topic?.topic.title ?? s.seat.topic)}</span>
      ${model ? `<small class="swarm-member-wait">${esc(model)} · waiting ${wait(s.waitedMillis ?? 0)}${s.overdue ? ' · overdue' : ''}</small>` : ''}
      <span class="swarm-member-link">Inspect trajectory ${icon('external')}</span></button>`;
      })
      .join('') ||
    `<div class="board-empty">${b.swarm.loading ? 'Loading members…' : b.swarm.error ? 'Members unavailable. Try Refresh.' : query ? 'No matching members in the loaded list.' : 'No swarm members in this scope.'}</div>`;
  if (focused)
    [...$('#board-topics').querySelectorAll<HTMLElement>('[data-member]')]
      .find((el) => el.dataset.member === focused)
      ?.focus();
  $('#board-more').hidden = loaded.length >= all.length;
  $<HTMLButtonElement>('#board-more').disabled = !!b.swarm.loading;
  $('#board-more').textContent = 'Load more members';
  $('#board-list-note').textContent =
    `${loaded.length} of ${all.length} members loaded · members on current topics${snapshot?.more ? ' · topic names/scope limited to the first 200 active topics' : ''}`;
  const selected = members.find((m) => memberKey(m.seat) === selectedMember),
    content = $('#board-detail');
  const nextStamp = JSON.stringify(['swarm', selected, state.connected]);
  if (nextStamp === stamp) return;
  stamp = nextStamp;
  content.dataset.topic = '';
  if (!selected) {
    content.innerHTML =
      '<div class="board-empty"><h2>Swarm activity</h2><p>Members appear here when a topic has agent seats.</p></div>';
    return;
  }
  const s = selected.seat,
    actions = memberActions(selected.activity?.value);
  content.innerHTML = `<header><div class="eyebrow">${esc(s.state)} · ${esc(selected.topic?.topic.project ?? s.seat.topic)}</div><h2>${esc(s.seat.occupant)}</h2><p class="board-subtitle">${esc(selected.topic?.topic.title ?? s.seat.topic)}</p></header>
    <button class="swarm-trajectory" data-conversation="${esc(s.seat.conversation)}">${icon('route')}Inspect trajectory</button>
    ${retryButton(s, selected.topic?.topic.project)}
    ${s.reason ? `<p>${esc(s.reason)}</p>` : ''}${s.seat.failedEnding ? `<p class="error-banner">${esc(s.seat.failedEnding)}</p>` : ''}
    <h3>Recent recorded actions</h3>${selected.activity?.error ? `<p class="error-banner">${esc(selected.activity.error)} · previous actions may be out of date.</p>` : ''}
    <ol class="swarm-actions">${actions.map((a) => `<li><small>Entry ${a.ordinal}</small><p>${esc(a.text)}</p></li>`).join('') || `<li>${esc(memberAction(selected))}</li>`}</ol>
    <p class="board-subtitle">Latest log entries, refreshed while this view is open. Open the trajectory to inspect tool calls, results and live updates.</p>`;
}
function renderDetail() {
  const b = state.board,
    selected = b.selected,
    reading = selected ? b.details[selected] : undefined,
    d = reading?.value;
  const nextStamp = JSON.stringify([
    selected,
    d,
    reading?.error,
    state.connected,
  ]);
  if (nextStamp === stamp) return;
  stamp = nextStamp;
  const content = $('#board-detail'),
    previous = content.dataset.topic,
    scroll = content.scrollTop;
  const nearEnd = scroll + content.clientHeight >= content.scrollHeight - 80;
  content.dataset.topic = selected ?? '';
  if (!d) {
    content.innerHTML = `<div class="board-empty">${icon('layers')}<h2>${selected ? (reading?.loading ? 'Loading topic…' : 'Topic unavailable') : 'Project board'}</h2><p>${esc(reading?.error ?? 'Choose a topic to inspect its messages, decisions and seats.')}</p></div>`;
    return;
  }
  const root = d.root,
    budget = root.potTotal ?? 0,
    spent = root.potSpent ?? 0;
  const ancestors: { id: string; title: string }[] = [],
    seen = new Set<string>();
  let parent = d.topic.parent;
  const topics = [
    ...(b.topics.value ?? []),
    ...(b.swarm.value?.topics ?? []),
  ].map((r) => r.topic);
  while (parent && !seen.has(parent)) {
    seen.add(parent);
    const p =
      topics.find((t) => t.id === parent) ?? b.details[parent]?.value?.topic;
    ancestors.unshift({ id: parent, title: p?.title ?? parent });
    parent = p?.parent ?? null;
  }
  content.innerHTML = `<nav class="board-breadcrumb" aria-label="Topic ancestors">${ancestors.map((p) => `<button data-topic="${esc(p.id)}">${esc(p.title)}</button> / `).join('')}<span>${esc(d.topic.title)}</span></nav><header><div class="eyebrow">${esc(d.topic.project)}${d.topic.swarm ? ` · ${esc(d.topic.swarm.name)}` : ''} · ${esc(d.topic.label)} · ${esc(d.topic.state)}</div><h2>${esc(d.topic.title)}</h2><p class="board-subtitle">Opened by ${esc(d.topic.opener)} · ${esc(date(d.topic.openedAt))} · ${esc(d.topic.id)}${d.topic.closedAt ? ` · closed ${esc(date(d.topic.closedAt))}` : ''}</p></header>${reading?.error ? `<p class="error-banner">${esc(reading.error)}</p>` : ''}
    <div class="board-budget"><meter min="0" max="${Math.max(1, budget)}" value="${spent}"></meter><strong>${spent} / ${budget} model calls spent</strong><small>${root.reserve ?? 0} closing reserve${d.topic.parent ? ' · shared root budget' : ''}</small></div>
    <h3>${icon('activity')}Seats</h3>${d.seats.length ? seatsHtml(d.seats, d.topic.project) : '<p class="board-subtitle">No agent seats on this topic.</p>'}
    ${d.topic.resolution ? `<section class="board-resolution"><div class="eyebrow">Resolution</div>${prose(d.topic.resolution)}</section>` : ''}
    <h3>${icon('message')}Messages · ${d.messages.length}</h3>${
      d.messages
        .map((m) => {
          const reply = m.replyTo
              ? d.messages.find((p) => p.id === m.replyTo)
              : undefined,
            decision = d.decisions.find((c) => c.request === m.id);
          let depth = 0,
            parent = reply;
          const visited = new Set([m.id]);
          while (parent && !visited.has(parent.id) && depth < 6) {
            visited.add(parent.id);
            depth++;
            parent = d.messages.find((p) => p.id === parent!.replyTo);
          }
          return `<article class="board-message ${m.replyTo ? `reply reply-${depth}` : ''}" id="message-${esc(m.id)}"><div class="board-message-meta"><span class="board-kind">${esc(m.kind.toUpperCase())}</span>${m.conversation ? `<button data-conversation="${esc(m.conversation)}">${esc(m.author)} ${icon('route')}</button>` : `<strong>${esc(m.author)}</strong>`}<time>${esc(date(m.postedAt))}</time>${m.alert ? '<span class="board-alert">⚑ Alert</span>' : ''}</div>${reply ? `<p class="board-reply">Reply to ${esc(reply.author)} · ${esc(reply.title ?? reply.kind)} · <button data-message="${esc(reply.id)}">View message</button></p>` : ''}${m.title ? `<h4>${esc(m.title)}</h4>` : ''}${prose(m.body)}${m.mentions.length ? `<p class="board-mentions">Mentions ${m.mentions.map((name) => `@${esc(name)}`).join(' ')}</p>` : ''}${decision ? `<div class="board-decision"><strong>${decision.approved ? 'Approved' : 'Refused'}</strong><span>${esc(decision.reason)}</span>${decision.child ? `<button data-topic="${esc(decision.child)}">${icon('layers')}Inspect child topic</button>` : ''}</div>` : m.kind === 'request' ? '<p class="board-subtitle">Awaiting a decision</p>' : ''}</article>`;
        })
        .join('') || '<p class="board-subtitle">No messages yet.</p>'
    }`;
  content.scrollTop =
    previous === selected ? (nearEnd ? content.scrollHeight : scroll) : 0;
}
for (const next of ['board', 'swarm'] as const) {
  $(`#view-${next}`).addEventListener('click', () =>
    background(
      action({
        action: 'board-view',
        view: next,
        ...(state.board.project === undefined
          ? {}
          : { project: state.board.project }),
      }),
    ),
  );
  $(`#view-${next}`).addEventListener('keydown', (event) => {
    if (['ArrowLeft', 'ArrowRight'].includes(event.key)) {
      event.preventDefault();
      const other = next === 'board' ? 'swarm' : 'board';
      $(`#view-${other}`).focus();
      background(
        action({
          action: 'board-view',
          view: other,
          ...(state.board.project === undefined
            ? {}
            : { project: state.board.project }),
        }),
      );
    }
  });
}
$('#board-search').addEventListener('input', render);
$('#board-state').addEventListener('change', render);
$('#board-project').addEventListener('change', () => {
  requested = '';
  stamp = '';
  background(
    action({
      action: 'board-view',
      view,
      ...($<HTMLSelectElement>('#board-project').value
        ? { project: $<HTMLSelectElement>('#board-project').value }
        : {}),
    }),
  );
});
$('#board-refresh').addEventListener('click', () =>
  background(action({ action: 'board-refresh' })),
);
$('#board-more').addEventListener('click', () =>
  background(action({ action: 'board-more' })),
);
document.addEventListener('click', (event) => {
  const target = event.target as HTMLElement;
  const member = target.closest<HTMLElement>('[data-member]')?.dataset.member;
  if (member) {
    selectedMember = member;
    stamp = '';
    render();
  }
  const topic = target.closest<HTMLElement>('[data-topic]')?.dataset.topic;
  if (topic) {
    requested = topic;
    background(action({ action: 'board-topic', topic }));
  }
  const conversation = target.closest<HTMLElement>('[data-conversation]')
    ?.dataset.conversation;
  if (conversation)
    background(action({ action: 'board-trajectory', conversation }));
  const message =
    target.closest<HTMLElement>('[data-message]')?.dataset.message;
  if (message)
    document
      .getElementById(`message-${message}`)
      ?.scrollIntoView({ block: 'start', behavior: 'smooth' });
  const url = target.closest<HTMLElement>('[data-web-link]')?.dataset.webLink;
  if (url) {
    event.preventDefault();
    background(action({ action: 'open-link', url }));
  }
});
window.plowshare.subscribe(update);
background(
  window.plowshare.request({ action: 'bootstrap' }).then((reply) => {
    view = reply.boardView ?? 'board';
    initialized = true;
    update(reply.state);
  }),
);

window.addEventListener('workspace-refresh', () => $('#board-refresh').click());

const postDialog = $<HTMLDialogElement>('#board-post-dialog');
const postProject = $<HTMLSelectElement>('#post-project'),
  postTopic = $<HTMLSelectElement>('#post-topic'),
  postBody = $<HTMLTextAreaElement>('#post-body');
let postIdentity = '',
  postTopicsStamp = '',
  restoredPostKey = '',
  postError = '';
const postStorageKey = () =>
  `plowshare.desktop.board-post.v1:${JSON.stringify([state.base, state.handle])}`;
function persistPost() {
  try {
    localStorage.setItem(
      postStorageKey(),
      JSON.stringify({
        requestId: postIdentity,
        project: postProject.value,
        topic: postTopic.value,
        body: postBody.value,
      }),
    );
  } catch {
    /* Keep the request identity in memory. */
  }
}
function renderPosting() {
  if (!postDialog?.open || !state) return;
  const posting = state.board.posting,
    project = postProject.value;
  const options =
    '<option value="">Choose project</option>' +
    state.projects
      .map(
        (row) => `<option value="${esc(row.name)}">${esc(row.name)}</option>`,
      )
      .join('');
  if (postProject.innerHTML !== options) {
    postProject.innerHTML = options;
    postProject.value = project;
  }
  const rows =
    posting?.project === project
      ? posting.topics.filter((row) => row.topic.state !== 'closed')
      : [];
  const signature = JSON.stringify([project, rows]);
  if (signature !== postTopicsStamp) {
    postTopicsStamp = signature;
    const selected = postTopic.value || postTopic.dataset.draft || '';
    postTopic.innerHTML =
      '<option value="">Choose an open topic</option>' +
      rows
        .map(
          (row) =>
            `<option value="${esc(row.topic.id)}">${esc(row.topic.title)}</option>`,
        )
        .join('');
    postTopic.value = rows.some((row) => row.topic.id === selected)
      ? selected
      : (rows[0]?.topic.id ?? '');
  }
  const busy = !!posting?.busy;
  postProject.disabled = busy;
  postTopic.disabled = busy || !!posting?.loading;
  postBody.disabled = busy;
  $<HTMLButtonElement>('#post-submit').disabled =
    busy ||
    !state.connected ||
    !!posting?.loading ||
    !postProject.value ||
    !postTopic.value ||
    !postBody.value.trim();
  $<HTMLButtonElement>('#board-post-close').disabled = busy;
  $('#post-topics-more').hidden = !posting?.more;
  $<HTMLButtonElement>('#post-topics-more').disabled =
    busy || !!posting?.loading;
  $('#post-error').hidden = !(posting?.error || postError);
  $('#post-error').textContent = posting?.error || postError;
  $('#post-notice').textContent =
    posting?.notice ??
    (posting?.loading
      ? 'Loading topics…'
      : rows.length
        ? ''
        : 'No open topics loaded for this project.');
}
$('#board-post-open').addEventListener('click', () => {
  postDialog.showModal();
  renderPosting();
  if (restoredPostKey !== postStorageKey()) {
    restoredPostKey = postStorageKey();
    postIdentity = '';
    postBody.value = '';
    postProject.value = state.board.project ?? state.projects[0]?.name ?? '';
    try {
      const saved: unknown = JSON.parse(
        localStorage.getItem(postStorageKey()) ?? '{}',
      );
      if (
        isObject(saved) &&
        typeof saved.body === 'string' &&
        isObject(saved) &&
        typeof saved.project === 'string' &&
        state.projects.some((row) => row.name === saved.project)
      ) {
        postBody.value = saved.body;
        postProject.value = saved.project;
        postTopic.dataset.draft =
          typeof saved.topic === 'string' ? saved.topic : '';
        postIdentity =
          typeof saved.requestId === 'string' ? saved.requestId : '';
      }
    } catch {
      /* Begin a new draft. */
    }
  }
  if (postProject.value)
    background(
      action({ action: 'board-post-topics', project: postProject.value }),
    );
  renderPosting();
});
postProject.addEventListener('change', () => {
  postIdentity = '';
  postTopicsStamp = '';
  postTopic.dataset.draft = '';
  persistPost();
  if (postProject.value)
    background(
      action({ action: 'board-post-topics', project: postProject.value }),
    );
  renderPosting();
});
postTopic.addEventListener('change', () => {
  postIdentity = '';
  persistPost();
  renderPosting();
});
postBody.addEventListener('input', () => {
  postIdentity = '';
  persistPost();
  renderPosting();
});
$('#post-topics-more').addEventListener('click', () =>
  background(
    action({
      action: 'board-post-topics',
      project: postProject.value,
      more: true,
    }),
  ),
);
$('#board-post-close').addEventListener('click', () => postDialog.close());
postDialog.addEventListener('cancel', (event) => {
  if (state.board.posting?.busy) event.preventDefault();
});
$('#board-post-form').addEventListener(
  'submit',
  ownedEvent(async (event: SubmitEvent) => {
    event.preventDefault();
    if ($<HTMLButtonElement>('#post-submit').disabled) return;
    postError = '';
    postIdentity ||= crypto.randomUUID();
    persistPost();
    const key = postStorageKey();
    try {
      await window.plowshare.request({
        action: 'board-post',
        project: postProject.value,
        topic: postTopic.value,
        body: postBody.value,
        requestId: postIdentity,
      });
      if (key === postStorageKey() && state.board.posting?.notice) {
        localStorage.removeItem(key);
        postIdentity = '';
        postBody.value = '';
      }
    } catch (error) {
      postError = error instanceof Error ? error.message : errorMessage(error);
    }
    renderPosting();
  }),
);

const createDialog = $<HTMLDialogElement>('#board-create-dialog');
const createProject = $<HTMLSelectElement>('#create-project'),
  createSwarm = $<HTMLSelectElement>('#create-swarm'),
  createTitle = $<HTMLInputElement>('#create-title'),
  createLabel = $<HTMLInputElement>('#create-label'),
  createBody = $<HTMLTextAreaElement>('#create-body'),
  createBudget = $<HTMLInputElement>('#create-budget');
let createIdentity = '',
  restoredCreateKey = '',
  createError = '',
  selectedCreateSwarm = '';
const createStorageKey = () =>
  `plowshare.desktop.board-create.v1:${JSON.stringify([state.base, state.handle])}`;
function persistCreate() {
  try {
    localStorage.setItem(
      createStorageKey(),
      JSON.stringify({
        requestId: createIdentity,
        project: createProject.value,
        swarm: selectedCreateSwarm,
        title: createTitle.value,
        label: createLabel.value,
        body: createBody.value,
        budget: createBudget.value,
      }),
    );
  } catch {
    /* The current draft still stays in memory. */
  }
}
function renderOpening() {
  if (!createDialog?.open || !state) return;
  const opening = state.board.opening,
    project = createProject.value;
  const options =
    '<option value="">Choose project</option>' +
    state.projects
      .map(
        (row) => `<option value="${esc(row.name)}">${esc(row.name)}</option>`,
      )
      .join('');
  if (createProject.innerHTML !== options) {
    createProject.innerHTML = options;
    createProject.value = project;
  }
  const reading = state.board.types?.[createProject.value];
  const choices = reading?.value?.types ?? [];
  createSwarm.innerHTML =
    '<option value="">Choose swarm type</option>' +
    choices
      .map(
        (type) =>
          `<option value="${esc(type.name)}" ${type.selection ? '' : 'disabled'}>${esc(type.name)} · ${type.members.length} members · ${type.budget} calls</option>`,
      )
      .join('');
  // Keep a retained draft's selector even when the definition disappears; receipt recovery
  // must send the same identity rather than silently selecting another type.
  if (
    selectedCreateSwarm &&
    !choices.some((type) => type.name === selectedCreateSwarm)
  )
    createSwarm.innerHTML += `<option value="${esc(selectedCreateSwarm)}">${esc(selectedCreateSwarm)} (retained draft)</option>`;
  createSwarm.value = selectedCreateSwarm;
  $('#create-swarm-status').textContent = reading?.loading
    ? 'Loading swarm types…'
    : (reading?.error ??
      (!choices.length
        ? 'No swarm types are available for this project.'
        : (choices.find((type) => type.name === selectedCreateSwarm)?.selection
            ?.description ?? 'Choose a swarm type for this topic.')));
  $<HTMLButtonElement>('#create-swarm-refresh').disabled =
    !!opening?.busy ||
    !state.connected ||
    !createProject.value ||
    !!reading?.loading;
  const busy = !!opening?.busy;
  for (const field of [
    createProject,
    createSwarm,
    createTitle,
    createLabel,
    createBody,
    createBudget,
  ])
    field.disabled = busy;
  $<HTMLButtonElement>('#board-create-close').disabled = busy;
  $<HTMLButtonElement>('#create-submit').disabled =
    busy ||
    !state.connected ||
    !createProject.value ||
    !selectedCreateSwarm ||
    !createTitle.value.trim() ||
    !createLabel.value.trim() ||
    !createBody.value.trim() ||
    !createBudget.validity.valid;
  $('#create-error').hidden = !(opening?.error || createError);
  $('#create-error').textContent = opening?.error || createError;
  $('#create-notice').textContent = busy ? 'Creating topic…' : '';
}
$('#board-create-open').addEventListener('click', () => {
  createDialog.showModal();
  renderOpening();
  if (restoredCreateKey !== createStorageKey()) {
    restoredCreateKey = createStorageKey();
    createIdentity = '';
    selectedCreateSwarm = '';
    createProject.value = state.board.project ?? state.projects[0]?.name ?? '';
    createTitle.value = '';
    createBody.value = '';
    createLabel.value = 'Discussion';
    createBudget.value = '';
    try {
      const saved: unknown = JSON.parse(
        localStorage.getItem(createStorageKey()) ?? '{}',
      );
      if (
        isObject(saved) &&
        typeof saved.project === 'string' &&
        state.projects.some((row) => row.name === saved.project)
      ) {
        createProject.value = saved.project;
        selectedCreateSwarm =
          typeof saved.swarm === 'string' ? saved.swarm : '';
        for (const [field, name] of [
          [createTitle, 'title'],
          [createLabel, 'label'],
          [createBody, 'body'],
          [createBudget, 'budget'],
        ] as const)
          if (typeof saved[name] === 'string') field.value = saved[name];
        createIdentity =
          typeof saved.requestId === 'string' ? saved.requestId : '';
      }
    } catch {
      /* Begin a new draft. */
    }
  }
  background(loadCreateSwarms());
  renderOpening();
  createTitle.focus();
});
for (const field of [
  createProject,
  createSwarm,
  createTitle,
  createLabel,
  createBody,
  createBudget,
])
  field.addEventListener('input', () => {
    createIdentity = '';
    createError = '';
    if (field === createSwarm) selectedCreateSwarm = createSwarm.value;
    if (field === createProject) {
      selectedCreateSwarm = '';
      background(loadCreateSwarms());
    }
    persistCreate();
    renderOpening();
  });
async function loadCreateSwarms() {
  if (!createProject.value || !state.connected) return;
  try {
    await window.plowshare.request({
      action: 'board-swarm-types',
      project: createProject.value,
    });
  } catch (error) {
    createError = errorMessage(error);
  }
  renderOpening();
}
$('#create-swarm-refresh').addEventListener('click', () => {
  background(loadCreateSwarms());
});
$('#board-create-close').addEventListener('click', () => createDialog.close());
createDialog.addEventListener('cancel', (event) => {
  if (state.board.opening?.busy) event.preventDefault();
});
$('#board-create-form').addEventListener(
  'submit',
  ownedEvent(async (event: SubmitEvent) => {
    event.preventDefault();
    if ($<HTMLButtonElement>('#create-submit').disabled) return;
    createError = '';
    createIdentity ||= crypto.randomUUID();
    persistCreate();
    const key = createStorageKey();
    try {
      const reply = await window.plowshare.request({
        action: 'board-create',
        project: createProject.value,
        swarm: selectedCreateSwarm,
        title: createTitle.value,
        label: createLabel.value,
        body: createBody.value,
        requestId: createIdentity,
        ...(createBudget.value
          ? { maxModelCalls: Number(createBudget.value) }
          : {}),
      });
      if (key === createStorageKey() && reply.state.board.opening?.notice) {
        localStorage.removeItem(key);
        createIdentity = '';
        createTitle.value = '';
        createBody.value = '';
        $<HTMLInputElement>('#board-search').value = '';
        $<HTMLSelectElement>('#board-state').value = 'all';
        createDialog.close();
        render();
      }
    } catch (error) {
      createError =
        error instanceof Error ? error.message : errorMessage(error);
    }
    renderOpening();
  }),
);

const retryDialog = $<HTMLDialogElement>('#board-retry-dialog');
const retryLimit = $<HTMLInputElement>('#retry-limit');
let retryDraft:
  | {
      project: string;
      topic: string;
      member: string;
      requestId: string;
      maxTurns: number;
    }
  | undefined;
let retryError = '';
const retryStorageKey = () =>
  `plowshare.desktop.board-retry.v1:${JSON.stringify([state.base, state.handle])}`;
function storedRetry() {
  try {
    const value: unknown = JSON.parse(
      localStorage.getItem(retryStorageKey()) ?? 'null',
    );
    if (
      isObject(value) &&
      ['project', 'topic', 'member', 'requestId'].every(
        (k) => typeof value[k] === 'string',
      ) &&
      typeof value.maxTurns === 'number' &&
      Number.isSafeInteger(value.maxTurns) &&
      value.maxTurns > 0
    )
      return value as NonNullable<typeof retryDraft>;
  } catch {
    /* Keep the request in memory when storage is unavailable. */
  }
  return undefined;
}
function saveRetry() {
  try {
    localStorage.setItem(retryStorageKey(), JSON.stringify(retryDraft));
  } catch {
    /* Keep the identity in memory. */
  }
}
function renderRetry() {
  const pending = storedRetry();
  $('#board-retry-pending').hidden = !pending;
  $<HTMLButtonElement>('#board-retry-pending').disabled =
    !state.connected || !!state.board.retrying?.busy;
  if (!retryDialog.open || !retryDraft) return;
  const result =
    state.board.retrying?.requestId === retryDraft.requestId
      ? state.board.retrying
      : undefined;
  const busy = !!result?.busy;
  $('#retry-description').textContent =
    `Continue ${retryDraft.member} on its existing conversation. This retry uses the topic’s remaining shared model call allowance; it does not add budget.`;
  retryLimit.disabled = busy || !!pending;
  $<HTMLButtonElement>('#retry-submit').disabled = busy || !state.connected;
  $('#retry-submit').textContent = busy ? 'Queuing…' : 'Retry member';
  $<HTMLButtonElement>('#board-retry-close').disabled = busy;
  $('#retry-error').hidden = !(retryError || result?.error);
  $('#retry-error').textContent = retryError || result?.error || '';
  $('#retry-notice').textContent = result?.notice ?? '';
}
function openRetry(draft: NonNullable<typeof retryDraft>) {
  retryDraft = draft;
  retryError = '';
  retryLimit.value = String(draft.maxTurns);
  retryDialog.showModal();
  renderRetry();
  retryLimit.focus();
}
document.addEventListener('click', (event) => {
  const button = (event.target as HTMLElement).closest<HTMLElement>(
    '[data-retry-member]',
  );
  if (!button || !state.connected || state.board.retrying?.busy) return;
  const pending = storedRetry();
  if (pending) {
    openRetry(pending);
    return;
  }
  openRetry({
    project: button.dataset.retryProject!,
    topic: button.dataset.retryTopic!,
    member: button.dataset.retryMember!,
    requestId: crypto.randomUUID(),
    maxTurns: 24,
  });
});
$('#board-retry-pending').addEventListener('click', () => {
  const pending = storedRetry();
  if (pending) openRetry(pending);
});
$('#board-retry-close').addEventListener('click', () => retryDialog.close());
retryDialog.addEventListener('cancel', (event) => {
  if (state.board.retrying?.busy) event.preventDefault();
});
retryLimit.addEventListener('input', () => {
  if (!retryDraft) return;
  // A submitted request keeps its payload until the server confirms it.
  if (storedRetry()) {
    retryLimit.value = String(retryDraft.maxTurns);
    return;
  }
  retryDraft = {
    ...retryDraft,
    requestId: crypto.randomUUID(),
    maxTurns: Number(retryLimit.value),
  };
});
$('#board-retry-form').addEventListener(
  'submit',
  ownedEvent(async (event: SubmitEvent) => {
    event.preventDefault();
    if (!retryDraft || state.board.retrying?.busy) return;
    const key = retryStorageKey(),
      reconcile = !!storedRetry();
    retryError = '';
    saveRetry();
    try {
      const reply = await window.plowshare.request({
        action: 'board-retry',
        ...retryDraft,
        reconcile,
      });
      update(reply.state);
      if (key === retryStorageKey() && reply.state.board.retrying?.notice) {
        localStorage.removeItem(key);
        retryDraft = undefined;
        retryDialog.close();
        render();
      }
    } catch (error) {
      const result = state.board.retrying;
      if (
        key === retryStorageKey() &&
        (result?.refused || result?.requestId !== retryDraft?.requestId)
      )
        localStorage.removeItem(key);
      retryError = error instanceof Error ? error.message : errorMessage(error);
      renderRetry();
    }
  }),
);
