import assert from 'node:assert/strict';
import test from 'node:test';
import { ComputerSessions, SessionError } from './sessions.ts';
import type { ComputerSession } from './sessions.ts';
import type { BrowserLease, BrowserObservation } from './browser-control.ts';
import { DeadlineExceeded, OperationDeadline } from './deadline.ts';
import { FakeClock, image, owner } from './contracts.test-support.ts';
import { BackendError } from './contracts.ts';

function fixture(maxImagesPerRun = 8) {
  const clock = new FakeClock();
  let next = 0;
  const sessions = new ComputerSessions({
    generation: 'generation-one',
    id: () => `id-${next++}`,
    clock,
    maxImagesPerRun,
  });
  return { clock, sessions };
}

await test('sessions bind account, project, run, provider and generation; target occupancy is exclusive', () => {
  const { sessions, clock } = fixture();
  const session = sessions.open(owner, 'target');
  assert.equal(sessions.require(owner, session), session);
  assert.throws(() => sessions.open(owner, 'target'), /BUSY/);
  for (const foreign of [
    { ...owner, account: 'foreign' },
    { ...owner, project: 'foreign' },
    { ...owner, run: 'foreign' },
    { ...owner, provider: 'foreign' },
  ])
    assert.throws(() => sessions.require(foreign, session), /NO_SESSION/);
  assert.throws(
    () => sessions.require(owner, { ...session, generation: 'generation-old' }),
    /NO_SESSION/,
  );
  clock.advance(60_000);
  assert.throws(() => sessions.require(owner, session), /NO_SESSION/);
  assert.equal(sessions.open(owner, 'target').targetId, 'target');
});

await test('late capture retains physical occupancy and never returns successful output or silently clears fence', async () => {
  const { sessions, clock } = fixture();
  const session = sessions.open(owner, 'target');
  const deadline = new OperationDeadline(1000, clock);
  let finish: ((value: string) => void) | undefined;
  const work = sessions.withSession(
    owner,
    session,
    deadline,
    () =>
      new Promise<string>((resolve) => {
        finish = resolve;
      }),
  );
  clock.advance(1001);
  assert.ok(deadline.signal.aborted);
  assert.throws(() => sessions.open(owner, 'target'), /BUSY/);
  assert.throws(() => sessions.close(owner, session), /FENCED/);
  assert.throws(() => sessions.releaseAfterQuiescence(owner, session), /BUSY/);
  const rejected = assert.rejects(work, DeadlineExceeded);
  if (finish === undefined) assert.fail('Capture did not start');
  finish('late pixels');
  await rejected;
  clock.advance(60_000);
  assert.throws(() => sessions.open(owner, 'target'), /BUSY/);
  assert.throws(() => sessions.require(owner, session), /FENCED/);
  sessions.releaseAfterQuiescence(owner, session);
  assert.equal(sessions.open(owner, 'target').targetId, 'target');
  deadline.dispose();
});

await test('idle expiry or close during capture never frees a physically occupied target', async () => {
  const { sessions, clock } = fixture();
  const session = sessions.open(owner, 'target');
  const deadline = new OperationDeadline(120_000, clock);
  let finish: (() => void) | undefined;
  const work = sessions.withSession(
    owner,
    session,
    deadline,
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
  );
  clock.advance(60_000);
  assert.throws(() => sessions.open(owner, 'target'), /BUSY/);
  assert.throws(() => sessions.releaseAfterQuiescence(owner, session), /BUSY/);
  const rejected = assert.rejects(work, /FENCED/);
  if (finish === undefined) assert.fail('Capture did not start');
  finish();
  await rejected;
  assert.throws(() => sessions.open(owner, 'target'), /BUSY/);
  sessions.releaseAfterQuiescence(owner, session);
  assert.equal(sessions.open(owner, 'target').targetId, 'target');
  deadline.dispose();
});

await test('explicit backend fence cannot be removed by idle expiry or ordinary close', () => {
  const { sessions, clock } = fixture();
  const session = sessions.open(owner, 'target');
  sessions.fence(owner, session);
  clock.advance(60_001);
  assert.throws(() => sessions.open(owner, 'target'), /BUSY/);
  assert.throws(() => sessions.close(owner, session), /FENCED/);
  sessions.releaseAfterQuiescence(owner, session);
  assert.equal(sessions.open(owner, 'target').targetId, 'target');
});

