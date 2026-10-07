import { isObject } from './values.ts';

/** The alias resolves on the addressed host; an empty path names that store's root. */
export interface FileStoreReference {
  readonly store: string;
  readonly path: string;
}

export function fileStoreReference(
  value: unknown,
): value is FileStoreReference {
  if (
    !isObject(value) ||
    Object.keys(value).some((key) => key !== 'store' && key !== 'path') ||
    typeof value.store !== 'string' ||
    !/^[a-z][a-z0-9_-]{0,63}$/.test(value.store) ||
    typeof value.path !== 'string' ||
    value.path.length > 2048 ||
    value.path.includes('\\') ||
    value.path.includes(':')
  )
    return false;
  return (
    value.path === '' ||
    value.path.split('/').every(
      (segment) =>
        segment !== '' &&
        segment !== '.' &&
        segment !== '..' &&
        segment.toLowerCase() !== '.git' &&
        !Array.from(segment).some((character) => {
          const code = character.charCodeAt(0);
          return code < 32 || (code >= 127 && code <= 159);
        }),
    )
  );
}

/** User-entered alias/path references, shared by client forms and configuration boundaries. */
export function parseFileStoreReference(value: string): FileStoreReference {
  const separator = value.indexOf('/');
  const result = {
    store: separator < 0 ? value : value.slice(0, separator),
    path: separator < 0 ? '' : value.slice(separator + 1),
  };
  if (!fileStoreReference(result))
    throw new Error(
      'Use a FileStore alias followed by an optional canonical relative path.',
    );
  return result;
}
