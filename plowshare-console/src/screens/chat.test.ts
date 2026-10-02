import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api'
import type { EventStream, EventStreamOptions, StreamStatus } from '../events'
import type { AgentView, ConversationView } from '../repl/wire'
import {
    ACTIVE, createChat, describeLifecycle, LIFECYCLES, NOTHING_PICKED, ORIGIN_NOTE, TREE_NOTE,
} from './chat'
import type { Screen, Transport } from './screen'
import { NOT_PICKED, PAGE } from './trajectory'
import type { ContextView, EntryPageView, EntryView, ProjectView } from './wire'

/**
 * The composed surface, and the capabilities the collapse must not drop.
 *
 * <h2>What this file is really guarding</h2>
 *
 * `sessions.ts` was deleted in this branch, and it was the only screen that
 * could browse a non-active listing, move a conversation to any of the four
 * states, and say the two things {@link ORIGIN_NOTE} and {@link TREE_NOTE} say
 * about the API. So this suite tests, first, the things only `chat.ts` is now
 * faithful to: that the lifecycle filter actually changes which listing the
 * picker shows, that the move reaches every destination `sessions.ts` reached
 * rather than only `archived`, that a refusal is the server's own words, and
 * that the two notes travel with the control.
 *
 * <h2>And the composition itself, which is not the components</h2>
 *
 * The REPL, the picker and the trajectory reading each have their own suite,
 * and each is correct standing alone. What no other suite can see is whether a
 * pick made in one of them reaches the other two -- and the first version of
 * this file said outright that it did not test the trajectory reading, which is
 * exactly where the defect went. `onPick` called `resume`, which rebuilds a
 * transcript and clears no budget verdict, and `load`, which re-reads whichever
 * conversation the trajectory had chosen for itself. So the second group below
 * tests the wiring as behaviour a person would meet: a fresh conversation whose
 * prompt is locked with the last one's hint over it, and a log tab reading a
 * different conversation from the one the sidebar is highlighting.
 */

let server: {
    projects: ProjectView[]
    conversationsByLifecycle: Record<string, ConversationView[]>
    agents: AgentView[]
    refuse: string | null
    /** One trajectory page per conversation, so a read of the wrong one is visible. */
    entriesByConversation: Record<string, EntryView[]>
}
let root: HTMLElement
let screen: Screen
let get: ReturnType<typeof vi.fn>
let put: ReturnType<typeof vi.fn>

function project(name: string): ProjectView {
    return { name, workspace: `/w/${name}`, lent: null, exclusions: null }
}

function conversation(over: Partial<ConversationView>): ConversationView {
    return {
        id: 'cnv_01',
        project: 'payments',
        maxModelCalls: 40,
        modelCallsSpent: 1,
        maxTurns: null,
        noTurnCap: false,
        ...over,
    }
}

function entry(over: Partial<EntryView>): EntryView {
    return {
        ordinal: 1,
        turnOrdinal: 1,
        kind: 'utterance',
        excerpt: 'what a person said',
        length: 18,
        cut: false,
        ejectedAt: null,
        supersededBy: null,
        toolCallId: null,
        toolCalls: [],
        handle: null,
        recordedAt: '2026-09-01T10:00:00Z',
        tookMillis: null,
        dispatch: null,
        wireModel: null,
        completion: null,
        outcome: null,
        ...over,
    }
}

function page(entries: EntryView[]): EntryPageView {
    return { entries, total: entries.length, offset: 0, limit: PAGE }
}

/** The one shape `ContextView` has to have to be drawn without throwing. */
function context(): ContextView {
    return {
        sent: null,
        sentAtTurn: null,
        turns: 0,
        turnsMeasured: 0,
        measuredTurns: [],
        systemPromptTokens: null,
        toolTokens: null,
        messageTokens: null,
        cacheHitRate: null,
        unavailable: [],
        prefix: null,
    }
}

