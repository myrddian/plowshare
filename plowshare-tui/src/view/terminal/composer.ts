import type { Key } from 'ink'

import { isOpenEnded, menuFor, picking } from '../../logic/menu.ts'
import type { Menu, Vocabulary } from '../../logic/menu.ts'
import { completing } from '../../logic/session.ts'
import type { Stroke } from '../../logic/approval.ts'
import type { QuestionStroke } from '../../logic/questions.ts'
import type { Composer } from '../../logic/screen.ts'
import type { ViewKey } from '../../logic/record.ts'
import type { ExploreKey } from '../../logic/explorer.ts'
import { DIALOG_KEYS, type DialogKey } from '../../logic/caps.ts'

/**
 * What a keypress means, decided without a terminal in the room.
 *
 * <h2>A pure function, and that is the whole design</h2>
 *
 * <p>Ink's `useInput` hands over `(input, key)` and expects the component to
 * do something about it. Everything it would have done inline lives here
 * instead, as a function from state and a keypress to the next state — so the
 * question "what does Ctrl-W do" is answered by a test that never opens a
 * terminal, never mounts React, and never waits for a frame.
 *
 * <p><b>And it ports.</b> A window has a textarea, which brings its own cursor,
 * its own selection and its own backspace — but not its own history, not Tab
 * over a roster the server declared, and not the rule that an empty line
 * submits nothing. Those three are decisions rather than mechanics, and they
 * are the ones this file will still be right about.
 *
 * <h2>Ours rather than `ink-text-input`</h2>
 *
 * <p>That package was last published in May 2024. The composer is the one
 * component somebody touches every second they are in this client, and a
 * two-year-old dependency for a hundred lines of cursor arithmetic is a bad
 * trade in both directions.
 *
 * <h2>What is deliberately NOT here</h2>
 *
 * <p><b>Ctrl-C and Ctrl-D.</b> They mean things about a conversation — stop the
 * run, then leave; end the input — and not things about a line. `mounting.ts`
 * holds them, beside the {@link import('../surface.ts').Surface} methods that
 * are how those meanings reach `converse`.
 */

/** The composer, plus where it is in the history it may be browsing. */
export interface Composing extends Composer {
    /**
     * How far back in the history, counting from the newest, or absent.
     *
     * <p>1 is the newest thing said, 2 the one before it. Absent means the line
     * is the person's own rather than one recalled — which is a different state
     * from "recalled the newest", because walking forward off the end has to
     * know whether there is a draft to come back to.
     */
    readonly recalled?: number

    /** The half-typed line that history browsing pushed aside, if any. */
    readonly draft?: string

    /**
     * Which row of the command menu is picked. Absent is the first, and every
     * edit to the line puts it back there: a different line is a different
     * menu, and a picked row carried across would point at something else.
     */
    readonly picked?: number

    /** Whether the arrows have moved {@link picked}, so Enter means the pick. */
    readonly moved?: boolean

    /** Whether Escape closed the menu. Any edit reopens it. */
    readonly closed?: boolean
}

/** The composer after a keypress, and what to send if the key was Return. */
export interface Pressed extends Composing {
    /**
     * What to send, or absent because this keypress sent nothing.
     *
     * <p><b>Absent and not empty.</b> A caller writing `if (submit !== undefined)`
     * is the obvious way to read this, and an empty string would send a blank
     * question to the server on every stray Enter.
     */
    readonly submit?: string
}

/**
 * A pasted block, as one line.
 *
 * <b>Measured, driving the real client through a pty.</b> A terminal delivers a
 * run of characters arriving together as TEXT rather than as keys, so anything
 * with a newline in it — a paste, or a driver writing a whole line at once —
 * reaches {@link pressing} as an ordinary insertion carrying `\r`. Inserted
 * literally those are invisible on screen and send the cursor to column zero
 * when the line is drawn: the transcript showed three separate inputs
 * apparently overwriting one another, and the entry that went out held all
 * three run together.
 *
 * <p><b>A composer is one line.</b> What a multi-line paste should MEAN —
 * submit each line, join them, refuse it — is a real question and not this
 * one's; a space is the answer that loses no characters and surprises nobody.
 * Every other control character is dropped, because a line is text.
 */
