// Shared request builders. This module performs no I/O.


/**
 * Pure request builders extracted from the TUI. Both frontends consume this
 * package; rendering and response interpretation remain in their consumers.
 * Discriminators are checked against FrameTypes.java by the TUI contract suite.
 */

/** Open a conversation, and answer with the id to speak into. */
export const CONVERSATION_OPEN = 'conversation.open'


/**
 * Read one conversation's turns back, oldest first.
 *
 * <b>Sent by nothing in this client, and mirrored only to stay held to the
 * server.</b> It once filled the screen of a conversation being talked to a
 * second time; replay now reads the log itself ({@link CONVERSATION_TRAJECTORY},
 * spec 2026-09-28 §5), because these turns say nothing of who spoke them and
 * would read a result the harness delivered back as "you said". Its readers
 * were removed so that nothing new picks that up.
 */
export const CONVERSATION_TURNS = 'conversation.turns'


/**
 * What a conversation's prompt costs now, and how much room its model has.
 *
 * <p><b>Asked only by a surface that draws the answer.</b> It is one round trip
 * after every turn, and a surface with no status line to put the number on
 * would be paying for a read it throws away. See {@link measuring}.
 */
export const CONVERSATION_CONTEXT = 'conversation.context'


/**
 * Every project that has been defined, with the name a person chose for each.
 *
 * <b>Routed since the socket surface was built, and unreached until now.</b>
 * Nothing was added on the server for this: {@code ProjectListHandler} has
 * always answered it with every {@code ProjectView}, and what was missing was a
 * client that asked. See {@link projects} for the one field of that view this
 * module reads and `wording.describeProjects` for why the other three are left.
 */
export const PROJECT_LIST = 'project.list'


/**
 * What is open in one home — the listing a console opens on.
 *
 * <b>Reading it opens nothing.</b> That matters more here than it looks: this
 * client does not open a conversation until somebody speaks, so a listing that
 * opened one to show it would both undo that and add the row a person was
 * looking for to the list they were looking at. {@link listingConversations}
 * sends this and only this.
 */
export const CONVERSATION_LIST = 'conversation.list'


/**
 * Every seam one conversation has had, oldest first.
 *
 * <b>Sent by nothing in this client</b>, for {@link CONVERSATION_TURNS}'s reason:
 * the log carries each fold as a `summary` entry among the turns it folds, so
 * replay draws the seams out of the same reading as the turns. A conversation
 * that has been compacted still holds every turn — a fold deletes nothing,
 * which `CompactionView` promises in as many words.
 */
export const CONVERSATION_COMPACTIONS = 'conversation.compactions'


/**
 * One conversation's whole log, entry by entry, in the order it was written.
 *
 * <b>The reading that shows the machinery and not the conversation.</b> {@link
 * CONVERSATION_TURNS} answers what was said; this answers what is recorded — the
 * roleless kinds a model never sees included, and, since the server began
 * recording it, which model produced each answer. A rerouted refusal is legible
 * only here: the refusal is a row a model is never shown, and the answer that
 * replaced it names the target that gave it. See {@link entriesOf}, which reads
 * the fields a provenance view is built from.
 */
export const CONVERSATION_TRAJECTORY = 'conversation.trajectory'


/**
 * Follow one conversation's log: from now on this socket is pushed
 * {@link CONVERSATION_APPENDED} whenever entries are committed to it, which is
 * how a turn the harness starts reaches a screen no stream of this client's
 * reaches. Replaces whatever this session followed before.
 */
export const CONVERSATION_FOLLOW = 'conversation.follow'


/** The push that says a followed log grew: `{ kind, conversation, through }`, no content. */
export const CONVERSATION_APPENDED = 'conversation.appended'


/** The most entries one page of a log holds — `RequestedWindow.MOST_ENTRIES_A_PAGE`. */
export const LOG_PAGE = 100


/**
 * How many entries a conversation is shown with on opening, and how many more each `/earlier`
 * brings: forty of the kinds the chat draws, which is about twenty turns of a question and its
 * answer.
 *
 * <b>Nothing has measured it</b>, and it is counted in entries and not turns because entries are
 * what the server can count backwards from the end in one read. A turn whose answer asked for
 * tools is more than two of them, so a tool-heavy conversation shows fewer turns — the number
 * is a guess until somebody has had a long conversation and said what they wanted. <b>Change
 * the constant</b>. It bounds the display and truncates nothing: `/earlier` goes further back,
 * which is traversal rather than recovery.
 */
