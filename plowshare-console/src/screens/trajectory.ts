import { consoleTransport } from '../transport';
import { background } from '../background.ts';
import { isList } from '../../../sdk/typescript/src/binding/values.ts';
import type { ConversationView } from '../repl/wire';
import {
  button,
  describeCount,
  el,
  input,
  labelled,
  moment,
  nothing,
  problemText,
  textOf,
  trouble,
} from './dom';
import type { Screen, Transport } from './screen';
import {
  GLOBAL_TIER,
  type AskedView,
  type ContextView,
  type EntryPageView,
  type EntryView,
  type ProjectionView,
  type TokenCount,
  type Unavailable,
} from './wire';

/**
 * One conversation, read the two ways the server already reads it, and the
 * numbers underneath both.
 *
 * <h2>The tabs are not a UI invention</h2>
 *
 * `EntryStore.pageOfProjection` and `EntryStore.pageOfLog` are two different
 * questions about the same rows, and the distinction between them has been
 * load-bearing in this server since the log existed — it is what a request
 * carries versus what happened. Until this screen the only caller of either was
 * the turn loop, so **every fact on the trajectory side is one the server wrote
 * down and nothing could ask for**: which entries a fold covered and which
 * summary covered them, the `diagnostic` rows the machinery leaves, the
 * `attempt_failed` that is how a stopped run is legible at all, the handles, and
 * the timings.
 *
 * So the two tabs are **two reads and never one list filtered twice**. That is
 * not fastidiousness: `EntryPageView.total` is about the conversation and not
 * the page, and it is *different between the two readings* — a chat's total
 * counts what a model is shown and a trajectory's counts everything. A screen
 * that fetched the log and hid rows to make a chat would report the log's total
 * under the chat's heading, and the gap between the two numbers is exactly the
 * thing worth seeing.
 *
 * <h2>A third tab, and still only two reads</h2>
 *
 * There are three tabs below and `Reading` has three members, but **`log` is
 * not a third read** and must never become one. It draws exactly the rows the
 * `trajectory` reading fetched — the same `EntryPageView`, the same total —
 * with each row's state as its own field instead of a sentence folded into the
 * conversation's narrative. `underlyingReading` is where that is enforced
 * rather than merely intended: `held` is typed so `log` has no slot of its
 * own, and `fetchPage`/`step` key off it and not off `reading` directly. A
 * `log` that called `GET .../log` would be asking the server a question it
 * already had the answer to, and reporting the trajectory's own total under a
 * third name.
 *
 * <h2>What the chat is, said plainly, because it is nearly a lie</h2>
 *
 * The chat is what the model is shown: superseded rows gone, roleless kinds
 * gone, in the order a model reads them. **It is not byte-for-byte what a
 * request contained**, and `ConversationController.chat` says so rather than
 * leaving it to be discovered: `Compaction.whatWasSaidAndWhatCameBack`
 * substitutes a reference line for an older turn's tool result one layer above
 * this read, so a request carries a line where this page carries the result.
 * That is the more useful of the two answers — a person checking what a
 * reference stood for wants the result — and this screen says which one it is
 * showing rather than implying the other.
 *
 * <h2>Every bound is drawn as a bound</h2>
 *
 * Three caps, each with both of its numbers on the wire, and each rendered with
 * both:
 *
 * - The **page** is capped and carries `total`, `offset` and `limit`. Paging
 *   steps by the `limit` the server *used*, which is not necessarily the one
 *   this console asked for; nothing here hardcodes the cap, because a client
 *   that echoed its own ask would step over entries it never saw.
 * - An entry's **text** is cut at one cap, and `length` says how long it really
 *   was. There is no way to ask this surface for the rest, and **the handle is
 *   not an exception for this console**: `result_read` is an agent tool, no
 *   route on this server takes a handle, and a row that offered one as a way to
 *   read the rest would be pointing a person at something that does not exist.
 *   So the row says how much it is not showing, and who can reach the rest.
 * - A call's **arguments** are cut at a second cap, separately, because
 *   `file_edit` sends a whole file as an argument. A row can be uncut while a
 *   call inside it is cut, and the two are drawn apart.
 *
 * The search box and the kind filter act on **the page in hand and not on the
 * conversation**, and say so on the screen. This console cannot filter server
 * side — no endpoint takes a kind or a query — and a filter that silently
 * searched fifty of a thousand entries would answer "nothing matched" about a
 * conversation it had not read.
 *
 * <h2>Rendering is escaping, and here that means `textContent`</h2>
 *
 * A trajectory row holds whatever a tool read off a disk. There is no
 * `innerHTML` in this file and no call to `escape` either: `dom.ts` gives the
 * reason and `render.test.ts` holds it over every file under `src/`. An
 * `escape()` on the way into `textContent` double-encodes — a file that really
 * said `a & b` would reach the page as `a &amp; b` — which is a visible bug in
 * somebody else's text rather than defence in depth.
 */

/**
 * How many entries this console asks for at a time.
 *
 * **An ask and never the cap.** `ConversationController.MOST_ENTRIES_A_PAGE` is
 * the server's, it narrows anything larger, and `EntryPageView.limit` reports
 * what it actually used. This number is deliberately under it: a page a person
 * scrolls is not a page a harness drains, and asking for less means the two
 * numbers agree in the ordinary case while the paging still steps by the
 * server's answer in the case where they do not.
 */
export const PAGE = 50;

/** What an empty reading means: read, and there was nothing in it. */
export const NOTHING_LOGGED =
  'Nothing has been recorded in this conversation under this reading. That is an answer and' +
  ' not a failure — a conversation that was opened and never spoken into holds no entries,' +
  ' and the chat reading of a conversation whose every row was roleless holds none either.';

/**
 * What an empty log means, worded for the append-only reading and not reused.
 *
 * The log's own sentence rather than {@link NOTHING_LOGGED}: "recorded ...
 * under this reading" is true of the chat and the trajectory, which really are
 * two different readings of the conversation, and would be a small lie here —
 * the log is the trajectory's own page, and this is the one case where the
 * page in hand really is the whole of it and it is still empty.
 */
export const LOG_EMPTY =
  'Nothing has been appended to this conversation yet. That is an answer and not a failure to' +
  ' read.';

/**
 * What it means when this screen is driven from outside and nothing has been
 * picked yet.
 *
 * Its own sentence rather than {@link NO_CONVERSATIONS}: that one says the
 * server holds no conversation on this tier, which is a fact about the archive,
 * and this one says a person has not named one yet, which is a fact about the
 * screen. A screen with no chooser of its own cannot say the first and must not
 * borrow it -- and it must say something, because a section left blank under a
 * tab note reads as a reading that came back empty.
 */
export const NOT_PICKED =
  'No conversation is picked, so there is nothing to read. Choose one in the sidebar and this' +
  ' tab reads that one — it follows the pick rather than keeping a conversation of its own.';

/** What it means when the server has no conversation to read. */
export const NO_CONVERSATIONS =
  'No conversation has been opened on this tier, so there is nothing to read the chat or the' +
  ' trajectory of. The repl opens one; this screen only ever reads.';

/**
 * What each kind this server writes today means, in a person's words.
 *
 * **Not a switch, and deliberately not exhaustive.** `EntryKind.wireName` is the
 * string the `kind` column holds and it is what travels, so a client reading
 * this and a person reading the table are looking at the same word — which is
 * the whole point of a trajectory. A kind added on the server must reach this
 * console without a change here, so an unknown one renders as itself.
 */
const KINDS: Readonly<Record<string, string>> = Object.freeze({
  utterance: 'utterance — what a person said',
  answer: 'answer — what the model said, and what it asked to call',
  tool_result:
    'tool_result — what one tool returned, against the call it answers',
  summary: 'summary — what a fold put in place of the turns it covers',
  attempt_failed:
    'attempt_failed — a model call refused or abandoned. Recorded because it is a fact' +
    ' about the run, and never shown to a model, which would be inventing history',
  runtime_note:
    'runtime_note — the harness nudging a run that had called the same tool the same way' +
    ' several times over. Already delivered in-run, so never shown again',
  plan: 'plan — an externalised plan. The kind is reserved; nothing writes one yet',
  diagnostic:
    'diagnostic — the harness talking about the conversation rather than in it: a fold' +
    ' firing and its span, a resumption, a learner pass',
  refusal:
    "refusal — a probable refusal from the agent's own model that its fallback was" +
    ' dispatched to answer. Recorded with the model that refused, and never shown to a' +
    " model: the fallback's answer is the conversation",
});

/**
 * The kind in words if this build knows the spelling, and as the spelling if not.
 *
 * `Object.hasOwn` and not a plain lookup: the value is a string off the wire,
 * and `KINDS['constructor']` on a plain object literal answers a function.
 */
export function describeKind(kind: unknown): string {
  if (typeof kind !== 'string' || kind === '') {
    return 'a kind the server did not name';
  }
  return Object.hasOwn(KINDS, kind) ? (KINDS[kind] as string) : kind;
}

/**
 * What a log row says, in full.
 *
 * **Never empty**, which is the property the column depends on: a blank cell in
 * a table reads as a rendering fault, and three of the four rows on a short
 * conversation are legitimately textless. So each way of holding no text says
 * which way it is. An ejected payload is not a tool that returned nothing --
 * the bytes were dropped on purpose and there is nothing left to show -- and an
 * `answer` carrying only tool calls is not an answer that said nothing either.
 */
export function logBody(row: EntryView): string {
  if (typeof row.ejectedAt === 'string' && row.ejectedAt !== '') {
    return `payload ejected ${moment(row.ejectedAt)} — the bytes are gone from the row.`;
  }
  const text = textOf(row.excerpt);
  return text === '' ? 'no text recorded on this entry' : text;
}

