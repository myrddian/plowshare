import type * as FileSystem from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import {
  chmod,
  lstat,
  mkdtemp,
  mkdir,
  readFile,
  realpath,
  rm,
  symlink,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  FileReply,
  FileResult,
  Span,
} from 'plowshare-client-ts/binding/files';
import { allows, binaryKind, enforcing } from './enforcer.ts';

/**
 * Every directory the enforcer lists, in order — the one seam the walk's reach is
 * measured through. `readdir` itself is unchanged: the mock passes through.
 */
const listed = vi.hoisted((): string[] => []);
/**
 * Run once after the next `readFile` the enforcer makes, before it is answered —
 * the seam a file removed between an edit's read and its write is made through.
 */
const afterRead = vi.hoisted(
  (): { next: (() => Promise<void>) | undefined } => ({ next: undefined }),
);
vi.mock('node:fs/promises', async (importOriginal) => {
  const real = await importOriginal<typeof FileSystem>();
  return {
    ...real,
    readdir: ((path: string, ...rest: unknown[]) => {
      listed.push(String(path));
      return (real.readdir as (...args: unknown[]) => unknown)(path, ...rest);
    }) as typeof real.readdir,
    readFile: (async (...args: unknown[]) => {
      const read = await (
        real.readFile as (...args: unknown[]) => Promise<unknown>
      )(...args);
      const then = afterRead.next;
      afterRead.next = undefined;
      await then?.();
      return read;
    }) as typeof real.readFile,
  };
});

let root = '';
let outside = '';
/** Directories a test locked, unlocked again before they are removed. */
const locked: string[] = [];
/** Whether this process ignores permissions, as root does — which a lock cannot test. */
const permissive =
  typeof process.getuid === 'function' && process.getuid() === 0;

beforeEach(async () => {
  root = await realpath(await mkdtemp(join(tmpdir(), 'enforcer-root-')));
  outside = await realpath(await mkdtemp(join(tmpdir(), 'enforcer-outside-')));
  await mkdir(join(root, 'src'), { recursive: true });
  await writeFile(
    join(root, 'src', 'a.ts'),
    'export const a = 1\nexport const b = 2\n',
  );
  await writeFile(join(root, 'README.md'), '# hello\n');
  await mkdir(join(root, '.git'), { recursive: true });
  await writeFile(join(root, '.git', 'config'), 'secret\n');
  await mkdir(join(root, '.plowshare', 'bots'), { recursive: true });
  await writeFile(join(root, '.plowshare', 'project'), 'ledger\n');
  await writeFile(
    join(root, '.plowshare', 'bots', 'sophron.md'),
    '---\nname: sophron\n---\n',
  );
  await writeFile(join(root, '.plowshare', 'bots', 'default'), 'sophron\n');
  await writeFile(join(outside, 'elsewhere.txt'), 'not yours\n');
});

afterEach(async () => {
  while (locked.length > 0) {
    await chmod(locked.pop() ?? '', 0o755);
  }
  listed.length = 0;
  await rm(root, { recursive: true, force: true });
  await rm(outside, { recursive: true, force: true });
});

function asking(
  op: string,
  more: Record<string, unknown> = {},
): Promise<FileReply> {
  return enforcing(root)({ id: 'r', op, ...more });
}

describe('the fence', () => {
  it('lets a reader in below the root and nowhere else', () => {
    expect(allows(root, join(root, 'src', 'a.ts'), 'reading')).toBe(true);
    expect(allows(root, join(outside, 'elsewhere.txt'), 'reading')).toBe(false);
    expect(allows(root, `${root}-sibling${sep}x`, 'reading')).toBe(false);
  });

  it('keeps every hidden path from a model, the definitions included', () => {
    expect(allows(root, join(root, '.git', 'config'), 'reading')).toBe(false);
    expect(
      allows(root, join(root, '.plowshare', 'bots', 'default'), 'reading'),
    ).toBe(false);
    expect(
      allows(root, join(root, '.plowshare', 'agents', 'x.md'), 'reading'),
    ).toBe(false);
  });

  it('lets the harness read definitions and project metadata, and nothing else hidden', () => {
    expect(
      allows(root, join(root, '.plowshare', 'bots', 'default'), 'definitions'),
    ).toBe(true);
    expect(
      allows(root, join(root, '.plowshare', 'agents', 'x.md'), 'definitions'),
    ).toBe(true);
    expect(
      allows(root, join(root, '.plowshare', 'project'), 'definitions'),
    ).toBe(true);
    expect(
      allows(
        root,
        join(root, '.plowshare', 'bots', 'deep', 'x.md'),
        'definitions',
      ),
    ).toBe(false);
    expect(allows(root, join(root, '.git', 'config'), 'definitions')).toBe(
      false,
    );
    expect(
      allows(root, join(root, '.plowshare', 'bots', 'default'), 'writing'),
    ).toBe(false);
  });

  it('lets the harness read exactly .plowshare/environment.yml beside the definitions', () => {
    expect(
      allows(root, join(root, '.plowshare', 'environment.yml'), 'definitions'),
    ).toBe(true);
    expect(
      allows(root, join(root, '.plowshare', 'environment.yml'), 'reading'),
    ).toBe(false);
    expect(
      allows(root, join(root, '.plowshare', 'environment.yml'), 'writing'),
    ).toBe(false);
    expect(
      allows(root, join(root, '.plowshare', 'environment.yaml'), 'definitions'),
    ).toBe(false);
    expect(
      allows(
        root,
        join(root, '.plowshare', 'bots', 'environment.yml'),
        'definitions',
      ),
    ).toBe(true);
    expect(
      allows(
        root,
        join(root, 'src', '.plowshare', 'environment.yml'),
        'definitions',
      ),
    ).toBe(false);
  });
});

