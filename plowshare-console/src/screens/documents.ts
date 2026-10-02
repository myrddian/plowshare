import { api, refused } from '../api'
import { describeEnding } from '../repl/render'
import type { JobView, OutcomeView, StartedJob } from '../repl/wire'
import {
    button, describeCount, el, field, input, labelled, nothing, problemText, textOf, trouble,
} from './dom'
import type { Screen, Transport } from './screen'
import { figure } from './trajectory'
import type { DocumentHit, DocumentSearchResponse } from './wire'

/**
 * The corpus: putting a document into it, and asking it a question.
 *
 * Both endpoints existed with no UI at all — an ingest was a `curl` and a search
 * was a `curl` — and the two halves are on one screen because they are one
 * thing: the second is what the first is for, and a corpus you cannot query is
 * indistinguishable from an ingest that failed.
 *
 * <h2>An ingest is a job, and this screen refuses to draw a progress bar</h2>
 *
 * A 30-page document is about 26 minutes and about 220 model calls, so the one
 * thing this screen must not do is render it as though a button had finished
 * something. What it can honestly draw is bounded, and the bound is worth
 * stating exactly because it looks like an omission:
 *
 * `DocumentController.upload` submits through `JobStore.submit(String,
 * Function)` — **the door that takes no session and puts no limits on the
 * handle**. So there is nothing published to this tab's socket (that overload
 * publishes a start and an ending *to no session*), and `JobView.limits` is null
 * with no `modelCallsSpent` to count against. `JobView` carries no timestamp
 * either, so even "how long has this been going" is not a question the wire
 * answers. What is left while a run is going is `state` and `cancelRequested`,
 * and what arrives when it ends is the outcome — which carries the counts.
 *
 * **So the screen polls, and every field on every card comes from `GET
 * /v1/jobs`.** That is `jobs.ts`'s rule for the same reason and one more: an
 * ingest started by a `curl` or by another tab publishes to nobody here, and a
 * screen that believed its own socket would show a corpus filling up from
 * nowhere.
 *
 * <h2>Two refusals arrive before there is a job at all</h2>
 *
 * `TextExtraction.extract` runs on the request thread, so a format this server
 * will not read — a PDF, refused **by signature and not by extension** — comes
 * back as a `415` to the upload itself, and a file over the multipart cap comes
 * back as a `413`. Neither produces a handle. That is why the upload's failure
 * is drawn beside the form rather than as a job that went wrong.
 *
 * <h2>What a search answers, and what a hit is for</h2>
 *
 * The search is fused vector + lexical, so a hit may have matched by meaning, by
 * words, or by both, and `mode` is echoed because a hybrid answer and a
 * vector-only answer to one question are two different claims. The hit carries
 * a chunk id and a paragraph id and **only one of them is a citation** — see
 * {@link WHAT_TO_CITE}, which is on the screen because a person copying an id
 * out of this list is the exact person the distinction was built for.
 */

/**
 * What a document's job is called, mirroring `DocumentController.BY`.
 *
 * Not an agent — no `AgentDefinition` exists for it — which is what
 * `JobStore.submit(String, Function)` means by "the caller's own name for the
 * run". It is the only thing on `GET /v1/jobs` that tells an ingest from an
 * agent's run, which is why the spelling matters here.
 */
export const INGEST = 'ingest'

/** How often the job listing is re-read when nothing says otherwise. */
export const POLL_MS = 4000

/** Why there is no percentage, said where the percentage would have been. */
export const NO_PROGRESS =
    'There is no progress figure here and there could not be one. A 30-page document is about'
    + ' 26 minutes and about 220 model calls, and DocumentController submits it through the'
    + ' JobStore door that takes no session and puts no limits on the handle — so nothing is'
    + ' published to this tab’s socket and there is no spent-against-limit to count. JobView'
    + ' carries no timestamp either, so how long it has been going is not on the wire. While it'
    + ' runs, what is true is that it is running; what it did arrives with the outcome.'

/** What a restart costs, which is a hole and not a property. */
export const RESTART_NOTE =
    'These live in this process’s memory and nothing reaps them, so a restart empties this list'
    + ' and the next ingest is job_000001 again — the same handle a run from another day already'
    + ' had. An ingest that was going when the server stopped left its work in the corpus; what'
    + ' is lost is the handle that said it ran. A paragraph carrying no summary is the query that'
    + ' says what is still owed.'

