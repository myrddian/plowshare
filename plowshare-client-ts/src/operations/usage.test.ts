import { describe, expect, it } from 'vitest';
import { UsageClient, UsageWatch, usageReport, usageReply, type UsageReport } from './usage.ts';
import { referenceCost, REFERENCE_PRICES } from './reference-cost.ts';
import { usageCommand, runUsage } from './usage-command.ts';
import { usageRange, usageText } from './usage-presentation.ts';
function report(conversation = 'root', input = '1000000'): UsageReport {
    return { filters: { type: 'usage.conversation', filter: { conversation, scope: 'subtree', ...usageRange(), group_by: ['run', 'agent'] } }, totals: { calls: '2', attempts: '3', active_calls: '0', incomplete_attempts: '0', unknown_cost_attempts: '0', input_tokens: input, output_tokens: '500000', input_tokens_known: '3', output_tokens_known: '3', costs: { EUR: '0.0012' }, usage_complete: true, cost_complete: true, complete: true }, groups: [], cursor: null, health: { watermark: '12', as_of: '2026-10-02T01:00:00Z', capture_enabled: true, historical_usage: 'not_imported' } };
}
const initial = (subscription: string, value = report()) => ({ subscription, revision: 0, filters: value.filters, report: value });
const update = (subscription: string, revision: number, value = report()) => ({ id: null, type: 'usage.updated', protocol_version: 'plowshare-v1', payload: { subscription, revision, report: value } });
describe('reference comparison preserves recorded accounting', () => {
    it('keeps sub-cent and signed-long precision without adding cache/reasoning subsets', () => {
        const row = report('root', '9223372036854775807').totals, before = structuredClone(row);
        Object.assign(row, { cache_read_tokens: '1000000', reasoning_tokens: '1000000' });
        expect(referenceCost(row).amount).toBe('3689348814742.7103228');
        expect(row.costs).toEqual(before.costs);
        expect(referenceCost({ ...row, input_tokens: '1', output_tokens: '1' }).amount).toBe('0.000002');
        expect(referenceCost(row, REFERENCE_PRICES[1]).amount).toBe('18446744073713.551614');
    });
    it('distinguishes no attempts, unknown and partial known quantities', () => {
        const row = report().totals;
        expect(referenceCost({ ...row, input_tokens: '0', output_tokens: '0', input_tokens_known: '0', output_tokens_known: '0', usage_complete: false }).amount).toBeUndefined();
        expect(referenceCost({ ...row, input_tokens: '0', output_tokens: '0', input_tokens_known: '0', output_tokens_known: '0', attempts: '0' }).amount).toBe('0');
        expect(referenceCost({ ...row, active_calls: '1' }).complete).toBe(false);
        expect(referenceCost({ ...row, incomplete_attempts: '1', usage_complete: false }).complete).toBe(false);
        expect(referenceCost(row, { ...REFERENCE_PRICES[0]!, input: '0', output: '0' }).amount).toBe('0');
        for (const input of ['-1', 'NaN', '1e2', '.5', '1.0000001'])
            expect(() => referenceCost(row, { ...REFERENCE_PRICES[0]!, input })).toThrow();
    });
    it('dates every preset and explains coverage consistently for text and markup', () => {
        expect(REFERENCE_PRICES.every(p => p.checked === '2026-10-02' && p.source?.startsWith('https://'))).toBe(true);
        const value = report();
        value.totals.usage_complete = false;
        const text = usageText(value);
        for (const output of [text]) {
            expect(output).toContain('USD 1.2');
            expect(output).toContain('EUR 0.0012');
            expect(output).toContain('partial known subtotal');
            expect(output).toContain('Recorded costs are unchanged.');
        }
        expect(usageRange(30, new Date('2026-10-02T21:30:00Z'))).toEqual({ from: '2026-09-03T00:00:00.000Z', to: '2026-10-03T00:00:00.000Z' });
    });
});
describe('snapshot lifecycle on the existing authenticated socket', () => {
    it('replaces snapshots, ignores duplicates/foreign subscriptions and resubscribes without inference', async () => {
        const calls: string[] = [], client = new UsageClient({ async ask(type, payload) { calls.push(type); if (type === 'usage.unsubscribe')
                return { code: 'OK', payload: {} }; const v = report((payload as {
                conversation: string;
            }).conversation); return { code: 'OK', payload: initial('s' + calls.length, v) }; } });
        const watch = new UsageWatch(client, () => { });
        await watch.open('usage.conversation', { conversation: 'root' });
        const id = watch.state.subscription!;
        watch.push(update('foreign', 3, report('root', '999')));
        expect(watch.state.report?.totals.input_tokens).toBe('1000000');
        watch.push(update(id, 2, report('root', '20')));
        watch.push(update(id, 1, report('root', '10')));
        watch.push(update(id, 2, report('root', '40')));
        expect(watch.state.report?.totals.input_tokens).toBe('20');
        watch.disconnected();
        expect(watch.state.stale).toBe(true);
        expect(watch.state.report?.totals.input_tokens).toBe('20');
        await watch.reconnect();
        expect(watch.state.revision).toBe(0);
        expect(watch.state.report?.totals.input_tokens).toBe('1000000');
        await watch.open('usage.conversation', { conversation: 'child' });
        expect(watch.state.report?.filters.filter.conversation).toBe('child');
        await watch.close();
        expect(watch.state.report).toBeUndefined();
        expect(calls.filter(t => t === 'usage.unsubscribe')).toHaveLength(2);
        expect(calls.every(t => ['usage.subscribe', 'usage.unsubscribe'].includes(t))).toBe(true);
    });
    it('holds the newest early snapshot and rejects a different scope even with the same report type', async () => {
        let finish!: (value: unknown) => void;
        const client = new UsageClient({ ask: type => type === 'usage.unsubscribe' ? Promise.resolve({ code: 'OK', payload: {} }) : new Promise(resolve => { finish = value => resolve({ code: 'OK', payload: value }); }) });
        const watch = new UsageWatch(client, () => { }), opening = watch.open('usage.conversation', { conversation: 'root' });
        watch.push(update('s', 3, report('root', '30')));
        watch.push(update('s', 2, report('root', '20')));
        finish(initial('s'));
        await opening;
        expect(watch.state.report?.totals.input_tokens).toBe('30');
        watch.push(update('s', 4, report('wrong', '999')));
        expect(watch.state.stale).toBe(true);
        expect(watch.state.report?.totals.input_tokens).toBe('30');
        const switching = watch.open('usage.conversation', { conversation: 'child' });
        await new Promise(resolve => setTimeout(resolve, 0));
        finish(initial('bad', report('wrong')));
        await switching;
        expect(watch.state.report).toBeUndefined();
        expect(watch.state.error).toContain('another selection');
    });
    it('does not render old acknowledgements after selection/close or retry a model request on refusal', async () => {
        let finish!: (value: unknown) => void;
        const released: string[] = [];
        const client = new UsageClient({ ask: (type, payload) => type === 'usage.unsubscribe' ? (released.push((payload as {
                subscription: string;
            }).subscription), Promise.resolve({ code: 'OK', payload: {} })) : new Promise(resolve => { finish = value => resolve({ code: 'OK', payload: value }); }) });
        const watch = new UsageWatch(client, () => { }), opening = watch.open('usage.conversation', { conversation: 'root' });
        await watch.close();
        finish(initial('late'));
        await opening;
        expect(released).toEqual(['late']);
        expect(watch.state.report).toBeUndefined();
        await expect(new UsageClient({ ask: async () => ({ code: 'BAD_REQUEST', said: 'Permission revoked.' }) }).report('usage.models')).rejects.toThrow('Permission revoked.');
    });
    it('requires decimal-string totals, known coverage, matching filters and the current push protocol', async () => {
        const valid = report();
        expect(usageReport(valid)).toBeDefined();
        expect(usageReport({ ...valid, totals: { ...valid.totals, input_tokens: 9007199254740993 } })).toBeUndefined();
        expect(usageReport({ ...valid, totals: { ...valid.totals, costs: { USD: 'NaN' } } })).toBeUndefined();
        expect(usageReply('usage.models', valid)).toBe(false);
        expect(usageReply('usage.subscribe', { ...initial('s'), filters: { type: 'usage.models', filter: {} } })).toBe(false);
        const watch = new UsageWatch(new UsageClient({ ask: async () => ({ code: 'OK', payload: initial('s') }) }), () => { });
        await watch.open('usage.conversation', { conversation: 'root' });
        watch.push({ ...update('s', 1, report('root', '999')), protocol_version: 'plowshare-v99' });
        expect(watch.state.revision).toBe(0);
    });
});
it('provides lightweight scoped commands and the same report wording', async () => {
    const value = report(), client = new UsageClient({ ask: async () => ({ code: 'OK', payload: value }) });
    const command = usageCommand(['usage'], { conversation: 'root', agent: 'hermes' });
    value.filters.filter.group_by=['model'];
    expect(command.filter).toMatchObject({ conversation: 'root', scope: 'subtree' });
    expect(await runUsage(client, command)).toBe(usageText(value));
    expect(usageCommand(['usage', 'count'], { conversation: 'root', agent: 'hermes' }).filter).toEqual({ conversation: 'root', agent: 'hermes' });
    expect(() => usageCommand(['usage', 'models', '--days', '999'])).toThrow();
    expect(() => usageCommand(['usage', 'run'])).toThrow();
    expect(usageCommand(['usage','--reference','openai-gpt-4.1'],{conversation:'root'})).toMatchObject({type:'usage.conversation',reference:'openai-gpt-4.1'});
    expect(()=>usageCommand(['usage','models','ignored-filter'])).toThrow();
});