describe('the six ops', () => {
  it('keeps the root manifest out of agent mutations while preserving the CLI executable', async () => {
    const manifest = join(root, 'plowshare');
    expect(
      (
        await asking('write', {
          path: 'plowshare',
          content: '{"version":1,"name":"house"}',
        })
      ).outcome,
    ).toBe('refused');
    await writeFile(manifest, '{"version":1,"name":"house"}');
    expect((await asking('delete', { path: 'plowshare' })).outcome).toBe(
      'refused',
    );
    await writeFile(manifest, '#!/bin/sh\necho test\n');
    expect(
      (
        await asking('write', {
          path: 'plowshare',
          content: '#!/bin/sh\necho changed\n',
        })
      ).outcome,
    ).toBe('ok');
  });
  it('names its one root', async () => {
    expect((await asking('roots')).paths).toEqual([root]);
  });

  it('reads a window, relative paths resolved against the root', async () => {
    const reply = await asking('read', {
      path: 'src/a.ts',
      offset: 1,
      limit: 5,
    });
    expect(reply.outcome).toBe('ok');
    expect(reply.span).toEqual({
      lines: ['export const b = 2'],
      offset: 1,
      totalLines: 2,
      more: false,
      stoppedBy: 'end',
    });
  });

  it('refuses a path outside, naming the root to ask about instead', async () => {
    const reply = await asking('read', {
      path: join(outside, 'elsewhere.txt'),
    });
    expect(reply.outcome).toBe('refused');
    expect(reply.sentence, 'the server words it').toBeUndefined();
    expect(reply.result).toEqual({
      version: 1,
      kind: 'refused',
      op: 'read',
      path: join(outside, 'elsewhere.txt'),
      reason: 'outside',
      roots: [root],
    });
  });

  it('refuses a link that leads out of the root', async () => {
    await symlink(join(outside, 'elsewhere.txt'), join(root, 'escape.txt'));
    expect((await asking('read', { path: 'escape.txt' })).outcome).toBe(
      'refused',
    );
  });

  it('reads the definitions for the harness, and refuses the same read to a model', async () => {
    expect(
      (
        await asking('read', {
          path: '.plowshare/bots/default',
          purpose: 'definitions',
        })
      ).span?.lines,
    ).toEqual(['sophron']);
    expect(
      (await asking('read', { path: '.plowshare/bots/default' })).outcome,
    ).toBe('refused');
    expect(
      (await asking('read', { path: '.git/config', purpose: 'definitions' }))
        .outcome,
    ).toBe('refused');
  });

  it('stats a file by its line count', async () => {
    expect((await asking('stat', { path: 'src/a.ts' })).span).toEqual({
      lines: [],
      offset: 0,
      totalLines: 2,
      more: true,
      stoppedBy: 'lines',
    });
  });

  it('globs relative to the root, answers absolute, and skips hidden trees', async () => {
    expect((await asking('glob', { pattern: '**/*.ts' })).paths).toEqual([
      join(root, 'src', 'a.ts'),
    ]);
    expect((await asking('glob', { pattern: '**' })).paths).not.toContain(
      join(root, '.git', 'config'),
    );
  });

  it('lists the definitions for the harness, and shows a model none of them', async () => {
    expect(
      (
        await asking('glob', {
          pattern: '.plowshare/bots/*',
          purpose: 'definitions',
        })
      ).paths,
    ).toEqual([
      join(root, '.plowshare', 'bots', 'default'),
      join(root, '.plowshare', 'bots', 'sophron.md'),
    ]);
    expect(
      (await asking('glob', { pattern: '.plowshare/bots/*' })).paths,
    ).toEqual([]);
    expect((await asking('glob', { pattern: '**' })).paths).not.toContain(
      join(root, '.plowshare', 'bots', 'sophron.md'),
    );
  });

  it('never lets the harness mark reach a write', async () => {
    expect(
      (
        await asking('write', {
          path: '.plowshare/bots/default',
          content: 'x',
          purpose: 'definitions',
        })
      ).outcome,
    ).toBe('refused');
  });

  it('refuses an absolute pattern and says what to write instead', async () => {
    const reply = await asking('glob', { pattern: '/src/*.ts' });
    expect(reply.outcome).toBe('refused');
    expect(reply.result).toEqual({
      version: 1,
      kind: 'refused',
      op: 'glob',
      reason: 'absolute-pattern',
      pattern: '/src/*.ts',
    });
  });

  it('greps the tree, and one file, by line offset', async () => {
    expect(
      (await asking('grep', { needle: 'const b' })).found?.matches,
    ).toEqual([
      {
        path: join(root, 'src', 'a.ts'),
        offset: 1,
        line: 'export const b = 2',
        truncated: false,
      },
    ]);
    expect(
      (await asking('grep', { needle: 'hello', path: 'README.md' })).found
        ?.matches,
    ).toHaveLength(1);
  });

  it("sweeps nothing hidden into a model's grep, nor into one the harness marked", async () => {
    for (const purpose of [{}, { purpose: 'definitions' }]) {
      expect(
        (await asking('grep', { needle: 'secret', ...purpose })).found?.matches,
      ).toEqual([]);
      expect(
        (await asking('grep', { needle: 'name: sophron', ...purpose })).found
          ?.matches,
      ).toEqual([]);
      expect(
        (await asking('grep', { needle: 'sophron', ...purpose })).found
          ?.matches,
      ).toEqual([]);
    }
  });

  it('refuses a write through a dangling link in the middle, and makes nothing outside', async () => {
    await symlink(join(outside, 'newdir'), join(root, 'd'));

    const reply = await asking('write', {
      path: 'd/x.txt',
      content: 'escaped\n',
    });

    expect(reply.outcome).toBe('refused');
    expect(reply.result?.reason).toBe('outside');
    await expect(
      readFile(join(outside, 'newdir', 'x.txt'), 'utf8'),
    ).rejects.toThrow();
    await expect(readFile(join(outside, 'newdir'))).rejects.toThrow();
  });

  it('writes, making the directories on the way', async () => {
    expect(
      (await asking('write', { path: 'notes/today.md', content: 'hi\n' }))
        .outcome,
    ).toBe('ok');
    expect(await readFile(join(root, 'notes', 'today.md'), 'utf8')).toBe(
      'hi\n',
    );
  });

  it('will not write the definitions, or through a link', async () => {
    expect(
      (await asking('write', { path: '.plowshare/bots/default', content: 'x' }))
        .outcome,
    ).toBe('refused');
    await symlink(join(outside, 'elsewhere.txt'), join(root, 'link.txt'));
    expect(
      (await asking('write', { path: 'link.txt', content: 'x' })).outcome,
    ).toBe('refused');
    expect(await readFile(join(outside, 'elsewhere.txt'), 'utf8')).toBe(
      'not yours\n',
    );
  });

  it('will not write through a dangling link either', async () => {
    await symlink(join(outside, 'not-yet.txt'), join(root, 'dangling.txt'));
    expect(
      (await asking('write', { path: 'dangling.txt', content: 'x' })).outcome,
    ).toBe('refused');
  });

  it('refuses an op it does not know, naming it', async () => {
    const reply = await asking('chmod', { path: 'README.md' });
    expect(reply.outcome).toBe('refused');
    expect(reply.result).toEqual({
      version: 1,
      kind: 'refused',
      op: 'chmod',
      reason: 'unknown-op',
    });
  });

  it('says unavailable, not refused, when the root itself has gone, and why as facts', async () => {
    await rm(root, { recursive: true, force: true });
    const reply = await asking('roots');
    expect(reply.outcome).toBe('unavailable');
    expect(reply.sentence).toBeUndefined();
    expect(reply.result).toEqual({
      version: 1,
      kind: 'unavailable',
      op: 'roots',
      reason: 'root-gone',
      path: root,
    });
  });

  it('says a hidden path is hidden and a directory read is a directory', async () => {
    const hidden = await asking('read', { path: '.git/config' });
    const directory = await asking('stat', { path: 'src' });
    expect(hidden.result).toEqual({
      version: 1,
      kind: 'refused',
      op: 'read',
      path: '.git/config',
      reason: 'hidden',
      roots: [root],
    });
    expect(directory.result).toEqual({
      version: 1,
      kind: 'refused',
      op: 'stat',
      path: 'src',
      reason: 'directory',
    });
  });

  it('refuses a pattern it cannot compile naming the pattern written, not a spelling of it', async () => {
    const reply = await asking('glob', { pattern: '**/[ab' });
    expect(reply.result).toEqual({
      version: 1,
      kind: 'refused',
      op: 'glob',
      reason: 'bad-pattern',
      pattern: '**/[ab',
      detail: 'it opens a [ it never closes',
    });
  });
});

