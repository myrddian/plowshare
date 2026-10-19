import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  mkdtemp,
  readFile,
  writeFile,
  stat,
  symlink,
  rm,
  readdir,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { PrivateImportRecoveryFiles } from './import-recovery-files.ts';
import {
  decodeImportRecovery,
  RECOVERY_FORMAT,
} from './import-recovery-contract.ts';

const id = '11111111-1111-4111-8111-111111111111';
const body = Buffer.from('\uFEFF# Safety 🔎\r\n');
const hash = createHash('sha256').update(body).digest('hex');
const record = decodeImportRecovery({
  format: RECOVERY_FORMAT,
  context: 'selected-account-and-scope',
  requestId: id,
  scope: { kind: 'personal', includeGlobal: false },
  plan: {
    bundle: {
      format: 'plowshare-information-bundle/1',
      id,
      documents: [
        {
          descriptor: {
            resource: id,
            revision: id,
            ordinal: 1,
            sourceName: 'source.md',
            title: 'Source',
            path: 'documents/source.md',
            mediaType: 'text/markdown',
            contentHash: hash,
            tags: [],
            provenance: {
              owner: 'foreign-owner',
              kind: 'source',
              createdAt: '2026-10-16T00:00:00Z',
              sourceHash: hash,
              dependencies: [],
              evidence: [],
            },
          },
          bytesBase64: body.toString('base64'),
        },
      ],
      relationships: [],
      relationshipCoverage: 'included-endpoints-only',
    },
    destinations: [{ resource: id, name: 'Source', mode: 'create' }],
    types: [],
    reuse: [],
    restoreRelationships: false,
  },
});

await test('recovery save resolves with a complete private file and leaves no temporary file', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-recovery-'));
  try {
    const file = join(directory, 'recovery.json');
    await new PrivateImportRecoveryFiles().save(file, record);
    assert.deepEqual(JSON.parse(await readFile(file, 'utf8')), record);
    if (process.platform !== 'win32')
      assert.equal((await stat(file)).mode & 0o777, 0o600);
    assert.deepEqual(await readdir(directory), ['recovery.json']);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

await test(
  'atomic save replaces the chosen entry without following a destination symlink',
  {
    // Windows file symlinks require developer mode or administrative privileges.
    skip: process.platform === 'win32',
  },
  async () => {
    const directory = await mkdtemp(join(tmpdir(), 'plowshare-recovery-'));
    try {
      const target = join(directory, 'untouched.txt'),
        file = join(directory, 'recovery.json');
      await writeFile(target, 'retain this file');
      await symlink(target, file);
      await new PrivateImportRecoveryFiles().save(file, record);
      assert.equal(await readFile(target, 'utf8'), 'retain this file');
      assert.deepEqual(JSON.parse(await readFile(file, 'utf8')), record);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  },
);

await test('recovery files reject malformed commands and persistence failures remain failures', async () => {
  assert.throws(() =>
    decodeImportRecovery({ ...record, requestId: 'foreign' }),
  );
  assert.throws(() => decodeImportRecovery({ ...record, unknown: true }));
  assert.throws(() => decodeImportRecovery({ ...record, context: '' }));
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-recovery-'));
  try {
    await assert.rejects(
      new PrivateImportRecoveryFiles().save(
        join(directory, 'missing', 'recovery.json'),
        record,
      ),
    );
    assert.deepEqual(await readdir(directory), []);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
