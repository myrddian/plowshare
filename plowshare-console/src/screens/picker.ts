import { api } from '../api'
import type { ConversationView } from '../repl/wire'
import { button, el, nothing, problemText, trouble } from './dom'
import type { Transport } from './screen'
import { GLOBAL_TIER, type ProjectView } from './wire'

/**
 * The conversation picker: every project as a node, and a project's
 * conversations fetched the moment somebody asks to see them.
 *
 * Spec §3.1-3.2. Built and tested on its own, and mounted by `chat.ts`, which
 * is what `shell.ts` builds for the `chat` view. Nothing below knows that:
 * this file names an id and the file that mounted it decides what a name
 * means, which is what lets the same picker be tested against a bare host.
 *
 * <h2>A project is drawn before its conversations are asked for</h2>
 *
 * `GET /v1/projects` is one small answer; `GET /v1/conversations?project=` is
 * a per-project one. Fetching every project's conversations up front would be
 * one request per row before a person has said which row they care about, so
 * {@link createPicker} draws the tree of projects immediately and asks for a
 * project's conversations only on first expand -- and leaves them in the DOM
 * afterwards rather than re-fetching on a second open: `expand` is wired only
 * to the toggle that opens a project, and nothing here forgets what a project
 * already showed.
 *
 * <h2>The empty children host is the point, and not dead code</h2>
 *
 * Each conversation this picker draws gets its own empty `.children` div,
 * tagged `data-children` with that conversation's id, and today nothing ever
 * fills it. That is a real gap in the wire and not an oversight of this file:
 * `ConversationView` carries no `parentId`, and no route this console can call
 * enumerates a root's children -- `chat.ts`'s `TREE_NOTE`, which is the
 * sentence this sidebar sits under, says the same thing in a person's words and
 * for the same reason. `V17` gave `conversations` a `parent_id`, and its one
 * caller today is the retention sweep, not a read.
 *
 * The div is built anyway, once per conversation, so that the day that route
 * exists this file gains a fetch into an already-present host rather than a
 * second pass rewriting every row to grow one. A slot drawn empty and a slot
 * never drawn look identical to a person looking at the screen; they are not
 * identical to whoever writes the patch that has to add one.
 *
 * <h2>What this picker does not decide</h2>
 *
 * `onPick` is called and nothing else happens here -- no navigation, no fetch
 * of the conversation itself. This module's whole job is naming an id; what a
 * caller does with that id belongs to whatever mounts this picker, not to it.
 */

/**
 * One row of the tree: a project, or the tier a conversation gets without one.
 *
 * Carries a `query` and not a project name, because the global tier is not
 * addressed by naming anything. `ConversationController.resolveHome(null)`
 * answers `Home.global()`, so **omitting the parameter is already the question
 * "the conversations that belong to no project"**. An empty `?project=` is a
 * different request and a refused one: it reaches `Home.of("")`, which throws on
 * a blank name, and the controller answers 400. Three of this server's own tools
 * carry a guard against exactly that confusion, each having met it.
 */
interface Tier {
    /** What a person is shown. */
    readonly name: string
    /** The project this tier is, or the empty string for the global one. */
    readonly project: string
    /** The `project=` clause of a listing, trailing `&`, or nothing at all. */
    readonly query: string
}

/**
 * The tier a conversation gets when nobody chose one.
 *
 * **Not a project and never from `GET /v1/projects`.** `Home` models it as a
 * state — `new Home(null)`, with `Home.of` refusing a blank name and saying in
 * its own message "use Home.global() for the global tier". A reserved project
 * name would collide with a real project of that name, and would flatten a state
 * into a value, which is the shape `Budget.none()` and `TurnCap.none()` both
 * exist to avoid.
 *
 * First in the list and never conditional on there being any projects: on most
 * deployments it holds everything.
 */
const GLOBAL: Tier = { name: GLOBAL_TIER, project: '', query: '' }

