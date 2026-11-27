/** Portable constraints supplement the generated field graph. Authorization remains server-owned. */
import { GOAL_ANSWER_NONBLANK, SHAPES } from '../sdk/typescript/build/operations/payload-validation.js';
const uuid = '^[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$';
const portName = { pattern: '^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$', maxLength: 160 };
const canonicalUuid = { pattern: '^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$' };
const zeroUuid = '00000000-0000-0000-0000-000000000000';
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
// Used only by Ex-specific properties: other SDK policies keep their existing validators.
const ecmaWhitespace = '\\x09-\\x0d\\x20' + '\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff';
const ecmaNonblank = `^[${ecmaWhitespace}]*[^${ecmaWhitespace}]`;
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
  const providerProject = { maxLength:256, trimmed:true, pattern:'^[^\\x00-\\x1f\\x7f-\\x9f]+$', disallowPrefixes:['personal:','Personal:','client:'] };
  const providerUrl = { web:true, minLength:1, maxLength:2048, pattern:"^https://[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?(?::[1-9][0-9]{0,4})?(?:/[A-Za-z0-9._~!$&'()*+,;=:@/-]*)?$" };
  const providerService = { pattern:'^@service/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$', maxLength:45 };
  const providerText = (maxLength) => ({minLength:1,maxLength,trimmed:true,pattern:'^[^\\x00-\\x1f\\x7f-\\x9f]+$'});
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
    // Inspect alone requires a selector. Its TypeScript field shape is shared with the list
    // reads, so keep this operation's rules on a private copy of the root schema.
    if (op === 'proc.inspect') graph.inputs[op] = structuredClone(resolve(graph, ref));
    const s = resolve(graph, graph.inputs[op]),
      extended = SHAPES[op];
    // Project/system Relay requests are unions; constrain each concrete topic field.
    if (op.startsWith('relay.'))
      for (const variant of s.anyOf ?? [ref])
        field(resolve(graph, variant), 'topic', { ...portName, maxLength: 1024 });
    if (s.anyOf) continue;
    if (op==='information.tags.replace') field(s,'expectedTagsVersion',integer(0,9007199254740991));
    if (op.startsWith('proc.')) {
      for (const key of ['project','conversation','root','causation','after'])
        field(s,key,{...identity,maxLength:256});
      field(s,'limit',integer(1,100));
      if(op==='proc.inspect')rules(s,or(present('conversation'),present('root'),present('causation')));
      // PROC uses opaque string cursors and stricter selector bounds; generic numeric-after
      // and identifier defaults below belong to other operations.
      continue;
    }
    if (op==='acting.context.open') {
      field(s,'application',providerProject);
      field(s,'grant',{pattern:'^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'});
      const proof=nested(s,'proof');
      if (proof) field(proof,'credential',{pattern:'^psa_[0-9a-f]{48}$',maxLength:52});
    }
    if (op==='acting.context.close') field(s,'context',canonicalUuid);
    if (op.startsWith('provider.connection.') || op.startsWith('provider.credential.')) {
      field(s,'project',providerProject);
      for(const key of ['connection','requestId','expectedRevision','expectedGeneration']) field(s,key,canonicalUuid);
      field(s,'subject',providerService);
      // RE2 bounds counted repetitions at 1000; portable length limits remain separate.
      field(s,'secret',{pattern:'^[\\x20-\\x7e]+$',minLength:1,maxLength:8192,disallowPrefixes:['pss_','pst_']});
    }
    if(op.startsWith('tool.scope.host.')||op.startsWith('tool.scope.worker.')) {
      field(s,'project',{...identity,maxLength:256,disallowPrefixes:['client:']});
      for(const k of ['binding','registration','requestId','invocation']) field(s,k,canonicalUuid);
      field(s,'expectedGeneration',integer(1,9007199254740991));
      if(op==='tool.scope.worker.credential')rules(s,{eq:[eq('purpose','EXECUTION'),present('invocation')]});
    }
    if (op.startsWith('tool.scope.')) {
      field(s, 'scope', { ...namedSwarm, trimmed: true });
      field(s, 'provider', { pattern: '^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$', maxLength: 48, trimmed: true });
      field(s, 'prefix', { pattern: '^[a-z][a-z0-9_]*_$', maxLength: 48, trimmed: true });
      field(s, 'leaseSeconds', integer(1, 300));
      if (s.properties?.grants) {
        s.properties.grants = { type: 'array', minItems: 1, maxItems: 128, uniqueItems: true,
          items: { type: 'string', pattern: '^(?:\\*|[a-z][a-z0-9]*(?:_[a-z0-9]+)*)$', maxLength: 256, trimmed: true } };
        rules(s, implies({ contains: [g('grants'), '*'] }, eq('grants', ['*'])));
      }
      if (s.properties?.agents) s.properties.agents = { type: 'array', minItems: 1, maxItems: 128,
        uniqueItems: true, items: { type: 'string', ...namedSwarm, trimmed: true } };

    }
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
        ].includes(k) &&
        !(k === 'after' && (op === 'schedule.list' || op === 'schedule.files' || op === 'goal.sources' || op === 'goal.list'))
      )
        add(
          v,
          integer(['offset', 'after', 'start', 'waitMs'].includes(k) ? 0 : 1),
        );
      if (k === 'requestId') add(v, { pattern: uuid });
    }
    if (op.startsWith('conversation.goal.')) {
      for (const key of ['goal', 'requestId'])
        field(s, key, { pattern: uuid, disallowPrefixes: ['00000000-0000-0000-0000-000000000000'] });
      field(s, 'version', integer(1));
      field(s, 'maxActivations', integer(1, 1000));
      field(s, 'objective', { ...text(16000, 1), nonblank: true });
      field(s, 'criteria', { ...text(8000, 1), nonblank: true });
    }
    if (op === 'conversation.goal.resume')
      field(s, 'maxModelCalls', integer(1, 1000000));
    if (op === 'message.listen') {
      field(s, 'batchCount', integer(2, 16));
      field(s, 'maxDelaySeconds', integer(1, 86400));
      rules(s, { eq: [present('batchCount'), present('maxDelaySeconds')] });
    }
    if (op === 'message.unlisten') field(s, 'version', integer(1));
    if (['goal.list', 'goal.status', 'goal.receipt'].includes(op)) {
      for (const key of ['after', 'goal', 'requestId'])
        field(s, key, { pattern: uuid, maxLength: 36, disallowPrefixes: [zeroUuid] });
      field(s, 'limit', integer(1, 50));
    }
    if (op === 'goal.answer') {
      for (const key of ['goal', 'question', 'requestId'])
        field(s, key, {pattern: uuid, maxLength: 36, disallowPrefixes: [zeroUuid]});
      field(s, 'conversation', {pattern: '^cnv_[A-Za-z0-9]+$', maxLength: 128, trimmed: true});
      field(s, 'answer', {...text(16000, 1), maxUtf8Bytes: 48000, pattern: GOAL_ANSWER_NONBLANK});
    }
    if (['goal.start', 'goal.sources', 'message.listen', 'message.unlisten'].includes(op)) {
      for (const key of ['goal', 'requestId', 'generation', 'binding'])
        field(s, key, { pattern: uuid, disallowPrefixes: [zeroUuid] });
      field(s, 'conversation', {pattern:'^cnv_[A-Za-z0-9]+$', maxLength:128});
      field(s, 'objective', {...text(16000,1), nonblank:true});
      field(s, 'criteria', {...text(8000,1), nonblank:true});
      field(s, 'topic', {pattern:'^[a-z][a-z0-9]*([._-][a-z0-9]+)*$', maxLength:1024});
      field(s, 'after', {pattern:'^[a-z][a-z0-9]*([._-][a-z0-9]+)*$', maxLength:1024});
      field(s, 'prefix', {pattern:'^[a-z][a-z0-9]*([._-][a-z0-9]+)*[._-]?$', maxLength:1024});
      field(s, 'limit', integer(1,100));
    }
    // Operation-specific bounds follow generic defaults so narrower identities stay narrow.
    if (op === 'inbox.publish') {
      field(s, 'text', {...text(131072, 1), nonblank:true});
    }
    if (op === 'board.close') {
      field(s, 'topic', {pattern:'^bdt_[A-Za-z0-9]+$', minLength:5, maxLength:256});
      field(s, 'body', {...text(16000,1),maxUtf8Bytes:64000,nonblank:true});
      field(s, 'requestId', {pattern:uuid});
      field(s, 'project', {...identity,maxLength:256});
      if(s.properties?.cites)s.properties.cites={type:'array',maxItems:200,items:{type:'string',pattern:'^bdm_[A-Za-z0-9]+$',minLength:5,maxLength:256}};
    }
    if (op === 'orchestration.repair') {
      const checkpoint = nested(s, 'checkpoint');
      const digest = { pattern: '^sha256:[a-f0-9]{64}$' };
      field(checkpoint, 'sequence', integer(0, 2147483646));
      field(checkpoint, 'sourceHash', digest);
      field(checkpoint, 'replacementHash', digest);
      rules(s, not({ eq: [g('checkpoint.sourceHash'), g('checkpoint.replacementHash')] }));
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
    if (['application.identity.status','application.token.issue','application.token.receipt'].includes(op)) {
      field(s, 'project', {maxLength:256,trimmed:true,pattern:'^[^\\x00-\\x1f\\x7f-\\x9f]+$',disallowPrefixes:['personal:','Personal:','client:']});
      field(s, 'requestId', {pattern:uuid,minLength:36,maxLength:36});
      field(s, 'expiresInDays', integer(1,365));
    }
    if (op === 'schedule.list' || op === 'schedule.files' || op === 'goal.sources') {
      field(s, 'project', {maxLength:256,trimmed:true,pattern:'^[^\\x00-\\x1f\\x7f-\\x9f]+$',disallowPrefixes:['personal:','Personal:','client:']});
      field(s, 'after', {minLength:1,maxLength:512,trimmed:true,pattern:'^[^\\x00-\\x1f\\x7f-\\x9f]+$'});
      field(s, 'limit', integer(1,100));
    }
    if (op === 'application.deploy') {
      rules(s, present('destination.path'));
      field(s, 'writableAreas', {maxItems:100, uniqueItems:true});
      field(s, 'files', {minItems:1, maxItems:128, applicationFiles:true});
      const file = resolve(graph, resolve(graph, s.properties.files).items);
      field(file, 'path', {maxLength:512});
      field(file, 'text', {maxLength:65536, noNul:true});
    }
    if (['application.deploy','application.activate','application.deployment.receipt'].includes(op)) field(s, 'requestId', {minLength:36,maxLength:36});
    if (op === 'application.deploy' || op === 'application.activate') {
      field(s, 'expectedRevision', {pattern:uuid,minLength:36,maxLength:36});
      if (op === 'application.activate') field(s, 'revision', {pattern:uuid,minLength:36,maxLength:36});
    }
    if (op === 'personal.subscription.activate') {
      field(s, 'revision', {pattern:'^[0-9a-f]{64}$',minLength:64,maxLength:64});
      field(s, 'expectedActiveRevision', {pattern:'^(?:[0-9a-f]{64})?$',minLength:0,maxLength:64,nonblank:false,trimmed:true});
    }
    if (op === 'application.create' || op === 'application.storage.set') {
      field(s, 'writableAreas', {maxItems:100, uniqueItems:true});
      field(s, 'type', {pattern:'^(MANAGED|DISJOINT)$'});
    }
    // SDK admission requires an explicit runtime destination; wire defaults remain server-owned.
    if (['conversation.open', 'orchestration.start'].includes(op))
      rules(s, present('project'));
    if (['conversation.list', 'conversation.latest'].includes(op)) rules(s, {has:'project'});
    if (op === 'agent.run')
      rules(
        s,
        or(present('conversation'), present('project')),
        implies(eq('newConversation', true), absent('conversation')),
        implies(present('conversation'), absent('project')),
        implies(
          or(eq('newConversation', true), present('conversation')),
          not(present('images')),
        ),
        // ADR 0069: a raise names an existing conversation's new total and nothing else.
        implies(
          present('maxModelCalls'),
          and(present('conversation'), not(eq('newConversation', true))),
        ),
      );
    rules(
      s,
      implies(eq('noTurnCap', true), absent('maxTurns')),
      implies(eq('noBudget', true), absent('maxModelCalls')),
    );
    if (op === 'conversation.open.ex' || op === 'agent.run.ex') {
      // Ex follows canonical ECMAScript trim semantics without applying a language's strip/Trim
      // to the value. In particular FEFF is whitespace, while NEL and U+001C are not.
      const edge = ecmaWhitespace + '\\x00-\\x1f\\x7f-\\x9f';
      // Equality with native trim also rejects regex '$' matching before a final LF. Native-only
      // trim characters are already forbidden by the locator's control/ASCII sets; no value is
      // rewritten. Do not transfer this locator-only check to task/session/agent or reply names.
      field(s, 'project', {trimmed:true,pattern:`^[^${edge}](?:[^\\x00-\\x1f\\x7f-\\x9f\u2028\u2029]*[^${edge}])?$`});
      field(s, 'source', {minLength:1,maxLength:128,trimmed:true,pattern:'^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$'});
      field(s, 'externalConversation', {minLength:1,maxLength:256,trimmed:true,pattern:'^[\\x21-\\x7e]+$'});
      if (op === 'agent.run.ex') {
        field(s,'images',{maxItems:0});
        for (const key of ['agent','task','session'])
          field(s,key,{minLength:1,maxLength:50*1024*1024,pattern:ecmaNonblank,nonblank:false,trimmed:false,noNul:false});
      }
    }
    if (op === 'document.citations')
      rules(s, { atMostOne: ['document', 'conversation'] });
    if (op === 'conversation.trajectory' || op === 'orchestration.record')
      rules(s, { atMostOne: ['after', 'before', 'tail'] });
    if (op === 'approval.list')
      rules(s, exactlyOne('conversation', 'project', 'mine'));
    if (op === 'approval.answer') {
      rules(s, implies(eq('decision', 'project'), present('prefix')));
      rules(s, implies(present('reason'), eq('decision', 'deny')));
      field(s, 'reason', { ...text(4000, 1), nonblank: true });
    }
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
    if (['information.navigation.capabilities','information.documents.list','information.documents.facets','information.documents.get','information.relationships.list','information.relationships.create','information.relationships.remove','information.policy.get','information.policy.set','information.views.list','information.views.get','information.views.save','information.views.delete'].includes(op)) {
      if(s.properties?.target) {
        const target=resolve(graph,s.properties.target);
        for(const variant of target.anyOf??[s.properties.target]) field(resolve(graph,variant),'resource',{pattern:uuid});
      }
      field(s,'expectedVersion',integer(['information.relationships.remove','information.views.delete'].includes(op)?1:0,9007199254740990));
      field(s,'limit',integer(1,100));
      for(const key of ['resource','source','id','relationship','relatedTo','otherResource','expectedSourceRevision']) field(s,key,{pattern:uuid});
      field(s,'relation',{pattern:'^[a-z][a-z0-9_-]{0,63}$',maxLength:64});
      field(s,'reason',{...text(4096),nonblank:true});
      if(['information.documents.list','information.documents.facets'].includes(op)) rules(s,implies(or(present('relation'),present('direction')),present('relatedTo')));
      if(op==='information.documents.list') rules(s,implies(present('projection'),eq('view','content-types-v1')));
    }
    if (op.startsWith('information.interchange.')) {
      field(s,'revisions',{minItems:1,maxItems:16,uniqueItems:true,element:canonicalUuid});
      field(s,'revision',canonicalUuid); field(s,'requestId',canonicalUuid);
      add(s,{maxJsonBytes:8388608});
      if(op==='information.interchange.import') rules(s,not(eq('scope.kind','global_read')));
    }
    if (op.startsWith('information.')) {
      if (op.startsWith('information.discovery.')) {
        field(s,'scopes',{minItems:1,maxItems:32,uniqueItems:true,element:{pattern:'^(global_read|[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})$'}});
        field(s,'limit',integer(1,op.endsWith('.read')?32768:50));
        field(s,'offset',integer(0,2147483647));
        field(s,'cursor',{pattern:'^[A-Za-z0-9_-]{1,2048}\\.[A-Za-z0-9_-]{43}$',maxLength:2092});
        field(s,'query',{...text(512,1),nonblank:true});
        field(s,'revision',canonicalUuid);
      }
      const accountOperations = ['upload','list','status','await','read','tags','tags.groups','exclude','unexclude','withdraw','restore','delete','retry','rebuild','allowance','share','unshare'];
      if (!accountOperations.includes(op.slice('information.'.length))) rules(s,not(eq('scope.kind','account')));
      else rules(s,implies(eq('scope.kind','account'),and(absent('typing','expectedRevision'),or(absent('corpus'),eq('corpus','documents')))));
      field(s, 'waitMs', { maximum: 30000 });
      for (const key of ['expectedRevision', 'version', 'type', 'attachedRevision']) field(s, key, canonicalUuid);
      field(s, 'expectedControlRevision', integer(1,9007199254740991));
      field(s, 'stages', {minItems:1,maxItems:7,uniqueItems:true});
      field(s, 'metadata', {schemaInstance:true, maxJsonBytes:16384});
      field(s, 'presentation', {schemaInstance:true, maxJsonBytes:8192});
      field(s, 'schema', {documentSchema:true, maxJsonBytes:65536});
      field(s, 'slug', {pattern:'^[a-z](?:[a-z0-9-]{0,62}[a-z0-9])?$', minLength:1,maxLength:64});
      field(s, 'label', {minLength:1,maxLength:120,nonblank:true});
      // Empty descriptions are valid; override the generic nonblank text constraint.
      field(s, 'description', {...text(4096), nonblank:false});
      if (s.properties.filter) {
        if (!op.startsWith('information.discovery.')) rules(s, implies(or(present('filter.contentType'),present('filter.contentTypeVersion'),present('filter.untyped')),eq('view','content-types-v1')));
        rules(s,implies(eq('filter.untyped',true),absent('filter.contentType','filter.contentTypeVersion')));
      }
      if (s.properties.typing) rules(s, implies(present('typing'),eq('view','content-types-v1')),implies(present('expectedRevision'),present('typing')));
      if (op==='information.types.version.create') rules(s,
        {eq:[present('type'),present('expectedControlRevision')]},
        implies(present('type'),absent('slug','label','description')),
        implies(not(present('type')),and(present('slug'),present('label'))));
      if (op==='information.acquire') rules(s, absent('typing','expectedRevision'));
      if (op==='information.refresh') rules(s, implies(present('typing'), {eq:[g('revision'),g('expectedRevision')]}));
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
  // Ex must carry its resolved internal conversation. Clone the root so strengthening this
  // result cannot mutate the ordinary started-job contracts shared by agent.run/resume.
  for (const key of ['nativeAgentRunExResult','nativeConversationOpenExResult'])
    if (Object.hasOwn(graph.$defs,key)) throw new Error('Private Ex result definition already exists');
  const externalStarted = structuredClone(resolve(graph, graph.results['agent.run.ex']));
  externalStarted.required = [...new Set([...(externalStarted.required ?? []), 'conversation'])];
  field(externalStarted,'id',{pattern:ecmaNonblank,nonblank:false});
  externalStarted.properties.conversation = {type:'string',pattern:'^cnv_'};
  graph.$defs.nativeAgentRunExResult = externalStarted;
  graph.results['agent.run.ex'] = {$ref:'#/$defs/nativeAgentRunExResult'};
  // Reply names preserve their text; they are not request selectors. Clone before supplementing
  // the shared ordinary view so a new Ex check never changes old operation policy.
  const externalView = structuredClone(resolve(graph, graph.results['conversation.open.ex']));
  for (const key of ['id','project']) field(externalView,key,{pattern:ecmaNonblank,nonblank:false});
  for (const key of ['maxModelCalls','modelCallsSpent','maxTurns'])
    field(externalView,key,integer(0,9007199254740991));
  graph.$defs.nativeConversationOpenExResult = externalView;
  graph.results['conversation.open.ex'] = {$ref:'#/$defs/nativeConversationOpenExResult'};
  for (const operation of ['goal.start', 'goal.receipt', 'goal.sources', 'message.listen']) {
    const reply = resolve(graph, graph.results[operation]);
    for (const key of ['goal', 'requestId', 'binding', 'generation']) field(reply, key, canonicalUuid);
    field(reply,'conversation',{pattern:'^cnv_[A-Za-z0-9]+$', maxLength:128});
    field(reply,'instance',{pattern:'^ins_[A-Za-z0-9]+$', maxLength:128});
    field(reply,'version',integer(1));
    field(reply,'topic',{pattern:'^[a-z][a-z0-9]*([._-][a-z0-9]+)*$', maxLength:1024});
    field(reply,'sources',{maxItems:100});
    if (operation === 'goal.sources') {
      const source = resolve(graph, resolve(graph, reply.properties.sources).items);
      field(source,'generation',canonicalUuid);
      field(source,'topic',{pattern:'^[a-z][a-z0-9]*([._-][a-z0-9]+)*$', maxLength:1024});
    }
  }
  const goalPage = resolve(graph, graph.results['goal.list']);
  const discoveryStatus = resolve(graph, graph.results['goal.status']);
  const discoveryUuid = { ...canonicalUuid, maxLength: 36, disallowPrefixes: [zeroUuid] };
  field(goalPage, 'goals', { maxItems: 50 });
  field(goalPage, 'next', discoveryUuid);
  field(discoveryStatus, 'goal', discoveryUuid);
  field(discoveryStatus, 'project', text(1024, 1));
  field(discoveryStatus, 'conversation', { pattern: '^cnv_[A-Za-z0-9]+$', maxLength: 128 });
  field(discoveryStatus, 'instance', { pattern: '^ins_[A-Za-z0-9]+$', maxLength: 128 });
  field(discoveryStatus, 'version', integer(1));
  field(discoveryStatus, 'agent', text(1024, 1));
  field(discoveryStatus, 'objective', text(16000, 1));
  field(discoveryStatus, 'nextScheduleAt', instant);
  field(discoveryStatus, 'schedules', { maxItems: 16 });
  field(discoveryStatus, 'relayListeners', { maxItems: 16 });
  rules(discoveryStatus, implies(eq('readable', false), and(
    ...['agent', 'objective', 'detail', 'latestResult', 'nextScheduleAt'].map(key => eq(key, null)),
    eq('schedules', []), eq('relayListeners', []),
    eq('moreSchedules', false), eq('moreRelayListeners', false),
  )));
  const goalShape = title => Object.values(graph.$defs).find(shape => shape.title === title);
  const result = goalShape('GoalResult');
  field(result, 'job', { pattern: '^job_[A-Za-z0-9]+$', maxLength: 128 });
  field(result, 'turn', integer(1));
  field(result, 'summary', text(16000, 1));
  field(result, 'evidence', { minItems: 1, maxItems: 12, element: text(500, 1) });
  const wake = goalShape('GoalScheduleWake');
  field(wake, 'schedule', text(512, 1));
  field(wake, 'generation', discoveryUuid);
  field(wake, 'nextFireAt', instant);
  const listener = goalShape('GoalRelayWake');
  field(listener, 'binding', discoveryUuid);
  field(listener, 'generation', discoveryUuid);
  field(listener, 'topic', { ...portName, maxLength: 1024 });
  const batch = goalShape('GoalPendingBatch');
  field(batch, 'id', discoveryUuid);
  field(batch, 'memberCount', integer(1, 16));
  field(batch, 'deadline', instant);
  const goalAnswer = resolve(graph, graph.results['goal.answer']);
  for (const key of ['goal', 'question', 'requestId'])
    field(goalAnswer, key, {...canonicalUuid, maxLength: 36, disallowPrefixes: [zeroUuid]});
  field(goalAnswer, 'conversation', {pattern: '^cnv_[A-Za-z0-9]+$', maxLength: 128, trimmed: true});
  const unlisten = resolve(graph, graph.results['message.unlisten']);
  for (const key of ['goal', 'binding', 'requestId'])
    field(unlisten, key, { ...canonicalUuid, disallowPrefixes: [zeroUuid] });
  field(unlisten, 'version', integer(1));
  const goalStatus = resolve(graph, graph.results['conversation.goal.get']);
  field(goalStatus, 'goal', canonicalUuid);
  field(goalStatus, 'version', integer(1));
  field(goalStatus, 'maxActivations', integer(1, 1000));
  field(goalStatus, 'activations', integer(0, 1000));
  field(goalStatus, 'lastTurn', integer(1));
  field(goalStatus, 'modelCallsSpent', integer(0));
  field(goalStatus, 'modelCallLimit', integer(0));
  for(const operation of ['acting.turn.start','acting.turn.receipt']) {
    const turnReceipt=resolve(graph,graph.results[operation]);
    field(turnReceipt,'requestId',canonicalUuid);
    field(turnReceipt,'conversation',{pattern:'^cnv_[A-Za-z0-9]+$',maxLength:128});
    field(turnReceipt,'agent',{pattern:'^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$',maxLength:64});
    field(turnReceipt,'job',{pattern:'^job_[A-Za-z0-9]+$',maxLength:128});
    rules(turnReceipt,implies(eq('state','STARTED'),present('job')),implies(eq('state','FINISHED'),present('job')));
  }
  const actingOpened=resolve(graph,graph.results['acting.context.open']);
  field(actingOpened,'context',canonicalUuid);
  field(actingOpened,'subject',{pattern:'^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'});
  field(actingOpened,'expiresAt',instant);
  for (const shape of Object.values(graph.$defs)) {
    if (shape.title === 'InputProvenance') {
      field(shape,'start',integer());field(shape,'end',integer(1));
      field(shape,'sources',{maxItems:1});field(shape,'sourceCount',integer(0,1024));
      rules(shape,{gt:[g('end'),g('start')]},implies(present('sources'),{gt:[g('sourceCount'),0]}));
    }
    if (shape.title === 'InputReference') {
      for (const key of ['id','revision']) field(shape,key,{...identity,maxLength:256,pattern:'^[^\\s\\x00-\\x1f\\x7f-\\x9f]+$'});
    }
    if (shape.title === 'SearchSource') {
      field(shape,'conversationId',{...identity,maxLength:256});
      field(shape,'ordinal',integer(1));field(shape,'turnOrdinal',integer(1));
      field(shape,'length',integer());field(shape,'supersededBy',integer(1));
      rules(shape,implies(present('inputProvenance'),not({gt:[g('inputProvenance.end'),g('length')]})));
    }
    if (shape.title === 'LogSearchHit') {
      field(shape,'sources',{minItems:1,maxItems:20});field(shape,'sourceCount',integer(1));
      rules(shape,implies(present('sources'),present('sourceCount')),
          implies(present('sourceCount'),present('sources')));
    }
    if (shape.title === 'ToolInvocationResult') {
      shape.toolResult = true; field(shape,'id',canonicalUuid); field(shape,'text',text(16384));
    }
    if (shape.title === 'RelayBlobDescriptor') {
      field(shape,'sha256',{pattern:'^[0-9a-f]{64}$'});
      field(shape,'byteLength',integer(1,50*1024*1024));
      field(shape,'mediaType',{maxLength:255,pattern:'^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}$'});
    }
    if(shape.properties?.kind && shape.properties?.text && shape.properties?.schedule && shape.properties?.blob) {
      rules(shape,{eq:[eq('kind','BLOB'),present('blob')]});
      rules(shape,implies(eq('kind','BLOB'),absent('text')));
    }
    if (shape.title === 'ImageAdmissionRequest') {
      field(shape, 'invocation', canonicalUuid); field(shape, 'requestId', canonicalUuid);
      field(shape, 'ordinal', integer(0, 15));
    }
    if (shape.title === 'ImageAdmissionReceipt') {
      const subject = {maxLength:128,pattern:'^(?:[A-Za-z0-9][A-Za-z0-9_.-]{0,63}|@service/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})$'};
      field(shape, 'project', identity); field(shape, 'owner', subject);
      field(shape, 'producer', subject);
      field(shape, 'imageId', {pattern:'^img_[0-9a-f]{32}$'});
      field(shape, 'sha256', {pattern:'^[0-9a-f]{64}$'});
      field(shape, 'width', integer(1,32768)); field(shape, 'height', integer(1,32768));
      field(shape, 'byteLength', integer(1,2147483647)); field(shape, 'admittedAt', instant);
    }
    const title = shape.title ?? '';
    if(title.startsWith('Proc')) {
      for(const key of ['total','completed','pending','running','blocked','failed','skipped','children','calls','attempts','activeCalls','repeatedCalls','causationDepth','queued','capacity'])field(shape,key,integer(0,9007199254740991));
      for(const key of ['snapshotAt','createdAt','lastTurnAt','endedAt','startedAt','recordedAt','oldestPendingAt','leaseUntil'])field(shape,key,instant);
      for(const key of ['conversations','calls','queues','summaries','observations','tools'])field(shape,key,{maxItems:100});
      field(shape,'signals',{maxItems:200});field(shape,'pools',{maxItems:200});
      if(title==='ProcAttempt') {
        field(shape,'number',integer(1));field(shape,'httpStatus',integer(100,599));
        field(shape,'inputTokens',decimal);field(shape,'outputTokens',decimal);
      }
    }
    if (shape.properties?.mode && shape.properties?.metadata && !shape.properties?.scope) field(shape,'metadata',{schemaInstance:true,maxJsonBytes:16384});
    if (title === 'InformationTypeContract') {
      for(const key of ['type','version'])field(shape,key,canonicalUuid);
      field(shape,'ordinal',integer(1)); field(shape,'label',text(120,1));field(shape,'description',text(4096));
      field(shape,'definitionHash',{pattern:'^[a-f0-9]{64}$'});
      field(shape,'schema',{documentSchema:true,maxJsonBytes:65536});field(shape,'presentation',{schemaInstance:true,maxJsonBytes:8192});
    }
    if (['InformationContentBinding','InformationContentMetadata'].includes(title)) {field(shape,'metadata',{schemaInstance:true,maxJsonBytes:16384});field(shape,'metadataHash',{pattern:'^[a-f0-9]{64}$'});}
    if (title === 'InformationTypeIdentity') {
      for(const key of ['type','version']) field(shape,key,canonicalUuid);
      field(shape,'ordinal',integer(1)); field(shape,'label',{...text(120,1),nonblank:true});
      field(shape,'slug',{pattern:'^[a-z](?:[a-z0-9-]{0,62}[a-z0-9])?$',maxLength:64});
      field(shape,'definitionHash',{pattern:'^[a-f0-9]{64}$'});
    }
    if (title === 'InformationTypeVersion') {field(shape,'controlRevision',integer(1,9007199254740991));field(shape,'createdAt',instant);}
    if (title === 'InformationContentView') {field(shape,'revision',canonicalUuid);field(shape,'startingRevision',canonicalUuid);field(shape,'reused',{maxItems:7});field(shape,'authorisedStages',{maxItems:7,uniqueItems:true});}
    if (title === 'MetadataValidationResult') field(shape,'issues',{maxItems:32});
    if (title === 'SchemaObject') add(shape,{maxProperties:1024,keyPattern:'^[^\\x00-\\x1f\\x7f-\\x9f]+$'});
    if (shape.properties?.source && shape.properties?.subject && shape.properties?.state)
      field(shape, 'source', { ...portName, maxLength: 1024 });
    if (['DiscoveryScopes','DiscoveryPage','DiscoveryFacets'].includes(title)) {
      field(shape,'items',{maxItems:50});
      field(shape,'scopes',{minItems:1,maxItems:32,uniqueItems:true,element:{pattern:'^(global_read|[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})$'}});
      field(shape,'nextCursor',{pattern:'^[A-Za-z0-9_-]{1,2048}\\.[A-Za-z0-9_-]{43}$',maxLength:2092});
      rules(shape,{eq:[g('hasMore'),not(eq('nextCursor',null))]});
      if(title==='DiscoveryFacets') {
        const values=resolve(graph,resolve(graph,shape.properties.items).items);
        field(values,'count',integer(1,9007199254740991));
      }
    }
    if(title==='DiscoveryScope') {
      field(shape,'id',{pattern:'^(global_read|[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})$'});
      field(shape,'kind',{pattern:'^(PROJECT|PERSONAL|ACCOUNT_KNOWLEDGE|CONTAINED|GLOBAL_READ)$'});
    }
    if(title==='DiscoveryItem') {
      field(shape,'revision',canonicalUuid);field(shape,'resource',canonicalUuid);field(shape,'scope',canonicalUuid);
      field(shape,'createdAt',instant);
      for(const key of ['tags','autoTag']) field(shape,key,{maxItems:32,element:{minLength:1,maxLength:64}});
    }
    if (title.startsWith('Relay')) {
      field(shape, 'topic', { ...portName, maxLength: 1024 });
      field(shape, 'parentTopic', { ...portName, maxLength: 1024 });
      if (title === 'RelayTopic') field(shape, 'name', { ...portName, maxLength: 1024 });
    }
  }
  for (const operation of ['relay.publish','relay.consume','relay.ack','tool.scope.worker.publish','tool.scope.worker.consume','tool.scope.worker.ack']) {
    const op='relay.'+operation.split('.').at(-1);
    const wrapper=resolve(graph,graph.inputs[operation]);
    const request=operation.startsWith('relay.')?wrapper:nested(wrapper,op==='relay.publish'?'publication':op==='relay.consume'?'consumption':'acknowledgement');
    const reply=resolve(graph,graph.results[operation]);
    for(const shape of [request,reply]) {
      field(shape,'project',{...identity,maxLength:256});
      for(const k of ['topic','parentTopic']) field(shape,k,{...portName,maxLength:1024});
      field(shape,'group',{...portName,maxLength:140});
      for(const k of ['consumerId','requestId','batchId','generation']) field(shape,k,canonicalUuid);
      for(const k of ['position','through','expiredThrough','fence']) field(shape,k,decimal);
      field(shape,'position',{disallow:['0']}); field(shape,'fence',{disallow:['0']});
      for(const k of ['occurredAt','publishedAt','expiresAt']) field(shape,k,instant);
    }
    if(op==='relay.publish') {
      field(request,'text',{...text(50 * 1024 * 1024,1),maxUtf8Bytes:50 * 1024 * 1024,nonblank:true});
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
  for (const shape of [resolve(graph,graph.inputs['relay.group.delete']),resolve(graph,graph.results['relay.group.delete'])]) {
    field(shape,'project',{...identity,maxLength:256});
    field(shape,'topic',{...portName,maxLength:1024});
    field(shape,'group',{...portName,maxLength:140});
    field(shape,'generation',canonicalUuid);
  }
  for(const shape of [resolve(graph,graph.inputs['relay.group.inspect']),resolve(graph,graph.results['relay.group.inspect'])]) {
    field(shape,'project',{...identity,maxLength:256});field(shape,'topic',{...portName,maxLength:1024});
    field(shape,'group',{...portName,maxLength:140});field(shape,'generation',canonicalUuid);
  }
  rules(resolve(graph,graph.results['relay.group.inspect']),{eq:[present('generation'),eq('exists',true)]});
  // Constraints owned by shared domain shapes apply to both requests and responses.
  for (const s of Object.values(graph.$defs)) {
    if (!s.properties) continue;
    field(s,'tagsVersion',integer(0,9007199254740991));
    const p = s.properties,
      title = s.title ?? '';
    if(title.startsWith('HostedTool')) {
      field(s,'project',{...identity,maxLength:256,disallowPrefixes:['client:']});
      for(const k of ['binding','registration','connection','requestId','invocation','credentialGeneration']) if(p[k]&&stringShape(p[k]))field(s,k,canonicalUuid);
      field(s,'generation',integer(1,9007199254740991));
      for(const k of ['owner','credentialSubject','subject'])field(s,k,{pattern:'^(?:[A-Za-z0-9][A-Za-z0-9_.-]{0,63}|@service/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})$',maxLength:128});
    }
    if(title==='HostedToolDefinition') {
      field(s,'credentialProject',providerProject);field(s,'subject',providerService);field(s,'executor',providerService);
      field(s,'scope',{...namedSwarm,trimmed:true});
      field(s,'provider',{pattern:'^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$',maxLength:48});
      field(s,'prefix',{pattern:'^[a-z][a-z0-9_]*_$',maxLength:48});
      field(s,'leaseSeconds',integer(1,300));
      s.properties.grants={type:'array',minItems:1,maxItems:128,uniqueItems:true,items:{type:'string',pattern:'^(?:\\*|[a-z][a-z0-9]*(?:_[a-z0-9]+)*)$',maxLength:256}};
      s.properties.agents={type:'array',minItems:1,maxItems:128,uniqueItems:true,items:{type:'string',...namedSwarm}};
      rules(s,implies({contains:[g('grants'),'*']},eq('grants',['*'])));
    }
    if(title==='HostedToolBinding') {
      rules(s,implies(eq('deleted',true),eq('enabled',false)),
        {eq:[g('project'),g('route.project')]},{eq:[g('definition.executor'),g('route.account')]},
        {eq:[g('definition.provider'),g('route.sourceProvider')]},
        ...['scope','prefix','grants','agents','leaseSeconds'].map(k=>({eq:[g('definition.'+k),g('route.'+k)]})),
        implies(eq('definition.subject',null),{eq:[g('credentialSubject'),g('owner')]}),
        implies(not(eq('definition.subject',null)),{eq:[g('credentialSubject'),g('definition.subject')]}));
    }
    if(title==='HostedToolRegistration') {field(s,'registeredAt',instant);field(s,'expiresAt',instant);}
    if(title==='HostedToolWorkerStatus')rules(s,implies(not(eq('registration',null)),{eq:[g('binding'),g('registration.binding.binding')]}));
    if(title==='HostedToolCredential'||title==='HostedToolCredentialRequest')rules(s,{eq:[eq('purpose','EXECUTION'),present('invocation')]});
    if(title==='HostedToolCredential')field(s,'value',{pattern:'^[\\x20-\\x7e]+$',minLength:1,maxLength:8200});
    if (['ProviderOAuthSettings','ProviderConnectionDefinition','ProviderConnection','ProviderCredentialStatus','ProviderMutationReceipt','ProviderAuthorization','ProviderCredentialTarget','ProviderCredentialMutation'].includes(title)) {
      field(s,'project',providerProject);
      for(const key of ['connection','revision','generation','connectionRevision','requestId']) {
        // Receipt.connection is a nested DTO, not a UUID.
        if(p[key] && stringShape(p[key])) field(s,key,canonicalUuid);
      }
    }
    if(title==='ProviderOAuthSettings') {
      for(const key of ['issuer','resource','resourceMetadataUrl','issuerMetadataUrl','authorizationEndpoint','tokenEndpoint','redirectUri']) field(s,key,providerUrl);
      field(s,'clientId',providerText(256));
      s.properties.scopes={type:'array',maxItems:64,uniqueItems:true,items:{type:'string',pattern:'^[\\x21\\x23-\\x5b\\x5d-\\x7e]{1,128}$',minLength:1,maxLength:128}};
    }
    if(title==='ProviderConnectionDefinition') {
      field(s,'name',providerText(128));field(s,'endpoint',providerUrl);
      rules(s, implies(eq('authentication','API_KEY'),and(eq('oauth',null),not(eq('apiKeyHeader',null)))),
        implies(not(eq('authentication','API_KEY')),and(not(eq('oauth',null)),eq('apiKeyHeader',null))));
    }
    if(title==='ProviderConnection') rules(s,implies(eq('deleted',true),eq('enabled',false)));
    if(title==='ProviderCredentialStatus') {
      field(s,'subject',{pattern:'^(?:[A-Za-z0-9][A-Za-z0-9_.-]{0,63}|@service/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})$',maxLength:128});
      field(s,'keyId',providerText(64));field(s,'expiresAt',instant);
      rules(s,implies(eq('state','NONE'),eq('generation',null)),implies(not(eq('state','NONE')),not(eq('generation',null))),
        implies(or(eq('state','READY'),eq('state','PENDING'),eq('state','REFRESHING')),not(eq('keyId',null))),
        implies(or(eq('state','NONE'),eq('state','REVOKED'),eq('state','UNCERTAIN')),eq('keyId',null)),
        implies(not(eq('state','READY')),eq('expiresAt',null)));
    }
    if(title==='ProviderMutationReceipt') {
      rules(s,implies(not(eq('credential',null)),and({eq:[g('credential.project'),g('connection.project')]},{eq:[g('credential.connection'),g('connection.connection')]})));
    }
    if(title==='ProviderAuthorization') {
      field(s,'authorizationUrl',{pattern:'^https://',minLength:1,maxLength:16384});field(s,'expiresAt',instant);
    }
    if (title === 'ApplicationServiceDeclaration') {
      field(s,'handle',{pattern:'^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$',maxLength:64});
    }
    if (title === 'ApplicationIdentityBinding' || title === 'ApplicationTokenReceipt') {
      field(s,'tokenId',{pattern:uuid,minLength:36,maxLength:36});
      field(s,'principal',{pattern:'^@service/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$',maxLength:45});
      if(title === 'ApplicationIdentityBinding') field(s,'credentialRequestId',{pattern:uuid,minLength:36,maxLength:36});
    }
    if (title === 'ApplicationCredential') field(s,'credential',{pattern:'^pss_[0-9a-f]{48}$',minLength:52,maxLength:52});
    if (title === 'ApplicationRelease') { field(s, 'revision', {pattern:uuid,minLength:36,maxLength:36}); field(s, 'digest', {pattern:'^[a-f0-9]{64}$',minLength:64,maxLength:64}); field(s, 'fileCount', integer(1,128)); }
    if (title === 'ApplicationDeploymentReceipt') field(s, 'requestId', {pattern:uuid,minLength:36,maxLength:36});
    if (title === 'ApplicationDeploymentStatus') { field(s, 'activeRevision', {pattern:uuid,minLength:36,maxLength:36}); field(s, 'releases', {maxItems:100}); }
    if (title === 'BudgetExtension') {
      field(s, 'calls', integer());
      field(s, 'steps', integer());
      rules(s, or({gt:[g('calls'),0]}, {gt:[g('steps'),0]}));
    }
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
    if (title === 'FileStoreCatalog') field(s, 'stores', {maxItems:100});
    if (title === 'FileStoreOption') field(s, 'alias', {pattern:'^[a-z][a-z0-9_-]{0,63}$',maxLength:64});
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
    if (title === 'EntryView') {
      field(s, 'job', { minLength: 1, nonblank: true });
      for (const key of ['promptTokens', 'completionTokens', 'reasoningTokens'])
        field(s, key, integer(0, 9007199254740991));
    }
    if (title === 'MessageDelivery') {
      for (const k of ['conversation', 'sourceConversation']) field(s, k, { minLength: 1, nonblank: true });
    }
    if (title === 'RelayCausation') {
      for (const k of ['rootId', 'parentId']) field(s, k, { ...identity, maxLength: 256 });
      field(s, 'depth', integer(-1, 32));
      rules(s, { eq: [{ gt: [g('depth'), 0] }, present('parentId')] });
    }
    if (p.text && (p.url || p.raw || p.data || p.kind))
      field(s, 'text', text(262144));
    if (p.text && p.kind && p.schedule && p.emits && p.fireAt)
      field(s, 'text', {...text(50 * 1024 * 1024, 1), maxUtf8Bytes:50 * 1024 * 1024, nonblank:true});
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
    if (p.mediaType && s.title !== 'RelayBlobDescriptor')
      field(s, 'mediaType', {
        maxLength: 256,
        pattern: '^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$',
      });
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
    if (title?.startsWith('Portable')) {
      for(const key of ['resource','revision','id','source','target','pinnedRevision','foreignResource','foreignRevision','foreignVersion','localVersion','localRevision','expectedRevision','bundle']) if(p[key] && stringShape(p[key])) field(s,key,canonicalUuid);
      for(const key of ['manifestHash','contentHash','textHash','sourceHash']) field(s,key,{pattern:'^[a-f0-9]{64}$',maxLength:64});
      if(p.path) field(s,'path',{portablePath:true,maxLength:240});
      if(title==='PortableDescriptor') {
        add(s,{portableGraph:'descriptor'}); field(s,'ordinal',integer(1));field(s,'sourceName',text(1024,1));field(s,'title',text(32768));
        field(s,'mediaType',{maxLength:256,pattern:'^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:;[ -~]{1,128})?$'});
        field(s,'tags',{maxItems:32,element:{...identity,maxLength:64}});
      }
      if(title==='PortableProvenance') {
        field(s,'owner',identity); field(s,'createdAt',instant);field(s,'producer',text(1024,1));field(s,'producerDefinitionHash',{pattern:'^[a-f0-9]{64}$',maxLength:64});rules(s,implies(present('producerDefinitionHash'),present('producer')));
        field(s,'sourceHash',{pattern:'^[a-f0-9]{64}$',maxLength:64}); field(s,'dependencies',{maxItems:16,uniqueItems:true,element:canonicalUuid}); field(s,'evidence',{maxItems:64});
      }
      if(title==='PortableEvidence') {
        field(s,'start',integer()); field(s,'end',integer(1)); field(s,'quote',{...text(65536),nonblank:true}); field(s,'locator',text(4096)); rules(s,{gt:[g('end'),g('start')]});
      }
      if(title==='PortableBundle') {add(s,{portableGraph:'bundle',maxJsonBytes:8388608}); field(s,'documents',{minItems:1,maxItems:16}); field(s,'relationships',{maxItems:64});}
      if(title==='PortableImportPlan') {add(s,{portableGraph:'plan',maxJsonBytes:8388608}); field(s,'destinations',{minItems:1,maxItems:16}); field(s,'types',{maxItems:16}); field(s,'reuse',{maxItems:16});}
      if(title==='PortableDestination') field(s,'name',{...identity,maxLength:1024});
      if(title==='PortableDocument') field(s,'bytesBase64',{minLength:4,maxLength:2796204,pattern:'^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/][AQgw]==|[A-Za-z0-9+/]{2}[AEIMQUYcgkosw048]=)?$'});
      if(title==='PortableLink') field(s,'relation',{pattern:'^[a-z][a-z0-9_-]{0,63}$',maxLength:64});
      if(title==='PortableImported') field(s,'mappings',{minItems:1,maxItems:16});
      if(title==='PortableReadable') {add(s,{portableGraph:'readable'});field(s,'markdown',text(2097152));}
    }
    if (title === 'NavigationPath') field(s,'steps',{minItems:1,maxItems:32,navigationSteps:true});
    if (title === 'ReferencePolicySettings') field(s,'referenceLabel',{...identity,maxLength:120});
    if (['CurrentDocument','DocumentTarget','DocumentRelationship','NavigationChanged','SavedNavigationView'].includes(title)) {
      for(const key of ['resource','revision','source','target','pinnedRevision','id']) if(p[key] && stringShape(p[key])) field(s,key,{pattern:uuid});
    }
    if(title==='DocumentRelationship' || p.kind && p.relation) field(s,'relation',{pattern:'^[a-z][a-z0-9_-]{0,63}$',maxLength:64});
    if(title==='SavedNavigationView') field(s,'label',{...identity,maxLength:120});
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
  // Apply operation-specific bounds after common identifier defaults; they are narrower.
  for (const op of ['acting.turn.start','acting.turn.receipt']) {
    const s=resolve(graph,graph.inputs[op]);
    if (op==='acting.turn.start' || op==='acting.turn.receipt') field(s,'requestId',canonicalUuid);
    if (op==='acting.turn.start') {
      field(s,'agent',{pattern:'^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$',maxLength:64});
      field(s,'task',{minLength:1,maxLength:65536,maxUtf8Bytes:65536,nonblank:true,noNul:true});
      field(s,'conversation',{pattern:'^cnv_[A-Za-z0-9]+$',maxLength:128});
    }

  }
  field(resolve(graph,graph.inputs['conversation.search']),'conversation',{...identity,maxLength:256});
  const toolRead=resolve(graph,graph.inputs['tool.invocation.read']);
  field(toolRead,'project',{...identity,maxLength:256}); field(toolRead,'invocation',canonicalUuid);
  annotatePush(push);
  // Binary HTTP uses the same DTO graph without inventing WebSocket operations.
  for (const [name, ref] of Object.entries(graph.http ?? {})) {
    const s = resolve(graph, ref);
    for (const row of [s, nested(s, 'publication')].filter(Boolean)) {
      field(row, 'requestId', canonicalUuid);
      field(row, 'eventId', canonicalUuid);
      field(row, 'project', { ...identity, maxLength: 256 });
      for (const key of ['topic', 'parentTopic'])
        field(row, key, { ...portName, maxLength: 1024 });
      field(row, 'group', { ...portName, maxLength: 140 });
      for (const key of ['correlationId', 'parentEventId'])
        field(row, key, { ...identity, maxLength: 256 });
      for (const key of ['occurredAt', 'publishedAt']) field(row, key, {...instant,pattern:'^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(?:\\.\\d{1,9})?(?:Z|[+-]\\d\\d:\\d\\d)$'});
      field(row, 'position', { ...decimal, disallow: ['0'] });
      field(row, 'mediaType', {
        maxLength: 255,
        pattern:
          '^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}$',
      });
    }
    if (name === 'RelayBlobPublish')
      rules(s, { eq: [present('parentTopic'), present('parentEventId')] });
  }
  return graph;
}
