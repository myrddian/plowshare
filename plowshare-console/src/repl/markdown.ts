/**
 * The markdown the agents write, rendered as nodes.
 *
 * <h2>THERE IS A SECOND COPY OF THIS GRAMMAR AND IT IS THE SURVIVOR</h2>
 *
 * `plowshare-tui/src/logic/markdown.ts` holds `Span`, `Item`, `Block`,
 * `spansOf` and `parse` -- everything this file has above `toDom` -- copied out
 * of here with its tests, and <b>that copy is the one meant to outlive this
 * one</b>. <b>A grammar bug fixed here must be carried there.</b> The flow is
 * `copy -> the TUI evolves freely -> this console adopts the settled module ->
 * this copy dies`, and it runs outward from the terminal client rather than
 * inward from here: an extraction would have made this file the source of
 * truth, and this file has known defects that every later consumer would then
 * have inherited.
 *
 * <b>Nothing else about this file changed when that copy was taken, and this
 * pointer is the whole of the edit.</b> `toDom` and `markdown` below are the
 * browser emitter and stayed here deliberately; the terminal grows its own.
 * `markdown.test.ts`'s 49 cases travelled too, all but the `toDom` group -- a
 * copy that arrives without its tests is the weaker of the two and nobody ever
 * adopts it, which is how a temporary fork becomes a permanent one.
 *
 * <h2>Why this exists</h2>
 *
 * Every shipped agent writes markdown, because every model does: `close_reader`
 * answers in four numbered steps, `ask_synthesiser` hands back quoted
 * attribution blocks, and `**bold**` and backticked tool names run through all
 * of them. The scrollback showed all of it as the characters it is -- a
 * numbered list as `1.`, a heading as `#` -- which is not a rendering of the
 * answer so much as a rendering of its source.
 *
 * <h2>Nodes, never markup, and what that buys</h2>
 *
 * This module builds DOM: `createElement`, `textContent`, `append`. **It never
 * assembles a string that anything parses**, which is the rule
 * `no_renderer_in_this_module_reaches_for_innerHTML` asserts over the source of
 * every file in this console, and which this file is deliberately inside
 * rather than exempt from.
 *
 * That is not a constraint this renderer works around -- it is the whole
 * security argument, and it is stronger than the escaping one. A markdown
 * library that hands back an HTML string needs a sanitiser after it, and the
 * sanitiser is then the thing standing between a model's output and the page.
 * Here there is no parse step for a payload to reach at all: markup in the
 * source is a run of characters this file has no rule for, so it lands in a
 * text node and renders as itself, which
 * `lands_markup_in_the_source_in_the_DOM_as_text_and_not_as_an_element` holds
 * on the rendered tree.
 *
 * The same argument is why a link is not an anchor. `escape.ts` records that a
 * `javascript:` URL survives escaping intact, so an `href` built from model
 * output would need a scheme allowlist to be safe. This renderer has no
 * element with an `href` on it, so there is no allowlist to get wrong: a link
 * renders as its label followed by its address, and a person reads where it
 * would have gone before deciding to go there.
 *
 * <h2>Two layers, and why the guarantee got easier to check</h2>
 *
 * `parse` knows the grammar and produces plain data; `toDom` produces nodes and
 * is the only code here that knows a DOM exists. That split is what lets a
 * second emitter -- a terminal -- reuse the grammar rather than reimplement it,
 * and it also narrows the claim above: markup in the source reaches a text node
 * because `toDom` has exactly two places that build one, rather than because a
 * grammar spread across six arms never slipped.
 *
 * <h2>Where it is deliberately not CommonMark</h2>
 *
 * **No underscore emphasis, at all.** Not an omission -- a decision this
 * corpus forces. The agents' prose names their tools constantly, and
 * `file_read and file_glob send you to file_roots` has three underscores in
 * it; any pair rule over `_` turns the run between two of them into emphasis
 * and eats the underscores out of a name the reader is meant to be able to
 * retype. `*` and `**` are supported and `_` and `__` are literal characters,
 * which is the trade that loses a mark nobody writes to keep a name everybody
 * does.
 *
 * **A mark with whitespace against it opens nothing**, so `5 * 3 * 2` is
 * arithmetic rather than an emphasised `3`, and **an unclosed mark is text**,
 * so a truncated answer -- a `TURN_CAP` landing mid-fence -- shows the rest of
 * what it managed to say instead of swallowing it into a code block. Both are
 * the same principle: a renderer that guesses deletes characters out of
 * somebody else's answer, and showing the mark is the smaller error.
 *
 * **Headings clamp to `h3`.** The shell owns `h1` and the screens own `h2`; an
 * answer's `#` is not this page's title, and emitting one would put a model's
 * wording above the console's own in every outline and every screen reader.
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
 * `link` keeps its address as an address. Flattening it to text is the DOM
 * emitter's choice — see `spansToDom` and the class doc — and a parse that had
 * already flattened would leave a second emitter nothing to decide with.
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
 * exists to close.
 */
