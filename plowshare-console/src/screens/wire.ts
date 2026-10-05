/**
 * The shapes the four screens read, written down once.
 *
 * Assertions about the wire and not validated facts, exactly as `repl/wire.ts`
 * says of its own: `api.get`'s javadoc settles that at the transport, and every
 * renderer here is built to show a missing field as missing rather than to
 * throw over a page. What they buy is that the contract is in one file.
 *
 * Each is a copy of a Java record in `io.aeyer.plowshare.server.api` or
 * `io.aeyer.plowshare.protocol`, named the same, and the sentences below are the
 * halves of those records' javadoc that a renderer has to obey.
 */

/**
 * What this console calls the tier a conversation gets when nobody chose one.
 *
 * **One word for the whole console, because there were five copies of it.**
 * `inbox.ts` and `memory.ts` each carried a bare `'global'`, and three inputs
 * each carried their own `'blank for global'`. Every one of them now reads this.
 *
 * It is a *label* and never an identifier: on the wire this tier is the absence
 * of a project, which `Home` models as `new Home(null)` and whose `of` refuses a
 * blank name in as many words. Nothing may send this string as a project name —
 * see `picker.ts`'s `GLOBAL` for why a reserved name would be worse than the
 * absence it replaced.
 *
 * <h2>The Java side is not the same problem, and was mistaken for it</h2>
 *
 * `AgentTools.tier` and `MemoryTools.tier` are byte-identical and are real
 * duplication, worth collapsing. `ConversationTools.tier` looks like a third
 * spelling of the same thing and is not: it answers "the global tier" where the
 * other two answer "global", because its call sites write `"in " + tier(...)`
 * while theirs write `"the " + tier(...) + " archive"`. Unify them on either
 * wording and one end reads "the the global tier archive" and the other reads
 * "in global". The difference is grammar, not drift, and a later reader tidying
 * it into one constant would be introducing the bug rather than removing one.
 */
export const GLOBAL_TIER = 'global';

/**
 * One project: `GET /v1/projects` and `POST /v1/projects/{name}/workspace`.
 *
 * **`workspace` is where the project is; `workspace` and `lent` together are
 * what it reaches.** They are two fields and not one list because only the first
 * is the project's identity -- it is the `<PATH>` of `MACHINE/PATH/NAME`, so an
 * identity composed from a list would change whenever the list reordered. A
 * renderer that showed only the workspace would show a *shorter* leash than the
 * one being enforced, which is the failure the next paragraph forbids in the
 * other direction.
 *
 * **`lent` is complete as sent, unlike `exclusions`.** The server adds paths of
 * its own to the fence and none of its own to the grant: it fences things off,
 * it does not lend them. So this list is exactly the row's, and there is no
 * server half of it for a renderer to worry about hiding.
 *
 * **`exclusions` is the effective list and never the row's.** `ProjectView`
 * argues it: the `projects` row stores only the paths the project itself fenced
 * off, and `ProjectStore.effectiveExclusions` adds the ones no row may override
 * before this answer is built. Two consequences a renderer has to hold at once:
 *
 * - Showing this list is showing the leash as it is actually enforced, which is
 *   the single thing a person reads this screen to learn.
 * - **The two halves are not separable from this answer.** The wire carries one
 *   list and no marker on any element of it, so nothing here can say which
 *   entries came from the row. See `projects.ts`, which says that on the screen
 *   rather than guessing.
 */
export interface ProjectView {
  readonly kind?: 'project' | 'personal';
  readonly name: string;
  readonly workspace: string;
  readonly lent: readonly string[] | null | undefined;
  readonly exclusions: readonly string[] | null | undefined;
}

/**
 * One line of the index: `GET /v1/memories/index?project=`.
 *
 * **No body, and that is the whole reason this projection exists.** `TocEntry`
 * calls it the librarian's attention budget: the index is read whole, so a body
 * here is a body in every prompt. A screen that fetched each body to render a
 * list would put the cost back on a different budget and call it a feature.
 *
 * `unsearchable` is true when the memory has no embedding, so `recall` cannot
 * reach it however the question is phrased. It is still active and still
 * readable by id.
 */
