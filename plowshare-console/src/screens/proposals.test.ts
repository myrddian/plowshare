import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api'
import { createProposals, describeAction, describeProposalState, FINALITY } from './proposals'
import type { Screen, Transport } from './screen'
import type { ProposalView, Reconsidered, ResolvedProposal } from './wire'

let server: { waiting: ProposalView[], reconsidered: Reconsidered }
let root: HTMLElement
let screen: Screen
let get: ReturnType<typeof vi.fn>
let post: ReturnType<typeof vi.fn>
/** Every resolve the fake server was asked for, in order. */
let rulings: { path: string, payload: unknown }[]

function proposal(over: Partial<ProposalView>): ProposalView {
    return {
        id: 'prop-1',
        memoryId: 'mem-1',
        project: 'payments',
        action: 'promote',
        reason: 'this holds outside payments too',
        state: 'pending',
        createdAt: '2026-08-30T09:00:00Z',
        proposedBy: 'curator',
        resolvedAt: null,
        resolvedBy: null,
        resolution: null,
        ...over,
    }
}

function transport(): Transport {
    get = vi.fn(async (path: string): Promise<unknown> => {
        if (path.startsWith('/v1/proposals?') || path === '/v1/proposals') {
            return server.waiting
        }
        throw new ApiError(`${path} answered 404`, 404)
    })
    post = vi.fn(async (path: string, payload?: unknown): Promise<unknown> => {
        if (path.startsWith('/v1/proposals/reconsider')) {
            return server.reconsidered
        }
        const match = /^\/v1\/proposals\/([^/]+)\/resolve$/.exec(path)
        if (match !== null) {
            rulings.push({ path, payload })
            const id = decodeURIComponent(match[1] as string)
            const settled = server.waiting.find((one) => one.id === id)
            server.waiting = server.waiting.filter((one) => one.id !== id)
            const answer: ResolvedProposal = {
                proposal: settled ?? null,
                promotedId: 'mem-global-1',
                demoted: ['mem-old-3'],
            }
            return answer
        }
        throw new ApiError(`${path} answered 404`, 404)
    })
    return { get, post } as unknown as Transport
}

function name(text: string): void {
    const who = root.querySelector('[data-input="by"]') as HTMLInputElement
    who.value = text
    who.dispatchEvent(new Event('input'))
}

function accept(id = 'prop-1'): HTMLButtonElement {
    return root.querySelector(`[data-proposal="${id}"] button.accept`) as HTMLButtonElement
}

function reject(id = 'prop-1'): HTMLButtonElement {
    return root.querySelector(`[data-proposal="${id}"] button.reject`) as HTMLButtonElement
}

beforeEach(() => {
    server = { waiting: [proposal({})], reconsidered: { reopened: [], refused: [] } }
    rulings = []
    root = document.createElement('main')
    document.body.replaceChildren(root)
    screen = createProposals({ root, transport: transport(), project: 'payments' })
})

