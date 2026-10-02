/** Validate dispatch structure; the server owns grants, UUID references and report semantics. */
export function informationPayloadProblem(type: string, body: Record<string, unknown>, required: readonly string[]): string | undefined {
    for (const key of required) if (!(key in body)) return `${type} needs ${key}`
    for (const [key,value] of Object.entries(body)) {
        if (key === 'scope') {
            if (!value || typeof value !== 'object' || Array.isArray(value)) return 'scope must be a personal, project or shared selection'
            const scope = value as Record<string, unknown>
            if (!['personal','project','shared'].includes(String(scope['kind'])) || (scope['kind'] === 'project' && (typeof scope['project'] !== 'string' || !scope['project'].trim()))) return 'select a valid information scope'
            if (Object.keys(scope).some(key => !['kind','project','includeShared'].includes(key)) || ('includeShared' in scope && typeof scope['includeShared'] !== 'boolean') || (scope['kind'] !== 'project' && 'project' in scope)) return 'invalid information scope fields'
        } else if (['start','end','offset','limit','after','maxModelCalls'].includes(key)) {
            const minimum = ['limit','maxModelCalls','end'].includes(key) ? 1 : 0
            if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum || value > 2147483647) return `${key} must be an integer between ${minimum} and 2147483647`
        } else if (['inputs','objectives','scopeChanges'].includes(key) || (key === 'evidence' && type === 'information.record.report')) {
            if (!Array.isArray(value) || value.some(item => typeof item !== 'string' || !item.trim())) return `${key} must be an array of nonblank strings`
        } else if (['findings','reviews'].includes(key)) {
            if (!Array.isArray(value) || value.some(item => !item || typeof item !== 'object' || Array.isArray(item))) return `${key} must be an array of objects`
        } else if (typeof value !== 'string' || !value.trim()) return `${key} must be nonblank text`
    }
    if (['information.status','information.retry'].includes(type)) {
        if (Number('revision' in body) + Number('acquisition' in body) !== 1) return `${type} needs exactly one of revision or acquisition`
        if (type === 'information.retry' && 'revision' in body && !('requestId' in body)) return 'revision retry needs a stable requestId'
    }
    if (type === 'information.evidence.record' && Number(body['end']) <= Number(body['start'])) return 'evidence end must follow start'
    return undefined
}