/** Which id is the one to keep. */
export const WHAT_TO_CITE =
    'The paragraph id is the citation. The chunk id is what matched and is deliberately not one:'
    + ' a chunk is an artefact of the chunker, so changing the chunk size moves every chunk id'
    + ' while no document has moved. A paragraph id survives a re-ingest that left its text'
    + ' alone, and a re-ingest that changed the text issues a new one rather than repointing this'
    + ' one — so a citation either still means these words or means nothing.'

/** What the number beside a hit is, and what it is not. */
export const SIMILARITY_NOTE =
    'Similarity is 1 for an identical direction and 0 for an unrelated one. It is comparable'
    + ' between two hits of one search and is not a threshold anybody calibrated. Under hybrid'
    + ' the list is in the fused order, so this is a fact about each hit and not the key it was'
    + ' sorted on.'

/** The three halves of the corpus a search can be asked to read. */
const MODES = ['hybrid', 'vector', 'lexical'] as const

/**
 * How much of the corpus a question could reach, said with both numbers.
 *
 * Zero unsearchable means the answer is complete; anything else means the search
 * was partial and the screen has to say so rather than let an empty result read
 * as a corpus with nothing in it.
 */
export function reachOf(answer: DocumentSearchResponse): string {
    const blind = answer.unsearchable
    const head = `${describeCount(answer.searchable, 'chunk', 'chunks')} in the corpus a question`
        + ' can reach at all'
    if (typeof blind !== 'number' || blind === 0) {
        return `${head}, and none holding text with no vector. This answer is complete.`
    }
    return `${head}; ${blind} more hold text and no vector, so they were skipped whatever the`
        + ' question was. This answer is partial.'
}

export interface DocumentsOptions {
    readonly root: HTMLElement
    readonly transport?: Transport
    /**
     * How a document's bytes are sent, so a test can replace the one call that
     * is not JSON.
     *
     * **`api.post` cannot do this and must not be made to.** It sets
     * `Content-Type: application/json` and calls `JSON.stringify`, which turns a
     * `FormData` into the string `[object FormData]`; and a multipart request
     * must carry no `Content-Type` set by this code at all, because the boundary
     * is the browser's to write. So the upload goes through `api.request`, which
     * is the seam that takes a `RequestInit` whole.
     */
    readonly send?: (form: FormData) => Promise<StartedJob>
    /** How often to re-read the job listing, or `null` for a screen that only reads when asked. */
    readonly pollMs?: number | null
}

/** The real upload: multipart, with the boundary left to the browser. */
async function upload(form: FormData): Promise<StartedJob> {
    const response = await api.request('/v1/documents', { method: 'POST', body: form })
    if (!response.ok) {
        // Through `refused` -- a named import beside `api` rather than a member
        // of it, since it takes a `Response` rather than sending one -- and not
        // built here, so an upload refusal reads the same as every other
        // refusal in this console. It matters more here than most: a 415 is the
        // corpus declining a file by its signature, and
        // `UnreadableDocumentException`'s sentence says where conversion
        // belongs, which is the whole of what somebody does next about the file
        // they just chose.
        //
        // **The 413 is the exception and is worth naming.** Too large is
        // refused by the container, not by this server's code:
        // `max-file-size: 8MB` makes it a `MaxUploadSizeExceededException`,
        // which Spring's own `ResponseEntityExceptionHandler` answers as a
        // `ProblemDetail` with no `error` key -- so `refused()` finds nothing it
        // can attribute to `ApiExceptionHandler` and falls back to
        // "/v1/documents answered 413". That is the shape check working, not
        // failing: this console repeats what this server wrote, and that body
        // is Spring's.
        throw await refused(response, '/v1/documents')
    }
    return (await response.json()) as StartedJob
}

