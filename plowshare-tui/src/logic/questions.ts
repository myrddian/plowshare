import { answeringRunWith } from './session.ts';
import type { Ask, Choice, Draft, Structure } from './session.ts';
import type { Stroke } from './approval.ts';

/**
 * A question with options — spec 2026-09-29-orchestration-studio §2.5 — answered a key at a time.
 *
 * <h2>A fold, for `approval.ts`'s reason</h2>
 *
 * <p>What a key does is a pure function from a state and a stroke to the next state, so it is
 * answered by a test that never opens a terminal. The labels a frame carries are read off the same
 * state `describeQuestionsDialog` drew, so what is sent is what the person was shown chosen.
 *
 * <p><b>Nothing is sent from here.</b> An answer is a state carrying the {@link Ask}; the view
 * sends it. Esc is a state too, and it sends nothing: the question stays open on the server.
 *
 * <p><b>Viewing the draft is not a state.</b> An install question's `v` ({@link draftAsked}) is
 * read by the view before the fold, which leaves the question as it was: the view shows the draft
 * and puts the same state back up, so nothing the person had chosen moves while they read.
 */

/**
 * How many characters an "Other" or a note may be — `StructuredAnswers.MOST_FREE`, counted as Java
 * counts a string's length, in UTF-16 units, as a JavaScript string's is. Past it the server
 * refuses the whole answer, so what is typed past it is not taken.
 */
export const MOST_FREE = 2000;

/** One key, in this module's words: the approval prompt's, and the ones a list needs. */
export type QuestionStroke =
  | Stroke
  | { readonly kind: 'up' }
  | { readonly kind: 'down' }
  | { readonly kind: 'tab' }
  | { readonly kind: 'backtab' }
  | { readonly kind: 'backspace' };

/** Where one question's answer stands: the options chosen, by index, and any words. */
export interface Answer {
  readonly chosen: readonly number[];
  readonly other?: string;
  readonly note?: string;
}

interface Place {
  readonly run: string;
  readonly structure: Structure;
  /** Which question is shown. */
  readonly at: number;
  /** Which of its options the arrows are on. */
  readonly focus: number;
  readonly answers: readonly Answer[];
}

/** Where the whole question stands. */
export type Answering =
  /** Keys move and choose; `missing` names a question Enter found unanswered. */
  | (Place & { readonly kind: 'choosing'; readonly missing?: string })
  /** `o` or `n` was pressed: typing the words, not yet kept. */
  | (Place & {
      readonly kind: 'typing';
      readonly what: 'other' | 'note';
      readonly text: string;
    })
  /** Decided. The view sends `ask`. */
  | {
      readonly kind: 'answered';
      readonly run: string;
      readonly structure: Structure;
      readonly ask: Ask;
    }
  /** Esc: nothing is sent and the question stays open. */
  | {
      readonly kind: 'left';
      readonly run: string;
      readonly structure: Structure;
    };

/** The question, before any key. */
export function answeringAbout(run: string, structure: Structure): Answering {
  return {
    kind: 'choosing',
    run,
    structure,
    at: 0,
    focus: 0,
    answers: structure.questions.map(() => ({ chosen: [] })),
  };
}

/** Whether nothing more is read for this question. */
export function settledAnswering(state: Answering): boolean {
  return state.kind === 'answered' || state.kind === 'left';
}

/** Whether one question has an answer the server would take. */
export function answeredEach(answer: Answer): boolean {
  return answer.chosen.length > 0 || answer.other !== undefined;
}

/** The question after one key. A key that means nothing here leaves it as it was. */
export function answeringOn(
  state: Answering,
  stroke: QuestionStroke,
): Answering {
  if (state.kind === 'answered' || state.kind === 'left') {
    return state;
  }
  return state.kind === 'typing'
    ? typingOn(state, stroke)
    : choosingOn(state, stroke);
}

/**
 * The draft `stroke` asks to see: `v`, while choosing, on a question that carries one (the Studio's
 * install question). Otherwise undefined — typing, `v` is a letter of the words, and {@link
 * answeringOn} leaves a question with no draft as it was.
 */
export function draftAsked(
  state: Answering,
  stroke: QuestionStroke,
): Draft | undefined {
  return state.kind === 'choosing' &&
    stroke.kind === 'text' &&
    stroke.text.toLowerCase() === 'v'
    ? state.structure.draft
    : undefined;
}

type Choosing = Extract<Answering, { kind: 'choosing' }>;
type Typing = Extract<Answering, { kind: 'typing' }>;

function placeOf(state: Choosing | Typing): Place {
  return {
    run: state.run,
    structure: state.structure,
    at: state.at,
    focus: state.focus,
    answers: state.answers,
  };
}

function choosingAt(place: Place): Choosing {
  return { kind: 'choosing', ...place };
}

