export type InformationScope = { kind: 'personal' | 'shared'; includeShared?: boolean }
    | { kind: 'project'; project: string; includeShared?: boolean }
export const INFORMATION_OPERATIONS = [
    'upload', 'acquire', 'refresh', 'revise', 'replace', 'list', 'inventory', 'acquisitions', 'status', 'read',
    'search', 'rank', 'ask', 'evidence.record', 'evidence.read', 'record.report', 'finalise',
    'link', 'unlink', 'share', 'unshare', 'withdraw', 'exclude', 'unexclude', 'restore', 'delete', 'retry',
    'rebuild', 'allowance', 'events', 'migration.list', 'migration.adopt', 'migration.inspect', 'migration.release',
] as const
export type InformationOperation = typeof INFORMATION_OPERATIONS[number]
export interface InformationTransport { ask(type: string, payload: unknown): Promise<{ readonly code: string; readonly said?: string; readonly payload?: unknown }> }

/** All operations use the existing socket. Callers keep mutation request IDs across uncertain delivery. */
export class InformationClient {
    private readonly transport: InformationTransport
    readonly scope: InformationScope
    constructor(transport: InformationTransport, scope: InformationScope) { this.transport=transport;this.scope=scope }
    async call(operation: InformationOperation, payload: Record<string, unknown> = {}): Promise<unknown> {
        if (!(INFORMATION_OPERATIONS as readonly string[]).includes(operation)) throw new Error('Unknown information operation.')
        if (this.scope.kind === 'project' && !this.scope.project.trim()) throw new Error('Select a project.')
        const outcome = await this.transport.ask(`information.${operation}`, { ...payload, scope: this.scope })
        if (outcome.code !== 'OK' && outcome.code !== 'ACCEPTED') throw new Error(outcome.said ?? `Information request answered ${outcome.code}.`)
        return outcome.payload
    }
}
export interface InformationChanged { kind: 'information.changed'; sequence: number; revision: string; generation: number }
export function informationChanged(value: unknown): InformationChanged | undefined {
    if (!value || typeof value !== 'object') return undefined
    const row = value as Record<string, unknown>
    if (row.kind !== 'information.changed' || typeof row.revision !== 'string'
        || typeof row.sequence !== 'number' || !Number.isSafeInteger(row.sequence) || row.sequence < 1
        || typeof row.generation !== 'number' || !Number.isSafeInteger(row.generation) || row.generation < 1) return undefined
    return row as unknown as InformationChanged
}

export interface InformationCommand { operation: InformationOperation; scope: InformationScope; payload: Record<string, unknown> }
export const INFORMATION_HELP = 'information <operation> [--scope personal|shared|project] [--project NAME] {JSON payload}. Mutations need a stable UUID requestId; retain it across disconnects. Operations: ' + INFORMATION_OPERATIONS.join(', ')
/** JSON remains intact; shell/slash parsing must not strip quotes inside source text. */
export function informationCommand(text: string, project?: string): InformationCommand {
    const at=text.indexOf('{')
    const prefix=((at<0?text:text.slice(0,at)).trim().match(/"(?:\\.|[^"\\])*"|'[^']*'|[^\s]+/g) ?? []).map(word=>word.startsWith('"')?JSON.parse(word) as string:word.startsWith("'")?word.slice(1,-1):word)
    if(prefix.shift()!=='information')throw new Error(INFORMATION_HELP)
    const operation=prefix[0]?.startsWith('--')?'list':prefix.shift() ?? 'list'
    if(!(INFORMATION_OPERATIONS as readonly string[]).includes(operation))throw new Error(INFORMATION_HELP)
    let kind: string=project?'project':'personal', chosen=project
    while(prefix.length){
        const flag=prefix.shift(), value=prefix.shift()
        if(!value || value.startsWith('--'))throw new Error(`${String(flag)} needs a value.`)
        if(flag==='--scope')kind=value
        else if(flag==='--project'){chosen=value;kind='project'}
        else throw new Error(`Unknown information option: ${String(flag)}`)
    }
    if(!['personal','shared','project'].includes(kind))throw new Error('Scope must be personal, shared or project.')
    if(kind==='project' && !chosen?.trim())throw new Error('Select a project.')
    const payload: unknown=at<0?{}:JSON.parse(text.slice(at))
    if(!payload || typeof payload!=='object' || Array.isArray(payload))throw new Error('Information payload must be a JSON object.')
    return {operation:operation as InformationOperation,scope:kind==='project'?{kind:'project',project:chosen!}:{kind:kind as 'personal'|'shared'},payload:payload as Record<string,unknown>}
}
