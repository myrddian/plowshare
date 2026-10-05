import { describe, expect, it } from 'vitest';
import {
  type Block,
  type Item,
  parse,
  type Span,
  spansOf,
} from './markdown.ts';

/**
 * The grammar's suite, carried over with the grammar.
 *
 * <b>THESE TESTS WERE PORTED, NOT WRITTEN.</b> They are
 * `plowshare-console/src/repl/markdown.test.ts` minus its one `toDom` group,
 * which stayed behind with the emitter it asserts over. The port is not
 * optional and not a nicety: a copy that arrives without its tests is the
 * weaker of the two copies, nobody ever adopts it, and the temporary fork spec
 * §2 describes quietly becomes a permanent one. Forty-eight cases travelled and
 * one did not.
 *
 * <b>WHAT CHANGED ON THE WAY OVER, AND IT IS EVERY CASE THAT USED TO SAY
 * `querySelector`.</b> The console mounts a fragment and asks the rendered tree
 * questions — is there an `em`, what is this `li`'s `textContent`, does the
 * `ol` carry `start="3"`. There is no tree here and there must not be one, so
 * those became assertions on `Block[]` and `Span[]` directly, which is the
 * shape the `spans as data` and `blocks as data` groups below were already
 * written in and the shape the parse/emit split was made for. The helpers under
 * this comment are the translation: `named` is `querySelector`, `said` is
 * `textContent`, `kinds` is the list of tag names.
 *
 * <b>Two DOM-shaped assertions have no data-shaped equivalent and are recorded
 * here rather than dropped in silence</b>, because a case that vanishes in a
 * port is indistinguishable from a case that was never there:
 *
 *   1. <b>The heading clamp.</b> `blocks('# one') === ['h3']` asserts
 *      `Math.min(6, level + 2)`, which lives in `toDom` because the console's
 *      shell owns `h1` and its screens own `h2`. The grammar records the level
 *      that was written and clamps nothing, so what ports is the six levels
 *      round-tripping; the clamp stays with the emitter that performs it, and
 *      this module's own emitter will need its own case for whatever a terminal
 *      decides a heading looks like.
 *   2. <b>"not as an element".</b> `querySelector('img')` being null, and the
 *      text not containing `&lt;`, are claims about a DOM that was built and
 *      about entity encoding that did not happen. This data model has no
 *      elements and no encoding step to get wrong. What survives is the real
 *      grammar half — markup in the source is a run of characters this file has
 *      no rule for, so it arrives in a text span byte for byte — which is also
 *      the premise the console's stronger claim rests on.
 *
 * A third is a softening rather than a loss: the link group's
 * `textContent === 'see the paper (https://example.org/p.pdf)'` asserts the
 * browser emitter's flattening. The parse keeps label and address apart, so
 * what is asserted here is that separation, which is the thing the flattening
 * is downstream of.
 */

/** The kinds of the document's top-level blocks, in order: the old tag names. */
function kinds(text: string): string[] {
  return parse(text).map((block) => block.kind);
}

/** Every block anywhere in the document, quotes and items walked into. */
function everyBlock(blocks: readonly Block[]): Block[] {
  return blocks.flatMap((block) => {
    if (block.kind === 'quote') {
      return [block, ...everyBlock(block.blocks)];
    }
    if (block.kind === 'list') {
      return [
        block,
        ...block.items.flatMap((entry) => everyBlock(entry.blocks)),
      ];
    }
    return [block];
  });
}

/** Every span anywhere in a run, nesting walked into. */
function everySpan(spans: readonly Span[]): Span[] {
  return spans.flatMap((span) => {
    if (span.kind === 'strong' || span.kind === 'em') {
      return [span, ...everySpan(span.spans)];
    }
    if (span.kind === 'link') {
      return [span, ...everySpan(span.label)];
    }
    return [span];
  });
}

/** Every span of one kind, anywhere in the document: `querySelector`, as data. */
function named(text: string, kind: Span['kind']): Span[] {
  return everyBlock(parse(text))
    .flatMap((block) =>
      block.kind === 'para' || block.kind === 'heading'
        ? everySpan(block.spans)
        : [],
    )
    .filter((span) => span.kind === kind);
}

