import { isList } from './values.ts';
/**
 * The file channel's wire, read and answered from the client's side of it.
 *
 * <h2>A mirror, and `mirrors-the-server.test.ts` holds it to that</h2>
 *
 * <p>Every constant and every bound here is `plowshare-protocol`'s — `FileRequest`,
 * `FileReply`, `FileResult`, `Excerpt`, `Span`, `Window`, `Needle`, `Found`, `GlobSpellings`,
 * `Replacement` — and the
 * behaviour of `cut`, `find` and `replaced` is `Window.cut`, `Needle.find` and
 * `Replacement.apply` line for line.
 * The server trusts nothing a client sends about a window it did not ask for
 * (`ChannelDefinitions.read` checks every page), so a divergence here is a file
 * the server refuses to assemble, not a file silently read wrong.
 *
 * <h2>Pure, and in `binding/` for that reason</h2>
 *
 * <p>No filesystem, no `TextEncoder`: this directory's `types: []` forbids both, and
 * nothing here needs them. `view/files/enforcer.ts` owns the disk and calls these.
 */

export const FILES_PATH = '/v1/files';
export const SESSION_PARAM = 'session';
export const PROJECT_PARAM = 'project';
export const MACHINE_PARAM = 'machine';
export const ROOT_PARAM = 'root';

export const ROOTS = 'roots';
export const READ = 'read';
export const SOURCE = 'source';
export const MAX_SOURCE_BYTES = 8 * 1024 * 1024;
export const SOURCE_CHUNK_BYTES = 64 * 1024;
export const STAT = 'stat';
export const GLOB = 'glob';
export const WRITE = 'write';
export const GREP = 'grep';
export const EDIT = 'edit';
export const DELETE = 'delete';
export const MOVE = 'move';
export const RUN = 'run';

/** Requires the local bubblewrap backend; older clients refuse this distinct operation. */
export const RUN_ISOLATED = 'run_isolated';
export const CANCEL = 'cancel';
export const OPS: readonly string[] = [
  ROOTS,
  READ,
  STAT,
  GLOB,
  WRITE,
  GREP,
  EDIT,
  DELETE,
  MOVE,
  RUN,
  CANCEL,
  SOURCE,
];

export const REPLY_OK = 'ok';
export const REPLY_REFUSED = 'refused';
export const REPLY_UNAVAILABLE = 'unavailable';

/** The harness reading definitions — `FileRequest.DEFINITIONS`. Never a model's request. */
export const DEFINITIONS = 'definitions';

/** The harness snapshotting a session's local hooks — `FileRequest.HOOKS`. Never a model's request. */
export const HOOKS = 'hooks';

export const MAX_WINDOW_LINES = 2_000;
export const MAX_WINDOW_BYTES = 96 * 1024;
export const MAX_MATCHES = 50;
export const MAX_LINE_CHARS = 200;
export const MAX_RECURSIVE_WILDCARDS = 4;

export type Stopped = 'lines' | 'bytes' | 'end';

export interface FileRequest {
  readonly id: string;
  readonly op: string;
  readonly path?: string;
  readonly pattern?: string;
  readonly content?: string;
  readonly offset?: number;
  readonly limit?: number;
  readonly needle?: string;
  readonly ignoreCase?: boolean;
  /** {@link DEFINITIONS} or {@link HOOKS} when the server's harness is asking; absent for a model's tools. */
  readonly purpose?: string;
  /** The text an {@link EDIT} replaces; `content` is what replaces it, and may be empty. */
  readonly replacing?: string;
  /** The destination of a {@link MOVE}; `path` is the source. */
  readonly to?: string;
  /** For a {@link WRITE}: true refuses when the file is already there. Absent reads as false. */
  readonly createOnly?: boolean;
  /** A {@link RUN}'s program and its arguments, started with no shell; `path` is its working directory. */
  readonly argv?: readonly string[];
  /** The variables a {@link RUN} sets explicitly, as the server resolved them. */
  readonly env?: Readonly<Record<string, string>>;
  /** The host variables a {@link RUN} passes through, as the server resolved them. */
  readonly inherit?: readonly string[];
  readonly timeoutMillis?: number;
  /** How much of the end of each of stdout and stderr a {@link RUN} keeps. */
  readonly outputBytes?: number;
  /** What the server resolved; this client decides from its own file, whatever this says. */
  readonly shells?: boolean;
  /** What a {@link RUN} writes to the command's input; absent closes it. */
  readonly stdin?: string;
}