describe('how far a glob or a grep walks', () => {
  async function lock(directory: string): Promise<void> {
    await mkdir(directory, { recursive: true });
    await writeFile(join(directory, 'inside.ts'), 'export const locked = 1\n');
    await chmod(directory, 0o000);
    locked.push(directory);
  }

  it.skipIf(permissive)(
    'skips a directory it cannot read rather than refusing every glob',
    async () => {
      await lock(join(root, 'locked'));

      const everything = await asking('glob', { pattern: '**/*.ts' });
      expect(everything.outcome).toBe('ok');
      expect(everything.paths).toEqual([join(root, 'src', 'a.ts')]);
      // THE HARNESS'S OWN GLOB, which is how one unreadable directory used to
      // take every client-side definition away with nothing said.
      expect(
        (
          await asking('glob', {
            pattern: '.plowshare/bots/*',
            purpose: 'definitions',
          })
        ).paths,
      ).toContain(join(root, '.plowshare', 'bots', 'sophron.md'));
      expect(
        (await asking('grep', { needle: 'const a' })).found?.matches,
      ).toHaveLength(1);
    },
  );

  it.skipIf(permissive)(
    'still refuses when the directory it starts in cannot be read',
    async () => {
      await lock(join(root, 'locked'));

      expect((await asking('glob', { pattern: 'locked/*.ts' })).outcome).toBe(
        'refused',
      );
    },
  );

  it("starts at the pattern's literal directories, so the definitions never walk node_modules", async () => {
    await mkdir(join(root, 'node_modules', 'left-pad'), { recursive: true });
    await writeFile(join(root, 'node_modules', 'left-pad', 'index.js'), '\n');
    listed.length = 0;

    const found = await asking('glob', {
      pattern: '.plowshare/bots/*',
      purpose: 'definitions',
    });

    expect(found.paths).toEqual([
      join(root, '.plowshare', 'bots', 'default'),
      join(root, '.plowshare', 'bots', 'sophron.md'),
    ]);
    expect(listed).toEqual([join(root, '.plowshare', 'bots')]);
  });

  it('sweeps only the directory a grep was pointed at', async () => {
    await mkdir(join(root, 'node_modules'), { recursive: true });
    await writeFile(join(root, 'node_modules', 'x.ts'), 'export const a = 3\n');
    listed.length = 0;

    const found = await asking('grep', { needle: 'const a', path: 'src' });

    expect(found.found?.matches.map((match) => match.path)).toEqual([
      join(root, 'src', 'a.ts'),
    ]);
    expect(listed).toEqual([join(root, 'src')]);
  });

  it('keeps the hidden rules when the walk starts below the root', async () => {
    expect((await asking('glob', { pattern: '.git/*' })).paths).toEqual([]);
    expect(
      (await asking('glob', { pattern: '.git/*', purpose: 'definitions' }))
        .paths,
    ).toEqual([]);
    expect(
      (
        await asking('glob', {
          pattern: '.plowshare/*',
          purpose: 'definitions',
        })
      ).paths,
    ).toEqual([]);
    expect((await asking('glob', { pattern: 'no/such/dir/*' })).paths).toEqual(
      [],
    );
    await symlink(outside, join(root, 'away'));
    expect((await asking('glob', { pattern: 'away/*' })).paths).toEqual([]);
  });
});

describe('what it will not pretend to read', () => {
  it('recognises a PDF and the pictures the server takes', () => {
    expect(binaryKind(new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d]))).toBe(
      'PDF',
    );
    expect(binaryKind(new Uint8Array([0x89, 0x50, 0x4e, 0x47]))).toBe('png');
    expect(binaryKind(new Uint8Array([0x68, 0x69]))).toBeUndefined();
  });

  it('refuses bytes that are not UTF-8 with the facts the MCP client sends', async () => {
    await writeFile(
      join(root, 'blob.bin'),
      Buffer.from([0xff, 0xfe, 0x80, 0xc3]),
    );
    expect((await asking('read', { path: 'blob.bin' })).result).toEqual({
      version: 1,
      kind: 'not-text',
      op: 'read',
      path: 'blob.bin',
      reason: 'not-utf8',
    });
  });

  it('refuses a PDF or a picture by its format, which the server words', async () => {
    await writeFile(
      join(root, 'shot.png'),
      Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a]),
    );
    expect((await asking('read', { path: 'shot.png' })).result).toEqual({
      version: 1,
      kind: 'not-text',
      op: 'read',
      path: 'shot.png',
      reason: 'unconverted',
      format: 'png',
    });
  });
});

describe('a create-only write', () => {
  it('refuses to replace a file that is there, and leaves it as it was', async () => {
    const reply = await asking('write', {
      path: 'README.md',
      content: 'gone\n',
      createOnly: true,
    });

    expect(reply.outcome).toBe('refused');
    expect(reply.result).toEqual({
      version: 1,
      kind: 'refused',
      op: 'write',
      path: 'README.md',
      reason: 'exists',
    });
    expect(reply.sentence, 'the server words it').toBeUndefined();
    expect(await readFile(join(root, 'README.md'), 'utf8')).toBe('# hello\n');
  });

  it('creates a file that is not there, directories and all', async () => {
    expect(
      (
        await asking('write', {
          path: 'new/one.md',
          content: 'hi',
          createOnly: true,
        })
      ).outcome,
    ).toBe('ok');
    expect(await readFile(join(root, 'new', 'one.md'), 'utf8')).toBe('hi');
  });

  it('replaces as it always did when the flag is false or absent', async () => {
    expect(
      (
        await asking('write', {
          path: 'README.md',
          content: 'a',
          createOnly: false,
        })
      ).outcome,
    ).toBe('ok');
    expect(
      (await asking('write', { path: 'README.md', content: 'b' })).outcome,
    ).toBe('ok');
    expect(await readFile(join(root, 'README.md'), 'utf8')).toBe('b');
  });
});