function choosingOn(state: Choosing, stroke: QuestionStroke): Answering {
  const place = placeOf(state);
  const question = place.structure.questions[place.at];
  const answer = place.answers[place.at];
  if (question === undefined || answer === undefined) {
    return state;
  }
  const last = place.structure.questions.length - 1;
  switch (stroke.kind) {
    case 'escape':
      return { kind: 'left', run: place.run, structure: place.structure };
    case 'up':
      return choosingAt({ ...place, focus: Math.max(0, place.focus - 1) });
    case 'down':
      return choosingAt({
        ...place,
        focus: Math.min(question.options.length - 1, place.focus + 1),
      });
    case 'tab':
      return choosingAt(moveTo(place, Math.min(last, place.at + 1)));
    case 'backtab':
      return choosingAt(moveTo(place, Math.max(0, place.at - 1)));
    case 'enter': {
      // ENTER ON AN UNANSWERED SINGLE CHOICE IS "THIS ONE": the arrows put the person on it.
      const picked =
        !question.multi && !answeredEach(answer)
          ? choose(place, place.focus)
          : place;
      return place.at < last
        ? choosingAt(moveTo(picked, place.at + 1))
        : sending(picked);
    }
    case 'text': {
      if (stroke.text === ' ') {
        return choosingAt(choose(place, place.focus));
      }
      // ONE CHARACTER OR NOTHING, as `approval.ts` reads it: a paste that began with `1` is
      // not a choice.
      if (/^[1-9]$/u.test(stroke.text)) {
        const index = Number(stroke.text) - 1;
        return index < question.options.length
          ? choosingAt({ ...choose(place, index), focus: index })
          : state;
      }
      switch (stroke.text.toLowerCase()) {
        case 'o':
          return {
            kind: 'typing',
            ...place,
            what: 'other',
            text: answer.other ?? '',
          };
        case 'n':
          return {
            kind: 'typing',
            ...place,
            what: 'note',
            text: answer.note ?? '',
          };
        default:
          return state;
      }
    }
    default:
      return state;
  }
}

function typingOn(state: Typing, stroke: QuestionStroke): Answering {
  const place = placeOf(state);
  switch (stroke.kind) {
    case 'escape':
      return choosingAt(place);
    case 'backspace':
      return {
        ...state,
        text: [...Array.from(state.text)].slice(0, -1).join(''),
      };
    case 'text':
      return { ...state, text: upToMost(state.text, stroke.text) };
    case 'enter':
      return choosingAt(
        state.what === 'other'
          ? withOther(place, state.text.trim())
          : withNote(place, state.text.trim()),
      );
    default:
      return state;
  }
}

/** `more` added to `text` a character at a time, as far as {@link MOST_FREE} takes it, none split. */
function upToMost(text: string, more: string): string {
  let out = text;
  for (const point of more) {
    if (out.length + point.length > MOST_FREE) {
      break;
    }
    out += point;
  }
  return out;
}

/** The question `at`, the arrows on its first chosen option or its first. */
function moveTo(place: Place, at: number): Place {
  return { ...place, at, focus: place.answers[at]?.chosen[0] ?? 0 };
}

function replacing(place: Place, answer: Answer): Place {
  return {
    ...place,
    answers: place.answers.map((each, at) => (at === place.at ? answer : each)),
  };
}

/** Pick option `index`: the one choice of a single question, which drops its "Other"; toggled on a multi. */
function choose(place: Place, index: number): Place {
  const question = place.structure.questions[place.at];
  const answer = place.answers[place.at];
  if (question === undefined || answer === undefined) {
    return place;
  }
  if (!question.multi) {
    return replacing(place, {
      chosen: [index],
      ...(answer.note === undefined ? {} : { note: answer.note }),
    });
  }
  const chosen = answer.chosen.includes(index)
    ? answer.chosen.filter((each) => each !== index)
    : [...answer.chosen, index].sort((a, b) => a - b);
  return replacing(place, { ...answer, chosen });
}

/** "Other" kept: on a single question it replaces the choice; empty, it is taken away. */
function withOther(place: Place, text: string): Place {
  const question = place.structure.questions[place.at];
  const answer = place.answers[place.at];
  if (question === undefined || answer === undefined) {
    return place;
  }
  const note = answer.note === undefined ? {} : { note: answer.note };
  if (text === '') {
    return replacing(place, { chosen: answer.chosen, ...note });
  }
  return replacing(place, {
    chosen: question.multi ? answer.chosen : [],
    other: text,
    ...note,
  });
}

function withNote(place: Place, text: string): Place {
  const answer = place.answers[place.at];
  if (answer === undefined) {
    return place;
  }
  const other = answer.other === undefined ? {} : { other: answer.other };
  return replacing(place, {
    chosen: answer.chosen,
    ...other,
    ...(text === '' ? {} : { note: text }),
  });
}

/** Enter on the last question: sent, or moved to the first one unanswered and named. */
function sending(place: Place): Answering {
  const unanswered = place.answers.findIndex((answer) => !answeredEach(answer));
  if (unanswered >= 0) {
    return {
      ...choosingAt(moveTo(place, unanswered)),
      missing: place.structure.questions[unanswered]?.header ?? '',
    };
  }
  const choices: Choice[] = place.structure.questions.map((question, at) => {
    const answer = place.answers[at] ?? { chosen: [] };
    return {
      header: question.header,
      chosen: answer.chosen.map(
        (index) => question.options[index]?.label ?? '',
      ),
      ...(answer.other === undefined ? {} : { other: answer.other }),
      ...(answer.note === undefined ? {} : { note: answer.note }),
    };
  });
  return {
    kind: 'answered',
    run: place.run,
    structure: place.structure,
    ask: answeringRunWith(place.run, choices),
  };
}
