import { isList } from '../../../sdk/typescript/src/binding/values.ts';
import { markdown } from './markdown';
import type {
  ApprovalDecision,
  ApprovalView,
  CompactionView,
  TurnView,
} from './wire';

/**
 * The scrollback's renderer: one function per kind of thing that can appear in
 * a transcript, and the rule that every one of them obeys.
 *
 * <h2>Rendering is escaping, and here that means `textContent`</h2>
 *
 * Every byte on this screen is somebody else's: model output, file contents
 * echoed back in a tool result, memory bodies, agent names, error text from a
 * remote provider. **There is no `innerHTML` in this module and no template
 * with a hole in it.** Nodes are built with `createElement` and filled with
 * `textContent`, which has no parse step for a payload to reach --
 * `a_payload_in_a_tool_result_reaches_the_dom_as_text` asserts that on the
 * rendered node rather than on a string, and
 * `no_renderer_in_this_module_reaches_for_innerHTML` asserts the absence over
 * the source of every file here so that the rule is checked rather than
 * remembered.
 *
 * `markdown.ts` is inside that rule rather than an exception to it, which is
 * the whole reason an agent's answer can be read for marks at all: it builds
 * nodes too, so markup in an answer is a run of characters it has no rule for
 * and lands in a text node. A library that handed back an HTML string would
 * have needed a sanitiser standing between a model and this page; there is no
 * parse step here for anything to reach.
 *
 * `escape.ts` is not called from this file, and that is its own javadoc's
 * instruction rather than an oversight: *"If a caller finds itself reaching
 * for `escape` and `innerHTML`, the answer is `textContent`, and the escaping
 * was the wrong half of the fix."* A `escape()` around a value on its way into
 * `textContent` would double-encode it -- `a & b` would render as `a &amp; b`
 * on the page -- so the call would not be defence in depth, it would be a
 * visible bug. `escape` is for a value assembled into a string that something
 * else will parse as markup, and this renderer builds no such string.
 *
 * <h2>Who said it is decided by where it came from, never by what it says</h2>
 *
 * Each entry's `role` is set by the code path that knows the provenance -- an
 * utterance because the transcript endpoint called it one, a runtime note
 * because this console's own runtime observed something. **Nothing here reads
 * the text to decide the role.** That is what stops the transcript from
 * lying in either direction: a person who types the runtime's own bracketed
 * wording is still shown as the person who typed it, which
 * `text_that_looks_like_a_runtime_note_is_still_rendered_as_the_persons_own`
 * holds.
 */

/** Where a line came from. The value of `data-role`, and what decides the gutter. */
export type Role =
  'utterance' | 'answer' | 'seam' | 'tool' | 'model' | 'runtime' | 'refusal';

/** What the person said, as the transcript records it. */
export interface UtteranceEntry {
  readonly role: 'utterance';
  readonly text: string;
  readonly ordinal: number | null;
}

/** What the turn came to, whatever its ending. */
export interface AnswerEntry {
  readonly role: 'answer';
  readonly text: string;
  readonly ending: string;
  readonly promptTokens: number | null | undefined;
  readonly ordinal: number | null;
}

/** A fold in the history, at the turn it reaches through. */
export interface SeamEntry {
  readonly role: 'seam';
  readonly throughOrdinal: number;
  readonly summary: string;
}

/** One tool a run called. The name the server registered, never one a model invented. */
export interface ToolEntry {
  readonly role: 'tool';
  readonly tool: string;
  readonly agent: string;
}

/** One model call claimed from the budget. */
export interface ModelEntry {
  readonly role: 'model';
  readonly agent: string;
  /** Steps completed before this call. `wire.OutcomeView.steps` says why this
   *  is not called turns. */
  readonly steps: number;
  readonly modelCalls: number;
}

/**
 * Something this console's runtime observed, which nobody typed and no agent
 * said.
 *
 * Dropped events, a stream that is reconnecting, a conversation that can take
 * no further turn. **It is a separate role and not a differently worded
 * utterance**, because the one thing a transcript must never do is put words
 * in the person's mouth.
 */
export interface RuntimeEntry {
  readonly role: 'runtime';
  readonly text: string;
}

/** Something the server would not do, said in this console's own words. */
export interface RefusalEntry {
  readonly role: 'refusal';
  readonly text: string;
}

export type Entry =
  | UtteranceEntry
  | AnswerEntry
  | SeamEntry
  | ToolEntry
  | ModelEntry
  | RuntimeEntry
  | RefusalEntry;