describe('an edit', () => {
  it('changes the one occurrence and leaves CRLFs exactly as they were', async () => {
    await writeFile(join(root, 'crlf.txt'), 'one\r\ntwo\r\nthree\r\n');

    const reply = await asking('edit', {
      path: 'crlf.txt',
      replacing: 'two',
      content: '2',
    });

    expect(reply.outcome).toBe('ok');
    expect(await readFile(join(root, 'crlf.txt'))).toEqual(
      Buffer.from('one\r\n2\r\nthree\r\n'),
    );
  });

  it('leaves a missing final newline missing, and a byte-order mark in place', async () => {
    await writeFile(join(root, 'bare.txt'), 'one\ntwo');
    await writeFile(
      join(root, 'bom.txt'),
      Buffer.from([0xef, 0xbb, 0xbf, 0x61, 0x62]),
    );

    expect(
      (
        await asking('edit', {
          path: 'bare.txt',
          replacing: 'one',
          content: '1',
        })
      ).outcome,
    ).toBe('ok');
    expect(
      (await asking('edit', { path: 'bom.txt', replacing: 'b', content: 'c' }))
        .outcome,
    ).toBe('ok');

    expect(await readFile(join(root, 'bare.txt'))).toEqual(
      Buffer.from('1\ntwo'),
    );
    expect(await readFile(join(root, 'bom.txt'))).toEqual(
      Buffer.from([0xef, 0xbb, 0xbf, 0x61, 0x63]),
    );
  });

  it('may replace with nothing', async () => {
    expect(
      (
        await asking('edit', {
          path: 'README.md',
          replacing: ' hello',
          content: '',
        })
      ).outcome,
    ).toBe('ok');
    expect(await readFile(join(root, 'README.md'), 'utf8')).toBe('#\n');
  });

  it('refuses text that is absent, or there more than once, and changes nothing', async () => {
    const absent = await asking('edit', {
      path: 'src/a.ts',
      replacing: 'const c',
      content: 'x',
    });
    const twice = await asking('edit', {
      path: 'src/a.ts',
      replacing: 'export',
      content: 'x',
    });
    const empty = await asking('edit', {
      path: 'src/a.ts',
      replacing: '',
      content: 'x',
    });

    expect(absent.outcome).toBe('refused');
    expect(absent.result?.kind).toBe('no-match');
    expect(absent.result?.path).toBe('src/a.ts');
    expect(twice.outcome).toBe('refused');
    expect(twice.result).toEqual({
      version: 1,
      kind: 'many-matches',
      op: 'edit',
      path: 'src/a.ts',
      count: 2,
    });
    expect(empty.outcome).toBe('refused');
    expect(empty.result?.reason).toBe('empty-old');
    expect(await readFile(join(root, 'src', 'a.ts'), 'utf8')).toBe(
      'export const a = 1\nexport const b = 2\n',
    );
  });

  it('needs both the text to replace and the text replacing it', async () => {
    const noOld = await asking('edit', { path: 'README.md', content: 'x' });
    const noNew = await asking('edit', {
      path: 'README.md',
      replacing: 'hello',
    });

    expect(noOld.result).toEqual({
      version: 1,
      kind: 'refused',
      op: 'edit',
      reason: 'missing',
      argument: 'replacing',
    });
    expect(noNew.result).toEqual({
      version: 1,
      kind: 'refused',
      op: 'edit',
      reason: 'missing',
      argument: 'content',
    });
    expect([noOld.outcome, noNew.outcome]).toEqual(['refused', 'refused']);
  });

  it('refuses a file that is absent, a directory, a link, or not UTF-8', async () => {
    await symlink(join(root, 'README.md'), join(root, 'inner-link.md'));
    await writeFile(join(root, 'blob.bin'), Buffer.from([0x61, 0xff, 0xfe]));

    const absent = await asking('edit', {
      path: 'nope.txt',
      replacing: 'a',
      content: 'b',
    });
    const directory = await asking('edit', {
      path: 'src',
      replacing: 'a',
      content: 'b',
    });
    const link = await asking('edit', {
      path: 'inner-link.md',
      replacing: 'hello',
      content: 'b',
    });
    const binary = await asking('edit', {
      path: 'blob.bin',
      replacing: 'a',
      content: 'b',
    });

    expect(absent.result).toEqual({
      version: 1,
      kind: 'no-file',
      op: 'edit',
      path: 'nope.txt',
    });
    expect(directory.result?.reason).toBe('directory');
    expect(link.result?.reason).toBe('link');
    expect(binary.result).toEqual({
      version: 1,
      kind: 'not-text',
      op: 'edit',
      path: 'blob.bin',
      reason: 'not-utf8',
    });
    expect(
      [absent, directory, link, binary].map((reply) => reply.sentence),
    ).toEqual([undefined, undefined, undefined, undefined]);
    expect(
      [absent, directory, link, binary].map((reply) => reply.outcome),
    ).toEqual(['refused', 'refused', 'refused', 'refused']);
    expect(await readFile(join(root, 'README.md'), 'utf8')).toBe('# hello\n');
  });

  /*
   * What the answer reports — `ClientEnforcerTest`'s cases, so the terminal
   * client and the Java one report the same facts, which the server words.
   */
  const numbered = (n: number): string =>
    Array.from({ length: n }, (_, i) => `l${i}\n`).join('');

  it('reports the changed lines, numbered as this machine reads them', async () => {
    await writeFile(join(root, 'A.txt'), numbered(20));

    const reply = await asking('edit', {
      path: 'A.txt',
      replacing: 'l10\n',
      content: 'A\nB\n',
    });

    expect(reply.outcome).toBe('ok');
    expect(reply.sentence, 'the server words it').toBeUndefined();
    expect(reply.result).toEqual({
      version: 1,
      kind: 'edited',
      op: 'edit',
      path: 'A.txt',
      first: 10,
      last: 11,
      removed: false,
      excerpt: {
        from: 7,
        to: 14,
        total: 21,
        lines: ['l7', 'l8', 'l9', 'A', 'B', 'l11', 'l12', 'l13'],
      },
    });
    const read = await asking('read', { path: 'A.txt', offset: 7, limit: 8 });
    expect(
      read.span?.lines,
      'offset 7 of a read is the first line the edit showed',
    ).toEqual(['l7', 'l8', 'l9', 'A', 'B', 'l11', 'l12', 'l13']);
  });

  it('reports a long replacement by its ends and the gap between them', async () => {
    await writeFile(join(root, 'A.txt'), numbered(20));

    const reply = await asking('edit', {
      path: 'A.txt',
      replacing: 'l10\n',
      content: 'n\n'.repeat(100),
    });

    expect(reply.outcome).toBe('ok');
    expect(reply.result?.excerpt?.gap).toBe(20);
    expect(reply.result?.excerpt?.from).toBe(7);
    expect(reply.result?.excerpt?.to).toBe(112);
    expect(reply.result?.excerpt?.lines.length).toBe(40);
  });

  it('answers a whole-file write with what it wrote and no words', async () => {
    const reply = await asking('write', { path: 'W.txt', content: 'a\nb\n' });

    expect(reply.outcome).toBe('ok');
    expect(reply.sentence).toBeUndefined();
    expect(reply.result).toEqual({
      version: 1,
      kind: 'written',
      op: 'write',
      path: 'W.txt',
      bytes: 4,
      lines: 2,
    });
  });

  it("says so when an edit differs only in indentation, and shows the file's text", async () => {
    await writeFile(join(root, 'A.java'), 'class A {\n    int x = 1;\n}\n');

    const reply = await asking('edit', {
      path: 'A.java',
      replacing: 'class A {\n  int x = 1;',
      content: 'class A {\n  int x = 2;',
    });

    expect(reply.outcome).toBe('refused');
    expect(reply.result).toEqual({
      version: 1,
      kind: 'no-match',
      op: 'edit',
      path: 'A.java',
      near: 'whitespace',
      excerpt: {
        from: 0,
        to: 1,
        total: 3,
        lines: ['class A {', '    int x = 1;'],
      },
    });
    expect(
      await readFile(join(root, 'A.java'), 'utf8'),
      'shown, never applied',
    ).toBe('class A {\n    int x = 1;\n}\n');
  });

  it('names a look-alike character', async () => {
    await writeFile(join(root, 'A.md'), 'a well-known fact\n');

    const reply = await asking('edit', {
      path: 'A.md',
      replacing: 'well‑known',
      content: 'famous',
    });

    expect(reply.outcome).toBe('refused');
    expect(reply.result?.differences).toEqual([{ sent: 0x2011, there: 0x2d }]);
    expect(await readFile(join(root, 'A.md'), 'utf8')).toBe(
      'a well-known fact\n',
    );
  });

  it('shows a paraphrase the closest lines, and an unrelated one nothing', async () => {
    await writeFile(
      join(root, 'A.java'),
      'a();\nif (ready) {\n    go();\n}\nb();\n',
    );

    const near = await asking('edit', {
      path: 'A.java',
      replacing: 'if (ready) {\n    start();\n}',
      content: 'x',
    });
    const far = await asking('edit', {
      path: 'A.java',
      replacing: 'zzz',
      content: 'x',
    });

    expect(near.result?.near).toBe('closest');
    expect(near.result?.excerpt).toEqual({
      from: 1,
      to: 3,
      total: 5,
      lines: ['if (ready) {', '    go();', '}'],
    });
    expect(far.result).toEqual({
      version: 1,
      kind: 'no-match',
      op: 'edit',
      path: 'A.java',
      count: 0,
      foreign: [],
    });
  });

  it('reports only that there is no file, for the server to say how to create one', async () => {
    const reply = await asking('edit', {
      path: 'gone.txt',
      replacing: 'a',
      content: 'b',
    });

    expect(reply.outcome).toBe('refused');
    expect(reply.result).toEqual({
      version: 1,
      kind: 'no-file',
      op: 'edit',
      path: 'gone.txt',
    });
    expect(reply.sentence).toBeUndefined();
  });

  it('refuses, and does not bring back, a file removed between the read and the write', async () => {
    await writeFile(join(root, 'brief.txt'), 'one\ntwo\n');
    afterRead.next = () => rm(join(root, 'brief.txt'));

    const reply = await asking('edit', {
      path: 'brief.txt',
      replacing: 'two',
      content: '2',
    });

    expect(reply.outcome).toBe('refused');
    expect(reply.result).toEqual({
      version: 1,
      kind: 'no-file',
      op: 'edit',
      path: 'brief.txt',
    });
    expect(
      await lstat(join(root, 'brief.txt')).catch(() => undefined),
      'not recreated',
    ).toBeUndefined();
  });

  it('will not edit behind the fence', async () => {
    expect(
      (
        await asking('edit', {
          path: '.git/config',
          replacing: 'secret',
          content: 'x',
        })
      ).outcome,
    ).toBe('refused');
    expect(
      (
        await asking('edit', {
          path: join(outside, 'elsewhere.txt'),
          replacing: 'not',
          content: 'x',
        })
      ).outcome,
    ).toBe('refused');
    expect(await readFile(join(outside, 'elsewhere.txt'), 'utf8')).toBe(
      'not yours\n',
    );
  });
});

