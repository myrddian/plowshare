import { lstat, mkdir, rm, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { type Git, GitFailed, type ShadowPaths } from './git.ts';
import { allowed } from './fence.ts';
import { excludeLines } from './rules.ts';

/**
 * A union's reconciliation, run on the client. The server never merges (spec
 * §4): this module commits local changes, fetches the hub's main, merges with
 * `git merge-tree` (in memory; the work tree is touched only once the result is
 * known), keeps the local version of any conflicted path, and pushes.
 */

export const CONFLICT_REF = 'refs/plowshare/conflicts/';

export interface Shadow {
  readonly paths: ShadowPaths;
  readonly git: Git;
  /** `<handle>@<machine>`, the author of every commit this client makes. */
  readonly author: string;
}

export interface FoundConflict {
  readonly path: string;
  readonly baseBlob?: string;
  readonly oursBlob?: string;
  readonly theirsBlob?: string;
  readonly theirsAuthor: string;
  readonly runId?: string;
}

export type OpenConflict = (found: FoundConflict) => Promise<number>;
export type Pushed = 'ok' | 'moved';

export interface Reconciled {
  readonly commit: string;
  readonly conflicts: readonly { readonly n: number; readonly path: string }[];
}

export async function shadowExists(paths: ShadowPaths): Promise<boolean> {
  return stat(join(paths.gitDir, 'HEAD')).then(
    () => true,
    () => false,
  );
}

export async function initShadow(
  shadow: Shadow,
  remoteUrl: string,
): Promise<void> {
  await mkdir(join(shadow.paths.root, '.plowshare'), { recursive: true });
  await shadow.git.ok(['init', '-q', '-b', 'main']);
  await shadow.git.ok(['config', 'core.quotepath', 'false']);
  await shadow.git.ok(['config', 'core.autocrlf', 'false']);
  // git's own fsck rejects trees carrying an HFS- or NTFS-equivalent `.git`/`.plowshare` (a
  // Unicode-confusable or short-name variant a naive string check might miss) among the objects
  // a fetch brings in — a second, independent check on what actually lands in the object store.
  await shadow.git.ok(['config', 'transfer.fsckObjects', 'true']);
  await shadow.git.ok(['config', 'remote.origin.url', remoteUrl]);
}

export async function prepare(
  shadow: Shadow,
  hidden: readonly string[],
  maxFileBytes: number,
): Promise<readonly string[]> {
  const exclude = join(shadow.paths.gitDir, 'info', 'exclude');
  await mkdir(join(shadow.paths.gitDir, 'info'), { recursive: true });
  await writeFile(exclude, `${excludeLines(hidden, []).join('\n')}\n`);
  const listed = await shadow.git.ok([
    'ls-files',
    '-z',
    '--cached',
    '--others',
    '--exclude-standard',
  ]);
  const skipped: string[] = [];
  for (const path of new Set(listed.split('\0').filter((p) => p !== ''))) {
    const info = await stat(join(shadow.paths.root, path)).catch(
      () => undefined,
    );
    if (info?.isDirectory() === true) {
      skipped.push(path.replace(/\/$/, '')); // a nested repository git lists as a directory
    } else if (info !== undefined && info.size > maxFileBytes) {
      skipped.push(path);
    }
  }
  await writeFile(exclude, `${excludeLines(hidden, skipped).join('\n')}\n`);

  // A path already tracked (e.g. a file that grew past the cap after being synced) must not be
  // `rm --cached`: that stages a deletion, and the next commit would delete it everywhere. Only
  // untracked skipped paths need `rm --cached`; tracked ones are hidden from diffs instead, via
  // the skip-worktree bit, leaving the last-synced content in the index untouched.
  const trackedListed =
    skipped.length > 0
      ? await shadow.git.ok(['ls-files', '-z', '--cached', '--', ...skipped])
      : '';
  const trackedNow = new Set(trackedListed.split('\0').filter((p) => p !== ''));
  const trackedSkipped = skipped.filter((path) => trackedNow.has(path));
  const untrackedSkipped = skipped.filter((path) => !trackedNow.has(path));
  if (trackedSkipped.length > 0) {
    await shadow.git.run([
      'update-index',
      '--skip-worktree',
      '--',
      ...trackedSkipped,
    ]);
  }
  if (untrackedSkipped.length > 0) {
    await shadow.git.run([
      'rm',
      '-q',
      '-r',
      '--cached',
      '--ignore-unmatch',
      '--',
      ...untrackedSkipped,
    ]);
  }

  // A tracked path that carries the skip-worktree bit from an earlier prepare() but is no longer
  // skipped (shrunk back under the cap) must have it cleared, or its local edits stay invisible
  // to `git add -A` forever.
  const skippedSet = new Set(skipped);
  const verbose = await shadow.git.ok(['ls-files', '-z', '-v']);
  const toUnskip = verbose
    .split('\0')
    .filter(
      (line) =>
        line !== '' && line.charAt(0) === 'S' && !skippedSet.has(line.slice(2)),
    )
    .map((line) => line.slice(2));
  if (toUnskip.length > 0) {
    await shadow.git.run([
      'update-index',
      '--no-skip-worktree',
      '--',
      ...toUnskip,
    ]);
  }

  return skipped;
}

export async function head(shadow: Shadow): Promise<string | undefined> {
  const found = await shadow.git.run(['rev-parse', '--verify', '-q', 'HEAD']);
  return found.code === 0 ? found.stdout.toString('utf8').trim() : undefined;
}

/** What the hub will accept: its hidden-path allowlist and its per-file cap. */
export interface Fence {
  readonly hidden: readonly string[];
  readonly maxFileBytes: number;
}

export async function commitDirty(
  shadow: Shadow,
  message: string,
  fence: Fence,
): Promise<string | undefined> {
  await shadow.git.ok(['add', '-A']);
  await unstageOutsideFence(shadow, fence);
  const staged = await shadow.git.run(['diff', '--cached', '--quiet']);
  if (staged.code === 0) {
    return undefined;
  }
  await shadow.git.ok(['commit', '-q', '--no-verify', '-m', message], {
    env: identity(shadow.author),
  });
  return head(shadow);
}

/**
 * Drops from the index every path the hub would refuse, after `git add -A` staged it. `info/exclude`
 * alone cannot hold the fence: a project `.gitignore` outranks it, so `!.env`, `!.gitkeep` or
 * `!.plowshare/` re-include what it keeps out, and the hub would then refuse every push — after the
 * pack, secrets and all, had already landed on its disk. Refused: a reserved segment at any depth,
 * a hidden segment the allowlist does not permit, and (for a path not in `HEAD`) a file over the
 * cap. A tracked path carrying skip-worktree (prepare's handling of a file that outgrew the cap) is
 * left alone, so its last-synced content is not staged as a deletion.
 *
 * A refused path that is already in `HEAD` is never unstaged: removing it from the index commits a
 * deletion, which the hub accepts and every other machine's checkout carries out — narrowing the
 * hidden allowlist would delete those files everywhere. Instead its index entry goes back to
 * `HEAD`'s content and takes skip-worktree, so it stays at its last-synced version and local edits
 * to it stop syncing. Only paths the hub has never had are removed from the index.
 */
async function unstageOutsideFence(
  shadow: Shadow,
  fence: Fence,
): Promise<void> {
  const listed = await shadow.git.ok(['ls-files', '-z', '-v', '--cached']);
  const rows = listed.split('\0').filter((row) => row !== '');
  if (rows.length === 0) {
    return;
  }
  const local = await head(shadow);
  const atHead =
    local === undefined
      ? new Set<string>()
      : new Set(
          (await shadow.git.ok(['ls-tree', '-r', '-z', '--name-only', local]))
            .split('\0')
            .filter((path) => path !== ''),
        );
  const refused: string[] = [];
  for (const row of rows) {
    const tag = row.charAt(0);
    const path = row.slice(2);
    if (tag === 'S' || tag === 's') {
      continue;
    }
    const segments = path.split('/');
    if (
      segments[0] === '.plowshare' ||
      segments.includes('.git') ||
      !allowed(path, fence.hidden)
    ) {
      refused.push(path);
      continue;
    }
    if (!atHead.has(path)) {
      const info = await lstat(join(shadow.paths.root, path)).catch(
        () => undefined,
      );
      if (info !== undefined && info.size > fence.maxFileBytes) {
        refused.push(path);
      }
    }
  }
  const kept = refused.filter((path) => atHead.has(path));
  const dropped = refused.filter((path) => !atHead.has(path));
  for (let i = 0; i < kept.length; i += 500) {
    const batch = kept.slice(i, i + 500);
    await shadow.git.ok(['reset', '-q', local as string, '--', ...batch]);
    await shadow.git.ok(['update-index', '--skip-worktree', '--', ...batch]);
  }
  for (let i = 0; i < dropped.length; i += 500) {
    await shadow.git.ok([
      'rm',
      '-q',
      '--cached',
      '--ignore-unmatch',
      '--',
      ...dropped.slice(i, i + 500),
    ]);
  }
}

export async function fetchMain(shadow: Shadow): Promise<string | undefined> {
  const fetched = await shadow.git.run([
    'fetch',
    '-q',
    'origin',
    '+refs/heads/main:refs/remotes/origin/main',
  ]);
  if (fetched.code !== 0) {
    if (
      /couldn't find remote ref|does not appear to have|not our ref/i.test(
        fetched.stderr,
      )
    ) {
      return undefined;
    }
    throw new GitFailed(['fetch'], fetched);
  }
  return shadow.git.ok(['rev-parse', 'refs/remotes/origin/main']);
}

