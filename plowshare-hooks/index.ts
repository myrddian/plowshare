/**
 * The contract a Plowshare hook is written against. Types only, and only
 * erasable syntax, so Node strips it and the server's swc4j strips it the same
 * way. A hook imports it with `import type`, which is erased — a value import
 * would be a load failure on the server, where hooks have no IO.
 *
 * Specs: implementation rationale §4 (the run
 * stages) and 2026-09-28-hooks-reach-the-log-design.md §3–4 (the log stages),
 * and 2026-09-30-local-hooks-are-served-design.md (a person's own hooks, in .plowshare/hooks/).
 * The names below are pinned to the server by HookContractMirrorTest.
 */

export type Stage = 'prompt.pre' | 'prompt.post' | 'tool.pre' | 'tool.post' | 'step.post' | 'log.open' | 'log.close' | 'stage.pre' | 'stage.post' | 'approval.pre' | 'approval.post' | 'fold.post' | 'delivery.pre' | 'delivery.post'

export type Mode = 'volatile' | 'durable'

/** Which door a log came through. A `turn` log is a conversation: the only one a person talks in. */
export type Origin = 'memory' | 'turn' | 'delegation' | 'curator' | 'submission' | 'event' | 'orchestration' | 'board'

/** What every run stage knows about the run. Absent, not null, when a value does not apply. */
export interface Context {
    readonly agent: string
    readonly bot: boolean
    readonly project?: string
    readonly conversation?: string
    /** Always 'server': every hook runs on the server, a person's local ones included. */
    readonly side: 'server' | 'local'
    /** On a `run` call only: what environment.yml allows where the command would run. */
    readonly environment?: RunEnvironment
}

export interface RunEnvironment {
    /** Where the command would run. A local hook's `{ allow: true }` counts only when this is 'local'. */
    readonly side: 'server' | 'local'
    readonly mode: 'off' | 'gated' | 'ask' | 'open'
    readonly shells: boolean
    /** environment.yml's `isolation:` for that side; `none` until the isolation slice ships another. */
    readonly isolation: string
}

/** The retained information target a stage is about; allocation may not exist yet. */
export interface DocumentContext {
    readonly operation: string
    readonly resource: string | null
    readonly revision: string | null
    readonly generation: number
    readonly stage: string
    readonly attempt: number
    readonly sourceUri?: string | null
}

export interface OrchestrationContext {
    readonly id: string
    /** The orchestration definition's name. */
    readonly definition: string
    /** The stage moving, on `stage.*` and on an acceptance command's `approval.pre`. */
    readonly stage?: string
}

/** What every log stage knows about the log. Absent, not null, when a value does not apply. */
export interface LogContext {
    /** The agent whose log this is; absent on a `turn` log, which names none. */
    readonly agent?: string
    readonly bot: boolean
    readonly project?: string
    readonly side: 'server' | 'local'
    readonly origin: Origin
    /** The log's id. */
    readonly log: string
    /** The same id, on a `turn` log only. */
    readonly conversation?: string
    /** On `approval.pre`: what environment.yml allows where the command would run. */
    readonly environment?: RunEnvironment
    /** On `stage.*`, and on `approval.pre` for an orchestration's check or acceptance command. */
    readonly orchestration?: OrchestrationContext
    readonly document?: DocumentContext
}

export interface PromptPreEvent {
    readonly context: Context
    /** The person's words, verbatim. */
    readonly utterance: string
}

export interface PromptPostEvent {
    readonly context: Context
    readonly reply: string
    /** The names of the tools the model asked for in this reply. */
    readonly tools: readonly string[]
}

export interface ToolPreEvent {
    readonly context: Context
    readonly tool: string
    readonly args: Readonly<Record<string, unknown>>
}

export interface ToolPostEvent {
    readonly context: Context
    readonly tool: string
    readonly args: Readonly<Record<string, unknown>>
    readonly result: string
}

export type PromptPreDecision = undefined | { readonly add: string; readonly mode: Mode }

export type PromptPostDecision = undefined | { readonly note: string } | { readonly redact: string }

