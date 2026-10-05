import type { Answer } from 'plowshare-client-ts/operations/response';
import {
  ALWAYS_CAPS,
  bodyOf,
  countAt,
  fieldsOf,
  OK,
  textAt,
} from './session.ts';
import type { Ask, DialogKind } from './session.ts';
import type { QuestionStroke } from './questions.ts';

/*
 * THE CAPS THE PERSON CONTROLS. Spec 2026-09-29 §2: `caps:` in the project's own
 * `.plowshare/environment.yml`, which `/cap` writes, and `orchestration.caps`, which reads them back
 * as the server sees them and applies them to the runs already going.
 */

/** A project's caps, read and applied — `FrameTypes.ORCHESTRATION_CAPS`. */
export const ORCHESTRATION_CAPS = 'orchestration.caps';

/** What `/always caps` and the dialog's `a` set `auto-continue` to — `session.ts`'s, whose
 *  parser needs it and which this file must not be imported by. */
export { ALWAYS_CAPS };

/** One cap: its value, absent when unset, and where it came from. */
export interface CapSetting {
  readonly value?: number;
  readonly source: string;
}

/**
 * `CapsFrames.CapsView`. `time` (V69) is the minutes a run goes before it asks, none when unset;
 * `failedChecks` how many times its check may fail before it asks — the server's `default` five
 * when no file sets it. A server from before V69 names neither, and reads as those.
 */
export interface Caps {
  readonly project: string;
  readonly steps: CapSetting;
  readonly budget: CapSetting;
  readonly autoContinue: CapSetting;
  readonly time: CapSetting;
  readonly failedChecks: CapSetting;
  readonly autoIncrease?: { readonly value?: boolean; readonly source: string };
  readonly applied: number;
  readonly said?: string;
}

/** How many failed checks a run takes before it asks when no file says — `ProjectCaps.DEFAULT_FAILED_CHECKS`. */
export const DEFAULT_FAILED_CHECKS = 5;

/**
 * What the dialog's `a` sets `auto-continue` to: {@link ALWAYS_CAPS}, or what is already set when
 * that is more (final review) — `a` means "keep going", and writing 3 over a 5 the person chose
 * lowered it.
 */
export function alwaysAutoContinue(current: number | undefined): number {
  return Math.max(current ?? 0, ALWAYS_CAPS);
}

/** What a cap question's dialog can be answered with. */
export type CapKey = 'continue' | 'stop' | 'always' | 'watch' | 'later';

/** What any question's dialog can be answered with: a cap's keys, and `r` to reply in words. */
export type DialogKey = CapKey | 'reply';

/**
 * The keys each dialog takes, in the order its last line says them. A cap's `y` is yes, a stuck
 * run's is "go on", and a product check's or a checker's concerns' is `accept`, whose `r` answers it
 * in words instead; a
 * root's question is answered in words, so its dialog only puts `/answer` in the composer. Esc — or,
 * read by line, an empty one — is always `later`.
 */
export const DIALOG_KEYS: Readonly<Record<DialogKind, readonly DialogKey[]>> = {
  cap: ['continue', 'stop', 'always', 'watch', 'later'],
  stuck: ['continue', 'stop', 'watch', 'later'],
  accept: ['continue', 'reply', 'watch', 'later'],
  // A FAILING CHECK'S `y` IS "go on" AND ITS `n` "stop" (V69) — never `a`: auto-continue never
  // passes a repeating failure, which is exactly what the person is asked to see.
  checks: ['continue', 'stop', 'watch', 'later'],
  question: ['reply', 'watch', 'later'],
  // AN APPROVAL IS ANSWERED WITH THE APPROVAL PROMPT'S OWN KEYS — `o`, `c`, `p`, `d`, the arrows
  // — read as `approval.ts` strokes, not as these; esc, or an empty line, is later as here.
  approval: ['later'],
};

export function readingCaps(project: string): Ask {
  return { type: ORCHESTRATION_CAPS, payload: { project } };
}

function settingOf(value: unknown): CapSetting {
  const fields = fieldsOf(value);
  const number = countAt(fields, 'value');
  return {
    ...(number === undefined ? {} : { value: number }),
    source: textAt(fields, 'source') ?? 'definition',
  };
}

