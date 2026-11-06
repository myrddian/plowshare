import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  mkdir,
  readFile,
  realpath,
  stat,
  symlink,
  writeFile,
} from 'node:fs/promises';
import { join } from 'node:path';
import { NativeConfigurations } from './native-configuration.ts';
import { NativeHostCommands, decodeNativeCommand } from './native-commands.ts';
import { NativeMcpSessions } from './native-host.ts';
import { nativeFixture } from './native.test-support.ts';

await test('Desktop and TUI share user/project entries, preserve other owners and reject stale saves', async () => {
  const fixture = await nativeFixture();
  try {
    const root = await realpath(fixture.directory);
    const user = join(root, 'user');
    const project = join(root, 'project');
    await mkdir(project);
    await mkdir(join(project, '.plowshare'), { mode: 0o755 });
    const directoryMode = (await stat(join(project, '.plowshare'))).mode;
    const desktop = new NativeConfigurations(fixture.config.owner, user);
    const tui = new NativeConfigurations(fixture.config.owner, user);
    const locations = await desktop.files(project);
    assert.deepEqual(
      locations.map((row) => row.scope),
      ['user', 'project'],
    );
    assert.deepEqual(
      locations.map((row) => row.profiles),
      [[], []],
    );
    for (const location of locations) {
      const before = await desktop.save(
        location.file,
        fixture.config,
        null,
        'echo',
      );
      const loaded = await tui.read(location.file, 'echo');
      assert.equal(loaded.revision, before.revision);
      assert.equal(loaded.config.transport.kind, 'stdio');
      assert.equal((await stat(location.file)).mode & 0o777, 0o600);
      const otherOwner = { ...fixture.config.owner, account: 'another-user' };
      const other = new NativeConfigurations(otherOwner, user);
      await other.save(
        location.file,
        { ...fixture.config, owner: otherOwner },
        null,
        'echo',
      );
      const otherProfile = await other.read(location.file, 'echo');
      const updated = await tui.save(
        location.file,
        { ...loaded.config, timeoutMs: 2500 },
        loaded.revision,
        'echo',
      );
      assert.notEqual(updated.revision, before.revision);
      await assert.rejects(
        desktop.save(location.file, fixture.config, before.revision, 'echo'),
        /changed/,
      );
      assert.equal(
        (await other.read(location.file, 'echo')).revision,
        otherProfile.revision,
      );
      await desktop.save(
        location.file,
        { ...fixture.config, profile: 'second' },
        null,
        'second',
      );
      assert.deepEqual(
        (await desktop.files(project)).find(
          (item) => item.file === location.file,
        )?.profiles,
        ['echo', 'second'],
      );
      assert.deepEqual(
        (await other.files(project)).find((item) => item.file === location.file)
          ?.profiles,
        ['echo'],
      );
      const held = await desktop.read(location.file, 'echo');
      const saves = await Promise.allSettled([
        desktop.save(
          location.file,
          { ...held.config, timeoutMs: 3000 },
          held.revision,
          'echo',
        ),
        tui.save(
          location.file,
          { ...held.config, timeoutMs: 4000 },
          held.revision,
          'echo',
        ),
      ]);
      assert.equal(
        saves.filter((result) => result.status === 'fulfilled').length,
        1,
      );
      assert.equal(
        saves.filter((result) => result.status === 'rejected').length,
        1,
      );
    }
    assert.equal((await stat(join(project, '.plowshare'))).mode, directoryMode);
    assert.equal((await fixture.seen()).length, 0);
  } finally {
    await fixture.close();
  }
});

