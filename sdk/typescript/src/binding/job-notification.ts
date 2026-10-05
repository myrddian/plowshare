import { isObject, isOneOf } from './values.ts';
/** Job hints never establish completion; consumers reconcile with job.status. */
export interface JobEvent {
  readonly part?: never;
  readonly text?: never;
  readonly job: string;
  readonly kind: string;
  readonly agent?: string;
  readonly tool?: string | null;
  readonly ending?: string | null;
  readonly steps?: number;
  readonly modelCalls?: number;
}
export interface JobDelta {
  readonly kind?: never;
  readonly job: string;
  readonly part: 'THINKING' | 'ANSWER';
  readonly text: string;
}
export type JobNotification = JobEvent | JobDelta;
const whole = (v: unknown): v is number =>
  typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;
const identity = (v: unknown, max = 1024): v is string =>
  typeof v === 'string' &&
  !!v.trim() &&
  v.length <= max &&
  !Array.from(v).some(
    (c) =>
      c.charCodeAt(0) < 32 ||
      (c.charCodeAt(0) >= 127 && c.charCodeAt(0) <= 159) ||
      c === '\u2028' ||
      c === '\u2029',
  );
/** Older job peers may omit counters/agent metadata. Every supplied field is checked and copied. */
export function jobNotification(value: unknown): JobNotification | undefined {
  if (!isObject(value) || !identity(value.job)) return undefined;
  const job = value.job;
  if ('part' in value) {
    if (
      !isOneOf(value.part, ['THINKING', 'ANSWER']) ||
      typeof value.text !== 'string' ||
      value.text.length > 1048576 ||
      value.text.includes('\0')
    )
      return undefined;
    return { job, part: value.part, text: value.text };
  }
  if (
    typeof value.kind !== 'string' ||
    !/^[a-z][a-z0-9_]{0,63}$/.test(value.kind)
  )
    return undefined;
  if (
    ('agent' in value && !identity(value.agent, 256)) ||
    ('steps' in value && !whole(value.steps)) ||
    ('modelCalls' in value && !whole(value.modelCalls)) ||
    ('tool' in value && value.tool !== null && !identity(value.tool, 256)) ||
    ('ending' in value &&
      value.ending !== null &&
      (typeof value.ending !== 'string' ||
        !/^[A-Z_]{1,64}$/.test(value.ending)))
  )
    return undefined;

  // The assertion follows validation of every exposed field, and unknown fields are omitted.
  return {
    job,
    kind: value.kind,
    ...Object.fromEntries(
      ['agent', 'steps', 'modelCalls', 'tool', 'ending']
        .filter((k) => Object.hasOwn(value, k))
        .map((k) => [k, value[k]]),
    ),
  };
}
