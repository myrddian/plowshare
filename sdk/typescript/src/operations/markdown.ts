/**
 * The markdown the agents write, as data.
 *
 * <h2>THERE ARE TWO COPIES OF THIS GRAMMAR AND THIS ONE IS THE SURVIVOR</h2>
 *
 * The other is `plowshare-console/src/repl/markdown.ts`, which additionally
 * holds `toDom` and `markdown` — the browser emitter, which did not travel and
 * must not. <b>If you are fixing a grammar bug, fix it here and then carry it
 * there</b>, because the flow runs outward from this file and not inward:
 *
 *     copy -> the TUI evolves freely -> the console adopts this module ->
 *     the console's copy dies.
 *
 * The direction is the design (spec §2) and it is not arbitrary. An extraction
 * would have made the console the source of truth, and the console has known
 * defects that every later consumer would then inherit; it would also have tied
 * this client's freedom to change shape to not breaking a production one. So
 * this is a copy with a stated survivor, made at a moment when the survivor is
 * not yet known to be right. <b>The duplication is deliberate, bounded and
 * directional</b> — and it ends when the console imports this module and deletes
 * its own half.
 *
 * The tests travelled with the file, which is the other half of the same
 * obligation: `markdown.test.ts` next door is the console's suite minus its one
 * `toDom` group. A copy that arrives without its tests is the weaker of the two
 * and nobody ever adopts it, which is how a temporary fork becomes a permanent
 * one.
 *
 * <h2>Parse only, and why the emitter is somebody else's file</h2>
 *
 * `parse` knows the grammar and produces plain data; it builds no nodes, writes
 * no strings anything parses, and knows of no screen. That is what makes the
 * two emitters possible — `toDom` in the console's copy, `toTerminal` in this
 * module's `view/` — and it is also what makes this file legal in `logic/` at
 * all: no `node:` import, no `process`, no DOM global, per spec §3.1. The
 * neutrality scan in `src/neutrality.test.ts` reads this file on every run.
 *
 * Two decisions are deliberately <b>not</b> made here, because making them
 * would make them for both emitters:
 *
 *   - <b>a heading keeps the level that was written</b>, 1 through 6. The
 *     console clamps to `h3` because its shell owns `h1` and its screens own
 *     `h2`; a terminal has no such constraint.
 *   - <b>a link keeps its address as an address.</b> Flattening it to
 *     `label (address)` is the browser emitter's choice, and a parse that had
 *     already flattened would leave a second emitter nothing to decide with.
 *
 * <h2>Where it is deliberately not CommonMark</h2>
 *
 * <b>No underscore emphasis, at all.</b> Not an omission — a decision this
 * corpus forces. The agents' prose names their tools constantly, and
 * `file_read and file_glob send you to file_roots` has three underscores in it;
 * any pair rule over `_` turns the run between two of them into emphasis and
 * eats the underscores out of a name the reader is meant to be able to retype.
 * `*` and `**` are supported and `_` and `__` are literal characters, which is
 * the trade that loses a mark nobody writes to keep a name everybody does.
 * `markdown.test.ts` holds it under `leaves snake_case names alone`.
 *
 * <b>A mark with whitespace against it opens nothing</b>, so `5 * 3 * 2` is
 * arithmetic rather than an emphasised `3`, and <b>an unclosed mark is text</b>,
 * so a truncated answer — a `TURN_CAP` landing mid-fence — shows the rest of
 * what it managed to say instead of swallowing it into a code block. Both are
 * the same principle: a renderer that guesses deletes characters out of
 * somebody else's answer, and showing the mark is the smaller error.
 */

/** The inline marks, tried left to right at each position. */
const INLINE = new RegExp(
  [
    // Code first, so a name with a mark in it survives being quoted. The
    // backreference makes the run length its own closer, which is what lets
    // ``a `b` c`` hold a backtick.
    '(`+)([\\s\\S]+?)\\1',
    // `[^\s*]` at both ends is the whitespace rule: the mark has to bite on
    // something. The middle is lazy so the nearest closer wins.
    '\\*\\*([^\\s*](?:[\\s\\S]*?[^\\s*])?)\\*\\*',
    '\\*([^\\s*](?:[\\s\\S]*?[^\\s*])?)\\*',
    // No parentheses and no whitespace in the address: a URL that contains one
    // is left as the text it is rather than truncated at the character this
    // pattern could not carry.
    '\\[([^\\]\\n]*)\\]\\(([^()\\s]*)\\)',
  ].join('|'),
  'g',
);

