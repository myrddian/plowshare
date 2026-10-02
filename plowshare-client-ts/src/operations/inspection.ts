// Pure views shared by terminal and desktop clients.
import type { Answer } from './response.ts'
import { OK, bodyOf, countAt, fieldsOf, textAt } from './response.ts'
import type { Block } from './markdown.ts'
import { jobStatusOf } from '../binding/job-view.ts'
import { parse } from './markdown.ts'


/**
 * One run — `OrchestrationFrames.RunView`, in the fields the two run screens
 * read.
 *
 * <p><b>`parent` and `depth` are both carried, and neither is derivable from
 * the other here.</b> A listing shows twenty runs and a child's parent may not
 * be among them, so the id is what makes the relation legible; the depth is
 * what says how far down a run is when the chain above it is not on the screen.
 */
export interface Run {
    readonly id: string

    /** The definition it is running, by name. */
    readonly definition: string

    readonly tier: string

    /** The project it runs in. Absent for a run in the global tier. */
    readonly project?: string

    /** `running`, `asking`, `waiting`, `finished`, `failed`, `capped` or `cancelled`. */
    readonly state: string

    /**
     * Which cap a conductor is asking its caller to raise — `turn_cap`,
     * `call_budget` or `time_cap` — or `stuck` for a run asking the person whether it goes on
     * after three turns without progress, `uncovered` or `check_failures` (see {@link
     * PERSON_ONLY_KINDS}). Absent unless the run is `asking`, and
     * absent for a run asking an ordinary question rather than for a cap.
     */
    readonly pendingCap?: string

    /**
     * What the run came to. Absent until it is `finished`.
     *
     * <p><b>Read for the same reason `failure` is</b>: a screen that shows a run
     * has ended and not what it ended as sends a person back to the conversation
     * archive for the one fact they opened it for.
     */
    readonly result?: string

    /** Why it stopped. Absent unless it ended in some way other than finishing. */
    readonly failure?: string

    /** The run that started this one. Absent for one a person started. */
    readonly parent?: string

    readonly depth: number

    /** The child this run is waiting on. Absent unless it is waiting on one. */
    readonly waitingFor?: string

    readonly createdAt: string

    /** When it ended. Absent while it has not. */
    readonly endedAt?: string

    /**
     * When a stall sweep last found this run quiet. Absent unless the run is
     * `running` and the server has marked it — the mark this task exists to show.
     */
    readonly stalledSince?: string

    /** The conductor's own conversation — where the explorer opens a run. */
    readonly conductorConversation?: string

    /** Caller conversation identifies a launch without guessing from timestamps. */
    readonly callerConversation?: string

    /**
     * The agent that started it — for a root run, the model its questions and its ending are
     * delivered to beside the person, which the question dialog names.
     */
    readonly callerAgent?: string
}


/**
 * One stage of a run — `TodoView`, read as a stage.
 *
 * <p><b>A run's stages are the conductor's todo list</b>, which is where the
 * engine keeps them: `orchestration.status` answers with `todos`, and this
 * client renders them under the word the definition uses. The `stage` is the
 * definition's own id where the row belongs to one.
 */
export interface RunStage {
    readonly text: string

    /** `pending`, `in_progress`, `done` or `cancelled`, in the server's words. */
    readonly status: string

    /** What finishing it came to. Absent while nothing has. */
    readonly summary?: string

    /** The definition's stage id. Absent for a row the conductor added itself. */
    readonly stage?: string

    /** The todo's id. Absent for a row too old to carry one. */
    readonly id?: string

    /** The item it sits under — a phase child names its stage's. Absent for a row with no parent. */
    readonly parent?: string
}


/** One option of a question with options — `StructuredQuestions.Option`. */
export interface QuestionOption {
    readonly label: string
    readonly description: string
    /** Monospace text shown beside the option when it is focused. */
    readonly preview?: string
}


/** One question with options — `StructuredQuestions.Question`. */
export interface Question {
    /** The short chip it is named by, and what an answer names it with. */
    readonly header: string
    readonly question: string
    readonly multi: boolean
    readonly options: readonly QuestionOption[]
}


