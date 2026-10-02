import { api, ApiError } from '../api'
import { openEventStream, type EventStream, type EventStreamOptions } from '../events'
import { grantFieldFor, type GrantField } from '../grant'
import { renderApproval, renderEntry, transcript, type ApprovalResult, type Entry } from './render'
import { mountStyles } from './styles'
import {
    asJobEvent, ENDED, MODEL_CALL, STARTED, TOOL_CALLED,
    type AgentView, type ApprovalAnswered, type ApprovalDecision, type ApprovalList,
    type ApprovalView, type CompactionView, type ConversationView, type JobView,
    type StartedJob, type TurnView,
} from './wire'

/**
 * The REPL: a scrollback, a prompt, and one run's progress while it happens.
 *
 * <h2>The transcript is the server's and the scrollback is not the record</h2>
 *
 * A terminal keeps its own scrollback because it typed every utterance and
 * read every answer. A browser tab that is reloaded has none of that, which is
 * what `GET /v1/conversations/{id}/turns` exists for: without it a refresh is
 * amnesia, and the conversation goes on being correct on the server -- with
 * its whole history in the next turn's prompt -- while the person is shown an
 * empty one. So {@link Repl.resume} rebuilds the whole transcript from the
 * server, and every turn that ends rebuilds it again rather than trusting what
 * this tab happened to watch.
 *
 * <h2>The event stream is droppable, and this console treats it as such</h2>
 *
 * `EventChannelHandler` offers each event to a bounded queue and drops on
 * overflow rather than let a slow listener hold a job's turn. Three things
 * follow, and all three are implemented rather than hoped for:
 *
 * - **`GET /v1/jobs/{id}` is what decides a run is over.** {@link
 *   Repl.reconcile} polls it on its own timer, so a run whose every event was
 *   dropped still lands its answer. Nothing here waits for an `ended` frame;
 *   an `ended` frame only makes the poll happen sooner.
 * - **Silence is never read as "nothing happened".** The events move a
 *   progress panel and nothing else; no state this console reports comes from
 *   an event not arriving.
 * - **A gap is said out loud.** When the outcome reports more model calls than
 *   this tab saw frames for, or reports an ending this tab never saw announced,
 *   a runtime note says so and names where the outcome came from. A person
 *   watching a partial stream should know the stream was partial.
 *
 * <h2>A run that stopped is not a dead end</h2>
 *
 * A run that reaches its cap without answering used to be the end of the
 * screen's story: the ending was rendered, and the only thing left to do was
 * say something else. `POST /v1/conversations/{id}/resume` continues one, and a
 * resumable ending now carries two controls -- **grant more turns**, and
 * **finish**. Three rules hold them:
 *
 * - **The number that ran out, and only that one.** "Approve for another
 *   CAP_TURNS" is what was first asked for, and a run can also stop at the
 *   conversation's model-call budget rather than its turn cap -- `considerOffer`
 *   reads `outcome.ending` to tell which, and the grant carries that field and
 *   never the other. Sending both, or the one that was never touched, would put
 *   back, one layer down, the number this screen stopped asking for when a
 *   conversation was opened.
 * - **"Finish" writes nothing.** The run is already ended and already recorded;
 *   a verb that wrote a row would invent a state the log does not have.
 * - **Which endings those are is the server's to say.** `OutcomeView.resumable`
 *   is read and no ending is enumerated -- see `considerOffer`.
 *
 * <h2>A run that asked is waiting on a person, over frames</h2>
 *
 * A turn that ends `AWAITING` asked before running a command. The questions are
 * read with `approval.list` and answered with `approval.answer` -- **frames on the
 * event socket, never HTTP**, because HTTP for clients is being deprecated and
 * this surface was built without it (spec 2026-09-15, asking a person, §4). An
 * answer that starts the continuing turn hands back its job id, and that job is
 * followed exactly as a submitted one is: the same poll of `GET /v1/jobs/{id}`,
 * which is still the record of what a run came to.
 *
 * <h2>Who said what</h2>
 *
 * Every line's role is set by the code path that knows its provenance and
 * never by reading the text -- see `render.ts`. **A runtime note is this
 * console's own observation and is rendered as one**, in its own gutter, and
 * an utterance that happens to be worded like one is still the person's.
 */

/** The two verbs this screen needs from `api.ts`, so a test can replace them. */
export interface Transport {
    get<T>(path: string): Promise<T>
    post<T>(path: string, payload?: unknown): Promise<T>
    /**
     * The third verb, for the one endpoint on this server that takes it.
     *
     * `PUT /v1/conversations/{id}/lifecycle` is a state a caller puts a row
     * into, and `api.ts`'s own `put` says why the verb is not folded into
     * {@link post}. It is declared here rather than as a seam of the one screen
     * that uses it, so that this interface goes on being "what a screen needs
     * from `api.ts`" and not "what most screens need, plus an exception".
     */
    put<T>(path: string, payload: unknown): Promise<T>
}

export interface ReplOptions {
    /** Where the REPL is built. Its children are replaced. */
    readonly root: HTMLElement
    /** Defaults to the real transport. */
    readonly transport?: Transport
    /** Defaults to the real socket. */
    readonly openStream?: (options: EventStreamOptions) => EventStream
    /** The listener session id. Minted per tab when absent. */
    readonly session?: string
    /** The project tier, or null for global. */
    readonly project?: string | null
    /** The agent chosen before {@link Repl.start} has listed any. */
    readonly agent?: string
    /**
     * Whether this REPL draws its own conversation chooser. `true` when absent.
     *
     * `false` is for a caller that has one already: `chat.ts` composes this
     * REPL beside a sidebar whose whole job is picking a conversation, and two
     * choosers for one conversation in one view is the defect §1 of the design
     * names -- a selection made in one place that the thing beside it does not
     * follow. It is an option rather than a deletion because this REPL is still
     * mounted alone by its own suite, where the select is the only way in.
     *
     * It settles one question and not two: with no chooser of its own this REPL
     * is *told* which conversation to show, through {@link Repl.switchTo}, so
     * {@link Repl.start} does not list conversations either -- that listing
     * exists to fill the select, and `switchTo` reads the row it needs itself.
     */
    readonly ownChooser?: boolean
    /**
     * How often to ask the job endpoint how the run is going, or `null` for a
     * REPL that only reconciles when {@link Repl.reconcile} is called.
     *
     * `null` is what the tests use: a suite that let a timer run would be a
     * suite whose failures depend on how long a machine took.
     */
    readonly pollMs?: number | null
}

