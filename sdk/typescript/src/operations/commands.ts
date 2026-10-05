import { SHAPES, commandProblem, SCOPED } from './payload-validation.ts';
export { SHAPES, commandProblem } from './payload-validation.ts';
import { decodeRequest } from './schema.ts';
import { isList } from '../binding/values.ts';
import { BOUND_OPERATIONS } from './administration.ts';
import { CLI_OPERATIONS } from './catalog.ts';
import { parseDirect } from './direct.ts';
import type { Parsed, Request } from './direct.ts';

const COMMAND_HEADS = Object.keys(CLI_OPERATIONS)
  .sort((left, right) => right.length - left.length)
  .map((name) => ({
    name,
    pattern: new RegExp(
      '^' + name.split(' ').join('\\s+') + '(?:\\s+(.*))?$',
      's',
    ),
  }));

/** Shared headless command grammar. TUI parseDirect retains its own command boundaries. */
export function parseCommand(line: string, project?: string): Parsed {
  const trimmed = line.trim();
  const recognized = COMMAND_HEADS.map((head) => ({
    name: head.name,
    match: head.pattern.exec(trimmed),
  })).find((head) => head.match !== null);
  const fallback = /^(\S+)\s+(\S+)/.exec(trimmed);
  const name =
    recognized?.name ?? (fallback ? `${fallback[1]} ${fallback[2]}` : '');
  if (!Object.hasOwn(CLI_OPERATIONS, name)) {
    const bound = name.replace(' ', '.').replace('conflict-', 'conflict.');
    if (Object.hasOwn(BOUND_OPERATIONS, bound))
      return {
        kind: 'usage',
        said: `${bound} requires ${BOUND_OPERATIONS[bound as keyof typeof BOUND_OPERATIONS]}; use a persistent platform client`,
      };
    return parseDirect(line, project);
  }
  const type = CLI_OPERATIONS[name as keyof typeof CLI_OPERATIONS];
  const argument = (recognized?.match?.[1] ?? '').trim();
  let body: Record<string, unknown>;
  if (argument.startsWith('{')) {
    try {
      const value: unknown = JSON.parse(argument);
      if (typeof value !== 'object' || value === null || isList(value))
        throw new Error();
      body = value as Record<string, unknown>;
    } catch {
      return { kind: 'usage', said: 'payload must be a valid JSON object' };
    }
  } else if (argument === '') body = {};
  else {
    const [required, optional] = SHAPES[type];
    // Single-identifier reads and query retrieval accept plain text.
    const key =
      required.length === 1 &&
      !type.startsWith('project.') &&
      type !== 'agent.define'
        ? required[0]
        : undefined;
    if (key === undefined || optional.includes('roots'))
      return { kind: 'usage', said: `${name} takes a JSON payload` };
    body = { [key]: argument };
  }
  if (
    !('project' in body) &&
    project !== undefined &&
    (SCOPED.includes(type) ||
      type === 'outgoing.send' ||
      type === 'outgoing.peers' ||
      ((type === 'agent.run' || type === 'trigger.define') &&
        body['conversation'] == null) ||
      (type === 'approval.list' &&
        body['conversation'] == null &&
        body['mine'] !== true))
  )
    body = { ...body, project };
  // Schedule file saves preserve existing files unless replacement is explicit.
  // Materialize the server's omitted-boolean default in the required SDK DTO;
  // explicit null or nonboolean values still fail validation.
  if (type === 'schedule.save' && !('overwrite' in body))
    body = { ...body, overwrite: false };
  const said = commandProblem(type, body);
  if (said !== undefined) return { kind: 'usage', said: said };
  try {
    return { kind: 'request', request: decodeRequest(type, body) };
  } catch (failure) {
    return {
      kind: 'usage',
      said:
        failure instanceof Error ? failure.message : 'invalid operation input',
    };
  }
}

/** Used by adapters that read a complete JSON payload from stdin. */
export function isPayloadCommand(command: string): boolean {
  return (
    Object.hasOwn(CLI_OPERATIONS, command) ||
    /^(?:memory\s+\w+|search|job\s+(?:status|cancel))$/.test(command)
  );
}

/** Match the event socket identity without replacing an explicitly supplied session. */
export function withSession(asked: Request, session: string): Request {
  if (asked.type === 'agent.run' && !('session' in asked.payload))
    return { ...asked, payload: { ...asked.payload, session } };
  if (asked.type === 'conversation.resume' && !('session' in asked.payload))
    return { ...asked, payload: { ...asked.payload, session } };
  return asked;
}

/** Decimal text preserves exact operator rates; nested shapes are checked before sending. */
