import { displayText } from './values.ts';
import { isList } from './values.ts';
import { CAP_KEYS, capRange, parseEnvironment } from './environment.ts';
import type { Caps, Section } from './environment.ts';

export const MANIFEST_CAP_KEYS = {
  steps: 'steps',
  budget: 'budget',
  'auto-continue': 'autoContinue',
  time: 'time',
  'failed-checks': 'failedChecks',
  'auto-increase': 'autoIncrease',
} as const;
export interface ProjectCommandPolicy {
  readonly mode?: string;
  readonly shells?: boolean;
  readonly inherit?: readonly string[];
  readonly env?: Readonly<Record<string, string>>;
  readonly timeout?: string;
  readonly output?: string;
  readonly isolation?: string;
}
export interface ProjectManifestSettings {
  readonly access?: ApplicationAccess;
  readonly caps?: Caps;
  readonly commands?: {
    readonly local?: ProjectCommandPolicy;
    readonly server?: ProjectCommandPolicy;
  };
  readonly skills?: Readonly<
    Record<string, { readonly agentVisible: boolean }>
  >;
  readonly defaultBot?: string;
}

/** Deployed account ceilings, intersected with authenticated server membership. */
export interface ApplicationAccess {
  readonly accounts: readonly {
    readonly handle: string;
    readonly role: 'VIEWER' | 'CONTRIBUTOR' | 'MANAGER';
  }[];
}

export function applicationAccess(value: unknown): ApplicationAccess {
  const access = object(value, 'Application access');
  if (
    Object.keys(access).some((key) => key !== 'accounts') ||
    !isList(access.accounts) ||
    access.accounts.length > 256
  )
    throw new Error('Application access needs a bounded accounts list');
  const handles = new Set<string>();
  const accounts = access.accounts.map(
    (entry): ApplicationAccess['accounts'][number] => {
      const grant = object(entry, 'Application account grant');
      const role = grant.role;
      if (
        Object.keys(grant).some((key) => !['handle', 'role'].includes(key)) ||
        typeof grant.handle !== 'string' ||
        !grant.handle.trim() ||
        grant.handle !== grant.handle.trim() ||
        grant.handle.length > 512 ||
        Array.from(grant.handle).some((char) => {
          const code = char.charCodeAt(0);
          return code < 32 || (code >= 127 && code <= 159);
        }) ||
        (role !== 'VIEWER' && role !== 'CONTRIBUTOR' && role !== 'MANAGER') ||
        handles.has(grant.handle)
      )
        throw new Error('Invalid or duplicate application account grant');
      handles.add(grant.handle);
      return {
        handle: grant.handle,
        role,
      };
    },
  );
  return { accounts };
}
export interface ProjectSettings extends Omit<
  ProjectManifestSettings,
  'commands'