/** What a run of spans says once the marks themselves are gone. */
function textOf(spans: readonly Span[]): string {
  return spans
    .map((span) => {
      if (span.kind === 'text' || span.kind === 'code') {
        return span.text;
      }
      if (span.kind === 'link') {
        // The label only. Whether the address is shown, and how, is an
        // emitter's decision -- see the `links` group.
        return textOf(span.label);
      }
      return textOf(span.spans);
    })
    .join('');
}

/** Everything the document says, run together: `textContent`, as data. */
function said(text: string): string {
  return everyBlock(parse(text))
    .map((block) => {
      if (block.kind === 'para' || block.kind === 'heading') {
        return textOf(block.spans);
      }
      if (block.kind === 'code') {
        return block.text;
      }
      return '';
    })
    .join('');
}

/** The items of the document's first block, when that block is a list. */
function itemsOf(text: string): readonly Item[] {
  const first = parse(text)[0];
  return first?.kind === 'list' ? first.items : [];
}

/** What one item says, its blocks run together. */
function itemText(entry: Item): string {
  return entry.blocks
    .map((block) => (block.kind === 'para' ? textOf(block.spans) : ''))
    .join('');
}

describe('paragraphs', () => {
  it('records prose as a paragraph rather than as preformatted text', () => {
    expect(kinds('a sentence about a document')).toEqual(['para']);
    expect(said('a sentence about a document')).toBe(
      'a sentence about a document',
    );
  });

  it('starts a new paragraph at a blank line', () => {
    expect(kinds('first\n\nsecond')).toEqual(['para', 'para']);
  });

  it('keeps a soft line break inside the paragraph it was written in', () => {
    // The pre this replaces showed every newline, and an answer whose lines
    // were reflowed into one river would read as a different answer. The
    // break is kept in the text; each emitter draws it its own way.
    expect(kinds('one line\nthe next line')).toEqual(['para']);
    expect(said('one line\nthe next line')).toBe('one line\nthe next line');
  });

  it('records nothing at all for nothing at all', () => {
    expect(kinds('')).toEqual([]);
    expect(said('')).toBe('');
  });
});

describe('inline marks', () => {
  it('records a double-asterisk run as strong', () => {
    expect(named('the **whole point** of it', 'strong')).toEqual([
      { kind: 'strong', spans: [{ kind: 'text', text: 'whole point' }] },
    ]);
    expect(said('the **whole point** of it')).toBe('the whole point of it');
  });

  it('records a single-asterisk run as emphasis', () => {
    expect(named('read it *before* answering', 'em')).toEqual([
      { kind: 'em', spans: [{ kind: 'text', text: 'before' }] },
    ]);
    expect(named('read it *before* answering', 'strong')).toEqual([]);
  });

  it('records a backtick run as code, and leaves the markers inside it alone', () => {
    // Code first in the pass order, so a name with an asterisk or an
    // underscore in it survives being quoted.
    const source = 'call `document_ask` with **that**';
    expect(named(source, 'code')).toEqual([
      { kind: 'code', text: 'document_ask' },
    ]);
    expect(named(source, 'strong')).toEqual([
      { kind: 'strong', spans: [{ kind: 'text', text: 'that' }] },
    ]);
  });

  it('does not read emphasis inside a code span', () => {
    expect(named('`a *b* c`', 'em')).toEqual([]);
    expect(named('`a *b* c`', 'code')).toEqual([
      { kind: 'code', text: 'a *b* c' },
    ]);
  });

  it('leaves an asterisk that marks nothing exactly as it was typed', () => {
    // Arithmetic, a footnote marker, a bullet quoted mid-sentence. A
    // renderer that guessed here would eat characters out of somebody's
    // answer, which is worse than showing the mark.
    expect(said('5 * 3 * 2 is 30')).toBe('5 * 3 * 2 is 30');
    expect(named('5 * 3 * 2 is 30', 'em')).toEqual([]);
  });

  it('leaves snake_case names alone, because this corpus is full of them', () => {
    // THE REASON UNDERSCORE EMPHASIS IS NOT SUPPORTED AT ALL, and the one
    // behaviour this port was told to preserve on purpose. The shipped
    // agents' prose names its tools constantly -- file_read, memory_recall,
    // plowshare_memory_write -- and a pair rule over underscores turns the
    // run between two of them into emphasis, silently deleting the
    // underscores from a tool name a person is meant to be able to retype.
    // A terminal emitter would get this wrong exactly as often as a browser
    // one, so the rule travelled with the grammar rather than being
    // rethought at the new emitter.
    const source = 'file_read and file_glob send you to file_roots';
    expect(said(source)).toBe(source);
    expect(named(source, 'em')).toEqual([]);
    expect(said('__not bold__')).toBe('__not bold__');
    expect(named('__not bold__', 'strong')).toEqual([]);
  });

  it('does not open emphasis on a mark with a space against it', () => {
    expect(named('a ** b ** c', 'strong')).toEqual([]);
    expect(said('a ** b ** c')).toBe('a ** b ** c');
  });

  it('leaves an unclosed mark as text', () => {
    expect(said('an **unfinished thought')).toBe('an **unfinished thought');
    expect(said('an `unfinished quote')).toBe('an `unfinished quote');
  });
});