export interface Span {
  readonly lines: readonly string[];
  readonly offset: number;
  readonly totalLines: number;
  readonly more: boolean;
  readonly stoppedBy: Stopped;
}

export interface Match {
  readonly path: string;
  readonly offset: number;
  readonly line: string;
  readonly truncated: boolean;
}

export interface Found {
  readonly matches: readonly Match[];
  readonly stoppedBy: 'matches' | 'end';
}

export interface FileReply {
  readonly id: string;
  readonly outcome: string;
  readonly sentence?: string;
  readonly paths?: readonly string[];
  readonly span?: Span;
  readonly found?: Found;
  /** A {@link RUN}'s exit status; null when it was killed — timed out or cancelled. */
  readonly exitCode?: number | null;
  readonly timedOut?: boolean;
  readonly stdout?: string;
  /** Bytes dropped from the front of stdout to keep its tail. */
  readonly stdoutCut?: number;
  readonly stderr?: string;
  readonly stderrCut?: number;
  readonly millis?: number;
  /**
   * What a {@link WRITE}, {@link EDIT}, {@link DELETE} or {@link MOVE} did, or why
   * any op was not done, or a picture a read named — facts, never a sentence: the
   * server words them.
   */
  readonly result?: FileResult;
  readonly source?: {
    readonly size: number;
    readonly sha256?: string;
    readonly offset?: number;
    readonly data?: string;
  };
}

/** `FileResult.VERSION`. */
export const RESULT_VERSION = 1;

// `FileResult`'s kinds.
export const EDITED = 'edited';
export const WRITTEN = 'written';
export const DELETED = 'deleted';
export const MOVED = 'moved';
export const NO_MATCH = 'no-match';
export const MANY_MATCHES = 'many-matches';
export const NO_FILE = 'no-file';
export const NOT_TEXT = 'not-text';
export const REFUSED = 'refused';
export const NAMED = 'named';
export const UNAVAILABLE = 'unavailable';

// `FileResult`'s reasons.
export const NO_WORKSPACE = 'no-workspace';
export const NO_PATH = 'no-path';
export const UNNAMEABLE = 'unnameable';
export const WORKSPACE_MOVED = 'workspace-moved';
export const OUTSIDE = 'outside';
export const DIRECTORY = 'directory';
export const LINK = 'link';
export const NOT_REGULAR = 'not-regular';
export const TOO_LARGE = 'too-large';
export const EXISTS = 'exists';
export const DESTINATION_EXISTS = 'destination-exists';
export const FAILED = 'failed';
export const MISSING = 'missing';
export const EMPTY_OLD = 'empty-old';
export const NOT_UTF8 = 'not-utf8';
export const HIDDEN = 'hidden';
export const DENIED = 'denied';
export const LINE_TOO_WIDE = 'line-too-wide';
export const UNSERVABLE = 'unservable';
export const NO_PATTERN = 'no-pattern';
export const ABSOLUTE_PATTERN = 'absolute-pattern';
export const BAD_PATTERN = 'bad-pattern';
export const TOO_MANY_WILDCARDS = 'too-many-wildcards';
export const TOO_MANY_MATCHES = 'too-many-matches';
export const UNLISTABLE = 'unlistable';
export const UNKNOWN_OP = 'unknown-op';
export const UNCONVERTED = 'unconverted';
export const ENCRYPTED = 'encrypted';
export const DAMAGED = 'damaged';
export const IMAGE_REFUSED = 'image-refused';
export const IMAGE_UNREACHED = 'image-unreached';
export const ROOT_GONE = 'root-gone';
export const ROOT_NOT_DIRECTORY = 'root-not-directory';
export const INTERNAL = 'internal';

// What a no-match's excerpt is.
export const LOOKALIKE = 'look-alike';
export const WHITESPACE = 'whitespace';
export const CLOSEST = 'closest';
export const EMPTY_FILE = 'empty-file';

/** `Excerpt`: some lines of a file, raw, and which lines they are. */
export interface Excerpt {
  readonly from: number;
  readonly to: number;
  readonly total: number;
  readonly lines: readonly string[];
  /** Where in `lines` the left-out lines belong; absent when none are. */
  readonly gap?: number;
  /** Indexes in `lines` of the lines clipped at `MAX_SHOWN_LINE_CHARS`; absent when none were. */
  readonly clipped?: readonly number[];
}

