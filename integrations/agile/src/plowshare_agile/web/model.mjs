// Browser boundary and pure board projections. No network, DOM or hidden state.
export const statuses = ['backlog', 'ready', 'in-progress', 'done'];
export const kinds = ['feature', 'bug', 'chore'];
export const priorities = ['low', 'normal', 'high'];
export const labels = {
  backlog: 'Backlog',
  ready: 'Ready',
  'in-progress': 'In progress',
  done: 'Done',
};

function record(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid workspace response.');
  return value;
}
function string(value, max = 1024) {
  if (typeof value !== 'string' || !value.trim() || value.length > max)
    throw new Error('Invalid workspace text.');
  return value;
}
export function uuid(value) {
  if (
    typeof value !== 'string' ||
    !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(
      value,
    )
  )
    throw new Error('Invalid document identity.');
  return value;
}

export function requestIdentity(random) {
  // getRandomValues also works on an explicitly configured LAN HTTP origin;
  // randomUUID is restricted to secure contexts. Never use Math.random for IDs.
  const bytes = random.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) =>
    value.toString(16).padStart(2, '0'),
  ).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
function choice(value, allowed) {
  if (!allowed.includes(value)) throw new Error('Invalid document metadata.');
  return value;
}
function nullable(value, max) {
  return value === null ? null : string(value, max);
}
function tagVersion(value) {
  if (!Number.isSafeInteger(value) || value < 0)
    throw new Error('Invalid tag version.');
  return value;
}
export function decodeDocument(value, kind) {
  const row = record(value),
    meta = record(row.fields);
  if (
    !Array.isArray(row.tags) ||
    row.tags.length > 32 ||
    new Set(row.tags).size !== row.tags.length
  )
    throw new Error('Invalid document tags.');
  const fields =
    kind === 'ticket'
      ? {
          title: string(meta.title, 160),
          kind: choice(meta.kind, kinds),
          status: choice(meta.status, statuses),
          priority: choice(meta.priority, priorities),
          assignee: nullable(meta.assignee, 120),
          iteration: nullable(meta.iteration, 80),
        }
      : { title: string(meta.title, 160) };
  if (
    row.body !== null &&
    (typeof row.body !== 'string' || row.body.length > 16384)
  )
    throw new Error('Invalid document body.');
  return {
    resource: uuid(row.resource),
    revision: uuid(row.revision),
    owner: string(row.owner),
    ...(row.tags_version === undefined || row.tags_version === null
      ? {}
      : {
          tags_version: tagVersion(row.tags_version),
        }),
    tags: row.tags.map((tag) => string(tag, 64)),
    fields,
    body: row.body,
  };
}
export function decodeSnapshot(value) {
  const row = record(value);
  if (
    !Array.isArray(row.tickets) ||
    row.tickets.length > 100 ||
    !Array.isArray(row.epics) ||
    row.epics.length > 20
  )
    throw new Error('Workspace exceeds the supported board size.');
  const tickets = row.tickets.map((v) => decodeDocument(v, 'ticket'));
  const epics = row.epics.map((v) => decodeDocument(v, 'epic'));
  if (
    new Set([...tickets, ...epics].map((v) => v.resource)).size !==
    tickets.length + epics.length
  )
    throw new Error('Workspace contains duplicate documents.');
  return { project: string(row.project), tickets, epics };
}
export function replaceDocument(snapshot, kind, document) {
  const key = kind === 'ticket' ? 'tickets' : 'epics';
  const current = snapshot[key];
  const found = current.some((value) => value.resource === document.resource);
  // Re-run the normal bounds/identity decoder, including cross-kind duplicates.
  // Replace from an authorized read, never from an optimistic mutation payload.
  return decodeSnapshot({
    ...snapshot,
    [key]: found
      ? current.map((value) =>
          value.resource === document.resource ? document : value,
        )
      : [...current, document],
  });
}
export function decodeResult(value) {
  const row = record(value);
  return {
    request_id: uuid(row.request_id),
    resource: uuid(row.resource),
    revision: uuid(row.revision),
    code: choice(row.code, ['OK', 'ACCEPTED', 'CREATED', 'NO_CONTENT']),
    operation: choice(row.operation, [
      'information.upload',
      'information.content.revise',
      'information.revise',
      'information.tags',
      'information.tags.replace',
    ]),
  };
}
export function emptyFilters() {
  return {
    epics: [],
    tags: [],
    kind: '',
    assignee: '',
    iteration: '',
    search: '',
  };
}
export function matches(ticket, filters) {
  const fields = ticket.fields;
  return (
    filters.epics.every((epic) => ticket.tags.includes(`epic:${epic}`)) &&
    filters.tags.every((tag) => ticket.tags.includes(tag)) &&
    (!filters.kind || fields.kind === filters.kind) &&
    (!filters.assignee || fields.assignee === filters.assignee) &&
    (!filters.iteration || fields.iteration === filters.iteration) &&
    (!filters.search ||
      fields.title
        .toLocaleLowerCase()
        .includes(filters.search.toLocaleLowerCase()))
  );
}
export function visibleTickets(tickets, filters) {
  return tickets
    .filter((ticket) => matches(ticket, filters))
    .sort(
      (a, b) =>
        priorities.indexOf(b.fields.priority) -
          priorities.indexOf(a.fields.priority) ||
        a.resource.localeCompare(b.resource),
    );
}
export function epicProgress(tickets, epic) {
  const members = tickets.filter((ticket) =>
    ticket.tags.includes(`epic:${epic}`),
  );
  return {
    total: members.length,
    done: members.filter((ticket) => ticket.fields.status === 'done').length,
  };
}
export function topicTags(tickets) {
  return [
    ...new Set(
      tickets.flatMap((ticket) =>
        ticket.tags.filter((tag) => !tag.startsWith('epic:')),
      ),
    ),
  ].sort();
}
export function filterCount(filters) {
  return (
    filters.epics.length +
    filters.tags.length +
    ['kind', 'assignee', 'iteration', 'search'].filter((key) => filters[key])
      .length
  );
}
// A textarea normalizes line endings. Merely opening the reader must never turn
// a metadata save into a source rewrite, even if the original has CRLF or a BOM.
export function bodyForSave(original, draft, edited) {
  return !edited || draft === original ? null : draft;
}
