import { renderToString } from 'ink';
import { describe, expect, it } from 'vitest';

import { tint } from '../../logic/tints.ts';
import { DEFAULT_LOOK } from '../look.ts';
import { roleColour, tintedOf } from './tinted.ts';

describe('a tinted line, painted', () => {
  const palette = DEFAULT_LOOK.palette;

  it('maps every role to a token of the palette', () => {
    expect(roleColour('ok', palette)).toEqual(palette.ok);
    expect(roleColour('fail', palette)).toEqual(palette.fail);
    expect(roleColour('salient', palette)).toEqual(palette.text);
    expect(roleColour('rail', palette)).toEqual(palette.border);
    expect(roleColour('selected', palette)).toEqual(palette.accent);
  });

  it('reads as its text, uncoloured and coloured alike', () => {
    const line = [
      tint('● ', 'ok'),
      tint('run', 'tool'),
      tint('  ✗ exit 1', 'fail'),
    ];
    expect(renderToString(tintedOf(line, undefined, 'k'))).toBe(
      '● run  ✗ exit 1',
    );
    expect(renderToString(tintedOf(line, palette, 'k'))).toContain('run');
  });
});
