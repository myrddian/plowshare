/**
 * `environment.yml`: whether, and how, `run` may start a command on this machine.
 *
 * <h2>`EnvironmentFile`'s copy, held to its table</h2>
 *
 * <p>`plowshare-protocol`'s `EnvironmentFile` is what the server and the Java client
 * execute; this is the terminal client's copy of it, line for line, and
 * `mirrors-the-server.test.ts` runs the shared case table `environments.json` against
 * it. <b>A fixed subset, not YAML</b>, for the Java class's reason: the file decides
 * whether commands run, and two readings of it is the one disagreement that must not
 * be possible — so anything outside the grammar refuses the whole file, with a
 * sentence naming the line.
 *
 * <p>Durations are milliseconds here where the Java has `Duration`, and the names are
 * the table's: `timeoutMillis`, `outputBytes`.
 *
 * <h2>Pure, and in `binding/` for that reason</h2>
 *
 * <p>It parses text and reads no disk; `view/files/enforcer.ts` reads the file and
 * decides with this.
 */
import { isJavaWhitespace } from './files.ts';

export const OFF = 'off';
export const GATED = 'gated';
/** A call no hook allowed or denied goes to a person. Spec 2026-09-15, asking a person. */
export const ASK = 'ask';
export const OPEN = 'open';

export const LOCAL = 'local';
export const SERVER = 'server';
/** The top-level section the person's caps live in — spec 2026-09-29 §2. */
export const CAPS = 'caps';
/** `caps:`'s settings, in the order the refusal names them. */
export const CAP_KEYS = [
  'steps',
  'budget',
  'auto-continue',
  'time',
  'failed-checks',
  'auto-increase',
] as const;

/** The longest `time` cap, in minutes: a week — `EnvironmentFile.MOST_TIME_MINUTES`. */
export const MOST_TIME_MINUTES = 7 * 24 * 60;

/** The most `failed-checks` may allow — `EnvironmentFile.MOST_FAILED_CHECKS`. */
export const MOST_FAILED_CHECKS = 100;

/**
 * The values a `caps:` setting may take — `EnvironmentFile`'s own bounds. Exported so `/cap` can
 * say a value is out of range before it writes, rather than write a file that then does not parse
 * and blame the file (Task 14's review).
 */
export function capRange(key: (typeof CAP_KEYS)[number]): {
  readonly least: number;
  readonly most: number;
} {
  return {
    least: key === 'auto-continue' || key === 'auto-increase' ? 0 : 1,
    most:
      key === 'auto-increase'
        ? 1
        : key === 'steps'
          ? 10_000
          : key === 'budget'
            ? 1_000_000
            : key === 'time'
              ? MOST_TIME_MINUTES
              : key === 'failed-checks'
                ? MOST_FAILED_CHECKS
                : 100,
  };
}

export const DEFAULT_TIMEOUT_MILLIS = 5 * 60 * 1000;
export const MAX_TIMEOUT_MILLIS = 60 * 60 * 1000;
export const DEFAULT_OUTPUT_BYTES = 1024 * 1024;
export const MAX_OUTPUT_BYTES = 8 * 1024 * 1024;
export const DEFAULT_INHERIT: readonly string[] = ['PATH', 'HOME', 'LANG'];

/** The commands that are a shell, by basename with any `.exe` removed. */
export const SHELLS: ReadonlySet<string> = new Set([
  'bash',
  'sh',
  'zsh',
  'fish',
  'dash',
  'ksh',
  'csh',
  'tcsh',
  'cmd',
  'powershell',
  'pwsh',
]);

const SETTINGS: readonly string[] = [
  'mode',
  'shells',
  'inherit',
  'env',
  'timeout',
  'output',
  'isolation',
];
const NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;
const DURATION = /^([0-9]{1,6})(s|m)$/;
const SIZE = /^([0-9]{1,9})(KiB|MiB)$/;

/** What one side may do, every key resolved — `EnvironmentFile.Side`. */
export interface Side {
  readonly mode: string;
  readonly shells: boolean;
  readonly inherit: readonly string[];
  readonly env: Readonly<Record<string, string>>;
  readonly timeoutMillis: number;
  readonly outputBytes: number;
  readonly isolation: string;
}

/** `Side.DEFAULT`: off, no shells, the three inherited variables, five minutes, 1 MiB. */
export const DEFAULT_SIDE: Side = {
  mode: OFF,
  shells: false,
  inherit: DEFAULT_INHERIT,
  env: {},
  timeoutMillis: DEFAULT_TIMEOUT_MILLIS,
  outputBytes: DEFAULT_OUTPUT_BYTES,
  isolation: 'none',
};

