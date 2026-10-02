import { execFileSync } from 'node:child_process'
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'
import { describe, expect, it } from 'vitest'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const directories = ['binding', 'jobs', 'operations']

describe('the independently installable neutral package', () => {
    it('has no runtime dependencies and imports only other neutral binding modules', () => {
        const manifest = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'))
        expect(Object.keys(manifest.dependencies ?? {})).toEqual([])
        const sources = directories.flatMap(directory => readdirSync(join(root, 'src', directory))
            .filter(name => name.endsWith('.ts') && !name.endsWith('.test.ts'))
            .map(name => `${directory}/${name}`))
        expect(sources.length).toBeGreaterThan(0)
        for (const name of sources) {
            const source = ts.createSourceFile(name, readFileSync(join(root, 'src', name), 'utf8'),
                ts.ScriptTarget.Latest, true)
            function visit(node: ts.Node): void {
                if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) && node.moduleSpecifier) {
                    const specifier = node.moduleSpecifier
                    expect(ts.isStringLiteral(specifier)).toBe(true)
                    if (ts.isStringLiteral(specifier)) {
                        expect(specifier.text, name).toMatch(/^\.{1,2}\//)
                        const target = resolve(root, 'src', dirname(name), specifier.text)
                        expect(target.startsWith(join(root, 'src') + '/'), name).toBe(true)
                        expect(target.endsWith('.ts'), name).toBe(true)
                    }
                }
                if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword) {
                    throw new Error(`${name}: dynamic imports require a neutrality review`)
                }
                ts.forEachChild(node, visit)
            }
            visit(source)
        }
    })

    it('exports runnable JavaScript without loading source TypeScript or a frontend', () => {
        const names = readdirSync(join(root, 'src/binding')).filter(name => name.endsWith('.ts') && !name.endsWith('.test.ts'))
            .map(name => `binding/${name.slice(0, -3)}`)
        names.push('jobs', ...readdirSync(join(root, 'src/operations'))
            .filter(name => name.endsWith('.ts') && !name.endsWith('.test.ts'))
            .map(name => `operations/${name.slice(0, -3)}`))
        const output = execFileSync(process.execPath, ['--input-type=module', '-e', `
            for (const name of ${JSON.stringify(names)}) {
                const specifier = 'plowshare-client-ts/' + name;
                if (!import.meta.resolve(specifier).endsWith('.js')) throw new Error(specifier);
                await import(specifier);
            }
            console.log('loaded');
        `], { cwd: root, encoding: 'utf8' })
        expect(output.trim()).toBe('loaded')
    })
})
