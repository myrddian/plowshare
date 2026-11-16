import assert from 'node:assert/strict';
import { test } from 'node:test';
import { BackendError } from '../contracts.ts';
import { OperationDeadline } from '../deadline.ts';
import { FakeClock, owner } from '../contracts.test-support.ts';
import type {
  BrowserActionResult,
  BrowserObservation,
} from '../browser-control.ts';
import {
  createBrowserControlBackend,
  decodeBrowserControlBackendConfig,
} from './browser-control-backend.ts';
import type {
  BrowserControlBackendConfig,
  BrowserControlDriver,
  ControlledBrowserContext,
} from './browser-control-backend.ts';
import type {
  BrowserOwnership,
  BrowserOwnershipLease,
} from './browser-ownership.ts';

const config: BrowserControlBackendConfig = {
  executablePath: '/fixture/browser',
  headless: true,
  ownershipDirectory: '/fixture/ownership',
  cleanupBudgetMs: 30,
  targets: [
    {
      targetId: 'fixture',
      label: 'Fixture',
      url: 'https://fixture.test/',
      viewport: { width: 320, height: 160 },
    },
  ],
  control: {
    enabled: true,
    tools: ['windows', 'focus', 'navigate', 'pointer', 'type', 'key', 'scroll'],
    allowedOrigins: ['https://fixture.test'],
  },
};
const session = {
  sessionId: 'fixture-session',
  generation: 'fixture-generation',
};
const guard = {
  surfaceId: 'viewport',
  documentId: 'fixture-document',
  layoutGeneration: 'fixture-layout',
  focusGeneration: 'fixture-focus',
};
const request = {
  expected: guard,
  observationReserveMs: 10,
  action: {
    kind: 'key' as const,
    expectedFocus: null,
    key: 'Enter' as const,
    modifiers: [],
  },
};
const image = Uint8Array.from(
  Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jB1kAAAAASUVORK5CYII=',
    'base64',
  ),
);
function observation(): BrowserObservation {
  return {
    image: {
      bytes: image,
      mimeType: 'image/png',
      width: 1,
      height: 1,
      transform: { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 },
      capturedAt: '2026-11-16T00:00:00.000Z',
      layoutGeneration: guard.layoutGeneration,
    },
    state: {
      ...guard,
      readiness: 'ready',
      guardInstalled: true,
      url: 'https://fixture.test/',
      origin: 'https://fixture.test',
      requestedUrl: null,
      focusedElementId: null,
    },
    snapshot: null,
  };
}
function delivered(): BrowserActionResult {
  return {
    delivery: {
      outcome: 'DISPATCHED',
      physicalSettlement: 'settled',
      mechanism: 'keyboard',
    },
    observation: { state: 'FAILED', code: 'OBSERVATION_FAILED' },
    dialog: null,
  };
}
function context(
  overrides: Partial<ControlledBrowserContext> = {},
): ControlledBrowserContext {
  return {
    windows: async () => ({
      windowsId: 'fixture-windows',
      capturedAt: '2026-11-16T00:00:00.000Z',
      tabs: [{ state: observation().state, title: 'Fixture' }],
      truncated: false,
    }),
    observe: async () => observation(),
    focus: async () => delivered(),
    navigate: async () => delivered(),
    perform: async () => delivered(),
    close: async () => ({
      physicalSettlement: 'settled',
      heldInputsReleased: true,
    }),
    ...overrides,
  };
}
class Ownership implements BrowserOwnership {
  readonly events: string[] = [];
  held = false;
  fenced = false;
  async acquire(): Promise<BrowserOwnershipLease> {
    if (this.held) throw new BackendError('CAPTURE_FAILED', 'uncertain');
    this.held = true;
    this.events.push('acquired');
    return {
      fence: () => {
        this.fenced = true;
        this.events.push('fenced');
      },
      releaseAfterQuiescence: async () => {
        if (this.fenced) throw new BackendError('CAPTURE_FAILED', 'uncertain');
        this.events.push('released');
        this.held = false;
      },
    };
  }
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((accept) => {
    resolve = accept;
  });
  return { promise, resolve };
}
async function fixture(
  overrides: Partial<ControlledBrowserContext> = {},
  selected = config,
) {
  const ownership = new Ownership();
  const driver: BrowserControlDriver = {
    open: async () => context(overrides),
    close: async () => {},
  };
  const deadline = new OperationDeadline(1000);
  const handle = await createBrowserControlBackend(
    selected,
    deadline,
    async () => driver,
    ownership,
  );
  const control = handle.backend.browserControl;
  const lease = await control.acquire(
    { targetId: 'fixture', owner, session },
    deadline,
  );
  return { ownership, handle, control, lease, deadline };
}

