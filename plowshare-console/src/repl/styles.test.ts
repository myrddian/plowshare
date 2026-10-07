import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { STYLES } from './styles';

/**
 * The stylesheet, read the way a browser reads it rather than the way a
 * reviewer does.
 *
 * <h2>Why this file exists, in one sentence</h2>
 *
 * A branch added six selectors that were character-identical to six rules
 * further down the same sheet, at equal specificity. Every one of them lost.
 * `--bound` and `--cite` were declared, documented, committed, and painted
 * nothing; two of the four type sizes were set and then wiped by a `font`
 * shorthand later in the file; a token was declared and referenced nowhere at
 * all. **Three suites were green through all of it**, because nothing on this
 * project had ever parsed this string — the console's tests build DOM and
 * assert on text, and a stylesheet is not DOM.
 *
 * That is the gap. Every defect above is invisible to a reader (the two rules
 * are four hundred lines apart) and obvious to a browser (it resolves the
 * cascade in microseconds). So this file is the browser's half of the review.
 *
 * <h2>What these assertions are, and are not</h2>
 *
 * They are structural and never aesthetic. Nothing here says a colour is right
 * or a size is pleasant — those are a person's to judge, and a test that
 * pinned them would break every time somebody improved one. What they say is
 * that the sheet means what it says it means:
 *
 * - a value added to one scheme is added to both, or half the machines that
 *   open this page render the other scheme's;
 * - one selector states one property once, so which rule wins is not decided
 *   by where in the file somebody happened to paste it;
 * - a token declared is a token used, and a token used is a token declared;
 * - a size survives the shorthand that would otherwise reset it.
 */

/** One rule as this sheet holds it: where it is, what it selects, what it sets. */
interface Rule {
  /** Its position in the sheet. Later wins, which is the whole subject here. */
  readonly at: number;
  /** The at-rule it sits inside, or null at the top level. */
  readonly media: string | null;
  readonly selectors: readonly string[];
  /** Property names in the order they are declared, values discarded. */
  readonly properties: readonly string[];
}

/**
 * Enough of a CSS parser for the questions below, and deliberately no more.
 *
 * It reads property *names* and never values, so nothing here can drift into
 * asserting a colour. Comments go first because this sheet's comments contain
 * selectors and property names as prose, and one of them naming a rule is not
 * that rule.
 */
function parse(css: string): readonly Rule[] {
  const source = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const rules: Rule[] = [];
  let media: string | null = null;
  let mediaEnds = -1;
  let at = 0;
  while (at < source.length) {
    if (mediaEnds >= 0 && at >= mediaEnds) {
      media = null;
      mediaEnds = -1;
    }
    const opens = source.indexOf('{', at);
    if (opens < 0) {
      break;
    }
    // Whitespace after an at-rule can put its closing brace in the next
    // selector's prefix. Determine scope from the next opening brace too.
    if (mediaEnds >= 0 && opens > mediaEnds) {
      media = null;
      mediaEnds = -1;
    }
    // A `}` between here and the brace closed a block; it is never part of
    // a selector, so it is whitespace as far as this is concerned.
    const head = source.slice(at, opens).replace(/}/g, ' ').trim();
    if (head.startsWith('@')) {
      media = head;
      mediaEnds = closeOf(source, opens);
      at = opens + 1;
      continue;
    }
    const closes = source.indexOf('}', opens);
    rules.push({
      at: rules.length,
      media,
      selectors: head
        .split(',')
        .map((one) => one.trim().replace(/\s+/g, ' '))
        .filter((one) => one !== ''),
      properties: source
        .slice(opens + 1, closes)
        .split(';')
        .map((one) => one.trim())
        .filter((one) => one.includes(':'))
        .map((one) => one.slice(0, one.indexOf(':')).trim()),
    });
    at = closes + 1;
  }
  return rules;
}

/** Where the block opened at `opens` closes, counting nesting. */
function closeOf(source: string, opens: number): number {
  let depth = 0;
  for (let at = opens; at < source.length; at += 1) {
    if (source[at] === '{') {
      depth += 1;
    } else if (source[at] === '}') {
      depth -= 1;
      if (depth === 0) {
        return at;
      }
    }
  }
  return source.length;
}

const RULES = parse(STYLES);
const PRINT_RULES = parse(
  readFileSync(
    join(
      dirname(fileURLToPath(import.meta.url)),
      '../../../client-assets/themes/print.css',
    ),
    'utf8',
  ),
);

/** The custom properties one block declares, in declaration order. */
function tokensOf(rule: Rule): readonly string[] {
  return rule.properties.filter((one) => one.startsWith('--'));
}

describe('the two schemes', () => {
  /**
   * The rule the palette's own comment states: "every property here is
   * declared twice, once per scheme". A value added to one block only is
   * invisible to a reviewer and renders as the wrong scheme's on half the
   * machines that open the page.
   *
   * Custom properties and not every property: `color-scheme` is declared once
   * on purpose — it is the declaration that tells the browser both schemes
   * exist, and it is not itself scheme-dependent.
   */
  it('declare the same tokens, so a value cannot be added to one of them', () => {
    const roots = RULES.filter((rule) => rule.selectors.includes(':root'));

    expect(roots).toHaveLength(2);
    expect(roots[0]?.media).toBeNull();
    expect(roots[1]?.media).toContain('prefers-color-scheme: dark');
    expect(tokensOf(roots[1] as Rule)).toEqual(tokensOf(roots[0] as Rule));
  });
});

