import assert from 'node:assert/strict';
import test from 'node:test';
import { randomUUID } from 'node:crypto';
import type { NativeArming, NativeArmStatus } from './native-arming.ts';
import type { NativeLease } from './native-control.ts';
import { decodeOwner, decodeCloseArguments } from './contracts.ts';
import { NativeToolRuntime } from './native-runtime.ts';
import { ComputerSessions } from './sessions.ts';
import { FakeNativeControlBackend } from './native-provider.test-support.ts';
import { FakeClock } from './contracts.test-support.ts';
import { OperationDeadline } from './deadline.ts';
import {
  binding,
  call,
  metadata,
  admissions,
  record,
} from './provider.test-support.ts';
import { NativeArmingRequests } from './native-arming-requests.ts';
import { nativeProviderFixture } from './native-provider-fixture.test-support.ts';

class ArmedBackend extends FakeNativeControlBackend {
  requests = 0;
  statuses = 0;
  revocations = 0;
  gate: Promise<void> | undefined;
  private result(lease: NativeLease): NativeArmStatus {
    return {
      state: 'UNARMED',
      binding: {
        lease,
        helperGeneration: '11111111-2222-3333-4444-555555555555',
      },
    };
  }
  readonly nativeArming: NativeArming = {
    request: async (lease, request) => {
      this.requests++;
      await this.gate;
      return {
        state: 'PENDING',
        binding: this.result(lease).binding,
        requestId: request.requestId,
        challengeId: 'aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb',
        requestedAt: '2026-11-17T00:00:00Z',
        expiresAt: '2026-11-17T00:00:30Z',
      };
    },
    status: async (lease) => {
      this.statuses++;
      await this.gate;
      return this.result(lease);
    },
    revoke: (lease) => {
      this.revocations++;
      return Promise.resolve({
        state: 'REVOKED',
        binding: this.result(lease).binding,
        requestId: null,
        armGeneration: null,
        revokedAt: '2026-11-17T00:00:00Z',
        reason: 'HOST_REVOKED',
      });
    },
  };
}
async function fixture() {
  const clock = new FakeClock(),
    backend = new ArmedBackend(clock),
    deadline = new OperationDeadline(2000, clock);
  const runtime = new NativeToolRuntime({
    binding,
    controls: [backend],
    armingControls: [backend],
    tools: backend.allowedTools,
    clock,
    sessions: new ComputerSessions({
      generation: 'provider-generation',
      id: randomUUID,
      clock,
    }),
    origin: 'https://fixture.invalid',
    currentBearer: async () => 'fixture-bearer',
    http: admissions(),
    captureLimits: { maxBytes: 1_048_576, maxDimension: 1600 },
    observationReserveMs: 100,
    cleanupBudgetMs: 2000,
    idleCheckMs: 1000,
  });
  const request = call(clock, 'open', { targetId: backend.targetId });
  const owner = decodeOwner({
    account: request.account,
    project: request.project,
    run: request.run,
    provider: request.provider,
  });
  const opened = await runtime.open(
      request,
      backend.targetId,
      backend,
      deadline,
    ),
    row = record(metadata(opened)['session']),
    session = decodeCloseArguments({
      sessionId: row['sessionId'],
      generation: row['generation'],
    });
  return {
    runtime,
    arming: runtime.nativeArming,
    backend,
    deadline,
    owner,
    session,
    async close() {
      backend.gate = undefined;
      runtime.stop();
      await runtime.drain(deadline);
      deadline.dispose();
      // The scheduled cleanup owner disposes its separate budget when the same
      // physical promise settles, which can follow drain's waiter by a microtask.
      await Promise.resolve();
      await Promise.resolve();
      assert.equal(clock.pendingTimers(), 0);
    },
  };
}
const intent = () => ({ requestId: randomUUID(), durationMs: 30_000 });

await test('host arming resolves only exact current owner/session, with zero helper calls for forged identities', async () => {
  const f = await fixture();
  try {
    const inventory = f.arming.sessions();
    assert.equal(inventory.length, 1);
    assert.ok(
      Object.isFrozen(inventory) && Object.isFrozen(inventory[0]?.owner),
    );
    for (const key of ['account', 'project', 'provider', 'run'] as const) {
      await assert.rejects(
        f.arming.request(
          { ...f.owner, [key]: 'foreign' },
          f.session,
          intent(),
          f.deadline,
        ),
      );
      await assert.rejects(
        f.arming.revoke(
          { ...f.owner, [key]: 'foreign' },
          f.session,
          f.deadline,
        ),
      );
    }
    await assert.rejects(
      f.arming.status(
        f.owner,
        { ...f.session, generation: 'foreign' },
        f.deadline,
      ),
    );
    await assert.rejects(
      f.arming.request(
        f.owner,
        f.session,
        { ...intent(), durationMs: 30_001 },
        f.deadline,
      ),
    );
    assert.equal(
      f.backend.requests + f.backend.statuses + f.backend.revocations,
      0,
    );
    assert.equal(
      (await f.arming.request(f.owner, f.session, intent(), f.deadline)).state,
      'PENDING',
    );
    assert.equal(f.backend.requests, 1);
  } finally {
    await f.close();
  }
});

