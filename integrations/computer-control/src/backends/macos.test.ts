import assert from 'node:assert/strict';
import { chmod, mkdtemp, rm, stat, writeFile } from 'node:fs/promises';
import { EventEmitter } from 'node:events';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PassThrough } from 'node:stream';
import { test } from 'node:test';
import { OperationDeadline } from '../deadline.ts';
import {
  createMacOSBackend,
  createCoordinatedMacOSBackend,
  decodeMacOSBackendConfig,
  NATIVE_COORDINATION_VERSION,
  SpawnNativeCaptureProcess,
} from './macos.ts';
import { FakeClock } from '../contracts.test-support.ts';
import type {
  NativeCaptureProcess,
  NativeRequest,
  NativeChild,
} from './macos.ts';

const png = Uint8Array.from(
  Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jB1kAAAAASUVORK5CYII=',
    'base64',
  ),
);
const config = {
  helperExecutable: '/test/native-helper',
  ownershipDirectory: '/test/native-ownership',
  targets: [{ targetId: 'fixture', label: 'Fixture', surfaceId: 'window:42' }],
};
function frame(
  metadata: object,
  image: Uint8Array = new Uint8Array(),
): Uint8Array {
  return Buffer.concat([Buffer.from(`${JSON.stringify(metadata)}\n`), image]);
}
function captureMetadata() {
  return {
    version: 'computer-control-native/1',
    ok: true,
    operation: 'capture',
    byteLength: png.byteLength,
    width: 1,
    height: 1,
    transform: { a: 2, b: 0, c: 0, d: 2, e: -100, f: 50 },
    capturedAt: '2026-11-16T00:00:00.000Z',
    layoutGeneration: 'window:42:geometry',
  };
}
function fake(run: NativeCaptureProcess['run']): NativeCaptureProcess {
  return { run, close: async () => {} };
}

await test('native capture cooperation requires a validated handshake and every later wire request', async () => {
  const requests: NativeRequest[] = [];
  const deadline = new OperationDeadline(1000);
  let closes = 0;
  const process: NativeCaptureProcess = {
    run: async (_executable, request) => {
      requests.push(request);
      assert.equal(request.coordination, NATIVE_COORDINATION_VERSION);
      return request.operation === 'status'
        ? frame({
            version: 'computer-control-native/1',
            ok: true,
            operation: 'status',
            screenCaptureAllowed: true,
            accessibilityAllowed: false,
            coordination: NATIVE_COORDINATION_VERSION,
          })
        : frame(
            { ...captureMetadata(), coordination: NATIVE_COORDINATION_VERSION },
            png,
          );
    },
    close: async () => {
      closes++;
    },
  };
  try {
    assert.equal(
      createMacOSBackend(
        config,
        fake(async () => new Uint8Array()),
      ).backend.desktopIsolation,
      'physical',
    );
    const handle = await createCoordinatedMacOSBackend(
      config,
      deadline,
      process,
    );
    assert.equal(handle.backend.desktopIsolation, 'native-broker');
    assert.deepEqual(
      (
        await handle.backend.capture(
          { targetId: 'fixture', surfaceId: 'window:42' },
          deadline,
        )
      ).bytes,
      png,
    );
    assert.deepEqual(
      requests.map((request) => request.operation),
      ['status', 'capture'],
    );
    await handle.close(deadline);
    assert.equal(closes, 1);
  } finally {
    deadline.dispose();
  }
});

await test('old or malformed helper cooperation cannot be promoted or produce pixels', async () => {
  for (const output of [
    frame({
      version: 'computer-control-native/1',
      ok: false,
      code: 'INVALID_REQUEST',
    }),
    frame({
      version: 'computer-control-native/1',
      ok: true,
      operation: 'status',
      screenCaptureAllowed: true,
      accessibilityAllowed: true,
    }),
    frame({
      version: 'computer-control-native/1',
      ok: true,
      operation: 'status',
      screenCaptureAllowed: true,
      accessibilityAllowed: true,
      coordination: 'unverified',
    }),
  ]) {
    const deadline = new OperationDeadline(1000);
    let closes = 0;
    let captures = 0;
    try {
      await assert.rejects(
        createCoordinatedMacOSBackend(config, deadline, {
          run: async (_executable, request) => {
            if (request.operation === 'capture') captures++;
            return output;
          },
          close: async () => {
            closes++;
          },
        }),
      );
      assert.equal(captures, 0);
      assert.equal(closes, 1);
    } finally {
      deadline.dispose();
    }
  }
});

