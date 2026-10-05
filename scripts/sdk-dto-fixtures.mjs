/** Shared boundary examples, checked against the canonical SDK before native tests. */
import {
  decodePayload,
  decodeReply,
} from '../sdk/typescript/build/operations/schema.js';
import { decodeServerPush } from '../sdk/typescript/build/operations/push.js';
import { readFileSync, writeFileSync } from 'node:fs';
const cases = [];
const input = (operation, value, valid) =>
  cases.push({ boundary: 'input', operation, value, valid });
const result = (operation, value, valid) =>
  cases.push({ boundary: 'result', operation, value, valid });
const push = (value, valid) => cases.push({ boundary: 'push', value, valid });
const receipt = '11111111-1111-1111-1111-111111111111';
input('project.list', {}, true);
input('project.list', { future: 1 }, false);
for (const job of ['job_fixture', '', '  '])
  input('job.status', { job }, job === 'job_fixture');
for (const limit of [1, 0, -1, 1.5, 2147483648])
  input('admin.audit', { limit }, limit === 1);
input('approval.list', { mine: true }, true);
input('approval.list', {}, false);
input('approval.list', { mine: true, project: 'fixture' }, false);
input('admin.account.update', { handle: 'fixture' }, false);
input('admin.account.update', { handle: 'fixture', enabled: false }, true);
input(
  'agent.run',
  { agent: 'fixture', task: 'free text', conversation: 'c', project: 'p' },
  false,
);
input(
  'agent.run',
  {
    agent: 'fixture',
    task: 'free text',
    newConversation: true,
    conversation: 'c',
  },
  false,
);
input(
  'agent.run',
  { agent: 'fixture', task: 'free text', maxModelCalls: 1 },
  false,
);
input('schedule.pause', { schedule: 'fixture' }, false);
input('schedule.pause', { schedule: 'fixture', paused: false }, true);
input('information.status', { scope: { kind: 'personal' } }, false);
input(
  'information.status',
  { scope: { kind: 'personal' }, revision: receipt, acquisition: receipt },
  false,
);
input(
  'information.status',
  { scope: { kind: 'personal' }, revision: receipt },
  true,
);
const message = { parts: [{ text: 'hello' }] };
input('outgoing.send', { requestId: receipt, peer: 'fixture', message }, true);
for (const requestId of ['bad', ''])
  input('outgoing.send', { requestId, peer: 'fixture', message }, false);
for (const parts of [
  [],
  [{ text: '\0bad' }],
  [{ text: 3 }],
  [{ text: 'hi', raw: 'AAAA' }],
  [{ raw: 'AB==' }],
  [{ url: 'https://user:secret@example.invalid/a' }],
  [{ text: 'ok', filename: '../x' }],
])
  input(
    'outgoing.send',
    { requestId: receipt, peer: 'fixture', message: { parts } },
    false,
  );
input('outgoing.claim', { peers: ['fixture'] }, true);
input('outgoing.claim', { peers: [] }, false);
input('outgoing.claim', { peers: ['fixture', 'fixture'] }, false);
input(
  'outgoing.report',
  { id: receipt, claim: 'fixture', revision: -1, state: 'WORKING' },
  false,
);
result('project.list', [], true);
const project = {
  name: 'fixture',
  workspace: 'fixture',
  machine: null,
  members: [],
  lent: [],
  exclusions: [],
};
result('project.list', [{ ...project, future: 1 }], true);
result('project.list', [{ ...project, members: [1] }], false);
result('project.list', [{ ...project, machine: 42 }], false);
result('project.list', [{ name: 'partial' }], false);
result('conversation.follow', null, true);
const informationSearch = JSON.parse(readFileSync(
  new URL('../test-support/contracts/ws-information-fixtures.json', import.meta.url),
  'utf8',
))['information.search'].payload;
result('information.search', informationSearch, true);
result('information.search', [{ ...informationSearch[0], distance: 'near' }], false);
result('information.search', [{ ...informationSearch[0], chunk: { ...informationSearch[0].chunk, placement: { sectionId: 'partial' } } }], false);
result('information.search', { query: 'question', document: null, limit: 10, hits: [] }, false);
result('outgoing.claim', { work: null, action: null }, true);
result('outgoing.claim', { work: null, action: 'send' }, false);
push({ kind: 'inbox.changed', unread: 1, future: 1 }, true);
for (const unread of [-1, 1.5, '1'])
  push({ kind: 'inbox.changed', unread }, false);
push(
  {
    kind: 'orchestration.resumed',
    orchestration: 'fixture',
    requestId: receipt,
  },
  true,
);
push(
  { kind: 'orchestration.resumed', orchestration: 'fixture', requestId: 'bad' },
  false,
);
push({ job: 'fixture', kind: 'started' }, false);
push(
  {
    job: 'fixture',
    kind: 'started',
    agent: 'fixture',
    steps: 0,
    modelCalls: 0,
  },
  true,
);
push({ job: 'fixture', part: 'ANSWER', text: 'text' }, true);
push({ job: 'fixture', part: 'ANSWER', text: '\0bad' }, false);
push(
  {
    id: null,
    type: 'usage.closed',
    protocol_version: 'plowshare-v1',
    payload: { subscription: receipt, code: 'BAD_REQUEST' },
  },
  true,
);
for (const test of cases) {
  let accepted = true;
  try {
    if (test.boundary === 'input') decodePayload(test.operation, test.value);
    else if (test.boundary === 'result')
      decodeReply(test.operation, test.value);
    else decodeServerPush(test.value);
  } catch {
    accepted = false;
  }
  if (accepted !== test.valid)
    throw new Error(
      'Fixture differs from canonical contract: ' + JSON.stringify(test),
    );
}
const path = new URL(
    '../test-support/contracts/sdk-dto-conformance.json',
    import.meta.url,
  ),
  content = JSON.stringify(cases, null, 2) + '\n';
if (process.argv.includes('--check')) {
  if (readFileSync(path, 'utf8') !== content)
    throw new Error('Stale SDK DTO fixtures');
} else writeFileSync(path, content);
console.log('Canonical SDK DTO cases verified:', cases.length);