export const LOG_BACK = 40


/**
 * The entry kinds the chat view draws — {@link loggedFrom}'s, spelled as `EntryKind` spells
 * them — and so the only ones a replay or a catch-up asks the server for. Every other kind
 * (tool results, hooks, thinking, diagnostics) would be read only to be hidden, and would count
 * against {@link LOG_BACK} while it was.
 */
export const DRAWN_KINDS: readonly string[] = ['utterance', 'answer', 'summary']


/**
 * The conversation one agent is still having in one tier.
 *
 * <b>The one frame this client sends that mirrors no endpoint.</b> Every other
 * type here is the socket half of a route under `api/`; this one is the
 * socket's own, because a listing answers "what is open in this tier", oldest
 * first and unlimited, and this answers "which one am I carrying on with".
 * `FrameTypes.CONVERSATION_LATEST` carries the argument and `Capabilities`
 * records it in the register.
 *
 * <p><b>An absent payload is an answer and not a refusal.</b> An agent with no
 * conversation here — every first run — comes back as an `OK` carrying nothing,
 * so {@link continued} answers nothing and the caller reads the code to tell
 * that from a server that would not say.
 */
export const CONVERSATION_LATEST = 'conversation.latest'


/**
 * What this deployment serves, of both kinds, and why it is not serving the
 * rest.
 *
 * <b>Routed since the socket surface was built, and unreached until now.</b>
 * Nothing was added on the server for it: {@code AgentListHandler} has always
 * answered with every {@code AgentView}, including the definitions this server
 * read and refused.
 *
 * <p><b>One frame doing three jobs, which is why it is asked once at sign-in
 * rather than three times.</b> It settles who answers <i>before</i> anything is
 * created — measured: {@code PLOWSHARE_AGENT=no_such_bot} left
 * {@code cnv_31355ABEA50EA42E} behind with zero model calls, because the run
 * was validated after the conversation was opened and the server's perfectly
 * good refusal arrived too late to stop the row. It is what {@link
 * whoAnswers} reads to pick a default. And it is what the two rosters print.
 */
export const AGENT_LIST = 'agent.list'


/** Start one declared agent on one task, and answer with the handle at once. */
export const AGENT_RUN = 'agent.run'


/** How a run is going, and how it ended once it has. */
export const JOB_STATUS = 'job.status'


/**
 * Every scheduled run left for the signed-in account, unread ones first.
 *
 * <b>The one listing this client did not have to invent a frame for.</b> The
 * console (Task 13) already asks it; this is the same route from a second
 * client. `{@code InboxListHandler}` answers `BAD_REQUEST` for a socket with
 * no account, which is why {@link listingInbox} is asked quietly at connect
 * and loudly from `/inbox` — see `main.ts`.
 */
export const INBOX_LIST = 'inbox.list'


/** Mark inbox items read, by id. Never removes them — only the flag changes. */
export const INBOX_READ = 'inbox.read'


/** Ask what is waiting, unread first, twenty at a time. */
export function listingInbox(): Ask {
    return { type: INBOX_LIST, payload: { unread: true, limit: 20 } }
}


/** Mark these items read. `session.ts` does not choose which ids that is. */
export function readingInbox(ids: readonly string[]): Ask {
    return { type: INBOX_READ, payload: { items: [...ids] } }
}


/** A conversation's open questions, or a project's standing approvals. Exactly one of the two. */
export const APPROVAL_LIST = 'approval.list'


/** Answer one question: once, conversation, project (with a prefix) or deny. */
export const APPROVAL_ANSWER = 'approval.answer'


/** Take back a standing project approval, by id. */
export const APPROVAL_REVOKE = 'approval.revoke'


/** How a person may answer, in the words the server reads. */
export type Decision = 'once' | 'conversation' | 'project' | 'deny'


/** The questions this conversation has open. */
export function listingAsked(conversation: string): Ask {
    return { type: APPROVAL_LIST, payload: { conversation } }
}


/**
 * Every question on this account that nobody has answered, wherever it was raised.
 *
 * <p><b>Not by conversation</b>, which is what {@link listingAsked} asks: a command
 * approval raised under an orchestration is written against its conductor's
 * conversation, which no person has open, so a listing by conversation could never
 * show it to anyone.
 */
export function listingMyApprovals(): Ask {
    return { type: APPROVAL_LIST, payload: { mine: true } }
}


