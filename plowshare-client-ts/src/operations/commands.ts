import { informationPayloadProblem } from './information-validation.ts'
import { BOUND_OPERATIONS } from './administration.ts'
import { CLI_OPERATIONS } from './catalog.ts'
import type { ExtendedPayloads } from './catalog.ts'
import { parseDirect, problem } from './direct.ts'
import type { Parsed, Request } from './direct.ts'

type ExtendedOperation = keyof ExtendedPayloads
// Required text fields, followed by optional fields. Unknown keys are refused,
// particularly agent.run.maxModelCalls, which the server cannot honour.
export const SHAPES: Record<ExtendedOperation, readonly [readonly string[], readonly string[]]> = {
    'admin.pricing.list': [[], []],
    'admin.pricing.set': [['billingRoute','model','expectedVersion','mode'], ['currency','rates','tiers','requestFee','source']],
    'admin.status': [[], []],
    'admin.accounts': [[], []],
    'admin.account.create': [['handle'], ['serverAdmin']],
    'admin.account.update': [['handle'], ['enabled', 'serverAdmin']],
    'admin.account.reset': [['handle'], []],
    'admin.sessions': [['handle'], []],
    'admin.session.revoke': [['handle'], []],
    'admin.audit': [[], ['handle', 'before', 'limit']],
    'admin.service.accounts': [[], []],
    'admin.service.account.create': [['handle'], []],
    'admin.service.account.update': [['handle', 'enabled'], []],
    'admin.service.tokens': [['handle'], []],
    'admin.service.token.create': [['handle', 'name', 'scopes'], ['expiresInDays']],
    'admin.service.token.rotate': [['handle', 'id'], ['expiresInDays']],
    'admin.service.token.revoke': [['handle', 'id'], []],
    'outgoing.send': [['requestId','peer','message'], ['project','conversation']],
    'outgoing.status': [['id'], []], 'outgoing.cancel': [['id'], []], 'outgoing.peers': [[], ['project']],
    'usage.conversation': [['conversation'], ['project', 'agent', 'run', 'orchestration', 'model', 'pool', 'route', 'scope', 'from', 'to', 'group_by', 'cursor', 'limit']],
    'usage.project': [['project'], ['conversation', 'agent', 'run', 'orchestration', 'model', 'pool', 'route', 'scope', 'from', 'to', 'group_by', 'cursor', 'limit']],
    'usage.agent': [['agent'], ['conversation', 'project', 'run', 'orchestration', 'model', 'pool', 'route', 'scope', 'from', 'to', 'group_by', 'cursor', 'limit']],
    'usage.run': [['run'], ['conversation', 'project', 'agent', 'orchestration', 'model', 'pool', 'route', 'scope', 'from', 'to', 'group_by', 'cursor', 'limit']],
    'usage.orchestration': [['orchestration'], ['conversation', 'project', 'agent', 'run', 'model', 'pool', 'route', 'scope', 'from', 'to', 'group_by', 'cursor', 'limit']],
    'usage.models': [[], ['conversation', 'project', 'agent', 'run', 'orchestration', 'model', 'pool', 'route', 'scope', 'from', 'to', 'group_by', 'cursor', 'limit']],
    'usage.pools': [[], ['conversation', 'project', 'agent', 'run', 'orchestration', 'model', 'pool', 'route', 'scope', 'from', 'to', 'group_by', 'cursor', 'limit']],
    'usage.calls': [[], ['conversation', 'project', 'agent', 'run', 'orchestration', 'model', 'pool', 'route', 'scope', 'from', 'to', 'group_by', 'cursor', 'limit', 'call', 'attempt_cursor']],
    'conversation.context.snapshot': [['conversation','agent'], ['measure']],
    'conversation.context.count': [['conversation','agent'], []],
    'information.upload': [["scope", "requestId", "name", "text"], []],
    'information.acquire': [["scope", "requestId", "url"], ["name"]],
    'information.facets': [["scope"], ["kind", "filter"]],
    'information.tags.groups': [['scope','revision','requestId','groups'], []],
    'information.tags': [["scope", "revision", "requestId", "tags"], []],
    'information.list': [["scope"], ["kind", "filter", "limit", "offset"]],
    'information.await': [["scope", "sources"], ["waitMs"]],
    'information.status': [["scope"], ["revision", "acquisition"]],
    'information.read': [["scope", "revision"], ["offset", "limit"]],
    'information.outline': [["scope", "corpus", "revision"], ["limit", "offset"]],
    'information.symbols': [["scope", "corpus", "query"], ["revision", "limit", "offset"]],
    'information.search': [["scope", "query"], ["revision", "filter", "limit"]],
    'information.rank': [["scope", "query"], ["filter", "limit"]],
    'information.ask': [["scope", "revision", "question"], ["maxModelCalls"]],
    'information.evidence.record': [["scope", "revision", "requestId", "start", "end", "quote", "locator"], []],
    'information.evidence.read': [["scope", "evidence"], []],
    'information.record.report': [["scope", "requestId", "name", "text"], ["inputs", "evidence", "feedback", "objectives", "findings", "reviews", "scopeChanges"]],
    'information.finalise': [["scope", "revision", "requestId"], []],
    'information.link': [["scope", "revision", "requestId", "collectionProject"], []],
    'information.unlink': [["scope", "revision", "requestId", "collectionProject"], []],
    'information.share': [["scope", "revision", "requestId"], []],
    'information.unshare': [["scope", "revision", "requestId"], []],
    'information.withdraw': [["scope", "revision", "requestId"], []],
    'information.exclude': [["scope", "revision", "requestId"], []],
    'information.unexclude': [["scope", "revision", "requestId"], []],
    'information.restore': [["scope", "revision", "requestId"], []],
    'information.delete': [["scope", "revision", "requestId"], []],
    'information.retry': [["scope"], ["acquisition", "revision", "requestId"]],
    'information.revise': [["scope", "revision", "requestId", "text"], ["name"]],
    'information.replace': [["scope", "revision", "requestId", "text"], ["name"]],
    'information.refresh': [["scope", "revision", "requestId"], []],
    'information.rebuild': [["scope", "revision", "requestId", "stage"], []],
    'information.allowance': [["scope", "revision", "requestId", "maxModelCalls"], []],
    'information.events': [["scope"], ["after", "limit"]],
    'information.migration.list': [[], ["scope", "limit", "offset"]],
    'information.migration.adopt': [["revision", "owner", "visibility", "reason", "requestId"], ["scope", "collectionProject"]],
    'information.migration.inspect': [["payload", "reason"], ["scope", "limit", "offset"]],
    'information.migration.release': [["scope", "payload", "owner", "reason", "requestId"], ["inputs"]],
    'information.inventory': [["scope"], ["limit", "offset"]],
    'information.acquisitions': [["scope"], ["limit", "offset"]],
    'conversation.open': [[], ['project', 'maxModelCalls', 'maxTurns', 'noTurnCap', 'noBudget']],
    'conversation.list': [[], ['project', 'lifecycle']], 'conversation.latest': [['agent'], ['project']],
    'conversation.lifecycle': [['conversation', 'lifecycle'], []],
    'conversation.turns': [['conversation'], []], 'conversation.compactions': [['conversation'], []],
    'conversation.chat': [['conversation'], ['offset', 'limit']],
    'conversation.trajectory': [['conversation'], ['offset', 'limit', 'after', 'before', 'tail', 'kinds', 'drawn']],
    'conversation.context': [['conversation'], ['agent']], 'conversation.projection': [['conversation'], ['agent', 'turn']],
    'conversation.resume': [['conversation'], ['agent', 'session', 'maxTurns', 'noTurnCap', 'maxModelCalls']],
    'agent.list': [[], ['project']], 'agent.define': [['name', 'text'], ['project', 'overwrite']],
    'agent.run': [['agent', 'task'], ['project', 'session', 'conversation', 'newConversation', 'maxTurns', 'noTurnCap', 'images']],
    'job.list': [[], []], 'job.limits': [['job'], ['maxTurns', 'noTurnCap', 'maxModelCalls']],
    'document.ask': [['document', 'question'], ['maxModelCalls']],
    'document.retrieve': [['query'], ['document', 'limit']], 'document.list': [[], ['q', 'offset', 'limit']],
    'document.detail': [['document'], []], 'document.chunk': [['chunk'], []], 'document.rank': [['query'], ['limit']],
    'document.stance': [['document', 'claim'], []], 'document.citations': [[], ['document', 'conversation', 'limit']],
    'document.search': [['query'], ['limit', 'mode']], 'web.search': [['query', 'pageSize', 'max', 'page'], []],
    'web.fetch': [['url'], ['offset']], 'project.list': [[], []],
    'project.attach': [['name', 'workspace', 'machine'], []],
    'project.create': [['name'], ['workspace', 'type', 'writePaths']],
    'project.define': [['name', 'workspace'], ['lent', 'exclusions']],
    'project.lend': [['project'], ['roots']], 'project.unlend': [['project'], ['roots']],
    'project.workspace': [['project', 'workspace'], []], 'project.move': [['project', 'to'], []],
    'project.forget': [['project'], []], 'project.access': [['project'], []], 'project.member.role': [['project','handle','role'], []], 'project.member.add': [['project', 'handle'], ['role']],
    'project.member.remove': [['project', 'handle'], []],
    'approval.list': [[], ['conversation', 'project', 'mine']],
    'approval.answer': [['id', 'decision'], ['prefix']], 'approval.revoke': [['id'], []],
    'message.instances': [['project'], ['archived', 'offset', 'limit']],
    'message.instance': [['instance'], []], 'message.instance.open': [['project', 'agent', 'requestId'], ['makeDefault']],
    'message.instance.default': [['instance'], []], 'message.instance.stop': [['instance'], []],
    'message.instance.archive': [['instance'], []], 'message.deliveries': [['instance'], ['offset', 'limit']],
    'message.delivery': [['message'], []], 'message.cancel': [['message'], []],
    'board.topics': [[], ['project', 'offset', 'limit']], 'board.messages': [['topic'], []], 'swarm.status': [[], []],
    'board.topup': [['topic'], ['maxModelCalls']], 'buffer.purge': [[], []], 'retention.sweep': [[], []],
    'board.open': [['project', 'title', 'label', 'body', 'requestId'], ['maxModelCalls']],
    'board.retry': [['project', 'topic', 'member', 'requestId', 'maxTurns'], []],
    'board.post': [['project', 'topic', 'body', 'requestId'], []],
    'provider.list': [[], []], 'provider.deregister': [['provider'], []],
    'inbox.list': [[], ['unread', 'offset', 'limit']], 'inbox.read': [[], ['items']],
    'orchestration.start': [['agent', 'definition', 'request', 'requestId'], ['project', 'context']],
    'orchestration.receipt': [['requestId'], []],
    'todos.read': [['conversation'], []], 'orchestration.definitions': [[], ['project']],
    'orchestration.list': [[], ['project', 'state', 'limit']], 'orchestration.status': [['id'], []],
    'orchestration.answer': [['id'], ['answer', 'choices']], 'orchestration.cancel': [['id'], []],
    'orchestration.caps': [['project'], []],
    'orchestration.record': [['root'], ['after', 'before', 'tail', 'limit', 'kinds']],
    'schedule.list': [[], []], 'schedule.define': [['schedule', 'cron', 'emits'], ['zone']],
    'schedule.read': [['text'], ['zone', 'project', 'conversation']],
    'schedule.pause': [['schedule'], ['paused']], 'schedule.forget': [['schedule'], []],
    'trigger.list': [[], []],
    'trigger.define': [['trigger', 'event', 'agent', 'task'], ['project', 'conversation', 'maxModelCalls', 'maxTurns', 'queueCap']],
    'trigger.pause': [['trigger'], ['paused']], 'trigger.forget': [['trigger'], []],
    'event.fire': [['event'], ['data']], 'firing.list': [[], ['trigger', 'status', 'offset', 'limit']],
    'union.status': [['project'], []], 'union.conflict.list': [['project'], []],
} satisfies { [K in ExtendedOperation]: readonly [readonly (keyof ExtendedPayloads[K] & string)[], readonly (keyof ExtendedPayloads[K] & string)[]] }
const SCOPED: readonly ExtendedOperation[] = ['project.access','project.member.add','project.member.remove','project.member.role','message.instances', 'message.instance.open', 'usage.project', 'orchestration.start', 'conversation.open', 'conversation.list', 'conversation.latest', 'agent.list', 'agent.define', 'orchestration.definitions', 'orchestration.list', 'orchestration.caps', 'schedule.read', 'union.status', 'union.conflict.list']
const ARRAY_FIELDS = ['writePaths', 'group_by', 'images', 'roots', 'lent', 'exclusions', 'kinds', 'items']
const BOOLEAN_FIELDS = ['enabled', 'serverAdmin', 'makeDefault', 'archived', 'newConversation', 'noTurnCap', 'noBudget', 'overwrite', 'tail', 'drawn', 'paused', 'mine', 'unread']
const NUMBER_FIELDS = ['expiresInDays', 'maxTurns', 'maxModelCalls', 'offset', 'limit', 'after', 'before', 'turn', 'pageSize', 'max', 'page', 'queueCap']
const NULLABLE = ['project', 'session', 'conversation', 'document']

