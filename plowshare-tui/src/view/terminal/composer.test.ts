import type { Key } from 'ink';
import { describe, expect, it } from 'vitest';

import { BOTS_COMMAND, HELP_COMMAND } from '../../logic/wording.ts';
import {
  capKeyOf,
  densityToggled,
  exploreKeyOf,
  panelToggled,
  pressing,
  questionStrokeOf,
  strokeOf,
  viewKeyOf,
} from './composer.ts';
import type { Composing } from './composer.ts';

/** No modifier and no special key: an ordinary character arriving. */
const PLAIN = {} as Key;
/** One named key pressed. */
const press = (named: Partial<Key>): Key => named as Key;

/** A composer holding `text`, with the cursor at the end unless told otherwise. */
function at(text: string, cursor = text.length): Composing {
  return { typed: text, at: cursor, offering: [] };
}

describe('typing', () => {
  it('inserts a character at the cursor, not at the end', () => {
    expect(pressing(at('ab', 1), 'X', PLAIN, [], []).typed).toBe('aXb');
  });

  it('moves the cursor along with what was inserted', () => {
    expect(pressing(at('ab', 1), 'X', PLAIN, [], []).at).toBe(2);
  });

  it('drops the line endings out of a pasted block', () => {
    // MEASURED, DRIVING THE REAL THING THROUGH A PTY. A terminal delivers a
    // run of characters arriving together as text rather than as keys, so a
    // paste carrying a newline arrives here as an ordinary insertion with a
    // `\r` in the middle of it. Inserted literally it is invisible on
    // screen and moves the cursor to column zero when the line is drawn --
    // the transcript showed three separate inputs apparently overwriting
    // one another, and the entry that went out held all three.
    //
    // A composer is one line. What a multi-line paste MEANS -- submit each
    // line, join them, refuse -- is a real question and not this one; a
    // space is the answer that loses no characters and surprises nobody.
    const after = pressing(at(''), 'first line\r\nsecond line', PLAIN, [], []);
    expect(after.typed).toBe('first line second line');
  });

  it('never types a carriage return into the line', () => {
    expect(pressing(at('ab'), '\r', PLAIN, [], []).typed).not.toContain('\r');
  });

  it('treats a lone carriage return arriving as text as the Enter it is', () => {
    // This expectation was the other way round an hour ago, written before
    // the coalescing above was understood. A `\r` that reaches here as
    // TEXT rather than as `key.return` is a Return that Ink did not manage
    // to classify -- there is no other way for one to arrive -- so
    // swallowing it would drop a keypress a person definitely made.
    expect(pressing(at('ab'), '\r', PLAIN, [], []).submit).toBe('ab');
  });

  it('submits when a chunk of text ends in a line ending', () => {
    // MEASURED, AND IT IS TWO BUGS IN ONE PLACE. A terminal coalesces keys
    // that arrive close together, so typing or pasting fast delivers
    // `elp\r` as ONE text chunk with no `return` key set -- logged out of
    // the real client driven through a pty. Treating that as text alone
    // drops the newline silently: the line sits in the composer and
    // pressing Enter appears to have done nothing, which is exactly what a
    // person pasting `a question⏎` would see.
    const after = pressing(at('a quest'), 'ion?\r', PLAIN, [], []);
    expect(after.submit).toBe('a question?');
    expect(after.typed).toBe('');
  });

  it('completes when a chunk of text ends in a tab', () => {
    // The same coalescing as above, met by the other key that is not text.
    // Driven through a pty against a live server, `/bo` + Tab arrived as
    // one chunk `"/bo\t"` -- the tab was stripped as a control character
    // and the completion never happened, so `/bo` went to the server as an
    // unknown command.
    expect(pressing(at(''), '/bot\t', PLAIN, [], []).typed).toBe(BOTS_COMMAND);
  });

  it('submits a pasted block as the one line it becomes', () => {
    const after = pressing(at(''), 'first line\nsecond line\n', PLAIN, [], []);
    expect(after.submit).toBe('first line second line');
  });

  it('does not submit a chunk that merely contains a line ending in the middle', () => {
    const after = pressing(at(''), 'first line\nsecond line', PLAIN, [], []);
    expect(Object.hasOwn(after, 'submit')).toBe(false);
    expect(after.typed).toBe('first line second line');
  });

  it('submits nothing for a chunk that is only a line ending on an empty line', () => {
    expect(Object.hasOwn(pressing(at(''), '\r', PLAIN, [], []), 'submit')).toBe(
      false,
    );
  });

  it('takes a paste as one insertion rather than one character', () => {
    // A terminal delivers a paste as a single chunk. Handling only the
    // first character would silently drop the rest of what somebody
    // pasted, which is the worst way to lose text.
    const after = pressing(at(''), 'a whole pasted sentence', PLAIN, [], []);
    expect(after).toMatchObject({ typed: 'a whole pasted sentence', at: 23 });
  });
});