/** The project approvals standing in this project. */
export function listingApprovals(project: string): Ask {
    return { type: APPROVAL_LIST, payload: { project } }
}


/**
 * One answer. `prefix` goes only with `project` — the server refuses a project
 * answer without one and ignores it on the other three, so it is not sent there.
 */
export function answeringApproval(id: string, decision: Decision, prefix?: readonly string[]): Ask {
    return {
        type: APPROVAL_ANSWER,
        payload: decision === 'project' && prefix !== undefined
            ? { id, decision, prefix: [...prefix] }
            : { id, decision },
    }
}


/** Take this approval back. */
export function revokingApproval(id: string): Ask {
    return { type: APPROVAL_REVOKE, payload: { id } }
}


/*
 * ORCHESTRATIONS, AND ONLY THE READING OF THEM. Spec 2026-09-13, §7.
 *
 * Three of `OrchestrationFrames`' five frames, and deliberately the three that
 * change nothing: what this tier can start, what this account has started, and
 * how one of those is going. `orchestration.answer` and `orchestration.cancel`
 * stay routed and unspoken here — a person who is going to answer a conductor's
 * question or stop a run needs first to be able to see it, and seeing it is the
 * whole of what this adds.
 *
 * <p><b>The two listings are not symmetrical, because the server's are not.</b>
 * `orchestration.definitions` is scoped by a project and answers a caller with
 * no account at all — `AgentListHandler`'s promise that asking what a deployment
 * offers is never a refusal, kept one area over. `orchestration.list` and
 * `.status` call `Asking.requireHandle` and refuse a socket with no account, so
 * their readers answer nothing and the view says the server's own sentence.
 */

/** What orchestrations a caller could start here, the refused files included. */
export const ORCHESTRATION_DEFINITIONS = 'orchestration.definitions'


/** The signed-in account's orchestration runs, newest first. */
export const ORCHESTRATION_LIST = 'orchestration.list'


/** One run: its stages, what it has asked, and the children it started. */
export const ORCHESTRATION_STATUS = 'orchestration.status'


/**
 * What this tier can start.
 *
 * <p><b>An empty payload for the boot set and not `{ project: null }`</b>, on
 * {@link listingAgents}' ruling. `RequestedProjectId.forListing` degrades an
 * absent project — and an unreachable archive — to the boot set rather than
 * refusing, which is why the two cases are one frame and the view says which
 * set it showed.
 */
export function listingDefinitions(project?: string): Ask {
    return {
        type: ORCHESTRATION_DEFINITIONS,
        payload: project === undefined ? {} : { project },
    }
}


/**
 * This account's runs, newest first.
 *
 * <p><b>No project and no state, deliberately.</b> `OrchestrationFrames.list`
 * takes both as filters, and a terminal listing that quietly hid the runs of
 * every other project would be the screen a person goes to when they have lost
 * a run. The limit is {@link listingInbox}'s twenty, inside the server's 1..200.
 */
export function listingRuns(): Ask {
    return { type: ORCHESTRATION_LIST, payload: { limit: 20 } }
}


/**
 * This account's runs that are waiting on a person, for the background check
 * that keeps the count on the status line.
 *
 * <p><b>Filtered on the server, unlike {@link listingRuns}.</b> That listing is
 * the screen somebody opens to find a run and hides nothing; this one is asked
 * every few seconds for the one state that has stopped until somebody speaks,
 * and fetching every run to throw most of them away would be the poll paying
 * for a screen nobody opened.
 */
export function listingAsking(): Ask {
    return { type: ORCHESTRATION_LIST, payload: { state: 'asking', limit: 20 } }
}


/**
 * This account's runs that could be stalled, for the same background check.
 *
 * <p><b>{@code running}, and not a state of its own.</b> A stall sweep marks
 * {@code stalledSince} on the row rather than moving it out of {@code
 * running} — a stalled run is still trying, just quiet — so this asks for
 * every running run and {@link stalledWaiting} is what narrows that to the
 * ones the sweep has actually marked.
 */
export function listingStalled(): Ask {
    return { type: ORCHESTRATION_LIST, payload: { state: 'running', limit: 20 } }
}


/** One run, by the id a listing showed. */
export function readingRun(id: string): Ask {
    return { type: ORCHESTRATION_STATUS, payload: { id } }
}