await test('a rejected helper promise with uncertain physical settlement retains target fence', async () => {
  const { sessions, clock } = fixture();
  const session = sessions.open(owner, 'target');
  const deadline = new OperationDeadline(1000, clock);
  await assert.rejects(
    sessions.withSession(owner, session, deadline, () =>
      Promise.reject(new BackendError('CAPTURE_FAILED', 'uncertain')),
    ),
    BackendError,
  );
  clock.advance(60_001);
  assert.throws(() => sessions.open(owner, 'target'), /BUSY/);
  assert.throws(() => sessions.close(owner, session), /FENCED/);
  deadline.dispose();
});

await test('frames reject old identities, changed layout and monotonic expiry without retaining bytes', () => {
  const { sessions, clock } = fixture();
  const session = sessions.open(owner, 'target');
  const frame = sessions.recordFrame(owner, session, 'viewport', image(clock));
  assert.equal(
    sessions.checkFrame(owner, session, frame.frameId, 'layout-one'),
    frame,
  );
  assert.ok(!('bytes' in frame));
  assert.throws(
    () => sessions.checkFrame(owner, session, frame.frameId, 'layout-two'),
    /STALE_OBSERVATION/,
  );
  const next = sessions.recordFrame(owner, session, 'viewport', image(clock));
  assert.throws(
    () => sessions.checkFrame(owner, session, frame.frameId, 'layout-one'),
    /STALE_OBSERVATION/,
  );
  clock.wall -= 3_600_000;
  clock.advance(5000);
  assert.throws(
    () => sessions.checkFrame(owner, session, next.frameId, 'layout-one'),
    /STALE_OBSERVATION/,
  );
});

await test('capture intent allowance survives session reopen and journal restoration never lowers count', () => {
  const { sessions } = fixture(2);
  const first = sessions.open(owner, 'target');
  sessions.reserveImage(owner);
  sessions.close(owner, first);
  const second = sessions.open(owner, 'target');
  sessions.restoreImageCount(owner, 0);
  sessions.reserveImage(owner);
  assert.throws(() => sessions.reserveImage(owner), /FRAME_LIMIT/);
  sessions.close(owner, second);
  const restarted = fixture(2).sessions;
  restarted.restoreImageCount(owner, 2);
  assert.throws(() => restarted.reserveImage(owner), /FRAME_LIMIT/);
  assert.throws(() => restarted.restoreImageCount(owner, -1));
});

await test('session configured bounds include consistent capture policy', () => {
  const clock = new FakeClock();
  const sessions = new ComputerSessions({
    generation: 'generation',
    id: () => 'id',
    clock,
    captureLimits: { maxDimension: 2000, maxBytes: 1024 },
  });
  const session = sessions.open(owner, 'target');
  const frame = sessions.recordFrame(owner, session, 'viewport', {
    ...image(clock),
    width: 2000,
  });
  assert.equal(frame.width, 2000);
  assert.throws(
    () => new ComputerSessions({ generation: 'g', id: () => 'i', idleMs: 0 }),
  );
  assert.throws(
    () =>
      new ComputerSessions({
        generation: 'g',
        id: () => 'i',
        maxSessions: Infinity,
      }),
  );
  assert.ok(new SessionError('BUSY') instanceof Error);
});

function browserObservation(
  clock: FakeClock,
  suffix = 'one',
): BrowserObservation {
  const guard = {
    surfaceId: 'tab-' + suffix,
    documentId: 'document-' + suffix,
    layoutGeneration: 'layout-' + suffix,
    focusGeneration: 'focus-' + suffix,
  };
  return {
    image: {
      ...image(clock),
      layoutGeneration: guard.layoutGeneration,
      transform: { a: 2, b: 0, c: 0, d: 2, e: 5, f: 10 },
    },
    state: {
      ...guard,
      readiness: 'ready',
      guardInstalled: true,
      url: 'https://fixture.invalid/',
      origin: 'https://fixture.invalid',
      requestedUrl: null,
      focusedElementId: 'element-' + suffix,
    },
    snapshot: {
      snapshotId: 'snapshot-' + suffix,
      rootElementId: null,
      guard,
      kind: 'dom-semantic',
      approximate: true,
      capturedAt: new Date(clock.wallNow()).toISOString(),
      truncated: false,
      nodes: [
        {
          elementId: 'element-' + suffix,
          parentElementId: null,
          role: 'textbox',
          name: 'Field',
          editable: true,
          focused: true,
          disabled: false,
          bounds: { x: 10, y: 10, width: 20, height: 10 },
        },
      ],
    },
  };
}
function bind(
  sessions: ComputerSessions,
  session: ComputerSession,
): BrowserLease {
  const lease = {
    leaseId: 'lease-' + session.sessionId,
    targetId: session.targetId,
    sessionId: session.sessionId,
    generation: session.generation,
  };
  sessions.bindBrowserLease(owner, session, lease);
  return lease;
}

