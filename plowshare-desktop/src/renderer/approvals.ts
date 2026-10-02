import type { Approval, DesktopState, Request, Reply } from '../shared.ts';
import { escapeHtml as esc } from './markdown.ts';
import { icon } from './icons.ts';

/** Older servers include the approval id in their question; never infer one from a command. */
export function noticeApproval(state: DesktopState, notice: { kind: string; about?: string; answer: string }): Approval | undefined {
  if (notice.kind !== 'approval') return;
  const id = notice.about?.startsWith('approval:') ? notice.about.slice('approval:'.length)
    : !notice.about && notice.answer.startsWith('Approve running ') ? /\[([^\]\r\n]+)\]$/.exec(notice.answer)?.[1] : undefined;
  return state.approvals.find(row => row.id === id && row.state === 'asked');
}

/** The same reviewed command and exact decision on every surface displaying a request. */
export function installApprovalControls(host: Document | HTMLElement, state: () => DesktopState,
  send: (command: Request) => Promise<Reply>, changed: () => void) {
  const pending = new Set<string>();
  const errors = new Map<string, string>();
  let revision = 0;
  host.addEventListener('click', async event => {
    const button = (event.target as HTMLElement).closest<HTMLButtonElement>('[data-approval][data-decision]');
    const id = button?.dataset.approval, decision = button?.dataset.decision;
    if (!id || button?.disabled || pending.has(id) || state().answeringApprovals?.includes(id) || (decision !== 'once' && decision !== 'deny')) return;
    if (state().mode === 'live' && !state().connected) return;
    if (!state().approvals.some(row => row.id === id && row.state === 'asked')) return;
    pending.add(id); errors.delete(id); revision++; changed();
    try { await send({ action: 'answer', id, decision }); }
    catch (reason) { errors.set(id, reason instanceof Error ? reason.message : String(reason)); }
    finally { pending.delete(id); revision++; changed(); }
  });
  return {
    get revision() { return revision; },
    prompt(row: Approval, context = '') {
      const busy = pending.has(row.id) || state().answeringApprovals?.includes(row.id);
      const disabled = busy || (state().mode === 'live' && !state().connected);
      return `<section class="command-approval" data-approval-prompt="${esc(row.id)}" aria-label="Command approval"><strong class="section-label">${icon('shield')}Approval requested · ${esc(row.side)}${context ? ` · ${esc(context)}` : ''}</strong><pre>${esc((row.commands ?? [row.command]).map(command => command.join(' ')).join('\n'))}</pre><p>${esc(row.cwd)}${row.reason ? ` · ${esc(row.reason)}` : ''}</p><div class="approval-actions"><button data-approval="${esc(row.id)}" data-decision="once" ${disabled ? 'disabled' : ''}>${icon('check')}${busy ? 'Recording…' : 'Approve'}</button><button data-approval="${esc(row.id)}" data-decision="deny" ${disabled ? 'disabled' : ''}>${icon('close')}Deny</button><span>Approval allows this command once.</span></div>${errors.has(row.id) ? `<p class="approval-error" role="alert">${esc(errors.get(row.id))}</p>` : ''}</section>`;
    },
  };
}
