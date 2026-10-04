import type { InformationRow } from 'plowshare-client-ts/operations/information-replies';

export const MANUAL_TAG = 'plowshare-manual';
export const MANUAL_INDEX = '00-index';

/** Stable chapter identity is independent of the server's immutable revision UUID. */
export function manualChapterTag(chapter: string): string {
  if (!/^[a-z0-9-]{1,48}$/.test(chapter)) throw new Error('Choose a valid manual chapter.');
  return `manual-chapter-${chapter}`;
}

/** The supplied resource name stays fixed when its title, edition or content changes. */
export function manualSourceName(chapter: string): string {
  manualChapterTag(chapter);
  return `Plowshare manual / ${chapter}.md`;
}

/** Resolve one current shared revision. Never guess between competing installations. */
export function manualRevision(rows: readonly InformationRow[], chapter: string): string | undefined {
  const tag = manualChapterTag(chapter);
  if (rows.length > 1) {
    throw new Error('More than one shared manual supplies this chapter. Ask your server administrator to select one publication.');
  }
  const row = rows[0];
  if (!row) return undefined;
  if (row.kind !== 'source' || row.source_name !== manualSourceName(chapter) || typeof row.id !== 'string'
      || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(row.id)
      || !Array.isArray(row.tags) || !row.tags.includes(MANUAL_TAG) || !row.tags.includes(tag)) {
    throw new Error('The server returned a different manual chapter. Refresh before opening it.');
  }
  return row.id;
}