/** `FileResult.Difference`: a character `old` has, and the file's in its place, as UTF-16 units. */
export interface Difference {
  readonly sent: number;
  readonly there: number;
}

/**
 * `FileResult`: what a change to a file did, or why it was not made. The fields a
 * kind does not use are absent — `FileResult`'s javadoc says which each one uses.
 */
export interface FileResult {
  readonly version: number;
  readonly kind: string;
  readonly op: string;
  readonly path?: string;
  readonly to?: string;
  readonly reason?: string;
  readonly argument?: string;
  readonly roots?: readonly string[];
  readonly detail?: string;
  readonly bytes?: number;
  readonly limit?: number;
  readonly count?: number;
  readonly lines?: number;
  readonly first?: number;
  readonly last?: number;
  readonly removed?: boolean;
  readonly excerpt?: Excerpt;
  readonly near?: string;
  readonly differences?: readonly Difference[];
  readonly foreign?: readonly number[];
  /** What a file is, for a format this side recognises: `PDF`, or a picture's `png`, `jpeg`… */
  readonly format?: string;
  /** The id a picture was named with, for {@link NAMED}. */
  readonly image?: string;
  /** The image store's status, for {@link IMAGE_REFUSED}. */
  readonly status?: number;
  /** A glob, as the request spelled it, for the pattern reasons. */
  readonly pattern?: string;
}

/** Whether a result says the change was made: `FileResult.made()`. */
export function made(result: FileResult): boolean {
  return [EDITED, WRITTEN, DELETED, MOVED].includes(result.kind);
}

/**
 * `FileResult.lineCount`: how many lines `bytes` holds, counted as `String.lines()`
 * counts them, without decoding.
 */
export function lineCount(bytes: Uint8Array): number {
  let lines = 0;
  for (let i = 0; i < bytes.length; i += 1) {
    const b = bytes[i];
    if (
      b === 0x0a ||
      (b === 0x0d && (i + 1 >= bytes.length || bytes[i + 1] !== 0x0a))
    ) {
      lines += 1;
    }
  }
  const end = bytes[bytes.length - 1];
  if (end !== undefined && end !== 0x0a && end !== 0x0d) {
    lines += 1;
  }
  return lines;
}

export interface Needle {
  readonly text: string;
  readonly ignoreCase: boolean;
}

/** A refusal's facts before the enforcer stamps which op and version they answer. */
export type Refusal = Omit<FileResult, 'version' | 'kind' | 'op'> & {
  readonly reason: string;
};

/**
 * A request this client can correct nothing about, as the facts of why —
 * `FileResult`'s reason and its data, which the server words (spec 2026-09-30:
 * the file side reports facts). Answered as `refused`, never `unavailable`: the
 * channel is fine, the question was wrong.
 */
export class Unservable extends Error {
  readonly facts: Refusal;

  constructor(facts: Refusal) {
    super(facts.reason);
    this.name = 'Unservable';
    this.facts = facts;
  }
}

export function requestIn(data: unknown): FileRequest | undefined {
  if (typeof data !== 'string') {
    return undefined;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(data);
  } catch {
    return undefined;
  }
  if (typeof parsed !== 'object' || parsed === null || isList(parsed)) {
    return undefined;
  }
  const fields = parsed as Record<string, unknown>;
  const id = fields['id'];
  if (typeof id !== 'string' || id === '') {
    return undefined;
  }
  const op = fields['op'];
  const path = textAt(fields, 'path');
  const pattern = textAt(fields, 'pattern');
  const content = textAt(fields, 'content');
  const offset = wholeAt(fields, 'offset');
  const limit = wholeAt(fields, 'limit');
  const needle = textAt(fields, 'needle');
  const ignoreCase = fields['ignoreCase'];
  const purpose = textAt(fields, 'purpose');
  const replacing = textAt(fields, 'replacing');
  const to = textAt(fields, 'to');
  const createOnly = fields['createOnly'];
  const argv = textsAt(fields, 'argv');
  const env = variablesAt(fields, 'env');
  const inherit = textsAt(fields, 'inherit');
  const timeoutMillis = wholeAt(fields, 'timeoutMillis');
  const outputBytes = wholeAt(fields, 'outputBytes');
  const shells = fields['shells'];
  const stdin = textAt(fields, 'stdin');
  return {
    id,
    op: typeof op === 'string' ? op : '',
    ...(path === undefined ? {} : { path }),
    ...(pattern === undefined ? {} : { pattern }),
    ...(content === undefined ? {} : { content }),
    ...(offset === undefined ? {} : { offset }),
    ...(limit === undefined ? {} : { limit }),
    ...(needle === undefined ? {} : { needle }),
    ...(typeof ignoreCase === 'boolean' ? { ignoreCase } : {}),
    ...(purpose === undefined ? {} : { purpose }),
    ...(replacing === undefined ? {} : { replacing }),
    ...(to === undefined ? {} : { to }),
    ...(typeof createOnly === 'boolean' ? { createOnly } : {}),
    ...(argv === undefined ? {} : { argv }),
    ...(env === undefined ? {} : { env }),
    ...(inherit === undefined ? {} : { inherit }),
    ...(timeoutMillis === undefined ? {} : { timeoutMillis }),
    ...(outputBytes === undefined ? {} : { outputBytes }),
    ...(typeof shells === 'boolean' ? { shells } : {}),
    ...(stdin === undefined ? {} : { stdin }),
  };
}

