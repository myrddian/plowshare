import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import {
    CLAMP_AT, describeCost, describeEnding, renderApproval, renderEntry, startingPrefix, transcript,
    type ApprovalResult,
} from './render'
import type { ApprovalView, CompactionView, TurnView } from './wire'

const HERE = dirname(fileURLToPath(import.meta.url))

/** Every `.ts` file under `src/`, tests excluded: what the source-level guards read. */
function renderers(directory: string = dirname(HERE)): string[] {
    return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
        const full = join(directory, entry.name)
        if (entry.isDirectory()) {
            return renderers(full)
        }
        return entry.name.endsWith('.ts') && !entry.name.endsWith('.test.ts')
            && !entry.name.endsWith('.d.ts')
            ? [full]
            : []
    })
}

/**
 * A file's source with its comments stripped.
 *
 * Stripped first, and the guard below failed until they were: the paragraphs in
 * these files state the rule by name, and a check that matched prose would be a
 * check that forbids writing the rule down.
 */
function sourceOf(file: string): string {
    return readFileSync(file, 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/\/\/.*$/gm, '')
}

/** The wording the runtime really appends, copied from `JobRuntime.Repeats.opening`. */
const RUNTIME_WORDING =
    "[Runtime note, not part of any tool's answer: you have now called 'file_read' with exactly"
    + ' the same arguments three times in a row]'

function turn(over: Partial<TurnView>): TurnView {
    return {
        ordinal: 1,
        utterance: 'a question',
        answer: 'an answer',
        ending: 'ANSWERED',
        promptTokens: 42,
        ...over,
    }
}

function into(entries: ReturnType<typeof transcript>): HTMLElement {
    const root = document.createElement('div')
    root.append(...entries.map(renderEntry))
    return root
}

describe('describeEnding', () => {
    it('says what the endings this build knows mean', () => {
        expect(describeEnding('ANSWERED')).toBe('answered')
        expect(describeEnding('CALL_BUDGET')).toContain('budget')
    })

    it('tells a run that was stuck from one that ran out of room', () => {
        // Two endings that read the same would be one ending to whoever is
        // looking at the transcript, and these two send a person to different
        // places: one is answered by giving the run more room and the other is
        // answered by not giving it any.
        expect(describeEnding('STUCK')).not.toBe(describeEnding('TURN_CAP'))
        expect(describeEnding('STUCK')).toContain('same call')
        expect(describeEnding('STUCK')).not.toContain('turn cap')
    })

    it('says a run that kept writing its calls as text was stopped for it', () => {
        expect(describeEnding('CALL_FAILURES')).toContain('as text')
        expect(describeEnding('CALL_FAILURES')).not.toBe(describeEnding('STUCK'))
    })

    it('renders an ending it has never heard of, rather than failing over one', () => {
        // TurnView sends the constant's own name and nothing enumerates them,
        // exactly so that an ending added on the server reaches this console
        // without a change here. The plan's own fixture used 'REFUSED', which
        // is not one of the constants -- so it is the case, not a typo.
        expect(() => describeEnding('REFUSED')).not.toThrow()
        expect(describeEnding('REFUSED')).toBe('ended REFUSED')
        expect(describeEnding('SOMETHING_LATER')).toBe('ended SOMETHING_LATER')
    })

    it('treats an ending that names a property of every object as unknown', () => {
        // A plain lookup answers a function for these, and the function would
        // reach textContent and render its own source on the page. Object.hasOwn
        // is what makes the table a table.
        expect(describeEnding('constructor')).toBe('ended constructor')
        expect(describeEnding('toString')).toBe('ended toString')
        expect(describeEnding('__proto__')).toBe('ended __proto__')
    })

    it('says the server did not say, rather than inventing an ending', () => {
        expect(describeEnding(undefined)).toContain('did not say')
        expect(describeEnding('')).toContain('did not say')
    })
})

describe('describeCost', () => {
    it('renders no measurement as no measurement and never as zero', () => {
        // A turn that never reached a model call has no measurement. Rendering
        // the absence as 0 states the opposite of what happened: that a prompt
        // was measured and found to be free.
        expect(describeCost(null)).toBe('prompt not measured')
        expect(describeCost(undefined)).toBe('prompt not measured')
        expect(describeCost(null)).not.toContain('0')
    })

    it('renders a measurement, and counts one of them in the singular', () => {
        expect(describeCost(1)).toBe('1 prompt token')
        expect(describeCost(2048)).toBe('2048 prompt tokens')
    })
})