await test('controlled session binding strictly owns backend lease and cannot be adopted by foreign identities', () => {
  const { sessions } = fixture(),
    session = sessions.openControlled(owner, 'target');
  assert.throws(() => sessions.browserLease(owner, session), /NO_LEASE/);
  const lease = {
    leaseId: 'lease',
    targetId: session.targetId,
    sessionId: session.sessionId,
    generation: session.generation,
  };
  for (const delta of [
    { targetId: 'foreign' },
    { sessionId: 'foreign' },
    { generation: 'foreign' },
  ])
    assert.throws(
      () => sessions.bindBrowserLease(owner, session, { ...lease, ...delta }),
      /NO_LEASE/,
    );
  sessions.bindBrowserLease(owner, session, lease);
  lease.leaseId = 'mutated';
  assert.equal(sessions.browserLease(owner, session).leaseId, 'lease');
  assert.throws(() => sessions.bindBrowserLease(owner, session, lease), /BUSY/);
  for (const foreign of [
    { ...owner, account: 'foreign' },
    { ...owner, project: 'foreign' },
    { ...owner, run: 'foreign' },
    { ...owner, provider: 'foreign' },
  ]) {
    assert.throws(() => sessions.browserLease(foreign, session), /NO_SESSION/);
    assert.throws(() => sessions.beginClose(foreign, session), /NO_SESSION/);
  }
  const ordinary = sessions.open(owner, 'ordinary');
  assert.throws(
    () =>
      sessions.bindBrowserLease(owner, ordinary, {
        ...lease,
        sessionId: ordinary.sessionId,
        targetId: ordinary.targetId,
      }),
    /NO_SESSION/,
  );
});

await test('controlled close and idle expiry retain cleanup obligations and target occupancy without a lease', () => {
  const { sessions, clock } = fixture(),
    session = sessions.openControlled(owner, 'target');
  assert.throws(() => sessions.close(owner, session), /CLEANUP_REQUIRED/);
  assert.throws(() => sessions.openControlled(owner, 'target'), /BUSY/);
  const obligation = sessions.pendingCleanup()[0];
  assert.ok(obligation);
  assert.equal(obligation.reason, 'closing');
  assert.equal(obligation.lease, null);
  assert.equal(obligation.busy, false);
  assert.ok(Object.isFrozen(obligation));
  clock.advance(60_001);
  assert.throws(() => sessions.require(owner, session), /FENCED/);
  assert.throws(() => sessions.openControlled(owner, 'target'), /BUSY/);
  // Host-owned explicit proof, not a model operation or an inference from lease=null.
  sessions.releaseAfterQuiescence(owner, session);
  const next = sessions.openControlled(owner, 'target');
  bind(sessions, next);
  clock.advance(60_001);
  const expired = sessions.pendingCleanup()[0];
  assert.ok(expired);
  assert.equal(expired.reason, 'idle');
  assert.ok(expired.lease);
  assert.throws(() => sessions.openControlled(owner, 'target'), /BUSY/);
  assert.throws(() => sessions.browserLease(owner, next), /FENCED/);
});

await test('late acquire token remains available only for cleanup and physical work keeps occupancy', async () => {
  const { sessions, clock } = fixture(),
    session = sessions.openControlled(owner, 'target'),
    deadline = new OperationDeadline(1000, clock);
  let finish: (() => void) | undefined;
  const running = sessions.withSession(owner, session, deadline, async () => {
    await new Promise<void>((resolve) => {
      finish = resolve;
    });
    bind(sessions, session);
  });
  assert.ok(finish);
  const expired = assert.rejects(running, DeadlineExceeded);
  clock.advance(1001);
  const pending = sessions.pendingCleanup()[0];
  assert.ok(pending);
  assert.equal(pending.lease, null);
  assert.equal(pending.busy, true);
  assert.throws(() => sessions.releaseAfterQuiescence(owner, session), /BUSY/);
  finish();
  await expired;
  deadline.dispose();
  const settled = sessions.pendingCleanup()[0];
  assert.ok(settled?.lease);
  assert.equal(settled.busy, false);
  assert.equal(settled.reason, 'fenced');
  assert.throws(() => sessions.browserLease(owner, session), /FENCED/);
  assert.throws(() => sessions.openControlled(owner, 'target'), /BUSY/);
  sessions.releaseAfterQuiescence(owner, session);
  const next = sessions.openControlled({ ...owner, run: 'new-run' }, 'target');
  assert.throws(() => sessions.browserLease(owner, next), /NO_SESSION/);
});

