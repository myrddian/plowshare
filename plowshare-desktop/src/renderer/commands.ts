import { errorMessage } from 'plowshare-client-ts/binding/values';
import { ownedEvent } from './events.ts';
import type { CommandEntry } from 'plowshare-client-ts/operations/conversation-replies';

export const desktopCommands = [
  ['/help', 'Show available commands'],
  ['/commands', 'Show available commands'],
  ['/skills', 'Show granted skills'],
  ['/orchestrations', 'Show granted workflows'],
  ['/new', 'Start a conversation'],
  ['/bots', 'Inspect the selected bot'],
  ['/projects', 'Browse projects'],
  ['/conversations', 'Find a conversation'],
  ['/project', 'Switch project: /project <name>'],
  ['/context', 'Inspect conversation context'],
  ['/earlier', 'Load earlier messages'],
  ['/log', 'Open conversation trajectory'],
  ['/trajectory', 'Open conversation trajectory'],
  ['/inbox', 'Open mailbox'],
  ['/runs', 'Open runs'],
  ['/schedule', 'Open scheduled work'],
  ['/memory', 'Open memories'],
  ['/board', 'Open board'],
  ['/swarm', 'Open swarm'],
  ['/usage', 'Open usage'],
  ['/approvals', 'Review permissions'],
  ['/refresh', 'Refresh this workspace'],
  ['/cancel', 'Stop the current chat job'],
] as const;

export function composerCommand(
  text: string,
): { name: string; argumentsText: string } | undefined {
  if (text.trim() === '/') return { name: '/', argumentsText: '' };
  const match = /^\/(\S+)(?:\s+([\s\S]*))?$/.exec(text.trim());
  return match
    ? { name: `/${match[1]}`, argumentsText: match[2] ?? '' }
    : undefined;
}

export function commandOffers(text: string, commands: readonly CommandEntry[]) {
  if (!/^\/[^\s]*$/.test(text)) return [];
  const query = text.toLowerCase();
  return [
    ...desktopCommands.map(([command, description]) => ({
      command,
      description,
      argumentHint: '',
    })),
    ...commands.map((command) => ({
      command: command.command,
      description: command.description,
      argumentHint: command.argumentHint,
    })),
  ].filter((command) => command.command.toLowerCase().startsWith(query));
}

/** Completion prepares a draft; Enter/Tab never launches the highlighted command. */
export function installCommandPicker(
  root: HTMLElement,
  draft: HTMLTextAreaElement,
  choose: (command: string) => boolean | void,
) {
  let commands: readonly CommandEntry[] = [];
  let offers: ReturnType<typeof commandOffers> = [];
  let active = 0;
  let dismissed = '';
  let stamp = '';
  function refresh() {
    offers =
      dismissed === draft.value ? [] : commandOffers(draft.value, commands);
    active = Math.min(active, Math.max(0, offers.length - 1));
    root.hidden = !offers.length;
    draft.setAttribute('aria-expanded', String(!!offers.length));
    const html = offers
      .map(
        (offer, index) =>
          `<button type="button" role="option" tabindex="-1" id="slash-option-${index}" data-slash-index="${index}" aria-selected="${index === active}"><code>${esc(offer.command)}</code><span>${esc(offer.description)}${offer.argumentHint ? ` · ${esc(offer.argumentHint)}` : ''}</span></button>`,
      )
      .join('');
    if (html !== stamp) {
      root.innerHTML = html;
      stamp = html;
    }
    if (offers.length)
      draft.setAttribute('aria-activedescendant', `slash-option-${active}`);
    else draft.removeAttribute('aria-activedescendant');
  }
  function pick(index: number) {
    const offer = offers[index];
    if (!offer) return;
    dismissed = '';
    const focusDraft = choose(offer.command);
    refresh();
    if (focusDraft !== false) draft.focus();
  }
  root.addEventListener('mousedown', (event) => event.preventDefault());
  root.addEventListener('click', (event) => {
    const button = (event.target as HTMLElement).closest<HTMLElement>(
      '[data-slash-index]',
    );
    if (button) pick(Number(button.dataset.slashIndex));
  });
  draft.addEventListener('input', () => {
    active = 0;
    if (dismissed !== draft.value) dismissed = '';
    refresh();
  });
  draft.addEventListener('keydown', (event) => {
    if (event.isComposing || !offers.length) return;
    if (
      ['ArrowDown', 'ArrowUp', 'Enter', 'Tab', 'Escape'].includes(event.key) &&
      !event.shiftKey
    ) {
      event.preventDefault();
      event.stopImmediatePropagation();
      if (event.key === 'Escape') {
        dismissed = draft.value;
        refresh();
      } else if (event.key === 'Enter' || event.key === 'Tab') pick(active);
      else {
        active =
          (active + (event.key === 'ArrowDown' ? 1 : offers.length - 1)) %
          offers.length;
        refresh();
        root
          .querySelector('[aria-selected=true]')
          ?.scrollIntoView({ block: 'nearest' });
      }
    }
  });
  return {
    update(next: readonly CommandEntry[]) {
      commands = next;
      refresh();
    },
  };
}