/** A list of strings, or absent: a list holding anything else is not one this client reads. */
function textsAt(
  fields: Record<string, unknown>,
  key: string,
): string[] | undefined {
  const value = fields[key];
  return isList(value) && value.every((item) => typeof item === 'string')
    ? value
    : undefined;
}

/** A map of strings to strings, or absent, for the reason {@link textsAt} gives. */
function variablesAt(
  fields: Record<string, unknown>,
  key: string,
): Record<string, string> | undefined {
  const value = fields[key];
  if (typeof value !== 'object' || value === null || isList(value)) {
    return undefined;
  }
  const entries = Object.entries(value);
  return entries.every(([, item]) => typeof item === 'string')
    ? Object.fromEntries(entries)
    : undefined;
}

function textAt(
  fields: Record<string, unknown>,
  key: string,
): string | undefined {
  const value = fields[key];
  return typeof value === 'string' ? value : undefined;
}

function wholeAt(
  fields: Record<string, unknown>,
  key: string,
): number | undefined {
  const value = fields[key];
  return typeof value === 'number' ? value : undefined;
}

/** `FileRequest.window()`: absent means the start and the most, and too many is clamped. */
export function windowOf(request: FileRequest): {
  offset: number;
  limit: number;
} {
  const offset = request.offset ?? 0;
  const limit = Math.min(request.limit ?? MAX_WINDOW_LINES, MAX_WINDOW_LINES);
  if (!Number.isInteger(offset) || offset < 0) {
    throw new Unservable({
      reason: UNSERVABLE,
      argument: 'offset',
      first: offset,
    });
  }
  if (!Number.isInteger(limit) || limit < 1) {
    throw new Unservable({ reason: UNSERVABLE, argument: 'limit', limit });
  }
  return { offset, limit };
}

/** `Window.cut`, including the order of its three stops. */
export function cut(
  all: readonly string[],
  offset: number,
  limit: number,
): Span {
  const total = all.length;
  if (offset >= total) {
    return {
      lines: [],
      offset,
      totalLines: total,
      more: false,
      stoppedBy: 'end',
    };
  }
  const taken: string[] = [];
  let bytes = 0;
  let stoppedBy: Stopped = 'end';
  for (let at = offset; at < total; at += 1) {
    if (taken.length === limit) {
      stoppedBy = 'lines';
      break;
    }
    const line = all[at] ?? '';
    const size = utf8Length(line);
    const cost = size + 1;
    if (taken.length === 0 && size > MAX_WINDOW_BYTES) {
      throw new Unservable({
        reason: LINE_TOO_WIDE,
        first: at,
        bytes: size,
        limit: MAX_WINDOW_BYTES,
      });
    }
    if (taken.length > 0 && bytes + cost > MAX_WINDOW_BYTES) {
      stoppedBy = 'bytes';
      break;
    }
    taken.push(line);
    bytes += cost;
    if (bytes >= MAX_WINDOW_BYTES) {
      stoppedBy = 'bytes';
      break;
    }
  }
  const more = offset + taken.length < total;
  return {
    lines: taken,
    offset,
    totalLines: total,
    more,
    stoppedBy: more ? stoppedBy : 'end',
  };
}