await test('controlled browser physical-domain declaration follows its actual headless mode', async () => {
  for (const headless of [true, false]) {
    const f = await fixture({}, { ...config, headless });
    try {
      assert.equal(
        f.handle.backend.desktopIsolation,
        headless ? 'isolated' : 'physical',
      );
      await f.control.release(f.lease, f.deadline);
      await f.handle.close(f.deadline);
    } finally {
      f.deadline.dispose();
    }
  }
});

await test('controlled config requires explicit policy and stable ownership storage', () => {
  for (const delta of [
    { control: { ...config.control, enabled: false } },
    { ownershipDirectory: 'relative' },
    { maxTabs: 33 },
    { maxDomNodes: 201 },
    { maxVisitedNodes: 1 },
    { pixelsPerLine: 257 },
    { unknown: true },
    { control: { ...config.control, allowedOrigins: ['https://other.test'] } },
  ])
    assert.throws(() =>
      decodeBrowserControlBackendConfig({ ...config, ...delta }),
    );
  const checked = decodeBrowserControlBackendConfig(config);
  assert.equal(checked.maxDomNodes, 200);
  assert.equal(checked.maxVisitedNodes, 2000);
});

await test('an expired factory deadline performs no browser launch', async () => {
  let calls = 0;
  const deadline = new OperationDeadline(1000);
  deadline.cancel();
  try {
    await assert.rejects(
      createBrowserControlBackend(config, deadline, async () => {
        calls++;
        return { open: async () => context(), close: async () => {} };
      }),
    );
    assert.equal(calls, 0);
  } finally {
    deadline.dispose();
  }
});

await test('monotonic expiry before queued factory start performs no launch without a timer callback', async () => {
  let calls = 0;
  const clock = new FakeClock(),
    deadline = new OperationDeadline(20, clock);
  try {
    const opening = createBrowserControlBackend(config, deadline, async () => {
      calls++;
      return { open: async () => context(), close: async () => {} };
    });
    clock.monotonic = 20;
    await assert.rejects(opening);
    assert.equal(calls, 0);
  } finally {
    deadline.dispose();
  }
});

await test('controlled factory bare capture always refuses; the initial alias remains viewport', async () => {
  const f = await fixture();
  try {
    assert.equal(
      (await f.handle.backend.targets(f.deadline))[0]?.surfaces[0]?.id,
      'viewport',
    );
    await assert.rejects(
      f.handle.backend.capture(
        { targetId: 'fixture', surfaceId: 'viewport' },
        f.deadline,
      ),
      { code: 'NO_LEASE' },
    );
    assert.equal(
      (
        await f.control.observe(
          f.lease,
          { surfaceId: 'viewport', structured: false },
          f.deadline,
        )
      ).image.width,
      1,
    );
    await f.control.release(f.lease, f.deadline);
    await assert.rejects(
      f.handle.backend.capture(
        { targetId: 'fixture', surfaceId: 'viewport' },
        f.deadline,
      ),
      { code: 'NO_LEASE' },
    );
  } finally {
    await f.handle.close(f.deadline);
    f.deadline.dispose();
  }
});

await test('lease excludes a second owner and forged session/generation before driver work', async () => {
  const f = await fixture();
  try {
    await assert.rejects(
      f.control.acquire(
        {
          targetId: 'fixture',
          owner: { ...owner, provider: 'other' },
          session,
        },
        f.deadline,
      ),
      { code: 'BUSY' },
    );
    for (const lease of [
      { ...f.lease, leaseId: 'other' },
      { ...f.lease, generation: 'other' },
      { ...f.lease, sessionId: 'other' },
    ])
      await assert.rejects(
        f.control.observe(
          lease,
          { surfaceId: 'viewport', structured: false },
          f.deadline,
        ),
        { code: 'NO_LEASE' },
      );
  } finally {
    await f.handle.close(f.deadline);
    f.deadline.dispose();
  }
});

