import { describe, expect, it } from 'vitest';

import { DEFAULT_COLUMNS, wrapText } from './wrap.ts';

describe('wrapping a text a person reads whole', () => {
  it('is 80 columns when the surface cannot say', () => {
    expect(DEFAULT_COLUMNS).toBe(80);
    const words = Array.from({ length: 40 }, (_, at) => `word${at}`).join(' ');
    const rows = wrapText(words, DEFAULT_COLUMNS);
    expect(rows.length).toBeGreaterThan(1);
    for (const row of rows) {
      expect(row.length).toBeLessThanOrEqual(80);
    }
    expect(rows.join(' ')).toBe(words);
  });

  it('breaks on word boundaries at the width it is given', () => {
    expect(wrapText('the quick brown fox jumps over the lazy dog', 15)).toEqual(
      ['the quick brown', 'fox jumps over', 'the lazy dog'],
    );
    // A word that ends exactly at the edge stays on its row; the space after it is the break.
    expect(wrapText('abcde fgh', 5)).toEqual(['abcde', 'fgh']);
  });

  it('hard-breaks a word longer than the width, and goes on after it', () => {
    expect(wrapText('see /a/very/long/path/to/a/file.py now', 10)).toEqual([
      'see',
      '/a/very/lo',
      'ng/path/to',
      '/a/file.py',
      'now',
    ]);
    expect(wrapText('x abcdefghijkl', 5)).toEqual([
      'x',
      'abcde',
      'fghij',
      'kl',
    ]);
  });

  it("keeps the text's own line breaks, a blank line among them, and a line's indent", () => {
    expect(
      wrapText('Which database?\n\n- Postgres, for the server\n- SQLite', 80),
    ).toEqual([
      'Which database?',
      '',
      '- Postgres, for the server',
      '- SQLite',
    ]);
    expect(wrapText('built it:\n  12 tests pass', 80)).toEqual([
      'built it:',
      '  12 tests pass',
    ]);
    expect(wrapText('a\r\nb', 80)).toEqual(['a', 'b']);
  });

  it('counts code points, not UTF-16 units, and never goes below one column', () => {
    expect(wrapText('ééééé ééé', 5)).toEqual(['ééééé', 'ééé']);
    expect(wrapText('abc', 0)).toEqual(['a', 'b', 'c']);
  });
});