export async function push(
  shadow: Shadow,
  refs: readonly string[],
): Promise<Pushed> {
  const pushed = await shadow.git.run([
    'push',
    '--porcelain',
    '--atomic',
    'origin',
    ...refs.map((ref) => `${ref}:${ref}`),
  ]);
  if (pushed.code === 0) {
    return 'ok';
  }
  const said = `${pushed.stdout.toString('utf8')}\n${pushed.stderr}`;
  if (/non-fast-forward|fetch first|\[rejected\]/.test(said)) {
    return 'moved';
  }
  throw new GitFailed(['push'], pushed);
}

export async function reconcile(
  shadow: Shadow,
  open: OpenConflict,
  fence: Fence,
  attempts = 5,
): Promise<Reconciled> {
  // Conflicts accumulate across attempts: a push can carry main and a conflict ref together
  // (push() is atomic) yet still lose a race, and a later attempt may find nothing new to merge
  // (ours already won) — that attempt's push must still carry, and this function must still
  // report, every conflict opened by an earlier attempt whose push did not land (I-4).
  const allConflicts: { n: number; path: string }[] = [];
  for (let attempt = 0; attempt < attempts; attempt++) {
    await commitDirty(shadow, `sync from ${shadow.author}`, fence);
    const remote = await fetchMain(shadow);
    const local = await head(shadow);
    let conflicts: { n: number; path: string }[] = [];
    if (remote === undefined) {
      if (local === undefined) {
        await shadow.git.ok(
          [
            'commit',
            '-q',
            '--allow-empty',
            '--no-verify',
            '-m',
            'an empty union',
          ],
          { env: identity(shadow.author) },
        );
      }
    } else if (local === undefined) {
      await guardUntracked(shadow, remote);
      await shadow.git.ok(['read-tree', '-m', '-u', remote]);
      await shadow.git.ok(['update-ref', 'refs/heads/main', remote]);
    } else if (await ancestor(shadow, remote, local)) {
      // local is ahead or equal: nothing to merge
    } else if (await ancestor(shadow, local, remote)) {
      await guardUntracked(shadow, remote);
      await shadow.git.ok(['read-tree', '-m', '-u', 'HEAD', remote]);
      await shadow.git.ok(['update-ref', 'refs/heads/main', remote]);
    } else {
      conflicts = await merge(shadow, remote, open);
    }
    allConflicts.push(...conflicts);
    const commit = (await head(shadow)) as string;
    if (remote === commit) {
      return { commit, conflicts: allConflicts };
    }
    const refs = [
      'refs/heads/main',
      ...allConflicts.map((c) => `${CONFLICT_REF}${c.n}`),
    ];
    if ((await push(shadow, refs)) === 'ok') {
      return { commit, conflicts: allConflicts };
    }
  }
  throw new Error(`the hub kept moving; gave up after ${attempts} attempts`);
}

