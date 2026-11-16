import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { Arrival, Socket } from 'plowshare-client-ts/binding/connection';
import { PROJECT_CREATOR_HINT, RootRefused, rooter } from './rooter.ts';

type SocketEvent = Arrival & { code?: number; reason?: string };

/** A socket the test closes by hand, as the server closes a refused claim. */
function refusingSocket(closing: { code?: number; reason?: string }): Socket {
  let onClose: ((event: SocketEvent) => void) | undefined;
  return {
    send: () => undefined,
    close: () => undefined,
    addEventListener: (
      type: string,
      listener: (event: SocketEvent) => void,
    ) => {
      if (type === 'close') {
        onClose = listener;
        queueMicrotask(() => onClose?.({ ...closing, data: undefined }));
      }
    },
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

await test('unexpected file loss reconnects presence, and explicit release cancels a pending reconnect', async () => {
  let closeFirst: (() => void) | undefined;
  let opened = 0;
  let closeCurrent: (() => void) | undefined;
  let restored: (() => void) | undefined;
  const recovery = new Promise<void>((resolve) => {
    restored = resolve;
  });
  const held = rooter({
    open: async () => {
      opened++;
      const index = opened;
      let closed: ((event: SocketEvent) => void) | undefined;
      return {
        send() {},
        close() {
          closed?.({ data: undefined, code: 1000 });
        },
        addEventListener(type: string, listener: (event: SocketEvent) => void) {
          if (type === 'message')
            queueMicrotask(() =>
              listener({
                data: JSON.stringify({ ready: true, project: claim.project }),
              }),
            );
          if (type === 'close') {
            closed = listener;
            closeCurrent = () => listener({ data: undefined, code: 1006 });
            if (index === 1) closeFirst = closeCurrent;
          }
        },
      };
    },
    answering: () => async (request) => ({
      id: request.id,
      outcome: 'refused',
      sentence: 'No file operation was requested.',
    }),
    onLost() {},
    onRestored: () => restored?.(),
    reconnect: { attempts: 2, delayMs: 1 },
    requireReady: true,
    patience: 1,
  });
  await held.root(claim);
  assert.ok(closeFirst);
  closeFirst();
  await recovery;
  assert.equal(opened, 2);
  assert.deepEqual(held.current(), claim);
  assert.ok(closeCurrent);
  closeCurrent();
  await held.release();
  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal(opened, 2);
});
