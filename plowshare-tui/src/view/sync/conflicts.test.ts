import { execFileSync } from 'node:child_process';
import {
  mkdir,
  mkdtemp,
  readFile,
  realpath,
  rm,
  stat,
  symlink,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { ConflictRow } from '../../logic/union.ts';
import {
  dropConflictRef,
  finishMerge,
  mergeFileOf,
  stageMerge,
  takeTheirs,
} from './conflicts.ts';
import { shadowGit, shadowPaths } from './git.ts';
import {
  CONFLICT_REF,
  type FoundConflict,
  type Shadow,
  initShadow,
  prepare,
  reconcile,
} from './shadow.ts';

let top = '';
let hub = '';
let laptop: Shadow;
let row: ConflictRow;

beforeEach(async () => {
  top = await realpath(await mkdtemp(join(tmpdir(), 'conflicts-')));
  hub = join(top, 'hub.git');
  execFileSync('git', ['init', '-q', '--bare', '-b', 'main', hub]);
  const root = join(top, 'laptop');
  await mkdir(root);
  await writeFile(join(root, 'a.txt'), 'one\ntwo\nthree\n');
  laptop = {
    paths: shadowPaths(root),
    git: shadowGit(shadowPaths(root)),
    author: 'enzo@laptop',
  };
  await initShadow(laptop, `file://${hub}`);
  await prepare(laptop, [], 1_000_000);
  await reconcile(
    laptop,
    async () => {
      throw new Error('none');
    },
    { hidden: [], maxFileBytes: 1_000_000 },
  );

  const work = join(top, 'bot');
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
  await writeFile(join(work, 'a.txt'), 'one\nBOT\nthree\n');
  execFileSync('git', [
    '-C',
    work,
    '-c',
    'user.name=nightly-bot',
    '-c',
    'user.email=b@b',
    'commit',
    '-qam',
    'run run_7',
  ]);
  execFileSync('git', ['-C', work, 'push', '-q', 'origin', 'main']);

  await writeFile(join(root, 'a.txt'), 'one\nMINE\nthree\n');
  let found: FoundConflict | undefined;
  await reconcile(
    laptop,
    async (conflict) => {
      found = conflict;
      return 1;
    },
    { hidden: [], maxFileBytes: 1_000_000 },
  );
  row = { n: 1, ...(found as FoundConflict) };
});
afterEach(async () => {
  await rm(top, { recursive: true, force: true });
});

describe('resolving', () => {
  it('theirs writes the bot version into the working tree', async () => {
    await takeTheirs(laptop, row);
    expect(await readFile(join(laptop.paths.root, 'a.txt'), 'utf8')).toBe(
      'one\nBOT\nthree\n',
    );
  });

  it('merge stages a marked-up file outside the tree, and done copies it in', async () => {
    const staged = await stageMerge(laptop, row);
    expect(staged).toBe(mergeFileOf(laptop.paths.root, row));
    const marked = await readFile(staged, 'utf8');
    expect(marked).toContain('<<<<<<<');
    expect(marked).toContain('MINE');
    expect(marked).toContain('BOT');
    await writeFile(staged, 'one\nBOTH\nthree\n');
    await finishMerge(laptop, row);
    expect(await readFile(join(laptop.paths.root, 'a.txt'), 'utf8')).toBe(
      'one\nBOTH\nthree\n',
    );
  });

  it('done without a staged merge is refused', async () => {
    await expect(finishMerge(laptop, row)).rejects.toThrow(/merge/);
  });

  it('dropping the ref removes it locally and on the hub, twice without error', async () => {
    await dropConflictRef(laptop, row);
    await dropConflictRef(laptop, row);
    expect(() =>
      execFileSync(
        'git',
        ['--git-dir', hub, 'rev-parse', '--verify', `${CONFLICT_REF}1`],
        { stdio: 'ignore' },
      ),
    ).toThrow();
  });

  it('refuses a conflict path that climbs out of the project', async () => {
    await expect(
      takeTheirs(laptop, { ...row, path: '../escape.txt' }),
    ).rejects.toThrow(/outside the project/);
    expect(
      await stat(join(top, 'escape.txt')).then(
        () => true,
        () => false,
      ),
    ).toBe(false);
    await expect(
      finishMerge(laptop, { ...row, path: 'a/../../escape.txt' }),
    ).rejects.toThrow(/outside the project/);
  });

  it('refuses to write through a symlink', async () => {
    await rm(join(laptop.paths.root, 'a.txt'), { force: true });
    await writeFile(join(top, 'outside.txt'), 'keep');
    await symlink(join(top, 'outside.txt'), join(laptop.paths.root, 'a.txt'));
    await expect(takeTheirs(laptop, row)).rejects.toThrow(/symbolic link/);
    expect(await readFile(join(top, 'outside.txt'), 'utf8')).toBe('keep');
  });

  it('dropping a ref surfaces a hub that refuses', async () => {
    await laptop.git.ok([
      'config',
      'remote.origin.url',
      `file://${join(top, 'nope.git')}`,
    ]);
    await expect(dropConflictRef(laptop, row)).rejects.toThrow();
  });

  it('a failed merge-file leaves no temp files', async () => {
    const oid = (
      await laptop.git.run(['hash-object', '-w', '--stdin'], {
        input: Buffer.from([0, 1, 2, 0, 255]),
      })
    ).stdout
      .toString('utf8')
      .trim();
    await expect(
      stageMerge(laptop, { ...row, theirsBlob: oid }),
    ).rejects.toThrow();
    const scratch = join(laptop.paths.root, '.plowshare', 'conflicts', '1');
    expect(
      await stat(join(scratch, '.ours')).then(
        () => true,
        () => false,
      ),
    ).toBe(false);
    expect(
      await stat(join(scratch, '.base')).then(
        () => true,
        () => false,
      ),
    ).toBe(false);
    expect(
      await stat(join(scratch, '.theirs')).then(
        () => true,
        () => false,
      ),
    ).toBe(false);
  });

  it('refuses reserved segments in any letter case', async () => {
    for (const path of [
      '.GIT/hooks/post-checkout',
      'sub/.Git/config',
      '.PlowShare/project',
      '.git./x',
      'GIT~1/config',
    ]) {
      await expect(takeTheirs(laptop, { ...row, path })).rejects.toThrow(
        /outside the project/,
      );
      expect(
        await stat(join(laptop.paths.root, path)).then(
          () => true,
          () => false,
        ),
      ).toBe(false);
    }
  });

  it('refuses an NTFS alternate-data-stream suffix onto a reserved name', async () => {
    const path = '.git::$INDEX_ALLOCATION/hooks/x';
    await expect(takeTheirs(laptop, { ...row, path })).rejects.toThrow(
      /outside the project/,
    );
    expect(
      await stat(join(laptop.paths.root, '.git::$INDEX_ALLOCATION')).then(
        () => true,
        () => false,
      ),
    ).toBe(false);
  });

  it("refuses a path that was never in this project's sync history", async () => {
    await expect(
      takeTheirs(laptop, { ...row, path: 'never-synced.txt' }),
    ).rejects.toThrow(
      /never-synced\.txt is not a path in this project's sync history/,
    );
    expect(
      await stat(join(laptop.paths.root, 'never-synced.txt')).then(
        () => true,
        () => false,
      ),
    ).toBe(false);
  });

  // A bare ':' in an otherwise ordinary name (e.g. a timestamp) is legal on macOS and Linux;
  // `contained` must not refuse it there as "outside the project" — only Windows, where a ':'
  // can start an alternate data stream or a drive letter, gets that check. The path still fails
  // (this machine never synced it), but for the history reason, not the containment one.
  it.skipIf(process.platform === 'win32')(
    'a bare colon in an ordinary name is not refused as containment on non-win32',
    async () => {
      const path = '2026-09-14T10:00.md';
      await expect(takeTheirs(laptop, { ...row, path })).rejects.toThrow(
        /is not a path in this project's sync history/,
      );
    },
  );
});

describe('blob ids and a recreated shadow', () => {
  it('refuses a blob id that is not a hex object id and leaves the working file alone', async () => {
    await expect(
      takeTheirs(laptop, { ...row, theirsBlob: 'zzzz' }),
    ).rejects.toThrow(/invalid blob id/);
    await expect(
      takeTheirs(laptop, { ...row, theirsBlob: 'main:a.txt' }),
    ).rejects.toThrow(/invalid blob id/);
    expect(await readFile(join(laptop.paths.root, 'a.txt'), 'utf8')).toBe(
      'one\nMINE\nthree\n',
    );
    await expect(
      stageMerge(laptop, { ...row, baseBlob: '../x' }),
    ).rejects.toThrow(/invalid blob id/);
  });

  it('a well-formed blob id the shadow cannot read is refused rather than written as empty', async () => {
    await expect(
      takeTheirs(laptop, { ...row, theirsBlob: 'f'.repeat(40) }),
    ).rejects.toThrow();
    expect(await readFile(join(laptop.paths.root, 'a.txt'), 'utf8')).toBe(
      'one\nMINE\nthree\n',
    );
  });

  it('a recreated shadow fetches the conflict ref and can still take theirs for a path it deleted', async () => {
    const root = join(top, 'desk');
    await mkdir(root);
    await writeFile(join(root, 'keep.txt'), 'k\n');
    await writeFile(join(root, 'gone.txt'), 'base\n');
    const fence = { hidden: [] as readonly string[], maxFileBytes: 1_000_000 };
    const hub2 = join(top, 'hub2.git');
    execFileSync('git', ['init', '-q', '--bare', '-b', 'main', hub2]);
    let desk: Shadow = {
      paths: shadowPaths(root),
      git: shadowGit(shadowPaths(root)),
      author: 'enzo@desk',
    };
    await initShadow(desk, `file://${hub2}`);
    await prepare(desk, [], 1_000_000);
    await reconcile(
      desk,
      async () => {
        throw new Error('none');
      },
      fence,
    );

    const work = join(top, 'bot2');
    execFileSync('git', ['clone', '-q', hub2, work]);
    execFileSync('git', [
      '-C',
      work,
      'checkout',
      '-q',
      '-B',
      'main',
      'origin/main',
    ]);
    await writeFile(join(work, 'gone.txt'), 'bot kept it\n');
    execFileSync('git', [
      '-C',
      work,
      '-c',
      'user.name=nightly-bot',
      '-c',
      'user.email=b@b',
      'commit',
      '-qam',
      'run run_8',
    ]);
    execFileSync('git', ['-C', work, 'push', '-q', 'origin', 'main']);

    await rm(join(root, 'gone.txt'));
    let found: FoundConflict | undefined;
    await reconcile(
      desk,
      async (conflict) => {
        found = conflict;
        return 1;
      },
      fence,
    );
    const deleted: ConflictRow = { n: 1, ...(found as FoundConflict) };
    expect(deleted.oursBlob).toBeUndefined();

    await rm(desk.paths.gitDir, { recursive: true, force: true });
    desk = {
      paths: shadowPaths(root),
      git: shadowGit(shadowPaths(root)),
      author: 'enzo@desk',
    };
    await initShadow(desk, `file://${hub2}`);
    await prepare(desk, [], 1_000_000);
    await reconcile(
      desk,
      async () => {
        throw new Error('none');
      },
      fence,
    );
    expect(() =>
      execFileSync(
        'git',
        [
          '--git-dir',
          desk.paths.gitDir,
          'rev-parse',
          '--verify',
          `${CONFLICT_REF}1`,
        ],
        { stdio: 'ignore' },
      ),
    ).toThrow();

    await takeTheirs(desk, deleted);
    expect(await readFile(join(root, 'gone.txt'), 'utf8')).toBe(
      'bot kept it\n',
    );
  });
});