/**
 * The draft an install question puts to the person — `Studio.installQuestion` writes it beside
 * the questions: the orchestration's name, where the draft sits, and the whole text that would be
 * installed, which the question's own preview cuts short.
 */
export interface Draft {
    readonly name: string
    readonly path: string
    readonly text: string
    /** Hash pinned by Studio beside the reviewed source. */
    readonly sha256?: string
}


/** What a question with options carries on `MessageView.structure` (V70). */
export interface Structure {
    /** The conductor's lead-in to its questions. */
    readonly lead: string
    readonly questions: readonly Question[]
    /** The draft, on the Studio's install question; absent on every other. */
    readonly draft?: Draft
}


/** One question the conductor asked, or one answer it was given — `MessageView`. */
export interface Message {
    /** `question` or `answer`, in the server's words. */
    readonly kind: string

    readonly author: string

    readonly text: string

    /** Its options, for a question with options (V70); absent for words. */
    readonly structure?: Structure
}


/** One run a run started — `ChildView`, which is just enough to point at it. */
export interface Child {
    readonly id: string
    readonly state: string
}


/** One run, whole: what it is, where it has got to, what it asked, what it started. */
export interface RunStatus {
    readonly run: Run
    readonly stages: readonly RunStage[]
    readonly messages: readonly Message[]
    readonly children: readonly Child[]
}


/**
 * The runs an `orchestration.list` answer named, or nothing for a refusal.
 *
 * <p><b>A socket with no account is a refusal and not an empty list</b>, which
 * is the whole reason this answers nothing: `requireHandle` refuses, and a
 * person signed out who was told "no runs" would have been told something false
 * about their account rather than the truth about this socket.
 */
export function runsOf(answer: Answer): Run[] | undefined {
    const rows = bodyOf(answer, OK)?.['orchestrations']
    if (!Array.isArray(rows)) {
        return undefined
    }
    return rows
        .map((row) => runIn(fieldsOf(row)))
        .filter((row): row is Run => row !== undefined)
}


/**
 * One `RunView`.
 *
 * <p><b>The id and the state are both required, and the rest are not.</b> They
 * are what every line of both run screens is built out of — the id is what
 * `orchestration.status` takes and the state is the whole of what a listing
 * answers — and a row missing either renders as separators around nothing, which
 * reads as a broken client rather than as a row that could not be read. The other
 * fields are read tolerantly, and `wording.runLine` leaves out what came back
 * empty rather than drawing a gap.
 */
function runIn(fields: Record<string, unknown>): Run | undefined {
    const id = textAt(fields, 'id')
    const state = textAt(fields, 'state')
    if (id === undefined || state === undefined) {
        return undefined
    }
    const project = textAt(fields, 'project')
    const parent = textAt(fields, 'parent')
    const waitingFor = textAt(fields, 'waitingFor')
    const endedAt = textAt(fields, 'endedAt')
    const pendingCap = textAt(fields, 'pendingCap')
    const result = textAt(fields, 'result')
    const failure = textAt(fields, 'failure')
    const stalledSince = textAt(fields, 'stalledSince')
    const conductorConversation = textAt(fields, 'conductorConversation')
    const callerConversation = textAt(fields, 'callerConversation')
    const callerAgent = textAt(fields, 'callerAgent')
    return {
        id,
        definition: textAt(fields, 'definition') ?? '',
        tier: textAt(fields, 'tier') ?? '',
        ...(project === undefined ? {} : { project }),
        state,
        ...(pendingCap === undefined || pendingCap === '' ? {} : { pendingCap }),
        ...(result === undefined || result === '' ? {} : { result }),
        ...(failure === undefined || failure === '' ? {} : { failure }),
        ...(parent === undefined ? {} : { parent }),
        depth: countAt(fields, 'depth') ?? 0,
        ...(waitingFor === undefined ? {} : { waitingFor }),
        createdAt: textAt(fields, 'createdAt') ?? '',
        ...(endedAt === undefined ? {} : { endedAt }),
        ...(stalledSince === undefined ? {} : { stalledSince }),
        ...(conductorConversation === undefined ? {} : { conductorConversation }),
        ...(callerConversation === undefined ? {} : { callerConversation }),
        ...(callerAgent === undefined || callerAgent === '' ? {} : { callerAgent }),
    }
}


