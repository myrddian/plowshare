import { describe, expect, it, vi } from 'vitest'
import { createMemory } from './memory'
import type { Transport } from './screen'

describe('memory digest controls', () => {
    it('shows source level and incompleteness as text', async () => {
        const post = vi.fn(async () => ({level: 'fold_summary', ids: ['dig_1'], complete: false,
            text: '<script>historical evidence</script>'}))
        const root=document.createElement('div')
        createMemory({root, project: 'payments', transport: {get: vi.fn(), post} as unknown as Transport})
        const question=root.querySelector<HTMLInputElement>('input[data-input="question"]')!
        question.value='why retries'
        const go=Array.from(root.querySelectorAll('button')).find(b=>b.textContent==='navigate history')!
        go.click()
        await vi.waitFor(()=>expect(root.textContent).toContain('complete=false'))
        expect(post).toHaveBeenCalledWith('/v1/memories/navigate', {project:'payments',question:'why retries'})
        expect(root.textContent).toContain('dig_1')
        expect(root.querySelector('script')).toBeNull()
    })
    it('starts a build job and does not paint a result after unmount', async () => {
        let finish!: (v: {id: string})=>void
        const post=vi.fn(()=>new Promise(resolve=>{finish=resolve}))
        const root=document.createElement('div')
        const screen=createMemory({root,transport:{get:vi.fn(),post} as unknown as Transport})
        Array.from(root.querySelectorAll('button')).find(b=>b.textContent==='build digests')!.click()
        expect(post).toHaveBeenCalledWith('/v1/memories/digest',{project:null})
        screen.destroy();finish({id:'late-job'});await Promise.resolve()
        expect(root.textContent).not.toContain('late-job')
    })
})
