import { decodeReply } from '../../../sdk/typescript/src/operations/schema.ts';
import type { UsagePanelOptions } from './usage-panel';
import { describe, it, expect, vi } from 'vitest';
import { createUsage } from './usage';
import { mountUsagePanel } from './usage-panel';
import {
  usageRange,
  usageText,
} from '../../../sdk/typescript/src/operations/usage-presentation.ts';
import type { UsageReport } from '../../../sdk/typescript/src/operations/usage.ts';
import type { EventStreamOptions } from '../events';
function checkedRead(
  ask: (type: string, payload: unknown) => Promise<unknown>,
): UsagePanelOptions['read'] {
  return async (type, payload) => decodeReply(type, await ask(type, payload));
}
const makeReport = (): UsageReport => ({
  filters: {
    type: 'usage.models',
    filter: {
      ...usageRange(),
      scope: 'subtree',
      group_by: ['model'],
      limit: 100,
    },
  },
  totals: {
    calls: '2',
    attempts: '2',
    active_calls: '0',
    incomplete_attempts: '0',
    unknown_cost_attempts: '0',
    input_tokens: '1000000',
    output_tokens: '500000',
    input_tokens_known: '2',
    output_tokens_known: '2',
    costs: { USD: '0.005' },
    usage_complete: true,
    cost_complete: true,
    complete: true,
  },
  groups: [],
  cursor: null,
  health: {
    watermark: '12',
    as_of: '2026-10-02T01:00:00Z',
    capture_enabled: true,
    historical_usage: 'not_imported',
  },
});
const tick = () => new Promise((resolve) => setTimeout(resolve, 0));
describe('usage panel', () => {
  it('changes comparison locally, persists the default, and never replaces recorded costs', async () => {
    const root = document.createElement('div'),
      select = vi.fn(async () => {}),
      read = vi.fn(),
      storage = { getItem: () => null, setItem: vi.fn() };
    const panel = mountUsagePanel(root, { select, read, storage });
    await tick();
    panel.update({
      report: makeReport(),
      revision: 0,
      stale: false,
      loading: false,
    });
    expect(root.textContent).toContain('USD 1.2');
    expect(root.textContent).toContain('USD 0.005');
    const price = root.querySelector<HTMLSelectElement>('[data-usage-price]')!;
    price.value = 'openai-gpt-4.1';
    price.dispatchEvent(new Event('change'));
    expect(root.textContent).toContain('USD 6');
    expect(root.textContent).toContain('USD 0.005');
    expect(read).not.toHaveBeenCalled();
    expect(select).toHaveBeenCalledOnce();
    expect(storage.setItem).toHaveBeenCalledOnce();
    expect(usageText(makeReport())).toContain('USD 1.2');
    price.value = 'custom';
    price.dispatchEvent(new Event('change'));
    expect(root.querySelector<HTMLElement>('[data-usage-custom]')!.hidden).toBe(
      false,
    );
    const input = root.querySelector<HTMLInputElement>('[data-usage-input]')!;
    input.value = '-1';
    input.dispatchEvent(new Event('change'));
    expect(root.querySelector('[role=alert]')!.textContent).toContain(
      'nonnegative',
    );
    panel.destroy();
    expect(root.textContent).toBe('');
  });
  it('rejects old scopes, safely displays run hierarchy and drills down without mixing inclusive totals', async () => {
    const root = document.createElement('div'),
      select = vi.fn(async () => {}),
      panel = mountUsagePanel(root, { select, read: vi.fn() });
    await tick();
    const group = root.querySelector<HTMLSelectElement>('[data-usage-group]')!;
    group.value = 'run';
    group.dispatchEvent(new Event('change'));
    await tick();
    const report = makeReport();
    report.filters.filter.group_by = ['run', 'agent'];
    report.groups = [
      {
        ...report.totals,
        run: 'leaf',
        agent: '<img src=x>',
        ancestor_runs: ['idle', 'root'],
      },
    ];
    panel.update({ report, revision: 0, stale: false, loading: false });
    expect(root.querySelector('img')).toBeNull();
    expect(root.querySelectorAll('[data-usage-run]')).toHaveLength(3);
    root.querySelector<HTMLButtonElement>('[data-usage-run="idle"]')!.click();
    await tick();
    expect(select).toHaveBeenLastCalledWith(
      'usage.run',
      expect.objectContaining({ run: 'idle', scope: 'subtree' }),
    );
    panel.update({ report, revision: 1, stale: false, loading: false });
    expect(root.textContent).not.toContain('USD 1.2');
    panel.destroy();
  });
  it('reads context as a separate operation with explicit conversation and agent controls', async () => {
    const root = document.createElement('div'),
      read = vi.fn(async () => ({
        conversation: 'chat',
        agent: 'hermes',
        projection: 'next',
        count: {
          source: 'test',
          pool: null,
          model: null,
          revision: null,
          countedAt: '2026-10-05T00:00:00Z',
          elapsedMillis: 0,
          cached: false,
          tokens: null,
          basis: 'UNKNOWN',
          gaps: ['template not supported'],
        },
      })),
      panel = mountUsagePanel(root, {
        select: async () => {},
        read: checkedRead(read),
      });
    root.querySelector<HTMLInputElement>(
      '[data-usage-count-conversation]',
    )!.value = 'chat';
    root.querySelector<HTMLInputElement>('[data-usage-count-agent]')!.value =
      'hermes';
    root.querySelector<HTMLButtonElement>('[data-usage-count]')!.click();
    await tick();
    expect(read).toHaveBeenCalledWith('conversation.context.count', {
      conversation: 'chat',
      agent: 'hermes',
    });
    expect(
      root.querySelector('[data-usage-count-result]')!.textContent,
    ).toContain('Unknown tokens');
    panel.destroy();
  });
  it('subscribes after the first connection, retains stale measurements, reconciles on reconnect and unsubscribes on close', async () => {
    const root = document.createElement('div');
    let options!: EventStreamOptions;
    let open = false,
      index = 0;
    const ask = vi.fn(async (type: string) => {
      if (!open) throw new Error('not connected');
      if (type === 'usage.unsubscribe') return { code: 'OK', payload: {} };
      const report = makeReport();
      return {
        code: 'OK',
        payload: {
          subscription: 's' + ++index,
          revision: 0,
          filters: report.filters,
          report,
        },
      };
    });
    const close = vi.fn(),
      screen = createUsage({
        root,
        session: 'one',
        project: null,
        openStream: (value) => {
          options = value;
          return {
            ask,
            close,
            status: () => ({
              state: open ? 'open' : 'connecting',
              attempt: 0,
              retryInMs: null,
            }),
          };
        },
      });
    await screen.load();
    open = true;
    options.onStatus?.({ state: 'open', attempt: 0, retryInMs: null });
    await tick();
    expect(root.textContent).toContain('USD 1.2');
    options.onStatus?.({ state: 'reconnecting', attempt: 1, retryInMs: 100 });
    expect(root.textContent).toContain('Last snapshot');
    expect(root.textContent).toContain('USD 1.2');
    options.onStatus?.({ state: 'open', attempt: 0, retryInMs: null });
    await tick();
    expect(index).toBe(2);
    screen.destroy();
    await tick();
    expect(ask).toHaveBeenCalledWith('usage.unsubscribe', {
      subscription: 's2',
    });
    expect(close).toHaveBeenCalledOnce();
    expect(root.childElementCount).toBe(0);
  });
});