export function commandProblem(type: ExtendedOperation, body: Record<string, unknown>): string | undefined {
    const [required, optional] = SHAPES[type], allowed = [...required, ...optional, ...(type.startsWith('information.') ? ['corpus'] : [])]
    for (const key of Object.keys(body)) if (!allowed.includes(key)) return `${type} does not accept ${key}`
    if (type === 'orchestration.start' || type === 'orchestration.receipt' || type === 'board.post' || type === 'board.open' || type === 'board.retry' || type === 'outgoing.send' || type === 'message.instance.open') {
        if (typeof body['requestId'] !== 'string' || !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(body['requestId'])) return 'requestId must be a UUID retained for receipt recovery'
    }
    if (type === 'project.create') {
        if (body['type'] !== undefined && !['MANAGED', 'DISJOINT'].includes(String(body['type']))) return 'type must be MANAGED or DISJOINT';
        if (body['type'] === 'DISJOINT' && (typeof body['workspace'] !== 'string' || !body['workspace'].trim())) return 'DISJOINT needs an existing server workspace';
        if (Array.isArray(body['writePaths']) && body['writePaths'].some(path => typeof path !== 'string' || path.startsWith('/') || path.includes('\\') || path.split('/').includes('..'))) return 'writePaths must stay within the workspace';
    }
    if (type.startsWith('project.member.') && body['role'] !== undefined && !['VIEWER','CONTRIBUTOR','MANAGER'].includes(String(body['role']))) return 'role must be VIEWER, CONTRIBUTOR or MANAGER'
    if (type === 'admin.account.update' && !('enabled' in body) && !('serverAdmin' in body)) return 'admin.account.update needs enabled or serverAdmin'
    if (type === 'admin.audit' && typeof body['limit'] === 'number' && body['limit'] > 100) return 'admin.audit limit must be between 1 and 100'
    if (type.startsWith('admin.service.token.')) {
        if (body['expiresInDays'] !== undefined && (typeof body['expiresInDays'] !== 'number' || !Number.isInteger(body['expiresInDays']) || body['expiresInDays'] < 1 || body['expiresInDays'] > 365)) return 'expiresInDays must be 1–365'
        if (type !== 'admin.service.token.create' && (typeof body['id'] !== 'string' || !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(body['id']))) return 'id must be a token UUID'
        if (type === 'admin.service.token.create') {
            const scopes = body['scopes']
            if (!Array.isArray(scopes) || scopes.length < 1 || scopes.length > 100) return 'Supply 1–100 project scopes'
            const projects = new Set<string>()
            for (const scope of scopes) {
                if (!scope || typeof scope !== 'object' || Array.isArray(scope) || Object.keys(scope).some(key => !['project','role'].includes(key)) || typeof scope.project !== 'string' || !scope.project.trim() || /^(?:personal:|Personal:|client:)/.test(scope.project) || projects.has(scope.project) || !['VIEWER','CONTRIBUTOR','MANAGER'].includes(scope.role)) return 'Scopes need distinct ordinary server projects and VIEWER, CONTRIBUTOR or MANAGER roles'
                projects.add(scope.project)
            }
        }
    }
    if (type === 'admin.pricing.set') return pricingProblem(body)
    if (type.startsWith('information.')) return informationPayloadProblem(type, body, required)
    if (type === 'outgoing.send' && (body['message'] === null || typeof body['message'] !== 'object' || Array.isArray(body['message']))) return 'message must be a JSON object'
    if (type === 'web.search') for (const key of ['pageSize', 'max', 'page']) if (!(key in body)) return `web.search needs ${key}; the server has no paging defaults`
    for (const key of required) if (!NUMBER_FIELDS.includes(key) && !BOOLEAN_FIELDS.includes(key) && key !== 'scopes' && !(type === 'outgoing.send' && key === 'message') && (typeof body[key] !== 'string' || body[key].trim() === '')) return `${type} needs ${key} as nonblank text`
    for (const key of [...optional, ...required.filter(key => NUMBER_FIELDS.includes(key) || BOOLEAN_FIELDS.includes(key))]) {
        if (!(key in body)) continue
        const value = body[key]
        if (key === 'choices') continue // JSON value; the server owns the question's structure.
        if (key === 'data') {
            if (value !== null && (typeof value !== 'object' || Array.isArray(value))) return 'data must be a JSON object or null'
            continue
        }
        if (key === 'answer') {
            if (value !== null && typeof value !== 'string') return 'answer must be text or null'
            continue
        }
        if (key === 'prefix') {
            if (!Array.isArray(value) || value.some(item => typeof item !== 'string')) return 'prefix must be an array of strings'
            continue
        }
        if (value === null && NULLABLE.includes(key)) continue
        if (ARRAY_FIELDS.includes(key)) {
            if (!Array.isArray(value) || value.some(item => typeof item !== 'string' || item.trim() === '')) return `${key} must be an array of nonblank strings`
        } else if (BOOLEAN_FIELDS.includes(key)) {
            if (typeof value !== 'boolean') return `${key} must be boolean`
        } else if (NUMBER_FIELDS.includes(key)) {
            const minimum = (['offset', 'after'].includes(key) || type === 'admin.audit' && key === 'before') ? 0 : 1
            if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum || value > 2147483647) return `${key} must be an integer between ${minimum} and 2147483647`
        } else if (typeof value !== 'string' || value.trim() === '') return `${key} must be nonblank text`
    }
    if (type.startsWith('usage.') && body['scope'] !== undefined && !['direct','subtree'].includes(String(body['scope']))) return 'usage scope must be direct or subtree'
    if (body['noTurnCap'] === true && 'maxTurns' in body) return 'choose maxTurns or noTurnCap:true'
    if (body['noBudget'] === true && 'maxModelCalls' in body) return 'choose maxModelCalls or noBudget:true'
    if (type === 'agent.run' && body['newConversation'] === true) {
        if (body['conversation'] != null) return 'newConversation cannot be combined with a conversation id'
        if (Array.isArray(body['images']) && body['images'].length > 0) return 'a new conversation cannot carry images; use --standalone'
    }
    if (type === 'agent.run' && body['conversation'] != null) {
        if (body['project'] != null) return 'conversation owns its project; omit project for a conversation turn'
        if (Array.isArray(body['images']) && body['images'].length > 0) return 'images are not supported in conversation turns'
    }
    if (type === 'document.citations' && body['document'] != null && body['conversation'] != null) return 'citations accepts document or conversation, not both'
    if (type === 'project.lend' || type === 'project.unlend') if (!Array.isArray(body['roots']) || body['roots'].length === 0) return `${type} needs a nonempty roots array`
    if ((type === 'conversation.trajectory' || type === 'orchestration.record') && [body['after'] != null, body['before'] != null, body['tail'] === true].filter(Boolean).length > 1) return 'trajectory accepts one of after, before, tail:true'
    if (type === 'approval.list' && [body['conversation'] != null, body['project'] != null, body['mine'] === true].filter(Boolean).length !== 1) return 'approval.list needs exactly one of conversation, project, mine:true'
    if (type === 'approval.answer') {
        if (!['once', 'conversation', 'project', 'deny'].includes(String(body['decision']))) return 'decision must be once, conversation, project or deny'
        if (body['decision'] === 'project' && (!Array.isArray(body['prefix']) || body['prefix'].length === 0 || body['prefix'][0] === '')) return 'project approval needs a nonempty prefix beginning with the program'
    }
    if (type === 'orchestration.answer' && body['choices'] == null && (typeof body['answer'] !== 'string' || body['answer'].trim() === '')) return 'orchestration.answer needs answer text or structured choices'
    if (type === 'inbox.read' && (!Array.isArray(body['items']) || body['items'].length === 0)) return 'inbox.read needs a nonempty items array'
    if ((type === 'schedule.pause' || type === 'trigger.pause') && typeof body['paused'] !== 'boolean') return `${type} needs paused:true or paused:false`
    if (type === 'board.topup' && !('maxModelCalls' in body)) return 'board.topup needs maxModelCalls, the new total'
    if (type === 'trigger.define' && body['conversation'] != null && (body['project'] != null || body['maxModelCalls'] != null || body['maxTurns'] != null)) return 'conversation triggers use the conversation home and limits; omit project, maxModelCalls and maxTurns'
    return problem(type, body)
}