await test('durable ownership precedes context creation and a released lease gets a fresh context', async () => {
  const ownership = new Ownership();
  let opened = 0,
    closed = 0;
  const deadline = new OperationDeadline(1000);
  const handle = await createBrowserControlBackend(
    config,
    deadline,
    async () => ({
      open: async () => {
        assert.ok(ownership.held);
        opened++;
        return context({
          close: async () => {
            closed++;
            return { physicalSettlement: 'settled', heldInputsReleased: true };
          },
        });
      },
      close: async () => {},
    }),
    ownership,
  );
  try {
    const control = handle.backend.browserControl;
    const first = await control.acquire(
      { targetId: 'fixture', owner, session },
      deadline,
    );
    assert.equal(
      (await control.release(first, deadline)).physicalSettlement,
      'settled',
    );
    const second = await control.acquire(
      {
        targetId: 'fixture',
        owner,
        session: { ...session, sessionId: 'second' },
      },
      deadline,
    );
    assert.notEqual(first.leaseId, second.leaseId);
    assert.equal(opened, 2);
    assert.equal(closed, 1);
  } finally {
    await handle.close(deadline);
    deadline.dispose();
  }
});

await test('tools and origin policy refuse before any driver input', async () => {
  let performed = 0;
  const f = await fixture(
    {
      perform: async () => {
        performed++;
        return delivered();
      },
    },
    { ...config, control: { ...config.control, tools: ['navigate'] } },
  );
  try {
    await assert.rejects(
      async () => f.control.perform(f.lease, request, f.deadline),
      {
        code: 'CONTROL_DISABLED',
      },
    );
    await assert.rejects(
      async () =>
        f.control.navigate(
          f.lease,
          {
            expected: guard,
            url: 'https://other.test/',
            observationReserveMs: 10,
          },
          f.deadline,
        ),
      { code: 'ORIGIN_DENIED' },
    );
    assert.equal(performed, 0);
  } finally {
    await f.handle.close(f.deadline);
    f.deadline.dispose();
  }
});

await test('in-flight read keeps the context occupied until physical work settles', async () => {
  const pending = deferred<BrowserObservation>();
  const entered = deferred<void>();
  const f = await fixture({
    observe: () => {
      entered.resolve();
      return pending.promise;
    },
  });
  try {
    const observing = f.control.observe(
      f.lease,
      { surfaceId: 'viewport', structured: false },
      f.deadline,
    );
    await entered.promise;
    await assert.rejects(f.control.perform(f.lease, request, f.deadline), {
      code: 'BUSY',
    });
    pending.resolve(observation());
    await observing;
    assert.equal(
      (await f.control.perform(f.lease, request, f.deadline)).delivery.outcome,
      'DISPATCHED',
    );
  } finally {
    await f.handle.close(f.deadline);
    f.deadline.dispose();
  }
});

await test('unexpected action throw enters cleanup and remains UNKNOWN without replay', async () => {
  let calls = 0,
    closes = 0;
  const f = await fixture({
    perform: async () => {
      calls++;
      throw new Error('private browser payload');
    },
    close: async () => {
      closes++;
      return { physicalSettlement: 'settled', heldInputsReleased: true };
    },
  });
  try {
    const result = await f.control.perform(f.lease, request, f.deadline);
    assert.deepEqual(result.delivery, {
      outcome: 'UNCERTAIN',
      physicalSettlement: 'settled',
      code: 'INPUT_FAILED',
    });
    assert.equal(calls, 1);
    assert.equal(closes, 1);
    await assert.rejects(f.control.perform(f.lease, request, f.deadline), {
      code: 'FENCED',
    });
  } finally {
    await f.handle.close(f.deadline);
    f.deadline.dispose();
  }
});

