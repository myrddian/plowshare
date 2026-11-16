import assert from 'node:assert/strict';
import test from 'node:test';
import { decodeToolCall } from 'plowshare-client-ts';
import { nativeProviderFixture as fixture } from './native-provider-fixture.test-support.ts';
import { FakeNativeControlBackend } from './native-provider.test-support.ts';
import { FakeBrowserControlBackend } from './browser-provider.test-support.ts';
import { FakeClock } from './contracts.test-support.ts';
import {
  admissions,
  binding,
  metadata,
  record,
} from './provider.test-support.ts';
import { createComputerProvider } from './provider.ts';

await test('native SDK polling captures and acts through the original journal with exact receipt reuse', async () => {
  const f = await fixture();
  try {
    assert.equal(f.provider.declarations.length, 10);
    assert.equal(
      f.provider.declarations.some((row) => row.name === 'computer_navigate'),
      false,
    );
    const session = await f.open();
    const observed = await f.observe(session);
    const request = f.request('type', {
      ...session,
      target: observed.target,
      mode: 'replace',
      text: 'Hello🙂',
    });
    const result = await f.dispatch(request);
    assert.equal(result.state, 'COMPLETED');
    assert.equal(metadata(result)['actionOutcome'], 'DISPATCHED');
    assert.equal(metadata(result)['observationOutcome'], 'CAPTURED');
    assert.equal(result.content?.parts[1]?.kind, 'IMAGE');
    assert.equal(result.admissions?.[0]?.sha256.length, 64);
    assert.equal(f.backend.performed[0]?.invocationId, request.invocationId);
    assert.equal(f.backend.inputs, 1);
    assert.equal(f.backend.bareCaptureCalls, 0);
    assert.deepEqual(await f.dispatch(request), result);
    assert.equal(f.backend.inputs, 1);
    assert.equal(f.backend.captureCalls, 2);
  } finally {
    await f.cleanup();
  }
});

await test('native frame points retain signed desktop affine coordinates', async () => {
  const f = await fixture();
  try {
    const session = await f.open();
    const observed = await f.observe(session);
    const result = await f.invoke('pointer', {
      ...session,
      action: 'click',
      target: { kind: 'point', frameId: observed.frameId, x: 10, y: 20 },
    });
    assert.equal(result.state, 'COMPLETED');
    const action = f.backend.performed[0]?.action;
    assert.equal(action?.kind, 'click');
    if (action?.kind !== 'click') throw new Error('Expected native click');
    assert.deepEqual(action.target, { kind: 'point', x: -290, y: 70 });
  } finally {
    await f.cleanup();
  }
});

await test('one SDK catalogue dispatches isolated browser and native sessions to their exact ports', async () => {
  const clock = new FakeClock();
  const browser = new FakeBrowserControlBackend(clock);
  const native = new FakeNativeControlBackend(clock);
  const f = await fixture({
    clock,
    backend: native,
    additionalBackends: [browser],
    browserControls: [browser],
    browserTools: browser.browserControl.allowedTools,
  });
  try {
    assert.equal(f.provider.declarations.length, 11);
    const nativeSession = await f.open();
    const nativeFrame = await f.observe(nativeSession);
    const browserSession = await f.open(browser.targetId);
    const browserFrame = await f.observe(browserSession, browser.surfaceId);
    assert.equal(
      (
        await f.invoke('type', {
          ...nativeSession,
          target: nativeFrame.target,
          mode: 'insert',
          text: 'Native🙂',
        })
      ).state,
      'COMPLETED',
    );
    assert.equal(
      (
        await f.invoke('type', {
          ...browserSession,
          target: browserFrame.target,
          mode: 'insert',
          text: 'Browser🙂',
        })
      ).state,
      'COMPLETED',
    );
    assert.equal(native.inputs, 1);
    assert.equal(browser.inputs, 1);
    const targets = metadata(await f.invoke('targets'))['targets'];
    assert.ok(Array.isArray(targets));
    const nativeTarget = targets
      .map(record)
      .find((row) => row['targetId'] === native.targetId);
    assert.ok(nativeTarget);
    assert.ok(Array.isArray(nativeTarget['nativeTools']));
    assert.equal(nativeTarget['browserTools'], undefined);
  } finally {
    await f.cleanup();
  }
});

await test('a shared browser dialog schema does not grant native automatic dialog handling', async () => {
  const clock = new FakeClock();
  const browser = new FakeBrowserControlBackend(clock);
  const f = await fixture({
    clock,
    additionalBackends: [browser],
    browserControls: [browser],
    browserTools: browser.browserControl.allowedTools,
  });
  try {
    const session = await f.open();
    const observed = await f.observe(session);
    const result = await f.invoke('pointer', {
      ...session,
      action: 'click',
      target: observed.target,
      dialogDecision: { expectedType: 'confirm', decision: 'accept' },
    });
    assert.equal(result.state, 'REJECTED');
    assert.equal(f.backend.inputs, 0);
    assert.equal(browser.inputs, 0);
  } finally {
    await f.cleanup();
  }
});

