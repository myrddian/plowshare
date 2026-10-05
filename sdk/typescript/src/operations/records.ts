import { decodeReply } from './schema.ts';
import { isList } from '../binding/values.ts';
import { bodyOf, countAt, fieldsOf, OK, textAt } from './response.ts';
import type { Answer } from './response.ts';
import type { Ask } from './session.ts';

export const ORCHESTRATION_RECORD = 'orchestration.record';
export const TOOL_CALL = 'tool_call';
export const QUESTION_ASKED = 'question_asked';
export const RECORD_TAIL = 100;

/** The complete Java RecordView wire contract; unknown fields are omitted at the boundary. */
export interface RecordView {
  readonly ordinal: number;
  readonly at: string;
  readonly run: string;
  readonly actor: string;
  readonly kind: string;
  readonly text: string;
  readonly detail: string | null;
  readonly body?: string;
}
export interface RecordPageView {
  readonly root: string;
  readonly rows: readonly RecordView[];
  readonly total: number;
  readonly limit: number;
  readonly through: number;
  readonly oldest: number | null;
  readonly more: boolean | null;
}
const count = (value: unknown, minimum = 0): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum;
const object = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !isList(value);
/** Validate a complete server page without sorting, losing fields or claiming partial rows as success. */
export function recordReply(payload: unknown): RecordPageView | undefined {
  if (
    !object(payload) ||
    typeof payload['root'] !== 'string' ||
    !payload['root'].trim() ||
    !count(payload['total']) ||
    !count(payload['limit'], 1) ||
    !count(payload['through']) ||
    !(payload['oldest'] === null || count(payload['oldest'], 1)) ||
    !(payload['more'] === null || typeof payload['more'] === 'boolean') ||
    !isList(payload['rows'])
  )
    return undefined;
  const ordinals = new Set<number>();
  for (const row of payload['rows']) {
    if (
      !object(row) ||
      !count(row['ordinal'], 1) ||
      row['ordinal'] > payload['through'] ||
      ordinals.has(row['ordinal']) ||
      !['at', 'run', 'actor', 'kind', 'text'].every(
        (key) => typeof row[key] === 'string',
      ) ||
      !(row['detail'] === null || typeof row['detail'] === 'string') ||
      ('body' in row && typeof row['body'] !== 'string')
    )
      return undefined;
    ordinals.add(row['ordinal']);
  }
  try {
    return decodeReply('orchestration.record', payload);
  } catch {
    return undefined;
  }
}

/** One row — `RecordView`. `tool` is whether it is a tool line, so words need no kind names. */
export interface Recorded {
  readonly ordinal: number;
  readonly at: string;
  readonly run: string;
  readonly actor: string;
  readonly kind: string;
  readonly text: string;
  /** A tool line's outcome, a stage's summary, a phase's directory; absent when there is none. */
  readonly detail?: string;
  /**
   * The whole text behind `text`, its own line breaks kept, on the few rows a person reads in
   * full — a question, its answer, a stall, a run's ending — when the line is not all of it
   * (V64); absent otherwise. Wrapped to draw by `wrap.ts`.
   */
  readonly body?: string;
  readonly tool: boolean;
}

/** One read's rows, oldest first whichever way it was read. */
export interface RecordPage {
  /** The root the id resolved to. */
  readonly root: string;
  readonly rows: readonly Recorded[];
  /** The whole record's highest ordinal. */
  readonly through: number;
  /** Whether a backwards read left rows before these; false for a forward read. */
  readonly more: boolean;
}

function recordAsk(
  root: string,
  bounds: Omit<Ask<typeof ORCHESTRATION_RECORD>['payload'], 'root' | 'kinds'>,
  kinds?: readonly string[],
): Ask {
  return {
    type: ORCHESTRATION_RECORD,
    payload: {
      root,
      ...bounds,
      ...(kinds === undefined ? {} : { kinds: [...kinds] }),
    },
  };
}

/** The newest `limit` rows of a tree, of `kinds` or of every kind. */
export function readingRecordTail(
  root: string,
  kinds?: readonly string[],
  limit = RECORD_TAIL,
): Ask {
  return recordAsk(root, { tail: true, limit }, kinds);
}

/** The page before ordinal `before`. */
export function readingRecordEarlier(
  root: string,
  before: number,
  kinds?: readonly string[],
): Ask {
  return recordAsk(root, { before, limit: RECORD_TAIL }, kinds);
}

/** What came after ordinal `after`. */
export function readingRecordAfter(
  root: string,
  after: number,
  kinds?: readonly string[],
): Ask {
  return recordAsk(root, { after, limit: RECORD_TAIL }, kinds);
}

/** The one tool line at `ordinal`: the first tool line after the ordinal before it. */
export function readingToolLine(root: string, ordinal: number): Ask {
  return recordAsk(root, { after: ordinal - 1, limit: 1 }, [TOOL_CALL]);
}

/** A `RecordPageView`, oldest first, or nothing for a refusal or a page this build cannot read. */
export function recordPageOf(answer: Answer): RecordPage | undefined {
  const body = bodyOf(answer, OK);
  if (body === undefined) {
    return undefined;
  }
  const root = textAt(body, 'root');
  const through = countAt(body, 'through');
  const rows = body['rows'];
  if (root === undefined || through === undefined || !isList(rows)) {
    return undefined;
  }
  const read = rows
    .map((each) => recordedIn(fieldsOf(each)))
    .filter((each): each is Recorded => each !== undefined)
    .sort((a, b) => a.ordinal - b.ordinal);
  return { root, rows: read, through, more: body['more'] === true };
}

function recordedIn(fields: Record<string, unknown>): Recorded | undefined {
  const ordinal = countAt(fields, 'ordinal');
  const kind = textAt(fields, 'kind');
  const text = textAt(fields, 'text');
  if (ordinal === undefined || kind === undefined || text === undefined) {
    return undefined;
  }
  const detail = textAt(fields, 'detail');
  const body = textAt(fields, 'body');
  return {
    ordinal,
    at: textAt(fields, 'at') ?? '',
    run: textAt(fields, 'run') ?? '',
    actor: textAt(fields, 'actor') ?? '',
    kind,
    text,
    tool: kind === TOOL_CALL,
    ...(detail === undefined || detail === '' ? {} : { detail }),
    ...(body === undefined || body === '' ? {} : { body }),
  };
}

/** The latest question asked in a tree: for an asking tree whose last milestones do not hold it. */
export function readingQuestion(root: string): Ask {
  return readingRecordTail(root, [QUESTION_ASKED], 1);
}
