import { errorMessage } from 'plowshare-client-ts/binding/values';
import { background } from './events.ts';
import { decodeOperatorInput } from '../operator-input.ts';
import { displayText } from 'plowshare-client-ts/binding/values';
import { isList } from 'plowshare-client-ts/binding/values';
import type { DesktopApi, DesktopState } from '../shared.ts';
import { OPERATOR_KINDS, type OperatorKind } from '../operator-shared.ts';
const esc = (value: unknown) =>
  displayText(value ?? '').replace(
    /[&<>"']/g,
    (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[
        c
      ]!,
  );
const labels: Record<OperatorKind, string> = {
  'message-deliveries': 'Inspect message deliveries',
  'message-open': 'Create a persistent message instance',
  'message-default': 'Choose a default message instance',
  'message-stop': 'Stop a message instance',
  'message-archive': 'Archive a message instance',
  'memory-write': 'Save a memory',
  'memory-digest': 'Consolidate memories',
  'agent-curate': 'Improve project agents',
  'conversation-lifecycle': 'Archive or restore a conversation',
  'conversation-resume': 'Continue a conversation',
  'job-limits': 'Adjust a running job’s limits',
  'approval-grant': 'Review command permissions',
  'approval-revoke': 'Remove a saved command permission',
  'board-topup': 'Increase a Board topic’s budget',
  caps: 'Set project work limits',
};
const descriptions: Record<OperatorKind, string> = {
  'message-deliveries':
    'Read messages and their delivery status for a project instance. You can cancel pending handling after reviewing the exact message.',
  'message-open':
    'Give an agent or bot a persistent address and its own conversation in this project.',
  'message-default':
    'Messages addressed to a definition name use this project’s default instance. Existing instance addresses stay valid.',
  'message-stop':
    'Stop new deliveries and cancel pending handling. Expected replies receive a cancellation outcome.',
  'message-archive':
    'Stop the instance and hide it from active listings. Its message and conversation history is retained.',
  'memory-write':
    'Save something agents should remember. Give it a short summary, explain when it applies, and write the details.',
  'memory-digest':
    'Ask the configured model to consolidate conversations and memories in this workspace. This starts a maintenance job.',
  'agent-curate':
    'Ask the configured model to improve this project’s agent definitions. Choose a project and a budget before starting.',
  'conversation-lifecycle':
    'Hide a conversation from the active list or bring an archived conversation back. Archiving leaves any running job active.',
  'conversation-resume':
    'Start a new job to continue an existing conversation. Set how much work the agent may do.',
  'job-limits':
    'Change the turn and model-call limits for a job that is already running.',
  'approval-grant':
    'Review a pending command and choose whether to allow it once, for its conversation, or for a project. You can also answer approvals directly in the conversation.',
  'approval-revoke':
    'Remove a standing permission for commands in a project. Future matching commands will need approval again.',
  'board-topup':
    'Give an open Board topic a higher total model-call budget so it can continue its work.',
  caps: 'Set how much work a project’s orchestrations may do before stopping or asking you. Changes are saved to the project configuration.',
};
const field = (name: string, label: string, type = 'text', value = '') =>
  `<label>${label}<input name="${name}" type="${type}" value="${esc(value)}" ${type === 'number' ? 'min="1" max="1000000"' : 'maxlength="8000"'} required></label>`;
const select = (name: string, label: string, options: [string, string][]) =>
  `<label>${label}<select name="${name}" required>${options.map(([id, label]) => `<option value="${esc(id)}">${esc(label)}</option>`).join('')}</select></label>`;
export function installOperator(
  api: DesktopApi,
  state: () => DesktopState,
  activeProject: () => string,
  options: { host?: HTMLElement; close?: () => void } = {},
) {
  const dialog = document.createElement('dialog');
  dialog.className = 'operator-dialog';
  dialog.setAttribute('aria-label', 'Workspace controls');
  dialog.innerHTML = `<header><div><div class="eyebrow">WORKSPACE</div><h2>Manage work</h2></div><button data-close type="button" class="secondary-button">Close</button></header><p>Choose a task below. You can review the exact change before applying it.</p><div class="operator-toolbar">${select(
    'kind',
    'What would you like to do?',
    OPERATOR_KINDS.map((k) => [k, labels[k]]),
  )}${select('project', 'Workspace', [])}<button data-read type="button" class="secondary-button">Refresh options</button></div><section class="operator-task-help"><h3 data-task-title></h3><p data-task-description></p></section><p data-error role="alert" hidden></p><p data-notice role="status"></p><div data-content></div><section data-jobs></section>`;
  (options.host ?? document.body).append(dialog);
  const kind = dialog.querySelector<HTMLSelectElement>('[name="kind"]')!,
    project = dialog.querySelector<HTMLSelectElement>('[name="project"]')!;
  const content = dialog.querySelector<HTMLElement>('[data-content]')!,
    notice = dialog.querySelector<HTMLElement>('[data-notice]')!,
    error = dialog.querySelector<HTMLElement>('[data-error]')!;
  let stamp = '',
    selectedIdentity = '',
    readRevision = 0;
  const fail = (e: unknown) => {
    error.textContent = e instanceof Error ? e.message : errorMessage(e);
    error.hidden = false;
  };
  const scope = () => project.value || undefined;
  const renderJobs = () => {
    const jobs = state().jobs.filter((row) => row.source === 'maintenance');
    const target = dialog.querySelector<HTMLElement>('[data-jobs]')!;
    const open = new Set(
      Array.from(
        target.querySelectorAll<HTMLDetailsElement>(
          '[data-maintenance-detail]',
        ),
      )
        .filter((node) => node.open)
        .map((node) => node.dataset.maintenanceDetail),
    );
    const names: Record<string, string> = {
      'agent.curate': 'Improve project agents',
      'memory.digest': 'Consolidate memories',
      'conversation.resume': 'Continue conversation',
    };
    target.innerHTML = jobs.length
      ? `<details class="operator-recent-jobs" data-maintenance-detail="recent"><summary>Recent maintenance jobs (${jobs.length})</summary>${jobs
          .slice(-20)
          .reverse()
          .map(
            (job) =>
              `<article><strong>${esc(names[job.agent] ?? job.agent)}</strong> · ${esc(job.status)}${job.text ? `<p>${esc(job.text)}</p>` : ''}<details data-maintenance-detail="${esc(job.id)}"><summary>Job details</summary><p>${esc(job.id)}${job.ending ? ` · ${esc(job.ending)}` : ''}</p>${job.detail ? `<pre>${esc(job.detail)}</pre>` : ''}</details></article>`,
          )
          .join('')}</details>`
      : '';
    for (const node of target.querySelectorAll<HTMLDetailsElement>(
      '[data-maintenance-detail]',
    ))
      node.open = open.has(node.dataset.maintenanceDetail);
  };
  function render() {
    if (!dialog.open) return;
    renderJobs();
    const view = state().operator;
    if (
      !view ||
      view.kind !== kind.value ||
      (view.project ?? '') !== project.value ||
      view.identity !== selectedIdentity
    )
      return;
    kind.disabled = project.disabled = !!view.busy;
    if (!view.busy && kind.value === 'approval-grant') project.disabled = true;
    notice.textContent = view.notice ?? '';
    if (view.error) fail(view.error);
    const next = JSON.stringify([
      view.identity,
      view.data,
      view.preview,
      view.receipt,
      view.busy,
    ]);
    if (next === stamp) return;
    stamp = next;
    if (view.preview) {
      const payload = view.preview.payload,
        subject = view.preview.subject;
      content.innerHTML = `<h3>Review change</h3><p>${esc(labels[view.kind])} · ${esc(view.kind === 'approval-grant' ? 'Approval’s own conversation/project' : (view.project ?? 'Global'))}</p>${view.kind === 'caps' ? '<p>Review the resulting cap configuration.</p>' : `<p>${esc(view.preview.summary)}</p>`}${
        subject
          ? `<dl>${(
              [
                'agent',
                'conversation',
                'title',
                'side',
                'cwd',
                'scope',
              ] as const
            )
              .filter((k) => subject[k] != null)
              .map((k) => `<dt>${esc(k)}</dt><dd>${esc(subject[k])}</dd>`)
              .join(
                '',
              )}</dl>${subject.command || subject.commands ? `<pre>${esc(JSON.stringify(subject.commands ?? subject.command))}</pre>` : ''}`
          : ''
      }${
        view.kind === 'caps'
          ? `<pre>${esc(view.preview.summary)}</pre>`
          : `<dl>${Object.entries(payload)
              .map(([key, value]) => {
                if (key === 'requestId') return '';
                const names: Record<string, string> = {
                  conversation: 'Conversation',
                  lifecycle: 'Lifecycle',
                  job: 'Job',
                  id: 'Approval',
                  decision: 'Decision',
                  prefix: 'Command prefix',
                  topic: 'Topic',
                  maxModelCalls: 'Model-call allowance',
                  maxTurns: 'Turn limit',
                  project: 'Project',
                  proposal: 'Memory',
                };
                if (
                  key === 'proposal' &&
                  'proposal' in payload &&
                  typeof payload.proposal === 'object'
                ) {
                  const memory = payload.proposal;
                  return `<dt>Summary and scope</dt><dd>${esc(memory.summary)} · ${esc(memory.scope)}</dd><dt>Memory body</dt><dd><pre>${esc(memory.body)}</pre></dd>`;
                }
                return `<dt>${esc(names[key] ?? key)}</dt><dd>${esc(isList(value) ? value.map((v) => JSON.stringify(v)).join(' · ') : value)}</dd>`;
              })
              .join('')}</dl>`
      }<label class="operator-confirm"><input data-confirm type="checkbox">I reviewed this change and its scope.</label><div class="dialog-actions"><button data-back type="button" ${view.busy ? 'disabled' : ''}>Back to editing</button><button data-apply type="button" disabled>${view.kind === 'caps' ? 'Save and apply caps' : 'Confirm change'}</button></div>`;
      if (view.kind === 'message-deliveries' && subject) {
        const details = document.createElement('section');
        details.innerHTML = `<p>${esc(subject.sender)} → ${esc(subject.recipient)} · ${esc(subject.state)}</p><pre>${esc(subject.body)}</pre>`;
        content.prepend(details);
      }
      return;
    }
    const data = view.data;
    let fields = '';
    if (view.kind.startsWith('message-')) {
      const instances = data.instances ?? [];
      fields += `<div class="operator-items">${instances.map((r) => `<article><strong>${esc(r.agent)}</strong> · ${esc(r.state)}${r.defaultInstance ? ' · Default' : ''}<p>${esc(r.id)} · ${esc(r.pending)} pending</p></article>`).join('') || '<p>No message instances in this project.</p>'}</div>`;
      if (view.kind === 'message-deliveries') {
        fields +=
          select(
            'instance',
            'Instance to inspect',
            instances.map((r) => [
              String(r.id),
              `${displayText(r.agent)} · ${displayText(r.state)} · ${displayText(r.id)}`,
            ]),
          ) + '<button data-message-read type="button">Read messages</button>';
        const deliveries = data.deliveries ?? [];
        if (data.messageInstance)
          fields += `<div class="operator-items">${deliveries.map((r) => `<article><strong>${esc(r.state)}</strong> · ${esc(r.message)}${r.replyExpected ? ' · Reply expected' : ''}${r.generated ? ' · Harness outcome' : ''}<p>${esc(r.sender)} → ${esc(r.recipient)}${r.ending ? ` · ${esc(r.ending)}` : ''}</p><pre>${esc(r.body)}</pre></article>`).join('') || '<p>No messages on this page.</p>'}</div>${data.messageMore ? '<button data-message-more type="button">Older messages</button>' : ''}`;
        const pending = deliveries.filter((r) =>
          ['queued', 'running', 'awaiting'].includes(String(r.state)),
        );
        if (pending.length)
          fields += select(
            'id',
            'Message to cancel',
            pending.map((r) => [
              String(r.message),
              `${displayText(r.state)} · ${displayText(r.message)}`,
            ]),
          );
        else fields += '<p>No pending message on this page to cancel.</p>';
      } else if (view.kind === 'message-open')
        fields +=
          select(
            'agent',
            'Agent or bot',
            (data.agents ?? []).map((r) => [String(r.name), String(r.name)]),
          ) +
          select('makeDefault', 'Use for messages addressed by name', [
            ['false', 'Keep current default'],
            ['true', 'Make this the default'],
          ]);
      else
        fields += select(
          'id',
          'Instance',
          instances
            .filter((r) =>
              view.kind === 'message-default'
                ? r.active && r.lifetime === 'persistent'
                : view.kind !== 'message-archive' || r.lifetime !== 'caller',
            )
            .map((r) => [
              String(r.id),
              `${displayText(r.agent)} · ${displayText(r.state)} · ${displayText(r.id)}`,
            ]),
        );
    } else if (view.kind.startsWith('conversation-')) {
      fields += select(
        'id',
        'Conversation',
        (data.conversations ?? []).map((r) => [
          String(r.id),
          `${displayText(r.title ?? r.id)} · ${displayText(r.lifecycle ?? 'active')}`,
        ]),
      );
      if (view.kind === 'conversation-lifecycle')
        fields += select('lifecycle', 'Lifecycle', [
          ['archived', 'Archived'],
          ['active', 'Active'],
        ]);
      else
        fields +=
          field('maxTurns', 'Turn limit', 'number', '10') +
          field('maxModelCalls', 'Model-call allowance', 'number', '20');
    } else if (view.kind.startsWith('approval-')) {
      fields += select(
        'id',
        'Command approval',
        (data.approvals ?? []).map((r) => [
          String(r.id),
          `${displayText(isList(r.command) ? r.command.join(' ') : r.id)} · ${displayText(r.side)} · ${displayText(r.cwd)}`,
        ]),
      );
      fields += `<div class="operator-items">${
        (data.approvals ?? [])
          .map(
            (r) =>
              `<article><strong>${esc(r.id)}</strong><pre>${esc((isList(r.commands) ? r.commands : [r.command]).map((c) => (isList(c) ? c.join(' ') : '')).join('\n'))}</pre><p>${esc(r.reason)} · ${esc(r.scope ?? 'awaiting decision')}</p></article>`,
          )
          .join('') || '<p>No approvals in this scope.</p>'
      }</div>`;
      if (view.kind === 'approval-grant')
        fields +=
          select('decision', 'Decision scope', [
            ['once', 'Allow once'],
            ['conversation', 'Allow for this conversation'],
            ['project', 'Allow this prefix for the project'],
            ['deny', 'Deny'],
          ]) +
          '<label>Project command prefix (one argument per line)<textarea name="prefix" rows="3" maxlength="8192"></textarea></label>';
    } else if (view.kind === 'memory-write')
      fields +=
        field('summary', 'Summary') +
        field('scope', 'When this memory applies') +
        '<label>Memory body<textarea name="body" rows="8" maxlength="100000" required></textarea></label>';
    else if (view.kind === 'memory-digest')
      fields +=
        '<p>Digest existing conversations and memories in this scope using the configured model. The accepted job is followed below.</p>';
    else if (view.kind === 'agent-curate')
      fields +=
        field('maxModelCalls', 'Model-call allowance', 'number', '20') +
        '<p>Curation uses the configured model and may revise this project’s agents.</p>';
    else if (view.kind === 'job-limits')
      fields +=
        select(
          'id',
          'Job',
          (data.jobs ?? []).map((r) => [
            String(r.id),
            `${displayText(r.agent ?? 'job')} · ${displayText(r.state)} · ${displayText(r.id)}`,
          ]),
        ) +
        field('maxTurns', 'Turn limit', 'number', '10') +
        field('maxModelCalls', 'Model-call allowance', 'number', '20');
    else if (view.kind === 'board-topup')
      fields +=
        select(
          'id',
          'Topic',
          (data.topics ?? []).map((r) => {
            const t = r.topic;
            return [
              String(t.id),
              `${displayText(t.title ?? t.id)} · ${displayText(t.state)} · ${displayText(t.potSpent ?? 0)}/${displayText(t.potTotal ?? 'unset')} calls`,
            ];
          }),
        ) +
        field(
          'maxModelCalls',
          'New total model-call allowance',
          'number',
          '140',
        );
    else if (view.kind === 'caps') {
      const caps = data.caps,
        file = data.file;
      if (!caps || !file)
        throw new Error(
          'The cap configuration is unavailable. Refresh options.',
        );
      fields += `<p>Caps are saved in this project’s local configuration and then reloaded by the server.</p><dl>${(
        [
          'steps',
          'budget',
          'autoContinue',
          'time',
          'failedChecks',
          'autoIncrease',
        ] as const
      )
        .map((k) => {
          const setting = caps[k];
          return `<dt>${esc(k)}</dt><dd>${esc(setting.value ?? 'unset')} · ${esc(setting.source)}</dd>`;
        })
        .join(
          '',
        )}</dl>${caps.said ? `<p>${esc(caps.said)}</p>` : ''}<details><summary>Current configuration</summary><pre>${esc(file.source ?? 'No project configuration file yet.')}</pre></details>`;
      fields +=
        select('key', 'Cap', [
          ['steps', 'Steps per turn'],
          ['budget', 'Model calls per run'],
          ['auto-continue', 'Caps continued automatically'],
          ['time', 'Time limit in minutes'],
          ['failed-checks', 'Failed checks before asking'],
          ['auto-increase', 'Automatically increase steps and budget'],
        ]) +
        '<div data-cap-value><label>Value<input type="number" name="value" required min="0" max="1000000" value="10"></label></div>';
    }
    const empty =
      view.kind.startsWith('conversation-') &&
      !(data.conversations ?? []).length
        ? 'No conversations in this workspace. Choose another workspace or create a conversation first.'
        : view.kind === 'job-limits' && !(data.jobs ?? []).length
          ? 'No running jobs have adjustable limits. Start work in a conversation first.'
          : view.kind.startsWith('approval-') && !(data.approvals ?? []).length
            ? 'No command permissions to review in this workspace.'
            : view.kind === 'board-topup' && !(data.topics ?? []).length
              ? 'No open root topics in this workspace. Open Board to create or inspect a topic.'
              : '';
    if (empty)
      content.innerHTML = `<p class="operator-empty">${esc(empty)}</p>`;
    else
      content.innerHTML = `<form data-change>${fields}<div class="dialog-actions"><button type="submit" class="primary-button">Review change</button></div></form>`;
    const capSelector =
      view.kind === 'caps'
        ? content.querySelector<HTMLSelectElement>('[name="key"]')
        : null;
    capSelector?.addEventListener('change', () => {
      const slot = content.querySelector<HTMLElement>('[data-cap-value]');
      if (slot)
        slot.innerHTML =
          capSelector.value === 'auto-increase'
            ? select('value', 'Automatic increases', [
                ['0', 'Disabled'],
                ['1', 'Enabled'],
              ])
            : '<label>Value<input type="number" name="value" required min="0" max="1000000" value="10"></label>';
    });
    const messageSelector =
      content.querySelector<HTMLSelectElement>('[name="instance"]');
    if (messageSelector && typeof view.data.messageInstance === 'string')
      messageSelector.value = view.data.messageInstance;
    if (
      view.kind === 'message-deliveries' &&
      !content.querySelector('[name="id"]')
    ) {
      const submit = content.querySelector<HTMLButtonElement>(
        '[data-change] button[type="submit"]',
      );
      if (submit) submit.disabled = true;
    }
    if (view.receipt) {
      const receipt = view.receipt,
        payload = receipt;
      const result = document.createElement('section');
      result.innerHTML = `<h3>Confirmed result</h3><p>${esc(receipt.code ?? 'Server cap status')}${payload.id || payload.job ? ` · ${esc(payload.id ?? payload.job)}` : ''}${payload.revoked !== undefined ? ` · ${payload.revoked ? 'Grant revoked' : 'No standing grant changed'}` : ''}${payload.kind ? ` · ${esc(payload.kind)}` : ''}${payload.busy ? ' · The conversation is busy; the decision is recorded.' : ''}</p>${payload.note || receipt.said ? `<p>${esc(payload.note ?? receipt.said)}</p>` : ''}`;
      content.prepend(result);
    }
  }
  function describeTask() {
    dialog.querySelector<HTMLElement>('[data-task-title]')!.textContent =
      labels[kind.value as OperatorKind];
    dialog.querySelector<HTMLElement>('[data-task-description]')!.textContent =
      descriptions[kind.value as OperatorKind];
  }
  async function read() {
    const revision = ++readRevision;
    error.hidden = true;
    selectedIdentity = '';
    stamp = '';
    notice.textContent = '';
    describeTask();
    if (
      (['agent-curate', 'approval-revoke', 'caps'].includes(kind.value) ||
        kind.value.startsWith('message-')) &&
      !project.value
    ) {
      content.innerHTML =
        '<p class="operator-empty">Choose a project workspace above to continue.</p>';
      return;
    }
    content.innerHTML = '<p role="status">Loading available options…</p>';
    const selected = `${kind.value}|${project.value}`;
    try {
      const selectedProject = scope();
      const reply = await api.request({
        action: 'operator-prepare',
        kind: kind.value as OperatorKind,
        ...(selectedProject === undefined ? {} : { project: selectedProject }),
      });
      if (
        revision !== readRevision ||
        selected !== `${kind.value}|${project.value}` ||
        !dialog.open
      )
        return;
      selectedIdentity = reply.state.operator!.identity;
      render();
    } catch (e) {
      if (
        revision !== readRevision ||
        selected !== `${kind.value}|${project.value}` ||
        !dialog.open
      )
        return;
      fail(e);
      content.innerHTML =
        '<p>Options could not be loaded. Use Refresh options to try again.</p>';
    }
  }
  dialog.addEventListener('click', (event) => {
    const button = (event.target as HTMLElement).closest('button');
    if (!button) return;
    if (
      button.hasAttribute('data-message-read') ||
      button.hasAttribute('data-message-more')
    ) {
      const view = state().operator;
      if (!view) return;
      const instance = button.hasAttribute('data-message-more')
        ? String(view.data.messageInstance)
        : content.querySelector<HTMLSelectElement>('[name="instance"]')?.value;
      if (!instance) return;
      const offset = button.hasAttribute('data-message-more')
        ? Number(view.data.messageOffset ?? 0) + 200
        : 0;
      void api
        .request({
          action: 'operator-messages',
          identity: selectedIdentity,
          instance,
          offset,
        })
        .then(render)
        .catch(fail);
    }
    if (button.hasAttribute('data-close')) {
      dialog.close();
      options.close?.();
    }
    if (button.hasAttribute('data-read') || button.hasAttribute('data-back'))
      background(read());
    if (button.hasAttribute('data-apply')) {
      const view = state().operator;
      if (!view?.preview) return;
      void api
        .request({ action: 'operator-apply', identity: view.preview.identity })
        .then(render)
        .catch(fail);
    }
  });
  dialog.addEventListener('change', (event) => {
    if (event.target === kind) {
      project.disabled = kind.value === 'approval-grant';
      if (project.disabled) project.value = '';
    }
    if (event.target === kind || event.target === project) {
      background(read());
    }
    const confirmation =
        dialog.querySelector<HTMLInputElement>('[data-confirm]'),
      apply = dialog.querySelector<HTMLButtonElement>('[data-apply]');
    if (confirmation && apply)
      apply.disabled = !confirmation.checked || state().operator?.busy === true;
  });
  dialog.addEventListener('submit', (event) => {
    event.preventDefault();
    const form = event.target as HTMLFormElement,
      input = Object.fromEntries(new FormData(form));
    for (const name of ['maxTurns', 'maxModelCalls', 'value'])
      if (input[name] !== undefined)
        (input as Record<string, unknown>)[name] = Number(input[name]);
    if (input.prefix !== undefined)
      input.prefix = JSON.stringify(
        displayText(input.prefix)
          .split('\n')
          .filter((s) => s.trim().length > 0),
      );
    void api
      .request({
        action: 'operator-preview',
        identity: selectedIdentity,
        input: decodeOperatorInput(input),
      })
      .then(render)
      .catch(fail);
  });
  dialog.addEventListener('close', () => {
    readRevision++;
    selectedIdentity = '';
  });
  api.subscribe(render);
  return {
    close() {
      dialog.close();
    },
    open() {
      if (!state().connected) throw new Error('Connect to manage work.');
      project.innerHTML = `<option value="">Global workspace</option>${state()
        .projects.map(
          (p) => `<option value="${esc(p.name)}">${esc(p.name)}</option>`,
        )
        .join('')}`;
      project.value = activeProject();
      project.disabled = kind.value === 'approval-grant';
      if (project.disabled) project.value = '';
      selectedIdentity = '';
      stamp = '';
      error.hidden = true;
      notice.textContent = '';
      kind.disabled = false;
      if (!dialog.open) {
        if (options.host) dialog.show();
        else dialog.showModal();
      }
      renderJobs();
      background(read());
    },
  };
}
