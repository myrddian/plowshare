/**
 * The one escaping function, and the honest account of what it does not do.
 *
 * Every screen this console will grow renders text somebody else wrote: model
 * output, file contents, memory bodies, agent names, project paths. That is the
 * whole of the attack surface, and it is why the spec's rule is stated as
 * *rendering is escaping* rather than as a list of fields to remember.
 */

/**
 * The five characters that change the meaning of markup, and nothing else.
 *
 * `&` is the one whose order matters in a chain of `replace` calls, and getting
 * it wrong is the classic double-encoding bug: escape `<` first and a plain
 * `<` becomes `&lt;`, and a later `&` pass then rewrites that into `&amp;lt;`,
 * which renders on the page as the literal text `&lt;`. The single-pass regex
 * below has no ordering question to get wrong — each character of the input is
 * visited once and replaced once, and no replacement is ever re-read.
 */
const REPLACEMENTS: Readonly<Record<string, string>> = Object.freeze({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
})

/**
 * Render `value` as text that cannot become markup.
 *
 * `String(value)` rather than a `string` parameter, because the values that
 * reach a renderer arrive from `JSON.parse` and are therefore `unknown`: a
 * number, a `null`, or an object whose `toString` somebody else chose. Typing
 * the parameter as `string` would push the conversion out to every call site,
 * and a call site that forgets it is the one that interpolates `[object
 * Object]` on a good day and an attacker's `toString` on a bad one.
 *
 * **What this does not neutralise, measured rather than assumed** — see
 * `escape.test.ts`, which asserts each of these as a fact rather than leaving
 * it as a caveat:
 *
 * - **A `javascript:` URL survives escaping intact.** None of its characters
 *   are in the table, so `escape('javascript:alert(1)')` returns exactly what
 *   it was given. Escaping is not URL validation, and a value that is going to
 *   become an `href` or a `src` needs a scheme check that this function does
 *   not perform and does not pretend to.
 * - **It is not an attribute-context escape on its own.** The output is safe
 *   inside a quoted attribute value, because both quote characters are in the
 *   table; it is not safe in an *unquoted* one, where a space or a backtick
 *   ends the value. Nothing in this module writes an unquoted attribute, and
 *   the rule below is why nothing has to remember that.
 *
 * **The rule this function is defence in depth for, not a substitute for.**
 * *No `innerHTML` with interpolated content anywhere in this module, ever.*
 * The console builds DOM — `createElement`, `textContent`, `setAttribute` —
 * which escapes by construction and has no parse step for a payload to reach.
 * `escape` exists for the places that fall outside that: a value going into a
 * `title` attribute assembled as a string, a document title, an `svg` fragment,
 * anything a future renderer builds as text before the DOM sees it. If a caller
 * finds itself reaching for `escape` *and* `innerHTML`, the answer is
 * `textContent`, and the escaping was the wrong half of the fix.
 */
export function escape(value: unknown): string {
    return String(value).replace(/[&<>"']/g, (character) => REPLACEMENTS[character] as string)
}
