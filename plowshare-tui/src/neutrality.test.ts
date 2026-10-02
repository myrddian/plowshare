import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, relative, sep } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * The edges of this module, stated as a property of its source.
 *
 * Spec §3.1: `logic/` must be runtime-neutral, because the console is a browser
 * bundle and one `node:` import in here means it can never adopt this
 * directory. That failure is silent — nothing breaks, no test goes red, and it
 * is found by whoever tries a year from now with the module's shape long since
 * settled around the assumption. This is the scan that makes it loud.
 *
 * <b>WHY THIS EXISTS WHEN THE COMPILER ALREADY REFUSES ALL THREE, which is the
 * question that gets this file deleted if it is not answered here.</b> Task 1
 * measured the compiler on each violation: `node:fs` is TS2307, `process` is
 * TS2591, `document` is TS2584, a cross-directory import is TS6059 + TS6307.
 * All true, and all true only <b>for a build configured the way this one is
 * today</b>. Four edits restore what the design forbids without touching a line
 * of `logic/`:
 *
 *   - <b>Installing @types/node.</b> `types: []` stops a package being pulled
 *     in as ambient globals; it does NOT stop an explicit `import … from
 *     'node:fs'` resolving through it. That is exactly how the console's own
 *     tests import `node:fs` under `"types": []`. Task 5 installs @types/node
 *     for `view/`, and on that day TS2307 stops being the guard it is now.
 *     <b>THAT DAY WAS TASK 7 AND IT HAS HAPPENED.</b> The view needs
 *     `node:util`'s `styleText`, `node:readline` and a `WebSocket`, so
 *     `src/view/tsconfig.json` now carries `types: ["node"]` — and the package
 *     being on disk is all it takes. Measured on the day of that edit, not
 *     predicted: `import { readFileSync } from 'node:fs'` planted in
 *     `logic/markdown.ts` and used, `npx tsc -b --force` <b>exits 0</b>, where
 *     before it was TS2307 naming the file. The first assertion below then
 *     failed on its own with `logic/markdown.ts: a node: import (line 1)`.
 *     <b>This file is now the only thing standing between `logic/` and a Node
 *     import</b>, which is what it was written for and why deleting it as
 *     redundant would have been a mistake taken on evidence that has since
 *     expired. The other three rules keep both guards: `lib` is still ES2022
 *     everywhere but `platform/`, and the reference graph is untouched.
 *   - <b>Widening `lib`.</b> One `"DOM"` added to `src/logic/tsconfig.json` and
 *     TS2584 is gone.
 *   - <b>A `// @ts-expect-error`</b> over the import, which is one line and
 *     reads like diligence.
 *   - <b>Merging the three tsconfigs into one</b>, which someone will propose
 *     as simplification, and which deletes the reference graph that TS6059 is a
 *     consequence of.
 *
 * The first two are not hypothetical: TS2591's own hint text recommends
 * installing @types/node and adding it to `types`, and TS2584's recommends
 * adding `dom` to `lib`. The compiler tells you to make the edit that blinds
 * it. This scan reads the source text where the compiler reads the type graph,
 * and neither subsumes the other — `declare const process: never` gets past the
 * compiler, and a genuinely novel spelling of a Node reach gets past this.
 * <b>Two guards, and the cost of keeping the weaker-looking one is this file.</b>
 *
 * <b>WHY IT LIVES HERE AND NOT IN `logic/`</b>, where the plan first put it.
 * Two reasons, the second decisive:
 *
 *   1. It asserts over `binding/` as well as `logic/`. A file in `logic/`
 *      reading `binding/`'s tree is the very direction the third assertion
 *      below forbids; even as a test it reads as the thing being outlawed.
 *   2. <b>It imports `node:fs`.</b> `src/logic/tsconfig.json` includes every
 *      `.ts` file under it, tests included — `logic/session.test.ts` is
 *      compiled by `logic`'s project today — so this file in `logic/` would be
 *      a `node:`
 *      import inside the directory that may not have one. Making it compile
 *      would mean installing @types/node (which blinds TS2307 for every file in
 *      `logic/`, per above) or excluding test files from the project (which
 *      silently stops type-checking every logic test anyone writes after this).
 *      Both pay the property this file exists to protect, in order to house the
 *      file that protects it.
 *
 * `src/` is in none of the three projects' `include`, so `tsc -b` never
 * compiles this file. That is the price, and it is the right way round: the
 * scan is executed by vitest on every run, and what goes unchecked is one test
 * rather than a directory's worth of source.
 *
 * <b>The test may reach for `node:fs`; the code it scans may not.</b> There is
 * no contradiction and it is worth saying out loud, because it looks like one:
 * the scan is a development-time assertion that runs under vitest on Node and
 * will never be bundled anywhere, while `logic/` is the shipped surface the
 * console has to be able to import. `node:fs` is also what keeps the
 * zero-dependency posture — a globbing devDependency would have done this job
 * and is deliberately not installed.
 *
 * <b>MEASURED, one violation at a time, because a guard that has never refused
 * anything proves nothing</b> (the plan's step 2, and the standard every
 * mutation in this repository is held to). Each was planted on its own, `npx
 * vitest run` was run, exactly one of the three assertions failed naming the
 * file, and the plant was removed:
 *
 *   logic/wiring.ts   import { readFile } from 'node:fs'
 *                     -> "logic/wiring.ts: a node: import (line 1)"
 *   logic/wiring.ts   export const TITLE = document.title
 *                     -> "logic/wiring.ts: document (line 21)"
 *   logic/wiring.ts   import { greeting } from '../view/wiring.ts'
 *                     -> "logic/wiring.ts imports view/wiring.ts"
 *   binding/wiring.ts import { LAYER } from '../logic/wiring.ts'
 *                     -> "binding/wiring.ts imports logic/wiring.ts"
 *
 * The fourth is the half of the third rule the view import does not reach: the
 * two directories not importing <i>each other</i>, which is a different claim
 * from neither importing the view.
 *
 * <b>THE FOUR RULES A WHOLE-PLAN REVIEW ADDED WERE MEASURED THE SAME WAY</b>,
 * one plant at a time, `npx vitest run src/neutrality.test.ts` each time,
 * exactly one assertion failing and naming the file, plant removed:
 *
 *   logic/markdown.ts   export const BYTES = Buffer.alloc(1)
 *                       -> "logic/markdown.ts: Buffer (line 387)"
 *   logic/session.ts    export const SOON = setImmediate
 *                       -> "logic/session.ts: setImmediate (line 642)"
 *   logic/planted.d.ts  declare const process: { cwd(): string }
 *                       -> "logic/planted.d.ts: process (line 1)"
 *                          (a NEW FILE, and the plant that would have been
 *                          invisible an hour earlier: `.d.ts` was skipped)
 *   logic/wording.ts    import { x } from '@view/main.ts'
 *                       -> "logic/wording.ts imports the package "@view/main.ts""
 *   binding/auth.ts     import … from 'plowshare-tui/src/binding/connection.ts'
 *                       -> "binding/auth.ts imports the package "plowshare-…""
 *
 * <b>WHAT IS STILL OPEN, said plainly rather than left to be rediscovered.</b>
 * The review asked for the cheap holes closed and the rest recorded; these are
 * the rest:
 *
 *   - <b>a novel spelling of a reach</b>. This scan is regexes over text, so
 *     `globalThis['pro' + 'cess']` gets past it exactly as it always has. The
 *     compiler is the guard for anything that has to resolve, and the two
 *     together are the whole of it.
 *   - <b>`.test.ts` under `logic/` is not scanned</b>, and a `node:` import in
 *     one is caught by nothing. Accepted: a test is neither shipped nor
 *     adopted, and `logic/session.test.ts` imports `vitest`, which
 *     {@link reachingPackages} would refuse on sight.
 *   - <b>`view/` and `platform/` are not scanned at all</b>, by design — the
 *     view's whole job is knowing it is on a terminal — so a rule here says
 *     nothing about a file there.
 *
 * <b>The self-check was measured the same way</b>, by pointing `LOGIC` at an
 * empty directory: all three assertions went on passing — over nothing — and
 * only `toBeGreaterThan(0)` went red, in all three. That is the failure this
 * half exists for, reproduced, rather than asserted.
 */