describe('a delete', () => {
  it('removes one file, and reports its size and lines', async () => {
    const reply = await asking('delete', { path: 'README.md' });

    expect(reply.outcome).toBe('ok');
    expect(reply.result).toEqual({
      version: 1,
      kind: 'deleted',
      op: 'delete',
      path: 'README.md',
      bytes: 8,
      lines: 1,
    });
    await expect(lstat(join(root, 'README.md'))).rejects.toThrow();
  });

  it('refuses a directory, an absent path, and a link — leaving the link and its file', async () => {
    await symlink(join(root, 'README.md'), join(root, 'inner-link.md'));

    const directory = await asking('delete', { path: 'src' });
    const absent = await asking('delete', { path: 'nope.txt' });
    const link = await asking('delete', { path: 'inner-link.md' });

    expect(directory.outcome).toBe('refused');
    expect(directory.result?.reason).toBe('directory');
    expect(absent.outcome).toBe('refused');
    expect(absent.result).toEqual({
      version: 1,
      kind: 'no-file',
      op: 'delete',
      path: 'nope.txt',
    });
    expect(link.outcome).toBe('refused');
    expect(link.result?.reason).toBe('link');
    expect((await lstat(join(root, 'inner-link.md'))).isSymbolicLink()).toBe(
      true,
    );
    expect(await readFile(join(root, 'README.md'), 'utf8')).toBe('# hello\n');
  });

  it('refuses a dangling link too', async () => {
    await symlink(join(root, 'not-yet.txt'), join(root, 'dangling.txt'));
    const reply = await asking('delete', { path: 'dangling.txt' });
    expect(reply.outcome).toBe('refused');
    expect(reply.result?.reason).toBe('link');
  });

  it('will not delete behind the fence, or the root', async () => {
    expect(
      (await asking('delete', { path: '.plowshare/bots/default' })).outcome,
    ).toBe('refused');
    expect(
      (await asking('delete', { path: join(outside, 'elsewhere.txt') }))
        .outcome,
    ).toBe('refused');
    expect((await asking('delete', { path: '.' })).outcome).toBe('refused');
    expect(await readFile(join(outside, 'elsewhere.txt'), 'utf8')).toBe(
      'not yours\n',
    );
    expect(
      await readFile(join(root, '.plowshare', 'bots', 'default'), 'utf8'),
    ).toBe('sophron\n');
  });
});