/** Settle a question a conductor asked, or a cap it is waiting on. */
export const ORCHESTRATION_ANSWER = 'orchestration.answer'


/** Stop a run and, with it, every descendant it started. */
export const ORCHESTRATION_CANCEL = 'orchestration.cancel'


/**
 * The answer to one run's question, as the person wrote it.
 *
 * <p><b>Not trimmed and not shortened here.</b> The server refuses a blank —
 * "orchestration.answer needs the answer text; nothing was answered" — and
 * {@link typed} is what declines to send one, so this function has no opinion
 * left to hold: whatever reaches it is what the person typed after the id, and
 * editing it would be this client answering a conductor in its own words.
 */
export function answeringRun(id: string, answer: string): Ask {
    return { type: ORCHESTRATION_ANSWER, payload: { id, answer } }
}


/**
 * The answer to a question with options: one `Choice` per question, and `note` as the words beside
 * them. The server checks every label against the question it answers.
 */
export function answeringRunWith(id: string, choices: readonly Choice[], note?: string): Ask {
    return {
        type: ORCHESTRATION_ANSWER,
        payload: { id, choices, ...(note === undefined || note === '' ? {} : { answer: note }) },
    }
}


/**
 * Stop one run.
 *
 * <p><b>The cascade is the server's and it is not softened here.</b> {@code
 * Orchestrations} cancels a run's descendants depth-first, so this one id can
 * end a whole tree — which is why the wording a person reads says so before
 * they type it rather than after.
 */
export function cancellingRun(id: string): Ask {
    return { type: ORCHESTRATION_CANCEL, payload: { id } }
}


/** One question's answer, as `orchestration.answer`'s `choices` carries it. */
export interface Choice {
    readonly header: string
    readonly chosen: readonly string[]
    readonly other?: string
    readonly note?: string
}


/*
 * SCHEDULING, READ OUT OF ONE SENTENCE.
 *
 * `schedule.read` asks the server to read a sentence into a proposal and saves
 * nothing; the pair it proposes is saved only when a person says yes, as a
 * `schedule.define` and then a `trigger.define`. The rest list, pause, forget
 * and fire what was saved. Every one was routed before this client spoke it.
 */

/** Read one sentence into a proposed schedule and trigger. Saves nothing. */
export const SCHEDULE_READ = 'schedule.read'


/** Define a schedule: a cron, a zone, and the event it emits when it fires. */
export const SCHEDULE_DEFINE = 'schedule.define'


/** Every schedule the signed-in account defined. */
export const SCHEDULE_LIST = 'schedule.list'


/** Pause or resume one schedule. */
export const SCHEDULE_PAUSE = 'schedule.pause'


/** Forget one schedule. */
export const SCHEDULE_FORGET = 'schedule.forget'


/** Define a trigger: who runs, doing what, when an event arrives. */
export const TRIGGER_DEFINE = 'trigger.define'


/** Every trigger the signed-in account defined. */
export const TRIGGER_LIST = 'trigger.list'


/** Pause or resume one trigger. Pausing refuses the firings waiting on it. */
export const TRIGGER_PAUSE = 'trigger.pause'


/** Forget one trigger. */
export const TRIGGER_FORGET = 'trigger.forget'


/** Emit an event now, by hand, and answer with the firings it made. */
export const EVENT_FIRE = 'event.fire'


/** The firings, newest first. */
export const FIRING_LIST = 'firing.list'


/**
 * What `schedule.read` proposed: when, who, doing what, and where the result
 * goes — with the times it will really fire, which are the safeguard.
 *
 * <p><b>`when` is the model's words and is not checked by anything.</b>
 * `nextFires` is computed by the server from `cron` and `zone`, so a proposal
 * is shown with both side by side and a person can see a `when` that says one
 * thing beside fire times that say another.
 */
export interface Proposal {
    readonly cron: string
    readonly zone: string
    readonly when: string
    readonly agent: string
    /** Verbatim: what the trigger will be told to do, possibly several lines. */
    readonly task: string
    /** Whether the result goes into {@link conversation} rather than the inbox. */
    readonly intoConversation: boolean
    /** The tier it runs in; absent for the global tier or a conversation's own. */
    readonly project?: string
    /** Present exactly when {@link intoConversation} is. */
    readonly conversation?: string
    /** ISO instants. */
    readonly nextFires: readonly string[]
    readonly names: { readonly schedule: string; readonly trigger: string; readonly event: string }
}