describe('editing', () => {
  it('backspace removes the character before the cursor', () => {
    expect(
      pressing(at('abc', 2), '', press({ backspace: true }), [], []),
    ).toMatchObject({ typed: 'ac', at: 1 });
  });

  it('backspace at the start of the line does nothing', () => {
    expect(
      pressing(at('abc', 0), '', press({ backspace: true }), [], []),
    ).toMatchObject({ typed: 'abc', at: 0 });
  });

  it('treats the key macOS labels Delete as a backspace', () => {
    // Ink reports that key as `delete`, and on a Mac keyboard it is the one
    // people press to rub out what they just typed. A composer that made it
    // delete forwards would be right about the name and wrong about the
    // keyboard.
    expect(
      pressing(at('abc', 2), '', press({ delete: true }), [], []),
    ).toMatchObject({ typed: 'ac', at: 1 });
  });

  it('ctrl-u throws the whole line away', () => {
    expect(
      pressing(at('half a question'), 'u', press({ ctrl: true }), [], []),
    ).toMatchObject({ typed: '', at: 0 });
  });

  it('ctrl-w removes the word before the cursor', () => {
    expect(
      pressing(at('sort by two columns'), 'w', press({ ctrl: true }), [], []),
    ).toMatchObject({ typed: 'sort by two ', at: 12 });
  });
});

describe('moving', () => {
  it('left moves back one', () => {
    expect(pressing(at('ab'), '', press({ leftArrow: true }), [], []).at).toBe(
      1,
    );
  });

  it('left does not walk off the start', () => {
    expect(
      pressing(at('ab', 0), '', press({ leftArrow: true }), [], []).at,
    ).toBe(0);
  });

  it('right does not walk off the end', () => {
    expect(pressing(at('ab'), '', press({ rightArrow: true }), [], []).at).toBe(
      2,
    );
  });

  it('ctrl-a goes to the start and ctrl-e to the end', () => {
    expect(pressing(at('hello'), 'a', press({ ctrl: true }), [], []).at).toBe(
      0,
    );
    expect(
      pressing(at('hello', 0), 'e', press({ ctrl: true }), [], []).at,
    ).toBe(5);
  });
});

describe('submitting', () => {
  it('return submits what was typed and clears the line', () => {
    expect(
      pressing(at('hi'), '', press({ return: true }), [], []),
    ).toMatchObject({ submit: 'hi', typed: '', at: 0 });
  });

  it('return on an empty line submits nothing at all', () => {
    // NOT `submit: ''`. A surface doing `if (submit !== undefined)` would
    // otherwise send an empty question to the server on every stray Enter.
    expect(
      Object.hasOwn(
        pressing(at(''), '', press({ return: true }), [], []),
        'submit',
      ),
    ).toBe(false);
  });

  it('return on a line of only spaces submits nothing either', () => {
    expect(
      Object.hasOwn(
        pressing(at('   '), '', press({ return: true }), [], []),
        'submit',
      ),
    ).toBe(false);
  });

  it('submits the characters as typed, spaces and all', () => {
    // Trimming is the caller's, and it already does it. A composer that
    // trimmed would be editing somebody's line on its way past.
    expect(
      pressing(at('  spaced  '), '', press({ return: true }), [], []).submit,
    ).toBe('  spaced  ');
  });
});