const COMMAND_HEADS = Object.keys(CLI_OPERATIONS).sort((left,right) => right.length-left.length)
    .map(name => ({name, pattern: new RegExp('^'+name.split(' ').join('\\s+')+'(?:\\s+(.*))?$','s')}))

/** Shared headless command grammar. TUI parseDirect retains its own command boundaries. */
export function parseCommand(line: string, project?: string): Parsed {
    const trimmed = line.trim()
    const recognized = COMMAND_HEADS.map(head => ({name:head.name,match:head.pattern.exec(trimmed)})).find(head=>head.match !== null)
    const fallback = /^(\S+)\s+(\S+)/.exec(trimmed)
    const name = recognized?.name ?? (fallback ? `${fallback[1]} ${fallback[2]}` : '')
    if (!Object.hasOwn(CLI_OPERATIONS, name)) {
        const bound = name.replace(' ', '.').replace('conflict-', 'conflict.')
        if (Object.hasOwn(BOUND_OPERATIONS, bound)) return { kind: 'usage', said: `${bound} requires ${BOUND_OPERATIONS[bound as keyof typeof BOUND_OPERATIONS]}; use a persistent platform client` }
        return parseDirect(line, project)
    }
    const type = CLI_OPERATIONS[name as keyof typeof CLI_OPERATIONS]
    const argument = (recognized?.match?.[1] ?? '').trim()
    let body: Record<string, unknown>
    if (argument.startsWith('{')) {
        try {
            const value: unknown = JSON.parse(argument)
            if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error()
            body = value as Record<string, unknown>
        } catch { return { kind: 'usage', said: 'payload must be a valid JSON object' } }
    } else if (argument === '') body = {}
    else {
        const [required, optional] = SHAPES[type]
        // Single-identifier reads and query retrieval accept plain text.
        const key = required.length === 1 && !type.startsWith('project.') && type !== 'agent.define' ? required[0] : undefined
        if (key === undefined || optional.includes('roots')) return { kind: 'usage', said: `${name} takes a JSON payload` }
        body = { [key]: argument }
    }
    if (!('project' in body) && project !== undefined && ((SCOPED.includes(type) || type === 'outgoing.send' || type === 'outgoing.peers')
        || ((type === 'agent.run' || type === 'trigger.define') && body['conversation'] == null)
        || (type === 'approval.list' && body['conversation'] == null && body['mine'] !== true))) body = { ...body, project }
    const said = commandProblem(type, body)
    return said === undefined ? { kind: 'request', request: { type, payload: body } as Request } : { kind: 'usage', said }
}