/**
 * The tools a record asked for, whichever way it is spelled on the row.
 *
 * <b>A record that both spoke and called tools used to show only the text.</b>
 * `logBody` named the calls in its no-text branch and nowhere else, so an
 * `answer` that said something and then called three tools rendered as its
 * sentence alone — and the log, which is meant to be the whole of what the
 * archive holds, showed strictly less than the narrative reading beside it.
 * The calls are a property of the record, not a fallback for a record that has
 * nothing else.
 */
export function logCalls(row: EntryView): string {
  const calls = isList(row.toolCalls) ? row.toolCalls : [];
  const named = calls
    .map((asked) => textOf(asked.name))
    .filter((name) => name !== '');
  return named.length === 0 ? '' : `calls ${named.join(', ')}`;
}

/**
 * Which call a result answers, named when this page can name it.
 *
 * A `tool_result` carries the id it answers and never the tool: the name lives
 * on the `answer` that asked. Paging can cut between the two, so a miss says so
 * rather than leaving an unexplained id — the same rule `entryNode` follows,
 * and the log had none of it at all.
 */
export function logAnswers(
  row: EntryView,
  named: ReadonlyMap<string, string>,
): string {
  const answers = textOf(row.toolCallId);
  if (answers === '') {
    return '';
  }
  const tool = named.get(answers);
  return tool === undefined
    ? `answers a call not on this page`
    : `answers ${tool}`;
}

/**
 * Which model spoke for the assistant on this row, or `''` for a row that
 * carries no model — an utterance, a tool result, an answer written before the
 * provenance was recorded.
 *
 * **The fallback is the case this exists to make legible.** A rerouted refusal
 * leaves the fallback's answer as the assistant's reply, and without this a
 * reader cannot tell it apart from a reply the agent's own model gave. So a
 * fallback is named; a primary answer is named only when the caller wants the
 * long form, since "the agent answered its own turn" is the ordinary case and a
 * log column scanned for the exception should not repeat it on every row.
 */
export function logProvenance(row: EntryView, includePrimary = false): string {
  const dispatch = textOf(row.dispatch);
  if (dispatch === '') {
    return '';
  }
  const model = textOf(row.wireModel);
  const named = model === '' ? '' : ` (${model})`;
  if (dispatch === 'fallback') {
    return textOf(row.completion) === 'refused'
      ? `fallback refused${named}`
      : `fallback answered${named}`;
  }
  return includePrimary ? `agent's own model${named}` : '';
}

/** The first line with anything on it, for a column that holds one line. */
export function firstLineOf(text: string): string {
  for (const line of text.split('\n')) {
    if (line.trim() !== '') {
      return line.trim();
    }
  }
  return text.trim();
}

/**
 * How big the row is, compactly, because this column is scanned and not read.
 *
 * {@link describeCount}'s sentence -- "165 characters" -- is right in a
 * paragraph and wrong in a column: at four figures it is the widest thing on
 * the row, and it repeats the same word down the whole page. The unit moves to
 * the column heading. Absence still refuses to be a zero, and a cut row says it
 * is cut here rather than reporting the excerpt's length as the entry's.
 */
export function logSize(row: EntryView): string {
  const length = row.length;
  if (typeof length !== 'number' || !Number.isFinite(length)) {
    return 'not reported';
  }
  const shown =
    length < 1000 ? String(length) : `${(length / 1000).toFixed(1)}k`;
  return row.cut === true ? `${shown}+` : shown;
}

/**
 * The one column somebody auditing the archive is scanning for.
 *
 * A fold and an ejection are the two states a record can be in that are not
 * visible in what it says, and the whole point of a fixed column is that there
 * is exactly one place to look for either. The previous layout appended them as
 * extra fields after a variable number of others, so their position moved from
 * row to row -- which is the one thing a scanned column must not do.
 */
export function logState(row: EntryView): string {
  const marks: string[] = [];
  if (typeof row.supersededBy === 'number') {
    marks.push(`folded→${row.supersededBy}`);
  }
  if (typeof row.ejectedAt === 'string' && row.ejectedAt !== '') {
    marks.push('ejected');
  }
  // A WORD ON EVERY ROW, because a column that is blank on a healthy
  // conversation is a column nobody can see. This returned '' for any record
  // nothing had happened to, which is every record of a conversation that has
  // not folded -- so the one column the tab exists for read as empty space,
  // and an archive where nothing has been covered or emptied looked identical
  // to one where the state was simply not being rendered.
  return marks.length === 0 ? 'stands' : marks.join(' ');
}

/** The fields the columns compress, spelled out under a row that was opened. */
export function logDetail(row: EntryView): string {
  const parts = [
    `recorded ${moment(row.recordedAt)}`,
    describeCount(row.length, 'character', 'characters'),
  ];
  if (row.cut === true) {
    parts.push('cut — this console was not sent the rest');
  }
  if (typeof row.supersededBy === 'number') {
    parts.push(`superseded by entry ${row.supersededBy}`);
  }
  if (typeof row.ejectedAt === 'string' && row.ejectedAt !== '') {
    parts.push(`payload ejected ${moment(row.ejectedAt)}`);
  }
  if (typeof row.toolCallId === 'string' && row.toolCallId !== '') {
    parts.push(`answers call ${row.toolCallId}`);
  }
  if (typeof row.handle === 'string' && row.handle !== '') {
    parts.push(`handle ${row.handle}`);
  }
  // The whole of the provenance the row carries, primary included: the detail
  // is read, not scanned, so here the ordinary case is worth stating rather
  // than leaving a reader to infer it from the absence of a fallback note.
  const who = logProvenance(row, true);
  if (who !== '') {
    parts.push(who);
  }
  return parts.join('  ·  ');
}

/**
 * The column headings, once, above the records.
 *
 * These are the labels every row used to carry its own copy of. A log is read
 * by scanning down a column, so a name belongs at the top of its column and not
 * beside all eighteen of its values.
 */
export function recordHead(): HTMLElement {
  const node = el('div', 'record record-head');
  node.append(
    el('span', 'open-mark', ''),
    el('span', 'at', '#'),
    el('span', 'turn', 'turn'),
    el('span', 'kind', 'kind'),
    el('span', 'content', 'what it says'),
    el('span', 'size', 'chars'),
    el('span', 'state', 'state'),
  );
  return node;
}

/**
 * How much of a reading this page is, said with both numbers.
 *
 * `EntryPageView` is explicit that a caller cannot tell a page that ended from
 * one that was cut without being told both, and this is the sentence that
 * refuses to pretend. The tail is only added when there really is more, so
 * "showing 3 of 3" reads as complete rather than as a page that gave up.
 */
export function showingOf(shown: number, total: number): string {
  const head =
    `showing ${shown} of ${total} ${total === 1 ? 'entry' : 'entries'}` +
    ' in this reading';
  return shown >= total
    ? `${head}. This is the whole of it.`
    : `${head} — ${total - shown} more, and this page is not the whole of it.`;
}

/**
 * A number the server sent, or the fact that it did not send one.
 *
 * **`textOf` is deliberately not this, and the difference cost a bug.** It
 * answers `''` for anything that is not a string, which is right for a text
 * field arriving as null and silently wrong for an ordinal: `#${textOf(1)}`
 * renders `#` with nothing after it, and nothing fails. Every number on these
 * rows goes through here instead, and an absence says so rather than leaving a
 * gap that reads as a rendering fault.
 */
/**
 * What a basis means, in a person's words.
 *
 * Spelled here rather than shown raw because MEASURED and ESTIMATED are wire
 * constants, and a spelling the server adds later has to reach a reader without
 * a change to this file -- the same rule `describeKind` follows for kinds.
 */
export function basisWord(basis: unknown): string {
  if (basis === 'MEASURED') {
    return 'measured';
  }
  if (basis === 'ESTIMATED') {
    return 'estimated';
  }
  if (basis === 'BOUND') {
    return 'an upper bound';
  }
  return 'on a basis this console does not know';
}

export function figure(value: unknown): string {
  return typeof value === 'number' && Number.isFinite(value)
    ? String(value)
    : 'not reported';
}

/**
 * A turn's steps, and how much of the turn anybody actually timed.
 *
 * **An unmeasured step is not a step that took no time**, which is the rule
 * `duration` states on this same page — "absence is never a zero" — and which
 * the turn summary broke by coercing an absent `tookMillis` to `0` and adding
 * it. Two things went wrong at once. A turn nobody timed summed to `0` and the
 * summary then hid the zero, so it read exactly like a turn measured at 0 ms.
 * And a **mixed** turn — three steps timed, two not — printed the sum of the
 * three as though it were the turn's total, which is a smaller number than the
 * truth presented as the truth.
 *
 * So there are three answers here and never one: everything timed and this is
 * the total; some of it timed and this is a partial that says how partial;
 * none of it timed and there is no total to give.
 *
 * @param steps each step's `tookMillis` as it arrived, absences included —
 *     the absences are half the question.
 */
export function turnTook(
  steps: readonly (number | null | undefined)[],
): string {
  const count = steps.length === 1 ? '1 step' : `${steps.length} steps`;
  const timed = steps.filter(
    (each): each is number => typeof each === 'number' && Number.isFinite(each),
  );
  if (timed.length === 0) {
    return `${count} · nothing here was timed`;
  }
  const took = timed.reduce((run, each) => run + each, 0);
  return timed.length === steps.length
    ? `${count} · ${took} ms`
    : `${count} · ${took} ms over the ${timed.length} of them that were timed, and this is` +
        " not the turn's total";
}

/** Which of the three answers {@link turnTook} gave, for the rule that draws it. */
export function turnTotal(
  steps: readonly (number | null | undefined)[],
): 'whole' | 'partial' | 'none' {
  const timed = steps.filter(
    (each): each is number => typeof each === 'number' && Number.isFinite(each),
  );
  if (timed.length === 0) {
    return 'none';
  }
  return timed.length === steps.length ? 'whole' : 'partial';
}

