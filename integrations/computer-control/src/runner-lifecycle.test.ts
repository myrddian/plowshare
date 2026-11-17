import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { mkdtemp, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { ToolReceipt } from 'plowshare-client-ts';
import type { RelayReplies } from 'plowshare-client-ts/operations/relay';
import { toolDeploymentConfig } from 'plowshare-client-node/tools';
import { decodeToolResult, toolResultRequestId } from 'plowshare-client-ts';
import { nodeToolHost } from 'plowshare-client-node/tools';
import { decodeComputerHostConfig } from './config.ts';
import { computerDeclarations } from './provider.ts';
import {
  CatalogueRecoveryRequired,
  FileCatalogueIntents,
  runComputerHost,
} from './runner.ts';
import { CatalogueLeaseExpired } from './host-lifecycle.ts';
import { ConnectionFault } from 'plowshare-client-ts/binding/connection';
import { FakeClock } from './contracts.test-support.ts';
import {
  binding,
  call,
  CaptureBackend,
  client,
  MemoryJournal,
  record,
  RelayWire,
} from './provider.test-support.ts';

const identity = createHash('sha256')
  .update(toolDeploymentConfig(binding, computerDeclarations()))
  .digest('hex');
const originalId = '22222222-2222-2222-2222-222222222222';
const publisher =
  'sdk:' + createHash('sha256').update(binding.account).digest('hex');
const catalogueText = JSON.stringify({
  version: 'plowshare-tool-catalog/3',
  tools: computerDeclarations(),
});
const generation = '33333333-3333-3333-3333-333333333333';
type LogPage = RelayReplies['relay.log'];

/** A controlled Relay peer with retained positions, not another local tool executor. */
class LifecycleWire extends RelayWire {
  pageSize = 100;
  loseWithdrawal = false;
  mutatePage: ((page: LogPage) => LogPage) | undefined;
  onPublication:
    ((publication: Readonly<Record<string, unknown>>) => void) | undefined;
  onConsume: (() => void) | undefined;
  override send(source: string): void {
    const envelope = record(JSON.parse(source));
    const request = record(envelope['payload']);
    if (envelope['type'] === 'relay.log') {
      const topic = request['topic'];
      const after = request['after'];
      if (typeof topic !== 'string' || typeof after !== 'string')
        throw new Error('Invalid fixture log request');
      const rows = this.publications
        .map((value, index) => ({ value, position: String(index + 1) }))
        .filter((row) => row.value['topic'] === topic);
      const selected = rows
        .filter((row) => BigInt(row.position) > BigInt(after))
        .slice(0, this.pageSize);
      const events = selected.map(({ value, position }) => {
        const eventId = value['requestId'],
          text = value['text'],
          occurredAt = value['occurredAt'];
        if (
          typeof eventId !== 'string' ||
          typeof text !== 'string' ||
          typeof occurredAt !== 'string'
        )
          throw new Error('Invalid fixture retained publication');
        return {
          position,
          eventId,
          publisher,
          occurredAt,
          publishedAt: '2026-11-16T00:00:00.000Z',
          correlationId:
            typeof value['correlationId'] === 'string'
              ? value['correlationId']
              : null,
          causationId:
            typeof value['parentEventId'] === 'string'
              ? value['parentEventId']
              : null,
          causation: null,
          payload: {
            kind: 'TEXT' as const,
            text,
            schedule: null,
            emits: null,
            fireAt: null,
          },
        };
      });
      const page: LogPage = {
        scope: { project: binding.project, system: false },
        topic: {
          name: topic,
          generation,
          kind: 'TEXT',
          retentionSeconds: '345600',
          maxRecords: null,
          through: rows.at(-1)?.position ?? '0',
          expiredThrough: '0',
        },
        after,
        next: events.at(-1)?.position ?? after,
        gapThrough: null,
        events,
        subscribers: [],
        branches: [],
      };
      this.listener?.({
        data: JSON.stringify({
          id: envelope['id'],
          type: 'relay.log',
          protocol_version: 'plowshare-v1',
          payload: { code: 'OK', payload: this.mutatePage?.(page) ?? page },
        }),
      });
      return;
    }
    if (envelope['type'] === 'relay.publish') {
      const text = request['text'];
      if (typeof text === 'string' && this.loseWithdrawal) {
        const body = record(JSON.parse(text));
        if (Array.isArray(body['tools']) && body['tools'].length === 0) {
          this.publications.push(request);
          this.close();
          return;
        }
      }
      super.send(source);
      this.onPublication?.(request);
      return;
    }
    super.send(source);
    if (envelope['type'] === 'relay.consume') this.onConsume?.();
  }
}

function publication(requestId = originalId, text = catalogueText) {
  return {
    project: binding.project,
    topic: `tool.${binding.provider}.catalog`,
    requestId,
    occurredAt: '2026-11-16T00:00:00.000Z',
    text,
  };
}
function config(directory: string) {
  return decodeComputerHostConfig({
    version: 1,
    origin: 'https://fixture.invalid',
    binding,
    credential: { kind: 'saved', directory },
    journalDirectory: directory,
    catalogue: { leaseSeconds: 300, renewalMs: 30_000 },
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
async function pending(directory: string): Promise<void> {
  await new FileCatalogueIntents(directory, identity).save({
    version: 1,
    identity,
    requestId: originalId,
    kind: 'publish',
    state: 'pending',
  });
}
async function noActiveMarker(directory: string): Promise<boolean> {
  return !(await readdir(directory)).some((name) => name.endsWith('.active'));
}
async function microtasks(): Promise<void> {
  for (let turn = 0; turn < 30; turn++) await Promise.resolve();
}

await test('original catalogue UUID is positively reconciled once before fresh metadata, never resent', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-reconcile-'));
  const wire = new LifecycleWire([publication()]);
  const connection = client(wire),
    clock = new FakeClock();
  try {
    await pending(directory);
    await runComputerHost({
      config: config(directory),
      connection,
      clock,
      currentBearer: async () => 'fixture',
      signal: new AbortController().signal,
      journal: new MemoryJournal(identity),
      backends: [{ backend: new CaptureBackend(clock), close: async () => {} }],
      maxPasses: 1,
    });
    assert.equal(
      wire.publications.filter((row) => row['requestId'] === originalId).length,
      1,
    );
    assert.equal(wire.publications.length, 3);
    assert.ok(await noActiveMarker(directory));
    assert.equal(clock.pendingTimers(), 0);
  } finally {
    connection.close();
    await rm(directory, { recursive: true, force: true });
  }
});

const malformed: readonly [string, (page: LogPage) => LogPage][] = [
  [
    'scope',
    (page) => ({ ...page, scope: { project: 'foreign', system: false } }),
  ],
  [
    'topic',
    (page) => ({ ...page, topic: { ...page.topic, name: 'foreign.catalog' } }),
  ],
  ['cursor', (page) => ({ ...page, after: '4' })],
  [
    'correlation',
    (page) => ({
      ...page,
      events: page.events.map((event) => ({
        ...event,
        correlationId: originalId,
      })),
    }),
  ],
  [
    'conflicting text',
    (page) => ({
      ...page,
      events: page.events.map((event) => ({
        ...event,
        payload: { ...event.payload, text: '{}' },
      })),
    }),
  ],
  [
    'duplicate UUID',
    (page) => ({
      ...page,
      topic: { ...page.topic, through: '2' },
      next: '2',
      events: [
        ...page.events,
        ...page.events.map((event) => ({
          ...event,
          position: '2',
          payload: { ...event.payload, text: '{}' },
        })),
      ],
    }),
  ],
  [
    'oversized page',
    (page) => {
      const first = page.events[0];
      if (first === undefined) throw new Error('Expected fixture event');
      return {
        ...page,
        topic: { ...page.topic, through: '101' },
        next: '101',
        events: Array.from({ length: 101 }, (_, index) => ({
          ...first,
          position: String(index + 1),
        })),
      };
    },
  ],
];
for (const [name, mutate] of malformed) {
  await test(`catalogue reconciliation refuses ${name} before any fresh publication`, async () => {
    const directory = await mkdtemp(
      join(tmpdir(), 'computer-reconcile-invalid-'),
    );
    const wire = new LifecycleWire([publication()]);
    wire.mutatePage = mutate;
    const connection = client(wire),
      clock = new FakeClock();
    try {
      await pending(directory);
      await assert.rejects(
        runComputerHost({
          config: config(directory),
          connection,
          clock,
          currentBearer: async () => 'fixture',
          signal: new AbortController().signal,
          journal: new MemoryJournal(identity),
          backends: [
            { backend: new CaptureBackend(clock), close: async () => {} },
          ],
          maxPasses: 1,
        }),
        (error: unknown) =>
          error instanceof CatalogueRecoveryRequired ||
          error instanceof ConnectionFault,
      );
      assert.equal(wire.publications.length, 1);
      assert.equal(
        (await new FileCatalogueIntents(directory, identity).load())?.state,
        'pending',
      );
      assert.ok(await noActiveMarker(directory));
    } finally {
      connection.close();
      await rm(directory, { recursive: true, force: true });
    }
  });
}

await test('missing or expired original catalogue evidence never authorizes resend', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-reconcile-absent-'));
  const wire = new LifecycleWire();
  wire.mutatePage = (page) => ({
    ...page,
    next: '1',
    gapThrough: '1',
    topic: { ...page.topic, through: '1', expiredThrough: '1' },
  });
  const connection = client(wire),
    clock = new FakeClock();
  try {
    await pending(directory);
    await assert.rejects(
      runComputerHost({
        config: config(directory),
        connection,
        clock,
        currentBearer: async () => 'fixture',
        signal: new AbortController().signal,
        journal: new MemoryJournal(identity),
        backends: [
          { backend: new CaptureBackend(clock), close: async () => {} },
        ],
        maxPasses: 1,
      }),
      CatalogueRecoveryRequired,
    );
    assert.equal(wire.publications.length, 0);
    assert.equal(
      (await new FileCatalogueIntents(directory, identity).load())?.requestId,
      originalId,
    );
  } finally {
    connection.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('a topic generation change during pagination cannot confirm the original catalogue', async () => {
  const directory = await mkdtemp(
    join(tmpdir(), 'computer-reconcile-generation-'),
  );
  const wire = new LifecycleWire([
    publication('44444444-4444-4444-4444-444444444444'),
    publication(),
  ]);
  wire.pageSize = 1;
  wire.mutatePage = (page) =>
    page.after === '0'
      ? page
      : {
          ...page,
          topic: {
            ...page.topic,
            generation: '55555555-5555-5555-5555-555555555555',
          },
        };
  const connection = client(wire),
    clock = new FakeClock();
  try {
    await pending(directory);
    await assert.rejects(
      runComputerHost({
        config: config(directory),
        connection,
        clock,
        currentBearer: async () => 'fixture',
        signal: new AbortController().signal,
        journal: new MemoryJournal(identity),
        backends: [
          { backend: new CaptureBackend(clock), close: async () => {} },
        ],
        maxPasses: 1,
      }),
      CatalogueRecoveryRequired,
    );
    assert.equal(wire.publications.length, 2);
  } finally {
    connection.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('unresolved PUBLISHING result blocks availability until its original receipt is retained', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-result-reconcile-'));
  const clock = new FakeClock(),
    journal = new MemoryJournal(identity);
  const offered = call(clock, 'open', { targetId: 'fixture-target' });
  const receipt: ToolReceipt = {
    invocationId: offered.invocationId,
    request: JSON.stringify(offered),
    phase: 'publishing',
    result: { state: 'UNKNOWN', text: 'Retained original result' },
    occurredAt: new Date(clock.wallNow()).toISOString(),
  };
  journal.rows.set(receipt.invocationId, receipt);
  const absent = new LifecycleWire(),
    first = client(absent);
  let ready = false;
  try {
    await assert.rejects(
      runComputerHost({
        config: config(directory),
        connection: first,
        clock,
        currentBearer: async () => 'fixture',
        signal: new AbortController().signal,
        journal,
        backends: [
          { backend: new CaptureBackend(clock), close: async () => {} },
        ],
        maxPasses: 1,
        onReady: () => {
          ready = true;
        },
      }),
      CatalogueRecoveryRequired,
    );
    assert.equal(ready, false);
    assert.equal(absent.publications.length, 0);
    assert.equal(journal.rows.get(receipt.invocationId)?.phase, 'publishing');
    const normalized = decodeToolResult(
      receipt.result,
      offered,
      binding.account,
    );
    if (normalized.content === undefined)
      assert.fail('Expected negotiated content result');
    const retained = new LifecycleWire([
      {
        project: binding.project,
        topic: `tool.${binding.provider}.${offered.tool}.result`,
        requestId: await toolResultRequestId(
          nodeToolHost,
          offered.invocationId,
        ),
        occurredAt: receipt.occurredAt,
        correlationId: offered.invocationId,
        parentEventId: offered.invocationId,
        text: JSON.stringify({
          schema: 'plowshare-tool/3',
          invocationId: offered.invocationId,
          project: offered.project,
          provider: offered.provider,
          tool: offered.tool,
          state: normalized.state,
          content: normalized.content,
        }),
      },
    ]);
    const recovered = client(retained);
    try {
      await runComputerHost({
        config: config(directory),
        connection: recovered,
        clock,
        currentBearer: async () => 'fixture',
        signal: new AbortController().signal,
        journal,
        backends: [
          { backend: new CaptureBackend(clock), close: async () => {} },
        ],
        maxPasses: 1,
      });
      assert.equal(journal.rows.get(receipt.invocationId)?.phase, 'done');
      assert.equal(
        retained.publications.filter(
          (row) =>
            row['topic'] === `tool.${binding.provider}.${offered.tool}.result`,
        ).length,
        1,
      );
    } finally {
      recovered.close();
    }
  } finally {
    first.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('physically quiet lost withdrawal leaves original metadata recoverable on an explicit restart', async () => {
  const directory = await mkdtemp(
    join(tmpdir(), 'computer-withdraw-recovery-'),
  );
  const clock = new FakeClock(),
    journal = new MemoryJournal(identity);
  const lost = new LifecycleWire();
  lost.loseWithdrawal = true;
  const first = client(lost);
  let closed = 0;
  try {
    await assert.rejects(
      runComputerHost({
        config: config(directory),
        connection: first,
        clock,
        currentBearer: async () => 'fixture',
        signal: new AbortController().signal,
        journal,
        backends: [
          {
            backend: new CaptureBackend(clock),
            close: async () => {
              closed++;
            },
          },
        ],
        maxPasses: 1,
      }),
      /metadata requires reconciliation/,
    );
    assert.equal(closed, 1);
    assert.ok(await noActiveMarker(directory));
    const intent = await new FileCatalogueIntents(directory, identity).load();
    assert.ok(intent);
    assert.equal(intent.kind, 'withdraw');
    assert.equal(intent.state, 'pending');
    const resumed = new LifecycleWire(lost.publications),
      second = client(resumed);
    try {
      await runComputerHost({
        config: config(directory),
        connection: second,
        clock,
        currentBearer: async () => 'fixture',
        signal: new AbortController().signal,
        journal,
        backends: [
          {
            backend: new CaptureBackend(clock),
            close: async () => {
              closed++;
            },
          },
        ],
        maxPasses: 1,
      });
      assert.equal(closed, 2);
      assert.equal(
        resumed.publications.filter(
          (row) => row['requestId'] === intent.requestId,
        ).length,
        1,
      );
      assert.ok(await noActiveMarker(directory));
    } finally {
      second.close();
    }
  } finally {
    first.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('a timed-out backend close stays fenced after its original close eventually settles', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-close-timeout-'));
  const clock = new FakeClock(),
    connection = client(new LifecycleWire());
  let began = () => {};
  const closing = new Promise<void>((resolve) => {
    began = resolve;
  });
  let release = () => {};
  const physical = new Promise<void>((resolve) => {
    release = resolve;
  });
  let closes = 0;
  try {
    const running = runComputerHost({
      config: config(directory),
      connection,
      clock,
      currentBearer: async () => 'fixture',
      signal: new AbortController().signal,
      journal: new MemoryJournal(identity),
      backends: [
        {
          backend: new CaptureBackend(clock),
          close: () => {
            closes++;
            began();
            return physical;
          },
        },
      ],
      maxPasses: 1,
    });
    const rejected = assert.rejects(running, /cleanup is uncertain/);
    await closing;
    clock.advance(2001);
    await rejected;
    assert.equal(closes, 1);
    assert.equal(await noActiveMarker(directory), false);
    release();
    await microtasks();
    assert.equal(await noActiveMarker(directory), false);
    assert.equal(closes, 1);
    await assert.rejects(
      runComputerHost({
        config: config(directory),
        connection,
        clock,
        currentBearer: async () => 'fixture',
        signal: new AbortController().signal,
        journal: new MemoryJournal(identity),
        backends: [
          {
            backend: new CaptureBackend(clock),
            close: async () => {
              closes++;
            },
          },
        ],
        maxPasses: 1,
      }),
      /prior computer host/,
    );
    assert.equal(closes, 1);
  } finally {
    release();
    connection.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('late monotonic lease expiry refuses the next serving pass before SDK consumption', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-lease-expiry-'));
  const clock = new FakeClock(),
    wire = new LifecycleWire(),
    connection = client(wire);
  let consumed = 0;
  wire.onConsume = () => {
    consumed++;
  };
  try {
    await assert.rejects(
      runComputerHost({
        config: config(directory),
        connection,
        clock,
        currentBearer: async () => 'fixture',
        signal: new AbortController().signal,
        journal: new MemoryJournal(identity),
        backends: [
          { backend: new CaptureBackend(clock), close: async () => {} },
        ],
        maxPasses: 1,
        onReady: () => {
          clock.monotonic = 300001;
        },
      }),
      CatalogueLeaseExpired,
    );
    assert.equal(consumed, 0);
    assert.ok(await noActiveMarker(directory));
    assert.equal(clock.pendingTimers(), 0);
  } finally {
    connection.close();
    await rm(directory, { recursive: true, force: true });
  }
});

for (const stage of ['journal', 'intents'] as const) {
  await test(`uncooperative startup ${stage} is bounded and late construction cannot serve`, async () => {
    const directory = await mkdtemp(join(tmpdir(), 'computer-startup-owned-'));
    const clock = new FakeClock(),
      wire = new LifecycleWire(),
      connection = client(wire);
    let entered = () => {};
    const reading = new Promise<void>((resolve) => {
      entered = resolve;
    });
    let release = () => {};
    const delayed = new Promise<void>((resolve) => {
      release = resolve;
    });
    let closeBegan = () => {};
    const closing = new Promise<void>((resolve) => {
      closeBegan = resolve;
    });
    let closes = 0,
      ready = false,
      saves = 0;
    const journal = new MemoryJournal(identity);
    const cfg = config(directory);
    try {
      const running = runComputerHost({
        config: decodeComputerHostConfig({
          ...cfg,
          budgets: { ...cfg.budgets, startupMs: 1000, cleanupMs: 1000 },
        }),
        connection,
        clock,
        currentBearer: async () => 'fixture',
        signal: new AbortController().signal,
        journal:
          stage === 'journal'
            ? {
                identity,
                all: async () => {
                  entered();
                  await delayed;
                  return [];
                },
                save: async () => {
                  saves++;
                },
              }
            : journal,
        ...(stage === 'intents'
          ? {
              intents: {
                load: async () => {
                  entered();
                  await delayed;
                  return null;
                },
                save: async () => {
                  saves++;
                },
              },
            }
          : {}),
        backends: [
          {
            backend: new CaptureBackend(clock),
            close: async () => {
              closes++;
              closeBegan();
            },
          },
        ],
        maxPasses: 1,
        onReady: () => {
          ready = true;
        },
      });
      const rejected = assert.rejects(running, /cleanup is uncertain/);
      await reading;
      clock.advance(1001);
      // Every already-owned backend gets cancellation time while the original
      // initialization promise is still blocked; no fresh cleanup budget exists.
      await closing;
      assert.equal(closes, 1);
      clock.advance(1001);
      await rejected;
      assert.equal(await noActiveMarker(directory), false);
      release();
      await microtasks();
      assert.equal(ready, false);
      assert.equal(wire.publications.length, 0);
      assert.equal(saves, 0);
      assert.equal(closes, 1);
      assert.equal(await noActiveMarker(directory), false);
      assert.equal(clock.pendingTimers(), 0);
    } finally {
      release();
      connection.close();
      await rm(directory, { recursive: true, force: true });
    }
  });
}
