import { describe, expect, it } from 'vitest';

import {
  askingAbout,
  pressingOn,
  prefixAt,
  settledAsking,
  startingLength,
  strokesOf,
} from './approval.ts';
import type { Asking, Stroke } from './approval.ts';
import { APPROVAL_ANSWER } from './session.ts';
import type { Approval } from './session.ts';
import { describeDecided, describeKeys, describeQuestion } from './wording.ts';

/**
 * One question a run asked, answered a key at a time — without a terminal.
 *
 * <p>The property every case below comes back to: <b>the prefix a frame carries
 * is the prefix the person was shown</b>. A key that sent one argument more
 * than the line on the screen would approve a command nobody read.
 */

const GRADLE: Approval = {
  id: 'apr_1',
  conversation: 'cnv_1',
  agent: 'builder',
  side: 'local',
  command: ['./gradlew', 'test', '--tests', 'Foo'],
  cwd: '/repo',
  state: 'asked',
  defaultPrefix: ['./gradlew', 'test'],
};

const text = (typed: string): Stroke => ({ kind: 'text', text: typed });
const LEFT: Stroke = { kind: 'left' };
const RIGHT: Stroke = { kind: 'right' };
const ENTER: Stroke = { kind: 'enter' };
const ESCAPE: Stroke = { kind: 'escape' };

function pressed(approval: Approval, ...strokes: Stroke[]): Asking {
  return strokes.reduce(pressingOn, askingAbout(approval));
}

describe('the four answers', () => {
  it('sends once, conversation and deny on their own key, with no prefix', () => {
    for (const [key, decision] of [
      ['o', 'once'],
      ['c', 'conversation'],
      ['d', 'deny'],
    ] as const) {
      const state = pressed(GRADLE, text(key));
      expect(state.kind).toBe('answered');
      expect(state.kind === 'answered' ? state.ask : undefined).toEqual({
        type: APPROVAL_ANSWER,
        payload: { id: 'apr_1', decision },
      });
      expect(settledAsking(state)).toBe(true);
    }
  });

  it('reads a capital as the same key, because caps lock is not a different answer', () => {
    expect(pressed(GRADLE, text('D')).kind).toBe('answered');
  });

  it('answers nothing for any other key, or for a paste that starts with one', () => {
    for (const stroke of [
      text('x'),
      text('deny'),
      text(' '),
      LEFT,
      RIGHT,
      ENTER,
    ]) {
      expect(pressed(GRADLE, stroke)).toEqual(askingAbout(GRADLE));
    }
  });

  it('leaves the question open on escape, and sends nothing', () => {
    const state = pressed(GRADLE, ESCAPE);
    expect(state).toEqual({ kind: 'left', approval: GRADLE });
    expect(settledAsking(state)).toBe(true);
    expect('ask' in state).toBe(false);
  });

  it('reads no more keys once it is settled', () => {
    const once = pressed(GRADLE, text('o'));
    expect(pressingOn(once, text('d'))).toBe(once);
  });
});

describe('the project prefix', () => {
  it('starts at the server default, and sends it on enter', () => {
    const state = pressed(GRADLE, text('p'));
    expect(state).toEqual({ kind: 'prefixing', approval: GRADLE, length: 2 });
    expect(settledAsking(state)).toBe(false);
    expect(pressingOn(state, ENTER)).toEqual({
      kind: 'answered',
      approval: GRADLE,
      ask: {
        type: APPROVAL_ANSWER,
        payload: {
          id: 'apr_1',
          decision: 'project',
          prefix: ['./gradlew', 'test'],
        },
      },
    });
  });

  it('shrinks and grows by one whole argument', () => {
    expect(pressed(GRADLE, text('p'), LEFT)).toMatchObject({ length: 1 });
    expect(pressed(GRADLE, text('p'), RIGHT)).toMatchObject({ length: 3 });
  });

  it('never goes below the program or past the whole command', () => {
    expect(pressed(GRADLE, text('p'), LEFT, LEFT, LEFT)).toMatchObject({
      length: 1,
    });
    expect(
      pressed(GRADLE, text('p'), RIGHT, RIGHT, RIGHT, RIGHT),
    ).toMatchObject({ length: 4 });
    const whole = pressed(GRADLE, text('p'), RIGHT, RIGHT, RIGHT, ENTER);
    expect(
      whole.kind === 'answered' ? whole.ask.payload['prefix'] : undefined,
    ).toEqual(GRADLE.command);
  });

  it('backs out to the choice on escape, where a second escape leaves the question', () => {
    const back = pressed(GRADLE, text('p'), RIGHT, ESCAPE);
    expect(back).toEqual(askingAbout(GRADLE));
    expect(pressingOn(back, ESCAPE).kind).toBe('left');
  });

  it('ignores the answer keys while choosing the prefix', () => {
    const state = pressed(GRADLE, text('p'));
    expect(pressingOn(state, text('d'))).toBe(state);
  });

  it('starts at the program alone when the default does not lead the command', () => {
    // The server refuses a prefix that does not lead the command, and a
    // person who pressed enter on what they were shown should not be told
    // their answer was malformed.
    expect(startingLength({ ...GRADLE, defaultPrefix: ['make', 'test'] })).toBe(
      1,
    );
    expect(startingLength({ ...GRADLE, defaultPrefix: [] })).toBe(1);
    expect(
      startingLength({ ...GRADLE, defaultPrefix: [...GRADLE.command, 'more'] }),
    ).toBe(1);
    expect(
      startingLength({ ...GRADLE, command: ['ls'], defaultPrefix: ['ls'] }),
    ).toBe(1);
  });

  it('shows exactly the prefix it sends', () => {
    const state = pressed(GRADLE, text('p'), RIGHT);
    const shown = describeKeys(state).join('\n');
    expect(shown).toContain(
      'allow any command starting: ./gradlew test --tests',
    );
    expect(shown).toContain('(not: Foo)');
    const sent = pressingOn(state, ENTER);
    expect(
      sent.kind === 'answered' ? sent.ask.payload['prefix'] : undefined,
    ).toEqual(prefixAt(GRADLE, 3));
    expect(describeDecided(sent)).toBe(
      'allow any command starting ./gradlew test --tests for this project',
    );
  });
});