> {
  readonly commands?: { readonly local?: Section; readonly server?: Section };
}
function object(value: unknown, name: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || isList(value))
    throw new Error(`${name} must be an object`);
  return value as Record<string, unknown>;
}
function commandSection(value: unknown): Section {
  const fields = object(value, 'Command policy');
  let yaml = 'local:\n';
  let env: Record<string, string> | undefined;
  for (const [key, entry] of Object.entries(fields)) {
    if (
      ![
        'mode',
        'shells',
        'inherit',
        'env',
        'timeout',
        'output',
        'isolation',
      ].includes(key)
    )
      throw new Error(`Unknown command setting: ${key}`);
    if (key === 'env') {
      env = {};
      for (const [name, text] of Object.entries(object(entry, 'Command env'))) {
        if (
          !/^[A-Za-z_][A-Za-z0-9_]*$/.test(name) ||
          typeof text !== 'string' ||
          /[\r\n\0]/u.test(text)
        )
          throw new Error('Invalid command environment variable');
        Object.defineProperty(env, name, { value: text, enumerable: true });
      }
      continue;
    }
    if (
      key === 'shells'
        ? typeof entry !== 'boolean'
        : key === 'inherit'
          ? !isList(entry) ||
            !entry.every(
              (name) =>
                typeof name === 'string' &&
                /^[A-Za-z_][A-Za-z0-9_]*$/.test(name),
            )
          : typeof entry !== 'string' || /[\r\n\0#]/u.test(entry)
    )
      throw new Error(`Invalid command setting: ${key}`);
    yaml += `  ${key}: ${displayText(key === 'inherit' ? `[${(entry as string[]).join(', ')}]` : entry)}\n`;
  }
  return {
    ...parseEnvironment(yaml).local,
    ...(env === undefined ? {} : { env }),
  };
}
/** Validate recognized settings while preserving other integration/application properties. */
export function projectSettings(value: unknown): ProjectSettings {
  const record = object(value, 'Project settings');
  const access =
    record.access === undefined ? undefined : applicationAccess(record.access);
  let caps: Caps | undefined;
  if (record.caps !== undefined) {
    const values = object(record.caps, 'Project caps');
    for (const [key, value] of Object.entries(values)) {
      const cap = CAP_KEYS.find(
        (keyInYaml) => MANIFEST_CAP_KEYS[keyInYaml] === key,
      );
      if (!cap) throw new Error(`Unknown project cap: ${key}`);
      const { least, most } = capRange(cap);
      if (
        cap === 'auto-increase'
          ? typeof value !== 'boolean'
          : !Number.isSafeInteger(value) ||
            (value as number) < least ||
            (value as number) > most
      )
        throw new Error(`Invalid project cap: ${key}`);
    }
    caps = values;
  }
  let commands: ProjectSettings['commands'];
  if (record.commands !== undefined) {
    const values = object(record.commands, 'Project commands');
    if (Object.keys(values).some((key) => !['local', 'server'].includes(key)))
      throw new Error('Unknown project command side');
    commands = {
      ...(values.local === undefined
        ? {}
        : { local: commandSection(values.local) }),
      ...(values.server === undefined
        ? {}
        : { server: commandSection(values.server) }),
    };
  }
  if (record.skills !== undefined) {
    const skills = object(record.skills, 'Project skills');
    if (Object.keys(skills).length > 512)
      throw new Error('Too many skill visibility overrides');
    for (const [name, value] of Object.entries(skills)) {
      const setting = object(value, 'Skill visibility');
      if (
        !/^[\p{L}\p{Nd}]+(?:-[\p{L}\p{Nd}]+)*$/u.test(name) ||
        name !== name.toLowerCase() ||
        Object.keys(setting).length !== 1 ||
        typeof setting.agentVisible !== 'boolean'
      )
        throw new Error(`Invalid skill visibility: ${name}`);
    }
  }
  if (
    record.defaultBot !== undefined &&
    (typeof record.defaultBot !== 'string' ||
      !/^[\p{L}\p{Nd}]+(?:-[\p{L}\p{Nd}]+)*$/u.test(record.defaultBot))
  )
    throw new Error('Invalid defaultBot');
  return {
    ...(access === undefined ? {} : { access }),
    ...(caps === undefined ? {} : { caps }),
    ...(commands === undefined ? {} : { commands }),
    ...(record.skills === undefined
      ? {}
      : { skills: record.skills as NonNullable<ProjectSettings['skills']> }),
    ...(record.defaultBot === undefined
      ? {}
      : { defaultBot: record.defaultBot }),
  };
}
export function withProjectCap(
  source: string,
  key: (typeof CAP_KEYS)[number],
  value: number,
): string {
  const record = object(JSON.parse(source), 'Project manifest');
  projectSettings(record);
  const { least, most } = capRange(key);
  if (!Number.isSafeInteger(value) || value < least || value > most)
    throw new Error(`Invalid cap value: ${key}`);
  record.caps = {
    ...(record.caps ?? {}),
    [MANIFEST_CAP_KEYS[key]]: key === 'auto-increase' ? value === 1 : value,
  };
  projectSettings(record);
  return JSON.stringify(record, null, 2) + '\n';
}

export function withProjectLocalMode(source: string, mode: string): string {
  const record = object(JSON.parse(source), 'Project manifest');
  projectSettings(record);
  const commands = record.commands as Record<string, unknown> | undefined;
  record.commands = {
    ...commands,
    local: {
      ...(commands?.local as Record<string, unknown> | undefined),
      mode,
    },
  };
  projectSettings(record);
  return JSON.stringify(record, null, 2) + '\n';
}
