/**
 * The node builders every screen shares, and the two rules they encode.
 *
 * <h2>Rendering is escaping, and here that means `textContent`</h2>
 *
 * Every byte these screens draw is somebody else's: a workspace path an
 * operator typed, a memory body a model wrote, a proposal's reason, an error
 * string a remote provider returned. **There is no `innerHTML` in this
 * directory and no template with a hole in it.** Everything below is
 * `createElement` and `textContent`, which has no parse step for a payload to
 * reach.
 *
 * `escape.ts` is deliberately not imported here, and that is its own javadoc's
 * instruction rather than an oversight: an `escape()` on the way into
 * `textContent` double-encodes -- `a & b` renders as `a &amp; b` on the page --
 * so the call would be a visible bug and not defence in depth. `render.test.ts`
 * holds both halves of that over the source of this directory as well as its
 * own.
 *
 * <h2>An empty list is an answer, and never an error</h2>
 *
 * {@link nothing} exists so that "there is nothing here" is a rendered
 * statement with its own attribute rather than the absence of any rendering at
 * all. A screen that drew no nodes for an empty answer is indistinguishable
 * from a screen that failed to draw, and the two want opposite reactions from
 * the person looking at it. `FileProvider` reserves an empty result for
 * *searched, and there was nothing*; these screens reserve `[data-empty]` for
 * the same meaning.
 */

/** An element with a class and, optionally, its text. No markup, anywhere. */
export function el(tag: string, className: string, text?: string): HTMLElement {
    const node = document.createElement(tag)
    if (className !== '') {
        node.className = className
    }
    if (text !== undefined) {
        node.textContent = text
    }
    return node
}

/**
 * A labelled value: the label as its own node, the value as its own node.
 *
 * Two nodes rather than one string, so that a test can assert on the value
 * without matching the label's prose -- the prose is the half most likely to be
 * reworded, and an assertion that breaks when a sentence improves is an
 * assertion that gets deleted.
 */
export function field(label: string, value: string): HTMLElement {
    const row = el('div', 'field')
    row.dataset['field'] = label
    row.append(el('span', 'label', label), el('span', 'value', value))
    return row
}

/** A button that does one thing. `type=button` so it never submits a form. */
export function button(className: string, text: string): HTMLButtonElement {
    const node = document.createElement('button')
    node.type = 'button'
    node.className = className
    node.textContent = text
    return node
}

/**
 * A text control with its label, for the fields these screens have to collect.
 *
 * The label wraps the control rather than pointing at it by id: an id would
 * have to be unique across a page that mounts five views into one document,
 * and a generated one is a name nothing can assert on.
 */
export function input(label: string, placeholder: string): HTMLInputElement {
    const control = document.createElement('input')
    control.type = 'text'
    control.placeholder = placeholder
    control.dataset['input'] = label
    return control
}

/** Wrap a control in its label. */
export function labelled(text: string, control: HTMLElement): HTMLElement {
    const label = document.createElement('label')
    label.textContent = `${text} `
    label.appendChild(control)
    return label
}

/**
 * "Searched, and there was nothing."
 *
 * @param text the sentence for this particular nothing. Every screen writes its
 *     own, because "no projects" and "no proposals waiting" are different facts
 *     about a working server and a shared word for them would say neither.
 */
export function nothing(text: string): HTMLElement {
    const node = el('p', 'nothing', text)
    node.dataset['empty'] = ''
    return node
}

/**
 * What this console can honestly say about a failure it was handed.
 *
 * `api.ts` now puts the server's own refusal in an `ApiError`'s message where
 * there is one -- `ApiExceptionHandler` writes a sentence per refusal and those
 * sentences are written to be read -- and falls back to the path and the status
 * for the one code whose detail nobody wrote, and for a body that is not this
 * server's shape at all.
 *
 * So this repeats the message when there is one and uses the caller's `fallback`
 * when there is not. **The fallback is no longer the common case**, which is
 * what changed: it used to carry every refusal, and now it carries only the two
 * `api.ts` withholds. It still never invents a cause.
 */
export function problemText(problem: unknown, fallback: string): string {
    return problem instanceof Error && problem.message !== '' ? problem.message : fallback
}

/** A failure, said in this console's own voice and marked as its own. */
export function trouble(text: string): HTMLElement {
    const node = el('p', 'trouble', text)
    node.dataset['trouble'] = ''
    return node
}

/**
 * A moment, in whatever form the server sent it.
 *
 * `Instant` serialises as an ISO-8601 string, and this renders it as one rather
 * than reformatting it into a locale: a console whose timestamps read
 * differently on two machines is a console whose screenshots cannot be compared.
 * A field the server stops sending renders as an absence and not as the epoch.
 */
export function moment(value: unknown): string {
    return typeof value === 'string' && value !== '' ? value : 'not recorded'
}

/**
 * A count, or the fact that nothing reported one.
 *
 * The discipline `describeCost` states for `promptTokens`, applied wherever a
 * number can be legitimately absent: **absence is never rendered as a zero**,
 * because `0` states that something was counted and found to be none, which is
 * the opposite of nothing having counted.
 */
export function describeCount(value: unknown, singular: string, plural: string): string {
    if (typeof value !== 'number' || !Number.isFinite(value)) {
        return `${plural} not reported`
    }
    return value === 1 ? `1 ${singular}` : `${value} ${plural}`
}

/**
 * Text, whatever arrived.
 *
 * `api.get`'s javadoc is explicit that nothing validates the shape the server
 * sent, and that a screen should show a missing field as missing rather than
 * throw over the whole page. An absent field renders empty, which is visibly
 * wrong and still a page.
 */
export function textOf(value: unknown): string {
    return typeof value === 'string' ? value : ''
}