it('retains a marked stale same-scope measurement on outage and reconciles changed models/cancellation with full snapshots',async()=>{
    let refused=false;
    const client=new UsageClient({ask:async type=>type==='usage.unsubscribe'?{code:'OK',payload:{}}:refused?{code:'UNAVAILABLE',said:'Projection unavailable.'}:{code:'OK',payload:initial('s')}});
    const watch=new UsageWatch(client,()=>{});await watch.open('usage.conversation',{conversation:'root'});
    const active=report('root','100');active.totals.active_calls='2';active.totals.usage_complete=false;active.groups=[{...active.totals,model:'local-a'}];watch.push(update('s',1,active));
    const settled=report('root','120');settled.groups=[{...settled.totals,model:'local-b'}];settled.totals.incomplete_attempts='1';settled.totals.usage_complete=false;watch.push(update('s',2,settled));
    expect(watch.state.report?.totals.active_calls).toBe('0');expect(watch.state.report?.totals.input_tokens).toBe('120');expect(watch.state.report?.groups[0]?.model).toBe('local-b');
    refused=true;await watch.open('usage.conversation',{conversation:'root'});expect(watch.state.report?.totals.input_tokens).toBe('120');expect(watch.state.stale).toBe(true);expect(watch.state.error).toBe('Projection unavailable.');
    refused=false;await watch.reconnect();expect(watch.state.report?.totals.input_tokens).toBe('1000000');expect(watch.state.stale).toBe(false);
});
