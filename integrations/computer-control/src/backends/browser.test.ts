import assert from 'node:assert/strict';
import { test } from 'node:test';
import { BackendError } from '../contracts.ts';
import { OperationDeadline } from '../deadline.ts';
import {
  browserStartupStep,
  createBrowserBackend,
  decodeBrowserBackendConfig,
} from './browser.ts';
import type {
  BrowserBackendConfig,
  BrowserCaptureDriver,
  BrowserPixels,
} from './browser.ts';

const png = Uint8Array.from(
  Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jB1kAAAAASUVORK5CYII=',
    'base64',
  ),
);
const config: BrowserBackendConfig = {
  executablePath: '/test/browser',
  headless: true,
  targets: [
    {
      targetId: 'fixture',
      label: 'Fixture',
      url: 'https://fixture.invalid/',
      viewport: { width: 1, height: 1 },
    },
  ],
};
function pixels(): BrowserPixels {
  return {
    bytes: png,
    width: 1,
    height: 1,
    capturedAt: '2026-11-16T00:00:00.000Z',
    layoutGeneration: 'layout-1',
  };
}
function driver(
  capture: BrowserCaptureDriver['capture'] = async () => pixels(),
): BrowserCaptureDriver {
  return { capture, close: async () => {} };
}

await test('browser configuration rejects unknown values before launch', async () => {
  let launches = 0;
  const deadline = new OperationDeadline(1000);
  try {
    await assert.rejects(
      createBrowserBackend(
        { ...config, executablePath: 'browser' },
        deadline,
        async () => {
          launches++;
          return driver();
        },
      ),
    );
    assert.equal(launches, 0);
    assert.throws(() => decodeBrowserBackendConfig({ ...config, extra: true }));
    assert.throws(() =>
      decodeBrowserBackendConfig({
        ...config,
        targets: [
          { ...config.targets[0], viewport: { width: 1601, height: 1 } },
        ],
      }),
    );
    assert.throws(() =>
      decodeBrowserBackendConfig({
        ...config,
        targets: [
          { ...config.targets[0], url: 'https://user:secret@fixture.invalid/' },
        ],
      }),
    );
  } finally {
    deadline.dispose();
  }
});

await test('browser backend preserves PNG bytes, viewport coordinates and configured target scope', async () => {
  let calls = 0;
  const deadline = new OperationDeadline(1000);
  try {
    const handle = await createBrowserBackend(config, deadline, async () =>
      driver(async () => {
        calls++;
        return pixels();
      }),
    );
    assert.deepEqual(
      (await handle.backend.targets(deadline))[0]?.capabilities,
      ['capture'],
    );
    const capture = await handle.backend.capture(
      { targetId: 'fixture', surfaceId: 'viewport' },
      deadline,
    );
    assert.deepEqual(capture.bytes, png);
    assert.notEqual(capture.bytes, png);
    assert.deepEqual(capture.transform, { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 });
    await assert.rejects(
      handle.backend.capture(
        { targetId: 'foreign', surfaceId: 'viewport' },
        deadline,
      ),
      { code: 'TARGET_GONE' },
    );
    await assert.rejects(
      handle.backend.capture(
        { targetId: 'fixture', surfaceId: 'other' },
        deadline,
      ),
      { code: 'TARGET_GONE' },
    );
    assert.equal(calls, 1);
    await handle.close(deadline);
    await assert.rejects(
      handle.backend.capture(
        { targetId: 'fixture', surfaceId: 'viewport' },
        deadline,
      ),
      { code: 'TARGET_GONE' },
    );
  } finally {
    deadline.dispose();
  }
});

await test('browser rejects dimensions/bytes outside the capture contract', async () => {
  const deadline = new OperationDeadline(1000);
  try {
    const dimensions = await createBrowserBackend(config, deadline, async () =>
      driver(async () => ({ ...pixels(), width: 2 })),
    );
    await assert.rejects(
      dimensions.backend.capture(
        { targetId: 'fixture', surfaceId: 'viewport' },
        deadline,
      ),
      BackendError,
    );
    const bytes = await createBrowserBackend(config, deadline, async () =>
      driver(async () => ({ ...pixels(), bytes: new Uint8Array(1_048_577) })),
    );
    await assert.rejects(
      bytes.backend.capture(
        { targetId: 'fixture', surfaceId: 'viewport' },
        deadline,
      ),
      BackendError,
    );
  } finally {
    deadline.dispose();
  }
});

