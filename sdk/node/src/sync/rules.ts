/**
 * The shadow repo's `info/exclude`. Spec §3.3: every hidden path out unless the
 * project's allowlist names it, `.git/` and `.plowshare/` always out, and files
 * skipped for size (or nested repositories) out by exact path. The project's own
 * `.gitignore` files still apply: git reads them from the work tree.
 */
export function excludeLines(
  hidden: readonly string[],
  skipped: readonly string[],
): string[] {
  return [
    '# written by plowshare on every sync; edits are overwritten',
    '.*',
    ...hidden.map(
      (entry) => `!/${entry.split('/').map(escapePattern).join('/')}`,
    ),
    '/.git/',
    '/.plowshare/',
    ...skipped.map(
      (path) => `/${path.split('/').map(escapePattern).join('/')}`,
    ),
  ];
}

export function escapePattern(segment: string): string {
  return segment.replace(/[\\#!*?[\] ]/g, (character) => `\\${character}`);
}
