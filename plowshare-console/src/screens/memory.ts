import { api } from '../api'
import {
    button, describeCount, el, field, input, labelled, moment, nothing, problemText, textOf,
    trouble,
} from './dom'
import type { Screen, Transport } from './screen'
import {
    GLOBAL_TIER, type MemoryView, type RecallResponse, type TocEntry,
} from './wire'

/**
 * The memory screen: what the archive knows, and one memory at a time.
 *
 * <h2>The index carries summaries, and a body is fetched when one is opened</h2>
 *
 * `TocEntry` has no body **deliberately**: the index is read whole on every
 * survey, so a body there is a body in every prompt -- Excalibur's `toc.py`
 * calls it the librarian's attention budget, and it is the reason the index
 * exists as a separate projection rather than as a list of memories. A screen
 * that fetched each body to draw the list would move that cost onto a different
 * budget and call it a feature: one request per line, and every body in memory
 * to render text that does not show any of them.
 *
 * So the list is drawn from the index alone, `GET /v1/memories/{id}` is issued
 * when a person opens one, and what comes back is kept so that opening the same
 * memory twice asks once.
 *
 * **`POST /v1/memories/recall` is the exception, and it is the server's and not
 * this screen's.** A recall answers with whole `Memory` objects, bodies
 * included, so a hit already carries what the index withholds. This screen
 * still draws the summary and opens the body on request -- the reading is the
 * same either way -- and it does not issue a fetch for a body it has already
 * been sent.
 *
 * <h2>An empty recall is not necessarily an empty archive</h2>
 *
 * `RecallResponse.unsearchable` is "the field that stops an empty answer being
 * a lie": a memory written while the embedding endpoint was down keeps
 * everything except its vector, stays active, stays in the index, and cannot be
 * reached by any question. Without that count, an archive holding the answer
 * with no vector on it answers the same shape as an archive holding nothing.
 * So an empty recall is drawn two different ways here, and only one of them
 * says *searched, and there was nothing*.
 *
 * The count is over **the tiers the recall drew on** and not over the tier
 * named in the request: `Archive.recall` searches a project's tier *and*
 * global, so a project recall's `unsearchable` spans both. The wording here
 * says "this recall" rather than "this tier" for that reason.
 *
 * <h2>Reading counts, and the read that costs one</h2>
 *
 * `GET /v1/memories/{id}` calls `Archive.read` and not `Archive.get`, so it
 * **touches the use counters**: a memory found only by id, never by recall,
 * would otherwise decay as if nothing ever asked for it. `MemoryController`
 * names this as a real tension with REST convention and resolves it in favour
 * of matching Excalibur. It is worth knowing here because it makes opening a
 * memory a write in one respect, which is another reason not to open all of
 * them to draw a list.
 */

/**
 * What each state this archive has today means, in a person's words.
 *
 * **Not a switch, and deliberately not exhaustive.** `MemoryState.wireName` is
 * written out rather than derived precisely because those spellings are a
 * contract with rows already written; a state added later must reach this
 * console without a change here, and an unknown one is rendered as itself.
 *
 * `Object.hasOwn` and not a plain lookup: the value is a string off the wire,
 * and `STATES['constructor']` on a plain object literal answers a function.
 */
const STATES: Readonly<Record<string, string>> = Object.freeze({
    active: 'active',
    superseded: 'superseded — something replaced it',
    invalidated: 'invalidated — it stopped being true',
    cold: 'cold — still true, out of the working set for disuse',
})

/** The state in words if this build knows the name, and as the name if not. */
export function describeMemoryState(state: unknown): string {
    if (typeof state !== 'string' || state === '') {
        return 'in a state the server did not name'
    }
    return Object.hasOwn(STATES, state) ? (STATES[state] as string) : state
}

/**
 * What an empty recall means when everything in the tier could be searched.
 *
 * The one place this console is entitled to say *searched, and there was
 * nothing* about the archive.
 */
export const NOTHING_NEAR =
    'Nothing this recall searched is near that question, and all of it could be searched. That is'
    + ' an answer: the archive was asked and had nothing to offer, not asked and unable to look.'