/** Returning nothing allows. A rewrite is re-checked by the tool's own permission check. */
export type ToolPreDecision =
    | undefined
    | { readonly allow: true }
    | { readonly deny: string }
    | { readonly rewrite: Readonly<Record<string, unknown>> }
    /** `run` only: a person decides before the command starts. Denied where nobody can be asked. */
    | { readonly ask: string }

export type ToolPostDecision = undefined | { readonly note: string } | { readonly redact: string }

/** One step that asked for tools, shown after its results are in. */
export interface StepPostEvent {
    readonly context: Context
    readonly step: {
        readonly number: number
        readonly model?: string
        readonly calls: readonly { readonly tool: string; readonly args: unknown }[]
        readonly results: readonly string[]
        readonly thinking?: string
    }
}

export type StepPostDecision = undefined | { readonly note: string }

/** A log's row is committed and no turn has started. */
export interface LogOpenEvent {
    readonly context: LogContext
    /** The log that delegated to this one, on a `delegation` log. */
    readonly parent?: string
    /** The account that owns the log, when one does. */
    readonly owner?: string
}

/**
 * Added once, after the agent's prompt, to every request of this log — byte for byte, and never
 * changed afterwards. A clock or a counter here is frozen at the log's first moment.
 */
export type LogOpenDecision = undefined | { readonly add: string }

/**
 * Why a log closed, always lower case: a machine log's run ending (never `awaiting`, which a
 * person's answer continues), an orchestration's terminal state (its conductor's log), or the
 * lifecycle a person's conversation moved to out of `active`. `cancelled` is both a run ending and
 * an orchestration state; `context.origin` tells them apart.
 */
export type LogEnding =
    | 'answered' | 'turn_cap' | 'call_budget' | 'cancelled' | 'stuck' | 'unavailable'
    | 'sub_agent_failed' | 'session_gone' | 'call_failures'
    | 'finished' | 'failed' | 'capped'
    | 'archived' | 'to_be_ejected'

/** A log will take no more turns. */
export interface LogCloseEvent {
    readonly context: LogContext
    readonly ending: LogEnding
    /** How many turns the log holds. */
    readonly turns: number
}

/** Sent to the log owner's inbox; a log nobody owns records it and tells nobody. */
export type NotifyDecision = undefined | { readonly notify: string }

/** A result on its way from a run's log. The context is the source log's. */
export interface DeliveryEvent {
    readonly context: LogContext
    readonly source: { readonly log: string; readonly origin: Origin }
    /**
     * At `delivery.pre`, where the delivery will try first; at `delivery.post`, where it went.
     * They can differ: a person's conversation that refuses the result sends it to the inbox,
     * so pre says `conversation` and post says `inbox`.
     */
    readonly destination: 'parent' | 'conversation' | 'inbox' | 'nowhere'
    readonly text: string
}

/** A result that was delivered; `destination` is where it actually went. */
export interface DeliveredEvent extends DeliveryEvent {
    readonly delivered: true
}

/** Appended to the delivered text. A delivery cannot be rerouted or withheld. */
export type DeliveryPreDecision = undefined | { readonly note: string }

/** One orchestration stage. `index` counts from zero in the run's stage order. */
export interface StageShown {
    readonly id: string
    /** The stage item's text on the conductor's list. */
    readonly title: string
    readonly index: number
    readonly count: number
}

/** A locked stage is about to start (from pending) or be returned to (from done). */
export interface StagePreEvent {
    readonly context: LogContext
    readonly stage: StageShown
    readonly returning?: boolean
    /** Returns the run has left once this move stands. */
    readonly returnsLeft?: number
}

/** A stage is about to be marked done; every system gate, its check included, has passed. */
export interface StagePostEvent {
    readonly context: LogContext
    readonly stage: StageShown
    /** The summary the list will store, the harness's own sentences included. */
    readonly summary: string
    /** On a checked stage: the run's check, which has just passed. */
    readonly check?: { readonly command: readonly string[]; readonly passed: true }
}

export type ApprovalScope = 'once' | 'conversation' | 'project'