/**
 * One run's whole status, or nothing for a refusal or a payload with no run in
 * it.
 *
 * <p><b>The run is validated and the three lists are not</b> — {@link
 * inboxPageOf}'s rule for the one field the screen is about, and {@link
 * approvalsOf}' for the rows under it. A status with no readable run is nothing
 * to show; a status whose third message cannot be read is still the run, minus a
 * message. <b>Three empty lists are not a failure</b>: a run that has just
 * started has no stages, has asked nothing and has started nobody.
 */
export function runStatusOf(answer: Answer): RunStatus | undefined {
    const body = bodyOf(answer, OK)
    if (body === undefined) {
        return undefined
    }
    const run = runIn(fieldsOf(body['orchestration']))
    if (run === undefined) {
        return undefined
    }
    return {
        run,
        stages: listOf(body['todos'], stageOf),
        messages: listOf(body['messages'], messageIn),
        children: listOf(body['children'], childIn),
    }
}


/** Every row of `rows` that `read` could read, and nothing for anything that is not a list. */
function listOf<Row>(
        rows: unknown, read: (fields: Record<string, unknown>) => Row | undefined): Row[] {
    if (!Array.isArray(rows)) {
        return []
    }
    return rows
        .map((row) => read(fieldsOf(row)))
        .filter((row): row is Row => row !== undefined)
}


/** One `TodoView`, read as the stage of a run that it is. */
function stageOf(fields: Record<string, unknown>): RunStage | undefined {
    const text = textAt(fields, 'text')
    if (text === undefined) {
        return undefined
    }
    const summary = textAt(fields, 'summary')
    const stage = textAt(fields, 'stage')
    const id = textAt(fields, 'id')
    const parent = textAt(fields, 'parent')
    return {
        text,
        status: textAt(fields, 'status') ?? '',
        ...(summary === undefined || summary === '' ? {} : { summary }),
        ...(stage === undefined || stage === '' ? {} : { stage }),
        ...(id === undefined || id === '' ? {} : { id }),
        ...(parent === undefined || parent === '' ? {} : { parent }),
    }
}


/** One `MessageView`, in the three fields a history is read out of. */
function messageIn(fields: Record<string, unknown>): Message | undefined {
    const text = textAt(fields, 'text')
    if (text === undefined) {
        return undefined
    }
    const structure = structureIn(fields['structure'])
    return {
        kind: textAt(fields, 'kind') ?? '',
        author: textAt(fields, 'author') ?? '',
        text,
        ...(structure === undefined ? {} : { structure }),
    }
}


/**
 * A `structure` read whole or not at all: a question whose options this client cannot read in
 * full is answered in words, which the server still takes, rather than drawn with options missing.
 */
function structureIn(value: unknown): Structure | undefined {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) {
        return undefined
    }
    const fields = value as Record<string, unknown>
    const questions = fields['questions']
    if (!Array.isArray(questions) || questions.length === 0) {
        return undefined
    }
    const read = questions.map(questionIn)
    if (read.some((question) => question === undefined)) {
        return undefined
    }
    const draft = draftIn(fields)
    return { lead: textAt(fields, 'lead') ?? '', questions: read as Question[], ...(draft === undefined ? {} : { draft }) }
}


/**
 * The install question's draft, read only when its name, path and text are all text: without it
 * the question is still answered, its preview the only look at the draft there is.
 */
