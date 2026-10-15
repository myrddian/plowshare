import assert from 'node:assert/strict';
import { test } from 'node:test';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { readdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { NativeMcpHost, NativeMcpSessions } from './native-host.ts';
import { NativeHostCommands } from './native-commands.ts';
import type { NativeManagedStatus } from './native-commands.ts';
import { decodeNativeConfig } from './native-config.ts';
import { nativeFixture } from './native.test-support.ts';
import { McpWire } from './wire.test-support.ts';
import type { McpProtocol } from './contracts.ts';

for (const protocol of ['2026-07-28', '2025-11-25'] satisfies McpProtocol[]) {
  for (const stop of ['abort', 'close'] as const) {
    await test(`${protocol}: STDIO ${stop} cancels the exact submitted request and restart never repeats its uncertain effect`, async () => {
      const fixture = await nativeFixture(protocol, 'hang');
      const sessions = new NativeMcpSessions({ NATIVE_SELECTED: 'one' });
      const host = await NativeMcpHost.start(
        fixture.config,
        fixture.config.owner,
        sessions,
        true,
      );
      try {
        const tool = host.tools[0];
        assert.ok(tool);
        const input = {
          invocationId: randomUUID(),
          tool: tool.name,
          arguments: { values: [{ name: 'one' }] },
          deadline: new Date(Date.now() + 2000).toISOString(),
        };
        const controller = new AbortController();
        const pending = host.invoke(input, controller.signal);
        const deadline = Date.now() + 1000;
        while (
          !(await fixture.seen()).some((row) => row.method === 'tools/call') &&
          Date.now() < deadline
        )
          await delay(5);
        assert.equal(
          (await fixture.seen()).filter((row) => row.method === 'tools/call')
            .length,
          1,
        );
        if (stop === 'abort') controller.abort();
        else await host.close();
        const outcome = await pending;
        assert.equal(outcome.state, 'UNKNOWN');
        assert.match(outcome.text, /Cancellation requested/);
        await host.close();
        const seen = await fixture.seen();
        const call = seen.find((row) => row.method === 'tools/call');
        assert.ok(call);
        const cancellations = seen.filter(
          (row) => row.method === 'notifications/cancelled',
        );
        assert.equal(cancellations.length, 1);
        assert.equal(cancellations[0]?.cancelledRequestId, call.requestId);
        const restarted = await NativeMcpHost.start(
          fixture.config,
          fixture.config.owner,
          sessions,
          true,
        );
        try {
          assert.equal((await restarted.invoke(input)).state, 'UNKNOWN');
        } finally {
          await restarted.close();
        }
        assert.equal(
          (await fixture.seen()).filter((row) => row.method === 'tools/call')
            .length,
          1,
        );
      } finally {
        await host.close();
        await fixture.close();
      }
    });
  }
}

for (const protocol of ['2026-07-28', '2025-11-25'] satisfies McpProtocol[]) {
  await test(`${protocol}: direct STDIO uses selected env, nested values, private receipts and no Relay`, async () => {
    const fixture = await nativeFixture(protocol);
    const sessions = new NativeMcpSessions({
      NATIVE_SELECTED: 'selected-value',
      HOME: 'must-not-inherit',
      TOKEN: 'must-not-inherit',
    });
    const commands = new NativeHostCommands(fixture.config.owner, sessions, {
      invalidate() {
        throw new Error('Direct local calls must never invalidate Plowshare');
      },
      async request() {
        throw new Error('Direct local calls must never enter Plowshare');
      },
    });
    try {
      assert.equal(
        (
          (await commands.execute({
            action: 'inspect',
            file: fixture.file,
          })) as { active: boolean }
        ).active,
        false,
      );
      assert.equal((await fixture.seen()).length, 0);
      await assert.rejects(
        commands.execute({
          action: 'start',
          file: fixture.file,
          consent: false,
        }),
      );
      assert.equal((await fixture.seen()).length, 0);
      const status = await commands.execute({
        action: 'start',
        file: fixture.file,
        consent: true,
      });
      assert.ok('tools' in status);
      const tool = status.tools[0];
      assert.ok(tool);
      const invocation = {
        invocationId: randomUUID(),
        tool: tool.name,
        arguments: { values: [{ name: 'one' }] },
        deadline: new Date(Date.now() + 2000).toISOString(),
      };
      const request = {
        action: 'call' as const,
        instance: fixture.config.instance,
        profile: fixture.config.profile,
        invocation,
      };
      const first = await commands.execute(request);
      assert.ok('state' in first);
      assert.equal(first.state, 'COMPLETED');
      const reply: unknown = JSON.parse(first.text);
      assert.ok(
        reply !== null &&
          typeof reply === 'object' &&
          'structuredContent' in reply,
      );
      assert.deepEqual(reply.structuredContent, invocation.arguments);
      assert.deepEqual(await commands.execute(request), first);
      await assert.rejects(
        commands.execute({
          ...request,
          invocation: {
            ...invocation,
            arguments: { values: [{ name: 'different' }] },
          },
        }),
      );
      await assert.rejects(
        commands.execute({
          ...request,
          invocation: {
            ...invocation,
            invocationId: randomUUID(),
            arguments: { bad: true },
          },
        }),
      );
      assert.equal(
        (await fixture.seen()).filter((row) => row.method === 'tools/call')
          .length,
        1,
      );
      assert.ok(
        (await fixture.seen()).every(
          (row) => !row.home && row.selected === 'selected-value',
        ),
      );
      await commands.close();
      // Restart requires consent; the same ID returns its exact retained result without execution.
      const restarted = await NativeMcpHost.start(
        fixture.config,
        fixture.config.owner,
        sessions,
        true,
      );
      try {
        assert.deepEqual(await restarted.invoke(invocation), first);
      } finally {
        await restarted.close();
      }
      assert.equal(
        (await fixture.seen()).filter((row) => row.method === 'tools/call')
          .length,
        1,
      );
    } finally {
      await commands.close();
      await fixture.close();
    }
  });
  await test(`${protocol}: direct HTTP host isolates credentials and does not enter a server transport`, async () => {
    const fixture = await nativeFixture(protocol),
      wire = new McpWire(protocol);
    await wire.start();
    const config = decodeNativeConfig({
      ...fixture.config,
      transport: {
        kind: 'http',
        endpoint: wire.endpoint,
        allowHttp: true,
        allowPrivateNetwork: true,
        authorization: { header: 'Authorization', environment: 'NATIVE_AUTH' },
      },
    });
    const env = { NATIVE_AUTH: 'Bearer fixture-private' };
    const host = await NativeMcpHost.start(
      config,
      config.owner,
      new NativeMcpSessions(env),
      true,
    );
    try {
      const tool = host.tools[0];
      assert.ok(tool);
      const result = await host.invoke({
        invocationId: randomUUID(),
        tool: tool.name,
        arguments: { values: [{ name: 'http' }] },
        deadline: new Date(Date.now() + 2000).toISOString(),
      });
      assert.equal(result.state, 'COMPLETED');
      env.NATIVE_AUTH = 'Bearer rotated';
      assert.equal(
        (
          await host.invoke({
            invocationId: randomUUID(),
            tool: tool.name,
            arguments: { values: [{ name: 'http' }] },
            deadline: new Date(Date.now() + 2000).toISOString(),
          })
        ).state,
        'REJECTED',
      );
      assert.equal(
        wire.seen.filter((row) => row.method === 'tools/call').length,
        1,
      );
      assert.ok(
        wire.seen.every(
          (row) => row.authorization === 'Bearer fixture-private',
        ),
      );
      assert.equal(
        JSON.stringify(host.status()).includes('fixture-private'),
        false,
      );
    } finally {
      await host.close();
      await wire.close();
      await fixture.close();
    }
  });
}
for (const mode of ['loss', 'malformed', 'hang']) {
  await test(`STDIO ${mode}: uncertain effect is recorded once, never replayed or exposed as success`, async () => {
    const fixture = await nativeFixture('2026-07-28', mode),
      sessions = new NativeMcpSessions({ NATIVE_SELECTED: 'selected' });
    const host = await NativeMcpHost.start(
      fixture.config,
      fixture.config.owner,
      sessions,
      true,
    );
    const tool = host.tools[0];
    assert.ok(tool);
    const input = {
      invocationId: randomUUID(),
      tool: tool.name,
      arguments: { values: [{ name: 'one' }] },
      deadline: new Date(Date.now() + 100).toISOString(),
    };
    try {
      assert.equal((await host.invoke(input)).state, 'UNKNOWN');
      await host.close();
      const restarted = await NativeMcpHost.start(
        fixture.config,
        fixture.config.owner,
        sessions,
        true,
      );
      try {
        assert.equal((await restarted.invoke(input)).state, 'UNKNOWN');
      } finally {
        await restarted.close();
      }
      assert.equal(
        (await fixture.seen()).filter((row) => row.method === 'tools/call')
          .length,
        1,
      );
      assert.equal(
        JSON.stringify(host.status()).includes('fixture-secret'),
        false,
      );
    } finally {
      await host.close();
      await fixture.close();
    }
  });
}
await test('interrupted native SDK receipt is UNKNOWN on explicit restart with no repeated effect', async () => {
  const fixture = await nativeFixture(),
    sessions = new NativeMcpSessions({ NATIVE_SELECTED: 'one' });
  const host = await NativeMcpHost.start(
    fixture.config,
    fixture.config.owner,
    sessions,
    true,
  );
  const tool = host.tools[0];
  assert.ok(tool);
  const input = {
    invocationId: randomUUID(),
    tool: tool.name,
    arguments: { values: [{ name: 'one' }] },
    deadline: new Date(Date.now() + 2000).toISOString(),
  };
  try {
    await host.invoke(input);
    await host.close();
    const roots = await readdir(fixture.config.journalDirectory);
    const root = roots[0];
    assert.ok(root);
    const directory = join(fixture.config.journalDirectory, root),
      files = await readdir(directory),
      file = files.find((file) => file.endsWith('.json'));
    assert.ok(file);
    const source: unknown = JSON.parse(
      await readFile(join(directory, file), 'utf8'),
    );
    assert.ok(
      source !== null &&
        typeof source === 'object' &&
        'receipts' in source &&
        Array.isArray(source.receipts),
    );
    const row: unknown = source.receipts[0];
    assert.ok(row !== null && typeof row === 'object');
    await writeFile(
      join(directory, file),
      JSON.stringify({
        ...source,
        receipts: [
          { ...row, phase: 'executing', result: null, occurredAt: null },
        ],
      }),
      { mode: 0o600 },
    );
    const restarted = await NativeMcpHost.start(
      fixture.config,
      fixture.config.owner,
      sessions,
      true,
    );
    try {
      assert.equal((await restarted.invoke(input)).state, 'UNKNOWN');
    } finally {
      await restarted.close();
    }
    assert.equal(
      (await fixture.seen()).filter((row) => row.method === 'tools/call')
        .length,
      1,
    );
  } finally {
    await host.close();
    await fixture.close();
  }
});
await test('foreign owner and invalid environment/process policies refuse before launch', async () => {
  const fixture = await nativeFixture();
  try {
    await assert.rejects(
      NativeMcpHost.start(
        fixture.config,
        { ...fixture.config.owner, account: 'different' },
        new NativeMcpSessions({ NATIVE_SELECTED: 'one' }),
        true,
      ),
    );
    await assert.rejects(
      NativeMcpHost.start(
        fixture.config,
        fixture.config.owner,
        new NativeMcpSessions({}),
        true,
      ),
    );
    assert.equal((await fixture.seen()).length, 0);
    assert.throws(() =>
      decodeNativeConfig({
        ...fixture.config,
        transport: {
          ...fixture.config.transport,
          executable: 'relative-command',
        },
      }),
    );
    assert.throws(() =>
      decodeNativeConfig({
        ...fixture.config,
        transport: { ...fixture.config.transport, shell: true },
      }),
    );
  } finally {
    await fixture.close();
  }
});
await test(
  'POSIX shutdown kills owned descendants even when their parent exits first',
  { skip: process.platform === 'win32' },
  async () => {
    const fixture = await nativeFixture('2026-07-28', 'tree');
    const host = await NativeMcpHost.start(
      fixture.config,
      fixture.config.owner,
      new NativeMcpSessions({ NATIVE_SELECTED: 'one' }),
      true,
    );
    try {
      const child = (await fixture.seen())[0]?.child;
      assert.ok(child);
      await host.close();
      let gone = false;
      for (let i = 0; i < 40; i++) {
        try {
          process.kill(child, 0);
        } catch {
          gone = true;
          break;
        }
        await delay(10);
      }
      assert.equal(gone, true, 'owned descendant remained after host shutdown');
    } finally {
      await host.close();
      await fixture.close();
    }
  },
);

await test('changed discovery cannot reinterpret a UUID retained by an older native catalogue', async () => {
  const fixture = await nativeFixture(),
    wire = new McpWire('2026-07-28');
  await wire.start();
  const config = decodeNativeConfig({
    ...fixture.config,
    transport: {
      kind: 'http',
      endpoint: wire.endpoint,
      allowHttp: true,
      allowPrivateNetwork: true,
    },
  });
  const sessions = new NativeMcpSessions({});
  const first = await NativeMcpHost.start(config, config.owner, sessions, true);
  const tool = first.tools[0];
  assert.ok(tool);
  const input = {
    invocationId: randomUUID(),
    tool: tool.name,
    arguments: { values: [{ name: 'one' }] },
    deadline: new Date(Date.now() + 2000).toISOString(),
  };
  try {
    await first.invoke(input);
    await first.close();
    const original = wire.tools[0];
    assert.ok(original !== null && typeof original === 'object');
    wire.tools = [{ ...original, description: 'changed' }];
    const second = await NativeMcpHost.start(
      config,
      config.owner,
      sessions,
      true,
    );
    try {
      const next = second.tools[0];
      assert.ok(next);
      assert.notEqual(next.name, tool.name);
      await assert.rejects(second.invoke({ ...input, tool: next.name }));
    } finally {
      await second.close();
    }
    assert.equal(
      wire.seen.filter((entry) => entry.method === 'tools/call').length,
      1,
    );
  } finally {
    await first.close();
    await wire.close();
    await fixture.close();
  }
});

await test('direct native discovery is not restricted by the server provider catalogue count', async () => {
  const fixture = await nativeFixture(),
    wire = new McpWire('2026-07-28');
  await wire.start();
  const source = wire.tools[0];
  assert.ok(source !== null && typeof source === 'object');
  wire.tools = Array.from({ length: 129 }, (_, i) => ({
    ...source,
    name: 'echo-' + i,
  }));
  const config = decodeNativeConfig({
    ...fixture.config,
    transport: {
      kind: 'http',
      endpoint: wire.endpoint,
      allowHttp: true,
      allowPrivateNetwork: true,
    },
  });
  const host = await NativeMcpHost.start(
    config,
    config.owner,
    new NativeMcpSessions({}),
    true,
  );
  try {
    assert.equal(host.tools.length, 129);
    const tool = host.tools[128];
    assert.ok(tool);
    assert.equal(
      (
        await host.invoke({
          invocationId: randomUUID(),
          tool: tool.name,
          arguments: { values: [{ name: 'one' }] },
          deadline: new Date(Date.now() + 2000).toISOString(),
        })
      ).state,
      'COMPLETED',
    );
  } finally {
    await host.close();
    await wire.close();
    await fixture.close();
  }
});

await test('private profile forms save without launching, preserve revisions and refuse foreign/active edits', async () => {
  const fixture = await nativeFixture();
  const commands = new NativeHostCommands(
    fixture.config.owner,
    new NativeMcpSessions({ NATIVE_SELECTED: 'selected-value' }),
  );
  try {
    const loaded = await commands.execute({
      action: 'profile-read',
      file: fixture.file,
    });
    assert.ok('config' in loaded);
    assert.equal((await fixture.seen()).length, 0);
    assert.equal(JSON.stringify(loaded).includes('selected-value'), false);
    const config = decodeNativeConfig({ ...loaded.config, timeoutMs: 2500 });
    const saved = await commands.execute({
      action: 'profile-save',
      file: fixture.file,
      config,
      expectedRevision: loaded.revision,
    });
    assert.ok('config' in saved);
    assert.equal(saved.config.timeoutMs, 2500);
    assert.notEqual(saved.revision, loaded.revision);
    assert.equal((await fixture.seen()).length, 0);
    await assert.rejects(
      commands.execute({
        action: 'profile-save',
        file: fixture.file,
        config,
        expectedRevision: loaded.revision,
      }),
      /changed/,
    );
    await assert.rejects(
      commands.execute({
        action: 'start',
        file: fixture.file,
        consent: true,
        expectedRevision: loaded.revision,
      }),
      /changed/,
    );
    assert.equal((await fixture.seen()).length, 0);
    await assert.rejects(
      commands.execute({
        action: 'profile-save',
        file: fixture.file,
        config: { ...config, owner: { ...config.owner, account: 'other' } },
        expectedRevision: saved.revision,
      }),
      /another login/,
    );
    const starting = commands.execute({
      action: 'start',
      file: fixture.file,
      consent: true,
      expectedRevision: saved.revision,
    });
    await assert.rejects(
      commands.execute({
        action: 'profile-save',
        file: fixture.file,
        config,
        expectedRevision: saved.revision,
      }),
      /busy/,
    );
    await starting;
    const snapshot = await commands.execute({ action: 'list' });
    assert.ok(Array.isArray(snapshot));
    const statuses = snapshot as readonly NativeManagedStatus[];
    assert.equal(statuses[0]?.sharing, 'local');
    await assert.rejects(
      commands.execute({
        action: 'profile-save',
        file: fixture.file,
        config,
        expectedRevision: saved.revision,
      }),
      /Stop/,
    );
    await assert.rejects(
      commands.execute({
        action: 'profile-save',
        file: fixture.file,
        config: { ...config, profile: 'renamed' },
        expectedRevision: saved.revision,
      }),
      /Stop/,
    );
    await commands.execute({
      action: 'stop',
      instance: config.instance,
      profile: config.profile,
    });
    await commands.execute({
      action: 'profile-save',
      file: fixture.file,
      config,
      expectedRevision: saved.revision,
    });
  } finally {
    await commands.close();
    await fixture.close();
  }
});

await test('profile saves never overwrite unrelated files or symlinks and create mode 0600 authoring files', async () => {
  const fixture = await nativeFixture();
  const commands = new NativeHostCommands(
    fixture.config.owner,
    new NativeMcpSessions({}),
  );
  try {
    const { lstat, symlink } = await import('node:fs/promises');
    const file = join(fixture.directory, 'new-profile.json');
    const saved = await commands.execute({
      action: 'profile-save',
      file,
      config: fixture.config,
      expectedRevision: null,
    });
    assert.ok('config' in saved);
    assert.equal((await lstat(file)).mode & 0o777, 0o600);
    await assert.rejects(
      commands.execute({
        action: 'profile-save',
        file,
        config: fixture.config,
        expectedRevision: null,
      }),
      /changed/,
    );
    const unrelated = join(fixture.directory, 'unrelated.txt');
    await writeFile(unrelated, 'preserve this');
    await assert.rejects(
      commands.execute({
        action: 'profile-save',
        file: unrelated,
        config: fixture.config,
        expectedRevision: null,
      }),
    );
    assert.equal(await readFile(unrelated, 'utf8'), 'preserve this');
    const link = join(fixture.directory, 'linked.json');
    await symlink(file, link);
    await assert.rejects(
      commands.execute({
        action: 'profile-save',
        file: link,
        config: fixture.config,
        expectedRevision: saved.revision,
      }),
    );
    await assert.rejects(
      commands.execute({
        action: 'profile-save',
        file: join(fixture.directory, 'oversized.json'),
        config: {
          ...fixture.config,
          transport: {
            kind: 'stdio',
            executable: process.execPath,
            directory: fixture.directory,
            args: ['x'.repeat(65536)],
            environment: {},
            treeTerminationExecutable: null,
          },
        },
        expectedRevision: null,
      }),
      /capacity/,
    );
    assert.equal((await fixture.seen()).length, 0);
  } finally {
    await commands.close();
    await fixture.close();
  }
});

await test('closing the command owner observes an admitted private save and refuses subsequent work', async () => {
  const fixture = await nativeFixture();
  const { NativeProfiles } = await import('./native-profiles.ts');
  const store = new NativeProfiles(fixture.config.owner);
  let release: () => void = () => {
    throw new Error('Save not admitted');
  };
  let entered: () => void = () => {
    throw new Error('Missing admission observer');
  };
  const admitted = new Promise<void>((resolve) => {
    entered = resolve;
  });
  const commands = new NativeHostCommands(
    fixture.config.owner,
    new NativeMcpSessions({}),
    undefined,
    {
      read: (file) => store.read(file),
      async save(file, config, expected) {
        entered();
        await new Promise<void>((resolve) => {
          release = resolve;
        });
        return store.save(file, config, expected);
      },
    },
  );
  try {
    const original = await store.read(fixture.file);
    const save = commands.execute({
      action: 'profile-save',
      file: fixture.file,
      config: { ...fixture.config, timeoutMs: 2400 },
      expectedRevision: original.revision,
    });
    await admitted;
    let closed = false;
    const closing = commands.close().then(() => {
      closed = true;
    });
    await delay(0);
    assert.equal(
      closed,
      false,
      'Command ownership ended before its admitted private save settled',
    );
    await assert.rejects(commands.execute({ action: 'list' }), /closed/);
    release();
    await save;
    await closing;
    assert.equal((await store.read(fixture.file)).config.timeoutMs, 2400);
    assert.equal((await fixture.seen()).length, 0);
  } finally {
    release();
    await commands.close();
    await fixture.close();
  }
});