/** What a turn's summary line shows when there is no utterance text to show. */
export const TURN_NO_UTTERANCE =
  'no utterance on this page — a continued run, or one the page begins after';

/** The utterance row is on this page and its payload is not. */
export const TURN_UTTERANCE_EJECTED =
  'the utterance is on this page and its text is gone — the payload was ejected from the row';

/** The utterance row is here, nothing ejected it, and it carries no text. */
export const TURN_UTTERANCE_EMPTY =
  'the utterance on this page carries no text, and nothing ejected it';

/**
 * The line a person recognises a turn by, or the exact reason there is not one.
 *
 * **Three states and not two.** A turn with no utterance row on the page and a
 * turn whose utterance row is here with its payload ejected are different
 * facts, and they collapsed into one branch whose sentence asserted the first:
 * an ejected conversation had every turn labelled "a continued run, or one the
 * page begins after", which is a positive false statement about each of them.
 * `entryNode` and `.record[data-ejected]` already draw this distinction for the
 * other two tabs, and the three readings must not disagree about it.
 *
 * @returns the text to draw, and the state it is a statement about — `null`
 *     when the text is the utterance itself and not a statement at all.
 */
export function turnSaid(spoken: EntryView | undefined): {
  readonly text: string;
  readonly state: 'absent' | 'ejected' | 'empty' | null;
} {
  if (spoken === undefined) {
    return { text: TURN_NO_UTTERANCE, state: 'absent' };
  }
  const said = textOf(spoken.excerpt);
  if (said !== '') {
    return { text: said, state: null };
  }
  const gone = spoken.ejectedAt;
  return typeof gone === 'string' && gone !== ''
    ? { text: TURN_UTTERANCE_EJECTED, state: 'ejected' }
    : { text: TURN_UTTERANCE_EMPTY, state: 'empty' };
}

/**
 * The three tabs. `log` is not a third read: see the header above.
 *
 * Named for the tabs and not for the reads on purpose — `Reading` is one name
 * wider than the two things it can mean over the wire, and {@link FetchedReading}
 * is the narrower type that keeps `log` out of `held`.
 */
/**
 * What the projection is, said where it is shown.
 *
 * The one thing a reader would otherwise get wrong: this is assembled now, and
 * a turn from last week may have been sent a different system block.
 */
export const PROJECTION_NOTE =
  "This is what this conversation's NEXT prompt would carry, assembled now the way a real" +
  " turn assembles it. It is not a record of what an older turn was sent: an agent's prompt" +
  ' file can change between turns, and a turn that ran before this server started recording' +
  ' its system block has no copy of the one it went out with. Computing it makes no model' +
  ' call.';

/**
 * What a turn's projection says when that turn recorded the block it was sent.
 *
 * The claim the whole recording exists to let this page make: the rows are the
 * ones that turn could see AND the block above them is the one it went out with,
 * whatever the agent's file says today.
 *
 * The last clause is the part that keeps the sentence honest, and it is one
 * clause on purpose. Nothing records an agent's `tools:` per turn, and two
 * things in this list follow that list as it stands NOW: a fold's seam is worded
 * from `result_list`, and an older tool result is shown in full or as a
 * reference from `result_read`. So "what this turn was shown" is exact about
 * which rows and about the block, and not about the wording of those two — a
 * page that claimed otherwise would be making the confident wrong assertion this
 * whole panel exists to stop.
 */
export const TURN_BLOCK_AS_SENT =
  'This is what this turn was shown. The history is the one this turn could see, and the' +
  ' system block above it is the one it actually went out with — recorded when the turn ran,' +
  " so editing the agent's prompt afterwards does not change it. The agent's tool list is" +
  " not recorded: if it has changed since, a fold's seam and how older tool results are" +
  " shown follow today's list. Computing this makes no model call.";

/**
 * What a turn's projection says when that turn recorded no block.
 *
 * Its own sentence and deliberately not {@link PROJECTION_NOTE}, which is about
 * a prompt nobody has been sent yet and would be a wrong reading of this one.
 * Here the history IS a record — it is the rows this turn could see, exactly —
 * and it is only the block at the front that is today's file. Reusing the
 * whole-conversation note would throw that away and tell a reader the whole
 * answer is provisional, which is a different and larger claim than the true
 * one.
 */
export const TURN_BLOCK_NOT_RECORDED =
  'The history here is the one this turn could see. The system block above it is NOT: it is' +
  " the agent's prompt as the file stands now, because this turn ran before the server" +
  ' recorded the block each turn went out with, and there is no way to recover what it was.' +
  ' If the file has been edited since, that block is not what this turn was sent.';

/**
 * Which of the three sentences the panel draws, from what the server asserted.
 *
 * The decision is read off the response and never inferred from the messages:
 * `turn` says which question was answered and `systemBlockAsSent` says what the
 * block at the front is, and both are the server's to state. A console that
 * guessed either would be a second place for the answer to be decided, able to
 * disagree with the one that did the work.
 */
export function projectionNote(seen: ProjectionView | null): string {
  if (typeof seen?.turn !== 'number') {
    return PROJECTION_NOTE;
  }
  return seen.systemBlockAsSent === true
    ? TURN_BLOCK_AS_SENT
    : TURN_BLOCK_NOT_RECORDED;
}

/**
 * What an empty projection means, in its own words.
 *
 * Its own sentence and not {@link NOTHING_LOGGED}: that one is about a page of
 * entries, and this is about a prompt. A projection that came back with no
 * messages is a read that succeeded — the route answered, and what it answered
 * is that this conversation's next prompt would carry nothing. Rendering
 * nothing at all for it, which is what this did, is the one thing an empty
 * answer is not allowed to be: indistinguishable from a screen that failed to
 * draw.
 */
export const PROJECTION_EMPTY =
  "What the model would be shown is empty: this conversation's next prompt would carry no" +
  ' messages at all. That is an answer and not a failed read — the route replied, and this' +
  ' is what it replied.';

/**
 * Which agent a projection was computed against, and why the page says it.
 *
 * `ProjectionView.agent` exists for one reason, and it is that field's own
 * javadoc: a reader comparing two projections of the same conversation needs to
 * know whether they were computed against the same agent. On a deployment with
 * more than one agent a page that drew the messages and dropped the name leaves
 * "whose prompt is this" unanswerable from the screen.
 */
export function agentLine(agent: string): string {
  return (
    `computed against ${agent}. Two readings of this conversation are only comparable` +
    ' when they name the same agent, which is why this line is here.'
  );
}

/** What it means when the server named no agent for a projection. */
export const NO_AGENT_NAMED =
  'The server named no agent for this projection, so there is nothing here to compare a second' +
  ' reading against. A conversation names an agent once one has answered in it.';

export const READINGS = ['chat', 'trajectory', 'log'] as const;

export type Reading = (typeof READINGS)[number];

/**
 * The two readings that ever cross the wire.
 *
 * `held`, `fetchPage` and `step` are keyed by this and not by `Reading`
 * directly: the log has no request and no held state of its own, and typing it
 * out of this alias is what keeps a future `held['log']` from ever compiling
 * rather than merely being a convention someone has to remember.
 */
type FetchedReading = 'chat' | 'trajectory';

/**
 * Which held page a reading draws from, on the wire.
 *
 * Opening the log tab before the trajectory tab has to read the trajectory
 * endpoint once — there is nowhere else the rows could come from — and this is
 * what makes that read land in the trajectory's own held slot rather than a
 * slot of the log's own, so opening the other tab afterwards finds it already
 * there instead of asking again.
 */
function underlyingReading(name: Reading): FetchedReading {
  return name === 'chat' ? 'chat' : 'trajectory';
}

/**
 * What the log tab is, and what it is not.
 *
 * It is the same rows the `trajectory` reading fetched, rendered with the
 * record's state first rather than the conversation's narrative. **It is not a
 * third read**, and must not become one: the two-reads rule at the top of this
 * file is about chat and trajectory having different totals, and a reading that
 * re-fetched the same page would report the same numbers twice.
 */
export const LOG_NOTE =
  'The records themselves, in the order they were appended. Superseded is not deletion — a' +
  ' fold hides the reference and keeps the address, so the entry that covered a row is' +
  ' named beside it. Ejected is the one state where the content is really gone.';

/** What each tab is, on the screen, so the split is not left to be inferred. */
const TAB_NOTES: Readonly<Record<Reading, string>> = Object.freeze({
  chat:
    'What the model is shown: superseded rows gone, roleless kinds gone, in the order a' +
    ' model reads them. Not byte-for-byte a request — a request carries a reference line' +
    ' where this page carries the tool result it stands for.',
  trajectory:
    'Everything that happened, unfiltered, in conversation order: the superseded rows the' +
    ' fold hid, the diagnostics, the failed attempts, the handles and the timings. Nothing' +
    ' here is derived; every row was written down when it happened.',
  log: LOG_NOTE,
});

export interface TrajectoryOptions {
  readonly root: HTMLElement;
  readonly transport?: Transport;
  /**
   * The tier to list conversations from, or null for global.
   *
   * Read only when this screen does its own choosing: it scopes the listing
   * that fills the chooser, and there is no other listing here.
   */
  readonly project?: string | null;
  /**
   * Whether this screen draws its own conversation chooser. `true` when absent.
   *
   * `false` is for a caller that has one already -- `chat.ts`, whose sidebar
   * is the pick for the whole view -- and it settles one question, not two:
   * a screen that does not choose is *told*, through {@link Trajectory.show},
   * and therefore does not choose a default either. That second half is the
   * one that matters. {@link Screen.load} picks the first row of the listing
   * when nothing is showing, which is right for a screen a person came to on
   * its own and is a lie inside a composed view: it renders one
   * conversation's entries under a sidebar highlighting another, with nothing
   * on the page naming either. Told-not-choosing renders {@link NOT_PICKED}
   * instead, which says which of the two is true.
   */
  readonly ownChooser?: boolean;
  /**
   * Whether this screen draws its own reading tabs. `true` when absent.
   *
   * `false` for a caller that draws them itself and drives {@link
   * Trajectory.choose} -- see that method for why `chat.ts` must.
   */
  readonly ownTabs?: boolean;
}

