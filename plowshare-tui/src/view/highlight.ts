import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import c from 'highlight.js/lib/languages/c'
import cpp from 'highlight.js/lib/languages/cpp'
import csharp from 'highlight.js/lib/languages/csharp'
import css from 'highlight.js/lib/languages/css'
import diff from 'highlight.js/lib/languages/diff'
import dockerfile from 'highlight.js/lib/languages/dockerfile'
import go from 'highlight.js/lib/languages/go'
import gradle from 'highlight.js/lib/languages/gradle'
import ini from 'highlight.js/lib/languages/ini'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import kotlin from 'highlight.js/lib/languages/kotlin'
import lua from 'highlight.js/lib/languages/lua'
import makefile from 'highlight.js/lib/languages/makefile'
import markdown from 'highlight.js/lib/languages/markdown'
import php from 'highlight.js/lib/languages/php'
import python from 'highlight.js/lib/languages/python'
import ruby from 'highlight.js/lib/languages/ruby'
import rust from 'highlight.js/lib/languages/rust'
import scss from 'highlight.js/lib/languages/scss'
import shell from 'highlight.js/lib/languages/shell'
import sql from 'highlight.js/lib/languages/sql'
import swift from 'highlight.js/lib/languages/swift'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'

import type { Run, Style } from './look.ts'
import type { Palette, Token } from './theme/tokens.ts'

/**
 * Code, coloured by highlight.js and drawn in the theme's syntax colours.
 *
 * <h2>The shape, which is pi's</h2>
 *
 * <p>highlight.js knows the languages; this file knows the theme. hljs turns
 * code into nested `<span class="hljs-…">` scopes, and {@link SCOPES} maps each
 * scope to a {@link Token} — so a theme author sets `syntaxKeyword` once and
 * never learns what highlight.js calls anything.
 *
 * <p><b>Only the core, and only these languages</b>, registered once at import.
 * The full bundle is every language hljs has and costs startup time for
 * languages an agent almost never writes.
 *
 * <p><b>No auto-detection.</b> A fence with no language is very often a
 * command's output or a log, and a guess that colours a stack trace as Ruby is
 * worse than no colour. Unnamed or unknown means plain.
 */

const LANGUAGES = {
    bash, c, cpp, csharp, css, diff, dockerfile, go, gradle, ini, java, javascript, json,
    kotlin, lua, makefile, markdown, php, python, ruby, rust, scss, shell, sql, swift,
    typescript, xml, yaml,
}
for (const [name, language] of Object.entries(LANGUAGES)) {
    hljs.registerLanguage(name, language)
}

/**
 * highlight.js scope → theme token. A dotted scope with no entry of its own
 * falls back to its prefix, so `title.function.invoke` finds `title.function`.
 */
const SCOPES: Readonly<Record<string, Token>> = {
    'comment': 'syntaxComment',
    'quote': 'syntaxComment',
    'doctag': 'syntaxKeyword',
    'keyword': 'syntaxKeyword',
    'selector-tag': 'syntaxKeyword',
    'name': 'syntaxKeyword',
    'built_in': 'syntaxType',
    'type': 'syntaxType',
    'class': 'syntaxType',
    'title.class': 'syntaxType',
    'title': 'syntaxFunction',
    'title.function': 'syntaxFunction',
    'function': 'syntaxFunction',
    'string': 'syntaxString',
    'regexp': 'syntaxString',
    'symbol': 'syntaxString',
    'char': 'syntaxString',
    'number': 'syntaxNumber',
    'literal': 'syntaxNumber',
    'variable': 'syntaxVariable',
    'params': 'syntaxVariable',
    'attr': 'syntaxVariable',
    'attribute': 'syntaxVariable',
    'property': 'syntaxVariable',
    'template-variable': 'syntaxVariable',
    'selector-class': 'syntaxType',
    'selector-id': 'syntaxType',
    'operator': 'syntaxOperator',
    'punctuation': 'syntaxPunctuation',
    'tag': 'syntaxPunctuation',
    'meta': 'syntaxMeta',
    'section': 'mdHeading2',
    'bullet': 'mdBullet',
    'link': 'mdLink',
    'code': 'mdCode',
    'addition': 'diffAdded',
    'deletion': 'diffRemoved',
}

/** The token a scope is drawn in, trying the scope and then each shorter prefix. */
function tokenOf(scope: string, language: string): Token | undefined {
    if (language === 'diff' && scope.startsWith('meta')) {
        return 'diffHunk'
    }
    const parts = scope.split('.')
    for (let length = parts.length; length > 0; length -= 1) {
        const found = SCOPES[parts.slice(0, length).join('.')]
        if (found !== undefined) {
            return found
        }
    }
    return undefined
}

const ENTITIES: Readonly<Record<string, string>> = {
    '&lt;': '<', '&gt;': '>', '&amp;': '&', '&quot;': '"', '&#x27;': '\'', '&#39;': '\'',
}

const unescaped = (html: string): string =>
    html.replace(/&(?:lt|gt|amp|quot|#x27|#39);/gu, (entity) => ENTITIES[entity] ?? entity)

/** Whether a fence's language is one this client colours. */
export function knows(language: string): boolean {
    return language !== '' && hljs.getLanguage(language) !== undefined
}

/**
 * `code` as runs, or `undefined` when its language is not one this client knows.
 * The runs may hold newlines; the caller cuts them into lines.
 */
export function highlight(code: string, language: string, palette: Palette): Run[] | undefined {
    const known = language === '' ? undefined : hljs.getLanguage(language)
    if (known === undefined) {
        return undefined
    }
    const canonical = (known.name ?? language).toLowerCase()
    const html = hljs.highlight(code, { language, ignoreIllegals: true }).value
    const runs: Run[] = []
    const stack: Style[] = [{}]
    for (const found of html.matchAll(/<span class="([^"]*)">|<\/span>|([^<]+)/gu)) {
        const current = stack[stack.length - 1] ?? {}
        if (found[2] !== undefined) {
            runs.push({ text: unescaped(found[2]), style: current })
        } else if (found[1] !== undefined) {
            const scope = found[1].split(' ')
                .map((part) => part.replace(/^hljs-/u, '').replace(/_+$/u, ''))
                .join('.')
            const token = tokenOf(scope, canonical)
            const colour = token === undefined ? undefined : palette[token]
            let next: Style = colour === undefined || colour.kind === 'none'
                ? current : { ...current, fg: colour }
            if (token === 'syntaxComment') {
                next = { ...next, italic: true }
            }
            stack.push(next)
        } else if (stack.length > 1) {
            stack.pop()
        }
    }
    return runs
}
