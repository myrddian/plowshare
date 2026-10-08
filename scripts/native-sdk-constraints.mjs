/** Portable constraints supplement the generated field graph. Authorization remains server-owned. */
import { SHAPES } from '../sdk/typescript/build/operations/payload-validation.js';
const uuid = '^[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$';
const portName = { pattern: '^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$', maxLength: 160 };
const canonicalUuid = { pattern: '^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$' };
const instant = { timestamp: true, pattern: '^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(?:\\.\\d{1,9})?Z$' };
// Decimal positions are signed-long values; never coerce them through floating point.
const longLimit = '9223372036854775807';
const longBranches = [...longLimit].flatMap((digit,index) => {
  const lower=index===0?1:0, upper=Number(digit)-1;
  if(upper<lower) return [];
  return [longLimit.slice(0,index)+(upper===lower?String(lower):`[${lower}-${upper}]`)+`[0-9]{${18-index}}`];
});
const decimal = { pattern: `^(?:0|[1-9][0-9]{0,17}|${longBranches.join('|')}|${longLimit})$` };
const printable =
  '^[^\\x00-\\x1f\\x7f-\\x9f' + String.fromCharCode(0x2028, 0x2029) + ']+$';
const identity = {
  minLength: 1,
  maxLength: 1024,
  pattern: printable,
  trimmed: true,
};
const integer = (minimum = 0, maximum = 2147483647) => ({
  integer: true,
  minimum,
  maximum,
});
const text = (maxLength, minLength = 0) => ({
  minLength,
  maxLength,
  noNul: true,
});
const g = (path) => ({ get: path });
const eq = (path, value) => ({ eq: [g(path), value] });
const present = (path) => ({ present: path });
const and = (...args) => ({ and: args });
const or = (...args) => ({ or: args });
const not = (value) => ({ not: value });
const implies = (a, b) => or(not(a), b);
const exactlyOne = (...paths) => ({ exactlyOne: paths });
const absent = (...paths) => and(...paths.map((p) => not(present(p))));
function resolve(graph, s) {
  return s.$ref ? resolve(graph, graph.$defs[s.$ref.split('/').at(-1)]) : s;
}
export function constrain(graph) {
  const namedSwarm = { pattern: '^[a-z][a-z0-9]*(?:[-_][a-z0-9]+)*$', minLength: 1, maxLength: 64 };
  const add = (s, c) => Object.assign(s, c);
  const rules = (s, ...values) => (s.rules = [...(s.rules ?? []), ...values]);
  const field = (s, k, c) => {
    if (s.properties?.[k]) add(s.properties[k], c);
  };
  const nested = (s, k) =>
    s.properties?.[k] ? resolve(graph, s.properties[k]) : undefined;
  const stringShape = (s) => {
    const v = resolve(graph, s);
    return (
      v.type === 'string' ||
      typeof v.const === 'string' ||
      v.anyOf?.some(stringShape)
    );
  };
  for (const [op, ref] of Object.entries(graph.inputs)) {
    const s = resolve(graph, ref),
      extended = SHAPES[op];
    if (s.anyOf) continue;
    if (op === 'board.open') field(s, 'swarm', namedSwarm);
    for (const [k, v] of Object.entries(s.properties ?? {})) {
      if (
        (extended || s.required?.includes(k)) &&
        stringShape(v) &&
        !['answer', 'text', 'body', 'task', 'definition'].includes(k)
      )
        add(v, { minLength: 1, nonblank: true, noNul: true });
      if (
        [
          'project',
          'agent',
          'conversation',
          'job',
          'peer',
          'handle',
          'session',
          'root',
          'memory',
          'proposal',
          'id',
        ].includes(k) &&
        stringShape(v)
      )
        add(v, identity);
      if (
        [
          'limit',
          'offset',
          'maxModelCalls',
          'maxTurns',
          'expiresInDays',
          'after',
          'before',
          'turn',
          'pageSize',
          'max',
          'page',
          'queueCap',
          'start',
          'end',
          'waitMs',
        ].includes(k)
      )
        add(
          v,
          integer(['offset', 'after', 'start', 'waitMs'].includes(k) ? 0 : 1),
        );
      if (k === 'requestId') add(v, { pattern: uuid });
    }
    if (op === 'outgoing.report') {
      field(s, 'revision', integer(0, 9007199254740991));
      field(s, 'error', text(2000));
      rules(
        s,
        implies(
          or(
            eq('state', 'WORKING'),
            eq('state', 'INPUT_REQUIRED'),
            eq('state', 'AUTH_REQUIRED'),
          ),
          present('remoteTask'),
        ),
        implies(
          present('result.task'),
          and(
            { eq: [g('result.task.id'), g('remoteTask')] },
            { eq: [g('result.task.contextId'), g('remoteContext')] },
          ),
        ),
      );
    }
    if (op === 'outgoing.claim' || op === 'outgoing.advertise')
      field(s, 'peers', {
        minItems: 1,
        maxItems: 32,
        uniqueItems: true,
        element: identity,
      });
    if (op === 'incoming.receive') {
      field(s, 'body', text(65536));
      field(s, 'command', {
        pattern:
          '^/(skill|orchestration):[a-zA-Z0-9_.-]+(?: --mode=(INHERITED|SUMMARISED|NEW|DIRECT))?$',
      });
    }
    if (op === 'admin.audit') field(s, 'limit', { maximum: 100 });
    if (op.startsWith('admin.service.token.'))
      field(s, 'expiresInDays', { maximum: 365 });
    if (op === 'admin.account.update')
      rules(s, or({ has: 'enabled' }, { has: 'serverAdmin' }));
    if (op === 'project.create')
      rules(s, implies(eq('type', 'DISJOINT'), present('workspace')));
    if (op === 'project.create') {
      const f = s.properties.writePaths;
      if (f) add(f, { element: { safeRelativePath: true, minLength: 1 } });
    }
    if (op === 'application.create' || op === 'application.storage.set') {
      field(s, 'writableAreas', {maxItems:100, uniqueItems:true});
      field(s, 'type', {pattern:'^(MANAGED|DISJOINT)$'});
    }
    if (op === 'agent.run')
      rules(
        s,
        implies(eq('newConversation', true), absent('conversation')),
        implies(present('conversation'), absent('project')),
        implies(
          or(eq('newConversation', true), present('conversation')),
          not(present('images')),
        ),
      );
    rules(
      s,
      implies(eq('noTurnCap', true), absent('maxTurns')),
      implies(eq('noBudget', true), absent('maxModelCalls')),
    );
    if (op === 'document.citations')
      rules(s, { atMostOne: ['document', 'conversation'] });
    if (op === 'conversation.trajectory' || op === 'orchestration.record')
      rules(s, { atMostOne: ['after', 'before', 'tail'] });
    if (op === 'approval.list')
      rules(s, exactlyOne('conversation', 'project', 'mine'));
    if (op === 'approval.answer')
      rules(s, implies(eq('decision', 'project'), present('prefix')));
    if (op === 'orchestration.answer')
      rules(s, or(present('choices'), present('answer')));
    for (const k of ['roots', 'items', 'prefix'])
      if (s.properties[k])
        field(s, k, {
          minItems: 1,
          element: { minLength: 1, nonblank: true, noNul: true },
        });
    if (op === 'schedule.pause' || op === 'trigger.pause')
      s.required = [...new Set([...(s.required ?? []), 'paused'])];
    if (op === 'board.topup')
      s.required = [...new Set([...(s.required ?? []), 'maxModelCalls'])];
    if (op === 'trigger.define')
      rules(
        s,
        implies(
          present('conversation'),
          absent('project', 'maxModelCalls', 'maxTurns'),
        ),
      );
    if (op.startsWith('information.')) {
      field(s, 'waitMs', { maximum: 30000 });
      field(s, 'sources', { minItems: 1, maxItems: 100 });
      for (const k of [
        'tags',
        'objectives',
        'inputs',
        'scopeChanges',
        'evidence',
      ])
        if (s.properties[k])
          field(s, k, {
            maxItems: k === 'tags' ? 32 : 10000,
            element: {
              minLength: 1,
              maxLength: k === 'tags' ? 64 : 32768,
              nonblank: true,
              noNul: true,
            },
          });
      if (['information.status', 'information.retry'].includes(op))
        rules(s, exactlyOne('revision', 'acquisition'));
      if (op === 'information.retry')
        rules(s, implies(present('revision'), present('requestId')));
      if (op === 'information.evidence.record')
        rules(s, { gt: [g('end'), g('start')] });
      if (op === 'information.outline' || op === 'information.symbols') {
        rules(s, eq('corpus', 'code'));
        field(s, 'limit', { maximum: 100 });
        field(s, 'query', text(128, 1));
      }
    }
    if (op === 'schedule.save' || op === 'schedule.sync')
      rules(s, implies(eq('source', 'workspace'), present('project')));
    if (op === 'schedule.save') {
      field(s, 'name', { pattern: '^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$' });
      const d = nested(s, 'definition');
      field(d, 'cron', text(256, 1));
      field(d, 'zone', text(128, 1));
      const a = nested(d, 'action'),
        t = nested(d, 'target'),
        l = nested(d, 'limits');
      field(a, 'agent', { pattern: '^[A-Za-z0-9_.-]{1,128}$' });
      field(a, 'input', text(16000, 1));
      rules(
        a,
        implies(eq('kind', 'agent'), absent('name', 'mode')),
        implies(not(eq('kind', 'agent')), present('name')),
        implies(present('mode'), eq('kind', 'skill')),
      );
      rules(
        t,
        implies(
          eq('kind', 'conversation'),
          and(present('conversation'), absent('project', 'to', 'route')),
        ),
        implies(not(eq('kind', 'conversation')), absent('conversation')),
        implies(eq('kind', 'message'), exactlyOne('to', 'route')),
        implies(not(eq('kind', 'message')), absent('to', 'route')),
        implies(present('route'), absent('project')),
      );
      field(l, 'queueCap', integer(1, 100));
      for (const k of ['maxModelCalls', 'maxTurns'])
        field(l, k, integer(1, 100000));
      rules(
        d,
        implies(
          eq('target.kind', 'conversation'),
          absent('limits.maxModelCalls', 'limits.maxTurns'),
        ),
      );
    }
  }
  for (const op of ['relay.publish','relay.consume','relay.ack']) {
    const request=resolve(graph,graph.inputs[op]), reply=resolve(graph,graph.results[op]);
    for(const shape of [request,reply]) {
      field(shape,'project',{...identity,maxLength:256});
      for(const k of ['topic','parentTopic']) field(shape,k,portName);
      field(shape,'group',{...portName,maxLength:140});
      for(const k of ['consumerId','requestId','batchId']) field(shape,k,canonicalUuid);
      for(const k of ['position','through','expiredThrough','fence']) field(shape,k,decimal);
      field(shape,'position',{disallow:['0']}); field(shape,'fence',{disallow:['0']});
      for(const k of ['occurredAt','publishedAt','expiresAt']) field(shape,k,instant);
    }
    if(op==='relay.publish') {
      field(request,'text',{...text(65536,1),nonblank:true});
      for(const k of ['parentEventId','correlationId']) field(request,k,{...identity,maxLength:256});
      rules(request,{eq:[present('parentTopic'),present('parentEventId')]});
    }
    if(op==='relay.consume') {
      field(request,'limit',integer(1,100)); field(request,'waitMs',integer(0,30000));
      field(reply,'events',{maxItems:100});
      const leased=or(eq('status','DATA'),eq('status','GAP'));
      for(const k of ['batchId','fence','expiresAt']) rules(reply,{eq:[leased,present(k)]});
      rules(reply,{eq:[eq('status','DATA'),present('events')]},{eq:[eq('status','GAP'),present('expiredThrough')]});
    }
  }
  const claim = resolve(graph, graph.results['outgoing.claim']);
  rules(
    claim,
    { eq: [{ present: 'work' }, { present: 'action' }] },
    implies(
      eq('action', 'send'),
      and(eq('work.state', 'DISPATCHED'), absent('work.remoteTask')),
    ),
    implies(
      and(present('action'), not(eq('action', 'send'))),
      present('work.remoteTask'),
    ),
  );
  // Constraints owned by shared domain shapes apply to both requests and responses.
  for (const s of Object.values(graph.$defs)) {
    if (!s.properties) continue;
    const p = s.properties,
      title = s.title ?? '';
    if (title === 'BoardOpened') rules(s, not(eq('topic.swarm', null)));
    if (title === 'BoardMessages') rules(s, {eq:[g('topic.swarm'),g('root.swarm')]});
    if (title === 'SwarmSelection') {
      field(s, 'name', namedSwarm);
      field(s, 'revision', { pattern: '^[a-f0-9]{64}$' });
      field(s, 'description', text(4096));
      s.properties.members = { type:'array', minItems: 1, maxItems: 64, uniqueItems: true, items:{type:'string', minLength:1, nonblank:true, maxLength:128, noNul:true} };
      field(s, 'budget', integer(2));
    }
    if (title === 'SwarmType') {
      field(s, 'name', namedSwarm); field(s, 'members', {maxItems:64,uniqueItems:true}); field(s, 'budget', integer(2));
      rules(s, implies(not(eq('selection', null)), and({eq:[g('selection.name'),g('name')]},{eq:[g('selection.budget'),g('budget')]},{eq:[g('selection.members'),g('members')]})), implies(eq('selection',null),eq('members',[])));
    }
    if (title === 'SwarmTypes') field(s, 'types', {maxItems:64});
    if (title === 'FileStoreReference') {
      field(s, 'store', {pattern:'^[a-z][a-z0-9_-]{0,63}$',maxLength:64});
      field(s, 'path', {maxLength:2048,canonicalRelativePath:true});
    }
    if (title === 'ProjectView') field(s, 'writableAreas', { maxItems: 100 });
    if (title === 'SourceView') {
      field(s, 'kind', { pattern: '^(unknown|person|message|relay|board|event|approval|orchestration)$' });
      field(s, 'reference', { minLength: 1, nonblank: true });
      rules(s, { eq: [eq('kind', 'unknown'), eq('reference', null)] });
    }
    if (title === 'EntryView') field(s, 'job', { minLength: 1, nonblank: true });
    if (title === 'MessageDelivery') {
      for (const k of ['conversation', 'sourceConversation']) field(s, k, { minLength: 1, nonblank: true });
    }
    if (title === 'RelayCausation') {
      for (const k of ['rootId', 'parentId']) field(s, k, { ...identity, maxLength: 256 });
      field(s, 'depth', integer(-1, 32));
      rules(s, { eq: [{ gt: [g('depth'), 0] }, present('parentId')] });
    }
    if (title === 'ExternalMessage') {
      field(s, 'parts', { minItems: 1, maxItems: 256 });
      for (const k of ['messageId', 'contextId', 'taskId'])
        field(s, k, identity);
      field(s, 'extensions', {
        maxItems: 32,
        uniqueItems: true,
        element: { ...identity, pattern: '^[a-zA-Z][a-zA-Z0-9+.-]*:[^\\s]+$' },
      });
    }
    if (p.text && (p.url || p.raw || p.data || p.kind))
      field(s, 'text', text(262144));
    if (p.raw)
      field(s, 'raw', {
        maxLength: 262144,
        pattern:
          '^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/][AQgw]==|[A-Za-z0-9+/]{2}[AEIMQUYcgkosw048]=)?$',
      });
    if (p.url)
      field(s, 'url', {
        web: true,
        maxLength: 8192,
        pattern:
          '^https?://(?:\\[[a-fA-F0-9:.]+\\]|[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?)(?::[0-9]{1,5})?(?:[/?][^\\s#]*)?$',
      });
    if (p.filename)
      field(s, 'filename', {
        ...identity,
        pattern: '^[^/\\\\]+$',
        disallow: ['.', '..'],
      });
    if (p.mediaType)
      field(s, 'mediaType', {
        maxLength: 256,
        pattern: '^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$',
      });
    if (title === 'IntegrationRequest')
      field(s, 'binding', { ...identity, maxLength: 256 });
    if (p.entities)
      field(s, 'entities', {
        minItems: 1,
        maxItems: 256,
        uniqueItems: true,
        element: identity,
      });
    if (p.parameters)
      field(s, 'parameters', {
        maxProperties: 256,
        keyPattern: '^[A-Za-z_][A-Za-z0-9_]{0,255}$',
        values: { maxLength: 4096, noNul: true, safePrecision: true },
      });
    if (title === 'ExternalMetadata') {
      field(s, 'plowshareCommand', {
        pattern:
          '^/(skill|orchestration):[a-zA-Z0-9_.-]+(?: --mode=(INHERITED|SUMMARISED|NEW|DIRECT))?$',
      });
      field(s, 'plowshareEnding', {
        pattern:
          '^(?:ANSWERED|TURN_CAP|CALL_BUDGET|CANCELLED|STUCK|UNAVAILABLE|SUB_AGENT_FAILED|SESSION_GONE|AWAITING)?$',
      });
    }
    if (title === 'IncomingSource') {
      const parts = resolve(graph, s.properties.parts);
      const part = resolve(graph, parts.items);
      field(part, 'text', text(65536));
      field(s, 'parts', { minItems: 1, maxItems: 256 });
      field(s, 'messageId', { ...identity, maxLength: 256 });
      field(s, 'referenceTaskIds', {
        maxItems: 256,
        uniqueItems: true,
        element: identity,
      });
    }
    if (title === 'AgentCard') {
      for (const k of ['name', 'version']) field(s, k, identity);
      field(s, 'description', text(32768));
      field(s, 'skills', { maxItems: 256 });
    }
    if (title === 'ExternalTask') {
      for (const k of ['id', 'contextId']) field(s, k, identity);
      field(s, 'history', { maxItems: 256 });
      field(s, 'artifacts', { maxItems: 256 });
    }
    if (p.timestamp) field(s, 'timestamp', { timestamp: true });
    if (p.claim && p.objective && p.rationale) {
      for (const k of ['id', 'claim', 'objective', 'rationale'])
        field(s, k, { ...text(32768, 1), nonblank: true });
      for (const k of ['support', 'counterEvidence'])
        field(s, k, { element: { pattern: uuid } });
    }
    if (p.stage && p.outcome && p.text)
      for (const k of ['stage', 'outcome', 'text'])
        field(s, k, { ...text(32768, 1), nonblank: true });
    if (title === 'OutgoingWork')
      field(s, 'revision', integer(0, 9007199254740991));
    if (title === 'JobEvent') {
      s.required = [
        ...new Set([...(s.required ?? []), 'agent', 'steps', 'modelCalls']),
      ];
      field(s, 'job', identity);
      field(s, 'agent', { ...identity, maxLength: 256 });
      field(s, 'kind', { pattern: '^[a-z][a-z0-9_]{0,63}$' });
      for (const k of ['steps', 'modelCalls'])
        field(s, k, integer(0, 9007199254740991));
      rules(
        s,
        implies(eq('kind', 'tool_called'), present('tool')),
        implies(eq('kind', 'ended'), present('ending')),
      );
    }
    if (title === 'JobDelta') {
      field(s, 'job', identity);
      field(s, 'text', text(1048576));
    }
  }
  const push = resolve(graph, graph.pushes);
  const annotatePush = (s) => {
    s = resolve(graph, s);
    if (s.anyOf) {
      s.anyOf.forEach(annotatePush);
      return;
    }
    for (const k of ['unread', 'through', 'settled', 'steps', 'modelCalls'])
      field(s, k, integer(0, 9007199254740991));
    for (const k of ['generation', 'sequence'])
      field(s, k, integer(1, 9007199254740991));
    for (const k of ['orchestration', 'root', 'conversation'])
      field(s, k, identity);
    if (s.properties?.subscription) {
      field(s, 'subscription', { pattern: uuid });
      field(s, 'revision', integer(1, 9007199254740991));
    }
    if (
      s.properties?.kind &&
      resolve(graph, s.properties.kind).const === 'information.changed'
    )
      field(s, 'revision', { pattern: uuid });
    if (s.properties?.requestId) field(s, 'requestId', { pattern: uuid });
  };
  annotatePush(push);
  return graph;
}
