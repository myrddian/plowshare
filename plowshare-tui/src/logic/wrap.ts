/*
 * WRAPPING A TEXT A PERSON READS WHOLE. The record keeps one short line per event and, for the few
 * a person has to read in full — a question, its answer, a stall, a run's ending — the whole text
 * beside it (V64, `RecordRow.body`). The surfaces draw one row per tinted line and truncate the
 * rest, so a body is wrapped here, before it is tinted: to the terminal's width when the surface
 * knows it, and to {@link DEFAULT_COLUMNS} when it does not.
 */

/** The width a body is wrapped to when the surface cannot say how wide it is. */
export const DEFAULT_COLUMNS = 80;

/**
 * `text` as rows no wider than `width`: broken at spaces, a word wider than a row broken across
 * as many as it takes, and the text's own line breaks kept — a blank line stays a blank row, and a
 * line's leading indent stays on its first row. The spaces a break falls on are dropped. Widths
 * are code points, as `tints.ts` counts them; a width below one is one.
 */
export function wrapText(text: string, width: number): string[] {
  const room = Math.max(1, Math.floor(width));
  const rows: string[] = [];
  for (const line of text.replace(/\r\n?/gu, '\n').split('\n')) {
    const indent = /^ */u.exec(line)?.[0] ?? '';
    const words = line
      .slice(indent.length)
      .split(/ +/u)
      .filter((word) => word !== '');
    if (words.length === 0) {
      rows.push('');
      continue;
    }
    // An indent as wide as the row would leave no room for a word after it.
    let row = indent.length < room ? indent : '';
    let used = row.length;
    let empty = true;
    for (const word of words) {
      let points = [...Array.from(word)];
      const needed = empty ? points.length : 1 + points.length;
      if (used + needed <= room) {
        row += empty ? word : ` ${word}`;
        used += needed;
        empty = false;
        continue;
      }
      if (!empty) {
        rows.push(row);
        row = '';
        used = 0;
      }
      while (used + points.length > room) {
        const taken = room - used;
        rows.push(row + points.slice(0, taken).join(''));
        points = points.slice(taken);
        row = '';
        used = 0;
      }
      row += points.join('');
      used += points.length;
      empty = false;
    }
    rows.push(row);
  }
  return rows;
}