await test('browser references resolve exact latest owner-bound frame/snapshot and transformed points only', () => {
  const { sessions, clock } = fixture(),
    session = sessions.openControlled(owner, 'target');
  bind(sessions, session);
  const source = browserObservation(clock),
    observed = sessions.recordBrowserObservation(owner, session, source);
  assert.equal('image' in observed, false);
  assert.equal('bytes' in observed.frame, false);
  const resolved = sessions.prepareBrowserTarget(owner, session, {
    kind: 'point',
    frameId: observed.frame.frameId,
    x: 10,
    y: 20,
  });
  assert.deepEqual(resolved.target, { kind: 'point', x: 25, y: 50 });
  assert.equal(resolved.expected.documentId, 'document-one');
  assert.deepEqual(
    sessions.prepareBrowserTarget(owner, session, {
      kind: 'element',
      snapshotId: 'snapshot-one',
      elementId: 'element-one',
    }).target,
    { kind: 'element', snapshotId: 'snapshot-one', elementId: 'element-one' },
  );
  assert.throws(() =>
    sessions.prepareBrowserTarget(owner, session, {
      kind: 'point',
      frameId: observed.frame.frameId,
      x: 100,
      y: 0,
    }),
  );
  assert.throws(
    () =>
      sessions.prepareBrowserTarget({ ...owner, run: 'other' }, session, {
        kind: 'point',
        frameId: observed.frame.frameId,
        x: 0,
        y: 0,
      }),
    /NO_SESSION/,
  );
  assert.throws(
    () =>
      sessions.prepareBrowserTarget(owner, session, {
        kind: 'element',
        snapshotId: 'snapshot-one',
        elementId: 'same-text-unseen',
      }),
    /STALE_OBSERVATION/,
  );
  const next = sessions.recordBrowserObservation(
    owner,
    session,
    browserObservation(clock, 'two'),
  );
  assert.throws(
    () => sessions.resolveBrowserFrame(owner, session, observed.frame.frameId),
    /STALE_OBSERVATION/,
  );
  assert.throws(
    () =>
      sessions.prepareBrowserTarget(owner, session, {
        kind: 'element',
        snapshotId: 'snapshot-one',
        elementId: 'element-one',
      }),
    /STALE_OBSERVATION/,
  );
  assert.equal(
    sessions.resolveBrowserFrame(owner, session, next.frame.frameId).documentId,
    'document-two',
  );
});

await test('only metadata-exposed nodes are actionable even when actual focused element was trimmed out', () => {
  const { sessions, clock } = fixture(),
    session = sessions.openControlled(owner, 'target');
  bind(sessions, session);
  const source = browserObservation(clock);
  assert.ok(source.snapshot);
  const trimmed = {
    ...source,
    snapshot: { ...source.snapshot, nodes: [], truncated: true },
  };
  const recorded = sessions.recordBrowserObservation(owner, session, trimmed);
  assert.equal(recorded.state.focusedElementId, 'element-one');
  assert.throws(
    () =>
      sessions.prepareBrowserTarget(owner, session, {
        kind: 'element',
        snapshotId: 'snapshot-one',
        elementId: 'element-one',
      }),
    /STALE_OBSERVATION/,
  );
  assert.equal(
    sessions.resolveBrowserFrame(owner, session, recorded.frame.frameId)
      .surfaceId,
    'tab-one',
  );
});