/**
 * Refuse a checkout that would silently overwrite a local file plowshare never synced — skipped
 * for size, excluded, or ignored by the project's own `.gitignore`. `read-tree -m -u` treats such
 * a file as expendable since it is not in the index; this rejects the whole reconcile instead (I-3).
 *
 * Two things git's own "added" diff can hide, so each is checked explicitly:
 *  - a rename: `--diff-filter=A` alone classifies a hub-side `git mv` onto a colliding path as a
 *    rename, not an add, so `--no-renames` is required to see it as a plain new path.
 *  - a directory one level down: an added `foo/bar` doesn't reveal that a local, excluded *file*
 *    `foo` blocks it — `lstat('foo/bar')` merely fails with ENOTDIR. Every parent prefix of each
 *    added path is checked too; a parent that exists on disk, isn't a directory, and isn't itself
 *    a tracked blob in HEAD is a collision (a *tracked* file becoming a directory is legitimate —
 *    git's own read-tree handles that transition correctly on its own).
 */
async function guardUntracked(shadow: Shadow, target: string): Promise<void> {
  const localHead = await head(shadow);
  const listed =
    localHead === undefined
      ? await shadow.git.ok(['ls-tree', '-r', '-z', '--name-only', target])
      : await shadow.git.ok([
          'diff',
          '--name-only',
          '-z',
          '--no-renames',
          '--diff-filter=A',
          localHead,
          target,
        ]);
  const added = listed.split('\0').filter((path) => path !== '');
  const collide = new Set<string>();
  const prefixIsBlocker = new Map<string, boolean>();
  for (const path of added) {
    const info = await lstat(join(shadow.paths.root, path)).catch(
      () => undefined,
    );
    if (info !== undefined) {
      collide.add(path);
      continue;
    }
    const segments = path.split('/');
    let prefix = '';
    for (let i = 0; i < segments.length - 1; i++) {
      prefix =
        prefix === '' ? (segments[i] ?? '') : `${prefix}/${segments[i] ?? ''}`;
      let blocker = prefixIsBlocker.get(prefix);
      if (blocker === undefined) {
        const prefixInfo = await lstat(join(shadow.paths.root, prefix)).catch(
          () => undefined,
        );
        const tracked =
          prefixInfo === undefined ||
          prefixInfo.isDirectory() ||
          localHead === undefined
            ? undefined
            : await entry(shadow.git, 'HEAD', prefix);
        blocker =
          prefixInfo !== undefined &&
          !prefixInfo.isDirectory() &&
          tracked === undefined;
        prefixIsBlocker.set(prefix, blocker);
      }
      if (blocker) {
        collide.add(prefix);
        break;
      }
    }
  }
  if (collide.size > 0) {
    throw new Error(
      `these local files are not synced but the hub has them: ${[...collide].join(', ')}; ` +
        'move them aside and sync again',
    );
  }
}

