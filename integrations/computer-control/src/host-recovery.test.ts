import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import {
  mkdtemp,
  readFile,
  readdir,
  rm,
  unlink,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { localLock } from 'plowshare-client-node/connections';
import { toolDeploymentConfig } from 'plowshare-client-node/tools';
import { decodeToolCall } from 'plowshare-client-ts';
import type { ToolCall, ToolReceipt } from 'plowshare-client-ts';
import { decodeComputerHostConfig } from './config.ts';
import { decodeCloseArguments } from './contracts.ts';
import { FakeClock } from './contracts.test-support.ts';
import { computerDeclarations } from './provider.ts';
import { runComputerHost } from './runner.ts';
import {
  binding,
  call,
  CaptureBackend,
  client,
  MemoryJournal,
  metadata,
  record,
  RelayWire,
} from './provider.test-support.ts';

const identity = createHash('sha256')
  .update(toolDeploymentConfig(binding, computerDeclarations()))
  .digest('hex');
function gate() {
  let release = () => {};
  const promise = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { promise, release: () => release() };
}
async function pause(ms: number): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, ms));
}
async function boundedGate(
  work: Promise<void>,
  message: string,
): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    await Promise.race([
      work,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error(message)), 1500);
      }),
    ]);
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}
function config(directory: string, controlMs = 2000) {
  return decodeComputerHostConfig({
    version: 1,
    origin: 'https://fixture.invalid',
    binding,
    credential: { kind: 'saved', directory },
    journalDirectory: directory,
    budgets: { startupMs: 1000, controlMs, cleanupMs: 1000 },
    catalogue: { leaseSeconds: 300, renewalMs: 30000 },
    browser: {
      executablePath: '/fixture/browser',
      headless: true,
      targets: [
        {
          targetId: 'fixture-target',
          label: 'Fixture',
          url: 'https://fixture.invalid',
          viewport: { width: 100, height: 80 },
        },
      ],
    },
  });
}
async function active(directory: string): Promise<boolean> {
  return (await readdir(directory)).some((name) => name.endsWith('.active'));
}

for (const reason of ['deadline', 'stop'] as const) {
  await test(
    `SDK-held host lock survives ${reason} admission refusal and never transfers backend ownership`,
    { timeout: 4000 },
    async () => {
      const directory = await mkdtemp(join(tmpdir(), 'computer-held-host-'));
      const selectedConfig = config(
        directory,
        reason === 'deadline' ? 50 : 1000,
      );
      const hostIdentity = createHash('sha256')
        .update(
          toolDeploymentConfig(
            binding,
            computerDeclarations(selectedConfig.prefix, {
              controlBudgetMs: selectedConfig.budgets.controlMs,
              observeBudgetMs: selectedConfig.budgets.observeMs,
            }),
          ),
        )
        .digest('hex');
      const lock = join(directory, hostIdentity + '.lock'),
        sentinel = join(lock, 'sentinel');
      const entered = gate(),
        release = gate(),
        stop = new AbortController();
      const wire = new RelayWire(),
        connection = client(wire),
        clock = new FakeClock(),
        backend = new CaptureBackend(clock);
      let closes = 0,
        ready = 0;
      const holder = localLock(directory, hostIdentity, async () => {
        await writeFile(sentinel, 'owned fixture', { mode: 0o600 });
        entered.release();
        await release.promise;
        await unlink(sentinel);
      });
      let stopTimer: ReturnType<typeof setTimeout> | undefined;
      try {
        await boundedGate(
          Promise.race([entered.promise, holder]),
          'Fixture lock was not acquired',
        );
        const began = performance.now();
        const running = runComputerHost({
          config: selectedConfig,
          connection,
          currentBearer: async () => 'fixture',
          signal: stop.signal,
          backends: [
            {
              backend,
              close: async () => {
                closes++;
              },
            },
          ],
          journal: new MemoryJournal(hostIdentity),
          maxPasses: 1,
          onReady: () => {
            ready++;
          },
        });
        if (reason === 'stop') stopTimer = setTimeout(() => stop.abort(), 30);
        await assert.rejects(running, /admission expired|stopped/);
        assert.ok(performance.now() - began < 1000);
        // The SDK's original lock waiter checks its cancelled signal on its next bounded poll.
        await pause(75);
        assert.equal(await readFile(sentinel, 'utf8'), 'owned fixture');
        assert.equal(await active(directory), false);
        assert.equal(closes, 0);
        assert.equal(ready, 0);
        assert.equal(wire.publications.length, 0);
        release.release();
        await holder;
        await pause(35);
        assert.equal(closes, 0);
        assert.equal(ready, 0);
        assert.equal(wire.publications.length, 0);
        assert.equal(await active(directory), false);
      } finally {
        if (stopTimer !== undefined) clearTimeout(stopTimer);
        release.release();
        await holder;
        connection.close();
        await rm(directory, { recursive: true, force: true });
      }
    },
  );
}