export interface Repl {
    /** List the agents and the conversations, and draw the shell. */
    start(): Promise<void>
    /**
     * Open a new conversation on this tier, and resume it.
     *
     * No allowance, and the absence is the whole of change: what a conversation
     * may spend is `plowshare.conversations.default-budget`, an operator's
     * number set once for a deployment. A person opening one is starting to
     * talk.
     */
    open(): Promise<string>
    /** Render one conversation's history and seams from the server. */
    resume(conversationId: string): Promise<void>
    /**
     * Show that conversation instead of whatever is showing: the whole switch,
     * in one call, for whoever is doing the choosing.
     *
     * {@link resume} is not this and must not be used as if it were. It rebuilds
     * the transcript and nothing else, which is right for what it is named
     * after -- coming back to the conversation this REPL is already in. A
     * *different* conversation also carries a different budget, and `spent` is
     * a fact about the one that set it: a REPL that resumed a fresh
     * conversation while still holding the last one's spent flag closes the
     * prompt over it and says on screen that this conversation has spent its
     * budget, which is false. The internal chooser has always done these three
     * things together; this is that sequence given a name, so that a caller
     * with its own chooser calls it rather than keeping a second copy of it
     * that drifts.
     *
     * Never rejects: what went wrong is drawn in the scrollback, in this
     * console's own voice, rather than thrown at whoever clicked a row.
     */
    switchTo(conversationId: string): Promise<void>
    /** Say something, as a turn in the current conversation. */
    submit(text: string): Promise<void>
    /** Ask the job endpoint how the run is going, and finish it if it is over. */
    reconcile(): Promise<void>
    /** The element this REPL built, for a caller that wants to place it. */
    element(): HTMLElement
    /** Stop the socket and the poll. Idempotent. */
    destroy(): void
}

/** The default agent. The operator's "Main" -- there is no MAIN, and this is the one. */
export const DEFAULT_AGENT = 'interlocutor'

/** How often the job endpoint is asked, when nothing says otherwise. */
export const POLL_MS = 1500

/**
 * The listener id this tab attaches under.
 *
 * Random per tab because a second tab attaching under the same id would
 * displace the first -- `EventChannelHandler` keeps one listener per session
 * and closes the one it replaced. It is not a credential: the socket is
 * admitted by the `ps_access` cookie and by nothing in this string, so
 * `Math.random` is an adequate fallback for a context without `randomUUID`.
 */