export function createDocuments(options: DocumentsOptions): Screen {
    const transport: Transport = options.transport ?? api
    const send = options.send ?? upload
    const pollMs = options.pollMs === undefined ? POLL_MS : options.pollMs

    let jobs: readonly JobView[] = []
    let timer: ReturnType<typeof setTimeout> | null = null
    let stopped = false
    let reading = false
    let asked = false

    const shell = el('section', 'screen documents')
    const head = el('header', 'screen-head')
    const reload = button('reload', 'reload')
    const body = el('div', 'screen-body')

    // --- the ingest half -----------------------------------------------------

    const ingesting = el('section', 'ingesting')
    const file = document.createElement('input')
    const naming = input('name', 'blank to file it under its filename')
    const by = input('by', 'operator')
    const start = button('ingest', 'ingest this document')
    const ingestTrouble = el('div', 'complaint')
    const running = el('div', 'runs')

    file.type = 'file'
    file.dataset['file'] = ''
    ingesting.append(
        el('h3', 'section-title', 'put a document in'),
        labelled('file', file),
        labelled('name', naming),
        labelled('ingested by', by),
        start,
        ingestTrouble,
    )
    const noProgress = el('p', 'note', NO_PROGRESS)
    noProgress.dataset['noProgress'] = ''
    const restart = el('p', 'note', RESTART_NOTE)
    restart.dataset['restartNote'] = ''
    ingesting.append(noProgress, restart, running)

    // --- the search half -----------------------------------------------------

    const asking = el('section', 'asking')
    const question = input('question', 'what you want to know, in prose')
    const limit = input('limit', 'blank for the server’s default')
    const modes = document.createElement('select')
    const ask = button('ask', 'search the corpus')
    const answered = el('div', 'answer')

    // `read` and not `mode`: the note below reports the mode that was USED,
    // which is the server's answer, and the control here is only what was asked.
    modes.dataset['read'] = ''
    for (const name of MODES) {
        const option = document.createElement('option')
        option.value = name
        option.textContent = name
        modes.append(option)
    }
    modes.value = 'hybrid'
    answered.dataset['answer'] = ''
    asking.append(
        el('h3', 'section-title', 'ask the corpus'),
        labelled('question', question),
        labelled('how many', limit),
        labelled('read', modes),
        ask,
        el('p', 'note',
            'The mode is on this surface and on no other. A conjunctive word search is correctly'
            + ' silent for most well-formed questions, so a lexical half that has broken and one'
            + ' that is working as designed produce the same hybrid answer — running the question'
            + ' twice is the only thing that tells them apart.'),
        answered,
    )

    head.append(el('h2', 'screen-title', 'documents'), reload)
    body.dataset['documents'] = ''
    body.append(ingesting, asking)
    shell.append(head, body)
    options.root.replaceChildren(shell)

    // --- drawing an ingest ---------------------------------------------------

    function outcomeNode(view: OutcomeView): HTMLElement {
        const node = el('div', 'outcome')
        node.dataset['outcome'] = ''
        node.append(el('div', 'ending', describeEnding(view.ending)))
        // `answered` before the text, on JobView's own instruction: it is the
        // one bit a caller reads before believing what follows. An ingest that
        // stopped part-way stored everything it had written by then, and saying
        // so is the difference between a partial corpus and a failed one.
        node.append(el('div', 'answered', view.answered === true
            ? 'it ran to the end'
            : 'it stopped before the end. What it had written by then is in the corpus and'
                + ' stays there; what is missing is what it had not reached'))
        node.append(el('pre', 'body', textOf(view.text)))
        const detail = textOf(view.detail)
        if (detail !== '') {
            node.append(field('detail', detail))
        }
        node.append(el('div', 'meta',
            `${describeCount(view.steps, 'step', 'steps')}, `
            + `${describeCount(view.modelCalls, 'model call', 'model calls')}`))
        return node
    }

    function ingestNode(view: JobView): HTMLElement {
        const card = el('article', 'ingest')
        const id = textOf(view.id)
        card.dataset['ingest'] = id
        card.dataset['state'] = textOf(view.state)
        card.append(el('div', 'ingest-head', `${id}  ${textOf(view.state)}`))

        if (view.cancelRequested === true) {
            card.append(el('div', 'cancel-requested',
                'a stop has been asked for; this ingest ends at its next batch boundary'))
        }

        const came = view.outcome
        if (came === null || came === undefined) {
            const note = el('div', 'running-note',
                'still going, as of the last time this screen asked. Silence is not idleness —'
                + ' nothing about this job is published anywhere, so asking is the only way to'
                + ' know.')
            note.dataset['running'] = ''
            card.append(note)
        } else {
            card.append(outcomeNode(came))
        }

        const stop = button('cancel', 'ask this ingest to stop')
        stop.dataset['cancel'] = id
        // On the control, because it is what a person is about to do: a cancel
        // is not a rollback and the corpus does not go back to how it was.
        stop.title =
            'Not a rollback. Everything already written stays in the corpus — the paragraphs,'
            + ' the chunks, the vectors and the summaries paid for so far — and what stops is'
            + ' the rest. Re-ingesting the same name later picks up exactly what holds none.'
        stop.addEventListener('click', () => {
            stop.disabled = true
            void transport.post<JobView>(`/v1/jobs/${encodeURIComponent(id)}/cancel`)
                .then(() => refresh())
                .catch((problem: unknown) => {
                    stop.disabled = false
                    card.append(trouble(problemText(
                        problem, 'That ingest could not be asked to stop.')))
                })
        })
        card.append(stop)
        return card
    }

    function drawRuns(): void {
        const mine = jobs.filter((one) => textOf(one.agent) === INGEST)
        const others = jobs.length - mine.length
        const note = el('p', 'window-note', others === 0
            ? `this process is holding ${describeCount(mine.length, 'ingest', 'ingests')}`
            : `this process is holding ${describeCount(mine.length, 'ingest', 'ingests')};`
                + ` ${describeCount(others, 'run', 'runs')} of other kinds are on the jobs`
                + ' screen and not here, including any named code_reviewer or another agent')
        note.dataset['window'] = String(mine.length)
        if (mine.length === 0) {
            running.replaceChildren(note, nothing(
                'No ingest is in this process’s memory. On a server that has been restarted that'
                + ' is the ordinary answer and says nothing about the corpus, which is in the'
                + ' database.'))
            return
        }
        // Newest first: this list is read to see what was just started, and the
        // listing arrives newest last.
        running.replaceChildren(note, ...[...mine].reverse().map(ingestNode))
    }

    // --- drawing an answer ---------------------------------------------------

    function hitNode(one: DocumentHit): HTMLElement {
        const node = el('article', 'hit')
        node.dataset['hit'] = textOf(one.chunkId)
        node.dataset['cite'] = textOf(one.paragraphId)

        node.append(el('div', 'hit-head',
            `${textOf(one.title)} — ${textOf(one.sourceName)}`))
        const ordinal = el('div', 'meta',
            `paragraph ${figure(one.paragraphOrdinal)} of this document as it stands today —`
            + ' a position and not an identity, so it moves when a paragraph is inserted above'
            + ' it and the id below does not')
        ordinal.dataset['ordinal'] = ''
        node.append(ordinal)

        node.append(el('pre', 'body', textOf(one.paragraphText)))
        const cite = field('cite this paragraph', textOf(one.paragraphId))
        cite.dataset['citation'] = ''
        node.append(cite)
        node.append(field('matched chunk', textOf(one.chunkId)))
        node.append(el('pre', 'chunk', textOf(one.text)))
        node.append(field('similarity', figure(one.similarity)))
        return node
    }

    function drawAnswer(view: DocumentSearchResponse): void {
        const nodes: HTMLElement[] = []
        const mode = el('p', 'mode-note',
            `read ${textOf(view.mode)}, at most ${figure(view.limit)} hits — the figure the`
            + ' server used, which is not the one asked for when the ask was over its cap')
        mode.dataset['mode'] = ''
        nodes.push(mode)

        const reach = el('p', 'reach', reachOf(view))
        reach.dataset['reach'] = ''
        nodes.push(reach)

        const hits = Array.isArray(view.hits) ? view.hits : []
        if (hits.length === 0) {
            nodes.push(nothing(view.searchable === 0
                ? 'Nothing was found, and there is nothing in it a question can reach: no chunk'
                + ' in this corpus holds a vector. That is a fact about the corpus and not about'
                + ' the question.'
                : 'Nothing matched. The corpus was searched and answered nothing — which the'
                + ' counts above are what let you believe.'))
            answered.replaceChildren(...nodes)
            return
        }

        const cited = el('p', 'note', WHAT_TO_CITE)
        cited.dataset['citationNote'] = ''
        const near = el('p', 'note', SIMILARITY_NOTE)
        near.dataset['similarityNote'] = ''
        nodes.push(cited, near)
        answered.replaceChildren(...nodes, ...hits.map(hitNode))
    }

    // --- the server ----------------------------------------------------------

    async function read(): Promise<void> {
        let listed: readonly JobView[]
        try {
            listed = (await transport.get<JobView[]>('/v1/jobs')) ?? []
        } catch (problem) {
            // Left as it was rather than emptied, on jobs.ts's rule: an
            // unreadable answer is not a process with no ingests on it.
            running.prepend(trouble(problemText(problem, 'The ingests could not be listed.')))
            return
        }
        jobs = listed
        drawRuns()
    }

    /** One read at a time, and one more if anything asked while it was going. */
    async function refresh(): Promise<void> {
        if (reading) {
            asked = true
            return
        }
        reading = true
        try {
            await read()
        } finally {
            reading = false
        }
        if (asked && !stopped) {
            asked = false
            await refresh()
        }
    }

    async function ingest(): Promise<void> {
        const chosen = file.files
        if (chosen === null || chosen.length === 0) {
            ingestTrouble.replaceChildren(trouble(
                'Choose a file first. Nothing was sent — an ingest is a document’s bytes and'
                + ' there is nothing else this form could mean.'))
            return
        }
        const form = new FormData()
        form.append('file', chosen[0] as File)
        const named = naming.value.trim()
        if (named !== '') {
            // Omitted and never blank. The server refuses a blank `name` on
            // purpose: a blank is somebody who meant to name the document, and
            // falling back to the filename would file it somewhere nobody chose.
            form.append('name', named)
        }
        const who = by.value.trim()
        if (who !== '') {
            form.append('by', who)
        }
        start.disabled = true
        try {
            await send(form)
            ingestTrouble.replaceChildren()
        } catch (problem) {
            // Beside the form, because the two refusals that matter arrive
            // before there is any job: a format refused by signature is a 415 on
            // the request thread and an oversized file is a 413.
            ingestTrouble.replaceChildren(trouble(problemText(
                problem,
                'That document was not taken. A format this server will not read is refused by'
                + ' signature rather than by extension, and a file over the multipart cap is'
                + ' refused before any work starts; neither leaves a job behind.')))
            return
        } finally {
            start.disabled = false
        }
        // The listing and not the answer: the handle came back, and what the
        // handle is doing is a question only GET /v1/jobs answers.
        await refresh()
    }

    async function runSearch(): Promise<void> {
        const wanted = question.value.trim()
        if (wanted === '') {
            answered.replaceChildren(trouble(
                'A search needs a question, in prose. The server refuses a blank one and so does'
                + ' this: nothing was searched, which is not the same as nothing being found.'))
            return
        }
        const payload: { query: string; mode: string; limit?: number } = {
            query: wanted, mode: modes.value,
        }
        const many = Number.parseInt(limit.value.trim(), 10)
        if (Number.isFinite(many)) {
            payload.limit = many
        }
        ask.disabled = true
        let view: DocumentSearchResponse
        try {
            view = await transport.post<DocumentSearchResponse>('/v1/documents/search', payload)
        } catch (problem) {
            answered.replaceChildren(trouble(problemText(
                problem, 'That search could not be run.')))
            return
        } finally {
            ask.disabled = false
        }
        drawAnswer(view)
    }

    function schedulePoll(): void {
        if (pollMs === null || stopped || timer !== null) {
            return
        }
        timer = setTimeout(() => {
            timer = null
            void refresh().finally(schedulePoll)
        }, pollMs)
    }

    reload.addEventListener('click', () => {
        void refresh()
    })
    start.addEventListener('click', () => {
        void ingest()
    })
    ask.addEventListener('click', () => {
        void runSearch()
    })

    async function load(): Promise<void> {
        await refresh()
        schedulePoll()
    }

    return {
        element: () => shell,
        load,
        destroy(): void {
            stopped = true
            if (timer !== null) {
                clearTimeout(timer)
                timer = null
            }
        },
    }
}
