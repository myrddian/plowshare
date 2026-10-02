import { api, refused } from '../api'
import { button, el, field, input, labelled, moment, nothing, problemText, textOf, trouble }
    from './dom'
import type { Screen, Transport } from './screen'
import type { Setting } from './wire'

/**
 * The system's own configuration: `GET /v1/config`, and the one write this
 * console offers over it.
 *
 * This is the reason the rail has a SYSTEM section at all rather than just a
 * `projects` view under a name nobody chose: `projects` is a leash a person
 * consults, and this screen is the handful of knobs a person can actually
 * turn while the server keeps running. `RuntimeConfigController`'s own javadoc
 * names both endpoints "the two doors onto the few keys that are not" bound
 * once at boot -- everything else in this server is a final field, and stays
 * off this screen for exactly that reason.
 *
 * <h2>A short list is the ordinary shape of a healthy answer</h2>
 *
 * `GET /v1/config` lists what `RuntimeConfigSeed.declared()` names `@Live` and
 * nothing else, so a list of one is not this screen failing to read -- it is
 * the true count of keys a running server lets an operator move. {@link
 * countedNote} says this on the page itself, because a settings screen that
 * shows one row and says nothing about it reads as broken rather than as
 * accurate, and this console's discipline is that an absence states itself
 * rather than being discovered.
 *
 * <h2>A write that does not survive the next boot, said before it is made</h2>
 *
 * `Setting.pinned` is true when an operator supplied a key outside the jar --
 * an environment variable, typically -- and `RuntimeConfigSeed` applies that
 * pin on every boot, after this map and before anything reads it. So writing
 * a pinned key through this screen works, is listed, and is silently put back
 * the next time this server starts. {@link PINNED_NOTE} is drawn on every row
 * `pinned` is true for, not folded into a tooltip or a changelog nobody
 * reads: a screen that offered the edit and stayed quiet about that would be
 * lying by omission.
 *
 * <h2>The value travels as a raw body, and that is why writing goes around
 * `api.put`</h2>
 *
 * `PUT /v1/config/{key}` reads `@RequestBody String value` -- the exact bytes
 * of the value, not a quoted JSON string -- because `RuntimeConfig.intOr`
 * trims on the way out and needs the operator's own text, trailing newline
 * and all, to trim. `api.put` JSON-encodes its payload and sets `Content-Type:
 * application/json`, which is right for the one endpoint it was built for and
 * wrong for this one: it would send `"40"`, quotes included, for a value the
 * server would then read literally. So this screen does not call `transport.
 * put` at all. It goes through {@link write}, `api.request` held whole, the
 * same seam `documents.ts` uses for the one call of its own that is not JSON.
 */

/**
 * What an empty count of live keys says, which is a real answer and not a
 * failed read.
 *
 * `RuntimeConfigSeed.declared()` can in principle name nothing -- every
 * `@Live` accessor removed, or a build with none added yet -- and that is a
 * fact about this server, not about this screen's ability to reach it.
 */
export const NO_LIVE_KEYS =
    'No key on this server is live right now, which means GET /v1/config genuinely has nothing'
    + ' to list. That is not a failed read: everything else this server runs on is bound once at'
    + ' boot and read from a final field, and only a restart with different settings changes it.'

/**
 * Why the list this screen draws is as short as it is, said once per load
 * rather than left for a reader to wonder about.
 *
 * Exported so the test asserts the property and not a paraphrase of it, and so
 * the wording lives beside the argument in this file's header.
 */
export function countedNote(count: number): string {
    const named = count === 1 ? 'one key is' : `these ${count} keys are`
    return `This lists ${count === 1 ? 'one live key' : `${count} live keys`} today because`
        + ` ${named} what this server currently declares @Live -- not because the read failed.`
        + ' Almost everything else this server runs on is bound once at boot and read from a'
        + ' final field, so a live key nobody has written shows the value the jar or an'
        + " operator's own environment variable put there, and that is the ordinary state."
}

/**
 * What a pinned key means, drawn on every row it is true for.
 *
 * The one fact this endpoint exists to surface before a write and not after
 * it: see this file's header.
 */
export const PINNED_NOTE =
    'An operator pinned this key outside the jar — an environment variable, typically — so a'
    + ' write here works now and is undone at the next boot: RuntimeConfigSeed applies that pin'
    + ' before anything else runs, and it wins again. There is no control on this screen that'
    + ' removes a pin; that lives wherever this server is deployed.'

/** A value as this screen shows it, which is never a blank for one the server sent as null. */
function describeValue(value: string | null | undefined): string {
    return typeof value === 'string' ? value : '(no value bound)'
}