function mintSession(): string {
    const source = globalThis.crypto as { randomUUID?: () => string } | undefined
    if (source !== undefined && typeof source.randomUUID === 'function') {
        return `console-${source.randomUUID()}`
    }
    return `console-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}

/** An element with a class and, optionally, its text. No markup anywhere. */
function el(tag: string, className: string, text?: string): HTMLElement {
    const node = document.createElement(tag)
    node.className = className
    if (text !== undefined) {
        node.textContent = text
    }
    return node
}

export function createRepl(options: ReplOptions): Repl {
    const transport: Transport = options.transport ?? api
    const openSocket = options.openStream ?? openEventStream
    const session = options.session ?? mintSession()
    const project = options.project ?? null
    const pollMs = options.pollMs === undefined ? POLL_MS : options.pollMs
    const ownChooser = options.ownChooser ?? true

    // --- state ---------------------------------------------------------------

    /** Entries derived from the server's answer. Replaced whole on every refresh. */
    let served: Entry[] = []
    /**
     * What this console's runtime observed, in the order it observed it.
     *
     * Kept beside the transcript rather than merged into it, because a refresh
     * replaces the transcript entirely and these must survive one: a note
     * saying the stream dropped events would otherwise vanish at exactly the
     * moment the reconciliation it is explaining lands. They render after the
     * transcript, newest last, which is where a person looks for what just
     * happened.
     */
    let notes: Entry[] = []
    let conversationId: string | null = null
    let agent = options.agent ?? DEFAULT_AGENT
    let budget: ConversationView | null = null
    /** The conversation cannot take another turn. Terminal; nothing clears it. */
    let spent = false
    let jobId: string | null = null
    /**
     * How much more the offer beside a stopped run would grant, or `null` for an
     * offer that is not standing. **The number the button says**, and not
     * necessarily the number the body carries -- see {@link grantTotal}.
     *
     * The ceiling the stopped run itself ran under, read off its own `limits`,
     * which is what "approve for another CAP_TURNS" means: the same number that
     * stopped the last run is granted again.
     */
    let grantable: number | null = null
    /**
     * The number the request body actually carries for {@link grantable}, or
     * `null` when no number is being sent.
     *
     * **Two variables because a turn cap and a budget count from different
     * places, and the comment here used to say they did not.** It read: "a new
     * run counts its turns (or its model calls...) from zero, so the same number
     * that stopped the last one is a full grant again". The parenthesis was the
     * bug. A turn cap bounds *one run* and the resumed run does start at zero
     * turns, so `maxTurns` is the grant itself. A budget is *conversation-
     * cumulative* and shared by reference down the delegation tree -- that
     * asymmetry is the whole subject of spec 4.3 -- so `maxModelCalls` is a new
     * **total** for the conversation, measured against everything every turn in
     * it has already spent. Sending the exhausted total back as the total leaves
     * `spent === total` and nothing to spend: `Turn.resume` refuses the
     * continuation with "has spent all N model calls of its budget", under a
     * button that said "grant 20 more model calls". So the budget grant is added
     * to what the conversation has already spent, and a grant of N genuinely
     * leaves N.
     */
    let grantTotal: number | null = null
    /**
     * Which field {@link grantable} is a count of, or `null` when {@link
     * considerOffer} could not tell.
     *
     * Set from `outcome.ending` via {@link grantFieldFor} -- shared with
     * `jobs.ts`, which reaches the identical question over a run that stopped
     * unattended, rather than reimplemented beside it -- and see {@link
     * considerOffer}'s javadoc for why that read is not the one this file's own
     * top-level javadoc forbids: it answers *which number to raise*, not
     * *whether to offer at all* -- that second question is `outcome.resumable`
     * alone, checked before this is ever set.
     */
    let grantKind: GrantField = null
    let modelCallsSeen = 0
    let endedSeen = false
    let timer: ReturnType<typeof setTimeout> | null = null
    let stream: EventStream | null = null
    let stopped = false
    /** The last turn's ending as the transcript read it, so a resumed question is shown again. */
    let lastEnding: string | null = null

    // --- the shell -----------------------------------------------------------

    mountStyles(options.root.ownerDocument)

    const shell = el('section', 'repl')
    const head = el('header', 'repl-head')
    const agents = document.createElement('select')
    const conversations = document.createElement('select')
    const openButton = document.createElement('button')
    const budgetLabel = el('span', 'budget')
    const scrollback = el('div', 'scrollback')
    const runPanel = el('section', 'run')
    const runHead = el('div', 'run-head')
    const activity = el('div', 'activity')
    const approvals = el('section', 'approvals')
    const offer = el('div', 'offer')
    const offerNote = el('span', 'offer-note')
    const grantButton = document.createElement('button')
    const finishButton = document.createElement('button')
    const form = document.createElement('form')
    const input = document.createElement('textarea')
    const send = document.createElement('button')
    const hint = el('span', 'hint')

    // There is no allowance field, and its absence is a decision rather than a
    // simplification.
    //
    // It was a required number input, and the comment here defended it: the
    // server refused `POST /v1/conversations` without `maxModelCalls` because
    // how much a person is going to say has no arithmetic behind it, so a
    // number this page invented would be folklore. **The server's half of that
    // is still true and is still the server's** -- nothing computes an allowance
    // anywhere -- but the conclusion drawn from it here was wrong. A refusal to
    // invent a number is not a reason to ask a person for one: they have no
    // method for it either, and they were being asked before they had said a
    // word. The owner's judgement, which this implements: "it should just
    // inherit that from the system ... esp in REPL mode having a Budget makes no
    // sense".
    //
    // Where the number comes from now is `plowshare.conversations
    // .default-budget`, which an operator sets once for a deployment, beside
    // every other cost bound that server takes; `ConversationsProperties` and
    // `OpenConversationRequest` carry the argument. A caller that does know what
    // it wants still sends the field and is still honoured -- what changed is
    // that this screen is not such a caller.
    openButton.type = 'button'
    openButton.textContent = 'open a conversation'
    send.type = 'submit'
    send.textContent = 'send'
    input.rows = 2
    input.placeholder = 'say something'
    scrollback.dataset['scrollback'] = ''
    conversations.dataset['conversations'] = ''
    agents.dataset['agents'] = ''
    activity.dataset['activity'] = ''
    runPanel.hidden = true
    approvals.dataset['approvals'] = ''
    approvals.hidden = true

    // Left out of the tree rather than hidden, when this REPL is not the thing
    // that chooses: an element never appended cannot be found by a
    // `querySelector` for `[data-conversations]`, and that is the point -- in
    // the composed view the attribute has to name exactly one control, where a
    // merely hidden second one would still answer first if it came first.
    head.append(labelled('agent', agents))
    if (ownChooser) {
        head.append(labelled('conversation', conversations))
    }
    head.append(openButton, budgetLabel)
    grantButton.type = 'button'
    grantButton.dataset['grant'] = ''
    finishButton.type = 'button'
    finishButton.dataset['finish'] = ''
    finishButton.textContent = 'finish'
    offer.dataset['offer'] = ''
    offer.hidden = true
    offer.append(offerNote, grantButton, finishButton)
    runPanel.append(runHead, activity, offer)
    form.className = 'prompt'
    form.append(input, send, hint)
    shell.append(head, scrollback, approvals, runPanel, form)
    options.root.replaceChildren(shell)

    function labelled(text: string, control: HTMLElement): HTMLElement {
        const label = document.createElement('label')
        label.textContent = `${text} `
        label.appendChild(control)
        return label
    }

    // --- rendering -----------------------------------------------------------

    function draw(): void {
        const nodes = [...served, ...notes].map(renderEntry)
        scrollback.replaceChildren(...nodes)
        // jsdom reports zero for both, so this is a no-op in the suite and the
        // reason nothing asserts on it.
        scrollback.scrollTop = scrollback.scrollHeight
    }

    /** Say something in this console's own voice. Never in the person's. */
    function note(text: string): void {
        notes = [...notes, { role: 'runtime', text }]
        draw()
    }

    function refusal(text: string): void {
        notes = [...notes, { role: 'refusal', text }]
        draw()
    }

    /**
     * Renders nothing for a lifted conversation, not zero and not the total the
     * operator's default would have been -- either of those would be the
     * invented number `Budget` and this design exist to refuse, one layer down
     * from a person reading it off the screen.
     *
     * `budget.maxModelCalls === null` is checked beside `noBudget`, and not
     * instead of it: the two travel together on the wire, but only `noBudget`
     * is the decision, and checking the number too is what lets the compiler
     * follow the arithmetic below into a type that no longer admits `null`.
     *
     * `modelCallsSpent === null` is the third and rarest of them -- a row whose
     * allowance is not its own to report. There is no meter to draw without a
     * spend either, and drawing one from a number the server declined to give
     * is the same substitution again.
     */
    function showBudget(): void {
        if (budget === null || budget.noBudget === true || budget.maxModelCalls === null
                || budget.modelCallsSpent === null) {
            budgetLabel.textContent = ''
            return
        }
        const left = budget.maxModelCalls - budget.modelCallsSpent
        budgetLabel.dataset['spent'] = String(spent || left <= 0)
        budgetLabel.textContent =
            `budget ${budget.modelCallsSpent}/${budget.maxModelCalls} model calls spent`
    }

    /**
     * The prompt is closed when there is nothing to say into, when a turn is
     * already in flight, or when the conversation's budget is spent.
     *
     * The third is terminal: `Turn.speak` refuses an utterance into a
     * conversation with nothing left, so a person left typing into one is
     * typing into a 409 they have not been told about yet.
     */
    function showPrompt(): void {
        const closed = conversationId === null || spent || jobId !== null
        input.disabled = closed
        send.disabled = closed
        if (conversationId === null) {
            hint.textContent = 'open or choose a conversation first'
        } else if (spent) {
            hint.textContent = 'this conversation has spent its budget and can take no more turns'
        } else if (jobId !== null) {
            hint.textContent = 'a turn is in flight'
        } else {
            hint.textContent = ''
        }
    }

    function showActivity(entries: Entry[]): void {
        activity.replaceChildren(...entries.map(renderEntry))
    }

    /**
     * The two things a person may do about a run that stopped before it
     * answered: give it more turns, or let it be.
     *
     * <h2>Which endings these appear beside is not decided here</h2>
     *
     * **`outcome.resumable` is read and no ending is enumerated.** Which endings
     * a grant continues is the server's decision -- `Turn` holds the table
     * beside the code that continues one -- and it is not the same list as the
     * one this console would put controls next to: `CANCELLED` is continued on
     * request and never offered, because the person asked it to stop. A copy of
     * either list here would go on offering a grant the server had stopped
     * taking, with nothing failing. The server also folds in what this console
     * could not know: a run with nothing left in the conversation's budget
     * cannot be continued by a grant of *turns*, so it is not offered one.
     *
     * <h2>Why it is here and not on the ending's own line</h2>
     *
     * The sentence describing an ending is rendered into the scrollback by
     * `render.ts`, and **the scrollback is replaced whole from the server on
     * every refresh** -- a control built into it would be destroyed by the next
     * `refreshTranscript`, which is the same reason the runtime's own notes are
     * kept beside the transcript rather than merged into it. This panel is the
     * console's own and survives one.
     *
     * <h2>Which number to raise is a different question from whether to offer,
     * and looks alike enough to be confused with it</h2>
     *
     * The section above is about *whether* an offer stands at all, and that is
     * `outcome.resumable` and nothing else -- read once, above, and no ending is
     * named to decide it. What follows is a second, narrower question this
     * function still has to answer once the server has already said yes: a
     * resumable, stopped run ran out of exactly one thing, either its turn cap
     * or the conversation's model-call budget, and a grant that raises the wrong
     * one buys nothing -- the new run stops again immediately, at the number
     * that was never touched. `outcome.ending` is read here to tell the two
     * apart. That is not the enumeration the section above forbids: it is not
     * used to decide *if* a grant is offered -- `resumable` already settled
     * that -- only to decide *which field* a grant already agreed to offer
     * should carry. Branching on the ending for the first question would
     * recreate a table `Turn.CONTINUABLE` owns and this console must not; branching
     * on it for the second is answering something the server's `resumable` bit
     * never spoke to in the first place. The two reads sit two lines apart in
     * this function and are easy to fold into "one enum read, so one rule" --
     * they are not the same rule.
     *
     * `TURN_CAP` and `CALL_BUDGET` are the two endings a stopped, resumable run
     * currently has. An ending outside that pair -- one the server has added
     * since this file was last read, which it is free to do -- leaves this
     * console knowing an offer is owed but not which field would mean anything
     * on the wire. Guessing is worse than the alternative in both directions: a
     * body with the wrong field grants nothing, and a body with an invented
     * field is a number this console has no method for, which is the exact
     * complaint that put an allowance field on this screen in the first place
     * and took it back off. So the grant control itself is withheld for such an
     * ending -- `grantKind` stays `null` and {@link grantMore} refuses to run --
     * while "finish" is still offered, because dismissing commits to nothing and
     * needs no knowledge this console lacks.
     */
    function considerOffer(job: JobView): void {
        const outcome = job.outcome
        if (outcome === null || outcome === undefined || outcome.resumable !== true) {
            withdrawOffer()
            return
        }
        grantKind = grantFieldFor(outcome.ending)
        if (grantKind === null) {
            grantable = null
            grantTotal = null
            grantButton.hidden = true
            offerNote.textContent = 'this run stopped before it answered, at an ending this'
                + ' console does not know how to grant more of. It can still be let stand.'
            offer.hidden = false
            return
        }
        const cap = grantKind === 'maxTurns' ? job.limits?.maxTurns : job.limits?.maxModelCalls
        const noun = grantKind === 'maxTurns' ? 'turns' : 'model calls'
        const more = typeof cap === 'number' ? cap : null
        // What the body would carry: the grant itself for turns, and the grant
        // on top of what the conversation has already spent for model calls.
        // See `grantTotal` -- the second is a new total for a cumulative count
        // and the first is a ceiling for a run that starts again at zero.
        const already = job.limits?.modelCallsSpent
        const total = more === null || grantKind === 'maxTurns' ? more
            : typeof already === 'number' ? already + more
            : null
        // The two are set together and go null together. A button naming a
        // number whose body could not be computed would promise a grant of that
        // size and send none -- which is the shape of the defect this pair
        // exists to fix, arrived at from the other direction.
        grantable = total === null ? null : more
        grantTotal = total
        grantButton.hidden = false
        grantButton.textContent = grantable === null
            ? `grant more ${noun}`
            : `grant ${grantable} more ${noun}`
        offerNote.textContent = 'this run stopped before it answered.'
        offer.hidden = false
    }

    /**
     * Take the offer away, having granted nothing.
     *
     * **"Finish" writes nothing, and that is the decision rather than an
     * omission.** The run is already ended and already recorded; a verb that
     * wrote a row here would invent a state the log does not have. It is the
     * absence of a grant, so it dismisses the offer and does nothing else --
     * the conversation is still open and can still be spoken into.
     */
    function withdrawOffer(): void {
        offer.hidden = true
        grantable = null
        grantTotal = null
        grantKind = null
    }

    /**
     * Ask for another run continuing the one that stopped.
     *
     * <h2>What the body carries, and what it deliberately does not</h2>
     *
     * - **Exactly the field that ran out, and never the other one.**
     *   {@link considerOffer} has already read `outcome.ending` and decided --
     *   `maxTurns` for a `TURN_CAP` stop, `maxModelCalls` for a `CALL_BUDGET`
     *   one -- and left the answer in `grantKind`; this function trusts it
     *   rather than re-deriving it, and never sends both fields on one request.
     *   The owner's own words, "approve for another CAP_TURNS", were about the
     *   case that first asked for this control, and the shape they describe
     *   still holds one layer down: a grant is one number, not a pair, and not
     *   the number that was never touched.
     * - **Nothing at all when {@link considerOffer} could not tell which field
     *   to send.** `grantKind === null` means an ending this console does not
     *   know how to top up; the button for it is not drawn (see
     *   {@link considerOffer}), and this guard is the second half of the same
     *   refusal in case something else ever calls this function.
     * - **The session, said again rather than inherited.** A session is a socket
     *   somebody is holding now, not a fact the stopped run left behind, so the
     *   grant says which one this run reaches. Without it the continued run
     *   reaches no client machine and this tab sees no events for it.
     * - **No agent.** `turns.agent` records which one answered, and the endpoint
     *   continues as that one; a body naming a different agent is a 400 rather
     *   than a silent correction, and this console has no better answer than the
     *   record does.
     */
    async function grantMore(): Promise<void> {
        if (conversationId === null || offer.hidden || grantKind === null) {
            return
        }
        const body: { session: string, maxTurns?: number, maxModelCalls?: number } = { session }
        // `grantTotal` and not `grantable`: the button names how many more and
        // the wire wants what the field means, which for a conversation-
        // cumulative budget is a new total rather than an increment. They are
        // the same number for turns and are not for model calls.
        if (grantTotal !== null) {
            if (grantKind === 'maxTurns') {
                body.maxTurns = grantTotal
            } else {
                body.maxModelCalls = grantTotal
            }
        }
        withdrawOffer()
        running = []
        modelCallsSeen = 0
        endedSeen = false
        runPanel.hidden = false
        runHead.textContent = 'this run'
        showActivity(running)
        try {
            const started = await transport.post<StartedJob>(
                `/v1/conversations/${encodeURIComponent(conversationId)}/resume`, body)
            jobId = started.id
            showPrompt()
            schedulePoll()
        } catch (problem) {
            jobId = null
            showPrompt()
            await explainRefusedGrant(problem)
        }
    }

    /**
     * Why the server would not continue that run.
     *
     * **The server's own sentence is preferred, and these are what is left when
     * there is none.** `ApiError.said` is the discriminator and the status is
     * deliberately not: this used to branch on 409 first, which meant the
     * enumeration below beat every 409 the server ever explained -- and the 409
     * is the one this endpoint is refused with, so the enumeration was not a
     * fallback at all but a replacement for the answer.
     *
     * `said` is null for an `internal_error`, whose detail `api.ts` withholds,
     * and for a body that did not come from this server. Those are the cases
     * these sentences are kept for. They enumerate the situations the server has
     * for a status because that is the honest thing to say when it did not say
     * which: a 409 is about the conversation -- an ending a grant does not
     * continue, a turn already in flight, a row that is a machine's log rather
     * than a person's conversation -- and a 400 is about the body, which is this
     * console's mistake and not the person's.
     *
     * The budget is re-read after any 409 either way, because a 409 this console
     * cannot read is still one the budget may explain.
     */
    async function explainRefusedGrant(problem: unknown): Promise<void> {
        const failure = problem instanceof ApiError ? problem : null
        const status = failure === null ? 0 : failure.status
        if (failure !== null && failure.said !== null) {
            refusal(failure.said)
        } else if (status === 409) {
            refusal('The server would not continue that run. A conversation holds one turn at a'
                + ' time, and not every ending is one a grant continues; what this conversation'
                + ' has already said is below, and it can still be spoken into.')
        } else if (status === 400) {
            refusal('The server refused the terms of that grant. Nothing was continued, and'
                + ' nothing about the run that stopped has changed.')
        } else {
            refusal(problem instanceof Error
                ? problem.message
                : 'That run could not be continued, and the failure said nothing this console can'
                    + ' repeat.')
        }
        if (status === 409) {
            await refreshBudget().catch(() => undefined)
        }
    }

    let running: Entry[] = []

    // --- the server ----------------------------------------------------------

    async function refreshTranscript(): Promise<void> {
        if (conversationId === null) {
            return
        }
        const id = encodeURIComponent(conversationId)
        const [turns, folds] = await Promise.all([
            transport.get<TurnView[]>(`/v1/conversations/${id}/turns`),
            transport.get<CompactionView[]>(`/v1/conversations/${id}/compactions`),
        ])
        served = transcript(turns ?? [], folds ?? [])
        const last = [...(turns ?? [])].sort((a, b) => a.ordinal - b.ordinal).at(-1)
        lastEnding = last === undefined || typeof last.ending !== 'string' ? null : last.ending
        draw()
    }

    // --- approvals -------------------------------------------------------------

    function clearApprovals(): void {
        approvals.replaceChildren()
        approvals.hidden = true
    }

    /**
     * Read this conversation's open questions and draw one block per question.
     *
     * @param loud whether a failure is said in the scrollback. True after a turn
     *     this tab watched end `AWAITING`; false when a conversation is merely
     *     opened on one, where the socket may still be connecting and a
     *     refusal about it would be noise about a question nobody just asked.
     */
    async function showApprovals(loud: boolean): Promise<void> {
        const asked = conversationId
        if (asked === null) {
            return
        }
        let outcome
        try {
            if (stream === null) {
                throw new Error('the event socket is not open; "approval.list" was not sent')
            }
            outcome = await stream.ask('approval.list', { conversation: asked })
        } catch (problem) {
            if (loud) {
                refusal(problem instanceof Error ? problem.message
                    : 'The questions this run asked could not be read.')
            }
            return
        }
        // A switch while the list was out: these are another conversation's.
        if (conversationId !== asked) {
            return
        }
        if (outcome.code !== 'OK') {
            if (loud) {
                refusal(outcome.said ?? 'The questions this run asked could not be read.')
            }
            return
        }
        const open = ((outcome.payload as ApprovalList | undefined)?.approvals ?? [])
            .filter((one) => one.state === 'asked')
        approvals.replaceChildren(...open.map((one) =>
            renderApproval(one, (decision, prefix) => answerApproval(one, decision, prefix))))
        approvals.hidden = open.length === 0
        if (loud && open.length === 0) {
            note('This run ended waiting for an answer, and no question is open now: it has'
                + ' already been answered, perhaps from another tab.')
        }
    }

    /**
     * Send one answer, and follow the turn it continues.
     *
     * `prefix` travels only with `project`: it is that scope's whole content and
     * means nothing to the other three.
     */
    async function answerApproval(
        view: ApprovalView, decision: ApprovalDecision, prefix: readonly string[] | null,
    ): Promise<ApprovalResult> {
        const payload = decision === 'project'
            ? { id: view.id, decision, prefix: [...(prefix ?? [])] }
            : { id: view.id, decision }
        let outcome
        try {
            if (stream === null) {
                throw new Error('the event socket is not open; "approval.answer" was not sent')
            }
            outcome = await stream.ask('approval.answer', payload)
        } catch (problem) {
            return { answered: false, note: problem instanceof Error ? problem.message
                : 'That answer could not be sent.' }
        }
        if (outcome.code !== 'OK') {
            return { answered: false, note: outcome.said ?? 'The server did not take that answer.' }
        }
        const reply = outcome.payload as ApprovalAnswered | undefined
        if (typeof reply?.job === 'string' && reply.job !== '') {
            follow(reply.job)
            return { answered: true, note: null }
        }
        if (reply?.busy === true) {
            return { answered: true, note: reply.note ?? 'This conversation already has a turn in'
                + ' flight, so nothing continued. The answer stands, and the next message carries on.' }
        }
        return { answered: true, note: null }
    }

    /**
     * What the conversation row says it has spent.
     *
     * The listing and not the turn's ending, because the two answer different
     * questions: an ending of `CALL_BUDGET` proves the budget is gone, and a
     * turn that answered on the last call leaves it just as gone without ever
     * saying so. Read after every turn for that reason.
     */
    async function refreshBudget(): Promise<void> {
        if (conversationId === null) {
            return
        }
        const query = project === null ? '' : `?project=${encodeURIComponent(project)}`
        const rows = await transport.get<ConversationView[]>(`/v1/conversations${query}`)
        const mine = (rows ?? []).find((row) => row.id === conversationId)
        if (mine !== undefined) {
            budget = mine
            // A lifted conversation's allowance never runs out -- `trySpend`
            // always succeeds for one -- so `spent` must never become true for
            // one here. Checked at the read rather than left to the arithmetic
            // alone, because a `noBudget` row's `maxModelCalls` is `null` and
            // `null - number <= 0` would happen to be false anyway; that is an
            // accident of the arithmetic and not a rule this file wants to rely
            // on, so it is said outright.
            if (!mine.noBudget && mine.maxModelCalls !== null && mine.modelCallsSpent !== null
                    && mine.maxModelCalls - mine.modelCallsSpent <= 0) {
                spent = true
            }
        }
        showBudget()
        showPrompt()
    }

    function listConversations(rows: readonly ConversationView[]): void {
        conversations.replaceChildren(...rows.map((row) => {
            const option = document.createElement('option')
            option.value = row.id
            // A lifted row has no total to subtract from, and "0 of null model
            // calls left" is not a sentence -- said in words instead, the way
            // `Budget.toString` says it on the server. A row with no spend
            // either is one whose allowance is not its own, and it gets the id
            // alone: there is nothing true to say about a budget the server
            // declined to describe, and "0 model calls spent" would be the
            // invented number one layer further out.
            option.textContent = row.modelCallsSpent === null
                ? row.id
                : row.noBudget === true || row.maxModelCalls === null
                ? `${row.id} (no ceiling, ${row.modelCallsSpent} model calls spent)`
                : `${row.id} (${row.maxModelCalls - row.modelCallsSpent} of`
                    + ` ${row.maxModelCalls} model calls left)`
            return option
        }))
        if (conversationId !== null) {
            conversations.value = conversationId
        }
    }

    // --- the stream ----------------------------------------------------------

    function onEvent(frame: unknown): void {
        const event = asJobEvent(frame)
        // A frame that is not a job event, or one for another job on this
        // session, is dropped. One session may have several jobs running at
        // once, which is why every kind carries the job id.
        if (event === null || jobId === null || event.job !== jobId) {
            return
        }
        if (event.kind === STARTED) {
            running = [...running, { role: 'runtime', text: `${event.agent} started` }]
        } else if (event.kind === MODEL_CALL) {
            modelCallsSeen = Math.max(modelCallsSeen, event.modelCalls)
            running = [...running, {
                role: 'model',
                agent: event.agent,
                steps: event.steps,
                modelCalls: event.modelCalls,
            }]
        } else if (event.kind === TOOL_CALLED) {
            running = [...running, {
                role: 'tool',
                tool: typeof event.tool === 'string' ? event.tool : '',
                agent: event.agent,
            }]
        } else if (event.kind === ENDED) {
            endedSeen = true
            // The frame says a run is over; it does not say what the run came
            // to, and this console does not read one out of it. The outcome is
            // fetched, because the outcome is the job endpoint's.
            void reconcile()
        }
        // Any other kind is ignored rather than refused: the kinds travel as
        // strings so a console built against a later server binds a word it has
        // never heard of.
        showActivity(running)
    }

    // --- the verbs -----------------------------------------------------------

    async function resume(id: string): Promise<void> {
        conversationId = id
        served = []
        running = []
        runPanel.hidden = true
        withdrawOffer()
        clearApprovals()
        lastEnding = null
        await refreshTranscript()
        showPrompt()
        if (lastEnding === 'AWAITING') {
            await showApprovals(false)
        }
    }

    /**
     * The whole of a switch: forget the last conversation's verdict, read this
     * one, then read what this one has spent.
     *
     * The order is not free. `spent` is cleared *before* the read rather than
     * after, so that nothing renders between the two with the previous
     * conversation's flag still standing; and `refreshBudget` runs last because
     * it is the only thing that can legitimately set the flag again -- a
     * conversation that really is out of model calls closes its own prompt on
     * the strength of its own row, and not on the strength of the row before.
     *
     * See {@link Repl.switchTo} for why `resume` alone is not this.
     */
    async function switchTo(id: string): Promise<void> {
        spent = false
        try {
            await resume(id)
        } catch (problem) {
            refusal(problem instanceof Error
                ? problem.message
                : 'That conversation could not be read.')
        }
        await refreshBudget().catch(() => undefined)
    }

    async function open(): Promise<string> {
        // The project and nothing else. `maxModelCalls` is optional on this
        // endpoint and an omitted one takes the operator's configured budget,
        // which is the number this screen used to make a person invent.
        const opened = await transport.post<ConversationView>('/v1/conversations', { project })
        budget = opened
        spent = false
        await resume(opened.id)
        showBudget()
        return opened.id
    }

    function schedulePoll(): void {
        if (pollMs === null || stopped || jobId === null || timer !== null) {
            return
        }
        timer = setTimeout(() => {
            timer = null
            void reconcile().finally(schedulePoll)
        }, pollMs)
    }

    function stopPoll(): void {
        if (timer !== null) {
            clearTimeout(timer)
            timer = null
        }
    }

    /**
     * Watch a job this tab did not start with a POST: the turn an answer
     * continued. The same state a submitted turn sets, and the same poll.
     */
    function follow(id: string): void {
        running = []
        modelCallsSeen = 0
        endedSeen = false
        runPanel.hidden = false
        runHead.textContent = 'this run'
        withdrawOffer()
        showActivity(running)
        jobId = id
        showPrompt()
        schedulePoll()
    }

    async function submit(text: string): Promise<void> {
        if (conversationId === null) {
            refusal('There is no conversation open to say that into.')
            return
        }
        if (spent) {
            refusal('This conversation has spent its whole model-call budget, so the server will'
                + ' not take another turn in it. Open a new one to go on.')
            return
        }
        running = []
        modelCallsSeen = 0
        endedSeen = false
        runPanel.hidden = false
        runHead.textContent = 'this run'
        // A new utterance is an answer to the offer as much as either button is:
        // the person went on rather than continuing what stopped.
        withdrawOffer()
        clearApprovals()
        showActivity(running)
        // Shown at once and marked as the person's, because it is: this is the
        // one line on the screen whose provenance this console knows first
        // hand. It is replaced by the server's own record of it when the turn
        // lands.
        served = [...served, { role: 'utterance', text, ordinal: null }]
        draw()

        try {
            const started = await transport.post<StartedJob>(
                `/v1/agents/${encodeURIComponent(agent)}/runs`,
                { task: text, session, conversation: conversationId },
            )
            jobId = started.id
            showPrompt()
            schedulePoll()
        } catch (problem) {
            jobId = null
            runPanel.hidden = true
            showPrompt()
            await explainRefusal(problem)
        }
    }

    /**
     * Why the server would not take that turn, as far as this console can
     * honestly say.
     *
     * **`ApiError.said` decides, and the status does not.** `Turn.Refused` is
     * the 409 here and it names which of the two situations applies -- a budget
     * that is spent, or a turn already in flight -- so where the server sent a
     * sentence, that sentence is shown. This branched on the status first for
     * one commit, which meant the sentence below settled nothing and overwrote
     * the answer that would have.
     *
     * That sentence is kept for the case it is honest in: `said` is null for an
     * `internal_error` and for a body that did not come from this server, and
     * naming both situations is then all this console knows. The budget is
     * re-read after any 409 either way, which settles it when the words did not.
     */
    async function explainRefusal(problem: unknown): Promise<void> {
        const failure = problem instanceof ApiError ? problem : null
        const status = failure === null ? 0 : failure.status
        if (failure !== null && failure.said !== null) {
            refusal(failure.said)
        } else if (status === 409) {
            refusal('The server would not take that turn. A conversation holds one turn at a'
                + ' time, and one that has spent its budget takes no more; the budget above is'
                + ' re-read now and says which.')
        } else {
            refusal(problem instanceof Error
                ? problem.message
                : 'That turn could not be submitted, and the failure said nothing this console'
                    + ' can repeat.')
        }
        if (status === 409) {
            await refreshBudget().catch(() => undefined)
        }
    }

    async function reconcile(): Promise<void> {
        if (jobId === null) {
            return
        }
        const id = jobId
        let job: JobView
        try {
            job = await transport.get<JobView>(`/v1/jobs/${encodeURIComponent(id)}`)
        } catch (problem) {
            stopPoll()
            jobId = null
            showPrompt()
            withdrawOffer()
            refusal(problem instanceof ApiError && problem.status === 404
                ? 'The server no longer holds that job. Jobs live in this process’s memory,'
                    + ' so a restart really does lose them; what the run produced is in the'
                    + ' archive, and the transcript below is re-read from it.'
                : 'This run could not be polled, so what it came to is not known from here.')
            await refreshTranscript().catch(() => undefined)
            return
        }
        const outcome = job.outcome
        if (outcome === null || outcome === undefined) {
            // Still running. Nothing is concluded from the absence of events --
            // only from this answer, which is the contractual one.
            return
        }
        stopPoll()
        jobId = null
        reportGaps(outcome.modelCalls)
        await refreshTranscript().catch(() => undefined)
        if (outcome.ending === 'CALL_BUDGET' && budget?.noBudget !== true) {
            // Terminal for the conversation and not only for the turn: this
            // ending fires exactly when the shared budget is spent, and
            // `Turn.speak` refuses any further utterance into a conversation
            // with nothing left.
            //
            // `budget?.noBudget` is read here and not only where the label is
            // drawn: a lifted budget's `trySpend` always succeeds, so nothing
            // on the server should ever send CALL_BUDGET for one -- but `spent`
            // is terminal once set, and trusting that invariant to hold at
            // every layer forever is exactly the substitution this design
            // exists to refuse everywhere else. If it is ever wrong, this
            // console still will not close a prompt that has nothing to close
            // it over.
            spent = true
        }
        await refreshBudget().catch(() => undefined)
        showPrompt()
        runHead.textContent = 'the last run'
        considerOffer(job)
        clearApprovals()
        if (outcome.ending === 'AWAITING') {
            await showApprovals(true)
        }
    }

    /**
     * Say so when the stream did not carry everything the run did.
     *
     * Two independent checks, because they catch different losses: an `ended`
     * frame that never arrived means the tail was dropped or the socket was
     * away, and an outcome reporting more model calls than there were frames
     * means the middle was. Either way the answer below came from the job
     * endpoint, and saying where it came from is the difference between a
     * partial view and a wrong one.
     */
    function reportGaps(modelCalls: number): void {
        if (!endedSeen) {
            note('The event stream did not carry this run’s end. That is not an error --'
                + ' the stream is droppable by design -- and the outcome below was read from'
                + ' GET /v1/jobs, which is the record of what a run came to.')
        }
        if (modelCalls > modelCallsSeen) {
            note(`The run made ${modelCalls} model calls and this tab saw ${modelCallsSeen} of`
                + ' them go past. Events are dropped when a listener falls behind, so the'
                + ' progress above was partial; the transcript below is not.')
        }
    }

    // --- start ---------------------------------------------------------------

    openButton.addEventListener('click', () => {
        void open().catch((problem: unknown) => {
            refusal(problem instanceof Error ? problem.message : 'That conversation could not be'
                + ' opened.')
        })
    })

    grantButton.addEventListener('click', () => {
        void grantMore()
    })

    finishButton.addEventListener('click', withdrawOffer)

    conversations.addEventListener('change', () => {
        const chosen = conversations.value
        if (chosen !== '') {
            void switchTo(chosen)
        }
    })

    agents.addEventListener('change', () => {
        agent = agents.value
    })

    form.addEventListener('submit', (submitted: Event) => {
        submitted.preventDefault()
        const text = input.value
        if (text.trim() === '') {
            return
        }
        input.value = ''
        void submit(text)
    })

    // Enter sends and shift-enter breaks a line, which is the REPL convention
    // and the reason the control is a textarea rather than an input.
    input.addEventListener('keydown', (pressed: KeyboardEvent) => {
        if (pressed.key === 'Enter' && !pressed.shiftKey) {
            pressed.preventDefault()
            form.requestSubmit()
        }
    })

    showPrompt()

    async function start(): Promise<void> {
        stream = openSocket({
            session,
            onEvent,
            // A conversation opened on a waiting turn before the socket was up
            // could not list its questions then; the open is when it can.
            onStatus: (status) => {
                if (status.state === 'open' && stream !== null && lastEnding === 'AWAITING'
                        && jobId === null && approvals.childElementCount === 0) {
                    void showApprovals(false)
                }
            },
        })
        // Surfaced rather than swallowed. The commonest failure on this first
        // pair of calls is `SIGNED_OUT` -- a tab opened without a live cookie
        // -- and its whole value is the sentence it carries, which names the
        // bootstrap URL as the way back in. A catch that answered an empty list
        // would show a server with no agents on it instead.
        //
        // Scoped by `project`, the same way `/v1/conversations` already is
        // below -- so this list is the one a run into this project can
        // actually reach, and not the boot set a run scoped to `project`
        // would then be refused against. No `session` on this call: `GET
        // /v1/agents` takes none, on purpose -- the server's own javadoc
        // says why, and it is not a fact this console needs to react to.
        //
        // ONE THING THIS PICKER CANNOT SHOW: a bot defined only in this
        // session's own `.plowshare/`. The server would still run it --
        // `session` is still sent below, to `POST /v1/agents/{name}/runs`,
        // and that door resolves it by name -- but the listing has no way to
        // ask for it, since the server cannot tell which client is asking
        // without naming a session on this call, and it deliberately does
        // not (see `AgentController.agents`'s own javadoc for why). This
        // picker offers only a `<select>` filled from the listing, with no
        // free-text way to name an agent, so such a bot is simply
        // unreachable from this console -- a gap in what the UI offers, not
        // just in what this one list shows.
        const listQuery = project === null ? '' : `?project=${encodeURIComponent(project)}`
        const listed = await transport.get<AgentView[]>(`/v1/agents${listQuery}`)
            .catch((problem: unknown) => {
                refusal(problem instanceof Error
                    ? problem.message
                    : 'The agents could not be listed.')
                return [] as AgentView[]
            })
        const offered = listed ?? []
        // A disabled agent stays on the list and cannot be chosen. The server
        // sends it deliberately -- an agent that merely vanished would be met at
        // first use with no explanation -- and what this picker owes it is a row
        // that says so, since selecting it would post a turn that comes back 400.
        agents.replaceChildren(...offered.map((one) => {
            const option = document.createElement('option')
            option.value = one.name
            option.textContent = one.served ? one.name : `${one.name} — disabled`
            option.disabled = !one.served
            if (!one.served || one.withheld.length > 0) {
                option.title = one.withheld.join('\n')
            }
            return option
        }))
        const runnable = offered.filter((one) => one.served).map((one) => one.name)
        if (runnable.includes(agent)) {
            agents.value = agent
        } else if (runnable.length > 0) {
            agent = runnable[0] as string
            agents.value = agent
        }
        if (!ownChooser) {
            // No select to fill, and `switchTo` reads the row it needs itself.
            // Asking here would be one request per build for a control that is
            // not on the page, over an endpoint the composing view has already
            // called for its own sidebar.
            return
        }
        const query = project === null ? '' : `?project=${encodeURIComponent(project)}`
        const rows = await transport.get<ConversationView[]>(`/v1/conversations${query}`)
            .catch(() => [] as ConversationView[])
        listConversations(rows ?? [])
    }

    return {
        start,
        open,
        resume,
        switchTo,
        submit,
        reconcile,
        element: () => shell,
        destroy(): void {
            stopped = true
            stopPoll()
            stream?.close()
            stream = null
        },
    }
}
