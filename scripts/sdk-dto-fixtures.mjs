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
const originEntry = {
  ordinal: 1, turnOrdinal: 1, kind: 'utterance', excerpt: 'message', length: 7,
  cut: false, ejectedAt: null, supersededBy: null, toolCallId: null, toolCalls: [],
  handle: null, recordedAt: null, tookMillis: null, dispatch: null, wireModel: null,
  completion: null, speaker: 'harness', speakerName: 'message msg_fixture', outcome: null,
  job: 'job_fixture', source: { kind: 'message', reference: 'msg_fixture' },
};
const originPage = (entry) => ({ entries: [entry], total: 1,
  offset: 0, limit: 1, through: 1, oldest: 1, more: false });
result('conversation.trajectory', originPage(originEntry), true);
result('conversation.trajectory', originPage({ ...originEntry, job: null, source: { kind: 'unknown', reference: null } }), true);
for (const source of [{ kind: 'message', reference: null }, { kind: 'unknown', reference: 'msg_fixture' }, { kind: 'forged', reference: 'msg_fixture' }, { kind: 'message', reference: ' ' }])
  result('conversation.trajectory', originPage({ ...originEntry, source }), false);
result('conversation.trajectory', originPage({ ...originEntry, job: ' ' }), false);
const messageDelivery = { message: 'msg_fixture', sender: 'sender', recipient: 'recipient',
  replyTo: null, replyExpected: false, finalReply: false, generated: false, state: 'handled',
  ending: 'ANSWERED', reply: null, job: 'job_fixture', deadlineAt: null,
  postedAt: '2026-10-07T00:00:00Z', body: 'hello', conversation: 'cnv_recipient', sourceConversation: 'cnv_sender' };
