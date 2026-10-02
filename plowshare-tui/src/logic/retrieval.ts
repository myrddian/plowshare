
export type SearchMode = 'lexical' | 'semantic' | 'hybrid'
export interface RetrievalCommand {
    readonly type: 'conversation.search' | 'memory.navigate'
    readonly payload: Readonly<Record<string, unknown>>
    readonly json: boolean
}
export interface SearchHit {
    readonly conversationId: string
    readonly ordinal: number
    readonly turnOrdinal: number
    readonly kind: string
    readonly snippet: string
    readonly length: number
    readonly supersededBy: number | null
    readonly handle: string | null
    readonly recordedAt: string | null
    readonly rank: number
    readonly evidence?: { readonly retrievedBy: string; readonly passagePosition: number | null; readonly sourceRevision: string }
}
export interface Coverage {
    readonly eligible: number; readonly indexed: number; readonly passages: number
    readonly pending: number; readonly failed: number; readonly stale?: number
}
export interface SearchResult {
    readonly hits: readonly SearchHit[]
    readonly total: number; readonly offset: number; readonly limit: number
    readonly reach: { readonly searched: number; readonly ejected: number; readonly recordedOnly: number }
    readonly retrieval?: {
        readonly effectiveMode: SearchMode; readonly complete: boolean; readonly truncated: boolean
        readonly totalMeaning: string; readonly snapshot: string | null; readonly fallback: string | null
        readonly coverage: Coverage | null; readonly queryEmbeddingCalls: number
    }
}
export interface NavigationResult {
    readonly level: string; readonly ids: readonly string[]; readonly text: string
    readonly complete: boolean; readonly modelCalls: number
    readonly retrieval?: {
        readonly tier: string; readonly globalFallback: boolean; readonly seedMode: string
        readonly coverage: Coverage | null; readonly fallback: string | null; readonly queryEmbeddingCalls: number
    }
}
export const RETRIEVAL_HELP = [
    'conversation search [--mode lexical|semantic|hybrid] [--project NAME|--global] [--offset N --snapshot ID] [--limit N] [--json] QUESTION',
    'memory navigate [--project NAME|--global] [--json] QUESTION',
    'Search defaults to hybrid; omitting project uses the current project, or global outside a project. Explicit search scope never includes other tiers.',
    'Semantic/hybrid search spends a system query embedding; lexical needs no model. Coverage and effective mode accompany results. Search never automatically starts navigation.',
    'Navigation spends query embedding and model calls under a separate system allowance; project navigation may fall back to global, labelled in results.',
    'Open search evidence with /trajectory CONVERSATION (or conversation.trajectory); internal conversation_trajectory accepts conversation plus handle for a historical tool result.',
    'Semantic/hybrid total is a bounded snapshot count. Repeat question, mode and scope with returned snapshot for subsequent offsets. Snapshots expire after five minutes.',
] as const

/** One parser and operation contract for direct CLI and signed-in TUI commands. */
export function retrievalCommand(args: readonly string[], project?: string): RetrievalCommand {
    const [group, verb, ...rest] = args
    const search = group === 'conversation' && verb === 'search'
    if (!search && !(group === 'memory' && verb === 'navigate')) throw new Error('Expected conversation search or memory navigate')
    let scope = project
    let scoped = false
    let mode: SearchMode = 'hybrid'
    let offset = 0
    let limit = 10
    let snapshot: string | undefined
    let json = false
    let literal = false
    const question: string[] = []
    for (let i = 0; i < rest.length; i++) {
        const word = rest[i]!
        if (literal) { question.push(word); continue }
        if (word === '--') { literal = true; continue }
        if (word === '--json') { json = true; continue }
        if (word === '--global') {
            if (scoped) throw new Error('Choose one scope: --project or --global')
            scope = undefined; scoped = true; continue
        }
        if (['--project', '--mode', '--offset', '--limit', '--snapshot'].includes(word)) {
            const value = rest[++i]
            if (value === undefined || value.trim() === '' || value.startsWith('--')) throw new Error(`${word} needs a value`)
            if (word === '--project') {
                if (scoped) throw new Error('Choose one scope: --project or --global')
                scope = value; scoped = true
            } else {
                if (!search) throw new Error(`${word} is only for conversation search`)
                if (word === '--mode') {
                    if (value !== 'lexical' && value !== 'semantic' && value !== 'hybrid') throw new Error('Mode must be lexical, semantic or hybrid')
                    mode = value
                } else if (word === '--snapshot') snapshot = value
                else {
                    if (!/^\d+$/.test(value) || !Number.isSafeInteger(Number(value))) throw new Error(`${word} must be a nonnegative integer`)
                    if (word === '--offset') offset = Number(value)
                    else {
                        limit = Number(value)
                        if (limit < 1 || limit > 100) throw new Error('--limit must be between 1 and 100')
                    }
                }
            }
            continue
        }
        if (word.startsWith('--')) throw new Error(`Unknown option ${word}`)
        question.push(word)
    }
    const text = question.join(' ').trim()
    if (text === '') throw new Error('A retrieval needs a question')
    if (search && mode !== 'lexical' && offset > 0 && snapshot === undefined) throw new Error('Further semantic/hybrid pages need --snapshot from the first page')
    if (mode === 'lexical' && snapshot !== undefined) throw new Error('Lexical search uses offset paging without snapshots')
    return { type: search ? 'conversation.search' : 'memory.navigate', json,
        payload: { ...(scope === undefined ? {} : { project: scope }),
            ...(search ? { q: text, mode, offset, limit, ...(snapshot === undefined ? {} : { snapshot }) } : { question: text }) } }
}