/**
 * `String.getBytes(UTF_8).length`. A lone surrogate counts one byte, because Java
 * replaces it with `?` rather than encoding it — and the server measures the
 * window it is sent against Java's count, not this one's.
 */
export function utf8Length(text: string): number {
  let bytes = 0;
  for (const character of text) {
    const point = character.codePointAt(0) ?? 0;
    if (point >= 0xd800 && point <= 0xdfff) {
      bytes += 1;
    } else if (point < 0x80) {
      bytes += 1;
    } else if (point < 0x800) {
      bytes += 2;
    } else if (point < 0x10000) {
      bytes += 3;
    } else {
      bytes += 4;
    }
  }
  return bytes;
}

/** `String.lines()`: every line ending, and no empty line after a final one. */
export function linesOf(text: string): string[] {
  if (text === '') {
    return [];
  }
  const lines = text.split(/\r\n|\r|\n/);
  if (lines.at(-1) === '') {
    lines.pop();
  }
  return lines;
}

/**
 * `Character.isWhitespace`, not `\s` and not `String.prototype.trim`.
 *
 * <p>Three code points are Unicode space separators that Java's method
 * deliberately excludes — U+00A0 (no-break space), U+2007 (figure space) and
 * U+202F (narrow no-break space) — because each is meant to hold a line
 * together rather than break it. `String.isBlank()` walks this method
 * codepoint by codepoint, so a needle made only of one of those three is not
 * blank in Java and must not be refused here either, even though it looks
 * exactly like a run of ordinary spaces.
 *
 * <p>The other Unicode space separators (category `Zs`, plus the line and
 * paragraph separators `Zl`/`Zp`) count as whitespace, and so do the nine
 * ASCII control characters Java lists by name: tab, line feed, vertical tab,
 * form feed, carriage return, and the four separator controls U+001C–U+001F.
 */
const SPACE_LIKE = /\p{Zs}|\p{Zl}|\p{Zp}/u;

export function isJavaWhitespace(codePoint: number): boolean {
  if (codePoint === 0x00a0 || codePoint === 0x2007 || codePoint === 0x202f) {
    return false;
  }
  if (SPACE_LIKE.test(String.fromCodePoint(codePoint))) {
    return true;
  }
  switch (codePoint) {
    case 0x09:
    case 0x0a:
    case 0x0b:
    case 0x0c:
    case 0x0d:
    case 0x1c:
    case 0x1d:
    case 0x1e:
    case 0x1f:
      return true;
    default:
      return false;
  }
}

/** `String.isBlank()`: empty, or nothing but {@link isJavaWhitespace}. */
function isBlank(text: string): boolean {
  for (const character of text) {
    if (!isJavaWhitespace(character.codePointAt(0) ?? 0)) {
      return false;
    }
  }
  return true;
}

/** `FileRequest.sought()` and `Needle`'s own refusal. */
export function sought(request: FileRequest): Needle {
  const text = request.needle;
  if (text === undefined || isBlank(text)) {
    throw new Unservable({
      reason: UNSERVABLE,
      argument: 'needle',
      ...(text === undefined ? {} : { detail: text }),
    });
  }
  return { text, ignoreCase: request.ignoreCase === true };
}

/**
 * One UTF-16 code unit, folded the way `Character.toUpperCase(char)` then
 * `Character.toLowerCase(char)` folds it — never the way
 * `String.prototype.toLowerCase()` folds a whole string.
 *
 * <p>Java's `regionMatches(true, …)` compares two strings unit by unit and
 * accepts a pair when `c1 == c2`, or `toUpperCase(c1) == toUpperCase(c2)`, or
 * `toLowerCase(toUpperCase(c1)) == toLowerCase(toUpperCase(c2))` — and because
 * each step only strengthens the last (`c1 == c2` implies the upper-cased
 * forms are equal, which implies their lower-cased forms are equal too), the
 * three-way check collapses to one: fold both units through
 * `toLowerCase(toUpperCase(·))` and compare the folded units. That fold never
 * consults a neighbour, which is the difference this function exists for —
 * <b>`Character.toUpperCase`/`toLowerCase` are single-character mappings and
 * never see context</b>, unlike `String.prototype.toLowerCase()`, which
 * applies Greek's word-final-sigma rule and rewrites a trailing Σ to ς
 * (U+03C2) instead of the context-free σ (U+03C3) that Java's per-character
 * fold produces and that a needle spelled with an ordinary σ expects to find.
 *
 * <p><b>Only ever asked to fold a single code unit, and never expanded past
 * one.</b> JS's `toUpperCase()`/`toLowerCase()` implement the <i>full</i>
 * Unicode case mapping (`SpecialCasing.txt`), which for a handful of
 * characters — `İ` (U+0130) lower-cases to two units, `ß` upper-cases to two —
 * produces more units than it was given, where Java's single-`char` overloads
 * are simple 1:1 mappings and can never do that. Falling back to the
 * pre-mapping unit whenever a mapping would grow keeps every folded unit
 * exactly one unit long, which is what lets {@link javaFold} preserve length
 * and lets plain `includes` stand in for a sliding `regionMatches`.
 */