function transport(): Transport {
    get = vi.fn(async (path: string): Promise<unknown> => {
        if (path === '/v1/agents') {
            return server.agents
        }
        if (path.startsWith('/v1/projects')) {
            return server.projects
        }
        // One conversation's own reads, before the listing branch below: every
        // one of these paths also starts with `/v1/conversations`.
        const one = /^\/v1\/conversations\/([^/?]+)\/([a-z]+)/.exec(path)
        if (one !== null) {
            const id = decodeURIComponent(one[1] as string)
            const read = one[2] as string
            if (read === 'turns' || read === 'compactions') {
                return []
            }
            if (read === 'context') {
                return context()
            }
            if (read === 'chat' || read === 'trajectory') {
                return page(server.entriesByConversation[id] ?? [])
            }
            throw new ApiError(`${path} answered 404`, 404)
        }
        if (path.startsWith('/v1/conversations')) {
            const url = new URL(path, 'https://console.invalid')
            const asked = url.searchParams.get('project')
            const lifecycle = url.searchParams.get('lifecycle') ?? ACTIVE
            const rows = server.conversationsByLifecycle[lifecycle] ?? []
            return asked === null ? rows : rows.filter((row) => row.project === asked)
        }
        throw new ApiError(`${path} answered 404`, 404)
    })
    put = vi.fn(async (path: string, payload?: unknown): Promise<unknown> => {
        const match = /^\/v1\/conversations\/([^/]+)\/lifecycle$/.exec(path)
        if (match === null) {
            throw new ApiError(`${path} answered 404`, 404)
        }
        if (server.refuse !== null) {
            throw new ApiError(server.refuse, 409)
        }
        return { id: decodeURIComponent(match[1] as string), lifecycle: payload }
    })
    return { get, post: vi.fn(async () => []), put } as unknown as Transport
}

function stream(options: EventStreamOptions): EventStream {
    return {
        status: (): StreamStatus => ({ state: 'open', attempt: 0, retryInMs: null }),
        close: (): void => {
            options.onStatus?.({ state: 'closed', attempt: 0, retryInMs: null })
        },
        ask: () => Promise.reject(new Error('not in this test')),
    }
}

function build(): Screen {
    return createChat({
        root,
        transport: transport(),
        openStream: stream,
        session: 'session-under-test',
        project: null,
    })
}

beforeEach(() => {
    server = {
        projects: [project('payments')],
        conversationsByLifecycle: {
            // cnv_01 has spent every model call it was given and cnv_03 has
            // spent one: the pair is what makes "the prompt follows the pick"
            // a thing a test can see.
            active: [
                conversation({ id: 'cnv_01', project: 'payments', modelCallsSpent: 40 }),
                conversation({ id: 'cnv_03', project: 'payments', modelCallsSpent: 1 }),
            ],
            archived: [conversation({ id: 'cnv_02', project: 'payments' })],
        },
        agents: [{ name: 'interlocutor', tools: [], calls: [], scopes: [], served: true,
            withheld: [] }],
        refuse: null,
        entriesByConversation: {
            cnv_01: [entry({ ordinal: 1, excerpt: 'said in cnv_01' })],
            cnv_03: [entry({ ordinal: 2, excerpt: 'said in cnv_03' })],
        },
    }
    root = document.createElement('main')
    document.body.replaceChildren(root)
    screen = build()
})

afterEach(() => {
    screen.destroy()
})

function lifecycleFilter(): HTMLSelectElement {
    return root.querySelector('[data-lifecycle]') as HTMLSelectElement
}

function destinationChooser(): HTMLSelectElement {
    return root.querySelector('[data-lifecycle-target]') as HTMLSelectElement
}

function optionsOf(select: HTMLSelectElement): string[] {
    return [...select.options].map((option) => option.value)
}

/** Opens the payments project, then picks its one visible conversation. */
async function pickPaymentsConversation(id: string): Promise<void> {
    (root.querySelector('[data-project="payments"] button') as HTMLButtonElement).click()
    await Promise.resolve()
    ;(root.querySelector(`[data-conversation="${id}"]`) as HTMLButtonElement).click()
}

