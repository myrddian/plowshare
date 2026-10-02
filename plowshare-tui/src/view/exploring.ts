import {
    current, explorerAscended, explorerDescended, explorerEarlier, explorerGrew, explorerKeyed,
    explorerOpened, isAt, levelOf, originOf, selectedRow, type ExploreView, type Land,
} from '../logic/explorer.ts'
import type { BackPage, Entry } from '../logic/session.ts'
import { stepsOf } from '../logic/trajectory.ts'
import { describeExplorer, describeExplorerListing, explorerListRoom, explorerScrollLimit } from '../logic/wording.ts'
import type { Surface } from './surface.ts'

/**
 * The explorer on a surface — spec 2026-09-29 §5-6: `/trajectory` and `/log` open it, and `/watch`
 * will too.
 *
 * <p><b>The state is `logic/explorer.ts`'s and the words are `logic/wording.ts`'s</b>; this is the
 * loop between them and the surface, the `/watch` viewer's loop in `main.ts` over different pieces:
 * a key in, the next state, maybe a read, and a draw. Reads are serialised, so a page read earlier
 * and a descent cannot land out of order, and one that lands after the explorer closed draws
 * nothing.
 */

/** What the explorer reads, as functions — the socket in `main.ts`, a fixture in the demo. */
export interface Reads {
    tail(conversation: string): Promise<BackPage | undefined>
    before(conversation: string, ordinal: number): Promise<BackPage | undefined>
    after(conversation: string, ordinal: number): Promise<readonly Entry[] | undefined>
    /** Follow this conversation's log: the server pushes when it grows. */
    follow(conversation: string): Promise<void>
}

export interface Exploring {
    readonly surface: Surface
    readonly reads: Reads
    /** Subscribe to `conversation.appended`; returns the unsubscribe. */
    readonly appended: (listener: (conversation: string, through: number) => void) => () => void
    readonly zone: string
    /** The conversation the chat follows, followed again when the explorer closes. */
    readonly restore?: string
    /** How a surface with no keys prints lines. */
    readonly print: (lines: readonly string[]) => void
}

export interface Opening {
    readonly conversation: string
    readonly label: string
    readonly view: ExploreView
    readonly land?: Land
}

/**
 * Open the explorer and run it until it closes; on a surface with no keys, print its rows once.
 *
 * @returns `'refused'` when the first read was, and nothing was shown
 */