/**
 * One piece of a run of prose, as data rather than as an element.
 *
 * `link` keeps its address as an address — see the header. There is no `em`
 * arm reachable from an underscore, and that is the point of the note there.
 */
export type Span =
  | { readonly kind: 'text'; readonly text: string }
  | { readonly kind: 'code'; readonly text: string }
  | { readonly kind: 'strong'; readonly spans: readonly Span[] }
  | { readonly kind: 'em'; readonly spans: readonly Span[] }
  | {
      readonly kind: 'link';
      readonly label: readonly Span[];
      readonly address: string;
    };

/**
 * One list item: its blocks, and whether the source wrote it on one line.
 *
 * `tight` is a fact about the SOURCE and not about what the blocks turned out
 * to be, and those are different questions: a wrapped item and a one-line item
 * both parse to a single paragraph, and this grammar has always rendered the
 * first loose and the second tight. An emitter that inferred it from the shape
 * of `blocks` cannot tell them apart — which is exactly the bug this type
 * exists to close, in a terminal as much as in a browser.
 */
export interface Item {
  readonly tight: boolean;
  readonly blocks: readonly Block[];
}

/**
 * One block of a document, as data.
 *
 * `heading.level` is the level that was <b>written</b>, 1 through 6. Deciding
 * on a ceiling here would decide for every emitter; see the header.
 *
 * A list item is an `Item`, never a bare run of spans, so that nested content
 * needs no second shape.
 */
export type Block =
  | { readonly kind: 'para'; readonly spans: readonly Span[] }
  | {
      readonly kind: 'heading';
      readonly level: number;
      readonly spans: readonly Span[];
    }
  | { readonly kind: 'code'; readonly text: string; readonly lang?: string }
  | { readonly kind: 'quote'; readonly blocks: readonly Block[] }
  | { readonly kind: 'rule' }
  | {
      readonly kind: 'table';
      readonly align: readonly Align[];
      readonly header: readonly Cell[];
      readonly rows: readonly (readonly Cell[])[];
    }
  | {
      readonly kind: 'list';
      readonly ordered: false;
      readonly items: readonly Item[];
    }
  | {
      readonly kind: 'list';
      readonly ordered: true;
      readonly start: number;
      readonly items: readonly Item[];
    };

/** How a table column's text sits in its cell, as the delimiter row spelt it. */
export type Align = 'left' | 'center' | 'right';

/** One table cell: a run of prose, which is all a pipe table can hold. */
export type Cell = readonly Span[];

