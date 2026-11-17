import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { open } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { localLock } from 'plowshare-client-node/connections';
import {
  FileToolJournal,
  toolDeploymentConfig,
} from 'plowshare-client-node/tools';
import { decodeToolCall } from 'plowshare-client-ts';
import { FakeClock, target } from './contracts.test-support.ts';
import type { ComputerBackend } from './contracts.ts';
import { decodeCloseArguments } from './contracts.ts';
import { OperationDeadline } from './deadline.ts';
import { computerDeclarations, createComputerProvider } from './provider.ts';
import {
  admissions,
  binding,
  call,
  client,
  metadata,
  record,
  RelayWire,
} from './provider.test-support.ts';

/** Actual process-crash fixture, with a file counter instead of a physical capture. */
export async function operationalProcessFixture(
  mode: string,
  directory: string,
): Promise<void> {
  assert.ok(['crash-before-ready', 'recover'].includes(mode));
  assert.ok(isAbsolute(directory));
  const watchdog = setTimeout(() => process.exit(3), 4000);
  const identity = createHash('sha256')
    .update(toolDeploymentConfig(binding, computerDeclarations()))
    .digest('hex');
  const clock = new FakeClock();
  const wire = new RelayWire(),
    connection = client(wire);
  const backend: ComputerBackend = {
    targets: async () => [target],
    capture: async () => {
      // A single durable synthetic effect, completed before asking the parent to
      // kill this process. No helper, browser, HTTP or native input is involved.
      const counter = await open(join(directory, 'effects'), 'a', 0o600);
      try {
        await counter.writeFile('capture\n');
        await counter.sync();
      } finally {
        await counter.close();
      }
      assert.equal(
        mode,
        'crash-before-ready',
        'Recovery must not run the backend',
      );
      process.send?.({ stage: 'EFFECT_RECORDED' });
      return await new Promise<never>(() => {});
    },
  };
  try {
    await localLock(
      directory,
      identity,
      async () => {
        const journal = await FileToolJournal.open(directory, identity);
        const provider = await createComputerProvider({
          connection,
          binding,
          journal,
          backends: [backend],
          generation:
            mode === 'recover' ? 'recovered-generation' : 'original-generation',
          origin: 'https://fixture.invalid',
          currentBearer: async () => 'fixture',
          clock,
          http: admissions(),
        });
        try {
          if (mode === 'crash-before-ready') {
            const opening = call(clock, 'open', { targetId: target.targetId });
            wire.calls.push(opening);
            assert.equal(await provider.provider.poll(), 1);
            const opened = (await journal.all()).find(
              (row) => row.invocationId === opening.invocationId,
            );
            assert.ok(opened?.result);
            const row = record(metadata(opened.result)['session']);
            const session = decodeCloseArguments({
              sessionId: row['sessionId'],
              generation: row['generation'],
            });
            wire.calls.push(
              call(clock, 'observe', {
                sessionId: session['sessionId'],
                generation: session['generation'],
                surfaceId: 'viewport',
              }),
            );
            await provider.provider.poll();
            assert.fail('Parent must kill the original pending worker');
          } else {
            const interrupted = (await journal.all()).find(
              (row) => row.phase === 'executing',
            );
            assert.ok(interrupted);
            const original = decodeToolCall(interrupted.request);
            await provider.provider.reconcile();
            await provider.provider.poll();
            const recovered = (await journal.all()).find(
              (row) => row.invocationId === original.invocationId,
            );
            assert.equal(recovered?.phase, 'done');
            assert.equal(recovered.result?.state, 'UNKNOWN');
            assert.equal(recovered.request, interrupted.request);
            assert.equal(wire.publications.length, 1);
            // Redelivery of the original request acknowledges retained work only.
            wire.calls.push(original);
            assert.equal(await provider.provider.poll(), 1);
            assert.equal(wire.publications.length, 1);
            process.send?.({ stage: 'RECOVERED_WITHOUT_REPLAY' });
          }
        } finally {
          provider.stop();
          const cleanup = new OperationDeadline(1000);
          try {
            await provider.drain(cleanup);
          } finally {
            cleanup.dispose();
          }
        }
      },
      AbortSignal.timeout(1000),
    );
  } finally {
    clearTimeout(watchdog);
    connection.close();
  }
}

const entry = process.argv[1];
if (entry !== undefined && import.meta.url === pathToFileURL(entry).href) {
  try {
    assert.equal(process.argv.length, 4);
    await operationalProcessFixture(
      process.argv[2] ?? '',
      process.argv[3] ?? '',
    );
  } catch {
    process.send?.({ stage: 'FIXTURE_REFUSED' });
    process.exitCode = 1;
  }
}