describe('tab', () => {
  it('finishes a command from a unique prefix', () => {
    // '/he' is no longer unique to '/help' now that '/here' exists too,
    // so this reaches for '/hel', which still is.
    expect(pressing(at('/hel'), '', press({ tab: true }), [], []).typed).toBe(
      HELP_COMMAND,
    );
  });

  it('puts the cursor after what it finished', () => {
    expect(pressing(at('/hel'), '', press({ tab: true }), [], []).at).toBe(
      HELP_COMMAND.length,
    );
  });

  it('finishes a name the server declared', () => {
    expect(
      pressing(at('aristo'), '', press({ tab: true }), ['aristoxenus'], [])
        .typed,
    ).toBe('aristoxenus');
  });

  it('finishes no name the server did not send, and leaves the line as typed', () => {
    expect(
      pressing(at('herm'), '', press({ tab: true }), ['aristoxenus'], []).typed,
    ).toBe('herm');
  });

  it('offers every match when the prefix is ambiguous', () => {
    const after = pressing(at('/'), '', press({ tab: true }), [], []);
    expect(after.offering).toContain(HELP_COMMAND);
    expect(after.offering).toContain(BOTS_COMMAND);
  });

  it('extends an ambiguous prefix as far as the matches agree', () => {
    const after = pressing(
      at('ari'),
      '',
      press({ tab: true }),
      ['aristoxenus', 'aristotle'],
      [],
    );
    expect(after.typed).toBe('aristo');
    expect(after.offering).toEqual(['aristoxenus', 'aristotle']);
  });

  it('does not throw on a tab with nothing in front of it', () => {
    expect(() =>
      pressing(at(''), '', press({ tab: true }), [], []),
    ).not.toThrow();
  });

  it('forgets what it offered as soon as something else is typed', () => {
    const offered = pressing(at('/'), '', press({ tab: true }), [], []);
    expect(pressing(offered, 'h', PLAIN, [], []).offering).toEqual([]);
  });
});

describe('history', () => {
  const SAID = ['older', 'newer'] as const;

  it('up walks back through history, newest first', () => {
    expect(
      pressing(at(''), '', press({ upArrow: true }), [], [...SAID]).typed,
    ).toBe('newer');
  });

  it('up twice reaches the one before', () => {
    const once = pressing(at(''), '', press({ upArrow: true }), [], [...SAID]);
    expect(
      pressing(once, '', press({ upArrow: true }), [], [...SAID]).typed,
    ).toBe('older');
  });

  it('up at the oldest entry stays there rather than emptying the line', () => {
    let state: Composing = at('');
    for (let press_ = 0; press_ < 5; press_ += 1) {
      state = pressing(state, '', press({ upArrow: true }), [], [...SAID]);
    }
    expect(state.typed).toBe('older');
  });

  it('down comes back forwards', () => {
    let state: Composing = at('');
    state = pressing(state, '', press({ upArrow: true }), [], [...SAID]);
    state = pressing(state, '', press({ upArrow: true }), [], [...SAID]);
    expect(
      pressing(state, '', press({ downArrow: true }), [], [...SAID]).typed,
    ).toBe('newer');
  });

  it('gives back the half-typed line when it walks off the newest end', () => {
    // The line somebody was part-way through when they reached for the up
    // key. Losing it is the thing that makes history browsing feel unsafe.
    const drafted = at('what I was in the middle of');
    const recalled = pressing(
      drafted,
      '',
      press({ upArrow: true }),
      [],
      [...SAID],
    );
    expect(recalled.typed).toBe('newer');
    expect(
      pressing(recalled, '', press({ downArrow: true }), [], [...SAID]).typed,
    ).toBe('what I was in the middle of');
  });

  it('does nothing at all when there is no history to walk', () => {
    expect(
      pressing(at('typed'), '', press({ upArrow: true }), [], []),
    ).toMatchObject({ typed: 'typed' });
  });
});