describe('headings', () => {
  it('records every level that can be written, 1 through 6', () => {
    // THE CONSOLE'S CASE ASSERTED THE CLAMP -- `# one` rendering as an h3,
    // because its shell owns h1 and its screens own h2. That is a fact
    // about a page's outline and it lives in `toDom`, so it did not travel;
    // see this file's header. What travels is the half that is grammar: the
    // level written is the level recorded, and any ceiling is an emitter's
    // to impose.
    const levels = [
      '# a',
      '## a',
      '### a',
      '#### a',
      '##### a',
      '###### a',
    ].flatMap((source) =>
      parse(source).map((block) =>
        block.kind === 'heading' ? block.level : 0,
      ),
    );
    expect(levels).toEqual([1, 2, 3, 4, 5, 6]);
  });

  it('reads the marks in a heading like any other prose', () => {
    expect(named('## what `document_ask` answers', 'code')).toEqual([
      { kind: 'code', text: 'document_ask' },
    ]);
  });

  it('needs a space, so a hash against a word is not a heading', () => {
    expect(kinds('#1 on the list')).toEqual(['para']);
    expect(said('#1 on the list')).toBe('#1 on the list');
  });
});

describe('lists', () => {
  it('records a bullet list as a list, whichever bullet was typed', () => {
    for (const bullet of ['-', '*', '+']) {
      const source = `${bullet} first\n${bullet} second`;
      expect(kinds(source)).toEqual(['list']);
      expect(
        parse(source).map((block) => block.kind === 'list' && block.ordered),
      ).toEqual([false]);
      expect(itemsOf(source).map(itemText)).toEqual(['first', 'second']);
    }
  });

  it('records a numbered list as one, and starts it where it was started', () => {
    // The agents write ordered instructions constantly -- close_reader's
    // body is four numbered steps -- and a list renumbered from 1 would be
    // this client rewriting which step a person was told to take.
    const first = parse('3. third\n4. fourth')[0];
    expect(first?.kind === 'list' && first.ordered && first.start).toBe(3);
    expect(itemsOf('3. third\n4. fourth').map(itemText)).toEqual([
      'third',
      'fourth',
    ]);
  });

  it('reads the marks inside an item', () => {
    expect(named('- ask **once**', 'strong')).toEqual([
      { kind: 'strong', spans: [{ kind: 'text', text: 'once' }] },
    ]);
  });

  it('nests a list written under an item, rather than showing its bullet', () => {
    const outer = itemsOf('- outer\n  - inner');
    expect(outer.length).toBe(1);
    const nested = (outer[0] as Item).blocks.find(
      (block) => block.kind === 'list',
    );
    expect(nested?.kind === 'list' && nested.items.map(itemText)).toEqual([
      'inner',
    ]);
    expect(said('- outer\n  - inner')).not.toContain('- inner');
  });

  it('keeps a line wrapped under an item as part of that item', () => {
    const items = itemsOf('1. a step that runs\n   onto a second line');
    expect(items.length).toBe(1);
    expect(itemText(items[0] as Item)).toContain('onto a second line');
  });

  it('ends the list at the prose that follows it', () => {
    expect(kinds('- one\n- two\n\nand then a sentence')).toEqual([
      'list',
      'para',
    ]);
  });

  it('does not read a lone hyphen mid-sentence as a bullet', () => {
    expect(kinds('a sentence - with a dash in it')).toEqual(['para']);
  });

  it('reads a one-line item as prose, so a hash in it is a hash', () => {
    // Not CommonMark, and deliberately unchanged: this grammar has always
    // recorded `- # x` with the marker showing, and a port is not where
    // that gets decided.
    const items = itemsOf('- # not a heading\n- second');
    expect((items[0] as Item).blocks.map((block) => block.kind)).toEqual([
      'para',
    ]);
    expect(itemText(items[0] as Item)).toBe('# not a heading');
  });

  it('reads a one-line item as prose, so an angle bracket in it is text', () => {
    const items = itemsOf('- > quoted\n- second');
    expect((items[0] as Item).blocks.map((block) => block.kind)).toEqual([
      'para',
    ]);
    expect(itemText(items[0] as Item)).toBe('> quoted');
  });

  it('keeps a wrapped item loose, the way the source wrote it', () => {
    // THE CONSOLE ASKED THIS AS `li > p`, which is the emitter's way of
    // asking about `tight` -- and `tight` is the field that exists because
    // the shape of `blocks` cannot answer it. Both of these items parse to
    // a single paragraph and only one of them is loose, so the data-shaped
    // assertion is the more direct of the two rather than a weaker stand-in.
    const items = itemsOf('- a step that runs\n  onto a second line');
    expect((items[0] as Item).tight).toBe(false);
    expect(itemText(items[0] as Item)).toBe(
      'a step that runs\nonto a second line',
    );
  });

  it('keeps a one-line item tight', () => {
    const items = itemsOf('- one line');
    expect((items[0] as Item).tight).toBe(true);
    expect(itemText(items[0] as Item)).toBe('one line');
  });
});

