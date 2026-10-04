import { performance } from 'node:perf_hooks'
import process from 'node:process'

import { render } from 'ink'
import type { Key } from 'ink'

import { blank } from '../../logic/screen.ts'
import type { Entry, Screen, Working } from '../../logic/screen.ts'
import { DEFAULT_LOOK } from '../look.ts'
import type { Look } from '../look.ts'
import type { Surface } from '../surface.ts'
import type { Offer, Vocabulary } from '../../logic/menu.ts'
import { ANSWER_COMMAND, COMMANDS, describeCommand, describeWaitingCount, waitingOffers } from '../../logic/wording.ts'
import type { Waiting } from '../../logic/session.ts'
import type { Panel, Viewed, ViewKey } from '../../logic/record.ts'
import type { ExploreSize, Standing } from '../../logic/wording.ts'
import type { ExploreKey } from '../../logic/explorer.ts'
import type { Tinted } from '../../logic/tints.ts'
import { app, dialogRoom as roomForDialog, viewerFitted, viewerRoom } from './app.ts'
import {
    capKeyOf, densityToggled, exploreKeyOf, panelToggled, pressing, questionStrokeOf, strokeOf, viewKeyOf,
} from './composer.ts'
import type { Stroke } from '../../logic/approval.ts'
import type { QuestionStroke } from '../../logic/questions.ts'
import { DIALOG_KEYS, type DialogKey } from '../../logic/caps.ts'
import type { Composing } from './composer.ts'

/**
 * `render` on one side, {@link Surface} on the other.
 *
 * <h2>The screen lives outside React, deliberately</h2>
 *
 * <p>`converse` owns what is on the screen — it decides that a refusal happened
 * and that a run is going — and it is not a React component and must never
 * become one. So the {@link Screen} is held here, plainly, and every change
 * re-renders through Ink's own `rerender`.
 *
 * <p><b>And so is the composer, which took a measured bug to learn.</b> It
 * started as `useState` inside the component, where `useInput`'s callback
 * closes over the render it was registered in — so two keys pressed in one
 * render cycle both read the same starting composer and the second overwrote
 * the first. Driven through a pty against a live server,
 * `Name one thing…` arrived as `/boe one thing…` and Tab completed nothing.
 * React is left with exactly one piece of state now: which spinner frame is
 * up, which nothing outside can race because nothing outside drives it.
 *
 * <p><b>That is also what makes the console's job small later.</b> Everything
 * this file does with a `Screen` is append to it and hand it to a renderer; a
 * page would do the same with a different renderer, and neither needs anything
 * from the other.
 *
 * <h2>`exitOnCtrlC: false`, which is not a detail</h2>
 *
 * <p>Ink's default is to exit the process on Ctrl-C. That is exactly the
 * behaviour this client spent a task removing: the run goes on server-side
 * whether or not anybody is listening, so the key everybody presses to stop
 * something was the key that abandoned it — spending a budget nobody was
 * watching to produce an answer nobody would read. The press has to reach
 * {@link Surface.onInterrupt}, where `converse` turns the first one into a
 * `job.cancel` and the second into a way out.
 */

export interface Terminal {
    readonly colour: boolean
    /** Per command, what the menu offers after it — `/theme` and the themes. */
    readonly arguments?: Readonly<Record<string, () => readonly Offer[]>>
    /** What to draw with when there is colour. The default look otherwise. */
    readonly look?: Look
    readonly stdout?: NodeJS.WriteStream
    readonly stdin?: NodeJS.ReadStream
    /**
     * How long a cap dialog just drawn lets a key through to the composer rather than read it as
     * an answer — {@link DIALOG_GRACE} unless told otherwise; the pty harness stretches it.
     */
    readonly dialogGrace?: number
}

/**
 * How long, in milliseconds, a cap dialog just drawn takes its keys as typing and not as answers.
 *
 * <p><b>It comes up on its own, between two keys of whatever the person was about to type.</b>
 * Held while the composer holds text is not enough: at an empty prompt the first letter of "now…",
 * "and…" or "what…" lands on a dialog the person has not yet seen, and would stop the run, set
 * auto-continue or open a viewer. A key inside the window puts the dialog back down and goes to the
 * composer, where the hold keeps it down until the line is sent or cleared. Esc, Ctrl-C, Ctrl-D and
 * Ctrl-O are never typing, so they are never held back.
 */
export const DIALOG_GRACE = 400

