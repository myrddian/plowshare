import { isList } from 'plowshare-client-ts/binding/values';
import {
  definitionFromProposal,
  type ScheduleDefinition,
} from 'plowshare-client-ts/operations/schedule-files';
import type { DesktopState, Request } from '../shared.ts';
import { escapeHtml as esc, markdownHtml } from './markdown.ts';
const identity = (value: unknown) => JSON.stringify(value);
let account = '';
let formKey = '';
let form: ScheduleDefinition | undefined;
let fileProject = '',
  fileSource: 'server' | 'workspace' = 'server';
let draft = {
  text: '',
  zone: Intl.DateTimeFormat().resolvedOptions().timeZone,
  destination: '',
};
const prose = (value: string) =>
  `<div class="markdown-body">${markdownHtml(value)}</div>`;
export function rememberSchedule(container: HTMLElement) {
  if (form && container.querySelector('#schedule-action-kind')) {
    const read = (id: string) =>
      container.querySelector<
        HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement
      >(`#${id}`)?.value ?? '';
    const kind = read(
      'schedule-action-kind',
    ) as ScheduleDefinition['action']['kind'];
    const target = read(
      'schedule-target-kind',
    ) as ScheduleDefinition['target']['kind'];
    form = {
      ...form,
      cron: read('schedule-cron'),
      zone: read('schedule-time-zone'),
      action: {
        kind,
        agent: read('schedule-agent'),
        name: kind === 'agent' ? null : read('schedule-command'),
        input: read('schedule-input'),
        mode:
          kind === 'skill' && read('schedule-mode')
            ? (read('schedule-mode') as ScheduleDefinition['action']['mode'])
            : null,
      },
      target: {
        kind: target,
        project:
          target === 'conversation' || read('schedule-route')
            ? null
            : read('schedule-target-project') || null,
        conversation:
          target === 'conversation'
            ? read('schedule-conversation') || null
            : null,
        to:
          target === 'message' && !read('schedule-route')
            ? read('schedule-to') || null
            : null,
        route: target === 'message' ? read('schedule-route') || null : null,
      },
    };
    for (const node of container.querySelectorAll<HTMLElement>(
      '[data-schedule-command]',
    ))
      node.hidden = kind === 'agent';
    for (const node of container.querySelectorAll<HTMLElement>(
      '[data-schedule-mode]',
    ))
      node.hidden = kind !== 'skill';
    for (const node of container.querySelectorAll<HTMLElement>(
      '[data-schedule-conversation]',
    ))
      node.hidden = target !== 'conversation';
    for (const node of container.querySelectorAll<HTMLElement>(
      '[data-schedule-message]',
    ))
      node.hidden = target !== 'message';
    for (const node of container.querySelectorAll<HTMLElement>(
      '[data-schedule-target-project]',
    ))
      node.hidden = target === 'conversation';
    fileProject = read('schedule-file-project');
    fileSource =
      read('schedule-file-source') === 'workspace' ? 'workspace' : 'server';
  }
  const text = container.querySelector<HTMLTextAreaElement>('#schedule-text');
  if (text)
    draft = {
      text: text.value,
      zone: container.querySelector<HTMLInputElement>('#schedule-zone')!.value,
      destination: container.querySelector<HTMLSelectElement>(
        '#schedule-destination',
      )!.value,
    };
}
function definitionForm(
  state: DesktopState,
  definition: ScheduleDefinition,
  key: string,
  project: string | null,
  source: 'server' | 'workspace',
  existing = false,
) {
  if (formKey !== key) {
    formKey = key;
    form = definition;
    fileProject = project ?? '';
    fileSource = source;
  }
  const current = form!;
  const field = (id: string, label: string, value: string, placeholder = '') =>
    `<label>${label}<input id="${id}" value="${esc(value)}" placeholder="${esc(placeholder)}"></label>`;
  const options = (
    values: readonly string[],
    selected: string,
    empty = 'Same project',
  ) =>
    values
      .map(
        (value) =>
          `<option value="${esc(value)}" ${selected === value ? 'selected' : ''}>${esc(value || empty)}</option>`,
      )
      .join('');
  const projects = ['', ...state.projects.map((row) => row.name)];
  const commands = [
    ...new Set(
      Object.values(state.agents)
        .flat()
        .flatMap((agent) => agent.commands ?? [])
        .map((command) => command.name),
    ),
  ];
  return `<section class="run-question"><h3>Timing</h3>${field('schedule-cron', 'Cron (six fields, seconds first)', current.cron)}${field('schedule-time-zone', 'Time zone', current.zone)}<h3>Action and destination</h3>
    <label>Run<select id="schedule-action-kind">${options(['agent', 'skill', 'orchestration'], current.action.kind)}</select></label>
    ${field('schedule-agent', 'Agent or bot', current.action.agent)}
    <label data-schedule-command ${current.action.kind === 'agent' ? 'hidden' : ''}>Skill or orchestration name<input id="schedule-command" list="schedule-commands" value="${esc(current.action.name ?? '')}"></label><datalist id="schedule-commands">${commands.map((name) => `<option value="${esc(name)}">`).join('')}</datalist>
    <label data-schedule-mode ${current.action.kind !== 'skill' ? 'hidden' : ''}>Skill context mode<select id="schedule-mode">${options(['', 'INHERITED', 'SUMMARISED', 'NEW', 'DIRECT'], current.action.mode ?? '', 'Use declared mode')}</select></label>
    <label>Task or command input<textarea id="schedule-input" maxlength="16000">${esc(current.action.input)}</textarea></label>
    <label>Destination<select id="schedule-target-kind">${options(['mailbox', 'conversation', 'message'], current.target.kind)}</select></label>
    <label data-schedule-target-project ${current.target.kind === 'conversation' ? 'hidden' : ''}>Destination project<select id="schedule-target-project">${options(projects, current.target.project ?? '')}</select></label>
    <div data-schedule-conversation ${current.target.kind !== 'conversation' ? 'hidden' : ''}>${field('schedule-conversation', 'Conversation ID', current.target.conversation ?? '')}</div>
    <div data-schedule-message ${current.target.kind !== 'message' ? 'hidden' : ''}>${field('schedule-to', 'Message recipient', current.target.to ?? '', 'Agent, bot or instance address')}
    ${field('schedule-route', 'Named message route', current.target.route ?? '')}</div>
    <h3>Schedule file</h3><label>Source project<select id="schedule-file-project" ${existing ? 'disabled' : ''}>${options(projects, fileProject, 'Global')}</select></label>
    <label>Definitions folder<select id="schedule-file-source" ${existing ? 'disabled' : ''}>${options(['server', 'workspace'], fileSource)}</select></label>
    <p>Server definitions use schedules/. A rooted workspace uses .plowshare/schedules/. Project managers can edit the folder; the registered owner supplies execution authority.</p>
    <p>Named routes supply their project and recipient. Skills keep their declared context mode, or require an explicit mode when none is declared.</p></section>`;
}
export function scheduledPanel(state: DesktopState, selected: string) {
  const nextAccount = JSON.stringify([state.base, state.handle]);
  if (account !== nextAccount) {
    account = nextAccount;
    draft = {
      text: '',
      zone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      destination: '',
    };
    formKey = '';
    form = undefined;
  }
  const value = state.activity.schedules,
    disabled = !state.connected || value?.busy ? 'disabled' : '';
  let chosen: [string, string] | undefined;
  try {
    chosen = selection(selected);
  } catch {
    /* Unreadable drafts have no selected target. */
  }
  const schedule =
    chosen?.[0] === 'schedule'
      ? value?.schedules.find((row) => row.name === chosen[1])
      : undefined;
  const trigger =
    chosen?.[0] === 'trigger'
      ? value?.triggers.find((row) => row.name === chosen[1])
      : undefined;
  const common = `<p class="run-notice" role="status">${esc(value?.notice ?? '')}</p>`;
  let html = common;
  if (schedule || trigger) {
    const row = (schedule ?? trigger)!,
      kind = schedule ? 'schedule' : 'trigger';
    html += `<header><h2>${esc(row.name)}</h2><p>${kind} · ${row.paused ? 'Paused' : 'Active'}</p></header>`;
    html += schedule
      ? `<dl><dt>Cron</dt><dd>${esc(schedule.cron)}</dd><dt>Time zone</dt><dd>${esc(schedule.zone)}</dd><dt>Next fire</dt><dd>${esc(schedule.nextFireAt)}</dd><dt>Event</dt><dd>${esc(schedule.emits)}</dd></dl>`
      : `<p>Agent ${esc(trigger!.agent)} · ${esc(trigger!.project ?? trigger!.conversation ?? 'Global → Mailbox')}</p>${prose(trigger!.task)}<p>Event ${esc(trigger!.event)} · queue limit ${trigger!.queueCap}${trigger!.maxModelCalls === null ? '' : ` · call limit ${trigger!.maxModelCalls}`}${trigger!.maxTurns === null ? '' : ` · turn limit ${trigger!.maxTurns}`}</p>`;
    html += `<div class="run-actions"><button data-schedule-pause="${!row.paused}" ${disabled}>${row.paused ? 'Resume' : 'Pause'}</button></div><details><summary>Remove this ${kind}</summary><p>${schedule ? 'This removes the schedule. File-backed schedules also remove their file and trigger.' : 'This removes the trigger and refuses its waiting firings. File-backed work also removes its schedule file.'}</p><button data-schedule-remove ${disabled}>Remove ${kind}</button></details>`;
    const file = value?.files?.find((file) => file.internalName === row.name);
    if (file)
      html += `<p>File ${esc(file.path)} · ${esc(file.status)}${file.error ? ` · ${esc(file.error)}` : ''}</p>`;
    if (file?.definition)
      html +=
        definitionForm(
          state,
          file.definition,
          identity(file),
          file.project,
          file.source,
          true,
        ) +
        `<button id="schedule-file-update" ${disabled}>Save changes to schedule file</button>`;
    if (trigger)
      html += `<details><summary>Emit this event now</summary><p>Emitting ${esc(trigger.event)} can activate every matching trigger across your account.</p><button data-schedule-fire ${disabled}>Emit event ${esc(trigger.event)}</button></details>`;
    html += `<section><h3>Recent firings</h3>${
      (value?.firings ?? [])
        .filter((firing) =>
          schedule
            ? firing.schedule === schedule.name
            : firing.trigger === trigger!.name,
        )
        .map(
          (firing) =>
            `<article class="activity-message"><div class="eyebrow">${esc(firing.status)} · ${esc(firing.arrivedAt)}</div><p>${esc(firing.event)} · ${esc(firing.target ?? '')}</p>${firing.reason ? prose(firing.reason) : ''}${firing.jobId ? `<p>Job ${esc(firing.jobId)}</p>` : ''}</article>`,
        )
        .join('') ||
      '<p>No matching firings in the 100 most recent account firings.</p>'
    }</section>`;
  } else {
    html += `${value?.proposal ? '<details><summary>Change schedule description</summary>' : ''}<header><h2>New scheduled work</h2><p>Describe when to run and what to do. Review the server's proposal before saving.</p></header><div class="run-question"><label>Description<textarea id="schedule-text" maxlength="16000" ${disabled}>${esc(draft.text)}</textarea></label><label>Time zone<input id="schedule-zone" value="${esc(draft.zone)}" maxlength="128" ${disabled}></label><label>Destination<select id="schedule-destination" ${disabled}><option value="">Global → Mailbox</option>${state.projects
      .map((row) => {
        const key = JSON.stringify(['project', row.name]);
        return `<option value="${esc(key)}" ${key === draft.destination ? 'selected' : ''}>${esc(row.name)} → Mailbox</option>`;
      })
      .join('')}${state.conversations
      .map((row) => {
        const key = JSON.stringify(['conversation', row.id]);
        return `<option value="${esc(key)}" ${key === draft.destination ? 'selected' : ''}>Conversation: ${esc(row.title || row.id)}</option>`;
      })
      .join(
        '',
      )}</select></label><button id="schedule-preview" ${disabled || value?.previewing ? 'disabled' : ''}>${value?.previewing ? 'Reading…' : 'Preview schedule'}</button><p class="activity-footnote">Preview asks the server to interpret your description. It saves nothing.</p></div>${value?.proposal ? '</details>' : ''}`;
    if (value?.previewError)
      html += `<p class="error-banner">${esc(value.previewError)}</p>`;
    const proposal = value?.proposal;
    if (proposal)
      html +=
        definitionForm(
          state,
          definitionFromProposal(proposal),
          identity(proposal),
          value?.sourceProject ?? proposal.project,
          'server',
        ) +
        `<section class="schedule-proposal"><h3>Review proposal</h3>${prose(proposal.when)}<p>Agent ${esc(proposal.agent)} · ${esc(proposal.project ?? proposal.conversation ?? 'Global → Mailbox')}</p>${prose(proposal.task)}<dl><dt>Cron</dt><dd>${esc(proposal.cron)}</dd><dt>Time zone</dt><dd>${esc(proposal.zone)}</dd><dt>Schedule</dt><dd>${esc(proposal.names.schedule)}</dd><dt>Trigger</dt><dd>${esc(proposal.names.trigger)}</dd><dt>Event</dt><dd>${esc(proposal.names.event)}</dd></dl><h4>Server-computed next fire times</h4><ul>${proposal.nextFires.map((time) => `<li>${esc(time)}</li>`).join('')}</ul><p>Saving writes the schedule JSON. The server reconciles its schedule and trigger together and monitors later edits.</p><button id="schedule-save" ${disabled}>Save schedule file</button></section>`;
  }
  html += `<details><summary>Monitored folders and file status</summary><label>Project<select id="schedule-sync-project"><option value="">Global</option>${state.projects.map((row) => `<option value="${esc(row.name)}">${esc(row.name)}</option>`).join('')}</select></label><label>Definitions folder<select id="schedule-sync-source"><option value="server">Server definitions</option><option value="workspace">Rooted workspace</option></select></label><button id="schedule-sync" ${disabled}>Register and sync folder</button><p>Register a folder once to monitor JSON files you create yourself. Workspace folders require the owner's connected file provider.</p>${(value?.files ?? []).map((file) => `<article><strong>${esc(file.name)}</strong><p>${esc(file.project ?? 'Global')} · ${esc(file.source)} · ${esc(file.path)} · ${esc(file.status)}</p>${file.error ? `<p>${esc(file.error)}</p>` : ''}</article>`).join('') || '<p>No schedule files registered.</p>'}</details>`;
  return html;
}
export function scheduledAction(
  state: DesktopState,
  selected: string,
  target: HTMLElement,
): Request | undefined {
  if (target.closest('#schedule-sync')) {
    const container = target.closest('#activity-detail');
    const project = container?.querySelector<HTMLSelectElement>(
      '#schedule-sync-project',
    )?.value;
    const source =
      container?.querySelector<HTMLSelectElement>('#schedule-sync-source')
        ?.value === 'workspace'
        ? 'workspace'
        : 'server';
    return { action: 'schedule-sync', source, ...(project ? { project } : {}) };
  }
  if (target.closest('#schedule-preview')) {
    let destination: [string, string] | undefined;
    try {
      destination = selection(draft.destination);
    } catch {
      /* Unreadable drafts have no selected target. */
    }
    return {
      action: 'schedule-preview',
      text: draft.text,
      zone: draft.zone,
      ...(destination?.[0] === 'project'
        ? { project: destination[1] }
        : destination?.[0] === 'conversation'
          ? { conversation: destination[1] }
          : {}),
    };
  }
  if (target.closest('#schedule-save') && state.activity.schedules?.proposal)
    return {
      action: 'schedule-save',
      identity: identity(state.activity.schedules.proposal),
      ...(form ? { definition: form } : {}),
      source: fileSource,
      ...(fileProject ? { project: fileProject } : {}),
    };
  let chosen: [string, string] | undefined;
  try {
    chosen = selection(selected);
  } catch {
    /* Unreadable drafts have no selected target. */
  }
  if (!chosen || (chosen[0] !== 'schedule' && chosen[0] !== 'trigger')) return;
  const file = state.activity.schedules?.files?.find(
    (file) => file.internalName === chosen[1],
  );
  if (target.closest('#schedule-file-update') && file && form)
    return {
      action: 'schedule-file-save',
      name: file.name,
      definition: form,
      source: file.source,
      ...(file.project ? { project: file.project } : {}),
      overwrite: true,
      identity: identity(file),
    };
  const kind = chosen[0],
    row = (
      kind === 'schedule'
        ? state.activity.schedules?.schedules
        : state.activity.schedules?.triggers
    )?.find((row) => row.name === chosen[1]);
  if (!row) return;
  if (
    target.closest('[data-schedule-pause]') ||
    target.closest('[data-schedule-remove]')
  )
    return {
      action: 'schedule-change',
      kind,
      name: row.name,
      identity: identity(row),
      ...(target.closest('[data-schedule-pause]')
        ? { paused: !row.paused }
        : {}),
    };
  if (kind === 'trigger' && target.closest('[data-schedule-fire]'))
    return {
      action: 'schedule-fire',
      trigger: row.name,
      identity: identity(row),
    };
}

function selection(text: string): [string, string] {
  const value: unknown = JSON.parse(text);
  if (
    !isList(value) ||
    value.length !== 2 ||
    typeof value[0] !== 'string' ||
    typeof value[1] !== 'string'
  )
    throw new Error('Unreadable saved selection.');
  return [value[0], value[1]];
}
