import assert from 'node:assert/strict';
import test from 'node:test';
import { decodeToolCall } from 'plowshare-client-ts';
import { createComputerProvider, computerDeclarations } from './provider.ts';
import { admissions, binding, metadata } from './provider.test-support.ts';
import { browserProviderFixture as fixture } from './browser-provider-fixture.test-support.ts';
import { FakeClock } from './contracts.test-support.ts';

await test('browser credential wait cannot dispatch expired action references', async () => {
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

await test('browser credential wait cannot dispatch expired readonly subtree references', async () => {
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

await test('P1 catalogue stays observation-only; P2 requires exact explicitly bound capabilities', async () => {
  assert.deepEqual(
    computerDeclarations().map((value) => value.name),
    ['computer_targets', 'computer_open', 'computer_observe', 'computer_close'],
  );
  assert.equal(
    computerDeclarations().some((value) => value.inputSchema !== undefined),
    false,
  );
  const f = await fixture();
  try {
    assert.equal(f.provider.declarations.length, 11);
    assert.equal(
      f.provider.declarations.find((value) => value.name === 'computer_observe')
        ?.parameters.length,
      0,
    );
    await assert.rejects(
      createComputerProvider({
        connection: f.connection,
        binding,
        journal: f.journal,
        backends: [f.backend],
        browserControls: [f.backend],
        generation: 'g',
        origin: 'https://fixture.invalid',
        currentBearer: async () => 'fixture',
      }),
      /explicitly configured together/,
    );
    await assert.rejects(
      createComputerProvider({
        connection: f.connection,
        binding,
        journal: f.journal,
        backends: [f.backend],
        browserControls: [f.backend],
        browserTools: ['type'],
        generation: 'g',
        origin: 'https://fixture.invalid',
        currentBearer: async () => 'fixture',
      }),
      /match the configured backend/,
    );
  } finally {
    await f.cleanup();
  }
});

await test('SDK poll admits a structured frame and exact next-action pixels without bare capture', async () => {
  const f = await fixture();
  try {
    const session = await f.open(),
      initial = await f.observe(session);
    const result = await f.invoke('type', {
      ...session,
      target: initial.target,
      mode: 'replace',
      text: 'hello🙂',
    });
    assert.equal(result.state, 'COMPLETED');
    assert.equal(metadata(result)['actionOutcome'], 'DISPATCHED');
    assert.equal(metadata(result)['observationOutcome'], 'CAPTURED');
    assert.equal(result.content?.parts[1]?.kind, 'IMAGE');
    assert.equal(result.content?.parts[1]?.use, 'OBSERVATION');
    assert.equal(result.admissions?.[0]?.sha256.length, 64);
    assert.equal(f.backend.inputs, 1);
    assert.equal(f.backend.bareCaptureCalls, 0);
    assert.equal(f.backend.captureCalls, 2);
    assert.equal(f.backend.performed[0]?.action.kind, 'type');
    assert.equal(f.journal.rows.size, 3);
    assert.ok(
      [...f.journal.rows.values()].every((row) => row.phase === 'done'),
    );
  } finally {
    await f.cleanup();
  }
});

await test('stale/foreign references and SDK schema authority overrides perform zero input', async () => {
  const f = await fixture();
  try {
    const session = await f.open(),
      initial = await f.observe(session);
    for (const args of [
      {
        ...session,
        target: { ...initial.target, elementId: 'foreign' },
        mode: 'insert',
        text: 'x',
      },
      {
        ...session,
        generation: 'old',
        target: initial.target,
        mode: 'insert',
        text: 'x',
      },
    ])
      assert.equal((await f.invoke('type', args)).state, 'REJECTED');
    const invalid = f.request('type', {
      ...session,
      target: initial.target,
      mode: 'insert',
      text: 'x',
      owner: 'other',
    });
    f.wire.calls.push(invalid);
    await assert.rejects(f.provider.provider.poll());
    assert.equal(f.backend.inputs, 0);
    assert.equal(f.journal.rows.has(invalid.invocationId), false);
    const foreign = decodeToolCall(
      JSON.stringify({
        ...f.request('type', {
          ...session,
          target: initial.target,
          mode: 'insert',
          text: 'x',
        }),
        account: 'other-owner',
      }),
    );
    assert.equal((await f.dispatch(foreign)).state, 'REJECTED');
    assert.equal(f.backend.inputs, 0);
  } finally {
    await f.cleanup();
  }
});

await test('image allowance and durable intent refusal both precede mutation dispatch', async () => {
  const capped = await fixture({ maxImagesPerRun: 1 });
  try {
    const session = await capped.open(),
      observed = await capped.observe(session);
    const result = await capped.invoke('type', {
      ...session,
      target: observed.target,
      mode: 'insert',
      text: 'x',
    });
    assert.equal(result.state, 'REJECTED');
    assert.equal(metadata(result)['code'], 'FRAME_LIMIT');
    assert.equal(capped.backend.inputs, 0);
  } finally {
    await capped.cleanup();
  }
  const f = await fixture();
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    f.journal.save = async () => {
      throw new Error('fixture disk refusal');
    };
    await assert.rejects(
      f.invoke('type', {
        ...session,
        target: observed.target,
        mode: 'insert',
        text: 'x',
      }),
    );
    assert.equal(f.backend.inputs, 0);
  } finally {
    await f.cleanup();
  }
});

await test('known dispatched input plus capture failure remains COMPLETED text-only', async () => {
  const f = await fixture();
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    f.backend.postObservationFailure = 'OBSERVATION_FAILED';
    f.backend.dialogEvent = {
      type: 'confirm',
      decision: 'accept',
      message: 'Save?',
      messageTruncated: false,
    };
    const result = await f.invoke('type', {
      ...session,
      target: observed.target,
      mode: 'insert',
      text: 'once',
    });
    assert.equal(result.state, 'COMPLETED');
    assert.equal(metadata(result)['actionOutcome'], 'DISPATCHED');
    assert.equal(metadata(result)['observationOutcome'], 'FAILED');
    assert.deepEqual(metadata(result)['dialog'], f.backend.dialogEvent);
    assert.equal(
      result.content?.parts.some((part) => part.kind === 'IMAGE'),
      false,
    );
    assert.equal(f.backend.inputs, 1);
    assert.equal(f.backend.captureCalls, 1);
  } finally {
    await f.cleanup();
  }
});

await test('known dispatched input plus lost admission reply does not recapture or repeat input', async () => {
  let uploads = 0;
  const f = await fixture({
    http: async (...args) => {
      uploads++;
      if (uploads > 1) throw new Error('fixture response lost');
      return admissions()(...args);
    },
  });
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    f.backend.dialogEvent = {
      type: 'prompt',
      decision: 'accept',
      message: '\u0001'.repeat(1024),
      messageTruncated: true,
    };
    const result = await f.invoke('type', {
      ...session,
      target: observed.target,
      mode: 'insert',
      text: 'once',
    });
    assert.equal(result.state, 'COMPLETED');
    assert.equal(metadata(result)['actionOutcome'], 'DISPATCHED');
    assert.equal(metadata(result)['observationOutcome'], 'FAILED');
    assert.deepEqual(metadata(result)['dialog'], f.backend.dialogEvent);
    assert.ok(result.text.length <= 12000);
    assert.equal(
      result.content?.parts.some((part) => part.kind === 'IMAGE'),
      false,
    );
    assert.equal(uploads, 2);
    assert.equal(f.backend.inputs, 1);
    assert.equal(f.backend.captureCalls, 2);
  } finally {
    await f.cleanup();
  }
});

await test('duplicate SDK delivery reuses original action receipt and admitted frame', async () => {
  const f = await fixture();
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    const request = f.request('type', {
      ...session,
      target: observed.target,
      mode: 'insert',
      text: 'once',
    });
    const first = await f.dispatch(request),
      second = await f.dispatch(request);
    assert.deepEqual(second, first);
    assert.equal(f.backend.inputs, 1);
    assert.equal(f.backend.captureCalls, 2);
  } finally {
    await f.cleanup();
  }
});

