import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type {
  ConnectionRegistry,
  NamedConnection,
} from 'plowshare-client-node/connections';
import { LoginRequired } from 'plowshare-client-node/credentials';
import type { Session } from 'plowshare-client-node/session';
import type { ImageAdmissionReceipt } from 'plowshare-client-ts';
import type { RequestSignal } from 'plowshare-client-ts/binding/acting';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import {
  AcceptanceError,
  AcceptanceCheckpoints,
  acceptanceFailureCode,
  acceptanceRoundPassed,
  decodeAcceptanceConfig,
  runAcceptance,
} from './acceptance.ts';
import type {
  AcceptanceConfig,
  AcceptanceEvidence,
  AcceptanceRound,
} from './acceptance.ts';
import { FakeClock } from './contracts.test-support.ts';
import { DeadlineExceeded, OperationDeadline } from './deadline.ts';

const selected: NamedConnection = {
  key: 'fixture-key',
  name: 'Fixture',
  server: 'https://fixture.invalid',
  account: 'fixture-owner',
  reconnect: false,
};
function config(root: string): AcceptanceConfig {
  return decodeAcceptanceConfig({
    connectionName: selected.name,
    project: 'fresh-fixture',
    model: 'fixture-vision',
    browserExecutable: join(root, 'browser'),
    workDirectory: join(root, 'workspace'),
    journalDirectory: join(root, 'journal'),
    evidenceFile: join(root, 'evidence.json'),
  });
}
function registry(): ConnectionRegistry {
  return {
    load: async () => ({ version: 1, connections: [selected] }),
    put: async () => {
      throw new Error('Registry mutation not expected');
    },
    select: async () => {
      throw new Error('Registry mutation not expected');
    },
    rename: async () => {
      throw new Error('Registry mutation not expected');
    },
    remove: async () => {
      throw new Error('Registry mutation not expected');
    },
  };
}
function session(
  handle: string,
  actual = handle,
): {
  readonly session: Session;
  readonly operations: string[];
  closed: number;
} {
  const state = { operations: [] as string[], closed: 0 };
  const opened: Session = {
    session: 'fixture-session',
    handle,
    async ask(type) {
      state.operations.push(type);
      if (type !== 'admin.status') throw new Error('Unexpected mutation');
      return { code: 'OK', payload: { handle: actual, serverAdmin: false } };
    },
    async root() {
      state.operations.push('root');
      throw new Error('Unexpected root');
    },
    runSession: () => 'fixture-session',
    bearer: async () => 'private-fixture-token',
    release: async () => {},
    close() {
      state.closed++;
    },
  };
  return {
    session: opened,
    get operations() {
      return state.operations;
    },
    get closed() {
      return state.closed;
    },
  };
}
function round(): AcceptanceRound {
  const sha256 = 'a'.repeat(64);
  const admission: ImageAdmissionReceipt = {
    request: {
      invocation: '00000000-0000-0000-0000-000000000001',
      requestId: '00000000-0000-0000-0000-000000000002',
      ordinal: 0,
    },
    project: 'fixture-project',
    owner: selected.account,
    producer: selected.account,
    imageId: 'img_' + sha256.slice(0, 32),
    mimeType: 'image/png',
    width: 640,
    height: 240,
    byteLength: 4567,
    sha256,
    admittedAt: '2026-11-17T00:00:00Z',
  };
  return {
    expected: '12345678',
    answer: '12345678',
    answered: true,
    ending: 'ANSWERED',
    job: 'fixture-job',
    conversation: 'fixture-conversation',
    project: admission.project,
    account: selected.account,
    provider: 'fixture-provider',
    jobLinked: true,
    trajectoryComplete: true,
    procComplete: true,
    images: [
      {
        invocation: admission.request.invocation,
        retainedInvocation: admission.request.invocation,
        call: 'fixture-call',
        project: admission.project,
        provider: 'fixture-provider',
        account: selected.account,
        trajectoryCallLinked: true,
        state: 'COMPLETED',
        use: 'OBSERVATION',
        admission,
        capturedSha256: sha256,
        capturedMimeType: 'image/png',
        capturedWidth: 640,
        capturedHeight: 240,
        capturedByteLength: 4567,
        artifactState: 'COMMITTED',
        artifactReceipt: admission,
      },
    ],
    wireModels: ['fixture-vision'],
    procModels: ['fixture-vision'],
    routeReadFailures: [],
  };
}