/**
 * The word in the gutter, per role.
 *
 * Short and fixed-width on purpose: the gutter is the whole of how a person
 * tells what they said from what an agent said from what a tool returned from
 * what this console observed, and a label that changed length per line would
 * stop being a column to read down.
 */
const GUTTER: Readonly<Record<Role, string>> = Object.freeze({
  utterance: 'you',
  answer: 'agent',
  seam: 'folded',
  tool: 'tool',
  model: 'model',
  runtime: 'runtime',
  refusal: 'refused',
});

/**
 * What each ending this server has today means, in a person's words.
 *
 * **Not a switch, and deliberately not exhaustive.** `TurnView` sends the
 * constant's own name precisely so that an ending added on the server reaches
 * a console without a change here; a `switch` with a `default: throw`, or a
 * lookup that trusted whatever came back, would make this file the thing that
 * has to be edited first. An unknown name is rendered as itself.
 *
 * `Object.hasOwn` and not a plain lookup: `ending` is a string off the wire,
 * and `ENDINGS['constructor']` on a plain object literal answers a function.
 * `an_ending_naming_a_property_of_every_object_is_still_unknown` holds it.
 */
const ENDINGS: Readonly<Record<string, string>> = Object.freeze({
  ANSWERED: 'answered',
  TURN_CAP: 'stopped at its turn cap without reaching an answer',
  CALL_BUDGET:
    'stopped: this conversation has spent its whole model-call budget',
  CANCELLED: 'cancelled',
  STUCK: 'stopped: it kept making the same call and was getting nowhere',
  UNAVAILABLE: 'stopped: the model could not be reached',
  SUB_AGENT_FAILED: 'stopped: an agent this one delegated to failed',
  SESSION_GONE: 'stopped: the client that owned the files went away',
  AWAITING: 'waiting for an answer to its question',
  CALL_FAILURES:
    'stopped: it kept writing tool calls as text instead of making them',
});

/** How this turn ended, in words if this build knows the name and as the name if not. */
export function describeEnding(ending: unknown): string {
  if (typeof ending !== 'string' || ending === '') {
    return 'ended, and the server did not say how';
  }
  return Object.hasOwn(ENDINGS, ending)
    ? (ENDINGS[ending] as string)
    : `ended ${ending}`;
}

/**
 * What the prompt cost, or the fact that nothing measured it.
 *
 * **Absence is never rendered as a zero**, which is the one thing `TurnView`'s
 * javadoc asks of whatever reads it: a turn that never reached a model call
 * has no measurement, and `0` states the opposite -- that a prompt was
 * measured and found to be free. `undefined` is treated the same as `null` so
 * that a field the server stops sending is still an absence rather than a
 * crash.
 */
export function describeCost(promptTokens: number | null | undefined): string {
  if (
    promptTokens === null ||
    promptTokens === undefined ||
    typeof promptTokens !== 'number'
  ) {
    return 'prompt not measured';
  }
  return promptTokens === 1
    ? '1 prompt token'
    : `${promptTokens} prompt tokens`;
}

/** How many characters of a body are shown before it is clamped behind a button. */
export const CLAMP_AT = 600;

/** An element with a class and, optionally, its text. */
function el(tag: string, className: string, text?: string): HTMLElement {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) {
    node.textContent = text;
  }
  return node;
}

/**
 * The frame every line shares: a gutter naming the source, and a column for
 * whatever that source produced.
 *
 * `data-role` carries the same word the gutter shows, so a test can assert on
 * provenance without matching prose that may be reworded.
 */
function line(role: Role): HTMLElement {
  const entry = el('div', 'entry');
  entry.dataset['role'] = role;
  entry.appendChild(el('span', 'who', GUTTER[role]));
  return entry;
}

/**
 * A body that is long is clamped and given a button, rather than allowed to
 * push the prompt off the screen.
 *
 * **The clamp measures `text` and not the rendering.** What makes a body too
 * long is how much of it there is, which is a fact about what arrived rather
 * than about how many elements it turned into -- so the same body clamps at
 * the same place whether it is shown verbatim or read for marks.
 */
function clamped(text: string, shown: HTMLElement): HTMLElement {
  const column = el('div', 'column');
  column.appendChild(shown);
  if (text.length > CLAMP_AT) {
    shown.dataset['clamped'] = 'true';
    const more = document.createElement('button');
    more.type = 'button';
    more.className = 'more';
    more.textContent = 'show all';
    more.addEventListener('click', () => {
      const hidden = shown.dataset['clamped'] === 'true';
      shown.dataset['clamped'] = hidden ? 'false' : 'true';
      more.textContent = hidden ? 'show less' : 'show all';
    });
    column.appendChild(more);
  }
  return column;
}