export interface TocEntry {
  readonly id: string;
  readonly summary: string;
  readonly scope: string;
  readonly unsearchable: boolean;
}

/** Where and when a memory came from. */
export interface Provenance {
  readonly at: string;
  readonly by: string;
  readonly where: string;
}

/** A tombstone: why a memory stopped being true, and who said so. */
export interface Invalidation {
  readonly at: string;
  readonly by: string;
  readonly reason: string;
}

/**
 * One memory in full: `GET /v1/memories/{id}`, and every hit of a recall.
 *
 * `state` is one of four words on the wire and **nothing here enumerates them**
 * — `MemoryState.wireName` exists so that the spelling is a contract with rows
 * already written, and a state added later must reach this console without a
 * change to this file.
 *
 * `lastUsed`, `supersedes`, `supersededBy` and `invalidation` are legitimately
 * absent, per `Memory`'s own compact constructor, which rejects only the nulls
 * that have no meaning.
 */
export interface MemoryView {
  readonly id: string;
  readonly summary: string;
  readonly scope: string;
  readonly formed: Provenance | null | undefined;
  readonly state: string;
  readonly pinned: boolean;
  readonly uses: number | null | undefined;
  readonly lastUsed: string | null | undefined;
  readonly body: string;
  readonly supersedes: string | null | undefined;
  readonly supersededBy: string | null | undefined;
  readonly invalidation: Invalidation | null | undefined;
  readonly home: { readonly project: string | null } | null | undefined;
}

/**
 * What `POST /v1/memories/recall` answers.
 *
 * **`unsearchable` is the field that stops an empty answer being a lie.**
 * Without it an archive holding one memory with no vector answers the same
 * shape as an empty archive, and a reader told the archive is empty stops
 * asking. Zero means the answer is complete; anything else means the search was
 * partial and the screen has to say so.
 */
export interface RecallResponse {
  readonly question: string;
  readonly limit: number;
  readonly memories: readonly MemoryView[] | null | undefined;
  readonly unsearchable: number | null | undefined;
}

/**
 * One proposal: `GET /v1/proposals?project=`.
 *
 * `state` travels as `pending`, `accepted` or `rejected` and **nothing here
 * enumerates them**, on `ProposalState.wireName`'s own terms: those spellings
 * are a contract with the rows and with a database check constraint, and a
 * fourth would have to reach this console without a change here.
 *
 * `action` is deliberately an open string — `ProposalStore.PROMOTE` today, "so
 * a second kind of question needs no migration".
 *
 * `resolvedAt`, `resolvedBy` and `resolution` are absent while it waits;
 * `proposedBy` is absent only on a row written before the column existed.
 */
export interface ProposalView {
  readonly id: string;
  readonly memoryId: string;
  readonly project: string | null;
  readonly action: string;
  readonly reason: string;
  readonly state: string;
  readonly createdAt: string;
  readonly proposedBy: string | null | undefined;
  readonly resolvedAt: string | null | undefined;
  readonly resolvedBy: string | null | undefined;
  readonly resolution: string | null | undefined;
}

/**
 * What settling one did: `POST /v1/proposals/{id}/resolve`.
 *
 * `demoted` is the acceptance half and is surfaced rather than silent: an
 * approval adds to the tier every project reads, and a promotion that quietly
 * pushed other memories out of that index would make it shrink for a reason no
 * caller could see. Cold, not deleted.
 */
export interface ResolvedProposal {
  readonly proposal: ProposalView | null | undefined;
  readonly promotedId: string | null | undefined;
  readonly demoted: readonly string[] | null | undefined;
}

/**
 * What a reconsider pass did: `POST /v1/proposals/reconsider?project=`.
 *
 * Both lists, because a re-open can be refused -- the row's waiting place may
 * have been taken since it was settled -- and a count alone cannot tell a tier
 * with nothing to re-open from one where every row was blocked.
 */
export interface Reconsidered {
  readonly reopened: readonly string[] | null | undefined;
  readonly refused: readonly string[] | null | undefined;
}

