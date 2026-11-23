import assert from 'node:assert/strict';
import test from 'node:test';
import { ComputerSessions, SessionError } from './sessions.ts';
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