describe('settling is once, and permanently', () => {
    it('says so with every proposal, above the controls that do it', async () => {
        // A consequence a person learns after the click is a consequence they
        // were not given a chance to weigh.
        await screen.load()

        const card = root.querySelector('[data-proposal="prop-1"]') as HTMLElement
        const finality = card.querySelector('[data-finality]') as HTMLElement
        expect(finality.textContent).toBe(FINALITY)
        expect(finality.textContent).toContain('never re-opened')
        expect(finality.textContent).toContain('refuses a stale ruling')
        // Above the controls in document order, which is the half that makes it
        // legible before rather than after.
        const controls = card.querySelector('.rulings') as HTMLElement
        expect(finality.compareDocumentPosition(controls)
            & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })

    it('sends one ruling for a double click and not two', async () => {
        // The ordinary hazard of a slow network. The second click would be a
        // second ruling on a row the first has already settled — refused by the
        // server, which is the good case, and reported to the person as a
        // failure of the ruling they actually meant.
        await screen.load()
        name('enzo')
        let land: (value: unknown) => void = () => undefined
        post.mockImplementationOnce(async () => new Promise((resolve) => {
            land = resolve
        }))
        const control = accept()

        control.click()
        control.click()
        control.click()

        expect(post).toHaveBeenCalledTimes(1)
        expect(control.disabled).toBe(true)
        land({ proposal: null, promotedId: 'mem-global-1', demoted: [] })
        await vi.waitFor(() => expect(root.querySelector('[data-settled]')).not.toBeNull())
        expect(post).toHaveBeenCalledTimes(1)
    })

    it('closes the other ruling too, so accept and reject cannot both be sent', async () => {
        await screen.load()
        name('enzo')
        post.mockImplementationOnce(async () => new Promise(() => undefined))
        const yes = accept()
        const no = reject()

        yes.click()
        no.click()

        expect(post).toHaveBeenCalledTimes(1)
        expect(no.disabled).toBe(true)
        expect(rulings).toEqual([])
    })

    it('closes the controls before anything is awaited', async () => {
        // Everything after the first await is a later turn of the event loop,
        // and a second click lands in between. Asserted synchronously, with no
        // await between the click and the check, because that gap is the bug.
        await screen.load()
        name('enzo')

        accept().click()

        expect(accept().disabled).toBe(true)
        expect(reject().disabled).toBe(true)
        expect((root.querySelector('[data-proposal="prop-1"]') as HTMLElement)
            .dataset['settling']).toBe('true')
    })

    it('sends the ruling, the reason and who decided', async () => {
        await screen.load()
        name('  enzo  ')
        const why = root.querySelector(
            '[data-proposal="prop-1"] [data-input="reason"]') as HTMLInputElement
        why.value = 'it is general'

        reject().click()
        await vi.waitFor(() => expect(rulings).toHaveLength(1))

        expect(rulings[0]).toEqual({
            path: '/v1/proposals/prop-1/resolve',
            payload: { accept: false, reason: 'it is general', by: 'enzo' },
        })
    })

    it('re-reads the queue rather than re-enabling a ruling that failed', async () => {
        // A POST that failed is a POST whose fate this console does not know.
        // The listing is the only thing that says whether the row is still
        // waiting, so it is what decides whether a control comes back.
        await screen.load()
        name('enzo')
        post.mockRejectedValueOnce(new ApiError('/v1/proposals/prop-1/resolve answered 409', 409))

        accept().click()
        await vi.waitFor(() => expect(root.querySelector('[data-trouble]')).not.toBeNull())

        expect(root.querySelector('[data-trouble]')?.textContent).toContain('answered 409')
        expect(get).toHaveBeenCalledTimes(2)
        // Still waiting on the server, so it comes back and can be ruled on.
        expect(accept().disabled).toBe(false)
    })

    it('draws what accepting pushed out of the global index', async () => {
        // ResolvedProposal.demoted is surfaced rather than silent: an approval
        // adds to the tier every project reads, and a promotion that quietly
        // pushed memories out of it would make that index shrink for a reason
        // no caller could see.
        await screen.load()
        name('enzo')

        accept().click()
        await vi.waitFor(() => expect(root.querySelector('[data-settled]')).not.toBeNull())

        const settled = root.querySelector('[data-settled]') as HTMLElement
        expect(settled.textContent).toContain('mem-global-1')
        expect(settled.textContent).toContain('mem-old-3')
        expect(settled.textContent).toContain('cold rather than')
    })
})

describe('who decided is required', () => {
    it('holds the rulings closed until there is a name, and says why', async () => {
        // ResolveProposalRequest.by is required with no default, and a row that
        // cannot say which person records only that somebody, once, thought it
        // was fine.
        await screen.load()

        expect(accept().disabled).toBe(true)
        expect(reject().disabled).toBe(true)
        const note = root.querySelector('[data-need-a-name]') as HTMLElement
        expect(note.hidden).toBe(false)
        expect(note.textContent).toContain('which person')

        name('enzo')

        expect(accept().disabled).toBe(false)
        expect((root.querySelector('[data-need-a-name]') as HTMLElement).hidden).toBe(true)
    })

    it('closes them again when the name is erased', async () => {
        await screen.load()
        name('enzo')

        name('   ')

        expect(accept().disabled).toBe(true)
    })

    it('does not re-open a ruling that has already been spent', async () => {
        await screen.load()
        name('enzo')
        post.mockImplementationOnce(async () => new Promise(() => undefined))
        accept().click()

        name('somebody else')

        expect(accept().disabled).toBe(true)
        expect(post).toHaveBeenCalledTimes(1)
    })
})

describe('a queue with nothing in it', () => {
    it('renders an empty queue as answered rather than as lost', async () => {
        server.waiting = []

        await screen.load()

        const empty = root.querySelector('[data-empty]')
        expect(empty?.textContent).toContain('not listed again')
        expect(root.querySelector('[data-trouble]')).toBeNull()
        expect(root.querySelector('[data-proposal]')).toBeNull()
    })

    it('says a reconsider pass did nothing rather than saying nothing', async () => {
        // Reconsidered carries both lists because a count alone cannot tell a
        // tier with nothing to re-open from one where every row was blocked.
        server.waiting = []
        await screen.load()

        ;(root.querySelector('button.reconsider') as HTMLButtonElement).click()
        await vi.waitFor(() => expect(root.querySelector('[data-reconsidered]')).not.toBeNull())

        const said = root.querySelector('[data-reconsidered]') as HTMLElement
        expect(said.textContent).toContain('nothing went back')
        expect(said.textContent).toContain('nothing was refused')
    })

    it('names the rows a reconsider pass could not put back', async () => {
        server.reconsidered = { reopened: ['prop-9'], refused: ['prop-8 (its place was taken)'] }
        await screen.load()

        ;(root.querySelector('button.reconsider') as HTMLButtonElement).click()
        await vi.waitFor(() => expect(root.querySelector('[data-reconsidered]')).not.toBeNull())

        const said = root.querySelector('[data-reconsidered]') as HTMLElement
        expect(said.textContent).toContain('prop-9')
        expect(said.textContent).toContain('its place was taken')
    })
})

describe('a wire this build was not written against', () => {
    it('renders an action and a state it has never heard of', () => {
        expect(describeAction('promote')).toContain('global tier')
        expect(describeAction('retire')).toBe('retire')
        expect(describeAction('constructor')).toBe('constructor')
        expect(describeAction(null)).toContain('did not name')
        expect(describeProposalState('pending')).toContain('waiting')
        expect(describeProposalState('withdrawn')).toBe('withdrawn')
        expect(describeProposalState('toString')).toBe('toString')
    })

    it('draws a proposal in a state and of a kind this build was not written against',
        async () => {
            server.waiting = [proposal({ action: 'retire', state: 'withdrawn' })]

            await expect(screen.load()).resolves.toBeUndefined()

            const card = root.querySelector('[data-proposal="prop-1"]') as HTMLElement
            expect(card.textContent).toContain('retire')
            expect(card.textContent).toContain('withdrawn')
            expect(card.querySelector('[data-finality]')).not.toBeNull()
        })

    it('says a row nobody was recorded for is a row that predates the column', async () => {
        server.waiting = [proposal({ proposedBy: null })]

        await screen.load()

        expect(root.querySelector('[data-field="raised by"] .value')?.textContent)
            .toContain('predates the column')
    })
})

describe('the tier', () => {
    it('reads the tier it was given and global when the field is emptied', async () => {
        await screen.load()
        expect(get).toHaveBeenCalledWith('/v1/proposals?project=payments')

        const tier = root.querySelector('[data-input="project"]') as HTMLInputElement
        tier.value = ''
        tier.dispatchEvent(new Event('change'))
        await vi.waitFor(() => expect(get).toHaveBeenCalledWith('/v1/proposals'))
    })
})

describe('rendering is escaping', () => {
    it('lands a payload in a reason and an id in the DOM as text', async () => {
        const payload = '<img src=x onerror=alert(1)>'
        server.waiting = [proposal({ reason: payload, memoryId: payload, proposedBy: payload })]

        await screen.load()

        expect(root.querySelector('img')).toBeNull()
        expect(root.querySelector('[data-proposal="prop-1"] pre.body')?.textContent).toBe(payload)
        expect(root.querySelector('[data-field="memory"] .value')?.textContent).toBe(payload)
        expect(root.textContent).not.toContain('&lt;')
    })
})