await test('invalid, foreign, linked and duplicate MCP settings cannot replace valid entries', async () => {
  const fixture = await nativeFixture();
  try {
    const root = await realpath(fixture.directory);
    const user = join(root, 'user');
    const store = new NativeConfigurations(fixture.config.owner, user);
    const file = join(user, 'mcp.json');
    await store.save(file, fixture.config, null, 'echo');
    const before = await readFile(file, 'utf8');
    await assert.rejects(
      store.save(file, fixture.config, null, '../echo'),
      /Invalid/,
    );
    await assert.rejects(
      store.save(file, fixture.config, null, 'renamed'),
      /name or login/,
    );
    await assert.rejects(
      store.save(
        file,
        {
          ...fixture.config,
          owner: { ...fixture.config.owner, account: 'foreign' },
        },
        null,
        'echo',
      ),
      /login/,
    );
    assert.equal(await readFile(file, 'utf8'), before);
    await symlink(file, join(root, 'linked.json'));
    await assert.rejects(
      store.read(join(root, 'linked.json'), 'echo'),
      /regular file/,
    );
    await symlink(user, join(root, 'linked-directory'));
    await assert.rejects(
      store.files(join(root, 'linked-directory')),
      /Reconnect/,
    );
    const malformed = join(user, 'malformed.json');
    await writeFile(malformed, 'invalid-fixture-private-material');
    await assert.rejects(
      store.read(malformed, 'echo'),
      (error: unknown) =>
        error instanceof Error &&
        error.message === 'MCP configuration file is invalid JSON',
    );
    await writeFile(
      malformed,
      JSON.stringify({
        version: 1,
        profiles: [fixture.config, fixture.config],
      }),
    );
    await assert.rejects(store.read(malformed, 'echo'), /Duplicate/);
    await writeFile(
      malformed,
      JSON.stringify({
        version: 1,
        profiles: [{ ...fixture.config, unexpected: true }],
      }),
    );
    await assert.rejects(
      store.save(malformed, fixture.config, null, 'echo'),
      /Unknown/,
    );
    assert.throws(
      () =>
        decodeNativeCommand({
          action: 'start',
          file,
          entry: '../escape',
          consent: true,
        }),
      /Invalid/,
    );
    assert.throws(
      () =>
        decodeNativeCommand({
          action: 'configuration-files',
          root,
          unknown: true,
        }),
      /Unknown/,
    );
  } finally {
    await fixture.close();
  }
});

await test('configured entries require current revision and explicit launch consent across native owners', async () => {
  const fixture = await nativeFixture();
  const root = await realpath(fixture.directory);
  const store = new NativeConfigurations(
    fixture.config.owner,
    join(root, 'user'),
  );
  const commands = new NativeHostCommands(
    fixture.config.owner,
    new NativeMcpSessions({ NATIVE_SELECTED: 'selected-value' }),
    undefined,
    undefined,
    store,
  );
  try {
    const file = join(root, 'user', 'mcp.json');
    const saved = await commands.execute({
      action: 'profile-save',
      file,
      entry: 'echo',
      config: fixture.config,
      expectedRevision: null,
    });
    assert.ok('config' in saved);
    assert.equal((await fixture.seen()).length, 0);
    await assert.rejects(
      commands.execute({
        action: 'start',
        file,
        entry: 'echo',
        consent: false,
        expectedRevision: saved.revision,
      }),
      /consent/i,
    );
    const next = await store.save(
      file,
      { ...fixture.config, timeoutMs: 2500 },
      saved.revision,
      'echo',
    );
    await assert.rejects(
      commands.execute({
        action: 'start',
        file,
        entry: 'echo',
        consent: true,
        expectedRevision: saved.revision,
      }),
      /changed/,
    );
    assert.equal((await fixture.seen()).length, 0);
    await commands.execute({
      action: 'start',
      file,
      entry: 'echo',
      consent: true,
      expectedRevision: next.revision,
    });
    assert.equal(commands.hosts.list()[0]?.active, true);
    await assert.rejects(
      commands.execute({
        action: 'profile-save',
        file,
        entry: 'echo',
        config: fixture.config,
        expectedRevision: next.revision,
      }),
      /running host/,
    );
  } finally {
    await commands.close();
    await fixture.close();
  }
});