describe('the command menu, while it is open', () => {
  const vocabulary = {
    commands: [
      { name: HELP_COMMAND, detail: 'this' },
      { name: BOTS_COMMAND, detail: 'who' },
      { name: '/theme', detail: 'colours' },
    ],
    names: [{ name: 'aristoxenus' }],
    arguments: { '/theme': () => [{ name: 'auto' }, { name: 'dusk' }] },
  };
  const key = (
    state: Composing,
    input: string,
    named: Partial<Key>,
    history: string[] = [],
  ) => pressing(state, input, press(named), [], history, vocabulary);

  it('moves the pick with the arrows, wrapping, instead of walking history', () => {
    const down = key(at('/'), '', { downArrow: true }, ['older']);
    expect(down.picked).toBe(1);
    expect(down.typed).toBe('/');
    expect(key(at('/'), '', { upArrow: true }).picked).toBe(2);
  });

  it('takes the picked row on Tab', () => {
    const picked = key({ ...at('/'), picked: 1, moved: true }, '', {
      tab: true,
    });
    expect(picked.typed).toBe(BOTS_COMMAND);
    expect(picked.submit).toBeUndefined();
  });

  it('sends a command on Enter when what is typed is only the start of one', () => {
    expect(key(at('/he'), '', { return: true }).submit).toBe(HELP_COMMAND);
  });

  it('sends a whole command as typed, rather than completing it into another', () => {
    // `/theme` typed in full lists the themes; it must not become `/theme `.
    expect(key(at('/theme'), '', { return: true }).submit).toBe('/theme');
  });

  it('opens the arguments when Enter picks a command that takes one', () => {
    const picked = key(at('/th'), '', { return: true });
    expect(picked.submit).toBeUndefined();
    expect(picked.typed).toBe('/theme ');
  });

  it('sends a command with the argument Enter picked', () => {
    const picked = key({ ...at('/theme '), picked: 1, moved: true }, '', {
      return: true,
    });
    expect(picked.submit).toBe('/theme dusk');
  });

  it('sends a bare /theme from its argument menu when nothing was picked', () => {
    expect(key(at('/theme '), '', { return: true }).submit).toBe('/theme ');
  });

  it('completes an open-ended argument on Enter rather than sending it', () => {
    const answering = {
      ...vocabulary,
      arguments: {
        ...vocabulary.arguments,
        '/answer': () => [{ name: 'orc_1' }, { name: 'orc_2' }],
      },
      openEnded: ['/answer'],
    };
    const enter = (state: Composing) =>
      pressing(state, '', press({ return: true }), [], [], answering);

    // Typed in full, the id is still only the start of the line.
    const whole = enter(at('/answer orc_1'));
    expect(whole.submit).toBeUndefined();
    expect(whole.typed).toBe('/answer orc_1 ');
    const chosen = enter({ ...at('/answer '), picked: 1, moved: true });
    expect(chosen.submit).toBeUndefined();
    expect(chosen.typed).toBe('/answer orc_2 ');
    // Nothing picked and nothing typed: `/answer` alone, which lists them.
    expect(enter(at('/answer ')).submit).toBe('/answer ');
  });

  it('completes a mention on Enter without sending the sentence', () => {
    const picked = key(at('ask @ari'), '', { return: true });
    expect(picked.typed).toBe('ask @aristoxenus ');
    expect(picked.submit).toBeUndefined();
  });

  it('closes on Escape and stays closed until the line changes', () => {
    const closed = key(at('/he'), '', { escape: true });
    expect(closed.closed).toBe(true);
    // Closed, Enter sends what is typed — the unknown command gets its answer.
    expect(key(closed, '', { return: true }).submit).toBe('/he');
    expect(key(closed, 'l', PLAIN).closed).toBeUndefined();
  });

  it('forgets the pick when the line changes', () => {
    expect(
      key({ ...at('/'), picked: 2, moved: true }, 'b', PLAIN).picked,
    ).toBeUndefined();
  });
});