export function terminal(options: Terminal): Surface {
    /** What is on the screen. Not React state — see the header. */
    let screen: Screen = blank('')
    /**
     * What is half-typed. Out here for the same reason, and for a sharper one.
     *
     * <p>It was `useState` inside the component, and two keys pressed within a
     * single render cycle both read the state of the render the handler was
     * registered in — so the second overwrote the first and typing at speed
     * dropped characters. Measured through a pty against a live server:
     * `Name one thing…` arrived as `/boe one thing…`, and Tab completed against
     * a line that was no longer there. There is one composer now and the
     * keypress handler is a pure call on it.
     */
    let composing: Composing = screen.composer
    /** Whether what is being typed is drawn as dots. See {@link Surface.asked}. */
    let quietly = false
    /** How many inbox items are unread. Held the way `working` is. */
    let unread = 0
    /** The runs waiting on a person. Held the way `unread` is; all but the stalled ones are
     *  offered under `/answer ` ({@link waitingOffers}). */
    let asking: readonly Waiting[] = []
    /** Who is being talked to and how full it is. Held the way `unread` is. */
    let standing: Standing | undefined
    /** The runs panel's lines, or none. Held the way `unread` is. */
    let panelLines: readonly Tinted[] | undefined
    /** Whether Ctrl-O hid a panel that is there. */
    let panelHidden = false
    /** The `/watch` viewer, or none. */
    let viewing: Viewed | undefined
    /**
     * How many entries were on the screen when the viewer came up. Whatever is shown after that —
     * a catch-up of the log, a run that started asking — is kept in `screen` and written to the
     * transcript when the viewer goes away: `<Static>` writes above the redrawn region, which
     * the viewer fills, so it would land a row at a time over the viewer's head and scroll off
     * unread.
     */
    let viewedAt = 0
    /**
     * How many of the transcript's items — the banner, then entries — `<Static>` has written and
     * {@link screen} has let go. An entry is let go the moment it is written: the terminal holds
     * it from then on, and a session left open for hours would otherwise hold every entry it ever
     * showed. See `app.ts`'s `transcriptOf`.
     */
    let written = 0
    /** Whoever is waiting for the viewer's next key; a key pressed with nobody waiting is dropped. */
    let viewAsker: ((key: ViewKey | undefined) => void) | undefined
    /**
     * The explorer's lines, or none — spec 2026-09-29 §5. Up the way the viewer is, in place of the
     * composer, and holding what is shown meanwhile from the same {@link viewedAt}.
     */
    let exploring: readonly Tinted[] | undefined
    /** Whether a search is being typed in the explorer, when printable keys are its query. */
    let exploreTyping = false
    /** Whoever is waiting for the explorer's next key; a key pressed with nobody waiting is dropped. */
    let exploreAsker: ((key: ExploreKey | undefined) => void) | undefined
    /** Whoever wants to hear the terminal changed size. See {@link Surface.onResize}. */
    const resizeListeners = new Set<() => void>()
    /** What the surface draws with. Replaced by {@link Surface.restyle}. */
    let look: Look = options.look ?? DEFAULT_LOOK
    let declared: readonly string[] = []
    let details: Readonly<Record<string, string>> = {}
    let serverCommands: readonly { readonly name: string; readonly detail: string }[] = []
    /** What the command menu offers, rebuilt from the latest roster. */
    const vocabulary = (): Vocabulary => ({
        commands: [...COMMANDS.map((name) => ({ name, detail: describeCommand(name) })), ...serverCommands],
        names: declared.map((name) => {
            const detail = details[name]
            return detail === undefined || detail === '' ? { name } : { name, detail }
        }),
        arguments: { ...options.arguments, [ANSWER_COMMAND]: () => waitingOffers(asking) },
        openEnded: [ANSWER_COMMAND],
    })
    /** What has been said, oldest first, for the composer's history. */
    const history: string[] = []
    let interrupted: (() => void) | undefined
    /** Whoever wants to hear of Ctrl-T. See {@link Surface.density}. */
    let densityAsked: () => void = () => undefined
    /** Lines entered faster than they were asked for. */
    const typed: string[] = []
    /** Asks made before there was a line to answer them with. */
    const waiting: ((line: string | undefined) => void)[] = []
    /**
     * A question answered a key at a time, or nothing. See {@link Surface.choosing}.
     *
     * <p><b>One at a time, and never queued.</b> A key pressed before anybody
     * asked is not an answer to a question it was pressed before, so keys only
     * reach this while it is set — which is also why lines typed ahead are left
     * in `typed` for the prompt rather than read here.
     */
    let choosing: { readonly lines: readonly string[]; readonly answer: (stroke: Stroke | undefined) => void }
        | undefined
    /**
     * A cap question, or nothing. See {@link Surface.capDialog}.
     *
     * <p><b>Held, not drawn, while the composer holds something</b> — `shown` says which. It takes
     * `y`, `n`, `a` and `w` as answers, and a person halfway through "you know what, stop" would
     * otherwise answer it with the next letter they typed after it appeared. It comes up once the
     * composer is empty, by editing or at once; a line sent instead is not its answer, and
     * `converse` takes it down and asks again at the next prompt.
     */
    let dialog: {
        readonly lines: readonly string[]
        /**
         * What a key means to it, or nothing: one of a question's keys (see {@link
         * Surface.capDialog}), an approval prompt's stroke (see {@link Surface.approvalDialog}), or
         * a question with options' stroke (see {@link Surface.questionDialog}).
         */
        readonly read: (input: string, key: Key) => DialogKey | QuestionStroke | undefined
        /** Whether what a key meant is esc — later — which no grace holds back. */
        readonly later: (meant: DialogKey | QuestionStroke | undefined) => boolean
        readonly answer: (meant: DialogKey | QuestionStroke | undefined) => void
        shown: boolean
        /** When it was last drawn, for {@link DIALOG_GRACE}. */
        shownAt: number
    } | undefined
    const grace = options.dialogGrace ?? DIALOG_GRACE
    let ended = false

    const submitted = (line: string): void => {
        // NOT INTO THE HISTORY WHEN IT WAS NOT SHOWN. The up arrow would
        // otherwise hand somebody's password back to them in a composer that
        // is echoing again by then.
        if (!quietly) {
            history.push(line)
        }
        quietly = false
        const asker = waiting.shift()
        if (asker === undefined) {
            typed.push(line)
        } else {
            asker(line)
        }
    }

    const end = (): void => {
        if (ended) {
            return
        }
        ended = true
        while (waiting.length > 0) {
            waiting.shift()?.(undefined)
        }
        const question = choosing
        choosing = undefined
        question?.answer(undefined)
        const capped = dialog
        dialog = undefined
        capped?.answer(undefined)
        const watcher = viewAsker
        viewAsker = undefined
        watcher?.(undefined)
        const explorer = exploreAsker
        exploreAsker = undefined
        explorer?.(undefined)
        out.off('resize', resized)
        // WHAT THE VIEWER HELD, WRITTEN BEFORE LEAVING: a question that landed while it was up
        // would otherwise go with it, since no redraw follows an unmount. The explorer's the same.
        if (viewing !== undefined || exploring !== undefined) {
            viewing = undefined
            exploring = undefined
            instance.rerender(tree())
        }
        instance.unmount()
    }

    /**
     * What a keypress means.
     *
     * <p>Ctrl-C and Ctrl-D first, because they are facts about a conversation
     * rather than about a line — the first press of Ctrl-C asks a run to stop
     * and the second leaves, and both of those live in `converse`. Everything
     * else is {@link pressing}, which is pure and tested without a terminal.
     * The `/watch` viewer comes before both: it is only up between turns, where
     * Ctrl-C has no run to stop and is taken as a way back to the chat.
     */
    const pressed = (input: string, key: Key): void => {
        // THE EXPLORER TAKES EVERY KEY WHILE IT IS UP but Ctrl-D, on the viewer's reasoning below.
        if (exploring !== undefined && !(key.ctrl === true && input === 'd')) {
            const wanted = exploreKeyOf(input, key, exploreTyping)
            const asker = exploreAsker
            if (wanted !== undefined && asker !== undefined) {
                exploreAsker = undefined
                asker(wanted)
            }
            return
        }
        // THE VIEWER TAKES EVERY KEY WHILE IT IS UP but Ctrl-D, which still leaves — the
        // meaningful ones as a move (Ctrl-C among them, as a way back) and the rest as nothing, on
        // a question's reasoning: a letter that fell through would be typing nobody can see.
        if (viewing !== undefined && !(key.ctrl === true && input === 'd')) {
            const wanted = viewKeyOf(input, key)
            const asker = viewAsker
            if (wanted !== undefined && asker !== undefined) {
                viewAsker = undefined
                asker(wanted)
            }
            return
        }
        // THE CAP DIALOG IS MODAL WHILE IT IS UP: it takes every key but Ctrl-D, which still leaves,
        // and Ctrl-O, which hides the runs a person may want out of the way to read it — its own as
        // an answer and the rest as nothing, on a question's reasoning.
        if (dialog?.shown === true && !(key.ctrl === true && (input === 'd' || input === 'o'))) {
            const wanted = dialog.read(input, key)
            // TYPING THAT OUTRAN IT (see DIALOG_GRACE): the dialog goes back down, held, and the
            // key goes on to the composer below.
            if (!dialog.later(wanted) && Date.now() - dialog.shownAt < grace) {
                dialog.shown = false
            } else {
                if (wanted !== undefined) {
                    const { answer } = dialog
                    dialog = undefined
                    redraw()
                    answer(wanted)
                }
                return
            }
        }
        if (key.ctrl === true && input === 'c') {
            composing = { typed: '', at: 0, offering: [] }
            revealDialog()
            redraw()
            if (interrupted === undefined) {
                end()
                return
            }
            interrupted()
            return
        }
        if (key.ctrl === true && input === 'd' && composing.typed === '') {
            end()
            return
        }
        // CTRL-O, BEFORE A QUESTION TAKES EVERY KEY: a panel shown or hidden changes nothing a
        // question is waiting on, and a person answering one may want the runs out of the way.
        if (panelToggled(input, key)) {
            if (panelLines !== undefined) {
                panelHidden = !panelHidden
                redraw()
            }
            return
        }
        // CTRL-T, FOR THE SAME REASON: how tool lines are drawn changes nothing a question waits on.
        if (densityToggled(input, key)) {
            densityAsked()
            return
        }
        // A QUESTION TAKES EVERY KEY WHILE IT IS UP, the meaningful ones as an
        // answer and the rest as nothing: a letter that fell through into the
        // composer would be half a sentence nobody meant to type.
        if (choosing !== undefined) {
            const stroke = strokeOf(input, key)
            if (stroke !== undefined) {
                const { answer } = choosing
                choosing = undefined
                redraw()
                answer(stroke)
            }
            return
        }
        // Kept apart: `Pressed` carries the line to send and `Composing` does
        // not, which is what stops a stale `submit` from a previous keypress
        // being read as a new one.
        const next = pressing(composing, input, key, declared, history, vocabulary())
        composing = next
        // A LINE SENT IS NOT A LINE CLEARED: the dialog stays held, and `converse`, handed the
        // line, takes it down — drawn for the moment between, it would flash over a turn starting.
        if (next.submit === undefined) {
            revealDialog()
        }
        redraw()
        if (next.submit !== undefined) {
            submitted(next.submit)
        }
    }

    /** A held cap dialog comes up once the composer is empty. See {@link dialog}. */
    const revealDialog = (): void => {
        if (dialog !== undefined && !dialog.shown && composing.typed === '') {
            dialog.shown = true
            dialog.shownAt = Date.now()
        }
    }

    const tree = (): ReturnType<typeof app> => app({
        screen: viewing === undefined && exploring === undefined
            ? screen : { ...screen, entries: screen.entries.slice(0, viewedAt) },
        written,
        composing,
        quietly,
        unread,
        ...(asking.length === 0 ? {} : { waiting: describeWaitingCount(asking.length) ?? '' }),
        ...(standing === undefined ? {} : { standing }),
        ...(choosing === undefined ? {} : { choosing: choosing.lines }),
        ...(dialog?.shown === true ? { dialog: dialog.lines } : {}),
        ...(panelLines === undefined ? {} : panelHidden ? { panelHidden: true } : { panel: panelLines }),
        ...(viewing === undefined ? {} : { viewing }),
        ...(exploring === undefined ? {} : { exploring }),
        colour: options.colour,
        vocabulary: vocabulary(),
        look,
        onKey: pressed,
    })

    // A RESIZE DRAWS AGAIN, and before Ink's own handler does, which only lays the last tree out
    // anew: the panel and the viewer are fitted to the rows there were when it was drawn, and a
    // terminal made shorter under them would be cleared and written whole every frame after. A
    // shrink under the viewer still clears once — the frame already up is taller than the rows
    // left — and only once.
    const out = options.stdout ?? process.stdout
    const resized = (): void => {
        // THE LISTENERS FIRST: a region worded for the old size is worded again before it is drawn.
        for (const listener of resizeListeners) {
            listener()
        }
        redraw()
    }
    out.on('resize', resized)

    const instance = render(tree(), {
        ...(options.stdout === undefined ? {} : { stdout: options.stdout }),
        ...(options.stdin === undefined ? {} : { stdin: options.stdin }),
        // See the header. The press belongs to the conversation, not to Ink.
        exitOnCtrlC: false,
        // Ink's console patching rewrites `console.log` to route through the
        // renderer. Nothing here logs, and a client that quietly changed the
        // meaning of `console` for anything it imports would be surprising.
        patchConsole: false,
        // REACT'S MEASURES, LET GO AS EACH FRAME IS WRITTEN. Its development build — the one this
        // client runs, `NODE_ENV` being unset — puts a `performance.measure` in the timeline for
        // every component it renders, and Node keeps every one until somebody clears it. A
        // `/watch` frame is about a hundred of them, ~135 KB of heap, measured 2026-09-30; a
        // client left in the viewer for eleven hours died at 4 GB, which is ~30,000 such frames
        // (`mounting.test.ts`). Nothing in this client measures anything of its own, so the whole
        // timeline goes.
        onRender: () => {
            performance.clearMeasures()
        },
    })

    /** The entries `<Static>` has just written, let go — unless something is drawn in their place
     *  and they are not written yet. */
    const letGo = (): void => {
        if (viewing === undefined && exploring === undefined) {
            written = Math.max(1, written) + screen.entries.length
            if (screen.entries.length > 0) {
                screen = { ...screen, entries: [] }
            }
        }
    }
    letGo()

    const redraw = (): void => {
        if (!ended) {
            instance.rerender(tree())
            letGo()
        }
    }

    return {
        show(entry: Entry): void {
            screen = { ...screen, entries: [...screen.entries, entry] }
            redraw()
        },

        working(state: Working | undefined): void {
            // Spread conditionally: `working: undefined` is a PRESENT key under
            // `exactOptionalPropertyTypes`, and `Screen.working` is optional
            // precisely so that its absence is what "nothing is running" looks
            // like. Assigning undefined would type-error, which is the rule
            // doing its job rather than getting in the way.
            const { working: _was, ...rest } = screen
            screen = state === undefined ? rest : { ...rest, working: state }
            redraw()
        },

        completing(names: readonly string[], described?: Readonly<Record<string, string>>, commands: readonly { readonly name: string; readonly detail: string }[] = []): void {
            declared = names
            details = described ?? {}
            serverCommands = commands
            redraw()
        },

        unread(count: number): void {
            unread = count
            redraw()
        },

        waiting(runs: readonly Waiting[]): void {
            asking = runs
            redraw()
        },

        onInterrupt(listener: () => void): void {
            interrupted = listener
        },

        density(listener: () => void): void {
            densityAsked = listener
        },

        columns(): number {
            return out.columns ?? 80
        },

        asked(hidden = false): Promise<string | undefined> {
            quietly = hidden
            redraw()
            // A LINE TYPED AHEAD IS NEVER A PASSWORD. Whatever is already in
            // the queue was typed while the composer was still echoing, so it
            // was typed in answer to something else -- handing it over here
            // would answer a password prompt with a question somebody asked
            // before it existed, and put it on the wire as a credential.
            const ready = hidden ? undefined : typed.shift()
            if (ready !== undefined) {
                return Promise.resolve(ready)
            }
            if (ended) {
                return Promise.resolve(undefined)
            }
            return new Promise((answer) => {
                waiting.push(answer)
            })
        },

        choosing(lines: readonly string[]): Promise<Stroke | undefined> {
            if (ended) {
                return Promise.resolve(undefined)
            }
            return new Promise((answer) => {
                choosing = { lines, answer }
                redraw()
            })
        },

        capDialog(lines: readonly string[], keys: readonly DialogKey[] = DIALOG_KEYS.cap): Promise<DialogKey | undefined> {
            if (ended) {
                return Promise.resolve(undefined)
            }
            // ONE AT A TIME: a second would strand whoever awaits the first. `converse` never
            // asks twice, and a surface that trusted that silently would be the next bug.
            const was = dialog
            return new Promise((answer) => {
                dialog = {
                    lines, read: (input, key) => capKeyOf(input, key, keys),
                    later: (meant) => meant === 'later', answer: (meant) => answer(meant as DialogKey | undefined),
                    shown: composing.typed === '', shownAt: Date.now(),
                }
                redraw()
                was?.answer(undefined)
            })
        },

        approvalDialog(lines: readonly string[], again = false): Promise<Stroke | undefined> {
            if (ended) {
                return Promise.resolve(undefined)
            }
            // THE SAME MODAL, READ AS THE APPROVAL PROMPT'S STROKES, Ctrl-C being esc as it is to a
            // question. Redrawn after one of its own keys, it is up at once and holds no grace: the
            // person is answering it, and a second arrow pressed quickly is not typing that outran it.
            const was = dialog
            return new Promise((answer) => {
                dialog = {
                    lines,
                    read: (input, key) => key.ctrl === true && input === 'c' ? { kind: 'escape' } : strokeOf(input, key),
                    later: (meant) => typeof meant === 'object' && meant.kind === 'escape',
                    answer: (meant) => answer(meant as Stroke | undefined),
                    shown: again || composing.typed === '', shownAt: again ? 0 : Date.now(),
                }
                redraw()
                was?.answer(undefined)
            })
        },

        questionDialog(lines: readonly string[], again = false): Promise<QuestionStroke | undefined> {
            if (ended) {
                return Promise.resolve(undefined)
            }
            // THE SAME MODAL, READ AS A LIST'S STROKES, Ctrl-C being esc as it is to every question.
            // Redrawn after one of its own keys it is up at once, as the approval prompt is.
            const was = dialog
            return new Promise((answer) => {
                dialog = {
                    lines,
                    read: (input, key) => key.ctrl === true && input === 'c' ? { kind: 'escape' } : questionStrokeOf(input, key),
                    later: (meant) => typeof meant === 'object' && meant.kind === 'escape',
                    answer: (meant) => answer(meant as QuestionStroke | undefined),
                    shown: again || composing.typed === '', shownAt: again ? 0 : Date.now(),
                }
                redraw()
                was?.answer(undefined)
            })
        },

        prefill(text: string): void {
            // AS THOUGH TYPED, THE CURSOR AT ITS END: the person finishes the line and sends it.
            // A dialog queued behind the one that asked for this stays held while it is there.
            composing = { typed: text, at: text.length, offering: [] }
            redraw()
        },

        dialogRoom(): number {
            return roomForDialog(out.rows ?? 24)
        },

        closeDialog(): void {
            const open = dialog
            dialog = undefined
            redraw()
            open?.answer(undefined)
        },

        close(): void {
            end()
        },

        status(next: Standing | undefined): void {
            standing = next
            redraw()
        },

        restyle(next: Look): void {
            look = next
            redraw()
        },

        view(next: Viewed | undefined): void {
            if (viewing === undefined && next !== undefined) {
                viewedAt = screen.entries.length
            }
            viewing = next
            redraw()
        },

        viewRoom(): number {
            return viewing === undefined ? 1 : viewerRoom(viewing, out.rows ?? 24, out.columns ?? 80)
        },

        viewBody(viewed: Viewed): number {
            return viewerFitted(viewed, out.rows ?? 24, out.columns ?? 80).room
        },

        viewKey(): Promise<ViewKey | undefined> {
            if (ended) {
                return Promise.resolve(undefined)
            }
            return new Promise((answer) => {
                viewAsker = answer
            })
        },

        explore(next: readonly Tinted[] | undefined, typing = false): void {
            if (exploring === undefined && viewing === undefined && next !== undefined) {
                viewedAt = screen.entries.length
            }
            exploring = next
            exploreTyping = typing
            redraw()
        },

        exploreKey(): Promise<ExploreKey | undefined> {
            if (ended) {
                return Promise.resolve(undefined)
            }
            return new Promise((answer) => {
                exploreAsker = answer
            })
        },

        exploreSize(): ExploreSize {
            return { rows: out.rows ?? 24, columns: out.columns ?? 80 }
        },

        onResize(listener: () => void): () => void {
            resizeListeners.add(listener)
            return () => {
                resizeListeners.delete(listener)
            }
        },

        panel(next: Panel | undefined): void {
            panelLines = next?.lines
            // A panel that went away and came back is a new one: shown, whatever the last was.
            if (next === undefined) {
                panelHidden = false
            }
            redraw()
        },
    }
}
