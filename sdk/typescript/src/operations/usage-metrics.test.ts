import { describe, expect, it } from 'vitest';
import type { UsageTotals } from './usage.ts';
import {
  usageCacheHitRate,
  usageTokenDetails,
  usageTokenMetric,
} from './usage-metrics.ts';

function totals(changes: Partial<UsageTotals> = {}): UsageTotals {
  return {
    calls: '1',
    attempts: '1',
    active_calls: '0',
    incomplete_attempts: '0',
    unknown_cost_attempts: '0',
    input_tokens: '100',
    output_tokens: '20',
    input_tokens_known: '1',
    output_tokens_known: '1',
    provider_total_tokens: '120',
    provider_total_tokens_known: '1',
    cache_read_tokens: '80',
    cache_read_tokens_known: '1',
    costs: {},
    usage_complete: true,
    cost_complete: true,
    complete: true,
    ...changes,
  };
}

describe('provider token measurements', () => {
  it('distinguishes an explicitly measured zero from an unreported quantity', () => {
    expect(
      usageTokenMetric(
        totals({ cache_read_tokens: '0' }),
        'cache_read_tokens',
        'Cache read tokens',
      ),
    ).toMatchObject({ value: '0', detail: '1 of 1 attempts reported' });
    expect(
      usageTokenMetric(totals(), 'cache_write_tokens', 'Cache write tokens'),
    ).toMatchObject({
      value: 'Not reported',
      detail: '0 of 1 attempts reported · known subtotal',
    });
    const missingValue = totals();
    delete missingValue.cache_read_tokens;
    expect(
      usageTokenMetric(missingValue, 'cache_read_tokens', 'Cache read tokens')
        .value,
    ).toBe('Not reported');
  });

  it('labels partial measurements as known subtotals without inventing the missing attempts', () => {
    const row = totals({ attempts: '2', input_tokens_known: '2' });
    expect(
      usageTokenMetric(row, 'cache_read_tokens', 'Cache read tokens'),
    ).toMatchObject({
      value: '80',
      detail: '1 of 2 attempts reported · known subtotal',
    });
    expect(usageCacheHitRate(row).value).toBe('Unavailable');
  });

  it('shows no attempts separately from a measured zero', () => {
    const row = totals({
      calls: '0',
      attempts: '0',
      input_tokens: '0',
      output_tokens: '0',
      input_tokens_known: '0',
      output_tokens_known: '0',
    });
    expect(
      usageTokenMetric(row, 'cache_write_tokens', 'Cache write tokens'),
    ).toMatchObject({ value: '0', detail: 'No attempts' });
    expect(usageCacheHitRate(row)).toMatchObject({
      value: 'Not applicable',
      detail: 'No attempts',
    });
  });

  it('exposes all provider categories without changing recorded tokens or prices', () => {
    const row = totals({ reasoning_tokens: '5', reasoning_tokens_known: '1' });
    const before = structuredClone(row);
    expect(usageTokenDetails(row).map((metric) => metric.label)).toEqual([
      'Provider total tokens',
      'Cache read tokens',
      'Cache write tokens',
      'Reasoning tokens',
      'Token cache hit rate',
    ]);
    expect(row).toEqual(before);
  });
});

describe('token-weighted cache hit rate', () => {
  it('remains available when token measurements are complete but pricing is unknown', () => {
    expect(
      usageCacheHitRate(
        totals({
          cost_complete: false,
          complete: false,
          unknown_cost_attempts: '1',
        }),
      ),
    ).toMatchObject({ value: '80.00%' });
  });

  it('does not divide a partial cache numerator by a different input population', () => {
    // One attempt reports 80/100 cached input; a second reports 900 input and no cache split.
    const row = totals({
      calls: '2',
      attempts: '2',
      input_tokens: '1000',
      input_tokens_known: '2',
      cache_read_tokens: '80',
      cache_read_tokens_known: '1',
    });
    expect(usageCacheHitRate(row).value).toBe('Unavailable');
    const missingCount = totals();
    delete missingCount.cache_read_tokens_known;
    expect(usageCacheHitRate(missingCount).value).toBe('Unavailable');
    const missingValue = totals();
    delete missingValue.cache_read_tokens;
    expect(usageCacheHitRate(missingValue).value).toBe('Unavailable');
  });

  it('refuses active, incomplete or invalid cohorts even if their known counts match', () => {
    for (const change of [
      { active_calls: '1' },
      { incomplete_attempts: '1' },
      { usage_complete: false },
      { input_tokens_known: '0' },
    ]) {
      expect(usageCacheHitRate(totals(change)).value).toBe('Unavailable');
    }
  });

  it('distinguishes measured misses, unknown splits and an empty input denominator', () => {
    expect(usageCacheHitRate(totals({ cache_read_tokens: '0' })).value).toBe(
      '0.00%',
    );
    expect(
      usageCacheHitRate(
        totals({ cache_read_tokens: '0', cache_read_tokens_known: '0' }),
      ).value,
    ).toBe('Unavailable');
    expect(
      usageCacheHitRate(totals({ input_tokens: '0', cache_read_tokens: '0' })),
    ).toMatchObject({ value: 'Not applicable', detail: 'No input tokens' });
    expect(usageCacheHitRate(totals({ cache_read_tokens: '101' })).value).toBe(
      'Unavailable',
    );
  });

  it('rounds large aggregate quantities exactly on both sides of a rounding boundary', () => {
    const row = totals({
      calls: '100',
      attempts: '100',
      input_tokens: '200000000000000000000',
      input_tokens_known: '100',
      output_tokens_known: '100',
      cache_read_tokens: '160009999999999999999',
      cache_read_tokens_known: '100',
    });
    expect(usageCacheHitRate(row).value).toBe('80.00%');
    expect(
      usageCacheHitRate({ ...row, cache_read_tokens: '160010000000000000000' })
        .value,
    ).toBe('80.01%');
    expect(
      usageCacheHitRate(totals({ input_tokens: '3', cache_read_tokens: '1' }))
        .value,
    ).toBe('33.33%');
    expect(usageCacheHitRate(totals({ cache_read_tokens: '100' })).value).toBe(
      '100.00%',
    );
  });
});