export interface Item {
  readonly tight: boolean;
  readonly blocks: readonly Block[];
}

/**
 * One block of a document, as data.
 *
 * `heading.level` is the level that was **written**, 1 through 6. A browser
 * emitter clamps it, because this page owns h1 and h2; a terminal has no such
 * constraint. Deciding here would decide for both.
 *
 * A list item is an `Item`, never a bare run of spans, so that nested content
 * needs no second shape. `itemToDom` unwraps a tight item's lone paragraph,
 * which is what keeps a one-line item free of a paragraph's margins.
 */
export type Block =
  | { readonly kind: 'para'; readonly spans: readonly Span[] }
  | {
      readonly kind: 'heading';
      readonly level: number;
      readonly spans: readonly Span[];
    }
  | { readonly kind: 'code'; readonly text: string }
  | { readonly kind: 'quote'; readonly blocks: readonly Block[] }
  | { readonly kind: 'rule' }
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
 *  renumbered from 1 would be this console rewriting which step somebody was
 *  told to take. The digits round-trip through `Number` and `String`, so a
 *  leading zero is not -- `007.` starts at 7, which browsers render the same
 *  as `007` in a `start` attribute either way. */
const ORDERED = /^( {0,3})(\d{1,9})[.)][ \t]+(.*)$/;

/** One run of prose, as data. The grammar lives here and nothing else does. */
/**
 * Code, as the characters a shell would actually receive.
 *
 * <h3>Measured end to end, and the model is where it comes from</h3>
 *
 * <p>The model this deployment runs emits U+202F NARROW NO-BREAK SPACE between
 * the words of a keyword phrase — `descending if\u202fDESC\u202fis specified`
 * — in about one answer in eight, asked the same question eight times straight
 * at the provider with no Plowshare in the path. It is valid UTF-8, category
 * Zs, and it carries intent: <i>do not break DESC away from its neighbours</i>.
 *
 * <h3>Prose keeps it. Code does not.</h3>
 *
 * <p>In prose it does real work and is left alone. Inside a code span it is
 * wrong whatever it meant, because a code span exists to be copied: `col\u202f
 * DESC;` pasted into psql is a syntax error whose cause is invisible on the
 * screen. Only substitutions that <b>cannot be seen</b> are removed — the
 * spaces that are not spaces, the hyphen that is not a hyphen, the widths that
 * are not widths. Curly quotes and dashes stay: they are visible, a reader can
 * judge them, and some of them are meant.
 *
 * <h3>This is the second copy of this function</h3>
 *
 * <p>`plowshare-tui/src/logic/markdown.ts` has the other, and the two
 * directories hold two copies of this whole grammar on purpose: the client
 * spec's ruling is that sharing runs outward from the TUI once it is finished,
 * not inward while it is being built. <b>So this is the same bug fixed twice,
 * and that is the cost of the ruling rather than an oversight.</b> Whoever
 * unifies them should expect to find exactly one function here that is not in
 * the other, and it is this one's test group.
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

/** An element holding `text` verbatim, with no parse step of any kind. */
function textNode(tag: string, text: string): HTMLElement {
  const node = document.createElement(tag);
  node.textContent = text;
  return node;
}

/**
 * Spans as nodes.
 *
 * A link is flattened here to its label followed by its address in
 * parentheses, which is where that decision belongs: the parse recorded a
 * link, and this emitter has decided how a browser shows one.
 */
