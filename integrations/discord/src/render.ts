/** Discord counts UTF-16 code units. Never split a surrogate pair or silently truncate a result. */
export function chunks(text: string): readonly string[] {
  if (!text.trim()) return ['Plowshare returned an empty reply.'];
  if (text.length > 40000)
    return [
      'Plowshare returned a result larger than this adapter can deliver (40,000 characters). Inspect the retained task using the integration’s SDK identity.',
    ];
  const result: string[] = [];
  let chunk = '';
  for (const character of text) {
    if (chunk.length + character.length > 2000) {
      result.push(chunk);
      chunk = '';
    }
    chunk += character;
  }
  if (chunk) result.push(chunk);
  return result;
}
