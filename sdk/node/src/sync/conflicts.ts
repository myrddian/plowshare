import { errorCode } from 'plowshare-client-ts/binding/values';
import {
  lstat,
  mkdir,
  open,
  readFile,
  rm,
  stat,
  writeFile,
} from 'node:fs/promises';
import { constants } from 'node:fs';
import { createHash } from 'node:crypto';
import { basename, dirname, isAbsolute, join } from 'node:path';
import type { ConflictRow } from 'plowshare-client-ts/operations/union';
import { reservedSegment } from './fence.ts';
import { GitFailed } from './git.ts';
import { CONFLICT_REF, type Shadow } from './shadow.ts';

/**
 * Resolving a set-aside conflict on this machine. Spec §6.4 and §11.5: the
 * marked-up file for a hand merge is written under `.plowshare/conflicts/<n>/`,
 * outside the synced tree, and never into the working file.
 *
 * `row.path` arrives in a frame from the server and is untrusted: every
 * working-tree path built from it is validated by `contained` (no `..`,
 * no absolute path, no reserved segment) and checked for symlinks along the
 * way before it is written or removed.
 */

export function mergeFileOf(root: string, row: ConflictRow): string {
  const base = basename(row.path);
  if (base === '' || base === '.' || base === '..') {
    throw new Error(
      `refusing a conflict path outside the project: ${row.path}`,
    );
  }
  return join(root, '.plowshare', 'conflicts', String(row.n), base);
}

export const MAX_PREVIEW_BYTES = 512 * 1024;
export interface ConflictText {
  readonly text: string | null;
  readonly bytes: number;
  readonly missing: boolean;
}
export interface ConflictPreview {
  readonly row: ConflictRow;
  readonly mine: ConflictText;
  readonly base: ConflictText;
  readonly server: ConflictText;
  readonly localHash: string;
  readonly merged?: string;
}

/** Bounded text for display; hash all local bytes so a later decision can reject changed files. */
export async function localVersion(
  shadow: Shadow,
  row: ConflictRow,
): Promise<ConflictText & { hash: string }> {
  const path = contained(shadow.paths.root, row.path);
  await assertNoSymlinks(shadow.paths.root, row.path);
  let file;
  try {
    file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  } catch (error) {
    if (errorCode(error) !== 'ENOENT') throw error;
    return { text: '', bytes: 0, missing: true, hash: 'missing' };
  }
  try {
    if (!(await file.stat()).isFile())
      throw new Error('A conflict must name a regular file.');
    const hash = createHash('sha256'),
      parts: Buffer[] = [];
    let bytes = 0;
    for await (const part of file.createReadStream({ autoClose: false })) {
      if (!Buffer.isBuffer(part) && typeof part !== 'string')
        throw new Error('Unreadable conflict stream.');
      const buffer = Buffer.from(part);
      hash.update(buffer);
      bytes += buffer.length;
      if (bytes <= MAX_PREVIEW_BYTES) parts.push(buffer);
    }
    return {
      text:
        bytes > MAX_PREVIEW_BYTES
          ? null
          : Buffer.concat(parts).toString('utf8'),
      bytes,
      missing: false,
      hash: hash.digest('hex'),
    };
  } finally {
    await file.close();
  }
}

function displayText(bytes: Buffer): string | null {
  if (bytes.includes(0)) return null;
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    return null;
  }
}

async function version(
  shadow: Shadow,
  oid: string | undefined,
): Promise<ConflictText> {
  if (oid === undefined) return { text: '', bytes: 0, missing: true };
  const size = Number(await shadow.git.ok(['cat-file', '-s', oid]));
  if (!Number.isSafeInteger(size) || size < 0)
    throw new Error('The conflict blob size is invalid.');
  return {
    text:
      size > MAX_PREVIEW_BYTES ? null : displayText(await blob(shadow, oid)),
    bytes: size,
    missing: false,
  };
}

export async function inspectConflict(
  shadow: Shadow,
  row: ConflictRow,
): Promise<ConflictPreview> {
  contained(shadow.paths.root, row.path);
  assertBlobIds(row);
  await fetchConflictRef(shadow, row);
  await assertInHistory(shadow, row);
  const [mine, base, server] = await Promise.all([
    localVersion(shadow, row),
    version(shadow, row.baseBlob),
    version(shadow, row.theirsBlob),
  ]);
  let merged: string | undefined;
  if ([mine, base, server].every((value) => value.text !== null)) {
    const path = await stageMerge(shadow, row);
    const bytes = await readFile(path);
    if (bytes.length <= MAX_PREVIEW_BYTES)
      merged = displayText(bytes) ?? undefined;
  }
  return {
    row,
    mine,
    base,
    server,
    localHash: mine.hash,
    ...(merged === undefined ? {} : { merged }),
  };
}