await test('a dialog prompt value additionally needs the configured type capability', async () => {
  const f = await fixture({ tools: ['windows', 'pointer'] });
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    const result = await f.invoke('pointer', {
      ...session,
      action: 'click',
      target: observed.target,
      dialogDecision: {
        expectedType: 'prompt',
        decision: 'accept',
        promptText: 'hidden typing',
      },
    });
    assert.equal(result.state, 'REJECTED');
    assert.equal(f.backend.inputs, 0);
  } finally {
    await f.cleanup();
  }
});

await test('local stop returns UNKNOWN while physical input and cleanup retain ownership', async () => {
  const f = await fixture();
  let finish = () => {};
  try {
    const session = await f.open(),
      observed = await f.observe(session),
      gate = f.backend.deferInput();
    finish = () => gate.resolve();
    let entered = () => {};
    const starting = new Promise<void>((resolve) => {
      entered = resolve;
    });
    f.backend.beforeInput = entered;
    const pending = f.invoke('type', {
      ...session,
      target: observed.target,
      mode: 'insert',
      text: 'once',
    });
    await starting;
    f.provider.stop();
    assert.equal((await pending).state, 'UNKNOWN');
    assert.equal(f.backend.inputs, 1);
    assert.equal(f.backend.hasLease, true);
    assert.throws(
      () =>
        f.provider.sessions.open(
          {
            account: 'owner',
            project: binding.project,
            provider: binding.provider,
            run: 'other',
          },
          f.backend.targetId,
        ),
      /BUSY/,
    );
    finish();
    await f.cleanup();
    assert.equal(f.backend.hasLease, false);
  } finally {
    finish();
    f.connection.close();
  }
});

await test('unknown thrown action failure is fenced and never reported as a preinput refusal', async () => {
  const f = await fixture();
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    f.backend.inputFailure = new Error('fixture unclassified action failure');
    const result = await f.invoke('type', {
      ...session,
      target: observed.target,
      mode: 'insert',
      text: 'once',
    });
    assert.equal(result.state, 'UNKNOWN');
    assert.equal(metadata(result)['actionOutcome'], 'UNCERTAIN');
    assert.equal(f.backend.inputs, 1);
    assert.equal(
      (
        await f.invoke('type', {
          ...session,
          target: observed.target,
          mode: 'insert',
          text: 'again',
        })
      ).state,
      'REJECTED',
    );
    assert.equal(f.backend.inputs, 1);
  } finally {
    await f.cleanup();
  }
});
