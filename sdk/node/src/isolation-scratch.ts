import {
  lstat,
  mkdir,
  opendir,
  readFile,
  readdir,
  realpath,
  rmdir,
  unlink,
} from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { join, resolve, relative, isAbsolute, sep } from 'node:path';
import { CommandRefused } from './runner.js';

const name = /^run-([0-9a-f-]{36})-([0-9]+)-([0-9]+)-([0-9a-f-]{36})$/;
const files = ['file', 'directory', 'options'];
const uid = (): number | undefined => process.getuid?.();
async function start(pid: string): Promise<string> {
  const stat = await readFile(join('/proc', pid, 'stat'), 'utf8');
  // The parenthesized comm may contain spaces. The suffix starts at field 3; starttime is 22.
  const ticks = stat.slice(stat.lastIndexOf(')') + 2).split(' ')[19];
  if (!ticks || !/^[0-9]+$/.test(ticks))
    throw new CommandRefused('invalid sandbox owner identity');
  return ticks;
}
function contains(root: string, path: string): boolean {
  const below = relative(root, path);
  return (
    below === '' ||
    (below !== '..' && !below.startsWith('..' + sep) && !isAbsolute(below))
  );
}

/** Recover only this installation's dead owners; PID start ticks also defend against PID reuse. */
export async function acquireScratch(
  configured: string,
  mounts: readonly string[],
): Promise<string> {
  const root = await realpath(configured);
  const attributes = await lstat(root);
  if (
    root !== resolve(configured) ||
    !attributes.isDirectory() ||
    attributes.uid !== uid() ||
    (attributes.mode & 0o777) !== 0o700 ||
    mounts.some((path) => contains(path, root) || contains(root, path))
  )
    throw new CommandRefused(
      'sandbox scratch must be an owned mode-0700 directory separate from mounts',
    );
  const boot = (
    await readFile('/proc/sys/kernel/random/boot_id', 'utf8')
  ).trim();
  if (!/^[0-9a-f-]{36}$/.test(boot))
    throw new CommandRefused('invalid sandbox boot identity');
  const entries: string[] = [];
  for await (const entry of await opendir(root)) {
    if (entries.length === 1024)
      throw new CommandRefused(
        'sandbox scratch recovery exceeded its entry bound',
      );
    entries.push(entry.name);
  }
  for (const entry of entries) {
    const match = name.exec(entry);
    if (!match) continue;
    if (boot === match[1]) {
      const processDirectory = await lstat(join('/proc', match[2] ?? '')).catch(
        (error: unknown) => {
          if (
            error instanceof Error &&
            'code' in error &&
            error.code === 'ENOENT'
          )
            return undefined;
          throw error;
        },
      );
      if (processDirectory && (await start(match[2] ?? '')) === match[3])
        continue;
    }
    await removeScratch(join(root, entry));
  }
  const path = join(
    root,
    `run-${boot}-${process.pid}-${await start('self')}-${randomUUID()}`,
  );
  await mkdir(path, { mode: 0o700 });
  return path;
}

/** No recursive deletion and no following links, even inside the operator-owned directory. */
export async function removeScratch(path: string): Promise<void> {
  const attributes = await lstat(path);
  if (
    !attributes.isDirectory() ||
    attributes.uid !== uid() ||
    (attributes.mode & 0o777) !== 0o700
  )
    return;
  for (const child of await readdir(path)) {
    const attributes = await lstat(join(path, child));
    if (
      !files.includes(child) ||
      attributes.uid !== uid() ||
      attributes.isSymbolicLink() ||
      (child === 'directory'
        ? !attributes.isDirectory()
        : !attributes.isFile() || attributes.nlink !== 1)
    )
      return;
  }
  for (const child of files) {
    try {
      if (child === 'directory') await rmdir(join(path, child));
      else await unlink(join(path, child));
    } catch (error) {
      if (
        !(error instanceof Error) ||
        !('code' in error) ||
        error.code !== 'ENOENT'
      )
        throw error;
    }
  }
  await rmdir(path);
}