export function capsOf(answer: Answer): Caps | undefined {
  const body = bodyOf(answer, OK);
  const project = body === undefined ? undefined : textAt(body, 'project');
  if (body === undefined || project === undefined) {
    return undefined;
  }
  const said = textAt(body, 'said');
  return {
    project,
    steps: settingOf(body['steps']),
    budget: settingOf(body['budget']),
    autoContinue: settingOf(body['autoContinue']),
    time: settingOf(body['time']),
    failedChecks:
      body['failedChecks'] === undefined
        ? { value: DEFAULT_FAILED_CHECKS, source: 'default' }
        : settingOf(body['failedChecks']),
    ...(body['autoIncrease'] === undefined
      ? {}
      : { autoIncrease: booleanSettingOf(body['autoIncrease']) }),
    applied: countAt(body, 'applied') ?? 0,
    ...(said === undefined ? {} : { said }),
  };
}

/**
 * The plain surface's dialog: a line that is exactly one of `keys`, or empty for later. A letter
 * another dialog would take is a line like any other.
 */
export function dialogKeyOfLine(
  line: string,
  keys: readonly DialogKey[],
): DialogKey | undefined {
  const key = line.trim() === 'r' ? 'reply' : capKeyOfLine(line);
  return key === undefined || !keys.includes(key) ? undefined : key;
}

/** One answer a harness question offers, as its modal lists it: the key it acts as, its letter, its words. */
export interface DialogOption {
  readonly key: DialogKey;
  /** The letter that picks it, or '' for `later`, which esc is. */
  readonly letter: string;
  readonly label: string;
}

/** Where picking a harness question's answer stands. */
export type Picking =
  | {
      readonly kind: 'picking';
      readonly options: readonly DialogOption[];
      readonly focus: number;
    }
  | { readonly kind: 'picked'; readonly key: DialogKey };

/**
 * The list before any key, the arrows on its last option — `later`, in every kind's `DIALOG_KEYS`
 * — so an Enter that outran the dialog decides later, never goes on or stops a run.
 */
export function pickingAbout(options: readonly DialogOption[]): Picking {
  return { kind: 'picking', options, focus: Math.max(0, options.length - 1) };
}

/**
 * The next state after one key: the arrows move, enter picks the focus, a letter picks its option,
 * esc is `later`. A letter no option has is nothing — `a` is not "always" to a stuck run.
 */
export function pickingOn(state: Picking, stroke: QuestionStroke): Picking {
  if (state.kind === 'picked') {
    return state;
  }
  switch (stroke.kind) {
    case 'escape':
      return { kind: 'picked', key: 'later' };
    case 'up':
      return { ...state, focus: Math.max(0, state.focus - 1) };
    case 'down':
      return {
        ...state,
        focus: Math.min(state.options.length - 1, state.focus + 1),
      };
    case 'enter': {
      const focused = state.options[state.focus];
      return focused === undefined
        ? state
        : { kind: 'picked', key: focused.key };
    }
    case 'text': {
      const letter = stroke.text.toLowerCase();
      const option =
        letter === ''
          ? undefined
          : state.options.find((each) => each.letter === letter);
      return option === undefined ? state : { kind: 'picked', key: option.key };
    }
    default:
      return state;
  }
}

/** The plain surface's dialog: a line that is exactly one key, or empty for later. */
export function capKeyOfLine(line: string): CapKey | undefined {
  switch (line.trim()) {
    case 'y':
      return 'continue';
    case 'n':
      return 'stop';
    case 'a':
      return 'always';
    case 'w':
      return 'watch';
    case '':
      return 'later';
    default:
      return undefined;
  }
}

function booleanSettingOf(raw: unknown): {
  readonly value?: boolean;
  readonly source: string;
} {
  const body = fieldsOf(raw);
  return {
    source:
      body === undefined
        ? 'definition'
        : (textAt(body, 'source') ?? 'definition'),
    ...(body !== undefined && typeof body['value'] === 'boolean'
      ? { value: body['value'] }
      : {}),
  };
}
