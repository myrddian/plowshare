/**
 * The three Node functions the source-level check in `render.test.ts` needs,
 * declared rather than depended on.
 *
 * `tsconfig.json` sets `"types": []`, so nothing ambient is in scope and
 * `node:fs` has no declaration -- measured, as four `TS2307`s and three
 * `TS7006`s the first time that test type-checked. The alternatives were
 * adding `@types/node` to a module whose whole dependency list is four tools,
 * or dropping a check on a rule that is a property of the source and cannot be
 * asserted any other way. This is the smallest of the three: the declarations
 * are narrowed to exactly the calls that test makes, so they cannot quietly
 * start standing in for the rest of Node.
 *
 * Types only. Nothing here reaches the bundle, and nothing outside a test may
 * import these -- a console that could read the filesystem is not a console.
 */
declare module 'node:fs' {
    export function readFileSync(path: string, encoding: 'utf8'): string
    export function readdirSync(path: string): string[]

    /**
     * The two members of Node's `Dirent` that the source-level guard reads.
     *
     * It walks the whole of `src/` rather than one directory, which is what
     * makes the guard cover a directory added later instead of only the one it
     * was written in; telling a directory from a file is the only extra fact
     * that walk needs.
     */
    export interface DirectoryEntry {
        readonly name: string
        isDirectory(): boolean
    }

    export function readdirSync(
        path: string, options: { withFileTypes: true }): DirectoryEntry[]
}

declare module 'node:path' {
    export function join(...parts: string[]): string
    export function dirname(path: string): string
}

declare module 'node:url' {
    export function fileURLToPath(url: string): string
}