describe('a move', () => {
  it('renames one file, making the directories above the destination', async () => {
    const reply = await asking('move', {
      path: 'README.md',
      to: 'docs/deep/README.md',
    });
    expect(reply.outcome).toBe('ok');
    expect(reply.result).toEqual({
      version: 1,
      kind: 'moved',
      op: 'move',
      path: 'README.md',
      to: 'docs/deep/README.md',
      bytes: 8,
    });
    expect(
      await readFile(join(root, 'docs', 'deep', 'README.md'), 'utf8'),
    ).toBe('# hello\n');
    await expect(lstat(join(root, 'README.md'))).rejects.toThrow();
  });

  it('never replaces a file that is already at the destination', async () => {
    const reply = await asking('move', { path: 'README.md', to: 'src/a.ts' });

    expect(reply.outcome).toBe('refused');
    expect(reply.result).toEqual({
      version: 1,
      kind: 'refused',
      op: 'move',
      path: 'README.md',
      to: 'src/a.ts',
      reason: 'destination-exists',
    });
    expect(await readFile(join(root, 'src', 'a.ts'), 'utf8')).toBe(
      'export const a = 1\nexport const b = 2\n',
    );
    expect(await readFile(join(root, 'README.md'), 'utf8')).toBe('# hello\n');
  });

  it('counts a dangling link at the destination as something there', async () => {
    await symlink(join(root, 'not-yet.txt'), join(root, 'dangling.txt'));
    expect(
      (await asking('move', { path: 'README.md', to: 'dangling.txt' })).outcome,
    ).toBe('refused');
  });

  it('refuses a source that is a directory, absent, or a link', async () => {
    await symlink(join(root, 'README.md'), join(root, 'inner-link.md'));

    const directory = await asking('move', { path: 'src', to: 'lib' });
    const absent = await asking('move', { path: 'nope.txt', to: 'yes.txt' });
    const link = await asking('move', {
      path: 'inner-link.md',
      to: 'moved.md',
    });

    expect(directory.result?.reason).toBe('directory');
    expect(absent.result).toEqual({
      version: 1,
      kind: 'no-file',
      op: 'move',
      path: 'nope.txt',
    });
    expect(link.result?.reason).toBe('link');
    expect([directory, absent, link].map((reply) => reply.outcome)).toEqual([
      'refused',
      'refused',
      'refused',
    ]);
    expect((await lstat(join(root, 'inner-link.md'))).isSymbolicLink()).toBe(
      true,
    );
    await expect(lstat(join(root, 'moved.md'))).rejects.toThrow();
  });

  it('refuses either path behind the fence, and moves nothing', async () => {
    const hiddenSource = await asking('move', {
      path: '.git/config',
      to: 'config',
    });
    const hiddenDestination = await asking('move', {
      path: 'README.md',
      to: '.plowshare/bots/readme.md',
    });
    const outsideSource = await asking('move', {
      path: join(outside, 'elsewhere.txt'),
      to: 'mine.txt',
    });
    const outsideDestination = await asking('move', {
      path: 'README.md',
      to: join(outside, 'README.md'),
    });
    const upward = await asking('move', {
      path: 'README.md',
      to: '../README.md',
    });

    const replies = [
      hiddenSource,
      hiddenDestination,
      outsideSource,
      outsideDestination,
      upward,
    ];
    for (const reply of replies) {
      expect(reply.outcome).toBe('refused');
      expect(reply.result?.roots).toEqual([root]);
    }
    expect(replies.map((reply) => reply.result?.reason)).toEqual([
      'hidden',
      'hidden',
      'outside',
      'outside',
      'outside',
    ]);
    expect(await readFile(join(root, 'README.md'), 'utf8')).toBe('# hello\n');
    expect(await readFile(join(root, '.git', 'config'), 'utf8')).toBe(
      'secret\n',
    );
    expect(await readFile(join(outside, 'elsewhere.txt'), 'utf8')).toBe(
      'not yours\n',
    );
    await expect(lstat(join(outside, 'README.md'))).rejects.toThrow();
  });

  it('needs a destination', async () => {
    const reply = await asking('move', { path: 'README.md' });
    expect(reply.outcome).toBe('refused');
    expect(reply.result).toEqual({
      version: 1,
      kind: 'refused',
      op: 'move',
      reason: 'missing',
      argument: 'to',
    });
  });
});

/*
 * `ClientEnforcerTest`'s shared table, `file-results.json`: the same disks and the
 * same file actions — changes, reads, stats, globs, searches, a run's fence —
 * answered by this client with the same facts the Java client
 * answers with — outcome and result, and never a sentence. The server words
 * whichever client sent them, so a fact one reports and the other does not would
 * be a model told two things about one disk.
 */
describe('the changes both clients answer alike', () => {
  interface Case {
    readonly name: string;
    readonly dirs?: readonly string[];
    readonly files?: Readonly<Record<string, string>>;
    readonly bytes?: Readonly<Record<string, readonly number[]>>;
    readonly links?: Readonly<Record<string, string>>;
    readonly request: Readonly<Record<string, unknown>>;
    readonly reply: {
      readonly outcome: string;
      readonly result?: FileResult;
      readonly span?: Span;
    };
    readonly after?: Readonly<Record<string, string | null>>;
  }

  const table = JSON.parse(
    readFileSync(
      join(
        dirname(fileURLToPath(import.meta.url)),
        '..',
        '..',
        '..',
        '..',
        'plowshare-protocol/src/test/resources/io/aeyer/plowshare/protocol/file-results.json',
      ),
      'utf8',
    ),
  ) as Case[];

  it('reads a table with the cases it was written with', () => {
    expect(table.length).toBeGreaterThan(20);
  });

  it.each(table.map((one) => [one.name, one] as const))(
    '%s',
    async (_name, one) => {
      const home = await realpath(
        await mkdtemp(join(tmpdir(), 'shared-root-')),
      );
      const away = await realpath(
        await mkdtemp(join(tmpdir(), 'shared-outside-')),
      );
      const spelled = <T>(value: T): T =>
        JSON.parse(
          JSON.stringify(value)
            .replaceAll('{root}', home)
            .replaceAll('{outside}', away),
        ) as T;
      try {
        for (const dir of one.dirs ?? []) {
          await mkdir(join(home, dir), { recursive: true });
        }
        for (const [name, text] of Object.entries(one.files ?? {})) {
          await mkdir(dirname(join(home, name)), { recursive: true });
          await writeFile(join(home, name), text);
        }
        for (const [name, bytes] of Object.entries(one.bytes ?? {})) {
          await writeFile(join(home, name), Buffer.from(bytes));
        }
        for (const [name, target] of Object.entries(one.links ?? {})) {
          await symlink(join(home, target), join(home, name));
        }

        const reply = await enforcing(home)({
          id: 'r',
          op: '',
          ...spelled(one.request),
        });

        expect(reply.outcome).toBe(one.reply.outcome);
        expect(
          reply.sentence,
          'a file action is answered with facts, never words',
        ).toBeUndefined();
        expect(reply.result).toEqual(
          one.reply.result === undefined
            ? undefined
            : spelled(one.reply.result),
        );
        if (one.reply.span !== undefined) {
          expect(reply.span).toEqual(one.reply.span);
        }
        for (const [name, text] of Object.entries(one.after ?? {})) {
          if (text === null) {
            expect(
              await lstat(join(home, name)).catch(() => undefined),
              name,
            ).toBeUndefined();
          } else {
            expect(await readFile(join(home, name), 'utf8'), name).toBe(text);
          }
        }
      } finally {
        await rm(home, { recursive: true, force: true });
        await rm(away, { recursive: true, force: true });
      }
    },
  );
});

