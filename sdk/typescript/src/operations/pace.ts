// Pure views shared by terminal and desktop clients.
import type { Pace } from './inspection.ts';

/**
 * How long something has been running, as a person would say it.
 *
 * <b>Here rather than in `screen.ts` because it is a sentence</b>, and every
 * other sentence this client says is in this file. A window drawing "4s" beside
 * a spinner says the same four characters, which is the test of whether a thing
 * belongs here.
 *
 * <p><b>Never counts backwards.</b> `Working.since` is a wall-clock reading and
 * whoever subtracts it is reading the clock again; a machine that resynchronised
 * in between gives a negative difference. That is a clock that moved, not a run
 * that started in the future, and `-3s` beside a spinner is a worse answer than
 * `0s`.
 */
export function describeElapsed(millis: number): string {
  const seconds = Math.max(0, Math.floor(millis / 1000));
  if (seconds < 60) {
    return `${seconds}s`;
  }
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

/**
 * One number of a run's pace, and which phase it belongs to — which is how a
 * view colours it.
 *
 * <p>`tools` carries the bare count: what marks it as tool calls is the view's
 * glyph, not a word.
 */
export interface PacePart {
  readonly kind: 'tools' | 'thinking' | 'responding' | 'waiting' | 'speed';
  readonly text: string;
}

/**
 * A run's pace in the parts a status line has room for, dropped from the end as
 * room runs out: what the model did first, how long and how fast last.
 *
 * <p>`—` for every number the server had none of. A dash is "not measured"; a
 * zero would be "measured, and none".
 */
export function describePace(pace: Pace): PacePart[] {
  const or = (
    value: number | undefined,
    say: (known: number) => string,
  ): string => (value === undefined ? '—' : say(value));
  return [
    { kind: 'tools', text: String(pace.toolCalls) },
    // `~` for an estimate: the endpoint counted the thinking as nothing, and
    // the server split the call's own count by what streamed.
    {
      kind: 'thinking',
      text: `think ${or(
        pace.reasoningTokens,
        (known) =>
          `${pace.reasoningEstimated === true ? '~' : ''}${tokens(known)}`,
      )}`,
    },
    { kind: 'responding', text: `out ${or(pace.completionTokens, tokens)}` },
    { kind: 'waiting', text: `ttft ${or(pace.firstTokenMillis, millis)}` },
    { kind: 'speed', text: `${or(pace.tokensPerSecond, rate)} tok/s` },
  ];
}

/** `850ms` under a second, `1.8s` under a minute, `2m 5s` past one. */
function millis(value: number): string {
  if (value < 1000) {
    return `${Math.round(value)}ms`;
  }
  if (value < 60_000) {
    return `${(Math.round(value / 100) / 10).toFixed(1).replace(/\.0$/, '')}s`;
  }
  return describeElapsed(value);
}

/** `38`, and one decimal under ten, where a whole number would hide the difference. */
function rate(value: number): string {
  return value < 10
    ? (Math.round(value * 10) / 10).toFixed(1)
    : String(Math.round(value));
}

/** A token count the way a status line has room for: `950`, `16.2K`, `120K`, `1.2M`. */
export function tokens(count: number): string {
  const scaled = (value: number, unit: string): string =>
    `${(Math.round(value * 10) / 10).toFixed(1).replace(/\.0$/, '')}${unit}`;
  if (count < 1000) {
    return String(count);
  }
  if (count < 999_950) {
    return scaled(count / 1000, 'K');
  }
  return scaled(count / 1_000_000, 'M');
}