await test('foreign native ownership, stale references and schema authority overrides perform zero input', async () => {
  const f = await fixture();
  try {
    const session = await f.open();
    const observed = await f.observe(session);
    const args = {
      ...session,
      target: observed.target,
      mode: 'insert',
      text: 'x',
    };
    const foreign = decodeToolCall(
      JSON.stringify({ ...f.request('type', args), account: 'another-owner' }),
    );
    assert.equal((await f.dispatch(foreign)).state, 'REJECTED');
    assert.equal(
      (await f.invoke('type', { ...args, generation: 'old' })).state,
      'REJECTED',
    );
    const overridden = f.request('type', { ...args, owner: 'forged' });
    f.wire.calls.push(overridden);
    await assert.rejects(f.provider.provider.poll());
    assert.equal(f.journal.rows.has(overridden.invocationId), false);
    assert.equal(f.backend.inputs, 0);
  } finally {
    await f.cleanup();
  }
});

await test('unproved native readiness and image allowance refuse before input', async () => {
  const f = await fixture({ maxImagesPerRun: 1 });
  try {
    const session = await f.open();
    const frame = await f.observe(session);
    const result = await f.invoke('type', {
      ...session,
      target: frame.target,
      mode: 'insert',
      text: 'x',
    });
    assert.equal(result.state, 'REJECTED');
    assert.equal(metadata(result)['code'], 'FRAME_LIMIT');
    assert.equal(f.backend.inputs, 0);
  } finally {
    await f.cleanup();
  }
  const clock = new FakeClock();
  const backend = new FakeNativeControlBackend(clock);
  backend.availability = 'unknown';
  const unknown = await fixture({ clock, backend });
  try {
    const session = await unknown.open();
    const observed = await unknown.observe(session);
    const result = await unknown.invoke('pointer', {
      ...session,
      action: 'click',
      target: observed.target,
    });
    assert.equal(result.state, 'REJECTED');
    assert.equal(backend.inputs, 0);
  } finally {
    await unknown.cleanup();
  }
});

await test('known native input remains text evidence when its post-action upload fails', async () => {
  const admit = admissions();
  let uploads = 0;
  const f = await fixture({
    http: async (input, init) => {
      if (++uploads === 2) throw new Error('Synthetic upload failure');
      return admit(input, init);
    },
  });
  try {
    const session = await f.open();
    const observed = await f.observe(session);
    const result = await f.invoke('type', {
      ...session,
      target: observed.target,
      mode: 'insert',
      text: 'once',
    });
    assert.equal(result.state, 'COMPLETED');
    assert.equal(metadata(result)['actionOutcome'], 'DISPATCHED');
    assert.equal(metadata(result)['observationOutcome'], 'FAILED');
    assert.equal(
      result.content?.parts.some((part) => part.kind === 'IMAGE'),
      false,
    );
    assert.equal(f.backend.inputs, 1);
    assert.equal(uploads, 2);
  } finally {
    await f.cleanup();
  }
});

await test('native SDK composition refuses foreign or uncoordinated backends before SDK effects', async () => {
  const f = await fixture();
  try {
    const base = {
      connection: f.connection,
      binding,
      journal: f.journal,
      backends: [f.backend],
      generation: 'g',
      origin: 'https://fixture.invalid',
      currentBearer: async () => 'fixture',
      nativeTools: f.tools,
    };
    await assert.rejects(
      createComputerProvider({
        ...base,
        nativeControls: [new FakeNativeControlBackend(f.clock)],
      }),
    );
    await assert.rejects(
      createComputerProvider({
        ...base,
        nativeControls: [f.backend],
        backends: [
          f.backend,
          {
            targets: async () => [],
            capture: async () => {
              throw new Error('Unexpected capture');
            },
          },
        ],
      }),
    );
    assert.equal(f.backend.acquireCalls, 0);
    assert.equal(f.wire.publications.length, 0);
  } finally {
    await f.cleanup();
  }
});

await test('credential wait cannot dispatch expired native frame references within a still-active action budget', async () => {
  const clock = new FakeClock();
  let lookups = 0;
  const f = await fixture({
    clock,
    currentBearer: async () => {
      if (++lookups === 2) clock.advance(6000);
      return 'fixture';
    },
  });
  try {
    const session = await f.open();
    const observed = await f.observe(session);
    const result = await f.invoke('type', {
      ...session,
      target: observed.target,
      mode: 'insert',
      text: 'stale',
    });
    assert.equal(result.state, 'REJECTED');
    assert.equal(metadata(result)['code'], 'STALE_OBSERVATION');
    assert.equal(f.backend.inputs, 0);
    assert.equal(f.backend.captureCalls, 1);
  } finally {
    await f.cleanup();
  }
});

await test('credential wait cannot dispatch an expired native subtree root for readonly observation', async () => {
  const clock = new FakeClock();
  let lookups = 0;
  const f = await fixture({
    clock,
    currentBearer: async () => {
      if (++lookups === 2) clock.advance(6000);
      return 'fixture';
    },
  });
  try {
    const session = await f.open();
    const observed = await f.observe(session);
    const result = await f.invoke('observe', {
      ...session,
      surfaceId: f.backend.surfaceId,
      root: observed.target,
    });
    assert.equal(result.state, 'REJECTED');
    assert.equal(metadata(result)['code'], 'STALE_OBSERVATION');
    assert.equal(f.backend.inputs, 0);
    assert.equal(f.backend.captureCalls, 1);
  } finally {
    await f.cleanup();
  }
});