it('preserves a paged breakdown across unrelated desktop state updates and resets on a newer usage snapshot', async () => {
  const root = document.createElement('div'),
    report = makeReport();
  report.groups = [{ ...report.totals, model: 'first-model' }];
  report.cursor = 'signed-page';
  const later = {
    ...report,
    groups: [{ ...report.totals, model: 'later-model' }],
    cursor: null,
  };
  const read = vi.fn(async () => later),
    panel = mountUsagePanel(root, {
      select: async () => {},
      read: checkedRead(read),
    });
  await tick();
  const snapshot = {
    report,
    subscription: 's',
    revision: 0,
    stale: false,
    loading: false,
  };
  panel.update(snapshot);
  root.querySelector<HTMLButtonElement>('[data-usage-more]')!.click();
  await tick();
  expect(root.textContent).toContain('later-model');
  panel.update(structuredClone(snapshot));
  expect(root.textContent).toContain('later-model');
  expect(root.textContent).not.toContain('first-model');
  panel.update({ ...snapshot, revision: 1 });
  expect(root.textContent).toContain('first-model');
  panel.destroy();
});

describe('Usage workspace', () => {
  it('separates views and compares providers locally without changing recorded costs', async () => {
    const root = document.createElement('div'),
      select = vi.fn(async () => {}),
      read = vi.fn(async () => ({ calls: [], cursor: null }));
    const panel = mountUsagePanel(root, {
      overview: true,
      context: false,
      select,
      read: checkedRead(read),
    });
    await tick();
    const report = makeReport();
    report.health.tracking_started_at = '2026-10-03T06:45:59Z';
    panel.update({ report, revision: 0, stale: false, loading: false });
    const view = (key: string) =>
      root.querySelector<HTMLElement>(`[data-usage-view="${key}"]`)!;
    const tab = (key: string) =>
      root.querySelector<HTMLButtonElement>(`[data-usage-tab="${key}"]`)!;
    expect(view('overview').hidden).toBe(false);
    expect(view('pricing').hidden).toBe(true);
    expect(view('overview').textContent).not.toContain('Reference rates');
    expect(view('overview').textContent).not.toContain('watermark');
    expect(view('overview').textContent).toContain(
      'Earlier history is not imported',
    );
    expect(read).not.toHaveBeenCalled();
    tab('calls').click();
    await tick();
    expect(read).toHaveBeenCalledOnce();
    expect(read).toHaveBeenCalledWith(
      'usage.calls',
      expect.objectContaining({ scope: 'subtree', limit: 20 }),
    );
    tab('pricing').click();
    const provider = root.querySelector<HTMLSelectElement>(
        '[data-usage-provider]',
      )!,
      model = root.querySelector<HTMLSelectElement>('[data-usage-price]')!;
    provider.value = 'Anthropic';
    provider.dispatchEvent(new Event('change'));
    expect(model.value).toBe('anthropic-sonnet-5.5');
    expect(view('pricing').textContent).toContain('USD 7');
    model.value = 'anthropic-opus-5.5';
    model.dispatchEvent(new Event('change'));
    expect(view('pricing').textContent).toContain('USD 14');
    expect(view('pricing').textContent).toContain('USD 0.005');
    provider.value = 'Google';
    provider.dispatchEvent(new Event('change'));
    expect(model.value).toBe('google-gemini-2.5-flash');
    expect(view('pricing').textContent).toContain('USD 1.55');
    expect(select).toHaveBeenCalledOnce();
    expect(read).toHaveBeenCalledOnce();
    const updated = structuredClone(report);
    updated.totals.input_tokens = '2000000';
    panel.update({
      report: updated,
      revision: 1,
      stale: false,
      loading: false,
    });
    expect(view('pricing').hidden).toBe(false);
    expect(view('pricing').textContent).toContain('USD 1.85');
    expect(root.querySelector('[data-usage-count]')).toBeNull();
    tab('pricing').dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }),
    );
    expect(view('recording').hidden).toBe(false);
    expect(tab('recording').getAttribute('aria-selected')).toBe('true');
    panel.destroy();
  });
  it('restores provider/model preferences and distinguishes missing measurements from known zero', async () => {
    const root = document.createElement('div'),
      storage = {
        getItem: () =>
          JSON.stringify({
            id: 'anthropic-opus-5.5',
            input: '4',
            output: '20',
          }),
        setItem: vi.fn(),
      };
    const panel = mountUsagePanel(root, {
      overview: true,
      context: false,
      storage,
      select: async () => {},
      read: vi.fn(),
    });
    await tick();
    const report = makeReport();
    Object.assign(report.totals, {
      input_tokens: '0',
      input_tokens_known: '0',
      output_tokens: '0',
      output_tokens_known: '2',
      usage_complete: false,
    });
    report.groups = [
      { ...report.totals, model: '<img src=x onerror=alert(1)>' },
    ];
    panel.update({ report, revision: 0, stale: false, loading: false });
    expect(
      root.querySelector<HTMLSelectElement>('[data-usage-provider]')!.value,
    ).toBe('Anthropic');
    expect(
      root.querySelector<HTMLSelectElement>('[data-usage-price]')!.value,
    ).toBe('anthropic-opus-5.5');
    const values = [...root.querySelectorAll('.usage-stat strong')].map(
      (el) => el.textContent,
    );
    expect(values.slice(0, 2)).toEqual(['Not reported', '0']);
    expect(root.querySelector('img')).toBeNull();
    expect(
      root.querySelector('[data-usage-comparison]')!.textContent,
    ).toContain('Partial token subtotal');
    panel.destroy();
  });
  it('shows readable attempts and rejects call pages from an old scope', async () => {
    const root = document.createElement('div');
    let resolve!: (value: unknown) => void;
    const read = vi.fn(
      () =>
        new Promise<unknown>((done) => {
          resolve = done;
        }),
    );
    const panel = mountUsagePanel(root, {
      overview: true,
      context: false,
      select: async () => {},
      read: checkedRead(read),
    });
    await tick();
    panel.update({
      report: makeReport(),
      revision: 0,
      stale: false,
      loading: false,
    });
    root.querySelector<HTMLButtonElement>('[data-usage-tab="calls"]')!.click();
    resolve({
      filters: { type: 'usage.calls', filter: {} },
      health: makeReport().health,
      calls: [
        {
          created_at: '2026-10-05T00:00:00Z',
          ended_at: null,
          pool: 'test',
          model_family: null,
          billing_route: null,
          price_version: null,
          project_id: null,
          scope: 'GLOBAL',
          conversation_id: null,
          run_id: null,
          orchestration_id: null,
          turn_ordinal: null,
          step_ordinal: null,
          attempt_count: 1,
          queue_millis: null,
          admission_accounting_millis: null,
          preflight_observation: null,
          attempt_cursor: null,
          attempts_truncated: false,
          agent_name: '<script>',
          wire_model: 'test',
          call_id: 'call',
          operation: 'AGENT_CHAT',
          lifecycle: 'SUCCEEDED',
          attempts: [
            {
              attempt_id: 'attempt',
              outcome: 'SUCCEEDED',
              http_status: 200,
              finish_reason: null,
              provider_total_tokens: null,
              cache_read_tokens: null,
              cache_write_tokens: null,
              reasoning_tokens: null,
              usage_source: null,
              cost_currency: null,
              price_version: null,
              first_output_millis: null,
              duration_millis: null,
              start_accounting_millis: null,
              cost_reasons: [],
              attempt_number: 1,
              usage_coverage: 'UNKNOWN',
              input_tokens: null,
              output_tokens: '0',
              cost_kind: 'UNPRICED',
              cost_amount: null,
            },
          ],
        },
      ],
      cursor: null,
    });
    await tick();
    expect(root.querySelector('[data-usage-calls]')!.textContent).toContain(
      'Not reported',
    );
    expect(root.querySelector('[data-usage-calls]')!.textContent).toContain(
      'Raw server record',
    );
    expect(root.querySelector('script')).toBeNull();
    root
      .querySelector<HTMLButtonElement>('[data-usage-view="calls"] button')!
      .click();
    const type = root.querySelector<HTMLSelectElement>('[data-usage-type]')!;
    type.value = 'usage.project';
    type.dispatchEvent(new Event('change'));
    resolve({ calls: [{ agent_name: 'old scope' }], cursor: null });
    await tick();
    expect(root.querySelector('[data-usage-calls]')!.textContent).not.toContain(
      'old scope',
    );
    panel.destroy();
  });
});

it('delivers synchronous first-mount updates and loads the first connected snapshot without a manual refresh', async () => {
  const root = document.createElement('div');
  const report = makeReport();
  const ask = vi.fn(async () => ({
    code: 'OK',
    payload: {
      subscription: 'first',
      revision: 0,
      filters: report.filters,
      report,
    },
  }));
  const screen = createUsage({
    root,
    session: 'one',
    project: null,
    openStream: () => ({
      ask,
      close: vi.fn(),
      status: () => ({ state: 'open', attempt: 0, retryInMs: null }),
    }),
  });
  expect(root.textContent).toContain('Reading usage');
  await screen.load();
  await tick();
  expect(ask).toHaveBeenCalledOnce();
  expect(root.textContent).toContain('USD 0.005');
  expect(root.textContent).not.toContain('before initialization');
  screen.destroy();
  await tick();
});
