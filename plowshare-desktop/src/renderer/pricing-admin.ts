import type { AdministrationPanel } from './admin-panel.ts';
import { background } from './events.ts';
import { decodeAdminCall } from '../admin-input.ts';
import { decodeReply } from 'plowshare-client-ts/operations/schema';
import { displayText } from 'plowshare-client-ts/binding/values';
import type { DesktopState, Reply, Request } from '../shared.ts';
import type { PricingEntry } from 'plowshare-client-ts/operations/conversation-replies';
import { commandProblem } from 'plowshare-client-ts/operations/commands';

/** Rate editor holds the displayed version so concurrent administrators cannot overwrite edits. */
export function installPricingAdmin(
  request: (value: Request) => Promise<Reply>,
  host: HTMLElement,
  back: () => void,
): AdministrationPanel {
  const panel = document.createElement('section');
  panel.id = 'pricing-admin-panel';
  panel.className = 'administration-panel';
  panel.hidden = true;
  panel.setAttribute('aria-labelledby', 'pricing-admin-title');
  panel.innerHTML = `<header><h2 id="pricing-admin-title">Server pricing</h2><button type="button" data-close>Back to accounts</button></header>
    <p>Rates are currency units per million tokens. Changes apply to future requests. Recorded costs and retries retain their original pricing.</p>
    <p data-error role="alert" hidden></p><p data-saved role="status"></p><button type="button" data-refresh>Refresh rates</button>
    <div class="admin-columns"><div data-prices></div><section><form data-edit hidden><h3 data-target></h3><p data-origin></p>
    <label>Billing mode<select name="mode"><option>TOKEN</option><option>INCLUDED</option><option>ZERO_RATE</option><option>UNPRICED</option></select></label>
    <label>Currency<input name="currency" maxlength="3" placeholder="USD"></label>
    <div data-token><label>Input per million<input name="input" inputmode="decimal"></label><label>Output per million<input name="output" inputmode="decimal"></label>
    <label>Cache read per million<input name="cacheRead" inputmode="decimal" placeholder="Optional"></label><label>Cache write per million<input name="cacheWrite" inputmode="decimal" placeholder="Optional"></label>
    <details data-advanced><summary>Advanced pricing: attempt fees and input tiers</summary><label>Fee per upstream attempt<input name="requestFee" inputmode="decimal" placeholder="Optional"></label>
    <label>Input tiers (JSON)<textarea name="tiers" rows="3" spellcheck="false" aria-describedby="pricing-tier-help"></textarea></label>
    <p id="pricing-tier-help">Optional array of increasing fromInputTokens thresholds and full rates. Each tier replaces the base rates.</p></details></div>
    <label>Rate source<input name="source" maxlength="512" placeholder="Operator or contract reference"></label><button type="submit">Save pricing</button></form>
    <details><summary>Startup configuration</summary><p>Durable overrides take precedence. A deployment's configured intervals remain visible below.</p><pre data-configured></pre></details>
    <button type="button" data-usage>Open usage statistics</button></section></div>`;
  host.append(panel);
  const get = <T extends HTMLElement>(selector: string) =>
    panel.querySelector<T>(selector)!;
  const field = (name: string) =>
    get<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(
      `[name="${name}"]`,
    );
  let state: DesktopState | undefined,
    generation = 0,
    busy = false;
  let rows: readonly PricingEntry[] = [],
    selected: PricingEntry | undefined;
  const error = (reason?: unknown) => {
    get('[data-error]').hidden = !reason;
    get('[data-error]').textContent =
      reason instanceof Error ? reason.message : displayText(reason ?? '');
  };
  function mode() {
    get('[data-token]').hidden = field('mode').value !== 'TOKEN';
  }
  function choose(row: PricingEntry) {
    selected = row;
    get('[data-edit]').hidden = false;
    get('[data-target]').textContent = `${row.billingRoute} / ${row.model}`;
    get('[data-origin]').textContent =
      `${row.origin} · Pools: ${row.pools.join(', ')}${row.updatedAt ? ' · Updated ' + new Date(row.updatedAt).toLocaleString() : ''}`;
    field('mode').value = row.card?.mode ?? 'UNPRICED';
    field('currency').value = row.card?.currency ?? '';
    for (const name of ['input', 'output', 'cacheRead', 'cacheWrite'] as const)
      field(name).value = row.card?.rates?.[name] ?? '';
    field('requestFee').value = row.card?.requestFee ?? '';
    field('source').value = row.card?.source ?? 'operator';
    field('tiers').value = JSON.stringify(
      row.card?.tiers.map((tier) => ({
        fromInputTokens: tier.fromInputTokens,
        rates: Object.fromEntries(
          Object.entries(tier.rates).filter(([, value]) => value !== null),
        ),
      })) ?? [],
      null,
      2,
    );
    get('[data-configured]').textContent = JSON.stringify(
      row.configured,
      null,
      2,
    );
    mode();
    for (const button of get(
      '[data-prices]',
    ).querySelectorAll<HTMLButtonElement>('button'))
      button.setAttribute(
        'aria-pressed',
        String(
          button.dataset.price ===
            JSON.stringify([row.billingRoute, row.model]),
        ),
      );
  }
  async function call<K extends 'admin.pricing.list' | 'admin.pricing.set'>(
    operation: K,
    payload?: Record<string, unknown>,
  ) {
    const turn = generation;
    const reply = await request({
      action: 'server-admin',
      ...decodeAdminCall(operation, payload),
    });
    if (
      turn !== generation ||
      panel.hidden ||
      !state?.connected ||
      !state.serverAdmin
    )
      throw new Error('Administration connection changed.');
    return decodeReply(operation, reply.administration);
  }
  async function run(work: () => Promise<void>) {
    if (busy) return;
    busy = true;
    const turn = generation;
    error();
    const controls = [
      ...panel.querySelectorAll<
        | HTMLButtonElement
        | HTMLInputElement
        | HTMLSelectElement
        | HTMLTextAreaElement
      >('button,input,select,textarea'),
    ];
    controls.forEach((node) => {
      if (!node.matches('[data-close]')) node.disabled = true;
    });
    try {
      await work();
    } catch (reason) {
      if (turn === generation && !panel.hidden) error(reason);
    } finally {
      if (turn === generation) {
        busy = false;
        controls.forEach((node) => {
          node.disabled = false;
        });
      }
    }
  }
  async function refresh() {
    rows = await call('admin.pricing.list');
    render();
  }
  function render() {
    const parent = get('[data-prices]');
    parent.replaceChildren();
    if (!rows.length) parent.textContent = 'No served models are configured.';
    for (const row of rows) {
      const button = document.createElement('button');
      button.type = 'button';
      button.dataset.price = JSON.stringify([row.billingRoute, row.model]);
      button.textContent = `${row.billingRoute} / ${row.model} · ${row.card?.mode ?? 'UNPRICED'}${row.card?.currency ? ' · ' + row.card.currency : ''}`;
      button.addEventListener('click', () => {
        error();
        get('[data-saved]').textContent = '';
        choose(row);
      });
      parent.append(button);
    }
    const current =
      rows.find(
        (row) =>
          row.billingRoute === selected?.billingRoute &&
          row.model === selected?.model,
      ) ?? rows[0];
    selected = undefined;
    get('[data-edit]').hidden = !current;
    if (current) choose(current);
  }
  get('[data-close]').addEventListener('click', back);
  get('[data-refresh]').addEventListener('click', () => {
    get('[data-saved]').textContent = '';
    background(run(refresh));
  });
  field('mode').addEventListener('change', mode);
  get<HTMLFormElement>('[data-edit]').addEventListener('submit', (event) => {
    event.preventDefault();
    if (!selected) return;
    get('[data-saved]').textContent = '';
    background(
      run(async () => {
        const payload: Record<string, unknown> = {
          billingRoute: selected!.billingRoute,
          model: selected!.model,
          expectedVersion: selected!.version,
          mode: field('mode').value,
        };
        if (field('currency').value.trim())
          payload['currency'] = field('currency').value.trim();
        if (field('source').value.trim())
          payload['source'] = field('source').value.trim();
        if (payload['mode'] === 'TOKEN') {
          payload['rates'] = Object.fromEntries(
            ['input', 'output', 'cacheRead', 'cacheWrite']
              .filter((name) => field(name).value.trim())
              .map((name) => [name, field(name).value.trim()]),
          );
          if (field('requestFee').value.trim())
            payload['requestFee'] = field('requestFee').value.trim();
          payload['tiers'] = JSON.parse(field('tiers').value || '[]');
        }
        const problem = commandProblem('admin.pricing.set', payload);
        if (problem) throw new Error(problem);
        const updated = await call('admin.pricing.set', payload);
        rows = rows.map((row) =>
          row.billingRoute === updated.billingRoute &&
          row.model === updated.model
            ? updated
            : row,
        );
        selected = updated;
        render();
        get('[data-saved]').textContent =
          'Pricing saved. Future requests use these rates; recorded history is unchanged.';
      }),
    );
  });
  get('[data-usage]').addEventListener('click', () => {
    close();
    document.querySelector<HTMLButtonElement>('#usage-open')?.click();
  });
  function close() {
    busy = false;
    panel.hidden = true;
    generation++;
    rows = [];
    selected = undefined;
    get('[data-prices]').replaceChildren();
    get<HTMLFormElement>('[data-edit]').reset();
    get('[data-edit]').hidden = true;
    get('[data-configured]').textContent = '';
    get('[data-saved]').textContent = '';
    error();
  }
  return {
    element: panel,
    open() {
      if (state?.connected && state.serverAdmin) {
        panel.hidden = false;
        background(run(refresh));
      }
    },
    close,
    update(next: DesktopState) {
      if (
        state &&
        (state.base !== next.base ||
          state.handle !== next.handle ||
          !next.connected ||
          !next.serverAdmin)
      ) {
        generation++;
        if (!panel.hidden) close();
      }
      state = next;
    },
  };
}