await test('browser frame/tree freshness uses earliest capture and monotonic expiry; invalidation retains lease', () => {
  const { sessions, clock } = fixture(),
    session = sessions.openControlled(owner, 'target');
  const lease = bind(sessions, session);
  const source = browserObservation(clock);
  assert.ok(source.snapshot);
  const observed = sessions.recordBrowserObservation(owner, session, {
    ...source,
    snapshot: {
      ...source.snapshot,
      capturedAt: new Date(clock.wallNow() - 4500).toISOString(),
    },
  });
  clock.wall -= 3_600_000;
  clock.advance(501);
  assert.throws(
    () => sessions.resolveBrowserFrame(owner, session, observed.frame.frameId),
    /STALE_OBSERVATION/,
  );
  const fresh = sessions.recordBrowserObservation(
    owner,
    session,
    browserObservation(clock),
  );
  sessions.invalidateBrowserObservations(owner, session);
  assert.throws(
    () => sessions.resolveBrowserFrame(owner, session, fresh.frame.frameId),
    /STALE_OBSERVATION/,
  );
  assert.equal(sessions.browserLease(owner, session).leaseId, lease.leaseId);
});

await test('window references are bounded latest-owner snapshots and allow explicit guarded quarantine lane', () => {
  const { sessions, clock } = fixture(),
    session = sessions.openControlled(owner, 'target');
  bind(sessions, session);
  const source = browserObservation(clock),
    state = {
      ...source.state,
      readiness: 'quarantined' as const,
      url: 'about:blank',
      origin: null,
      focusedElementId: null,
      requestedUrl: 'https://fixture.invalid/popup',
    };
  const windows = {
    windowsId: 'windows-one',
    capturedAt: new Date(clock.wallNow()).toISOString(),
    tabs: [{ state, title: 'Quarantined popup' }],
    truncated: false,
  };
  sessions.recordBrowserWindows(owner, session, windows);
  assert.equal(
    sessions.resolveBrowserWindow(
      owner,
      session,
      windows.windowsId,
      state.surfaceId,
    ).readiness,
    'quarantined',
  );
  assert.throws(
    () =>
      sessions.resolveBrowserWindow(
        { ...owner, provider: 'foreign' },
        session,
        windows.windowsId,
        state.surfaceId,
      ),
    /NO_SESSION/,
  );
  sessions.recordBrowserWindows(owner, session, {
    ...windows,
    windowsId: 'windows-two',
  });
  assert.throws(
    () =>
      sessions.resolveBrowserWindow(
        owner,
        session,
        windows.windowsId,
        state.surfaceId,
      ),
    /STALE_OBSERVATION/,
  );
  assert.throws(
    () =>
      sessions.resolveBrowserWindow(
        owner,
        session,
        'windows-two',
        'unseen-tab',
      ),
    /STALE_OBSERVATION/,
  );
  sessions.invalidateBrowserObservations(owner, session);
  assert.throws(
    () =>
      sessions.resolveBrowserWindow(
        owner,
        session,
        'windows-two',
        state.surfaceId,
      ),
    /STALE_OBSERVATION/,
  );
  sessions.recordBrowserWindows(owner, session, {
    ...windows,
    windowsId: 'windows-three',
  });
  clock.advance(5000);
  assert.throws(
    () =>
      sessions.resolveBrowserWindow(
        owner,
        session,
        'windows-three',
        state.surfaceId,
      ),
    /STALE_OBSERVATION/,
  );
});

await test('controlled busy close retains resources until operation settles and counts against capacity', async () => {
  const clock = new FakeClock();
  let next = 0;
  const sessions = new ComputerSessions({
    generation: 'generation',
    id: () => 'id-' + next++,
    maxSessions: 1,
    clock,
  });
  const session = sessions.openControlled(owner, 'target');
  bind(sessions, session);
  const deadline = new OperationDeadline(1000, clock);
  let finish: (() => void) | undefined;
  const operation = sessions.withSession(
    owner,
    session,
    deadline,
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
  );
  assert.ok(finish);
  assert.equal(sessions.beginClose(owner, session).busy, true);
  assert.throws(() => sessions.releaseAfterQuiescence(owner, session), /BUSY/);
  assert.throws(() => sessions.openControlled(owner, 'another-target'), /BUSY/);
  const closed = assert.rejects(operation, /FENCED/);
  finish();
  await closed;
  deadline.dispose();
  assert.equal(sessions.pendingCleanup()[0]?.busy, false);
  assert.throws(() => sessions.openControlled(owner, 'another-target'), /BUSY/);
  sessions.releaseAfterQuiescence(owner, session);
  assert.equal(
    sessions.openControlled(owner, 'another-target').targetId,
    'another-target',
  );
});
