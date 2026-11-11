import type { WorkspaceRoute } from '../shared.ts';

/**
 * Reusable placement for workspace pages.
 *
 * Workspace pages (trajectory, scheduled work, relay, library, …) are native
 * views owned by the main process. The renderer reports one rectangle and
 * the main process places the active page over it. A slot is any element in
 * the main window that wants to host a page: a panel registers a provider
 * that claims routes it can host and returns the element to cover. When no
 * provider claims the route, the page covers the full workspace host.
 *
 * Providers are consulted in registration order; the first claim wins. A
 * provider must only claim while its element is laid out and visible, since
 * the main process trusts the reported bounds. Call `changed` whenever a
 * provider's answer or its element's position may have changed.
 * Registration notifies asynchronously, so panels may register while the
 * host module is still initialising.
 */
export type WorkspaceSlotProvider = (
  route: WorkspaceRoute,
) => HTMLElement | undefined;

export interface WorkspaceSlots {
  /** Add a provider; returns a function that removes it again. */
  register(provider: WorkspaceSlotProvider): () => void;
  /** Re-measure after slot elements resize. */
  observe(element: Element): void;
  /** The element that should host this route, if any slot claims it. */
  resolve(route: WorkspaceRoute): HTMLElement | undefined;
  /** A provider's claim or slot position changed; re-place the page. */
  changed(): void;
}

export function createWorkspaceSlots(onChange: () => void): WorkspaceSlots {
  const providers: WorkspaceSlotProvider[] = [];
  const resize = new ResizeObserver(() => onChange());
  return {
    register(provider) {
      providers.push(provider);
      queueMicrotask(onChange);
      return () => {
        const index = providers.indexOf(provider);
        if (index >= 0) providers.splice(index, 1);
        queueMicrotask(onChange);
      };
    },
    observe(element) {
      resize.observe(element);
    },
    resolve(route) {
      for (const provider of providers) {
        const slot = provider(route);
        if (slot) return slot;
      }
      return undefined;
    },
    changed: onChange,
  };
}

const escapeText = (value: string) =>
  value.replace(
    /[&<>]/g,
    (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' })[character]!,
  );

/**
 * Markup for a slot element. The placeholder sits under the native page:
 * it is visible only while the page loads or when it could not be opened.
 */
export function workspaceSlotMarkup(name: string, status: string): string {
  return `<div class="workspace-slot" data-workspace-slot="${escapeText(name)}"><div class="workspace-slot-placeholder"><p data-slot-status>${escapeText(status)}</p><p data-slot-error role="alert" hidden></p><button type="button" data-slot-retry hidden>Try again</button></div></div>`;
}

/** Show a failure (with a retry button) in the slot, or clear it. */
export function renderWorkspaceSlotError(slot: HTMLElement, error: string) {
  const failure = slot.querySelector<HTMLElement>('[data-slot-error]')!;
  failure.hidden = !error;
  failure.textContent = error;
  slot.querySelector<HTMLElement>('[data-slot-status]')!.hidden = !!error;
  slot.querySelector<HTMLElement>('[data-slot-retry]')!.hidden = !error;
}