function foldedUnit(unit: string): string {
  const upper = unit.toUpperCase();
  const steppedUp = upper.length === 1 ? upper : unit;
  const lower = steppedUp.toLowerCase();
  return lower.length === 1 ? lower : steppedUp;
}

/**
 * A string folded one UTF-16 code unit at a time, the way `regionMatches(true,
 * …)` folds each position it compares.
 *
 * <p>Indexed rather than iterated by code point, because Java's `char` — and
 * so `regionMatches`, `charAt`, and every offset {@link Needle} deals in — is a
 * UTF-16 code unit, and a surrogate pair is two of them compared
 * independently. {@link foldedUnit} always returns exactly one unit, so this
 * function's output is always the same length as its input: a match found by
 * `includes` on two folded strings sits at the position a sliding
 * `regionMatches` would have accepted too.
 */
function javaFold(text: string): string {
  let folded = '';
  for (let at = 0; at < text.length; at += 1) {
    folded += foldedUnit(text[at] ?? '');
  }
  return folded;
}

/** `Needle.find`: true when there was a match past {@link MAX_MATCHES}. */
export function find(
  path: string,
  lines: readonly string[],
  needle: Needle,
  into: Match[],
): boolean {
  const wanted = needle.ignoreCase ? javaFold(needle.text) : needle.text;
  for (let at = 0; at < lines.length; at += 1) {
    const line = lines[at] ?? '';
    const haystack = needle.ignoreCase ? javaFold(line) : line;
    if (!haystack.includes(wanted)) {
      continue;
    }
    if (into.length >= MAX_MATCHES) {
      return true;
    }
    const shown = shortened(line);
    into.push({
      path,
      offset: at,
      line: shown,
      truncated: shown.length !== line.length,
    });
  }
  return false;
}

function shortened(line: string): string {
  if (line.length <= MAX_LINE_CHARS) {
    return line;
  }
  let end = MAX_LINE_CHARS;
  const unit = line.charCodeAt(end - 1);
  if (unit >= 0xd800 && unit <= 0xdbff) {
    end -= 1;
  }
  return line.slice(0, end);
}

/** `Replacement.Kind`, spelled as the shared case table spells it. */
export type Unreplaceable = 'absent' | 'ambiguous' | 'empty';

/**
 * `Replacement.Refused`: why an edit could not be made, and how many occurrences
 * there were. For an absent `old` it keeps the file's text and `old` as well, as
 * the Java one does, so the refusal can report the nearest lines of the file —
 * `refusalResult` in `editfacts.ts` finds them.
 */
export class Unreplaced extends Error {
  readonly kind: Unreplaceable;
  readonly count: number;
  /** The file and the text that was not in it, for an absent one's view; else undefined. */
  readonly text: string | undefined;
  readonly old: string | undefined;

  constructor(kind: Unreplaceable, count: number, text?: string, old?: string) {
    super(`${kind} (${count})`);
    this.name = 'Unreplaced';
    this.kind = kind;
    this.count = count;
    this.text = text;
    this.old = old;
  }
}

/**
 * `Replacement.apply`: `text` with the single occurrence of `old` replaced.
 *
 * <p>Occurrences are counted at every position, overlapping ones included, so
 * `aa` is in `aaa` twice — the next search starts one unit past the last match,
 * not past its end. Both languages' strings are UTF-16, so `indexOf` agrees.
 */
export function replaced(
  text: string,
  old: string,
  replacement: string,
): string {
  return replacedAt(text, old, replacement).text;
}