await test('coordinated capture handshake does not pretend to grant screen permission', async () => {
  const deadline = new OperationDeadline(1000);
  const process = fake(async () =>
    frame({
      version: 'computer-control-native/1',
      ok: true,
      operation: 'status',
      screenCaptureAllowed: false,
      accessibilityAllowed: true,
      coordination: NATIVE_COORDINATION_VERSION,
    }),
  );
  try {
    const handle = await createCoordinatedMacOSBackend(
      config,
      deadline,
      process,
    );
    assert.equal(handle.backend.desktopIsolation, 'native-broker');
    await assert.rejects(handle.backend.targets(deadline), {
      code: 'PERMISSION_DENIED',
    });
    await handle.close(deadline);
  } finally {
    deadline.dispose();
  }
});

await test('swapping to the old strict helper after handshake rejects before capture', async () => {
  const deadline = new OperationDeadline(1000);
  let realCaptures = 0;
  let calls = 0;
  const process = fake(async (_executable, request) => {
    if (++calls === 1)
      return frame({
        version: 'computer-control-native/1',
        ok: true,
        operation: 'status',
        screenCaptureAllowed: true,
        accessibilityAllowed: true,
        coordination: NATIVE_COORDINATION_VERSION,
      });
    // The shipped old strict helper refuses the new required coordination field
    // before choosing capture. It cannot silently accept an uncoordinated path.
    if (request.coordination !== undefined)
      return frame({
        version: 'computer-control-native/1',
        ok: false,
        code: 'INVALID_REQUEST',
      });
    realCaptures++;
    return frame(captureMetadata(), png);
  });
  try {
    const handle = await createCoordinatedMacOSBackend(
      config,
      deadline,
      process,
    );
    await assert.rejects(
      handle.backend.capture(
        { targetId: 'fixture', surfaceId: 'window:42' },
        deadline,
      ),
    );
    assert.equal(realCaptures, 0);
    await handle.close(deadline);
  } finally {
    deadline.dispose();
  }
});

await test('native coordination startup honors monotonic expiry without late promotion', async () => {
  const clock = new FakeClock();
  const deadline = new OperationDeadline(10, clock);
  let closes = 0;
  try {
    await assert.rejects(
      createCoordinatedMacOSBackend(config, deadline, {
        run: async () => {
          clock.monotonic += 11;
          return frame({
            version: 'computer-control-native/1',
            ok: true,
            operation: 'status',
            screenCaptureAllowed: true,
            accessibilityAllowed: true,
            coordination: NATIVE_COORDINATION_VERSION,
          });
        },
        close: async () => {
          closes++;
        },
      }),
    );
    assert.equal(closes, 1);
  } finally {
    deadline.dispose();
  }
});

await test('native coordination rechecks cutoff between validated status and promoted handle publication', async () => {
  const clock = new FakeClock();
  const deadline = new OperationDeadline(10, clock);
  let closes = 0;
  try {
    await assert.rejects(
      createCoordinatedMacOSBackend(config, deadline, {
        run: async () => {
          queueMicrotask(() =>
            queueMicrotask(() => {
              clock.monotonic += 11;
            }),
          );
          return frame({
            version: 'computer-control-native/1',
            ok: true,
            operation: 'status',
            screenCaptureAllowed: true,
            accessibilityAllowed: true,
            coordination: NATIVE_COORDINATION_VERSION,
          });
        },
        close: async () => {
          closes++;
        },
      }),
    );
    assert.equal(closes, 1);
  } finally {
    deadline.dispose();
  }
});