/** One section as written: a key the file did not set is absent — `EnvironmentFile.Section`. */
export interface Section {
  readonly mode?: string;
  readonly shells?: boolean;
  readonly inherit?: readonly string[];
  readonly env?: Readonly<Record<string, string>>;
  readonly timeoutMillis?: number;
  readonly outputBytes?: number;
  readonly isolation?: string;
}

/**
 * `caps:`'s settings as written; a key the file did not set is absent — `EnvironmentFile.Caps`.
 * Spec 2026-09-29 §2: `steps` overrides a definition's `max-turns`, `budget` its
 * `max-model-calls`, and `auto-continue` is how many caps a run may pass without asking; `time`
 * is how many minutes a run goes before it asks, and `failed-checks` how many times its check may
 * fail before it asks.
 */
export interface Caps {
  readonly steps?: number;
  readonly budget?: number;
  readonly autoContinue?: number;
  readonly time?: number;
  readonly failedChecks?: number;
  readonly autoIncrease?: boolean;
}

/** A file, parsed. A section the file does not have is null. */
export interface Parsed {
  readonly local: Section | null;
  readonly server: Section | null;
  readonly caps: Caps | null;
}

/** `Side.with`: this side with every key the section set replaced, and nothing else. */
export function sideWith(
  side: Side,
  section: Section | null | undefined,
): Side {
  if (section === null || section === undefined) {
    return side;
  }
  return {
    mode: section.mode ?? side.mode,
    shells: section.shells ?? side.shells,
    inherit: section.inherit ?? side.inherit,
    env: section.env ?? side.env,
    timeoutMillis: section.timeoutMillis ?? side.timeoutMillis,
    outputBytes: section.outputBytes ?? side.outputBytes,
    isolation: section.isolation ?? side.isolation,
  };
}

/** `Side.off`: this side forced off, which is what a file that could not be read means. */
export function sideOff(side: Side): Side {
  return { ...side, mode: OFF };
}

/** `EnvironmentFile.Unreadable`: a file that is not this grammar, with the sentence saying where. */
export class Unreadable extends Error {
  /** The 1-based line at fault, or 0 for the file as a whole. */
  readonly line: number;

  constructor(line: number, sentence: string) {
    super(line > 0 ? `line ${line}: ${sentence}` : sentence);
    this.name = 'Unreadable';
    this.line = line;
  }
}

/** `EnvironmentFile.isShell`: whether a command's first argument names a shell. */
export function isShell(argv0: string | undefined): boolean {
  if (argv0 === undefined) {
    return false;
  }
  let base = argv0;
  const slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
  if (slash >= 0) {
    base = base.slice(slash + 1);
  }
  base = base.toLowerCase();
  if (base.endsWith('.exe')) {
    base = base.slice(0, -4);
  }
  return SHELLS.has(base);
}

type Value =
  string | boolean | number | readonly string[] | Record<string, string>;

/**
 * `EnvironmentFile.parse`: the file's sections.
 *
 * @throws Unreadable for anything outside the grammar
 */