function spansToDom(spans: readonly Span[]): Node[] {
  const nodes: Node[] = [];
  for (const span of spans) {
    if (span.kind === 'text') {
      nodes.push(document.createTextNode(span.text));
    } else if (span.kind === 'code') {
      nodes.push(textNode('code', span.text));
    } else if (span.kind === 'strong' || span.kind === 'em') {
      const node = document.createElement(span.kind);
      node.append(...spansToDom(span.spans));
      nodes.push(node);
    } else {
      nodes.push(...spansToDom(span.label));
      if (span.address !== '') {
        nodes.push(document.createTextNode(` (${span.address})`));
      }
    }
  }
  return nodes;
}

/**
 * The line after the fence opened at `at` closes, or `null` if nothing does.
 *
 * Asked in two places and answered once: the block loop needs it to decide
 * whether to build a `pre`, and the paragraph collector needs the same answer
 * to decide whether the fence line ends the paragraph it is in. A fence nobody
 * closed is not a block, and both questions have to agree about that or a
 * truncated answer renders with a paragraph break through the middle of it.
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
 * existing choice rather than CommonMark's: `- # x` renders the hash. Reading
 * it as blocks would strip the marker, so the branch is kept here rather than
 * left to `itemToDom` to infer from the shape of what came back. `tight`
 * records which branch ran, because a wrapped item that happens to parse to
 * a single paragraph is not the same source as a one-line item -- see `Item`.
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
      blocks.push({
        kind: 'code',
        text: pasteable(lines.slice(at + 1, closes).join('\n')),
      });
      at = closes + 1;
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

/**
 * Blocks as nodes.
 *
 * `toDom` and the helpers below it are this module's emitter -- the only code
 * here that touches the DOM. `parse`, above, and everything it calls build no
 * nodes at all, which is what keeps the no-markup guarantee checkable in one
 * place rather than spread through a grammar.
 */
export function toDom(blocks: readonly Block[]): DocumentFragment {
  const fragment = document.createDocumentFragment();
  for (const block of blocks) {
    fragment.append(blockToDom(block));
  }
  return fragment;
}

function blockToDom(block: Block): HTMLElement {
  switch (block.kind) {
    case 'para':
      return spanned('p', block.spans);
    case 'heading':
      // The clamp lives here and not in the grammar: the shell owns h1
      // and the screens own h2, which is a fact about this page.
      return spanned(`h${Math.min(6, block.level + 2)}`, block.spans);
    case 'code':
      return textNode('pre', block.text);
    case 'rule':
      return document.createElement('hr');
    case 'quote': {
      const node = document.createElement('blockquote');
      node.append(toDom(block.blocks));
      return node;
    }
    case 'list': {
      const node = document.createElement(block.ordered ? 'ol' : 'ul');
      if (block.ordered) {
        node.setAttribute('start', String(block.start));
      }
      for (const entry of block.items) {
        node.append(itemToDom(entry));
      }
      return node;
    }
  }
}

/** An element whose children are these spans. */
function spanned(tag: string, spans: readonly Span[]): HTMLElement {
  const node = document.createElement(tag);
  node.append(...spansToDom(spans));
  return node;
}

/**
 * One item.
 *
 * A tight item is unwrapped: a one-line item wrapped in a `p` carries a
 * paragraph's margins and reads as a loose list nobody wrote. The `tight` bit
 * comes from the source rather than from the block shape -- see `Item`.
 */
function itemToDom(entry: Item): HTMLElement {
  const node = document.createElement('li');
  const only = entry.blocks.length === 1 ? (entry.blocks[0] as Block) : null;
  if (entry.tight && only !== null && only.kind === 'para') {
    node.append(...spansToDom(only.spans));
  } else {
    node.append(toDom(entry.blocks));
  }
  return node;
}

/** The text, as the blocks it spells. */
export function parse(text: string): Block[] {
  return blocksOf(text.split('\n'));
}

/** `text` as nodes, ready to append. Parse then emit, and nothing between. */
export function markdown(text: string): DocumentFragment {
  return toDom(parse(text));
}