function draftIn(fields: Record<string, unknown>): Draft | undefined {
    const name = textAt(fields, 'name')
    const path = textAt(fields, 'path')
    const text = textAt(fields, 'text')
    const sha256 = textAt(fields, 'sha256')
    return name === undefined || path === undefined || text === undefined ? undefined
        : { name, path, text, ...(sha256 && /^(?:sha256:)?[a-f0-9]{64}$/.test(sha256) ? { sha256 } : {}) }
}


function questionIn(value: unknown): Question | undefined {
    if (typeof value !== 'object' || value === null) {
        return undefined
    }
    const fields = value as Record<string, unknown>
    const header = textAt(fields, 'header')
    const question = textAt(fields, 'question')
    const options = fields['options']
    if (header === undefined || question === undefined || !Array.isArray(options)
        || options.length === 0) {
        return undefined
    }
    const read = options.map(optionIn)
    if (read.some((option) => option === undefined)) {
        return undefined
    }
    return { header, question, multi: fields['multi'] === true, options: read as QuestionOption[] }
}


function optionIn(value: unknown): QuestionOption | undefined {
    if (typeof value !== 'object' || value === null) {
        return undefined
    }
    const fields = value as Record<string, unknown>
    const label = textAt(fields, 'label')
    const description = textAt(fields, 'description')
    if (label === undefined || description === undefined) {
        return undefined
    }
    const preview = textAt(fields, 'preview')
    return { label, description, ...(preview === undefined ? {} : { preview }) }
}


/** One `ChildView`: the id to look at next, and where it has got to. */
function childIn(fields: Record<string, unknown>): Child | undefined {
    const id = textAt(fields, 'id')
    return id === undefined ? undefined : { id, state: textAt(fields, 'state') ?? '' }
}


/**
 * The unread count an `inbox.changed` push carries; `undefined` for any other
 * push.
 *
 * <b>Bare, and read as tolerantly as every other push here.</b> It carries no
 * `protocol_version` — it is not a frame answer — so this reads only the two
 * fields it needs and lets everything else about the shape be somebody else's
 * business.
 */
export function unreadOf(push: unknown): number | undefined {
    if (typeof push !== 'object' || push === null) {
        return undefined
    }
    const p = push as { kind?: unknown, unread?: unknown }
    return p.kind === 'inbox.changed' && typeof p.unread === 'number' ? p.unread : undefined
}


/**
 * One orchestration's new state, off the bare push its account is told with, or
 * nothing for any other frame.
 *
 * <h3>A third bare push, and the reason it needs its own reader</h3>
 *
 * <p>`OrchestrationsConfig.pushChanges` pushes `{kind, orchestration, state}` to
 * the run's account on every committed change. It carries no `job`, so {@link
 * followed} calls it unreadable — true, and not the same thing as nothing having
 * happened, which is the sentence {@link unreadOf} above was written under. The
 * shape is deliberately narrow: the id and the state, and <b>not</b> the question
 * text. A view that wants the question asks {@link readingRun} for it.
 *
 * <p><b>Every state, and the filtering is the view's.</b> This reader says what
 * the server said; which changes are worth checking the waiting runs over is a
 * judgement about a screen, and {@link rechecks} is where it lives. The
 * split matters because the set will grow — `waiting` did — and a reader that
 * had quietly dropped the states it did not recognise would hide the next one.
 */
export function changedOf(push: unknown): { readonly id: string, readonly state: string }
        | undefined {
    if (typeof push !== 'object' || push === null) {
        return undefined
    }
    const p = push as { kind?: unknown, orchestration?: unknown, state?: unknown }
    if (p.kind !== 'orchestration.changed') {
        return undefined
    }
    return typeof p.orchestration === 'string' && typeof p.state === 'string'
        ? { id: p.orchestration, state: p.state }
        : undefined
}


/** What a run was allowed, as `JobView.LimitsView` reports it. */
export interface Allowance {
    /** Spent across the whole delegation tree, not by this job alone. */
    readonly modelCallsSpent: number

    /** The ceiling as it stands, absent when there is none. */
    readonly maxModelCalls?: number