/**
 * One call a model asked for: `EntryView.AskedView`, inside a `toolCalls` array.
 *
 * **Three fields for the arguments and not one**, and the reason is the same as
 * the entry's own: `file_edit` sends a whole file as an argument, so a view
 * that carried them whole would be unbounded in exactly the dimension the page
 * exists to bound. `arguments` is what fitted, `length` is what there was, and
 * `cut` is the only way to tell one from the other without knowing the server's
 * cap.
 *
 * `name` is **model-supplied text**: a call to a tool that does not exist is
 * still recorded, so this is neither a name from any registry nor free of line
 * breaks, and whatever renders it for a person is what has to flatten it.
 *
 * `salient` (V62) is the one argument `ToolLines.salient` picked out, whole even
 * where `arguments` is cut -- null for a call recorded before the column existed.
 * `opened` (V62) is the child conversation this call started and the agent that
 * ran in it, null for a call that opened none.
 */
export interface AskedView {
  readonly id: string;
  readonly name: string;
  readonly arguments: string;
  readonly length: number;
  readonly cut: boolean;
  readonly salient: string | null | undefined;
  readonly opened:
    | { readonly conversation: string; readonly agent: string }
    | null
    | undefined;
}

/**
 * One entry of a conversation: an element of either reading's page.
 *
 * `kind` is **the stored spelling and not the constant's name** -- `utterance`,
 * `tool_result`, `attempt_failed` -- so that a client reading this and a person
 * reading the table are looking at the same word. Nothing here enumerates them
 * as a type, on `describeKind`'s reasoning: a kind added on the server has to
 * reach this console without a change to this file.
 *
 * **Four fields are absences with meanings, and none of them is a zero.**
 *
 * - `excerpt` is null for a tool result whose payload has been **ejected**, and
 *   `ejectedAt` is set for exactly those rows. That is what tells a reader a
 *   blank is not a tool that returned nothing. `cut` is false for one of these:
 *   the page did not cut it and there is nothing further to ask for.
 * - `supersededBy` is the ordinal of the summary that folded this entry away.
 *   **Never set on an entry from the chat reading**, which is what makes the two
 *   readings tell one story.
 * - `recordedAt` is null for an entry written before the column existed. Null is
 *   a real answer and is rendered as "not recorded" rather than as an epoch.
 * - `tookMillis` is null for a kind that records no operation and for one nobody
 *   measured. **Not the gap to the entry before it**, which holds everything
 *   that happened in between.
 *
 * `handle` is the address `result_read` redeems a result at, and is the only
 * way to reach the whole of a cut entry -- **there is no way to ask for the rest
 * of any other cut entry through this surface**, which is a real limit rather
 * than an oversight.
 *
 * `tookMillis` is a Java `Long` on the far side and arrives as a JSON number;
 * it is typed as `number` here for that reason and not because anything has
 * bounded it.
 *
 * `dispatch`, `wireModel` and `completion` are execution provenance (V39): which
 * model spoke, and what its completion was. **Set only on an `answer` or a
 * `refusal`**, and null on everything else and on any row written before the
 * column existed -- an utterance has no model. The conversational actor is still
 * the assistant; these say who answered for it, which is how a rerouted refusal
 * is legible as one. `dispatch` is `"primary"` or `"fallback"`; `completion` is
 * `"answered"`, `"refused"`, `"cut_off"` or `"called_tools"`.
 *
 * `outcome` (V62) is a `tool_result`'s outcome in a word, as the runtime told
 * it -- null for every other kind and for a result older than the column.
 */
export interface EntryView {
  readonly ordinal: number;
  readonly turnOrdinal: number;
  readonly kind: string;
  readonly excerpt: string | null | undefined;
  readonly length: number;
  readonly cut: boolean;
  readonly ejectedAt: string | null | undefined;
  readonly supersededBy: number | null | undefined;
  readonly toolCallId: string | null | undefined;
  readonly toolCalls: readonly AskedView[] | null | undefined;
  readonly handle: string | null | undefined;
  readonly recordedAt: string | null | undefined;
  readonly tookMillis: number | null | undefined;
  readonly dispatch: string | null | undefined;
  readonly wireModel: string | null | undefined;
  readonly completion: string | null | undefined;
  readonly outcome: string | null | undefined;
}