const esc = (text: string) =>
  text.replace(
    /[&<>"']/g,
    (character) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[
        character
      ]!,
  );

export function commandDraft(
  command: CommandEntry,
  mode: string,
  argumentsText: string,
): string {
  const selectedMode = mode || 'DIRECT';
  if (
    command.kind === 'skill' &&
    command.mode === null &&
    !['INHERITED', 'SUMMARISED', 'NEW', 'DIRECT'].includes(selectedMode)
  ) {
    throw new Error('Unknown skill context mode.');
  }
  let existing =
    argumentsText === command.command
      ? ''
      : argumentsText.startsWith(command.command + ' ')
        ? argumentsText.slice(command.command.length + 1)
        : argumentsText;
  if (command.kind === 'skill' && command.mode === null)
    existing = existing.replace(
      /^--mode=(INHERITED|SUMMARISED|NEW|DIRECT)(?:\s|$)/,
      '',
    );
  return `${command.command}${command.kind === 'skill' && command.mode === null ? ` --mode=${selectedMode}` : ''} ${existing}`;
}

export function workflowArguments(command: CommandEntry, text: string): string {
  if (command.kind !== 'orchestration')
    throw new Error('Choose an orchestration command.');
  const request = text.startsWith(command.command + ' ')
    ? text.slice(command.command.length + 1)
    : text;
  if (!request.trim())
    throw new Error('Describe the work in the message box first.');
  return request;
}

/** Display only server-provided metadata; selecting a command prepares an editable draft. */
export function installCommands(
  root: HTMLElement,
  prepare: (command: string) => void,
  argumentsText: () => string,
  start?: (command: CommandEntry, text: string) => Promise<void>,
) {
  let commands: readonly CommandEntry[] = [];
  let stamp = '';
  const launching = new Set<string>();
  root.addEventListener(
    'click',
    ownedEvent(async (event: MouseEvent) => {
      const native = (event.target as HTMLElement).closest<HTMLElement>(
        '[data-native-command]',
      );
      if (native) {
        prepare(`${native.dataset.nativeCommand} `);
        return;
      }
      const button = (event.target as HTMLElement).closest<HTMLButtonElement>(
        '[data-command-index]',
      );
      if (!button) return;
      const command = commands[Number(button.dataset.commandIndex)];
      if (!command) return;
      const card = button.closest<HTMLElement>('.command-card')!;
      const mode = card.querySelector<HTMLSelectElement>('select')?.value ?? '';
      const error = card.querySelector<HTMLElement>('[role="alert"]')!;
      if (button.dataset.commandStart !== undefined && start) {
        if (launching.has(command.command)) return;
        try {
          const text = workflowArguments(command, argumentsText());
          launching.add(command.command);
          button.disabled = true;
          await start(command, text);
          error.hidden = true;
          card.querySelector<HTMLElement>('[role="status"]')!.textContent =
            'Workflow started. Follow its progress and questions in Runs.';
        } catch (reason) {
          error.textContent =
            reason instanceof Error ? reason.message : errorMessage(reason);
          error.hidden = false;
        } finally {
          launching.delete(command.command);
          button.disabled = false;
        }
        return;
      }
      try {
        prepare(commandDraft(command, mode, argumentsText()));
        error.hidden = true;
      } catch (reason) {
        error.textContent =
          reason instanceof Error ? reason.message : errorMessage(reason);
        error.hidden = false;
      }
    }),
  );
  return {
    update(
      next: readonly CommandEntry[],
      enabled: boolean,
      diagnostics: readonly string[] = [],
      catalogAvailable = true,
    ) {
      const nextStamp = JSON.stringify([
        next,
        enabled,
        diagnostics,
        catalogAvailable,
      ]);
      if (nextStamp === stamp) return;
      stamp = nextStamp;
      commands = next;
      const cards = (kind: CommandEntry['kind']) =>
        commands
          .map((command, index) =>
            command.kind !== kind
              ? ''
              : `<div class="command-card"><button type="button" data-command-index="${index}" ${enabled ? '' : 'disabled'} title="Prepare ${esc(command.command)}"><code>${esc(command.command)}</code></button><p>${esc(command.description)}</p><small>${esc(command.mode ?? (command.kind === 'skill' ? 'DIRECT (default)' : 'Orchestration'))} · ${esc(command.executor)}</small>${command.kind === 'skill' && command.mode === null ? `<label>Context <select aria-label="Context for ${esc(command.command)}"><option value="DIRECT">Current agent (default)</option><option value="INHERITED">Inherited log</option><option value="SUMMARISED">Summarised context</option><option value="NEW">New context</option></select></label>` : ''}<small>${esc(command.argumentHint)}</small>${command.kind === 'orchestration' && start ? `<button type="button" data-command-index="${index}" data-command-start ${enabled ? '' : 'disabled'}>Start workflow</button><p role="status"></p>` : ''}<p role="alert" hidden></p></div>`,
          )
          .join('');
      root.innerHTML = `<div class="command-list"><p class="context-note">${commands.length} available · type / in the message box or select a command to prepare a draft.</p>${!catalogAvailable ? '<p class="side-note">Command discovery is unavailable. Refresh or update the server to load skills and orchestrations.</p>' : ''}<h3>Skills</h3>${cards('skill') || '<p class="side-note">No skills available to this bot in this project. Check its skill grants and connected resources.</p>'}<h3>Orchestrations</h3>${cards('orchestration') || '<p class="side-note">No orchestrations available to this bot in this project.</p>'}${diagnostics.length ? `<h3>Unavailable</h3>${diagnostics.map((reason) => `<p class="side-note">${esc(reason)}</p>`).join('')}` : ''}<h3>Workspace commands</h3>${desktopCommands.map(([command, description]) => `<button type="button" data-native-command="${esc(command)}"><code>${esc(command)}</code></button><p class="side-note">${esc(description)}</p>`).join('')}</div>`;
    },
  };
}
