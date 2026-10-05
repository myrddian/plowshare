/**
 * What a union may hold — the client's copy of the hub's own rules (the
 * server's `SyncRules`). `info/exclude` states them to git, but it has the
 * lowest precedence of git's ignore sources, so a project `.gitignore` can
 * re-include a hidden or reserved path; the shadow checks its index against
 * these before every commit instead of trusting the exclude file alone.
 */

/**
 * Unicode default-ignorable code points a hostile path can splice into `.git` or `.plowshare`
 * to slip past a naive string comparison while still rendering — and resolving on disk — as the
 * reserved name once a terminal or filesystem drops them. Soft hyphen, combining grapheme joiner,
 * the zero-width family, the bidi override family, word joiner and friends, and the BOM.
 */
const IGNORABLE =
  /\u00ad|\u034f|[\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]/gu;

/**
 * True for a path segment that names the project's control directories — `.git` or
 * `.plowshare` — on a case-insensitive filesystem (the macOS/Windows default), allowing for a
 * trailing run of dots/spaces NTFS and APFS both ignore, Windows' short 8.3 alias for either name
 * (`git~1`, `plowsh~1`, ...), an NTFS alternate-data-stream suffix (`.git::$DATA` still opens the
 * `.git` file), or Unicode default-ignorable code points spliced into the name. The segment is
 * folded to NFC first, since two visually identical names can be different code point sequences
 * that any later exact match would treat as different strings. Mirrors the server's
 * `SyncRules.reservedSegment`.
 */
export function reservedSegment(segment: string): boolean {
  const stripped = segment
    .normalize('NFC')
    .replace(IGNORABLE, '')
    .replace(/:.*$/s, '');
  const normalized = stripped.toLowerCase().replace(/[. ]+$/, '');
  return (
    normalized === '.git' ||
    normalized === '.plowshare' ||
    /^git~\d+$/.test(normalized) ||
    /^plowsh~\d+$/.test(normalized)
  );
}

/**
 * Whether a repository-relative path may be in a union, with `SyncRules.allowed`'s semantics: no
 * reserved segment at any depth, and every segment that starts with a dot is allowed only when the
 * path up to and including it equals an allowlist entry, or lies under an entry ending in `/`.
 */
export function allowed(path: string, hidden: readonly string[]): boolean {
  const segments = path.split('/');
  if (segments.some(reservedSegment)) {
    return false;
  }
  let prefix = '';
  for (const [i, segment] of segments.entries()) {
    prefix = i === 0 ? segment : `${prefix}/${segment}`;
    if (!segment.startsWith('.')) {
      continue;
    }
    const so = prefix;
    const listed = hidden.some((entry) =>
      entry.endsWith('/') ? `${so}/`.startsWith(entry) : so === entry,
    );
    if (!listed) {
      return false;
    }
  }
  return true;
}