await test('acceptance config accepts only explicit bounded private paths and named saved connection', () => {
  const valid = config('/private/tmp/computer-acceptance-config');
  assert.equal(valid.budgetMs, 180_000);
  for (const delta of [
    { endpoint: 'https://other.invalid' },
    { token: 'secret' },
    { account: 'other' },
    { budgetMs: 0 },
    { budgetMs: Infinity },
    { budgetMs: 300_001 },
    { browserExecutable: 'browser' },
    { workDirectory: valid.journalDirectory },
    { evidenceFile: join(valid.workDirectory, 'evidence.json') },
    { evidenceFile: join(valid.workDirectory, '..evidence') },
    { project: 'PERSONAL:private' },
    { project: 'client:claimed' },
    { connectionName: '' },
    { model: undefined },
  ]) {
    assert.throws(
      () => decodeAcceptanceConfig({ ...valid, ...delta }),
      AcceptanceError,
    );
  }
  assert.equal(
    decodeAcceptanceConfig({ ...valid, budgetMs: 300_000 }).budgetMs,
    300_000,
  );
  assert.equal(
    decodeAcceptanceConfig({ ...valid, project: 'global' }).project,
    'global',
  );
});

await test('login refusal performs no filesystem/root/scope effects and has no account fallback', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-acceptance-login-'));
  let attempts = 0;
  try {
    await assert.rejects(
      runAcceptance(config(directory), {
        registry: registry(),
        authenticate: async (connection) => {
          attempts++;
          assert.deepEqual(connection, selected);
          throw new LoginRequired();
        },
      }),
      LoginRequired,
    );
    assert.equal(attempts, 1);
    assert.deepEqual(await readdir(directory), []);
  } finally {
    await rm(directory, { recursive: true });
  }
});

await test('actual same-socket identity overrides cached session handle before state changes', async () => {
  const directory = await mkdtemp(
    join(tmpdir(), 'computer-acceptance-identity-'),
  );
  const opened = session(selected.account, 'another-account');
  try {
    await assert.rejects(
      runAcceptance(config(directory), {
        registry: registry(),
        authenticate: async () => opened.session,
      }),
      (error: unknown) => acceptanceFailureCode(error) === 'IDENTITY_MISMATCH',
    );
    assert.deepEqual(opened.operations, ['admin.status']);
    assert.equal(opened.closed, 1);
    assert.deepEqual(await readdir(directory), []);
  } finally {
    await rm(directory, { recursive: true });
  }
});

await test('noncooperative late login closes its returned socket and does not mutate state', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-acceptance-late-'));
  const clock = new FakeClock(),
    opened = session(selected.account);
  let resolveLogin: ((value: Session) => void) | undefined;
  try {
    const pending = runAcceptance(
      { ...config(directory), budgetMs: 1000 },
      {
        clock,
        registry: registry(),
        authenticate: () =>
          new Promise<Session>((resolve) => {
            resolveLogin = resolve;
          }),
      },
    );
    for (let step = 0; step < 20 && resolveLogin === undefined; step++)
      await Promise.resolve();
    assert.ok(resolveLogin);
    const rejected = assert.rejects(
      pending,
      (error: unknown) => acceptanceFailureCode(error) === 'DEADLINE_EXPIRED',
    );
    clock.advance(1001);
    await rejected;
    resolveLogin(opened.session);
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(opened.closed, 1);
    assert.deepEqual(opened.operations, []);
    assert.deepEqual(await readdir(directory), []);
    assert.equal(clock.pendingTimers(), 0);
  } finally {
    await rm(directory, { recursive: true });
  }
});

