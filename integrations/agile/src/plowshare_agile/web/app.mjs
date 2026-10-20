import {
  statuses,
  kinds,
  priorities,
  labels,
  decodeSnapshot,
  decodeDocument,
  decodeResult,
  replaceDocument,
  emptyFilters,
  visibleTickets,
  epicProgress,
  topicTags,
  filterCount,
  bodyForSave,
  requestIdentity,
  uuid,
} from './model.mjs';
import { PendingReads } from './reads.mjs';

const $ = (id) => document.getElementById(id);
const state = {
  snapshot: null,
  filters: emptyFilters(),
  view: 'board',
  csrf: '',
  project: '',
  selected: null,
  dirty: new Set(),
  pending: null,
  busy: false,
  generation: 0,
  createKind: 'ticket',
};

function node(tag, content, className) {
  const result = document.createElement(tag);
  if (content !== undefined) result.textContent = content;
  if (className) result.className = className;
  return result;
}
function on(element, event, action) {
  element.addEventListener(event, (e) => {
    try {
      Promise.resolve(action(e)).catch(report);
    } catch (error) {
      report(error);
    }
  });
}
function notice(message, error = false) {
  $('status').textContent = message;
  $('status').hidden = !message;
  $('status').dataset.kind = error ? 'error' : 'success';
}
function report(error) {
  // Logout and confirmed writes deliberately cancel superseded reads.
  if (error?.name === 'AbortError') return;
  const message =
    error instanceof Error
      ? error.message
      : 'The workspace could not complete that action.';
  notice(message, true);
  if ($('createDialog').open) $('createError').textContent = message;
}
class ApiError extends Error {
  constructor(status, value) {
    const conflict = value?.code === 'CONFLICT';
    const message = conflict
      ? 'This document changed. Your edits are still here. Reload and review before saving again.'
      : value?.error === 'transport'
        ? 'The connection was interrupted. Check the saved request before saving again.'
        : typeof value?.message === 'string'
          ? value.message
          : status === 401
            ? 'Your workspace session has ended. Sign in again.'
            : status === 403
              ? 'This action is not permitted for the configured document owner.'
              : 'The workspace could not complete that action.';
    super(message);
    this.status = status;
    this.pending = value?.pending !== false;
  }
}
async function api(path, { method = 'GET', payload, requestId, signal } = {}) {
  const headers = { Accept: 'application/json' };
  if (payload !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && state.csrf) headers['X-Agile-CSRF'] = state.csrf;
  if (requestId) headers['X-Agile-Request'] = requestId;
  let response;
  try {
    response = await fetch(path, {
      method,
      headers,
      credentials: 'same-origin',
      body: payload === undefined ? undefined : JSON.stringify(payload),
      signal,
    });
  } catch (error) {
    if (error?.name === 'AbortError') throw error;
    throw new Error(
      'The app connection was interrupted. Any save in progress still needs checking.',
    );
  }
  let data;
  try {
    data = await response.json();
  } catch (error) {
    if (error?.name === 'AbortError') throw error;
    throw new Error(
      'The app returned an unreadable reply. Any save in progress still needs checking.',
    );
  }
  if (!response.ok) throw new ApiError(response.status, data);
  return data;
}
const reads = new PendingReads((path, signal) => api(path, { signal }));
function pendingKey() {
  return `plowshare-agile-pending:${state.project}`;
}
function loadPending() {
  const saved = localStorage.getItem(pendingKey());
  if (!saved) return null;
  const value = JSON.parse(saved);
  return { id: uuid(value.id) };
}
function setPending(value) {
  if (value) localStorage.setItem(pendingKey(), JSON.stringify(value));
  else localStorage.removeItem(pendingKey());
  state.pending = value;
  updateBusy();
}
function updateBusy() {
  for (const button of document.querySelectorAll('[data-write]'))
    button.disabled = state.busy || !!state.pending || !state.snapshot;
  $('recover').disabled = state.busy;
  for (const control of document.querySelectorAll(
    '#metadataForm input, #metadataForm select, #metadataForm textarea, .membership-form input, #createForm input, #createForm select, #createForm textarea, #cancelCreate, #cancelCreateBottom, #logout, #reloadDetail, #closeDetail',
  ))
    control.disabled = state.busy;
  $('pending').hidden = !state.pending || state.busy;
  $('pendingText').textContent = state.pending
    ? `Your edits are preserved. Check request ${state.pending.id.slice(0, 8)} before another save.`
    : '';
}
async function mutate(path, payload) {
  if (!state.snapshot)
    throw new Error('Load the board before starting a save.');
  if (state.pending || state.busy)
    throw new Error('Check the saved request before starting another save.');
  const id = requestIdentity(crypto);
  setPending({ id }); // Retain identity before sending; no automatic mutation retry.
  return sendMutation(path, { ...payload, requestId: id }, id);
}
async function sendMutation(path, payload, id) {
  state.busy = true;
  updateBusy();
  notice('Saving…');
  try {
    const result = decodeResult(
      await api(path, { method: 'POST', payload, requestId: id }),
    );
    if (result.request_id !== id)
      throw new Error(
        'The save returned another request identity. Check the retained request.',
      );
    // A pre-write GET cannot be reused as post-write evidence. Invalidate only
    // the collection and this resource; unrelated document reads can complete.
    state.generation += 1;
    reads.invalidate([
      '/api/board',
      ...['ticket', 'epic'].flatMap((kind) => [
        `/api/documents/${kind}/${result.resource}`,
        `/api/documents/${kind}/${result.resource}?body=1`,
      ]),
    ]);
    setPending(null);
    notice('Saved.');
    return result;
  } catch (error) {
    if (error instanceof ApiError && !error.pending) setPending(null);
    throw error;
  } finally {
    state.busy = false;
    updateBusy();
  }
}
async function refreshBoard() {
  const generation = ++state.generation;
  const snapshot = decodeSnapshot(await reads.read('/api/board'));
  if (generation !== state.generation) return;
  applySnapshot(snapshot);
}
function applySnapshot(snapshot) {
  state.snapshot = snapshot;
  $('loading').hidden = true;
  if (state.selected?.document) {
    const current = [...snapshot.tickets, ...snapshot.epics].find(
      (v) => v.resource === state.selected.document.resource,
    );
    if (current?.revision === state.selected.document.revision)
      state.selected.document.tags = current.tags;
  }
  render();
}
async function refreshDocument(kind, resource) {
  // Keep the single-writer UI fenced until its committed replacement is read.
  // A successful receipt alone does not contain the actual fields/tags.
  state.busy = true;
  updateBusy();
  const generation = ++state.generation;
  try {
    const document = decodeDocument(
      await reads.read(`/api/documents/${kind}/${resource}`),
      kind,
    );
    if (generation !== state.generation) return;
    if (document.resource !== resource)
      throw new Error('The read returned another document.');
    applySnapshot(replaceDocument(state.snapshot, kind, document));
  } finally {
    state.busy = false;
    updateBusy();
  }
}
function toggle(values, value) {
  return values.includes(value)
    ? values.filter((v) => v !== value)
    : [...values, value];
}
function epicName(id) {
  return (
    state.snapshot.epics.find((epic) => epic.resource === id)?.fields.title ||
    'Unavailable epic'
  );
}
function chip(label, remove) {
  const button = node('button', `${label} ×`, 'chip');
  on(button, 'click', remove);
  return button;
}
function fillSelect(id, values, first, selected) {
  const select = $(id);
  select.replaceChildren();
  const initial = node('option', first);
  initial.value = '';
  select.append(initial);
  for (const value of values) {
    const option = node('option', value);
    option.value = value;
    select.append(option);
  }
  if (selected && !values.includes(selected)) {
    const option = node('option', selected);
    option.value = selected;
    select.append(option);
  }
  select.value = selected;
}
function render() {
  if (!state.snapshot) return;
  const { tickets, epics } = state.snapshot;
  const shown = visibleTickets(tickets, state.filters);
  $('boardNav').classList.toggle('active', state.view === 'board');
  $('epicsNav').classList.toggle('active', state.view === 'epics');
  for (const name of ['board', 'epics'])
    $(name === 'board' ? 'boardNav' : 'epicsNav').setAttribute(
      'aria-current',
      state.view === name ? 'page' : 'false',
    );
  $('viewLabel').textContent = state.view.toUpperCase();
  $('pageTitle').textContent =
    state.view === 'board' ? 'The work ahead' : 'Ideas, connected';
  $('pageSubtitle').textContent =
    state.view === 'board'
      ? 'A clear view of what matters next.'
      : 'Shared outcomes, with room for work to overlap.';
  $('newDocument').textContent =
    state.view === 'board' ? '+ New ticket' : '+ New epic';
  $('board').hidden = state.view !== 'board';
  $('epics').hidden = state.view !== 'epics';
  $('ticketCount').textContent = `${shown.length} of ${tickets.length} tickets`;
  $('clearFilters').hidden = filterCount(state.filters) === 0;
  fillSelect(
    'assigneeFilter',
    [...new Set(tickets.map((v) => v.fields.assignee).filter(Boolean))].sort(),
    'Anyone',
    state.filters.assignee,
  );
  fillSelect(
    'iterationFilter',
    [...new Set(tickets.map((v) => v.fields.iteration).filter(Boolean))].sort(),
    'All iterations',
    state.filters.iteration,
  );
  $('epicFilters').replaceChildren();
  if (!epics.length)
    $('epicFilters').append(
      node('p', 'No epics yet. Start with an outcome.', 'sidebar-hint'),
    );
  for (const epic of epics) {
    const button = node('button', undefined, 'epic-filter');
    const selected = state.filters.epics.includes(epic.resource);
    button.setAttribute('aria-pressed', String(selected));
    button.append(
      node('span', selected ? '✓' : '◇', 'epic-check'),
      node('span', epic.fields.title),
      node(
        'small',
        String(
          shown.filter((v) => v.tags.includes(`epic:${epic.resource}`)).length,
        ),
      ),
    );
    on(button, 'click', () => {
      state.filters.epics = toggle(state.filters.epics, epic.resource);
      render();
    });
    $('epicFilters').append(button);
  }
  $('tagFilters').replaceChildren();
  for (const tag of topicTags(tickets)) {
    const label = node('label'),
      input = node('input');
    input.type = 'checkbox';
    input.checked = state.filters.tags.includes(tag);
    on(input, 'change', () => {
      state.filters.tags = toggle(state.filters.tags, tag);
      render();
    });
    label.append(input, node('span', tag));
    $('tagFilters').append(label);
  }
  if (!topicTags(tickets).length)
    $('tagFilters').append(node('p', 'No topic tags yet.', 'muted'));
  $('tagCount').textContent = state.filters.tags.length
    ? `(${state.filters.tags.length})`
    : '';
  $('filterChips').replaceChildren();
  for (const id of state.filters.epics)
    $('filterChips').append(
      chip(epicName(id), () => {
        state.filters.epics = toggle(state.filters.epics, id);
        render();
      }),
    );
  for (const tag of state.filters.tags)
    $('filterChips').append(
      chip(tag, () => {
        state.filters.tags = toggle(state.filters.tags, tag);
        render();
      }),
    );
  for (const key of ['kind', 'assignee', 'iteration', 'search'])
    if (state.filters[key])
      $('filterChips').append(
        chip(state.filters[key], () => {
          state.filters[key] = '';
          $(
            {
              kind: 'kindFilter',
              assignee: 'assigneeFilter',
              iteration: 'iterationFilter',
              search: 'search',
            }[key],
          ).value = '';
          render();
        }),
      );
  renderColumns(shown);
  renderEpics(shown);
  updateBusy();
}
function renderColumns(tickets) {
  $('board').replaceChildren();
  for (const status of statuses) {
    const column = node('section', undefined, `column status-${status}`);
    const members = tickets.filter((v) => v.fields.status === status);
    const heading = node('div', undefined, 'column-heading');
    heading.append(
      node('span', '', 'status-dot'),
      node('h2', labels[status]),
      node('span', String(members.length), 'count'),
    );
    const add = node('button', '+', 'icon-button');
    add.setAttribute('aria-label', `Create ticket in ${labels[status]}`);
    on(add, 'click', () => openCreate('ticket', status));
    heading.append(add);
    column.append(heading);
    if (!members.length)
      column.append(node('p', 'No tickets here', 'empty-column'));
    for (const ticket of members) {
      const card = node('article', undefined, 'ticket-card');
      const meta = node('div', undefined, 'card-meta');
      meta.append(
        node(
          'span',
          ticket.fields.kind,
          `kind-label kind-${ticket.fields.kind}`,
        ),
        node('span', ticket.resource.slice(0, 8)),
      );
      const title = node('button', ticket.fields.title, 'card-title');
      on(title, 'click', () => openDocument('ticket', ticket.resource));
      card.append(meta, title);
      const tags = node('div', undefined, 'card-tags');
      for (const tag of ticket.tags.slice(0, 3))
        tags.append(
          node(
            'span',
            tag.startsWith('epic:') ? epicName(tag.slice(5)) : tag,
            'mini-tag',
          ),
        );
      card.append(tags);
      const footer = node('div', undefined, 'card-footer');
      footer.append(
        node(
          'span',
          `${ticket.fields.priority === 'high' ? '↑' : '−'} ${ticket.fields.priority}`,
          `priority priority-${ticket.fields.priority}`,
        ),
        node('span', ticket.fields.assignee || 'Unassigned', 'assignee'),
      );
      const move = node('select');
      move.setAttribute('aria-label', `Move ${ticket.fields.title}`);
      move.dataset.write = '';
      for (const value of statuses) {
        const option = node('option', labels[value]);
        option.value = value;
        move.append(option);
      }
      move.value = status;
      on(move, 'change', async () => {
        const fields = { ...ticket.fields, status: move.value };
        move.value = status;
        await mutate(`/api/documents/ticket/${ticket.resource}`, {
          expectedRevision: ticket.revision,
          fields,
          body: null,
        });
        await refreshDocument('ticket', ticket.resource);
      });
      footer.append(move);
      card.append(footer);
      column.append(card);
    }
    $('board').append(column);
  }
}
function renderEpics(shown) {
  $('epics').replaceChildren();
  if (!state.snapshot.epics.length) {
    const empty = node('div', undefined, 'empty-state');
    empty.append(
      node('span', '◇', 'empty-symbol'),
      node('h2', 'Give related work an outcome.'),
      node(
        'p',
        'Create an epic, then connect as many tickets as it needs.',
        'muted',
      ),
    );
    const add = node('button', '+ Create first epic', 'primary');
    on(add, 'click', () => openCreate('epic'));
    empty.append(add);
    $('epics').append(empty);
  }
  for (const epic of state.snapshot.epics) {
    const progress = epicProgress(state.snapshot.tickets, epic.resource);
    const card = node('article', undefined, 'epic-card');
    card.append(node('span', '◇', 'epic-symbol'));
    const title = node('button', epic.fields.title, 'epic-title');
    on(title, 'click', () => openDocument('epic', epic.resource));
    card.append(title);
    card.append(
      node(
        'p',
        progress.total
          ? `${progress.done} of ${progress.total} tickets done`
          : 'No tickets yet',
        'muted',
      ),
    );
    const bar = node('progress');
    bar.max = progress.total || 1;
    bar.value = progress.done;
    bar.setAttribute('aria-label', `${epic.fields.title} progress`);
    card.append(bar);
    const footer = node('div', undefined, 'epic-footer');
    footer.append(
      node(
        'span',
        `${shown.filter((v) => v.tags.includes(`epic:${epic.resource}`)).length} match current filters`,
        'fine-print',
      ),
    );
    const select = node('button', 'View tickets →', 'text-button');
    on(select, 'click', () => {
      state.filters.epics = [epic.resource];
      state.view = 'board';
      render();
    });
    footer.append(select);
    card.append(footer);
    $('epics').append(card);
  }
}
function discard() {
  return (
    !state.dirty.size ||
    window.confirm('Discard the unsaved edits in this document?')
  );
}
function closeDetail() {
  if (state.busy) return false;
  if (!discard()) return false;
  state.selected = null;
  state.dirty.clear();
  $('detail').hidden = true;
  return true;
}
function inputField(form, name, label, value, { options, max = 160 } = {}) {
  const wrapper = node('label', label),
    input = node(options ? 'select' : 'input');
  input.name = name;
  if (options)
    for (const option of options) {
      const entry = node('option', labels[option] || option);
      entry.value = option;
      input.append(entry);
    }
  else input.maxLength = max;
  input.value = value || '';
  wrapper.append(input);
  form.append(wrapper);
  return input;
}
function value(form, name) {
  const input = form.elements.namedItem(name);
  if (!(
    input instanceof HTMLInputElement ||
    input instanceof HTMLSelectElement ||
    input instanceof HTMLTextAreaElement
  ))
    throw new Error('Document editor is unavailable.');
  return input.value;
}
function formFields(form, kind) {
  const title = value(form, 'title');
  return kind === 'ticket'
    ? {
        title,
        kind: value(form, 'kind'),
        status: form.elements.namedItem('status')
          ? value(form, 'status')
          : form.dataset.status || 'backlog',
        priority: value(form, 'priority'),
        assignee: value(form, 'assignee') || null,
        iteration: value(form, 'iteration') || null,
      }
    : { title };
}
async function openDocument(
  kind,
  resource,
  { force = false, reload = false } = {},
) {
  if (state.busy) throw new Error('Wait for the current save to finish.');
  if (!force && !discard()) return;
  state.selected = {
    kind,
    resource,
    document: null,
    body: null,
    bodyDirty: false,
  };
  state.dirty.clear();
  $('detail').hidden = false;
  $('detailKind').textContent = kind.toUpperCase();
  $('detailContent').replaceChildren(node('p', 'Opening document…', 'muted'));
  const selection = state.selected;
  const cached = state.snapshot?.[kind === 'ticket' ? 'tickets' : 'epics'].find(
    (document) => document.resource === resource,
  );
  // All metadata is already on the board. Reusing visible fields discloses no
  // new data; body reads, explicit Reload and every write still go to the server.
  const selected = decodeDocument(
    !reload && cached
      ? cached
      : await reads.read(`/api/documents/${kind}/${resource}`),
    kind,
  );
  if (state.selected !== selection) return;
  state.selected.document = selected;
  renderDetail();
}
function renderDetail() {
  const { kind, document: doc } = state.selected;
  const container = $('detailContent');
  container.replaceChildren();
  const heading = node('h2', doc.fields.title);
  heading.tabIndex = -1;
  const identity = node(
    'p',
    `${doc.resource.slice(0, 8)} · ${doc.owner}`,
    'fine-print',
  );
  const revision = node(
    'p',
    `Revision ${doc.revision.slice(0, 8)}`,
    'revision-label',
  );
  revision.id = 'detailRevision';
  container.append(heading, identity, revision);
  const form = node('form');
  form.id = 'metadataForm';
  inputField(form, 'title', 'Title', doc.fields.title).required = true;
  if (kind === 'ticket') {
    const grid = node('div', undefined, 'field-grid');
    inputField(grid, 'kind', 'Kind', doc.fields.kind, { options: kinds });
    inputField(grid, 'status', 'Status', doc.fields.status, {
      options: statuses,
    });
    inputField(grid, 'priority', 'Priority', doc.fields.priority, {
      options: priorities,
    });
    inputField(grid, 'assignee', 'Assignee', doc.fields.assignee, { max: 120 });
    inputField(grid, 'iteration', 'Iteration', doc.fields.iteration, {
      max: 80,
    });
    form.append(grid);
  }
  const bodyArea = node('section', undefined, 'body-section');
  bodyArea.append(
    node('h3', kind === 'ticket' ? 'Description & notes' : 'Outcome & scope'),
  );
  const loadBody = node('button', 'Read / edit document', 'secondary');
  loadBody.type = 'button';
  const bodyContent = node('div');
  bodyArea.append(loadBody, bodyContent);
  form.append(bodyArea);
  on(loadBody, 'click', async () => {
    const selection = state.selected;
    const selectedResource = doc.resource;
    const loaded = decodeDocument(
      await reads.read(`/api/documents/${kind}/${selectedResource}?body=1`),
      kind,
    );
    if (state.selected !== selection) return;
    if (loaded.revision !== state.selected.document.revision)
      throw new Error(
        'A newer document revision is available. Reload and review before editing its body.',
      );
    state.selected.body = loaded.body;
    bodyContent.replaceChildren();
    loadBody.hidden = true;
    let preview = globalThis.AgileMarkdown.render(loaded.body || '');
    bodyContent.append(preview);
    const label = node('label', 'Edit Markdown'),
      textarea = node('textarea');
    textarea.name = 'body';
    textarea.rows = 12;
    textarea.maxLength = 16384;
    textarea.value = loaded.body || '';
    label.append(textarea);
    bodyContent.append(label);
    on(textarea, 'input', () => {
      state.dirty.add('fields');
      state.selected.bodyDirty = true;
      const next = globalThis.AgileMarkdown.render(textarea.value);
      preview.replaceWith(next);
      preview = next;
    });
  });
  on(form, 'input', () => state.dirty.add('fields'));
  const save = node('button', 'Save document', 'primary');
  save.type = 'submit';
  save.dataset.write = '';
  form.append(save);
  on(form, 'submit', async (event) => {
    event.preventDefault();
    const selected = state.selected;
    const fields = formFields(form, kind);
    const body = form.elements.namedItem('body') ? value(form, 'body') : null;
    const changedBody = bodyForSave(selected.body, body, selected.bodyDirty);
    const result = await mutate(`/api/documents/${kind}/${doc.resource}`, {
      expectedRevision: selected.document.revision,
      fields,
      body: changedBody,
    });
    if (state.selected !== selected) return;
    selected.document = {
      ...selected.document,
      revision: result.revision,
      fields,
    };
    if (changedBody !== null) selected.body = changedBody;
    selected.bodyDirty = false;
    state.dirty.delete('fields');
    heading.textContent = fields.title;
    revision.textContent = `Revision ${result.revision.slice(0, 8)}`;
    await refreshDocument(kind, doc.resource);
  });
  container.append(form);
  if (kind === 'ticket') renderMembership(container, doc);
  updateBusy();
  heading.focus();
}
function renderMembership(container, doc) {
  const epicsForm = node('form', undefined, 'membership-form');
  epicsForm.append(
    node('h3', 'Epics'),
    node('p', 'Connect this ticket to more than one outcome.', 'fine-print'),
  );
  for (const epic of state.snapshot.epics) {
    const label = node('label', undefined, 'checkbox-label'),
      input = node('input');
    input.type = 'checkbox';
    input.value = epic.resource;
    input.name = 'epic';
    input.checked = doc.tags.includes(`epic:${epic.resource}`);
    label.append(input, node('span', epic.fields.title));
    epicsForm.append(label);
  }
  const unresolved = doc.tags.filter(
    (tag) =>
      tag.startsWith('epic:') &&
      !state.snapshot.epics.some((epic) => tag === `epic:${epic.resource}`),
  );
  if (unresolved.length)
    epicsForm.append(
      node(
        'p',
        `${unresolved.length} unavailable epic membership(s) retained.`,
        'fine-print',
      ),
    );
  if (!state.snapshot.epics.length)
    epicsForm.append(
      node('p', 'Create an epic to connect this work.', 'muted'),
    );
  const saveEpics = node('button', 'Save epic membership', 'secondary');
  saveEpics.dataset.write = '';
  epicsForm.append(saveEpics);
  on(epicsForm, 'input', () => state.dirty.add('epics'));
  on(epicsForm, 'submit', async (event) => {
    event.preventDefault();
    if (unresolved.length)
      throw new Error(
        'Some epic memberships are unavailable. Resolve them before replacing this set.',
      );
    const selected = [...epicsForm.querySelectorAll('input:checked')].map(
      (v) => v.value,
    );
    await mutate(`/api/tickets/${doc.resource}/epics`, {
      expectedRevision: state.selected.document.revision,
      epics: selected,
    });
    state.dirty.delete('epics');
    await refreshDocument('ticket', doc.resource);
  });
  container.append(epicsForm);
  const topicForm = node('form', undefined, 'membership-form');
  topicForm.append(node('h3', 'Topic tags'));
  inputField(
    topicForm,
    'topics',
    'Comma-separated tags',
    doc.tags.filter((v) => !v.startsWith('epic:')).join(', '),
    { max: 2048 },
  );
  const saveTopics = node('button', 'Save topic tags', 'secondary');
  saveTopics.dataset.write = '';
  topicForm.append(saveTopics);
  on(topicForm, 'input', () => state.dirty.add('tags'));
  on(topicForm, 'submit', async (event) => {
    event.preventDefault();
    const selected = value(topicForm, 'topics')
      .split(',')
      .map((v) => v.trim())
      .filter(Boolean);
    await mutate(`/api/tickets/${doc.resource}/tags`, {
      expectedRevision: state.selected.document.revision,
      tags: selected,
    });
    state.dirty.delete('tags');
    await refreshDocument('ticket', doc.resource);
  });
  container.append(topicForm);
}
function openCreate(kind, status = 'backlog') {
  if (!closeDetail()) return;
  state.createKind = kind;
  const form = $('createForm');
  form.reset();
  form.dataset.status = status;
  $('createLabel').textContent = `NEW ${kind.toUpperCase()}`;
  $('createTitle').textContent =
    kind === 'ticket'
      ? 'Make the next step clear.'
      : 'Give the work a shared outcome.';
  $('createTicketFields').hidden = kind === 'epic';
  $('submitCreate').textContent = `Create ${kind}`;
  $('createHint').textContent =
    kind === 'ticket'
      ? 'Connect this ticket to epics and topic tags after creating it.'
      : 'Connect tickets to this epic from each ticket’s detail panel.';
  $('createBodyLabel').firstChild.textContent =
    kind === 'ticket'
      ? 'Description and acceptance criteria'
      : 'Outcome and scope';
  form.elements.namedItem('body').value =
    kind === 'ticket'
      ? '## Description\n\n\n## Acceptance criteria\n\n- [ ] \n\n## Notes\n'
      : '## Intended outcome\n\n\n## Scope\n';
  $('createError').textContent = '';
  $('submitCreate').dataset.write = '';
  updateBusy();
  $('createDialog').showModal();
}
async function signedIn(session) {
  if (typeof session.csrf !== 'string' || typeof session.project !== 'string')
    throw new Error('Invalid workspace session.');
  reads.invalidate();
  state.generation += 1;
  state.snapshot = null;
  state.selected = null;
  state.csrf = session.csrf;
  state.project = session.project;
  state.pending = loadPending();
  $('workspace').textContent = session.project;
  $('login').hidden = true;
  $('shell').hidden = false;
  updateBusy();
  await refreshBoard();
  updateBusy();
}
on($('loginForm'), 'submit', async (event) => {
  event.preventDefault();
  $('loginError').textContent = '';
  try {
    const session = await api('/session', {
      method: 'POST',
      payload: { accessKey: $('accessKey').value },
    });
    $('accessKey').value = '';
    await signedIn(session);
  } catch (error) {
    $('loginError').textContent =
      error instanceof Error ? error.message : 'Unable to open workspace.';
  }
});
on($('boardNav'), 'click', () => {
  state.view = 'board';
  render();
});
on($('epicsNav'), 'click', () => {
  state.view = 'epics';
  render();
});
on($('refresh'), 'click', async () => {
  await refreshBoard();
  notice('Workspace refreshed. Unsaved document edits are preserved.');
});
on($('newDocument'), 'click', () =>
  openCreate(state.view === 'board' ? 'ticket' : 'epic'),
);
on($('newEpic'), 'click', () => openCreate('epic'));
on($('clearFilters'), 'click', () => {
  state.filters = emptyFilters();
  $('kindFilter').value = '';
  $('search').value = '';
  render();
});
for (const [id, key] of [
  ['kindFilter', 'kind'],
  ['assigneeFilter', 'assignee'],
  ['iterationFilter', 'iteration'],
  ['search', 'search'],
])
  on($(id), id === 'search' ? 'input' : 'change', () => {
    state.filters[key] = $(id).value;
    render();
  });
