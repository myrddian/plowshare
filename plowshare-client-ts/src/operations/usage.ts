/** Accounting reports are immutable replacement snapshots. Token/cost values stay decimal strings. */
export const USAGE_REPORTS = ['usage.conversation', 'usage.project', 'usage.agent', 'usage.run', 'usage.orchestration', 'usage.models', 'usage.pools'] as const;
export type UsageReportType = typeof USAGE_REPORTS[number];
export type UsageDimension = 'day' | 'model' | 'pool' | 'agent' | 'operation' | 'project' | 'run';
export interface UsageFilter {
    conversation?: string;
    project?: string;
    agent?: string;
    run?: string;
    orchestration?: string;
    model?: string;
    pool?: string;
    route?: string;
    scope?: 'direct' | 'subtree';
    from?: string;
    to?: string;
    group_by?: readonly UsageDimension[];
    cursor?: string;
    limit?: number;
}
export interface UsagePayloads {
    'usage.conversation': UsageFilter & {
        conversation: string;
    };
    'usage.project': UsageFilter & {
        project: string;
    };
    'usage.agent': UsageFilter & {
        agent: string;
    };
    'usage.run': UsageFilter & {
        run: string;
    };
    'usage.orchestration': UsageFilter & {
        orchestration: string;
    };
    'usage.models': UsageFilter;
    'usage.pools': UsageFilter;
    'usage.calls': UsageFilter & {
        call?: string;
        attempt_cursor?: string;
    };
    'usage.subscribe': UsageFilter & {
        report_type: UsageReportType;
    };
    'usage.unsubscribe': {
        subscription: string;
    };
    'conversation.context.snapshot': {
        conversation: string;
        agent: string;
        measure?: boolean;
    };
    'conversation.context.count': {
        conversation: string;
        agent: string;
    };
}
export const USAGE_FRAMES = [...USAGE_REPORTS, 'usage.calls', 'usage.subscribe', 'usage.unsubscribe', 'conversation.context.count', 'conversation.context.snapshot'] as const;
export interface UsageTotals extends Record<string, unknown> {
    calls: string;
    attempts: string;
    active_calls: string;
    incomplete_attempts: string;
    unknown_cost_attempts: string;
    input_tokens: string;
    output_tokens: string;
    input_tokens_known: string;
    output_tokens_known: string;
    costs: Record<string, string>;
    usage_complete: boolean;
    cost_complete: boolean;
    complete: boolean;
}
export interface UsageHealth extends Record<string, unknown> {
    watermark: string;
    as_of: string;
    capture_enabled: boolean;
    historical_usage: string;
}
export interface UsageReport {
    filters: {
        type: UsageReportType;
        filter: UsageFilter;
    };
    totals: UsageTotals;
    groups: UsageTotals[];
    cursor: string | null;
    health: UsageHealth;
}
export interface UsageInitial {
    subscription: string;
    revision: number;
    filters: UsageReport['filters'];
    report: UsageReport;
}
export interface UsageTransport {
    ask(type: string, payload: unknown): Promise<{
        code: string;
        said?: string;
        payload?: unknown;
    }>;
}
const integer = (v: unknown): v is string => typeof v === 'string' && /^\d+$/.test(v);
const decimal = (v: unknown): v is string => typeof v === 'string' && /^\d+(?:\.\d+)?$/.test(v);
export function usageFields(value: unknown): Record<string, unknown> | undefined { return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined; }
function totals(value: unknown): value is UsageTotals {
    const v = usageFields(value), costs = usageFields(v?.costs);
    return !!v && ['calls', 'attempts', 'active_calls', 'incomplete_attempts', 'unknown_cost_attempts', 'input_tokens', 'output_tokens', 'input_tokens_known', 'output_tokens_known'].every(k => integer(v[k]))
        && ['usage_complete', 'cost_complete', 'complete'].every(k => typeof v[k] === 'boolean')
        && !!costs && Object.values(costs).every(decimal);
}
export function usageReport(value: unknown): UsageReport | undefined {
    const v = usageFields(value), f = usageFields(v?.filters), h = usageFields(v?.health);
    if (!v || !f || !USAGE_REPORTS.includes(f.type as UsageReportType) || !usageFields(f.filter) || !totals(v.totals)
        || !Array.isArray(v.groups) || !v.groups.every(totals) || (v.cursor !== null && typeof v.cursor !== 'string')
        || !h || !integer(h.watermark) || typeof h.as_of !== 'string' || typeof h.capture_enabled !== 'boolean' || typeof h.historical_usage !== 'string')
        return undefined;
    return v as unknown as UsageReport;
}
function initial(value: unknown): UsageInitial | undefined {
    const v = usageFields(value);
    if (!v || JSON.stringify(v.filters) !== JSON.stringify(usageReport(v.report)?.filters) || typeof v.subscription !== 'string' || !v.subscription || !Number.isSafeInteger(v.revision) || (v.revision as number) < 0 || !usageReport(v.report))
        return undefined;
    return v as unknown as UsageInitial;
}
function usageAttempt(value: unknown): boolean {
    const r = usageFields(value);
    return !!r && typeof r.attempt_id === 'string' && Number.isInteger(r.attempt_number) && (r.attempt_number as number) > 0
        && ['input_tokens', 'output_tokens', 'provider_total_tokens', 'cache_read_tokens', 'cache_write_tokens', 'reasoning_tokens'].every(key => r[key] === null || integer(r[key]))
        && typeof r.usage_source === 'string' && typeof r.usage_coverage === 'string' && typeof r.cost_kind === 'string'
        && (r.cost_amount === null || decimal(r.cost_amount)) && (r.cost_currency === null || typeof r.cost_currency === 'string');
}
/** Shared dispatch validation also covers raw CLI requests without introducing a model-facing tool. */
export function usageReply(type: string, value: unknown): boolean {
    if (USAGE_REPORTS.includes(type as UsageReportType))
        return usageReport(value)?.filters.type === type;
    if (type === 'usage.subscribe')
        return !!initial(value);
    const v = usageFields(value);
    if (!v)
        return false;
    if (type === 'usage.unsubscribe')
        return true;
    if (type === 'usage.calls') {
        const f = usageFields(v.filters), h = usageFields(v.health);
        return f?.type === 'usage.calls' && !!usageFields(f.filter) && !!h && integer(h.watermark) && typeof h.capture_enabled === 'boolean'
            && (v.cursor === null || typeof v.cursor === 'string') && (Array.isArray(v.calls) && v.calls.every(item => { const r = usageFields(item); return !!r && typeof r.call_id === 'string' && typeof r.wire_model === 'string' && typeof r.operation === 'string' && typeof r.lifecycle === 'string' && typeof r.attempts_truncated === 'boolean' && Array.isArray(r.attempts) && r.attempts.every(usageAttempt); })
            || typeof v.call === 'string' && Array.isArray(v.attempts) && v.attempts.every(usageAttempt));
    }
    if (type === 'conversation.context.snapshot') return contextSnapshot(value) !== undefined;
    if (type === 'conversation.context.count') {
        const c = usageFields(v.count);
        return typeof v.conversation === 'string' && typeof v.agent === 'string' && v.projection === 'next' && !!c
            && ['MEASURED', 'ESTIMATED', 'UNKNOWN'].includes(String(c.basis)) && (c.tokens === null || integer(c.tokens)) && Array.isArray(c.gaps);
    }
    return false;
}
export class UsageClient {
    private readonly transport: UsageTransport;
    constructor(transport: UsageTransport) { this.transport = transport; }
    async call(type: typeof USAGE_FRAMES[number], payload: unknown = {}): Promise<unknown> {
        if (!(USAGE_FRAMES as readonly string[]).includes(type))
            throw new Error('Unknown usage operation.');
        const answer = await this.transport.ask(type, payload);
        if (answer.code !== 'OK')
            throw new Error(answer.said || `Usage unavailable (${answer.code}).`);
        if (!usageReply(type, answer.payload))
            throw new Error('Usage response is incomplete or unreadable. Previous measurements are retained.');
        const expected = usageFields(payload), received = usageFields(answer.payload);
        if ((type === 'conversation.context.count' || type === 'conversation.context.snapshot') && (expected?.conversation !== received?.conversation || expected?.agent !== received?.agent))
            throw new Error('Context count belongs to another selection.');
        if (USAGE_REPORTS.includes(type as UsageReportType) && !usageMatches(answer.payload as UsageReport, type as UsageReportType, payload as UsageFilter))
            throw new Error('Usage snapshot belongs to another selection.');
        return answer.payload;
    }
    async report(type: UsageReportType, filter: UsageFilter = {}): Promise<UsageReport> { const result = await this.call(type, filter) as UsageReport; if (!usageMatches(result, type, filter))
        throw new Error('Usage snapshot belongs to another selection.'); return result; }
    async subscribe(type: UsageReportType, filter: UsageFilter = {}): Promise<UsageInitial> { const result = await this.call('usage.subscribe', { ...filter, report_type: type }) as UsageInitial; if (!usageMatches(result.report, type, filter)) {
        await this.unsubscribe(result.subscription).catch(() => undefined);
        throw new Error('Usage snapshot belongs to another selection.');
    } return result; }
    async unsubscribe(subscription: string): Promise<void> { await this.call('usage.unsubscribe', { subscription }); }
}
export interface UsageViewState {
    report?: UsageReport;
    subscription?: string | undefined;
    revision: number;
    stale: boolean;
    loading: boolean;
    error?: string | undefined;
}
/** One view owns one socket subscription. Async replies from old scopes/sockets never replace a newer selection. */
export class UsageWatch {
    state: UsageViewState = { revision: -1, stale: true, loading: false };
    private epoch = 0;
    private selection: {
        type: UsageReportType;
        filter: UsageFilter;
    } | undefined;
    private readonly client: UsageClient;
    private readonly changed: (state: UsageViewState) => void;
    private readonly early = new Map<string, unknown>();
    constructor(client: UsageClient, changed: (state: UsageViewState) => void) { this.client = client; this.changed = changed; }
    private emit() { this.changed({ ...this.state }); }
    async open(type: UsageReportType, filter: UsageFilter = {}): Promise<void> {
        const epoch = ++this.epoch, old = this.state.subscription;
        const { cursor: _cursor, ...selectedFilter } = filter;
        const retained = this.state.report && usageMatches(this.state.report, type, selectedFilter) ? this.state.report : undefined;
        this.selection = { type, filter: selectedFilter };
        this.early.clear();
        // Never display another scope's spend while a new selection is loading.
        this.state = { ...(retained ? { report: retained } : {}), revision: -1, stale: true, loading: true };
        this.emit();
        if (old)
            await this.client.unsubscribe(old).catch(() => undefined);
        if (epoch !== this.epoch)
            return;
        try {
            const next = await this.client.subscribe(type, selectedFilter);
            if (epoch !== this.epoch) {
                await this.client.unsubscribe(next.subscription).catch(() => undefined);
                return;
            }
            if (!usageMatches(next.report, type, selectedFilter))
                throw new Error('Usage snapshot belongs to another report.');
            this.state = { report: next.report, subscription: next.subscription, revision: next.revision, stale: false, loading: false };
            this.emit();
            const early = this.early.get(next.subscription);
            this.early.clear();
            if (early)
                this.push(early);
        }
        catch (error) {
            if (epoch === this.epoch) {
                this.state.loading = false;
                this.state.error = error instanceof Error ? error.message : String(error);
                this.emit();
            }
        }
    }
    push(frame: unknown): boolean {
        const envelope = usageFields(frame);
        if (!envelope || !['usage.updated', 'usage.closed'].includes(String(envelope.type)))
            return false;
        if (envelope.protocol_version !== 'plowshare-v1' || envelope.id !== null)
            return true;
        const payload = usageFields(envelope.payload);
        if (!payload || typeof payload.subscription !== 'string')
            return true;
        if (payload.subscription !== this.state.subscription) {
            if (this.state.loading && (this.early.has(payload.subscription) || this.early.size < 8)) {
                const held = usageFields(usageFields(this.early.get(payload.subscription))?.payload);
                if (!held || envelope.type === 'usage.closed' || Number(payload.revision) > Number(held.revision))
                    this.early.set(payload.subscription, frame);
            }
            return true;
        }
        if (envelope.type === 'usage.closed') {
            this.state = { ...this.state, subscription: undefined, stale: true, error: 'Usage access is no longer available.' };
            this.emit();
            return true;
        }
        if (!Number.isSafeInteger(payload.revision) || (payload.revision as number) <= this.state.revision)
            return true;
        const report = usageReport(payload.report);
        if (!report || report.filters.type !== this.selection?.type || !sameUsageFilters(report.filters.filter, this.state.report?.filters.filter ?? {})) {
            this.state.error = 'Unreadable or foreign usage update; refresh to reconcile.';
            this.state.stale = true;
            this.emit();
            return true;
        }
        // Entire replacement; never add streaming counters to durable totals.
        this.state = { ...this.state, report, revision: payload.revision as number, error: undefined, stale: false };
        this.emit();
        return true;
    }
    disconnected() { ++this.epoch; this.state = { ...this.state, subscription: undefined, stale: true, loading: false, error: 'Disconnected. Last usage snapshot may be out of date.' }; this.emit(); }
    async reconnect() { const selected = this.selection; if (selected)
        await this.open(selected.type, selected.filter); }
    async close() { const id = this.state.subscription; ++this.epoch; this.selection = undefined; this.early.clear(); this.state = { revision: -1, stale: true, loading: false }; this.emit(); if (id)
        await this.client.unsubscribe(id).catch(() => undefined); }
}
/** Compare semantic selector values; resolved server defaults are permitted. */
export function usageMatches(report: UsageReport, type: UsageReportType, filter: UsageFilter): boolean {
    return report.filters.type === type && Object.entries(filter).every(([key, value]) => value === undefined || value === null || key === 'cursor' ||
        (key === 'from' || key === 'to' ? Date.parse(String(value)) === Date.parse(String(report.filters.filter[key])) :
            JSON.stringify(value) === JSON.stringify(report.filters.filter[key as keyof UsageFilter])));
}
export function sameUsageFilters(left: UsageFilter, right: UsageFilter): boolean {
    return Object.keys({ ...left, ...right }).every(key => JSON.stringify(left[key as keyof UsageFilter]) === JSON.stringify(right[key as keyof UsageFilter]));
}