/**
 * The frame that reads a sentence into a proposal.
 *
 * <p>The tier and the conversation are left off when there is none, on {@link
 * opening}'s ruling; the zone is always sent, because "9am" means nothing
 * without one and the terminal's own is what a person means.
 */
export function readingSchedule(
        text: string, zone: string, project?: string, conversation?: string): Ask {
    return {
        type: SCHEDULE_READ,
        payload: {
            text, zone,
            ...(project === undefined ? {} : { project }),
            ...(conversation === undefined ? {} : { conversation }),
        },
    }
}


/** The schedule half of saving a proposal. */
export function definingSchedule(proposal: Proposal): Ask {
    return {
        type: SCHEDULE_DEFINE,
        payload: {
            schedule: proposal.names.schedule, cron: proposal.cron, zone: proposal.zone,
            emits: proposal.names.event,
        },
    }
}


/**
 * The trigger half of saving a proposal.
 *
 * <p><b>No limits, and never a project beside a conversation.</b>
 * `TriggerDefineHandler` refuses a conversation named with `maxModelCalls` or
 * `maxTurns` — a turn there takes the conversation's own budget — and refuses a
 * project named beside a conversation, which already has a home. The server
 * never proposes both; this sends a conversation only when the result goes
 * there, and a project only when there is one.
 */
export function definingTrigger(proposal: Proposal): Ask {
    return {
        type: TRIGGER_DEFINE,
        payload: {
            trigger: proposal.names.trigger, event: proposal.names.event,
            agent: proposal.agent, task: proposal.task,
            ...(proposal.project === undefined ? {} : { project: proposal.project }),
            ...(proposal.intoConversation && proposal.conversation !== undefined
                ? { conversation: proposal.conversation } : {}),
        },
    }
}


/** Ask for every schedule. */
export function listingSchedules(): Ask {
    return { type: SCHEDULE_LIST, payload: {} }
}


/** Ask for every trigger. */
export function listingTriggers(): Ask {
    return { type: TRIGGER_LIST, payload: {} }
}


/** Pause a schedule, or resume it. `paused` is always written: the handler refuses its absence. */
export function pausingSchedule(schedule: string, paused: boolean): Ask {
    return { type: SCHEDULE_PAUSE, payload: { schedule, paused } }
}


/** Pause a trigger, or resume it. */
export function pausingTrigger(trigger: string, paused: boolean): Ask {
    return { type: TRIGGER_PAUSE, payload: { trigger, paused } }
}


/** Forget a schedule. */
export function forgettingSchedule(schedule: string): Ask {
    return { type: SCHEDULE_FORGET, payload: { schedule } }
}


/** Forget a trigger. */
export function forgettingTrigger(trigger: string): Ask {
    return { type: TRIGGER_FORGET, payload: { trigger } }
}


/** Emit an event now. No data: a schedule fired by hand carries none either. */
export function firingEvent(event: string): Ask {
    return { type: EVENT_FIRE, payload: { event } }
}


/** Ask for the last ten firings. */
export function listingFirings(): Ask {
    return { type: FIRING_LIST, payload: { limit: 10 } }
}


/**
 * Ask a run to stop, which is what Ctrl-C now means.
 *
 * <b>Routed since the socket surface was built and never called by this
 * client.</b> Ctrl-C quit the terminal and left the run going server-side,
 * spending a budget nobody was watching and filing an answer nobody would read.
 *
 * <p><b>It is a request and not a kill</b>, and everything this module says
 * about it is careful to keep that true. `JobCancelHandler`: the loop honours a
 * cancel <i>between turns</i>, so the answer still reads `RUNNING` and
 * `cancelRequested` is what says the request landed. A run that was already
 * finishing answers anyway — cancelling a finished job "changes nothing" — so
 * nothing here, and nothing in {@link describeCancelling}, may tell a person
 * their run has stopped. What stopped it, if anything did, is read back off
 * `job.status` like any other outcome.
 */
export const JOB_CANCEL = 'job.cancel'


/**
 * Ask to be sent the tokens as a model produces them, or to stop.
 *
 * <p><b>The subscription belongs to this listener and not to a run.</b> One
 * frame turns it on for everything this session watches from then on, and
 * another turns it off — which is why a person who has read enough of a model
 * thinking can stop it without ending anything.
 */
export const JOB_STREAM = 'job.stream'