const SRC = dirname(fileURLToPath(import.meta.url))
const LOGIC = join(SRC, 'logic')
const BINDING = join(SRC, '..', '..', 'plowshare-client-ts', 'src', 'binding')

/**
 * Every non-test `.ts` file under a directory: what these guards read.
 *
 * <b>`.d.ts` USED TO BE SKIPPED HERE AND IS NOT ANY MORE.</b> A whole-plan
 * review named it as a hole, and it is the worst-shaped kind: a declaration
 * file is precisely where an ambient Node global would be introduced, it is
 * what `skipLibCheck` already stops the compiler looking inside, and it ends in
 * `.ts` so nothing else about this scan would have noticed. There is none in
 * either directory today; the exclusion was protecting nothing and blinding the
 * scan to the one file type it most needed to read.
 *
 * <b>`.test.ts` is still skipped, and that one is deliberate rather than a
 * second hole.</b> What is being protected is that the console can adopt
 * `logic/`, and a console adopts nobody's tests — while `logic/session.test.ts`
 * imports `vitest`, which {@link reachingPackages} below would refuse on sight.
 * So the exclusion is the rule's scope and not a gap in it. The residual, said
 * plainly: <b>a `node:` import in a logic TEST is caught by nothing</b>, here
 * or in the compiler, and that is accepted because such a file is never
 * shipped and never adopted.
 */
