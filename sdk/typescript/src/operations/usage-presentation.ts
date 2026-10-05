import type { UsageReport } from './usage.ts';
import {
  DEFAULT_REFERENCE,
  referenceCost,
  type ReferencePrice,
} from './reference-cost.ts';
export function usageRange(
  days = 30,
  now = new Date(),
): {
  from: string;
  to: string;
} {
  const to = new Date(
      Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1),
    ),
    from = new Date(to);
  from.setUTCDate(from.getUTCDate() - days);
  return { from: from.toISOString(), to: to.toISOString() };
}
const costs = (row: UsageReport['totals']) =>
  Object.entries(row.costs)
    .map(([currency, amount]) => `${currency} ${amount}`)
    .join(' · ') ||
  (row.cost_complete ? 'No booked cost' : 'Unpriced / unknown');
export function usageText(
  report: UsageReport,
  price: ReferencePrice = DEFAULT_REFERENCE,
): string {
  const estimate = referenceCost(report.totals, price),
    t = report.totals,
    h = report.health;
  return [
    `Usage · ${report.filters.type} · ${report.filters.filter.scope ?? 'direct'}`,
    `${report.filters.filter.from} → ${report.filters.filter.to} (UTC, end exclusive)`,
    `Calls ${t.calls} · attempts ${t.attempts} · active ${t.active_calls}`,
    `Recorded input ${t.input_tokens} · output ${t.output_tokens} (${t.usage_complete ? 'complete' : 'known subtotal; incomplete usage'})`,
    `Booked estimates: ${costs(t)}${t.cost_complete ? '' : ' · incomplete pricing'}`,
    `Reference ${price.label}: ${estimate.amount === undefined ? 'Unavailable' : `${estimate.currency} ${estimate.amount}`}${estimate.complete ? '' : ' · partial known subtotal'}`,
    `Reference rates / million: input ${price.input}, output ${price.output}. Checked ${price.checked}.`,
    estimate.basis,
    `Capture ${h.capture_enabled ? 'enabled' : 'disabled'} · watermark ${h.watermark} · pending ${h.pending_events ?? 'unknown'} · lag ${h.projection_lag_millis ?? 'unknown'} ms · historical usage ${h.historical_usage}`,
    ...report.groups.map(
      (row) =>
        `${(
          [
            'day',
            'project',
            'agent',
            'run',
            'model',
            'pool',
            'operation',
          ] as const
        )
          .map((key) => row[key])
          .filter((v) => v !== undefined)
          .map((v) => (v === null ? '(unassigned)' : String(v)))
          .join(
            ' / ',
          )}: input ${row.input_tokens}, output ${row.output_tokens}, ${costs(row)}${row.usage_complete ? '' : ' · known subtotal; incomplete usage'}${row.cost_complete ? '' : ' · incomplete pricing'}`,
    ),
    ...(report.cursor ? ['More breakdown rows are available.'] : []),
  ].join('\n');
}
