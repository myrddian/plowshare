import { api } from '../api'
import type { EventStream, EventStreamOptions } from '../events'
import type { ApprovalList, ApprovalRevoked, ApprovalView } from '../repl/wire'
import { button, el, field, input, labelled, nothing, problemText, textOf, trouble }
    from './dom'
import type { Screen, Transport } from './screen'
import type { ProjectView } from './wire'

/**
 * The projects screen: every leash that has been set -- where each project is,
 * what else it reaches, and what it really fences off.
 *
 * <h2>Where a project is, and what it reaches, are two rows on the card</h2>
 *
 * `workspace` is one directory and stays one: it is the `<PATH>` of the
 * project's full name `MACHINE/PATH/NAME`, so an identity composed from a list
 * would change whenever the list reordered. `lent` is everything else the
 * project's jobs may open at that place, and it may name a directory *outside*
 * the workspace -- that is the feature and not an edge case.
 *
 * So the card says both, separately. Drawing one merged list would make every
 * path after the first look like somewhere the project might be, and drawing
 * only the workspace -- which is what this screen did while a project had one
 * directory -- would show a person a **shorter** leash than the one enforced.
 * That is the same failure the section below refuses in the other direction,
 * arriving through the grant instead of through the fence.
 *
 * Unlike `exclusions`, `lent` is complete as the wire sends it: the server adds
 * paths of its own to the fence and none of its own to the grant. So there is no
 * "which of these is the server's" problem on this half, and no note is needed
 * to say there is one.
 *
 * <h2>The list on the screen is the leash as enforced, not the row</h2>
 *
 * `ProjectView.exclusions` is `ProjectStore.effectiveExclusions`' answer and
 * not `ProjectRecord.exclusions()`. The row stores only the paths the project
 * itself fenced off; the paths no project may override are held in Java and
 * added on top, deliberately never seeded into a row, because *a default
 * written into a row is a default a row can be edited out of*. So what this
 * screen draws, together with the two rows above it, is the whole leash --
 * which is the single thing a person opens it
 * to learn -- slice 3b's argument is that a person cannot reason about a leash
 * whose mandatory parts are invisible, and a screen that listed rows would make
 * them invisible again one layer up.
 *
 * <h2>What this screen cannot say, and says instead of guessing</h2>
 *
 * **The wire carries one list and marks nothing in it.** `ProjectView` sends
 * `List<String> exclusions` with no flag per entry and no companion field, so
 * from this answer alone nothing can tell an entry the server insisted on from
 * an entry the row asked for. This screen therefore does not mark individual
 * paths, and the reason is not modesty:
 *
 * - **Marking too many is the dangerous error.** A path of the project's own,
 *   labelled as one the operator cannot remove, is a path they stop trying to
 *   remove. The screen would have taught them a false rule about their own
 *   server.
 * - **Marking too few is visible and recoverable**: they try, and it stays.
 *
 * The available derivations all fail in the dangerous direction. The mandatory
 * paths are a prefix of every project's list -- `effectiveExclusions` builds a
 * `LinkedHashSet` with them first -- but the wire does not carry the prefix's
 * length. They also appear on every project, so an intersection across the
 * listing is a superset of them; with one project that superset is the whole
 * list, and two projects that happen to fence off the same directory put a
 * project's own path into it. A superset marked as "you cannot remove these" is
 * exactly the dangerous error, and it arrives most readily on the smallest
 * deployment.
 *
 * So {@link LEASH_NOTE} states the property at the level the answer supports
 * it: this list is effective, part of it is the server's and immovable, and
 * this answer does not separate the two. It names no path and counts nothing --
 * `ProjectStore.mandatoryExclusions` derives its answer from a set of candidates
 * and drops any that lies inside another, so the ordinary arrangement collapses
 * to one path and a console that named a count would be wrong on every ordinary
 * server. The count is deliberately not written down here either: it has already
 * grown twice, and a paragraph that stated it would have been wrong both times.
 *
 * The remedy is a wire change and is written up rather than faked here: one
 * more field on `ProjectView` carrying `ProjectStore.mandatoryExclusions`, or a
 * per-entry flag, would let this screen mark each path with no derivation at
 * all.
 */

/**
 * What the exclusion list is, said once per project.
 *
 * Exported so the test asserts the property rather than a paraphrase of it, and
 * so that the wording lives beside the argument above.
 */