await test('host revoke bypasses busy normal work; stop prevents later host requests', async () => {
  const f = await fixture();
  let release = () => {};
  f.backend.gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const pending = assert.rejects(
    f.arming.status(f.owner, f.session, f.deadline),
    /STALE_OBSERVATION/,
  );
  try {
    await assert.rejects(
      f.arming.request(f.owner, f.session, intent(), f.deadline),
      /BUSY/,
    );
    assert.equal(
      (await f.arming.revoke(f.owner, f.session, f.deadline)).state,
      'REVOKED',
    );
    assert.equal(f.backend.revocations, 1);
    release();
    await pending;
    f.runtime.stop();
    await assert.rejects(
      f.arming.status(f.owner, f.session, f.deadline),
      /NO_SESSION/,
    );
  } finally {
    release();
    await f.close();
  }
});

await test('SDK revoke suppresses pixels and stale references from deferred input and ignored-abort upload', async () => {
  for (const phase of ['input', 'upload'] as const) {
    const clock = new FakeClock(),
      backend = new ArmedBackend(clock);
    let release = () => {},
      entered = () => {},
      uploads = 0,
      uploadSignal: AbortSignal | null | undefined;
    const held = new Promise<void>((resolve) => {
        release = resolve;
      }),
      seen = new Promise<void>((resolve) => {
        entered = resolve;
      });
    const f = await nativeProviderFixture({
      clock,
      backend,
      armingControls: [backend],
      http: async (...args) => {
        uploads++;
        if (phase === 'upload' && uploads === 2) {
          uploadSignal = args[1]?.signal;
          entered();
          await held;
        }
        return admissions()(...args);
      },
    });
    const original = backend.perform.bind(backend);
    if (phase === 'input')
      backend.perform = async (...args) => {
        const result = await original(...args);
        entered();
        await held;
        return result;
      };
    try {
      const session = await f.open(),
        observed = await f.observe(session),
        request = f.request('type', {
          ...session,
          target: observed.target,
          mode: 'insert',
          text: 'once',
        });
      let finished = false;
      const pending = f.dispatch(request).then((result) => {
        finished = true;
        return result;
      });
      await seen;
      const arming = f.provider.nativeArming;
      assert.ok(arming);
      const owner = decodeOwner({
        account: request.account,
        project: request.project,
        provider: request.provider,
        run: request.run,
      });
      const deadline = new OperationDeadline(2000, clock);
      try {
        assert.equal(
          (await arming.revoke(owner, session, deadline)).state,
          'REVOKED',
        );
      } finally {
        deadline.dispose();
      }
      assert.equal(finished, false);
      assert.equal(backend.inputs, 1);
      if (phase === 'upload') assert.equal(uploadSignal?.aborted, true);
      assert.throws(
        () => f.provider.sessions.releaseAfterQuiescence(owner, session),
        /BUSY/,
      );
      release();
      const result = await pending;
      assert.equal(result.state, 'COMPLETED');
      assert.equal(metadata(result)['actionOutcome'], 'DISPATCHED');
      assert.equal(metadata(result)['observationOutcome'], 'FAILED');
      assert.equal(metadata(result)['frame'], undefined);
      assert.equal(metadata(result)['image'], undefined);
      assert.ok(
        !JSON.stringify(result).includes('proofEpoch') &&
          !JSON.stringify(result).includes('helperGeneration'),
      );
      assert.deepEqual(await f.dispatch(request), result);
      assert.equal(backend.inputs, 1);
      assert.equal(uploads, phase === 'input' ? 1 : 2);
    } finally {
      release();
      await f.cleanup();
    }
    assert.equal(backend.releaseCalls, 1);
  }
});

await test('foreground arming presents once per session and never replays a lost response', async () => {
  const f = await fixture(),
    requests = new NativeArmingRequests(30_000);
  let attempts = 0;
  const lost = {
    ...f.arming,
    async request(): Promise<NativeArmStatus> {
      attempts++;
      throw new Error('lost');
    },
  };
  try {
    await assert.rejects(requests.present(lost, f.deadline), /lost/);
    await requests.present(lost, f.deadline);
    assert.equal(attempts, 1);
    assert.throws(() => new NativeArmingRequests(30_001));
  } finally {
    await f.close();
  }
});

await test('malformed and failed post-dispatch arming responses fence and clean the original lease once', async () => {
  for (const malformed of [false, true]) {
    const f = await fixture();
    // Deliberately violate the injected port at its runtime boundary, without an
    // unchecked type cast. A typed backend promise is not proof of valid output.
    Object.defineProperty(f.backend.nativeArming, 'status', {
      value: async () => {
        if (malformed) return { state: 'UNARMED' };
        throw new Error('Response lost after changing local state');
      },
    });
    try {
      await assert.rejects(f.arming.status(f.owner, f.session, f.deadline));
      await assert.rejects(
        f.arming.request(f.owner, f.session, intent(), f.deadline),
      );
      assert.equal(f.backend.requests, 0);
    } finally {
      await f.close();
    }
    assert.equal(f.backend.releaseCalls, 1);
  }
});

await test('delayed revoke remains owned after host timeout and cannot publish a late status', async () => {
  const f = await fixture(),
    deadline = new OperationDeadline(1000);
  let release = () => {};
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  const original = f.backend.nativeArming.revoke.bind(f.backend.nativeArming);
  Object.defineProperty(f.backend.nativeArming, 'revoke', {
    value: async (...args: Parameters<NativeArming['revoke']>) => {
      await held;
      return original(...args);
    },
  });
  const work = assert.rejects(f.arming.revoke(f.owner, f.session, deadline));
  try {
    deadline.cancel();
    await work;
    assert.equal(f.arming.sessions()[0]?.closing, true);
    release();
    await Promise.resolve();
    await Promise.resolve();
  } finally {
    release();
    deadline.dispose();
    await f.close();
  }
});
