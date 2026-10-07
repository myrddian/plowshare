import { background } from './events.ts';
import { errorMessage } from 'plowshare-client-ts/binding/values';
import { displayText } from 'plowshare-client-ts/binding/values';
import { installPaneResize } from './pane-resize.ts';
import { activeJob, stoppedJob } from '../shared.ts';
import type { DesktopState, Job } from '../shared.ts';
import {
  turnsOf,
  stepKey,
  callStanding,
} from 'plowshare-client-ts/operations/trajectory';
import type { Step } from 'plowshare-client-ts/operations/trajectory';
import { icon, mountIcons } from './icons.ts';
import type { Icon } from './icons.ts';
import { copyButton, installCopyControls } from './copy.ts';
import { installApprovalControls } from './approvals.ts';
import {
  stageSteps,
  stageSpans,
  navigableCall,
  type StageScope,
} from '../run-navigation.ts';

mountIcons();
installCopyControls();

const $ = <T extends HTMLElement = HTMLElement>(selector: string): T =>
  document.querySelector(selector)!;
const esc = (value: unknown) =>
  displayText(value ?? '').replace(
    /[&<>"']/g,
    (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[
        c
      ]!,
  );
interface Item {
  key: string;
  label: string;
  preview: string;
  standing: string;
  turn?: number;
  millis?: number;
  step?: Step;
  job?: Job;
}
function itemIcon(row: Item): Icon {
  if (row.step)
    return {
      person: 'user',
      reasoning: 'brain',
      answer: 'sparkles',
      fold: 'layers',
      note: 'file',
      call: 'tool',
    }[row.step.kind] as Icon;
  return row.key.startsWith('thinking:')
    ? 'brain'
    : row.job
      ? 'sparkles'
      : 'activity';
}
installPaneResize({
  container: $('.trajectory-columns'),
  pane: $('.trajectory-log'),
  other: $('.trajectory-detail'),
  key: 'trajectory',
  label: 'Resize trajectory log',
  property: '--trajectory-width',
  minimum: 300,
});
let state: DesktopState;
const approvalControls = installApprovalControls(
  document,
  () => state,
  async (command) => {
    const reply = await window.plowshare.request(command);
    update(reply.state);
    return reply;
  },
  render,
);
let conversation = '';
let stageScope: StageScope | undefined;
let selected = '';
let following = true;
let items: Item[] = [];
let visible: Item[] = [];
let runItems: Item[] = [];
let busy = false;
let detailStamp = '';
const millis = (n: number) =>
  n < 1000
    ? `${n} ms`
    : n < 60000
      ? `${(n / 1000).toFixed(1)} s`
      : `${Math.floor(n / 60000)}m ${Math.round((n % 60000) / 1000)}s`;
const pretty = (s: string) => {
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
};
function title() {
  if (stageScope)
    return (
      state.activity.details[stageScope.run]?.value?.run.definition ||
      stageScope.run
    );
  return (
    state.conversations.find((row) => row.id === conversation)?.title ||
    state.history[conversation]?.entries
      .find((row) => row.kind === 'utterance')
      ?.text?.slice(0, 64) ||
    'New conversation'
  );
}
function fromStep(step: Step): Item {
  if (step.kind === 'call')
    return {
      key: stepKey(step),
      label: step.tool,
      preview: step.salient || step.arguments,
      standing: callStanding(step),
      turn: step.turn,
      ...(step.result?.tookMillis === undefined
        ? {}
        : { millis: step.result?.tookMillis }),
      step,
    };
  const label = {
    person: 'User',
    reasoning: 'Think',
    answer: 'Assistant',
    fold: 'Summary',
    note: step.entry.kind,
  }[step.kind];
  return {
    key: stepKey(step),
    label,
    preview: step.entry.text ?? `${step.entry.state} entry`,
    standing:
      step.entry.supersededBy !== undefined
        ? 'folded for model'
        : step.entry.state === 'stands'
          ? 'recorded'
          : step.entry.state,
    turn: step.turn,
    ...(step.entry.tookMillis === undefined
      ? {}
      : { millis: step.entry.tookMillis }),
    step,
  };
}
function update(next: DesktopState) {
  state = next;
  if (conversation) render();
}
function render() {
  const approvals = state.approvals.filter(
    (row) =>
      row.state === 'asked' &&
      (row.conversation === conversation || row.askedIn === conversation),
  );
  $('#trajectory-approvals').hidden = !approvals.length;
  $('#trajectory-approvals').innerHTML = approvals
    .map((row) => approvalControls.prompt(row))
    .join('');
  const history = state.history[conversation];
  const steps = stageSteps(state, conversation, stageScope);
  const turns = turnsOf(steps);
  items = steps.map(fromStep);
  const jobs = state.jobs.filter((job) => job.conversation === conversation);
  // Progress is replaced in its own region; only durable steps belong to the timeline.
  // Successful completion hands Follow latest back to the recorded answer.
  runItems = [];
  const job = jobs.at(-1);
  if (
    job &&
    (!stageScope ||
      (stageSpans(state, stageScope.run).get(stageScope.stage) ?? []).some(
        (span) => span.end === undefined,
      ))
  ) {
    if (activeJob(job) && job.thinking)
      runItems.push({
        key: `thinking:${job.id}`,
        label: 'Live reasoning',
        preview: job.thinking,
        standing: job.status,
        job,
      });
    if (activeJob(job) || stoppedJob(job)) {
      runItems.push({
        key: `job:${job.id}`,
        label: activeJob(job) ? 'Live response' : 'Run outcome',
        preview: job.text || job.detail || job.status,
        standing: job.ending || job.status,
        job,
      });
    }
  }
  const query = $<HTMLInputElement>('#trajectory-search').value.toLowerCase();
  const filter = $<HTMLSelectElement>('#trajectory-filter').value;
  const matches = (row: Item) =>
    (!query ||
      `${row.label} ${row.preview} ${row.step?.kind === 'call' ? (row.step.result?.text ?? '') : (row.step?.entry.text ?? JSON.stringify(row.job ?? ''))}`
        .toLowerCase()
        .includes(query)) &&
    (filter === 'all' ||
      (filter === 'tools' && row.step?.kind === 'call') ||
      (filter === 'failed' &&
        (row.standing === 'fail' ||
          row.standing === 'interrupted' ||
          (row.job !== undefined && stoppedJob(row.job)))));
  visible = items.filter(matches);
  runItems = runItems.filter(matches);
  if (
    following ||
    ![...visible, ...runItems].some((row) => row.key === selected)
  )
    selected = runItems.at(-1)?.key ?? visible.at(-1)?.key ?? '';
  document.title = `${stageScope ? `${stageScope.stage} · ` : ''}${title()} · Trajectory`;
  $('#trajectory-title').textContent = stageScope
    ? `${stageScope.stage} · ${title()}`
    : title();
  $('#trajectory-scope').hidden = !stageScope;
  $('#trajectory-scope').textContent = stageScope
    ? `Stage: ${stageScope.stage} · all recorded visits · ${stageScope.run}`
    : '';
  const unavailable =
    state.connected &&
    (state.liveHistory?.status === 'unavailable' || history?.error);
  $('#trajectory-connection').textContent =
    state.mode === 'demo'
      ? 'Offline demo'
      : `${state.connection}${unavailable ? ' · history updates unavailable' : ''}`;
  $('#trajectory-connection').title =
    history?.error || state.liveHistory?.detail || '';
  $('#trajectory-conversation').textContent = conversation;
  const modelCalls = turns.reduce((n, t) => n + t.modelCalls, 0);
  const calls = turns.reduce((n, t) => n + t.calls, 0);
  const failed = turns.reduce((n, t) => n + t.failed, 0);
  const modelTime = turns.reduce((n, t) => n + t.modelMillis, 0);
  const toolTime = turns.reduce((n, t) => n + t.toolMillis, 0);
  $('#trajectory-summary').textContent =
    `${turns.length} loaded turns · ${steps.length} steps · ${modelCalls} model calls · ${calls} tools${failed ? ` · ${failed} failed` : ''}${modelTime ? ` · model ${millis(modelTime)}` : ''}${toolTime ? ` · tools ${millis(toolTime)}` : ''}`;
  $('#trajectory-earlier').hidden = !history?.more;
  $<HTMLButtonElement>('#trajectory-earlier').disabled = busy;
  $<HTMLButtonElement>('#trajectory-reload').disabled =
    busy || (state.mode === 'live' && !state.connected);
  const follow = $('#trajectory-follow');
  follow.setAttribute('aria-pressed', String(following));
  follow.innerHTML = `${icon('follow')}${following ? 'Following' : 'Follow latest'}`;
  $('#trajectory-strip').innerHTML = steps
    .map(
      (step) =>
        `<button data-step="${esc(stepKey(step))}" class="strip-step ${step.kind === 'call' ? callStanding(step) : step.kind}" title="${esc(step.kind === 'call' ? step.tool : step.kind)} · turn ${step.turn}" aria-label="Select ${esc(step.kind === 'call' ? step.tool : step.kind)} in turn ${step.turn}" ${stepKey(step) === selected ? 'aria-current="true"' : ''}></button>`,
    )
    .join('');
  const rows = $('#trajectory-rows');
  const top = rows.scrollTop;
  rows.innerHTML =
    visible
      .map((row) => {
        const call = row.step?.kind === 'call' ? row.step : undefined,
          linked = call && navigableCall(call);
        return `<div class="trajectory-step" role="listitem"><button class="trajectory-row ${row.step?.kind ?? 'event'} ${row.standing}" data-step="${esc(row.key)}" ${row.key === selected ? 'aria-current="true"' : ''}><span class="step-turn">${row.turn ?? '·'}</span><span class="step-kind">${linked ? '<span class="delegation-direction" title="Agent called">&gt;</span>' : icon(itemIcon(row))}<span>${esc(row.label)}</span></span><span class="step-preview">${esc(row.preview.replace(/\s+/g, ' '))}</span><span class="step-outcome">${linked && call.result ? '<span class="delegation-direction" title="Agent result received">&lt;</span> ' : ''}${row.millis === undefined ? esc(row.standing) : `${esc(row.standing)} · ${millis(row.millis)}`}</span></button>${linked ? delegationLink(call, 'row') : ''}</div>`;
      })
      .join('') ||
    '<div class="trajectory-empty">No matching steps. Load earlier entries or refresh the conversation.</div>';
  if (following) rows.scrollTop = rows.scrollHeight;
  else rows.scrollTop = top;
  renderRun(job);
  renderDiagnostics(jobs);
  renderDetail();
}
function renderRun(job: Job | undefined) {
  const panel = $('#trajectory-run');
  panel.hidden = !runItems.length;
  const status = !job
    ? ''
    : job.status === 'unknown'
      ? 'Outcome uncertain'
      : job.status === 'cancelling'
        ? 'Stopping…'
        : stoppedJob(job)
          ? `Run stopped · ${job.ending || job.status}`
          : job.phase === 'tool'
            ? `Using ${job.tool || 'a tool'}…`
            : job.phase === 'thinking'
              ? 'Reasoning…'
              : job.phase === 'answer'
                ? 'Responding…'
                : 'Working…';
  $('#trajectory-run-status').textContent = `${job?.agent ?? ''} · ${status}`;
  $('#trajectory-run-items').innerHTML = runItems
    .map(
      (row) =>
        `<button class="trajectory-run-item" data-step="${esc(row.key)}" ${row.key === selected ? 'aria-current="true"' : ''}>${icon(itemIcon(row))}<span>${esc(row.label)}</span><span class="step-preview">${esc(row.preview.replace(/\s+/g, ' ').slice(0, 160))}</span></button>`,
    )
    .join('');
}
function renderDiagnostics(jobs: Job[]) {
  const panel = $('#trajectory-diagnostics');
  panel.hidden = !jobs.length;
  const content = $('#trajectory-diagnostics-content');
  const data = jobs.map((job) => ({
    job: job.id,
    agent: job.agent,
    status: job.status,
    ending: job.ending,
    detail: job.detail,
    ...(stoppedJob(job) ? { text: job.text } : {}),
    events: job.events,
  }));
  const stamp = JSON.stringify(data);
  if (content.dataset.stamp === stamp) return;
  const open = new Set(
    [...content.querySelectorAll<HTMLDetailsElement>('details[open]')].map(
      (row) => row.dataset.job,
    ),
  );
  const top = content.scrollTop;
  content.innerHTML = data
    .map(
      (job) =>
        `<details class="trajectory-raw" data-job="${esc(job.job)}"><summary>${esc(job.agent)} · ${esc(job.ending || job.status)} · ${job.events.length} events ${copyButton('Copy job diagnostics', 'record')}</summary><pre>${esc(JSON.stringify(job, null, 2))}</pre></details>`,
    )
    .join('');
  content.querySelectorAll<HTMLDetailsElement>('details').forEach((row) => {
    row.open = open.has(row.dataset.job);
  });
  content.scrollTop = top;
  content.dataset.stamp = stamp;
}
function delegationLink(
  call: Extract<Step, { kind: 'call' }>,
  placement: 'row' | 'header',
) {
  const name = call.opened?.agent || call.tool.replace(/^orchestrate_/, '');
  return `<button class="step-trajectory-link ${placement}" data-delegate-parent="${esc(conversation)}" data-delegate-step="${esc(stepKey(call))}" title="Open ${esc(name)} trajectory" aria-label="Open ${esc(name)} trajectory">${icon('external')}${placement === 'header' ? 'Trajectory' : ''}</button>`;
}
function section(label: string, text: string, note = '', direction = '') {
  return `<section class="trajectory-text-section"><h3>${icon(label === 'Input' ? 'terminal' : label === 'Result' ? 'file' : label === 'Published reasoning' ? 'brain' : 'message')}${direction ? `<span class="delegation-direction">${esc(direction)}</span>` : ''}${esc(label)}${copyButton('Copy displayed text', 'section')}</h3><pre>${esc(text)}</pre>${note ? `<p class="history-note">${esc(note)}</p>` : ''}</section>`;
}
function renderDetail() {
  const row = [...visible, ...runItems].find((item) => item.key === selected);
  const stamp = JSON.stringify(row);
  if (stamp === detailStamp) return;
  detailStamp = stamp;
  const detail = $('#trajectory-detail-content');
  const top = detail.scrollTop;
  const nearBottom = detail.scrollHeight - detail.clientHeight - top < 80;
  $('#trajectory-detail-heading').innerHTML = row
    ? `<div><span class="eyebrow">${row.turn === undefined ? (row.job && activeJob(row.job) ? 'LIVE RUN' : 'RUN OUTCOME') : `TURN ${row.turn} · #${row.step?.ordinal}`}</span><h2>${icon(itemIcon(row))}${esc(row.label)}</h2></div><div class="detail-actions">${row.step?.kind === 'call' && navigableCall(row.step) ? delegationLink(row.step, 'header') : ''}<span class="detail-standing ${esc(row.standing)}">${esc(row.standing)}${row.millis === undefined ? '' : ` · ${millis(row.millis)}`}</span></div>`
    : '<h2>Select a step</h2>';
  if (!row) {
    detail.innerHTML =
      '<div class="trajectory-empty">The complete selected entry appears here.</div>';
    return;
  }
  let body = '';
  if (row.step?.kind === 'call') {
    const call = row.step;
    const linked = navigableCall(call);
    body = section(
      'Input',
      pretty(call.arguments),
      call.argumentsCut
        ? `Excerpt of ${call.argumentsLength} characters; arguments were cut by the server.`
        : '',
      linked ? '>' : '',
    );
    body += section(
      'Result',
      call.result?.text ??
        (call.pending
          ? 'Waiting for the tool result.'
          : call.unanswered
            ? 'The conversation continued without a recorded result.'
            : 'Result content is unavailable.'),
      call.result?.cut
        ? `Excerpt of ${call.result.length ?? 'unknown'} characters; stored handle: ${call.result.handle ?? 'unavailable'}.`
        : '',
      linked && call.result ? '<' : '',
    );
    for (const hook of call.hooks)
      body += section('Hook', hook.text ?? '(content unavailable)');
  } else if (row.step) {
    const entry = row.step.entry;
    body = section(
      row.step.kind === 'reasoning' ? 'Published reasoning' : 'Text',
      entry.text ?? 'This entry has no retained text.',
      entry.cut
        ? `Excerpt of ${entry.length ?? 'unknown'} characters, as returned by the server.`
        : entry.ejectedAt
          ? `Content ejected ${entry.ejectedAt}`
          : '',
    );
  } else if (row.job) {
    body = section(
      row.key.startsWith('thinking:') ? 'Published reasoning' : row.job.agent,
      row.key.startsWith('thinking:')
        ? (row.job.thinking ?? '')
        : row.job.text ||
            row.job.detail ||
            'Waiting for published response text.',
    );
    if (row.job.detail && row.job.text)
      body += section('Status', row.job.detail);
  }
  if (row.step?.entry.supersededBy !== undefined)
    body += `<p class="history-note">Folded into model summary #${row.step.entry.supersededBy}. The original entry remains in your history.</p>`;
  const recorded =
    row.step?.kind === 'call' ? row.step.result : row.step?.entry;
  if (recorded?.job) body += section('Job', recorded.job);
  if (recorded?.source)
    body += section(
      'Origin',
      recorded.source.kind === 'unknown'
        ? 'Origin not recorded'
        : `${recorded.source.kind} · ${recorded.source.reference ?? ''}`,
    );
  body += `<details class="trajectory-raw"><summary>Record details ${copyButton('Copy displayed record', 'record')}</summary><pre>${esc(JSON.stringify(row.step ?? row.job, null, 2))}</pre></details>`;
  detail.innerHTML = body;
  if (following && nearBottom) detail.scrollTop = detail.scrollHeight;
  else detail.scrollTop = top;
}
function select(key: string) {
  const changed = selected !== key;
  following = false;
  selected = key;
  if (changed) {
    detailStamp = '';
    $('#trajectory-detail-content').scrollTop = 0;
  }
  render();
}
async function load(before?: number) {
  if (busy || !conversation) return;
  busy = true;
  $('#trajectory-error').hidden = true;
  render();
  try {
    update(
      (
        await window.plowshare.request({
          action: 'history',
          conversation,
          ...(before === undefined ? {} : { before }),
        })
      ).state,
    );
    // A stage can be much older than the tail. Read backwards until its first visit
    // is covered, retaining complete entries and the server's ordinary pagination.
    const spans =
      stageScope && stageSpans(state, stageScope.run).get(stageScope.stage);
    const start = spans?.[0]?.start;
    while (start !== undefined && state.history[conversation]?.more) {
      const history = state.history[conversation];
      if (!history) break;
      const at = Date.parse(history.entries[0]?.recordedAt ?? '');
      if (Number.isFinite(at) && at <= start) break;
      const cursor = history.oldest;
      if (!cursor) break;
      update(
        (
          await window.plowshare.request({
            action: 'history',
            conversation,
            before: cursor,
          })
        ).state,
      );
      if (state.history[conversation]?.oldest === cursor)
        throw new Error('Earlier trajectory history did not advance.');
    }
  } catch (reason) {
    $('#trajectory-error').textContent =
      reason instanceof Error ? reason.message : errorMessage(reason);
    $('#trajectory-error').hidden = false;
  } finally {
    busy = false;
    render();
  }
}
for (const selector of [
  '#trajectory-rows',
  '#trajectory-strip',
  '#trajectory-run-items',
])
  $(selector).addEventListener('click', (event) => {
    const key = (event.target as HTMLElement).closest<HTMLElement>(
      '[data-step]',
    )?.dataset.step;
    if (
      key &&
      selector === '#trajectory-strip' &&
      !visible.some((row) => row.key === key)
    ) {
      $<HTMLInputElement>('#trajectory-search').value = '';
      $<HTMLSelectElement>('#trajectory-filter').value = 'all';
    }
    if (key) select(key);
  });
$('#trajectory-rows').addEventListener('keydown', (event) => {
  if (
    !['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key) ||
    !visible.length
  )
    return;
  event.preventDefault();
  const index = visible.findIndex((row) => row.key === selected);
  const next =
    event.key === 'Home'
      ? 0
      : event.key === 'End'
        ? visible.length - 1
        : Math.max(
            0,
            Math.min(
              visible.length - 1,
              index + (event.key === 'ArrowDown' ? 1 : -1),
            ),
          );
  const nextItem = visible[next];
  if (!nextItem) return;
  select(nextItem.key);
  $('#trajectory-rows [aria-current="true"]')?.scrollIntoView({
    block: 'nearest',
  });
});
$('#trajectory-search').addEventListener('input', () => {
  following = false;
  render();
});
$('#trajectory-filter').addEventListener('change', () => {
  following = false;
  render();
});
$('#trajectory-follow').addEventListener('click', () => {
  following = !following;
  render();
});
$('#trajectory-reload').addEventListener('click', () => background(load()));
$('#trajectory-earlier').addEventListener('click', () =>
  background(load(state.history[conversation]?.oldest)),
);
document.addEventListener('keydown', (event) => {
  if (event.key === '/' && !(event.target instanceof HTMLInputElement)) {
    event.preventDefault();
    $<HTMLInputElement>('#trajectory-search').focus();
  }
});
document.addEventListener('click', (event) => {
  const delegate = (event.target as HTMLElement).closest<HTMLElement>(
    '[data-delegate-step]',
  );
  if (delegate?.dataset.delegateParent && delegate.dataset.delegateStep)
    void window.plowshare
      .request({
        action: 'delegate-trajectory',
        conversation: delegate.dataset.delegateParent,
        step: delegate.dataset.delegateStep,
      })
      .catch((reason: unknown) => {
        $('#trajectory-error').textContent = errorMessage(reason);
        $('#trajectory-error').hidden = false;
      });
});
window.plowshare.subscribe(update);
void window.plowshare
  .request({ action: 'bootstrap' })
  .then((reply) => {
    conversation = reply.conversation ?? '';
    stageScope = reply.stageScope;
    update(reply.state);
    return load();
  })
  .catch((reason: unknown) => {
    $('#trajectory-error').textContent = errorMessage(reason);
    $('#trajectory-error').hidden = false;
  });

window.addEventListener('workspace-refresh', () =>
  $('#trajectory-reload').click(),
);
