import type { DesktopApi, DesktopState } from '../shared.ts';
import { OPERATOR_KINDS, type OperatorKind } from '../operator-shared.ts';
const esc = (value: unknown) => String(value ?? '').replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]!));
const obj = (v: unknown): Record<string, unknown> => v && typeof v === 'object' ? v as Record<string, unknown> : {};
const rows = (v: unknown) => Array.isArray(v) ? v.map(obj) : [];
const labels: Record<OperatorKind, string> = { 'memory-write': 'Create memory', 'memory-digest': 'Digest memories', 'agent-curate': 'Curate project agents', 'conversation-lifecycle': 'Archive or restore conversation', 'conversation-resume': 'Resume conversation', 'job-limits': 'Change job limits', 'approval-grant': 'Decide command approval', 'approval-revoke': 'Revoke standing command grant', 'board-topup': 'Top up Board topic', caps: 'Project orchestration caps' };
const field = (name: string, label: string, type = 'text', value = '') => `<label>${label}<input name="${name}" type="${type}" value="${esc(value)}" ${type === 'number' ? 'min="1" max="1000000"' : 'maxlength="8000"'} required></label>`;
const select = (name: string, label: string, options: [string, string][]) => `<label>${label}<select name="${name}" required>${options.map(([id,label]) => `<option value="${esc(id)}">${esc(label)}</option>`).join('')}</select></label>`;
export function installOperator(api: DesktopApi, state: () => DesktopState, activeProject: () => string) {
  const dialog = document.createElement('dialog'); dialog.className = 'operator-dialog'; dialog.setAttribute('aria-label', 'Workspace controls');
  dialog.innerHTML = `<header><div><div class="eyebrow">WORKSPACE CONTROLS</div><h2>Manage work</h2></div><button data-close type="button">Close</button></header><div class="operator-toolbar">${select('kind', 'Control', OPERATOR_KINDS.map(k => [k, labels[k]]))}${select('project', 'Scope', [])}<button data-read type="button">Read current state</button></div><p data-error role="alert" hidden></p><p data-notice role="status"></p><div data-content><p>Choose a control and read the current state.</p></div><section data-jobs></section>`;
  document.body.append(dialog);
  const kind = dialog.querySelector<HTMLSelectElement>('[name="kind"]')!, project = dialog.querySelector<HTMLSelectElement>('[name="project"]')!;
  const content = dialog.querySelector<HTMLElement>('[data-content]')!, notice = dialog.querySelector<HTMLElement>('[data-notice]')!, error = dialog.querySelector<HTMLElement>('[data-error]')!;
  let stamp = '', selectedIdentity = '', loading = false;
  const fail = (e: unknown) => { error.textContent = e instanceof Error ? e.message : String(e); error.hidden = false; };
  const scope = () => project.value || undefined;
  const renderJobs = () => {
    const jobs = state().jobs.filter(row => row.source === 'maintenance');
    const target = dialog.querySelector<HTMLElement>('[data-jobs]')!;
    target.innerHTML = jobs.length ? `<h3>Maintenance jobs</h3>${jobs.slice(-20).reverse().map(job => `<article><strong>${esc(job.agent)}</strong> · ${esc(job.status)}<p>${esc(job.id)}${job.ending ? ` · ${esc(job.ending)}` : ''}</p>${job.detail ? `<p>${esc(job.detail)}</p>` : ''}${job.text ? `<pre>${esc(job.text)}</pre>` : ''}</article>`).join('')}` : '';
  };
  function render() {
    if (!dialog.open) return;
    renderJobs(); const view = state().operator;
    if (!view || view.kind !== kind.value || (view.project ?? '') !== project.value || view.identity !== selectedIdentity) return;
    notice.textContent = view.notice ?? '';
    if (view.error) fail(view.error);
    const next = JSON.stringify([view.identity, view.preview, view.receipt, view.busy]); if (next === stamp) return; stamp = next;
    if (view.preview) {
      const payload = view.preview.payload, subject = view.preview.subject;
      content.innerHTML = `<h3>Review change</h3><p>${esc(labels[view.kind])} · ${esc(view.kind === 'approval-grant' ? 'Approval’s own conversation/project' : view.project ?? 'Global')}</p>${view.kind === 'caps' ? '<p>Review the resulting cap configuration.</p>' : `<p>${esc(view.preview.summary)}</p>`}${subject ? `<dl>${['agent','conversation','title','side','cwd','scope'].filter(k => subject[k] != null).map(k => `<dt>${esc(k)}</dt><dd>${esc(subject[k])}</dd>`).join('')}</dl>${subject.command || subject.commands ? `<pre>${esc(JSON.stringify(subject.commands ?? subject.command))}</pre>` : ''}` : ''}${view.kind === 'caps' ? `<pre>${esc(view.preview.summary)}</pre>` : `<dl>${Object.entries(payload).map(([key,value]) => {
        const names: Record<string,string> = { conversation:'Conversation', lifecycle:'Lifecycle', job:'Job', id:'Approval', decision:'Decision', prefix:'Command prefix', topic:'Topic', maxModelCalls:'Model-call allowance', maxTurns:'Turn limit', project:'Project', proposal:'Memory' };
        if (key === 'proposal') { const memory = obj(value); return `<dt>Summary and scope</dt><dd>${esc(memory.summary)} · ${esc(memory.scope)}</dd><dt>Memory body</dt><dd><pre>${esc(memory.body)}</pre></dd>`; }
        return `<dt>${esc(names[key] ?? key)}</dt><dd>${esc(Array.isArray(value) ? value.map(v => JSON.stringify(v)).join(' · ') : value)}</dd>`;
      }).join('')}</dl>`}<label class="operator-confirm"><input data-confirm type="checkbox">I reviewed this change and its scope.</label><div class="dialog-actions"><button data-back type="button" ${view.busy ? 'disabled' : ''}>Back to current state</button><button data-apply type="button" disabled>${view.kind === 'caps' ? 'Save and apply caps' : 'Apply reviewed change'}</button></div>`;
      return;
    }
    const data = view.data; let fields = '';
    if (view.kind.startsWith('conversation-')) {
      fields += select('id', 'Conversation', rows(data.conversations).map(r => [String(r.id), `${r.label ?? r.id} · ${r.lifecycle ?? 'active'}`]));
      if (view.kind === 'conversation-lifecycle') fields += select('lifecycle', 'Lifecycle', [['archived','Archived'], ['active','Active']]);
      else fields += field('maxTurns','Turn limit','number','10') + field('maxModelCalls','Model-call allowance','number','20');
    } else if (view.kind.startsWith('approval-')) {
      fields += select('id', 'Command approval', rows(data.approvals).map(r => [String(r.id), `${Array.isArray(r.command) ? r.command.join(' ') : r.id} · ${r.side} · ${r.cwd}`]));
      fields += `<div class="operator-items">${rows(data.approvals).map(r => `<article><strong>${esc(r.id)}</strong><pre>${esc((Array.isArray(r.commands) ? r.commands : [r.command]).map(c => Array.isArray(c) ? c.join(' ') : '').join('\n'))}</pre><p>${esc(r.reason)} · ${esc(r.scope ?? 'awaiting decision')}</p></article>`).join('') || '<p>No approvals in this scope.</p>'}</div>`;
      if (view.kind === 'approval-grant') fields += select('decision', 'Decision scope', [['once','Allow once'], ['conversation','Allow for this conversation'], ['project','Allow this prefix for the project'], ['deny','Deny']]) + '<label>Project command prefix (one argument per line)<textarea name="prefix" rows="3" maxlength="8192"></textarea></label>';
    } else if (view.kind === 'memory-write') fields += field('summary','Summary') + field('scope','When this memory applies') + '<label>Memory body<textarea name="body" rows="8" maxlength="100000" required></textarea></label>';
    else if (view.kind === 'memory-digest') fields += '<p>Digest existing conversations and memories in this scope using the configured model. The accepted job is followed below.</p>';
    else if (view.kind === 'agent-curate') fields += field('maxModelCalls','Model-call allowance','number','20') + '<p>Curation uses the configured model and may revise this project’s agents.</p>';
    else if (view.kind === 'job-limits') fields += select('id', 'Job', rows(data.jobs).map(r => [String(r.id), `${r.agent ?? 'job'} · ${r.state} · ${r.id}`])) + field('maxTurns','Turn limit','number','10') + field('maxModelCalls','Model-call allowance','number','20');
    else if (view.kind === 'board-topup') fields += select('id', 'Topic', rows(data.topics).map(r => { const t = obj(r.topic); return [String(t.id), `${t.title ?? t.id} · ${t.state} · ${t.potSpent ?? 0}/${t.potTotal ?? 'unset'} calls`]; })) + field('maxModelCalls','New total model-call allowance','number','140');
    else if (view.kind === 'caps') {
      const caps = obj(data.caps), file = obj(data.file);
      fields += `<p>Caps are saved in this project’s local configuration and then reloaded by the server.</p><dl>${['steps','budget','autoContinue','time','failedChecks'].map(k => {const setting = obj(caps[k]); return `<dt>${esc(k)}</dt><dd>${esc(setting.value ?? 'unset')} · ${esc(setting.source)}</dd>`;}).join('')}</dl>${caps.said ? `<p>${esc(caps.said)}</p>` : ''}<details><summary>Current configuration</summary><pre>${esc(file.source ?? 'No project configuration file yet.')}</pre></details>`;
      fields += select('key', 'Cap', [['steps','Steps per turn'],['budget','Model calls per run'],['auto-continue','Caps continued automatically'],['time','Time limit in minutes'],['failed-checks','Failed checks before asking']]) + '<label>Value<input type="number" name="value" required min="0" max="1000000" value="10"></label>';
    }
    content.innerHTML = `<form data-change>${fields}<div class="dialog-actions"><button type="submit">Review change</button></div></form>`;
    if (view.receipt) {
      const receipt = obj(view.receipt), payload = obj(receipt.payload ?? receipt);
      const result = document.createElement('section'); result.innerHTML = `<h3>Confirmed result</h3><p>${esc(receipt.code ?? 'Server cap status')}${payload.id || payload.job ? ` · ${esc(payload.id ?? payload.job)}` : ''}${payload.revoked !== undefined ? ` · ${payload.revoked ? 'Grant revoked' : 'No standing grant changed'}` : ''}${payload.kind ? ` · ${esc(payload.kind)}` : ''}${payload.busy ? ' · The conversation is busy; the decision is recorded.' : ''}</p>${payload.note || receipt.said ? `<p>${esc(payload.note ?? receipt.said)}</p>` : ''}`; content.prepend(result);
    }
  }
  async function read() {
    if (loading) return; loading = true; error.hidden = true; selectedIdentity = ''; stamp = ''; content.innerHTML = '<p>Reading current state…</p>';
    const selected = `${kind.value}|${project.value}`;
    try { const reply = await api.request({ action:'operator-prepare', kind:kind.value as OperatorKind, project:scope() }); if (selected !== `${kind.value}|${project.value}` || !dialog.open) return; selectedIdentity = reply.state.operator!.identity; render(); }
    catch (e) { if (selected !== `${kind.value}|${project.value}` || !dialog.open) return; fail(e); content.innerHTML = '<p>Current state could not be read. Read again before making a change.</p>'; }
    finally { loading = false; }
  }
  dialog.addEventListener('click', event => {
    const button = (event.target as HTMLElement).closest('button'); if (!button) return;
    if (button.hasAttribute('data-close')) dialog.close();
    if (button.hasAttribute('data-read') || button.hasAttribute('data-back')) void read();
    if (button.hasAttribute('data-apply')) { const view = state().operator; if (!view?.preview) return; void api.request({ action:'operator-apply', identity:view.preview.identity }).then(render).catch(fail); }
  });
  dialog.addEventListener('change', event => {
    if (event.target === kind) { project.disabled = kind.value === 'approval-grant'; if (project.disabled) project.value = ''; }
    if (event.target === kind || event.target === project) { selectedIdentity = ''; stamp = ''; content.innerHTML = '<p>Read current state for this control.</p>'; notice.textContent = ''; error.hidden = true; }
    const confirmation = dialog.querySelector<HTMLInputElement>('[data-confirm]'), apply = dialog.querySelector<HTMLButtonElement>('[data-apply]'); if (confirmation && apply) apply.disabled = !confirmation.checked || state().operator?.busy === true;
  });
  dialog.addEventListener('submit', event => {
    event.preventDefault(); const form = event.target as HTMLFormElement, input = Object.fromEntries(new FormData(form));
    for (const name of ['maxTurns','maxModelCalls','value']) if (input[name] !== undefined) (input as Record<string, unknown>)[name] = Number(input[name]);
    if (input.prefix !== undefined) input.prefix = JSON.stringify(String(input.prefix).split('\n').filter(s => s.trim().length > 0));
    void api.request({ action:'operator-preview', identity:selectedIdentity, input }).then(render).catch(fail);
  });
  api.subscribe(render);
  return { open() {
    if (!state().connected) throw new Error('Connect to manage work.');
    project.innerHTML = `<option value="">Global</option>${state().projects.map(p => `<option value="${esc(p.name)}">${esc(p.name)}</option>`).join('')}`; project.value = activeProject(); project.disabled = kind.value === 'approval-grant'; if (project.disabled) project.value = '';
    selectedIdentity = ''; stamp = ''; content.innerHTML = '<p>Choose a control and read current state.</p>'; error.hidden = true; notice.textContent = ''; dialog.showModal(); renderJobs();
  } };
}