/**
 * One frame this client wants sent, as a value.
 *
 * <b>The whole of the dependency inversion is this shape.</b> `logic/` says
 * what to ask; `view/` hands it to `connection.ask(ask.type, ask.payload)`.
 * Nothing here knows that a socket is how it gets there.
 */
export interface Ask {
    /** A dotted discriminator, from the constants above. */
    readonly type: string

    /** The frame's own data. Always an object, never null — see {@link opening}. */
    readonly payload: Record<string, unknown>
}


/**
 * The frame that opens a conversation.
 *
 * <b>An empty payload when no project is named, and not a payload of nulls.</b>
 * `OpenConversationRequest` is entirely optional and a body naming neither a
 * budget nor a cap still takes `plowshare.conversations.default-budget` — an
 * operator's number goes on meaning what it always meant. A client filling in
 * nulls would be stating something it was never asked, which is how a limit
 * nobody chose gets applied.
 */
export function opening(project?: string): Ask {
    return {
        type: CONVERSATION_OPEN,
        payload: project === undefined ? {} : { project },
    }
}


/**
 * The frame that takes a turn.
 *
 * <b>`agent` is a parameter and not a default</b>, though the plan's sketch of
 * this signature had two arguments. `AgentRunHandler` reads `agent` with
 * `Payloads.required` before it reads anything else, so a turn without one is
 * refused by the server; and inventing a name here would be this client
 * deciding who answers, which is `agent.list`'s question and the person's
 * answer.
 */
export function speaking(
        conversation: string, agent: string, text: string, session: string): Ask {
    return {
        type: AGENT_RUN,
        payload: { agent, conversation, task: text, session },
    }
}


/**
 * The frame that asks for every project.
 *
 * <b>An empty payload, because the endpoint takes nothing.</b> {@code
 * ProjectListHandler} binds no record: unfiltered and unpaged, since a project
 * is the thing a person chooses <i>between</i> here.
 */
export function listingProjects(): Ask {
    return { type: PROJECT_LIST, payload: {} }
}


/**
 * The frame that asks for one home's conversations.
 *
 * <b>An empty payload for the global tier, and not `{ project: null }`</b> —
 * {@link opening}'s ruling, and {@code RequestedHome.in} reads an absent field
 * and a null identically anyway, so the null would be this client stating
 * something it was never asked.
 *
 * <p><b>The lifecycle is not named either, which is a decision and not an
 * omission.</b> {@code RequestedLifecycle} reads an absent one as {@code ACTIVE}
 * — "the state a person works in is the one they mean when they do not say" —
 * and the three others are reachable only by naming one. This client names none,
 * because a terminal that could list archived conversations and not unarchive
 * them would be showing a person rows they cannot act on.
 */
export function listingConversations(project?: string): Ask {
    return {
        type: CONVERSATION_LIST,
        payload: project === undefined ? {} : { project },
    }
}


/**
 * The frame that asks what this deployment serves, of both kinds.
 *
 * <b>The tier is carried and the session is not.</b> `AgentListHandler` reads
 * `project` through `RequestedProjectId.forListing` and deliberately hands a
 * null session on: a listing is a read, and a session on it would let any
 * caller enumerate another live session's local `.plowshare/`. The handler
 * drops the field; this client does not send it, which is the same decision
 * made where it is cheapest to keep.
 *
 * <p>An empty payload for the global tier and not `{ project: null }`, on
 * {@link opening}'s ruling.
 */
export function listingAgents(project?: string): Ask {
    return {
        type: AGENT_LIST,
        payload: project === undefined ? {} : { project },
    }
}


/**
 * The frame that reads what a conversation's prompt costs, priced against
 * `agent`.
 *
 * <p><b>The agent is named even when the server could work it out.</b> Without
 * one a conversation is priced against whoever answered it last, and a person
 * who has just moved to somebody else is asking about the next turn, not the
 * previous one — the model, and so the room, may differ.
 */
export function measuring(conversation: string, agent: string): Ask {
    return { type: CONVERSATION_CONTEXT, payload: { conversation, agent } }
}


/** The frame that follows one conversation's log. See {@link CONVERSATION_FOLLOW}. */
export function followingLog(conversation: string): Ask {
    return { type: CONVERSATION_FOLLOW, payload: { conversation } }
}


