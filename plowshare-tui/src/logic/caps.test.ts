import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

import {
  ALWAYS_CAPS,
  alwaysAutoContinue,
  capKeyOfLine,
  capsOf,
  DIALOG_KEYS,
  dialogKeyOfLine,
  pickingAbout,
  pickingOn,
  readingCaps,
} from './caps.ts';
import { dialogOptionsOf } from './wording.ts';

describe('the caps frame', () => {
  it('asks for a project and reads each value with its source', () => {
    expect(readingCaps('story')).toEqual({
      type: 'orchestration.caps',
      payload: { project: 'story' },
    });
    expect(
      capsOf({
        code: 'OK',
        payload: {
          project: 'story',
          applied: 2,
          steps: { value: 40, source: '.plowshare/environment.yml' },
          budget: { value: null, source: 'definition' },
          autoContinue: { value: 3, source: '.plowshare/environment.yml' },
          time: { value: 90, source: '.plowshare/environment.yml' },
          failedChecks: { value: 5, source: 'default' },
        },
      }),
    ).toEqual({
      project: 'story',
      applied: 2,
      steps: { value: 40, source: '.plowshare/environment.yml' },
      budget: { source: 'definition' },
      autoContinue: { value: 3, source: '.plowshare/environment.yml' },
      time: { value: 90, source: '.plowshare/environment.yml' },
      failedChecks: { value: 5, source: 'default' },
    });
    // A server from before V69 names neither: no time cap, and the default of five.
    expect(
      capsOf({
        code: 'OK',
        payload: { project: 'story', steps: {}, budget: {}, autoContinue: {} },
      }),
    ).toMatchObject({
      time: { source: 'definition' },
      failedChecks: { value: 5, source: 'default' },
    });
    expect(capsOf({ code: 'REFUSED', said: 'no' })).toBeUndefined();
  });

  it('reads a plain line as a dialog key', () => {
    expect(['y', 'n', 'a', 'w', ''].map(capKeyOfLine)).toEqual([
      'continue',
      'stop',
      'always',
      'watch',
      'later',
    ]);
    expect(capKeyOfLine('yes please')).toBeUndefined();
    expect(ALWAYS_CAPS).toBe(3);
  });

  it('reads a plain line as a key of the dialog that is up, and only of that one', () => {
    expect(DIALOG_KEYS).toEqual({
      cap: ['continue', 'stop', 'always', 'watch', 'later'],
      stuck: ['continue', 'stop', 'watch', 'later'],
      accept: ['continue', 'reply', 'watch', 'later'],
      // V69: a failing check's `y` is "go on" and `n` stops it; never `a`, since
      // auto-continue never passes a repeating failure.
      checks: ['continue', 'stop', 'watch', 'later'],
      question: ['reply', 'watch', 'later'],
      // An approval's own keys are the approval prompt's strokes; later is all it shares.
      approval: ['later'],
    });
    expect(
      ['y', 'r', 'w', '', 'n', 'a'].map((line) =>
        dialogKeyOfLine(line, DIALOG_KEYS.accept),
      ),
    ).toEqual(['continue', 'reply', 'watch', 'later', undefined, undefined]);
    expect(
      ['r', 'w', '', 'y', 'n', 'a'].map((line) =>
        dialogKeyOfLine(line, DIALOG_KEYS.question),
      ),
    ).toEqual(['reply', 'watch', 'later', undefined, undefined, undefined]);
    expect(
      ['y', 'n', 'w', '', 'a', 'r'].map((line) =>
        dialogKeyOfLine(line, DIALOG_KEYS.stuck),
      ),
    ).toEqual(['continue', 'stop', 'watch', 'later', undefined, undefined]);
    expect(dialogKeyOfLine(' r ', DIALOG_KEYS.question)).toBe('reply');
    expect(
      ['y', 'n', 'w', '', 'a', 'r'].map((line) =>
        dialogKeyOfLine(line, DIALOG_KEYS.checks),
      ),
    ).toEqual(['continue', 'stop', 'watch', 'later', undefined, undefined]);
    expect(dialogKeyOfLine('r', DIALOG_KEYS.cap)).toBeUndefined();
  });

  it('has a set auto-continue at least three, and never lowers a higher one', () => {
    // The final review: `a` wrote 3 over a 5 the person had chosen.
    expect(alwaysAutoContinue(undefined)).toBe(3);
    expect(alwaysAutoContinue(0)).toBe(3);
    expect(alwaysAutoContinue(5)).toBe(5);
  });
});

describe("picking a harness question's answer from a list", () => {
  const options = dialogOptionsOf('stuck');

  it("offers each of the kind's keys, in order, with its letter", () => {
    expect(options).toEqual([
      { key: 'continue', letter: 'y', label: 'go on' },
      { key: 'stop', letter: 'n', label: 'stop the run' },
      { key: 'watch', letter: 'w', label: 'watch the run first' },
      { key: 'later', letter: '', label: 'decide later' },
    ]);
  });

  it("names every key of every kind in words, a failing check's included — never the raw key", () => {
    for (const kind of Object.keys(
      DIALOG_KEYS,
    ) as (keyof typeof DIALOG_KEYS)[]) {
      if (kind === 'approval') {
        continue;
      }
      for (const option of dialogOptionsOf(kind)) {
        expect(option.label, `${kind}'s ${option.key}`).not.toBe(option.key);
      }
    }
    // As its keys line says them: `y go on · n stop · w watch · esc later`.
    expect(
      dialogOptionsOf('checks').map(
        (option) => `${option.letter} ${option.label}`,
      ),
    ).toEqual([
      'y go on',
      'n stop the run',
      'w watch the run first',
      ' decide later',
    ]);
  });

  it('starts on later, so a stray enter decides later rather than going on', () => {
    expect(pickingAbout(options)).toEqual({
      kind: 'picking',
      options,
      focus: 3,
    });
    expect(pickingOn(pickingAbout(options), { kind: 'enter' })).toEqual({
      kind: 'picked',
      key: 'later',
    });
    expect(pickingOn(pickingAbout(options), { kind: 'down' })).toEqual(
      pickingAbout(options),
    );
  });

  it('the arrows and enter pick; so does a letter; esc is later', () => {
    const up = pickingOn(pickingOn(pickingAbout(options), { kind: 'up' }), {
      kind: 'up',
    });
    expect(pickingOn(up, { kind: 'enter' })).toEqual({
      kind: 'picked',
      key: 'stop',
    });
    expect(
      pickingOn(pickingAbout(options), { kind: 'text', text: 'W' }),
    ).toEqual({ kind: 'picked', key: 'watch' });
    expect(pickingOn(pickingAbout(options), { kind: 'escape' })).toEqual({
      kind: 'picked',
      key: 'later',
    });
    expect(
      pickingOn(pickingAbout(options), { kind: 'text', text: 'a' }),
    ).toEqual(pickingAbout(options));
  });
});

describe('the caps module', () => {
  // Task 14's review: session.ts imported ALWAYS_CAPS from here while this file read its answers
  // with session.ts's helpers — a cycle at load, whose order decides which side sees undefined.
  it('is not imported by session.ts, whose helpers it reads with', () => {
    const session = readFileSync(
      new URL('./session.ts', import.meta.url),
      'utf8',
    );
    expect(session).not.toMatch(/from '\.\/caps\.ts'/u);
  });
});
