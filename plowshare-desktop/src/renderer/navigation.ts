import { escapeHtml } from './markdown.ts';
import { icon } from './icons.ts';
import type { Icon } from './icons.ts';

export interface NavigationItem {
  id: string;
  title: string;
  detail: string;
  group: 'Conversations' | 'Workspaces' | 'Actions';
  icon: Icon;
  shortcut?: string;
  disabled?: boolean;
  run(): void | Promise<void>;
}

/** Local navigation over loaded names. This never searches conversation contents. */
export function installNavigation(items: () => NavigationItem[], onError: (message: string) => void) {
  const dialog = document.querySelector<HTMLDialogElement>('#navigation-dialog')!;
  const input = document.querySelector<HTMLInputElement>('#navigation-query')!;
  const results = document.querySelector<HTMLElement>('#navigation-results')!;
  const count = document.querySelector<HTMLElement>('#navigation-count')!;
  let rows: NavigationItem[] = [];
  let active = -1;
  let previousFocus: HTMLElement | null = null;
  let restoreFocus = true;

  function select(index: number) {
    active = index;
    results.querySelectorAll<HTMLElement>('[data-navigation-index]').forEach((row, i) => row.setAttribute('aria-selected', String(i === active)));
    if (active >= 0) input.setAttribute('aria-activedescendant', `navigation-option-${active}`);
    else input.removeAttribute('aria-activedescendant');
  }
  function refresh(reset = false) {
    if (!dialog.open) return;
    const previous = reset ? undefined : rows[active]?.id;
    const terms = input.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    rows = items().filter(row => terms.every(term => `${row.title} ${row.detail} ${row.group}`.toLowerCase().includes(term)));
    let group = '';
    const html = rows.map((row, i) => {
      const heading = row.group !== group ? `<div class="navigation-group" role="presentation">${escapeHtml(row.group)}</div>` : '';
      group = row.group;
      return `${heading}<button id="navigation-option-${i}" class="navigation-result" role="option" tabindex="-1" data-navigation-index="${i}" aria-selected="false" ${row.disabled ? 'disabled aria-disabled="true"' : ''}>${icon(row.icon)}<span><strong>${escapeHtml(row.title)}</strong><small>${escapeHtml(row.detail)}</small></span>${row.shortcut ? `<kbd>${escapeHtml(row.shortcut)}</kbd>` : ''}</button>`;
    }).join('') || `<div class="navigation-empty">${icon('search')}<p>No matching names or actions.</p></div>`;
    // Do not reset the result list's scroll position on every published token.
    if (results.dataset.content !== html) { results.innerHTML = html; results.dataset.content = html; }
    count.textContent = `${rows.length} ${rows.length === 1 ? 'result' : 'results'}`;
    const previousIndex = rows.findIndex(row => row.id === previous && !row.disabled);
    select(previousIndex >= 0 ? previousIndex : rows.findIndex(row => !row.disabled));
  }
  function open() {
    if (dialog.open) { input.focus(); return; }
    if (document.querySelector('dialog[open]')) return;
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    restoreFocus = true;
    input.value = '';
    dialog.showModal(); refresh(true); input.focus();
  }
  async function execute(index: number) {
    const row = rows[index];
    if (!row || row.disabled) return;
    restoreFocus = false; dialog.close();
    try { await row.run(); }
    catch (reason) { onError(reason instanceof Error ? reason.message : String(reason)); }
  }
  input.addEventListener('input', () => refresh(true));
  input.addEventListener('keydown', event => {
    if (event.isComposing) return;
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); dialog.close(); return; }
    if (event.key === 'Enter') { event.preventDefault(); void execute(active); }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const enabled = rows.map((row, i) => row.disabled ? -1 : i).filter(i => i >= 0);
      if (!enabled.length) return;
      const current = enabled.indexOf(active);
      select(enabled[(current + (event.key === 'ArrowDown' ? 1 : enabled.length - 1)) % enabled.length]);
      results.querySelector('[aria-selected=true]')?.scrollIntoView({ block: 'nearest' });
    }
  });
  results.addEventListener('pointermove', event => {
    const row = (event.target as HTMLElement).closest<HTMLElement>('[data-navigation-index]');
    if (row && !rows[Number(row.dataset.navigationIndex)]?.disabled) select(Number(row.dataset.navigationIndex));
  });
  results.addEventListener('click', event => {
    const row = (event.target as HTMLElement).closest<HTMLElement>('[data-navigation-index]');
    if (row) void execute(Number(row.dataset.navigationIndex));
  });
  dialog.addEventListener('close', () => { if (restoreFocus && previousFocus?.isConnected && !previousFocus.closest('[hidden]')) previousFocus.focus(); });
  dialog.addEventListener('click', event => {
    const rect = dialog.getBoundingClientRect();
    if (event.target === dialog && (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom)) dialog.close();
  });
  document.querySelector('#navigation-close')!.addEventListener('click', () => dialog.close());
  document.querySelector('#navigation-open')!.addEventListener('click', open);
  document.addEventListener('keydown', event => {
    if ((event.metaKey || event.ctrlKey) && !event.altKey && !event.isComposing && event.key.toLowerCase() === 'k') { event.preventDefault(); open(); }
  });
  return { refresh, isOpen: () => dialog.open };
}
