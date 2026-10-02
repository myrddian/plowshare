import { USAGE_REPORTS, usageFields, usageMatches, type UsageFilter, type UsageReportType, type UsageViewState } from '../../../plowshare-client-ts/src/operations/usage.ts';
import { DEFAULT_REFERENCE, referenceCost, referencePrice, REFERENCE_PRICES, type ReferencePrice } from '../../../plowshare-client-ts/src/operations/reference-cost.ts';
import { usageText, usageRange } from '../../../plowshare-client-ts/src/operations/usage-presentation.ts';
export interface UsagePanel {
    update(state: UsageViewState): void;
    destroy(): void;
}
export interface UsagePanelOptions {
    select(type: UsageReportType, filter: UsageFilter): Promise<void>;
    read(type: 'usage.calls' | UsageReportType | 'conversation.context.count', payload: unknown): Promise<unknown>;
    conversation?: () => string;
    agent?: () => string;
    project?: string | null;
    storage?: Pick<Storage, 'getItem' | 'setItem'>;
}
function node<K extends keyof HTMLElementTagNameMap>(tag: K, text?: string): HTMLElementTagNameMap[K] {
    const result = document.createElement(tag);
    if (text !== undefined)
        result.textContent = text;
    return result;
}
function selectControl(entries: readonly (readonly [
    string,
    string
])[], value: string): HTMLSelectElement {
    const control = node('select');
    for (const [key, label] of entries) {
        const option = node('option', label);
        option.value = key;
        control.append(option);
    }
    control.value = value;
    return control;
}
function label(text: string, control: HTMLElement): HTMLLabelElement { const result = node('label', text); result.append(control); return result; }
function button(text: string, click: () => void): HTMLButtonElement { const result = node('button', text); result.type = 'button'; result.addEventListener('click', click); return result; }
function disclosure(title: string): HTMLDetailsElement { const result = node('details'); result.append(node('summary', title)); return result; }
const EMPTY = { input_tokens: '0', output_tokens: '0', input_tokens_known: '0', output_tokens_known: '0', attempts: '0', active_calls: '0', usage_complete: true } as never;
/** Shared desktop/console widget. Server values reach textContent, never the HTML parser. */
export function mountUsagePanel(root: HTMLElement, options: UsagePanelOptions): UsagePanel {
    root.replaceChildren();
    root.classList.add('usage-panel');
    const style = node('style', USAGE_STYLE);
    root.append(style);
    const type = selectControl(USAGE_REPORTS.map(type => [type, type === 'usage.models' ? 'Accessible queries · by model' : type === 'usage.pools' ? 'Fleet pools (administrator)' : type.slice(6)]), options.conversation?.() ? 'usage.conversation' : 'usage.models');
    const target = node('input');
    target.placeholder = 'Conversation, project, agent or run';
    const days = selectControl([['1', 'Today (UTC)'], ['7', '7 days through today'], ['30', '30 days through today'], ['365', '365 days through today']], '30');
    const scope = selectControl([['subtree', 'Include descendants'], ['direct', 'Direct only']], 'subtree');
    const group = selectControl([['model', 'Model'], ['agent', 'Agent'], ['run', 'Agent runs'], ['operation', 'Operation'], ['pool', 'Pool'], ['project', 'Project'], ['day', 'Day']], 'model');
    const preset = selectControl([...REFERENCE_PRICES.map(p => [p.id, p.label] as const), ['custom', 'Custom rates / Azure deployment']], DEFAULT_REFERENCE.id);
    const input = node('input');
    input.value = DEFAULT_REFERENCE.input;
    input.inputMode = 'decimal';
    const output = node('input');
    output.value = DEFAULT_REFERENCE.output;
    output.inputMode = 'decimal';
    const custom = node('div');
    custom.hidden = true;
    custom.append(label('Input / million', input), label('Output / million', output), node('p', 'Custom USD rates, including negotiated Azure pricing. Applies only to this comparison.'));
    const reportBody = node('div'), errorBody = node('p');
    errorBody.setAttribute('role', 'alert');
    const auditBody = node('div'), countBody = node('p');
    countBody.setAttribute('role', 'status');
    const countConversation = node('input');
    countConversation.value = options.conversation?.() ?? '';
    const countAgent = node('input');
    countAgent.value = options.agent?.() ?? '';
    const controls = node('div');
    controls.className = 'usage-controls';
    controls.append(label('Report', type), label('Target', target), label('Range', days), label('Accounting scope', scope), label('Breakdown', group), button('Refresh usage', () => { void select(); }));
    const references = node('div');
    references.className = 'usage-reference-controls';
    references.append(label('Reference provider / model', preset), custom);
    const more = button('Next breakdown page', () => { void nextGroups(); });
    more.hidden = true;
    const auditNext = button('Next call page', () => { void audit(true); });
    auditNext.hidden = true;
    const auditDetails = disclosure('Call and attempt audit');
    auditDetails.append(button('Read call details', () => { void audit(); }), auditNext, auditBody);
    const context = disclosure('Current context · separate from spend');
    context.append(node('p', 'Counts the selected agent’s next ordinary conversation projection. Does not book tokens or costs; drafts and transient forced-tool prompts are excluded.'), label('Conversation', countConversation), label('Agent', countAgent), button('Count next projection', () => { void count(); }), countBody);
    root.append(controls, node('p', 'All recorded attempts in the selected authorized scope and date range. Descendant spend is inclusive; do not add it to direct spend.'), references, errorBody, reportBody, more, auditDetails, context);
    // Stable selectors for platform integration and accessibility tests.
    for (const [name, element] of Object.entries({ type, target, days, scope, group, price: preset, input, output, custom, report: reportBody, more, 'audit-next': auditNext, 'count-conversation': countConversation, 'count-agent': countAgent, 'count-result': countBody }))
        element.setAttribute('data-usage-' + name, '');
    context.querySelector('button')!.setAttribute('data-usage-count', '');
    let state: UsageViewState = { revision: -1, stale: true, loading: false }, price: ReferencePrice = DEFAULT_REFERENCE;
    let selection: {
        type: UsageReportType;
        filter: UsageFilter;
    } | undefined;
    let lastSnapshot = '';
    let closed = false, epoch = 0, page = 0, auditCursor: string | null = null, groupCursor: string | null = null;
    try {
        const saved = options.storage?.getItem('plowshare.reference-price.v1');
        if (saved) {
            const p = JSON.parse(saved) as ReferencePrice;
            referenceCost(EMPTY, p);
            price = p.id === 'custom' ? { id: 'custom', label: 'Custom reference', input: p.input, output: p.output, currency: 'USD', checked: /^\d{4}-\d{2}-\d{2}$/.test(p.checked) ? p.checked : 'unknown' } : referencePrice(p.id);
            preset.value = price.id;
            input.value = price.input;
            output.value = price.output;
        }
    }
    catch { /* Damaged or unavailable preferences use the dated default. */ }
    custom.hidden = preset.value !== 'custom';
    function error(reason: unknown) { if (!closed)
        errorBody.textContent = reason instanceof Error ? reason.message : String(reason); }
    function render() {
        if (closed)
            return;
        reportBody.replaceChildren(node('p', state.loading ? 'Reading usage…' : state.stale ? 'Last snapshot · refresh or reconnect to reconcile' : 'Live usage snapshot'));
        if (state.error)
            reportBody.append(node('p', state.error));
        if (!state.report) {
            reportBody.append(node('p', 'No usage measurement loaded. Select a scope and refresh.'));
            return;
        }
        // One pure presenter defines the wording, coverage and exact decimals for UI and TUI.
        for (const line of usageText(state.report, price).split('\n')) {
            const paragraph = node('p', line);
            if (line.startsWith('Reference ' + price.label + ':')) {
                paragraph.className = 'usage-reference-total';
                paragraph.setAttribute('aria-label', 'Reference cost comparison');
            }
            reportBody.append(paragraph);
        }
        if (price.source) {
            const source = node('a', 'Pricing source');
            source.href = price.source;
            source.target = '_blank';
            source.rel = 'noreferrer';
            reportBody.append(source);
        }
        const h = state.report.health;
        reportBody.append(node('p', `Tracking began ${String(h.tracking_started_at ?? 'not recorded')} · last projection ${String(h.last_projected_at ?? 'not recorded')} · unpriced attempts ${state.report.totals.unknown_cost_attempts}`));
        for (const key of ['journal_problem', 'projection_problem', 'projection_conflict', 'lost_terminal_events'])
            if (h[key] !== undefined)
                reportBody.append(node('p', `${key.replace(/_/g, ' ')}: ${String(h[key])}`));
        reportBody.append(node('p', 'Booked estimates retain prices captured at admission. Included, zero-rate and unpriced modes are shown in attempt details.'));
        if (state.report.filters.filter.group_by?.includes('run'))
            tree();
        more.hidden = !groupCursor;
    }
    function tree() {
        const section = node('section');
        section.setAttribute('aria-label', 'Agent run tree');
        section.append(node('h3', 'Agent run tree'), node('p', 'Rows are direct contributions. Open a run to read its inclusive subtree. No direct row on this page does not mean zero historical spend.'));
        const nodes = new Map<string, {
            parent: string | undefined;
            agent: unknown;
        }>();
        for (const row of state.report!.groups) {
            if (typeof row.run !== 'string')
                continue;
            const path = [row.run, ...(Array.isArray(row.ancestor_runs) ? row.ancestor_runs.filter((id): id is string => typeof id === 'string') : [])];
            if (new Set(path).size !== path.length)
                continue;
            path.forEach((id, index) => nodes.set(id, { parent: path[index + 1], agent: index === 0 ? row.agent : nodes.get(id)?.agent }));
        }
        function visit(parent: string | undefined, depth = 0): HTMLUListElement {
            const list = node('ul');
            if (depth > 256)
                return list;
            for (const [id, entry] of nodes)
                if (entry.parent === parent) {
                    const item = node('li'), open = button(`${String(entry.agent ?? 'Ancestor run')} · ${id}`, () => { type.value = 'usage.run'; target.value = id; target.disabled = false; scope.value = 'subtree'; void select(); });
                    open.dataset.usageRun = id;
                    item.append(open, visit(id, depth + 1));
                    list.append(item);
                }
            return list;
        }
        section.append(visit(undefined));
        if (state.report!.cursor)
            section.append(node('p', 'Partial tree page; more groups are available.'));
        reportBody.append(section);
    }
    function query(): {
        type: UsageReportType;
        filter: UsageFilter;
    } {
        const selected = type.value as UsageReportType, key = selected.slice(6);
        return { type: selected, filter: { ...usageRange(Number(days.value)), scope: scope.value as 'direct' | 'subtree', group_by: group.value === 'run' ? ['run', 'agent'] : [group.value as never], ...(['conversation', 'project', 'agent', 'run', 'orchestration'].includes(key) ? { [key]: target.value.trim() } : {}), limit: 100 } };
    }
    async function select() {
        ++epoch;
        lastSnapshot = '';
        ++page;
        auditCursor = null;
        groupCursor = null;
        more.hidden = true;
        auditNext.hidden = true;
        auditBody.replaceChildren();
        countBody.textContent = '';
        errorBody.textContent = '';
        selection = query();
        const retained = state.report && usageMatches(state.report, selection.type, selection.filter) ? state.report : undefined;
        state = { ...(retained ? { report: retained } : {}), revision: -1, stale: true, loading: true };
        render();
        try {
            await options.select(selection.type, selection.filter);
        }
        catch (reason) {
            error(reason);
        }
    }
    function setTarget() {
        const key = type.value.slice(6);
        target.disabled = !['conversation', 'project', 'agent', 'run', 'orchestration'].includes(key);
        target.setAttribute('aria-label', key + ' target');
        target.value = '';
        if (key === 'conversation')
            target.value = options.conversation?.() ?? '';
        if (key === 'project')
            target.value = options.project ?? '';
        if (key === 'agent')
            target.value = options.agent?.() ?? '';
    }
    setTarget();
    for (const control of [type, target, days, scope, group])
        control.addEventListener('change', () => { if (control === type)
            setTarget(); void select(); });
    function comparison() {
        custom.hidden = preset.value !== 'custom';
        try {
            const next = preset.value === 'custom' ? { id: 'custom', label: 'Custom reference', input: input.value, output: output.value, currency: 'USD', checked: new Date().toISOString().slice(0, 10) } : referencePrice(preset.value);
            referenceCost(state.report?.totals ?? EMPTY, next);
            price = next;
            errorBody.textContent = '';
            try {
                options.storage?.setItem('plowshare.reference-price.v1', JSON.stringify(price));
            }
            catch { /* Preference persistence does not prevent comparison. */ }
            render();
        }
        catch (reason) {
            error(reason);
        }
    }
    for (const control of [preset, input, output])
        control.addEventListener('change', comparison);
    async function audit(next = false) {
        const stamp = epoch, resolved = state.report?.filters.filter;
        const { cursor: _groupCursor, ...filter } = resolved ?? {};
        try {
            if (!resolved)
                throw new Error('Load a usage report first.');
            const payload = usageFields(await options.read('usage.calls', { ...filter, ...(next && auditCursor ? { cursor: auditCursor } : {}), limit: 20 }))!;
            if (closed || stamp !== epoch)
                return;
            auditCursor = typeof payload.cursor === 'string' ? payload.cursor : null;
            auditNext.hidden = !auditCursor;
            auditBody.replaceChildren();
            for (const item of payload.calls as Record<string, unknown>[]) {
                const details = disclosure(`${String(item.agent_name ?? 'No agent')} · ${String(item.wire_model)} · run ${String(item.run_id ?? 'none')} · ${String(item.operation)} · ${String(item.lifecycle)}`), body = node('pre', JSON.stringify(item, null, 2));
                details.append(body);
                if (item.attempts_truncated) {
                    let cursor = item.attempt_cursor;
                    const moreAttempts = button('Next attempt page', () => {
                        void (async () => {
                            try {
                                const result = usageFields(await options.read('usage.calls', { ...filter, call: item.call_id, attempt_cursor: cursor, limit: 20 }));
                                if (closed || stamp !== epoch)
                                    return;
                                body.textContent = JSON.stringify(result, null, 2);
                                cursor = result?.cursor;
                                moreAttempts.hidden = !cursor;
                            }
                            catch (reason) {
                                error(reason);
                            }
                        })();
                    });
                    details.append(moreAttempts);
                }
                auditBody.append(details);
            }
        }
        catch (reason) {
            error(reason);
        }
    }
    async function nextGroups() {
        if (!state.report || !groupCursor)
            return;
        const stamp = epoch, nextPage = ++page;
        try {
            const report = await options.read(state.report.filters.type, { ...state.report.filters.filter, cursor: groupCursor }) as NonNullable<UsageViewState['report']>;
            if (closed || stamp !== epoch || nextPage !== page)
                return;
            groupCursor = report.cursor;
            state = { ...state, report };
            render();
        }
        catch (reason) {
            error(reason);
        }
    }
    async function count() {
        const stamp = epoch;
        try {
            const conversation = countConversation.value.trim() || (type.value === 'usage.conversation' ? target.value : ''), agent = countAgent.value.trim() || (type.value === 'usage.agent' ? target.value : '');
            if (!conversation || !agent)
                throw new Error('Enter a conversation and agent to count its next projection.');
            const result = usageFields(await options.read('conversation.context.count', { conversation, agent })), count = usageFields(result?.count);
            if (closed || stamp !== epoch)
                return;
            countBody.textContent = `${String(count?.tokens ?? 'Unknown')} tokens · ${String(count?.basis)} · ${String(count?.model ?? 'no model')} · gaps ${JSON.stringify(count?.gaps)}`;
        }
        catch (reason) {
            error(reason);
        }
    }
    void select();
    return {
        update(next) {
            if (closed || next.report && selection && !usageMatches(next.report, selection.type, selection.filter)) return;
            // Chat progress also emits desktop state. Preserve a paged breakdown until usage actually changes.
            const signature = JSON.stringify([next.subscription, next.revision, next.stale, next.loading, next.error, next.report?.health.watermark, next.report?.health.as_of]);
            if (signature === lastSnapshot) return;
            lastSnapshot = signature;
            state = next;
            ++page;
            groupCursor = next.report?.cursor ?? null;
            render();
        },
        destroy() { closed = true; ++epoch; root.replaceChildren(); },
    };
}
const USAGE_STYLE = `
.usage-panel { overflow:auto; padding:1rem; font-size:.85rem; line-height:1.5; }
.usage-panel * { box-sizing:border-box; }
.usage-panel .usage-controls,.usage-panel .usage-reference-controls { display:flex; flex-wrap:wrap; gap:.65rem; align-items:end; }
.usage-panel label { display:flex; flex-direction:column; gap:.2rem; min-width:0; max-width:100%; }
.usage-panel input,.usage-panel select { color:inherit; background:transparent; border:1px solid #8886; border-radius:5px; padding:.4rem; max-width:100%; }
.usage-panel option { color:CanvasText; background:Canvas; }
.usage-panel button { color:inherit; background:#8882; border:1px solid #8886; border-radius:5px; padding:.4rem .6rem; cursor:pointer; }
.usage-panel section { margin-block:1.2rem; border-top:1px solid #8884; padding-top:.6rem; }
.usage-panel h3 { margin:.2rem 0 .6rem; font-size:1rem; }
.usage-panel p { overflow-wrap:anywhere; }
.usage-panel .usage-reference-total { font-size:1.2rem; font-weight:600; overflow-wrap:anywhere; padding-block:.7rem; border-block:1px solid #8885; }
.usage-panel details { margin:.7rem 0; }
.usage-panel summary { cursor:pointer; overflow-wrap:anywhere; }
.usage-panel pre { overflow:auto; max-height:24rem; font-size:.75rem; }
.usage-panel [role=alert] { color:#c95839; }
.usage-panel [aria-label="Agent run tree"] ul { padding-left:.8rem; list-style:none; border-left:1px solid #8886; }
.usage-panel [data-usage-run] { text-align:left; overflow-wrap:anywhere; max-width:100%; }
.usage-panel [hidden] { display:none !important; }
`;