function asOneLine(input: string): string {
    return input
        // Control characters first, so a stray one cannot survive as a space.
        // `\r` and `\n` are spared here and dealt with below.
        .replace(/[\u0000-\u0009\u000b\u000c\u000e-\u001f\u007f]/gu, '')
        // Line endings at the EDGES are the shape of the paste, not content —
        // a block copied out of an editor almost always ends in one, and
        // turning that into a trailing space would make the composer's line
        // differ from what was copied. Leading and trailing spaces the person
        // actually pasted are left alone, which is why this is not `.trim()`.
        .replace(/^[\r\n]+|[\r\n]+$/gu, '')
        // The ones in the middle separate words, so they become the separator
        // a single line has.
        .replace(/[\r\n]+/gu, ' ')
}

/** The composer, cleared, keeping nothing from the line that just went. */
const EMPTY = { typed: '', at: 0, offering: [] as readonly string[] }

/** How much of `words` every one of them starts with. */
function agreedPrefix(words: readonly string[]): string {
    const first = words[0] ?? ''
    let length = first.length
    for (const word of words) {
        while (length > 0 && !word.startsWith(first.slice(0, length))) {
            length -= 1
        }
    }
    return first.slice(0, length)
}

/** The line with `word` at the cursor replaced by `finished`. */
function finishing(state: Composing, word: string, finished: string): Pressed {
    const before = state.typed.slice(0, state.at - word.length)
    const after = state.typed.slice(state.at)
    return {
        typed: `${before}${finished}${after}`,
        at: before.length + finished.length,
        offering: [],
    }
}

/** Where the word before the cursor begins, for Ctrl-W. */
function wordStart(text: string, at: number): number {
    let start = at
    while (start > 0 && text[start - 1] === ' ') {
        start -= 1
    }
    while (start > 0 && text[start - 1] !== ' ') {
        start -= 1
    }
    return start
}

/** What a key does while the menu is open, or `undefined` for a key it leaves alone. */
function menuKey(
    state: Composing,
    input: string,
    key: Key,
    menu: Menu,
    vocabulary: Vocabulary,
): Pressed | undefined {
    const { typed } = state
    const count = menu.choices.length
    const picked = Math.min(state.picked ?? 0, count - 1)
    const choice = menu.choices[picked]
    if (key.escape === true) {
        return { ...state, offering: [], closed: true }
    }
    if (key.upArrow === true || key.downArrow === true) {
        const step = key.upArrow === true ? -1 : 1
        return { ...state, offering: [], picked: (picked + step + count) % count, moved: true }
    }
    if (choice === undefined) {
        return undefined
    }
    const pick = (): Pressed => {
        const next = picking(typed, menu, choice, vocabulary)
        return { typed: next.typed, at: next.at, offering: [] }
    }
    if (key.tab === true && input.replace(/\t/gu, '') === '') {
        return pick()
    }
    if (key.return === true) {
        // ENTER SENDS WHAT IS TYPED WHEN IT IS ALREADY WHOLE. `/theme` typed in
        // full lists the themes; it does not become `/theme ` waiting for an
        // argument because the menu happened to be open. Only a pick the arrows
        // chose, or a word that is not yet any choice, is completed first.
        const word = typed.slice(menu.start, menu.end)
        const spelt = (offer: { readonly name: string }): string =>
            menu.kind === 'mention' ? `@${offer.name}` : offer.name
        const whole = menu.choices.some((offer) => spelt(offer) === word)
            || (menu.kind === 'argument' && word === '')
        // AN OPEN-ENDED ARGUMENT TYPED IN FULL IS STILL ONLY THE START OF THE
        // LINE. `/answer orc_1` wants an answer after it, so Enter leaves room
        // for one; only nothing typed at all — `/answer ` — is sent as it stands.
        const unfinished = isOpenEnded(menu, vocabulary) && word !== ''
        if (state.moved !== true && whole && !unfinished) {
            return undefined
        }
        const next = picking(typed, menu, choice, vocabulary)
        return next.complete
            ? { typed: '', at: 0, offering: [], submit: next.typed.trimEnd() }
            : { typed: next.typed, at: next.at, offering: [] }
    }
    return undefined
}

/** The line `back` steps from the newest, or nothing when there is no such line. */
function recalling(history: readonly string[], back: number): string | undefined {
    return history[history.length - back]
}