class ObserveAfterOpenJournal extends MemoryJournal {
  observe: ToolCall | undefined;
  constructor(
    private readonly wire: RelayWire,
    private readonly clock: FakeClock,
  ) {
    super(identity);
  }
  override async save(row: ToolReceipt): Promise<void> {
    await super.save(row);
    if (
      this.observe !== undefined ||
      row.phase !== 'done' ||
      row.result === null
    )
      return;
    if (decodeToolCall(row.request).tool !== 'computer_open') return;
    const session = record(metadata(row.result)['session']);
    const reference = decodeCloseArguments({
      sessionId: session['sessionId'],
      generation: session['generation'],
    });
    this.observe = call(this.clock, 'observe', {
      ...reference,
      surfaceId: 'viewport',
    });
    this.wire.calls.push(this.observe);
  }
}

await test(
  'stop closes the actual SDK capture backend before drain and never publishes late image work',
  { timeout: 5000 },
  async () => {
    const directory = await mkdtemp(join(tmpdir(), 'computer-capture-close-'));
    const clock = new FakeClock(),
      wire = new RelayWire(),
      connection = client(wire),
      backend = new CaptureBackend(clock),
      journal = new ObserveAfterOpenJournal(wire, clock);
    const began = gate(),
      closing = gate(),
      allowClose = gate();
    let finishCapture: ((value: typeof backend.value) => void) | undefined;
    const physicalCapture = new Promise<typeof backend.value>((resolve) => {
      finishCapture = resolve;
    });
    const stop = new AbortController();
    let closes = 0,
      uploads = 0,
      hostSettled = false;
    backend.capture = async () => {
      backend.captures++;
      began.release();
      return physicalCapture;
    };
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => {
      uploads++;
      throw new Error('unexpected late image upload');
    };
    const running = runComputerHost({
      config: config(directory),
      connection,
      currentBearer: async () => 'fixture',
      signal: stop.signal,
      clock,
      journal,
      backends: [
        {
          backend,
          close: async () => {
            closes++;
            closing.release();
            await allowClose.promise;
            assert.ok(finishCapture);
            finishCapture(backend.value);
          },
        },
      ],
      onReady: () =>
        wire.calls.push(call(clock, 'open', { targetId: 'fixture-target' })),
    });
    const observed = running.then(
      () => {
        hostSettled = true;
      },
      (error: unknown) => {
        hostSettled = true;
        throw error;
      },
    );
    try {
      await boundedGate(
        Promise.race([
          began.promise,
          observed.then(() => {
            throw new Error('Host ended before capture');
          }),
        ]),
        'Capture never began',
      );
      stop.abort();
      // Waiting for drain before starting close would deadlock: only close settles this capture.
      await boundedGate(
        Promise.race([
          closing.promise,
          observed.then(() => {
            throw new Error('Host ended without backend close');
          }),
        ]),
        'Stop did not begin backend close before drain',
      );
      assert.equal(closes, 1);
      assert.equal(hostSettled, false);
      assert.equal(await active(directory), true);
      for (let i = 0; i < 30; i++) await Promise.resolve();
      assert.ok(journal.observe);
      const before = journal.rows.get(journal.observe.invocationId);
      assert.ok(before?.result);
      assert.equal(before.result.state, 'UNKNOWN');
      const immutable = JSON.stringify(before.result);
      assert.equal(uploads, 0);
      assert.equal(backend.captures, 1);
      allowClose.release();
      await observed;
      const after = journal.rows.get(journal.observe.invocationId);
      assert.equal(JSON.stringify(after?.result), immutable);
      assert.equal(after?.result?.admissions, undefined);
      assert.equal(closes, 1);
      assert.equal(uploads, 0);
      assert.equal(backend.captures, 1);
      assert.equal(await active(directory), false);
      assert.equal(clock.pendingTimers(), 0);
    } finally {
      stop.abort();
      allowClose.release();
      finishCapture?.(backend.value);
      await observed.catch(() => {});
      globalThis.fetch = originalFetch;
      connection.close();
      await rm(directory, { recursive: true, force: true });
    }
  },
);