await test('native config rejects unconfigured paths, handles and extra fields', () => {
  assert.throws(() =>
    decodeMacOSBackendConfig({ ...config, helperExecutable: 'helper' }),
  );
  assert.throws(() => decodeMacOSBackendConfig({ ...config, extra: true }));
  for (const surfaceId of [
    'window:0',
    'display:01',
    'window:4294967296',
    'path:/secret',
  ])
    assert.throws(() =>
      decodeMacOSBackendConfig({
        ...config,
        targets: [{ ...config.targets[0], surfaceId }],
      }),
    );
});

await test('native status reports capture permission independently from accessibility', async () => {
  const deadline = new OperationDeadline(1000);
  try {
    const allowed = createMacOSBackend(
      config,
      fake(async () =>
        frame({
          version: 'computer-control-native/1',
          ok: true,
          operation: 'status',
          screenCaptureAllowed: true,
          accessibilityAllowed: false,
        }),
      ),
    );
    assert.equal(
      (await allowed.backend.targets(deadline))[0]?.surfaces[0]?.id,
      'window:42',
    );
    const denied = createMacOSBackend(
      config,
      fake(async () =>
        frame({
          version: 'computer-control-native/1',
          ok: true,
          operation: 'status',
          screenCaptureAllowed: false,
          accessibilityAllowed: true,
        }),
      ),
    );
    await assert.rejects(denied.backend.targets(deadline), {
      code: 'PERMISSION_DENIED',
    });
  } finally {
    deadline.dispose();
  }
});

await test('native frame validates exact byte count and configured surface before capture', async () => {
  let calls = 0;
  const deadline = new OperationDeadline(1000);
  try {
    const handle = createMacOSBackend(
      config,
      fake(async (_executable, request) => {
        calls++;
        assert.equal(request.surfaceId, 'window:42');
        assert.ok(request.timeoutMs > 0 && request.timeoutMs <= 1000);
        return frame(captureMetadata(), png);
      }),
    );
    await assert.rejects(
      handle.backend.capture(
        { targetId: 'fixture', surfaceId: 'window:43' },
        deadline,
      ),
      { code: 'TARGET_GONE' },
    );
    assert.equal(calls, 0);
    const image = await handle.backend.capture(
      { targetId: 'fixture', surfaceId: 'window:42' },
      deadline,
    );
    assert.deepEqual(image.bytes, png);
    assert.deepEqual(image.transform, captureMetadata().transform);
    assert.equal(calls, 1);
    for (const output of [
      frame({ ...captureMetadata(), byteLength: png.byteLength + 1 }, png),
      frame({ ...captureMetadata(), extra: true }, png),
      frame(captureMetadata(), Buffer.concat([png, Buffer.from('extra')])),
      Buffer.from(
        '{"version":"computer-control-native/1","ok":true,"ok":false}\n',
      ),
      Buffer.from(
        '{"version":"computer-control-native/1","ok":false,"code":"PERMISSION_DENIED"}\nextra',
      ),
    ]) {
      const malformed = createMacOSBackend(
        config,
        fake(async () => output),
      );
      await assert.rejects(
        malformed.backend.capture(
          { targetId: 'fixture', surfaceId: 'window:42' },
          deadline,
        ),
        { code: 'CAPTURE_FAILED' },
      );
    }
  } finally {
    deadline.dispose();
  }
});

async function script(source: string): Promise<{
  readonly executable: string;
  readonly directory: string;
  cleanup(): Promise<void>;
}> {
  const directory = await mkdtemp(join(tmpdir(), 'computer-native-test-'));
  const executable = join(directory, 'helper');
  await writeFile(executable, `#!${process.execPath}\n${source}`);
  await chmod(executable, 0o700);
  return {
    executable,
    directory,
    cleanup: () => rm(directory, { recursive: true, force: true }),
  };
}
const statusRequest: NativeRequest = {
  version: 'computer-control-native/1',
  operation: 'status',
  timeoutMs: 1000,
};

