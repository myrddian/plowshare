import assert from 'node:assert/strict';
import test from 'node:test';
import { NativeSessionReferences } from './native-session-references.ts';
import { NativeControlError } from './native-control.ts';
import type {
  NativeLease,
  NativeObservation,
  NativeWindows,
} from './native-control.ts';
import { ComputerSessions } from './sessions.ts';
import type { ComputerSession } from './sessions.ts';
import { DeadlineExceeded, OperationDeadline } from './deadline.ts';
import { FakeClock, image, owner } from './contracts.test-support.ts';

function fixture() {
  const clock = new FakeClock();
  let id = 0;
  const sessions = new ComputerSessions({
    generation: 'generation',
    id: () => 'session-' + id++,
    clock,
  });
  return {
    clock,
    sessions,
    refs: new NativeSessionReferences({ sessions, clock }),
  };
}
function observe(clock: FakeClock, suffix = 'one'): NativeObservation {
  const guard = {
    surfaceId: 'display-' + suffix,
    applicationId: 'application-' + suffix,
    windowId: 'window-' + suffix,
    layoutGeneration: 'layout-' + suffix,
    focusGeneration: 'focus-' + suffix,
    displayGeneration: 'displays-' + suffix,
    interactionGeneration: 'interaction-' + suffix,
  };
  return {
    image: { ...image(clock), layoutGeneration: guard.layoutGeneration },
    state: {
      ...guard,
      availability: 'ready',
      permissions: {
        screenCapture: true,
        accessibility: true,
        eventPosting: true,
        eventListening: true,
      },
      secureInput: 'clear',
      interference: 'clear',
      focusKnown: true,
      focusedElementId: 'element-' + suffix,
      display: {
        displayId: 'display-' + suffix,
        bounds: { x: -500, y: 0, width: 1000, height: 1000 },
        scaleX: 2,
        scaleY: 1,
        rotation: 0,
      },
      applicationLabel: 'Fixture',
      bundleId: 'com.fixture.allowed',
    },
    snapshot: {
      snapshotId: 'snapshot-' + suffix,
      rootElementId: null,
      guard,
      capturedAt: new Date(clock.wallNow()).toISOString(),
      kind: 'ax',
      approximate: true,
      truncated: false,
      nodes: [
        {
          elementId: 'element-' + suffix,
          parentElementId: null,
          role: 'AXTextField',
          subrole: null,
          name: 'Field',
          editable: true,
          focused: true,
          enabled: true,
          secure: false,
          bounds: { x: -300, y: 50, width: 100, height: 30 },
        },
      ],
    },
  };
}
function bind(
  refs: NativeSessionReferences,
  session: ComputerSession,
): NativeLease {
  const lease = {
    leaseId: 'lease-' + session.sessionId,
    targetId: session.targetId,
    sessionId: session.sessionId,
    generation: session.generation,
  };
  refs.bindLease(owner, session, lease);
  return lease;
}
function windows(clock: FakeClock): NativeWindows {
  return {
    windowsId: 'windows-one',
    capturedAt: new Date(clock.wallNow()).toISOString(),
    truncated: false,
    windows: [
      {
        surfaceId: 'focus-reference',
        applicationId: 'application-one',
        windowId: 'window-one',
        captureSurfaceId: 'display-one',
        applicationLabel: 'Fixture',
        bundleId: 'com.fixture.allowed',
        title: 'Fixture',
        bounds: { x: -400, y: 0, width: 500, height: 400 },
        focused: true,
        state: 'available',
      },
    ],
  };
}

await test('native lease/ref ownership is separate from browser state and strict across every owner field', () => {
  const { refs, sessions } = fixture(),
    session = refs.open(owner, 'desktop');
  assert.throws(() => refs.lease(owner, session), /NO_LEASE/);
  const invalid = {
    leaseId: 'lease',
    targetId: 'other',
    sessionId: session.sessionId,
    generation: session.generation,
  };
  assert.throws(() => refs.bindLease(owner, session, invalid), /NO_LEASE/);
  const lease = bind(refs, session);
  assert.equal(refs.lease(owner, session).leaseId, lease.leaseId);
  assert.throws(() => refs.bindLease(owner, session, lease), /BUSY/);
  assert.throws(() => sessions.browserLease(owner, session), /NO_LEASE/);
  for (const changed of [
    { ...owner, account: 'other' },
    { ...owner, project: 'other' },
    { ...owner, provider: 'other' },
    { ...owner, run: 'other' },
  ]) {
    assert.throws(() => refs.lease(changed, session), /NO_SESSION/);
    assert.throws(() => refs.beginClose(changed, session), /NO_SESSION/);
  }
  assert.equal(refs.hasSession({ ...session, generation: 'old' }), false);
});

