import type { CommandEntry } from './conversation-replies.ts';

/** Shared Desktop/browser command preparation. Completion is read-only: only an
 * explicit submission may start work. Local command catalogs are caller-owned. */
export function composerCommand(
  text: string,
): { name: string; argumentsText: string } | undefined {
  if (text.trim() === '/') return { name: '/', argumentsText: '' };
  const match = /^\/(\S+)(?:\s+([\s\S]*))?$/.exec(text.trim());
  return match
    ? { name: `/${match[1]}`, argumentsText: match[2] ?? '' }
    : undefined;
}

export function commandOffers(
  text: string,
  commands: readonly CommandEntry[],
  native: readonly (readonly [string, string])[] = [],
) {
  if (!/^\/[^\s]*$/.test(text)) return [];
  const query = text.toLowerCase();
  return [
    ...native.map(([command, description]) => ({
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