describe('transcript', () => {
    it('renders what the server said, so a reload is not amnesia', () => {
        const root = into(transcript([turn({ utterance: 'first question' })], []))

        expect(root.textContent).toContain('first question')
        expect(root.textContent).toContain('an answer')
        expect(root.querySelector('[data-role="utterance"]')?.textContent)
            .toContain('first question')
    })

    it('puts the seam at the turn the fold reaches through', () => {
        // Slice 3e made the fold visible on purpose: a transcript that hid it
        // would be a continuous history that quietly lost its middle. What is
        // above the marker is what was folded; what is below it is what the
        // next turn was shown verbatim, and every folded turn is still there.
        const turns = [
            turn({ ordinal: 1, utterance: 'one' }),
            turn({ ordinal: 2, utterance: 'two' }),
            turn({ ordinal: 3, utterance: 'three' }),
        ]
        const folds: CompactionView[] = [{ throughOrdinal: 2, summary: 'what was said before' }]
        const root = into(transcript(turns, folds))

        const seam = root.querySelector('[data-seam]')
        expect(seam).not.toBeNull()
        expect(seam?.getAttribute('data-seam')).toBe('2')
        expect(seam?.textContent).toContain('what was said before')

        const children = [...root.children]
        const at = (ordinal: string, role: string): number => children.findIndex(
            (node) => node.getAttribute('data-ordinal') === ordinal
                && node.getAttribute('data-role') === role,
        )
        expect(children.indexOf(seam as Element)).toBeGreaterThan(at('2', 'answer'))
        expect(children.indexOf(seam as Element)).toBeLessThan(at('3', 'utterance'))
        // The folded turns are not removed and not replaced by the summary.
        expect(root.textContent).toContain('one')
        expect(root.textContent).toContain('two')
    })

    it('keeps a fold that reaches past every turn it was given', () => {
        // Not expected from this server, and dropping it silently would be the
        // one failure mode a seam exists to prevent.
        const root = into(transcript([turn({ ordinal: 1 })], [{ throughOrdinal: 9, summary: 'f' }]))
        expect(root.querySelector('[data-seam="9"]')).not.toBeNull()
    })

    it('orders by ordinal rather than by the order the answer arrived in', () => {
        const root = into(transcript(
            [turn({ ordinal: 2, utterance: 'second' }), turn({ ordinal: 1, utterance: 'first' })],
            [],
        ))
        const said = [...root.querySelectorAll('[data-role="utterance"]')]
            .map((node) => node.textContent)
        expect(said[0]).toContain('first')
        expect(said[1]).toContain('second')
    })

    it('shows a turn nothing measured as unmeasured, never as zero', () => {
        // The plan's own fixture, which is also a turn missing utterance and
        // answer: api.get does not validate a shape, and a screen should render
        // a missing field as missing rather than throw over the whole page.
        const partial = [{ ordinal: 1, promptTokens: null, ending: 'REFUSED' } as unknown as
            TurnView]
        const root = into(transcript(partial, []))

        expect(root.textContent).not.toContain('0 tokens')
        expect(root.textContent).not.toContain('0 prompt')
        expect(root.textContent).toContain('prompt not measured')
        expect(root.textContent).toContain('ended REFUSED')
    })

    it('renders every turn with an ending, so a truncation is never dressed as a reply', () => {
        const root = into(transcript([turn({ ending: 'TURN_CAP', answer: 'partial' })], []))
        expect(root.querySelector('[data-role="answer"] .ending')?.textContent)
            .toContain('turn cap')
    })
})

describe('who said it', () => {
    it('renders a runtime note as the runtime’s and not as the person’s', () => {
        const root = document.createElement('div')
        root.append(renderEntry({ role: 'runtime', text: 'the stream dropped events' }))

        const line = root.querySelector('[data-role="runtime"]')
        expect(line).not.toBeNull()
        expect(line?.querySelector('.who')?.textContent).toBe('runtime')
        expect(root.querySelector('[data-role="utterance"]')).toBeNull()
    })

    it('still renders text that reads like a runtime note as the person’s own', () => {
        // The inverse failure, and the one this wire can actually produce: the
        // transcript endpoint carries no runtime notes -- they live only in the
        // in-run message list and are never persisted -- so every utterance it
        // answers with is a person's, including one that quotes the runtime
        // word for word. A renderer that decided the role by reading the text
        // would put this console's voice on something somebody typed.
        const root = into(transcript([turn({ utterance: RUNTIME_WORDING })], []))

        const line = root.querySelector('[data-role="utterance"]')
        expect(line?.querySelector('.who')?.textContent).toBe('you')
        expect(line?.textContent).toContain('Runtime note')
        expect(root.querySelector('[data-role="runtime"]')).toBeNull()
    })

    it('gives each source its own gutter word', () => {
        const roots = [
            renderEntry({ role: 'utterance', text: 'x', ordinal: null }),
            renderEntry({
                role: 'answer', text: 'x', ending: 'ANSWERED', promptTokens: 1, ordinal: null,
            }),
            renderEntry({ role: 'tool', tool: 'file_read', agent: 'interlocutor' }),
            renderEntry({ role: 'runtime', text: 'x' }),
            renderEntry({ role: 'seam', throughOrdinal: 1, summary: 'x' }),
        ]
        expect(roots.map((node) => node.querySelector('.who')?.textContent))
            .toEqual(['you', 'agent', 'tool', 'runtime', 'folded'])
    })
})