export async function explore(ctx: Exploring, opening: Opening): Promise<'shown' | 'refused'> {
    const first = await ctx.reads.tail(opening.conversation)
    if (first === undefined) {
        return 'refused'
    }
    let ex = explorerOpened(levelOf(opening.conversation, opening.label, first, opening.view, opening.land), opening.view)
    const { explore: show, exploreKey, exploreSize } = ctx.surface
    if (show === undefined || exploreKey === undefined || exploreSize === undefined) {
        ctx.print(describeExplorerListing(ex, 100))
        return 'shown'
    }
    const children = new Map<string, { steps: number, more: boolean }>()
    /** Whether the explorer is still up: a read that lands after it closed draws and follows nothing. */
    let open = true
    /**
     * What this socket follows as far as the explorer has moved it — the chat's until it follows
     * another. Moved only inside the serial chain, so closing reads where the last follow left it.
     */
    let followed = ctx.restore
    const following = async (conversation: string): Promise<void> => {
        await ctx.reads.follow(conversation)
        followed = conversation
    }
    const draw = (): void => {
        if (open) {
            show.call(ctx.surface, describeExplorer(ex, exploreSize.call(ctx.surface), { children, zone: ctx.zone }),
                ex.search?.typing === true)
        }
    }
    let reading: Promise<void> = Promise.resolve()
    const serially = (work: () => Promise<void>): Promise<void> => {
        reading = reading.then(work).catch(() => undefined)
        return reading
    }
    // A DELEGATION UNDER THE CURSOR IS READ ONCE, for its step count in the inspector; a failed
    // read leaves the count out and may be tried again.
    const counting = (): void => {
        const row = selectedRow(ex)
        const opened = row?.kind === 'step' && row.step.kind === 'call' ? row.step.opened : undefined
        if (opened === undefined || children.has(opened.conversation)) {
            return
        }
        children.set(opened.conversation, { steps: 0, more: true })
        void serially(async () => {
            const page = await ctx.reads.tail(opened.conversation)
            if (page === undefined) {
                children.delete(opened.conversation)
                return
            }
            children.set(opened.conversation, { steps: stepsOf(page.entries).length, more: page.more })
            draw()
        })
    }
    const stop = ctx.appended((conversation, through) => {
        void serially(async () => {
            const level = ex.levels.find((each) => each.conversation === conversation)
            if (level === undefined || through <= level.through) {
                return
            }
            const fresh = await ctx.reads.after(conversation, level.through)
            if (fresh !== undefined) {
                ex = explorerGrew(ex, conversation, fresh, through)
                draw()
            }
        })
    })
    // A LOG OTHER THAN THE CHAT'S IS FOLLOWED WHILE IT IS OPEN, so it grows live as the chat's
    // does; closing puts the chat's follow back.
    if (opening.conversation !== ctx.restore) {
        await serially(() => following(opening.conversation))
    }
    const unresized = ctx.surface.onResize?.call(ctx.surface, draw) ?? ((): void => undefined)
    counting()
    draw()
    try {
        for (;;) {
            const key = await exploreKey.call(ctx.surface)
            if (key === undefined) {
                break
            }
            const size = exploreSize.call(ctx.surface)
            const next = explorerKeyed(ex, key, explorerListRoom(size), explorerScrollLimit(ex, size, { children, zone: ctx.zone }))
            ex = next.explorer
            if (next.then === 'close') {
                break
            }
            if (next.then === 'earlier') {
                // THE PAGE BELONGS TO THE LEVEL IT WAS ASKED FOR, named now: by the time it lands
                // ← may have put another level on screen, and it must not take the page.
                const conversation = current(ex).conversation
                void serially(async () => {
                    const oldest = ex.levels.find((each) => each.conversation === conversation)?.oldest
                    if (oldest === undefined) {
                        return
                    }
                    const page = await ctx.reads.before(conversation, oldest)
                    if (page !== undefined) {
                        ex = explorerEarlier(ex, conversation, page)
                        draw()
                    }
                })
            }
            if (next.then === 'descend') {
                const row = selectedRow(ex)
                const opened = row?.kind === 'step' && row.step.kind === 'call' ? row.step.opened : undefined
                if (opened !== undefined) {
                    const from = originOf(ex)
                    void serially(async () => {
                        // A DESCENT WHOSE ORIGIN IS NO LONGER ON SCREEN IS DROPPED — ← or another →
                        // landed first — and so is one that lands after the explorer closed, follow
                        // and all: following the child now would take the chat's follow away from it.
                        if (!open || !isAt(ex, from)) {
                            return
                        }
                        const page = await ctx.reads.tail(opened.conversation)
                        if (page === undefined || !open) {
                            return
                        }
                        const descended = explorerDescended(ex, levelOf(opened.conversation, opened.agent, page, ex.view), from)
                        if (descended === ex) {
                            return
                        }
                        ex = descended
                        await following(opened.conversation)
                        draw()
                    })
                }
            }
            if (next.then === 'ascend') {
                ex = explorerAscended(ex)
                const back = current(ex).conversation
                void serially(() => following(back))
            }
            counting()
            draw()
        }
    } finally {
        open = false
        stop()
        unresized()
        // DOWN FIRST, so a frozen explorer is not left up while the reads before the restore drain.
        show.call(ctx.surface, undefined)
        // DECIDED IN THE CHAIN, after every read and follow queued before it has landed: a
        // descent still in flight is dropped there, and an ascent's follow has moved `followed`.
        const restore = ctx.restore
        await serially(async () => {
            if (restore !== undefined && followed !== restore) {
                await ctx.reads.follow(restore)
            }
        })
    }
    return 'shown'
}
