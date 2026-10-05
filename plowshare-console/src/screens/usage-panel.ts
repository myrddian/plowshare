import { errorMessage } from '../../../sdk/typescript/src/binding/values.ts';
import { background } from '../background.ts';
import { decodePayload } from '../../../sdk/typescript/src/operations/schema.ts';
import type { Replies } from '../../../sdk/typescript/src/operations/replies.ts';
import type {
  UsagePayloads,
  UsageAttempt,
} from '../../../sdk/typescript/src/operations/usage.ts';
import {
  displayText,
  isObject,
  isOneOf,
} from '../../../sdk/typescript/src/binding/values.ts';
import { isList } from '../../../sdk/typescript/src/binding/values.ts';
import {
  USAGE_REPORTS,
  usageMatches,
  type UsageFilter,
  type UsageReportType,
  type UsageViewState,
  type UsageTotals,
} from '../../../sdk/typescript/src/operations/usage.ts';
import {
  DEFAULT_REFERENCE,
  referenceCost,
  referencePrice,
  REFERENCE_PRICES,
  type ReferencePrice,
} from '../../../sdk/typescript/src/operations/reference-cost.ts';
import {
  usageText,
  usageRange,
} from '../../../sdk/typescript/src/operations/usage-presentation.ts';
export interface UsagePanel {
  update(state: UsageViewState): void;
  destroy(): void;
  refresh(): Promise<void>;
}
export interface UsagePanelOptions {
  select(type: UsageReportType, filter: UsageFilter): Promise<void>;
  read<
    K extends 'usage.calls' | UsageReportType | 'conversation.context.count',
  >(
    type: K,
    payload: UsagePayloads[K],
  ): Promise<Replies[K]>;
  overview?: boolean;
  context?: boolean;
  injectStyle?: boolean;
  conversation?: () => string;
  agent?: () => string;
  project?: string | null;
  storage?: Pick<Storage, 'getItem' | 'setItem'>;
}
function node<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  text?: string,
): HTMLElementTagNameMap[K] {
  const result = document.createElement(tag);
  if (text !== undefined) result.textContent = text;
  return result;
}
function selectControl(
  entries: readonly (readonly [string, string])[],
  value: string,
): HTMLSelectElement {
  const control = node('select');
  for (const [key, label] of entries) {
    const option = node('option', label);
    option.value = key;
    control.append(option);
  }
  control.value = value;
  return control;
}
function label(text: string, control: HTMLElement): HTMLLabelElement {
  const result = node('label', text);
  result.append(control);
  return result;
}
function button(text: string, click: () => void): HTMLButtonElement {
  const result = node('button', text);
  result.type = 'button';
  result.addEventListener('click', click);
  return result;
}
function disclosure(title: string): HTMLDetailsElement {
  const result = node('details');
  result.append(node('summary', title));
  return result;
}
let panelSequence = 0;
const EMPTY: UsageTotals = {
  calls: '0',
  incomplete_attempts: '0',
  unknown_cost_attempts: '0',
  costs: {},
  cost_complete: true,
  complete: true,
  input_tokens: '0',
  output_tokens: '0',
  input_tokens_known: '0',
  output_tokens_known: '0',
  attempts: '0',
  active_calls: '0',
  usage_complete: true,
};
/** Shared desktop/console widget. Server values reach textContent, never the HTML parser. */
export function mountUsagePanel(
  root: HTMLElement,
  options: UsagePanelOptions,
): UsagePanel {
  root.replaceChildren();
  root.classList.add('usage-panel');
  root.classList.toggle('usage-workspace', !!options.overview);
  const style = node('style', USAGE_STYLE);
  if (options.injectStyle !== false) root.append(style);
  const type = selectControl(
    USAGE_REPORTS.map((type) => [
      type,
      type === 'usage.models'
        ? 'Accessible work'
        : type === 'usage.pools'
          ? 'Fleet pools (administrator)'
          : type.slice(6),
    ]),
    options.conversation?.() ? 'usage.conversation' : 'usage.models',
  );
  const target = node('input');
  target.placeholder = 'Conversation, project, agent or run';
  const days = selectControl(
    [
      ['1', 'Today (UTC)'],
      ['7', '7 days through today'],
      ['30', '30 days through today'],
      ['365', '365 days through today'],
    ],
    '30',
  );
  const scope = selectControl(
    [
      ['subtree', 'Include descendants'],
      ['direct', 'Direct only'],
    ],
    'subtree',
  );
  const group = selectControl(
    [
      ['model', 'Model'],
      ['agent', 'Agent'],
      ['run', 'Agent runs'],
      ['operation', 'Operation'],
      ['pool', 'Pool'],
      ['project', 'Project'],
      ['day', 'Day'],
    ],
    'model',
  );
  const preset = selectControl(
    [
      ...REFERENCE_PRICES.map((p) => [p.id, p.label] as const),
      ['custom', 'Custom rates / Azure deployment'],
    ],
    DEFAULT_REFERENCE.id,
  );
  const input = node('input');
  input.value = DEFAULT_REFERENCE.input;
  input.inputMode = 'decimal';
  const output = node('input');
  output.value = DEFAULT_REFERENCE.output;
  output.inputMode = 'decimal';
  const custom = node('div');
  custom.hidden = true;
  custom.append(
    label('Input / million', input),
    label('Output / million', output),
    node(
      'p',
      'Custom USD rates, including negotiated Azure pricing. Applies only to this comparison.',
    ),
  );
  const reportBody = node('div'),
    errorBody = node('p'),
    liveStatus = node('div');
  liveStatus.setAttribute('role', 'status');
  liveStatus.dataset.usageStatus = '';
  errorBody.setAttribute('role', 'alert');
  const auditBody = node('div'),
    countBody = node('p');
  countBody.setAttribute('role', 'status');
  const countConversation = node('input');
  countConversation.value = options.conversation?.() ?? '';
  const countAgent = node('input');
  countAgent.value = options.agent?.() ?? '';
  const provider = selectControl(
    [...new Set(REFERENCE_PRICES.map((p) => p.label.split(' · ')[0]!))]
      .map((name) => [name, name] as const)
      .concat([['custom', 'Custom / Azure']]),
    DEFAULT_REFERENCE.label.split(' · ')[0]!,
  );
  const controls = node('div');
  controls.className = 'usage-controls';
  controls.append(
    label('Scope', type),
    label('Target', target),
    label('Date range', days),
    label('Child work', scope),
    button('Refresh usage', () => {
      background(select());
    }),
  );
  if (!options.overview) controls.append(label('Breakdown', group));
  const references = node('div');
  references.className = 'usage-reference-controls';
  references.append(
    ...(options.overview
      ? [label('Provider', provider), label('Model', preset)]
      : [label('Reference provider / model', preset)]),
    custom,
  );
  const more = button('Next breakdown page', () => {
    background(nextGroups());
  });
  more.hidden = true;
  const auditNext = button('Next call page', () => {
    background(audit(true));
  });
  auditNext.hidden = true;
  const auditDetails = disclosure('Call and attempt audit');
  auditDetails.append(
    button('Read call details', () => {
      background(audit());
    }),
    auditNext,
    auditBody,
  );
  const context = disclosure('Current context · separate from spend');
  context.append(
    node(
      'p',
      'Counts the selected agent’s next ordinary conversation projection. Does not book tokens or costs; drafts and transient forced-tool prompts are excluded.',
    ),
    label('Conversation', countConversation),
    label('Agent', countAgent),
    button('Count next projection', () => {
      background(count());
    }),
    countBody,
  );
  const pricingBody = node('div'),
    recordingBody = node('div'),
    breakdownBody = node('div');
  const callStatus = node('p');
  callStatus.setAttribute('role', 'status');
  const views = new Map<string, HTMLElement>();
  const tabs = node('div');
  tabs.className = 'usage-tabs';
  tabs.setAttribute('role', 'tablist');
  tabs.setAttribute('aria-label', 'Usage views');
  const instance = ++panelSequence;
  let activeView = 'overview',
    auditLoaded = false,
    auditPending = false;
  for (const [key, title] of [
    ['overview', 'Overview'],
    ['calls', 'Calls'],
    ['pricing', 'Pricing'],
    ['recording', 'Recording'],
  ]) {
    const view = node('div');
    view.id = `usage-${instance}-${key}`;
    view.dataset.usageView = key;
    view.setAttribute('role', 'tabpanel');
    view.setAttribute('aria-labelledby', `${view.id}-tab`);
    view.hidden = key !== activeView;
    const tab = button(title!, () => showView(key!));
    tab.id = `${view.id}-tab`;
    tab.dataset.usageTab = key;
    tab.setAttribute('role', 'tab');
    tab.setAttribute('aria-controls', view.id);
    tab.setAttribute('aria-selected', String(key === activeView));
    tab.tabIndex = key === activeView ? 0 : -1;
    tabs.append(tab);
    views.set(key!, view);
  }
  function showView(key: string) {
    activeView = key;
    for (const [name, view] of views) view.hidden = name !== key;
    for (const tab of tabs.querySelectorAll<HTMLButtonElement>('button')) {
      const active = tab.dataset.usageTab === key;
      tab.setAttribute('aria-selected', String(active));
      tab.tabIndex = active ? 0 : -1;
    }
    if (key === 'calls' && state.report && !auditLoaded && !auditPending)
      background(audit());
  }
  tabs.addEventListener('keydown', (event) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    const buttons = [...tabs.querySelectorAll<HTMLButtonElement>('button')],
      index = buttons.indexOf(event.target as HTMLButtonElement);
    if (index < 0) return;
    event.preventDefault();
    const next =
      event.key === 'Home'
        ? 0
        : event.key === 'End'
          ? buttons.length - 1
          : (index + (event.key === 'ArrowRight' ? 1 : buttons.length - 1)) %
            buttons.length;
    showView(buttons[next]!.dataset.usageTab!);
    buttons[next]!.focus();
  });
  if (options.overview) {
    const breakdownControls = node('div');
    breakdownControls.className = 'usage-breakdown-controls';
    breakdownControls.append(node('h3', 'Breakdown'), label('Group by', group));
    views
      .get('overview')!
      .append(reportBody, breakdownControls, breakdownBody, more);
    const callControls = node('div');
    callControls.className = 'usage-call-controls';
    callControls.append(
      node('h3', 'Call history'),
      button('Refresh calls', () => {
        background(audit());
      }),
      auditNext,
    );
    views.get('calls')!.append(callControls, callStatus, auditBody);
    views
      .get('pricing')!
      .append(node('h3', 'Compare provider costs'), references, pricingBody);
    views
      .get('recording')!
      .append(node('h3', 'Recording and coverage'), recordingBody);
    root.append(controls, errorBody, tabs, liveStatus, ...views.values());
  } else {
    root.append(
      controls,
      node(
        'p',
        'All recorded attempts in the selected authorized scope and date range. Descendant spend is inclusive; do not add it to direct spend.',
      ),
      errorBody,
      reportBody,
      more,
      references,
      auditDetails,
    );
  }
  if (options.context !== false) root.append(context);
  // Stable selectors for platform integration and accessibility tests.
  for (const [name, element] of Object.entries({
    type,
    target,
    days,
    scope,
    group,
    price: preset,
    provider,
    input,
    output,
    custom,
    comparison: pricingBody,
    recording: recordingBody,
    breakdown: breakdownBody,
    calls: auditBody,
    report: reportBody,
    more,
    'audit-next': auditNext,
    'count-conversation': countConversation,
    'count-agent': countAgent,
    'count-result': countBody,
  }))
    element.setAttribute('data-usage-' + name, '');
  context.querySelector('button')!.setAttribute('data-usage-count', '');
  let state: UsageViewState = { revision: -1, stale: true, loading: false },
    price: ReferencePrice = DEFAULT_REFERENCE;
  let selection:
    | {
        type: UsageReportType;
        filter: UsageFilter;
      }
    | undefined;
  let lastSnapshot = '';
  let closed = false,
    epoch = 0,
    page = 0,
    auditCursor: string | null = null,
    groupCursor: string | null = null;
  try {
    const saved = options.storage?.getItem('plowshare.reference-price.v1');
    if (saved) {
      const p: unknown = JSON.parse(saved);
      if (!isObject(p) || typeof p['id'] !== 'string')
        throw new Error('Invalid saved reference price');
      const known = REFERENCE_PRICES.find((price) => price.id === p['id']);
      if (p['id'] !== 'custom' && !known)
        throw new Error('Unknown saved price');
      if (
        p['id'] === 'custom' &&
        (typeof p['input'] !== 'string' ||
          typeof p['output'] !== 'string' ||
          typeof p['checked'] !== 'string' ||
          p['currency'] !== 'USD')
      )
        throw new Error('Invalid custom reference price');
      const savedPrice: ReferencePrice = known ?? {
        id: 'custom',
        input: p['input'] as string,
        output: p['output'] as string,
        checked: p['checked'] as string,
        currency: 'USD',
        label: 'Custom reference',
      };
      referenceCost(EMPTY, savedPrice);
      price =
        savedPrice.id === 'custom'
          ? {
              id: 'custom',
              label: 'Custom reference',
              input: savedPrice.input,
              output: savedPrice.output,
              currency: 'USD',
              checked: /^\d{4}-\d{2}-\d{2}$/.test(savedPrice.checked)
                ? savedPrice.checked
                : 'unknown',
            }
          : referencePrice(savedPrice.id);
      preset.value = price.id;
      input.value = price.input;
      output.value = price.output;
    }
  } catch {
    /* Damaged or unavailable preferences use the dated default. */
  }
  syncProvider();
  custom.hidden = preset.value !== 'custom';
  function error(reason: unknown) {
    if (!closed)
      errorBody.textContent =
        reason instanceof Error ? reason.message : errorMessage(reason);
  }
  function render() {
    if (closed) return;
    if (options.overview) {
      breakdownBody.replaceChildren();
      recordingBody.replaceChildren();
      pricingBody.replaceChildren(
        node('p', 'Load a usage report to estimate costs.'),
      );
    }
    reportBody.replaceChildren();
    const status = options.overview ? liveStatus : reportBody;
    if (options.overview) liveStatus.replaceChildren();
    status.append(
      node(
        'p',
        state.loading
          ? 'Reading usage…'
          : state.stale
            ? 'Last snapshot · refresh or reconnect to reconcile'
            : 'Live usage snapshot',
      ),
    );
    if (state.error) {
      const failure = node('p', state.error);
      failure.setAttribute('role', 'alert');
      status.append(failure);
    }
    if (!state.report) {
      status.append(
        node('p', 'No usage measurement loaded. Select a scope and refresh.'),
      );
      return;
    }
    if (!state.report.health.capture_enabled)
      status.append(
        node(
          'p',
          'Usage recording is disabled on this server. Ask an administrator to enable accounting capture. Existing records remain readable.',
        ),
      );
    if (state.report.totals.calls === '0')
      status.append(
        node(
          'p',
          state.report.health.capture_enabled
            ? 'No recorded calls in this scope and date range.'
            : 'No recorded calls. These totals do not measure past activity while recording was disabled.',
        ),
      );
    if (options.overview) {
      renderOverview();
      more.hidden = !groupCursor;
      if (state.report.filters.filter.group_by?.includes('run')) tree();
      if (activeView === 'calls' && !auditLoaded && !auditPending)
        background(audit());
      return;
    }
    // The detailed view and TUI share exact decimal wording and accounting coverage.
    const body = reportBody;
    for (const line of usageText(state.report, price).split('\n')) {
      const paragraph = node('p', line);
      if (line.startsWith('Reference ' + price.label + ':')) {
        paragraph.className = 'usage-reference-total';
        paragraph.setAttribute('aria-label', 'Reference cost comparison');
      }
      body.append(paragraph);
    }
    if (price.source) {
      const source = node('a', 'Pricing source');
      source.href = price.source;
      source.target = '_blank';
      source.rel = 'noreferrer';
      body.append(source);
    }
    const h = state.report.health;
    body.append(
      node(
        'p',
        `Tracking began ${String(h.tracking_started_at ?? 'not recorded')} · last projection ${String(h.last_projected_at ?? 'not recorded')} · unpriced attempts ${state.report.totals.unknown_cost_attempts}`,
      ),
    );
    for (const key of [
      'journal_problem',
      'projection_problem',
      'projection_conflict',
      'lost_terminal_events',
    ] as const)
      if (h[key] !== undefined)
        body.append(node('p', `${key.replace(/_/g, ' ')}: ${String(h[key])}`));
    body.append(
      node(
        'p',
        'Booked estimates retain prices captured at admission. Included, zero-rate and unpriced modes are shown in attempt details.',
      ),
    );
    if (state.report.filters.filter.group_by?.includes('run')) tree();
    more.hidden = !groupCursor;
  }
  function tokens(totals: UsageTotals, direction: 'input' | 'output'): string {
    return totals[`${direction}_tokens_known`] === '0' &&
      totals.attempts !== '0'
      ? 'Not reported'
      : BigInt(totals[`${direction}_tokens`]).toLocaleString();
  }
  function costs(totals: UsageTotals): string {
    return (
      Object.entries(totals.costs)
        .map(([currency, amount]) => `${currency} ${amount}`)
        .join(' · ') ||
      (totals.cost_complete ? 'No booked cost' : 'Unavailable')
    );
  }
  function readableDate(value: unknown): string {
    if (typeof value !== 'string' || !Number.isFinite(Date.parse(value)))
      return 'Not recorded';
    return (
      new Intl.DateTimeFormat(undefined, {
        dateStyle: 'medium',
        timeStyle: 'short',
        timeZone: 'UTC',
      }).format(new Date(value)) + ' UTC'
    );
  }
  function facts(
    entries: readonly (readonly [string, string])[],
  ): HTMLDListElement {
    const list = node('dl');
    list.className = 'usage-facts';
    for (const [name, value] of entries)
      list.append(node('dt', name), node('dd', value));
    return list;
  }
  function renderOverview() {
    const report = state.report!,
      t = report.totals,
      h = report.health;
    const summary = node('div');
    summary.className = 'usage-summary';
    for (const [name, value, note] of [
      [
        'Input tokens',
        tokens(t, 'input'),
        t.usage_complete ? 'Reported tokens' : 'Reported subtotal',
      ],
      [
        'Output tokens',
        tokens(t, 'output'),
        t.usage_complete ? 'Reported tokens' : 'Reported subtotal',
      ],
      [
        'Calls',
        BigInt(t.calls).toLocaleString(),
        `${t.attempts} attempts · ${t.active_calls} active`,
      ],
      [
        'Recorded cost estimate',
        costs(t),
        t.cost_complete
          ? 'Saved admission prices'
          : `${t.unknown_cost_attempts} unpriced ${t.unknown_cost_attempts === '1' ? 'attempt' : 'attempts'} · partial estimate`,
      ],
    ]) {
      const stat = node('div');
      stat.className = 'usage-stat';
      stat.append(
        node('span', name),
        node('strong', value),
        node('small', note),
      );
      summary.append(stat);
    }
    reportBody.append(summary);
    const coverage = node('p');
    coverage.className = 'usage-coverage';
    coverage.append(
      node(
        'span',
        `Recording began ${readableDate(h.tracking_started_at)}. ${h.historical_usage === 'not_imported' ? 'Earlier history is not imported. ' : ''}${t.usage_complete ? '' : 'Token totals are incomplete. '}`,
      ),
      button('View coverage', () => showView('recording')),
    );
    reportBody.append(coverage);
    const attribution = node(
      'p',
      report.filters.filter.scope === 'direct'
        ? 'Direct work only.'
        : 'Child work included. Inclusive totals already contain descendant usage.',
    );
    attribution.className = 'usage-note';
    reportBody.append(attribution);
    breakdownBody.replaceChildren();
    const table = node('table');
    table.className = 'usage-breakdown';
    table.setAttribute('aria-label', 'Usage breakdown');
    const head = node('thead'),
      headings = node('tr');
    for (const text of [
      'Work',
      'Input tokens',
      'Output tokens',
      'Recorded estimate',
    ])
      headings.append(node('th', text));
    head.append(headings);
    table.append(head);
    const rows = node('tbody');
    for (const row of report.groups) {
      const entry = node('tr');
      const name = (
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
        .map((v) => (v === null ? 'Unassigned' : String(v)))
        .join(' / ');
      for (const text of [
        name,
        tokens(row, 'input'),
        tokens(row, 'output'),
        costs(row) + (row.cost_complete ? '' : ' · incomplete'),
      ])
        entry.append(node('td', text));
      rows.append(entry);
    }
    table.append(rows);
    breakdownBody.append(
      report.groups.length
        ? table
        : node('p', 'No breakdown rows in this selection.'),
    );
    renderPricing();
    recordingBody.replaceChildren(
      facts([
        [
          'Token recording',
          h.capture_enabled ? 'On · automatic' : 'Disabled on this server',
        ],
        ['Recorded history', `Since ${readableDate(h.tracking_started_at)}`],
        [
          'Token coverage',
          t.usage_complete
            ? 'Complete'
            : `Incomplete · ${t.incomplete_attempts} incomplete attempts`,
        ],
        [
          'Price coverage',
          t.cost_complete
            ? 'Complete'
            : `${t.unknown_cost_attempts} unpriced attempts`,
        ],
        [
          'Processing',
          `${String(h.pending_events ?? 'Unknown')} pending events · ${String(h.projection_lag_millis ?? 'Unknown')} ms lag`,
        ],
        ['Last update', readableDate(h.last_projected_at ?? h.as_of)],
      ]),
    );
    const diagnostics = disclosure('Technical diagnostics');
    diagnostics.append(
      facts(
        (
          [
            'watermark',
            'historical_usage',
            'journal_problem',
            'projection_problem',
            'projection_conflict',
            'lost_terminal_events',
          ] as const
        )
          .filter((key) => h[key] !== undefined)
          .map((key) => [key.replace(/_/g, ' '), String(h[key])] as const),
      ),
    );
    recordingBody.append(diagnostics);
    if (
      h.projection_conflict === true ||
      (['journal_problem', 'projection_problem'] as const).some(
        (key) => h[key] && h[key] !== 'NONE',
      ) ||
      Number(h.lost_terminal_events ?? 0) > 0
    ) {
      const fault = node(
        'p',
        'Usage recording needs attention. Open Recording for diagnostics.',
      );
      fault.setAttribute('role', 'alert');
      reportBody.append(fault);
    }
  }
  function renderPricing() {
    if (!state.report) {
      pricingBody.replaceChildren(
        node('p', 'Load a usage report to estimate costs.'),
      );
      return;
    }
    const t = state.report.totals,
      estimate = referenceCost(t, price);
    const columns = node('div');
    columns.className = 'usage-price-grid';
    const recorded = node('div'),
      comparison = node('div');
    recorded.append(
      node('h3', 'Recorded cost estimate'),
      node('strong', costs(t)),
      node(
        'p',
        t.cost_complete
          ? 'Based on prices saved for these attempts.'
          : `${t.unknown_cost_attempts} ${t.unknown_cost_attempts === '1' ? 'attempt has' : 'attempts have'} no saved price. Any displayed cost is a known subtotal.`,
      ),
    );
    comparison.append(node('h3', 'Reference estimate'));
    const amount = node(
      'p',
      estimate.amount === undefined
        ? 'Unavailable · token usage was not reported'
        : `${estimate.currency} ${estimate.amount}`,
    );
    amount.className = 'usage-reference-total';
    amount.setAttribute('aria-label', 'Reference cost comparison');
    amount.setAttribute('aria-live', 'polite');
    comparison.append(
      amount,
      node(
        'p',
        `${price.label} · ${estimate.complete ? 'Complete token total' : 'Partial token subtotal · missing measurements excluded'}`,
      ),
      node(
        'p',
        `Rates per million tokens: input ${price.input}, output ${price.output} ${price.currency}. ${price.id === 'custom' ? 'Custom rates' : 'Checked ' + price.checked}.`,
      ),
    );
    const assumptions = disclosure('Comparison assumptions and rate source');
    assumptions.append(node('p', estimate.basis));
    if (price.source) {
      const source = node('a', 'Provider pricing source');
      source.href = price.source;
      source.target = '_blank';
      source.rel = 'noreferrer';
      assumptions.append(source);
    }
    comparison.append(assumptions);
    columns.append(recorded, comparison);
    pricingBody.replaceChildren(columns);
  }
  function syncProvider() {
    if (!options.overview) return;
    provider.value =
      price.id === 'custom' ? 'custom' : price.label.split(' · ')[0]!;
    const entries: readonly (readonly [string, string])[] =
      provider.value === 'custom'
        ? [['custom', 'Custom rates / Azure deployment']]
        : REFERENCE_PRICES.filter(
            (p) => p.label.split(' · ')[0] === provider.value,
          ).map(
            (p) => [p.id, p.label.split(' · ').slice(1).join(' · ')] as const,
          );
    preset.replaceChildren(
      ...entries.map(([value, text]) => {
        const option = node('option', text);
        option.value = value;
        return option;
      }),
    );
    preset.value = price.id;
  }
  function tree() {
    const section = node('section');
    section.setAttribute('aria-label', 'Agent run tree');
    section.append(
      node('h3', 'Agent run tree'),
      node(
        'p',
        'Rows are direct contributions. Open a run to read its inclusive subtree. No direct row on this page does not mean zero historical spend.',
      ),
    );
    const nodes = new Map<
      string,
      {
        parent: string | undefined;
        agent: unknown;
      }
    >();
    for (const row of state.report!.groups) {
      if (typeof row.run !== 'string') continue;
      const path = [
        row.run,
        ...(isList(row.ancestor_runs)
          ? row.ancestor_runs.filter(
              (id): id is string => typeof id === 'string',
            )
          : []),
      ];
      if (new Set(path).size !== path.length) continue;
      path.forEach((id, index) =>
        nodes.set(id, {
          parent: path[index + 1],
          agent: index === 0 ? row.agent : nodes.get(id)?.agent,
        }),
      );
    }
    function visit(parent: string | undefined, depth = 0): HTMLUListElement {
      const list = node('ul');
      if (depth > 256) return list;
      for (const [id, entry] of nodes)
        if (entry.parent === parent) {
          const item = node('li'),
            open = button(
              `${displayText(entry.agent ?? 'Ancestor run')} · ${id}`,
              () => {
                type.value = 'usage.run';
                target.value = id;
                target.disabled = false;
                scope.value = 'subtree';
                background(select());
              },
            );
          open.dataset.usageRun = id;
          item.append(open, visit(id, depth + 1));
          list.append(item);
        }
      return list;
    }
    section.append(visit(undefined));
    if (state.report!.cursor)
      section.append(
        node('p', 'Partial tree page; more groups are available.'),
      );
    reportBody.append(section);
  }
  function query(): {
    type: UsageReportType;
    filter: UsageFilter;
  } {
    const selected = type.value as UsageReportType,
      key = selected.slice(6);
    return {
      type: selected,
      filter: {
        ...usageRange(Number(days.value)),
        scope: scope.value as 'direct' | 'subtree',
        group_by:
          group.value === 'run'
            ? ['run', 'agent']
            : [
                isOneOf(group.value, [
                  'model',
                  'agent',
                  'run',
                  'operation',
                  'pool',
                  'project',
                  'day',
                ] as const)
                  ? group.value
                  : (() => {
                      throw new Error('Invalid usage dimension');
                    })(),
              ],
        ...([
          'conversation',
          'project',
          'agent',
          'run',
          'orchestration',
        ].includes(key)
          ? { [key]: target.value.trim() }
          : {}),
        limit: 100,
      },
    };
  }
  async function select() {
    ++epoch;
    lastSnapshot = '';
    ++page;
    auditCursor = null;
    auditLoaded = false;
    auditPending = false;
    callStatus.textContent = 'Load usage before reading calls.';
    groupCursor = null;
    more.hidden = true;
    auditNext.hidden = true;
    auditBody.replaceChildren();
    countBody.textContent = '';
    errorBody.textContent = '';
    selection = query();
    const retained =
      state.report &&
      usageMatches(state.report, selection.type, selection.filter)
        ? state.report
        : undefined;
    state = {
      ...(retained ? { report: retained } : {}),
      revision: -1,
      stale: true,
      loading: true,
    };
    render();
    try {
      await options.select(selection.type, selection.filter);
    } catch (reason) {
      error(reason);
    }
  }
  function setTarget() {
    const key = type.value.slice(6);
    target.disabled = ![
      'conversation',
      'project',
      'agent',
      'run',
      'orchestration',
    ].includes(key);
    target.closest('label')!.hidden = target.disabled;
    target.setAttribute('aria-label', key + ' target');
    target.value = '';
    if (key === 'conversation') target.value = options.conversation?.() ?? '';
    if (key === 'project') target.value = options.project ?? '';
    if (key === 'agent') target.value = options.agent?.() ?? '';
  }
  setTarget();
  for (const control of [type, target, days, scope, group])
    control.addEventListener('change', () => {
      if (control === type) setTarget();
      background(select());
    });
  function comparison() {
    custom.hidden = preset.value !== 'custom';
    try {
      const next =
        preset.value === 'custom'
          ? {
              id: 'custom',
              label: 'Custom reference',
              input: input.value,
              output: output.value,
              currency: 'USD',
              checked: new Date().toISOString().slice(0, 10),
            }
          : referencePrice(preset.value);
      referenceCost(state.report?.totals ?? EMPTY, next);
      price = next;
      errorBody.textContent = '';
      try {
        options.storage?.setItem(
          'plowshare.reference-price.v1',
          JSON.stringify(price),
        );
      } catch {
        /* Preference persistence does not prevent comparison. */
      }
      render();
    } catch (reason) {
      error(reason);
    }
  }
  provider.addEventListener('change', () => {
    const choice =
      provider.value === 'custom'
        ? 'custom'
        : REFERENCE_PRICES.find(
            (p) => p.label.split(' · ')[0] === provider.value,
          )!.id;
    const entries =
      provider.value === 'custom'
        ? [['custom', 'Custom rates / Azure deployment']]
        : REFERENCE_PRICES.filter(
            (p) => p.label.split(' · ')[0] === provider.value,
          ).map((p) => [p.id, p.label.split(' · ').slice(1).join(' · ')]);
    preset.replaceChildren(
      ...entries.map(([value, text]) => {
        const option = node('option', text);
        option.value = value!;
        return option;
      }),
    );
    preset.value = choice;
    comparison();
  });
  for (const control of [preset, input, output])
    control.addEventListener('change', comparison);
  let auditRequest = 0;
  async function audit(next = false) {
    const stamp = epoch,
      request = ++auditRequest,
      resolved = state.report?.filters.filter;
    auditPending = true;
    const { cursor: _groupCursor, ...filter } = resolved ?? {};
    try {
      callStatus.textContent = 'Reading calls…';
      if (!resolved) throw new Error('Load a usage report first.');
      const payload = await options.read('usage.calls', {
        ...filter,
        ...(next && auditCursor ? { cursor: auditCursor } : {}),
        limit: 20,
      });
      if (!('calls' in payload)) throw new Error('Expected a call audit page.');
      if (closed || stamp !== epoch || request !== auditRequest) return;
      auditLoaded = true;
      auditCursor = typeof payload.cursor === 'string' ? payload.cursor : null;
      auditNext.hidden = !auditCursor;
      auditBody.replaceChildren();
      for (const item of payload.calls) {
        const details = disclosure(
          `${displayText(item.agent_name ?? 'No agent')} · ${String(item.wire_model)} · ${String(item.operation).toLowerCase().replace(/_/g, ' ')} · ${String(item.lifecycle).toLowerCase()}`,
        );
        details.append(
          facts([
            ['Call', String(item.call_id)],
            ['Run', displayText(item.run_id ?? 'None')],
            ['Model', String(item.wire_model)],
            ['Operation', String(item.operation)],
            ['Status', String(item.lifecycle)],
          ]),
        );
        const attempts = node('div');
        function showAttempts(values: readonly UsageAttempt[]) {
          attempts.replaceChildren();
          for (const attempt of values) {
            const detail = disclosure(
              `Attempt ${String(attempt.attempt_number)} · ${String(attempt.usage_coverage)} · ${String(attempt.cost_kind)}`,
            );
            detail.append(
              facts([
                [
                  'Input tokens',
                  displayText(attempt.input_tokens ?? 'Not reported'),
                ],
                [
                  'Output tokens',
                  displayText(attempt.output_tokens ?? 'Not reported'),
                ],
                [
                  'Cost',
                  attempt.cost_amount == null
                    ? 'Unavailable'
                    : `${String(attempt.cost_currency)} ${displayText(attempt.cost_amount)}`,
                ],
              ]),
            );
            attempts.append(detail);
          }
        }
        showAttempts(item.attempts);
        details.append(attempts);
        const raw = disclosure('Raw server record'),
          body = node('pre', JSON.stringify(item, null, 2));
        raw.append(body);
        details.append(raw);
        if (item.attempts_truncated) {
          let cursor = item.attempt_cursor;
          const moreAttempts = button('Next attempt page', () => {
            background(
              (async () => {
                try {
                  const result = await options.read('usage.calls', {
                    ...filter,
                    call: item.call_id,
                    ...(cursor === null ? {} : { attempt_cursor: cursor }),
                    limit: 20,
                  });
                  if (!('attempts' in result))
                    throw new Error('Expected an attempt audit page.');
                  if (closed || stamp !== epoch) return;
                  body.textContent = JSON.stringify(result, null, 2);
                  showAttempts(result.attempts);
                  cursor = result.cursor;
                  moreAttempts.hidden = !cursor;
                } catch (reason) {
                  error(reason);
                }
              })(),
            );
          });
          details.append(moreAttempts);
        }
        auditBody.append(details);
      }
      callStatus.textContent = payload.calls.length
        ? 'Calls in the selected scope. Open a call for attempts and details.'
        : 'No recorded calls in this selection.';
    } catch (reason) {
      if (!closed && stamp === epoch && request === auditRequest) {
        callStatus.textContent =
          'Could not read calls. Refresh calls to retry.';
        error(reason);
      }
    } finally {
      if (stamp === epoch && request === auditRequest) auditPending = false;
    }
  }
  async function nextGroups() {
    if (!state.report || !groupCursor) return;
    const stamp = epoch,
      nextPage = ++page;
    try {
      const report = await options.read(
        state.report.filters.type,
        decodePayload(state.report.filters.type, {
          ...state.report.filters.filter,
          cursor: groupCursor,
        }),
      );
      if (closed || stamp !== epoch || nextPage !== page) return;
      groupCursor = report.cursor;
      state = { ...state, report };
      render();
    } catch (reason) {
      error(reason);
    }
  }
  async function count() {
    const stamp = epoch;
    try {
      const conversation =
          countConversation.value.trim() ||
          (type.value === 'usage.conversation' ? target.value : ''),
        agent =
          countAgent.value.trim() ||
          (type.value === 'usage.agent' ? target.value : '');
      if (!conversation || !agent)
        throw new Error(
          'Enter a conversation and agent to count its next projection.',
        );
      const result = await options.read('conversation.context.count', {
          conversation,
          agent,
        }),
        count = result.count;
      if (closed || stamp !== epoch) return;
      countBody.textContent = `${displayText(count?.tokens ?? 'Unknown')} tokens · ${String(count?.basis)} · ${displayText(count?.model ?? 'no model')} · gaps ${JSON.stringify(count?.gaps)}`;
    } catch (reason) {
      error(reason);
    }
  }
  background(select());
  return {
    refresh: select,
    update(next) {
      if (
        closed ||
        (next.report &&
          selection &&
          !usageMatches(next.report, selection.type, selection.filter))
      )
        return;
      // Chat progress also emits desktop state. Preserve a paged breakdown until usage actually changes.
      const signature = JSON.stringify([
        next.subscription,
        next.revision,
        next.stale,
        next.loading,
        next.error,
        next.report?.health.watermark,
        next.report?.health.as_of,
      ]);
      if (signature === lastSnapshot) return;
      lastSnapshot = signature;
      state = next;
      ++page;
      groupCursor = next.report?.cursor ?? null;
      render();
    },
    destroy() {
      closed = true;
      ++epoch;
      root.replaceChildren();
    },
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
.usage-panel .usage-summary { display:grid; grid-template-columns:repeat(auto-fit,minmax(170px,1fr)); gap:14px; border:0; }
.usage-panel .usage-stat {display:flex;flex-direction:column;gap:6px;padding:18px;border:1px solid #8884;border-radius:10px;background:#ffffff03;}
.usage-panel .usage-breakdown {width:100%;border-collapse:collapse;margin:16px 0;text-align:left;}
.usage-panel .usage-breakdown th,.usage-panel .usage-breakdown td {padding:10px;border-bottom:1px solid #8884;overflow-wrap:anywhere;}
.usage-panel .usage-stat strong {font-size:24px;font-weight:600;line-height:1.4;overflow-wrap:anywhere;}
.usage-panel .usage-stat small {opacity:.7;font-size:12px;}
.usage-panel details { margin:.7rem 0; }
.usage-panel summary { cursor:pointer; overflow-wrap:anywhere; }
.usage-panel pre { overflow:auto; max-height:24rem; font-size:.75rem; }
.usage-panel [role=alert] { color:#c95839; }
.usage-panel [aria-label="Agent run tree"] ul { padding-left:.8rem; list-style:none; border-left:1px solid #8886; }
.usage-panel [data-usage-run] { text-align:left; overflow-wrap:anywhere; max-width:100%; }
.usage-panel [hidden] { display:none !important; }
.usage-panel.usage-workspace {padding:0; font-size:14px; line-height:1.5;}
.usage-workspace .usage-controls {align-items:center; gap:10px; margin:16px 0;}
.usage-workspace .usage-controls label {flex-direction:row; align-items:center; gap:6px; font-size:12px; color:#aaa79e;}
.usage-workspace .usage-controls button {margin-left:auto;}
.usage-workspace .usage-controls select {max-width:220px;}
.usage-workspace .usage-tabs {display:flex; flex-wrap:wrap; gap:24px; border-bottom:1px solid #34332c; margin:18px 0 24px;}
.usage-workspace .usage-tabs button {border:0; border-radius:0; padding:10px 0; background:transparent; color:#aaa79e;}
.usage-workspace .usage-tabs button[aria-selected=true] {color:#e5e3dd; box-shadow:inset 0 -2px #d6b875;}
.usage-workspace .usage-summary {grid-template-columns:repeat(4,minmax(0,1fr)); margin:18px 0 12px; padding:0 0 20px; gap:20px; border-bottom:1px solid #34332c;}
.usage-workspace .usage-stat {padding:0; border:0; border-radius:0; background:transparent; gap:6px; min-width:0;}
.usage-workspace .usage-stat > span {color:#aaa79e; font-size:12px;}
.usage-workspace .usage-stat strong {font-variant-numeric:tabular-nums; font-size:24px;}
.usage-workspace .usage-stat small {opacity:1; color:#aaa79e;}
.usage-workspace .usage-coverage,.usage-workspace .usage-note {font-size:12px; color:#aaa79e;}
.usage-workspace .usage-coverage button {border:0; background:transparent; color:#d6b875; padding:0 6px;}
.usage-workspace .usage-breakdown-controls,.usage-workspace .usage-call-controls {display:flex; align-items:center; flex-wrap:wrap; gap:12px; margin-top:24px;}
.usage-workspace .usage-breakdown-controls label {flex-direction:row; align-items:center; gap:8px; margin-left:auto; font-size:12px;}
.usage-workspace .usage-breakdown-controls h3,.usage-workspace .usage-call-controls h3 {margin:0;}
.usage-workspace .usage-call-controls > button:first-of-type {margin-left:auto;}
.usage-workspace .usage-breakdown th,.usage-workspace .usage-breakdown td {padding:12px 8px; vertical-align:top; border-color:#34332c;}
.usage-workspace .usage-breakdown th {font-size:12px; color:#aaa79e; font-weight:500;}
.usage-workspace .usage-breakdown td:not(:first-child),.usage-workspace .usage-breakdown th:not(:first-child) {text-align:right; font-variant-numeric:tabular-nums;}
.usage-workspace .usage-breakdown {table-layout:fixed;}
.usage-workspace .usage-breakdown th:first-child {width:35%;}
.usage-workspace .usage-reference-controls {align-items:start; margin:18px 0 24px;}
.usage-workspace .usage-reference-controls > label {min-width:150px;}
.usage-workspace .usage-reference-controls label {font-size:12px; color:#aaa79e;}
.usage-workspace .usage-reference-controls select,.usage-workspace .usage-reference-controls input {color:#e5e3dd;}
.usage-workspace [data-usage-custom] {display:flex; align-items:start; flex-wrap:wrap; gap:12px; width:100%;}
.usage-workspace [data-usage-custom] p {flex-basis:100%; margin:0; font-size:12px;}
.usage-workspace .usage-price-grid {display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:32px;}
.usage-workspace .usage-price-grid > div {min-width:0;}
.usage-workspace .usage-price-grid strong {font-size:24px; font-variant-numeric:tabular-nums; overflow-wrap:anywhere;}
.usage-workspace .usage-price-grid p {font-size:12px; color:#aaa79e;}
.usage-workspace .usage-price-grid .usage-reference-total {font-size:26px; color:#e5e3dd; padding:0; border:0; font-variant-numeric:tabular-nums;}
.usage-workspace .usage-facts {display:grid; grid-template-columns:minmax(120px,35%) minmax(0,1fr); gap:0; margin:16px 0;}
.usage-workspace .usage-facts dt,.usage-workspace .usage-facts dd {padding:12px 0; border-bottom:1px solid #34332c; overflow-wrap:anywhere;}
.usage-workspace .usage-facts dt {color:#aaa79e; padding-right:12px;}
.usage-workspace .usage-facts dd {margin:0;}
.usage-workspace summary {color:#d6b875; padding:8px 0;}
.usage-workspace [role=alert]:empty {display:none;}
@media(max-width:760px) {
  .usage-panel.usage-workspace .usage-summary {grid-template-columns:repeat(2,minmax(0,1fr));}
  .usage-workspace .usage-price-grid {grid-template-columns:1fr; gap:24px;}
  .usage-workspace .usage-controls button {margin-left:0;}
}
@media(max-width:420px) {
  .usage-workspace .usage-controls label {flex-direction:column; align-items:start;}
  .usage-workspace .usage-controls select {max-width:100%;}
  .usage-workspace .usage-controls input {width:100%;}
  .usage-workspace .usage-tabs {gap:18px;}
  .usage-workspace .usage-breakdown th,.usage-workspace .usage-breakdown td {padding:10px 4px; font-size:12px;}
}
`;