await test('native executor uses private stdio without inherited secret environment', async () => {
  const old = process.env['PLOWSHARE_COMPUTER_TEST_SECRET'];
  process.env['PLOWSHARE_COMPUTER_TEST_SECRET'] = 'private-sentinel';
  const fixture = await script(
    "process.stdin.resume();process.stdin.on('end',()=>process.stdout.write(process.env.PLOWSHARE_COMPUTER_TEST_SECRET===undefined?'clean':'leaked')); ",
  );
  const deadline = new OperationDeadline(1000);
  try {
    const output = await new SpawnNativeCaptureProcess(
      join(fixture.directory, 'ownership'),
    ).run(fixture.executable, statusRequest, deadline);
    assert.equal(Buffer.from(output).toString(), 'clean');
  } finally {
    deadline.dispose();
    if (old === undefined) delete process.env['PLOWSHARE_COMPUTER_TEST_SECRET'];
    else process.env['PLOWSHARE_COMPUTER_TEST_SECRET'] = old;
    await fixture.cleanup();
  }
});

await test('native executor kills/reaps expiry and never returns late stdout or raw diagnostics', async () => {
  const fixture = await script(
    "process.stdin.resume();setTimeout(()=>process.stdout.write('late-secret'),500);setInterval(()=>{},1000);",
  );
  const deadline = new OperationDeadline(30);
  const cleanup = new OperationDeadline(1000);
  const runner = new SpawnNativeCaptureProcess(
    join(fixture.directory, 'ownership'),
  );
  try {
    await assert.rejects(
      runner.run(fixture.executable, statusRequest, deadline),
      { code: 'DEADLINE_EXPIRED', message: 'DEADLINE_EXPIRED' },
    );
    await runner.close(cleanup);
    const loud = await script(
      "process.stderr.write('private-path-and-secret');process.exit(1);",
    );
    try {
      await assert.rejects(
        new SpawnNativeCaptureProcess(join(fixture.directory, 'ownership')).run(
          loud.executable,
          statusRequest,
          cleanup,
        ),
        { code: 'CAPTURE_FAILED', message: 'CAPTURE_FAILED' },
      );
    } finally {
      await loud.cleanup();
    }
  } finally {
    deadline.dispose();
    cleanup.dispose();
    await fixture.cleanup();
  }
});

await test('native executor rejects oversized output and explicit close drains active capture', async () => {
  const large = await script(
    'process.stdout.write(Buffer.alloc(1048576+8193));',
  );
  const idle = await script('process.stdin.resume();setInterval(()=>{},1000);');
  const deadline = new OperationDeadline(1000);
  const runner = new SpawnNativeCaptureProcess(
    join(large.directory, 'ownership'),
  );
  try {
    await assert.rejects(
      runner.run(large.executable, statusRequest, deadline),
      { code: 'CAPTURE_FAILED' },
    );
    const capture = runner.run(idle.executable, statusRequest, deadline);
    const rejected = assert.rejects(capture, { code: 'CAPTURE_FAILED' });
    await runner.close(deadline);
    await rejected;
  } finally {
    deadline.dispose();
    await large.cleanup();
    await idle.cleanup();
  }
});

class ControlledChild extends EventEmitter implements NativeChild {
  readonly stdin = new PassThrough();
  readonly stdout = new PassThrough();
  readonly stderr = new PassThrough();
  kills = 0;
  kill(_signal: 'SIGKILL'): boolean {
    this.kills++;
    return false;
  }
}