function sourcesUnder(directory: string): string[] {
    return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
        const full = join(directory, entry.name)
        if (entry.isDirectory()) {
            return sourcesUnder(full)
        }
        return entry.name.endsWith('.ts') && !entry.name.endsWith('.test.ts') ? [full] : []
    })
}

/**
 * A source with its comments blanked out, line structure intact.
 *
 * Blanked rather than deleted so that the line number in a failure still points
 * at the offending line — a guard that says `logic/session.ts: process` and
 * nothing else makes you grep for it, and this file's whole job is to be acted
 * on rather than argued with.
 *
 * Stripping at all is copied from the console's scan, which failed until it did
 * this: the files under `logic/` state these rules by name in their own
 * javadoc, and a check that matched prose would be a check that forbids writing
 * the rule down. `logic/markdown.ts` says `node:`, `process` and
 * `document` in its header today, having replaced the `wiring.ts` that
 * said so when this was written.
 */
function withoutComments(source: string): string {
    return source
        .replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, ' '))
        .replace(/\/\/.*$/gm, '')
}

function codeOf(file: string): string {
    return withoutComments(readFileSync(file, 'utf8'))
}

interface Rule {
    readonly named: string
    readonly spotted: RegExp
}

/**
 * What `logic/` may not reach for, because a browser bundle has none of it.
 *
 * <b>`Buffer` and `setImmediate` were added after a whole-plan review noticed
 * they were missing</b>, and they are the two most likely of any name on this
 * list to be reached for by accident: `Buffer` is what somebody writes the
 * moment they think about bytes, and `setImmediate` is what somebody writes the
 * moment they want "later, but sooner than a timeout". Neither exists in a
 * browser. The compiler catches both today — `types: []` keeps them out of
 * `logic/`'s ambient globals, which is the half of that guard task 7 did NOT
 * expire — so this closes the gap between the two guards rather than opening a
 * new one, which is the point of having two.
 */