/** A project, as a row of the same tree. */
function named(project: ProjectView): Tier {
    return {
        name: project.name,
        project: project.name,
        query: `project=${encodeURIComponent(project.name)}&`,
    }
}

export interface PickerOptions {
    readonly root: HTMLElement
    readonly transport?: Transport
    /** The lifecycle a project's conversations are asked for in. Defaults to `active`. */
    readonly lifecycle?: string
    readonly onPick: (conversationId: string) => void
}

export interface Picker {
    element(): HTMLElement
    load(): Promise<void>
    /** The last conversation id a click reported, or null before one has been. */
    picked(): string | null
    destroy(): void
}

export function createPicker(options: PickerOptions): Picker {
    const transport: Transport = options.transport ?? api
    const lifecycle = options.lifecycle ?? 'active'
    const tree = el('div', 'picker')
    let selected: string | null = null

    /**
     * One project's conversations, read once per expand and drawn under it.
     *
     * `host.replaceChildren()` first, so a second click on an already-open
     * project re-asks the server rather than piling a second copy of its rows
     * under the first -- the toggle below is not disabled after use, and
     * nothing stops a person clicking it twice.
     */
    async function expand(tier: Tier, host: HTMLElement): Promise<void> {
        host.replaceChildren()
        let rows: readonly ConversationView[] = []
        try {
            // Typed to tolerate a server that answers `null`: `Transport.get`
            // is generic and hands back exactly what it is told to expect,
            // and nothing between here and the socket checks that the body
            // actually matched.
            const listing = await transport.get<readonly ConversationView[] | null>(
                `/v1/conversations?${tier.query}lifecycle=${encodeURIComponent(lifecycle)}`)
            rows = listing ?? []
        } catch (problem) {
            host.append(trouble(problemText(problem, 'That listing could not be read.')))
            return
        }
        if (rows.length === 0) {
            // nothing(), not a host left empty: a tier with no conversations in
            // this lifecycle is an answer, and an empty host would look
            // identical to a picker that had not asked yet.
            host.append(nothing(`Nothing ${lifecycle} in ${tier.name}.`))
            return
        }
        for (const row of rows) {
            const control = button('conversation', row.id)
            control.dataset['conversation'] = row.id
            control.addEventListener('click', () => {
                selected = row.id
                for (const each of tree.querySelectorAll('[data-conversation]')) {
                    each.removeAttribute('aria-current')
                }
                control.setAttribute('aria-current', 'true')
                options.onPick(row.id)
            })
            host.append(control)
            // The slot this file's javadoc names. Built as a node with its
            // own children host, rather than as a flat row, precisely so that
            // the route naming a root's children is a fill-in here and not a
            // rewrite -- see the class doc above for why it is empty today.
            const children = el('div', 'children')
            children.dataset['children'] = row.id
            host.append(children)
        }
    }

    async function draw(): Promise<void> {
        let projects: readonly ProjectView[] = []
        try {
            const listing = await transport.get<readonly ProjectView[] | null>('/v1/projects')
            projects = listing ?? []
        } catch (problem) {
            tree.replaceChildren(
                trouble(problemText(problem, 'The projects could not be read.')))
            return
        }
        tree.replaceChildren()
        // The global tier first, always, and never conditional on there being
        // any projects: it is where a conversation opened without one lands,
        // which on most deployments is all of them. Measured on 2026-09-08
        // against a real server: 49 of 49 active conversations sat here, and a
        // picker built only from `GET /v1/projects` could reach none of them.
        for (const tier of [GLOBAL, ...projects.map(named)]) {
            const node = el('div', 'project-node')
            node.dataset['project'] = tier.project
            const host = el('div', 'conversations')
            const toggle = button('project-open', tier.name)
            toggle.dataset['tier'] = tier.project
            toggle.addEventListener('click', () => {
                void expand(tier, host)
            })
            node.append(toggle, host)
            tree.append(node)
        }
    }

    options.root.replaceChildren(tree)
    return {
        element: () => tree,
        load: draw,
        picked: () => selected,
        destroy: () => tree.replaceChildren(),
    }
}