await test('shared native ownership excludes another binding until actual child exit', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-native-lock-test-'));
  const deadline = new OperationDeadline(1000);
  const child = new ControlledChild();
  let started: (() => void) | undefined;
  const launched = new Promise<void>((resolve) => {
    started = resolve;
  });
  const first = new SpawnNativeCaptureProcess(directory, () => {
    started?.();
    return child;
  });
  let otherLaunches = 0;
  const second = new SpawnNativeCaptureProcess(directory, () => {
    otherLaunches++;
    return new ControlledChild();
  });
  try {
    const capture = first.run('/configured/helper', statusRequest, deadline);
    await launched;
    await assert.rejects(
      second.run('/configured/helper', statusRequest, deadline),
      { code: 'CAPTURE_FAILED' },
    );
    assert.equal(otherLaunches, 0);
    assert.ok((await stat(join(directory, 'capture.lock'))).isDirectory());
    child.stdout.write('settled');
    child.emit('close', 0);
    assert.equal(Buffer.from(await capture).toString(), 'settled');
    await assert.rejects(stat(join(directory, 'capture.lock')), {
      code: 'ENOENT',
    });
  } finally {
    deadline.dispose();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('failed native drain retains a durable fence even after a late child exit', async () => {
  const directory = await mkdtemp(
    join(tmpdir(), 'computer-native-fence-test-'),
  );
  const deadline = new OperationDeadline(20);
  const control = new OperationDeadline(5000);
  const child = new ControlledChild();
  const runner = new SpawnNativeCaptureProcess(directory, () => child, 20);
  try {
    await assert.rejects(
      runner.run('/configured/helper', statusRequest, deadline),
      { physicalSettlement: 'uncertain', code: 'DEADLINE_EXPIRED' },
    );
    assert.ok(child.kills > 0);
    child.emit('close', 0);
    await new Promise((resolve) => setImmediate(resolve));
    assert.ok((await stat(join(directory, 'capture.lock'))).isDirectory());
    await assert.rejects(
      runner.run('/configured/helper', statusRequest, control),
      { physicalSettlement: 'uncertain' },
    );
    await assert.rejects(runner.close(control), {
      physicalSettlement: 'uncertain',
    });
    let launched = false;
    const replacement = new SpawnNativeCaptureProcess(directory, () => {
      launched = true;
      return new ControlledChild();
    });
    await assert.rejects(
      replacement.run('/configured/helper', statusRequest, control),
      { code: 'CAPTURE_FAILED' },
    );
    assert.equal(launched, false);
  } finally {
    deadline.dispose();
    control.dispose();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('native close during acquisition prevents every later helper launch', async () => {
  const directory = await mkdtemp(
    join(tmpdir(), 'computer-native-close-test-'),
  );
  const deadline = new OperationDeadline(1000);
  let launches = 0;
  const runner = new SpawnNativeCaptureProcess(directory, () => {
    launches++;
    return new ControlledChild();
  });
  try {
    const running = runner.run('/configured/helper', statusRequest, deadline);
    const refused = assert.rejects(running, { code: 'CAPTURE_FAILED' });
    await runner.close(deadline);
    await refused;
    assert.equal(launches, 0);
    await assert.rejects(stat(join(directory, 'capture.lock')), {
      code: 'ENOENT',
    });
  } finally {
    deadline.dispose();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('synchronous launcher failure settles ownership without exposing diagnostics', async () => {
  const directory = await mkdtemp(
    join(tmpdir(), 'computer-native-launch-test-'),
  );
  const deadline = new OperationDeadline(1000);
  const runner = new SpawnNativeCaptureProcess(directory, () => {
    throw new Error('private executable path');
  });
  try {
    await assert.rejects(
      runner.run('/configured/helper', statusRequest, deadline),
      {
        code: 'CAPTURE_FAILED',
        physicalSettlement: 'settled',
        message: 'CAPTURE_FAILED',
      },
    );
    await runner.close(deadline);
    await assert.rejects(stat(join(directory, 'capture.lock')), {
      code: 'ENOENT',
    });
  } finally {
    deadline.dispose();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('failed ownership force before spawn retains durable fence and fails close', async () => {
  const directory = await mkdtemp(
    join(tmpdir(), 'computer-native-force-test-'),
  );
  const deadline = new OperationDeadline(1000);
  let launches = 0;
  const runner = new SpawnNativeCaptureProcess(
    directory,
    () => {
      launches++;
      return new ControlledChild();
    },
    20,
    {
      force: async () => {
        throw new Error('force failed');
      },
    },
  );
  try {
    await assert.rejects(
      runner.run('/configured/helper', statusRequest, deadline),
      { code: 'CAPTURE_FAILED', physicalSettlement: 'uncertain' },
    );
    assert.equal(launches, 0);
    assert.ok((await stat(join(directory, 'capture.lock'))).isDirectory());
    await assert.rejects(runner.close(deadline), {
      physicalSettlement: 'uncertain',
    });
  } finally {
    deadline.dispose();
    await rm(directory, { recursive: true, force: true });
  }
});