/**
 * The last {@link LOG_BACK} entries the chat draws, read back from the log's end in one read —
 * what replay shows on opening. The page comes newest first; {@link backPageOf} turns it round.
 *
 * <p><b>`drawn` as well as `kinds`</b>: an answer that asked for tools is of a kind the chat
 * draws and is still never drawn, and the server is the one that can leave it out before the
 * limit — otherwise a tool-heavy conversation spends most of its forty on them.
 */
export function readingTail(conversation: string): Ask {
    return {
        type: CONVERSATION_TRAJECTORY,
        payload: { conversation, tail: true, limit: LOG_BACK, kinds: [...DRAWN_KINDS], drawn: true },
    }
}


/** The {@link LOG_BACK} drawn entries before an ordinal — what `/earlier` shows. */
export function readingEarlier(conversation: string, before: number): Ask {
    return {
        type: CONVERSATION_TRAJECTORY,
        payload: { conversation, before, limit: LOG_BACK, kinds: [...DRAWN_KINDS], drawn: true },
    }
}


/**
 * One page of what was written to a conversation's log after an ordinal — a catch-up — of the
 * kinds the chat draws and without the answers that asked for tools, which is all a catch-up
 * draws. The page's `through` is still the whole
 * log's. A `limit` only when a reading wants the log's `through` rather than its entries: one
 * row is enough to carry it, and the server's own page size is what a catch-up reads by.
 */
export function readingAfter(conversation: string, after: number, offset: number, limit?: number): Ask {
    const kinds = [...DRAWN_KINDS]
    return {
        type: CONVERSATION_TRAJECTORY,
        payload: limit === undefined
            ? { conversation, after, offset, kinds, drawn: true }
            : { conversation, after, offset, limit, kinds, drawn: true },
    }
}


/**
 * The rows after an ordinal, of every kind — what the tracer reads to draw a turn's tool lines
 * as they happen. Not `drawn`: an answer that asked for tools is exactly what it is after.
 */
export function readingTrace(conversation: string, after: number, offset: number): Ask {
    return { type: CONVERSATION_TRAJECTORY, payload: { conversation, after, offset } }
}


/** The last {@link LOG_PAGE} rows of a log, every kind — where the explorer opens. */
export function readingLogTail(conversation: string): Ask {
    return { type: CONVERSATION_TRAJECTORY, payload: { conversation, tail: true, limit: LOG_PAGE } }
}


/** The {@link LOG_PAGE} rows before an ordinal, every kind — the explorer reading further back. */
export function readingLogBefore(conversation: string, before: number): Ask {
    return { type: CONVERSATION_TRAJECTORY, payload: { conversation, before, limit: LOG_PAGE } }
}


/**
 * The frame that asks which conversation this agent is still having.
 *
 * <b>The agent is required and the tier is not</b>, which is the shape of the
 * question: "which conversation" has no answer without somebody to ask it
 * about, and the server refuses a payload that names none rather than answering
 * "you have none yet" — on the strength of which this client would open a
 * second one every time it started.
 *
 * <p>An empty tier is the global one and never `{ project: null }`, on {@link
 * opening}'s ruling.
 */
export function continuing(agent: string, project?: string): Ask {
    return {
        type: CONVERSATION_LATEST,
        payload: project === undefined ? { agent } : { agent, project },
    }
}


/** The frame that asks how one run is going. Its field is named for the job. */
export function checking(job: string): Ask {
    return { type: JOB_STATUS, payload: { job } }
}


/**
 * The frame that asks one run to stop, by the handle it is being followed by.
 *
 * <p>The same field {@link checking} names, because it is the same handle: a
 * client that followed one id and cancelled another would stop a run nobody was
 * watching and go on watching the one it meant to stop.
 *
 * @see JOB_CANCEL for why nothing that comes back is evidence that it stopped
 */
export function cancelling(job: string): Ask {
    return { type: JOB_CANCEL, payload: { job } }
}


/**
 * Ask for the tokens, or ask for them to stop.
 *
 * <p><b>`on` is always written, never left out.</b> The server defaults an
 * absent field to true, which is the right default for a frame somebody sent on
 * purpose — but a client that relied on it could not turn the stream OFF
 * without a second spelling, and the two would then disagree about what an
 * empty payload meant.
 */
export function streaming(on: boolean): Ask {
    return { type: JOB_STREAM, payload: { on } }
}

/** Replace all conversation log subscriptions for this session. */
export function followingLogs(conversations: readonly string[]): Ask {
    return { type: CONVERSATION_FOLLOW, payload: { conversations: [...conversations] } }
}
