import { errorCode } from 'plowshare-client-ts/binding/values';
import {
  mkdir,
  lstat,
  realpath,
  readFile,
  writeFile,
  readdir,
  stat,
} from 'node:fs/promises';
import { join, relative, isAbsolute, sep } from 'node:path';
import { homedir } from 'node:os';

export const PERSONAL_SECTIONS = [
  'In',
  'Out',
  'Resources',
  'Archive',
  'Planning',
  'Bots',
] as const;
export type PersonalSection = (typeof PERSONAL_SECTIONS)[number];
export interface PersonalEntry {
  name: string;
  path: string;
  directory: boolean;
}

/** A fixed mount belongs to one server/account. Never combine another account's replica with it. */
export async function personalDirectory(
  server: string,
  account: string,
  project: string,
  home = homedir(),
): Promise<string> {
  const root = join(home, '.plowshare', 'personal');
  await mkdir(root, { recursive: true, mode: 0o700 });
  if ((await lstat(root)).isSymbolicLink())
    throw new Error('Personal space must be a directory, not a symbolic link');
  const canonical = await realpath(root);
  const config = join(canonical, '.plowshare');
  try {
    if ((await lstat(config)).isSymbolicLink())
      throw new Error(
        'Personal metadata must be a directory, not a symbolic link',
      );
  } catch (error) {
    if (errorCode(error) !== 'ENOENT') throw error;
  }
  const claim = join(config, 'personal.json');
  try {
    if ((await lstat(claim)).isSymbolicLink())
      throw new Error(
        'Personal ownership metadata must not be a symbolic link',
      );
  } catch (error) {
    if (errorCode(error) !== 'ENOENT') throw error;
  }
  const expected = { server: server.replace(/\/$/, ''), account, project };
  let owned: unknown;
  try {
    owned = JSON.parse(await readFile(claim, 'utf8'));
  } catch (error) {
    if (errorCode(error) !== 'ENOENT') throw error;
    if ((await readdir(canonical)).length)
      throw new Error(
        'The personal directory has unclaimed files. Move it aside before connecting this account.',
        { cause: error },
      );
    await mkdir(config, { mode: 0o700 });
    await writeFile(claim, JSON.stringify(expected) + '\n', {
      flag: 'wx',
      mode: 0o600,
    });
    owned = expected;
  }
  const held = owned as Record<string, unknown>;
  if (
    !held ||
    held.server !== expected.server ||
    held.account !== account ||
    held.project !== project
  ) {
    throw new Error(
      'The personal directory belongs to another server or account. Move it aside before connecting this account.',
    );
  }
  return canonical;
}

/** Canonical containment is checked on every read, including symlinks inside a section. */
export async function readPersonal(
  root: string,
  section: PersonalSection,
  path = section as string,
): Promise<{
  path: string;
  entries: PersonalEntry[];
  text?: string;
  note?: string;
}> {
  if (
    !PERSONAL_SECTIONS.includes(section) ||
    !(path === section || path.startsWith(section + '/'))
  )
    throw new Error('Choose a personal space section');
  const canonical = await realpath(root);
  const file = await realpath(join(canonical, path));
  const rel = relative(canonical, file);
  if (rel === '..' || rel.startsWith('..' + sep) || isAbsolute(rel))
    throw new Error('The path leaves personal space');
  if (!(rel === section || rel.startsWith(section + sep)))
    throw new Error('The path leaves the selected personal section');
  const info = await stat(file);
  if (info.isDirectory()) {
    const rows = await readdir(file, { withFileTypes: true });
    return {
      path,
      entries: rows
        .filter((row) => !row.name.startsWith('.'))
        .map((row) => ({
          name: row.name,
          path: path + '/' + row.name,
          directory: row.isDirectory(),
        }))
        .sort(
          (a, b) =>
            Number(b.directory) - Number(a.directory) ||
            a.name.localeCompare(b.name),
        ),
    };
  }
  if (info.size > 256 * 1024)
    return { path, entries: [], note: 'This file is too large to preview.' };
  const bytes = await readFile(file);
  if (bytes.includes(0))
    return { path, entries: [], note: 'Preview is available for text files.' };
  return {
    path,
    entries: [],
    text: new TextDecoder('utf-8', { fatal: true }).decode(bytes),
  };
}