result('message.delivery', messageDelivery, true);
result('message.delivery', { ...messageDelivery, conversation: '' }, false);
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
// Relay causation is an optional response field for older peers, with strict shared invariants.
const relayPage = {
  scope: { project: 'fixture', system: false },
  topic: { name: 'job.ended', kind: 'LIFECYCLE', retentionSeconds: '345600', maxRecords: null, through: '1', expiredThrough: '0' },
  after: '0', next: '1', gapThrough: null, subscribers: [], branches: [],
  events: [{ position: '1', eventId: 'source:event', publisher: 'system.source', occurredAt: '2026-10-06T00:00:00Z', publishedAt: '2026-10-06T00:00:00Z', correlationId: null, causationId: null,
    payload: { kind: 'LIFECYCLE', text: null, schedule: null, emits: null, fireAt: null, lifecycle: { source: 'job.ended', subject: 'job_fixture', state: 'ANSWERED', context: null, related: null } } }],
};
result('relay.log', relayPage, true);
for (const [causation, valid] of [
  [{ rootId: 'root', parentId: null, depth: 0 }, true],
  [{ rootId: 'root', parentId: 'parent', depth: 8 }, true],
  [{ rootId: 'legacy', parentId: null, depth: -1 }, true],
  [{ rootId: 'root', parentId: null, depth: 1 }, false],
  [{ rootId: 'root', parentId: 'parent', depth: 0 }, false],
  [{ rootId: 'root', parentId: 'parent', depth: 33 }, false],
  [{ rootId: 'root', parentId: null, depth: -2 }, false],
  [{ rootId: 'root', parentId: 'parent', depth: 1.5 }, false],
]) result('relay.log', { ...relayPage, events: [{ ...relayPage.events[0], causation }] }, valid);
// Generic topic ports preserve their project, consumer and explicit batch authority.
const publish = {requestId:receipt,project:'fixture',topic:'checks.requests',text:'opaque application JSON',occurredAt:'2026-10-07T00:00:00Z'};
input('relay.publish',publish,true);
for(const change of [{text:' '},{text:'\0bad'},{requestId:'bad'},{topic:'System Topic'},{parentTopic:'checks.source'},{occurredAt:'invalid'},{publisher:'forged'},{requestId:receipt.toUpperCase().replace('11111111','ABCDEFAB')}]) input('relay.publish',{...publish,...change},false);
input('relay.publish',{...publish,parentTopic:'checks.source',parentEventId:'event'},true);
const consume = {project:'fixture',topic:'checks.requests',group:'detectors',consumerId:receipt,start:'OLDEST_RETAINED'};
input('relay.consume',consume,true);
for(const change of [{group:' '},{consumerId:'bad'},{limit:0},{limit:101},{waitMs:30001},{waitMs:1.5},{start:'EARLIEST'}]) input('relay.consume',{...consume,...change},false);
const batch={project:'fixture',topic:'checks.requests',group:'detectors',consumerId:receipt,status:'DATA',batchId:receipt,fence:'1',through:'1',expiresAt:'2026-10-07T00:00:30Z',expiredThrough:null,events:relayPage.events};
result('relay.consume',batch,true);
for(const change of [{events:[]},{batchId:null},{fence:'0'},{fence:'9223372036854775808'},{status:'EMPTY'},{expiredThrough:'1'},{through:'-1'}]) result('relay.consume',{...batch,...change},false);
const empty={...batch,status:'EMPTY',batchId:null,fence:null,expiresAt:null,events:[]};
result('relay.consume',empty,true);
result('relay.consume',{...empty,status:'BUSY'},true);
result('relay.consume',{...batch,status:'GAP',events:[],expiredThrough:'1'},true);
result('relay.consume',{...batch,status:'GAP',events:[]},false);
const ack={project:'fixture',topic:'checks.requests',group:'detectors',consumerId:receipt,batchId:receipt,fence:'1'};
input('relay.ack',ack,true);
for(const change of [{fence:'0'},{fence:'01'},{batchId:'bad'},{expiredThrough:'9223372036854775808'}]) input('relay.ack',{...ack,...change},false);
result('relay.publish',{requestId:receipt,project:'fixture',topic:'checks.requests',position:'1',publishedAt:publish.occurredAt},true);
result('relay.ack',{project:'fixture',topic:'checks.requests',group:'detectors',batchId:receipt,through:'1',gap:false},true);
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
// Portable host references must have identical strict semantics in every SDK.
const root = { store: 'applications', path: 'chatbot' };
const area = { store: 'outputs', path: 'chatbot/reports' };
const deployment = { project: 'app', requestId: receipt, expectedRevision: null, destination: root, writableAreas: [], files: [{path:'plowshare.json',text:'{"version":1}'}] };
input('application.deploy', deployment, true);
input('application.deploy', {...deployment,files:[...deployment.files,{path:'agents/worker.md',text:'source'}]},true);
input('application.deploy', {...deployment,files:[...deployment.files,{path:'.plowshare/agents/worker.md',text:'source'}]},false);
input('application.deploy', {...deployment,requestId:receipt+'\n'},false);
input('application.deploy', {...deployment,files:[...deployment.files,{path:'notes.md\n',text:'x'}]},false);
input('application.deploy', {...deployment, expectedRevision: receipt}, true);
input('application.deploy', {...deployment,destination:{store:'applications',path:''}},false);
input('application.deploy', {...deployment, files: []}, false);
input('application.deploy', {...deployment, files: [{path:'../escape',text:'x'}]}, false);
input('application.deploy', {...deployment, files: [{path:'plowshare.json',text:'x'}, {path:'Plowshare.json',text:'x'}]}, false);
input('application.deploy', {...deployment, files: [{path:'plowshare.json',text:'x'}, {path:'a',text:'😀'.repeat(16384)}, {path:'b',text:'😀'.repeat(16384)}]}, false);
input('application.deploy', {...deployment, files: [{path:'plowshare.json',text:'x'}, {path:'a',text:'x'}, {path:'a/b',text:'x'}]}, false);
input('application.deploy', {...deployment, files: [{path:'plowshare.json',text:'x'}, {path:'.env',text:'x'}]}, false);
input('application.deploy', {...deployment,files:[...deployment.files,{path:'a/'.repeat(17)+'file',text:'x'}]},false);
input('application.activate', {project:'app',requestId:receipt,expectedRevision:receipt,revision:receipt}, true);
input('application.activate', {project:'app',requestId:receipt,expectedRevision:null,revision:receipt}, false);
result('application.deploy', {project:'app',requestId:receipt,release:{revision:receipt,digest:'a'.repeat(64),fileCount:1}}, true);
result('application.deployment.status', {project:'app',activeRevision:null,releases:[]}, true);
result('application.deploy',{project:'app',requestId:receipt,release:{revision:receipt,digest:'a'.repeat(64)+'\n',fileCount:1}},false);
result('application.deployment.status', {project:'app',activeRevision:receipt,releases:[{revision:receipt,digest:'a'.repeat(64),fileCount:0}]}, false);
input('application.create', { name: 'chatbot', applicationRoot: root, writableAreas: [area] }, true);
input('application.storage.set', { project: 'chatbot', applicationRoot: root, writableAreas: [] }, true);
input('application.create', { name: 'chatbot', applicationRoot: root }, false);
input('application.create', { name: 'chatbot', applicationRoot: root, writableAreas: [area, area] }, false);
input('project.create', { name: 'chatbot', applicationRoot: root, writableAreas: [] }, false);
input('application.create', { name: 'chatbot', workspace: '/legacy', applicationRoot: root, writableAreas: [] }, false);
const file = { project: 'chatbot', path: 'report.md', text: '', revision: 'a'.repeat(64), writable: true, location: area };
result('application.file.read', file, true);
result('application.file.read', { ...file, location: { ...area, future: true } }, true);
const placedProject = { name: 'chatbot', workspace: '/fixture', machine: null, members: [], lent: [], exclusions: [], applicationRoot: root, writableAreas: [area] };
result('application.create', placedProject, true);
result('application.storage.set', { ...placedProject, writableAreas: Array(101).fill(area) }, false);
for (const invalid of [
  { store: 'Upper', path: '' }, { store: 'outputs', path: '../secret' },
  { store: 'outputs', path: '/absolute' }, { store: 'outputs', path: 'a//b' },
  { store: 'outputs', path: 'a/./b' }, { store: 'outputs', path: 'a\\b' },
  { store: 'outputs', path: 'a:stream' }, { store: 'outputs', path: '.GiT/config' },
  { store: 'outputs', path: 'a\u0085' }, { store: 'outputs', path: '' , extra: true },
]) {
  input('application.file.read', { project: 'chatbot', path: 'report.md', location: invalid }, false);
  // Unknown reply fields are projected away for forward compatibility.
  if (!('extra' in invalid)) result('application.file.read', { ...file, location: invalid }, false);
}
input('application.files', { project: 'chatbot', location: { store: 'outputs', path: '' } }, true);

