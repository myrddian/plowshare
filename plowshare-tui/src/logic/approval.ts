import { answeringApproval } from './session.ts'
import type { Approval, Ask } from './session.ts'

/**
 * One question a run asked, answered a key at a time. Spec 2026-09-15, asking a
 * person, §5.
 *
 * <h2>A fold over keys, like the composer, and for the composer's reason</h2>
 *
 * <p>`view/terminal/composer.ts` made "what does this key do" a pure function
 * from state and a keypress to the next state, so it is answered by a test that
 * never opens a terminal. The same is true here, and more so: a key that sent
 * `project` with a prefix one argument wider than the person was shown would
 * approve something nobody saw. So the prefix a frame carries is the prefix
 * {@link describeAsking} in `wording.ts` drew, read off the same state.
 *
 * <p><b>The same keys wherever it is asked</b>: after the person's own turn ends waiting on it,
 * and in the dialog a question from one of their runs comes up in (`view/main.ts`).
 *
 * <p><b>The keys are this module's own words</b>, not Ink's `Key`: `logic/`
 * knows no terminal, and a window answering with four buttons and argument
 * chips reaches the same states by pressing the same strokes.
 *
 * <h2>Nothing is sent from here</h2>
 *
 * <p>An answer is a state carrying the {@link Ask}; the view sends it. Escape
 * from the choice is a state too, and it sends nothing — the question stays
 * open on the server, where nothing expires it.
 */

/** One key, in the words this module reads. */
export type Stroke =
    /** A printable character: `o`, `c`, `p` or `d` mean something, the rest nothing. */
    | { readonly kind: 'text'; readonly text: string }
    | { readonly kind: 'left' }
    | { readonly kind: 'right' }
    | { readonly kind: 'enter' }
    | { readonly kind: 'escape' }

/** Where one question stands. */
export type Asking =
    /** Waiting for `o`, `c`, `p` or `d`. */
    | { readonly kind: 'choosing'; readonly approval: Approval }
    /**
     * `p` was pressed: choosing how much of the command a project approval covers.
     *
     * @param length how many leading arguments, from 1 to the whole command
     */
    | { readonly kind: 'prefixing'; readonly approval: Approval; readonly length: number }
    /** Decided. The view sends `ask` and reads what comes back. */
    | { readonly kind: 'answered'; readonly approval: Approval; readonly ask: Ask }
    /** Escape at the choice: nothing is sent and the question stays open. */
    | { readonly kind: 'left'; readonly approval: Approval }

/** Whether a question asks about an acceptance set — several commands, one answer (V67). */
export function isSet(approval: Approval): boolean {
    return approval.commands !== undefined
}

/** A question, before any key. */
export function askingAbout(approval: Approval): Asking {
    return { kind: 'choosing', approval }
}

/** Whether nothing more is read for this question. */
export function settledAsking(state: Asking): boolean {
    return state.kind === 'answered' || state.kind === 'left'
}

/**
 * Where the prefix starts: the server's `defaultPrefix` when it is a leading
 * part of the command, and the program alone when it is not.
 *
 * <p><b>Checked rather than trusted</b>, because the server refuses a prefix
 * that does not lead the command, and a person who pressed Enter on what they
 * were shown should not be told their answer was malformed.
 */
export function startingLength(approval: Approval): number {
    const suggested = approval.defaultPrefix
    const leads = suggested.length > 0 && suggested.length <= approval.command.length
        && suggested.every((word, at) => approval.command[at] === word)
    return leads ? suggested.length : 1
}

/** The leading arguments a project approval would cover, at this length. */
export function prefixAt(approval: Approval, length: number): readonly string[] {
    return approval.command.slice(0, length)
}

/** This question after one key. A key that means nothing here leaves it as it was. */
export function pressingOn(state: Asking, stroke: Stroke): Asking {
    const { approval } = state
    if (state.kind === 'choosing') {
        if (stroke.kind === 'escape') {
            return { kind: 'left', approval }
        }
        if (stroke.kind !== 'text') {
            return state
        }
        // CASE-BLIND, BECAUSE CAPS LOCK IS NOT A DIFFERENT ANSWER. Anything
        // longer than one character is not a key at all — a paste — and a
        // paste that began with `d` must not deny a command.
        switch (stroke.text.toLowerCase()) {
            case 'o':
                return { kind: 'answered', approval, ask: answeringApproval(approval.id, 'once') }
            case 'c':
                return { kind: 'answered', approval, ask: answeringApproval(approval.id, 'conversation') }
            case 'd':
                return { kind: 'answered', approval, ask: answeringApproval(approval.id, 'deny') }
            case 'p':
                // A SET IS SEVERAL COMMANDS, and a project prefix covers one: it takes once,
                // conversation or deny (V67), and the server refuses a project answer to it.
                return isSet(approval) ? state
                    : { kind: 'prefixing', approval, length: startingLength(approval) }
            default:
                return state
        }
    }
    if (state.kind === 'prefixing') {
        // WHOLE ARGUMENTS, NEVER CHARACTERS. The server matches a prefix
        // argument by argument, so a half-argument is not a prefix it could
        // hold; at least the program, at most the whole command.
        switch (stroke.kind) {
            case 'left':
                return { ...state, length: Math.max(1, state.length - 1) }
            case 'right':
                return { ...state, length: Math.min(approval.command.length, state.length + 1) }
            case 'enter':
                return {
                    kind: 'answered', approval,
                    ask: answeringApproval(approval.id, 'project', prefixAt(approval, state.length)),
                }
            case 'escape':
                return { kind: 'choosing', approval }
            default:
                return state
        }
    }
    return state
}

/**
 * A line typed at a surface that has no keys, read as the strokes it spells.
 *
 * <p><b>For the plain surface</b>, which reads whole lines: `o`, `c`, `p` or `d`
 * is that key; `<` and `>` are the arrows, one per character, so `<<` is two;
 * an empty line is Enter; `esc` is Escape. Anything else is one text stroke,
 * which means nothing, so a sentence typed by mistake answers nothing.
 */
export function strokesOf(line: string): Stroke[] {
    const text = line.trim()
    if (text === '') {
        return [{ kind: 'enter' }]
    }
    if (text.toLowerCase() === 'esc') {
        return [{ kind: 'escape' }]
    }
    if (/^[<>]+$/u.test(text)) {
        return [...text].map((arrow) => (arrow === '<' ? { kind: 'left' } : { kind: 'right' }))
    }
    return [{ kind: 'text', text }]
}