/**
 * One page of either reading: `GET /v1/conversations/{id}/chat` and
 * `/trajectory`.
 *
 * **All three numbers are needed and none is derivable**, which is
 * `EntryPageView`'s own argument. `total` is the conversation's and not the
 * page's, and it is *different between the two readings of the same
 * conversation* -- a chat's total counts what a model is shown and a
 * trajectory's counts everything, so the gap between them is what folding and
 * the roleless kinds have taken out.
 *
 * `limit` is **the number the server used and not the one this console asked
 * for**, which differ exactly when the ask was over the server's cap. A client
 * pages by adding this to `offset`; echoing the request instead would step over
 * entries it never saw. Nothing in this console hardcodes that cap, for the
 * same reason.
 */
export interface EntryPageView {
  readonly entries: readonly EntryView[] | null | undefined;
  readonly total: number;
  readonly offset: number;
  readonly limit: number;
}

/**
 * One number this surface cannot give, and the reason: `ContextView.Unavailable`.
 *
 * `component` is spelled exactly as the field it stands for, so a client pairs
 * the two without a table of its own. `reason` is long on purpose -- a one-word
 * reason is what makes an absence look like an oversight -- and is **rendered
 * rather than summarised** here.
 */
export interface Unavailable {
  readonly component: string;
  readonly reason: string;
}

/** One tool's share of the fixed block, in characters of the JSON sent. */
export interface ToolCost {
  readonly name: string;
  readonly characters: number;
}

/**
 * What one agent's fixed block costs: `ContextView.Prefix`.
 *
 * **Characters, and never tokens.** It measures the JSON the transport really
 * sends, through the same builder a request's `tools` array goes through, so it
 * cannot drift from the wire -- and it must not be scaled into a token count,
 * because a characters-per-token ratio is the estimate the whole context
 * surface exists to refuse.
 *
 * `systemPromptCharacters` is **a length and never the text**: `AgentView`
 * declines to ship an agent's prompt to a browser and the reason is real. It is
 * also only the agent's own prompt -- a conversation with a standing seam has
 * the summary merged into the same system message at request time, which is a
 * fact about that conversation and not about this agent.
 *
 * `tools` is one entry per tool **this boot would actually offer this agent**,
 * and can be shorter than the agent's declared list when a deployment wired
 * fewer. The per-tool numbers sum to slightly less than `toolCharacters`; the
 * difference is the array's commas, which belong to no tool.
 */
export interface Prefix {
  readonly agent: string;
  readonly model: string;
  readonly systemPromptCharacters: number;
  readonly toolCharacters: number;
  readonly tools: readonly ToolCost[] | null | undefined;
}

/**
 * What one conversation's prompt costs: `GET /v1/conversations/{id}/context`.
 *
 * **The four nulls are the deliverable and not a gap in this console.**
 * `systemPromptTokens`, `toolTokens`, `messageTokens` and `cacheHitRate` are
 * *always* null and are sent as explicit nulls rather than omitted, so that a
 * client rendering the reference layout can find the slot, read nothing in it,
 * and say so. They are typed here as the nullable numbers the record declares
 * rather than as `null`, because this file mirrors the record; the refusal to
 * estimate lives in the renderer, where a reader of the screen can see it.
 *
 * `sent` is exact and is the model's own tokenizer counting **the whole
 * request**; it is null for a conversation no turn of which reached a model
 * call, and is **never zero**. `sentAtTurn` is which turn it was measured on,
 * which is not necessarily the last: a turn that ended before its first model
 * call has no measurement. `turns` against `turnsMeasured` is what tells a
 * reader that `sent` is older than the conversation.
 *
 * `prefix` is null only when neither the caller nor the conversation could name
 * an agent -- a conversation opened and never spoken into.
 */
export interface ContextView {
  readonly sent: number | null | undefined;
  readonly sentAtTurn: number | null | undefined;
  readonly turns: number;
  readonly turnsMeasured: number;
  readonly measuredTurns: readonly MeasuredTurn[] | null | undefined;
  readonly systemPromptTokens: TokenCount | null | undefined;
  readonly toolTokens: TokenCount | null | undefined;
  readonly messageTokens: TokenCount | null | undefined;
  readonly cacheHitRate: number | null | undefined;
  readonly unavailable: readonly Unavailable[] | null | undefined;
  readonly prefix: Prefix | null | undefined;
}