describe('one conversation, three readings', () => {
    it('shows the picker and the conversation surface together', async () => {
        await screen.load()
        expect(root.querySelector('.picker')).not.toBeNull()
        expect(root.querySelector('[data-tab="chat"]')).not.toBeNull()
    })

    it('lifecycle moves stay reachable after sessions is gone', async () => {
        // The sessions screen's archiving must not be lost in the collapse.
        await screen.load()
        expect(root.querySelector('[data-lifecycle-move]')).not.toBeNull()
    })

    it('says the lifecycle word belongs to the listing and not to the row', async () => {
        // ConversationView still carries no lifecycle field -- sessions.ts's
        // sentence has to travel with the control, not just the control.
        await screen.load()
        const note = root.querySelector('.chat-side .note')
        expect(note?.textContent ?? '').toContain('not each row\'s')
    })

    it('says nothing is picked rather than doing nothing at all', async () => {
        // Nothing goes over the wire, and the button does not read as broken:
        // the listing control drops the selection when it rebuilds the tree,
        // which is a thing that happens to a person's pick without their doing
        // it, so pressing move afterwards has to say what is missing.
        await screen.load()
        ;(root.querySelector('[data-lifecycle-move]') as HTMLButtonElement).click()
        await Promise.resolve()
        expect(put).not.toHaveBeenCalled()
        expect(root.querySelector('.chat-side [data-trouble]')?.textContent)
            .toBe(NOTHING_PICKED)
    })

    it('forgets the pick once a move lands, so a second press is not a refusal', async () => {
        // `Picker.load()` redraws the tree and keeps the picker's memory of the
        // click, so reloading in place left the button armed with an id the
        // server had just taken out of this listing -- a second press is a 409
        // a person did nothing to earn.
        await screen.load()
        await pickPaymentsConversation('cnv_01')
        const control = root.querySelector('[data-lifecycle-move]') as HTMLButtonElement
        control.click()
        await vi.waitFor(() => expect(put).toHaveBeenCalledTimes(1))

        control.click()
        await Promise.resolve()

        expect(put).toHaveBeenCalledTimes(1)
        expect(root.querySelector('.chat-side [data-trouble]')?.textContent)
            .toBe(NOTHING_PICKED)
    })

    it('puts the picked conversation to archived, the same endpoint sessions.ts used', async () => {
        await screen.load()
        await pickPaymentsConversation('cnv_01')
        ;(root.querySelector('[data-lifecycle-move]') as HTMLButtonElement).click()
        await Promise.resolve()
        await Promise.resolve()
        expect(put).toHaveBeenCalledWith(
            '/v1/conversations/cnv_01/lifecycle', { lifecycle: 'archived' })
    })

    it('renders the server\'s refusal verbatim rather than inventing a message', async () => {
        server.refuse = 'cnv_01 cannot move to archived from ejected'
        await screen.load()
        await pickPaymentsConversation('cnv_01')
        ;(root.querySelector('[data-lifecycle-move]') as HTMLButtonElement).click()
        await Promise.resolve()
        await Promise.resolve()
        const trouble = root.querySelector('.chat-side [data-trouble]')
        expect(trouble?.textContent ?? '').toBe(server.refuse)
    })

    it('offers the four lifecycle states sessions.ts named, as the listing filter', async () => {
        await screen.load()
        expect(optionsOf(lifecycleFilter())).toEqual(Object.keys(LIFECYCLES))
    })

    it('reloads the picker under the chosen lifecycle when the filter changes', async () => {
        await screen.load()
        lifecycleFilter().value = 'archived'
        lifecycleFilter().dispatchEvent(new Event('change'))
        await Promise.resolve()
        ;(root.querySelector('[data-project="payments"] button') as HTMLButtonElement).click()
        await Promise.resolve()
        expect(root.querySelector('[data-conversation="cnv_02"]')).not.toBeNull()
        expect(root.querySelector('[data-conversation="cnv_01"]')).toBeNull()
    })

    it('offers every destination except the state currently shown', async () => {
        await screen.load()
        expect(optionsOf(destinationChooser()))
            .toEqual(Object.keys(LIFECYCLES).filter((state) => state !== ACTIVE))
    })

    it('drops the new filter state from the destinations once the filter changes', async () => {
        await screen.load()
        lifecycleFilter().value = 'archived'
        lifecycleFilter().dispatchEvent(new Event('change'))
        expect(optionsOf(destinationChooser())).not.toContain('archived')
        expect(optionsOf(destinationChooser())).toContain(ACTIVE)
    })

    it('moves the picked conversation to whatever destination is chosen', async () => {
        await screen.load()
        await pickPaymentsConversation('cnv_01')
        destinationChooser().value = 'to_be_ejected'
        destinationChooser().dispatchEvent(new Event('change'))
        ;(root.querySelector('[data-lifecycle-move]') as HTMLButtonElement).click()
        await Promise.resolve()
        await Promise.resolve()
        expect(put).toHaveBeenCalledWith(
            '/v1/conversations/cnv_01/lifecycle', { lifecycle: 'to_be_ejected' })
    })

    it('carries sessions.ts\'s origin and tree notes forward, verbatim', async () => {
        await screen.load()
        const notes = [...root.querySelectorAll('.chat-side .note')].map((node) => node.textContent)
        expect(notes).toContain(ORIGIN_NOTE)
        expect(notes).toContain(TREE_NOTE)
    })

    // Ported from sessions.test.ts, which is deleted in this task: LIFECYCLES
    // and describeLifecycle are pure and owe nothing to a screen, but they
    // still need a home now that sessions.ts is gone.
    it('offers every state this build knows, and renders one it does not as itself', () => {
        expect(Object.keys(LIFECYCLES)).toEqual(
            ['active', 'archived', 'to_be_ejected', 'ejected'])
        expect(describeLifecycle('archived')).toContain('refuses')
        // ConversationLifecycle.of is the contract with rows already written; a
        // fifth state must reach this console without a change here.
        expect(describeLifecycle('quarantined')).toBe('quarantined')
        expect(describeLifecycle(7)).toContain('did not name')
    })
})

