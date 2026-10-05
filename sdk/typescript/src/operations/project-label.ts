/** Recognize the server-owned identity of a client-only project. */
export function clientProject(project: string): boolean {
  return project.startsWith('client:');
}

/**
 * Display a client-private project's encoded name without depending on Node or a browser.
 * Ordinary and legacy names remain unchanged. Malformed identities remain visible as supplied.
 */
export function projectLabel(project: string): string {
  if (
    !clientProject(project) ||
    project.indexOf(':') === project.lastIndexOf(':')
  )
    return project;
  const encoded = project
    .slice(project.lastIndexOf(':') + 1)
    .replace(/=+$/, '');
  if (!encoded || !/^[A-Za-z0-9_-]+$/.test(encoded) || encoded.length % 4 === 1)
    return project;
  const alphabet =
    'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  let bits = 0,
    available = 0,
    escaped = '';
  for (const character of encoded) {
    bits = (bits << 6) | alphabet.indexOf(character);
    available += 6;
    if (available >= 8) {
      available -= 8;
      escaped +=
        '%' + ((bits >>> available) & 255).toString(16).padStart(2, '0');
      bits &= (1 << available) - 1;
    }
  }
  // ECMAScript percent decoding handles UTF-8 without Buffer, atob or TextDecoder.
  try {
    return decodeURIComponent(escaped);
  } catch (error) {
    if (error instanceof URIError) return project;
    throw error;
  }
}