/**
 * Somebody else's text, shown as the characters it is.
 *
 * `pre` because this is a terminal: a tool result's own line breaks and
 * indentation are most of what makes it readable, and collapsing them would
 * be this console reformatting somebody else's output.
 */
function body(text: string): HTMLElement {
  return clamped(text, el('pre', 'body', text));
}

/**
 * An agent's own words, read for the markdown every model writes in.
 *
 * **The line between this and {@link body} is a provenance line, and it is the
 * same one the gutter draws.** An answer and a fold's summary were written by
 * a model, which writes markdown whether or not anything renders it -- so
 * showing them verbatim was showing the source of the answer rather than the
 * answer. Everything else on this screen is either somebody's own typing or
 * this console's own sentence, and neither gets reformatted: a person who
 * typed a hyphen typed a hyphen, and the runtime does not write markdown about
 * itself.
 *
 * `markdown` builds nodes and never markup, so this changes nothing about what
 * a payload in an answer can do -- see that module's own javadoc, and
 * `lands_a_payload_in_a_tool_result_in_the_DOM_as_text_and_not_as_an_element`,
 * which goes on asserting it through this path.
 */
function prose(text: string): HTMLElement {
  const shown = el('div', 'body md');
  shown.appendChild(markdown(text));
  return clamped(text, shown);
}

/** One line of the scrollback, built as nodes. */
export function renderEntry(entry: Entry): HTMLElement {
  switch (entry.role) {
    case 'utterance':
      return utterance(entry);
    case 'answer':
      return answer(entry);
    case 'seam':
      return seam(entry);
    case 'tool':
      return tool(entry);
    case 'model':
      return model(entry);
    case 'runtime':
    case 'refusal':
      return note(entry);
  }
}

function stamp(node: HTMLElement, ordinal: number | null): void {
  if (ordinal !== null) {
    node.dataset['ordinal'] = String(ordinal);
  }
}

function utterance(entry: UtteranceEntry): HTMLElement {
  const node = line('utterance');
  stamp(node, entry.ordinal);
  node.appendChild(body(entry.text));
  return node;
}

function answer(entry: AnswerEntry): HTMLElement {
  const node = line('answer');
  stamp(node, entry.ordinal);
  const column = prose(entry.text);
  // The ending before the cost, and both under the text: a turn that stopped
  // says how it stopped, and a reader who took the text for a reply without
  // reading this would be reading a truncation as an answer.
  const meta = el('div', 'meta');
  meta.appendChild(el('span', 'ending', describeEnding(entry.ending)));
  meta.appendChild(el('span', 'cost', describeCost(entry.promptTokens)));
  column.appendChild(meta);
  node.appendChild(column);
  return node;
}

function seam(entry: SeamEntry): HTMLElement {
  const node = line('seam');
  // The attribute the fold is found by. Its value is the turn the fold
  // reaches through, so a test can assert WHERE the seam landed and not only
  // that one exists.
  node.dataset['seam'] = String(entry.throughOrdinal);
  const column = el('div', 'column');
  column.appendChild(
    el(
      'div',
      'seam-head',
      `everything up to and including turn ${entry.throughOrdinal} was folded into this` +
        ' summary; the turns themselves are still above',
    ),
  );
  column.appendChild(prose(entry.summary));
  node.appendChild(column);
  return node;
}

function tool(entry: ToolEntry): HTMLElement {
  const node = line('tool');
  const column = el('div', 'column');
  column.appendChild(el('div', 'body', entry.tool));
  column.appendChild(el('div', 'meta', `called by ${entry.agent}`));
  node.appendChild(column);
  return node;
}

function model(entry: ModelEntry): HTMLElement {
  const node = line('model');
  const column = el('div', 'column');
  column.appendChild(
    el(
      'div',
      'body',
      `${entry.agent}: model call ${entry.modelCalls}, after ${entry.steps} completed` +
        (entry.steps === 1 ? ' step' : ' steps'),
    ),
  );
  node.appendChild(column);
  return node;
}

function note(entry: RuntimeEntry | RefusalEntry): HTMLElement {
  const node = line(entry.role);
  node.appendChild(body(entry.text));
  return node;
}