/** {@link replaced}, and the index in the result at which the replacement starts. */
export function replacedAt(
  text: string,
  old: string,
  replacement: string,
): { text: string; at: number } {
  if (old === '') {
    throw new Unreplaced('empty', 0);
  }
  const first = text.indexOf(old);
  if (first < 0) {
    throw new Unreplaced('absent', 0, text, old);
  }
  let count = 1;
  for (
    let at = text.indexOf(old, first + 1);
    at >= 0;
    at = text.indexOf(old, at + 1)
  ) {
    count += 1;
  }
  if (count > 1) {
    throw new Unreplaced('ambiguous', count);
  }
  return {
    text: text.slice(0, first) + replacement + text.slice(first + old.length),
    at: first,
  };
}

const RECURSIVE = '**/';

function recursiveAt(pattern: string, from: number): number {
  for (
    let at = pattern.indexOf(RECURSIVE, from);
    at >= 0;
    at = pattern.indexOf(RECURSIVE, at + 1)
  ) {
    if (at === 0 || pattern[at - 1] === '/') {
      return at;
    }
  }
  return -1;
}

/** `GlobSpellings.matchers`' expansion: each recursive wildcard spelled as itself and as nothing. */
export function spellings(pattern: string): string[] {
  let recursive = 0;
  for (
    let at = recursiveAt(pattern, 0);
    at >= 0;
    at = recursiveAt(pattern, at + 1)
  ) {
    recursive += 1;
  }
  if (recursive > MAX_RECURSIVE_WILDCARDS) {
    throw new Unservable({
      reason: TOO_MANY_WILDCARDS,
      pattern,
      count: recursive,
      limit: MAX_RECURSIVE_WILDCARDS,
    });
  }
  const into = new Set<string>();
  spell(pattern, 0, '', into);
  return [...into];
}

function spell(
  pattern: string,
  from: number,
  built: string,
  into: Set<string>,
): void {
  const at = recursiveAt(pattern, from);
  if (at < 0) {
    into.add(built + pattern.slice(from));
    return;
  }
  const head = built + pattern.slice(from, at);
  const after = at + RECURSIVE.length;
  spell(pattern, after, head + RECURSIVE, into);
  spell(pattern, after, head, into);
}

/**
 * One spelling as the JDK's `glob:` matcher reads it, against a `/`-separated
 * relative path: `*` and `?` stay inside a segment, `**` crosses them, `[…]` and
 * `[!…]` are classes, `{a,b}` is one level of alternatives, `\` escapes.
 */
export function globMatcher(spelling: string): (relative: string) => boolean {
  let source = '';
  let grouped = false;
  for (let at = 0; at < spelling.length; at += 1) {
    const character = spelling[at] ?? '';
    if (character === '\\') {
      const next = spelling[at + 1];
      if (next === undefined) {
        throw new Unservable({
          reason: BAD_PATTERN,
          pattern: spelling,
          detail: 'it ends in an escape with nothing to escape',
        });
      }
      source += literal(next);
      at += 1;
    } else if (character === '*') {
      if (spelling[at + 1] === '*') {
        source += '.*';
        at += 1;
      } else {
        source += '[^/]*';
      }
    } else if (character === '?') {
      source += '[^/]';
    } else if (character === '[') {
      const close = spelling.indexOf(']', at + 1);
      if (close < 0) {
        throw new Unservable({
          reason: BAD_PATTERN,
          pattern: spelling,
          detail: 'it opens a [ it never closes',
        });
      }
      let body = spelling.slice(at + 1, close);
      const negated = body.startsWith('!');
      if (negated) {
        body = body.slice(1);
      }
      source += `[${negated ? '^' : ''}${body.replace(/[\\\]^[]/g, (found) => `\\${found}`)}]`;
      at = close;
    } else if (character === '{') {
      if (grouped) {
        throw new Unservable({
          reason: BAD_PATTERN,
          pattern: spelling,
          detail: 'it nests one {…} inside another',
        });
      }
      grouped = true;
      source += '(?:';
    } else if (character === '}' && grouped) {
      grouped = false;
      source += ')';
    } else if (character === ',' && grouped) {
      source += '|';
    } else {
      source += literal(character);
    }
  }
  if (grouped) {
    throw new Unservable({
      reason: BAD_PATTERN,
      pattern: spelling,
      detail: 'it opens a { it never closes',
    });
  }
  const compiled = new RegExp(`^${source}$`);
  return (relative) => compiled.test(relative);
}

function literal(character: string): string {
  return /[.*+?^${}()|[\]\\/]/.test(character) ? `\\${character}` : character;
}
