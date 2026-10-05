import { errorMessage } from 'plowshare-client-ts/binding/values';
import { background } from './events.ts';
import {
  runQuestion,
  type DesktopState,
  type Request,
  type Reply,
} from '../shared.ts';
import {
  questionControls,
  rememberQuestion,
  questionAction,
  resumeQuestion,
} from './run-controls.ts';
import { escapeHtml as esc, markdownHtml } from './markdown.ts';
import { icon } from './icons.ts';

/** Only server-listed, fully read, currently asking runs acquire answer controls. */
export function pendingQuestions(state: DesktopState) {
  return state.activity.runs.items.flatMap((run) => {
    const detail = state.activity.details[run.id],
      wire = detail?.wire;
    if (
      run.state !== 'asking' ||
      !wire ||
      wire.orchestration.id !== run.id ||
      wire.orchestration.state !== 'asking'
    )
      return [];
    const message = [...wire.messages]
      .reverse()
      .find((message) => message.kind === 'question');
    return message
      ? [
          {
            id: run.id,
            question: runQuestion(wire),
            run: wire.orchestration,
            message,
            detail,
          },
        ]
      : [];
  });
}

export function questionInScope(
  run: ReturnType<typeof pendingQuestions>[number]['run'],
  scope: string,
  conversation: string,
) {
  return (
    (run.project ?? '') === scope ||
    Boolean(conversation && run.callerConversation === conversation)
  );
}