/**
 * The transcript, with the seams in it.
 *
 * A seam is a fact *about* the history and not an entry in it, so it is
 * rendered after the turn it reaches through: what is above the marker is what
 * was folded, and what is below it is what the next turn was shown verbatim.
 * Every folded turn is still there at its own ordinal with its own text, which
 * is `CompactionView`'s own promise and the reason nothing is removed here.
 *
 * Both lists are sorted rather than trusted to arrive ordered -- the server
 * orders both, and a renderer that assumed it would put a seam in the wrong
 * place silently the day something changed. A seam reaching through a turn
 * that is not in the answer is emitted after the last turn before it, and one
 * reaching past every turn is emitted at the end, so no fold is dropped for
 * failing to match.
 */
export function transcript(
  turns: readonly TurnView[],
  compactions: readonly CompactionView[],
): Entry[] {
  const ordered = [...turns].sort(
    (left, right) => ordinalOf(left) - ordinalOf(right),
  );
  const folds = [...compactions].sort(
    (left, right) => left.throughOrdinal - right.throughOrdinal,
  );
  const entries: Entry[] = [];
  let next = 0;

  for (const turn of ordered) {
    const ordinal = ordinalOf(turn);
    entries.push({ role: 'utterance', text: textOf(turn.utterance), ordinal });
    entries.push({
      role: 'answer',
      text: textOf(turn.answer),
      ending: typeof turn.ending === 'string' ? turn.ending : '',
      promptTokens: turn.promptTokens,
      ordinal,
    });
    while (
      next < folds.length &&
      (folds[next] as CompactionView).throughOrdinal <= ordinal
    ) {
      const fold = folds[next] as CompactionView;
      entries.push({
        role: 'seam',
        throughOrdinal: fold.throughOrdinal,
        summary: textOf(fold.summary),
      });
      next += 1;
    }
  }
  for (const fold of folds.slice(next)) {
    entries.push({
      role: 'seam',
      throughOrdinal: fold.throughOrdinal,
      summary: textOf(fold.summary),
    });
  }
  return entries;
}

/** A number, or zero for a field the server stopped sending. */
function ordinalOf(turn: TurnView): number {
  return typeof turn.ordinal === 'number' ? turn.ordinal : 0;
}

/**
 * Text, whatever arrived.
 *
 * `api.get`'s javadoc is explicit that nothing validates the shape the server
 * sent and that a screen should show a missing field as missing rather than
 * throw over the page. An absent `utterance` renders as an empty line, which
 * is visibly wrong and still a page.
 */