await test('known delivery survives observation deadline expiry as a text-only result', async () => {
  const clock = new FakeClock();
  const caller = new OperationDeadline(50, clock);
  const entered = deferred<void>();
  const f = await fixture({
    perform: (_request, budget) =>
      new Promise((resolve) => {
        budget.signal.addEventListener('abort', () => resolve(delivered()), {
          once: true,
        });
        entered.resolve();
      }),
  });
  try {
    const action = f.control.perform(f.lease, request, caller);
    await entered.promise;
    clock.advance(50);
    const result = await action;
    assert.equal(result.delivery.outcome, 'DISPATCHED');
    assert.equal(result.observation.state, 'FAILED');
  } finally {
    caller.dispose();
    await f.handle.close(f.deadline);
    f.deadline.dispose();
  }
});

await test('expiry before the queued operation starts invokes no driver method', async () => {
  let calls = 0;
  const f = await fixture({
    perform: async () => {
      calls++;
      return delivered();
    },
  });
  const clock = new FakeClock();
  const deadline = new OperationDeadline(20, clock);
  try {
    const action = f.control.perform(f.lease, request, deadline);
    clock.advance(20);
    await assert.rejects(action, { code: 'DEADLINE_EXPIRED' });
    assert.equal(calls, 0);
  } finally {
    deadline.dispose();
    await f.handle.close(f.deadline);
    f.deadline.dispose();
  }
});

await test('release cancels an active operation and waits for its owned close/settlement', async () => {
  const pending = deferred<BrowserObservation>();
  const entered = deferred<void>();
  let cancelled = false;
  const f = await fixture({
    observe: (_request, budget) => {
      budget.signal.addEventListener(
        'abort',
        () => {
          cancelled = true;
        },
        { once: true },
      );
      entered.resolve();
      return pending.promise;
    },
    close: async () => {
      pending.resolve(observation());
      return { physicalSettlement: 'settled', heldInputsReleased: true };
    },
  });
  try {
    const observing = f.control.observe(
      f.lease,
      { surfaceId: 'viewport', structured: false },
      f.deadline,
    );
    await entered.promise;
    assert.equal(
      (await f.control.release(f.lease, f.deadline)).physicalSettlement,
      'settled',
    );
    await observing;
    assert.ok(cancelled);
    assert.ok(!f.ownership.held);
  } finally {
    await f.handle.close(f.deadline);
    f.deadline.dispose();
  }
});

await test('unsettled context cleanup retains the durable fence and never reacquires', async () => {
  const f = await fixture({
    close: async () => ({
      physicalSettlement: 'uncertain',
      heldInputsReleased: false,
    }),
  });
  try {
    assert.equal(
      (await f.control.release(f.lease, f.deadline)).physicalSettlement,
      'uncertain',
    );
    assert.ok(f.ownership.held && f.ownership.fenced);
    await assert.rejects(
      f.control.acquire({ targetId: 'fixture', owner, session }, f.deadline),
      { code: 'BUSY' },
    );
    await assert.rejects(f.handle.close(f.deadline), {
      physicalSettlement: 'uncertain',
    });
  } finally {
    f.deadline.dispose();
  }
});

await test('late context acquisition after cancellation is closed and never published', async () => {
  const pending = deferred<ControlledBrowserContext>();
  const entered = deferred<void>();
  let closed = 0;
  const ownership = new Ownership();
  const deadline = new OperationDeadline(1000);
  const handle = await createBrowserControlBackend(
    config,
    deadline,
    async () => ({
      open: () => {
        entered.resolve();
        return pending.promise;
      },
      close: async () => {},
    }),
    ownership,
  );
  const opening = handle.backend.browserControl.acquire(
    { targetId: 'fixture', owner, session },
    deadline,
  );
  await entered.promise;
  deadline.cancel();
  await assert.rejects(opening, { physicalSettlement: 'uncertain' });
  pending.resolve(
    context({
      close: async () => {
        closed++;
        return { physicalSettlement: 'settled', heldInputsReleased: true };
      },
    }),
  );
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(closed, 1);
  assert.ok(ownership.held && ownership.fenced);
  const cleanup = new OperationDeadline(1000);
  try {
    await assert.rejects(handle.close(cleanup), {
      physicalSettlement: 'uncertain',
    });
  } finally {
    cleanup.dispose();
    deadline.dispose();
  }
});