await test('existing workspace is refused before Session.root and evidence remains private', async () => {
  const directory = await mkdtemp(
    join(tmpdir(), 'computer-acceptance-workspace-'),
  );
  const opened = session(selected.account),
    cfg = config(directory);
  try {
    await writeFile(cfg.workDirectory, 'existing');
    await assert.rejects(
      runAcceptance(cfg, {
        registry: registry(),
        authenticate: async () => opened.session,
      }),
      (error: unknown) =>
        acceptanceFailureCode(error) === 'WORKSPACE_NOT_FRESH',
    );
    assert.deepEqual(opened.operations, ['admin.status']);
    assert.equal(opened.closed, 1);
  } finally {
    await rm(directory, { recursive: true });
  }
});

await test('bounded registry resolution rejects an expired selection without starting login', async () => {
  const directory = await mkdtemp(
    join(tmpdir(), 'computer-acceptance-registry-'),
  );
  const clock = new FakeClock();
  let logins = 0;
  try {
    const pending = runAcceptance(
      { ...config(directory), budgetMs: 1000 },
      {
        clock,
        registry: {
          ...registry(),
          load: async () => {
            clock.monotonic = 1001;
            return { version: 1, connections: [selected] };
          },
        },
        authenticate: async () => {
          logins++;
          throw new LoginRequired();
        },
      },
    );
    await assert.rejects(
      pending,
      (error: unknown) => acceptanceFailureCode(error) === 'DEADLINE_EXPIRED',
    );
    assert.equal(logins, 0);
    assert.deepEqual(await readdir(directory), []);
  } finally {
    await rm(directory, { recursive: true });
  }
});

await test('semantic acceptance requires final answer, exact actual routes, and linked retained PNG evidence', () => {
  const valid = round();
  // Pure predicate fixture: this is not a live model result or transport reply.
  assert.ok(acceptanceRoundPassed(valid, 'fixture-vision'));
  assert.ok(
    acceptanceRoundPassed({ ...valid, wireModels: [] }, 'fixture-vision'),
  ); // Older trajectory, complete PROC routes.
  for (const delta of [
    { images: [] },
    { answered: false },
    { answer: 'The answer is 12345678' },
    { ending: 'EXHAUSTED' },
    { jobLinked: false },
    { trajectoryComplete: false },
    { procComplete: false },
    { procModels: [] },
    { wireModels: ['fixture-vision', 'other'] },
    { procModels: ['other'] },
    { answer: '87654321' },
  ]) {
    assert.equal(
      acceptanceRoundPassed({ ...valid, ...delta }, 'fixture-vision'),
      false,
    );
  }
  const image = valid.images[0];
  assert.ok(image);
  for (const delta of [
    { capturedSha256: 'b'.repeat(64) },
    { capturedWidth: 1 },
    { capturedMimeType: 'image/jpeg' },
    { artifactState: 'UNAVAILABLE' },
    { artifactReceipt: null },
    { retainedInvocation: 'other' },
    { trajectoryCallLinked: false },
    { project: 'other' },
    { provider: 'other' },
    { account: 'other' },
    { use: 'OUTPUT' },
    { state: 'UNKNOWN' },
    { artifactReceipt: { ...image.admission, producer: 'other' } },
    {
      artifactReceipt: { ...image.admission, mimeType: 'image/jpeg' as const },
    },
    {
      artifactReceipt: {
        ...image.admission,
        request: { ...image.admission.request, requestId: 'other' },
      },
    },
  ]) {
    assert.equal(
      acceptanceRoundPassed(
        { ...valid, images: [{ ...image, ...delta }] },
        'fixture-vision',
      ),
      false,
    );
  }
});

await test('diagnostic failure codes never echo credential-like exception messages', () => {
  assert.equal(
    acceptanceFailureCode(new Error('Authorization: Bearer private-token')),
    'FAILED',
  );
  assert.equal(acceptanceFailureCode(new LoginRequired()), 'LOGIN_REQUIRED');
  assert.equal(
    acceptanceFailureCode(
      new AcceptanceError('UNCERTAIN_EFFECT', {
        cause: new Error('private-token'),
      }),
    ),
    'UNCERTAIN_EFFECT',
  );
});

