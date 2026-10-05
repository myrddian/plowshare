import { describe, expect, it } from 'vitest';

import { parse } from './markdown.ts';
import { appended, blank, entered, LIVE_TAIL, phaseAfter } from './screen.ts';
import { describeElapsed } from './wording.ts';

describe('an entry', () => {
  it('omits note entirely when there is none', () => {
    const entry = entered(1, 'person', parse('hello'));
    // NOT toBeUndefined. `exactOptionalPropertyTypes` makes the difference
    // between an absent key and a present undefined one real, and a
    // renderer asking `'note' in entry` would draw an empty note line under
    // every entry that never had one.
    expect(Object.hasOwn(entry, 'note')).toBe(false);
  });

  it('keeps a note when given one', () => {
    expect(entered(1, 'bot', parse('hi'), '2 steps').note).toBe('2 steps');
  });

  it('carries the voice it was given and no colour at all', () => {
    const entry = entered(1, 'trouble', parse('no'));
    expect(entry.voice).toBe('trouble');
    // The model names who spoke. How that is drawn belongs to whoever
    // draws, which is the whole of the spec's §3.3.
    expect(Object.keys(entry).sort()).toEqual(['at', 'body', 'voice']);
  });
});

describe('a blank screen', () => {
  it('has no working key, which is how a surface knows nothing runs', () => {
    expect(Object.hasOwn(blank('aristoxenus'), 'working')).toBe(false);
  });

  it('starts with an empty composer at position zero', () => {
    expect(blank('x').composer).toEqual({ typed: '', at: 0, offering: [] });
  });

  it('keeps the title it was given', () => {
    expect(blank('aristoxenus').title).toBe('aristoxenus');
  });
});

describe('describeElapsed', () => {
  it('says seconds under a minute', () => {
    expect(describeElapsed(4_200)).toBe('4s');
  });

  it('says minutes and seconds over one', () => {
    expect(describeElapsed(80_000)).toBe('1m 20s');
  });

  it('says 0s rather than nothing at the instant a run starts', () => {
    expect(describeElapsed(0)).toBe('0s');
  });

  it('says a whole minute without a stray zero', () => {
    expect(describeElapsed(60_000)).toBe('1m 0s');
  });

  it('never counts backwards, however the clocks disagree', () => {
    // `since` is a wall clock and the one reading it is a different call.
    // A negative difference is a clock that moved, not a run that started
    // in the future, and "-3s" beside a spinner is worse than "0s".
    expect(describeElapsed(-3_000)).toBe('0s');
  });
});

describe('the live preview', () => {
  it('starts from nothing', () => {
    expect(appended(undefined, 'thinking', 'Let ')).toEqual({
      part: 'thinking',
      text: 'Let ',
    });
  });

  it('appends while the part stays the same', () => {
    const first = appended(undefined, 'thinking', 'Let ');
    expect(appended(first, 'thinking', 'me think.').text).toBe('Let me think.');
  });

  it('starts again when the model stops thinking and begins answering', () => {
    // Two streams, not one. Running them together would produce a sentence
    // neither of them said -- and the moment an answer starts is the moment
    // the reasoning stops being what anybody wants to look at.
    const thought = appended(undefined, 'thinking', 'Let me think.');
    expect(appended(thought, 'answer', 'Bordeaux')).toEqual({
      part: 'answer',
      text: 'Bordeaux',
    });
  });

  it('keeps only the tail, because reasoning runs to thousands of characters', () => {
    let live = appended(undefined, 'thinking', '');
    for (let nth = 0; nth < 200; nth += 1) {
      live = appended(live, 'thinking', `chunk${nth} `);
    }
    expect(live.text.length).toBe(LIVE_TAIL);
    // And it is the END that is kept: a status region shows what the model
    // is saying now, not what it started with.
    expect(live.text.endsWith('chunk199 ')).toBe(true);
  });
});

describe('the phase a model call is in', () => {
  it('reads a call, its thinking, its answer and a tool, in the order a run makes them', () => {
    let phase = phaseAfter(undefined, { kind: 'call' });
    expect(phase).toEqual({ kind: 'processing' });
    phase = phaseAfter(phase, { kind: 'delta', part: 'thinking' });
    expect(phase).toEqual({ kind: 'thinking' });
    phase = phaseAfter(phase, { kind: 'delta', part: 'answer' });
    expect(phase).toEqual({ kind: 'responding' });
    phase = phaseAfter(phase, { kind: 'tool', tool: 'read_file' });
    expect(phase).toEqual({ kind: 'tool', tool: 'read_file' });
    // The next call reads its prompt again, tool result and all.
    expect(phaseAfter(phase, { kind: 'call' })).toEqual({ kind: 'processing' });
  });

  it('is not moved by a heartbeat or anything else that says nothing about the model', () => {
    expect(phaseAfter({ kind: 'thinking' }, { kind: 'other' })).toEqual({
      kind: 'thinking',
    });
    expect(phaseAfter(undefined, { kind: 'other' })).toBeUndefined();
  });

  it('goes straight to responding when every thinking delta was dropped', () => {
    expect(
      phaseAfter({ kind: 'processing' }, { kind: 'delta', part: 'answer' }),
    ).toEqual({ kind: 'responding' });
  });
});