/**
 * A trajectory screen, and the one thing a composing view needs beyond
 * {@link Screen}.
 *
 * Widened rather than made a separate handle: `shell.ts` holds every view as a
 * `Screen` and must go on being able to, and an interface that extends it stays
 * assignable there while giving `chat.ts` the verb it needs. The alternative
 * -- a `conversation` field on the options -- was rejected because the options
 * are read once at construction and a pick happens many times afterwards.
 */
export interface Trajectory extends Screen {
  /**
   * Read that conversation instead of whatever is being read.
   *
   * Drops both held pages and the measurement with them: they are that
   * conversation's and a new one has neither. Never rejects -- a failed read
   * is drawn where the rows would be, like every other read on this screen.
   */
  show(conversationId: string): Promise<void>;
  /**
   * Show that reading instead of the one showing.
   *
   * Exposed for a caller that draws the tabs itself. `chat.ts` does: composed
   * there, the REPL *is* the chat reading -- the conversation a person is
   * actually in, with a composer -- and this screen's own chat reading would
   * be a second, read-only rendering of the same thing beside it. Two panes
   * both called chat is the confusion, and one tab strip over both is the fix.
   */
  choose(reading: Reading): Promise<void>;
}

/** One reading's state: the page in hand, and where in the log it starts. */
interface Held {
  page: EntryPageView | null;
  offset: number;
}

