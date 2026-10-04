import { runQuestion, type DesktopState, type Request } from '../shared.ts';
import type { Choice } from 'plowshare-client-ts/operations/session';
import { icon } from './icons.ts';
import { escapeHtml as esc, markdownHtml } from './markdown.ts';

// A draft belongs to one displayed server question, never to a later question for the same run.
const draftKey = (state: DesktopState, question: string) => JSON.stringify([state.base, state.handle, question]);
const drafts = new Map<string, { answer: string; choices: Choice[]; deferred?: boolean }>();
export function questionControls(state: DesktopState, id: string, options: { question?: boolean; management?: boolean } = {}) {
  const detail = state.activity.details[id], wire = detail?.wire, decision = state.activity.decisions?.[id];
  if (!wire) return '';
  const disabled = !state.connected || decision?.busy ? 'disabled' : '';
  let html = options.management === false ? '' : `${decision?.notice ? `<div class="run-notice" role="status">${esc(decision.notice)}</div>` : ''}${decision?.error ? `<p class="error-banner">${esc(decision.error)}</p>` : ''}`;
  if (options.question !== false && wire.orchestration.state === 'asking') {
    const fingerprint = runQuestion(wire), message = [...(detail.value?.messages ?? [])].reverse().find(message => message.kind === 'question');
    const draft = drafts.get(draftKey(state, fingerprint)) ?? { answer: '', choices: [] };
    html += `<section class="run-question" data-question="${esc(fingerprint)}"><h3>Answer the current question</h3>`;
    if (draft.deferred) html += `<p>Deferred. This question is still waiting.</p><button data-question-resume ${disabled}>Answer now</button>`;
    else {
      if (message?.structure) html += message.structure.questions.map((question, index) => {
        const choice = draft.choices[index];
        return `<fieldset data-choice="${index}"><legend>${esc(question.header)}</legend><div class="markdown-body">${markdownHtml(question.question)}</div>${question.options.map(option => `<label class="run-option"><input type="${question.multi ? 'checkbox' : 'radio'}" name="question-${esc(id)}-${index}" value="${esc(option.label)}" ${choice?.chosen.includes(option.label) ? 'checked' : ''} ${disabled}><span><strong>${esc(option.label)}</strong> ${esc(option.description)}${option.preview ? `<pre>${esc(option.preview)}</pre>` : ''}</span></label>`).join('')}<label>Another answer<input data-other maxlength="2000" value="${esc(choice?.other ?? '')}" ${disabled}></label><label>Note<textarea data-note maxlength="2000" ${disabled}>${esc(choice?.note ?? '')}</textarea></label></fieldset>`;
      }).join('');
      else html += `<label>Your answer<textarea id="run-answer-text" maxlength="2000" ${disabled}>${esc(draft.answer)}</textarea></label>`;
      html += `<div class="run-actions"><button id="run-answer-send" ${disabled}>${decision?.busy ? 'Sending…' : 'Send answer'}</button><button data-question-defer ${disabled}>Later</button></div><p class="activity-footnote">The current question is checked again before sending. Another client may answer first.</p>`;
    }
    html += '</section>';
  }
  return html;
}
/** One prominent action, scoped to the selected run; the server rechecks its state. */
export function cancelControl(state: DesktopState, id: string) {
  const wire = state.activity.details[id]?.wire;
  if (!wire || !['running', 'asking', 'waiting'].includes(wire.orchestration.state)) return '';
  return `<button id="run-cancel-confirm" class="run-cancel-button" title="Cancel this run and its descendants" ${!state.connected || state.activity.decisions?.[id]?.busy ? 'disabled' : ''}>${icon('stop')}Cancel run</button>`;
}
export function rememberQuestion(state: DesktopState, id: string, container: HTMLElement) {
  const wire = state.activity.details[id]?.wire, form = container.querySelector<HTMLElement>('[data-question]');
  if (!wire || !form || form.dataset.question !== runQuestion(wire)) return;
  const fingerprint = runQuestion(wire), previous = drafts.get(draftKey(state, fingerprint));
  if (previous?.deferred) return;
  const structure = [...(state.activity.details[id]?.value?.messages ?? [])].reverse().find(message => message.kind === 'question')?.structure;
  const choices = structure?.questions.map((question, index) => {
    const field = form.querySelector<HTMLElement>(`[data-choice="${index}"]`)!;
    const chosen = [...field.querySelectorAll<HTMLInputElement>('input:checked')].map(input => input.value);
    const other = field.querySelector<HTMLInputElement>('[data-other]')!.value, note = field.querySelector<HTMLTextAreaElement>('[data-note]')!.value;
    return { header: question.header, chosen, ...(other ? { other } : {}), ...(note ? { note } : {}) };
  }) ?? [];
  drafts.set(draftKey(state, fingerprint), { answer: form.querySelector<HTMLTextAreaElement>('#run-answer-text')?.value ?? '', choices });
}
export function resumeQuestion(state: DesktopState, id: string) {
  const wire = state.activity.details[id]?.wire;
  if (!wire) return;
  const key = draftKey(state, runQuestion(wire)), draft = drafts.get(key);
  if (draft) drafts.set(key, { ...draft, deferred: false });
}
export function questionAction(state: DesktopState, id: string, target: HTMLElement): Request | 'render' | undefined {
  const wire = state.activity.details[id]?.wire; if (!wire) return;
  const question = runQuestion(wire), draft = drafts.get(draftKey(state, question)) ?? { answer: '', choices: [] };
  if (target.closest('[data-question-defer]')) { drafts.set(draftKey(state, question), { ...draft, deferred: true }); return 'render'; }
  if (target.closest('[data-question-resume]')) { drafts.set(draftKey(state, question), { ...draft, deferred: false }); return 'render'; }
  if (target.closest('#run-answer-send')) return { action: 'run-answer', id, question, ...(draft.choices.length ? { choices: draft.choices } : { answer: draft.answer }) };
  if (target.closest('#run-cancel-confirm')) return { action: 'run-cancel', id };
}
