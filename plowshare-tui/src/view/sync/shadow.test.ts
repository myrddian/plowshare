import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import {
  mkdir,
  mkdtemp,
  readFile,
  realpath,
  rm,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { shadowGit, shadowPaths } from './git.ts';
import {
  CONFLICT_REF,
  type FoundConflict,
  type Shadow,
  commitDirty,
  initShadow,
  prepare,
  reconcile,
} from './shadow.ts';

let top = '';
let hub = '';
beforeEach(async () => {
  top = await realpath(await mkdtemp(join(tmpdir(), 'shadow-')));
  hub = join(top, 'hub.git');
  execFileSync('git', ['init', '-q', '--bare', '-b', 'main', hub]);
});
afterEach(async () => {
  await rm(top, { recursive: true, force: true });
});

async function machine(
  name: string,
  files: Record<string, string>,
): Promise<Shadow> {
  const root = join(top, name);
  await mkdir(root, { recursive: true });
  for (const [path, content] of Object.entries(files)) {
    await mkdir(join(root, path, '..'), { recursive: true });
    await writeFile(join(root, path), content);
  }
  const shadow: Shadow = {
    paths: shadowPaths(root),
    git: shadowGit(shadowPaths(root)),
    author: `enzo@${name}`,
  };
  await initShadow(shadow, `file://${hub}`);
  await prepare(shadow, [], 1_000);
  return shadow;
}

/** A bot on the server: clone the hub, change a file, push, as the hub's own commit would. */
function botEdits(path: string, content: string): void {
  const work = join(top, `bot-${Math.random().toString(36).slice(2)}`);
  execFileSync('git', ['clone', '-q', hub, work]);
  execFileSync('git', [
    '-C',
    work,
    'checkout',
    '-q',
    '-B',
    'main',
    'origin/main',
  ]);
  execFileSync('mkdir', ['-p', join(work, path, '..')]);
  execFileSync('sh', [
    '-c',
    `printf '%s' "$1" > "$2"`,
    'sh',
    content,
    join(work, path),
  ]);
  execFileSync('git', ['-C', work, 'add', '-A']);
  execFileSync('git', [
    '-C',
    work,
    '-c',
    'user.name=nightly-bot',
    '-c',
    'user.email=b@b',
    'commit',
    '-qm',
    'run run_7',
  ]);
  execFileSync('git', ['-C', work, 'push', '-q', 'origin', 'main']);
}

function hubFile(path: string): string {
  return execFileSync('git', [
    '--git-dir',
    hub,
    'show',
    `main:${path}`,
  ]).toString('utf8');
}

/** Clone the hub, apply an arbitrary change with `edit`, commit as a bot, push. */
function botPushes(edit: (work: string) => void, message = 'run run_1'): void {
  const work = join(top, `bot-${Math.random().toString(36).slice(2)}`);
  execFileSync('git', ['clone', '-q', hub, work]);
  execFileSync('git', [
    '-C',
    work,
    'checkout',
    '-q',
    '-B',
    'main',
    'origin/main',
  ]);
  edit(work);
  execFileSync('git', ['-C', work, 'add', '-A']);
  execFileSync('git', [
    '-C',
    work,
    '-c',
    'user.name=nightly-bot',
    '-c',
    'user.email=b@b',
    'commit',
    '-qm',
    message,
  ]);
  execFileSync('git', ['-C', work, 'push', '-q', 'origin', 'main']);
}

/** A bot on the server: rename a tracked path and write new content at the new path. */
function botRenames(from: string, to: string, content: string): void {
  botPushes((work) => {
    execFileSync('git', ['-C', work, 'mv', from, to]);
    writeFileSync(join(work, to), content);
  }, 'run run_9');
}

const noConflicts = async (): Promise<number> => {
  throw new Error('no conflict expected');
};
/** The server's rules as the tests' machines see them: no hidden allowlist, a 1 000-byte cap. */
const fence = { hidden: [] as readonly string[], maxFileBytes: 1_000 };

function hubPaths(): string[] {
  return execFileSync('git', [
    '--git-dir',
    hub,
    'ls-tree',
    '-r',
    '-z',
    '--name-only',
    'main',
  ])
    .toString('utf8')
    .split('\0')
    .filter((p) => p !== '');
}

describe('initShadow', () => {
  it('turns on fsck for fetched objects, so git itself rejects a hostile .git-alike tree', async () => {
    const laptop = await machine('laptop', { 'a.txt': 'a\n' });
    const configured = execFileSync('git', [
      '--git-dir',
      laptop.paths.gitDir,
      'config',
      '--get',
      'transfer.fsckObjects',
    ])
      .toString('utf8')
      .trim();
    expect(configured).toBe('true');
  });
});

describe('reconcile', () => {
  it('pushes the first snapshot to an empty hub, leaving hidden files behind', async () => {
    const laptop = await machine('laptop', {
      'src/a.ts': 'a\n',
      '.env': 'SECRET=1\n',
    });
    await commitDirty(laptop, 'snapshot', fence);
    const done = await reconcile(laptop, noConflicts, fence);
    expect(
      execFileSync('git', ['--git-dir', hub, 'rev-parse', 'main'])
        .toString()
        .trim(),
    ).toBe(done.commit);
    expect(hubFile('src/a.ts')).toBe('a\n');
    expect(() => hubFile('.env')).toThrow();
  });

  it('brings a bot edit into the working tree', async () => {
    const laptop = await machine('laptop', { 'src/a.ts': 'a\n' });
    await reconcile(laptop, noConflicts, fence);
    botEdits('src/b.ts', 'from bot\n');
    await reconcile(laptop, noConflicts, fence);
    expect(await readFile(join(laptop.paths.root, 'src/b.ts'), 'utf8')).toBe(
      'from bot\n',
    );
  });

  it('merges edits to different files on both sides', async () => {
    const laptop = await machine('laptop', { 'a.txt': 'a\n', 'b.txt': 'b\n' });
    await reconcile(laptop, noConflicts, fence);
    botEdits('b.txt', 'bot b\n');
    await writeFile(join(laptop.paths.root, 'a.txt'), 'mine a\n');
    await reconcile(laptop, noConflicts, fence);
    expect(await readFile(join(laptop.paths.root, 'b.txt'), 'utf8')).toBe(
      'bot b\n',
    );
    expect(hubFile('a.txt')).toBe('mine a\n');
    expect(hubFile('b.txt')).toBe('bot b\n');
  });

  it('keeps mine on a conflict, reports theirs, and pushes a conflict ref', async () => {
    const laptop = await machine('laptop', { 'a.txt': 'one\n' });
    await reconcile(laptop, noConflicts, fence);
    botEdits('a.txt', 'bot\n');
    await writeFile(join(laptop.paths.root, 'a.txt'), 'mine\n');
    const found: FoundConflict[] = [];
    const done = await reconcile(
      laptop,
      async (conflict) => {
        found.push(conflict);
        return 1;
      },
      fence,
    );
    expect(found.map((c) => [c.path, c.theirsAuthor, c.runId])).toEqual([
      ['a.txt', 'nightly-bot', 'run_7'],
    ]);
    expect(await readFile(join(laptop.paths.root, 'a.txt'), 'utf8')).toBe(
      'mine\n',
    );
    expect(hubFile('a.txt')).toBe('mine\n');
    expect(done.conflicts).toEqual([{ n: 1, path: 'a.txt' }]);
    expect(
      execFileSync('git', [
        '--git-dir',
        hub,
        'show',
        `${CONFLICT_REF}1:a.txt`,
      ]).toString(),
    ).toBe('bot\n');
  });

  it('a second machine with a stale copy conflicts instead of overwriting', async () => {
    const laptop = await machine('laptop', {
      'a.txt': 'new\n',
      'same.txt': 's\n',
    });
    await reconcile(laptop, noConflicts, fence);
    const desktop = await machine('desktop', {
      'a.txt': 'old\n',
      'same.txt': 's\n',
    });
    const found: string[] = [];
    await reconcile(
      desktop,
      async (conflict) => {
        found.push(conflict.path);
        return found.length;
      },
      fence,
    );
    expect(found).toEqual(['a.txt']);
  });

  it('skips a file over the size cap and says so', async () => {
    const laptop = await machine('laptop', {
      'small.txt': 's\n',
      'big.bin': 'x'.repeat(2_000),
    });
    const skipped = await prepare(laptop, [], 1_000);
    expect(skipped).toEqual(['big.bin']);
    await reconcile(laptop, noConflicts, fence);
    expect(() => hubFile('big.bin')).toThrow();
  });

  // I-1: merge-tree detects renames by default; a renamed-and-edited path on the hub must not
  // make the client's own edit at the old path vanish from the merged tree (and disk). The base
  // content is mostly-unchanged multi-line text so git's similarity heuristic would (absent the
  // fix) actually recognize the hub's move as a rename rather than an unrelated add/delete.
  it('keeps my edited file when the hub renamed it', async () => {
    const base = `${Array.from({ length: 10 }, (_, i) => `line${i + 1}`).join('\n')}\n`;
    const laptop = await machine('laptop', { 'a.txt': base });
    await reconcile(laptop, noConflicts, fence);
    botRenames('a.txt', 'b.txt', base.replace('line10', 'renamed by bot'));
    const mine = base.replace('line1', 'mine');
    await writeFile(join(laptop.paths.root, 'a.txt'), mine);
    await reconcile(laptop, async () => 1, fence);
    expect(await readFile(join(laptop.paths.root, 'a.txt'), 'utf8')).toBe(mine);
    expect(hubFile('a.txt')).toBe(mine);
  });

  // I-2: a file/directory clash must be refused outright, before any conflict is opened or the
  // working tree is touched — not silently resolved by moving the local file aside. Both sides
  // must actually touch `foo` (ours edits its content, theirs replaces it with a directory) —
  // an untouched local `foo` would let git auto-adopt theirs with no conflict reported at all,
  // never reaching merge-tree's conflict list (and never reaching merge() over a plain
  // fast-forward, which is why laptop edits `foo` itself rather than leaving it untouched).
  it('refuses a file/directory clash without touching the working tree', async () => {
    const laptop = await machine('laptop', { foo: 'i am a file\n' });
    await reconcile(laptop, noConflicts, fence);
    botPushes((work) => {
      execFileSync('git', ['-C', work, 'rm', '-q', 'foo']);
      mkdirSync(join(work, 'foo'), { recursive: true });
      writeFileSync(join(work, 'foo', 'bar'), 'i am inside a dir\n');
    });
    await writeFile(join(laptop.paths.root, 'foo'), 'mine\n');
    const opened: string[] = [];
    await expect(
      reconcile(
        laptop,
        async (conflict) => {
          opened.push(conflict.path);
          return opened.length;
        },
        fence,
      ),
    ).rejects.toThrow(/share the path foo/);
    expect(opened).toEqual([]);
    expect(await readFile(join(laptop.paths.root, 'foo'), 'utf8')).toBe(
      'mine\n',
    );
  });

  // I-3: a hub path that collides with a local file plowshare never synced (skipped for size,
  // or excluded by the project's own .gitignore) must not be silently overwritten by checkout.
  it('refuses to overwrite a local file skipped for size', async () => {
    const laptop = await machine('laptop', {
      'small.txt': 's\n',
      'big.bin': 'x'.repeat(2_000),
    });
    await reconcile(laptop, noConflicts, fence);
    botPushes((work) =>
      writeFileSync(join(work, 'big.bin'), 'y'.repeat(2_000)),
    );
    await expect(reconcile(laptop, noConflicts, fence)).rejects.toThrow(
      /big\.bin/,
    );
    expect(await readFile(join(laptop.paths.root, 'big.bin'), 'utf8')).toBe(
      'x'.repeat(2_000),
    );
  });

  it('refuses to overwrite an excluded hidden file on a fresh machine', async () => {
    const laptop = await machine('laptop', {
      '.gitignore': 'out.log\n',
      'keep.txt': 'k\n',
      'out.log': 'local log\n',
    });
    await reconcile(laptop, noConflicts, fence);
    botPushes((work) => writeFileSync(join(work, 'out.log'), 'bot log\n'));
    await expect(reconcile(laptop, noConflicts, fence)).rejects.toThrow(
      /out\.log/,
    );
    expect(await readFile(join(laptop.paths.root, 'out.log'), 'utf8')).toBe(
      'local log\n',
    );
  });

  // I-4: a push must be atomic (main and its conflict refs land together or not at all), and a
  // conflict opened on an attempt whose push loses a race must still be reported and pushed once
  // a later attempt succeeds — not silently dropped because that later attempt found no new ones.
  it('a conflict opened before a moved push is still pushed and reported', async () => {
    const laptop = await machine('laptop', {
      'a.txt': 'one\n',
      'other.txt': 'x\n',
    });
    await reconcile(laptop, noConflicts, fence);
    botEdits('a.txt', 'bot\n');
    await writeFile(join(laptop.paths.root, 'a.txt'), 'mine\n');
    let calls = 0;
    const done = await reconcile(
      laptop,
      async () => {
        calls += 1;
        if (calls === 1) {
          // A second bot commit lands on the hub while this attempt is still merging, so this
          // attempt's own push (main + conflict ref 1) is rejected as moved.
          botPushes(
            (work) => writeFileSync(join(work, 'other.txt'), 'bot other\n'),
            'run run_2',
          );
        }
        return calls;
      },
      fence,
    );
    expect(done.conflicts).toEqual([{ n: 1, path: 'a.txt' }]);
    expect(
      execFileSync('git', [
        '--git-dir',
        hub,
        'show',
        `${CONFLICT_REF}1:a.txt`,
      ]).toString(),
    ).toBe('bot\n');
  });

  // I-5: skipping a tracked file that grew past the cap must not delete it from the hub or from
  // other machines — only untrack it locally via skip-worktree, not `rm --cached`.
  it('a tracked file that grows past the cap is not deleted from the hub', async () => {
    const laptop = await machine('laptop', { 'grow.bin': 'small\n' });
    await reconcile(laptop, noConflicts, fence);
    expect(hubFile('grow.bin')).toBe('small\n');

    await writeFile(join(laptop.paths.root, 'grow.bin'), 'x'.repeat(2_000));
    await prepare(laptop, [], 1_000);
    await reconcile(laptop, noConflicts, fence);
    expect(hubFile('grow.bin')).toBe('small\n');

    await writeFile(join(laptop.paths.root, 'grow.bin'), 'new small\n');
    await prepare(laptop, [], 1_000);
    await reconcile(laptop, noConflicts, fence);
    expect(hubFile('grow.bin')).toBe('new small\n');
  });

  // I-3 gap A: guardUntracked's diff must not let git's default rename detection hide an added
  // path that collides with a local excluded file — a hub-side `git mv` onto that path is a
  // rename, not an "add", so plain --diff-filter=A alone misses it.
  it('refuses a hub rename onto a local excluded file', async () => {
    const laptop = await machine('laptop', {
      '.gitignore': 'out.log\n',
      'x.txt': 'tracked\n',
      'out.log': 'local log\n',
    });
    await reconcile(laptop, noConflicts, fence);
    botPushes((work) =>
      execFileSync('git', ['-C', work, 'mv', 'x.txt', 'out.log']),
    );
    await expect(reconcile(laptop, noConflicts, fence)).rejects.toThrow(
      /not synced/,
    );
    expect(await readFile(join(laptop.paths.root, 'out.log'), 'utf8')).toBe(
      'local log\n',
    );
  });

  // I-3 gap B: guardUntracked must also catch a local excluded FILE blocking a path the hub adds
  // one level deeper (e.g. `foo/bar`) — lstat on the added path itself fails with ENOTDIR since
  // `foo` isn't a directory, so the exact-path check alone misses it; every parent prefix of an
  // added path must be checked too, naming the blocking parent.
  it('refuses a hub directory where a local excluded file stands', async () => {
    const laptop = await machine('laptop', {
      '.gitignore': 'foo\n',
      'root.txt': 'r\n',
      foo: 'i am a local excluded file\n',
    });
    await reconcile(laptop, noConflicts, fence);
    botPushes((work) => {
      mkdirSync(join(work, 'foo'), { recursive: true });
      writeFileSync(join(work, 'foo', 'bar'), 'dir content\n');
    });
    await expect(reconcile(laptop, noConflicts, fence)).rejects.toThrow(
      /has them: foo;/,
    );
    expect(await readFile(join(laptop.paths.root, 'foo'), 'utf8')).toBe(
      'i am a local excluded file\n',
    );
  });

  // A tracked (synced) file turning into a directory on the hub is a legitimate change git's own
  // read-tree already handles correctly — the parent-prefix check must not flag it, since the
  // parent path IS a tracked blob in HEAD, distinguishing it from gap B's excluded-file case.
  it('a tracked file the hub turns into a directory still syncs', async () => {
    const laptop = await machine('laptop', { foo: 'i am tracked\n' });
    await reconcile(laptop, noConflicts, fence);
    botPushes((work) => {
      execFileSync('git', ['-C', work, 'rm', '-q', 'foo']);
      mkdirSync(join(work, 'foo'), { recursive: true });
      writeFileSync(join(work, 'foo', 'bar'), 'dir content\n');
    });
    await reconcile(laptop, noConflicts, fence);
    expect(await readFile(join(laptop.paths.root, 'foo', 'bar'), 'utf8')).toBe(
      'dir content\n',
    );
  });
  // C2: info/exclude has the lowest precedence, so a project .gitignore can re-include what the
  // fence keeps out. The commit itself must drop hidden and reserved paths from the index.
  it('keeps hidden and reserved paths out of the hub even when a .gitignore re-includes them', async () => {
    const laptop = await machine('laptop', {
      'a.txt': 'a\n',
      '.gitignore': '!.env\n!.plowshare/\n',
      '.env': 'SECRET=1\n',
      'logs/.gitignore': '*\n!.gitkeep\n',
      'logs/.gitkeep': '',
    });
    await writeFile(
      join(laptop.paths.root, '.plowshare', 'notes.txt'),
      'private\n',
    );
    const done = await reconcile(laptop, noConflicts, fence);
    expect(
      execFileSync('git', ['--git-dir', hub, 'rev-parse', 'main'])
        .toString()
        .trim(),
    ).toBe(done.commit);
    const pushed = hubPaths();
    expect(pushed).toContain('a.txt');
    expect(pushed).not.toContain('.env');
    expect(pushed).not.toContain('logs/.gitkeep');
    expect(pushed.filter((p) => p.startsWith('.plowshare/'))).toEqual([]);
    expect(await readFile(join(laptop.paths.root, '.env'), 'utf8')).toBe(
      'SECRET=1\n',
    );
  });

  // A synced path that the fence later refuses (the hidden allowlist was narrowed) must stay at its
  // last-synced content on the hub: unstaging it would push a deletion to every other machine.
  it('narrowing the hidden allowlist does not delete already-synced files from the hub', async () => {
    const laptop = await machine('laptop', {
      'a.txt': 'a\n',
      '.github/ci.yml': 'ci one\n',
    });
    const wide = {
      hidden: ['.github/'] as readonly string[],
      maxFileBytes: 1_000,
    };
    await prepare(laptop, wide.hidden, wide.maxFileBytes);
    await reconcile(laptop, noConflicts, wide);
    expect(hubFile('.github/ci.yml')).toBe('ci one\n');

    const narrow = {
      hidden: ['.eslintrc'] as readonly string[],
      maxFileBytes: 1_000,
    };
    await prepare(laptop, narrow.hidden, narrow.maxFileBytes);
    await writeFile(join(laptop.paths.root, 'a.txt'), 'a two\n');
    await reconcile(laptop, noConflicts, narrow);
    expect(hubFile('a.txt')).toBe('a two\n');
    expect(hubFile('.github/ci.yml')).toBe('ci one\n');

    await writeFile(join(laptop.paths.root, '.github/ci.yml'), 'ci two\n');
    await prepare(laptop, narrow.hidden, narrow.maxFileBytes);
    await reconcile(laptop, noConflicts, narrow);
    expect(hubFile('.github/ci.yml')).toBe('ci one\n');
    expect(
      await readFile(join(laptop.paths.root, '.github/ci.yml'), 'utf8'),
    ).toBe('ci two\n');
  });

  // I3: a conflict must not be recorded on the server for a merge that never lands locally.
  it('opens no conflict when the merge cannot be applied', async () => {
    const laptop = await machine('laptop', {
      '.gitignore': 'out.log\n',
      'a.txt': 'one\n',
      'out.log': 'local log\n',
    });
    await reconcile(laptop, noConflicts, fence);
    botPushes((work) => {
      writeFileSync(join(work, 'a.txt'), 'bot\n');
      writeFileSync(join(work, 'out.log'), 'bot log\n');
    });
    await writeFile(join(laptop.paths.root, 'a.txt'), 'mine\n');
    let opened = 0;
    await expect(
      reconcile(
        laptop,
        async () => {
          opened += 1;
          return opened;
        },
        fence,
      ),
    ).rejects.toThrow(/out\.log/);
    expect(opened).toBe(0);
    expect(await readFile(join(laptop.paths.root, 'a.txt'), 'utf8')).toBe(
      'mine\n',
    );
  });
});