export function createTrajectory(options: TrajectoryOptions): Trajectory {
  const transport: Transport = options.transport ?? consoleTransport;
  const ownChooser = options.ownChooser ?? true;
  const ownTabs = options.ownTabs ?? true;
  let project: string | null = options.project ?? null;
  let conversation: string | null = null;
  let reading: Reading = 'chat';
  let held: Record<FetchedReading, Held> = {
    chat: emptyHeld(),
    trajectory: emptyHeld(),
  };
  let measured: ContextView | null = null;
  /** The kinds the kind filter is currently offering, so it is not rebuilt under a choice. */
  let offering: string[] = [];
  /**
   * Which pick every in-flight read belongs to.
   *
   * Bumped once per {@link show}, and every read compares it after its await
   * and before it touches the DOM. Two rapid picks are two reads racing: the
   * later pick can be answered first, and without this the slower answer for
   * conversation A lands last and writes A's projection, A's rows and A's
   * numbers under B's name, with nothing on the page saying so. This is a
   * console for auditing what a run did; a page that quietly mixes two
   * conversations is the worst thing it could do.
   */
  let picked = 0;

  function emptyHeld(): Held {
    return { page: null, offset: 0 };
  }

  const shell = el('section', 'screen trajectory');
  const head = el('header', 'screen-head');
  const tier = input('project', `blank for ${GLOBAL_TIER}`);
  const chosen = document.createElement('select');
  const reload = button('reload', 'reload');
  const body = el('div', 'screen-body');
  const tabs = el('div', 'tabs');
  const filters = el('div', 'filters');
  const search = input('search', 'find in this page');
  const kinds = document.createElement('select');
  const window_ = el('div', 'window');
  const back = button('page-back', 'previous page');
  const on = button('page-on', 'next page');
  const strip = el('div', 'strip');
  const shownToModel = el('div', 'projection');
  const listed = el('section', 'entries');
  const economics = el('footer', 'economics');
  const tabNote = el('p', 'tab-note');

  chosen.dataset['conversations'] = '';
  kinds.dataset['kinds'] = '';
  body.dataset['trajectory'] = '';
  economics.dataset['context'] = '';
  back.dataset['page'] = 'back';
  on.dataset['page'] = 'next';
  tier.value = project ?? '';

  const tabButtons = new Map<Reading, HTMLButtonElement>();
  for (const name of READINGS) {
    const control = button('tab', name);
    control.dataset['tab'] = name;
    control.addEventListener('click', () => {
      background(choose(name));
    });
    tabButtons.set(name, control);
    tabs.append(control);
  }

  filters.append(
    labelled('search', search),
    labelled('kind', kinds),
    el(
      'p',
      'filter-note',
      'The search and the kind filter act on this page and not on the conversation. No' +
        ' endpoint here takes a query or a kind, so a filter that reached past the page' +
        ' would be this console guessing about entries it has not read.',
    ),
  );
  window_.append(el('p', 'window-note', ''), back, on);
  // The chooser is left out of the tree rather than hidden when this screen
  // is not the thing that chooses: an element never appended cannot be found
  // by a `querySelector` for `[data-conversations]`, and in the composed view
  // that attribute has to name exactly one control. The tier goes with it,
  // because the only thing it ever did was scope the listing that fills the
  // chooser -- drawn beside a chooser that is not there, it would be a
  // control a person can change with nothing behind it.
  const title = el('h2', 'screen-title', 'trajectory');
  head.append(title);
  if (ownChooser) {
    head.append(labelled('tier', tier), labelled('conversation', chosen));
  }
  head.append(reload);
  // The tab strip goes in only when this screen is the thing that switches
  // readings. Composed in `chat.ts` it is not: the REPL is the chat reading
  // there, so one strip sits above both panes and drives `choose` from
  // outside. Left out of the tree rather than hidden, for the reason the
  // chooser above is -- exactly one element may carry `[data-tab]` in the
  // composed view, or a scoped query resolves by DOM order.
  if (ownTabs) {
    body.append(tabs);
  }
  // The strip first, because it is what this reading is for: the shape of
  // where a conversation spent itself, before the rows that say it in words.
  // The note and the page filters go under it in a disclosure -- they are
  // worth having and they are not worth the top of the screen. The filter
  // note in particular is longer than the feature it qualifies.
  const aside = document.createElement('details');
  aside.className = 'about';
  const asideWhat = document.createElement('summary');
  asideWhat.textContent = 'about this reading, and filtering the page';
  aside.append(asideWhat, tabNote, filters);
  body.append(strip, shownToModel, window_, listed, aside, economics);
  shell.append(head, body);
  options.root.replaceChildren(shell);

  function query(): string {
    return project === null ? '' : `?project=${encodeURIComponent(project)}`;
  }

  // --- one row -------------------------------------------------------------

  /**
   * One call the model asked for, bounded the way the entry's own text is.
   *
   * The name is model-supplied text and is not a name from any registry: a
   * call to a tool that does not exist is still recorded. It goes in as
   * `textContent` like everything else here, which is why nothing has to
   * flatten it first.
   */
  function call(asked: AskedView): HTMLElement {
    const node = el('div', 'call');
    node.dataset['call'] = textOf(asked.id);
    node.dataset['cut'] = String(asked.cut === true);
    node.append(
      el('div', 'call-head', `${textOf(asked.name)}  id ${textOf(asked.id)}`),
    );
    node.append(el('pre', 'body', textOf(asked.arguments)));
    if (asked.cut === true) {
      node.append(
        el(
          'p',
          'bound',
          `arguments cut off at ${textOf(asked.arguments).length} of ` +
            `${describeCount(asked.length, 'character', 'characters')}. A whole file can` +
            ' arrive as one argument, which is why this cap is its own.',
        ),
      );
    }
    return node;
  }

  /** How long the operation took, or the fact that this kind is not one. */
  function duration(took: unknown): string {
    if (typeof took !== 'number' || !Number.isFinite(took)) {
      // Absence is never a zero: a kind that records no operation is not
      // an operation that took no time. And it is never the gap to the
      // row before, which holds everything that happened in between.
      return 'no duration — this kind records no operation, or nobody measured it';
    }
    return `${took} ms`;
  }

  /**
   * Which model spoke for the assistant on this row, in a person's words.
   *
   * **A fallback is named as one**, because that is the fact the field was
   * added for: a reader seeing an assistant reply after a refusal has to be
   * able to tell it came from the fallback and not from the model that
   * declined. The wire model is shown when the server sent one — it may be
   * null on a row written before it was recorded — and the completion is
   * named only when it is not the ordinary `answered`, so a plain primary
   * answer reads as one short line rather than three restatements of "fine".
   */
  function provenanceOf(entry: EntryView): string {
    const model = textOf(entry.wireModel);
    const named = model === '' ? '' : ` (${model})`;
    const outcome = textOf(entry.completion);
    const note =
      outcome === '' || outcome === 'answered'
        ? ''
        : `  ·  ${
            outcome === 'refused'
              ? 'a refusal'
              : outcome === 'cut_off'
                ? 'cut off at the token limit'
                : outcome === 'called_tools'
                  ? 'asked for tools'
                  : outcome
          }`;
    return entry.dispatch === 'fallback'
      ? `answered by the fallback${named}${note}`
      : `answered by the agent's own model${named}${note}`;
  }

  /**
   * One row, and the pairing that gives a result the name of what it answered.
   *
   * @param named the tool each call id on this page asked for. **Built from
   *     the page and not from the row**, because a `tool_result` carries the
   *     id it answers and never the tool: the name lives on the `answer` that
   *     asked. Paging can cut between the two, so a miss is said out loud
   *     rather than left as an unexplained id.
   */
  function entryNode(
    entry: EntryView,
    named: ReadonlyMap<string, string>,
  ): HTMLElement {
    const node = el('article', 'entry-row');
    node.dataset['entry'] = String(entry.ordinal);
    node.dataset['kind'] = textOf(entry.kind);
    node.dataset['cut'] = String(entry.cut === true);

    node.append(
      el(
        'div',
        'entry-head',
        `#${figure(entry.ordinal)}  turn ${figure(entry.turnOrdinal)}  ` +
          describeKind(entry.kind),
      ),
    );
    node.append(
      el(
        'div',
        'meta',
        `recorded ${moment(entry.recordedAt)}  ·  ${duration(entry.tookMillis)}`,
      ),
    );

    // Which target answered, on the answers and refusals that carry it.
    // Said out loud rather than left to a colour, because a rerouted
    // refusal and the fallback answer that replaced it are the whole point
    // of the field: a reader has to be able to see that the assistant's
    // reply came from a different model than the one that declined. An
    // utterance and a tool result carry none of this and get no line.
    const dispatch = textOf(entry.dispatch);
    if (dispatch !== '') {
      node.dataset['dispatch'] = dispatch;
      node.append(el('div', 'provenance', provenanceOf(entry)));
    }

    const folded = entry.supersededBy;
    if (typeof folded === 'number') {
      node.dataset['superseded'] = String(folded);
      node.append(
        el(
          'p',
          'superseded',
          `superseded — a fold covered this, and the summary at #${folded} stands in its` +
            ' place. It is here because this is the log; the chat reading does not carry' +
            ' it.',
        ),
      );
    }

    const gone = entry.ejectedAt;
    if (typeof gone === 'string' && gone !== '') {
      // An ejected payload is not a tool that returned nothing, and the
      // difference is the whole reason this field exists. `cut` is false
      // for one of these: the page did not cut it and there is nothing
      // left to ask for.
      node.dataset['ejected'] = gone;
      node.append(
        el(
          'p',
          'ejected',
          `payload ejected ${moment(gone)} — it was ` +
            `${describeCount(entry.length, 'character', 'characters')} and is gone from` +
            ' the row. This is not a blank result.',
        ),
      );
    } else {
      node.append(el('pre', 'body', textOf(entry.excerpt)));
      if (entry.cut === true) {
        node.append(
          el(
            'p',
            'bound',
            `cut off at ${textOf(entry.excerpt).length} of ` +
              `${describeCount(entry.length, 'character', 'characters')}.` +
              (typeof entry.handle === 'string' && entry.handle !== ''
                ? // Exact, because the near-miss is worse than silence:
                  // `result_read` is an agent tool and no HTTP route on
                  // this server redeems a handle, so the rest of this is
                  // reachable by a run and not by a reader.
                  ' The whole of it is redeemable at the handle below by an agent' +
                  ' calling result_read — not by this console, which has no endpoint' +
                  ' that takes a handle.'
                : ' There is no way to ask this surface for the rest.'),
          ),
        );
      }
    }

    const answers = entry.toolCallId;
    if (typeof answers === 'string' && answers !== '') {
      node.dataset['answers'] = answers;
      const tool = named.get(answers);
      if (tool === undefined) {
        node.append(
          el(
            'div',
            'answers',
            `answers call ${answers} — the call it answers is not on this page, so` +
              ' nothing here can name the tool. It is on a page this one does not' +
              ' cover.',
          ),
        );
      } else {
        node.dataset['answersTool'] = tool;
        node.append(el('div', 'answers', `answers call ${answers} — ${tool}`));
      }
    }
    const handle = entry.handle;
    if (typeof handle === 'string' && handle !== '') {
      node.append(
        el(
          'div',
          'handle',
          `handle ${handle} — the address an agent's result_read redeems this result at`,
        ),
      );
    }
    const calls = isList(entry.toolCalls) ? entry.toolCalls : [];
    for (const asked of calls) {
      node.append(call(asked));
    }
    return node;
  }

  /**
   * The entries of a page, gathered into the turns they belong to.
   *
   * `turnOrdinal` is the join and it is the server's, not this screen's: a
   * turn is what a person did and everything the run did answering it, which
   * is the unit somebody auditing a conversation actually reads. A flat list
   * of entries is the same facts with that grouping thrown away.
   */
  /**
   * What the model is shown, once, because it is one thing.
   *
   * <h2>Why this is not repeated inside every turn</h2>
   *
   * The system block is identical for every turn of a conversation -- it is
   * the agent's, not the turn's -- so a copy under each one would be noise.
   * And the route answers **what this conversation's next prompt would be**,
   * computed now from the definition as it stands now: an agent's prompt file
   * can change between turns, so pinning this to turn 3 would assert
   * something this server cannot back. Once, at the top, labelled as the
   * current projection.
   *
   * Shut by default. It is long by nature and a person opening a trajectory
   * is looking for what a run did, not for the block it did it under.
   */
  async function drawProjection(id: string, mine: number): Promise<void> {
    shownToModel.replaceChildren();
    let seen: ProjectionView | null;
    try {
      seen = await transport.get(
        `/v1/conversations/${encodeURIComponent(id)}/projection`,
      );
    } catch (problem) {
      if (mine !== picked) {
        return;
      }
      // A NOTE AND NOT A TROUBLE, which is a distinction this screen has
      // to make and got wrong first: the commonest reason this read fails
      // is that the deployment defines no agent, or none whose model is
      // reachable -- a fact about how the server is set up, true of every
      // conversation on it, and unchanged by anything a person did here.
      // Drawn red at the top of every trajectory it reads as an alarm
      // about the conversation being looked at, which it is not. The rows
      // are this screen's subject and they are already drawn.
      shownToModel.append(
        el(
          'p',
          'note',
          `What the model is shown could not be` +
            ` assembled. ${problemText(problem, 'The server did not say why.')}`,
        ),
      );
      return;
    }
    // The pick moved on while this was in flight. Nothing is drawn: these
    // are conversation A's messages and the rows under them are B's.
    if (mine !== picked) {
      return;
    }
    // Which agent this was computed against, first and outside the panel,
    // for the reason `ProjectionView.agent` exists at all -- see agentLine.
    // It is drawn whether or not there are messages, because "no agent" and
    // "no messages" are different answers and the empty case needs both.
    const agent =
      typeof seen?.agent === 'string' && seen.agent !== '' ? seen.agent : null;
    const named = el(
      'p',
      'projection-agent',
      agent === null ? NO_AGENT_NAMED : agentLine(agent),
    );
    if (agent !== null) {
      named.dataset['agent'] = agent;
    }
    shownToModel.append(named);
    // Guarded like `page.entries` is: a body that is not an array is a
    // shape this console does not have, and reading `.length` off it throws
    // into a catch that swallows -- which would blank the panel with no
    // sentence anywhere saying why.
    const carried = seen?.messages;
    const messages = isList(carried) ? carried : [];
    if (messages.length === 0) {
      shownToModel.append(nothing(PROJECTION_EMPTY));
      return;
    }
    const panel = document.createElement('details');
    panel.className = 'projection-panel';
    const what = document.createElement('summary');
    what.textContent = `what the model is shown — ${describeCount(
      messages.length,
      'message',
      'messages',
    )}`;
    panel.append(what);
    const note = el('p', 'note', projectionNote(seen));
    // The state on the element as well as in the sentence, so the stylesheet
    // can draw "this block is not a recording" as the caveat it is rather
    // than as another paragraph of the note. Same rule `turnSaid` follows
    // for an utterance that is a statement about an absence.
    //
    // Gated on the turn exactly as `projectionNote` is, and not set at all
    // without one: `as-sent` and `not-recorded` are both claims about a turn
    // that was sent something, and the next-prompt answer is of no turn.
    // Written unconditionally, the whole-conversation note carried
    // `not-recorded` -- a per-turn caveat painted onto an answer with no
    // turn -- so the attribute is dormant until a turn is asked for rather
    // than half-live today.
    if (typeof seen?.turn === 'number') {
      note.dataset['block'] =
        seen.systemBlockAsSent === true ? 'as-sent' : 'not-recorded';
    }
    panel.append(note);
    for (const message of messages) {
      const row = el('div', 'shown');
      row.dataset['role'] = textOf(message.role);
      row.append(el('span', 'shown-role', textOf(message.role)));
      row.append(el('pre', 'shown-body', textOf(message.content)));
      panel.append(row);
    }
    shownToModel.append(panel);
  }

  function byTurn(entries: readonly EntryView[]): [number, EntryView[]][] {
    const held = new Map<number, EntryView[]>();
    for (const entry of entries) {
      const mine = held.get(entry.turnOrdinal) ?? [];
      mine.push(entry);
      held.set(entry.turnOrdinal, mine);
    }
    return [...held.entries()].sort((left, right) => left[0] - right[0]);
  }

  /**
   * One turn: the line a person recognises, opening into what it took.
   *
   * <h2>Why the summary is chat-shaped</h2>
   *
   * Because that is the thing somebody scanning a conversation can find. A
   * trajectory that lists every entry flat is complete and unreadable: the
   * utterance that started a turn and the eleven rows answering it look
   * alike, and finding "the turn where I asked about the corpus" means
   * reading all of them. The utterance is the handle, so it is the summary,
   * and everything the run did under it is what opens.
   *
   * <h2>The bars share one axis per turn and not per page</h2>
   *
   * A turn is the span this is measuring inside, and scaling across a whole
   * page would make every bar in a fast turn invisible beside one slow one.
   * The axis resets per turn, and the summary says the turn's own total so
   * two turns are still comparable by a number even though their bars are
   * not comparable by length.
   */
  function turnNode(
    turn: number,
    entries: readonly EntryView[],
    named: ReadonlyMap<string, string>,
  ): HTMLElement {
    const node = document.createElement('details');
    node.className = 'turn';
    node.dataset['turn'] = String(turn);

    const spoken = entries.find((entry) => textOf(entry.kind) === 'utterance');
    const said = turnSaid(spoken);
    const steps = entries.map((entry) => entry.tookMillis);

    const what = document.createElement('summary');
    what.className = 'turn-head';
    what.append(el('span', 'turn-ordinal', `turn ${figure(turn)}`));
    // The state goes on the element and not only into the sentence, so the
    // stylesheet can draw "why there is no utterance here" as the statement
    // it is rather than as the utterance it is not.
    const handle = el('span', 'turn-said', said.text);
    if (said.state !== null) {
      handle.dataset['said'] = said.state;
    }
    what.append(handle);
    const totalled = el('span', 'turn-took', turnTook(steps));
    totalled.dataset['total'] = turnTotal(steps);
    what.append(totalled);
    node.append(what);

    // The axis for this turn, from the first thing recorded in it to the
    // last thing that finished. A turn nothing timed has no axis and its
    // rows say so rather than being placed at zero.
    const at = (entry: EntryView): number | null => {
      const when = textOf(entry.recordedAt);
      const parsed = when === '' ? Number.NaN : Date.parse(when);
      return Number.isNaN(parsed) ? null : parsed;
    };
    const starts = entries
      .map(at)
      .filter((when): when is number => when !== null);
    const first = starts.length === 0 ? null : Math.min(...starts);
    const last =
      starts.length === 0
        ? null
        : Math.max(
            ...entries.map((entry) => {
              const when = at(entry);
              return when === null ? 0 : when + (entry.tookMillis ?? 0);
            }),
          );
    const span =
      first === null || last === null ? 0 : Math.max(1, last - first);

    for (const entry of entries) {
      const row = entryNode(entry, named);
      row.prepend(bar(entry, at(entry), first, span));
      node.append(row);
    }
    return node;
  }

  /**
   * Where a step sat in its turn, and how long it held it.
   *
   * A mark and never a zero-width bar for a step nothing timed: `describeCount`'s
   * rule, which this file already keeps for counts, applied to a duration.
   * A step with no recorded moment is not placed at the beginning either --
   * unplaced and first are different facts, and the track says which by
   * carrying no bar at all.
   */
  function bar(
    entry: EntryView,
    at: number | null,
    first: number | null,
    span: number,
  ): HTMLElement {
    const track = el('div', 'step-track');
    track.dataset['for'] = textOf(entry.kind);
    if (at === null || first === null || span === 0) {
      track.dataset['unplaced'] = '';
      track.title = 'this server recorded no moment for this row';
      return track;
    }
    const drawn = el('span', 'step-bar');
    const took = typeof entry.tookMillis === 'number' ? entry.tookMillis : null;
    drawn.style.setProperty('--at', `${((at - first) / span) * 100}%`);
    drawn.style.setProperty(
      '--for',
      took === null ? '3px' : `${Math.max(0.8, (took / span) * 100)}%`,
    );
    if (took === null) {
      drawn.dataset['instant'] = '';
      drawn.title = 'nobody measured how long this took';
    } else {
      drawn.title = `${took} ms`;
    }
    track.append(drawn);
    return track;
  }

  /**
   * One record of the log: a row of a table, and not a form.
   *
   * <h2>Why this stopped using the shared form field</h2>
   *
   * It used to build five `.field`s -- the label/value pair the config
   * screen stacks *vertically* -- and lay them *across* a flex row. `.field`'s
   * label is `flex: 0 0 8rem` and refuses to shrink, so every field's value
   * absorbed the whole row's shrinkage down to its min-content width, which
   * `overflow-wrap: anywhere` makes one character. The rendered result was
   * five columns of vertical letters, one glyph per line, on every row of
   * every page. A component built to stack cannot be turned sideways.
   *
   * A log is read by scanning one column at a time, which is what a table is
   * and what a row of label/value pairs structurally cannot be: the label
   * repeated on every row is noise, and the alignment is the signal. So the
   * labels move out to {@link recordHead}, once, and these cells are
   * positional.
   *
   * <h2>The content is the column that matters</h2>
   *
   * The version before this deliberately carried no excerpt -- "this tab is
   * for the state, and only the state" -- and what that produced was eighteen
   * rows differing in an ordinal: `recorded` said "not recorded" on all of
   * them, and `length` was the only thing telling three `answer` rows apart.
   * The state is worth a column and not the whole table. What a person opens
   * a log for is what the row *says*, with its state beside it.
   */
  function record(
    row: EntryView,
    named: ReadonlyMap<string, string>,
  ): HTMLElement {
    const node = el('div', 'record');
    node.dataset['record'] = String(row.ordinal);
    // Through `textOf` for the reason the old field did: nothing validates
    // that the body matched its type, and a `kind` the server stopped
    // sending would reach `textContent` as `undefined` and render as that
    // word, in the one column a person scans for a state.
    node.dataset['kind'] = textOf(row.kind);
    if (typeof row.supersededBy === 'number') {
      node.dataset['supersededBy'] = String(row.supersededBy);
    }
    if (typeof row.ejectedAt === 'string' && row.ejectedAt !== '') {
      node.dataset['ejected'] = '';
    }
    // Set for every row that names a model, so the style can pick out a
    // fallback and a reader can filter for one. Absent on an utterance.
    const dispatch = textOf(row.dispatch);
    if (dispatch !== '') {
      node.dataset['dispatch'] = dispatch;
    }

    const whole = logBody(row);
    // The marker is a cell and not a ::before, because it changes when the
    // row opens and a person needs to be told the row opens at all. Without
    // it the detail below was reachable only by clicking a line that gave
    // no sign it was clickable, which is the same as not being there.
    const marker = el('span', 'open-mark', '\u25B8');
    const said = el('span', 'content', firstLineOf(whole));
    // What the record did besides speak, on the line rather than only in
    // the detail: these are the two facts the trajectory shows and the log
    // was dropping.
    for (const extra of [
      logCalls(row),
      logAnswers(row, named),
      logProvenance(row),
    ]) {
      if (extra !== '') {
        said.append(el('span', 'aside', `  ${extra}`));
      }
    }
    const state = el('span', 'state', logState(row));
    if (state.textContent === 'stands') {
      state.dataset['stands'] = '';
    }
    node.append(
      marker,
      el('span', 'at', figure(row.ordinal)),
      el('span', 'turn', figure(row.turnOrdinal)),
      el('span', 'kind', textOf(row.kind)),
      said,
      el('span', 'size', logSize(row)),
      state,
    );
    // The whole of what the row holds, and the fields the columns compress
    // into a marker. Shut until asked: a log is scanned before it is read,
    // and the trajectory tab is where the same rows are read as a story.
    const opened = el('div', 'full');
    opened.append(
      el('pre', 'full-body', whole),
      el('div', 'full-meta', logDetail(row)),
    );
    node.append(opened);
    node.addEventListener('click', () => {
      if (node.dataset['open'] === undefined) {
        node.dataset['open'] = '';
        marker.textContent = '\u25BE';
      } else {
        delete node.dataset['open'];
        marker.textContent = '\u25B8';
      }
    });
    return node;
  }

  // --- the filters, which act on the page in hand --------------------------

  function haystack(entry: EntryView): string {
    const calls = isList(entry.toolCalls) ? entry.toolCalls : [];
    return [
      textOf(entry.kind),
      textOf(entry.excerpt),
      textOf(entry.toolCallId),
      textOf(entry.handle),
      ...calls.map(
        (asked) => `${textOf(asked.name)} ${textOf(asked.arguments)}`,
      ),
    ]
      .join(' ')
      .toLowerCase();
  }

  /**
   * Offer exactly the kinds this page holds, and never rebuild under a choice.
   *
   * Built from the page rather than from {@link KINDS} for the same reason
   * `describeKind` falls back: a kind added on the server has to be reachable
   * here without a change to this file. Rebuilt only when the set really
   * changed, so re-rendering after a keystroke does not reset the select.
   */
  function offerKinds(entries: readonly EntryView[]): void {
    const present = [
      ...new Set(entries.map((entry) => textOf(entry.kind))),
    ].sort();
    if (
      present.length === offering.length &&
      present.every((kind, at) => kind === offering[at])
    ) {
      return;
    }
    offering = present;
    const was = kinds.value;
    const all = document.createElement('option');
    all.value = '';
    all.textContent = 'every kind';
    kinds.replaceChildren(
      all,
      ...present.map((kind) => {
        const option = document.createElement('option');
        option.value = kind;
        option.textContent = kind;
        return option;
      }),
    );
    kinds.value = present.includes(was) ? was : '';
  }

  // --- drawing -------------------------------------------------------------

  /**
   * Draw the page in hand, however the current tab wants it told.
   *
   * Keyed by {@link underlyingReading} and not by `reading`: the log tab
   * draws this very function over the trajectory's own page, filtered by the
   * same search box and kind select, because the search note already says
   * those act on "this page" and the log is that page too. Only the last
   * step — which element one row becomes — is different, and the empty-page
   * sentence, because a genuinely empty log is not a fact about "this
   * reading" the way an empty chat or an empty trajectory is.
   */
  /**
   * The conversation as a shape, across the page, rather than as rows down it.
   *
   * <h2>Why a strip and not a chart</h2>
   *
   * The question this answers is "where did this conversation spend itself",
   * and the useful axis is the turn. So it is one column per turn and three
   * lanes, which is the reading DSH puts above the same rows: a person scans
   * for the wide bars, then reads the rows under them.
   *
   * <h2>Two lanes are measured and one is not, and they are drawn differently
   * for that reason</h2>
   *
   * {@code V16__entry_timing.sql} allows {@code took_ms} on {@code answer} and
   * {@code tool_result} and on nothing else, so a model's turn and a tool's
   * work are the only things this server times. **An utterance has no
   * duration** — a person is not on a clock, and inventing one would make the
   * lane a lie in the direction that flatters the model. So the input lane is
   * a tick that marks where somebody spoke, and only the other two carry
   * width.
   *
   * A row this server never measured is drawn as its own state and never as a
   * zero-width bar, for {@code describeCount}'s reason: nothing counted is not
   * the same fact as counted-and-found-none, and a bar of no width would say
   * the second about the first.
   */
  function drawStrip(entries: readonly EntryView[]): void {
    strip.replaceChildren();
    if (entries.length === 0) {
      return;
    }
    const turns = [...new Set(entries.map((entry) => entry.turnOrdinal))].sort(
      (a, b) => a - b,
    );
    const took = (of: readonly EntryView[]): number | null => {
      const timed = of.filter((entry) => typeof entry.tookMillis === 'number');
      return timed.length === 0
        ? null
        : timed.reduce((run, entry) => run + (entry.tookMillis as number), 0);
    };
    const perTurn = turns.map((turn) => {
      const mine = entries.filter((entry) => entry.turnOrdinal === turn);
      return {
        turn,
        spoke: mine.some((entry) => textOf(entry.kind) === 'utterance'),
        model: took(mine.filter((entry) => textOf(entry.kind) === 'answer')),
        tools: took(
          mine.filter((entry) => textOf(entry.kind) === 'tool_result'),
        ),
      };
    });
    // The widest measured thing on the page sets the scale, so the bars are
    // comparable to each other and to nothing else. There is no absolute
    // scale here and this deliberately does not invent one.
    const widest = Math.max(
      1,
      ...perTurn.map((each) => Math.max(each.model ?? 0, each.tools ?? 0)),
    );

    for (const lane of ['input', 'model', 'tools'] as const) {
      const row = el('div', 'lane');
      row.dataset['lane'] = lane;
      row.append(el('span', 'lane-name', lane));
      const track = el('div', 'lane-track');
      for (const each of perTurn) {
        const cell = el('span', 'cell');
        cell.dataset['turn'] = String(each.turn);
        if (lane === 'input') {
          cell.dataset['mark'] = each.spoke ? 'spoke' : 'silent';
          cell.title = each.spoke
            ? `turn ${each.turn}: a person spoke`
            : `turn ${each.turn}: nobody spoke`;
        } else {
          const millis = lane === 'model' ? each.model : each.tools;
          if (millis === null) {
            cell.dataset['unmeasured'] = '';
            cell.title = `turn ${each.turn}: nothing here was measured`;
          } else {
            // The attribute is what the rule selects on -- styles.ts
            // says a value reaching a rule belongs in a `data-` one
            // and the rule used to sniff the `style` attribute's
            // text instead. The custom property is still how the
            // height gets a length; `attr()` cannot give it one.
            const filled = Math.round((millis / widest) * 100);
            cell.dataset['fill'] = String(filled);
            cell.style.setProperty('--fill', `${filled}%`);
            cell.title = `turn ${each.turn}: ${millis} ms`;
          }
        }
        track.append(cell);
      }
      row.append(track);
      strip.append(row);
    }
  }

  function drawEntries(): void {
    const page = held[underlyingReading(reading)].page;
    if (page === null) {
      return;
    }
    const entries = isList(page.entries) ? page.entries : [];
    if (reading === 'trajectory') {
      drawStrip(entries);
    }
    offerKinds(entries);
    const wanted = search.value.trim().toLowerCase();
    const kind = kinds.value;
    const shown = entries
      .filter((entry) => kind === '' || textOf(entry.kind) === kind)
      .filter((entry) => wanted === '' || haystack(entry).includes(wanted));

    const note = window_.querySelector('.window-note') as HTMLElement;
    const seen = page.offset + entries.length;
    note.textContent =
      `${showingOf(seen, page.total)}` +
      (shown.length === entries.length
        ? ''
        : ` ${shown.length} of them match what is in the boxes above.`);
    back.disabled = page.offset <= 0;
    // The server's limit and not this console's ask: they differ exactly
    // when the ask was over the cap, and stepping by the ask would skip.
    on.disabled = page.offset + page.limit >= page.total;

    if (entries.length === 0) {
      listed.replaceChildren(
        nothing(reading === 'log' ? LOG_EMPTY : NOTHING_LOGGED),
      );
      return;
    }
    if (shown.length === 0) {
      listed.replaceChildren(
        nothing(
          'Nothing on this page matches. That is about this page and not about the' +
            ' conversation — the rest of it has not been read.',
        ),
      );
      return;
    }
    // Over the whole page and not over what the filters left, so that
    // hiding the answer does not un-name the result that answered it.
    // Built before the branch because both readings pair the same way: the
    // log used to render a result's id with no tool beside it, or nothing
    // at all, while the trajectory named it.
    const named = new Map<string, string>();
    for (const each of entries) {
      for (const asked of isList(each.toolCalls) ? each.toolCalls : []) {
        named.set(textOf(asked.id), textOf(asked.name));
      }
    }
    if (reading === 'log') {
      listed.replaceChildren(
        recordHead(),
        ...shown.map((row) => record(row, named)),
      );
      return;
    }
    listed.replaceChildren(
      ...byTurn(shown).map(([turn, mine]) => turnNode(turn, mine, named)),
    );
  }

  /**
   * The tab, and the instruments that belong to it.
   *
   * <b>The strip, the projection and the cost panel are the trajectory's</b>,
   * and they used to be drawn under all three readings because nothing gated
   * them. On the log tab that produced a screen headed "trajectory", carrying
   * the trajectory's density strip, the trajectory's assembled prompt and the
   * trajectory's token accounting, with the archive's records at the bottom
   * of it -- a trajectory with a different table, which is what it looked
   * like. The log is the archive's own records and nothing else.
   *
   * `hidden` rather than removal from the tree: these are the same nodes
   * across a tab switch, holding what was already fetched and drawn, and a
   * reading that re-created them would re-ask for what it already has.
   */
  function drawTabs(): void {
    for (const name of READINGS) {
      const control = tabButtons.get(name) as HTMLButtonElement;
      if (name === reading) {
        control.setAttribute('aria-current', 'page');
      } else {
        control.removeAttribute('aria-current');
      }
    }
    tabNote.textContent = TAB_NOTES[reading];
    title.textContent = reading;
    const inspecting = reading === 'trajectory';
    strip.hidden = !inspecting;
    shownToModel.hidden = !inspecting;
    economics.hidden = !inspecting;
    body.dataset['reading'] = reading;
  }

  /**
   * One number this endpoint cannot give, with the server's own reason under it.
   *
   * **The value slot says "not available" and holds no digit**, which is the
   * point: `ContextView` sends these as explicit nulls rather than omitting
   * them so a client can find the slot, read nothing in it, and say so. A
   * zero here would state that something was counted and found to be none,
   * and an estimate would report a number nobody measured.
   */
  function unavailableSlot(one: Unavailable): HTMLElement {
    const node = el('div', 'field unavailable');
    node.dataset['unavailable'] = textOf(one.component);
    node.append(el('span', 'label', textOf(one.component)));
    node.append(el('span', 'value', 'not available'));
    node.append(el('p', 'reason', textOf(one.reason)));
    return node;
  }

  /**
   * One number, with how the server knows it.
   *
   * <b>The basis is rendered and not dropped</b>, which is the whole contract
   * of `TokenCount`: an estimate shown as a bare figure is indistinguishable
   * from a measurement, and this surface spent four slots refusing to answer
   * rather than allow that. Answering and saying what the answer is worth is
   * the other way to keep the same promise.
   */
  function countedSlot(name: string, count: TokenCount): HTMLElement {
    const node = el('div', 'field counted');
    node.dataset['counted'] = name;
    node.dataset['basis'] = textOf(count.basis);
    node.append(el('span', 'label', name));
    node.append(el('span', 'value', `${count.tokens} tokens`));
    node.append(
      el('p', 'reason', `${basisWord(count.basis)} — ${textOf(count.how)}`),
    );
    return node;
  }

  function drawContext(): void {
    const view = measured;
    if (view === null) {
      return;
    }
    const nodes: HTMLElement[] = [
      el('h3', 'economics-title', 'what this prompt costs'),
    ];

    if (typeof view.sent === 'number') {
      nodes.push(
        el(
          'div',
          'measured',
          `${view.sent} prompt tokens, by the model's own tokenizer, measured on turn ` +
            `${figure(view.sentAtTurn)}. That is the whole request and it is exact.`,
        ),
      );
    } else {
      nodes.push(
        el(
          'div',
          'measured',
          'Nothing in this conversation has been measured: no turn of it reached a model' +
            ' call, so there is no prompt anything counted. Not zero — zero would say the' +
            ' history is empty.',
        ),
      );
    }
    nodes.push(
      el(
        'div',
        'meta',
        `${describeCount(view.turns, 'turn', 'turns')}, of which ` +
          `${describeCount(
            view.turnsMeasured,
            'reached a model call and was counted',
            'reached a model call and were counted',
          )}.` +
          ' The two apart is what says the measurement is older than the conversation.',
      ),
    );

    // WHERE THE PROMPT WENT, which is the one breakdown that needs no
    // tokenizer at all: both terms of every difference below are the
    // model's own count of a whole request, so the difference is exact.
    const series = isList(view.measuredTurns) ? view.measuredTurns : [];
    if (series.length > 1) {
      nodes.push(el('h4', 'growth-head', 'where it went'));
      const growth = el('ul', 'growth');
      for (const step of series) {
        if (typeof step.grewBy !== 'number') {
          continue;
        }
        const line = el(
          'li',
          'growth-step',
          `turn ${figure(step.turn)} — ${step.grewBy >= 0 ? '+' : ''}${step.grewBy}` +
            ` to ${figure(step.promptTokens)}` +
            (step.since === step.turn - 1
              ? ''
              : `, since turn ${figure(step.since)}`),
        );
        // A fold is the only thing that makes a prompt smaller, and it
        // is worth saying out loud: it is the compaction seam showing
        // up as a number, and nowhere else on this surface is it
        // visible at all.
        line.dataset['direction'] = step.grewBy < 0 ? 'fell' : 'grew';
        if (step.grewBy < 0) {
          line.append(
            el(
              'span',
              'fold-note',
              ' — smaller than the turn before it,' +
                ' which is a fold: history was replaced by a summary',
            ),
          );
        }
        growth.append(line);
      }
      nodes.push(growth);
      nodes.push(
        el(
          'p',
          'growth-note',
          'Exact, and computed here from what is already recorded: every number above is' +
            " the model's own count of a whole request, so a difference between two of" +
            " them is the model's count too. No tokenizer is involved and none is needed.",
        ),
      );
    }

    // THE PARTS, on whatever basis this deployment can manage. The three
    // that used to be refusals: a count now arrives with its own basis, and
    // an estimate says it is one rather than being withheld.
    for (const [name, count] of [
      ['system prompt', view.systemPromptTokens],
      ['tool block', view.toolTokens],
      ['messages', view.messageTokens],
    ] as const) {
      if (
        count === null ||
        count === undefined ||
        typeof count.tokens !== 'number'
      ) {
        continue;
      }
      nodes.push(countedSlot(name, count));
    }

    const why = isList(view.unavailable) ? view.unavailable : [];
    if (why.length > 0) {
      nodes.push(
        el(
          'p',
          'unavailable-head',
          "And what nothing here can give, with the server's reason. Not an oversight:" +
            ' no tokenizer and no arithmetic recovers it at any fidelity.',
        ),
      );
      for (const one of why) {
        nodes.push(unavailableSlot(one));
      }
    }

    const prefix = view.prefix;
    if (prefix === null || prefix === undefined) {
      nodes.push(
        el(
          'p',
          'nothing-priced',
          'No agent could be named for this conversation, so its fixed block is not' +
            ' priced. A conversation names an agent once one has answered in it.',
        ),
      );
    } else {
      const node = el('div', 'prefix');
      node.dataset['prefix'] = textOf(prefix.agent);
      node.append(
        el(
          'div',
          'prefix-head',
          `${textOf(prefix.agent)} on ${textOf(prefix.model)} — system prompt ` +
            `${prefix.systemPromptCharacters}, tool block ${prefix.toolCharacters},` +
            ' measured in characters of the JSON the wire carries',
        ),
      );
      const each = isList(prefix.tools) ? prefix.tools : [];
      const list = el('ul', 'tool-costs');
      for (const cost of each) {
        list.append(
          el(
            'li',
            'tool-cost',
            `${textOf(cost.name)} — ${cost.characters} characters`,
          ),
        );
      }
      node.append(list);
      nodes.push(node);
      nodes.push(
        el(
          'p',
          'prefix-note',
          'Those are characters and must not be scaled into tokens. The per-tool numbers' +
            " sum to slightly less than the block: the difference is the array's own" +
            ' commas, which belong to no tool. The list is what this boot would really' +
            ' offer, which can be shorter than the agent declared.',
        ),
      );
    }
    economics.replaceChildren(...nodes);
  }

  // --- the reads -----------------------------------------------------------

  /**
   * Fetch the page the current tab needs, over the wire it actually has.
   *
   * `underlyingReading(reading)` and not `reading`: on the log tab this asks
   * `/trajectory`, the same URL the trajectory tab would ask, and lands the
   * answer in the trajectory's own held slot. There is no `/log` endpoint on
   * this server and there must never need to be one.
   */
  async function fetchPage(): Promise<void> {
    const id = conversation;
    if (id === null) {
      return;
    }
    const mine = picked;
    const wire = underlyingReading(reading);
    const at = held[wire];
    let fetched: EntryPageView;
    try {
      fetched = await transport.get(
        `/v1/conversations/${encodeURIComponent(id)}/${wire}?offset=${at.offset}&limit=${PAGE}`,
      );
    } catch (problem) {
      if (mine !== picked) {
        return;
      }
      at.page = null;
      listed.replaceChildren(
        trouble(
          problemText(
            problem,
            `The ${wire} of this conversation could not be read.`,
          ),
        ),
      );
      return;
    }
    // The pick moved on. `held` was replaced wholesale by `show`, so this
    // would be writing the old conversation's page into the new one's slot.
    if (mine !== picked) {
      return;
    }
    at.page = fetched;
    drawEntries();
  }

  async function fetchContext(): Promise<void> {
    const id = conversation;
    if (id === null) {
      return;
    }
    const mine = picked;
    let read: ContextView;
    try {
      read = await transport.get(
        `/v1/conversations/${encodeURIComponent(id)}/context`,
      );
    } catch (problem) {
      if (mine !== picked) {
        return;
      }
      measured = null;
      economics.replaceChildren(
        trouble(
          problemText(
            problem,
            'What this conversation costs could not be read.',
          ),
        ),
      );
      return;
    }
    if (mine !== picked) {
      return;
    }
    measured = read;
    drawContext();
  }

  /**
   * Switch tab, and read the underlying reading if it has not been read.
   *
   * Kept once fetched, so coming back to a tab does not re-ask; the reload
   * button is what asks again, and it drops both. `underlyingReading` is what
   * makes this the one place that decides whether opening `log` needs the
   * wire at all: if the trajectory tab was opened first, it did not.
   */
  async function choose(name: Reading): Promise<void> {
    reading = name;
    drawTabs();
    if (held[underlyingReading(name)].page === null) {
      await fetchPage();
      return;
    }
    drawEntries();
  }

  async function step(by: number): Promise<void> {
    const at = held[underlyingReading(reading)];
    const page = at.page;
    if (page === null) {
      return;
    }
    at.offset = Math.max(0, at.offset + by * page.limit);
    await fetchPage();
  }

  reload.addEventListener('click', () => {
    project = tier.value.trim() === '' ? null : tier.value.trim();
    background(load());
  });
  tier.addEventListener('change', () => {
    project = tier.value.trim() === '' ? null : tier.value.trim();
    background(load());
  });
  chosen.addEventListener('change', () => {
    if (chosen.value !== '') {
      // Through `show` and not a second copy of it: the select and a
      // sidebar are two ways of saying the same thing, and the day one
      // of them grows a step the other has to grow it too.
      background(show(chosen.value));
    }
  });
  search.addEventListener('input', () => {
    drawEntries();
  });
  kinds.addEventListener('change', () => {
    drawEntries();
  });
  back.addEventListener('click', () => {
    background(step(-1));
  });
  on.addEventListener('click', () => {
    background(step(1));
  });

  /**
   * Read one named conversation, whoever named it.
   *
   * The held pages and the measurement are dropped rather than kept and
   * re-checked: `held` is keyed by reading and not by conversation, so a page
   * left in it would be the previous conversation's rows drawn under the new
   * one's name until the fetch landed. `drawTabs` runs before either read so
   * the tab note is already the right one while the rows are in flight.
   */
  async function show(id: string): Promise<void> {
    picked += 1;
    void drawProjection(id, picked).catch(() => undefined);
    conversation = id;
    // Kept in step for the standalone case, and inert in the composed one:
    // a select with no options holds `''` whatever is assigned to it, and
    // nothing reads this back.
    chosen.value = id;
    held = { chat: emptyHeld(), trajectory: emptyHeld() };
    measured = null;
    // All four of this screen's surfaces, and not only the numbers. The
    // strip is the previous conversation's shape and the projection is its
    // prompt; left standing while the new rows load, they are two silent
    // claims about a conversation they are not about.
    economics.replaceChildren();
    strip.replaceChildren();
    drawTabs();
    await fetchPage();
    await fetchContext();
  }

  async function load(): Promise<void> {
    if (!ownChooser) {
      // Nothing to list: the composing view's sidebar is the chooser, and
      // this screen reads whatever `show` was last told. Before that has
      // happened it says so, rather than leaving a blank section under a
      // tab note or adopting a conversation nobody picked.
      if (conversation === null) {
        drawTabs();
        listed.replaceChildren(nothing(NOT_PICKED));
        return;
      }
      await show(conversation);
      return;
    }
    let rows: readonly ConversationView[];
    try {
      rows = (await transport.get(`/v1/conversations${query()}`)) ?? [];
    } catch (problem) {
      listed.replaceChildren(
        trouble(
          problemText(
            problem,
            'The conversations on this tier could not be listed.',
          ),
        ),
      );
      return;
    }
    chosen.replaceChildren(
      ...rows.map((row) => {
        const option = document.createElement('option');
        option.value = textOf(row.id);
        option.textContent =
          row.project === null || row.project === undefined
            ? textOf(row.id)
            : `${textOf(row.id)} — ${textOf(row.project)}`;
        return option;
      }),
    );
    if (rows.length === 0) {
      // Nothing is being read any more, so nothing may go on standing:
      // an in-flight projection is disowned by the bump, and the strip,
      // the projection and the numbers are cleared with the rows. A tier
      // with no conversations that still showed the last one's shape
      // would be saying there is one.
      picked += 1;
      conversation = null;
      held = { chat: emptyHeld(), trajectory: emptyHeld() };
      measured = null;
      drawTabs();
      listed.replaceChildren(nothing(NO_CONVERSATIONS));
      economics.replaceChildren();
      strip.replaceChildren();
      shownToModel.replaceChildren();
      return;
    }
    // Keep the conversation being read if the fresh listing still holds it,
    // and fall to the first row if it does not. Through `show`, so a reload
    // and a pick go the same way.
    const keep = rows.some((row) => textOf(row.id) === conversation);
    await show(
      keep && conversation !== null ? conversation : textOf(rows[0]?.id),
    );
  }

  drawTabs();

  return {
    element: () => shell,
    load,
    show,
    choose,
    destroy(): void {
      // Nothing to stop: no socket and no timer. This screen only reads.
    },
  };
}
