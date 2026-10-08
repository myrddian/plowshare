import {
  commandDraft,
  commandOffers,
} from '../../../sdk/typescript/src/operations/command-model.ts';
import type { CommandEntry } from '../../../sdk/typescript/src/operations/conversation-replies.ts';

/** Browser presentation over the same command grammar as Desktop. Preparation
 * never submits work; skills default to DIRECT and allow explicit context overrides. */
export function mountCommands(root: HTMLElement, draft: HTMLTextAreaElement) {
  let commands: readonly CommandEntry[] = [];
  let enabled = false;
  let offers: ReturnType<typeof commandOffers> = [];
  let active = 0;
  let dismissed = '';
  const doc = root.ownerDocument;
  const catalog = doc.createElement('details');
  catalog.className = 'command-catalog';
  const summary = doc.createElement('summary');
  summary.textContent = 'Commands';
  const note = doc.createElement('p');
  note.className = 'note';
  const search = doc.createElement('input');
  search.type = 'search';
  search.placeholder = 'Find a skill or workflow';
  search.setAttribute('aria-label', 'Find an available command');
  const cards = doc.createElement('div');
  cards.className = 'command-cards';
  catalog.append(summary, note, search, cards);
  const popup = doc.createElement('div');
  popup.className = 'command-completions';
  popup.id = 'console-command-completions';
  popup.setAttribute('role', 'listbox');
  popup.setAttribute('aria-label', 'Available commands');
  popup.hidden = true;
  draft.setAttribute('role', 'combobox');
  draft.setAttribute('aria-autocomplete', 'list');
  draft.setAttribute('aria-controls', popup.id);
  root.append(catalog, popup);

  function prepare(command: CommandEntry, mode = ''): void {
    offers = [];
    popup.replaceChildren();
    popup.hidden = true;
    draft.setAttribute('aria-expanded', 'false');
    draft.removeAttribute('aria-activedescendant');
    try {
      const argumentsText = /^\/[^\s]*$/.test(draft.value) ? '' : draft.value;
      draft.value = commandDraft(command, mode, argumentsText);
      popup.hidden = true;
      draft.setAttribute('aria-expanded', 'false');
      draft.removeAttribute('aria-activedescendant');
      draft.dispatchEvent(new Event('input'));
      note.textContent = 'Draft prepared. Review it and send when ready.';
      draft.focus();
    } catch (reason) {
      note.textContent =
        reason instanceof Error
          ? reason.message
          : 'Choose the command context.';
      catalog.open = true;
      [...cards.querySelectorAll<HTMLSelectElement>('select[data-command]')]
        .find((select) => select.dataset['command'] === command.command)
        ?.focus();
    }
  }
  function drawCatalog(): void {
    const query = search.value.trim().toLowerCase();
    const matched = commands.filter((c) =>
      (c.command + ' ' + c.description).toLowerCase().includes(query),
    );
    const shown = matched.slice(0, 40);
    cards.replaceChildren();
    for (const command of shown) {
      const card = doc.createElement('article');
      card.className = 'command-card';
      const label = doc.createElement('strong');
      label.textContent = command.command;
      const description = doc.createElement('p');
      description.textContent = command.description;
      const hint = doc.createElement('p');
      hint.className = 'note';
      hint.textContent =
        command.kind +
        ' · ' +
        (command.kind === 'orchestration'
          ? 'Workflow'
          : (command.mode ?? 'DIRECT (default)')) +
        ' · ' +
        command.argumentHint;
      const mode = doc.createElement('select');
      mode.dataset['command'] = command.command;
      mode.setAttribute('aria-label', 'Context for ' + command.command);
      for (const value of ['DIRECT', 'INHERITED', 'SUMMARISED', 'NEW']) {
        const option = doc.createElement('option');
        option.value = value;
        option.textContent =
          value === 'DIRECT' ? 'Current agent (default)' : value;
        mode.append(option);
      }
      const choose = doc.createElement('button');
      choose.type = 'button';
      choose.textContent = 'Prepare draft';
      choose.disabled = !enabled;
      choose.addEventListener('click', () => prepare(command, mode.value));
      card.append(label, description, hint);
      if (command.kind === 'skill' && command.mode === null) card.append(mode);
      card.append(choose);
      cards.append(card);
    }
    if (matched.length > shown.length) {
      const more = doc.createElement('p');
      more.textContent =
        'Showing 40 commands. Refine the search to find others.';
      cards.append(more);
    }
    if (!matched.length && commands.length) {
      const empty = doc.createElement('p');
      empty.textContent = 'No available command matches this search.';
      cards.append(empty);
    }
  }
  function complete(): void {
    offers =
      enabled && dismissed !== draft.value
        ? commandOffers(draft.value, commands).slice(0, 20)
        : [];
    active = Math.min(active, Math.max(0, offers.length - 1));
    popup.replaceChildren();
    popup.hidden = offers.length === 0;
    draft.setAttribute('aria-expanded', String(offers.length > 0));
    draft.removeAttribute('aria-activedescendant');
    offers.forEach((offer, index) => {
      const button = doc.createElement('button');
      button.type = 'button';
      button.setAttribute('role', 'option');
      button.setAttribute('aria-selected', String(index === active));
      button.id = 'console-command-option-' + index;
      button.textContent =
        offer.command +
        ' — ' +
        offer.description +
        (offer.argumentHint ? ' · ' + offer.argumentHint : '');
      button.addEventListener('click', () => pick(index));
      popup.append(button);
      if (index === active)
        draft.setAttribute('aria-activedescendant', button.id);
    });
  }
  function pick(index: number): void {
    const offered = offers[index];
    const command = commands.find((c) => c.command === offered?.command);
    if (command) prepare(command);
  }
  draft.addEventListener('input', () => {
    active = 0;
    complete();
  });
  draft.addEventListener('keydown', (event) => {
    if (event.isComposing || event.shiftKey || !offers.length) return;
    if (!['ArrowDown', 'ArrowUp', 'Enter', 'Tab', 'Escape'].includes(event.key))
      return;
    event.preventDefault();
    event.stopImmediatePropagation();
    if (event.key === 'Escape') dismissed = draft.value;
    else if (event.key === 'Enter' || event.key === 'Tab') {
      pick(active);
      return;
    } else
      active =
        (active + (event.key === 'ArrowDown' ? 1 : offers.length - 1)) %
        offers.length;
    complete();
  });
  search.addEventListener('input', drawCatalog);
  return {
    update(
      next: readonly CommandEntry[] | undefined,
      canPrepare: boolean,
      diagnostics: readonly string[] = [],
    ) {
      commands = next ?? [];
      enabled = canPrepare;
      note.textContent =
        next === undefined
          ? 'Command discovery is unavailable from this server. Refresh the chat to check again.'
          : commands.length
            ? commands.length +
              ' available. Select a command or type / to prepare an editable draft. ' +
              diagnostics.join(' ')
            : 'No commands are available to this agent in this project. ' +
              diagnostics.join(' ');
      drawCatalog();
      complete();
    },
    setEnabled(value: boolean) {
      enabled = value;
      drawCatalog();
      complete();
    },
  };
}
