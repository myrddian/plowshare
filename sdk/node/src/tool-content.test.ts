import { symlink } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  FileToolJournal,
  admitToolPng,
  admitToolImage,
  imageToolResult,
  pngToolResult,
  toolTextPart,
} from '../build/tool-content.js';
import type { ToolCall } from 'plowshare-client-ts';
import { invocationImageRequest } from './image-admissions.ts';

const call: ToolCall = {
  schema: 'plowshare-tool/3',
  resultProfile: 'content/1',
  invocationId: '11111111-1111-1111-1111-111111111111',
  project: 'atlas',
  provider: 'browser',
  tool: 'capture',
  account: 'owner',
  run: 'run',
  call: 'call',
  deadline: '2099-01-01T00:00:00Z',
  arguments: {},
};
const bytes = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]);
const sha = createHash('sha256').update(bytes).digest('hex');

await test('Node capture admission retains the original receipt and private ready journal across restart', async () => {
  const receipt = {
    request: await invocationImageRequest(call.invocationId, 0),
    project: call.project,
    owner: call.account,
    producer: 'provider',
    imageId: 'img_' + sha.slice(0, 32),
    mimeType: 'image/png',
    width: 2,
    height: 3,
    byteLength: bytes.length,
    sha256: sha,
    admittedAt: '2026-10-23T00:00:00Z',
  };
  let uploads = 0;
  const http: typeof fetch = async (_url, options) => {
    uploads++;
    assert.equal(options?.redirect, 'error');
    assert.ok(options.body instanceof FormData);
    assert.equal(options.body.get('invocation'), call.invocationId);
    return new Response(JSON.stringify(receipt));
  };
  const image = await admitToolPng(
    'https://capture.invalid',
    'fixture',
    call,
    0,
    bytes,
    'OBSERVATION',
    http,
  );
  const result = pngToolResult(
    call,
    [toolTextPart('before'), image.part, toolTextPart('after')],
    [image.receipt],
  );
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-tool-content-'));
  try {
    const identity = 'a'.repeat(64),
      journal = await FileToolJournal.open(directory, identity);
    await journal.save({
      invocationId: call.invocationId,
      request: JSON.stringify(call),
      phase: 'ready',
      result,
      occurredAt: null,
    });
    const reopened = await FileToolJournal.open(directory, identity);
    assert.deepEqual(await reopened.all(), await journal.all());
    assert.equal(uploads, 1);
    const saved = await readFile(join(directory, identity + '.json'), 'utf8');
    assert.ok(saved.includes('sha256'));
    assert.ok(!saved.includes('Bearer'));
    assert.equal(
      (await stat(join(directory, identity + '.json'))).mode & 0o777,
      0o600,
    );
    assert.throws(() => pngToolResult(call, [image.part], []), /receipt/);
    assert.throws(
      () =>
        pngToolResult(
          { ...call, invocationId: '22222222-2222-2222-2222-222222222222' },
          [image.part],
          [image.receipt],
        ),
      /receipt/,
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

await test('uncertain image upload is one attempt, and a mismatched receipt cannot become a result', async () => {
  let calls = 0;
  const http: typeof fetch = async () => {
    calls++;
    throw new Error('unknown delivery');
  };
  await assert.rejects(
    admitToolPng(
      'https://capture.invalid',
      'fixture',
      call,
      0,
      bytes,
      'OBSERVATION',
      http,
    ),
    /unknown delivery/,
  );
  assert.equal(calls, 1);
  await assert.rejects(
    admitToolPng(
      'https://capture.invalid',
      'fixture',
      { ...call, schema: 'plowshare-tool/1' },
      0,
      bytes,
      'OBSERVATION',
      http,
    ),
  );
  assert.equal(calls, 1);
});

await test('journal failure fences stale memory until the durable checkpoint is reopened', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-journal-fence-'));
  try {
    const identity = 'b'.repeat(64),
      journal = await FileToolJournal.open(directory, identity);
    await symlink(
      join(directory, 'missing'),
      join(directory, identity + '.json'),
    );
    await assert.rejects(
      journal.save({
        invocationId: call.invocationId,
        request: JSON.stringify(call),
        phase: 'executing',
        result: null,
        occurredAt: null,
      }),
    );
    await assert.rejects(journal.all(), /reopen/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

await test('JPEG/WebP receipts survive typed results and journal recovery without recapture', async () => {
  for (const mimeType of ['image/jpeg', 'image/webp'] as const) {
    const request = await invocationImageRequest(call.invocationId, 0);
    const receipt = {
      request,
      project: call.project,
      owner: call.account,
      producer: 'provider',
      imageId: 'img_' + sha.slice(0, 32),
      mimeType,
      width: 2,
      height: 3,
      byteLength: bytes.length,
      sha256: sha,
      admittedAt: '2026-10-23T00:00:00Z',
    };
    let uploads = 0;
    const admitted = await admitToolImage(
      'https://capture.invalid',
      'fixture',
      call,
      0,
      bytes,
      'OBSERVATION',
      async () => {
        uploads++;
        return new Response(JSON.stringify(receipt));
      },
    );
    const result = imageToolResult(call, [admitted.part], [admitted.receipt]);
    assert.equal(result.admissions?.[0]?.mimeType, mimeType);
    assert.throws(
      () => pngToolResult(call, [admitted.part], [admitted.receipt]),
      /PNG/,
    );
    const directory = await mkdtemp(join(tmpdir(), 'plowshare-formats-'));
    try {
      const identity = 'c'.repeat(64),
        journal = await FileToolJournal.open(directory, identity);
      await journal.save({
        invocationId: call.invocationId,
        request: JSON.stringify(call),
        phase: 'ready',
        result,
        occurredAt: null,
      });
      assert.deepEqual(
        await (await FileToolJournal.open(directory, identity)).all(),
        await journal.all(),
      );
      assert.equal(uploads, 1);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  }
});