describe('quotes and rules', () => {
  it('records a quoted block as a quote, with its own blocks inside', () => {
    expect(parse('> what the paragraph said\n> across two lines')).toEqual([
      {
        kind: 'quote',
        blocks: [
          {
            kind: 'para',
            spans: [
              {
                kind: 'text',
                text: 'what the paragraph said\nacross two lines',
              },
            ],
          },
        ],
      },
    ]);
  });

  it('records a horizontal rule', () => {
    expect(kinds('above\n\n---\n\nbelow')).toEqual(['para', 'rule', 'para']);
  });
});

describe('fenced code', () => {
  it('records a fence verbatim, marks and all', () => {
    const source = '```\nif (a * b) { return `x` }\n```';
    expect(kinds(source)).toEqual(['code']);
    expect(parse(source)).toEqual([
      { kind: 'code', text: 'if (a * b) { return `x` }' },
    ]);
    expect(named(source, 'em')).toEqual([]);
    expect(named(source, 'code')).toEqual([]);
  });

  it('keeps the info string as a language, and out of the code', () => {
    expect(parse('```kotlin\nval a = 1\n```')).toEqual([
      { kind: 'code', text: 'val a = 1', lang: 'kotlin' },
    ]);
    expect(said('```kotlin\nval a = 1\n```')).not.toContain('kotlin');
  });

  it('keeps only the first word of an info string', () => {
    expect(parse('```ts title="a.ts"\nx\n```')).toEqual([
      { kind: 'code', text: 'x', lang: 'ts' },
    ]);
  });

  it('leaves a fence nobody closed as the text it is', () => {
    // A truncated answer -- a TURN_CAP mid-block -- ends inside a fence, and
    // swallowing the rest of the answer into a code block would hide the
    // part a person most needs to see.
    expect(kinds('```\nstarted and cut off')).toEqual(['para']);
    expect(said('```\nstarted and cut off')).toContain('started and cut off');
  });
});