/** Slash commands use the existing group convention; quoted questions retain spaces. */
export function retrievalWords(line: string): string[] {
    const words: string[] = []
    const pattern = /"([^"\\]*(?:\\.[^"\\]*)*)"|'([^']*)'|(\S+)/g
    for (const match of line.matchAll(pattern)) words.push(match[1]?.replace(/\\(["\\])/g, '$1') ?? match[2] ?? match[3]!)
    return words
}
export interface RetrievalConnection { ask(type: string, payload: Readonly<Record<string, unknown>>): Promise<{ readonly code: string; readonly said?: string; readonly payload?: unknown }> }
export async function retrieve(connection: RetrievalConnection, command: RetrievalCommand): Promise<SearchResult | NavigationResult> {
    const outcome = await connection.ask(command.type, command.payload)
    if (outcome.code !== 'OK') throw new Error(outcome.said ?? `Retrieval refused: ${outcome.code}`)
    const value = outcome.payload
    if (typeof value !== 'object' || value === null) throw new Error('Server returned no retrieval result')
    if (command.type === 'conversation.search') {
        const result = value as SearchResult
        if (!Array.isArray(result.hits) || !Number.isInteger(result.total) || result.reach === undefined
            || result.hits.some(h => typeof h.conversationId !== 'string' || !Number.isInteger(h.ordinal) || typeof h.snippet !== 'string'))
            throw new Error('Server returned an invalid search result')
        return result
    }
    const result = value as NavigationResult
    if (typeof result.text !== 'string' || typeof result.complete !== 'boolean' || !Array.isArray(result.ids) || !Number.isInteger(result.modelCalls))
        throw new Error('Server returned an invalid navigation result')
    return result
}
export function describeRetrieval(result: SearchResult | NavigationResult): string {
    if ('hits' in result) {
        const r = result.retrieval
        const header = `Search mode: ${r?.effectiveMode ?? 'lexical'}; ${result.total} ${r?.totalMeaning ?? 'exact lexical matches'}; offset ${result.offset}; complete=${r?.complete ?? true}`
        const coverage = r?.coverage
        return [header,
            `Retained: ${result.reach.searched}; ejected: ${result.reach.ejected}; recorded only: ${result.reach.recordedOnly}`,
            ...(coverage == null ? [] : [`Index: ${coverage.indexed}/${coverage.eligible} entries; ${coverage.passages} passages; pending ${coverage.pending}; failed ${coverage.failed}`]),
            ...(r?.fallback == null ? [] : [`Fallback: ${r.fallback}`]),
            ...(r?.truncated ? ['Candidate set truncated.'] : []),
            ...result.hits.map(h => `${h.conversationId}:${h.ordinal} turn ${h.turnOrdinal} [${h.kind}]${h.supersededBy === null ? '' : ` superseded by ${h.supersededBy}`} (${h.length} characters)${h.evidence === undefined ? '' : ` via ${h.evidence.retrievedBy}${h.evidence.passagePosition === null ? '' : ` passage ${h.evidence.passagePosition}`}`}\n> ${h.snippet.replace(/\n/g, '\n> ')}\nOpen: /trajectory ${h.conversationId}${h.handle === null ? '' : `; historical tool handle ${h.handle}`}`),
            ...(r?.snapshot == null ? [] : [`Snapshot: ${r.snapshot} (reuse with the same question, mode and scope)`]),
        ].join('\n\n')
    }
    const r = result.retrieval
    return [`Reached ${result.level}; complete=${result.complete}; model calls=${result.modelCalls}; query embedding calls=${r?.queryEmbeddingCalls ?? 0}`,
        ...(r === undefined ? [] : [`Tier: ${r.tier}${r.globalFallback ? ' (global fallback)' : ''}; seeds: ${r.seedMode}`]),
        ...(r?.coverage == null ? [] : [`Digest index: ${r.coverage.indexed}/${r.coverage.eligible}; pending ${r.coverage.pending}; failed ${r.coverage.failed}; stale ${r.coverage.stale ?? 0}`]),
        ...(r?.fallback == null ? [] : [`Seed fallback: ${r.fallback}`]),
        `Sources: ${result.ids.join(', ')}`, `> ${result.text.replace(/\n/g, '\n> ')}`].join('\n\n')
}