/** Used by adapters that read a complete JSON payload from stdin. */
export function isPayloadCommand(command: string): boolean {
    return Object.hasOwn(CLI_OPERATIONS, command) || /^(?:memory\s+\w+|search|job\s+(?:status|cancel))$/.test(command)
}

/** Match the event socket identity without replacing an explicitly supplied session. */
export function withSession(asked: Request, session: string): Request {
    if (asked.type === 'agent.run' && !('session' in asked.payload)) return { ...asked, payload: { ...asked.payload, session } }
    if (asked.type === 'conversation.resume' && !('session' in asked.payload)) return { ...asked, payload: { ...asked.payload, session } }
    return asked
}

/** Decimal text preserves exact operator rates; nested shapes are checked before sending. */
function pricingProblem(body: Record<string, unknown>): string | undefined {
    for (const key of ['billingRoute','model','expectedVersion','mode']) if (typeof body[key] !== 'string' || !body[key].trim()) return `${key} is required`
    if (!['TOKEN','INCLUDED','ZERO_RATE','UNPRICED'].includes(String(body['mode']))) return 'Choose TOKEN, INCLUDED, ZERO_RATE or UNPRICED'
    if ((body['mode'] !== 'UNPRICED' || body['currency'] !== undefined) && (typeof body['currency'] !== 'string' || !/^[A-Z]{3}$/.test(body['currency']))) return 'currency must be three uppercase letters'
    if (body['source'] !== undefined && (typeof body['source'] !== 'string' || !body['source'].trim() || body['source'].length > 512)) return 'source must be nonblank text, at most 512 characters'
    const decimal = (value: unknown) => typeof value === 'string' && /^[0-9]{1,18}(\.[0-9]{1,12})?$/.test(value)
    const rates = (value: unknown) => value !== null && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).every(key => ['input','output','cacheRead','cacheWrite'].includes(key)) && Object.values(value).every(decimal)
    if (body['mode'] !== 'TOKEN') return ['rates','tiers','requestFee'].some(key => body[key] !== undefined) ? 'Only TOKEN mode accepts rates, tiers or request fees' : undefined
    if (!rates(body['rates']) || !decimal((body['rates'] as Record<string, unknown>)['input']) || !decimal((body['rates'] as Record<string, unknown>)['output'])) return 'Token pricing needs input and output decimal strings'
    if (body['requestFee'] !== undefined && !decimal(body['requestFee'])) return 'requestFee must be a nonnegative decimal string'
    if (body['tiers'] !== undefined) {
        if (!Array.isArray(body['tiers']) || body['tiers'].length > 100) return 'tiers must be an array with at most 100 entries'
        let previous = -1
        for (const tier of body['tiers']) {
            if (!tier || typeof tier !== 'object' || Object.keys(tier).some(key => !['fromInputTokens','rates'].includes(key)) || !Number.isSafeInteger(tier.fromInputTokens) || tier.fromInputTokens <= previous || !rates(tier.rates)) return 'Tiers need increasing nonnegative thresholds and decimal-string rates'
            previous = tier.fromInputTokens
        }
    }
}