describe('links', () => {
  it('records a link as a label and an address, kept apart', () => {
    // THE CONSOLE ASSERTED THE FLATTENING -- `the paper (https://...)` and
    // no anchor in the tree -- which is what a browser emitter does with
    // this span and why: escape.ts records that a javascript: URL survives
    // escaping intact, so the defence is to have no anchor to put one in. A
    // terminal emitter will make its own decision, and the grammar's job is
    // to leave one to make.
    expect(
      spansOf('see [the paper](https://example.org/p.pdf) for it'),
    ).toEqual([
      { kind: 'text', text: 'see ' },
      {
        kind: 'link',
        label: [{ kind: 'text', text: 'the paper' }],
        address: 'https://example.org/p.pdf',
      },
      { kind: 'text', text: ' for it' },
    ]);
  });

  it('records a javascript: link as text like any other', () => {
    // It is not even a link: the address pattern carries no parentheses, so
    // `alert(1)` refuses the whole construct and the source stays the
    // characters it is. The console asserted the same thing through the
    // tree -- no anchor, and the text still saying `javascript:alert(1)`.
    const source = '[click me](javascript:alert(1))';
    expect(spansOf(source)).toEqual([{ kind: 'text', text: source }]);
    expect(said(source)).toContain('javascript:alert(1)');
  });

  it('records a label whose address is empty as a link with no address', () => {
    expect(spansOf('[a label]()')).toEqual([
      { kind: 'link', label: [{ kind: 'text', text: 'a label' }], address: '' },
    ]);
  });
});

describe('rendering is escaping', () => {
  it('lands markup in the source in a text span, byte for byte', () => {
    // THE CONSOLE'S CASE ALSO ASSERTED NO `img` AND NO `script` IN THE TREE,
    // and no `&lt;` in the text. Neither claim has a data-shaped form --
    // this model has no elements and no encoding step -- and both are
    // recorded in this file's header rather than dropped in silence. What
    // is left is the premise they rest on and the only part that is
    // grammar: markup in the source is a run of characters this file has no
    // rule for, so it lands in a text span unchanged.
    const payload = '<img src=x onerror=alert(1)><script>alert(2)</script>';
    expect(spansOf(`the file said ${payload}`)).toEqual([
      { kind: 'text', text: `the file said ${payload}` },
    ]);
    expect(said(`the file said ${payload}`)).toContain(payload);
  });

  it('lands markup inside a fence as text too', () => {
    expect(parse('```\n<script>alert(1)</script>\n```')).toEqual([
      { kind: 'code', text: '<script>alert(1)</script>' },
    ]);
  });
});

describe('spans as data', () => {
  it('records a code span rather than building an element', () => {
    expect(spansOf('call `document_ask` now')).toEqual([
      { kind: 'text', text: 'call ' },
      { kind: 'code', text: 'document_ask' },
      { kind: 'text', text: ' now' },
    ]);
  });

  it('nests the marks inside a strong run', () => {
    expect(spansOf('**do `this`**')).toEqual([
      {
        kind: 'strong',
        spans: [
          { kind: 'text', text: 'do ' },
          { kind: 'code', text: 'this' },
        ],
      },
    ]);
  });

  it('records a link with its address, leaving the flattening to an emitter', () => {
    // The parse keeps the address as an address. An emitter that renders it
    // as text is one choice of several, and a parse that had already
    // flattened would have thrown away the only structured copy.
    expect(spansOf('see [the paper](https://example.org/p.pdf)')).toEqual([
      { kind: 'text', text: 'see ' },
      {
        kind: 'link',
        label: [{ kind: 'text', text: 'the paper' }],
        address: 'https://example.org/p.pdf',
      },
    ]);
  });

  it('records text that carries no marks as one span', () => {
    expect(spansOf('file_read and file_glob')).toEqual([
      { kind: 'text', text: 'file_read and file_glob' },
    ]);
  });
});

