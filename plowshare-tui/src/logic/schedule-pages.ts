/** Public schedule listings are bounded. Complete listings and absence decisions must
 * advance through every relevant page, rather than interpreting page one as complete.
 * Reads remain explicit and never replay a mutation. A non-progressing server fails closed. */
export const SCHEDULE_PAGE_LIMIT = 100;
const MAX_SCHEDULE_KEYS = 100_000;
export async function* schedulePages<T>(
  read: (after?: string) => Promise<readonly T[]>,
  key: (row: T) => string,
): AsyncGenerator<readonly T[]> {
  let after: string | undefined;
  const seen = new Set<string>();
  for (let page = 0; page <= MAX_SCHEDULE_KEYS / SCHEDULE_PAGE_LIMIT; page++) {
    const rows = await read(after);
    if (rows.length > SCHEDULE_PAGE_LIMIT)
      throw new Error('The server returned an oversized schedule page.');
    if (seen.size + rows.length > MAX_SCHEDULE_KEYS)
      throw new Error('The schedule listing exceeded its bounded key budget.');
    // Check every key before yielding any row. A repeated non-final key is just
    // as incomplete as a repeated cursor; callers must never infer absence from it.
    for (const row of rows) {
      const name = key(row);
      if (
        typeof name !== 'string' ||
        !name.trim() ||
        name.length > 512 ||
        seen.has(name)
      )
        throw new Error(
          'The server schedule page did not advance with unique valid keys.',
        );
      seen.add(name);
    }
    const last = rows.at(-1);
    const next = last === undefined ? undefined : key(last);
    yield rows;
    if (rows.length < SCHEDULE_PAGE_LIMIT) return;
    if (next === undefined)
      throw new Error('The server schedule page has no cursor.');
    after = next;
  }
  throw new Error('The schedule listing exceeded its bounded page budget.');
}
