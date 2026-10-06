import { errorCode } from 'plowshare-client-ts/binding/values';
import {
  mkdir,
  lstat,
  realpath,
  readFile,
  writeFile,
  readdir,
  stat,
  rename,
  mkdtemp,
} from 'node:fs/promises';
import { join, relative, isAbsolute, sep } from 'node:path';
import {
  canonicalServer,
  connectionDirectory,
  localLock,
  privateDirectory,
  userConfigDirectory,
} from './connections.ts';

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

/** The default mount and a persistent warning naming preserved recovery data. */
export interface PersonalStore {
  root: string;
  warning?: string;
}

class DamagedPersonalStore extends Error {}

/** Resolve Personal without replacing invalid data; callers needing recovery use preparePersonalStore. */
export async function personalDirectory(
  server: string,
  account: string,
  project: string,
  home?: string,
): Promise<string> {
  return (await prepareStore(server, account, project, home, false)).root;
}

/**
 * Open the connection's default Personal store. Structurally damaged or unreadable
 * local data is renamed into a unique dated recovery directory before creating a
 * fresh store. No old files are deleted, followed through symlinks, or imported
 * into the replacement. Foreign ownership and non-storage failures still refuse.
 *
 * Initialization and recovery share cross-process migration and connection
 * store locks; synchronization uses the same store lock outside the mount.
 * Recovery refuses an existing sync lock. Preserved directories record completed
 * renames, so retry/restart reports the old location and cannot reimport legacy
 * data after a partial recreation. Callers display the returned warning.
 */
export async function preparePersonalStore(
  server: string,
  account: string,
  project: string,
  home?: string,
): Promise<PersonalStore> {
  return prepareStore(server, account, project, home, true);
}

/** Explicitly preserve and recreate the selected store, including a valid but unusable local replica. */
export async function recreatePersonalStore(
  server: string,
  account: string,
  project: string,
  home?: string,
): Promise<PersonalStore> {
  return prepareStore(server, account, project, home, true, true);
}

async function prepareStore(
  server: string,
  account: string,
  project: string,
  home: string | undefined,
  recover: boolean,
  recreate = false,
): Promise<PersonalStore> {
  const storage =
    home === undefined ? userConfigDirectory() : join(home, '.plowshare');
  const scope = connectionDirectory(server, account, storage);
  const expected = { server: canonicalServer(server), account, project };
  await privateDirectory(storage);
  await privateDirectory(join(storage, 'connections'));
  await privateDirectory(scope);
  return localLock(storage, 'personal-migration', () =>
    localLock(scope, 'personal-store', async () => {
      const root = join(scope, 'personal');
      const legacy = join(storage, 'personal');
      let preserved = await latestRecovery(scope);
      let exists = true;
      try {
        await lstat(root);
      } catch (error) {
        if (errorCode(error) !== 'ENOENT') throw error;
        exists = false;
      }
      if (
        !exists &&
        !recreate &&
        !preserved &&
        (await ownsLegacyPersonal(legacy, server, account, project))
      )
        await rename(legacy, root);
      let canonical: string;
      try {
        if (recreate && exists)
          throw new DamagedPersonalStore('Personal recreation requested');
        canonical = await claimPersonal(root, expected);
      } catch (error) {
        const code = errorCode(error);
        if (
          !recover ||
          !(
            error instanceof DamagedPersonalStore ||
            code === 'EACCES' ||
            code === 'EPERM'
          )
        )
          throw error;
        await requireIdleStore(root);
        // mkdtemp reserves a private unique parent: rename can never overwrite an
        // existing recovery checkout, even when several resets share a timestamp.
        const date = new Date().toISOString().replace(/[:.]/g, '-');
        const recovery = await mkdtemp(
          join(scope, `personal-connection-recovery-${date}-`),
        );
        preserved = join(recovery, 'personal');
        try {
          await rename(root, preserved);
        } catch (failed) {
          throw new Error(
            'Personal recovery could not preserve the old store; recreation was not attempted.',
            { cause: failed },
          );
        }
        try {
          canonical = await claimPersonal(root, expected);
        } catch (failed) {
          throw new Error(
            `Personal could not be recreated. The previous store is intact at ${preserved}. Retry after correcting local storage access.`,
            { cause: failed },
          );
        }
      }
      return {
        root: canonical,
        ...(preserved
          ? {
              warning: `Personal store was recreated. The previous store is intact at ${preserved}.`,
            }
          : {}),
      };
    }),
  );
}