describe('blocks as data', () => {
  it('records a heading at the level written, leaving the clamp to an emitter', () => {
    // The console's page owns h1 and h2, so its emitter clamps. That is a
    // fact about that page's outline; a terminal has no such constraint,
    // and a parse that clamped would have decided for both.
    expect(parse('# one')).toEqual([
      { kind: 'heading', level: 1, spans: [{ kind: 'text', text: 'one' }] },
    ]);
    expect(parse('###### six')).toEqual([
      { kind: 'heading', level: 6, spans: [{ kind: 'text', text: 'six' }] },
    ]);
  });

  it('records a paragraph with its soft line break intact', () => {
    expect(parse('one line\nthe next')).toEqual([
      { kind: 'para', spans: [{ kind: 'text', text: 'one line\nthe next' }] },
    ]);
  });

  it('records a fence verbatim, with no spans read inside it', () => {
    expect(parse('```kotlin\nval a = 1 * 2\n```')).toEqual([
      { kind: 'code', text: 'val a = 1 * 2', lang: 'kotlin' },
    ]);
  });

  it('records every list item as blocks, even a one-line one', () => {
    expect(parse('- first\n- second')).toEqual([
      {
        kind: 'list',
        ordered: false,
        items: [
          {
            tight: true,
            blocks: [
              { kind: 'para', spans: [{ kind: 'text', text: 'first' }] },
            ],
          },
          {
            tight: true,
            blocks: [
              { kind: 'para', spans: [{ kind: 'text', text: 'second' }] },
            ],
          },
        ],
      },
    ]);
  });

  it('keeps the number a numbered list started at', () => {
    expect(parse('3. third')).toEqual([
      {
        kind: 'list',
        ordered: true,
        start: 3,
        items: [
          {
            tight: true,
            blocks: [
              { kind: 'para', spans: [{ kind: 'text', text: 'third' }] },
            ],
          },
        ],
      },
    ]);
  });

  it('records a quote as blocks inside a quote', () => {
    expect(parse('> what it said')).toEqual([
      {
        kind: 'quote',
        blocks: [
          { kind: 'para', spans: [{ kind: 'text', text: 'what it said' }] },
        ],
      },
    ]);
  });

  it('records a rule', () => {
    expect(parse('---')).toEqual([{ kind: 'rule' }]);
  });

  it('records nothing for nothing', () => {
    expect(parse('')).toEqual([]);
  });
});

describe('code is what somebody pastes', () => {
  // MEASURED, END TO END, BEFORE ANY OF THIS WAS WRITTEN.
  //
  // The model this deployment runs emits U+202F NARROW NO-BREAK SPACE between
  // the words of a keyword phrase — `descending if\u202fDESC\u202fis
  // specified` — about one answer in eight, asked the same question eight
  // times with no Plowshare in the path at all. It is valid UTF-8, it is
  // category Zs, and it is deliberate: it is the model saying "do not break
  // DESC away from its neighbours".
  //
  // <b>And in prose it works, so prose is left alone.</b> `string-width`
  // measures it as one column, exactly like a space, and `wrap-ansi` honours
  // the non-break — given 44 columns it keeps `if DESC is` whole and breaks
  // earlier, where plain spaces break between `if` and `DESC`. Nothing is
  // wrong there and normalising it would destroy working typography.
  //
  // <b>Inside code it is wrong whatever it meant.</b> A code span exists to
  // be copied into a shell, and `col\u202fDESC` pasted into psql is a syntax
  // error whose cause is invisible on the screen. Line breaks inside code are
  // the terminal's business; the characters are not.

  it('turns a narrow no-break space inside inline code into a space', () => {
    const [block] = parse('Try `ORDER BY col\u202fDESC;` next');
    const code =
      block?.kind === 'para'
        ? block.spans.find((span) => span.kind === 'code')
        : undefined;
    expect(code?.kind === 'code' ? code.text : '').toBe('ORDER BY col DESC;');
  });

  it('leaves the very same character alone in prose', () => {
    // BOTH text branches, deliberately. `spansOf` pushes plain text in two
    // places — before an inline match and after the last one — and a first
    // version of this case used a line with no inline markup at all, so it
    // only ever reached the second. Normalising prose in the first was
    // mutated in and every test still passed. One line now runs both.
    const [block] = parse(
      'if\u202fDESC\u202fis `code` then\u202fASC\u202fis not',
    );
    const prose =
      block?.kind === 'para'
        ? block.spans.filter((span) => span.kind === 'text')
        : [];
    expect(prose).toHaveLength(2);
    for (const span of prose) {
      expect(span.kind === 'text' ? span.text : '').toContain('\u202f');
    }
  });

  it('cleans a fenced block, which is the one people actually paste', () => {
    const [block] = parse('```sql\nSELECT *\nORDER BY col\u202fDESC;\n```');
    expect(block?.kind === 'code' ? block.text : '').toBe(
      'SELECT *\nORDER BY col DESC;',
    );
  });

  it('turns a non-breaking hyphen inside code into a plain one', () => {
    // The other invisible substitution: `--flag` written with U+2011 looks
    // identical and is not the same flag.
    const [block] = parse('```\nls \u2011\u2011color\n```');
    expect(block?.kind === 'code' ? block.text : '').toBe('ls --color');
  });

  it('drops a zero-width space inside code rather than spacing it out', () => {
    const [block] = parse('`a\u200bb`');
    const code =
      block?.kind === 'para'
        ? block.spans.find((span) => span.kind === 'code')
        : undefined;
    expect(code?.kind === 'code' ? code.text : '').toBe('ab');
  });

  it('leaves ordinary code entirely alone', () => {
    const [block] = parse('```\nSELECT * FROM t ORDER BY col DESC;\n```');
    expect(block?.kind === 'code' ? block.text : '').toBe(
      'SELECT * FROM t ORDER BY col DESC;',
    );
  });

  it('does not touch a curly quote, which is visible and may be meant', () => {
    // Visible differences are the reader's to judge. This only removes what
    // cannot be seen.
    const [block] = parse('`echo \u2019hi\u2019`');
    const code =
      block?.kind === 'para'
        ? block.spans.find((span) => span.kind === 'code')
        : undefined;
    expect(code?.kind === 'code' ? code.text : '').toBe('echo \u2019hi\u2019');
  });
});