describe('the cascade', () => {
  /**
   * **The assertion this file exists for.**
   *
   * Six selectors were added at one point in this sheet and six identical
   * ones already stood four hundred lines below. Same selector, same
   * property, same specificity, later one wins — so the six new rules were
   * dead the moment they were written, and the commit that added them said
   * they had changed what the console draws.
   *
   * A duplicate is never how a rule should win. If a later rule is meant to
   * take over, the earlier one is what should have been edited; if it is not,
   * one of them is dead. Either way one selector states one property once,
   * and the same-key comparison below is what makes that checkable rather
   * than a habit somebody has to keep.
   *
   * Keyed by at-rule too, so `:root` inside the dark block redeclaring every
   * token — which is the point of that block — is not a collision.
   */
  it('never asks one selector the same question twice', () => {
    const said = new Map<string, number>();
    const twice: string[] = [];
    for (const rule of RULES) {
      for (const selector of rule.selectors) {
        for (const property of rule.properties) {
          const key = `${rule.media ?? ''} | ${selector} | ${property}`;
          if (said.has(key)) {
            twice.push(
              `${key} — also at rule ${said.get(key)}, now at ${rule.at}`,
            );
          }
          said.set(key, rule.at);
        }
      }
    }

    expect(twice).toEqual([]);
  });

  /**
   * The same defect wearing a different hat, and the one a same-property
   * comparison cannot see: `font` is a shorthand and it resets `font-size`.
   * A rule setting the size and a later rule setting `font: inherit` for the
   * same selector are not the same property, so nothing above catches them —
   * and the size is gone all the same.
   *
   * `.picker .project-open` is the pattern that gets it right and the reason
   * this is expressible as a rule: put the size *after* the shorthand, in the
   * same declaration block, and the shorthand has nothing left to reset.
   */
  it('never lets a font shorthand wipe a size the same selector asked for', () => {
    const wiped: string[] = [];
    const seen = new Map<string, { font: number; size: number }>();
    for (const rule of RULES) {
      for (const selector of rule.selectors) {
        const key = `${rule.media ?? ''} | ${selector}`;
        const held = seen.get(key) ?? { font: -1, size: -1 };
        rule.properties.forEach((property, order) => {
          // Rule position dominates declaration order within a block,
          // which is exactly how a browser resolves the two.
          const at = rule.at * 1000 + order;
          if (property === 'font') {
            held.font = at;
          } else if (property === 'font-size') {
            held.size = at;
          }
        });
        seen.set(key, held);
      }
    }
    for (const [key, held] of seen) {
      if (held.size >= 0 && held.font > held.size) {
        wiped.push(key);
      }
    }

    expect(wiped).toEqual([]);
  });
});

describe('the tokens', () => {
  /**
   * A token declared and never referenced is a name for something that does
   * not happen. Two were: `--step-2` was a size nothing was drawn at, and
   * `--plain` was a weight nothing asked for — and `--bound` and `--cite`
   * were referenced only by rules the cascade had already killed, which is
   * the same thing one step removed.
   *
   * This is the check that says the palette's key is the truth about the
   * page rather than a description of an intention.
   */
  it('are every one of them used', () => {
    const light = RULES.find(
      (rule) => rule.selectors.includes(':root') && rule.media === null,
    ) as Rule;
    const used = new Set(
      [...STYLES.matchAll(/var\((--[\w-]+)\)/g)].map((hit) => hit[1]),
    );

    expect([...tokensOf(light)].filter((token) => !used.has(token))).toEqual(
      [],
    );
  });

  /**
   * And the other direction: shared print assets declare the appearance tokens;
   * three custom
   * properties are set on an element by `trajectory.ts` and read by a rule
   * here, which is this sheet's own instruction for how a value reaches a
   * rule. Naming them holds that list to three — a fourth would be either a
   * typo in a `var()` or a token somebody forgot to declare, and both render
   * as nothing at all.
   */
  it('are declared by the application or shared palette, except the three set by script', () => {
    const declared = new Set(
      [...RULES, ...PRINT_RULES].flatMap((rule) => tokensOf(rule)),
    );
    const used = [
      ...new Set(
        [...STYLES.matchAll(/var\((--[\w-]+)\)/g)].map(
          (hit) => hit[1] as string,
        ),
      ),
    ];

    expect(used.filter((token) => !declared.has(token)).sort()).toEqual([
      '--at',
      '--fill',
      '--for',
    ]);
  });
});

describe('the scrollback', () => {
  /**
   * The clamp is what keeps one enormous body from making the scrollback
   * unreadable, and `render.ts` now puts two different elements behind it: a
   * `pre` for text shown verbatim, and a `div` for an agent's answer read as
   * the markdown it was written in. A selector naming the tag clamps one of
   * them, and the one it stops clamping is the one most likely to be long.
   */
  it('clamps a long body whatever element it was rendered into', () => {
    const clamps = RULES.filter((rule) =>
      rule.selectors.some((one) => one.includes('data-clamped')),
    );

    expect(clamps.length).toBeGreaterThan(0);
    expect(
      clamps
        .flatMap((rule) => rule.selectors)
        .filter((one) => /\bpre\b/.test(one)),
    ).toEqual([]);
  });
});