await test('silent identity read expires its own 2s queue/reply budget before larger overall budget', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-acceptance-ask-'));
  const clock = new FakeClock(),
    opened = session(selected.account);
  let reply: ((answer: Outcome) => void) | undefined;
  let requestSignal: RequestSignal | undefined;
  const wire: Session = {
    ...opened.session,
    async ask(type, _payload, options) {
      opened.operations.push(type);
      if (type !== 'admin.status') throw new Error('Unexpected mutation');
      requestSignal = options?.signal;
      return new Promise((resolve) => {
        reply = resolve;
      });
    },
  };
  try {
    const pending = runAcceptance(
      { ...config(directory), budgetMs: 10_000 },
      { clock, registry: registry(), authenticate: async () => wire },
    );
    for (let step = 0; step < 30 && reply === undefined; step++)
      await Promise.resolve();
    assert.ok(reply);
    assert.ok(requestSignal);
    const rejected = assert.rejects(
      pending,
      (error: unknown) => acceptanceFailureCode(error) === 'DEADLINE_EXPIRED',
    );
    clock.advance(2001);
    await rejected;
    assert.ok(requestSignal.aborted);
    reply({
      code: 'OK',
      payload: { handle: selected.account, serverAdmin: false },
    });
    await Promise.resolve();
    await Promise.resolve();
    assert.deepEqual(opened.operations, ['admin.status']);
    assert.deepEqual(await readdir(directory), []);
    assert.equal(opened.closed, 1);
    assert.equal(clock.pendingTimers(), 0);
  } finally {
    await rm(directory, { recursive: true });
  }
});

await test('late identity reply after monotonic cutoff cannot begin filesystem or root work', async () => {
  const directory = await mkdtemp(
    join(tmpdir(), 'computer-acceptance-late-ask-'),
  );
  const clock = new FakeClock(),
    opened = session(selected.account);
  const wire: Session = {
    ...opened.session,
    async ask(type) {
      opened.operations.push(type);
      clock.monotonic = 2001;
      return {
        code: 'OK',
        payload: { handle: selected.account, serverAdmin: false },
      };
    },
  };
  try {
    await assert.rejects(
      runAcceptance(
        { ...config(directory), budgetMs: 10_000 },
        { clock, registry: registry(), authenticate: async () => wire },
      ),
      (error: unknown) => acceptanceFailureCode(error) === 'DEADLINE_EXPIRED',
    );
    assert.deepEqual(opened.operations, ['admin.status']);
    assert.deepEqual(await readdir(directory), []);
    assert.equal(opened.closed, 1);
    assert.equal(clock.pendingTimers(), 0);
  } finally {
    await rm(directory, { recursive: true });
  }
});

await test('uncertain scope connect is not replayed and early cleanup disconnects exactly once', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-acceptance-scope-'));
  const opened = session(selected.account),
    cfg = config(directory);
  const wire: Session = {
    ...opened.session,
    async root(project, path) {
      opened.operations.push('root');
      return { current: { project, root: path, machine: 'fixture-machine' } };
    },
    async ask(type, payload) {
      opened.operations.push(type);
      if (type === 'admin.status')
        return {
          code: 'OK',
          payload: { handle: selected.account, serverAdmin: false },
        };
      if (payload === null || typeof payload !== 'object')
        throw new Error('Expected fixture request');
      if (
        type === 'agent.define' &&
        'name' in payload &&
        typeof payload.name === 'string'
      )
        return {
          code: 'CREATED',
          payload: {
            restartRequired: false,
            agent: {
              name: payload.name,
              tools: ['relay_tool_read'],
              calls: [],
              scopes: [],
              served: true,
              withheld: [],
              bot: false,
              description: 'Fixture',
              preferred: false,
              model: cfg.model,
              orchestrations: [],
              dynamic: true,
            },
          },
        };
      if (type === 'tool.scope.connect')
        throw new Error('Uncertain transmission');
      if (
        type === 'tool.scope.disconnect' &&
        'scope' in payload &&
        typeof payload.scope === 'string'
      )
        return {
          code: 'OK',
          payload: {
            project: cfg.project,
            scope: payload.scope,
            disconnected: true,
          },
        };
      throw new Error('Unexpected request');
    },
  };
  try {
    await assert.rejects(
      runAcceptance(cfg, {
        registry: registry(),
        authenticate: async () => wire,
      }),
    );
    assert.deepEqual(opened.operations, [
      'admin.status',
      'root',
      'agent.define',
      'tool.scope.connect',
      'tool.scope.disconnect',
    ]);
    const paths = await readdir(directory);
    assert.deepEqual(paths.sort(), ['evidence.json', 'workspace']);
    assert.equal(opened.closed, 1);
  } finally {
    await rm(directory, { recursive: true });
  }
});

