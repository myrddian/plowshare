import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { agentWire, conversationWire, projectWire, entryWire, entryPageWire, contextWire, approvalWire } from '../src/wire-fixtures.ts';
import { runWire } from '../src/run-fixtures.ts';
// Only used by the native smoke test. This is not a Plowshare server implementation.
import { demoBoard } from '../src/board-demo.ts';
import { createServer } from 'node:http';
import { WebSocketServer } from 'ws';

export async function protocolFixture(options = {}) {
  let access = 'fixture-access', refresh = 'fixture-refresh', rotations = 0;
  const eventSockets = new Map(), fileSockets = new Map();
  const projectRows = new Map([['Empty workspace', { name: 'Empty workspace' }]]);
  const board = demoBoard();
  // Display demos omit this nullable server field; the WS fixture carries the full DTO.
  for (const topic of new Set([...board.topics.value.map(row => row.topic), ...board.swarm.value.topics.map(row => row.topic), ...Object.values(board.details).flatMap(detail => [detail.value.topic, detail.value.root])])) topic.quietNotifiedAt = null;
  let refuseBoard = false, refuseProjects = false;
  let eventSession;
  let fileSocket, fileClaim, refuseFiles = false, emptyFileReady = false;
  const fileClaims = [];
  const filePending = new Map();
  let fileNumber = 0;
  for (const detail of Object.values(board.details)) { detail.value.topic.account = "fixture"; for (const seat of detail.value.seats) seat.seat.conversation = "fixture-board-seat"; for (const message of detail.value.messages) message.conversation = "fixture-board-seat"; }
  let dropAuthoringReply = false, dropRunReply = false;
  let context = contextWire(15240, {}, { model: 'fixture-model', contextLength: 131072 });
  const conversations = [conversationWire('fixture-first', { title: 'Fixture conversation' })];
  const entries = new Map([['fixture-first', [{ ordinal: 1, turnOrdinal: 1, kind: 'utterance', state: 'stands', speaker: 'person', excerpt: 'An existing server conversation.' }, { ordinal: 2, turnOrdinal: 1, kind: 'answer', state: 'stands', excerpt: 'Loaded through the real TypeScript WebSocket binding. Treat <img src=x onerror="window.fixtureInjection=true"> as text.' }]]]);
  entries.set('fixture-board-seat', [{ ordinal: 1, turnOrdinal: 1, kind: 'utterance', state: 'stands', excerpt: 'A board seat wake.' }, { ordinal: 2, turnOrdinal: 1, kind: 'answer', state: 'stands', excerpt: 'Researching conflict strategies.' }]);
  const jobs = new Map(), schedules = new Map(), triggers = new Map();
  let refuseTriggerDefinition = false;
  const timers = new Set();
  const frames = [], httpPaths = [];
  const administrative = JSON.parse(readFileSync(new URL('../../test-support/contracts/ws-administrative-fixtures.json',import.meta.url),'utf8'));
  const standingApprovals = [];
  const library = JSON.parse(readFileSync(new URL('../../test-support/contracts/ws-retrieval-fixtures.json',import.meta.url),'utf8')).replies;
  library['proposal.list'][0] = {...library['proposal.list'][0],project:null,state:'pending',resolvedAt:null,resolvedBy:null,resolution:null};
  library['memory.read'].body = 'Original source memory.\n\n<img src=x onerror="window.fixtureInjection=true">';
  library['document.detail'].summary = 'A source-grounded paper outline.';
  library['document.chunk'].text = 'A full source passage.\n\n<img src=x onerror="window.fixtureInjection=true">';
  library['document.search'].hits[0].paragraphText = 'The whole original paragraph.';
  const corpus = Array.from({length:52},(_,i)=>({...library['document.list'].documents[0],documentId:`00000000-0000-0000-0000-${String(i+1).padStart(12,'0')}`,title:i?'Paper '+(i+1):'A paper'}));
  let malformedLibrary = false;
  const searchHits = Array.from({length:52},(_,i)=>({...library['conversation.search'].hits[0],conversationId:'fixture-search-conversation',ordinal:i+1,turnOrdinal:i+1,snippet:`Search evidence ${i+1}`,evidence:{retrievedBy:'passage_index',passagePosition:i+1,sourceRevision:'revision-1'}}));
  entries.set('fixture-search-conversation',[{ordinal:1,turnOrdinal:1,kind:'answer',state:'stands',excerpt:'An archived result conversation.'}]);

  const inbox = Array.from({ length: 25 }, (_, i) => ({ id: `fixture-inbox-${i + 1}`, arrivedAt: '2026-10-01T10:00:00Z', kind: ['run', 'approval', 'orchestration', 'hook', 'sync.conflict', 'custom.audit'][i] ?? 'notice', ...(i === 0 ? { ending: 'ANSWERED' } : {}), answer: i === 0 ? 'A **background research result**.\n\n| Source | Status |\n| --- | --- |\n| Library | Read |\n\nThis arrived independently of your chat job.' : `Fixture notice ${i + 1}.` }));
  inbox.push({ id: 'fixture-already-read', arrivedAt: '2026-09-30T10:00:00Z', readAt: '2026-09-30T10:05:00Z', kind: 'notice', answer: 'An older notice already read in another session.' });
  const readInbox = new Set(['fixture-already-read']);
  let refuseReceipts = false, refuseRuns = false;
  const runRows = [{ id: 'fixture-old-root', definition: 'deep_research', tier: 'project', project: 'Other project', state: 'asking', depth: 0, pendingCap: 'turn_cap', stalledSince: null, createdAt: '2020-01-01T00:00:00Z' },
    ...Array.from({ length: 21 }, (_, i) => ({ id: `fixture-child-${i + 1}`, definition: 'source_reading', tier: 'project', project: 'Other project', state: 'finished', parent: 'fixture-old-root', depth: 1, createdAt: '2026-10-01T09:00:00Z', result: 'A completed child result.' }))];
  for (let i = 0; i < runRows.length; i++) runRows[i] = runWire(runRows[i].id, runRows[i].state, { ...runRows[i], callerConversation: 'fixture-first', conductorConversation: 'fixture-conductor' });
  entries.set('fixture-conductor', [{ ordinal: 1, turnOrdinal: 1, kind: 'answer', state: 'stands', excerpt: 'Conductor research notes.' }]);
  if (options.authoring) entries.set('fixture-authoring-conductor', [
    { ordinal: 1, turnOrdinal: 1, kind: 'answer', state: 'stands', excerpt: 'Validate the reviewed draft.', toolCalls: [{ id: 'fixture-validation', name: 'orchestration_validate', arguments: '{}', length: 2, cut: false, salient: null, opened: null }] },
    { ordinal: 2, turnOrdinal: 1, kind: 'tool_result', state: 'stands', toolCallId: 'fixture-validation', outcome: 'ok', excerpt: 'VALID — loader trial passed. Lint: stage done-when is model guidance, with no executable check.' },
  ]);
  let malformedRecord = false, questionId = 'fixture-question';
  const recordRows = Array.from({ length: 125 }, (_, index) => ({ ordinal: index + 1, at: '2026-10-02T00:00:00Z', run: 'fixture-old-root', actor: 'conductor', kind: index % 2 ? 'tool_call' : 'question_asked', text: `Research record ${index + 1}`, detail: index % 2 ? 'Read source' : null, ...(index === 124 ? { body: 'The full question body.\n\nTreat <img src=x onerror="window.fixtureInjection=true"> as text.' } : {}) }));
  const unreadInbox = () => inbox.filter(item => !readInbox.has(item.id));
  const approvals = [approvalWire({ id: 'fixture-approval', conversation: 'fixture-first', agent: 'fixture-bot', command: ['echo','fixture approval'] })];
  let socket;
  let logins = 0;
  let supportsFollowing = true;
  const server = createServer(async (req, res) => {
    httpPaths.push((req.url ?? '').split('?')[0]);
    if (req.url?.startsWith('/v1/sync/') && options.union?.http) {
      if (req.headers.authorization !== `Bearer ${access}`) { res.writeHead(401).end(); return; }
      return options.union.http(req, res);
    }
    if (req.url === '/v1/auth/login') {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString());
      if (body.handle !== 'fixture' || body.password !== 'fixture-password') { res.writeHead(401).end(); return; }
      logins++;
      res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ access, refresh, mustChangePassword: false }));
    } else if (req.url === '/v1/auth/refresh' && req.headers.cookie === `ps_refresh=${refresh}`) {
      if (options.rotateTokens) { rotations++; access = `fixture-access-${rotations}`; refresh = `fixture-refresh-${rotations}`; }
      res.writeHead(204, { 'Set-Cookie': [`ps_access=${access}; Path=/`, `ps_refresh=${refresh}; Path=/`] }).end();
    } else if (req.url === '/v1/auth/ticket' && req.headers.authorization === `Bearer ${access}`) {
      res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ ticket: 'fixture-ticket' }));
    } else res.writeHead(404).end();
  });
  const wss = new WebSocketServer({ server });
  const push = (value) => {
    const job = value.job ? jobs.get(value.job) : undefined;
    for (const [id, client] of eventSockets) {
      if (job && job.session !== id) continue;
      if (value.kind === 'conversation.appended' && !client.following.has(value.conversation)) continue;
      if (client.socket.readyState === 1) client.socket.send(JSON.stringify(value));
    }
  };
  const grew = conversation => push({ kind: 'conversation.appended', conversation, through: entries.get(conversation)?.at(-1)?.ordinal ?? 0 });
  function commit(job, text) {
    if (job.committedText !== undefined) return;
    job.committedText = text;
    const log = entries.get(job.conversation); const ordinal = log.at(-1).ordinal + 1;
    log.push({ ordinal, turnOrdinal: job.turnOrdinal, kind: 'answer', state: 'stands', excerpt: text });
    grew(job.conversation);
  }
  function finish(job, ending = 'ANSWERED', text, pace = null) {
    if (job.outcome) return;
    job.outcome = { ending, answered: ending === 'ANSWERED', text: text ?? job.committedText ?? (ending === 'ANSWERED' ? `Fixture answer: ${job.task}` : 'Fixture run cancelled.'), pace };
    if (ending === 'ANSWERED') {
      commit(job, job.outcome.text);
    }
    push({ kind: 'ended', job: job.id, ending, agent: 'fixture-bot' });
  }
  function fileRequest(request, project) {
    const serving = project ? fileSockets.get(project)?.socket : fileSocket;
    if (!serving || serving.readyState !== 1) throw new Error('No file presence connected');
    const id = `file-fixture-${++fileNumber}`;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { filePending.delete(id); reject(new Error('File reply timed out')); }, 5000);
      filePending.set(id, { resolve, timer }); serving.send(JSON.stringify({ ...request, id }));
    });
  }
  function start(payload, approval = false, session = eventSession) {
    const job = { session, id: `fixture-job-${jobs.size + 1}`, conversation: payload.conversation, task: payload.task, limits:{maxTurns:10,noTurnCap:false,maxModelCalls:20,noBudget:false,modelCallsSpent:0} };
    jobs.set(job.id, job);
    const log = entries.get(job.conversation); const ordinal = (log.at(-1)?.ordinal ?? 0) + 1;
    job.turnOrdinal = ordinal;
    log.push({ ordinal, turnOrdinal: ordinal, kind: 'utterance', state: 'stands', speaker: approval ? 'harness' : 'person', ...(approval ? { speakerName: 'approval fixture-approval' } : {}), excerpt: job.task });
    // Deliberately arrive before the reply to exercise the client's holding buffer.
    push({ kind: 'started', job: job.id, agent: 'fixture-bot' });
    push({ part: 'THINKING', job: job.id, text: `Fixture reasoning: ${job.task}` });
    push({ part: 'ANSWER', job: job.id, text: `Fixture streaming: ${job.task}` });
    const timer = setTimeout(() => { timers.delete(timer); finish(job); }, 15000); timers.add(timer);
    return job;
  }
  wss.on('connection', (connection, req) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/v1/files' && url.searchParams.get('ticket') === 'fixture-ticket') {
      const claim = Object.fromEntries(['session', 'project', 'machine', 'root'].map(key => [key, url.searchParams.get(key)]));
      fileClaims.push(claim);
      if (refuseFiles) { connection.close(1003, 'Project is already rooted by another session'); return; }
      const holder = fileSockets.get(claim.project);
      if (holder && holder.claim.session !== claim.session) { connection.close(1003, 'Project is already rooted by another session'); return; }
      fileSocket = connection; fileClaim = claim;
      fileSockets.set(claim.project, { socket: connection, claim });
      projectRows.set(claim.project, { name: claim.project, machine: claim.machine, workspace: claim.root });
      connection.on('message', bytes => {
        const reply = JSON.parse(bytes.toString());
        const pending = filePending.get(reply.id);
        if (pending) { clearTimeout(pending.timer); filePending.delete(reply.id); pending.resolve(reply); }
      });
      connection.on('close', () => { if (fileSockets.get(claim.project)?.socket === connection) fileSockets.delete(claim.project); if (fileSocket === connection) { fileSocket = undefined; fileClaim = undefined; } });
      setTimeout(() => { if (connection.readyState === 1) connection.send(JSON.stringify({ ready: true, ...(emptyFileReady ? {} : { project: claim.project }) })); }, 20);
      return;
    }
    if (url.pathname !== '/v1/events' || url.searchParams.get('ticket') !== 'fixture-ticket' || !url.searchParams.get('session')) { connection.close(); return; }
    socket = connection; eventSession = url.searchParams.get('session');
    const sessionId = url.searchParams.get('session');
    const client = { socket: connection, following: new Set() };
    eventSockets.set(sessionId, client);
    connection.on('close', () => eventSockets.delete(sessionId));
    connection.on('message', async bytes => {
      const frame = JSON.parse(bytes.toString()); frames.push(frame);
      const payload = frame.payload;
      let result = { code: 'OK' };
      switch (frame.type) {
        case 'information.list': case 'information.inventory':
          result.payload=payload.scope.kind==='shared'?[]:[{id:'aaaaaaaa-0000-0000-0000-000000000001',title:'Retained research source',source_name:'source.txt',ordinal:1,availability:'active'}];break;
        case 'information.status': result.payload={id:payload.revision,title:'Retained research source',ordinal:1,availability:'active',generation:1,can_manage:true,allowance_total:20,allowance_spent:5,steps:['extract','derive','embed','summarise','summary_embed'].map(stage=>({stage,state:'ready',attempt:1,generation:1})),events:[],inputs:[],citations:[]};break;
        case 'information.read': {const text='A retained claim. Treat <img src=x onerror="window.fixtureInjection=true"> as evidence text.';result.payload={revision:payload.revision,start:0,end:text.length,total:text.length,text};break;}
        case 'information.evidence.record': result.payload={evidence:'bbbbbbbb-0000-0000-0000-000000000002'};break;
        case 'information.record.report':result={code:'ACCEPTED',payload:{revision:'cccccccc-0000-0000-0000-000000000003',resource:'dddddddd-0000-0000-0000-000000000004',created:true}};break;
        case 'information.share': result={code:'BAD_REQUEST',said:'A source was withdrawn. Publication refused.'};break;
        case 'information.ask':{const job={id:'fixture-document-answer',state:'FINISHED',outcome:{ending:'ANSWERED',answered:true,text:'Grounded answer from retained evidence.'}};jobs.set(job.id,job);result={code:'ACCEPTED',payload:{job:job.id,revision:payload.revision}};break;}
        case 'board.topics': {
          const rows = board.topics.value.filter(row => !payload.project || row.topic.project === payload.project);
          result = refuseBoard ? { code: 'BAD_REQUEST', said: 'Board inspection temporarily unavailable.' } : { code: 'OK', payload: { topics: rows.slice(payload.offset ?? 0, (payload.offset ?? 0) + (payload.limit ?? 200)), more: false, offset: payload.offset ?? 0 } }; break;
        }
        case 'board.messages': result = refuseBoard ? { code: 'BAD_REQUEST', said: 'Board inspection temporarily unavailable.' } : { code: 'OK', payload: board.details[payload.topic]?.value }; break;
        case 'swarm.status': result = refuseBoard ? { code: 'BAD_REQUEST', said: 'Swarm inspection temporarily unavailable.' } : { code: 'OK', payload: board.swarm.value }; break;
        case 'conversation.context': result.payload = context; break;
        case 'project.list': result = refuseProjects ? { code: 'BAD_REQUEST', said: 'Project listing unavailable' } : { code: 'OK', payload: [...projectRows.values()].map(row => projectWire(row.name, row)) }; break;
        case 'agent.list': {
          const ownsFiles = fileSockets.get(payload.project)?.claim.session === sessionId;
          try {
            result.payload = options.agentRoster ? await options.agentRoster({ project: payload.project, session: sessionId,
              files: ownsFiles ? request => fileRequest(request, payload.project) : undefined })
              : [{ name: 'fixture-bot', bot: true, served: true, preferred: true, model: 'fixture-model', tools: ['document_search'], description: 'Protocol fixture bot', withheld: [] }];
            result.payload = result.payload.map(row => agentWire(row));
          } catch (error) { result = { code: 'BAD_REQUEST', said: String(error) }; }
          break;
        }
        case 'conversation.list': result.payload = conversations.filter(row => (row.project ?? '') === (payload.project ?? '') && (row.lifecycle ?? 'active') === (payload.lifecycle ?? 'active')); break;
        case 'conversation.latest': result = { code: 'OK' }; break; // No previous conversation for the selected fixture bot.
        case 'conversation.lifecycle': {
          const row = conversations.find(row => row.id === payload.conversation);
          if (!row) { result = { code:'NOT_FOUND', said:'Conversation unavailable' }; break; }
          row.lifecycle = payload.lifecycle; result.payload = { id:row.id, lifecycle:row.lifecycle }; break;
        }
        case 'conversation.resume': {
          const job = start({ conversation:payload.conversation, task:'Resume reviewed conversation' },false,sessionId);
          setTimeout(() => finish(job,'ANSWERED','Resumed conversation finished.'),30);
          result = { code:'ACCEPTED', payload:{id:job.id,agent:'fixture-bot'} }; break;
        }
        case 'conversation.open': {
          const row = conversationWire(`fixture-${conversations.length + 1}`, { project: payload.project ?? null }); conversations.push(row); entries.set(row.id, []); result.payload = row; break;
        }
        case 'conversation.trajectory': {
          const log = entries.get(payload.conversation) ?? [];
          const rows = payload.before !== undefined ? log.filter(row => row.ordinal < payload.before) : payload.after !== undefined ? log.filter(row => row.ordinal > payload.after) : log;
          const offset = payload.offset ?? 0; const limit = payload.limit ?? 100;
          const selected = payload.tail || payload.before !== undefined ? rows.slice(-limit).reverse() : rows.slice(offset, offset + limit);
          result.payload = entryPageWire(selected.map(row => entryWire(row.ordinal, row.kind, row.excerpt ?? '', row)), log.at(-1)?.ordinal ?? 0, { total: rows.length, offset, limit, more: payload.tail || payload.before !== undefined ? rows.length > limit : null }); break;
        }
        case 'conversation.follow':
          if (!supportsFollowing) { result = { code: 'BAD_REQUEST', said: 'Fixture old server requires conversation' }; break; }
          client.following = new Set(payload.conversations); break;
        case 'agent.run': {
          const job = start(payload, false, sessionId);
          if (dropRunReply) { dropRunReply = false; connection.close(); return; }
          if (options.authoring && payload.task.includes('Start the granted design_orchestration')) runRows.unshift(runWire(`fixture-builder-${jobs.size}`, 'asking', { definition: 'design_orchestration', conductorConversation: 'fixture-authoring-conductor', createdAt: new Date().toISOString(), project: payload.project ?? conversations.find(row => row.id === payload.conversation)?.project ?? null, callerConversation: payload.conversation, pendingCap: 'install' }));
          if (options.authoring) setTimeout(() => finish(job, 'ANSWERED', 'Started the builder.'), 10);
          if (dropAuthoringReply && payload.task.includes('Start the granted design_orchestration')) { dropAuthoringReply = false; connection.close(); return; }
          result = { code: 'ACCEPTED', payload: { id: job.id, agent: 'fixture-bot' } }; break;
        }
        case 'job.status': {
          const job = jobs.get(payload.job);
          result.payload = { id: job.id, conversation: job.conversation, state: job.outcome ? 'DONE' : 'RUNNING', outcome: job.outcome ?? null }; break;
        }
        case 'job.list': result.payload = [...jobs.values()].map(job => ({ id:job.id,agent:'fixture-bot',conversation:job.conversation ?? null,state:job.outcome?'DONE':'RUNNING',outcome:job.outcome ?? null,limits:job.limits ?? null })); break;
        case 'job.limits': {
          const job = jobs.get(payload.job); job.limits = {maxTurns:payload.maxTurns,noTurnCap:false,maxModelCalls:payload.maxModelCalls,noBudget:false,modelCallsSpent:0};
          result.payload={id:job.id,state:job.outcome?'DONE':'RUNNING',conversation:job.conversation ?? null,limits:job.limits,outcome:job.outcome ?? null}; break;
        }
        case 'memory.write': result.payload = library['memory.write']; break;
        case 'memory.digest': case 'agent.curate': {
          const job = { id:`fixture-maintenance-${jobs.size+1}`,session:sessionId,conversation:null,task:frame.type }; jobs.set(job.id,job);
          push({kind:'started',job:job.id,agent:frame.type});
          setTimeout(() => { job.outcome={ending:'ANSWERED',answered:true,text:'Maintenance completed.'}; push({kind:'ended',job:job.id,ending:'ANSWERED',agent:frame.type}); },30);
          result={code:'ACCEPTED',payload:{id:job.id,agent:frame.type}}; break;
        }
        case 'board.topup': {
          const topic=board.details[payload.topic]?.value.topic;
          if(!topic){result={code:'NOT_FOUND',said:'Topic unavailable'};break;}
          topic.potTotal=payload.maxModelCalls;
          result.payload=topic; break;
        }
        case 'orchestration.caps': result.payload={...administrative[frame.type].payload,project:payload.project}; break;
        case 'job.cancel': { const job = jobs.get(payload.job); finish(job, 'CANCELLED'); result.payload = { id: job.id, conversation: job.conversation, state: 'DONE', outcome: job.outcome }; break; }
        case 'approval.list': result.payload = { approvals:payload.project?standingApprovals:approvals }; break;
        case 'approval.revoke': {
          const index=standingApprovals.findIndex(row=>row.id===payload.id);
          if(index>=0)standingApprovals.splice(index,1);result.payload={id:payload.id,revoked:index>=0};break;
        }
        case 'approval.answer': {
          const index = approvals.findIndex(row => row.id === payload.id);
          if (index < 0) { result = { code: 'BAD_REQUEST', said: 'Fixture approval already answered' }; break; }
          const [approval] = approvals.splice(index, 1);
          if (payload.decision==='project') standingApprovals.push({...approval,state:'allowed',scope:'project',prefix:payload.prefix,answeredAt:new Date().toISOString()});
          const job = start({ conversation: approval.conversation, task: `Approval ${payload.decision}: ${approval.id}` }, true, sessionId);
          if (options.controls) setTimeout(()=>finish(job,'ANSWERED','Approval continuation completed.'),30);
          result.payload = { id: approval.id, state: payload.decision === 'deny' ? 'denied' : 'allowed', job: job.id, busy: false, note: null }; break;
        }
        case 'job.stream': break;
        case 'inbox.list': result.payload = { items: (payload.unread ? unreadInbox() : inbox).slice(payload.offset ?? 0, (payload.offset ?? 0) + (payload.limit ?? 20)).map(item => ({ handle: 'fixture', firing: null, conversation: null, ending: null, ...item, readAt: readInbox.has(item.id) ? item.readAt ?? '2026-10-02T00:00:00Z' : null })), unread: unreadInbox().length }; break;
        case 'inbox.read':
          if (refuseReceipts) { result = { code: 'BAD_REQUEST', said: 'Fixture receipt refused' }; break; }
          for (const id of payload.items) if (inbox.some(item => item.id === id)) readInbox.add(id);
          result.payload = { marked: payload.items.length, unread: unreadInbox().length };
          push({ kind: 'inbox.changed', unread: unreadInbox().length }); break;
        case 'orchestration.list':
          if (refuseRuns && payload.state === 'waiting') { result = { code: 'BAD_REQUEST', said: 'Fixture live state unavailable' }; break; }
          result.payload = { orchestrations: (payload.state ? runRows.filter(run => run.state === payload.state) : options.authoring ? [...runRows].sort((a,b) => b.createdAt.localeCompare(a.createdAt)) : [...runRows].reverse()).slice(0, payload.limit ?? 20) }; break;
        case 'orchestration.status': {
          const run = runRows.find(run => run.id === payload.id);
          if (!run) { result = { code: 'BAD_REQUEST', said: 'Fixture run not found' }; break; }
          result.payload = { orchestration: run, todos: [{ id: 'fixture-todo', parent: null, position: 0, locked: false, updatedAt: '2026-10-02T00:00:00Z', text: 'Explore the source library', status: run.parent ? 'done' : 'in_progress', stage: 'reading', summary: 'References collected across projects.' }], messages: run.parent ? [] : [{ id: questionId, createdAt: '2026-10-02T00:00:00Z', deliveredAt: null, capKind: null, kind: 'question', author: 'conductor', text: 'Continue the research?', structure: run.definition === 'design_orchestration' ? { lead: 'Validated by the real loader trial in the server workflow.', name: 'draft_procedure', path: 'docs/orchestrations/draft.md', sha256: 'sha256:' + 'a'.repeat(64), text: '---\nname: draft_procedure\ntools: [file_read]\n---\nAn agent-driven procedure.\n', questions: [{ header: 'Install', question: 'Install the reviewed source?', multi: false, options: [{ label: 'Install', description: 'Grants file_read beyond fixture-bot. Write into the project.' }, { label: "Don’t install", description: 'Leave the draft in artifacts.' }] }] } : { lead: 'Choose the next research step.', questions: [{ header: 'Sources', question: 'Which sources should we read next?', multi: false, options: [{ label: 'Archives', description: 'Explore primary materials.' }] }] } }], children: run.parent ? [] : [{ id: 'fixture-child-1', state: 'finished' }] }; break;
        }
        case 'orchestration.definitions': result.payload = { definitions: [...(options.authoring ? [{ name: 'design_orchestration', description: 'Required system builder', tier: 'global', stages: [], triggers: [], served: true, withheld: null }] : []), { name: 'deep_research', description: 'Research from original materials', tier: payload.project ? 'project' : 'global', stages: [{ id: 'reading', doneWhen: 'Sources collected', mayReturnTo: [] }], triggers: ['research.requested'], served: true, withheld: null }, { name: 'unavailable_workflow', description: null, tier: 'global', stages: [], triggers: [], served: false, withheld: 'Required bot is unavailable' }] }; break;
        case 'orchestration.record': {
          const filtered = recordRows.filter(row => !payload.kinds?.length || payload.kinds.includes(row.kind));
          const bounded = filtered.filter(row => (payload.before === undefined || row.ordinal < payload.before) && (payload.after === undefined || row.ordinal > payload.after));
          const rows = payload.tail || payload.before !== undefined ? bounded.slice(-(payload.limit ?? 100)) : bounded.slice(0, payload.limit ?? 100);
          result.payload = { root: 'fixture-old-root', rows: malformedRecord ? [{ ordinal: 1 }] : rows, total: recordRows.length, limit: payload.limit ?? 100, through: 125, oldest: rows[0]?.ordinal ?? null, more: bounded.length > rows.length }; break;
        }
        case 'orchestration.answer': {
          const run = runRows.find(run => run.id === payload.id);
          if (!run || run.state !== 'asking') { result = { code: 'BAD_REQUEST', said: 'Question already answered elsewhere' }; break; }
          run.state = options.authoring && run.definition === 'design_orchestration' ? 'finished' : 'running'; run.pendingCap = null; result.payload = { id: run.id, state: run.state }; push({ kind: 'orchestration.changed', orchestration: run.id, state: run.state }); break;
        }
        case 'orchestration.cancel': {
          const run = runRows.find(run => run.id === payload.id);
          run.state = 'cancelled'; result.payload = { id: run.id, state: run.state }; push({ kind: 'orchestration.changed', orchestration: run.id, state: run.state }); break;
        }
        case 'document.list': { const rows=corpus.filter(row=>!payload.q||row.title.toLowerCase().includes(payload.q.toLowerCase()));result.payload=malformedLibrary?{documents:[]}: {...library[frame.type],documents:rows.slice(payload.offset??0,(payload.offset??0)+(payload.limit??20)),total:rows.length,offset:payload.offset??0,limit:payload.limit??20}; break; }
        case 'document.detail': result.payload={...library[frame.type],documentId:payload.document};break;
        case 'document.chunk': result.payload={...library[frame.type],chunkId:payload.chunk};break;
        case 'document.stance': result.payload={...library[frame.type],documentId:payload.document,claim:payload.claim};break;
        case 'document.citations': result.payload={...library[frame.type],scope:'document',citations:library[frame.type].citations.map(row=>({...row,documentId:row.standing==='document_gone'?null:payload.document}))};break;
        case 'document.search': result.payload={...library[frame.type],query:payload.query,mode:payload.mode};break;
        case 'document.retrieve': case 'document.rank': result.payload={...library[frame.type],query:payload.query};break;
        case 'memory.index': result.payload=library['memory.read'].state==='active'?library[frame.type]:[];break;
        case 'memory.read': library[frame.type]={...library[frame.type],uses:library[frame.type].uses+1,lastUsed:'2026-10-02T11:00:00Z'};result.payload=library[frame.type];break;
        case 'memory.recall': result.payload={...library[frame.type],question:payload.question,memories:[library['memory.read']]};break;
        case 'memory.navigate': result.payload={...library[frame.type],retrieval:{tier:payload.project?'project':'global',globalFallback:false,seedMode:'lexical',coverage:{eligible:1,indexed:0,passages:0,pending:0,failed:1,stale:0},fallback:'embedding provider unavailable',queryEmbeddingCalls:0,generation:'fixture-generation'}};break;
        case 'memory.invalidate': library['memory.read']={...library['memory.read'],state:'invalidated',invalidation:{at:'2026-10-02T10:00:00Z',by:payload.by,reason:payload.reason}};result.payload=library['memory.read'];break;
        case 'proposal.list': result.payload=payload.project?[]:library[frame.type];break;
        case 'proposal.resolve': {const proposal=library['proposal.list'].find(row=>row.id===payload.proposal);Object.assign(proposal,{state:payload.accept?'accepted':'rejected',resolvedAt:'2026-10-02T10:00:00Z',resolvedBy:payload.by,resolution:payload.reason});result.payload={proposal,promotedId:payload.accept?'global_memory':null,demoted:[]};break;}
        case 'memory.reembed': case 'proposal.reconsider': result.payload=library[frame.type];break;
        case 'conversation.search': result.payload={hits:searchHits.slice(payload.offset??0,(payload.offset??0)+(payload.limit??20)),total:searchHits.length,offset:payload.offset??0,limit:payload.limit??20,reach:{searched:1,ejected:2,recordedOnly:3},retrieval:{requestedMode:payload.mode,effectiveMode:'lexical',totalMeaning:'matches',snapshot:'fixture-snapshot',truncated:false,complete:false,fallback:'embedding provider unavailable',coverage:{eligible:52,indexed:0,passages:0,pending:0,failed:52,stale:0},generation:'fixture-generation',queryEmbeddingCalls:0,provenance:'live_lexical'}};break;
        case 'schedule.list': result.payload = [...schedules.values()]; break;
        case 'trigger.list': result.payload = [...triggers.values()]; break;
        case 'firing.list': result.payload = []; break;
        case 'schedule.read': result.payload = { cron: '0 0 9 * * *', zone: payload.zone, when: 'Every day at 9am', agent: 'fixture-bot', task: 'Read the original sources', intoConversation: !!payload.conversation, project: payload.project ?? null, conversation: payload.conversation ?? null, nextFires: ['2026-10-03T09:00:00+10:00','2026-10-04T09:00:00+10:00'], names: { schedule: 'fixture-morning', trigger: 'fixture-morning-run', event: 'fixture.morning' } }; break;
        case 'schedule.define': result.payload = { name: payload.schedule, cron: payload.cron, zone: payload.zone, emits: payload.emits, paused: false, nextFireAt: '2026-10-03T09:00:00+10:00', definedBy: 'fixture' }; schedules.set(payload.schedule, result.payload); break;
        case 'trigger.define':
          if (refuseTriggerDefinition) { result = { code: 'BAD_REQUEST', said: 'Fixture bot unavailable' }; break; }
          result.payload = { name: payload.trigger, event: payload.event, project: payload.project ?? null, conversation: payload.conversation ?? null, agent: payload.agent, task: payload.task, maxModelCalls: null, maxTurns: null, queueCap: 3, paused: false, definedBy: 'fixture' }; triggers.set(payload.trigger, result.payload); break;
        case 'schedule.pause': schedules.get(payload.schedule).paused = payload.paused; result = { code: 'NO_CONTENT', payload: null }; break;
        case 'trigger.pause': triggers.get(payload.trigger).paused = payload.paused; result = { code: 'NO_CONTENT', payload: null }; break;
        case 'schedule.forget': schedules.delete(payload.schedule); result = { code: 'NO_CONTENT', payload: null }; break;
        case 'trigger.forget': triggers.delete(payload.trigger); result = { code: 'NO_CONTENT', payload: null }; break;
        case 'event.fire': result.payload = []; break;
        default:
          if (frame.type.startsWith('union.')) {
            const claimed = fileSockets.get(payload.project)?.claim.session === sessionId;
            if (options.union) result = await options.union.ask(frame.type, payload, claimed);
            else if (frame.type === 'union.status') result = {code:'OK',payload:claimed ? {eligible:true,enabled:false,state:'OFFLINE',syncHidden:[],maxFileBytes:5242880,openConflicts:0,url:`/v1/sync/${encodeURIComponent(payload.project)}.git`} : {eligible:false,enabled:false}};
            else if (frame.type === 'union.conflict.list') result = {code:'OK',payload:{conflicts:[]}};
            else result = {code:'BAD_REQUEST',said:'Sync mutations require the Git-backed fixture'};
          } else result = { code: 'BAD_REQUEST', said: `Fixture does not implement ${frame.type}` };
      }
      connection.send(JSON.stringify({ id: frame.id, type: frame.type, protocol_version: frame.protocol_version, payload: result }));
    });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return { loseRunReply() { dropRunReply = true; }, loseAuthoringReply() { dropAuthoringReply = true; }, setMalformedLibrary: value=>{malformedLibrary=value;}, fileClaims, loginCount: () => logins,
    get eventSession() { return eventSession; },
    get fileClaim() { return fileClaim; },
    get liveFileClaims() { return [...fileSockets.values()].map(row => row.claim); },
    get rotations() { return rotations; },
    addServerProject(row) { projectRows.set(row.name, row); },
    queueApproval(id, conversation = 'fixture-first', legacy = false) {
      const row = approvalWire({ id, conversation, agent: 'fixture-bot', command: ['echo', id], cwd: '/fixture', reason: 'Review this exact request.' });
      approvals.push(row);
      const answer = `Approve running echo ${id} in /fixture on the server side? [${id}]`;
      inbox.unshift({ id: `inbox-${id}`, kind: 'approval', answer, ...(legacy ? {} : { about: `approval:${id}` }), arrivedAt: '2026-10-02T00:00:00Z' });
      const log = entries.get(conversation); const ordinal = (log.at(-1)?.ordinal ?? 0) + 1;
      log.push({ ordinal, turnOrdinal: ordinal, kind: 'answer', state: 'stands', excerpt: answer });
      grew(conversation); push({ kind: 'inbox.changed', unread: unreadInbox().length });
    },
    disconnectProject(name) { const id = fileSockets.get(name)?.claim.session; eventSockets.get(id)?.socket.close(); },
    setRefuseProjects(value) { refuseProjects = value; },
    setRefuseFiles(value) { refuseFiles = value; },
    setEmptyFileReady(value) { emptyFileReady = value; },
    loseFiles(project) { (project ? fileSockets.get(project)?.socket : fileSocket)?.close(1012, 'Server restarting'); },
    httpPaths, file: fileRequest, base: `http://127.0.0.1:${server.address().port}`, frames,
    publish: push,
    setRefuseBoard: value => { refuseBoard = value; },
    appendBoardMessage() { const detail = board.details['demo-board'].value; const previous = detail.messages.at(-1); detail.messages.push({ ...previous, id: 'fixture-board-update', kind: 'post', author: 'critic', title: null, body: 'A fresh swarm update from another session.', replyTo: previous.id, alert: true, postedAt: new Date().toISOString() }); board.topics.value[0].messages++; },
    get latestJob() { return [...jobs.values()].at(-1)?.id; },
    completeLatest: (text, pace) => finish([...jobs.values()].at(-1), 'ANSWERED', text, pace),
    commitLatest: text => commit([...jobs.values()].at(-1), text),
    completeJob: id => finish(jobs.get(id)),
    setContext: value => { const { prefix, sent, ...extra } = value; context = contextWire(sent ?? null, extra, prefix ?? undefined); },
    setSupportsFollowing: value => { supportsFollowing = value; },
    setRefuseReceipts: value => { refuseReceipts = value; },
    setRefuseTriggerDefinition: value => { refuseTriggerDefinition = value; },
    setMalformedRecord: value => { malformedRecord = value; },
    replaceQuestion() { questionId = 'fixture-replacement'; },
    settleRecord() { recordRows[123].detail = 'A later confirmed tool outcome.'; push({ kind: 'orchestration.recorded', root: 'fixture-old-root', through: 125, settled: 124 }); },
    setRefuseRuns: value => { refuseRuns = value; },
    changeRun(id, state) { const run = runRows.find(run => run.id === id); run.state = state; run.pendingCap = null; push({ kind: 'orchestration.changed', orchestration: id, state }); },
    addInboxNotice() { inbox.unshift({ id: `fixture-inbox-${inbox.length + 1}`, kind: 'notice', arrivedAt: '2026-10-01T11:00:00Z', answer: 'An inbox notice from another session.' }); push({ kind: 'inbox.changed', unread: unreadInbox().length }); },
    appendTurn(conversation, text) {
      const log = entries.get(conversation); const ordinal = (log.at(-1)?.ordinal ?? 0) + 1;
      log.push({ ordinal, turnOrdinal: ordinal, kind: 'utterance', state: 'stands', speaker: 'harness', speakerName: 'orchestration fixture-run', excerpt: `A background result for ${conversation}.` },
        { ordinal: ordinal + 1, turnOrdinal: ordinal, kind: 'answer', state: 'stands', excerpt: text });
      grew(conversation);
    },
    disconnect: () => { for (const row of eventSockets.values()) row.socket.close(); },
    async close() { for (const pending of filePending.values()) clearTimeout(pending.timer); for (const timer of timers) clearTimeout(timer); for (const client of wss.clients) client.terminate(); await new Promise(resolve => wss.close(resolve)); await new Promise(resolve => server.close(resolve)); assert.deepEqual(httpPaths.filter(path => !['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(path) && !(options.union?.http && path.startsWith('/v1/sync/'))), [], 'operational HTTP fallback'); },
  };
}