export function parseEnvironment(source: string): Parsed {
  let text = source;
  if (text.startsWith('﻿')) {
    text = text.slice(1);
  }
  const lines = text.split(/\r?\n/);
  const sections = new Map<string, Map<string, Value>>();
  let current: Map<string, Value> | undefined;
  let currentName: string | undefined;
  let env: Record<string, string> | undefined;
  for (let i = 0; i < lines.length; i += 1) {
    const number = i + 1;
    const raw = lines[i] ?? '';
    if (raw.includes('\t')) {
      throw new Unreadable(number, 'a tab is not allowed; indent with spaces');
    }
    const line = withoutComment(raw, number);
    if (isBlank(line)) {
      continue;
    }
    let indent = 0;
    while (indent < line.length && line[indent] === ' ') {
      indent += 1;
    }
    const body = stripTrailing(line.slice(indent));
    if (indent === 0) {
      if (
        body !== `${LOCAL}:` &&
        body !== `${SERVER}:` &&
        body !== `${CAPS}:`
      ) {
        throw new Unreadable(
          number,
          `expected 'local:', 'server:' or 'caps:', not '${body}'`,
        );
      }
      const name = body.slice(0, -1);
      if (sections.has(name)) {
        throw new Unreadable(number, `'${name}' appears twice`);
      }
      current = new Map();
      currentName = name;
      sections.set(name, current);
      env = undefined;
      continue;
    }
    if (current === undefined) {
      throw new Unreadable(
        number,
        "a setting must be inside 'local:', 'server:' or 'caps:'",
      );
    }
    if (indent === 4 && env !== undefined) {
      const [name, value] = pair(body, number);
      if (!NAME.test(name)) {
        throw new Unreadable(number, `'${name}' is not a variable name`);
      }
      if (Object.hasOwn(env, name)) {
        throw new Unreadable(number, `'${name}' appears twice`);
      }
      env[name] = scalar(value, number);
      continue;
    }
    if (indent !== 2) {
      throw new Unreadable(
        number,
        'a setting is indented by two spaces, and a variable' +
          " under 'env:' by four",
      );
    }
    env = undefined;
    const [key, value] = pair(body, number);
    if (currentName === CAPS) {
      if (
        key !== 'auto-increase' &&
        !(CAP_KEYS as readonly string[]).includes(key)
      ) {
        throw new Unreadable(
          number,
          `'${key}' is not a caps setting; the caps settings are ` +
            CAP_KEYS.join(', '),
        );
      }
      if (current.has(key)) {
        throw new Unreadable(number, `'${key}' appears twice`);
      }
      if (key === 'auto-increase') {
        const flag = scalar(value, number);
        if (flag !== 'true' && flag !== 'false')
          throw new Unreadable(
            number,
            `auto-increase is true or false, not '${flag}'`,
          );
        current.set(key, flag === 'true');
        continue;
      }
      const { least, most } = capRange(key as (typeof CAP_KEYS)[number]);
      current.set(key, whole(scalar(value, number), least, most, key, number));
      continue;
    }
    if (!SETTINGS.includes(key)) {
      throw new Unreadable(
        number,
        `'${key}' is not a setting; the settings are ` + SETTINGS.join(', '),
      );
    }
    if (current.has(key)) {
      throw new Unreadable(number, `'${key}' appears twice`);
    }
    switch (key) {
      case 'mode': {
        const mode = scalar(value, number);
        if (mode !== OFF && mode !== GATED && mode !== ASK && mode !== OPEN) {
          throw new Unreadable(
            number,
            `mode is off, gated, ask or open, not '${mode}'`,
          );
        }
        current.set(key, mode);
        break;
      }
      case 'shells': {
        const flag = scalar(value, number);
        if (flag !== 'true' && flag !== 'false') {
          throw new Unreadable(
            number,
            `shells is true or false, not '${flag}'`,
          );
        }
        current.set(key, flag === 'true');
        break;
      }
      case 'inherit':
        current.set(key, list(value, number));
        break;
      case 'env':
        if (value !== '') {
          throw new Unreadable(
            number,
            'env takes its variables on the lines below it,' +
              ' indented by four spaces',
          );
        }
        env = {};
        current.set(key, env);
        break;
      case 'timeout':
        current.set(key, duration(scalar(value, number), number));
        break;
      case 'output':
        current.set(key, size(scalar(value, number), number));
        break;
      case 'isolation': {
        const isolation = scalar(value, number);
        if (isolation !== 'none') {
          throw new Unreadable(
            number,
            `isolation can only be none for now, not '${isolation}'`,
          );
        }
        current.set(key, isolation);
        break;
      }
    }
  }
  return {
    local: section(sections.get(LOCAL)),
    server: section(sections.get(SERVER)),
    caps: capsOf(sections.get(CAPS)),
  };
}

function section(keys: Map<string, Value> | undefined): Section | null {
  if (keys === undefined) {
    return null;
  }
  const mode = keys.get('mode');
  const shells = keys.get('shells');
  const inherit = keys.get('inherit');
  const env = keys.get('env');
  const timeout = keys.get('timeout');
  const output = keys.get('output');
  const isolation = keys.get('isolation');
  return {
    ...(mode === undefined ? {} : { mode: mode as string }),
    ...(shells === undefined ? {} : { shells: shells as boolean }),
    ...(inherit === undefined ? {} : { inherit: inherit as readonly string[] }),
    ...(env === undefined
      ? {}
      : { env: { ...(env as Record<string, string>) } }),
    ...(timeout === undefined ? {} : { timeoutMillis: timeout as number }),
    ...(output === undefined ? {} : { outputBytes: output as number }),
    ...(isolation === undefined ? {} : { isolation: isolation as string }),
  };
}