on($('closeDetail'), 'click', closeDetail);
on(
  $('reloadDetail'),
  'click',
  () =>
    state.selected &&
    openDocument(state.selected.kind, state.selected.resource, {
      reload: true,
    }),
);
on($('cancelCreate'), 'click', () => $('createDialog').close());
on($('cancelCreateBottom'), 'click', () => $('createDialog').close());
on($('createDialog'), 'cancel', (event) => {
  if (state.busy) event.preventDefault();
});
on($('createForm'), 'submit', async (event) => {
  event.preventDefault();
  const form = $('createForm'),
    kind = state.createKind;
  const result = await mutate('/api/documents', {
    kind,
    fields: formFields(form, kind),
    body: value(form, 'body'),
  });
  $('createDialog').close();
  await refreshDocument(kind, result.resource);
  await openDocument(kind, result.resource, { force: true });
});
on($('recover'), 'click', async () => {
  const id = state.pending?.id;
  if (!id) return;
  await sendMutation(`/api/requests/${id}/recover`, { requestId: id }, id);
  await refreshBoard();
  notice(
    'Saved request confirmed. Reload the document to review its current state; unsaved edits are preserved.',
  );
});
on($('logout'), 'click', async () => {
  if (!discard()) return;
  await api('/session', { method: 'DELETE' });
  reads.invalidate();
  state.generation += 1;
  state.csrf = '';
  state.snapshot = null;
  state.selected = null;
  state.dirty.clear();
  $('shell').hidden = true;
  $('detail').hidden = true;
  $('createDialog').close();
  $('login').hidden = false;
});
window.addEventListener('beforeunload', (event) => {
  if (state.dirty.size) {
    event.preventDefault();
    event.returnValue = '';
  }
});
on(window, 'storage', (event) => {
  if (event.key === pendingKey()) {
    state.pending = loadPending();
    updateBusy();
  }
});
async function boot() {
  try {
    await signedIn(await api('/session'));
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 401))
      $('loginError').textContent =
        error instanceof Error ? error.message : 'Unable to open workspace.';
  }
}
boot().catch(report);
