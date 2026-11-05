import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  admitInvocationImage,
  invocationImageRequest,
} from './image-admissions.ts';

await test('explicit binary upload sends the stable slot once and verifies its receipt', async () => {
  const request = await invocationImageRequest(
    '11111111-1111-1111-1111-111111111111',
    0,
  );
  const receipt = {
    request,
    project: 'atlas',
    owner: 'owner',
    producer: 'producer',
    imageId: `img_${'a'.repeat(32)}`,
    mimeType: 'image/png',
    width: 2,
    height: 3,
    byteLength: 1,
    sha256: 'a'.repeat(64),
    admittedAt: '2026-10-22T00:00:00Z',
  };
  let calls = 0;
  const http: typeof fetch = async (url, init) => {
    calls++;
    assert.ok(url instanceof URL);
    assert.equal(url.href, 'https://admission.invalid/v1/images');
    assert.equal(init?.redirect, 'error');
    assert.ok(init?.body instanceof FormData);
    assert.equal(init.body.get('requestId'), request.requestId);
    assert.equal(init.body.get('profile'), 'invocation-image/1');
    assert.equal(init.body.has('project'), false);
    return new Response(JSON.stringify(receipt));
  };
  assert.deepEqual(
    await admitInvocationImage(
      'https://admission.invalid',
      'fixture',
      request,
      new Uint8Array([1]),
      'capture.png',
      http,
    ),
    receipt,
  );
  assert.equal(calls, 1);
  const refused: typeof fetch = async () => {
    calls++;
    return new Response('', { status: 503 });
  };
  await assert.rejects(
    admitInvocationImage(
      'https://admission.invalid',
      'fixture',
      request,
      new Uint8Array([1]),
      'capture.png',
      refused,
    ),
  );
  assert.equal(calls, 2);
});