async function merge(
  shadow: Shadow,
  remote: string,
  open: OpenConflict,
): Promise<{ n: number; path: string }[]> {
  const git = shadow.git;
  const based = await git.run(['merge-base', 'HEAD', remote]);
  const base =
    based.code === 0 ? based.stdout.toString('utf8').trim() : undefined;
  const merged = await git.run([
    'merge-tree',
    '--write-tree',
    '-z',
    '--name-only',
    '--no-messages',
    '--allow-unrelated-histories',
    '-X',
    'no-renames',
    'HEAD',
    remote,
  ]);
  if (merged.code > 1) {
    throw new GitFailed(['merge-tree'], merged);
  }
  const [tree = '', ...names] = merged.stdout.toString('utf8').split('\0');
  const paths = [...new Set(names.filter((name) => name !== ''))];

  // A conflicted name that isn't a real path on either side (or whose entry there is a
  // directory) means a file and a directory (or symlink) share the path: merge-tree moved the
  // loser aside under a `path~label` name. Refuse before opening any conflict or touching the
  // index/working tree — resolving this by picking a side would silently lose data (I-2).
  for (const name of paths) {
    const ours = await entry(git, 'HEAD', name);
    const theirs = await entry(git, remote, name);
    const clash =
      (ours === undefined && theirs === undefined) ||
      ours?.mode === '040000' ||
      theirs?.mode === '040000';
    if (clash) {
      throw new Error(
        `a file and a directory (or link) share the path ${name.replace(/~.*$/, '')} on ` +
          'the two sides; rename one and sync again',
      );
    }
  }

  let finalTree = tree;
  const found: FoundConflict[] = [];
  if (paths.length > 0) {
    const index = join(shadow.paths.gitDir, 'plowshare-merge-index');
    const env = { GIT_INDEX_FILE: index };
    await git.ok(['read-tree', tree], { env });
    for (const path of paths) {
      const ours = await entry(git, 'HEAD', path);
      const theirs = await entry(git, remote, path);
      const was = base === undefined ? undefined : await entry(git, base, path);
      if (ours === undefined) {
        await git.ok(['update-index', '--force-remove', '--', path], { env });
      } else {
        await git.ok(
          [
            'update-index',
            '--add',
            '--cacheinfo',
            `${ours.mode},${ours.oid},${path}`,
          ],
          { env },
        );
      }
      const who = await authorOf(git, remote, path);
      found.push({
        path,
        theirsAuthor: who.name,
        ...(was === undefined ? {} : { baseBlob: was.oid }),
        ...(ours === undefined ? {} : { oursBlob: ours.oid }),
        ...(theirs === undefined ? {} : { theirsBlob: theirs.oid }),
        ...(who.runId === undefined ? {} : { runId: who.runId }),
      });
    }
    finalTree = await git.ok(['write-tree'], { env });
    await rm(index, { force: true });
  }
  const commit = await git.ok(
    [
      'commit-tree',
      finalTree,
      '-p',
      'HEAD',
      '-p',
      remote,
      '-m',
      `merge the hub into ${shadow.author}`,
    ],
    { env: identity(shadow.author) },
  );
  await guardUntracked(shadow, commit);
  await git.ok(['read-tree', '-m', '-u', 'HEAD', commit]);
  await git.ok(['update-ref', 'refs/heads/main', commit]);
  // Only now that the merge has landed are its conflicts told to the server: a merge refused
  // above (an untracked collision, a failed checkout) must leave no conflict recorded behind it.
  const conflicts: { n: number; path: string }[] = [];
  for (const conflict of found) {
    conflicts.push({ n: await open(conflict), path: conflict.path });
  }
  for (const conflict of conflicts) {
    await git.ok(['update-ref', `${CONFLICT_REF}${conflict.n}`, remote]);
  }
  return conflicts;
}