/** ` ``` ` or `~~~`, with whatever info string followed it. */
const FENCE = /^ {0,3}(`{3,}|~{3,})(.*)$/;
/** `# ` through `###### `. The space is required, so `#1` is not a heading. */
const HEADING = /^ {0,3}(#{1,6})[ \t]+(.*)$/;
/** Three or more of one mark, alone on the line. Checked before the bullets,
 *  because `* * *` is a rule and reads as a list item to the pattern below. */
const RULE =
  /^ {0,3}(?:-[ \t]*){3,}$|^ {0,3}(?:\*[ \t]*){3,}$|^ {0,3}(?:_[ \t]*){3,}$/;
/** `> `, with the space optional so an empty quoted line still quotes. */
const QUOTE = /^ {0,3}> ?(.*)$/;
/** `- `, `* `, `+ `. The space is required, so a dash mid-sentence is a dash. */
const BULLET = /^( {0,3})([-*+])[ \t]+(.*)$/;
/** `1. ` or `1) `. The value is kept, not necessarily the spelling: a list
 *  renumbered from 1 would be this client rewriting which step somebody was
 *  told to take. The digits round-trip through `Number` and `String`, so a
 *  leading zero is not -- `007.` starts at 7. */
const ORDERED = /^( {0,3})(\d{1,9})[.)][ \t]+(.*)$/;

/** One run of prose, as data. The grammar lives here and nothing else does. */
/**
 * Code, as the characters a shell would actually receive.
 *
 * <h3>What this removes, and the one thing it is careful not to</h3>
 *
 * <p><b>Only substitutions that are invisible.</b> A narrow no-break space
 * looks exactly like a space, a non-breaking hyphen exactly like a hyphen, and
 * a zero-width space like nothing at all — so `ORDER BY col\u202fDESC;` copied
 * out of a scrollback and pasted into psql is a syntax error with no visible
 * cause. Curly quotes, dashes and arrows are left alone: they are visible,
 * a reader can judge them, and some of them are genuinely meant.
 *
 * <h3>Prose keeps every one of these, deliberately</h3>
 *
 * <p><b>Measured before this function existed.</b> The model this deployment
 * runs emits U+202F between the words of a keyword phrase — `descending
 * if\u202fDESC\u202fis specified` — in about one answer in eight, asked the
 * same question eight times with nothing of this project in the path. It is
 * valid UTF-8, category Zs, and it carries intent: <i>do not break DESC away
 * from its neighbours</i>.
 *
 * <p>And the terminal honours it. `string-width` measures it as one column,
 * exactly like a space, and `wrap-ansi` refuses to break there — given 44
 * columns it keeps `if DESC is` whole and breaks earlier, where plain spaces
 * break between `if` and `DESC`. <b>That is working typography and stripping it
 * would be vandalism.</b> The argument for this function was never that the
 * character is wrong; it is that code has to be pasteable, and inside code the
 * line breaks belong to the terminal while the characters do not.
 */
function pasteable(code: string): string {
  return (
    code
      // Spaces that are not the space they look like.
      .replace(/[\u00a0\u2007\u2009\u202f\u2060]/gu, ' ')
      // A hyphen that is not the hyphen it looks like. `--color` written with
      // these is a different flag and looks identical.
      .replace(/\u2011/gu, '-')
      // Characters that look like nothing and are not nothing.
      .replace(/(?:\u200b|\u200c|\u200d|\ufeff)/gu, '')
  );
}

export function spansOf(text: string): Span[] {
  const spans: Span[] = [];
  let at = 0;
  for (const found of text.matchAll(INLINE)) {
    const start = found.index;
    if (start > at) {
      spans.push({ kind: 'text', text: text.slice(at, start) });
    }
    if (found[2] !== undefined) {
      spans.push({ kind: 'code', text: pasteable(found[2]) });
    } else if (found[3] !== undefined) {
      spans.push({ kind: 'strong', spans: spansOf(found[3]) });
    } else if (found[4] !== undefined) {
      spans.push({ kind: 'em', spans: spansOf(found[4]) });
    } else {
      spans.push({
        kind: 'link',
        label: spansOf(found[5] as string),
        address: found[6] as string,
      });
    }
    at = start + found[0].length;
  }
  if (at < text.length) {
    spans.push({ kind: 'text', text: text.slice(at) });
  }
  return spans;
}

/**
 * The line after the fence opened at `at` closes, or `null` if nothing does.
 *
 * Asked in two places and answered once: the block loop needs it to decide
 * whether to build a code block, and the paragraph collector needs the same
 * answer to decide whether the fence line ends the paragraph it is in. A fence
 * nobody closed is not a block, and both questions have to agree about that or
 * a truncated answer renders with a paragraph break through the middle of it.
 */
function fenceEnd(lines: readonly string[], at: number): number | null {
  const opened = (lines[at] as string).match(FENCE);
  if (opened === null) {
    return null;
  }
  const marker = opened[1] as string;
  for (let index = at + 1; index < lines.length; index += 1) {
    const closing = (lines[index] as string).match(FENCE);
    if (
      closing !== null &&
      (closing[1] as string)[0] === marker[0] &&
      (closing[1] as string).length >= marker.length &&
      (closing[2] as string).trim() === ''
    ) {
      return index;
    }
  }
  return null;
}

/** A pipe table's delimiter row: `|---|:--:|`, with the outer pipes optional. */
const DELIMITER =
  /^ {0,3}\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$/;

/**
 * A table row's cells, split on the pipes that are not inside a code run and
 * not escaped. A pipe in `a || b` quoted as code is part of the code, and
 * cutting a cell there would put half an expression in the next column.
 */
function cellsOf(line: string): string[] {
  let row = line.trim();
  if (row.startsWith('|')) {
    row = row.slice(1);
  }
  if (row.endsWith('|') && !row.endsWith('\\|')) {
    row = row.slice(0, -1);
  }
  const cells: string[] = [];
  let cell = '';
  let ticks = 0;
  for (let index = 0; index < row.length; index += 1) {
    const char = row[index] as string;
    if (char === '\\' && row[index + 1] === '|') {
      cell += '|';
      index += 1;
    } else if (char === '`') {
      let run = 0;
      while (row[index + run] === '`') {
        run += 1;
      }
      ticks = ticks === 0 ? run : ticks === run ? 0 : ticks;
      cell += '`'.repeat(run);
      index += run - 1;
    } else if (char === '|' && ticks === 0) {
      cells.push(cell.trim());
      cell = '';
    } else {
      cell += char;
    }
  }
  cells.push(cell.trim());
  return cells;
}

/**
 * Whether a table starts at `at`: a row with a pipe in it, then a delimiter row
 * with the same number of columns. Both are required, so a sentence that
 * happens to contain `|` stays a sentence.
 */
function opensTable(lines: readonly string[], at: number): boolean {
  const head = lines[at];
  const rule = lines[at + 1];
  return (
    head !== undefined &&
    rule !== undefined &&
    head.includes('|') &&
    DELIMITER.test(rule) &&
    rule.includes('-') &&
    cellsOf(head).length === cellsOf(rule).length
  );
}

/** The table starting at `at`, and the line after it. */
function table(
  lines: readonly string[],
  at: number,
): { block: Block; next: number } {
  const header = cellsOf(lines[at] as string);
  const align = cellsOf(lines[at + 1] as string).map((spec): Align =>
    spec.startsWith(':') && spec.endsWith(':')
      ? 'center'
      : spec.endsWith(':')
        ? 'right'
        : 'left',
  );
  const rows: Cell[][] = [];
  let index = at + 2;
  for (; index < lines.length; index += 1) {
    const line = lines[index] as string;
    if (line.trim() === '' || !line.includes('|')) {
      break;
    }
    // Short rows are padded and long ones cut, so every row has exactly
    // the header's columns and an emitter never has to ask.
    const cells = cellsOf(line).slice(0, header.length);
    while (cells.length < header.length) {
      cells.push('');
    }
    rows.push(cells.map(spansOf));
  }
  return {
    block: { kind: 'table', align, header: header.map(spansOf), rows },
    next: index,
  };
}

/** Whether the line at `at` is the start of something other than more paragraph. */
function opensBlock(lines: readonly string[], at: number): boolean {
  const line = lines[at] as string;
  return (
    line.trim() === '' ||
    RULE.test(line) ||
    HEADING.test(line) ||
    QUOTE.test(line) ||
    BULLET.test(line) ||
    ORDERED.test(line) ||
    opensTable(lines, at) ||
    fenceEnd(lines, at) !== null
  );
}

/** How far in an item's own content sits: its indent, its marker, and the space after. */
function contentIndent(matched: RegExpMatchArray, line: string): number {
  const marker = (matched[1] as string).length + (matched[2] as string).length;
  const after =
    line.slice(marker).length - line.slice(marker).trimStart().length;
  return marker + after;
}

/** `line` with up to `depth` leading spaces taken off. */
function dedent(line: string, depth: number): string {
  let taken = 0;
  while (taken < depth && (line[taken] === ' ' || line[taken] === '\t')) {
    taken += 1;
  }
  return line.slice(taken);
}

/**
 * One item, as an `Item`.
 *
 * A one-line item is prose and never block syntax, which is this grammar's
 * existing choice rather than CommonMark's: `- # x` keeps the hash. Reading it
 * as blocks would strip the marker, so the branch is kept here rather than left
 * to an emitter to infer from the shape of what came back. `tight` records
 * which branch ran, because a wrapped item that happens to parse to a single
 * paragraph is not the same source as a one-line item -- see `Item`.
 */
function item(content: readonly string[]): Item {
  if (content.length === 1) {
    return {
      tight: true,
      blocks: [{ kind: 'para', spans: spansOf(content[0] as string) }],
    };
  }
  return { tight: false, blocks: blocksOf(content) };
}

/**
 * The list starting at `at`, and the line after it.
 *
 * A continuation line -- indented under the item, or running on from it
 * without a blank line between -- belongs to the item above. A blank line
 * followed by anything that is not indented and not another item ends the
 * list, which is what keeps the sentence after a list out of its last bullet.
 */
function list(
  lines: readonly string[],
  at: number,
): { block: Block; next: number } {
  const opened =
    (lines[at] as string).match(BULLET) ?? (lines[at] as string).match(ORDERED);
  const numbered = (lines[at] as string).match(BULLET) === null;
  const items: Item[] = [];

  let content: string[] = [(opened as RegExpMatchArray)[3] as string];
  let depth = contentIndent(opened as RegExpMatchArray, lines[at] as string);
  let index = at + 1;
  let blank = false;

  for (; index < lines.length; index += 1) {
    const line = lines[index] as string;
    if (line.trim() === '') {
      blank = true;
      continue;
    }
    const next = numbered ? line.match(ORDERED) : line.match(BULLET);
    const indented = line.length - line.trimStart().length >= depth;
    if (next !== null && !indented) {
      items.push(item(content));
      content = [next[3] as string];
      depth = contentIndent(next, line);
      blank = false;
      continue;
    }
    if (!indented && blank) {
      break;
    }
    if (
      !indented &&
      (BULLET.test(line) || ORDERED.test(line) || RULE.test(line))
    ) {
      // A bullet under a numbered list, or the other way about: a second
      // list rather than a stray item in this one.
      break;
    }
    if (blank) {
      content.push('');
      blank = false;
    }
    content.push(dedent(line, depth));
  }
  items.push(item(content));

  return {
    block: numbered
      ? {
          kind: 'list',
          ordered: true,
          start: Number((opened as RegExpMatchArray)[2]),
          items,
        }
      : { kind: 'list', ordered: false, items },
    next: index,
  };
}

/** The lines, as the blocks they spell. */
function blocksOf(lines: readonly string[]): Block[] {
  const blocks: Block[] = [];
  let at = 0;

  while (at < lines.length) {
    const line = lines[at] as string;
    if (line.trim() === '') {
      at += 1;
      continue;
    }

    const closes = fenceEnd(lines, at);
    if (closes !== null) {
      // The info string's first word, kept so an emitter can label and
      // colour the block. Absent rather than empty when nobody wrote one.
      const lang =
        ((line.match(FENCE) as RegExpMatchArray)[2] as string)
          .trim()
          .split(/\s+/u)[0] ?? '';
      const text = pasteable(lines.slice(at + 1, closes).join('\n'));
      blocks.push(
        lang === '' ? { kind: 'code', text } : { kind: 'code', text, lang },
      );
      at = closes + 1;
      continue;
    }

    if (opensTable(lines, at)) {
      const built = table(lines, at);
      blocks.push(built.block);
      at = built.next;
      continue;
    }

    if (RULE.test(line)) {
      blocks.push({ kind: 'rule' });
      at += 1;
      continue;
    }

    const heading = line.match(HEADING);
    if (heading !== null) {
      const stripped = (heading[2] as string).replace(/[ \t]+#+[ \t]*$/, '');
      blocks.push({
        kind: 'heading',
        level: (heading[1] as string).length,
        spans: spansOf(stripped),
      });
      at += 1;
      continue;
    }

    if (QUOTE.test(line)) {
      const quoted: string[] = [];
      while (at < lines.length) {
        const inside = (lines[at] as string).match(QUOTE);
        if (inside === null) {
          break;
        }
        quoted.push(inside[1] as string);
        at += 1;
      }
      blocks.push({ kind: 'quote', blocks: blocksOf(quoted) });
      continue;
    }

    if (BULLET.test(line) || ORDERED.test(line)) {
      const built = list(lines, at);
      blocks.push(built.block);
      at = built.next;
      continue;
    }

    const paragraph: string[] = [line];
    at += 1;
    while (at < lines.length && !opensBlock(lines, at)) {
      paragraph.push(lines[at] as string);
      at += 1;
    }
    const joined = paragraph.join('\n').replace(/\s+$/, '');
    blocks.push({ kind: 'para', spans: spansOf(joined) });
  }
  return blocks;
}

/** The text, as the blocks it spells. */
export function parse(text: string): Block[] {
  return blocksOf(text.split('\n'));
}