    /**
     * Whether this run spent an allowance with no ceiling at all.
     *
     * Its own field rather than a missing number, which is `JobView`'s own
     * ruling: <b>"no ceiling" is a decision somebody took</b> and a bare
     * absence cannot say so.
     */
    readonly noBudget: boolean

    readonly maxTurns?: number
    readonly noTurnCap: boolean
}


/** How a run ended, read off a `job.status` answer. */
export interface Ended {
    /** An `Outcome.Ending` constant's name, carried as the name it came as. */
    readonly ending: string

    /** The one bit that separates "it decided" from "it stopped". */
    readonly answered: boolean

    readonly steps: number
    readonly modelCalls: number

    /**
     * The answer, parsed.
     *
     * <b>Empty unless {@link answered}</b>, because every ending but `ANSWERED`
     * is contentless as a *document* and a truncated run must never be dressed
     * as an answer. Contentless as a document is not contentless: every other
     * ending carries a sentence too, and it is {@link sentence}.
     */
    readonly said: readonly Block[]

    /**
     * `Outcome.text` for a run that did not answer, as the characters the server
     * sent.
     *
     * <b>Not parsed, and absent for an answer.</b> `ANSWERED` puts the whole
     * answer in this field and {@link said} already holds it; every other ending
     * puts one sentence there, and the server writes a different one for each —
     * "the model could not be reached", "submitting to the model failed for a
     * reason that is not the endpoint being unreachable", "something the tool
     * '<i>x</i>' needs could not be reached". Those three are one `UNAVAILABLE`
     * to {@link Ended.ending} and three different outages to whoever has to fix
     * one, which is why this field exists.
     *
     * <p>Absent when the server sent nothing or sent a blank — an ending that
     * chose to say nothing, which the endings reached without a model call do.
     */
    readonly sentence?: string

    /**
     * `Outcome.detail`: the failure's type and the first line of its message.
     *
     * <b>The server built this to be shown.</b> `JobRuntime.describe` takes the
     * type and the <em>first line only</em>, on the rule that Postgres puts the
     * failing row on the second — so no utterance, no answer, and no API key
     * reaches here, and `LlmPool` names a pool rather than a URL for the same
     * reason. It is the discriminator the sentence above cannot be: `pool
     * 'local' chat request failed: HTTP 404` and `EmbeddingException: no
     * embedding model is configured` are both "could not reach something it
     * depends on" and they are not the same morning's work.
     *
     * <p>Read for every ending and not just for failures, because an answer
     * carries one too: `truncationNote` rides here on `ANSWERED`. Nothing shows
     * that yet — {@link describeFailure} declines an answered run — and this
     * field is where a view that wants to would find it.
     */
    readonly detail?: string

    readonly allowance?: Allowance

    /** How the run went at the model; absent for a run that measured nothing. */
    readonly pace?: Pace
}


/**
 * How one run went at the model, as `OutcomeView.pace` sends it.
 *
 * <p>Every number but `toolCalls` is absent when the server had none to give —
 * a model that reports no reasoning count, a call that streamed nothing. Absent
 * is not zero, and a view says it as a dash.
 */
export interface Pace {
    readonly toolCalls: number
    readonly completionTokens?: number
    readonly reasoningTokens?: number

    /**
     * Whether `reasoningTokens` is partly an estimate: the model streamed
     * thinking and its endpoint counted none of it. A view says so rather than
     * passing the number off as the model's own.
     */
    readonly reasoningEstimated?: boolean
    readonly firstTokenMillis?: number
    readonly tokensPerSecond?: number
}


/** A boolean field. Anything that is not `true` is false, including absent. */
export function flagAt(fields: Record<string, unknown>, name: string): boolean {
    return fields[name] === true
}



/**
 * A conversation's load: what its newest measured prompt cost, out of how much
 * its model accepts.
 */
export interface Load {
    /**
     * The newest measured prompt, by the model's own count. Absent before any
     * turn reached a model call — which is not a prompt of nothing.
     */
    readonly sent?: number

    /**
     * How long a prompt the agent's model accepts: the ceiling compaction folds
     * under. Absent when nothing serves the model.
     */
    readonly limit?: number