function capsOf(keys: Map<string, Value> | undefined): Caps | null {
  if (keys === undefined) {
    return null;
  }
  const steps = keys.get('steps');
  const budget = keys.get('budget');
  const auto = keys.get('auto-continue');
  const time = keys.get('time');
  const failedChecks = keys.get('failed-checks');
  return {
    ...(typeof steps === 'number' ? { steps } : {}),
    ...(typeof budget === 'number' ? { budget } : {}),
    ...(typeof auto === 'number' ? { autoContinue: auto } : {}),
    ...(typeof time === 'number' ? { time } : {}),
    ...(typeof failedChecks === 'number' ? { failedChecks } : {}),
    ...(typeof keys.get('auto-increase') === 'boolean'
      ? { autoIncrease: keys.get('auto-increase') as boolean }
      : {}),
  };
}

/** `String.isBlank()`, by Java's whitespace. */
function isBlank(text: string): boolean {
  for (const character of text) {
    if (!isJavaWhitespace(character.codePointAt(0) ?? 0)) {
      return false;
    }
  }
  return true;
}

/** `String.stripTrailing()`, by Java's whitespace. */
function stripTrailing(text: string): string {
  let end = text.length;
  while (end > 0 && isJavaWhitespace(text.codePointAt(end - 1) ?? 0)) {
    end -= 1;
  }
  return text.slice(0, end);
}

/** `String.strip()`, by Java's whitespace. */
function strip(text: string): string {
  let start = 0;
  while (
    start < text.length &&
    isJavaWhitespace(text.codePointAt(start) ?? 0)
  ) {
    start += 1;
  }
  return stripTrailing(text.slice(start));
}

function withoutComment(raw: string, number: number): string {
  let quoted = false;
  for (let i = 0; i < raw.length; i += 1) {
    const c = raw[i];
    if (c === '"') {
      quoted = !quoted;
    } else if (c === '#' && !quoted && (i === 0 || raw[i - 1] === ' ')) {
      return raw.slice(0, i);
    }
  }
  if (quoted) {
    throw new Unreadable(number, 'a quoted value is not closed');
  }
  return raw;
}

function pair(body: string, number: number): [string, string] {
  const colon = body.indexOf(':');
  if (colon <= 0) {
    throw new Unreadable(number, `expected 'name: value', not '${body}'`);
  }
  const key = body.slice(0, colon);
  const rest = body.slice(colon + 1);
  if (rest !== '' && rest[0] !== ' ') {
    throw new Unreadable(number, `put a space after the colon in '${body}'`);
  }
  return [key, strip(rest)];
}

function scalar(value: string, number: number): string {
  if (value === '') {
    throw new Unreadable(number, 'a value is missing');
  }
  if (value.startsWith('"')) {
    if (value.length < 2 || !value.endsWith('"')) {
      throw new Unreadable(number, 'a quoted value is not closed');
    }
    const inner = value.slice(1, -1);
    if (inner.includes('"') || inner.includes('\\')) {
      throw new Unreadable(
        number,
        'a quoted value may not contain a quote or a backslash',
      );
    }
    return inner;
  }
  if (value.startsWith('[') || value.startsWith('{') || value.startsWith("'")) {
    throw new Unreadable(number, `'${value}' is not a plain value`);
  }
  return value;
}

function list(value: string, number: number): readonly string[] {
  if (!value.startsWith('[') || !value.endsWith(']')) {
    throw new Unreadable(number, 'inherit is a list like [PATH, HOME]');
  }
  const inner = strip(value.slice(1, -1));
  const names: string[] = [];
  if (inner === '') {
    return [];
  }
  for (const part of inner.split(',')) {
    const name = strip(part);
    if (!NAME.test(name)) {
      throw new Unreadable(number, `'${name}' is not a variable name`);
    }
    if (names.includes(name)) {
      throw new Unreadable(number, `'${name}' appears twice`);
    }
    names.push(name);
  }
  return names;
}

function duration(value: string, number: number): number {
  const matched = DURATION.exec(value);
  if (matched === null) {
    throw new Unreadable(
      number,
      'timeout is a number of seconds or minutes, like 90s or' +
        ` 10m, not '${value}'`,
    );
  }
  const amount = Number(matched[1]);
  const millis = amount * (matched[2] === 's' ? 1000 : 60_000);
  if (millis < 1000 || millis > MAX_TIMEOUT_MILLIS) {
    throw new Unreadable(number, `timeout is from 1s to 60m, not '${value}'`);
  }
  return millis;
}

