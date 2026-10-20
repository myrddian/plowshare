import assert from 'node:assert/strict';
import test from 'node:test';
import { webcrypto } from 'node:crypto';
import {
  emptyFilters,
  visibleTickets,
  epicProgress,
  decodeSnapshot,
  decodeDocument,
  topicTags,
  bodyForSave,
  requestIdentity,
  replaceDocument,
} from '../src/plowshare_agile/web/model.mjs';

test('request identities work without secure-context randomUUID and remain distinct', () => {
  const random = {
    getRandomValues: (bytes) => webcrypto.getRandomValues(bytes),
  };
  const values = Array.from({ length: 100 }, () => requestIdentity(random));
  assert.equal(new Set(values).size, 100);
  for (const value of values)
    assert.match(
      value,
      /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/,
    );
  assert.throws(() => requestIdentity({}), TypeError);
});

const id = (n) =>
  `${n.toString(16).padStart(8, '0')}-1111-4111-8111-111111111111`;
const ticket = (n, tags, changes = {}) => ({
  resource: id(n),
  revision: id(n + 100),
  owner: 'fixture',
  tags,
  fields: {
    title: `Ticket ${n}`,
    kind: 'feature',
    status: 'ready',
    priority: 'normal',
    assignee: 'Alex',
    iteration: 'Next',
    ...changes,
  },
  body: null,
});
const tickets = [
  ticket(1, [`epic:${id(20)}`, `epic:${id(21)}`, 'sdk', 'websocket']),
  ticket(2, [`epic:${id(20)}`, 'sdk'], { status: 'done' }),
  ticket(3, [`epic:${id(21)}`, 'websocket'], { assignee: 'Sam' }),
];

test('displayed tag versions stay exact and malformed preconditions are refused', () => {
  for (const version of [0, 4, Number.MAX_SAFE_INTEGER]) {
    assert.equal(
      decodeDocument({ ...tickets[0], tags_version: version }, 'ticket')
        .tags_version,
      version,
    );
  }
  for (const version of [-1, 0.5, Number.MAX_SAFE_INTEGER + 1, '4', true]) {
    assert.throws(
      () => decodeDocument({ ...tickets[0], tags_version: version }, 'ticket'),
      /tag version/,
    );
  }
  assert.equal(decodeDocument(tickets[0], 'ticket').tags_version, undefined);
  assert.equal(
    decodeDocument({ ...tickets[0], tags_version: null }, 'ticket')
      .tags_version,
    undefined,
  );
});

test('a targeted authorized read replaces one document and preserves other rows', () => {
  const snapshot = decodeSnapshot({ project: 'fixture', tickets, epics: [] });
  const updated = { ...tickets[0], revision: id(999), tags: ['changed'] };
  const result = replaceDocument(snapshot, 'ticket', updated);
  assert.deepEqual(result.tickets[0], updated);
  assert.deepEqual(result.tickets.slice(1), tickets.slice(1));
  assert.deepEqual(snapshot.tickets, tickets);
  const added = replaceDocument(result, 'epic', {
    resource: id(20),
    revision: id(120),
    owner: 'fixture',
    fields: { title: 'Epic' },
    tags: [],
    body: null,
  });
  assert.equal(added.epics.length, 1);
  assert.equal(added.tickets.length, 3);
  assert.throws(
    () => replaceDocument(added, 'ticket', ticket(20, [])),
    /duplicate/,
  );
  const full = decodeSnapshot({
    project: 'fixture',
    tickets: Array.from({ length: 100 }, (_, n) => ticket(n + 1, [])),
    epics: [],
  });
  assert.throws(
    () => replaceDocument(full, 'ticket', ticket(101, [])),
    /board size/,
  );
});

test('epics and topics intersect without duplicating a shared ticket', () => {
  const filters = {
    ...emptyFilters(),
    epics: [id(20), id(21)],
    tags: ['sdk', 'websocket'],
    assignee: 'Alex',
    iteration: 'Next',
  };
  assert.deepEqual(
    visibleTickets(tickets, filters).map((v) => v.resource),
    [id(1)],
  );
  assert.equal(visibleTickets(tickets, { ...filters, kind: 'bug' }).length, 0);
  assert.equal(visibleTickets(tickets, emptyFilters()).length, 3);
});
test('overlapping epic totals remain independent and empty epics have no completed work', () => {
  assert.deepEqual(epicProgress(tickets, id(20)), { total: 2, done: 1 });
  assert.deepEqual(epicProgress(tickets, id(21)), { total: 2, done: 0 });
  assert.deepEqual(epicProgress(tickets, id(99)), { total: 0, done: 0 });
  assert.deepEqual(topicTags(tickets), ['sdk', 'websocket']);
});
test('boundary rejects malformed metadata, duplicate identities and nonstring text', () => {
  assert.throws(() =>
    decodeDocument(
      { ...tickets[0], fields: { ...tickets[0].fields, status: 'closed' } },
      'ticket',
    ),
  );
  assert.throws(() =>
    decodeDocument({ ...tickets[0], resource: 'a readable title' }, 'ticket'),
  );
  assert.throws(() =>
    decodeSnapshot({
      project: 'fixture',
      tickets: [tickets[0], tickets[0]],
      epics: [],
    }),
  );
  assert.throws(() =>
    decodeDocument(
      {
        ...tickets[0],
        fields: { ...tickets[0].fields, title: { html: '<script>' } },
      },
      'ticket',
    ),
  );
});
test('document prose is kept as text and unknown response members are projected away', () => {
  const value = decodeDocument(
    {
      ...tickets[0],
      fields: { ...tickets[0].fields, title: '<img onerror=evil>' },
      body: '<script>evil</script>',
      secret: 'ignored',
    },
    'ticket',
  );
  assert.equal(value.fields.title, '<img onerror=evil>');
  assert.equal(value.body, '<script>evil</script>');
  assert.equal(value.secret, undefined);
});
test('opening original Markdown does not rewrite CRLF bytes during a metadata save', () => {
  const source = '\ufeff## Heading\r\n\r\n**Original**\r\n';
  assert.equal(
    bodyForSave(source, source.replaceAll('\r\n', '\n'), false),
    null,
  );
  assert.equal(bodyForSave(source, source, true), null);
  assert.equal(bodyForSave(source, '## Edited\n', true), '## Edited\n');
});