export function pressing(
    state: Composing,
    input: string,
    key: Key,
    declared: readonly string[],
    history: readonly string[],
    vocabulary?: Vocabulary,
): Pressed {
    const { typed, at } = state

    // THE MENU TAKES THE KEYS IT NEEDS WHILE IT IS OPEN, and only those. See
    // `logic/menu.ts` for when a line has one.
    const menu = vocabulary === undefined || state.closed === true
        ? undefined : menuFor(typed, at, vocabulary)
    if (menu !== undefined) {
        const taken = menuKey(state, input, key, menu, vocabulary as Vocabulary)
        if (taken !== undefined) {
            return taken
        }
    }

    if (key.return === true) {
        // TRIMMED TO DECIDE, NOT TO SEND. Whether this was a blank Enter is a
        // question about whitespace; what goes out is what somebody typed, and
        // a composer that trimmed would be editing their line on the way past.
        // `converse` trims for its own reasons, which is its business.
        return typed.trim() === '' ? { ...EMPTY } : { ...EMPTY, submit: typed }
    }

    // BOTH KEYS RUB OUT BACKWARDS, WHICH IS A CLAIM ABOUT KEYBOARDS RATHER THAN
    // ABOUT NAMES. Ink reports the key macOS labels "delete" as `delete`, and
    // it is the one people press to take back what they just typed. Making it
    // delete forwards would be right about the word and wrong about the hand.
    if (key.backspace === true || key.delete === true) {
        if (at === 0) {
            return { typed, at, offering: [] }
        }
        return { typed: typed.slice(0, at - 1) + typed.slice(at), at: at - 1, offering: [] }
    }

    if (key.leftArrow === true) {
        return { typed, at: Math.max(0, at - 1), offering: [] }
    }
    if (key.rightArrow === true) {
        return { typed, at: Math.min(typed.length, at + 1), offering: [] }
    }

    if (key.ctrl === true) {
        if (input === 'a') {
            return { typed, at: 0, offering: [] }
        }
        if (input === 'e') {
            return { typed, at: typed.length, offering: [] }
        }
        if (input === 'u') {
            return { ...EMPTY }
        }
        if (input === 'w') {
            const start = wordStart(typed, at)
            return { typed: typed.slice(0, start) + typed.slice(at), at: start, offering: [] }
        }
        // Every other Ctrl-key is somebody else's — Ctrl-C and Ctrl-D most of
        // all. Falling through to the printable branch would type a control
        // character into the line.
        return { typed, at, offering: [] }
    }

    // A CHUNK THAT ENDS IN A TAB IS A TAB, for the coalescing reason above.
    // Measured the same way: `/bo` followed by Tab arrived as one chunk
    // `"/bo\t"`, the tab was stripped as a control character, no completion
    // happened, and `/bo` went to the server as an unknown command.
    const tabbed = key.tab === true || /\t$/u.test(input)
    if (tabbed) {
        // Whatever text came with it is typed first, so the completion runs
        // against the line the person actually has.
        const typedFirst = asOneLine(input)
        if (typedFirst !== '') {
            const grown = typed.slice(0, at) + typedFirst + typed.slice(at)
            return pressing(
                { typed: grown, at: at + typedFirst.length, offering: [] },
                '', { tab: true } as Key, declared, history, vocabulary)
        }
        // AGAINST WHAT IS BEFORE THE CURSOR, not the whole line. Tab pressed in
        // the middle of a line completes the word it is in; taking the whole
        // line would complete against text the person had already moved past.
        const { word, matches } = completing(typed.slice(0, at), declared)
        if (matches.length === 0) {
            return { typed, at, offering: [] }
        }
        const only = matches[0]
        if (matches.length === 1 && only !== undefined) {
            return finishing(state, word, only)
        }
        // Ambiguous: take the line as far as every match agrees and show the
        // rest, which is what a person needs to decide the next character.
        const agreed = agreedPrefix(matches)
        const reached = agreed.length > word.length ? finishing(state, word, agreed)
            : { typed, at, offering: [] as readonly string[] }
        return { ...reached, offering: matches }
    }


    if (key.upArrow === true || key.downArrow === true) {
        if (history.length === 0) {
            return { typed, at, offering: [] }
        }
        const held = state.recalled ?? 0
        const back = key.upArrow === true ? held + 1 : held - 1
        if (back > history.length) {
            // Already at the oldest. Staying is kinder than emptying the line,
            // which is what a naive index would do at the end of the walk.
            return { ...state, offering: [] }
        }
        if (back <= 0) {
            // Walked forward off the newest, so the line somebody was part-way
            // through comes back. Losing it here is the thing that makes
            // history browsing feel unsafe to use.
            const draft = state.draft ?? ''
            return { typed: draft, at: draft.length, offering: [] }
        }
        const line = recalling(history, back) ?? ''
        return {
            typed: line,
            at: line.length,
            offering: [],
            recalled: back,
            // Kept from the first press of Up and not overwritten after, or the
            // draft would become whichever history line was last looked at.
            draft: held === 0 ? typed : (state.draft ?? ''),
        }
    }

    const inserted = asOneLine(input)
    // A CHUNK THAT ENDS IN A LINE ENDING IS A LINE, AND THIS IS MEASURED.
    //
    // A terminal coalesces keys that arrive close together, so typing or
    // pasting quickly delivers something like `elp\r` as one text chunk with
    // no `return` key set at all — logged out of the real client driven
    // through a pty. Reading that as text alone drops the newline in silence:
    // the words land in the composer, nothing is sent, and a person who pasted
    // `a question⏎` sees a client that ignored their Enter.
    const finished = /[\r\n]$/u.test(input)
    if (inserted === '') {
        // A key this file has no opinion about — Escape, a function key, a
        // paste of nothing, or a chunk that was only line endings. Leave the
        // line exactly as it was; a bare newline on an empty line submits
        // nothing, for the same reason a bare Return does.
        return typed.trim() === '' || !finished
            ? { typed, at, offering: state.offering }
            : { ...EMPTY, submit: typed }
    }

    const line = typed.slice(0, at) + inserted + typed.slice(at)
    if (finished) {
        return line.trim() === '' ? { ...EMPTY } : { ...EMPTY, submit: line }
    }

    return {
        typed: line,
        at: at + inserted.length,
        // WHAT TAB OFFERED IS FORGOTTEN THE MOMENT SOMETHING IS TYPED. The list
        // answered the question "which of these did you mean"; one more
        // character is that question being answered, and leaving it up would
        // show stale advice under a line it no longer describes.
        offering: [],
    }
}