const RUNTIME: readonly Rule[] = [
    { named: 'a node: import', spotted: /\bnode:[a-z]/ },
    { named: 'process', spotted: /\bprocess\b/ },
    { named: '__dirname', spotted: /\b__dirname\b/ },
    { named: '__filename', spotted: /\b__filename\b/ },
    { named: 'require', spotted: /\brequire\b/ },
    { named: 'Buffer', spotted: /\bBuffer\b/ },
    { named: 'setImmediate', spotted: /\bsetImmediate\b/ },
    // THE FOUR BELOW WERE ADDED THE DAY THE COMPILER WAS RE-MEASURED AND FOUND
    // TO HAVE STOPPED REFUSING THEM. See `logic/tsconfig.json`: five of its six
    // documented probes now compile clean, because this project's `include` is
    // `**/*.ts` and that takes in the test files, whose `import … from 'vitest'`
    // pulls Node's ambient globals into the whole program. `types: []` cannot
    // stop that -- it governs automatic @types inclusion, not the globals a
    // .d.ts reached through an explicit import contributes.
    //
    // They are not Node-only names, which is the point: `fetch`, `WebSocket`
    // and the timers exist in a browser too. `logic/` may not reach for them
    // ANYWHERE, because a module that acquires I/O is not runtime-neutral even
    // when the I/O happens to be portable.
    { named: 'fetch', spotted: /\bfetch\s*\(/ },
    { named: 'WebSocket', spotted: /\bWebSocket\b/ },
    { named: 'setTimeout', spotted: /\bsetTimeout\b/ },
    { named: 'setInterval', spotted: /\bsetInterval\b/ },
]

/** What `logic/` may not touch, because a terminal has none of it. */
const DOM: readonly Rule[] = [
    // A quoted wire kind ('document') is data, not a reference to the DOM global.
    { named: 'document', spotted: /(?<!['"])\bdocument\b(?!['"])/ },
    { named: 'window', spotted: /\bwindow\b/ },
    { named: 'HTMLElement', spotted: /\bHTMLElement\b/ },
    { named: 'DocumentFragment', spotted: /\bDocumentFragment\b/ },
]

/** Pure over source text, so the matcher itself can be tested without a file. */
function breachesIn(code: string, rules: readonly Rule[]): string[] {
    return code.split('\n').flatMap((line, index) => rules
        .filter((rule) => rule.spotted.test(line))
        .map((rule) => `${rule.named} (line ${index + 1})`))
}

function offendersUnder(directory: string, rules: readonly Rule[]): string[] {
    return sourcesUnder(directory).flatMap((file) => breachesIn(codeOf(file), rules)
        .map((breach) => `${relative(SRC, file)}: ${breach}`))
}

/**
 * The three shapes an import specifier takes, and nothing else.
 *
 * <h2>What the one regex that stood here matched by accident</h2>
 *
 * <p>It was `/(?:from|import)\s*\(?\s*['"]([^'"]+)['"]/g` — the word `from`
 * anywhere, followed by a quote — and <b>`binding/envelope.ts` line 137 is a
 * sentence that ends in it</b>:
 *
 * <pre>
 *   throw new Error('a response carried no { code } payload to read an outcome from')
 * </pre>
 *
 * <p>`from'` at the end of that string matched, and the specifier it captured
 * was everything up to the <i>next</i> quote in the file — several lines of
 * source. This went unnoticed for as long as it did because the only caller,
 * {@link reachesOutOf}, filtered to specifiers starting with `.` and threw that
 * nonsense away unread. {@link reachingPackages} does not throw anything away,
 * so the first thing the stronger rule found was not a violation in `logic/` at
 * all — it was this. Worth recording as its own small lesson: <b>a guard whose
 * output is filtered before it is read can be wrong for years</b>, and the day
 * somebody widens it, the widening gets blamed.
 *
 * <p>So the matcher is now anchored to where an import can actually be. The
 * first two require a line to <i>begin</i> with the keyword, which is where
 * `isolatedModules` and `verbatimModuleSyntax` already require every static
 * import to live; the third is the one form that is an expression and may be
 * anywhere. `[^'"]` crosses newlines — a clause spread over several lines is
 * one specifier, which this module's own style produces — but it cannot cross a
 * string, so the `from` any of them finds belongs to the statement that started
 * the line and not to some sentence further down. `[\s}]` before `from` is what
 * keeps `Array.from('x')` out of it.
 */
const SPECIFIERS: readonly RegExp[] = [
    /^[ \t]*(?:import|export)\b[^'";]*?[\s}]from\s*['"]([^'"]+)['"]/gm,
    /^[ \t]*import\s+['"]([^'"]+)['"]/gm,
    /\bimport\s*\(\s*['"]([^'"]+)['"]/g,
]

/** Every specifier in a source, comments already blanked. */
function specifiersIn(code: string): string[] {
    return SPECIFIERS.flatMap((shape) => [...code.matchAll(shape)].map((found) => found[1] ?? ''))
}

function importsOf(file: string): string[] {
    return specifiersIn(codeOf(file))
}

function isUnder(path: string, directory: string): boolean {
    return path === directory || path.startsWith(directory + sep)
}

/**
 * Every relative import in `home` that lands outside `home`.
 *
 * Resolved against the filesystem rather than matched as a string, so that
 * `../view/x.ts` and `../../src/view/x.ts` are the same violation, and so that
 * a fourth directory added to `src/` is covered on the day it appears rather
 * than on the day someone remembers to add it to a list here.
 *
 * <b>It reads RELATIVE specifiers only, which is a hole {@link reachingPackages}
 * closes rather than one this function grew to cover.</b> The two questions are
 * different: this one is "does this path leave the directory", which needs a
 * path to resolve, and that one is "is this a path at all".
 */
function reachesOutOf(home: string): string[] {
    return sourcesUnder(home).flatMap((file) => importsOf(file)
        .filter((specifier) => specifier.startsWith('.'))
        .map((specifier) => join(dirname(file), specifier))
        .filter((target) => !isUnder(target, home))
        .map((target) => `${relative(SRC, file)} imports ${relative(SRC, target)}`))
}

/**
 * Every import in `home` that is not a relative path.
 *
 * <h2>The hole this closes, which a whole-plan review found</h2>
 *
 * <p>{@link reachesOutOf} filters to specifiers starting with `.` and then
 * resolves them, so <b>everything that is not a relative path was silently
 * exempt</b> from the only guard this module has left. Three shapes get through
 * that gap and all three are reachable by ordinary means:
 *
 * <ul>
 * <li>a <b>path alias</b> — `paths: { "@view/*": … }` added to a tsconfig, and
 *     `import { x } from '@view/main.ts'` in `logic/` reads as tidy;
 * <li>a <b>self-referencing package import</b> — `plowshare-tui/src/view/…`,
 *     which node resolves through `package.json`'s own name;
 * <li>a <b>dependency</b>, which this module has none of and must go on having
 *     none of (client design §6), and which `reachesOutOf` would not have
 *     mentioned either.
 * </ul>
 *
 * <p><b>An allowlist of none, rather than a list of forbidden prefixes.</b>
 * Every import in these two directories today is relative — measured, and it is
 * a property worth keeping rather than a coincidence: `logic/` has three
 * imports and `binding/` has four, all of them siblings. So the rule can be the
 * strong one. A list of bad prefixes would have to grow a line each time
 * somebody invents a new way to spell a reach, which is the shape of guard that
 * is always one entry behind.
 *
 * <p><b>It subsumes the `node:` rule for imports and does not replace it.</b>
 * `RUNTIME`'s first entry matches `node:` anywhere in a line, including in a
 * string that is not an import; this matches any specifier that is not a path,
 * including ones with no colon in them. Neither contains the other, and the
 * overlap is two guards agreeing rather than one being redundant.
 */
// Package exceptions are the extracted pure request/response and union helpers.
// Transport and dispatch remain in view/, and core has its own AST neutrality guard.
function reachingPackages(home: string): string[] {
    return sourcesUnder(home).flatMap((file) => importsOf(file)
        .filter((specifier) => !specifier.startsWith('.') && !(home === LOGIC && (
            ['session', 'response', 'union', 'views', 'activity', 'records', 'inspection', 'pace', 'board', 'board-demo', 'clean', 'markdown', 'swarm', 'tints', 'trajectory', 'information', 'usage', 'usage-command', 'usage-presentation', 'reference-cost'].some(name => specifier === `plowshare-client-ts/operations/${name}`)
            || specifier === 'plowshare-client-ts/binding/job-view')))
        .map((specifier) => `${relative(SRC, file)} imports the package "${specifier}"`))
}

describe('logic/ is runtime-neutral', () => {
    it('has no file in logic/ that reaches for node, process or require', () => {
        expect(offendersUnder(LOGIC, RUNTIME)).toEqual([])
        // The scan can still see something, or it is asserting over nothing and
        // passing vacuously. This half is not decoration: it is the console's
        // idiom and it is what the Java invariant
        // `the_guard_can_see_the_files_it_asserts_over` exists because of.
        // Task 3 deleted the `wiring.ts` this line was written against and
        // put `markdown.ts` in its place. The line is what would have turned
        // that deletion into a red suite rather than into a guard that
        // quietly stopped guarding, had the replacement not arrived with it.
        //
        // Task 7 deleted `binding/wiring.ts`, the last of that scaffolding,
        // and re-measured BOTH self-checks rather than assuming them: pointed
        // at an empty directory, `LOGIC` failed all three assertions and
        // `BINDING` failed the third, each with "expected 0 to be greater than
        // 0" and none of the rule assertions going red — which is the whole
        // point, because a scan over nothing passes every rule it has.
        expect(sourcesUnder(LOGIC).length).toBeGreaterThan(0)
    })

    it('has no file in logic/ that touches a DOM global', () => {
        expect(offendersUnder(LOGIC, DOM)).toEqual([])
        expect(sourcesUnder(LOGIC).length).toBeGreaterThan(0)
    })
})

describe('the dependencies run one way', () => {
    it('has no file in logic/ or binding/ that imports the view or each other', () => {
        // Spec §3: view/ -> binding/ and logic/; neither of those imports the
        // other or the view. The second half is what stops logic/ acquiring a
        // runtime transitively — a logic module that imported the binding would
        // pass the two assertions above and still be unadoptable, because the
        // socket it pulled in is as absent from a browser bundle as `node:fs`.
        expect(reachesOutOf(LOGIC)).toEqual([])
        expect(reachesOutOf(BINDING)).toEqual([])
        expect(sourcesUnder(LOGIC).length).toBeGreaterThan(0)
        expect(sourcesUnder(BINDING).length).toBeGreaterThan(0)
    })

    it('allows only sibling paths and the extracted neutral request builders', () => {
        // The half the assertion above could not see: it resolves relative
        // specifiers and exempts everything else, so a path alias, a
        // self-referencing package import, or a dependency all walked past it.
        // See `reachingPackages`. Zero runtime dependencies (client design §6)
        // is what makes the strong form of this rule available: there is
        // nothing in either directory that a relative path cannot name.
        expect(reachingPackages(LOGIC)).toEqual([])
        expect(reachingPackages(BINDING)).toEqual([])
        expect(sourcesUnder(LOGIC).length).toBeGreaterThan(0)
        expect(sourcesUnder(BINDING).length).toBeGreaterThan(0)
    })
})

describe('the scan itself', () => {
    it('matches what it claims to, and is not fooled by prose about it', () => {
        // The matcher tested directly, which the planted violations cannot do
        // for the cases nobody thought to plant. Synthetic on purpose: a
        // control that reads a named real file breaks the day task 3 deletes
        // `wiring.ts`, and would fail for a reason that has nothing to do with
        // what it checks.
        //
        // The comment cases are the ones that earn their keep. Blanking rather
        // than deleting is what keeps the reported line number the line you go
        // to, and stripping at all is what lets `logic/` state these rules in
        // its own javadoc — which it does today, so the two assertions above
        // are themselves the standing proof that stripping works on a real
        // file: break it and they go red over prose.
        expect(breachesIn("import { readFile } from 'node:fs'", RUNTIME))
            .toEqual(['a node: import (line 1)'])
        expect(breachesIn(withoutComments('// process, node:fs, __dirname'), RUNTIME))
            .toEqual([])
        const commented = '/* node:fs\n   process */\nconst a = document.title'
        expect(breachesIn(withoutComments(commented), DOM)).toEqual(['document (line 3)'])
    })

    it('allows the quoted board message kind while still detecting DOM references', () => {
        expect(breachesIn("const message = { kind: 'document' }", DOM)).toEqual([])
        expect(breachesIn('const message = { kind: "document" }', DOM)).toEqual([])
        for (const code of ['document.title', 'const dom = document', 'document["title"]', '${document.title}']) {
            expect(breachesIn(code, DOM)).toEqual(['document (line 1)'])
        }
    })

    it('reads the three shapes an import takes, over as many lines as it takes', () => {
        expect(specifiersIn("import { isCode } from './codes.ts'")).toEqual(['./codes.ts'])
        expect(specifiersIn("import type { Code } from './codes.ts'")).toEqual(['./codes.ts'])
        expect(specifiersIn("export { parse } from './markdown.ts'")).toEqual(['./markdown.ts'])
        expect(specifiersIn("import 'node:process'")).toEqual(['node:process'])
        expect(specifiersIn("const m = await import('@view/main.ts')")).toEqual(['@view/main.ts'])
        // A clause over several lines is one specifier and not none, which is
        // how this module writes an import of more than four names.
        expect(specifiersIn("import {\n    answering,\n    checking,\n} from './session.ts'"))
            .toEqual(['./session.ts'])
    })

    it('is not fooled by a sentence that happens to end in "from"', () => {
        // VERBATIM FROM `binding/envelope.ts`, which is where this was found.
        // The matcher that stood here read `from'` and captured the rest of
        // the file as a package name; the only reason nobody saw it is that
        // the one caller filtered the result away before reading it.
        expect(specifiersIn(
            "throw new Error('a response carried no { code } payload to read an outcome from')"))
            .toEqual([])
        // And the two shapes next door to it, for the same reason: a line
        // beginning `export` whose next quote is a value rather than a
        // specifier, and a `from` that is a method.
        expect(specifiersIn("export const CONVERSATION_TURNS = 'conversation.turns'"))
            .toEqual([])
        expect(specifiersIn("export const chars = Array.from('abc')")).toEqual([])
    })

    it('calls a package, an alias and a self-reference what they are', () => {
        // The three shapes `reachesOutOf` exempted, which is the hole
        // `reachingPackages` exists to close. Synthetic, because there is none
        // of any of them in either directory — which is the point.
        const bare = [
            "import { readFile } from 'node:fs'",
            "import { describe } from 'vitest'",
            "import { main } from '@view/main.ts'",
            "import { main } from 'plowshare-tui/src/view/main.ts'",
        ].join('\n')
        expect(specifiersIn(bare).filter((each) => !each.startsWith('.'))).toHaveLength(4)
        // And a relative one is not among them, which is the whole rule.
        expect(specifiersIn("import { parse } from './markdown.ts'")
            .filter((each) => !each.startsWith('.'))).toEqual([])
    })
})