/**
 * A person is about to be asked to allow a command. `context.environment` is where it would run.
 * There is no decision that approves: a hook may refuse the command or add to the question.
 */
export interface ApprovalPreEvent {
    readonly context: LogContext
    readonly argv: readonly string[]
    readonly cwd: string
    /** Why it is being asked, before any hook's note. */
    readonly reason?: string
    /** Whether the person is in the asking conversation, as against told through the inbox. */
    readonly attended: boolean
    readonly scopes: readonly ApprovalScope[]
}

export type ApprovalDecision = 'allow' | 'deny' | 'revoke'

/** A person answered an approval, or revoked a standing one. The context is the root's log. */
export interface ApprovalPostEvent {
    readonly context: LogContext
    readonly approval: string
    readonly decision: ApprovalDecision
    /** What was allowed or revoked; absent on a denial. */
    readonly scope?: ApprovalScope
}

/**
 * The folder has summarised the turns up to `through`, and the fold is not yet saved. Read
 * `summary` first: keep a marker only when the folder lost it, so it does not pile up over
 * repeated folds.
 */
export interface FoldPostEvent {
    readonly context: LogContext
    readonly through: number
    /** Entries in the span the fold covers. */
    readonly entries: number
    /** What the last turn sent and added, in tokens, as the endpoint measured it. */
    readonly estimatedTokens: number
    /** The folder's own summary of the span, before anything a hook keeps. */
    readonly summary: string
}

/**
 * `stage.*` and `approval.pre` fail closed: a denial refuses the move or the command, and so does
 * a hook that throws. A note is appended to the `todo_write` result, or to the question a person
 * sees.
 */
export type GateDecision = undefined | { readonly deny: string } | { readonly note: string }

/**
 * `keep` is appended verbatim after the folder's own text. What all hooks keep together is capped
 * at 512 tokens, taken in order: a keep that would pass the cap is dropped whole and recorded,
 * never cut short. `notify` reaches the log owner's inbox only once the fold is saved. All of a
 * fold's `fold.post` hooks share one time limit, so a hook run after a slow one has less of it.
 */
export type FoldPostDecision = undefined | { readonly keep: string } | { readonly notify: string }

/** A run stage: every hook that declares it is called. */
export interface RunStage<Event, Decision> {
    handle(event: Event): Decision
}

/** A tool stage names the tools it applies to: exact names, or a prefix ending in `*`. */
export interface ToolStage<Event, Decision> {
    readonly tools: readonly string[]
    handle(event: Event): Decision
}

/** A log stage names the origins it fires for; naming none is every origin. */
export interface LogStage<Event, Decision> {
    readonly origins?: readonly Origin[]
    handle(event: Event): Decision
}

export interface Hook {
    /** Unique within its directory; what the log records. */
    readonly name: string
    readonly stages: {
        readonly 'prompt.pre'?: RunStage<PromptPreEvent, PromptPreDecision>
        readonly 'prompt.post'?: RunStage<PromptPostEvent, PromptPostDecision>
        readonly 'tool.pre'?: ToolStage<ToolPreEvent, ToolPreDecision>
        readonly 'tool.post'?: ToolStage<ToolPostEvent, ToolPostDecision>
        readonly 'step.post'?: RunStage<StepPostEvent, StepPostDecision>
        readonly 'log.open'?: LogStage<LogOpenEvent, LogOpenDecision>
        readonly 'log.close'?: LogStage<LogCloseEvent, NotifyDecision>
        readonly 'stage.pre'?: LogStage<StagePreEvent, GateDecision>
        readonly 'stage.post'?: LogStage<StagePostEvent, GateDecision>
        readonly 'approval.pre'?: LogStage<ApprovalPreEvent, GateDecision>
        readonly 'approval.post'?: LogStage<ApprovalPostEvent, NotifyDecision>
        readonly 'fold.post'?: LogStage<FoldPostEvent, FoldPostDecision>
        readonly 'delivery.pre'?: LogStage<DeliveryEvent, DeliveryPreDecision>
        readonly 'delivery.post'?: LogStage<DeliveredEvent, NotifyDecision>
    }
}
