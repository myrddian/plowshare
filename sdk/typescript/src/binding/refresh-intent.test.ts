import { describe, expect, it } from 'vitest';
import { validateRefreshIntent } from './refresh-intent.ts';

describe('refresh intent boundary', () => {
  it('rejects malformed and non-string intents without coercion', () => {
    const canonical = 'f612b939-aa97-4938-b55d-b3c25f0fdc43';
    expect(() => validateRefreshIntent(canonical)).not.toThrow();
    for (const value of [
      undefined,
      null,
      42,
      [],
      { toString: () => canonical },
      '',
      canonical.toUpperCase(),
      ' ' + canonical,
      canonical + ',' + canonical,
      '00000000-0000-0000-0000-000000000000',
    ])
      expect(() => validateRefreshIntent(value)).toThrow(
        'Invalid refresh intent',
      );
  });
});
