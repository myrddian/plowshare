import type { DesktopState, Request } from '../shared.ts';
import { escapeHtml as esc, markdownHtml } from './markdown.ts';
const identity = (value: unknown) => JSON.stringify(value);
let account = '';
let draft = { text: '', zone: Intl.DateTimeFormat().resolvedOptions().timeZone, destination: '' };
const prose = (value: string) => `<div class="markdown-body">${markdownHtml(value)}</div>`;
export function rememberSchedule(container: HTMLElement) {
  const text = container.querySelector<HTMLTextAreaElement>('#schedule-text');
  if (text) draft = { text: text.value, zone: container.querySelector<HTMLInputElement>('#schedule-zone')!.value, destination: container.querySelector<HTMLSelectElement>('#schedule-destination')!.value };
}
export function scheduledPanel(state: DesktopState, selected: string) {
  const nextAccount = JSON.stringify([state.base, state.handle]);
  if (account !== nextAccount) { account = nextAccount; draft = { text: '', zone: Intl.DateTimeFormat().resolvedOptions().timeZone, destination: '' }; }
  const value = state.activity.schedules, disabled = !state.connected || value?.busy ? 'disabled' : '';
  let chosen: [string, string] | undefined; try { chosen = JSON.parse(selected); } catch {}
  const schedule = chosen?.[0] === 'schedule' ? value?.schedules.find(row => row.name === chosen![1]) : undefined;
  const trigger = chosen?.[0] === 'trigger' ? value?.triggers.find(row => row.name === chosen![1]) : undefined;
  const common = `<p class="run-notice" role="status">${esc(value?.notice ?? '')}</p>`;
  let html = common;
  if (schedule || trigger) {
    const row = (schedule ?? trigger)!, kind = schedule ? 'schedule' : 'trigger';
    html += `<header><h2>${esc(row.name)}</h2><p>${kind} · ${row.paused ? 'Paused' : 'Active'}</p></header>`;
    html += schedule ? `<dl><dt>Cron</dt><dd>${esc(schedule.cron)}</dd><dt>Time zone</dt><dd>${esc(schedule.zone)}</dd><dt>Next fire</dt><dd>${esc(schedule.nextFireAt)}</dd><dt>Event</dt><dd>${esc(schedule.emits)}</dd></dl>` : `<p>Agent ${esc(trigger!.agent)} · ${esc(trigger!.project ?? trigger!.conversation ?? 'Global → Inbox')}</p>${prose(trigger!.task)}<p>Event ${esc(trigger!.event)} · queue limit ${trigger!.queueCap}${trigger!.maxModelCalls === null ? '' : ` · call limit ${trigger!.maxModelCalls}`}${trigger!.maxTurns === null ? '' : ` · turn limit ${trigger!.maxTurns}`}</p>`;
    html += `<div class="run-actions"><button data-schedule-pause="${!row.paused}" ${disabled}>${row.paused ? 'Resume' : 'Pause'}</button></div><details><summary>Remove this ${kind}</summary><p>${schedule ? 'This removes the schedule. Its trigger remains.' : 'This removes the trigger and refuses its waiting firings. Its schedule remains.'}</p><button data-schedule-remove ${disabled}>Remove ${kind}</button></details>`;
    if (trigger) html += `<details><summary>Emit this event now</summary><p>Emitting ${esc(trigger.event)} can activate every matching trigger across your account.</p><button data-schedule-fire ${disabled}>Emit event ${esc(trigger.event)}</button></details>`;
    html += `<section><h3>Recent firings</h3>${(value?.firings ?? []).filter(firing => schedule ? firing.schedule === schedule.name : firing.trigger === trigger!.name).map(firing => `<article class="activity-message"><div class="eyebrow">${esc(firing.status)} · ${esc(firing.arrivedAt)}</div><p>${esc(firing.event)} · ${esc(firing.target ?? '')}</p>${firing.reason ? prose(firing.reason) : ''}${firing.jobId ? `<p>Job ${esc(firing.jobId)}</p>` : ''}</article>`).join('') || '<p>No matching firings in the 100 most recent account firings.</p>'}</section>`;
  } else {
    html += `<header><h2>New scheduled work</h2><p>Describe when to run and what to do. Review the server's proposal before saving.</p></header><div class="run-question"><label>Description<textarea id="schedule-text" maxlength="16000" ${disabled}>${esc(draft.text)}</textarea></label><label>Time zone<input id="schedule-zone" value="${esc(draft.zone)}" maxlength="128" ${disabled}></label><label>Destination<select id="schedule-destination" ${disabled}><option value="">Global → Inbox</option>${state.projects.map(row => { const key = JSON.stringify(['project', row.name]); return `<option value="${esc(key)}" ${key === draft.destination ? 'selected' : ''}>${esc(row.name)} → Inbox</option>`; }).join('')}${state.conversations.map(row => { const key = JSON.stringify(['conversation', row.id]); return `<option value="${esc(key)}" ${key === draft.destination ? 'selected' : ''}>Conversation: ${esc(row.title || row.id)}</option>`; }).join('')}</select></label><button id="schedule-preview" ${disabled || value?.previewing ? 'disabled' : ''}>${value?.previewing ? 'Reading…' : 'Preview schedule'}</button><p class="activity-footnote">Preview asks the server to interpret your description. It saves nothing.</p></div>`;
    if (value?.previewError) html += `<p class="error-banner">${esc(value.previewError)}</p>`;
    const proposal = value?.proposal;
    if (proposal) html += `<section class="schedule-proposal"><h3>Review proposal</h3>${prose(proposal.when)}<p>Agent ${esc(proposal.agent)} · ${esc(proposal.project ?? proposal.conversation ?? 'Global → Inbox')}</p>${prose(proposal.task)}<dl><dt>Cron</dt><dd>${esc(proposal.cron)}</dd><dt>Time zone</dt><dd>${esc(proposal.zone)}</dd><dt>Schedule</dt><dd>${esc(proposal.names.schedule)}</dd><dt>Trigger</dt><dd>${esc(proposal.names.trigger)}</dd><dt>Event</dt><dd>${esc(proposal.names.event)}</dd></dl><h4>Server-computed next fire times</h4><ul>${proposal.nextFires.map(time => `<li>${esc(time)}</li>`).join('')}</ul><p>Saving creates the schedule, then its trigger. A failure between them can leave only the schedule saved.</p><button id="schedule-save" ${disabled}>Save this schedule and trigger</button></section>`;
  }
  return html;
}
export function scheduledAction(state: DesktopState, selected: string, target: HTMLElement): Request | undefined {
  if (target.closest('#schedule-preview')) {
    let destination: [string, string] | undefined; try { destination = JSON.parse(draft.destination); } catch {}
    return { action: 'schedule-preview', text: draft.text, zone: draft.zone, ...(destination?.[0] === 'project' ? { project: destination[1] } : destination?.[0] === 'conversation' ? { conversation: destination[1] } : {}) };
  }
  if (target.closest('#schedule-save') && state.activity.schedules?.proposal) return { action: 'schedule-save', identity: identity(state.activity.schedules.proposal) };
  let chosen: [string,string] | undefined; try { chosen = JSON.parse(selected); } catch {}
  if (!chosen || (chosen[0] !== 'schedule' && chosen[0] !== 'trigger')) return;
  const kind = chosen[0], row = (kind === 'schedule' ? state.activity.schedules?.schedules : state.activity.schedules?.triggers)?.find(row => row.name === chosen![1]); if (!row) return;
  if (target.closest('[data-schedule-pause]') || target.closest('[data-schedule-remove]')) return { action: 'schedule-change', kind, name: row.name, identity: identity(row), ...(target.closest('[data-schedule-pause]') ? { paused: !row.paused } : {}) };
  if (kind === 'trigger' && target.closest('[data-schedule-fire]')) return { action: 'schedule-fire', trigger: row.name, identity: identity(row) };
}