describe('a question answered a key at a time', () => {
  it('reads the arrows, enter and escape as themselves', () => {
    expect(strokeOf('', press({ leftArrow: true }))).toEqual({ kind: 'left' });
    expect(strokeOf('', press({ rightArrow: true }))).toEqual({
      kind: 'right',
    });
    expect(strokeOf('\r', press({ return: true }))).toEqual({ kind: 'enter' });
    expect(strokeOf('', press({ escape: true }))).toEqual({ kind: 'escape' });
  });

  it('reads a letter as text, and a chord or a key with no text as nothing', () => {
    expect(strokeOf('p', PLAIN)).toEqual({ kind: 'text', text: 'p' });
    expect(strokeOf('c', press({ ctrl: true }))).toBeUndefined();
    expect(strokeOf('d', press({ meta: true }))).toBeUndefined();
    expect(strokeOf('', press({ upArrow: true }))).toBeUndefined();
  });
});

describe('questionStrokeOf', () => {
  it('reads the arrows, tab, shift-tab and backspace a list needs, and the rest as strokeOf', () => {
    expect(questionStrokeOf('', press({ upArrow: true }))).toEqual({
      kind: 'up',
    });
    expect(questionStrokeOf('', press({ downArrow: true }))).toEqual({
      kind: 'down',
    });
    expect(questionStrokeOf('', press({ tab: true }))).toEqual({ kind: 'tab' });
    expect(questionStrokeOf('', press({ tab: true, shift: true }))).toEqual({
      kind: 'backtab',
    });
    expect(questionStrokeOf('', press({ backspace: true }))).toEqual({
      kind: 'backspace',
    });
    expect(questionStrokeOf('2', PLAIN)).toEqual({ kind: 'text', text: '2' });
    expect(questionStrokeOf('', press({ escape: true }))).toEqual({
      kind: 'escape',
    });
  });
});

describe("the runs panel's key", () => {
  it('is Ctrl-O and nothing else', () => {
    expect(panelToggled('o', press({ ctrl: true }))).toBe(true);
    expect(panelToggled('o', PLAIN)).toBe(false);
    expect(panelToggled('p', press({ ctrl: true }))).toBe(false);
  });

  it('takes ctrl-t as the next tool-line density, and a plain t as typing', () => {
    expect(densityToggled('t', press({ ctrl: true }))).toBe(true);
    expect(densityToggled('t', PLAIN)).toBe(false);
    expect(pressing(at(''), 't', PLAIN, [], []).typed).toBe('t');
  });
});

describe("the viewer's keys", () => {
  it('scrolls, jumps, opens, reads earlier, filters, follows and goes back', () => {
    expect(viewKeyOf('', press({ upArrow: true }))).toBe('up');
    expect(viewKeyOf('', press({ downArrow: true }))).toBe('down');
    expect(viewKeyOf('', press({ pageUp: true }))).toBe('pageUp');
    expect(viewKeyOf('', press({ pageDown: true }))).toBe('pageDown');
    expect(viewKeyOf('e', PLAIN)).toBe('earlier');
    expect(viewKeyOf('t', PLAIN)).toBe('tools');
    expect(viewKeyOf('f', PLAIN)).toBe('follow');
    expect(viewKeyOf('', press({ escape: true }))).toBe('close');
    expect(viewKeyOf('q', PLAIN)).toBe('close');
    expect(viewKeyOf('z', PLAIN)).toBeUndefined();
    expect(viewKeyOf('x', PLAIN)).toBe('nextFailure');
    expect(viewKeyOf('X', PLAIN)).toBe('prevFailure');
    expect(viewKeyOf('[', PLAIN)).toBe('prevMark');
    expect(viewKeyOf(']', PLAIN)).toBe('nextMark');
    expect(viewKeyOf('\r', press({ return: true }))).toBe('open');
    expect(viewKeyOf('e', press({ ctrl: true }))).toBeUndefined();
    // Ctrl-C leaves the viewer: between turns there is nothing else for it to stop.
    expect(viewKeyOf('c', press({ ctrl: true }))).toBe('close');
  });
});