    /** The specifier the prefix was priced under. */
    readonly model?: string
}


/**
 * The load a `conversation.context` answer carried, or nothing for a refusal.
 *
 * <p>`ContextView.sent` and `ContextView.Prefix.contextLength`. Every other
 * number on that view is either an estimate or a breakdown, and a status line
 * has room for neither.
 */
export function loadOf(answer: Answer): Load | undefined {
    const body = bodyOf(answer, OK)
    if (body === undefined) {
        return undefined
    }
    const prefix = fieldsOf(body['prefix'])
    const sent = countAt(body, 'sent')
    const limit = countAt(prefix, 'contextLength')
    return {
        ...(sent === undefined ? {} : { sent }),
        ...(limit === undefined ? {} : { limit }),
        ...modelIn(prefix),
    }
}


/** `model`, spread so an absent one stays an absent key. */
function modelIn(fields: Record<string, unknown>): { model?: string } {
    const model = textAt(fields, 'model')
    return model === undefined || model === '' ? {} : { model }
}


/** One thing waiting in the inbox: a run's result, or a notice such as a sync conflict. */
export interface InboxItem {
    readonly id: string
    readonly arrivedAt: string
    /** When another client or this one marked it read; absent for an unread item. */
    readonly readAt?: string
    /** `run` for a run's result; anything else is a notice no conversation produced. */
    readonly kind: string
    /** How the run ended. Absent exactly when this is not a run. */
    readonly ending?: string
    readonly answer: string
    /** The server's question identity, such as approval:<id>. Absent for news and older servers. */
    readonly about?: string
}


/** What an `inbox.list` answer named: a page of items and how many are unread. */
export interface InboxPage {
    readonly items: readonly InboxItem[]
    readonly unread: number
}


/**
 * The inbox page an `inbox.list` answer carried, or nothing for a refusal or a
 * payload this build cannot read.
 *
 * <p><b>Nothing, and not an empty page</b>, on {@link projects}' rule: an
 * account with nothing waiting is a real and ordinary state — {@link
 * describeInbox} in `wording.ts` prints exactly that — and it must not be
 * confused with a malformed `OK` this client failed to parse.
 *
 * <p><b>One unreadable item refuses the whole page</b> rather than dropping
 * it quietly. `main.ts` marks read only the ids of what it actually showed; a
 * page that rendered four of five items and then asked to mark all five read
 * would flag a row this screen never displayed.
 */
export function inboxPageOf(answer: Answer): InboxPage | undefined {
    const body = bodyOf(answer, OK)
    if (body === undefined) {
        return undefined
    }
    const rawItems = body['items']
    const unread = countAt(body, 'unread')
    if (!Array.isArray(rawItems) || unread === undefined) {
        return undefined
    }
    const items: InboxItem[] = []
    for (const row of rawItems) {
        const item = inboxItemIn(fieldsOf(row))
        if (item === undefined) {
            return undefined
        }
        items.push(item)
    }
    return { items, unread }
}


/** One `InboxItem`, in the fields it is made of. */
function inboxItemIn(fields: Record<string, unknown>): InboxItem | undefined {
    const id = textAt(fields, 'id')
    const arrivedAt = textAt(fields, 'arrivedAt')
    const kind = textAt(fields, 'kind') ?? 'run'
    const ending = textAt(fields, 'ending')
    const answer = textAt(fields, 'answer')
    const readAt = textAt(fields, 'readAt')
    const about = textAt(fields, 'about')
    if (id === undefined || arrivedAt === undefined || answer === undefined
            || (fields['readAt'] != null && readAt === undefined)
            || (fields['about'] != null && about === undefined)
            || (kind === 'run' && ending === undefined)) {
        return undefined
    }
    return { id, arrivedAt, kind, answer, ...(ending === undefined ? {} : { ending }),
        ...(readAt === undefined ? {} : { readAt }), ...(about === undefined ? {} : { about }) }
}


