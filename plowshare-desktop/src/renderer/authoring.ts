import { errorMessage } from 'plowshare-client-ts/binding/values';
import {
  AUTHORING,
  authoringReady,
} from 'plowshare-client-ts/operations/authoring';
import { homeKey, type DesktopState } from '../shared.ts';
import { escapeHtml as esc } from './markdown.ts';
const select = (id: string) => document.querySelector<HTMLSelectElement>(id)!;
function options(id: string, rows: { value: string; label: string }[]) {
  const field = select(id),
    value = field.value,
    next = rows
      .map(
        (row) => `<option value="${esc(row.value)}">${esc(row.label)}</option>`,
      )
      .join('');
  if (field.innerHTML !== next) {
    field.innerHTML = next;
    if (rows.some((row) => row.value === value)) field.value = value;
  }
}
export function builderForm(state: DesktopState) {
  options('#builder-project', [
    { value: '', label: 'Choose project' },
    ...state.projects.map((row) => ({ value: row.name, label: row.name })),
  ]);
  const project = select('#builder-project').value;
  const rows = state.agents[homeKey(project)] ?? [];
  options(
    '#builder-agent',
    [...rows]
      .sort(
        (a, b) =>
          Number(b.served && b.orchestrations.includes(AUTHORING)) -
          Number(a.served && a.orchestrations.includes(AUTHORING)),
      )
      .map((row) => ({
        value: row.name,
        label: `${row.name}${row.served && row.orchestrations.includes(AUTHORING) ? '' : ' · unavailable or missing builder grant'}`,
      })),
  );
  const definitions =
    state.activity.definitions?.project === project
      ? state.activity.definitions.items
      : [];
  options('#builder-revision', [
    { value: '', label: 'New procedure' },
    ...definitions
      .filter((row) => row.name !== AUTHORING && row.served)
      .map((row) => ({ value: row.name, label: row.name })),
  ]);
  const folder = state.projectFolders?.find((row) => row.name === project);
  let reason =
    'Ready. The caller will start the builder and the person reviews installation.';
  try {
    if (state.activity.definitions?.error)
      throw new Error(state.activity.definitions.error);
    if (!state.connected || state.mode !== 'live')
      throw new Error('Connect to author a procedure.');
    authoringReady(
      project,
      folder?.files.status === 'ready',
      rows.find((row) => row.name === select('#builder-agent').value),
      definitions.find((row) => row.name === AUTHORING),
    );
  } catch (error) {
    reason = error instanceof Error ? error.message : errorMessage(error);
  }
  const attempt = state.authoring;
  const form = document.querySelector<HTMLDetailsElement>('#builder-form')!;
  if (
    attempt?.conversation &&
    attempt.status !== 'launching' &&
    form.dataset.reviewed !== attempt.conversation
  ) {
    form.dataset.reviewed = attempt.conversation;
    form.open = false;
  }
  document.querySelector('#builder-ready')!.textContent = reason;
  document.querySelector<HTMLButtonElement>('#builder-start')!.disabled =
    !reason.startsWith('Ready.') || attempt?.status === 'launching';
  document.querySelector<HTMLButtonElement>('#builder-prepare')!.disabled =
    !state.connected || !project;
  const linked =
    attempt?.conversation &&
    state.activity.runs.items.find(
      (row) =>
        row.definition === AUTHORING &&
        row.callerConversation === attempt.conversation,
    );
  const job =
    attempt?.conversation &&
    state.jobs.find((row) => row.conversation === attempt.conversation);
  if (attempt?.status === 'unknown' && !linked)
    document.querySelector<HTMLButtonElement>('#builder-start')!.disabled =
      true;
  if (
    ((!linked || ['running', 'asking', 'waiting'].includes(linked.state)) &&
      job &&
      ['starting', 'running', 'cancelling', 'unknown'].includes(job.status)) ||
    (linked && ['running', 'asking', 'waiting'].includes(linked.state))
  )
    document.querySelector<HTMLButtonElement>('#builder-start')!.disabled =
      true;
  document.querySelector('#builder-launch')!.textContent = attempt
    ? `${attempt.project} · ${attempt.status}${linked ? ` · ${linked.id}` : ' · waiting for the caller to start the builder; Refresh reads existing runs'}${job ? ` · caller ${job.status}${job.ending ? ` (${job.ending})` : ''}` : ''}${attempt.error ? ` · ${attempt.error}` : ''}`
    : '';
  document.querySelector<HTMLButtonElement>('#builder-caller')!.hidden =
    !attempt?.conversation;
}
export function builderPanel(state: DesktopState, id: string) {
  const messages = state.activity.details[id]?.value?.messages ?? [];
  const drafts = messages.flatMap((message) =>
    message.structure?.draft ? [message.structure.draft] : [],
  );
  const draft = drafts.at(-1),
    previous = drafts.at(-2);
  const structure = [...messages]
    .reverse()
    .find((message) => message.structure?.draft)?.structure;
  const conductor =
    state.activity.details[id]?.wire?.orchestration.conductorConversation;
  const history = conductor && state.history[conductor];
  const calls = new Map(
    history
      ? history.entries
          .flatMap((entry) => entry.calls ?? [])
          .map((call) => [call.id, call.name])
      : [],
  );
  const reports = history
    ? history.entries.filter(
        (entry) =>
          entry.toolCallId &&
          [
            'orchestration_validate',
            'orchestration_catalog',
            'orchestration_read',
            'orchestration_install',
          ].includes(calls.get(entry.toolCallId) ?? ''),
      )
    : [];
  const outputs = `<section><h3>Studio findings</h3>${history && history.error ? `<p class="error-banner">${esc(history.error)}</p>` : ''}${reports.map((entry) => `<article><h4>${esc(calls.get(entry.toolCallId!) ?? '')} · ${esc(entry.outcome ?? '')}</h4><pre>${esc(entry.text ?? 'Stored output unavailable in this excerpt.')}</pre>${entry.cut ? '<p>This output excerpt is truncated. Inspect the conductor trajectory for its stored result handle.</p>' : ''}</article>`).join('') || '<p>Read the conductor trajectory as it records catalog, source and validation results.</p>'}</section>`;
  const preview = structure?.questions
    .flatMap((question) => question.options)
    .find((option) => option.label === 'Install')?.preview;
  return `<section class="builder-review"><h2>Authoring review</h2><p>Stage completion text guides the model. Check and acceptance results in the run record are evidence. Review the complete source, grants and validation outcomes before choosing Install. After installation, callers still need a grant to start the new definition.</p>${draft ? `<h3>Exact draft · ${esc(draft.name)}</h3><p>${esc(draft.path)}</p>${draft.sha256 ? `<p class="activity-id">SHA-256 · ${esc(draft.sha256)}</p>` : ''}${preview ? `<h3>Server validation and revision preview</h3><pre>${esc(preview)}</pre><p>The server bounds this preview; the full reviewed source follows.</p>` : ''}<pre data-copy-source="${esc(draft.text)}">${esc(draft.text)}</pre>${previous ? `<details><summary>Previous reviewed draft · compare revision</summary><pre>${esc(previous.text)}</pre></details>` : ''}` : '<p>The interview and validation records appear below. A complete install draft appears here when Studio asks for your decision.</p>'}${outputs}</section>`;
}