await test('native affine points stay signed; only latest visible AX and frame refs can be prepared', () => {
  const { refs, clock } = fixture(),
    session = refs.open(owner, 'desktop');
  bind(refs, session);
  const first = refs.recordObservation(owner, session, observe(clock));
  assert.equal('image' in first, false);
  assert.equal('bytes' in first.frame, false);
  assert.deepEqual(
    refs.prepareTarget(owner, session, {
      kind: 'point',
      frameId: first.frame.frameId,
      x: 10,
      y: 20,
    }).target,
    { kind: 'point', x: -290, y: 70 },
  );
  const target = {
    kind: 'element' as const,
    snapshotId: 'snapshot-one',
    elementId: 'element-one',
  };
  assert.equal(refs.resolveElement(owner, session, target).role, 'AXTextField');
  assert.deepEqual(refs.prepareTarget(owner, session, target).target, target);
  assert.throws(() =>
    refs.prepareTarget(owner, session, {
      kind: 'point',
      frameId: first.frame.frameId,
      x: 100,
      y: 0,
    }),
  );
  refs.recordObservation(owner, session, observe(clock, 'two'));
  assert.throws(
    () => refs.resolveFrame(owner, session, first.frame.frameId),
    /STALE_OBSERVATION/,
  );
  assert.throws(
    () => refs.resolveElement(owner, session, target),
    /STALE_OBSERVATION/,
  );
});

await test('trimmed/foreign/reopened AX references cannot be adopted and immutable metadata excludes bytes', () => {
  const { refs, clock } = fixture(),
    session = refs.open(owner, 'desktop');
  bind(refs, session);
  const source = observe(clock);
  assert.ok(source.snapshot);
  const observed = refs.recordObservation(owner, session, {
    ...source,
    snapshot: { ...source.snapshot, nodes: [], truncated: true },
  });
  assert.equal(observed.state.focusedElementId, 'element-one');
  assert.throws(
    () =>
      refs.prepareTarget(owner, session, {
        kind: 'element',
        snapshotId: 'snapshot-one',
        elementId: 'element-one',
      }),
    /STALE_OBSERVATION/,
  );
  assert.throws(
    () =>
      refs.resolveFrame(
        { ...owner, run: 'foreign' },
        session,
        observed.frame.frameId,
      ),
    /NO_SESSION/,
  );
  assert.ok(Object.isFrozen(observed.state.display.bounds));
  assert.ok(Object.isFrozen(observed.snapshot?.nodes));
  refs.beginClose(owner, session);
  refs.releaseAfterQuiescence(owner, session);
  const next = refs.open(owner, 'desktop');
  bind(refs, next);
  assert.throws(
    () => refs.resolveFrame(owner, next, observed.frame.frameId),
    /STALE_OBSERVATION/,
  );
  assert.throws(() => refs.cleanupLease(owner, session), /NO_SESSION/);
});

await test('unknown native readiness still permits bounded AX subtree observation but never input preparation', () => {
  const { refs, clock } = fixture(),
    session = refs.open(owner, 'desktop');
  bind(refs, session);
  const source = observe(clock);
  const observed = refs.recordObservation(owner, session, {
    ...source,
    state: { ...source.state, availability: 'unknown' },
  });
  const target = {
    kind: 'element' as const,
    snapshotId: 'snapshot-one',
    elementId: 'element-one',
  };
  assert.equal(
    refs.resolveElement(owner, session, target).elementId,
    'element-one',
  );
  for (const action of [
    () => refs.resolveFrame(owner, session, observed.frame.frameId),
    () => refs.prepareTarget(owner, session, target),
  ])
    assert.throws(
      action,
      (error: unknown) =>
        error instanceof NativeControlError && error.code === 'SESSION_UNKNOWN',
    );
});

