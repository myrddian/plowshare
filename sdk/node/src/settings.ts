import { errorCode } from 'plowshare-client-ts/binding/values';
import {
  access,
  lstat,
  mkdir,
  readFile,
  realpath,
  rename,
  rm,
  writeFile,
} from 'node:fs/promises';
import { constants } from 'node:fs';
import { dirname, join } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import {
  CAP_KEYS,
  capRange,
  withCaps,
  withLocalMode,
} from 'plowshare-client-ts/binding/environment';
import { readProjectManifestFile } from './marker.js';
import {
  withProjectCap,
  withProjectLocalMode,
} from 'plowshare-client-ts/binding/project-settings';
export type CapKey = (typeof CAP_KEYS)[number];
export interface CapsFile {
  file: string;
  source?: string;
  hash: string;
  format?: 'json' | 'yaml';
}
export async function readCapsFile(root: string): Promise<CapsFile> {
  if ((await realpath(root)) !== root)
    throw new Error('The project folder moved. Reconnect it.');
  const directory = join(root, '.plowshare');
  try {
    const meta = await lstat(directory);
    if (!meta.isDirectory() || meta.isSymbolicLink())
      throw new Error('Project configuration must be a real directory.');
  } catch (e) {
    if (errorCode(e) !== 'ENOENT') throw e;
  }
  const manifest = await readProjectManifestFile(root);
  if (manifest?.source.trimStart().startsWith('{'))
    return {
      file: manifest.file,
      source: manifest.source,
      format: 'json',
      hash: createHash('sha256')
        .update(`present:${manifest.source}`)
        .digest('hex'),
    };
  const file = join(directory, 'environment.yml');
  let source: string | undefined;
  try {
    const stat = await lstat(file);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 65536)
      throw new Error(
        'Project configuration must be a regular file of at most 64 KiB.',
      );
    source = await readFile(file, 'utf8');
  } catch (e) {
    if (errorCode(e) !== 'ENOENT') throw e;
  }
  return {
    file,
    ...(source === undefined ? {} : { source }),
    hash: createHash('sha256')
      .update(source === undefined ? 'absent' : `present:${source}`)
      .digest('hex'),
  };
}
export function capPreview(before: CapsFile, key: CapKey, value: number) {
  if (!CAP_KEYS.includes(key)) throw new Error('Choose a cap setting.');
  const { least, most } = capRange(key);
  if (!Number.isSafeInteger(value) || value < least || value > most)
    throw new Error(`${key} must be an integer from ${least} to ${most}.`);
  return before.format === 'json'
    ? withProjectCap(before.source!, key, value)
    : withCaps(before.source, key, value);
}
export async function saveCapsFile(
  root: string,
  before: CapsFile,
  key: CapKey,
  value: number,
) {
  return saveSettingsFile(root, before, capPreview(before, key, value));
}
export function localModePreview(before: CapsFile, mode: string): string {
  return before.format === 'json'
    ? withProjectLocalMode(before.source!, mode)
    : withLocalMode(before.source, mode);
}
export async function saveSettingsFile(
  root: string,
  before: CapsFile,
  source: string,
) {
  if (Buffer.byteLength(source, 'utf8') > 65536)
    throw new Error('Project configuration exceeds 64 KiB.');
  const current = await readCapsFile(root);
  if (current.hash !== before.hash || current.file !== before.file)
    throw new Error('Cap settings changed. Review the current file again.');
  if (current.source !== undefined) {
    if (((await lstat(current.file)).mode & 0o222) === 0)
      throw new Error('Project configuration is read-only.');
    await access(current.file, constants.W_OK);
  }
  const directory = dirname(before.file);
  await mkdir(directory, { recursive: true });
  const temporary = join(directory, `.environment-${randomUUID()}.tmp`);
  try {
    await writeFile(temporary, source, { mode: 0o600, flag: 'wx' });
    const latest = await readCapsFile(root);
    if (latest.hash !== before.hash || latest.file !== before.file)
      throw new Error('Cap settings changed. Review them again.');
    await rename(temporary, before.file);
  } finally {
    await rm(temporary, { force: true });
  }
  return source;
}