describe('what a question says', () => {
  it('names the command, the side, the directory and the reason', () => {
    const said = describeQuestion({
      ...GRADLE,
      reason: 'tests touch the network',
    }).join('\n');
    expect(said).toContain('./gradlew test --tests Foo');
    expect(said).toContain('local');
    expect(said).toContain('/repo');
    expect(said).toContain('because tests touch the network');
  });

  it('says no reason when the mode asked rather than a hook', () => {
    expect(describeQuestion(GRADLE).join('\n')).not.toContain('because');
  });

  it('names all four keys while choosing', () => {
    const keys = describeKeys(askingAbout(GRADLE)).join('\n');
    for (const key of ['o once', 'c ', 'p ', 'd deny', 'esc']) {
      expect(keys).toContain(key);
    }
  });
});

describe('an acceptance set, answered once for all its commands (V67)', () => {
  const SET: Approval = {
    ...GRADLE,
    id: 'apr_set',
    agent: 'implement_specification',
    command: [],
    commands: [
      ['./gradlew', 'test'],
      ['./gradlew', 'run', '--args=3'],
    ],
    judged: 'unsure',
    defaultPrefix: [],
  };

  it('takes once, conversation and deny, and no project prefix', () => {
    expect(pressed(SET, text('p'))).toEqual(askingAbout(SET));
    for (const [key, decision] of [
      ['o', 'once'],
      ['c', 'conversation'],
      ['d', 'deny'],
    ]) {
      const state = pressed(SET, text(key as string));
      expect(state.kind === 'answered' && state.ask.payload['decision']).toBe(
        decision,
      );
    }
    const keys = describeKeys(askingAbout(SET)).join('\n');
    expect(keys).toContain('o once');
    expect(keys).toContain('d deny');
    expect(keys).not.toContain('p for this project');
  });

  it('shows every command, where, and why the judge put it to the person', () => {
    expect(describeQuestion(SET)).toEqual([
      'a run asks to run 2 acceptance commands on the local side, one answer for all of them:',
      '  ./gradlew test',
      '  ./gradlew run --args=3',
      '  in /repo',
      '  the command judge did not find them clearly safe: unsure',
    ]);
    expect(describeDecided(pressed(SET, text('o')))).toBe('allow all 2 once');
    expect(describeDecided(pressed(SET, text('d')))).toBe('deny all 2');
  });
});

describe('a line read as keys, for a surface that has none', () => {
  it('reads the answer letters, the arrows, enter and escape', () => {
    expect(strokesOf(' p ')).toEqual([text('p')]);
    expect(strokesOf('<<>')).toEqual([LEFT, LEFT, RIGHT]);
    expect(strokesOf('')).toEqual([ENTER]);
    expect(strokesOf('ESC')).toEqual([ESCAPE]);
  });

  it('reads a sentence as one stroke, which answers nothing', () => {
    expect(strokesOf('do it')).toEqual([text('do it')]);
    expect(pressed(GRADLE, ...strokesOf('do it'))).toEqual(askingAbout(GRADLE));
  });
});