/**
 * What a keypress is to a question answered a key at a time, or nothing for a
 * key that is not one of its words.
 *
 * <p><b>Kept beside {@link pressing}, and as pure</b>, so which of Ink's keys
 * reach `logic/approval.ts` is answered by a test with no terminal. Ctrl and
 * meta chords are not text: Ctrl-C and Ctrl-D mean things about a conversation
 * and `mounting.ts` has already taken them, and anything else held with a
 * modifier is not a letter a person meant as an answer.
 */
export function strokeOf(input: string, key: Key): Stroke | undefined {
    if (key.leftArrow === true) {
        return { kind: 'left' }
    }
    if (key.rightArrow === true) {
        return { kind: 'right' }
    }
    if (key.return === true) {
        return { kind: 'enter' }
    }
    if (key.escape === true) {
        return { kind: 'escape' }
    }
    if (key.ctrl === true || key.meta === true || input === '') {
        return undefined
    }
    return { kind: 'text', text: input }
}

/**
 * What a key means to a question with options — {@link strokeOf}'s strokes, and the four a list
 * needs besides: up, down, tab and shift-tab between questions, and backspace in the words being
 * typed. Pure and kept here for {@link strokeOf}'s reason.
 */
export function questionStrokeOf(input: string, key: Key): QuestionStroke | undefined {
    if (key.upArrow === true) {
        return { kind: 'up' }
    }
    if (key.downArrow === true) {
        return { kind: 'down' }
    }
    if (key.tab === true) {
        return key.shift === true ? { kind: 'backtab' } : { kind: 'tab' }
    }
    if (key.backspace === true || key.delete === true) {
        return { kind: 'backspace' }
    }
    return strokeOf(input, key)
}

/**
 * Ctrl-O: show or hide the runs panel. Pure and kept here for {@link strokeOf}'s reason — which
 * key it is is answered by a test with no terminal.
 */
export function panelToggled(input: string, key: Key): boolean {
    return key.ctrl === true && input === 'o'
}

