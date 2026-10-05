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
  let informationExtraction='ready', informationReadError='';
  let manualChapters = [];
  const manualRows = filter => manualChapters.filter(row => (filter?.tags ?? []).every(tag => row.tags.includes(tag)));

  const eventSockets = new Map(), fileSockets = new Map();
  const botLatest = new Map();
  let pricing={billingRoute:'hosted',model:'deployment',pools:['hosted'],version:'boot:',origin:'configuration',card:null,configured:[],updatedAt:null};
  const projectGrants=new Map();
  const projectChanges=new Map();
  const projectRows = new Map([['Empty workspace', { name: 'Empty workspace' }]]);
  const board = demoBoard();
  // Display demos omit this nullable server field; the WS fixture carries the full DTO.
  for (const topic of new Set([...board.topics.value.map(row => row.topic), ...board.swarm.value.topics.map(row => row.topic), ...Object.values(board.details).flatMap(detail => [detail.value.topic, detail.value.root])])) {topic.quietNotifiedAt = null; if(options.boardProject)topic.project=options.boardProject;}
  let loseRetryAck = false;
  let refuseBoard = false, refuseProjects = false, refusePost = false; const postReceipts = new Map();
  let eventSession;
  let fileSocket, fileClaim, refuseFiles = false, emptyFileReady = false;
  const fileClaims = [];
  const filePending = new Map();
  let fileNumber = 0;
  for (const detail of Object.values(board.details)) { detail.value.topic.account = "fixture"; for (const seat of detail.value.seats) seat.seat.conversation = "fixture-board-seat"; for (const message of detail.value.messages) message.conversation = "fixture-board-seat"; }
  let dropAuthoringReply = false, dropRunReply = false;
  let context = contextWire(15240, {}, { model: 'fixture-model', contextLength: 131072 });
  const conversations = [conversationWire('fixture-first', { title: 'Fixture conversation', ...(options.conversationProject ? { project: options.conversationProject } : {}) })];
  const entries = new Map([['fixture-first', [{ ordinal: 1, turnOrdinal: 1, kind: 'utterance', state: 'stands', speaker: 'person', excerpt: 'An existing server conversation.' }, { ordinal: 2, turnOrdinal: 1, kind: 'answer', state: 'stands', excerpt: 'Loaded through the real TypeScript WebSocket binding. Treat <img src=x onerror="window.fixtureInjection=true"> as text.' }]]]);
  entries.set('fixture-board-seat', [{ ordinal: 1, turnOrdinal: 1, kind: 'utterance', state: 'stands', excerpt: 'A board seat wake.' }, { ordinal: 2, turnOrdinal: 1, kind: 'answer', state: 'stands', excerpt: 'Researching conflict strategies.' }]);
  const jobs = new Map(), schedules = new Map(), triggers = new Map(), scheduleFiles = new Map();
  let refuseTriggerDefinition = false;
  const timers = new Set();
  let informationTags=['my project'];
  let informationGroups=null;
  const currentGroups=()=>informationGroups ?? {databases:['postgresql']};
  const frames = [], httpPaths = [];
  const usageSubscriptions = new Map();
  let refuseUsage = false, refuseSnapshot = false, usageRevision = 1, captureEnabled = true;
  function usageReport(type, filter, input = '2400') {
    const totals = {calls:captureEnabled?'2':'0',attempts:captureEnabled?'2':'0',active_calls:'0',incomplete_attempts:'1',unknown_cost_attempts:'1',input_tokens:input,output_tokens:'300',input_tokens_known:'2',output_tokens_known:'1',costs:{USD:'0.01234'},usage_complete:false,cost_complete:false,complete:false};
    return {filters:{type,filter},totals,groups:[{...totals,model:'fixture-model'}],cursor:null,health:{watermark:String(usageRevision),as_of:new Date().toISOString(),capture_enabled:captureEnabled,historical_usage:'not_imported',tracking_started_at:'2026-10-02T00:00:00Z'}};
  }
  const administrative = JSON.parse(readFileSync(new URL('../../test-support/contracts/ws-administrative-fixtures.json',import.meta.url),'utf8'));
  const standingApprovals = [];
  const messageInstances = [{id:'ins_fixture',project:'Research',agent:'fixture-bot',conversation:'fixture-message',lifetime:'persistent',defaultInstance:true,active:true,archived:false,state:'idle',pending:0,job:null,createdAt:'2026-10-03T00:00:00Z'}];
  const messageDeliveries = [{message:'bdm_fixture',sender:'ins_fixture_sender',recipient:'ins_fixture',replyTo:null,replyExpected:true,finalReply:false,generated:false,state:'awaiting',ending:null,reply:null,job:null,deadlineAt:null,postedAt:'2026-10-03T00:00:00Z',body:'Please review this. <img src=x onerror="window.fixtureInjection=true">'}];
  const messageOpenReceipts = new Map();
  const workflowReceipts = new Map();
  let workflowAckMissing = false;
  const adminAccounts = new Map([['fixture',{handle:'fixture',enabled:true,serverAdmin:options.serverAdmin === true,mustChangePassword:false,createdAt:'2026-10-04T00:00:00Z'}]]);
  const serviceAccounts = new Map(), serviceTokens = new Map();
  let refuseServiceTokenListingOnce = false;
  let serviceTokenNumber = 0, serviceCredentialNumber = 0;
  const adminAudit = [];
  const auditAdmin = (action,target) => adminAudit.unshift({id:adminAudit.length+1,occurredAt:'2026-10-04T00:00:00Z',actor:'fixture',action,target:target.handle,enabled:target.enabled,serverAdmin:target.serverAdmin});
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
  let refuseReceipts = false, refuseRuns = false, questionOverride;
  const runRows = [{ id: 'fixture-old-root', definition: 'deep_research', tier: 'project', project: 'Other project', state: 'asking', depth: 0, pendingCap: 'turn_cap', stalledSince: null, createdAt: '2020-01-01T00:00:00Z' },
    ...Array.from({ length: 21 }, (_, i) => ({ id: `fixture-child-${i + 1}`, definition: 'source_reading', tier: 'project', project: 'Other project', state: 'finished', parent: 'fixture-old-root', depth: 1, createdAt: '2026-10-01T09:00:00Z', result: 'A completed child result.' }))];
  for (let i = 0; i < runRows.length; i++) runRows[i] = runWire(runRows[i].id, runRows[i].state, { ...runRows[i], callerConversation: options.stageNavigation ? 'fixture-first' : null, conductorConversation: 'fixture-conductor' });
  if (options.retainedReport) {
    const coordinates=JSON.stringify({operation:'read',revision:options.retainedReport,offset:0,limit:8192});
    Object.assign(runRows[0], {state:'finished',pendingCap:null,result:`Research completed: 2 objectives and 3 findings. The full report with source links is retained as information revision ${options.retainedReport}. Read it with information_read ${coordinates}; continue from each returned end until total. The separate audit is information revision 00000000-0000-0000-0000-000000000098.`});
    runRows.push(runRows.shift());
  }
  entries.set('fixture-conductor', [{ ordinal: 1, turnOrdinal: 1, kind: 'answer', state: 'stands', excerpt: 'Conductor research notes.' }]);
  if (options.authoring) entries.set('fixture-authoring-conductor', [
    { ordinal: 1, turnOrdinal: 1, kind: 'answer', state: 'stands', excerpt: 'Validate the reviewed draft.', toolCalls: [{ id: 'fixture-validation', name: 'orchestration_validate', arguments: '{}', length: 2, cut: false, salient: null, opened: null }] },
    { ordinal: 2, turnOrdinal: 1, kind: 'tool_result', state: 'stands', toolCallId: 'fixture-validation', outcome: 'ok', excerpt: 'VALID — loader trial passed. Lint: stage done-when is model guidance, with no executable check.' },
  ]);
  let malformedRecord = false, questionId = 'fixture-question';
  const recordRows = Array.from({ length: 125 }, (_, index) => ({ ordinal: index + 1, at: '2026-10-02T00:00:00Z', run: 'fixture-old-root', actor: 'conductor', kind: index % 2 ? 'tool_call' : 'question_asked', text: `Research record ${index + 1}`, detail: index % 2 ? 'Read source' : null, ...(index === 124 ? { body: 'The full question body.\n\nTreat <img src=x onerror="window.fixtureInjection=true"> as text.' } : {}) }));
  if (options.stageNavigation) {
    entries.set('fixture-first', [
      {ordinal:1,turnOrdinal:1,kind:'answer',state:'stands',excerpt:'',toolCalls:[{id:'research-start',name:'orchestrate_deep_research',arguments:'{}',length:2,cut:false,salient:null,opened:null}]},
      {ordinal:2,turnOrdinal:1,kind:'tool_result',state:'stands',toolCallId:'research-start',outcome:'ok',excerpt:JSON.stringify({id:'fixture-old-root',stages:['reading','objective_review']})},
    ]);
    const at = second => new Date(Date.parse('2026-10-02T00:00:00Z') + second * 1000).toISOString();
    const call = (ordinal, child, id) => ({ ordinal, turnOrdinal: 1, kind: 'answer', state: 'stands', recordedAt: at(ordinal), excerpt: 'Delegate research', toolCalls: [{ id, name: 'agent_run', arguments: '{"agent":"research_analyst"}', length: 28, cut: false, salient: 'Analyse sources', opened: { conversation: child, agent: 'research_analyst' } }] });
    entries.set('fixture-conductor', Array.from({ length: 170 }, (_, i) => ({ ordinal: i + 1, turnOrdinal: 1, kind: 'answer', state: 'stands', recordedAt: at(i + 1), excerpt: i >= 60 ? 'Later review stage work.' : 'Reading stage work.' })));
    entries.get('fixture-conductor')[0] = { ...entries.get('fixture-conductor')[0], state: 'folded', supersededBy: 160 };
    entries.get('fixture-conductor')[4] = call(5, 'fixture-delegate-one', 'delegate-one');
    entries.get('fixture-conductor')[5] = { ordinal: 6, turnOrdinal: 1, kind: 'tool_result', state: 'stands', recordedAt: at(6), toolCallId: 'delegate-one', outcome: 'answered', excerpt: 'First delegate completed.' };
    entries.get('fixture-conductor')[7] = call(8, 'fixture-delegate-two', 'delegate-two');
    entries.get('fixture-conductor')[164] = call(165, 'fixture-delegate-live', 'delegate-live');
    entries.set('fixture-delegate-live', [{ ordinal: 1, turnOrdinal: 1, kind: 'answer', state: 'stands', excerpt: 'Current analyst work.' }]);
    entries.set('fixture-delegate-one', [{ ...call(1, 'fixture-nested-editor', 'nested-editor'), excerpt: 'Nested analyst work.', toolCalls: [{ ...call(1, 'fixture-nested-editor', 'nested-editor').toolCalls[0], opened: { conversation: 'fixture-nested-editor', agent: 'editor' } }] }]);
    entries.set('fixture-delegate-two', [{ ordinal: 1, turnOrdinal: 1, kind: 'answer', state: 'stands', excerpt: 'Second analyst work.' }]);
    entries.set('fixture-nested-editor', [{ ordinal: 1, turnOrdinal: 1, kind: 'answer', state: 'stands', excerpt: 'Nested editor verification.' }]);
    const transition = (ordinal, stage, text, second) => ({ ordinal, at: at(second), run: 'fixture-old-root', actor: 'conductor', kind: 'stage_moved', text: `${stage}: ${text}`, detail: null });
    recordRows[0] = transition(1, 'reading', 'pending → in_progress', 0);
    recordRows[1] = { ordinal: 2, at: at(5), run: 'fixture-old-root', actor: 'conductor', kind: 'delegated', text: 'conductor → research_analyst: Analyse sources', detail: null };
    recordRows[59] = transition(60, 'reading', 'in_progress → done', 60);
    recordRows[60] = transition(61, 'objective_review', 'pending → in_progress', 60);
    recordRows[61] = { ordinal: 62, at: at(62), run: 'fixture-old-root', actor: 'conductor', kind: 'question_asked', text: 'Review marker. Do these objectives match?', body: 'Review marker. Do these objectives match?', detail: null };
  }
  const unreadInbox = () => inbox.filter(item => !readInbox.has(item.id));
  const approvals = [approvalWire({ id: 'fixture-approval', conversation: 'fixture-first', agent: 'fixture-bot', command: ['echo','fixture approval'] })];
  let socket;
  let logins = 0;
  let setupPending = options.setup === true;
  let supportsFollowing = true;
  const server = createServer(async (req, res) => {
    httpPaths.push((req.url ?? '').split('?')[0]);
    if (req.url?.startsWith('/v1/sync/') && options.union?.http) {
      if (req.headers.authorization !== `Bearer ${access}`) { res.writeHead(401).end(); return; }
      return options.union.http(req, res);
    }
    if (req.url === '/v1/auth/setup' && setupPending) {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString());
      if (body.temporaryPassword !== 'fixture-temporary' || body.handle !== 'fixture' || body.password !== 'fixture-password') { res.writeHead(400).end(); return; }
      setupPending = false; res.writeHead(204).end(); return;
    }
    if (req.url === '/v1/auth/login') {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString());
      if (setupPending && body.handle === 'admin' && body.password === 'fixture-temporary') {
        res.writeHead(200, { 'Content-Type': 'application/json', 'X-Plowshare-Setup-Required': 'true' });
        res.end(JSON.stringify({ access, refresh: null, mustChangePassword: true })); return;
      }
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
      projectRows.set(claim.project, { ...projectRows.get(claim.project), name: claim.project, machine: claim.machine, workspace: claim.root });
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
        case 'relay.topics': case 'relay.log': case 'relay.operate':
          result = options.relay ? options.relay(frame.type, payload) : {code:'BAD_REQUEST',said:'Relay fixture is not configured'};
          break;
        case 'information.facets': {
          const names=['kind','tags','autoTag','tagGroup','author','documentAuthor','when','subtype'],filter=payload.filter ?? {};
          if (filter.tags?.includes('plowshare-manual')) {
            const selected = manualRows(filter), tags = [...new Set(selected.flatMap(row => row.tags))];
            result.payload = { total: selected.length,
              facets: Object.fromEntries(names.map(name => [name, name === 'tags' ? tags.map(value => ({value, count: selected.filter(row => row.tags.includes(value)).length})) : []])),
              hasMore: Object.fromEntries(names.map(name => [name, false])), tagGraph: {edges: [], hasMore: false} };
            break;
          }
          const matching=payload.scope.kind!=='shared' && (!filter.tags || filter.tags.every(tag=>informationTags.includes(tag))) && (!filter.autoTag || filter.autoTag.every(tag=>['postgresql'].includes(tag))) && (!filter.tagGroup || filter.tagGroup in currentGroups()) && (!filter.documentAuthor || filter.documentAuthor==='Ada Lovelace') && (!filter.search || 'A retained claim'.toLowerCase().includes(filter.search.toLowerCase()));
          result.payload={total:matching?1:0,facets:Object.fromEntries(names.map(name=>[name,matching?({kind:[{value:payload.kind??'source',count:1}],tags:informationTags.map(value=>({value,count:1})),autoTag:[{value:'postgresql',count:1}],tagGroup:Object.keys(currentGroups()).map(value=>({value,count:1})),author:[{value:'fixture',count:1}],documentAuthor:[{value:'Ada Lovelace',count:1}],when:[{value:'2024-10',count:1}],subtype:[{value:'text',count:1}]}[name]):[]])),hasMore:Object.fromEntries(names.map(name=>[name,false])),tagGraph:{edges:matching?Object.entries(currentGroups()).flatMap(([group,tags])=>tags.map(tag=>({group,tag,count:1}))):[],hasMore:false}};break;
        }
        case 'information.tags.groups': informationGroups=payload.groups;result.payload={changed:true};break;
        case 'information.tags': informationTags=payload.tags;result.payload={changed:true};break;
        case 'information.list': case 'information.inventory':
          if (payload.filter?.tags?.includes('plowshare-manual')) {
            assert.equal(payload.scope.kind, 'shared');
            result.payload = manualRows(payload.filter).slice(payload.offset ?? 0, (payload.offset ?? 0) + (payload.limit ?? 20)).map(({text, ...row}) => row);
            break;
          }
          result.payload=(payload.scope.kind==='shared'||payload.filter?.tags?.some(tag=>!informationTags.includes(tag))||payload.filter?.autoTag?.some(tag=>tag!=='postgresql')||payload.filter?.tagGroup&&!(payload.filter.tagGroup in currentGroups())||payload.filter?.documentAuthor&&payload.filter.documentAuthor!=='Ada Lovelace'||payload.filter?.search&&!('A retained claim'.toLowerCase().includes(payload.filter.search.toLowerCase())))?[]:[{id:'aaaaaaaa-0000-0000-0000-000000000001',title:payload.kind==='report'?'Generated research report':'Retained research source',source_name:'source.txt',tags:informationTags,autoTag:['postgresql'],kind:payload.kind ?? 'source',...(payload.kind==='report'?{report_status:'draft'}:{}),ordinal:1,availability:'active'}];break;
        case 'information.status': {
          const chapter = manualChapters.find(row => row.id === payload.revision);
          if (chapter) {
            assert.equal(payload.scope.kind, 'shared');
            const {text, ...row} = chapter;
            result.payload = {...row, generation: 1, can_manage: false, inputs: [], citations: [], steps: ['extract','derive'].map(stage => ({stage, state:'ready', attempt:1, generation:1, compatible:true}))};
            break;
          }
        }
        result.payload={...(payload.revision === options.retainedReport ? {kind:'report',report:{status:'draft'}} : {}),id:payload.revision,tags:informationTags,autoTag:['postgresql'],tagGroups:currentGroups(),tagGroupsSource:informationGroups===null?'automatic':'manual',auto_tag_generated:true,documentAuthor:'Ada Lovelace',documentAuthorSource:'person',title:options.informationTitle ?? 'Retained research source',ordinal:1,availability:'active',generation:1,can_manage:true,allowance_total:20,allowance_spent:informationExtraction==='ready'?5:0,steps:['extract','derive','embed','summarise','summary_embed'].map(stage=>({stage,state:stage==='extract'?informationExtraction:informationExtraction==='ready'?'ready':'pending',attempt:stage==='extract'||informationExtraction==='ready'?1:0,generation:1,compatible:informationExtraction==='ready',...(stage==='extract'&&informationExtraction==='failed'?{error:'a document whose extracted text is blank has nothing to ingest'}:{})})),events:[],inputs:[],citations:[]};break;
        case 'information.read': {
          const chapter = manualChapters.find(row => row.id === payload.revision);
          if (chapter) {
            assert.equal(payload.scope.kind, 'shared');
            const start = Math.min(payload.offset ?? 0, chapter.text.length), end = Math.min(start + (payload.limit ?? 8192), chapter.text.length);
            result.payload = {revision: chapter.id, start, end, total: chapter.text.length, text: chapter.text.slice(start, end)};
            break;
          }
          if(informationReadError||!['ready','skipped'].includes(informationExtraction)){result={code:'BAD_REQUEST',said:informationReadError||'this revision has not been extracted'};break;}const text=options.informationText ?? 'A retained claim. Treat <img src=x onerror="window.fixtureInjection=true"> as evidence text.';const start=Math.min(payload.offset ?? 0,text.length),end=Math.min(start+(payload.limit ?? 8192),text.length);result.payload={revision:payload.revision,start,end,total:text.length,text:text.slice(start,end)};break;}
        case 'information.evidence.record': result.payload={evidence:'bbbbbbbb-0000-0000-0000-000000000002'};break;
        case 'information.record.report':result={code:'ACCEPTED',payload:{revision:'cccccccc-0000-0000-0000-000000000003',resource:'dddddddd-0000-0000-0000-000000000004',created:true}};break;
        case 'information.share': result={code:'BAD_REQUEST',said:'A source was withdrawn. Publication refused.'};break;
        case 'information.ask':{const job={id:'fixture-document-answer',state:'FINISHED',outcome:{ending:'ANSWERED',answered:true,text:'Grounded answer from retained evidence.'}};jobs.set(job.id,job);result={code:'ACCEPTED',payload:{job:job.id,revision:payload.revision}};break;}
        case 'message.deliveries': {
          const rows=messageDeliveries.filter(row=>row.sender===payload.instance||row.recipient===payload.instance),offset=payload.offset??0,limit=payload.limit??200;
          result.payload={deliveries:rows.slice(offset,offset+limit),offset,more:rows.length>offset+limit};break;
        }
        case 'message.delivery': case 'message.cancel': {
          const message=messageDeliveries.find(row=>row.message===payload.message);
          if(!message){result={code:'BAD_REQUEST',said:'No accessible message.'};break;}
          if(frame.type==='message.cancel'){message.state='cancelled';message.ending='CANCELLED';message.reply='bdm_fixture_final';}
          result.payload={...message};break;
        }
        case 'message.instances': {
          const rows = messageInstances.filter(row => row.project === payload.project && (payload.archived || !row.archived));
          const offset = payload.offset ?? 0, limit = payload.limit ?? 200;
          result.payload = {instances:rows.slice(offset,offset+limit),more:rows.length>offset+limit,offset}; break;
        }
        case 'message.instance.open': {
          let instance = messageOpenReceipts.get(payload.requestId);
          if (!instance) {
            if (payload.makeDefault) for (const row of messageInstances) if (row.project === payload.project && row.agent === payload.agent) row.defaultInstance=false;
            instance={id:'ins_fixture_'+messageInstances.length,project:payload.project,agent:payload.agent,conversation:'fixture-message-'+messageInstances.length,lifetime:'persistent',defaultInstance:payload.makeDefault??false,active:true,archived:false,state:'idle',pending:0,job:null,createdAt:'2026-10-03T00:00:00Z'};
            messageInstances.push(instance);messageOpenReceipts.set(payload.requestId,instance);
          }
          result.payload={...instance};break;
        }
        case 'message.instance.default': case 'message.instance.stop': case 'message.instance.archive': {
          const instance=messageInstances.find(row=>row.id===payload.instance);
          if(!instance){result={code:'BAD_REQUEST',said:'No accessible message instance.'};break;}
          if(frame.type==='message.instance.default') {
            for(const row of messageInstances)if(row.project===instance.project&&row.agent===instance.agent)row.defaultInstance=false;
            instance.defaultInstance=true;
          } else {instance.active=false;instance.defaultInstance=false;instance.archived=frame.type==='message.instance.archive';instance.state=instance.archived?'archived':'stopped';instance.pending=0;}
          result.payload={...instance};break;
        }
        case 'board.topics': {
          const rows = board.topics.value.filter(row => !payload.project || row.topic.project === payload.project);
          result = refuseBoard ? { code: 'BAD_REQUEST', said: 'Board inspection temporarily unavailable.' } : { code: 'OK', payload: { topics: rows.slice(payload.offset ?? 0, (payload.offset ?? 0) + (payload.limit ?? 200)), more: false, offset: payload.offset ?? 0 } }; break;
        }
        case 'board.open': {
          if(refusePost){result={code:'BAD_REQUEST',said:'Topic acknowledgment unavailable.'};break;}
          let receipt=postReceipts.get(payload.requestId);
          if(receipt && receipt.identity!==JSON.stringify(payload)){result={code:'BAD_REQUEST',said:'Request identity changed.'};break;}
          if(!receipt){
            const id='topic-'+payload.requestId, template=board.details['demo-board'].value;
            const topic={...template.topic,id,root:id,parent:null,depth:0,project:payload.project,title:payload.title,label:payload.label,account:'fixture',openerKind:'person',opener:'fixture',originConversation:null,state:'open',resolution:null,closedAt:null,potTotal:payload.maxModelCalls ?? 120,potSpent:0,reserve:1,openedAt:new Date().toISOString()};
            const message={...template.messages[0],id:'opening-'+payload.requestId,topic:id,authorKind:'person',author:'fixture',conversation:null,entry:null,kind:'post',title:null,body:payload.body,replyTo:null,alert:false,mentions:[]};
            receipt={identity:JSON.stringify(payload),topic,message};postReceipts.set(payload.requestId,receipt);
            board.topics.value.unshift({topic,messages:1,documents:0});board.details[id]={value:{topic,root:topic,messages:[message],seats:[],decisions:[]}};
          }
          result={code:'OK',payload:{requestId:payload.requestId,topic:receipt.topic,message:receipt.message}};break;
        }
        case 'board.retry': {
          let receipt = postReceipts.get(payload.requestId);
          if (receipt && receipt.identity !== JSON.stringify(payload)) {result={code:'BAD_REQUEST',said:'Request identity changed.'};break;}
          if (!receipt) {
            const detail = board.details[payload.topic]?.value;
            const seat = detail?.seats.find(s=>s.seat.occupant===payload.member);
            if (!seat?.seat.failedEnding) {result={code:'BAD_REQUEST',said:'Choose a failed member.'};break;}
            const message = {...detail.messages[0], id:'retry-'+payload.requestId, topic:payload.topic, authorKind:'person', author:'fixture', conversation:null, entry:null, kind:'post', title:null, body:'Continue in your existing conversation.', replyTo:null, alert:false, mentions:[payload.member]};
            receipt={identity:JSON.stringify(payload),message}; postReceipts.set(payload.requestId,receipt); detail.messages.push(message);
            for (const s of [...detail.seats, ...board.swarm.value.seats]) if(s.seat.topic===payload.topic && s.seat.occupant===payload.member) {s.seat.failedEnding=null;s.state='ready';}
          }
          result={code:'OK',payload:{requestId:payload.requestId,member:payload.member,maxTurns:payload.maxTurns,message:receipt.message}};
          if (loseRetryAck) {loseRetryAck=false;result.payload={};}
          break;
        }
        case 'board.post': {
          if(refusePost){result={code:'BAD_REQUEST',said:'Post acknowledgment unavailable.'};break;}
          let receipt=postReceipts.get(payload.requestId);
          if(receipt && receipt.identity!==JSON.stringify(payload)){result={code:'BAD_REQUEST',said:'Request identity changed.'};break;}
          if(!receipt){const message={...board.details['demo-board'].value.messages[0],id:'person-'+payload.requestId,topic:payload.topic,authorKind:'person',author:'fixture',conversation:null,entry:null,kind:'post',title:null,body:payload.body,replyTo:null,alert:false,mentions:[]}; receipt={identity:JSON.stringify(payload),message};postReceipts.set(payload.requestId,receipt); const detail=board.details[payload.topic]?.value;if(detail)detail.messages.push(message);const row=board.topics.value.find(row=>row.topic.id===payload.topic);if(row)row.messages++;}
          result={code:'OK',payload:{requestId:payload.requestId,message:receipt.message}};break;
        }
        case 'board.messages': result = refuseBoard ? { code: 'BAD_REQUEST', said: 'Board inspection temporarily unavailable.' } : { code: 'OK', payload: board.details[payload.topic]?.value }; break;
        case 'swarm.status': result = refuseBoard ? { code: 'BAD_REQUEST', said: 'Swarm inspection temporarily unavailable.' } : { code: 'OK', payload: board.swarm.value }; break;
        case 'admin.pricing.list': result.payload=[pricing];break;
        case 'admin.pricing.set': {
          if(payload.expectedVersion!==pricing.version){result={code:'BAD_REQUEST',said:'Pricing changed; refresh before saving'};break;}
          pricing={...pricing,version:'price-'+frames.length,origin:'override',updatedAt:new Date().toISOString(),card:{revision:'operator-'+frames.length,mode:payload.mode,currency:payload.currency ?? null,rates:payload.rates?{input:null,output:null,cacheRead:null,cacheWrite:null,...payload.rates}:null,tiers:payload.tiers ?? [],requestFee:payload.requestFee ?? null,source:payload.source ?? 'operator',validFrom:null,validUntil:null}};
          result.payload=pricing;break;
        }
        case 'usage.subscribe': {
          if(refuseUsage){result={code:'BAD_REQUEST',said:'Usage capture is unavailable on this server.'};break;}
          const {report_type, ...filter}=payload, report=usageReport(report_type,filter);
          const subscription=`usage-${frames.length}`;
          usageSubscriptions.set(subscription,{connection,type:report_type,filter});
          result.payload={subscription,revision:usageRevision,filters:report.filters,report};break;
        }
        case 'usage.unsubscribe': usageSubscriptions.delete(payload.subscription);result.payload={};break;
        case 'usage.models': case 'usage.project': case 'usage.agent': case 'usage.run': case 'usage.orchestration': case 'usage.pools': case 'usage.conversation': result.payload=usageReport(frame.type,payload);break;
        case 'usage.calls': result.payload={filters:{type:frame.type,filter:payload},calls:[],cursor:null,health:{watermark:'1',capture_enabled:true}};break;
        case 'conversation.context.snapshot': {
          if(refuseSnapshot){result={code:'BAD_REQUEST',said:'Context snapshot unavailable. Update the server.'};break;}
          result.payload={conversation:payload.conversation,agent:payload.agent,projection:'next',captured_at:new Date().toISOString(),model:'fixture-model',sampling:{temperature:0.2},
            messages:[{role:'system',parts:[{type:'text',text:'Use retained evidence. <img src=x onerror="window.fixtureInjection=true">'}],tool_calls:[],tool_call_id:null},
            {role:'user',parts:[{type:'text',text:'Stored question in the current projection.'}],tool_calls:[],tool_call_id:null},
            {role:'assistant',parts:[],tool_calls:[{id:'call-1',name:'lookup',arguments:'{"query":"stored"}'}],tool_call_id:null},
            {role:'tool',parts:[{type:'text',text:'Evidence retained in the projection.'}],tool_calls:[],tool_call_id:'call-1'}],
            tools:[{name:'lookup',description:'Read retained evidence',parameters:{type:'object',properties:{query:{type:'string'}}}}],
            count:payload.measure?{basis:'MEASURED',tokens:'765',gaps:[]}:null};break;
        }
        case 'conversation.context': result.payload = context; break;
        case 'admin.status': result = {code:'OK',payload:{handle:'fixture',serverAdmin:adminAccounts.get('fixture').serverAdmin}}; break;
        case 'admin.service.accounts': result.payload=[...serviceAccounts.values()];break;
        case 'admin.service.account.create': {const account={handle:payload.handle,enabled:true,createdAt:new Date().toISOString()};serviceAccounts.set(account.handle,account);result.payload=account;break;}
        case 'admin.service.account.update': {const account=serviceAccounts.get(payload.handle);account.enabled=payload.enabled;if(!account.enabled)for(const token of serviceTokens.values())if(token.handle===account.handle)token.value.revokedAt=new Date().toISOString();result.payload=account;break;}
        case 'admin.service.tokens': if(refuseServiceTokenListingOnce){refuseServiceTokenListingOnce=false;result={code:'BAD_REQUEST',said:'Token metadata unavailable for this refresh'};}else result.payload=[...serviceTokens.values()].filter(row=>row.handle===payload.handle).map(row=>row.value);break;
        case 'admin.service.token.create': {
          if(payload.scopes.some(scope=>!projectGrants.get(scope.project)?.has(payload.handle))){result={code:'BAD_REQUEST',said:'Grant service account project access before issuing a token'};break;}
          const id='00000000-0000-0000-0000-'+String(++serviceTokenNumber).padStart(12,'0');
          const token={id,name:payload.name,principal:'@service/'+id,createdAt:new Date().toISOString(),expiresAt:new Date(Date.now()+(payload.expiresInDays ?? 30)*86400000).toISOString(),revokedAt:null,scopes:payload.scopes};
          serviceTokens.set(id,{handle:payload.handle,value:token});result.payload={token,credential:'pss_fixture-service-'+(++serviceCredentialNumber)};break;
        }
        case 'admin.service.token.rotate': {const token=serviceTokens.get(payload.id).value;token.revokedAt=null;token.expiresAt=new Date(Date.now()+(payload.expiresInDays ?? 30)*86400000).toISOString();result.payload={token,credential:'pss_fixture-service-'+(++serviceCredentialNumber)};break;}
        case 'admin.service.token.revoke': {const token=serviceTokens.get(payload.id).value;token.revokedAt=new Date().toISOString();result.payload=token;break;}
        case 'admin.accounts': result.payload=[...adminAccounts.values()]; break;
        case 'admin.account.create': {
          if(adminAccounts.has(payload.handle)){result={code:'BAD_REQUEST',said:'That account handle already exists'};break;}
          const account={handle:payload.handle,enabled:true,serverAdmin:payload.serverAdmin ?? false,mustChangePassword:true,createdAt:'2026-10-04T00:00:00Z'};
          adminAccounts.set(account.handle,account);auditAdmin('account.create',account);result.payload={account,temporaryPassword:'fixture-one-time-password'};break;
        }
        case 'admin.account.update': {
          const previous=adminAccounts.get(payload.handle);const account={...previous,...payload};
          if(previous.serverAdmin&&previous.enabled&&!(account.serverAdmin&&account.enabled)&&[...adminAccounts.values()].filter(row=>row.enabled&&row.serverAdmin).length===1){result={code:'BAD_REQUEST',said:'The last enabled server administrator cannot be disabled or demoted'};break;}
          adminAccounts.set(account.handle,account);auditAdmin('account.update',account);result.payload=account;break;
        }
        case 'admin.account.reset': {const account=adminAccounts.get(payload.handle);account.mustChangePassword=true;auditAdmin('account.password.reset',account);result.payload={account,temporaryPassword:'fixture-reset-password'};break;}
        case 'admin.sessions': result.payload=[{id:'11111111-1111-1111-1111-111111111111',restricted:false,createdAt:'2026-10-04T00:00:00Z',expiresAt:'2026-10-05T00:00:00Z'}];break;
        case 'admin.session.revoke': auditAdmin('session.revoke',adminAccounts.get(payload.handle));result.payload={handle:payload.handle};break;
        case 'admin.audit': {const rows=adminAudit.filter(row=>(!payload.before||row.id<payload.before)&&(!payload.handle||row.target===payload.handle)).slice(0,payload.limit ?? 50);result.payload={entries:rows,before:rows.length===(payload.limit ?? 50)?rows.at(-1).id:0};break;}
        case 'project.access':
        case 'project.member.add':
        case 'project.member.remove':
        case 'project.member.role': {
          const row=projectRows.get(payload.project);if(!row){result={code:'NOT_FOUND',said:'Unknown project'};break;}
          const grants=projectGrants.get(payload.project) ?? new Map([['fixture','MANAGER']]);projectGrants.set(payload.project,grants);
          const history=projectChanges.get(payload.project) ?? [];projectChanges.set(payload.project,history);
          if(frame.type!=='project.access') {
            if(row.role==='VIEWER'||row.role==='CONTRIBUTOR'){result={code:'BAD_REQUEST',said:'MANAGER access required'};break;}
            if(frame.type==='project.member.remove')grants.delete(payload.handle);else grants.set(payload.handle,payload.role ?? 'CONTRIBUTOR');
            history.unshift({id:history.length+1,occurredAt:'2026-10-04T00:00:00Z',actor:'fixture',target:payload.handle,action:frame.type,role:frame.type==='project.member.remove'?null:payload.role ?? 'CONTRIBUTOR'});
          }
          result.payload=['project.member.add','project.member.remove'].includes(frame.type)?{project:payload.project,members:[...grants.keys()]}:{project:payload.project,role:row.role ?? 'MANAGER',permissions:row.role==='VIEWER'?['read']:['read','work','manage'],members:[...grants].map(([handle,role])=>({handle,role})),history};break;
        }
        case 'project.create':
        case 'project.define': {
          projectRows.set(payload.name, { name: payload.name, role:'MANAGER', workspace: payload.workspace || '/server/provisioned', type: payload.type || 'STANDARD', readOnly: payload.writePaths?.length === 0, writePaths: payload.writePaths || ['.'], machine: null, lent: [], exclusions: [], members: ['fixture'] });
          result = { code: 'OK', payload: projectWire(payload.name, projectRows.get(payload.name)) }; break;
        }
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
        case 'conversation.latest': {
          const id = botLatest.get(JSON.stringify([payload.project, payload.agent]));
          result = { code: 'OK', ...(id ? { payload: conversations.find(row => row.id === id) } : {}) }; break;
        }
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
        case 'orchestration.start': {
          let receipt = workflowReceipts.get(payload.requestId);
          if (!receipt) { receipt = {id:'orc_fixture_started_'+workflowReceipts.size,state:'running',requestId:payload.requestId};workflowReceipts.set(payload.requestId,receipt); }
          result={code:'ACCEPTED',payload:workflowAckMissing?{}:receipt};break;
        }
        case 'orchestration.list':
          if (refuseRuns && payload.state === 'waiting') { result = { code: 'BAD_REQUEST', said: 'Fixture live state unavailable' }; break; }
          result.payload = { orchestrations: (payload.state ? runRows.filter(run => run.state === payload.state) : options.authoring ? [...runRows].sort((a,b) => b.createdAt.localeCompare(a.createdAt)) : [...runRows].reverse()).slice(0, payload.limit ?? 20) }; break;
        case 'orchestration.status': {
          const run = runRows.find(run => run.id === payload.id);
          if (!run) { result = { code: 'BAD_REQUEST', said: 'Fixture run not found' }; break; }
          result.payload = { orchestration: run, todos: [{ id: 'fixture-todo', parent: null, position: 0, locked: false, updatedAt: '2026-10-02T00:00:00Z', text: 'Explore the source library', status: run.parent ? 'done' : 'in_progress', stage: 'reading', summary: 'References collected across projects.' }], messages: run.parent ? [] : [{ id: questionId, createdAt: '2026-10-02T00:00:00Z', deliveredAt: null, capKind: null, kind: 'question', author: 'conductor', text: 'Continue the research?', structure: run.definition === 'design_orchestration' ? { lead: 'Validated by the real loader trial in the server workflow.', name: 'draft_procedure', path: 'docs/orchestrations/draft.md', sha256: 'sha256:' + 'a'.repeat(64), text: '---\nname: draft_procedure\ntools: [file_read]\n---\nAn agent-driven procedure.\n', questions: [{ header: 'Install', question: 'Install the reviewed source?', multi: false, options: [{ label: 'Install', description: 'Grants file_read beyond fixture-bot. Write into the project.' }, { label: "Don’t install", description: 'Leave the draft in artifacts.' }] }] } : { lead: 'Choose the next research step.', questions: [{ header: 'Sources', question: 'Which sources should we read next?', multi: false, options: [{ label: 'Archives', description: 'Explore primary materials.' }] }] } }], children: run.parent ? [] : [{ id: 'fixture-child-1', state: 'finished' }] }; if (questionOverride && run.id === 'fixture-old-root') result.payload.messages[0] = { ...result.payload.messages[0], ...questionOverride, id: questionId }; if (options.stageNavigation && !run.parent) {
            result.payload.todos = [
              { ...result.payload.todos[0], status: 'done' },
              { ...result.payload.todos[0], id: 'fixture-review-todo', stage: 'objective_review', status: 'in_progress', text: 'Review objectives', summary: null },
            ];
            result.payload.messages = [{ ...result.payload.messages[0], createdAt: '2026-10-02T00:01:02Z', text: 'Review marker. Do these objectives match?', structure: { lead: 'Review marker.', questions: [{ header: 'Objectives', question: 'Do these objectives match?', multi: false, options: [{ label: 'Approve', description: 'Proceed with these objectives.' }] }] } }];
          } break;
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
        case 'orchestration.resume': {
          const run = runRows.find(run => run.id === payload.id);
          if (!run || run.state !== 'failed' || run.parent !== null) { result = { code: 'BAD_REQUEST', said: 'Only failed roots can resume' }; break; }
          run.state = 'running'; run.failure = null; run.endedAt = null;
          result.payload = { id: run.id, state: run.state }; push({ kind: 'orchestration.changed', orchestration: run.id, state: run.state }); break;
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
        case 'schedule.files': result.payload = [...scheduleFiles.values()]; break;
        case 'schedule.sync': result.payload = [...scheduleFiles.values()]; break;
        case 'schedule.save': {
          if (refuseTriggerDefinition) { result = {code:'BAD_REQUEST',said:'Fixture bot unavailable'}; break; }
          if (scheduleFiles.has(payload.name) && !payload.overwrite) { result = {code:'CONFLICT',said:'Schedule file already exists'}; break; }
          const d = payload.definition, name = payload.name;
          result.payload = {name,project:payload.project ?? null,source:payload.source,path:`schedules/${name}.json`,internalName:name,definition:d,status:'active',error:null};
          scheduleFiles.set(name,result.payload);
          schedules.set(name,{name,cron:d.cron,zone:d.zone,emits:name,paused:d.paused,nextFireAt:'2026-10-03T09:00:00+10:00',definedBy:'fixture'});
          triggers.set(name,{name,event:name,project:d.target.project ?? payload.project ?? null,conversation:d.target.conversation,agent:d.action.agent,task:d.action.kind === 'agent' ? d.action.input : `/${d.action.kind}:${d.action.name} ${d.action.input}`,maxModelCalls:d.limits.maxModelCalls,maxTurns:d.limits.maxTurns,queueCap:d.limits.queueCap,paused:d.paused,definedBy:'fixture'});
          break;
        }
        case 'schedule.list': result.payload = [...schedules.values()]; break;
        case 'trigger.list': result.payload = [...triggers.values()]; break;
        case 'firing.list': result.payload = []; break;
        case 'schedule.read': result.payload = { cron: '0 0 9 * * *', zone: payload.zone, when: 'Every day at 9am', agent: 'fixture-bot', task: 'Read the original sources', intoConversation: !!payload.conversation, project: payload.project ?? null, conversation: payload.conversation ?? null, nextFires: ['2026-10-03T09:00:00+10:00','2026-10-04T09:00:00+10:00'], names: { schedule: 'fixture-morning', trigger: 'fixture-morning-run', event: 'fixture.morning' } }; break;
        case 'schedule.define': result.payload = { name: payload.schedule, cron: payload.cron, zone: payload.zone, emits: payload.emits, paused: false, nextFireAt: '2026-10-03T09:00:00+10:00', definedBy: 'fixture' }; schedules.set(payload.schedule, result.payload); break;
        case 'trigger.define':
          if (refuseTriggerDefinition) { result = { code: 'BAD_REQUEST', said: 'Fixture bot unavailable' }; break; }
          result.payload = { name: payload.trigger, event: payload.event, project: payload.project ?? null, conversation: payload.conversation ?? null, agent: payload.agent, task: payload.task, maxModelCalls: null, maxTurns: null, queueCap: 3, paused: false, definedBy: 'fixture' }; triggers.set(payload.trigger, result.payload); break;
        case 'schedule.pause': schedules.get(payload.schedule).paused = payload.paused; if (scheduleFiles.has(payload.schedule)) { scheduleFiles.get(payload.schedule).definition.paused = payload.paused; triggers.get(payload.schedule).paused = payload.paused; } result = { code: 'NO_CONTENT', payload: null }; break;
        case 'trigger.pause': triggers.get(payload.trigger).paused = payload.paused; result = { code: 'NO_CONTENT', payload: null }; break;
        case 'schedule.forget': schedules.delete(payload.schedule); if (scheduleFiles.delete(payload.schedule)) triggers.delete(payload.schedule); result = { code: 'NO_CONTENT', payload: null }; break;
        case 'trigger.forget': triggers.delete(payload.trigger); if (scheduleFiles.delete(payload.trigger)) schedules.delete(payload.trigger); result = { code: 'NO_CONTENT', payload: null }; break;
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
    setManualChapters(value) { manualChapters = structuredClone(value); },
    setInformationExtraction(value) { informationExtraction=value; },
    setInformationReadError(value) { informationReadError=value; },
    get eventSession() { return eventSession; },
    get fileClaim() { return fileClaim; },
    get liveFileClaims() { return [...fileSockets.values()].map(row => row.claim); },
    get rotations() { return rotations; },
    addServerProject(row) { projectRows.set(row.name, row); },
    addConversation(row, bot) {
      conversations.push(conversationWire(row.id, row)); entries.set(row.id, []);
      if (bot) botLatest.set(JSON.stringify([row.project, bot]), row.id);
    },
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
    failServiceTokenMetadataOnce() { refuseServiceTokenListingOnce = true; },
    setRefuseFiles(value) { refuseFiles = value; },
    setEmptyFileReady(value) { emptyFileReady = value; },
    loseFiles(project) { (project ? fileSockets.get(project)?.socket : fileSocket)?.close(1012, 'Server restarting'); },
    httpPaths, file: fileRequest, base: `http://127.0.0.1:${server.address().port}`, frames,
    publish: push,
    setUsageCapture: value => {captureEnabled=value;},
    setRefuseUsage: value => {refuseUsage=value;},
    setRefuseSnapshot: value => {refuseSnapshot=value;},
    publishUsage(input='3600') { ++usageRevision; for(const [subscription,row] of usageSubscriptions) if(row.connection.readyState===1) row.connection.send(JSON.stringify({id:null,type:'usage.updated',protocol_version:'plowshare-v1',payload:{subscription,revision:usageRevision,report:usageReport(row.type,row.filter,input)}})); },
    failBoardMember(member) {
      for (const s of [...board.details['demo-board'].value.seats, ...board.swarm.value.seats]) if(s.seat.occupant===member && s.seat.topic==='demo-board') {s.state='failed';s.seat.failedEnding='TURN_CAP';s.job=null;}
    },
    loseRetryAcknowledgment() {loseRetryAck=true;},
    setRefuseBoard: value => { refuseBoard = value; },
    setWorkflowAckMissing(value) { workflowAckMissing=value; },
    workflowRunCount() { return workflowReceipts.size; },
    setRefusePost(value) { refusePost=value; },
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
    setRunCaller(id, callerConversation) { runRows.find(run => run.id === id).callerConversation = callerConversation; },
    setQuestion(value) { questionOverride = value; questionId = value.id; push({ kind: 'orchestration.changed', orchestration: 'fixture-old-root', state: 'asking' }); },
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
    async close() { for (const pending of filePending.values()) clearTimeout(pending.timer); for (const timer of timers) clearTimeout(timer); for (const client of wss.clients) client.terminate(); await new Promise(resolve => wss.close(resolve)); await new Promise(resolve => server.close(resolve)); assert.deepEqual(httpPaths.filter(path => !['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(path) && !(options.union?.http && path.startsWith('/v1/sync/')) && !(options.setup && path === '/v1/auth/setup')), [], 'operational HTTP fallback'); },
  };
}