function size(value: string, number: number): number {
  const matched = SIZE.exec(value);
  if (matched === null) {
    throw new Unreadable(
      number,
      'output is a size in KiB or MiB, like 512KiB or 1MiB, not' +
        ` '${value}'`,
    );
  }
  const bytes =
    Number(matched[1]) * (matched[2] === 'KiB' ? 1024 : 1024 * 1024);
  if (bytes < 1024 || bytes > MAX_OUTPUT_BYTES) {
    throw new Unreadable(number, `output is from 1KiB to 8MiB, not '${value}'`);
  }
  return bytes;
}

function whole(
  value: string,
  least: number,
  most: number,
  key: string,
  number: number,
): number {
  if (!/^[0-9]{1,7}$/.test(value)) {
    throw new Unreadable(number, `${key} is a whole number, not '${value}'`);
  }
  const n = Number(value);
  if (n < least || n > most) {
    throw new Unreadable(
      number,
      `${key} is from ${least} to ${most}, not ${value}`,
    );
  }
  return n;
}

/**
 * `source` with its `local:` mode set to `mode`, and every other line as it was — the edit
 * `/always` makes, so the file stays the one place the setting lives.
 *
 * <p><b>The mode line alone.</b> A person's comments, timeouts, variables and `server:`
 * section are theirs, so this changes the one line, adds it under `local:` when the section
 * set none, and adds the section when the file had none. An absent file becomes the two lines
 * it takes.
 *
 * <p><b>A file that does not parse is refused, not rewritten.</b> It already means `off` to
 * every reader, and replacing it would discard whatever the person was in the middle of
 * writing; the {@link Unreadable} names the line, as it does for any reader.
 *
 * @param source the file as it is, or `undefined` for none
 */
export function withLocalMode(
  source: string | undefined,
  mode: string,
): string {
  if (source === undefined) {
    return `local:\n  mode: ${mode}\n`;
  }
  parseEnvironment(source);
  const lines = source.split('\n');
  const start = lines.findIndex((line) => /^local:\s*(#.*)?$/.test(line));
  let written: string;
  if (start < 0) {
    const body =
      source === '' || source.endsWith('\n') ? source : `${source}\n`;
    written = `${body}local:\n  mode: ${mode}\n`;
  } else {
    // The section runs until the next top-level key, which starts at column zero.
    let end = start + 1;
    while (end < lines.length && !/^[A-Za-z]/.test(lines[end] as string)) {
      end++;
    }
    const at = lines
      .slice(start + 1, end)
      .findIndex((line) => /^ {2}mode:/.test(line));
    if (at >= 0) {
      lines[start + 1 + at] = `  mode: ${mode}`;
    } else {
      lines.splice(start + 1, 0, `  mode: ${mode}`);
    }
    written = lines.join('\n');
  }
  parseEnvironment(written);
  return written;
}

/**
 * `source` with `caps:`'s `key` set to `value`, and every other line as it was — the edit
 * `/cap` and `/always caps` make. A file that does not parse is refused, not rewritten.
 */
export function withCaps(
  source: string | undefined,
  key: (typeof CAP_KEYS)[number],
  value: number,
): string {
  if (key === 'auto-increase' && value !== 0 && value !== 1)
    throw new Error('auto-increase must be enabled or disabled');
  const line = `  ${key}: ${key === 'auto-increase' ? value === 1 : value}`;
  if (source === undefined) {
    const written = `caps:\n${line}\n`;
    parseEnvironment(written);
    return written;
  }
  parseEnvironment(source);
  const lines = source.split('\n');
  const start = lines.findIndex((each) => /^caps:\s*(#.*)?$/.test(each));
  let written: string;
  if (start < 0) {
    const body =
      source === '' || source.endsWith('\n') ? source : `${source}\n`;
    written = `${body}caps:\n${line}\n`;
  } else {
    let end = start + 1;
    while (end < lines.length && !/^[A-Za-z]/.test(lines[end] as string)) {
      end++;
    }
    const at = lines
      .slice(start + 1, end)
      .findIndex((each) => each.startsWith(`  ${key}:`));
    if (at >= 0) {
      lines[start + 1 + at] = line;
    } else {
      lines.splice(start + 1, 0, line);
    }
    written = lines.join('\n');
  }
  parseEnvironment(written);
  return written;
}