/**
 * A count of tokens, and how the server knows it: `ContextView.TokenCount`.
 *
 * **`basis` is why this is an object and not a number.** The server has three
 * ways of knowing -- a real tokenizer, a bound whose direction of error is
 * known, and a ratio -- and they are not interchangeable. A bare number would
 * let an estimate be rendered as though something had counted it, which is the
 * confusion this whole surface was built to refuse.
 */
export interface TokenCount {
  readonly tokens: number;
  readonly basis: 'MEASURED' | 'BOUND' | 'ESTIMATED';
  readonly how: string;
}

/**
 * One turn the model counted, and what it grew by: `ContextView.Measured`.
 *
 * `since` is carried rather than implied because measured turns need not be
 * consecutive -- a turn that reached no model call records nothing -- so a
 * growth can span several turns, and a reader assuming it spanned one would
 * attribute all of it to the wrong turn.
 */
export interface MeasuredTurn {
  readonly turn: number;
  readonly promptTokens: number;
  readonly grewBy: number | null | undefined;
  readonly since: number | null | undefined;
}

/**
 * One chunk a document search found: `DocumentSearchResponse.Hit`.
 *
 * **`chunkId` is what matched and `paragraphId` is what to cite**, and the
 * difference is the whole reason both are on the wire. A chunk is an artefact
 * of the chunker — moving `chunk-target-tokens` moves every one of these while
 * no document has moved — where a paragraph id is `V18`'s surrogate key, which
 * survives a re-ingest that left the text alone and is reissued when the text
 * changed. So a citation held across an edit either still means these words or
 * means nothing, and never quietly means different words.
 *
 * `paragraphOrdinal` is **position and not identity**: it moves when a paragraph
 * is inserted above it and `paragraphId` does not.
 *
 * `similarity` is 1 for an identical direction and 0 for an unrelated one. It is
 * comparable between two hits of one search and is **not a probability and not a
 * threshold anyone has calibrated** — and under `hybrid` it is not the key the
 * list is sorted on, because the order is the fused one.
 *
 * Every id is a Java `UUID` on the far side and arrives as its string form.
 */
export interface DocumentHit {
  readonly chunkId: string;
  readonly text: string;
  readonly similarity: number;
  readonly paragraphId: string;
  readonly paragraphText: string;
  readonly paragraphOrdinal: number;
  readonly documentId: string;
  readonly sourceName: string;
  readonly title: string;
}

/**
 * What `POST /v1/documents/search` answers.
 *
 * `limit` is **what was used and not what was asked for**, which differ exactly
 * when the caller sent a number above the service's cap; nothing in this console
 * repeats that cap, for `EntryPageView`'s reason.
 *
 * `mode` is echoed lower-cased, and it is the sharper case of echoing the
 * question: a hybrid answer and a vector-only answer to one question are two
 * different claims, and the field exists precisely so somebody can put the two
 * side by side.
 *
 * **`unsearchable` is the field that stops an empty answer being a lie**, at
 * corpus scale where it is worse than in a recall: a whole document stored while
 * the embedding endpoint was down is every word of a paper that answers nothing.
 * Zero means the answer is complete.
 */
export interface DocumentSearchResponse {
  readonly query: string;
  readonly limit: number;
  readonly mode: string;
  readonly hits: readonly DocumentHit[] | null | undefined;
  readonly searchable: number;
  readonly unsearchable: number;
}

