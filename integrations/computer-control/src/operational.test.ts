import assert from 'node:assert/strict';
import test from 'node:test';
import { spawn } from 'node:child_process';
import type { ChildProcess } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, readdir, rm, rmdir, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { localLock } from 'plowshare-client-node/connections';
import {
  FileToolJournal,
  toolDeploymentConfig,
} from 'plowshare-client-node/tools';
import { decodeToolCall } from 'plowshare-client-ts';
import type { ToolCall, ToolReceipt, ToolResult } from 'plowshare-client-ts';
import { decodeCloseArguments } from './contracts.ts';
import { FakeClock } from './contracts.test-support.ts';
import { OperationDeadline } from './deadline.ts';
import { computerDeclarations, createComputerProvider } from './provider.ts';
import type { ComputerProvider } from './provider.ts';
import { ComputerPasses } from './runner.ts';
import {
  admissions,
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

async function drain(provider: ComputerProvider): Promise<void> {
  provider.stop();
  const deadline = new OperationDeadline(2000);
  try {
    await provider.drain(deadline);
  } finally {
    deadline.dispose();
  }
}

await test(
  'bounded SDK/file-journal soak retains receipts and image allowance across host reconstruction',
  { timeout: 20000 },
  async () => {
    const directory = await mkdtemp(
      join(tmpdir(), 'computer-operational-soak-'),
    );
    const lockDeadline = new OperationDeadline(2000);
    const overall = new OperationDeadline(15000);
    const clock = new FakeClock(),
      backend = new CaptureBackend(clock);
    let uploads = 0;
    let provider: ComputerProvider | undefined,
      connection: ReturnType<typeof client> | undefined;
    try {
      await localLock(
        directory,
        identity,
        async () => {
          const publications: RelayWire['publications'] = [];
          const receipts = new Map<string, ToolReceipt>();
          let firstImage: ToolCall | undefined;
          for (let epoch = 0; epoch < 2; epoch++) {
            const journal = await FileToolJournal.open(directory, identity);
            const wire = new RelayWire(publications);
            connection = client(wire);
            const passes = new ComputerPasses(connection, 2000);
            provider = await createComputerProvider({
              connection: passes.connection,
              binding,
              journal,
              backends: [backend],
              generation: 'soak-' + epoch,
              origin: 'https://fixture.invalid',
              currentBearer: async () => 'fixture',
              clock,
              http: async (...args) => {
                uploads++;
                return admissions()(...args);
              },
              maxImagesPerRun: 8,
              parentDeadline: () => passes.currentDeadline(),
            });
            const current = provider;
            const invoke = async (request: ToolCall): Promise<ToolResult> => {
              wire.calls.push(request);
              const deadline = overall.child(2000);
              assert.equal(
                await passes.run(
                  deadline,
                  () => current.provider.poll(),
                  () => current.stop(),
                ),
                1,
              );
              const row = (await journal.all()).find(
                (r) => r.invocationId === request.invocationId,
              );
              assert.ok(row?.result);
              assert.equal(row.phase, 'done');
              receipts.set(row.invocationId, row);
              return row.result;
            };
            for (let i = 0; i < 32; i++) await invoke(call(clock, 'targets'));
            if (epoch === 1) {
              assert.ok(firstImage);
              const stale = await invoke(
                call(clock, 'observe', firstImage.arguments),
              );
              assert.equal(stale.state, 'REJECTED');
              assert.equal(metadata(stale)['code'], 'NO_SESSION');
              assert.equal(backend.captures, 8);
              assert.equal(uploads, 8);
            }
            const opened = await invoke(
              call(clock, 'open', { targetId: 'fixture-target' }),
            );
            const row = record(metadata(opened)['session']);
            const session = decodeCloseArguments({
              sessionId: row['sessionId'],
              generation: row['generation'],
            });
            if (epoch === 0) {
              for (let i = 0; i < 8; i++) {
                const observation = call(clock, 'observe', {
                  ...session,
                  surfaceId: 'viewport',
                });
                firstImage ??= observation;
                const result = await invoke(observation);
                assert.equal(result.admissions?.length, 1);
                const before = publications.length;
                await invoke(observation);
                assert.equal(publications.length, before);
              }
            } else {
              assert.ok(firstImage);
              const before = publications.length;
              await invoke(firstImage);
              assert.equal(publications.length, before);
              const denied = await invoke(
                call(clock, 'observe', { ...session, surfaceId: 'viewport' }),
              );
              assert.equal(denied.state, 'REJECTED');
              assert.equal(metadata(denied)['code'], 'FRAME_LIMIT');
              assert.equal(denied.admissions, undefined);
            }
            await invoke(call(clock, 'close', { ...session }));
            const status = current.journal.status();
            if (status.state !== 'AVAILABLE')
              assert.fail('Missing settled capacity');
            assert.equal(status.receiptCount, receipts.size);
            assert.equal(status.phases.done, receipts.size);
            assert.ok(status.freeBytes > 0);
            assert.equal(
              (await stat(join(directory, identity + '.json'))).size,
              status.encodedBytes,
            );
            await drain(current);
            connection.close();
          }
          assert.equal(backend.captures, 8);
          assert.equal(uploads, 8);
          const persisted = await (
            await FileToolJournal.open(directory, identity)
          ).all();
          assert.equal(persisted.length, receipts.size);
          for (const row of persisted)
            assert.deepEqual(row, receipts.get(row.invocationId));
          assert.equal(
            persisted.filter((row) => row.result?.admissions?.length === 1)
              .length,
            8,
          );
        },
        lockDeadline.signal,
      );
      assert.deepEqual(await readdir(directory), [identity + '.json']);
    } finally {
      lockDeadline.dispose();
      overall.dispose();
      if (provider !== undefined) await drain(provider);
      connection?.close();
      await rm(directory, { recursive: true, force: true });
    }
  },
);

await test(
  'a full receipt journal refuses a new SDK delivery before acknowledgement or backend work',
  { timeout: 10000 },
  async () => {
    const clock = new FakeClock(),
      journal = new MemoryJournal(identity),
      backend = new CaptureBackend(clock);
    for (let i = 0; i < 1000; i++) {
      const request = call(clock, 'targets');
      await journal.save({
        invocationId: request.invocationId,
        request: JSON.stringify(request),
        phase: 'done',
        occurredAt: '2026-11-16T00:00:00.000Z',
        result: { state: 'COMPLETED', text: 'retained' },
      });
    }
    let discovery = 0,
      acknowledgements = 0;
    backend.targets = async () => {
      discovery++;
      return [];
    };
    const wire = new RelayWire();
    const send = wire.send.bind(wire);
    wire.send = (source) => {
      if (record(JSON.parse(source))['type'] === 'relay.ack')
        acknowledgements++;
      send(source);
    };
    const connection = client(wire);
    const provider = await createComputerProvider({
      connection,
      binding,
      journal,
      backends: [backend],
      generation: 'full-journal',
      origin: 'https://fixture.invalid',
      currentBearer: async () => 'fixture',
      clock,
      http: admissions(),
    });
    try {
      wire.calls.push(call(clock, 'targets'));
      await assert.rejects(provider.provider.poll(), /capacity/);
      assert.equal(discovery, 0);
      assert.equal(acknowledgements, 0);
      assert.equal(journal.rows.size, 1000);
      assert.equal(wire.publications.length, 0);
      await drain(provider);
    } finally {
      connection.close();
    }
  },
);

interface FixtureExit {
  readonly reaped: boolean;
  readonly code: number | null;
  readonly signal: NodeJS.Signals | null;
}

/** Own only this synthetic child; finite runtime, output, IPC and kill/reap budgets. */
class FixtureProcess {
  readonly child: ChildProcess;
  readonly done: Promise<FixtureExit>;
  private readonly stages = new Set<string>();
  private readonly arrivals = new Map<string, () => void>();
  private invalid = false;
  constructor(mode: 'crash-before-ready' | 'recover', directory: string) {
    this.child = spawn(
      process.execPath,
      [
        fileURLToPath(
          new URL('./operational-process.test-support.js', import.meta.url),
        ),
        mode,
        directory,
      ],
      {
        stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
        env: {},
      },
    );
    let output = 0;
    const consume = (chunk: Buffer) => {
      output += chunk.byteLength;
      if (output > 4096) {
        this.invalid = true;
        this.child.kill('SIGKILL');
      }
    };
    this.child.stdout?.on('data', consume);
    this.child.stderr?.on('data', consume);
    for (const stream of [this.child.stdout, this.child.stderr]) {
      stream?.on('error', () => {
        this.invalid = true;
        this.child.kill('SIGKILL');
      });
    }
    this.child.on('error', () => {
      this.invalid = true;
    });
    this.child.on('message', (value: unknown) => {
      if (value === null || typeof value !== 'object' || Array.isArray(value)) {
        this.invalid = true;
        this.child.kill('SIGKILL');
        return;
      }
      const row = record(value),
        stage = row['stage'];
      if (
        Object.keys(row).length !== 1 ||
        typeof stage !== 'string' ||
        ![
          'EFFECT_RECORDED',
          'RECOVERED_WITHOUT_REPLAY',
          'FIXTURE_REFUSED',
        ].includes(stage) ||
        this.stages.has(stage)
      ) {
        this.invalid = true;
        this.child.kill('SIGKILL');
        return;
      }
      this.stages.add(stage);
      this.arrivals.get(stage)?.();
    });
    this.done = new Promise((resolve) => {
      const kill = setTimeout(() => this.child.kill('SIGKILL'), 5000);
      const limit = setTimeout(
        () => resolve({ reaped: false, code: null, signal: null }),
        7000,
      );
      this.child.once('close', (code, signal) => {
        clearTimeout(kill);
        clearTimeout(limit);
        resolve({ reaped: true, code, signal });
      });
    });
  }
  async stage(name: string): Promise<void> {
    if (!this.stages.has(name))
      await Promise.race([
        new Promise<void>((resolve) => {
          this.arrivals.set(name, resolve);
        }),
        this.done.then(() => {
          throw new Error('Synthetic child ended before its checkpoint');
        }),
      ]);
    assert.equal(this.invalid, false);
  }
  async stop(): Promise<FixtureExit> {
    if (this.child.exitCode === null && this.child.signalCode === null)
      this.child.kill('SIGKILL');
    const result = await this.done;
    assert.equal(
      result.reaped,
      true,
      'Synthetic child did not settle; preserve its test directory',
    );
    assert.equal(this.invalid, false);
    return result;
  }
}

await test(
  'real process crash retains UNKNOWN intent and restart cannot repeat the synthetic effect',
  { timeout: 20000 },
  async () => {
    const directory = await mkdtemp(
      join(tmpdir(), 'computer-operational-crash-'),
    );
    const children: FixtureProcess[] = [];
    try {
      const original = new FixtureProcess('crash-before-ready', directory);
      children.push(original);
      await original.stage('EFFECT_RECORDED');
      assert.equal((await original.stop()).signal, 'SIGKILL');
      const journal = await FileToolJournal.open(directory, identity);
      const interrupted = (await journal.all()).find(
        (row) => row.phase === 'executing',
      );
      assert.ok(interrupted);
      assert.equal(
        await readFile(join(directory, 'effects'), 'utf8'),
        'capture\n',
      );
      const locked = new FixtureProcess('recover', directory);
      children.push(locked);
      await locked.stage('FIXTURE_REFUSED');
      assert.equal((await locked.done).code, 1);
      assert.ok(
        (await stat(join(directory, identity + '.lock'))).isDirectory(),
      );
      assert.deepEqual(
        (await journal.all()).find(
          (row) => row.invocationId === interrupted.invocationId,
        ),
        interrupted,
      );
      // Explicit fixture-only recovery: the exact owned child has been reaped and
      // has no external resources. Production stale locks are never cleared here.
      await rmdir(join(directory, identity + '.lock'));
      const recovered = new FixtureProcess('recover', directory);
      children.push(recovered);
      await recovered.stage('RECOVERED_WITHOUT_REPLAY');
      assert.equal((await recovered.done).code, 0);
      assert.equal(
        await readFile(join(directory, 'effects'), 'utf8'),
        'capture\n',
      );
      const receipt = (
        await (await FileToolJournal.open(directory, identity)).all()
      ).find((row) => row.invocationId === interrupted.invocationId);
      assert.equal(receipt?.request, interrupted.request);
      assert.equal(receipt.result?.state, 'UNKNOWN');
      assert.equal(receipt.phase, 'done');
      assert.equal(
        decodeToolCall(receipt.request).invocationId,
        interrupted.invocationId,
      );
    } finally {
      const results = await Promise.allSettled(
        children.map((child) => child.stop()),
      );
      const allReaped = results.every(
        (result) => result.status === 'fulfilled' && result.value.reaped,
      );
      if (allReaped) await rm(directory, { recursive: true, force: true });
      assert.ok(
        allReaped,
        'Synthetic process cleanup failed; its private evidence was retained',
      );
    }
  },
);