describe.skipIf(process.platform === 'win32')('a run', () => {
  const OPTED_IN = 'local:\n  mode: open\n';

  async function environment(text: string): Promise<void> {
    await writeFile(join(root, '.plowshare', 'environment.yml'), text);
  }

  function running(more: Record<string, unknown> = {}): Promise<FileReply> {
    return asking('run', {
      path: root,
      inherit: ['PATH'],
      timeoutMillis: 10_000,
      outputBytes: 1024,
      ...more,
    });
  }

  it('takes command consent from the JSON project and ignores a local server section', async () => {
    const manifest = join(root, '.plowshare/project');
    await writeFile(
      manifest,
      JSON.stringify({
        version: 1,
        name: 'house',
        commands: { local: { mode: 'open' } },
      }),
    );
    expect(await running({ argv: ['true'] })).toMatchObject({
      outcome: 'ok',
      exitCode: 0,
    });
    await writeFile(
      manifest,
      JSON.stringify({
        version: 1,
        name: 'house',
        commands: { local: { mode: 'off' }, server: { mode: 'open' } },
      }),
    );
    expect(await running({ argv: ['true'] })).toMatchObject({
      outcome: 'refused',
    });
    await writeFile(
      manifest,
      JSON.stringify({
        version: 1,
        name: 'house',
        commands: { local: { mode: 'yes' } },
      }),
    );
    expect(await running({ argv: ['true'] })).toMatchObject({
      outcome: 'refused',
    });
  });

  it("runs in the directory named when this machine's own file opts in", async () => {
    await environment(OPTED_IN);

    const reply = await running({ path: 'src', argv: ['ls'] });

    expect(reply).toMatchObject({
      id: 'r',
      outcome: 'ok',
      exitCode: 0,
      timedOut: false,
      stdout: 'a.ts\n',
      stdoutCut: 0,
      stderr: '',
      stderrCut: 0,
    });
    expect(typeof reply.millis).toBe('number');
  });

  it('runs with no file, and explicit off or an unreadable file still refuses', async () => {
    const absent = await running({ argv: ['true'] });
    await environment('local:\n  mode: off\n');
    const off = await running({ argv: ['true'] });
    await environment('local:\n  mode: yes\n');
    const unreadable = await running({ argv: ['true'] });

    expect(absent).toMatchObject({ outcome: 'ok', exitCode: 0 });
    for (const reply of [off, unreadable]) {
      expect(reply.outcome).toBe('refused');
      expect(reply.exitCode).toBeUndefined();
    }
    const sentence =
      "this machine's .plowshare/environment.yml does not allow commands to run here; its local mode is off";
    expect(off.sentence).toBe(sentence);
    expect(unreadable.sentence).toContain(
      'line 2: mode is off, gated, ask or open',
    );
    expect(unreadable.sentence).toContain('its local mode is off');
  });

  it('takes its default consent from the attended TUI and none from a server section', async () => {
    await environment('server:\n  mode: open\n');
    expect(await running({ argv: ['true'], shells: true })).toMatchObject({
      outcome: 'ok',
      exitCode: 0,
    });
  });

  it('refuses a shell unless its own file allows shells, whatever the request says', async () => {
    await environment(OPTED_IN);
    const refused = await running({
      argv: ['/bin/sh', '-c', 'printf hi'],
      shells: true,
    });
    expect(refused.outcome).toBe('refused');
    expect(refused.sentence).toContain('is a shell');

    await environment('local:\n  mode: open\n  shells: true\n');
    expect(
      await running({ argv: ['sh', '-c', 'printf hi'], shells: false }),
    ).toMatchObject({ outcome: 'ok', exitCode: 0, stdout: 'hi' });
  });

  it('passes through only the host variables its own file also names', async () => {
    await environment(
      'local:\n  mode: open\n  shells: true\n  inherit: [PATH]\n',
    );

    const reply = await running({
      argv: ['sh', '-c', 'printf "${HOME-unset}"'],
      inherit: ['PATH', 'HOME'],
    });

    expect(reply).toMatchObject({ outcome: 'ok', stdout: 'unset' });
  });

  it("bounds the deadline and the output by the smaller of the request's and its own", async () => {
    await environment('local:\n  mode: open\n  timeout: 1s\n  output: 1KiB\n');

    const late = await running({
      argv: ['sleep', '30'],
      timeoutMillis: 60_000,
    });
    expect(late).toMatchObject({
      outcome: 'ok',
      exitCode: null,
      timedOut: true,
    });

    await environment('local:\n  mode: open\n  shells: true\n  output: 1KiB\n');
    const long = await running({
      argv: ['sh', '-c', "printf '%03000d' 7"],
      outputBytes: 1_000_000,
    });
    expect(long.stdout).toHaveLength(1024);
    expect(long.stdoutCut).toBe(1976);
  });

  it('refuses a working directory outside the root, hidden, or not a directory', async () => {
    await environment(OPTED_IN);

    const outsideRoot = await running({ path: outside, argv: ['true'] });
    const hidden = await running({ path: '.git', argv: ['true'] });
    const plowshare = await running({ path: '.plowshare', argv: ['true'] });
    const file = await running({ path: 'README.md', argv: ['true'] });

    expect(outsideRoot.result?.reason).toBe('outside');
    for (const reply of [outsideRoot, hidden, plowshare]) {
      expect(reply.outcome).toBe('refused');
      expect(
        reply.sentence,
        "a run's fence is facts, as every path's is",
      ).toBeUndefined();
      expect(reply.result?.op).toBe('run');
    }
    expect([hidden.result?.reason, plowshare.result?.reason]).toEqual([
      'hidden',
      'hidden',
    ]);
    expect(file.outcome).toBe('refused');
    expect(file.sentence).toContain('is not a directory');
  });

  it("refuses a program that is not on the command's PATH", async () => {
    await environment(OPTED_IN);
    const reply = await running({ argv: ['plowshare-no-such-program'] });
    expect(reply.outcome).toBe('refused');
    expect(reply.sentence).toContain(
      "no program called 'plowshare-no-such-program'",
    );
  });

  it('kills a run a cancel names, promptly, and answers both — the cancel ok either way', async () => {
    await environment(OPTED_IN);
    const answer = enforcing(root);
    const started = Date.now();

    const run = answer({
      id: 'long',
      op: 'run',
      path: root,
      argv: ['sleep', '30'],
      inherit: ['PATH'],
      timeoutMillis: 60_000,
      outputBytes: 1024,
    });
    // The run holds nothing up: a read asked meanwhile is answered first.
    expect(
      (await answer({ id: 'meanwhile', op: 'read', path: 'README.md' }))
        .outcome,
    ).toBe('ok');
    await new Promise((done) => setTimeout(done, 200));
    expect(await answer({ id: 'stop', op: 'cancel', path: 'long' })).toEqual({
      id: 'stop',
      outcome: 'ok',
    });

    expect(await run).toMatchObject({
      id: 'long',
      outcome: 'ok',
      exitCode: null,
      timedOut: false,
    });
    expect(Date.now() - started).toBeLessThan(5_000);
    expect(await answer({ id: 'again', op: 'cancel', path: 'long' })).toEqual({
      id: 'again',
      outcome: 'ok',
    });
    expect(
      await answer({ id: 'nothing', op: 'cancel', path: 'never-was' }),
    ).toEqual({ id: 'nothing', outcome: 'ok' });
  });

  it('lets the harness read environment.yml, and keeps it and every other hidden path from a model', async () => {
    await environment(OPTED_IN);

    expect(
      (
        await asking('read', {
          path: '.plowshare/environment.yml',
          purpose: 'definitions',
        })
      ).span?.lines,
    ).toEqual(['local:', '  mode: open']);
    expect(
      (await asking('read', { path: '.plowshare/environment.yml' })).outcome,
    ).toBe('refused');
    expect(
      (
        await asking('read', {
          path: '.plowshare/project',
          purpose: 'definitions',
        })
      ).outcome,
    ).toBe('ok');
    expect(
      (await asking('read', { path: '.git/config', purpose: 'definitions' }))
        .outcome,
    ).toBe('refused');
    expect(
      (
        await asking('write', {
          path: '.plowshare/environment.yml',
          content: 'local:\n  mode: off\n',
          purpose: 'definitions',
        })
      ).outcome,
    ).toBe('refused');
    expect(
      (
        await asking('glob', {
          pattern: '.plowshare/*',
          purpose: 'definitions',
        })
      ).paths,
    ).toEqual([join(root, '.plowshare', 'environment.yml')]);
  });

  it('reports the attended ask default to the server when environment.yml is absent', async () => {
    expect(
      (
        await asking('read', {
          path: '.plowshare/environment.yml',
          purpose: 'definitions',
        })
      ).span?.lines,
    ).toEqual(['local:', '  mode: ask']);
    expect(
      (await asking('read', { path: '.plowshare/environment.yml' })).outcome,
    ).toBe('refused');
  });
});

