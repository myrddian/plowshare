import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { test } from 'node:test';
import { publishRelayBlob, readRelayBlob } from './relay-blobs.ts';

const bytes = new Uint8Array([0, 1, 255]);
const descriptor = {
  sha256: createHash('sha256').update(bytes).digest('hex'),
  byteLength: bytes.length,
  mediaType: 'application/octet-stream',
};
const request = {
  requestId: '11111111-1111-1111-1111-111111111111',
  project: 'atlas',
  topic: 'media.capture',
  occurredAt: '2026-10-22T00:00:00Z',
  mediaType: descriptor.mediaType,
};
const receipt = {
  publication: {
    requestId: request.requestId,
    project: request.project,
    topic: request.topic,
    position: '1',
    publishedAt: request.occurredAt,
  },
  blob: descriptor,
};
const read = {
  project: request.project,
  topic: request.topic,
  group: 'workers',
  eventId: request.requestId,
};
const origin = 'https://relay.invalid';

await test('binary publish retains original identity and checks its receipt', async () => {
  let calls = 0;
  const http: typeof fetch = async (url, init) => {
    calls++;
    assert.ok(url instanceof URL);
    assert.equal(url.href, `${origin}/v1/relay/blobs`);
    assert.equal(init?.redirect, 'error');
    assert.deepEqual(init.headers, { Authorization: 'Bearer fixture' });
    assert.ok(init.body instanceof FormData);
    assert.equal(init.body.get('requestId'), request.requestId);
    const file = init.body.get('file');
    assert.ok(file instanceof Blob);
    assert.deepEqual(new Uint8Array(await file.arrayBuffer()), bytes);
    return new Response(JSON.stringify(receipt));
  };
  assert.deepEqual(
    await publishRelayBlob(origin, 'fixture', request, bytes, http),
    receipt,
  );
  assert.equal(calls, 1);
  await assert.rejects(
    publishRelayBlob(
      origin,
      'fixture',
      request,
      bytes,
      async () =>
        new Response(
          JSON.stringify({
            ...receipt,
            blob: { ...descriptor, sha256: 'a'.repeat(64) },
          }),
        ),
    ),
    /different receipt/,
  );
});

await test('publication refusal and uncertain delivery are single attempts', async () => {
  for (const status of [302, 503]) {
    let calls = 0;
    await assert.rejects(
      publishRelayBlob(
        origin,
        'fixture',
        request,
        bytes,
        async (_url, init) => {
          calls++;
          assert.equal(init?.redirect, 'error');
          return new Response('', { status });
        },
      ),
    );
    assert.equal(calls, 1);
  }
  let calls = 0;
  await assert.rejects(
    publishRelayBlob(origin, 'fixture', request, bytes, async () => {
      calls++;
      throw new Error('delivery unknown');
    }),
    /delivery unknown/,
  );
  assert.equal(calls, 1);
});

await test('download uses publication authority and verifies bytes, headers and bounds', async () => {
  const http: typeof fetch = async (url, init) => {
    assert.ok(url instanceof URL);
    assert.equal(url.searchParams.get('eventId'), read.eventId);
    assert.equal(url.searchParams.has('sha256'), false);
    assert.equal(init?.redirect, 'error');
    return new Response(bytes, {
      headers: { 'Content-Type': 'application/octet-stream' },
    });
  };
  assert.deepEqual(
    await readRelayBlob(origin, 'fixture', read, descriptor, http),
    Buffer.from(bytes),
  );
  for (const body of [
    new Uint8Array([0, 1]),
    new Uint8Array([3, 2, 1]),
    new Uint8Array([0, 1, 255, 0]),
  ]) {
    await assert.rejects(
      readRelayBlob(
        origin,
        'fixture',
        read,
        descriptor,
        async () =>
          new Response(body, {
            headers: { 'Content-Type': 'application/octet-stream' },
          }),
      ),
    );
  }
  await assert.rejects(
    readRelayBlob(
      origin,
      'fixture',
      read,
      descriptor,
      async () =>
        new Response(bytes, { headers: { 'Content-Type': 'image/png' } }),
    ),
    /headers differ/,
  );
});

await test('invalid metadata fails before network and receipts have a hard bound', async () => {
  const unused: typeof fetch = async () => {
    assert.fail('must not send');
  };
  await assert.rejects(
    publishRelayBlob(
      origin,
      'fixture',
      { ...request, mediaType: 'image/*' },
      bytes,
      unused,
    ),
  );
  await assert.rejects(
    readRelayBlob(
      origin,
      'fixture',
      { ...read, eventId: '../file' },
      descriptor,
      unused,
    ),
  );
  await assert.rejects(
    publishRelayBlob(
      origin,
      'fixture',
      request,
      bytes,
      async () => new Response(' '.repeat(16385)),
    ),
    /exceeds its bound/,
  );
});