await test('browser keeps an unsettled capture exclusive and refuses its late output after expiry', async () => {
  let finish: ((value: BrowserPixels) => void) | undefined;
  const deadline = new OperationDeadline(25);
  const control = new OperationDeadline(1000);
  try {
    const handle = await createBrowserBackend(config, control, async () =>
      driver(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      ),
    );
    const capture = handle.backend.capture(
      { targetId: 'fixture', surfaceId: 'viewport' },
      deadline,
    );
    await assert.rejects(
      handle.backend.capture(
        { targetId: 'fixture', surfaceId: 'viewport' },
        control,
      ),
      { code: 'CAPTURE_FAILED' },
    );
    await new Promise<void>((resolve) =>
      deadline.signal.addEventListener('abort', () => resolve(), {
        once: true,
      }),
    );
    assert.ok(finish);
    finish(pixels());
    await assert.rejects(capture, { code: 'DEADLINE_EXPIRED' });
    await assert.rejects(
      handle.backend.capture(
        { targetId: 'fixture', surfaceId: 'viewport' },
        control,
      ),
      { code: 'TARGET_GONE' },
    );
  } finally {
    deadline.dispose();
    control.dispose();
  }
});

await test('late browser startup is closed before its expired handle can escape', async () => {
  const deadline = new OperationDeadline(10);
  let closed = false;
  const startup = createBrowserBackend(config, deadline, async () => {
    await new Promise<void>((resolve) =>
      deadline.signal.addEventListener('abort', () => resolve(), {
        once: true,
      }),
    );
    return {
      capture: async () => pixels(),
      close: async () => {
        closed = true;
      },
    };
  });
  try {
    await assert.rejects(startup, { code: 'DEADLINE_EXPIRED' });
    assert.equal(closed, true);
  } finally {
    deadline.dispose();
  }
});

await test('browser startup cleanup uses its configured budget and preserves cleanup uncertainty', async () => {
  const deadline = new OperationDeadline(10);
  let remaining = 0;
  let attempted: (() => void) | undefined;
  const attempt = new Promise<void>((resolve) => {
    attempted = resolve;
  });
  const startup = createBrowserBackend(
    { ...config, cleanupBudgetMs: 75 },
    deadline,
    async () => {
      await new Promise<void>((resolve) =>
        deadline.signal.addEventListener('abort', () => resolve(), {
          once: true,
        }),
      );
      return {
        capture: async () => pixels(),
        close: async (cleanup) => {
          remaining = cleanup.remainingMs();
          attempted?.();
          throw new BackendError('CAPTURE_FAILED', 'uncertain');
        },
      };
    },
  );
  try {
    await assert.rejects(startup, {
      physicalSettlement: 'uncertain',
    });
    await attempt;
    assert.ok(remaining > 0 && remaining <= 75);
    assert.throws(() =>
      decodeBrowserBackendConfig({ ...config, cleanupBudgetMs: 0 }),
    );
  } finally {
    deadline.dispose();
  }
});

await test('hung startup RPC and failed close stay bounded with an uncertain outcome', async () => {
  const deadline = new OperationDeadline(15);
  const cleanup = new OperationDeadline(40);
  let cleanupExpired = false;
  const running = createBrowserBackend(
    { ...config, cleanupBudgetMs: 25 },
    deadline,
    async (_config, operation) => {
      try {
        await browserStartupStep(new Promise<never>(() => {}), operation);
        throw new Error('Unreachable startup');
      } catch {
        try {
          await browserStartupStep(new Promise<never>(() => {}), cleanup);
        } finally {
          cleanupExpired = true;
        }
        throw new Error('Unreachable cleanup');
      }
    },
  );
  try {
    await assert.rejects(running, {
      code: 'DEADLINE_EXPIRED',
      physicalSettlement: 'uncertain',
    });
    if (!cleanup.signal.aborted)
      await new Promise<void>((resolve) =>
        cleanup.signal.addEventListener('abort', () => resolve(), {
          once: true,
        }),
      );
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(cleanupExpired, true);
  } finally {
    deadline.dispose();
    cleanup.dispose();
  }
});

await test('non-cooperative factory cannot block expiry and its late driver is closed', async () => {
  const deadline = new OperationDeadline(15);
  let provide: ((value: BrowserCaptureDriver) => void) | undefined;
  let closed: (() => void) | undefined;
  const closure = new Promise<void>((resolve) => {
    closed = resolve;
  });
  const running = createBrowserBackend(
    config,
    deadline,
    () =>
      new Promise((resolve) => {
        provide = resolve;
      }),
  );
  try {
    await assert.rejects(running, {
      code: 'DEADLINE_EXPIRED',
      physicalSettlement: 'uncertain',
    });
    assert.ok(provide);
    provide({
      capture: async () => pixels(),
      close: async () => {
        closed?.();
      },
    });
    await closure;
  } finally {
    deadline.dispose();
  }
});