/** Check structure before reading metadata; initialization writes only to an empty store. */
async function claimPersonal(
  root: string,
  expected: { server: string; account: string; project: string },
): Promise<string> {
  try {
    const info = await lstat(root);
    if (!info.isDirectory() || info.isSymbolicLink())
      throw new DamagedPersonalStore(
        'Personal space must be a directory, not a symbolic link',
      );
  } catch (error) {
    if (errorCode(error) !== 'ENOENT') throw error;
    await mkdir(root, { mode: 0o700 });
  }
  const canonical = await realpath(root);
  const config = join(canonical, '.plowshare');
  try {
    const info = await lstat(config);
    if (!info.isDirectory() || info.isSymbolicLink())
      throw new DamagedPersonalStore(
        'Personal metadata must be a real directory, not a symbolic link',
      );
  } catch (error) {
    if (errorCode(error) !== 'ENOENT') throw error;
  }
  const claim = join(config, 'personal.json');
  let owned: unknown;
  try {
    const info = await lstat(claim);
    if (!info.isFile() || info.isSymbolicLink() || info.size > 2_000_000)
      throw new DamagedPersonalStore(
        'Personal ownership metadata must be a regular file within its size limit',
      );
    owned = JSON.parse(await readFile(claim, 'utf8'));
  } catch (error) {
    if (error instanceof SyntaxError)
      throw new DamagedPersonalStore('Personal ownership metadata is corrupt', {
        cause: error,
      });
    if (errorCode(error) !== 'ENOENT') throw error;
    if ((await readdir(canonical)).length)
      throw new DamagedPersonalStore(
        'The personal directory has unclaimed files',
      );
    await mkdir(config, { mode: 0o700 });
    await writeFile(claim, JSON.stringify(expected) + '\n', {
      flag: 'wx',
      mode: 0o600,
    });
    owned = expected;
  }
  if (
    !owned ||
    typeof owned !== 'object' ||
    !('server' in owned) ||
    typeof owned.server !== 'string' ||
    !('account' in owned) ||
    typeof owned.account !== 'string' ||
    !('project' in owned) ||
    typeof owned.project !== 'string'
  )
    throw new DamagedPersonalStore('Personal ownership metadata is invalid');
  let origin: string;
  try {
    origin = canonicalServer(owned.server);
  } catch (error) {
    throw new DamagedPersonalStore('Personal ownership origin is invalid', {
      cause: error,
    });
  }
  if (
    origin !== expected.server ||
    owned.account !== expected.account ||
    owned.project !== expected.project
  )
    throw new Error(
      'The personal directory belongs to another server or account. Its ownership must be resolved before connecting.',
    );
  return canonical;
}

/** Do not follow a damaged root or metadata link, and never move a checkout with a sync lock. */
async function requireIdleStore(root: string): Promise<void> {
  const directory = await lstat(root);
  if (!directory.isDirectory() || directory.isSymbolicLink()) return;
  const config = join(root, '.plowshare');
  try {
    const metadata = await lstat(config);
    if (!metadata.isDirectory() || metadata.isSymbolicLink()) return;
    await lstat(join(config, 'sync.lock'));
  } catch (error) {
    if (errorCode(error) === 'ENOENT') return;
    throw error;
  }
  throw new Error(
    'Personal needs recovery but synchronization is locked. Close clients using this store and inspect an interrupted writer before retrying.',
  );
}

/** A backup entry proves the rename landed, including if creating its replacement failed. */
async function latestRecovery(scope: string): Promise<string | undefined> {
  const names = (await readdir(scope))
    .filter((name) =>
      /^personal-connection-recovery-\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}-\d{3}Z-[A-Za-z0-9]+$/.test(
        name,
      ),
    )
    .sort()
    .reverse();
  for (const name of names) {
    const directory = join(scope, name);
    const info = await lstat(directory);
    if (!info.isDirectory() || info.isSymbolicLink()) continue;
    const preserved = join(directory, 'personal');
    try {
      await lstat(preserved);
      return preserved;
    } catch (error) {
      if (errorCode(error) !== 'ENOENT') throw error;
    }
  }
  return undefined;
}

/** Never infer ownership from a folder name or follow legacy metadata symlinks. */
async function ownsLegacyPersonal(
  root: string,
  server: string,
  account: string,
  project: string,
): Promise<boolean> {
  const metadata = join(root, '.plowshare');
  const claim = join(metadata, 'personal.json');
  let source: string;
  try {
    for (const directory of [root, metadata]) {
      const info = await lstat(directory);
      if (!info.isDirectory() || info.isSymbolicLink()) return false;
    }
    const info = await lstat(claim);
    if (!info.isFile() || info.isSymbolicLink() || info.size > 2_000_000)
      return false;
    source = await readFile(claim, 'utf8');
  } catch (error) {
    // Legacy migration is optional. Missing or unreadable ownership authorizes
    // no move; leave the source intact and initialize the connection's store.
    const code = errorCode(error);
    if (code === 'ENOENT' || code === 'EACCES' || code === 'EPERM')
      return false;
    throw error;
  }
  let owner: unknown;
  try {
    owner = JSON.parse(source);
  } catch (error) {
    // Invalid legacy metadata cannot authorize moving any of its files.
    if (error instanceof SyntaxError) return false;
    throw error;
  }
  if (
    !owner ||
    typeof owner !== 'object' ||
    !('server' in owner) ||
    typeof owner.server !== 'string' ||
    !('account' in owner) ||
    owner.account !== account ||
    !('project' in owner) ||
    owner.project !== project
  )
    return false;
  let origin: string;
  try {
    origin = canonicalServer(owner.server);
  } catch {
    // This is a parser boundary: a malformed legacy origin proves no ownership.
    return false;
  }
  return origin === canonicalServer(server);
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
