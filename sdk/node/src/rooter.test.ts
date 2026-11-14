import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { Socket } from 'plowshare-client-ts/binding/connection';
import { PROJECT_CREATOR_HINT, RootRefused, rooter } from './rooter.ts';

/** A socket the test closes by hand, as the server closes a refused claim. */
function refusingSocket(closing: { code?: number; reason?: string }): Socket {
  let onClose: ((event: unknown) => void) | undefined;
  return {
    send: () => undefined,
    close: () => undefined,
    addEventListener: ((type: string, listener: (event: never) => void) => {
      if (type === 'close') {
        onClose = listener as (event: unknown) => void;
        queueMicrotask(() => onClose?.(closing));
      }
    }) as Socket['addEventListener'],
  };
}

const claim = { project: 'annex', root: '/work/annex', machine: 'desk' };

async function refusal(closing: {
  code?: number;
  reason?: string;
}): Promise<unknown> {
  const held = rooter({
    open: async () => refusingSocket(closing),
    answering: () => async (request) => ({
      id: request.id,
      outcome: 'unavailable',
      sentence: 'unused',
    }),
    onLost: () => undefined,
    patience: 10,
    readiness: 1_000,
  });
  return held.root(claim).then(
    () => undefined,
    (refused: unknown) => refused,
  );
}

await test('a refused claim rejects with RootRefused carrying the close code', async () => {
  const refused = await refusal({
    code: 1008,
    reason: "this account may not root 'annex'",
  });
  assert.ok(refused instanceof RootRefused);
  assert.equal(refused.code, 1008);
  assert.equal(refused.message, "this account may not root 'annex'");
});

await test('a refusal without a code keeps the message and an undefined code', async () => {
  const refused = await refusal({});
  assert.ok(refused instanceof RootRefused);
  assert.equal(refused.code, undefined);
  assert.match(refused.message, /closed before the server took the claim/);
});

await test('the creator hint names the administrator grant', () => {
  assert.match(PROJECT_CREATOR_HINT, /project creator role/);
});
