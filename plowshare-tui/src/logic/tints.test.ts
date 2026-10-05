import { describe, expect, it } from 'vitest';

import {
  fitTinted,
  padTinted,
  plainOf,
  selectedLine,
  shortenMiddle,
  tint,
} from './tints.ts';

describe('tinted lines', () => {
  const line = [
    tint('● ', 'ok'),
    tint('file_read', 'tool'),
    tint('  src/a.ts', 'salient'),
  ];

  it('reads as its plain text', () => {
    expect(plainOf(line)).toBe('● file_read  src/a.ts');
  });

  it('fits a width by cutting the last tints and marking the cut', () => {
    expect(plainOf(fitTinted(line, 12))).toBe('● file_read…');
    expect(fitTinted(line, 12).at(-1)).toEqual({
      text: 'file_read…',
      role: 'tool',
    });
    expect(fitTinted(line, 40)).toEqual(line);
    expect(fitTinted(line, 0)).toEqual([]);
  });

  it('pads to a width and marks a line selected without changing its roles', () => {
    expect(plainOf(padTinted(line, 24))).toBe('● file_read  src/a.ts   ');
    expect(selectedLine(line).map((each) => each.back)).toEqual([
      'selection',
      'selection',
      'selection',
    ]);
    expect(selectedLine(line).map((each) => each.role)).toEqual([
      'ok',
      'tool',
      'salient',
    ]);
  });

  it('shortens a path in the middle so both ends stay readable', () => {
    expect(
      shortenMiddle('plowshare-server/src/main/java/Tokenizer.java', 30),
    ).toBe('plowshare-serve…Tokenizer.java');
    expect(shortenMiddle('short', 30)).toBe('short');
  });
});
