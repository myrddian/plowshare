import type { Entry } from './session.ts';
import {
  stepKey,
  stepsOf,
  turnsOf,
  type Call,
  type Step,
  type Turn,
} from './trajectory.ts';

/**
 * What the chat has drawn of the turns in flight: spec 2026-09-29 §4. The tracer reads every
 * row after `through`, turns them into steps, and hands back the ones that may now be written
 * into the scrollback — reasoning, and calls whose result has come back — in log order,
 * stopping at the first call still waiting so a line is never written above one it followed.
 * The person's words and the answers are the chat's own and are never written here.
 */
export interface Trace {
  readonly rows: readonly Entry[];
  readonly written: readonly string[];
  readonly through: number;
}

export interface Traced {
  readonly trace: Trace;
  readonly written: readonly Step[];
  readonly pending: readonly Call[];
}

/** How many turns of rows are kept: the one in flight and the one before it. */
const TURNS_KEPT = 2;

export const traceOpened = (through: number): Trace => ({
  rows: [],
  written: [],
  through,
});

const traced = (step: Step): boolean =>
  step.kind === 'call' || step.kind === 'reasoning';

export function traceGrew(trace: Trace, fresh: readonly Entry[]): Traced {
  const newer = fresh.filter((row) => row.ordinal > trace.through);
  const through = newer.reduce(
    (most, row) => Math.max(most, row.ordinal),
    trace.through,
  );
  // Read off EVERY row seen so far, not the ones already trimmed to the window: a call
  // pending in a turn the window is about to drop can only be judged settled, failed or
  // outlived by `stepsOf` seeing it beside whatever came after it in the same pass. A batch
  // that catches the log up by more than `TURNS_KEPT` turns at once — replay, or a follow
  // that missed a beat — would otherwise evict that row before `stepsOf` ever ran, and the
  // call would be neither written nor pending: it would simply disappear.
  const all = [...trace.rows, ...newer];
  const latest = all.reduce((most, row) => Math.max(most, row.turnOrdinal), 0);
  const steps = stepsOf(all).filter(traced);
  const done = new Set(trace.written);
  const written: Step[] = [];
  let blocked = false;
  for (const step of steps) {
    if (done.has(stepKey(step))) {
      continue;
    }
    if (step.kind === 'call' && step.pending) {
      blocked = true;
    }
    if (!blocked) {
      written.push(step);
      done.add(stepKey(step));
    }
  }
  const pending = steps.filter(
    (step): step is Call => step.kind === 'call' && step.pending,
  );
  // What is kept for next time: the ordinary window, pulled back far enough that a call
  // still pending is never trimmed out from under itself before it settles.
  const normalCutoff = latest - TURNS_KEPT + 1;
  const cutoff = pending.reduce(
    (least, call) => Math.min(least, call.turn),
    normalCutoff,
  );
  const rows = all.filter((row) => row.turnOrdinal >= cutoff);
  const kept = new Set(stepsOf(rows).filter(traced).map(stepKey));
  return {
    trace: { rows, written: [...done].filter((key) => kept.has(key)), through },
    written,
    pending,
  };
}

/** A turn's sums over what the tracer holds, for the footer under its answer. */
export function turnOf(trace: Trace, turn: number): Turn | undefined {
  return turnsOf(stepsOf(trace.rows)).find((each) => each.ordinal === turn);
}
