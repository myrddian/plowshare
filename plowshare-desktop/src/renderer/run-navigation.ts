import type { DesktopState } from '../shared.ts';
import { questionStage, stageSpans } from '../run-navigation.ts';
import { escapeHtml as esc, markdownHtml } from './markdown.ts';
import { icon } from './icons.ts';
import { questionControls } from './run-controls.ts';
import { copyButton } from './copy.ts';

const prose = (text: string) => `<div class="message-content markdown-body" data-copy-source="${esc(text)}">${copyButton('Copy text', 'answer')}${markdownHtml(text)}</div>`;
function messages(state: DesktopState, id: string) {
  const detail = state.activity.details[id];
  return (detail?.value?.messages ?? []).map((message, index, all) => ({
    message,
    stage: questionStage(state, id, detail?.wire?.messages[index]?.createdAt ?? ''),
    current: detail?.wire?.orchestration.state === 'asking' && message.kind === 'question' && !all.slice(index + 1).some(next => next.kind === 'question'),
  }));
}
function messageHtml(state: DesktopState, id: string, row: ReturnType<typeof messages>[number]) {
  const { message, current } = row, structure = message.structure;
  // text is the server's flattened rendering of structure. Render one representation.
  const body = structure ? `${prose(structure.lead)}${current ? '' : structure.questions.map(question => `<h4>${esc(question.header)}</h4>${prose(question.question)}<ul>${question.options.map(option => `<li><strong>${esc(option.label)}</strong> ${esc(option.description)}${option.preview ? `<pre>${esc(option.preview)}</pre>` : ''}</li>`).join('')}</ul>`).join('')}${structure.draft ? `<h4>Draft · ${esc(structure.draft.name)}</h4><p>${esc(structure.draft.path)}</p>${prose(structure.draft.text)}` : ''}` : prose(message.text);
  return `<article class="stage-question"><div class="eyebrow">${esc(message.kind)} · ${esc(message.author)}</div>${body}${current ? questionControls(state, id, { management: false }) : ''}</article>`;
}
export function stagesPanel(state: DesktopState, id: string) {
  const stages = state.activity.details[id]?.value?.stages ?? [], rows = messages(state, id), spans = stageSpans(state, id);
  if (!stages.length) return '';
  const navigation = state.activity.navigation?.[id];
  return `<section><h3>${icon('layers')}Stages</h3>${navigation?.error ? `<p class="error-banner">Stage navigation unavailable: ${esc(navigation.error)}</p>` : ''}<ul class="activity-stages">${stages.map(stage => {
    const questions = rows.filter(row => stage.stage && row.stage === stage.stage), available = Boolean(stage.stage && spans.get(stage.stage)?.length);
    return `<li>${icon(stage.status === 'done' ? 'check' : 'activity')}<div class="stage-content"><button class="stage-link" data-run-stage="${esc(stage.stage ?? '')}" ${available ? '' : 'disabled'} title="${available ? 'Open this stage’s trajectory' : navigation?.loading ? 'Loading stage history' : 'No recorded work in this stage'}"><strong>${esc(stage.text)}</strong>${available ? `<span>${icon('external')}Trajectory</span>` : ''}</button><small>${esc(stage.status)}${stage.stage ? ` · ${esc(stage.stage)}` : ''}</small>${stage.summary ? `<p>${esc(stage.summary)}</p>` : ''}${questions.length ? `<details class="stage-questions" data-persist="stage-questions:${esc(id)}:${esc(stage.stage)}" ${questions.some(row => row.current) ? 'open' : ''}><summary>${questions.some(row => row.current) ? 'Answer needed' : `${questions.length} question${questions.length === 1 ? '' : 's'} / answer${questions.length === 1 ? '' : 's'}`}</summary>${questions.map(row => messageHtml(state, id, row)).join('')}</details>` : ''}</div></li>`;
  }).join('')}</ul></section>`;
}
/** Older or ambiguous journals remain readable without assigning questions to the wrong stage. */
export function unassignedQuestions(state: DesktopState, id: string) {
  const rows = messages(state, id).filter(row => !row.stage);
  if (!rows.length) return '';
  return `<details class="stage-questions" data-persist="unassigned:${esc(id)}" ${rows.some(row => row.current) ? 'open' : ''}><summary>Questions with no recorded stage (${rows.length})</summary>${rows.map(row => messageHtml(state, id, row)).join('')}</details>`;
}

/** Keep root navigation compact; child navigation belongs to its recorded call. */
export function runTrajectories(state: DesktopState, conversation: string) {
  const caller = Object.values(state.activity.details).some(detail => detail.wire?.orchestration.conductorConversation === conversation && detail.wire.orchestration.callerConversation);
  return `<div class="agent-root-links"><button class="agent-trajectory" data-run-actor="conductor">${icon('route')}<strong>Conductor trajectory</strong>${icon('external')}</button>${caller ? `<button class="agent-trajectory" data-run-actor="caller">${icon('message')}<strong>Caller trajectory</strong>${icon('external')}</button>` : ''}</div>`;
}