/**
 * One key of the live configuration: `GET /v1/config`, and what
 * `PUT /v1/config/{key}` answers with for the key it just wrote.
 *
 * Mirrors `RuntimeConfigController.Setting` field for field, and its javadoc is
 * what a renderer has to obey rather than a paraphrase of it.
 *
 * **`updatedAt` and `updatedBy` are null together, and that is the ordinary
 * state.** Null means no row: nobody has written this key through this screen
 * or through anything else, and `value` is whatever the jar shipped or an
 * operator pinned outside it. A live key nobody has touched is the shape of a
 * server nobody has touched, not a failure to read.
 *
 * **`pinned` is why this endpoint is worth a screen at all.** It says an
 * operator supplied this key outside the jar -- an environment variable,
 * typically -- in which case the *next boot* overwrites whatever gets written
 * here. A write still succeeds; it is the boot after it that does not keep it.
 *
 * `value` is typed nullable because `RuntimeConfigController.setting` falls
 * back to `Environment#getProperty`, which can answer null for a live key with
 * no row and no bound default -- a deployment gap this file has to admit
 * rather than assume away.
 */
export interface Setting {
  readonly key: string;
  readonly value: string | null | undefined;
  readonly updatedAt: string | null | undefined;
  readonly updatedBy: string | null | undefined;
  readonly pinned: boolean;
}

/**
 * What the model would be shown, assembled the way a real turn assembles it.
 *
 * `GET /v1/conversations/{id}/projection`. Computed and never sent: the route's
 * own javadoc carries that rule, and its test proves it against a dispatcher
 * that fails if anything reaches a model.
 *
 * **Two questions, told apart by `turn` on the answer.** Absent, it is what the
 * conversation's NEXT prompt would carry, assembled from the agent's file as it
 * stands right now — the current projection, which the screen must say rather
 * than let it read as history. Present, it is what that turn opened with: the
 * rows that turn could see, under the block it recorded going out with if it
 * recorded one (`systemBlockAsSent`).
 *
 * **Neither answer is byte-identical to a request.** Nothing records an agent's
 * `tools:` per turn, and the tool list decides two things about the history — a
 * fold's seam is worded from `result_list`, and an older tool result is shown in
 * full or as a reference from `result_read` — so both follow the file as it
 * stands at the read. `systemBlockAsSent` is about the block and does not cover
 * that.
 */
export interface ProjectionView {
  readonly agent: string | null | undefined;
  /**
   * The turn this was computed as of, or absent for the conversation's next
   * prompt. Never zero — there is no turn zero — so the absence is the whole
   * of "this is the next prompt".
   */
  readonly turn?: number | null;
  /**
   * Whether the system block at the front is the one that turn was actually
   * sent, rather than the agent's prompt as the file stands now. Only a past
   * turn that recorded its block can be true; every turn written before the
   * server started recording them is false, permanently.
   */
  readonly systemBlockAsSent?: boolean | null;
  readonly messages: readonly ProjectionMessage[] | null | undefined;
}

/** One message of a projection: who it is from, and what it holds. */
export interface ProjectionMessage {
  readonly role: string | null | undefined;
  readonly content: string | null | undefined;
}

/**
 * One arrival in the account's inbox: `inbox.list`'s `items`.
 *
 * Mirrors `InboxItem` field for field. `firing` is null for an item the console
 * cannot trace back to a schedule -- an event-started run has none.
 * `readAt` is null until `inbox.read` marks it, which is the field this screen
 * draws `[data-unread]` from.
 */
export interface InboxItemView {
  readonly id: string;
  readonly handle: string;
  readonly firing: string | null;
  readonly conversation: string;
  readonly ending: string;
  readonly answer: string;
  readonly arrivedAt: string;
  readonly readAt: string | null;
}

/** What `inbox.list` answers with. */
export interface InboxPage {
  readonly items: readonly InboxItemView[];
  readonly unread: number;
}

/** What `inbox.read` answers with. */
export interface InboxMarked {
  readonly marked: number;
  readonly unread: number;
}

/** The bare push `EventChannelHandler` sends every socket of the account. */
export interface InboxChanged {
  readonly kind: 'inbox.changed';
  readonly unread: number;
}

/** Whether a frame off the socket is the inbox's bare push, read tolerantly. */
export function asInboxChanged(frame: unknown): InboxChanged | null {
  if (typeof frame !== 'object' || frame === null) {
    return null;
  }
  const f = frame as { kind?: unknown; unread?: unknown };
  return f.kind === 'inbox.changed' && typeof f.unread === 'number'
    ? { kind: 'inbox.changed', unread: f.unread }
    : null;
}