export const LEASH_NOTE =
    'These are the paths this project’s jobs cannot reach, as this server enforces them — not'
    + ' the paths this project’s row stores. Some of them are the server’s own, applied to every'
    + ' project so that no row can open them, and this answer does not mark which: the row’s'
    + ' paths and the server’s arrive undistinguished. Redefining this project can drop only'
    + ' the paths this project added; the rest stay whatever the row says.'

/**
 * What an empty exclusion list means, which is not "this project is open".
 *
 * The server adds its own paths before building this answer, so it does not
 * produce an empty list for a project it holds. An empty one is therefore an
 * answer this console cannot account for, and reporting it as an open leash
 * would be the one reading that is both reassuring and unsafe.
 */
export const NO_EXCLUSIONS =
    'This answer carries no exclusions for this project. This server adds its own before it'
    + ' answers, so an empty list is not a leash with nothing on it — it is an answer this'
    + ' console cannot account for. Treat this project’s reach as unknown rather than as open.'

export const NO_PROJECTS =
    'No project on this server has a workspace. That is an answer and not a failure: a job in a'
    + ' project with no workspace has no local file access at all, and every file tool says so'
    + ' in words when it is asked.'

/**
 * What an empty `lent` list means, which is the ordinary case and not a
 * shortfall.
 *
 * The opposite of {@link NO_EXCLUSIONS}, and worth its own constant for that
 * reason: an empty exclusion list is an answer this console cannot account for,
 * because the server always adds its own. An empty `lent` list is the plain
 * truth about most projects -- the server lends nothing on its own -- so
 * rendering the two the same way would teach a person to distrust the honest
 * one.
 */
export const NOTHING_LENT =
    'Nothing else is lent to this project. Its jobs reach the workspace above and nothing'
    + ' beside it.'

/** What an empty approved-commands list means: nothing runs here without asking. */
export const NOTHING_APPROVED =
    'No command is approved for this whole project. A run that asks is asked again each time,'
    + ' unless a person allowed it for one conversation.'

export interface ProjectsOptions {
    readonly root: HTMLElement
    readonly transport?: Transport
    /**
     * The tab's socket, for the approved-commands list. Absent, the list is not
     * drawn: approvals are frames only and have no HTTP route to fall back to.
     */
    readonly openStream?: (options: EventStreamOptions) => EventStream
    readonly session?: string
}