describe('the hooks mark', () => {
  beforeEach(async () => {
    await mkdir(join(root, '.plowshare', 'hooks', 'deep'), { recursive: true });
    await writeFile(
      join(root, '.plowshare', 'hooks', '10-guard.ts'),
      'export default {}\n',
    );
    await writeFile(
      join(root, '.plowshare', 'hooks', '.draft.ts'),
      'export default {}\n',
    );
    await writeFile(
      join(root, '.plowshare', 'hooks', 'deep', 'x.ts'),
      'export default {}\n',
    );
  });

  it('lets the harness reach .plowshare/hooks/<one name> and nothing else hidden', () => {
    expect(
      allows(root, join(root, '.plowshare', 'hooks', '10-guard.ts'), 'hooks'),
    ).toBe(true);
    expect(
      allows(root, join(root, '.plowshare', 'hooks', '.draft.ts'), 'hooks'),
    ).toBe(false);
    expect(
      allows(root, join(root, '.plowshare', 'hooks', 'deep', 'x.ts'), 'hooks'),
    ).toBe(false);
    expect(
      allows(root, join(root, '.plowshare', 'agents', 'x.md'), 'hooks'),
    ).toBe(false);
    expect(
      allows(root, join(root, '.plowshare', 'environment.yml'), 'hooks'),
    ).toBe(false);
    expect(allows(root, join(root, '.git', 'config'), 'hooks')).toBe(false);
  });

  it('keeps the definitions mark and a model out of the hooks', () => {
    expect(
      allows(
        root,
        join(root, '.plowshare', 'hooks', '10-guard.ts'),
        'definitions',
      ),
    ).toBe(false);
    expect(
      allows(root, join(root, '.plowshare', 'hooks', '10-guard.ts'), 'reading'),
    ).toBe(false);
  });

  it('reads, stats and globs a hook for the harness', async () => {
    expect(
      (
        await asking('read', {
          path: '.plowshare/hooks/10-guard.ts',
          purpose: 'hooks',
        })
      ).span?.lines,
    ).toEqual(['export default {}']);
    expect(
      (
        await asking('stat', {
          path: '.plowshare/hooks/10-guard.ts',
          purpose: 'hooks',
        })
      ).span?.totalLines,
    ).toBe(1);
    expect(
      (
        await asking('glob', {
          pattern: '.plowshare/hooks/*',
          purpose: 'hooks',
        })
      ).paths,
    ).toEqual([join(root, '.plowshare', 'hooks', '10-guard.ts')]);
  });

  it('refuses a write, a deeper path, a dotfile and the definitions under the hooks mark', async () => {
    expect(
      (
        await asking('write', {
          path: '.plowshare/hooks/10-guard.ts',
          content: 'x',
          purpose: 'hooks',
        })
      ).outcome,
    ).toBe('refused');
    expect(
      await readFile(join(root, '.plowshare', 'hooks', '10-guard.ts'), 'utf8'),
    ).toBe('export default {}\n');
    expect(
      (
        await asking('read', {
          path: '.plowshare/hooks/deep/x.ts',
          purpose: 'hooks',
        })
      ).outcome,
    ).toBe('refused');
    expect(
      (
        await asking('read', {
          path: '.plowshare/hooks/.draft.ts',
          purpose: 'hooks',
        })
      ).outcome,
    ).toBe('refused');
    expect(
      (
        await asking('read', {
          path: '.plowshare/bots/default',
          purpose: 'hooks',
        })
      ).outcome,
    ).toBe('refused');
    expect(
      (
        await asking('glob', {
          pattern: '.plowshare/agents/*',
          purpose: 'hooks',
        })
      ).paths,
    ).toEqual([]);
  });

  it('shows the definitions mark and a model none of the hooks', async () => {
    expect(
      (
        await asking('read', {
          path: '.plowshare/hooks/10-guard.ts',
          purpose: 'definitions',
        })
      ).outcome,
    ).toBe('refused');
    expect(
      (
        await asking('glob', {
          pattern: '.plowshare/hooks/*',
          purpose: 'definitions',
        })
      ).paths,
    ).toEqual([]);
    expect(
      (await asking('read', { path: '.plowshare/hooks/10-guard.ts' })).outcome,
    ).toBe('refused');
    expect(
      (await asking('glob', { pattern: '.plowshare/hooks/*' })).paths,
    ).toEqual([]);
  });
});

describe('schedule definition file purpose', () => {
  it('creates and reads only direct schedule JSON while keeping it hidden from models', async () => {
    const path = '.plowshare/schedules/daily.json';
    expect(
      (
        await asking('write', {
          path,
          content: '{"version":1}',
          purpose: 'schedules',
        })
      ).outcome,
    ).toBe('ok');
    expect(
      (
        await asking('glob', {
          pattern: '.plowshare/schedules/*.json',
          purpose: 'schedules',
        })
      ).paths,
    ).toEqual([join(root, path)]);
    expect(
      (
        await asking('read', {
          path,
          purpose: 'schedules',
          offset: 0,
          limit: 10,
        })
      ).span?.lines,
    ).toEqual(['{"version":1}']);
    expect((await asking('read', { path })).outcome).toBe('refused');
    expect((await asking('write', { path, content: 'changed' })).outcome).toBe(
      'refused',
    );
    expect(
      (
        await asking('write', {
          path: '.plowshare/environment.yml',
          content: 'secret',
          purpose: 'schedules',
        })
      ).outcome,
    ).toBe('refused');
    expect(
      (
        await asking('write', {
          path: 'README.md',
          content: 'changed',
          purpose: 'schedules',
        })
      ).outcome,
    ).toBe('refused');
    expect(
      (await asking('delete', { path, purpose: 'schedules' })).outcome,
    ).toBe('ok');
  });
  it('refuses a symlinked schedule folder and path traversal', async () => {
    await symlink(outside, join(root, '.plowshare', 'schedules'));
    expect(
      (
        await asking('write', {
          path: '.plowshare/schedules/escape.json',
          content: 'changed',
          purpose: 'schedules',
        })
      ).outcome,
    ).toBe('refused');
    expect(
      (
        await asking('write', {
          path: '.plowshare/schedules/../escape.json',
          content: 'changed',
          purpose: 'schedules',
        })
      ).outcome,
    ).toBe('refused');
  });
});

it('protects application authority even before the root manifest exists', async () => {
  expect(
    (await asking('write', { path: 'plowshare.json', content: '{}' })).outcome,
  ).toBe('refused');
  await writeFile(join(root, 'plowshare.json'), '{}');
  expect((await asking('delete', { path: 'plowshare.json' })).outcome).toBe(
    'refused',
  );
  expect(
    (await asking('move', { from: 'plowshare.json', to: 'old.json' })).outcome,
  ).toBe('refused');
  expect(allows(root, join(root, 'plowshare.json'), 'definitions')).toBe(true);
});