describe("the explorer's keys", () => {
  it('moves, opens, goes back, inspects and closes', () => {
    expect(exploreKeyOf('', press({ upArrow: true }), false)).toBe('up');
    expect(exploreKeyOf('j', PLAIN, false)).toBe('down');
    expect(exploreKeyOf('', press({ rightArrow: true }), false)).toBe(
      'descend',
    );
    expect(exploreKeyOf('', press({ leftArrow: true }), false)).toBe('ascend');
    expect(exploreKeyOf('', press({ backspace: true }), false)).toBe('ascend');
    expect(exploreKeyOf('', press({ delete: true }), false)).toBe('ascend');
    expect(exploreKeyOf('', press({ return: true }), false)).toBe('inspect');
    expect(exploreKeyOf('', press({ tab: true }), false)).toBe('tab');
    expect(exploreKeyOf('/', PLAIN, false)).toBe('search');
    expect(exploreKeyOf('v', PLAIN, false)).toBe('view');
    expect(exploreKeyOf('q', PLAIN, false)).toBe('close');
    expect(exploreKeyOf('', press({ escape: true }), false)).toBe('escape');
    expect(exploreKeyOf('z', PLAIN, false)).toBeUndefined();
    expect(exploreKeyOf('v', press({ ctrl: true }), false)).toBeUndefined();
    // Ctrl-C leaves the explorer, as it leaves the viewer.
    expect(exploreKeyOf('c', press({ ctrl: true }), false)).toBe('close');
  });

  it('reads printable keys as the query while a search is typed', () => {
    expect(exploreKeyOf('q', PLAIN, true)).toEqual({ typed: 'q' });
    expect(exploreKeyOf('v', PLAIN, true)).toEqual({ typed: 'v' });
    expect(exploreKeyOf('', press({ backspace: true }), true)).toBe('erase');
    expect(exploreKeyOf('', press({ delete: true }), true)).toBe('erase');
    expect(exploreKeyOf('', press({ return: true }), true)).toBe('enter');
    expect(exploreKeyOf('', press({ escape: true }), true)).toBe('escape');
    expect(exploreKeyOf('c', press({ ctrl: true }), true)).toBe('close');
    expect(exploreKeyOf('w', press({ ctrl: true }), true)).toBeUndefined();
  });
});

describe('the cap dialog', () => {
  it("reads the cap dialog's keys, and escape as later", () => {
    expect(capKeyOf('y', PLAIN)).toBe('continue');
    expect(capKeyOf('n', PLAIN)).toBe('stop');
    expect(capKeyOf('a', PLAIN)).toBe('always');
    expect(capKeyOf('w', PLAIN)).toBe('watch');
    expect(capKeyOf('', press({ escape: true }))).toBe('later');
    expect(capKeyOf('x', PLAIN)).toBeUndefined();
    expect(capKeyOf('y', press({ ctrl: true }))).toBeUndefined();
    expect(capKeyOf('\r', press({ return: true }))).toBeUndefined();
  });

  it('reads Ctrl-C as later too, since the dialog is only up between turns', () => {
    // The viewer's reasoning: there is no run of the person's own for Ctrl-C to stop, and the
    // key everybody reaches for to get out must not be the one that does nothing.
    expect(capKeyOf('c', press({ ctrl: true }))).toBe('later');
  });

  it('reads a key only of the dialog that is up', () => {
    expect(capKeyOf('r', PLAIN)).toBeUndefined();
    expect(capKeyOf('r', PLAIN, ['reply', 'watch', 'later'])).toBe('reply');
    expect(capKeyOf('w', PLAIN, ['reply', 'watch', 'later'])).toBe('watch');
    expect(capKeyOf('y', PLAIN, ['reply', 'watch', 'later'])).toBeUndefined();
    expect(
      capKeyOf('a', PLAIN, ['continue', 'stop', 'watch', 'later']),
    ).toBeUndefined();
    expect(
      capKeyOf('', press({ escape: true }), ['reply', 'watch', 'later']),
    ).toBe('later');
  });
});