/** The renderer supplies text, never a path. The same fenced scratch location backs CLI merges. */
export async function writeMerge(
  shadow: Shadow,
  row: ConflictRow,
  text: string,
): Promise<void> {
  if (Buffer.byteLength(text) > MAX_PREVIEW_BYTES)
    throw new Error('The merged text is too large.');
  if (/^(<{7}|={7}|>{7})(?: |$)/m.test(text))
    throw new Error(
      'Resolve the merge markers before using the merged version.',
    );
  const staged = mergeFileOf(shadow.paths.root, row);
  await assertNoSymlinks(
    shadow.paths.root,
    `.plowshare/conflicts/${row.n}/${basename(row.path)}`,
  );
  await mkdir(dirname(staged), { recursive: true });
  await writeFile(staged, text);
}

export async function takeTheirs(
  shadow: Shadow,
  row: ConflictRow,
): Promise<void> {
  const target = contained(shadow.paths.root, row.path);
  assertBlobIds(row);
  await fetchConflictRef(shadow, row);
  await assertInHistory(shadow, row);
  await assertNoSymlinks(shadow.paths.root, row.path);
  if (row.theirsBlob === undefined) {
    await rm(target, { force: true });
    return;
  }
  const bytes = await blob(shadow, row.theirsBlob);
  await mkdir(dirname(target), { recursive: true });
  await writeFile(target, bytes);
}

export async function stageMerge(
  shadow: Shadow,
  row: ConflictRow,
): Promise<string> {
  const staged = mergeFileOf(shadow.paths.root, row);
  const ourFile = contained(shadow.paths.root, row.path);
  assertBlobIds(row);
  await fetchConflictRef(shadow, row);
  await assertInHistory(shadow, row);
  await assertNoSymlinks(shadow.paths.root, row.path);
  await assertNoSymlinks(
    shadow.paths.root,
    `.plowshare/conflicts/${row.n}/${basename(row.path)}`,
  );
  const scratch = dirname(staged);
  await mkdir(scratch, { recursive: true });
  const ours = join(scratch, '.ours');
  const base = join(scratch, '.base');
  const theirs = join(scratch, '.theirs');
  try {
    for (const name of ['.ours', '.base', '.theirs']) {
      await assertNoSymlinks(
        shadow.paths.root,
        `.plowshare/conflicts/${row.n}/${name}`,
      );
    }
    await writeFile(
      ours,
      await readFile(ourFile).catch((error: unknown) => {
        if (errorCode(error) !== 'ENOENT') throw error;
        return Buffer.alloc(0);
      }),
    );
    await writeFile(base, await blob(shadow, row.baseBlob));
    await writeFile(theirs, await blob(shadow, row.theirsBlob));
    const merged = await shadow.git.run([
      'merge-file',
      '-p',
      '-L',
      'mine',
      '-L',
      'base',
      '-L',
      row.theirsAuthor,
      ours,
      base,
      theirs,
    ]);
    if (merged.code < 0 || merged.code > 127) {
      throw new Error(`git merge-file failed: ${merged.stderr}`);
    }
    await writeFile(staged, merged.stdout);
    return staged;
  } finally {
    await Promise.all(
      [ours, base, theirs].map((path) => rm(path, { force: true })),
    );
  }
}

export async function finishMerge(
  shadow: Shadow,
  row: ConflictRow,
): Promise<void> {
  const target = contained(shadow.paths.root, row.path);
  await assertInHistory(shadow, row);
  const staged = mergeFileOf(shadow.paths.root, row);
  await assertNoSymlinks(
    shadow.paths.root,
    `.plowshare/conflicts/${row.n}/${basename(row.path)}`,
  );
  if (
    await stat(staged).then(
      () => false,
      () => true,
    )
  ) {
    throw new Error(
      `no merge is staged for ${row.path}; run /sync resolve ${row.path} merge first`,
    );
  }
  await assertNoSymlinks(shadow.paths.root, row.path);
  await mkdir(dirname(target), { recursive: true });
  await writeFile(target, await readFile(staged));
  await rm(dirname(staged), { recursive: true, force: true });
}

export async function dropConflictRef(
  shadow: Shadow,
  row: ConflictRow,
): Promise<void> {
  const ref = `${CONFLICT_REF}${row.n}`;
  await shadow.git.run(['update-ref', '-d', ref]);
  const pushArgs = ['push', '-q', 'origin', `:${ref}`];
  const pushed = await shadow.git.run(pushArgs);
  if (pushed.code === 0) {
    return;
  }
  if (
    /unable to delete|remote ref does not exist|does not exist/i.test(
      pushed.stderr,
    )
  ) {
    return;
  }
  throw new GitFailed(pushArgs, pushed);
}

