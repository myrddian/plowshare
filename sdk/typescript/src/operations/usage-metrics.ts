import type { UsageTotals } from './usage.ts';

export interface UsageMetric {
  label: string;
  value: string;
  detail: string;
}

type TokenField =
  | 'provider_total_tokens'
  | 'cache_read_tokens'
  | 'cache_write_tokens'
  | 'reasoning_tokens';

/** Render known subtotals without turning absent provider observations into zeroes. */
export function usageTokenMetric(
  totals: UsageTotals,
  field: TokenField,
  label: string,
): UsageMetric {
  const attempts = BigInt(totals.attempts),
    known = BigInt(totals[`${field}_known`] ?? '0'),
    value = totals[field];
  return {
    label,
    value:
      attempts === 0n
        ? '0'
        : known === 0n || value === undefined
          ? 'Not reported'
          : value,
    detail:
      attempts === 0n
        ? 'No attempts'
        : `${known} of ${attempts} attempts reported${known === attempts ? '' : ' · known subtotal'}`,
  };
}

/**
 * This is a token-weighted cache share, not a percentage of requests with hits.
 * Requiring both fields on every attempt gives the numerator and denominator the
 * same population. A ratio of partial cache counts to all input would understate
 * hits. Pricing completeness is independent and must not hide valid measurements.
 */
export function usageCacheHitRate(totals: UsageTotals): UsageMetric {
  const attempts = BigInt(totals.attempts),
    input = BigInt(totals.input_tokens),
    read = BigInt(totals.cache_read_tokens ?? '0'),
    label = 'Token cache hit rate';
  if (attempts === 0n) {
    return { label, value: 'Not applicable', detail: 'No attempts' };
  }
  if (
    !totals.usage_complete ||
    totals.active_calls !== '0' ||
    totals.incomplete_attempts !== '0' ||
    BigInt(totals.input_tokens_known) !== attempts ||
    BigInt(totals.cache_read_tokens_known ?? '0') !== attempts ||
    totals.cache_read_tokens === undefined ||
    read > input
  ) {
    return {
      label,
      value: 'Unavailable',
      detail:
        'Complete input and cache-read measurements are required for every attempt',
    };
  }
  if (input === 0n) {
    return { label, value: 'Not applicable', detail: 'No input tokens' };
  }
  // Round to two decimal places using integers, including above Number.MAX_SAFE_INTEGER.
  const hundredths = (read * 10000n + input / 2n) / input;
  return {
    label,
    value: `${hundredths / 100n}.${(hundredths % 100n).toString().padStart(2, '0')}%`,
    detail: 'Cache-read tokens / input tokens · all attempts measured',
  };
}

export function usageTokenDetails(totals: UsageTotals): readonly UsageMetric[] {
  return [
    usageTokenMetric(totals, 'provider_total_tokens', 'Provider total tokens'),
    usageTokenMetric(totals, 'cache_read_tokens', 'Cache read tokens'),
    usageTokenMetric(totals, 'cache_write_tokens', 'Cache write tokens'),
    usageTokenMetric(totals, 'reasoning_tokens', 'Reasoning tokens'),
    usageCacheHitRate(totals),
  ];
}