function textOf(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

/**
 * What answering one approval came to, as the block that asked needs to know it.
 *
 * An explicit refusal puts the buttons back. Unknown delivery keeps them closed
 * until a fresh retained read: a lost receipt does not establish no effect.
 * `note` is shown under the block either way --
 * the server's sentence for a busy conversation, or why the answer was not taken.
 */
export interface ApprovalResult {
  readonly answered: boolean;
  /** Unknown delivery leaves controls closed until a fresh retained read. */
  readonly uncertain?: boolean;
  readonly note: string | null;
}

/** The four buttons, in the order they are drawn, and the words on each. */
export const DECISIONS: readonly (readonly [ApprovalDecision, string])[] =
  Object.freeze([
    ['once', 'Allow once'],
    ['conversation', 'Allow for this conversation'],
    ['project', 'Allow for project…'],
    ['deny', 'Deny'],
  ] as const);

/**
 * The prefix a project approval starts on: the server's `defaultPrefix` when it
 * is a leading part of the command, and the program alone otherwise.
 *
 * Checked rather than trusted because `approval.answer` refuses a prefix that
 * does not lead the command, and a block that opened on one would offer a
 * confirm button the server was certain to refuse.
 */
export function startingPrefix(
  command: readonly string[],
  suggested: readonly string[] | null | undefined,
): readonly string[] {
  const offered = isList(suggested) ? suggested : [];
  const leads =
    offered.length > 0 &&
    offered.length <= command.length &&
    offered.every((word, index) => word === command[index]);
  return leads
    ? command.slice(0, offered.length)
    : command.slice(0, Math.min(1, command.length));
}

/**
 * One question a run asked: the command, where, why, and the four answers.
 *
 * Built beside the scrollback rather than into it, for `repl.ts`'s offer's
 * reason: the scrollback is replaced whole on every refresh, and a control
 * built there would not survive one.
 *
 * **The prefix is chosen by pointing, not typed.** Each argument is a chip, and
 * chip `i` selects arguments `0..i` -- a project approval covers a *leading
 * part* of the command, so there is nothing else a person could meaningfully
 * pick, and a text field would invite a prefix the server refuses.
 *
 * Every control is disabled while an answer is out and stays disabled once one
 * is recorded; `data-answered` names the decision that was.
 */
export function renderApproval(
  view: ApprovalView,
  answer: (
    decision: ApprovalDecision,
    prefix: readonly string[] | null,
  ) => Promise<ApprovalResult>,
): HTMLElement {
  const command = isList(view.command) ? view.command.map(textOf) : [];
  const block = el('article', 'approval');
  block.dataset['approval'] = textOf(view.id);

  block.appendChild(
    el(
      'div',
      'approval-head',
      `${textOf(view.agent) || 'the agent'} asks before running this command`,
    ),
  );
  const shown = el(
    'pre',
    'approval-command',
    JSON.stringify(view.commands ?? [command], null, 2),
  );
  shown.dataset['approvalCommand'] = '';
  block.appendChild(shown);
  block.appendChild(detail('side', textOf(view.side)));
  block.appendChild(detail('cwd', textOf(view.cwd)));
  block.appendChild(
    detail(
      'reason',
      typeof view.reason === 'string' && view.reason !== ''
        ? view.reason
        : 'none given: this environment asks before every command it has no rule for',
    ),
  );

  const actions = el('div', 'approval-actions');
  const buttons: HTMLButtonElement[] = [];
  for (const [decision, words] of DECISIONS) {
    const control = document.createElement('button');
    control.type = 'button';
    control.textContent = words;
    control.dataset['decision'] = decision;
    buttons.push(control);
    actions.appendChild(control);
  }
  block.appendChild(actions);

  let prefix: readonly string[] = startingPrefix(command, view.defaultPrefix);
  const chooser = el('div', 'approval-prefix');
  chooser.dataset['prefix'] = '';
  chooser.hidden = true;
  const chips = el('div', 'approval-chips');
  const covers = el('div', 'approval-covers');
  const confirm = document.createElement('button');
  confirm.type = 'button';
  confirm.dataset['confirmPrefix'] = '';
  buttons.push(confirm);
  const chipButtons = command.map((word, index) => {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'chip';
    chip.textContent = word;
    chip.dataset['chip'] = String(index);
    chip.addEventListener('click', () => {
      prefix = command.slice(0, index + 1);
      showPrefix();
    });
    chips.appendChild(chip);
    return chip;
  });
  buttons.push(...chipButtons);
  chooser.append(
    el(
      'div',
      'approval-prefix-head',
      'allow, on this side of this project, any command starting',
    ),
    chips,
    covers,
    confirm,
  );
  block.appendChild(chooser);

  const said = el('p', 'approval-note');
  said.dataset['approvalNote'] = '';
  said.hidden = true;
  block.appendChild(said);

  function showPrefix(): void {
    chipButtons.forEach((chip, index) => {
      chip.dataset['selected'] = String(index < prefix.length);
    });
    covers.textContent = prefix.join(' ');
    confirm.textContent = `Allow \u201c${prefix.join(' ')}\u201d for this project`;
  }
  showPrefix();

  function settle(result: ApprovalResult, decision: ApprovalDecision): void {
    if (result.answered) {
      block.dataset['answered'] = decision;
    } else if (!result.uncertain) {
      for (const control of buttons) {
        control.disabled = false;
      }
    }
    said.hidden = result.note === null;
    said.textContent = result.note ?? '';
    said.dataset['approvalNote'] = result.uncertain
      ? 'uncertain'
      : result.answered
        ? 'answered'
        : 'refused';
  }

  function send(
    decision: ApprovalDecision,
    chosen: readonly string[] | null,
  ): void {
    for (const control of buttons) {
      control.disabled = true;
    }
    void answer(decision, chosen).then(
      (result) => settle(result, decision),
      (problem: unknown) =>
        settle(
          {
            answered: false,
            uncertain: true,
            note:
              problem instanceof Error
                ? problem.message
                : 'That answer could not be sent.',
          },
          decision,
        ),
    );
  }

  for (const control of buttons.slice(0, DECISIONS.length)) {
    const decision = control.dataset['decision'] as ApprovalDecision;
    control.addEventListener('click', () => {
      if (decision === 'project') {
        // Opens the chooser and sends nothing: the prefix is the answer's
        // whole content, and it has not been chosen yet.
        chooser.hidden = !chooser.hidden;
        control.setAttribute('aria-expanded', String(!chooser.hidden));
        return;
      }
      send(decision, null);
    });
  }
  confirm.addEventListener('click', () => {
    send('project', prefix);
  });
  return block;
}

/** A labelled value inside an approval block. */
function detail(label: string, value: string): HTMLElement {
  const row = el('div', 'approval-detail');
  row.dataset['detail'] = label;
  row.append(el('span', 'label', label), el('span', 'value', value));
  return row;
}