export function createProjects(options: ProjectsOptions): Screen {
    const transport: Transport = options.transport ?? api

    const shell = el('section', 'screen projects')
    const head = el('header', 'screen-head')
    const title = el('h2', 'screen-title', 'projects')
    const reload = button('reload', 'reload')
    const body = el('div', 'screen-body')
    body.dataset['projects'] = ''

    head.append(title, reload)
    shell.append(head, body)
    options.root.replaceChildren(shell)

    reload.addEventListener('click', () => {
        void load()
    })

    /**
     * One project: its workspace, its whole leash, and the one verb this screen
     * has for it.
     */
    function draw(project: ProjectView): HTMLElement {
        const card = el('article', 'project')
        card.dataset['project'] = textOf(project.name)
        card.append(el('h3', 'project-name', textOf(project.name)))
        card.append(field('workspace', textOf(project.workspace)))
        card.append(lent(project))
        card.append(leash(project))
        card.append(mover(project))
        if (options.openStream !== undefined) {
            const section = el('section', 'approved')
            section.dataset['approved'] = textOf(project.name)
            card.append(section)
            void approved(section, textOf(project.name))
        }
        return card
    }

    /**
     * The commands a person allowed for this whole project, each revocable.
     *
     * `approval.list { project }` and `approval.revoke`, over the socket -- there
     * is no HTTP for either. A project approval is a leading part of a command on
     * one side, any directory there; it is drawn as that prefix, because the
     * prefix is what it allows, and the command that was first asked about is
     * only where it came from.
     */
    async function approved(section: HTMLElement, name: string): Promise<void> {
        const headed = (...rest: HTMLElement[]): void => {
            section.replaceChildren(el('div', 'approved-head', 'approved commands'), ...rest)
        }
        if (stream === null || stream.status().state !== 'open') {
            const waiting = el('p', 'nothing', 'connecting…')
            waiting.dataset['connecting'] = ''
            headed(waiting)
            return
        }
        let outcome
        try {
            outcome = await stream.ask('approval.list', { project: name })
        } catch (problem) {
            headed(trouble(problemText(problem, 'The approved commands could not be listed.')))
            return
        }
        if (outcome.code !== 'OK') {
            headed(trouble(outcome.said ?? 'The approved commands could not be listed.'))
            return
        }
        const standing = (outcome.payload as ApprovalList | undefined)?.approvals ?? []
        if (standing.length === 0) {
            headed(nothing(NOTHING_APPROVED))
            return
        }
        const list = el('ul', 'approved-list')
        list.append(...standing.map((one) => approval(section, name, one)))
        headed(list)
    }

    function approval(section: HTMLElement, name: string, one: ApprovalView): HTMLElement {
        const item = el('li', 'approved-item')
        item.dataset['approval'] = textOf(one.id)
        const prefix = Array.isArray(one.prefix) ? one.prefix : one.command
        const words = el('code', 'approved-prefix', (prefix ?? []).map(textOf).join(' '))
        const where = el('span', 'approved-side', `${textOf(one.side)} side, any directory`)
        const revoke = button('revoke', 'Revoke')
        revoke.dataset['revoke'] = textOf(one.id)
        revoke.addEventListener('click', () => {
            revoke.disabled = true
            void (async (): Promise<void> => {
                let outcome
                try {
                    if (stream === null) {
                        throw new Error('the event socket is not open; "approval.revoke" was not sent')
                    }
                    outcome = await stream.ask('approval.revoke', { id: one.id })
                } catch (problem) {
                    revoke.disabled = false
                    item.append(trouble(problemText(problem, 'That approval could not be revoked.')))
                    return
                }
                if (outcome.code !== 'OK') {
                    revoke.disabled = false
                    item.append(trouble(outcome.said ?? 'That approval could not be revoked.'))
                    return
                }
                // Re-read either way: `revoked: false` is an approval that was no
                // longer standing, and the list should stop showing it too.
                if ((outcome.payload as ApprovalRevoked | undefined)?.revoked !== true) {
                    item.append(trouble('That approval was no longer standing; nothing was changed.'))
                }
                await approved(section, name)
            })()
        })
        item.append(words, where, revoke)
        return item
    }

    /**
     * The directories lent alongside the workspace.
     *
     * Its own section rather than more `field` rows, because the count is not
     * fixed and because the heading is what carries the meaning: these are
     * places the project reaches and is not *at*. `nothing()` and not
     * `trouble()` for the empty case -- see {@link NOTHING_LENT}, which is the
     * one place on this card where an empty list is simply true.
     */
    function lent(project: ProjectView): HTMLElement {
        const section = el('section', 'lent')
        section.append(el('div', 'lent-head', 'also reached from this project'))
        const paths = Array.isArray(project.lent) ? project.lent : []
        if (paths.length === 0) {
            section.append(nothing(NOTHING_LENT))
            return section
        }
        const list = el('ul', 'lent-roots')
        list.dataset['lent'] = 'roots'
        for (const path of paths) {
            const item = el('li', 'lent-root', textOf(path))
            item.dataset['lentRoot'] = ''
            list.append(item)
        }
        section.append(list)
        return section
    }

    function leash(project: ProjectView): HTMLElement {
        const section = el('section', 'leash')
        section.append(el('div', 'leash-head', 'cannot be reached from this project'))
        const paths = Array.isArray(project.exclusions) ? project.exclusions : []
        if (paths.length === 0) {
            // Not `nothing()`: an empty leash is not "searched, and there was
            // nothing". It is an answer this console cannot account for, and
            // the two must not render as the same reassuring sentence.
            section.append(trouble(NO_EXCLUSIONS))
            return section
        }
        const list = el('ul', 'exclusions')
        // The attribute a test finds the list by, and the word that says what
        // the list IS. `effective` rather than a per-entry flag, because the
        // wire carries no per-entry fact to hang one on -- see this file's
        // header for why inventing one would be the dangerous error.
        list.dataset['exclusions'] = 'effective'
        for (const path of paths) {
            const item = el('li', 'exclusion', textOf(path))
            item.dataset['exclusion'] = ''
            list.append(item)
        }
        section.append(list)
        const note = el('p', 'leash-note', LEASH_NOTE)
        note.dataset['leashNote'] = ''
        section.append(note)
        return section
    }

    /**
     * `POST /v1/projects/{name}/workspace`, which moves a project without
     * touching its exclusions.
     *
     * Deliberately not a general edit form. `ProjectView`'s javadoc warns that
     * a console filling an edit form from a listing and posting it back
     * unchanged would write the mandatory paths into the row -- which is
     * exactly what this screen cannot avoid doing, because it cannot tell which
     * entries those are. `moveWorkspace` is one statement that keeps the row's
     * own exclusions, so it is the verb this screen can offer honestly.
     *
     * It keeps the lent directories too, for the same reason and with no extra
     * work: the statement names `workspace` and nothing else. So this screen has
     * no verb that lends either -- `POST /v1/projects/{name}/lend` exists and
     * would be an honest one to add, since `lent` arrives complete and a form
     * posting it back would write nothing the server did not send. It is left
     * out because this screen's one verb is deliberate, not because lending
     * carries the hazard exclusions do.
     */
    function mover(project: ProjectView): HTMLElement {
        const form = el('div', 'mover')
        const where = input('workspace', 'a directory on this server')
        const go = button('move', 'point at this directory')
        const say = el('p', 'mover-note',
            'Moves this project’s workspace and keeps both its exclusions and the directories'
            + ' lent to it. This screen has no verb'
            + ' that rewrites them: it cannot tell which of the excluded paths came from this'
            + ' project’s row, so posting the list back would write this server’s own paths into'
            + ' the row, where a later edit could drop them.')
        go.addEventListener('click', () => {
            const wanted = where.value.trim()
            if (wanted === '') {
                form.append(trouble('A workspace is a directory on this server’s disk. Nothing'
                    + ' was sent.'))
                return
            }
            go.disabled = true
            void transport
                .post<ProjectView>(
                    `/v1/projects/${encodeURIComponent(textOf(project.name))}/workspace`,
                    { workspace: wanted },
                )
                // The listing is re-read rather than the answer patched in.
                // The answer carries the effective list for one project, and
                // what this screen shows is every project's; re-reading is one
                // call and cannot leave the two disagreeing.
                .then(() => load())
                .catch((problem: unknown) => {
                    go.disabled = false
                    form.append(trouble(problemText(
                        problem, 'That workspace could not be set, and the failure said nothing'
                        + ' this console can repeat.')))
                })
        })
        form.append(labelled('move to', where), go, say)
        return form
    }

    let stream: EventStream | null = null
    let stopped = false

    async function load(): Promise<void> {
        if (options.openStream !== undefined && stream === null && !stopped) {
            stream = options.openStream({
                session: options.session ?? '',
                onEvent: () => {},
                // Built before the socket opened, a card says "connecting"; the
                // open is what fills it in.
                onStatus: (status) => {
                    if (status.state === 'open' && stream !== null) {
                        for (const section of body.querySelectorAll<HTMLElement>('[data-approved]')) {
                            void approved(section, section.dataset['approved'] ?? '')
                        }
                    }
                },
            })
        }
        let projects: readonly ProjectView[]
        try {
            projects = (await transport.get<ProjectView[]>('/v1/projects')) ?? []
        } catch (problem) {
            body.replaceChildren(trouble(problemText(
                problem, 'The projects could not be listed.')))
            return
        }
        if (projects.length === 0) {
            body.replaceChildren(nothing(NO_PROJECTS))
            return
        }
        const personal = projects.filter(project => project.kind === 'personal');
        const ordinary = projects.filter(project => project.kind !== 'personal');
        const personalRows = personal.map(() => {
            const element = document.createElement('section');
            element.className = 'project';
            const title = document.createElement('h2'); title.textContent = 'Personal';
            const note = document.createElement('p'); note.textContent = 'Your account space: In, Out, Resources, Archive, Planning and Bots. Mounted at ~/.plowshare/personal by the desktop or TUI.';
            element.append(title, note); return element;
        });
        body.replaceChildren(...personalRows, ...ordinary.map(draw))
    }

    return {
        element: () => shell,
        load,
        destroy(): void {
            // No timer; the socket subscription, when there is one, is the only
            // thing to let go of.
            stopped = true
            stream?.close()
            stream = null
        },
    }
}