/** Who last set this key, or the plain statement that nobody has. */
function describeAuthor(updatedBy: string | null | undefined): string {
    return typeof updatedBy === 'string' && updatedBy !== '' ? updatedBy : 'nobody has written this key'
}

export interface ConfigOptions {
    readonly root: HTMLElement
    readonly transport?: Transport
    /**
     * How one key's write reaches the server, so a test can replace the one
     * call that is not JSON. Defaults to {@link write}. See this file's header
     * for why `transport.put` cannot be used here.
     */
    readonly write?: (path: string, value: string) => Promise<Setting>
}

/** The real write: `PUT path`, the value as the raw body, parsed as the `Setting` it answers with. */
async function write(path: string, value: string): Promise<Setting> {
    const response = await api.request(path, { method: 'PUT', body: value })
    if (!response.ok) {
        // Through `refused` -- a named import beside `api` and not a member of
        // it, because it takes a `Response` rather than sending one -- and not
        // built here, so that this screen says what every other screen says
        // about the same kind of failure. It is the reason that function is
        // exported: a raw string body cannot go through `api.put`, and a
        // hand-rolled error here was how this screen came to be the one place
        // that still answered "answered 400" -- for the refusal that names the
        // keys that are live, which is precisely the answer to the mistake that
        // provoked it.
        throw await refused(response, path)
    }
    return (await response.json()) as Setting
}

export function createConfig(options: ConfigOptions): Screen {
    const transport: Transport = options.transport ?? api
    const writer = options.write ?? write

    const shell = el('section', 'screen config')
    const head = el('header', 'screen-head')
    const title = el('h2', 'screen-title', 'config')
    const reload = button('reload', 'reload')
    const body = el('div', 'screen-body')
    body.dataset['config'] = ''

    head.append(title, reload)
    shell.append(head, body)
    options.root.replaceChildren(shell)

    reload.addEventListener('click', () => {
        void load()
    })

    /** One live key: what it holds, who set it, and the one control this screen has for it. */
    function draw(setting: Setting): HTMLElement {
        const row = el('article', 'setting')
        row.dataset['key'] = textOf(setting.key)
        row.append(el('h3', 'setting-key', textOf(setting.key)))
        row.append(field('value', describeValue(setting.value)))
        row.append(field('updated by', describeAuthor(setting.updatedBy)))
        row.append(field('updated at', moment(setting.updatedAt)))
        if (setting.pinned) {
            const note = el('p', 'pinned-note', PINNED_NOTE)
            note.dataset['pinned'] = ''
            row.append(note)
        }
        row.append(editor(setting))
        return row
    }

    /**
     * `PUT /v1/config/{key}`, with the value as the raw body this endpoint
     * requires.
     *
     * The listing is re-read after a write lands rather than the answer
     * patched in, for `projects.ts`'s reason: the answer this endpoint hands
     * back is one key, and `pinned` can differ from what this row already
     * believed only if the server disagrees -- re-reading is one call and
     * cannot leave the two disagreeing.
     */
    function editor(setting: Setting): HTMLElement {
        const form = el('div', 'editor')
        const value = input('value', 'a new value for this key')
        value.value = typeof setting.value === 'string' ? setting.value : ''
        const save = button('save', 'write this value')
        save.addEventListener('click', () => {
            save.disabled = true
            void writer(`/v1/config/${encodeURIComponent(textOf(setting.key))}`, value.value)
                .then(() => load())
                .catch((problem: unknown) => {
                    save.disabled = false
                    form.append(trouble(problemText(
                        problem, 'That value could not be written, and the failure said nothing'
                        + ' this console can repeat.')))
                })
        })
        form.append(labelled('write', value), save)
        return form
    }

    async function load(): Promise<void> {
        let settings: readonly Setting[]
        try {
            settings = (await transport.get<Setting[]>('/v1/config')) ?? []
        } catch (problem) {
            body.replaceChildren(trouble(problemText(
                problem, 'The live configuration could not be read.')))
            return
        }
        if (settings.length === 0) {
            body.replaceChildren(nothing(NO_LIVE_KEYS))
            return
        }
        const note = el('p', 'config-note', countedNote(settings.length))
        note.dataset['note'] = ''
        body.replaceChildren(note, ...settings.map(draw))
    }

    return {
        element: () => shell,
        load,
        destroy(): void {
            // Nothing to stop: this screen holds no socket and no timer. It is
            // here because the shell treats every screen the same.
        },
    }
}