/**
 * What an empty recall means when some of the tier could not be searched.
 *
 * `unsearchable` is what makes these two cases distinguishable at all, and
 * running them together is the failure it exists to prevent: a reader told the
 * archive is empty stops asking and writes back what was already there.
 */
export function partialRecall(unsearchable: number): string {
    return `${describeCount(unsearchable, 'memory this recall drew on has no embedding',
        'memories this recall drew on have no embedding')}, and so could not be looked at however`
        + ' the question was phrased. This is not "the archive holds nothing like that"; it is'
        + ' "what could be searched held nothing like that". A re-embed pass closes the gap.'
}

export const EMPTY_INDEX =
    'This tier holds no active memories. That is an answer and not a failure — the archive was'
    + ' read and had nothing in it. Nothing is ever deleted from it, so a tier that once held'
    + ' something and now lists nothing has had it superseded, invalidated or gone cold.'

export interface MemoryOptions {
    readonly root: HTMLElement
    readonly transport?: Transport
    /** The tier to read, or null for global. */
    readonly project?: string | null
}

export function createMemory(options: MemoryOptions): Screen {
    const transport: Transport = options.transport ?? api
    let project: string | null = options.project ?? null
    let dead = false
    /** Bodies this screen has been given: fetched, or carried by a recall hit. */
    const opened = new Map<string, MemoryView>()

    const shell = el('section', 'screen memory')
    const head = el('header', 'screen-head')
    const tier = input('project', `blank for ${GLOBAL_TIER}`)
    const question = input('question', 'what are you trying to remember')
    const who = input('by', 'who is invalidating')
    const ask = button('ask', 'recall')
    const reload = button('reload', 'reload the index')
    const body = el('div', 'screen-body')
    const recalled = el('section', 'recalled')
    const listed = el('section', 'index')
    body.dataset['memories'] = ''
    recalled.dataset['recalled'] = ''
    listed.dataset['index'] = ''
    tier.value = project ?? ''

    const asking = el('div', 'recall')
    const navigate = button('navigate', 'navigate history')
    const digest = button('digest', 'build digests')
    asking.append(labelled('recall', question), ask, navigate, digest)
    navigate.addEventListener('click', () => {
        const asked=question.value.trim()
        if (asked === '') { recalled.replaceChildren(trouble('Navigation needs a question.')); return }
        const requestedProject = project
        navigate.disabled=true
        void transport.post<{level: string, ids: string[], text: string, complete: boolean}>(
            '/v1/memories/navigate', {project, question: asked})
            .then(result => { if (!dead && project === requestedProject) recalled.replaceChildren(
                el('div', 'navigation-level', `Reached ${result.level}; complete=${result.complete}; sources: ${result.ids.join(', ')}`),
                el('pre', 'body', result.text)) })
            .catch(error => { if (!dead && project === requestedProject) recalled.replaceChildren(trouble(problemText(error, 'Navigation failed.'))) })
            .finally(() => { navigate.disabled=false })
    })
    digest.addEventListener('click', () => {
        const requestedProject = project
        digest.disabled=true
        void transport.post<{id: string}>('/v1/memories/digest', {project})
            .then(result => { if (!dead && project === requestedProject) recalled.replaceChildren(el('p', 'digest-job', `Digest job ${result.id}; follow it on the jobs screen.`)) })
            .catch(error => { if (!dead && project === requestedProject) recalled.replaceChildren(trouble(problemText(error, 'Digest pass failed.'))) })
            .finally(() => { digest.disabled=false })
    })

    head.append(
        el('h2', 'screen-title', 'memory'),
        labelled('tier', tier),
        labelled('acting as', who),
        reload,
    )
    body.append(asking, recalled, listed)
    shell.append(head, body)
    options.root.replaceChildren(shell)

    function query(): string {
        return project === null ? '' : `?project=${encodeURIComponent(project)}`
    }

    reload.addEventListener('click', () => {
        project = tier.value.trim() === '' ? null : tier.value.trim()
        void load()
    })
    tier.addEventListener('change', () => {
        project = tier.value.trim() === '' ? null : tier.value.trim()
        void load()
    })
    ask.addEventListener('click', () => {
        void recall()
    })

    // --- one memory ----------------------------------------------------------

    /**
     * The full record, drawn under the summary it was opened from.
     *
     * Everything a `Memory` carries that a `TocEntry` does not, because the
     * whole reason to open one is to see what the summary line could not say.
     */
    function detail(memory: MemoryView): HTMLElement {
        const node = el('div', 'detail')
        node.dataset['detail'] = textOf(memory.id)
        node.append(field('state', describeMemoryState(memory.state)))
        node.append(field('tier', memory.home?.project === null
            || memory.home?.project === undefined
            ? GLOBAL_TIER
            : textOf(memory.home.project)))
        node.append(field('pinned', memory.pinned === true
            ? 'held in the working set regardless of decay'
            : 'not pinned'))
        // Absence is not zero, and here the two are both real: a memory recall
        // has never returned has no last use, which is not the same as one
        // returned at the epoch.
        node.append(field('recalled', describeCount(memory.uses, 'time', 'times')))
        node.append(field('last recalled', memory.lastUsed === null
            || memory.lastUsed === undefined
            ? 'never'
            : moment(memory.lastUsed)))
        const formed = memory.formed
        if (formed !== null && formed !== undefined) {
            node.append(field('formed',
                `${moment(formed.at)} by ${textOf(formed.by)} in ${textOf(formed.where)}`))
        }
        if (typeof memory.supersedes === 'string' && memory.supersedes !== '') {
            node.append(field('replaced', textOf(memory.supersedes)))
        }
        if (typeof memory.supersededBy === 'string' && memory.supersededBy !== '') {
            node.append(field('replaced by', textOf(memory.supersededBy)))
        }
        const dead = memory.invalidation
        if (dead !== null && dead !== undefined) {
            node.append(field('invalidated',
                `${moment(dead.at)} by ${textOf(dead.by)}: ${textOf(dead.reason)}`))
        }
        node.append(el('pre', 'body', textOf(memory.body)))
        node.append(invalidator(textOf(memory.id), node))
        return node
    }

    /**
     * `POST /v1/memories/{id}/invalidate`, which keeps the memory and changes
     * only its state.
     *
     * Both fields are required and neither is defaulted here: `Invalidation`'s
     * own words are that "a tombstone without a reason is just an absence, and
     * an absence teaches nobody", and the same is true of `by`. An empty string
     * passes the server's null check and is still that absence, which is why
     * the server refuses a blank and why this does not send one.
     */
    function invalidator(id: string, into: HTMLElement): HTMLElement {
        const row = el('div', 'invalidate')
        const why = input('invalidation reason', 'why it stopped being true')
        const go = button('invalidate', 'record that this stopped being true')
        go.addEventListener('click', () => {
            const reason = why.value.trim()
            const by = who.value.trim()
            if (reason === '' || by === '') {
                into.append(trouble('An invalidation needs a reason and a name. A tombstone'
                    + ' without a reason is just an absence, and an absence teaches nobody.'
                    + ' Nothing was sent.'))
                return
            }
            go.disabled = true
            void transport
                .post<MemoryView>(`/v1/memories/${encodeURIComponent(id)}/invalidate`,
                    { reason, by })
                .then((dead) => {
                    opened.set(id, dead)
                    // The index is re-read: an invalidated memory leaves it,
                    // and a screen that only patched this card would go on
                    // listing it as something the archive stands behind.
                    return load()
                })
                .catch((problem: unknown) => {
                    go.disabled = false
                    into.append(trouble(problemText(
                        problem, 'That memory could not be invalidated.')))
                })
        })
        row.append(labelled('because', why), go)
        return row
    }

    /**
     * One summary line, with the body behind a button.
     *
     * @param carried the whole memory when the answer already carried it -- a
     *     recall hit -- and `undefined` for an index line, where opening it is
     *     what fetches the body. The distinction is the whole property of this
     *     screen: an index line costs one request when somebody wants it, and
     *     never one per line to draw the list.
     */
    function line(
        id: string, summary: string, scope: string, unsearchable: boolean,
        carried?: MemoryView,
    ): HTMLElement {
        const card = el('article', 'memory')
        card.dataset['memory'] = id
        card.dataset['unsearchable'] = String(unsearchable)
        card.append(el('h3', 'memory-summary', summary))
        card.append(el('div', 'scope', scope))
        card.append(el('div', 'memory-id', id))
        if (unsearchable) {
            const flag = el('div', 'unsearchable',
                'no embedding: still active and still readable by id, and no question reaches it')
            flag.dataset['unsearchable'] = 'true'
            card.append(flag)
        }
        if (carried !== undefined) {
            opened.set(id, carried)
        }

        const open = button('open', 'open')
        open.dataset['open'] = id
        open.addEventListener('click', () => {
            const already = opened.get(id)
            if (already !== undefined) {
                // Nothing is fetched for a body this screen has already been
                // given, whether by an earlier open or by the recall that drew
                // this line.
                open.disabled = true
                card.append(detail(already))
                return
            }
            open.disabled = true
            void transport
                .get<MemoryView>(`/v1/memories/${encodeURIComponent(id)}`)
                .then((memory) => {
                    opened.set(id, memory)
                    card.append(detail(memory))
                })
                .catch((problem: unknown) => {
                    open.disabled = false
                    card.append(trouble(problemText(
                        problem, 'That memory could not be read.')))
                })
        })
        card.append(open)
        return card
    }

    // --- the two reads -------------------------------------------------------

    async function recall(): Promise<void> {
        const asked = question.value.trim()
        if (asked === '') {
            recalled.replaceChildren(trouble(
                'A recall needs a question. The server refuses a blank one, and so does this.'))
            return
        }
        ask.disabled = true
        let answer: RecallResponse
        try {
            answer = await transport.post<RecallResponse>(
                '/v1/memories/recall', { project, question: asked })
        } catch (problem) {
            recalled.replaceChildren(trouble(problemText(
                problem, 'That recall could not be run.')))
            return
        } finally {
            ask.disabled = false
        }
        const hits = Array.isArray(answer?.memories) ? answer.memories : []
        const blind = typeof answer?.unsearchable === 'number' ? answer.unsearchable : 0
        const nodes: HTMLElement[] = [el('div', 'recall-head',
            `asked: ${textOf(answer?.question)}`
            + (typeof answer?.limit === 'number' ? `, at most ${answer.limit} back` : ''))]
        if (hits.length === 0) {
            // The two empties, kept apart. Only one of them is "searched, and
            // there was nothing".
            nodes.push(blind > 0 ? trouble(partialRecall(blind)) : nothing(NOTHING_NEAR))
        } else {
            if (blind > 0) {
                const partial = el('p', 'partial', partialRecall(blind))
                partial.dataset['partial'] = String(blind)
                nodes.push(partial)
            }
            for (const hit of hits) {
                nodes.push(line(
                    textOf(hit.id), textOf(hit.summary), textOf(hit.scope), false, hit))
            }
        }
        recalled.replaceChildren(...nodes)
    }

    async function load(): Promise<void> {
        let entries: readonly TocEntry[]
        try {
            entries = (await transport.get<TocEntry[]>(`/v1/memories/index${query()}`)) ?? []
        } catch (problem) {
            listed.replaceChildren(trouble(problemText(
                problem, 'The index could not be read.')))
            return
        }
        if (entries.length === 0) {
            listed.replaceChildren(nothing(EMPTY_INDEX))
            return
        }
        listed.replaceChildren(
            el('div', 'index-head',
                `${describeCount(entries.length, 'memory', 'memories')} in this tier, by summary.`
                + ' The index carries no bodies; opening one asks for it.'),
            ...entries.map((entry) => line(
                textOf(entry.id), textOf(entry.summary), textOf(entry.scope),
                entry.unsearchable === true)),
        )
    }

    return {
        element: () => shell,
        load,
        destroy(): void {
            dead = true
        },
    }
}