/** The allowance in a `JobView`, when it reported one. */
function allowanceIn(limits: unknown): Allowance | undefined {
    const fields = fieldsOf(limits)
    const spent = countAt(fields, 'modelCallsSpent')
    if (spent === undefined) {
        return undefined
    }
    const maxModelCalls = countAt(fields, 'maxModelCalls')
    const maxTurns = countAt(fields, 'maxTurns')
    return {
        modelCallsSpent: spent,
        ...(maxModelCalls === undefined ? {} : { maxModelCalls }),
        noBudget: flagAt(fields, 'noBudget'),
        ...(maxTurns === undefined ? {} : { maxTurns }),
        noTurnCap: flagAt(fields, 'noTurnCap'),
    }
}


/**
 * How the run ended, off a `job.status` answer, or nothing while it is going.
 *
 * <b>`JobView.outcome` is null until the run has finished</b>, and that null is
 * the whole of the difference a caller needs — so an absent outcome here means
 * "still running" and never "finished with nothing to say".
 */
export function reached(answer: Answer, expectedJob?: string): Ended | undefined {
    if (jobStatusOf(answer, expectedJob)?.outcome == null) return undefined
    const body = bodyOf(answer, OK)
    if (body === undefined) {
        return undefined
    }
    const outcome = fieldsOf(body['outcome'])
    const ending = textAt(outcome, 'ending')
    if (ending === undefined) {
        return undefined
    }
    const answered = flagAt(outcome, 'answered')
    const allowance = allowanceIn(body['limits'])
    return {
        ending,
        answered,
        steps: countAt(outcome, 'steps') ?? 0,
        modelCalls: countAt(outcome, 'modelCalls') ?? 0,
        // Parsed only when it is an answer. A client that ran the grammar over a
        // truncated run's leftovers would be building a document out of
        // something the server declined to call one.
        said: answered ? parse(textAt(outcome, 'text') ?? '') : [],
        // The same field, unparsed, for the endings that are not answers -- and
        // the comment above used to say they were "contentless", which was true
        // of the grammar and false of the field. `stopped()` writes a sentence
        // on every one of them, this client was dropping all of them, and a
        // person watching a run die was told the kind of death and never the
        // cause. Blank stays absent: an empty string is not a sentence, and a
        // view with nothing to add says nothing rather than an empty quote.
        ...sentenceIn(textAt(outcome, 'text'), answered),
        ...detailIn(textAt(outcome, 'detail')),
        ...(allowance === undefined ? {} : { allowance }),
        ...paceIn(outcome['pace']),
    }
}


/**
 * `Outcome.text` as a sentence, spread, for the endings that are not answers.
 *
 * Nothing for an answer, where the same field is the whole answer and {@link
 * Ended.said} holds it parsed — a `sentence` there would be a second copy of a
 * document, and a view rendering it would print the answer twice.
 */
function sentenceIn(text: string | undefined, answered: boolean): { sentence?: string } {
    if (answered || text === undefined || text.trim().length === 0) {
        return {}
    }
    return { sentence: text }
}


/** `Outcome.detail`, spread, or nothing for the blank a run with nothing to add
 *  sends. Read for every ending: see {@link Ended.detail}. */
function detailIn(detail: string | undefined): { detail?: string } {
    return detail === undefined || detail.trim().length === 0 ? {} : { detail }
}


/** `pace`, spread, or nothing for a null one — a run that measured nothing. */
function paceIn(value: unknown): { pace?: Pace } {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) {
        return {}
    }
    const fields = fieldsOf(value)
    const numbers: Record<string, number> = {}
    for (const name of ['completionTokens', 'reasoningTokens', 'firstTokenMillis', 'tokensPerSecond']) {
        const found = countAt(fields, name)
        if (found !== undefined) {
            numbers[name] = found
        }
    }
    return {
        pace: {
            toolCalls: countAt(fields, 'toolCalls') ?? 0,
            ...numbers,
            ...(flagAt(fields, 'reasoningEstimated') ? { reasoningEstimated: true } : {}),
        },
    }
}