/** Ctrl-T: the next tool-line density — spec 2026-09-29 §4. */
export function densityToggled(input: string, key: Key): boolean {
    return key.ctrl === true && input === 't'
}

/**
 * What a key means to the `/watch` viewer, or nothing. Esc, `q` and Ctrl-C go back to the chat —
 * Ctrl-C because the viewer is only ever up between turns, where there is no run for it to stop.
 */
export function viewKeyOf(input: string, key: Key): ViewKey | undefined {
    if (key.escape === true || (key.ctrl === true && input === 'c')) {
        return 'close'
    }
    if (key.ctrl === true || key.meta === true) {
        return undefined
    }
    if (key.upArrow === true) {
        return 'up'
    }
    if (key.downArrow === true) {
        return 'down'
    }
    if (key.pageUp === true) {
        return 'pageUp'
    }
    if (key.pageDown === true) {
        return 'pageDown'
    }
    if (key.return === true) {
        return 'open'
    }
    switch (input) {
        case 'q':
            return 'close'
        case 'x':
            return 'nextFailure'
        case 'X':
            return 'prevFailure'
        case '[':
            return 'prevMark'
        case ']':
            return 'nextMark'
        case 'e':
            return 'earlier'
        case 't':
            return 'tools'
        case 'f':
            return 'follow'
        default:
            return undefined
    }
}

/** A key in the explorer — spec 2026-09-29 §5. While a search is typed, printable keys are the query. */
export function exploreKeyOf(input: string, key: Key, typing: boolean): ExploreKey | undefined {
    if (key.ctrl === true && input === 'c') {
        return 'close'
    }
    if (key.escape === true) {
        return 'escape'
    }
    if (typing) {
        if (key.return === true) {
            return 'enter'
        }
        if (key.backspace === true || key.delete === true) {
            return 'erase'
        }
        return input !== '' && key.ctrl !== true && key.meta !== true ? { typed: input } : undefined
    }
    if (key.ctrl === true || key.meta === true) {
        return undefined
    }
    if (key.upArrow === true) return 'up'
    if (key.downArrow === true) return 'down'
    if (key.pageUp === true) return 'pageUp'
    if (key.pageDown === true) return 'pageDown'
    if (key.rightArrow === true) return 'descend'
    if (key.leftArrow === true || key.backspace === true || key.delete === true) return 'ascend'
    if (key.return === true) return 'inspect'
    if (key.tab === true) return 'tab'
    switch (input) {
        case 'r': return 'refresh'
        case 'm': return 'more'
        case 'k': return 'up'
        case 'j': return 'down'
        case 'g': return 'top'
        case 'G': return 'bottom'
        case '[': return 'prevTurn'
        case ']': return 'nextTurn'
        case 'x': return 'nextFailure'
        case 'X': return 'prevFailure'
        case '/': return 'search'
        case 'n': return 'nextMatch'
        case 'N': return 'prevMatch'
        case 't': return 'fold'
        case 'v': return 'view'
        case 'f': return 'follow'
        case 'q': return 'close'
        default: return undefined
    }
}

/**
 * What a key means to the dialog that is up — one of its `keys`, a cap's unless told — or nothing.
 * Esc decides later, and so does Ctrl-C, on
 * {@link viewKeyOf}'s reasoning: the dialog is only ever up between turns, where there is no run
 * of the person's own for it to stop, and the key a person reaches for to get out must get them
 * out. Any other chord is not a letter they meant as an answer.
 */
export function capKeyOf(input: string, key: Key, keys: readonly DialogKey[] = DIALOG_KEYS.cap): DialogKey | undefined {
    if (key.escape === true || (key.ctrl === true && input === 'c')) {
        return 'later'
    }
    if (key.ctrl === true || key.meta === true) {
        return undefined
    }
    const wanted = lettered(input)
    // A LETTER ANOTHER DIALOG WOULD TAKE is nothing to this one: `a` is not "always" to a question
    // answered in words.
    return wanted === undefined || !keys.includes(wanted) ? undefined : wanted
}

/** What a letter means to some dialog, before the one that is up says whether it is one of its keys. */
function lettered(input: string): DialogKey | undefined {
    switch (input) {
        case 'y':
            return 'continue'
        case 'n':
            return 'stop'
        case 'a':
            return 'always'
        case 'w':
            return 'watch'
        case 'r':
            return 'reply'
        default:
            return undefined
    }
}
