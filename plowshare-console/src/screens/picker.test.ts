import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api'
import type { Transport } from './screen'
import { createPicker, type Picker } from './picker'

let root: HTMLElement
let picker: Picker
let get: ReturnType<typeof vi.fn>
let picked: string[]

function transport(): Transport {
    get = vi.fn(async (path: string): Promise<unknown> => {
        if (path.startsWith('/v1/projects')) {
            return [{ name: 'payments', workspace: '/w/payments' },
                    { name: 'excalibur', workspace: '/w/excalibur' }]
        }
        if (path.startsWith('/v1/conversations')) {
            const project = new URL(path, 'https://console.invalid')
                .searchParams.get('project')
            if (project === null) {
                // No project named at all is the global tier, which is what
                // this server answers Home.global() for.
                return [{ id: 'cnv_global', project: null, maxModelCalls: 40,
                          modelCallsSpent: 3, maxTurns: null, noTurnCap: false }]
            }
            return project === 'payments'
                ? [{ id: 'cnv_01', project: 'payments', maxModelCalls: 40,
                     modelCallsSpent: 1, maxTurns: null, noTurnCap: false }]
                : []
        }
        throw new ApiError(`${path} answered 404`, 404)
    })
    return { get, post: vi.fn(), put: vi.fn() } as unknown as Transport
}

beforeEach(() => {
    root = document.createElement('div')
    picked = []
    picker = createPicker({
        root, transport: transport(), onPick: (id) => picked.push(id),
    })
})

describe('the picker nests conversations under their project', () => {
    it('lists every project as a node, and the global tier before them', async () => {
        await picker.load()
        // Three nodes for two projects: the tier a conversation gets when
        // nobody chose one is not a project and never comes from
        // GET /v1/projects, but it is where most conversations live.
        const nodes = [...root.querySelectorAll('[data-project]')]
        expect(nodes.length).toBe(3)
        expect((nodes[0] as HTMLElement).dataset['project']).toBe('')
    })

    it('lists the conversations that belong to no project', async () => {
        // The ordinary case, not an edge one. Measured against a real server on
        // 2026-09-08: 49 of 49 active conversations had project: null, and a
        // picker built only from GET /v1/projects could reach none of them.
        await picker.load()
        ;(root.querySelector('[data-project=""] button') as HTMLButtonElement).click()
        await Promise.resolve()
        expect(root.querySelector('[data-conversation="cnv_global"]')).not.toBeNull()
    })

    it('asks for the global tier by naming no project at all', async () => {
        // ConversationController.resolveHome(null) answers Home.global(), so
        // omitting the parameter IS the question. An empty ?project= is a
        // different request and a refused one: it reaches Home.of(""), which
        // throws on a blank name, and the controller answers 400.
        await picker.load()
        ;(root.querySelector('[data-project=""] button') as HTMLButtonElement).click()
        await Promise.resolve()
        const asked = (get.mock.calls.map((call) => call[0] as string))
            .filter((path) => path.startsWith('/v1/conversations'))
        expect(asked.length).toBe(1)
        expect(new URL(asked[0] as string, 'https://console.invalid')
            .searchParams.has('project')).toBe(false)
    })

    it('asks for one project\'s conversations when it is opened', async () => {
        await picker.load()
        ;(root.querySelector('[data-project="payments"] button') as HTMLButtonElement).click()
        await Promise.resolve()
        const asked = get.mock.calls.map((call) => call[0] as string)
        expect(asked.some((path) => path.includes('project=payments'))).toBe(true)
    })

    it('reports the conversation that was picked', async () => {
        await picker.load()
        ;(root.querySelector('[data-project="payments"] button') as HTMLButtonElement).click()
        await Promise.resolve()
        ;(root.querySelector('[data-conversation="cnv_01"]') as HTMLButtonElement).click()
        expect(picked).toEqual(['cnv_01'])
        expect(picker.picked()).toBe('cnv_01')
    })

    it('says so when a project holds no conversations, rather than drawing nothing', async () => {
        await picker.load()
        ;(root.querySelector('[data-project="excalibur"] button') as HTMLButtonElement).click()
        await Promise.resolve()
        const node = root.querySelector('[data-project="excalibur"] [data-empty]')
        expect(node).not.toBeNull()
    })
})
