import { isObject } from 'plowshare-client-ts/binding/values';
import { isAbsolute, normalize } from 'node:path';

export interface FileStoreDefault {
  alias: string;
  root: string;
}
export interface FileStoreConfiguration {
  version: 1;
  defaultStore: string;
  fileStores: Readonly<Record<string, { readonly root: string }>>;
}
export function fileStoreAlias(value: unknown): string {
  if (typeof value !== 'string' || !/^[a-z][a-z0-9_-]{0,63}$/.test(value))
    throw new Error(
      'Use a FileStore alias starting with a lowercase letter, followed by letters, digits, underscores or hyphens (at most 64 characters).',
    );
  return value;
}
export function fileStoreRoot(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !isAbsolute(value) ||
    value.length > 4096 ||
    hasPathControls(value)
  )
    throw new Error(
      'A FileStore root must be an absolute host directory without control characters.',
    );
  return normalize(value);
}
function fields(
  value: unknown,
  allowed: readonly string[],
): asserts value is Record<string, unknown> {
  if (
    !isObject(value) ||
    Object.keys(value).some((key) => !allowed.includes(key))
  )
    throw new Error('FileStore configuration contains invalid fields.');
}
export function decodeFileStoreDefault(value: unknown): FileStoreDefault {
  fields(value, ['alias', 'root']);
  return {
    alias: fileStoreAlias(value.alias),
    root: fileStoreRoot(value.root),
  };
}
/** Validate the entire registry before creating any directories. */
export function decodeFileStoreConfiguration(
  value: unknown,
): FileStoreConfiguration {
  fields(value, ['version', 'defaultStore', 'fileStores']);
  if (value.version !== 1 || !isObject(value.fileStores))
    throw new Error(
      'Use FileStore configuration version 1 with a fileStores object.',
    );
  const entries = Object.entries(value.fileStores);
  if (!entries.length || entries.length > 100)
    throw new Error('Configure between 1 and 100 FileStores.');
  const rows = entries.map(([alias, raw]) => {
    fileStoreAlias(alias);
    fields(raw, ['root']);
    return [alias, { root: fileStoreRoot(raw.root) }] as const;
  });
  const defaultStore = fileStoreAlias(value.defaultStore);
  if (!rows.some(([alias]) => alias === defaultStore))
    throw new Error('defaultStore must name a configured FileStore.');
  return { version: 1, defaultStore, fileStores: Object.fromEntries(rows) };
}

/** Paths retain spaces and Unicode; only terminal/OS control characters are rejected. */
export function hasPathControls(value: string): boolean {
  return Array.from(value).some(
    (character) =>
      character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127,
  );
}
