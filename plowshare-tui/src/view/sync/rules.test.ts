import { describe, expect, it } from 'vitest';
import { escapePattern, excludeLines } from './rules.ts';

describe('excludeLines', () => {
  it('leaves out every hidden path, then lets the allowlist back in, then never .git or .plowshare', () => {
    expect(excludeLines(['.github/', '.eslintrc'], [])).toEqual([
      '# written by plowshare on every sync; edits are overwritten',
      '.*',
      '!/.github/',
      '!/.eslintrc',
      '/.git/',
      '/.plowshare/',
    ]);
  });

  it('leaves out skipped files by exact path', () => {
    expect(
      excludeLines([], ['assets/big video.mp4', 'odd[1].bin']).slice(-2),
    ).toEqual(['/assets/big\\ video.mp4', '/odd\\[1\\].bin']);
  });

  it('an allowlist entry cannot re-include .git or .plowshare', () => {
    const lines = excludeLines(['.git/', '.plowshare/'], []);
    expect(lines.indexOf('/.git/')).toBeGreaterThan(lines.indexOf('!/.git/'));
    expect(lines.indexOf('/.plowshare/')).toBeGreaterThan(
      lines.indexOf('!/.plowshare/'),
    );
  });
});

describe('escapePattern', () => {
  it('escapes what gitignore would read as a pattern', () => {
    expect(escapePattern('#a!b*c?d[e]f g\\h')).toBe(
      '\\#a\\!b\\*c\\?d\\[e\\]f\\ g\\\\h',
    );
  });
});
