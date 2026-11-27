import { goalControls } from 'plowshare-client-ts/operations/goal-controls';
import type { GoalActivity, GoalActivityRequest } from '../shared.ts';
import { escapeHtml as esc } from './markdown.ts';

const words = (text: string) => text.toLowerCase().replaceAll('_', ' ');
const label = { pause: 'Pause', resume: 'Resume', cancel: 'Cancel' };

export function goalList(
  panel: GoalActivity | undefined,
  offline: boolean,
): string {
  if (!panel) return '';
  const disabled = panel.loading || offline ? 'disabled' : '';
  const rows = panel.page?.goals ?? [];
  return [
    ...rows.map(
      (row) =>
        `<button class="activity-row" data-goal="${esc(row.goal)}" aria-current="${row.goal === panel.selected?.goal}" ${disabled}><span><strong>${esc(row.objective ?? row.goal)}</strong><small>${esc(words(row.lifecycle))} · ${esc(words(row.state))}</small></span></button>`,
    ),
    panel.loading ? '<p role="status">Loading Goal Agents…</p>' : '',
    !panel.loading && panel.page && !rows.length
      ? '<p>No Goal Agents on this page.</p>'
      : '',
    panel.previous || panel.page?.next
      ? `<nav class="run-actions" aria-label="Goal Agent pages"><button data-goal-page="previous" ${panel.previous ? disabled : 'disabled'}>Previous page</button><button data-goal-page="next" ${panel.page?.next ? disabled : 'disabled'}>Next page</button></nav>`
      : '',
  ].join('');
}

const fact = (name: string, value: string) =>
  `<dt>${esc(name)}</dt><dd>${esc(value)}</dd>`;

export function goalDetail(
  panel: GoalActivity | undefined,
  offline: boolean,
): string {
  const status = panel?.selected;
  if (!status)
    return offline
      ? '<div class="activity-empty"><p>Connect to a server to open Goal Agents.</p></div>'
      : '<div class="activity-empty"><p>Choose a project and select a Goal Agent.</p></div>';
  const facts = [
    fact('Goal', status.goal),
    fact('Conversation', status.conversation),
    fact('Instance', status.instance),
    fact('Lifecycle', words(status.lifecycle)),
    fact('State', words(status.state)),
  ];
  if (status.reason) facts.push(fact('Reason', words(status.reason)));
  const title = `<header><h2>${esc(status.objective ?? 'Goal Agent')}</h2></header>`;
  if (!status.readable)
    return `${title}<dl class="goal-facts">${facts.join('')}</dl><p>Conversation content is restricted.</p>`;
  if (status.agent) facts.push(fact('Agent', status.agent));
  if (status.detail) facts.push(fact('Detail', status.detail));
  const scheduleCount =
    status.schedules.length + (status.moreSchedules ? '+' : '');
  const listenerCount =
    status.relayListeners.length + (status.moreRelayListeners ? '+' : '');
  facts.push(
    fact(
      'Wake sources',
      `${scheduleCount} schedules · ${listenerCount} Relay listeners`,
    ),
  );
  if (status.nextScheduleAt)
    facts.push(fact('Next schedule', status.nextScheduleAt));
  for (const schedule of status.schedules) {
    facts.push(
      fact(
        'Schedule',
        `${schedule.schedule} · ${schedule.paused ? 'paused' : (schedule.nextFireAt ?? 'no next occurrence')}`,
      ),
    );
  }
  for (const listener of status.relayListeners) {
    const parts = [listener.topic ?? listener.binding];
    if (listener.failure) parts.push(words(listener.failure));
    const batch = listener.pendingBatch;
    if (batch)
      parts.push(
        `${words(batch.state)} batch · ${batch.memberCount} events · deadline ${batch.deadline}`,
      );
    facts.push(fact('Relay listener', parts.join(' · ')));
  }
  const result = status.latestResult;
  const summary = result
    ? `<h3>Latest result · turn ${result.turn}</h3><p class="goal-result">${esc(result.summary)}</p><ul class="goal-evidence">${result.evidence.map((item) => `<li>${esc(item)}</li>`).join('')}</ul>`
    : '';
  const actions = goalControls(status);
  const pending = panel.pending;
  const disabled = pending || offline ? 'disabled' : '';
  return `${title}<dl class="goal-facts">${facts.join('')}</dl>${summary}
    <section class="goal-controls" aria-label="Goal Agent controls">
    <button data-goal-history ${offline ? 'disabled' : ''}>Open conversation history</button>
    ${actions.includes('resume') && !pending ? '<label>Max activations <input id="goal-activations" type="number" min="1" max="1000" step="1"></label><label>Total model calls <input id="goal-model-calls" type="number" min="1" max="1000000" step="1"></label>' : ''}
    ${actions.map((action) => `<button data-goal-control="${action}" ${disabled}>${label[action]}</button>`).join('')}
    ${pending === 'submitting' ? '<p role="status">Submitting control…</p>' : ''}
    ${pending === 'pending' ? '<p role="status">Control outcome pending. Refresh to check its receipt.</p>' : ''}
    ${pending === 'missing' ? `<p role="status">No receipt found. Review the current status before clearing the marker.</p><button data-goal-clear ${offline ? 'disabled' : ''}>Clear pending control</button>` : ''}
    </section>`;
}

function optionalNumber(target: HTMLElement, id: string): number | undefined {
  const input = target.closest('section')?.querySelector<HTMLInputElement>(id);
  if (!input || !input.reportValidity() || !input.value) return undefined;
  return input.valueAsNumber;
}

export function goalAction(
  panel: GoalActivity | undefined,
  target: HTMLElement,
): GoalActivityRequest | undefined {
  const row = target.closest<HTMLButtonElement>('[data-goal]');
  if (row?.dataset.goal && !row.disabled)
    return { action: 'goal-inspect', goal: row.dataset.goal };
  const page = target.closest<HTMLButtonElement>('[data-goal-page]');
  const direction = page?.dataset.goalPage;
  if (!page?.disabled && (direction === 'previous' || direction === 'next'))
    return { action: 'goal-page', direction };
  const status = panel?.selected;
  if (!status?.readable) return undefined;
  const position = { goal: status.goal, version: status.version };
  if (target.closest('[data-goal-history]'))
    return { action: 'goal-trajectory', ...position };
  if (target.closest('[data-goal-clear]'))
    return { action: 'goal-clear', ...position };
  const button = target.closest<HTMLButtonElement>('[data-goal-control]');
  const control = button?.dataset.goalControl;
  if (button?.disabled) return undefined;
  if (control === 'pause' || control === 'cancel')
    return { action: 'goal-control', ...position, control };
  if (control !== 'resume') return undefined;
  const maxActivations = optionalNumber(target, '#goal-activations');
  const maxModelCalls = optionalNumber(target, '#goal-model-calls');
  if (target.closest('section')?.querySelector('input:invalid'))
    return undefined;
  return {
    action: 'goal-control',
    ...position,
    control,
    limits: {
      ...(maxActivations === undefined ? {} : { maxActivations }),
      ...(maxModelCalls === undefined ? {} : { maxModelCalls }),
    },
  };
}