describe('pipe tables', () => {
  const text = (value: string): Span[] => [{ kind: 'text', text: value }];

  it('records a header, its alignment and its rows', () => {
    expect(parse('| a | b | c |\n|:--|:-:|--:|\n| 1 | 2 | 3 |')).toEqual([
      {
        kind: 'table',
        align: ['left', 'center', 'right'],
        header: [text('a'), text('b'), text('c')],
        rows: [[text('1'), text('2'), text('3')]],
      },
    ]);
  });

  it('does without the outer pipes', () => {
    expect(kinds('a | b\n--- | ---\n1 | 2')).toEqual(['table']);
  });

  it('reads the marks inside a cell', () => {
    const [block] = parse('| x |\n|---|\n| **bold** |');
    expect(block?.kind === 'table' ? block.rows[0]?.[0] : undefined).toEqual([
      { kind: 'strong', spans: text('bold') },
    ]);
  });

  it('keeps a pipe inside a code run in its cell', () => {
    const [block] = parse('| x | y |\n|---|---|\n| `a || b` | c |');
    expect(block?.kind === 'table' ? block.rows[0] : undefined).toEqual([
      [{ kind: 'code', text: 'a || b' }],
      text('c'),
    ]);
  });

  it('pads a short row and cuts a long one to the header', () => {
    const [block] = parse('| a | b |\n|---|---|\n| 1 |\n| 1 | 2 | 3 |');
    expect(
      block?.kind === 'table' ? block.rows.map((row) => row.length) : [],
    ).toEqual([2, 2]);
  });

  it('ends at a blank line, and what follows is a paragraph', () => {
    expect(kinds('| a |\n|---|\n| 1 |\n\nafter')).toEqual(['table', 'para']);
  });

  it('ends a paragraph it interrupts', () => {
    expect(kinds('before\n| a |\n|---|')).toEqual(['para', 'table']);
  });

  it('leaves a sentence with a pipe in it as a sentence', () => {
    expect(kinds('this | that\nand more')).toEqual(['para']);
  });

  it("needs the delimiter row to have the header's columns", () => {
    expect(kinds('| a | b |\n|---|')).toEqual(['para']);
  });
});