const namedSelection = {name:'privacy', revision:'a'.repeat(64), description:'Review privacy', members:['researcher'], budget:20};
const namedType = {name:'privacy', members:['researcher'], budget:20, refused:{}, origin:'swarm/privacy.md', selection:namedSelection};
result('swarm.types', {project:'fixture', types:[namedType]}, true);
result('swarm.types', {project:'fixture', types:[]}, true);
for (const change of [{name:'../privacy'}, {revision:'bad'}, {budget:1}, {budget:2.5}, {description:'x'.repeat(4097)}, {members:['researcher','researcher']}, {members:[' ']}])
  result('swarm.types', {project:'fixture', types:[{...namedType, selection:{...namedSelection,...change}}]}, false);
result('swarm.types', {project:'fixture', types:[{...namedType, members:['critic']}]}, false);
const namedOpening = {project:'fixture',title:'Topic',label:'Review',body:'Evidence',requestId:receipt,swarm:'privacy'};
input('board.open', namedOpening, true);
for (const swarm of ['../privacy', 'Privacy', '', 'p'.repeat(65), null]) input('board.open', {...namedOpening,swarm}, false);
input('swarm.types', {project:'fixture'}, true);
input('swarm.types', {}, false);
input('filestore.list', {}, true);
input('filestore.list', {account:'other'}, false);
result('filestore.list', {stores:[]}, true);
result('filestore.list', {stores:[{alias:'applications',role:'MANAGER'},{alias:'reports',role:'CONTRIBUTOR'},{alias:'archive',role:'VIEWER'}]}, true);
result('filestore.list', {stores:[{alias:'../private',role:'MANAGER'}]}, false);
result('filestore.list', {stores:[{alias:'applications',role:'OWNER'}]}, false);
result('filestore.list', {stores:Array(101).fill({alias:'applications',role:'MANAGER'})}, false);
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