describe('rendering is escaping', () => {
    it('lands a payload in a tool result in the DOM as text and not as an element', () => {
        // Asserted on the rendered node rather than on a string, which is the
        // only assertion that distinguishes escaped-then-parsed from never
        // parsed at all. A tool result reaches this screen through the answer:
        // JobEvent carries no tool output by signature, so what a tool returned
        // arrives in what the agent then said about it.
        const payload = '<img src=x onerror=alert(1)>'
        const root = into(transcript(
            [turn({ utterance: payload, answer: `the file said ${payload}` })],
            [{ throughOrdinal: 1, summary: payload }],
        ))

        expect(root.querySelector('img')).toBeNull()
        expect(root.getElementsByTagName('*').length).toBeGreaterThan(0)
        expect(root.querySelector('[data-role="answer"] .body')?.textContent)
            .toBe(`the file said ${payload}`)
        expect(root.querySelector('[data-role="utterance"] .body')?.textContent).toBe(payload)
        expect(root.querySelector('[data-seam] .body')?.textContent).toBe(payload)
        // And not double-encoded on the way in, which is the other way to get
        // this wrong: the person must read what the file said, entities and all.
        expect(root.textContent).not.toContain('&lt;')
    })

    it('lands a payload in an agent or tool name in the DOM as text', () => {
        const root = document.createElement('div')
        root.append(renderEntry({
            role: 'tool',
            tool: '<script>alert(1)</script>',
            agent: '<b>not an agent</b>',
        }))
        expect(root.querySelector('script')).toBeNull()
        expect(root.querySelector('b')).toBeNull()
        expect(root.textContent).toContain('<script>alert(1)</script>')
    })

    it('has no renderer anywhere in this console that reaches for innerHTML', () => {
        // The rule stated as a property of the source rather than as a habit at
        // the call sites. escape.ts's own javadoc is the reason there is no
        // escape() call to find in a renderer either: these modules build DOM,
        // and an escape on the way into textContent would double-encode rather
        // than defend.
        //
        // EXTENDED rather than duplicated when the four screens arrived. A
        // second copy of this check under src/screens would be a check that
        // stops covering whatever directory is added next; walking the tree
        // from src/ covers every file this console has and every file it grows.
        const offenders = renderers().filter(
            (file) => /innerHTML|outerHTML|insertAdjacentHTML/.test(sourceOf(file)))
        expect(offenders).toEqual([])
        // The check can still see something, or it is checking nothing -- and
        // it can see past this file's own directory, which is the half the
        // screens added.
        expect(renderers().length).toBeGreaterThan(0)
        expect(renderers().some((file) => file.includes('/screens/'))).toBe(true)
        expect(/innerHTML/.test(sourceOf(join(HERE, 'render.ts')) + 'innerHTML')).toBe(true)
    })

    it('has no renderer that escapes a value on its way into textContent', () => {
        // escape.ts's own javadoc: "If a caller finds itself reaching for
        // escape AND innerHTML, the answer is textContent, and the escaping was
        // the wrong half of the fix." With innerHTML already forbidden above,
        // an escape() inside a renderer has nothing left to defend and one
        // thing left to do, which is to double-encode -- `a & b` reaching the
        // page as `a &amp; b`. That is a visible bug in somebody else's text,
        // not defence in depth, so it is checked rather than remembered.
        //
        // escape.ts itself is exempt: it is where the function is defined, and
        // it is not a renderer.
        const offenders = renderers()
            .filter((file) => !file.endsWith('escape.ts'))
            .filter((file) => /\bescape\s*\(/.test(sourceOf(file)))
        expect(offenders).toEqual([])
    })
})

describe('a long body', () => {
    it('is clamped behind a button rather than left to push the prompt away', () => {
        const long = 'x'.repeat(4000)
        const root = document.createElement('div')
        root.append(renderEntry({ role: 'utterance', text: long, ordinal: null }))

        const pre = root.querySelector('pre.body') as HTMLElement
        expect(pre.dataset['clamped']).toBe('true')
        const more = root.querySelector('button.more') as HTMLButtonElement
        expect(more.textContent).toBe('show all')
        more.click()
        expect(pre.dataset['clamped']).toBe('false')
        expect(more.textContent).toBe('show less')
        // The whole of it is in the DOM either way; the clamp is a height and
        // never a truncation of what somebody is being shown.
        expect(pre.textContent).toHaveLength(long.length)
    })

    it('leaves a short body alone', () => {
        const root = document.createElement('div')
        root.append(renderEntry({ role: 'utterance', text: 'brief', ordinal: null }))
        expect(root.querySelector('button.more')).toBeNull()
        expect((root.querySelector('pre.body') as HTMLElement).dataset['clamped'])
            .toBeUndefined()
    })
})

describe('markdown, and who gets it', () => {
    const LIST = '- ask once\n- read what came back'

    it('renders an agent’s answer as the markdown it was written in', () => {
        // Every shipped agent writes it -- close_reader answers in numbered
        // steps -- and a scrollback showing `1.` and `**` is rendering the
        // source of the answer rather than the answer.
        const root = into(transcript([turn({ answer: `**do this**\n\n${LIST}` })], []))
        const said = root.querySelector('[data-role="answer"]')

        expect(said?.querySelector('strong')?.textContent).toBe('do this')
        expect([...(said?.querySelectorAll('li') ?? [])].map((node) => node.textContent))
            .toEqual(['ask once', 'read what came back'])
    })

    it('leaves what the person typed exactly as the person typed it', () => {
        // The file's own rule about roles, applied to formatting: reflowing
        // somebody's own words is a quieter way of putting words in their
        // mouth, and a person who typed a hyphen typed a hyphen.
        const root = into(transcript([turn({ utterance: LIST })], []))
        const typed = root.querySelector('[data-role="utterance"]')

        expect(typed?.querySelector('li')).toBeNull()
        expect(typed?.querySelector('pre.body')?.textContent).toBe(LIST)
    })

    it('renders a fold’s summary as prose, because a model wrote it too', () => {
        const root = into(transcript([turn({})], [{ throughOrdinal: 1, summary: LIST }]))
        expect(root.querySelectorAll('[data-seam] li').length).toBe(2)
    })

    it('leaves this console’s own observations unformatted', () => {
        // A runtime note and a refusal are the console's own words, and it does
        // not write markdown. Reading marks in one would mean the console
        // reformatting a sentence it wrote itself.
        const root = document.createElement('div')
        root.append(
            renderEntry({ role: 'runtime', text: LIST }),
            renderEntry({ role: 'refusal', text: LIST }),
        )
        expect(root.querySelector('li')).toBeNull()
        expect(root.querySelectorAll('pre.body').length).toBe(2)
    })

    it('still clamps an answer that is too long to let the prompt stay put', () => {
        // The clamp measures the source, not the rendering: what makes a body
        // too long is how much of it there is, and a person still has to be
        // able to see it all.
        const long = `- ${'a'.repeat(CLAMP_AT)}\n- and another`
        const root = into(transcript([turn({ answer: long })], []))
        const said = root.querySelector('[data-role="answer"]')

        expect(said?.querySelector('.body')?.getAttribute('data-clamped')).toBe('true')
        const more = said?.querySelector('.more') as HTMLButtonElement
        expect(more.textContent).toBe('show all')
        more.click()
        expect(said?.querySelector('.body')?.getAttribute('data-clamped')).toBe('false')
        expect(more.textContent).toBe('show less')
    })
})

function approvalView(over: Partial<ApprovalView> = {}): ApprovalView {
    return {
        id: 'apr_1', conversation: 'conv-a', agent: 'builder', side: 'server',
        command: ['./gradlew', 'test', '--tests', 'Foo'], cwd: '/repo', reason: 'runs the build',
        state: 'asked', scope: null, prefix: null, defaultPrefix: ['./gradlew', 'test'],
        createdAt: '2026-09-15T10:00:00Z', answeredAt: null, ...over,
    }
}

function selected(block: HTMLElement): string[] {
    return [...block.querySelectorAll<HTMLElement>('[data-chip]')]
        .filter((chip) => chip.dataset['selected'] === 'true')
        .map((chip) => chip.textContent ?? '')
}

describe('a question a run asked', () => {
    it('shows the command, its side, its directory and its reason, and four answers', () => {
        const block = renderApproval(approvalView(), vi.fn())

        expect(block.dataset['approval']).toBe('apr_1')
        expect(block.querySelector('[data-approval-command]')?.textContent)
            .toBe('./gradlew test --tests Foo')
        expect(block.querySelector('[data-detail="side"] .value')?.textContent).toBe('server')
        expect(block.querySelector('[data-detail="cwd"] .value')?.textContent).toBe('/repo')
        expect(block.querySelector('[data-detail="reason"] .value')?.textContent).toBe('runs the build')
        expect([...block.querySelectorAll('[data-decision]')].map((b) => b.textContent)).toEqual([
            'Allow once', 'Allow for this conversation', 'Allow for project…', 'Deny',
        ])
        expect((block.querySelector('[data-prefix]') as HTMLElement).hidden).toBe(true)
    })

    it('says no reason was given rather than drawing an empty one', () => {
        const block = renderApproval(approvalView({ reason: null }), vi.fn())

        expect(block.querySelector('[data-detail="reason"] .value')?.textContent).toMatch(/none given/)
    })

    it('lands a payload in a command as text', () => {
        const block = renderApproval(approvalView({ command: ['echo', '<img src=x onerror=alert(1)>'] }),
            vi.fn())

        expect(block.querySelector('img')).toBeNull()
        expect(block.textContent).toContain('<img src=x onerror=alert(1)>')
    })

    it('opens the prefix chips on the default prefix, and chip i selects arguments 0..i', () => {
        const answer = vi.fn()
        const block = renderApproval(approvalView(), answer)
        ;(block.querySelector('[data-decision="project"]') as HTMLButtonElement).click()

        expect((block.querySelector('[data-prefix]') as HTMLElement).hidden).toBe(false)
        expect(selected(block)).toEqual(['./gradlew', 'test'])
        expect(answer).not.toHaveBeenCalled()

        ;(block.querySelector('[data-chip="2"]') as HTMLButtonElement).click()
        expect(selected(block)).toEqual(['./gradlew', 'test', '--tests'])

        ;(block.querySelector('[data-chip="0"]') as HTMLButtonElement).click()
        expect(selected(block)).toEqual(['./gradlew'])
    })

    it('confirms the chosen prefix as a project answer', async () => {
        const answer = vi.fn(async (): Promise<ApprovalResult> => ({ answered: true, note: null }))
        const block = renderApproval(approvalView(), answer)
        ;(block.querySelector('[data-decision="project"]') as HTMLButtonElement).click()
        ;(block.querySelector('[data-chip="3"]') as HTMLButtonElement).click()
        ;(block.querySelector('[data-confirm-prefix]') as HTMLButtonElement).click()

        expect(answer).toHaveBeenCalledWith('project', ['./gradlew', 'test', '--tests', 'Foo'])
        await vi.waitFor(() => expect(block.dataset['answered']).toBe('project'))
    })

    it('disables every control once answered, and shows a note the answer came back with', async () => {
        const answer = vi.fn(async (): Promise<ApprovalResult> =>
            ({ answered: true, note: 'a turn is already in flight' }))
        const block = renderApproval(approvalView(), answer)
        ;(block.querySelector('[data-decision="once"]') as HTMLButtonElement).click()

        expect(answer).toHaveBeenCalledWith('once', null)
        await vi.waitFor(() => expect(block.dataset['answered']).toBe('once'))
        expect([...block.querySelectorAll('button')].every((b) => (b as HTMLButtonElement).disabled))
            .toBe(true)
        expect(block.querySelector('[data-approval-note]')?.textContent).toBe('a turn is already in flight')
    })

    it('gives the buttons back when the answer was not taken', async () => {
        const answer = vi.fn(async (): Promise<ApprovalResult> =>
            ({ answered: false, note: 'the event socket is not open' }))
        const block = renderApproval(approvalView(), answer)
        const deny = block.querySelector('[data-decision="deny"]') as HTMLButtonElement
        deny.click()
        expect(deny.disabled).toBe(true)

        await vi.waitFor(() => expect(deny.disabled).toBe(false))
        expect(block.dataset['answered']).toBeUndefined()
        expect(block.querySelector('[data-approval-note="refused"]')?.textContent).toContain('not open')
    })
})

describe('the prefix a project approval starts on', () => {
    it('is the server’s suggestion when it leads the command', () => {
        expect(startingPrefix(['git', 'push', 'origin'], ['git', 'push'])).toEqual(['git', 'push'])
    })

    it('is the program alone when the suggestion does not lead the command', () => {
        expect(startingPrefix(['git', 'push'], ['npm', 'test'])).toEqual(['git'])
        expect(startingPrefix(['git'], ['git', 'push'])).toEqual(['git'])
        expect(startingPrefix([], ['git'])).toEqual([])
    })
})
