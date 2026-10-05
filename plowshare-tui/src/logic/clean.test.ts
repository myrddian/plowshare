import { describe, expect, it } from 'vitest';

import { cleaned } from './clean.ts';

describe('text cleaned to draw', () => {
  it('expands a tab to the next four-column stop, per line', () => {
    expect(cleaned('\tat Foo')).toBe('    at Foo');
    expect(cleaned('ab\tc')).toBe('ab  c');
    expect(cleaned('abcd\te')).toBe('abcd    e');
    expect(cleaned('x\n\ty')).toBe('x\n    y');
  });

  it('turns every line ending into one newline', () => {
    expect(cleaned('a\r\nb\rc\nd')).toBe('a\nb\nc\nd');
  });

  it('drops escape sequences whole: colour, cursor moves, a title, a charset switch', () => {
    expect(cleaned('\u001b[31mred\u001b[0m')).toBe('red');
    expect(cleaned('a\u001b[2J\u001b[1;1Hb')).toBe('ab');
    expect(cleaned('\u001b]0;title\u0007after')).toBe('after');
    expect(cleaned('\u001b]8;;https://x\u001b\\link\u001b]8;;\u001b\\')).toBe(
      'link',
    );
    expect(cleaned('\u001b(Bplain\u001b7')).toBe('plain');
    expect(cleaned('\u009b1mbold')).toBe('bold');
    expect(cleaned('\u001b[?1049hscreen')).toBe('screen');
  });

  it('drops every other control character and keeps the text', () => {
    expect(cleaned('a\u0000b\u0007c\u0008d\u007fe\u000bf\u000cg')).toBe(
      'abcdefg',
    );
    expect(cleaned('plain text, ünïcode ✓')).toBe('plain text, ünïcode ✓');
  });
});
