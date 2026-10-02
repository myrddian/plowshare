import type { UsageTotals } from './usage.ts';
/** Dated public reference prices, independent of configured admission price snapshots. USD / million text tokens. */
export interface ReferencePrice {
    id: string;
    label: string;
    input: string;
    output: string;
    currency: string;
    checked: string;
    source?: string;
}
export const REFERENCE_PRICES: readonly ReferencePrice[] = Object.freeze([
    { id: 'openai-gpt-4.1-mini', label: 'OpenAI · GPT-4.1 mini', input: '0.40', output: '1.60', currency: 'USD', checked: '2026-10-02', source: 'https://developers.openai.com/api/docs/models/gpt-4.1-mini' },
    { id: 'openai-gpt-4.1', label: 'OpenAI · GPT-4.1', input: '2', output: '8', currency: 'USD', checked: '2026-10-02', source: 'https://developers.openai.com/api/docs/models/gpt-4.1' },
    { id: 'anthropic-sonnet-5.5', label: 'Anthropic · Claude Sonnet 5.5', input: '2', output: '10', currency: 'USD', checked: '2026-10-02', source: 'https://www.anthropic.com/claude-sonnet-5-5' },
    { id: 'anthropic-opus-5.5', label: 'Anthropic · Claude Opus 5.5', input: '4', output: '20', currency: 'USD', checked: '2026-10-02', source: 'https://www.anthropic.com/claude-sonnet-5-5' },
    { id: 'google-gemini-2.5-flash', label: 'Google · Gemini 2.5 Flash', input: '0.30', output: '2.50', currency: 'USD', checked: '2026-10-02', source: 'https://ai.google.dev/gemini-api/docs/pricing' },
]);
export const DEFAULT_REFERENCE = REFERENCE_PRICES[0]!;
const rate = (value: string): bigint => {
    if (!/^\d{1,9}(?:\.\d{1,6})?$/.test(value))
        throw new Error('Reference rates must be nonnegative decimals with at most six decimal places.');
    const [whole, part = ''] = value.split('.');
    return BigInt(whole!) * 1000000n + BigInt(part.padEnd(6, '0'));
};
export function decimalAmount(value: bigint, scale = 12): string {
    const digits = value.toString().padStart(scale + 1, '0'), whole = digits.slice(0, -scale), part = digits.slice(-scale).replace(/0+$/, '');
    return part ? `${whole}.${part}` : whole;
}
export interface ReferenceEstimate {
    amount?: string;
    currency: string;
    complete: boolean;
    basis: string;
}
export const REFERENCE_BASIS = 'Same recorded input/output tokens at standard text rates. Includes all attempts and query types in this selection; ignores cache discounts, tiers, tool fees, taxes and provider-specific tokenization or output differences. Reasoning/cache subsets are not added again. Recorded costs are unchanged.';
export function referenceCost(totals: UsageTotals, price: ReferencePrice = DEFAULT_REFERENCE): ReferenceEstimate {
    const inputRate = rate(price.input), outputRate = rate(price.output);
    const known = BigInt(totals.input_tokens_known) > 0n || BigInt(totals.output_tokens_known) > 0n || BigInt(totals.attempts) === 0n;
    const amount = BigInt(totals.input_tokens) * inputRate + BigInt(totals.output_tokens) * outputRate;
    return { ...(known ? { amount: decimalAmount(amount) } : {}), currency: price.currency,
        complete: totals.usage_complete && BigInt(totals.active_calls) === 0n, basis: REFERENCE_BASIS };
}
export function referencePrice(id: string): ReferencePrice { const price = REFERENCE_PRICES.find(p => p.id === id); if (!price)
    throw new Error('Choose a reference price preset.'); return price; }