/** One-shot ledger response contracts are also used by operation schema discovery. */
export interface UsageAudit {
    readonly filters: { readonly type: 'usage.calls'; readonly filter: UsageFilter };
    readonly calls: readonly Readonly<Record<string, unknown>>[];
    readonly cursor: string | null;
    readonly health: UsageHealth;
}
export interface UsageAttempts {
    readonly filters: UsageAudit['filters'];
    readonly call: string;
    readonly attempts: readonly Readonly<Record<string, unknown>>[];
    readonly cursor: string | null;
    readonly health: UsageHealth;
}
export interface UsageReplies {
    'conversation.context.snapshot': ContextSnapshot;
    'usage.conversation': UsageReport;
    'usage.project': UsageReport;
    'usage.agent': UsageReport;
    'usage.run': UsageReport;
    'usage.orchestration': UsageReport;
    'usage.models': UsageReport;
    'usage.pools': UsageReport;
    'usage.calls': UsageAudit | UsageAttempts;
}
export const USAGE_OPERATIONS = [...USAGE_REPORTS, 'usage.calls'] as const;

export interface ProjectionMessage {
    role: 'system' | 'user' | 'assistant' | 'tool';
    parts: readonly ({type: 'text'; text: string} | {type: 'image'; uid: string; omitted: true})[];
    tool_calls: readonly {id: string; name: string; arguments: string}[];
    tool_call_id: string | null;
}
export interface ContextSnapshot {
    conversation: string;
    agent: string;
    projection: 'next';
    captured_at: string;
    model: string;
    sampling: Readonly<Record<string, unknown>>;
    messages: readonly ProjectionMessage[];
    tools: readonly {name: string; description: string; parameters: Readonly<Record<string, unknown>>}[];
    count: {basis: 'MEASURED' | 'ESTIMATED' | 'UNKNOWN'; tokens: string | null; gaps: readonly string[]} | null;
}
/** Refuse incomplete or foreign snapshots before displaying model-facing content. */
export function contextSnapshot(value: unknown): ContextSnapshot | undefined {
    const v = usageFields(value), c = usageFields(v?.count);
    if (!v || typeof v.conversation !== 'string' || typeof v.agent !== 'string' || v.projection !== 'next'
        || typeof v.captured_at !== 'string' || !Number.isFinite(Date.parse(v.captured_at)) || typeof v.model !== 'string' || !usageFields(v.sampling)
        || !Array.isArray(v.messages) || !v.messages.every(item => { const m = usageFields(item); return !!m
            && ['system','user','assistant','tool'].includes(String(m.role)) && (m.tool_call_id === null || typeof m.tool_call_id === 'string')
            && Array.isArray(m.parts) && m.parts.every(item => {const p = usageFields(item); return !!p && (p.type === 'text' && typeof p.text === 'string' || p.type === 'image' && typeof p.uid === 'string' && p.omitted === true);})
            && Array.isArray(m.tool_calls) && m.tool_calls.every(item => {const t = usageFields(item); return !!t && ['id','name','arguments'].every(k => typeof t[k] === 'string');}); })
        || !Array.isArray(v.tools) || !v.tools.every(item => {const t = usageFields(item);return !!t && typeof t.name === 'string' && typeof t.description === 'string' && !!usageFields(t.parameters);})
        || !(v.count === null || !!c && ['MEASURED','ESTIMATED','UNKNOWN'].includes(String(c.basis)) && (c.tokens === null || integer(c.tokens)) && Array.isArray(c.gaps) && c.gaps.every(g => typeof g === 'string')))
        return undefined;
    return v as unknown as ContextSnapshot;
}