function evidence(): AcceptanceEvidence {
  return {
    version: 1,
    connectionName: selected.name,
    selectedAccount: selected.account,
    requestedModel: 'fixture-vision',
    startedAt: '2026-11-17T00:00:00Z',
    finishedAt: null,
    state: 'RUNNING',
    project: null,
    agent: null,
    scope: null,
    provider: null,
    currentJob: null,
    currentConversation: null,
    stage: 'FIXTURE',
    rounds: [],
    catalogueIntents: [],
    failures: [],
    cleanup: {
      stopped: false,
      drained: false,
      browserClosed: false,
      scopeDisconnected: false,
      catalogueWithdrawn: false,
      fixtureClosed: false,
      workspaceRetained: true,
    },
  };
}

await test('evidence writer retains timed-out physical ownership and serializes immutable failure snapshot', async () => {
  const clock = new FakeClock(),
    record = evidence(),
    writes: AcceptanceEvidence[] = [];
  let finish: (() => void) | undefined,
    active = 0,
    maxActive = 0;
  const writer = new AcceptanceCheckpoints(
    '/private/tmp/unused-evidence.json',
    async (_file, snapshot) => {
      active++;
      maxActive = Math.max(maxActive, active);
      writes.push(snapshot);
      if (writes.length === 1)
        await new Promise<void>((resolve) => {
          finish = resolve;
        });
      active--;
    },
  );
  const firstBudget = new OperationDeadline(1000, clock);
  const first = writer.save(record, firstBudget);
  for (let step = 0; step < 10 && finish === undefined; step++)
    await Promise.resolve();
  assert.ok(finish);
  const expired = assert.rejects(first, DeadlineExceeded);
  clock.advance(1001);
  await expired;
  firstBudget.dispose();
  record.state = 'FAILED';
  record.failures.push('DEADLINE_EXPIRED');
  const finalBudget = new OperationDeadline(2000, clock),
    final = writer.save(record, finalBudget);
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(writes.length, 1);
  assert.equal(writes[0]?.state, 'RUNNING');
  assert.deepEqual(writes[0]?.failures, []);
  finish();
  await final;
  finalBudget.dispose();
  assert.equal(maxActive, 1);
  assert.equal(writes.at(-1)?.state, 'FAILED');
  assert.deepEqual(writes.at(-1)?.failures, ['DEADLINE_EXPIRED']);
  assert.equal(clock.pendingTimers(), 0);
});

await test('late provisional CHECKS_PASSED write never returns success or starts expired replacement', async () => {
  const clock = new FakeClock(),
    record = evidence(),
    states: string[] = [];
  record.state = 'CHECKS_PASSED';
  let finish: (() => void) | undefined,
    completed = false;
  const writer = new AcceptanceCheckpoints(
    '/private/tmp/unused-evidence.json',
    async (_file, snapshot) => {
      states.push(snapshot.state);
      await new Promise<void>((resolve) => {
        finish = resolve;
      });
    },
  );
  const firstBudget = new OperationDeadline(1000, clock);
  const first = writer.save(record, firstBudget).then(() => {
    completed = true;
  });
  for (let step = 0; step < 10 && finish === undefined; step++)
    await Promise.resolve();
  assert.ok(finish);
  const expired = assert.rejects(first, DeadlineExceeded);
  clock.advance(1001);
  await expired;
  firstBudget.dispose();
  record.state = 'FAILED';
  const finalBudget = new OperationDeadline(2000, clock),
    final = writer.save(record, finalBudget);
  const finalExpired = assert.rejects(final, DeadlineExceeded);
  clock.advance(2001);
  await finalExpired;
  finalBudget.dispose();
  finish();
  for (let step = 0; step < 10; step++) await Promise.resolve();
  assert.deepEqual(states, ['CHECKS_PASSED']);
  assert.equal(completed, false);
  assert.equal(clock.pendingTimers(), 0);
});