await test('native metadata freshness uses earliest image/tree time and monotonic expiry with separate windows refs', () => {
  const { refs, clock } = fixture(),
    session = refs.open(owner, 'desktop');
  bind(refs, session);
  const source = observe(clock);
  assert.ok(source.snapshot);
  const observed = refs.recordObservation(owner, session, {
    ...source,
    snapshot: {
      ...source.snapshot,
      capturedAt: new Date(clock.wallNow() - 4500).toISOString(),
    },
  });
  clock.wall -= 3600000;
  clock.advance(501);
  assert.throws(
    () => refs.resolveFrame(owner, session, observed.frame.frameId),
    /STALE_OBSERVATION/,
  );
  const first = refs.recordWindows(owner, session, windows(clock));
  assert.equal(
    refs.resolveWindow(owner, session, first.windowsId, 'focus-reference')
      .captureSurfaceId,
    'display-one',
  );
  refs.recordWindows(owner, session, {
    ...windows(clock),
    windowsId: 'windows-two',
  });
  assert.throws(
    () =>
      refs.resolveWindow(owner, session, first.windowsId, 'focus-reference'),
    /STALE_OBSERVATION/,
  );
  refs.invalidate(owner, session);
  assert.throws(
    () => refs.resolveWindow(owner, session, 'windows-two', 'focus-reference'),
    /STALE_OBSERVATION/,
  );
  assert.ok(refs.lease(owner, session));
});

await test('idle/close cleanup retains native capability and common target occupancy until explicit quiescence', () => {
  const { refs, sessions, clock } = fixture(),
    session = refs.open(owner, 'desktop');
  const lease = bind(refs, session);
  clock.advance(60001);
  const pending = refs.pendingCleanup()[0];
  assert.ok(pending);
  assert.equal(pending.reason, 'idle');
  assert.equal(pending.lease?.leaseId, lease.leaseId);
  assert.equal(sessions.pendingCleanup()[0]?.lease, null);
  assert.throws(() => refs.open(owner, 'desktop'), /BUSY/);
  assert.throws(() => refs.lease(owner, session), /FENCED/);
  assert.equal(refs.cleanupLease(owner, session)?.leaseId, lease.leaseId);
  refs.releaseAfterQuiescence(owner, session);
  assert.equal(refs.pendingCleanup().length, 0);
  assert.equal(refs.open(owner, 'desktop').targetId, 'desktop');
});

await test('late native acquisition is cleanup-only and busy physical work prevents local resource release', async () => {
  const { refs, sessions, clock } = fixture(),
    session = refs.open(owner, 'desktop'),
    deadline = new OperationDeadline(1000, clock);
  let finish: (() => void) | undefined;
  const physical = sessions.withSession(owner, session, deadline, async () => {
    await new Promise<void>((resolve) => {
      finish = resolve;
    });
    bind(refs, session);
  });
  assert.ok(finish);
  const failed = assert.rejects(physical, DeadlineExceeded);
  clock.advance(1001);
  assert.equal(refs.pendingCleanup()[0]?.lease, null);
  assert.throws(() => refs.releaseAfterQuiescence(owner, session), /BUSY/);
  finish();
  await failed;
  deadline.dispose();
  assert.ok(refs.cleanupLease(owner, session));
  assert.throws(() => refs.lease(owner, session), /FENCED/);
  assert.throws(() => refs.open(owner, 'desktop'), /BUSY/);
});

await test('native cleanup listings do not adopt another controller or browser session', () => {
  const { refs, sessions, clock } = fixture(),
    native = refs.open(owner, 'desktop');
  bind(refs, native);
  const browser = sessions.openControlled(owner, 'browser');
  clock.advance(60001);
  assert.equal(sessions.pendingCleanup().length, 2);
  assert.deepEqual(
    refs.pendingCleanup().map((entry) => entry.session.sessionId),
    [native.sessionId],
  );
  assert.throws(() => refs.require(owner, browser), /NO_SESSION/);
});