/** One question at a time. Deferral is local and never answers or cancels a run. */
export function installQuestionPopup(
  dialog: HTMLDialogElement,
  waiting: HTMLElement,
  send: (request: Request) => Promise<Reply>,
) {
  let state: DesktopState,
    scope = '',
    conversation = '',
    identity = '',
    selected = '',
    fingerprint = '',
    stamp = '',
    error = '';
  const deferred = new Set<string>();
  const content = dialog.querySelector<HTMLElement>('[data-question-content]')!;
  const identityOf = (value: DesktopState) =>
    JSON.stringify([value.mode, value.base, value.handle]);
  function remember() {
    if (state && selected) rememberQuestion(state, selected, content);
  }
  function close() {
    selected = '';
    fingerprint = '';
    stamp = '';
    dialog.close();
  }
  function later() {
    remember();
    if (fingerprint) deferred.add(fingerprint);
    close();
    render();
  }
  async function request(value: Request) {
    const sentIdentity = identity,
      sentQuestion = fingerprint;
    try {
      const reply = await send(value);
      if (identityOf(reply.state) !== sentIdentity || identity !== sentIdentity)
        return;
      if (fingerprint === sentQuestion) error = '';
      update(reply.state, scope, conversation);
    } catch (reason) {
      if (identity !== sentIdentity || fingerprint !== sentQuestion) return;
      error = reason instanceof Error ? reason.message : errorMessage(reason);
      stamp = '';
      render();
    }
  }
  function render() {
    if (!state) return;
    const questions = state.mode === 'live' ? pendingQuestions(state) : [];
    const asking = state.activity.runs.items.filter((run) => {
      const wire = state.activity.details[run.id]?.wire;
      return (
        run.state === 'asking' &&
        (!wire ||
          wire.orchestration.id !== run.id ||
          wire.orchestration.state === 'asking')
      );
    });
    const unread = asking.filter(
      (run) => !questions.some((question) => question.id === run.id),
    );
    waiting.hidden =
      state.mode !== 'live' ||
      (asking.length === 0 && !state.activity.runs.error);
    waiting.innerHTML = asking.length
      ? `<strong class="section-label">${icon('message')}${asking.length} question${asking.length === 1 ? '' : 's'} waiting</strong><div class="question-links">${questions.map((row) => `<button data-open-question="${esc(row.id)}">${esc(row.run.definition)} · ${esc(row.run.project || 'Global')}</button>`).join('')}</div>${unread.length ? `<p>${unread.some((run) => state.activity.details[run.id]?.error) ? 'Some question details could not be read.' : 'Loading question details…'}</p><button data-question-refresh ${state.connected ? '' : 'disabled'}>Refresh questions</button>` : ''}`
      : `<span>Pending questions could not be refreshed.</span><button data-question-refresh ${state.connected ? '' : 'disabled'}>Refresh questions</button>`;
    let current = questions.find((row) => row.id === selected);
    if (current && current.question !== fingerprint) {
      remember();
      fingerprint = current.question;
      stamp = '';
      error =
        'This question changed. Review its current state before answering.';
      resumeQuestion(state, selected);
    }
    if (!current && selected) close();
    if (
      !current &&
      state.connected &&
      !document.querySelector('dialog[open]')
    ) {
      current = questions.find(
        (row) =>
          !deferred.has(row.question) &&
          questionInScope(row.run, scope, conversation),
      );
      if (current) {
        selected = current.id;
        fingerprint = current.question;
        error = '';
        resumeQuestion(state, selected);
      }
    }
    if (!current) return;
    const next = JSON.stringify([
      identity,
      fingerprint,
      state.connected,
      state.activity.decisions?.[selected],
      current.detail.error,
      error,
    ]);
    if (next !== stamp) {
      remember();
      stamp = next;
      const active = document.activeElement as
        HTMLInputElement | HTMLTextAreaElement | null;
      const field =
        active && content.contains(active)
          ? [...content.querySelectorAll('input,textarea,button')].indexOf(
              active,
            )
          : -1;
      const start = active?.selectionStart,
        end = active?.selectionEnd;
      dialog.querySelector<HTMLElement>(
        '[data-question-context]',
      )!.textContent =
        `${current.run.definition} · ${current.run.project || 'Global'}`;
      const lead =
        current.message.structure &&
        typeof current.message.structure === 'object' &&
        'lead' in current.message.structure
          ? current.message.structure.lead
          : current.message.text;
      content.innerHTML = `<div class="markdown-body question-lead">${markdownHtml(typeof lead === 'string' ? lead : current.message.text)}</div>${current.detail.error ? `<p class="error-banner" role="alert">${esc(current.detail.error)} The last loaded question is shown. Refresh to check its state.</p>` : ''}${error ? `<p class="error-banner" role="alert">${esc(error)}</p>` : ''}${questionControls(state, selected)}<button data-question-refresh ${state.connected ? '' : 'disabled'}>${icon('refresh')}Refresh question</button>`;
      if (field >= 0) {
        const replacement = content.querySelectorAll<
          HTMLInputElement | HTMLTextAreaElement | HTMLButtonElement
        >('input,textarea,button')[field];
        replacement?.focus();
        if (
          replacement &&
          'setSelectionRange' in replacement &&
          start != null &&
          end != null
        )
          replacement.setSelectionRange(start, end);
      }
    }
    if (!dialog.open && !document.querySelector('dialog[open]'))
      dialog.showModal();
  }
  function update(
    next: DesktopState,
    project: string,
    selectedConversation: string,
  ) {
    remember();
    const key = identityOf(next);
    if (key !== identity) {
      close();
      deferred.clear();
      identity = key;
      error = '';
    }
    state = next;
    scope = project;
    conversation = selectedConversation;
    render();
  }
  waiting.addEventListener('click', (event) => {
    const target = (event.target as HTMLElement).closest<HTMLElement>(
      '[data-open-question], [data-question-refresh]',
    );
    if (target?.hasAttribute('data-question-refresh')) {
      background(request({ action: 'question-refresh' }));
      return;
    }
    const row = pendingQuestions(state).find(
      (row) => row.id === target?.dataset.openQuestion,
    );
    if (!row) return;
    selected = row.id;
    fingerprint = row.question;
    error = '';
    stamp = '';
    deferred.delete(fingerprint);
    resumeQuestion(state, selected);
    render();
  });
  dialog.addEventListener('input', remember);
  dialog.addEventListener('change', remember);
  dialog.addEventListener('cancel', (event) => {
    event.preventDefault();
    later();
  });
  dialog.addEventListener('click', (event) => {
    const target = event.target as HTMLElement;
    if (target.closest('[data-question-close], [data-question-defer]')) {
      later();
      return;
    }
    if (target.closest('[data-question-refresh]')) {
      background(request({ action: 'question-refresh' }));
      return;
    }
    remember();
    const action = questionAction(state, selected, target);
    if (action === 'render') {
      stamp = '';
      render();
    } else if (action) background(request(action));
  });
  // A connection/files/navigation dialog may have delayed the popup.
  document.addEventListener(
    'close',
    () => {
      if (state) queueMicrotask(render);
    },
    true,
  );
  return { update };
}