/** A blob's bytes. A blob the shadow cannot read throws: an empty answer would empty the file. */
async function blob(shadow: Shadow, oid: string | undefined): Promise<Buffer> {
  if (oid === undefined) {
    return Buffer.alloc(0);
  }
  const args = ['cat-file', 'blob', oid];
  const read = await shadow.git.run(args);
  if (read.code !== 0) {
    throw new GitFailed(args, read);
  }
  return read.stdout;
}

const BLOB_ID = /^[0-9a-f]{40}([0-9a-f]{24})?$/;

/**
 * Refuses a row whose blob ids are anything but a SHA-1 or SHA-256 object id. They arrive from
 * the server; `cat-file blob` would otherwise take a revision expression (`main:a.txt`) or an
 * option-looking string as well.
 */
function assertBlobIds(row: ConflictRow): void {
  for (const oid of [row.baseBlob, row.oursBlob, row.theirsBlob]) {
    if (oid !== undefined && !BLOB_ID.test(oid)) {
      throw new Error(`invalid blob id for ${row.path}: ${oid}`);
    }
  }
}

/**
 * A shadow recreated since the conflict was opened has no local conflict ref, so neither the
 * path check nor the blobs can find what the hub set aside. Fetch it when it is missing; a failed
 * fetch is left for the history and blob checks to refuse.
 */
async function fetchConflictRef(
  shadow: Shadow,
  row: ConflictRow,
): Promise<void> {
  const ref = `${CONFLICT_REF}${row.n}`;
  if ((await shadow.git.run(['rev-parse', '--verify', '-q', ref])).code === 0) {
    return;
  }
  await shadow.git
    .run(['fetch', '-q', 'origin', `+${ref}:${ref}`])
    .catch(() => undefined);
}

/**
 * Rejects a `row.path` that could escape the project root, and joins it onto `root` otherwise.
 * A bare `:` in an otherwise ordinary segment (e.g. a timestamp like `2026-09-14T10:00.md`) is
 * legal on macOS and Linux; it's refused only when this machine is actually Windows, where `:`
 * can start an alternate data stream or a drive letter. `reservedSegment` still cuts at the first
 * `:` regardless of platform, so `.git::$INDEX_ALLOCATION` stays refused everywhere.
 */
function contained(root: string, path: string): string {
  const bad =
    isAbsolute(path) ||
    path.includes('\\') ||
    path
      .split('/')
      .some(
        (segment) =>
          segment === '' ||
          segment === '.' ||
          segment === '..' ||
          (process.platform === 'win32' && segment.includes(':')) ||
          reservedSegment(segment),
      );
  if (bad) {
    throw new Error(`refusing a conflict path outside the project: ${path}`);
  }
  return join(root, path);
}

/**
 * Refuses a `row.path` that never appears as a real tree entry in this project's sync history —
 * neither the shadow's current `HEAD` nor the conflict ref the server opened this row under. A
 * path built only from what `contained` allows can still name something that was never actually
 * synced; resolving it would let a hostile hub or client write or read an arbitrary in-tree path
 * this machine never agreed to track.
 */
async function inHistory(shadow: Shadow, row: ConflictRow): Promise<boolean> {
  return (
    (await listedAt(shadow, 'HEAD', row.path)) ||
    (await listedAt(shadow, `${CONFLICT_REF}${row.n}`, row.path))
  );
}

async function listedAt(
  shadow: Shadow,
  rev: string,
  path: string,
): Promise<boolean> {
  const listed = await shadow.git.run([
    'ls-tree',
    '-r',
    '-z',
    '--name-only',
    rev,
    '--',
    path,
  ]);
  if (listed.code !== 0) {
    return false;
  }
  return listed.stdout.toString('utf8').split('\0').includes(path);
}

async function assertInHistory(
  shadow: Shadow,
  row: ConflictRow,
): Promise<void> {
  if (!(await inHistory(shadow, row))) {
    throw new Error(`${row.path} is not a path in this project's sync history`);
  }
}

/** Refuses to resolve through a symlink anywhere between `root` and `root/path`. */
async function assertNoSymlinks(root: string, path: string): Promise<void> {
  let current = root;
  for (const segment of path.split('/')) {
    current = join(current, segment);
    const info = await lstat(current).catch(() => undefined);
    if (info?.isSymbolicLink() === true) {
      throw new Error(
        `refusing to resolve ${path} through a symbolic link; resolve it by hand`,
      );
    }
  }
}