function prompt(): HTMLTextAreaElement {
    return root.querySelector('.repl textarea') as HTMLTextAreaElement
}

function hint(): string {
    return root.querySelector('.repl .hint')?.textContent ?? ''
}

function budgetLabel(): string {
    return root.querySelector('.repl .budget')?.textContent ?? ''
}

function tab(name: string): HTMLButtonElement {
    return root.querySelector(`[data-tab="${name}"]`) as HTMLButtonElement
}

function entryText(): string {
    return [...root.querySelectorAll('[data-entry]')].map((node) => node.textContent).join('\n')
}

describe('one pick, carried across everything in the view', () => {
    it('shows one conversation chooser and not three', async () => {
        // The picker is the chooser. Two elements carrying [data-conversations]
        // in one view is a scoped querySelector resolving by DOM order, and
        // three ways to name a conversation is the defect §1 describes.
        await screen.load()
        expect(root.querySelectorAll('[data-conversations]').length).toBe(0)
        expect(root.querySelectorAll('.picker').length).toBe(1)
    })

    it('closes the prompt on a conversation whose budget is really spent', async () => {
        // The premise of the next test, and a fact in its own right: `resume`
        // alone never reads the row, so nothing here would ever be true of a
        // REPL that was only resumed.
        await screen.load()
        await pickPaymentsConversation('cnv_01')

        // Waited for on the hint and not on `disabled`, which is already true
        // before anything is picked and would let this pass on the wrong state.
        await vi.waitFor(() => expect(hint()).toContain('spent its budget'))
        expect(prompt().disabled).toBe(true)
        expect(budgetLabel()).toContain('40/40')
    })

    it('leaves the prompt usable when the next conversation picked has budget left', async () => {
        // The failure this is here for: `spent` is terminal for the
        // conversation that set it and for no other, so a REPL that carried it
        // across a pick disables the textarea on a fresh conversation and says
        // on screen that this one has spent its budget -- which is false.
        await screen.load()
        await pickPaymentsConversation('cnv_01')
        await vi.waitFor(() => expect(hint()).toContain('spent its budget'))

        await pickPaymentsConversation('cnv_03')

        await vi.waitFor(() => expect(hint()).toBe(''))
        expect(prompt().disabled).toBe(false)
        expect(budgetLabel()).toContain('1/40')
    })

    it('reads the picked conversation on the trajectory tab, not one of its own', async () => {
        // The sidebar highlights one conversation and the tabs beside it drew
        // another, with nothing on the page saying so: somebody auditing which
        // records are superseded or ejected read the wrong conversation's log.
        await screen.load()
        await pickPaymentsConversation('cnv_01')
        tab('trajectory').click()
        await vi.waitFor(() => expect(entryText()).toContain('said in cnv_01'))

        await pickPaymentsConversation('cnv_03')

        await vi.waitFor(() => expect(entryText()).toContain('said in cnv_03'))
        expect(entryText()).not.toContain('said in cnv_01')
        expect(get).toHaveBeenCalledWith(
            `/v1/conversations/cnv_03/trajectory?offset=0&limit=${PAGE}`)
    })

    it('draws the picked conversation\'s records on the log tab', async () => {
        await screen.load()
        await pickPaymentsConversation('cnv_03')
        tab('log').click()

        await vi.waitFor(() => expect(root.querySelector('[data-record="2"]')).not.toBeNull())
        expect(root.querySelector('[data-record="1"]')).toBeNull()
    })

    it('says nothing is picked rather than drawing a tab note over a blank section', async () => {
        // An empty result is a sentence about that emptiness, and a section
        // with no rows, no sentence and no error is the one thing it must not
        // be -- a person cannot tell it apart from a screen that failed to draw.
        await screen.load()

        const empty = root.querySelector('.trajectory [data-empty]')
        expect(empty?.textContent).toBe(NOT_PICKED)
    })

    it('does not list conversations a second time for controls it does not draw', async () => {
        // Both inner choosers are retired here, and the listings that filled
        // them go with them: the picker's per-project reads are the ones this
        // view needs, and the REPL asks for the row it switches to when it
        // switches to it.
        await screen.load()

        const listings = get.mock.calls
            .map((call) => call[0] as string)
            .filter((path) => /^\/v1\/conversations(\?|$)/.test(path))
        expect(listings).toEqual([])
    })
})
