import { lstat, readdir, open, realpath } from 'node:fs/promises';
import { constants } from 'node:fs';
import {
  resolve,
  join,
  relative as relativePath,
  isAbsolute,
  sep,
} from 'node:path';
import {
  applicationFilesProblem,
  type ApplicationSourceFile,
} from 'plowshare-client-ts/operations/application-deployments';

/** A predictable source validation refusal that callers may display without native I/O details. */
export class ApplicationSourceError extends Error {}

/** Snapshot a user-selected source folder. No process is started and no client sync is enabled. */
export async function applicationFiles(
  directory: string,
): Promise<readonly ApplicationSourceFile[]> {
  const root = resolve(directory);
  const info = await lstat(root);
  if (!info.isDirectory() || info.isSymbolicLink())
    throw new ApplicationSourceError(
      'Choose a regular Application source directory',
    );
  const canonical = await realpath(root);
  const files: ApplicationSourceFile[] = [];
  let entries = 0;
  const visit = async (
    folder: string,
    relative: string,
    depth: number,
  ): Promise<void> => {
    const metadata = await lstat(folder);
    const canonicalFolder = await realpath(folder);
    const fromRoot = relativePath(canonical, canonicalFolder);
    if (
      !metadata.isDirectory() ||
      metadata.isSymbolicLink() ||
      fromRoot === '..' ||
      fromRoot.startsWith(`..${sep}`) ||
      isAbsolute(fromRoot)
    )
      throw new ApplicationSourceError(
        'Application source directory escaped its selected root',
      );
    if (depth > 16)
      throw new ApplicationSourceError(
        'Application source nesting exceeds 16 directories',
      );
    for (const entry of (await readdir(folder, { withFileTypes: true })).sort(
      (a, b) => a.name.localeCompare(b.name),
    )) {
      if (relative === '' && entry.name === '.plowshare')
        throw new ApplicationSourceError(
          'Application resources belong directly in the root; remove .plowshare/',
        );
      if (entry.name.startsWith('.')) continue;
      if (['node_modules', 'build', '__pycache__'].includes(entry.name))
        continue;
      if (++entries > 2048)
        throw new ApplicationSourceError(
          'Application source has too many entries',
        );
      if (entry.isSymbolicLink())
        throw new ApplicationSourceError(
          'Application source cannot contain symbolic links',
        );
      const path = join(folder, entry.name),
        name = relative ? `${relative}/${entry.name}` : entry.name;
      if (entry.isDirectory()) {
        await visit(path, name, depth + 1);
        continue;
      }
      if (!entry.isFile())
        throw new ApplicationSourceError(
          'Application source must contain regular text files',
        );
      const input = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
      try {
        const metadata = await input.stat();
        if (!metadata.isFile() || metadata.size > 65536 || files.length >= 128)
          throw new ApplicationSourceError(
            'Application source exceeds its file limits',
          );
        const bytes = Buffer.alloc(65537);
        let count = 0;
        for (;;) {
          const part = await input.read(
            bytes,
            count,
            bytes.length - count,
            count,
          );
          count += part.bytesRead;
          if (!part.bytesRead || count === bytes.length) break;
        }
        if (count > 65536)
          throw new ApplicationSourceError(
            'Application source file exceeds 64 KiB',
          );
        const text = new TextDecoder('utf-8', { fatal: true }).decode(
          bytes.subarray(0, count),
        );
        files.push({ path: name, text });
      } finally {
        await input.close();
      }
    }
  };
  await visit(canonical, '', 0);
  const problem = applicationFilesProblem(files);
  if (problem) throw new ApplicationSourceError(problem);
  return files;
}