async function ancestor(
  shadow: Shadow,
  older: string,
  newer: string,
): Promise<boolean> {
  return (
    (await shadow.git.run(['merge-base', '--is-ancestor', older, newer]))
      .code === 0
  );
}

async function entry(
  git: Git,
  rev: string,
  path: string,
): Promise<{ mode: string; oid: string } | undefined> {
  const listed = await git.ok(['ls-tree', '-z', rev, '--', path]);
  const line = listed.split('\0').find((row) => row.endsWith(`\t${path}`));
  if (line === undefined) {
    return undefined;
  }
  const [meta = ''] = line.split('\t');
  const [mode = '', , oid = ''] = meta.split(' ');
  return { mode, oid };
}

async function authorOf(
  git: Git,
  rev: string,
  path: string,
): Promise<{ name: string; runId?: string }> {
  const said = await git.ok([
    'log',
    '-1',
    '--format=%an%x00%B',
    rev,
    '--',
    path,
  ]);
  const [name = '', body = ''] = said.split('\0');
  const run = /^run (\S+)/m.exec(body)?.[1];
  return {
    name: name === '' ? '(unknown)' : name,
    ...(run === undefined ? {} : { runId: run }),
  };
}

function identity(author: string): Record<string, string> {
  return {
    GIT_AUTHOR_NAME: author,
    GIT_AUTHOR_EMAIL: author,
    GIT_COMMITTER_NAME: author,
    GIT_COMMITTER_EMAIL: author,
  };
}
