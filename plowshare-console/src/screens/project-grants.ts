import type { ApprovalView } from '../../../sdk/typescript/src/operations/administrative-replies.ts';
import { ApprovalRefused, type ProjectGrants } from '../approvals';
import { background } from '../background';
import { button, el, nothing, problemText } from './dom';
import type { Screen } from './screen';

export const NOTHING_APPROVED =
  'No command is approved for this whole project. A run that asks is asked again each time,' +
  ' unless a person allowed it for one conversation.';

/** A bounded view of standing grants. Pausing invalidates in-flight reads;
 * unknown mutations remain blocked across parent redraws through the owner's map.
 * No event, refresh or reconnection can repeat a revocation. */
export function createProjectGrants(options: {
  root: HTMLElement;
  project: string;
  client: ProjectGrants;
  active: boolean;
  blocked: Map<string, string>;
}): Screen {
  const head = el('div', 'approved-head', 'approved commands');
  const status = el('p', 'approved-status');
  status.setAttribute('role', 'status');
  const result = el('p', 'approved-result');
  result.setAttribute('role', 'status');
  const error = el('p', 'trouble');
  error.setAttribute('role', 'alert');
  error.hidden = true;
  const rows = el('div', 'approved-rows');
  const previous = button('previous', 'Previous grants');
  const next = button('next', 'Next grants');
  options.root.replaceChildren(
    head,
    status,
    result,
    error,
    rows,
    previous,
    next,
  );
  let stopped = false;
  let active = options.active;
  let epoch = 0;
  let reading = false;
  let asked = false;
  let fresh = false;
  let offset = 0;
  const windowSize = 30;
  let held: readonly ApprovalView[] = [];

  function draw(): void {
    offset = Math.min(
      offset,
      Math.max(0, Math.ceil(held.length / windowSize) - 1) * windowSize,
    );
    previous.disabled = !active || offset === 0;
    next.disabled = !active || offset + windowSize >= held.length;
    if (!fresh && held.length === 0) {
      const waiting = el(
        'p',
        'nothing',
        active
          ? 'Reading project grants…'
          : 'Waiting for an active connection…',
      );
      waiting.dataset['connecting'] = '';
      rows.replaceChildren(waiting);
      return;
    }
    if (held.length === 0) {
      rows.replaceChildren(nothing(NOTHING_APPROVED));
      return;
    }
    const list = el('ul', 'approved-list');
    for (const grant of held.slice(offset, offset + windowSize)) {
      const item = el('li', 'approved-item');
      item.dataset['approval'] = grant.id;
      // JSON argv keeps spaces and quotes distinguishable from separate arguments.
      item.append(
        el(
          'code',
          'approved-prefix',
          JSON.stringify(grant.prefix ?? grant.command),
        ),
        el('span', 'approved-side', `${grant.side} side, any directory`),
      );
      const revoke = button('revoke', 'Revoke');
      revoke.dataset['revoke'] = grant.id;
      revoke.disabled = !active || !fresh || options.blocked.has(grant.id);
      revoke.addEventListener('click', () => background(answer(grant.id)));
      item.append(revoke);
      const note = options.blocked.get(grant.id);
      if (note !== undefined) item.append(el('p', 'approved-note', note));
      list.append(item);
    }
    rows.replaceChildren(list);
  }

  async function answer(id: string): Promise<void> {
    if (stopped || !active || !fresh || options.blocked.has(id)) return;
    options.blocked.set(id, 'Sending revocation…');
    draw();
    try {
      const receipt = await options.client.revoke(id);
      options.blocked.set(
        id,
        receipt.revoked
          ? 'Revocation recorded. Refreshing grants…'
          : 'That grant is no longer standing. Refreshing grants…',
      );
    } catch (problem) {
      if (problem instanceof ApprovalRefused) {
        options.blocked.delete(id);
        if (!stopped) result.textContent = problem.message;
      } else {
        options.blocked.set(
          id,
          'Delivery is uncertain. Revocation was not replayed. Refresh to inspect current grants.',
        );
      }
    }
    if (stopped) return;
    if (active) draw();
    await load();
  }

  async function load(): Promise<void> {
    if (stopped || !active) return;
    if (reading) {
      asked = true;
      return;
    }
    reading = true;
    try {
      do {
        asked = false;
        const stamp = epoch;
        fresh = false;
        draw();
        try {
          const snapshot = await options.client.list(options.project);
          if (!stopped && active && stamp === epoch) {
            held = snapshot;
            fresh = true;
            error.hidden = true;
            status.textContent = `${held.length} standing grants. Showing at most ${windowSize}; server replies are unpaged.`;
            draw();
          }
        } catch (problem) {
          if (!stopped && active && stamp === epoch) {
            error.textContent = problemText(
              problem,
              'Project grants could not be read.',
            );
            error.hidden = false;
            status.textContent =
              'Current grants are unavailable. Refresh before taking action.';
            draw();
          }
        }
      } while (asked && !stopped && active);
    } finally {
      reading = false;
    }
  }

  previous.addEventListener('click', () => {
    offset = Math.max(0, offset - windowSize);
    draw();
  });
  next.addEventListener('click', () => {
    offset += windowSize;
    draw();
  });
  draw();
  return {
    element: () => options.root,
    load,
    setActive(nextActive) {
      if (stopped || active === nextActive) return;
      active = nextActive;
      ++epoch;
      fresh = false;
      asked = false;
      draw();
      if (active) background(load());
    },
    destroy() {
      stopped = true;
      active = false;
      ++epoch;
      asked = false;
    },
  };
}
