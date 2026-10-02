import { lstat, mkdir, readFile, realpath, rename, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { CAP_KEYS, capRange, withCaps } from 'plowshare-client-ts/binding/environment';
export type CapKey = typeof CAP_KEYS[number];
export interface CapsFile { file: string; source?: string; hash: string }
export async function readCapsFile(root: string): Promise<CapsFile> {
  if (await realpath(root) !== root) throw new Error('The project folder moved. Reconnect it.');
  const directory = join(root, '.plowshare');
  try { const meta = await lstat(directory); if (!meta.isDirectory() || meta.isSymbolicLink()) throw new Error('Project configuration must be a real directory.'); }
  catch (e) { if ((e as NodeJS.ErrnoException).code !== 'ENOENT') throw e; }
  const file = join(directory, 'environment.yml'); let source: string | undefined;
  try { const stat = await lstat(file); if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 65536) throw new Error('Project configuration must be a regular file of at most 64 KiB.'); source = await readFile(file, 'utf8'); }
  catch (e) { if ((e as NodeJS.ErrnoException).code !== 'ENOENT') throw e; }
  return { file, ...(source === undefined ? {} : { source }), hash: createHash('sha256').update(source === undefined ? 'absent' : `present:${source}`).digest('hex') };
}
export function capPreview(before: CapsFile, key: CapKey, value: number) {
  if (!CAP_KEYS.includes(key)) throw new Error('Choose a cap setting.');
  const { least, most } = capRange(key);
  if (!Number.isSafeInteger(value) || value < least || value > most) throw new Error(`${key} must be an integer from ${least} to ${most}.`);
  return withCaps(before.source, key, value);
}
export async function saveCapsFile(root: string, before: CapsFile, key: CapKey, value: number) {
  const source = capPreview(before, key, value), current = await readCapsFile(root);
  if (current.hash !== before.hash || current.file !== before.file) throw new Error('Cap settings changed. Review the current file again.');
  const directory = join(root, '.plowshare'); await mkdir(directory, { recursive: true });
  const temporary = join(directory, `.environment-${randomUUID()}.tmp`);
  try {
    await writeFile(temporary, source, { mode: 0o600, flag: 'wx' });
    const latest = await readCapsFile(root);
    if (latest.hash !== before.hash) throw new Error('Cap settings changed. Review them again.');
    await rename(temporary, before.file);
  } finally { await rm(temporary, { force: true }); }
  return source;
}
