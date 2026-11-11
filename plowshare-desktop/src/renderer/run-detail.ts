import type { DesktopState } from '../shared.ts';
import { retainedReport } from '../retained-report.ts';
import {
  runTrajectories,
  stagesPanel,
  unassignedQuestions,
} from './run-navigation.ts';
import { cancelControl, questionControls } from './run-controls.ts';
import { icon } from './icons.ts';
import { escapeHtml as esc, markdownHtml } from './markdown.ts';
import { copyButton } from './copy.ts';

const capLabels: Record<string, string> = {
  turn_cap: 'turn limit',
  call_budget: 'tool call budget',
  time_cap: 'time limit',
  stuck: 'progress decision',
  uncovered: 'coverage decision',
  check_failures: 'check failures',
};
const capLabel = (cap: string) => capLabels[cap] ?? cap.replace(/_/g, ' ');
const prose = (text: string) =>
  `<div class="message-content markdown-body" data-copy-source="${esc(text)}">${copyButton('Copy text', 'answer')}${markdownHtml(text)}</div>`;
const date = (value: string) => {
  const time = Date.parse(value);
  return Number.isFinite(time) ? new Date(time).toLocaleString() : value;
};

/** Shared run inspection chrome for Activity and the Application hub. */
export function runDetailHtml(
  state: DesktopState,
  runId: string,
  options: { prefix?: string; empty?: string } = {},
) {
  const detail = state.activity.details[runId];
  if (!detail?.value) {
    return `<div class="activity-empty">${icon('route')}<h2>${detail?.loading ? 'Loading run…' : 'Choose a run'}</h2><p>${esc(detail?.error || options.empty || 'Inspect stages, child runs and results here.')}</p></div>`;
  }
  const { run, children } = detail.value;
  return `${options.prefix ?? ''}<header class="run-detail-header"><div><div class="eyebrow">${esc(run.project || 'Global')} · ${esc(run.tier)} · ${esc(run.state)}<span data-run-loading ${detail.loading ? '' : 'hidden'}> · updating</span></div><h2>${icon('route')}${esc(run.definition || run.id)}</h2><p class="activity-id">${esc(run.id)}</p></div>${cancelControl(state, runId)}</header>${questionControls(state, runId, { question: false })}${detail.error ? `<p class="error-banner">${esc(detail.error)}</p>` : ''}
      ${run.parent ? `<p class="activity-relation">Child run · depth ${run.depth} · <button data-run="${esc(run.parent)}">${icon('layers')}Parent ${esc(run.parent)}</button></p>` : ''}
      ${run.stalledSince ? `<p class="activity-warning">${icon('alert')}Marked stalled ${esc(date(run.stalledSince))}</p>` : ''}
      ${run.pendingCap ? `<p class="activity-warning">${icon('alert')}Waiting on a ${esc(capLabel(run.pendingCap))}</p>` : ''}
      ${run.waitingFor ? `<p class="activity-relation">Waiting on ${esc(run.waitingFor)}</p>` : ''}
      ${run.result ? `<section><h3>Result</h3>${retainedReport(run.result) ? `<button data-report-revision="${esc(retainedReport(run.result))}" data-report-project="${esc(run.project || '')}" class="primary-button">Read retained report</button>` : ''}${prose(run.result)}</section>` : ''}${run.failure ? `<section><h3>Why it stopped</h3>${prose(run.failure)}</section>` : ''}
      ${detail.wire?.orchestration.conductorConversation ? runTrajectories(state, detail.wire.orchestration.conductorConversation) : ''}
      ${stagesPanel(state, runId)}
      ${children.length ? `<section><h3>Child runs</h3><div class="activity-children">${children.map((child) => `<button data-run="${esc(child.id)}">${icon('layers')}<span>${esc(child.id)} · ${esc(child.state)}</span>${icon('external')}</button>`).join('')}</div></section>` : ''}
      ${unassignedQuestions(state, runId)}`;
}
