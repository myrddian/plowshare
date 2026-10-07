import {
  chmod,
  link,
  mkdir,
  mkdtemp,
  realpath,
  rm,
  symlink,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  Bubblewrap,
  configuredIsolation,
} from 'plowshare-client-node/isolation';
import type { IsolationAccess } from 'plowshare-client-node/isolation';
import { allows } from './enforcer.ts';

describe.skipIf(process.platform === 'win32')('bubblewrap policy', () => {
  let temporary = '';
  let root = '';
  let runtime = '';
  let binary = '';
  let launcher = '';
  let access: IsolationAccess;
  let sandbox: Bubblewrap;
  beforeEach(async () => {
    temporary = await realpath(
      await mkdtemp(join(tmpdir(), 'isolation-policy-')),
    );
    root = join(temporary, 'workspace');
    runtime = join(temporary, 'runtime');
    binary = join(temporary, 'bwrap');
    launcher = join(runtime, 'launcher');
    await mkdir(root);
    await mkdir(runtime);
    await mkdir(join(root, '.plowshare'));
    await writeFile(
      join(root, 'plowshare.json'),
      '{"version":1,"name":"test"}',
    );
    for (const program of [binary, launcher]) {
      await writeFile(program, '#!/bin/sh\nexit 0\n');
      await chmod(program, 0o700);
    }
    access = {
      roots: [root],
      writeRoots: [root],
      permits: (path) => allows(root, path, 'reading'),
    };
    sandbox = new Bubblewrap({
      executable: binary,
      launcher,
      runtimeRoots: [runtime],
      scratchRoot: join(temporary, 'scratch'),
    });
  });
  afterEach(async () => {
    await rm(temporary, { recursive: true, force: true });
  });

  it('mounts only configured roots and masks project secrets and configuration writes', async () => {
    await writeFile(join(root, '.secret'), 'secret');
    const args = await sandbox.arguments(
      access,
      root,
      'opaque-file',
      'opaque-directory',
    );
    const text = args.join('\n');
    expect(args).toContain('--unshare-all');
    expect(args).toContain('--disable-userns');
    expect(args).not.toContain('--share-net');
    expect(text).toContain(
      ['--ro-bind', 'opaque-file', join(root, '.secret')].join('\n'),
    );
    expect(text).toContain(
      ['--ro-bind', 'opaque-directory', join(root, '.plowshare')].join('\n'),
    );
    expect(text).toContain(
      [
        '--ro-bind',
        join(root, 'plowshare.json'),
        join(root, 'plowshare.json'),
      ].join('\n'),
    );
  });
  it('refuses symlink and hard-link aliases before constructing a launch', async () => {
    await symlink(runtime, join(root, 'link'));
    await expect(sandbox.arguments(access, root, '', '')).rejects.toThrow(
      'symlinks',
    );
    await rm(join(root, 'link'));
    await link(launcher, join(root, 'hard-link'));
    await expect(sandbox.arguments(access, root, '', '')).rejects.toThrow(
      'hard links',
    );
  });
  it('requires complete absolute host configuration and never falls back', async () => {
    expect(() =>
      configuredIsolation({ PLOWSHARE_BUBBLEWRAP: binary }),
    ).toThrow();
    expect(
      () =>
        new Bubblewrap({
          executable: 'bwrap',
          launcher,
          runtimeRoots: [runtime],
          scratchRoot: join(temporary, 'scratch'),
        }),
    ).toThrow();
    await expect(
      configuredIsolation({}).run(
        {
          argv: ['test-program'],
          cwd: root,
          env: {},
          inherit: [],
          timeoutMillis: 1000,
          outputBytes: 1024,
        },
        access,
        {},
      ),
    ).rejects.toThrow('not configured');
  });
});
